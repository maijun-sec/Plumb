# R268 — SonarQube Style Checkers Batch 1 (19 rules)

## Goal
Add SonarQube-style rules to plumb-style. Continue
driving toward 95% SonarQube coverage.

## R268 batch 1: 19 new SonarQube rules (pl-0190 … pl-0216)

| RuleId | SonarQube | What it catches |
|--------|-----------|-----------------|
| pl-0190 | S1075 | hard-coded URI (`http://...`, `ftp://...`) |
| pl-0191 | S1199/S102 | nested block (`{ ... }` inside a method) |
| pl-0193 | S1104 | class field has same name as class |
| pl-0194 | S1117 | local variable shadows a field |
| pl-0195 | S1125 | `!true` / `!false` literal |
| pl-0196 | S1126 | method always returns true/false |
| pl-0197 | S1141 | try inside catch |
| pl-0199 | S1214 | interface with only static fields |
| pl-0200 | S1163/S1174 | throw in finally block |
| pl-0202 | S1181 | catch (Throwable) / catch (Error) |
| pl-0204 | S1192 | string literal duplicated 3+ times |
| pl-0206 | S1206 | equals without hashCode (or vice versa) |
| pl-0207 | S1213 | class member could be private (heuristic no-op) |
| pl-0208 | S1215 | Logger field should be static final |
| pl-0209 | S1264 | switch without default branch |
| pl-0213 | S1526/S1625 | `==` on Strings (use `.equals()`) |
| pl-0215 | S1626 | constructor calls overridable method |
| pl-0216 | S2055 | if/while with constant condition |

## Test results
- plumb-style: 298 → 315 tests, 0/0/0
- plumb-style: 187 → 206 checkers (+19)

## Pitfalls fixed
1. **`ConditionalExpr` is in `com.github.javaparser.ast.expr`**, not `.stmt` (R268 pitfall).
2. **`RuleDef.Type.MAINTAINABILITY` doesn't exist** — use `STYLE` or `BAD_PRACTICE`.
3. **Interface fields are implicitly `public static final`** — checking for explicit `static` modifier fails on interface constants. Just check that all members are `FieldDeclaration` declarations.

## Backups
- `D:\work\tmp\r268_done\` — 19 new plumb-style rules + 1 test file
