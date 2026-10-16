"""Parent integration regressions. All device/ART streams here are SIMULATED."""
import json
import tempfile
import unittest
from pathlib import Path

import beta_emulator_certify as driver
import test_beta_emulator_certify as fixtures


def report_gate(argv, timeout):
    """Test-only report producer, not device evidence."""
    out = Path(argv[argv.index('--out-dir') + 1])
    out.mkdir(parents=True, exist_ok=True)
    expected = argv[argv.index('--expected-apk-sha256') + 1]
    (out / 'report.json').write_text(json.dumps({
        'passed': True, 'status': 'PASS',
        'cli': {'transport': 'adb', 'serial': argv[argv.index('--serial') + 1],
                'package': argv[argv.index('--package') + 1],
                'expected_apk_sha256': expected},
        'artifact': {'expected_apk_sha256': expected},
    }))
    return driver.CmdResult(argv, 0, 'SIMULATED report written')


class DriverIntegrityTest(unittest.TestCase):
    def check_run(self, modify_adb=None, gate=report_gate):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config = fixtures.make_config(Path(tmp.name))
        adb = fixtures.happy_adb(config)
        if modify_adb:
            modify_adb(adb)
        run = driver.Certification(config, adb, gate_runner=gate, log=lambda *a: None)
        return run.run(), run, adb

    def test_nonzero_read_commands_cannot_be_accepted_despite_valid_stdout(self):
        for kind in ('getprop', 'pm-path', 'sha256', 'dumpsys'):
            with self.subTest(kind=kind):
                def change(adb):
                    original = adb.run
                    def fail(*args, **kwargs):
                        result = original(*args, **kwargs)
                        if adb._classify(list(args)) == kind:
                            result.exit_code = 1
                        return result
                    adb.run = fail
                code, run, adb = self.check_run(change)
                self.assertNotEqual(0, code, kind)
                self.assertNotIn('instrument', adb.kinds())
                if kind == 'getprop':
                    self.assertEqual([], adb.mutations)

    def test_art_exit_zero_without_report_is_not_a_pass(self):
        code, run, adb = self.check_run(gate=lambda argv, timeout: driver.CmdResult(argv, 0))
        self.assertNotEqual(0, code)
        self.assertNotIn('instrument', adb.kinds())

    def test_art_report_verdict_and_target_are_read_back(self):
        for mutation in ('false_verdict', 'wrong_serial', 'wrong_hash'):
            with self.subTest(mutation=mutation):
                def gate(argv, timeout):
                    result = report_gate(argv, timeout)
                    path = Path(argv[argv.index('--out-dir') + 1]) / 'report.json'
                    data = json.loads(path.read_text())
                    if mutation == 'false_verdict':
                        data['passed'] = False
                    elif mutation == 'wrong_serial':
                        data['cli']['serial'] = 'another-target'
                    else:
                        data['artifact']['expected_apk_sha256'] = '0' * 64
                    path.write_text(json.dumps(data))
                    return result
                code, run, adb = self.check_run(gate=gate)
                self.assertNotEqual(0, code)
                self.assertNotIn('instrument', adb.kinds())

    def test_launch_failure_blocks_instrumentation(self):
        for failure in ('exit', 'error_text'):
            with self.subTest(failure=failure):
                def change(adb):
                    original = adb.run
                    def fail(*args, **kwargs):
                        result = original(*args, **kwargs)
                        if adb._classify(list(args)) == 'am-start':
                            if failure == 'exit':
                                result.exit_code = 1
                            else:
                                result.stdout = 'Error type 3\nError: Activity class does not exist.\n'
                        return result
                    adb.run = fail
                code, run, adb = self.check_run(change)
                self.assertIn('am-start', adb.kinds())
                self.assertNotEqual(0, code)
                self.assertNotIn('instrument', adb.kinds())

    def test_instrumentation_final_result_must_be_success(self):
        text = fixtures.SIMULATED_OK_4.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0')
        self.assertTrue(driver.parse_instrumentation(text, 4)['problems'])

    def test_serial_whitespace_is_rejected_not_normalized(self):
        for serial in (' emulator-5554', 'emulator-5554 '):
            with self.subTest(serial=serial), self.assertRaises(driver.UsageError):
                driver.validate_serial(serial)

    def test_zero_expected_tests_cannot_create_vacuous_success(self):
        with self.assertRaises(driver.UsageError):
            driver.parse_expectations(['example.Test=0'])

    def test_expired_deadline_has_zero_device_traffic(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config = fixtures.make_config(Path(tmp.name))
        config.deadline_seconds = 0
        adb = fixtures.happy_adb(config)
        run = driver.Certification(config, adb, gate_runner=report_gate, log=lambda *a: None)
        self.assertNotEqual(0, run.run())
        self.assertEqual([], adb.commands)


import beta_artifact_gate as artifact
import test_beta_artifact_gate as artifact_fixtures
import beta_ci_gate as ci_gate
import test_beta_ci_gate as ci_fixtures


class ArtifactIntegrityTest(unittest.TestCase):
    def test_termux_apksigner_v3_certificate_format_is_recognized(self):
        # Format observed from the installed APK through Termux apksigner.
        digest = 'b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1'
        result = artifact.parse_apksigner_cert_digests(
            'V3.0 Signer: certificate SHA-256 digest: ' + digest + '\n')
        self.assertEqual([digest], [r['normalized'] for r in result['digests']])

    def test_descriptor_reference_without_class_definition_is_not_fusion(self):
        blob = bytearray(artifact_fixtures.build_dex([artifact.ACTIVITY_DESCRIPTOR]))
        # Keep a perfectly readable string table, but erase the definitions.
        blob[96:104] = b'\0' * 8
        blob = artifact_fixtures.rebuild_dex_digests(blob)
        self.assertTrue(artifact.dex_has_string(blob, artifact.ACTIVITY_DESCRIPTOR))
        self.assertFalse(getattr(artifact, 'dex_defines_class', artifact.dex_has_string)(
            blob, artifact.ACTIVITY_DESCRIPTOR))


class CIIntegrityTest(ci_fixtures.GateCase):
    def test_expected_job_success_does_not_close_a_running_workflow(self):
        f = self.fixture()
        f.runs_route(ci_fixtures.SHA_A, [ci_fixtures.mk_run(
            ci_fixtures.SHA_A, ci_fixtures.CI, 1, status='in_progress', conclusion=None)])
        f.jobs_route(1, 1, [ci_fixtures.mk_job('known', 1)])
        code, report = self.run_gate(['ci.yml=known'], fixture=f)
        self.assertNotEqual(0, code)
        self.assertEqual('INCOMPLETE', report['verdict'])

    def test_failed_unlisted_workflow_is_not_masked(self):
        f = self.fixture()
        f.runs_route(ci_fixtures.SHA_A, [
            ci_fixtures.mk_run(ci_fixtures.SHA_A, ci_fixtures.CI, 1),
            ci_fixtures.mk_run(ci_fixtures.SHA_A, ci_fixtures.PERF, 2, conclusion='failure')])
        f.jobs_route(1, 1, [ci_fixtures.mk_job('known', 1)])
        f.jobs_route(2, 1, [ci_fixtures.mk_job('unlisted', 2, conclusion='failure')])
        code, report = self.run_gate(['ci.yml=known'], fixture=f)
        self.assertNotEqual(0, code)
        self.assertEqual('FAIL', report['verdict'])


if __name__ == '__main__':
    unittest.main()
