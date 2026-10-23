# R132 — Generic-Aware Type Inference (typeArgs)

**Status:** complete (2026-07-27)
**Tests:** +9 dfa (`TypeArgsTest`), **166 dfa + 1 skip** total
**End-to-end:** 124 findings on `examples/` (zero regression vs R131)
**Scope:** TypeTable extension — no new checker, but enables future
generic-aware checks

## Why

R117 built `TypeTable` with `receiverClassName(...)` (R123 wire-up
turned it into a live consumer). The check could distinguish
`User` from `HashMap` as a receiver, but it couldn't distinguish
`List<User>` from `List<HashMap>`. The pattern `getPassword/0` is
method-only — the same code path matches both — so a
`List<HashMap>.getPassword()` (which doesn't really exist) was
indistinguishable from `List<User>.getPassword()` (which the
developer almost certainly meant).

R132 closes the gap with a `typeArgs` channel on `TypeTable`. The
translator now records the textual form of the ctor type
(preserving the angle-bracketed type parameters), and
`TypeTable.typeArgsOf(fn, var)` returns the parameter list.
This is the first piece of generic-aware inference in the
project.

## What R132 does

1. **Translator fix**: `translateNew` now uses
   `oce.getType().asString()` (which preserves
   `ArrayList<User>`) instead of `TypeMapper.map(...)` (which
   simplified to `ArrayList`). The structured `TacType` is
   still used for the type-table key (`typeOf(...)`); only
   the ctor type's textual form is preserved.
2. **TypeTable extension**: a new `typeArgs: Map<String, Map<String, List<String>>>`
   tracks `(function-sig, var-name) → type-arg list`. Populated
   from `NEW` instructions whose ctor type contains
   angle-bracketed parameters. Propagated through `MOVE`
   (so a chain like `ArrayList<String> a = ...; ArrayList<String> b = a;`
   has the same type-arg list on both vars).
3. **`parseTypeArgs` static helper**: a string-level parser
   that turns `ArrayList<User>` into `["User"]`,
   `HashMap<String, User>` into `["String", "User"]`, and
   `List<Map<String, Integer>>` into `["Map<String, Integer>"]`
   (the inner angle brackets are kept as a single arg's
   content; we don't try to recursively parse them).
4. **`receiverClassName` compatibility fix**: with the
   translator now emitting `HashMap<>` instead of `HashMap`,
   the existing R123 receiver-class check needed to strip
   the type-arg portion before looking up
   `KNOWN_JDK_SIMPLE`. This was a regression-guard fix.

## Test scenarios (9/9 passing)

`TypeArgsTest`:

| # | Test | What it locks in |
|---|------|-----------------|
| 1 | `parseEmptyReturnsEmpty` | Plain names, FQNs, null all yield empty |
| 2 | `parseDiamondReturnsEmpty` | `ArrayList<>` has no type info |
| 3 | `parseSingleArg` | `ArrayList<User>` → `["User"]` |
| 4 | `parseMultipleArgs` | `HashMap<String, User>` → `["String", "User"]` |
| 5 | `parseNestedArgs` | `List<Map<String, Integer>>` → `["Map<String, Integer>"]` |
| 6 | `typeArgsOfParameterizedLocal` | End-to-end: `new ArrayList<String>()` round-trips |
| 7 | `typeArgsOfMapWithTwoArgs` | Multi-arg case |
| 8 | `typeArgsOfDiamondIsEmpty` | Diamond at the source side, not just the parser side |
| 9 | `typeArgsPropagatesThroughMove` | SSA-shaped: chain var `b = a` inherits the type-arg list |

Plus the regression guard: `TypeAwareSensitiveTest` (R123)
still passes with the new ctor-type shape.

## What R132 does **not** do

R132 is intentionally narrow:

- **No new check.** No checker consumes `typeArgsOf` yet.
  The channel is in place for R133+ (e.g. a future
  `MISSING_INPUT_VALIDATION` upgrade that distinguishes
  `List<User>` from `List<HashMap>`, or a
  `SENSITIVE_DATA_COLLECTION` check that flags
  `List<PasswordEntry>`).
- **No recursive parsing.** `List<Map<String, Integer>>` is
  returned as `["Map<String, Integer>"]` — the caller can
  recurse if it cares about the inner structure. The
  parser is intentionally one level deep.
- **No FQN-to-simple normalization.** The parser returns
  whatever the translator recorded; if the source uses
  `java.util.List<User>`, the result is `["User"]`
  (JavaParser strips the FQN at the parameter level,
  not the list type level). This is a minor limitation
  but matches what most catalogs need.

## Cost

Per-function analysis grows by one map (`typeArgs`) and one
hash-insert per `NEW` instruction whose ctor type has
type-args. The cost is `O(N)` in the function size, same
as the rest of `analyzeFunction`. The recursive
`parseTypeArgs` walks the string once, `O(L)` in the
type-name length.

## Innovation summary (R132-specific)

- **The ctor-type textual form was already in the IR — R132
  just preserved it.** `TacInstruction.ctorType.toString()`
  was previously `ArrayList` (simplified by `TypeMapper`).
  R132 makes it `ArrayList<User>` (preserved from the AST).
  The fix is one line in the translator; the new metadata
  is already flowing through every check that looks at
  `ctorType`.
- **The type-arg channel is a natural extension of the
  TypeTable pattern.** R117 designed the table to be a
  per-function metadata registry. R123 added a method
  (receiver class). R132 adds another method (type args).
  The cost of each new method is one map and one access
  path; the consumer-facing API is uniform.
- **Compatibility with the previous receiver-class check
  is a non-obvious fix.** Stripping type-args from
  `className()` before the JDK/user lookup was a
  regression-guard fix; the R123 baseline
  (`HashMap.getPassword()` suppression) would have
  silently broken otherwise. The test suite caught it
  on the first run — TypeAwareSensitiveTest went from
  passing to failing the moment the ctor type started
  including `<>`. That's the value of the R123
  regression-guard tests: they catch exactly this kind
  of "new feature silently breaks an old feature"
  situation.

## Files changed

- `plumb-tac/.../translate/ExpressionTranslator.java` —
  `translateNew` uses `asString()` for the ctor type
  (preserves type parameters)
- `plumb-dfa/.../type/TypeTable.java` — new
  `typeArgs` field, `typeArgsOf(...)` accessor,
  `parseTypeArgs(...)` static helper, `receiverClassName`
  strips type-args
- `plumb-dfa/.../test/.../type/TypeArgsTest.java`
  (new, 9 tests)

## Related docs

- `R117-ARCHITECTURE.md` — TypeTable's original design
- `R123-TYPE-AWARE-TAINT.md` — the first consumer of
  `receiverClassName`, the pattern R132 extends
- `R125-ANNOTATION-IR.md` — the sibling "metadata channel"
  approach; R132 follows the same incremental-extension
  pattern
- `R131-DEF-CHAIN-HELPER.md` — the previous round's
  infrastructure addition
