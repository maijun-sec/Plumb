# plumb 文档索引

`docs/` 目录是 Plumb 项目的**统一文档中心**（R-846 整合后），按主题分子目录组织。

## 目录

| 子目录 | 用途 |
| --- | --- |
| `build/` | **构建**与发布：3 种发布形态、启动器逻辑、smoke test、跨平台注意 |
| `usage/` | **使用**文档：20 个 CLI flag、AI augmentation、YAML schema、典型场景 |
| `rules/` | **规则**清单：720+ 规则、启用/禁用、参数、emit-id、CWE 映射 |
| `tech/` | **技术**文档：架构、TAC 格式、数据流算法、风格类实现、模块合并、缓存 |

## 文件清单

### `build/`

| 文件 | 内容 |
| --- | --- |
| [`PACKAGE.md`](build/PACKAGE.md) | **3 种发布形态**：瘦发布包 (`scripts/package.sh`)、slim (`scripts/package-slim.ps1`)、解压即用 (`scripts/dist-jlink-{linux,windows}.sh`)。包含 `bin/s` + `bin/s.bat` 启动器逻辑（bundled jre fallback）、`conf/default-config.yaml` 字段详解、4 个 smoke test、跨平台注意、jlink 14 个模块集 |

### `usage/`

| 文件 | 内容 |
| --- | --- |
| [`CLI.md`](usage/CLI.md) | CLI 完整手册：20 个 flag（含 R797 `--source-root`/`--module-root`/`--classpath` 和 R833 `--build-system`/`--project-root`）、YAML schema（simple bool + sub-dict 形式）、AI augmentation、退出码、环境变量、故障排查 |
| [`CLI-EXAMPLES.md`](usage/CLI-EXAMPLES.md) | 9 个典型场景示例 + 命令与默认配置对照（CLI.md §6 + §13 拆分） |
| [`CLI-REFERENCE.md`](usage/CLI-REFERENCE.md) | 项目路径族 / build-system 自动发现 / YAML v2 速查（CLI.md §10-§12 拆分） |

### `rules/`

| 文件 | 内容 |
| --- | --- |
| [`RULES.md`](rules/RULES.md) | **主清单**：720+ 规则（27 taint + 66 DFA unified + 590 style + 36 legacy DFA），数量 / 严重度 / 类型 / emit-id 三种风格 / taint / DFA 检查器。§1.2 工具覆盖（R294 4 工具 95%+ / 2436 rules）、§1.3 4 大 taxonomy class（A=AST 515 / B=DFA-direct 69 / C=Taint-primary 42 / D=AI-primary 0）、§1.4 style vs DFA vs Taint 差异 |
| [`RULE-STYLE-OP.md`](rules/RULE-STYLE-OP.md) | **Style 规则操作**：590 条 9 大子目录分布、启用/禁用（`checks.*` + `rules.enabled/disabled`）、改规则参数（当前 + planned YAML）、CLI 临时禁用。RULES.md §7-§9 拆分 |
| [`RULE-AUTHORING.md`](rules/RULE-AUTHORING.md) | **规则开发**：AI prompt 三级覆盖链、CWE 标签与 mapping 一致性（Lesson 1318）、外部工具等价 ID、加新规则的具体步骤（style / DFA unified / Taint 三类）。RULES.md §10-§13 拆分 |
| [`RULE-TOOL-COMPARISON.md`](rules/RULE-TOOL-COMPARISON.md) | **全方位对比**：Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle — 实现机制 / CWE 覆盖 / Juliet corpus 实测 / 误报率 / 性能 / AI 集成 / Plumb 定位 |
| [`plumb-style-rules.md`](plumb-style-rules.md) | **自动生成**：每条 style rule 的 Javadoc + PMD/SpotBugs/SonarQube/Coverity 等价映射（5600+ 行） |
| [`plumb-style-rules-index.md`](plumb-style-rules-index.md) | **自动生成**：style rule 索引（508 行） |

### `tech/`

| 文件 | 内容 |
| --- | --- |
| [`ARCHITECTURE.md`](tech/ARCHITECTURE.md) | 全局架构：阶段化流水线（7 phase）、模块依赖图、R-797 项目路径族解耦、R-833 BuildAdapter、R-836/R-837 rule-id 三 fix（mapping gap + CheckerCategories 591 entries + ScanRunner isRuleIdAllowed 双向 normalize）、§3.0 DFA 引擎 SPI 与 5 大分类、11 个扩展点、§12 全 Juliet corpus 评估调度约束 |
| [`TAC.md`](tech/TAC.md) | 三地址码格式：IR 类、指令集、CFG、序列化、与 SSA 的差距 |
| [`DATA-FLOW.md`](tech/DATA-FLOW.md) | 数据流算法：IFDS taint + 区间抽象 + 跨函数 + Z3 |
| [`CROSS-FUNCTION-ANALYSIS.md`](tech/CROSS-FUNCTION-ANALYSIS.md) | **跨函数分析主干**：两阶段（Phase 1 自底向上 path-insensitive summary / Phase 2 自顶向下 fully context-sensitive + path-sensitive via Z3）、CallGraph 视角、Phase 1+2 详细设计。原文 89 KB 拆 3 份 |
| [`DFA-AUGMENTATION-DESIGN.md`](tech/DFA-AUGMENTATION-DESIGN.md) | **DFA 增强设计**：为什么 AST 不够用（4 类漏报场景）、4 种 DFA 增强技术（FunctionSummary / MOVE alias / BRANCH null / inter-procedural field tracking）、5 个 R540-DA/R541-DA/R542-DA/R543-DA/R560-DA/R561-DA checker 详解、AST/DFA 并存设计决策。源 `docs/COV_JAVA_DFA_AUGMENTATION.md` |
| [`CROSS-FUNCTION-OPERATION.md`](tech/CROSS-FUNCTION-OPERATION.md) | **Operation 体系与 6 套子分析**：常量传播 / 指向 / null / 数值范围 / Z3 / 副作用，summary 端 → Phase 2 消费侧梳理（原文 §5 拆分） |
| [`CROSS-FUNCTION-AI-TRACE.md`](tech/CROSS-FUNCTION-AI-TRACE.md) | **周边**：R217 并行 worklist / IFDS 对比 / 跨文件验证 / 已知限制 / Trace 覆盖率审计 4 bug / plumb-dfa → plumb-dfa 迁移 / AI 增强层 (plumb-ai) / Re-Act 框架（原文 §6-§14 拆分） |
| [`STYLE-RULES.md`](tech/STYLE-RULES.md) | 风格类实现：AstDispatcher、StyleCheckRegistry、9 大类、典型 checker 模式 |
| [`MODULE-MERGE.md`](tech/MODULE-MERGE.md) | 模块合并：plumb-parser 合并失败的复盘、最终方案、依赖图 |
| [`CACHE.md`](tech/CACHE.md) | 磁盘 IR 缓存：--output-dir 机制、文件格式、key、模式、兼容性 |

## 快速链接

- 第一次用？ → `usage/CLI.md`
- 想看具体场景？ → `usage/CLI-EXAMPLES.md`（9 个典型场景 + 默认配置对照）
- 想查具体 flag / YAML v2 写法？ → `usage/CLI-REFERENCE.md`
- 想知道所有规则？ → `rules/RULES.md` 主清单 + `rules/RULE-STYLE-OP.md` 590 style 操作 + `rules/RULE-AUTHORING.md` 加新规则
- 想加自定义规则？ → `rules/RULE-AUTHORING.md` 或 `tech/ARCHITECTURE.md` §10.1
- 出包上线？ → `build/PACKAGE.md`
- 想知道模块为什么这么拆？ → `tech/MODULE-MERGE.md`
- 想优化大型仓库扫描？ → `tech/CACHE.md`
- 跨函数分析怎么工作？ → `tech/CROSS-FUNCTION-ANALYSIS.md` 主干 + `CROSS-FUNCTION-OPERATION.md` 6 套子分析 + `CROSS-FUNCTION-AI-TRACE.md` 周边
- emit-id 不匹配 / RuleRegistry 缺规则？ → `rules/RULE-AUTHORING.md` §2（Lesson 1318）+ `tech/ARCHITECTURE.md` §8
- 跟 PMD / SonarQube / SpotBugs / Coverity / Checkstyle 怎么比？ → `rules/RULE-TOOL-COMPARISON.md`
- 想了解 DFA 增强 vs AST 的取舍？ → `tech/DFA-AUGMENTATION-DESIGN.md`

## 与 `_archive/` 的关系

仓库根 `docs/` 目录是 Plumb 项目的**统一文档中心**（R-846 整合后）。按主题分子目录组织：

- `tech/` — 技术架构 / TAC IR / 数据流 / 跨函数 / DFA 增强 / 缓存
- `rules/` — 规则清单 / 工具对比 / Style 规则操作 / 规则开发
- `usage/` — CLI 完整手册 + 典型场景 + 速查
- `build/` — 打包 / 发布
- `process/` — R* 总结类过程文档（~18 文件，R-840 整合 + R-847 清 27 个 + R-848 清 17 个纯过程流水）
- `archive/` — R* 单 bug 流水（~267 文件，R-843 移入，R-844 统一到根目录，R-846 改名为 `archive/`）
- `plumb-style-rules*.md` — 自动生成（5600+ 行 / 508 行索引）

`fathom-dfa/docs/` 是其他项目的早期架构参考（13 个 R1-R24）。R1117 起它**不再进版本库**——Plumb 是独立的 Java-only 项目，那些文件记录的是另一个代码库。文件仍在磁盘上，只是不再被 track。

`plumb-dfa/src/main/resources/docs/DFA_ARCHITECTURE.md` 是源码内嵌资源（classpath 加载），**不参与本目录**。

未来要找回某个 R* 总结：`mv docs/process/<file>.md docs/<subdir>/` 即可。
未来要找回某个 R* 单 bug：`mv docs/archive/docs-R<x00>-R<x99>/<file>.md docs/` 即可。

## R-836 / R-837 后 docs/ 的更新点（2026-10-16）

| 文档 | 主要更新 |
| --- | --- |
| `build/PACKAGE.md` | 加 R-? `dist-jlink-{linux,windows}.sh` 解压即用包流程；slim dist；启动器三级 fallback 逻辑；默认 conf 字段详解 |
| `usage/CLI.md` | 加 R-797 三个项目路径 flag（`--source-root` / `--module-root` / `--classpath`）；R-833 build-system adapter；checks.* sub-dict 形式；R-836 emit-id 三种风格；rules.parameters planned 路径；aggressiveness 废弃说明 |
| `tech/ARCHITECTURE.md` | 加 R-797 路径族解耦、R-833 BuildAdapter、R-836/R-837 三 fix（mapping 393 entries / CheckerCategories 591 entries / isRuleIdAllowed 双向 normalize）、模块依赖图、§12 全 Juliet corpus 评估调度约束（task 30 min 上限 + 21 GB 磁盘 + 4 批分批 + 单 corpus 体积实测 49–55%）、§3.0 DFA 引擎 SPI（DfaChecker / TaintedCheckerBase / PathSensitiveCheckerBase）+ 5 大性质分类（DFA-真 / 注解通道 / taint / SRC-正则 / AI-启发） |
| `tech/TAC.md` | 加 R261 try/catch/finally 语义（CATCH_HANDLER NNE 透传 / FINALLY_ENTER exception edge / 旧 L_CATCH_* 标签兼容） |
| `rules/RULES.md` | 加 §1.2 工具覆盖（R294 4 工具 95%+ / 2436 rules）、§1.3 4 大 taxonomy class（A=AST 515 / B=DFA-direct 69 / C=Taint-primary 42 / D=AI-primary 0）、§1.4 style vs DFA vs Taint 命名 + 注册机制差异 |
| `README.md` (根) | 本索引页：文件清单更新、新增 "R-836/R-837 后 docs/ 更新点" 表格 |

## R-840 / R-841 docs/ 整理（2026-10-16 夜）

| 文档 | 主要更新 |
| --- | --- |
| `tech/ARCHITECTURE.md` | §3.0 DFA 引擎 SPI（DfaChecker / TaintedCheckerBase / PathSensitiveCheckerBase）+ 5 大分类（DFA-真 / 注解通道 / taint / SRC-正则 / AI-启发 / STUB / HELPER），来自 `plumb-dfa/docs/AUDIT-checkers-2026-10-12.md` §0 |
| `tech/TAC.md` | R261 try/catch/finally 完整语义（CATCH_HANDLER NNE 透传 / FINALLY_ENTER exception edge / 旧 L_CATCH_* 标签兼容），来自 `docs/R261-TRY-CATCH-FINALLY-IR-AND-DATAFLOW.md` |
| `rules/RULES.md` | §1.2 工具覆盖（R294 4 工具 95%+）/ §1.3 4 大 taxonomy class（626 checker）/ §1.4 style vs DFA vs Taint 差异，来自 `docs/R294-FINAL-COVERAGE-REPORT.md` + `docs/CHECKERS.md` |
| `tech/CROSS-FUNCTION-ANALYSIS.md` | **拆分**：原 89 KB 拆为 3 份 — `CROSS-FUNCTION-ANALYSIS.md` (20 KB §1-4 主干) + `CROSS-FUNCTION-OPERATION.md` (10 KB §5 Operation 体系) + `CROSS-FUNCTION-AI-TRACE.md` (54 KB §6-14 周边) |
| `usage/CLI.md` | **拆分**：原 28 KB 拆为 3 份 — `CLI.md` (28 KB §1-5+§6-8 主干) + `CLI-EXAMPLES.md` (4 KB §6 场景 + §13 配置) + `CLI-REFERENCE.md` (6 KB §10-12 速查) |
| `rules/RULES.md` | **拆分**：原 23 KB 拆为 3 份 — `RULES.md` (18 KB §1-6 主清单) + `RULE-STYLE-OP.md` (7 KB §7-9 Style 操作) + `RULE-AUTHORING.md` (4 KB §10-13 开发) |
| `README.md` (根) | 本索引页：文件清单全面更新、9 个新文件 + 快速链接调整 |

## R-842 工具对比 + 实现思路文档（2026-10-17）

| 文档 | 主要更新 |
| --- | --- |
| `rules/RULE-TOOL-COMPARISON.md` (新) | **全方位对比**：Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle — 实现机制（核心差异：两阶段 summary 是 Plumb 独有）/ CWE 覆盖（14 个关键 CWE 对比，Plumb 优势 CWE-94 4 sink / CWE-416 UAF / CWE-190 Z3）/ Juliet corpus 实测（Plumb cwe_matched prec 0.4537 vs R791 0.4378）/ 误报率（disable 6 noise rule）/ 性能（单 CWE 5-10 min / 全 112 corpus 10-18 h）/ AI 集成（plumb-ai Re-Act 独有）/ Plumb 定位（覆盖开源 4 工具 95%+ + 跨函数 taint + Z3 路径条件） |
| `tech/DFA-AUGMENTATION-DESIGN.md` (新) | **DFA 增强设计**：为什么 AST 不够用（4 类漏报场景：helper method chain / ctor chaining / aliasing / 跨方法字段访问）/ 4 种通用 DFA 增强技术（FunctionSummary / MOVE alias 传播 / BRANCH null 比较 / inter-procedural field tracking）/ 5 个 R540-DA/R541-DA/R542-DA/R543-DA/R560-DA/R561-DA checker 详解（含 ORM/单例/Servlet 场景）/ AST/DFA 并存设计决策（不同 rule id + yaml 二选一）/ AST vs DFA 判定准则 / R585 aggressiveness knob（已废弃） |
| `rules/RULES.md` §1.2 | 加交叉引用 `**完整对比见 RULE-TOOL-COMPARISON.md**` |
| `rules/RULE-AUTHORING.md` §3 | 加交叉引用 `**全方位对比见 RULE-TOOL-COMPARISON.md**` |
| `README.md` (根) | 本索引页：加 2 个新文件 (RULE-TOOL-COMPARISON + DFA-AUGMENTATION-DESIGN) + 2 个新快速链接 + R-842 整理表格 |

## R-846 统一所有产品 doc 到根 docs/ (2026-10-17)

| 动作 | 结果 |
| --- | --- |
| `doc/` 整体并入 `docs/` | 22 个产品 doc（含 4 个子目录 tech/rules/usage/build）按子目录保留 |
| `docs/` 顶层 R* 总结类 + `docs/architecture/` 2 个 → `docs/process/` | 64 个 R* 总结类过程文档 |
| `plumb-dfa/docs/` 43 个 → `docs/process/` | DFA 引擎 R* 总结 |
| `plumb-rules/docs/` 1 个 → `docs/process/` | rules 模块 RULE_AUTHORING.md |
| `_archive/` (根) → `docs/archive/` | 267 个 R* 单 bug 流水，命名 `{原目录}-R<轮次>` |
| `doc/` + `plumb-dfa/docs/` + `plumb-rules/docs/` + `_archive/` 4 个空目录 | 全删 |
| `fathom-dfa/docs/` | **不动**（用户授权：其他项目） |
| `plumb-dfa/src/main/resources/docs/` | **不动**（源码内嵌 classpath 资源） |
| 目录 | 数量 | 用途 |
| --- | ---: | --- |
| `tech/` | 11 | 架构 / TAC / 数据流 / 跨函数 / DFA 增强 / 缓存 / 模块合并 / style 实现 / 跟踪覆盖审计 |
| `rules/` | 4 | 规则主清单 / Style 操作 / 规则开发 / 工具对比 |
| `usage/` | 3 | CLI 主手册 / 场景示例 / 速查 |
| `build/` | 1 | 打包与发布 |
| `process/` | 18 | R* 总结类过程文档（R-848 清 17 个纯过程流水）|
| `archive/` | 267 | R* 单 bug 流水 |
| `plumb-style-rules*.md` | 2 | 自动生成 |
| **总计** | **309** | **2.9 MB** |

## R-848 整合过程文档到技术文档 (2026-10-17)

| 文档 | 主要更新 |
| --- | --- |
| `rules/RULE-AUTHORING.md` §13.0 (新) | **AST checker Pattern 选型决策**（Pattern A/B/C/D 5 类 + 决策表 + 选型红线 + 587 个 checker 当前分布），来自 `docs/process/R815-AST-SCAN-OPTIMIZATION-REPORT-2026-10-14.md` §7 |
| `rules/RULE-STYLE-OP.md` §8.4 (新) | **5 层 enabled gate 机制**（RuleDef.enabled / RuleRegistry.disabledRuleNames / withBuiltinDefaults / StyleContext.forceEnable / ScanRunner.isRuleIdAllowed），来自 `docs/process/R834-DISABLE-DEFAULT-2026-10-15.md` §2 |
| `tech/ARCHITECTURE.md` §3.2 (新) | **Plumb 关键设计决策 8 条**（TAC String key / CallResolver name-based / Independent catalog / TypeTable / dedup key / 1-hop def chase / synthetic <init> / Annotation IR），来自 `docs/process/ROADMAP-R115-R125.md` |
| `tech/ARCHITECTURE.md` §7.1 (新) | **R-833 关键设计决策 6 条**（零侵入 / ServiceLoader SPI / Parser-light / 默认保守 / Classpath 委派 m2 / multi-module dedup）+ 4 条 Lessons (1290-1293)，来自 `docs/process/R833-BUILD-SYSTEM-ADAPTERS-2026-10-15.md` §7-§8 |
| **bug fix** | `tech/ARCHITECTURE.md` §3.1 标题重复 (line 144/146)，合并去重 |
| 硬删 17 个 | R608-R651 (Aug 2026 旧数据，R-836 已超越) + 12 个 DFA 引擎 R* 单 bug 流水（R804-FOLLOWUP-2 / R806 / R810×2 / R812×3 / R813 / R816/817/818/819 / R820 / R821 / R826 / R831）|
| 保留 18 个 | 4 个唯一史源（AUDIT/CHECKERS/A1-A3/R815-classify + R334-CWE-STATS.csv）+ 4 个 COV_JAVA + 3 个 R115-R125 累计贡献 + 2 个 Juliet 评估 (R334/R832) + 4 个 R836 final + R37-R503/R815/R833/R834 4 个产品级 R* final |

## R-847 清理 docs/process/ (2026-10-17)

| 动作 | 结果 |
| --- | --- |
| 硬删 27 个已 100% 沉到产品 doc 的 .md | 释放 238 KB |
| docs/process/ 从 64 → 35 个文件 | R-840 整合 + R-847 清理 |
| docs/ 总计 | 353 → 326 个文件 / 3.3 MB → 3.1 MB |
| 保留 35 个 | AUDIT-checkers 93KB (DFA SPI 权威源) + CHECKERS 17KB + COV_JAVA 2 个 + 19 个 R* 流水 + 4 个 R* final report + 3 个 R115-R125 累计贡献 |
| 删 27 个 | 7 个 R115-R118/R261 架构（沉 tech/ARCHITECTURE/CROSS-FUNCTION）+ 8 个 R803-R808 v2 trace（沉 CROSS-FUNCTION-AI-TRACE）+ 6 个 R812-R830 治理流水（沉 RULES §1.4）+ R837/RULE_AUTHORING/R354/R358/POST-R188 收尾报告（结论 100% 沉）|
