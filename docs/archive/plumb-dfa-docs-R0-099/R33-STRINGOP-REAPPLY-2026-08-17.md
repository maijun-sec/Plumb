# R33 — StringOp Re-Application in Cross-Function Summary Fold

**Date**: 2026-08-17
**Round**: R33 (Day 2 of the R5+ 5-day cross-fn sprint)
**Status**: SHIPPED — 11 new unit tests, 0 regressions
**Effort**: ~1.5 hours (code change + 2 helpers + 11 unit tests)

## Why R33

R32 shipped the `stringOps` data structure —
a `FunctionSummary` extension that records
the sequence of taint-passthrough string
methods (e.g. `toLowerCase`, `trim`,
`substring`) a function applies to its
param. R32 stops at recording; R33 ships
the **re-application** at the call site.

When a caller does
`dest = helper(tainted_arg)` and the
callee's `FunctionSummary` has a
non-empty `stringOps` list, R33:

1. **Enriches the dest's taint label** with
   the passthrough chain. The label
   becomes
   `"callee-ret:<sig>[<m1>,<m2>,...]"`.
2. **Appends one `passthrough-summary-fold`
   `FlowStep` per `StringOp`** to the
   dest's taint path, in order. The
   `expr` field is the bare
   `"callee.<method>()"` form so reporters
   can render the chain directly.

This is the "second half" of the R5+
two-pass analysis design: R32's forward
pass (inside the callee) records the
passthroughs; R33's backward pass (in the
caller) re-applies them on the dest's
taint path so the structural info
survives summary fold.

## What R33 ships

### 1. `propagateTaintFromCalleeSummary` enrichment

The existing method already marks the
dest as tainted via `TaintPropagate` on
`returnOps` and writes a
`summary-fold` `FlowStep` to the dest's
taint path. R33 extends the dest-tainting
branch:

- The dest's taint label is now
  `enrichTaintLabelWithPassthroughs("callee-ret:<sig>", calleeSummary)`.
- The dest's taint path gets
  `appendPassthroughStepsForSummary(fold, calleeSummary, ins)`
  appended, which writes one
  `passthrough-summary-fold` step per
  `StringOp` in the callee's ordered
  `stringOps` list.

The call arg tainting branch (param-level
`TaintPropagate` → mark caller's arg as
tainted) is unchanged. R33 only enriches
the **dest's** path; the caller's arg
already has its own taint state from the
param-level fold.

### 2. Two new static helpers

```java
static String enrichTaintLabelWithPassthroughs(
        String baseLabel, FunctionSummary calleeSummary)

static void appendPassthroughStepsForSummary(
        List<FlowStep> fold,
        FunctionSummary calleeSummary,
        TacInstruction ins)
```

- `enrichTaintLabelWithPassthroughs` —
  returns the base label as-is when the
  callee has no `stringOps`, or appends
  `[m1,m2,...]` when it does. The
  bracketed chain is purely a
  human-readable marker; the structural
  information is also available in the
  FlowSteps.
- `appendPassthroughStepsForSummary` —
  appends one `FlowStep(role="passthrough-summary-fold")`
  per `StringOp`, with the call site's
  line / column. Empty `stringOps` is a
  no-op; `null` summary is a no-op.

### 3. Test accessors

Added two convenience accessors to
`TaintedCheckerBase` for tests:

- `public String taintedLabelFor(TacOperand.TacVar v)`
- `public List<FlowStep> taintedPathFor(TacOperand.TacVar v)`

Both return `null` when the var is not
currently tainted. The list returned by
`taintedPathFor` is the unmodifiable view
stored in `taintPaths`.

## R33 vs the R5+ design doc

The R5+ design doc's Phase 2 ("Two-pass
analysis") is broader than what R33
ships. The doc describes both:
- Enriching the source-to-sink path
  (this is what R33 ships)
- Adding new taint flow through
  cross-function helpers (this is what
  R34 ships via `FieldTaintTracker`)

R33 is the **path-enrichment** half. R34
will be the **new-taint-flow** half. The
R5+ doc's expected impact of
+62 CWE-256 / +126 CWE-259 is the
cumulative R32+R33+R34 impact, not R33
alone. R33's actual cweFire count
contribution is expected to be small
(some Juliet files that already fire
will get richer path info, not new
fires); the R34 / R35 rounds will
deliver the fire-count increase.

## Test coverage (11 new tests, 0 regressions)

### `R33StringOpReapplyTest` (11 tests)

- `destLabelIsEnrichedWithPassthroughChain`:
  callee with 2 StringOps → dest label is
  `callee-ret:Helper#lowerAndTrim[toLowerCase,trim]`.
- `destTaintPathIncludesPassthroughFlowStepsInOrder`:
  callee with 3 StringOps → dest path
  has 1 `summary-fold` + 3
  `passthrough-summary-fold` steps in
  order, with the call site's line
  recorded on each.
- `destLabelUnchangedWhenCalleeHasNoStringOps`:
  callee has `TaintPropagate` on
  returnOps but no `stringOps` → label
  is the base `callee-ret:<sig>` form,
  no extra FlowSteps added.
- `destNotTaintedWhenCalleeHasNoReturnOpsTaintPropagate`:
  callee has `stringOps` but no
  `TaintPropagate` on returnOps → dest
  is NOT tainted, no path changes.
- `singleStringOpEnrichesLabelWithOneElement`:
  single-element chain → label has
  `[valueOf]` (no trailing comma).
- Static-helper isolation tests (5):
  `enrichTaintLabelWithPassthroughs` /
  `appendPassthroughStepsForSummary` with
  empty / null / multi-element inputs.

## Regression check

Ran `org.plumb.dfa.unified.summary.*Test`
+ `org.plumb.dfa.unified.taint.*Test`
(206 tests, 2 skipped, 4 pre-existing
R503 failures):

- **All R32 + R33 new tests pass** (11 +
  30 + 15 = 56 new tests).
- **0 new regressions** introduced by R33.
- The 4 pre-existing failures in
  `R218aZ3BridgeWireTest` (3) and
  `TaintedCheckerBaseTest` (1) are
  documented in
  `_trash/R503-PRE-EXISTING-TEST-FAILURES.md`
  and **NOT caused by R33**.

## Cross-cutting design decisions

1. **Path enrichment only — no new taint
   flow.** R33 enriches the dest's taint
   path and label with the callee's
   structural passthrough info. It does
   NOT change which vars are tainted. The
   cweFire count is expected to be
   unchanged by R33 alone; the R34
   `FieldTaintTracker` is the round that
   adds new taint flow (via `this.f =
   param` cross-method propagation).
2. **Only the dest is enriched, not the
   call args.** The call args already have
   their own taint state from the
   param-level summary fold (existing
   `propagateTaintFromCalleeSummary`
   behavior). Enriching the call args
   would double-write and is not needed
   for the R33 goal (path display).
3. **Static helpers, package-private.**
   `enrichTaintLabelWithPassthroughs` and
   `appendPassthroughStepsForSummary` are
   static and package-private so R34 /
   other consumers can call them without
   going through a `TaintedCheckerBase`
   instance. Tests in the same package
   can call them directly.
4. **Bare method name in FlowStep expr.**
   The `expr` field of each
   `passthrough-summary-fold` step is
   `callee.<method>()` (e.g.
   `callee.toLowerCase()`). This matches
   the convention used by other
   `propagate` / `summary-fold` steps
   (the `expr` is human-readable, not
   FQN). Reporters can render it directly
   without further FQN-stripping.
5. **Empty `stringOps` is a no-op.**
   The `appendPassthroughStepsForSummary`
   helper checks for empty list and
   returns immediately. This keeps the
   dest's path identical to the pre-R33
   form for the (very common) case where
   the callee has no structural
   passthroughs.

## Out of scope (deferred to R34)

- **New taint flow via `this.f =
  param`** — R34's `FieldTaintTracker`
  will add the missing taint flow that
  the R5+ design doc's +62 / +126 fire
  estimates assume. R33 alone does NOT
  fix the R567 FN_DEEP / FN_CROSS test
  cases; those need R34.
- **Backward pass from sinks** — the
  R5+ design doc's "backward pass" was
  for path construction; R33 doesn't
  add a separate backward pass because
  the existing `taintPaths` map already
  records the full path during the
  forward walk. R33 just enriches one
  segment of the path (the
  summary-fold segment) with the
  callee's structural info.
- **Per-param `StringOp` knowledge** —
  R32's `StringOp` record only has the
  method name, not the param index.
  R33 doesn't need per-param knowledge
  because it only enriches the dest's
  path (which is the return value, not a
  param). R34's `FieldTaintTracker` may
  need per-param `FieldOp` knowledge
  (already supported via
  `FieldOp.sourceParamIndex`); the
  `StringOp` extension can be added
  later if R34 needs it.

## Files added in R33

| File | Status | Purpose |
|------|--------|---------|
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/R33StringOpReapplyTest.java` | NEW | 11 unit tests for path / label enrichment (17703 bytes) |
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/TaintedCheckerBase.java` | MODIFIED | `propagateTaintFromCalleeSummary` enriched dest branch; 2 new static helpers; 2 new test accessors |
| `plumb-dfa/docs/R33-STRINGOP-REAPPLY-2026-08-17.md` | NEW | This doc |

## Cumulative R1-R33 (Plumb only)

- 39+ CWE families (no new CWE families in
  R33 — this is path enrichment)
- ~16,321 cweFire hits (unchanged by R33
  alone; R34 / R35 will deliver the
  +62/+126 from the R5+ design doc)
- 109 checkers (unchanged)
- **265 → 276 unit tests (+11 from R33)**
- 0 regressions

## Next steps (per the R5+ 5-day sprint)

- **R34 (Day 3)** — `FieldTaintTracker`.
  New class that walks the `fieldOps`
  list at the call site and seeds
  `this.f` with taint from the matching
  caller arg. This is the round that
  actually adds new taint flow (fixes
  the 2 R567 FN cases).
- **R35 (Day 4)** — R10 baseline
  validation. Expected: CWE-256 38→100,
  CWE-259 54→180, CWE-89 +85, 2 R567
  FN cases fixed (cumulative R32+R33+R34).
- **R36 (Day 5)** — Documentation +
  R31.0 release tag.
