import csv
import re
import sys

cwe_filter = sys.argv[1] if len(sys.argv) > 1 else "CWE191"
multi_re = re.compile(r"[;,]")
cross_re = re.compile(r"_(\d{2,3})[a-z]\.java$")

pos_files = set()
neg_files = set()
variant_counter = {}

with open(r"D:\tmp\plumb_test\plumb_testcases_detail.csv") as f:
    r = csv.DictReader(f)
    for row in r:
        if row["CWE_ID"] != cwe_filter:
            continue
        f = row["FILES"]
        if multi_re.search(f) or cross_re.search(f):
            continue
        variant = row["FLOW_VARIANT"]
        is_pos = row["POSITIVE"] == "True"
        is_neg = row["NEGATIVE"] == "True"
        if is_pos:
            pos_files.add(f)
            variant_counter[variant] = variant_counter.get(variant, 0) + 1
        if is_neg:
            neg_files.add(f)

print(f"{cwe_filter} Pos files (single-file): {len(pos_files)}")
print(f"{cwe_filter} Neg files: {len(neg_files)}")
print("Variants in Pos:")
for v in sorted(variant_counter.keys()):
    print(f"  _v{v}: {variant_counter[v]}")