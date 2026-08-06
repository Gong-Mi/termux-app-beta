#!/usr/bin/env python3
"""Fail CI if the unit-test job ran zero tests where tests are required.

Guards against vacuous green: a Gradle test task passes with 0 tests, so
any module's suite could silently stop running (e.g. aapt2/AGP regressions,
wrong --tests filters) without failing the run. This step makes the CI
verify that the modules we rely on actually executed tests.

Usage:
  python3 verify_tests.py --require-module=app --require-module=terminal-emulator
"""
import glob
import sys
import xml.etree.ElementTree as ET

REQUIRE = [a.split("=")[1] for a in sys.argv[1:] if a.startswith("--require-module=")]
reports = sorted(glob.glob("*/build/test-results/*/TEST-*.xml"))
counts = {}
total = 0
failures = 0
for f in reports:
    mod = f.split("/")[0]
    try:
        root = ET.parse(f).getroot()
    except Exception as e:  # noqa: BLE001 - report and exit
        print(f"PARSE ERROR {f}: {e}", file=sys.stderr)
        sys.exit(1)
    n = int(root.get("tests", 0))
    fails = int(root.get("failures", 0)) + int(root.get("errors", 0))
    counts[mod] = counts.get(mod, 0) + n
    total += n
    failures += fails
    print(f"{f}: tests={n} failures={fails}")

print(f"TOTAL tests={total} failures={failures} modules_with_tests={sorted(counts)}")

missing = [m for m in REQUIRE if counts.get(m, 0) == 0]
if missing:
    print(f"ERROR: required modules ran zero tests: {missing}", file=sys.stderr)
    sys.exit(1)
if total == 0:
    print("ERROR: zero tests ran at all", file=sys.stderr)
    sys.exit(1)
sys.exit(1 if failures else 0)
