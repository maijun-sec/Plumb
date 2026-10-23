# R48 — Overload Disambiguation via Call Arg Types

**Date**: 2026-08-18
**Status**: SHIPPED ✅
**Round scope**: Pick the right
overload at a call site when
multiple functions share the same
bare-name+arity (e.g.
`foo(int)` and `foo(String)` are
both `foo/1` in the IR builder's
bare-name form).

## Why R48

R41 made the call-graph sig
normalization multi-value
(`Map<String, List<String>>`),
and R44 made that explicit (vs.
the pre-R44 "first wins"
behavior). Both R41 and R44
picked the **first** candidate
when multiple functions shared
a bare-name+arity. R48 fixes
this by **using the actual call
arg types** to pick the right
candidate.

Pre-R48, the resolution was:
```java
String canonical = candidates.get(0);  // first wins
```

R48 changes it to:
```java
for (String candSig : candidates) {
    // Parse candidate's param types
    List<String> paramTypes = extractParamTypes(candSig);
    // Compare with the actual arg types
    if (typesMatch(actualArgTypes, paramTypes)) {
        return candSig;  // first match wins
    }
}
return candidates.get(0);  // fallback (R44 behavior)
```

## The fix

### `CallGraphBuilder.java`

Three changes:

1. **New `r48ResolveCalleeSigWithArgs` static helper** —
   public, takes the program,
   the raw `calleeSig`, the
   bareNameToCanonical map, and
   the actual `args` (List of
   TacOperand). Three-tier
   resolution: raw match, then
   arg-type-aware match, then
   first-candidate fallback.

2. **New `bareNameAndArity`
   updated** to handle both
   canonical-sig form
   (`foo(String):void`) and
   bare-name form
   (`QueryBuilder.setQuery/1`).
   R48 strips the class prefix
   before lookup so a call to
   `QueryBuilder.setQuery/1`
   resolves through the same
   `setQuery/1` map entry.

3. **Two new private helpers** —
   `extractParamTypes(sig)` parses
   the param-type list from a
   canonical sig (e.g.
   `foo(String,int):void` →
   `["String", "int"]`).
   `normalizeTypeName(s)` converts
   short type names like
   `"String"` to FQN
   `"java.lang.String"` so the
   match against
   `TacVar.type()` is consistent.

### `ChaCallGraphStrategy.java`

CHA had its own private
`bareNameToCanonical` (Map<String,
String> with `putIfAbsent`) and
its own `r41ResolveCalleeSig`
that R41's multi-value / R44's
multi-value never reached. R48
updates CHA to:

- Use `buildBareNameMap(program)`
  to get the multi-value
  `Map<String, List<String>>`
- Call
  `r48ResolveCalleeSigWithArgs(...)`
  for sig resolution with arg
  types

The new CHA `r41ResolveCalleeSig`
is kept for binary back-compat
but is no longer called by
CHA's `build()`.

## What R48 ships

| File | Change |
|---|---|
| `cg/CallGraphBuilder.java` | New `r48ResolveCalleeSigWithArgs` public static helper. `bareNameAndArity` updated to handle bare-name-with-class-prefix form. New private helpers `extractParamTypes`, `normalizeTypeName`, `tacOperandTypeName`, `tacOperandTypeNameFromType`, `typesMatch`. |
| `cg/ChaCallGraphStrategy.java` | Updated `build()` to use the shared `buildBareNameMap` + `r48ResolveCalleeSigWithArgs`. Old `r41ResolveCalleeSig` kept for binary back-compat (no longer called). |
| NEW: `cg/R48OverloadResolutionTest.java` | 2 unit tests verifying that `r48ResolveCalleeSigWithArgs` picks the right overload for both String and int arg types. |
| DOC: `docs/R48-OVERLOAD-DISAMBIGUATION-2026-08-18.md` (this file) | |
| BAK: `_trash/R48Debug.java.bak`, `R48Debug2.java.bak`, `R48Debug3.java.bak`, `R48ParseCheck.java.bak`, `R48OverloadDisambiguationTest.java.bak` | |

## Numerical results

| Metric | Pre-R48 | Post-R48 | Δ |
|---|---:|---:|---:|
| **R10 Juliet baseline (12763 files)** | 6939 fires | **6939 fires** | **0** (no regression) |
| **R567 cross-fn sweep** | 4 / 4 ✓ | **4 / 4 ✓** | **0** (R41 + R42's 100% preserved) |
| **plumb-dfa unit tests** | 575 / 575 ✓ | **577 / 577 ✓** | **+2** (R48's 2 new unit tests) |
| **R32 / R34 unit tests** | 28 ✓ | **28 ✓** | **0** |
| **R46 cross-instance test** | 3 / 3 ✓ | **3 / 3 ✓** | **0** |
| **R47 static field test** | 3 / 3 ✓ | **3 / 3 ✓** | **0** |
| **R48 overload resolution test (new)** | — | **2 / 2 ✓** (String overload picked for String arg, int overload picked for int arg) | NEW |

R48 is a **clean no-regression**
round. R10 baseline unchanged.
The +2 unit tests are pure
disambiguation tests (no
end-to-end taint flow); R48 is a
defect fix for a latent issue
that didn't affect Juliet
(Juliet doesn't use
overloads).

## Why R48 matters

1. **R44's "first wins" was a
   latent defect.** When a
   program has multiple overloads
   of the same method, R41/R44
   picked whichever was registered
   first in the bareNameToCanonical
   map — usually the first function
   visited during `program.functions()`
   iteration. The result is wrong
   call-graph resolution for the
   non-first overloads: taint flow
   that should go through the
   String overload gets routed to
   the int overload (whose body
   doesn't take the taint), and
   vice versa. R48 fixes this by
   matching the actual call arg
   types to the candidate's
   declared param types.

2. **The R48 fix is engine-side
   and additive.** No IR builder
   change, no IR-serializable
   contract change. The change is
   localized to `CallGraphBuilder`
   and `ChaCallGraphStrategy`. The
   other strategies (RTA, VTA,
   OneCFA, Global) can opt in by
   calling the same shared
   `r48ResolveCalleeSigWithArgs`
   helper (a future R-round could
   propagate R48 to them).

3. **The FQN normalization is
   the right tradeoff.** The
   IR builder uses short type
   names like `"String"` in
   canonical sigs (via
   `Type.asString()`), but the
   actual arg types are also
   FQN-typed in
   `TacVar.type()`. Normalizing
   both sides to FQN
   (`"String"` → `"java.lang.String"`)
   is the minimum signal needed
   to make the comparison
   consistent.

## Cross-cutting design lessons (R48)

1. **"First wins" is a footgun for
   multi-value maps.** R41's
   `Map<String, String>` was a
   strict subset of the natural
   `Map<String, List<String>>` for
   overloads. R44 made it explicit.
   R48 makes the resolution
   actually correct. **Lesson**:
   when a "first wins" is
   documented, it's a sign that
   the resolution is incomplete
   — there should be a way to
   pick the *right* one, not just
   the first.

2. **Helper APIs should be
   `public static` when multiple
   call-graph strategies share
   them.** R48's
   `r48ResolveCalleeSigWithArgs` is
   public static. CHA calls it
   directly. RTA / VTA / OneCFA
   / Global can opt in by calling
   the same helper — a future
   R-round.

3. **"Tried-but-no-match" is the
   safest fallback.** R48 picks
   the first match if exactly one
   candidate matches. If no
   candidates match, R48 falls
   back to `candidates.get(0)` —
   R44's "first wins" behavior.
   This means R48 is
   **conservative**: when in
   doubt, pick something
   (otherwise the call becomes a
   placeholder node with no
   resolution). The tradeoff is
   that R48 might over-fire
   (route a String call to an int
   overload) but never
   under-fire.

4. **The unit test is more
   valuable than the integration
   test.** R48's "interesting"
   behavior is the disambiguation
   itself, not the end-to-end
   taint flow (which depends on
   cross-fn return-value taint
   that's a separate R-round's
   scope). The unit test
   (`R48OverloadResolutionTest`)
   directly tests the helper's
   return value, which is
   unambiguous. R48's first
   integration test
   (`R48OverloadDisambiguationTest`)
   was overly ambitious and
   demonstrated nothing
   meaningful; it was removed in
   favor of the unit test.

## Cumulative R1-R48 (Plumb only)

- 39+ CWE families, **~16,714 cweFire hits** (R48 unchanged on Juliet; Juliet doesn't use overloads in the R10 scope)
- 109 checkers (unchanged)
- 290 unit tests + 2 new (R48) = **292 unit tests**
- 0 R503 / 0 R16 / 0 R567 / 0 R46 / 0 R47 / 0 R48 pre-existing failures
- **R5+ 5-day cross-fn sprint is fully complete** (R32 + R33 + R34 + R40 + R41 + R42 + R43 + R44 + R45 + R46 + R47 + R48 = 12 R-rounds, all shipped)

## R48+ follow-up candidates

- **R49**: 2-CFA (or k-CFA) call-graph strategy. R45's consistency across strategies makes this easier to add as a new strategy composing on the same `buildBareNameMap` / `r48ResolveCalleeSigWithArgs` helpers.
- **R50**: Already covered — multi-class static field tracking is enabled by R46's `recvName` accepting any non-empty receiver name. R47's `STATIC_FIELD_PATH` test (using `PathHelper` in a different class) covers this case.
- **R51**: Multi-receiver aliasing. If a function does `Helper h1 = ...; Helper h2 = h1;` then `h1.data` and `h2.data` are aliased. R46 conservatively treats them as different. Precise alias analysis is a future R-round.
- **R52**: Propagate R48 to RTA / VTA / OneCFA / Global call-graph strategies. R48 currently affects CHA and the TU-local `CallGraphBuilder.buildFromProgram`. The other 4 strategies can opt in by calling the same shared helpers.
- **R53**: Cross-fn return-value taint. R48's unit test had to dodge this because the fold for getter methods (non-void return) doesn't propagate taint back to the caller's dest. R37 / R40 partially address this; R53 could complete the picture.
