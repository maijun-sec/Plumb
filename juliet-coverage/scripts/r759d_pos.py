import csv
import sys
from collections import Counter

cwe_filter = sys.argv[1] if len(sys.argv) > 1 else "CWE191"

pos_files = set()
all_files = set()
cnt_total = 0
cnt_pos = 0
variant_counter = Counter()
sink_counter = Counter()

with open(r"D:\tmp\plumb_test\plumb_testcases_detail.csv") as f:
    r = csv.DictReader(f)
    for row in r:
        if row["CWE_ID"] != cwe_filter:
            continue
        cnt_total += 1
        all_files.add(row["FILES"])
        if row["POSITIVE"] == "True":
            cnt_pos += 1
            pos_files.add(row["FILES"])
            variant_counter[row["FLOW_VARIANT"]] += 1
            sink_counter[row["BAD_SINK"]] += 1

print(f"{cwe_filter}: total rows={cnt_total}, Pos rows={cnt_pos}, unique Pos files={len(pos_files)}")
print("Variants in Pos:")
for v, c in variant_counter.most_common(40):
    print(f"  _v{v}: {c}")
print("Top bad sinks:")
for s, c in sink_counter.most_common(10):
    print(f"  {s}: {c}")