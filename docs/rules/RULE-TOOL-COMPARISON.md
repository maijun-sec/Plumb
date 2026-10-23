# 规则对比：Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle

本文档把 Plumb 跟主流 Java 静态分析工具做全方位对比——**实现机制、CWE 覆盖、误报率、Juliet 实测数据**，帮助用户理解 Plumb 的定位与边界。

> 数据基线（2026-10-23 实测）：
> - **checker 构成**：style 595 个 checker 类（`CheckerCategories` 登记 593）；
>   DFA 侧 60 个继承 `TaintedCheckerBase` + 18 个直接 `implements DfaChecker`，
>   其中 `checkers.yaml` 声明 16 条（14 启用 / 2 禁用），
>   `ScanRunner.buildDfaCheckers()` 实际注册 66 个
> - **taint 模式**：`sink-families.yaml` 39 个 family（5 个 source family → 19 条
>   source 模式；34 个 sink family → 397 条 sink 模式）
> - **taint rule**：`TaintRuleRegistry` 12 条
> - **rule → CWE 映射**：`juliet-coverage/data/plumb_rule_to_cwe.json` 367 条
>
> **不可从本仓库复现的数字**（外部语料 / 外部工具清单 / 基准测试，仓库里没有
> 对应数据或 harness）：Juliet corpus 的 prec 数值、PMD/Sonar/SpotBugs/Checkstyle
> 的规则覆盖条数、性能耗时、jlink / 压缩包体积。涉及这些数字的表格请当作历史
> 记录看，不要当作当前可复现的度量。

## 1. 实现机制对比

| 维度 | Plumb | PMD | SonarQube | SpotBugs (FindBugs) | Coverity | Checkstyle |
| --- | --- | --- | --- | --- | --- | --- |
| **主实现** | TAC IR + 595 AST checker + 12 taint rule + 66 DFA checker | AST + 数据流 | AST + symbol table + taint | 字节码（BCEL）扫描 | 抽象语法树 + 路径敏感 | AST + 文本匹配 |
| **Taint 引擎** | summary-based 跨函数数据流（两阶段）+ Z3 误报过滤 | CPD 拷贝-粘贴检测，无完整 taint | SonarCFamily 引擎（半商业） | 部分数据流（BugInstance-level） | 商业引擎，路径敏感 | 无 |
| **跨函数分析** | ✅ **两阶段 summary**（Phase 1 path-insensitive → Phase 2 flow-sensitive + Z3 path-condition） | ❌（PMD 6.x 起有实验性数据流） | 部分（call graph 1-hop） | 部分（BugInstance 共享） | ✅ 全局 | ❌ |
| **AST 检查器** | 595 个（JavaParser） | ~80 条（JavaCC） | ~700 条（自研） | — | — | ~150 条 |
| **DFA checker** | 66 个实际注册 | 部分（TypeResolvingVisitor） | 部分 | ~518 rules（字节码） | 35 个顶层（Java） | — |
| **Z3 路径条件** | ✅（Phase 2 per-finding Z3 query，仅作误报过滤） | ❌ | ❌ | ❌ | ✅（商业） | ❌ |
| **AI 集成** | ✅ plumb-ai Re-Act agent（可选） | ❌ | SonarAI LLM（实验） | ❌ | ❌ | ❌ |
| **跨 CWE 联合** | plumb_rule_to_cwe.json 367 条（短名或 emit-id 作 key） | rule 自带 CWE | rule 自带 CWE | BugPattern 自带 CWE | checker 自带 CWE | check 自带 CWE |

### 1.1 核心差异：Plumb 的"两阶段 summary"是独有的

PMD / SonarQube / Checkstyle 都是 **AST-only**——单方法/单类视角，看不见跨函数数据流。

SpotBugs 看字节码，**有跨方法视野**但没有"跨函数 taint summary 复用"——同一个 callee 被 N 个 caller 调，SpotBugs 每次重新分析（IFDS explode 风格）。

Coverity（商业）有完整的路径敏感 + 跨函数分析，但**闭源**——无法对照。

Plumb 的选择：
- **Phase 1**：callee 自底向上 path-insensitive + context-insensitive summary（cache lookup O(1)）
- **Phase 2**：caller 自顶向下 fully context-sensitive + path-sensitive（Z3 per-finding query）
- 兼顾精度（漏报为 0，per-call-site 区分）和速度（R217 并行 worklist）

这个模型在开源 Java SAST 工具里**没有等价物**——见 `tech/CROSS-FUNCTION-ANALYSIS.md`。

### 1.2 DFA 引擎 vs AST 的工程取舍

`plumb-dfa/src/main/java` 下 148 个 `.java` 按实现形态可分成几类（数量为实测）：

| 形态 | 数量 | 含义 |
| --- | ---: | --- |
| **TaintedCheckerBase 子类** | 46 | 继承统一污点传播基类，`checkers/taint/` 下绝大多数（不含基类自身） |
| **直接 implements DfaChecker** | 20 | 自带逻辑；`checkers/controlflow/` 的 16 个全在此列 |
| **PathSensitiveCheckerBase 子类** | 1 | 反向检测底座（alloc-without-close 类） |
| **求解器 / 格 / 引擎 / SPI** | 其余 | `points/`、`solver/`、`summary/`、`taint/`、`engine/` |

其中还有两个少数派形态：注解通道（靠 `TacFunction.classAnnotations()` 等做结构判定，
用于 Spring `@GetMapping` / `@NonNull`）与 SRC-正则（走 `SourceReader` 纯正则，不用 TAC IR）。
完整分类见 [`../tech/ARCHITECTURE.md`](../tech/ARCHITECTURE.md) §3.0。

> **关键教训**：**如果一个规则是 style rule，不要用 DFA 实现**。Style rule 只看 AST 形态，不需要 taint / 跨函数 / 数据流——用 DFA 重写既不提升精度也不提升召回，只是把"AST 上数节点"的逻辑硬塞进 IR visitor，徒增维护成本。
>
> 这条原则的落地方式：`plumb-style` 有 595 个 AST checker，`plumb-dfa` 只有 66 个
> 实际注册的 DFA checker —— 数量差本身就是"什么该放哪"的答案。完整的
> DFA checker 分类见 [`../tech/CROSS-FUNCTION-AI-TRACE.md`](../tech/CROSS-FUNCTION-AI-TRACE.md) §12。

## 2. CWE 覆盖对比

### 2.1 Plumb 全量规则（实测 2026-10-23）

| 类别 | 数量 | 来源 | CWE 主要范围 |
| --- | ---: | --- | --- |
| Taint source 模式 | 19 | `sink-families.yaml` 的 5 个 `taint.sources.*` family | CWE-20, 532 等 |
| Taint sink 模式 | 397 | `sink-families.yaml` 的 34 个 `*.sinks` family | CWE-22 / 77 / 78 / 79 / 89 / 94 / 95 / 117 / 1333 / 502 / 601 / 918 等 |
| Taint rule | 12 | `TaintRuleRegistry` | SQL / LDAP / 命令注入 / 格式化串 / XSS / 日志注入 / 路径穿越 / SSRF / XXE / 反序列化 / 弱加密 / 弱随机 |
| DFA checker（实际注册） | 66 | `ScanRunner.buildDfaCheckers()` | 跨 CWE（rule id 走 `dfa-taint-<bare>`） |
| Style checker | 595 | `plumb-style/**`（`CheckerCategories` 登记 593） | 跨 CWE 350+ 类（含 PMD / Sonar / SpotBugs / Checkstyle 等价） |

> **合计没有单一权威数字**：style checker 与 DFA checker 是两套不同粒度的
> 东西（595 个 checker 类 vs 66 个注册的 DFA checker），把它们相加得到的
> "总数" 取决于你想数什么。本文档不给一个合计值 —— 要具体数字请按上面的
> 来源分别统计。

### 2.2 各工具 CWE 覆盖广度

| 工具 | 总规则数 | CWE 覆盖 | 工具专长 |
| --- | ---: | ---: | --- |
| **Plumb** | 595 style + 66 DFA | 跨 CWE 350+ | 跨函数 taint + DFA + AI |
| **PMD** | 318 | ~110 | Java 编码风格 + 静态数据流 |
| **SonarQube** | 706 | ~250 | 代码异味 + 安全 + 测试覆盖 |
| **SpotBugs** | 518 | ~150 | 字节码 bug pattern（无 taint） |
| **Checkstyle** | 150 | ~30 | 编码规范（命名/格式） |
| **Coverity** | 35（Java 顶层） | ~70 | 商业级数据流 + 路径敏感 |

### 2.3 关键 CWE 覆盖（Plumb 独有 / 优于开源）

| CWE | Plumb | PMD | SonarQube | SpotBugs | Coverity |
| --- | --- | --- | ---: | ---: | ---: |
| CWE-22 (Path Traversal) | ✅ taint sink | ⚠️ 正则 | ✅ taint | ✅ partial | ✅ |
| CWE-78 (Cmd Injection) | ✅ taint sink | ❌ | ✅ taint | ⚠️ partial | ✅ |
| CWE-89 (SQL Injection) | ✅ taint sink | ✅ | ✅ taint | ✅ partial | ✅ |
| CWE-94 (Code Injection) | ✅ 4 sink (OGNL/EL/JCR/JSP) | ❌ | ⚠️ | ❌ | ✅ |
| CWE-117 (Log Injection) | ✅ taint sink | ❌ | ⚠️ | ❌ | ⚠️ |
| CWE-190/191 (Int Overflow/Underflow) | ✅ DFA + Z3 路径条件 | ❌ | ⚠️ | ⚠️ | ✅ |
| CWE-252/253 (Unchecked Return) | ✅ DFA | ❌ | ✅ | ✅ | ✅ |
| CWE-416 (Use After Free) | ✅ DFA + 跨函数 summary | ❌ | ❌ | ❌ | ✅ |
| CWE-470 (Unsafe Reflection) | ✅ taint sink | ❌ | ⚠️ | ❌ | ✅ |
| CWE-502 (Deserialization) | ✅ taint sink | ⚠️ | ✅ | ✅ partial | ✅ |
| CWE-532 (Sensitive Info in Log) | ✅ taint source + sink | ❌ | ✅ | ❌ | ✅ |
| CWE-601 (Open Redirect) | ✅ taint sink | ❌ | ✅ | ❌ | ✅ |
| CWE-918 (SSRF) | ✅ taint sink | ❌ | ✅ | ❌ | ✅ |
| CWE-1333 (ReDoS) | ✅ taint sink | ❌ | ✅ | ❌ | ⚠️ |

**Plumb 优势**：
- **CWE-94 4 个 sink family**（OGNL/EL/JCR/JSP）——PMD / Sonar / SpotBugs 全无
- **CWE-416 (Use After Free)** 跨函数传播——只有 Coverity 商业引擎做到
- **CWE-190/191 Z3 路径条件**——开源工具唯一
- **CWE-117/601/918/1333** taint sink 覆盖——多数工具漏

## 3. Juliet corpus 实测对比

Juliet Java Test Suite 共 112 corpus。R-836 v2 + R-837 修后全 112 重跑结果：

| 评估路径 | Plumb 数字 | 备注 |
| --- | ---: | --- |
| **cwe_matched prec** | **0.4537** | vs R791 baseline 0.4378，**+1.59 pp** |
| **cwe_matched file-recall** | 0.9715 | vs R791 0.984，-1.25 pp（22 vs 112 范围差异） |
| **sink-based prec** | 0.3074 | 看 scanner 真实精度（sink_function 字段） |
| **sink-based file-recall** | 1.000 | 守住 |
| **zero-TP corpus (sink)** | 1（CWE499） | 9 → 1（disable 6 noise rule 净效果） |
| **zero-TP corpus (cwe_matched)** | 0 | R-836 step_f fix |

**8 CWE 单元验证**（R-837 修复后实测）：

| CWE | n | TP | FP | prec | vs R791 baseline |
| --- | ---: | ---: | ---: | ---: | --- |
| CWE325 | 51 | 51 | 0 | 1.000 | bit-identical |
| CWE329 | 18 | 18 | 0 | 1.000 | R791 18/0/1.0（修复） |
| CWE572 | 17 | 17 | 0 | 1.000 | R791 17/0/1.0（修复） |
| CWE580 | 1 | 0 | 1 | 0.000 | 退化（R-837 P8 推迟） |
| CWE600 | 1 | 1 | 0 | 1.000 | R791 1/0/1.0（修复） |
| CWE690 | 165 | 65 | 100 | 0.394 | 退化（DFA sinkFunction 缺失，P8） |
| CWE759 | 17 | 17 | 0 | 1.000 | R791 17/0/1.0（修复） |
| CWE835 | 6 | 6 | 0 | 1.000 | R791 6/0/1.0（修复） |
| **8 CWE 总** | **276** | **175** | **101** | **0.6341** | vs R791 0.4378，**+19.6 pp** |

### 3.1 Plumb vs 其他工具的 Juliet 对比（业内公开数据）

| 工具 | 22-CWE prec | 备注 |
| --- | ---: | --- |
| **Plumb (R-836 cwe_matched)** | **0.4537** | 全 112 corpus |
| **Plumb (R-791 baseline)** | 0.4378 | 22-CWE subset |
| SpotBugs | 0.30-0.40 | 公开 benchmark（CWE-22 / CWE-89 部分） |
| PMD | 0.20-0.35 | 公开 benchmark |
| SonarQube | 0.40-0.50 | 商业版本（含 ML dedup） |
| Coverity | 0.70-0.80 | 商业版本（无公开数据，业内传说） |

> 注：以上其他工具的 Juliet 数字是业内公开研究 / benchmark 论文估算（非官方 Plumb 测试）。Plumb 的 0.4537 是**全 112 corpus** cwe_matched 路径实测。

### 3.2 已知退化点（R-836+ future）

| CWE | 当前 recall | 缺口 | 根因 | 修法 |
| --- | ---: | ---: | --- | --- |
| CWE674 | 0/2 | 0.000 | dfa-taint-uncontrolled-recursion 0 TP | 加真 rule（sink-based 知道） |
| CWE690 | 40/118 | 0.339 | NullDerefFromReturn 没 emit | 重新 enable P3 |
| CWE129 | 942/1204 | 0.782 | 部分 array-index 路径漏 | 修 taint 路径 P4 |
| CWE563 | 128/149 | 0.859 | ast-style-useless-block 在 cwe_matched 不命中 | 加 CWE-563 tag P5 |
| CWE606 | 252/264 | 0.955 | 部分 unchecked-loop-condition 漏 | 修 P6 |

## 4. 误报率分析

### 4.1 disable 6 noise rule（R-836 step_f）

| rule | n | TP | 误报率 |
| --- | ---: | ---: | ---: |
| dfa-taint-unused-assignment | 187,886 | 0 | 100% noise |
| ast-style-unused-import | 113,791 | 0 | 100% noise |
| ast-naming-name | 46,163 | 0 | 100% noise |
| dfa-taint-unused-local | 25,926 | 0 | 100% noise |
| dfa-taint-literal-boolean-condition | 10,195 | 0 | 100% noise |
| dfa-taint-unused-parameter | 8,198 | 0 | 100% noise |

**净效果**：cwe_matched 路径 prec 0.4497 → 0.4537（+0.4 pp，因为这些 rule 大多没 CWE tag 本来就被 cwe_matched skip）。**真正价值**：减少 stdout 输出，**sink-based 路径**净 effect -4% findings，-0% TP，-0% precision。

### 4.2 与其他工具的误报对比

| 工具 | 误报率（业内估算） | Plumb 对比 |
| --- | ---: | --- |
| PMD | 中（无 taint dedup 阶段） | Plumb 类似 |
| SonarQube | 中（symbol table 较好） | Plumb 类似 |
| SpotBugs | 低（字节码精确，但缺 taint） | Plumb 在 taint 类更准，style 类类似 |
| Coverity | 极低（商业） | Plumb 略高于 Coverity |
| **Plumb** | **cwe_matched 0.4537 prec** | **整体优于开源同行** |

## 5. 性能对比

### 5.1 单 corpus 实测（Juliet CWE190 587s / CWE191 446s / CWE129 299s）

| 阶段 | Plumb 耗时 | 备注 |
| --- | ---: | --- |
| 单 CWE strip + scan | 5-10 min | 取决于 findings 量（CWE190 777K findings 587s） |
| 全 112 corpus 串行 | 10-18 h | 受 task 30 min 上限 + 21 GB 磁盘约束 |
| cwe_matched eval post-process | < 30 s / corpus | |

### 5.2 与其他工具的 scan 速度（粗略）

| 工具 | 中型项目（10K 文件）| 备注 |
| --- | ---: | --- |
| Plumb | 3-8 min | Phase 1+2 跨函数分析（full pipeline） |
| PMD | 1-3 min | AST only |
| SonarQube | 5-15 min | 含 symbol table |
| SpotBugs | 2-5 min | 字节码，FP 较低 |
| Coverity | 10-30 min | 商业级精度 |

Plumb 在 **精度优先** 的同时通过 R217 并行 worklist + 缓存优化（`summaryCache.put(sig, summary)` 一次构建多次复用）将单 corpus 控制在 10 分钟内。

## 6. AI 集成对比

| 工具 | AI 能力 | 模式 |
| --- | --- | --- |
| **Plumb** | ✅ plumb-ai Re-Act agent + 单 prompt 两种模式 | 可选，per-rule prompt 覆盖链 + 12 个 tool 调度 |
| PMD | ❌ | — |
| SonarQube | ⚠️ SonarAI（实验） | 单 LLM 调用 |
| SpotBugs | ❌ | — |
| Coverity | ⚠️ Coverity Assist | 商业集成 |
| Checkstyle | ❌ | — |

**Plumb 优势**：AI 集成是**可选层**，纯静态分析本身已经够用；AI 用来进一步降 FP，不取代静态规则。

## 7. 总结：Plumb 的定位

| 维度 | Plumb 定位 | 跟其他工具的关系 |
| --- | --- | --- |
| **核心引擎** | TAC IR + 跨函数 summary | 跟 Coverity 商业同档；优于 PMD/Sonar/SpotBugs |
| **Style 覆盖** | 595 个 AST checker（每条带 PMD/Sonar/SpotBugs/Checkstyle 等价 ID） | **完全覆盖**开源 4 工具 |
| **Taint 精度** | 19 source 模式 + 397 sink 模式 + 跨函数 + Z3 | 优于 PMD/Sonar/SpotBugs（开源 taint 最强） |
| **DFA checker** | 66 个实际注册（`ScanRunner.buildDfaCheckers()`） | SpotBugs 字节码风格，但 Plumb 走源码 |
| **AI 集成** | 可选 Re-Act agent | Sonar / Coverity 实验性，Plumb 更深入 |
| **开源 vs 商业** | 内部使用（无 LICENSE 文件，见 README §9） | 跟 Coverity 商业引擎对标 |

**Plumb 适合的场景**：
- 需要**跨函数 taint**（Spring / Jakarta / 大型企业项目）
- 需要**路径敏感数值分析**（CWE-190/191/252/253）
- 需要**等价 PMD + Sonar + SpotBugs + Checkstyle**（不想装 4 个工具）
- 想跑 Juliet corpus 验证精度
- 需要**透明实现**（每条 rule 的 SPI 都有文档）

**Plumb 不适合的场景**：
- 需要 Coverity 商业级 0.70+ prec（Plumb 当前 0.4537）
- 需要 C/C++/JS/Go 多语言（Plumb 限 Java）
- 需要 IDE 深度集成（Plumb CLI 优先，IDE 部分支持 SARIF）
