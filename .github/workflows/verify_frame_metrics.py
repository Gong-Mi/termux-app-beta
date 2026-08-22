#!/usr/bin/env python3
"""Verify terminal frame-metric log lines against the parser/render contract.

Input: a text file containing lines emitted by the debug-only
TerminalFrameDiagnostics implementation, e.g.

    frame rev=123 published=45 lastPublishedRev=123 drawn=44 lastDrawnRev=122
        dropped=0 coalesced=12 acked=122 mutations=5 visible=24 ...

Invariants checked (per log line, in order):
  1. revision is non-decreasing across draw log lines (a frame may be redrawn)
  2. lastPublishedRev monotonically non-decreasing
  3. all lifecycle counters are non-negative
  4. acked == lastDrawnRev        (ack records the last successfully drawn revision)
  5. acked <= lastPublishedRev    (never ack a revision that was not published)
  6. visible > 0                  (render always has a non-empty viewport)
  7. parser counters are present, non-negative and monotonic
  8. parser and render counters are kept as separate snapshots (they may differ
     by one or more events because the diagnostic line is not an atomic sample)

Optional:
  --min-lines N requires at least N parsed lines (default 1).
  --max-rev-gap N caps the largest revision jump between consecutive lines.
  --max-dropped-ratio R caps dropped / published (default 1.0, disabled at 1.0).
  --max-coalesced-ratio R caps coalesced / published.
  --max-parser-bytes-per-frame N caps parserBytes / parserFrames.
  --min-drawn-ratio R requires drawn / published >= R.

Exits 0 on success, 1 on any violation. Prints a one-line summary on success.
"""
import argparse
import re
import sys

FIELD_RE = re.compile(r"(published|lastPublishedRev|drawn|lastDrawnRev|dropped|coalesced"
                      r"|acked|visible|rev|parserBytes|appendCommands|controlCommands"
                      r"|parserFrames|finishCommands|stopCommands|skipped)=(-?\d+)")


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
    parser_fields = ("parserBytes", "appendCommands", "controlCommands", "parserFrames",
                     "finishCommands", "stopCommands")

    if None in (published, drawn, dropped, acked, last_drawn_rev, last_published_rev, visible):
        return f"missing field in row: {row}"
    missing_parser = [name for name in parser_fields if row.get(name) is None]
    if missing_parser:
        return f"missing parser field(s): {', '.join(missing_parser)}"
    for name, value in (("published", published), ("drawn", drawn), ("dropped", dropped),
                        ("lastPublishedRev", last_published_rev),
                        *[(name, row[name]) for name in parser_fields]):
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
    ap.add_argument("--max-rev-gap", type=int, default=0,
                    help="maximum allowed revision jump between consecutive frame lines "
                         "(0 disables, default 0)")
    ap.add_argument("--max-dropped-ratio", type=float, default=1.0,
                    help="maximum allowed dropped / published ratio (1.0 disables)")
    ap.add_argument("--max-coalesced-ratio", type=float, default=1.0,
                    help="maximum allowed coalesced / published ratio (1.0 disables)")
    ap.add_argument("--max-parser-bytes-per-frame", type=int, default=0,
                    help="maximum allowed parserBytes / parserFrames (0 disables)")
    ap.add_argument("--min-drawn-ratio", type=float, default=0.0,
                    help="minimum required drawn / published ratio (0.0 disables)")
    ap.add_argument("--require-skipped", action="store_true",
                    help="require at least one frame line with skipped > 0 (proves the "
                         "clean-row redraw skip path actually ran)")
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
    parser_fields = ("parserBytes", "appendCommands", "controlCommands", "parserFrames",
                     "finishCommands", "stopCommands")
    prev_parser = {name: None for name in parser_fields}
    max_skipped = 0
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
        for name in parser_fields:
            value = row[name]
            previous = prev_parser[name]
            if previous is not None and value < previous:
                print(f"FAIL: {name} decreased at line {i + 1}: {value} < {previous}")
                sys.exit(1)
            prev_parser[name] = value
        err = check_row(row)
        if err:
            print(f"FAIL: line {i + 1}: {err}")
            print(f"  row: {row}")
            sys.exit(1)
        skipped = row.get("skipped")
        if skipped is not None:
            if skipped < 0:
                print(f"FAIL: skipped({skipped}) < 0 at line {i + 1}")
                sys.exit(1)
            max_skipped = max(max_skipped, skipped)

    if args.require_skipped and max_skipped <= 0:
        print("FAIL: --require-skipped set but no frame line reports skipped > 0 "
              "(clean-row skip path never ran; check layer type/hwui mode)")
        sys.exit(1)

    # Threshold checks against aggregate counters from the last sample.
    last = rows[-1]
    first = rows[0]
    max_rev_gap = max((rows[i]["rev"] - rows[i - 1]["rev"]) for i in range(1, len(rows)))
    total_published = last.get("published", 0)
    total_drawn = last.get("drawn", 0)
    total_dropped = last.get("dropped", 0)
    total_coalesced = last.get("coalesced", 0)
    total_parser_bytes = last.get("parserBytes", 0)
    total_parser_frames = last.get("parserFrames", 0)

    if args.max_rev_gap > 0 and max_rev_gap > args.max_rev_gap:
        print(f"FAIL: maxRevGap({max_rev_gap}) > --max-rev-gap({args.max_rev_gap})")
        sys.exit(1)
    if args.max_dropped_ratio < 1.0 and total_published > 0:
        dropped_ratio = total_dropped / total_published
        if dropped_ratio > args.max_dropped_ratio:
            print(f"FAIL: dropped/published({dropped_ratio:.3f}) > "
                  f"--max-dropped-ratio({args.max_dropped_ratio})")
            sys.exit(1)
    if args.max_coalesced_ratio < 1.0 and total_published > 0:
        coalesced_ratio = total_coalesced / total_published
        if coalesced_ratio > args.max_coalesced_ratio:
            print(f"FAIL: coalesced/published({coalesced_ratio:.3f}) > "
                  f"--max-coalesced-ratio({args.max_coalesced_ratio})")
            sys.exit(1)
    if args.min_drawn_ratio > 0.0 and total_published > 0:
        drawn_ratio = total_drawn / total_published
        if drawn_ratio < args.min_drawn_ratio:
            print(f"FAIL: drawn/published({drawn_ratio:.3f}) < "
                  f"--min-drawn-ratio({args.min_drawn_ratio})")
            sys.exit(1)
    if args.max_parser_bytes_per_frame > 0 and total_parser_frames > 0:
        bytes_per_frame = total_parser_bytes / total_parser_frames
        if bytes_per_frame > args.max_parser_bytes_per_frame:
            print(f"FAIL: parserBytes/parserFrames({bytes_per_frame:.1f}) > "
                  f"--max-parser-bytes-per-frame({args.max_parser_bytes_per_frame})")
            sys.exit(1)

    print("PASS frames={} firstRev={} lastRev={} published={} drawn={} dropped={} "
          "coalesced={} acked={} appendCommands={}(+{}) controlCommands={}(+{}) "
          "parserBytes={}(+{}) parserFrames={}(+{}) finishCommands={} stopCommands={} "
          "maxRevGap={} maxSkipped={}"
          .format(len(rows), rows[0]["rev"], last["rev"], last.get("published", 0),
                  last.get("drawn", 0), last.get("dropped", 0), last.get("coalesced", 0),
                  last.get("acked", 0),
                  last.get("appendCommands", 0),
                  last.get("appendCommands", 0) - first.get("appendCommands", 0),
                  last.get("controlCommands", 0),
                  last.get("controlCommands", 0) - first.get("controlCommands", 0),
                  last.get("parserBytes", 0),
                  last.get("parserBytes", 0) - first.get("parserBytes", 0),
                  last.get("parserFrames", 0),
                  last.get("parserFrames", 0) - first.get("parserFrames", 0),
                  last.get("finishCommands", 0),
                  last.get("stopCommands", 0),
                  max_rev_gap, max_skipped))


if __name__ == "__main__":
    main()