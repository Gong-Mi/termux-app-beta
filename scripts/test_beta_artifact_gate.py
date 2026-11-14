#!/usr/bin/env python3
"""Contract tests for the beta artifact identity gate.

Scope and evidence layering:
  * Every APK / DEX / badging / certificate input used here is an explicitly
    synthetic fixture built in this file. Nothing here is a real APK, a real
    signing identity, a real build or a real device result.
  * Parsers and the gate are exercised through both an injected fake runner and
    real subprocess invocations of tiny executable tool stubs, so the CLI exit
    code contract (0 pass / 1 gate failure / 2 input or execution error) is
    tested end to end without Gradle, network access or a device.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import beta_artifact_gate as gate  # noqa: E402

DEX_HEADER_SIZE = 0x70

# Baseline head of the certification branch: a real SHA-1 taken from this repo,
# used only as a string fixture (it says nothing about any APK).
TEST_HEAD = "c93d5b87dd26ace6170053e0645d9d89525e801e"
OTHER_HEAD = "0b5e9286" + "0" * 32

# Synthetic certificate fingerprint: SHA-256 over a literal fixture label. It is
# NOT a production signing identity and must never be treated as one.
TEST_CERT_SHA256 = hashlib.sha256(b"beta-artifact-gate synthetic certificate fixture").hexdigest()
OTHER_CERT_SHA256 = hashlib.sha256(b"beta-artifact-gate other synthetic certificate").hexdigest()

ACTIVITY = "Lcom/termux/app/TermuxActivity;"

# Gates that describe the given arguments and the head sidecar rather than the
# artifact bytes: they stay evaluable even when the APK itself is missing.
INPUT_GATES = ("expected_head_format", "build_head_literal", "head_match",
               "expected_cert_sha256_format")

BADGING_OK = """package: name='com.termux' versionCode='1022' versionName='0.119.0-beta.2' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
sdkVersion:'24'
targetSdkVersion:'28'
uses-permission: name='android.permission.INTERNET'
application-label:'Termux'
application: label='Termux' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
launchable-activity: name='com.termux.app.TermuxActivity'  label='Termux' icon=''
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
"""

BADGING_DEBUG = BADGING_OK.replace(
    "application: label='Termux'",
    "application: label='Termux'\napplication-debuggable\napplication-usesCleartextTraffic",
)

BADGING_ABI_MISSING = BADGING_OK.replace(
    "native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'",
    "native-code: 'x86_64'",
)


def certs_output(cert_sha256: str, *, signers: int = 1) -> str:
    blocks = []
    for index in range(1, signers + 1):
        digest = cert_sha256 if index == 1 else OTHER_CERT_SHA256
        blocks.append(
            "Signer #%d certificate DN: CN=Android Debug, O=Android, C=US\n"
            "Signer #%d certificate SHA-256 digest: %s\n"
            "Signer #%d certificate SHA-1 digest: %s\n"
            "Signer #%d certificate MD5 digest: %s\n"
            % (index, index, digest, index, "aa" * 20, index, "bb" * 16)
        )
    return (
        "Verifies\n"
        "Verified using v1 scheme (JAR signing): true\n"
        "Verified using v2 scheme (APK Signature Scheme v2): true\n"
        "Verified using v3 scheme (APK Signature Scheme v3): true\n"
        "Number of signers: %d\n" % signers
    ) + "".join(blocks)


def colon_form(digest: str, separator: str = ":") -> str:
    return separator.join(digest[i : i + 2] for i in range(0, len(digest), 2))


# --------------------------------------------------------------------------
# synthetic DEX / APK fixture builders
# --------------------------------------------------------------------------


def _uleb128(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _put_u32(buf: bytearray, offset: int, value: int) -> None:
    buf[offset : offset + 4] = int(value).to_bytes(4, "little")


def rebuild_dex_digests(data: bytes) -> bytes:
    """Recompute DEX signature + Adler-32 checksum.

    Used to craft self-consistent malformed fixtures, so a structural defect can
    be tested independently of the checksum gate.
    """
    buf = bytearray(data)
    buf[12:32] = hashlib.sha1(bytes(buf[32:])).digest()
    _put_u32(buf, 8, zlib.adler32(bytes(buf[12:])) & 0xFFFFFFFF)
    return bytes(buf)


def build_dex(strings, *, magic: bytes = b"dex\n035\0", endian_tag: int = 0x12345678) -> bytes:
    """Build a structurally valid minimal DEX carrying a string table."""
    strings = list(strings)
    descriptors = [i for i, text in enumerate(strings) if text.startswith("L") and text.endswith(";")]
    type_off = DEX_HEADER_SIZE + 4 * len(strings)
    class_off = type_off + 4 * len(descriptors)
    base_data_off = class_off + 32 * len(descriptors)
    data = bytearray()
    offsets = []
    for text in strings:
        offsets.append(base_data_off + len(data))
        data += _uleb128(len(text)) + text.encode("utf-8") + b"\x00"
    while len(data) % 4:
        data += b"\x00"
    file_size = base_data_off + len(data)
    buf = bytearray(file_size)
    buf[0:8] = magic
    _put_u32(buf, 32, file_size)
    _put_u32(buf, 36, DEX_HEADER_SIZE)
    _put_u32(buf, 40, endian_tag)
    _put_u32(buf, 56, len(strings))
    _put_u32(buf, 60, DEX_HEADER_SIZE if strings else 0)
    _put_u32(buf, 64, len(descriptors))
    _put_u32(buf, 68, type_off if descriptors else 0)
    _put_u32(buf, 96, len(descriptors))
    _put_u32(buf, 100, class_off if descriptors else 0)
    for index, string_index in enumerate(descriptors):
        _put_u32(buf, type_off + 4 * index, string_index)
        _put_u32(buf, class_off + 32 * index, index)
        _put_u32(buf, class_off + 32 * index + 8, 0xffffffff)
    _put_u32(buf, 104, len(data))
    _put_u32(buf, 108, base_data_off if data else 0)
    for index, offset in enumerate(offsets):
        _put_u32(buf, DEX_HEADER_SIZE + 4 * index, offset)
    buf[base_data_off:] = data
    return rebuild_dex_digests(bytes(buf))


DEFAULT_DEX_STRINGS = [
    ACTIVITY,
    "Lcom/termux/app/TermuxService;",
    "Lcom/termux/shared/termux/TermuxConstants;",
    "onCreate",
]


def make_apk(path: Path, entries) -> Path:
    """Write a ZIP shaped like an APK. Not a real APK: no binary manifest."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(entries, dict):
        entries = list(entries.items())
    provided = {name for name, _ in entries}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        if "AndroidManifest.xml" not in provided:
            archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00" + b"\x00" * 96)
        if "resources.arsc" not in provided:
            archive.writestr("resources.arsc", b"\x02\x00\x0c\x00" + b"\x00" * 32)
        for name, blob in entries:
            archive.writestr(name, blob)
    return path


def fake_apk(path: Path, strings=DEFAULT_DEX_STRINGS, *, extra_entries=(), malformed_dex=False) -> Path:
    """An APK containing classes.dex (plus optional extra raw entries)."""
    blob = build_dex(strings)
    if malformed_dex:
        damaged = bytearray(blob)
        damaged[0:8] = b"junk\n035"
        blob = bytes(damaged)
    return make_apk(path, [("classes.dex", blob), *extra_entries])


# --------------------------------------------------------------------------
# runners
# --------------------------------------------------------------------------


def _result(argv, rc=0, stdout="", stderr="", error=None):
    return {"argv": list(argv), "rc": rc, "stdout": stdout, "stderr": stderr, "error": error}


class FakeRunner:
    """Deterministic runner: maps an executable path to a canned response.

    `unusable` mimics what SubprocessRunner.check reports for a tool that exists
    but cannot be executed (missing file, no exec bit); such tools must never be
    run and must end as an input error.
    """

    def __init__(self, handlers=None, unusable=None):
        self.handlers = dict(handlers or {})
        self.unusable = dict(unusable or {})
        self.calls = []

    def check(self, tool, label="tool"):
        return self.unusable.get(str(tool))

    def run(self, argv):
        argv = [str(item) for item in argv]
        self.calls.append(argv)
        handler = self.handlers.get(argv[0])
        if handler is None:
            return _result(argv, rc=None, error="no fake handler for %s" % argv[0])
        return handler(argv)


def aapt_handler(badging_text=BADGING_OK, *, rc=0, stderr="", error=None):
    def handler(argv):
        if "badging" in argv:
            return _result(argv, rc=rc, stdout=badging_text, stderr=stderr, error=error)
        if len(argv) > 1 and argv[1] == "version":
            return _result(argv, rc=0, stdout="Android Asset Packaging Tool, v0.2-synthetic-fixture\n")
        return _result(argv, rc=1, stderr="unsupported aapt invocation")

    return handler


def apksigner_handler(certs=None, *, rc=0, stderr="", error=None):
    certs = certs_output(TEST_CERT_SHA256) if certs is None else certs

    def handler(argv):
        return _result(argv, rc=rc, stdout=certs, stderr=stderr, error=error)

    return handler


FAKE_AAPT = "/fake/tools/aapt"
FAKE_APKSIGNER = "/fake/tools/apksigner"


class GateTestCase(unittest.TestCase):
    """Shared fixture plumbing: temp dir, fixture APK, config, fake runner."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="beta-artifact-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.apk = fake_apk(self.tmp / "termux-app-beta-universal.apk")

    def write(self, name: str, text: str) -> str:
        path = self.tmp / name
        path.write_text(text, encoding="utf-8")
        return str(path)

    def digest_of(self, apk: Path) -> str:
        try:
            return gate.sha256_file(Path(apk))
        except OSError:
            return "0" * 64  # placeholder: the gate must still fail closed

    def config(self, *, apk=None, expected_head=TEST_HEAD, build_head=None, sha256_text=None,
               cert=TEST_CERT_SHA256, aapt=FAKE_AAPT, apksigner=FAKE_APKSIGNER):
        apk = Path(apk or self.apk)
        if build_head is None:
            build_head = TEST_HEAD + "\n"
        if sha256_text is None:
            sha256_text = "%s  %s\n" % (self.digest_of(apk), apk.name)
        return {
            "apk": str(apk),
            "expected_head": expected_head,
            "build_head_path": self.write("build-head.txt", build_head),
            "sha256_file_path": self.write("apk.sha256", sha256_text),
            "expected_cert_sha256": cert,
            "aapt": aapt,
            "apksigner": apksigner,
        }

    def run_gate(self, config, unusable=None, **handlers):
        certs = certs_output(config["expected_cert_sha256"] or "")
        runner = FakeRunner(
            {
                config["aapt"]: handlers.get("aapt", aapt_handler()),
                config["apksigner"]: handlers.get("apksigner", apksigner_handler(certs)),
            },
            unusable=unusable,
        )
        return gate.evaluate(config, runner), runner

    def gate_by_id(self, report, gate_id):
        for entry in report["gates"]:
            if entry["id"] == gate_id:
                return entry
        self.fail("report has no gate %r; gates=%s" % (gate_id, [g["id"] for g in report["gates"]]))

    def failed_ids(self, report):
        return [entry["id"] for entry in report["gates"] if not entry["passed"]]


# --------------------------------------------------------------------------
# parser level tests
# --------------------------------------------------------------------------


class HeadParsingTest(unittest.TestCase):
    def test_accepts_40_hex(self):
        self.assertTrue(gate.is_valid_git_head(TEST_HEAD))
        self.assertTrue(gate.is_valid_git_head(TEST_HEAD.upper()))

    def test_rejects_short_long_non_hex_and_none(self):
        for value in ("c93d5b87", "", TEST_HEAD + "a", TEST_HEAD[:-1], "z" * 40, None, 40):
            with self.subTest(value=value):
                self.assertFalse(gate.is_valid_git_head(value), value)

    def test_build_head_accepts_one_full_sha_with_surrounding_whitespace(self):
        self.assertEqual(gate.parse_build_head(TEST_HEAD + "\n"), (TEST_HEAD, None))
        self.assertEqual(gate.parse_build_head("  %s  " % TEST_HEAD), (TEST_HEAD, None))

    def test_build_head_rejects_anything_that_is_not_exactly_one_sha(self):
        cases = {
            "empty": "",
            "whitespace only": "  \n",
            "prefixed": "HEAD=%s" % TEST_HEAD,
            "two tokens": "%s\n%s" % (TEST_HEAD, OTHER_HEAD),
            "same line two tokens": "%s %s" % (TEST_HEAD, OTHER_HEAD),
            "short": "c93d5b87",
            "41 chars": "%s1" % TEST_HEAD,
            "trailing label": "%s  extra" % TEST_HEAD,
        }
        for label, text in cases.items():
            with self.subTest(case=label):
                literal, reason = gate.parse_build_head(text)
                self.assertIsNone(literal, label)
                self.assertTrue(reason, label)
                self.assertIn("40-hex", reason)


class CertParsingTest(unittest.TestCase):
    def test_normalize_strips_only_colons_and_whitespace(self):
        self.assertEqual(gate.normalize_cert_sha256(colon_form(TEST_CERT_SHA256)), TEST_CERT_SHA256)
        self.assertEqual(gate.normalize_cert_sha256(colon_form(TEST_CERT_SHA256, "  ")), TEST_CERT_SHA256)
        self.assertEqual(gate.normalize_cert_sha256(TEST_CERT_SHA256.upper()), TEST_CERT_SHA256)

    def test_normalize_does_not_repair_short_or_broken_values(self):
        self.assertEqual(gate.normalize_cert_sha256("aa:bb:cc"), "aabbcc")
        self.assertNotEqual(len(gate.normalize_cert_sha256("aa" * 20)), 64)
        self.assertFalse(gate.is_valid_sha256_hex(gate.normalize_cert_sha256("aa" * 20)))

    def test_parse_colon_form_from_apksigner(self):
        parsed = gate.parse_apksigner_cert_digests(certs_output(colon_form(TEST_CERT_SHA256)))
        self.assertEqual([entry["normalized"] for entry in parsed["digests"]], [TEST_CERT_SHA256])
        self.assertEqual(parsed["malformed"], [])

    def test_parse_reports_malformed_digest_line_instead_of_guessing(self):
        text = certs_output(TEST_CERT_SHA256).replace(TEST_CERT_SHA256, "deadbeef", 1)
        parsed = gate.parse_apksigner_cert_digests(text)
        self.assertEqual(parsed["malformed"], ["deadbeef"])

    def test_parse_ignores_unrelated_lines(self):
        parsed = gate.parse_apksigner_cert_digests(certs_output(TEST_CERT_SHA256))
        self.assertEqual(len(parsed["digests"]), 1)
        self.assertEqual(parsed["digests"][0]["signer"], 1)


class BadgingParsingTest(unittest.TestCase):
    def test_extracts_package_version_abi_debuggable(self):
        info = gate.parse_aapt_badging(BADGING_OK)
        self.assertEqual(info["package"], "com.termux")
        self.assertEqual(info["version_code"], "1022")
        self.assertEqual(info["version_name"], "0.119.0-beta.2")
        self.assertFalse(info["debuggable"])
        self.assertIn("arm64-v8a", info["native_code"])
        self.assertEqual(info["malformed"], [])

    def test_detects_debuggable_marker(self):
        info = gate.parse_aapt_badging(BADGING_DEBUG)
        self.assertTrue(info["debuggable"])
        self.assertEqual(info["debuggable_marker"], "application-debuggable")

    def test_flags_output_without_package_line(self):
        info = gate.parse_aapt_badging("adb: failed to run aapt\n")
        self.assertIsNone(info["package"])
        self.assertIn("missing package: line", info["malformed"])


class DexParsingTest(unittest.TestCase):
    def test_built_fixture_is_structurally_valid(self):
        blob = build_dex(DEFAULT_DEX_STRINGS)
        detail = gate.validate_dex(blob)
        self.assertTrue(detail["valid"], detail["reason"])
        self.assertEqual(detail["string_count"], len(DEFAULT_DEX_STRINGS))
        self.assertTrue(detail["checksum_ok"])

    def test_string_table_lookup_is_exact(self):
        blob = build_dex(DEFAULT_DEX_STRINGS)
        self.assertTrue(gate.dex_has_string(blob, ACTIVITY))
        self.assertFalse(gate.dex_has_string(blob, ACTIVITY[:-1]))
        self.assertFalse(gate.dex_has_string(blob, "Lcom/other/app/TermuxActivity;"))

    def test_malformed_dex_is_reported_with_a_reason(self):
        good = build_dex(DEFAULT_DEX_STRINGS)

        bad_magic = bytearray(good)
        bad_magic[0:8] = b"nope\n035"

        bad_endian = bytearray(good)
        _put_u32(bad_endian, 40, 0)

        bad_size = bytearray(good)
        _put_u32(bad_size, 32, len(good) + 512)

        bad_string_off = bytearray(good)
        _put_u32(bad_string_off, DEX_HEADER_SIZE, len(good) + 4096)

        bad_string_table_off = bytearray(good)
        _put_u32(bad_string_table_off, 60, len(good) + 4096)

        bad_checksum = bytearray(good)
        bad_checksum[100] ^= 0xFF

        cases = {
            "magic": bytes(bad_magic),
            "truncated": good[:64],
            "endian": rebuild_dex_digests(bytes(bad_endian)),
            "file_size": rebuild_dex_digests(bytes(bad_size)),
            "string_data_offset": rebuild_dex_digests(bytes(bad_string_off)),
            "string_ids_off": rebuild_dex_digests(bytes(bad_string_table_off)),
            "checksum": bytes(bad_checksum),
        }
        for label, blob in cases.items():
            with self.subTest(case=label):
                detail = gate.validate_dex(blob)
                self.assertFalse(detail["valid"], label)
                self.assertTrue(detail["reason"], label)
                with self.assertRaises(gate.DexFormatError):
                    gate.dex_has_string(blob, ACTIVITY)

    def test_appended_descriptor_bytes_do_not_satisfy_the_lookup(self):
        # A structurally valid DEX whose string table lacks the descriptor must
        # not be satisfied by those bytes appearing after the declared end.
        blob = build_dex(["Lcom/termux/app/NoSuchActivity;"]) + ACTIVITY.encode("utf-8") + b"\x00"
        detail = gate.validate_dex(blob)
        self.assertFalse(detail["valid"], "appended bytes must break file_size")
        self.assertFalse(gate.dex_has_string(build_dex(["Lcom/termux/app/Nothing;"]), ACTIVITY))


# --------------------------------------------------------------------------
# gate level tests (injected fake runner)
# --------------------------------------------------------------------------


class GateDecisionTest(GateTestCase):
    def test_success_case_passes_every_gate(self):
        report, _ = self.run_gate(self.config())
        self.assertEqual(self.failed_ids(report), [], report["gates"])
        self.assertTrue(report["passed"])
        self.assertEqual(report["exit_code"], 0)
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["apk"]["sha256"], gate.sha256_file(self.apk))
        self.assertGreater(report["apk"]["size_bytes"], 0)
        self.assertNotEqual(report["claims_not_proven"], [])

    def test_negative_control_debuggable_apk_is_rejected(self):
        report, _ = self.run_gate(self.config(), aapt=aapt_handler(BADGING_DEBUG))
        self.assertFalse(report["passed"])
        self.assertEqual(report["exit_code"], 1)
        entry = self.gate_by_id(report, "aapt_not_debuggable")
        self.assertFalse(entry["passed"])
        self.assertIn("application-debuggable", entry["reason"])
        self.assertTrue(self.gate_by_id(report, "aapt_package_name")["passed"])

    def test_negative_control_hash_mismatch_is_rejected(self):
        config = self.config()
        config["sha256_file_path"] = self.write("apk.sha256", "%s\n" % ("0" * 64))
        report, _ = self.run_gate(config)
        self.assertEqual(report["exit_code"], 1)
        entry = self.gate_by_id(report, "apk_sha256_match")
        self.assertFalse(entry["passed"])
        self.assertIn(gate.sha256_file(self.apk), entry["reason"])

    def test_negative_control_sha256_sidecar_without_digest_is_rejected(self):
        config = self.config()
        config["sha256_file_path"] = self.write("apk.sha256", "")
        report, _ = self.run_gate(config)
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "apk_sha256_match")["passed"])

    def test_negative_control_cert_mismatch_is_rejected(self):
        report, _ = self.run_gate(
            self.config(), apksigner=apksigner_handler(certs_output(OTHER_CERT_SHA256)))
        self.assertEqual(report["exit_code"], 1)
        self.assertTrue(self.gate_by_id(report, "apksigner_verified")["passed"])
        self.assertFalse(self.gate_by_id(report, "cert_sha256_match")["passed"])

    def test_negative_control_colon_form_expected_cert_matches(self):
        report, _ = self.run_gate(
            self.config(cert=colon_form(TEST_CERT_SHA256)),
            apksigner=apksigner_handler(certs_output(colon_form(TEST_CERT_SHA256))))
        self.assertTrue(report["passed"], report["gates"])

    def test_negative_control_two_signers_is_rejected(self):
        report, _ = self.run_gate(
            self.config(), apksigner=apksigner_handler(certs_output(TEST_CERT_SHA256, signers=2)))
        self.assertEqual(report["exit_code"], 1)
        entry = self.gate_by_id(report, "cert_sha256_match")
        self.assertFalse(entry["passed"])
        self.assertEqual(entry["detail"]["signer_digest_count"], 2)

    def test_negative_control_short_expected_cert_is_not_repaired(self):
        report, _ = self.run_gate(self.config(cert="aa" * 20))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "expected_cert_sha256_format")["passed"])

    def test_negative_control_wrong_head_is_rejected(self):
        report, _ = self.run_gate(self.config(build_head=OTHER_HEAD + "\n"))
        self.assertEqual(report["exit_code"], 1)
        entry = self.gate_by_id(report, "head_match")
        self.assertFalse(entry["passed"])
        self.assertIn(OTHER_HEAD, entry["reason"])

    def test_negative_control_malformed_head_is_not_repaired(self):
        report, _ = self.run_gate(self.config(expected_head=TEST_HEAD[:-1] + "Z"))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "expected_head_format")["passed"])

        report, _ = self.run_gate(self.config(expected_head="c93d5b87"))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "expected_head_format")["passed"])

        report, _ = self.run_gate(self.config(build_head="HEAD=%s\n" % TEST_HEAD))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "build_head_literal")["passed"])

    def test_negative_control_base_only_apk_without_activity_is_rejected(self):
        apk = fake_apk(self.tmp / "base-only.apk", ["Lcom/termux/app/TermuxService;", "onCreate"])
        report, _ = self.run_gate(self.config(apk=apk))
        self.assertEqual(report["exit_code"], 1)
        self.assertTrue(self.gate_by_id(report, "zip_has_dex")["passed"])
        self.assertTrue(self.gate_by_id(report, "dex_entries_valid")["passed"])
        entry = self.gate_by_id(report, "dex_has_termux_activity")
        self.assertFalse(entry["passed"])
        self.assertIn("TermuxActivity", entry["reason"])

    def test_negative_control_activity_bytes_outside_dex_are_not_enough(self):
        # Descriptor present only in a non-DEX entry: raw ZIP text scanning must
        # never satisfy the activity gate.
        apk = fake_apk(
            self.tmp / "text-only.apk",
            strings=["Lcom/termux/app/TermuxService;"],
            extra_entries=[("resources.arsc", ACTIVITY.encode("utf-8") + b"\x00" * 8)],
        )
        report, _ = self.run_gate(self.config(apk=apk))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "dex_has_termux_activity")["passed"])

    def test_negative_control_activity_in_secondary_dex_is_accepted(self):
        apk = fake_apk(
            self.tmp / "split.apk",
            ["Lcom/termux/app/TermuxService;"],
            extra_entries=[("classes2.dex", build_dex([ACTIVITY, "Landroid/app/Activity;"]))],
        )
        report, _ = self.run_gate(self.config(apk=apk))
        self.assertTrue(report["passed"], report["gates"])
        self.assertEqual(report["exit_code"], 0)
        self.assertEqual(self.gate_by_id(report, "dex_has_termux_activity")["detail"]["containing"], ["classes2.dex"])

    def test_negative_control_missing_classes_dex_is_rejected(self):
        apk = make_apk(self.tmp / "no-dex.apk", [("lib/arm64-v8a/libtermux.so", b"\x7fELF")])
        report, _ = self.run_gate(self.config(apk=apk))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "zip_has_dex")["passed"])

    def test_negative_control_malformed_dex_fails_closed(self):
        apk = fake_apk(self.tmp / "broken-dex.apk", malformed_dex=True)
        report, _ = self.run_gate(self.config(apk=apk))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "dex_entries_valid")["passed"])
        entry = self.gate_by_id(report, "dex_has_termux_activity")
        self.assertFalse(entry["passed"])
        self.assertIn("not evaluated", entry["reason"])

    def test_negative_control_non_zip_apk_fails_closed(self):
        apk = self.tmp / "not-a-zip.apk"
        apk.write_bytes(b"\x7fELF" + b"\x00" * 512)
        report, _ = self.run_gate(self.config(apk=apk))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "zip_has_dex")["passed"])
        self.assertFalse(self.gate_by_id(report, "dex_has_termux_activity")["passed"])

    def test_negative_control_aapt_nonzero_exit_fails_closed(self):
        report, _ = self.run_gate(
            self.config(), aapt=aapt_handler("", rc=1, stderr="ERROR: dump failed"))
        self.assertEqual(report["exit_code"], 1)
        for gate_id in ("aapt_package_name", "aapt_version_code", "aapt_version_name_beta",
                        "aapt_not_debuggable", "aapt_arm64_abi"):
            entry = self.gate_by_id(report, gate_id)
            self.assertFalse(entry["passed"], gate_id)
            self.assertIn("exited 1", entry["reason"])

    def test_negative_control_apksigner_nonzero_exit_fails_closed(self):
        report, _ = self.run_gate(
            self.config(), apksigner=apksigner_handler("DOES NOT VERIFY\n", rc=1))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "apksigner_verified")["passed"])
        entry = self.gate_by_id(report, "cert_sha256_match")
        self.assertFalse(entry["passed"])
        self.assertIn("not evaluated", entry["reason"])

    def test_negative_control_wrong_abi_version_and_package_are_rejected(self):
        report, _ = self.run_gate(self.config(), aapt=aapt_handler(BADGING_ABI_MISSING))
        self.assertEqual(report["exit_code"], 1)
        self.assertFalse(self.gate_by_id(report, "aapt_arm64_abi")["passed"])

        for replacement, gate_id in (
            ("versionCode='1021'", "aapt_version_code"),
            ("0.119.0-rc.1", "aapt_version_name_beta"),
            ("name='com.termux.beta'", "aapt_package_name"),
        ):
            badging = BADGING_OK.replace(
                {"aapt_version_code": "versionCode='1022'",
                 "aapt_version_name_beta": "0.119.0-beta.2",
                 "aapt_package_name": "name='com.termux'"}[gate_id],
                replacement,
            )
            with self.subTest(gate=gate_id):
                report, _ = self.run_gate(self.config(), aapt=aapt_handler(badging))
                self.assertEqual(report["exit_code"], 1)
                self.assertFalse(self.gate_by_id(report, gate_id)["passed"])

    def test_badging_without_package_line_fails_closed(self):
        report, _ = self.run_gate(self.config(), aapt=aapt_handler("adb: failed to run aapt\n"))
        self.assertEqual(report["exit_code"], 1)
        entry = self.gate_by_id(report, "aapt_package_name")
        self.assertFalse(entry["passed"])
        self.assertIn("missing package: line", entry["reason"])

    def test_missing_apk_is_an_input_error_with_fail_closed_report(self):
        config = self.config(apk=self.tmp / "does-not-exist.apk")
        report, runner = self.run_gate(config)
        self.assertEqual(report["exit_code"], 2)
        self.assertFalse(report["passed"])
        self.assertEqual(runner.calls, [], "no tool may run without an APK")
        self.assertTrue(report["errors"])
        self.assertFalse(self.gate_by_id(report, "apk_readable")["passed"])
        # the input-format gates describe the arguments, so they stay evaluable
        for gate_id in INPUT_GATES:
            self.assertTrue(self.gate_by_id(report, gate_id)["passed"], gate_id)
        # ... while every artifact-derived gate is a hard failure
        artifact_gates = [entry for entry in report["gates"] if entry["id"] not in INPUT_GATES]
        self.assertEqual(len(artifact_gates), len(report["gates"]) - len(INPUT_GATES))
        for entry in artifact_gates:
            self.assertFalse(entry["passed"], entry["id"])
            self.assertTrue(entry["reason"], entry["id"])

    def test_zero_length_apk_is_rejected(self):
        apk = self.tmp / "empty.apk"
        apk.write_bytes(b"")
        report, _ = self.run_gate(self.config(apk=apk))
        self.assertFalse(report["passed"])
        self.assertFalse(self.gate_by_id(report, "apk_readable")["passed"])
        self.assertTrue(self.gate_by_id(report, "apk_readable")["reason"])

    def test_unusable_tool_is_an_input_error_and_is_never_run(self):
        config = self.config(aapt="/nonexistent/aapt")
        report, runner = self.run_gate(
            config, unusable={"/nonexistent/aapt": "--aapt is not a file: /nonexistent/aapt"})
        self.assertEqual(report["exit_code"], 2)
        self.assertFalse(report["passed"])
        self.assertTrue(report["errors"])
        self.assertEqual([call for call in runner.calls if call[0] == "/nonexistent/aapt"], [])
        self.assertFalse(self.gate_by_id(report, "aapt_package_name")["passed"])

    def test_missing_build_head_file_is_an_input_error(self):
        config = self.config()
        config["build_head_path"] = str(self.tmp / "absent.txt")
        report, _ = self.run_gate(config)
        self.assertEqual(report["exit_code"], 2)
        self.assertFalse(self.gate_by_id(report, "build_head_literal")["passed"])

    def test_report_records_argv_return_codes_and_layering_claims(self):
        report, _ = self.run_gate(self.config())
        self.assertEqual(report["tools"]["aapt"]["rc"], 0)
        self.assertEqual(report["tools"]["apksigner"]["rc"], 0)
        self.assertEqual(report["tools"]["aapt"]["argv"][1:3], ["dump", "badging"])
        self.assertEqual(report["tools"]["apksigner"]["argv"][1:3], ["verify", "--print-certs"])
        joined = " ".join(report["claims_not_proven"]).lower()
        for fragment in ("fork", "sidecar", "test key", "aot", "jit"):
            self.assertIn(fragment, joined)
        self.assertIn("artifact", report["evidence_layer"])


# --------------------------------------------------------------------------
# CLI level tests (real subprocess, executable tool stubs)
# --------------------------------------------------------------------------


def write_tool_stub(directory: Path, name: str, *, stdout: str = "", stderr: str = "", rc: int = 0) -> str:
    path = Path(directory) / name
    body = "#!/bin/sh\n"
    if stdout:
        body += "cat <<'BETA_ARTIFACT_STDOUT_EOF'\n%sBETA_ARTIFACT_STDOUT_EOF\n" % stdout
    if stderr:
        body += "cat >&2 <<'BETA_ARTIFACT_STDERR_EOF'\n%sBETA_ARTIFACT_STDERR_EOF\n" % stderr
    body += "exit %d\n" % rc
    path.write_text(body, encoding="utf-8")
    path.chmod(0o755)
    return str(path)


class CliIntegrationTest(GateTestCase):
    """Real subprocess execution of scripts/beta_artifact_gate.py."""

    CLI = [sys.executable, str(SCRIPTS / "beta_artifact_gate.py")]

    def setUp(self):
        super().setUp()
        self.tools = self.tmp / "tools"
        self.tools.mkdir()

    def cli(self, config, output_name="report.json"):
        output = self.tmp / output_name
        argv = self.CLI + [
            "--apk", config["apk"],
            "--expected-head", config["expected_head"],
            "--build-head", config["build_head_path"],
            "--sha256-file", config["sha256_file_path"],
            "--expected-cert-sha256", config["expected_cert_sha256"],
            "--aapt", config["aapt"],
            "--apksigner", config["apksigner"],
            "--output", str(output),
        ]
        proc = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        report = json.loads(output.read_text(encoding="utf-8")) if output.exists() else None
        return proc, report

    def stub_config(self, badging=BADGING_OK, certs=None, **kwargs):
        certs = certs_output(TEST_CERT_SHA256) if certs is None else certs
        return self.config(
            aapt=write_tool_stub(self.tools, "aapt", stdout=badging),
            apksigner=write_tool_stub(self.tools, "apksigner", stdout=certs),
            **kwargs,
        )

    def test_success_path_exits_zero_with_json_report(self):
        proc, report = self.cli(self.stub_config())
        self.assertIsNotNone(report, proc.stderr.decode())
        self.assertEqual(proc.returncode, 0, proc.stdout.decode() + proc.stderr.decode())
        self.assertTrue(report["passed"], report["gates"])
        self.assertEqual(report["exit_code"], 0)
        self.assertEqual(report["tools"]["aapt"]["rc"], 0)
        self.assertIn("PASS", proc.stdout.decode())

    def test_debug_apk_exits_one(self):
        proc, report = self.cli(self.stub_config(badging=BADGING_DEBUG))
        self.assertEqual(proc.returncode, 1)
        self.assertFalse(report["passed"])
        self.assertIn("aapt_not_debuggable", [g["id"] for g in report["gates"] if not g["passed"]])
        self.assertIn("FAIL", proc.stdout.decode())

    def test_hash_mismatch_exits_one(self):
        proc, report = self.cli(self.stub_config(sha256_text="%s\n" % ("f" * 64)))
        self.assertEqual(proc.returncode, 1)
        self.assertFalse(report["passed"])

    def test_failing_tool_exit_code_is_recorded(self):
        config = self.config(
            aapt=write_tool_stub(self.tools, "aapt", stderr="ERROR: dump failed\n", rc=1),
            apksigner=write_tool_stub(self.tools, "apksigner", stdout=certs_output(TEST_CERT_SHA256)),
        )
        proc, report = self.cli(config)
        self.assertEqual(proc.returncode, 1)
        self.assertFalse(report["passed"])
        self.assertEqual(report["tools"]["aapt"]["rc"], 1)

    def test_missing_tool_exits_two(self):
        config = self.config(
            aapt=str(self.tools / "absent-aapt"),
            apksigner=write_tool_stub(self.tools, "apksigner", stdout=certs_output(TEST_CERT_SHA256)),
        )
        proc, report = self.cli(config)
        self.assertEqual(proc.returncode, 2)
        self.assertIsNotNone(report)
        self.assertFalse(report["passed"])
        self.assertTrue(report["errors"])

    def test_non_executable_tool_exits_two(self):
        tool = self.tools / "aapt-noexec"
        tool.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        tool.chmod(0o644)
        config = self.config(
            aapt=str(tool),
            apksigner=write_tool_stub(self.tools, "apksigner", stdout=certs_output(TEST_CERT_SHA256)),
        )
        proc, report = self.cli(config)
        self.assertEqual(proc.returncode, 2)
        self.assertFalse(report["passed"])

    def test_missing_apk_exits_two_with_report(self):
        proc, report = self.cli(self.config(apk=self.tmp / "nope.apk"))
        self.assertEqual(proc.returncode, 2)
        self.assertIsNotNone(report)
        self.assertFalse(report["passed"])

    def test_malformed_head_argument_exits_one(self):
        proc, report = self.cli(self.stub_config(expected_head="not-a-sha"))
        self.assertEqual(proc.returncode, 1)
        self.assertFalse(report["passed"])


if __name__ == "__main__":
    unittest.main()
