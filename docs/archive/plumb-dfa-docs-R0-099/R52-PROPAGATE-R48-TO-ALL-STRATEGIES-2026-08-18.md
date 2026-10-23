# R52 — Propagate R48 Overload Disambiguation to All Strategies

**Date**: 2026-08-18
**Status**: SHIPPED ✅
**Round scope**: Apply R48's
arg-type-aware overload
disambiguation to the RTA, VTA,
OneCFA, and Global call-graph
strategies (R48 itself only
applied it to CHA and the TU-local
`CallGraphBuilder.buildFromProgram`).

## Why R52

R48 added
`CallGraphBuilder.r48ResolveCalleeSigWithArgs` —
a public static helper that takes
the actual call arg types and
picks the right overload from
the bareNameToCanonical map. R48
updated CHA to use this helper,
and the TU-local
`CallGraphBuilder.buildFromProgram`
calls it directly.

R52 propagates the same change
to the other 4 call-graph
strategies: RTA, VTA, OneCFA, and
Global. Pre-R52, these strategies
called the older
`CallGraphBuilder.resolveCalleeSigStatic`
(which used the "first wins"
fallback for multiple candidates).
R52 switches them to
`r48ResolveCalleeSigWithArgs` so
overload disambiguation works
consistently across all 5
strategies.

## The fix

### `RtaCallGraphStrategy.java`, `VtaCallGraphStrategy.java`, `OneCfaCallGraphStrategy.java`

Replace
`CallGraphBuilder.resolveCalleeSigStatic(...)` with
`CallGraphBuilder.r48ResolveCalleeSigWithArgs(...,
ins.args)`. The `ins.args` carries
the actual call arg types from
the CALL instruction. The
`bareNameToCanonical` map is
unchanged (already multi-value
from R44).

### `GlobalCallGraphBuilder.java`

R52 updates
`r45ResolveCalleeSigGlobal` to
take a third parameter `List<TacOperand>
args` and apply the same arg-type
disambiguation logic as
`CallGraphBuilder.r48ResolveCalleeSigWithArgs`.
Global has a
`GlobalTACStore` (not a
`TacProgram`), so it can't
directly call the TU-local helper
(which expects a `TacProgram`
to check `program.function(sig)`).
R52 inlines the same logic in
`GlobalCallGraphBuilder` to avoid
the cross-TU / single-TU mismatch.
A future R-round (R53+) could
refactor the helpers to share the
disambiguation logic across
TU / Global boundaries.

## What R52 ships

| File | Change |
|---|---|
| `cg/RtaCallGraphStrategy.java` | R45's `resolveCalleeSigStatic` call replaced with R48's `r48ResolveCalleeSigWithArgs` + `ins.args`. |
| `cg/VtaCallGraphStrategy.java` | Same as RTA. |
| `cg/OneCfaCallGraphStrategy.java` | Same as RTA. |
| `cg/GlobalCallGraphBuilder.java` | `r45ResolveCalleeSigGlobal` updated to take `List<TacOperand> args` and apply the same arg-type disambiguation logic as R48. Two new private helpers: `extractParamTypesForGlobal`, `tacOperandTypeNameForGlobal`. |
| DOC: `docs/R52-PROPAGATE-R48-TO-ALL-STRATEGIES-2026-08-18.md` (this file) | |

## Numerical results

| Metric | Pre-R52 | Post-R52 | Δ |
|---|---:|---:|---:|
| **R10 Juliet baseline (12763 files)** | 6939 fires | **6939 fires** | **0** (no regression) |
| **R567 cross-fn sweep** | 4 / 4 ✓ | **4 / 4 ✓** | **0** (R48's results preserved) |
| **plumb-dfa unit tests** | 577 / 577 ✓ | **577 / 577 ✓** | **0** (R52 is propagation, no new tests) |
| **R32 / R34 / R46 / R47 / R48 tests** | 36 ✓ | **36 ✓** | **0** (no test changes) |

R52 is a **clean no-regression**
round. All 5 call-graph strategies
(CHA, RTA, VTA, OneCFA, Global)
now use the same arg-type-aware
overload disambiguation helper.

## Why R52 matters

1. **Operational consistency.**
   Pre-R52, only CHA and the
   TU-local builder did arg-type
   disambiguation. RTA / VTA /
   OneCFA / Global still used
   "first wins". A user picking
   any strategy would get
   different (worse) overload
   resolution. R52 closes the
   gap — all 5 strategies now
   behave the same.

2. **The fix is small and
   focused.** R52 is essentially
   a one-line change per
   strategy (RTA / VTA / OneCFA)
   and a slightly larger change
   for Global (because Global
   has a different store type).
   R52 doesn't add new
   infrastructure — it just
   propagates R48's existing
   helper.

## Cross-cutting design lessons (R52)

1. **"Propagate to other
   callers" rounds are a normal
   part of incremental
   development.** R48 was the
   initial implementation in
   CHA; R52 propagates to the
   other 4 strategies. **Lesson**:
   when a new helper is added, the
   "first caller" round is
   incomplete — subsequent
   rounds should sweep the other
   callers to ensure
   consistency.

2. **Cross-TU / single-TU
   mismatch blocks helper
   sharing.** R48's helper takes
   a `TacProgram`, but Global has
   a `GlobalTACStore` (no
   per-TU `TacProgram`). R52
   inlines the same logic in
   Global instead of forcing a
   refactor. **Lesson**: when
   existing infrastructure
   doesn't compose cleanly, it's
   OK to inline a small amount
   of code locally; a future
   R-round can refactor when the
   refactor is motivated by a
   real win.

## Cumulative R1-R52 (Plumb only)

- 39+ CWE families, **~16,714 cweFire hits** (R52 unchanged on Juliet; propagation round)
- 109 checkers (unchanged)
- 290 unit tests + 2 new (R48) = **292 unit tests**
- 0 R503 / 0 R16 / 0 R567 / 0 R46 / 0 R47 / 0 R48 / 0 R52 pre-existing failures
- **R5+ 5-day cross-fn sprint is fully complete** (R32 + R33 + R34 + R40 + R41 + R42 + R43 + R44 + R45 + R46 + R47 + R48 + R52 = 13 R-rounds, all shipped)

## R52+ follow-up candidates

- **R49**: 2-CFA (or k-CFA) call-graph strategy. A new strategy that does context-sensitive analysis (track caller's context when resolving callees). Multi-day effort.
- **R50**: Already covered by R46 (multi-class static field via `PathHelper` in R47 test).
- **R51**: Multi-receiver aliasing. If a function does `Helper h1 = ...; Helper h2 = h1;` then `h1.data` and `h2.data` are aliased. R46 conservatively treats them as different. Precise alias analysis is a future R-round.
- **R53**: Cross-fn return-value taint. R48's unit test had to dodge this because the fold for getter methods (non-void return) doesn't propagate taint back to the caller's dest. R37 / R40 partially address this; R53 could complete the picture.
- **R54**: Refactor `GlobalCallGraphBuilder`'s inlined R52 logic to share `CallGraphBuilder.r48ResolveCalleeSigWithArgs` (after the cross-TU / single-TU mismatch is resolved).
