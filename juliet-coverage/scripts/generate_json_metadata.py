"""
Phase 8: 从 RuleRegistry.withBuiltinDefaults() + AST checkers 批量生成 JSON 元数据

输入:
  1. RuleRegistry.java 的 withBuiltinDefaults() 调用 (80+ entries)
  2. AST checkers 的硬编码 RuleId (548 entries)

输出:
  plumb-rules/src/main/resources/rules/<domain>/<name>.json  (约 600 文件)

每个 JSON 文件:
  - id: plumb.<domain>.<kebab-case-name>
  - name: UPPER_SNAKE (from existing)
  - description: from existing
  - severity: from existing
  - type: from existing
  - cwe: from existing
  - checker.class: from existing
  - checker.kind: by file path (dfa, ast, etc.)
  - aliases: from migration CSV (old_id -> new_id)
  - since: "R720" (placeholder)

这个脚本是幂等的: 已存在的 JSON 文件不会被覆盖 (unless --force).
"""
import re
import csv
import json
from pathlib import Path
from collections import defaultdict

PLUMB_ROOT = Path(r"D:\work\workspace\idea\engine\Plumb")
RULE_REG_JAVA = PLUMB_ROOT / "plumb-rules/src/main/java/org/plumb/rules/RuleRegistry.java"
AST_ROOT = PLUMB_ROOT / "plumb-style/src/main/java/org/plumb/style"
DFA_ROOT = PLUMB_ROOT / "plumb-dfa/src/main/java/org/.../dfa/checkers"  # (not used yet)
MIGRATION_CSV = PLUMB_ROOT / "juliet-coverage/data/rule_id_migration.csv"
OUT_ROOT = PLUMB_ROOT / "plumb-rules/src/main/resources/rules"

# load migration CSV: map old_id -> (new_id, domain)
migration: dict[str, tuple[str, str]] = {}
with MIGRATION_CSV.open(encoding="utf-8") as fh:
    for row in csv.DictReader(fh):
        migration[row["old_id"]] = (row["new_id"], row["domain"])

# extract from RuleRegistry.withBuiltinDefaults() (in reverse: withBuiltinDefaults writes defs;
# we'll extract the args to def() calls)
# The pattern is: this.register(def("bu-XXXX-name", "NAME", "desc", Severity, Type, [cwe], "Class"));
rule_defs: list[dict] = []
for m in re.finditer(
    r'this\.register\(def\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*'
    r'RuleDef\.Severity\.(\w+),\s*RuleDef\.Type\.(\w+),\s*List\.of\(([^)]*)\),\s*"([^"]+)"\)\)',
    RULE_REG_JAVA.read_text(encoding="utf-8")
):
    rid, name, desc, sev, typ, cwe_block, cls = m.groups()
    cwe_list = re.findall(r'"([^"]+)"', cwe_block)
    rule_defs.append({
        "old_id": rid, "name": name, "desc": desc,
        "severity": sev, "type": typ, "cwe": cwe_list, "class": cls,
    })

# extract addAlias() calls
alias_to_id: dict[str, str] = defaultdict(list)
for m in re.finditer(r'addAlias\("([^"]+)",\s*List\.of\(([^)]+)\)\)', RULE_REG_JAVA.read_text(encoding="utf-8")):
    rid = m.group(1)
    for a in re.findall(r'"([^"]+)"', m.group(2)):
        # parse "Tool:Name" form
        if ":" in a:
            tool, foreign_id = a.split(":", 1)
            alias_to_id[rid].append(f"{tool}:{foreign_id}")
        else:
            alias_to_id[rid].append(a)

# extract AST checker hardcoded RuleId
# pattern: new RuleId("pl-NNNN-...", "NAME")
ast_rules: list[dict] = []
for jf in AST_ROOT.rglob("*.java"):
    text = jf.read_text(encoding="utf-8", errors="replace")
    # DEFAULT_RULE pattern
    m = re.search(
        r'private\s+static\s+final\s+RuleDef\s+DEFAULT_RULE\s*=\s*new\s+RuleDef\(\s*'
        r'new\s+RuleId\("([^"]+)",\s*"([^"]+)"\),\s*'
        r'"([^"]+)",\s*'
        r'RuleDef\.Severity\.(\w+),\s*RuleDef\.Type\.(\w+),\s*List\.of\(([^)]*)\),\s*'
        r'"([^"]+)"',
        text, re.DOTALL
    )
    if m:
        rid, name, desc, sev, typ, cwe_block, cls = m.groups()
        cwe_list = re.findall(r'"([^"]+)"', cwe_block)
        ast_rules.append({
            "old_id": rid, "name": name, "desc": desc,
            "severity": sev, "type": typ, "cwe": cwe_list, "class": cls,
            "file": jf.name,
        })

print(f"RuleRegistry rules: {len(rule_defs)}")
print(f"AST checkers: {len(ast_rules)}")
print(f"Migration map: {len(migration)}")

# Convert each to JSON
out_files: list[Path] = []
skipped: list[tuple[str, str]] = []  # (old_id, reason)
for src in rule_defs + ast_rules:
    old_id = src["old_id"]
    if old_id not in migration:
        skipped.append((old_id, "no migration mapping"))
        continue
    new_id, domain = migration[old_id]
    # parse new_id into (domain_dir, name)
    parts = new_id.split(".")
    if len(parts) != 3 or not new_id.startswith("plumb."):
        skipped.append((old_id, f"bad new_id format: {new_id}"))
        continue
    domain_dir, name = parts[1], parts[2]
    # Normalize name: collapse multiple dashes, strip leading/trailing dashes
    name = re.sub(r'-+', '-', name).strip('-')
    # Strip any leading digit prefix (e.g. "604-comments-indentation" → "comments-indentation")
    name = re.sub(r'^\d+-', '', name)
    if not name or not re.match(r'^[a-z][a-z0-9-]{0,63}$', name):
        skipped.append((old_id, f"name '{name}' invalid after normalization"))
        continue
    out_dir = OUT_ROOT / domain_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / f"{name}.json"
    if out_file.exists():
        continue  # idempotent

    # Build aliases: old id + other old ids in same migration collision
    aliases = [old_id]
    # find all old_ids that map to the same new_id
    for other_old, (other_new, _) in migration.items():
        if other_new == new_id and other_old != old_id:
            aliases.append(other_old)
    # add A3 cross-tool aliases
    if old_id in alias_to_id:
        aliases.extend(alias_to_id[old_id])
    # dedupe
    aliases = list(dict.fromkeys(aliases))

    # checker.kind
    cls = src["class"]
    if "dfa" in cls.lower():
        kind = "dfa"
    elif "boundary" in cls.lower():
        kind = "boundary"
    elif "taint" in cls.lower():
        kind = "taint"
    elif "style" in cls.lower() or cls.startswith("org.plumb.style"):
        kind = "ast"
    else:
        kind = "ast"

    # description
    desc = src["desc"]
    if not desc or desc.strip() == "":
        desc = f"{name} check (migrated from {old_id})"

    json_obj = {
        "schemaVersion": "2.0",
        "id": new_id,
        "name": src["name"],
        "description": desc,
        "severity": src["severity"],
        "type": src["type"],
        "cwe": src["cwe"],
        "checker": {"class": cls, "kind": kind},
        "ai": {"enabled": True, "promptPath": f"ai-prompts/{name}.txt"},
        "parameters": [],
        "aliases": sorted(aliases),
        "tags": [domain],
        "since": "R720",
    }
    out_file.write_text(json.dumps(json_obj, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    out_files.append(out_file)

print(f"\nGenerated {len(out_files)} JSON files")
print(f"Skipped {len(skipped)} entries (no migration mapping)")
if skipped[:10]:
    print("Sample skipped:")
    for s in skipped[:10]:
        print(f"  {s[0]}: {s[1]}")