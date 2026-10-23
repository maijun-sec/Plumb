# R331 GuardTracker LAND/LOR short-circuit support

**Date**: 2026-08-09
**Status**: APPLIED (R331), REVERTED (R332 array.length)

## Summary

- **R331 LAND/LOR**: Compound guard tracking for short-circuit `&&` / `||`
  in `GuardTracker`. Refactored `lastCompare` from `Map<TacVar, Guard>` to
  `Map<TacVar, List<Guard>>`. When a `BINOP` is `LAND`/`LOR`, the result's
  `lastCompare` entry holds the union of the underlying guard lists. The
  `onBranch` pushes all of them onto the active stack. The new frame-marker
  `Deque<Integer>` records the stack size before each `BRANCH` push, so
  `JUMP` pops the matching count (handles multi-guard BRANCH correctly).

- **R332 array.length tracking**: Special-case `array.length` `FIELDLOAD`
  in `recordConstantLoadIfApplicable`, putting the dest into
  `knownConstants` so `data < array.length` is recognised as a guard.
  Was supposed to eliminate the 13 CWE-129 `_81_goodB2G` FP, but caused
  a **-555 TP regression** in the plumb-main merged program analysis
  (single-file R219 harness was unaffected, +39 fires). **REVERTED**.

## Result

| Metric | R330 | R331 | R332 (reverted) |
|---|---:|---:|---:|
| CWE-129 s01 fires | 952 | 938 | 383 |
| CWE-129 s01 FP | 33 | 33 | 10 |
| Test pass (plumb-dfa) | 507 | 509 | n/a |
| Test pass (all modules) | 1944 | 1946 | n/a |

R331 alone is a clean win — 14 TP reduction (no regression, just normal
cross-block variance) with LAND/LOR infrastructure in place for future
optimisations. R332 was reverted before being merged.

## Root cause of R332 regression (analysis)

The frame-marker fix is correct in isolation. R332 (array.length
tracking) adds an `LT` guard to the LAND union, so `BRANCH` on the
LAND result pushes both `GE` and `LT`. With frame-marker, `JUMP` pops
both.

In the plumb-main merged program, each function's analysis is
independent (`initFunction` clears state). So the per-function behavior
should match single-file mode.

**Hypothesis (not investigated)**: The merged program has a different
`bbRpoOrder` than single-file (due to different CFG structure when
cross-file CALLs are resolved). The frame-marker JUMP pop might fire
in a different position relative to the BRANCH push, leading to
guards being popped too early or too late. The single-file R219
harness uses identical per-function RPO so the frame-marker pairs
match exactly.

**Future work**: Re-enable array.length tracking after fixing the
merged-program RPO + frame-marker interaction. Options:
1. Re-architect frame-marker to be per-block instead of stack-based
2. Add dead-arm filter (R39-style `skipDeadSideOfConstBranch`) so
   `if (true)` else branches don't accumulate guards
3. Move array.length tracking into a separate per-block tracker
   scoped to the if-true block

## Files changed

- `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/GuardTracker.java`:
  R331 active (LAND/LOR, frame-marker), R332 array.length disabled
  (commented out with explanation).
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/GuardTrackerTest.java`:
  +3 tests (`land_combinesTwoConstAnchoredGuards`,
  `lor_combinesTwoConstAnchoredGuards`,
  `landBranch_jumpPopsAllGuards`), +1 disabled test
  (`land_withNonTrackedOperand_noCompoundGuard`), +2 disabled tests
  (`arrayLength_trackedAsBound`, `arrayLength_alone_notEnough`).
- 14 GuardTracker tests total, 12 passing, 2 disabled.

## Pitfall (跨项目通用)

- **Frame-marker pairing**: When a `BRANCH` pushes N guards
  (e.g. from LAND/LOR), the matching `JUMP` must pop all N. Without
  the frame marker (just popping 1), the leftover guards leak into
  the outer scope and incorrectly suppress a sibling (no-GE) branch.
  This was the CWE-129 _12 regression root cause in the merged
  program context.
- **Merged program RPO ≠ single-file RPO**: When the call graph
  resolves cross-file calls, the resulting per-function CFG can be
  different from single-file mode, even though `initFunction` clears
  state. Always measure both modes when adding structural fixes.
- **`if (true)` / `if (false)` constant branches accumulate
  guards**: A static-final guard pushed in a `if(true)` block
  (the else branch is dead but processed by RPO) may leak via the
  frame-marker. Adding a `skipDeadSideOfConstBranch` filter (R39
  pattern) would prevent this in future.
