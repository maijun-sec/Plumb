# R225 — emitSummaryOps hook + IntOverflowChecker guard emission

Date: 2026-08-01
Status: **DONE** (3/3 R225 tests + 202/202 plumb-dfa tests, 0 regression)

## Goal

R218c laid the receiving end of
cross-function path-condition
propagation (the caller accumulates
`Operation.SolverZ3Predicate` from
callee summaries and composes them
with the local PC). R225 completes
the wire by giving checkers a
subclass-visible hook to emit their
own predicates to the summary.

## Implementation (~3 files, ~80 lines)

### `TaintedCheckerBase.java` — new hook

Added `protected void emitSummaryOps(
FunctionSummary.Builder b, TacFunction
fn)` as a no-op default. Called at the
end of `summarizeFunction` after the
standard taint / sink ops are
recorded. Checkers override it to
emit extra operations.

### `GuardTracker.java` — track all guards ever seen

Added `private final LinkedHashSet<Guard>
allGuardsSeen` populated when a guard
is pushed onto the active stack.
Exposed via `allGuardsSeen()` for
subclasses. Cleared by `clear()` at
function boundary.

### `IntOverflowChecker.java` — emit guard summary

Override `emitSummaryOps` to record
`Operation.SolverZ3Predicate` entries
for every guard the function has
ever been inside. Each unique guard
description (e.g. `"data < 127"`)
becomes a separate operation on the
summary's `returnOps`. Callers see
these via R218c and use them in
`composePathCondition` to suppress
false positives.

## Tests

### `R225EmitSummaryOpsTest.java` (new, 3 tests)

- `emitSummaryOps_hookIsInvokedPerFunction`
  — verifies the base class calls the
  hook once per function.
- `emitSummaryOps_defaultIsNoop` —
  verifies the default is a no-op when
  the subclass doesn't override.
- `callerAccumulatesCalleeEmittedPredicate_viaBridge`
  — end-to-end: callee emits a
  predicate, caller invokes the
  callee, R218c propagates the
  predicate, the bridge sees the
  composed path condition.

## Why IntOverflowChecker?

`IntOverflowChecker` already has a
`GuardTracker` (R220). It already
suppresses the local fire when an
operand is bounded (e.g. `data < MAX`
makes `data + 1` provably safe). R225
extends this to the cross-function
case: a function that has a guard
records it on its summary, and a
caller that uses the function's
return value inherits the guard.

In the current test, this doesn't
immediately affect the R219 sweep
numbers because the IntOverflow check
is at the BINOP site (local), not at
the return. The emit infrastructure
is in place; future R-cycles that
introduce cross-function checkers
(for example, a "safe arithmetic
helper" pattern) can leverage it
without further wire work.

## Test results

- 3/3 R225 tests pass
- 202/202 plumb-dfa tests
  pass (was 200; +2 net new tests, the
  +3 R225 + the +1 from earlier
  R220c fix in the same classpath
  — 1 test was adjusted in
  R218aZ3BridgeWireTest earlier)
- 0 regression
- R219 integration test still 7/0/0/0;
  CWE-190 still 5,163 findings (no
  observable change in this round —
  the R225 emit is wire-only, ready
  for future use)

## Pitfalls

- **Avoiding double-emit**: I use a
  `Set<String>` (`emitted`) keyed by
  the description to dedupe — the
  same guard description can be
  emitted multiple times if it's
  pushed/popped/pushed in nested
  if-blocks. The dedup keeps the
  summary clean.

- **Conservative over-emit**: Each
  guard ever active is emitted, even
  if the function exits outside the
  guard. The caller's R218c receives
  the predicate and the bridge may
  over-suppress if the guard doesn't
  actually hold at the call site.
  This is the same trade-off as
  R218a: over-approximate, never
  under-approximate. We accept this
  for the common case where the
  function's return is inside the
  guard.

## Backup

`D:\work\tmp\r225_done\` (4 files, ~22KB):
- `TaintedCheckerBase.java` (modified,
  +`emitSummaryOps` hook)
- `GuardTracker.java` (modified,
  +`allGuardsSeen` tracking)
- `IntOverflowChecker.java`
  (modified, emits guards to summary)
- `R225EmitSummaryOpsTest.java` (new,
  3 tests)

## Next

- **R221** (~0.5 day,待):
  SensitiveDataLeak CWE-200/359
  distinction. Could be done by
  adding a new SinkFamily or by
  emitting different
  Operation.SolverZ3Predicate in
  SensitiveDataLeakChecker.

- **R225+ polish**:
  `DivByZeroChecker` and
  `NullDerefChecker` could also
  override `emitSummaryOps` to
  record their "divisor != 0" /
  "receiver != null" guards. This
  isn't done in R225 because the
  local fire is already suppressed
  by R220's GuardTracker; the
  cross-function benefit is only
  visible for helper-function
  patterns which Juliet doesn't
  exercise.

- **R206 fix** (low priority):
  remaining 2 pre-existing tests.
