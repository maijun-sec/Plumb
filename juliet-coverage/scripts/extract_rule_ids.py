"""
Phase 2: 抽取所有 rule id 来源，输出一个合并的 unique-rule-ids.csv
"""
from __future__ import annotations
import csv
import re
from pathlib import Path
from collections import defaultdict

# 来源 1: yaml 配置
yaml_dir = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\configs")
yaml_ids: dict[str, list[str]] = defaultdict(list)
for yf in yaml_dir.glob("*.yaml"):
    for m in re.finditer(r"^\s*-\s+(\S+)", yf.read_text(encoding="utf-8"), re.MULTILINE):
        yaml_ids[m.group(1)].append(yf.name)

# 来源 2: finding ndjson (取样 — 限制 10000 条/文件)
eval_dir = Path(r"D:\tmp\plumb_test\r759e_eval")
finding_ids: dict[str, int] = defaultdict(int)
nd_files = [f for f in eval_dir.glob("*.ndjson") if "flat" not in f.name]
print(f"Scanning {len(nd_files)} ndjson files...")
for i, nf in enumerate(nd_files):
    if i % 20 == 0:
        print(f"  {i}/{len(nd_files)}")
    with nf.open(encoding="utf-8", errors="replace") as fh:
        for j, line in enumerate(fh):
            if j > 10000:  # sample limit per file
                break
            m = re.search(r'"rule_id"\s*:\s*"([^"]+)"', line)
            if m:
                finding_ids[m.group(1)] += 1
print(f"Done. Unique finding rule ids: {len(finding_ids)}")

# 来源 3: RuleRegistry 的 withBuiltinDefaults (Java 源码)
reg_path = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-rules\src\main\java\org\plumb\rules\RuleRegistry.java")
reg_ids: dict[str, str] = {}
for m in re.finditer(r'public static final String\s+\w+\s*=\s*"([^"]+)"', reg_path.read_text(encoding="utf-8")):
    reg_ids[m.group(1)] = "RuleRegistry"

# 来源 4: 散落的 DFA checker 内部的 RULE_ID 字符串
dfa_dir = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\checkers")
dfa_hardcoded: dict[str, str] = {}
for jf in dfa_dir.rglob("*.java"):
    text = jf.read_text(encoding="utf-8", errors="replace")
    # RULE_ID 常量
    for m in re.finditer(r'private\s+static\s+final\s+String\s+RULE_ID\s*=\s*"([^"]+)"', text):
        dfa_hardcoded[m.group(1)] = f"hardcoded:{jf.name}"
    # 构造器里的 "plumb.taint.xxx" 字符串
    for m in re.finditer(r'new\s+TaintRule\("([^"]+)"', text):
        dfa_hardcoded.setdefault(m.group(1), f"taint-rule:{jf.name}")
    # Finding.violation 第二参
    for m in re.finditer(r'Finding\.violation\("([^"]+)","([^"]+)"', text):
        dfa_hardcoded.setdefault(m.group(2), f"violation:{jf.name}")
    # Finding.violation 第一参（ruleName / class name）
    for m in re.finditer(r'Finding\.violation\("([^"]+)"', text):
        if "." not in m.group(1):
            dfa_hardcoded.setdefault(m.group(1), f"name:{jf.name}")

# 来源 5: plumb-style AST checker
style_dir = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-style\src\main\java\org\plumb\style")
ast_ids: dict[str, str] = {}
for jf in style_dir.rglob("*.java"):
    if jf.name == "AstChecker.java" or jf.name == "StyleCheck.java" or jf.name == "StyleContext.java":
        continue
    text = jf.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r'(?:name|ruleName|rule_id|getRuleName)\s*[=:]\s*"([^"]+)"', text):
        ast_ids.setdefault(m.group(1), f"ast:{jf.name}")
    # Pattern used in violation
    for m in re.finditer(r'(?:report|addFinding|addReport)\s*\([^,]+,\s*"([^"]+)"', text):
        ast_ids.setdefault(m.group(1), f"ast-report:{jf.name}")

# Merge all sources
all_ids: dict[str, dict] = {}
def add(id_: str, source: str):
    if not id_:
        return
    all_ids.setdefault(id_, {"id": id_, "sources": set()})
    all_ids[id_]["sources"].add(source)

for id_, files in yaml_ids.items():
    add(id_, f"yaml[{files[0]}]")
for id_, cnt in finding_ids.items():
    add(id_, f"finding[n={cnt}]")
for id_, src in reg_ids.items():
    add(id_, src)
for id_, src in dfa_hardcoded.items():
    add(id_, src)
for id_, src in ast_ids.items():
    add(id_, src)

# Write csv
out_csv = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\rule_ids_inventory.csv")
with out_csv.open("w", encoding="utf-8", newline="") as fh:
    wr = csv.writer(fh)
    wr.writerow(["rule_id", "source_count", "sources"])
    for id_, info in sorted(all_ids.items()):
        wr.writerow([id_, len(info["sources"]), "; ".join(sorted(info["sources"]))])

print(f"\nWrote {len(all_ids)} unique rule ids to {out_csv}")
print("\nTop 30 by source count:")
for id_, info in sorted(all_ids.items(), key=lambda x: -len(x[1]["sources"]))[:30]:
    print(f"  {len(info['sources']):3d}  {id_:<60}  {sorted(info['sources'])[0]}")
