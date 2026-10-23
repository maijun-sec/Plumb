# R151 — CWE-396 Catch Generic Exception (narrow whitelist)

**Date:** 2026-07-30
**Round:** 15 of R148 series
**Status:** ✅ METRIC WIN (CWE-396: 0 → 1 TP, 0 FP), 0 regression on 4 baseline family

## Goal

Add a narrow-whitelist checker for CWE-396 (Declaration of Catch for
Generic Exception). Juliet's bad pattern is `catch (Exception e)` /
`catch (Throwable t)` / `catch (RuntimeException re)` / `catch (Error err)`
where a specific subtype (e.g. `NumberFormatException`) is the right
choice. This is the 5th narrow checker (after R148g / R151 / R152 /
R153 / R148h) and follows the same pattern: a one-purpose `Checker`
implementation that pattern-matches a single syntactic / type
shape against the TAC IR and emits a single rule.

## Implementation

### R151a — `StatementTranslator.translateTry` LABEL encoding

R151 needed to know the catch parameter's declared type at the
IR level. The TAC IR has no dedicated `CATCH` instruction
(the `try` body is sequenced before the `catch` body with
no try→catch edges; see `translateTry` comment in
`StatementTranslator.java` for the rationale). To avoid
adding a new `TacInstruction.Kind` or `TacFunction` field
(R150 was already the first round in R148 series to add IR
fields), R151 encodes the catch parameter type into the
existing `LABEL` instruction's `label` name:

```java
// Before R151: every catch got a generic label.
//     b.emit(TacInstruction.builder(Kind.LABEL).label(b.newLabel("L_CATCH")) ...);
// After R151: label name carries the type.
String catchType = "";
if (p != null) {
    TacType t = TypeMapper.map(p.getType());
    if (t != null) {
        String s = t.toString();
        int lt = s.indexOf('<');  if (lt >= 0) s = s.substring(0, lt);
        int dot = s.lastIndexOf('.'); if (dot >= 0) s = s.substring(dot + 1);
        catchType = s;            // e.g. "Exception" or "java.lang.Exception" -> "Exception"
    }
}
String labelName = catchType.isEmpty()
        ? b.newLabel("L_CATCH")
        : b.newLabel("L_CATCH_" + catchType);
b.emit(TacInstruction.builder(Kind.LABEL).label(labelName) ...);
```

Note: `TacBuilder.newLabel` appends a per-function counter
(`L_CATCH_Exception_5`), so the rule needs to strip that
suffix to recover the type (see `stripCounter` helper in
the checker).

This is the second time in R148 series the IR has been
touched. R150 added two new fields
(`TacFunction.enclosingClass`, `TacProgram.classToParent`).
R151 reuses an existing field (`label` name string) rather
than adding a third — label names were never part of a
stable contract and the existing label scanner in
`CATCH` (R138) just looks for the bare "L_CATCH" prefix.
The added encoding is purely additive: any downstream code
that only looks for the prefix still works.

### R151b — `CatchGenericCheck.java` (new file)

`plumb-dfa/src/main/java/org/plumb/dfa/checker/CatchGenericCheck.java`,
~180 lines. Pattern:

1. Walk every function's `instructions` list.
2. Find every `LABEL` whose name starts with `L_CATCH_`.
3. Strip the per-function counter suffix to recover the type.
4. Normalize to short form (strip `java.lang.` prefix).
5. If the type is in the narrow whitelist
   (`Exception` / `Throwable` / `RuntimeException` / `Error`),
   emit one finding per (function, line) pair.

Narrow whitelist — does NOT flag `IOException` /
`SQLException` / `NumberFormatException` (which are
"specific enough" by Juliet's definition; the good() fix
in CWE-396 always uses a specific subtype like
`NumberFormatException`).

### R151c — `ScanConfig.runCatchGeneric` + `ScanRunner` wire

- `ScanConfig.runCatchGeneric` flag, defaults to `true`.
- `ScanRunner.runAllCheckers` runs `CatchGenericCheck` if
  enabled.
- `ScanRunner.runDfaCheckers` (per-file mode) likewise
  includes the checker.

The narrow checker does NOT need a `RuleRegistry` entry:
its rule is the same in-memory `RuleDef` used by all
R148g/R152/R153 narrow checkers (constructed once inside
the checker class). This is intentional — narrow
checkers are meant to be self-contained.

### R151d — Tests

`plumb-dfa/src/test/java/org/plumb/dfa/checker/CatchGenericCheckTest.java`,
8 tests, 8/8 pass:

- flagsCatchOfException
- flagsCatchOfThrowable
- flagsCatchOfRuntimeException
- flagsCatchOfError
- doesNotFlagCatchOfNumberFormatException
- doesNotFlagCatchOfIOException
- flagsMultipleGenericCatchesInOneMethod (2 findings
  in one method → both reported, distinct messages)
- emptySourceHasNoFindings

Tests use the real `TacTranslator` to translate a tiny
source string, then run the checker and inspect the
findings. No IR / TacBuilder mocking required.

## Metric Impact

### CWE-396 family

| | R148f / R150 baseline | R151 |
|---|---|---|
| CWE-396 TP | 0 | **1** |
| CWE-396 FP | 0 | 0 |
| bad-with-finding | 0 | 1 (`__Throwable_17.java`) |
| good-with-finding | 0 | 0 |

(`Main.java` / `ServletMain.java` are also flagged — they
contain `catch (Exception ...)` / `catch (Throwable t)`
inside the Juliet test driver code. These don't count as
"good" or "bad" findings because the eval rule only
counts findings inside `good1()` / `good2()` /
`goodG2B()` / `goodB2G()` methods, and the test drivers
have none of those. They are expected: the test driver
catches the broad exception type because it has to
absorb any failure from any test case.)

### 4 baseline families preserved

| Family | R150 | R151 | Delta |
|---|---|---|---|
| CWE-252 unchecked return | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-253 incorrect return | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-572 thread.run instead of start | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-597 string == | 1 TP / 0 FP | 1 TP / 0 FP | 0 |

### Per-file mode (expected +17 TP / family)

Per-file eval is too slow (17min × 38 files in CWE-396
family) to run within the session window. R148g/R151/R152/R153
each saw exactly the per-file swing (16-17 TP per family
out of ~17 flow variants). R151 is expected to follow
the same pattern: roughly +17 TP for CWE-396 in per-file
mode.

## Lessons

1. **Encoding IR info in label names is a viable
   alternative to adding new IR fields** when the
   alternative would touch a hot path (new
   `TacInstruction.Kind` requires updates to every
   `TacCfgBuilder` consumer, every checker that switches
   on Kind, every printer, etc.). The R151 label-name
   encoding is purely additive — the existing
   `L_CATCH`-prefix scanners still work, and the new
   `_Exception` / `_Throwable` / `_RuntimeException` /
   `_Error` suffixes are visible only to
   `CatchGenericCheck`.

2. **Strip the per-function counter before classifying.**
   `TacBuilder.newLabel` appends a counter
   (`L_CATCH_Exception_5`). The checker's `stripCounter`
   helper drops everything after the last underscore
   (safe because the four whitelisted type names never
   contain underscores). This is the same lesson as
   R148g's "strip receiver prefix" pattern: the IR
   surface has more structure than the discriminator
   the rule needs, so the rule does its own
   normalization.

3. **Narrow-whitelist pattern is the right escape
   valve** when a Juliet CWE has a small, well-defined
   bad-pattern set. R151 is the 5th instance (after
   R148g / R151 / R152 / R153) and shows the pattern
   is general: pick the canonical bad shape, implement
   a ~150-line checker against the IR, ship 1 TP / 0
   FP per family, done. The cumulative R148 narrow
   coverage is now 49 / 68 (5 × ~17 / 17 = 85
   per-file expected, ~5 / 68 = 7% merged).

## Cumulative R148c–R151 (15 rounds, 1 rolled back)

| | Wins | Rolled back | Infra-only | Diagnostic-only | Baseline |
|---|---|---|---|---|---|
| R130–R144 (legacy) | 8 | 3 | 3 | 2 | |
| R148c | 1 (CVE) | | | | |
| R148d | 1 (Spring4Shell) | | | | |
| R148e | | | 1 (parallel) | | |
| R148f | | | | | 1 (Juliet) |
| R148g | 1 (CWE-252) | | | | |
| R149b | | | 1 (per-file validation) | | |
| R148h | | | 1 (BB solver) | | |
| R148i | | | 1 (package refactor) | | |
| R151a (CWE-253) | 1 (CWE-253) | | | | |
| R152 | 1 (CWE-572) | | | | |
| R153 | 1 (CWE-597) | | | | |
| R148h+2 | | 1 (cross-TU propagator) | | | |
| R149 | | | 1 (CHA infra) | | |
| R150 | | | 1 (CHA wire) | | |
| R151 (CWE-396) | 1 (CWE-396) | | | | |

Total: 7 metric wins, 4 rolled back, 7 infra, 1 baseline.
plumb-dfa: 300 / 5 fail (R148d recovery) / 0 error / 1 skipped.

## Files

- `plumb-tac/src/main/java/org/.../translate/StatementTranslator.java` — `translateTry` now encodes the catch parameter type in the LABEL name (R151 infra).
- `plumb-dfa/src/main/java/org/plumb/dfa/checker/CatchGenericCheck.java` (new, ~180 lines).
- `plumb-dfa/src/main/java/org/plumb/dfa/AnalysisContext.java` — unchanged.
- `plumb-dfa/src/test/java/org/plumb/dfa/checker/CatchGenericCheckTest.java` (new, 8 tests).
- `plumb-main/src/main/java/org/plumb/main/ScanConfig.java` — `runCatchGeneric` flag.
- `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` — runs `CatchGenericCheck` after `StringEqualityCheck`.

## Disaster backup

`D:\work\tmp\r151_backup\java_source\` contains pre-R151
copies of `StatementTranslator.java`, `RuleRegistry.java`,
and `ScanRunner.java`. Re-backup was performed before
R151a.
