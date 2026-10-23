"""Classify CWE190/191/197/606 missed files by variant."""
import csv
from pathlib import Path
import re

CSV_PATH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
JULIET = Path(r"D:\work\tmp\Juliet_Java\src\testcases")

target_cwes = ['CWE190', 'CWE191', 'CWE197', 'CWE606']
print("[*] loading CSV ...", flush=True)
with CSV_PATH.open("r", encoding="utf-8") as f:
    rd = csv.DictReader(f)
    rows = list(rd)

# Group rows by CWE and variant
buckets = {cwe: [] for cwe in target_cwes}
for r in rows:
    if ";" in r["FILES"] or "," in r["FILES"]:
        continue
    m = re.search(r"_(\d{2,3})[a-z]\.java$", r["FILES"])
    if m and int(m.group(1)) >= 22:
        continue  # skip cross-file variants
    if r["CWE_ID"] in buckets:
        buckets[r["CWE_ID"]].append(r)

for cwe in target_cwes:
    rs = buckets[cwe]
    pos_files = [r for r in rs if r["POSITIVE"] == "True"]
    print(f"\n=== {cwe} ({len(pos_files)} positive single-file variants) ===")
    # Count by source pattern + flow variant
    patterns = {}
    for r in pos_files:
        bn = Path(r["FILES"]).name
        # Extract source pattern (between __ and _NN)
        m = re.search(r"__([A-Za-z_]+?)_(?:[A-Za-z]+)?_\d+\.java", bn)
        if m:
            pat = m.group(1)
        else:
            pat = "unknown"
        # Extract sink (last word before _NN)
        m2 = re.search(r"_([A-Za-z]+)_\d+\.java", bn)
        sink = m2.group(1) if m2 else "?"
        key = f"{pat}→{sink}"
        patterns[key] = patterns.get(key, 0) + 1
    for k, v in sorted(patterns.items(), key=lambda x: -x[1])[:15]:
        print(f"  {v:5d}  {k}")