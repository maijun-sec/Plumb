# Implementation Design of the Style Rules (plumb-style)

[English](../README.md) | [中文](../zh/tech/STYLE-RULES.md)

This document describes the overall architecture, the registration mechanism and the implementation patterns of the 500+ style-category rules in the `plumb-style` module.

## 1. Design goals

| Goal | Approach |
| --- | --- |
| **Fully independent of TAC/DFA** | `plumb-style` depends only on `plumb-parser` (which provides the `CompilationUnit` AST); it does not need `plumb-tac` |
| **One traversal, many checkers** | `AstDispatcher` visits the `CompilationUnit` once and dispatches every node to all checkers |
| **Pluggable / prunable** | A rule is a plain `AstChecker` subclass; it registers itself automatically through a classpath scan at startup |
| **Prunable by category** | `AstCheckerRegistry.byCategory("naming", "design")` loads only the named subdirectories |
| **Comparable with external tools (PMD / Checkstyle)** | The `aliases()` field records the IDs of equivalent external rules, so downstream consumers can offer a "see also" hint |

## 2. Overall architecture

```
                    .java
                      │
                      ▼
              JavaSourceParser  ← plumb-parser
                      │
                      ▼
                ParsedSource
                      │
                      ▼
                AstDispatcher  ← plumb-style
                ┌─────┴─────┐
        visit(node)│           │ visit(node)
                ▼             ▼
       AstChecker #1   AstChecker #2 ... AstChecker #N
                │             │                │
                └─────────────┴────────────────┘
                              │
                              ▼
                       StyleContext
                              │
                              ▼
                       List<Finding>
```

The input is the `CompilationUnit` AST of JavaParser, not TAC. Every `AstChecker` visits the AST nodes directly and does pattern matching.

## 3. The `AstChecker` interface

```java
public interface AstChecker {
    String name();                    // unique name: "empty-catch"
    void init(StyleContext ctx);      // initialisation (optional override)

    // 54 per-node visit overloads, all of them default methods:
    //   default void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {}
    //   default void visit(MethodDeclaration n, StyleContext ctx) {}
    //   ... 54 node types in total
    // There is also visit(CompilationUnit, StyleContext) as a catch-all.

    default void finish(StyleContext ctx) {}                 // finalisation
    default org.plumb.rules.RuleDef rule() { ... }           // rule metadata
    default String getAiPrompt(Finding finding) { ... }      // AI review prompt
}
```

**Key point**: `visit` is a set of **per-node overloads** (**54** of them), not a single
`visit(CompilationUnit, ctx)`. `AstDispatcher` calls `visit(X, ctx)` once for each
node type, so a checker only overrides the one overload it cares about -- see
[`../rules/RULE-AUTHORING.md`](../rules/RULE-AUTHORING.md) §13.0 for the choice of pattern.

The main public API of `StyleContext` (`plumb-style/.../StyleContext.java`):

| Method | Purpose |
| --- | --- |
| `source()` | The current `ParsedSource` (to get the AST / the source file) |
| `rules()` | The current `RuleRegistry` (to look up `RuleDef`) |
| `findings()` | The list of `Finding` objects accumulated so far |
| `report(...)` | Reports one finding (two overloads: position + rule, or with dataflow) |
| `paramOverride(name, type)` | Reads a rule parameter overridden in YAML (§9.1 of RULE-STYLE-OP) |
| `setCurrentRule(ruleId)` / `currentRule()` | The rule id currently in effect |
| `forceEnable(String...)` | Escape hatch: force certain rules on (used by smoke tests) |
| `typeSolver()` / `resolvedTypeOf(...)` / `resolveDecl(...)` | Type resolution |

Checkers do **not** share mutable state with each other: every checker only adds
to `findings()` through `ctx.report(...)`. To pass state between checkers, go
through the parameter overrides of `StyleContext` or a singleton you implement
yourself -- do not write shared fields.

## 4. `AstDispatcher` (per-node dispatch)

```java
public final class AstDispatcher {
    private final List<AstChecker> checkers = new ArrayList<>();
    public AstDispatcher register(AstChecker c);
    public void run(StyleContext ctx);
}
```

`run(ctx)` calls `checker.visit(X, ctx)` once for **every node type** that occurs in
the AST. Therefore:

- A checker only overrides the node overloads it cares about -> it does not need to `findAll` the whole tree internally
- Calling `cu.findAll(...)` inside `visit` causes the same file to be walked several times, which is an **anti-pattern**
  (see the red-line table in RULE-AUTHORING.md §13.0)

## 5. `AstCheckerRegistry` (dynamic discovery + categorised loading)

```java
public final class AstCheckerRegistry {
    public AstCheckerRegistry builtins();        // loads every discoverable checker
    public AstCheckerRegistry load(String... globs);   // by simple class name glob
    public AstCheckerRegistry byCategory(String... categories);
}
```

`builtins()` calls `discoverAvailable()`: it scans every class under `org/plumb/style/`
(classpath directory or jar) and finds the instantiable implementations of `AstChecker`
(interfaces, abstract classes and inner classes `$` are excluded).

**Advantage**: adding a checker means writing one `*Check.java` plus `implements AstChecker`;
no registry has to be touched.

## 6. Rule categories

The 9 subdirectories correspond to the 9 major categories (these define the semantics of the `byCategory` filter). The "file count" in the table below is the measured number of files in
`plumb-style/src/main/java/org/plumb/style/<category>/*Check.java`, and the
"registered count" is the number of entries recorded in `CheckerCategories.CHECKER_CATEGORIES`:

| Directory | Topic | Files | Registered | Typical rules |
| --- | --- | ---: | ---: | --- |
| `style/` | Coding style (comments, Javadoc, indentation, imports, control flow) | 353 | 354 | `AbsentCodeblockInCaseCheck`, `AssignmentInOperandCheck` |
| `naming/` | Naming conventions | 51 | 51 | `AbstractCheck`, `ArrayNamingCheck` |
| `performance/` | Performance | 63 | 63 | `ArrayEqualityCheck`, `ArrayStoreIncompatibleCheck` |
| `security/` | Security-related | 42 | 41 | `AvoidUsingHardCodedIPCheck`, `HardcodedCredentialsCheck` |
| `error/` | Error handling | 35 | 35 | `AssignmentToCatchBlockParameterCheck`, `AvoidCatchingThrowableCheck` |
| `bug/` | Clear bug patterns | 20 | 20 | `CompareObjectsWithEqualsCheck`, `IncorrectReturnValueCheckChecker` |
| `design/` | Design metrics (cyclomatic complexity, coupling, depth) | 18 | 18 | `AbstractClassCouplingCheck`, `BooleanExpressionComplexityCheck` |
| `quality/` | Domains such as ORM | 10 | 10 | `AlwaysFalseCheck`, `OrmLoadNullCheckCheck` |
| `concurrency/` | Concurrency | 2 | 2 | `ServletAtomicityCheck`, `SingletonRaceCheck` |
| **Total** | | **596** | **594** | |

Additional notes:

- The **root** directory `plumb-style/src/main/java/org/plumb/style/` holds 2 more classes
  (the `AstChecker` interface and `ObjectHijackCheck`) that belong to no subdirectory category;
  they are not selected by the `byCategory(...)` filter.
- The `annotations/` and `config/` directories contain no checkers; they hold the annotation IR utilities used by the DFA engine.
- The 2 entries whose file count and registered count disagree:
  - Not registered: `ObjectHijackCheck` (root directory) -- `byCategory` cannot load it, it can only be registered explicitly via
    `load("<glob>")` or `register(...)`.
  - Registered but the file name does not match the glob: a checker class name does not have to end with `Check`
    (e.g. `IncorrectReturnValueCheckChecker`, `UseObjectForClearerAPI`), so the `*Check.java` glob misses them.
- `InsecureTempFilePermsCheck` is registered in `CheckerCategories` under the category
  `style`, but it actually lives in the `security/` directory -- `byCategory("style")` selects it,
  `byCategory("security")` does not.

`CheckerCategories.java` is generated by `scripts/gen_categories.py`
(`python scripts/gen_categories.py --check` verifies that it is up to date).

`byCategory("naming")` is equivalent to loading only the 51 checkers whose category is
`naming` in `CheckerCategories.CHECKER_CATEGORIES`.

## 7. Implementation pattern: a typical checker

### 7.1 Simple pattern matching (per-node override)

Real example: `plumb-style/src/main/java/org/plumb/style/error/EmptyCatchBlockCheck.java`

```java
public final class EmptyCatchBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-empty-catch-block", "EMPTY_CATCH_BLOCK"),
          "empty catch block — strict",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyCatchBlockCheck",
          true,
          List.of("ast-error-catch-block-strict", "empty-catch-block"));   // alias

  @Override
  public String name() {
    return "empty-catch-block";
  }

  @Override
  public void visit(CatchClause cc, StyleContext ctx) {     // ← per-node override
    if (!cc.getBody().getStatements().isEmpty()) return;
    int line = cc.getBegin().map(p -> p.line).orElse(-1);
    int col  = cc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty catch block; use logging or rethrow",
        RuleDef.Severity.MEDIUM,
        "catch");                                               // dataflowHead
  }
}
```

`ctx.report` has two overloads:

```java
void report(int line, int col, RuleDef rule, String message,
            RuleDef.Severity severity, String dataflowHead);

void report(int line, int col, RuleDef rule, String message,
            RuleDef.Severity severity, String dataflowHead, String sinkFunction);
```

Note that what is overridden here is **`visit(CatchClause, ctx)`**, not `visit(CompilationUnit, ctx)`
-- the latter degenerates into the old "one `findAll` over the whole tree per file" style.

### 7.2 Naming rules (per-node override)

Naming-category checkers are also standalone `final class`es, with **no common base class**. Real example:
`plumb-style/src/main/java/org/plumb/style/naming/AbstractNamingCheck.java`

```java
public final class AbstractNamingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-abstract-naming", "ABSTRACT_NAMING"),
          "abstract class should be named starting with Abstract (heuristic)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AbstractNamingCheck",
          true,
          List.of("ast-naming-abstract-naming", "abstract-naming"));

  @Override
  public String name() {
    return "abstract-naming";
  }

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.isAbstract()) return;
    if (c.getNameAsString().toLowerCase().startsWith("abstract")) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col  = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE,
        "Abstract class should be named Abstract" + c.getNameAsString(),
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}
```

### 7.3 Complexity rules (accumulated statistics)

Complexity-category rules have to aggregate at the **method level**. `CyclomaticComplexityCheck` (`plumb-style/.../design/`)
overrides `visit(MethodDeclaration, ctx)` and counts the various branch nodes with `findAll`
inside the method body -- this kind of "statistics inside a method" is a legitimate exception
to the per-node pattern: it does not walk the whole tree repeatedly across methods.

```java
public final class CyclomaticComplexityCheck implements AstChecker {
    @Override public String name() { return "cyclomatic-complexity"; }

    @Override
    public void visit(MethodDeclaration m, StyleContext ctx) {
        int complexity = 1;
        complexity += m.findAll(IfStmt.class).size();
        complexity += m.findAll(ForStmt.class).size();
        complexity += m.findAll(WhileStmt.class).size();
        complexity += m.findAll(CatchClause.class).size();
        complexity += m.findAll(BinaryExpr.class).stream()
            .filter(b -> b.getOperator() == BinaryExpr.Operator.AND
                      || b.getOperator() == BinaryExpr.Operator.OR)
            .count();
        if (complexity > THRESHOLD) {
            ctx.report(line, col, DEFAULT_RULE, "...", RuleDef.Severity.LOW, null);
        }
    }
}
```

### 7.4 Checkers that need several node types aggregated

Some rules inherently have to look at several kinds of node. Such a checker overrides
**several** per-node overloads instead of falling back to `visit(CompilationUnit)`
plus a whole-tree `findAll`.

Real example: `plumb-style/src/main/java/org/plumb/style/style/UnusedImportCheck.java`
-- it has to decide whether an import is used, so it overrides all the "name reference" node types:

```java
public final class UnusedImportCheck implements AstChecker {
    @Override public String name() { return "unused-import"; }

    @Override public void visit(NameExpr x, StyleContext ctx) { /* record usage */ }
    @Override public void visit(ClassOrInterfaceType t, StyleContext ctx) { /* record usage */ }
    @Override public void visit(AnnotationExpr a, StyleContext ctx) { /* record usage */ }
    @Override public void visit(MarkerAnnotationExpr a, StyleContext ctx) { /* ... */ }
    @Override public void visit(NormalAnnotationExpr a, StyleContext ctx) { /* ... */ }
    @Override public void visit(SingleMemberAnnotationExpr a, StyleContext ctx) { /* ... */ }
}
```

Every checker sees the **same** `StyleContext`, but checkers do not share mutable state with each other.

## 8. Mapping to `plumb-rules`

`AstChecker` is itself an AST visitor and does not depend on `RuleRegistry` directly. But the `Finding` model requires a
`rule` field. The convention is:

- Every `*Check` class holds a `private static final RuleDef DEFAULT_RULE`
- `ctx.report(line, col, DEFAULT_RULE, message, severity, dataflowHead)` uses it directly
- `RuleRegistry.registerFromCheckers(...)` (`plumb-rules/.../RuleRegistry.java:268`)
  reflects the `rule()` of the checkers into the registry when the scan starts, so that `rules.enabled` /
  `rules.disabled` can match by id / short name / alias

A checker without a `RuleRegistry` constant still works -- it uses its own `DEFAULT_RULE`.
But only checkers **registered in `CheckerCategories`** can be selected by `byCategory(...)`
(see §6).

## 9. Equivalence table with Checkstyle / PMD

The `RuleDef` of every style rule carries an `aliases` list (the last parameter of the `RuleDef` constructor)
that lists the IDs of equivalent external rules. **These aliases are mainly this repository's own historical ids**
(such as `ast-error-catch-block-strict`, `empty-catch-block`), so that `rules.enabled` /
`rules.disabled` can match them in several spellings.

The equivalent IDs of external tools (PMD / SpotBugs / SonarQube / Checkstyle / Coverity) are **not**
read from the aliases. They are parsed heuristically from the checkers' Javadoc by
`scripts/gen_rules_doc.py`, which generates the "externally equivalent rules" section of
[`../plumb-style-rules.md`](../plumb-style-rules.md). Downstream consumers (IDE plugins, SARIF converters) that want
to show a "see also" hint should read that generated document, not `RuleDef.aliases()`.

## 10. Enabling / disabling style rules

`plumb-style` has 595 checkers in total, and the vast majority of them default to **enabled = true**. Fine-grained control:

- **`checks.style: false`**: turns off all style rules (the switch is in `ScanConfig.runStyle`).
- **`rules.disabled`** / **`rules.enabled`**: controls individual rules by rule id, short name or alias (see
  [`../usage/CLI.md`](../usage/CLI.md) §4 and
  [`../rules/RULE-STYLE-OP.md`](../rules/RULE-STYLE-OP.md) §2).
- **Modifying the source code**: if you only want to skip a rule in your own project, there is **currently no
  "project-level disable file" mechanism** -- you have to use the YAML `rules.disabled`, or change the `enabled`
  parameter of the checker's `DEFAULT_RULE` to `false` (which requires a rebuild).

## 11. Testing conventions

Every rule has at least 1 **positive test** (it should fire) and 1 **negative test** (it should not fire). The positive test asserts with AssertJ that the number of findings is >= 1, the negative test asserts == 0.

```java
@Test
void emptyCatchFires() {
    String src = "class T { void m() { try { } catch (Exception e) {} } }";
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).rule().ruleId().name()).isEqualTo("STYLE_EMPTY_CATCH");
}
```
