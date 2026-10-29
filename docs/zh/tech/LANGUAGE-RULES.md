# 按语言划分的规则：语言到底在哪里进入

[English](../../tech/LANGUAGE-RULES.md) | [中文](../README.md)

本文档只回答一个问题：**如果污点引擎要同时支持 Kotlin、Scala 和 Java，
语言是从哪里进入的？**

答案由测量决定，不是靠读代码猜。`scripts/survey_language_readiness.py`
产出下面的数字；配置或引擎有任何改动之后都要重跑一次。

```bash
python scripts/survey_language_readiness.py
```

## 1. 今天的配置是什么样

六个 YAML 文件，由 `plumb-dfa` 的 `ConfigLoader` 加载：

| 文件 | 形态 | 装的是什么 |
| --- | --- | --- |
| `sink-families.yaml` | `families:` | 39 个 family、416 条模式 —— 污点 source 和 sink |
| `receiver-passthrough.yaml` | `families:` | 9 个 family、95 条模式 —— 哪些 getter 返回带污点的数据 |
| `fact-kinds.yaml` | -- | 引擎传播的 fact 种类 |
| `checkers.yaml` | -- | 每个 checker 的参数 |
| `cwe-mappings.yaml` | -- | 每个 checker 带的 CWE |
| `tuning.yaml` | -- | 激进度旋钮及其同类 |

`ConfigLoader` 默认从 classpath 加载，也可以从用户给的目录加载。
覆盖机制是一个 `文件名 -> 路径` 的 map，所以用户现在已经能替换其中任意一个文件。

## 2. 测量结果

**规则模型没有语言概念。** `SinkFamily` 的字段是：

```
String pattern, int argIndex, Pattern compiled, Pattern relaxed,
String name, String cwe, String severityLabel, boolean enabled,
int defaultArgIndex, List<SinkPattern> patterns
```

没有 `language` 字段，而且也不需要：一个 family 是一组模式，一个模式是一条正则，
一条正则匹配一个被调用者的签名。

**65% 的 sink 模式点名的是 Java 的库类。** `sink-families.yaml` 的 416 条模式里，
**270 条点名了 `java.` / `javax.` / `jakarta.` 的类**，另外 146 条点名的不是
（项目自己的类，或者一个光秃秃的方法名）。

有 11 个 family 是**完全由 Java 库模式组成**的：

```
cleartext-tx.sinks (21)    file-path.sinks (20)    format-string.sinks (13)
mem-alloc.sinks (13)       xss.sinks (13)          taint.sources.io (6)
taint.sources.network (3)  taint.sources.console (2)
taint.sources.env (2)      regex.sinks (2)         xquery.sinks (1)
```

**receiver-passthrough 的模式用的是宽松形式。** 它们匹配
`^((?:[\w.]+\.)*)HttpServletRequest\.getParameter` —— 类名但不带包名，
所以模式文本本身不含 `java.`。它点的那个类仍然是
`javax.servlet.http.HttpServletRequest`。

**语言只出现在三个地方，而且只有三个：**

| 位置 | 是什么 |
| --- | --- |
| `plumb-parser` 的 `JavaSourceParser` | 解析器 —— 对 Java 解析器的 11 处引用 |
| `plumb-tac` 的 `TypeMapper` | 类型映射器 —— 对 Java 解析器类型模型的 8 处引用 |
| `plumb-main` 的 `ScanRunner` | 源文件分发 —— 11 处 `.java` 扩展名判断 |

## 3. 这意味着什么

**规则模型已经是语言无关的。** `SinkFamily`、`FactKind` 以及 YAML 的形态里，
没有任何东西知道"语言"是什么。一套 Kotlin 规则就是同样的 YAML，换掉模式而已。

**配置需要按语言拆开，拆开的程度只取决于它点的 LIBRARY 是不是不一样。**
这是一个**数据**上的差异，不是结构上的差异。Kotlin 程序不会调用
`javax.servlet.http.HttpServletRequest`，它调用的是 `io.ktor.server.request.*`
或者 `spark.Request`。缺陷类别 —— "不可信输入到达了 SQL sink" —— 是同一个；
模式不是。

**所以按语言的布局是一个目录，不是一次 schema 改动。**

```
plumb-dfa/src/main/resources/
  rules/
    java/
      sink-families.yaml
      receiver-passthrough.yaml
    kotlin/
      sink-families.yaml
      receiver-passthrough.yaml
```

共享的那几个文件（`fact-kinds.yaml`、`checkers.yaml`、`cwe-mappings.yaml`、
`tuning.yaml`）留在原地，因为它们描述的是**引擎**，不是语言。

**加载规则必须定下来，而这里才是真正需要设计的问题。** 三个选项：

1. **每语言一个目录，按文件的语言加载。**
   扫描是按文件走的，所以一个同时装着 `Foo.java` 和 `Foo.kt` 的目录会加载两套
   family，然后合并。按 family 名合并意味着两边都定义的 family 取模式的并集 ——
   这是错的，因为 `java.sql.Statement#execute` 和
   `scala.collection.mutable.Set#+=` 是不同的 sink，不该因为名字撞了就共享 CWE 归属。

2. **每语言一个目录，family 的键带语言。** `java.sql.sinks` 和 `kotlin.sql.sinks`
   是两个独立的 family，带各自的 CWE。合并上没有歧义。代价是报告里的 family 名
   会带着语言。

3. **模式自己带语言。** 每条模式说明它属于哪个语言的库，引擎按被扫描文件的语言
   过滤 family 里的模式。没标语言的模式对所有语言都适用 —— 这正是
   "任意接收者上一个叫 `exec` 的方法"这种语言无关模式想要的。

本文档推荐选项 3，理由很具体：**缺陷类别在各语言之间是同一个，而报告应该说的是
缺陷类别。** 一条写着 `CWE-89 SQL injection` 的发现，不管项目是 Java 还是 Kotlin，
都成立。如果 family 按语言拆开，规则 id 也跟着拆，用户给一个语言配的白名单就会
悄悄地对另一个语言失效。

但选项 3 需要 sink-family 模型开始理解语言，而今天的 `SinkFamily` 没有 language
字段。这是模型**唯一**必须改的地方。

## 4. 要做什么，按什么顺序

1. **语言到达扫描层。** `ScanRunner` 在 11 个地方按 `.java` 扩展名分发。
   这变成按语言分发：扩展名 -> 语言 -> 解析器。那三个本来就按语言分的组件
   （解析器、类型映射器）拿到一个接口。

2. **`SinkPattern` 学会自己的语言。** 一条模式说明它点的是哪个语言的库。
   引擎按被扫描文件的语言过滤 family 里的模式。没标语言的模式对所有语言都适用。

3. **配置按语言拆开，共享文件保持共享。** 就是上面那个目录布局，
   该语言的模式放在该语言的文件里。

4. **规则目录保持"一个缺陷类别一条规则"。** CWE 映射不变；变的只是
   能够到达 sink 的模式。

## 5. 本文刻意不定的部分

- **Kotlin 和 Scala 的解析器。** 每一种都需要自己的前端（Java 用 JavaParser；
  其他的用 Kotlin 编译器的 PSI，或 Scala 编译器的语法树）。那是另一件独立的
  工作，有自己的测量。
- **支持哪些语言。** 上面的布局是按语言的，但哪些语言各配一份，是产品决策。
- **面向用户的覆盖形态。** `ConfigLoader` 已经收一个 `文件名 -> 路径` 的 map。
  按语言的布局会让这个 map 也按语言来；用户是覆盖某一个语言的文件还是全部，
  是关于用户体验的决策，不是关于引擎的决策。
