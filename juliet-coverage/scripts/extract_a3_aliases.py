"""
Phase 3c: 抽取 A3 跨工具别名 (PMD / SonarQube / SpotBugs / Coverity)
        这些是 third-party 工具原生的 id，跨工具报告用
"""
import re
import csv
from pathlib import Path

reg_path = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-rules\src\main\java\org\plumb\rules\RuleRegistry.java")
text = reg_path.read_text(encoding="utf-8", errors="replace")

# 抽取 addAlias() 调用
out = []
for m in re.finditer(r'addAlias\("([^"]+)",\s*List\.of\(([^)]+)\)\)', text):
    plumb_id = m.group(1)
    aliases_str = m.group(2)
    aliases = re.findall(r'"([^"]+)"', aliases_str)
    for a in aliases:
        # 解析 "Tool:Name" 形式
        if ":" in a:
            tool, foreign_id = a.split(":", 1)
            out.append({
                "plumb_id": plumb_id,
                "tool": tool,
                "foreign_id": foreign_id,
                "alias_combined": a,
            })
        else:
            out.append({
                "plumb_id": plumb_id,
                "tool": "unknown",
                "foreign_id": a,
                "alias_combined": a,
            })

# 写 csv
out_csv = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\a3_cross_tool_aliases.csv")
with out_csv.open("w", encoding="utf-8", newline="") as fh:
    wr = csv.writer(fh)
    wr.writerow(["plumb_id_old", "tool", "foreign_id", "alias_combined"])
    for r in out:
        wr.writerow([r["plumb_id"], r["tool"], r["foreign_id"], r["alias_combined"]])

# 统计
from collections import Counter
tool_counts = Counter(r["tool"] for r in out)
print(f"Total cross-tool aliases: {len(out)}")
print(f"By tool:")
for tool, c in tool_counts.most_common():
    print(f"  {tool}: {c}")
print(f"\nDistinct plumb ids with cross-tool aliases: {len(set(r['plumb_id'] for r in out))}")
print(f"\nWrote {out_csv}")

# 抽取所有有 alias 的 plumb id 列表（用于 Phase 6 检查：每个 alias 必须能解析到新 id）
plumb_ids_with_aliases = set(r["plumb_id"] for r in out)
out2 = Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\a3_plumb_ids.csv")
out2.write_text("\n".join(sorted(plumb_ids_with_aliases)), encoding="utf-8")
print(f"Wrote {out2} ({len(plumb_ids_with_aliases)} ids)")