# plumb 文档索引

`docs/` 是 Plumb 项目的文档中心。文档分两类，**不要混在一起看**：

| 类别 | 目录 | 性质 |
| --- | --- | --- |
| **产品文档** | `tech/` `rules/` `usage/` `build/` | 描述系统**是什么、怎么工作**。以当前代码为准，改代码就要改这里。 |
| **过程文档** | `process/` `archive/` | 记录**怎么走到今天**（某轮改了什么、审计发现了什么、评测数据）。是历史，不是规格。 |

判断一份文档该放哪里的标准很简单：**如果它的内容在一次重构后仍然成立，它属于产品文档；
如果它描述的是「某次改动 / 某次审计 / 某轮评测」，它属于过程文档。**

---

## 产品文档

### `tech/` — 技术架构

| 文件 | 内容 |
| --- | --- |
| [`ARCHITECTURE.md`](tech/ARCHITECTURE.md) | 全局架构：7 阶段流水线、模块依赖图、checker 实现形态、关键设计决策、Build-system 自动发现、rule-id 一致性、扩展点、测试体系 |
| [`TAC.md`](tech/TAC.md) | 三地址码格式：IR 类、指令集、CFG、序列化 |
| [`DATA-FLOW.md`](tech/DATA-FLOW.md) | **数据流分析**：引擎骨架、fact 域、污点 / 边界 / 指向 / 控制流四类分析、可空性三值语义、Z3 的真实作用、call graph 策略 |
| [`CROSS-FUNCTION-ANALYSIS.md`](tech/CROSS-FUNCTION-ANALYSIS.md) | 跨函数分析主干：summary 机制、调用图视角 |
| [`CROSS-FUNCTION-OPERATION.md`](tech/CROSS-FUNCTION-OPERATION.md) | `Operation` 体系：常量传播 / 指向 / null / 数值范围 / 副作用的 summary 侧与消费侧 |
| [`CROSS-FUNCTION-AI-TRACE.md`](tech/CROSS-FUNCTION-AI-TRACE.md) | 周边：并行 worklist、跨文件验证、已知限制、AI 增强层、Re-Act 框架 |
| [`DFA-AUGMENTATION-DESIGN.md`](tech/DFA-AUGMENTATION-DESIGN.md) | 为什么 AST 不够用，以及 DFA 增强技术 |
| [`STYLE-RULES.md`](tech/STYLE-RULES.md) | 风格类实现：`AstDispatcher`、`StyleCheckRegistry`、典型 checker 模式 |
| [`MODULE-MERGE.md`](tech/MODULE-MERGE.md) | 模块合并方案与依赖图 |
| [`CACHE.md`](tech/CACHE.md) | 磁盘 IR 缓存：`--output-dir` 机制、文件格式、key、兼容性 |

### `rules/` — 规则

| 文件 | 内容 |
| --- | --- |
| [`RULES.md`](rules/RULES.md) | 主清单：数量概览（数字均从配置文件实测）、严重度 / 类型 / emit-id、taint 与 DFA checker |
| [`RULE-STYLE-OP.md`](rules/RULE-STYLE-OP.md) | Style 规则操作：分布、启用/禁用机制、改规则参数 |
| [`RULE-AUTHORING.md`](rules/RULE-AUTHORING.md) | 规则开发：加新规则的步骤、CWE 标签一致性、AST checker 选型 |
| [`RULE-TOOL-COMPARISON.md`](rules/RULE-TOOL-COMPARISON.md) | Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle |
| [`plumb-style-rules.md`](plumb-style-rules.md) | 自动生成：每条 style rule 的 Javadoc + 外部工具等价映射 |
| [`plumb-style-rules-index.md`](plumb-style-rules-index.md) | 自动生成：style rule 索引 |

### `usage/` — 使用

| 文件 | 内容 |
| --- | --- |
| [`CLI.md`](usage/CLI.md) | CLI 完整手册：flag 列表、YAML schema、AI augmentation、退出码、故障排查 |
| [`CLI-EXAMPLES.md`](usage/CLI-EXAMPLES.md) | 典型场景示例 + 命令与默认配置对照 |
| [`CLI-REFERENCE.md`](usage/CLI-REFERENCE.md) | 项目路径族 / build-system 自动发现 / YAML v2 速查 |

### `build/` — 打包发布

| 文件 | 内容 |
| --- | --- |
| [`PACKAGE.md`](build/PACKAGE.md) | 发布形态、启动器逻辑、smoke test、跨平台注意 |

---

## 过程文档

| 目录 | 内容 |
| --- | --- |
| `process/` | R\* 轮次总结、覆盖率分析、审计报告、优化路径 |
| `process/audits/` | checker 分类审计、trace 覆盖率审计 |
| `archive/` | R\* 单次改动流水（按轮次分目录） |

**不要把过程文档当规格引用。** 例如某轮报告写「某 checker 归类为 X」，那是当时的快照；
`tech/ARCHITECTURE.md` 才是当前的分类依据。

---

## 端到端测量

| 文件 | 内容 |
| --- | --- |
| [`../testcase/SUPPORT-MATRIX.md`](../testcase/SUPPORT-MATRIX.md) | 五个 testcase category 的实测 TP / TN 数字与 known-limitation |
| [`../testcase/README.md`](../testcase/README.md) | 怎么跑一个 category、harness 的语义、case 状态定义 |

支持矩阵的数字由 `scripts/write_support_matrix.py` 从各 category 的 `RESULT.json` 生成，
不要手改。

---

## 快速链接

| 问题 | 去哪 |
| --- | --- |
| 第一次用 | `usage/CLI.md` |
| 具体场景 | `usage/CLI-EXAMPLES.md` |
| flag / YAML 写法 | `usage/CLI-REFERENCE.md` |
| 有哪些规则 | `rules/RULES.md` |
| 加自定义规则 | `rules/RULE-AUTHORING.md`、`tech/ARCHITECTURE.md` §10 |
| 系统整体怎么跑 | `tech/ARCHITECTURE.md` |
| **数据流分析怎么工作** | **`tech/DATA-FLOW.md`** |
| 指向分析 / 可空性 / 污点 | `tech/DATA-FLOW.md` §3–§5 |
| 跨函数怎么总结 | `tech/CROSS-FUNCTION-ANALYSIS.md` |
| 磁盘缓存 | `tech/CACHE.md` |
| 出包上线 | `build/PACKAGE.md` |
| 跟其他工具怎么比 | `rules/RULE-TOOL-COMPARISON.md` |
| 精确率 / 召回率现状 | `testcase/SUPPORT-MATRIX.md` |

---

## 维护约定

1. **产品文档以代码为准。** 引用类名必须能在源码里搜到；引用数字必须能从配置或测试跑出来。
2. **数字不要手写。** 规则数、source/sink 模式数、testcase 得分都有生成或测量路径。
3. **改动架构时同步改文档。** `DATA-FLOW.md` 描述引擎骨架，`ARCHITECTURE.md` 描述模块边界；
   改动其中之一必须检查另一处是否还在说同一件事。
4. **过程文档留在 `process/`。** 不要把某轮结论直接搬进 `tech/`——如果它现在仍然成立，
   就用当前代码验证一遍再写进去，并去掉轮次编号。

---

## 其他位置

- `fathom-dfa/docs/` — 另一个项目的早期架构参考，本地保留、不进版本库。
- `plumb-dfa/src/main/resources/docs/` — 源码内嵌资源（classpath 加载），不属于本目录。
- `testcase/config/min.yaml` — 最小配置示例（引用的是历史 demo 路径，仅作字段参考）。