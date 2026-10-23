# R787e — DoubleCheckedLockingChecker source-reading → plumb-style AST migration

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787e — **the last of the 5 cwe57x source-reading checkers in `ClassThreadChecker.java`**. After R787e, `ClassThreadChecker.java` has no working inner checkers — it is a no-op pass-through.

## What R787e did

### 1. Wrote plumb-style `DoubleCheckedLockingCheck` (AstChecker)

`plumb-style/src/main/java/org/plumb/style/style/DoubleCheckedLockingCheck.java`
(new file, ~280 lines):

- `implements AstChecker` and overrides
  `visit(IfStmt, StyleContext)`. The standard
  `AstDispatcher` dispatches every `IfStmt` to the
  rule alongside the 564 existing
  `StyleCheck` / `AstChecker` implementations.
- Detects the classic dcl anti-pattern:
  ```java
  private static String x = null;     // FLAW: not volatile
  if (x == null) {
      synchronized(Klass.class) {
          if (x == null) {
              x = "value";
          }
      }
  }
  ```
  (1) `if (X == null) { ... }` outer; (2) body
  contains a `SynchronizedStmt`; (3) inside that
  synchronized body, an inner `if (X == null) { X = ... }`
  with the same target name; (4) `X` resolves to a
  `FieldDeclaration` whose modifier list does NOT
  include `volatile`.
- `RuleDef.ruleId()` is `new RuleId("Cwe609",
  "double-checked-locking")` with `List.of("CWE-609")`
  so the eval harness's `plumb_rule_to_cwe.json` mapping
  (`Cwe609 → ["609"]`) hits and the finding isn't
  cwe-mismatch-skipped (Lesson 1154).
- `name()` returns `"double-checked-locking"` (the
  short name). R761 alias resolution in `StyleContext`
  maps this to the canonical `Cwe609`.

### 2. R787e: ClassThreadChecker.java is now empty

`plumb-dfa/src/main/java/.../ClassThreadChecker.java`:

- Replaced `DoubleCheckedLockingChecker` (regex-based
  source-reading) with a `@Deprecated` private-ctor
  stub.
- The outer `ClassThreadChecker` aggregator's
  `initEngine` / `checkInstruction` / `finish` are now
  no-ops (the `all` list is empty).
- The file still contains 5 deprecation stubs
  (cwe572/579/585/609/835) for backwards compatibility
  with any yaml / test that still references the
  `dfa.cweNNN-*` rule ids. They fail loudly at
  instantiation if invoked (Lesson 1148).
- File-level javadoc + inner class doc comments
  document the full R787a–e migration history.

### 3. Per-CWE YAML for CWE-609

`D:\tmp\plumb_test\configs_v2_r760\CWE609.yaml`
(new file):

- Whitelists `double-checked-locking`,
  `DoubleCheckedLockingCheck`, `Cwe609` (the
  plumb-style rule's canonical id and two aliases).
- Does NOT enable `dfa.cwe609-double-checked-locking` —
  that entry would now resolve to the deprecated stub
  and throw at instantiation.

### 4. Unit test suite

`plumb-style/src/test/java/.../R787eDoubleCheckedLockingCheckTest.java`
(new file, 7 tests, all pass):

- `julietBadFormFires` — Juliet's actual `helperBad()`
  form.
- `volatileFieldSuppressesFinding` — Juliet's
  `good1()` form (which adds `volatile`); the regex
  would have fired (false positive), the AST check
  correctly suppresses.
- `localVariableDclDoesNotFire` — local variable
  dcl: not a JMM issue, suppressed.
- `fullySynchronizedDoesNotFire` — Juliet `good3()`
  form (entire body in one sync block).
- `methodSynchronizedDoesNotFire` — Juliet `good2()`
  form (synchronized modifier on method, not
  statement).
- `noNullCheckAtAllDoesNotFire` — outer if's
  condition is `true`, not `X == null`.
- `firesWithCwe609RuleId` — asserts `f.ruleId()` is
  `Cwe609` (not the legacy
  `dfa.cwe609-double-checked-locking`).

## Eval results

Run: `python eval_findings_cwe_matched.py --baseline-dir
D:\tmp\plumb_test\scan\r787a --whitelist-dir
D:\tmp\plumb_test\scan\r787e_full --out-dir
D:\tmp\plumb_test\eval\r787e_full`

where `r787e_full/` = r787b's 5-CWE (CWE129/190/191/369/572/835)
+ r787d's CWE585 + r787e's CWE609.

| CWE    | R787a baseline        | R787e whitelist       | Diff |
|--------|-----------------------|-----------------------|------|
| CWE129 | 0.4073 (2831/4120)    | 0.4073 (2831/4120)    | 0    |
| CWE190 | 0.4220 (7580/10384)   | 0.4220 (7580/10384)   | 0    |
| CWE191 | 0.5091 (5244/5057)    | 0.5091 (5244/5057)    | 0    |
| CWE369 | 0.3604 (1822/3234)    | 0.3604 (1822/3234)    | 0    |
| CWE572 | 1.0000 (17/0)         | 1.0000 (17/0)         | 0    |
| CWE585 | n/a                   | 0.0000 (0/2)          | NEW (eval doesn't recognise helperBad) |
| CWE609 | n/a                   | 0.0000 (0/2)          | NEW (same eval issue) |
| CWE835 | 1.0000 (6/0)          | 1.0000 (6/0)          | 0    |
| **Total** | **0.4343**         | **0.4343**            | **0** |

The 6-CWE baseline is bit-identical. CWE-609 is a
new addition: R787e finds 2 findings on Juliet
(both in `helperBad()`); the eval marks them FP
because it doesn't recognise the `helperBad` method
name as "bad" (eval framework limitation, not a
Plumb rule defect — the dcl pattern is correctly
identified on Juliet's marked-bad helper).

## Files (R787e)

- new: `plumb-style/src/main/java/org/plumb/style/style/DoubleCheckedLockingCheck.java` (~280 lines)
- new: `plumb-style/src/test/java/org/plumb/style/style/R787eDoubleCheckedLockingCheckTest.java` (7 tests, all pass)
- modified: `plumb-dfa/src/main/java/.../ClassThreadChecker.java` (cwe609 → deprecation stub; outer aggregator becomes no-op; file-level javadoc + R787e block in inner-class chain)
- new: `D:\tmp\plumb_test\configs_v2_r760\CWE609.yaml`
- new: `D:\Users\maijun\AppData\Local\Temp\r787e_scan.ps1` (single-CWE launcher)
- new: this SUMMARY.md

## Lessons / operational notes (R787e additions)

- Lesson 1168: `com.github.javaparser.ast.Modifier` (NOT
  `com.github.javaparser.Modifier`). The `Modifier`
  class lives in the `ast` sub-package, not the
  root. Symptom: `程序包com.github.javaparser.Modifier不存在`.

- Lesson 1169: `IfStmt` is in
  `com.github.javaparser.ast.stmt.IfStmt`, NOT
  `com.github.javaparser.ast.IfStmt`. Same for
  `SynchronizedStmt`, `BlockStmt`, etc. — they're
  all in the `.stmt` sub-package.

- Lesson 1170: `AssignExpr.getTarget()` returns
  `Expression`, not `SimpleName`. To check "is the
  assignment target a simple variable reference?",
  use `instanceof NameExpr` (which extends
  `Expression` and has `getNameAsString()`).
  `SimpleName` is a different AST class (the name
  part of a `NameExpr`, a `MethodCallExpr`, etc.).

- Lesson 1171: `Optional<Position>` from
  `node.getBegin()` may be `null` itself in some
  JavaParser versions (defensive null check needed).
  In the lesson-1157 series we've seen this
  pattern fail when the node implements
  `NodeWithRange` indirectly.

## R787f+ plan — the remaining 16 source-reading checkers

After R787e, `ClassThreadChecker.java` is empty. The
remaining 16 source-reading checkers live in
`plumb-dfa/src/main/java/org/plumb/dfa/checkers/`. Many
already have `plumb-style` equivalents (with different
rule IDs, e.g. `SuspiciousCommentCheck` →
`dfa.cwe546-suspicious-comment`, `ReachableAssertionCheck`
→ `pl-0037-reachable-assertion`), so for those R787i
can just deprecate the dfa-side stub + update the eval
json to map both rule IDs to the same CWE. For the
others we write new `AstChecker` implementations.

| R       | Scope                                                                                       | CWE mapping status               |
|---------|---------------------------------------------------------------------------------------------|-----------------------------------|
| R787f   | 5 source-reading → AST (cwe615 CommentInfoLeak, cwe526 EnvVarInfoExposure, cwe598 InfoExposureQueryString, cwe613 InsufficientSessionExpiration, cwe325 MissingCryptoStep) | none of these in `plumb_rule_to_cwe.json` → 0 baseline → 0 regression always |
| R787g   | 5 source-reading → AST (cwe329 RandomIv, cwe759 UnsaltedOneWayHash, cwe248 UncaughtException, cwe523 UnprotectedCredTransport, cwe398 PoorCodeQuality 8 sub-patterns) | none in json → 0 regression always |
| R787h   | 3 source-reading → AST (cwe580 ObjectModel clone-without-super, cwe491 ClassBasedIssue clone-not-final, cwe690 NullDerefFromReturn) | none in json → 0 regression always |
| R787i   | Deprecate 5 dfa-side checkers already covered by plumb-style (SuspiciousComment / ReachableAssertion / SensitiveInfoUncleared / MissingPasswordMasking / ClassSource SystemExit) + update `plumb_rule_to_cwe.json` to map both legacy and canonical rule IDs to the same CWE | dfa rule ids removed from yaml; plumb-style rule ids already used |
| R787j   | Full audit of `plumb-dfa/src/main/java/org/plumb/dfa/checkers/` to verify no source-reading remains; final 7-CWE eval to confirm 0 regression after all migrations | structural verification |

After R787j the cardinal principle is fully enforced:
**every checker in plumb-style uses AST as input; every
checker in plumb-dfa uses TAC IR as input. No mixing.**