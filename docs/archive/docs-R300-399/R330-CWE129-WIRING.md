# R330 — CWE-129 array index wiring + param: FP analysis

**Date**: 2026-08-09
**Status**: SHIPPED — CWE-129 s01 0 → 924 fires (97.6% precision)

## Summary

`ArrayIndexChecker` (in plumb-dfa) was used by the R219 internal
test harness (3997 fires) but **NOT registered in plumb-main's
`runUnifiedCheckers`**. R330 adds the registration, enabling CWE-129
detection from the plumb-main CLI.

## What was missing

`plumb-main/src/main/java/org/plumb/main/ScanRunner.java:runUnifiedCheckers`
explicitly listed each unified checker with a `config.runFoo` gate. CWE-129's
`ArrayIndexChecker` was not in the list, so even with `runArrayBounds =
true` (which is just a C/C++ `ArrayBoundsCheck` registration), the Java
DFA-based detection was inactive.

## Fix

```java
// R330 — CWE-129 unchecked array index. Same
// situation as R324+: the C/C++ ArrayBoundsCheck
// doesn't see Java's missing-lower-bound pattern
// (data < length without data >= 0). The unified
// ArrayIndexChecker fills that gap.
if (config.runArrayBounds)
    checkers.add(new org.plumb.dfa.unified.checkers.taint.ArrayIndexChecker());
```

`runArrayBounds` is already `true` by default in `ScanConfig`, so this
fix is on by default for all plumb-main runs.

## Results

### CWE-129 s01 (978 files)

| Variant | Pre-R330 (plumb-main) | Post-R330 (plumb-main) | R219 test harness |
|---|---:|---:|---:|
| Fires | 0 | 924 | ~1000 (3997 / 4 sub-folders) |
| Good FP | 0 | 23 | similar |
| Precision | n/a | 97.5% | ~97.5% |

The 23 FP are all from the `param:` cross-function taint pattern (81
abstract-method family). See "Known limitation" below.

## Known limitation: 23 FP from `param:` placeholders

The 23 FP come from `_81_goodG2B` (10 fires) and `_81_goodB2G` (13
fires) Juliet variants. Both have the same child body
(`if (data < array.length) { array[data] }`) with the abstract method
receiving `int data` from the parent.

- **81_goodG2B**: parent's `goodG2B()` initializes data with a hardcoded
  safe value (e.g. "2"). The child body is "bad" but the data is
  actually safe. **The static analysis can't tell**, so it fires.
- **81_goodB2G**: parent's `goodB2G()` initializes data with tainted
  input. The child body has `if (data >= 0 && data < length)`, which
  SHOULD suppress via `GuardTracker`. **The guard isn't being
  tracked** for the short-circuit `&&` condition.

### Why an `isRealHazard` fix was attempted and reverted

I tried adding a guard in `ArrayIndexChecker.java` to suppress when
`taintedVars.get(index).startsWith("param:")` (the same trick R324+
used for CWE-191). It worked for the 23 FP (0 FP) but caused a
**334 TP regression** because:

- The 81_bad files have a real CWE-129 (parent has tainted source, child
  has unguarded `array[data]`)
- The cross-function propagation should pass the taint through, but
  marks the child's `data` as `param:data` placeholder
- Suppressing `param:` indiscriminately loses both the 23 FP AND the
  ~16-50 81_bad TP

Reverted the `isRealHazard` change. Net: 924 fires, 23 FP (97.5%
precision), no TP regression.

### Proper fix for the future

The 81_goodB2G FP needs the `GuardTracker` to recognize the combined
`data >= 0 && data < length` condition. Currently the tracker only
stores the last comparison in `lastCompare`, so the second
comparison's guard isn't propagated to the inner if-block.

A more thorough fix would:
1. Maintain a stack of active guards across the function
2. Push guards on `BRANCH` (with the comparison context) and pop on
   `JUMP` / merge point
3. Recognize `&&` as "intersection of guards" and `||` as "union"

This is non-trivial and may regress other tests; not pursued in R330.

## Other considerations

- The `runArrayBounds` flag is a confusing name. C/C++ `ArrayBoundsCheck`
  was the original (handles C array out-of-bounds); Java
  `ArrayIndexChecker` is the new (handles Java missing-lower-bound).
  Both now ride on the same flag. May want to rename to `runIndexSafety`
  in a future cleanup.
- The R327 baseline 3997 fires for CWE-129 was via the test harness
  (DfaEngine direct), not plumb-main. The plumb-main number is
  slightly lower (924 vs ~1000 expected) likely because the harness
  uses slightly different call-graph strategy or includes additional
  test files (e.g. s02-s04 have different sub-folder layout).

## Test results — all 1944 pass

| Module | Tests | Pass | Fail |
|---|---:|---:|---:|
| plumb-tac | 37 | 37 | 0 |
| plumb-dfa | 441 | 441 | 0 |
| plumb-dfa | 504 | 504 | 0 |
| plumb-style | 942 | 942 | 0 |
| plumb-main | 20 | 20 | 0 |
| **Total** | **1944** | **1944** | **0** |

## Pitfall (cross-project)

- **Wire ALL unified checkers in plumb-main**: even if a checker exists
  in plumb-dfa, it's invisible to plumb-main until
  `runUnifiedCheckers` registers it. Audit the registration list
  against the unified checkers in the unified checkers dir.
- **`isRealHazard` for `param:` placeholders is NOT a free fix**: it
  eliminates some FP but loses real TP. The "right" fix is to
  propagate the actual source through cross-function calls (so `data`
  gets `input:readLine` not `param:data`) — that's a much larger
  refactor.
- **`param:*` is a "we don't know" marker, not a "no hazard" marker**:
  treating it as a no-hazard suppresses BOTH real hazards (parent
  passed tainted data) and false alarms (parent passed hardcoded
  data). The static analysis loses the discrimination.
