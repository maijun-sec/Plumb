# 规则开发：AI prompt / CWE 标签 / 外部工具 ID / 加新规则

[English](../../rules/RULE-AUTHORING.md) | [中文](../README.md)

本文档讲怎么写一条规则：它带的 AI prompt、CWE 标签从哪来、与外部工具的等价 ID、
以及加一条新规则的步骤。规则主清单见 `RULES.md`；Style 规则操作（启用禁用 / 改参数）见 `RULE-STYLE-OP.md`。

## 1. AI augmentation 与每条规则的 prompt

每条 `DfaChecker` / `AstChecker` 自己持有 `getAiPrompt(Finding) -> String`：

- 返回 `null` = 该规则**不走** AI dedup
- 返回非 null = LLM 收到这条规则的 prompt + finding 详情 + dataflow trace + 源码窗口，输出 TP / FP / UNKNOWN

prompt 默认由 checker 提供。手写 override 见：
- 20 个 `DfaChecker` + `TaintedCheckerBase`（auto-generated from `TaintRule` 元数据）
- `plumb-style/src/main/java/org/plumb/style/security/HardcodedCredentialsCheck.java`
- `plumb-style/src/main/java/org/plumb/style/error/EmptyCatchBlockCheck.java`
- `plumb-style/src/main/java/org/plumb/style/style/BadShiftCheck.java`

用户的 `--rule-prompts-dir` 仍可 override（`<rule-name>.md` 文件覆盖对应 checker 的 inline `getAiPrompt` 返回值）。

**三级覆盖链**：
1. `TaintedCheckerBase` 自动从 `TaintRule` 元数据生成（ruleId / sourceKindName / sinkFamilyKey / cwe / severityLabel / domain）
2. DfaChecker / AstChecker 手写 inline `getAiPrompt`（少数高价值规则）
3. `--rule-prompts-dir` 用户 `.md` 覆盖（最优先）

详见 `docs/usage/CLI.md` §2.2。

## 2. CWE 标签从哪来

CWE 标签有两个独立来源，新增规则时要知道自己的规则属于哪一个：

| 规则类型 | CWE 来源 | 怎么加 |
| --- | --- | --- |
| AST 规则（`plumb-style`） | `RuleDef` 构造参数里的 CWE 列表 | 在 `new RuleId(...)` 之后的 `RuleDef` 里传 `List.of("CWE-xxx")` |
| DFA 规则（`plumb-dfa`） | `plumb-dfa/src/main/resources/cwe-mappings.yaml` | 在该 YAML 里加一条 `finding-key: CWE-xxx` 映射 |

DFA 映射的 key 是 **finding key**：`{CheckerName}`，或者一个 checker 对不同 sink family
发不同 CWE family 时用 `{CheckerName}_{SinkFamilyShortName}`。所以 YAML 长这样：

```yaml
mappings:
  MyChecker: CWE-XXX
  MyChecker_sub: CWE-YYY
```

AST 规则的 CWE 会随 finding 进入 NDJSON 的 `cwe` 字段；DFA 规则的 finding key 则通过
`cwe-mappings.yaml` 查表。**改 emit-id 字符串不影响 CWE**——两者不是同一份映射。

## 3. 与外部工具的等价 ID

有**两种**东西都被叫作「外部 ID」，而它们不是同一份映射。

**`RuleDef.aliases` 携带的是 Plumb 自己的别名**——同一条规则在 Plumb 里被接受的
其它写法。一条规则可以有一个规范 id 和若干兼容别名：

```java
new RuleId("ast-error-empty-catch-block", "EMPTY_CATCH_BLOCK"),
// ...
List.of("ast-error-catch-block-strict", "empty-catch-block");
```

这些 id 用户可以写进 `rules.enabled` 或 allowlist。它们**不是**
PMD / SpotBugs / Checkstyle / SonarQube 的 id。

**与外部工具的等价关系是另一份独立的映射**，写在 `docs/plumb-style-rules.md` 每条规则的
「外部等价规则」/ "external equivalents" 条目下。这份映射由`scripts/gen_rules_doc.py` **推导**出来，而不是在源码里声明的：生成器读 checker 的
javadoc，找显式提及（"Checkstyle's `Foo`"、"SonarQube S1234"），再回退到一张命名模式
对照表。它覆盖 **PMD、SpotBugs、Checkstyle、SonarQube**，仅此四个。
一条规则如果没有检测到等价规则，就会明说没有——这是常态，不是缺口。

完整表由 `scripts/gen_rules_doc.py` 生成在 `docs/plumb-style-rules.md`。

**全方位对比**（实现机制 / CWE 覆盖 / 误报率 / 性能 / AI 集成 / Plumb 定位）见 [`RULE-TOOL-COMPARISON.md`](RULE-TOOL-COMPARISON.md)。

## 4. 添加新规则的步骤

**先选一条路。** AST 规则有两种加法：

| | 写 Java checker | 写 Kotlin DSL 规则 |
| --- | --- | --- |
| 适合 | 进产品默认规则集、复杂逻辑、需要 AI dedup prompt | 项目自带规则、快速加一条形态检查、跟着上游规则走 |
| 落地 | `plumb-style` 模块 + `CheckerCategories` 注册 | 一个规则文件（XML / YAML） |
| 分发 | 跟代码一起发布 | 跟项目配置一起走 |
| 文档 | 本文 §13.1 | [`../tech/KOTLIN-DSL.md`](../tech/KOTLIN-DSL.md) |

数据流 / 污点规则**只有一条路**：YAML + `plumb-dfa` 的 checker（本文 §13.2 / §13.3）。
DSL 引擎只做 AST 规则。

### 13.0 Pattern 选型决策（Style 规则前置步骤）

**写新 Style checker 之前先选 Pattern**。`AstDispatcher` 的分发表是通过反射
checker 声明的 `visit(NodeType, StyleContext)` 方法建出来的，所以单个 checker 内部多次
`findAll` / `walk` 会导致每文件被 walk 多次。Pattern 选型决策表：

```
[新 AST checker 写之前]
   ↓
[1. 我要处理什么节点？]
   ↓
   ├── 多种类型无关节点？(e.g. "BinaryExpr 上的 if/else 分支")
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
      │
      └── 2.b 需要 file-level 上下文 (Imports / Comments / 跨节点关系)
            ↓
         2.b.1 在 visit(CU) 里手工一次 walk + instanceof 分发 → Pattern A 改良
         2.b.2 如果是 [Import + Class + AnnotationExpr] 等少量明确类型
               → 用 n.findAll 多次, 但**保证 fA ≤ 2**
               → Pattern A (可接受)
         2.b.3 都不行, 才允许 Pattern C (n.walk 多次)
```

**选型红线**（强制约束）：

- ❌ **禁止** Pattern B（`visit(CU) + n.findAll(A) + n.findAll(B) + ...` 3+ 次）
- ❌ **禁止** 循环里嵌 walk（`for (X : n.findAll(A)) { n.walk(B, ...) }`）
- ❌ **禁止** Pattern D+（visit(CU) + per-node visit 同时存在）
- ⚠️ **限制** Pattern A 最多 2 次 findAll；超过必须降级到 Pattern D
- ✅ **优先** Pattern D（per-node visit 重载，AstDispatcher 单次 walk）

**当前 plumb-style checker 的 walk 模式评价**：

| Pattern | 评价 |
| --- | --- |
| A（单次 findAll） | ✓ 已最优 |
| B（多次 findAll） | ⚠️ 反模式，改成 A 或 D |
| C（多次 walk） | ⚠️ 反模式，改成 A 或 D |
| D（per-node visit 重载） | ✓ 推荐，AstDispatcher 单次 walk |
| D+（per-node + CU 同时存在） | ⚠️ 二选一 |
| E / E0（不属上述分类） | 需单独确认 |

**改造方向**：B / C 是主要优化目标，改为 A 或 D 可把每文件的 walk 数降下来。定量盘点脚本
不随文档保留，需要时直接扫 `plumb-style/src/main/java` 下各 `*Check.java` 的
`n.walk(` / `n.findAll(` 调用次数重新统计。

### 13.1 加新 Style 规则

1. 在 `plumb-style/src/main/java/org/plumb/style/<category>/FooCheck.java` 写规则，实现 `AstChecker` 接口。`<category>` 子目录**就是**分类。
2. 跑 `python scripts/gen_categories.py` 重新生成 `CheckerCategories.java`。那个文件是**自动生成的**（`// AUTO-GENERATED by scripts/gen_categories.py -- DO NOT EDIT`），生成器从 checker 所在的子目录推出它的分类——所以没有手写的 `m.put` 要加，手改这个文件会在下次重新生成时丢掉。
3. 跑 `mvn -o -pl plumb-style install` rebuild。
4. `java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar --list-rules | grep FooCheck` 验证。

⚠️ 直接放在包根目录的 checker **没有**分类，根本不会进 `CheckerCategories`——
`byCategory(...)` 选不到它。这种要用 `load("<glob>")` 或 `register(...)`。
（目前只有一个这样的 checker：`ObjectHijackCheck`。）

注意两条加载路径：`AstCheckerRegistry.builtins()` 走 classpath 扫描，**不**依赖
`CheckerCategories`；漏登记影响的是按分类加载的路径 `byCategory(...)`。

### 13.2 加新 DFA Unified 规则

1. 在 `plumb-dfa/src/main/java/org/plumb/dfa/checkers/<family>/FooChecker.java` 写规则，实现 `DfaChecker` 接口（或继承 `TaintedCheckerBase`）。
2. 在 `ScanRunner.buildDfaCheckers()` 加一行 `checkers.add(new org.plumb.dfa.checkers.<family>.FooChecker());`，并挂上对应的 YAML flag。
3. 在 `plumb-dfa/src/main/resources/checkers.yaml` 加一条 checker 条目（`enabled` 决定默认开关）。
4. 在 `plumb-dfa/src/main/resources/cwe-mappings.yaml` 加一条 `finding-key: CWE-xxx` 映射。
5. 跑 `mvn -pl plumb-dfa install` rebuild。
6. `java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar --list-rules | grep <rule-id>` 验证。

### 13.3 加新 Taint Rule

1. 在 `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRuleRegistry.java` 加一条 `m.put("<rule-id>", TaintRule.of(...))`。
2. **在 `plumb-dfa/src/main/resources/sink-families.yaml` 注册 source / sink 模式**（不存在
   `TaintCatalog` 这个类——早期文档提到的它从未存在）。sanitizer 若不是某个 sink family 的
   固有属性，则由 `TaintedCheckerBase.isSanitizer(...)` 判定，它是**private static** 方法、
   里面是一张写死的 callee 签名前缀清单——子类覆盖不了它，所以新的 sanitizer 要加在那里。
3. 在 `plumb-dfa/src/main/resources/cwe-mappings.yaml` 加一条对应映射。
4. rebuild + verify。

#### sink pattern 怎么写（重要）

sink pattern 用**全限定名**写，并**锚定**在 `^`：

```yaml
- '^org\.springframework\.jdbc\.core\.JdbcTemplate\.query(/\d+)?'
```

锚定是必要的：`SinkPattern` 用 `Matcher.find()` 做**子串**匹配，
`^org\.apache\.commons\.io\.IOUtils\.read` 不锚定就会匹配到
`...IOUtils\.readAllBytes` 这样的别的方法。

**不要**因为「怕写错包名」而把包名省略。匹配时会自动派生一份
**前缀可选**的变体（见 `SinkFamily.SinkPattern.relaxedForm`）：

```
^org\.springframework\.jdbc\.core\.JdbcTemplate\.query(/\d+)?
        ↓
^(?:[\w.]+\.)?JdbcTemplate\.query\b(/\d+)?
```

这解决的是 TAC 的一个硬约束：**callee signature 只有在符号解析器能解析
接收者类型时才是全限定名**。第三方依赖的 jar 不在分析 classpath 上时
（扫描一个不带依赖 jar 的项目是常态），IR 里写的是 simple name：

```
org.springframework.jdbc.core.JdbcTemplate.query/2   ← 解析成功
JdbcTemplate.query/2                                   ← 解析失败
```

没有这份前缀可选的变体，**所有框架 sink（Spring JdbcTemplate、MyBatis、
Jackson、Guava）会静默失配**——分析照常跑完，只是一条 finding 都不报。

变体的三条边界：

- **只在接收者是 simple name 时启用**。全限定名的 callee 走严格形式，
  所以精确匹配永远优先。
- **只放宽包前缀**，类名和方法名仍然必须精确匹配。
  `JdbcTemplate.query` 不会匹配 `NamedParameterJdbcTemplate.query`。
- **方法名后加了词边界 `\b`**。否则 `JdbcTemplate.query` 会连
  `JdbcTemplate.queryForList` 一起匹配上，而这两者的参数下标不同。
