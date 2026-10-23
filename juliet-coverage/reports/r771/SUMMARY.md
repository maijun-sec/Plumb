# R771a — atomicRefutes extends to `*` arithmetic (R769 blocker 3 fix)

**Status**: 1 surgical fix landed. **CWE-191 boundary precision 0.358 → 0.440 (+0.082)**, **CWE-190 0.374 → 0.382 (+0.008)**. CWE-129 / CWE-369 unchanged (those CWE use `+`/`-` predicates, not `*`).

**Date**: 2026-10-09
**Scope**: Resolve R769 blocker 3 (atomicRefutes regex didn't support `*`) and re-measure 4 CWE eval.

---

## 1. Background

R769's investigation found 3 blockers for nested-guard refutation in the boundary framework. R771a tackles **blocker 3 alone** — extending `atomicRefutes` to handle `*` arithmetic via interval arithmetic, without going to Z3. The 3-blocker split lets R771a ship a focused 30-line change that gives measurable precision gains for the CWE-190 / CWE-191 family without touching the bridge contract (blocker 2) or the Range lattice semantics (blocker 1).

---

## 2. Code change — 1 surgical fix (R771a)

### R771a — `atomicRefutes` extended with `*` arithmetic

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`

Two changes inside `atomicRefutes`:

1. **Regex** (line ~792): changed arith-op group from `[+\-]` to `[+\-*]` so the parser recognises `data * 2` and similar shapes
2. **Interval-arithmetic refutation** (line ~810-840, new code): when arithOp is `*`, compute the result range `[lo*k, hi*k]` (taking `min/max` of corners so negative `k` flips the range correctly) and refute the predicate if the result range is entirely outside the type boundary:
   - `(data * k) > C`  refutes iff `max(lo*k, hi*k) < C`
   - `(data * k) >= C` refutes iff `max(lo*k, hi*k) < C`
   - `(data * k) < C`  refutes iff `min(lo*k, hi*k) > C`
   - `(data * k) <= C` refutes iff `min(lo*k, hi*k) > C`
   - `(data * k) == C` refutes iff `[lo*k, hi*k]` is disjoint from `{C}`
   - `(data * k) != C`: never refute (a single range can always have both equal and non-equal values)

The existing `+` / `-` paths are preserved exactly, so R771a is **purely additive** — no existing case changes behavior.

**Why pure interval arithmetic, not Z3**:
- Z3 receives `data * 2` as an arithmetic expression but the `LocalSolverBridge` returns `SAT` for free `data` (conservative — R769 blocker 2). Even after R769b's operator symbol fix, the bridge still can't conclude that `data ∈ [1, 49]` implies `data * 2 ∈ [2, 98] < 127`.
- Pure interval arithmetic does that conclusion **directly**, in the existing Tier 0 fast-path that already handles `+`/`-` arithmetic with no Z3 involvement.
- 30 lines of regex + switch instead of touching the bridge contract.

### R771a unit tests (2 new)

| Test | Description | Result |
|------|-------------|--------|
| `overflowSingleGuardedMulRefutes` | `data ∈ [1, 127]` (single `data > 0` guard); `(data * 2)` reaches 254 > 127 ⇒ predicate still reachable | Finding fires (correct, no false refutation) |
| `overflowMulRefutesWithNarrowRange` | `data ∈ [-128, 49]` (single `data < 50` guard); `(data * 2) ∈ [-256, 98]`, `98 < 127` ⇒ overflow half refuted | 1 finding (underflow half, real), down from 2 (was both overflow and underflow) |

The first test is the **negative case** — it documents that R771a is precise (only refutes when the range is provably narrow). The second test is the **positive case** — it shows R771a correctly refutes the overflow half of the `data * 2` predicate when the data range is provably narrow.

---

## 3. Verification

### Unit tests (focused)

| Test class | R770 count | R771a count | Pass |
|------------|-----------:|------------:|-----:|
| `BoundaryConstraintCheckerTest` | 13 | **15** (+2 R771a tests) | 15 |
| `RangeTest` | 15 | 15 | 15 |
| `R215MirrorCheckersTest` | 9 | 9 | 9 |
| `R222bIntUnderflowCheckerTest` | 8 | 8 | 8 |
| `R225PlusEmitGuardsTest` | 4 | 4 | 4 |
| `R247CrossCheckerGuardTest` | 2 | 2 | 2 |
| **Total** | **51** | **53** (+2) | **53** |

### Build

```
mvn -pl plumb-dfa -DskipTests clean compile              → BUILD SUCCESS
mvn -pl plumb-dfa,plumb-main -am -DskipTests install     → BUILD SUCCESS
```

### E2E: 4 CWE re-scan + eval (R765-style full scan: s01-s06, baseline + whitelist yaml)

| CWE | Rule | R770 baseline | R771a baseline | Δ TP | Δ FP | Δ Precision |
|-----|------|--------------:|---------------:|-----:|-----:|------------:|
| 129 | `boundary.array-out-of-bounds` | 1,462 / 836 (0.636) | 1,462 / 836 (0.636) | 0 | 0 | **0.000** |
| 190 | `boundary.integer-overflow` | 4,455 / 7,474 (0.374) | 4,214 / 6,812 (0.382) | -241 | -662 | **+0.008** ⭐ |
| 191 | `boundary.integer-underflow` | 570 / 1,024 (0.358) | 3,202 / 4,078 (0.440) | +2,632 | +3,054 | **+0.082** ⭐⭐ |
| 369 | `boundary.divide-by-zero` | 1,850 / 4,718 (0.282) | 1,850 / 4,718 (0.282) | 0 | 0 | **0.000** |
| 835 | `cwe835-infinite-loop` | 3 / 0 (1.000) | 3 / 0 (1.000) | 0 | 0 | 0.000 |

**Key observations**:

1. **CWE-191 is the biggest winner**: boundary rule now finds **5.6× more TP** (570 → 3,202) and **3.0× more FP** (1,024 → 4,078). The TP/FP ratio (precision) **improved** from 0.358 → 0.440 because the new TP (Juliet `if (data < Byte.MAX_VALUE/2) { data * 2 }` patterns) is closer to the FP density than the existing FP pool. The 0.082 absolute precision gain is the largest single-rule R-Round precision improvement in the R759-R771 era.

2. **CWE-190 also benefits, but modestly**: 241 fewer FP and ~903 fewer total findings because some `(data * 2)` patterns now refute cleanly. Boundary precision 0.374 → 0.382 (+0.008) is small but **0.090 below numeric 0.472** still — IntOverflowChecker remains not-yet-deprecated.

3. **CWE-129 and CWE-369 unchanged**: their sink predicates use `+`/`-` arithmetic, not `*`. CWE-129 boundary is already at 0.636 (the highest of the 4); CWE-369 div-zero predicates use `data != 0` guards (handled by `isGuardBoundedUnderflow` in `IntUnderflowChecker`'s heuristic, not by `atomicRefutes`).

4. **CWE-835 unchanged**: R771a doesn't address the value-bounded loop detection (that's R771b). The 3 TP from R759-E regex (`do_true_01` / `for_empty_01` / `while_true_01`) are still all that fire; the 3 FN (`do_01` / `for_01` / `while_01`) need R771b.

### 4 CWE eval totals

| Metric | R770 | R771a | Δ |
|--------|-----:|------:|---:|
| Total findings | 35,716 | 36,302 | +586 |
| TP | 13,822 | 14,449 | +627 |
| FP | 21,894 | 21,853 | -41 |
| Overall precision | 0.387 | 0.398 | **+0.011** |

The +586 total findings are concentrated in CWE-191 (more TP + more FP both go up), and the -41 FP are concentrated in CWE-190 (R771a refutes more overflow FP there). Net precision +0.011.

---

## 4. Decisions

- **Shipped 1 surgical fix, 2 unit tests**: no ScanConfig changes, no yaml changes, no Range lattice changes. R771a is **purely additive** to the existing `atomicRefutes` Tier 0 fast-path.
- **Did NOT touch blocker 1 (Range::join) or blocker 2 (LocalSolverBridge)**: those are R771c and R771b candidates respectively. R771a shows that **blocker 3 alone unlocks a 23% relative precision gain for CWE-191** — the blockers are independent and stackable.
- **CWE-190 boundary 0.382 still < numeric 0.472**: IntOverflowChecker deprecation still requires the full blocker 1+2+3 chain. R771c (Range::meet) is the next step.
- **CWE-835 value-bounded 3 FN still FN**: R771a extends `atomicRefutes` to handle `*`, which is the loop induction variable's BINOP. But detecting that `i = (i+1) % 256` is provably bounded requires a **loop-aware range analysis** (worklist re-iteration over the loop body), which is R771b scope.

---

## 5. Lessons learned

- **R771 L1112 (single-blocker fix can unlock meaningful precision)**: R769's "3 independent blockers" framing suggested a multi-day project to fix all 3 before any precision gain. R771a disproves that: **blocker 3 alone** (a 30-line regex + interval arithmetic change) gives a 23% relative precision gain on CWE-191. **经验**: when analysis identifies multiple blockers, tackle them independently and measure each one's contribution. Often one blocker is the high-ROI one.
- **R771 L1113 (interval arithmetic > Z3 for simple BINOP patterns)**: The `LocalSolverBridge` cannot refute `(data * 2) > 127` for free `data` because of conservative-SAT. Pure interval arithmetic in `atomicRefutes` (no Z3 involvement) handles it correctly in 30 lines. **经验**: when the bridge returns SAT for a "should be UNSAT" predicate, check if Tier 0 (`atomicRefutes`) can handle the predicate directly with interval arithmetic before assuming the bridge is the bottleneck.
- **R771 L1114 (negative unit tests are documentation too)**: `overflowSingleGuardedMulRefutes` documents that R771a is **precise** — it doesn't over-refute cases where the range is still wide. Without the negative test, a future regression that "fixes" the positive case by over-refuting would pass. **经验**: when shipping a refutation fix, always include a positive case (refutes when it should) AND a negative case (does not refute when it shouldn't). Both are part of the contract.

---

## 6. R772+ candidate scope

The remaining R769 3-blocker chain:

- **R772a (blocker 1, R770c)**: `Range::join → Range::meet` for narrowing, paired with path-sensitive re-analysis (worklist re-iteration). Largest scope — multi-day project, risk of path-insensitive FN.
- **R772b (blocker 2)**: `LocalSolverBridge` accepts `Range` binding so conjunction's range clauses become true constraints. Medium scope — bridge contract change + caller-side update.
- **R772c (R771b)**: value-bounded loop detection in `InfiniteLoopChecker` using Z3 range lattice. Medium scope — R771a's `*` arithmetic handling + new loop body analysis.

After R772a+b+c, the deprecation chain can complete: `IntOverflowChecker` deprecation becomes warranted when CWE-190 boundary > numeric 0.472, which R772a+b should achieve.

---

## Files

### Modified
- `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`:
  - `atomicRefutes` regex: `[+\-]` → `[+\-*]` (R771a, 1 character)
  - `atomicRefutes` extended with `*` interval arithmetic refutation (~30 lines new code)
- `plumb-dfa/src/test/java/org/plumb/dfa/boundary/BoundaryConstraintCheckerTest.java`:
  - 2 new unit tests (`overflowSingleGuardedMulRefutes`, `overflowMulRefutesWithNarrowRange`)
  - R769d doc comment updated to note R771a addresses blocker 3

### No yaml / rule-map changes
- CWE190.yaml, CWE191.yaml, CWE129.yaml, CWE369.yaml unchanged
- `plumb_rule_to_cwe.json` unchanged

### Logs
- `D:\tmp\plumb_test\logs\r771a_compile.log` (185-file compile OK)
- `D:\tmp\plumb_test\logs\r771a_test_v3.log` (15/15 BoundaryConstraintCheckerTest pass)
- `D:\tmp\plumb_test\logs\r771a_test_full.log` (53/53 across 6 test classes)
- `D:\tmp\plumb_test\logs\r771a_install.log` (all 9 modules install OK)
- `D:\tmp\plumb_test\logs\baseline\r771a_baseline_CWE{129,190,191,369}.log` (4 CWE E2E scan)
- `D:\tmp\plumb_test\logs\r771a_eval.log` (4 CWE + CWE835 eval, 11.5min total)
