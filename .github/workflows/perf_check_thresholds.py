#!/usr/bin/env python3
"""Fail the perf job when a tracked benchmark regresses past its budget.

Usage: python3 perf_check_thresholds.py <perf-timing.txt>

Reads the timing artifact produced by perf_extract_timing.py and compares
the individual-test wall times against the budgets below. Budgets are set
with ~4x headroom over the measured post-optimization numbers so normal CI
jitter passes, but a structural regression (e.g. the old O(columns^2)
row scans or the per-code-point binary-search width lookup) fails loudly.

Tracked benchmarks and their budgets (seconds, aarch64 is the slower leg
for CJK; budgets cover both runners):
  testPerfMixedCjkBurst                           0.6   (measured ~0.14)
  testPerfWorkerSnapshotMailboxMixedCjkBurst      0.6   (measured ~0.12)
  testPerfAsciiBurst                              1.0   (measured ~0.21)
  testPerfCsiColorBurst                           0.5   (measured ~0.03)
"""
import re
import sys

BUDGETS = {
    "testPerfMixedCjkBurst": 0.6,
    "testPerfWorkerSnapshotMailboxMixedCjkBurst": 0.6,
    "testPerfAsciiBurst": 1.0,
    "testPerfCsiColorBurst": 0.5,
}

LINE_RE = re.compile(r"^\s+([\w.]+)\.([\w]+)\s+([0-9.]+)s$")


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "perf-timing.txt"
    try:
        with open(path) as f:
            lines = f.readlines()
    except OSError as e:
        print(f"ERROR: cannot read timing file {path}: {e}", file=sys.stderr)
        return 1

    measured = {}
    for line in lines:
        m = LINE_RE.match(line)
        if m:
            measured[m.group(2)] = float(m.group(3))

    failed = False
    for name, budget in sorted(BUDGETS.items()):
        actual = measured.get(name)
        if actual is None:
            print(f"WARN: tracked benchmark {name} not found in timing file", file=sys.stderr)
            continue
        status = "OK" if actual <= budget else "FAIL"
        if status == "FAIL":
            failed = True
        print(f"{status} {name:45s} {actual:6.3f}s (budget {budget:.1f}s)")
    if failed:
        print("PERF-REGRESSION: tracked benchmark(s) exceeded their budget; "
              "see .github/workflows/perf_check_thresholds.py", file=sys.stderr)
        return 1
    print("PERF-OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())