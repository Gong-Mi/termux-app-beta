#!/usr/bin/env python3
"""Tests for scripts/beta_art_gate.py (beta-certification line: art).

FIXTURE PROVENANCE -- read this before trusting any string in this file
======================================================================
* Constants named REAL_* are verbatim excerpts of read-only output captured
  on the host device (Redmi/rothko, Android 16 / API 36) with
  `cmd package art dump <pkg>` and `dumpsys package <pkg>`.  They are real
  device text and are used ONLY to test the parser.
* Constants named MOCK_* are hand-written synthetic text.  They exist to
  exercise branches the host cannot produce read-only (a split APK that
  owns its own code path, an unparsable OEM dump, ...).  A MOCK fixture is
  never a device result: the fake transport used here reports
  evidence_tier="mock-fixture" and no test in this file claims a real
  device pass.  The real speed-compiled positive control is owned by CI.
* Nothing in this file compiles, installs, force-stops, reboots or
  otherwise mutates a device.
"""

import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import beta_art_gate as gate  # noqa: E402

# --------------------------------------------------------------------------
# REAL device fixtures (verbatim, read-only capture; API 36 / rothko)
# --------------------------------------------------------------------------

REAL_BASE_APK = ("/data/app/~~sttH6Q8-cMCOmpEnhA89dA==/"
                 "com.termux-BVX7OY6YcjaXPkFT2hyS5w==/base.apk")
REAL_BASE_SHA256 = (
    "1d9184d72b2f5e1f48b5c0a9f4e175be10f55cac00ebdc25d19ed91cf2044e0a")
REAL_CODEPATH = ("/data/app/~~sttH6Q8-cMCOmpEnhA89dA==/"
                 "com.termux-BVX7OY6YcjaXPkFT2hyS5w==")

REAL_PM_PATH_TERMUX = "package:%s\n" % REAL_BASE_APK

# `cmd package art dump com.termux` (exit 0)
REAL_ART_DUMP_TERMUX = """[com.termux]
  path: %s
    arm64: [status=run-from-apk] [reason=unknown] [primary-abi]
      [location is error]
""" % REAL_BASE_APK

# `cmd package art dump` excerpt for com.miui.home (a package the device
# reports as speed-compiled; genuine text, kept as a parser fixture only).
REAL_MIUI_HOME_BASE = ("/data/app/~~Sf0L_wJngMbR88Oq82E5Bw==/"
                       "com.miui.home-cZLcvmstogj4b9F-PlUbQg==/base.apk")
REAL_ART_DUMP_MIUI_HOME = """[com.miui.home]
  path: %s
    arm64: [status=speed] [reason=install] [primary-abi]
      [location is %s]
""" % (REAL_MIUI_HOME_BASE,
       REAL_MIUI_HOME_BASE.replace("/base.apk", "/oat/arm64/base.odex"))

# `dumpsys package com.termux` excerpt (verbatim lines, incl. the OEM-augmented
# "Dexopt state:" block and the "Compiler stats:" tail).
REAL_DUMPSYS_PACKAGE_TERMUX = """Packages:
  Package [com.termux] (1af027c):
    appId=10717
    sharedUser=SharedUserSetting{6b299ac com.termux/10717}
    pkg=Package{be60675 com.termux}
    codePath=%s
    resourcePath=%s
    legacyNativeLibraryDir=%s/lib
    extractNativeLibs=true
    primaryCpuAbi=arm64-v8a
    secondaryCpuAbi=null
    cpuAbiOverride=null
    versionCode=1022 minSdk=24 targetSdk=28
    minExtensionVersions=[]
    versionName=0.119.0-beta.3
    hiddenApiEnforcementPolicy=2
    usesNonSdkApi=false
    isMiuiPreinstall=false
    splits=[base]
    apkSigningVersion=3
    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
    privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING ]
    forceQueryable=false
    pageSizeCompat=0
    scannedAsStoppedSystemApp=false
    supportsScreens=[small, medium, large, xlarge, resizeable, anyDensity]
    usesLibraries:
      android.test.base
    timeStamp=2026-10-16 20:13:21
    lastUpdateTime=2026-10-16 20:13:21
    installerPackageName=null
    installerPackageUid=-1
    packageSource=1
    signatures=PackageSignatures{87a26ff version:3, signatures:[db86cf3c], past signatures:[]}
    installPermissionsFixed=false
    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
    apexModuleName=null

Dexopt state:
  [com.termux]
    path: %s
      arm64: [status=run-from-apk] [reason=unknown] [primary-abi]
        [location is error]
    base APK odex file size: Oops,Some thing wrong when get dex file output paths!

Compiler stats:
  [com.termux]
    (No recorded stats)
""" % (REAL_CODEPATH, REAL_CODEPATH, REAL_CODEPATH, REAL_BASE_APK)

REAL_GETPROP_DUMP = """[ro.build.version.sdk]: [36]
[ro.build.version.release]: [16]
[ro.product.cpu.abi]: [arm64-v8a]
[ro.product.cpu.abilist]: [arm64-v8a]
[ro.build.fingerprint]: [Redmi/rothko/rothko:16/BP2A.250605.031.A3/OS3.0.307.0.WNNCNXM:user/release-keys]
[ro.kernel.qemu]: []
"""

# --------------------------------------------------------------------------
# MOCK fixtures (synthetic; never device evidence)
# --------------------------------------------------------------------------

MOCK_SPLIT_APK = "/data/app/~~MOCKSESSION==/com.termux-MOCKSPLIT==/split_support_ui.apk"
MOCK_BASE_APK = "/data/app/~~MOCKSESSION==/com.termux-MOCKBASE==/base.apk"
MOCK_BASE_SHA256 = "a" * 64
MOCK_SPLIT_SHA256 = "b" * 64

MOCK_PM_PATH_SPLIT = ("package:%s\npackage:%s\n"
                      % (MOCK_BASE_APK, MOCK_SPLIT_APK))

MOCK_DUMPSYS_PACKAGE_CLEAN = """Packages:
  Package [%s] (mock):
    codePath=/data/app/~~MOCKSESSION==/com.termux-MOCKBASE==
    primaryCpuAbi=arm64-v8a
    versionCode=1022 minSdk=24 targetSdk=28
    versionName=0.119.0-beta.3
    splits=[base, support_ui]
    apkSigningVersion=3
    flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
    pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
    timeStamp=2026-10-16 20:13:21
"""

def mock_art_dump(entries, package="com.termux"):
    """Build a synthetic `cmd package art dump` body.

    entries: list of (dex_path, isa, status, reason, location_or_None, primary)
    """
    out = ["[%s]" % package]
    seen = []
    by_path = {}
    for dex_path, isa, status, reason, location, primary in entries:
        by_path.setdefault(dex_path, []).append((isa, status, reason, location, primary))
    for dex_path, rows in by_path.items():
        out.append("  path: %s" % dex_path)
        for isa, status, reason, location, primary in rows:
            line = "    %s: [status=%s] [reason=%s]%s" % (
                isa, status, reason, " [primary-abi]" if primary else "")
            out.append(line)
            out.append("      [location is %s]" % (location or "error"))
    return "\n".join(out) + "\n"


# --------------------------------------------------------------------------
# fakes
# --------------------------------------------------------------------------

class Recorder:
    """Records exec argv; never touches a real device."""

    def __init__(self, result=(0, "", "")):
        self.calls = []
        self.result = result

    def __call__(self, argv, timeout):
        self.calls.append(list(argv))
        return self.result


class FakeTransport(gate.Transport):
    """Scripted transport.  Marked mock so a report can never look like a
    device result."""

    name = "fake-fixture"
    evidence_tier = "mock-fixture (synthetic: NOT device evidence)"

    def __init__(self, script):
        self.script = script
        self.calls = []

    def run(self, cmd_id, remote_argv):
        self.calls.append((cmd_id, list(remote_argv)))
        entry = self.script.get(cmd_id)
        if entry is None:
            return gate.CommandResult(cmd_id, list(remote_argv),
                                      ["fake-fixture", cmd_id], 127, "",
                                      "no fixture for %s" % cmd_id)
        if callable(entry):
            entry = entry(remote_argv)
        exit_code, out, err = entry
        return gate.CommandResult(cmd_id, list(remote_argv),
                                  ["fake-fixture", cmd_id], exit_code, out, err)


def standard_script(**overrides):
    """Script matching the REAL host state (debuggable + run-from-apk)."""
    script = {
        gate.CMD_GETPROP: (0, REAL_GETPROP_DUMP, ""),
        gate.CMD_PM_PATH: (0, REAL_PM_PATH_TERMUX, ""),
        gate.CMD_DUMPSYS_PACKAGE: (0, REAL_DUMPSYS_PACKAGE_TERMUX, ""),
        gate.CMD_ART_DUMP: (0, REAL_ART_DUMP_TERMUX, ""),
        gate.CMD_PIDOF: (0, "6807\n", ""),
        gate.CMD_SHA256SUM: (0, "%s  %s\n" % (REAL_BASE_SHA256, REAL_BASE_APK), ""),
        gate.CMD_MAPS_GREP: (0, "", ""),
    }
    script.update(overrides)
    return script


def run_gate(script, expected_sha=REAL_BASE_SHA256, extra_argv=(), transport=None):
    """Run the gate in-process with a scripted transport.

    Returns (exit_code, report_or_None, out_dir, fake_transport).
    """
    out_dir = tempfile.mkdtemp(prefix="beta-art-test-")
    argv = ["--transport", "local-root", "--package", "com.termux",
            "--expected-apk-sha256", expected_sha, "--out-dir", out_dir]
    argv.extend(extra_argv)
    fake = transport if transport is not None else FakeTransport(script)
    buf_out, buf_err = io.StringIO(), io.StringIO()
    with redirect_stdout(buf_out), redirect_stderr(buf_err):
        code = gate.main(argv, make_transport=lambda name, args: fake,
                         exec_fn=None)
    report_path = os.path.join(out_dir, "report.json")
    report = None
    if os.path.exists(report_path):
        with open(report_path, encoding="utf-8") as fh:
            report = json.load(fh)
    return code, report, out_dir, fake


# --------------------------------------------------------------------------
# 1. NEGATIVE CONTROLS for erroneous input (fail-closed, exit 2)
# --------------------------------------------------------------------------

class NegativeControlTests(unittest.TestCase):
    """Erroneous CLI input must be refused, never silently downgraded."""

    def _run_inproc(self, argv):
        buf_out, buf_err = io.StringIO(), io.StringIO()
        with redirect_stdout(buf_out), redirect_stderr(buf_err):
            code = gate.main(argv, make_transport=lambda name, args: FakeTransport({}),
                             exec_fn=None)
        return code, buf_out.getvalue(), buf_err.getvalue()

    def test_adb_without_serial_is_refused(self):
        out_dir = tempfile.mkdtemp(prefix="beta-art-test-")
        code, out, err = self._run_inproc(
            ["--transport", "adb", "--package", "com.termux",
             "--expected-apk-sha256", REAL_BASE_SHA256, "--out-dir", out_dir])
        self.assertEqual(code, gate.EXIT_USAGE)
        self.assertIn("serial", err.lower())
        with open(os.path.join(out_dir, "report.json"), encoding="utf-8") as fh:
            report = json.load(fh)
        self.assertIs(report["passed"], False)
        self.assertEqual(report["status"], "ERROR")

    def test_adb_empty_serial_is_refused(self):
        out_dir = tempfile.mkdtemp(prefix="beta-art-test-")
        code, out, err = self._run_inproc(
            ["--transport", "adb", "--serial", "", "--package", "com.termux",
             "--expected-apk-sha256", REAL_BASE_SHA256, "--out-dir", out_dir])
        self.assertEqual(code, gate.EXIT_USAGE)

    def test_malformed_expected_sha256_is_refused(self):
        for bad in ("", "zz", "abc", REAL_BASE_SHA256[:-1],
                    REAL_BASE_SHA256 + "0", "sha256:" + REAL_BASE_SHA256,
                    REAL_BASE_SHA256.upper().replace("1D", "1g")):
            out_dir = tempfile.mkdtemp(prefix="beta-art-test-")
            code, out, err = self._run_inproc(
                ["--transport", "local-root", "--package", "com.termux",
                 "--expected-apk-sha256", bad, "--out-dir", out_dir])
            self.assertEqual(code, gate.EXIT_USAGE, "accepted bad hash %r" % bad)

    def test_uppercase_hex_is_accepted_and_normalised(self):
        script = standard_script()
        code, report, out_dir, _ = run_gate(script,
                                            expected_sha=REAL_BASE_SHA256.upper())
        # The artifact still fails (debuggable + run-from-apk), but the hash
        # itself must be accepted and compared case-insensitively.
        self.assertEqual(report["cli"]["expected_apk_sha256"],
                         REAL_BASE_SHA256)
        self.assertEqual(report["cli"]["expected_apk_sha256_input"],
                         REAL_BASE_SHA256.upper())
        codes = [r["code"] for r in report["reasons"]]
        self.assertNotIn("usage-invalid-expected-sha256", codes)

    def test_unknown_transport_is_refused(self):
        out_dir = tempfile.mkdtemp(prefix="beta-art-test-")
        code, out, err = self._run_inproc(
            ["--transport", "bogus", "--package", "com.termux",
             "--expected-apk-sha256", REAL_BASE_SHA256, "--out-dir", out_dir])
        self.assertEqual(code, gate.EXIT_USAGE)

    def test_missing_required_args_are_refused(self):
        for argv in (["--transport", "local-root"],
                     ["--package", "com.termux"],
                     ["--package", "com.termux", "--expected-apk-sha256",
                      REAL_BASE_SHA256]):
            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                with self.assertRaises(SystemExit) as ctx:
                    gate.main(argv, make_transport=lambda n, a: FakeTransport({}),
                              exec_fn=None)
            self.assertNotEqual(ctx.exception.code, 0)

    def test_missing_transport_binary_message_is_actionable(self):
        # A transport whose exec layer raises must fail closed, not pass.
        def boom(argv, timeout):
            raise FileNotFoundError("adb")

        out_dir = tempfile.mkdtemp(prefix="beta-art-test-")
        transport = gate.AdbTransport("SERIAL", adb="/nonexistent/adb", exec_fn=boom)
        buf_out, buf_err = io.StringIO(), io.StringIO()
        with redirect_stdout(buf_out), redirect_stderr(buf_err):
            code = gate.main(["--transport", "adb", "--serial", "SERIAL",
                              "--package", "com.termux",
                              "--expected-apk-sha256", REAL_BASE_SHA256,
                              "--out-dir", out_dir],
                             make_transport=lambda n, a: transport, exec_fn=None)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        with open(os.path.join(out_dir, "report.json"), encoding="utf-8") as fh:
            report = json.load(fh)
        self.assertIs(report["passed"], False)
        self.assertEqual(report["status"], "ERROR")


# --------------------------------------------------------------------------
# 2. Gate logic negative controls (scripted transport; mock evidence tier)
# --------------------------------------------------------------------------

class GateFailureTests(unittest.TestCase):

    def test_debuggable_and_run_from_apk_fails(self):
        code, report, out_dir, _ = run_gate(standard_script())
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        self.assertIs(report["passed"], False)
        self.assertEqual(report["status"], "FAIL")
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("package-debuggable", codes)
        self.assertIn("dexopt-primary-filter-not-aot", codes)
        self.assertTrue(report["package_flags"]["debuggable"])
        self.assertEqual(report["artifact"]["apk_paths"][0]["matches_expected"], True)

    def test_artifact_hash_mismatch_fails(self):
        code, report, out_dir, _ = run_gate(standard_script(),
                                            expected_sha="c" * 64)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("artifact-sha256-mismatch", codes)
        self.assertFalse(report["artifact"]["hash_all_match"])

    def test_every_non_aot_filter_is_rejected(self):
        for filt in ("verify", "run-from-apk", "speed-profile", "quicken",
                     "space", "space-profile", "extract", "unknown", "missing",
                     "error", "assume-verified", ""):
            dump = mock_art_dump([(
                MOCK_BASE_APK, "arm64", filt or "?", "install",
                MOCK_BASE_APK.replace("/base.apk", "/oat/arm64/base.odex"),
                True)])
            script = standard_script(**{
                gate.CMD_PM_PATH: (0, "package:%s\n" % MOCK_BASE_APK, ""),
                gate.CMD_ART_DUMP: (0, dump, ""),
                gate.CMD_SHA256SUM: (0, "%s  %s\n" % (MOCK_BASE_SHA256, MOCK_BASE_APK), ""),
                gate.CMD_DUMPSYS_PACKAGE: (0, MOCK_DUMPSYS_PACKAGE_CLEAN % "com.termux", ""),
            })
            code, report, out_dir, _ = run_gate(script, expected_sha=MOCK_BASE_SHA256)
            self.assertEqual(code, gate.EXIT_NOT_PASSED, "filter %r passed" % filt)
            codes = [r["code"] for r in report["reasons"]]
            self.assertIn("dexopt-primary-filter-not-aot", codes,
                          "filter %r produced %r" % (filt, codes))
            self.assertIs(report["passed"], False)

    def test_neighbouring_package_speed_entry_does_not_pass(self):
        """The dump carries a neighbouring package with status=speed while the
        requested package is run-from-apk.  Must not leak into the verdict."""
        dump = REAL_ART_DUMP_MIUI_HOME + "\n" + REAL_ART_DUMP_TERMUX
        script = standard_script(**{gate.CMD_ART_DUMP: (0, dump, "")})
        code, report, out_dir, _ = run_gate(script)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        self.assertEqual(report["dexopt"]["primary_code_path"]["status"],
                         "run-from-apk")
        # and the miui entry must not be attributed to com.termux
        paths = {e["dex_path"] for e in report["dexopt"]["entries"]}
        self.assertNotIn(REAL_MIUI_HOME_BASE, paths)

    def test_split_without_dexopt_entry_is_incomplete(self):
        script = standard_script(**{
            gate.CMD_PM_PATH: (0, MOCK_PM_PATH_SPLIT, ""),
            gate.CMD_DUMPSYS_PACKAGE: (0, MOCK_DUMPSYS_PACKAGE_CLEAN % "com.termux", ""),
            gate.CMD_ART_DUMP: (0, mock_art_dump([(
                MOCK_BASE_APK, "arm64", "speed", "install",
                MOCK_BASE_APK.replace("/base.apk", "/oat/arm64/base.odex"), True)]), ""),
            gate.CMD_SHA256SUM: (0, "%s  %s\n%s  %s\n" % (
                MOCK_BASE_SHA256, MOCK_BASE_APK, MOCK_SPLIT_SHA256, MOCK_SPLIT_APK), ""),
        })
        code, report, out_dir, _ = run_gate(script, expected_sha=MOCK_BASE_SHA256)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        self.assertIs(report["passed"], False)
        codes = [r["code"] for r in report["reasons"]]
        # Either the split is proven code-bearing (FAIL) or unprovable here
        # (INCOMPLETE); both must be non-passing.
        self.assertTrue({"split-code-path-unverified", "split-code-path-not-aot"}
                        & set(codes), codes)

    def test_unparsable_dexopt_state_is_incomplete_not_pass(self):
        script = standard_script(**{
            gate.CMD_ART_DUMP: (1, "Error: Unknown 'art' sub-command 'dump'\n"
                                   "See 'pm help' for help\n", ""),
            gate.CMD_DUMPSYS_PACKAGE: (0, "Packages:\n  Package [com.termux] (1af027c):\n"
                                          "    flags=[ HAS_CODE ]\n", ""),
        })
        code, report, out_dir, _ = run_gate(script)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        self.assertEqual(report["status"], "INCOMPLETE")
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("dexopt-state-unparsable", codes)

    def test_hash_unavailable_is_incomplete(self):
        script = standard_script(**{gate.CMD_SHA256SUM: (1, "", "sha256sum: error\n")})
        code, report, out_dir, _ = run_gate(script)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        self.assertIs(report["passed"], False)
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("artifact-sha256-unavailable", codes)

    def test_empty_pm_path_is_incomplete(self):
        script = standard_script(**{gate.CMD_PM_PATH: (0, "", "")})
        code, report, out_dir, _ = run_gate(script)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("pm-path-empty", codes)

    def test_missing_package_flags_is_incomplete(self):
        script = standard_script(**{
            gate.CMD_DUMPSYS_PACKAGE: (0, "Packages:\n  Package [com.termux] (1af027c):\n"
                                          "    codePath=%s\n" % REAL_CODEPATH, ""),
        })
        code, report, out_dir, _ = run_gate(script)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("package-flags-unparsable", codes)

    def test_dumpsys_dexopt_state_fallback_used_when_art_service_absent(self):
        """API < 34 / OEM: `cmd package art dump` missing, `dumpsys package`
        Dexopt state section must be used (REAL fixture text)."""
        script = standard_script(**{
            gate.CMD_ART_DUMP: (1, "Error: Unknown 'art' sub-command 'dump'\n"
                                   "See 'pm help' for help\n", ""),
        })
        code, report, out_dir, _ = run_gate(script)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        self.assertEqual(report["dexopt"]["source"],
                         "dumpsys-package-dexopt-state")
        self.assertEqual(report["dexopt"]["primary_code_path"]["status"],
                         "run-from-apk")
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("dexopt-primary-filter-not-aot", codes)

    def test_disagreeing_sources_are_incomplete(self):
        dump = mock_art_dump([(REAL_BASE_APK, "arm64", "speed", "install",
                               REAL_BASE_APK.replace("/base.apk",
                                                     "/oat/arm64/base.odex"),
                               True)])
        dumpsys = (MOCK_DUMPSYS_PACKAGE_CLEAN % "com.termux").replace(
            "splits=[base, support_ui]", "splits=[base]") + (
            "\nDexopt state:\n  [com.termux]\n    path: %s\n"
            "      arm64: [status=run-from-apk] [reason=unknown] [primary-abi]\n"
            "        [location is error]\n\nCompiler stats:\n" % REAL_BASE_APK)
        script = standard_script(**{gate.CMD_ART_DUMP: (0, dump, ""),
                                    gate.CMD_DUMPSYS_PACKAGE: (0, dumpsys, "")})
        code, report, out_dir, _ = run_gate(script)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        self.assertEqual(report["status"], "INCOMPLETE")
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("dexopt-source-conflict", codes)

    def test_maps_evidence_unavailable_is_not_gating(self):
        script = standard_script(**{gate.CMD_PIDOF: (1, "", ""),
                                    gate.CMD_MAPS_GREP: (127, "", "missing")})
        code, report, out_dir, _ = run_gate(script)
        self.assertEqual(report["status"], "FAIL")  # the real reasons only
        self.assertFalse(report["maps"]["available"])
        self.assertNotIn("maps", "".join(r["code"] for r in report["reasons"]))

    def test_maps_evidence_is_extra_only(self):
        script = standard_script(**{
            gate.CMD_PIDOF: (0, "4242\n", ""),
            gate.CMD_MAPS_GREP: (
                0,
                "71a00000-71a40000 r--p 00000000 00:00 0 %s/oat/arm64/base.vdex\n"
                "71a40000-71a80000 r--p 00000000 00:00 0 "
                "[anon:dalvik-/system/framework/boot.art]\n"
                % REAL_CODEPATH,
                ""),
        })
        code, report, out_dir, _ = run_gate(script)
        self.assertTrue(report["maps"]["available"])
        self.assertEqual(report["maps"]["pid"], 4242)
        self.assertEqual(report["maps"]["code_path_related_lines"], 1)
        self.assertEqual(len(report["maps"]["sample"]), 2)
        # a resolvable maps view must not change the verdict of the base run
        self.assertEqual(report["status"], "FAIL")

    def test_location_error_forces_incomplete_when_filter_is_aot(self):
        """A speed entry whose location cannot be resolved is not a pass."""
        dump = mock_art_dump([(MOCK_BASE_APK, "arm64", "speed", "install",
                               None, True)])
        script = standard_script(**{
            gate.CMD_PM_PATH: (0, "package:%s\n" % MOCK_BASE_APK, ""),
            gate.CMD_ART_DUMP: (0, dump, ""),
            gate.CMD_DUMPSYS_PACKAGE: (0, MOCK_DUMPSYS_PACKAGE_CLEAN % "com.termux", ""),
            gate.CMD_SHA256SUM: (0, "%s  %s\n" % (MOCK_BASE_SHA256, MOCK_BASE_APK), ""),
        })
        code, report, out_dir, _ = run_gate(script, expected_sha=MOCK_BASE_SHA256)
        self.assertEqual(code, gate.EXIT_NOT_PASSED)
        self.assertEqual(report["status"], "INCOMPLETE")
        codes = [r["code"] for r in report["reasons"]]
        self.assertIn("dexopt-location-unresolved", codes)


# --------------------------------------------------------------------------
# 3. Positive control (MOCK fixture -- CI owns the real device control)
# --------------------------------------------------------------------------

class PositiveControlTests(unittest.TestCase):

    def test_mock_speed_non_debuggable_hash_match_passes(self):
        dump = mock_art_dump([(MOCK_BASE_APK, "arm64", "speed", "install",
                               MOCK_BASE_APK.replace("/base.apk",
                                                     "/oat/arm64/base.odex"),
                               True)])
        script = standard_script(**{
            gate.CMD_PM_PATH: (0, "package:%s\n" % MOCK_BASE_APK, ""),
            gate.CMD_ART_DUMP: (0, dump, ""),
            gate.CMD_DUMPSYS_PACKAGE: (0, MOCK_DUMPSYS_PACKAGE_CLEAN % "com.termux", ""),
            gate.CMD_SHA256SUM: (0, "%s  %s\n" % (MOCK_BASE_SHA256, MOCK_BASE_APK), ""),
        })
        code, report, out_dir, _ = run_gate(script, expected_sha=MOCK_BASE_SHA256)
        self.assertEqual(code, gate.EXIT_PASS, report["reasons"])
        self.assertIs(report["passed"], True)
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(report["dexopt"]["primary_code_path"]["status"], "speed")
        # the mock run must be visibly labelled
        self.assertIn("mock", report["evidence_tier"].lower())

    def test_mock_everything_filter_passes(self):
        dump = mock_art_dump([(MOCK_BASE_APK, "arm64", "everything", "bg-dexopt",
                               MOCK_BASE_APK.replace("/base.apk",
                                                     "/oat/arm64/base.odex"),
                               True)])
        script = standard_script(**{
            gate.CMD_PM_PATH: (0, "package:%s\n" % MOCK_BASE_APK, ""),
            gate.CMD_ART_DUMP: (0, dump, ""),
            gate.CMD_DUMPSYS_PACKAGE: (0, MOCK_DUMPSYS_PACKAGE_CLEAN % "com.termux", ""),
            gate.CMD_SHA256SUM: (0, "%s  %s\n" % (MOCK_BASE_SHA256, MOCK_BASE_APK), ""),
        })
        code, report, out_dir, _ = run_gate(script, expected_sha=MOCK_BASE_SHA256)
        self.assertEqual(code, gate.EXIT_PASS, report["reasons"])

    def test_real_speed_dump_parses_but_is_not_a_terumx_pass(self):
        """REAL parser fixture: the device's own speed entry must parse to
        status=speed for its own package -- parser regression only."""
        parsed = gate.parse_dexopt_state(REAL_ART_DUMP_MIUI_HOME)
        entry = parsed["blocks"]["com.miui.home"]["primary"][0]
        self.assertEqual(entry["status"], "speed")
        self.assertEqual(entry["isa"], "arm64")
        self.assertTrue(entry["primary_abi"])
        self.assertIn("oat/arm64/base.odex", entry["location_debug_string"])


# --------------------------------------------------------------------------
# 4. Read-only / transport invariants
# --------------------------------------------------------------------------

class InvariantTests(unittest.TestCase):

    def test_all_device_commands_are_read_only(self):
        _, report, _, fake = run_gate(standard_script())
        self.assertTrue(fake.calls, "no device command was issued")
        for cmd_id, argv in fake.calls:
            gate.assert_read_only(cmd_id, argv)  # raises on mutation

    def test_mutating_command_is_refused_by_guard(self):
        for cmd_id, argv in (
                (gate.CMD_PM_PATH, ["/system/bin/pm", "compile", "-m", "speed", "com.termux"]),
                (gate.CMD_ART_DUMP, ["/system/bin/cmd", "package", "art", "dexopt-packages"]),
                (gate.CMD_ART_DUMP, ["/system/bin/cmd", "package", "install", "x.apk"]),
                (gate.CMD_PM_PATH, ["/system/bin/pm", "uninstall", "com.termux"]),
                (gate.CMD_GETPROP, ["/system/bin/am", "force-stop", "com.termux"]),
                (gate.CMD_SHA256SUM, ["/system/bin/rm", "-rf", "/data"]),
        ):
            with self.assertRaises(gate.GateError):
                gate.assert_read_only(cmd_id, argv)

    def test_expected_command_set_is_exact(self):
        _, report, _, fake = run_gate(standard_script())
        ids = [c["id"] for c in report["commands"]]
        self.assertEqual(ids, [gate.CMD_GETPROP, gate.CMD_PM_PATH,
                               gate.CMD_DUMPSYS_PACKAGE, gate.CMD_ART_DUMP,
                               gate.CMD_SHA256SUM, gate.CMD_PIDOF,
                               gate.CMD_MAPS_GREP])

    def test_adb_transport_always_pins_serial(self):
        rec = Recorder()
        t = gate.AdbTransport("SERIAL123", adb="/opt/adb", exec_fn=rec)
        t.run(gate.CMD_PM_PATH, ["/system/bin/pm", "path", "com.termux"])
        self.assertEqual(rec.calls, [["/opt/adb", "-s", "SERIAL123", "shell",
                                      "/system/bin/pm", "path", "com.termux"]])

    def test_adb_transport_requires_serial(self):
        with self.assertRaises(gate.GateError):
            gate.AdbTransport("", adb="/opt/adb", exec_fn=Recorder())

    def test_local_root_transport_uses_su_c_with_absolute_paths(self):
        rec = Recorder()
        t = gate.LocalRootTransport(su="/usr/bin/su", exec_fn=rec)
        t.run(gate.CMD_PM_PATH, ["/system/bin/pm", "path", "com.termux"])
        self.assertEqual(len(rec.calls), 1)
        argv = rec.calls[0]
        self.assertEqual(argv[0], "/usr/bin/su")
        self.assertEqual(argv[1], "-c")
        self.assertIn("/system/bin/pm path com.termux", argv[2])
        self.assertNotIn("shell", argv)

    def test_transport_never_falls_back_to_local_shell(self):
        rec = Recorder()
        t = gate.AdbTransport("S", adb="/opt/adb", exec_fn=rec)
        t.run(gate.CMD_GETPROP, ["/system/bin/getprop"])
        for argv in rec.calls:
            self.assertNotIn("sh", argv[:1])
            self.assertIn("-s", argv)
            self.assertIn("shell", argv)


# --------------------------------------------------------------------------
# 5. Report contract
# --------------------------------------------------------------------------

class ReportContractTests(unittest.TestCase):

    def test_report_schema_and_raw_evidence(self):
        code, report, out_dir, fake = run_gate(standard_script())
        for key in ("schema", "generated_at", "line", "passed", "status",
                    "reasons", "scope_disclaimers", "cli", "device",
                    "artifact", "package_flags", "dexopt", "checks",
                    "commands", "evidence_tier", "transport"):
            self.assertIn(key, report)
        self.assertIsInstance(report["passed"], bool)
        self.assertEqual(report["line"], "art")
        for cmd in report["commands"]:
            self.assertIn("exit_code", cmd)
            self.assertTrue(os.path.exists(cmd["stdout_path"]), cmd["stdout_path"])
            self.assertTrue(os.path.exists(cmd["stderr_path"]), cmd["stderr_path"])
            self.assertEqual(len(cmd["stdout_sha256"]), 64)
        # raw stdout/stderr land next to report.json
        self.assertTrue(os.path.isdir(os.path.join(out_dir, "raw")))

    def test_report_is_written_for_every_outcome(self):
        cases = {
            "fail": (standard_script(), REAL_BASE_SHA256),
            "incomplete": (standard_script(**{gate.CMD_PM_PATH: (0, "", "")}),
                           REAL_BASE_SHA256),
            "pass-mock": (standard_script(**{
                gate.CMD_PM_PATH: (0, "package:%s\n" % MOCK_BASE_APK, ""),
                gate.CMD_ART_DUMP: (0, mock_art_dump([(
                    MOCK_BASE_APK, "arm64", "speed", "install",
                    MOCK_BASE_APK.replace("/base.apk", "/oat/arm64/base.odex"),
                    True)]), ""),
                gate.CMD_DUMPSYS_PACKAGE: (0, MOCK_DUMPSYS_PACKAGE_CLEAN % "com.termux", ""),
                gate.CMD_SHA256SUM: (0, "%s  %s\n" % (MOCK_BASE_SHA256, MOCK_BASE_APK), ""),
            }), MOCK_BASE_SHA256),
        }
        for label, (script, sha) in cases.items():
            code, report, out_dir, _ = run_gate(script, expected_sha=sha)
            self.assertIsNotNone(report, label)
            self.assertIsInstance(report["passed"], bool, label)
            self.assertEqual(report["passed"], code == gate.EXIT_PASS, label)

    def test_scope_disclaimers_present(self):
        _, report, _, _ = run_gate(standard_script())
        joined = " ".join(report["scope_disclaimers"]).lower()
        for needle in ("non-debuggable", "jit", "speed-profile", "release"):
            self.assertIn(needle, joined)

    def test_parser_real_dumpsys_dexopt_state(self):
        parsed = gate.parse_dexopt_state(REAL_DUMPSYS_PACKAGE_TERMUX)
        block = parsed["blocks"]["com.termux"]
        self.assertEqual(len(block["primary"]), 1)
        self.assertEqual(block["primary"][0]["status"], "run-from-apk")
        self.assertEqual(block["primary"][0]["reason"], "unknown")
        self.assertEqual(block["primary"][0]["dex_path"], REAL_BASE_APK)

    def test_parser_real_dumpsys_package_flags(self):
        info = gate.parse_dumpsys_package(REAL_DUMPSYS_PACKAGE_TERMUX, "com.termux")
        self.assertEqual(info["versionCode"], "1022")
        self.assertEqual(info["versionName"], "0.119.0-beta.3")
        self.assertEqual(info["primaryCpuAbi"], "arm64-v8a")
        self.assertEqual(info["splits"], ["base"])
        self.assertIn("DEBUGGABLE", info["flags"])
        self.assertEqual(info["codePath"], REAL_CODEPATH)

    def test_parser_rejects_neighbour_package_block(self):
        info = gate.parse_dumpsys_package(REAL_DUMPSYS_PACKAGE_TERMUX,
                                          "com.other.package")
        self.assertIsNone(info)

    def test_isa_mapping(self):
        self.assertEqual(gate.abi_to_isa("arm64-v8a"), "arm64")
        self.assertEqual(gate.abi_to_isa("armeabi-v7a"), "arm")
        self.assertEqual(gate.abi_to_isa("x86_64"), "x86_64")
        self.assertEqual(gate.abi_to_isa("x86"), "x86")


if __name__ == "__main__":
    unittest.main(verbosity=2)
