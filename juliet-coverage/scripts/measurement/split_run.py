#!/usr/bin/env python3
"""Split the 112 Juliet CWEs between two workers, balanced by file count.

The split is LONGEST-PROCESSING-TIME-FIRST: the biggest CWE goes to the
worker that has less work so far, then the next biggest, and so on. A
plain "first half / second half" split leaves one worker finishing early
and idling while the other still has the three biggest corpora left.

The file counts come from Juliet's own CSV, which is the only place the
corpus size is stated once for every file.

    python split_run.py
"""
import csv
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
CSV = pathlib.Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")

counts: dict[str, int] = {}
with CSV.open(newline="", encoding="utf-8", errors="replace") as fh:
    for row in csv.DictReader(fh):
        cwe = (row.get("CWE_ID") or "").replace("CWE", "").strip()
        if not cwe:
            continue
        try:
            counts[cwe] = counts.get(cwe, 0) + int(row.get("FILES_COUNT") or 0)
        except ValueError:
            continue

import sys

sys.path.insert(0, str(HERE))
import measure  # noqa: E402

dirs = measure.cwe_dirs()
missing = [c for c in dirs if c not in counts]
if missing:
    print("WARNING: no file count in the CSV for: %s" % " ".join(missing))

# Longest first, so the big corpora are placed before the small ones.
ordered = sorted(dirs, key=lambda c: (-counts.get(c, 0), int(c)))

load_a = load_b = 0
a: list[str] = []
b: list[str] = []
for cwe in ordered:
    n = counts.get(cwe, 0)
    if load_a <= load_b:
        a.append(cwe)
        load_a += n
    else:
        b.append(cwe)
        load_b += n

a.sort(key=int)
b.sort(key=int)

print("WORKER A -- %d CWEs, %d files" % (len(a), load_a))
print(" ".join(a))
print()
print("WORKER B -- %d CWEs, %d files" % (len(b), load_b))
print(" ".join(b))
print()
print("TOTAL -- %d CWEs, %d files" % (len(a) + len(b), load_a + load_b))

(HERE / "run_a_cwes.txt").write_text(" ".join(a), encoding="utf-8")
(HERE / "run_b_cwes.txt").write_text(" ".join(b), encoding="utf-8")
