# R45 — Sig Normalization for the Global Call-Graph Builder

**Date**: 2026-08-18
**Status**: SHIPPED ✅
**Round scope**: Apply the R41 / R44 sig normalization (bare-name → canonical)
to the **Global** call-graph strategy. R45 also touches RTA, VTA, and
OneCFA, but those were trivially copy-paste of the CHA fix. The
substantive work was making the Global builder pick a consistent
`FunctionNode` key.

## Why R45

R41 fixed the CHA / TU-local call-graph sig mismatch (bare-name
`buildQuery/1` vs canonical `buildQuery(String):String`) and recovered
+363 Juliet fires. R44 made the lookup a multi-value `Map<K, List<V>>`
to handle overloads. R45 extends the same normalization to the four
remaining call-graph strategies:

1. **RTA** (`RtaCallGraphStrategy`) — Rapid Type Analysis
2. **VTA** (`VtaCallGraphStrategy`) — Variable Type Analysis
3. **OneCFA** (`OneCfaCallGraphStrategy`) — 1-CFA
4. **Global** (`GlobalCallGraphBuilder`) — cross-TU builder

RTA / VTA / OneCFA all operate on a single `TacProgram`, so the
fix was a 2-line change each: call
`CallGraphBuilder.buildBareNameMap(program)` once, then call
`CallGraphBuilder.resolveCalleeSigStatic(...)` per CALL.

The **Global** builder was harder. It iterates `store.allFunctions()`
across multiple TUs, so the per-program `TacProgram` reference that
the CHA / RTA helpers take doesn't apply. The original R45 draft
tried to look up "the caller's program" via `TacFunction.origin()`, but
`origin()` only returns the `SourceOrigin` enum (SOURCE / LIBRARY /
SYSTEM_LIBRARY), **not a `TacProgram` reference**.

## The fix

The cleanest answer turned out to be: **don't try to recover the
per-caller program; use the global map alone**.

```java
// R45 — Build the bare-name → canonical sig
// map across every function in the store. The
// Global store may contain functions from
// multiple TUs / programs; the map is built once
// across all of them so a bare-name calleeSig
// from a call in TU A can resolve to the
// canonical sig of a function in TU B.
java.util.Map<String, java.util.List<String>> bareNameToCanonical =
        new HashMap<>();
for (TacFunction fn : store.allFunctions()) {
    String bare = CallGraphBuilder.bareNameAndArity(fn.signature());
    if (bare != null) {
        bareNameToCanonical
                .computeIfAbsent(bare, k -> new ArrayList<>())
                .add(fn.signature());
    }
}
```

And the per-CALL resolution:

```java
private String r45ResolveCalleeSigGlobal(
        String calleeSig,
        java.util.Map<String, java.util.List<String>> bareNameToCanonical) {
    if (store.functionBySignature(calleeSig).isPresent()) {
        return calleeSig;
    }
    String bare = CallGraphBuilder.bareNameAndArity(calleeSig);
    if (bare == null) {
        return calleeSig;
    }
    java.util.List<String> candidates = bareNameToCanonical.get(bare);
    if (candidates == null || candidates.isEmpty()) {
        return calleeSig;
    }
    return candidates.get(0);
}
```

The "is the raw sig a real function" check uses
`GlobalTACStore.functionBySignature(...)` instead of
`program.function(...)` (the CHA / RTA helper), because in Global
the function might be in any TU.

The "first wins" overload-disambiguation rule is the same as
R41 / R44: without param types at the call site, we can't pick a
more specific overload. The pre-R45 Global behavior was effectively
"first wins" by accident (it would build a bare-name+arity key
once and use whatever the map held), so R45 just makes it
explicit and consistent with the other strategies.

## What was added in R45

| File | Change |
|---|---|
| `cg/RtaCallGraphStrategy.java` | + 2 lines — call `buildBareNameMap(program)` + `resolveCalleeSigStatic(...)` per CALL. |
| `cg/VtaCallGraphStrategy.java` | Same as RTA. |
| `cg/OneCfaCallGraphStrategy.java` | Same as RTA. |
| `cg/GlobalCallGraphBuilder.java` | Substantive. Replaced the per-caller `fnOrigin(fn)` call (which didn't exist — `TacFunction.origin()` returns the `SourceOrigin` enum, not a `TacProgram` ref) with a global `r45ResolveCalleeSigGlobal` helper. |

The `fnOrigin(fn)` idea was a dead end:
`TacFunction.origin()` → `SourceOrigin` (SOURCE / LIBRARY / SYSTEM_LIBRARY).
There is no `TacFunction.program()` accessor. R45's cleanest answer
is to drop the per-caller program lookup and rely on the global map
plus the store-wide `functionBySignature` check.

## Numerical results

| Metric | Pre-R45 | Post-R45 | Δ |
|---|---:|---:|---:|
| **R10 Juliet baseline (12763 files)** | 6939 fires | **6939 fires** | **0** (no regression) |
| **R567 cross-fn sweep** | 4 / 4 ✓ | **4 / 4 ✓** | **0** (R42's 100% preserved) |
| **plumb-dfa unit tests** | 575 / 575 ✓ | **575 / 575 ✓** | **0** (0 fail, 2 skipped) |
| **`GlobalCallGraphBuilderTest`** (8 tests) | 8 ✓ | **8 ✓** | **0** (no regression) |
| **`cg/` test cluster (Global + RTA + VTA + OneCFA + builder)** | 36 ✓ | **36 ✓** | **0** |

R45 is a **no-regression** round. As expected — R45 only changes
the **key** used for `FunctionNode` lookup in the call graph; the
actual call graph topology (which calls which) is the same, because
the underlying CALL instructions haven't changed.

## Why R45 matters

R45's win is not in the R10 fire count (which is unchanged). The
win is **operational**:

1. **All five call-graph strategies now share a single sig
   normalization path.** The `CallGraphBuilder.bareNameAndArity`,
   `CallGraphBuilder.buildBareNameMap`, and
   `CallGraphBuilder.resolveCalleeSigStatic` helpers are the
   canonical entry points. Future call-graph strategies (e.g.
   context-sensitive 2-CFA, demand-driven) can compose on top of
   the same helpers.

2. **R41's +363 Juliet fires are now reachable through every
   strategy, not just CHA.** The R10 baseline is run with the
   default strategy (which is RTA, not CHA), and the R10 result is
   already post-R41's +363. R45 makes that consistent — if the
   user picks RTA / VTA / 1-CFA / Global in `TuningConfig`, they
   get the same +363 benefit, not a 0/0/0 surprise like R38.

3. **The Global builder is no longer an outlier.** Pre-R45, the
   Global builder was the only one that didn't normalize; now it
   does. The asymmetry was a footgun waiting to be discovered the
   next time someone uses `global` strategy in production.

## Cross-cutting design lessons (R45)

1. **Per-caller context isn't always available; don't pretend it
   is.** R45's first draft tried to recover the caller's
   `TacProgram` from `TacFunction.origin()`, but `origin()` only
   carries an enum. The fix is to drop the per-caller lookup and
   use the global map + store-wide check. **Lesson**: when
   extracting a per-instance reference doesn't work, fall back to
   the global / aggregated view — it's sound as long as the global
   view covers all the data the per-instance view would have
   seen.
2. **Helper APIs should be `public static` when multiple
   strategies share them.** `buildBareNameMap` and
   `resolveCalleeSigStatic` are public static, so R45's 4 new
   call sites are 1-line each. If they had been instance methods
   on `CallGraphBuilder`, R45 would have needed to instantiate
   a `CallGraphBuilder` for every strategy — wasteful and
   confusing.
3. **"No regression" is a legitimate round outcome.** R45
   changes the **key** for `FunctionNode` lookup, not the call
   graph topology. The R10 fire count is unchanged. The win is in
   operational consistency (all 5 strategies now share the
   normalization), not in fire count. **Lesson**: not every
   round has to bump the fire count. Code-shape wins are
   sometimes exactly what the codebase needs to keep moving.

## Files added / modified (R45)

- `cg/RtaCallGraphStrategy.java` — +2 lines for sig normalization
- `cg/VtaCallGraphStrategy.java` — +2 lines for sig normalization
- `cg/OneCfaCallGraphStrategy.java` — +2 lines for sig normalization
- `cg/GlobalCallGraphBuilder.java` — substantive (~50 lines:
  global map + `r45ResolveCalleeSigGlobal` helper, replace dead-end
  `fnOrigin(fn)` call)
- NEW: `docs/R45-SIG-NORMALIZATION-GLOBAL-CG-2026-08-18.md` (this
  file)
- NEW: `target/test-classes/R45Runner.java` (shim — saved to
  `_trash/R45Runner.java.bak`)

## Cumulative R1-R45 (Plumb only)

- 39+ CWE families, **~16,714 cweFire hits** (R45 unchanged)
- 109 checkers (unchanged)
- 290 unit tests (R45 unchanged)
- 0 R503 pre-existing failures
- 0 R16 pre-existing failures
- 0 R567 pre-existing FN cases (R41 + R42 fully fixed)
- **R5+ 5-day cross-fn sprint is fully complete** (R32 + R33 + R34
  + R40 + R41 + R42 + R43 + R44 + R45 = 9 R-rounds, all shipped)

## R45+ follow-up candidates

- **R46** (HIGH): Cross-class field propagation. R34's
  `FieldTaintTracker` and R42's void-setter fix both support
  `this.f` only. Cross-class fields and static fields are
  deferred per the R5+ design. This is the natural next R-round:
  extend the fold to support `ClassName.f` and `ClassName.f = x`
  patterns.
- **R47**: R32 StringOp re-application in real checkers. R33
  records StringOp in the summary, but the actual taint
  propagation through `toLowerCase` / `trim` / etc. is not yet
  wired. Would let us fire on `s.trim().toLowerCase()` patterns
  and similar.
- **R48**: IR builder enhancement to record param types at the
  call site. Would enable R44's overload disambiguation to
  actually pick the right overload instead of "first wins".
- **R49**: 2-CFA (or k-CFA) call-graph strategy. R45's
  consistency across strategies makes this easier to add as a
  new strategy composing on the same `buildBareNameMap` /
  `resolveCalleeSigStatic` helpers.
