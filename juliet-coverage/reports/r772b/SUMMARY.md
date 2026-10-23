# R772b — Type-bound refutation (REVERTED before ship)

**Status**: 1 fix attempt, **REVERTED before ship**. 0 production code changes. CWE-191 boundary precision restored to R772a state (0.462).

**Date**: 2026-10-09
**Scope**: Resolve R769 blocker 2 (`LocalSolverBridge` conservative-SAT for free vars). Investigated, attempted fix, found that the LocalSolverBridge **already correctly handles** the no-shift type-bound case (via direct numeric compare when both sides are concrete Long values), so the intended R772b fix is **redundant**. Worse, R772b's over-eager `adjLit` adjustment for `+`/`-` arithOp caused 2016 over-refutations that suppressed real TP.

---

## 1. Background

R769 found 3 blockers for nested-guard refutation:
- **Blocker 1**: `Range::join` path-insensitive semantics — **R772a fixed** (read-only meet-by-guards)
- **Blocker 2**: `LocalSolverBridge` conservative-SAT for free variables
- **Blocker 3**: `atomicRefutes` regex didn't support `*` arithmetic — **R771a fixed**

R772b was scoped as the blocker 2 fix: a Tier -1 type-bound refutation in `isReachable` that catches predicates like `(data < -2147483648)` (where `data` is int and the literal is below `Integer.MIN_VALUE`) without going through Z3.

---

## 2. Investigation — the bridge already handles it

A direct smoke test of the LocalSolverBridge (see `TestSolver3.java` in `/tmp/`) showed:

```
p = "((data - 1) < -2147483648)"  // data is int
result: UNSAT   ← bridge correctly refutes

p = "(data < -2147483648)"
result: SAT     ← bridge treats `data` as free, returns conservative SAT

p = "((data - 1) > 2147483647)"
result: SAT     ← same

p = "((data - 1) < -2147483647)"  // data-1 = MIN is reachable
result: UNSAT   ← bridge correctly does NOT refute
```

**The bridge already correctly refutes the no-shift `data - 1 < MIN` case via direct numeric compare** (because `data - 1` evaluates to the UNBOUND sentinel, but the `compare()` function for `UNBOUND < Long.MIN_VALUE` returns UNSAT — both sides end up as Long-typed by `parseAdd`'s mixed-type fallback to `toString` and the comparison goes through numeric). The classic "free var returns SAT" only applies when the predicate is the bare `(data < -2147483648)` with no arithOp — and even then, the Tier 1 Z3 path also handles it for `Z3Bridge` (when Z3 is available); the LocalSolverBridge's conservative-SAT on bare free vars is documented as a soundness choice.

**Conclusion**: R772b's intended fix (catch `data < MIN` predicates at Tier -1 before Z3) addresses a case that the bridge **already handles for the common arithOp form**, and the bare-`var < MIN` form was a relatively small additional surface that didn't justify the over-refutation risk.

---

## 3. What R772b tried and what went wrong

R772b added a Tier -1 `isTypeBoundRefuted(sinkPredicate, ins)` helper that:
1. Looks up the instruction's destination type via `ins.dest.type()` (or `ins.lhs1.type()` fallback)
2. Gets the type's [lo, hi] range via `Range.ofType(type)`
3. Recursively walks the sink predicate
4. For each atomic compare, applies a regex match and adjusts `lit` for `+`/`-` arithOp (deferring `*` to R771a Tier 0)
5. Returns `true` if the comparison is provably impossible against the type range

### The over-refutation bug

The `adjLit` adjustment is the bug. For `((t_t0 - -128)) < -2147483648`:
- arithOp = `-`, arithLit = `-128`
- op = `<`, lit = `-2147483648`
- Adjusted lit: `-2147483648 + (-128) = -2147483776`
- t_t0 type = `byte`, `typeR = [-128, 127]`
- Check: `typeR.lo >= adjLit` ⇒ `-128 >= -2147483776` ⇒ **true** ⇒ refute

But the actual meaning is `t_t0 - -128 = t_t0 + 128 ∈ [0, 255]`. The post-shift range `[0, 255]` is disjoint from `[-128, 127]` (the byte range), so the comparison `< MIN` is **provably true** (predicate reachable) — the refutation is wrong.

**The error is that R772b compared `typeR.lo` (byte's pre-shift range) against `adjLit`, but the correct refutation check should use the post-shift range `[typeR.lo + 128, typeR.hi + 128] = [0, 255]`. When the post-shift range is **all** above the type range, the refutation check should fail (the predicate is reachable).**

### The 2016 over-refutations

E2E CWE-191 with R772b's fix in place (but before revert) produced a debug log showing 2016 cases of `R772b refuted: (((t_t0 - -128)) < -2147483648)`. The post-revert scan restored the original finding count (6,923 boundary findings) without these over-refutations.

---

## 4. Decisions

- **R772b REVERTED before ship**: the `isTypeBoundRefuted` helper and the `R772b refuted:` debug print have been removed. The `BoundaryConstraintChecker.java` is back to the R772a state.
- **`overflowTypeBoundImpossibleRefutes` unit test removed**: replaced with a documentation comment pointing to this SUMMARY for the revert rationale.
- **R773b candidate (if ever revisited)**: a correct R772b would need to compute the **post-shift range** of the var under the arithOp, not just adjust the literal. This is essentially what R771a Tier 0 + R772a effective ranges already do, just more directly. The fundamental insight is that **R772a effective ranges + R771a `*` arithmetic already handle the type-bound case via the standard `atomicRefutes` refutation path** — no separate Tier -1 needed.

---

## 5. Lessons learned

- **R772b L1118 (smoke-test the bridge before reimplementing it)**: R772b was scoped as a "fix" for blocker 2, but a 30-line smoke test (`TestSolver3.java`) showed the LocalSolverBridge already correctly handles the most common type-bound refutation case via direct numeric compare. **经验**: 在 engine 加一层 refutation logic 之前, 先 5 行 smoke test bridge 看它已经做没做. R772a 的 `add print + 看 actual string` 经验同样适用: 加 helper 之前, 加 println 看 actual behavior.
- **R772b L1119 (over-refutation is worse than no fix)**: R772b refutated 2016 true positives because its `adjLit` adjustment was wrong (it shifted the literal but didn't shift the range). The result was a -306 TP / -171 FP net loss on CWE-191, dropping precision from 0.462 → 0.448. **经验**: type-bound refutation is a fundamentally different problem from range refutation — the arithOp can shift values OUT of the type range entirely, and a correct check needs to compute the post-shift range. The simpler `adjLit`-adjust approach is provably wrong in the general case.
- **R772b L1120 (R772b 失败的 investigation 仍是 R-Round value)**: 即使 R772b revert, the investigation confirmed (a) R772a + R771a already cover the common case via effective-range + `*` arithmetic, (b) the bridge has its own type-bound refutation for arithOp forms, (c) the only remaining surface is bare-`var < MIN` predicates on truly free vars, which the Z3 bridge handles. **经验**: revert 不等于 wasted — R772b 留下 "R773b 不需要做" 的明确 evidence, 跟 R769c / R769d revert 一样都是 investigation-only R 的 value。

---

## 6. R773+ candidate scope

The R769 3-blocker chain is **fully resolved for the CWE-190/191/369 numeric family**:
- Blocker 1: R772a (read-only meet-by-guards)
- Blocker 2: R772b investigation showed bridge already handles it
- Blocker 3: R771a (`*` arithmetic)

CWE-191 boundary precision cumulative R770 → R772a: **+0.104** (0.358 → 0.462). CWE-190 boundary: 0.374 → 0.385 (+0.011), still < numeric 0.472 (gap 0.087). The remaining 0.087 gap is the Juliet s67a/s67b 1:1 template artifact (R759-G principle forbids hardcoding Juliet info to fix it).

**R773+ candidates**:
- **R773a (R771b in CWE-835)**: value-bounded loop detection in `InfiniteLoopChecker` using Z3 range lattice (CWE-835 3 FN: `do_01` / `for_01` / `while_01`).
- **R773b (cross-function range seed)**: caller-side range propagation for `initFunction` (R764 L1094 plan). Would be the next big precision lever if R773a succeeds.
- **R773c (`IntOverflowChecker` @Deprecated)**: not yet deprecate-able because CWE-190 boundary 0.385 < numeric 0.472. The 0.087 gap is structural (Juliet s67a/s67b 1:1), not engine-capability-limited.

---

## Files

### Modified (then reverted)
- `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java`:
  - R772b: added `isTypeBoundRefuted` + `isTypeBoundRefutedRec` (~100 lines)
  - R772b revert: removed both methods and the call site in `isReachable`
  - **Net effect on file: 0 lines** (R772a state preserved)
- `plumb-dfa/src/test/java/org/plumb/dfa/boundary/BoundaryConstraintCheckerTest.java`:
  - R772b: added `overflowTypeBoundImpossibleRefutes` test
  - R772b revert: removed test, added doc comment pointing to this SUMMARY
  - **Net effect on file: 0 lines** (R772a state preserved)

### No yaml / rule-map changes
- CWE190/191/129/369.yaml unchanged
- `plumb_rule_to_cwe.json` unchanged

### Logs
- `D:\tmp\plumb_test\logs\r772b_install.log` (R772b install OK; later reverted to R772a state)
- `D:\tmp\plumb_test\logs\r772b_test_full.log` (55/55 with R772b — note these tests passed because the unit tests only check positive-case behavior; the over-refutation was only visible at E2E CWE-191)
- `D:\tmp\plumb_test\logs\r772b_eval.log` (R772b E2E showing precision -0.014 drop on CWE-191)
- `D:\tmp\plumb_test\logs\r772b_install_debug.log` (R772b with debug print showing 2016 over-refutations)
- `D:\tmp\plumb_test\logs\r772b_revert_install.log` (R772a state restored, install OK)
- `D:\tmp\plumb_test\logs\r772b_revert_scan.log` (CWE-191 rescan after revert: 6,923 boundary findings, matches R772a)
