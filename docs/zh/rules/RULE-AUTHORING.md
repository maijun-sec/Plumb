# 规则开发：AI prompt / CWE 标签 / 外部工具 ID / 加新规则

[English](../../rules/RULE-AUTHORING.md) | [中文](../README.md)

本文档讲怎么写一条规则：它带的 AI prompt、CWE 标签从哪来、与外部工具的等价 ID、
以及加一条新规则的步骤。规则主清单见 `RULES.md`；Style 规则操作（启用禁用 / 改参数）见 `RULE-STYLE-OP.md`。

## 1. AI augmentation 与每条规则的 prompt

每条 `DfaChecker` / `AstChecker` 自己持有 `getAiPrompt(Finding) -> String`：

- 返回 `null` = 该规则**不走** AI dedup
- 返回非 null = LLM 收到这条规则的 prompt + finding 详情 + dataflow trace + 源码窗口，输出 TP / FP / UNKNOWN

prompt 默认由 checker 提供。手写 override 见：
- 42 个 `DfaChecker` + `TaintedCheckerBase`（auto-generated from `TaintRule` 元数据）
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

#### passthrough 表：语言，以及分桶

两张 passthrough 表——`receiver-passthrough.yaml` 与 `argument-passthrough.yaml`
——既说清一个 family 属于哪种语言，也能说清单条 pattern 属于哪种语言：

```yaml
families:
  collection-getters:
    enabled: true
    languages: [java, kotlin, scala, groovy, csharp]
    patterns:                     # family 里的每种语言都能到达
      - '^((?:[\w.]+\.)*)Map\.get(/\d+)?'
    by_language:                  # 只有该语言能提到的符号
      csharp:
        - '^((?:[\w.]+\.)*)Dictionary\.get(/\d+)?'
```

`languages:` 是闸门。不声明语言的 family 对所有语言生效；声明了的，只对声明
的那些语言生效。桶只属于一种语言，桶里的 pattern 只在那里触发。

桶里的语言不在 family 自己的 `languages:` 里，这个桶就是死的：family 在那种
语言上根本不生效，桶里的 pattern 永远不会触发。loader 读到时会说出这件事。

#### 一个桶可以声明多种语言

两种语言可能到达**同一批**库符号：TypeScript 和 JavaScript 由同一个前端读取，
产生同样的 callee signature，所以它们的代码能提到的符号是一份清单而不是两份。
把这份清单写两遍，两份就会慢慢分叉；而一份自相矛盾的目录，其行为取决于文件
恰好是用哪种语言写的。

所以桶的键可以声明多种语言，用逗号分隔：

```yaml
    by_language:
      typescript,javascript,arkts:
        - pattern: '^(?:node:)?child_process\.(exec|execSync)(/\d+)?'
          arg_index: 0
```

每种被点名的语言都会拿到这些 pattern，并且各自单独与 family 自己的
`languages:` 闸门对照：桶里点名了 family 不覆盖的语言时，那种语言的那一份会被
丢弃，loader 会说出这件事。

#### 脚本语言：按模块写的目录

TypeScript、JavaScript 和 ArkTS 是通过**模块**而不是通过类去够它们的库的：
`child_process.exec` 是 `child_process` 模块的一个函数，没有类可以点名。所以目录
点名的是这个函数从哪个模块导入而来。

| callee key | 它是什么 |
| --- | --- |
| `child_process.exec/2` | 从 `child_process` 模块导入的 `exec` |
| `node:child_process.execSync/2` | 同一个模块，换成 Node 16 之后偏好的写法 |
| `app.eval/1` | 全局 `eval`：自由函数没有接收者，所以 owner 是**文件**所属的模块 |

点名模块正是规则保持精确的原因。一份只点名方法名的目录会认领程序里的每一个
`exec`——包括 `RegExp.exec`，它跑的是正则表达式而不是命令，而 `exec` 是脚本程序
里最常见的方法名之一。

这两种形状的差别有个值得记住的原因。模块级函数是通过模块到达的，所以它的 owner
是那个模块；自由函数根本没有接收者对象，接收者槽里放的是模块名的常量，于是
owner 变成了**文件**所属的模块。这就是为什么 `eval` 的 pattern 只点名方法
（`\.eval\b(/\d+)?`），而模块级的 pattern 点名模块。

#### 脚本语言的 semantics 表按操作命名，不按 owner

库语义表决定一次调用是否把接收者的污点带进结果，污点引擎在把一次调用当作字符串
透传之前会查这张表。它的键是 callee key，而 callee key 只有在前端能解析出接收者
类型时才带 owner。在脚本语言里字符串是**原始类型**，而原始类型不带类符号：对着
TypeScript 编译器实测，字符串接收者的类型根本不报符号，数组接收者报 `Array`，
类实例报它自己的类。

于是，在脚本程序里承载污点的那些操作——字符串的那些——恰恰是一张按 owner 写的表
点不到名的。所以脚本语言的表按**操作**命名：

```java
LanguageSemantics.Entry.byMethod("trim", TaintBehaviour.PRESERVES)
```

比较时忽略大小写，与引擎自己的方法名分类一致：两者必须对齐，否则引擎认成 trim
的调用找不到 PRESERVES 条目，污点就停在那里。代价是按方法名的条目分不清字符串的
`trim` 和用户对象的 `trim`，所以这张表只列那些「无论在哪里拼写、行为都相同」的
操作。

#### Python：同样的形状，而且别名被解析掉了

Python 和脚本语言一样，是通过**模块**到达它的库的，而前端会把别名解析掉，所以目录
点名的是函数**被导入自**的那个模块，而不是程序里写的那个名字。

| callee key | 它是什么 |
| --- | --- |
| `os.system/1` | `os.system`，也包括 `from os import system` |
| `subprocess.run/1` | subprocess 家族 |
| `builtins.eval/1` | 内建函数：owner 是 Python 自己提供它的那个模块 |
| `pickle.loads/1` | 字节串是第一个参数 |
| `os.environ.get/1` | 环境变量：值不是程序自己选的 |

这个形状带来两条规则。只点名方法名的规则会认领程序里的每一个 `loads`——而
`json.loads` 是最常见的一个，它解析 JSON，不构造对象。另外，内建函数是按 Python
提供它们的那个模块命名的，所以 `eval` 是 `builtins.eval` 而不是 `eval`。

Python 的 semantics 表同样是按方法名写的，原因和脚本语言一样。Python 的字符串是
原始类型，前端推不出接收者类型时退回**文件**所属的模块：只有带注解的参数才会带着
类型到达，所以按 owner 写的表只会点名带注解的那一半，把普通的那一半丢掉。

Python 没有覆盖到的部分，以及原因：

- `socket.connect`，以及程序自己造出来的对象上的其他方法：接收者的类型推不出来，
  owner 就是文件所属的模块，这条调用没有可以被匹配的名字。
- 读文件：内容是通过文件对象上**之后**的一次调用到达的，而那次调用有同样的、
  点不出名字的 owner。

#### PHP：按 PHP 自己写的限定名

PHP 用命名空间限定名字，全局函数写作前导反斜杠。目录点名的是 PHP 自己写的那个限定名：

| callee key | 它是什么 |
| --- | --- |
| `\system/1` | 全局函数：`system`，在命名空间里调用它也是这个形状 |
| `App\Support\sanitize/1` | 命名空间里的函数 |
| `App\Http\Greeter::greet/1` | 静态方法 |
| `<php>.include/1` | `include`、`require`、`require_once`：语言构造，前端给它们一个合成 owner |

由此带来两件事。对内建函数的调用是**黑盒**——前端没解析出来——所以污点能不能穿过它由
semantics 表决定，而表里的 DISSOCIATES 条目正是把污点停在那些真的不承载污点的调用上的
东西：解析出来的数字不是它被解析自的那段文本，摘要也不是。

还有，方法名必须按 PHP 的分隔符来读。全局函数的 key 里根本没有点——`\trim/1`——只按点
切分的读法会把整串当成方法名，方法名清单就一条都匹配不上。引擎自己的读法和 semantics
表的读法现在都按「点或反斜杠」切分，因为两者必须对齐。

PHP 桶里的每个名字都以词边界结尾，这不是装饰。引擎对 sink pattern 是按**子串**匹配的，
所以一个没有边界的 `system` pattern 也会认领 `systemctl`——而一个自己定义了
`system_info` 的项目就会被报成命令注入。

PHP 没有覆盖到的部分，以及原因：

- `eval`：前端把它降级成一个未知值而不是一次调用，所以没有 callee key 可供规则点名。
- 超全局 `$_GET`、`$_POST` 之类：它们是容器读取而不是调用，所以没有 callee key 可供规则点名。
  它们由下面第二种源声明覆盖。

#### 不是调用的源：source variables

目录里其他每一个源都是 callee 签名，而目录匹配的也正是签名。PHP 超全局不是调用——`$_GET['user']`
是容器读取——没有签名可点名，值到达时也不带任何标记。`$_GET['x']` 流进 `system()` 是 PHP 最经典
的漏洞形态，而一个只能点名的目录什么都看不见。

所以一个源族可以声明 SOURCE VARIABLES：承载不可信数据的变量名，按语言分域。

```yaml
taint.sources.web:
  source_variables:
    php:
      - '_GET'
      - '_POST'
      - '_REQUEST'
      - '_COOKIE'
```

引擎在函数入口处、参数旁边把它们标成源，因为超全局在完全相同的意义上就是一个信任边界入口：
请求就是经过它到达程序的。它带的标签是引擎别处已经认识的 `input:` 那个，所以这份值像参数的值
一样，在被常量赋值之后仍保留污点。

分域正是关键。`_GET` 这个名字在 PHP 里是「请求的查询串」，在别的语言里什么都不是，所以声明里
写了语言，引擎按正在分析的语言去要名字。若这条规则到处都生效，任何有一个叫 `_GET` 的字段的
Java 程序都会被认领。

有两个名字是刻意不收的，理由是「请求」和「配置」的区别。`$_SERVER` 把请求头和服务端自己的设置
混在一起，把整个数组标成不可信就会声称攻击者控制不了的值；`$_FILES` 里是 PHP 自己生成的临时
文件名。
