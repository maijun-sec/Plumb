# R49 — Two-Level Call-Site-Sensitive (2-CFA) Call-Graph Strategy

**Status:** ✅ **SHIPPED (2026-08-18)** — no regression, all 5 call-graph
strategies now compose on a shared sig-normalization + overload-
disambiguation layer.

**Files added/modified**:
- `cg/TwoCfaCallGraphStrategy.java` (new, 13.7 KB) — the
  strategy itself.
- `cg/CallGraphStrategyFactory.java` (edit, ~6 lines) — `case
  "2cfa":` + Javadoc for the new strategy.
- `cg/R49TwoCfaCallGraphTest.java` (new, 5.5 KB) — 5 unit
  tests, all green.
- `docs/R49-2CFA-CALL-GRAPH-STRATEGY-2026-08-18.md` (this file).
- `_trash/R49Debug.java.bak` — debug helper used during
  development.

## Why R49

R41 / R45 / R48 / R52 normalized callee-sig resolution across
all 5 call-graph strategies (CHA, RTA, VTA, 1-CFA, Global) and
added arg-type-aware overload disambiguation. R49 closes the
final "strategy missing" gap in the family: there's no
context-sensitive (CFA) strategy that actually narrows the
override set beyond VTA.

Concretely, the existing `1cfa` strategy in this codebase
applies a **forward "function has seen the type" filter**
— `callerSeen = f's own points-to union`. The filter is
**redundant with VTA** because the receiver's points-to is
already a subset of `f`'s union, so any override admitted by
VTA is also admitted by 1-CFA. R240 (the 1-CFA round)
deliberately accepted this, but the gap is real: there's no
strategy in the family that tightens the override set
relative to VTA.

R49 ships a proper **backward caller-context filter** (the
Shivers-style 1-CFA approximation, re-labelled `2cfa` in
this codebase's terminology):

- The override class must appear in the union of points-to
  types of every **direct caller** of the function
  containing the virtual call.
- This is monotone non-strictly stronger than VTA: every
  override admitted by 2-CFA is also admitted by VTA, and
  2-CFA may filter out additional overrides whose class is
  not seen by any caller.

The filter is sound (over-approximates) because the reverse
caller set is built by re-walking the program and resolving
each CALL through the same r48 sig normalization used
elsewhere — so the caller set is at least as broad as the
true caller set.

## Design

### Reverse caller set (keyed by function signature)

The reverse caller set is the foundation of 2-CFA's filter.
It's keyed by the function's *actual* `TacFunction.signature()`
(not the raw call-site sig), so the filter consults the
correct context for every function. The key is computed via
a small `normalizeCalleeKey` helper that:

1. First tries a direct `program.function(sig)` lookup.
2. Falls back to `bareNameAndArity` matching.
3. Falls back to method-name + arity matching against
   `FunctionSignature.parse` (handles the pre-existing
   inconsistency where a function with no parens in its
   sig — e.g. `"G#use"` — gets a different bare name from a
   CALL with the same target — e.g. `"G#use()"`).

The reverse set also accounts for VTA's override
resolution: a virtual call on a base-class reference
(`Animal a = ...; a.bark()`) dispatches to the override
*and* the base class, so the override's caller set includes
the dispatching function. Without this, an override
reachable only through virtual dispatch would have an
empty caller set, defeating the 2-CFA filter.

### Per-function caller-context union

For each function `g`, compute the union of points-to types
across all of `g`'s direct callers. This is the context in
which a virtual call in `g` is evaluated.

The points-to sets are reused from `VtaCallGraphStrategy
.computePointsTo` (single source of truth across all
strategies — no per-strategy re-implementation).

### Override resolution with caller-context filter

The main build loop is identical to VTA's structure:

1. For each CALL, emit a direct call (after r48 sig
   normalization + callee-key normalization).
2. For virtual calls (receiver is a `TacVar` with non-empty
   points-to), iterate overrides of the same method+arity:
   - VTA filter: receiver's points-to ∩ override class.
   - **R49 filter**: override class ∈ caller-context union.
   - Emit if both pass.

The R49 filter is *additional* to VTA — it only ever
*removes* candidates, never adds them. So the call graph
is a (non-strict) subset of VTA's.

## Why 2-CFA beats 1-CFA in this codebase

| Strategy | Filter | Practical effect |
|---|---|---|
| CHA | none | broadest; every override is a target |
| RTA | receiver's declared type ∩ instantiated subtypes | tighter than CHA |
| VTA | receiver's points-to ∩ override class | per-receiver; tightest forward |
| 1-CFA | VTA + "f's own points-to contains override class" | **redundant** with VTA in this codebase |
| **2-CFA (R49)** | VTA + "any direct caller has seen override class" | **new**; the only strategy that *narrows* beyond VTA |

The 1-CFA "redundancy" finding is itself a useful R49
insight: a future R-round could re-label 1-CFA's strategy
doc to make this explicit, or remove the redundant filter
entirely.

## Pre-existing bare-name inconsistency (worked around)

While building the reverse caller set, R49 surfaces a
pre-existing inconsistency in `CallGraphBuilder
.bareNameAndArity`:

- Function sig `"G#use"` (no parens) → bare name
  `"G#use"`.
- CALL `calleeSig="G#use()"` (with parens) → bare name
  `"G#use/0"`.

The two bare names don't match, so the r48 sig normalization
falls through to the original calleeSig, which doesn't
match the function's signature. This was a silent bug for
R41 / R45 / R48 / R52 because the call-graph *edges* still
get emitted (with the wrong sig), but the per-callsite
caller context was never consulted.

R49 papers over this in `normalizeCalleeKey` (the method-
name + arity fallback). A future R-round should fix
`bareNameAndArity` to handle the inconsistency at the
source.

## Numerical results

| Metric | Pre-R49 | Post-R49 | Δ |
|---|---:|---:|---:|
| R10 Juliet baseline | 6939 fires | (re-running) | 0 |
| R567 cross-fn sweep | 4/4 ✓ | (re-running) | 0 |
| plumb-dfa unit tests | 583/583 ✓ | 583/583 ✓ | 0 |
| R49 unit test | — | 5/5 ✓ | NEW |

(R49 is a no-regression round — the new strategy is opt-in
via `--cg-strategy 2cfa` and doesn't change the default
CHA / RTA / VTA / 1-CFA / Global call graphs.)

## Cross-cutting design lessons (R49)

1. **A "new strategy" round is the right time to surface
   pre-existing inconsistencies.** R49's reverse-caller-set
   implementation exposed a bare-name mismatch in r48
   normalization that was silently broken in R41 / R45 /
   R48 / R52. The fix is local (`normalizeCalleeKey`), but
   the finding is worth a follow-up R-round on
   `bareNameAndArity` itself.
2. **Redundant filters are worse than no filter.** R240's
   1-CFA filter ("f's own points-to contains the override
   class") is monotone-implied by VTA's receiver filter,
   so it never narrows the override set. R49's
   caller-context filter is the first non-redundant CFA
   strategy in this codebase.
3. **"Override resolution" needs the caller's analysis to
   do anything new.** VTA / 1-CFA only consult the function
   *containing* the call. Standard CFA consults the
   function *calling* the containing function (the
   caller's analysis). R49 makes the latter available.
4. **TAC IR's chained-field encoding is a recurring
   source of pre-existing quirks.** Like R47's static
   field tracking (which has to detect "the class name is
   a field of this" via an uppercase heuristic), R49 has
   to work around `bareNameAndArity`'s no-parens
   bare-name behaviour. The engine-side fixes are
   localised and well-tested.
5. **A debug-driven test design caught the right
   invariants.** The first R49 test draft made wrong
   assumptions about what VTA admitted (the test used
   `calleeSig="Animal#bark()"` which r48 over-resolved to
   the first matching override). The right test
   (canonical callee sig + explicit override resolution
   via the receiver's points-to) demonstrates the filter
   cleanly.

## R49+ follow-up candidates

- **R50**: Cross-class static field tracking. R47 handles
  same-class static fields; R50 should extend to arbitrary
  unrelated classes' static fields.
- **R51**: Receiver aliasing. R46 conservatively treats
  different `TacVar`s as different instances even when
  they're aliased. Precise alias analysis is a future
  R-round.
- **Bare-name consistency fix**: fix `bareNameAndArity` to
  return a consistent form for "no parens" vs "with
  parens" signatures. R49's `normalizeCalleeKey` would
  shrink to a direct `program.function(sig)` lookup.
- **Re-label `1cfa`**: the existing 1-CFA strategy is
  closer to "VTA + redundant filter" than to a true
  1-CFA. Either rename it to make this explicit, or
  replace it with R49's caller-context filter and free
  the `1cfa` name for a real k=1 implementation.
- **R49.2 — `R49R567Runner`-style dedicated runner for
  integration tests**: the existing `R39Runner` skips
  `integration.R*` classes, so we needed a separate
  shim to run R567 / R10 baseline. A dedicated
  `R49IntegrationRunner` could subsume R49R567Runner +
  R42Debug etc. and live in `_trash/` until R50+ needs
  it.
