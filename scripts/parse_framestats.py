#!/usr/bin/env python3
"""Parse `dumpsys gfxinfo <pkg> framestats` PROFILEDATA into per-stage percentiles.

Framestats gives nanosecond timestamps per rendered frame (AOSP FrameInfo),
which lets us split the frame pipeline into stages instead of relying on the
aggregate janky/bucket histogram alone:

  IntendedVsync -> FrameCompleted          total frame duration
  DrawStart -> SyncQueued                  UI thread: measure/layout + record
  SyncQueued -> SyncStart                  UI blocked waiting for RenderThread sync
  SyncStart -> SwapBuffers                 RenderThread: sync + draw + swap
  SwapBuffers -> GpuCompleted              GPU completion (when reported)

Rows with the window-layout-changed flag are counted but excluded from the
steady-state percentiles. Fields may be 0 or Long.MAX_VALUE when an event did
not happen (e.g. no input event); such rows are excluded per-stage.

Usage: parse_framestats.py <framestats.txt> <out.json>
Exit code is 0 even when no usable frames were found (the JSON then carries
frames_used=0) so evidence collection never fails the workflow by itself.
"""

import json
import math
import sys

LONG_MAX = 9223372036854775807
NS_PER_MS = 1_000_000.0

FLAG_WINDOW_LAYOUT_CHANGED = 1

STAGES = (
    # (name, start_column, end_column)
    ("total_ms", "IntendedVsync", "FrameCompleted"),
    ("ui_draw_ms", "DrawStart", "SyncQueued"),
    ("sync_wait_ms", "SyncQueued", "SyncStart"),
    ("rt_draw_ms", "SyncStart", "SwapBuffers"),
    ("gpu_ms", "SwapBuffers", "GpuCompleted"),
)


def percentile(sorted_vals, p):
    if not sorted_vals:
        return None
    rank = max(0, math.ceil(p * len(sorted_vals)) - 1)
    return round(sorted_vals[rank], 3)


def summarize(vals):
    vals = sorted(vals)
    if not vals:
        return {"count": 0}
    return {
        "count": len(vals),
        "p50": percentile(vals, 0.50),
        "p90": percentile(vals, 0.90),
        "p95": percentile(vals, 0.95),
        "p99": percentile(vals, 0.99),
        "max": round(vals[-1], 3),
        "mean": round(sum(vals) / len(vals), 3),
    }


def parse_profile_blocks(text):
    """Yield (header_columns, rows) for each PROFILEDATA block with a Flags header."""
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        if lines[i].strip() == "---PROFILEDATA---":
            j = i + 1
            if j < len(lines) and lines[j].lstrip().startswith("Flags,"):
                header = [c.strip() for c in lines[j].split(",") if c.strip()]
                rows = []
                k = j + 1
                while k < len(lines) and lines[k].strip() != "---PROFILEDATA---":
                    parts = [p.strip() for p in lines[k].split(",") if p.strip()]
                    if len(parts) >= len(header) - 1:
                        try:
                            rows.append([int(p) for p in parts])
                        except ValueError:
                            pass
                    k += 1
                yield header, rows
                i = k
            else:
                i = j
        else:
            i += 1


def valid(value):
    return value > 0 and value != LONG_MAX


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    with open(sys.argv[1], errors="replace") as f:
        text = f.read()

    frames_total = 0
    frames_layout_changed = 0
    stage_values = {name: [] for name, _, _ in STAGES}

    for header, rows in parse_profile_blocks(text):
        col = {name: idx for idx, name in enumerate(header)}
        required = {"Flags"}
        required.update(c for _, a, b in STAGES for c in (a, b) if c in col)
        if "Flags" not in col:
            continue
        for row in rows:
            frames_total += 1
            flags = row[col["Flags"]]
            if flags & FLAG_WINDOW_LAYOUT_CHANGED:
                frames_layout_changed += 1
                continue
            for name, start_col, end_col in STAGES:
                if start_col not in col or end_col not in col:
                    continue
                start = row[col[start_col]]
                end = row[col[end_col]]
                if not valid(start) or not valid(end) or end < start:
                    continue
                stage_values[name].append((end - start) / NS_PER_MS)

    summary = {
        "frames_total": frames_total,
        "frames_layout_changed_excluded": frames_layout_changed,
        "frames_used": len(stage_values["total_ms"]),
        "stages": {name: summarize(vals) for name, vals in stage_values.items()},
    }

    with open(sys.argv[2], "w") as f:
        json.dump(summary, f, indent=2)
        f.write("\n")

    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
