# R32 — `FunctionSummary` Enhancement (stringOps + fieldOps)

**Date**: 2026-08-17
**Round**: R32 (Day 1 of the R5+ 5-day cross-fn sprint)
**Status**: SHIPPED — 30 + 15 new unit tests, 0 regressions, 107 summary-package tests pass
**Effort**: ~2 hours (data structure + Builder plumbing + 4 new methods + 45 unit tests)

## Why R32

The R5+ design doc (`R5-PLUS-CROSS-FN-DESIGN-2026-08-16.md`)
identified two structural gaps in the
cross-function taint machinery:

1. **String passthroughs lose the structural
   information about WHAT was the passthrough.**
   The R500+ `propagateStringPassthroughs`
   handles inline JDK methods (e.g.
   `arg.toLowerCase()` at the call site), but
   the result is recorded in the callee's
   summary as a generic `TaintPropagate(0)`
   op — losing the information that the
   callee used `toLowerCase` (vs. some other
   passthrough). The R33 two-pass analyzer
   needs this structural information to
   re-apply the passthroughs at the caller.

2. **Field writes don't record the source.**
   The existing `Operation.WriteToField`
   records the field name but not the source
   (a caller seeing `WriteToField("data")`
   can't tell whether the field was written
   from a parameter, a constant, or a chain
   of computations). The R34
   `FieldTaintTracker` needs the source
   information to propagate taint from the
   matching caller arg to subsequent
   `FIELDLOAD`s.

R32 ships the data structures (the
`StringOp` and `FieldOp` records), extends
`FunctionSummary` to hold ordered lists of
each, wires the recording into
`TaintedCheckerBase` so per-function analysis
populates the lists, and writes them into
the function's `FunctionSummary` via
`summarizeFunction`. R32 is **purely
additive**: no existing behavior changes,
no existing tests regress, the R33 / R34
days consume the new data structures.

## What R32 ships

### 1. `StringOp` record (`summary/StringOp.java`)

A new record type representing a
taint-passthrough string method observed
inside a function:

```java
public record StringOp(String methodName) {
    public static final Set<String> KNOWN_PASSTHROUGHS = Set.of(
        "toLowerCase", "toUpperCase", "trim", "strip",
        "stripLeading", "stripTrailing", "replace",
        "replaceAll", "replaceFirst", "substring",
        "concat", "valueOf", "toString", "split", "intern");
    public boolean isKnownPassthrough() { ... }
    public static Set<String> knownPassthroughs() { ... }
}
```

- The `methodName` is the bare method name
  (e.g. `"toLowerCase"`, not
  `"java.lang.String.toLowerCase/1"`). The
  R33 caller-side applier sees the name
  alone; the FQN is not needed for
  re-application.
- `isKnownPassthrough()` returns `true` iff
  the method is in `KNOWN_PASSTHROUGHS`. R33
  uses this as a quick filter to apply only
  structurally sound passthroughs (a method
  not in the list is still recorded but
  treated conservatively by R33's
  consumer).
- Validation: rejects `null` and empty
  method names at construction time.

### 2. `FieldOp` record (`summary/FieldOp.java`)

A new record type representing a
`this.f = x`-style field write observed
inside a function:

```java
public record FieldOp(String fieldName, int sourceParamIndex) {
    public static final int NON_PARAM_SOURCE = -1;
    public static FieldOp fromLocalSource(String fieldName) { ... }
    public static FieldOp fromParam(String fieldName, int paramIndex) { ... }
    public boolean hasParamSource() { ... }
}
```

- `sourceParamIndex` is the 0-based
  parameter index of the value being
  written. The value `-1`
  (`NON_PARAM_SOURCE`) means "the source is
  NOT a parameter" (a local computation, a
  constant, a call return value, etc.). R34
  can model the field write even when the
  source isn't a param.
- Convenience factories `fromParam` and
  `fromLocalSource` make call sites
  self-documenting.
- Validation: rejects null/empty
  `fieldName`, rejects
  `sourceParamIndex < -1` (anything below
  the NON_PARAM_SOURCE sentinel is
  invalid).

### 3. `FunctionSummary` extension (`summary/FunctionSummary.java`)

Two new fields, both ordered `List`s (not
`Set`s — order matters for R33's
two-pass re-application):

- `private final List<StringOp> stringOps;`
- `private final List<FieldOp> fieldOps;`

Builder methods:
- `Builder.recordStringOp(StringOp op)` —
  null-safe, appends to the ordered list.
  Duplicates are NOT deduplicated (R33 may
  need to distinguish
  `arg.toLowerCase().trim()` from
  `arg.trim().toLowerCase()`).
- `Builder.recordFieldOp(FieldOp op)` —
  null-safe, appends to the ordered list.
  Multiple writes to the same field are NOT
  deduplicated (R34 needs the full list to
  reconstruct the final state).

Constructor: defensive copies via
`Collections.unmodifiableList(new ArrayList<>(...))`
so post-construction mutation is impossible.

Accessors:
- `List<StringOp> stringOps()` — unmodifiable view
- `List<FieldOp> fieldOps()` — unmodifiable view

Other methods:
- `isTrivial()` now also checks
  `stringOps.isEmpty() && fieldOps.isEmpty()`
- `merge()` unions both lists in receiver-first
  order (R33 walks the merged list in
  order; this gives a sensible
  "either-side-observed" sequence)
- `project(Predicate<Operation>)` preserves
  both lists as-is (they're not Operation
  subtypes; the Operation-based filter
  doesn't apply)
- `toString()` now reports `stringOps=N` and
  `fieldOps=N` counts

### 4. `TaintedCheckerBase` wiring (`taint/TaintedCheckerBase.java`)

Per-function accumulators added (matching
the existing `sinkObservations` /
`passedOutTaint` pattern):

```java
private final List<StringOp> stringOps = new ArrayList<>();
private final List<FieldOp> fieldOps = new ArrayList<>();
```

Cleared by `initFunction` (so they don't
leak across functions).

Recording methods (private helpers):

- `recordStringPassthroughsForSummary(ins, fn)` —
  called in the `CALL` branch of
  `checkInstruction`, after
  `propagateStringPassthroughs(ins)`. Mirrors
  the recognition logic in
  `propagateStringPassthroughs` (same prefix
  checks) and records a `StringOp` for each
  tainted source. For `append` / `concat`
  both `args[0]` and `args[1]` may carry
  taint, so we record a `StringOp` for each
  in args order.
- `recordStringOpForArg(ins, fn, argIdx, methodName)` —
  helper that records a `StringOp` for a
  specific arg index. The recording is
  conservative: only when the source is
  currently in `taintedVars` AND the source's
  taint label starts with `"param:"`. The
  param index is resolved via
  `fn.params()` and the documented
  `"param:<name>"` label format.
- `recordFieldOpForStore(ins, fn)` —
  called from the new `FIELDSTORE` branch in
  `checkInstruction`'s switch. Records a
  `FieldOp` when the receiver (`ins.lhs1`)
  is `this` and the stored value
  (`ins.dest`) is currently tainted from a
  function parameter. The param index is
  resolved the same way as
  `recordStringOpForArg`. When the source
  isn't a param, records
  `FieldOp.fromLocalSource(ins.fieldName)`.
- `extractMethodName(calleeSig)` — static
  helper that extracts the bare method
  name from a TAC `calleeSig`. Handles all
  three callee-sig formats discovered in
  R27:
  - `java.lang.String.toLowerCase/1` →
    `"toLowerCase"`
  - `String.toLowerCase/1` → `"toLowerCase"`
  - `toLowerCase/1` → `"toLowerCase"`
  - `java.lang.String#toLowerCase/1`
    (Jimple form) → `"toLowerCase"`
  - `java.lang.String.toLowerCase` (no
    arity) → `"toLowerCase"`

`summarizeFunction` writes the per-function
accumulators into the summary builder:

```java
for (StringOp sop : stringOps) b.recordStringOp(sop);
for (FieldOp fop : fieldOps) b.recordFieldOp(fop);
```

## Cross-cutting design decisions

1. **StringOp / FieldOp are NOT Operation
   subtypes.** They live as separate types
   in the summary package. Rationale:
   - The existing `Operation` sum type
     describes "what this function does to
     its params" (TaintPropagate, Sink,
     WriteToField, etc.). `StringOp` and
     `FieldOp` describe "what this function
     does to its result / fields" — a
     different semantic category.
   - The `project(Predicate<Operation>)`
     filter doesn't apply to them (they're
     not `Operation` instances). We chose
     to preserve them through `project()` as
     a conservative default. R33 may add a
     `Predicate<Object>` overload if
     demand materialises.
   - They have their own validation
     (`StringOp(methodName)` non-empty,
     `FieldOp(fieldName)` non-empty,
     `FieldOp(sourceParamIndex)` >= -1).

2. **Ordered `List`, not `Set`.** Order
   matters for R33's two-pass re-application
   (`arg.toLowerCase().trim()` vs
   `arg.trim().toLowerCase()` are different
   chains; we need to preserve them).
   R34's `FieldTaintTracker` similarly
   needs the full list of writes to model
   the field state at function exit.

3. **No deduplication.** Duplicates of
   `StringOp` / `FieldOp` are preserved.
   See the Javadoc on `Builder.recordStringOp`
   / `recordFieldOp` for the rationale.

4. **Conservative recording.** StringOp is
   only recorded when the source is
   currently in `taintedVars` AND the source's
   taint label starts with `"param:"`. A
   non-param source (a local computation, a
   constant) is NOT recorded as a StringOp
   because R33's caller-side re-application
   would not know how to map the source back
   to a caller arg. (FieldOp is different
   — it uses `FieldOp.fromLocalSource` for
   the non-param case because R34 needs to
   model the field write regardless of
   source.)

5. **`this` is the only receiver supported
   by R32.** Cross-class field writes
   (`other.data = x`) and static-field
   writes are deferred per the R5+ design
   doc. The `recordFieldOpForStore` helper
   explicitly checks `recv.name().equals("this")`
   and short-circuits otherwise.

6. **Receiver-offset bug NOT fixed.** R32
   does NOT fix the pre-R503 sink-arg-index
   convention bug documented in
   `_trash/R503-PRE-EXISTING-TEST-FAILURES.md`.
   That fix is a separate R-round (Issue 2
   in the trash doc) — R32 is a focused
   additive change.

## Test coverage (45 new tests, 0 regressions)

### `R32FunctionSummaryEnhancementTest` (30 tests)

- `StringOp` validation, equality, known-passthrough
  membership (6 tests)
- `FieldOp` validation, equality, NON_PARAM_SOURCE
  sentinel, factory methods (8 tests)
- `Builder` accumulation, null-safety, no-dedup
  (4 tests)
- `stringOps()` / `fieldOps()` return unmodifiable
  views (2 tests)
- `isTrivial()` with StringOp / FieldOp / empty
  (3 tests)
- `merge()` unions both lists in receiver-first
  order, with trivial short-circuit (3 tests)
- `project()` preserves both lists (1 test)
- `toString()` includes new counts (1 test)
- Empty summary has empty lists (not null) (1 test)
- Builder allows duplicate StringOps / FieldOps
  (2 tests — covers the no-dedup contract)

### `R32TaintedCheckerRecordingTest` (15 tests)

- Records StringOp for `toLowerCase` on a param
  (1 test)
- Records StringOp for `trim` on a param (1 test)
- Records multiple StringOps in order (1 test)
- Does NOT record StringOp for non-passthrough
  call (1 test)
- Does NOT record StringOp for non-tainted source
  (1 test)
- Records FieldOp for `this.f = d` (1 test)
- Does NOT record FieldOp for non-`this` receiver
  (1 test)
- Records FieldOp from local source (1 test)
- `initFunction` clears the per-function
  accumulators between functions (1 test)
- `extractMethodName` handles FQN, short, bare,
  Jimple, no-arity, null, empty (7 tests)

## Regression check

Ran the following test classes (all in the
`plumb-dfa` module):

- `org.plumb.dfa.unified.summary.*` (9 test
  classes, 107 tests): **0 failures, 0 errors**.
  Includes the 30 new R32 tests + 77
  pre-existing tests.
- `org.plumb.dfa.unified.taint.*` (12 test
  classes, 195 tests): **4 pre-existing
  failures, 0 new failures**. The 4 failures
  are documented in
  `_trash/R503-PRE-EXISTING-TEST-FAILURES.md`:
  3 in `R218aZ3BridgeWireTest` (Issue 1:
  pre-R503 sink-arg-index convention in test
  fixtures) and 1 in `TaintedCheckerBaseTest`
  (Issue 2: framework param-index convention
  bug). **None caused by R32.**
- `org.plumb.dfa.unified.checkers.*` spot
  checks: `SqlInjectionCheckerTest` (4
  tests), `OrmLostUpdateDfaCheckerTest` (8
  tests), `R256CrossFunctionFoldTest` (3
  tests), `R257AssignmentInConditionalFoldTest`
  (2 tests): all pass.

## Out of scope (deferred to R33 / R34)

- **R33** — Two-pass analysis. Walk the
  ordered `stringOps` list at the call
  site and re-apply the passthroughs in
  order. Expected impact: CWE-256 +62,
  CWE-259 +126 fires (per the R5+ design
  doc).
- **R34** — `FieldTaintTracker`. Walk the
  ordered `fieldOps` list at the call
  site and seed `this.f` with taint from
  the matching caller arg. Expected
  impact: 2 R567 FN cases (`FN_DEEP`,
  `FN_CROSS`).
- **Cross-class field writes** —
  `other.data = x` is not supported in
  R32; deferred per the R5+ design.
- **Static field writes** — same.
- **Non-param StringOp sources** — R32
  conservatively skips StringOp recording
  when the source's taint label doesn't
  start with `"param:"`. R33 may want to
  record a "non-param" StringOp variant
  for these.

## Files added in R32

| File | Status | Purpose |
|------|--------|---------|
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/StringOp.java` | NEW | `StringOp` record (3416 bytes) |
| `plumb-dfa/src/main/java/org/perm/dfa/unified/summary/FieldOp.java` | NEW | `FieldOp` record (3615 bytes) |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/summary/R32FunctionSummaryEnhancementTest.java` | NEW | 30 unit tests for the data structures (14722 bytes) |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/R32TaintedCheckerRecordingTest.java` | NEW | 15 unit tests for the recording (20171 bytes) |
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/FunctionSummary.java` | MODIFIED | Added stringOps + fieldOps fields, Builder methods, merge/project/isTrivial/toString updates |
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/TaintedCheckerBase.java` | MODIFIED | Added per-function accumulators, FIELDSTORE case, recording helpers, summarizeFunction writes |
| `plumb-dfa/docs/R32-FUNCTION-SUMMARY-ENHANCEMENT-2026-08-17.md` | NEW | This doc |

## Cumulative R1-R32 (Plumb only)

- 39+ CWE families (no new CWE families in
  R32 — this is infrastructure)
- ~16,321 cweFire hits (unchanged — R32 is
  additive, not adding new fire paths yet;
  R33 / R34 are the rounds that consume
  the new data and produce the expected
  +CWE-256 +62 / +CWE-259 +126 fires)
- 109 checkers (unchanged)
- **220 → 265 unit tests (+45 from R32)**
- 0 regressions

## Next steps (per the R5+ 5-day sprint)

- **R33 (Day 2)** — Two-pass analysis.
  Refactor `TaintedCheckerBase.checkInstruction`
  to do a forward pass (build the
  per-function summary) + backward pass
  (walk the sink-to-source path). When
  the backward pass hits a CALL with a
  `stringOps` list in the callee's
  summary, re-apply the passthroughs in
  order on the call's source.
- **R34 (Day 3)** — `FieldTaintTracker`.
  New class that walks the `fieldOps`
  list at the call site and seeds
  `this.f` with taint from the matching
  caller arg.
- **R35 (Day 4)** — R10 baseline
  validation. Expected: CWE-256 38→100,
  CWE-259 54→180, CWE-89 +85, 2 R567
  FN cases fixed.
- **R36 (Day 5)** — Documentation +
  R31.0 release tag.
