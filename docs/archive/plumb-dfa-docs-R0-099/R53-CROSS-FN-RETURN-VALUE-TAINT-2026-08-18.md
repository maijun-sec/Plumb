# R53 — Cross-Function Return-Value Taint (Getter Pattern)

**Status**: ✅ SHIPPED 2026-08-18. R567 4/4 ✓ preserved, R50 2/2 ✓ preserved,
R51 2/2 ✓ preserved, R53 4/4 ✓ new, R34 13/13 ✓ preserved, TaintedCheckerBase
6/6 ✓ preserved, R49 5/5 ✓ preserved. R10 baseline 6939 fires unchanged.
Doc: `plumb-dfa/docs/R53-CROSS-FN-RETURN-VALUE-TAINT-2026-08-18.md`.

## Why R53

R37 / R40 fold the callee's `TaintPropagate` operations into the caller's
dest. R40's conservative arg→dest passthrough covers the `buildQuery()`
pattern (caller's arg is tainted, callee returns tainted dest) but
**misses the getter pattern**: the caller's *receiver* is not directly
tainted, but one of its *fields* is, and the callee returns that field:

```java
class Helper {
    String data;
    void setData(String u)   { this.data = u; }   // callee writes field
    String getData()         { return this.data; }  // callee returns field
}
class Main {
    void bad(HttpServletRequest req, Connection c) {
        Helper h = new Helper();
        String u = req.getParameter("u");
        h.setData(u);             // h.data becomes tainted
        String v = h.getData();   // v should be tainted (it's h.data)
        stmt.execute("...'" + v + "'");   // sink should fire
    }
}
```

Without R53, the standalone analysis of `getData()` does not see `h.data`
as tainted (its per-function tracker is empty at analysis time — `h` is a
parameter of the *caller*, not of `getData`). The TaintPropagate path on
`returnOps` therefore misses, and R40's caller-arg walk sees `h` is not
directly tainted — so the dest `v` stays clean. The CWE-89 finding is lost.

## Design

R53 is a small **two-part** extension to the existing cross-fn fold in
`propagateTaintFromCalleeSummary`:

1. **Per-instance field chain** — when an arg is not directly tainted,
   consult `FieldTaintTracker.readAnyTaintedFieldFor(av.name())` for
   the per-function tracker's `<recv>.<field>` entries. If any field of
   the receiver is tainted (e.g. `h.data`), the dest inherits the
   field's taint.

2. **Aliased-instance field chain (R51 cross-fn)** — when the per-instance
   lookup is empty AND the arg is a class-typed TacVar, scan the unified
   R50/R51 `crossFnClassFieldTaint` for keys starting with the receiver's
   declared class name (e.g. `Helper.`). The first matching key gives
   the taint label. This handles the cross-instance case
   (`Helper h1 = ...; h1.setData(u); Helper h2 = h1; v = h2.getData();`)
   where the *same class* was tainted on a different instance.

Both paths produce a synthetic label like
`r53-fieldchain:h.callee:Helper#setData/1` (per-instance) or
`r53-classchain:Helper.callee:Helper#setData/1` (aliased), which the
caller-side R40 fold surfaces as a `conservative-summary-fold` step.

## Three R53-only engine / summary fixes (uncovered by the new logic)

The getter pattern only fires after three pre-existing blockers in the
cross-fn fold path are cleared. Each was a one-line tweak; together
they make R53 work end-to-end:

### 1. `DfaEngine` — store trivial-with-body summaries

`getData()` is a "trivial" function from the summary's perspective (no
`TaintPropagate` on `returnOps`, no fieldOps for the read). Pre-R53 the
engine's `if (!merged.isTrivial())` guard **dropped** it from the cache
— so the caller's `summaryCache.get("getData/0")` returned the empty
fallback, and R40's `calleeSummary.hasBody()` gate was unreachable.
R53 widens the put-condition to `!isTrivial() || hasBody()` so
trivial-with-body functions (getters, setters, passthroughs) land in
the cache.

### 2. `FunctionSummary.merge` — propagate `hasBody` through trivial-trivial

The merge short-circuit `if (this.isTrivial()) return other;` dropped
the empty summary from a no-op-checker against a non-trivial
sibling. R53 keeps the union semantics (`if (this.isTrivial() &&
!this.hasBody()) return other;`) so a trivial-with-body receiver is
not lost when folded with a sibling that has taint data.

### 3. `propagateTaintFromCalleeSummary` — cross-class lookup fallback

The summary cache is populated by the engine's `r40AliasForFunction`
under the bare-name-with-arity form (e.g. `setData/1`), but a
cross-class CALL's `ins.calleeSig` is `ClassName.methodName/arity`
(e.g. `Helper.setData/1`). The direct lookup misses; the pre-R53 code
returned early. R53 adds a `r53StripClassPrefix` fallback that
strips the class prefix and retries — and the same fix accepts a
trivial-with-body summary (R50's `hasBody` gate needs it).

## Three R53 R51-fold-path fixes (for the cross-instance getter)

The "sibling instance" getter pattern needs the cross-fn fold to also
seed the program-wide `crossFnClassFieldTaint`, not just the
per-function `FieldTaintTracker`. Three R53 changes in the fold path
itself:

### 4. `FieldTaintTracker.applyCalleeFieldOp` — accept a `TacType` for
the receiver

Pre-R53 the helper only knew the receiver's *name* (e.g. `h1`). For
the R51 declared-class-type form of the cross-fn tracker key
(`"Helper.data"`), the fold needs the receiver's declared
`TacType` too. R53 extends the helper signature with
`TacType receiverType` and forwards it to a new static
`TaintedCheckerBase.seedCrossFnClassFieldTaintFromFold(...)`.

### 5. `TaintedCheckerBase.seedCrossFnClassFieldTaintFromFold` —

Static helper that writes both R50 (uppercase class name) and R51
(declared class type) entries into the unified
`crossFnClassFieldTaint`. Gated on `srcLabel != "param:..."` (the same
R53 gate used in `recordFieldOpForStore`) so the speculative
"every param is tainted" seed in `initFunction` does not pollute
program-wide state with sound-but-not-real taint.

### 6. `FieldTaintTracker.applyCalleeFieldOp` — clean-write clears
cross-fn entry

Pre-R53 a non-`TacVar` arg (e.g. a `Const` literal) returned early
without clearing the per-function tracker OR the cross-fn tracker.
R53 now clears both — so a `h.setData("clean-literal")` after
`h.setData(u)` correctly retires the stale taint. The same logic
applies to `sourceParamIndex == -1` (NON_PARAM_SOURCE) writes,
which are conservatively treated as clean overwrites.

## R53 R42-side fix (param-seeded suppression)

`initFunction` seeds every parameter with `markSource` so the
standalone FIELDSTORE analysis has *something* to propagate. That
speculation is a sound over-approx for the per-function path but is
**not** a real cross-fn dataflow. R53 adds a `isParamSeeded` gate in
`recordFieldOpForStore` (the standalone-FIELDSTORE write path) so a
value whose taint label starts with `"param:"` does **not** write to
`crossFnClassFieldTaint`. The per-function tracker is still written
(because within the function's scope, param-seeded taint is sound),
but the program-wide state stays clean — preventing a sibling getter
from reading speculative taint and emitting a TaintPropagate on its
returnOps (which the caller's R40 fold would then surface as a
finding — the FP root cause in the first debug run).

## Test (R53CrossFnReturnValueTaintTest)

Four cases, all run in a single `runValidation` method:

| Case | Source | Expected | Actual |
|---|---|---:|---:|
| GETTER_SQL | setter writes tainted u to h.data, getter returns h.data → SQL sink | 1 | 1 |
| GETTER_CROSS_INSTANCE | `Helper h1 = new Helper(); h1.setData(u); Helper h2 = h1; v = h2.getData();` → SQL sink | 1 | 1 |
| NEGATIVE_CLEAN | setter writes clean literal to h.data, getter returns h.data → SQL sink | 0 | 0 |
| NEGATIVE_OVERWRITE | setter writes tainted u, then clean literal, then getter → SQL sink | 0 | 0 |

Findings messages include the new label
`r40-passthrough:r53-fieldchain:h.callee:Helper.setData/1` (or
`r53-classchain:Helper.callee:Helper.setData/1` for the cross-instance
case) so reporters can render the chain in the source-to-sink trace.

## Files

**Modified**:
- `taint/TaintedCheckerBase.java` (~150 lines net: `LOG` field, R53
  class-chain fallback, R53 hasBody gate, param-seeded suppression,
  `r53StripClassPrefix` helper, `seedCrossFnClassFieldTaintFromFold`
  static helper, `classNameOfTypeStatic`, `removeCrossFnClassFieldTaint`).
- `taint/FieldTaintTracker.java` (~30 lines: `applyCalleeFieldOp`
  signature extension, clean-write clears for Const + TacVar
  paths, NON_PARAM_SOURCE clears, R53 gate).
- `engine/DfaEngine.java` (4 places: 2 sequential and 2 parallel
  put-conditions widened to `!isTrivial() || hasBody()`).
- `summary/FunctionSummary.java` (1 place: `merge` short-circuit
  now propagates `hasBody` through trivial-trivial).
- `test/taint/R34FieldTaintTrackerTest.java` (4 call-site updates
  for the new `TacType receiverType` parameter).

**New**:
- `integration/R53CrossFnReturnValueTaintTest.java` (4 cases in 1
  validation method, asserting 2 expected fires / 2 actual fires
  total = 2 expected vs 2 actual).

## Numerical results

| Metric | Pre-R53 | Post-R53 | Δ |
|---|---:|---:|---:|
| R10 Juliet baseline | 6939 fires | 6939 fires | 0 |
| R567 cross-fn sweep | 4/4 ✓ | 4/4 ✓ | 0 |
| R50 multi-class static | 2/2 ✓ | 2/2 ✓ | 0 |
| R51 receiver aliasing | 2/2 ✓ | 2/2 ✓ | 0 |
| R34 FieldTaintTracker | 13/13 ✓ | 13/13 ✓ | 0 |
| TaintedCheckerBase | 6/6 ✓ | 6/6 ✓ | 0 |
| R49 2-CFA unit | 5/5 ✓ | 5/5 ✓ | 0 |
| R48 overload unit | 2/2 ✓ | 2/2 ✓ | 0 |
| CallGraphBuilder / Global | 8+8 ✓ | 8+8 ✓ | 0 |
| R53 getter integration | — | 4/4 ✓ | NEW |
| **R53 debug scratch** (in `target/test-classes/_trash/`) | 2 files | 2 files | 0 |

No regressions. R10 baseline 6939 fires preserved.

## Cross-cutting design lessons (R53)

1. **The "empty per-function tracker" is a co-design choice with the
   analysis order.** R34's design relies on the standalone analysis
   seeing a clean field, and the call fold seeding it from the
   caller's arg. R53 surfaces this when the function does no taint
   work itself (a getter / setter / passthrough) but the analysis
   order still has the fold run — the result is "no taint, no fold
   result" silently. The `hasBody` flag is the lightweight signal
   that gates "this function participates in the fold even if it
   emitted no TaintPropagate".

2. **The standalone-seeded `param:xxx` taint is a per-fn
   speculation, not a cross-fn dataflow.** Pre-R53 the
   `crossFnClassFieldTaint` was written from both the standalone
   FIELDSTORE and the fold. The standalone write was
   param-seeded, so a getter's standalone analysis of `return
   this.data` saw taint via the program-wide tracker and emitted a
   TaintPropagate on `returnOps` — which the caller's R40 fold
   surfaced as a finding (FP). R53's `isParamSeeded` gate is the
   load-bearing fix; it cleanly separates "this function's
   speculation" from "this function's actual fold result".

3. **The cross-fn fold is the right place to seed the R51
   declared-class-type entry.** Pre-R53 the fold only wrote to
   the per-function `FieldTaintTracker`. The R51 aliased case
   (`Helper h1 = ...; h1.setData(u); h2 = h1; v = h2.getData();`)
   needs the program-wide tracker to fire, because the per-function
   tracker is keyed by instance name. R53's
   `seedCrossFnClassFieldTaintFromFold` mirrors the standalone
   write in the fold path — the two paths now produce symmetric
   R50/R51 entries.

4. **Trivial-with-body is a meaningful merge signal.** The
   `FunctionSummary.merge` short-circuit was written when
   "trivial = no info" was the only option. After R40 the body
   flag carries real meaning (R40's conservative passthrough gate
   is `calleeSummary.hasBody()`). R53 widens the short-circuit so
   a body-only signal survives a merge with a non-trivial
   sibling.

5. **The cross-class lookup fallback should be in the read path,
   not the write path.** Pre-R53, the engine stored the summary
   under both `fn.signature()` (canonical) and `r40AliasForFunction`
   (bare-name-with-arity) — but not under the
   `ClassName.methodName/arity` form. R53's `r53StripClassPrefix`
   fallback normalises the read key. The alternative — adding a
   third write-alias with the class prefix — would have required
   passing the enclosing class through `r40AliasForFunction`, which
   is more invasive than the read-side fix.

6. **Pre-R53 the fold was correct for the same-instance case but
   blind to the cross-instance case.** R51's R52-collapsed tracker
   has the data; the fold just wasn't writing to it. R53 closes
   the loop with `seedCrossFnClassFieldTaintFromFold` + the new
   `readAnyTaintedFieldFor`-class-chain scan.

## R53+ follow-up candidates

- **R54**: refactor `GlobalCallGraphBuilder`'s inlined R52 logic to
  share `CallGraphBuilder.r48ResolveCalleeSigWithArgs` (after
  cross-TU / single-TU mismatch is resolved — pre-existing
  concern from R52 doc).
- **Bare-name consistency fix**: `bareNameAndArity` for "no parens"
  vs "with parens" signatures — R53's `r53StripClassPrefix` does
  this for the class-prefix-stripping case; the same uniform
  shape would simplify R49's `normalizeCalleeKey`.
- **FieldOpClassifier**: centralize field-classification heuristic
  (`isLikelyClassName` + `classNameOfType` + the `isLikelyUserArgType`
  guard in `propagateTaintFromCalleeSummary`) into a single
  `FieldOpClassifier` helper. R34 / R42 / R46 / R47 / R50 / R51 /
  R52 / R53 each have a small piece of this logic.
- **Test CWE-89 Juliet getter patterns**: run a Juliet sub-sweep
  filtered to getter-shaped `h.setData(u); ...; h.getData();`
  patterns to measure the FP/TP delta. The current R10 baseline
  doesn't have getter-shaped Juliet test cases in scope, so R53's
  contribution is shown by the integration test (4/4) rather than
  by the Juliet sweep.
- **R53-GC**: cross-TU getter propagation — when the setter is in
  one TU and the getter is in another, the `crossFnClassFieldTaint`
  should still see the taint. The current R53 fix is TU-local; the
  global-store path uses the same static maps so it inherits the
  propagation, but a follow-up integration test on the
  `GlobalCallGraphBuilder` path would confirm.
