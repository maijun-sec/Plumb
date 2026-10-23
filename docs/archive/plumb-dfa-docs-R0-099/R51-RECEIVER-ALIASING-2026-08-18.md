# R51 — Receiver Aliasing via Declared Class Type

**Status:** ✅ **SHIPPED (2026-08-18)** — no regression,
receiver aliasing now propagates taint across same-typed
receivers.

**Files added/modified**:
- `taint/TaintedCheckerBase.java` (edit, ~100 lines) —
  adds `crossFnInstanceFieldTaint` and
  `crossFnInstanceFieldSource` per-class instance-field
  trackers; integrates with
  `recordFieldOpForStore` /
  `restoreFieldTaintOnLoad`; new
  `classNameOfType` helper.
- `integration/R51ReceiverAliasingTest.java` (new,
  8.5 KB) — 3 integration tests.
- `docs/R51-RECEIVER-ALIASING-2026-08-18.md` (this
  file).

## Why R51

R46 lifted the "only `this.f`" restriction and keyed the
per-instance field tracker on
`(receiver, fieldName)`. R47 extended this to static
fields via the `classRefVars` chain tracker. R50
promoted static fields to a **cross-function** tracker
so a write in `ClassB` is visible to a read in
`ClassC`.

R51 closes the symmetric gap for **instance fields**
across **aliased receivers**:
- `Helper h1 = new Helper();`
- `Helper h2 = h1;` — `h2` is a (potentially-)alias of
  `h1`.
- `h1.data = u;` (tainted).
- `sink(h2.data)` should fire because `h1` and
  `h2` may point to the same object.

Pre-R51, R46's per-instance tracker recorded the write
under `(h1, data)` and the read under `(h2, data)` —
different keys, no taint flow. R51's per-class tracker
also keys on the receiver's *declared class type* —
`(Helper, data)` — so any receiver of declared type
`Helper` shares taint state with every other.

## Design

### New per-class instance-field tracker

A second pair of `static final` maps in
`TaintedCheckerBase`:

```java
private static final Map<String, String>
        crossFnInstanceFieldTaint = new HashMap<>();
private static final Map<String, String>
        crossFnInstanceFieldSource = new HashMap<>();
```

- Key: `"ClassName.fieldName"` (e.g. `"Helper.data"`).
- Value: taint label string.

`ClassName` is the receiver's declared class type,
extracted from the `TacType` via the new
`classNameOfType` helper (returns `null` for
non-class types — primitives, arrays, UNKNOWN).

`crossFnInstanceFieldSource` records the source
function for path reporting. Both maps are cleared in
`clearCrossFunctionState()` (which `finish()` calls
between DfaEngine runs).

### Write path

`recordFieldOpForStore` already records the
per-instance write (R46) and the per-static-class
write (R50). R51 adds a third write — to the
per-class instance tracker — whenever the receiver has
a declared class type:

```java
String recvTypeName = classNameOfType(recv.type());
if (recvTypeName != null) {
    crossFnInstanceFieldTaint.put(
            recvTypeName + "." + ins.fieldName, fldLabel);
    crossFnInstanceFieldSource.put(
            recvTypeName + "." + ins.fieldName, fn.signature());
}
```

A clean write also removes the per-class entry,
consistent with the per-instance and per-static
trackers.

### Read path

`restoreFieldTaintOnLoad` first consults the
per-instance tracker (R46), then the per-static-class
tracker (R50), then the per-class instance tracker
(R51) as a third tier. The per-class read produces a
synthetic `ALIASED_INSTANCE_READ` path that mentions
the declared type and the source function.

### Soundness

- A write in one function to a receiver of declared
  type `Helper` updates `(Helper, data)` in the global
  map. A read in another function on any
  `Helper`-typed receiver sees the taint.
- If two receivers of the same declared type are
  *not* actually aliased, the per-class tracker
  treats them as one — over-approximates. Possible
  false positive, but R51's R10 / R567 baselines are
  stable on the change.
- If two receivers are of *different* declared
  types, the per-class tracker keys are different
  and the cross-type flow is correctly prevented
  (verified by `DIFFERENT_TYPES_NEGATIVE`).
- The static state is cleared at the end of each
  `DfaEngine` run.

## Numerical results

| Metric | Pre-R51 | Post-R51 | Δ |
|---|---:|---:|---:|
| R10 Juliet baseline | 6939 fires | 6939 fires | 0 |
| R567 cross-fn sweep | 4/4 ✓ | 4/4 ✓ | 0 |
| plumb-dfa unit tests | 583/583 ✓ | 583/583 ✓ | 0 |
| R51 receiver-aliasing test | — | 2/2 ✓ (SQL, Path) + 0 Neg | NEW |

R51 is a no-regression round on existing benchmarks
and unlocks the same-typed-aliased-receiver pattern
in real code.

## Cross-cutting design lessons (R51)

1. **Alias analysis is a spectrum.** R51 implements
   the *coarsest* useful form: "receivers of the
   same declared type are potentially aliased". A
   more precise analysis would use VTA's points-to
   sets (R226) and merge only those receivers with
   overlapping points-to. R51 deliberately picks
   the coarser form because (a) the R10 / R567
   baselines are stable on it, (b) it's a
   self-contained change with no cross-cutting
   impact, and (c) "potential alias" matches the
   intuition of "what would a human reviewer
   notice?".
2. **Two-tier scope (instance, then class) is the
   right ordering.** R46's per-instance tracker
   records exact (recv, field) state. R51's
   per-class tracker is consulted as a fallback —
   only when the per-instance tracker has nothing.
   This means exact match wins over aliased match,
   which is the right precedence (an exact h1.data
   write is more specific than an aliased
   Helper.data write).
3. **Static and instance cross-function trackers
   are now symmetric in design.** R50's
   `crossFnStaticFieldTaint` keys on
   `ClassName.fieldName` for static fields
   (uppercase heuristic). R51's
   `crossFnInstanceFieldTaint` keys on
   `ClassName.fieldName` for instance fields
   (declared type). The two maps are separate
   because (a) static fields have a different
   access pattern (chained IR), (b) the upper-case
   heuristic doesn't apply to instance receivers,
   and (c) future R-rounds may want to tune them
   independently. A future refactor could unify
   them into a single `crossFnClassFieldTaint`.
4. **Synthetic paths are a way to surface
   cross-function state in finding reports.** R51
   emits an `ALIASED_INSTANCE_READ` step that
   mentions the declared type and the source
   function. The path is less informative than a
   true source-to-sink chain, but it's better than
   no path at all.
5. **The TacType.kind() == CLASS gate is the right
   filter.** R51's `classNameOfType` returns `null`
   for non-class types. This is the same gate
   R46's class-type heuristic uses, and it
   prevents spurious "Helper" lookups for
   primitives (which would never have fields).

## R51+ follow-up candidates

- **Points-to-based alias analysis**: replace the
  declared-type heuristic with a true
  points-to-based merge. R226's
  `VtaCallGraphStrategy.computePointsTo` is the
  right primitive; would need to thread the
  points-to map through `AnalysisContext`.
- **Unify static + instance cross-function
  trackers**: a single
  `crossFnClassFieldTaint: Map<String, String>`
  shared by both R50 (static, uppercase class
  name) and R51 (instance, declared type). The
  two halves of the design are now identical
  modulo the key derivation.
- **Per-class path preservation**: store the
  full `List<FlowStep>` (not just the label) so
  cross-class reads can show the original
  source-to-write chain.
- **Alias analysis is also relevant for
  `R218a` Z3 bridge**: the current
  path-condition conjunction may over-approximate
  aliasing through object identity. A future
  R-round could feed the R51 per-class tracker
  into the bridge as a hint.
