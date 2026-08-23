#!/usr/bin/env python3
"""Verify the TerminalSession PTY descriptor ownership contract.

This is intentionally a source contract, not a substitute for Android runtime
FD tracing. It protects the failure paths that pure-JVM tests cannot exercise:
independent dup ownership, rollback after partial dup failure, stream
registration after cleanup, and raw-FD close ordering.
"""
from pathlib import Path

SOURCE = Path("terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java")
text = SOURCE.read_text(encoding="utf-8")

def require(fragment: str, description: str) -> None:
    if fragment not in text:
        raise SystemExit(f"FAIL: missing PTY ownership contract: {description}\n  {fragment}")

require("inputFileDescriptor = Os.dup(terminalFileDescriptorWrapped);", "input duplicate")
require("outputFileDescriptor = Os.dup(terminalFileDescriptorWrapped);", "output duplicate")
require("final FileDescriptor inputDescriptor = inputFileDescriptor;", "stable input descriptor capture")
require("final FileDescriptor outputDescriptor = outputFileDescriptor;", "stable output descriptor capture")
require("Os.close(inputFileDescriptor);", "rollback closes first duplicate")
require("Os.close(outputFileDescriptor);", "rollback closes second duplicate when present")
require("if (mPtyStreamsCloseRequested) return;", "late stream registration is rejected")
require("closePtyStreams();\n        JNI.close(mTerminalFileDescriptor);", "duplicate streams close before raw master")
require("if (mShellPid <= 0 || mProcessExited) return;", "post-exit PID kill authority is revoked")

# The raw master is allowed to be closed in the dup-failure rollback and in
# normal cleanup, but stream constructors must use only their duplicate vars.
if "new FileInputStream(terminalFileDescriptorWrapped)" in text:
    raise SystemExit("FAIL: input stream still owns the raw PTY descriptor")
if "new FileOutputStream(terminalFileDescriptorWrapped)" in text:
    raise SystemExit("FAIL: output stream still owns the raw PTY descriptor")

print(f"PASS PTY ownership contract: {SOURCE}")
