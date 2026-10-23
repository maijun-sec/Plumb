# R271-R280: PMD / SonarQube / SpotBugs / Checkstyle Coverage

## Executive Summary

R271-R280 extended the Plumb Java SAST rule catalogue to substantially improve
PMD / SonarQube / SpotBugs / Checkstyle coverage. The big architectural
shift in R276 was wiring **every** `AstChecker` into `StyleCheckRegistry.builtins()`
— before that, only 16 hand-picked checkers were running in production; the
remaining 350+ were present on the classpath but never executed.

## Rule Counts by Tool (post-R279)

| Tool        | Rules  | Target (95%) | Gap  | Status |
|-------------|--------|--------------|------|--------|
| PMD         | 132    | 302          | 170  | partial |
| SonarQube   | 96     | 670          | 574  | partial |
| SpotBugs    | 95     | 492          | 397  | partial |
| Checkstyle  | 51     | 143          | 92   | partial |
| **Total AstChecker** | **408** | — | — | — |

## R271-R279 Round Summary

| Round | Description | New Rules | Tests | Backup |
|-------|-------------|-----------|-------|--------|
| R271  | Cross-tool dedup (canonical concept wins) | -13 (net) | +0 | `D:\work\tmp\r271_dedup_done\` |
| R272  | SpotBugs batch 1 (UnconfirmedCast, RedundantNullCheck, Bitwise, SignedBit, …) | 11 | 19 | `D:\work\tmp\r272_spotbugs_done\` |
| R273  | SonarQube batch 4 (TabCharacter, ClassInheritanceDepth, GenericException, …) | 25 | 29 | `D:\work\tmp\r273_sonar_done\` |
| R274  | SpotBugs batch 2 (DmBoxedPrimitiveForParsing, NpAlwaysNull, EcIncompatibleTypes, …) | 25 | 25 | `D:\work\tmp\r274_spotbugs_done\` |
| R275  | SpotBugs batch 3 (IlContainerSequencing, RangeArrayLength, UcUselessObject, …) | 25 | 25 | `D:\work\tmp\r275_done\` |
| R276  | **Critical fix**: full registry expansion + Checkstyle batch 1 (30 rules) | 30 | 60 | `D:\work\tmp\r276_checkstyle_batch1\` |
| R277  | SonarQube batch 5 (AtLeastOneCtor, BadAbstractClassName, BooleanGetMethodName, …) | 31 | 62 | `D:\work\tmp\r277_sonar_batch5\` |
| R278  | Checkstyle batch 2 (AnnoTbl, AvoidNestedBlocks, ClassFanOut, …) | 16 | 32 | `D:\work\tmp\r278_done\` |
| R279  | SpotBugs batch 4 (AssignmentToCatchBlockParameter, BadCheckForOdd, CovariantEquals, …) | 19 | 38 | `D:\work\tmp\r279_done\` |

**Net R271-R279**: +170 new rules (with 13 dups removed in R271), ~290 new tests.

## R276 — The Critical Fix

Before R276, `StyleCheckRegistry.builtins()` only registered 16 checkers
that the original author had hand-picked. The other 300+ `AstChecker`
implementations were on the classpath, but `ScanRunner.runStyleChecks`
only invoked the 16 hand-picked ones.

R276 fixed this by generating a `register(new XxxCheck())` for every
`AstChecker` implementation found in `org.plumb.style.*`. R277-R279
added the new checkers and re-ran the auto-generation. The current
registry contains 408 entries — every Plumb style checker is now
actually executed during a real scan.

This was the most important fix in R271-R280 because the previous
numbers (PMD 95% done, etc.) were measured against the *classpath*,
not against rules that actually ran.

## Key Pitfalls Encountered

1. **`BinaryExpr.Operator.AND` is logical, `BINARY_AND` is bitwise** —
   same for `OR` / `BINARY_OR`. XOR has only `XOR`.
2. **`FieldDeclaration.getElementType()` returns component type
   (`int`), not declared type (`int[]`)** — use `getCommonType()`.
3. **`MethodDeclaration.getBody()` returns `Optional<BlockStmt>`,
   `ConstructorDeclaration.getBody()` returns `BlockStmt` directly**.
4. **`Modifier` is a class, not an enum** — its `Keyword` enum is
   nested (`Modifier.Keyword.PUBLIC`).
5. **`(x.y).z` in JavaParser parses as
   `EnclosedExpr(BinaryExpr(MULTIPLY, x, y))` with `.z` chained** —
   unwrap EnclosedExpr before scanning inner nodes.
6. **`LongLiteralExpr.asString()` doesn't exist** — use `toString()`.
7. **Python regex `\\b` becomes `\b` in Java source which is backspace** —
   must use `\\\\b` in Python source to get `\\b` in Java source.
8. **StyleCheckRegistry corruption via repeated `Add-Content`** — fix
   by re-running `gen_registry.py` and re-creating the file.
9. **Duplicate rule IDs across checkers** — when a checker like
   `UnnecessaryBoxingCheck` is added twice (R263 + R277), the second
   shadows the first; rename to `_Sonar` suffix.
10. **`for (;;) {}` in `for (i=0; 0==0; i++) {}`** — JavaParser
    parses `i==0` with `NameExpr(i)` on the left, not both literals.
    Use a `0==0` test or a `0<0` test in the source.

## Test Counts (post-R279)

| Module              | Tests | Pass | Fail |
|---------------------|-------|------|------|
| plumb-tac           | 37    | 37   | 0    |
| plumb-rules         | 6     | 6    | 0    |
| plumb-dfa           | 394   | 394  | 0    |
| plumb-style         | 645   | 645  | 0    |
| plumb-reporter      | 6     | 6    | 0    |
| plumb-dfa   | 391   | 391  | 0    |
| plumb-main          | 53    | 53   | 0    |
| **Total**           | **1532** | **1532** | **0** |

## Future Work

To reach 95% per tool, the remaining gaps need ~1233 more rules:

| Tool        | Remaining | Suggested Rounds (×30/round) |
|-------------|-----------|------------------------------|
| PMD         | 170       | ~6 more rounds (R281-R286)   |
| SonarQube   | 574       | ~20 more rounds (R281-R300)  |
| SpotBugs    | 397       | ~14 more rounds (R281-R294)  |
| Checkstyle  | 92        | ~3 more rounds (R281-R283)   |

Each round should be balanced across tools for tractability.
