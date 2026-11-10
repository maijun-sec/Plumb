# Plumb 中的 ArkTS：sidecar 路线、编译器自己的文法，以及 TAC 如何生成

[English](../../tech/ARKTS-FRONTEND-ANALYSIS.md) | [中文](../README.md)

## 0. 先说结论

Plumb 能读 ArkTS。路线是一个 Node sidecar 驱动 ArkTS 编译器自己的前端，产出的
TAC 就是普通的 TAC：同样的指令、同样的调用约定、同样的 keyed 命名空间——和
JavaScript、TypeScript 前端用的是同一套。

| 问题 | 答案 |
|---|---|
| 用哪个编译器 | `ohos-typescript`，ArkTS 的 TypeScript 编译器分支，通过它经典的 `createProgram` API 驱动 |
| 在哪运行 | 一个 Node 进程，走 stdio，一个请求一帧 |
| 读哪些文件 | `.ets` |
| 产出什么 | IR 里的 `TacFunction`，并附上该文件的问题 |
| 还没产出什么 | finding：ArkTS 的 sink 目录与 ArkTS 的语义表属于规则层的工作 |

最后一行是实话。前端把 ArkTS 降级成 TAC 是正确的；能不能出 finding，取决于
哪些规则去匹配降级后的调用，而那些规则对 ArkTS 还不存在。

## 1. JVM 够不到 ArkTS 编译器

### 1.1 编译器是一个程序，不是一个库

ArkTS 由 TypeScript 编译器的一个分支来读，而这个分支认识这门语言：它解析
`struct`，它认识 ArkUI 装饰器，它解析声明式 UI DSL。这些不是便利设施——它们
决定一个文件到底能不能解析。

在 JVM 里重新实现这套文法，等于有第二套实现，而第二套必然会在"UI DSL 从哪里
开始""哪些名字是组件""一个属性的类型是什么"这些问题上与第一套不一致。而类型上
的不一致会产生不同的被调签名，被调签名正是 sink 目录用来匹配的东西。

### 1.2 路线：一个 Node sidecar

所以分析跑在一个 sidecar 里，由它驱动编译器自己的 API，走长度前缀的 JSON 分帧，
每个响应都带版本握手。Java 侧把线上的东西映射进 IR；IR 就是那个 IR，引擎不需要
知道它是哪种语言产出的。

### 1.3 为什么这个 sidecar 是独立的 npm 工程

ArkTS sidecar 与 TypeScript sidecar 不共用任何文件。两者由不同的编译器驱动，
API 也不同——一个走经典的 `createProgram`，另一个走版本 7 的 client/server 通道——
共用文件会把其中一个硬塞进另一个的形状里。于是每个 sidecar 各自持有一份协议和
各自的翻译器。

## 2. ArkTS 会把 TAC 的什么打破

### 2.1 对象是命名空间，不是记录

在 ArkTS 里 `this.message` 与 `this["message"]` 是同一个位置，`items[0]` 就是
`items["0"]`。所以属性访问被降级为带字符串键的容器访问——一个索引操作数是键的
`ARRAYLOAD` 或 `ARRAYSTORE`——而不是字段访问。字段是字段，属性不是。

**赋值是写，而写必须到达 store。** 属性赋值是一个以属性名为键的 `ARRAYSTORE`，
这样后续对同一属性的读才能找到写进去的值。

### 2.2 接收者槽位是保留的

TAC 的调用约定把 `args[0]` 留给接收者，真正的参数从 `args[1]` 开始。无论调用
有没有接收者对象，这个槽位都保留：自由函数没有接收者，于是槽位里放函数**导入
自的那个模块**的名字的常量。

### 2.3 owner 是函数来自的那个模块

脚本语言是通过**模块**而不是类来触达库的：没有类可以点名，所以模块级函数的
owner 是它被导入自的那个模块。

```
import { parse } from '@ohos.util.json';
parse(text);          // @ohos.util.json.parse

import * as json from '@ohos.util.json';
json.stringify(text); // @ohos.util.json.stringify
```

另一个选择——**文件**所属的模块——说的是调用写在哪里，而不是调用了什么；而且
没有任何 sink 目录能匹配上它，因为目录不可能枚举每个程序的文件名。

别名会被解析掉：`import { parse as read }` 调用的是 `parse`，所以目录要匹配的
是导出时的那个名字。

方法的 owner 是接收者的类，类名来自编译器自己的答案。**[MEASURED]** 这套编译器不给本地类实例
命名：`new Helper()` 的类型出来是全局的 `Object`，所以 owner 回落到调用所在的那个文件。同样的事
发生在没有类型的全局对象上（比如 `console`），因为这套编译器不加载 DOM 的声明。这个回落由烟雾
测试钉住，所以它是一条写明的行为，而不是一个意外。

### 2.4 组件调用的 arity

普通调用的 arity 数的是声明参数数。UI DSL 的组件是例外：在 SDK 的声明里，组件
是一个类型为接口的常量，而 DSL 是把它当函数调用的，所以它的声明参数数说明不了
这次调用。参数个数才是诚实的那个数。

### 2.5 struct 的状态，以及它放在哪里

struct 的属性是它的状态，而读属性的方法必须看到属性里放着什么。所以一个 struct
产出两类函数：struct 自己的初始化函数，装着每个属性的初始值；以及每个方法一个
的函数，进入时先带上它可能读到的属性值。

初始值是纯表达式的属性会被复制进每一个方法。初始值里有调用的属性不会：一个调用
是有副作用的，每个方法都跑一遍就会把同一个副作用报一遍。它留在 struct 的初始化
函数里，只被看一次。

### 2.6 哪些没有被建模，以及怎么报

下面每一种都会变成函数上的一个问题，受影响的值变成未知值。绝不静默省略。

| 构造 | 会发生什么 |
|---|---|
| `break` 或 `continue` | 报为 `SOURCE_LOWERED`；控制流由循环自身的结构承担 |
| `switch` | 报为 `SOURCE_LOWERED`；各个 case 按顺序降级，数据流得以保留，case 的选择没有保留 |
| 被当作值使用的函数 | 报为 `SOURCE_LOWERED`；该值变成未知 |
| 类表达式 | 报为 `UNHANDLED_NODE` |
| 被调解析不出来的调用 | 发出为 `UNINTERPRETED`：一个结果可能是一切的黑盒 |
| 其他任何语句或表达式 | 报为 `UNHANDLED_NODE`；该值变成未知 |

只声明类型的构造——接口、类型别名、import——被跳过而不是上报：它声明的是一个
类型，没有任何运行期效果，里面没有可作为 finding 靶子的东西。

## 3. 是工程，不是文件

### 3.1 单独分析的文件类型是未知的

ArkTS 文件会指名它 import 的模块，编译器对照工程来解析这些名字。单独分析一个
文件，它 import 任何东西的地方类型都是未知的，而未知的类型意味着解析不出来的
被调——那是漏掉的 finding，而不是缺失的精度。

所以编译器拿到的是整个工程，工程开一次，之后每个文件复用。

### 3.2 工程的边界在哪里

边界是编译上下文的模块根（当文件属于某个模块时），否则是最近的、标记了一个模块
的祖先目录。

| 标记 | 含义 |
|---|---|
| `build-profile.json5` | ArkTS 工具链的构建配置：一个模块 |
| `oh-package.json5` | 模块的包清单：一个模块 |
| `tsconfig.json` | 同时使用 TypeScript 配置的工程 |
| `package.json` | **不是**标记：它在家目录里很常见，边界一路找到它就会把整个家目录当成一个工程 |

### 3.3 哪些被排除在外，以及为什么

声明文件只有签名没有可执行体，分析它们等于对着声明报 finding，而不是对着代码报。
依赖目录里是别人的代码，构建输出里是生成的代码，而以点开头的目录是工具自己的
状态。

## 4. ArkTS 的 TAC：五步

### 4.1 打开工程

Java 侧把工程的文件发过去，编译器把它们加载起来。组件清单还会补上工程自己源码
里用到的名字，所以 SDK 没有声明的组件——以及工程为自己定义的组件——照样能被
认出来。

### 4.2 翻译

Java 侧点名一个文件。sidecar 在 program 里找到它，从磁盘读出它的文本，然后降级。

编译器自己读文件，所以分析的是磁盘上的那个文件。调用方递过来别的文本，分析的
就不是它递的那个东西，这件事会被报出来。

### 4.3 降级

struct、它的方法与属性变成函数；UI DSL 变成一次调用；属性读与属性写变成带字符串
键的容器访问；二元运算符变成 `BINOP`。

### 4.4 讲线格式

函数以长度前缀的 JSON 过线。每个响应都带协议版本与 IR 版本，失败的响应也一样。

### 4.5 报出每一处降级

建模不了的构造变成函数上的一个问题，函数的精度降到 `SOURCE_LOWERED`。没解析出
来的文件变成文件上的一个问题，而文件仍然被分析：丢掉它就会藏起里面每一条污点。

## 5. 编译器的三个设置

三个编译选项决定一个 ArkTS 文件到底能不能解析。它们不是调优。

| 选项 | 取值 | 决定什么 |
|---|---|---|
| `ets.render.method` | `["build"]` | 哪个方法是渲染方法；UI DSL 只在里面被解析 |
| `ets.render.decorator` | `["Builder", "LocalBuilder"]` | 哪些装饰器引入一个 builder 函数 |
| `ets.components` | SDK 的名字加上工程的 | 哪些名字是组件；不在清单上的名字是普通调用 |

SDK 的模块映射也会被加载，取自 SDK 自己的配置所带的 `paths`。没有它，`@ohos.*`
模块的 import 什么也解析不到，而解析不到的 import 意味着未知的类型，也就意味着
漏掉的 finding。

## 6. 让两半互相校验的交叉检查

Java 侧用一台讲同样协议的替身 sidecar 来测，于是分帧、握手与线上到 IR 的映射在
哪里跑都钉得住。Node 侧由一个烟雾测试来测：它把真的 sidecar 当进程拉起来，检查
过线回来的 TAC 长什么样。还有一个端到端测试，用真的前端驱动真的编译器。

每一半都被故意改坏过，用来检查另一半会不会发现。把属性读改成字段读，把接收者
槽位挪到参数列表末尾：两处都会让测试变红。

## 7. 本文不主张什么

| 主张 | 状态 |
|---|---|
| ArkTS 源码被降级进 TAC | 成立，且对着真编译器测过 |
| 这个降级能找出每一条污点流 | 不主张：分析是过程内的，一个方法里写的字段另一个方法看不到 |
| ArkTS 会出 finding | 还没有：ArkTS 的 sink 目录与语义表都不存在 |
| 组件调用的 arity 是参数个数 | 成立，并且刻意不同于普通调用的 arity |

## 附录：事实从哪里来

| 事实 | 来源 |
|---|---|
| 编译器的 API | [`ohos-typescript`](../../../plumb-frontend/arkts-sidecar/package.json)，从装好的包里读 |
| UI DSL 的文法 | 编译器自己的解析器：组件表达式是一个构造，带 block 的调用也是一个 |
| 组件名 | SDK 自己的声明，由 [`tools/generate-components.mjs`](../../../plumb-frontend/arkts-sidecar/tools/generate-components.mjs) 重新生成 |
| 模块映射 | SDK 自己的配置文件，是读来的，不是抄来的 |
| 线格式 | [`plumb-frontend/arkts-sidecar/PROTOCOL.md`](../../../plumb-frontend/arkts-sidecar/PROTOCOL.md) |
| Java 前端 | [`ArktsTacFrontend.java`](../../../plumb-frontend/src/main/java/org/plumb/tac/frontend/arkts/ArktsTacFrontend.java) |
| 翻译器 | [`plumb-frontend/arkts-sidecar/src/translator.mjs`](../../../plumb-frontend/arkts-sidecar/src/translator.mjs) |
