#!/usr/bin/env python3
"""beta-art gate: read-only ART AOT readiness certificate for a beta APK.

Line: art (beta certification environment, NOT account/OAuth auth).

What this gate does
-------------------
It collects *read-only* device state for one package and decides whether the
installed artifact meets this round's AOT baseline:

  1. the APK on device is the artifact under test  (base.apk SHA-256 matches
     --expected-apk-sha256; every APK path reported by `pm path` is hashed;
     split APKs that own code must not be skipped),
  2. the package flags are explicitly known and do NOT contain DEBUGGABLE,
  3. the *primary code path* dexopt entry the device actually reports has
     compiler filter `speed` or `everything` -- verify / run-from-apk /
     speed-profile / unknown / missing / error do not satisfy the baseline.

Everything else (warm-method AOT, zero-JIT claims, R8 verification vs minified
release, signing identity) is explicitly out of scope; see scope_disclaimers in
the report.

Hard properties
---------------
* Read-only.  Every device command is validated against a whitelist by
  assert_read_only() before it is executed; no compile / install / force-stop /
  reboot is reachable from this tool.
* Fail-closed.  Anything that cannot be determined yields status INCOMPLETE
  (never PASS): unparsable OEM `dumpsys package`, a missing dexopt entry for a
  code-bearing split, an unresolvable artifact hash, a dexopt location that
  resolved to `error`, ...
* Two transports, never mixed:
    --transport local-root  -> `su -c '<abs /system/bin/... >'` on the host
    --transport adb         -> always `adb -s <SERIAL> shell <cmd...>`
  The adb transport refuses to be constructed without an explicit serial and it
  never falls back to a local shell.

Usage
-----
  python3 scripts/beta_art_gate.py --transport local-root \
      --package com.termux --expected-apk-sha256 <64 HEX> --out-dir DIR
  python3 scripts/beta_art_gate.py --transport adb --serial SERIAL \
      --package com.termux --expected-apk-sha256 <64 HEX> --out-dir DIR

Exit codes: 0 = passed, 1 = not passed (FAIL/INCOMPLETE/ERROR), 2 = usage error.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA = "beta-art-gate/1"
LINE = "art"

EXIT_PASS = 0
EXIT_NOT_PASSED = 1
EXIT_USAGE = 2

DEFAULT_TIMEOUT = 120
SYSTEM_BIN = "/system/bin/"

# compiler filters that satisfy this round's baseline
AOT_FILTERS = ("speed", "everything")
# filters we explicitly know how to name as "not this baseline" (for detail text)
KNOWN_FILTERS = (
    "verify", "run-from-apk", "speed-profile", "space", "space-profile",
    "quicken", "extract", "assume-verified", "verify-none", "error",
    "unknown", "missing",
)

CMD_GETPROP = "getprop"
CMD_PM_PATH = "pm-path"
CMD_DUMPSYS_PACKAGE = "dumpsys-package"
CMD_ART_DUMP = "art-dump-package"
CMD_SHA256SUM = "sha256sum"
CMD_PIDOF = "pidof"
CMD_MAPS_GREP = "maps-grep"

COMMAND_ORDER = [CMD_GETPROP, CMD_PM_PATH, CMD_DUMPSYS_PACKAGE, CMD_ART_DUMP,
                 CMD_SHA256SUM, CMD_PIDOF, CMD_MAPS_GREP]

MAPS_GREP_PATTERN = r"(\.odex|\.oat|\.vdex|\.art|/oat/)"

SCOPE_DISCLAIMERS = [
    "non-debuggable is NOT proof of AOT compilation; it is only a recorded "
    "prerequisite (a debuggable build still fails this gate).",
    "`pm compile` reporting Success is NOT proof of an actual speed filter; "
    "this gate never issues a compile and only reads the dexopt state the "
    "device reports.",
    "normal ART mixed execution is allowed: this gate makes no claim of zero "
    "JIT and no claim that every method is AOT compiled.  Thread presence or "
    "high CPU is likewise not evidence of repeated recompilation here.",
    "compiler filters verify / run-from-apk / speed-profile do not satisfy "
    "this baseline; speed or everything do.  The reason is preserved in the "
    "report instead of being silently attributed.",
    "verification (R8 off) builds and minified release builds are separate "
    "artifacts; this gate inspects dexopt state only and certifies neither "
    "R8 mode nor shrinking.",
    "a test signing key must never be treated as production identity; this "
    "gate records apkSigningVersion but does not authenticate the signer.",
    "OEM `dumpsys package` output differs from AOSP (API 32 vs 36); state "
    "that cannot be parsed yields INCOMPLETE (fail-closed), never PASS.",
    "optional /proc/<pid>/maps evidence is recorded as extra context only; "
    "being unable to read it is not a hard failure.",
]


class GateError(Exception):
    """Raised for refused input, refused command, or unparsable device state."""


# ---------------------------------------------------------------------------
# transports
# ---------------------------------------------------------------------------

@dataclass
class CommandResult:
    cmd_id: str
    remote_argv: list
    exec_argv: list
    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool = False


def _default_exec(argv, timeout):
    proc = subprocess.run(argv, capture_output=True, text=True,
                          timeout=timeout, stdin=subprocess.DEVNULL)
    return proc.returncode, proc.stdout, proc.stderr


def _run_via(cmd_id, remote_argv, exec_argv, exec_fn, timeout, channel_note=""):
    """Execute a validated argv, never raising on tool failure."""
    assert_read_only(cmd_id, remote_argv)
    try:
        code, out, err = exec_fn(exec_argv, timeout)
    except subprocess.TimeoutExpired:
        return CommandResult(cmd_id, list(remote_argv), list(exec_argv), 124,
                             "", "timeout after %ss" % timeout, True)
    except OSError as error:
        # missing binary, permission denied, ... -> fail closed, record why
        return CommandResult(cmd_id, list(remote_argv), list(exec_argv), 127,
                             "", "%s: %s" % (type(error).__name__, error), False)
    if channel_note:
        err = err if err else ""
    return CommandResult(cmd_id, list(remote_argv), list(exec_argv), code,
                         out, err, False)


class Transport:
    name = "abstract"
    evidence_tier = "unknown"

    def __init__(self, timeout=DEFAULT_TIMEOUT, exec_fn=None):
        self.timeout = timeout
        self.exec_fn = exec_fn or _default_exec

    def build_argv(self, remote_argv):  # pragma: no cover - overridden
        raise NotImplementedError

    def run(self, cmd_id, remote_argv):
        exec_argv = self.build_argv(remote_argv)
        return _run_via(cmd_id, remote_argv, exec_argv, self.exec_fn,
                        self.timeout)


class LocalRootTransport(Transport):
    """Host device, root via `su -c`, always absolute /system/bin paths."""

    name = "local-root"
    evidence_tier = ("device: host device, rooted local exec (su -c, "
                     "absolute /system/bin paths)")

    def __init__(self, su=None, timeout=DEFAULT_TIMEOUT, exec_fn=None):
        super().__init__(timeout=timeout, exec_fn=exec_fn)
        self.su = su or shutil.which("su") or "su"

    def build_argv(self, remote_argv):
        command = " ".join(shlex.quote(str(a)) for a in remote_argv)
        return [self.su, "-c", command]


class AdbTransport(Transport):
    """Remote device, always `adb -s <serial> shell ...`; never local."""

    name = "adb"
    evidence_tier = ("device: remote device via `adb -s SERIAL shell` "
                     "(adb may merge remote stderr into stdout)")

    def __init__(self, serial, adb="adb", timeout=DEFAULT_TIMEOUT, exec_fn=None):
        super().__init__(timeout=timeout, exec_fn=exec_fn)
        if serial is None or not str(serial).strip():
            raise GateError("--transport adb requires an explicit --serial")
        self.serial = str(serial)
        self.adb = adb or "adb"

    def build_argv(self, remote_argv):
        return [self.adb, "-s", self.serial, "shell"] + [str(a) for a in remote_argv]


# ---------------------------------------------------------------------------
# read-only command whitelist
# ---------------------------------------------------------------------------

_ALLOWED_TOOL = {
    CMD_GETPROP: "/system/bin/getprop",
    CMD_PM_PATH: "/system/bin/pm",
    CMD_DUMPSYS_PACKAGE: "/system/bin/dumpsys",
    CMD_ART_DUMP: "/system/bin/cmd",
    CMD_SHA256SUM: "/system/bin/sha256sum",
    CMD_PIDOF: "/system/bin/pidof",
    CMD_MAPS_GREP: "/system/bin/grep",
}

_ABS_PATH_RE = re.compile(r"^/[A-Za-z0-9._~@+/=-]+$")
_PROC_MAPS_RE = re.compile(r"^/proc/\d+/maps$")
_PACKAGE_RE = re.compile(r"^[A-Za-z0-9_.]+$")


def assert_read_only(cmd_id, argv):
    """Raise GateError unless argv is an allowed read-only device command.

    This is the single choke point that makes 'no implicit device action'
    enforceable: every device command goes through here.
    """
    argv = [str(a) for a in argv]
    if not argv:
        raise GateError("empty command")
    expected_tool = _ALLOWED_TOOL.get(cmd_id)
    if expected_tool is None:
        raise GateError("unknown command id %r" % cmd_id)
    tool = argv[0]
    if not tool.startswith(SYSTEM_BIN):
        raise GateError("device commands must use absolute %s paths, got %r"
                        % (SYSTEM_BIN, tool))
    if tool != expected_tool:
        raise GateError("command %s must use %s, got %s" % (cmd_id, expected_tool, tool))

    if cmd_id == CMD_GETPROP:
        if len(argv) != 1:
            raise GateError("getprop takes no arguments (full property dump only)")
    elif cmd_id == CMD_PM_PATH:
        if len(argv) != 3 or argv[1] != "path" or not _PACKAGE_RE.match(argv[2]):
            raise GateError("only `pm path <package>` is allowed, got %r" % (argv,))
    elif cmd_id == CMD_DUMPSYS_PACKAGE:
        if len(argv) != 3 or argv[1] != "package" or not _PACKAGE_RE.match(argv[2]):
            raise GateError("only `dumpsys package <package>` is allowed, got %r" % (argv,))
    elif cmd_id == CMD_ART_DUMP:
        if argv[1:4] != ["package", "art", "dump"] or len(argv) not in (4, 5):
            raise GateError("only `cmd package art dump [package]` is allowed, got %r"
                            % (argv,))
        if len(argv) == 5 and not _PACKAGE_RE.match(argv[4]):
            raise GateError("unexpected argument to art dump: %r" % (argv[4],))
    elif cmd_id == CMD_SHA256SUM:
        if len(argv) < 2:
            raise GateError("sha256sum needs at least one path")
        for path in argv[1:]:
            if not _ABS_PATH_RE.match(path):
                raise GateError("sha256sum path must be absolute: %r" % (path,))
    elif cmd_id == CMD_PIDOF:
        if len(argv) != 2 or not _PACKAGE_RE.match(argv[1]):
            raise GateError("only `pidof <package>` is allowed, got %r" % (argv,))
    elif cmd_id == CMD_MAPS_GREP:
        if len(argv) != 4 or argv[1] != "-E" or not _PROC_MAPS_RE.match(argv[3]):
            raise GateError("only `grep -E <pattern> /proc/<pid>/maps` is allowed, got %r"
                            % (argv,))
    return True


# ---------------------------------------------------------------------------
# parsing helpers
# ---------------------------------------------------------------------------

_SHA_RE = re.compile(r"^(?P<hex>[0-9a-fA-F]{64})$")
_SHA256SUM_LINE_RE = re.compile(r"^(?P<hex>[0-9a-fA-F]{64})[ \t]+(?P<binary>\*)?(?P<path>.+?)[ \t]*$")
_GETPROP_LINE_RE = re.compile(r"^\[(?P<key>[^\]]+)\]:\s*\[(?P<value>.*)\]\s*$")
_BLOCK_RE = re.compile(r"^\s*\[(?P<name>[A-Za-z0-9_.]+)\]\s*$")
_PATH_RE = re.compile(r"^\s*path:\s*(?P<path>/[^\s]+)\s*$")
_STATUS_RE = re.compile(
    r"^\s*(?P<isa>[A-Za-z0-9_\-]+):\s*"
    r"\[(?P<key>status|compiler-filter|compilation-filter)=(?P<value>[^\]\s,]+)\]"
    r"(?:\s*\[reason=(?P<reason>[^\]]*)\])?(?P<rest>.*)$")
_LOCATION_RE = re.compile(r"^\s*\[location is (?P<text>.*?)\]\s*$")
_SECONDARY_DEX_RE = re.compile(
    r"^\s*/(?:data|system|system_ext|product|vendor|mnt|apex)/\S+$")
_PACKAGE_BLOCK_RE = re.compile(
    r"^\s*Package \[(?P<name>[^\]]+)\]\s*(?:\((?P<id>[^)]*)\))?\s*:?\s*$")

ABI_TO_ISA = {
    "arm64-v8a": "arm64",
    "armeabi-v7a": "arm",
    "armeabi": "arm",
    "x86_64": "x86_64",
    "x86": "x86",
    "riscv64": "riscv64",
}


def abi_to_isa(abi):
    """Map an Android ABI name to the ISA string ART prints in its dumps."""
    if abi is None:
        return None
    return ABI_TO_ISA.get(str(abi).strip(), str(abi).strip())


def normalise_sha256(value):
    """Validate a 64-hex-char SHA-256.  No repairs, no prefixes."""
    if value is None:
        raise GateError("missing --expected-apk-sha256")
    text = str(value)
    if not _SHA_RE.match(text):
        raise GateError(
            "expected apk sha256 must be exactly 64 hex characters "
            "(no 'sha256:' prefix, no whitespace): got %r" % text)
    return text.lower()


def parse_getprop_dump(stdout):
    props = {}
    for line in stdout.splitlines():
        match = _GETPROP_LINE_RE.match(line)
        if match:
            props[match.group("key")] = match.group("value")
    return props


def parse_pm_path(stdout):
    """Return the APK paths reported by `pm path`, in reported order.

    Raises GateError on any line we cannot account for (fail closed) so a
    malformed OEM variant can never be silently truncated.
    """
    paths = []
    for raw in stdout.splitlines():
        line = raw.rstrip()
        if not line:
            continue
        if not line.startswith("package:"):
            raise GateError("unparsable `pm path` line: %r" % line)
        path = line[len("package:"):].strip()
        if not path.startswith("/"):
            raise GateError("unparsable `pm path` entry: %r" % path)
        paths.append(path)
    return paths


def parse_sha256sum(stdout):
    """Parse `sha256sum <paths>` output -> {path: hex}."""
    out = {}
    for raw in stdout.splitlines():
        line = raw.rstrip()
        if not line:
            continue
        match = _SHA256SUM_LINE_RE.match(line)
        if not match:
            raise GateError("unparsable sha256sum line: %r" % line)
        out[match.group("path")] = match.group("hex").lower()
    return out


def parse_dumpsys_package(text, package):
    """Extract the `Package [<package>]` block fields, or None if absent."""
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        match = _PACKAGE_BLOCK_RE.match(line)
        if match and match.group("name") == package:
            start = index
            break
    if start is None:
        return None

    block = []
    for line in lines[start + 1:]:
        if _PACKAGE_BLOCK_RE.match(line):
            break
        if line.strip() and not line[:1].isspace():
            break
        block.append(line)

    info = {
        "package": package,
        "codePath": None,
        "primaryCpuAbi": None,
        "secondaryCpuAbi": None,
        "versionCode": None,
        "versionName": None,
        "minSdk": None,
        "targetSdk": None,
        "splits": [],
        "flags": None,
        "flags_raw": None,
        "flags_source": None,
        "apkSigningVersion": None,
        "timeStamp": None,
        "installerPackageName": None,
    }
    seen = set()

    def first(key, value):
        if key in seen:
            return
        seen.add(key)
        info[key] = value

    for line in block:
        stripped = line.strip()
        # versionCode=1022 minSdk=24 targetSdk=28  (three facts on one line)
        match = re.match(
            r"^versionCode=(?P<vc>\S+)(?:\s+minSdk=(?P<min>\S+))?(?:\s+targetSdk=(?P<tg>\S+))?",
            stripped)
        if match and "versionCode" not in seen:
            seen.add("versionCode")
            info["versionCode"] = match.group("vc")
            if match.group("min"):
                info["minSdk"] = match.group("min")
            if match.group("tg"):
                info["targetSdk"] = match.group("tg")
            continue
        match = re.match(r"^(?P<key>[A-Za-z]+)=(?P<value>.*)$", stripped)
        if not match:
            continue
        key, value = match.group("key"), match.group("value")
        if key == "flags" or key == "pkgFlags":
            if info["flags_raw"] is None and value.startswith("["):
                info["flags_raw"] = value
                info["flags_source"] = key
                body = value.strip().lstrip("[").rstrip("]").strip()
                info["flags"] = body.split() if body else []
            continue
        if key == "splits" and not info["splits"]:
            body = value.strip().lstrip("[").rstrip("]").strip()
            info["splits"] = [p.strip() for p in body.split(",") if p.strip()]
            continue
        if key in ("codePath", "primaryCpuAbi", "secondaryCpuAbi", "versionName",
                   "apkSigningVersion", "timeStamp", "installerPackageName"):
            first(key, None if value == "null" else value)
    return info


def extract_dexopt_state_section(text):
    """Return the `Dexopt state:` section body, or None when absent."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line.strip() == "Dexopt state:":
            body = []
            for follow in lines[index + 1:]:
                if follow.strip() and not follow[:1].isspace():
                    break
                body.append(follow)
            return "\n".join(body)
    return None


def parse_dexopt_state(text):
    """Parse ART dexopt state text (ART-service dump or dumpsys subsection).

    Recognised per-dex-container line:
        <isa>: [status=<filter>] [reason=<reason>] [primary-abi]
          [location is <path|error>]

    Unknown shapes produce no entries (never a guess).  Returns
    {"blocks": {package: {"primary": [...], "secondary_paths": [...]}},
     "warnings": [...]}
    """
    blocks = {}
    warnings = []
    current = None
    current_path = None

    for raw in text.splitlines():
        block_match = _BLOCK_RE.match(raw)
        if block_match:
            current = block_match.group("name")
            current_path = None
            blocks.setdefault(current, {"primary": [], "secondary_paths": [],
                                        "path_order": []})
            continue
        if current is None:
            continue
        block = blocks[current]

        path_match = _PATH_RE.match(raw)
        if path_match:
            current_path = path_match.group("path")
            if current_path not in block["path_order"]:
                block["path_order"].append(current_path)
            continue

        status_match = _STATUS_RE.match(raw)
        if status_match:
            value = status_match.group("value").strip()
            entry = {
                "dex_path": current_path,
                "isa": status_match.group("isa"),
                "status": value,
                "reason": status_match.group("reason"),
                "primary_abi": "primary-abi" in (status_match.group("rest") or ""),
                "filter_key": status_match.group("key"),
                "location_debug_string": None,
                "location_is_error": False,
                "package": current,
            }
            if current_path is None:
                warnings.append("status line without a preceding `path:` in "
                                "block [%s]: %r" % (current, raw.strip()))
            block["primary"].append(entry)
            continue

        location_match = _LOCATION_RE.match(raw)
        if location_match and block["primary"]:
            text_value = location_match.group("text").strip()
            entry = block["primary"][-1]
            entry["location_debug_string"] = text_value
            entry["location_is_error"] = (
                text_value == "" or text_value.lower().startswith("error"))
            continue

        if _SECONDARY_DEX_RE.match(raw):
            block["secondary_paths"].append(raw.strip())

    return {"blocks": blocks, "warnings": warnings}


def parse_sha256sum_line_count(stdout):
    return len([l for l in stdout.splitlines() if l.strip()])


def apk_has_code(apk_path):
    """Locally inspect an APK for dex containers.

    Returns (state, classes) where state is one of
    "has-code" | "no-code" | "unreadable".  Used only for split APKs whose
    code could otherwise be skipped; "unreadable" fails closed at the caller.
    """
    if not os.path.exists(apk_path):
        return "unreadable", []
    try:
        with zipfile.ZipFile(apk_path) as archive:
            names = [n for n in archive.namelist()
                     if re.match(r"^classes\d*\.dex$", n)]
        return ("has-code" if names else "no-code"), sorted(names)
    except (OSError, zipfile.BadZipFile):
        return "unreadable", []


# ---------------------------------------------------------------------------
# report plumbing
# ---------------------------------------------------------------------------

def _sha256_text(text):
    return hashlib.sha256(text.encode("utf-8", "replace")).hexdigest()


def _summarise(text, max_lines=6, max_chars=400):
    lines = [l for l in text.splitlines() if l.strip()]
    head = "\n".join(lines[:max_lines])
    if len(head) > max_chars:
        head = head[:max_chars] + " ...[truncated]"
    if len(lines) > max_lines:
        head += "\n... (%d more non-blank lines)" % (len(lines) - max_lines)
    return head


def _local_repo_head():
    try:
        proc = subprocess.run(["git", "rev-parse", "HEAD"],
                              capture_output=True, text=True, timeout=20,
                              cwd=str(Path(__file__).resolve().parents[1]))
        if proc.returncode == 0:
            return proc.stdout.strip()
        return None
    except (OSError, subprocess.SubprocessError):
        return None


def write_report(out_dir, report):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "report.json"
    path.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n",
                    encoding="utf-8")
    return path


class Collector:
    """Runs the read-only plan and persists every raw stdout/stderr."""

    def __init__(self, transport, out_dir, verbose=False):
        self.transport = transport
        self.raw_dir = Path(out_dir) / "raw"
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.verbose = verbose
        self.commands = []
        self.results = {}

    def _record(self, result):
        index = len(self.commands) + 1
        base = "%02d-%s" % (index, result.cmd_id)
        stdout_path = self.raw_dir / (base + ".stdout")
        stderr_path = self.raw_dir / (base + ".stderr")
        stdout_path.write_text(result.stdout, encoding="utf-8")
        stderr_path.write_text(result.stderr, encoding="utf-8")
        env = {
            "id": result.cmd_id,
            "exec_argv": result.exec_argv,
            "remote_argv": result.remote_argv,
            "exit_code": result.exit_code,
            "timed_out": result.timed_out,
            "ok": result.exit_code == 0 and not result.timed_out,
            "stdout_path": str(stdout_path),
            "stderr_path": str(stderr_path),
            "stdout_bytes": len(result.stdout.encode("utf-8")),
            "stderr_bytes": len(result.stderr.encode("utf-8")),
            "stdout_sha256": _sha256_text(result.stdout),
            "stderr_sha256": _sha256_text(result.stderr),
            "stdout_summary": _summarise(result.stdout),
            "stderr_summary": _summarise(result.stderr, max_lines=4),
        }
        self.commands.append(env)
        self.results[result.cmd_id] = result
        return env

    def run(self, cmd_id, remote_argv):
        result = self.transport.run(cmd_id, remote_argv)
        env = self._record(result)
        if self.verbose:
            sys.stderr.write("[%s] exit=%s argv=%r\n"
                             % (cmd_id, result.exit_code, result.exec_argv))
        return result


# ---------------------------------------------------------------------------
# gate evaluation
# ---------------------------------------------------------------------------

@dataclass
class Verdict:
    reasons: list = field(default_factory=list)
    checks: list = field(default_factory=list)
    incomplete: bool = False
    failed: bool = False

    def fail(self, code, detail):
        self.failed = True
        self.reasons.append({"code": code, "detail": detail, "severity": "fail"})

    def need_more(self, code, detail):
        self.incomplete = True
        self.reasons.append({"code": code, "detail": detail,
                             "severity": "incomplete"})

    def check(self, cid, ok, detail):
        self.checks.append({"id": cid, "ok": ok, "detail": detail})

    @property
    def status(self):
        if self.failed:
            return "FAIL"
        if self.incomplete:
            return "INCOMPLETE"
        return "PASS"


def _select_primary_entry(entries, base_path, expected_isa):
    """Pick the primary-ABI entry for one dex container path."""
    candidates = [e for e in entries if e["dex_path"] == base_path]
    if not candidates:
        return None, "no dexopt entry for %s" % base_path
    marked = [e for e in candidates if e["primary_abi"]]
    if len(marked) == 1:
        return marked[0], None
    if len(marked) > 1:
        return None, "multiple [primary-abi] entries for %s" % base_path
    if expected_isa:
        by_isa = [e for e in candidates if e["isa"] == expected_isa]
        if len(by_isa) == 1:
            return by_isa[0], None
        return None, ("no unambiguously primary ABI among %r for %s"
                      % ([e["isa"] for e in candidates], base_path))
    return None, "no primary ABI marker and no device ABI to correlate"


def evaluate(package, expected_sha256, results, props, verbose=False, allow_local_apk_inspection=False):
    """Turn collected raw results into a verdict + report fragments."""
    verdict = Verdict()

    # --- device --------------------------------------------------------
    device = {
        "sdk": props.get("ro.build.version.sdk"),
        "release": props.get("ro.build.version.release"),
        "abilist": props.get("ro.product.cpu.abilist"),
        "primaryAbi": props.get("ro.product.cpu.abi"),
        "fingerprint": props.get("ro.build.fingerprint"),
        "ro_kernel_qemu": props.get("ro.kernel.qemu"),
        "props_command_ok": results.get(CMD_GETPROP) is not None
        and results[CMD_GETPROP].exit_code == 0,
    }
    abilist = [a for a in (device["abilist"] or "").split(",") if a]
    device["emulator_suspected"] = bool(
        device["ro_kernel_qemu"] in ("1", "true")
        or "generic" in (device["fingerprint"] or "")
        or "sdk_gphone" in (device["fingerprint"] or "")
        or "ranchu" in (device["fingerprint"] or ""))
    expected_isa = abi_to_isa(device["primaryAbi"] or (abilist[0] if abilist else None))

    # --- package state -------------------------------------------------
    artifacts = {
        "package": package,
        "codePath": None,
        "primaryCpuAbi": None,
        "versionCode": None,
        "versionName": None,
        "minSdk": None,
        "targetSdk": None,
        "splits": [],
        "apkSigningVersion": None,
        "timeStamp": None,
        "apk_paths": [],
        "expected_apk_sha256": expected_sha256,
        "hash_all_match": False,
        "hash_complete": False,
    }
    package_flags = {"raw": None, "list": None, "debuggable": None,
                     "source": None, "status": "unknown"}

    dumpsys = results.get(CMD_DUMPSYS_PACKAGE)
    if dumpsys is None or dumpsys.exit_code != 0:
        verdict.need_more("package-state-unavailable",
                          "`dumpsys package` unavailable (exit=%s)"
                          % (None if dumpsys is None else dumpsys.exit_code))
    else:
        info = parse_dumpsys_package(dumpsys.stdout, package)
        if info is None:
            verdict.need_more("package-state-unparsable",
                              "no `Package [%s]` block in `dumpsys package` "
                              "output (OEM format?)" % package)
        else:
            for key in ("codePath", "primaryCpuAbi", "versionCode", "versionName",
                        "minSdk", "targetSdk", "splits", "apkSigningVersion",
                        "timeStamp"):
                artifacts[key] = info[key]
            package_flags = {
                "raw": info["flags_raw"],
                "list": info["flags"],
                "debuggable": None if info["flags"] is None
                else "DEBUGGABLE" in info["flags"],
                "source": "`dumpsys package %s` field %s=" % (package,
                                                              info["flags_source"]),
                "status": "known" if info["flags"] is not None else "unparsable",
            }
            if info["flags"] is None:
                verdict.need_more("package-flags-unparsable",
                                  "flags= line missing or not bracketed in the "
                                  "`Package [%s]` block; DEBUGGABLE cannot be "
                                  "excluded" % package)
            elif "DEBUGGABLE" in info["flags"]:
                verdict.fail("package-debuggable",
                             "package flags contain DEBUGGABLE (%r)"
                             % info["flags_raw"])
            else:
                verdict.check("package-not-debuggable", True,
                              "flags=%r" % info["flags_raw"])
            verdict.check("package-flags-known", info["flags"] is not None,
                          "flags=%r" % info["flags_raw"])

    # --- artifact identity --------------------------------------------
    pm_path = results.get(CMD_PM_PATH)
    apk_paths = []
    if pm_path is None or pm_path.exit_code != 0:
        verdict.need_more("pm-path-unavailable",
                          "`pm path` unavailable (exit=%s)"
                          % (None if pm_path is None else pm_path.exit_code))
    else:
        try:
            apk_paths = parse_pm_path(pm_path.stdout)
        except GateError as error:
            verdict.need_more("pm-path-unparsable", str(error))
            apk_paths = []
        if not apk_paths and not verdict.incomplete:
            verdict.need_more("pm-path-empty",
                              "`pm path %s` returned no APK path" % package)

    base_paths = [p for p in apk_paths if p.endswith("/base.apk")]
    base_path = base_paths[0] if len(base_paths) == 1 else None
    if apk_paths and base_path is None:
        verdict.need_more("base-apk-not-identified",
                          "could not identify a unique base.apk among %r" % apk_paths)

    hashes = {}
    if apk_paths:
        sha_result = results.get(CMD_SHA256SUM)
        if sha_result is None or sha_result.exit_code != 0:
            verdict.need_more("artifact-sha256-unavailable",
                              "`sha256sum` of the installed APK(s) failed "
                              "(exit=%s)"
                              % (None if sha_result is None else sha_result.exit_code))
        else:
            try:
                hashes = parse_sha256sum(sha_result.stdout)
            except GateError as error:
                verdict.need_more("artifact-sha256-unparsable", str(error))
                hashes = {}
            missing = [p for p in apk_paths if p not in hashes]
            extra = [p for p in hashes if p not in apk_paths]
            if missing or extra:
                verdict.need_more(
                    "artifact-sha256-incomplete",
                    "hash set does not match `pm path` exactly (missing=%r extra=%r)"
                    % (missing, extra))
            else:
                artifacts["hash_complete"] = True

    apk_rows = []
    for path in apk_paths:
        row = {"path": path,
               "is_base": path == base_path,
               "sha256": hashes.get(path),
               "matches_expected": None,
               "code_containers": None,
               "code_state": None}
        if row["is_base"] and row["sha256"] is not None:
            row["matches_expected"] = (row["sha256"] == expected_sha256)
        if not row["is_base"]:
            # An adb path belongs to the remote filesystem, never this host.
            state, containers = (apk_has_code(path) if allow_local_apk_inspection
                                 else ("unknown", []))
            row["code_state"] = state
            row["code_containers"] = containers
        apk_rows.append(row)
    artifacts["apk_paths"] = apk_rows
    if apk_rows:
        artifacts["hash_all_match"] = all(
            row["matches_expected"] in (True, None) for row in apk_rows)

    if base_path is not None and hashes.get(base_path) is None:
        pass  # already reported as artifact-sha256-*
    elif base_path is not None and hashes[base_path] != expected_sha256:
        verdict.fail("artifact-sha256-mismatch",
                     "installed base.apk sha256=%s does not match expected=%s"
                     % (hashes[base_path], expected_sha256))
    elif base_path is not None:
        verdict.check("artifact-sha256-match", True,
                      "base.apk sha256=%s" % hashes[base_path])

    # --- dexopt state --------------------------------------------------
    dexopt = {
        "source": None,
        "sources_tried": [],
        "entries": [],
        "primary_code_path": None,
        "warnings": [],
        "requested_filter_used": False,
        "note": ("only on-device dexopt status is read; no install/compile "
                 "request flag is consulted"),
    }
    parsed_by_source = {}

    art_dump = results.get(CMD_ART_DUMP)
    if art_dump is not None:
        parsed = parse_dexopt_state(art_dump.stdout) if art_dump.exit_code == 0 else {"blocks": {}, "warnings": []}
        n_entries = sum(len(b["primary"]) for b in parsed["blocks"].values())
        dexopt["sources_tried"].append({
            "id": "art-service-dump",
            "command_id": CMD_ART_DUMP,
            "exit_code": art_dump.exit_code,
            "parsed_primary_entries": n_entries,
            "available": art_dump.exit_code == 0 and n_entries > 0,
            "note": "`cmd package art dump <pkg>` (ART service; API 34+)",
        })
        if art_dump.exit_code == 0 and n_entries:
            parsed_by_source["art-service-dump"] = parsed

    if dumpsys is not None and dumpsys.exit_code == 0:
        section = extract_dexopt_state_section(dumpsys.stdout)
        parsed = parse_dexopt_state(section) if section is not None else {"blocks": {}, "warnings": []}
        n_entries = sum(len(b["primary"]) for b in parsed["blocks"].values())
        dexopt["sources_tried"].append({
            "id": "dumpsys-package-dexopt-state",
            "command_id": CMD_DUMPSYS_PACKAGE,
            "exit_code": dumpsys.exit_code,
            "parsed_primary_entries": n_entries,
            "available": section is not None and n_entries > 0,
            "note": "`dumpsys package <pkg>` \"Dexopt state:\" section",
        })
        if section is not None and n_entries:
            parsed_by_source["dumpsys-package-dexopt-state"] = parsed

    if not parsed_by_source:
        verdict.need_more(
            "dexopt-state-unparsable",
            "no read-only device source yielded a parsable dexopt entry for %s; "
            "tried=%r" % (package,
                          [(s["id"], s["available"]) for s in dexopt["sources_tried"]]))
    else:
        # prefer the ART-service dump; refuse to guess when sources disagree
        source = ("art-service-dump" if "art-service-dump" in parsed_by_source
                  else "dumpsys-package-dexopt-state")
        dexopt["source"] = source
        parsed = parsed_by_source[source]
        dexopt["warnings"] = parsed["warnings"]
        block = parsed["blocks"].get(package)
        if block is None or not block["primary"]:
            verdict.need_more("dexopt-package-entry-missing",
                              "dexopt state contains no entry for [%s]"
                              % package)
        else:
            dexopt["entries"] = block["primary"]
            for entry in dexopt["entries"]:
                entry["filter_is_aot"] = entry["status"] in AOT_FILTERS

        if source == "art-service-dump" and "dumpsys-package-dexopt-state" in parsed_by_source:
            other = parsed_by_source["dumpsys-package-dexopt-state"]["blocks"].get(package)
            if other and other["primary"] and block and block["primary"]:
                a = block["primary"][0]
                b = other["primary"][0]
                if (a["status"], a["isa"]) != (b["status"], b["isa"]):
                    verdict.need_more(
                        "dexopt-source-conflict",
                        "ART service dump says %s/%s while dumpsys says %s/%s"
                        % (a["status"], a["isa"], b["status"], b["isa"]))

        if base_path is not None:
            primary, why = _select_primary_entry(block["primary"], base_path,
                                                 expected_isa) if block else (None, "no dexopt block")
            if primary is None:
                if block is not None and why:
                    verdict.need_more("dexopt-primary-abi-ambiguous", why)
            else:
                dexopt["primary_code_path"] = dict(primary)
                if primary["status"] not in AOT_FILTERS:
                    verdict.fail(
                        "dexopt-primary-filter-not-aot",
                        "primary code path %s reports status=%s reason=%s "
                        "(baseline requires %s)"
                        % (primary["dex_path"], primary["status"],
                           primary["reason"], "/".join(AOT_FILTERS)))
                else:
                    verdict.check("dexopt-primary-filter-aot", True,
                                  "status=%s reason=%s" % (primary["status"],
                                                           primary["reason"]))
                    if primary["location_is_error"]:
                        verdict.need_more(
                            "dexopt-location-unresolved",
                            "status=%s but the dexopt location resolved to %r; "
                            "the AOT artifact cannot be confirmed (fail-closed)"
                            % (primary["status"], primary["location_debug_string"]))
                    else:
                        verdict.check("dexopt-location-resolved", True,
                                      primary["location_debug_string"])

        # splits that own code must not be skipped
        if block is not None:
            for row in apk_rows:
                if row["is_base"] or row["path"] not in [p for p in apk_paths]:
                    continue
                entries = [e for e in block["primary"] if e["dex_path"] == row["path"]]
                if entries:
                    primary, why = _select_primary_entry(block["primary"],
                                                         row["path"], expected_isa)
                    if primary is None:
                        verdict.need_more("split-code-path-unverified", why or "split primary ABI unresolved")
                    elif primary["status"] not in AOT_FILTERS:
                        verdict.fail("split-code-path-not-aot",
                                     "split %s status=%s reason=%s"
                                     % (row["path"], primary["status"],
                                        primary["reason"]))
                    else:
                        verdict.check("split-code-path-aot", True, row["path"])
                else:
                    state = row["code_state"]
                    if state == "no-code":
                        verdict.check("split-without-code", True,
                                      "%s carries no classes*.dex (%r)"
                                      % (row["path"], row["code_containers"]))
                    elif state == "has-code":
                        verdict.fail(
                            "split-code-path-not-aot",
                            "split %s carries %r but the device reports no "
                            "dexopt entry for it" % (row["path"],
                                                     row["code_containers"]))
                    else:
                        verdict.need_more(
                            "split-code-path-unverified",
                            "split %s could not be inspected for dex containers "
                            "and has no dexopt entry; code coverage of splits "
                            "cannot be excluded (fail-closed)" % row["path"])
    return verdict, device, artifacts, package_flags, dexopt, apk_paths


def maps_evidence(collector, pid, code_path=None):
    if pid is None:
        return {"available": False, "reason": "package process not running"}
    result = collector.run(CMD_MAPS_GREP,
                           ["/system/bin/grep", "-E", MAPS_GREP_PATTERN,
                            "/proc/%d/maps" % pid])
    lines = [l for l in result.stdout.splitlines() if l.strip()]
    related = [l for l in lines if code_path and code_path in l]
    return {
        "available": result.exit_code in (0, 1),
        "pid": pid,
        "matched_lines": len(lines),
        "code_path_related_lines": len(related),
        "code_path_sample": related[:10],
        "sample": lines[:20],
        "exit_code": result.exit_code,
        "note": ("filtered subset of /proc/<pid>/maps (grep -E %s); extra "
                 "evidence only, never a hard failure" % MAPS_GREP_PATTERN),
    }


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def build_parser():
    parser = argparse.ArgumentParser(
        prog="beta_art_gate.py",
        description="Read-only ART AOT readiness gate for a beta APK "
                    "(beta-certification line: art).")
    parser.add_argument("--transport", required=True,
                        help="local-root (host, su -c) or adb (remote, -s SERIAL)")
    parser.add_argument("--serial", default=None,
                        help="adb serial; required (and only meaningful) for "
                             "--transport adb")
    parser.add_argument("--package", required=True, help="package name to certify")
    parser.add_argument("--expected-apk-sha256", required=True,
                        help="expected SHA-256 of the installed base.apk "
                             "(64 hex chars, no prefix)")
    parser.add_argument("--out-dir", required=True,
                        help="directory for report.json and raw/ captures")
    parser.add_argument("--adb", default="adb", help="adb executable (default: adb)")
    parser.add_argument("--su", default=None, help="su executable (default: from PATH)")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                        help="per-command timeout in seconds (default: %d)" % DEFAULT_TIMEOUT)
    parser.add_argument("--verbose", action="store_true")
    return parser


def default_make_transport(name, args, exec_fn=None):
    if name == "local-root":
        return LocalRootTransport(su=args.su, timeout=args.timeout, exec_fn=exec_fn)
    if name == "adb":
        return AdbTransport(args.serial, adb=args.adb, timeout=args.timeout,
                            exec_fn=exec_fn)
    raise GateError("unknown transport %r (expected local-root or adb)" % name)


def _base_report(args, transport):
    return {
        "schema": SCHEMA,
        "line": LINE,
        "generated_at": datetime.datetime.now().astimezone().isoformat(),
        "repo_head": _local_repo_head(),
        "passed": False,
        "status": "ERROR",
        "reasons": [],
        "checks": [],
        "scope_disclaimers": list(SCOPE_DISCLAIMERS),
        "transport": getattr(transport, "name", args.transport),
        "evidence_tier": getattr(transport, "evidence_tier", "unknown"),
        "cli": {
            "argv": list(args._argv) if hasattr(args, "_argv") else None,
            "transport": args.transport,
            "serial": args.serial if args.transport == "adb" else None,
            "package": args.package,
            "expected_apk_sha256_input": args.expected_apk_sha256,
            "expected_apk_sha256": None,
            "out_dir": str(args.out_dir),
        },
        "device": {},
        "artifact": {},
        "package_flags": {},
        "dexopt": {},
        "maps": {},
        "commands": [],
        "raw_dir": str(Path(args.out_dir) / "raw"),
        "unfinished": [],
    }


def main(argv=None, make_transport=None, exec_fn=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    args._argv = list(argv) if argv is not None else sys.argv[1:]
    if make_transport is None:
        make_transport = lambda name, a: default_make_transport(name, a, exec_fn)

    problems = []
    expected = None
    try:
        expected = normalise_sha256(args.expected_apk_sha256)
    except GateError as error:
        problems.append(("usage-invalid-expected-sha256", str(error)))
    if args.transport not in ("local-root", "adb"):
        problems.append(("usage-unknown-transport",
                         "unknown --transport %r (expected local-root or adb)"
                         % args.transport))
    if args.transport == "adb" and not (args.serial or "").strip():
        problems.append(("usage-missing-serial-for-adb",
                         "--transport adb requires an explicit --serial so that no "
                         "command can fall back to the local machine"))

    if problems:
        report = _base_report(args, None)
        report["transport"] = args.transport
        report["evidence_tier"] = "none (refused before any device command)"
        report["reasons"] = [{"code": code, "detail": detail, "severity": "error"}
                             for code, detail in problems]
        report["cli"]["expected_apk_sha256"] = expected
        report["unfinished"] = ["refused before collection; nothing was executed"]
        try:
            write_report(args.out_dir, report)
        except OSError as error:
            sys.stderr.write("could not write report: %s\n" % error)
        for code, detail in problems:
            sys.stderr.write("REFUSED %s: %s\n" % (code, detail))
        return EXIT_USAGE

    try:
        transport = make_transport(args.transport, args)
    except GateError as error:
        report = _base_report(args, None)
        report["reasons"] = [{"code": "usage-transport-unavailable",
                              "detail": str(error), "severity": "error"}]
        report["cli"]["expected_apk_sha256"] = expected
        write_report(args.out_dir, report)
        sys.stderr.write("REFUSED: %s\n" % error)
        return EXIT_USAGE

    report = _base_report(args, transport)
    report["cli"]["expected_apk_sha256"] = expected
    collector = Collector(transport, args.out_dir, verbose=args.verbose)

    # 1. device facts
    collector.run(CMD_GETPROP, ["/system/bin/getprop"])
    # 2. package paths + state
    collector.run(CMD_PM_PATH, ["/system/bin/pm", "path", args.package])
    collector.run(CMD_DUMPSYS_PACKAGE, ["/system/bin/dumpsys", "package", args.package])
    # 3. ART service dexopt dump (authoritative read-only source; may be absent
    #    on API < 34 -- absence is recorded, not guessed around)
    collector.run(CMD_ART_DUMP, ["/system/bin/cmd", "package", "art", "dump",
                                 args.package])
    # 4. hash every APK path the device reports (base + any split)
    results = collector.results
    apk_paths = []
    pm = results.get(CMD_PM_PATH)
    if pm is not None and pm.exit_code == 0:
        try:
            apk_paths = parse_pm_path(pm.stdout)
        except GateError:
            apk_paths = []
    if apk_paths:
        collector.run(CMD_SHA256SUM, ["/system/bin/sha256sum"] + apk_paths)
    # 5. optional pid + maps context (never gating)
    collector.run(CMD_PIDOF, ["/system/bin/pidof", args.package])
    pid_result = collector.results.get(CMD_PIDOF)
    pid = None
    if pid_result is not None and pid_result.exit_code == 0:
        first = [l.strip() for l in pid_result.stdout.splitlines() if l.strip()]
        if first and first[0].isdigit():
            pid = int(first[0])
    report["pid"] = pid

    props = parse_getprop_dump(results[CMD_GETPROP].stdout) \
        if results.get(CMD_GETPROP) is not None else {}

    verdict, device, artifacts, package_flags, dexopt, apk_paths = evaluate(
        args.package, expected, results, props, verbose=args.verbose,
        allow_local_apk_inspection=args.transport == "local-root")

    # transport-level failure: the very first command could not run at all, so
    # nothing below is evidence about the device -> ERROR, never INCOMPLETE/PASS
    props_result = results.get(CMD_GETPROP)
    transport_broken = props_result is not None and (
        props_result.exit_code != 0 or props_result.timed_out)
    if not props:
        verdict.need_more("device-props-unavailable",
                          "`getprop` produced no properties; device identity "
                          "(SDK/ABI) is unknown")
    report["device"] = device
    report["artifact"] = artifacts
    report["package_flags"] = package_flags
    report["dexopt"] = dexopt
    report["maps"] = maps_evidence(collector, pid, artifacts.get("codePath"))
    report["commands"] = collector.commands
    report["reasons"] = verdict.reasons
    report["checks"] = verdict.checks
    if transport_broken:
        report["reasons"].insert(0, {
            "code": "transport-error",
            "detail": "the transport could not execute any device command "
                      "(exit=%s): %s" % (props_result.exit_code,
                                         _summarise(props_result.stderr, 2)),
            "severity": "error"})
        report["status"] = "ERROR"
    else:
        report["status"] = verdict.status
    report["passed"] = (report["status"] == "PASS")
    report["unfinished"] = [
        "warm-method AOT confirmation and any zero-JIT claim are out of scope "
        "for this gate",
        "real speed/everything positive control on CI is owned by the beta-cert "
        "parent, not this tool",
    ]

    write_report(args.out_dir, report)

    # short stdout summary only; raw text lives in raw/
    print("beta-art gate: %s (exit %d)"
          % (report["status"], EXIT_PASS if report["passed"] else EXIT_NOT_PASSED))
    print("package=%s versionCode=%s versionName=%s"
          % (artifacts.get("package"), artifacts.get("versionCode"),
             artifacts.get("versionName")))
    for row in artifacts.get("apk_paths", []):
        print("apk %s%s sha256=%s%s"
              % ("base " if row["is_base"] else "split", row["path"],
                 row["sha256"],
                 "" if row["matches_expected"] is None
                 else " matches_expected=%s" % row["matches_expected"]))
    print("flags=%r debuggable=%s" % (package_flags.get("raw"),
                                      package_flags.get("debuggable")))
    primary = dexopt.get("primary_code_path")
    print("dexopt source=%s primary=%s status=%s reason=%s"
          % (dexopt.get("source"),
             primary and primary.get("dex_path"), primary and primary.get("status"),
             primary and primary.get("reason")))
    for reason in report["reasons"]:
        print("  %-8s %s: %s" % (reason["severity"].upper(), reason["code"],
                                 reason["detail"]))
    print("report=%s" % (Path(args.out_dir) / "report.json"))

    return EXIT_PASS if report["passed"] else EXIT_NOT_PASSED


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GateError as error:
        sys.stderr.write("FAIL beta-art gate: %s\n" % error)
        raise SystemExit(EXIT_NOT_PASSED)
