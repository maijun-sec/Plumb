# R298-R299 Stub Re-implementation + New Rule Coverage (2026-08-03)

## TL;DR

继续 R295 整改方向：把 R295 留下的 19 个 weak (0-1 AST op) checker 一条一条按原工具文档重写，并新增 5 个常用重要 rule。所有 checker 都按 R295 约束：
- 规则名/RuleId/description 完全无 tool info
- `name()` 返回 kebab-case
- 每条带 `RuleParameter[]` (parameter 支持, 跟原工具 property 名字一致)
- 一条对应 fire + no-fire test

**结果**:
- 7 个 weak checker 改成真实现 (R298 a-g)
- 5 个新 checker (R299 a-e)
- 1 个 weak checker 重写 (R299g EmptyBlock)
- 1 个 weak checker 重写 (R299h PackageAnnotation)
- 14/14 R298-R299h checkers 全注册到 StyleCheckRegistry
- **+46 tests, 0 regression, 9 modules 全 0/0/0**

## R298 弱 checker 重写 (7 个)

| # | Checker | 原工具 | 实现要点 | Test |
|---|---|---|---|---|
| a | `IteratorIdiomaticCheck` | SonarQube S3958 / PMD UseForEach | `for (int i=0; i<list.size(); i++)` + body 只用 `list.get(i)` → for-each | 5 tests |
| b | `AnonInnerLengthCheck` | Checkstyle | `ObjectCreationExpr.getAnonymousClassBody()` 行数 > max | 3 tests |
| c | `AbstractClassCouplingCheck` | Checkstyle | 类里 distinct `new T()` 类型数 > max | 3 tests |
| d | `ImportControlCheck` | Checkstyle/PMD | import 顺序 java/javax/other, 同组 alphabetical | 3 tests |
| e | `OverloadMethodsDeclarationOrderCheck` | Checkstyle | 同名方法必须相邻, 中间不能有别的 method | 2 tests |
| f | `MissingDeprecatedCheck` | SpotBugs | `@Deprecated` 类的非 private 方法也要 `@Deprecated` | 4 tests |
| g | `DeclarationOrderCheck` | Checkstyle | 成员顺序 static → instance → ctor → method | 3 tests |

## R299 新规则 + 重写 (6 个)

| # | Checker | 原工具 | 用途 | Test |
|---|---|---|---|---|
| a | `AvoidStarImportCheck` | Checkstyle | `import x.*` 应该用 explicit | 3 tests |
| b | `UnusedImportsCheck` | Checkstyle | import 后没用到 | 3 tests |
| c | `RedundantImportCheck` | Checkstyle/PMD | 同样 FQN 重复 import | 3 tests |
| d | `SystemOutPrintlnCheck` | PMD/Sonar S106 | 用 logger 代替 `System.out.println` | 4 tests |
| e | `PrintStackTraceCheck` | PMD/Sonar S1148 | 用 logger 代替 `e.printStackTrace()` | 3 tests |
| g | `EmptyBlockCheck` (重写) | Checkstyle | `{}` 空 block (含 parameter `allowEmptyIf/Catch/Loop`) | 4 tests |
| h | `PackageAnnotationCheck` | Checkstyle | `package-info.java` 应该有 package annotation | 3 tests |

## 关键设计

### IteratorIdiomaticCheck (R298a) — 最复杂的一个

检测模式:
```java
for (int i = 0; i < list.size(); i++) {  // 或 arr.length
    ... list.get(i) ...  // body 只用 list.get(i) 或 arr[i]
}
```

实现细节:
- `for` init 必须是 `int i = 0` (其他初值跳过 — for-each 行为不同)
- compare 必须是 `i < x.size()` 或 `i < arr.length`
- update 必须是 `i++` 或 `++i`
- body 必须至少有一次 `x.get(i)` / `arr[i]` 调用
- body 不能有别的 `i` 使用 (会改变 for-each 语义)

Conservative: 误报比漏报严重 (for-each 改语义 = 编译可能过但行为变)

### SystemOutPrintlnCheck (R299d) — Parameter 设计

3 个 parameter:
- `allowInMain` (default true) — main 跳过
- `allowInTest` (default true) — `test*` 方法跳过
- `includeErr` (default false) — `System.err` 也检查

Exemption 通过 AST parent walk (不是简单的 check method name) — 找到 enclosing `MethodDeclaration` 再决定。

### EmptyBlockCheck (R299g) — 跟 R168 test 协调

R168 test 期望空 `if {}` 触发. 旧 EmptyBlockCheck (R168 stub) 跟 R295 删的 magic-name 不一样, R168 是 real implementation (R168 = "PMD EmptyBlock 真实重写"). 当时 default `allowEmptyIf=false` 跟 R168 行为一致。

R270 test 之前用 `class N { void m() { {} } }` (method body 内的空 block) — 这在新的 R299g 里被刻意 skip (method body 是别的 rule). 我改成 `if (true) {}` 测试真实场景。

### PackageAnnotationCheck (R299h) — 简单 but 准确

只对 `package-info.java` fire (无 top-level type). 其他 .java 跳过。

## 数字 (post-R298-R299)

| 指标 | R297 | R298-R299 | Δ |
|---|---:|---:|---:|
| plumb-style tests | 802 | 848 | +46 |
| plumb-style checkers (registered) | 498 | 499 | +1 (R299d/R299e 旧 duplicate, 实际有效 +5 - 4 weak stub 改) |
| StyleCheckRegistry 注册 | 488 | 499 | +11 |
| weak checkers | 19 | 11 | -8 |
| 9 modules tests | 全 0/0/0 | 全 0/0/0 | 0 回归 |

weak checkers 从 19 → 11 (修 7 + 1 + 1 = 9; 加 R299g 重写 EmptyBlock 还差 1 - 实际是 -8 because R299g/R299h 都不在原 19 weak list 里).

remaining 11 weak (大部分需 type resolution):
- DataflowAnomalyAnalysis (需要 full DF analysis)
- JavaNCSS (需要 line counting)
- LocalHomeNamingConvention (EJB naming, 罕见)
- StyleCheck (interface, 不是真 checker)
- SuppressWarningsCheck (需要 type analysis)
- ClassMembersShouldBePrivate (需要跨类 usage analysis)
- LineLength/NewlineAtEndOfFile/OuterTypeNumber/PackageDeclaration/TabCharacter (find_weak 误判, 实际有真实现)

## 关键 pitfall (R298-R299 新增)

1. `ImportDeclaration.isAsterisk()` 不是 name.endsWith(".*") — `getNameAsString()` 不含 `*`
2. `getAnonymousClassBody()` 返回 `Optional<NodeList<BodyDeclaration<?>>>` 不是 `Optional<BlockStmt>` — begin/end 在 outer `ObjectCreationExpr` 上
3. `SwitchEntry.getLabels()` 返回 `NodeList<Expression>` 不是 `String` — 不是 `getLabel()`
4. `ThrowStmt.getExpression()` 返回 `Expression` (不是 `Optional<Expression>`) — 3.x
5. `ForStmt.getBody()` 返回 `Statement` (不是 `Optional<Statement>`) — 3.x
6. `ForStmt.getInitialization()` 是 `List<Expression>`, 单个 `VariableDeclarationExpr` 要 instanceof 检查
7. `n.findAll(NameExpr.class)` 在 child `NameExpr` 上不递归到 grand-children — 需要手动 walk
8. `BinaryExpr.Operator.LESS` 是 `<`, `LESS_EQUALS` 是 `<=`
9. `UnaryExpr.Operator.POSTFIX_INCREMENT` 是 `i++`, `PREFIX_INCREMENT` 是 `++i`
10. `R168/R270/R276` 等老 test 用 `name="BLOCK"` 或 magic-name 测试 — 跟新 rule 名字不匹配时要改 test (用真实现测试样例, 不再 stub)
11. `AstChecker` interface 加新 visit type 时, `AstDispatcher` 也要同步加 (否则 dispatcher 不会调, 静默 skip)

## 备份

- 35 files in `D:\work\tmp\r298_r299_done\`:
  - 14 R298-R299h checker src: IteratorIdiomaticCheck, AnonInnerLengthCheck, AbstractClassCouplingCheck, ImportControlCheck, OverloadMethodsDeclarationOrderCheck, MissingDeprecatedCheck, DeclarationOrderCheck, AvoidStarImportCheck, UnusedImportsCheck, RedundantImportCheck, SystemOutPrintlnCheck, PrintStackTraceCheck, EmptyBlockCheck (R168 重写), PackageAnnotationCheck (R283 重写)
  - 2 core: AstChecker (新 visit), AstDispatcher (新 dispatch)
  - 15 R298-R299h test: R298a-g (7) + R299a-e (5) + R299g + R299h (2) + R299h = 15
  - 4 老 test 改: R168PmdEmptyBlockTest, R270SonarQubeGapFillingTest, R276CheckstyleBatch1Test, StyleCheckTest
  - 1 doc: R298-R299-CHECKER-REEIMPLEMENT-NEW.md

## R298-R299 关键 decision

- 决定保留 `SystemOutCheck` (已有) + 加新 `SystemOutPrintlnCheck` (有 parameters). 旧的是 default fire 模式, 新的是 configurable. 两个都注册 (不同 rule ID)
- 决定保留 `AvoidPrintStackTraceCheck` (已有, 只 catch param) + 加新 `PrintStackTraceCheck` (任何 e.printStackTrace()). 两个都注册, 互补

## R300+ 接下来

- R300 备份 + memory update + final retrospective
- R301+ 还可以加的 rule: UnnecessaryParenthesesCheck, EmptyStatement (已有?), SuppressWarnings (难, 需 type resolution), JavaNCSS (line count 简单)
- 真 95% per tool 目标已退后 (R295 后 tool info 删掉, rule 注册不能批量生成 magic-name, 只能一条一条加 — 不切实际, 跳过)
- 更现实目标: 每个 tool 关键 50+ rules 全, 覆盖核心 pattern, 每个 rule 都带 parameter, 跟原工具 property names 一致
