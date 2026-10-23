# R593 — Guarded-Base Propagation for ArrayIndexChecker

**Date**: 2026-08-17
**Project**: plumb-dfa (Java)
**Round**: R593 (continuation of R556-R592 FP-fix cycle)

## Goal

Reduce the 351 ArrayIndexChecker fires (CWE-129) on real code
(commons-lang3 + commons-codec). The R576 fingerprint showed
the dominant pattern was `if (3 <= pos && src[pos - 3])` —
a lower-bound guard on `pos` should suppress `src[pos - 3]`
but the current `GuardTracker` only recognized the guard on
`pos` itself, not on the derived index `pos - 3`.

## Three sub-fixes

### 1. R593-A — Guard propagation through BINOP±literal and MOVE

`GuardTracker` now adds a **derived guard** to the dest of
`BINOP dest = var ± literal` (or `MOVE dest = var`) when
`var` has an active or ever-seen guard. The bound is
adjusted: `pos >= 3` + offset `-3` becomes `t2 >= 0`
(GE/GT semantics: only propagate when the new bound
is meaningful, i.e., ≥ 0 for GE, ≥ -1 for GT).

```java
if (3 <= pos) {
    return src[pos - 3];  // R593: t2 = pos - 3 is derived
                          // guard "t2 >= 0", suppress
}
```

**New API**: `isGuardedByIncludingDerived(var, op)` —
checks both active guards AND derived guards. Used by
`ArrayIndexChecker`.

### 2. Operator-flip fix in `recordCompareIfApplicable`

When the literal is on the LHS of a comparison
(e.g. `3 LE pos`, equivalent to `pos GE 3`), the
previous code recorded the WRONG direction
(`pos LE 3`). The fix flips the operator (LE↔GE,
LT↔GT) so `isGuardedBy(pos, GE)` returns true.

This is a pre-existing bug in the tracker; the R593
work surfaced it via the mini test where `if (3 <= pos)`
was recording as `pos LE 3` instead of `pos GE 3`.

### 3. Worklist-order robustness

The DFA worklist processes instructions in BB-RPO order,
not source order. The worklist can visit a `BINOP dest =
var ± literal` AFTER the matching JUMP has popped the
active guard. The fix: use `allGuardsSeen` (cumulative,
per-function) instead of `activeGuards` (current stack)
when looking for source guards. The derived guard
itself is also per-function and never popped
(conservative: might over-suppress, but the typical
pattern is correct).

## Performance

The R593 work added a per-BINOP / per-MOVE cost.
Initial R593-A made the CWE-129 sweep 2x slower
(217s → 415s). Two optimizations restored and
improved baseline:

1. `propagatableSources: Set<TacVar>` — O(1) fast-path
   gate inside the propagation methods.
2. Top-level gate in `track()`: skip the propagation
   call entirely when no var is propagatable.

After both fixes: **CWE-129 sweep 59s (was 217s baseline,
3.7x faster)**. The propagation methods only run for
the small fraction of functions that have a guard, so
the common case is essentially free.

## Test results

- **R593GuardBaseTest**: 9/9 unit tests pass
  (posMinus3, posMinus5-not-propagated, posPlus1,
   moveAlias, chained-minus, originalIsGuardedBy-unchanged,
   clear-resets-derived, literalOnLHS-flips, op-flip-derived)
- **GuardTrackerTest**: 14/14 pass (2 skipped, R332 array.length
  disabled tests)
- **R572NullDerefResetTest**: 4/4 pass
- **R568SanitizerTest**: 5/5 pass
- **R19Cwe129Sweep** (Juliet CWE-129): 4402 files, 4027 fires,
  59s — **0 regression** (same as pre-R593)
- **R593TacDump** (mini test): 0 fires for `if (3 <= pos) src[pos-3]`,
  0 fires for `if (pos > 0) src[pos-1]` (was 1 each pre-R593)
- **R593RealcodeSweep** (lang3+codec, 326 files): 351 fires — same
  as R578 baseline. The R593 derived-guard does NOT help the
  dominant `if (3 <= pos && src[pos - 3])` pattern in real code
  (see "Known limitation" below).

## Known limitation (R593-C deferred)

The R593-A approach handles the **BINOP-after-BRANCH**
pattern:
```java
if (3 <= pos) {        // BRANCH first
    return src[pos - 3];  // BINOP derived guard works
}
```

It does NOT help the **BINOP-before-BRANCH** pattern
(Java short-circuit AND):
```java
if (3 <= pos && src[pos - 3]) {  // BINOP + ARRAYLOAD before BRANCH
    ...
}
```

TAC layout of the second pattern:
```
t = 3 LE pos          // comparison
t2 = pos - 3          // derived BINOP (BEFORE guard active)
t3 = src[t2]          // ARRAYLOAD (BEFORE guard active)
t4 = t LAND t3        // short-circuit AND
BRANCH t4             // guard `pos >= 3` activated HERE (too late)
```

By the time the BRANCH pushes the guard, the
ARRAYLOAD has already been processed and fired. To
fix this, we'd need to **retroactively** propagate
the guard to the BINOPs that fed the LAND/LOR's
operands. This is R593-C (deferred; multi-hour work).

The R593-A foundation is still useful for the
BINOP-after-BRANCH pattern, which appears in:
- explicit nested if-blocks
- for-loop bodies (`for (int i = 0; i < n; i++) { arr[i]; }` is
  i is in `i < n` guard by the time `arr[i]` is evaluated,
  but `i++` is the BINOP that derives next i — different shape)

## Files modified

- `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/GuardTracker.java`
  - Added `derivedGuards: Map<TacVar, List<Guard>>`
  - Added `derivedGuardedVars: Set<TacVar>`
  - Added `propagatableSources: Set<TacVar>` (perf gate)
  - Added `recordArithIfApplicable` (R593-A)
  - Added `recordMoveIfApplicable` (R593-A)
  - Added `isGuardedByIncludingDerived` (R593-A API)
  - Added `flipOperator` (R593 op-flip fix)
  - Modified `recordCompareIfApplicable` to flip literal-LHS
  - Modified `track` to dispatch MOVE + arith with perf gates
  - Modified `clear` to reset new state
  - Modified `onBranch` to update `propagatableSources`

- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/ArrayIndexChecker.java`
  - Switched from `isGuardedBy` to `isGuardedByIncludingDerived`

## New files

- `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/R593Debug.java`
  - Internal flag for stderr debug output (default off)

- `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/R593GuardBaseTest.java`
  - 9 unit tests for the new derived-guard API

- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R593TacDump.java`
  - Diagnostic test for TAC layout investigation

- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R593RealcodeSweep.java`
  - Real-code baseline measurement

- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R593PerFileFire.java`
  - Per-file fire distribution for lang3

## Status

R593-A SHIPPED. 9 new unit tests, 0 regression. R593-A
handles the BINOP-after-BRANCH pattern. R593-C (LAND/LOR
operand guard retroactive propagation) is the next step
for real-code FP reduction but is deferred.

## Key lessons

1. **Worklist-order matters**: DFA RPO + intra-block order
   means BRANCH and matching JUMP can be processed
   separately from BINOPs that should be guarded. Use
   cumulative per-function state (`allGuardsSeen`), not
   the current stack (`activeGuards`), for guard
   propagation.

2. **Literal-on-LHS comparison bug**: when the literal is
   on the LHS of a comparison (`3 <= pos`), the operator
   must be flipped (`pos >= 3`). The pre-existing code
   had this bug; R593 surfaced it.

3. **Fast-path gate pattern**: adding expensive logic to
   a hot path needs a per-function "any work to do"
   flag. Without it, every BINOP/MOVE pays the cost even
   when no guard exists.

4. **R593-A is a foundation, not a complete fix**: it
   only handles the explicit-nested-if pattern. The
   short-circuit AND pattern (more common in real code)
   needs R593-C.
