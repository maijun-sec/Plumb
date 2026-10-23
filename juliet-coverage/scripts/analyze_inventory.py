"""Phase 2b: 详细分析 rule id 命名风格，给出迁移建议"""
import csv
import re
from pathlib import Path
from collections import defaultdict

csv_path = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_ids_inventory.csv")
rows = list(csv.DictReader(csv_path.open(encoding="utf-8")))

# 详细分类
def classify(rid: str) -> str:
    if "+" in rid or " " in rid:
        return "JUNK"
    if rid.startswith("pl-"):
        return "pl- (PMD-style)"
    if rid.startswith("bu-"):
        return "bu- (SpotBugs-style)"
    if rid.startswith("cov-"):
        return "cov- (Coverity-style)"
    if rid.startswith("plumb."):
        # 三级/四级 点号
        parts = rid.split(".")
        if len(parts) == 3:
            return "plumb.X.Y (3-level dotted)"
        elif len(parts) == 4:
            return "plumb.X.Y.Z (4-level dotted)"
        else:
            return f"plumb.({len(parts)}-level dotted)"
    if rid.startswith("dfa."):
        return "dfa.X-Y (DFA loose-style)"
    if rid.startswith("loose."):
        return "loose.X.Y (loose-class checker)"
    if re.match(r"^[A-Z][A-Z0-9_]+$", rid):
        return "UPPER_SNAKE (PMD name)"
    if re.match(r"^[A-Z][a-zA-Z]+$", rid):
        return "PascalCase (ClassName)"
    if re.match(r"^[a-z][a-z0-9-]+$", rid):
        return "lowercase-kebab (misc)"
    return f"OTHER:{rid[:20]}"

buckets = defaultdict(list)
for r in rows:
    buckets[classify(r["rule_id"])].append(r["rule_id"])

print("=" * 80)
print("Rule ID naming style distribution ({} unique)".format(len(rows)))
print("=" * 80)
for k, v in sorted(buckets.items(), key=lambda x: -len(x[1])):
    print(f"  {len(v):4d}  {k}")
    for x in v[:3]:
        print(f"        {x}")
    if len(v) > 3:
        print(f"        ... +{len(v)-3} more")

# 写第二份 csv：每行 (old_id, new_style, suggested_new_id, classification)
out_path = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_classification.csv")
with out_path.open("w", encoding="utf-8", newline="") as fh:
    wr = csv.writer(fh)
    wr.writerow(["old_id", "current_style", "suggested_new_id_prefix", "notes"])
    for r in sorted(rows, key=lambda x: x["rule_id"]):
        rid = r["rule_id"]
        cls = classify(rid)
        if cls.startswith("JUNK"):
            continue
        # suggested prefix
        if cls.startswith("pl-") or cls.startswith("plumb."):
            new = "plumb.<domain>."
        elif cls.startswith("bu-") or cls.startswith("UPPER_SNAKE"):
            new = "plumb.boundary."  # need to differentiate
        elif cls.startswith("cov-"):
            new = "plumb.ast."
        elif cls.startswith("dfa."):
            new = "plumb.dfa."
        elif cls.startswith("loose."):
            new = "plumb.boundary."
        elif cls.startswith("PascalCase"):
            new = "plumb.ast."
        else:
            new = "plumb.<todo>."
        wr.writerow([rid, cls, new, ""])
print(f"\nWrote {out_path}")