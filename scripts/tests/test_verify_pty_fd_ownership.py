#!/usr/bin/env python3
"""Exercise the actual source guard with a safe ownership transfer and bad variants.

These are guard detection controls, not native FD behavior acceptance.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'verify_pty_fd_ownership.py'
RELATIVE = Path('terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java')
SOURCE = (SCRIPT.parent.parent / RELATIVE).read_text(encoding='utf-8')
NATIVE_RELATIVE = Path('terminal-emulator/src/main/jni/termux.c')
NATIVE = (SCRIPT.parent.parent / NATIVE_RELATIVE).read_text(encoding='utf-8')


class PtyOwnershipGuardTest(unittest.TestCase):
    def run_guard(self, source, native=NATIVE):
        with tempfile.TemporaryDirectory(prefix='pty-source-guard-') as tmp:
            target = Path(tmp) / RELATIVE
            target.parent.mkdir(parents=True)
            target.write_text(source, encoding='utf-8')
            native_target = Path(tmp) / NATIVE_RELATIVE
            native_target.parent.mkdir(parents=True)
            native_target.write_text(native, encoding='utf-8')
            return subprocess.run([sys.executable, str(SCRIPT)], cwd=tmp,
                                  env=dict(os.environ, LLVM_PROFILE_FILE='/dev/null'),
                                  capture_output=True, text=True, timeout=15)

    def reject(self, old, new, reason):
        self.assertEqual(1, SOURCE.count(old), 'the mutation must alter one real source edge')
        result = self.run_guard(SOURCE.replace(old, new, 1))
        self.assertNotEqual(0, result.returncode)
        self.assertIn(reason, result.stdout + result.stderr)

    def test_safe_captured_owner_and_finally_close_is_accepted(self):
        result = self.run_guard(SOURCE)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('PASS PTY ownership contract', result.stdout)

    def test_missing_captured_raw_close_is_rejected(self):
        self.reject('if (descriptor >= 0) JNI.close(descriptor);',
                    '/* lost raw owner close */', 'captured raw master closes in finally')

    def test_closing_invalidated_live_field_instead_of_captured_owner_is_rejected(self):
        self.reject('if (descriptor >= 0) JNI.close(descriptor);',
                    'if (descriptor >= 0) JNI.close(mTerminalFileDescriptor);',
                    'captured raw master closes in finally')

    def test_missing_raw_handle_invalidation_is_rejected(self):
        self.reject('descriptor = mTerminalFileDescriptor;\n            mTerminalFileDescriptor = -1;',
                    'descriptor = mTerminalFileDescriptor; /* live handle left positive */',
                    'raw master is captured then invalidated')

    def test_missing_single_cleanup_owner_is_rejected(self):
        self.reject('if (!mResourcesClosed.compareAndSet(false, true)) return;',
                    '/* duplicate cleanup admitted */', 'exactly one cleanup owner')

    def test_unstarted_session_must_not_own_stdin(self):
        self.reject('private int mTerminalFileDescriptor = -1;',
                    'private int mTerminalFileDescriptor = 0;', 'unstarted raw FD sentinel')

    def test_native_observer_must_keep_the_pid_reserved(self):
        old = 'WEXITED | WNOWAIT'
        self.assertEqual(1, NATIVE.count(old))
        result = self.run_guard(SOURCE, NATIVE.replace(old, 'WEXITED', 1))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('native PID reservation uses WNOWAIT', result.stdout + result.stderr)

    def test_observer_cannot_use_the_reaping_wait(self):
        self.reject('int observedExit = JNI.waitForExit(watchedPid);',
                    'int observedExit = JNI.waitFor(watchedPid);', 'observe child exit without reaping')

    def test_failure_callback_cannot_run_before_reap(self):
        self.reject('if (getPid() > 0 && !mProcessReaped && !mProcessOwnershipLost) return;',
                    'if (getPid() > 0 && !mProcessExited && !mProcessOwnershipLost) return;',
                    'failure completion waits for reap or explicit lost ownership')

    def test_raw_descriptor_cannot_be_given_to_input_stream(self):
        self.reject('new FileInputStream(inputDescriptor)',
                    'new FileInputStream(terminalFileDescriptorWrapped)',
                    'input stream still owns the raw PTY descriptor')


if __name__ == '__main__':
    unittest.main()
