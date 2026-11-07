#!/usr/bin/env python3
"""Verify the TerminalSession PTY descriptor ownership contract.

This is intentionally a source contract, not a substitute for Android runtime
FD tracing. It protects the failure paths that pure-JVM tests cannot exercise:
independent dup ownership, rollback after partial dup failure, stream
registration after cleanup, and raw-FD close ordering.
"""
from pathlib import Path
import re

SOURCE = Path("terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java")
text = SOURCE.read_text(encoding="utf-8")

def require(fragment: str, description: str, scope: str = text) -> None:
    if fragment not in scope:
        raise SystemExit(f"FAIL: missing PTY ownership contract: {description}\n  {fragment}")

require("inputFileDescriptor = Os.dup(terminalFileDescriptorWrapped);", "input duplicate")
require("outputFileDescriptor = Os.dup(terminalFileDescriptorWrapped);", "output duplicate")
require("final FileDescriptor inputDescriptor = inputFileDescriptor;", "stable input descriptor capture")
require("final FileDescriptor outputDescriptor = outputFileDescriptor;", "stable output descriptor capture")
require("Os.close(inputFileDescriptor);", "rollback closes first duplicate")
require("Os.close(outputFileDescriptor);", "rollback closes second duplicate when present")
# Audit L1-1: the dup'd descriptors must be closed in closePtyStreams too —
# stream.close() never closes them (libcore isFdOwner=false).
require("mPtyInputFd = inputDescriptor;", "dup input fd retained for cleanup")
require("mPtyOutputFd = outputDescriptor;", "dup output fd retained for cleanup")
require("Os.close(inFd);", "cleanup closes input duplicate")
require("Os.close(outFd);", "cleanup closes output duplicate")
require("if (mPtyStreamsCloseRequested) return;", "late stream registration is rejected")
# cleanup detaches the integer owner under the session lock, then closes the
# captured handle in finally. Checking the old live-field spelling rejects this
# safer transfer and does not protect exactly-once close.
require("private int mTerminalFileDescriptor = -1;", "unstarted raw FD sentinel")
method = re.search(r"void cleanupResources\((?:int|Integer) exitStatus\)\s*\{(.*?)\n    \}", text, re.S)
if method is None:
    raise SystemExit("FAIL: missing PTY ownership contract: cleanupResources body")
cleanup = method.group(1)
require("if (!mResourcesClosed.compareAndSet(false, true)) return;",
        "exactly one cleanup owner", cleanup)
require("descriptor = mTerminalFileDescriptor;\n            mTerminalFileDescriptor = -1;",
        "raw master is captured then invalidated", cleanup)
close_order = re.search(
    r"closePtyStreams\(\);\s*\}\s*finally\s*\{(?:\s*//[^\n]*\n)*"
    r"\s*if \(descriptor >= 0\) JNI\.close\(descriptor\);", cleanup)
if close_order is None:
    raise SystemExit("FAIL: missing PTY ownership contract: captured raw master closes in finally")
require("if (mShellPid <= 0 || mProcessExited) return;", "post-exit PID kill authority is revoked")

# The raw master is allowed to be closed in the dup-failure rollback and in
# normal cleanup, but stream constructors must use only their duplicate vars.
if "new FileInputStream(terminalFileDescriptorWrapped)" in text:
    raise SystemExit("FAIL: input stream still owns the raw PTY descriptor")
if "new FileOutputStream(terminalFileDescriptorWrapped)" in text:
    raise SystemExit("FAIL: output stream still owns the raw PTY descriptor")

print(f"PASS PTY ownership contract: {SOURCE}")
