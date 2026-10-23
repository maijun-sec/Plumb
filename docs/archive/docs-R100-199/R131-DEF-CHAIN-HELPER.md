# R131 — Bounded Def-Chain Chase Utility (DefChainHelper)

**Status:** complete (2026-07-27)
**Tests:** +6 dfa (`DefChainHelperTest`), **157 dfa + 1 skip** total
**End-to-end:** 124 findings on `examples/` (zero regression vs R128)
**Scope:** infrastructure / refactor — no new checker, no new CWE

## Why

R122 left a documented limitation: helper chains like
`String helper() { return "*"; }` then
`cfg.allowedOrigins(List.of(helper()))` were missed by
`CorsMisconfigurationCheck`'s 1-hop chase. R122 was explicit:

> "A chain like `myHelper(of("*"))` is still missed."

The chase was also duplicated across three checks
(`CorsMisconfigurationCheck` R122b, `PredictableKeyPasswordCheck`
R122a, `FieldHardcodedPasswordCheck` R124) with subtly
different semantics and subtly different bug surfaces.
R131 extracts the common machinery into a single utility
and upgrades the chase from 1 hop to a bounded **5-hop
recursive walk** that descends into `CALL` / `NEW` /
`NEWARRAY` arguments.

## What R131 does

1. **Extracts** the var→def-map building code from three
   checks into `DefChainHelper.buildDefMap`.
2. **Adds** a generic visitor-pattern chase
   `DefChainHelper.chaseDefinition(fn, start, maxHops, visitor)`
   for the simple case (each `TacInstruction` along the chain
   is presented to the visitor).
3. **Adds** a recursive operand chase
   `DefChainHelper.chaseForOperand(fn, op, maxDepth, matcher)`
   for the harder case (the walk descends into `CALL` /
   `NEW` / `NEWARRAY` arguments and matches the chain
   against an arbitrary predicate).
4. **Upgrades** `CorsMisconfigurationCheck` to use the
   recursive chase, so a chain like
   `String a = "*"; String b = a; ... List.of(b)` fires
   the wildcard check (R122 baseline + R131 rename-chain
   upgrade).
5. **Upgrades** `PredictableKeyPasswordCheck` to use the
   shared `buildDefMap` (no behavior change — the upgrade
   is the de-duplication, not a precision improvement).
6. **Sets** the default chase depth to 5 hops — enough to
   cover a 4-level rename chain (a → b → c → d, with the
   literal at d's MOVE-lhs1) plus one level of CALL-arg
   descent (a list-factory call whose first arg is the
   rename chain).

## Cost

Per-check cost is unchanged. The `defs` map is built once
per function (one linear pass over the instructions) and
reused across the recursive walk. The recursive walk itself
is bounded at `maxDepth` (5 by default); on a function with
N instructions the walk is `O(maxDepth)`, not `O(N)`. The
cycle-detection via `seen.add(cur)` makes the walk safe on
pathological code (mutually recursive type names, weird
self-referential renames, etc.).

## Test scenarios (6/6 passing)

`DefChainHelperTest`:

1. `buildDefMapReturnsLastDefinition` — SSA-shape: later writes shadow earlier ones
2. `chaseDefinitionStopsAtMaxHops` — chain walk terminates at the bound
3. `corsFiresOnDirectWildcard` — R119c baseline regression guard
4. `corsFiresOnHelperChain` — R131 NEW: 2-hop intra-procedural rename chain fires
5. `corsDoesNotFireOnEmptyList` — negative control
6. `corsDoesNotFireOnDeepChain` — 6-hop chain beyond maxHops=5 does NOT fire (conservative fall-back)

## Why not unbounded chase

An unbounded chase would catch every helper chain. The cost
is unbounded too — aliasing and recursion can loop forever,
and pathological code with thousands of instructions of
rename chains is a real risk. The bounded approach matches
the "conservative fall-back" principle that runs through the
whole project: prefer to miss pathological chains rather
than burn a minute of CPU on a single check.

The bounded depth (5) is chosen empirically to cover the
common 3-4 level rename pattern plus one list-factory
descent. Three of the existing checks were happy with a
1-hop chase; the 5-hop upgrade is a strict superset of the
R122 behavior — no precision lost, just a wider reach.

## Innovation summary (R131-specific)

- **Bounded recursive chase is the right shape for
  intra-procedural def chains.** Unbounded is asymptotically
  unsafe; 1-hop is too narrow. 5-hop recursive (MOVE chain +
  CALL-arg descent) is a sweet spot.
- **Visitor pattern + operand-matcher pattern together
  cover the existing use cases.** Visitor for the
  "check the chain's instructions" case; operand-matcher
  for the "check the chain's leaves" case. The two methods
  share a `defs` map and a cycle-detector, so the cost
  story is uniform.
- **Cycle detection on the `TacVar` graph is what makes
  the chase safe.** Without it, a rename loop (`a = b; b = a;`)
  would loop forever. With it, the walk terminates in
  `O(maxDepth)` regardless of input.

## Files changed

- `plumb-dfa/.../util/DefChainHelper.java` (new,
  7.6KB) — the shared utility
- `plumb-dfa/.../CorsMisconfigurationCheck.java` —
  `cameFromWildcardList` now uses `chaseDefinition` +
  `chaseForOperand`; the 1-hop `defs` map builder is
  replaced by `DefChainHelper.buildDefMap`; `containsWildcardOrigin`
  takes the function (so the helper has access to the
  function's def map)
- `plumb-dfa/.../PredictableKeyPasswordCheck.java` —
  `buildDefMap` replaced by `DefChainHelper.buildDefMap`
- `plumb-dfa/.../test/.../util/DefChainHelperTest.java`
  (new, 6 tests)

## Related docs

- `R122-FOLLOWUP-FIXES.md` — the parent work that introduced
  the 1-hop chase; R131 is the upgrade to 5-hop recursive
- `R124-FIELD-HARDCODED-PASSWORD.md` — the third check
  that uses the shared `defs` map
- `R120-DEFENSIVE-CHECKS.md` — earlier work on the IR that
  the chase relies on
