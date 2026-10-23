# R265 — PMD / SonarQube / Checkstyle Style Checkers (Batches 1+2)

## Goal
Move the plumb-dfa taint package structure
into a cleaner `org.plumb.dfa.unified.checkers.taint.*`
subdirectory, and bulk-add PMD / SonarQube / Checkstyle
style rules to the existing `plumb-style` module to drive
towards the 95% per-tool coverage target.

## Constraints reaffirmed
- **Style rules** (JavaParser-based) live in
  `plumb-style` (single-walk `AstChecker` + `AstDispatcher`).
- **Data-flow / taint rules** (TAC-based) live in
  `plumb-dfa` (R153+ `TaintedCheckerBase`).
- Every new rule must ship with a test that exercises both
  the fire and the no-fire condition (single-fire, no
  overshoot).

## Stage 1: directory restructure
- 20 taint checkers moved from
  `plumb-dfa/.../taint/checker/*` to
  `plumb-dfa/.../checkers/taint/*` (package
  `org.plumb.dfa.unified.checkers.taint`).
- 7 taint-checker unit tests moved alongside.
- All `org.plumb.dfa.unified.taint.checker.*` references
  rewritten (sed-replace across plumb-dfa
  src/main + src/test).
- BOM (`EF BB BF`) stripped from all 27 moved files
  (Move-Item preserved the source BOM that the existing
  files already had).
- Empty `taint/checker/` directories removed
  (main + test).
- `checkers/` (plural, empty before) is now the
  canonical home for `checkers/taint/` and (later)
  `checkers/style/`.

## R264 pre-existing compile errors fixed
Found while building plumb-style after Stage 1. R264
work had shipped 5 broken checkers. Fixed:

| File                              | Issue                                                          |
|-----------------------------------|----------------------------------------------------------------|
| EmptyBlockCheck.java              | `import org.plumb.dba.Finding` typo (should be `dfa`); also `p` variable shadowed by lambda parameter |
| ArrayNamingCheck.java             | `visit(VariableDeclarator, ...)` not in interface → switched to `visit(VariableDeclarationExpr, ...)` and iterate |
| AvoidFinalLocalVariableCheck.java | Same `VariableDeclarator` issue                              |
| ForLoopVariableTypeCheck.java     | `n.getInitialization().ifPresent(...)` on a `NodeList` (no `ifPresent`); also `pt == PrimitiveType.Primitive.DOUBLE` (comparing a `PrimitiveType` to a `PrimitiveType.Primitive` enum) |
| UnnecessaryReturnCheck.java       | `stmpsEmpty` typo (should be `stmtsEmpty`)                   |
| EmptyStatementUsageCheck.java     | R177 contract: skip only when parent is a loop, NOT when it's an `IfStmt` (the test `firesOnEmptyIfStatement` was failing because the R264 overwrite had also skipped `if (cond) ;`) |

## AstChecker interface additions
R265 batch 1/2 needs these new visit overloads
(both interface and `AstDispatcher`):

- `NameExpr` (UnnecessaryFQN, AvoidPrintStackTrace)
- `IntegerLiteralExpr` (AvoidUsingOctalValues, MagicNumber)
- `LongLiteralExpr` (forward-compat)
- `StringLiteralExpr` (forward-compat)
- `NullLiteralExpr` (ReturnEmptyCollectionNotNull, LiteralsFirst)
- `ConditionalExpr` (ConfusingTernary)
- `ArrayCreationExpr` (UseShortArrayInitializer)
- `UnaryExpr` (forward-compat)
- `InstanceOfExpr` (forward-compat)
- `AssignExpr` (UnusedAssignment)

## R265 batch 1: 28 new style rules (pl-0072 … pl-0100)
All implemented in `plumb-style` (JavaParser-based).
Every rule has a corresponding `@Test` in
`R265PMDStyleBatch1Test.java`.

| RuleId | Source | What it catches |
|--------|--------|-----------------|
| pl-0072 | PMD | `catch (Throwable t)` / `catch (Error e)` |
| pl-0073 | PMD | `System.gc()` / `Runtime.gc()` |
| pl-0074 | PMD | `class N extends Error` / `extends Throwable` |
| pl-0075 | PMD | `MessageDigest` as a field (not thread-safe) |
| pl-0076 | PMD | `native` methods |
| pl-0077 | PMD | `ThreadGroup` (use `Executor`) |
| pl-0078 | PMD | octal literals (`0755`) |
| pl-0079 | PMD | `volatile` fields (misuse as silver-bullet) |
| pl-0080 | PMD | `getXxx()` returning `boolean` (should be `isXxx()`) |
| pl-0081 | PMD | subclass constructor missing `super(...)` |
| pl-0082 | PMD | nested ternary `a ? b : c ? d : e` |
| pl-0083 | PMD | `new Date()` (use `Calendar`/`LocalDate`) |
| pl-0084 | PMD | `StringBuffer` as a field |
| pl-0085 | Checkstyle | control statement without `{ }` |
| pl-0086 | PMD | `toLowerCase().equals(...)` (use `equalsIgnoreCase`) |
| pl-0087 | PMD | `new int[] { 1, 2, 3 }` (use `int[] x = { 1, 2, 3 }`) |
| pl-0088 | PMD | `protected` in `final` class (use `private`) |
| pl-0089 | PMD | `assertTrue(a == b)` (use `assertEquals`) |
| pl-0090 | PMD | `assertTrue(x == null)` (use `assertNull`) |
| pl-0091 | PMD | `assertTrue(a == b)` for identity (use `assertSame`) |
| pl-0092 | PMD | non-final `HashMap` field (use `ConcurrentHashMap`) |
| pl-0093 | SonarQube | `return null` for `List`/`Map`/etc. (return empty) |
| pl-0094 | PMD | `equals(String)` instead of `equals(Object)` |
| pl-0096 | PMD | `new ArrayList<String>()` (use `new ArrayList<>()`) |
| pl-0097 | SonarQube | `java.util.List<String>` (use import + short name) |
| pl-0098 | PMD | utility method `return new Foo()` (different return type) |
| pl-0099 | PMD | assignment expression (verify value is used) |
| pl-0100 | Checkstyle | method with > 50 statements |

## R265 batch 2: 20 more style rules (pl-0101 … pl-0119)
5 of these overwrote existing rules (UnusedFormalParameter,
UnusedLocalVariable, UnusedImport, EmptyFinallyBlock,
LocalVariableCouldBeFinal, LiteralsFirstInComparisons) —
the new implementation matches the existing test contract
(annotation-aware UnusedImport, while-loop skip for
EmptyStatementUsage, SONAR_LITERALS_FIRST_IN_COMPARISONS
ruleId name for R263RefactorTest).

The other 14 are new:

| RuleId | Source | What it catches |
|--------|--------|-----------------|
| pl-0104 | PMD | method arg could be `final` (not reassigned) |
| pl-0106 | PMD | method with > 1 `return` (use single exit) |
| pl-0107 | PMD | `return` in `finally` (silently swallows exceptions) |
| pl-0108 | PMD | `s + ","` in a loop (use `StringBuilder`) |
| pl-0109 | PMD | `new String("...")` (string literal is interned) |
| pl-0110 | PMD | ctors declared but no no-arg ctor |
| pl-0111 | PMD | explicit empty constructor (Java provides one) |
| pl-0112 | PMD | non-instantiable class with no static members |
| pl-0113 | PMD | empty public method (use `abstract` or remove) |
| pl-0114 | PMD | local var without initializer |
| pl-0115 | PMD | non-`transient` field in `Serializable` class |
| pl-0116 | SonarQube | `new SimpleDateFormat(pattern)` (needs `Locale`) |
| pl-0117 | SonarQube | `ParseException` swallowed without log |
| pl-0118 | PMD | class in default package |
| pl-0119 | SonarQube | `x.equals("foo")` (literal first to avoid NPE) |

## Test results
- **plumb-style**: 188 → 230 (R265 batch 1) → 249 (R265 batch 2) tests, 0/0/0
- **plumb-dfa**: 391 tests, 0/0/0 (after Stage 1 move)
- **All 9 modules**: 1768 tests, 0 failures, 0 errors, 0 skipped

## R265 cumulative state
- plumb-style: 95 → 137 checkers (+42)
- plumb-dfa/checkers/taint: 20 checkers (moved)
- plumb-dfa legacy: 33 checkers (untouched — will be moved in a later round)
- **Total Plumb rules: 190**

## Pitfalls fixed
1. **`Move-Item` preserves UTF-8 BOM** — all moved files
   had to have `EF BB BF` stripped, otherwise
   `javac --release 17` rejected them with
   "非法字符: '\ufeff'" on line 1.
2. **`Set-Content -Encoding UTF8` writes BOM** — when
   using PowerShell to rewrite files in place, must
   use `[System.IO.File]::WriteAllText($f, $c, (New-Object
   System.Text.UTF8Encoding $false))` to avoid the BOM
   appearing after rewrite.
3. **`BodyDeclaration<?>.getModifiers()` doesn't exist** —
   must narrow to `NodeWithModifiers` interface check.
4. **`ConstructorDeclaration.getBody()` returns `BlockStmt`** (not
   `Optional<BlockStmt>`) — direct access, no `.get()`.
5. **`IfStmt.getElseStmt()` returns `Optional<Statement>`** —
   must `.orElse(null)` before passing to a `Statement`-typed
   parameter.
6. **`Optional<NodeList>.ifPresent(...)` doesn't exist** — use
   `for (Expression init : n.getInitialization())` instead.
7. **`PrimitiveType == PrimitiveType.Primitive.DOUBLE` is a
   type error** — compare via `pt.asString().equals("double")`
   or `pt.getPrimitive() == ...` (the latter not always
   available in 3.26.4).
8. **`instanceof Foo bar` pattern matching requires Java 21+** —
   plumb targets Java 17, so use
   `if (x instanceof Foo) { Foo bar = (Foo) x; ... }`.
9. **`RuleDef.Type` enum has no `CONFUSING` or `UNUSED`** —
   use `BAD_PRACTICE` or `STYLE`.
10. **`AsString()` includes the FQN**; `getNameAsString()`
    returns only the simple name. For
    `new java.lang.String("x")` the type's `getNameAsString()`
    is `"String"`, not `"java.lang.String"`.
11. **AnnotationExpr check is required for unused-import
    detection** — `jakarta.persistence.Entity` used as
    `@Entity` doesn't appear in `NameExpr` / `ClassOrInterfaceType`
    lookups; the AST has a separate `AnnotationExpr` node that
    carries the simple name.

## Backups
- `D:\work\tmp\r265_done\` — 142 plumb-style files (main + test)
- `D:\work\tmp\r265_done\taint\` — 20 plumb-dfa taint checkers
- `D:\work\tmp\r265_done\taint_test\` — 7 taint unit tests
