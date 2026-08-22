#!/usr/bin/env python3
"""Parse Android `adb shell dumpsys gfxinfo` output into a stable JSON summary.

Usage:
    python3 parse_gfxinfo.py gfxinfo-final.txt gfxinfo-summary.json

The script extracts the aggregate frame metrics for the top activity and writes
a JSON object with integer counters and computed ratios. It is intentionally
lenient on formatting so it survives minor Android version differences.
"""
import json
import re
import sys
from pathlib import Path


def parse(path):
    text = Path(path).read_text(encoding="utf-8", errors="replace")

    result = {
        "total_frames_rendered": None,
        "janky_frames": None,
        "janky_ratio": None,
        "p50_ms": None,
        "p90_ms": None,
        "p95_ms": None,
        "p99_ms": None,
        "missed_vsync": None,
        "high_input_latency": None,
        "slow_ui_thread": None,
        "slow_bitmap_uploads": None,
        "slow_issue_draw_commands": None,
        "frame_deadline_missed": None,
    }

    patterns = {
        "total_frames_rendered": re.compile(r"Total frames rendered:\s*(\d+)"),
        "janky_frames": re.compile(r"Janky frames:\s*(\d+)"),
        "p50_ms": re.compile(r"50th percentile:\s*(\d+)ms"),
        "p90_ms": re.compile(r"90th percentile:\s*(\d+)ms"),
        "p95_ms": re.compile(r"95th percentile:\s*(\d+)ms"),
        "p99_ms": re.compile(r"99th percentile:\s*(\d+)ms"),
        "missed_vsync": re.compile(r"Number Missed Vsync:\s*(\d+)"),
        "high_input_latency": re.compile(r"Number High input latency:\s*(\d+)"),
        "slow_ui_thread": re.compile(r"Number Slow UI thread:\s*(\d+)"),
        "slow_bitmap_uploads": re.compile(r"Number Slow bitmap uploads:\s*(\d+)"),
        "slow_issue_draw_commands": re.compile(r"Number Slow issue draw commands:\s*(\d+)"),
        "frame_deadline_missed": re.compile(r"Number Frame deadline missed:\s*(\d+)"),
    }

    for key, pat in patterns.items():
        m = pat.search(text)
        if m:
            result[key] = int(m.group(1))

    if result["total_frames_rendered"] and result["janky_frames"] is not None:
        total = result["total_frames_rendered"]
        if total > 0:
            result["janky_ratio"] = round(result["janky_frames"] / total, 4)

    return result


def main():
    if len(sys.argv) < 2:
        print("Usage: parse_gfxinfo.py <gfxinfo-file> [output-json-file]", file=sys.stderr)
        sys.exit(2)

    in_file = sys.argv[1]
    summary = parse(in_file)
    print(json.dumps(summary, indent=2))

    if len(sys.argv) >= 3:
        out_file = sys.argv[2]
        Path(out_file).write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
