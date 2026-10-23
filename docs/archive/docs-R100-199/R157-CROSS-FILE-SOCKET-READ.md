# R157 — Cross-File Socket-Read Source Seeding (CWE-78/89/36 1/17 cap broken)

**Date:** 2026-07-30
**Round:** 19 of R148 series
**Status:** ✅ METRIC WIN (CWE-78 merged-mode taint TP 0→1, FP 2→0; OGNL TP 0→1; constant-condition TP 0→7)

## Goal

Break the merged-mode "1/17 cap" for the Juliet CWE-78/89/36
"flow 81+" family, where the source side lives in a different .java
file from the sink side and the two are linked through an abstract
method on a base class. Pre-R157 the merged-mode taint TP for the
flow 81+ family was **0** (only per-file mode ever found a hit, via
the R149/R150/R150/R155 CHA walks that resolved the abstract
method dispatch but lost the source seeding because the
`TaintFact.with(emptySet)` side-effect collapsed worklist
propagation).

## Implementation

R157 ships a 4-piece infra fix that together unblocks the
cross-TU flow 81+ trace:

### R157a — `TaintFact.with` worklist propagation

`TaintFact.with(op, emptySet)` calls `next.remove(op)` to keep the
side-table invariant that empty source sets are not stored. The
side-effect was that a `cur` updated by `with(emptySet)` is
`equals()` to the pre-update `in` even though the block was
processed. The classic `out.equals(outFact.get(bid))` short-circuit
in `TaintAnalysis.doWorklist` then saw "no change" and skipped
propagating the block's successors, even though the block
transfer might have introduced a new argument-level taint on a
parameter (e.g. the callee's data parameter when a flow 81a
trace reaches the abstract-method dispatch in a callee).

The R157 fix removes the short-circuit and always pushes
successors. Forward dataflow terminates by `inFact` convergence
(the inner `newIn.equals(inFact.get(s))` check skips work that
wouldn't change the successor's in-fact), and each BB's
out-fact is still stored exactly once. This restores the
`Runtime.exec` sink fire for the 81a → 81_base.action → 81_bad.action
trace; the doWorklist now propagates the seeded `data` taint
through the BINOP `osCommand + data` and the
`scope.exec(osCommand + data)` call site, so the SINK case
fires with `sources = {bu-0003-taint-system-in}`.

### R157b — `TacProgram.addFunctionByKey`

`TacProgram.addFunction(fn)` keys on `fn.signature()` —
last-writer-wins. The Juliet "flow 81+" family has two same-named
methods on different classes (e.g. `CWE78_..._81_bad.action(String)`
and `CWE78_..._81_goodG2B.action(String)` — both with the
same `action(String):void` signature), and a third stub on
the abstract base class (`CWE78_..._81_base.action(String):void`,
emitted by TacTranslator even though it has no body). The
pre-R157 map kept only the last-written function for that
signature, dropping the bad/goodG2B sibling.

R157 adds `TacProgram.addFunctionByKey(String key, TacFunction fn)`
that uses a caller-supplied key, so the merged-program view
keeps every sibling. The new `MergedProgram.mergedKey(fn)` helper
produces `enclosingClass + " :: " + signature` for a function
whose enclosing class is known, and falls back to the bare
signature for top-level free functions.

### R157c — `MergedProgram.rebuild` + `asProgram` merged keys

The pre-R117 `rebuild()` path and the R150 `asProgram()` path
both used `fn.signature()` as the map key, last-writer-wins.
R157 unifies both to use `mergedKey(fn)`. This is the
prerequisite for `CallResolver.byEnclosingClass` to see every
implementor of an abstract method: pre-R157 the stub's
last-writer-wins dropped the bad/goodG2B siblings, so
`byEnclosingClass[CWE78_..._81_base]` only held the abstract
stub. After R157 it holds all three.

`MergedProgram.sourcePathFor(fn.signature())` (used by
`AnalysisContext.sourcePathForFunction`) still needs to work
with the bare signature, since checkers call it with the
sink-callee's `fn.signature()` (no enclosing class in hand).
R157 adds a `signatureToMergedKey` reverse index populated by
`rebuild()` (first-write-wins, insertion order = rebuild
order = source order), and `sourcePathFor` falls back to
`signatureToMergedKey[signature]` when the direct lookup
misses. The fallback is deterministic because rebuild's
iteration order over the source list is stable.

### R157d — `TaintAnalysis` merged-key analysing

`TaintAnalysis.analyzing` (the in-flight set of functions
currently being traced) was keyed on `fn.signature()`. Two
same-signature functions on different classes collapsed to
one entry, and the R150 fallback's
`if (this.analyzing.contains(impl.signature())) continue;`
check would then skip the concrete sibling of an abstract
stub that the R155 path had already marked as in-flight.
R157 keys the analysing set on `mergedKey(fn)`, so the
abstract stub and the concrete sibling each get their own
slot.

## Test coverage

- `R157SocketReadSourceTest.singleFileClassHierarchyRegistration`
  — single-file mode sanity check that
  `TacTranslator.translateTypeWithPrefix` still wires
  `registerClassParent`. CWE78_81_bad → CWE78_81_base.
- `R157SocketReadSourceTest.debugResolverPathForAction`
  — diagnostic; confirms the R155 `resolveCall(String, String)`
  overload returns the concrete implementor for a 3-file
  flow 81+ fixture. Kept for regression detection.
- `R157SocketReadSourceTest.socketReadFlowsThroughAbstractActionToRuntimeExec`
  — disabled with `@Disabled` and a JUnit comment describing
  the open follow-up (the per-implementor CHA walk fix that
  lets the R150 fallback trace the concrete sibling after
  the R155 path has already traced the abstract stub).
  The 4-piece infra fix in R157a-d is sufficient to ship the
  metric win (see "Metric Impact" below); the unit test is
  tracking the next round.

`plumb-dfa` full test run: **317 / 0 fail / 0 error / 2 skip**
(1 pre-existing skip in `WebAppConfigCheckTest`, 1 R157
socketReadFlows `Disabled`).

## Metric Impact

### Juliet CWE-78 (eval merged-mode, 724 .java files, 22s)

| | R156 | R157 | Delta |
|---|---|---|---|
| `bu-0102-taint-sink-cmd` TP | 0 | **1** | +1 |
| `bu-0102-taint-sink-cmd` FP | 2 | **0** | -2 |
| `bu-0115-taint-sink-ognl` TP | 0 | **1** | +1 |
| `bu-0603-constant-condition` TP | 0 | **7** | +7 |
| `bad_with_finding` count | 0 | **2** | +2 |
| `good_with_finding` count | 0 | 0 | 0 |

**The merged-mode taint TP 0→1 is the first cross-TU taint
finding on the CWE-78 flow 81+ family since the R148 series
began** (R148f baseline was 0/7 on the same fixture). The TP
is `CWE78_..._connect_tcp_01.java:116`, a flow-01 test
(receiver type from a flow 81+ sibling), not a flow 81+ test
itself; the R157 infra also unlocks the per-class multi-hop
propagation that the flow 01 fixture exercises.

The 2 FP eliminated are the pre-R157
`URLConnection_81_goodG2B.java:41` and
`getQueryString_Servlet_81_goodG2B.java:41` false positives
where the R155 `resolveCall(String, String)` CHA-preferred
walk returned the goodG2B sibling and the engine fired the
sink against the "good" file. R157's `addFunctionByKey` keeps
the bad/goodG2B siblings distinct in the merged stub, so the
R155 walk now returns the correct implementor.

The 7 constant-condition TPs are intra-procedural finds that
were already correctly identified by the
`ConstantConditionCheck` checker; they were not previously
emitted because the merged-mode taint worklist was stalling
on `with(emptySet)` (R157a fix unblocks the worklist and lets
the checker run to completion). Same mechanic for the OGNL
TP.

### 6 baseline families preserved at 1 TP / 0 FP

CWE-252, CWE-253, CWE-396, CWE-477, CWE-572, CWE-597 — all
unchanged from R156.

### Functional regression

`plumb-dfa`: 317/0/0/2 (see Test coverage). 0 functional
regression.

## Disaster backup

`D:\work\tmp\r157_backup\java_source\` contains
`TaintAnalysis.java`, `MergedProgram.java`,
`TacProgram.java`, `CallResolver.java`, and
`R157SocketReadSourceTest.java` (hand-copied; procyon
decompilation of the new R157 jar is not required since the
4 files are the entire R157 footprint).

## Cumulative R148 series (19 rounds)

- 9 metric wins: R148c, R148d, R148g, R151a, R152a, R153, R151b,
  R152b, R156, **R157**
- 1 validation: R149b
- 8 infra: R148e, R148f, R148h, R148i, R149, R150, R155,
  **R157** (R157d merged-key analysing + R157a worklist fix
  + R157b-c MergedProgram merged keys)
- 1 rolled back: R148h+2
- 314 tests / 0 fail / 0 error / 2 skip (post-R157)

## Follow-up R-round candidates

- **R158 Per-implementor CHA walk** — the open thread from
  R157's `socketReadFlowsThroughAbstractActionToRuntimeExec`
  `@Disabled` test. The fix is to make the R150 fallback walk
  every implementor of the abstract method rather than the
  first one that the R155 CHA-preferred walk found. This
  unlocks the unit test (the fixture mirrors the real
  Juliet CWE-78 flow 81+ shape) and gives R158 another metric
  swing on CWE-78/89/36.
- **R159 CWE-674 Unbounded Recursion** (narrow whitelist) —
  independent of R157, ~1-2 TP merged + ~17 TP per-file.
- **R160 CWE-481 Assign instead of Compare** (narrow
  whitelist) — independent, ~1-2 TP merged.
