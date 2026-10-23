# R787h — 3 source-reading checkers batch-migrated from plumb-dfa to plumb-style AST

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787h — **3 cwe source-reading batch (CWE-580/491/690)** moved from
`plumb-dfa/checkers/*Checker.java` (regex on text) to plumb-style `*Check.java`
(`AstChecker` walking the JavaParser `CompilationUnit`).

## What R787h did

### 1. Three new plumb-style `AstChecker` files

`plumb-style/src/main/java/org/plumb/style/style/`:

| Old dfa-side (now stub) | New plumb-style (AST) | Detection strategy |
|---|---|---|
| `ObjectModelChecker.CloneWithoutSuperChecker` (CWE-580) | `ObjectModelCheck` | walk `MethodDeclaration` named `clone` (no params); fire if any `ReturnStmt`'s expression is an `ObjectCreationExpr`. |
| `ClassBasedIssueChecker.CloneNotFinalChecker` (CWE-491) | `ClassBasedIssueCheck` | walk `MethodDeclaration` named `clone`; fire if modifier set contains `protected` but NOT `final`. |
| `NullDerefFromReturnChecker` (CWE-690) | `NullDerefFromReturnCheck` | walk `MethodDeclaration`; per-body track variable declarations / assignments; fire on `x.method()` use without intervening `if (x != null)` null check. |

All three use `RuleId("CweNNN", "<slug>")` with `cwe=["CWE-NNN"]` so the eval
harness's `plumb_rule_to_cwe.json` mapping (`CweNNN → ["NNN"]`) hits and the
finding isn't cwe-mismatch-skipped (Lesson 1154).

### 2. Three dfa-side stubs (Lesson 1148 pattern)

`plumb-dfa/src/main/java/org/plumb/dfa/checkers/`:

- `ObjectModelChecker` → `@Deprecated` private-ctor that throws
- `ClassBasedIssueChecker` → same (note: only CWE-491 sub-pattern; the
  other ClassBasedIssueChecker sub-patterns (CWE-500/561/582/607/667)
  remain in dfa until R787i+ evaluates them. Many of them already
  have plumb-style equivalents — e.g. Cwe500MutableStatic,
  Cwe582ArrayPublicFinal — so a future R-Round will deprecate them too.)
- `NullDerefFromReturnChecker` → same

### 3. ScanRunner wiring (Lesson 1148 + R787a template)

`plumb-main/src/main/java/org/plumb/main/ScanRunner.java`:

- Removed `NullDerefFromReturnChecker` registration. (ObjectModelChecker
  and ClassBasedIssueChecker were never registered in R-Round; the
  R787h dfa-side stubs simply remove the source-reading path that
  was registered via the AST rule's classifier code.)
- The plumb-side auto-registration via
  `StyleCheckRegistry.builtins().all()` already includes the 3 new
  AstCheckers (no further wiring needed).

### 4. Three per-CWE yaml files

`D:\tmp\plumb_test\configs_v2_r760/CWE{580,491,690}.yaml` — each
whitelists `CweNNN` (the new plumb-style canonical rule id), the
`name()` short name, and the class name; resource-leak rule also enabled.

### 5. Unit test suite

`plumb-style/src/test/java/.../R787hSourceReadingBatchTest.java`
(new file, 7 tests, all pass):

- 2 on `ObjectModelCheck`: clone returns `new ...()` fires, clone returns `super.clone()` suppresses.
- 2 on `ClassBasedIssueCheck`: protected clone not final fires, protected final clone suppresses.
- 2 on `NullDerefFromReturnCheck`: assign-then-use without null check fires, with null check suppresses.
- 1 final test asserts all three `name()` short names.

## Juliet v1.3 scan results (R787h only)

3 CWE scanned:

| CWE | findings | TP | FP | precision |
|-----|---------|-----|-----|-----------|
| CWE580 | TBD | TBD | TBD | TBD |
| CWE491 | TBD | TBD | TBD | TBD |
| CWE690 | TBD | TBD | TBD | TBD |

(precisions are computed in the final 24-CWE eval at the end of R787j.)

## What's next (R787i+ plan)

- R787i: deprecate 5 dfa checkers already covered by plumb-style
  (SuspiciousComment / ReachableAssertion / SensitiveInfoUncleared /
  MissingPasswordMasking / ClassSource SystemExit).
- R787j: final 24-CWE eval confirms 0 regression on the 6 R787a
  baseline; high precision on the 13 new AST rules.
