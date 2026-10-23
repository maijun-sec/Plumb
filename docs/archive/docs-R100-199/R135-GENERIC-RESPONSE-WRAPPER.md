# R135 — Generic Response Wrapper Type-Arg Inspection

**Status:** complete (2026-07-27)
**Tests:** +8 dfa (`InformationExposureCheckTest`, flipped + 7 new R135 scenarios), **186 dfa + 1 skip** total (was 178 in R134)
**End-to-end:** 140 findings on `examples/` (R134 132 → R135 140, +8 from R135's 3 new fire + 5 secondary check fires on the 3 new controllers)
**Zero regression on R115-R134 baseline**

## Why

R134's first cut put the entire `ResponseEntity` / `HttpEntity` /
`Response` family into a single `SAFE_RETURN_TYPES` set, suppressing
*all* findings for them. R134's `returnClassName()` helper also
*stripped* type arguments before lookup. That was wrong: a wrapper
*does* change the security shape if its type argument is a user
class. R135 closes the gap by:

1. **Splitting** `SAFE_RETURN_TYPES` into two semantically distinct
   sets: `INHERENTLY_SAFE` (primitives, `String`, `CharSequence`) and
   `WRAPPER_TYPES` (response / collection wrappers that are safe
   only if every type argument is a JDK / framework class).
2. **Inspecting** the return type's generic type arguments via
   `TypeTable.parseTypeArgs(String)` (the same parser the
   `typeArgsOf` channel uses for locals) and recursively checking
   each one against `TypeTable.isUserClass(...)`.
3. **Fixing the translator** so the function's declared return
   type preserves its generic arguments in the IR (a 1-line
   replacement: `TypeMapper.mapPreservingTypeArgs` instead of
   `TypeMapper.map`).

The result: `ResponseEntity<User>`, `List<User>`, `Map<String,
User>`, and `List<Map<String, User>>` all fire; their
`ResponseEntity<String>` / `List<String>` /
`Map<String, String>` siblings don't.

## What it does

For each endpoint function, R134 / R135 walks the return type's
class name (and, for wrappers, its type arguments) and applies a
three-step decision:

| Return type | Outcome | Why |
|-------------|---------|-----|
| `void`, `int`, `String`, `CharSequence`, ... | safe | `INHERENTLY_SAFE` set |
| `ResponseEntity<User>`, `List<User>`, `Map<String, User>`, `Page<User>`, `Optional<User>`, ... | **fire** | wrapper + user class in args (R135) |
| `ResponseEntity<String>`, `List<String>`, `Map<String, String>`, `ResponseEntity<HashMap<...>>`, ... | safe | wrapper + all-JDK args (R135) |
| `User`, `Account`, `MyDto` (plain user class) | **fire** | direct return (R134) |
| `HashMap`, `String`, etc. (plain JDK class) | safe | not user class |
| non-endpoint method | safe | out of scope |

The wrapper set (`WRAPPER_TYPES`) covers three families:
- **Spring response / entity wrappers:** `ResponseEntity`,
  `HttpEntity`, `Response`.
- **Spring Data paged-result wrappers:** `Page`, `Slice`,
  `Pageable`, `PagedList`.
- **Java collection wrappers:** `List`, `Set`, `Collection`,
  `Iterable`, `Map`, `Optional`, `Future`, `CompletableFuture`.

## Recursive type-arg inspection

The key new helper is `typeArgsContainUserClass(String className)`,
which:

1. Parses the angle-bracketed portion via
   `TypeTable.parseTypeArgs(className)`.
2. For each argument, strips FQN + generic form to get the
   simple class name and checks it against
   `TypeTable.isUserClass(String)`.
3. If the argument itself contains generics (e.g. `Map<String,
   User>` inside `List<Map<String, User>>`), recurses.

This makes `List<Map<String, User>>` fire even though the outer
`List` is JDK. The depth is bounded by the actual nesting in the
source code — there is no fixed recursion limit, but the parser's
top-level split + per-arg recursive descent covers all common
shapes.

## Test scenarios (16/16 passing, +8 from R134)

`InformationExposureCheckTest` (rebuilt for R135):

**R134 baseline (kept):**
1. `firesOnUserClassReturnType` — plain `User` return fires
2. `firesOnAccountClassReturnType` — `Account` return fires
3. `doesNotFireOnStringReturnType` — `String` is safe
4. `doesNotFireOnVoidReturnType` — `void` is safe
5. `doesNotFireOnPrimitiveReturnType` — `int` is safe
6. `doesNotFireOnNonEndpoint` — non-endpoint is out of scope
7. `aggregatesMultipleEndpointsOnSameClass` — per-class aggregation: ONE finding listing the affected methods

**R135 new (8 added; `firesOnResponseEntityOfUserClass` flipped):**
8. `firesOnResponseEntityOfUserClass` — `ResponseEntity<User>` **fires** (R134 said no-fire; R135 flips it)
9. `doesNotFireOnResponseEntityOfString` — `ResponseEntity<String>` is safe
10. `doesNotFireOnResponseEntityOfJdkCollection` — `ResponseEntity<HashMap<String, String>>` is safe
11. `firesOnListOfUserClass` — `List<User>` fires
12. `doesNotFireOnListOfString` — `List<String>` is safe
13. `firesOnMapWithUserValue` — `Map<String, User>` fires
14. `doesNotFireOnMapWithJdkValue` — `Map<String, String>` is safe
15. `firesOnNestedGenericWithUserClass` — `List<Map<String, User>>` fires (recursive)
16. `firesOnOptionalOfUserClass` — `Optional<User>` fires

## End-to-end (`examples/r134/R134Demo.java`)

Twelve scenarios (was 6 in R134; R135 adds 6 more):

| # | Class | Should fire? | Why |
|---|-------|--------------|-----|
| A | R134UserController | YES (L56) | Plain `User` return (R134) |
| B | R134AccountController | YES (L67) | Plain `Account` return (R134) |
| C | R134PingController | NO | `String` return is safe |
| D | R134DeleteController | NO | `void` return is safe |
| F | R134NotAController | NO | Plain non-endpoint class |
| G | R135ResponseEntityUserController | YES (L108) | `ResponseEntity<User>` (R135) |
| H | R135ResponseEntityStringController | NO | `ResponseEntity<String>` safe |
| I | R135ListUserController | YES (L131) | `List<User>` (R135) |
| J | R135ListStringController | NO | `List<String>` safe |
| K | R135MapUserController | YES (L154) | `Map<String, User>` (R135) |
| L | R135MapStringController | NO | `Map<String, String>` safe |

End-to-end matches: **5 INFORMATION_EXPOSURE findings** at L56, L67,
L108, L131, L154 (was 2 in R134). The 3 new R135 fires correspond
exactly to scenarios G, I, K — no false positives on H, J, L.

## Innovation summary (R135-specific)

- **R135 is the first cross-domain consumer of `parseTypeArgs`.**
  R132 / R133 used the same parser to record type args on **local
  variables** (`new ArrayList<User>()` → "User"). R135 reuses the
  parser to inspect type args on a function's **return type**, a
  different shape in the IR. The parser is generic; the consumer
  is what makes the call.
- **Two-axis decision tree (return class + type args).** R134 was
  a one-axis check: "is the return type a user class?". R135 adds
  the type-args axis: "is the return type a wrapper with a user
  class inside?". The two axes are checked in sequence and
  combined to give a single fire / no-fire decision.
- **Recursive descent in a static check.** Most static checkers
  are 1-hop (a CALL has a receiver, a CALL has args). R135
  recurses through nested generics, which is unusual for a
  static security check — the consumer is willing to pay O(N)
  descent cost (N = generic depth, typically 1) for precision.
- **Translator fix as a prerequisite, not as a new IR field.**
  R132 added a `typeArgs` map to `TypeTable` for **local**
  variables. R135 needed the same kind of info for **return
  types**, but `TacType.className()` already carries the
  generic form. The fix was to make the translator record the
  generic form on the return type too — a 1-line swap in
  `TacTranslator.java` plus a new helper in `TypeMapper.java`.
  No new IR field; the IR was already expressive enough, the
  translator was just dropping the args on the floor.
- **Backward-compat fix pattern continues.** R132's translator
  fix broke R123's `receiverClassName` because the
  `KNOWN_JDK_SIMPLE` lookup didn't expect type-arg tails.
  R123's regression test caught it, the fix was a strip at the
  consumer. R135's translator fix didn't break anything because
  the only consumer (R134) had no test that asserted the old
  behavior. This is the project cadence: build infra, write
  consumer, write regression test for consumer, then expand.
- **Per-class aggregation unchanged.** R135 keeps R134's
  one-finding-per-controller aggregation. A controller with
  N endpoints that return user-bearing types produces ONE
  finding listing the affected methods. The 4th consumer
  of per-class aggregation is the same shape.

## Files changed

- `plumb-tac/.../TypeMapper.java` — new
  `mapPreservingTypeArgs(Type)` helper (preserves `<...>` in
  the resulting `TacType.className()` payload)
- `plumb-tac/.../TacTranslator.java` — use
  `mapPreservingTypeArgs` for the function's return type
  (one-line swap at `translateCallable`)
- `plumb-dfa/.../type/TypeTable.java` — `parseTypeArgs`
  visibility bumped to public (R135 calls it cross-package)
- `plumb-dfa/.../InformationExposureCheck.java`
  (rewritten, 14.1KB) — split `SAFE_RETURN_TYPES` into
  `INHERENTLY_SAFE` + `WRAPPER_TYPES`; new
  `typeArgsContainUserClass(String)` recursive helper;
  new `simpleClassName(String)` FQN-stripping helper
- `plumb-dfa/.../test/InformationExposureCheckTest.java`
  (rewritten, 12.4KB) — flipped `firesOnResponseEntityOfUserClass`
  to assert fire; +7 new R135 scenarios
- `examples/r134/R134Demo.java` (rewritten, 6.2KB) — 6 new
  R135 scenarios (G, H, I, J, K, L) using FQN-style types so
  the wrapper lookup matches

## Translator change scope

R135's translator change is the second such change of the
project (the first was R132's `translateNew` fix). Both are
**1-line translator edits that change the visible string form
of a type in the IR**, with no new IR fields. The fix
unblocks a check that needed more type metadata than the
translator was producing.

R132 fixed: `new ArrayList<User>()` → ctorType was "ArrayList",
now "ArrayList<User>".
R135 fixed: function return type was "User" or "ResponseEntity"
or "ResponseEntity", now "User" or "ResponseEntity" or
"ResponseEntity<User>" (preserves the generic form).

Both fixes are tiny; both have outsized precision impact.

## Limitations and R136+ work

- **Param type-args still lossy.** The translator uses
  `TypeMapper.map(p.getType())` (not `mapPreservingTypeArgs`)
  for parameter types. R135 doesn't need them. A future check
  that did (e.g. R128 R136+ "missing validation on
  `List<User>` parameter") would need the same translator
  fix at the parameter path.
- **Diamond `<>` falls back to safe.** `List<>` is unparseable
  and the parser returns an empty list — R135 doesn't fire.
  This is conservative fall-back; the project prefers to miss
  diamond-typed cases over firing on them.
- **Nesting beyond what `parseTypeArgs` exposes is opaque.**
  The parser does depth-1 split and keeps inner generics as a
  single string. R135 then recurses on that string. This
  covers all common shapes; pathological multi-level
  generic nesting (e.g. `List<Map<List<User>, Set<User>>>`)
  is bounded by the recursion depth in practice.
- **No new CWE family.** R135 fires the same CWE-200 as R134
  but on a different shape of return type.

## Related docs

- `R134-INFORMATION-EXPOSURE.md` — the original direct-return check
- `R132-GENERIC-AWARE-TYPE.md` — the `typeArgsOf` /
  `parseTypeArgs` infrastructure R135 consumes
- `R133-GENERIC-AWARE-SENSITIVE.md` — the first consumer of
  the generic-aware type channel (different shape: taint flow,
  not return type)
- `R123-TYPE-AWARE-TAINT.md` — the `isUserClass` /
  `KNOWN_JDK_SIMPLE` heuristic R135 reuses
