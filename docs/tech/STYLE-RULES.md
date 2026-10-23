# 风格类规则（plumb-style）实现方案

本文档说明 plumb-style 模块中 500+ 风格类规则的整体架构、注册机制和实现模式。

## 1. 设计目标

| 目标 | 方案 |
| --- | --- |
| **完全独立于 TAC/DFA** | plumb-style 只依赖 `plumb-parser`（提供 CompilationUnit AST），不需要 `plumb-tac` |
| **单次遍历多个 checker** | 引入 `AstDispatcher`，一次访问 CompilationUnit，把每个节点派发给所有 checker |
| **可插拔 / 可裁剪** | 规则是普通的 `AstChecker` 子类，启动时 classpath 扫描自动注册 |
| **可按类目裁剪** | `StyleCheckRegistry.byCategory("naming", "design")` 只加载指定子目录 |
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
    String name();                        // 唯一名："empty-catch"
    void visit(CompilationUnit unit,      // 整个 CU
               StyleContext ctx);         // 检查器之间的共享上下文
}
```

`StyleContext` 提供：
- 当前 `RuleRegistry`（用于查 RuleDef）
- 累计的 `Finding` 列表
- 跨 checker 共享的状态（如"已看到的 imports"）

注意：checker 不能阻塞其它 checker 的运行。每个 checker **只读** `StyleContext`，写入自己负责的字段。

## 4. `AstDispatcher`（单次访问派发）

```java
public final class AstDispatcher {
    private final List<AstChecker> checkers = new ArrayList<>();
    public AstDispatcher register(AstChecker c);
    public void run(StyleContext ctx);
}
```

`run(ctx)` 调用每个 checker 的 `visit(cu, ctx)`。AST 本身没有被多次遍历 —— 每个 checker 内部自己用 `cu.findAll(...)` 拉取感兴趣的节点。

## 5. `StyleCheckRegistry`（动态发现 + 分类加载）

```java
public final class StyleCheckRegistry {
    public StyleCheckRegistry builtins();        // 加载所有可发现的 checker
    public StyleCheckRegistry load(String... globs);   // 按 simple class name glob
    public StyleCheckRegistry byCategory(String... categories);
}
```

`builtins()` 调用 `discoverAvailable()`：扫描 `org/plumb/style/` 下的所有 class，找到 `extends AstChecker` 的类（即 `*Check.java`，但不是 `AstChecker` 自身也不是 `Registry`）。

**优势**：新增 checker = 写一个 `*Check.java` + `implements AstChecker`，无需改任何注册表。

## 6. 规则分类

7 个子目录对应 7 大类（决定 `byCategory` 过滤的语义）：

| 目录 | 主题 | 数量级 | 典型规则 |
| --- | --- | ---: | --- |
| `style/` | 编码风格（注释、Javadoc、缩进、imports、控制流） | ~200 | `EmptyCatchCheck`, `LineLengthCheck` |
| `naming/` | 命名约定 | ~60 | `ClassNamingCheck`, `MethodNamingCheck` |
| `design/` | 设计度量 | ~40 | `CyclomaticComplexityCheck`, `ClassFanOutComplexityCheck` |
| `error/` | 错误处理 | ~80 | `EmptyCatchBlockCheck`, `IllegalCatchCheck` |
| `performance/` | 性能 | ~50 | `StringBuilderConcatenationCheck`, `UseAssertEqualsInsteadOfAssertTrueCheck` |
| `bug/` | 明确 bug 模式 | ~40 | `CovariantEqualsCheck`, `CompareToReturnValueCheck` |
| `security/` | 安全相关 | ~20 | `WeakUrlSanitizationCheck`, `HardcodedCredentialsCheck` |
| `concurrency/` | 并发 | ~5 | `SingletonRaceCheck`, `ServletAtomicityCheck` |
| `quality/` | ORM 等领域 | ~5 | `OrmLoadNullCheckCheck`, `RegexConfusionCheck` |
| `annotations/` | 注解 IR 工具 | ~5 | 不是真 checker；只暴露给 DFA 引擎 |

`byCategory("naming")` 等价于只加载 `org/plumb/style/naming/*Check.class`。

## 7. 实现模式：典型 Checker

### 7.1 简单模式匹配

```java
public final class EmptyCatchCheck implements AstChecker {
    @Override public String name() { return "empty-catch"; }

    @Override
    public void visit(CompilationUnit cu, StyleContext ctx) {
        cu.findAll(CatchClause.class).forEach(c -> {
            if (c.getBody().getStatements().isEmpty()) {
                ctx.report(new Finding(
                    ctx.file(), c.getBegin().map(p -> p.line).orElse(-1),
                    c.getBegin().map(p -> p.column).orElse(-1),
                    ctx.rules().byName(STYLE_EMPTY_CATCH).orElseThrow(),
                    "Empty catch block swallows exceptions",
                    Finding.Severity.LOW, null));
            }
        });
    }
}
```

### 7.2 命名类（基于 `AbstractNamingCheck`）

`AbstractNamingCheck` 把"取声明 + 校验名 + 报告"抽出来。子类只提供：

```java
public final class ClassNamingCheck extends AbstractNamingCheck {
    @Override protected String name() { return "class-naming"; }
    @Override protected Pattern pattern() { return Pattern.compile("^[A-Z][a-zA-Z0-9]*$"); }
    @Override protected void collectDeclarations(CompilationUnit cu, Consumer<Declaration> out) {
        cu.findAll(ClassDeclaration.class).forEach(d -> out.accept(d));
        cu.findAll(InterfaceDeclaration.class).forEach(d -> out.accept(d));
        // ...
    }
}
```

### 7.3 复杂度类（累积统计）

```java
public final class CyclomaticComplexityCheck implements AstChecker {
    @Override public String name() { return "cyclomatic-complexity"; }
    @Override
    public void visit(CompilationUnit cu, StyleContext ctx) {
        cu.findAll(MethodDeclaration.class).forEach(m -> {
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
                ctx.report(...);
            }
        });
    }
}
```

### 7.4 资源 / 上下文（共享 `StyleContext` 状态）

```java
public final class UnusedImportCheck implements AstChecker {
    @Override public String name() { return "unused-import"; }
    @Override
    public void visit(CompilationUnit cu, StyleContext ctx) {
        Set<String> used = new HashSet<>();
        cu.findAll(NameExpr.class).forEach(n -> used.add(n.getNameAsString()));
        cu.findAll(ImportDeclaration.class).forEach(imp -> {
            String name = imp.getNameAsString();
            if (!used.contains(name) && !nameEndsWithStar(used, name)) {
                ctx.report(...);
            }
        });
    }
}
```

`StyleContext` 在 `visit()` 之间保留（虽然 dispatcher 顺序访问所有 checker，每个 checker 看到的是同一个 ctx）。

## 8. 与 `plumb-rules` 的映射

`StyleCheck` 本身是 AST visitor，不依赖 `RuleRegistry`。但 Finding 模型要求 `rule` 字段。约定：

- 每个 `*Check` 类对应一个 `RuleRegistry.STYLE_*` / `NAMING_*` 常量
- `check.visit()` 构造 Finding 时通过 `ctx.rules().byName(STYLE_XXX).orElseThrow()` 拿 RuleDef

这种松耦合让 `*Check` 类的代码完全独立于 `plumb-rules`，但运行时仍然输出可识别的 rule_id。

## 9. 与 Checkstyle / PMD 的等价表

每条 Style 规则有 `aliases()` 字段，列出在 PMD / SpotBugs / SonarQube / Checkstyle / Coverity 中对应的规则 ID。下游消费方（IDE 插件、SARIF 转换器）可基于此展示"see also"。

例如 `STYLE_EMPTY_CATCH`：

```java
addAlias("bu-0201-style-empty-catch", List.of("PMD:EmptyCatchBlock", "SonarQube:java:S1163"));
```

`A3` 任务（见 `docs/A1-A3-REFACTOR-AND-DEDUP.md`）实现了别名表 + 跨工具覆盖报告。

## 10. 启用 / 禁用风格规则

`plumb-style` 一共 500+ 条，全部默认 **enabled = true**。细粒度控制：

- **`checks.style: false`**：关掉全部 500+ 风格规则（开关在 `ScanConfig.runStyle`）。
- **`rules.disabled`** / **`rules.enabled`**：按规则 ID 或 name 单独控制（见 `docs/usage/CLI.md` §4）。
- **修改源代码**：如果只针对自己项目不想跑某条，**目前没有"项目级 disable file"机制**——只能走 YAML `rules.disabled` 或在代码里把 `addAlias` 那行删掉 + `RuleRegistry.STYLE_XXX` 设为 `withEnabled(false)`。

未来 R-version 会引入 `.plumb-ignore` 文件（类似 `.gitignore`），按 path glob 屏蔽。

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
