#!/usr/bin/env python3
"""Beta certification emulator driver (work line: ci).

Drives one *explicit*, already-booted emulator through the Termux beta
certification sequence for the verification artifact (release-derived,
non-debuggable, test-key signed, R8 off):

    identity gate -> install -> capability grants -> force-stop
    -> AOT compile request (speed) -> ART read-only gate -> instrumentation

Frozen CLI (called by ``.github/workflows/beta-certification.yml``)::

    python3 scripts/beta_emulator_certify.py \
        --serial emulator-5554 --apk PATH --test-apk PATH --out-dir DIR

Fail-closed rules
-----------------
* ``--serial`` is mandatory and is always passed to adb as ``adb -s SERIAL``;
  an implicit adb target (``-d`` / ``-e`` / empty) is rejected.
* No mutation (install / grant / force-stop / compile / instrument) is issued
  before the target is *confirmed* to be ``ro.kernel.qemu=1`` with
  ``sys.boot_completed=1`` at API 32 or 36. A non-emulator target is refused
  with zero mutations.
* ``cmd package compile -m speed -f`` is only a *request*; the ART read-only
  gate (``scripts/beta_art_gate.py``) decides the real state and a gate
  failure is fatal -- instrumentation is not run on an unverified ART state.
* Instrumentation success is never taken from the adb exit code alone: the
  expected test count must match exactly and no failure / no-tests / crash /
  truncated-stream marker may be present.
* The AOT compile is recorded as happening before instrumentation, and the
  ordering is asserted.

What this driver explicitly does NOT claim (shared acceptance semantics)
-----------------------------------------------------------------------
* non-debuggable != AOT proof
* ``cmd package compile`` reporting Success != actual speed-mode state
* ART may legitimately mix execution modes; no zero-JIT / all-AOT claim
* the instrumentation runner mode is not ordinary app performance
* verification (R8 off) != minified release certification
* ``testkey_untrusted.jks`` is a test identity, not a production identity
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable, Iterable, Optional, Sequence

SCHEMA = "termux.beta_emulator_certification/1"

SUPPORTED_API_LEVELS = (32, 36)
DEFAULT_PACKAGE = "com.termux"
DEFAULT_TEST_PACKAGE = "com.termux.test"
DEFAULT_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
DEFAULT_LAUNCH_COMPONENT = "com.termux/.app.TermuxActivity"

#: class -> expected number of executed tests, mirrored from the pre-existing
#: clipboard/storage emulator regression (4) and the verification build-type
#: contract test (1).
DEFAULT_EXPECTATIONS = (
    ("com.termux.app.ClipboardAndStorageInstrumentedTest", 4),
    ("com.termux.app.VerificationBuildTypeInstrumentedTest", 1),
)

EXIT_OK = 0
EXIT_REFUSED = 2
EXIT_FAILED = 3
EXIT_USAGE = 4

CERTIFICATION_NOTES = (
    "verification variant is non-debuggable; that is NOT by itself an AOT proof",
    "`cmd package compile -m speed -f` returning Success is not proof of the actual"
    " ART state; the read-only gate reads the real state and gates this run",
    "ART may legitimately mix execution modes; this run does not claim zero JIT or"
    " that every method is AOT-compiled",
    "instrumentation runs the app inside a test runner mode and is not a general"
    " app performance measurement",
    "verification build keeps R8 off; the minified release variant is out of scope"
    " for this certification",
    "app/testkey_untrusted.jks is a test signing identity, never a production one",
)


class UsageError(ValueError):
    """Raised for CLI / serial misuse that must stop before touching a device."""


# --------------------------------------------------------------------------- #
# primitives
# --------------------------------------------------------------------------- #


@dataclasses.dataclass
class CmdResult:
    argv: Sequence[str]
    exit_code: int
    stdout: str = ""
    stderr: str = ""
    timed_out: bool = False
    started: float = 0.0
    finished: float = 0.0

    def as_dict(self) -> dict:
        return {
            "argv": list(self.argv),
            "exit_code": self.exit_code,
            "timed_out": self.timed_out,
            "stdout": self.stdout,
            "stderr": self.stderr,
        }


def _coerce(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return str(value)


def validate_serial(serial) -> str:
    """Return a non-empty, non-flag serial or raise :class:`UsageError`."""
    if serial is None or not str(serial).strip():
        raise UsageError(
            "an explicit --serial is required; implicit adb targets are not allowed"
        )
    value = str(serial)
    if value.startswith("-"):
        raise UsageError(
            f"serial {value!r} looks like an adb target selector, not a device serial"
        )
    if any(ch.isspace() for ch in value):
        raise UsageError("serial must not contain whitespace")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, payload) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run_command(argv: Sequence[str], timeout: float) -> CmdResult:
    """Run a command, never raising on failure; timeouts are explicit results."""
    argv = [str(a) for a in argv]
    started = time.time()
    try:
        proc = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        return CmdResult(
            argv=argv,
            exit_code=-1,
            stdout=_coerce(exc.stdout),
            stderr=_coerce(exc.stderr) + f"\n[driver] timed out after {timeout}s\n",
            timed_out=True,
            started=started,
            finished=time.time(),
        )
    except OSError as exc:  # missing binary, permission, ...
        return CmdResult(
            argv=argv,
            exit_code=127,
            stdout="",
            stderr=f"{type(exc).__name__}: {exc}\n",
            timed_out=False,
            started=started,
            finished=time.time(),
        )
    return CmdResult(
        argv=argv,
        exit_code=proc.returncode,
        stdout=proc.stdout or "",
        stderr=proc.stderr or "",
        timed_out=False,
        started=started,
        finished=time.time(),
    )


class Adb:
    """adb wrapper that always pins the target with an explicit ``-s SERIAL``."""

    def __init__(self, serial: str, adb_bin: str = "adb", default_timeout: float = 120.0):
        self.serial = validate_serial(serial)
        self.adb_bin = adb_bin
        self.default_timeout = float(default_timeout)

    def argv(self, *args) -> list:
        return [self.adb_bin, "-s", self.serial, *[str(a) for a in args]]

    def run(self, *args, timeout: Optional[float] = None) -> CmdResult:
        budget = self.default_timeout if timeout is None else float(timeout)
        return run_command(self.argv(*args), budget)


# --------------------------------------------------------------------------- #
# pure parsing / gating helpers
# --------------------------------------------------------------------------- #

_GETPROP_LINE_RE = re.compile(r"^\[(?P<key>[^\]]*)\]:\s*\[(?P<value>.*)\]$")


def parse_getprop_dump(text: str) -> dict:
    """Parse ``getprop`` output (``[key]: [value]``) into a dict."""
    props = {}
    for raw_line in (text or "").splitlines():
        match = _GETPROP_LINE_RE.match(raw_line.strip())
        if match:
            props[match.group("key")] = match.group("value")
    return props


def evaluate_target_identity(props: dict):
    """Return ``(eligible, reasons, identity)`` for the target boot properties."""
    reasons = []

    qemu = props.get("ro.kernel.qemu")
    if qemu != "1":
        reasons.append(
            f"ro.kernel.qemu={qemu!r} != '1' (target is not a QEMU/emulator instance)"
        )

    sdk_raw = props.get("ro.build.version.sdk", "")
    sdk = int(sdk_raw) if str(sdk_raw).isdigit() else None
    if sdk not in SUPPORTED_API_LEVELS:
        reasons.append(
            f"ro.build.version.sdk={sdk_raw!r} is not one of the certified API levels"
            f" {list(SUPPORTED_API_LEVELS)}"
        )

    boot = props.get("sys.boot_completed")
    if boot != "1":
        reasons.append(f"sys.boot_completed={boot!r} != '1' (target is not booted)")

    identity = {
        "ro.kernel.qemu": qemu,
        "ro.build.version.sdk": sdk_raw,
        "ro.build.version.release": props.get("ro.build.version.release"),
        "ro.build.fingerprint": props.get("ro.build.fingerprint"),
        "ro.product.model": props.get("ro.product.model"),
        "ro.product.cpu.abi": props.get("ro.product.cpu.abi"),
        "ro.boot.qemu.avd_name": props.get("ro.boot.qemu.avd_name"),
        "ro.hardware": props.get("ro.hardware"),
        "sys.boot_completed": boot,
        "dev.bootcomplete": props.get("dev.bootcomplete"),
    }
    return (not reasons), reasons, identity


_INSTR_OK_RE = re.compile(r"OK \((\d+) tests?\)")
_INSTR_TESTS_RUN_RE = re.compile(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)")

_INSTR_FAILURE_PATTERNS = (
    (re.compile(r"INSTRUMENTATION_FAILED"), "instrumentation run failed"),
    (re.compile(r"INSTRUMENTATION_ABORTED"), "instrumentation run aborted"),
    (re.compile(r"FAILURES!!!"), "JUnit reported failures"),
    (re.compile(r"Process crashed"), "instrumented process crashed"),
    (re.compile(r"shortMsg="), "ActivityManager reported an instrumentation error"),
    (re.compile(r"Unable to find instrumentation"), "instrumentation component not found"),
    (re.compile(r"No tests found", re.IGNORECASE), "runner reported no tests"),
    (
        re.compile(r"INSTRUMENTATION_STATUS_CODE:\s*-2\b"),
        "a test reported a failure (INSTRUMENTATION_STATUS_CODE -2)",
    ),
    (
        re.compile(r"INSTRUMENTATION_STATUS_CODE:\s*-1\b"),
        "a test reported an error (INSTRUMENTATION_STATUS_CODE -1)",
    ),
)


def parse_instrumentation(text: str, expected_count: int) -> dict:
    """Judge an ``am instrument -w -r`` raw stream against ``expected_count``.

    The verdict is derived from the raw stream only. The adb exit code is
    recorded by the caller but never used as the success signal.
    """
    text = text or ""
    problems = []
    for pattern, label in _INSTR_FAILURE_PATTERNS:
        if pattern.search(text):
            problems.append(label)

    observed = None
    ok_match = _INSTR_OK_RE.search(text)
    run_match = _INSTR_TESTS_RUN_RE.search(text)
    if ok_match:
        observed = int(ok_match.group(1))
        if observed != expected_count:
            problems.append(
                f"expected {expected_count} tests but the runner reported OK ({observed} tests)"
            )
    elif run_match:
        observed = int(run_match.group(1))
        failures = int(run_match.group(2))
        if failures:
            problems.append(f"JUnit reported {failures} failure(s)")
        if observed != expected_count:
            problems.append(
                f"expected {expected_count} tests but JUnit reported Tests run: {observed}"
            )
    else:
        problems.append(
            "no JUnit success marker found (neither 'OK (N tests)' nor "
            "'Tests run: N, Failures: 0')"
        )

    final_codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", text, re.MULTILINE)
    if not final_codes:
        problems.append(
            "instrumentation stream is truncated: no final 'INSTRUMENTATION_CODE:' line"
        )
    elif final_codes != ["-1"]:
        problems.append(f"instrumentation final result is not one RESULT_OK (-1): {final_codes}")

    return {"expected": expected_count, "observed": observed, "problems": problems}


_PACKAGE_PATH_PREFIX = "package:"
_SHA256_TOKEN_RE = re.compile(r"\b([0-9a-fA-F]{64})\b")


def first_package_path(text: str) -> Optional[str]:
    for raw_line in (text or "").splitlines():
        line = raw_line.strip()
        if line.startswith(_PACKAGE_PATH_PREFIX):
            return line[len(_PACKAGE_PATH_PREFIX):].strip()
    return None


def first_sha256_token(text: str) -> Optional[str]:
    match = _SHA256_TOKEN_RE.search(text or "")
    return match.group(1).lower() if match else None


# --------------------------------------------------------------------------- #
# configuration
# --------------------------------------------------------------------------- #


@dataclasses.dataclass
class CertConfig:
    serial: str
    apk: Path
    test_apk: Path
    out_dir: Path
    package: str = DEFAULT_PACKAGE
    test_package: str = DEFAULT_TEST_PACKAGE
    runner: str = DEFAULT_RUNNER
    launch_component: str = DEFAULT_LAUNCH_COMPONENT
    expectations: tuple = DEFAULT_EXPECTATIONS
    art_gate_script: Optional[Path] = None
    python_bin: str = sys.executable or "python3"
    source_head: Optional[str] = None
    adb_timeout: float = 120.0
    install_timeout: float = 600.0
    compile_timeout: float = 300.0
    instrumentation_timeout: float = 600.0
    art_gate_timeout: float = 300.0
    settle_seconds: float = 3.0
    deadline_seconds: float = 1800.0


# --------------------------------------------------------------------------- #
# certification run
# --------------------------------------------------------------------------- #


class Certification:
    def __init__(
        self,
        config: CertConfig,
        adb,
        gate_runner: Callable[[Sequence[str], float], CmdResult] = run_command,
        log: Callable[..., None] = print,
    ):
        self.config = config
        self.adb = adb
        self.gate_runner = gate_runner
        self.log = log
        self.out_dir = Path(config.out_dir)
        self.steps = []
        self.failures = []
        self.refusals = []
        self._step_index = 0
        self._deadline = time.monotonic() + float(config.deadline_seconds)
        self.data = {
            "schema": SCHEMA,
            "line": "ci",
            "serial": config.serial,
            "source_head": config.source_head,
            "package": config.package,
            "test_package": config.test_package,
            "runner": config.runner,
            "expectations": [
                {"class": cls, "expected_tests": count} for cls, count in config.expectations
            ],
            "mutation_allowed": False,
            "target_identity": None,
            "apk": {},
            "test_apk": {},
            "install": {},
            "art": {},
            "instrumentation": [],
            "steps": self.steps,
            "notes": list(CERTIFICATION_NOTES),
            "failures": self.failures,
            "refusals": self.refusals,
            "passed": False,
        }

    # -- bookkeeping -------------------------------------------------------- #

    @staticmethod
    def _ok(result: CmdResult) -> bool:
        return (not result.timed_out) and result.exit_code == 0

    def fail(self, message: str) -> None:
        self.failures.append(message)
        self.log(f"[driver][FAIL] {message}")

    def refuse(self, message: str) -> None:
        self.refusals.append(message)
        self.log(f"[driver][REFUSE] {message}")

    def expired(self) -> bool:
        return time.monotonic() >= self._deadline

    def remaining(self) -> float:
        return max(0.0, self._deadline - time.monotonic())

    def run_adb(self, *args, timeout=None) -> CmdResult:
        budget = min(float(timeout or self.config.adb_timeout), self.remaining())
        if budget <= 0:
            return CmdResult(["adb", "-s", self.config.serial, *map(str, args)],
                             -1, stderr="driver deadline exhausted", timed_out=True)
        return self.adb.run(*args, timeout=budget)

    def step(self, name: str, result: CmdResult, *, save_dir=None, best_effort=False) -> dict:
        self._step_index += 1
        duration = None
        if result.finished and result.started:
            duration = round(result.finished - result.started, 3)
        entry = {
            "index": self._step_index,
            "name": name,
            "argv": list(result.argv),
            "exit_code": result.exit_code,
            "timed_out": bool(result.timed_out),
            "duration_seconds": duration,
            "best_effort": bool(best_effort),
        }
        self.steps.append(entry)
        if save_dir is not None:
            target = Path(save_dir)
            target.mkdir(parents=True, exist_ok=True)
            base = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
            (target / f"{base}.stdout.txt").write_text(result.stdout, encoding="utf-8")
            (target / f"{base}.stderr.txt").write_text(result.stderr, encoding="utf-8")
            (target / f"{base}.exit.txt").write_text(
                f"{result.exit_code}{' TIMEOUT' if result.timed_out else ''}\n",
                encoding="utf-8",
            )
        if result.timed_out:
            self.fail(f"{name}: command exceeded its timeout budget")
        return entry

    def prepare_dirs(self) -> None:
        for sub in ("identity", "install", "art", "instrumentation"):
            (self.out_dir / sub).mkdir(parents=True, exist_ok=True)

    def finish(self, exit_code: int) -> int:
        if exit_code == EXIT_OK and (self.failures or self.refusals):
            exit_code = EXIT_FAILED
        self.data["passed"] = exit_code == EXIT_OK
        self.data["failures"] = list(self.failures)
        self.data["refusals"] = list(self.refusals)
        self.data["exit_code"] = exit_code
        write_json(self.out_dir / "certification-result.json", self.data)
        self.log(
            f"[driver] passed={self.data['passed']} exit={exit_code} "
            f"failures={len(self.failures)} refusals={len(self.refusals)} "
            f"result={self.out_dir / 'certification-result.json'}"
        )
        return exit_code

    # -- main sequence ------------------------------------------------------ #

    def run(self) -> int:
        cfg = self.config
        self.prepare_dirs()

        # 1. local artifacts have to exist before any device traffic.
        for label, path in (("apk", cfg.apk), ("test_apk", cfg.test_apk)):
            candidate = Path(path)
            if not candidate.is_file():
                self.fail(f"{label} not found: {candidate}")
            else:
                self.data[label] = {
                    "path": str(candidate),
                    "sha256": sha256_file(candidate),
                    "size_bytes": candidate.stat().st_size,
                }
        gate_script = Path(cfg.art_gate_script) if cfg.art_gate_script else None
        if gate_script is None or not gate_script.is_file():
            self.fail(
                f"ART read-only gate script not found: {gate_script} "
                "(the cross-line interface must be integrated; no stand-in is used)"
            )
        if self.failures:
            return self.finish(EXIT_FAILED)

        # 2. read-only identity gate ---------------------------------------
        state = self.run_adb("get-state", timeout=cfg.adb_timeout)
        self.step("adb/get-state", state, save_dir=self.out_dir / "identity")
        if not self._ok(state):
            self.fail(
                f"`adb -s {cfg.serial} get-state` unusable (exit={state.exit_code}, "
                f"out={state.stdout.strip()[:200]!r})"
            )
            return self.finish(EXIT_FAILED)
        if state.stdout.strip() != "device":
            self.fail(f"target {cfg.serial} is in state {state.stdout.strip()!r}, not 'device'")
            return self.finish(EXIT_FAILED)

        props_result = self.run_adb("shell", "getprop", timeout=cfg.adb_timeout)
        self.step("adb/getprop", props_result, save_dir=self.out_dir / "identity")
        raw_path = self.out_dir / "identity" / "boot-getprop-raw.txt"
        raw_path.write_text(props_result.stdout, encoding="utf-8")

        if not self._ok(props_result):
            self.fail("getprop command failed; refusing mutations despite any partial stdout")
            return self.finish(EXIT_FAILED)
        props = parse_getprop_dump(props_result.stdout)
        if not props:
            self.fail("no boot properties could be parsed from `getprop`; cannot confirm target")
            return self.finish(EXIT_FAILED)

        eligible, reasons, identity = evaluate_target_identity(props)
        self.data["target_identity"] = identity
        write_json(self.out_dir / "boot-identity.json", identity)
        if not eligible:
            for reason in reasons:
                self.refuse(reason)
            return self.finish(EXIT_REFUSED)

        self.data["mutation_allowed"] = True
        self.log(
            f"[driver] target {cfg.serial} confirmed emulator "
            f"api={identity['ro.build.version.sdk']} avd={identity['ro.boot.qemu.avd_name']!r} "
            f"model={identity['ro.product.model']!r}"
        )
        if self.expired():
            self.fail("driver deadline exceeded before the mutation phase")
            return self.finish(EXIT_FAILED)

        # 3. install + readback --------------------------------------------
        installs = (
            ("app", ["install", "-r", str(cfg.apk)], cfg.install_timeout),
            ("test", ["install", "-r", "-t", str(cfg.test_apk)], cfg.install_timeout),
        )
        for label, argv, budget in installs:
            result = self.run_adb(*argv, timeout=budget)
            self.step(f"install/{label}", result, save_dir=self.out_dir / "install")
            if not self._ok(result) or "Failure" in result.stdout:
                self.fail(
                    f"install/{label} failed (exit={result.exit_code}): "
                    f"{result.stdout.strip()[:200]!r}"
                )
        if self.failures:
            return self.finish(EXIT_FAILED)

        readbacks = (
            ("app", cfg.package, "apk"),
            ("test", cfg.test_package, "test_apk"),
        )
        for label, package, key in readbacks:
            path_result = self.run_adb("shell", f"pm path {package}", timeout=cfg.adb_timeout)
            self.step(f"pm-path/{label}", path_result, save_dir=self.out_dir / "install")
            if not self._ok(path_result):
                self.fail(f"pm path failed for {package} (exit={path_result.exit_code})")
                continue
            device_path = first_package_path(path_result.stdout)
            if not device_path:
                self.fail(
                    f"`pm path {package}` returned no package path: "
                    f"{path_result.stdout.strip()[:200]!r}"
                )
                continue
            sum_result = self.run_adb(
                "shell", f"sha256sum {shlex.quote(device_path)}", timeout=cfg.adb_timeout
            )
            self.step(f"device-sha256/{label}", sum_result, save_dir=self.out_dir / "install")
            device_sha = first_sha256_token(sum_result.stdout)
            expected_sha = self.data[key]["sha256"]
            matched = self._ok(sum_result) and device_sha == expected_sha
            self.data["install"][label] = {
                "package": package,
                "device_path": device_path,
                "device_sha256": device_sha,
                "expected_sha256": expected_sha,
                "match": matched,
            }
            if not matched:
                self.fail(
                    f"install readback mismatch for {package}: device={device_sha!r} "
                    f"local={expected_sha!r}"
                )
        if self.failures:
            return self.finish(EXIT_FAILED)

        # 4. capability grants, exactly the pre-existing workflow semantics.
        #    These are best-effort (the old workflow used `|| true`); the
        #    instrumentation tests are the real capability check. No extra
        #    host permission is requested or granted here.
        grants = (
            (
                "appops-manage-external-storage",
                ["shell", "appops", "set", cfg.package, "MANAGE_EXTERNAL_STORAGE", "allow"],
            ),
            (
                "grant-read-external-storage",
                ["shell", "pm", "grant", cfg.package, "android.permission.READ_EXTERNAL_STORAGE"],
            ),
            (
                "grant-write-external-storage",
                ["shell", "pm", "grant", cfg.package, "android.permission.WRITE_EXTERNAL_STORAGE"],
            ),
        )
        for name, argv in grants:
            result = self.run_adb(*argv, timeout=cfg.adb_timeout)
            self.step(f"grant/{name}", result, save_dir=self.out_dir / "install", best_effort=True)

        force_stop = self.run_adb("shell", "am", "force-stop", cfg.package, timeout=cfg.adb_timeout)
        self.step("app/force-stop", force_stop, save_dir=self.out_dir / "art")
        if not self._ok(force_stop):
            self.fail(f"`am force-stop {cfg.package}` failed (exit={force_stop.exit_code})")
            return self.finish(EXIT_FAILED)

        # 5. AOT compile request (this emulator only) -----------------------
        compile_argv = ["shell", "cmd", "package", "compile", "-m", "speed", "-f", cfg.package]
        compile_result = self.run_adb(*compile_argv, timeout=cfg.compile_timeout)
        compile_step = self.step("art/compile-speed", compile_result, save_dir=self.out_dir / "art")
        self.data["art"]["compile"] = {
            "requested_mode": "speed",
            "argv": list(compile_result.argv),
            "exit_code": compile_result.exit_code,
            "timed_out": compile_result.timed_out,
            "reported_success": "Success" in compile_result.stdout,
            "stdout": compile_result.stdout.strip()[:500],
            "step_index": compile_step["index"],
            "note": "a compile *request*; the read-only gate below reads the actual ART state",
        }
        if not self._ok(compile_result) or "Error" in compile_result.stdout:
            self.fail(
                f"`cmd package compile -m speed -f {cfg.package}` failed "
                f"(exit={compile_result.exit_code}, out={compile_result.stdout.strip()[:200]!r})"
            )
            return self.finish(EXIT_FAILED)

        dump_result = self.run_adb(
            "shell", "dumpsys", "package", cfg.package, timeout=cfg.adb_timeout
        )
        self.step("art/dumpsys-package", dump_result, save_dir=self.out_dir / "art")
        self.data["art"]["readback_dumpsys_exit_code"] = dump_result.exit_code
        if not self._ok(dump_result):
            self.fail("dumpsys package failed after the compile request")
            return self.finish(EXIT_FAILED)

        # 6. ART read-only gate (frozen cross-line interface) ---------------
        gate_argv = [
            cfg.python_bin,
            str(gate_script),
            "--transport", "adb",
            "--serial", cfg.serial,
            "--package", cfg.package,
            "--expected-apk-sha256", self.data["apk"]["sha256"],
            "--out-dir", str(self.out_dir / "art-gate"),
        ]
        report_path = self.out_dir / "art-gate" / "report.json"
        if report_path.exists():
            report_path.unlink()  # never accept a previous invocation's verdict
        budget = min(cfg.art_gate_timeout, self.remaining())
        gate_result = (self.gate_runner(list(gate_argv), budget) if budget > 0 else
                       CmdResult(gate_argv, -1, stderr="driver deadline exhausted", timed_out=True))
        gate_report = None
        report_problem = None
        try:
            gate_report = json.loads(report_path.read_text(encoding="utf-8"))
            cli = gate_report.get("cli", {})
            artifact = gate_report.get("artifact", {})
            if not (gate_report.get("passed") is True and gate_report.get("status") == "PASS"
                    and cli.get("transport") == "adb" and cli.get("serial") == cfg.serial
                    and cli.get("package") == cfg.package
                    and cli.get("expected_apk_sha256") == self.data["apk"]["sha256"]
                    and artifact.get("expected_apk_sha256") == self.data["apk"]["sha256"]):
                report_problem = "ART report failed verdict/target/APK identity readback"
        except (OSError, ValueError, TypeError, AttributeError) as exc:
            report_problem = f"ART report missing or malformed: {exc}"
        if report_problem:
            self.fail(report_problem)
        gate_entry = self.step("art/read-only-gate", gate_result, save_dir=self.out_dir / "art")
        gate_entry["argv"] = list(gate_argv)
        self.data["art"]["read_only_gate"] = {
            "argv": list(gate_argv),
            "exit_code": gate_result.exit_code,
            "timed_out": gate_result.timed_out,
            "out_dir": str(self.out_dir / "art-gate"),
            "passed": self._ok(gate_result) and report_problem is None,
            "report_path": str(report_path),
            "report_problem": report_problem,
            "step_index": gate_entry["index"],
        }
        if not self._ok(gate_result) or report_problem is not None:
            self.fail(
                f"ART read-only gate failed (exit={gate_result.exit_code}); refusing to run "
                "instrumentation on an unverified ART state"
            )
            return self.finish(EXIT_FAILED)

        # 7. instrumentation ------------------------------------------------
        launch = self.run_adb(
            "shell", "am", "start", "-W", "-n", cfg.launch_component, timeout=cfg.adb_timeout
        )
        self.step("app/am-start", launch, save_dir=self.out_dir / "instrumentation")
        if (not self._ok(launch) or
                re.search(r"(?:^|\n)\s*(?:Error|Exception|Failure)\b",
                          launch.stdout + "\n" + launch.stderr, re.IGNORECASE)):
            self.fail("ordinary app launch failed; instrumentation cannot replace startup acceptance")
            return self.finish(EXIT_FAILED)
        if cfg.settle_seconds:
            time.sleep(cfg.settle_seconds)

        for test_class, expected in cfg.expectations:
            argv = [
                "shell", "am", "instrument", "-w", "-r",
                "-e", "class", test_class,
                f"{cfg.test_package}/{cfg.runner}",
            ]
            result = self.run_adb(*argv, timeout=cfg.instrumentation_timeout)
            entry = self.step(
                f"instrument/{test_class.rsplit('.', 1)[-1]}",
                result,
                save_dir=self.out_dir / "instrumentation",
            )
            verdict = parse_instrumentation(result.stdout, expected)
            verdict["class"] = test_class
            verdict["step_index"] = entry["index"]
            verdict["adb_exit_code"] = result.exit_code
            verdict["timed_out"] = bool(result.timed_out)
            verdict["raw_stdout"] = str(
                self.out_dir / "instrumentation"
                / f"instrument_{test_class.rsplit('.', 1)[-1]}.stdout.txt"
            )
            if result.timed_out:
                verdict["problems"].append("instrumentation did not finish within its timeout")
            if result.exit_code != 0:
                verdict["problems"].append(
                    f"adb reported exit {result.exit_code} for the instrumentation command"
                )
            verdict["passed"] = not verdict["problems"]
            self.data["instrumentation"].append(verdict)
            if not verdict["passed"]:
                self.fail(f"instrumentation {test_class} failed: {'; '.join(verdict['problems'])}")

        # 8. ordering evidence: AOT compile must precede instrumentation ----
        indices = [v["step_index"] for v in self.data["instrumentation"]]
        ordered = bool(indices) and compile_step["index"] < min(indices)
        self.data["art"]["compile"]["before_instrumentation"] = ordered
        if indices and not ordered:
            self.fail("AOT compile was not recorded before instrumentation")

        if self.failures:
            return self.finish(EXIT_FAILED)
        if self.data["instrumentation"] and all(v["passed"] for v in self.data["instrumentation"]):
            self.log("[driver] instrumentation matched every expected test count")
        return self.finish(EXIT_OK)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def parse_expectations(values: Optional[Iterable[str]]) -> tuple:
    if not values:
        return DEFAULT_EXPECTATIONS
    parsed = []
    for raw in values:
        if "=" not in raw:
            raise UsageError(f"--expect must be CLASS=COUNT, got {raw!r}")
        cls, _, count = raw.partition("=")
        cls = cls.strip()
        if not cls or not count.strip().isdigit() or int(count.strip()) <= 0:
            raise UsageError(f"--expect must be CLASS=COUNT, got {raw!r}")
        parsed.append((cls, int(count.strip())))
    return tuple(parsed)


def build_parser() -> argparse.ArgumentParser:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(
        prog="beta_emulator_certify.py",
        description="Beta certification driver for one explicit emulator target (work line: ci).",
    )
    parser.add_argument("--serial", required=True, help="explicit adb serial, e.g. emulator-5554")
    parser.add_argument("--apk", required=True, type=Path, help="verification universal APK")
    parser.add_argument("--test-apk", required=True, type=Path, help="verification instrumentation APK")
    parser.add_argument("--out-dir", required=True, type=Path, help="evidence output directory")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--test-package", default=DEFAULT_TEST_PACKAGE)
    parser.add_argument("--runner", default=DEFAULT_RUNNER)
    parser.add_argument("--launch-component", default=DEFAULT_LAUNCH_COMPONENT)
    parser.add_argument(
        "--expect",
        action="append",
        metavar="CLASS=COUNT",
        help="instrumentation class and its exact expected test count (repeatable)",
    )
    parser.add_argument("--art-gate-script", type=Path, default=here / "beta_art_gate.py")
    parser.add_argument("--python-bin", default=sys.executable or "python3")
    parser.add_argument("--source-head", default=None, help="exact head recorded as the tested source")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-timeout", type=float, default=120.0)
    parser.add_argument("--install-timeout", type=float, default=600.0)
    parser.add_argument("--compile-timeout", type=float, default=300.0)
    parser.add_argument("--instrumentation-timeout", type=float, default=600.0)
    parser.add_argument("--art-gate-timeout", type=float, default=300.0)
    parser.add_argument("--settle-seconds", type=float, default=3.0)
    parser.add_argument("--deadline-seconds", type=float, default=1800.0)
    return parser


def config_from_args(args) -> CertConfig:
    return CertConfig(
        serial=args.serial,
        apk=Path(args.apk),
        test_apk=Path(args.test_apk),
        out_dir=Path(args.out_dir),
        package=args.package,
        test_package=args.test_package,
        runner=args.runner,
        launch_component=args.launch_component,
        expectations=parse_expectations(args.expect),
        art_gate_script=Path(args.art_gate_script) if args.art_gate_script else None,
        python_bin=args.python_bin,
        source_head=args.source_head,
        adb_timeout=args.adb_timeout,
        install_timeout=args.install_timeout,
        compile_timeout=args.compile_timeout,
        instrumentation_timeout=args.instrumentation_timeout,
        art_gate_timeout=args.art_gate_timeout,
        settle_seconds=args.settle_seconds,
        deadline_seconds=args.deadline_seconds,
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        adb = Adb(args.serial, adb_bin=args.adb, default_timeout=args.adb_timeout)
        config = config_from_args(args)
    except UsageError as exc:
        print(f"[driver] refusing to run: {exc}", file=sys.stderr)
        return EXIT_USAGE

    certification = Certification(config, adb, gate_runner=run_command, log=print)
    return certification.run()


if __name__ == "__main__":
    sys.exit(main())
