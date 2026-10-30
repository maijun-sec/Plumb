# Juliet 测量：它测的是什么，以及它不测什么

[English](../../tech/JULIET-MEASUREMENT.md) | [中文](../README.md)

**日期：** 2026-10-30
**范围：** `testcase/` 下的七个 Juliet 派生类别。

本文记录这套测量**实际**测量的是什么。之所以写这份文档，是因为测量方法在本次工作期间被改动过，
而一个脱离了产生方法的数字，谁都无法信任。

---

## 1. 改了什么，为什么

### 1.1 规则集在源头收窄（第 3 点）

**改之前：** 扫描会跑引擎拥有的全部规则。测量随后在匹配器里决定哪些告警算数。

**改之后：** 扫描只跑清单里各条用例点名的那几条规则，别的一律不跑。收窄由 `--rule` 完成，
所以收窄发生在引擎里。

**为什么：** 一条**本就不该被考虑**的告警，是一条无法归咎于被测规则的告警。当整个规则集都躺在
输出里时，匹配器里的每一个错误都变成了关于**某条**规则的错误，而报告无法指出是哪一条。

**实测影响：** 基线告警数从七个类别合计 **3839 条**降到 **120 条**。分数没有变——TP 与 TN 覆盖率
完全相同——这正是收窄去掉的是噪音、而不是检测能力的证据。

### 1.2 匹配是精确的：规则 id **与** CWE（第 4 点）

**改之前：** 匹配器把 `rule_ids` 与 `cwes` 当成二选一。点名 `rule_ids` 的用例忽略 CWE；
只点名 `cwes` 的用例忽略规则。

**改之后：** 用例点名规则 id 和/或 CWE，告警必须**同时**满足两者。只点名一项时，告警必须满足
那一项。

**为什么：** 这正是「cwe89 的用例，扫描出来一个 ldap 注入的告警」那个错误。一条被 LDAP 注入告警
满足的 CWE-89 用例，会抬高召回、掩盖误报，并声称检测到了一个从未寻找的缺陷类别。

### 1.3 调用图是 global 的（第 2 点）

测量脚本传 `--cg-strategy global`，而引擎的默认值也是 `global`（跨 TU 签名查找）。这一点本来
就成立；现在它被明确写下来，并作为 `cg_strategy` 记进每个类别的 `RESULT.json`，这样任何数字
都无法脱离产生它的策略被单独阅读。

**但请看第 3 节。** global 调用图是必要条件，不是充分条件：跨文件类别目前**根本没有**在检验它。

---

## 2. 规则 → CWE 映射来自活引擎

按 CWE 出表需要一份规则 → CWE 映射。这份映射**不是**维护在引擎旁边的表：它是从引擎本身读
出来的，靠把引擎跑起来：

```
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar --list-rule-cwes
```

映射由 `ScanRunner.liveRuleCweMap()` 构建，取自扫描本身用的那两份清单：AST 检查器
（`buildStyleChecks()`）与 DFA 检查器（`buildDfaCheckers()`）。

构建过程中发现并修复了三个缺陷：

1. **映射的键用错了名字。** `RuleId` 有两个名字：`id()` 是发射出的 id
   （`ast-security-unchecked-return`），`name()` 是旧注册表名（`UNCHECKED_RETURN`）。
   映射用的是 `name()`，于是它**一条**报告里真正打印出来的 id 都不含。映射现在以 `id()` 为键。

2. **DFA 检查器整体缺失。** `TaintRuleRegistry.builtins()` 装的是*通用*污点规则，不是每个检查器
   自己的——每个 DFA 检查器都内联构造自己的 `TaintRule`。映射现在读检查器实例。

3. **多规则检查器只报一条规则。** `AstChecker.rule()` 返回一条规则，但仅
   `EmptyStatementBlockCheck` 一个就发射三条，各有各的 id 和 CWE。`AstChecker` 增加了
   `allRules()`，四个多规则检查器覆盖了它。

**结果：** 引擎发射的 155 个规则 id 全部在映射里。其中 84 个不带 CWE——那些是风格与 lint 规则，
它们本来就没有 CWE。

---

## 3. 跨文件类别目前并没有在测跨文件分析

**这是本文最重要的一条发现，而且是引擎的限制，不是测量的限制。**

类别 `01-taint-crossfile` 声称在测一条「跨 6 个文件、9 跳」的污点链。这条链是真的，构造得也
很好：

```
SourceLayer.readRequestParam   (文件 1)
  -> SourceLayer.wrap           (文件 1，字段存储)
  -> Payload.readBack           (文件 3，字段读取)
  -> ...
  -> TextOps.build              (文件 5，StringBuilder)
  -> SqlSink.queryByName        (文件 8，汇点)
```

### 3.1 实测

| 扫描 | 告警数 | dataflow |
|---|---|---|
| **单独**扫 `SqlSink.java` | 3 | `taint: param:userName -> CWE-89` |
| 扫**整个目录**，`global` 调用图 | 3 | `taint: param:userName -> CWE-89` |
| 扫**整个目录**，`global` 调用图，看完整轨迹 | 4 步 | `source \| SqlSink.java:-1:-1 \| param:userName` |

轨迹只有 **4 步**，而 source 步落在 `SqlSink.java:-1:-1`——**汇点文件自己**。单独扫文件与扫整个
目录，结果**逐字节相同**。跨文件的那条链对这条告警没有任何贡献。

### 3.2 原因

`TaintedCheckerBase.beginFunction` 把**每一个方法参数**都种成污点源：

```java
markSource(p, "param:" + p.name(), null, fn.signature(), "param:" + p.name());
```

`SqlSink.queryByName(Connection conn, String userName)` 有个 `String userName` 参数，于是汇点
是**靠它自己的参数**被报出来的。这是一个有意为之的保守选择——方法参数可能是攻击者可控的——
但这意味着这条告警并不依赖那条链。

### 3.3 链上真正的源头没有被识别

这条链真正的源头是 `SourceLayer.readRequestParam` → `HttpRequestSupport.getParameter`，
fixture 自己把后者注释为「`HttpServletRequest#getParameter` 的**替身**」。它是个*替身*：返回
`"req:" + name`，从不调用真正的 servlet API。

引擎的 source 模式要求接收者以 `HttpServletRequest` 结尾：

```yaml
- '^((?:[\w.]+\.)*)HttpServletRequest\.getParameter(/\d+)?'
```

`HttpRequestSupport` 匹配不上。于是链的源头不是 source，跨文件传播也就没有东西可传。

### 3.4 判别实验，已实测

一个探针 fixture（`_audit-scratch/probe_crossfile_chain.py`）复现这条链，但把值通过**字段**
而不是参数送到汇点，于是汇点方法**没有任何可污染的参数**：

**结果：0 条告警。** 到那个汇点的唯一通路就是这条链，而这条链什么都没送来。

### 3.5 这对数字意味着什么

`01-taint-crossfile` 的 TP 用例测的是**参数污点抵达汇点**，不是跨文件传播。它们是对真实缺陷的
真实检测，价值就是它们测量的那个价值——但它们**不是**类别名称和清单注释所声称的东西。

其他污点类别的情况好一些：

| 类别 | 观察到的 source 标签 | 识别到源头了吗 |
|---|---|---|
| `01-taint-crossfile` | 只有 `param:*` | **没有** |
| `04-polymorphic-callgraph` | `input:RequestLike.getParameter/1`、`param:*` | 部分 |
| `05-real-cve-derived` | `input:Request.getParameter/1`、`sensitive:Request.getHeader/1` | **有** |
| `06-framework-orms` | `param:customer@spring-mvc`、`param:params@carrier` | **有** |

类别 05 与 06 确实在检验框架 source 识别，它们的轨迹带的是被识别出来的 source 标签，而不是裸的
`param:`。

### 3.6 怎么把这个缺口补上

缺的是两件互相独立的事：

1. **fixture 的替身不是 source。** 要么让 fixture 调用真正的
   `javax.servlet.http.HttpServletRequest.getParameter`（需要 servlet API 在 classpath 上），
   要么让引擎认识用户自定义的替身。前者是改 fixture，后者是加能力。

2. **一条源头被识别的跨文件链，并没有抵达汇点。** 即便源头被识别了，传播还得扛过字段存储、字段
   读取、跨文件返回、容器存取，以及那个长得像消毒器的 helper。它扛不扛得住，是另一项测量——
   而且**那才是这个类别名字所指的那项测量**。

**建议：** 要么把类别注释改成它真正测的东西，要么改 fixture，让那条链成为这条告警**唯一**可能的
解释。一个名字声称在测跨文件污点、实际却在测参数污点的类别，比没有这个类别更糟——因为那个数字
读起来像是引擎具备了它其实不具备的能力。

---

## 4. 同一片区域里发现的第二个缺陷：一个规则 id 选中了两条规则

`--rule dfa-taint-null-deref-from-return` 选中了**两条**规则：

| 规则 | CWE |
|---|---|
| `dfa-taint-null-deref-from-return` | CWE-476 |
| `ast-style-null-deref-from-return` | CWE-690 |

**成因。** 过滤器接受规则 id 的若干种写法，其中包括剥掉前导引擎与域 token 之后的写法。有**十二
对**活规则共享同一种剥壳写法，其中**八对**的 CWE 还不同：

```
catch-nullpointer-exception     ast-error-…  对  ast-style-…
empty-catch-block               ast-error-…  对  dfa-style-…
jumbled-incrementer             ast-bug-…     对  ast-performance-…
literal-boolean-condition       ast-style-…  对  dfa-taint-…
null-deref-from-return          ast-style-…  对  dfa-taint-…
unchecked-loop-condition        ast-style-…  对  dfa-taint-…
unused-private-field            ast-style-…  对  dfa-taint-…
explicit-finalize               ast-error-…  对  ast-style-…
```

AST 检查器自己的 `name()` **就是**那个裸 slug（`null-deref-from-return`），所以条目的剥壳写法
匹配上了它。

**对测量的影响。** 一条 CWE-476 的 TN 用例被判成误报，因为 CWE-690 的规则在同一行上开火了——
一个并不存在的误报，以及一个测的是过滤器而不是规则的召回数字。

**修法。** 剥壳写法只有在**不歧义**时才选中规则。歧义的裸 slug 什么都不选，用户必须点名他想要
的规则。完整 id 永远精确选中它所指的那一条规则。活的规则集从引擎读，所以这个判定不会漂移。

`scripts/mutate_rule_filter_ambiguity.py` 杀掉全部三个适用变异；第四个按 NO-OP 报告并附原因
（见脚本内说明）。

---

## 5. AI 降误报

AI 降误报**尚未**针对修正后的测量重跑。七个类别里有五个存在更早一次构建留下的 AI 运行结果
（`_audit-scratch/ai-run/`）；类别 03 与 04 是被中止的，没有留下任何告警。

**那些更早的运行结果与新基线不可比**，有三个原因：

1. 它们来自**没有**规则过滤器修复的构建，所以告警里带着 `--rule` 过去会放行的额外规则。
2. 它们来自**没有**精确匹配修复的构建，所以一条用例可能被另一条 CWE 的告警满足。
3. 它们来自**跑全部规则**的扫描，所以告警集合大得多。

`_audit-scratch/summarise_ai_runs.py` 记录了现存的东西，并把中止的运行标为中止。被中止的运行什么
都没测；它不是「AI 没找到误报」的那次运行。

---

## 6. 复现方式

```
# 构建
mvn -o -pl plumb-main -am -DskipTests -B install

# 逐类别跑基线，规则已收窄、匹配已精确
python testcase/run_category.py testcase/<category> --per-file

# 按 CWE 出报告
python scripts/report_juliet_cwe.py --baseline _audit-scratch/baseline

# 报告与测量脚本逐条用例一致
python scripts/check_juliet_report.py
```
