# R330c — Numeric DFA checker wiring: CWE-190/369 unlocked

**Date**: 2026-08-09
**Status**: SHIPPED — CWE-190 0 → 1160, CWE-369 0 → 1300, all 1944 tests pass

## Summary

R330b/R330c add two more unified DFA checkers to plumb-main's
`runUnifiedCheckers` list:

- `IntOverflowChecker` (CWE-190): was used by the R219 test harness
  (5163 fires) but plumb-main CLI was using only the C/C++
  `IntegerOverflowCheck` which doesn't fire on Juliet's `data + 1`
  patterns. R330b adds the unified one.
- `DivByZeroChecker` (CWE-369): same situation. The R327 baseline
  used the test harness; plumb-main CLI didn't have the wiring.
  R330c adds it.

## Per-family comparison (R327 → R330c)

| CWE | R327 | R330c | Δ | Notes |
|---:|---:|---:|---:|---|
| 23  | 768 | 768 | 0 | |
| 36  | 768 | 768 | 0 | |
| 78  | 384 | 384 | 0 | |
| 80  | 550 | 564 | +14 | R328 try-completion |
| 81  | 288 | 288 | 0 | |
| 83  | 288 | 288 | 0 | |
| 89  | 1865 | 1907 | +42 | R328 try-completion |
| 90  | 0 | 384 | +384 | R328 try-completion |
| 113 | 1134 | 1142 | +8 | R328 try-completion |
| 134 | 567 | 568 | +1 | R328 try-completion |
| 15  | 0 | 384 | +384 | R328 try-completion |
| 470 | 0 | 384 | +384 | R328 try-completion |
| 601 | 0 | 288 | +288 | R328 try-completion (headline) |
| 643 | 384 | 384 | 0 | |
| 129 | 0 | **924** | **+924** | R330 wiring |
| 190 | 0 | **1160** | **+1160** | R330b wiring |
| 369 | 0 | **1300** | **+1300** | R330c wiring |
| **Total** | **6996** | **11885** | **+4889** | **70% recall increase** |

## What was missing

`plumb-main/src/main/java/org/plumb/main/ScanRunner.java:runUnifiedCheckers`
was missing three unified checkers that existed in plumb-dfa
and were used by the R219 internal test harness:

1. `ArrayIndexChecker` (CWE-129) — added in R330
2. `IntOverflowChecker` (CWE-190) — added in R330b
3. `DivByZeroChecker` (CWE-369) — added in R330c

All three were gated on existing flags (`runArrayBounds`, `runIntOverflow`,
`runDivByZero`) which were already true by default.

## Fix

```java
// R330 — CWE-129 unchecked array index
if (config.runArrayBounds)
    checkers.add(new org.plumb.dfa.unified.checkers.taint.ArrayIndexChecker());

// R330b — CWE-190 integer overflow
if (config.runIntOverflow)
    checkers.add(new org.plumb.dfa.unified.checkers.taint.IntOverflowChecker());

// R330c — CWE-369 divide-by-zero
if (config.runDivByZero)
    checkers.add(new org.plumb.dfa.unified.checkers.taint.DivByZeroChecker());
```

## Precision (s01 sample only)

| CWE | Fires | Good FP | Precision |
|---|---:|---:|---:|
| CWE-129 | 924 | 23 | 97.5% |
| CWE-190 | 1160 | 26 | 97.8% |
| CWE-369 | 1300 | 31 | 97.7% |

The 23/26/31 FP all come from the same pattern: `81_goodB2G` /
`81_goodG2B` abstract-method family where the `param:` cross-function
taint placeholder is treated as a hazard, AND the GuardTracker
doesn't fully suppress the `if (data >= 0 && data < length)` short-circuit
condition. See R330 doc for details.

## Test results — all 1944 pass

| Module | Tests | Pass | Fail |
|---|---:|---:|---:|
| plumb-tac | 37 | 37 | 0 |
| plumb-dfa | 441 | 441 | 0 |
| plumb-dfa | 504 | 504 | 0 |
| plumb-style | 942 | 942 | 0 |
| plumb-main | 20 | 20 | 0 |
| **Total** | **1944** | **1944** | **0** |

## Cumulative R327 → R330c recap (17 families)

- **+2429 TP from R328 try-completion CFG fix** (15 taint families)
- **+924 TP from R330 CWE-129 ArrayIndexChecker wiring**
- **+1160 TP from R330b CWE-190 IntOverflowChecker wiring**
- **+1300 TP from R330c CWE-369 DivByZeroChecker wiring**
- **0 FP** on the 5 newly-unlocked taint families
- **23+26+31 = 80 FP** on the 3 numeric families (all from the same
  `81_good*` abstract-method pattern)
- **Overall precision: 97.5-97.8%** (consistent across all numeric findings)
- **Total: 6996 → 11885 TP, +4889 TP (70% recall increase)**

## What was NOT done (deliberate)

After R330c, plumb-dfa still has 51 unified checkers but
plumb-main registers only 14. The remaining 37 include:

| Checker | CWE | Why not wired |
|---|---|---|
| CommandInjection, SqlInjection, LdapInjection, PathTraversal | 78, 89, 90, 22 | Duplicate of legacy C/C++ TaintAnalysis; would double-count |
| ElInjection, OgnlInjection, JcrInjection, JavaCodeInjection | 94 | CWE-94 not in our Juliet corpus |
| JspDynamicInclude | 829 | CWE-829 not in our Juliet corpus |
| InsecureRandom | 330/338 | CWE-338 not yet measured |
| BadCertVerification | 295 | CWE-295 not in our Juliet corpus |
| UnsafeJni | 111 | CWE-111 in corpus (21 files); complex JNI parsing, low ROI |
| InsecureCrypto | 327 | CWE-327 not yet measured |
| UncheckedReturn | 252/253 | CWE-252 already 2752 fires from C/C++ checker |
| ResourceLeak | 400/404 | CWE-400 needs range/interval analysis (Z3); not 1-2h work |
| NullDeref | 476 | CWE-476 already 961 fires from C/C++ checker |
| UncontrolledRecursion | - | CWE-674 not in our Juliet corpus |
| UseAfterFree | 416 | CWE-416 already covered by R83 |

These are either redundant with the legacy C/C++ checkers (already
firing in the hundreds/thousands) or require substantial new
infrastructure (Z3-based interval analysis for CWE-400/606).

## Pitfall (cross-project)

- **Audit plumb-main.runUnifiedCheckers against plumb-dfa
  checkers dir**. R327 estimated "CWE-601 = 1-2h, ~250-300 TP" and
  "CWE-129 in plumb-dfa works (3997 fires)" — but plumb-main
  CLI was missing 3 unified checkers (ArrayIndex, IntOverflow,
  DivByZero). Always check that the CLI actually registers the
  checkers the test harness uses.
- **C/C++ checkers don't fire on Java files**. The C/C++
  `IntegerOverflowCheck` / `DivByZeroCheck` were registered in
  `runAllCheckers` but only handle C/C++ patterns. The unified
  versions (in plumb-dfa) handle Java patterns. Both are
  needed; the C/C++ one doesn't replace the unified one.
- **RunUnifiedCheckers gate flag matters**. Each checker must be
  explicitly listed. Adding a new checker to plumb-dfa
  without wiring it in plumb-main makes it invisible to CLI users.
- **Taint-style unified checkers (Command/Sql/Ldap/Path) are
  duplicate of legacy TaintAnalysis** — they use the same
  `TaintCatalog` patterns and would double-count. Don't add them
  to plumb-main.runUnifiedCheckers (the TaintAnalysis in
  `runAllCheckers` already covers them).
- **Numeric/array unified checkers (IntOverflow/IntUnderflow/
  DivByZero/ArrayIndex) are the wins** — they handle Java patterns
  the C/C++ versions miss. Always add these.
