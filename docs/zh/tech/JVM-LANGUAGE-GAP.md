# JVM 差距实测：TAC 丢失了什么，规则目录覆盖到了什么

[English](../../README.md) | [中文](../../tech/JVM-LANGUAGE-GAP.md)

> 状态：JVM 语言支持与规则目录的实测审计
> 范围：**三个前端丢了什么、那会造成什么、以及两个参考实现的目录覆盖到了什么**
> 方法：下面每一条断言都是本仓库或本地参考材料的**实测**性质，并且写明测量方式。

---

## 0. 先说结论

这里回答三个问题。结论都是测量出来的。

| 问题 | 答案 |
|---|---|
| Q1. Java/Kotlin/Scala 前端丢了什么，有影响吗？ | Java 静默丢 2 个构造。Kotlin 丢 7 个，全部有报告。Scala 丢 11 个，全部有报告 —— **另外还有 40 个标准库操作没有产生可用的调用：其中 31 个被报告了但理由是错的，9 个则悄无声息地消失了**。 |
| Q2. 两个参考实现的 DFA 有哪些可借鉴，是否已经实现？ | 6 条思路，其中 **4 条已经实现**。没实现的 2 条列在下面。 |
| Q3. 对照参考目录，差距在哪？ | **它们的 28 个 JVM 缺陷类别，这里全部有对应的 family。** 差距不在广度 —— 而在于 **19 个 family 根本没有任何 watcher**，其中 12 个来自规则包迁移。 |

最后一行才是关键。目录看起来是完整的：81 个 family、成千上万个 pattern、每个都有 CWE。其中 19 个却永远不可能产生 finding —— 因为 family 是 *pattern 集合*，而真正去看它的是 **checker**。来自迁移的那 12 个现在各有 watcher，并且端到端验证了其中一个会触发。

---

## 1. TAC 丢失了什么，按语言

### 1.1 测量方式

每个构造都用同一种方式测量：让前端跑一个用到了该构造的文件，然后问三个问题。

1. IR 里有没有对应它的指令？
2. 有没有一条 **problem** 随 IR 一起走，说明它没被降解？
3. 函数的 precision 是不是被降级了？

整个系统遵守的规则是：

> 没能降解的构造必须产生一条 problem 和一次 precision 降级。绝不允许静默丢弃。

**静默**缺口是最糟的一种，因为它和"代码是干净的"无法区分。这方面的覆盖契约见
[`TAC-COVERAGE.md`](TAC-COVERAGE.md)。

### 1.2 Java

Java 是三者中唯一有**强制穷尽性不变式**的：节点清单测试会枚举 jar 里每一个 JavaParser 节点类，
只要有一个没被匹配上就失败。这就是 Java 静默缺口数最少的原因 —— 但也不是零。

| 构造 | 有报告？ | 缺口的影响 |
|---|---|---|
| `case X when <guard>`（Java 21） | **静默** | guard 判定完全不在 IR 里：分支只凭类型判定就命中。产生的是**误报**，不是漏报。 |
| 枚举常量体覆写方法 | **静默** | 覆写不在程序里，所以调用解析到基类方法。 |

Java 剩下的这两个缺口产生的是**错误**的 finding，而不是**缺失**的 finding。这就是它们排在
Java 待办最前面而不是最后面的原因。

### 1.3 Kotlin

Kotlin 有 7 个已知缺口，**7 个全部有报告**。前端的主要贡献是**可空性**：platform type 被记录为
可空，`!!` 被记录为一次断言。这是 Kotlin 类型系统知道、而 TAC 这套语言中立 IR 不知道的东西。

| 构造 | 有报告？ | 缺口的影响 |
|---|---|---|
| `when` 的 `is`-模式分支 | 有报告 | 该分支无法降解；函数被降级。 |
| companion object 的成员 | 有报告 | 这些成员不在程序里。 |
| 默认参数 | 有报告 | 只建模了声明的参数表。 |
| 可变参数 | 有报告 | 只建模了第一个参数。 |
| `inline` / `reified` | 有报告 | inline 体没有内联进调用方。 |
| `suspend` 函数 | 有报告 | continuation 没有被建模。 |
| 扩展函数的 receiver | 有报告 | receiver 变成 `null`，于是对它的调用是误报。 |

### 1.4 Scala —— 库层面的缺口

Scala 的缺口列表最大，而且它是最容易摧毁污点流的那个语言。11 个构造级缺口全部有报告。但
**库层面**的缺口严重得多，而且在被测量之前完全不可见。

**测量方式。** 一个探针让 Scala 前端跑过 84 个标准库 String 操作，对每一个记录：产生了哪些
指令，以及报告了什么 problem。结果：

| 结果 | 之前 | 之后 |
|---|---|---|
| 降解成有名字的 CALL | 44 | 82 |
| 被丢弃，并报告为不支持 | 31 | 0 |
| 被丢弃，且**完全静默** —— 没有任何 problem | 9 | 0 |
| 产生了 CALL，但名字是 `<unknown>` | 9 | 0 |

**为什么这些操作被丢弃。** Scala 的 `s.trim` 不是 `Apply` —— 没有参数可 apply —— 所以 dotc
把它表示成 `Select`。前端把每一种方法选择都当成"字段读"或者"无法降解的方法引用当值用"，而
作用在值上的无参调用落进了第二种。三件事同时出错：

1. 调用**没有产生指令**，于是这个操作对污点的影响是未知的。
2. 它被报告成不支持的构造，而且**理由是错的** —— `s.trim` 是调用，不是引用 —— 于是一个普通
   文件被标记成降级。
3. 这个操作永远到不了任何规则：没有 callee key，就没有任何 sink pattern 能匹配，也没有任何
   semantics 表条目能生效。

同一个探针还查出另外两个缺陷，并在同一轮修掉：

- **一个字符字面量会中止整个函数。** Scala 的 `Char` 被装箱成 `java.lang.Character`，它不是
  `java.lang.Number`；字面量降解把它 cast 成 `Number`，cast 抛异常，异常一路传出去，杀掉了
  后面所有指令。一个只含一个 `'x'` 的文件会丢掉函数体剩下的部分。
- **带默认参数的调用**（`s.sortBy(f)`，而 `sortBy` 还带一个隐式 `Ordering`）是一个 `Apply`，
  它的函数又是另一个 `Apply`。这层包装把方法名藏了起来，于是发出的 key 是
  `scala.collection.StringOps.<unknown>/1` —— 一个匹配不到任何 sink pattern、也匹配不到任何
  semantics 表的 key。

**实测出来的 owner。** 修复需要知道前端实际发出的是什么，而答案和源码文本暗示的不一样。
Scala 的 `String` *就是* `java.lang.String`：

| 源码 | 发出的 callee key |
|---|---|
| `s.trim` | `java.lang.String.trim/0` |
| `s.substring(1)` | `java.lang.String.substring/1` |
| `s.take(2)` | `scala.collection.StringOps.take/1`（前面有 `scala.Predef$.augmentString/1`） |
| `s"text=$x"` | `scala.StringContext.s/1` |

只照源码文本写的 semantics 表，命名的 owner 根本不会出现在 IR 里，每一条都是死的。

### 1.5 丢失到底造成了什么代价

| 缺口类别 | 代价 | 是漏报还是误报？ |
|---|---|---|
| 一个被丢弃的操作 | 污点流在该操作处断掉 | 漏报 |
| 一个被丢弃的 guard（Java 21 `when`） | 分支在不该命中时命中 | 误报 |
| 一个缺失的 receiver（Kotlin 扩展） | 对 `null` 的调用 | 误报 |
| 一个被丢弃的捕获（Scala，现已修复） | 污点在捕获处断掉 | 漏报 |

这个区分很重要，因为两个方向的修法相反。漏报需要把构造降解下来；误报需要把构造**收窄**。
一个什么都激进降解的工具，是用第一类错误换第二类错误。

---

## 2. 两个参考实现怎么做，以及借用了什么

### 2.1 它们对同一个问题的做法

两者解决语言丢失问题的方式相同，也正是 Plumb 现在采用的方式：**语言前端降解到语言中立的 IR，
语言特有的知识放在数据里，而不是放在分析引擎里。**

| | 它们的做法 | Plumb 的做法 |
|---|---|---|
| 前端产出 | 语言中立 IR（一个是镜像 AST，一个是 MIR） | 统一的 TAC |
| sink 知识 | 按 **mangled 签名**索引的 pattern 目录 | `sink-families.yaml`，按 `owner.method/arity` 索引 |
| 每语言差异 | 同一个 family 内的每语言 pattern 表 | 同一个 family 内的 `by_language:` |
| 语言特有语义 | 原语库：具名的 source/sink/effect 原语 | `LanguageSemanticsRegistry`：按语言的 callee 行为 |

**塑造了 Plumb 设计的那个观察。** 两个目录都按 *mangled 签名*索引 sink ——
`java.lang.String.trim()Ljava/lang/String;`，或者 Kotlin 函数的 mangled 形态。这和 Plumb 做的
决定是同一个：sink 由**解析后的签名**标识，而不是由 receiver 的语法类型标识。这正是 Scala
调用 Java 方法能到达和 Java 调用相同的 family 的原因。

**两者都没做的事。** 两个目录都按 *mangled* 签名索引，而那是某一个编译器 mangling 方案专用的。
Plumb 按 `owner.method/arity` 索引 —— 同样的信息，但不依赖编译器的私有编码。这个选择的代价是
真实存在的，并且在 [`ENGINE-GAP-ANALYSIS.md`](ENGINE-GAP-ANALYSIS.md) 里写明了：只在参数类型
上不同的重载无法区分。

### 2.2 借用的思路

从两个实现的 DFA 里评估了 6 条思路，与 Plumb 的引擎对照。四条已经实现，两条没有。

| 思路 | Plumb 的状态 | 位置 |
|---|---|---|
| **语言中立 IR + 每语言 pattern 表** | 已实现 | `sink-families.yaml` 的 `by_language:`；`LanguageSemanticsRegistry` |
| **sink 由解析后的签名标识，而非语法类型** | 已实现 | `owner.method/arity`；Scala 与 Kotlin 的 owner 解析修复 |
| **共享的、语言无关的原语词汇** | 已实现 | `Operation`（summary 侧代数）+ `LanguageSemantics`（callee 行为） |
| **显式的"未知"／黑盒语义** | 已实现 | `TaintBehaviour.UNKNOWN` 与 `BLACK_BOX`；`Operation.HasUnmodelledBehavior`；`Operation.UnmodelledCall` |
| **每个 checker 都能看见分析预算** | 已实现 | `AnalysisBudget`；`AnalysisCompleteness`；SARIF 的 `executionSuccessful` 标志 |
| **调用字符串上下文敏感（k-CFA）** | **部分实现** | 调用方把被调方的摘要按调用点的参数绑定投影一遍，键是一个 1-CFA 调用串。`context_depth: 0` 仍然关闭跨函数折叠；正值不是有界的 k-调用串前缀。见 [`ENGINE-GAP-ANALYSIS.md` §2.2a](ENGINE-GAP-ANALYSIS.md)。 |

第六条要特别小心。配置里的 `context_depth: 2` **不等于** 2-CFA；它的意思是"保持跨函数边界
折叠"。把它写成 k-CFA 就是一次虚假承诺，而这正是
[`ENGINE-GAP-ANALYSIS.md`](ENGINE-GAP-ANALYSIS.md) 要终结的那种失败模式。

**此后发生的变化。** summary 缓存现在有了第二层、按调用串做键的条目，而调用方一侧是上下文
敏感的：被调方自己的事实仍然上下文无关、只算一次，而摘要按它被到达的那个调用点的绑定投影。
于是同一被调方的两个调用点不再共用一份答案。那是 1-CFA，而且它不等于 `context_depth` 这个
设置变成了 k-CFA —— 该设置仍然区分「不做跨函数分析」和「有跨函数分析」，它自己也是这么说的。

### 2.3 值得点名的一个结构差异

两个参考实现的原语库用一套**词汇**来命名知识：具名的 source、具名的 sink、具名的 effect。
Plumb 的等价物拆成了两半 —— `Operation` 描述一个*函数*做了什么（对参数、对返回值），
`LanguageSemantics` 描述一次*库调用*对污点做了什么。

这个拆分是刻意的，也正是 Scala 那个缺陷能被查出来的原因。一套原语词汇会把"一次剥掉空白的
调用"当成一件事命名；Plumb 必须说"一次对 `java.lang.String.trim` 的调用保留污点"，而这是
关于一个**解析后签名**的事实 —— 这是一个前端**可能弄错**的事实。而它确实弄错了，40 个操作
弄错了，直到探针把它测出来。

---

## 3. 目录差距：没人看的那些 family

### 3.1 差距是怎么查出来的

目录的广度从来不是问题 —— 它已经测过，而且很大。问题是**可达性**：哪些 family 真的能产生
finding？

方法是机械的：

1. 取 `sink-families.yaml` 里的每个 family key（81 个）。
2. 取每个专用 checker 命名的 family（62 个），加上每个**源** family —— `SourceFamilyMatcher`
   用目录自己的 pattern 读它们（11 个）。合起来就是被看着的那一组（62 个）。
3. 差集（19 个）就是不可达集合。
4. 确认它：在目录之外，对这 19 个 key 各自全树搜索。

第 4 步对全部 19 个都返回空。它们全是**死配置**：真实的 pattern、真实的 CWE，以及一条都触发
不了的路。

### 3.2 来自规则包迁移的那部分

在补上那十二个 checker 之前不可达的那些 family 里，**12 个来自规则包迁移**。它们的 pattern 是从真实规则包转换来的，所以符号是真的、CWE
也是真的 —— 但每个 family 该有的 checker 从来没被写出来。迁移搬走了 *pattern*，忘了 *rule*。

| Family | CWE | Watcher（现在） |
|---|---|---|
| `code-injection.sinks` | 94/95/494 | `CodeInjectionChecker` |
| `cookie.sinks` | 113 | `CookieInjectionChecker` |
| `credential.sinks` | 256/798 | `CredentialExposureChecker` |
| `dos.sinks` | 730/776 | `DenialOfServiceChecker` |
| `formula-injection.sinks` | 1236 | `FormulaInjectionChecker` |
| `header-injection.sinks` | 93/113 | `HeaderInjectionChecker` |
| `insecure-comms.sinks` | 297/319/326/327 | `InsecureCommunicationChecker` |
| `insecure-random.sinks` | 335/330 | `InsecureRandomFamilyChecker` |
| `log-injection.sinks` | 117 | `LogInjectionFamilyChecker` |
| `privacy.sinks` | 215/359/489/497 | `PrivacyViolationChecker` |
| `resource-injection.sinks` | 94/95/99/502 | `ResourceInjectionChecker` |
| `setting-manipulation.sinks` | 15 | `SettingManipulationChecker` |

**剩下的这些不是迁移欠债。** 它们在迁移之前就已经是死的，而且属于另一种工作：其中几个命名了
已经不存在的 API（`atg.*`、`flex.*`、`oracle.jbo.*` —— 一些在这里分析的代码的 classpath 上根本
没有的厂商产品），另外几个和已经有 watcher 的 family 重叠。它们列在 §3.4。

### 3.3 验证

可达性现在是**测试**，不再是声明：

- `SinkFamilyReachabilityTest` 断言这 12 个 family 各有 watcher、它们的 pattern 仍然匹配它们
  命名的 API、它们不和已经有 checker 的 family 冲突、并且它们的 checker 在 `checkers.yaml` 里
  注册过。
- 测试里的 API 是用注册表本身**实测**出来的，不是从 pattern 文本里读的：pattern 的文本和它实际
  匹配的 API 是两回事。
- 端到端：一个含 `engine.eval(userInput)` 的夹具现在会产出
  `Tainted value flows to code-injection.sinks (CWE-94,CWE-95,CWE-494) at userInput via
  ScriptEngine.eval/1`。没有 watcher 的 family 根本不可能触发，无论它的 pattern 写得多全。

### 3.4 还开着的

| 项目 | 状态 | 说明 |
|---|---|---|
| 19 个没有 watcher 的 family | **开着** | 早于迁移。其中九个和已有 watcher 的 family 重叠；其余命名的是这里分析的项目并不依赖的库。每个都需要一个决定：写 watcher、合并进已有 watcher 的 family，或者删掉。 |
| `context_depth` 作为真正的 k-CFA | **开着** | 设置已被消费（0 关闭折叠），但它不是 k-CFA。调用串投影是 1-CFA，是另一套机制；见 §2.2。 |
| 路径敏感可达性 | **部分** | 算术矛盾判定已有；通用的路径条件还没有。 |
| 对象敏感分析 | **已完成** | points-to 传播现在既有生产者也有消费者：引擎把被调方的分配点挂到摘要上，调用方的接收者继承它们。同一类的两个实例可以区分开了。 |
| `TaintBehaviour.PRODUCES_TAINT` / `BLACK_BOX` | **开着** | 两个常量都存在，但没有任何表填它们。目前这两种行为的调用与没有条目的调用无法区分。 |
| Scala：可变参数、try/catch、by-name 参数、隐式、tuple 解构、match guard | **开着** | 全部有报告。见 [`TAC-COVERAGE.md`](TAC-COVERAGE.md)。 |

**语言语义表现在覆盖四种语言，不是两种。** 注册表注册了 Java、Kotlin、Scala 和 C#
（`LanguageSemanticsRegistry.java:79-82`），而且没有注册表的语言拿到的是一张空表而不是 null ——
所以这四种语言之间的差别是表的大小不同，而不是「这个语言到底被理解没有」不同。

### 3.5 那 19 个没有 watcher 的 family，分类之后

这个数字可以精确复现：目录里有 81 个 family key，被 checker 或规则点名 62 个（其中 11 个是源 family，由 \SourceFamilyMatcher\ 读），没 watcher 的
19 个，其中没有一个是源 family —— 十一个源 family 由 `SourceFamilyMatcher` 看着（§3.6）。

**是什么改变了这次度量。** 这个数字以前是 17，而它错在**把洞藏起来**的方向上。门禁把
「族名在 main 的 Java 源码里作为字符串字面量出现过」当作「有人看着」，而
`TaintRuleRegistry` 点名了三个族（`ssrf.sinks`、`xxe.sinks`、`weak-crypto.sinks`）——
**引擎从不读那张表**。只有那张表点名的族，事实上就是没人看。现在门禁只数真正会跑的 checker
和源匹配器。

**SSRF 这一类现在有 checker 了。** `ssrf.sinks` 带着上百条 pattern —— 每一条是一个让服务端
对外发请求的 API —— 却没有任何 checker 点名它，于是污点 URL 到了任何一条那里都不报。
`SsrfChecker` 现在看着它。同时这个 family 也被整理过：往流里写是日志写，带格式的写是格式
串，Kotlin 读文件给的是路径，URL 变成进程是命令，交给解析器的 XML 文档是 XXE，Netscape
那套 LDAP 调用说的是目录服务。它们过去都被报成 SSRF —— 那是错的编号 —— 现在各归各家。

要紧的是它们**不是同一种东西**，而之前把它们描述成「几个命名了已不存在的 API」在要紧的
地方是错的：它提到的那些 API 名（`atg.*`、`flex.*`、`oracle.jbo.*`）在本仓库里**哪里都不存在**
——目录里没有，源码里也没有。它们只存在于提到它们的那份文档里。

| 类别 | 数量 | 是什么 |
|---|---:|---|
| 和已有 watcher 的 family 重叠 | 8 | `format.sinks` 与 `format-string.sinks` 重复；`cookie-write.sinks` 与 `cookie.sinks` 重复；`http-header-write.sinks` 与 `header.sinks` 重复；`redirect.sinks` 与 `open-redirect.sinks` 重复；`sensitive-log.sinks` 和 `db-credential.sinks` 与 `credential.sinks` 重复；`predictable-seed.sinks` 与 `insecure-random.sinks` 重复；`sensitive-url.sinks` 与 `ssrf.sinks` 重复。 |
| 真实且独立的缺陷类别 | 5 | `cors.sinks`（CWE-942）、`unencrypted-credential.sinks`（CWE-311）与 `oauth-credential.sinks`（CWE-798）—— 同样的 API，但 **CWE 不同**，所以彼此不是重复；`registry.sinks`（CWE-15）；`android-clipboard.sinks`（CWE-200）。 |
| 还差一个 checker 的缺陷类别 | 2 | `xxe.sinks`（CWE-611）与 `weak-crypto.sinks`（CWE-261/326）：整类都进了目录，却没有任何 checker 看着它。 |
| 命名了这里没有依赖的库 | 4 | `android-implicit-intent.sinks`、`console.sinks`、`generic.sinks`、`kms-plaintext.sinks` —— `android.*`、`org.jclouds.*`、`software.amazon.awssdk.*`：在任何被跟踪的 `pom.xml` 里都不出现。 |

**为什么「源 family」这一类才是要紧的。** 一个因为与已有 family 重叠而没有 watcher 的 family
并不损失什么：那个 watcher 会开火。一个因为库不存在而没有 watcher 的 family 也不损失什么：
没有分析到的项目会有那个库。但一个**源**family 没有 watcher 是损失了能力的，因为目录说
「这个 API 是污点源」，而引擎不相信。目录和引擎互相矛盾，而跑起来的是引擎。§3.6 就是把这个
矛盾修掉。

### 3.6 目录里的污点源 family 现在被相信了

**矛盾的形状。** `sink-families.yaml` 声明了 11 个污点源 family，每个都用锚定的 pattern 点名
那个把不可信数据带进来的 API。而引擎判断「什么算输入源」用的是**另一份硬编码子串清单**
（`InputSourcePatterns`），目录从未被问到。于是目录声明的源，引擎看不见。

**代价是什么。** 一个引擎不认识的已声明源是**漏报**：值从一个不可信的地方读出来，引擎却把它
当干净，下游每一个 sink 都瞎了。对着目录实测，有 8 个源是不可见的：

| Callee | Family |
|---|---|
| `java.lang.System.console` | `taint.sources.console` |
| `java.util.Scanner.next` | `taint.sources.io` |
| `java.net.URL.getHost` | `ip-address.sources` |
| `java.net.InetAddress.getByName` | `dns.sources` |
| `java.security.Principal.getName` | `principal.sources` |
| `javax.servlet.request.getUserPrincipal` | `principal.sources` |
| `org.springframework.http.HttpHeaders.getFirst` | `taint.sources.web` |
| `io.undertow.server.HttpServerExchange.getQueryParameters` | `taint.sources.web` |

**为什么答案是并集而不是替换。** 目录的 pattern 是**锚定**的，所以它不匹配
`Integer.parseInt` —— 而一个解析出来的数字仍然受攻击者影响，数值类 sink（CWE-789 / CWE-190 /
CWE-191）就靠它。子串清单**没有 owner**，所以它会匹配 `com.example.MyReader.read`。两份清单
互不包含：用目录替换子串清单，会把 8 个漏报换成另一组漏报；用子串清单替换目录，正是最初让
已声明的源不可见的原因。

**决定方向的不对称。** 引擎**漏掉**的源是漏报；引擎**多认**的源是误报。对一个扫描器来说，
这个不对称偏向并集：它花掉精度，换来可靠性。所以引擎两份清单都问，答案是它们的并集。

**什么才算一个源 family。** 没有 CWE 的 family。目录靠 `cwe:` 键的**缺失**来区分源和 sink，
而这也是唯一可用的区分方式——YAML 里没有角色标记。`SourceFamilyMatcher` 读的就是这个区分，
所以往目录里加一个源 family 就足以让引擎相信它，不需要改代码。

**为什么匹配是语言感知的。** 一个有语言限制的 family 不该为别的语言的调用背锅：
`kotlin.io.readLine` 是 Kotlin 的 API，而一个含有那个字符串的 Java 文件并没有在读 Kotlin 的
标准输入。注册表本来就能回答「这个 family 适不适用于这个语言」，所以这个问题在那里问，
而不是在这里重问一遍。

---

## 4. Scala 的 semantics 表

Scala 现在有一张 80 条的 semantics 表。没有它的话，每个 Scala callee 都会是 `UNKNOWN`，
让每一次 Scala 调用都保留污点 —— 包括 `s.length` 和 `s.hashCode`，而它们的全部意义就是
*去掉*污点。一个在 `length` 上触发的规则集就不可用了。

这张表里的每个 owner 都是对着前端真实输出实测出来的。这些条目正是引擎正确性所依赖
的：把污点带下去的（`trim`、`substring`、`concat`、`split`、`take`、`drop`、`stripPrefix`）
和把污点去掉的（`length`、`hashCode`、`isEmpty`、`contains`、`startsWith`、`endsWith`、
`indexOf`、`compareTo`、`matches`、`getBytes`、`toCharArray`、各种数值解析）。

有一个坑值得点名，因为它会产生一张*看起来*对、却什么也不改变的表：**Scala 3 把这些操作发射
成静态扩展方法**（`toInt$extension`），但前端把它们解析成编译器最终调用的那个实例方法，所以
**发出的 key 不带 `$extension`**。照着库自己的方法名写的表会一条都匹配不到。
`LanguageSemanticsTest.scalaTableNamesTheResolvedOwner` 守的就是这一点。

---

## 5. 让这件事保持诚实的两条规则

两条规则，都是从本文档记录的缺陷里学到的：

1. **没有 watcher 的 family 是死配置。** 目录不是能力；能力是 family **加上**一个看着它的
   checker。覆盖率报告必须数**可达的** family，而不是配置里的。

2. **表里的条目必须对着 IR 实测，不能照源码文本读。** Scala 的 owner 表就是活例子：它最初 80
   条里有大部分是死的，因为解析后的 owner 不是源码暗示的那个。

两条现在都是测试而不是约定 —— 只有测试能活过一年。
