#!/usr/bin/env python3
"""Negative-first unit tests for the beta certification emulator driver.

Everything in this module is SIMULATED parser/contract data: no emulator, no
device, no cross-line gate script is executed here. The fixtures below are
hand-written ``am instrument`` / ``getprop`` streams used to prove that the
driver *rejects* bad input before it *accepts* good input.

Run:  python3 scripts/test_beta_emulator_certify.py
  or: PYTHONPATH=scripts python3 -m unittest test_beta_emulator_certify -v
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

from beta_emulator_certify import (  # noqa: E402
    DEFAULT_EXPECTATIONS,
    EXIT_FAILED,
    EXIT_OK,
    EXIT_REFUSED,
    EXIT_USAGE,
    Adb,
    CertConfig,
    Certification,
    CmdResult,
    UsageError,
    build_parser,
    config_from_args,
    evaluate_target_identity,
    first_package_path,
    first_sha256_token,
    main,
    parse_getprop_dump,
    parse_instrumentation,
    run_command,
    sha256_file,
    validate_serial,
)

BASELINE_HEAD = "c93d5b87dd26ace6170053e0645d9d89525e801e"

# --------------------------------------------------------------------------- #
# SIMULATED boot property fixtures (not measured on any device)
# --------------------------------------------------------------------------- #

EMULATOR_PROPS_32 = {
    "ro.kernel.qemu": "1",
    "ro.build.version.sdk": "32",
    "ro.build.version.release": "12",
    "ro.build.fingerprint": "google/sdk_gphone_x86_64/generic_x86_64:12/SIMULATED/eng.test:userdebug/test-keys",
    "ro.product.model": "sdk_gphone_x86_64",
    "ro.product.cpu.abi": "x86_64",
    "ro.boot.qemu.avd_name": "permission-test",
    "ro.hardware": "ranchu",
    "sys.boot_completed": "1",
    "dev.bootcomplete": "1",
}

EMULATOR_PROPS_36 = dict(
    EMULATOR_PROPS_32,
    **{
        "ro.build.version.sdk": "36",
        "ro.build.version.release": "16",
        "ro.boot.qemu.avd_name": "beta-cert",
    },
)

# A physical handset profile: no ro.kernel.qemu at all.
PHYSICAL_PROPS = {
    "ro.build.version.sdk": "34",
    "ro.build.version.release": "14",
    "ro.build.fingerprint": "Nothing/A065/A065:14/SIMULATED:user/release-keys",
    "ro.product.model": "A065",
    "ro.product.cpu.abi": "arm64-v8a",
    "ro.hardware": "mt6989",
    "sys.boot_completed": "1",
}

EMULATOR_PROPS_UNSUPPORTED_API = dict(EMULATOR_PROPS_32, **{"ro.build.version.sdk": "34"})
EMULATOR_PROPS_NOT_BOOTED = dict(EMULATOR_PROPS_32, **{"sys.boot_completed": "0"})

# --------------------------------------------------------------------------- #
# SIMULATED `am instrument -w -r` streams
# --------------------------------------------------------------------------- #

SIMULATED_OK_4 = """INSTRUMENTATION_STATUS: class=com.termux.app.ClipboardAndStorageInstrumentedTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=4
INSTRUMENTATION_STATUS: stream=
com.termux.app.ClipboardAndStorageInstrumentedTest:
INSTRUMENTATION_STATUS: test=apiSelectsExpectedStorageCapability
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.termux.app.ClipboardAndStorageInstrumentedTest
INSTRUMENTATION_STATUS: current=4
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=4
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: test=mediaStoreDownloadRoundTripWorksOnModernAndroid
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 12.345

OK (4 tests)


INSTRUMENTATION_CODE: -1
"""

SIMULATED_OK_1 = """INSTRUMENTATION_STATUS: class=com.termux.app.VerificationBuildTypeInstrumentedTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
com.termux.app.VerificationBuildTypeInstrumentedTest:
INSTRUMENTATION_STATUS: test=verificationVariantIsNonDebuggableAndKeepsTermuxPackageIdentity
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 0.512

OK (1 test)


INSTRUMENTATION_CODE: -1
"""

SIMULATED_WRONG_COUNT_OK_3 = SIMULATED_OK_4.replace("numtests=4", "numtests=3").replace(
    "OK (4 tests)", "OK (3 tests)"
)

SIMULATED_FAILURES = """INSTRUMENTATION_STATUS: class=com.termux.app.ClipboardAndStorageInstrumentedTest
INSTRUMENTATION_STATUS: test=apiSelectsExpectedStorageCapability
INSTRUMENTATION_STATUS_CODE: -2
INSTRUMENTATION_RESULT: stream=

Time: 9.001

There was 1 failure:
1) apiSelectsExpectedStorageCapability(com.termux.app.ClipboardAndStorageInstrumentedTest)
java.lang.AssertionError
	at com.termux.app.ClipboardAndStorageInstrumentedTest.apiSelectsExpectedStorageCapability(ClipboardAndStorageInstrumentedTest.java:78)

FAILURES!!!
Tests run: 4,  Failures: 1


INSTRUMENTATION_CODE: -1
"""

SIMULATED_NO_TESTS = """INSTRUMENTATION_RESULT: stream=

Time: 0.10

OK (0 tests)


INSTRUMENTATION_CODE: -1
"""

SIMULATED_CRASH = """INSTRUMENTATION_STATUS: test=clipboardCopyRoundTripsThroughShareUtils
INSTRUMENTATION_STATUS_CODE: -1
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
"""

SIMULATED_INSTRUMENTATION_FAILED = """INSTRUMENTATION_FAILED: com.termux.test/androidx.test.runner.AndroidJUnitRunner
INSTRUMENTATION_CODE: 0
"""

SIMULATED_TRUNCATED = """INSTRUMENTATION_STATUS: class=com.termux.app.ClipboardAndStorageInstrumentedTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: numtests=4
INSTRUMENTATION_STATUS_CODE: 1
"""

# --------------------------------------------------------------------------- #
# SIMULATED cross-line ART gate fixture (never shipped; proves argv wiring)
# --------------------------------------------------------------------------- #

FIXTURE_GATE_SOURCE = '''#!/usr/bin/env python3
"""SIMULATED stand-in used ONLY by unit tests to prove the driver argv wiring."""
import json
import os
import sys

record = os.environ.get("FAKE_GATE_RECORD")
if record:
    with open(record, "w", encoding="utf-8") as handle:
        json.dump(sys.argv[1:], handle)
from pathlib import Path
out = Path(sys.argv[sys.argv.index("--out-dir") + 1])
out.mkdir(parents=True, exist_ok=True)
expected = sys.argv[sys.argv.index("--expected-apk-sha256") + 1]
(out / "report.json").write_text(json.dumps({
    "passed": True, "status": "PASS",
    "cli": {"transport": "adb", "serial": sys.argv[sys.argv.index("--serial") + 1],
            "package": sys.argv[sys.argv.index("--package") + 1],
            "expected_apk_sha256": expected},
    "artifact": {"expected_apk_sha256": expected},
}))
print("SIMULATED ART GATE OUTPUT")
sys.exit(int(os.environ.get("FAKE_GATE_EXIT", "0")))
'''

MUTATION_KINDS = frozenset(
    {"install", "grant", "force-stop", "compile", "am-start", "instrument"}
)


def _silent(*_args, **_kwargs):
    return None


class FakeAdb:
    """Test-only adb stand-in. Records every invocation; never touches a device."""

    def __init__(
        self,
        props,
        *,
        get_state="device",
        install_exit=0,
        installed_hashes=None,
        compile_exit=0,
        compile_stdout="Success\n",
        instrument_streams=None,
        package_paths=None,
        grant_exit=0,
        force_stop_exit=0,
        am_start_exit=0,
    ):
        self.props = dict(props)
        self.get_state = get_state
        self.install_exit = install_exit
        self.installed_hashes = dict(installed_hashes or {})
        self.compile_exit = compile_exit
        self.compile_stdout = compile_stdout
        self.instrument_streams = dict(instrument_streams or {})
        self.package_paths = dict(package_paths or {})
        self.grant_exit = grant_exit
        self.force_stop_exit = force_stop_exit
        self.am_start_exit = am_start_exit
        self.commands = []

    # -- helpers ---------------------------------------------------------- #

    @staticmethod
    def _classify(args):
        if not args:
            return "empty"
        head = args[0]
        if head == "get-state":
            return "state"
        if head == "install":
            return "install"
        if head == "shell":
            rest = " ".join(args[1:])
            if rest == "getprop":
                return "getprop"
            if rest.startswith("pm path "):
                return "pm-path"
            if rest.startswith("sha256sum "):
                return "sha256"
            if rest.startswith("appops set ") or rest.startswith("pm grant "):
                return "grant"
            if rest.startswith("am force-stop "):
                return "force-stop"
            if rest.startswith("cmd package compile "):
                return "compile"
            if rest.startswith("dumpsys package "):
                return "dumpsys"
            if rest.startswith("am start "):
                return "am-start"
            if rest.startswith("am instrument "):
                return "instrument"
        return "unknown"

    @property
    def mutations(self):
        return [cmd for cmd in self.commands if self._classify(cmd) in MUTATION_KINDS]

    def kinds(self):
        return [self._classify(cmd) for cmd in self.commands]

    def _path_for(self, package):
        return self.package_paths.get(package, f"/data/app/~~sim==/{package}-sim==/base.apk")

    def _props_dump(self):
        return "".join(f"[{key}]: [{value}]\n" for key, value in sorted(self.props.items()))

    # -- the adb surface the driver uses ---------------------------------- #

    def run(self, *args, timeout=None):
        args = [str(a) for a in args]
        self.commands.append(args)
        kind = self._classify(args)

        if kind == "state":
            return CmdResult(args, 0, self.get_state + "\n")
        if kind == "getprop":
            return CmdResult(args, 0, self._props_dump())
        if kind == "install":
            if self.install_exit == 0:
                return CmdResult(args, 0, "Success\n")
            return CmdResult(args, self.install_exit, "Failure [INSTALL_FAILED_TEST_ONLY]\n")
        if kind == "pm-path":
            package = " ".join(args[1:]).split()[-1]
            return CmdResult(args, 0, f"package:{self._path_for(package)}\n")
        if kind == "sha256":
            raw = " ".join(args[1:]).split("sha256sum", 1)[1].strip().strip("'").strip('"')
            for package, path in [(p, self._path_for(p)) for p in self.installed_hashes]:
                if path == raw:
                    return CmdResult(args, 0, f"{self.installed_hashes[package]}  {raw}\n")
            return CmdResult(args, 0, f"sha256sum: {raw}: No such file or directory\n")
        if kind == "grant":
            return CmdResult(args, self.grant_exit, "")
        if kind == "force-stop":
            return CmdResult(args, self.force_stop_exit, "")
        if kind == "compile":
            return CmdResult(args, self.compile_exit, self.compile_stdout)
        if kind == "dumpsys":
            return CmdResult(args, 0, "SIMULATED dumpsys package output\n")
        if kind == "am-start":
            return CmdResult(args, self.am_start_exit, "Starting: Intent { cmp=... }\n")
        if kind == "instrument":
            joined = " ".join(args[1:])
            test_class = ""
            tokens = joined.split()
            if "-e" in tokens and tokens.index("-e") + 2 <= len(tokens):
                index = tokens.index("-e")
                if tokens[index + 1] == "class":
                    test_class = tokens[index + 2]
            return CmdResult(args, 0, self.instrument_streams.get(test_class, ""))
        return CmdResult(args, 0, "")


def make_config(tmp: Path, *, expectations=DEFAULT_EXPECTATIONS, art_gate_script=None, serial="emulator-5554"):
    apk = tmp / "universal.apk"
    apk.write_bytes(b"SIMULATED-VERIFICATION-APK-PAYLOAD")
    test_apk = tmp / "app-verification-androidTest.apk"
    test_apk.write_bytes(b"SIMULATED-VERIFICATION-TEST-APK-PAYLOAD")
    gate = Path(art_gate_script) if art_gate_script else (tmp / "beta_art_gate.py")
    if art_gate_script is None:
        # Only the implicit default gate path gets a fixture placeholder. An
        # explicitly-passed path is left exactly as the caller gave it, so
        # "script is missing" can actually be exercised.
        gate.write_text(
            "# cross-line ART gate fixture placeholder; never executed in unit tests\n",
            encoding="utf-8",
        )
    return CertConfig(
        serial=serial,
        apk=apk,
        test_apk=test_apk,
        out_dir=tmp / "out",
        expectations=expectations,
        art_gate_script=gate,
        python_bin=sys.executable or "python3",
        source_head=BASELINE_HEAD,
        settle_seconds=0.0,
    )


def happy_adb(config: CertConfig, **overrides):
    """FakeAdb configured so that only deliberately-broken inputs fail."""
    kwargs = dict(
        props=EMULATOR_PROPS_32,
        installed_hashes={
            "com.termux": sha256_file(config.apk),
            "com.termux.test": sha256_file(config.test_apk),
        },
        instrument_streams={
            "com.termux.app.ClipboardAndStorageInstrumentedTest": SIMULATED_OK_4,
            "com.termux.app.VerificationBuildTypeInstrumentedTest": SIMULATED_OK_1,
        },
    )
    kwargs.update(overrides)
    return FakeAdb(**kwargs)


def gate_ok(argv, timeout):
    out = Path(argv[argv.index("--out-dir") + 1])
    out.mkdir(parents=True, exist_ok=True)
    expected = argv[argv.index("--expected-apk-sha256") + 1]
    (out / "report.json").write_text(json.dumps({
        "passed": True, "status": "PASS",
        "cli": {"transport": "adb", "serial": argv[argv.index("--serial") + 1],
                "package": argv[argv.index("--package") + 1],
                "expected_apk_sha256": expected},
        "artifact": {"expected_apk_sha256": expected},
    }))
    return CmdResult(list(argv), 0, "SIMULATED ART GATE: speed state verified\n")


def gate_exit(code):
    def runner(argv, timeout):
        return CmdResult(list(argv), code, "", "SIMULATED ART GATE FAILURE\n")

    return runner


def run_driver(tmp, adb, *, gate_runner=gate_ok, expectations=DEFAULT_EXPECTATIONS, art_gate_script=None):
    config = make_config(tmp, expectations=expectations, art_gate_script=art_gate_script)
    certification = Certification(config, adb, gate_runner=gate_runner, log=_silent)
    return certification.run(), certification, config


# --------------------------------------------------------------------------- #
# NEGATIVE CONTROLS -- these run first on purpose
# --------------------------------------------------------------------------- #


class SerialContractNegativeControlTest(unittest.TestCase):
    def test_empty_serial_is_rejected(self):
        with self.assertRaises(UsageError):
            validate_serial("")

    def test_whitespace_serial_is_rejected(self):
        with self.assertRaises(UsageError):
            validate_serial("   ")

    def test_flag_selector_instead_of_serial_is_rejected(self):
        for bogus in ("-d", "-e", "-s", "--serial"):
            with self.subTest(bogus=bogus):
                with self.assertRaises(UsageError):
                    validate_serial(bogus)

    def test_adb_constructor_rejects_implicit_target(self):
        with self.assertRaises(UsageError):
            Adb("")

    def test_missing_serial_flag_is_a_cli_error(self):
        with self.assertRaises(SystemExit):
            build_parser().parse_args(["--apk", "a", "--test-apk", "b", "--out-dir", "c"])

    def test_main_returns_usage_error_for_flag_selector_serial(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            code = main(
                [
                    "--serial=-d",
                    "--apk", str(Path(tmpdir) / "a.apk"),
                    "--test-apk", str(Path(tmpdir) / "b.apk"),
                    "--out-dir", str(Path(tmpdir) / "out"),
                ]
            )
            self.assertEqual(code, EXIT_USAGE)


class TargetIdentityNegativeControlTest(unittest.TestCase):
    def test_physical_device_is_refused_with_zero_mutation(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = FakeAdb(PHYSICAL_PROPS)  # hashes deliberately irrelevant
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()

            self.assertEqual(code, EXIT_REFUSED)
            self.assertFalse(certification.data["mutation_allowed"])
            self.assertEqual(adb.mutations, [], "a non-emulator target must never be mutated")
            self.assertEqual(adb.commands, [["get-state"], ["shell", "getprop"]])
            self.assertTrue(
                any("ro.kernel.qemu" in reason for reason in certification.data["refusals"])
            )

    def test_unsupported_api_level_is_refused_with_zero_mutation(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            adb = FakeAdb(EMULATOR_PROPS_UNSUPPORTED_API)
            code, certification, _ = run_driver(Path(tmpdir), adb)
            self.assertEqual(code, EXIT_REFUSED)
            self.assertEqual(adb.mutations, [])
            self.assertTrue(
                any("not one of the certified API levels" in r for r in certification.data["refusals"])
            )

    def test_unbooted_emulator_is_refused_with_zero_mutation(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            adb = FakeAdb(EMULATOR_PROPS_NOT_BOOTED)
            code, _, _ = run_driver(Path(tmpdir), adb)
            self.assertEqual(code, EXIT_REFUSED)
            self.assertEqual(adb.mutations, [])

    def test_offline_target_is_a_failure(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            adb = FakeAdb(EMULATOR_PROPS_32, get_state="offline")
            code, _, _ = run_driver(Path(tmpdir), adb)
            self.assertEqual(code, EXIT_FAILED)
            self.assertEqual(adb.mutations, [])

    def test_unparseable_getprop_is_a_failure(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            adb = FakeAdb(props={})
            code, _, _ = run_driver(Path(tmpdir), adb)
            self.assertEqual(code, EXIT_FAILED)
            self.assertEqual(adb.mutations, [])


class InputArtifactNegativeControlTest(unittest.TestCase):
    def test_missing_apk_is_rejected_without_any_device_traffic(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            config.apk.unlink()
            adb = FakeAdb(EMULATOR_PROPS_32)
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            self.assertEqual(adb.commands, [], "bad local input must not produce device traffic")

    def test_missing_art_gate_script_is_rejected_without_device_traffic(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp, art_gate_script=tmp / "does-not-exist.py")
            adb = FakeAdb(EMULATOR_PROPS_32)
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            self.assertEqual(adb.commands, [])
            self.assertTrue(any("cross-line interface" in f for f in certification.data["failures"]))


class InstrumentationParserNegativeControlTest(unittest.TestCase):
    def test_wrong_test_count_is_rejected(self):
        verdict = parse_instrumentation(SIMULATED_WRONG_COUNT_OK_3, 4)
        self.assertTrue(verdict["problems"])
        self.assertTrue(any("expected 4 tests" in p for p in verdict["problems"]))

    def test_junit_failure_marker_is_rejected(self):
        verdict = parse_instrumentation(SIMULATED_FAILURES, 4)
        self.assertEqual(verdict["observed"], 4)
        self.assertTrue(any("JUnit reported failures" in p for p in verdict["problems"]))
        self.assertTrue(any("failure(s)" in p or "failures" in p for p in verdict["problems"]))

    def test_failed_status_code_is_rejected(self):
        verdict = parse_instrumentation(SIMULATED_FAILURES, 4)
        self.assertTrue(
            any("INSTRUMENTATION_STATUS_CODE -2" in p for p in verdict["problems"])
        )

    def test_zero_tests_is_rejected(self):
        verdict = parse_instrumentation(SIMULATED_NO_TESTS, 4)
        self.assertTrue(verdict["problems"])

    def test_runner_reported_no_tests_is_rejected(self):
        text = SIMULATED_NO_TESTS.replace("OK (0 tests)", "No tests found. Nothing to run.")
        verdict = parse_instrumentation(text, 4)
        self.assertTrue(any("no tests" in p.lower() for p in verdict["problems"]))

    def test_crash_is_rejected(self):
        verdict = parse_instrumentation(SIMULATED_CRASH, 4)
        self.assertTrue(
            any(("crashed" in p) or ("shortMsg" in p) or ("ActivityManager" in p) for p in verdict["problems"]),
            msg=f"expected a crash marker in {verdict['problems']}",
        )

    def test_instrumentation_failed_is_rejected(self):
        verdict = parse_instrumentation(SIMULATED_INSTRUMENTATION_FAILED, 4)
        self.assertTrue(any("failed" in p for p in verdict["problems"]))

    def test_truncated_stream_is_rejected_even_with_no_other_marker(self):
        verdict = parse_instrumentation(SIMULATED_TRUNCATED, 4)
        self.assertTrue(any("truncated" in p for p in verdict["problems"]))
        self.assertTrue(any("no JUnit success marker" in p for p in verdict["problems"]))

    def test_empty_stream_is_rejected(self):
        verdict = parse_instrumentation("", 4)
        self.assertTrue(verdict["problems"])

    def test_good_streams_only_pass_when_the_count_matches_exactly(self):
        self.assertEqual(parse_instrumentation(SIMULATED_OK_4, 4)["problems"], [])
        self.assertEqual(parse_instrumentation(SIMULATED_OK_1, 1)["problems"], [])
        self.assertTrue(parse_instrumentation(SIMULATED_OK_4, 1)["problems"])
        self.assertTrue(parse_instrumentation(SIMULATED_OK_1, 4)["problems"])


class PipelineNegativeControlTest(unittest.TestCase):
    def test_install_failure_stops_before_compile_and_instrumentation(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(config, install_exit=1)
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            kinds = adb.kinds()
            self.assertIn("install", kinds)
            self.assertNotIn("compile", kinds)
            self.assertNotIn("instrument", kinds)

    def test_install_readback_hash_mismatch_is_fatal(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(config, installed_hashes={"com.termux": "0" * 64})
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            self.assertFalse(certification.data["install"]["app"]["match"])
            self.assertNotIn("instrument", adb.kinds())

    def test_compile_failure_stops_before_instrumentation(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(config, compile_exit=1, compile_stdout="Error: no such package\n")
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            self.assertNotIn("instrument", adb.kinds())

    def test_art_gate_failure_blocks_instrumentation(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(config)
            certification = Certification(config, adb, gate_runner=gate_exit(1), log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            self.assertFalse(certification.data["art"]["read_only_gate"]["passed"])
            self.assertNotIn("instrument", adb.kinds())
            self.assertEqual(certification.data["instrumentation"], [])

    def test_art_gate_timeout_blocks_instrumentation(self):
        def timing_out_gate(argv, timeout):
            return CmdResult(list(argv), -1, "", "timed out\n", timed_out=True)

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(config)
            certification = Certification(config, adb, gate_runner=timing_out_gate, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            self.assertNotIn("instrument", adb.kinds())

    def test_instrumentation_failure_fails_the_run_even_when_other_class_passed(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(
                config,
                instrument_streams={
                    "com.termux.app.ClipboardAndStorageInstrumentedTest": SIMULATED_FAILURES,
                    "com.termux.app.VerificationBuildTypeInstrumentedTest": SIMULATED_OK_1,
                },
            )
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            verdicts = {v["class"]: v for v in certification.data["instrumentation"]}
            self.assertFalse(verdicts["com.termux.app.ClipboardAndStorageInstrumentedTest"]["passed"])
            self.assertTrue(verdicts["com.termux.app.VerificationBuildTypeInstrumentedTest"]["passed"])

    def test_instrumentation_missing_class_yields_empty_stream_and_fails(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(config, instrument_streams={})
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)

    def test_nonzero_adb_exit_fails_even_with_a_good_looking_stream(self):
        class ExitOneAdb(FakeAdb):
            def run(self, *args, timeout=None):
                result = super().run(*args, timeout=timeout)
                if self._classify([str(a) for a in args]) == "instrument":
                    result.exit_code = 1
                return result

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = ExitOneAdb(
                EMULATOR_PROPS_32,
                installed_hashes={
                    "com.termux": sha256_file(config.apk),
                    "com.termux.test": sha256_file(config.test_apk),
                },
                instrument_streams={
                    "com.termux.app.ClipboardAndStorageInstrumentedTest": SIMULATED_OK_4,
                    "com.termux.app.VerificationBuildTypeInstrumentedTest": SIMULATED_OK_1,
                },
            )
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            for verdict in certification.data["instrumentation"]:
                self.assertFalse(verdict["passed"])


# --------------------------------------------------------------------------- #
# POSITIVE CONTROLS
# --------------------------------------------------------------------------- #


class PositiveControlTest(unittest.TestCase):
    def _run_api32(self, tmp: Path):
        config = make_config(tmp)
        adb = happy_adb(config)
        certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
        return certification.run(), certification, adb, config

    def test_full_sequence_passes_and_records_ordered_steps(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            code, certification, adb, config = self._run_api32(tmp)
            self.assertEqual(code, EXIT_OK)
            self.assertTrue(certification.data["passed"])
            self.assertTrue(certification.data["mutation_allowed"])
            self.assertEqual(certification.data["target_identity"]["ro.build.version.sdk"], "32")

            self.assertEqual(certification.data["install"]["app"]["match"], True)
            self.assertEqual(certification.data["install"]["test"]["match"], True)
            self.assertEqual(certification.data["art"]["read_only_gate"]["passed"], True)
            self.assertTrue(certification.data["art"]["compile"]["before_instrumentation"])
            self.assertEqual(certification.data["art"]["compile"]["reported_success"], True)

            verdicts = {v["class"]: v for v in certification.data["instrumentation"]}
            self.assertEqual(len(verdicts), 2)
            self.assertEqual(verdicts["com.termux.app.ClipboardAndStorageInstrumentedTest"]["observed"], 4)
            self.assertEqual(verdicts["com.termux.app.VerificationBuildTypeInstrumentedTest"]["observed"], 1)
            self.assertTrue(all(v["passed"] for v in verdicts.values()))

            # the compile must be recorded before every instrumentation step
            compile_index = certification.data["art"]["compile"]["step_index"]
            for verdict in certification.data["instrumentation"]:
                self.assertLess(compile_index, verdict["step_index"])

            # evidence files landed on disk
            self.assertTrue((config.out_dir / "certification-result.json").is_file())
            self.assertTrue((config.out_dir / "boot-identity.json").is_file())
            self.assertTrue((config.out_dir / "art" / "art_compile-speed.stdout.txt").is_file())
            self.assertTrue(
                (config.out_dir / "instrumentation" / "instrument_ClipboardAndStorageInstrumentedTest.stdout.txt").is_file()
            )
            self.assertTrue(
                (config.out_dir / "instrumentation" / "instrument_ClipboardAndStorageInstrumentedTest.exit.txt").is_file()
            )

            # grants stayed inside the pre-existing workflow semantics
            grant_kinds = [c for c in adb.commands if FakeAdb._classify(c) == "grant"]
            self.assertEqual(len(grant_kinds), 3)

    def test_api36_target_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(config, props=EMULATOR_PROPS_36)
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_OK)
            self.assertEqual(certification.data["target_identity"]["ro.build.version.sdk"], "36")

    def test_every_serial_is_passed_explicitly_to_adb(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp, serial="emulator-5556")
            adb = happy_adb(config)
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            certification.run()
            # FakeAdb records post-``-s SERIAL`` argv, so assert on the real Adb wrapper.
            adb_wrapper = Adb("emulator-5556", adb_bin="adb")
            self.assertEqual(
                adb_wrapper.argv("shell", "getprop"),
                ["adb", "-s", "emulator-5556", "shell", "getprop"],
            )
            self.assertEqual(config.serial, "emulator-5556")

    def test_art_gate_argv_contract(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp)
            adb = happy_adb(config)
            recorded = {}

            def recording_gate(argv, timeout):
                recorded["argv"] = list(argv)
                return gate_ok(argv, timeout)

            certification = Certification(config, adb, gate_runner=recording_gate, log=_silent)
            certification.run()
            self.assertEqual(
                recorded["argv"],
                [
                    config.python_bin,
                    str(config.art_gate_script),
                    "--transport", "adb",
                    "--serial", "emulator-5554",
                    "--package", "com.termux",
                    "--expected-apk-sha256", sha256_file(config.apk),
                    "--out-dir", str(config.out_dir / "art-gate"),
                ],
            )

    def test_art_gate_argv_wiring_through_a_real_subprocess(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            gate = tmp / "fake_art_gate.py"
            gate.write_text(FIXTURE_GATE_SOURCE, encoding="utf-8")
            record = tmp / "gate-argv.json"
            os.environ["FAKE_GATE_RECORD"] = str(record)
            os.environ["FAKE_GATE_EXIT"] = "0"
            try:
                config = make_config(tmp, art_gate_script=gate)
                adb = happy_adb(config)
                certification = Certification(config, adb, gate_runner=run_command, log=_silent)
                code = certification.run()
            finally:
                os.environ.pop("FAKE_GATE_RECORD", None)
                os.environ.pop("FAKE_GATE_EXIT", None)

            self.assertEqual(code, EXIT_OK)
            self.assertTrue(record.is_file(), "the gate subprocess never ran")
            self.assertEqual(
                json.loads(record.read_text(encoding="utf-8")),
                [
                    "--transport", "adb",
                    "--serial", "emulator-5554",
                    "--package", "com.termux",
                    "--expected-apk-sha256", sha256_file(config.apk),
                    "--out-dir", str(config.out_dir / "art-gate"),
                ],
            )

    def test_frozen_cli_shape_is_accepted(self):
        parser = build_parser()
        args = parser.parse_args(
            [
                "--serial", "emulator-5554",
                "--apk", "universal.apk",
                "--test-apk", "app-verification-androidTest.apk",
                "--out-dir", "certify-out/api-32",
            ]
        )
        config = config_from_args(args)
        self.assertEqual(config.serial, "emulator-5554")
        self.assertEqual(
            config.art_gate_script,
            Path(__file__).resolve().parent / "beta_art_gate.py",
        )
        self.assertEqual(dict(config.expectations), dict(DEFAULT_EXPECTATIONS))


# --------------------------------------------------------------------------- #
# REGRESSION -- pure helpers
# --------------------------------------------------------------------------- #


class HelperRegressionTest(unittest.TestCase):
    def test_getprop_dump_parsing_ignores_malformed_lines(self):
        props = parse_getprop_dump(
            "[ro.kernel.qemu]: [1]\n"
            "not a property line\n"
            "\n"
            "[ro.build.version.sdk]: [32]\n"
            "[empty.value]: []\n"
        )
        self.assertEqual(props["ro.kernel.qemu"], "1")
        self.assertEqual(props["ro.build.version.sdk"], "32")
        self.assertEqual(props["empty.value"], "")
        self.assertEqual(len(props), 3)

    def test_target_identity_reasons_are_specific(self):
        eligible, reasons, identity = evaluate_target_identity(PHYSICAL_PROPS)
        self.assertFalse(eligible)
        self.assertEqual(identity["ro.kernel.qemu"], None)
        joined = " | ".join(reasons)
        self.assertIn("ro.kernel.qemu", joined)
        self.assertIn("certified API levels", joined)

    def test_first_package_path_handles_split_and_missing(self):
        self.assertEqual(first_package_path("package:/data/app/x/base.apk\n"), "/data/app/x/base.apk")
        self.assertEqual(
            first_package_path("package:/data/app/x/base.apk\npackage:/data/app/x/split_a.apk\n"),
            "/data/app/x/base.apk",
        )
        self.assertIsNone(first_package_path(""))

    def test_first_sha256_token(self):
        token = "a" * 64
        self.assertEqual(first_sha256_token(f"{token}  /data/app/x/base.apk\n"), token)
        self.assertIsNone(first_sha256_token("sha256sum: not found\n"))

    def test_sha256_file_matches_hashlib(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "blob"
            path.write_bytes(b"termux-beta-certification")
            import hashlib

            self.assertEqual(sha256_file(path), hashlib.sha256(b"termux-beta-certification").hexdigest())

    def test_run_command_reports_missing_binary_without_raising(self):
        result = run_command(["definitely-not-a-real-binary-xyz"], 5)
        self.assertEqual(result.exit_code, 127)
        self.assertFalse(result.timed_out)

    def test_expect_override_parsing(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            config = make_config(tmp, expectations=(("com.termux.app.FooTest", 2),))
            adb = happy_adb(
                config,
                instrument_streams={"com.termux.app.FooTest": SIMULATED_OK_1},
            )
            certification = Certification(config, adb, gate_runner=gate_ok, log=_silent)
            code = certification.run()
            self.assertEqual(code, EXIT_FAILED)
            self.assertTrue(any("expected 2 tests" in f for f in certification.data["failures"]))


class MainSmokeTest(unittest.TestCase):
    def test_main_refuses_a_non_emulator_without_mutation(self):
        """End-to-end through main(), with adb faked at the subprocess layer."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp = Path(tmpdir)
            apk = tmp / "universal.apk"
            apk.write_bytes(b"SIMULATED-APK")
            test_apk = tmp / "app-verification-androidTest.apk"
            test_apk.write_bytes(b"SIMULATED-TEST-APK")
            gate = tmp / "beta_art_gate.py"
            gate.write_text("# fixture placeholder\n", encoding="utf-8")

            seen = []

            def fake_run(argv, timeout):
                seen.append(list(argv))
                joined = " ".join(argv)
                if joined.endswith("get-state"):
                    return CmdResult(list(argv), 0, "device\n")
                if joined.endswith("shell getprop"):
                    return CmdResult(list(argv), 0, "[ro.kernel.qemu]: [0]\n[ro.build.version.sdk]: [34]\n")
                return CmdResult(list(argv), 0, "")

            with mock.patch("beta_emulator_certify.run_command", side_effect=fake_run):
                code = main(
                    [
                        "--serial", "emulator-5554",
                        "--apk", str(apk),
                        "--test-apk", str(test_apk),
                        "--out-dir", str(tmp / "out"),
                        "--art-gate-script", str(gate),
                    ]
                )

            self.assertEqual(code, EXIT_REFUSED)
            mutations = [
                argv
                for argv in seen
                if any(token in {"install", "instrument"} for token in argv)
            ]
            self.assertEqual(mutations, [], "refusal must not produce any mutation")
            result = json.loads((tmp / "out" / "certification-result.json").read_text(encoding="utf-8"))
            self.assertFalse(result["passed"])
            self.assertFalse(result["mutation_allowed"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
