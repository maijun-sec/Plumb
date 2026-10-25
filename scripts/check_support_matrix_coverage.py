#!/usr/bin/env python3
"""Check that the support matrix covers every measured category.

The matrix used to list its categories in a hardcoded list inside
`scripts/write_support_matrix.py`. A category that was measured but not added to that list
produced NO ROW AT ALL -- it was invisible in the report, which is exactly how a capability gap
survives a green matrix. The generator now discovers the categories, so the list cannot go
stale; this gate checks the OTHER half: that the committed matrix really does mention every
measured category.

The gate is deliberately a CROSS-FILE check. `write_support_matrix.py` discovering the
categories is the generator's job; nothing else would notice if the generated file were stale,
committed by hand, or produced by an older generator that still had the hardcoded list.

    python scripts/check_support_matrix_coverage.py

Exit code 0 when every measured category appears in the matrix, 1 otherwise.
"""

import io
import json
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
TESTCASE = REPO / "testcase"
MATRIX = TESTCASE / "SUPPORT-MATRIX.md"


def measured_categories():
    """The categories that have a RESULT.json -- the harness wrote it, so they were measured."""
    names = []
    for path in sorted(TESTCASE.iterdir()):
        if path.is_dir() and (path / "RESULT.json").is_file():
            names.append(path.name)
    return names


def main():
    if not MATRIX.is_file():
        print("FAIL: %s does not exist" % MATRIX.relative_to(REPO))
        return 1

    matrix = MATRIX.read_text(encoding="utf-8")
    # The summary table rows are the ones that carry the counts.
    rows = re.findall(r"^\|\s*([0-9A-Za-z\-]+)\s*\|\s*TP\s", matrix, re.MULTILINE)
    listed = set(rows)

    categories = measured_categories()
    if not categories:
        print("FAIL: no measured categories found")
        return 1

    missing = [c for c in categories if c not in listed]
    if missing:
        print("FAIL: the support matrix does not cover these measured categories:")
        for name in missing:
            print("  - %s" % name)
        print("Regenerate it: python scripts/write_support_matrix.py")
        return 1

    # A category that is NOT measured must not be listed as if it were. An extra row means the
    # matrix claims a measurement that does not exist.
    extra = [c for c in sorted(listed) if c not in categories]
    if extra:
        print("FAIL: the support matrix lists categories with no RESULT.json:")
        for name in extra:
            print("  - %s" % name)
        return 1

    # The counts in the matrix must match the measurements they claim to report. A row that
    # says "TP 5/5" while RESULT.json says something else is a drifted report.
    for name in categories:
        result = json.loads((TESTCASE / name / "RESULT.json").read_text(encoding="utf-8"))
        summary = result["summary"]
        row = next(r for r in matrix.splitlines() if r.startswith("| %s |" % name))
        cells = [c.strip() for c in row.strip().strip("|").split("|")]
        want_tp = "TP %d/%d" % (summary["tp_supported"], summary["tp_total"])
        want_tn = "TN %d/%d" % (summary["tn_supported"], summary["tn_total"])
        if cells[1] != want_tp or not cells[2].startswith(want_tn):
            print("FAIL: %s row drifted from its measurement" % name)
            print("  matrix says: %s | %s" % (cells[1], cells[2]))
            print("  RESULT.json says: %s | %s" % (want_tp, want_tn))
            return 1

    print("OK: the support matrix covers all %d measured categories, and every row "
          "matches its measurement." % len(categories))
    return 0


if __name__ == "__main__":
    sys.exit(main())
