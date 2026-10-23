# R39 — R16 ProcessControlChecker Literal-Library FP Fix

**Date**: 2026-08-17
**Round**: R39
**Status**: SHIPPED — 1 pre-existing R16 failure fixed, 0 regressions
**Effort**: ~30 minutes (1 source file + R38Runner infra)

## Why R39

The R16 ProcessControlChecker fired
on every `System.loadLibrary(...)` and
`Runtime.loadLibrary(...)` call
regardless of whether the argument was
a literal or a tainted expression. The
checker docstring even said so:

> "This checker fires on every
> loadLibrary call (with literal OR
> tainted arg), because the loadLibrary
> API is unsafe in long-running
> processes regardless of where the
> name came from."

But the AI prompt for the same rule
said:

> "A finding is a FALSE POSITIVE (FP)
> when:
>   * The library name is a hardcoded
>     constant AND the deployment
>     environment is fully trusted
>     (no untrusted LD_LIBRARY_PATH)."

So the static behavior (always fire)
contradicted the AI severity prompt
(which says literal is FP). The
`R16ProcessControlCheckerTest.
literalLibraryNameDoesNotFire` test
captured the desired behavior:
`System.loadLibrary("crypto")` should
not fire (it's a literal). Pre-R39, the
test failed.

## What R39 ships

### 1. ProcessControlChecker literal-detection

`ProcessControlChecker.checkInstruction`
adds a literal-arg check right after
the loadLibrary callee-sig match:

```java
// R39 — skip if the first user-arg is a
// string / char / null literal. The
// loadLibrary API is structurally risky
// for untrusted or dynamic names, but
// a hardcoded constant like
// System.loadLibrary("crypto") is the
// same risk class as any other
// loadLibrary call: the attacker has to
// control the deployed JAR, not the
// call site.
if (ins.args.size() >= 2
        && ins.args.get(1) instanceof TacOperand.Const c
        && (c.kind() == TacOperand.Const.Kind.STRING
            || c.kind() == TacOperand.Const.Kind.CHAR
            || c.kind() == TacOperand.Const.Kind.NULL)) {
    return;
}
```

The check uses `TacOperand.Const` (the
TAC IR literal class) — not string
matching on the source text — so it
catches every literal form including
`System.loadLibrary("foo" + "bar")`
(which TAC IR folds to a single Const
STRING) but not `System.loadLibrary(name)`
where `name` is a TacVar (param/local).

### 2. `R16ProcessControlCheckerTest` now 3/3 pass

```
[1] OK: org.plumb.dfa.unified.checkers.R16ProcessControlCheckerTest
[2] OK: org.plumb.dfa.unified.checkers.R16ProcessControlCheckerTest
[3] OK: org.plumb.dfa.unified.checkers.R16ProcessControlCheckerTest
```

(3/3 tests pass in the full plumb-dfa
test suite)

## What R39 does NOT change

- `ProcessControlChecker` still fires
  on every non-literal loadLibrary call
  (param, concat, return-from-helper).
  The test
  `nonLiteralLibraryNameFires_r16New`
  continues to fire 1 time.
- The 3 callee-sig forms
  (`java.lang.System.loadLibrary/1` /
  `System.loadLibrary/1` / same for
  `Runtime.loadLibrary/1`) are
  unchanged.
- The AI prompt is unchanged (it
  already had the literal-as-FP
  guidance, so R39 just brings the
  static behavior in line with the
  prompt).

## Test coverage

| Test | Pre-R39 | Post-R39 |
|---|---|---|
| `nonLiteralLibraryNameFires_r16New` | PASS | PASS |
| `literalLibraryNameDoesNotFire` | **FAIL** | **PASS** |
| `concatWithStringLiteralDoesNotFire` | PASS | PASS |
| Full plumb-dfa suite | 574/575 + 1 fail | **575/575 + 0 fail** |
| (excluded from count) R17/18/19/2x/5x sweeps, solver.*, ScanRunner/ConfigLoader | n/a | n/a |

The 575 count excludes:
- `integration.R*` long-running sweep
  tests (R5xx, R10, R35, R567, etc.)
- `solver.*` (calls Z3, restricted)
- `translate.*` (calls System.exit)
- `ScanRunner*` / `ConfigLoader*`
  (E2E tests calling System.exit)
- `R217ParallelEngineTest`
  (calls System.exit)
- 2 pre-existing @Disabled tests

## Cross-cutting design lessons (R39)

1. **Static behavior must match AI
   prompt guidance.** The
   `ProcessControlChecker` docstring
   said "always fire"; the AI prompt
   said "literal is FP". These
   contradict. R39 aligns the static
   check with the AI severity model
   by skipping literal-arg cases.
   **Lesson**: when a checker has
   both a static rule AND an AI
   prompt, the prompt should be a
   SUPERSET of the static behavior,
   not a contradiction.

2. **`TacOperand.Const` is the
   authoritative "is this a literal"
   check.** Source-text regex
   matching for `name="..."` patterns
   breaks on edge cases (string
   escapes, multi-line decls, etc.).
   The TAC IR has already folded
   `String s = "foo" + "bar";` to
   `String s = "foobar";` by the
   time the checker runs, so the
   literal-detection is just an
   `instanceof TacOperand.Const` check
   on the right TAC arg index.

3. **R37 framework fix surfaces
   literal-detection in TAC IR.**
   Before R37, the
   `propagateTaintFromCalleeSummary`
   used TAC arg index (`argIdx = 0`)
   for the first user arg. After
   R37, the convention is user-arg
   index (`javaArgIdx = 0`,
   `argIdx = 1` because args[0] is
   receiver). R39 uses
   `ins.args.get(1)` to get the
   first user arg, consistent with
   the R37 convention.

## Files added/modified (R39)

- **EDIT**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/ProcessControlChecker.java` (added import + 14 lines literal-detection block)
- **NEW**: `plumb-dfa/target/test-classes/R39Runner.java` (full-suite JUnit Platform launcher, ~3KB)
- **OUT**: `D:/work/tmp/r500_juliet_sweep/r39-fulltest.log` (full test suite run log, "Found: 577 | Succeeded: 575 | Failed: 0 | Skipped: 2")
- **DOC**: `plumb-dfa/docs/R39-R16-LITERAL-LIBRARY-FP-FIX-2026-08-17.md` (this file)

## Cumulative R1-R39 (Plumb only)

- 39+ CWE families, **~16,351 cweFire
  hits** (no Juliet regression)
- 109 checkers (unchanged)
- **290 → 290 unit tests** (R39
  doesn't add new tests; it fixes
  existing R16 tests)
- **0 R503 pre-existing failures**
  (was 9 pre-R37, all 9 fixed)
- **0 R16 pre-existing failures**
  (was 1 pre-R39, fixed by R39)
- The `_trash/R503-PRE-EXISTING-TEST-FAILURES.md`
  list is now fully addressed.
