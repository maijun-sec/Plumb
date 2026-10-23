# R787g — 5 source-reading checkers batch-migrated from plumb-dfa to plumb-style AST

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787g — **5 cwe source-reading batch (CWE-329/759/523/600/398)** moved from
`plumb-dfa/checkers/*Checker.java` (regex on text) to plumb-style `*Check.java`
(`AstChecker` walking the JavaParser `CompilationUnit`).

## What R787g did

### 1. Five new plumb-style `AstChecker` files

`plumb-style/src/main/java/org/plumb/style/style/`:

| Old dfa-side (now stub) | New plumb-style (AST) | Detection strategy |
|---|---|---|
| `RandomIvChecker` (CWE-329) | `RandomIvCheck` | walk `ObjectCreationExpr` for `IvParameterSpec`; check if arg is literal byte array or var whose declaration is literal/empty without `.nextBytes()` between. |
| `UnsaltedOneWayHashChecker` (CWE-759) | `UnsaltedOneWayHashCheck` | linear walk `MethodCallExpr`; track getInstance/digest/update in line order; fire on digest without intervening update. |
| `UnprotectedCredTransportChecker` (CWE-523) | `UnprotectedCredTransportCheck` | walk `StringLiteralExpr`; fire when literal contains both `(?<!s)http://` AND a login form token (`j_security_check` / `j_username` / `j_password`). |
| `UncaughtExceptionChecker` (CWE-600) | `UncaughtExceptionCheck` | walk `MethodDeclaration`; fire on risky calls (`InetAddress.getByName` / `Class.forName` / `ProcessBuilder.start`) without an enclosing `TryStmt` ancestor. (CWE-248 cross-function propagator stays in dfa.) |
| `PoorCodeQualityChecker` (CWE-398) | `PoorCodeQualityCheck` | walk `BlockStmt`; fire when block is effectively empty (no statements or only `EmptyStmt`). Also flags empty method bodies. |

All five use `RuleId("CweNNN", "<slug>")` with `cwe=["CWE-NNN"]` so the eval
harness's `plumb_rule_to_cwe.json` mapping (`CweNNN → ["NNN"]`) hits and the
finding isn't cwe-mismatch-skipped (Lesson 1154).

### 2. Five dfa-side stubs (Lesson 1148 pattern)

`plumb-dfa/src/main/java/org/plumb/dfa/checkers/`:

- `RandomIvChecker` → `@Deprecated` private-ctor that throws
- `UnsaltedOneWayHashChecker` → same
- `UnprotectedCredTransportChecker` → same
- `UncaughtExceptionChecker` → same (note: only CWE-600 sub-pattern. CWE-248 propagator stays)
- `PoorCodeQualityChecker` → same

### 3. ScanRunner wiring (Lesson 1148 + R787a template)

`plumb-main/src/main/java/org/plumb/main/ScanRunner.java`:

- Removed 5 `checkers.add(new org.plumb.dfa.checkers.*Checker())` lines.
- The plumb-side auto-registration via
  `StyleCheckRegistry.builtins().all()` already includes the 5 new
  AstCheckers (no further wiring needed).

### 4. Five per-CWE yaml files

`D:\tmp\plumb_test\configs_v2_r760/CWE{329,759,523,600,398}.yaml` — each
whitelists `CweNNN` (the new plumb-style canonical rule id), the
`name()` short name, and the class name; resource-leak rule also enabled
(it fires on `new Thread(...)` without `close/start` in Juliet CWE-572).

### 5. Unit test suite

`plumb-style/src/test/java/.../R787gSourceReadingBatchTest.java`
(new file, 11 tests, all pass):

- 2 on `RandomIvCheck`: literal array fires, `.nextBytes` between suppresses.
- 2 on `UnsaltedOneWayHashCheck`: digest without update fires, update between suppresses.
- 2 on `UnprotectedCredTransportCheck`: http:// form fires, https:// form suppresses.
- 2 on `UncaughtExceptionCheck`: risky call without try/catch fires, with try/catch suppresses.
- 2 on `PoorCodeQualityCheck`: empty block fires, empty function fires.
- 1 final test asserts all five `name()` short names.

## Juliet v1.3 scan results (R787g only)

5 CWE scanned:

| CWE | findings | TP | FP | precision |
|-----|---------|-----|-----|-----------|
| CWE329 | TBD | TBD | TBD | TBD |
| CWE398 | TBD | TBD | TBD | TBD |
| CWE523 | TBD | TBD | TBD | TBD |
| CWE600 | TBD | TBD | TBD | TBD |
| CWE759 | TBD | TBD | TBD | TBD |

(precisions are computed in the final 24-CWE eval at the end of R787j.)

## What's next (R787h+ plan)

- R787h: 3 source-reading batch (cwe580 ObjectModel + cwe491 ClassBasedIssue
  + cwe690 NullDerefFromReturn). Same R787f template.
- R787i: deprecate 5 dfa checkers already covered by plumb-style
  (SuspiciousComment / ReachableAssertion / SensitiveInfoUncleared /
  MissingPasswordMasking / ClassSource SystemExit).
- R787j: final 24-CWE eval confirms 0 regression on the 6 R787a
  baseline; high precision on the 13 new AST rules.
