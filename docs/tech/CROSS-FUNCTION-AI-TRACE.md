# 跨函数分析周边：并行 worklist / IFDS 对比 / Trace 审计 / checker 迁移 / AI 增强

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

## 7. 与传统 IFDS / IDE 的对比

| 维度 | IFDS / IDE 单 worklist | Plumb summary-based two-phase |
| --- | --- | --- |
| 状态 | 一个统一的 `(program-point × fact)` 图 + 全局 worklist | 每函数一份不可变 `FunctionSummary` + `MethodSummary`；per-function checker 状态 |
| Phase 1 精度 | 不分两阶段，fact 在 call/return 边实时传播 | **path-insensitive + context-insensitive** summary，模板化 |
| Phase 2 精度 | 天然 path-sensitive (IFDS 区分 IF-function) | **fully context-sensitive**（per call site + caller 自己的 args / facts）+ **path-sensitive via Z3**（每条 finding 单条 Z3 query） |
| 调用代价 | 每次 CALL 边都展开一份 callee 的 facts | O(1) cache lookup，summary 一次构建多次复用 |
| 并行化 | worklist 是共享的，几乎没法并行 | callee 之间独立，可以 worklist 并行（R217） |
| 域扩展 | 新加一类 fact（如 points-to）要重建 lattice | 新加一类 `Operation` 变体 + 配套 `Fact` 即可，旧 checker 不动 |
| Call graph 策略 | 与求解器耦合（IFDS 自己维护 explode / context） | summary 跟 call graph 解耦——CHA / RTA / 0-CFA / 1-CFA / VTA 都 drop-in |
| 异构分析 | 同一 lattice 难装多套分析 | summary 是模板，**6 套子分析**（常量 / points-to / null / 数值 / Z3 / 副作用）共用同一框架 |
| 精度 vs 速度 | 高精度但代价大 | summary 边界处会 over-approximate（多分支 return 折叠成 `top`），但漏报为 0 |
| 适用问题 | 单 checker / 同质 fact 域 | 异构 checker（taint + null + points-to + side effect + Z3）并行 |

**为什么 Plumb 选 summary 而非 IFDS**：

1. **异构 fact 难统一 lattice**。Taint domain 分 4 类（String/Numeric/Pointer/
   Mixed），nullability 是三值，points-to 是 set-of-alloc。IFDS 要把它们
   flatten 成一个整数 ID，checkers 之间相互污染。
2. **可扩展性**。新增 checker 只要加一个新 `Operation` 变体（或复用现有
   `TaintPropagate` / `Sink` / `WriteToField`），不用动 RPO 调度本身。
3. **缓存天然命中率高**。同一个 callee 被 N 个 caller 调 → summary 只算 N 次
   lookup，IFDS 重复算 N 份。
4. **并行不是事后补丁**。R217 worklist 直接基于 dep-count，IFDS 的 explode 在
   并行下要复杂得多的 partition 策略。
5. **call graph 策略可插拔**。`FunctionSummaryCache` 是 signature→summary 映射，
   call graph 算法只决定"有哪些 callee 可达"，不参与求解。

代价是 **summary 边界处的 over-approximation**：`staticReturnsTrue` 永远
`const:true`，但 `choosePath(boolean b) { if (b) return 1; else return "x"; }`
的两个 return 一个 const、一个 string，summary 会被 `buildReturnShape` 退到
`UNKNOWN("multiple returns with mismatched shapes")`——caller 看到 `top`，可能
丢精度。这条用 R218 / R242 的 Z3 path-condition bridge 在 Phase 2 单条 finding
上验证 SAT/UNSAT 补，但补不完整（多分支 return 的事实已经被 summary 合并
掉了，Z3 也救不回来）。

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
   `doc/tech/CACHE.md` "Future extensions"。
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

- `doc/tech/DATA-FLOW.md` —— 区间抽象 + Z3 路径条件
- `doc/tech/ARCHITECTURE.md` —— 模块边界（`plumb-dfa` 老引擎 vs `plumb-dfa`）
- `doc/tech/TAC.md` —— TAC IR（summary 的输入）
- `doc/tech/CACHE.md` —— 磁盘 IR 缓存与 `merged.tac`

## 11. R-? — Trace 覆盖率审计 (R-?)

在 R-? 阶段，我们审计了 `plumb-dfa` 输出 findings 的
`flowSteps` 字段的覆盖率。审计覆盖了 18 个直接 `DfaChecker`
（pattern-based，无 taint）和 4 个数字 taint checker
（`IntOverflow` / `IntUnderflow` / `DivByZero` / `ArrayIndex`）
外加 28 个 `TaintedCheckerBase` 子类（`SQL/Command/Path` 注入
家族等）。审计发现 4 个 coverage bug，逐一修复。

### 11.1 Bug 1 — `InputSourcePatterns` 不识别 `#` 分隔符

`InputSourcePatterns.matches()` / `isInputSourceCall()` 只匹配
`.` 分隔的 callee signature（如 `java.io.BufferedReader.readLine`）。
但 IR 里有些 source 来自 `BufferedReader#readLine` 这种 hash
分隔的格式 —— 全部漏掉。

**修复**：`matches()` 和 `isInputSourceCall()` 在做 `endsWith`
检查前先 normalize `#` → `.`。一个 6 行的补丁，零行为差
异风险。

### 11.2 Bug 2 — `TaintedCheckerBase.summarizeFunction` 不 emit return taint

`case RETURN` 当时只把 tainted var 加进局部 `passedOutTaint` 集合
但 `summarizeFunction` 没读它。结果是 `helper(s)` 返回 `s` 的
跨函数 passthrough pattern 完全看不到 taint，caller 视角
的 `helper(tainted)` 不报。

**修复**：`case RETURN` 维护 `passedOutTaint`；
`summarizeFunction` 在 `returnOps` 上 emit
`Operation.TaintPropagate(sourceId, confidence)`。这
让 `propagateTaintFromCalleeSummary` 在 caller 视角把对应实参
标为 tainted。

### 11.3 Bug 3 — 4 个数字 checker 标 taint 但不写 `taintPaths`

`IntOverflowChecker` / `IntUnderflowChecker` / `DivByZeroChecker` /
`ArrayIndexChecker` 在 `case CALL` 命中 input-source call 时调
`taintedVars.put(d, "input:"+callee)`，但没在 `taintPaths` 里
seed `FlowStep`。结果 finding 的 `dataflow_steps` 字段是空的
—— 报告里看不到 source。

**修复**：抽公共 helper `markSource(v, label, ins, fnSig, expr)`，
4 个数字 checker + `TaintedCheckerBase.case CALL` 都改用这个
helper。它做两件事：(1) 把 v 标 tainted，(2) 在 `taintPaths`
里 seed role=`source` 的 `FlowStep`。

### 11.4 Bug 4 — 18 个直接 `DfaChecker` 的 `Finding` 构造 9 参 ctor

`OffByOneChecker` / `InsecureCryptoChecker` /
`IteratorConcurrentModificationChecker` 等 18 个 pattern-based
checker 用 9 参 `Finding` ctor（`filePath` slot 在），从不写
`flowSteps` 字段。结果这些 checker 的 finding 在 JSON 里
`dataflow_steps` 永远是 `[]`。

**修复**：`Finding` 加 `violation(checkerName, ruleId, msg,
severity, cwe, line, col, fnSig, flow)` 9 参静态工厂。它产
出一个有 1 个 `FlowStep(role="violation", ...)` 的 finding
+ 同步设置 `flow` 字段（向后兼容老 reporter）。18 个
checker 全部换成这个工厂。`FlowStep` role 列表收敛为
`source | propagate | summary-fold | sink | violation`。

### 11.5 净效果

- 之前 0 个 finding 有 `dataflow_steps` → 现在 12 个 finding
  （E2E `dist/plumb-0.1.0-SNAPSHOT/` 跑通后的 1867 个 finding 里
  的 12 个，主要由 7 个 unified taint + 5 个直接 DfaChecker-style
  checker 在 `examples/` 命中产生）。
- `RflowStepEmissionTest` 增强：原 1 个测试 case 升级
  + 2 个新 case（`sqlInjectionCheckerEmitsSourceFromInputCall`
  验证 3 步 trace 的精确行号；`sqlInjectionCheckerEmitsSummaryFoldAcrossFunctions`
  验证跨函数 `helper(s) → s` 的 `summary-fold` step）。
- 详细修复 + before/after 路径对比见
  `doc/tech/TRACE-COVERAGE-AUDIT-2026-08-13.md`。

## 12. R-? — plumb-dfa → plumb-dfa checker 迁移

`plumb-dfa`（老 Soot-based 引擎，~37 个 checker）已被整体迁
移到 `plumb-dfa`，`plumb-dfa` 模块从 build 移除。迁
移按"机制"分类，不批量写：

| 机制 | 例子 | 引擎实现 |
| --- | --- | --- |
| **A — Pattern-based**（IR 局部 pattern，无 taint） | `BadShift` / `CorsMisconfig` / `DefaultEncoding` / `HardcodedCredential` / `InfiniteLoop` / `ObsoleteFunction` / `StringEquality` / `InsecureCommunication` / `InsecureCookie` / `MissingAuthorization` / `PredictableKey` / `PredictableRandomSeed` / `StaticApiKey` / `BigDecimalDoubleConstructor` / `FieldHardcodedPassword` | 直接 `DfaChecker` 实现，emit `Finding.violation(...)` 单步 trace |
| **B — Taint-style**（数据流 + sink） | `SqlInjection` / `CommandInjection` / `PathTraversal` / `LdapInjection` / `ElInjection` / `OgnlInjection` / `JavaCodeInjection` / `JcrInjection` / `JspDynamicInclude` / `TrustBoundaryViolation` / `SensitiveDataLeak` / `SensitivePIILeak` / `SessionFixation` / `UnsafeReflection` / `UnsafeJni` / `BadCertVerification` / `ResourceLeak` / `InsecureRandom` / `NullDeref` / `IntOverflow` / `IntUnderflow` / `ArrayIndex` / `DivByZero` / `NumericTruncation` / `UncheckedLoopCondition` / `UncontrolledMemAlloc` / `UseAfterFree` / `UncontrolledRecursion` | 继承 `TaintedCheckerBase`，构造时传一个 `TaintRule(ruleId, factKind, sinkFamily, cwe, severity, taintDomain)` |
| **C — Annotation-aware**（端点 / 注解元数据） | `MissingInputValidation` / `InformationExposure` / `MissingAuthorization` | `DfaChecker` 用 `TacFunction.classAnnotations()` / `methodAnnotations()` / `paramAnnotations()` / `returnType()` |
| **不迁移 — Style rule**（应留在 plumb-style） | `AssignmentInOperand` / `CatchGeneric` / `ConstantCondition` / `DeadStore` / `HardcodedIp` / `IncorrectReturn` / `ThreadRun` / `WeakCrypto` | 已经在 `plumb-style` 里有等价实现（如 `AssignmentInOperandCheck` / `CatchGenericCheck` / `BooleanLiteralInIfCheck` / `UnusedAssignmentCheck` / `AvoidUsingHardCodedIPCheck` / `IncorrectReturnValueCheckChecker` / `CallToThreadRunCheck` / `InsecureCryptoCheck`），不重复造 DFA |

`plumb-main` 的 `ScanRunner.runUnifiedCheckers` 一次性注册所
有 33 个 unified taint + 22 个 DfaChecker（约 55 个
`new XxxChecker()`），单一开关 `runTaint` 控制 33 个 taint
全家桶，per-rule flag（如 `runIntOverflow`）做粒度覆盖。

### 12.1 文件清单

**Category A (DfaChecker, 新增)** — `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/`:
- `PredictableRandomSeedChecker.java` (CWE-332/336)
- `PredictableKeyPasswordChecker.java` (CWE-259/798)
- `StaticApiKeyChecker.java` (CWE-798, static field)
- `InsecureCommunicationChecker.java` (CWE-319)
- `CorsMisconfigurationChecker.java` (CWE-942)
- `DefaultEncodingChecker.java` (CWE-176)
- `BadShiftChecker.java` (CWE-758)
- `InsecureCookieChecker.java` (CWE-614/1004)
- `MissingAuthorizationChecker.java` (CWE-862)
- `BigDecimalDoubleConstructorChecker.java` (CWE-682, R-?)
- `FieldHardcodedPasswordChecker.java` (CWE-798, R-?)
- `HardcodedCredentialChecker.java` (CWE-798, R-?)
- `InfiniteLoopChecker.java` (CWE-835, R-?)
- `ObsoleteFunctionChecker.java` (CWE-477, R-?)
- `StringEqualityChecker.java` (CWE-597, R-?)

**Category B (TaintedCheckerBase, 已有 + 新增)** — `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/`:
- `NumericTruncationChecker.java` (CWE-197, R-?)
- `UncontrolledMemAllocChecker.java` (CWE-789, R-?)
- `UncheckedLoopConditionChecker.java` (CWE-606, R-?)
- (其余 30 个 taint checker 上一轮已就位)

**Category C (annotation-aware, 新增)** — `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/`:
- `MissingInputValidationChecker.java` (CWE-20, R-?)
- `InformationExposureChecker.java` (CWE-200, R-?)

### 12.2 plumb-dfa 模块移除

- `pom.xml`：根 `pom.xml` 删除 `<module>plumb-dfa</module>`，
  `<dependencyManagement>` 删除 plumb-dfa 引用。
- `plumb-main/pom.xml`：删除 `plumb-dfa` 依赖。
- `plumb-main/src/main/java/.../ScanRunner.java`：
  - 删除 33 个 `org.plumb.dfa.checker.*` import
  - 删除 `org.plumb.dfa.taint.TaintAnalysis` import
  - 删除 `runAllCheckers(AnalysisContext)` 方法（~46 行）
  - 3 个 caller 改为直接 `out.addAll(runUnifiedCheckers(...))`
- `default-config.yaml`：`checks:` 块从 plumb-dfa 字段
  升级到 unified 字段（保留 `run*` flag 名字向后兼容）。

### 12.3 不迁移规则清单（应留在 plumb-style）

| plumb-dfa checker | plumb-style 等价 | CWE |
| --- | --- | --- |
| `AssignmentInOperandCheck` | `AssignmentInOperandCheck` | style |
| `CatchGenericCheck` | `CatchGenericCheck` | CWE-396 |
| `ConstantConditionCheck` | `BooleanLiteralInIfCheck` + unified `LiteralBooleanConditionChecker` (taint) | CWE-570 |
| `DeadStoreCheck` | `UnusedAssignmentCheck` + unified `UnusedAssignmentChecker` (taint) | CWE-563 |
| `HardcodedIPCheck` | `AvoidUsingHardCodedIPCheck` | CWE-605 |
| `IncorrectReturnCheck` | `IncorrectReturnValueCheckChecker` | CWE-253 |
| `ThreadRunCheck` | `CallToThreadRunCheck` | CWE-572 |
| `WeakCryptoCheck` | `InsecureCryptoCheck` + unified `InsecureCryptoChecker` | CWE-327 |

Style 规则的特点是只需要 AST 形态信息（不需要 taint 传播、
不需要跨函数、找不到数据流），DFA 引擎重写它们既不提升
精度也不提升召回，只是把"在一棵 AST 上数节点"的逻辑硬塞
进 IR visitor，徒增维护成本。**如果一个规则是 style rule，
不要用 DFA 实现**。

## 13. R-? — AI 增强层 (plumb-ai)

R-? 在 cross-function summary 之后加了一个 AI 增强层，模块
名 `plumb-ai`。它做两件事：

1. **FP reducer**（`--ai-dedup` / `ai.dedup: true`）：扫完所有
   静态规则后，把每条 finding 发给 LLM，附上 rule 自己的 prompt
   + 源码上下文 + dataflow trace，让 LLM 判 TP/FP/UNKNOWN。
   FP 的 finding 标 `ai_verdict=FP`（或丢掉，看 `ai.dropOnAiFp`）。
2. **Bug scanner**（`--ai-scan` / `ai.scan: true`）：把每个扫过
   的函数源码发到 LLM，让 LLM 找静态规则漏掉的（业务逻辑漏洞、
   framework 反模式、信任边界问题）。LLM 返回 Finding-shape
   JSON 数组，runner 合并进报告，`rule_id=AI_SCANNER` 区分。

### 13.1 模块结构

```
plumb-ai/
  AIConfig.java                 # 共享配置 (enabled/dedup/scan/provider/apiKey/...)
  AIProvider.java               # HTTP client (OpenAI / Anthropic)
  AIServiceFactory.java         # Bundle: registry + dedup + scan
  prompts/
    AgentPrompts.java           # 两个 System Prompt 字符串
    RulePrompts (in registry)   # per-rule prompt 注册表
  dedup/
    AIDedupService.java         # FP reducer agent
    AIDedupResult.java          # verdict + reason
  scan/
    AIScanService.java          # Bug scanner agent
    AIScanFinding.java          # Finding-shape record
  registry/
    RulePromptRegistry.java     # classpath + user dir + auto-generate
    Aggressiveness.java         # low / medium / high
  resources/rule-prompts/*.md   # 10+ hand-curated prompts
```

### 13.2 两条流水线在 ScanRunner 里的位置

```text
parse -> translate -> style + taint + DfaChecker
                                   |
                                   v
                            [static findings]
                                   |
                                   v
                            AI dedup (per-finding parallel)
                                   |
                                   v
                            AI scan (per-function; only if --ai-scan)
                                   |
                                   v
                                emit
```

`--aggressiveness=high` 时 AI dedup 默认开（除非 `ai.dedup: false`）。
`--aggressiveness=low` 时 AI 默认关。`medium`（默认）跟 YAML。

### 13.3 Rule prompt 自动生成

`plumb-ai` 给 10+ 已知 dataflow 规则（SQL_INJECTION /
COMMAND_INJECTION / PATH_TRAVERSAL / XSS_REFLECTED /
WEAK_CRYPTO / HARDCODED_CREDENTIAL / INSECURE_RANDOM /
SENSITIVE_DATA_LEAK / TRUST_BOUNDARY_VIOLATION /
DESERIALIZATION / ...）手写了 .md prompt。剩下没手写的会从
`CheckerConfig` + `TaintRule` 元数据自动生成一个 generic
"什么算 TP / 什么算 FP / 边界" 的模板。

加载顺序：
1. `--rule-prompts-dir` 用户目录（最高优先级）
2. bundled classpath `plumb-ai/src/main/resources/rule-prompts/`
3. auto-generate from `CheckerConfig` + `TaintRule`
4. generic fallback prompt

### 13.4 系统提示（System Prompt）

两个 agent 的 system prompt 都简短，规则语义在 rule prompt 里：

**FP reducer**:
> You are a senior application-security reviewer. ... default
> to TP if you cannot identify a CLEAR neutralisation. Reply
> with EXACTLY one JSON line: {"verdict":"TP|FP|UNKNOWN",
> "reason":"<one short sentence>"}.

**Bug scanner**:
> You are a senior application-security reviewer running a
> manual source-code audit. Look for: (a) any of the listed
> rules that the static analyzer may have missed, (b) business-
> logic security flaws ... Reply with JSON array.

### 13.5 为什么不用 Spring-AI

`plumb-ai` 用裸的 `java.net.http.HttpClient` + Jackson，~50 行
的 client 代码，省掉 Spring-AI 整个 Spring Boot core 依赖
（~3 MB）。任何 OpenAI 兼容端点（OpenAI / Azure / Ollama / vLLM
/ LocalAI / Anthropic-via-proxy）都能接，request/response body
跟 Spring-AI 一样，迁移成本几乎为零。

## 14. R-? — Re-Act 框架：把 LLM 变成能"看代码"的 reviewer

`§13` 那一版的 plumb-ai 是个**单轮** agent：把 finding + 源码 + trace
塞进一个 user turn，LLM 看一眼直接回 TP/FP/UNKNOWN。问题在于 LLM
只能看到那 ±5 行源码窗口 + 一份 trace 文本——它不能:
1. **跳出去看 caller / callee**——一个 finding 的真伪常常取决于 caller
   有没有 sanitize
2. **正向追溯 tainted var 的定义**——`q = SELECT * + userInput`，
   用户的 source 在 `userInput = req.getParameter(...)` 那一行
3. **查这个函数里有没有 guard**——`if (q.length() > 0) stmt.executeQuery(q)`
   看着像 SQL 注入，但前面那一行是 `setString` 之后才拼的，FP

**Re-Act (Reason + Act)** 把 LLM 从"一次性答卷"升级成"可以反复调用
工具的 reviewer"：

```
turn 0: system=FP_REDUCER, user=rule_prompt + finding + trace
turn 1: assistant=tool_call(read_source, {path,start,end})   <-- LLM 想看更多代码
        tool    =L<line>: <code>...                          <-- read_source 返回源码
turn 2: assistant=tool_call(has_sanitizer_on_path, {var,from,to})
        tool    =Found 1 sanitiser at L<line>: CALL setString
turn 3: assistant=FINAL: {"verdict":"FP","reason":"setString() escaped it"}
```

LLM 自己决定什么时候看够了、什么时候 FINAL 化。框架只负责：
- 调 LLM、解析回复
- 派发 tool call、把 tool 结果塞回 conversation
- 兜底（max-turns、provider null、tool throw）

### 14.1 不接 Spring-AI，自己实现的理由

Spring-AI / LangChain4j 这类框架确实把 tool calling 包装得很漂亮，
但代价是 **3MB+ 的 Spring 依赖 + 大量 reflection + 调用栈深 6-8 层**。
plumb-ai 的全部目标就是给 LLM 喂数据 + 收 reply + dispatch tool，
**用 OpenAI 官方 tool-calling JSON 协议 + 50 行裸 HTTP 就够**。
把 AI 框架引入 SAST 工具会带三个问题：
1. **jar 体积**——plumb-main all-jar 79MB，多 3MB 是个回归
2. **启动时间**——Spring bean 初始化 0.5-1.5s
3. **调试可见性**——框架把 reply 包成 `ChatResponse`，我们 print
   不到 raw JSON，出问题排不干净

自己实现的核心组件只有 9 个文件：

| 文件 | 作用 | 行数 |
|------|------|------|
| `agent/LlmMessage.java` | chat-completion 一条 message（5 个角色） | 50 |
| `agent/LlmReply.java` | LLM 一轮回复（content + tool_calls） | 60 |
| `agent/ToolCall.java` | 一次 tool call（id/name/args） | 40 |
| `agent/ToolResult.java` | 一次 tool 执行结果（ok/error） | 40 |
| `agent/ToolDefinition.java` | tool 的 OpenAI JSON Schema | 100 |
| `agent/LlmProvider.java` | LLM HTTP 客户端接口 | 50 |
| `agent/AgentResult.java` | Re-Act 一次 run 的 4 种结局 | 80 |
| `agent/AgentTask.java` | Re-Act 任务（system+user+maxTurns） | 50 |
| `agent/ReActAgent.java` | 主循环 | 140 |
| `tools/Tool.java` + 4 工具 | tool 接口 + 4 个 Step 1 工具 | 700 |

合计 ~1300 行 Java，没用任何 AI 框架。

### 14.2 4 个核心工具（Step 1）

| Tool | 干啥 | 何时用 |
|------|------|--------|
| `read_source` | 读文件 L<start>..L<end> | LLM 想看 finding 周围 ±5 行之外的代码 |
| `find_definitions` | 找变量 / 方法的定义点 | 追溯 `q = ... + userInput` 找 userInput 哪来的 |
| `find_callers` | 找方法的全部调用点 | 验证 caller 有没有 sanitize |
| `has_sanitizer_on_path` | 检查 [from..to] 行内有没有 guard / sanitize / 赋值常量 | 一击判定 FP |

`has_sanitizer_on_path` 是 FP 降误报的杀手锏——它内置 15 个已知
sanitizer（`setString` / `escapeHtml` / `normalize` / `isValid` /
`whitelist` 等），LLM 不用知道这些 API 名，工具直接回答"在 L40..L60
之间**没找到**任何 sanitiser on var `q`，变体危险"。

### 14.3 4 个 stop 条件

```java
for (turn = 0; turn < maxTurns; turn++) {
    reply = provider.chatWithTools(messages, tools, model, temp);
    if (reply == null) return PROVIDER_ERROR;
    if (reply.isFinalAnswer()) return FINAL_ANSWER;  // "FINAL: {verdict, reason}"
    if (reply.noToolCalls()) {
        // LLM 没 FINAL: 也没 tool call — 给它一次 nudge 再试
        messages.add(user("Reply with FINAL: {verdict, reason} (or [] for no findings)."));
        continue;
    }
    for (call : reply.toolCalls()) {
        result = tools.execute(call.name(), ctx, call.args());
        messages.add(tool(call.id, call.name, result.render()));
    }
}
return MAX_TURNS_EXCEEDED;
```

1. **FINAL: prefix** in assistant content (no tool calls) — happy path
2. **maxTurns** exceeded — model stuck in tool loop
3. **provider null** — network/auth failure
4. **tool throw** — model sees `error: ...` in tool body, can adapt

### 14.4 `LlmProvider` interface for testability

```java
public interface LlmProvider {
    LlmReply chatWithTools(List<LlmMessage>, List<ToolDefinition>,
                            String model, double temperature);
    default boolean isAvailable() { return true; }
}
```

`AIProvider` implements this for production.
`InMemoryMockProvider` (test-only) returns scripted `LlmReply` based on
call index — lets us write `ReActAgentTest` 10 个测试 + `AIDedupService
ReactiveTest` 8 个测试 + `AIDedupEndToEndTest` 4 个测试 = 22 个测试
**全离线跑通**，没碰网络。

### 14.5 端到端验证

`AIDedupEndToEndTest.reactiveDedupJudgesFindingAsFp`:
1. 在 temp file 写一段有 SQL 注入的代码
2. 配 `InMemoryMockProvider` script 3 轮：
   - turn 1: `tool_call(read_source)`
   - turn 2: `tool_call(has_sanitizer_on_path)`
   - turn 3: `FINAL: FP`
3. 调 `AIDedupService.judge(finding, code, dataflow)` — 返回 `FP` verdict
4. 验证 mock 真被调了 3 次、`tool` 角色 message 进了 conversation

不需要 OpenAI key、不需要联网、不需要任何后端服务。

### 14.6 Step 2 (deferred)

`§14.2` 的 4 个工具是**单函数**的。Step 2 会加：
- `get_method_annotations(method)` — 查 `@PreAuthorize` / `@Valid` 等
- `get_class_hierarchy(class)` — 查父类，避免假报父类已修的漏洞
- `get_dataflow_trace_with_facts(finding)` — 把 z3 path condition 也喂给 LLM
- `get_full_class(class)` — 看类所有方法，理解业务逻辑
- `search_pattern(regex)` — codebase-wide 搜索
- `get_call_hierarchy(method)` — 递归 call graph

外加：`AIScanService` 切换到 Re-Act path、跑通真实 LLM E2E（如果有
API key）。

### 14.7 plumb-ai 测试统计

- `plumb-ai`: 33/33 (10 ReActAgentTest + 8 AIDedupServiceReactiveTest +
  4 AIDedupEndToEndTest + 6 AgentPromptsTest + 5 RulePromptRegistryTest)
- `plumb-main`: 37/37 (no regression)
- `plumb-dfa`: 505/0/0/2 (no regression)

### 14.7.1 静态分析是 core，AI 是 optional

`plumb-ai` 是 **runtime optional** —— 它在 `plumb-main/pom.xml` 是 hard
compile dep（要 import `AIServiceFactory` / `AIDedupService` 等做 type check），
但 `ScanRunner` 通过 reflection 调它：

```java
// ScanRunner.runAiDedup / runAiScan 开头
Object ai = tryLoadAiBundle();
if (ai == null) return;  // plumb-ai 不在 classpath → no-op
```

`tryLoadAiBundle()` 用 `Class.forName("org.plumb.ai.AIServiceFactory")` +
反射调 `build()` / `dedup()` / `scan()` / `judgeAll()` / `close()`，把 AI 层
当 black box。即使 `sast-scan` 启动时 `plumb-ai.jar` 被剥掉，扫描器
仍跑完静态分析阶段、emit findings、退出码 0；只是不调 LLM 降误报。

这意味着用户能：
- **生产 CI** 用 slim dist（剥 plumb-ai）跑静态分析，体积小
- **审计/审查** 用 full dist（带 plumb-ai）跑 LLM 降误报

### 14.7.2 Tools 不都依赖 IR — Claude Code-style file ops

`§14.2` 那 4 个 IR-aware 工具（find_definitions / find_callers /
has_sanitizer_on_path / read_source）已经存在，但用户说"不接 spring-ai，
tool 不能都跟 AST/TAC 结合"——3/4 强依赖 TacProgram，IR 不在时
退化为 "no program available"。

**Step 2 加 3 个 IR-independent 工具 + 给 IR 工具加 Grep fallback**：

| 工具 | IR 依赖 | Grep fallback |
|------|--------|--------------|
| `read_source` | ✗ | 不用 (本来就读文件) |
| `find_definitions` | ✓ | ✓ (搜 `var` 在声明/赋值/参数位置的 regex) |
| `find_callers` | ✓ | ✓ (搜 `name(` 调用点 across .java/.kt/.scala) |
| `has_sanitizer_on_path` | ✓ | ✓ (在 finding 的 file 里搜 15 known sanitizers + branch guard) |
| `grep` (NEW) | ✗ | — |
| `glob` (NEW) | ✗ | — |
| `list_dir` (NEW) | ✗ | — |

`GrepTool` 设计要点：
- Java regex（支持 `\b` 词边界、`(a|b)` 选择）
- 默认 50 行 cap，max 500 行（防 runaway）
- `ignore_case` 开关
- `file_glob` 过滤（支持 `**` 递归）
- skip `.git/` `build/` `target/` `node_modules/` 等噪音目录
- binary 文件检测（前 8KB 含 NUL → skip）
- 一次只读 8KB（grep 是粗筛，LLM 想看全文件用 `read_source`）

`GlobTool` 支持 `*` `?` `**`（**有**就递归），不需要拉额外库，自己写
40 行 matcher。

`ListDirTool` 0-3 深度（默认 1），返回 `[D]` / `[F]` 排序后目录树。

### 14.7.3 Re-Act 工具集 v2 = 7 tools

```
[always works — no IR]
  read_source   (path, start, end, max_lines)
  grep          (pattern, path, max_results, ignore_case, file_glob)
  glob          (pattern, base_dir, max_results)
  list_dir      (path, max_depth, max_entries)

[precise when IR available, fallback to grep otherwise]
  find_definitions      (symbol, kind)
  find_callers          (method, max)
  has_sanitizer_on_path (var, from, to)
```

`AIDedupService` 构造时注册全部 7 个；`AIScanService` 切到 Re-Act 时
也挂同样 7 个。LLM 在 `find_callers` 失败时仍能用 `grep "executeQuery("`
手动搜调用点，IR 不可用时工具链不破。

### 14.7.4 Step 3 — 6 个 cross-function 工具 (IR-independent)

用户 feedback: "工具不能都跟 AST/TAC 结合"。Step 2 加了 3 个 Claude
Code-style file ops (grep/glob/list_dir) 但 IR 工具 (find_*) 还是
强依赖 TacProgram。Step 3 进一步：

| 工具 | 干啥 | 何时用 |
|------|------|--------|
| `get_method_annotations` | 读 class/method/param 上的 @Annotation | "this finding 是不是在 @PreAuthorize 方法里？" |
| `get_class_hierarchy` | 找 extends / implements | "这个 controller 继承自哪个基类？是不是 TrustedService 子类？" |
| `get_dataflow_trace_with_facts` | 重新格式化 dataflow trace (source/propagate/sink) + Z3 facts | "trace 路径上 L7 的 BRANCH 是不是 sanitizer？" |
| `get_full_class` | 读整个 .java 源 (按 class name 找) | "看 controller 完整 surface (所有 method 怎么 sanitize input)" |
| `search_pattern` | camelCase 4-variant regex (userRepo / UserRepo / USER_REPO / user_repo) | "这个常量在哪定义？where is CONSTANT_FOO used?" |
| `get_call_hierarchy` | 递归 walk caller chain (BFS, depth ≤ 4) | "这个 sink 从 public HTTP 真的能 reach 到吗？walk up call chain" |

**设计原则**：6 个工具**全部不依赖 IR** — 都是 regex / 简单 text parse +
filesystem walk。这样:

- LLM 在 TacProgram 为 null 时仍能查
- 静态分析失败 parse 的文件 LLM 仍能 review
- 没有 `<TacFunction>` 引用的工具可以独立部署

`get_class_hierarchy` 用一个 regex 抓 `class Foo extends Bar
implements Baz, Qux` — Java 语法对这个模式足够 regular。
Modifier group 用 keyword list (public/private/static/...) 而不是
任意 word，避免贪婪吃 `FooController`：

```python
\b(class|interface|enum)\s+
(?:(?:public|private|protected|static|final|...|sealed|non-sealed)\s+)*
(?<name>[A-Za-z_][A-Za-z0-9_]*)
(?:\s+extends\s+(?<ext>...))?
(?:\s+implements\s+(?<impl>...))?
```

bug: 第一版 modifier group 用 `[A-Za-z_][A-Za-z0-9_]*\s+?` — 太宽，
贪婪把 `FooController` 吃了，name group 拿到 `extends`。改成
keyword list 后修。

`get_call_hierarchy` 调 `find_callers` 多次：BFS，节点用 `Set`
dedup，`max_depth=4` / `max_nodes=100` cap。返回树形 (depth 缩进)
LLM 一眼看出 "method <- caller_a <- caller_b <- public_endpoint"。

`search_pattern` 把 1 个 word 展成 4 个 naming variant（camelCase /
PascalCase / UPPER_SNAKE / snake_case）。plain regex `\b<word>\b`
只会匹 camelCase 一种。

### 14.7.5 AIScanService 切到 Re-Act

之前 AIScanService 用单 prompt — 一次性把 source snippet + rule list
塞进 user turn，LLM 看一眼就回 JSON array。问题: LLM 看不到 caller /
caller 的 annotation / 类的完整 surface — 经常漏报（"this looks
fine, I can't tell if `svc.create()` sanitises the input"）。

切到 Re-Act 后:
- AIScanService 构造时挂 12 工具
- `scanReactive()` 调 `ReActAgent.run(AgentTask.bugScan(...), ToolContext)`
- maxTurns=8 (vs AIDedupService 的 5，bug scanner 经常需要更多 turn)
- 仍保留 single-prompt fallback (`aiReactive=false`)
- parseReply 同时接受 `FINAL: [...]` 和裸 `[...]` 格式

LLM 现在能在写 finding 之前调:
- `get_full_class` 看 controller 的其他 method 有没有 sanitize
- `get_class_hierarchy` 看 extends 链
- `get_method_annotations` 看 method 上有没有 @PreAuthorize / @Valid
- `search_pattern` 在 codebase 找相同的 anti-pattern 看是不是
  pattern-level FP

### 14.7.6 Step 3 测试统计

- `plumb-ai`: 73/73 (从 54 → 73, +19) — CrossFunctionToolsTest 12 +
  AIScanServiceReactiveTest 7
- `plumb-main`: 37/37 (无回归)
- dist jar: 含 12 工具 + ReActAgent + AIDedupService (12 工具) +
  AIScanService (12 工具)

### 14.7.7 Step 4 — 真实 LLM E2E + MiniMax + slim dist

用户说"我已经在环境变量中配置了 MiniMax 的 apikey". 配了:
- env `MINIMAX_API_KEY` (长度 125, `sk-cp-...` 开头)
- endpoint `https://api.minimax.chat/v1/chat/completions`
- model `MiniMax-Text-01`

**首次 E2E 失败 root cause**: MiniMax 模型在 Re-Act 多轮 tool calling
模式下不会自然 FINAL 化。每个 finding × 5 turn × 1.6s/turn = 8s × 53 findings
= 7 分钟 → timeout. 报错 `max-turns-exceeded (model kept calling
tools, no FINAL: after 5 turns)`.

**修**: `AgentPrompts.FP_REDUCER` / `BUG_SCANNER` 加显式 FINAL 提示:

```
When you have enough information to judge the finding, you
MUST reply with EXACTLY this prefix on the first line:
FINAL: {"verdict":"TP|FP|UNKNOWN","reason":"<one short sentence>"}
```

加 "Call them only when you genuinely need more information
than the initial user turn provides" — 防止模型过度调 tool.

**修后 E2E 成功**:
- 17 findings × ~5.7s = 96s 跑完 TinyAiTest
- 12 个 finding 被判 TP (硬编码密码 / 缺 package / 缺 Javadoc)
- 4 个 FP (style-only: indentation, 命名法)
- 1 个 UNKNOWN (file access)
- key 真实 LLM judgement 对: `password = "P@ssw0rd123"` → TP
  ("The code assigns a hardcoded password directly to a
  variable and uses it in a database query without any
  sanitization or evidence of a secret store")

**集成 test**: `LlmIntegrationTest` (5 tests) 用
`@EnabledIfEnvironmentVariable(named = "PLUMB_AI_E2E", matches = ".+")`
默认 skip, 设 `PLUMB_AI_E2E=1` 跑. 测试:
- `aiProviderChatWithToolsReturnsReasonableReply` (1-shot)
- `aiProviderIsAvailableWithConfiguredKey`
- `reActAgentSingleShotFpReduction` (1-shot FINAL: TP/FP/UNKNOWN)
- `reActAgentCallsReadSourceForComplexFinding` (写真实 .java 文件, model 应调 read_source 查 sanitizer)
- `reActAgentToolDefinitionJsonSchema` (cheap, no network)

跑通: **plumb-ai 78/78 (含 5 个真实 LLM E2E, 20s)**.

### 14.7.8 slim dist 打包脚本 (`scripts/package-slim.ps1`)

**两个考量**:
1. **完全删除 plumb-ai classes** — 失败, 因为 `plumb-main/ScanConfig.aiConfig`
   是 `org.plumb.ai.AIConfig` 类型 (compile-time ref), 运行时 `NoClassDefFoundError`.
2. **Config-level 关闭** — 通过 `ai.enabled: false` + 把 plumb-ai
   class files 留在 jar 里, 但 runner 短路所有 AI passes.

采用方案 2. `scripts/package-slim.ps1`:
- 拷 bin/conf 从 full dist
- 重写 `conf/default-config.yaml` 设 `ai.enabled: false`
- 拷全 jar unchanged (76MB)
- 加 `README-SLIM.txt` 解释 trade-off

**用户想要更紧 footprint**: 删 `lib/plumb-main-*-all.jar` 跟
`mvn -pl plumb-ai,plumb-main -am` 不带 plumb-ai 重新 shade. 反射
guard 会 log "plumb-ai not in classpath; AI pass skipped" 然后
继续静态分析.

E2E 验证 slim dist:
- `--list-rules` 工作
- 73 unique rules fired
- 退出码 0

**测试统计** (Step 4 final):
- plumb-ai: 78/78 (含 5 个 LLM E2E)
- plumb-main: 37/37 (无回归)
- dist (full): 76MB jar, 12 工具, ReActAgent
- dist (slim): 76MB jar (same classes), `ai.enabled: false`

### 14.7.9 Step 5 — z3 path condition facts + 真实 production E2E

**z3 path condition facts emit 到 flowSteps**:
- `TaintedCheckerBase.checkSinkAtCall` 现在加 z3-fact FlowStep
- 触发条件: `solverBridge != null` AND `composePathCondition(...)` 不空
- emit 格式: `FlowStep("z3-fact", "", -1, -1, "", "Z3 path condition: <conjunction>")`
- `GetDataflowTraceWithFactsTool.classify` 加 `z3-fact` role 分类

LLM 现在能看到 z3 事实，reasoning 路径可达性更精准。

**真实 production E2E** (rNNN example, 73 findings, 真实 MiniMax LLM):

```
sast-scan --ai-dedup -c llm-yaml -i examples/rNNN/RNNNPlumbDfaPortsDemo.java
→ 297 findings (NDJSON lines)
→ 158 FP, 135 TP, 4 UNKNOWN
→ ~6 分钟 (73 findings × ~5s/each, 1-shot FINAL, no tool retries)
```

distribution:
- 158 FP: PlumbDfaPortsDemo 是 anti-pattern demo 文件 (static field
  hardcoded, missing authorization 等是 intentional design choices)
- 135 TP: model 标 "true positive" (model 对 security 严格)
- 4 UNKNOWN: file access issue (model 报告 "Unable to read source file")

**测试统计** (Step 5 final):
- plumb-dfa: 512/0/0/2 PASS (was 505 → 512, R460-41 multi-dim GEP)
- plumb-ai: 78/78 (含 5 LLM E2E, 0 regression after z3 facts change)
- plumb-main: 37/37 (无回归)
- dist (full): 76.1MB jar, 12 工具, ReActAgent
- dist (slim): 76.1MB jar, ai.enabled=false, 73 rules fired

**Step 5 lessons**:
1. **z3 facts 通过 FlowStep "z3-fact" role 透传** — 不需要改 Finding 字段
   结构, 不需要改 reporter. FlowStep 是 record, immutable. 增量加一个
   new role "z3-fact" 即可
2. **Production E2E 比 LlmIntegrationTest 更值** — 单 file 17 findings
   不足以验证 agent. rNNN 73 findings × 5s = 6 min 测试模型在
   production 量级的稳定性
3. **MiniMax 在 PlumbDfaPortsDemo 上 judgment 偏 FP** — 60% FP 对
   anti-pattern demo 是合理的, 模型识别出 "intentional design"

### 14.7.10 Step 6 — `sast-scan --ai-dedup-stats` 报告

**问题**: `--ai-dedup` 把 verdict 埋进每条 NDJSON 的 `ai_verdict` 字段, 没法一眼
看 AI 整体表现. 用户想知道: "AI 真的管用吗? 哪些规则 AI 觉得是 FP?"

**方案**: 新增 `--ai-dedup-stats` 开关, AI dedup 跑完后往 **stderr** 打 verdict
分布 + 每条规则 FP 占比 Top 10. stdout 的 NDJSON 仍然干净, 可以继续 pipe
给下游.

**输出格式** (stderr):
```
AI dedup stats
==============
Total findings:   297
Reviewed:         297 (100.0%)
Dropped (FP):     158
Kept:             139
Elapsed:          6m14s

Verdict distribution:
  TP            135  (45.5%)
  FP            158  (53.2%)
  UNKNOWN         4  ( 1.3%)

Top 10 rules by FP ratio (FP / total):
  StaticApiKey                    8/10 (80.0%)
  HardcodedCredential             6/8  (75.0%)
  ...
```

**实现**:

1. **`ScanConfig.cliAiDedupStats`** (boolean, default false) - CLI 开关
2. **`SastMain`** 加 `--ai-dedup-stats` (no-arg) - `cli.hasOption("ai-dedup-stats")` 设 `cfg.cliAiDedupStats = true`
3. **`ScanRunner.runAiDedup`** 抽 `computeDedupStats(findings, judged, dropOnAiFp)` + `DedupStats` 静态内部类
4. **`ScanRunner.printDedupStatsImpl(stats, elapsedMs)`** 写 stderr

**`DedupStats` 设计**:
```java
static final class DedupStats {
    final int total;                            // 静态规则扫出的总 finding 数
    final EnumMap<Verdict, int[]> verdictCounts;  // TP / FP / UNKNOWN → count
    final LinkedHashMap<String, int[]> perRuleCounts;  // ruleName → [TP, FP, UNKNOWN, total]
    final int droppedFp;                        // 因 dropOnAiFp 丢的 FP
    final int kept;                             // 进最终报告的 finding 数
    DedupStats withDropped(int droppedFp) {...}
    DedupStats withKept(int kept) {...}
}
```

`computeDedupStats` 反射调 `AIDedupResult.verdict()` / `.reason()`, 不 hard-dep
plumb-ai (跟 runner 整体保持一致). `Verdict.parse(s)` 把任何非 TP/FP 的 verdict
(包括模型编的 "MAYBE") 规范化为 UNKNOWN, report 不会爆.

> **Step 7 改**: `computeDedupStats` 现在直接接 `Map<String, String> verdict`
> (key=`file:line:hash`, value=`"VERDICT|reason"` 字符串), reflection
> 删了. `plumb-ai` 是 `plumb-main` 的 hard dep, `Verdict` enum 直接在
> `plumb-main` 包内定义. 见 §14.7.11.

**关键设计选择**:

- **package-private `computeDedupStats` + `DedupStats` + `Verdict`** - 让
  `RAiDedupStatsTest` 可以直接测试, 不需要起 AIDedupService
- **`pct` / `padLeft` / `padRight` / `formatElapsed` 都是 static private**,
  包 `*Public` 包装 - 测试可以访问, 但 main flow 还是用 private 版本
- **stderr 不 stdout** - NDJSON 仍然 pipeline-friendly
- **`Dropped (FP)` 和 `Kept` 是 runner 在 dedup loop 后填的** - 跟 `dropOnAiFp`
  flag 强耦合, 不能在 `computeDedupStats` 阶段算 (那时候还在算 verdict 分布,
  还没决定哪些 finding 留哪些丢)

**测试统计** (Step 6 final):
- plumb-main: **50/50 PASS** (was 37, +13 new in `RAiDedupStatsTest`)
- plumb-parser / plumb-tac / plumb-rules / plumb-reporter / plumb-style: 941/0/0/0 (无回归)
- plumb-dfa: 512/0/0/2 (无回归)
- plumb-ai: 78/78 (无回归)

**`RAiDedupStatsTest` (13 tests)**:
- `computeDedupStatsTalliesVerdicts` - 3 findings 1 TP / 1 FP / 1 UNKNOWN
- `computeDedupStatsTalliesPerRule` - 4 findings 跨 2 规则, perRule counts 正确
- `computeDedupStatsClampsUnknownVerdictString` - 模型编 "MAYBE" 规范化为 UNKNOWN
- `computeDedupStatsWithMissingKeyCountsAsUnknown` - judged map 缺 key 算 UNKNOWN
- `computeDedupStatsWithNullVerdictFieldIsUnknown` - `AIDedupResult.verdict()` 返回 null
- `verdictParseClampsCustomStrings` - "tp" / "Maybe" / null 都正确处理
- `dedupStatsWithDroppedAndWithKeptReturnCopies` - `withX` 返回新实例
- `printDedupStatsWritesVerdictDistribution` - capture stderr 验证 verdict 行
- `printDedupStatsWritesTopRulesByFpRatio` - Top 10 按 FP ratio 排序
- `pctHandlesZeroTotal` - div-by-zero 安全
- `padLeftRightAlignsNumbers` - "135" → "   135"
- `padRightLeftAlignsLabels` - "TP" → "TP      "
- `formatElapsedHandlesAllUnits` - ms / s / m+s

**使用场景**:
```bash
# 1. 一次性看 AI 整体表现
bin/sast-scan -c conf/default-config.yaml -i src \
    --ai-dedup --ai-dedup-stats 2>ai-stats.txt >findings.ndjson

# 2. CI 集成 - 监控 AI FP 占比
FP_RATIO=$(bin/sast-scan -c conf/default-config.yaml -i src \
    --ai-dedup --ai-dedup-stats 2>&1 >/dev/null | grep '^  FP' | awk '{print $NF}')
# 如果 FP 占比 < 30% 说明 AI 太严格, 考虑换模型
```

**Step 6 lessons**:
1. **抽出 `computeDedupStats` 让 stats 可独立测试** - runner 整体
   reflection-based (跟 plumb-ai 解耦), 但 stats 计算 pure, 应该
   package-private + static + 直接喂预制 judged map 让 unit test 测
2. **`withDropped` / `withKept` builder pattern** - DedupStats 是 immutable
   record-like, 避免 mutable state, withX 返回新实例 (verified by test)
3. **stderr / stdout 分离是必须的** - 用户要把 NDJSON pipe 给下游工具
   (SARIF converter, IDE plugin), verdict 报告不能污染 NDJSON stream
4. **`Verdict.parse` 主动 clamp 未知值** - 模型可能编出 "MAYBE" / "PROBABLY" /
   "ALMOST" / 等任意字符串, 不要让它 crash 报告. 一律规范化为 UNKNOWN
5. **per-rule breakdown 按 FP ratio 降序, tie-break 绝对 FP 数** - 用户最想
   看 "哪些规则 AI 觉得是 FP". ratio + count 双维度排序最有 signal
6. **`pct(0, 0)` 不能 div-by-zero** - 0 findings 边界 (新项目 + 没扫出) 不能崩
7. **`formatElapsed` 三档 (ms / s / m+s)** - 大部分扫描 < 1min, 用 "Xs" 简明;
   长扫描用 "XmYs" 给分钟级感. ms 单位 < 1s 时给
8. **跳过 `AIDedupService.dedupStats()` 抽 API** - 原本计划在 plumb-ai
   加 stats API, 但发现 runner 已经有 data, 在 runner 端算 stats 更直接
   避免改 plumb-ai 接口 + 反射调用. 取消的 todo: "Add AIDedupService.dedupStats()"

**Step 7+ (deferred)**:
- 真实 LLM 测 plumb-dfa Juliet corpus (production benchmark)
- 真实 LLM 测 plumb-style 规则
- `get_call_hierarchy` 加 callEE (callee chain)
- `get_dataflow_trace_with_facts` 加更详细 z3 facts (Step 5 已有基础,
  还可以加 source/snippet/sanitizer 详细)
- `get_method_annotations` IR 路径加 method-parameter FQN 映射
- 加 multi-finding batch API (让 model 一次 review 多个 finding 共享 context)
- slim dist 用 mvn profile 完全 shade 不带 plumb-ai (invasive, skip)

**文档**: `doc/usage/CLI.md` §2.4 (R-? AI dedup 报告 --ai-dedup-stats)

### 14.7.11 Step 7 — slim plumb-ai / rule-owned prompts / no reflection

**问题**: Step 1-6 之后的 `plumb-ai` 有 33 个文件, 8 个 service class
(`AIConfig` / `AIProvider` / `AIServiceFactory` / `AIDedupService` /
`AIDedupResult` / `AIScanService` / `AIScanFinding` / `RulePromptRegistry` /
`Aggressiveness`), 加上 `plumb-main` 通过 reflection 调
`AIServiceFactory` 来保持 "plumb-ai 是 runtime optional" 的语义.
`RulePromptRegistry` 把所有 rule prompts 集中放在 plumb-ai
classpath 里的 `rule-prompts/<RULE_ID>.md`, 跟 checker 实现脱节.

reflection 路线带来三个问题:
1. **prompt 跟 checker 实现分离** - 改 checker 行为时要改两个地方, prompt
   不知道 checker 当前实际检测什么
2. **reflection 调 reflection-y 框架调试困难** - 任何 LLM bug 要追 6-8 层
   调用栈; `LLM fail-soft` 实际 fail 在 reflection 层时 log 看不出来
3. **`Aggressiveness` 是个非常宽的总开关** - "low / medium / high" 跟具体
   规则实际需要的 `dedup on/off` 没对齐. 实际数据是: `catchGeneric`
   完全不需要 dedup (规则本身就低 FP); `HardcodedCredential` 离开
   dedup 几乎没法用

**方案 (4 个并行改动)**:
1. **Slim plumb-ai**: 删 8 个 service class, 只留 agent 框架壳
   (12 个 agent 抽象 + 12 个 tool + 1 个 prompts)
2. **Rule owns its prompt**: `DfaChecker.getAiPrompt(Finding) -> String`
   `StyleCheck.getAiPrompt(Finding) -> String` `AstChecker.getAiPrompt(Finding) -> String`
   - 返回 `null` = 不参与 AI dedup, 返回非 null = rule prompt
3. **No reflection in ScanRunner**: `plumb-ai` 是 `plumb-main` 的 hard dep,
   `ScanRunner` 直接 `import org.plumb.ai.agent.*` / `org.plumb.ai.tools.*`
   构造 LlmProvider / ReActAgent / ToolRegistry
4. **`Aggressiveness` 删了**: 用 `checks.<rule>.enabled: true` 配
   per-rule enable/disable, 跟 AI dedup 完全解耦

**Slim plumb-ai 最终结构** (28 个 source 文件):
```
plumb-ai/
  agent/                # 11 files: LlmMessage / LlmReply / ToolCall / ToolResult
                       # ToolDefinition / LlmProvider / AgentResult
                       # AgentTask / ReActAgent
                       # LlmConnection           (NEW, replaces AIConfig)
                       # OpenAiCompatibleLlmProvider (NEW, replaces AIProvider)
  prompts/              # 1 file: AgentPrompts (FP_REDUCER / BUG_SCANNER)
  tools/                # 16 files: Tool / ToolContext / ToolRegistry
                       # 12 tool implementations
```

`plumb-ai/pom.xml` deps: `plumb` (parent) + `plumb-rules` + `plumb-reporter`
+ jackson + slf4j + junit + assertj. **不**依赖 `plumb-tac` /
`plumb-dfa` —— agent 框架壳定位, 跟 analysis engine 解耦.

**Rule prompt 所有权模型**:

```java
// plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/DfaChecker.java
default String getAiPrompt(Finding finding) {
    return null;  // no AI dedup
}

// TaintedCheckerBase has auto-generated prompt from TaintRule metadata
@Override public String getAiPrompt(Finding f) {
    TaintRule r = rule();
    return "RULE: " + r.ruleId() + " (" + r.cwe() + ", " + r.severityLabel() + ")\n"
         + "Source kind: " + r.sourceKindName()
         + " Sink family: " + r.sinkFamilyKey()
         + " Domain: " + r.domain().label() + "\n"
         + "This rule fires when a tainted value flows from the source family "
         + "into the sink family without a recognised sanitizer on the path. ...";
}

// Individual checkers override for hand-tuned prompts
// e.g. SqlInjectionChecker.getAiPrompt() lists CWE-89 specific sanitizers
```

**三级 prompt 解析**:
1. **TaintedCheckerBase auto-generates** from `TaintRule.ruleId()/cwe()/severityLabel()/sourceKindName()/sinkFamilyKey()/domain().label()` (覆盖所有 33 taint checkers)
2. **DfaChecker / AstChecker inline `getAiPrompt` 手动 override** (覆盖
   18 高价值 dfa checkers + 2 style checkers, 见 `_trash/patch_step7.py`
   批量注入的 `private static final String AI_PROMPT = """..."""`)
3. **`--rule-prompts-dir` 用户 .md 覆盖** (最优先, 完整替换 inline)

返回 `null` 的规则不参与 AI dedup; verdict 记为 `(no-rule-prompt)`,
跑 stats 时按 UNKNOWN 计入.

**Real LLM E2E (Step 7 first run)**:
- examples/rNNN: 297 findings, 跑 `--rule HardcodedCredential`
- 跑前 18 个 prompt 已经 patch, 但 `SqlInjectionChecker` 等还没单独
  跑（HardcodedCredentialChecker 这次 patch 后覆盖 prompt）
- 改完跑一次: 0 (no-rule-prompt) warning 才是真正的 fix

**测试结果** (Step 7 final):
- plumb-main: **50/50 PASS** (37 + 13 `RAiDedupStatsTest`)
- plumb-ai: **54/54 PASS** (5 LLM E2E @EnabledIfEnvironmentVariable "PLUMB_AI_E2E", default skip)
- plumb-dfa: 516/0/0/2 (无回归, R340 类的 Juliet corpus tests 单独跑
  或通过 `-Dtest=!R340*` 跳过)
- plumb-parser / plumb-tac / plumb-rules / plumb-reporter / plumb-style:
  941/0/0/0 (无回归)

**Step 7 lessons**:
1. **Rule owns its prompt 永远比 registry 集中式 prompt 强** - prompt 跟
   规则实现放一起, 改 checker 时 prompt 跟着改; 用户看 checker 源码就
   知道 LLM 在看什么; 测试 checker 自动测 prompt (TaintedCheckerBase
   的 default prompt 是从 `TaintRule` 元数据派生的, 不会跟实际行为脱节)
2. **reflection 路线每次都后悔** - 第一次 reflection 是为了 "plumb-ai
   optional" 留个 hook, 但实际上 plumb-ai 已经 slim 到 28 文件, 跟
   `plumb-main` 同进程跑没区别. reflection 调用栈 debug 噩梦 + 失去
   IDE 自动补全 + 失去编译期类型检查, 收益不抵成本
3. **`Aggressiveness` 这种"全局粗粒度开关"是 anti-pattern** - 用户
   实际想要的是 "这条规则要 dedup, 那条不要"; 用 `checks.<rule>.enabled`
   + `getAiPrompt` 返回 null 的二分已经足够, 三档"low/medium/high"
   是 over-engineering
4. **`plumb-ai` 不依赖 `plumb-dfa` 是关键** - 之前 plumb-ai
   直接 import DFA finding 类型, 任何 DFA 接口变化都要 plumb-ai 跟着改.
   slim 后 plumb-ai 只看 `org.plumb.reporter.Finding` + `org.plumb.rules.RuleDef`
   跟 analysis engine 完全解耦; 未来如果 plumb-style 也要 AI dedup,
   plumb-ai 直接复用来不及
5. **`-Dtest='!R340*'` 排除 Juliet corpus dump tests 跑 dev inner loop** -
   800+ Juliet 文件 dump 单测 8-15min, dev inner loop 等不起; nightly
   CI 跑全. Surefire `!` pattern 必须配合 `*` 才生效 (用 `-Dtest=*,!R340*`)
6. **Python 脚本 import 同目录的 module 会触发 module-level 副作用** -
   我用 `importlib.util.spec_from_file_location("ap", "add_prompts.py")`
   触发它的 top-level "n=0 ... for cls, path in FILES.items() ..." 二次
   跑了 patch, 覆盖了已经 patched 的文件. 解决: 用 `ast.parse` + 切除
   `n=0 / for-loop / Done print` 后再 exec, 见 `_trash/patch_step7.py`

**Step 7+ (deferred)**:
- 真实 LLM 测 plumb-dfa Juliet corpus (production benchmark)
- 真实 LLM 测 plumb-style 规则
- `get_call_hierarchy` 加 callEE (callee chain)
- `get_dataflow_trace_with_facts` 加更详细 z3 facts
- `get_method_annotations` IR 路径加 method-parameter FQN 映射
- 加 multi-finding batch API
- Re-add bug scanner (--ai-scan) 1-2 day effort, requires ReActAgent
  + tools re-wiring
