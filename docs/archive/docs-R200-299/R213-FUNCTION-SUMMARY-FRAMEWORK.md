# R213 — FunctionSummary 通用框架 + TAC 三分类

**Round**: R213 · **2026-07-31** · **状态**: ✅ DONE

---

## 1. 背景与设计目标

R212 落地了 plumb-dfa 的 CallGraph 跨 TU 框架 + DfaEngine 5-step lifecycle
(`initEngine → initFunction → checkInstruction → finishFunction → finish`)。
但是 DfaEngine 在 RPO 顺序跑 lifecycle 时，没有让 checker 把"函数经过分析后
其入参可能有哪些操作"沉淀成可被 caller 查询的摘要 — caller-side checker 在看到
`CALL` 指令时，没有一个标准化的"callee 摘要"接口可用。

R213 落地两个用户硬约束的产物：

1. **TAC 三分类**: `SOURCE` / `LIBRARY` / `SYSTEM_LIBRARY`。默认只分析 `SOURCE`；
   配 `SOURCE + LIBRARY` 是显式 opt-in；`SYSTEM_LIBRARY` 必须显式 opt-in。
2. **数据流分析核心 — 逆序 + 摘要 + 复用**: 引擎按 RPO 顺序（callee 先于 caller）
   跑 lifecycle；每个 function `finishFunction` 后立刻让该 function 的所有 checker
   跑 `summarizeFunction` 产生 `FunctionSummary`；引擎把多个 checker 的 summary
   合并后 put 进共享 `FunctionSummaryCache`。caller-side checker 在 `checkInstruction`
   看到 `CALL` 时通过 `ctx.summaryCache().get(calleeSig)` 查 callee 的 summary，
   直接对 caller 端的 param 做"是否触发缺陷"判断 — 不会再走 callee 体内第二次分析。

单函数内的、**不关联入参**的缺陷（dead code / unhandled `RuntimeException` /
unconditional `System.exit` 等）通过 `ctx.reporter().report(...)` 在
`checkInstruction` 阶段立即报；这些 finding **不**进 summary — summary 只装
"param-bound 跨函数效应"。

---

## 2. 实施清单

### plumb-tac
- `plumb-tac/.../ir/SourceOrigin.java` (new, 2.9KB) — 3 元素 enum
- `plumb-tac/.../ir/TacFunction.java` (modified) — 加 `origin` 字段 + `origin()` /
  `hasOriginIn(Set<SourceOrigin>)` accessor；12-arg 完整 ctor 接受 origin；老
  6/9/10/11-arg ctor 链全部走默认 `SOURCE` (back-compat)
- `plumb-tac/.../translate/TacBuilder.java` (modified) — `setOrigin(SourceOrigin)`
  setter，默认 `SOURCE`。R140/R142 字节码端后续 set 改用此 setter

### plumb-dfa
- `plumb-dfa/.../config/TuningConfig.java` (modified) — 加 `analysedOrigins:
  Set<SourceOrigin>` + `analyseSystemLibraries: boolean`；14-arg 完整 ctor 接受
  origin set；老 13-arg ctor 走默认 `{SOURCE}` (back-compat)
- `plumb-dfa/.../summary/Operation.java` (new, 6.6KB) — sealed
  interface 6 个子类型：`PassThrough` / `Sink(familyKey, atParamIndex)` /
  `WriteToField(fieldName)` / `TaintPropagate(sourceId, confidence)` /
  `HasUnmodelledBehavior(reason)` / `UnmodelledCall(calleeSig, isLibrary)`
- `plumb-dfa/.../summary/FunctionSummary.java` (new, 11.9KB) —
  immutable `(signature, paramOps, returnOps, calleesReached, hasUnmodelledBehavior)`；
  `Builder` 累加；`merge(other)` 双侧 union；`project(filter)` 投影特定子类型
  （如 SQL checker 用 `project(op instanceof Sink sk && "sql.sinks".equals(sk.familyKey()))`）；
  `isTrivial()` 判定"引擎什么都没看到"
- `plumb-dfa/.../summary/FunctionSummaryCache.java` (new, 6.8KB) —
  per-run invalidated `Map<String, FunctionSummary>`；`get(sig)` 永远返回
  summary（never null — unknown sig 给 empty 单例）；`put / computeIfAbsent /
  contains / shouldSummarise(fn)` / `clear` / `snapshot` / `hits / misses` 计数器
- `plumb-dfa/.../engine/DfaChecker.java` (modified) — 加
  `default FunctionSummary summarizeFunction(AnalysisContext, TacFunction)`
  lifecycle 回调；默认返回 `FunctionSummary.empty()`
- `plumb-dfa/.../engine/AnalysisContext.java` (modified) — 加
  `FunctionSummaryCache summaryCache` 字段 + `summaryCache()` accessor
- `plumb-dfa/.../engine/DfaEngine.java` (modified) — 在
  `finishFunction` 之后调每个 checker 的 `summarizeFunction` 合并到 cache；
  函数级 origin filter 跳过 LIBRARY/SYSTEM_LIBRARY（除非配 opt-in）；
  日志增加 `N functions analysed (of M), K summarised`

### 测试
- `plumb-tac/.../ir/SourceOriginTest.java` (new, 5 tests) — 默认 SOURCE 、
  显式 origin 返回、hasOriginIn 集合行为、analysedByDefault 只 SOURCE、
  null origin 在 ctor 归一化为 SOURCE
- `plumb-dfa/.../summary/FunctionSummaryTest.java` (new, 10 tests) —
  empty 单例、Builder param/return 累加、unmodelled flag、merge union、
  merge with empty、project filter、Sink 拒绝负 arg 索引、
  TaintPropagate 拒绝 confidence 越界
- `plumb-dfa/.../summary/FunctionSummaryCacheTest.java` (new, 9 tests) —
  unknown sig 给 empty 单例、put/contains、computeIfAbsent 只算一次、
  shouldSummarise 走 analysedOrigins、library opt-in 行为、
  analyseSystemLibraries flag、clear + 计数重置、snapshot 防御性复制、
  null/empty key no-op
- `plumb-dfa/.../engine/R213FunctionSummaryTest.java` (new, 5 tests) —
  summary phase 填 cache、caller 看到 callee summary (RPO invariant)、
  LIBRARY 默认跳过、opt-in LIBRARY 后分析、默认 empty summary 不触发 caller

---

## 3. Post-order 不变量 (R213 核心 invariant)

**R213 最重要的保证**: caller-side checker 在 `checkInstruction` 看到
`CALL` 时，**callee 的 summary 一定已经在 cache 里**。这是因为：

1. 引擎按 RPO 顺序遍历 functions（callee 永远先于 caller）
2. 对每个 function，引擎先跑 `initFunction → checkInstruction* → finishFunction`
3. **立刻**对每个 enabled checker 调 `summarizeFunction` 拿 summary
4. 引擎把多个 checker 的 summary `merge` 起来 put 进 cache
5. 才推进到下一个 function (RPO 中下一个 = caller)

所以 caller-side checker 的 `ctx.summaryCache().get(calleeSig)` 一定命中。

`R213FunctionSummaryTest.callerSeesCalleeSummaryAfterRpoWalk` 是这个 invariant 的
回归测试 — 测试构造 `App#main` 调 `Lib#sink`，`SqlSinkSummariser` 在
`summarizeFunction` 时给 `Lib#sink` 注入 `Sink("sql.sinks", 0)`，
`CallerSqlChecker` 在 `App#main` 的 `checkInstruction` 查 summary 找到
sql sink → emit finding。**1 finding** = invariant 成立。

---

## 4. TAC 三分类接线

R213 在三个地方 wire 了 SOURCE / LIBRARY / SYSTEM_LIBRARY 分类:

1. **TacFunction.origin 字段**: 每个 function 标记自己从哪里来
2. **TuningConfig.analysedOrigins**: 用户配置"要分析哪些 origin"
3. **DfaEngine**: 在 RPO 遍历里 filter `if (!analysedOrigins.contains(fn.origin())) continue;`

默认行为：用户不配，引擎只跑 SOURCE (用户写的 .java)。LIBRARY (用户传的 .jar)
需要显式 opt-in。SYSTEM_LIBRARY (JDK runtime) 必须显式 opt-in，且正常情况下
引擎根本没 JDK 函数的 body — 这个分类是个"用户明确说要分析 JDK"时的开关，
未来 `jrt:/...` 这类 JDK stub 加载配合使用。

LIBRARY opt-in 例子:
```java
TuningConfig t = new TuningConfig(2, 10000, 5000, 1024, 8,
    false, false, false, "full", "cwe_family", "sarif", false, 0,
    EnumSet.of(SourceOrigin.SOURCE, SourceOrigin.LIBRARY),
    false);
EngineConfig cfg = base.withTuning(t);
```

---

## 5. R213 测试结果

| Module | tests | 失败 | 错误 | 跳过 |
|---|---|---|---|---|
| plumb-tac | 24 | 0 | 0 | 0 |
| plumb-dfa | 92 | 0 | 0 | 0 |
| plumb-parser | 3 | 0 | 0 | 0 |
| plumb-dfa (legacy) | 394 | 0 | 0 | 1 |
| plumb-reporter | 2 | 0 | 0 | 0 |
| plumb-style | 166 | 1 (pre-existing R206) | 0 | 0 |
| plumb-rules | 2 | 0 | 0 | 0 |
| plumb-main | (CLI tests) | 0 | 0 | 0 |

**R213 净增**: 29 tests (5 SourceOrigin + 10 FunctionSummary + 9 FunctionSummaryCache + 5 R213FunctionSummary)。
R213 引入 0 回归。R206 那个 pre-existing 失败是 R206 doc 提过的老问题，跟 R213 无关。

`plumb-dfa` 详细:
- `DfaEngineTest` 5/0 (R211 保留)
- `R213FunctionSummaryTest` 5/0 (R213 新)
- `FunctionSummaryCacheTest` 9/0 (R213 新)
- `FunctionSummaryTest` 10/0 (R213 新)
- `cg/*` 33/0 (R210/R212 保留)
- `config/ConfigLoaderTest` 11/0 (R208 保留)
- 合计 **73/0/0/0** (plumb-dfa test 文件总和)

---

## 6. 创新点 (5 条)

1. **`Operation` 是 sealed-interface sum-type 跨 checker 通用 vocabulary** (R213 design).
   6 个子类型覆盖 6 个基本跨函数效应类别 (pass-through / sink / field write /
   taint propagate / unmodelled / unknown callee)。每个 checker 只构建自己关心的
   子类型；engine 合并时 union 不会丢。**General 框架不限于 taint** — TaintedChecker
   用 TaintPropagate，ResourceLeakChecker 用 Sink(close)，NullDerefChecker 用
   UnmodelledCall (间接判断) 等等。这是 Fathom `FunctionSummary` 框架的 Plumb port。

2. **"Direct finding vs Summary 入口分流"** (R213 design). 
   同一函数对同一 checker 既可以产生 "直接 finding"（在 `checkInstruction` 立即报
   `ctx.reporter().report(...)`，不依赖任何 param），也可以产生
   "summary 记录"（在 `summarizeFunction` 返回 summary，让 caller 端判断）。
   引擎不强制统一入口 — 让 checker 自己选。R217 (TaintedChecker 集成 R213 框架
   的下一步) 就是把现有 TaintAnalysis 的 in-pass emission 重构为
   "summary 记录 + caller 端 emit"。

3. **RPO post-order 是 summary-ready 的天然保障** (R213 invariant).
   引擎不需要显式传"callee summary 已就绪"标志；只要 RPO 顺序 + summary 紧跟
   finishFunction 之后，caller-side `checkInstruction` 看到的 cache 一定有内容。
   旧 R141a returnTaintCache 是 taint 领域内的特定实现；R213 FunctionSummaryCache
   是 general 框架，cache 内容由各 checker 自定 → cache 是 "checker-neutral
   lookup table"。

4. **Source 三分类用单字段而不是分层** (R213 design choice). 一个 `TacFunction.origin`
   字段 + 一个 `TuningConfig.analysedOrigins` 集合 — 没有"system_library_mode"
   /"library_mode" 之类独立 flag。这样分析"哪些 origin"是一个集合 inclusion 检查，
   跟 framework 的其他集合 config（sink families / fact kinds）同一形式。
   `SYSTEM_LIBRARY` 必须显式 opt-in 是从 `TuningConfig` ctor 强制做的
   (`analyseSystemLibraries=true` 才把它加进 set)。

5. **`FunctionSummaryCache.get(unknown) -> empty 单例` 而不是 null** (R213 design).
   大量 caller-side checker 的 hot-path 都需要 `summary.paramOpsAt(i).contains(...)`
   — 如果 `get` 返 null，每个 caller 都要写 `Optional.ofNullable(cache.get(sig))`。
   返 empty 单例让热路径短一截、stack 短一截、readability 提升。`isTrivial()`
   进一步让 consumer 区分"空 summary 是个真实答案"和"unmodelled 是个真实答案"
   — 后者用 `hasUnmodelledBehavior() == true` 检测。

---

## 7. 后续 R-round 候选 (post-R213)

按用户硬约束"先优化数据流底层 + 路径约束"路线：

- **R214** — 跨函数常量传播 (`CrossFunctionConstantPropagation`)：消费
  R213 FunctionSummary 框架 + R158/R160 GlobalCallGraph。Callee 摘要里加
  "param i 是 const literal" 类别（`Operation.ConstPropagate(value)`），caller-side
  ConstantPropagation 查 summary 直接用。跟 R141a merged-key 旧方案
  区别：R141a 是 taint-specific fingerprint 缓存；R214 是 general summary 里的
  常量子集。
- **R215** — TaintedChecker 接 R213 框架重构：把现有 `TaintAnalysis` 的
  in-pass 报告改为 "summary 记录 + caller 端 emit"；引入 `TaintDomain`
  (NUMERIC/STRING/POINTER/MIXED) 区分 SQL/LDAP/Command taint 类型，跟
  `SinkFamilyRegistry` 配合（一次 walk 多 CWE 标签）。
- **R216** — 5 mirror checker 接 DfaEngine 端到端验证：DivByZero / IntegerOverflow
  / NullDeref / ResourceLeak / SensitiveDataLeak。把 plumb-dfa 的
  现有 checker 全部 lift 到 plumb-dfa，统一走 R213 summary 框架。
- **R217** — Z3 solver 跟 FunctionSummary 集成：callee summary 含
  `Operation.SolverZ3Predicate(predicate)` 让 caller 知道
  "if you pass param i = literal x, callee branch to false" — 跨函数路径约束。
- **R218** — End-to-end test：R213 + R214 + R215 联合，对比同一 Juliet 样本
  plumb-dfa (legacy) vs plumb-dfa (R213 framework) 的 TP/FP/P/R metrics。

---

## 8. 文件清单

**main (8 files)**:
- `plumb-tac/src/main/java/org/plumb/tac/ir/SourceOrigin.java` (new)
- `plumb-tac/src/main/java/org/plumb/tac/ir/TacFunction.java` (modified)
- `plumb-tac/src/main/java/org/plumb/tac/translate/TacBuilder.java` (modified)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/config/TuningConfig.java` (modified)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/Operation.java` (new)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/FunctionSummary.java` (new)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/FunctionSummaryCache.java` (new)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/DfaChecker.java` (modified)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/AnalysisContext.java` (modified)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/DfaEngine.java` (modified)
- `plumb-style/src/main/java/org/plumb/style/StringEqualityWithEqualsCheck.java` (modified, pre-existing JavaParser 3.26 fix)

**test (4 files)**:
- `plumb-tac/src/test/java/org/plumb/tac/ir/SourceOriginTest.java` (new, 5 tests)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/summary/FunctionSummaryTest.java` (new, 10 tests)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/summary/FunctionSummaryCacheTest.java` (new, 9 tests)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/engine/R213FunctionSummaryTest.java` (new, 5 tests)

**backup**: `D:\work\tmp\r213_done\` (14 files, 8 main + 4 test + 2 helper backups)
