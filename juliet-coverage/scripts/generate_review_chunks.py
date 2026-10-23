"""
Phase 3b: 把 179 个 pl-NNNN-* 规则的 review 任务切成 30 个一组，
方便人工审核（拆 ast→style/concurrency/boundary/info/naming）
"""
import csv
from collections import defaultdict
from pathlib import Path

IN_MIGRATION = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration.csv")
OUT_REVIEW_CHUNKS = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_review_chunks.csv")

rows = list(csv.DictReader(IN_MIGRATION.open(encoding="utf-8")))

# Group by detect_reason and current domain
groups = defaultdict(list)
for r in rows:
    key = (r["domain"], r["detect_reason"])
    groups[key].append(r)

# Output one CSV with all rows, sorted by (domain, old_id)
# but also write a markdown report with group-by-group summary
sorted_rows = sorted(rows, key=lambda r: (r["domain"], r["old_id"]))

# Write the chunks file
with OUT_REVIEW_CHUNKS.open("w", encoding="utf-8", newline="") as fh:
    wr = csv.writer(fh)
    wr.writerow(["chunk", "domain", "old_id", "new_id", "detect_reason", "review_status"])
    chunk = 0
    cur_count = 0
    for i, r in enumerate(sorted_rows):
        if cur_count >= 30:
            chunk += 1
            cur_count = 0
        if cur_count == 0:
            chunk += 1
        cur_count += 1
        wr.writerow([
            f"chunk_{chunk:03d}", r["domain"], r["old_id"], r["new_id"],
            r["detect_reason"], "TODO"
        ])

# Markdown summary
md_path = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration_summary.md")
with md_path.open("w", encoding="utf-8") as fh:
    fh.write("# Rule ID Migration Summary\n\n")
    fh.write(f"Total migrations: **{len(rows)}**\n")
    fh.write(f"Collisions (multiple old → same new): **{sum(1 for r in rows if r['collision'] == 'YES')}**\n\n")

    fh.write("## Distribution by target domain\n\n")
    by_domain = defaultdict(int)
    for r in rows:
        by_domain[r["domain"]] += 1
    for d, c in sorted(by_domain.items(), key=lambda x: -x[1]):
        fh.write(f"- `{d}`: {c}\n")
    fh.write("\n")

    fh.write("## Distribution by detect_reason\n\n")
    by_reason = defaultdict(int)
    for r in rows:
        by_reason[r["detect_reason"]] += 1
    for r2, c in sorted(by_reason.items(), key=lambda x: -x[1]):
        fh.write(f"- {r2}: {c}\n")
    fh.write("\n")

    fh.write("## Review priority (ast domain has 569 — likely over-classified)\n\n")
    fh.write("Rules that ended up in `plumb.ast.*` should be reviewed for potential re-classification to:\n")
    fh.write("- `plumb.concurrency.*` (multi-threading: deadlock, race, lock)\n")
    fh.write("- `plumb.boundary.*` (numeric: overflow, divide-by-zero, float)\n")
    fh.write("- `plumb.crypto.*` (encryption / keys)\n")
    fh.write("- `plumb.info.*` (logging / error message exposure)\n")
    fh.write("- `plumb.style.*` (pure code style: naming, formatting)\n")
    fh.write("- `plumb.resource.*` (resource leaks)\n\n")
    fh.write("Use `rule_id_review_chunks.csv` for batched review (30 rules per chunk).\n\n")

    fh.write("## Collisions (old rules that map to same new id)\n\n")
    collisions = defaultdict(list)
    for r in rows:
        if r["collision"] == "YES":
            collisions[r["new_id"]].append(r["old_id"])
    for nid, olds in sorted(collisions.items(), key=lambda x: -len(x[1]))[:20]:
        fh.write(f"- `{nid}` ← {len(olds)} aliases: `{'`, `'.join(olds[:5])}`{' ...' if len(olds) > 5 else ''}\n")
    fh.write(f"\nTotal collided new_ids: {len(collisions)} (out of {len(set(r['new_id'] for r in rows))} unique new_ids)\n")

print(f"Wrote {OUT_REVIEW_CHUNKS}")
print(f"Wrote {md_path}")
print(f"Total: {len(rows)} migrations, {len(collisions)} collision groups, {len(by_domain)} domains")