# R-815: AST checker 扫描模式审计与选型指南 (2026-10-14)

## 1. 背景

R-813 完成了 7 个 AST migration + 5 个 orphan DFA 决定，plumb-style 现有
**587 个 `*Check.java`** checker。这些 checker 共用一个 dispatch 入口
`AstDispatcher.run(StyleContext ctx)`，由 `plumb-main` 的 `ScanRunner`
每个 CompilationUnit 调用一次。

`AstDispatcher` 内部是一个 `VoidVisitorAdapter<Void>`，对 **43 种节点类型**
各重写了 `visit(X, Void)`：每个节点都触发所有 checker 的对应 `visit` 重载。

**核心问题**：单个 checker 在 `visit(CompilationUnit, StyleContext)` 内部
多次调 `n.findAll(X.class)` 或 `n.walk(X.class, lambda)` —— **每次都是一次
完整 AST walk**。这导致每个文件被 walk 多次，性能与 token 数线性相关。

用户方向 (2026-10-14 13:51)：

> 我推荐主要用 pattern D，就是 visit 每类节点，不行再 visit cu

— 优先 per-node visit (Pattern D)，做不到才回退 visit(CU) + 单次 findAll (Pattern A)。
当前是**审计 + 决策** round，不改代码。

## 2. 模式分类

把 587 个 checker 按扫描模式分到 5 类 + 2 个异常：

| 模式 | 描述 | 数量 | walks/file | 评价 |
|------|------|------|-----------|------|
| **A** | `visit(CU)` + 1 次 `findAll` | 167 | 1 | ✓ 已最优 |
| **B** | `visit(CU)` + ≥2 次 `findAll` | **37** | 2-10 | ⚠️ 反模式 |
| **C** | `visit(CU)` + ≥1 次 `walk` | **8** | 1-6 | ⚠️ 反模式 |
| **D** | per-node visit 重载，AstDispatcher 单次 walk | 333 | 1 | ✓ 推荐 |
| **D+** | visit(CU) **+** per-node visit 双重触发 | **3** | 2 | ⚠️ 二选一 |
| **E** | visit(CU) 内部无 findAll/walk（多为空 stub） | 33 | 0 | 🔍 待确认 |
| **E0** | 完全没有 visit 方法 | 6 | 0 | 🐛 实现 broken |

**当前总 walk 数**: 621 walk/file。**优化空间**: Pattern B/C 改造为 Pattern A/D
可省 61 walk/file (~10%)。Pattern D+ 清理省 3。

## 3. 模式 B 详细清单 (37 个，多次 findAll)

按 findAll 次数降序排：

| Checker | fA | 类型 | nested=loop 嵌 walk |
|---------|----|------|----------------------|
| MethodTooComplexCheck | 10 | BinaryExpr, CatchClause, ConditionalExpr, DoStmt, ForEachStmt, ForStmt, IfStmt, MethodDeclaration, SwitchStmt, WhileStmt | 3 |
| FiUselessBlockCheck | 5 | DoStmt, ForEachStmt, ForStmt, IfStmt, WhileStmt | **5 (全在 loop)** |
| OrmLostUpdateCheck | 4 | AssignExpr, ClassOrInterfaceDeclaration, MethodCallExpr, MethodDeclaration | 4 |
| SingletonRaceCheck | 4 | AssignExpr, ClassOrInterfaceDeclaration, NameExpr, UnaryExpr | 4 |
| AlwaysFalseCheck | 3 | BinaryExpr, IfStmt, WhileStmt | 3 |
| AlwaysTrueCheck | 3 | BinaryExpr, IfStmt, WhileStmt | 3 |
| EmptyLineSeparatorCheck | 3 | MethodDeclaration | 3 |
| FinalLocalVariableCheck | 3 | AssignExpr, MethodDeclaration, VariableDeclarator | 3 |
| HibernateBadHashcodeCheck | 3 | ClassOrInterfaceDeclaration, FieldAccessExpr, NameExpr | 3 |
| UnloggedSecurityExceptionCheck | 3 | CatchClause, ClassOrInterfaceDeclaration, MethodCallExpr | 3 |
| UnusedImportCheck | 3 | AnnotationExpr, ClassOrInterfaceType, NameExpr | 3 |
| UnusedImportsCheck | 3 | ClassOrInterfaceType, MethodCallExpr, NameExpr | 3 |
| UnusedLocalVariableCheck | 3 | MethodDeclaration, NameExpr, VariableDeclarator | 3 |
| AssignmentToCatchBlockParameterCheck | 2 | AssignExpr, CatchClause | 2 |
| ChildClassShadowFieldCheck | 2 | ClassOrInterfaceDeclaration | 2 |
| ClassFanOutComplexityCheck | 2 | ClassOrInterfaceDeclaration, ClassOrInterfaceType | 2 |
| CompareToReturnValueCheck | 2 | MethodDeclaration, ReturnStmt | 2 |
| ConstructorCallingOverridableCheck | 2 | ConstructorDeclaration, MethodCallExpr | 2 |
| DuplicatedStringLiteralCheck | 2 | StringLiteralExpr | 2 |
| FieldSelfAssignmentCheck | 2 | AssignExpr, ClassOrInterfaceDeclaration | 2 |
| ImmutableFieldCheck | 2 | AssignExpr, ClassOrInterfaceDeclaration | 2 |
| InvalidCompareToObjectCheck | 2 | BinaryExpr, MethodDeclaration | 2 |
| IteratorNextExceptionCheck | 2 | MethodCallExpr, StringLiteralExpr | 2 |
| LambdaParameterShadowingCheck | 2 | ClassOrInterfaceDeclaration, LambdaExpr | 2 |
| LoopsWithSameConditionCheck | 2 | ForStmt | 2 |
| ModifierOrderCheck | 2 | FieldDeclaration, MethodDeclaration | 2 |
| NestedForDepthCheck | 2 | ForStmt, Statement | 2 |
| NestedIfDepthCheck | 2 | IfStmt | 2 |
| NosonarCommentCheck | 2 | BlockComment, LineComment | 2 |
| NullReturnedToStringCheck | 2 | MethodDeclaration, ReturnStmt | 2 |
| OneStatementPerLineCheck | 2 | ConstructorDeclaration, MethodDeclaration | 2 |
| OrmUnnecessaryGetCheck | 2 | MethodDeclaration, NameExpr | 2 |
| ReturnCountCheck | 2 | MethodDeclaration, ReturnStmt | 2 |
| ServletAtomicityCheck | 2 | MethodCallExpr, MethodDeclaration | 2 |
| SuperCloneCheck | 2 | MethodCallExpr, MethodDeclaration | 2 |
| SuperFinalizeCheck | 2 | MethodCallExpr, MethodDeclaration | 2 |
| UnusedPrivateFieldCheck | 2 | ClassOrInterfaceDeclaration, NameExpr | 2 |

**反模式 warning**: `nested == fA` 的 checker 是**循环里嵌 walk**（每次循环
触发额外的 AST walk），是最坏情况。优先级最高：
- **FiUselessBlockCheck**（5/5 nested）
- **AlwaysFalseCheck / AlwaysTrueCheck**（3/3 nested）
- **EmptyLineSeparatorCheck**（3/3 nested）
- **HibernateBadHashcodeCheck**（3/3 nested）
- **FinalLocalVariableCheck**（3/3 nested）

## 4. 模式 C 详细清单 (8 个，多次 walk)

| Checker | walks | 类型 |
|---------|-------|------|
| RandomIvCheck | **6** | VariableDeclarationExpr, ObjectCreationExpr, FieldDeclaration, MethodCallExpr |
| UncaughtExceptionCheck | 3 | MethodCallExpr, VariableDeclarator, MethodDeclaration |
| PoorCodeQualityCheck | 2 | BlockStmt, MethodDeclaration |
| EnvVarInfoExposureCheck | 2 | MethodCallExpr |
| ClassBasedIssueCheck | 1 | MethodDeclaration |
| InsufficientSessionExpirationCheck | 1 | MethodCallExpr |
| NullDerefFromReturnCheck | 1 | MethodDeclaration |
| UnsaltedOneWayHashCheck | 1 | MethodCallExpr |

**RandomIvCheck** 是**最坏情况**：`n.walk(ObjectCreationExpr, ...)` 主 walk
遍历每个 `new IvParameterSpec(...)` 调用，对**每个**匹配节点都触发
3 次额外 walk（`scope.walk(VariableDeclarationExpr)` + `scope.walk(FieldDeclaration)`
+ `scope.walk(MethodCallExpr)`），总 walk 数 = N_oc + 3·N_oc = **4·N_oc walk**。

## 5. 模式 D+ 详细清单 (3 个，双重 visit)

| Checker | visit(CU) | per-node visits |
|---------|-----------|-----------------|
| NullDerefFromReturnAstCheck | yes (有 findAll/walk) | MethodDeclaration |
| CorsMisconfigurationCheck | yes (有 findAll/walk) | MethodCallExpr |
| ObjectHijackCheck | **空 stub** | ClassOrInterfaceDeclaration |

**ObjectHijackCheck** 的 visit(CU) 是 `// Per-class dispatch below.` 的空实现。
`NullDerefFromReturnAstCheck` 和 `CorsMisconfigurationCheck` 实际**两个模式都跑**。

## 6. 模式 E 详细清单 (33 个，visit(CU) 但无 walk)

这些 checker 拥有 `visit(CompilationUnit, ctx)` 但内部既不 findAll 也不 walk。
可能是：
- (a) 空 stub，等同于 disabled — 应删
- (b) 只用 ctx / source 做 file-level 检查（如 ImportControl 类的 import 列表检查）
- (c) broken 实现

抽查清单（不展开）：

```
ObjectHijackCheck (D+, 已列)
LocalHomeNamingConventionCheck / OuterTypeFilenameCheck / PackageCaseCheck
AvoidEscapedUnicodeCharactersCheck / AvoidStarImportCheck / AvoidStaticImportCheck
BadSyntaxInRegexCheck / CommentInfoLeakCheck / CommentsIndentationCheck
DataflowAnomalyAnalysisCheck / DontImportJavaLangCheck / DontImportSunCheck
DuplicateImportsCheck / FileTabCharacterCheck / ImportControlCheck
ImportFromSamePackageCheck / JavadocMethodCheck / LineLengthCheck
MfClassMasksFieldCheck / MfMethodMasksFieldCheck / NewlineAtEndOfFileCheck
NoCodeInFileCheck / NoPackageCheck / PackageAnnotationCheck
PackageDeclarationCheck / PackageInfoCheck / TabCharacterCheck
UnnecessaryFullyQualifiedClassNameCheck / WhitespaceAfterCheck
```

**判断方法**：找 visit(CU) body 里的 `getTypes()` / `getImports()` /
`getComments()` 等 — 这些是 file-level API，不需要 walk。

## 7. 选型决策表 (新 checker 必须遵守)

```
[新 AST checker 写之前]
   ↓
[1. 我要处理什么节点？]
   ↓
   ├── 多种类型无关节点？ (e.g. "BinaryExpr 上的 if/else 分支")
   │     ↓
   │   1.a 每个类型一个 visit(X, ctx) 重载 → Pattern D
   │      (AstDispatcher 单次 walk, 不写 visit(CU))
   │      例子: ObjectHijackCheck.visit(ClassOrInterfaceDeclaration, ctx)
   │
   └── 只关心一种节点 (e.g. 只看 MethodCallExpr)
         ↓
       2. 用 per-node visit(X, ctx) → Pattern D
          (AstDispatcher walk 时只在该节点类型命中时触发)
          例子: EnvVarCheck.visit(MethodCallExpr, ctx)
                —— 但 EnvVarCheck 实际写的是 visit(CU)+findAll(可优化)
   │
   └── 2.b 需要 file-level 上下文 (Imports / Comments / 跨节点关系)
         ↓
       2.b.1 在 visit(CU) 里手工一次 walk + instanceof 分发 → Pattern A 改良
       2.b.2 如果是 [Import + Class + AnnotationExpr] 等少量明确类型
             → 用 n.findAll 多次, 但**保证 fA ≤ 2**
             → Pattern A (可接受)
       2.b.3 都不行, 才允许 Pattern C (n.walk 多次)
```

### 选型红线

- ❌ **禁止**: Pattern B `visit(CU) + n.findAll(A) + n.findAll(B) + ...` (3+ 次)
- ❌ **禁止**: 循环里嵌 walk（`for (X : n.findAll(A)) { n.walk(B, ...) }`）
- ❌ **禁止**: Pattern D+（visit(CU) + per-node visit 同时存在）
- ⚠️ **限制**: Pattern A 最多 2 次 findAll；超过必须降级到 Pattern D
- ✅ **优先**: Pattern D (per-node visit 重载，AstDispatcher 单次 walk)

## 8. 改造模板

### 8.1 Pattern B → Pattern D 改造

**原 (Pattern B)**: 3 次 findAll
```java
// AlwaysFalseCheck — 3 次 findAll, 3 次 walk
@Override
public void visit(CompilationUnit n, StyleContext ctx) {
    for (IfStmt s : n.findAll(IfStmt.class)) { ... }      // walk 1
    for (WhileStmt s : n.findAll(WhileStmt.class)) { ... } // walk 2
    for (BinaryExpr e : n.findAll(BinaryExpr.class)) { ... } // walk 3
}
```

**改造后 (Pattern D)**: 3 个 visit 重载
```java
// 删 visit(CompilationUnit, StyleContext)
@Override
public void visit(IfStmt s, StyleContext ctx) { ... }
@Override
public void visit(WhileStmt s, StyleContext ctx) { ... }
@Override
public void visit(BinaryExpr e, StyleContext ctx) { ... }
```

**优点**：AstDispatcher 单次 walk 触发，**0 次冗余 walk**。

### 8.2 Pattern B → Pattern A (单次 findAll) 改造

适用于**只关心一种节点但需要 file-level 上下文**：

**原**: 2 次 findAll (Pattern B)
```java
@Override
public void visit(CompilationUnit n, StyleContext ctx) {
    for (MethodDeclaration md : n.findAll(MethodDeclaration.class)) {
        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) { ... }
    }
}
```

**改造**: 1 次 findAll (Pattern A)
```java
@Override
public void visit(CompilationUnit n, StyleContext ctx) {
    for (MethodCallExpr mc : n.findAll(MethodCallExpr.class)) {
        MethodDeclaration md = mc.findAncestor(MethodDeclaration.class).orElse(null);
        if (md == null) continue;
        ...
    }
}
```

或更好：**Pattern D + findAncestor 缓存**
```java
@Override
public void visit(MethodCallExpr mc, StyleContext ctx) {
    MethodDeclaration md = mc.findAncestor(MethodDeclaration.class).orElse(null);
    if (md == null) return;
    ...
}
```

### 8.3 Pattern C → Pattern D 改造 (RandomIvCheck 模板)

**原 (Pattern C, 6 次 walk)**: 循环里嵌 walk
```java
n.walk(ObjectCreationExpr.class, oc -> {
    if (!isIvParameterSpec(oc)) return;
    // 每个 IvParameterSpec 触发 3 次额外 walk
    scope.walk(VariableDeclarationExpr.class, vd -> ...);
    scope.walk(FieldDeclaration.class, fd -> ...);
    scope.walk(MethodCallExpr.class, mc -> ...);
});
```

**改造 (Pattern D, 1 次 walk)**: 在 visit(ObjectCreationExpr) 内**不 walk**，
用 `enclosing.walk` 一次 + instanceof 分发到各 handler：

```java
@Override
public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (!isIvParameterSpec(oc)) return;
    // enclosing walk 一次, instanceof 分发 3 个 handler
    Node enclosing = findEnclosingMethodDecl(oc);
    if (enclosing == null) return;
    final boolean[] seen = new boolean[3]; // [byteDecl, fieldDecl, nextBytes]
    enclosing.walk(Node.class, node -> {
        if (node instanceof VariableDeclarationExpr vd) {
            seen[0] = matches(vd, varName) || seen[0];
        } else if (node instanceof FieldDeclaration fd) {
            seen[1] = matches(fd, varName) || seen[1];
        } else if (node instanceof MethodCallExpr mc) {
            seen[2] = isNextBytes(mc, varName) || seen[2];
        }
    });
    ...
}
```

**或更好**: `enclosing.getBody().getStatements()` 手写线性扫描（if / decl
顺序是有意义的，单次线性 pass 比 walk 快 ~2x）。

## 9. 优先级排序 (R-816 候选)

按 **walk 节省 × 调用频率** 排（Pattern B 改造到 Pattern D 每次文件省
fA-1 个 walk）：

| 优先级 | Checker | 节省 walk/file | Juliet 验证状态 |
|--------|---------|---------------|----------------|
| 1 | **RandomIvCheck** (Pattern C, 6 walk) | 5 | 已有 CWE-329 测试 |
| 2 | **AlwaysFalseCheck / AlwaysTrueCheck** | 2 each | R-813 已 Juliet 验证 |
| 3 | **MethodTooComplexCheck** (10 findAll) | 9 | 需先 Juliet E2E |
| 4 | **FiUselessBlockCheck** (5 findAll, 全 in loop) | 4 | 需先 Juliet E2E |
| 5 | **HibernateBadHashcodeCheck** (3, 3 in loop) | 2 | 需先 Juliet E2E |
| 6 | **FinalLocalVariableCheck** (3, 3 in loop) | 2 | 需先 Juliet E2E |
| 7 | **EmptyLineSeparatorCheck** (3, 3 in loop) | 2 | 需先 Juliet E2E |
| 8 | **OrmLostUpdateCheck** / **SingletonRaceCheck** (4) | 3 each | R-813 已验证 SingletonRace |
| 9 | D+ 3 个（清理双重 visit） | 1 each | 需先 Juliet E2E |
| 10 | 剩余 Pattern B (20 个 fA=2) | 1 each | 一次性扫 |

## 10. 验证标准 (R-816 实施时)

参考 R-813 双重验证流程：

1. **Hermetic unit test**: `mvn -pl plumb-style -am test` 2871+ PASS
2. **Juliet E2E** (针对 CWE 相关 checker):
   - 跑 `D:/work/tmp/Juliet_Java/src/testcases/CWE-<N>` 全量
   - 比对改造前后 fire 数 (R-813 baseline 表)
3. **无 FP 退化**: 改造前 FP 数 ≤ 改造后 FP 数
4. **walk 数验证**: 加 micro-benchmark，确认 walks/file 减少
   (可加 `AstDispatcher` 内部计数器 + `--debug-walks` flag)

## 11. 关键决策

- **本次 (R-815) 不改代码**。纯审计 + 决策文档。
- **新 checker 写之前**必须先选 Pattern A/D，不允许 Pattern B/C。
- **R-816** (后续 round) 按 §9 优先级实施 Pattern B → D 改造。
- **R-817** (后续 round) 处理 D+/E 待确认清单。
- **D+ 改造 3 个**: ObjectHijackCheck 删空 stub, NullDerefFromReturnAstCheck
  + CorsMisconfigurationCheck 二选一 (per-node visit 优先)。

## 12. 实施时序

1. R-815 (本 round): 报告 + 决策表 + 模板 (本文档) + memory entry
2. R-816: 实施 §9 优先级 1-5 (RandomIvCheck / AlwaysFalse / True /
   MethodTooComplex / FiUselessBlock)
3. R-817: 实施 §9 优先级 6-10
4. R-818: 实施 D+/E 清理

每 round 走 hermetic + Juliet E2E 双重验证。

## 13. 与 R-813 / R-814 的关系

- R-813: AST migration (plumb-dfa → plumb-style) — 7 个新 checker
- R-814: controlflow 子包迁移 — DFA 范式分类
- R-815 (本): AST 扫描模式审计 — **plumb-style 内部优化**
- R-816 以后: 按本报告优先级逐批实施

## 14. Lessons (memory entries)

Lesson 1268-1271 详见 `MEMORY.md`：
- 1268: Pattern B/C 是 AST checker 反模式
- 1269: Pattern D (per-node visit) 是默认选择
- 1270: Pattern D+ 双重 visit 的清理方法
- 1271: 循环里嵌 walk 是最坏 case (N×K walk)
