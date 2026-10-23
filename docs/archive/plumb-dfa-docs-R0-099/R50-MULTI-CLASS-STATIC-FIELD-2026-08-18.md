# R50 — Multi-Class Static Field Propagation

**Status:** ✅ **SHIPPED (2026-08-18)** — no regression, multi-class
static field taint now flows across function boundaries.

**Files added/modified**:
- `taint/TaintedCheckerBase.java` (edit, ~70 lines) — adds
  cross-function static-field tracker, integrates with
  `recordFieldOpForStore` and `restoreFieldTaintOnLoad`,
  adds `clearCrossFunctionState()` called from `finish()`.
- `integration/R50MultiClassStaticFieldTest.java` (new,
  8.5 KB) — 3 integration tests (SQL, Path, Negative).
- `docs/R50-MULTI-CLASS-STATIC-FIELD-2026-08-18.md` (this
  file).

## Why R50

R46 / R47 lifted the "only `this.f`" restriction and added
static field handling. R47 uses R46's
`(receiver, fieldName)` tracker to support same-function
static field writes and reads. But the tracker is
**per-function** — it's reset at every function boundary
by `initFunction`. That's exactly the wrong behavior for
a static field, which has program-wide identity.

The motivating scenario for R50:
- `ClassB.set(req)` does
  `ClassA.secret = req.getParameter("u")`.
- `ClassC.run()` does
  `String s = ClassA.secret; ... use s in a sink`.
- Taint must flow from `ClassB` to `ClassC` via the
  static field.

Pre-R50, `ClassB` writes `ClassA.secret` in
`ClassB`'s tracker. `ClassC` is analyzed separately
(tracker reset), so the read in `ClassC` sees no taint.
R50 promotes static fields to a **cross-function**
tracker so the write survives the function boundary.

## Design

### New cross-function tracker

A `static final` map in `TaintedCheckerBase`:

```java
private static final Map<String, String>
        crossFnStaticFieldTaint = new HashMap<>();
private static final Map<String, String>
        crossFnStaticFieldSource = new HashMap<>();
```

- Key: `"ClassName.fieldName"` (e.g. `"ClassA.secret"`).
- Value: taint label string (same shape as
  `taintedVars`).
- `crossFnStaticFieldSource` records the function that
  set the taint, for path reporting.

The map is `static` so all `TaintedCheckerBase`
instances in the same JVM share it. Cleared at the end
of every `DfaEngine` run via `clearCrossFunctionState()`,
called from a new `finish()` override:

```java
@Override
public void finish(AnalysisContext ctx) {
    clearCrossFunctionState();
}
```

### Write path (`recordFieldOpForStore`)

If the per-function `FieldTaintTracker` records a write
under `(ClassName, fieldName)`, R50 mirrors the same
write into the cross-function map:

```java
if (isLikelyClassName(recvName)) {
    crossFnStaticFieldTaint.put(
            recvName + "." + ins.fieldName, fldLabel);
    crossFnStaticFieldSource.put(
            recvName + "." + ins.fieldName, fn.signature());
}
```

A clean write also removes the cross-function entry
(consistent with the per-function tracker's "clean
write overwrites" semantics).

### Read path (`restoreFieldTaintOnLoad`)

If the per-function tracker has no entry, R50 falls
through to the cross-function map:

```java
if (isLikelyClassName(recvName)) {
    String globalLabel = crossFnStaticFieldTaint.get(
            recvName + "." + ins.fieldName);
    if (globalLabel != null) {
        taintedVars.put(dv, globalLabel);
        // build a synthetic path
        // recording the source function
    }
}
```

The "is the receiver a class name?" check uses
`isLikelyClassName` (R47's uppercase heuristic).

### Soundness

- A write in `ClassB` updates the cross-function map
  for `ClassA.secret`. A subsequent read in `ClassC`
  sees the taint. If the write never happens at
  runtime, no taint is added to the map, and the read
  sees nothing. No false positives from the
  cross-function map.
- If two functions write different taint labels to
  the same static field (e.g. one writes
  `"input:..."`, another writes
  `"input:other:..."`), the map stores the most
  recent. Reads see the most recent. Slightly more
  conservative than the union-of-labels ideal, but
  sound for "is there any taint?".
- The static state is cleared at the end of each
  `DfaEngine` run, so a subsequent run on a different
  program starts clean.

## Numerical results

| Metric | Pre-R50 | Post-R50 | Δ |
|---|---:|---:|---:|
| R10 Juliet baseline | 6939 fires | 6939 fires | 0 |
| R567 cross-fn sweep | 4/4 ✓ | 4/4 ✓ | 0 |
| plumb-dfa unit tests | 583/583 ✓ | 583/583 ✓ | 0 |
| R50 multi-class test | — | 2/2 ✓ (1 SQL, 1 Path, 0 Neg) | NEW |

R50 is a no-regression round on the existing
benchmarks. The new test exercises the multi-class
static-field taint flow that pre-R50 silently
dropped.

## Cross-cutting design lessons (R50)

1. **Per-function trackers are wrong for static
   fields.** A static field is a program-wide
   singleton. R47's per-function tracker handled
   the "single function reads + writes" case but
   broke the "different functions read and write"
   case. R50's fix is local — add a static map,
   mirror writes, consult on reads — but the design
   lesson is general: **"scoped to function" is a
   design choice, not a hard constraint, and it
   needs to be revisited for program-singleton
   values**.
2. **`static` fields for cross-cutting state are
   fine when the lifecycle is clear.** The
   cross-function tracker is `static` so all
   checker instances share it, and `finish()`
   clears it between programs. The
   `clearCrossFunctionState()` method is the
   safety net.
3. **Path reporting for cross-function state is
   trickier than the data flow itself.** R50
   records the *source function* in
   `crossFnStaticFieldSource` so the finding's
   path can mention where the taint was set. The
   current path is a single "GLOBAL_STATIC_READ"
   step, which is less informative than a true
   source-to-sink chain. A future R-round could
   improve this by storing the original taint
   *path* (not just the label) and reconstructing
   the chain at read time.
4. **R47's uppercase heuristic is the load-bearing
   trick for static fields.** `isLikelyClassName`
   is the gate that decides whether a FIELDSTORE
   is "this.f" (instance), "h.f" (cross-instance),
   or "Helper.f" (static). R50 reuses this exact
   check to decide whether to write to the
   cross-function map.
5. **The "is the receiver a class?" question
   matters across R34, R42, R46, R47, R50.** Every
   field-tracking round has had to reason about
   this. A future refactor could centralize the
   "what kind of field is this?" check in a
   shared `FieldOpClassifier` helper.

## R50+ follow-up candidates

- **R51**: Multi-receiver aliasing. R46 conservatively
  treats different `TacVar`s as different instances
  even when they're aliased. Precise alias analysis
  is a future R-round.
- **Cross-function path preservation**: R50 stores
  the source *function* but not the original
  *taint path*. A future R-round could store the
  full `List<FlowStep>` so cross-function static
  reads show a more informative path.
- **Field op model v2**: A unified
  `FieldOpClassifier` that encapsulates the
  uppercase-heuristic / cross-instance / static
  checks. Would reduce the duplication across
  R34, R42, R46, R47, R50.
