# Plumb 中的 TypeScript 与 JavaScript：sidecar 路线、keyed 命名空间，以及 TAC 如何生成

[English](../../tech/TYPESCRIPT-FRONTEND-ANALYSIS.md) | [中文](../README.md)

本文按顺序回答四个问题：

1. **一个跑在 JVM 上的引擎，到底能不能分析 TypeScript？** 这个答案决定路线，而且它不是一个
   偏好问题。
2. **TypeScript 会把 TAC 的什么打破？** 有一件事会，而且它正是每种动态语言都会遇到的那件：
   对象是命名空间，不是记录。
3. **引擎需要什么，才能读懂一个命名空间？** 访问路径本来就带有 map 形式。缺的不是机制，而是一个
   决定：哪些语言使用它。
4. **一份 TypeScript 的 TAC 是怎么端到端产生的？**

路线与 C#、Go 前端已经在用的那条相同：一个独立进程讲一套带帧的 JSON 协议，由 JVM 读回来。
变化只在于这个 sidecar 是一个 **Node 程序**——因为 TypeScript 编译器不是 JVM 程序。

标注 **[MEASURED]** 的部分是从本仓库的代码里读出来的，或是对着本仓库实测的。描述编译器行为的
部分标注 **[COMPILER]**，它们是关于编译器自身行为的陈述，而不是关于 Plumb 的。

---

## 0. 先说结论

| 问题 | 答案 |
| --- | --- |
| JVM 能分析 TypeScript 吗？ | **不能直接分析。** 编译器是一个原生程序，有自己的 parser、binder 和类型检查器，而它报出的类型就是编译器自己看到的类型。在 JVM 里重新实现它们，等于写一份第二实现，而它会与真正的实现不一致——而一个解析方式不同的类型会产出不同的被调用者签名，被调用者签名正是 sink 目录匹配的东西。 |
| 路线是什么？ | **一个 Node sidecar**，驱动编译器自己的 API，用 C# 与 Go sidecar 已经在讲的那套长度前缀 JSON 协议，把同样一份 Java 序列化的 IR 喂给同一个引擎。 |
| TAC 的 IR 契约变了吗？ | **没变。** 没有新 opcode，没有新类型种类，被调用者键的契约也没变。属性访问变成一个带字符串键的容器访问——一个 `ARRAYLOAD` 或一个 `ARRAYSTORE`——而那是 TAC 本来就有的 opcode。 |
| 引擎需要什么？ | **一个决定，而不是一套新机制。** 访问路径本来就带着 map 形式；`SourceLanguage.hasKeyedContainers()` 说明哪些语言使用它。五个脚本语言用它，五个静态语言不用，所以既有 finding 不会移动。 |
| 真正的代价是什么？ | **sink 目录。** `sink-families.yaml` 有 67 个 family，规则层把其中一个桶限定到 TypeScript、JavaScript 与 ArkTS。前端是小的一半，目录是大的一半。 |

最后一行决定了 TypeScript 到底会不会产出 finding。前端已经存在并且忠实地降级这门语言，目录的脚
本语言桶也按模块点名了那些 Node API。两半的交接处是一个命名约定——模块级调用的 owner 是它被导
入自的那个模块——而**没有任何东西在检查这个一致性**：目录测试是手工构造 TAC 的，前端测试证明的
是前端发出了它被写成要发出的东西。两边不一致时，谁都不会发现。而症状是最糟的那种：不崩溃、不
告警，扫描报告程序是干净的。

---

## 1. JVM 够不到编译器

### 1.1 编译器是一个程序，不是一个库

**[COMPILER]** TypeScript 编译器不是一个 JVM 库。它是一个原生程序，而为它存在的那些 JavaScript
前端——打包器内嵌的那些、编辑器对话的那些——都是编译器自己的入口，而不是它的重新实现。

这正是 sidecar 成为路线而不是偏好的原因。Plumb 需要编译器给出三个事实：表达式的类型、调用解析
出来的签名、调用抵达的符号名。这三个都是编译器做出的决定，而它们每一个都会改变 sink 目录所
匹配的被调用者签名。对这些决定的第二份实现，会在常见情形上与编译器一致，在其余情形上不一致，
而不一致会以「少一个 finding」的形式出现，而不是以错误的形式出现。

### 1.2 路线：一个 Node sidecar

**[MEASURED]** 这个 sidecar 是 `plumb-frontend/ts-sidecar/` 下的一个 Node 程序，Java 侧用两个字
启动它——Node 运行时与脚本：

```javascript
import { API } from "typescript/unstable/sync";
```

进程处理与分帧与其他 sidecar 共用：见
[`SidecarProcess`](../../../plumb-frontend/src/main/java/org/plumb/tac/frontend/sidecar/SidecarProcess.java)。
这一族特有的地方是命令有两个元素而不是一个，这也是 `SidecarProcess.launch` 增加了一个
`List<String>` 重载的原因。

### 1.3 版本 7 的根导出里没有编译器 API

**[COMPILER]** 这个 sidecar 面向最新发布，而那个发布搬动了 API。包的根导出只带版本号，别无他物；
编译器的 API 要通过 `typescript/unstable/sync` 抵达，那是一个编译器**服务端**的客户端：

| 通道 | 它是什么 | Plumb 用它做什么 |
| --- | --- | --- |
| `typescript`（根导出） | 版本号，以及编译器二进制 | 没用。这里没有编译器 API。 |
| `typescript/unstable/sync` | 编译器服务端的同步客户端 | 打开工程，并向编译器询问类型。 |
| `typescript/unstable/ast` | 服务端返回的 AST 的判定函数 | 认出翻译器要走过的那些节点种类。 |

**[COMPILER]** 这个客户端自己会拉起服务端，这也正是工程要**只打开一次**并一直持有：每文件打开
一次会为每个文件重新解析一遍标准库，而标准库才是其中的大头。

---

## 2. TypeScript 会把 TAC 的什么打破

### 2.1 对象是命名空间，不是记录

这是唯一要紧的决定，而它与 Python、PHP 做的是同一个决定。

在有记录的语言里，`obj.userId` 命名的是一个**字段**：这个位置由对象和字段名共同确定，而类型系
统说明那里可以放什么。在 JavaScript 里对象是一个**命名空间**：`obj.userId` 与 `obj["userId"]` 是
同一个位置，`arr[0]` 与 `arr["0"]` 是同一个位置，而一个解析不出来的键可能是任意键。

所以属性访问**不是**字段访问。它是一个键为字符串的容器访问：

```
ARRAYLOAD  dest=t1, lhs1=req, lhs2=CONST(STRING, "userId")
```

不是 `FIELDLOAD`。字段命名的就是字段，而字段不是属性的含义。属性访问的身份是（对象，键）这一
对，而这一对正是引擎的 **keyed 命名空间**所承载的东西。

**[MEASURED]** IR 本来就有这套词汇。`TacAccessPath` 在 `Reference` 旁边带着 `MapKey` 与
`LazyMapKey`，而 `SourceLanguage.hasKeyedContainers()` 决定哪些语言读它。五个脚本语言用它；Java、
Kotlin、Scala、C# 与 Go 不用，所以它们的 finding 不会移动。

| 构造 | 降级为 | 为什么 |
| --- | --- | --- |
| `obj.name` | 带 `STRING` 键的 `ARRAYLOAD` | 属性是命名空间里的一项，不是字段。 |
| `obj.name = v` | 带 `STRING` 键的 `ARRAYSTORE` | 同一个位置，写入。 |
| `obj[key]` | 带计算键的 `ARRAYLOAD` | 这个键可能是任意键，而引擎就是这么说的。 |
| `new Thing(a)` | 带参数的 `NEW` | 构造器是一次带接收者的调用。 |
| `obj.method(a)` | 接收者在 `args[0]` 的 `CALL` | 见下文。 |

### 2.2 接收者槽位是保留的

**[MEASURED]** `args[0]` 永远是接收者。自由函数没有接收者对象——它是**导入来源模块**的函数——所以该
槽位放那个模块名的常量。真正的参数在 `args[1..]`，而 arity 数的是**声明参数**，不含接收者。

```
args[0] = 接收者槽位   （接收者，或 CONST(STRING, 函数来自的那个模块)）
args[1..] = 真正的参数
```

这一点值得大声说出来，因为一个产出 `args = [tainted]` 的 sidecar 会产出看起来完全合理的 TAC，
并且会匹配上 sink 目录——然后引擎读 `args[0]`，找到的是*接收者*，在那里没找到任何污点，于是什么
都不报。finding 不见了，而扫描报告成功。

### 2.3 owner 是函数来自的那个模块

**[MEASURED]** 被调用者签名是 `owner.method/arity`。owner 对方法来说是接收者的类型；对模块级函数
来说，它是**函数被导入自的那个模块**：

```
import { exec } from "child_process";
exec(cmd);                 // child_process.exec

import * as vm from "vm";
vm.runInNewContext(code);  // vm.runInNewContext
```

第二种形式和第一种同样重要。脚本语言是通过**模块**而不是类来触达库的：`child_process.exec` 是
*child_process 模块*的函数，没有类可以点名。改成点名文件会产出 `app.exec`，那说的是调用写在哪里，
而不是调用了什么——而且没有任何 sink 目录能匹配上它，因为目录不可能枚举每个程序的文件名。sink 目录
是照着模块名写的，所以前端必须产出模块名。

别名会被解析掉：`import { execFile as spawner }` 调用的是 `execFile`，所以目录要匹配的是导出时的
那个名字。

方法的 owner 是接收者的**类**，而类名来自编译器自己的答案：

```ts
class Repo {
  find(id: string): string { ... }
}
const r = new Repo();
r.find(id);                // Repo.find
```

**[MEASURED]** 类名是从编译器的符号上按**属性**读出来的。访问器 `getName()` 在原生预览里没有实
现，问它只会得到 `undefined`——所以一个类实例原本会退化成 `Object`，它的方法会退化成对**文件**
所属模块的调用。没有名字的符号就是没有名字的类。

没有模块可点的自由函数只有两个全局函数。`eval` 和 `fetch` 不是从任何地方导入的，所以它们的 owner
是**文件**所属的模块——目录只按方法名匹配它们。

**[MEASURED]** 在编译器能解析出来时，arity 数的是**声明参数**，而不是传进去的实参：一个带一个参数的
方法是 `UserStore.find/1`，接收者在 `args[0]`。经由 import 到达的函数是例外——编译器给它的解析后签名
带着一个空的参数表，所以 arity 算出来是 0。sink 目录把 arity 当作可选部分，名字照样匹配得上。

### 2.4 哪些没有建模，以及怎么报出来

**[MEASURED]** 翻译器降级不了的每一个构造，都会作为该函数上的一个问题报出来，受影响的值变成
`HAVOC`——一个未知的值。绝不静默省略。

| 构造 | 会发生什么 | 问题的种类 |
| --- | --- | --- |
| 一个 `break` 或一个 `continue` | 循环的结构承载了控制流 | `SOURCE_LOWERED` |
| 被当作值使用的函数 | 这个值变成一个 `HAVOC` | `SOURCE_LOWERED` |
| 一个类表达式 | 这个值变成一个 `HAVOC` | `UNHANDLED_NODE` |
| 被调用者解析不出来的调用 | 该调用发出为 `UNINTERPRETED` | `SOURCE_LOWERED` |
| 任何别的语句或表达式 | 这个值变成一个 `HAVOC` | `UNHANDLED_NODE` |

**[MEASURED]** 一个函数只要有至少一个问题就带 `SOURCE_LOWERED`，没有问题才带 `SOURCE_FULL`。
这个精度是对近似的记录，不是过滤器：引擎不会读它来决定报不报。

---

## 3. 是工程，不是文件

### 3.1 单独分析一个文件，类型就是未知的

**[COMPILER]** 一个 TypeScript 文件会命名它导入的那些模块，而编译器是对着工程解析这些名字的。
因此单独分析的文件在它导入某处的地方类型是未知的——而一个未知的类型就是一个未解析的被调用者，
那是**少一个 finding**，而不是少一点精度。

所以 sidecar 持有整个工程，并从它里面翻译。

### 3.2 工程的边界在哪里

**[MEASURED]** 当文件属于某个模块时，边界就是编译上下文的模块根——也就是这次扫描被要求分析的
那个根；否则就是最近的持有 `tsconfig.json` 的祖先目录，那是这门语言自己对「工程是什么」的说法。

包清单**刻意不是**工程标记。编译器不读它来决定工程包含什么，而清单在一个 home 目录里是很常见
的东西：一个向上走到清单的边界，会把整个 home 目录当成一个工程。

### 3.3 哪些被排除在外，为什么

**[MEASURED]** 发现过程排除的每一个文件，都是一个类型会保持未知的文件，所以每一次排除都是关于
分析能看到什么的决定。

| 被排除 | 为什么 |
| --- | --- |
| 一个 `.d.ts` 文件 | 它声明签名，不含可执行体；分析它会把 finding 报在声明上而不是代码上。 |
| `node_modules` | 依赖，不是工程自己的代码。 |
| `dist`、`build`、`out`、`coverage` | 构建产物与报告。 |
| 名字以点开头的目录 | 工具自己的状态。 |

**[MEASURED]** 源文件超过五千个的工程会被截短，而且截短是**被报出来的**：被排除在外那些文件的
类型可能是未知的，而那是 finding 的下界，不是答案。

---

## 4. TypeScript 的 TAC：五步

### 4.1 打开工程

**[MEASURED]** sidecar 把工程打开一次并一直持有。请求按绝对路径给出工程的���件，以及它们所在的根：

```json
{ "op": "openProject", "protocolVersion": 1, "projectId": "ts:C:/src/app",
  "sourceRoot": "C:/src/app", "sources": [ { "path": "C:/src/app/routes/user.ts" } ] }
```

**[MEASURED]** 编译器从磁盘读这些文件。它必须如此：它报出的类型就是这些文件此刻的类型，而一个
文本被调用方副本替换掉的文件会是**另一个程序**——编译器会按一个版本解析导入、按另一个版本算
位置。所以调用方交来的文本要与磁盘上的文本比对，而差异会被报出来。

### 4.2 翻译

**[MEASURED]** 一次一个文件，对着已经打开的工程。一棵目录树可能持有多个编译器工程——每个
`tsconfig.json` 一个——所以 sidecar 会向**所有**它们询问这个文件，并取持有它的那个。

### 4.3 降级

**[MEASURED]** 翻译器走编译器自己的 AST。它降级不了的每一个构造都会变成一个 `HAVOC` 和一个问
题；没有任何东西被丢掉。

### 4.4 讲线格式

**[MEASURED]** 分帧是每个 sidecar 都在用的那一套：四字节大端长度，然后是 UTF-8 的 JSON 载荷。
握手是对称的——不声明协议版本的请求会被拒绝，不声明版本的响应也会被 Java 侧拒绝。

| 操作 | 含义 |
| --- | --- |
| `openProject` | 创建或替换工程。 |
| `translate` | 把工程里的一个文件翻译成 TAC。 |
| `closeProject` | 丢掉工程与它的缓存状态。 |
| `ping` | 存活检查。 |
| `shutdown` | 让进程退出。 |

完整契约见
[`PROTOCOL.md`](../../../plumb-frontend/ts-sidecar/PROTOCOL.md)。

### 4.5 把每一次降级都报出来

**[MEASURED]** 问题以两种形式传出去：普通解析结果里是字符串，结构化结果里是结构化问题——带着
构造、行号与列号。结构化那种才是穿行于 IR 之中的形式。

**[MEASURED]** 一个前端必须是全函数。缺 Node 运行时、缺 sidecar、响应格式错误：每一个都被报
成一个问题，每一个都交出能解析出来的部分。没有一个是静空的返回，因为静空的返回读起来就是
「没有 finding」。

---

## 5. 让两半保持诚实的交叉校验

**[MEASURED]** Java 侧与 Node 侧是各自独立构建的，而两边的不一致会产出看起来合理、却什么也找不
到的 TAC。所以有两个测试，它们是一对：

| 测试 | 它驱动什么 | 它钉住什么 |
| --- | --- | --- |
| 替身 sidecar | 一个用 Java 写的、讲同一套协议的对端 | Java 侧的分帧、握手与线上到 IR 的映射，在任何机器上都成立 |
| 端到端测试 | 真正的 Node sidecar 与真正的编译器 | Java 侧读的那条线，就是真 sidecar 写的那条线 |

而断言正是那三条一旦坏掉就会静默失效的：

- 一次属性读是带字符串键的 `ARRAYLOAD`，而且**绝不**是 `FIELDLOAD`；
- 一次调用把接收者留在 `args[0]`；
- 一次模块级调用点名的是它**被导入自的那个模块**，而不是它写在哪个文件里。

**[MEASURED]** 前两条都用变异检验过：把翻译器改成发出 `FIELDLOAD`，再把接收者挪到参数列表末尾，
每一次都让端到端测试变红，并给出指认该缺陷的那条断言。第三条用同样的方法检验过，而且它最要紧，
因为这个命名约定的两半分处两个文件：把模块改成文件，让端到端测试里脚本语言那几例变红，而 Python
与 PHP 那几例仍然是绿的——而这正是单边损坏的样子。一个从没被看着失败过的测试不是证据。

---

## 6. 本文不主张什么

- **它不主张对一个 JavaScript 项目的扫描是完整的。** 目录点名的是模块级的 Node API；一个通过
  应用自己的类触达危险 API 的程序不在其中，而一份靠猜的目录比一份不猜的目录更糟。
- **它不主张翻译器覆盖了整门语言。** 第 2.4 节的表是它**没有**建模的清单。其余它都降级，而它
  降级的东西它也都报出来。
- **它不主张属性访问是字段访问。** 恰恰相反，第 2.1 节说了为什么。
- **它不主张五个脚本语言走得一样远。** 本文讲的是 TypeScript 那一个。ArkTS、Python 3 与 PHP
  各有自己的 sidecar、各有自己的前端、各有自己的文档。

---

## 附录：事实来自哪里

| 事实 | 它在哪里 |
| --- | --- |
| sidecar 的协议 | [`plumb-frontend/ts-sidecar/PROTOCOL.md`](../../../plumb-frontend/ts-sidecar/PROTOCOL.md) |
| 翻译器 | [`plumb-frontend/ts-sidecar/src/translator.mjs`](../../../plumb-frontend/ts-sidecar/src/translator.mjs) |
| Java 前端 | [`plumb-frontend/src/main/java/org/plumb/tac/frontend/ts/TsTacFrontend.java`](../../../plumb-frontend/src/main/java/org/plumb/tac/frontend/ts/TsTacFrontend.java) |
| 工程的发现 | [`plumb-frontend/src/main/java/org/plumb/tac/frontend/ts/TsProjectDiscovery.java`](../../../plumb-frontend/src/main/java/org/plumb/tac/frontend/ts/TsProjectDiscovery.java) |
| keyed 命名空间 | [`plumb-tac/src/main/java/org/plumb/tac/ir/TacAccessPath.java`](../../../plumb-tac/src/main/java/org/plumb/tac/ir/TacAccessPath.java) 与 [DATA-FLOW.md](../../tech/DATA-FLOW.md) |
| 语言的判定 | [`plumb-tac/src/main/java/org/plumb/tac/ir/SourceLanguage.java`](../../../plumb-tac/src/main/java/org/plumb/tac/ir/SourceLanguage.java) |
| Go 那条路线，供对照 | [GO-FRONTEND-ANALYSIS.md](../../tech/GO-FRONTEND-ANALYSIS.md) |
