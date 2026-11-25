#!/usr/bin/env python3
"""
Generate a flat table (one row per rule) for at-a-glance
lookup. Sibling doc to plumb-style-rules.md; the user can
open this for a single-page view of every rule's
identifier, class, severity, and external equivalents.
"""
import re
import sys
from pathlib import Path

# A gate that dies while printing its result has measured nothing. The
# console's code page is not UTF-8 on every machine, so force the streams
# to UTF-8 and replace what cannot be encoded instead of crashing.
#
# reconfigure() and not a fresh TextIOWrapper: this file is imported as a
# module by another gate, and a second wrapper would take over the same
# buffer. The first wrapper would then be collected, and collecting it
# closes the buffer the new one is still using -- the next gate to wrap
# the stream would die on a closed buffer, having measured nothing.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


sys.path.insert(0, "scripts")
from gen_rules_doc import (  # type: ignore
    MAIN_ROOT,
    REGISTRY_CONST_RE,
    RULE_ID_RE,
    collect as collect_full,
)


def main() -> int:
    rows = collect_full()
    out: list[str] = []
    out.append("# plumb-style — 规则快查表")
    out.append("")
    out.append(
        f"本表是 `{len(rows)}` 条规则的扁平索引，每行一条。"
        "详细说明请看 `plumb-style-rules.md`。"
    )
    out.append("")
    out.append(
        "| 规则 ID | 短名 | 分类 | 严重度 | 类型 | 规则类 |"
        " 外部等价 (PMD / SpotBugs / Checkstyle / SonarQube) |"
    )
    out.append(
        "| ------- | ---- | ---- | ------ | ---- | ------ |"
        " --------------------------------------------- |"
    )
    for r in sorted(rows, key=lambda r: r["pl_id"]):
        eqs = r["equivalents"]
        eq_str_parts = []
        for tool in ["PMD", "SpotBugs", "Checkstyle", "SonarQube"]:
            if eqs.get(tool):
                eq_str_parts.append(f"**{tool}**: {', '.join(eqs[tool])}")
        eq_str = "<br/>".join(eq_str_parts) if eq_str_parts else "—"
        out.append(
            f"| `{r['pl_id']}` | `{r['short']}` | `{r['category']}` "
            f"| {r['severity']} | {r['type']} "
            f"| `{r['fqn']}` | {eq_str} |"
        )
    out.append("")
    out_path = Path("docs") / "plumb-style-rules-index.md"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"Wrote {out_path}  ({out_path.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
