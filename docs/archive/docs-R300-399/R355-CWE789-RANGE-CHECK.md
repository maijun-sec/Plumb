# R355 — CWE-789 Range-Check Suppression (2026-08-05) — DONE, 0 new tests, 0 regression

## Goal
Suppress CWE-789 false positives on memory allocation sizes that are validated by an explicit `> 0 && < N` (or `>= 0 && < N`) range check before the allocation.

## Pattern
```java
String data = req.getQueryString();   // tainted
int n = Integer.parseInt(data);
if (n > 0 && n < 1000) {              // R355: range check
    ArrayList list = new ArrayList(n);  // R350 v1: fires CWE-789 (FP)
}
```

The Juliet CWE-789 goodB2G baseline uses this shape. Pre-R355 the checker fires CWE-789 on the allocation because it can't see the validation.

## R355 implementation
- Reuses the R352 `collectRangeChecks()` pattern
- Pass 1: vars compared `> 0` / `>= 0` against a constant → lowerBounded set
- Pass 2: vars in lowerBounded + `< N` (N > 0) / `<= N` (N >= 0) → validatedAliases set
- The allocation check skips sizes in the validated set
- Conservative: only validates vars already in the taint-aliases map

## Updated test
- `validatedSizeDoesNotFire` — previously documented the v1 limitation (expected FP), now asserts the suppression works:
  - `n > 0 && n < 1000` → 0 findings (was 1 finding in R350 v1)
- All 5 R350 tests still pass

## Numbers
- plumb-dfa tests: 437 (R355) — same count as R353 (updated 1 test, no new tests)
- 9 modules total: 0 fail, 0 regression

## R355 backup
`D:\work\tmp\r350_done\` (already exists, R350 doc updated to reflect R355)

## R356+ candidates
- R356: R351 (CWE-197) range-check suppression — same template
- R357: R349 (CWE-606) compound range-check (`a > 0 && b < n`)
- R358: Cross-function taint propagation for R350/R351
- R359: 5-round retrospective R355-R359
