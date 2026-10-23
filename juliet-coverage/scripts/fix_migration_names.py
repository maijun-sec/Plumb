"""
Phase 8b: 修 migration CSV 里所有以数字开头的 new_id
同时把已经生成的非法 JSON 文件移到 trash.
"""
import re
import csv
from pathlib import Path
import shutil

MIGRATION_CSV = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_id_migration.csv")
RULES_DIR = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-rules\src\main\resources\rules")
TRASH = Path(r"D:\work\workspace\idea\engine\Plumb\_cleanup_trash")

NAME_PATTERN = re.compile(r'^[a-z][a-z0-9-]{0,63}$')

fixed = []
with MIGRATION_CSV.open(encoding="utf-8") as fh:
    rows = list(csv.DictReader(fh))

for row in rows:
    new_id = row["new_id"]
    parts = new_id.split(".")
    if len(parts) == 3:
        domain, name = parts[1], parts[2]
        # Normalize: collapse dashes, strip leading digits
        new_name = re.sub(r'-+', '-', name).strip('-')
        new_name = re.sub(r'^\d+-', '', new_name)
        if new_name != name:
            row["new_id"] = f"plumb.{domain}.{new_name}"
            fixed.append((row["old_id"], new_id, row["new_id"]))

with MIGRATION_CSV.open("w", encoding="utf-8", newline="") as fh:
    wr = csv.DictWriter(fh, fieldnames=["old_id", "new_id", "domain", "detect_reason", "source_count",
                                          "in_yaml", "in_finding", "in_registry", "in_dfa_hardcoded", "in_ast", "collision"])
    wr.writeheader()
    for r in rows:
        wr.writerow(r)

print(f"Fixed {len(fixed)} entries")

# Now scan existing JSON files and trash any whose id starts with a digit
trashed = 0
for jf in RULES_DIR.rglob("*.json"):
    try:
        text = jf.read_text(encoding="utf-8")
    except Exception:
        continue
    m = re.search(r'"id"\s*:\s*"plumb\.([a-z]+)\.(\d[^"]*)"', text)
    if m:
        # Move to trash and let generate_json_metadata regenerate
        ts = "2026-10-07"
        dest = TRASH / f"{ts}_{jf.name}"
        if not dest.exists():
            shutil.move(str(jf), str(dest))
            trashed += 1
print(f"Moved {trashed} illegal-id JSON files to trash")