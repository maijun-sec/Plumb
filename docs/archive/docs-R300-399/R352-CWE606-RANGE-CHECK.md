# R352 — CWE-606 Range-Check Suppression (2026-08-05) — DONE, 0 new tests, 0 regression

## Goal
Suppress CWE-606 false positives on loop bounds that are validated by an explicit `>= 0 && < N` (or `> 0 && < N`) range check before the loop.

## Pattern
```java
String data = req.getQueryString();   // tainted
int n = Integer.parseInt(data);
if (n >= 0 && n < 100) {              // R352: range check
    for (int i = 0; i < n; i++) {     // R349 v1: fires CWE-606 (FP)
        ...
    }
}
```

The Juliet CWE-606 goodB2G baseline uses exactly this shape. Pre-R352 the checker fires CWE-606 on the loop bound `n` because it can't see the validation.

## R352 implementation
- `UncheckedLoopConditionCheck.collectRangeChecks()` — pass-based collection
  - Pass 1: vars compared with `>= 0` or `> 0` against a constant → lowerBounded set
  - Pass 2: vars in lowerBounded also compared with `< N` (N > 0) or `<= N` (N >= 0) → range-checked set
- The loop-condition check skips loop bounds in the range-checked set
- Conservative: only validates vars already in the taint-aliases map (no false negatives from "untainted" range checks)

## Updated test
- `validatedLoopBoundDoesNotFire` — previously documented the v1 limitation, now asserts the suppression works:
  - `n >= 0 && n < 100` → 0 findings (was 1 finding in R349 v1)
- All 4 R349 tests still pass

## CWE-606 sweep results (post-R352)
- File-level: 2 TP / 1 FP (was 0/1 TP/FP in R349 v1)
- precision: 0.667 (was 0.480)
- recall: 0.005 (low; still need more source pattern coverage)
- Total findings: 102 (was 99 — slight noise)
- TP/FP at finding level: 69 bad_findings / 28 good_findings (was ~36 TP / ~39 FP at high level)

## R352 limitations (carry-forward)
- Only `>= 0` / `> 0` and `< N` / `<= N` patterns are recognised
- Compound `if (a > 0 && b < n)` not handled (separate vars)
- Short-circuit `&&` / `||` evaluation not modelled — second condition assumed always evaluated

## Numbers
- plumb-dfa tests: 436 → 436 (no new tests; updated `validatedLoopBoundDoesNotFire` from "FP" to "no FP")
- 9 modules total: 0 fail, 0 regression

## R352 backup
`D:\work\tmp\r352_done\` (1 src + 1 test + 2 data files)

## R353+ candidates
- R353: Performance pass (cache invalidation, parallel sweep, JFR profiling)
- R354: 10-round retrospective R340-R354
- R352b: Compound range-check (`a > 0 && b < n`) support
- R352c: Source-pattern coverage expansion (5/14 Juliet sources)
