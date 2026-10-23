# R269 + R270 — PMD + SonarQube Gap-Filling (查漏补缺)

## Goal
Audit existing rules, find the PMD/SonarQube rules
NOT yet covered, and add them.

## R269 PMD gap-filling batch (27 rules, pl-0217 … pl-0242)

| RuleId | PMD Name | What it catches |
|--------|----------|-----------------|
| pl-0217 | EmptyWhileStmt | `while (cond) ;` empty body |
| pl-0218 | IfElseStmt | long if/else if chain |
| pl-0219 | NullAssignment | `x = null` reassignment |
| pl-0220 | ShortClassName | class < 3 chars |
| pl-0221 | ShortMethodName | method < 2 chars |
| pl-0222 | ShortVariable | var < 2 chars |
| pl-0223 | SimplifyBooleanExpressions | `x == true` |
| pl-0224 | SimplifyBooleanReturns | `cond ? true : false` |
| pl-0225 | SimplifyConditional | ternary same branches |
| pl-0226 | SingularField | field used in only 1 method |
| pl-0227 | TooManyFields | class > 15 fields |
| pl-0228 | TooManyMethods | class > 30 methods |
| pl-0229 | UseVarargs | array as last param |
| pl-0230 | InstantiationToGetClass | `new X().getClass()` |
| pl-0231 | MoreThanOneLogger | > 1 logger field |
| pl-0232 | PackageCase | non-lowercase package |
| pl-0233 | ProperLogger | non-conventional logger name |
| pl-0234 | NoPackage | no package declaration |
| pl-0235 | FinalizeDoesNotCallSuperFinalize | `finalize()` w/o `super.finalize()` |
| pl-0236 | NonCaseLabelInSwitchStatement | labeled stmt in switch |
| pl-0237 | ReturnEmptyCollectionRatherThanNull | `return null` for collection |
| pl-0238 | UncommentedEmptyMethod | empty public method w/o comment |
| pl-0239 | CouplingBetweenObjects | > 20 coupled classes |
| pl-0240 | DataClass | data class (> 5 fields, < 3 methods) |
| pl-0241 | LoosePackageCoupling | > 30 type references |
| pl-0242 | UnnecessaryReturn | `return` as last stmt |

## R270 SonarQube gap-filling batch (16 rules, pl-0243 … pl-0259)

| RuleId | SonarQube | What it catches |
|--------|-----------|-----------------|
| pl-0243 | S101 | class name not PascalCase |
| pl-0244 | S108 | empty block |
| pl-0245 | S1144 | unused private field |
| pl-0246 | S1155 | array variable (Map/Set key risk) |
| pl-0247 | S1170 | public constant not SCREAMING_SNAKE_CASE |
| pl-0248 | S1217 | local var declared and returned |
| pl-0249 | S1228/S138 | method > 50 statements |
| pl-0250 | S1301 | switch < 3 cases |
| pl-0251 | S1319 | class name > 30 chars |
| pl-0252 | S135 | loop with multiple break/continue |
| pl-0253 | S1444 | throws RuntimeException |
| pl-0255 | S1611 | paren around method call |
| pl-0256 | S1622 | method just delegates |
| pl-0257 | S2147 | catch w/o using exception |
| pl-0258 | S1141/S2583 | if/while with constant condition |
| pl-0259 | S1449 | public mutable field |

## Test results
- plumb-style: 315 → 339 (R269) → 355 (R270) tests, 0/0/0
- plumb-style: 206 → 233 (R269) → 249 (R270) checkers

## Pitfalls fixed
1. **`NoPackageCheck` requires parent to be `CompilationUnit`** — not `TypeDeclaration` (R269 pitfall).
2. **`IfElseStmtCheck` chainLength < 2 not < 3** — a 3-branch chain (1 if + 2 else if) fires.
3. **`VariableDeclarationExpr` is wrapped in `ExpressionStmt`** when used as a statement. The parent chain is `BlockStmt → ExpressionStmt → VariableDeclarationExpr`. So `n.getParentNode()` is `ExpressionStmt`, not `BlockStmt`. Walk through the ExpressionStmt to find the next sibling in the block.
4. **`RuleDef.Type.UNUSED` doesn't exist** — use `STYLE`.
5. **`LambdaExpr` visit method not in `AstChecker` interface** — skip the check or implement via generic `Node` visit (which also isn't in interface). Best to drop the check.
6. **`Finding.Severity.LOW` (NOT `RuleDef.Severity.LOW`)** — `ctx.report()` uses `Finding.Severity`.

## Backups
- `D:\work\tmp\r270_done\` — 43 R269+R270 files
