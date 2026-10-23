# 风格类规则（plumb-style）实现方案

本文档说明 plumb-style 模块中 500+ 风格类规则的整体架构、注册机制和实现模式。

## 1. 设计目标

| 目标 | 方案 |
| --- | --- |
| **完全独立于 TAC/DFA** | plumb-style 只依赖 `plumb-parser`（提供 CompilationUnit AST），不需要 `plumb-tac` |
| **单次遍历多个 checker** | 引入 `AstDispatcher`，一次访问 CompilationUnit，把每个节点派发给所有 checker |
| **可插拔 / 可裁剪** | 规则是普通的 `AstChecker` 子类，启动时 classpath 扫描自动注册 |
| **可按类目裁剪** | `AstCheckerRegistry.byCategory("naming", "design")` 只加载指定子目录 |
| **与外部工具（PMD / Checkstyle）可比** | 通过 `aliases()` 字段记录外部规则的 ID，下游消费方可做"see also"提示 |

## 2. 整体架构

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

输入是 JavaParser 的 `CompilationUnit`（AST），不是 TAC。每个 `AstChecker` 直接访问 AST 节点做模式匹配。

## 3. `AstChecker` 接口

```java
public interface AstChecker {
    String name();                    // 唯一名："empty-catch"
    void init(StyleContext ctx);      // 初始化（可选覆写）

    // 54 个 per-node visit 重载，全部是 default 方法：
    //   default void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {}
    //   default void visit(MethodDeclaration n, StyleContext ctx) {}
    //   ... 共 54 种节点类型
    // 另有 visit(CompilationUnit, StyleContext) 作为兜底。

    default void finish(StyleContext ctx) {}                 // 收尾
    default org.plumb.rules.RuleDef rule() { ... }           // 规则元数据
    default String getAiPrompt(Finding finding) { ... }      // AI 复审 prompt
}
```

**关键点**：`visit` 是 **per-node 重载**（54 个），不是只有一个
`visit(CompilationUnit, ctx)`。`AstDispatcher` 对每种节点各调一次
`visit(X, ctx)`，所以 checker 覆写自己关心的那一个重载即可 —— 详见
[`../rules/RULE-AUTHORING.md`](../rules/RULE-AUTHORING.md) §13.0 的 Pattern 选型。

`StyleContext`（`plumb-style/.../StyleContext.java`）的主要公开 API：

| 方法 | 作用 |
| --- | --- |
| `source()` | 当前 `ParsedSource`（拿 AST / 源文件） |
| `rules()` | 当前 `RuleRegistry`（查 `RuleDef`） |
| `findings()` | 已累计的 `Finding` 列表 |
| `report(...)` | 上报一条 finding（两个重载：位置 + 规则 / 或带 dataflow） |
| `paramOverride(name, type)` | 读 YAML 覆盖的规则参数（§9.1 of RULE-STYLE-OP） |
| `setCurrentRule(ruleId)` / `currentRule()` | 当前生效的 rule id |
| `forceEnable(String...)` | escape hatch：强制开某些规则（smoke test 用） |
| `typeSolver()` / `resolvedTypeOf(...)` / `resolveDecl(...)` | 类型解析 |

checker 之间**不共享可变状态**：每个 checker 只通过 `ctx.report(...)` 往
`findings()` 里加东西。要在 checker 之间传状态，走 `StyleContext` 的
参数覆盖或自己实现的单例，不要写共享字段。

## 4. `AstDispatcher`（per-node 派发）

```java
public final class AstDispatcher {
    private final List<AstChecker> checkers = new ArrayList<>();
    public AstDispatcher register(AstChecker c);
    public void run(StyleContext ctx);
}
```

`run(ctx)` 对 AST 里出现的**每种节点类型**各调一次
`checker.visit(X, ctx)`。因此：

- checker 只覆写自己关心的节点重载 → 不需要在内部 `findAll` 全树扫描
- 在 `visit` 里调 `cu.findAll(...)` 会导致同一文件被多次 walk，是**反模式**
  （详见 RULE-AUTHORING.md §13.0 的红线表）

## 5. `AstCheckerRegistry`（动态发现 + 分类加载）

```java
public final class AstCheckerRegistry {
    public AstCheckerRegistry builtins();        // 加载所有可发现的 checker
    public AstCheckerRegistry load(String... globs);   // 按 simple class name glob
    public AstCheckerRegistry byCategory(String... categories);
}
```

`builtins()` 调用 `discoverAvailable()`：扫描 `org/plumb/style/` 下的所有 class（classpath 目录或 jar），找到 `AstChecker` 的可实例化实现（排除接口、抽象类、内部类 `$`）。

**优势**：新增 checker = 写一个 `*Check.java` + `implements AstChecker`，无需改任何注册表。

## 6. 规则分类

9 个子目录对应 9 大类（决定 `byCategory` 过滤的语义）。下表的「数量」是
`plumb-style/src/main/java/org/plumb/style/<目录>/*Check.java` 的实测文件数，
「注册数」是 `CheckerCategories.CHECKER_CATEGORIES` 里登记的条目数：

| 目录 | 主题 | 文件数 | 注册数 | 典型规则 |
| --- | --- | ---: | ---: | --- |
| `style/` | 编码风格（注释、Javadoc、缩进、imports、控制流） | 354 | 353 | `AbsentCodeblockInCaseCheck`, `AssignmentInOperandCheck` |
| `naming/` | 命名约定 | 51 | 51 | `AbstractCheck`, `ArrayNamingCheck` |
| `performance/` | 性能 | 63 | 63 | `ArrayEqualityCheck`, `ArrayStoreIncompatibleCheck` |
| `security/` | 安全相关 | 41 | 41 | `AvoidUsingHardCodedIPCheck`, `HardcodedCredentialsCheck` |
| `error/` | 错误处理 | 35 | 35 | `AssignmentToCatchBlockParameterCheck`, `AvoidCatchingThrowableCheck` |
| `bug/` | 明确 bug 模式 | 20 | 20 | `CompareObjectsWithEqualsCheck`, `IncorrectReturnValueCheckChecker` |
| `design/` | 设计度量（圈复杂度、耦合、深度） | 18 | 18 | `AbstractClassCouplingCheck`, `BooleanExpressionComplexityCheck` |
| `quality/` | ORM 等领域 | 10 | 10 | `AlwaysFalseCheck`, `OrmLoadNullCheckCheck` |
| `concurrency/` | 并发 | 2 | 2 | `ServletAtomicityCheck`, `SingletonRaceCheck` |
| **合计** | | **595** | **593** | |

补充说明：

- `plumb-style/src/main/java/org/plumb/style/` **根目录**下还有 1 个 checker
  （`ObjectHijackCheck`）不属于任何子目录分类，`byCategory(...)` 过滤时不会被选中。
- `annotations/` 与 `config/` 目录不含 checker，是给 DFA 引擎用的注解 IR 工具。
- 文件数与注册数不一致的 2 个条目：
  - 未登记：`ObjectHijackCheck`（根目录）—— `byCategory` 加载不到它，只能靠
    `load("<glob>")` 或 `register(...)` 显式注册。
  - 登记但文件名不匹配 glob：checker 类名不必以 `Check` 结尾
    （如 `IncorrectReturnValueCheckChecker`、`UseObjectForClearerAPI`），
    所以 `*Check.java` 这个 glob 会漏掉它们。
- `CheckerCategories` 里 `InsecureTempFilePermsCheck` 登记的 category 是
  `style`，但它实际位于 `security/` 目录 —— `byCategory("style")` 能选中它，
  `byCategory("security")` 不能。

`CheckerCategories.java` 由 `scripts/gen_categories.py` 生成
（`python scripts/gen_categories.py --check` 可验证它是不是最新的）。

`byCategory("naming")` 等价于只加载 `CheckerCategories.CHECKER_CATEGORIES`
里 category 为 `naming` 的 51 个 checker。

## 7. 实现模式：典型 Checker

### 7.1 简单模式匹配（per-node 覆写）

真实例子：`plumb-style/src/main/java/org/plumb/style/error/EmptyCatchBlockCheck.java`

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
  public void visit(CatchClause cc, StyleContext ctx) {     // ← per-node 覆写
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

`ctx.report` 有两个重载：

```java
void report(int line, int col, RuleDef rule, String message,
            RuleDef.Severity severity, String dataflowHead);

void report(int line, int col, RuleDef rule, String message,
            RuleDef.Severity severity, String dataflowHead, String sinkFunction);
```

注意这里**覆写的是 `visit(CatchClause, ctx)`**，不是 `visit(CompilationUnit, ctx)`
——后者会退化成"每个文件全树 `findAll` 一次"的老写法。

### 7.2 命名类（per-node 覆写）

命名类 checker 也是独立的 `final class`，**没有公共基类**。真实例子：
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

### 7.3 复杂度类（累积统计）

复杂度类需要在**方法级**聚合。`CyclomaticComplexityCheck`（`plumb-style/.../design/`）
覆写的是 `visit(MethodDeclaration, ctx)`，在方法体内部 `findAll` 各类分支节点计数
—— 这类"方法内部统计"是 per-node 模式的合理例外：它不跨方法重复 walk 整棵树。

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

### 7.4 需要多节点聚合的 checker

有些规则天然需要看多种节点。这类 checker 覆写**多个** per-node 重载，
而不是回到 `visit(CompilationUnit)` + 全树 `findAll`。

真实例子：`plumb-style/src/main/java/org/plumb/style/style/UnusedImportCheck.java`
—— 它要判断 import 是否被用到，所以覆写了所有"名字引用"类的节点：

```java
public final class UnusedImportCheck implements AstChecker {
    @Override public String name() { return "unused-import"; }

    @Override public void visit(NameExpr x, StyleContext ctx) { /* 记录用到 */ }
    @Override public void visit(ClassOrInterfaceType t, StyleContext ctx) { /* 记录用到 */ }
    @Override public void visit(AnnotationExpr a, StyleContext ctx) { /* 记录用到 */ }
    @Override public void visit(MarkerAnnotationExpr a, StyleContext ctx) { /* ... */ }
    @Override public void visit(NormalAnnotationExpr a, StyleContext ctx) { /* ... */ }
    @Override public void visit(SingleMemberAnnotationExpr a, StyleContext ctx) { /* ... */ }
}
```

每个 checker 看到的是**同一个** `StyleContext`，但 checker 之间不共享可变状态。

## 8. 与 `plumb-rules` 的映射

`AstChecker` 本身是 AST visitor，不直接依赖 `RuleRegistry`。但 `Finding` 模型要求
`rule` 字段。约定：

- 每个 `*Check` 类持有一个 `private static final RuleDef DEFAULT_RULE`
- `ctx.report(line, col, DEFAULT_RULE, message, severity, dataflowHead)` 直接用它
- `RuleRegistry.registerFromCheckers(...)`（`plumb-rules/.../RuleRegistry.java:268`）
  在扫描启动时把 checker 的 `rule()` 反射进 registry，供 `rules.enabled` /
  `rules.disabled` 按 id / 短名 / alias 匹配

没有 `RuleRegistry` 常量的 checker 也能工作 —— 它用自己的 `DEFAULT_RULE`。
但只有**登记进 `CheckerCategories`** 的 checker 能被 `byCategory(...)` 选中
（见 §6）。

## 9. 与 Checkstyle / PMD 的等价表

每条 Style 规则的 `RuleDef` 携带一个 `aliases` 列表（构造 `RuleDef` 的最后一个参数），
列出等价的外部规则 ID。**这些 alias 主要是本仓库自己的历史 id**（如
`ast-error-catch-block-strict`、`empty-catch-block`），供 `rules.enabled` /
`rules.disabled` 用多种写法匹配。

外部工具（PMD / SpotBugs / SonarQube / Checkstyle / Coverity）的等价 ID **不是**
从 alias 读的，而是由 `scripts/gen_rules_doc.py` 从 checker 的 Javadoc 里
启发式解析，生成到 [`../plumb-style-rules.md`](../plumb-style-rules.md) 的
「外部等价规则」小节。下游消费方（IDE 插件、SARIF 转换器）要展示
"see also" 时读那个生成文档，不要读 `RuleDef.aliases()`。

## 10. 启用 / 禁用风格规则

`plumb-style` 一共 595 个 checker，绝大多数默认 **enabled = true**。细粒度控制：

- **`checks.style: false`**：关掉全部风格规则（开关在 `ScanConfig.runStyle`）。
- **`rules.disabled`** / **`rules.enabled`**：按规则 ID、短名或 alias 单独控制（见
  [`../usage/CLI.md`](../usage/CLI.md) §4 和
  [`../rules/RULE-STYLE-OP.md`](../rules/RULE-STYLE-OP.md) §2）。
- **修改源代码**：如果只针对自己项目不想跑某条，**目前没有"项目级 disable file"
  机制** —— 只能走 YAML `rules.disabled`，或把 checker 的 `DEFAULT_RULE` 的
  `enabled` 参数改成 `false`（需要 rebuild）。

## 11. 测试约定

每条规则至少有 1 个**正面测试**（应该报）和 1 个**负面测试**（不应该报）。正面测试用 AssertJ 断言 finding 数 ≥ 1，负面测试断言 == 0。

```java
@Test
void emptyCatchFires() {
    String src = "class T { void m() { try { } catch (Exception e) {} } }";
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).rule().ruleId().name()).isEqualTo("STYLE_EMPTY_CATCH");
}
```
