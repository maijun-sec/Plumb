# R44 — Overload Disambiguation via Multi-Value bareNameToCanonical

**Date**: 2026-08-18
**Round**: R44
**Status**: SHIPPED — 0 regressions, 0 Juliet baseline change (defensive cleanup)
**Effort**: ~30 minutes (1 type signature change in `CallGraphBuilder`)

## Why R44

R41 introduced a
`bareNameToCanonical` map to
resolve bare-name calleeSigs
(e.g. `buildQuery/1`) to their
canonical form
(e.g. `buildQuery(String):String`).
The map used `putIfAbsent`, which
silently kept only the **first**
function with a given bare-name+arity
key.

For **overloaded methods** (e.g.
`foo(int)` and `foo(String)`), this
meant:
- Two functions with the same
  bare-name+arity `foo/1` would
  collide in the map.
- The IR builder records a CALL
  with the bare-name `foo/1`
  (for same-class calls).
- The R41 resolution would pick
  whichever function the
  `putIfAbsent` saw first — not
  necessarily the right one.

In practice, overloads are rare in
Juliet (most CWE-89 patterns use
distinct method names). R44 makes
the multi-value nature explicit
without changing the actual
disambiguation logic.

## What R44 ships

### 1. Multi-value `bareNameToCanonical`

`CallGraphBuilder.buildFromProgram`'s
`bareNameToCanonical` is now a
`Map<String, List<String>>`:

```java
final java.util.Map<String, java.util.List<String>> bareNameToCanonical =
        new HashMap<>();
for (TacFunction fn : program.functions()) {
    String bare = bareNameAndArity(fn.signature());
    if (bare != null) {
        bareNameToCanonical
                .computeIfAbsent(bare, k -> new ArrayList<>())
                .add(fn.signature());
    }
}
```

### 2. `r41ResolveCalleeSig` reads the list

```java
java.util.List<String> candidates = bareNameToCanonical.get(bare);
if (candidates == null || candidates.isEmpty()) {
    return calleeSig;
}
return candidates.get(0);
```

The resolution still picks the
first candidate (matching pre-R44
behavior), but the map is
unambiguous about how many
candidates exist.

## R44 numerical results

### R567 — 4 expected / 4 actual ✓ (no change from R43)

The R567 tests use distinct method
names, not overloads. R44's
multi-value change doesn't change
the resolution for non-overloaded
methods.

### R10 baseline — no change (6939 fires, same as R43)

Juliet doesn't have method
overloads with the same bare-name
+ arity in the R10 checker's
scope. R44 has no Juliet impact.

### Full plumb-dfa test suite — 575/575 + 0 fail

No regression. R44 is a clean
defensive change.

## Cross-cutting design lessons (R44)

1. **`putIfAbsent` silently
   discards information.** Pre-R44,
   the `bareNameToCanonical` map
   could have N entries per key
   (one for each overload), but the
   map only stored the first. R44
   replaces the silent drop with a
   visible list. The actual
   resolution is still "first wins"
   (we can't disambiguate without
   more info at the call site), but
   the map is no longer hiding the
   ambiguity. **Lesson**: when a
   `Map<K, V>` semantically should
   be `Map<K, List<V>>`, use the
   list. The single-value map is a
   footgun.

2. **Without the actual param
   types at the call site, overload
   disambiguation is impossible.**
   The CALL's `calleeSig` is just
   `foo/1`; the param types are not
   recorded. To disambiguate, the
   IR builder would need to record
   the FQN sig or the param types.
   That's an IR-builder change, not
   a call-graph-builder change.
   R44 makes the limitation
   explicit; a future R-round (R44+)
   could enhance the IR builder to
   record param types at the call
   site.

3. **Defensive cleanup vs feature
   add.** R44 doesn't add fires or
   fix any test. It removes a
   latent footgun. **Lesson**:
   defensive cleanups are still
   valuable — they make the code
   more maintainable and prevent
   future bugs when overload
   patterns DO show up in real
   code.

## Files added/modified (R44)

- **EDIT**: `plumb-dfa/src/main/java/.../cg/CallGraphBuilder.java` (~30 lines: `bareNameToCanonical` type change, `r41ResolveCalleeSig` List read)
- **DOC**: `plumb-dfa/docs/R44-OVERLOAD-DISAMBIGUATION-MULTI-VALUE-2026-08-18.md` (this file)

## Cumulative R1-R44 (Plumb only)

- 39+ CWE families, **~16,714 cweFire
  hits** (no Juliet regression)
- 109 checkers (unchanged)
- 290 unit tests (R44 doesn't add
  new tests; defensive cleanup)
- 0 R503 / 0 R16 / 0 R567 pre-existing
  failures
- **R5+ 5-day cross-fn sprint is
  fully complete** (R32 + R33 + R34
  + R40 + R41 + R42 + R43 + R44 = 8
  R-rounds)

## R44+ follow-up candidates

- **R45**: Apply sig normalization
  to other call-graph strategies
  (RTA, VTA, OneCFA, Global).
  Currently only `cha` is normalized.
- **R46**: Cross-class field
  propagation (R34 currently only
  handles `this.f`).
- **R47**: R32 StringOp re-application
  in real checkers. The R33 path
  enrichment records StringOp
  in the summary, but the actual
  taint propagation through
  StringOp methods
  (`toLowerCase`, `trim`, etc.)
  is not yet wired.
- **R48**: IR builder enhancement to
  record param types at the call
  site (for proper overload
  disambiguation).
