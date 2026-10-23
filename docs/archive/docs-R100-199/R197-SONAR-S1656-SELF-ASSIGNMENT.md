# R197 — SonarQube S1656 Self-Assignment (2026-07-31)

## Goal

Pilot another SonarQube (C=SonarQube) rule with a trivial
AST mirror. SonarQube S1656 "Variables should not be
self-assigned" detects the pattern `x = x;` (or
`obj.x = obj.x;`) where the assignment source and target
are the same variable. Self-assignment is always a no-op
and usually indicates a copy-paste bug.

## Implementation

- `plumb-style/.../SelfAssignmentCheck.java` (~3 KB)
- `visit(ExpressionStmt, StyleContext)` — match
  `AssignExpr` with `==` op and LHS/RHS both `NameExpr`
  with the same name
- AST-only check, no dataflow; same narrow-whitelist
  template as R167/R168/R171/R176/R177/R180/R181/R183/R192

## R197 4 创新点

1. **Self-assignment is a single-token AST pattern**
   (R197 design). The check is "LHS is `NameExpr`, RHS
   is `NameExpr`, names match" — three checks, no
   analysis. R167-R183 all do similar trivial AST walks;
   R197 is a particularly clean example.
2. **Field vs local disambiguation via simple name only**
   (R197 implementation). We only fire on bare
   `NameExpr`-to-`NameExpr` matches, not
   `FieldAccessExpr`-to-`NameExpr`. This means
   `this.x = x;` (where the LHS is a field access on
   `this` and the RHS is a local) does NOT fire —
   different scopes, even with the same name.
3. **Compound assignments are explicitly excluded**
   (R197 trade-off). `x += x;` is NOT a no-op (it's
   `x = x + x`), so we only flag the simple `=`
   operator. Compound ops (`+=`, `-=`, `*=`, etc.)
   are silently ignored.
4. **R197 adds `ExpressionStmt` to AstChecker/AstDispatcher**
   (R197 infra). R197 was the first rule that needed
   the `ExpressionStmt` node type in the A1 single-pass
   AST walker. Future rules (Sonar S1854 unused
   assignment, Coverity UNUSED_VALUE, etc.) can now
   share the dispatcher support.

## Test Results (4/4 pass)

| Test | Pattern | Result |
|---|---|---|
| `firesOnSelfAssignment` | `x = x;` | fires ✓ |
| `noFireOnDifferentVars` | `x = y;` | suppressed ✓ |
| `noFireOnCompoundAssignment` | `x += x;` | suppressed ✓ |
| `noFireOnFieldAssignment` | `this.x = x;` | suppressed ✓ |

## 注册

- `RuleRegistry.SONAR_S1656_SELF_ASSIGNMENT = "bu-0608-sonar-s1656-self-assignment"`
- CWE-682 (Incorrect Calculation)
- aliases: `["SonarQube:java:S1656"]`

## Files

- 新: `plumb-style/.../SelfAssignmentCheck.java` (3122 bytes)
- 改: `plumb-style/.../AstChecker.java` (+ ExpressionStmt default no-op)
- 改: `plumb-style/.../AstDispatcher.java` (+ ExpressionStmt visit override)
- 改: `plumb-rules/.../RuleRegistry.java` (+ SONAR_S1656_SELF_ASSIGNMENT, def, alias)
- 新: `plumb-style/.../R197SonarSelfAssignmentTest.java` (2834 bytes, 4 tests)
- 备份: `D:\work\tmp\r197_done\java_source\`

## Source 集成 status post-R197 (15 narrow mirrors)

- A (PMD): R166/R167/R168/R169 (4 rules)
- B (Coverity): R192 (1 rule, 254 remaining)
- C (SpotBugs): R170/R171/R182/R183 (4 rules)
- D (SonarQube): R176/R177/R180/R181/**R197** (5 rules, 701 remaining)
- E (Juliet): 0 rules, baseline 49/68

## Cumulative R148 series (41 rounds, 1 rolled back)

- 14 metric wins
- 1 validation
- **23 infra** (R186, R187, R188, R189, R192, R194, R195, **R197**)
- 1 rolled back
- **440 tests / 0 fail / 0 error / 1 skip** (390 dfa + 50 style, R197 +4)
