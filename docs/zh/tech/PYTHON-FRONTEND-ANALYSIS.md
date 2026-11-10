# Plumb 中的 Python：sidecar 路线、CPython 自己的解析器，以及 TAC 如何生成

[English](../../tech/PYTHON-FRONTEND-ANALYSIS.md) | [中文](../README.md)

## 0. 先说结论

Plumb 能读 Python 3。路线是一个 Python sidecar，它驱动 CPython 自己的
`ast` 模块；它产出的 TAC 就是那个 TAC：同样的指令、同样的调用约定、同样的
keyed 命名空间——JavaScript 和 TypeScript 前端用的就是这一套。

| 问题 | 答案 |
|---|---|
| 用哪个解析器 | CPython 3 自己的 `ast` 模块，经由运行 sidecar 的那个解释器 |
| 跑在哪里 | 一个 Python 进程，走 stdio，一个请求一帧 |
| 读哪些文件 | `.py` |
| 产出什么 | IR 里的 `TacFunction`，并带上这个文件的问题清单 |
| 还不产出什么 | findings：Python 的 sink 目录和语义表是规则层的工作 |

最后一行是诚实的那一行。前端把 Python 正确地降级成 TAC；能不能出 finding，
取决于那些匹配降级后调用的规则，而 Python 的规则还不存在。

**Python 2 不被支持，而且这不是将来会补上的缺口。** 枚举名是 `PYTHON3`，
解析器用的是 Python 3 的文法；一个跑在 Python 2 解释器上的 sidecar 会被
拒绝，而不是被信任。

## 1. JVM 够不到 Python 的解析器

### 1.1 解析器是一个程序，不是一个库

Python 的文法由参考实现自己的解析器决定：它决定 `async` 是不是关键字、
match 语句能不能解析、walrus 运算符的值到哪里结束。

在 JVM 里重写那份文法，就是第二份实现，而第二份会和第一份在"一个文件
是什么意思"上产生分歧。关于名字的分歧会产生不同的 callee 签名，而 callee
签名正是 sink 目录所匹配的东西。

### 1.2 路线：一个 Python sidecar

所以分析跑在一个 sidecar 里，由它驱动解释器自己的解析器，走长度前缀的
JSON 分帧，每个响应都带一次版本握手。Java 侧把线上格式映射进 IR；IR 就是
那个 IR，引擎并不知道它是哪种语言产出的。

### 1.3 为什么这个 sidecar 不需要装任何东西

别的 sidecar 都自带编译器作为依赖：TypeScript 那个要 npm，ArkTS 那个要
ArkTS 的 fork，Go 那个要 Go 工具链。Python 这个**什么都不需要**：`ast` 就在
标准库里。

这不只是省事。它意味着 Python 前端没有安装步骤、没有 lock 文件、没有要和
包管理器对齐的版本；也意味着解析器就是这台机器上的解释器真正在用的那一个。

### 1.4 为什么解释器的版本要查

靠 PATH 查找没法从名字上分辨 Python 2 和 Python 3：它们都叫 `python`。
所以版本不是假设出来的，而是问出来的。sidecar 在 `ping` 响应里报告自己跑
在哪个解释器上，前端拒绝任何一个不是 Python 3 的。

理由不是吹毛求疵。Python 2 的解释器会把 Python 3 的文件读成坏文件——或者
更糟，把它读成 Python 2 的文件，然后对着用户根本没写过的程序报 finding。

## 2. Python 会把 TAC 的什么打破

### 2.1 对象是命名空间，不是记录

在 Python 里 `obj.user_id` 和 `obj["user_id"]` 是同一个位置，`items[0]` 就是
`items["0"]`。所以属性访问被降级成带字符串键的容器访问——一个 `ARRAYLOAD`
或一个 `ARRAYSTORE`，它的下标操作数就是那个键——而不是字段访问。字段指的是
字段，而属性不是字段。

**赋值是一次写，而这次写必须到达 store。** 属性赋值是一个 `ARRAYSTORE`，
键就是属性名，这样后面再读同一个属性时才能读到刚写进去的值。

### 2.2 接收者槽位是保留的

TAC 的调用约定把 `args[0]` 留给接收者，真正的参数从 `args[1]` 开始。这个
槽位是有调用就保留、没有调用也保留：自由函数没有接收者，于是槽位里放的是
模块名的常量。

### 2.3 调用的 owner 是 import 给出的那个名字

Python 按**名字**解析调用，而名字正是目录所匹配的东西。所以 import 是被
**读**的，而不只是被执行：正是它们让一个 callee 有可能被解析出来。

| 源码 | owner | 签名 |
|---|---|---|
| `import os` 然后 `os.system(cmd)` | `os` | `os.system/1` |
| `from os import system` 然后 `system(cmd)` | `os` | `os.system/1` |
| `import numpy as np` 然后 `np.array(x)` | `numpy` | `numpy.array/1` |
| `from os import path` 然后 `path.join(a, b)` | `os.path` | `os.path.join/2` |
| `eval(x)` | `builtins` | `builtins.eval/1` |
| `store.find(key)`，其中 `store` 是 `UserStore` | `UserStore` | `UserStore.find/1` |
| `self.find(key)` | 外层那个类 | `UserStore.find/1` |
| `helper(x)`，其中 `helper` 在本模块里 | 本模块 | `app.helper/1` |

解析不出结果的 callee 是**漏掉的 finding**，而不是少一点精度：那个签名会
是一个匹配不到任何 family 的名字。所以这种情况会被报出来，调用会落成
`UNINTERPRETED`——一个结果可能是任何东西的黑盒，而这是保守的方向。

### 2.4 arity 数的是位置参数

本模块声明的函数贡献它声明的位置参数，接收者不在其中：一个带一个参数的
方法是 `UserStore.find/1`，接收者在 `args[0]`。

来自别处的函数贡献这次调用传了几个位置参数。Python 没有静态类型，所以外部
函数的声明参数在这里看不见——而且关键字参数不是位置参数。所以
`subprocess.run(cmd, shell=True)` 是 `subprocess.run/1`，这正是那个 API 自己的
签名所说的。

### 2.5 哪些没有被建模，以及怎么报

下面每一条都会作为问题报在那个函数上，受影响的值变成未知值。绝不静默省略。

| 构造 | 会发生什么 |
|---|---|
| lambda | 报为 `SOURCE_LOWERED`；值变成未知 |
| 生成器（`yield`） | 报为 `UNHANDLED_NODE`；值变成未知 |
| match 语句 | 报为 `UNHANDLED_NODE`；被匹配的对象仍然被降级 |
| 推导式 | 报为 `SOURCE_LOWERED`：推导式有自己的作用域，所以第一个之后的元素没有被建模 |
| 解析不出 callee 的调用 | 落成 `UNINTERPRETED`：一个结果可能是任何东西的黑盒 |
| 关键字参数 | 报为 `SOURCE_LOWERED`：它被当成位置参数传 |
| 带星号的参数 | 报为 `SOURCE_LOWERED`：它被当成一个参数传 |
| 非常量的默认值 | 报为 `SOURCE_LOWERED`：默认值是在函数被定义的地方求值的 |
| set 字面量 | 报为 `SOURCE_LOWERED`：set 没有顺序 |
| 其他任何语句或表达式 | 报为 `UNHANDLED_NODE`；值变成未知 |

有两处不报，只是因为这个差别改变不了分析能看到的东西：`await` 产出被等待的
那个值，因为 TAC 没有协程；`with` 把上下文管理器的值绑给目标变量，因为 TAC
没有 `__enter__` 也没有 `__exit__`。上下文表达式本身仍然被降级，所以里面的
调用仍然是调用。

**同一个问题只报一次，带上次数。** 一个文件里有五十次调用都指向一个解析不
出来的名字，那么是一条说"发生了五十次"的问题，而不是五十条问题。

## 3. 是工程，不是文件

### 3.1 文件的模块名就是它的路径

模块的名字是它在工程根目录下的路径，用点连起来：`app.py` 是 `app`，
`app/routes/user.py` 是 `app.routes.user`；而一个包的 `__init__.py` **就是**
那个包，所以 `app/__init__.py` 是 `app`。模块名是本模块函数的 owner，所以根
目录决定了 callee 签名长什么样。

### 3.2 工程的边界在哪里

边界是编译上下文的模块根目录（当文件属于某个模块时），否则是最近的、带有
工程标记的祖先目录。

| 标记 | 含义 |
|---|---|
| `pyproject.toml` | 工程自己的配置：一个工程 |
| `setup.py` / `setup.cfg` | setuptools 工程的配置：一个工程 |
| `__init__.py` | **不是**标记：它说明一个目录是包，而不是说明这个目录是工程——否则一个包里的每个子目录都会各成一个工程 |

### 3.3 哪些被排除在外，以及为什么

类型存根（`.pyi`）声明别的模块的形状，自己没有可执行体，分析它等于对着
声明报 finding，而不是对着代码报。虚拟环境里装着依赖，构建输出里是生成的
代码，而名字以点开头的目录是工具自己的状态。

## 4. Python 的 TAC：五步

### 4.1 打开工程

Java 侧把工程的文件和它们所在的根目录发过去。sidecar 把两者记下来，之后
每个文件都带着那个根目录去翻译。

### 4.2 翻译

Java 侧点名要一个文件。sidecar 从磁盘读它的文本，然后降级。

解析器自己读文件，所以分析的是磁盘上的那个文件。调用方递过来一份不一样的
文本，就是在分析别的东西，这是要报出来的。

### 4.3 降级

模块自己的代码变成一个合成函数；函数变成函数；类的类体变成一个合成函数，
它的方法各自变成函数；属性读和属性写变成带字符串键的容器访问；二元运算符
变成一个 `BINOP`。

### 4.4 讲线格式

函数以长度前缀的 JSON 传递。每个响应都带协议版本和 IR 版本，失败的那些也
一样。

### 4.5 报出每一处降级

降不下来的构造会成为那个函数上的一个问题，函数的精度降到 `SOURCE_LOWERED`。
一个没解析成功的文件会变成一次失败响应，Java 侧把它变成一个问题：一个
Python 2 的文件不是"不完整的 Python 3 文件"，它是另一个程序。

## 5. 让两半互相校验的交叉检查

Java 侧对着一个说同样协议的替身 sidecar 做测试，所以分帧、握手和线上到 IR
的映射在任何能跑测试的机器上都被钉住了。Python 侧由一个烟雾测试来测：它
把真正的 sidecar 当进程启动，检查从线上回来的 TAC。端到端测试则用真正的
前端去驱动真正的解析器。

两半都被有意改坏过，用来检查另一半会不会发现：属性读被改成字段读，接收者
槽位被挪到参数列表末尾——两个都会让测试变红。

## 6. 本文不主张什么

| 主张 | 状态 |
|---|---|
| Python 3 源码被降级进 TAC | 成立，并且是对着真正的解析器测过的 |
| 降级能找出每一条污点流 | 不主张：分析是过程内的，一个方法里写的属性，另一个方法看不到 |
| Python 会产出 finding | 还不会：Python 的 sink 目录和语义表都不存在 |
| callee 的 owner 一定对 | 不主张：接收者类型推不出来时，owner 退回模块名，并且这一点会被报出来 |
| Python 2 被支持 | 不成立，而且是故意的：枚举名是 `PYTHON3`，Python 2 的解释器会被拒绝 |

## 附录：事实从哪里来

| 事实 | 来源 |
|---|---|
| 文法 | CPython 3 自己的 `ast` 模块，经由运行 sidecar 的那个解释器 |
| 内置函数 | 运行中解释器自己的 `builtins` 模块，是读来的而不是抄来的 |
| 线格式 | [`plumb-frontend/python-sidecar/PROTOCOL.md`](../../../plumb-frontend/python-sidecar/PROTOCOL.md) |
| Java 前端 | [`PythonTacFrontend.java`](../../../plumb-frontend/src/main/java/org/plumb/tac/frontend/python/PythonTacFrontend.java) |
| 翻译器 | [`plumb-frontend/python-sidecar/src/plumb_translator.py`](../../../plumb-frontend/python-sidecar/src/plumb_translator.py) |
| 烟雾测试 | [`plumb-frontend/python-sidecar/smoketest.py`](../../../plumb-frontend/python-sidecar/smoketest.py) |
