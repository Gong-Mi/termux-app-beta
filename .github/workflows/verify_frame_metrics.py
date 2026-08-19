#!/usr/bin/env python3
"""Verify terminal frame-metric log lines against the parser/render contract.

Input: a text file containing lines of the form emitted by
TerminalView.logFrameInfo() with DEBUG_FRAME_INFO enabled, e.g.

    frame rev=123 published=45 lastPublishedRev=123 drawn=44 lastDrawnRev=122
        dropped=0 coalesced=12 acked=122 mutations=5 visible=24 ...

Invariants checked (per log line, in order):
  1. revision is non-decreasing across draw log lines (a frame may be redrawn)
  2. lastPublishedRev monotonically non-decreasing
  3. all lifecycle counters are non-negative
  4. acked == lastDrawnRev        (ack records the last successfully drawn revision)
  5. acked <= lastPublishedRev    (never ack a revision that was not published)
  6. visible > 0                  (render always has a non-empty viewport)

Optional: --min-lines N requires at least N parsed lines (default 1).

Exits 0 on success, 1 on any violation. Prints a one-line summary on success.
"""
import argparse
import re
import sys

FIELD_RE = re.compile(r"(published|lastPublishedRev|drawn|lastDrawnRev|dropped|coalesced"
                      r"|acked|visible|rev)=(-?\d+)")


def parse_lines(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if "frame rev=" not in line:
                continue
            values = dict(FIELD_RE.findall(line))
            if "rev" not in values:
                continue
            try:
                ints = {k: int(v) for k, v in values.items()}
            except ValueError:
                continue
            rows.append(ints)
    return rows


def check_row(row):
    published = row.get("published")
    drawn = row.get("drawn")
    dropped = row.get("dropped")
    acked = row.get("acked")
    last_drawn_rev = row.get("lastDrawnRev")
    last_published_rev = row.get("lastPublishedRev")
    visible = row.get("visible")

    if None in (published, drawn, dropped, acked, last_drawn_rev, last_published_rev, visible):
        return f"missing field in row: {row}"
    for name, value in (("published", published), ("drawn", drawn), ("dropped", dropped),
                        ("lastPublishedRev", last_published_rev)):
        if value < 0:
            return f"{name}({value}) < 0"
    if acked != last_drawn_rev:
        return f"acked({acked}) != lastDrawnRev({last_drawn_rev})"
    if acked > last_published_rev:
        return f"acked({acked}) > lastPublishedRev({last_published_rev})"
    if visible <= 0:
        return f"visible({visible}) <= 0"
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile", help="file containing 'frame rev=' log lines")
    ap.add_argument("--min-lines", type=int, default=1, help="minimum required line count")
    args = ap.parse_args()

    rows = parse_lines(args.logfile)
    if len(rows) < args.min_lines:
        print(f"FAIL: expected >= {args.min_lines} frame lines, got {len(rows)}")
        sys.exit(1)
    if not rows:
        print("FAIL: no 'frame rev=' lines parsed")
        sys.exit(1)

    prev_rev = None
    prev_published_rev = None
    for i, row in enumerate(rows):
        rev = row["rev"]
        if prev_rev is not None and rev < prev_rev:
            print(f"FAIL: revision decreased at line {i + 1}: {rev} < {prev_rev}")
            sys.exit(1)
        prev_rev = rev
        lpr = row.get("lastPublishedRev")
        if prev_published_rev is not None and lpr < prev_published_rev:
            print(f"FAIL: lastPublishedRev decreased at line {i + 1}: {lpr} < {prev_published_rev}")
            sys.exit(1)
        prev_published_rev = lpr
        err = check_row(row)
        if err:
            print(f"FAIL: line {i + 1}: {err}")
            print(f"  row: {row}")
            sys.exit(1)

    last = rows[-1]
    max_rev_gap = max((rows[i]["rev"] - rows[i - 1]["rev"]) for i in range(1, len(rows)))
    print("PASS frames={} firstRev={} lastRev={} published={} drawn={} dropped={} "
          "coalesced={} acked={} maxRevGap={}"
          .format(len(rows), rows[0]["rev"], last["rev"], last.get("published", 0),
                  last.get("drawn", 0), last.get("dropped", 0), last.get("coalesced", 0),
                  last.get("acked", 0), max_rev_gap))


if __name__ == "__main__":
    main()