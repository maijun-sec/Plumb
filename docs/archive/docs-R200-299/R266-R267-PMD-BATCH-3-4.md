# R266 + R267 — PMD / SonarQube / Checkstyle Style Checkers Batch 3 + 4 (50 more rules)

## Goal
Add 50 more PMD style rules to plumb-style, building
on the 48 rules added in R265. Continue driving
toward 95% per-tool coverage.

## R266 batch 3: 25 new style rules (pl-0120 … pl-0159)

| RuleId | Source | What it catches |
|--------|--------|-----------------|
| pl-0120 | PMD | assignment to non-final static field |
| pl-0121 | PMD | for-loop with `arr[i]` (use for-each) |
| pl-0122 | PMD | `instanceof` in catch block |
| pl-0123 | PMD | `throw new X()` without original cause |
| pl-0125 | PMD | explicit `import java.lang.X` |
| pl-0126 | PMD | duplicate imports |
| pl-0127 | PMD | `if (cond) ;` (empty then) |
| pl-0128 | PMD | `static { }` empty initializer |
| pl-0129 | PMD | `switch (x) {}` no cases |
| pl-0130 | PMD | `try { }` empty block |
| pl-0132 | PMD | `==` on Strings (use `.equals()`) |
| pl-0135 | PMD | `int x = 0;` redundant default-value init |
| pl-0137 | PMD | `equals(String)` (should be Object) |
| pl-0139 | PMD | `assertEquals(true, x)` (use assertTrue) |
| pl-0140 | PMD | `list.size() == 0` (use `isEmpty()`) |
| pl-0141 | PMD | redundant parens `(x)` |
| pl-0142 | PMD | `while(true)` infinite loop |
| pl-0146 | PMD | utility class missing private ctor |
| pl-0149 | PMD | `System.out.println` (use logger) |
| pl-0151 | PMD | `throw new X()` loses stack trace |
| pl-0152 | PMD | reassign loop variable |
| pl-0153 | PMD | `i < list.size()` (cache size) |
| pl-0154 | PMD | `==` on objects (use .equals) |
| pl-0155 | PMD | `s.indexOf("a")` (use char) |
| pl-0157 | PMD | `"" + x` (use `String.valueOf`) |
| pl-0159 | PMD | `i += 2` non-1 step (jumbled incrementer) |

## R267 batch 4: 25 more style rules (pl-0160 … pl-0189)

| RuleId | Source | What it catches |
|--------|--------|-----------------|
| pl-0160 | PMD | abstract class with no abstract members |
| pl-0161 | PMD | abstract class not prefixed with `Abstract` |
| pl-0162 | PMD | assignment in if/while condition |
| pl-0163 | PMD | if-statement nested > 4 levels |
| pl-0166 | PMD | method parameter reassignment |
| pl-0167 | SpotBugs | `new BigDecimal(double)` (use String) |
| pl-0168 | PMD | class with only private ctors not final |
| pl-0169 | PMD | for-loop with `float`/`double` index |
| pl-0170 | PMD | empty catch block |
| pl-0171 | PMD | empty `finalize()` |
| pl-0175 | PMD | `finalize()` not `protected` |
| pl-0176 | PMD | for-loop with `l.get(i)` (use for-each) |
| pl-0177 | PMD | for-loop with > 1 init variable |
| pl-0178 | PMD | identical catch bodies (use multi-catch) |
| pl-0179 | PMD | non-final field never reassigned |
| pl-0180 | PMD | import from same package |
| pl-0181 | PMD | log message lacks `{}` placeholder |
| pl-0182 | PMD | JUnit assertion without message |
| pl-0183 | PMD | `@Test` method with no assertion |
| pl-0184 | PMD | local var not camelCase |
| pl-0185 | PMD | var name > 30 chars |
| pl-0186 | Checkstyle | missing `@Override` (heuristic no-op) |
| pl-0187 | PMD | strict variant of OnlyOneReturn |
| pl-0188 | PMD | hard-coded IP address literal |
| pl-0189 | PMD | method name not camelCase |

## AstChecker interface additions (R267)
- `Parameter` (for LongVariableCheck)

## Test results
- plumb-style: 249 → 275 (R266) → 298 (R267) tests, 0/0/0
- All 9 modules: 1866 tests, 0/0/0

## R267 cumulative state
- plumb-style: 137 → 187 checkers (+50)
- plumb-dfa/checkers/taint: 20 (moved in R265, no change)
- plumb-dfa legacy: 33 (untouched)
- **Total Plumb rules: 240** (was 190 in R265)

## Pitfalls fixed
1. **`InitializationDeclaration` is actually `InitializerDeclaration`** — class name typo.
2. **`Parameter` lives in `com.github.javaparser.ast.body`**, not `com.github.javaparser.ast` (R267 pitfall).
3. **`Statement.getStatements()` doesn't exist** — use `node.findAll(...)` instead of iterating body statements directly.
4. **`TryStmt.getBody()` doesn't exist** — use `getTryBlock()` (returns `BlockStmt` directly, not Optional).
5. **`for` loop variable name shadows the lambda parameter `p`** — rename the variable (e.g. `begin -> begin.line`).
6. **`EnclosedExpr` and `ThrowStmt` and `InitializerDeclaration` visit methods were missing** from `AstChecker` and `AstDispatcher` — added.
7. **String heuristic for "is String"** — match common String variable names like `str`, `name`, `text`, `msg`, `word`, `label` (instead of `s` which is too short).
8. **`info()` scope can be a chain** — `LoggerFactory.getLogger(N.class).info(...)` has a `MethodCallExpr` scope (not `FieldAccessExpr`); need to handle both.

## Backups
- `D:\work\tmp\r267_done\` — 50 new plumb-style rules + 2 test files
