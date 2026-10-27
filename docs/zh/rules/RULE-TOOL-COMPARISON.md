# 规则对比：Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle

[English](../../rules/RULE-TOOL-COMPARISON.md) | [中文](../README.md)

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
| **主实现** | TAC IR + 596 个 AST checker + 12 条 taint rule + 67 个已注册 DFA checker | AST | AST + symbol table | 字节码扫描 | 抽象语法树 + 路径敏感 | AST + 文本匹配 |
| **Taint 引擎** | summary-based 跨函数数据流（两阶段） | 无完整 taint | SonarCFamily 引擎（商业） | 部分数据流 | 商业引擎 | 无 |
| **跨函数分析** | 两阶段 summary：Phase 1 path-insensitive → Phase 2 flow-sensitive + Z3 路径条件 | 有限 | 部分（call graph 1-hop） | 部分（BugInstance 共享） | 全局 | 无 |
| **AST 检查器** | 596 个（JavaParser） | 数百条 | 数百条 | — | — | 一百多条 |
| **DFA checker** | 67 个已注册 | 部分 | 部分 | 字节码 bug pattern | 有 | 无 |
| **Z3 路径条件** | 有（Phase 2 per-finding 查询，用于误报过滤） | 无 | 无 | 无 | 有（商业） | 无 |
| **AI 集成** | 可选 Re-Act agent（12 个 tool） | 无 | SonarAI（实验） | 无 | Coverity Assist | 无 |
| **rule → CWE 映射** | 规则自带，覆盖 59 个 CWE | rule 自带 | rule 自带 | BugPattern 自带 | checker 自带 | check 自带 |

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
| **AST checker（`plumb-style`）** | 596 | 规则类，看 AST 形态 |
| **`CheckerCategories` 登记** | 594 | 上面这些里被登记进规则表的 |
| **DFA checker（已注册）** | 67 | 引擎实际装配的，涉及数据流 / 跨函数 |
| **其中 `extends TaintedCheckerBase`** | 47 | 继承统一污点传播基类 |
| **事实种类（fact kinds）** | 27 | 内置 15 个 + `fact-kinds.yaml` 12 个 |

**分工原则**：只依赖 AST 形态的规则放在 `plumb-style`。用 DFA 实现 style rule 既不提升精度
也不提升召回，只是把"数 AST 节点"的逻辑硬塞进 IR visitor。

完整的 DFA checker 分类见 [`../tech/CROSS-FUNCTION-AI-TRACE.md`](../tech/CROSS-FUNCTION-AI-TRACE.md) §12。

## 2. 规则覆盖面

### 2.1 Plumb 的规则构成

| 类别 | 数量 | 来源 |
| --- | ---: | --- |
| AST checker 类 | 596 | `plumb-style/**` |
| 其中登记进规则表 | 594 | `CheckerCategories` |
| 已注册规则总数 | 1148 | AST + DFA 的实际注册 |
| DFA checker（已注册） | 67 | `ScanRunner.buildDfaCheckers()` |
| Taint rule | 12 | `TaintRuleRegistry` |
| Taint source family / 模式 | 5 / 19 | `sink-families.yaml` 的 `taint.sources.*` |
| Taint sink family / 模式 | 34 / 348 | `sink-families.yaml` 的 `*.sinks` |
| 输入源模式（框架入口） | 65 | `InputSourcePatterns` |
| 事实种类 | 27 | 内置 + YAML |

### 2.2 关键 CWE 覆盖

以下只列 Plumb 侧的机制，**不与其他工具逐条对比** —— 那些数字来自外部资料，无法复现。

| CWE | Plumb 侧的实现 |
| --- | --- |
| CWE-22 路径穿越 | taint sink（`url.sinks` / `file.sinks`） |
| CWE-78 命令注入 | taint sink |
| CWE-89 SQL 注入 | taint sink（`sql.sinks`）+ MyBatis `${}` 原始插值检查（AST 层） |
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
| 工具 | 12 个已注册（源码里有 14 个 tool 类，含 `Tool` 接口本身） |
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
