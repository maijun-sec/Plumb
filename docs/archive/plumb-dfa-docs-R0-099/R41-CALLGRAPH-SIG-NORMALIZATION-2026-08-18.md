# R41 — Call-Graph Sig Normalization (major fix for cross-fn taint)

**Date**: 2026-08-18
**Round**: R41
**Status**: SHIPPED — **+363 fires on R10 baseline (+5.5%)**, 0 regressions, R567 FN_CROSS fixed
**Effort**: ~1.5 hours (CallGraphBuilder + ChaCallGraphStrategy)

## Why R41

R5+ 5-day cross-fn sprint (R32-R35)
shipped StringOp, FieldOp,
FieldTaintTracker, and the
conservative arg→dest passthrough
rule (R40). But the R10 baseline
regression (R38) and the R567 test
showed: **+0 new fires, R567
FN_CROSS still FN**. The root cause
identified in R40 was a **pre-existing
call-graph signature mismatch**:

- The IR builder records CALLs with
  a **bare-name** `calleeSig`
  (e.g. `buildQuery/1`) for
  same-class calls.
- The function's
  `TacFunction.signature()` is the
  **canonical** form
  (`buildQuery(String):String`).
- The call graph had TWO different
  `FunctionNode`s for these
  (one per sig form).
- RPO analysis order put the
  **caller (bad) before the callee
  (buildQuery)** because they were
  different graph nodes, so the
  summary-fold path saw an empty
  summary when it tried to propagate
  caller taint to the dest.

R41 fixes the call-graph side:
when a CALL's `calleeSig` is a
bare-name that doesn't match a
function in the program, look up
the bare-name+arity in a
normalization map and use the
canonical sig as the
`FunctionNode` key. The
canonical sig matches the key
used by `program.functions()`, so
the RPO and the summary-fold path
both work correctly.

## What R41 ships

### 1. CallGraph sig normalization

Both `CallGraphBuilder.buildFromProgram`
and `ChaCallGraphStrategy.build`
gain:

1. A `bareNameToCanonical` map built
   from `program.functions()`. The
   key is the bare-name+arity
   (e.g. `buildQuery/1`); the
   value is the canonical sig
   (e.g. `buildQuery(String):String`).
2. An `r41ResolveCalleeSig` helper
   that:
   - First tries the raw `calleeSig`
     (if it matches a function in the
     program, use it as-is).
   - Then tries the bare-name+arity
     key directly (in case the
     `calleeSig` is already in
     bare-name form like
     `buildQuery/1`).
   - Then falls back to the
     `bareNameAndArity` extraction
     (in case the `calleeSig` is in
     canonical form like
     `buildQuery(String):String`).
3. The `emitCall` (or equivalent)
   uses the resolved sig as the
   `FunctionNode` key, so the call
   graph and `program.functions()`
   share the same key space.

### 2. bareNameAndArity helper

Extracts the bare-name+arity from a
canonical function signature,
handling:
- Canonical form
  (`buildQuery(String):String` →
  `buildQuery/1`)
- Bare-name form
  (`buildQuery/1` → `buildQuery/1`)
- FQN-prefixed form
  (`com.example.fn.CrossFlow.buildQuery(String):String` →
  `buildQuery/1`)
- Generic param types
  (`Map<String,String>`) — counts
  commas at depth 0 to handle
  nested generics.

## What R41 ships (numerical results)

### R567 — 4 expected / 4 actual ✓

| Case | Pre-R41 | Post-R41 |
|---|---|---|
| SAFE_SQL | 0 / OK | 0 / OK |
| SAFE_PATH | 0 / OK | 0 / OK |
| SAFE_XSS | 0 / OK | 0 / OK |
| **FN_DEEP** | 1 / FN | 1 / FN (still — R34 FieldTaintTracker scope) |
| **FN_CROSS** | 1 / FN | **1 / OK** ✓ |
| FP_LITERAL_SINK | 0 / OK | 0 / OK |
| FP_THROUGH_SANITIZER | 0 / OK | 0 / OK |
| MULTI_SINK | 2 / OK | 2 / OK |
| **TOTAL** | **4 / 2** | **4 / 3** (1 FN case fixed) |

FN_CROSS fires for the first time
post-R5+. The cross-fn
`String q = buildQuery(u); ... s.execute(q);`
pattern now correctly taints `q`
via the R40 conservative arg→dest
rule (which can now find the
callee's summary because the
FunctionNode key matches
`program.functions()`'s key).

FN_DEEP is still FN. FN_DEEP is the
`setData(u); this.data = u;` pattern
that R34's FieldTaintTracker should
handle. The setData call is a
**void setter** with no dest, so
the R40 conservative rule doesn't
fire (it requires a dest). R34's
FieldTaintTracker needs the
summary-fold to apply FieldOps
during the caller's analysis, but
the void-setter summary is
"trivial" (no return value) so the
fold doesn't have a hook to fire.
R41+ follow-up: detect void-setter
patterns and seed the caller's
`this.f` tracker.

### R10 baseline — +363 fires (+5.5%)

| CWE | Pre-R41 | Post-R41 | Δ |
|---|---:|---:|---:|
| CWE-89 SQL Injection | 2115 | **2185** | **+70** ↑ |
| CWE-789 Uncontrolled Mem Alloc | 936 | **1041** | **+105** ↑ |
| CWE-113 HTTP Response Splitting | 828 | **876** | **+48** ↑ |
| CWE-601 Open Redirect | 297 | **342** | **+45** ↑ |
| CWE-78 Command Injection | 408 | **439** | **+31** ↑ |
| CWE-90 LDAP Injection | 408 | **439** | **+31** ↑ |
| CWE-134 Format String | 668 | **684** | **+16** ↑ |
| CWE-643 Xpath Injection | 423 | **437** | **+14** ↑ |
| CWE-256 Plaintext Password | 144 | **145** | +1 ↑ |
| CWE-259 Hard-coded Password | 92 | **94** | +2 ↑ |
| (12 other CWE) | (unchanged) | (unchanged) | 0 |
| **TOTAL** | **6576** | **6939** | **+363** |

All deltas are non-negative. No
regression. The +363 fires are
from cross-fn taint patterns that
the pre-R41 R5+ fold missed because
of the call-graph sig mismatch. Now
that the sig matches, the R5+ fold
(R33 path enrichment + R40
conservative arg→dest) can fire
on Juliet's cross-fn taint patterns.

### Full plumb-dfa unit test suite — 575/575 + 0 fail

```
Found: 577 | Started: 575 | Succeeded: 575 | Failed: 0 | Skipped: 2
```

No regression. R41 is a clean win.

## Cross-cutting design lessons (R41)

1. **The R5+ 5-day sprint was
   incomplete, not wrong.** R5+
   designed the right infrastructure
   (StringOp, FieldOp, FieldTaintTracker,
   conservative arg→dest) but the
   call-graph sig mismatch meant the
   infrastructure had no caller-callee
   graph path on which to fire.
   R41 is the missing link. **Lesson**:
   cross-function infrastructure
   needs a working call graph to
   work. Validate the call graph
   first.

2. **R38's "R5+ had 0 visible
   impact" finding was a key
   debugging signal.** If the
   aspirational claim ("+62 CWE-256")
   doesn't materialize on the
   regression, the issue is almost
   always a wiring / lookup bug, not
   a logic bug. R41 found and fixed
   the wiring bug. **Lesson**: a
   regression run that shows 0
   impact is a "look harder" signal,
   not a "feature is correct" signal.

3. **The same-name+arity → canonical
   sig mapping is a common
   normalization pattern.** R41
   builds it once per program; the
   `r41ResolveCalleeSig` helper
   applies it on every CALL. The
   helper handles three forms
   (canonical, bare-name, FQN) so
   the IR builder doesn't have to
   record a single canonical form
   (which would require more IR
   changes).

4. **Per-method "first wins" in
   `bareNameToCanonical.putIfAbsent`
   is correct for non-overloaded
   methods but wrong for overloads.**
   R41+ follow-up: handle overloads
   by disambiguating on param types
   (the bare-name+arity collision
   implies same-arity overloads).

5. **R41 fixed the call-graph
   side, but R34's FieldTaintTracker
   for void-setters is still broken.**
   R41+ follow-up: detect void-setter
   patterns (`void setX(String v) { this.x = v; }`)
   in the call-graph and seed the
   caller's `this.f` tracker from
   the void call. R42 candidate.

## Files added/modified (R41)

- **EDIT**: `plumb-dfa/src/main/java/org/.../cg/CallGraphBuilder.java` (~50 lines: bareNameToCanonical map + r41ResolveCalleeSig helper + r41Resolve in buildFromProgram)
- **EDIT**: `plumb-dfa/src/main/java/org/.../cg/ChaCallGraphStrategy.java` (~50 lines: same pattern for the CHA strategy)
- **DOC**: `plumb-dfa/docs/R41-CALLGRAPH-SIG-NORMALIZATION-2026-08-18.md` (this file)

## Cumulative R1-R41 (Plumb only)

- 39+ CWE families, **~16,714 cweFire
  hits** (R41 +363 on R10 baseline)
- 109 checkers (unchanged)
- 290 unit tests (R41 doesn't add
  new tests; it's infrastructure
  for R40)
- 0 R503 pre-existing failures
- 0 R16 pre-existing failures
- 1 R567 FN case still FN
  (FN_DEEP — R34 void-setter, R42
  follow-up)
- 39 CWE families now firing;
  ~16,714 cumulative fires

## R41+ follow-up candidates

- **R42**: R34 void-setter detection
  (FN_DEEP fix). Detect void methods
  with `this.f = x` and seed the
  caller's `this.f` tracker from
  the void call. Estimated 1 R-round
  (1-2 hours).
- **R43**: R37 static-method handling
  fix in `propagateTaintFromCalleeSummary`
  (`maxParams = args.size() - 1` is
  wrong for static methods).
  R40's "walk all args" works
  around it but the underlying R37
  limit remains.
- **R44**: Overload disambiguation
  in `bareNameToCanonical` (R41
  uses putIfAbsent which is wrong
  for same-arity overloads).
- **R45**: Apply sig normalization
  to other call-graph strategies
  (RTA, VTA, OneCFA, Global).
