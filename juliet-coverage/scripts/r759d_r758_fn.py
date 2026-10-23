import csv
import re
import json
import sys

cwe_filter = sys.argv[1] if len(sys.argv) > 1 else "CWE191"
multi_re = re.compile(r"[;,]")
cross_re = re.compile(r"_(\d{2,3})[a-z]\.java$")

pos_files = set()

with open(r"D:\tmp\plumb_test\plumb_testcases_detail.csv") as f:
    r = csv.DictReader(f)
    for row in r:
        if row["CWE_ID"] != cwe_filter:
            continue
        f = row["FILES"]
        if multi_re.search(f) or cross_re.search(f):
            continue
        if row["POSITIVE"] == "True":
            pos_files.add(f)

print(f"Pos files: {len(pos_files)}")

# Load R758 findings, filter CWE-191
hits_by_file = {}
fn_total = pos_files.__len__()  # initialize
for sub in ["s01","s02","s03","s04","s05"]:
    p = fr"D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk\CWE191.{sub}.ndjson"
    try:
        with open(p, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                if '"CWE-191"' not in line and 'CWE-191' not in line:
                    continue
                m = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                if not m:
                    continue
                bn = m.group(1).rsplit("\\", 1)[-1]
                hits_by_file[bn] = hits_by_file.get(bn, 0) + 1
    except FileNotFoundError:
        pass

print(f"R758 hits: {len(hits_by_file)} files")

# FN = pos_files not in hits_by_file
fn_files = [f for f in pos_files if f not in hits_by_file]
print(f"FN (file-level): {len(fn_files)}")

from collections import Counter
v_counter = Counter()
for f in fn_files:
    m = re.match(r'CWE191_Integer_Underflow__[a-z]+_[a-zA-Z_]+_(\d{2})[a-z]?\.java', f)
    if m:
        v_counter[m.group(1)] += 1
    else:
        v_counter['other'] += 1

print("FN variants:")
for v, c in v_counter.most_common():
    print(f"  _v{v}: {c}")

print("\nFN file samples by variant:")
for v in sorted(v_counter.keys()):
    sample = [f for f in fn_files if re.match(r'CWE191_Integer_Underflow__[a-z]+_[a-zA-Z_]+_' + v + r'[a-z]?\.java', f)]
    print(f"\n_v{v} ({v_counter[v]} files):")
    for f in sample[:3]:
        print(f"  {f}")