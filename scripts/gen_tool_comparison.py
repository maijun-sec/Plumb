#!/usr/bin/env python3
"""Generate docs/rules/RULE-TOOL-COMPARISON.md.

The Plumb column of every table is MEASURED from the source, so it cannot drift. The other
tools' columns are external facts that this repository cannot reproduce, and they are labelled
as such in the document.

What this generator deliberately does NOT write:

  * Juliet corpus measurements. The corpus and the NDJSON scan output are not in version
    control, so the numbers cannot be reproduced here. A historical table would be a claim
    about a past run, not a fact about the product.
  * "which rules were disabled in which round" history.
  * Any performance timing.

    python scripts/gen_tool_comparison.py            # write the document
    python scripts/gen_tool_comparison.py --check     # fail if the committed one is stale
"""

import argparse
import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "rules" / "RULE-TOOL-COMPARISON.md"

sys.path.insert(0, str(REPO / "scripts"))
import capability_baseline as cb  # noqa: E402

STYLE_ROOT = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style"
DFA_ROOT = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
CWE_MAPPINGS = REPO / "plumb-dfa" / "src" / "main" / "resources" / "cwe-mappings.yaml"
RULE_REGISTRY = REPO / "plumb-rules" / "src" / "main" / "java" / "org" / "plumb" / "rules" / "RuleRegistry.java"


def _pattern_count(sink, names):
    """The number of patterns in the named sink families.

    `capability_baseline.measure()` returns the sink families as a dict: the key
    "families" holds the list of family NAMES, and each family name is a key of its
    own whose value is that family's list of patterns.

    An earlier version of this script read the "families" key -- the list of names --
    and then looked each name up in it, so every family counted zero patterns and the
    document stated "0 / 0" taint sources and sinks.
    """
    total = 0
    for name in names:
        patterns = sink.get(name)
        if isinstance(patterns, list):
            total += len(patterns)
        elif isinstance(patterns, dict):
            inner = patterns.get("patterns")
            if isinstance(inner, list):
                total += len(inner)
    return total


def _product_cwes():
    """Every CWE the product can attach to a finding, and where it comes from.

    Two sources, both of them product:
      * the AST rules carry their CWE in their `RuleDef`;
      * the DFA rules get theirs from `cwe-mappings.yaml`.

    The evaluation-side rule-to-CWE map is NOT one of them -- it belongs to a
    benchmark harness, and counting it here would put an evaluation number in the
    product documentation.
    """
    cwes = set()
    if RULE_REGISTRY.is_file():
        text = RULE_REGISTRY.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'"(CWE-\d+)"', text):
            cwes.add(m.group(1))
    if CWE_MAPPINGS.is_file():
        try:
            import yaml
            data = yaml.safe_load(CWE_MAPPINGS.read_text(encoding="utf-8")) or {}
            for value in (data.get("mappings") or {}).values():
                if isinstance(value, str) and value:
                    cwes.add(value)
        except Exception:
            pass
    return cwes


def measure():
    """Every number the document states about Plumb."""
    surface = cb.measure()
    sink = surface["sink_families"]
    names = [f for f in sink.get("families", []) if f != "families"]
    source_families = [f for f in names if f.startswith("taint.sources.")]

    style_classes = [p for p in STYLE_ROOT.rglob("*.java")
                      if p.name.endswith("Check.java") or p.name.endswith("Checker.java")]
    categories = (STYLE_ROOT / "CheckerCategories.java").read_text(encoding="utf-8", errors="replace")

    tainted = 0
    for p in DFA_ROOT.rglob("*.java"):
        if "extends TaintedCheckerBase" in p.read_text(encoding="utf-8", errors="replace"):
            tainted += 1

    trr = (DFA_ROOT / "taint" / "TaintRuleRegistry.java").read_text(encoding="utf-8", errors="replace")

    cwes = _product_cwes()

    # The AI tools that are actually REGISTERED. A tool class that exists but is not registered
    # is not something the agent can call, so the registration is what counts.
    runner = (REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main"
              / "ScanRunner.java")
    registered_tools = 0
    if runner.is_file():
        rt = runner.read_text(encoding="utf-8", errors="replace")
        body = rt[rt.find("buildToolRegistry()"):]
        registered_tools = len(re.findall(r"\.register\(new org\.plumb\.ai\.tools\.\w+\(\)\)", body))
    ai_tool_classes = len(list((REPO / "plumb-ai" / "src" / "main" / "java" / "org" / "plumb" / "ai"
                                / "tools").glob("*Tool.java")))

    return {
        "style_checker_classes": len(style_classes),
        "checker_categories_registered": len(re.findall(r"m\.put\(", categories)),
        "registered_checkers": len(surface["registered_checkers"]),
        "registered_rules": len(surface["registered_rules"]),
        "tainted_subclasses": tainted,
        "taint_rules": len(re.findall(r"new TaintRule\(|TaintRule\.of\(", trr)),
        "sink_families": len(names),
        "sink_patterns": _pattern_count(sink, names),
        "source_families": len(source_families),
        "source_patterns": _pattern_count(sink, source_families),
        "input_source_patterns": len(surface["input_source_patterns"]),
        "rule_to_cwe_entries": len(cwes),
        "cwe_count": len(cwes),
        "ai_tools": registered_tools,
        "ai_tool_classes": ai_tool_classes,
        "fact_kinds": len(surface["fact_kinds"]),
    }


def render(m):
    return """# 规则对比：Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle

本文档对比 Plumb 与主流 Java 静态分析工具的**实现机制**和**规则覆盖面**，帮助用户理解
Plumb 的定位与边界。

> **本文档里哪些数字是可复现的。**
>
> Plumb 一列的所有数字都由 `scripts/gen_tool_comparison.py` 从源码测量得出，重新生成即可
> 复现。其他工具一列的数字**来自公开的第三方资料，本仓库没有任何数据支撑**，只作为量级参考。
> 本文档不包含任何基准测试成绩 —— 仓库里没有 benchmark harness，任何"跑得比谁快"的数字
> 都是无法复现的断言。

## 1. 实现机制对比

| 维度 | Plumb | PMD | SonarQube | SpotBugs | Coverity | Checkstyle |
| --- | --- | --- | --- | --- | --- | --- |
| **主实现** | TAC IR + {style_checker_classes} 个 AST checker + {taint_rules} 条 taint rule + {registered_checkers} 个已注册 DFA checker | AST | AST + symbol table | 字节码扫描 | 抽象语法树 + 路径敏感 | AST + 文本匹配 |
| **Taint 引擎** | summary-based 跨函数数据流（两阶段） | 无完整 taint | SonarCFamily 引擎（商业） | 部分数据流 | 商业引擎 | 无 |
| **跨函数分析** | 两阶段 summary：Phase 1 path-insensitive → Phase 2 flow-sensitive + Z3 路径条件 | 有限 | 部分（call graph 1-hop） | 部分（BugInstance 共享） | 全局 | 无 |
| **AST 检查器** | {style_checker_classes} 个（JavaParser） | 数百条 | 数百条 | — | — | 一百多条 |
| **DFA checker** | {registered_checkers} 个已注册 | 部分 | 部分 | 字节码 bug pattern | 有 | 无 |
| **Z3 路径条件** | 有（Phase 2 per-finding 查询，用于误报过滤） | 无 | 无 | 无 | 有（商业） | 无 |
| **AI 集成** | 可选 Re-Act agent（{ai_tools} 个 tool） | 无 | SonarAI（实验） | 无 | Coverity Assist | 无 |
| **rule → CWE 映射** | 规则自带，覆盖 {cwe_count} 个 CWE | rule 自带 | rule 自带 | BugPattern 自带 | checker 自带 | check 自带 |

> "AST 检查器"和"DFA checker"是**两套不同粒度**的东西：前者数的是 checker 类，后者数的是
> 引擎实际注册的 checker。两者相加得到的"总数"取决于你在数什么，本文档不给合计值。

### 1.1 Plumb 的两阶段跨函数模型

PMD / SonarQube / Checkstyle 是 **AST-only** —— 单方法或单类视角，看不见跨函数数据流。

Plumb 的做法分两阶段：

- **Phase 1**：callee 自底向上做 path-insensitive、context-insensitive 的 summary，按函数签名
  缓存，caller 侧 O(1) 查表复用。
- **Phase 2**：caller 自顶向下做 context-sensitive、path-sensitive 分析，Z3 按 finding 求值。

这样兼顾精度（漏报为 0，per-call-site 区分）和速度。详见
[`../tech/CROSS-FUNCTION-ANALYSIS.md`](../tech/CROSS-FUNCTION-ANALYSIS.md)。

### 1.2 DFA 引擎 vs AST 的分工

| 形态 | 数量 | 含义 |
| --- | ---: | --- |
| **AST checker（`plumb-style`）** | {style_checker_classes} | 规则类，看 AST 形态 |
| **`CheckerCategories` 登记** | {checker_categories_registered} | 上面这些里被登记进规则表的 |
| **DFA checker（已注册）** | {registered_checkers} | 引擎实际装配的，涉及数据流 / 跨函数 |
| **其中 `extends TaintedCheckerBase`** | {tainted_subclasses} | 继承统一污点传播基类 |
| **事实种类（fact kinds）** | {fact_kinds} | 内置 {built_in_kinds} 个 + `fact-kinds.yaml` {yaml_kinds} 个 |

**分工原则**：只依赖 AST 形态的规则放在 `plumb-style`。用 DFA 实现 style rule 既不提升精度
也不提升召回，只是把"数 AST 节点"的逻辑硬塞进 IR visitor。

完整的 DFA checker 分类见 [`../tech/CROSS-FUNCTION-AI-TRACE.md`](../tech/CROSS-FUNCTION-AI-TRACE.md) §12。

## 2. 规则覆盖面

### 2.1 Plumb 的规则构成

| 类别 | 数量 | 来源 |
| --- | ---: | --- |
| AST checker 类 | {style_checker_classes} | `plumb-style/**` |
| 其中登记进规则表 | {checker_categories_registered} | `CheckerCategories` |
| 已注册规则总数 | {registered_rules} | AST + DFA 的实际注册 |
| DFA checker（已注册） | {registered_checkers} | `ScanRunner.buildDfaCheckers()` |
| Taint rule | {taint_rules} | `TaintRuleRegistry` |
| Taint source family / 模式 | {source_families} / {source_patterns} | `sink-families.yaml` 的 `taint.sources.*` |
| Taint sink family / 模式 | {sink_family_count} / {sink_pattern_count} | `sink-families.yaml` 的 `*.sinks` |
| 输入源模式（框架入口） | {input_source_patterns} | `InputSourcePatterns` |
| 事实种类 | {fact_kinds} | 内置 + YAML |

### 2.2 关键 CWE 覆盖

以下只列 Plumb 侧的机制，**不与其他工具逐条对比** —— 那些数字来自外部资料，无法复现。

| CWE | Plumb 侧的实现 |
| --- | --- |
| CWE-22 路径穿越 | taint sink（`url.sinks` / `file.sinks`） |
| CWE-78 命令注入 | taint sink |
| CWE-89 SQL 注入 | taint sink（`sql.sinks`）+ MyBatis `${{}}` 原始插值检查（AST 层） |
| CWE-94 代码注入 | taint sink（`ognl.sinks` / `el.sinks` / `jcr.sinks` / `jsp-include.sinks`） |
| CWE-117 日志注入 | taint sink（`log.sinks`） |
| CWE-190/191 整数溢出/下溢 | DFA + Z3 路径条件 |
| CWE-252/253 未检查返回值 | DFA（`UncheckedReturnCheck`） |
| CWE-470 不安全反射 | taint sink（`reflection.sinks`） |
| CWE-502 反序列化 | taint sink |
| CWE-532 日志中的敏感信息 | taint source + taint sink |
| CWE-601 开放重定向 | taint sink（`open-redirect.sinks`） |
| CWE-918 SSRF | taint sink（`url.sinks`） |
| CWE-1333 ReDoS | taint sink（`regex.sinks`） |

**框架入口**：Spring MVC / JAX-RS / Quarkus REST / Micronaut / Jakarta Servlet 的绑定注解
（`@RequestParam`、`@QueryParam`、`@RestQuery`、`@QueryValue` 等）由同一份注解清单驱动，
同时供污点引擎和输入校验 checker 使用。

## 3. 误报控制

Plumb 的误报控制分三层：

| 层 | 机制 |
| --- | --- |
| **路径敏感** | `SolverZ3Bridge` / `BoundaryConstraintChecker` 按 finding 求值路径条件，用于否定数值类告警 |
| **来源可信度** | 污点源带可信度权重，来源标签形如 `param:customer@spring-mvc:RequestParam` |
| **AI 降噪（可选）** | `plumb-ai` 的 Re-Act agent，可按规则覆写 prompt |

> **一句话原则**：缺少事实**不等于**安全。空的 points-to 集合、缺失的 null fact、未覆盖的
> alias 都**不能**用来抑制一条告警 —— 它们只意味着"不知道"，而"不知道"要按"可能有问题"
> 处理。抑制一条告警的前提是 guard 覆盖了全部相关可能性集合。

## 4. AI 集成

| 能力 | 说明 |
| --- | --- |
| 形态 | Re-Act agent（`ReActAgent` + `LlmConnection` + `ToolRegistry`） |
| 工具 | {ai_tools} 个已注册（源码里有 {ai_tool_classes} 个 tool 类，含 `Tool` 接口本身） |
| Provider | `OpenAiCompatibleLlmProvider`（OpenAI 兼容接口） |
| 定位 | **可选层**。纯静态分析本身已经够用；AI 用来进一步降 FP，不取代静态规则 |
| 每条规则的 prompt | 写在各 checker 的 `getAiPrompt()` 里 |

## 5. 适合与不适合的场景

**适合**：
- 需要**跨函数 taint**（Spring / Jakarta / 大型企业项目）
- 需要**路径敏感的数值分析**（CWE-190 / 191 / 252 / 253）
- 希望**一个工具覆盖多套风格规则**，而不是同时装 PMD + Sonar + SpotBugs + Checkstyle
- 需要**透明实现** —— 每条规则的 SPI 都有文档
- 需要把结果接入 CI / IDE（NDJSON、SARIF 输出）

**不适合**：
- 需要 C / C++ / JS / Go 多语言（Plumb 只做 Java）
- 需要商业引擎级别的路径敏感覆盖
- 需要 IDE 内的实时增量检查（Plumb 以 CLI 为主）

## 6. 相关文档

- [`RULES.md`](RULES.md) —— 规则总览与分类
- [`RULE-AUTHORING.md`](RULE-AUTHORING.md) —— 怎么加一条规则
- [`RULE-STYLE-OP.md`](RULE-STYLE-OP.md) —— 规则运维手册
- [`../tech/ARCHITECTURE.md`](../tech/ARCHITECTURE.md) —— 引擎架构
- [`../tech/CROSS-FUNCTION-ANALYSIS.md`](../tech/CROSS-FUNCTION-ANALYSIS.md) —— 跨函数分析
- [`../../README.md`](../../README.md) —— 快速上手
""".format(**m)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    surface = cb.measure()
    m = measure()
    m["built_in_kinds"] = len([k for k in surface["fact_kinds"] if k.startswith("builtin:")])
    m["yaml_kinds"] = len([k for k in surface["fact_kinds"] if k.startswith("yaml:")])
    m["sink_family_count"] = m["sink_families"] - m["source_families"]
    m["sink_pattern_count"] = m["sink_patterns"] - m["source_patterns"]

    text = render(m)
    if args.check:
        if not DOC.is_file():
            print("FAIL: %s does not exist" % DOC.relative_to(REPO))
            return 1
        current = DOC.read_text(encoding="utf-8")
        if current != text:
            print("FAIL: %s is stale -- run: python scripts/gen_tool_comparison.py"
                  % DOC.relative_to(REPO))
            return 1
        print("  ok: the comparison document matches the measured numbers")
        return 0

    DOC.parent.mkdir(parents=True, exist_ok=True)
    DOC.write_text(text, encoding="utf-8")
    print("wrote %s" % DOC.relative_to(REPO))
    return 0


if __name__ == "__main__":
    sys.exit(main())
