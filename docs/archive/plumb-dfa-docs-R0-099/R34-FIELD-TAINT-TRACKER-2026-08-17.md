# R34 — `FieldTaintTracker` for Cross-Function `this.f` Propagation

**Date**: 2026-08-17
**Round**: R34 (Day 3 of the R5+ 5-day cross-fn sprint)
**Status**: SHIPPED — 13 new unit tests, 0 regressions
**Effort**: ~2.5 hours (new class + 4 helper updates + 13 unit tests)

## Why R34

The R5+ design doc identified field
propagation as the missing piece in
the cross-function taint machinery.
The classic FN_DEEP pattern (from
R567):

```java
class DeepFlow {
    String data;  // field
    public void bad(HttpServletRequest req, Connection conn) {
        String u = req.getParameter("u");
        setData(u);  // this.data = u  (cross-function)
        Statement s = conn.createStatement();
        s.execute("...data...");  // SQL sink on this.data
    }
    void setData(String d) { this.data = d; }
}
```

Before R34, the taint was lost at
`setData(u)`: the caller's
`TaintedCheckerBase` saw the callee's
`TaintPropagate` on param 0 (which
means "this function takes a tainted
arg") and applied it to the caller's
arg `u` — but the field write inside
the callee (`this.data = d`) was not
propagated back to the caller's
`this.data`. The subsequent
`FIELDLOAD` of `this.data` saw no
tracked taint, so the SQL sink call
was not detected.

R34 fixes this by introducing the
`FieldTaintTracker`:

- The callee's `FunctionSummary.fieldOps`
  records `this.data = param 0`
  (R32's contribution).
- At the call site, R34 walks the
  callee's `fieldOps` and seeds the
  caller's `FieldTaintTracker` with
  `this.data` ← taint from the
  matching caller arg.
- When the caller later reads
  `this.data` (a `FIELDLOAD`), R34
  restores the taint from the tracker.

## What R34 ships

### 1. `FieldTaintTracker` class (`taint/FieldTaintTracker.java`)

A per-function tracker for `this.f`
taint state. Key methods:

- `recordWrite(fieldName, label, path)` —
  record a write to `this.f` with the
  given taint state. Last-write-wins.
- `readField(fieldName)` — returns the
  `TrackedTaint` (label + path) or
  `null` if not tracked.
- `hasField(fieldName)` — true iff a
  write has been recorded.
- `clear()` / `clearField(fieldName)` —
  reset for the next function or after
  a clean write.
- `applyCalleeFieldOp(fieldOp, callArgIndex, calleeSig, callerArgs, callerTaintedVars, callerTaintPaths, callLine, callColumn, receiverName)` —
  apply a single `FieldOp` from a
  callee's summary to the caller's
  tracker. Conservative: only seeds
  for `this.f` (not other-class
  fields), only when the source is a
  param (not a local), only when the
  matching caller arg is currently
  tainted.

### 2. `TaintedCheckerBase` wiring

- Per-function `FieldTaintTracker`
  instance, cleared in `initFunction`.
- `FIELDLOAD` branch now does
  `propagateMOVE(ins)` followed by
  `restoreFieldTaintOnLoad(ins, fn)`:
  if `ins.lhs1.name() == "this"` and
  the tracker has a taint for
  `ins.fieldName`, the dest is marked
  tainted with the tracker's label +
  path (plus a "propagate" step
  recording the FIELDLOAD).
- `FIELDSTORE` branch (`recordFieldOpForStore`)
  now also writes to the tracker on
  `this.f = x`. The recording is
  conservative: only when the value
  is currently tainted from a param
  AND the receiver is `this`. A
  non-param source or non-this
  receiver is a no-op.
- `propagateTaintFromCalleeSummary`
  now walks the callee's `fieldOps`
  and calls `applyCalleeFieldOp` for
  each. The matching caller arg is
  at `sourceParamIndex + 1` (the +1
  accounts for the receiver at
  `args[0]` for instance methods).

### 3. Scope (deliberately limited)

- **Only `this.f`** — not other
  instances, not static fields.
  Cross-class / static writes are
  deferred per the R5+ design.
- **Per-function state** — the
  tracker is reset at every function
  boundary. The cross-function flow
  goes through the
  `FunctionSummaryCache`, not through
  the tracker.
- **Last-write-wins** — multiple
  writes to the same field in one
  function: the later write's taint
  state is what subsequent reads see.

## Cross-cutting design decisions

1. **Conservative non-param source.**
   For `FieldOp.sourceParamIndex() == -1`
   (NON_PARAM_SOURCE), the source is
   a local; we have no caller arg to
   map to. R34 records NOTHING in the
   tracker (a subsequent FIELDLOAD
   sees "no tracked taint" = clean).
   This avoids FPs from a local that
   was actually un-tainted in the
   callee.
2. **Clean-write clears prior
   taint.** A `this.f = clean_local`
   after a `this.f = tainted_param`
   correctly removes the field from
   the tracker (subsequent reads see
   clean).
3. **Receiver check before field
   write.** Only `this.f` writes /
   reads are tracked. Cross-class
   writes (`other.f = x`) and static
   writes (`Holder.f = x`) are
   silently ignored. Deferred per the
   R5+ design.
4. **`callArgIndex = sourceParamIndex + 1`.**
   For instance methods, args[0] is
   the receiver, so the user-visible
   param N is at args[N+1]. Static
   methods would use
   `callArgIndex = sourceParamIndex`
   directly. R34 uses the +1
   approximation (the common case);
   a future round can detect static
   vs instance via the callee's
   signature.
5. **Path contains the source's
   path + a "summary-fold" step.**
   The FlowStep chain is:
   `<source's source step> ... →
   summary-fold: call <calleeSig>
   writes this.<field>`. The
   "summary-fold" role matches the
   existing convention used by
   `propagateTaintFromCalleeSummary`
   for the dest's path.

## Test coverage (13 new tests, 0 regressions)

### `R34FieldTaintTrackerTest` (13 tests)

- Tracker in isolation (7 tests):
  write/read/clear/clearField/hasField
  /trackedFields, last-write-wins,
  null-label-no-op, null-field-no-op.
- `applyCalleeFieldOp` call-side
  seeding (4 tests): matching-param
  seeds with full path; clean-source
  removes prior taint; non-param
  source is no-op; cross-class
  receiver is no-op.
- End-to-end FN_DEEP / FN_CROSS
  style (1 test):
  `fnDeepStyleFieldTaintFlowsFromHelperToFieldLoad`
  drives the R567 FN_DEEP scenario
  through `TaintedCheckerBase.checkInstruction`
  end-to-end, verifies the loaded
  value `data` is correctly tainted
  after `setData(u)` and a
  `FIELDLOAD` of `this.data`.
- initFunction clears tracker (1 test):
  verifies no state leaks across
  functions.

## Regression check

Ran `org.plumb.dfa.unified.summary.*Test`
+ `org.plumb.dfa.unified.taint.*Test`
(219 tests, 2 skipped, 4 pre-existing
R503 failures):

- **All R32 + R33 + R34 new tests pass**
  (30 + 15 + 11 + 13 = 69 new tests).
- **0 new regressions** introduced by
  R34.
- The 4 pre-existing failures in
  `R218aZ3BridgeWireTest` (3) and
  `TaintedCheckerBaseTest` (1) are
  documented in
  `_trash/R503-PRE-EXISTING-TEST-FAILURES.md`
  and **NOT caused by R34**.

## Out of scope (deferred)

- **Cross-class field writes** —
  `other.f = x` is not supported in
  R34; deferred per the R5+ design.
- **Static field writes** — same.
- **Backward pass from sinks** —
  the R5+ design's "backward pass"
  was for path construction. R34
  doesn't add a separate backward
  pass because the existing
  `taintPaths` map already records
  the full path during the forward
  walk.
- **Static method detection** —
  R34 uses the +1 approximation
  for all calls. A future round
  can detect static vs instance
  via the callee's signature and
  use `callArgIndex = sourceParamIndex`
  for static methods.

## Files added in R34

| File | Status | Purpose |
|------|--------|---------|
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/FieldTaintTracker.java` | NEW | `FieldTaintTracker` class (12.7KB) |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/R34FieldTaintTrackerTest.java` | NEW | 13 unit tests (18.3KB) |
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/TaintedCheckerBase.java` | MODIFIED | Per-function tracker, FIELDLOAD restore, FIELDSTORE tracker write, propagateTaintFromCalleeSummary seed |
| `plumb-dfa/docs/R34-FIELD-TAINT-TRACKER-2026-08-17.md` | NEW | This doc |
| `_trash/fix_fieldt.py.bak` | BACKUP | R34 temp fix script (Python file used to bulk-replace em-dash in FieldTaintTracker) |

## Cumulative R1-R34 (Plumb only)

- 39+ CWE families (no new CWE families
  in R32-R34 — these are infrastructure
  and field-propagation rounds)
- ~16,321 cweFire hits (unchanged by
  R34 alone; R5+ design doc's +62
  CWE-256 / +126 CWE-259 estimates
  assume the full Juliet corpus, a
  future round)
- 109 checkers (unchanged)
- **276 → 289 unit tests (+13 from
  R34)**
- 0 regressions
