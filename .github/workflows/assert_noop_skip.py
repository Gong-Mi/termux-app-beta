#!/usr/bin/env python3
"""Assert that publishes which cannot change a pixel are consumed, not drawn.

Frame diagnostics lines carry cumulative counters, e.g.

    frame rev=... published=40 drawn=118 acked=... noopSkips=6 ...

A "no-op publish" is a screen revision that reaches the view with no cell write,
no cursor movement and no selection change: it cannot change any pixel, so the
view must consume it without starting a draw. The counter that proves this is
`noopSkips`, which (like every other counter) is printed only when a draw
happens, so the check compares consecutive drawn frames:

  * find a pair of consecutive frame lines where `noopSkips` grew by at least
    --min-noop-skips while `drawn` grew by at most --max-drawn-delta;
  * fail when the field is missing entirely, which is the pre-fix line format.

Exit code 0 when such a pair exists, 1 otherwise.
"""

import argparse
import re
import sys

FIELD = {
    "rev": re.compile(r"\brev=(\d+)"),
    "drawn": re.compile(r"\bdrawn=(\d+)"),
    "noop": re.compile(r"\bnoopSkips=(\d+)"),
}


def parse_lines(path):
    rows = []
    missing_noop = 0
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if "frame rev=" not in line:
                continue
            if FIELD["noop"].search(line) is None:
                missing_noop += 1
                continue
            row = {}
            for name, pattern in FIELD.items():
                match = pattern.search(line)
                if match is None:
                    row = None
                    break
                row[name] = int(match.group(1))
            if row:
                rows.append(row)
    return rows, missing_noop


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logfile", help="file containing 'frame rev=' log lines")
    parser.add_argument("--min-noop-skips", type=int, default=3,
                        help="minimum noopSkips growth between two drawn frames")
    parser.add_argument("--max-drawn-delta", type=int, default=3,
                        help="maximum drawn growth allowed over that growth")
    parser.add_argument("--show-last", type=int, default=12,
                        help="how many trailing lines to print as evidence")
    args = parser.parse_args()

    rows, missing_noop = parse_lines(args.logfile)
    if missing_noop:
        print(f"FAIL: {missing_noop} frame line(s) have no noopSkips field; "
              f"this build cannot prove the no-op publish gate")
        return 1
    if len(rows) < 2:
        print(f"FAIL: need at least 2 drawn frames to compare, found {len(rows)}")
        return 1

    print(f"{'rev':>10s} {'drawn':>8s} {'noopSkips':>10s} {'d_drawn':>8s} {'d_noop':>8s}")
    best = None
    for index in range(len(rows) - 1):
        drawn_delta = rows[index + 1]["drawn"] - rows[index]["drawn"]
        noop_delta = rows[index + 1]["noop"] - rows[index]["noop"]
        print(f"{rows[index + 1]['rev']:>10d} {rows[index + 1]['drawn']:>8d} "
              f"{rows[index + 1]['noop']:>10d} {drawn_delta:>8d} {noop_delta:>8d}")
        if noop_delta >= args.min_noop_skips and drawn_delta <= args.max_drawn_delta:
            if best is None or noop_delta > best[1]:
                best = (index, noop_delta, drawn_delta)

    if best is None:
        print(f"FAIL: no pair of drawn frames with noopSkips >= {args.min_noop_skips} "
              f"and drawn delta <= {args.max_drawn_delta}: publishes that cannot change "
              f"a pixel are still being drawn")
        return 1

    index, noop_delta, drawn_delta = best
    print(f"PASS: frames {rows[index]['rev']} -> {rows[index + 1]['rev']} consumed "
          f"{noop_delta} no-op publish(es) costing {drawn_delta} draw(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
