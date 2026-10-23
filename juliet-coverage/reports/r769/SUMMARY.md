# R769 — Inter-proc Range propagation investigation + infrastructure-only fix

**Status**: Investigation + minimal infrastructure fix shipped. **CWE-190 boundary precision 0.374 still < numeric 0.472** — R769a/b/c full fix deferred to R770+.

**Date**: 2026-10-09
**Scope**: CWE-190 (overflow) inter-proc Range propagation, nested-guard refutation. Architecture investigation + 2 surgical infrastructure patches.

---

## 1. Background

R764 L1094 identified inter-proc Range propagation as the natural follow-up to make CWE-190 boundary rule exceed numeric 0.472. R765 confirmed CWE-190 boundary at 0.374 vs numeric 0.472 — a 0.098 gap.

R769 was scoped as the **architecture investigation + 1 surgical fix** round: figure out the actual blocker for CWE-190 boundary precision, land a minimal infrastructure change that doesn't regress the 4 CWE, and document what's left.

---

## 2. Code changes — 2 surgical infrastructure fixes

### R769a — `guards: Map<TacVar, String>` → `Map<TacVar, List<String>>` (nested guard stack)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`

- Changed `private final Map<TacOperand.TacVar, String> guards` to `Map<TacOperand.TacVar, java.util.List<String>>`
- `emitGuard` now **appends** to the list (via `computeIfAbsent(...).add(desc)`) instead of overwriting via `put`
- `isReachable` iterates `guardList` and conjoins **every** guard in the list

**Why**: Pre-R769, when Juliet s67b wrote
```java
if (data > 0) {           // emitGuard(data, GT, 0) → guards[data] = "data > 0"
    if (data < 63) {      // emitGuard(data, LT, 63) → guards[data] = "data < 63"  (OVERWRITES first)
        data * 2;
    }
}
```
the second `emitGuard` call overwrote the first, so only `data < 63` reached the Z3 conjunction. The list-based fix preserves **both** guards for Z3 to conjoin.

### R769b — `emitGuard` operator symbol (LT/GT/LE/GE → `<`/`>`/`<=`/`>=`)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`

- `emitGuard` now converts `BinaryOperator` enum values to Z3 DSL symbols:
  - `LT` → `<`
  - `LE` → `<=`
  - `GT` → `>`
  - `GE` → `>=`
- Previously emitted `data GT 0` (Java enum `.toString()` form), which the `LocalSolverBridge` grammar does NOT recognise — it was treated as a free expression and the guard silently failed to constrain.

**Why**: Necessary precondition for R769a's nested guard stack to actually reach Z3 correctly. The `LocalSolverBridge.check` invocation now receives `(data > 0) && (data < 63)` instead of `(data GT 0) && (data LT 63)`.

### R769c — (REVERTED) `Range::join` → `Range::meet` for narrowing

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`

This **was attempted** but **reverted before shipping** because:

1. **Path-insensitive FN risk**: With `Range::meet`, if path A has `data = -1` (no overflow risk) and path B has `data = Byte.MAX_VALUE + 1` (overflow risk), `meet([-128, 127], [128, ?])` returns BOTTOM (disjoint), causing the engine to suppress all overflow reports on `data` — a hard FN regression.

2. **R759+ design rationale**: The `Range::join` policy (over-approximation) is documented in the Range class doc and is the design R759+ shipped deliberately. Changing it touches the **core lattice semantics** of every boundary subclass.

3. **R769 nested-guard test failed after meet**: `overflowNestedGuardedComparisonsSuppress` test wrote `if (data > 0) { if (data < 63) { data * 2 }}` and expected no fire. With `Range::meet` the test still failed because the **LocalSolverBridge treats free variables as conservatively-SAT** (per its own doc), so the Z3 conjunction `(data >= 1 && data <= 62) && (data > 0) && (data < 63) && ((data * 2) > 127 || ((data * 2)) < -128)` still resolved to SAT, because the bridge cannot conclude from the range clause alone that `data` cannot satisfy the sink predicate.

4. **Reverted** to preserve all R759-R768 4-CWE precision numbers. The narrowing-merge change is the **right thing** but needs to be paired with either (a) bridge changes that treat range-clause vars as bounded, or (b) path-sensitive control flow re-analysis (worklist re-iteration), both of which are larger than R769's scope.

### R769d — (REVERTED) `overflowNestedGuardedComparisonsSuppress` unit test

**File**: `plumb-dfa/src/test/java/org/plumb/dfa/boundary/BoundaryConstraintCheckerTest.java`

A new unit test was added to model the Juliet CWE-190 goodB2GSink pattern:
```java
if (data > 0) {
    if (data < 63) {
        byte result = (byte)(data * 2);   // safe: data ∈ [1, 62]
    }
}
```

After investigation, the test **was reverted** (replaced with a documentation comment) because the underlying framework has 3 distinct blockers for nested-guard refutation (see Section 3). The test would have permanently failed and would have caused CI to break. R770+ will re-introduce the test once the framework actually supports nested-guard refutation.

---

## 3. What R769 actually found — the 3 real blockers for nested-guard refutation

R769's investigation (via the unit test + Z3 conjunction debug logging + `LocalSolverBridge` smoke test) revealed that nested-guard refutation in the boundary framework has **three independent blockers**, not one:

### Blocker 1: `Range::join` policy (R759+ design choice)

`ranges.merge(lv, narrowed, Range::join)` widens on every guard hit instead of narrowing. This means two consecutive guards on the same variable yield a range that's the **union** of both guards' narrowing, not the intersection.

Example: `if (data > 0) { if (data < 63) { ... }}` produces
- After guard 1: `ranges[data] = [1, MAX/2]`
- After guard 2: `ranges[data] = join([1, MAX/2], [MIN/2, 62]) = [MIN/2, MAX/2]` (full type range, lost both narrowings)

**Why `Range::join`**: the framework is **path-insensitive** — every `RPO` instruction sees a single combined `ranges` map, and the join ensures that for any path through the function the range is over-approximated. The meet-equivalent would cause FN in functions with branching paths where the var takes different ranges on different paths.

**Fix options (R770+ candidates)**:
- R769c (reverted): switch to `Range::meet` for narrowing, accept FN risk
- R770 candidate: **path-sensitive re-analysis** — after first RPO pass, do a second pass that re-narrows ranges based on cross-BRANCH guard stacks (requires worklist re-iteration infrastructure, which is partially in place via `warmUpMethodSummaries`)
- R771 candidate: **call-site-aware ranges** — at call sites, seed callee params with caller-side range info (cross-function propagation)

### Blocker 2: `LocalSolverBridge` conservative-SAT on free variables

When the Z3 conjunction contains `data > 0` and `data` is not in `bindings`, the `LocalSolverBridge` returns **SAT** (the conservative answer) per its own documentation:
> "Variables referenced in the predicate but not bound are treated as **free**: the result is `Result#SAT` (the path might be reachable). The bridge is intentionally conservative — under-approximating satisfiability (saying 'UNSAT' when the path is actually reachable) would suppress true findings."

This is a **soundness / completeness trade-off** the bridge made for design reasons. The combination of "free var → SAT" + the fact that `Range::join` doesn't shrink the range to a single point means the bridge can never refute any predicate over a multi-point range.

Smoke test confirmation:
```
p = "((data * 2) > 127 || (data * 2) < -128)";        // only-pred: UNSAT
p = "(data > 0) && (data < 63) && ((data * 2) > 127 || (data * 2) < -128)";  // guards-only: SAT (free data)
p = "((data * 2) > 127)";   // with bindings={data:30}: UNSAT
p = "((data * 2) > 127)";   // with bindings={data:100}: SAT
```

**Fix options (R770+ candidates)**:
- R770 candidate: change the bridge to accept `Range` (not just `Long`/`Double`/`Boolean`/`String`) as a binding value, so the conjunction's range clauses become true constraints
- R770 candidate: pre-bind the range mid-points or check the conjunction structurally before passing to the bridge (Tier 0 already does this for `+`/`-` arithmetic, see `intervalRefutesSinkPredicate` / `atomicRefutes`)

### Blocker 3: `atomicRefutes` doesn't support `*` arithmetic

`intervalRefutesSinkPredicate` (Tier 0 fast-path) is supposed to refute sink predicates without Z3, by parsing them as `<ident> (+|-|* literal)? <cmp> <literal>` and checking against the var's range. The regex:
```java
Pattern.compile("[\\(\\s]*([A-Za-z_][A-Za-z_0-9]*)"
        + "(?:\\s*([+\\-])\\s*(-?\\d+))?"
        + ...
        + "\\s*(==|!=|<=|<|>=|>)\\s*(-?\\d+)$")
```
captures `+` and `-` for the arith-op but **not `*`**. So the canonical overflow predicate `((data * 2) > 127 || ((data * 2)) < -128)` falls through to Tier 1 (Z3), which then has the Blockers 1+2 problems.

**Fix options (R770+ candidates)**:
- R770 candidate: extend `atomicRefutes` to handle `*` arithmetic — for `data * k > C`, refute iff `range.hi * k < C` (or `range.lo * k > C` for `<`). Pure interval arithmetic, no Z3 needed.
- R770 candidate: extend `atomicRefutes` to handle `/` for divide-by-zero predicates

---

## 4. The combined conclusion

**R769 is shipped with 0 precision regression** because:
- The 2 infrastructure changes (R769a nested guard stack, R769b operator symbol) are pure format / list-of-guards changes that don't affect any code path that previously worked.
- The 2 attempted deeper changes (R769c `Range::meet`, R769d nested-guard test) were **reverted** before they could affect 4-CWE eval.

**CWE-190 boundary precision 0.374** is **unchanged** from R768. **IntOverflowChecker remains the only heuristic not yet @Deprecated** because the path to deprecate it requires the 3-blocker fix chain documented above.

**R770+ should NOT continue R769's "inter-proc Range propagation" agenda** in the same form. Instead, the three blockers should be tackled as **independent R-Round sub-projects**:
- R770a: extend `atomicRefutes` to support `*` and `/` arithmetic (Tier 0 fast-path)
- R770b: extend `LocalSolverBridge` to accept `Range` bindings
- R770c: re-introduce `Range::meet` for narrowing with path-sensitive re-analysis

Each is a focused, testable change. The full R769a/b/c "inter-proc Range propagation" ambition should be R771+, after the three blockers are individually resolved.

---

## 5. Verification

### Unit tests (focused)

| Test class | Tests | Pass |
|------------|------:|-----:|
| `BoundaryConstraintCheckerTest` | 13 | 13 |
| `RangeTest` | 15 | 15 |
| `R215MirrorCheckersTest` | 9 | 9 |
| `R222bIntUnderflowCheckerTest` | 8 | 8 |
| `R225PlusEmitGuardsTest` | 4 | 4 |
| `R247CrossCheckerGuardTest` | 2 | 2 |
| **Total** | **51** | **51** |

### Build

```
mvn -pl plumb-dfa -DskipTests clean compile   → BUILD SUCCESS
mvn -pl plumb-dfa,plumb-main -am -DskipTests install → BUILD SUCCESS
```

### E2E: CWE-190 re-scan after R769

| Rule | TP | FP | Total | Precision | (R768 baseline) |
|------|---:|---:|------:|----------:|-----------------:|
| `plumb.numeric.integer-overflow` | 3,723 | 4,166 | 7,889 | 0.472 | 0.472 ✓ |
| `plumb.boundary.integer-overflow` | 4,455 | 7,474 | 11,929 | 0.374 | 0.374 ✓ |

**0 regression** vs R768. The infrastructure changes (R769a, R769b) are behavior-preserving in the absence of the 3 deeper blockers.

---

## 6. Lessons learned

- **R769 L1105 (framework-already-shipped 不等于 framework-fully-capable)**: R765 claimed boundary framework was "fully shipped" (Range + Z3 + Guard + solver). R769 showed the framework ships **all the building blocks** but is missing 3 specific integration points (range-clause→Z3 binding, `*`/`/` in atomic refutation, narrowing on guard stack). Reading "framework shipped" too literally leads to overconfident R765 SUMMARY. **经验**: "shipped" should be qualified with "shipped for the cases R-Round tested" — boundary rules work for the patterns R759+ unit tests exercise, but the 3-blocker chain shows there are still narrow coverage gaps.
- **R769 L1106 (debug-driven discovery 比 design review 高效)**: R769 started with a "smart" `Range::meet` change based on lattice theory. It was wrong. The real bugs (op symbol, conservative-SAT, `*` regex gap) only surfaced after **adding debug print statements to a failing unit test and looking at the actual Z3 conjunction string**. Design review of the framework would not have surfaced them. **经验**: when investigating a "framework should do X" hypothesis, **write a minimal unit test that should pass under the hypothesis, run it, see what actually happens**. The Z3 conjunction string is the ground truth.
- **R769 L1107 (revert > ship-broken)**: R769c (`Range::meet`) and R769d (nested-guard test) were both reverted before E2E. The decision to revert was driven by E2E data preservation: **0 regression is a hard constraint**, and any change that would have changed the 4-CWE precision numbers was reverted even if "theoretically" correct. **经验**: when the scope of a change is unclear, prefer the smallest possible change that ships 0-regression. Larger ambition is better as a documented R770+ candidate than as a reverted-and-re-shipped attempt.
- **R769 L1108 (conservative-SAT 是 design choice, not a bug)**: The `LocalSolverBridge` returning SAT on unbound variables is intentional per its Javadoc. Calling it a "bug" would be wrong. It's a **design trade-off** that biases toward FP over FN. R769 could not fix this without changing the bridge contract. **经验**: when reading solver code, always check the Javadoc for "conservative" / "soundness" disclaimers before assuming the behavior is wrong.

---

## 7. Decisions

- **Infrastructure-only ship**: R769a (List<String>) + R769b (op symbol) ship as 0-regression, infrastructure-only changes that don't yet unlock new precision.
- **`Range::join` → `Range::meet` reverted**: Documented as R770c candidate with the path-insensitive FN risk analysis.
- **Nested-guard unit test reverted**: Will be re-introduced as part of R770a (atomic-refute `*` fix) — when the framework actually supports nested-guard refutation, the test will go back in and assert it.
- **CWE-190 boundary 0.374 still < numeric 0.472**: `IntOverflowChecker` remains not-yet-deprecated. The deprecation requires the 3-blocker fix chain, which is R770a/b/c scope.
- **No new ScanConfig flags**: No yaml changes. No new tests added. The unit test draft was reverted.

---

## 8. Cumulative deprecation chain (R765 + R767 + R768 + R769)

| Round | Rule | Class | Status | Boundary replacement | Reason |
|-------|------|-------|--------|---------------------:|--------|
| R765a | `plumb.numeric.integer-underflow` | `IntUnderflowChecker` | `@Deprecated` | `UnderflowConstraintChecker` (0.358) | forward-looking |
| R767a | `plumb.numeric.unchecked-array-index` | `ArrayIndexChecker` | `@Deprecated` | `ArrayBoundsConstraintChecker` (0.636) ⭐ | 2x precision gain |
| R768a | `plumb.numeric.divide-by-zero` | `DivByZeroChecker` | `@Deprecated` | `DivZeroConstraintChecker` (0.282) | numeric 0 findings |
| R769  | `plumb.numeric.integer-overflow` | `IntOverflowChecker` | **NOT deprecated** | `OverflowConstraintChecker` (0.374) | precision delta -0.098 |

The deprecation chain is **3 of 4 complete**. R769's 3-blocker analysis shows that R770+ can complete the chain only after the framework gains nested-guard refutation (which unlocks the path from CWE-190 boundary 0.374 to a number that beats numeric 0.472).

---

## Files

### Modified
- `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`:
  - `guards` field type changed: `Map<TacVar, String>` → `Map<TacVar, List<String>>` (R769a)
  - `emitGuard` appends to list and emits operator as Z3 DSL symbol (R769a + R769b)
  - `isReachable` iterates guard list and conjoins every entry (R769a)

### Reverted
- `Range::join` → `Range::meet` in 4 narrowing sites (R769c): reverted before E2E
- `overflowNestedGuardedComparisonsSuppress` unit test in `BoundaryConstraintCheckerTest.java` (R769d): reverted, replaced with documentation comment

### Logs
- `D:\tmp\plumb_test\logs\r769_baseline.log` (51/51 unit tests pass)
- `D:\tmp\plumb_test\logs\r769_install.log` (all 9 modules install OK)
- `D:\tmp\plumb_test\logs\r769_scan_cwe190.log` (6:30 E2E CWE-190 rescan, exit 0)
- `D:\tmp\plumb_test\logs\r769_eval.log` (4 CWE eval, 0 regression vs R768)
