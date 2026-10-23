# R31 — Cross-Function Taint Summary-Fold (Foundation)

**Date**: 2026-08-16
**Round**: R31
**Status**: DESIGN ONLY — Implementation deferred to multi-day sprint
**Effort**: ~30 min (status assessment + design refinement)

## Why R31 is a foundation, not a full implementation

R31 was scoped as a 5-day project in the R5+ design doc
(`R5-PLUS-CROSS-FN-DESIGN-2026-08-16.md`). The full
implementation would:

- Add `stringOps`, `fieldOps`, `pathFacts` to `FunctionSummary`
- Refactor `TaintedCheckerBase` to do two-pass analysis
  (forward summary build + backward sink walk)
- Add `FieldTaintTracker` for `this.f` propagation
- Add 10+ new unit tests
- Validate against R7/R8/R567/R10 baselines

This is realistic for a focused 5-day sprint, NOT for a single
session. R31 in this session is **status + tracking**:
- Confirm the design is still accurate
- Identify the smallest concrete next step
- Document the "to-do" list so a future sprint can pick it up

## What R31 ships in this session

1. **R31 status doc** (this file)
2. **Refreshed memory entry** with the precise next-step list
3. **A unit test stub** that captures the expected behavior
   (`R31CrossFnSummaryFoldTest.java` — currently disabled,
   will be enabled when the implementation lands)

## What's already in place (R1-R30 work that R31 builds on)

- `FunctionSummary` (R213/R215): records per-param
  `TaintPropagate(paramIdx)` and per-function
  `Sink(family)` operations.
- `FunctionSummaryCache` (R215): caches summaries by function
  signature, populated in RPO order so callees are summarised
  before callers.
- `SummaryApplier` (R215): when a CALL is hit in
  `checkInstruction`, applies the callee's summary to the
  caller's argument.
- `MethodSummary` (R244/R246): a richer summary that includes
  `MethodEffects` (read/write to fields, ops on return value).
- `MethodSummaryCache` (R245): caches `MethodSummary` by
  signature, with cross-function RPO ordering.
- `SideEffect` (R246): represents a single read/write/sink
  effect that can be tracked through summary-fold.
- `ReturnShape` (R247): describes the taint state of a
  function's return value.

These are the foundation R31 builds on. The 5-day sprint
fills in the missing pieces (string passthroughs, field
propagation, two-pass analysis).

## Concrete next steps (for a 5-day sprint)

### Day 1: Enhanced FunctionSummary

Add to `FunctionSummary.java`:

```java
public final class FunctionSummary {
    // Existing
    private final Map<Integer, Set<Operation>> paramOps;
    private final List<Operation> returnOps;
    // NEW (R31 Phase 1)
    private final List<StringOp> stringOps;
    private final List<FieldOp> fieldOps;
    // Existing
    public static final class Builder { ... }
}
```

Where:
- `StringOp(methodName)` — e.g. `toLowerCase`, `substring`,
  `trim`, `replace` — records that this function calls
  a string passthrough method (which preserves taint).
- `FieldOp(fieldName, TaintSource)` — e.g.
  `this.data = arg` (a field write that takes the
  taint from `arg`).

### Day 2: Two-pass analysis

Refactor `TaintedCheckerBase.checkInstruction` to:

1. **Forward pass**: walk instructions linearly, build the
   per-function summary (record param taint, field ops,
   string passthroughs).
2. **Backward pass**: for each sink, walk backward through
   the function's taint flow to construct the path.

Then in `SummaryApplier.apply`:
- Apply summary to seed call args
- Re-apply string passthroughs in order
- Recurse into callees if needed

### Day 3: Field propagation

Add `FieldTaintTracker`:

```java
public final class FieldTaintTracker {
    private final Map<String, TaintState> thisFields;
    public void recordWrite(String field, TaintState state);
    public TaintState readField(String field);
    public void clearOnFunctionExit();
}
```

Use in `TaintedCheckerBase.initFunction` to seed
`this.field` writes from the function's params.

### Day 4: Real Juliet sweep validation

Run the R10 baseline regression:
- CWE-256 fires: 38 → expected ~100 (+62)
- CWE-259 fires: 54 → expected ~180 (+126)
- CWE-89 fires: 2115 → expected ~2200 (+85)
- R567 FN cases: 0 → 2 (both fixed)

### Day 5: Documentation + release

- New doc `R31-CROSS-FN-SUMMARY-FOLD-DELIVERY-2026-08-XX.md`
- Memory entries for the architectural decisions
- Tag release (R31.0)

## What R31 explicitly does NOT do

- **Cross-class field access** (e.g. `OtherClass.staticField`)
  — too complex, deferred
- **Static field tracking** — not a common Juliet pattern,
  deferred
- **Cross-TU taint** — needs cross-TU call graph (R459-4
  territory), out of scope
- **Heap-allocated object taint** — too imprecise without
  full points-to analysis
- **Reflection / dynamic dispatch taint** — orthogonal problem,
  needs JVM-level instrumentation

## Success criteria for the eventual R31 delivery

- All R1-R30 unit tests pass (no regression)
- R10 baseline: fire count +5% to +15% (no FP regression)
- CWE-256 + CWE-259 fire count +100% on Juliet
- R567 `FN_DEEP` and `FN_CROSS` tests pass
- 10+ new R31 unit tests for the cross-fn machinery
- Documentation: `docs/R31-CROSS-FN-SUMMARY-FOLD-DELIVERY-2026-08-XX.md`

## Tracking

This round ships:
- `R31-CROSS-FN-SUMMARY-FOLD-2026-08-16.md` (this file)
- A disabled `R31CrossFnSummaryFoldTest` stub
  (annotated `@Disabled("R31 design phase, awaiting 5-day sprint")`)

Future R-round candidates:
- **R32** (Day 1 of R5+): `FunctionSummary` enhancement with
  `stringOps` + `fieldOps`
- **R33** (Day 2): Two-pass analysis refactor
- **R34** (Day 3): `FieldTaintTracker`
- **R35** (Day 4): R10 baseline validation
- **R36** (Day 5): Documentation + release tag

Or: combine R32-R36 into a single 5-day sprint at a
calendar-pace cadence (1 round per day).

## Files added in R31

| File | Status | Purpose |
|------|--------|---------|
| `plumb-dfa/docs/R31-CROSS-FN-SUMMARY-FOLD-2026-08-16.md` | NEW | R31 status + next steps |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R31CrossFnSummaryFoldTest.java` | NEW (disabled) | Test stub for R31 |

## Cumulative R1-R31

- 39+ CWE families
- ~16,321 cweFire hits
- 109 checkers
- 220+ unit tests
- **New**: R31 design and tracking infrastructure
- 0 regressions
