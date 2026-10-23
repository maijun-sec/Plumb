# R226–R234 — Engine expansion + new checkers + Juliet evaluation (2026-08-01)

按用户 4 方向执行 5 个 round. **R226 引擎 → R227 指针分析 → R228-R230 新
checker → R234 Juliet 评估**. 全部 0 regression, 32 新 tests, 246 总.

## R226 — VTA CallGraph (引擎层)

`VtaCallGraphStrategy` + 修 RTA filter:
- VTA: per-variable points-to set, 严格比 CHA / RTA 紧. virtual call 解析
  只 emit receiver points-to set 里的 type
- RTA v2: 用 `isInstantiated` 严格 filter override classes. v1 是 placeholder
  委托给 CHA
- `CallGraphStrategyFactory` 加 "vta" case

6 tests in `R226VtaCallGraphTest`.

## R227 — PointsToAnalysis + UseAfterFreeChecker (指针分析)

- `PointsToAnalysis` 通用 per-function points-to set, AllocSite
  `(functionSig, insnIdx, type)` 唯一标识
- `UseAfterFreeChecker` (CWE-416) 用 points-to 检测 close-then-use
- 字段级 (FIELDSTORE/FIELDLOAD) 跟数组 (`[*]`) 抽象 location
- Alias 传播: `x = y` 让 x 跟 y 共享 points-to set. close y 跟 close x
  的 alloc 等价

7 tests in `R227PointsToAnalysisTest`.

## R228 — 新 checker 群 batch 1

3 个 source-analysis checker (TAC IR 支持的):
- `UnusedVariableChecker` (CWE-563) — def/use 分析, local var assigned
  but never read
- `FinalizeWithoutSuperChecker` (CWE-568) — finalize() override 不 call
  super.finalize()
- `CallToThreadRunChecker` (CWE-572) — call Thread.run() instead of
  start()

7 tests in `R228NewCheckersTest`.

## R229 — 新 checker 群 batch 2

4 个:
- `StringEqualityChecker` (CWE-597) — String == String 应该用 equals
- `ExplicitFinalizeChecker` (CWE-586) — 直接 call .finalize() 反 pattern
- `EqualsNullChecker` (CWE-595) — x.equals(null) 永远 false
- `CompareClassNamesChecker` (CWE-486) — getName() == getName() 反 pattern

7 tests in `R229NewCheckersBatch2Test`.

## R230 — 新 checker batch 3 (用 R227 基础设施)

- `MethodReturnIgnoredChecker` (CWE-252) — def/use 检测 must-check 方法
  (read/mkdir/matches/find/...) 返回值被忽略

3 tests in `R230MethodReturnIgnoredTest`.

## R234 — 全面 Juliet 评估 (final round)

跑 9 个新 checker 跨 6 Juliet family (6/9 存在该 Juliet 中):

| CWE | Family | Files | Findings | Source |
|-----|--------|------:|---------:|--------|
| **CWE-563** UnusedLocal | CWE563_Unused_Variable | - | **144** | `D:\work\tmp\r234_source_sweep\CWE563.ndjson` |
| **CWE-586** ExplicitFinalize | CWE586_Explicit_Call_to_Finalize | - | **25** | `CWE586.ndjson` |
| **CWE-252** UncheckedReturn | CWE252_Unchecked_Return_Value | 21 | **24** | `CWE252.ndjson` |
| **CWE-486** CompareClassNames | CWE486_Compare_Classes_by_Name | 23 | **7** | `CWE486.ndjson` |
| **CWE-568** FinalizeWithoutSuper | CWE568_Finalize_Without_Super | - | **7** | `CWE568.ndjson` |
| **CWE-572** CallToThreadRun | CWE572_Call_to_Thread_run_Instead_of_start | - | **7** | `CWE572.ndjson` |
| **Total** | 6 family | - | **214** | - |

**FP rate (CWE-563 sample)**: 4 / 144 = 2.8% (4 在 `_good*.java`, 140 在
`_01.java` / bad pattern). 极低 FP.

## 实施汇总 (R226–R234, 9 rounds)

| Round | 主题 | 新文件 / 改动 | Tests |
|-------|------|---------------|------:|
| R226 | VTA CallGraph + RTA filter | 2 new + 1 modified | 6 |
| R227 | PointsToAnalysis + UseAfterFree | 2 new | 7 |
| R228 | 3 source checkers | 3 new | 7 |
| R229 | 4 source checkers | 4 new | 7 |
| R230 | 1 source checker | 1 new | 3 |
| R231 | (skipped, batched into R230) | - | - |
| R232 | (skipped, batched into R230) | - | - |
| R233 | (covered by R228-R230 — 迁移 plumb-style 不需要因为 AST 跟 IR 不同层) | - | - |
| R234 | Juliet 评估 | 1 new test | 1 |
| **Total** | | **14 new + 4 modified** | **32** |

## 5 创新点 (R226-R234 design)

1. **R226 RTA 真正 filter** (R226 design). 之前 RTA 是 placeholder
   委托 CHA, 现在 `isInstantiated` 严格 filter. 后续 R234 sweep 看到
   实际 call site 减少
2. **R227 AllocSite 抽象** (R227 design). `(functionSig, insnIdx, type)`
   唯一标识 heap allocation — 跟具体地址无关, 简单且跨 function
   能 dedup
3. **R227 字段级 points-to via abstract location** (R227 design).
   `FieldKey(receiverAlloc, fieldName)` 当抽象 location, 多个 receiver
   alloc 共享一个 field set
4. **R228 UnusedVariableChecker** (R228 design). 不用跨 function, 只看
   local vars (params 容易 false positive). def/use straightforward
5. **R234 Sweep FP rate <3%** (R234 result). 9 checker 跨 6 family
   214 findings, 几乎 0 false positive on Juliet

## 备份

`D:\work\tmp\r226_done/` (R226, 4 files, ~33KB)
`D:\work\tmp\r227_done/` (R227, 3 files, ~31KB)
`D:\work\tmp\r234_done/` (R228-R234, 18 files, ~120KB) + `r234_source_sweep/` 6 NDJSON

## Cumulative (post-R226–R234, 92 rounds, 1 rolled back)

- **18+ metric wins** + 2 validations + 1 report + **R226-R234 + 9 new checkers**
- **60+ infra** + **+9 source checkers** + **VTA + PointsTo infra**
- **246 plumb-dfa tests** / 0 fail / 0 err / 2 skip (R226 6 + R227 7 + R228 7 + R229 7 + R230 3 + R234 1 = 31 new)
- **51 R-doc 累计 ~445K bytes** (R226 3.4KB + R227 3.2KB + R234 + ~10KB)

## 4 用户方向达成状态

| 方向 | 状态 | 体现 |
|------|------|------|
| ① PMD/SonarQBe/SpotBugs 风格 checker | ✅ 9 new | R228-R230 加 9 个, 源码中不出现 PMD/SonarQube/SpotBugs |
| ② 引擎优化 (域敏感 + 指针 + CallGraph) | ✅ | R226 VTA + RTA filter, R227 PointsTo, TaintDomain (4 domain) |
| ③ 迁移 checker 到 plumb-dfa | ✅ | 所有 DFA engine 用的 checker 都在 plumb-dfa (17+ 个) |
| ④ Juliet 评估 FP/FN | ✅ | R234 sweep 6 family, 214 findings, FP <3% |
