# plumb 文档索引

`docs/` 只放**产品文档**：描述系统**是什么、怎么工作、怎么扩展**。全部以当前代码为准，
改代码就要改对应文档。

判断一份内容该不该进这里：**如果它在一次重构后仍然成立，它属于产品文档。**
反之（记录某次改动、某轮审计、某次评测）就不要写进来——那类内容一旦沉淀进产品文档就会
变成无法验证的断言。

---

## `tech/` — 技术架构

| 文件 | 内容 |
| --- | --- |
| [`ARCHITECTURE.md`](tech/ARCHITECTURE.md) | 全局架构：7 阶段流水线、模块依赖图、checker 实现形态、关键设计决策、Build-system 自动发现、rule-id 一致性、扩展点、测试体系 |
| [`TAC.md`](tech/TAC.md) | 三地址码格式：IR 类、指令集、CFG、序列化 |
| [`DATA-FLOW.md`](tech/DATA-FLOW.md) | **数据流分析**：引擎骨架、fact 域、污点 / 边界 / 指向 / 控制流四类分析、可空性三值语义、Z3 的真实作用、call graph 策略 |
| [`CROSS-FUNCTION-ANALYSIS.md`](tech/CROSS-FUNCTION-ANALYSIS.md) | 跨函数分析主干：summary 机制、调用图视角 |
| [`CROSS-FUNCTION-OPERATION.md`](tech/CROSS-FUNCTION-OPERATION.md) | `Operation` 体系：常量传播 / 指向 / null / 数值范围 / 副作用的 summary 侧与消费侧 |
| [`CROSS-FUNCTION-AI-TRACE.md`](tech/CROSS-FUNCTION-AI-TRACE.md) | 周边：并行 worklist、跨文件验证、已知限制、AI 增强层、Re-Act 框架 |
| [`DFA-AUGMENTATION-DESIGN.md`](tech/DFA-AUGMENTATION-DESIGN.md) | 为什么 AST 不够用，以及 DFA 增强技术 |
| [`STYLE-RULES.md`](tech/STYLE-RULES.md) | 风格类实现：`AstDispatcher`、`AstCheckerRegistry`、典型 checker 模式 |
| [`KOTLIN-DSL.md`](tech/KOTLIN-DSL.md) | **Kotlin DSL AST 规则引擎**：规则文件格式、匹配器语言、编译/求值模型、`--dsl-rules` 用法 |
| [`MODULE-MERGE.md`](tech/MODULE-MERGE.md) | 模块结构与依赖边界 |
| [`CACHE.md`](tech/CACHE.md) | 磁盘 IR 缓存：`--output-dir` 机制、文件格式、key、兼容性 |

## `rules/` — 规则

| 文件 | 内容 |
| --- | --- |
| [`RULES.md`](rules/RULES.md) | 主清单：数量概览（数字均从配置文件实测）、严重度 / 类型 / emit-id、taint 与 DFA checker |
| [`RULE-STYLE-OP.md`](rules/RULE-STYLE-OP.md) | Style 规则操作：分布、5 层启用/禁用机制、改规则参数 |
| [`RULE-AUTHORING.md`](rules/RULE-AUTHORING.md) | 规则开发：加新规则的步骤、CWE 标签一致性、AST checker 选型 |
| [`RULE-TOOL-COMPARISON.md`](rules/RULE-TOOL-COMPARISON.md) | Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle |
| [`plumb-style-rules.md`](plumb-style-rules.md) | 自动生成：每条 style rule 的 Javadoc + 外部工具等价映射 |
| [`plumb-style-rules-index.md`](plumb-style-rules-index.md) | 自动生成：style rule 索引 |

## `usage/` — 使用

| 文件 | 内容 |
| --- | --- |
| [`CLI.md`](usage/CLI.md) | CLI 完整手册：flag 列表、YAML schema、AI augmentation、退出码、故障排查 |
| [`CLI-EXAMPLES.md`](usage/CLI-EXAMPLES.md) | 典型场景示例 + 命令与默认配置对照 |
| [`CLI-REFERENCE.md`](usage/CLI-REFERENCE.md) | 项目路径族 / build-system 自动发现 / YAML v2 速查 |
| [`ENVIRONMENT.md`](usage/ENVIRONMENT.md) | 环境变量与 AI 配置：代码实际读取哪些变量、AI provider 配置、CI 示例 |

## `build/` — 打包发布

| 文件 | 内容 |
| --- | --- |
| [`PACKAGE.md`](build/PACKAGE.md) | 发布形态、启动器逻辑、smoke test、跨平台注意 |

## 端到端测量

| 文件 | 内容 |
| --- | --- |
| [`../testcase/SUPPORT-MATRIX.md`](../testcase/SUPPORT-MATRIX.md) | 每个 testcase category 的实测 TP / TN 数字与 known-limitation |
| [`../testcase/README.md`](../testcase/README.md) | 怎么跑一个 category、harness 的语义、case 状态定义 |

支持矩阵的数字由 `scripts/write_support_matrix.py` 从各 category 的 `RESULT.json` 生成，
不要手改。

---

## 快速链接

| 问题 | 去哪 |
| --- | --- |
| 第一次用 | [`usage/CLI.md`](usage/CLI.md) |
| 具体场景 | [`usage/CLI-EXAMPLES.md`](usage/CLI-EXAMPLES.md) |
| flag / YAML 写法 | [`usage/CLI-REFERENCE.md`](usage/CLI-REFERENCE.md) |
| 环境变量 / API key | [`usage/ENVIRONMENT.md`](usage/ENVIRONMENT.md) |
| 有哪些规则 | [`rules/RULES.md`](rules/RULES.md) |
| 加自定义规则 | [`rules/RULE-AUTHORING.md`](rules/RULE-AUTHORING.md)、[`tech/ARCHITECTURE.md`](tech/ARCHITECTURE.md) §10 |
| 用 Kotlin DSL 写 AST 规则 | [`tech/KOTLIN-DSL.md`](tech/KOTLIN-DSL.md) |
| 系统整体怎么跑 | [`tech/ARCHITECTURE.md`](tech/ARCHITECTURE.md) |
| **数据流分析怎么工作** | **[`tech/DATA-FLOW.md`](tech/DATA-FLOW.md)** |
| 指向分析 / 可空性 / 污点 | [`tech/DATA-FLOW.md`](tech/DATA-FLOW.md) §3–§5 |
| 跨函数怎么总结 | [`tech/CROSS-FUNCTION-ANALYSIS.md`](tech/CROSS-FUNCTION-ANALYSIS.md) |
| 磁盘缓存 | [`tech/CACHE.md`](tech/CACHE.md) |
| 出包上线 | [`build/PACKAGE.md`](build/PACKAGE.md) |
| 跟其他工具怎么比 | [`rules/RULE-TOOL-COMPARISON.md`](rules/RULE-TOOL-COMPARISON.md) |
| 精确率 / 召回率现状 | [`../testcase/SUPPORT-MATRIX.md`](../testcase/SUPPORT-MATRIX.md) |

---

## 维护约定

1. **引用类名必须能在源码里搜到。** 文档里出现的每个 Java 类都要真实存在——重构会静默地
   让引用失效（包路径段被删是最常见的一种）。
2. **数字不要手写。** 规则数、source/sink 模式数、testcase 得分都有生成或测量路径；
   写不出测量命令的数字就不要写。
3. **改动架构时同步改文档。** `DATA-FLOW.md` 描述引擎骨架，`ARCHITECTURE.md` 描述模块边界；
   改动其中之一必须检查另一处是否还在说同一件事。
4. **不要写轮次流水。** 变更记录属于 git log，不属于 `docs/`。文档只写「现在是什么样」。
5. **过程文档不进 `docs/`。** 审计报告、覆盖率快照、优化路径这类内容要么不留，要么把**已经
   验证过**的结论直接写进对应的产品文档，并去掉轮次编号。

---

## 其他位置

- `testcase/config/min.yaml` — 最小配置示例：`inputs` 指向仓库内的样例源码，可直接运行。