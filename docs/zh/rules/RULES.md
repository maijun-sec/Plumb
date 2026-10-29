# Plumb 规则清单

[English](../../rules/RULES.md) | [中文](../README.md)

本文档列出 Plumb 自带的所有规则、其参数、严重度、类型、CWE 标签、emit-id 命名风格，以及如何启用 / 禁用 / 修改参数。

> **自动生成文档**：
> - `docs/plumb-style-rules.md` — 每条 style rule 的 Javadoc + 等价工具（PMD/SpotBugs/SonarQube/Checkstyle）映射，由 `scripts/gen_rules_doc.py` 生成
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
| Taint source 模式 | plumb-dfa | **19** | `sink-families.yaml` 的 5 个 `taint.sources.*` family | taint |
| Taint sink 模式 | plumb-dfa | **358** | `sink-families.yaml` 的 48 个 `*.sinks` family | taint |
| DFA checker（YAML 声明） | plumb-dfa | **16**（14 启用 / 2 禁用） | `checkers.yaml` | dfa |
| DFA checker（实际注册） | plumb-main | **67** | `ScanRunner.buildDfaCheckers()` | dfa |
| Style / 命名 / 设计 | plumb-style | **597** | JavaParser AST visitor | style |
| 已注册规则总数 | 全部 | **1148** | AST + DFA 的实际注册 | — |

> **数字口径不同，不能相加**：
> - **style checker 类**（597）是源码里的类数；
> - **已注册规则**（1148）是引擎实际能 emit 的 rule id 数 —— 一条规则可以由
>   多个 checker 声明，所以两个数不相等。
>
> `checkers.yaml` 声明的 16 条 DFA checker 也不是全部：`ScanRunner` 实际注册了
> 67 个 DFA checker，差的那部分（污点、边界、控制流）是**按层实现**的，
> 它们不通过 YAML 开关。

### 1.1 Style 规则子目录分布（实测）

按 `plumb-style/src/main/java/org/plumb/style/**` 里声明了 `implements AstChecker` /
`extends AstChecker` 的类统计：

| 子目录 | 数量 | 主题 |
| --- | ---: | --- |
| `plumb-style/style/` | **354** | 编码风格（注释、Javadoc、缩进、imports、控制流、声明） |
| `plumb-style/performance/` | **63** | 性能（字符串、boxed、BigDecimal、Random） |
| `plumb-style/naming/` | **51** | 命名约定（类型、方法、字段、参数、局部变量、包） |
| `plumb-style/security/` | **42** | 安全相关（deserialization、URL sanitization） |
| `plumb-style/error/` | **35** | 错误处理（catch、finally、throw、synchronized） |
| `plumb-style/bug/` | **20** | 明确 bug 模式（compareTo、equals、null 检查） |
| `plumb-style/design/` | **18** | 设计度量（圈复杂度、耦合、深度） |
| `plumb-style/quality/` | **10** | 质量（regex confusion、ORM 模式） |
| `plumb-style/concurrency/` | **2** | 并发（servlet 原子性、singleton race） |
| `plumb-style/`（根目录） | **2** | 不属于任何子目录分类 |
| **合计** | **597** | — |

`CheckerCategories.CHECKER_CATEGORIES` 里登记了 **594** 条。

### 1.2 外部工具等价覆盖

每条 Plumb 规则通过 `RuleDef.aliases` 字段携带 PMD / SpotBugs / SonarQube / Checkstyle
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
| **Style**（AST-only） | `plumb-style/` | **597** 个 checker 类 | 仅 JavaParser | 编码风格、命名、纯结构 bug |
| **DFA** | `plumb-dfa/` | **47** 个继承 `TaintedCheckerBase` + **20** 个直接 `implements DfaChecker` | TAC + CFG（污点 / 数值 / 资源 / 控制流） | 需跨语句 def-use、type-tracking、source-to-sink 数据流 |

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

`TaintRuleRegistry` 当前有 **13** 条 taint rule：
`dfa-taint-sql-injection`、`dfa-taint-untrusted-source`、`dfa-taint-ldap-injection`、`dfa-taint-command-injection`、`dfa-taint-format-string`、`dfa-taint-xss`、`dfa-taint-log-injection`、`dfa-taint-path-traversal`、`dfa-taint-ssrf`、`dfa-taint-xxe`、`dfa-taint-unsafe-deserialization`、`dfa-taint-weak-crypto`、`dfa-taint-weak-prng`。

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
| Taint rule（registry） | `dfa-taint-sql-injection` | `dfa-taint-<bare>` | `TaintRuleRegistry` 的 13 条 |
| RuleRegistry UUID | `bu-0401-weak-crypto` | `bu-<NNNN>-<bare>` | `plumb-rules/.../RuleRegistry.java` |

实测 `plumb-style/src/main/java` 下 579 个 `new RuleId("ast-...")` 字面量里，
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

## 5. Taint 规则（13 条）

Taint 规则由 `TaintRuleRegistry` 管理，与 sink family 分开配置：
sink family 决定"哪些调用是 sink"，taint rule 决定"从哪个 source 到哪个 sink 算一条问题"。

| 规则 ID | 严重度 | CWE |
| --- | --- | --- |
| `dfa-taint-sql-injection` | — | — |
| `dfa-taint-untrusted-source` | — | — |
| `dfa-taint-ldap-injection` | — | — |
| `dfa-taint-command-injection` | — | — |
| `dfa-taint-format-string` | — | — |
| `dfa-taint-xss` | — | — |
| `dfa-taint-log-injection` | — | — |
| `dfa-taint-path-traversal` | — | — |
| `dfa-taint-ssrf` | — | — |
| `dfa-taint-xxe` | — | — |
| `dfa-taint-unsafe-deserialization` | — | — |
| `dfa-taint-weak-crypto` | — | — |
| `dfa-taint-weak-prng` | — | — |

## 6. 启用 / 禁用

DFA checker 的开关在 `plumb-dfa/src/main/resources/checkers.yaml`；style 规则的开关通过
`RuleDef.enabled()`，由 `CheckerCategories` 登记时确定。

**不要用 `plumb-rules` 的 `RuleRegistry.java` 推断规则总数**：它有 89 处
`register(...)`，其中大量 `org.plumb.dfa.checker.*` / `org.plumb.dfa.taint.*` 类名在
`plumb-dfa` 下并不存在（例如指向 `org.plumb.dfa.taint.TaintAnalysis`），该 registry 已陈旧。

## 7. 相关文档

- [`RULE-AUTHORING.md`](RULE-AUTHORING.md) —— 怎么加一条规则
- [`RULE-STYLE-OP.md`](RULE-STYLE-OP.md) —— 规则运维手册
- [`RULE-TOOL-COMPARISON.md`](RULE-TOOL-COMPARISON.md) —— 与主流工具的对比
- [`../tech/STYLE-RULES.md`](../tech/STYLE-RULES.md) —— style 规则的设计约束
- [`../../README.md`](../../README.md) —— 快速上手
