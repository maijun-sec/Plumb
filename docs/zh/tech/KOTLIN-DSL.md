# Kotlin AST 规则引擎（`plumb-dsl-kotlin`）

[English](../../tech/KOTLIN-DSL.md) | [中文](../README.md)

`plumb-dsl-kotlin` 是一个**独立的 AST 规则引擎**。规则用一种小型 Kotlin 风格 DSL 编写，
在 JavaParser 的 AST 节点上求值。项目可以自带 AST 规则，而不用写 Java。

**数据流 / 污点规则不在这个模块里。** 它们仍然由 `plumb-dfa` 的 YAML 配置驱动
（`sink-families.yaml`、`checkers.yaml`、`tuning.yaml`）。两个引擎互不重叠：

| 规则类型 | 配置方式 | 所在模块 |
| --- | --- | --- |
| AST 规则（语法形态、局部模式） | Kotlin DSL 规则文件（XML / YAML） | `plumb-dsl-kotlin` |
| 数据流 / 污点规则（source → sink） | YAML | `plumb-dfa` |

---

## 1. 快速上手

### 1.1 写一条规则

规则文件的最小形态（YAML）：

```yaml
rules:
  - id: plumb.dsl.demo-empty-catch
    name: demo empty catch block
    description: A catch block with no statements
    severity: MEDIUM
    type: BAD_PRACTICE
    cwe: [CWE-390]
    nodeType: CatchClause
    matcher: |
      CatchClause catchClause: catchClause match { empty }
```

`matcher` 的形状是：

```
<NodeType> <binding>: <predicate>
```

- `NodeType` —— JavaParser 的节点类型名（`CatchClause`、`MethodDeclaration`、
  `Comment`……）。抽象类型也支持：`Comment` 会匹配 `LineComment` /
  `BlockComment` / `JavadocComment`，`Statement` 会匹配所有语句子类。
- `binding` —— 节点在谓词里的名字。
- `predicate` —— 匹配条件。

### 1.2 跑起来

```bash
plumb -i src/main/java --dsl-rules rules/ast-rules.yaml
```

`--dsl-rules` 可以重复；多个文件里的规则会合并。

也可以写进 YAML 配置：

```yaml
dsl:
  rules:
    - rules/ast-rules.yaml
  strict: false
```

CLI 上的 `--dsl-rules` 是**追加**到 YAML 已列出的文件之后，不是覆盖。

### 1.3 输出

规则命中后产生的 finding 和内置规则完全一样，走同一条 reporter 管线：

```json
{
  "file": "src/main/java/Sample.java",
  "line": 5,
  "column": 11,
  "severity": "medium",
  "rule_id": "plumb.dsl.demo-empty-catch",
  "rule_name": "DEMO_EMPTY_CATCH_BLOCK",
  "rule_type": "bad_practice",
  "cwe": ["CWE-390"],
  "message": "A catch block with no statements",
  "dataflow": "dsl:plumb.dsl.demo-empty-catch",
  "sink_function": "run():void"
}
```

---

## 2. 规则文件格式

### 2.1 XML（上游兼容）

上游 `java-sca-ast-dsl` 项目用 XML。Plumb 直接读同一种格式：

```xml
<rules>
    <rule language="java">
        <id>8E2C4F1A-0B3D-4E5F-9A7B-1C2D3E4F5A6B</id>
        <name>empty catch block</name>
        <description>empty catch block</description>
        <matcher>
            <![CDATA[
                CatchClause catchClause: catchClause match { empty }
            ]]>
        </matcher>
    </rule>
</rules>
```

规则元素的子元素：

| 元素 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 规则 id（上游是 UUID，见 §3.1） |
| `name` | 是 | 短名 |
| `description` | 否 | 描述，缺省用 `name` |
| `matcher` | 是 | 匹配表达式，通常放在 CDATA 里 |

XML 里**没有** `nodeType` 字段——节点类型从 `matcher` 的头部解析出来。

XML 也接受 `severity` / `type` / `enabled` / `cwe` 这四个可选元素（语义与 YAML
里同名字段一致），只是上游文件不写它们。

`<rule>` 上的 `language` 属性会被忽略；未知的子元素也不会导致加载失败。

**格式按扩展名选择**：`.xml` 走 XML，其余一律走 YAML。

### 2.2 YAML（Plumb 扩展）

YAML 形态多出 Plumb 自己的元数据：严重度、类型、CWE、启用开关。

| 字段 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `id` | 是 | — | 规则 id。见 §3.1 |
| `name` | 是 | — | 短名，会变成 finding 的 `rule_name` |
| `description` | 否 | 用 `name` | finding 的 message |
| `matcher` | 是 | — | 匹配表达式 |
| `nodeType` | 否 | 匹配所有节点 | JavaParser 节点类型名 |
| `severity` | 否 | `MEDIUM` | `INFO` / `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` |
| `type` | 否 | `OTHER` | Plumb 的规则类型，如 `BAD_PRACTICE` / `SECURITY` |
| `cwe` | 否 | 空 | CWE 编号列表（字符串或列表） |
| `enabled` | 否 | `true` | 关掉这条规则 |

`nodeType` 留空时规则作用于**每一个** AST 节点——通常不是想要的，但引擎允许。

写错 `nodeType`（拼了一个不存在的节点类）会被记 WARN 并**跳过这条规则**，而不是
悄悄退化成"匹配所有节点"。

---

## 3. 规则 id 与别名

### 3.1 id 归一化

上游用随机 UUID 当规则 id。Plumb 的 registry、SARIF 输出和文档门禁都要求
`plumb.dsl.<slug>` 这个约定，所以引擎会做一次归一化：

- id 已经以 `plumb.` 开头 → 原样保留。
- 其它 id（上游的 UUID）→ 由 `name` 推导出 slug，变成 `plumb.dsl.<slug>`。

归一化后的 id 是 finding 真正使用的 id；**原始 id 保留为 alias**，所以按上游 id
过滤规则依然有效。

### 3.2 规则的启停

一条 DSL 规则的启用状态有三个来源，优先级从低到高：

1. 规则文件里的 `enabled: false`。
2. `RuleRegistry` 里的状态——runner 会把 DSL 规则注册进 live registry，
   用户 YAML 的 `rules.enabled` / `rules.disabled` 按通常的规则作用于它们。
3. 命令行的 `--rule <id>`。

---

## 4. 匹配器语言

### 4.1 基本形态

```
<NodeType> <binding>: <predicate>
```

谓词是 Kotlin 表达式，但**不是**任意 Kotlin：引擎会把它重写成对一组静态
facade 方法的调用（见 §5）。

### 4.2 节点属性（裸名字）

谓词里可以直接写节点属性，引擎会把它重写成对 `NodeAccess` 的调用：

| 写法 | 实际调用 |
| --- | --- |
| `simpleName` | `NodeAccess.simpleName` |
| `qualifiedName` | `NodeAccess.qualifiedName` |
| `ownQualifiedName` | `NodeAccess.ownQualifiedName` |
| `methodName` | `NodeAccess.methodName` |
| `parameterTypes` | `NodeAccess.parameterTypes` |
| `parameters` | `NodeAccess.parameters` |
| `exception` | `NodeAccess.exception` |
| `enclosingFunction` | `NodeAccess.enclosingFunction` |
| `enclosingClass` | `NodeAccess.enclosingClass` |
| `static` | `NodeAccess.isStatic` |
| `final` | `NodeAccess.isFinal` |
| `type` | `NodeAccess.type` |
| `content` | `NodeAccess.content` |
| `method` | `NodeAccess.method` |
| `empty` | `NodeAccess.isEmpty` |

### 4.3 中缀运算符

| 写法 | 实际调用 |
| --- | --- |
| `s startsWith p` / `s startWith p` | `StringOps.startsWith` |
| `s endsWith p` | `StringOps.endsWith` |
| `s matches regex` | `StringOps.matches` |
| `c contain e` / `c contains e` | `Ops.contains` |

### 4.3.1 布尔运算符

| 写法 | 变成 |
| --- | --- |
| `a and b` | `a and b` —— Kotlin 自带的 `Boolean.and` |
| `a or b` | `a or b` —— Kotlin 自带的 `Boolean.or` |
| `a && b` / `a \|\| b` | 不变 |
| `not a` | `!(a)` |

只有 `not` 需要改写。上游 DSL 用单词拼布尔运算符，因为它把这些定义成 Kotlin 扩展中缀函数。
Kotlin 的 `Boolean.and` 和 `Boolean.or` 是成员函数，所以这两个原样就能用 —— 但它**没有前缀**
`not` 运算符：`Boolean.not()` 是函数调用，所以 `not a` 是语法错误，规则编译不过。

操作数也要加括号，因为 `!` 的结合优先级比比较运算符高：`not a == b` 必须变成 `!(a == b)`，
而不是 `!a == b`。后者是拿 Boolean 和 String 比较，是类型错误，不是规则想要的取反。

只有独立成词的 `not` 才会被改写。只是以 `not` 开头的名字 —— `nothing`、`annotate` —— 是另一个
词，保持原样。

### 4.4 节点与类型的访问

规则的谓词里可以访问当前节点和它的类型：

| facade | 作用 |
| --- | --- |
| `NodeAccess.simpleName(n)` | 节点所在类的简单名 |
| `NodeAccess.qualifiedName(n)` | 节点所在类的限定名 |
| `NodeAccess.ownQualifiedName(n)` | 节点自己的名字：外层类型 + 成员 |
| `NodeAccess.content(n)` | 节点的源码文本 |
| `NodeAccess.isEmpty(n)` | 节点是否为空 |
| `NodeAccess.method(n)` | 取方法声明 |
| `NodeAccess.methodName(n)` | 方法名 |
| `NodeAccess.methodNameOf(n)` | 方法名（另一种取法） |
| `NodeAccess.resolvedMethodName(n)` | 解析后的方法名 |
| `NodeAccess.parameters(n)` | 参数列表 |
| `NodeAccess.parameterTypes(n)` | 参数类型列表 |
| `NodeAccess.parameterAt(n, i)` | 取参数列表里第 `i` 个参数 |
| `NodeAccess.type(n)` | 节点声明的类型 |
| `NodeAccess.exception(n)` | 节点携带的异常 |
| `NodeAccess.exceptionName(n)` | 异常名 |
| `NodeAccess.exceptionType(n)` | 异常类型 |
| `NodeAccess.enclosingClass(n)` | 外层类名 |
| `NodeAccess.enclosingFunction(n)` | 外层函数名 |
| `NodeAccess.constructorName(n)` | 构造器名 |
| `NodeAccess.isStatic(n)` | 是否 static |
| `NodeAccess.isFinal(n)` | 是否 final |
| `TypeAccess.typeName(n)` | 解析后的类型全名 |
| `TypeAccess.resolve(n)` | 解析成 `ResolvedType` |
| `TypeAccess.supers(n)` | "is-a" 闭包：类型自身 + 所有父类型 |
| `TypeAccess.strictSupers(n)` | 严格父类型（不含自身），与上游语义一致 |
| `TypeAccess.supersOf(typeRef)` | 同 `supers`，作用于一个类型引用 |
| `TypeAccess.strictSupersOf(typeRef)` | 同 `strictSupers`，作用于一个类型引用 |
| `TypeAccess.typeNameOf(typeRef)` | 类型引用的全名 |
| `TypeAccess.resolveTypeName(typeRef)` | 类型引用的全名 |
| `TypeAccess.resolveDeclaredTypeName(typeRef)` | 声明类型的全名 |
| `TypeAccess.typeAt(types, index)` | 取列表里第 `index` 个类型 |

> **上游语义差异。** 上游的 `ResolvedType.supers` 不包含类型自身，所以上游
> "poor logging practice" 那条规则对直接声明的 `Logger` 字段不会触发。
> Plumb 的 `TypeAccess.supers` 用的是 "is-a" 闭包（**包含**自身），
> `TypeAccess.strictSupers` 保留上游行为。

### 4.5 字符串与集合

| facade | 作用 |
| --- | --- |
| `StringOps.startsWith(s, p)` | 前缀 |
| `StringOps.endsWith(s, p)` | 后缀 |
| `StringOps.matches(s, regex)` | 正则匹配（`(?!` 前缀表示忽略大小写） |
| `StringOps.contains(s, sub)` | 子串包含 |
| `Ops.contains(collection, element)` | 集合含某元素 |
| `Ops.containsText(collection, text)` | 集合里有元素包含该文本 |
| `Ops.size(collection)` | 集合大小 |

### 4.6 结构性匹配器

| facade | 对应的 DSL 写法 | 作用 |
| --- | --- | --- |
| `Matchers.match(node) { ... }` | `node match { ... }` | 谓词作用于节点自身 |
| `Matchers.contain(node) { ... }` | `node contain { ... }` | **直接子节点**中有一个满足谓词 |
| `Matchers.ancestor(node) { ... }` | `node ancestor { ... }` | 任意祖先中有一个满足谓词 |

`contain` 和 `ancestor` 的谓词会拿到**被检查的那个节点**作为参数，所以
`contain { simpleName == "ReturnStmt" }` 的意思是"我的直接语句里有一个 return"。

#### `contain` 到底是什么意思

`contain` 看的是节点的**直接子节点**，仅此而已。由此推出三条，每一条都承载着语义：

- **节点不包含自己。** catch 子句不包含 catch 子句。
- **节点不包含孙节点。** catch 子句包含它的语句，不包含语句里的表达式；方法包含它的语句，
  不包含语句里的调用。
- **每一层嵌套就是往下一层。** `contain { contain { ... } }` 到达孙节点，
  `contain { contain { contain { ... } } }` 到达曾孙节点。

这就是上游的语义。上游的 `contain` 是：

```kotlin
infix fun Node.contain(block: Node.() -> Boolean): Boolean {
    if (this is NodeWithBlockStmt<*>) {
        this.body.statements.forEach {
            if (block(it)) { return true }
        }
    }
    return false
}
```

它遍历节点自己 body 里的语句，绝不往更深处看。子树搜索会在上游从不触发的规则上触发，
那已经是另一门语言了，不是规则文件当初针对的那一门。

> **一处有意的差异。** 上游的 `contain` 只作用于 `NodeWithBlockStmt`，而在 JavaParser 里
> 那只有 catch 子句和构造器 —— 所以上游的 `contain` 在方法体和代码块里什么也找不到。
> Plumb 引擎保留了"只看直接子节点"这条规则，同时把"有 body 的节点"这个集合放宽了，
> 因为"这个方法里有没有调用"是这门语言应该能回答的问题，而上游自己的规则文件并不依赖那个
> 窄口径。

### 4.7 多级匹配

`contain` 可以嵌套，因此一条规则可以表达"某个方法里有一个调用"。每一层嵌套就是往下一层，
内层块不需要自己的接收者 —— 它读的是外层 `contain` 正在看的那个节点：

```
MethodDeclaration method: method contain {
  contain { simpleName == "MethodCallExpr" }
}
```

---

## 5. 引擎怎么跑

### 5.1 编译一次，重复求值

每条规则**只编译一次**，编译产物（`javax.script.CompiledScript`）在整轮扫描里复用。

这一点是实测出来的，不是拍脑袋：

| 方式 | 每次求值耗时 |
| --- | --- |
| `engine.eval(text)`（每次重新编译） | 约 236,551 µs |
| `CompiledScript.eval()`（复用编译产物） | 约 543 µs |

差约 435 倍。热编译后单条规则的编译成本约 45 ms，扫描开始时付一次。

### 5.2 当前节点放在 ThreadLocal 里

Kotlin script 在**编译期**解析自由变量，所以脚本没法把节点当参数传进去——编译一次
之后就没有"每次求值传不同节点"的位置了。

上游的做法是每个节点重建一次脚本，也就是每节点一次完整重编译（236 ms）。
Plumb 的做法不同：

- 节点在**运行期**从一个 `ThreadLocal` 读取；
- 编译产物在**所有线程之间共享**。

为什么节点必须是 per-thread：Plumb 用 worker pool 并行扫描。共享一个静态字段，
两个 worker 会互相覆盖对方的节点——实测 8 线程、2400 次求值里出现 **635 次**错判。
换成 `ThreadLocal` 后，同样条件下**零错判**。

### 5.3 为什么规则不挂在 AstDispatcher 的 per-node 派发上

Plumb 的 `AstDispatcher` 按**精确的节点类**派发：它给每个 checker 声明的节点类型
建一张表，一个节点只会送给注册在那个精确类下面的 checker。这带来两个后果：

1. **dispatcher 永远不会把 `CompilationUnit` 送给 checker。** `CompilationUnit` 是
   树的**根**，不是树里的节点，`unit.walk(...)` 不会访问根。所以一个重写了
   `visit(CompilationUnit, StyleContext)` 的 checker **永远不会执行**。
2. **抽象节点类型无法表达。** 重写 `visit(CatchClause, ...)` 的 checker 只能看到
   `CatchClause`；而 DSL 规则可以写 `Statement` / `Expression` / `Comment`。

所以 DSL 规则**不能**走 dispatcher 的 per-node 派发。它们在
`AstChecker.finish(ctx)` 里运行——那是 dispatcher 每文件调用一次、保证能看到
整个 unit 的唯一钩子。

`DslAstChecker` 因此自己跑一遍遍历，按规则目标节点类型分组。

### 5.4 注释需要单独处理

JavaParser 把注释挂在它所注释的节点上，但**不把注释放进树里**：
`findAll(Node.class)` 永远不会访问到注释。注释要从 `getAllContainedComments()`
拿。所以一条目标是 `Comment` 的规则需要显式地把它们加进候选列表。

### 5.5 规则必须进 registry

`StyleContext.report(...)` 对"默认关闭且不在 registry 里"的规则会直接丢弃。
runner 因此把 DSL 规则的元数据注册进 live `RuleRegistry`，让它们和内置规则
一样参与 whitelist / blacklist 过滤。

---

## 6. 错误处理

| 情况 | 行为 |
| --- | --- |
| 规则文件不存在 | 记 ERROR，跳过该文件 |
| XML 格式错误 | 记 ERROR，跳过该文件 |
| 规则没有可用的节点类型 | 记 ERROR，跳过该规则 |
| matcher 重写失败 | 记 ERROR，跳过该规则 |
| 脚本编译失败 | 记 ERROR，跳过该规则 |
| 脚本求值抛异常 | 记 WARN，该节点不匹配，扫描继续 |

**一条坏规则不会让整轮扫描挂掉。**

`--dsl-strict`（或 YAML 的 `dsl.strict: true`）改变这一点：编译失败直接中止扫描。
严格模式适合 CI——一条写错的规则不应该被静默跳过。

---

## 7. 示例语料库

匹配器语言的示例就放在仓库里，而且是一份**会被执行**的语料库：

```
plumb-dsl-kotlin/src/test/resources/testcase/
  ├── examples.yaml    示例
  └── Fixture.java     示例运行的代码
```

`examples.yaml` 每条示例一个条目：

```yaml
  - name: empty-catch-block
    nodeType: CatchClause
    matcher: |
      CatchClause catchClause: catchClause match { empty }
    expect: 2
    shows: "最小的 matcher。头部给出节点类型和绑定名，主体是谓词。"
```

`expect` 是这条 matcher 在 `Fixture.java` 里必须匹配到的 `nodeType` 节点数。**零**也是合法的
期望值：一条**不应该**触发的规则，和一条应该触发的规则一样，是这门语言契约的一部分。

`DslExampleCorpusTest` 会跑每一条示例并核对数量。所以这份语料库是双向自证的：一条示例编译
不过，构建就失败；一条示例的数量漂了，构建也失败。

**而且语料库不能缩水。** 删掉一条示例，和"引擎不再支持某个构造"是同一件事，看不出来。所以
`scripts/check_dsl_corpus.py` 把语料库的大小钉住，并检查重写器的每一张词汇表 —— 节点属性、
包装器属性、列表方法、中缀运算符、结构性匹配器 —— 至少被一条示例用到。一个没人用的新属性
就是语料库的缺口，门禁会指出来。

这个门禁是变异测试过的：`scripts/mutate_dsl_corpus.py` 会删掉一条示例、删掉一条说明、给源码
加一个没人用的属性，这些漂移每一条都必须让门禁失败。

**语义本身也是变异测试过的。** 引擎*行为*的改变 —— `contain` 又变回子树搜索 —— 不会动语料库
文件，所以 Python 门禁看不见。变异脚本知道这一点，所以它有两类变异：上面那三个由门禁抓住，
还有一个改引擎的由 **Kotlin 测试**抓住。那个变异替换的是 `directChildren` 的**整个**函数体，
因为只替换它的兜底分支只会影响没有 block body 的节点 —— 那样变异会活下来，而且是因为错误的
原因活下来。

### 7.1 改掉的那些行为

写这份语料库找出七处引擎行为和规则作者预期不一致的地方：

- **`enclosingClass` 返回的是声明，不是名字。** 所以 `enclosingClass == "com.example.Foo"` 是
  编译错误 —— "Operator '==' cannot be applied to 'ClassOrInterfaceDeclaration?' and 'String'"。
  现在它返回限定名，和包装器的 `enclosingClass` 形状一致；声明本身另有
  `enclosingClassDeclaration`。
- **字符串上的 `contains` 编译不过。** 运算符解析到了集合版本，所以字符串接收者直接类型错误。
  现在运算符按接收者的类型解析：字符串是子串测试，集合是**精确**的成员测试 —— 上游的语义。
  集合保持精确是故意的：子串测试会让 `supers contain "java.lang.String"` 匹配到祖先列表里的
  `java.lang.Comparable<java.lang.String>`。
- **`contain` 搜的是整棵子树。** 它应该是上游的*直接子节点* —— 而子树搜索能找到直接子节点
  搜索能找到的一切，外加更多，所以它会在上游从不触发的规则上触发。它还把节点自身算进去了，
  因为 JavaParser 的 `findAll` 包含接收者：每个节点都"包含"自己。见 [§4.6](#46-结构性匹配器)。
- **`qualifiedName` 偏离了上游。** 上游的 `qualifiedName` 是 `javaClass.name` —— 节点所在
  **类**的名字，每个文件里每个方法都是同一个字符串。如果把它改成返回节点自己的名字，
  就会破坏任何按上游写的规则。现在 `qualifiedName` 就是上游的含义，节点自己的名字是新增的
  `ownQualifiedName`。
- **`parameters[0].name` 编译不过。** 重写器假定所有被索引的列表装的都是类型，于是
  `parameters[0]` 被重写成解析后的类型，从它上面读的属性就成了类型的属性。两个列表装的东西
  不一样 —— `parameterTypes` 装类型，`parameters` 装参数 —— 所以重写器现在会先问自己索引的
  是哪个列表。
- **带自己头部的嵌套 matcher 编译不过。** `contain { MethodCallExpr call: ... }` 有第二个绑定，
  而重写器一条规则只有一个绑定。嵌套块读的是外层 `contain` 正在看的那个节点，所以它不需要
  自己的绑定。
- **没有接收者的结构性匹配器被改写错了。** 那一趟会把**外层**表达式当成接收者吃掉，于是
  `contain { contain { ... } }` 变成了胡话。

---

## 8. 模块与依赖

```
plumb-dsl-kotlin
  ├── plumb-parser          (ParsedSource)
  ├── plumb-rules           (RuleDef / RuleRegistry)
  ├── plumb-reporter        (Finding)
  ├── plumb-style           (AstChecker / StyleContext SPI)
  ├── javaparser-core
  ├── javaparser-symbol-solver-core
  ├── kotlin-scripting-jsr223
  └── kotlin-stdlib
```

`plumb-style` 依赖这个模块，但这个模块**不**依赖 `plumb-style` 的任何子模块——
SPI 在 `plumb-style` 里，实现在这里，没有环。

`plumb-main` 依赖 `plumb-dsl-kotlin`，把 `DslAstChecker` 接进 style 检查链。

Kotlin 版本固定在 **2.2.0**：这是第一个在 JDK 25 上能跑、同时仍然产出 Java 17
字节码的版本（1.9.22 不完整，2.1.0 在 JDK 25 上抛 `IllegalArgumentException: 25.0.1`）。

---

## 9. 相关文档

| 文档 | 内容 |
| --- | --- |
| [`../../usage/CLI.md`](../../usage/CLI.md) | `--dsl-rules` / `--dsl-strict` 的 CLI 手册 |
| [`../../usage/CLI-REFERENCE.md`](../../usage/CLI-REFERENCE.md) | YAML v2 速查，含 `dsl:` 块 |
| [`STYLE-RULES.md`](STYLE-RULES.md) | 内置 style 规则、`AstDispatcher`、`AstCheckerRegistry` |
| [`../../rules/RULE-AUTHORING.md`](../../rules/RULE-AUTHORING.md) | 写规则的两种方式：Java checker 与 Kotlin DSL |
