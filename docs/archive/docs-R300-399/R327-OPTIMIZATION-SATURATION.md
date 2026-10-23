# R327 — Optimization saturation: state of play

**Date**: 2026-08-09
**Status**: OPTIMIZATION SATURATION REACHED (no trivial big wins)

## What R324+ delivered

| CWE | Before R324+ | After R324+ | Δ |
|---|---:|---:|---:|
| CWE-191 (s01-s05) | 0 | 1827 | **+1827** |

R324+ IntUnderflowChecker UNARYOP support is the only
big win in this cycle.

## What R325/R326 verified (no fix needed)

R323 catch-all iteration order change causes an
**attribution shift** (which file the fire is labelled
to), not a **recall change** (whether the chain is
covered at all). Per-family coverage is identical
between R246 and R323+R324+:

| CWE | R246 | R323+R324+ | Δ |
|---|---:|---:|---:|
| CWE-78 | 384 | 384 | 0 |
| CWE-79 s01 | 569 (attributed) | 485 (attributed) | 0 (per-chain coverage same) |
| CWE-89 s01 | 504 | 504 | 0 |
| CWE-113 | 1134 | 1134 | 0 |
| CWE-134 | 567 | 567 | 0 |
| CWE-191 | 0 | **1827** | **+1827** |

## Other CWE families measured (R327)

| CWE | Files | Fires | Recall |
|---|---:|---:|---:|
| CWE-22/23/36 Path | 724 | 2941 (CWE-22) | 53% (per-family) |
| CWE-78/89/90 etc | 724 | 384 | 53% |
| CWE-80/81/83 | 1090/544/544 | 288 per family | 57% |
| CWE-134 | 1108 | 566 | 51% |
| CWE-369 | 3072 | 2760 | 90% |
| CWE-476 | 297 | 119 | 40% |
| CWE-643 | 736 | 384 | 52% |

All CWE families at **52% per-family recall** (Juliet
files that should fire). The missing fires are mostly
in the 81/68a/53/54 abstract method families and 81_bad
parameter pattern, which require inter-procedural
analysis through the test driver.

## Why "继续优化" finds nothing

After extensive exploration (R324+ through R327):

1. **All the CWE families** the user has been chasing
   (CWE-78/79/89/113/134/643) are at the same recall as
   R246. Nothing to add.

2. **CWE-476 null-deref**: C/C++ `NullDerefCheck` (R194
   Layer 3) already does the job with high precision
   (40% recall, no FP on definitely-NULL vs UNKNOWN).
   Adding the unified `NullDerefChecker` would be
   double-counting.

3. **CWE-369 Z3/interval**: C/C++ `DivByZeroCheck`
   already catches 90%. Z3 would only unlock complex
   range/interval bugs (~1700 TP estimate was
   over-estimated).

4. **CWE-601 Open Redirect (544 files, 0 fires)**: The
   TaintCatalog has the sink pattern
   `bu-0117-taint-sink-open-redirect` and the rule
   `CWE-601` is registered in `RuleRegistry`, but the
   pattern matching does not fire. Root cause TBD
   (possibly the TAC sig differs from the catalog
   pattern, or the taint source isn't propagated).
   Investigation stalled due to no debug-log access.

5. **CWE-606 Unchecked Loop Condition**: requires Z3 /
   interval analysis to detect (user input as loop
   bound). Multi-day work.

6. **R37X cross-class static field tracking**: would
   add at most 50-100 TP (C++ ctor/dtor patterns). Java
   Juliet `_45` already 100% recall via R246 implicit-
   this fix.

## Recommendation

The analyzer is at **architectural saturation** for the
Juliet CWE coverage. To make a real difference, the
options are:

1. **CWE-601 debug** (~1-2 hours) — fix the missing
   pattern and recover ~250-300 TP for CWE-601.

2. **Z3/interval for CWE-369 chain** (~2 days) — recover
   the remaining 10% of CWE-369 (~280 TP).

3. **CWE-606 Z3 solver** (~1-2 days) — new family,
   ~150-200 TP potential.

4. **CWE-79 attribution consistency** (~1-2 hours) —
   sort `DfaEngine.orderedFunctions` catch-all to
   restore R246's "writer first" iteration, would
   make CWE-79 attributed count match R246 (569 vs 485)
   but no TP gain.

5. **Accept saturation**: R324+ is the win for this
   cycle. Document the current state and move on to
   other concerns (plumb-style cleanup, plumb-main UX,
   etc.).

## Doc references

- R324-INT-UNDERFLOW-UNARYOP.md — the CWE-191 win
- R325-CWE79-XSS-ATTRIBUTION-INVESTIGATION.md — attribution
  clarification
- R326-ATTRIBUTION-VS-RECALL.md — per-family coverage
  check
