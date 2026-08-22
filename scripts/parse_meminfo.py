#!/usr/bin/env python3
"""Extract comparable Android dumpsys meminfo metrics."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def parse(path: Path) -> dict[str, int | str]:
    text = path.read_text(encoding="utf-8", errors="replace")

    def value(pattern: str) -> int:
        match = re.search(pattern, text, re.MULTILINE)
        if not match:
            raise ValueError(f"missing metric {pattern!r} in {path}")
        return int(match.group(1))

    result: dict[str, int | str] = {"file": str(path)}
    result["total_pss_kb"] = value(r"^[ ]*TOTAL PSS:[ ]*([0-9]+)")
    result["total_rss_kb"] = value(r"TOTAL RSS:[ ]*([0-9]+)")
    result["total_swap_pss_kb"] = value(r"TOTAL SWAP PSS:[ ]*([0-9]+)")
    result["java_heap_pss_kb"] = value(r"^[ ]*Java Heap:[ ]*([0-9]+)")
    result["native_heap_pss_kb"] = value(r"^[ ]*Native Heap:[ ]*([0-9]+)")
    result["graphics_pss_kb"] = value(r"^[ ]*Graphics:[ ]*([0-9]+)")
    result["dalvik_heap_alloc_kb"] = value(r"^[ ]*Dalvik Heap( +[0-9]+){6} +([0-9]+)")
    result["native_heap_alloc_kb"] = value(r"^[ ]*Native Heap( +[0-9]+){5} +([0-9]+)")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--json", type=Path, help="write a JSON array")
    args = parser.parse_args()
    records = [parse(path) for path in args.files]
    output = json.dumps(records, indent=2, sort_keys=True)
    print(output)
    if args.json:
        args.json.write_text(output + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
