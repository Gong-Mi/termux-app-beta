#!/usr/bin/env python3
"""Fail-closed APK identity gate for the Termux beta certification environment.

This is *artifact* evidence only. It answers exactly one question: "do the bytes
named by these CI sidecars look like the beta APK this line claims to certify?"
It never answers anything about runtime behaviour, compile filters, AOT/JIT
state or a device.

CLI
---
    python3 scripts/beta_artifact_gate.py \\
        --apk PATH --expected-head FULL_SHA --build-head PATH \\
        --sha256-file PATH --expected-cert-sha256 HEX \\
        --aapt PATH --apksigner PATH --output JSON_PATH

Exit codes
----------
    0  every gate passed
    1  at least one gate failed (bad artifact or bad head/cert input value)
    2  input or execution error (missing file, unexecutable tool, spawn
       failure, unwritable report)
In every non-zero case the JSON report is still written with "passed": false.

Gates
-----
    apk_readable                  APK exists, is non-empty, sha256 computed
    expected_head_format          --expected-head is 40 hex characters as given
    build_head_literal            build-head file contains *exactly one* full
                                  40-hex SHA and nothing else (no repair)
    head_match                    build-head literal == expected head, exactly
    apk_sha256_match              APK sha256 == digest in --sha256-file
    aapt_package_name             aapt badging package == com.termux
    aapt_version_code             versionCode == 1022
    aapt_version_name_beta        versionName contains "beta"
    aapt_not_debuggable           badging has no application-debuggable marker
    aapt_arm64_abi                badging native-code includes arm64-v8a
    zip_has_dex                   APK ZIP holds at least one classes*.dex
    dex_entries_valid             every classes*.dex parses as a DEX (magic,
                                  header, string_ids bounds, Adler-32 checksum)
    dex_has_termux_activity       Lcom/termux/app/TermuxActivity; is present in
                                  a real DEX string table (raw ZIP text never
                                  counts)
    expected_cert_sha256_format   --expected-cert-sha256 normalizes to 64 hex
    apksigner_verified            apksigner verify --print-certs exited 0
    cert_sha256_match             the only signer certificate SHA-256 digest
                                  equals the expected value

What a pass does NOT establish is enumerated in the report under
"claims_not_proven"; read it before quoting a pass anywhere.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
import zlib
from datetime import datetime, timezone
from pathlib import Path

GATE_SCHEMA = "beta-artifact-gate/1"
GATE_VERSION = "1"
LINE = "artifact"
DEFAULT_TOOL_TIMEOUT = 180.0

EXPECTED_PACKAGE = "com.termux"
EXPECTED_VERSION_CODE = 1022
EXPECTED_VERSION_NAME_SUBSTRING = "beta"
EXPECTED_ABI = "arm64-v8a"
ACTIVITY_DESCRIPTOR = "Lcom/termux/app/TermuxActivity;"

GIT_HEAD_RE = re.compile(r"[0-9a-fA-F]{40}")
SHA256_HEX_RE = re.compile(r"[0-9a-fA-F]{64}")
DEX_ENTRY_RE = re.compile(r"classes\d*\.dex")
DEX_MAGIC_RE = re.compile(rb"dex\n0[0-9]{2}\0")

DEX_HEADER_SIZE = 0x70
DEX_ENDIAN_TAG = 0x12345678
DEX_CHECKSUM_OFF = 8
DEX_SIGNATURE_OFF = 12
DEX_FILE_SIZE_OFF = 32
DEX_HEADER_SIZE_OFF = 36
DEX_ENDIAN_TAG_OFF = 40
DEX_STRING_IDS_SIZE_OFF = 56
DEX_STRING_IDS_OFF_OFF = 60

AAPT_GATES = (
    "aapt_package_name",
    "aapt_version_code",
    "aapt_version_name_beta",
    "aapt_not_debuggable",
    "aapt_arm64_abi",
)

EVIDENCE_LAYER = (
    "artifact-identity: static APK/ZIP/DEX structure, manifest values and signature "
    "fingerprint reported by local tools; no device, no runtime, no AOT/JIT measurement"
)

CLAIMS_NOT_PROVEN = [
    "Identity only: a matching package name (com.termux), versionCode (1022) and a "
    "versionName containing 'beta' do NOT prove the artifact was built from this fork "
    "at the expected head. Upstream or any other tree with the same manifest values "
    "would satisfy those gates.",
    "The build-head sidecar is CI-provided text and only one link in the provenance "
    "chain. This gate compares it to the expected head and hashes the APK; it cannot "
    "cryptographically bind the bytes to the commit.",
    "A test key fingerprint recorded here is not a production release identity. "
    "Passing the certificate gate says nothing about production signing; a test/debug "
    "key must never be reused as the production identity.",
    "Non-debuggable (no application-debuggable marker) is NOT proof of AOT compilation, "
    "of a 'speed' compile filter, or that zero JIT will happen: ART may legitimately mix "
    "interpretation, JIT and AOT for the same method set.",
    "Nothing here measures runtime: no pm compile result, no compile-filter state, no "
    "JIT thread activity and no recompilation count is established by this gate.",
    "The verification build (R8/minification disabled) and a minified release build are "
    "different artifacts; this gate does not replace keeping those two tracks separate.",
]


# --------------------------------------------------------------------------
# errors
# --------------------------------------------------------------------------


class DexFormatError(ValueError):
    """A DEX blob is structurally unusable, so its string table cannot be trusted."""


# --------------------------------------------------------------------------
# hashing helpers
# --------------------------------------------------------------------------


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path) -> str:
    digest = hashlib.sha256()
    with open(Path(path), "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


# --------------------------------------------------------------------------
# head parsing
# --------------------------------------------------------------------------


def is_valid_git_head(value) -> bool:
    """True only for a literal 40-hex-character string."""
    return isinstance(value, str) and bool(GIT_HEAD_RE.fullmatch(value))


def parse_build_head(text: str):
    """Return (literal, None) or (None, reason).

    The sidecar must contain exactly one full 40-hex SHA. Surrounding whitespace
    (a trailing newline from `git rev-parse HEAD > file`) is tolerated; anything
    else - a prefix, a second token, a shortened SHA - is rejected verbatim
    instead of being repaired.
    """
    if text is None:
        return None, "build-head sidecar could not be read (expected one full 40-hex SHA)"
    literal = text.strip()
    if not literal:
        return None, "build-head sidecar is empty (expected one full 40-hex SHA)"
    if not GIT_HEAD_RE.fullmatch(literal):
        return None, (
            "build-head must contain exactly one full 40-hex SHA and nothing else "
            "(got %d character(s): %r); malformed values are not repaired"
            % (len(literal), _clip(literal))
        )
    return literal, None


def _clip(text: str, limit: int = 120) -> str:
    return text if len(text) <= limit else text[:limit] + "...<clipped>"


# --------------------------------------------------------------------------
# certificate parsing
# --------------------------------------------------------------------------


def normalize_cert_sha256(value) -> str:
    """Normalize a certificate fingerprint: drop whitespace and colons, lowercase.

    Nothing else is repaired - a SHA-1 sized value or a broken token stays broken
    and fails the format gate.
    """
    if value is None:
        return ""
    return re.sub(r"[\s:]", "", str(value)).lower()


def is_valid_sha256_hex(value) -> bool:
    return isinstance(value, str) and bool(SHA256_HEX_RE.fullmatch(value))


CERT_DIGEST_LINE_RE = re.compile(r"^(?:Signer #(\d+)|V[0-9.]+ Signer:) certificate SHA-256 digest:\s*(\S.*?)\s*$")


def parse_apksigner_cert_digests(text: str) -> dict:
    """Parse `apksigner verify --print-certs` output.

    Returns {"digests": [{"signer", "raw", "normalized"}], "malformed": [raw...]}.
    Digest lines that do not normalize to 64 hex are reported as malformed rather
    than silently dropped.
    """
    digests = []
    malformed = []
    for raw_line in (text or "").splitlines():
        match = CERT_DIGEST_LINE_RE.match(raw_line.strip())
        if not match:
            continue
        signer = int(match.group(1) or "1")
        raw = match.group(2)
        normalized = normalize_cert_sha256(raw)
        if is_valid_sha256_hex(normalized):
            digests.append({"signer": signer, "raw": raw, "normalized": normalized})
        else:
            malformed.append(raw)
    return {"digests": digests, "malformed": malformed}


# --------------------------------------------------------------------------
# aapt badging parsing
# --------------------------------------------------------------------------


def parse_aapt_badging(text: str) -> dict:
    """Extract the manifest facts needed by the gates from `aapt dump badging`."""
    info = {
        "package": None,
        "version_code": None,
        "version_name": None,
        "debuggable": False,
        "debuggable_marker": None,
        "native_code": [],
        "launchable_activity": None,
        "sdk_version": None,
        "target_sdk_version": None,
        "package_line": None,
        "malformed": [],
    }
    for raw_line in (text or "").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("package:"):
            info["package_line"] = line
            name = re.search(r"name='([^']*)'", line)
            if name:
                info["package"] = name.group(1)
            code = re.search(r"versionCode='([^']*)'", line)
            if code:
                info["version_code"] = code.group(1)
            version = re.search(r"versionName='([^']*)'", line)
            if version:
                info["version_name"] = version.group(1)
        elif line.startswith("application-debuggable"):
            info["debuggable"] = True
            info["debuggable_marker"] = line
        elif line.startswith("native-code:"):
            info["native_code"] = re.findall(r"'([^']*)'", line)
        elif line.startswith("launchable-activity:"):
            activity = re.search(r"name='([^']*)'", line)
            if activity:
                info["launchable_activity"] = activity.group(1)
        elif line.startswith("sdkVersion:"):
            info["sdk_version"] = line.split(":", 1)[1].strip().strip("'")
        elif line.startswith("targetSdkVersion:"):
            info["target_sdk_version"] = line.split(":", 1)[1].strip().strip("'")

    if info["package_line"] is None:
        info["malformed"].append("missing package: line")
    if info["version_code"] is None:
        info["malformed"].append("missing versionCode in package: line")
    return info


# --------------------------------------------------------------------------
# DEX parsing
# --------------------------------------------------------------------------


def _u32(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 4 > len(data):
        raise DexFormatError("truncated u32 at offset %d" % offset)
    return int.from_bytes(data[offset : offset + 4], "little")


def _read_uleb128(data: bytes, offset: int):
    result = 0
    shift = 0
    while True:
        if offset >= len(data):
            raise DexFormatError("truncated uleb128 at offset %d" % offset)
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7
        if shift > 35:
            raise DexFormatError("uleb128 too long at offset %d" % offset)


def read_dex_header(data: bytes) -> dict:
    """Validate the DEX header and the string_ids table bounds."""
    if len(data) < DEX_HEADER_SIZE:
        raise DexFormatError("truncated DEX header: %d < %d bytes" % (len(data), DEX_HEADER_SIZE))
    magic = data[:8]
    if not DEX_MAGIC_RE.fullmatch(magic):
        raise DexFormatError("bad DEX magic %r" % (magic,))
    endian_tag = _u32(data, DEX_ENDIAN_TAG_OFF)
    if endian_tag != DEX_ENDIAN_TAG:
        raise DexFormatError("bad DEX endian tag 0x%08x" % endian_tag)
    header_size = _u32(data, DEX_HEADER_SIZE_OFF)
    if header_size < DEX_HEADER_SIZE:
        raise DexFormatError("DEX header_size %d < %d" % (header_size, DEX_HEADER_SIZE))
    file_size = _u32(data, DEX_FILE_SIZE_OFF)
    if file_size != len(data):
        raise DexFormatError("DEX file_size %d != actual %d bytes" % (file_size, len(data)))
    string_ids_size = _u32(data, DEX_STRING_IDS_SIZE_OFF)
    string_ids_off = _u32(data, DEX_STRING_IDS_OFF_OFF)
    if string_ids_size == 0:
        if string_ids_off != 0:
            raise DexFormatError("string_ids_off %d set with string_ids_size 0" % string_ids_off)
    elif string_ids_off < DEX_HEADER_SIZE or string_ids_off + 4 * string_ids_size > len(data):
        raise DexFormatError(
            "string_ids table out of bounds: size=%d off=%d file=%d"
            % (string_ids_size, string_ids_off, len(data))
        )
    return {
        "version": magic[4:7].decode("ascii", "replace"),
        "file_size": file_size,
        "string_ids_size": string_ids_size,
        "string_ids_off": string_ids_off,
        "checksum": _u32(data, DEX_CHECKSUM_OFF),
        "declared_checksum_ok": _u32(data, DEX_CHECKSUM_OFF)
        == (zlib.adler32(data[DEX_SIGNATURE_OFF:]) & 0xFFFFFFFF),
        "declared_signature_ok": data[DEX_SIGNATURE_OFF : DEX_SIGNATURE_OFF + 20]
        == hashlib.sha1(data[32:]).digest(),
    }


def _read_string_data_item(data: bytes, offset: int) -> str:
    utf16_size, position = _read_uleb128(data, offset)
    terminator = data.find(b"\x00", position)
    if terminator < 0:
        raise DexFormatError("string_data_item at %d has no NUL terminator" % offset)
    raw = data[position:terminator]
    if all(byte < 0x80 for byte in raw) and utf16_size != len(raw):
        raise DexFormatError(
            "string_data_item at %d declares utf16_size %d but holds %d ASCII bytes"
            % (offset, utf16_size, len(raw))
        )
    return raw.decode("utf-8", "replace")


def iter_dex_strings(data: bytes):
    """Yield the DEX string table. Raises DexFormatError on a malformed blob."""
    header = read_dex_header(data)
    if not header["declared_checksum_ok"]:
        raise DexFormatError(
            "DEX Adler-32 checksum 0x%08x does not match the file body"
            % header["checksum"]
        )
    for index in range(header["string_ids_size"]):
        offset = _u32(data, header["string_ids_off"] + 4 * index)
        if offset <= 0 or offset >= len(data):
            raise DexFormatError(
                "string_ids[%d] offset %d out of range (file %d bytes)"
                % (index, offset, len(data))
            )
        yield _read_string_data_item(data, offset)


def dex_strings(data: bytes) -> list:
    return list(iter_dex_strings(data))


def dex_has_string(data: bytes, needle: str) -> bool:
    """True when `needle` is an entry of the real DEX string table."""
    return any(text == needle for text in iter_dex_strings(data))


def dex_defined_classes(data: bytes) -> list:
    """Resolve class_defs -> type_ids -> string_ids, not mere references."""
    strings = dex_strings(data)
    type_count, type_off = _u32(data, 64), _u32(data, 68)
    class_count, class_off = _u32(data, 96), _u32(data, 100)
    for name, count, offset, width in (("type_ids", type_count, type_off, 4),
                                        ("class_defs", class_count, class_off, 32)):
        if count == 0:
            if offset != 0:
                raise DexFormatError(f"{name}: nonzero offset for an empty table")
        elif offset < DEX_HEADER_SIZE or offset + count * width > len(data):
            raise DexFormatError(f"{name} table out of bounds")
    types = []
    for index in range(type_count):
        string_index = _u32(data, type_off + 4 * index)
        if string_index >= len(strings):
            raise DexFormatError("type_ids descriptor index out of bounds")
        types.append(strings[string_index])
    defined = []
    for index in range(class_count):
        type_index = _u32(data, class_off + 32 * index)
        if type_index >= len(types):
            raise DexFormatError("class_defs class index out of bounds")
        defined.append(types[type_index])
    return defined


def dex_defines_class(data: bytes, descriptor: str) -> bool:
    return descriptor in dex_defined_classes(data)


def validate_dex(data: bytes) -> dict:
    """Structural validation. Never raises; reports valid=False with a reason."""
    detail = {
        "valid": False,
        "reason": "",
        "string_count": 0,
        "checksum_ok": False,
        "signature_ok": False,
        "version": None,
        "declared_size": None,
        "actual_size": len(data),
    }
    try:
        strings = dex_strings(data)
        dex_defined_classes(data)
    except DexFormatError as error:
        detail["reason"] = str(error)
        return detail
    header = read_dex_header(data)
    detail.update(
        {
            "valid": True,
            "reason": "ok",
            "string_count": len(strings),
            "checksum_ok": header["declared_checksum_ok"],
            "signature_ok": header["declared_signature_ok"],
            "version": header["version"],
            "declared_size": header["file_size"],
        }
    )
    return detail


def list_dex_entries(apk_path) -> list:
    """Root-level classes*.dex entries of an APK, in archive order."""
    with zipfile.ZipFile(Path(apk_path)) as archive:
        return [item.filename for item in archive.infolist() if DEX_ENTRY_RE.fullmatch(item.filename)]


def read_apk_dex_bytes(apk_path, entry_name: str) -> bytes:
    with zipfile.ZipFile(Path(apk_path)) as archive:
        return archive.read(entry_name)


# --------------------------------------------------------------------------
# process runner
# --------------------------------------------------------------------------


class SubprocessRunner:
    """Default runner: real process execution with a hard timeout."""

    def __init__(self, timeout: float = DEFAULT_TOOL_TIMEOUT):
        self.timeout = timeout

    def check(self, tool, label="tool"):
        """Return an error message when the tool cannot be executed at all."""
        candidate = Path(str(tool or ""))
        if not candidate.is_file():
            return "%s is not a file: %s" % (label, candidate)
        if not os.access(candidate, os.X_OK):
            return "%s is not executable: %s" % (label, candidate)
        return None

    def run(self, argv) -> dict:
        argv = [str(item) for item in argv]
        try:
            proc = subprocess.run(
                argv,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=self.timeout,
                check=False,
            )
        except subprocess.TimeoutExpired:
            return {"argv": argv, "rc": None, "stdout": "", "stderr": "",
                    "error": "timeout after %.0fs" % self.timeout}
        except (OSError, ValueError) as error:
            return {"argv": argv, "rc": None, "stdout": "", "stderr": "",
                    "error": "%s: %s" % (type(error).__name__, error)}
        return {
            "argv": argv,
            "rc": proc.returncode,
            "stdout": proc.stdout.decode("utf-8", "replace"),
            "stderr": proc.stderr.decode("utf-8", "replace"),
            "error": None,
        }


def _is_executable(path) -> bool:
    """Standalone executability probe (used by the default runner)."""
    candidate = Path(str(path or ""))
    return candidate.is_file() and os.access(candidate, os.X_OK)


def tool_check(runner, tool, label) -> str:
    """Ask the runner whether a tool is usable; "" when it is (or cannot judge).

    Runners may implement check(tool, label); when they do not, executability is
    established by the spawn attempt itself (a failed spawn is an input error).
    """
    checker = getattr(runner, "check", None)
    if checker is None:
        return ""
    return str(checker(tool, label) or "")


def _tool_record(tool, result) -> dict:
    return {
        "tool": str(tool),
        "argv": list(result.get("argv") or []),
        "rc": result.get("rc"),
        "error": result.get("error"),
        "stdout_sha256": sha256_bytes((result.get("stdout") or "").encode("utf-8")),
        "stdout_head": _clip(result.get("stdout") or "", 4000),
        "stderr_head": _clip(result.get("stderr") or "", 2000),
    }


# --------------------------------------------------------------------------
# the gate
# --------------------------------------------------------------------------


def _new_report(config) -> dict:
    return {
        "schema": GATE_SCHEMA,
        "gate_version": GATE_VERSION,
        "line": LINE,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "evidence_layer": EVIDENCE_LAYER,
        "inputs": {
            "apk": config.get("apk"),
            "expected_head": config.get("expected_head"),
            "build_head_path": config.get("build_head_path"),
            "sha256_file_path": config.get("sha256_file_path"),
            "expected_cert_sha256_raw": config.get("expected_cert_sha256"),
            "expected_cert_sha256_normalized": normalize_cert_sha256(config.get("expected_cert_sha256")),
            "aapt": config.get("aapt"),
            "apksigner": config.get("apksigner"),
            "tool_timeout_s": config.get("tool_timeout", DEFAULT_TOOL_TIMEOUT),
        },
        "apk": {},
        "artifacts": {"dex": [], "notes": []},
        "tools": {},
        "gates": [],
        "errors": [],
        "summary": {},
        "claims_not_proven": list(CLAIMS_NOT_PROVEN),
        "passed": False,
        "exit_code": None,
    }


def finalize(report: dict) -> dict:
    """Compute passed/exit_code from gates + errors. Fail closed on anything odd."""
    gates = report.get("gates") or []
    errors = report.get("errors") or []
    failures = [entry["id"] for entry in gates if not entry.get("passed")]
    report["summary"] = {
        "gates_total": len(gates),
        "gates_passed": len(gates) - len(failures),
        "gates_failed": failures,
        "errors": len(errors),
    }
    report["passed"] = bool(gates) and not failures and not errors
    if report["passed"]:
        report["exit_code"] = 0
    elif errors:
        report["exit_code"] = 2
    else:
        report["exit_code"] = 1
    return report


def evaluate(config, runner=None) -> dict:
    """Run every gate. Always returns a report; never raises for bad artifacts."""
    runner = runner or SubprocessRunner(float(config.get("tool_timeout", DEFAULT_TOOL_TIMEOUT)))
    report = _new_report(config)
    gates = report["gates"]
    errors = report["errors"]
    artifacts = report["artifacts"]

    def gate(gate_id, passed, reason, detail=None):
        entry = {"id": gate_id, "passed": bool(passed), "reason": reason}
        if detail:
            entry["detail"] = detail
        gates.append(entry)
        return bool(passed)

    def fail_group(gate_ids, reason):
        for gate_id in gate_ids:
            gate(gate_id, False, reason)

    apk_path = Path(str(config.get("apk") or ""))
    build_head_path = Path(str(config.get("build_head_path") or ""))
    sha256_path = Path(str(config.get("sha256_file_path") or ""))
    aapt = config.get("aapt")
    apksigner = config.get("apksigner")
    expected_head = config.get("expected_head")
    expected_cert = normalize_cert_sha256(config.get("expected_cert_sha256"))

    # --- 1. APK identity: hash and size --------------------------------
    apk_sha = None
    apk_size = None
    if not apk_path.is_file():
        errors.append({"stage": "apk", "kind": "missing_input",
                       "message": "APK not found: %s" % apk_path})
        gate("apk_readable", False, "not evaluated: APK not found (%s)" % apk_path)
    else:
        apk_size = apk_path.stat().st_size
        try:
            apk_sha = sha256_file(apk_path)
        except OSError as error:
            errors.append({"stage": "apk", "kind": "unreadable",
                           "message": "cannot hash APK: %s" % error})
            gate("apk_readable", False, "not evaluated: APK unreadable (%s)" % error)
        else:
            report["apk"] = {"path": str(apk_path), "name": apk_path.name,
                             "size_bytes": apk_size, "sha256": apk_sha}
            gate("apk_readable", apk_size > 0,
                 "APK readable" if apk_size > 0 else "APK is zero bytes",
                 {"size_bytes": apk_size, "sha256": apk_sha})
    apk_usable = apk_sha is not None and apk_size is not None and apk_size > 0
    apk_unusable_reason = "not evaluated: APK unusable"

    # --- 2. head sidecar (no repair) -----------------------------------
    expected_head_ok = is_valid_git_head(expected_head)
    gate(
        "expected_head_format",
        expected_head_ok,
        "--expected-head is 40 hex characters"
        if expected_head_ok
        else "--expected-head must be exactly 40 hex characters as given, got %r"
        % (_clip(str(expected_head)),),
    )

    build_head_literal = None
    if not build_head_path.is_file():
        errors.append({"stage": "build-head", "kind": "missing_input",
                       "message": "build-head sidecar not found: %s" % build_head_path})
        gate("build_head_literal", False,
             "not evaluated: build-head sidecar not found (%s)" % build_head_path)
    else:
        try:
            raw_text = build_head_path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as error:
            errors.append({"stage": "build-head", "kind": "unreadable",
                           "message": "cannot read build-head sidecar: %s" % error})
            gate("build_head_literal", False,
                 "not evaluated: build-head sidecar unreadable (%s)" % error)
        else:
            build_head_literal, reason = parse_build_head(raw_text)
            report["inputs"]["build_head_literal"] = build_head_literal
            gate(
                "build_head_literal",
                build_head_literal is not None,
                reason
                if build_head_literal is None
                else "build-head literal %s" % build_head_literal,
                {"literal": build_head_literal, "raw_chars": len(raw_text)},
            )

    if build_head_literal is None or not expected_head_ok:
        gate("head_match", False, "not evaluated: head inputs unusable")
    else:
        matched = build_head_literal == expected_head
        gate(
            "head_match",
            matched,
            "build-head %s == expected-head" % build_head_literal
            if matched
            else "build-head %s != expected-head %s (exact comparison; no repair)"
            % (build_head_literal, expected_head),
        )

    # --- 3. APK digest vs sha256 sidecar -------------------------------
    if not apk_usable:
        gate("apk_sha256_match", False, apk_unusable_reason)
    elif not sha256_path.is_file():
        errors.append({"stage": "sha256-file", "kind": "missing_input",
                       "message": "sha256 sidecar not found: %s" % sha256_path})
        gate("apk_sha256_match", False,
             "not evaluated: sha256 sidecar not found (%s)" % sha256_path)
    else:
        try:
            declared_text = sha256_path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as error:
            errors.append({"stage": "sha256-file", "kind": "unreadable",
                           "message": "cannot read sha256 sidecar: %s" % error})
            gate("apk_sha256_match", False, "not evaluated: sha256 sidecar unreadable (%s)" % error)
        else:
            report["inputs"]["sha256_file_literal"] = _clip(declared_text.strip(), 300)
            tokens = declared_text.split()
            declared = tokens[0] if tokens else ""
            if not is_valid_sha256_hex(declared):
                gate("apk_sha256_match", False,
                     "sha256 sidecar must start with a 64-hex digest, got %r"
                     % (_clip(declared),))
            elif declared.lower() != apk_sha:
                gate("apk_sha256_match", False,
                     "APK sha256 %s != sidecar %s (sidecar %s)"
                     % (apk_sha, declared.lower(), sha256_path),
                     {"sidecar_filename_field": " ".join(tokens[1:]) or None})
            else:
                gate("apk_sha256_match", True,
                     "APK sha256 %s matches %s" % (apk_sha, sha256_path),
                     {"sidecar_filename_field": " ".join(tokens[1:]) or None})

    # --- 4. aapt dump badging -----------------------------------------
    badging_parsed = None
    aapt_problem = tool_check(runner, aapt, "--aapt") if apk_usable else ""
    if not apk_usable:
        fail_group(AAPT_GATES, apk_unusable_reason)
    elif aapt_problem:
        errors.append({"stage": "aapt", "kind": "tool_unusable", "message": aapt_problem})
        fail_group(AAPT_GATES, "not evaluated: %s" % aapt_problem)
    else:
        version_probe = runner.run([aapt, "version"])
        report["tools"]["aapt_version"] = _tool_record(aapt, version_probe)
        result = runner.run([aapt, "dump", "badging", str(apk_path)])
        report["tools"]["aapt"] = _tool_record(aapt, result)
        if result.get("error"):
            errors.append({"stage": "aapt", "kind": "tool_not_executed",
                           "message": "aapt dump badging could not be executed: %s"
                           % result["error"]})
            fail_group(AAPT_GATES, "not evaluated: aapt dump badging not executed (%s)"
                       % result["error"])
        elif result.get("rc") != 0:
            fail_group(AAPT_GATES, "aapt dump badging exited %s: %s"
                       % (result["rc"], _clip((result.get("stderr") or "").strip(), 200)))
        else:
            info = parse_aapt_badging(result.get("stdout") or "")
            badging_parsed = info
            report["tools"]["aapt"]["parsed"] = info
            if info["malformed"]:
                fail_group(AAPT_GATES, "aapt badging unparsable: %s" % "; ".join(info["malformed"]))
            else:
                gate(
                    "aapt_package_name",
                    info["package"] == EXPECTED_PACKAGE,
                    "badging package %r == %r" % (info["package"], EXPECTED_PACKAGE)
                    if info["package"] == EXPECTED_PACKAGE
                    else "badging package %r != expected %r" % (info["package"], EXPECTED_PACKAGE),
                    {"package": info["package"]},
                )
                try:
                    version_code = int(info["version_code"])
                except (TypeError, ValueError):
                    version_code = None
                gate(
                    "aapt_version_code",
                    version_code == EXPECTED_VERSION_CODE,
                    "versionCode %s == %d" % (info["version_code"], EXPECTED_VERSION_CODE)
                    if version_code == EXPECTED_VERSION_CODE
                    else "versionCode %r != expected %d" % (info["version_code"], EXPECTED_VERSION_CODE),
                    {"version_code": info["version_code"]},
                )
                version_name = info["version_name"] or ""
                gate(
                    "aapt_version_name_beta",
                    EXPECTED_VERSION_NAME_SUBSTRING in version_name.lower(),
                    "versionName %r contains %r" % (version_name, EXPECTED_VERSION_NAME_SUBSTRING)
                    if EXPECTED_VERSION_NAME_SUBSTRING in version_name.lower()
                    else "versionName %r does not contain %r"
                    % (version_name, EXPECTED_VERSION_NAME_SUBSTRING),
                    {"version_name": info["version_name"]},
                )
                gate(
                    "aapt_not_debuggable",
                    not info["debuggable"],
                    "no application-debuggable marker in badging"
                    if not info["debuggable"]
                    else "badging carries %r: the artifact is debuggable"
                    % info["debuggable_marker"],
                    {"debuggable": info["debuggable"]},
                )
                gate(
                    "aapt_arm64_abi",
                    EXPECTED_ABI in info["native_code"],
                    "native-code %s includes %s" % (info["native_code"], EXPECTED_ABI)
                    if EXPECTED_ABI in info["native_code"]
                    else "native-code %s does not include %s"
                    % (info["native_code"], EXPECTED_ABI),
                    {"native_code": info["native_code"]},
                )

    # --- 5. apksigner verify + certificate identity --------------------
    # The expected fingerprint is an *input*, so its format is always evaluated
    # on its own merits; every artifact-dependent gate below stays fail-closed
    # when the APK could not be read.
    gate(
        "expected_cert_sha256_format",
        is_valid_sha256_hex(expected_cert),
        "expected certificate SHA-256 normalizes to 64 hex characters"
        if is_valid_sha256_hex(expected_cert)
        else "--expected-cert-sha256 must be a full certificate SHA-256 fingerprint "
        "(64 hex, colons/spaces allowed); normalization only removes whitespace and "
        "colons, it does not repair the value (got %r)"
        % (_clip(config.get("expected_cert_sha256")),),
        {"normalized": expected_cert},
    )
    signer_digests = []
    apksigner_problem = tool_check(runner, apksigner, "--apksigner") if apk_usable else ""
    if not apk_usable:
        gate("apksigner_verified", False, apk_unusable_reason)
        gate("cert_sha256_match", False, apk_unusable_reason)
    else:
        if apksigner_problem:
            errors.append({"stage": "apksigner", "kind": "tool_unusable",
                           "message": apksigner_problem})
            gate("apksigner_verified", False, "not evaluated: %s" % apksigner_problem)
            gate("cert_sha256_match", False, "not evaluated: apksigner not executed")
        else:
            version_probe = runner.run([apksigner, "version"])
            report["tools"]["apksigner_version"] = _tool_record(apksigner, version_probe)
            result = runner.run([apksigner, "verify", "--print-certs", str(apk_path)])
            report["tools"]["apksigner"] = _tool_record(apksigner, result)
            if result.get("error"):
                errors.append({"stage": "apksigner", "kind": "tool_not_executed",
                               "message": "apksigner verify could not be executed: %s"
                               % result["error"]})
                gate("apksigner_verified", False,
                     "not evaluated: apksigner verify not executed (%s)" % result["error"])
                gate("cert_sha256_match", False, "not evaluated: apksigner not executed")
            elif result.get("rc") != 0:
                gate("apksigner_verified", False,
                     "apksigner verify --print-certs exited %s: %s"
                     % (result["rc"], _clip((result.get("stderr") or "").strip(), 200)))
                gate("cert_sha256_match", False,
                     "not evaluated: apksigner verify exited %s" % result["rc"])
            else:
                gate("apksigner_verified", True, "apksigner verify --print-certs exited 0")
                parsed = parse_apksigner_cert_digests(
                    (result.get("stdout") or "") + "\n" + (result.get("stderr") or ""))
                report["tools"]["apksigner"]["cert_digests"] = parsed
                if parsed["malformed"]:
                    gate("cert_sha256_match", False,
                         "apksigner reported malformed certificate digest line(s): %s"
                         % "; ".join(_clip(item) for item in parsed["malformed"]))
                else:
                    signer_digests = sorted({entry["normalized"] for entry in parsed["digests"]})
                    detail = {"signer_digest_count": len(signer_digests),
                              "expected": expected_cert}
                    if not signer_digests:
                        gate("cert_sha256_match", False,
                             "apksigner printed no certificate SHA-256 digest", detail)
                    elif not is_valid_sha256_hex(expected_cert):
                        gate("cert_sha256_match", False,
                             "not evaluated: expected certificate fingerprint unusable", detail)
                    elif signer_digests == [expected_cert]:
                        gate("cert_sha256_match", True,
                             "signer certificate SHA-256 %s matches expected" % expected_cert,
                             detail)
                    else:
                        gate("cert_sha256_match", False,
                             "signer certificate SHA-256 set %s != expected %s"
                             % (signer_digests, expected_cert), detail)

    # --- 6. DEX string table ------------------------------------------
    dex_names = []
    dex_details = []
    zip_error = None
    if not apk_usable:
        gate("zip_has_dex", False, apk_unusable_reason)
    else:
        try:
            dex_names = list_dex_entries(apk_path)
        except (zipfile.BadZipFile, OSError) as error:
            zip_error = "%s: %s" % (type(error).__name__, error)
            artifacts["notes"].append("APK is not a readable ZIP: %s" % zip_error)
            gate("zip_has_dex", False, "APK is not a readable ZIP (%s)" % zip_error)
        else:
            gate("zip_has_dex", bool(dex_names),
                 "classes*.dex present: %s" % dex_names if dex_names
                 else "APK ZIP holds no root-level classes*.dex entry",
                 {"entries": dex_names})

    if not apk_usable or zip_error is not None:
        gate("dex_entries_valid", False, "not evaluated: no readable classes*.dex entries")
        gate("dex_has_termux_activity", False, "not evaluated: no readable classes*.dex entries")
    elif not dex_names:
        gate("dex_entries_valid", False, "not evaluated: no classes*.dex entries present")
        gate("dex_has_termux_activity", False, "not evaluated: no classes*.dex entries present")
    else:
        invalid = []
        containing = []
        any_valid = False
        for name in dex_names:
            try:
                blob = read_apk_dex_bytes(apk_path, name)
            except (zipfile.BadZipFile, OSError) as error:
                invalid.append("%s: unreadable (%s)" % (name, error))
                dex_details.append({"entry": name, "size_bytes": None, "sha256": None,
                                    "valid": False, "reason": "unreadable: %s" % error})
                continue
            detail = validate_dex(blob)
            record = {"entry": name, "size_bytes": len(blob), "sha256": sha256_bytes(blob)}
            record.update(detail)
            dex_details.append(record)
            if detail["valid"]:
                any_valid = True
                try:
                    if dex_defines_class(blob, ACTIVITY_DESCRIPTOR):
                        containing.append(name)
                except DexFormatError as error:  # defensive: validate_dex said "valid"
                    invalid.append("%s: %s" % (name, error))
                    record["valid"] = False
                    record["reason"] = str(error)
            else:
                invalid.append("%s: %s" % (name, detail["reason"]))
        artifacts["dex"] = dex_details
        gate("dex_entries_valid", not invalid,
             "all %d classes*.dex entries parse as DEX" % len(dex_names) if not invalid
             else "malformed DEX: %s" % "; ".join(invalid),
             {"entries": dex_details})
        if not any_valid:
            gate("dex_has_termux_activity", False,
                 "not evaluated: every classes*.dex entry is malformed DEX")
        else:
            gate("dex_has_termux_activity", bool(containing),
                 "%s defined by DEX class_defs in %s" % (ACTIVITY_DESCRIPTOR, containing)
                 if containing
                 else "%s missing from DEX class_defs in %s (a base-only APK without "
                 "the support_ui activity, or unrelated ZIP text, does not satisfy this gate)"
                 % (ACTIVITY_DESCRIPTOR, dex_names),
                 {"containing": containing, "scanned": dex_names})

    return finalize(report)


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


class _ArgumentParser(argparse.ArgumentParser):
    """argparse that still emits a fail-closed JSON report for bad usage."""

    def error(self, message):
        report = {
            "schema": GATE_SCHEMA,
            "gate_version": GATE_VERSION,
            "line": LINE,
            "generated_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "evidence_layer": EVIDENCE_LAYER,
            "inputs": {},
            "apk": {},
            "artifacts": {"dex": [], "notes": []},
            "tools": {},
            "gates": [],
            "errors": [{"stage": "cli", "kind": "argument_error", "message": message}],
            "summary": {"gates_total": 0, "gates_passed": 0, "gates_failed": [], "errors": 1},
            "claims_not_proven": list(CLAIMS_NOT_PROVEN),
            "passed": False,
            "exit_code": 2,
        }
        output = _recover_output_path(sys.argv[1:])
        if output:
            try:
                _write_json(report, output)
            except OSError:
                pass
        self.print_usage(sys.stderr)
        self.exit(2, "%s: error: %s\n" % (self.prog, message))


def _recover_output_path(argv) -> str:
    """Best-effort --output recovery for argparse failures (report as fail-closed)."""
    for index, item in enumerate(argv):
        if item.startswith("--output="):
            return item.split("=", 1)[1]
        if item in ("--output", "--out") and index + 1 < len(argv):
            return argv[index + 1]
    return ""


def _write_json(payload: dict, output_path) -> str:
    path = Path(str(output_path))
    if str(path.parent) not in ("", "."):
        path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
    path.write_text(text, encoding="utf-8")
    return str(path)


def write_report(report: dict, output_path):
    """Write the JSON report; return an error dict instead of raising."""
    try:
        _write_json(report, output_path)
        return None
    except OSError as error:
        return {"stage": "report", "kind": "output_not_writable",
                "message": "cannot write JSON report to %s: %s" % (output_path, error)}


def build_parser() -> _ArgumentParser:
    parser = _ArgumentParser(
        prog="beta_artifact_gate.py",
        description="Fail-closed APK identity gate for the Termux beta certification "
                    "environment (artifact evidence only).",
    )
    parser.add_argument("--apk", required=True, help="APK under test")
    parser.add_argument("--expected-head", required=True,
                        help="full 40-hex commit SHA the APK must correspond to")
    parser.add_argument("--build-head", required=True,
                        help="CI sidecar holding exactly one full 40-hex SHA")
    parser.add_argument("--sha256-file", required=True,
                        help="CI sidecar holding the APK sha256 (sha256sum format)")
    parser.add_argument("--expected-cert-sha256", required=True,
                        help="expected signer certificate SHA-256 (colons/spaces allowed)")
    parser.add_argument("--aapt", required=True, help="runnable aapt executable")
    parser.add_argument("--apksigner", required=True, help="apksigner executable")
    parser.add_argument("--output", required=True, help="path for the JSON report")
    parser.add_argument("--tool-timeout", type=float, default=DEFAULT_TOOL_TIMEOUT,
                        help="per-tool timeout in seconds (default %d)" % DEFAULT_TOOL_TIMEOUT)
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    namespace = parser.parse_args(argv)
    config = {
        "apk": namespace.apk,
        "expected_head": namespace.expected_head,
        "build_head_path": namespace.build_head,
        "sha256_file_path": namespace.sha256_file,
        "expected_cert_sha256": namespace.expected_cert_sha256,
        "aapt": namespace.aapt,
        "apksigner": namespace.apksigner,
        "tool_timeout": namespace.tool_timeout,
    }
    report = finalize(evaluate(config))
    error = write_report(report, namespace.output)
    if error:
        report["errors"].append(error)
        finalize(report)
        retry = write_report(report, namespace.output)
        if retry:
            sys.stdout.write(json.dumps(report, indent=2, ensure_ascii=False) + "\n")

    summary = report["summary"]
    if report["passed"]:
        print("PASS beta-artifact-gate: %d/%d gates; apk sha256=%s; head=%s; report=%s"
              % (summary["gates_passed"], summary["gates_total"],
                 report["apk"].get("sha256"), report["inputs"].get("expected_head"),
                 namespace.output))
    else:
        print("FAIL beta-artifact-gate: %d/%d gates failed (%s); errors=%d; report=%s"
              % (len(summary["gates_failed"]), summary["gates_total"],
                 ",".join(summary["gates_failed"]) or "-", summary["errors"], namespace.output))
    return report["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
