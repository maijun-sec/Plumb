# 跨函数分析周边：并行 worklist / tabulation 对比 / Trace 审计 / checker 迁移 / AI 增强

本文档是 `CROSS-FUNCTION-ANALYSIS.md` 的 §6-§14 拆分版本。Phase 1+2 主干（动机、CallGraph、Phase 1 Summary、Phase 2 精确分析）见 `CROSS-FUNCTION-ANALYSIS.md`；Operation 体系与 6 套子分析见 `CROSS-FUNCTION-OPERATION.md`。

## 6. R217 并行 worklist

Phase 1 的"callee 先于 caller"是 RPO 的硬约束，但 R217 不必等整层 RPO 跑完才开
始下一层——只要一个函数所有**在程序里有 body 的 callee** 都总结完，caller 就可以
开工。

dep-count 算法：

```text
deps[fn] = 体内 CALL 到的、在 program.function(sig) 里能找到的 callee 个数
readyQ = { fn | deps[fn] == 0 }
worker:
  sig = readyQ.poll()
  analyseOneFunction(sig)            # Phase 2 (caller 视角) 跟 Phase 1 (callee 写 summary) 一起跑
  summaryCache.put(sig, merged)       # Phase 1 写完
  for caller in callersOf[sig]:
    if deps[caller].decrementAndGet() == 0: readyQ.add(caller)
```

自递归（直接 / 间接）被 dep 计算时主动跳过（`if (cs.callee().signature().equals(fn.signature())) continue;`），
所以死锁天然破除——自递归的 callee 在 caller 分析时还没有 summary，`FunctionSummaryCache.get(sig)`
返回 `FunctionSummary.empty()`，caller 看到"没有 taint 流出"是 conservative 上界。

并发安全靠两点：
- `FunctionSummaryCache` 升级到 `ConcurrentHashMap` + `LongAdder`（R217），
  跨 worker 写 / 读都无锁。
- `FactBag` 与 `taintedVars` 等 per-function 状态在 `analyseOneFunction` 入口
  `clear()`、出口丢弃——同一时刻只被一个 worker 持有。

`R217 worklist` 的设计取舍详见 `DfaEngine.runWorklist` 的注释。

## 7. 与 tabulation（IFDS / IDE）方案的对比

> 本节是**设计取舍的论证**，不是对 Plumb 自身的描述。Plumb 本身既不是 IFDS 也不是 IDE，
> 它的实际结构见 [`DATA-FLOW.md`](./DATA-FLOW.md)。

| 维度 | IFDS / IDE 单 worklist | Plumb summary-based |
| --- | --- | --- |
| 状态 | 一个统一的 `(program-point × fact)` 图 + 全局 worklist | 每函数一份 `FunctionSummary` + `MethodSummary`；per-function checker 状态 |
| 两阶段 | 不分两阶段，fact 在 call/return 边实时传播 | summary 预热（path-insensitive）+ 正向遍历（flow-sensitive） |
| 调用代价 | 每次 CALL 边都展开一份 callee 的 facts | O(1) cache lookup，summary 一次构建多次复用 |
| 并行化 | worklist 是共享的，分区策略复杂 | callee 之间独立，可按依赖计数并行 |
| 域扩展 | 新加一类 fact（如 points-to）要重建 lattice | 新加一类 `Operation` 变体，旧 checker 不动 |
| Call graph 策略 | 与求解器耦合（IFDS 自己维护 explode / context） | summary 与 call graph 解耦——`cha` / `rta` / `vta` / `1cfa` / `2cfa` / `stub` 都可插拔 |
| 异构分析 | 同一 lattice 难装多套分析 | summary 是模板，多套子分析（常量 / points-to / null / 数值 / 副作用）共用框架 |

**为什么选 summary 而非 tabulation**：

1. **异构 fact 难统一 lattice**。Taint 分多类（String / Numeric / Pointer / Mixed），
   nullability 是三值，points-to 是 set-of-alloc。tabulation 要把它们 flatten 成统一 ID，
   checker 之间容易相互污染。
2. **可扩展性**。新增 checker 只需加一个新 `Operation` 变体（或复用 `TaintPropagate` /
   `Sink` / `WriteToField`），不用动 RPO 调度本身。
3. **缓存天然命中率高**。同一个 callee 被 N 个 caller 调用 → summary 只算 N 次；tabulation
   方案要为每个调用点各算一份。
4. **并行不是事后补丁**。worklist 直接基于依赖计数，按调用图拓扑分批。
5. **call graph 策略可插拔**。`FunctionSummaryCache` 是 signature→summary 映射，call graph
   算法只决定「有哪些 callee 可达」，不参与求解。

代价是 **summary 边界处的 over-approximation**：多分支 return 被折叠成 `top`，caller 看到的是
合并后的事实，可能丢精度。注意 Z3 在这里是**误报过滤器**而非精度恢复器——它只在
`pathConditionForSink` 非空时被调用，不能恢复已被 summary 合并掉的 return 细节。

## 8. 跨文件验证

- `plumb-dfa/src/test/.../summary/R256CrossFunctionFoldTest` —— 端到端
  跑 `IO.staticReturnsTrue` + `bad()`，验证 fold 出 `const:true` 后
  `LiteralBooleanConditionChecker` 报 finding。
- `plumb-dfa/src/test/.../integration/R256JulietCrossFunctionSweepTest` +
  `R257JulietCrossFunctionSweepTest` —— 在真实 Juliet corpus（`CWE129_Improper_
  Validation_of_Array_Index` 的 `s01/`，672 个 TU）上跑 cross-function sweep。
- `plumb-dfa/src/test/.../points/R236CrossFunctionPointsToTest` —— 验证
  `applyCalleeSummary` 把 callee 内的 `new` 接到 caller 的 dest 上。

## 9. 已知限制 / 未来工作

1. **多分支 return 折叠成 `top`**。`MethodSummaryBuilder` 的 `buildReturnShape`
   在 `allSame == false && !(boolean-conditional-pattern)` 时返回
   `UNKNOWN("multiple returns with mismatched shapes")`。如果想保留多分支精
   度，可以扩展 `ReturnShape` 加 `RETURN_MULTI` 形态（multi-value lattice），但
   路径敏感的代价会落到所有 caller。
2. **跨 TU 缺 caller-free 形参传播**。R83 修过的 use-after-free 在跨 TU
   时仍然需要 caller 在 callee 调用后做 free。summary 已经能看见
   `WriteToField`，但 caller 看不到"callee 内部把传进去的指针 close 了"——这
   条信息目前在 `HasUnmodelledBehavior` 里被合并，丢失了精度。计划在 R560
   加 `Operation.CloseParam(int)` 变体。
3. **`.tac` 缓存复用 summary**。当前 `IrSerializer` 只序列 IR，summary 是
   per-run 重算。把 `FunctionSummaryCache` 跟着 `merged.tac` 一起序列化
   （不变量 = source path 不变 + rule set 不变）可以省掉 Phase 1。详见
   `docs/tech/CACHE.md` "Future extensions"。
4. **Native / Reflection 不可见**。`HasUnmodelledBehavior("native")` 让 caller
   直接退到最保守的"可能产生任何副作用"。如果想给 JNI 做白名单分析，需要
   在 `MethodSummary` 加一个 `nativeSemantics` 子结构。
5. **`GlobalCallGraphStrategy` 未接 CLI**。`DfaEngine` 的构造只接受 TU-local
   strategy name（`"cha" / "rta" / "vta" / "1cfa" / "stub" / "cached:..."`），
   不接受 `GlobalTACStore`。`GlobalCallGraphStrategy` 只能被集成测试用，要
   在 CLI 里用 `--cg-strategy global`，需要给 `DfaEngine` 加
   `createCrossTu(cfg, checkers, store, threads)` 工厂，跟 R217 worklist
   集成，并把 `CallGraphStrategyFactory.create(...)` 的"name in {global}"分支
   删掉（避免 "Unknown strategy" 误报）。

## 10. 进一步阅读

- `docs/tech/DATA-FLOW.md` —— 区间抽象 + Z3 路径条件
- `docs/tech/ARCHITECTURE.md` —— 模块边界（`plumb-dfa` 老引擎 vs `plumb-dfa`）
- `docs/tech/TAC.md` —— TAC IR（summary 的输入）
- `docs/tech/CACHE.md` —— 磁盘 IR 缓存与 `merged.tac`

## 11. Trace（`dataflow_steps`）的产出机制

每个 finding 可以携带一条 `FlowStep` 列表，在 JSON 里以 `dataflow_steps` 输出。
`FlowStep` 的 `role` 取值共 5 种：

| role | 含义 | 产出位置 |
| --- | --- | --- |
| `source` | taint 起点（用户输入、参数、资源分配点） | `TaintedCheckerBase.markSource(...)`；`PathSensitiveCheckerBase` 在资源分配点 |
| `propagate` | 污点随赋值 / 二元运算传播 | `TaintedCheckerBase` 的 MOVE / BINOP / CALL 分支 |
| `summary-fold` | 跨函数折叠：callee 的 summary 在 caller 侧合并进 fact bag | `FieldTaintTracker`、`TaintedCheckerBase` 的 summary applier |
| `sink` | 命中 sink family | `TaintedCheckerBase.checkSinkAtCall(...)` |
| `violation` | 违规点（无 taint 的 pattern-based checker） | `Finding.violation(...)` 静态工厂 |

### 11.1 `source` role 的识别

`InputSourcePatterns`（`plumb-dfa/.../taint/InputSourcePatterns.java`）维护一份
callee signature 的 source 模式表。IR 里同一方法的 signature 可能有两种写法
（`java.io.BufferedReader.readLine` 与 `BufferedReader#readLine`），所以
`matches(...)` 与 `isInputSourceCall(...)` 在做 `endsWith` / `contains` 比较前
先统一把 `#` 归一化成 `.`。

### 11.2 `summary-fold` role 的产出

跨函数场景下，callee 内部发生的字段写入对 caller 不可见。`FieldTaintTracker`
在 callee 体内记录 `Operation.WriteToField(...)`，caller 侧应用 summary 时把
这条信息折叠成一条 `summary-fold` step，让 trace 读起来是
「source → propagate → summary-fold → sink」。

`TaintedCheckerBase` 的 `case RETURN` 维护 `passedOutTaint` 集合；
`summarizeFunction` 在 `returnOps` 上 emit
`Operation.TaintPropagate(sourceId, confidence)`，caller 侧的
`propagateTaintFromCalleeSummary` 据此把对应实参标为 tainted。

### 11.3 `violation` role 的产出

`Finding.violation(checkerName, ruleId, msg, severity, cwe, line, col, fnSig, flow)`
（`plumb-reporter/.../Finding.java`）是 pattern-based checker 的推荐入口：
它产出一个带 1 个 `FlowStep(role="violation", ...)` 的 finding，
并同步设置 `flow` 字段以兼容尚未迁移到结构化 step 的 reporter。

### 11.4 `propagate` role 的产出

`TaintedCheckerBase` 在 MOVE（`MOVE src -> dst`）、BINOP（`BINOP lhs+rhs -> dst`，
两个操作数都带 taint 时取更长的那条 path）等分支上追加 `propagate` step，
记录该指令的行 / 列 / 目标变量。

## 12. DFA checker 的分类与注册

`plumb-dfa` 侧的 checker 分三类实现：

| 类别 | 基类 | 数量 | 说明 |
| --- | --- | ---: | --- |
| **taint-style** | `TaintedCheckerBase` | 47（`checkers/taint/`） | 构造时传一个 `TaintRule(ruleId, factKind, sinkFamily, cwe, severity, taintDomain)`；命中 sink family 时发 finding |
| **constraint-style** | `checkers/boundary/` | 1 | `BoundaryConstraintChecker`，用 Z3 求解路径条件 |
| **pattern-based** | 直接 `implements DfaChecker` | 16（`checkers/controlflow/`）+ 2（`checkers/taint/`） | 无 taint 传播，靠 IR 局部模式匹配；用 `Finding.violation(...)` 发单步 trace |

`ScanRunner.buildDfaCheckers()`（`plumb-main/.../ScanRunner.java`）**实际注册
66 个** DFA checker。注册受 YAML 的 per-rule flag 控制（例如
`config.runIntOverflow`、`config.runNullDeref`），`config.runTaint` 是
taint 全家桶的总开关。

另有 5 个 DFA checker **曾经存在但已从源码树删除**，注册语句的注释还留在
`ScanRunner.buildDfaCheckers()` 里。它们已迁移到 `plumb-style` 的 AST 等价实现，
由 `AstCheckerRegistry.builtins()` 自动注册：

| 已删除的 DFA checker | plumb-style 等价 | CWE |
| --- | --- | --- |
| `CleartextSocketUsageChecker` | `CleartextSocketUsageCheck` | CWE-319 |
| `CompareClassesByNameChecker` | `CompareClassesByNameCheck` | CWE-273 |
| `ProcessControlChecker` | `ProcessControlCheck` | CWE-114 |
| `RecursionChecker` | `UncheckedLoopConditionCheck`（覆盖同一 CWE，但语义是无出口的循环而非自递归） | CWE-674 |
| `UninitNonnullDfaChecker` | `UninitNonnullCheck` | CWE-457 |

同理，`ServletAtomicityDfaChecker` 与 `SingletonRaceDfaChecker` **仍在源码里但
不注册**：`plumb-style` 的 `ServletAtomicityCheck` / `SingletonRaceCheck`
覆盖同样的 CWE-362 / CWE-366 且精度更好。需要启用时把对应 YAML flag 打开
（`runServletAtomicity` / `runSingletonRace`）。

**新增一条 DFA 规则的做法**：在 `plumb-dfa/src/main/java/org/plumb/dfa/checkers/<family>/`
下写一个 `<Name>Checker.java`，继承 `TaintedCheckerBase`（taint 类）或直接
`implements DfaChecker`（pattern 类），然后在 `ScanRunner.buildDfaCheckers()`
里加一行 `checkers.add(new ...)` 并挂上对应的 YAML flag。

## 13. AI 增强层（plumb-ai）

`plumb-ai` 在静态分析之后加了一层 LLM 判定，用于**给 finding 标 TP/FP/UNKNOWN**。
它只有一条流水线：FP reducer。

### 13.1 启用方式

```bash
sast-scan --ai-dedup ...        # CLI 开关
```

```yaml
ai:
  dedup: true                   # YAML 等价开关，默认 false
  dropOnAiFp: false             # true = 丢弃被判 FP 的 finding；false = 保留并标 ai_verdict=FP
  dedupRules: []                # 空 = 所有规则都过一遍；填了则只过这些 rule
  provider: openai_compatible
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}
  model: gpt-4o-mini
  timeoutSeconds: 30
  maxConcurrent: 4
  temperature: 0.0
```

只有**自带 prompt 的规则**会进入 AI 判定：checker 通过 `getAiPrompt(Finding)` 提供 prompt，
返回 `null` 表示该规则不走 AI。`--rule-prompts-dir <dir>` 可以用 `<rule-name>.md`
覆盖 checker 的内联 prompt。

### 13.2 模块结构

`plumb-ai/src/main/java/org/plumb/ai/` 下共 29 个类，分三个包：

```
org.plumb.ai.agent/     —— LLM 连接与 Re-Act 循环
  LlmConnection            连接参数（provider / baseUrl / apiKey / model / maxTokens / ...）
  LlmProvider              provider 接口（便于测试替换）
  OpenAiCompatibleLlmProvider  裸 java.net.http.HttpClient 实现
  LlmMessage / LlmReply    请求与响应
  ReActAgent               工具调用循环
  AgentTask / AgentResult  任务与结果
  ToolCall / ToolDefinition / ToolResult
  ToolRegistry             工具注册表

org.plumb.ai.tools/     —— 13 个工具类 + 上下文
  Tool                     工具接口
  ToolContext              工具可访问的上下文
  ToolRegistry             工具注册与调用
  ReadSourceTool / ListDirTool / GlobTool / GrepTool / SearchPatternTool
  GetFullClassTool / GetClassHierarchyTool / GetCallHierarchyTool
  FindCallersTool / FindDefinitionsTool
  GetMethodAnnotationsTool / GetDataflowTraceWithFactsTool
  HasSanitizerOnPathTool
  V2TraceSummarizer        trace 摘要

org.plumb.ai.prompts/    —— AgentPrompts（系统提示）
```

### 13.3 在 ScanRunner 里的位置

```text
parse -> translate -> style + DFA
                    |
                    v
             [static findings]
                    |
                    v
             AI dedup（仅 --ai-dedup 且 aiConn.isUsable()）
                    |
                    v
                  emit
```

`ScanRunner.runAiDedup(...)`（`plumb-main/.../ScanRunner.java:223`）按
`ai.dedupRules` 过滤后，为每条 finding 找到拥有该 rule 的 checker，取它的 prompt，
交给 `ReActAgent` 判定，结果写回 `ai_verdict` / `ai_reason`。
`--ai-dedup-stats` 额外打印判定分布。

### 13.4 为什么不用 Spring-AI

`plumb-ai` 用裸的 `java.net.http.HttpClient` + Jackson，避免 Spring-AI 整个
Spring Boot core 依赖。任何 OpenAI 兼容端点（OpenAI / Azure / Ollama / vLLM /
LocalAI / MiniMax）都能接；Anthropic 走 `Provider.ANTHROPIC` 的原生
`/v1/messages`，但只支持单 prompt，tool-calling 会抛
`UnsupportedOperationException`。

> **注意**：本节早期版本描述过一个 `--ai-scan`（LLM 主动找静态规则漏掉的 bug）、
> `--aggressiveness` 开关、`AIConfig` / `AIServiceFactory` / `AIDedupService` /
> `AIScanService` / `RulePromptRegistry` / `Aggressiveness` 一组类，以及
> `plumb-ai/src/main/resources/rule-prompts/` 目录。**这些都不存在**。
> `--ai-scan` 与 `--aggressiveness` 已被移除（前者从未接线，后者是无操作：
> `ConfigLoader` 忽略 YAML 里的 `aggressiveness` 键，`SastMain` 也从不读 CLI 值），
> 规则级开关改由 YAML 的 `checks.<rule>.enabled` 与 `--rule <id>` 控制；
> per-rule prompt 现在由 checker 自己持有。

## 14. Re-Act 框架：让 LLM 能「看代码」

静态规则只能按写死的模式匹配。Re-Act 循环让 LLM 在判定一个 finding 时**主动调用工具**去读
代码、查调用者、追 dataflow trace，而不是只拿到一段截断的上下文就下结论。

### 14.1 三个核心类型

```java
// plumb-ai/.../agent/ReActAgent.java
public ReActAgent(LlmProvider provider, ToolRegistry tools) { ... }
public AgentResult run(AgentTask task, ToolContext ctx) { ... }
```

- **`LlmProvider`** —— provider 接口，生产实现是 `OpenAiCompatibleLlmProvider`
  （裸 `java.net.http.HttpClient` + Jackson）。做成接口是为了让测试能注入假 provider。
- **`AgentTask`** —— record，描述这次要 agent 做什么。`AgentTask.fpReduction(...)` 与
  `AgentTask.bugScan(...)` 是两个工厂方法；`withMaxTurns(int)` 与
  `withForceFirstTurnRequired(boolean)` 可调循环参数。
- **`AgentResult`** —— record，带 `Kind` 枚举（final answer / max turns exceeded /
  provider error / tool error）。`isFinalAnswer()` 区分「LLM 给了结论」与「循环没跑完」。

### 14.2 工具集：13 个工具类，注册 12 个

`plumb-ai/.../tools/` 下有 **13** 个具体工具类（外加 `Tool` 接口、`ToolContext`、
`ToolRegistry`、`ToolCall`、`ToolDefinition`、`ToolResult` 这些基础类型）。

`ScanRunner.buildToolRegistry()`（`plumb-main/.../ScanRunner.java:450`）**注册 12 个**：

| 工具 | 用途 |
| --- | --- |
| `ReadSourceTool` | 读源文件内容 |
| `GrepTool` | 正则搜索 |
| `GlobTool` | 按 glob 找文件 |
| `ListDirTool` | 列目录 |
| `SearchPatternTool` | 结构化模式搜索 |
| `FindDefinitionsTool` | 找定义 |
| `FindCallersTool` | 找调用者 |
| `HasSanitizerOnPathTool` | 问「这条路径上有没有 sanitizer」 |
| `GetMethodAnnotationsTool` | 取方法注解 |
| `GetClassHierarchyTool` | 取类继承链 |
| `GetFullClassTool` | 取整个类的源码 |
| `GetDataflowTraceWithFactsTool` | 取带事实的 dataflow trace |

`GetCallHierarchyTool` 存在但**未注册**到 `buildToolRegistry()` —— LLM 在
`--ai-dedup` 判定时拿不到它。另有 `V2TraceSummarizer`（trace 摘要，不是 Tool）。

### 14.3 在 ScanRunner 里的调用链

```java
org.plumb.ai.tools.ToolRegistry tools = buildToolRegistry();                       // :242
org.plumb.ai.agent.ReActAgent agent = new org.plumb.ai.agent.ReActAgent(provider, tools);  // :243
...
org.plumb.ai.tools.ToolContext ctx = new org.plumb.ai.tools.ToolContext(...);      // :399
```

每个 finding 走一次 `agent.run(task, ctx)`，结果里的判定写回 `ai_verdict` / `ai_reason`。

### 14.4 静态分析是 core，AI 是 optional

`plumb-ai` 是 `plumb-main` 的**可选**依赖。`plumb-main` 通过
`org.plumb.ai.agent.LlmConnection`（一个纯数据 record）与 `plumb-ai` 交互，
LLM 不可用时静态分析照常跑完。配置见 [`../usage/ENVIRONMENT.md`](../usage/ENVIRONMENT.md)。

### 14.5 去掉的是什么

早期设计里存在一个「AI bug scanner」——把整个函数丢给 LLM 让它找静态规则漏掉的漏洞，
输出 `rule_id=AI_SCANNER` 的 finding。`AgentTask.bugScan(...)` 这个工厂方法仍在，
但**没有任何调用方**：`--ai-scan` 开关不存在，扫描流水线里也没有这条支路。
AI 当前只做 FP 判定（`AgentTask.fpReduction(...)`）。

> 本节早期版本提到的 `AIDedupService` / `AIScanService` / `AIServiceFactory` /
> `AIConfig` / `AIDedupResult` / `AIScanFinding` / `RulePromptRegistry` /
> `Aggressiveness` 这一组类，**在代码库中都不存在**——它们已被
> `ReActAgent` + `LlmConnection` + `ToolRegistry` 取代。per-rule prompt 现在由
> checker 的 `getAiPrompt(Finding)` 持有，不再集中在一个 registry 里。

