"""
Phase 10 fast check: 对比 r759d_eval vs rpatch_v2_eval 的 per-rule TP/FP 数字
（用 alias map 归一化 R759-D 的 rule_id 后再比较）
"""
import csv
import json
import re
from collections import defaultdict
from pathlib import Path

MIGRATION_CSV = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration.csv")
alias_map: dict[str, str] = {}
with MIGRATION_CSV.open(encoding="utf-8") as fh:
    for row in csv.DictReader(fh):
        alias_map[row["old_id"]] = row["new_id"]

# Also pull from JSON
JSON_RULES = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-rules\src\main\resources\rules")
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
                alias_map.setdefault(a, canonical)
print(f"Alias map: {len(alias_map)} entries")

# Process: walk r759d_eval/flat.ndjson files; per-rule aggregate TP/FP/etc.
# Load ground truth
GROUND_TRUTH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
# Cached testcases: file → cwe_id mapping (for cross-checking CWE dir alignment)
# The CSV has column "FILES" containing "; "-separated file list and
# "CWE_ID" (e.g. "90"). We explode into a (file_basename, cwe_id) map.
file_cwe: dict[str, str] = {}
import csv
with GROUND_TRUTH.open(encoding="utf-8", errors="replace") as fh:
    for row in csv.DictReader(fh):
        cwe = row.get("CWE_ID", "")
        if not cwe:
            continue
        files_str = row.get("FILES", "")
        for f in files_str.split(";"):
            f = f.strip().replace("\\", "/")
            if f:
                file_cwe[f] = cwe
                # also index by basename for fuzzy match
                base = f.split("/")[-1]
                if base and not file_cwe.get(base):
                    file_cwe[base] = cwe
print(f"Ground truth file→cwe: {len(file_cwe)} entries")

# Per-rule aggregation
def is_unknown_path(path: str) -> bool:
    if not path or path == "unknown":
        return True
    base = path.replace("\\", "/").split("/")[-1]
    if base in ("Main.java", "ServletMain.java"):
        return True
    if "testcasesupport/" in path.replace("\\", "/"):
        return True
    return False

def file_cwe_from_path(path: str) -> str | None:
    if not path or is_unknown_path(path):
        return None
    rel = path.replace("\\", "/")
    for f, c in file_cwe.items():
        if rel.endswith(f):
            return c
    return None

def normalize(rid: str) -> str:
    return alias_map.get(rid, rid)

# Aggregate per-rule TP/FP from R759-D (raw) and R-PATCH v2 (rewritten)
def aggregate(ndjson_dir: Path, label: str) -> dict:
    """Returns {(rule_id, cwe): (tp, fp, n_findings)}"""
    out: dict[tuple[str, str], tuple[int, int, int]] = {}
    for nf in ndjson_dir.glob("CWE*.ndjson"):
        with nf.open(encoding="utf-8", errors="replace") as fh:
            for line in fh:
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                rid = obj.get("rule_id", "")
                cwe = obj.get("cwe", [""])[0] if obj.get("cwe") else ""
                # extract cwe number
                cwe_num = cwe.replace("CWE-", "") if cwe.startswith("CWE-") else ""
                if label == "post":
                    rid = normalize(rid)
                key = (rid, cwe_num)
                if key not in out:
                    out[key] = [0, 0, 0]
                out[key][2] += 1
    # convert lists to tuples
    return {k: tuple(v) for k, v in out.items()}

print("\nAggregating R759-D pre-alias...")
r759d_data = aggregate(Path(r"D:\tmp\plumb_test\r759d_eval"), "pre")
print(f"  R759-D: {len(r759d_data)} unique (rule, cwe) buckets")
print("\nAggregating R-PATCH v2 post-alias...")
rpv2_data = aggregate(Path(r"D:\tmp\plumb_test\rpatch_v2_eval"), "post")
print(f"  R-PATCH v2: {len(rpv2_data)} unique (rule, cwe) buckets")

# Per-rule delta
all_rules: set[str] = set(k[0] for k in r759d_data.keys()) | set(k[0] for k in rpv2_data.keys())
print(f"\n{'-'*80}")
print(f"Per-rule delta (R759-D pre vs R-PATCH v2 post):")
print(f"{'rule_id':<55} {'R759-D finds':>12} {'RPATCH v2 finds':>15} {'delta':>8}")
print(f"{'-'*80}")

gains: list[tuple[str, int, int, int]] = []
losses: list[tuple[str, int, int, int]] = []
stable_count = 0
for rid in sorted(all_rules):
    n_pre = sum(v[2] for k, v in r759d_data.items() if k[0] == rid)
    n_post = sum(v[2] for k, v in rpv2_data.items() if k[0] == rid)
    if n_post > n_pre:
        gains.append((rid, n_pre, n_post, n_post - n_pre))
    elif n_post < n_pre:
        losses.append((rid, n_pre, n_post, n_pre - n_post))
    else:
        stable_count += 1

print(f"\nGains ({len(gains)}):")
for rid, pre, post, delta in sorted(gains, key=lambda x: -x[3])[:15]:
    print(f"  +{delta:5d}  {rid}")
print(f"\nLosses ({len(losses)}):")
for rid, pre, post, delta in sorted(losses, key=lambda x: -x[3])[:15]:
    print(f"  -{delta:5d}  {rid}")
print(f"\nStable (no change): {stable_count}")
print(f"Total gains: {sum(g[3] for g in gains)}")
print(f"Total losses: {sum(l[3] for l in losses)}")