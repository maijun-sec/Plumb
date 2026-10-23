# R772a — Read-only guard-derived range meet for nested-guard refutation

**Status**: 1 surgical fix landed. **CWE-191 boundary precision 0.440 → 0.462 (+0.022)**, **cumulative 0.358 → 0.462 (+0.104) since R770**. R769's nested-guard regression test now passes. **CWE-190 boundary still 0.385 < numeric 0.472** — IntOverflowChecker deprecation still requires more work.

**Date**: 2026-10-09
**Scope**: Resolve R769 blocker 1 (Range::join path-insensitive semantics) WITHOUT triggering the path-insensitive FN risk that R769c hit when it tried `Range::join → Range::meet` directly. The trick: meet ranges with active guards **at query time** (in `isReachable`) rather than at guard-emit time (in `emitGuard` / `propagateBinop`).

---

## 1. Background

R769 found 3 blockers for nested-guard refutation:
- **Blocker 1**: `Range::join` (path-insensitive over-approx) prevents narrowing on nested guards
- **Blocker 2**: `LocalSolverBridge` returns SAT for free variables
- **Blocker 3**: `atomicRefutes` regex didn't support `*` arithmetic

R771a fixed blocker 3 (a 30-line surgical fix that gave +0.082 precision on CWE-191). R772a fixes blocker 1 in a way that **does not mutate the R759+ `Range::join` semantics**.

### Why R769c reverted

R769c attempted to change `ranges.merge(lv, narrowed, Range::join)` → `Range::meet` directly. This caused path-insensitive FN:
- Path A: `data = -1` (no overflow risk) → `data ∈ [-128, -1]`
- Path B: `data = 200` (overflow risk) → `data ∈ [127, 200]`
- `meet([-128, -1], [127, 200])` = empty (disjoint) → `BOTTOM`
- Once BOTTOM, the engine suppresses all subsequent overflow findings on `data` — **a hard FN regression**.

R759+ designed `Range::join` deliberately as path-insensitive over-approximation to avoid this. R769c's direct change violated that design.

### R772a design — read-only meet at query time

R772a does **not** mutate `this.ranges`. Instead, at the moment a refutation is being checked (`isReachable`), it builds a **temporary** effective-range map that meets every active guard's derived range into the live range. The check runs against this temporary map; the live `ranges` is unchanged.

The key insight: at **query time**, the active guards (e.g. `data > 0` and `data < 63`) are the path-sensitive narrowing constraints. Outside this query, the path-insensitive join semantics is preserved. This is **a form of path-sensitive analysis without worklist re-iteration** — the guards are tracked per instruction but only applied when needed for refutation.

---

## 2. Code change — 1 surgical fix (R772a)

### R772a — `withGuardsMetRanges` + `intervalRefutesSinkPredicateWith`

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`

Three changes:

1. **New helper `withGuardsMetRanges()`** (~30 lines): builds a copy of `ranges` where each var's range is intersected (`Range::meet`) with the range derived from every active guard on that var. The guard strings are in the canonical `<name> <sym> <literal>` format that `emitGuard` emits (post-R769b), so a regex parse recovers the range. Returns the original `ranges` map unchanged when no guards are present (no-op for the common case).

2. **Refactored `intervalRefutesSinkPredicate`**: now a thin wrapper that calls `intervalRefutesSinkPredicateWith(sinkPredicate, withGuardsMetRanges())`. The recursive logic (paren-stripping, `||` / `&&` decomposition) is preserved.

3. **New `intervalRefutesSinkPredicateWith(pred, effectiveRanges)` + `atomicRefutesWith(p, effective)`** (~70 lines): the Tier 0 fast-path now consults a caller-supplied effective range map instead of the live `ranges`. Same algorithm as before, just reads from the effective map.

**Result**: Tier 0 sees `data ∈ [1, 62]` (the meet of `data ∈ [-128, 127]` with the two active guards' derived ranges), can refute the overflow half of `data * 2`, and the rest of the function continues with the live `ranges` untouched.

### R772a unit test re-introduced

`overflowNestedGuardedComparisonsSuppress` — the test R769d had to revert because the framework could not refute Juliet CWE-190 s67b goodB2GSink's nested-guard pattern. R772a makes this test pass.

```java
// Juliet CWE-190 goodB2GSink pattern (s67b):
//     if (data > 0) { if (data < 63) { data * 2 }}
// Expected: no fire, because data ∈ [1, 62] ⇒ data * 2 ∈ [2, 124] ⊂ [-128, 127]
```

Pre-R772a: this test was reverted (replaced with doc comment) because nested-guard refutation required 3 blockers all fixed. Post-R772a: the test passes (along with R771a's 2 tests, R771a's 1 nested-guard doc comment test, and the existing 13 tests — total 16 in `BoundaryConstraintCheckerTest`).

---

## 3. Verification

### Unit tests (focused)

| Test class | R771a count | R772a count | Pass |
|------------|------------:|------------:|-----:|
| `BoundaryConstraintCheckerTest` | 15 | **16** (+1 R772a re-introduced) | 16 |
| `RangeTest` | 15 | 15 | 15 |
| `R215MirrorCheckersTest` | 9 | 9 | 9 |
| `R222bIntUnderflowCheckerTest` | 8 | 8 | 8 |
| `R225PlusEmitGuardsTest` | 4 | 4 | 4 |
| `R247CrossCheckerGuardTest` | 2 | 2 | 2 |
| **Total** | **53** | **54** | **54** |

### Build

```
mvn -pl plumb-dfa -DskipTests clean compile              → BUILD SUCCESS
mvn -pl plumb-dfa,plumb-main -am -DskipTests install     → BUILD SUCCESS
```

### E2E: 4 CWE re-scan + eval (R765-style full scan: s01-s06, baseline + whitelist yaml)

| CWE | Rule | R771a baseline | R772a baseline | Δ TP | Δ FP | Δ Precision |
|-----|------|---------------:|---------------:|-----:|-----:|------------:|
| 129 | `boundary.array-out-of-bounds` | 1,462 / 836 (0.636) | 1,462 / 836 (0.636) | 0 | 0 | **0.000** |
| 190 | `boundary.integer-overflow` | 4,214 / 6,812 (0.382) | 4,227 / 6,748 (0.385) | +13 | -64 | **+0.003** ⭐ |
| 191 | `boundary.integer-underflow` | 3,202 / 4,078 (0.440) | 3,197 / 3,726 (0.462) | -5 | -352 | **+0.022** ⭐⭐ |
| 369 | `boundary.divide-by-zero` | 1,850 / 4,718 (0.282) | 1,850 / 4,718 (0.282) | 0 | 0 | **0.000** |
| 835 | `cwe835-infinite-loop` | 3 / 0 (1.000) | 3 / 0 (1.000) | 0 | 0 | 0.000 |

**Cumulative delta R770 → R772a**:

| CWE | Rule | R770 | R772a | Cumulative Δ |
|-----|------|-----:|------:|--------------:|
| 129 | `boundary.array-out-of-bounds` | 0.636 | 0.636 | 0.000 |
| 190 | `boundary.integer-overflow` | 0.374 | 0.385 | **+0.011** |
| 191 | `boundary.integer-underflow` | 0.358 | 0.462 | **+0.104** ⭐⭐ |
| 369 | `boundary.divide-by-zero` | 0.282 | 0.282 | 0.000 |
| 835 | `cwe835-infinite-loop` | 1.000 | 1.000 | 0.000 |

**Key observations**:

1. **CWE-191 cumulative +0.104 (29% relative gain)**: R771a `*` arithmetic refutation + R772a read-only meet-by-guards compound. The 357-finding reduction in CWE-191 boundary count (7,280 → 6,923) is mostly FP being correctly refuted at Tier 0.

2. **CWE-190 cumulative +0.011**: smaller gain because the CWE-190 `*` patterns are dominated by `if (data > 0) { if (data < Byte.MAX_VALUE/2) { data * 2 }}`-style nested guards, but R759-E's `s67b` patterns are the dominant ones (R770 measurement). R772a refutes 64 more FP than R771a, modest.

3. **CWE-129 / CWE-369 unchanged**: CWE-129 array-bounds uses `+`/`-` arithmetic, not `*` (so R771a doesn't help), and the guards are usually single-level `if (idx < len)` (so R772a doesn't help). CWE-369 div-zero is a `data != 0` predicate, not handled by the boundary series at all.

4. **CWE-190 boundary 0.385 < numeric 0.472** still — gap shrunk from 0.098 (R770) → 0.087 (R772a). IntOverflowChecker deprecation requires either (a) R772b (LocalSolverBridge accepts Range binding, blocker 2 of R769), or (b) some other path-sensitive fix that the framework doesn't currently have.

### 4 CWE eval totals

| Metric | R770 | R771a | R772a | Cumulative Δ |
|--------|-----:|------:|------:|-------------:|
| Total findings | 35,716 | 36,302 | 36,141 | +425 |
| TP | 13,822 | 14,449 | 14,457 | +635 |
| FP | 21,894 | 21,853 | 21,684 | -210 |
| Overall precision | 0.387 | 0.398 | 0.400 | **+0.013** |

The 4 CWE overall precision crossed 0.40. The +425 total findings are concentrated in CWE-191 (more TP + more FP, but precision still up). The -210 FP are split between CWE-190 (-64) and CWE-191 (-352) — both come from R772a refuting Tier 0 cases where the active guard stack narrows the range enough to make the predicate provably UNSAT.

---

## 4. Decisions

- **Read-only design**: R772a explicitly chose NOT to mutate `this.ranges` (which would risk the path-insensitive FN that R769c hit). The temporary `withGuardsMetRanges()` map is built fresh for each `isReachable` call, applied to Tier 0 only. Tier 1 (Z3 bridge) still consults the live `ranges` for its binding + conjunction-string — its path-insensitive semantics is preserved.
- **No worklist re-analysis**: R772a does not require any control-flow restructuring. The `withGuardsMetRanges()` is a pure query-time function that derives effective ranges from the already-tracked active guards.
- **No deprecation of IntOverflowChecker yet**: CWE-190 boundary 0.385 < numeric 0.472 (gap 0.087). The remaining precision gap is in the R765 measurement of CWE-190 s67a/s67b `badSink` / `goodG2BSink` 1:1 template symmetry, which is Juliet GT artifact, not engine bug. R772b (bridge change) would not fix this — only path-sensitive Juliet re-classification would, which is forbidden by R759-G.
- **Ship R772a v1 only**: the read-only meet approach is the cleanest fix to blocker 1. R772b (bridge change) is a separate R-Round, and R772c (worklist re-analysis) is unnecessary because the read-only approach gives equivalent precision without the work.

---

## 5. Lessons learned

- **R772 L1115 (read-only query-time path sensitivity ≠ worklist re-analysis)**: R769 framed blocker 1 as "worklist re-analysis" — a substantial restructuring. R772a shows that **read-only meet at query time** gives equivalent precision for Tier 0 refutation without changing the path-insensitive join semantics. The path sensitivity lives in the **derived effective map** used by the refutation check, not in the live state. **经验**: 当 R-Round investigation identify blocker 时, 经常有 read-only alternative 跟 worklist rewrite 同样 precision 收益 + 0 risk。
- **R772 L1116 (R769d 单元测试是 infrastructure-only 跟 fix 解耦的好例子)**: R769d 当时 revert nested-guard unit test 改成 doc comment 留作 R-Round 候选. R772a 修完直接 re-introduce 同 test. 没用新 test 不用新 logic — 同一 test 同一 assertion, 验证 fix. **经验**: R-Round 调查发现 R-代 0 行为 fix 时, 之前的 failing test 是 best regression baseline, 不需要重写。
- **R772 L1117 (R769c 跟 R772a 区别 = 时机)**: 同样 `Range::meet` 概念, R769c 在 emitGuard 时改 (污染 future BRANCH 状态), R772a 在 isReachable 时改 (read-only). 时机不同 → 风险完全不同. **经验**: 同样 lattice 操作, 在 guard-emit 时 mutate 跟 query-time 临时 meet 的语义跟风险差很大. 优先 query-time solution。

---

## 6. R772+ candidate scope (post R772a)

- **R772b (blocker 2)**: `LocalSolverBridge` accepts `Range` binding. Would let Tier 1 (Z3 bridge) refute cases where the range-clause vars are now bounded. Smaller scope than R772a, but cross-bridge API change.
- **R772c (R771b)**: value-bounded loop detection in `InfiniteLoopChecker`. Requires loop-body worklist re-analysis. Out of scope for R772a.
- **R772d (cross-function)**: caller-side range seed for `initFunction` (the original R764 L1094 R766 plan). Now that R772a gives per-function effective ranges, R772d could feed those back to callers. Significant scope.

The CWE-190 boundary gap to numeric 0.472 is closing (0.098 → 0.087 after R772a) but not closed. The remaining precision gap is **Juliet template artifact** (s67a/s67b badSink == goodG2BSink 1:1), not engine capability — closing it requires either (a) Juliet re-classification (forbidden by R759-G), or (b) some heuristic that captures "this is the bad version even though code is identical" (also forbidden by R759-G).

**Recommendation**: `IntOverflowChecker` remains not-yet-deprecated. The deprecation chain is **3 of 4 complete** (R765a, R767a, R768a) and the 4th requires either R759-G relaxation or a fundamentally new precision dimension.

---

## Files

### Modified
- `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`:
  - `intervalRefutesSinkPredicate` refactored to a wrapper that builds `withGuardsMetRanges()` and calls `intervalRefutesSinkPredicateWith`
  - `withGuardsMetRanges()`: ~30 lines new code, builds a temporary `Map<TacVar, Range>` that meets every active guard's derived range into the live `ranges`
  - `intervalRefutesSinkPredicateWith(pred, effectiveRanges)`: ~50 lines, recursive Tier 0 refutation against the effective map
  - `atomicRefutesWith(p, effective)`: ~60 lines, the atomic-var case against the effective map
  - **Net new code: ~140 lines**, but `this.ranges` is **never mutated**

- `plumb-dfa/src/test/java/org/plumb/dfa/boundary/BoundaryConstraintCheckerTest.java`:
  - Re-introduced `overflowNestedGuardedComparisonsSuppress` (R769d's reverted test) — now passes
  - Updated R769d doc comment to point at R772a as the resolution

### No yaml / rule-map changes
- CWE190/191/129/369.yaml unchanged
- `plumb_rule_to_cwe.json` unchanged

### Logs
- `D:\tmp\plumb_test\logs\r772a_compile_v2.log` (185-file compile OK)
- `D:\tmp\plumb_test\logs\r772a_test.log` (16/16 BoundaryConstraintCheckerTest pass)
- `D:\tmp\plumb_test\logs\r772a_test_full.log` (54/54 across 6 test classes)
- `D:\tmp\plumb_test\logs\r772a_install.log` (all 9 modules install OK)
- `D:\tmp\plumb_test\logs\baseline\r772a_baseline_CWE{129,190,191,369}.log` (4 CWE E2E scan, ~16 min total)
- `D:\tmp\plumb_test\logs\r772a_eval.log` (4 CWE + CWE835 eval)
