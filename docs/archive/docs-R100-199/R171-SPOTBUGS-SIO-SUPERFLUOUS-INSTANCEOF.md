# R171 — SpotBugs SIO_SUPERFLUOUS_INSTANCEOF Mirror

**Date:** 2026-07-31
**Status:** Complete, 4/4 tests pass, 0 regression
**Rule id:** `bu-0505-spotbugs-sio-superfluous-instanceof`
**CWE:** CWE-704 (Incorrect Type Conversion or Cast)

## 1. Goal

Mirror SpotBugs's `SIO_SUPERFLUOUS_INSTANCEOF` bug pattern
in Plumb, as the **style-side companion** to R170
(DM_DEFAULT_ENCODING, data-flow side). R171 demonstrates
that SpotBugs style/structural rules can be mirrored in
`plumb-style` with a JavaParser visitor — no Soot or
bytecode analysis needed.

## 2. SpotBugs rule description

From `spotbugs.jar!/messages.xml`:
> Type check performed using the instanceof operator
> where it can be statically determined whether the
> object is of the type requested.

The canonical case (covered by R171):

```java
if (x instanceof String) {
    String s = (String) x;   // <-- redundant cast
    s.length();
}
```

After the `instanceof` check, the compiler already
knows `x` is a `String` (in flow-sensitive type
analysis), so the `(String)` cast is dead code.

## 3. Plumb detection pattern

`plumb-style` checker — JavaParser visitor (same as
R137 / R167 / R168):

1. Visit every `IfStmt` in the source.
2. Check the condition is an `InstanceOfExpr`; read the
   target type name (e.g. `"String"`).
3. Walk the then-statement (or then-block) looking for
   a `CastExpr` whose target type matches.
4. The walk is **shallow** — only direct children of
   the then-block are checked, to keep the FP rate low.

## 4. Innovation points (4)

1. **SpotBugs style rules map cleanly to JavaParser
   visitors** (R171 design). Despite SpotBugs's
   bytecode-analysis reputation, structural rules
   like `SIO_SUPERFLUOUS_INSTANCEOF` are observable
   at the source level. R171 demonstrates that the
   `plumb-style` JavaParser visitor pattern
   (R137/R167/R168) extends naturally to SpotBugs
   style rules. No new infrastructure needed.

2. **The "redundant cast in instanceof branch" is the
   narrow, well-defined core of the rule** (R171
   design choice). SpotBugs's `SIO_SUPERFLUOUS_INSTANCEOF`
   has a broad scope — including "instanceof always
   true" (e.g. `x instanceof Object`) and "static
   type already guarantees" cases that need type
   resolution. R171 narrows to the redundant-cast
   case, which is the only variant that a
   source-only, type-resolution-free checker can
   robustly detect.

3. **Nested `VoidVisitorAdapter` to bound the
   search scope** (R171 implementation). R171 uses
   a child `VoidVisitorAdapter` (`RedundantCastFinder`)
   scoped to the then-block only. This is
   intentionally **non-recursive** across nested
   `IfStmt` / lambdas — those are different
   control-flow regions and any cast there is not
   made redundant by THIS instanceof. The shallow
   scope is a deliberate design choice to keep the
   FP rate low; deeper analysis is a future
   R-round.

4. **R171 reports once per `(IfStmt, then-block)`,
   not per redundant cast** (R171 UX). Multiple
   redundant casts inside the same then-block are
   collapsed into a single finding, because the
   user-visible problem is "this whole branch has
   redundant casts", not "this specific cast is
   redundant" — the fix is the same either way
   (remove the cast operator).

## 5. Tests

| Test | Asserts |
|---|---|
| `firesOnRedundantCastInThenBlock` | canonical pattern → finding |
| `noFindingWhenThenBlockHasNoCast` | `instanceof` without cast → no finding |
| `noFindingForDifferentCastType` | `instanceof String` + `(Object) x` → no finding |
| `noFindingForPlainIfWithoutInstanceof` | cast without `instanceof` → no finding |

All 4 pass, 0 regression in the 21-test plumb-style
suite.

## 6. Known limits (R171+ follow-ups)

- **`x instanceof Object`** always-true case not
  detected (would need "is this a supertype" check).
- **Static type guarantees** (e.g. method parameter
  declared as `String` and tested with
  `instanceof String`) not detected (would need
  Plumb-side type resolution, not just AST).
- **Nested cast inside lambda or nested IfStmt** not
  searched (deliberate shallow scope).

These are the same kinds of limits that apply to
SpotBugs's source-only mode (vs bytecode mode).

## 7. Files

- New: `plumb-style/src/main/java/.../SuperfluousInstanceofCheck.java`
  (5475 bytes, ~120 lines)
- New: `plumb-style/src/test/java/.../R171SpotBugsSuperfluousInstanceofTest.java`
  (2894 bytes, 4 tests)
- Modified: `plumb-rules/src/main/java/.../RuleRegistry.java`
  (added constant + register)
- Modified: `plumb-style/src/main/java/.../StyleCheckRegistry.java`
  (added `register(new SuperfluousInstanceofCheck())`)

## 8. Disaster backup

`D:\work\tmp\r170_done\java_source\SuperfluousInstanceofCheck.java` (5475 bytes)
`D:\work\tmp\r170_done\java_source\R171SpotBugsSuperfluousInstanceofTest.java` (2894 bytes)
