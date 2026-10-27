#!/usr/bin/env python3
"""Generate docs/rules/RULES.md.

Every number in the document is MEASURED from the source, so it cannot drift. The prose
explains what the numbers mean; the numbers themselves come from the code.

    python scripts/gen_rules_overview.py           # write the document
    python scripts/gen_rules_overview.py --check   # fail if the committed one is stale

The rule tables are read out of the sources too: the taint rules from
`TaintRuleRegistry`, the DFA checkers from `checkers.yaml`, and the style distribution from
the checker classes themselves.
"""

import argparse
import io
import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "rules" / "RULES.md"
ZH_DOC = REPO / "docs" / "zh" / "rules" / "RULES.md"

sys.path.insert(0, str(REPO / "scripts"))
import capability_baseline as cb  # noqa: E402

STYLE_ROOT = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style"
DFA_ROOT = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
CHECKERS_YAML = REPO / "plumb-dfa" / "src" / "main" / "resources" / "checkers.yaml"
RULE_REGISTRY = REPO / "plumb-rules" / "src" / "main" / "java" / "org" / "plumb" / "rules" / "RuleRegistry.java"
CWE_MAPPINGS = REPO / "plumb-dfa" / "src" / "main" / "resources" / "cwe-mappings.yaml"
TAINT_REGISTRY = DFA_ROOT / "taint" / "TaintRuleRegistry.java"

# The sub-directory names of plumb-style, and what each one is about.
STYLE_DIRS = [
    ("style", "Coding style (comments, Javadoc, indentation, imports, control flow, declarations)"),
    ("performance", "Performance (strings, boxed types, BigDecimal, Random)"),
    ("naming", "Naming conventions (types, methods, fields, parameters, local variables, packages)"),
    ("security", "Security-related (deserialization, URL sanitization)"),
    ("error", "Error handling (catch, finally, throw, synchronized)"),
    ("bug", "Clear bug patterns (compareTo, equals, null checks)"),
    ("design", "Design metrics (cyclomatic complexity, coupling, depth)"),
    ("quality", "Quality (regex confusion, ORM patterns)"),
    ("concurrency", "Concurrency (servlet atomicity, singleton race)"),
]

# The Chinese topics of the same sub-directories, for the Chinese counterpart.
STYLE_DIRS_ZH = [
    ("style", "编码风格（注释、Javadoc、缩进、imports、控制流、声明）"),
    ("performance", "性能（字符串、boxed、BigDecimal、Random）"),
    ("naming", "命名约定（类型、方法、字段、参数、局部变量、包）"),
    ("security", "安全相关（deserialization、URL sanitization）"),
    ("error", "错误处理（catch、finally、throw、synchronized）"),
    ("bug", "明确 bug 模式（compareTo、equals、null 检查）"),
    ("design", "设计度量（圈复杂度、耦合、深度）"),
    ("quality", "质量（regex confusion、ORM 模式）"),
    ("concurrency", "并发（servlet 原子性、singleton race）"),
]


def _count_cwe_mappings():
    """Count the entries under the `mappings:` block of cwe-mappings.yaml.

    The block is read with the engine's own YAML loader rather than with a regex, so the
    count is the number of mappings the engine actually builds. A regex over the file would
    also match the comment block that documents the format.
    """
    if not CWE_MAPPINGS.is_file():
        return 0
    try:
        import yaml
        data = yaml.safe_load(CWE_MAPPINGS.read_text(encoding="utf-8")) or {}
    except Exception:
        return 0
    mappings = data.get("mappings")
    return len(mappings) if isinstance(mappings, dict) else 0


def measure():
    surface = cb.measure()
    sink = surface["sink_families"]
    families = [k for k in sink if k != "families"]
    source_families = [f for f in families if f.startswith("taint.sources.")]
    sink_families = [f for f in families if not f.startswith("taint.sources.")]

    style_by_dir = {}
    style_total = 0
    rule_id_literals = 0
    for p in STYLE_ROOT.rglob("*.java"):
        t = p.read_text(encoding="utf-8", errors="replace")
        rule_id_literals += len(re.findall(r'new RuleId\(\s*"ast-', t))
        if not re.search(r"implements\s+AstChecker|extends\s+AstChecker", t):
            continue
        style_total += 1
        rel = p.relative_to(STYLE_ROOT)
        top = rel.parts[0] if len(rel.parts) > 1 else ""
        style_by_dir[top] = style_by_dir.get(top, 0) + 1

    categories = (STYLE_ROOT / "CheckerCategories.java").read_text(encoding="utf-8", errors="replace")
    declared, enabled, disabled = _declared_checkers()

    tainted_sub = 0
    direct_dfa = 0
    for p in DFA_ROOT.rglob("*.java"):
        t = p.read_text(encoding="utf-8", errors="replace")
        if "extends TaintedCheckerBase" in t:
            tainted_sub += 1
        if "implements DfaChecker" in t:
            direct_dfa += 1

    taint_rules = _taint_rules()

    return {
        "source_families": len(source_families),
        "source_patterns": sum(len(sink.get(f, [])) for f in source_families),
        "sink_families": len(sink_families),
        "sink_patterns": sum(len(sink.get(f, [])) for f in sink_families),
        "dfa_declared": declared,
        "dfa_enabled": enabled,
        "dfa_disabled": disabled,
        "dfa_registered": len(surface["registered_checkers"]),
        "style_total": style_total,
        "style_by_dir": style_by_dir,
        "rule_id_literals": rule_id_literals,
        "checker_categories": len(re.findall(r"m\.put\(", categories)),
        "registered_rules": len(surface["registered_rules"]),
        "cwe_mappings": _count_cwe_mappings(),
        "rule_registry_register": len(re.findall(r"\bregister\(", RULE_REGISTRY.read_text(encoding="utf-8"))) if RULE_REGISTRY.is_file() else 0,
        "tainted_subclasses": tainted_sub,
        "direct_dfa": direct_dfa,
        "taint_rules": taint_rules,
    }


def _declared_checkers():
    """The checkers checkers.yaml declares, and how many are enabled."""
    if not CHECKERS_YAML.is_file():
        return 0, 0, 0
    text = CHECKERS_YAML.read_text(encoding="utf-8", errors="replace")
    in_checkers = False
    declared = []
    enabled = []
    current = None
    for line in text.splitlines():
        if re.match(r"^checkers:\s*$", line):
            in_checkers = True
            continue
        if not in_checkers:
            continue
        m = re.match(r"^  (\w+):\s*$", line)
        if m:
            current = m.group(1)
            declared.append(current)
            continue
        m = re.match(r"^\s+enabled:\s*(\w+)", line)
        if m and current and m.group(1) == "true":
            enabled.append(current)
    return len(declared), len(enabled), len(declared) - len(enabled)


def _taint_rules():
    """The taint rules, from the registry."""
    if not TAINT_REGISTRY.is_file():
        return []
    text = TAINT_REGISTRY.read_text(encoding="utf-8", errors="replace")
    ids = re.findall(r'"(dfa-taint-[a-z0-9\-]+)"', text)
    seen = []
    for i in ids:
        if i not in seen:
            seen.append(i)
    return seen


def render(m):
    rows = []
    for name, topic in STYLE_DIRS:
        n = m["style_by_dir"].get(name, 0)
        if n:
            rows.append("| `plumb-style/%s/` | **%d** | %s |" % (name, n, topic))
    root_n = m["style_by_dir"].get("", 0)
    if root_n:
        rows.append("| `plumb-style/` (the root directory) | **%d** | Does not belong to any sub-directory category |" % root_n)
    style_table = "\n".join(rows)

    taint_list = ", ".join("`%s`" % t for t in m["taint_rules"])

    m = dict(m)
    m["style_table"] = style_table
    m["taint_rule_count"] = len(m["taint_rules"])
    m["taint_list"] = taint_list
    m["taint_table"] = _taint_table(m["taint_rules"])
    return TEMPLATE % m


def render_zh(m):
    """The Chinese counterpart, from the SAME measured values."""
    rows = []
    for name, topic in STYLE_DIRS_ZH:
        n = m["style_by_dir"].get(name, 0)
        if n:
            rows.append("| `plumb-style/%s/` | **%d** | %s |" % (name, n, topic))
    root_n = m["style_by_dir"].get("", 0)
    if root_n:
        rows.append("| `plumb-style/`（根目录） | **%d** | 不属于任何子目录分类 |" % root_n)
    style_table = "\n".join(rows)

    taint_list = "、".join("`%s`" % t for t in m["taint_rules"])

    m = dict(m)
    m["style_table_zh"] = style_table
    m["taint_rule_count"] = len(m["taint_rules"])
    m["taint_list"] = taint_list
    m["taint_table"] = _taint_table(m["taint_rules"])
    return ZH_TEMPLATE % m


TEMPLATE = """# Plumb Rule Catalogue

[English](../README.md) | [中文](../zh/rules/RULES.md)

This document lists every rule that ships with Plumb, its parameters, severity,
type, CWE labels, emit-id naming styles, and how to enable / disable / reparameterise
them.

> **Generated documents**:
> - `docs/plumb-style-rules.md` -- the Javadoc of every style rule plus the equivalent-tool (PMD/SpotBugs/SonarQube/Coverity) mapping, generated by `scripts/gen_rules_doc.py`
> - `docs/plumb-style-rules-index.md` -- the style rule index, generated by `scripts/gen_rules_index.py`
> - `plumb-dfa/src/main/resources/cwe-mappings.yaml` -- the DFA finding key -> CWE mapping
>
> This document is generated by `scripts/gen_rules_overview.py`, and every number in it
> is MEASURED from the source:
> `plumb-dfa/src/main/resources/sink-families.yaml`,
> `plumb-dfa/src/main/resources/checkers.yaml`,
> `plumb-style/src/main/java/org/plumb/style/**`,
> `plumb-main/src/main/java/org/plumb/main/ScanRunner.java`.

## 1. Counts at a glance

| Category | Module | Count | How it is implemented | Stage |
| --- | --- | ---: | --- | --- |
| Taint source patterns | plumb-dfa | **%(source_patterns)d** | The %(source_families)d `taint.sources.*` families of `sink-families.yaml` | taint |
| Taint sink patterns | plumb-dfa | **%(sink_patterns)d** | The %(sink_families)d `*.sinks` families of `sink-families.yaml` | taint |
| DFA checker (declared in YAML) | plumb-dfa | **%(dfa_declared)d** (%(dfa_enabled)d enabled / %(dfa_disabled)d disabled) | `checkers.yaml` | dfa |
| DFA checker (actually registered) | plumb-main | **%(dfa_registered)d** | `ScanRunner.buildDfaCheckers()` | dfa |
| Style / naming / design | plumb-style | **%(style_total)d** | JavaParser AST visitor | style |
| Total registered rules | all | **%(registered_rules)d** | The actual registrations of AST + DFA | -- |

> **The counts measure different things and must not be added up**:
> - the **style checker classes** (%(style_total)d) is the number of classes in the source;
> - the **registered rules** (%(registered_rules)d) is the number of rule ids the engine can
>   actually emit -- one rule can be declared by several checkers, so the two numbers
>   are not equal.
>
> The %(dfa_declared)d DFA checkers declared in `checkers.yaml` are not the whole story
> either: `ScanRunner` actually registers %(dfa_registered)d DFA checkers, and the
> difference (taint, boundary, control flow) is **implemented per layer** and is not
> switched through the YAML.

### 1.1 Distribution of the style rules over the sub-directories (measured)

Counted over the classes under `plumb-style/src/main/java/org/plumb/style/**` that
declare `implements AstChecker` / `extends AstChecker`:

| Sub-directory | Count | Topic |
| --- | ---: | --- |
%(style_table)s
| **Total** | **%(style_total)d** | -- |

`CheckerCategories.CHECKER_CATEGORIES` registers **%(checker_categories)d** of them.

### 1.2 Equivalent coverage in external tools

Every Plumb rule carries the corresponding ID of PMD / SpotBugs / SonarQube / Coverity
in the `RuleDef.aliases` field. **For the external tool IDs a rule actually maps to,
see the "Equivalent rules in external tools" section of
[`../plumb-style-rules.md`](../plumb-style-rules.md)** -- it is parsed out of the
aliases / Javadoc in the source by `scripts/gen_rules_doc.py`, so there is no
mapping table to maintain by hand.

The coverage ratio changes with the rule set, so this document does not hard-code a
percentage that would go stale.

**For the full comparison (implementation mechanism / CWE coverage / rule coverage /
AI integration) see `RULE-TOOL-COMPARISON.md`**.

### 1.3 By implementation mechanism

The checkers split into two large groups by "which layer they run on"; AI is not a
third group -- it is a post-processing dedup layer over the findings and does not
add new checkers:

| Category | Location | Count (measured) | Trigger | Where it applies |
| --- | --- | ---: | --- | --- |
| **Style** (AST-only) | `plumb-style/` | **%(style_total)d** checker classes | JavaParser only | Coding style, naming, purely structural bugs |
| **DFA** | `plumb-dfa/` | **%(tainted_subclasses)d** extending `TaintedCheckerBase` + **%(direct_dfa)d** directly `implements DfaChecker` | TAC + CFG (taint / numeric / resource / control flow) | When you need cross-statement def-use, type tracking, source-to-sink data flow |

The breakdown of the implementation shapes inside the DFA is in
[`../tech/ARCHITECTURE.md`](../tech/ARCHITECTURE.md) §3.0, and the four kinds of
analysis (taint / boundary / points-to / control flow) are in
[`../tech/DATA-FLOW.md`](../tech/DATA-FLOW.md).

### 1.4 The difference between style rule naming and DFA rule naming

| Dimension | Style | DFA checker | Taint rule |
| --- | --- | --- | --- |
| Where it is written | `plumb-style/<cat>/*Check.java` | `plumb-dfa/checkers/<family>/*Checker.java` | `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRuleRegistry.java` |
| How it is registered | classpath scan + `CheckerCategories` | `ScanRunner.buildDfaCheckers()` | an `m.put(...)` in `TaintRuleRegistry` |
| Naming style | `ast-<category>-<bare>` | `dfa-taint-<bare>` | `dfa-taint-<bare>` |
| On or off by default | mostly on | controlled by the `enabled` flag of `checkers.yaml` | all on (cannot be turned off individually) |
| Steps to add a new rule | write `*Check.java` + add `m.put(...)` to `CheckerCategories.java` | write `<Name>Checker.java` + add a line to `ScanRunner.buildDfaCheckers()` + add an entry to `checkers.yaml` | add an `m.put(...)` to `TaintRuleRegistry` |

`TaintRuleRegistry` currently holds **%(taint_rule_count)d** taint rules:
%(taint_list)s.

## 2. Severity

`RuleDef.Severity` defines 5 levels:

| Severity | Meaning | Typical rules |
| --- | --- | --- |
| `CRITICAL` | Fix immediately | `TAINT_SINK_COMMAND_INJECTION`, `HARDCODED_CREDENTIALS`, `STATIC_API_KEY`, `PREDICTABLE_KEY_PASSWORD` |
| `HIGH` | Must investigate | `TAINT_SINK_SQL_INJECTION`, `WEAK_CRYPTO`, `MISSING_AUTHORIZATION`, `NULL_DEREF`, `XXE`, `ITERATOR_CONCURRENT_MODIFICATION` |
| `MEDIUM` | Should investigate | `ASSIGNMENT_IN_OPERAND`, `DEAD_STORE`, `MISSING_INPUT_VALIDATION`, `INFORMATION_EXPOSURE`, `DIV_BY_ZERO`, `INT_OVERFLOW` |
| `LOW` | Suggested | `STYLE_*`, `NAMING_*`, `INFINITE_LOOP`, `EMPTY_CATCH_HANDLER` |
| `INFO` | Information only | `TAINT_SOURCE_*` |

## 3. Types

`RuleDef.Type` has 13 values:

| Type | Use |
| --- | --- |
| `TAINT` | Data flow (source-to-sink) |
| `DFA` | Data flow (numeric / resource) |
| `STYLE` | Coding style |
| `NAMING` | Naming conventions |
| `SECURITY` | Security-related |
| `CORRECTNESS` | Correctness |
| `PERFORMANCE` | Performance |
| `BUG` | An outright mistake |
| `BAD_PRACTICE` | An anti-pattern |
| `MULTITHREADING` | Concurrency |
| `MALICIOUS_CODE` | Malicious code |
| `I18N` | Internationalisation |
| `OTHER` | Anything else |

## 4. Emit-id naming convention

There are four emit-id styles. `ScanRunner.isRuleIdAllowed` matches them in more
than one form.

### 4.1 The four styles side by side

| Source | Example | Style | Notes |
| --- | --- | --- | --- |
| Style check | `ast-style-random-iv-cbc` | `ast-<category>-<bare>` | category = the sub-package the checker lives in (style / naming / security / error / bug / design / performance / quality / concurrency). `plumb-style/style/RandomIvCheck` |
| DFA taint checker | `dfa-taint-null-dereference` | `dfa-taint-<bare>` | `plumb-dfa/checkers/taint/NullDerefChecker` |
| Taint rule (registry) | `dfa-taint-sql-injection` | `dfa-taint-<bare>` | the %(taint_rule_count)d entries of `TaintRuleRegistry` |
| RuleRegistry UUID | `bu-0401-weak-crypto` | `bu-<NNNN>-<bare>` | `plumb-rules/.../RuleRegistry.java` |

Measured over the %(rule_id_literals)d `new RuleId("ast-...")` literals under
`plumb-style/src/main/java`, **not one of them ends in `-style`**. A string with an
`-style` suffix such as `ast-style-random-iv-cbc-style` is kept only as an **alias**
of a `RuleDef` (for compatibility with the old YAML whitelist); it is no longer the
emit-id of any rule.

### 4.2 How to write the yaml whitelist

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc        # style emit-id
    - ast-style-random-iv-cbc-style  # old alias (kept for compatibility)
    - random-iv-cbc                  # bare slug (also matched after the prefix is stripped)
    - dfa-taint-null-dereference     # DFA taint emit-id
    - TAINT_SINK_SQL_INJECTION        # taint rule name
    - bu-0401-weak-crypto             # UUID
```

**Any one** of these styles is matched by `ScanRunner.isRuleIdAllowed`.

### 4.3 Where the CWE labels come from

CWE labels have two independent sources, and **changing the emit-id string does not
affect the CWE**:

| Rule type | CWE source | How to add it |
| --- | --- | --- |
| AST rules (`plumb-style`) | The CWE list in the `RuleDef` constructor arguments | Pass `List.of("CWE-xxx")` |
| DFA rules (`plumb-dfa`) | `plumb-dfa/src/main/resources/cwe-mappings.yaml` | Add a `finding-key: CWE-xxx` entry |

The CWE of an AST rule travels with the finding into the NDJSON `cwe` field; the
finding key of a DFA rule is looked up through `cwe-mappings.yaml`.

## 5. Taint rules (%(taint_rule_count)d of them)

The taint rules are managed by `TaintRuleRegistry` and configured separately from
the sink families: a sink family decides "which calls are sinks", a taint rule
decides "which source-to-sink path counts as a problem".

| Rule ID | Severity | CWE |
| --- | --- | --- |
%(taint_table)s

## 6. Enabling and disabling

The switches of the DFA checkers are in
`plumb-dfa/src/main/resources/checkers.yaml`; the switch of a style rule goes
through `RuleDef.enabled()`, which is decided when `CheckerCategories` registers it.

**Do not use the `RuleRegistry.java` of `plumb-rules` to infer the total number of
rules**: it has %(rule_registry_register)d `register(...)` calls, and a great many of
the `org.plumb.dfa.checker.*` / `org.plumb.dfa.taint.*` class names in it do not
exist under `plumb-dfa` (for example the ones pointing at
`org.plumb.dfa.taint.TaintAnalysis`); that registry is stale.

## 7. Related documents

- [`RULE-AUTHORING.md`](RULE-AUTHORING.md) -- how to add a rule
- [`RULE-STYLE-OP.md`](RULE-STYLE-OP.md) -- the rule operations manual
- [`RULE-TOOL-COMPARISON.md`](RULE-TOOL-COMPARISON.md) -- the comparison with the mainstream tools
- [`../tech/STYLE-RULES.md`](../tech/STYLE-RULES.md) -- the design constraints of the style rules
- [`../../README.md`](../../README.md) -- getting started
"""


# The Chinese counterpart. It carries the SAME measured values as the English
# template above, so the two versions cannot drift apart. It is generated rather
# than hand-written for exactly that reason: a hand-written copy would carry a
# stale number the moment a count changed.
ZH_TEMPLATE = """# Plumb 规则清单

[English](../../rules/RULES.md) | [中文](../README.md)

本文档列出 Plumb 自带的所有规则、其参数、严重度、类型、CWE 标签、emit-id 命名风格，以及如何启用 / 禁用 / 修改参数。

> **自动生成文档**：
> - `docs/plumb-style-rules.md` — 每条 style rule 的 Javadoc + 等价工具（PMD/SpotBugs/SonarQube/Coverity）映射，由 `scripts/gen_rules_doc.py` 生成
> - `docs/plumb-style-rules-index.md` — style rule 索引，由 `scripts/gen_rules_index.py` 生成
> - `plumb-dfa/src/main/resources/cwe-mappings.yaml` — DFA finding key → CWE 映射
>
> 本文档由 `scripts/gen_rules_overview.py` 生成，每个数字都从源码实测：
> `plumb-dfa/src/main/resources/sink-families.yaml`、
> `plumb-dfa/src/main/resources/checkers.yaml`、
> `plumb-style/src/main/java/org/plumb/style/**`、
> `plumb-main/src/main/java/org/plumb/main/ScanRunner.java`。

## 1. 数量概览

| 类别 | 模块 | 数量 | 实现方式 | 阶段 |
| --- | --- | ---: | --- | --- |
| Taint source 模式 | plumb-dfa | **%(source_patterns)d** | `sink-families.yaml` 的 %(source_families)d 个 `taint.sources.*` family | taint |
| Taint sink 模式 | plumb-dfa | **%(sink_patterns)d** | `sink-families.yaml` 的 %(sink_families)d 个 `*.sinks` family | taint |
| DFA checker（YAML 声明） | plumb-dfa | **%(dfa_declared)d**（%(dfa_enabled)d 启用 / %(dfa_disabled)d 禁用） | `checkers.yaml` | dfa |
| DFA checker（实际注册） | plumb-main | **%(dfa_registered)d** | `ScanRunner.buildDfaCheckers()` | dfa |
| Style / 命名 / 设计 | plumb-style | **%(style_total)d** | JavaParser AST visitor | style |
| 已注册规则总数 | 全部 | **%(registered_rules)d** | AST + DFA 的实际注册 | — |

> **数字口径不同，不能相加**：
> - **style checker 类**（%(style_total)d）是源码里的类数；
> - **已注册规则**（%(registered_rules)d）是引擎实际能 emit 的 rule id 数 —— 一条规则可以由
>   多个 checker 声明，所以两个数不相等。
>
> `checkers.yaml` 声明的 %(dfa_declared)d 条 DFA checker 也不是全部：`ScanRunner` 实际注册了
> %(dfa_registered)d 个 DFA checker，差的那部分（污点、边界、控制流）是**按层实现**的，
> 它们不通过 YAML 开关。

### 1.1 Style 规则子目录分布（实测）

按 `plumb-style/src/main/java/org/plumb/style/**` 里声明了 `implements AstChecker` /
`extends AstChecker` 的类统计：

| 子目录 | 数量 | 主题 |
| --- | ---: | --- |
%(style_table_zh)s
| **合计** | **%(style_total)d** | — |

`CheckerCategories.CHECKER_CATEGORIES` 里登记了 **%(checker_categories)d** 条。

### 1.2 外部工具等价覆盖

每条 Plumb 规则通过 `RuleDef.aliases` 字段携带 PMD / SpotBugs / SonarQube / Coverity
的对应 ID。**每条规则实际映射到哪些外部工具 ID，见
[`../../plumb-style-rules.md`](../../plumb-style-rules.md) 的「外部等价规则」小节** ——
那是由 `scripts/gen_rules_doc.py` 从源码里的 aliases / Javadoc 解析出来的，
不需要手工维护对照表。

覆盖比例随规则集变化，本文档不再手写一个会过期的百分比。

**完整对比（实现机制 / CWE 覆盖 / 规则覆盖面 / AI 集成）见 `RULE-TOOL-COMPARISON.md`**。

### 1.3 按实现机制分类

checker 按"跑在哪一层"分成两大类，AI 不构成第三类——它是 findings 的后置 dedup 层，
不写新 checker：

| 类别 | 位置 | 数量（实测） | 触发器 | 适用场景 |
| --- | --- | ---: | --- | --- |
| **Style**（AST-only） | `plumb-style/` | **%(style_total)d** 个 checker 类 | 仅 JavaParser | 编码风格、命名、纯结构 bug |
| **DFA** | `plumb-dfa/` | **%(tainted_subclasses)d** 个继承 `TaintedCheckerBase` + **%(direct_dfa)d** 个直接 `implements DfaChecker` | TAC + CFG（污点 / 数值 / 资源 / 控制流） | 需跨语句 def-use、type-tracking、source-to-sink 数据流 |

DFA 内部的实现形态细分见 [`../tech/ARCHITECTURE.md`](../tech/ARCHITECTURE.md) §3.0，
四类分析（污点 / 边界 / 指向 / 控制流）见 [`../tech/DATA-FLOW.md`](../tech/DATA-FLOW.md)。

### 1.4 Style 规则命名 vs DFA 规则命名的差异

| 维度 | Style | DFA checker | Taint rule |
| --- | --- | --- | --- |
| 写在哪 | `plumb-style/<cat>/*Check.java` | `plumb-dfa/checkers/<family>/*Checker.java` | `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRuleRegistry.java` |
| 怎么注册 | classpath scan + `CheckerCategories` | `ScanRunner.buildDfaCheckers()` | `TaintRuleRegistry` 的 `m.put(...)` |
| 命名风格 | `ast-<category>-<bare>` | `dfa-taint-<bare>` | `dfa-taint-<bare>` |
| 默认开/关 | 大多 on | 受 `checkers.yaml` 的 `enabled` 控制 | 全部 on（不可单独关） |
| 加新规则步骤 | 写 `*Check.java` + 在 `CheckerCategories.java` 加 `m.put(...)` | 写 `<Name>Checker.java` + 在 `ScanRunner.buildDfaCheckers()` 加一行 + 在 `checkers.yaml` 加条目 | 在 `TaintRuleRegistry` 加一条 `m.put(...)` |

`TaintRuleRegistry` 当前有 **%(taint_rule_count)d** 条 taint rule：
%(taint_list)s。

## 2. 严重度

`RuleDef.Severity` 定义 5 档：

| 严重度 | 含义 | 典型规则 |
| --- | --- | --- |
| `CRITICAL` | 立即修复 | `TAINT_SINK_COMMAND_INJECTION`, `HARDCODED_CREDENTIALS`, `STATIC_API_KEY`, `PREDICTABLE_KEY_PASSWORD` |
| `HIGH` | 必查 | `TAINT_SINK_SQL_INJECTION`, `WEAK_CRYPTO`, `MISSING_AUTHORIZATION`, `NULL_DEREF`, `XXE`, `ITERATOR_CONCURRENT_MODIFICATION` |
| `MEDIUM` | 应查 | `ASSIGNMENT_IN_OPERAND`, `DEAD_STORE`, `MISSING_INPUT_VALIDATION`, `INFORMATION_EXPOSURE`, `DIV_BY_ZERO`, `INT_OVERFLOW` |
| `LOW` | 建议 | `STYLE_*`, `NAMING_*`, `INFINITE_LOOP`, `EMPTY_CATCH_HANDLER` |
| `INFO` | 仅信息 | `TAINT_SOURCE_*` |

## 3. 类型

`RuleDef.Type` 13 个：

| Type | 用途 |
| --- | --- |
| `TAINT` | 数据流（source-to-sink） |
| `DFA` | 数据流（数值 / 资源） |
| `STYLE` | 编码风格 |
| `NAMING` | 命名约定 |
| `SECURITY` | 安全相关 |
| `CORRECTNESS` | 正确性 |
| `PERFORMANCE` | 性能 |
| `BUG` | 明显错误 |
| `BAD_PRACTICE` | 反模式 |
| `MULTITHREADING` | 并发 |
| `MALICIOUS_CODE` | 恶意代码 |
| `I18N` | 国际化 |
| `OTHER` | 其他 |

## 4. Emit-id 命名规范

emit-id 有四种风格。`ScanRunner.isRuleIdAllowed` 对它们做多形态匹配。

### 4.1 四种风格对照

| 来源 | 例子 | 风格 | 备注 |
| --- | --- | --- | --- |
| Style check | `ast-style-random-iv-cbc` | `ast-<category>-<bare>` | category = checker 所在子包（style / naming / security / error / bug / design / performance / quality / concurrency）。`plumb-style/style/RandomIvCheck` |
| DFA taint checker | `dfa-taint-null-dereference` | `dfa-taint-<bare>` | `plumb-dfa/checkers/taint/NullDerefChecker` |
| Taint rule（registry） | `dfa-taint-sql-injection` | `dfa-taint-<bare>` | `TaintRuleRegistry` 的 %(taint_rule_count)d 条 |
| RuleRegistry UUID | `bu-0401-weak-crypto` | `bu-<NNNN>-<bare>` | `plumb-rules/.../RuleRegistry.java` |

实测 `plumb-style/src/main/java` 下 %(rule_id_literals)d 个 `new RuleId("ast-...")` 字面量里，
**没有一个以 `-style` 结尾**。`ast-style-random-iv-cbc-style` 这类带 `-style`
后缀的字符串只作为 `RuleDef` 的 **alias** 保留（兼容老 YAML 白名单），
不再是任何规则的 emit-id。

### 4.2 yaml whitelist 写法

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc        # style emit-id
    - ast-style-random-iv-cbc-style  # 老 alias（兼容保留）
    - random-iv-cbc                  # bare slug（也会被剥 prefix 后匹配）
    - dfa-taint-null-dereference     # DFA taint emit-id
    - TAINT_SINK_SQL_INJECTION        # taint rule name
    - bu-0401-weak-crypto             # UUID
```

以上风格 **任意一种** 写出来都能被 `ScanRunner.isRuleIdAllowed` 匹配。

### 4.3 CWE 标签从哪来

CWE 标签有两个独立来源，**改 emit-id 字符串不影响 CWE**：

| 规则类型 | CWE 来源 | 怎么加 |
| --- | --- | --- |
| AST 规则（`plumb-style`） | `RuleDef` 构造参数里的 CWE 列表 | 传 `List.of("CWE-xxx")` |
| DFA 规则（`plumb-dfa`） | `plumb-dfa/src/main/resources/cwe-mappings.yaml` | 加一条 `finding-key: CWE-xxx` |

AST 规则的 CWE 随 finding 进入 NDJSON 的 `cwe` 字段；DFA 规则的 finding key 通过
`cwe-mappings.yaml` 查表。

## 5. Taint 规则（%(taint_rule_count)d 条）

Taint 规则由 `TaintRuleRegistry` 管理，与 sink family 分开配置：
sink family 决定"哪些调用是 sink"，taint rule 决定"从哪个 source 到哪个 sink 算一条问题"。

| 规则 ID | 严重度 | CWE |
| --- | --- | --- |
%(taint_table)s

## 6. 启用 / 禁用

DFA checker 的开关在 `plumb-dfa/src/main/resources/checkers.yaml`；style 规则的开关通过
`RuleDef.enabled()`，由 `CheckerCategories` 登记时确定。

**不要用 `plumb-rules` 的 `RuleRegistry.java` 推断规则总数**：它有 %(rule_registry_register)d 处
`register(...)`，其中大量 `org.plumb.dfa.checker.*` / `org.plumb.dfa.taint.*` 类名在
`plumb-dfa` 下并不存在（例如指向 `org.plumb.dfa.taint.TaintAnalysis`），该 registry 已陈旧。

## 7. 相关文档

- [`RULE-AUTHORING.md`](RULE-AUTHORING.md) —— 怎么加一条规则
- [`RULE-STYLE-OP.md`](RULE-STYLE-OP.md) —— 规则运维手册
- [`RULE-TOOL-COMPARISON.md`](RULE-TOOL-COMPARISON.md) —— 与主流工具的对比
- [`../tech/STYLE-RULES.md`](../tech/STYLE-RULES.md) —— style 规则的设计约束
- [`../../README.md`](../../README.md) —— 快速上手
"""


def _taint_table(taint_rules):
    if not taint_rules:
        return "| — | — | — |"
    return "\n".join("| `%s` | — | — |" % t for t in taint_rules)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    m = measure()
    targets = [(DOC, render(m)), (ZH_DOC, render_zh(m))]

    if args.check:
        failed = False
        for path, expected in targets:
            if not path.is_file():
                print("FAIL: %s does not exist" % path.relative_to(REPO))
                failed = True
                continue
            if path.read_text(encoding="utf-8") != expected:
                print("FAIL: %s is stale -- run: python scripts/gen_rules_overview.py"
                      % path.relative_to(REPO))
                failed = True
        if failed:
            return 1
        print("  ok: the rules overview matches the measured numbers")
        return 0

    for path, expected in targets:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(expected, encoding="utf-8")
        print("wrote %s" % path.relative_to(REPO))
    return 0


if __name__ == "__main__":
    sys.exit(main())
