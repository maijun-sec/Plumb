import csv, re, os
from collections import Counter

cwe_filter = "CWE191"
multi_re = re.compile(r"[;,]")
cross_re = re.compile(r"_(\d{2,3})[a-z]\.java$")

pos_files = set()
with open(r"D:\tmp\plumb_test\plumb_testcases_detail.csv") as f:
    r = csv.DictReader(f)
    for row in r:
        if row["CWE_ID"] != cwe_filter: continue
        fs = row["FILES"]
        if multi_re.search(fs) or cross_re.search(fs): continue
        if row["POSITIVE"] == "True":
            pos_files.add(fs)
print(f"Pos files: {len(pos_files)}")

new_dir = r"D:\work\workspace\idea\engine\Plumb\.aethercode\r759d_bulk"
old_dir = r"D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk"

def hits(d):
    out = set()
    for sub in ["s01","s02","s03","s04","s05"]:
        p = os.path.join(d, f"CWE191.{sub}.ndjson")
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if "CWE-191" not in line: continue
                    m = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                    if not m: continue
                    out.add(m.group(1).rsplit("\\", 1)[-1])
        except FileNotFoundError: pass
    return out

old_h = hits(old_dir)
new_h = hits(new_dir)

old_fn = pos_files - old_h
new_fn = pos_files - new_h

print(f"\nR758  hits: {len(old_h)}")
print(f"R758  FN: {len(old_fn)}")
print(f"\nR759-D hits: {len(new_h)}")
print(f"R759-D FN: {len(new_fn)}")

# Show R759-D FN
if new_fn:
    print("\nR759-D FN samples:")
    v = Counter()
    for f in new_fn:
        m = re.match(r'CWE191_Integer_Underflow__[a-z]+_[a-zA-Z_]+_(\d{2})[a-z]?\.java', f)
        if m:
            v[m.group(1)] += 1
        else:
            v["other"] += 1
    for k,vv in sorted(v.items()):
        print(f"  _v{k}: {vv}")

# What's fixed
fixed = old_fn & (old_fn - new_fn)
print(f"\nFN fixed by R759-D: {len(fixed)}")
for f in sorted(fixed)[:5]:
    print(f"  + {f}")