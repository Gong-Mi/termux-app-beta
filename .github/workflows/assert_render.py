#!/usr/bin/env python3
"""Pixel assertions for render smoke screenshots (GPU emulation CI).

Exit codes:
  0 = pass, 1 = assertion failure, 2 = harness/unreadable input

Assertions (keep deterministic, no OCR):
  A. decodable PNG at expected minimum size
  B. not a blank screen: single dominant color must not cover >= 98%
  C. terminal content present: glyph (bright) pixel ratio within sane band,
     and content bounding box spans most of the screen area
"""

import sys
from collections import Counter

from PIL import Image

MIN_W, MIN_H = 720, 1280
DOMINANT_MAX_SHARE = 0.98
GLYPH_MIN_RATIO = 0.005  # 0.5% bright pixels: any real text render exceeds this
GLYPH_MAX_RATIO = 0.60   # more than 60% bright = probably inverted/blank white
CONTENT_BOX_MIN_COVERAGE = 0.50  # bbox area / screen area
BRIGHT_THRESHOLD = 128


def fail(code, msg):
    print(f"FAIL[{code}]: {msg}")
    sys.exit(1)


def main():
    if len(sys.argv) != 2:
        print("usage: assert_render.py <screenshot.png>")
        sys.exit(2)
    path = sys.argv[1]
    try:
        im = Image.open(path).convert("RGB")
    except Exception as e:  # noqa: BLE001 - report any decode failure
        print(f"HARNESS: cannot open/decode {path}: {e}")
        sys.exit(2)

    w, h = im.size
    print(f"info: size={w}x{h}")
    if w < MIN_W or h < MIN_H:
        fail("size", f"{w}x{h} below minimum {MIN_W}x{MIN_H}")

    total = w * h
    pixels = list(im.getdata())

    # A. dominant color share (blank-screen detector)
    dominant, count = Counter(pixels).most_common(1)[0]
    share = count / total
    print(f"info: dominant color {dominant} share={share:.4f}")
    if share >= DOMINANT_MAX_SHARE:
        fail("blank", f"dominant color covers {share:.2%} (>= {DOMINANT_MAX_SHARE:.0%}) - blank screen")

    # B. bright (glyph-ish) pixel ratio
    bright = sum(1 for p in pixels if p[0] > BRIGHT_THRESHOLD and p[1] > BRIGHT_THRESHOLD and p[2] > BRIGHT_THRESHOLD)
    ratio = bright / total
    print(f"info: bright pixel ratio={ratio:.4f}")
    if ratio < GLYPH_MIN_RATIO:
        fail("glyphs", f"bright ratio {ratio:.4%} < {GLYPH_MIN_RATIO:.2%} - no text rendered")
    if ratio > GLYPH_MAX_RATIO:
        fail("inverted", f"bright ratio {ratio:.4%} > {GLYPH_MAX_RATIO:.0%} - likely blank white/inverted")

    # C. content bounding box coverage
    gray = im.convert("L")
    bbox = gray.point(lambda p: 255 if p > BRIGHT_THRESHOLD else 0).getbbox()
    print(f"info: content bbox={bbox}")
    if bbox is None:
        fail("bbox", "no bright content found")
    bw = bbox[2] - bbox[0]
    bh = bbox[3] - bbox[1]
    coverage = (bw * bh) / total
    print(f"info: bbox coverage={coverage:.4f}")
    if coverage < CONTENT_BOX_MIN_COVERAGE:
        fail("bbox", f"content bbox covers {coverage:.2%} < {CONTENT_BOX_MIN_COVERAGE:.0%} of screen")

    print("PASS: non-blank, glyphs present, content bbox spans screen")
    sys.exit(0)


if __name__ == "__main__":
    main()
