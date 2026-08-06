#!/usr/bin/env python3
"""Extract per-test timing from Gradle JUnit XML reports for perf comparison.

Usage: python3 perf_extract_timing.py <arch> <output-file>

Reads terminal-emulator/build/test-results/testDebugUnitTest/TEST-*.xml
produced by `./gradlew :terminal-emulator:testDebugUnitTest`. Each
<testcase> element carries a `time` attribute (wall seconds) -- this is
more precise and stable than parsing --info log lines, and it is what
the perf.yml compare job feeds the x86_64-vs-aarch64 table from.
"""
import glob
import sys
import xml.etree.ElementTree as ET

ARCH = sys.argv[1] if len(sys.argv) > 1 else "unknown"
OUT = sys.argv[2] if len(sys.argv) > 2 else f"perf-timing-{ARCH}.txt"

REPORT_GLOB = "terminal-emulator/build/test-results/testDebugUnitTest/TEST-*.xml"
TOP_N = 20


def main() -> int:
    files = sorted(glob.glob(REPORT_GLOB))
    if not files:
        print(f"ERROR: no JUnit XML reports under {REPORT_GLOB}; did the test task run?",
              file=sys.stderr)
        return 1

    suite_times = {}
    test_times = []
    for f in files:
        root = ET.parse(f).getroot()
        suite = root.get("name", f)
        suite_times[suite] = float(root.get("time", 0))
        for tc in root.iter("testcase"):
            test_times.append((tc.get("classname", ""), tc.get("name", ""),
                               float(tc.get("time", 0))))

    total_suite = sum(suite_times.values())
    total_tests = len(test_times)
    lines = [
        f"arch: {ARCH}",
        f"test classes: {len(suite_times)}, tests: {total_tests}, "
        f"suite wall total: {total_suite:.3f}s",
        "",
        "per test class (wall seconds, lower is better):",
    ]
    for suite, t in sorted(suite_times.items(), key=lambda kv: -kv[1]):
        lines.append(f"  {suite:60s} {t:8.3f}s")
    lines.append("")
    lines.append(f"slowest {TOP_N} individual tests:")
    for cls, name, t in sorted(test_times, key=lambda x: -x[2])[:TOP_N]:
        lines.append(f"  {cls}.{name:55s} {t:8.3f}s")

    text = "\n".join(lines) + "\n"
    with open(OUT, "w") as w:
        w.write(text)
    print(text)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
