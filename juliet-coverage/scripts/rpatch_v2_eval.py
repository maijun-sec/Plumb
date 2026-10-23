"""
Phase 10: R-PATCH v2 回归验证

策略:
  1. 用 R-PATCH v2 JSON 元数据 + alias 映射
  2. 把 R759-D 时期的 ndjson 文件重新归一化 rule_id（alias → canonical）
  3. 跑 eval_findings_strict.py 对比 R759-D pre-alias vs R-PATCH v2 post-alias
  4. 如果 TP/FP/Recall 数字相同或更好 = 通过

不需要重跑 bulk scan！通过 alias 归一化层重放历史 findings。
"""
from __future__ import annotations
import csv
import json
import re
from collections import defaultdict
from pathlib import Path

# Load alias map from CSV migration
MIGRATION_CSV = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration.csv")
# Map: old_id → new_id (canonical)
alias_map: dict[str, str] = {}
with MIGRATION_CSV.open(encoding="utf-8") as fh:
    for row in csv.DictReader(fh):
        alias_map[row["old_id"]] = row["new_id"]

# Also load JSON metadata to get the full aliases[] arrays for each canonical id
# This is critical because the migration CSV has 1:1 old→new, but the JSON
# has many:1 (multiple aliases → one canonical)
JSON_RULES = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-rules\src\main\resources\rules")
json_aliases: dict[str, str] = {}  # old_id → new_id
for jf in JSON_RULES.rglob("*.json"):
    try:
        text = jf.read_text(encoding="utf-8", errors="replace")
    except Exception:
        continue
    m_id = re.search(r'"id"\s*:\s*"([^"]+)"', text)
    if not m_id:
        continue
    canonical = m_id.group(1)
    for m_alias in re.finditer(r'"aliases"\s*:\s*\[([^\]]+)\]', text):
        for a in re.findall(r'"([^"]+)"', m_alias.group(1)):
            if a != canonical:
                json_aliases[a] = canonical

# Merge: prefer JSON aliases (more comprehensive)
for k, v in alias_map.items():
    json_aliases.setdefault(k, v)

print(f"Alias map: {len(json_aliases)} entries")

# Now re-normalize R759-D ndjson files
INPUT_DIR = Path(r"D:\tmp\plumb_test\r759d_eval")
OUT_DIR = Path(r"D:\tmp\plumb_test\rpatch_v2_eval")
OUT_DIR.mkdir(parents=True, exist_ok=True)

# Copy flat.ndjson → post-alias flat.ndjson
# For non-flat (per-file .ndjson), copy and rewrite rule_id field

stats = {"total": 0, "rewritten": 0, "kept_canonical": 0, "unknown": 0}
unknown_ids: dict[str, int] = defaultdict(int)

for nf in INPUT_DIR.glob("CWE*.ndjson"):
    out_nf = OUT_DIR / nf.name
    is_flat = "flat" in nf.name
    with nf.open(encoding="utf-8", errors="replace") as inf, out_nf.open("w", encoding="utf-8") as outf:
        for line in inf:
            stats["total"] += 1
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                outf.write(line)
                continue
            old_id = obj.get("rule_id", "")
            if old_id in json_aliases:
                new_id = json_aliases[old_id]
                if new_id != old_id:
                    obj["rule_id"] = new_id
                    stats["rewritten"] += 1
                else:
                    stats["kept_canonical"] += 1
            else:
                stats["unknown"] += 1
                unknown_ids[old_id] += 1
            outf.write(json.dumps(obj, ensure_ascii=False) + "\n")

print(f"Rewrote {stats['rewritten']} / {stats['total']} findings")
print(f"  Already canonical: {stats['kept_canonical']}")
print(f"  Unknown ids (no alias): {stats['unknown']}")
print(f"\nTop 20 unknown ids (will need manual review):")
for rid, n in sorted(unknown_ids.items(), key=lambda x: -x[1])[:20]:
    print(f"  {n:6d}  {rid}")