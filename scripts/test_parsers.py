#!/usr/bin/env python3
"""Regression tests for CI evidence parsers."""
import tempfile
import unittest
from pathlib import Path

from parse_gfxinfo import parse


VALID_GFXINFO = """
Stats since: 1000ns
Total frames rendered: 42
Janky frames: 3 (7.14%)
50th percentile: 5ms
90th percentile: 9ms
95th percentile: 12ms
99th percentile: 18ms
Number Missed Vsync: 1
Number High input latency: 2
Number Slow UI thread: 0
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 0
Number Frame deadline missed: 1
"""


class GfxInfoParserContractTest(unittest.TestCase):
    def _write(self, text: str) -> Path:
        handle = tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False)
        with handle:
            handle.write(text)
        return Path(handle.name)

    def test_valid_gfxinfo_contains_real_frame_evidence(self):
        path = self._write(VALID_GFXINFO)
        try:
            result = parse(path)
        finally:
            path.unlink()
        self.assertEqual(result["total_frames_rendered"], 42)
        self.assertEqual(result["janky_frames"], 3)
        self.assertEqual(result["janky_ratio"], 0.0714)

    def test_empty_gfxinfo_is_a_hard_failure(self):
        path = self._write("adb failed\n")
        try:
            with self.assertRaises(ValueError):
                parse(path)
        finally:
            path.unlink()

    def test_zero_frame_gfxinfo_is_a_hard_failure(self):
        path = self._write("Total frames rendered: 0\nJanky frames: 0\n")
        try:
            with self.assertRaises(ValueError):
                parse(path)
        finally:
            path.unlink()


if __name__ == "__main__":
    unittest.main()
