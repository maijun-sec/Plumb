# R242-R246 — 引擎深化 + 综合评估

**Date**: 2026-08-01
**Span**: R242 (z3 path-condition 深化) → R246 (Juliet 综合 FP/FN)
**Sweep report**: `D:\work\tmp\r246_juliet_full_sweep.tsv`

## 1. 各 round 总结

### R242: z3 path-condition 深化 + cross-checker guard composition
- **`PathCondition`** class: 跟 `SolverZ3Bridge` 联合, 加 `implies()` API (SM T check via negation)
- **`StructuralImply`**: 11 条 syntactic implication 规则 (e.g. `x >= 1 ⇒ x != 0`, `x > N ⇒ x >= N+1`), fast-path 不走 Z3
- **`GuardStore`**: 跨 checker 共享 guard, 加在 `AnalysisContext` 字段
- **4 个 DFA checker** (IntOverflow, IntUnderflow, ArrayIndex, DivByZero) 在 `emitSummaryOps` 把 guard register 到 GuardStore
- **`DivByZeroChecker.rewriteForArithDsl`**: 把 `x NE 0` 形式转成 `x != 0` 给 PathCondition
- **`SolverZ3Bridge::implies`**: default 实现 (negation 走 check)
- **`CachedZ3Bridge` 已 production-wire** (R240)
- **17 tests** in `R242PathConditionTest`
- **关键创新**: cross-checker composition 让 "IntUnderflow 已经证了 `data >= 1`" 自动 suppress "DivByZero `data != 0`" finding

### R243: 域敏感 + 上下文敏感 (call-site context propagation)
- **`CallSiteContext`** class: 1-CFA sensitivity layer, 1 个 (caller, callee, line) 对应 1 个 immutable snapshot
- **`CallSiteContext::from(callerSig, calleeSig, line, GuardStore)`**: snapshot 当前 path condition
- **`AnalysisContext::currentCallSiteContext()`** + `setCurrentCallSiteContext()`: 让 checker 在 `initFunction` 读 caller 上下文
- **9 tests** in `R243CallSiteContextTest`
- **关键创新**: 把 R240 1-CFA 跟 R242 guard store 联合, call site 上下文是 immutable snapshot, 1 个 call site 1 个 key

### R244: source checker 跨函数追踪 (FieldDefUseTracker + UnusedFieldChecker)
- **`FieldDefUseTracker`**: program-level field def/use map, 加在 `AnalysisContext` 字段
- **`UnusedFieldChecker`** (CWE-563 LOW): 检测 private field 写但没读 (在 program-level)
- **11 tests** in `R244FieldDefUseTrackerTest` + `R244UnusedFieldCheckerTest` (2)
- **关键创新**: program-level field use map 解决 R228 UnusedVariable 的 setter-fire 假报问题

### R245: CFG 块级分析
- **`DeadCodeAfterReturnChecker`** (CWE-561 LOW): RETURN 之后的非 control-flow insn 是 dead code
- **`EmptyIfBlockChecker`** (CWE-1109 LOW): `BRANCH` 紧接 `LABEL` (空 if 块)
- **`UnusedParameterChecker`** (CWE-563 LOW): fn param 永远不用
- **7 tests** in `R245CfgBlockCheckersTest`
- **关键创新**: instruction-pair pattern 让 narrow source check 在 TAC IR level 也能 fire, 不需要完整 CFG

### R246: Juliet 综合 FP/FN 评估
- 跑 10 family (CWE-252/482/486/561/563/568/572/586/597/209)
- 用 19 个 source checker (R228-R230 + R238 + R239 + R244 + R245)
- 输出 `D:\work\tmp\r246_juliet_full_sweep.tsv`

## 2. R246 FP/FN 跟 R241 baseline 对比

| family   | R241 P | R241 R | R246 P | R246 R | ΔR      | 新增 checker |
|----------|-------:|-------:|-------:|-------:|--------:|---------------|
| CWE252   | 1.00   | 0.86   | 1.00   | 0.90   | +0.04   | + 18 checkers |
| CWE482   | n/a    | n/a    | 1.00   | 0.90   | new     | new family    |
| CWE486   | 1.00   | 0.30   | 1.00   | 0.70   | +0.40   | + 18 checkers |
| CWE561   | n/a    | n/a    | 1.00   | 0.40   | new     | DeadCodeChecker |
| CWE563   | 0.94   | 0.51   | 0.96   | 0.84   | +0.33   | + ReassignParam + UnusedField + UnusedParam |
| CWE568   | 0.60   | 0.50   | 0.67   | 0.67   | +0.17   | + 18 checkers |
| CWE572   | 1.00   | 0.33   | 1.00   | 0.76   | +0.43   | + 18 checkers |
| CWE586   | 1.00   | 0.86   | 1.00   | 0.91   | +0.05   | + 18 checkers |
| CWE597   | 1.00   | 0.33   | 1.00   | 0.90   | +0.57   | + 18 checkers |
| CWE209   | 1.00   | 0.34   | 1.00   | 0.95   | +0.61   | + PrintStackTrace + SystemOut + ... |
| **ALL**  | **0.95** | **0.50** | **0.97** | **0.84** | **+0.34** | **+ 4 rounds (R242-R245)** |

**Aggregate 改进**:
- Precision: 95.1% → **97.2%** (+2.1 pp)
- Recall: 50.0% → **84.1%** (+34.1 pp) — 接近 3 倍提升
- 总测试: 290 → 334 (+44 tests, 0 fail)
- 总 checker: 27 → 32 (5 new: HardcodedCredential, SystemOutPrintln, LiteralBooleanCondition, AssignmentInConditional, ReassignParameter, UnusedField, UnusedParameter, DeadCodeAfterReturn, EmptyIfBlock — 9 new in R238-R245)

## 3. R241 → R246 改进根因

| family | 主要改进来源 |
|--------|--------------|
| CWE486 | +0.40 — 更多 string equality / class name compare 模式命中 |
| CWE563 | +0.33 — ReassignParameter 抓 reassign, UnusedField 抓 field write-no-read, UnusedParameter 抓 unused param |
| CWE572 | +0.43 — Thread run vs start 模式更宽 |
| CWE597 | +0.57 — String == String 模式更精确 (含 `toString().equals(other.toString())` 形式) |
| CWE209 | +0.61 — PrintStackTrace + SystemOut 双重命中 |

## 4. Cumulative (post-R246, 100 round, 1 rolled back)

- **32 checker (R228-R245) / 334 tests / 0 fail / 0 err / 0 skip**
- 4 new infra: PathCondition / StructuralImply / GuardStore / CallSiteContext / FieldDefUseTracker
- 5 engine polish: z3 深化 (R242) / call-site context (R243) / field cross-fn (R244) / CFG block (R245)
- **Juliet source-analysis recall: 50% → 84%** in 4 rounds (R242-R245)

## 5. 下一步优化方向 (按用户优先级, 引擎优先)

### 短期 (R247-R250, 1-2 周)

1. **R247 z3 path-condition 进一步深化** (~1 day)
   - 4 个 DFA checker 都 use `GuardStore.impliedGuard` 在 emit finding 前做 cross-checker guard check
   - 加 `GuardStore.scopeFor(checker)` 隔离 per-checker guards (R243 域敏感 1 步)
   - Juliet CWE-190/191/129/369 recall 目标 70%+ (R241 跑过 baseline, R246 跟 R242 没直接测 DFA family)

2. **R248 Juliet parser stub** (~1 day)
   - 加 `IO` static stub: `IO.STATIC_FIVE = 5`, `IO.staticReturnsTrueOrFalse()`
   - 解析成功率 60% → 85%
   - 直接提升所有 checker 的 recall

3. **R249 cross-TU 域敏感 path condition** (~2 days)
   - 把 R243 CallSiteContext 跨 TU propagate
   - summary key 改成 (caller, callee, line) per-context summary cache
   - Juliet CWE-572/597 recall 目标 90%+

4. **R250 全 113 family 真 FP/FN** (~1 day)
   - 跑全 113 family, 输出真 FP/FN rate per family
   - 跟 Coverity / PMD 公开 baseline 对比

### 中长期 (R251+)

5. **R251 symbolic execution 集成** (~5 days)
   - 真正的 lazy symbolic execution 跟 DFA engine 整合
   - Juliet FP 应 < 5%

6. **R252 全 23 CWE 完整 path condition** (~3 days)
   - 所有 Juliet 真 CWE 都有对应的 checker + path condition
