import re
import sys
import os

cwe_filter = "CWE191"
new_dir = r"D:\work\workspace\idea\engine\Plumb\.aethercode\r759d_bulk"
old_dir = r"D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk"

def load_hits(d, cwe):
    files = {}
    for sub in ["s01","s02","s03","s04","s05"]:
        p = os.path.join(d, f"CWE191.{sub}.ndjson")
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    if "CWE-191" not in line:
                        continue
                    m = re.search(r'"file"\s*:\s*"([^"]+)"', line)
                    if not m:
                        continue
                    bn = m.group(1).rsplit("\\", 1)[-1]
                    files.setdefault(bn, []).append(line)
        except FileNotFoundError:
            pass
    return files

new_hits = load_hits(new_dir, cwe_filter)
old_hits = load_hits(old_dir, cwe_filter)

print(f"R759-D: {len(new_hits)} unique files")
print(f"R758  : {len(old_hits)} unique files")

new_files = set(new_hits.keys())
old_files = set(old_hits.keys())

new_in_old = new_files & old_files
only_new = new_files - old_files
only_old = old_files - new_files
print(f"\nIn both: {len(new_in_old)}")
print(f"Only R759-D (NEW TP): {len(only_new)}")
print(f"Only R758 (regression?): {len(only_old)}")

# Show breakdown by variant for only_new
from collections import Counter
v_new = Counter()
for f in only_new:
    m = re.match(r'CWE191_Integer_Underflow__[a-z]+_([a-z]+_)?(\d{2})[a-z]?\.java', f)
    if m:
        v_new[f"v{m.group(2)}"] += 1
    else:
        v_new["other"] += 1

print(f"\nNEW TP variants:")
for k in sorted(v_new.keys()):
    print(f"  {k}: {v_new[k]}")

print(f"\nSample NEW TP files:")
for f in sorted(only_new)[:10]:
    print(f"  {f}")

print(f"\nFiles ONLY in R758 (regression candidates):")
for f in sorted(only_old)[:10]:
    print(f"  {f}")