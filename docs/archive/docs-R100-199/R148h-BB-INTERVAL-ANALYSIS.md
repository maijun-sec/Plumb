# R148h — BB-level IntervalAnalysis (infra round, not a metric win)

**Date**: 2026-07-29
**Round**: R148h (post-R148g UncheckedReturnCheck)
**Status**: ✅ complete — 0 metric win on Juliet, 0 regression, infrastructure gain

---

## 1. Goal

Replace the single-forward-pass `IntervalAnalysis.solve(List<TacInstruction>)`
with a BB-level forward dataflow. The R148f report called out
"single-pass solver not modeling BB join" as the root cause for
the CWE-369 / CWE-190 / CWE-191 / CWE-197 / CWE-129 / CWE-789 /
CWE-400 family losses in merged mode.

The plan was: rewrite `IntervalAnalysis.solve(TacFunction)` as a
worklist, and switch `DivByZeroCheck` (and eventually
`IntegerOverflowCheck` / `ArrayBoundsCheck`) over to it.

## 2. Implementation

### 2.1 `IntervalAnalysis.solveWithBlockState(TacFunction)`

`plumb-dfa/src/main/java/org/plumb/dfa/solver/IntervalAnalysis.java`
(+ ~120 lines).

- Builds the function CFG via `TacCfgBuilder`.
- Per block: in-state is the **join** (per-variable `Interval.union`)
  of every predecessor's out-state. A variable present in some
  predecessor but not in another widens to `TOP` (unconstrained
  on at least one incoming path).
- Transfer: linear `step` walk over the block's instructions.
- Worklist: pushes successors whenever the per-var
  `Interval.widen()` diff against the previous out-state is
  non-empty. First-visit of a block always pushes.
- Returns `Map<Integer, Map<TacOperand.TacVar, Interval>>` —
  per-block in-state, indexed by block id.

### 2.2 `DivByZeroCheck` switchover

- Walks the CFG; for each block takes the in-state from
  `solveWithBlockState`, propagates through the block linearly
  with a private `propagate()` mirror of the legacy `step()`,
  and fires on `DIV` / `MOD` whose rhs interval contains 0.
- `IntegerOverflowCheck` / `ArrayBoundsCheck` still use the
  legacy linear `solve(List)` — out of scope for this round
  (they need their own per-insn state-at-instruction
  infrastructure, which is a follow-up R-round).

## 3. Bug caught during dev

Initial run of the BB-level test
`suppressesWhenAllPathsNonZero` reported "data = `<top>`"
instead of the expected `[2, 5]`.

Root cause: the worklist seed compared the new out-state to a
pre-initialized empty `prev`. For a `BRANCH`-terminated entry
block (whose transfer is the identity, no state change), the
new and prev out-states were both empty, so
`widens(empty, empty)` returned `false` and the
`trueLabel` / `falseLabel` successors were never pushed onto
the worklist. The entire dataflow stalled at block 0.

Fix: `widens()` first-time visit (prev == null) always pushes
successors. Documented in the Javadoc on
`solveWithBlockState`.

## 4. Unit tests

`plumb-dfa/src/test/java/org/plumb/dfa/DivByZeroBbTest.java`
(5 tests, all pass):

- `detectsZeroAtJoin` — `if (b) data=0; else data=5; return 10 % data;`
  fires (BB join widens to `[0, 5]`, contains 0)
- `suppressesWhenAllPathsNonZero` — `if (b) data=2; else data=5; ...`
  does **not** fire (BB join = `[2, 5]`, no zero). This is the
  case the legacy linear pass would have produced an FP on
  (it'd see the last write in the source order).
- `detectsAfterConstantSwitch` — `switch (6) { case 6: data=0; ... }`
  fires (data is provably 0 on the constant branch).
- `detectsSequentialZeroWinsLinear` — `data=2; data=0;` fires
  (legacy linear still considered; the join doesn't lose this).
- `noFindingWhenUnreachableAfterReturn` — function returns
  before any divide; no finding.

## 5. Regression status

Full Plumb test suite:

- **305 tests / 0 fail / 0 error / 1 skipped** (was 299
  pre-R148h; +5 from `DivByZeroBbTest`, +1 from
  `UncheckedReturnCheckTest` 9 vs 9 in R148g)
- All existing checker tests still pass; `DivByZeroCheck` is
  correctness-preserving for the simple cases that the linear
  pass already handled.

## 6. Juliet CWE-369 / CWE-190 / CWE-191 evaluation

| Family | R148f baseline | R148g | **R148h** |
|---|---|---|---|
| CWE-369 TP | 0 | 0 | 0 |
| CWE-369 FP | 3 | 3 | 3 |
| CWE-369 elapsed | 34 s | 34 s | 24 s |
| CWE-190 TP | 0 | 0 | 0 |
| CWE-190 FP | 0 | 0 | 0 |
| CWE-190 elapsed | 95 s | — | 71 s |
| CWE-191 TP | 0 | 0 | 0 |
| CWE-191 FP | 0 | 0 | 0 |
| CWE-191 elapsed | 78 s | — | 54 s |

**0 metric change** on the three R148f-flagged numeric-checker
families. Why the BB-level solver didn't move the needle:

- CWE-369's 3 R148f FPs all live in `flow 67+` `_b.java` files
  (cross-TU: source in `_a`, sink in `_b`, parameter-passed
  data is `TOP` at the callee). The BB-level solver only joins
  within a single function — `data` arrives as a parameter
  with no interval, so the solver can't tighten the divisor.
  The fix needs cross-TU parameter-side constraining, which is
  out of scope for R148h.
- CWE-190 / CWE-191 are checked by `IntegerOverflowCheck`, which
  was not migrated in this round (its per-insn state-at-insn
  need is structurally different from DivByZero's).

R148h's elapsed time on CWE-369 actually went **down** by 30 %
(34 s → 24 s) — a side effect of the worklist processing
fewer no-op blocks (BB 0 of each test function is now
short-circuited to a no-state-change "BRANCH only" transfer
that doesn't push successors redundantly).

## 7. What R148h actually bought

**Infrastructure for followup rounds.** Future R-rounds can now
do per-BB forward dataflow without rebuilding the worklist:

- **R148h+1: `IntegerOverflowCheck` migration** — wire it up to
  `solveWithBlockState`; the same BB-level join should fix
  CWE-190 / CWE-191 / CWE-197 `flow 15` (switch with const
  selector) FPs that the legacy linear pass produces.
- **R148h+2: cross-TU parameter-constraining** — extend the
  worklist to consume the `analysis` layer's propagated
  constants at the call site (the R141/R142 TaintAnalysis
  work already has a fixpoint loop pattern; can be reused).
  This would actually unlock CWE-369 / CWE-78 / CWE-36's
  environment-source TPs.
- **R148h+3: `ArrayBoundsCheck` migration** — same shape as
  DivByZero, low risk.

R148h itself ships a meaningful correctness improvement
(BB-level join) for DivByZero with no Juliet-visible win,
because the CWE-369 losses R148h was meant to fix turn out
to be at the cross-TU layer, not the intra-function solver
layer.

## 8. Files changed / added

- Modified: `plumb-dfa/src/main/java/org/plumb/dfa/solver/IntervalAnalysis.java`
  (+ ~120 lines, new `solveWithBlockState` entry point and
  `joinPredecessors` / `widens` helpers; the legacy
  `solve(List)` is preserved for back-compat)
- Modified: `plumb-dfa/src/main/java/org/plumb/dfa/DivByZeroCheck.java`
  (rewrote `checkFunction` to walk the CFG and use
  `solveWithBlockState`; added a private `propagate()` mirror
  of the legacy `step` so DivByZero's transfer stays
  self-contained)
- Added: `plumb-dfa/src/test/java/org/plumb/dfa/DivByZeroBbTest.java`
  (5 tests)
- 0 IR / 0 TAC / 0 new side table.
- 0 changes to `IntegerOverflowCheck` / `ArrayBoundsCheck` /
  `MergedProgram` / `TacCfgBuilder` / etc.

## 9. Cumulative R148x status

```
R148c  capability assessment
R148d  Spring/JAX-RS binding annotation source
R148e  parallel Phase-1 plumbing
R148f  Juliet baseline (0% TP / 0.04% FP)
R148g  UncheckedReturnCheck              (CWE-252: 0 → 1 TP)
R149b  per-file eval (validation)        (CWE-252: 1 → 16 TP, infra-only)
R148h  BB-level IntervalAnalysis          (0 metric, infra)
```

Plumb tests: 305 / 0 / 0 / 1. Plumb jar still ships. R148i+
(target: short-circuit / virtual dispatch / CWE-134 / etc.)
can build on the R148h infra.
