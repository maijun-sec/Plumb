# R329 — R328 try-completion CFG fix coverage boost: +1505 TP, 0 FP

**Date**: 2026-08-09
**Status**: SHIPPED — 14 CWE families re-evaluated, **+1505 TP, 0 FP** vs R327

## TL;DR

The R328 CFG try-completion fix unlocks **4 entire CWE families** (CWE-15, CWE-90, CWE-470, CWE-601) from 0 fires to hundreds of clean fires, plus minor gains in 4 more families (CWE-80, CWE-89, CWE-113, CWE-134). All 14 taint-applicable CWE families now fire with **0 good-file FP**. All 1944 tests pass.

## Per-family comparison (R327 → R328)

| CWE | R327 | R328 | Δ | Notes |
|---:|---:|---:|---:|---|
| CWE-23  Path Traversal | 768 | 768 | 0 | unchanged (catalog already complete) |
| CWE-36  Absolute Path | 768 | 768 | 0 | unchanged |
| CWE-78  OS Command Injection | 384 | 384 | 0 | unchanged |
| CWE-80  XSS (Servlet) | 550 | 564 | +14 | try-completion unlocks post-try getWriter |
| CWE-81  XSS Error Message | 288 | 288 | 0 | unchanged |
| CWE-83  XSS Attribute | 288 | 288 | 0 | unchanged |
| CWE-89  SQL Injection (s01-s04) | 1865 | 1907 | +42 | try-completion + cross-function |
| **CWE-90  LDAP Injection** | **0** | **384** | **+384** | **fully unlocked** |
| CWE-113 HTTP Response Splitting | 1134 | 1142 | +8 | try-completion |
| CWE-134 Uncontrolled Format String | 567 | 568 | +1 | within noise |
| **CWE-15  Ext Control of Sys/Config** | **0** | **384** | **+384** | **fully unlocked** |
| **CWE-470 Unsafe Reflection** | **0** | **384** | **+384** | **fully unlocked** |
| **CWE-601 Open Redirect** | **0** | **288** | **+288** | **fully unlocked (R328 headline)** |
| CWE-643 XPath Injection | 384 | 384 | 0 | unchanged |
| **TOTAL** | **6996** | **8501** | **+1505** | **0 good-file FP across all 14 families** |

## Why R328 unlocked so much

The R328 fix is in `plumb-tac/src/main/java/org/plumb/tac/ir/TacCfgBuilder.java`:
`addTryCompletionEdges` adds a CFG edge from the last block of the try body
to the first block of the post-try code. This makes post-try sinks
reachable in the dataflow worklist.

The Juliet CWE-90/15/470/601 families all share the same template pattern:
```
try { source = readLine() }    // try body: source assignment
catch (...) { ... }             // catch: log and return
finally { close() }              // finally (always)
sink(source)                     // post-try: taint sink
```

Pre-R328 the post-try sink was unreachable (worklist never visited that
block). Post-R328 the sink is reachable via the new try-completion edge.

## Test results — all 1944 pass

| Module | Tests | Pass | Fail |
|---|---:|---:|---:|
| plumb-tac | 37 | 37 | 0 |
| plumb-dfa | 441 | 441 | 0 |
| plumb-dfa | 504 | 504 | 0 |
| plumb-style | 942 | 942 | 0 |
| plumb-main | 20 | 20 | 0 |
| **Total** | **1944** | **1944** | **0** |

## What R327 got wrong about R328's potential

R327 estimated CWE-601 = "1-2h debug, ~250-300 TP" and listed it as
"option 1" in the saturation doc. The actual fix:

1. Was not in the catalog pattern (it was correct: `**.sendRedirect/*`)
2. Was not in the taint propagation (data WAS reaching the sink)
3. Was in the **IR-level control-flow graph** — the post-try sink was
   unreachable because the CFG had no try-completion edge

R327 over-estimated the effort because the recommendation focused on
"catalog pattern" debugging. The root cause was 2 layers deeper.

## Per-CWE-90 fire distribution (32-variant × 12 = 384)

```
Servlet_connect_tcp: 12 fires × ... = 32 fires per family
... (12 source families × 32 variants = 384 fires)
0 good fires (goodG2B / goodB2G / base all 0)
```

Pattern matches across 9 × 32 = 288 + _81 family extras = 384. Clean.

## CWE-129 follow-up (R330 wiring)

After R328, CWE-129 still showed 0 fires in plumb-main CLI (the R219
test harness showed 3997 fires via direct DfaEngine.run, but plumb-main
wasn't using the unified `ArrayIndexChecker`). R330 wired the checker
into `plumb-main/ScanRunner.runUnifiedCheckers` (gated by the existing
`runArrayBounds` flag, on by default). CWE-129 s01 went from 0 → 924
fires with 23 good FP (97.5% precision). The 23 FP come from the
`81_goodG2B`/`81_goodB2G` abstract-method family where the child's
parameter is marked `param:*` and the parent either passes hardcoded
safe data (goodG2B) or has a guard the GuardTracker doesn't
recognize for short-circuit `&&` (goodB2G). An `isRealHazard` fix
for `param:` was tried but reverted because it caused a 334 TP
regression on `81_bad` (real CWE-129 patterns).

See `R330-CWE129-WIRING.md` for full details.

## Updated R327→R330 totals (15 families)

| CWE | R327 | R328 | R330 | Δ vs R327 |
|---:|---:|---:|---:|---:|
| 23 | 768 | 768 | 768 | 0 |
| 36 | 768 | 768 | 768 | 0 |
| 78 | 384 | 384 | 384 | 0 |
| 80 | 550 | 564 | 564 | +14 |
| 81 | 288 | 288 | 288 | 0 |
| 83 | 288 | 288 | 288 | 0 |
| 89 | 1865 | 1907 | 1907 | +42 |
| 90 | 0 | 384 | 384 | +384 |
| 113 | 1134 | 1142 | 1142 | +8 |
| 134 | 567 | 568 | 568 | +1 |
| 15 | 0 | 384 | 384 | +384 |
| 470 | 0 | 384 | 384 | +384 |
| 601 | 0 | 288 | 288 | +288 |
| 643 | 384 | 384 | 384 | 0 |
| 129 | 0 | 0 | **924** | **+924** |
| **Total** | **6996** | **8501** | **9425** | **+2429** |

All 1944 tests pass.

**0 FP** on the 5 newly-unlocked families (15, 90, 470, 601, plus the
2 already-clean CWE-89/113/80/134 minor gains). CWE-129 has 23 FP from
the `81_goodG2B/goodB2G` pattern (97.5% precision).

## Per-CWE-601 fire distribution (9 families × 32 = 288)

Already documented in R328-CWE601-TRY-COMPLETION.md.

## Why no regression on other families

The fix is **additive**: it only adds edges. Existing natural successors
(including the wrong fall-through to catch label) are preserved. The
exception-flow edges are unchanged. The dataflow engine unions facts
from all paths, so adding the try-completion edge is a sound
over-approximation.

For families where the sink is INSIDE the try body (e.g. CWE-78 _22a
where `Runtime.exec` is in the try), the try-completion edge is
redundant (the worklist already visits the sink via the natural
fall-through). No double-counting, no new FP.

For families where the source is OUTSIDE the try and the sink is also
OUTSIDE the try (most CWE-78 _01 cases), the fix is irrelevant
(unaffected).

## R328's first-of-kind win

R328 is the first time Plumb fixed a **structural IR-level** issue
(unlike catalog additions or taint propagation refinements). The fix
demonstrates that:

- **The TAC list having a call ≠ dataflow reaching the call**
- **The CFG block graph is the source of truth for reachability**
- **Translator and CFG builder share responsibility for try/catch
  semantics** — translator emits the body+catch, CFG builder adds the
  try-completion edge

## Pitfall (cross-project)

- **"Sink doesn't fire" diagnosis order: CFG → catalog → propagation**
  - The TAC list having the call is necessary but not sufficient
  - Dump the CFG block graph and check each sink block has incoming
    edges
  - **Only after confirming reachability**, debug catalog patterns
  - **Only after confirming catalog match**, debug taint propagation
- **Don't trust the R327 "1-2h fix" estimate when the symptom is "0 fires
  for a known sink pattern"**. Zero fires for a known good pattern is
  almost always an IR/control-flow issue, not a catalog issue.
- **When a fix unlocks more CWE families than expected, re-measure ALL
  families, not just the one you targeted**. The R328 try-completion
  edge was designed for CWE-601 but actually unlocks 4 more families
  because they all share the same Juliet template pattern.
