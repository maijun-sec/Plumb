# 降级方案：PHP 与 Python 前端（IR v2 前端侧）

**路径缩写**（下文所有 `file:line` 均相对于仓库根 `D:\work\workspace\idea\engine\Plumb`）：
`PHP/` = `plumb-frontend/php-sidecar/src/`；`PY/` = `plumb-frontend/python-sidecar/src/`；
`IR/` = `plumb-tac/src/main/java/org/plumb/tac/ir/`；`FE/` = `plumb-frontend/src/main/java/org/plumb/tac/frontend/`；
`DFA/` = `plumb-dfa/src/main/java/org/plumb/dfa/`；`PP/` = `plumb-frontend/php-sidecar/node_modules/php-parser/src/`。
**两处例外**：① `FE/TacFrontend.java` 实际在 **`plumb-tac/src/main/java/org/plumb/tac/frontend/`**（该文件属于 `plumb-tac` 模块，
不在 `plumb-frontend` 里）——本文凡引它都写成 `FE/TacFrontend.java`，以**文件名**定位；② `PHP/package.json` 与 `PHP/PROTOCOL.md`
在**侧车根目录** `plumb-frontend/php-sidecar/`（不在 `src/` 下）——本文凡引它们都写成 `PHP/package.json` / `PHP/PROTOCOL.md`，
以**文件名**定位。

**行号口径**：本文所有行号取自 **HEAD `2c15f0e` 的当前工作树**（2026-11-16）。仓库被另一团队并发修改，
`plumb-tac/.../ir/TacFunction.java`、`TacCfgBuilder.java`、`IrPrecision.java` 等文件在工作树里与 HEAD 有差异；
`plumb-frontend/php-sidecar/src/translator.mjs` 与 `plumb-frontend/python-sidecar/src/plumb_translator.py`
的行号与审计报告一致（已逐条抽查，**附录 D** 列出抽查样本）。**行号会漂，以方法名 + 代码骨架为准。**

**前提**：IR v2 形状已定稿（`01-TAC-IR-扩展设计.md`，下称 `IR v2 §IR-A`…`§IR-Q`，补遗 `§IR-I`…`§IR-Q`），
本文只做前端降级方案，不重设 IR。补充提案一律用**完整编号 `IR-xx`**（本文从 `IR-AO` 起），不用单字母简写
（单字母简写在跨文档场景下会撞名）。

**口径**：**完整支持优先**。凡 TAC 能表达的构念，一律给出真正的降级形状；`TacDrop` / `SOURCE_DEGRADED`
**不是兜底通道**，只在「确实无法静态表达」时产生（§12 逐条论证）。
**禁止**用 `UNKNOWN` / `HAVOC` / `UNINTERPRETED` 之类的占位代替「完整表达」。

**硬约束**：不新增语言专用 opcode（`FE/TacFrontend.java:50-57`）；不点名任何商用安全工具。

**AST 节点名约定**：本方案每一张声明面/表达式面表格都有「解析器 AST 节点 / API」一列。
PHP 侧车用的是 **`php-parser` 3.7.0**（`PHP/package.json:12` 声明 `"php-parser": "3.7.0"`），
节点名与字段名取自 `PP/ast/*.js` 与 `PP/parser/*.js`；Python 侧车用的是 **CPython 标准库 `ast`**，
节点名取自 `PY/plumb_translator.py` 里实际 `isinstance` 判断的类。**若某构念在解析器里没有对应节点，本文明说。**

---

## 1. 目标与范围

| 编号 | 目标 | 落点 |
|---|---|---|
| G1 | PHP / Python 的**每个**构念都有完整 TAC 表达；不存在「不认识 ⇒ `HAVOC` + drop」的默认路径 | §4 §5 §6 §11 |
| G2 | 语义保真优先于结构保真：求值顺序、短路、可选链、分支选择、异常边、迭代协议、循环 `else` 全部建模 | §6 |
| G3 | 类型保真：联合保留成员、可空性保留、类型实参不丢、类型变量有上界 | §7 |
| G4 | 分支必带结构化守卫（`IR v2 §IR-C`），跳转必带标签语义（`IR v2 §IR-G`） | §6 §9 |
| G5 | 精度由丢弃推导（`IR v2 §IR-B`）；丢弃只在 §12 例外里产生 ⇒ 正常路径应为 `SOURCE_FULL` | §3 §9 |
| G6 | 声明面成员身份可表达：字段 / 属性 / 构造器 / 静态方法 / 魔术方法 / 装饰器可区分（`IR v2 §IR-E`） | §4 |
| G7 | 跨函数污点能跨过方法边界（`IR v2 §IR-P` 的 callee 规范） | §4.4 §6.10 |
| G8 | 解析器层面的**文件级丢失**被消除（PHP 的 `"$_GET['x']"` 整文件解析失败） | §2.5 §10.4 |

**非目标**：
① 不建模事件循环 / 协程 / Fiber 的调度时序（TAC 是顺序指令流，`IR/TacInstruction.java:192-258`）；
② 不做跨文件的类型推断（类型取自各自解析器/本模块的声明）；
③ 不建模「运行期才决定的动态事实」——这一类进 §12，并逐条给出理由；
④ 不改引擎的指令语义（`IR v2 §0.2` 的爆炸半径约束），新能力一律是**加字段**。

### 1.1 两种语言的关键差异（决定了方案为何分两节）

| 维度 | PHP | Python |
|---|---|---|
| 解析器 | 手写 sidecar + `php-parser` 3.7.0（`PHP/package.json:12`） | CPython 标准库 `ast`（`PY/plumb_translator.py:34` `import ast`） |
| 解析器版本 | 固定在 package.json；**升级会漂 AST 形状** | **跟着解释器走**：`ast.TryStar`（3.11+）、`ast.TypeAlias`（3.12+）、`type_params`（3.12+）是否存在取决于运行时解释器（`PY/plumb_translator.py:34`） |
| 对象模型 | 命名空间（`$o->name` ≡ `$o["name"]`），刻意用 `ARRAYLOAD`/`ARRAYSTORE`（`PHP/translator.mjs:12-17`） | 命名空间（`obj.user_id` ≡ `obj["user_id"]`），同样用 `ARRAYLOAD`/`ARRAYSTORE`（`PY/plumb_translator.py:13-18`） |
| 隐式接收者 | `$this`（方法必有） | `self` / `cls`（**`staticmethod` 没有**）——这是两边最大的语义分歧，见 §4.4 |
| 闭包捕获 | **显式** `use (...)`，捕获集编译期已知（`PP/ast/closure.js` 的 `uses` 字段） | **隐式** 自由变量，需静态自由变量分析（`PY/plumb_translator.py:533-544` `free_variables`） |
| 字符串插值 | `encapsed` 节点（`PP/ast/encapsed.js`：`value, raw, type`） | `ast.JoinedStr` + `ast.FormattedValue`（`PY/plumb_translator.py:1485-1488`） |
| 异常 | `try`/`catch`/`finally`（`PP/ast/try.js`：`body, catches, always`） | `try`/`except*`/`else`/`finally`（`PY/plumb_translator.py:954` 只认 `ast.Try`） |
| 循环 `else` | **无** | **有**（`PY/plumb_translator.py:1227-1228` / `:1322-1323`，今天语义反了） |
| 命名实参 | PHP 8 有（`PP/ast/namedargument.js`：`name, value`） | Python 3 有关键字实参（`PY/plumb_translator.py:1905-1906`） |

---

## 2. 现状地图

### 2.1 PHP sidecar 的结构

| 部件 | 位置 | 职责 |
|---|---|---|
| 入口 / wire 循环 | `PHP/main.mjs:73-107`（`handle`）、`:123-162`（`translate`）、`:175-232`（分帧） | 4 字节大端长度前缀 + UTF-8 JSON；`parseCode` 失败 ⇒ `failure(OP.TRANSLATE, "the file is not valid PHP: …")`（`:137-142`） |
| 解析器设置 | `PHP/main.mjs:36-45` | `version: "8.3"`、`withPositions: true`、`suppressErrors: false` |
| wire 常量表 | `PHP/protocol.mjs:26-102`（`KIND`/`TYPE`/`CONST`/`PRECISION`/`PROBLEM`/`OP`） | **两档精度**（`:91-94`），没有 `SOURCE_DEGRADED` |
| 翻译器主体 | `PHP/translator.mjs:211-2217`（`class Translator`） | 全部降级逻辑 |
| 算子表 | `PHP/translator.mjs:49-78`（`BINARY_OPERATORS`）、`:88-103`（`APPROXIMATED_OPERATORS`）、`:112-126`（`COMPOUND_OPERATORS`）、`:134-138`（`UNARY_OPERATORS`） | `**`/`??`/`<=>` 被**近似**并上报 |
| 名字解析 | `PHP/translator.mjs:280-305`（`collectBindings`）、`:313-326`（`collectClassBindings`）、`:339-358`（`recordUse`）、`:1934-1945`（`qualifyFunction`）、`:1981-1988`（`qualifyClass`）、`:1965-1969`（`canonicalName`） | PHP 的全局回退规则（`canonicalName` 给全局名加前导 `\`） |
| 类体降级 | `PHP/translator.mjs:628-662`（`lowerClass` / `lowerClassBody`） | 类体 ⇒ 合成 `<init>` 函数 + 每方法一函数；**`else` 分支把未知成员丢给 `lowerStatement`**（`:658-659`） |
| 函数收尾 | `PHP/translator.mjs:371-389`（`finishFunction`） | **不发 `returnType`**（`:378-388` 的返回对象里没有这个键）；`synthetic` 恒 `false`（`:383`）；精度 = 有 problem 就是 `SOURCE_LOWERED`（`:382`） |

### 2.2 Python sidecar 的结构

| 部件 | 位置 | 职责 |
|---|---|---|
| wire 模型 | `PY/plumb_wire.py:25-102` | 与 PHP 的 `protocol.mjs` **同构但独立**（`Kind`/`Type`/`Const`/`Precision`/`Problem`/`Op`） |
| 翻译器主体 | `PY/plumb_translator.py:271-2421`（`class Translator`） | 全部降级逻辑 |
| 名字绑定预扫 | `PY/plumb_translator.py:336-386`（`collect_bindings` / `walk_bindings`） | 导入、类、函数、方法；`ast.ImportFrom` **不读 `node.level`**（`:360-373`） |
| 作用域预扫 | `PY/plumb_translator.py:472-531`（`collect_scope` / `bound_names`）、`:533-544`（`free_variables`）、`:546-593`（`collect_loaded_names`） | 自由变量 ⇒ 闭包的前导形参 |
| 函数收尾 | `PY/plumb_translator.py:725-757`（`finish_function`） | **发 `returnType`**（`:742`，用 `return_type_of_signature`，`:92-109`）；`synthetic` 正确（`:752`）；精度 = 有 problem 就是 `SOURCE_LOWERED`（`:744-751`） |
| callee 解析 | `PY/plumb_translator.py:2064-2084`（`resolve_callee`）、`:2096-2176`（`resolve_free_function`）、`:2178-2211`（`resolve_method`）、`:2248-2262`（`arity_of_name`）、`:2264-2276`（`declared_arity`） | 见 §4.4 的 arity 结论 |

### 2.3 IR 本体

| 结构 | 位置 | 现状 |
|---|---|---|
| `TacType` | `IR/TacType.java:37-…`，15 个 `Kind`（`:40-64`） | `nullable` 字段已存在（`:123`、`:158`）；**没有** `domain` / `unionMembers` / `typeArguments` / `bound` |
| `TacOperand` | `IR/TacOperand.java` | 只有 `VAR` / `CONST` 两种变体（新增变体 = 411 处 `instanceof` 风险，见 `01` §0.2） |
| `TacInstruction` | `IR/TacInstruction.java:47-157`（`Kind`）、`:192-258`（字段） | 22 个 `Kind`，含 `CATCH_HANDLER`（`:73`）、`FINALLY_ENTER`（`:81`）、`NULL_CHECK`（`:109`）、`HAVOC`（`:134`）、`UNINTERPRETED`（`:156`）；**没有** `guard` / `labelRef` / `ownerType` |
| `TacFunction` | `IR/TacFunction.java:67-…` | 有 `returnType`（`:72`）、`tryRegions`（`:171`）、四个注解通道（`:123-126`）+ `annotationValues`（`:154`）、`synthetic`（`:190`）；**没有** `drops` / `memberKind` / `parameters` |
| `TryRegion` | `IR/TacFunction.java:749-835` | `tryStart/tryEnd`（`:751`/`:754`）、`catchStart/catchEnd`（`:760`/`:766`）、`catchLabels`（`:774`）、`finallyLabel`（`:781`）、`finallyStart/finallyEnd`（`:787`/`:793`）；**没有** `catchTypes` / `resourceVars` / `syntheticFinally` / `finallyExits` |
| `IrPrecision` | `IR/IrPrecision.java:66-96` | 四档：`SOURCE_FULL` / `SOURCE_LOWERED` / `BYTECODE` / `BYTECODE_SYNTHETIC`；**没有** `SOURCE_DEGRADED` |

### 2.4 引擎侧

| 结构 | 位置 | 与本方案的关系 |
|---|---|---|
| wire → IR 的映射 | `FE/sidecar/SidecarTacMapper.java:75-156`（`toFunction`）、`:159-227`（`toInstruction`） | `tryRegions` **硬编码 `List.of()`**（`:150`）；四个注解通道硬编码空（`:144-147`）；`annotationValues` 硬编码 `Map.of()`（`:154`）；精度只认两档（`:132-135`）；`returnType` **有读**（`:140`） |
| CFG 构建 | `IR/TacCfgBuilder.java:193`（`addTryCompletionEdges`）、`:205`（`buildExceptionSuccessors`）、`:227-235`（try 区域出口）、`:312-320`（catch/finally 边） | 异常边的**能力已经在 IR 里**，缺的只是 wire 字段 ⇒ PHP 的 catch / Python 的 except 可达性是**纯接线问题** |
| 分析顺序 | `DFA/engine/DfaEngine.java:2175`（`orderCacheFor(ctx).analysisOrder(fn)`）、`:2573`（`analysisOrder`） | 从入口出发的 CFG 遍历 ⇒ **没有前驱的块永远不会被 checker 看到** ⇒ catch 体不可达的根因 |
| 摘要应用 | `DFA/engine/DfaEngine.java:2207-2210` | `SummaryApplier.apply` **只对 `Kind.CALL`** ⇒ `NEW` 拿不到构造器摘要 |
| 污点源 | `DFA/taint/TaintedCheckerBase.java:985`（`for (TacOperand.TacVar p : fn.params())`） | **每个形参都是污点源** ⇒ 闭包捕获被提成形参 ⇒ 干净的捕获也会被当污点 |
| 守卫存储 | `DFA/solver/GuardStore.java:35-37`（key 是变量名）、`:54`（`Map<String, List<String>>`）、`:115`（`impliedGuard`） | 变量名契约：`TacOperand.TacVar.name()` 原文 |
| 路径条件 | `DFA/solver/PathCondition.java:106-131`（`implies`）、`:157-165`（`structurallyImplies`） | 吃字符串 mini-DSL |
| Z3 桥 | `DFA/solver/Z3Bridge.java:258-260`（字符串折成长度）、`:266-278`（数建成 `mkInt`）、`:278`（未绑定标识符 ⇒ 自由变量）、`:305`（非零即真） | 浮点被整数化、字符串被折成长度 ⇒ 数值/内容级性质不可判定 |

### 2.5 现状地图的六条「机制性」结论

审计的 184（PHP）+ 177（Python）条丢弃，**不是** 361 个独立缺陷，而是**六个机制**反复作用的结果。
本方案按机制修，不按条目修：

| 机制 | 表现 | 涉及条数（PHP / Python） | 本方案的修法 |
|---|---|---|---|
| M1 **声明面 dispatch 缺 `else`** | PHP `lowerClassBody` 的 `else` 把成员丢给 `lowerStatement`（`PHP/translator.mjs:658-659`）⇒ 落到 `default` ⇒ `UNHANDLED_NODE`；Python `lower_class_body` 的 `else` 同样落到 `lower_statement` 的兜底（`PY/plumb_translator.py:884-885`） | 约 24 / 约 14 | §4：穷尽分派 + 默认形状（不是 `HAVOC`） |
| M2 **wire 缺字段 ⇒ IR 槽位恒空** | `tryRegions` 恒空（`FE/sidecar/SidecarTacMapper.java:150`）⇒ 异常流零边；四个注解通道恒空 ⇒ PHP 的 `#[Attr]` 永不可见 | 约 6 / 约 9 | §9：纯接线（`W2`） |
| M3 **精度由「有没有 problem」推导** | `PHP/translator.mjs:382`、`PY/plumb_translator.py:744-751` 都写死「有 problem ⇒ `SOURCE_LOWERED`」⇒ 语义性丢失被谎报成「结构丢失」 | 全局 | §3.3：改读 `drops` 推导（`IR v2 §IR-B`） |
| M4 **控制流建错 ⇒ 守卫也是错的** | PHP `for` 的 `node.test` 是**数组**、`node.increment` 叫 `increment` 不是 `loop`（`PP/parser/loops.js:97`）；Python 循环 `else` 与 `break` 同块 | 约 12 / 约 14 | §6：按控制流模板重写 |
| M5 **算子表把「没有的算子」近似掉** | `**`→`MUL`、`??`→`LOR`、`<=>`→`EQ`（`PHP/translator.mjs:88-103`；`PY/plumb_translator.py:174-183`） | 约 8 / 约 3 | §5.4：真展开或真算子 |
| M6 **解析器/词法器层面的文件级丢失** | PHP 的 `"$_GET['x']"` 让**整个文件**解析失败（§10.4 给出精确机制） | 1（但波及整个文件） | §10.4：修补 `php-parser` 的 `ST_VAR_OFFSET` |

---

## 3. 完整覆盖原则

### 3.1 原则表

| 编号 | 原则 |
|---|---|
| P1 | 每个分派分支都产出完整 TAC；**不得**保留「不认识 ⇒ `HAVOC` + drop」的兜底。兜底只在**解析器内部错误**时触发（`PHP/translator.mjs:2114-2124` 的 `guard`、`PY/plumb_translator.py:2294-2313` 的 `guard`），并记 `SEMANTICS` 级 drop |
| P2 | 值级保守建模只允许三类：(a) 运行期才确定的值；(b) 外部世界的副作用顺序；(c) 语言本身无静态语义。其余一律建模 |
| P3 | 每次保守建模必须写清：为什么无法静态表达 / 保守形状 / 精度影响（§12） |
| P4 | 精度由 drops 推导（`IR v2 §IR-B`：`SEMANTICS` ⇒ `SOURCE_DEGRADED`；`STRUCTURE` ⇒ `SOURCE_LOWERED`；仅 `VALUE` ⇒ 仍 `SOURCE_FULL`）。现状两处都写死「有 problem ⇒ `SOURCE_LOWERED`」（`PHP/translator.mjs:382`、`PY/plumb_translator.py:744-751`）⇒ 改成读 drops |
| P5 | 每个 `BRANCH` 必带 `guard`（`IR v2 §IR-C`），否则 Z3 拿不到路径条件 |
| P6 | 每个 `LABEL`/`JUMP`/`BRANCH` 必带 `labelRef`（`IR v2 §IR-G`），且 `labelRef.name` 必须等于 `label`/`trueLabel`/`falseLabel` |
| P7 | 语义等价优先于结构保真：能表达就不写 drop（`IR v2 §IR-C` 的风险条：守卫只能是「更精确的信息」，不能是「猜测」） |
| P8 | **不新增 opcode、不新增 operand 变体**（`IR v2 §0.1`、`:0.2`）；新能力一律是既有节点上的可选字段 |
| P9 | **PHP 的对象是命名空间**是**有意的语言决定**（`PHP/translator.mjs:12-17`）；字段/属性**继续用 `ARRAYLOAD`/`ARRAYSTORE`**，另加 `ownerType` 作**附加标注**而不是换指令（IR v2 §IR-E 的 `ownerType` 字段扩展到 `ARRAYLOAD`/`ARRAYSTORE`，见 `IR-AP`） |

### 3.2 与上游文档的关系

本方案**不重设**上游已定稿的东西：
IR 形状取自 `01-TAC-IR-扩展设计.md`；构念族的表达力判定取自 `02-覆盖矩阵与不可支持项.md`；
wire 字段名与能力协商取自 `08-wire-协议-v2.md`；守卫渲染与变量名契约取自 `09-引擎消费与Z3桥.md`；
阶段与门禁取自 `10-实施路线图与验收.md`。
本方案只做两件事：**① 逐条给出 PHP / Python 的降级形状；② 提出 5 条上游没有的补充 IR 提案（§8.2）**。

### 3.3 构造清单的来源

`02` §4 门禁 A 要求「每个前端维护一张**声明种类清单**」。本方案的两张清单就是
**审计台账 PHP A.1–A.20（184 行）与 Python A.1–A.11（177 行）**：

- **附录 A** 给出 PHP 184 条的逐条去向（降级形状 / 无法完整支持 + 理由）；
- **附录 B** 给出 Python 177 条的逐条去向；
- 每条都带 **「解析器 AST 节点 / API」** 一列 ⇒ 实现者能直接知道要新增哪个 `case`；
- 门禁 A 的实现 = 读附录 A/B 的「AST 节点」列 + 断言每个节点都被 `lowerXxx` 的分派覆盖。

**清单要跟着语言版本升**：PHP 侧车的解析器版本锁在 `PHP/package.json:12`（`php-parser` 3.7.0），
Python 侧车的解析器**跟着解释器走**（`PY/plumb_translator.py:34`）⇒ Python 的清单必须覆盖
`ast.TryStar`（3.11+）、`ast.TypeAlias` / `ast.TypeVar`（3.12+）、`node.type_params`（3.12+）这三个
**按解释器版本是否存在**的节点（`10` §6 风险 4）。

---

## 4. 声明面

**分界线（PHP）**：PHP 的对象是**命名空间** ⇒ `$o->name` 与 `$o["name"]` 是同一位置（`PHP/translator.mjs:12-17`）
⇒ 属性读写**继续用 `ARRAYLOAD`/`ARRAYSTORE`**（P9），并附 `ownerType` 标注（`IR-AP`）。
**分界线（Python）**：同上（`PY/plumb_translator.py:13-18`），但 Python 还要额外区分
**类属性**（类体里的赋值）与**实例属性**（`self.x = ...`）⇒ 靠 `ownerType` 区分。
**⇒ 而 R21 的 B3 又加了一条分界线**（`14` §3.41）：**作用域**与**属性**是两件事 ——
属性是**某个对象**的命名空间里的一个键（`ARRAYLOAD`/`ARRAYSTORE`），而作用域是
**跨函数活着的一个位置**（模块的、类的、某个函数的）⇒ 作用域里的名字**不是**属性，
它落在**类的那个机制**上（`FIELDLOAD`/`FIELDSTORE`，接收者是一个合成的作用域所有者），
因为引擎的**跨函数字段污点表**正是「一个跨函数活着的可变位置」这件事已有的实现。

### 4.1 PHP 声明面总表

| 源构念 | 解析器 AST 节点 / API（`php-parser` 3.7.0） | 完整 TAC 表达 | `memberKind` | 需要的 IR 能力 |
|---|---|---|---|---|
| 普通类 | `class`（`PP/ast/class.js`：`name, ext, impl, body, flags`） | 类体 ⇒ **合成 `<clinit>` 函数**（`memberKind=FIELD_INITIALIZER`）+ 每方法一函数；`ext`/`impl` 进 `TacTypeDecl` | `FIELD_INITIALIZER` | `IR v2 §IR-E` |
| 抽象类 | 同上，`flags` 含 `MODIFIER_ABSTRACT` | 同上 + `TacTypeDecl.isAbstract=true` | `FIELD_INITIALIZER` | `IR v2 §IR-E` |
| 最终类 | 同上，`flags` 含 `MODIFIER_FINAL` | 同上 + `TacTypeDecl` 的 `modifiers` 槽位 | `FIELD_INITIALIZER` | `IR v2 §IR-E` |
| `readonly` 类（8.2） | 同上，`flags` 含 `MODIFIER_READONLY` | 同上 + `modifiers` | `FIELD_INITIALIZER` | `IR v2 §IR-E` |
| 接口 | `interface`（`PP/ast/interface.js`：`name, ext, body, attrGroups`） | **不生成函数**（无体）；全部成员进 `TacTypeDecl.members`（`isInterface=true`）；`ext` ⇒ `interfaces` | — | `IR v2 §IR-E` |
| trait | `trait`（`PP/ast/trait.js`：`name, body`） | 方法降级为独立函数（owner = trait 名）+ `TacTypeDecl.traits` 链 | `METHOD` | `IR v2 §IR-E` + `IR-AP` |
| trait `use` | `traituse`（`PP/ast/traituse.js`：`traits, adaptations`） | `traits` ⇒ 被使用的 trait 列表；`adaptations` ⇒ 冲突消解 | — | `IR-AP` |
| trait `insteadof` | `traitprecedence`（在 `traituse.adaptations` 里） | **编译期消解** ⇒ 直接算出最终生效的成员，写进 `TacTypeDecl.members` | — | `IR v2 §IR-E` |
| trait `alias` | `traitalias`（`PP/ast/traitalias.js`：`trait, method, as, flags`） | 同上 | — | `IR v2 §IR-E` |
| 纯枚举（8.1） | `enum`（`PP/ast/enum.js`：`name, valueType, impl, body`） | 枚举 ⇒ 一个 `TacTypeDecl`（`isEnum=true`）+ 每个 `enumcase`（`PP/ast/enumcase.js`）一个类常量 + 方法各自成函数 | `FIELD_INITIALIZER` | `IR v2 §IR-E` |
| 带 backing 的枚举 | 同上，`valueType` 非空 | 同上 + backing 类型进 `TacTypeDecl` | `FIELD_INITIALIZER` | `IR v2 §IR-E` |
| 枚举实现的接口 | 同上，`impl` | 同上 + `interfaces` | — | `IR v2 §IR-E` |
| 枚举方法 | `enum.body` 里的 `method` | 同普通方法 | `METHOD` | 现有 IR 即可 |
| 匿名类 | `new` 的 `what` 是一个 `class`（`PP/ast/new.js`：`what, args`） | 合成稳定类名 `<fqcn>$anon$<n>`（按源位置编号）⇒ 它的体降级为独立函数 + `NEW` 指向它 | `FIELD_INITIALIZER` | `IR v2 §IR-E` |
| 属性（带初值） | `propertystatement`（`PP/ast/propertystatement.js`：`kind, properties, flags`）+ `property`（`PP/ast/property.js`：`name, value, readonly, nullable, type, attrGroups, hooks`） | 在 `<clinit>` 里：`ARRAYSTORE this["name"] = <初值>`，`ownerType` = 类名 | `FIELD_INITIALIZER` | `IR v2 §IR-E` + `IR-AP` |
| 属性（无初值） | 同上，`value` 为 null | 运行期无行为 ⇒ 零指令；名字与类型进 `TacTypeDecl.fields` | — | `IR v2 §IR-E` |
| 静态属性 | 同上 + `flags` 含 `MODIFIER_STATIC` | receiver 是**类对象**（合成变量 `t_cls_<Name>`，类型 `CLASS<Name>`）⇒ `ARRAYSTORE t_cls_Name["x"]` | `FIELD_INITIALIZER` | `IR-AP` |
| 属性可见性 | `property.flags`（`MODIFIER_PUBLIC/PROTECTED/PRIVATE`，`PP/ast/parameter.js` 的 typedef 给出同组常量） | `TacTypeDecl.members` 的 `isPublic`（`IR v2 §IR-E` 的 `TacMemberRef`） | — | `IR v2 §IR-E` |
| `readonly` 属性 | `property.readonly` 布尔字段 | `TacTypeDecl.members` 加 `isReadonly`（`IR-AP`） | — | `IR-AP` |
| 提升为构造器的属性（8.0） | `parameter`（`PP/ast/parameter.js`：`name, type, value, isRef, isVariadic, readonly, nullable, flags, hooks, flagsSet`） | 构造器体里 `ARRAYSTORE this["x"] = x`，`ownerType` = 类名 | `CONSTRUCTOR` | `IR v2 §IR-E` |
| 类常量 | `classconstant`（`PP/ast/classconstant.js`：`kind, constants, flags, nullable, type, attrGroups`） | `ARRAYSTORE t_cls_C["NAME"] = <值>`；`type`（8.3 的有类型类常量）进 `TacTypeDecl` | `FIELD_INITIALIZER` | `IR-AP` |
| 顶层 `const` | `constantstatement`（`PP/ast/constantstatement.js`：`kind, constants`） | 现状即：`MOVE` 到同名局部 + 进模块常量表 | — | 现有 IR 即可 |
| `define()` | `call`（`PP/ast/call.js`：`what, args`） | 现状即：`\define/2` 黑盒 | — | 现有 IR 即可 |
| 方法 | `method`（`PP/ast/method.js`，继承 `PP/ast/function.js`：`name, args, byref, type, nullable`） | 现状即 | `METHOD` | 现有 IR 即可 |
| 静态方法 | `method` + `flags` 含 `MODIFIER_STATIC` | calleeSig **不带 receiver**；`TacTypeDecl.members.isStatic=true` | `METHOD` | `IR v2 §IR-E` + `IR-P` |
| 抽象方法 | `method` + `flags` 含 `MODIFIER_ABSTRACT` | 无体 ⇒ **不生成函数**，进 `TacTypeDecl.members` | — | `IR v2 §IR-E` |
| 最终方法 | `method` + `flags` 含 `MODIFIER_FINAL` | `TacTypeDecl.members` 的 `modifiers` | — | `IR-AP` |
| 方法可见性 | `method.flags` | `TacTypeDecl.members.isPublic` | — | `IR v2 §IR-E` |
| 构造函数 | `method` 且 `name` = `__construct` | **改名并签名对齐为 `<init>`**：`signature = <fqcn>.<init>(<参数类型>):<返回类型>`，`memberKind=CONSTRUCTOR` ⇒ `NEW` 的 calleeSig 直接命中 | `CONSTRUCTOR` | `IR v2 §IR-E` |
| 析构函数 | `method` 且 `name` = `__destruct` | 降级为独立函数；**调用时机是运行期**（对象销毁 / 请求结束）⇒ 见 §12 的 `PHP4` | `DESTRUCTOR`（`IR-AP`） | `IR-AP` |
| `__get` / `__set` / `__isset` / `__unset` | `method`，`name` ∈ 该集合 | 属性**不在** `TacTypeDecl.fields` 里 ⇒ 读降级为 `CALL <Class>.__get/2`（args[0]=receiver, args[1]=键），写为 `CALL <Class>.__set/3` | `METHOD` | `IR v2 §IR-E` |
| `__call` / `__callStatic` | `method` | 方法**不在** `TacTypeDecl.members` 里 ⇒ `CALL <Class>.__call/3` / `CALL <Class>.__callStatic/3` | `METHOD` | `IR v2 §IR-E` |
| `__invoke` | `method` | `$obj(...)` ⇒ `CALL <Class>.__invoke/n`（`args[0]`=receiver） | `METHOD` | `IR-P` |
| `__toString` | `method` | 对象参与字符串拼接/插值/`echo` ⇒ `CALL <Class>.__toString/1` | `METHOD` | 现有 IR 即可 |
| `__clone` | `method` + `clone`（`PP/ast/clone.js`：`what, properties`） | `clone $x` ⇒ `NEW <Class>.__clone/0` + 逐字段 `ARRAYSTORE`（字段表来自 `TacTypeDecl.fields`） | `METHOD` | `IR v2 §IR-E` |
| `__sleep` / `__wakeup` | `method` | `serialize($x)` ⇒ `CALL <Class>.__sleep/1`；`unserialize($x)` ⇒ `CALL <Class>.__wakeup/1` | `METHOD` | 现有 IR 即可 |
| 属性（PHP 8.4 的 property hooks） | `property.hooks`（`PP/ast/property.js` 的 `hooks` 字段） | 每个 hook ⇒ 一个 `memberKind=GETTER/SETTER` 的函数；读写路由到它 | `GETTER`/`SETTER` | `IR v2 §IR-E` |
| 函数 | `function`（`PP/ast/function.js`） | 现状即 | `METHOD` | 现有 IR 即可 |
| 按引用返回的函数 | `function.byref` 布尔字段 | `TacFunction.parameters` 的 `byRef`（`IR-AO`） | `METHOD` | `IR-AO` |
| 可选参数 | `parameter.value` | 见 §4.5 的两级默认参数方案 | — | `IR-AO` |
| 可变参参数 | `parameter.isVariadic` 布尔字段 | 形参类型 = `ARRAY(component)`；调用点把剩余实参收成数组 | — | `IR-AO` + `IR-AR` |
| 按引用参数 | `parameter.isRef` 布尔字段 | `IR-AO` 的 `byRef` + 引擎侧别名 | — | `IR-AO` |
| 闭包 | `closure`（`PP/ast/closure.js`：`args, byref, uses, type, nullable, isStatic`） | 见 §5.3 | `METHOD` | `IR-P` |
| 箭头函数 | `arrowfunc`（同 `closure` 形状） | 见 §5.3 | `METHOD` | `IR-P` |
| 属性 `#[Attr]`（8.0） | `attrgroup`（`PP/ast/attrgroup.js`：`attrs`）+ `attribute`（`PP/ast/attribute.js`：`name, args`）；挂在 `class.attrGroups` / `method` / `property.attrGroups` / `parameter` / `classconstant.attrGroups` | 走 IR **已有**的四个注解通道 + `annotationValues`（`IR v2 §IR-E`），只需 wire 字段 | — | `IR v2 §IR-E` + §9 |

### 4.2 Python 声明面总表

| 源构念 | 解析器 AST 节点 / API（CPython `ast`） | 完整 TAC 表达 | `memberKind` | 需要的 IR 能力 |
|---|---|---|---|---|
| 模块 | `ast.Module`（`.body`） | 合成 `<module>` 函数（现状已是，`PY/plumb_translator.py:328`） | `MODULE` | 现有 IR 即可 |
| 函数 | `ast.FunctionDef`（`.name, .args, .body, .decorator_list, .returns, .type_params`） | 现状即 | `METHOD` | 现有 IR 即可 |
| 异步函数 | `ast.AsyncFunctionDef`（同字段） | 降级成同步函数 + `Effect.SUSPEND` 标记（`IR v2 §IR-L`）；**求值顺序**不建模 ⇒ §12 的 `PY4` | `METHOD` | `IR v2 §IR-L` |
| 嵌套函数 | `ast.FunctionDef` 出现在 `ast.FunctionDef.body` 里 | 现状即（独立函数） | `METHOD` | 现有 IR 即可 |
| lambda | `ast.Lambda`（`.args, .body`） | 见 §5.3 | `METHOD` | `IR-P` |
| 生成器函数 | `ast.FunctionDef` 且体内有 `ast.Yield` | 状态机展开（§5.3） | `METHOD` | 现有 IR 即可 |
| 类 | `ast.ClassDef`（`.name, .bases, .keywords, .body, .decorator_list, .type_params`） | 类体 ⇒ 合成 `<clinit>` 函数 + 每方法一函数；**`bases`/`decorator_list` 必须被读** | `FIELD_INITIALIZER` | `IR v2 §IR-E` |
| 类的基类 | `ast.ClassDef.bases` | `TacTypeDecl.superType`（单基类）/ `interfaces`（多基类） | — | `IR v2 §IR-E` |
| 元类 | `ast.ClassDef.keywords`（`metaclass=...`） | `TacTypeDecl` 的 `metaclass` 字段（`IR-AP`） | — | `IR-AP` |
| 类装饰器 | `ast.ClassDef.decorator_list` | 装饰器表达式**在类定义处求值** ⇒ 降级进 `<clinit>` 的流；装饰器名进 `TacTypeDecl.annotations` | — | `IR v2 §IR-E` + §9 |
| 方法 | `ast.FunctionDef` 且父节点是 `ast.ClassDef` | 现状即 | `METHOD` | 现有 IR 即可 |
| 静态方法 | `ast.FunctionDef` + `decorator_list` 里的 `ast.Name("staticmethod")` | **解析器里没有 `isStatic` 标志** ⇒ 必须读装饰器列表判定 ⇒ 形参**不减接收者**（见 §4.4） | `METHOD` | `IR-AO` |
| 类方法 | `ast.FunctionDef` + 装饰器 `ast.Name("classmethod")` | 接收者是 `cls` ⇒ `args[0]` 必须放**类对象**而不是实例 | `METHOD` | `IR-AO` |
| 属性（getter） | `ast.FunctionDef` + 装饰器 `ast.Name("property")` | 读 `obj.v` ⇒ `CALL <Owner>.v/0`（args[0]=obj） | `GETTER` | `IR v2 §IR-E` |
| 属性（setter） | `ast.FunctionDef` + 装饰器 `ast.Attribute(value=Name("x"), attr="setter")` | 写 `obj.v = x` ⇒ `CALL <Owner>.v/1` | `SETTER` | `IR v2 §IR-E` |
| 属性（deleter） | 同上，`attr="deleter"` | `del obj.v` ⇒ `CALL <Owner>.v/1` | `SETTER`（`IR-AP` 增 `DELETER`） | `IR-AP` |
| 实例属性 | `ast.Assign`，目标是 `ast.Attribute(value=Name("self"), attr="x")` | `ARRAYSTORE self["x"]`，`ownerType` = 类名 | — | `IR-AP` |
| 类属性 | `ast.Assign`，目标是 `ast.Name` 且出现在 `ast.ClassDef.body` | **作用域槽**：`FIELDSTORE Scope_class_<模块>.<类>["x"]` 的等价物 —— 接收者是**类的作用域所有者**（`FIELDSTORE`，`fieldName` = 名字）⇒ **R21 的 B3 改的**（`14` §3.41）：原来是 `ARRAYSTORE t_cls_C["x"]`，而它**跨不过方法** | `FIELD_INITIALIZER` | `IR-AP`（B3 用的是**既有** IR，没有新增） |
| `__slots__` | `ast.Assign`，目标是 `ast.Name("__slots__")` | 运行期无行为 ⇒ 零指令；名字进 `TacTypeDecl.fields` | — | `IR v2 §IR-E` |
| `__all__` | `ast.Assign`，目标是 `ast.Name("__all__")` | 同上 | — | 现有 IR 即可 |
| `@dataclass` | `ast.ClassDef.decorator_list` 里的 `ast.Name("dataclass")` | **解析器不区分 `dataclass` 与别的装饰器** ⇒ 靠名字判定 ⇒ 合成 `__init__`：`FIELDSTORE` 每个字段 = 形参 | `CONSTRUCTOR` | `IR v2 §IR-E` |
| `@dataclass(init=False/frozen=True/kw_only=True)` | 同上，装饰器是 `ast.Call`（`ast.Call.args` / `ast.Call.keywords`） | 关键字实参按名绑定（`IR-AR`）⇒ `init=False` ⇒ 不合成 `__init__`；`frozen=True` ⇒ `TacTypeDecl` 的 `isFrozen`（`IR-AP`） | `CONSTRUCTOR` | `IR-AR` + `IR-AP` |
| `NamedTuple` / `TypedDict` / `Enum` / `IntEnum` / `StrEnum` / `Flag` / `Protocol` | `ast.ClassDef.bases` 里的 `ast.Name(...)` | `TacTypeDecl.isEnum` / `isProtocol` / `isTypedDict`（`IR-AP`）；`NamedTuple` 的默认值是类属性 | — | `IR-AP` |
| `typing.Protocol` / `runtime_checkable` | `ast.ClassDef.bases` / `decorator_list` | 同上 | — | `IR-AP` |
| `__init__` | `ast.FunctionDef`，`name == "__init__"` | **改名并签名对齐为 `<init>`**：`signature = <Class>.<init>(...)`；`NEW` 的 calleeSig = `<Class>.<init>/n`（**去掉 `<unknown>.` 前缀**） | `CONSTRUCTOR` | `IR-P` + `IR v2 §IR-E` |
| `__new__` | `ast.FunctionDef`，`name == "__new__"` | `memberKind=CONSTRUCTOR` 的第二个候选（构造器选择是运行期的）⇒ 见 §12 的 `PY2` | `CONSTRUCTOR` | `IR-AP` （`B` 档：记 `drop(STRUCTURE, "constructor.selection")`） |
| `__init_subclass__` / `__class_getitem__` | `ast.FunctionDef` | 降级为独立函数；`K[int]` ⇒ `CALL <Owner>.__class_getitem__/1` | `METHOD` | 现有 IR 即可 |
| `__getattr__` / `__getattribute__` / `__setattr__` / `__delattr__` | `ast.FunctionDef` | 属性**不在** `TacTypeDecl.fields` 里 ⇒ 读/写路由到对应 dunder | `METHOD` | `IR v2 §IR-E` |
| `__call__` | `ast.FunctionDef` | `obj(...)` ⇒ `CALL <Owner>.__call__/n` | `METHOD` | `IR-P` |
| `__eq__` / `__hash__` / `__repr__` | `ast.FunctionDef` | `a == b` ⇒ `CALL <Owner>.__eq__/2`；`hash(x)` ⇒ `CALL <Owner>.__hash__/1` | `METHOD` | 现有 IR 即可 |
| `__enter__` / `__exit__` | `ast.FunctionDef` | `with` ⇒ `CALL <Owner>.__enter__/1` + 体 + `CALL <Owner>.__exit__/3`（`IR v2 §IR-F` 的 `resourceVars`） | `METHOD` | `IR v2 §IR-F` |
| `__aiter__` / `__anext__` | `ast.FunctionDef` | `async for` ⇒ 迭代协议（§6.9） | `METHOD` | `IR-P` |
| 元类的 `__call__` | `ast.FunctionDef` | 构造 ⇒ 若元类声明了 `__call__` 则 `CALL <Meta>.__call__/n`（构造器的选择是运行期的 ⇒ §12 的 `PY2`） | `METHOD` | `IR-AP` （`B` 档：记 `drop(STRUCTURE, "constructor.selection")`） |
| 参数标注 | `ast.arg.annotation`（`ast.Name` / `ast.Attribute` / `ast.Subscript` / `ast.Constant`） | 进 `TacType`（§7.2）+ `paramAnnotations` | — | `IR v2 §IR-K` + `IR-E` |
| 返回标注 | `ast.FunctionDef.returns` | 进 `TacType.returnType`（现状已有 `PY/plumb_translator.py:742`） | — | 现有 IR 即可 |
| 变量标注 | `ast.AnnAssign.annotation` | 进 `TacTypeDecl` 的类型表 + `fieldAnnotations` | — | `IR v2 §IR-K` |
| 类型参数（3.12） | `ast.FunctionDef.type_params` / `ast.ClassDef.type_params`（元素是 `ast.TypeVar` / `ast.ParamSpec` / `ast.TypeVarTuple`） | `TacTypeDecl.typeParameters`（`IR v2 §IR-N`） | — | `IR v2 §IR-N` |
| `type` 别名（3.12） | `ast.TypeAlias`（`.name, .value, .type_params`） | 运行期无对应物 ⇒ 零指令 + `STRUCTURE` 级 drop（`IR v2 §IR-N` 的 `A8` 口径） | — | `IR v2 §IR-N` |
| `TypeVar` / `Generic` | `ast.TypeVar`（3.12）/ `ast.ClassDef.bases` 里的 `Generic[T]` | `TacType.bound` + `TacTypeDecl.typeParameters` | — | `IR v2 §IR-K` + `IR-N` |

### 4.3 字段 / 属性 / 构造器的统一形状（两语言共用）

```
// 声明侧（合成 <clinit>，memberKind=FIELD_INITIALIZER）
  t_cls_C   = NEW CLASS<C>                  // 类对象（静态成员与类常量的 receiver）
  t_this    = <receiver>                    // 实例对象
  ARRAYSTORE t_this["name"] = <init value>  ; ownerType = C
  ARRAYSTORE t_cls_C["CONST"] = <value>     ; ownerType = C

// 构造器（memberKind=CONSTRUCTOR，signature = C.<init>(T1,T2):R）
  t_this    = <receiver>
  ARRAYSTORE t_this["promoted"] = promoted ; ownerType = C
  <构造器体>

// 调用点
  NEW  calleeSig = C.<init>/2   ctorType = CLASS<C>   args = [a1, a2]
```

**关键修正（相对现状）**：今天两个语言都把**类体**命名成 `<init>`、把**真正的构造器**命名成 `__construct` / `__init__`，
于是 `NEW` 的 calleeSig 指向的是「类体」而不是「构造器」：

- PHP：`PHP/translator.mjs:633` 的类体函数签名是 `${qualified}.<init>():any`，
  `PHP/translator.mjs:1768` 的 `NEW` calleeSig 是 `${className}.<init>/${args.length}` ⇒ **两者同名**；
  而真正的构造器函数名是 `__construct`、签名是 `${qualified}::__construct(...)` ⇒ **构造器体永远不会被链接**。
- Python：`PY/plumb_translator.py:860-861` 的类体函数签名是 `Class.<init>():any`，
  `PY/plumb_translator.py:2038-2039` 的 `NEW` calleeSig 是 `<unknown>.Class.<init>/N` ⇒ **owner 不同 ⇒ 也不链接**。

⇒ 本方案统一为：**类体函数改名 `<clinit>`（`memberKind=FIELD_INITIALIZER`）**，
**真正的构造器改名 `<init>`（`memberKind=CONSTRUCTOR`）**，两语言一致。

### 4.4 隐式接收者与 arity（本方案最关键的一条修正）

**PHP 侧**：`PHP/translator.mjs:1913-1919`（`arityOf`）用的是**声明形参个数**
（`PHP/translator.mjs:434-436` `declaredArityOf`），**不减接收者**；
PHP 的方法**必有** `$this`（`PHP/translator.mjs:366` 把 `this` 绑到外层类），
但 PHP 的 `calleeSig` 形状是 `Owner::method/arity`（`PHP/translator.mjs:1797`），
**`$this` 从来不在 `args` 里** ⇒ arity 与实参个数天然一致 ⇒ **PHP 侧没有 arity 错位**。
真正的问题在别处：PHP 的方法调用**没有 receiver 槽**（`args[0]` 是命名空间常量，见 `PHP/translator.mjs:1791`），
而 `IR v2 §IR-P` 要求「接收者一律显式作为 `args[0]`」⇒ **PHP 需要一次 receiver 槽对齐**（见 §6.10）。

**Python 侧（这是审计与本方案都要正面回答的一条）**：`PY/plumb_translator.py:2264-2276`（`declared_arity`）：

```python
def declared_arity(self, definition):
    args = definition.args
    count = len(args.posonlyargs) + len(args.args)
    if args.vararg is not None:
        count += 1
    if id(definition) in self.method_nodes:
        count -= 1
    return max(count, 0)
```

**明确结论（逐条判定）**：

| 情形 | `method_nodes` 是否含该节点 | 减 1 是否成立 | 结论 |
|---|---|---|---|
| **顶层函数** | 否（`PY/plumb_translator.py:376-384`：`walk_bindings` 只有 `isinstance(parent, ast.ClassDef)` 才 `add`） | 不减 | ✅ **成立**：顶层函数的 arity = 声明形参数，正确 |
| **普通实例方法** `def m(self, a)` | 是 | 减 | ✅ **成立**：接收者 `self` 不是用户实参 |
| **`@classmethod`** `def m(cls, a)` | 是 | 减 | ⚠️ **arity 对、语义错**：`cls` **确实**是隐式接收者，减 1 对；但 `args[0]` 放的是**实例**（`PY/plumb_translator.py:2207` 的 `receiver` 来自 `type_of_receiver`）而不是**类对象** ⇒ 类永远绑不到 `cls` |
| **`@staticmethod`** `def m(a)` | **是**（装饰器不影响 `walk_bindings` 的判定） | **减** | ❌ **不成立**：`staticmethod` **没有接收者** ⇒ 一参数的方法被算成 arity 0 ⇒ calleeSig = `K.stat/0`，而签名是 `K.stat(cmd):any` ⇒ **arity 与签名不一致 ⇒ 调用解析不到**。**这既是漏报来源（跨方法边界的污点断掉），也是误报来源（`self.stat(cmd)` 这种错调用被当成合法的一参数调用）** |

⇒ **修法**：把「有没有接收者」从「是不是类体里的函数」改成「**是不是 `staticmethod`**」：

```
接收者规则（IR-AO 的细化，Python 专用）：
  是类体里的 ast.FunctionDef
    且 decorator_list 含 ast.Name(id="staticmethod")   ⇒ 无接收者，arity 不减，args[0] 放模块名
    否则                                            ⇒ 有接收者，arity 减 1
  顶层 / 嵌套函数                                   ⇒ 无接收者，arity 不减
```

**另外两条 arity 相关的缺陷（本方案新发现，审计未记录）**：

1. **`declared_arity` 与 `parameters_of` 数的是两套东西。**
   `PY/plumb_translator.py:804-813`（`parameters_of`）返回
   `posonlyargs + args + [vararg] + kwonlyargs + [kwarg]`（**含**关键字专用与 `**kwargs`）；
   `PY/plumb_translator.py:2270-2273`（`declared_arity`）只数
   `posonlyargs + args + [vararg]`（**不含** `kwonlyargs` 与 `kwarg`）。
   ⇒ `def f(a, *, b=1, **kw)` 的 calleeSig arity = 1，签名却是 `f(a,b,kw):any`（3 个形参）
   ⇒ **任何带关键字专用形参或 `**kwargs` 的函数，calleeSig 的 arity 与签名永不相等 ⇒ 调用解析不到**。
2. **`defined_methods` 按方法名索引 ⇒ 同名方法跨类串味。**
   `PY/plumb_translator.py:381` 是 `self.defined_methods.setdefault(node.name, node)`（**只按名字**），
   而 `PY/plumb_translator.py:2257-2259`（`arity_of_name`）查的正是这张表
   ⇒ 两个类里同名方法，**先声明的那个**的 arity 会被套用到另一个类的调用上。
   对照：`PY/plumb_translator.py:1960`（`return_type_of_callee`）查的是 `class_methods`（按 `(类, 方法)` 索引）
   ⇒ **返回类型是按类的、arity 不是** ⇒ 同一个调用的两个属性用了两套规则。
   ⇒ 修法：`arity_of_name` 必须按 `(receiver 的类, 方法名)` 查；查不到再退回按名字（并记 `STRUCTURE` 级 drop）。

### 4.5 默认参数的两级方案（PHP）

PHP 的默认参数在**调用时**、在**被调用者的作用域**里求值。这带来两个可分别处理的事实：

| 情形 | 默认表达式的自由名字 | 完整降级形状 | 是否需要 `IR-AO` |
|---|---|---|---|
| **A 级**：默认值不引用被调用者作用域独有的名字（字面量、类常量、全局函数、**调用点已知的值**） | 无 | **调用点求值**：调用点把默认表达式的降级内联到调用之前，作为第 i 个实参传入 | 否 |
| **B 级**：默认值引用被调用者的**前序形参**或**函数内 `static` 变量** | 有 | **被调用者 prologue 求值**：prologue 里 `t_def_i = <默认表达式>`（每次调用都算，时机正确）；被调用者要知道「第 i 个实参传了没有」⇒ 需要 `argCount` | **是** |

B 级的完整形状（`IR-AO` 的 `__argc` 合成形参）：

```
// 调用点（省略了第 2 个实参）
  t_def_b   = <B 级默认表达式>          // A 级：调用点求值
  CALL  C.m/2   args = [recv, a1, t_def_b]   argCount = 2

// 被调用者 prologue（知道 argCount）
  t_argc    = <合成形参 __argc>           // IR-AO：调用点把 argCount 作为第一个显式实参传入
  BRANCH (t_argc > 1) -> L_GIVEN, L_DEFAULT ; guard = Compare(t_argc, GT, 1)
L_GIVEN:    MOVE b = args[1] ; JUMP L_BODY
L_DEFAULT:  MOVE b = t_def_b              // prologue 求出的默认值
L_BODY:     <函数体>
```

⇒ **`__argc` 不计入 `calleeSig` 的 arity**（`IR v2 §IR-P` 的「arity 只数显式实参」需要这样细化：
「只数**用户可见的**显式实参；合成的实参（`__argc`、闭包捕获）不计入 arity，但计入 `args`」）⇒ 这是 `IR-AO` 的一部分。

### 4.6 装饰器 / 注解（两语言）

| 维度 | PHP `#[Attr]` | Python `@dec` |
|---|---|---|
| 解析器节点 | `attrgroup.attrs[]` → `attribute(name, args)`；挂在 `class.attrGroups` / `method` / `property.attrGroups` / `parameter` / `classconstant.attrGroups` | `ast.FunctionDef.decorator_list` / `ast.ClassDef.decorator_list`；元素是 `ast.Name` / `ast.Attribute` / `ast.Call` |
| 求值时机 | **类定义时**（属性在声明它的类/函数被定义时求值） | **函数/类定义时**（装饰器表达式在定义处求值，装饰器**调用**也在定义处） |
| 完整降级 | 装饰器表达式降级进「定义处」的流（类体 ⇒ `<clinit>`；函数 ⇒ 外层流）；名字 + 参数进 `annotationValues` | 同左：`PY/plumb_translator.py:990-1015` 已经在定义处发 `CALL <python>.decorator/1` ⇒ **但装饰器的身份丢了**（见下） |
| **必须修的** | 读 `attrGroups` | **装饰器的身份必须进 callee key**：`ast.Name` 装饰器现在**只**产生一条 `CALL <python>.decorator/1`（`PY/plumb_translator.py:997-1007`），名字 `login_required` 在 TAC 里**一个字都没有** ⇒ 改成 `CALL <owner>.<decoratorName>/n`，`owner` 由 `ast.Name` ⇒ 模块名、`ast.Attribute` ⇒ 接收者的 owner 决定 |
| 框架入口点 | `#[Route]` ⇒ 路由 | `@app.route` / `@login_required` / `@task` / `@receiver` ⇒ 框架入口点（`DFA/taint/FrameworkEntryPoints.java` 是现成的消费方） |

⇒ **Python 装饰器身份丢失是本轮要修的第一批「静默丢弃」**：`IR v2 §IR-E` 的注解通道 +
`calleeSig` 的规范化（`IR v2 §IR-P`）已经够用，**不需要新 IR**。

---

## 5. 表达式面

### 5.1 PHP 表达式面总表

| 源构念 | 解析器 AST 节点 / API（`php-parser` 3.7.0） | 完整 TAC 表达 | 需要的 IR 能力 |
|---|---|---|---|
| 变量 | `variable`（`PP/ast/variable.js`：`name, curly`） | `MOVE` / `ARRAYLOAD` 的目标变量名就是源码名（`PHP/translator.mjs:1058-1059`） | 现有 IR 即可 |
| 变量变量 `$$x` | 同上，`name` 是一个**对象**而不是字符串，`curly=false` | 见 §5.5 | `IR-AS` |
| `${$x}` | 同上，`curly=true` | 同 §5.5（`curly` 用来区分两种写法） | `IR-AS` |
| `$this` | `variable`，`name = "this"` | 现状即；作为 receiver 时恒 `args[0]` | `IR-P` |
| 超全局 | `variable`，`name ∈ {_GET,_POST,…}` | 现状即；来源标签进 `param:`/`global:` 通道 | 现有 IR 即可 |
| 字面量（数字） | `number`（`PP/ast/number.js`：`value, raw`） | `CONST INT/LONG/DOUBLE`（`PHP/translator.mjs:1406-1416`） | `IR v2 §IR-D` |
| 字面量（字符串） | `string`（`PP/ast/string.js`：`isDoubleQuote, value, unicode, raw`） | `CONST STRING` | 现有 IR 即可 |
| 字面量（布尔/空） | `boolean`（`value, raw`）/ `null` | `CONST BOOL` / `CONST NULL` | 现有 IR 即可 |
| 魔术常量 | `magic`（`PP/ast/magic.js`：`value, raw`） | `CONST STRING`（现状即） | 现有 IR 即可 |
| 插值字符串 | `encapsed`（`PP/ast/encapsed.js`：`value, raw, type`）+ `encapsedpart`（`expression, syntax, curly`） | 各 part 用 `BINOP ADD` 连接（`PHP/translator.mjs:1218-1259`）；`type == "shell"` ⇒ `<php>.shell/1` | 现有 IR 即可 |
| nowdoc | `nowdoc`（`PP/ast/nowdoc.js`：`value, raw, label`） | `CONST STRING`（**节点自带 `value`，现在被丢成 `HAVOC`**） | 现有 IR 即可 |
| 数组字面量 | `array`（`PP/ast/array.js`：`shortForm, items`）+ `entry`（`PP/ast/entry.js`：`key, value, byRef, unpack`） | `NEWARRAY` + 逐项 `ARRAYSTORE`（`PHP/translator.mjs:1268-1291`） | 现有 IR 即可 |
| 数组展开 `[...$a]` | `entry.unpack = true` | **展开的元素个数运行期才定** ⇒ `IR-AR`（`ARRAYSTORE` 的展开形态） | `IR-AR` |
| 嵌套数组 | `entry.value` 又是 `array` | 递归（现状即） | 现有 IR 即可 |
| 解构 `[$a,$b]` / `list(...)` | `list`（`PP/ast/list.js`：`items, shortForm`） | `ARRAYLOAD` 逐位 + 写（`PHP/translator.mjs:1517-1534`） | 现有 IR 即可 |
| 算术/比较/位运算 | `bin`（`PP/ast/bin.js`：`type, left, right`） | `BINOP`（`PHP/translator.mjs:49-78`） | 现有 IR 即可 |
| 幂 `**` | `bin`，`type == "**"` | **不再近似成 `MUL`**：真幂需要新算子或展开 | 见 §5.4 |
| 拼接 `.` | `bin`，`type == "."` | `BINOP ADD`（现状即） | 现有 IR 即可 |
| 三路比较 `<=>` | `bin`，`type == "<=>"` | **用现有算子完整表达**：`t_lt = (a<b)`；`t_gt = (a>b)`；`t_res = t_lt ? -1 : (t_gt ? 1 : 0)` | `IR v2 §IR-C` |
| 严格比较 `===`/`!==` | `bin` | `BINOP EQ/NE`（现状即） | 现有 IR 即可 |
| 宽松比较 `==`/`!=` | `bin` | `BINOP EQ/NE` + 引擎侧 PHP 强制转换规则（`IR v2 §IR-Q`） | `IR-Q` |
| 空合并 `??` | `bin`，`type == "??"` | **分支链**：`BRANCH (a != NULL)` → 取 a，否则取 b（`IR v2 §IR-C`） | `IR-C` |
| 空合并赋值 `??=` | `assign`，`operator == "??="` | 分支链：条件写（`PHP/translator.mjs:125` 现在无条件写） | `IR-C` |
| 三元 `? :` | `retif`（`PP/ast/retif.js`：`test, trueExpr, falseExpr`） | 现状即（`PHP/translator.mjs:1304-1326`） | 现有 IR 即可 |
| 短路三元 `?:` | `retif`，`trueExpr` 为 null | 现状即（`:1318` 用 test 自身） | 现有 IR 即可 |
| 逻辑 `&&`/`\|\|`/`and`/`or`/`xor` | `bin`，`type ∈ {&&,\|\|,and,or,xor}` | **短路展开成 `BRANCH` 链**（PHP 的 `and`/`or` 优先级低于 `=`，解析器已处理） | `IR-C` |
| 位运算 `&`/`\|`/`^`/`~`/`<<`/`>>` | `bin` / `unary`（`PP/ast/unary.js`：`type, what`） | `BINOP`/`UNARYOP`（现状即） | 现有 IR 即可 |
| 复合赋值 `+=` 等 | `assign`，`operator` | 读 + `BINOP` + 写（现状即，`PHP/translator.mjs:1424-1449`） | 现有 IR 即可 |
| `++` / `--` | `pre`（`PP/ast/pre.js`）/ `post`（`PP/ast/post.js`），均继承 `operation` | **读 + `BINOP ADD/SUB` + 写**；前缀返回新值、后缀返回旧值（**现在完全没写回**） | 现有 IR 即可 |
| 引用 `&$x` | `byref`（`PP/ast/byref.js`：`what`） | 别名 ⇒ `IR-AS` | `IR-AS` |
| 引用赋值 `$a = &$b` | `assignref`（`PP/ast/assignref.js`：`left, right`） | 别名 ⇒ `IR-AS` | `IR-AS` |
| 调用展开 `f(...$a)` | `variadic`（`PP/ast/variadic.js`：`what`） | 剩余实参 ⇒ `IR-AR` | `IR-AR` |
| 一等可调用 `foo(...)` | `variadicplaceholder`（`PP/ast/variadicplaceholder.js`） | **不是调用** ⇒ 取可调用引用（不发 `CALL`）；调用点另发 `CALL` | `IR-P` |
| 命名实参 | `namedargument`（`PP/ast/namedargument.js`：`name, value`） | **按形参名重排** ⇒ `IR-AR`（需要形参名表 ⇒ `IR-AO`） | `IR-AO` + `IR-AR` |
| 属性读 `$o->x` | `propertylookup`（`PP/ast/propertylookup.js`：`what, offset`） | `ARRAYLOAD o["x"]`（现状即，`PHP/translator.mjs:1627-1639`） | 现有 IR + `IR-AP`（`ownerType` 标注） |
| 空安全属性读 `$o?->x` | `nullsafepropertylookup`（同形状） | `BRANCH (o != NULL)` + 读，否则短路（`IR-C`） | `IR-C` |
| 下标读 `$a[$k]` | `offsetlookup`（`PP/ast/offsetlookup.js`：`what, offset`） | `ARRAYLOAD`（现状即） | 现有 IR 即可 |
| 静态读 `C::$x` | `staticlookup`（`PP/ast/staticlookup.js`：`what, offset`） | **现在完全不发指令**（`PHP/translator.mjs:1661-1665` 返回一个 `varOp`）⇒ 改：`ARRAYLOAD t_cls_C["x"]` | `IR-AP` |
| `instanceof` | `bin`，`type == "instanceof"` | `INSTANCEOF`（现状即，`PHP/translator.mjs:1588-1600`）；**`self`/`static` 现在解析成类名 `\`** ⇒ 修 `qualifiedNameOf` | 现有 IR 即可 |
| 构造 `new C(...)` | `new`（`PP/ast/new.js`：`what, args`） | `NEW`（现状即） | `IR-P` |
| 克隆 `clone $x` | `clone`（`PP/ast/clone.js`：`what, properties`） | `NEW <Class>.__clone/0` + 逐字段拷贝 | `IR-E` |
| `isset(...)` | `isset`（`PP/ast/isset.js`：`variables`） | 「键存在」⇒ `BRANCH` + `PropertyPresence` 守卫（`IR-AQ`） | `IR-AQ` |
| `empty(...)` | `empty`（`PP/ast/empty.js`：`expression`） | 同 `isset` + 真值规则（`IR v2 §IR-H` 的 `PHP_LIKE`） | `IR-AQ` + `IR-H` |
| `unset(...)` | `unset`（`PP/ast/unset.js`：`variables`） | **删除键** ⇒ `ARRAYSTORE` 带删除标志（`IR-AQ`） | `IR-AQ` |
| `@expr` | `silent`（`PP/ast/silent.js`：`expr`） | 现状即（值 = 操作数） | 现有 IR 即可 |
| `(T)expr` | `cast`（`PP/ast/cast.js`：`type, raw, expr`） | `UNARYOP CAST`（现状即） | 现有 IR 即可 |
| `echo` | `echo`（`PP/ast/echo.js`：`expressions, shortForm`） | **输出 sink** ⇒ `CALL <php>.echo/n`（现状只求值，不发指令 ⇒ 无 finding） | 现有 IR + catalogue 条目 |
| `print` | `print`（`PP/ast/print.js`：`expression`） | 同 `echo` | 同上 |
| `include` / `require` | `include`（`PP/ast/include.js`：`once, require, target`） | **四种模式可区分**（`once`/`require` 两个布尔字段）：`<php>.include/1`、`<php>.include_once/1`、`<php>.require/1`、`<php>.require_once/1` | 现有 IR 即可 |
| `eval` | `eval`（`PP/ast/eval.js`：`source`） | `UNINTERPRETED <php>.eval/1`（现状即）⇒ 内容不可静态表达 ⇒ §12 的 `PHP5` | 现有 IR 即可 |
| `exit` / `die` | `exit`（`PP/ast/exit.js`：`expression, useDie`） | 终止当前执行单元 ⇒ `THROW`（`IR-J` 的 `ctorType`） | `IR-J` |
| 类常量 `C::X` | `staticlookup` | `ARRAYLOAD t_cls_C["X"]` | `IR-AP` |
| `self::` / `static::` / `parent::` | `staticlookup` 的 `what` 是 `selfreference` / `staticreference` / `parentreference`（各带 `raw`） | `self` ⇒ 声明类；`parent` ⇒ `TacTypeDecl.superType`；`static` ⇒ 声明类 + `lateStaticBinding`（运行期类不可知 ⇒ §12 的 `PHP1`） | `IR-AP` （`B` 档：记 `drop(SEMANTICS, "lateStaticBinding")`） |
| 生成器 `yield` | `yield`（`PP/ast/yield.js`）/ `yieldfrom`（`PP/ast/yieldfrom.js`） | 状态机展开（§5.3） | 现有 IR 即可 |
| `match` | `match`（`PP/ast/match.js`：`cond, arms`）+ `matcharm`（`PP/ast/matcharm.js`：`conds, body`） | 分派链 + 无命中 ⇒ `THROW`（`IR-J`） | `IR-C` + `IR-J` |
| `fn() =>` / `function(){}` | `arrowfunc` / `closure` | 见 §5.3 | `IR-P` |

### 5.2 Python 表达式面总表

| 源构念 | 解析器 AST 节点 / API（CPython `ast`） | 完整 TAC 表达 | 需要的 IR 能力 |
|---|---|---|---|
| 字面量 | `ast.Constant`（`.value`） | `CONST INT/LONG/DOUBLE/STRING/BOOL/NULL`（`PY/plumb_translator.py:1499-1521`） | `IR v2 §IR-D` |
| 名字 | `ast.Name`（`.id, .ctx`；`ctx ∈ {ast.Load, ast.Store, ast.Del}`） | `MOVE`/`ARRAYLOAD`（现状即） | 现有 IR 即可 |
| 属性读 | `ast.Attribute`（`.value, .attr, .ctx`） | `ARRAYLOAD recv["attr"]`（现状即，`:1523-1543`） | 现有 IR + `IR-AP` |
| 下标读 | `ast.Subscript`（`.value, .slice, .ctx`） | `ARRAYLOAD recv[key]`（现状即，`:1545-1559`） | 现有 IR 即可 |
| 切片 | `ast.Slice`（`.lower, .upper, .step`） | 三个分量分别求值 ⇒ 键 = 合成元组（**现在整个切片是 `HAVOC`**） | 现有 IR 即可 |
| 二元运算 | `ast.BinOp`（`.left, .op, .right`），`op ∈ {ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.Pow, ast.LShift, ast.RShift, ast.BitOr, ast.BitXor, ast.BitAnd, ast.MatMult}` | `BINOP`（`:153-166`） | 现有 IR 即可 |
| 幂 `**` | `ast.BinOp`，`op == ast.Pow` | **不再近似成 `MUL`** ⇒ 见 §5.4 | — |
| 矩阵乘 `@` | `ast.BinOp`，`op == ast.MatMult` | 矩阵语义 ⇒ §12 的 `PY3` | — （`B` 档：记 `drop(VALUE, "matmul.result")`） |
| 一元运算 | `ast.UnaryOp`（`.op, .operand`），`op ∈ {ast.USub, ast.UAdd, ast.Not, ast.Invert}` | `UNARYOP`（`:201-205`；`ast.UAdd` 是恒等，`:1593-1595`） | 现有 IR 即可 |
| 布尔运算 | `ast.BoolOp`（`.op ∈ {ast.And, ast.Or}, .values`） | **短路展开成 `BRANCH` 链**（现在两个操作数都求值，`:1612-1638`） | `IR-C` |
| 比较 | `ast.Compare`（`.left, .ops, .comparators`） | 逐个 `BINOP` + `LAND` 串联（现状即，`:1640-1681`） | 现有 IR 即可 |
| `in` / `not in` | `ast.Compare`，`ops` 里是 `ast.In` / `ast.NotIn` | **现在整个比较是 `HAVOC`**（`COMPARISON_OPERATORS`（`:186-195`）里没有这两个）⇒ 补进表即可 | 现有 IR 即可 |
| `is` / `is not` | `ast.Compare`，`ops` 里是 `ast.Is` / `ast.IsNot` | `BINOP EQ/NE`（现状即） | 现有 IR 即可 |
| 调用 | `ast.Call`（`.func, .args, .keywords`） | `CALL`（`:1889-1936`） | `IR-P` |
| 关键字实参 | `ast.Call.keywords`，元素是 `ast.keyword`（`.arg, .value`） | **按名绑定**（现在按位置传，`:1905-1906`）⇒ `IR-AR` | `IR-AR` |
| 星号实参 `f(*a)` | `ast.Call.args` 里的 `ast.Starred`（`.value`） | 剩余实参 ⇒ `IR-AR` | `IR-AR` |
| 构造 `C(...)` | `ast.Call`，`.func` 是 `ast.Name` 且该名字是本模块声明的类 | `NEW`（`:1999-2045`） | `IR-P` |
| 条件表达式 | `ast.IfExp`（`.test, .body, .orelse`） | 现状即（`:1683-1707`） | 现有 IR 即可 |
| 列表/元组 | `ast.List` / `ast.Tuple`（`.elts`） | `NEWARRAY` + 逐项 `ARRAYSTORE`（`:1709-1724`） | 现有 IR 即可 |
| 集合 | `ast.Set`（`.elts`） | 同上 + 无序说明（`:1726-1751`） | 现有 IR 即可 |
| 字典 | `ast.Dict`（`.keys, .values`） | `NEWARRAY` + 逐项 `ARRAYSTORE`（`:1753-1781`） | 现有 IR 即可 |
| 推导式 | `ast.ListComp` / `ast.SetComp` / `ast.GeneratorExp`（`.elt, .generators`）；`ast.DictComp`（`.key, .value, .generators`） | **降级成循环**（见 §5.6），`generator.ifs` 也必须降级 | 现有 IR 即可 |
| 推导式的过滤 | `ast.comprehension.ifs` | **现在完全没被访问** ⇒ 过滤表达式里的 sink 不可见 | 现有 IR 即可 |
| lambda | `ast.Lambda`（`.args, .body`） | 见 §5.3 | `IR-P` |
| `await` | `ast.Await`（`.value`） | 值域完整：`t = MOVE awaitable`；调度时序不可表达 ⇒ §12 的 `PY1` | `IR v2 §IR-L` （`B` 档：记 `drop(SEMANTICS, "eventLoop.order")`） |
| `yield` | `ast.Yield`（`.value`） | 状态机展开（§5.3） | 现有 IR 即可 |
| `yield from` | `ast.YieldFrom`（`.value`） | 状态机 + 委托给子生成器 | 现有 IR 即可 |
| 海象 `:=` | `ast.NamedExpr`（`.target, .value`） | 现状即（`:1481-1484`） | 现有 IR 即可 |
| f-string | `ast.JoinedStr`（`.values`）+ `ast.FormattedValue`（`.value, .conversion, .format_spec`） | 各 part 用 `BINOP ADD` 连接；**单 part 的 f-string 现在返回操作数本身而不是字符串**（`:1871-1887`） | 现有 IR 即可 |
| f-string 的 `!r`/`!s` | `ast.FormattedValue.conversion`（`'r'`/`'s'`） | 显式转换 ⇒ `UNARYOP CAST` | 现有 IR 即可 |
| f-string 的格式说明 | `ast.FormattedValue.format_spec` | **现在完全没被访问** ⇒ `f"{x:{w}}"` 里的 `w` 被丢 | 现有 IR 即可 |
| 命名空间属性链 | `ast.Attribute` 嵌套 | 逐层 `ARRAYLOAD` | 现有 IR 即可 |
| 类型标注表达式 | `ast.Name` / `ast.Attribute` / `ast.Subscript` / `ast.Constant` | 运行期无行为 ⇒ 零指令；解析后的类型进 `TacType` | `IR v2 §IR-K` |

### 5.3 闭包 / 箭头函数 / 生成器：三种语言共用的完整形状

**闭包（PHP 的 `closure`/`arrowfunc`、Python 的 `ast.Lambda`）**

PHP 的捕获是**显式**的（`PP/ast/closure.js` 的 `uses` 字段）⇒ 捕获集**编译期已知** ⇒ 完整；
Python 的捕获是**隐式**的 ⇒ 需要自由变量分析（`PY/plumb_translator.py:533-544` 已有）。

```
// 定义点（PHP：捕获集 = node.uses；Python：捕获集 = 自由变量 ∩ 可见作用域）
  t_cl = NEW CLASS<Closure>                       // 闭包对象
  FIELDSTORE t_cl.<cap1> = <cap1>                // 捕获值拷进闭包对象
  FIELDSTORE t_cl.<cap2> = <cap2>
  MOVE  f = t_cl

// 被调函数（独立的 TacFunction，owner = 闭包名）
  fn  <owner>$closure$<n>(recv, cap1, cap2, <用户形参…>)
      t_c1 = FIELDLOAD recv.cap1
      t_c2 = FIELDLOAD recv.cap2
      <体>

// 调用点
  CALL <owner>$closure$<n>/<k>   args = [t_cl, cap1值, cap2值, <用户实参…>]
```

⇒ **PHP 的 `use (&$x)`（按引用捕获）** ⇒ `FIELDSTORE` 换成别名 ⇒ `IR-AS`。
⇒ **PHP 的 `static fn`**（`closure.isStatic`）⇒ 不捕获 `$this` ⇒ 闭包函数**没有** `recv` 形参。
⇒ **Python 的 `ast.Lambda`**：现状是「捕获变量提成前导形参」（`PY/plumb_translator.py:632-674`）
⇒ **值**是 `HAVOC`（`:1463`）。本方案改成上面的闭包对象形状 ⇒ 捕获值**在调用点**传递 ⇒
**不再把捕获当形参** ⇒ 修掉「干净的捕获被当污点源」的误报（`DFA/taint/TaintedCheckerBase.java:985`）。

**生成器（PHP 的 `yield`/`yieldfrom`、Python 的 `ast.Yield`）**

```
// 生成器函数 ⇒ 拆成「状态函数」：状态 id 进函数名
  fn  <owner>$gen$<n>$state0(receiver) { ... }      // 入口
  fn  <owner>$gen$<n>$state1(receiver) { ... }      // 第一次 yield 之后
  ...
// yield v（状态 k）
  MOVE  gen.yielded = v
  JUMP  <owner>$gen$<n>$state$k+1                 // 挂起标签（IR v2 §IR-L 的 resumePoints）
```

⇒ **生成器体里的表达式在生成器自己的流里求值**（Python 现状已经如此，`PY/plumb_translator.py:1473-1474`）
⇒ 生成器体里的 sink **不会丢**。PHP 现在**连值都不求值**（`PHP/translator.mjs:1188-1195` 直接 `HAVOC`）
⇒ 本方案修成状态机 ⇒ PHP 的 `yield $_GET['x']` 里的源**可见**。

### 5.4 算子表重构：把「近似」改回「真表达」

现状：两张表**同时**存在，且**近似表优先**（`PHP/translator.mjs:1558-1563`；`PY/plumb_translator.py:1563-1567`）
⇒ `BINARY_OPERATORS` 里的 `"**"`（`PHP/translator.mjs:55`）与 `ast.Pow`（`PY/plumb_translator.py:160`）
**是死条目**。

| 算子 | 现状 | 本方案 | 需要什么 |
|---|---|---|---|
| `**` / `ast.Pow` | `MUL` + 上报 | **真幂**：要么加 `BinaryOperator.POW`（IR 侧枚举末尾追加，兼容），要么展开为重复乘法（指数是常量时）。**推荐前者** | IR 侧枚举追加（属 `IR v2 §IR-D` 的值域族） |
| `??` | `LOR` + 上报 | **分支链**：`BRANCH (a != NULL)` | `IR v2 §IR-C` |
| `<=>` | `EQ` + 上报 | **两次比较 + 分支**（§5.1） | `IR v2 §IR-C` |
| `??=` | 无条件写 | **条件写**：分支链 | `IR v2 §IR-C` |
| `&&` / `\|\|` / `and` / `or` | `LAND`/`LOR`（无短路） | **分支链**（§6.2） | `IR v2 §IR-C` |
| `ast.MatMult` | `None` ⇒ `HAVOC` | 矩阵乘 ⇒ §12 的 `PY3` | — （`B` 档：记 `drop(VALUE, "matmul.result")`） |

⇒ **删除**两张近似表里的三条（`PHP/translator.mjs:88-103`；`PY/plumb_translator.py:174-183`），
改成真展开；`APPROXIMATED_OPERATORS` 只保留**确实无法静态表达**的项（`ast.MatMult`）。

### 5.5 PHP 的变量变量（`$$x`）—— 完整形状与它的边界

`variable` 节点（`PP/ast/variable.js`）的 `name` 字段在变量变量里是**一个对象**而不是字符串
⇒ 现状 `PHP/translator.mjs:1059` 的 `varOp(node.name, …)` 生成的 TAC 变量名是 **`[object Object]`**
⇒ **两个不同的变量变量塌成同一个 TAC 变量**（审计 §B 第 7 条）。

**完整形状**：
```
  t_name = <名字表达式>                  // 运行期值
  t_val  = ARRAYLOAD t_scope[t_name]    // 作用域变量表的动态项
  ARRAYSTORE t_scope[t_name] = <值>     // 写
```

**它的边界（诚实说明）**：上面的形状完整表达了「按运行期名字读写一个槽」，
但**不能**表达「`$$x` 指向的那个变量，和源码里 `$y` 指向的变量是同一个 `TacVar`」
——因为 `TacVar` 与「容器项」是两种不同的位置（`IR/TacOperand.java` 的 `VAR` vs `CONST`，
容器项靠 `(receiver, key)` 定位）。

⇒ 两个选项：
- **(a) 局部方案（默认）**：普通 `$x` 保持 `TacVar`；变量变量走「作用域变量表」的动态项。
  **代价**：间接写过的变量，其直接读路径看不到污染（反之亦然）⇒ 记 `SEMANTICS` 级 drop（§12 的 `PHP2`）。
- **(b) 彻底方案**：PHP 的**所有**局部变量统一放进「作用域变量表」容器，
  `$x` ⇒ `ARRAYLOAD t_scope["x"]` ⇒ **同一性成立**。
  **代价**：引擎的污点标签是 `TacVar` 粒度（`DFA/taint/TaintedCheckerBase.java:985` 逐形参打标签），
  容器化之后 `param:x` 这类标签会失效 ⇒ 需要引擎侧改造。
  ⇒ 列为待拍板 **Q1**。

### 5.6 Python 推导式 —— 降级成循环（完整形状）

现状（`PY/plumb_translator.py:1783-1821`）：只建模**第一个**元素，
`generator.ifs` **完全没被访问**，推导式的循环变量**写进外层函数的局部**。

```
// [f(x) for x in it if g(x)]
  t_it  = <it>
  t_len = CALL <python>.len/1  args = [<python>, t_it]
  t_i   = CONST INT 0
L_HEAD: t_cmp = BINOP LT(t_i, t_len)
  BRANCH t_cmp -> L_BODY, L_END ; guard = Compare(t_i, LT, t_len)
L_BODY: t_item = ARRAYLOAD t_it[t_i]
        MOVE  x = t_item                    // 循环变量写进推导式自己的局部（不再是外层的）
        t_g   = <g(x)>                      // ★ 现在完全没被访问
        BRANCH t_g -> L_NEXT, L_END ; guard = Truthiness(t_g, TRUE)   // ★ 现在完全没被访问
L_NEXT: t_val = <f(x)>
        ARRAYSTORE t_res[t_i] = t_val
        t_i = BINOP ADD(t_i, CONST INT 1)
        JUMP L_HEAD
L_END:  (t_res)
```

⇒ **循环变量泄漏进外层作用域**这一条（审计 C97）由「推导式自己的局部」修掉：
`PY/plumb_translator.py:1808` 的 `lower_write(generator.target, …)` 改成写**推导式局部**。

---

## 6. 控制流面

`t_*` 为临时变量，`L_*` 为标签；每个 `BRANCH` 带 `guard`（`IR v2 §IR-C`），
每个标签带 `labelRef`（`IR v2 §IR-G`）。两语言的形状**同构**（差别只在 §1.1 标出的那几处）。

### 6.1 分支与守卫总则

| 源条件 | `TacGuard` | 备注 |
|---|---|---|
| `if ($x)` / `if (x)` | `Truthiness(x, TRUE)` | 引擎按 `PHP_LIKE` / `PYTHON_LIKE` 规则求值（`IR v2 §IR-H`） |
| `if ($x == $y)` | `Compare(lhs, EQ, rhs)` | PHP 的 `==` 是宽松比较 ⇒ 引擎侧按 `IR-Q` 的规则表 |
| `if ($x === $y)` | `Compare(lhs, EQ, rhs)` | 严格比较，无规则差异 |
| `if ($x instanceof Y)` | `TypeTest(x, CLASS<Y>, false)` | `IR v2 §IR-C` |
| `if (isset($a['k']))` | `PropertyPresence(a, k)` | **新变体** ⇒ `IR-AQ` |
| `switch` 的 case | `Compare(discriminant, EQ, caseValue)` | 多标签 ⇒ `Membership` |
| `match` 的 arm | `Compare(cond, EQ, armValue)` | 多条件 ⇒ `Membership` |
| 循环的计数条件 | `Compare(i, LT, len)` | |
| `for` 的条件 | `Compare(t_c, EQ, CONST BOOL true)` 或源表达式 | |
| `while` / `if` 的布尔字面量 | `Always(true)` / `Always(false)` | |
| Python 的 `and`/`or` 短路点 | `Truthiness(t_a, TRUE)` | 极性由真/假标签表达（`IR v2 §IR-C`） |

### 6.2 `if` / `elseif` / `else`（两语言同形）

```
// if (c) A else B
  t_c = <c>
  BRANCH t_c -> L_THEN, L_ELSE        ; guard = Truthiness(t_c, TRUE) ; labelRef = {kind: BLOCK}
L_THEN:  <A> ; JUMP L_END
L_ELSE:  <B>
L_END:
```

PHP 的 `elseif` 是嵌套 `if`（`PHP/translator.mjs:707-709`）⇒ 保持；
但**要加 `labelRef`** 与 **`guard`**。Python 的 `elif` 同理（`PY/plumb_translator.py:1179-1200`）。

### 6.3 短路算子（两语言同形）

```
// a && b   （结果 = a 或 b，取决于 a 的真值）
  t_a = <a> ; MOVE t_res = t_a
  BRANCH t_a -> L_RHS, L_END           ; guard = Truthiness(t_a, TRUE)
L_RHS:   t_b = <b> ; MOVE t_res = t_b ; JUMP L_END
L_END:

// a || b
  t_a = <a> ; MOVE t_res = t_a
  BRANCH t_a -> L_END, L_RHS           ; guard = Truthiness(t_a, TRUE)
L_RHS:   t_b = <b> ; MOVE t_res = t_b ; JUMP L_END
L_END:

// a ?? b   （PHP 专有）
  t_a = <a> ; MOVE t_res = t_a
  BRANCH t_a -> L_END, L_RHS           ; guard = Compare(t_a, NE, NULL)
L_RHS:   t_b = <b> ; MOVE t_res = t_b ; JUMP L_END
L_END:
```

⇒ **求值顺序严格保真**（右操作数只在需要时求值）⇒ 修掉 Python 的「两个操作数都求值」
（`PY/plumb_translator.py:1621-1637`）与 PHP 的 `LAND`/`LOR`（`PHP/translator.mjs:66-68`）。
⇒ **注意 PHP 的 `and`/`or` 优先级低于 `=`**：解析器已经把优先级处理掉了，前端只管算子语义。

### 6.4 循环（两语言同形）

```
// while (c)
L_HEAD:   t_c = <c>
  BRANCH t_c -> L_BODY, L_END          ; guard = Truthiness(t_c, TRUE) ; labelRef = {kind: LOOP_HEAD}
L_BODY:   <body> ; JUMP L_HEAD
L_END:                                    ; labelRef = {kind: LOOP_END}

// do { b } while (c)
L_BODY:   <b>
L_COND:   t_c = <c>
  BRANCH t_c -> L_BODY, L_END           ; labelRef = {kind: LOOP_HEAD}
L_END:

// for (init; c; step)
  <init>
L_HEAD:   t_c = <c>                     ; 无 c 时直接 JUMP L_BODY
  BRANCH t_c -> L_BODY, L_END
L_BODY:   <body>
L_STEP:   <step> ; JUMP L_HEAD          ; labelRef = {kind: LOOP_BODY_END}
L_END:
```

**PHP 的 `for` 现状是错的**（`PHP/translator.mjs:755-781`）：
- `node.test` 在 `php-parser` 里是**数组**（`PP/parser/loops.js:80`：`test = this.read_list(this.read_expr, ",")`）
  ⇒ `PHP/translator.mjs:765` 的 `this.lowerExpression(node.test)` 拿到的是数组 ⇒ 落到兜底 ⇒ `HAVOC` + `UNHANDLED_NODE`；
- 增量字段叫 **`increment`**（`PP/parser/loops.js:69`、`:97`：`result(init, test, increment, body, shortForm)`），
  **不叫 `loop`** ⇒ `PHP/translator.mjs:776` 的 `node.loop` 是 `undefined` ⇒ **增量被静默丢弃**。
⇒ 修法：`node.test?.[0]` / `node.increment`。

**Python 的 `for`**（`plumb-frontend/python-sidecar/src/plumb_translator.py` 的 `lower_for`）现在按「下标 + `len()`」降级，
⇒ 迭代协议（`__iter__`/`__next__`）在**容器类型定不出来**时建模、**字典迭代得到值而不是键**（审计 C69，仍未改）：

```
// 通用迭代协议（IR v2 §IR-P：迭代协议 callee 名必须是真实方法名）
  t_it = <iterable>
  t_iter = CALL <iterable.__iter__>/0   args = [t_it]      ; 接收者槽 = 被迭代的容器
L_HEAD: t_item = CALL <iterable.__next__>/0  args = [t_iter] ; 接收者槽 = 迭代器
L_BODY:  MOVE <target> = t_item          ; ★ 元素就是 __next__ 的结果
         <body> ; JUMP L_HEAD
L_END:
```

⇒ **两个地方与上面的「通用协议」形状不同，都是 Python 自己的语义，不是实现取巧**：

1. **没有 `done` 标志**。Python 的 `__next__` 到头时**抛 `StopIteration`**，它不返回一对「值 + 结束标志」。
   ⇒ `{"value", "done"}` 那个形状是 **JavaScript** 的迭代器结果（`IteratorResult`），**不是** Python 的。
   ⇒ 循环的**界**因此用 `len()`（一个**合成调用**，因为 TAC 没有长度），
   元素的**来源**用 `__next__` 的结果。
   ⇒ **这个区别有后果**：引擎的迭代器传播染色的是 `__next__` 的**结果**，
   若再从它里面读一个 `["value"]` 成员，读到的**不是**那个结果 ⇒ **元素的污点在这里断掉**。

2. **字典迭代得到的是键**（`for k in d` 交出来的是键，不是值）。
   引擎在 keyed 的语言里是**保守**的（容器里**任何**脏东西都算）⇒ 这一条**会多报**，是**已知偏差**。

⇒ **引擎侧要配套**：`plumb-dfa` 的 `TaintedCheckerBase` 的 `isIteratorFactory` / `isIteratorNext`
原本只认 Java / C# 的 `iterator` / `next` ⇒ Python 的 `__iter__` / `__next__` 认不出来 ⇒ 元素的污点跨不过协议。
（**不要**改侧车去凑 `iterator` / `next`：IR-P 要求 callee 名是**真实存在的方法名**，而 Python 的迭代协议**就是**这两个方法。）

### 6.5 `foreach`（PHP）

现状（`PHP/translator.mjs:791-873`）按位置迭代 ⇒ **字符串键的数组，键变量拿到的是位置**（审计第 91 条）。
完整形状：

```
// $arr as $k => $v   （$arr 的键已知时）
  t_arr = <source>
  t_len = CONST INT <元素数>            // 数组字面量
L_HEAD:  t_cmp = BINOP LT(t_i, t_len)
  BRANCH t_cmp -> L_BODY, L_END         ; guard = Compare(t_i, LT, t_len)
L_BODY:  t_key   = ARRAYLOAD t_arr[t_i]   // ★ 键从数组里读，不是位置
         t_val   = ARRAYLOAD t_arr[t_key]
         MOVE <keyVar> = t_key ; MOVE <valueVar> = t_val
         <body> ; JUMP L_HEAD
L_END:
```

⇒ 按引用 `foreach ($arr as &$v)`（`PP/ast/foreach.js` 的 `value` 是 `byref` 节点）
⇒ 别名 ⇒ `IR-AS`。

### 6.6 `switch`（PHP）与 `match`（PHP）

**`switch`**（现状 `PHP/translator.mjs:887-943`）已经建出了分派链和 fall-through，
要补的是：`guard`（`Compare(EQ)`）、`labelRef`（`SWITCH_HEAD`/`SWITCH_CASE`/`SWITCH_END`）、
以及 **`IR v2 §IR-I` 的 `TacBranchRegion`**（case 列表 + `default` 位置 + `fallThrough` 标志）。
PHP 的**宽松比较**由 `IR v2 §IR-Q` 的引擎侧规则表承担，**前端不再上报**。

**`match`**（现状 `PHP/translator.mjs:1340-1404`）：分派链已对，
但「无 arm 命中」现在是 `HAVOC`（`:1393`），**而 TAC 有 `THROW`** ⇒
`PHP/translator.mjs:1388-1391` 的注释「the TAC has no way to say that」是**错的**。
完整形状：

```
  t_c = <cond>
  t_m = BINOP EQ(t_c, v1)
  BRANCH t_m -> L_ARM1, L_NEXT1        ; guard = Compare(t_c, EQ, v1)
L_NEXT1: t_m = BINOP EQ(t_c, v2)
  BRANCH t_m -> L_ARM2, L_NOMATCH      ; guard = Compare(t_c, EQ, v2)
L_NOMATCH: THROW <UnhandledMatchError>  ; ctorType = CLASS<UnhandledMatchError>   ← IR-J
L_ARM1:  t_v = <arm1 body> ; MOVE dest = t_v ; JUMP L_END
L_END:
```

### 6.7 `try` / `catch` / `finally`（两语言）

**现状的根因**：两个 sidecar 都发了 `CATCH_HANDLER`，但
`FE/sidecar/SidecarTacMapper.java:150` 把 `tryRegions` **硬编码成 `List.of()`**
⇒ `IR/TacCfgBuilder.java:205` 的 `buildExceptionSuccessors` 拿不到区域 ⇒
**没有异常边** ⇒ `DFA/engine/DfaEngine.java:2175` 的 `analysisOrder` 从入口遍历 CFG
⇒ **catch / except 体永远走不到 ⇒ 永远不被任何 checker 看到**。

⇒ **这是纯接线问题，IR 侧能力已经齐了**（`IR/TacFunction.java:749-835` 的 `TryRegion`、
`IR/TacInstruction.java:73` 的 `CATCH_HANDLER`、`:81` 的 `FINALLY_ENTER`）。

**PHP 的完整形状**（现状 `PHP/translator.mjs:952-992` 的 catch 体没有前驱）：

```
L_TRY:     <try 体> ; JUMP L_ELSE
L_CATCH_1: CATCH_HANDLER dest=e1 ctorType=CLASS<RuntimeException>
           <catch 体> ; JUMP L_AFTER
L_CATCH_2: CATCH_HANDLER dest=e2 ctorType=CLASS<LogicException>
           <catch 体> ; JUMP L_AFTER
L_AFTER:   JUMP L_END
L_ELSE:    L_END:
L_FIN:     FINALLY_ENTER label=L_FIN ; <finally 体>
```

**Python 的完整形状**（现状 `PY/plumb_translator.py:1354-1408`，同样没有前驱）：

```
L_TRY:     <try 体> ; JUMP L_ELSE
L_CATCH_1: CATCH_HANDLER dest=e1 ctorType=CLASS<OSError>
           <except 体> ; JUMP L_AFTER
L_ELSE:    <try 的 else 体> ; JUMP L_END
L_AFTER:   JUMP L_END
L_END:     L_FIN: FINALLY_ENTER label=L_FIN ; <finally 体>
```

**必填的 `tryRegions` 字段**（`IR v2 §IR-F`；IR 侧已有 `tryStart:751`、`tryEnd:754`、`catchStart:760`、
`catchEnd:766`、`catchLabels:774`、`finallyLabel:781`、`finallyStart:787`、`finallyEnd:793`）：

| 字段 | PHP 取值 | Python 取值 |
|---|---|---|
| `tryStart`/`tryEnd` | try 体在指令流里的起止下标 | 同 |
| `catchStart`/`catchEnd` | 第一个 catch 体的起止下标 | 同 |
| `catchLabels` | 每个 catch 的入口标签（`L_CATCH_<Type>`） | 同 |
| `catchTypes`（`IR-F` 新增） | 每个 catch 的类型名（**多类型 catch 见 `IR-AP`**） | 同 |
| `finallyLabel` | `L_FIN` | `L_FIN` |
| `finallyStart`/`finallyEnd` | finally 体的起止下标 | 同 |
| `syntheticFinally`（`IR-F` 新增） | `false` | `true`（Python 的 `with` ⇒ 编译器合成的 try/finally） |
| `finallyExits`（`IR-F` 新增） | `["L_FIN"]` + 每个会抛出的出口 | 同 |
| `resourceVars`（`IR-F` 新增） | 空 | `with` 的 `optional_vars` 列表 |

**Python 的 `finally` 现在只有正常路径可达**（`PY/plumb_translator.py:1399-1408`）：
`try` 体里的 `return` / `raise` 不会走到 finally ⇒ `finallyExits` 必须覆盖
「正常完成 / 每个 catch 完成 / rethrow / `return`」**四类出口**（`10` 门禁 B 第 ③ 条）。

### 6.8 循环 `else`（Python 专有）—— 语义今天反了

现状（`PY/plumb_translator.py:1202-1228` 的 `lower_while`、`:1230-1323` 的 `lower_for`）：
`else` 体被放在 `end_label` **之后**（`:1227-1228`、`:1322-1323`），
而 `break_label` **就是** `end_label`（`:1221`、`:1296`）
⇒ **else 体与 break 目标同块 ⇒ 循环 break 了 else 也跑** ⇒ **与 Python 的规则相反**。

修法（`IR v2 §IR-G` 已给出正确的标签划分）：

```
L_HEAD:   t_c = <c>
  BRANCH t_c -> L_BODY, L_LOOP_END     ; labelRef = {kind: LOOP_HEAD}
L_BODY:   <body>
          JUMP L_HEAD                   ; continue 目标
          ; break 目标 = L_LOOP_BODY_END  ← 必须跳过 else
L_LOOP_BODY_END:                        ; labelRef = {kind: LOOP_BODY_END}
          JUMP L_LOOP_END               ; 正常结束 ⇒ 进入 else
L_LOOP_END:                             ; labelRef = {kind: LOOP_END}
L_ELSE:   <else 体>
```

⇒ **`break` 跳 `LOOP_BODY_END`（跳过 else），正常流跳 `LOOP_END`（进入 else）**。

### 6.9 `with`（Python 专有）

现状（`PY/plumb_translator.py:1325-1352`）把**上下文表达式**绑给目标，
`__enter__`/`__exit__` 不建模 ⇒ 绑的是错的变量。

完整形状（`IR v2 §IR-F` 的 `resourceVars` + `IR v2 §IR-L` 的挂起点）：

```
  t_cm   = <context expression>
  t_res  = CALL <Owner>.__enter__/1   args = [t_cm]
  MOVE   <target> = t_res              // ★ 绑的是 __enter__ 的返回值
  <体>
L_FIN:   FINALLY_ENTER label=L_FIN
          CALL <Owner>.__exit__/3      args = [t_cm, <异常或 null>, <是否异常>]
```

⇒ **`with` 的目标必须由 `__enter__` 的返回值绑定**，这是 `IR v2 §IR-F` 的 `resourceVars` 的直接用途。

### 6.10 `break` / `continue` / `goto` / 标签（两语言）

**`break` / `continue` 带层级**（`PP/ast/break.js` / `continue.js` 的 `level` 字段）：
现状只保留最内层（`PHP/translator.mjs:574-589`、`:597-612`；`PY/plumb_translator.py:933-950` 不支持 level）。
⇒ **完整形状**：翻译器维护**标签栈**（标签名、目标标签、`labelRef.kind`、`scopeId`），
`break N` ⇒ 跳栈上第 N 层的 break 目标；`continue N` ⇒ 跳栈上第 N 层的 continue 目标。

**`goto` / 标签**（`PP/ast/goto.js`：`label`；`PP/ast/label.js`：`name`）：
现在报 `UNHANDLED_NODE`（`PHP/translator.mjs:559-562`）。
⇒ **完整形状**：`goto L` ⇒ `JUMP L`；`L:` ⇒ `LABEL L`。
PHP 的限制（不能跳进循环、不能跳出函数）**编译期可判定** ⇒ 可在翻译器里断言并记 `STRUCTURE` 级 drop。

### 6.11 接收者槽与 callee 规范（`IR v2 §IR-P` 的落地）

| 语言 | 现状 | `IR-P` 要求 | 本方案 |
|---|---|---|---|
| PHP | `args[0]` 是**命名空间常量**（`PHP/translator.mjs:1791`），方法调用**不带 receiver** | 接收者一律显式作为 `args[0]` | **改**：方法调用的 `args[0]` = receiver 表达式；`calleeSig` 的 arity **不含** receiver（现状已是） |
| Python | `args[0]` 是 receiver（`PY/plumb_translator.py:1917`），但**静态方法没有 receiver** | 同上 | **改**：按 §4.4 的规则决定「有无 receiver」，有则 `args[0]` 放它、无则放模块名 |
| 两者的 `NEW` | **没有** receiver 槽（`PHP/translator.mjs:1771`、`:1766-1772`；`PY/plumb_translator.py:2041`） | `IR-P` 的措辞是「接收者一律显式作为 `args[0]`」 | ⇒ **`IR-P` 需要一条细化**：`NEW` 没有接收者（对象还不存在）⇒ 「接收者槽」规则只适用于 `CALL`。**列 `IR-AO` 的一部分** |

---

## 7. 类型面

### 7.1 PHP 类型 → `TacType`

现状：`PHP/translator.mjs:2170-2189`（`typeNameOf`）与 `:2198-2216`（`tacTypeOfName`）。

| PHP 类型 | 解析器节点 / API | `TacType` | `domain`（`IR v2 §IR-D`） | 说明 |
|---|---|---|---|---|
| `int` / `integer` | `typereference`（`PP/ast/typereference.js`：`name, raw`） | `INT` | `Integral(32,true)` | PHP 的 int 是**平台相关**（64 位常见）⇒ 见 Q3 |
| `float` / `double` | 同上 | `DOUBLE` | `Floating(53,11)` | |
| `string` | 同上 | `STRING` | — | |
| `bool` / `boolean` | 同上 | `BOOLEAN` | — | |
| `array` | 同上 | `ARRAY(component=UNKNOWN)` | — | 元素类型不可知 |
| `iterable` | 同上 | `ARRAY` | — | |
| `object` | 同上 | `UNKNOWN` + `Dynamic` | — | PHP 的 `object` 不是记录 |
| `mixed` | 同上 | `UNKNOWN` + `Dynamic` | — | |
| `callable` | 同上 | `UNKNOWN` + `Dynamic` | — | 可调用不是数据类 |
| `void` | 同上 | `VOID` | — | |
| `null` | 同上 | `NULL` | — | |
| `true` / `false` | 同上 | `BOOLEAN` | — | |
| `never` | 同上 | `VOID` | — | **现在 `tacTypeOfName` 落到 `CLASS "never"`**（`PHP/translator.mjs:2215`） |
| `?T`（可空） | **不是 `nullable` 节点** ⇒ 是 `function.nullable` / `parameter.nullable` / `property.nullable` / `classconstant.nullable` 的**布尔字段** | `T` 且 `nullable=true`（`IR/TacType.java:123`） | — | **现在 `PHP/translator.mjs:2179-2181` 的 `if (node.kind === "nullable")` 在 php-parser 3.7.0 里是死分支**（解析器不产生这种节点）⇒ 可空性被静默丢弃 |
| `A\|B`（联合） | `uniontype`（`PP/ast/uniontype.js`：`types`） | `unionMembers=[A,B]`（`IR v2 §IR-K`） | — | **现在只取 `types[0]`**（`PHP/translator.mjs:2175-2178`）⇒ 其余静默丢失 |
| `A&B`（交集） | `intersectiontype`（`PP/ast/intersectiontype.js`：`types`） | `intersectionMembers=[A,B]` ⇒ **新维度** ⇒ `IR-AP` | — | 现在只取 `types[0]` |
| `(A&B)\|null`（DNF 8.2） | `uniontype` 里套 `intersectiontype` | 递归结构 ⇒ `IR-AP` | — | 现在塌成第一个类型 |
| `static`（返回类型） | `typereference`，`name == "static"` | `CLASS<声明类>` + `lateStatic=true` ⇒ `IR-AP` | — | **现在变成类名 `static`**（`PHP/translator.mjs:2215`） |
| 类名 `C` | `typereference`，`name` | `CLASS<C>` | — | |
| `self` / `parent` | `typereference` 的 `name` ∈ {self,parent} | `CLASS<声明类>` / `CLASS<superType>` | — | 现在 `self`/`parent` 都落到 `CLASS "self"`/`CLASS "parent"` |
| `Generator` | `typereference` | `CLASS<Generator>` | — | |
| 返回类型（整体） | `function.type` / `method.type` | `TacFunction.returnType` | — | **PHP 的 `finishFunction` 现在根本不发 `returnType`**（`PHP/translator.mjs:378-388`）⇒ **纯接线**：`FE/sidecar/SidecarTacMapper.java:140` 已经会读 |

### 7.2 Python 类型 → `TacType`

现状：`PY/plumb_translator.py:2354-2368`（`annotation_name`）+ `:2402-2421`（`tac_type_of_name`）。

| Python 标注 | 解析器节点 | `TacType` | 说明 |
|---|---|---|---|
| `int` | `ast.Name(id="int")` | `INT` + `domain=Unbounded()` | **Python 的 int 是任意精度**（`IR v2 §IR-D`）⇒ 边界检查器不能按 64 位判 |
| `str` | `ast.Name` | `STRING` | |
| `bool` | `ast.Name` | `BOOLEAN` | |
| `float` | `ast.Name` | `DOUBLE` + `Floating(53,11)` | |
| `bytes` | `ast.Name` | `STRING` | 现在是解码后的字符串（`:2415-2416`） |
| `None` / `NoneType` | `ast.Name` / `ast.Constant(value=None)` | `NULL` | |
| `list`/`tuple`/`set`/`frozenset`/`dict` | `ast.Name` | `ARRAY(component=UNKNOWN)` | |
| `Optional[T]` | `ast.Subscript(value=Name("Optional"), slice=…)` | `unionMembers=[T, NULL]`（`IR v2 §IR-K`） | **现在 `annotation_name` 把下标标注塌成基名 `Optional`**（`:2362-2363`）⇒ 形状全丢 |
| `Union[A,B]` | `ast.Subscript` | `unionMembers=[A,B]` | 同上 |
| `List[T]` / `Dict[K,V]` | `ast.Subscript` | `typeArguments=[T]` / `[K,V]`（`IR v2 §IR-K`） | 同上 |
| `Any` / `Literal[...]` / `Final` / `ClassVar` | `ast.Name` / `ast.Subscript` | `UNKNOWN`+`Dynamic` / `typeArguments` / — | 同上 |
| `Callable[...]` / `Iterable[T]` / `Iterator[T]` / `Generator[T,Y]` | `ast.Subscript` | `typeArguments` | 同上 |
| `Annotated[T, meta]` | `ast.Subscript` | `typeArguments=[T]` + `annotationValues` 记 `meta` | 同上 |
| `TypeVar` / `Generic[T]` | `ast.TypeVar`（3.12+）/ `ast.ClassDef.bases` 里的 `ast.Subscript` | `bound` + `TacTypeDecl.typeParameters`（`IR v2 §IR-K` / `§IR-N`） | |
| `Self` | `ast.Name(id="Self")` | `CLASS<声明类>` | 现在只作为名字出现在签名里 |
| `NewType` | `ast.Call` | `CLASS<名字>` | 现在只是普通调用 |
| `type` 别名（3.12） | `ast.TypeAlias` | 运行期无对应物 ⇒ 零指令 + `STRUCTURE` 级 drop | |

⇒ **注解通道（`IR v2 §IR-E`）现在恒空**（`FE/sidecar/SidecarTacMapper.java:144-147`）⇒
Python 的**变量注解**（`PY/plumb_translator.py:1024-1031` 的 `ast.AnnAssign`）、
**参数注解**、**返回标注**全部到不了 IR ⇒ **纯接线**。

### 7.3 数值域

| 语言事实 | 落点 |
|---|---|
| Python `int` **任意精度** | `domain=Unbounded()`（`IR v2 §IR-D`）⇒ Z3 不加有界约束 ⇒ 溢出类判定在 Python 上**不应**按固定宽度触发 |
| PHP `int` **平台相关**（32/64） | `domain=Integral(64,true)`（保守取 64）⇒ 见 Q3 |
| PHP `float` 是 double | `domain=Floating(53,11)` |
| Python `float` 是 double | `domain=Floating(53,11)` |

---

## 8. IR 需求

### 8.1 依赖 `01` 文档的主提案（IR-A .. IR-Q）

| 主提案 | 本方案用在哪 | 具体用法 |
|---|---|---|
| `IR-A`（精度三档） | §3.3 P4、§9、§11 | `SOURCE_DEGRADED` 用来表达「语义真丢」；两处写死的「有 problem ⇒ `SOURCE_LOWERED`」改成读 drops |
| `IR-B`（丢弃可见性） | §3.3 P4、§9、§12 | `TacFunction.drops` + `TacProgram.programDrops`；**只在 §12 的例外里产生** |
| `IR-C`（结构化守卫） | §6.1、§6.3、§6.6 | 每个 `BRANCH` 带 `guard`；短路/空合并展开成分支链 |
| `IR-D`（值域） | §7.3 | `TacType.domain`；Python `Unbounded()`、PHP `Integral/Floating` |
| `IR-E`（声明面元数据） | §4 | `memberKind`、`ownerType`、`TacTypeDecl`（含 `fields`/`members`/`annotations`） |
| `IR-F`（异常区域 v2） | §6.7 | `TryRegion` 的 `catchTypes`/`resourceVars`/`syntheticFinally`/`finallyExits` + wire `tryRegions` |
| `IR-G`（标签语义） | §6.4、§6.8、§6.10 | `TacLabelRef` 的 `LOOP_HEAD`/`LOOP_BODY_END`/`LOOP_END`/`SWITCH_*`/`CATCH_ENTRY`/`FINALLY_ENTRY` |
| `IR-H`（语言运行时规则） | §6.1、§7 | `TruthinessPolicy.PHP_LIKE`（**注意 `"0"` 在 PHP 是假**）/ `PYTHON_LIKE` |
| `IR-I`（分支区域） | §6.6 | `TacBranchRegion`：PHP 的 `switch`/`match` 的 case 列表、`default` 位置、`fallThrough` |
| `IR-J`（抛出类型） | §6.6、§5.1 | `THROW` 复用 `ctorType`：PHP 的 `match` 无命中 ⇒ `UnhandledMatchError`；`exit` ⇒ 终止 |
| `IR-K`（类型三维度） | §7.1、§7.2 | `unionMembers`（PHP 联合类型、Python `Optional`）、`typeArguments`（Python `List[T]`）、`bound`（`TypeVar`） |
| `IR-L`（挂起/恢复） | §5.3、§6.9 | PHP 的 `yield`/Python 的 `await`/`async` 的挂起点与恢复点 |
| `IR-M`（临界区） | — | PHP / Python 无语言级锁 ⇒ **本方案不使用** |
| `IR-N`（类型声明完整化） | §4.2 | `typeParameters`（Python 3.12 的 `type_params`）、`permittedSubtypes`（两语言无 sealed ⇒ 不使用） |
| `IR-P`（callee 规范） | §4.4、§6.11 | arity 只数用户可见的显式实参；接收者一律 `args[0]`；迭代协议用真实方法名；owner 未知不许用占位符 |
| `IR-Q`（强制转换/相等语义） | §6.1、§7.1 | PHP 的 `==` 宽松比较、`"1abc"+1` 的字符串↔数字转换 |

### 8.2 IR 需求（补充）：`IR-AO` .. `IR-AS`

`01` 文档 §10.1（`01-TAC-IR-扩展设计.md:950-952`）规定：补充提案一律用 `IR-xx`、由 `01` 文档**统一登记**，
主命名空间 `IR-A`..`IR-Q` **不再扩充**；其 §10.2 的全局分配表（`:954-982`）**已把 `IR-AO` 起分配给本文**（`:982`）。
以下 5 条按该登记表使用。

全部是「在既有节点上加可选字段」，**不新增 opcode、不新增 operand 变体**（`IR v2 §0.1`、`:0.2`）。

**先说清楚「同一形状会不会有两个编号」**（本文与 `05` / `04` 两份文档的交叉点）：

| 本文编号 | 其它文档已登记的**同一形状** | 关系 |
|---|---|---|
| `IR-AQ` | `IR-S`（`05`，`PropertyPresence` 守卫，`01:960`）+ `IR-V`（`05`，`ARRAYSTORE` 删除键标志，`01:963`） | **沿用同一套字段定义**，本文只补 PHP / Python 的生产侧与消费侧 ⇒ **不是第二套定义** |
| `IR-AR` | `IR-W`（`05`，`CALL`/`NEW` 的「剩余参数数组」，`01:964`） | `spreadArgs` **就是** `IR-W`；`namedArgs` 是本文新增 |
| `IR-AO` | `IR-AC`（`04`，形参的可选性 / 可变参声明，`01:970`） | **同类事实、两种数据形状**（`List<TacParameter>` vs `Map<String,ParamOption>`）⇒ 需拍板，见 **Q11** |

`01` 文档还给出两条硬约定：① `TacGuard` 的多语言扩展（`IR-S` / `IR-AF`）**应在同一个 `TacGuard` 层次里一起做**，
否则会出现两套互不兼容的守卫代数（`01:987-990`）——本文的 `IR-AQ` 属于同一个 `TacGuard` 扩展批次；
② `IR-AE`（`04`）是 `IR-P` 的语言细则、**不是新字段** ⇒ 归到 `IR-P` 下（`01:991`）——
本文对 `IR-P` 的两条细化同理，归到 `IR-P` 下（见 `IR-AO` 的「附带对 `IR-P` 的细化」）。

#### IR-AO —— 形参声明元数据（`TacFunction.parameters`）+ `CALL/NEW.argCount`

- **动机**：PHP 的可选/可变参/按引用形参（台账 A.3 第 16/18/19/20 条）；
  Python 的 keyword-only / `*args` / `**kwargs`（C12/C13/C14/C22）与 §4.4 的 arity 错位。
  今天形参只有一个 `List<TacOperand.TacVar>`（`IR/TacFunction.java:73`），
  **没有「这个形参是可选的 / 是可变参 / 是按引用的 / 默认值是什么」这一事实**。
- **数据形状**：

```java
/** 一个形参的声明事实。 */
public record TacParameter(
    String name,              // 形参名（命名实参按名绑定要用）
    TacType type,             // 形参类型
    boolean optional,         // 有默认值
    boolean variadic,         // ...$args / *args
    boolean byRef,            // &$x / &$args
    boolean keywordOnly,      // Python 的 bare `*` 之后
    TacOperand defaultValue   // 默认值（已降级的操作数）；null = 无默认
) implements java.io.Serializable {}
```

  `TacFunction` **末尾追加** `private final List<TacParameter> parameters;`（默认空列表）。
  `TacInstruction`（`CALL`/`NEW`）**末尾追加** `private final int argCount;`（**用户可见的**显式实参个数，不含 receiver）。

- **为什么现有 IR 不够**：`TacFunction.params` 只有变量列表 ⇒
  ① 调用点无法按名绑定实参（PHP 8 的命名实参）；② 被调用者无法知道「第 i 个实参传了没有」
  ⇒ 默认参数的两级方案（§4.5）无法实现；③ 引擎的 resolver 无法区分
  「`staticmethod` 没有接收者」与「普通方法有接收者」⇒ **每个 Python 方法调用都解析不到**（§4.4）。
- **附带对 `IR-P` 的细化**（必须写进规范，否则两条文档会打架）：
  - 「接收者槽」规则**只适用于 `CALL`**；`NEW` 没有接收者（对象还不存在）；
  - `calleeSig` 的 arity **只数用户可见的显式实参**；
    **合成实参**（`__argc`、闭包捕获）**计入 `args` 但不计入 arity**。
- **数据流友好性**：污点从实参到形参的映射可以按 `parameters` 的名字与顺序做，
  不再靠「位置 + 猜测」。
- **Z3 友好性**：无直接影响；但 `argCount` 是编译期常量 ⇒ 被调用者里的分支对每个调用点是可折叠的。
- **生产消费方**：`DFA/cg/CallGraphBuilder.java` 的 resolver（当前对 Python 返回 `UNRESOLVED`）、
  `DFA/taint/TaintedCheckerBase.java:5267-5279` 的实参→形参映射。
- **序列化影响**：`TacFunction` / `TacInstruction` 末尾追加字段 ⇒ 兼容（`IR v2 §0.3`）。
- **风险**：改 arity 规则 ⇒ **所有** Python 方法调用的 callee 键都变 ⇒ 调用图解析率突变 ⇒ 见 K2。

#### IR-AP —— 类型交集 / 晚绑定 / trait 链 / 属性标注

- **动机**：PHP 的交集类型与 DNF（台账 A.13 第 117/184 条）、`static` 返回类型（第 120 条）、
  `static::`（第 72 条）、trait 使用链（第 39–41 条）、`readonly`（第 36/49/175 条）、
  `isFinal`/`isAbstract`（第 34/35/55 条）；
  Python 的 `Enum`/`Protocol`/`TypedDict`/`frozen=True`/`metaclass`（C52/C57/C60/C62/C63/C64/C65）。
  `IR v2 §IR-K` 只给了**并集**（`unionMembers`）、类型实参、类型变量**三个维度**，
  **交集**与**晚绑定**没有位置。
- **数据形状**：

```java
// TacType 末尾追加两个可选字段（不动 15 个既有 Kind）：
private final List<TacType> intersectionMembers;  // 交集成员；null = 不是交集
private final boolean lateStatic;                 // static:: / static 返回类型
// TacTypeDecl 末尾追加三个可选字段：
private final List<String> traits;                // PHP 的 trait 使用链
private final List<String> modifiers;             // abstract/final/readonly/frozen…
private final String metaclass;                   // Python 的 metaclass=
```

  并**扩展 `IR v2 §IR-E` 的 `ownerType`**：允许它挂在 `ARRAYLOAD`/`ARRAYSTORE` 上
  （PHP / Python 的对象是命名空间，见 P9）⇒ 「这个命名空间项属于哪个类」。
- **为什么现有 IR 不够**：`TacType.Kind` 只有 **15** 个基础种类（`IR/TacType.java:40-64`：
  `BOOLEAN`/`BYTE`/`SHORT`/`CHAR`/`INT`/`LONG`/`FLOAT`/`DOUBLE`/`DECIMAL`/`STRING`/`CLASS`/`ARRAY`/`NULL`/`VOID`/`UNKNOWN`），
  加 `Kind` 会波及**每一处** `switch (t.kind())` ⇒ 用可选字段。
  实测 `plumb-dfa/src/main` 里 `switch (…kind())` 共 **11 处**，其中 **9 处**切的是 `TacType.Kind`
  （`DFA/checkers/boundary/Range.java:135`、`UnderflowConstraintChecker.java:144`、`OverflowConstraintChecker.java:161`、
  `DivZeroConstraintChecker.java:97`、`ArrayBoundsConstraintChecker.java:111`、
  `DFA/checkers/taint/NumericTruncationChecker.java:149`、
  `DFA/cg/CallGraphBuilder.java:818`、`:864`、`DFA/cg/VtaCallGraphStrategy.java:244`），
  另 **2 处**切的是 `TacOperand.Const.Kind`（`DFA/taint/TaintedCheckerBase.java:2126`、`DFA/checkers/taint/LiteralBooleanConditionChecker.java:97`）——
  `01` 文档 §0.2 说的「11 处」是**两者合计**。
  `TacTypeDecl`（`IR v2 §IR-E`）没有 trait / 修饰符 / 元类的位置。
- **生产消费方**：`DFA/checkers/boundary/Range.java:135` 的 `switch (t.kind())`（`Range.ofType` 按 kind 取**值域** ⇒ `IR-D` 的 `domain` 落在这里）
  与四个 `isArithType`（`UnderflowConstraintChecker.java:144`、`OverflowConstraintChecker.java:161`、`DivZeroConstraintChecker.java:97`、`ArrayBoundsConstraintChecker.java:111`）、
  `DFA/cg/CallGraphBuilder.java:818`/`:864` 与 `DFA/cg/VtaCallGraphStrategy.java:244`（按 kind 求类型名）、
  `DFA/taint/TaintedCheckerBase.java` 的 per-class 字段表（读 `ownerType`）、
  `DFA/cg/CallGraphBuilder.java` 的方法查找（读 `traits`）。
- **序列化影响**：末尾追加 ⇒ 兼容。

#### IR-AQ —— 键存在性与容器删除（守卫 + 数组存删标志）

- **动机**：PHP 的 `isset` / `empty` / `unset`（台账 A.15 第 144/145/146/171 条）、
  `array_key_exists`；Python 的 `in` / `not in`（C105）与 `del`（C80）。
  `IR v2 §IR-C` 的 `TacGuard` 变体里**没有「键是否存在」**，
  `IR v2 §IR-E` 也没有「删除一个键」的形状 ⇒ PHP 的 `isset` 现在发 `HAVOC` **且不报任何 problem**
  （`PHP/translator.mjs:1107-1119`）⇒ 函数仍声称 `SOURCE_FULL`。
- **数据形状**：

```java
// TacGuard 追加一个变体（sealed interface 的 permits 名单末尾追加）：
public record PropertyPresence(TacOperand receiver, TacOperand key) implements TacGuard {}

// TacInstruction 追加一个可选字段（仅 ARRAYSTORE 用）：
private final boolean deleteKey;
```

- **为什么现有 IR 不够**：`Membership` 是「值 ∈ 集合」，语义不同（`IR v2 §IR-C` 已指出）；
  `ARRAYSTORE` 只能写值不能删键（`IR/TacInstruction.java:194-195` 的 `lhs1`/`lhs2`）。
- **数据流友好性**：删除键 ⇒ 该位置的污点**应当被杀死** ⇒ PHP 现在 `unset` 保留污点 ⇒ **误报**
  （审计 §B 第 19 条）。`deleteKey` 让引擎能正确杀掉污点。
- **Z3 友好性**：`PropertyPresence` 渲染成**不可判定**（像 `TypeTest` 那样渲染成 `true`，
  见 `09-引擎消费与Z3桥.md:117` 的渲染表与 `:120-122` 的理由）⇒ 不贡献数值约束，但不丢失信息。
  ⚠ **落地时必须注意载荷形态**：`GuardStore` 的载荷是**字符串 mini-DSL 谓词**、不是 `TacGuard` 对象
  （`DFA/solver/GuardStore.java:54` 的 `Map<String, List<String>>`、`:35-37` 的「key 是变量名、值是谓词串」；
  `DFA/solver/PathCondition.java:58` 的 `List<String> predicates`、`:92-95` 的 `renderConjunction`），
  ⇒ `PropertyPresence` 在进 `GuardStore` 之前**必须先渲染成 DSL 字符串**（`09` §3 的渲染表就是这一步的规范）。
- **生产消费方**：写入点 `DFA/solver/GuardStore.java:69`（`add(String var, String predicate)`）——
  **今天 `plumb-dfa/src/main` 里只有一处 `guardStore().add(...)`**：`DFA/checkers/taint/DivByZeroChecker.java:216`；
  `BoundaryConstraintChecker.emitGuard`（`:694`）写的是它**自己的局部 `guards` map**（`:718`），**并不进 `GuardStore`**
  ⇒ 这条「跨 checker 共享守卫」的机制目前几乎是**单生产者**的。
  本条要新增的正是「把分支上的 `TacGuard` 渲染成 DSL 串后写进 `GuardStore`」这一步；
  查询点 `DFA/solver/GuardStore.java:115`（`impliedGuard`）；
  以及 `DFA/taint/TaintedCheckerBase.java` 的位置污点表（删除时清污点）。
- **序列化影响**：`TacGuard` 的 permits 名单末尾追加 + `TacInstruction` 末尾追加字段 ⇒ 兼容。
- **与 `05` 文档的关系（避免重复定义）**：本条的 `PropertyPresence` 守卫与
  `ARRAYSTORE.deleteKey` **就是** `05` 文档已登记的 **`IR-S`**（`01-TAC-IR-扩展设计.md:960`）
  与 **`IR-V`**（`:963`）——**同一形状、同一套字段定义**，
  本条**只补 PHP / Python 的生产侧与消费侧**（`isset` / `empty` / `unset` / `array_key_exists` / `in` / `del`），
  **不构成第二套定义**；实现时 `IR-S` / `IR-V` / 本文这三处落地**必须落在同一个 `TacGuard` 层次里**
  （`01:987-990` 已就 `TacGuard` 的多语言扩展给出同一条告警）。

#### IR-AR —— 实参按名绑定 + 剩余实参（展开）

- **动机**：PHP 8 的**命名实参**（台账 A.3 第 22 条、§A.20 第 176 条）——
  现在**名字被丢掉**（`PHP/translator.mjs:1810-1820` 的 `argumentsOf` 只取 `argument.value`）
  ⇒ 值被绑到**错的形参**；PHP 的 `f(...$a)`（第 112 条）；
  Python 的关键字实参（C113）与 `f(*a)`（C108/C114）。
- **数据形状**：

```java
// TacInstruction 末尾追加两个可选字段（仅 CALL/NEW 用）：
private final List<TacNamedArg> namedArgs;   // 按名绑定的实参（与 args 并列）
private final TacOperand spreadArgs;          // 运行期才定个数的那部分实参
public record TacNamedArg(String name, TacOperand value) implements java.io.Serializable {}
```

- **为什么现有 IR 不够**：`IR/TacInstruction.java:228` 的 `args` 是**定长列表**，
  表达不了「按名字绑定」也表达不了「实参个数运行期才定」。
- **数据流友好性**：按名绑定后，污点能到**正确的形参** ⇒ 现在是「到错的形参」（审计 §B 第 12 条）。
- **Z3 友好性**：无直接影响。
- **生产消费方**：`DFA/taint/TaintedCheckerBase.java:5267-5279`（实参→形参绑定）、
  `DFA/cg/CallGraphBuilder.java` 的 resolver（按名解析重载）。
- **序列化影响**：末尾追加 ⇒ 兼容。
- **与 `05` 文档的关系（避免重复定义）**：本条的 `spreadArgs`（运行期才定个数的那部分实参）
  **就是** `05` 文档已登记的 **`IR-W`**（`01-TAC-IR-扩展设计.md:964`，`CALL`/`NEW` 的「剩余参数数组」）；
  本条的 `namedArgs`（按名绑定）是 **PHP 8 命名实参 + Python 关键字实参**特有的形状，`05` 文档未涉及
  ⇒ **只有 `namedArgs` 这一半是本文新增**。

#### IR-AS —— 作用域绑定与别名

- **动机**：PHP 的 `global $x`（台账 A.19 第 169 条）、函数内 `static $c`（第 170 条）、
  按引用形参（第 19 条）、按引用返回（第 20 条）、引用赋值 `$a = &$b`（第 111 条）、
  `&$x`（`byref` 节点）、变量变量（第 4/5 条）；
  Python 的 `global` / `nonlocal`（C81/C82）—— 后者现在**被当成局部绑定**
  （`PY/plumb_translator.py:604-613` 的 `collect_declared_globals` 只认 `ast.Global`，不认 `ast.Nonlocal`）。
- **数据形状**：

```java
// TacFunction 末尾追加：
private final List<TacScopeBinding> scopeBindings;
// 一条绑定：名字 + 它属于哪个作用域 + 是不是按引用
public record TacScopeBinding(String name, ScopeKind kind, boolean byRef) implements java.io.Serializable {}
public enum ScopeKind { LOCAL, GLOBAL, STATIC_LOCAL, CAPTURED, PARAM }

// TacInstruction（仅 MOVE / ARRAYSTORE）末尾追加：
private final String aliasOf;   // 这个位置与哪个变量是同一个位置（null = 不别名）
```

- **为什么现有 IR 不够**：TAC 的位置只有两种（`TacVar` 与「容器项」），
  **没有「两个位置是同一个」的概念** ⇒ 按引用与 `global` 都无法表达。
- **⇒ ⚠ R21 的 B3 把这一条**部分**兑现了**（`14` §3.41）：**Python 侧**没有加 IR ——
  **作用域槽**用的是**既有**的 `FIELDLOAD`/`FIELDSTORE` + 一个合成的作用域所有者，
  而引擎的**跨函数字段污点表**（`TaintedCheckerBase.crossFnClassFieldTaint`，键 = `<类>.<字段>`）
  **本来就是「一个跨函数活着的可变位置」这件事已有的实现** ⇒ ⇒ **所以这条提案对 Python 已不再是前置**；
  **PHP 侧仍未兑现**（`global $x` 的写还是进函数自己的局部）。
  **⇒ 而本节「生产消费方」写的那条通道**（按 `scopeBindings` 把 `global` 写路由到模块作用域的位置）
  **在仓库里零命中**（`plumb-dfa` / `plumb-main` / `plumb-tac` 的 `src/main` 都搜不到 `scopeBindings`）——
  这条通道**存在但没有人消费**，所以它**不能**被当成「已经有的能力」。
- **数据流友好性**：这是**跨函数污点**在 PHP / Python 里最常见的断点：
  `global G; G = cmd` 现在写进**函数自己的局部**（审计 S9），
  脏数据永远到不了读它的另一个函数。
  **⇒ R21 的 B3 已修好 Python 那一半**（`14` §3.41）：端到端实测，模块级写 / `global` 写 /
  类属性 / `nonlocal` 写**四种形状**从 **0 条**变成 **各 1 条**，而四条反向对照**仍然是 0 条**。
- **Z3 友好性**：无直接影响。
- **生产消费方**：`DFA/taint/TaintedCheckerBase.java` 的 per-function 变量表与模块级变量表
  （按 `scopeBindings` 把 `global` 写路由到模块作用域的位置）。
  **⇒ 而 Python 侧现在走的是另一条消费方**：`crossFnClassFieldTaint` + `classFieldKey`（`14` §3.41）。
- **序列化影响**：末尾追加 ⇒ 兼容。

### 8.3 补充提案与既有提案的合并说明

| 本文的提案 | 与既有提案的关系 |
|---|---|
| `IR-AO` | **细化 `IR v2 §IR-P`**（新增「`NEW` 无接收者」「合成实参不计入 arity」两条细则）+ **新增** `parameters`/`argCount`；**与 `IR-AC`（`04`，形参可选性/可变参）同类事实、数据形状不同** ⇒ 见 **Q11** |
| `IR-AP` | **细化 `IR v2 §IR-K`**（加交集维度）+ **细化 `IR v2 §IR-E`**（`ownerType` 扩展到 `ARRAYLOAD`/`ARRAYSTORE`；`TacTypeDecl` 加 `traits`/`modifiers`/`metaclass`） |
| `IR-AQ` | **细化 `IR v2 §IR-C`**（`PropertyPresence` 守卫）+ `ARRAYSTORE.deleteKey`；**沿用 `IR-S`（`05`）与 `IR-V`（`05`）的同一套字段定义**（`01:960`、`:963`），本文只补 PHP / Python 的落地 |
| `IR-AR` | `spreadArgs` **沿用 `IR-W`（`05`）**（`01:964`）；`namedArgs` 为**新增** |
| `IR-AS` | **新增**；与 `IR-AH`（`07`，按引用实参/多输出，`01:975`）**分工不同**：`IR-AH` 是**调用点**的实参形状，`IR-AS` 是**声明侧**的作用域绑定与位置别名 |

⇒ **不重复定义** `01` 已有的主命名空间提案（`IR-A`..`IR-Q`）；
与 `05` 文档重叠的三处（`IR-S`/`IR-V`/`IR-W`）已显式声明为**同一套字段定义**、本文只补落地，不另起编号；
与 `04` 文档的 `IR-AC` 的一处数据形状分歧已列为 **Q11** 待拍板。

---

## 9. wire 协议改动

### 9.1 本方案要动的字段（与 `08` 文档的字段表对齐）

| 键 | 层级 | 形状 | PHP sidecar | Python sidecar | Java 消费点 |
|---|---|---|---|---|---|
| `precision` | 函数 | `"SOURCE_FULL"\|"SOURCE_LOWERED"\|"SOURCE_DEGRADED"` | 改：由 drops 推导（现 `PHP/translator.mjs:382` 写死） | 改（现 `PY/plumb_translator.py:744-751` 写死） | `FE/sidecar/SidecarTacMapper.java:132-135` 扩三种值；**外层聚合也要改**（见下） |
| `drops` | 函数 | `[{construct,detail,line,column,impact,scope}]` | 增 | 增 | 增 → `TacFunction.drops`（`IR v2 §IR-B`） |
| `programDrops` | 文件 | 同上 | 增 | 增 | 增 → `TacProgram.programDrops` |
| `memberKind` | 函数 | `"METHOD"\|"CONSTRUCTOR"\|"FIELD_INITIALIZER"\|"GETTER"\|"SETTER"\|"MODULE"\|…` | 增 | 增 | 增 → `TacFunction.memberKind`（`IR v2 §IR-E`） |
| `tryRegions` | 函数 | `[{tryStart,tryEnd,catchStart,catchEnd,catchLabels,catchTypes,finallyLabel,finallyStart,finallyEnd,syntheticFinally,finallyExits,resourceVars}]` | 增 | 增 | 增：`FE/sidecar/SidecarTacMapper.java:150` 的 `List.of()` 换真实映射 |
| `returnType` | 函数 | 类型对象 | **修**：现在**根本没有这个键**（`PHP/translator.mjs:378-388`）⇒ 键在 JSON 里**不存在** ⇒ `FE/sidecar/SidecarTacMapper.java:140` 读到 null ⇒ `TacType.UNKNOWN` | 已有（`PY/plumb_translator.py:742`）⇒ 只需保证 `{"kind":"UNKNOWN"}` 而不是 `undefined` | 已有读取（`:140`），无需改 |
| `guard` | 指令 | `{kind:"Compare",lhs,op,rhs}` / `"Truthiness"` / `"Membership"` / `"TypeTest"` / `"Always"` / `"PropertyPresence"` | 增 | 增 | 增：`toInstruction`（`:159-227`）→ `TacInstruction.guard` |
| `labelRef` | 指令 | `{name,kind,scopeId}` | 增 | 增 | 增 → `TacInstruction.labelRef`（`IR v2 §IR-G`） |
| `ownerType` | 指令 | 字符串（FQN） | 增（**`ARRAYLOAD`/`ARRAYSTORE` 也要用**，见 P9 与 `IR-AP`） | 增 | 增 → `TacInstruction.ownerType` |
| `branchRegion` | 指令 | 区域 id | 增（`switch`/`match`） | 增（`match` 语句） | 增 → `TacInstruction`（`IR v2 §IR-I`） |
| `types` | 文件 | `[{fqn,simpleName,superType,interfaces,annotations,fields,fieldAnnotations,members,traits,modifiers,metaclass,isInterface,isAbstract,isEnum,isRecord,isSynthetic}]` | 增 | 增 | 增 → `TacProgram.types`（`IR v2 §IR-E`） |
| `classAnnotations`/`methodAnnotations`/`fieldAnnotations`/`paramAnnotations`/`annotationValues` | 函数 | 字符串 / `Map<string,string[]>` | 增（**PHP 的 `#[Attr]` 就落在这里**，`PP/ast/attrgroup.js`） | 增（**Python 的装饰器身份落在这里**） | 增：`FE/sidecar/SidecarTacMapper.java:144-147`、`:154` 的 `List.of()`/`Map.of()` 换真实映射 |
| `parameters` | 函数 | `[{name,type,optional,variadic,byRef,keywordOnly,defaultValue}]` | 增 | 增 | 增 → `TacFunction.parameters`（`IR-AO`） |
| `argCount` | 指令 | 整数 | 增 | 增 | 增 → `TacInstruction.argCount`（`IR-AO`） |
| `namedArgs` | 指令 | `[{name,value}]` | 增 | 增 | 增 → `TacInstruction.namedArgs`（`IR-AR`） |
| `spreadArgs` | 指令 | 操作数 | 增 | 增 | 增 → `TacInstruction.spreadArgs`（`IR-AR`） |
| `scopeBindings` | 函数 | `[{name,kind,byRef}]` | 增 | 增 | 增 → `TacFunction.scopeBindings`（`IR-AS`） |
| `aliasOf` | 指令 | 字符串 | 增 | 增 | 增 → `TacInstruction.aliasOf`（`IR-AS`） |
| `problems`（文件级） | 响应 | `[{kind,message,nodeClass,line,column}]` | **补**：现在响应里**只有恒空的 `diagnostics`**（`PHP/main.mjs:159`） | **补**：同（`PY/plumb_wire.py` 无 envelope；`main.py` 需查） | `FE/php/PhpTacFrontend.java:210-218`、`FE/python/PythonTacFrontend.java:236-244` 需消费 |

### 9.2 构念 ID 词表新增（`08` 文档 §4）

| 词条 | 含义 | 典型出现处 |
|---|---|---|
| `decl.closure.capture` | 闭包捕获未绑定到闭包对象 | PHP/Python |
| `decl.constructor.unlinked` | 构造器体与 `NEW` 未链接 | PHP/Python |
| `decl.trait.unlinked` | trait 方法未链接到使用它的类 | PHP |
| `ctrl.for.conditionArray` | `for` 的条件被当成数组 | PHP |
| `ctrl.for.incrementDropped` | `for` 的增量被丢弃 | PHP |
| `ctrl.loop.else.inverted` | 循环 `else` 与 `break` 同块 | Python |
| `ctrl.match.noArm` | `match` 无 arm 命中 | PHP |
| `exc.handler.unreachable` | catch/except 不可达 | PHP/Python |
| `exc.finally.exit` | `finally` 的某个出口没有边 | PHP/Python |
| `expr.varvar.alias` | 变量变量的目标身份不可静态确定 | PHP |
| `expr.fcc.notCall` | 一等可调用被当成调用 | PHP |
| `op.assign.byref` | 按引用赋值未建模 | PHP |
| `op.call.namedArg` | 命名实参未按名绑定 | PHP/Python |
| `value.arity.static` | `staticmethod` 的 arity 减了接收者 | Python |
| `value.default.kwOnly` | 关键字专用形参未计入 arity | Python |
| `type.union.collapsed` | 联合类型塌成第一个成员 | PHP/Python |
| `type.intersection.collapsed` | 交集类型塌成第一个成员 | PHP |
| `parse.file.failed` | 整个文件解析失败 | PHP |

⇒ 词表纪律（`08` §4）：一个词条只描述一件事；词条带 `impact`；
`scripts/` 下的门禁比对「sidecar 实际发出的词条」与词表。

---

## 10. 改动清单

### 10.1 PHP sidecar

| 文件 | 函数 / 位置 | 要加什么 | 用到的 IR / wire 能力 |
|---|---|---|---|
| `PHP/translator.mjs` | `lowerClassBody`（`:643-662`） | 穷尽分派：补 `enum`/`enumcase`/`classconstant`/`traituse`/`traitprecedence`/`traitalias`/`interface`/`attribute`；删 `else` 兜底 | `IR-E` + `IR-AP` |
| 同上 | `lowerClass`（`:628-641`） | 类体函数改名 `<clinit>`，`memberKind=FIELD_INITIALIZER` | `IR-E` |
| 同上 | 新增 `lowerConstructor` | `__construct` 方法改名 `<init>`、`memberKind=CONSTRUCTOR`、签名 `<fqcn>.<init>(...)` | `IR-E` + `IR-P` |
| 同上 | 新增 `lowerInterface` / `lowerEnum` / `lowerTrait` / `lowerTraitUse` | 按 §4.1 的表 | `IR-E` + `IR-AP` |
| 同上 | `lowerPropertyStatement`（`:670-679`） | 初值改成 `ARRAYSTORE this["x"]`（`ownerType` = 类名），不是 `MOVE` 到局部 | `IR-E` + `IR-AP` |
| 同上 | `lowerConstantStatement`（`:681-689`）+ 新增 `lowerClassConstant` | 类常量 ⇒ `ARRAYSTORE t_cls_C["X"]` | `IR-AP` |
| 同上 | `lowerParameters`（`:414-424`） | 读 `parameter.value` / `isRef` / `isVariadic` / `nullable`；填 `parameters` | `IR-AO` |
| 同上 | `functionSignature`（`:446-450`） | 返回类型进 `returnType`（类型对象，不是字符串） | §9 |
| 同上 | `declaredArityOf`（`:434-436`） | 按 `parameters` 计 | `IR-AO` |
| 同上 | `lowerFunction`（`:398-412`） | `byref` 进 `parameters` | `IR-AO` |
| 同上 | `lowerFor`（`:755-781`） | `node.test?.[0]` / `node.increment`；补 `guard`/`labelRef` | `IR-C` + `IR-G` |
| 同上 | `lowerWhile`（`:717-735`）/ `lowerDo`（`:737-753`）/ `lowerForeach`（`:791-873`） | 补 `guard`/`labelRef`；`foreach` 的键从数组读 | `IR-C` + `IR-G` |
| 同上 | `lowerIf`（`:691-715`） | 补 `guard`/`labelRef` | `IR-C` + `IR-G` |
| 同上 | `lowerSwitch`（`:887-943`） | 补 `guard`/`labelRef`/`branchRegion`；宽松比较不再上报 | `IR-C` + `IR-G` + `IR-I` + `IR-Q` |
| 同上 | `lowerMatch`（`:1340-1404`） | 无命中 ⇒ `THROW`（`ctorType`）而不是 `HAVOC`；补 `guard`/`labelRef` | `IR-J` + `IR-C` |
| 同上 | `lowerTry`（`:952-992`） | 补 `tryRegions` 各字段；`finally` 的四个出口都有边 | `IR-F` |
| 同上 | `catchTypeName`（`:1001-1012`） | 多类型 catch ⇒ `catchTypes` 记全部 | `IR-AP` |
| 同上 | `lowerBreak`（`:574-589`）/ `lowerContinue`（`:597-612`） | 标签栈 ⇒ 支持 `level` | `IR-G` |
| 同上 | 新增 `lowerGoto` | `goto`/`label` ⇒ `JUMP`/`LABEL` | `IR-G` |
| 同上 | `lowerExpression` 的 `case "closure"/"arrowfunc"`（`:1180-1187`） | 闭包 ⇒ 独立函数 + 闭包对象（§5.3）；`uses` 读 `closure.uses` | `IR-P` |
| 同上 | `lowerExpression` 的 `case "yield"/"yieldfrom"`（`:1188-1195`） | 生成器 ⇒ 状态机（§5.3） | `IR-L` |
| 同上 | `lowerExpression` 的 `case "list"`（`:1198-1204`） | 表达式位置的 `list` ⇒ 合成数组 | 现有 IR 即可 |
| 同上 | `lowerExpression` 的 `case "isset"/"empty"`（`:1107-1119`） | `PropertyPresence` 守卫；**必须同时报 problem 或不留 `HAVOC`** | `IR-AQ` |
| 同上 | `lowerExpression` 的 `case "unset"`（`:552-558` 在 statement 侧） | 删除键 ⇒ `ARRAYSTORE` 带 `deleteKey` | `IR-AQ` |
| 同上 | `lowerExpression` 的 `default`（`:1205-1207`） | **删兜底**：改成按节点种类穷尽分派（§5.1 的表） | 门禁 A |
| 同上 | `lowerBinary`（`:1553-1580`） | 删近似优先；`??`/`<=>` 真展开 | `IR-C` |
| 同上 | `lowerAssign`（`:1424-1449`）/ `lowerWrite`（`:1457-1509`） | `??=` 条件写；`assignref`/`byref` ⇒ `aliasOf`；`staticlookup` 写 ⇒ `ownerType` | `IR-C` + `IR-AS` |
| 同上 | `lowerPropertyRead`（`:1627-1639`）/ `lowerStaticRead`（`:1661-1665`） | `ownerType`；`staticlookup` 读**必须发指令** | `IR-AP` |
| 同上 | `lowerInstanceof`（`:1588-1600`）+ `qualifiedNameOf`（`:2011-2030`） | `selfreference`/`staticreference`/`parentreference` ⇒ 声明类 / `superType` | `IR-AP` |
| 同上 | `lowerNew`（`:1758-1775`） | `calleeSig` = `<fqcn>.<init>/n`；`ctorType` 正确 | `IR-P` |
| 同上 | `lowerCall`（`:1785-1802`）/ `resolveCallee`（`:1828-1888`）/ `argumentsOf`（`:1810-1820`） | 方法调用带 receiver 槽；命名实参按名绑定；`variadicplaceholder` **不是调用** | `IR-P` + `IR-AR` |
| 同上 | `resolveStaticOwner`（`:1673-1710`） | `parent` 从 `superType` 读；`static` 标 `lateStaticBinding` | `IR-AP` |
| 同上 | `recordUse`（`:339-358`） | `usegroup` 的 `name`（组前缀）要拼上；`use const` 绑定常量 | 现有 IR 即可 |
| 同上 | `lowerArrayLiteral`（`:1268-1291`） | `entry.unpack` ⇒ 展开 | `IR-AR` |
| 同上 | `lowerForeach`（`:791-873`） | `byRef` ⇒ `aliasOf` | `IR-AS` |
| 同上 | `lowerStatement` 的 `case "global"/"static"/"declare"`（`:543-548`） | `global.items` / `static.variables` / `declare.directives` ⇒ 作用域绑定 | `IR-AS` |
| 同上 | `lowerStatement` 的 `case "interface"`（`:533-536`） | 降级成 `TacTypeDecl`，不再空转 | `IR-E` |
| 同上 | `lowerStatement` 的 `case "unset"`（`:552-558`）/ `case "goto"/"label"`（`:559-562`） | 见上 | `IR-AQ` / `IR-G` |
| 同上 | `finishFunction`（`:371-389`） | 发 `returnType`/`memberKind`/`drops`/`tryRegions`/`parameters`/`scopeBindings`/`annotations`；`synthetic` 按实际 | §9 |
| 同上 | `report`（`:2145-2160`） | 去重键加上 `nodeClass` 与位置；**并产出 `drops`**（`impact` 三档） | `IR-B` |
| 同上 | 新增 `emitDrop(construct, detail, impact)` | 统一丢弃出口 | `IR-B` |
| 同上 | 新增 `varNameScope`（作用域变量表） | `global`/`static`/变量变量 | `IR-AS` |
| `PHP/protocol.mjs` | 整文件 | 加 `DROP_IMPACT`/`GUARD`/`LABEL_KIND`/`MEMBER_KIND`；`PRECISION` 加 `DEGRADED` | §9 |
| `PHP/main.mjs` | `translate`（`:123-162`） | 响应带 `capabilities`/`programDrops`/`types`/`problems`；`diagnostics` 取消 | §9 |
| `PHP/PROTOCOL.md` | 全文 | 补新键（字段表 `:169-177`）；「What the sidecar does not model」（`:346-360`）改写为「保守建模清单」 | — |

### 10.2 Python sidecar

| 文件 | 函数 / 位置 | 要加什么 | 用到的 IR / wire 能力 |
|---|---|---|---|
| `PY/plumb_translator.py` | `lower_class`（`:850-871`） | 读 `node.bases` / `node.decorator_list` / `node.keywords` / `node.type_params`；类体函数改名 `<clinit>` | `IR-E` + `IR-AP` |
| 同上 | `lower_class_body`（`:873-885`） | 穷尽分派：补 `ast.AnnAssign`（类属性）、`ast.AsyncFunctionDef`、装饰器；删 `else` 兜底 | 门禁 A |
| 同上 | 新增 `lower_constructor` | `__init__` 改名 `<init>`、`memberKind=CONSTRUCTOR` | `IR-E` + `IR-P` |
| 同上 | `lower_function_declaration`（`:983-1016`） | 装饰器**身份**进 callee key：`CALL <owner>.<name>/n` 而不是 `<python>.decorator/1` | `IR-E` + `IR-P` |
| 同上 | `lower_function`（`:759-785`） | 静态方法 / 类方法的接收者规则（§4.4）；`parameters` | `IR-AO` |
| 同上 | `lower_parameters`（`:787-802`） | 按 `parameters` 填 `keywordOnly`/`variadic`/`byRef` | `IR-AO` |
| 同上 | `parameters_of`（`:804-813`）/`declared_arity`（`:2264-2276`） | 两者统一（§4.4 的两条修正） | `IR-AO` |
| 同上 | `report_non_constant_defaults`（`:815-834`） | 读 `args.kw_defaults`（现在完全没读） | 现有 IR 即可 |
| 同上 | `finish_function`（`:725-757`） | 发 `drops`/`memberKind`/`tryRegions`/`parameters`/`scopeBindings`/注解通道 | §9 |
| 同上 | `report`（`:2325-2344`） | 产出 `drops`（`impact` 三档）；去重键加 `nodeClass` | `IR-B` |
| 同上 | `lower_statement`（`:894-981`） | 补 `ast.TryStar`、`ast.TypeAlias`、`ast.Match`（真降级）、`ast.Nonlocal`、`ast.Global` | `IR-C` + `IR-I` + `IR-AS` |
| 同上 | `lower_try`（`:1354-1408`） | 补 `tryRegions`；`finally` 四个出口 | `IR-F` |
| 同上 | `lower_while`（`:1202-1228`）/ `lower_for`（`:1230-1323`） | `else` 语义修正（§6.8）；迭代协议（§6.4）；`guard`/`labelRef` | `IR-C` + `IR-G` + `IR-P` |
| 同上 | `lower_with`（`:1325-1352`） | 绑 `__enter__` 的返回值；`__exit__` | `IR-F` |
| 同上 | `lower_match`（新增，替换 `:975-980`） | 每个 case 体都要降级（现在整个 `ast.Match` 只有 subject 被降级） | `IR-C` + `IR-I` |
| 同上 | `lower_comprehension`（`:1783-1821`）/ `lower_dict_comprehension`（`:1823-1857`） | 降级成循环；`generator.ifs` 必须降级；循环变量写推导式自己的局部 | 现有 IR 即可 |
| 同上 | `lower_boolop`（`:1612-1638`） | 短路展开 | `IR-C` |
| 同上 | `lower_compare`（`:1640-1681`） | `COMPARISON_OPERATORS`（`:186-195`）补 `ast.In`/`ast.NotIn` | 现有 IR 即可 |
| 同上 | `lower_aug_assign`（`:1033-1059`） | 查 `APPROXIMATED_OPERATORS`（现在只查 `BINARY_OPERATORS`，`x **= y` 被静默当成乘法且不上报） | 现有 IR 即可 |
| 同上 | `lower_key`（`:1147-1160`） | `ast.Slice` 的三个分量分别求值 | 现有 IR 即可 |
| 同上 | `lower_joined_string`（`:1859-1887`）+ `lower_expression` 的 `ast.FormattedValue`（`:1487-1488`） | `format_spec` 必须访问；单 part f-string 产字符串；`conversion` ⇒ `CAST` | 现有 IR 即可 |
| 同上 | `lower_lambda`（`:632-674`） | 闭包对象形状（§5.3），不再把捕获提成形参 | `IR-P` |
| 同上 | `resolve_method`（`:2178-2211`）/ `resolve_free_function`（`:2096-2176`） | 按 `(类, 方法)` 查 arity（§4.4）；owner 未知不许用占位符（`IR-P`） | `IR-P` |
| 同上 | `collect_declared_globals`（`:604-613`） | 补 `ast.Nonlocal` | `IR-AS` |
| 同上 | **R21 的 B3 已加**：`collect_scope_slots` / `read_function_slots` / `read_class_slots` / `promote_cells` / `declared_scopes` / `scope_owner` / `write_slot` / `read_slot` / `slot_of` | **多级作用域槽**：模块 / 类 / 函数三层的作用域，各自有**身份**；作用域里的名字落成**合成所有者上的 `FIELDLOAD`/`FIELDSTORE`**（`14` §3.41）⇒ ⇒ **没有新 IR** | **既有 IR**（`FIELDLOAD`/`FIELDSTORE` + `CLASS` 类型的接收者） |
| 同上 | `collect_loaded_names`（`:546-593`） | `decorator_list`（`:560`、`:571`）与 `bases`（`:572`）**不再算作外层作用域的「被读名字」**（否则它们会被提升成外层函数的捕获形参 ⇒ 污染签名与污点源） | `IR-AO` |
| 同上 | `walk_bindings`（`:350-386`） | `ast.ImportFrom` 读 `node.level` | 现有 IR 即可 |
| 同上 | `lower_construction`（`:1999-2045`） | `calleeSig` = `<Class>.<init>/n`（去掉 `<unknown>.` 前缀） | `IR-P` |
| `PY/plumb_wire.py` | 整文件 | 加 `DROP_IMPACT`/`GUARD`/`LABEL_KIND`/`MEMBER_KIND`；`Precision` 加 `DEGRADED` | §9 |
| `PY/main.py` | `translate` 响应 | 增 `capabilities`/`programDrops`/`types`/`problems` | §9 |
| `PY/PROTOCOL.md` | 全文 | 补新键（字段表 `:170-180`）；「What the sidecar does not model」（`:331-348`）改写 | — |

### 10.3 Java 侧（共享）

| 文件 | 函数 / 位置 | 要加什么 |
|---|---|---|
| `FE/sidecar/SidecarTacMapper.java` | `toFunction`（`:75-156`） | 读 `drops`/`memberKind`/`tryRegions`/`parameters`/`scopeBindings`/五个注解通道；`:150` 换真实 `tryRegions`；`:144-147`、`:154` 换真实映射；`:132-135` 扩三档精度 |
| 同上 | `toInstruction`（`:159-227`） | 读 `guard`/`labelRef`/`ownerType`/`branchRegion`/`argCount`/`namedArgs`/`spreadArgs`/`aliasOf` |
| 同上 | `toType`（`:282-312`） | 读 `domain`/`typeArguments`/`unionMembers`/`bound`/`intersectionMembers`/`lateStatic` |
| `FE/php/PhpTacFrontend.java` | `parse`（`:197-238`） | 消费文件级 `problems`；**精度聚合扩三档**（`:232-237` 现在只认 `SOURCE_LOWERED`） |
| `FE/python/PythonTacFrontend.java` | `parse`（`:222-264`） | 同上（`:258-263` 现在只认 `SOURCE_LOWERED`） |
| `IR/TacCfgBuilder.java` | `buildExceptionSuccessors`（`:269-…`） | 读 `finallyExits`/`catchTypes` |
| `DFA/cg/CallGraphBuilder.java` | resolver | 按 `IR-P` 的新规范解析 callee（**Python 的方法调用从 `UNRESOLVED` 变成可解析**） |
| `DFA/solver/GuardStore.java` | `add`（`:69-`） | 新增 CFG 来源的守卫写入点 |

### 10.4 解析器层：PHP 的 `"$_GET['x']"` 整文件解析失败（精确机制与修法）

**审计结论复核**：审计台账 A.16 第 153 条与 §B 第 26 条称「`php-parser` 3.7.0 **无法解析**
`"$_GET['x']"` 这种带引号下标的简单插值形式 ⇒ 整个文件被报成非法 PHP」。
**结论成立**，但机制需要精确化（本方案给出的机制是源码级可核验的）：

1. **词法器**：`PP/lexer/strings.js:350-372`（`consume_VARIABLE`）在吃掉 `$var` 后看下一个字符，
   **是 `[` 就 `unput(1)` 并 `begin("ST_VAR_OFFSET")`** ⇒ 这一步是对的。
2. `ST_VAR_OFFSET` 状态（`PP/lexer/property.js:55-95`）能吐出 `[`、`]`、`$var`、裸词、数字，
   **但把 `'` 归到「空白类」分支**（`:75-81`：`ch === "'"` ⇒ `return this.tok.T_ENCAPSED_AND_WHITESPACE`）
   ⇒ **`'x'` 的引号被当成普通文本，不是字符串记号**。
3. **解析器**：`read_encaps_var_offset`（`PP/parser/variable.js:247-279`）只接受
   `T_STRING` / `T_NUM_STRING` / `-` / `T_VARIABLE` 四种 ⇒ 遇到 `T_ENCAPSED_AND_WHITESPACE`
   落到 `:266-277` 的 `this.expect([...])` ⇒ **期望失败 ⇒ 解析抛异常**。
4. **后果**：`PHP/main.mjs:37` 的 `parseCode` 抛 ⇒ `:137-142` 返回
   `failure(OP.TRANSLATE, "the file is not valid PHP: …")` ⇒
   `FE/php/PhpTacFrontend.java:210-218` 记一条 `PARSE_PROBLEM` 并**返回 0 个函数**
   ⇒ **整个文件零分析，扫描继续** ⇒ 文件里的所有 sink 全部消失。

**修法（两条，都要落到 `§10.1`）**：

- **修法 A（推荐，不改第三方库）**：**sidecar 侧预处理**。
  把 `"$var[...]"` 这种简单插值改写成 `"{$var[...]}"`（复杂插值）再交给解析器
  ⇒ 复杂形式走 `ST_IN_SCRIPTING`（`PP/lexer/strings.js:430-440`）⇒ `'x'` 是正常的字符串记号 ⇒ 解析通过。
  **代价**：源码文本被改写 ⇒ 位置信息会偏 ⇒ 必须**保留原文偏移**（改写时记录每个字符的原始偏移，
  在 `position(node)`（`PHP/translator.mjs:2163-2167`）里查回）。
- **修法 B（直接补词法器）**：把 `php-parser` **vendor 一份**到 `plumb-frontend/php-sidecar/vendor/php-parser/`，
  在 `matchST_VAR_OFFSET`（`PP/lexer/property.js:55-95`）里给 `'` 加一个分支返回字符串记号，
  并在 `read_encaps_var_offset`（`PP/parser/variable.js:247-279`）里加对应的分支。
  **代价**：要维护一份 fork（`php-parser` 升级即失效）⇒ 必须有门禁锁住版本。

⇒ 两条都要落在 `§10.1`；**并且**要在 `§11` 的门禁里加一条：
「`"$_GET['x']"`、`"$a[0]"`、`"{$a['x']}"` 三种写法必须都能解析，且产出的 TAC 形状一致」。

---

## 11. 验收口径

### 11.1 PHP 必须产生的 TAC 形状

| 源构念 | 必须出现 | 守卫 / 标签 |
|---|---|---|
| `if ($c)` | `BRANCH` + `guard` | `Truthiness` |
| `$a && $b` | `BRANCH` 链；右操作数**不在**真分支之前求值 | `Truthiness` |
| `$a ?? $b` | `BRANCH`；假支取 `b` | `Compare(NE,NULL)` |
| `for ($i=0;$i<3;$i++)` | **条件不是 `HAVOC`**；**增量有 `BINOP ADD`** | `Compare` |
| `foreach ($a as $k=>$v)` | 键变量绑**键**（不是位置） | `Compare(LT)` |
| `switch ($x)` | N 个 `BRANCH` 分派 + `branchRegion` | 每 case 一个 `Compare(EQ)` |
| `match ($x)` | 分派链 + **无命中是 `THROW`**（不是 `HAVOC`） | `Compare(EQ)` |
| `try/catch/finally` | `CATCH_HANDLER` + `FINALLY_ENTER`；`tryRegions` 非空；**catch 体可达** | `CATCH_ENTRY` / `FINALLY_ENTRY` |
| `function () { system($_GET['x']); }` | **闭包体被降级成一个独立函数**，里面的 `CALL \system/1` 存在 | — |
| `fn($x) => system($x)` | 同上 | — |
| `yield $_GET['x']` | yield 的**值表达式被求值** | — |
| `$this->x = $_GET['x']` | `ARRAYSTORE`（带 `ownerType`） | — |
| `new C($x)` | `calleeSig` = `<fqcn>.<init>/1`；**构造器体可达** | — |
| `isset($a['k'])` | `BRANCH` + `PropertyPresence`；**不留 `HAVOC` 且不声称 `SOURCE_FULL`** | `PropertyPresence` |
| `unset($a['k'])` | 删除键的 `ARRAYSTORE` | — |
| `$x++` | 变量**被写回** | — |
| `C::CONST` | 读**必须发指令** | — |
| `catch (A\|B $e)` | `catchTypes` 记**两个**类型 | — |
| `break 2;` | 跳到**外层**循环的 `LOOP_END` | `LOOP_END` |
| 任何函数 | `returnType` 键**存在**且非 `UNKNOWN`（除非声明就是 `mixed`/`callable`） | — |
| 任何函数 | `memberKind` 正确；构造器是 `CONSTRUCTOR`，类体是 `FIELD_INITIALIZER` | — |
| `"$_GET['x']"` | **文件能解析**；`ARRAYLOAD` 存在 | — |

### 11.2 Python 必须产生的 TAC 形状

| 源构念 | 必须出现 | 守卫 / 标签 |
|---|---|---|
| `if x:` | `BRANCH` + `guard` | `Truthiness`（`PYTHON_LIKE`） |
| `a and b` | `BRANCH` 链；`b` 只在需要时求值 | `Truthiness` |
| `for x in d:` | 迭代协议（`__iter__`/`__next__`），**元素就是 `__next__` 的结果**（Python 没有 `{"value","done"}` 那个形状）；**字典迭代得到键**（已知偏差，引擎保守 ⇒ 多报） | `Compare(LT)`（界用 `len()`，因为 Python 到头抛 `StopIteration` 而不是返回标志） |
| `while ... else:` | `break` 跳 `LOOP_BODY_END`（**跳过** else）；正常流进 `LOOP_END`（进 else） | `LOOP_BODY_END` / `LOOP_END` |
| `try/except/else/finally` | `CATCH_HANDLER` + `FINALLY_ENTER`；`tryRegions` 非空；**except 体可达**；`finally` 的**四个出口**都有边 | `CATCH_ENTRY` / `FINALLY_ENTRY` |
| `except*`（3.11+） | 被降级（`ast.TryStar` 现在落到兜底） | — |
| `match` 语句 | **每个 case 体都被降级**（现在只有 subject） | `Compare(EQ)` / `Membership` |
| `[f(x) for x in it if g(x)]` | **过滤表达式 `g(x)` 被降级**；循环变量不泄漏进外层 | — |
| `f"{x:{w}}"` | `w` 被求值 | — |
| `x in items` | `BINOP`（不是 `HAVOC`） | — |
| `x **= y` | 幂的语义（不是静默的乘法） | — |
| `def f(a, *, b=1, **kw)` | calleeSig 的 arity 与**签名一致** | — |
| `@staticmethod def s(a)` | calleeSig arity = **1**（不减接收者）；`args[0]` 不是实例 | — |
| `@classmethod def c(cls, a)` | `args[0]` 是**类对象** | — |
| `with open(f) as fh:` | `fh` 绑的是 `__enter__` 的返回值；`__exit__` 被调用 | — |
| `def f(a, *, b=os.getcwd())` | `kw_defaults` 里的表达式被降级 | — |
| `class C(Base):` | `Base` 进 `TacTypeDecl.superType` | — |
| `@dataclass class C:` | 装饰器被降级 + 合成的 `__init__` | — |
| `@app.route("/x")` | 装饰器名 `route` 出现在**某个 callee key** 里 | — |
| `def f(): ...` 的 `nonlocal g` | `g` 写进**外层**的位置（**R21 的 B3 已做**：写进的是**外层函数的作用域槽**，端到端实测能报出来 —— `14` §3.41） | — |
| 任何函数 | `returnType` 键存在且非 `UNKNOWN`（除非声明就是 `Any`） | — |

### 11.3 门禁 A / B / C 的具体化

| 门禁（`10` §2） | 在 PHP/Python 上的具体形态 |
|---|---|
| **A 声明面完备性** | 读**附录 A / 附录 B** 的「AST 节点」列；对每个节点断言「要么有对应的 TAC 形状，要么有一条 `drops`」。**实现 = 扫描两个 translator 的 `case` 分支集合，与附录的节点集合求差** |
| **B 控制流结构断言** | ① 每个 `BRANCH` 的 `guard` 非空（除恒真/恒假）；② 每个 `CATCH_HANDLER` 都被 `tryRegions.catchLabels` 覆盖且**在 CFG 里可达**；③ 每个 `FINALLY_ENTER` 的 `finallyExits` 覆盖 try 完成 / 每个 catch 完成 / rethrow / `return` 四类；④ 每个 `JUMP` 的 `labelRef` 合法 |
| **C 求解器可判定性** | 固定语料上「自动推出的守卫数 > 0」；「守卫可渲染率 ≥ 95%」；不可渲染的守卫记 `drops`（`construct = "guard.unrenderable"`，`09` §3.3） |

### 11.4 必须不变的

| 项 | 依据 |
|---|---|
| `args[0]` 恒为 receiver 槽（`CALL`）；`NEW` 无 receiver 槽 | `IR v2 §IR-P` + 本方案 §6.11 |
| PHP / Python 的对象属性访问仍是 `ARRAYLOAD`/`ARRAYSTORE`（**不换 `FIELDLOAD`**） | `PHP/translator.mjs:12-17`、`PY/plumb_translator.py:13-18` |
| `calleeSig` 形状 `owner.method/arity` | `PHP/translator.mjs:1797`、`PY/plumb_translator.py:1931` |
| 未知 owner 用 `<unknown>` 角括号（**但不再用于「真实但 owner 不可知」的调用** ⇒ 见 Q4） | `PY/plumb_translator.py:144` |
| 旧 IR 行为（`domain==null`、`labelRef==null`、`drops` 空） | `IR v2 §0.3` |
| 同一语料上**非新增能力处**的 finding 数不变 | `10` 铁律二 |

---

## 12. 无法完整支持

**结论先行**：PHP 侧 7 条、Python 侧 7 条。**本轮（2026-11-17）按「构念本体是否可降级」复核后**：**9 条仍是真例外**（全部落在 `02` §3 的**四类根因**之内 —— 根因一：运行期才决定；根因二：并发/调度；根因三：求解器表达力；根因四：无运行时对应物），**5 条降为 `B` 档**（本体已完整降级，缺的只是一个运行期才定的事实，已用 `drop` 显式记账）。
**没有一条是「IR 表达不了」**。

**本表有三档 —— 先看「档」列，它决定附录行的分档**：

| 档 | 含义 | 附录行怎么指向它 |
|---|---|---|
| **C** | **真例外**：构念**本体**无法完整降级（被调方不在源码里 / 边由运行期建立 / 编译期产物 / 求解器侧能力） | 附录行指向本表的 `PHPn` / `PYn` ⇒ 查「档」列 ⇒ **C** |
| **B** | **B 级残留**：构念**本体已完整降级**，缺的只是一个**运行期才定的事实** ⇒ 记 `drop` 显式记账 | 附录行指向本表的 `PHPn` / `PYn` ⇒ 查「档」列 ⇒ **B** |
| **已覆盖** | 已有 **IR 提案**给出解法 ⇒ **不是例外**；本表只留条目以便追溯 | 附录行指向**正文** ⇒ 分档判为 **A** |

⇒ **判据**（与 `05` §3 的 **P8** 同一条）：`A` = 构念变成真实 TAC 形状（指令**或** IR 元数据），无语义损失；`B` = 构念**本体**完整降级，缺的**只是一个运行期才定的事实**，且该缺失用 `drop` **显式记账**；`C` = 构念**本体**无法降级，只能给保守形状。
⇒ **B 与 C 的分界**：缺的是「**这次运行里它是否发生 / 何时发生 / 以什么顺序发生 / 是哪个值**」⇒ **B**；缺的是「**一条边**（谁调用它）」「**一个被调方**（不在源码里）」「**编译期产物**」⇒ **C**。
⇒ **本轮（2026-11-17）复核结果**：14 条里 **5 条降为 `B`**（`PHP1` / `PHP7` / `PY1` / `PY2` / `PY3`）、**9 条仍是真例外** ⇒ **PHP/Python 侧的真例外从 14 条降到 9 条**。


### 12.1 PHP

| 编号 | 档 | 构念 | 为什么无法静态表达 | 保守建模的形状 | 会损失什么 |
| --- | --- | --- | --- | --- | --- |
| PHP1 | B | `static::`（晚静态绑定）的**运行期类** | 实际被调用的类由**接收者的运行期类**决定，源码里没有这个事实（`02` §3.1 根因一）。**但调用边与 LSB 标记已完整降级**（owner = 声明类 + `TacTypeDecl.lateStaticBinding=true`；引擎按接收者的**推断类型**沿 `superType` 链解析，与 PHP 的方法覆盖同一条机制）⇒ 缺的只是「接收者的运行期类」这一个事实 | owner = **声明类** + `TacTypeDecl.lateStaticBinding=true` + 一条 `SEMANTICS` 级 drop（`construct = "value.dynamic"`） | 跨继承层次的静态调用解析不到子类覆盖的方法；`static::` 指向的常量取到父类的值 |
| PHP2 | C | **变量变量指向的具体变量身份**（`$$x`、`${$x}`） | 目标变量的**名字**是运行期值 ⇒ 静态不可判定它指向哪个 `TacVar`（根因一） | 作用域变量表里的**动态项**（`ARRAYLOAD t_scope[t_name]`，§5.5）⇒ 完整表达「按运行期名字读写」；**不能**表达「它与某个静态可知的 `TacVar` 是同一位置」 | 间接写过的变量，其直接读路径看不到污染（反之亦然）⇒ 一类漏报 + 一类误报。**彻底修法见 Q1** |
| PHP3 | C | `extract()` 创建的变量集合 | 被创建的变量名是运行期数组的**键集合** ⇒ 静态不可枚举（根因一） | `UNINTERPRETED <php>.extract/1` + 一条 `SEMANTICS` 级 drop | `extract` 出来的变量不可见；`extract($_GET)` 之后的污染传播断掉 |
| PHP4 | C | `__destruct` 的**调用时机** | 对象销毁时由 PHP 引擎调用；**这条调用边由引擎建立**（源码里永远没有）⇒ 缺的是一条**边**，不是一个事实（根因一） | 析构函数照常降级为独立函数，但**没有任何调用点** ⇒ 一条 `SEMANTICS` 级 drop | 析构函数里的 sink 不可见；对象资源释放的副作用不建模 |
| PHP5 | C | `eval()` 的**代码内容** | 被求值的代码是运行期产生的字符串 ⇒ 不可静态解析（根因一） | `UNINTERPRETED <php>.eval/1`（现状已是，`PHP/translator.mjs:1163-1169`）+ `SEMANTICS` 级 drop | `eval` 内部的 sink 不可见（但**参数**的污点仍到结果上 ⇒ `eval($_GET['x'])` 仍可报） |
| PHP6 | C | `include`/`require` 的**目标文件内容** | 被包含哪个文件由运行期决定（根因一）；且 sidecar 是**逐文件**翻译的 | `CALL <php>.include/1`（现状已是）+ `SEMANTICS` 级 drop（`construct = "parse.file.failed"` 同族） | 被包含文件里的 sink 在包含点不可见（**在被包含文件自己的扫描里可见**） |
| PHP7 | B | **Fiber**（8.1）的挂起/恢复交错 | 交错由调度器决定（`02` §3.2 根因二）。**但 Fiber 体是闭包 ⇒ 本体已完整降级**⇒ 缺的只是「多个 Fiber 的交错顺序」这一个**运行期才定的事实** | `Fiber::suspend()`/`resume()` 是普通调用；Fiber 体是闭包 ⇒ 按 §5.3 的闭包形状降级 | Fiber 内的 sink 可见；**多个 Fiber 的交错顺序**不可见 |

### 12.2 Python

| 编号 | 档 | 构念 | 为什么无法静态表达 | 保守建模的形状 | 会损失什么 |
| --- | --- | --- | --- | --- | --- |
| PY1 | B | `async`/`await` 的**调度时序** | 事件循环的调度顺序由运行期决定（根因二）；TAC 是顺序指令流（`IR/TacInstruction.java:192-258`）。**但 `await x` 的值域完整**（`t = MOVE x`，现状已是 `PY/plumb_translator.py:1464-1467`）⇒ 缺的只是「调度顺序」这一个**运行期才定的事实** | `Effect.SUSPEND` 标记 + `resumePoints`（`IR v2 §IR-L`）；`await x` 的**值域完整**（`t = MOVE x`，现状已是 `PY/plumb_translator.py:1464-1467`） | 值/污点层面**无影响**；并发/重入类 checker 看不到 `await` 边界 |
| PY2 | B | **构造器/元类的选择**（`__new__` vs `__init__`、元类的 `__call__`） | 实际调用哪个构造路径由运行期的类对象决定（根因一；Python 的 `type.__call__` 内部再选 `__new__`/`__init__`）。**但两条候选路径（`__new__` 与 `__init__`）都已降级为独立函数**⇒ 缺的只是「`__new__` 的返回是否被 `__init__` 接收」这一个**运行期才定的事实** | 构造降级成 `NEW <Class>.<init>/n`（指向 `__init__`）；若该类声明了 `__new__` 或元类声明了 `__call__` ⇒ **两条都降级为独立函数**，并各记一条 `STRUCTURE` 级 drop | `__new__` 里的 sink 可见（它被降级了），但**它对构造结果的贡献**不建模；元类 `__call__` 的贡献不建模 |
| PY3 | B | **矩阵乘法 `@`**（`ast.MatMult`） | 矩阵的维度与元素类型是运行期值 ⇒ **结果的值**不可静态判定（根因一）。**但两个操作数与这次乘法已完整降级** ⇒ 缺的只是**这一个值** | `HAVOC` + `VALUE` 级 drop（**不是** `UNHANDLED_NODE`） | 矩阵运算的结果不可知（其操作数的污点仍到结果上 ⇒ 保守） |
| PY4 | C | `exec` / `eval` 的**代码内容** | 同 PHP 的 PHP5（根因一） | `UNINTERPRETED builtins.exec/1`（现状已是）+ `SEMANTICS` 级 drop | 内部 sink 不可见 |
| PY5 | C | `getattr`/`setattr`/`delattr`/`hasattr` 的**动态访问** | 属性是否存在取决于运行期的对象状态与 `__getattr__` 的实现（根因一） | `CALL builtins.getattr/2`（现状已是）+ `SEMANTICS` 级 drop | 动态访问到的名字不可见 ⇒ 依赖该名字的规则不出结论 |
| PY6 | C | `globals()` / `locals()` 的**命名空间内容** | 命名空间是运行期状态（根因一） | `CALL builtins.globals/0`（现状已是）+ `SEMANTICS` 级 drop | 同上 |
| PY7 | C | **模块命名空间被运行期改写**（monkeypatching） | 谁在什么时候改写模块的名字是运行期事实（根因一） | 普通赋值（现状即是）+ `SEMANTICS` 级 drop（`construct = "value.dynamic"`） | 被 patch 的函数的**体**不会被当成本次运行的实现 ⇒ 它的 sink 不可见 |

### 12.3 为什么不是空的

本方案的 **14 条**里，**9 条是真例外**（`C` 档）、**5 条是 `B` 级残留**（本体已完整降级，缺的只是一个运行期才定的事实，已用 `drop` 显式记账）。**9 条真例外全部属于 `02` §3 的四类根因，没有一条是「IR 表达不了」**：
- 根因一（运行期决定）：PHP2（变量变量的身份）、PHP3（`extract` 的变量集）、PHP4（`__destruct` 的调用边）、PHP5（`eval` 的内容）、PHP6（`include` 的目标）、PY4（`exec`/`eval`）、PY5（`getattr`/`setattr` 动态访问）、PY6（`globals()`/`locals()` 的内容）、PY7（monkeypatching）（**9 条**）
- 根因二（并发/调度）：**0 条** —— 原来的 `PHP7`（Fiber）与 `PY1`（事件循环）已降为 `B`（本体完整，缺的只是交错顺序）
- 根因三（求解器表达力）：**0 条** —— 两语言都不需要浮点精确舍入 / 十进制定点；
  字符串内容级性质不可判定是 `09` §3.3 的**全局**简化，不单列
- 根因四（无运行时对应物）：**0 条** —— Python 的 `type` 别名（PEP 695）运行期无对应物，
  但它**不影响运行时分析**（`02` §3.4 的口径）⇒ 记 `STRUCTURE` 级 drop 即可，不进本表
- **B 档（本体完整，缺一个运行期事实）**：`PHP1`（`static::` 的接收者运行期类）、`PHP7`（Fiber 的交错顺序）、
  `PY1`（事件循环的调度顺序）、`PY2`（构造路径的选择）、`PY3`（矩阵乘的结果值）（**5 条**）

**给上游 `02` §3.6 对账表的两条补充**（`02` §3.6 的对账表目前只收了 `03`/`04`/`05`/`07` 的语言级条目，**未收 `06`**）：

| 根因 | 本文对应的语言级条目（**只收 `C` 档**） |
|---|---|
| 一、运行时决定 | `06` PHP2（变量变量的身份）、PHP3（`extract` 的变量集）、PHP4（`__destruct` 的调用边）、PHP5（`eval` 的内容）、PHP6（`include` 的目标）、PY4（`exec`/`eval`）、PY5（`getattr`/`setattr` 动态访问）、PY6（`globals()`/`locals()` 的内容）、PY7（模块命名空间被改写）（**9 条**） |
| 二、并发/调度 | 无（`PHP7` / `PY1` 已降为 `B`） （`B` 档：记 `drop(SEMANTICS, "fiber.interleave")`） |
| 三、求解器能力 | 无（PHP / Python 都不涉及浮点精确舍入与十进制定点；字符串内容级性质按 `09` §3.3 的全局简化，不单列） |
| 四、无运行时对应物 | 无（Python 的 `type` 别名运行期无对应物，但不影响运行时分析 ⇒ 记 `STRUCTURE` 级 drop） |

⇒ **9 条真例外全部落到四类根因之一，没有第五类**；与 `02` §3.6 的口径一致。
（另注：`02` §3.5 写的是「4 类根因、13 个具体条目」，而 `02` §6 写的是「12 个例外」——
这两处是 `02` 文档内部的数字不一致，**不在本文的修改范围**，此处只作提示。）

---

## 13. 风险与待拍板

### 13.1 风险

| 编号 | 风险 | 证据 / 说明 | 缓解 |
|---|---|---|---|
| **K1** | **修复「属性/字段建模」可能让 finding 变少 = 一次倒退**：PHP 的属性从「合成 `<init>` 里的一个局部 `MOVE`」改成 `ARRAYSTORE this["x"]` 后，**原来那个局部不再被写**，而对象的字段第一次有了真实的污点历史 ⇒ 如果引擎的 per-class 字段表按「类名」分桶而我们的 `ownerType` 还不生效 ⇒ 字段污点反而更不准 | `PHP/translator.mjs:670-679`（现状）vs §4.3 的目标形状 | 门禁：同语料上「新增能力处 finding **增加**、其它处 **逐条一致**」（`10` 铁律二）；`ownerType` 必须与 `TacTypeDecl.fields` 一起上线 |
| **K2** | **Python 的 arity 修正会改掉每一个方法调用的 callee 键** ⇒ 调用图解析率突变 ⇒ **跨方法边界的污点会突然打通** ⇒ finding 数会**大幅变化**（可能暴涨，也可能因为新解析到的路径产生误报） | `PY/plumb_translator.py:2264-2276` 的 `-1` 规则（§4.4） | 必须**先跑基线**（`10` 铁律三 + `10` §4「当场跑、当场落盘」），差分逐条解释；`IR-P` 的 resolver 改动与前端改动**必须同批上线** |
| **K3** | **`IR-AO` 的 `argCount` / `__argc` 合成形参改变了 `args` 的形状** ⇒ 若只改前端不改引擎 ⇒ **所有**带默认参数的调用解析失败 ⇒ finding 归零（**倒退**） | §4.5、§8.2 IR-AO | 前后端同批；门禁：带默认参数的函数，其 calleeSig 必须能被 resolver 解析 |
| **K4** | 短路 / 空合并展开成 `BRANCH` 链 ⇒ 指令数暴涨（每个 `&&` 约 4 条） | §6.3 | 设函数指令数上限，超限记 `STRUCTURE` 级 drop；测量真实语料的膨胀比 |
| **K5** | 闭包从「捕获提成形参」改成「闭包对象 + 显式传捕获」⇒ **捕获值不再是形参** ⇒ **不再自动是污点源** ⇒ **误报会减少**（这是好事，但要如实记录） | `DFA/taint/TaintedCheckerBase.java:985`；`PY/plumb_translator.py:632-674` | 门禁：闭包体内的 finding 数**逐条解释**（可能减少，也可能因为不再「一律污点」而**新增**真 finding） |
| **K6** | **接通异常边（`tryRegions`）后，catch/except 体第一次变可达 ⇒ finding 可能大幅增加** ⇒ 之前「没报」的现在全报了 ⇒ 用户会以为「引入了新问题」 | `FE/sidecar/SidecarTacMapper.java:150`（`tryRegions` 写死 `List.of()`）⇒ `IR/TacCfgBuilder.java:205` 的 `buildExceptionSuccessors(…, fn.tryRegions())` 拿不到区域 ⇒ **没有异常边**；`DFA/engine/DfaEngine.java:2175`/`:2573` 的 `analysisOrder` 从入口遍历 ⇒ catch 体不可达 | 报告里明确区分「新增能力生效处」与「回归」；`10` 铁律二的差分口径 |
| **K7** | **php-parser 的 lexer 修补需要 vendor 一份 fork** ⇒ 上游升级即失效 ⇒ 静默回归（文件又解析不了了） | §10.4 | 版本锁 + 门禁语料（§11.1 最后一行）；或改用修法 A（sidecar 侧预处理，不动第三方库） |
| **K8** | **守卫由前端提供 ⇒ 守卫写错 ⇒ Z3 更快地给出错答案** | `IR v2 §IR-C` 的风险条；`09` §9 风险 1 | 每个语言的前端必须给出「语言写法 → `TacGuard` 变体」的对照表（§6.1）并由测试验证；**PHP 的 `==` 是宽松比较 ⇒ 只能发 `Compare` 并让引擎按 `IR-Q` 处理，不得编造更精确的守卫** |
| **K9** | PHP 的 `synthetic` 从恒 `false` 改成「类体 `<clinit>` 是 `true`」⇒ 引擎会压制针对合成函数的 finding ⇒ **finding 变少** | `PHP/translator.mjs:383`；`PHP/PROTOCOL.md:173`（现在写的是「Always false」，而类体函数**就是**合成的 ⇒ 文档与实现都错） | 门禁：压制只允许针对**合成的**函数，且必须逐条解释减少量（`IR v2 §0.4` 方向性规则） |
| **K10** | `collect_loaded_names` 不再把 `decorator_list`/`bases` 算作外层作用域的「被读名字」⇒ **外层函数的捕获集变小** ⇒ 外层函数的**形参列表变短** ⇒ 外层函数的 callee 键变 ⇒ 与 K2 同类 | `PY/plumb_translator.py:560`、`:571`、`:572` | 与 K2 同批处理 |

### 13.2 待拍板

| 编号 | 事项 | 选项 | 建议 |
|---|---|---|---|
| **Q1** | PHP 的局部变量是否统一搬进「作用域变量表」容器（§5.5 的方案 b） | (a) 只对变量变量用动态槽；(b) 全部局部变量容器化 | **先 (a)**：(b) 会让引擎的 `param:x` 污点标签失效，改造面覆盖 `TaintedCheckerBase` 的 per-function 变量表 ⇒ 属另一个阶段 |
| **Q2** | `IR-AO` 的 `__argc` 合成形参是否进 `args[0]` 之后（占 `args[1]`）还是走独立槽 | (a) 占 `args[1]`，用户实参后移；(b) 独立字段 `argc` | **(b) 独立字段**：(a) 会让「用户实参的索引」在所有函数里都偏移 ⇒ 引擎的实参→形参映射全线要改 |
| **Q3** | PHP 的 `int` 值域取多少位 | 32 / 64 / 平台相关 | **64**（保守取宽），并在 `TacTypeDecl` 标注「平台相关」；Z3 侧给 `Integral(64,true)` 的有界约束 |
| **Q4** | 未知 owner 的表示 | (a) 保留 `<unknown>` 占位；(b) 结构化缺失（`ownerType=null` + calleeSig 只含方法名与 arity） | **(b)**：`IR v2 §IR-P` 已明确「不允许 `UNKNOWN_OWNER` 占位符」⇒ `PY/plumb_translator.py:144` 的 `UNKNOWN_OWNER` 要替换掉 |
| **Q5** | PHP 多类型 catch 的 `catchTypes` 形状 | (a) `List<List<String>>`；(b) 一个 catch 拆成多个 `catchLabels` | **(a)**：(b) 会让 `catchLabels` 与 `catchTypes` 的同序约定破裂 |
| **Q6** | `isset` 的守卫 | `PropertyPresence` / `Compare(NE,NULL)` | **`PropertyPresence`**：`isset(null)` 为假而 `null != null` 为假 —— 两者对 `null` 一致，但 `isset($a['k'])` 与 `$a['k'] != null` 对**键不存在**的语义不同 ⇒ 必须区分 |
| **Q7** | Python 的 `and`/`or` 展开后结果的类型 | 操作数类型 / 布尔 | **操作数类型**（与 Python 语义一致）；引擎侧按 `PYTHON_LIKE` 真值规则求值 |
| **Q8** | PHP 的 `switch` 宽松比较由谁承担 | (a) 引擎侧规则表（`IR-Q`）；(b) 前端发 `Compare` 并把宽松性记成 `STRUCTURE` 级 drop | **(a)**：前端编不出「PHP 的 `==` 在这两个值上相等」的可靠判断 ⇒ 交给引擎 |
| **Q9** | `IR-AO` 的 `parameters` 与既有 `params` 的关系 | (a) `parameters` 取代 `params`；(b) `parameters` 与 `params` 并存 | **(b)**：`params` 是 `List<TacVar>`，被 343 处 `instanceof` 依赖（`IR v2 §0.2`）⇒ 不能动 |
| **Q10** | PHP 的 `match` 无命中 ⇒ `THROW` 的 `ctorType` | `CLASS<UnhandledMatchError>` / 留空 | **`CLASS<UnhandledMatchError>`**：这样 `catch (UnhandledMatchError $e)` 才能匹配上（`IR-J`） |
| **Q11** | **已裁定（2026-11）**：以 `IR-AO` 的 `List<TacParameter>` 为准，`IR-AC`（`04`）视为它的**退化视图** | (a) 以 `IR-AO` 的 `List<TacParameter>` 为准，`IR-AC` 视为它的退化视图；(b) 保留 `IR-AC` 的 map 形状，`IR-AO` 改用 map + 单独一个名字表 | **(a) 已采纳**：`TacParameter` 里已有 `optional`/`vararg`/`defaultFunction` 三个槽位，**一个存储形状**就够；map 形状会逼出**第二套存储**（两处都要同步、且 `equals`/`hashCode` 要各改一次）。`TacParameter`（名字 + 类型 + `optional` + `variadic` + `byRef` + `keywordOnly` + `defaultValue`）**能表达 `IR-AC` 的全部三条事实**（`optional`/`vararg`/默认值），并额外支持**按名绑定**（`IR-AR` 的 `namedArgs` 需要形参名）；两份文档各留一套会重演 `01` 文档 §10.1 明确要避免的「同一事实两个编号/两个形状」。**已完成对齐**：`04` 文档 §10 的 `IR-AC` 条目已改写为「并入 `IR-AO` 的退化视图」，不再新增 `Map<String,ParamOption>` |

---

## 附录 A. PHP 审计台账 A.1–A.20 逐条去向（184 条）

**审计来源**：`D:\work\tmp\audit\07-php.md`。
**「现状」列**用审计的状态词：`KEPT` / `DEGRADED` / `DROPPED-SILENT` / `DROPPED-NOTED`（沿用审计的词表，不改写其结论）。
**「AST 节点」列**是 `php-parser` 3.7.0 的**真实**节点名/字段名（取自 `PP/ast/*.js` 与 `PP/parser/*.js`），
凡审计里的字段名与解析器实际字段名不一致的，**在备注里标出**（这是本方案对审计的一处校正）。

### A.1 变量

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 1 | `$var` | KEPT | `variable`（`name, curly`） | 保持 `TacVar`（`PHP/translator.mjs:1058-1059`）；类型由 `state.types` 推断 | §5.1 |
| 2 | `$this` | KEPT | `variable`（`name="this"`） | 保持（`PHP/translator.mjs:366`）；作 receiver 时恒 `args[0]` | §4.1 §6.11 |
| 3 | `self` / `static` / `parent` | DEGRADED | `staticlookup.what` = `selfreference` / `staticreference` / `parentreference` | `self`⇒声明类；`parent`⇒`TacTypeDecl.superType`；`static`⇒声明类+`lateStaticBinding`（运行期类不可知⇒§12 的 `PHP1`） | §4.1 §7.1 §12 （`B` 档：记 `drop(SEMANTICS, "lateStaticBinding")`） |
| 4 | 变量变量 `$$x` | DROPPED-SILENT | `variable`，`name` 是**对象**，`curly=false` | 作用域变量表的动态项；目标身份不可静态确定⇒§12 的 `PHP2` | §5.5 §12 |
| 5 | 变量变量 `${$x}` | DROPPED-SILENT | 同上，`curly=true` | 同 4（`curly` 用来区分两种写法） | §5.5 |

### A.2 超全局

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 6 | `$_GET` | KEPT | `variable` | 保持；来源标签进污点源通道 | §5.1 |
| 7 | `$_POST` | KEPT | `variable` | 同上 | §5.1 |
| 8 | `$_REQUEST` | KEPT | `variable` | 同上 | §5.1 |
| 9 | `$_COOKIE` | KEPT | `variable` | 同上 | §5.1 |
| 10 | `$_SERVER` | KEPT | `variable` | 同上 | §5.1 |
| 11 | `$_SESSION` | KEPT | `variable` | 同上 | §5.1 |
| 12 | `$_FILES` | KEPT | `variable` | 同上 | §5.1 |
| 13 | `$_ENV` | KEPT | `variable` | 同上 | §5.1 |
| 14 | `$GLOBALS` | KEPT | `variable` | 保持；**语义修正**：它是**全局作用域表**，与 `global $x`（第 169 条）共享同一容器 | §4.1 §10.1 |

### A.3 函数

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 15 | 具名函数 | KEPT | `function`（`name, args, byref, type, nullable`） | 保持 | §4.1 |
| 16 | 可选参数 | DEGRADED | `parameter.value` | A 级：调用点求值；B 级：被调用者 prologue + `argCount` | §4.5 |
| 17 | 默认值 | DROPPED-SILENT | `parameter.value`（**`PHP/translator.mjs:414-424` 从未读它**） | 同 16 | §4.5 §10.1 |
| 18 | 可变参 `...$args` | DEGRADED | `parameter.isVariadic`（**字段名不是 `variadic`**） | 形参类型 = `ARRAY(component)`；剩余实参 ⇒ `IR-AR` | §4.1 §8.2 |
| 19 | 按引用参数 `&$x` | DROPPED-SILENT | `parameter.isRef`（**字段名不是 `byRef`**） | `IR-AO` 的 `byRef` + 引擎侧别名 | §4.1 |
| 20 | 按引用返回 | DEGRADED | `function.byref` | 同 19 | §4.1 |
| 21 | 静态函数 | DEGRADED | `method.flags` 含 `MODIFIER_STATIC` | `TacTypeDecl.members.isStatic`；callee **无 receiver** | §4.1 |
| 22 | 命名实参（PHP 8） | DEGRADED | `namedargument`（`name, value`） | **按形参名重排**（`IR-AO` 的 `parameters` + `IR-AR` 的 `namedArgs`） | §5.1 §8.2 |

### A.4 可调用对象

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 23 | `call_user_func` | KEPT | `call` | 保持 `\call_user_func/2`；登记进构念词表 | §9.2 |
| 24 | `call_user_func_array` | KEPT | `call` | 保持 `\call_user_func_array/2` | §9.2 |
| 25 | `Closure::fromCallable` | KEPT | `staticlookup` | 保持 `\Closure::fromCallable/1` | §9.2 |

### A.5 闭包与箭头函数

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 26 | `function () {}` | DROPPED-NOTED | `closure`（`args, byref, uses, type, nullable, isStatic`） | **闭包体降级为独立函数**；`uses` 是**编译期已知**的捕获集 ⇒ 完全可判定 | §5.3 |
| 27 | 箭头函数 `fn () =>` | DROPPED-NOTED | `arrowfunc`（同 `closure` 形状） | 同 26 | §5.3 |
| 28 | `static fn` | DROPPED-NOTED | `closure.isStatic` | 同 26 + **不捕获 `$this`** | §5.3 |
| 29 | 一等可调用 `foo(...)` | DROPPED-NOTED **+ 输出错误** | `variadicplaceholder` | **不是调用** ⇒ 取可调用引用；调用点另发 `CALL` | §5.1 |
| 30 | `use` 捕获 | DROPPED-SILENT | `closure.uses` | 捕获集编译期已知 ⇒ 完整 | §5.3 |
| 31 | `use (&$x)` 按引用捕获 | DROPPED-SILENT | `closure.uses` 里的 `byref` 节点 | 别名 ⇒ `IR-AS` | §5.3 §8.2 |
| 32 | 闭包里的 `$this` 绑定 | DROPPED-SILENT | `closure.isStatic=false` | 隐式捕获 `$this` ⇒ 前导实参 | §5.3 |

### A.6 类

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 33 | 普通类 | KEPT | `class`（`name, ext, impl, body, flags`） | 保持 + `ext`/`impl` 进 `TacTypeDecl`；类体改名 `<clinit>` | §4.1 §4.3 |
| 34 | 抽象类 | DROPPED-SILENT | `class.flags`（`MODIFIER_ABSTRACT`） | `TacTypeDecl.isAbstract` | §4.1 |
| 35 | 最终类 | DROPPED-SILENT | `class.flags`（`MODIFIER_FINAL`） | `TacTypeDecl.modifiers` | §4.1 |
| 36 | `readonly` 类（8.2） | DROPPED-SILENT | `class.flags`（`MODIFIER_READONLY`） | 同 35 | §4.1 |
| 37 | 匿名类 `new class {}` | DROPPED-SILENT | `new.what` 是一个 `class` | 合成稳定类名 `<fqcn>$anon$<n>` + 体降级为独立函数 + `NEW` 指向它 | §4.1 |
| 38 | 接口 | DROPPED-SILENT | `interface`（`name, ext, body, attrGroups`） | **降级成 `TacTypeDecl`**（`isInterface=true`）+ 成员进 `members` | §4.1 |
| 39 | trait | KEPT（部分） | `trait`（`name, body`） | 方法降级 + `TacTypeDecl.traits` 链（`IR-AP`） | §4.1 |
| 40 | trait `insteadof` | DROPPED-NOTED | `traituse.adaptations` 里的 `traitprecedence` | **编译期消解** ⇒ 直接算出最终生效的成员写进 `TacTypeDecl.members` | §4.1 |
| 41 | trait `alias` | DROPPED-NOTED | `traituse.adaptations` 里的 `traitalias`（`trait, method, as, flags`） | 同 40 | §4.1 |
| 42 | 纯枚举 | DROPPED-NOTED | `enum`（`name, valueType, impl, body`） | 枚举 ⇒ `TacTypeDecl(isEnum=true)` + 每个 `enumcase` 一个类常量 + 方法各自成函数 | §4.1 |
| 43 | 带 backing 的枚举 | DROPPED-NOTED | `enum.valueType` | 同 42 + backing 类型进 `TacTypeDecl` | §4.1 |
| 44 | 枚举实现接口 | DROPPED-NOTED | `enum.impl` | 同 42 + `interfaces` | §4.1 |
| 45 | 枚举方法 | DROPPED-NOTED | `enum.body` 里的 `method` | 同普通方法 | §4.1 |

### A.7 类成员

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 46 | 属性（有/无初值） | DEGRADED | `propertystatement`（`kind, properties, flags`）+ `property`（`name, value, readonly, nullable, type, attrGroups, hooks`） | 初值 ⇒ `ARRAYSTORE this["x"]`（`ownerType`=类名），**不再是合成 `<init>` 里的局部 `MOVE`** | §4.1 §4.3 |
| 47 | 静态属性 | DEGRADED | `property.flags` 含 `MODIFIER_STATIC` | receiver = 类对象 ⇒ `ARRAYSTORE t_cls_C["x"]`；**读也必须发指令** | §4.1 §5.1 |
| 48 | 属性可见性 | DROPPED-SILENT | `property.flags` | `TacTypeDecl.members.isPublic` | §4.1 |
| 49 | `readonly` 属性 | DROPPED-SILENT | `property.readonly` | `modifiers` | §4.1 |
| 50 | 提升为构造器的属性 | DROPPED-SILENT | `parameter`（8.0 的 promoted 形式） | 构造器体里 `ARRAYSTORE this["x"] = x` | §4.1 §4.3 |
| 51 | 类常量 | DROPPED-NOTED | `classconstant`（`kind, constants, flags, nullable, type, attrGroups`）—— **不是 `constantstatement`** | `ARRAYSTORE t_cls_C["X"]` | §4.1 |
| 52 | `define()` | KEPT | `call` | 保持（黑盒） | §11 |
| 53 | 静态方法 | DEGRADED | `method.flags` 含 `MODIFIER_STATIC` | 同 21 | §4.1 |
| 54 | 抽象方法 | DEGRADED | `method.flags` 含 `MODIFIER_ABSTRACT` | **不生成函数**，进 `TacTypeDecl.members` | §4.1 |
| 55 | 最终方法 | DROPPED-SILENT | `method.flags` 含 `MODIFIER_FINAL` | `modifiers` | §4.1 |
| 56 | 方法可见性 | DROPPED-SILENT | `method.flags` | `members.isPublic` | §4.1 |
| 57 | `__construct` | KEPT（作为方法） | `method`（`name="__construct"`） | **改名 `<init>` + `memberKind=CONSTRUCTOR`** ⇒ `NEW` 能链接到它 | §4.3 |
| 58 | `__destruct` | KEPT（作为方法） | `method` | 降级为独立函数；**调用时机是运行期的** ⇒ §12 的 `PHP4` | §4.1 §12 |
| 59 | `__get` | KEPT（作为方法） | `method` | 属性**不在** `TacTypeDecl.fields` ⇒ 读路由到 `CALL <Class>.__get/2` | §4.1 |
| 60 | `__set` | KEPT（作为方法） | `method` | 同 59（写） | §4.1 |
| 61 | `__isset` | KEPT（作为方法） | `method` | 同 59 | §4.1 |
| 62 | `__unset` | KEPT（作为方法） | `method` | 同 59 | §4.1 |
| 63 | `__call` | KEPT（作为方法） | `method` | 方法**不在** `members` ⇒ `CALL <Class>.__call/3` | §4.1 |
| 64 | `__callStatic` | KEPT（作为方法） | `method` | 同 63 | §4.1 |
| 65 | `__invoke` | DROPPED-NOTED | `method` + `call.what` 是 `variable` | `$obj(...)` ⇒ `CALL <Class>.__invoke/n` | §4.1 §5.1 |
| 66 | `__toString` | DROPPED-SILENT | `method` | 对象参与字符串化 ⇒ `CALL <Class>.__toString/1` | §4.1 |
| 67 | `__clone` | KEPT（作为方法） | `method` + `clone`（`what, properties`） | `NEW <Class>.__clone/0` + 逐字段拷贝 | §4.1 |
| 68 | `__sleep` | KEPT（作为方法） | `method` | `serialize($x)` ⇒ `CALL <Class>.__sleep/1` | §4.1 |
| 69 | `__wakeup` | KEPT（作为方法） | `method` | `unserialize($x)` ⇒ `CALL <Class>.__wakeup/1` | §4.1 |

### A.8 继承

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 70 | `extends` | DROPPED-SILENT | `class.ext`（**字段名是 `ext`，不是 `extends`**） | `TacTypeDecl.superType` | §4.1 |
| 71 | `parent::` | DEGRADED | `staticlookup.what` = `parentreference` | 从 `superType` 读 ⇒ 真实父类 | §4.1 §7.1 |
| 72 | `static::`（晚静态绑定） | DROPPED-SILENT | `staticlookup.what` = `staticreference` | 声明类 + `lateStaticBinding`；**运行期类不可知** ⇒ §12 的 `PHP1` | §4.1 §12 （`B` 档：记 `drop(SEMANTICS, "lateStaticBinding")`） |
| 73 | `self::` | DEGRADED | `staticlookup.what` = `selfreference` | owner = 声明类；**读必须发指令** | §4.1 §5.1 |
| 74 | 方法覆盖 | DEGRADED | `TacTypeDecl.superType` + 引擎侧动态派发 | `superType` 链 ⇒ 引擎按接收者的**推断类型**查 | §4.1 |
| 75 | `instanceof` | DEGRADED | `bin`（`type="instanceof"`） | `INSTANCEOF` 已有；**`self`/`static` 现在解析成类名 `\`** ⇒ 修 `qualifiedNameOf` | §5.1 §10.1 |

### A.9 属性（Attribute）

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 76 | `#[Attr]`（PHP 8） | DROPPED-SILENT | `attrgroup`（`attrs`）→ `attribute`（`name, args`）；挂在 `class.attrGroups` / `method` / `property.attrGroups` / `parameter` / `classconstant.attrGroups` | 走 IR **已有**的四个注解通道 + `annotationValues` ⇒ **只需 wire 字段 + 前端读** | §4.6 §9 |

### A.10 命名空间

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 77 | `namespace` | KEPT | `namespace`（`name, children, withBrackets`） | 保持 | §4.1 |
| 78 | `use`（类导入） | KEPT | `useitem`（`name, alias, type`） | 保持 | §4.1 |
| 79 | `use function` | KEPT | `useitem`（`type="function"`） | 保持 | §4.1 |
| 80 | `use const` | DROPPED-SILENT | `useitem`（`type="const"`） | 绑定常量名进模块常量表；值来自 `constantstatement` 时是编译期常量 | §4.1 §10.1 |
| 81 | `use ... as ...` | KEPT | `useitem.alias` | 保持 | §4.1 |
| 82 | 组 use `use A\B\{C, D as E}` | DEGRADED（错误） | `usegroup`（**`name` 就是组前缀**） | 拼上 `usegroup.name`（现在被丢掉 ⇒ 解析到全局 `\C`） | §10.1 |
| 83 | 全限定 `\Foo\Bar` | KEPT | `name`（`resolution="fqn"`） | 保持 | §4.1 |

### A.11 控制流

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 84 | `if` | KEPT | `if` | 保持 + `guard`/`labelRef` | §6.2 |
| 85 | `elseif` | KEPT | `if` 的 `alternate` 是 `if` | 同上 | §6.2 |
| 86 | `else` | KEPT | `if` 的 `alternate` | 同上 | §6.2 |
| 87 | 替代语法 `if: endif;` | KEPT | `if` 的 `shortForm` | 解析器已归一 | §6.2 |
| 88 | `switch` | DEGRADED | `switch`（`test, body, shortForm`）+ `case`（`test, body`） | 分派链 + `branchRegion`；宽松比较交给 `IR-Q` | §6.6 |
| 89 | `match` | DEGRADED | `match`（`cond, arms`）+ `matcharm`（`conds, body`） | 无命中 ⇒ **`THROW`**（不是 `HAVOC`；`PHP/translator.mjs:1388-1391` 的注释是错的） | §6.6 |
| 90 | `for` | DEGRADED / DROPPED | `for`（`init, test, increment, body`）—— **`test` 是数组、增量叫 `increment`** | 修字段名：`node.test?.[0]` / `node.increment`（`PHP/translator.mjs:765`、`:776` 两处都错） | §6.4 §10.1 |
| 91 | `foreach` 键/值 | KEPT | `foreach`（`source, key, value, body, shortForm`） | 键从数组读（现在拿的是位置） | §6.5 |
| 92 | `foreach` 按引用 | DEGRADED | `foreach.value` 是 `byref` 节点 | 别名 ⇒ `IR-AS` | §6.5 |
| 93 | `list()` 解构 | KEPT | `list`（`items, shortForm`） | 保持 | §5.1 |
| 94 | `while` | KEPT | `while` | 保持 + `guard`/`labelRef` | §6.4 |
| 95 | `do`-`while` | KEPT | `do` | 同上 | §6.4 |
| 96 | `break`/`continue` 带层级 | DEGRADED | `break` / `continue`（`level`） | 标签栈 ⇒ 支持 `level` | §6.10 |
| 97 | `goto` / 标签 | DROPPED-NOTED | `goto`（`label`）/ `label`（`name`） | `JUMP` / `LABEL` | §6.10 |

### A.12 运算符

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 98 | 算术 `+ - * / %` | KEPT | `bin`（`type, left, right`） | 保持 | §5.1 |
| 99 | 字符串拼接 `.` | KEPT | `bin`（`type="."`） | 保持 | §5.1 |
| 100 | 比较 `< <= > >=` | KEPT | `bin` | 保持 | §5.1 |
| 101 | 三路比较 `<=>` | DEGRADED | `bin`（`type="<=>"`） | 两次比较 + 分支（**不需要新算子**） | §5.1 §5.4 |
| 102 | 严格 `===` / `!==` | KEPT | `bin` | 保持 | §5.1 |
| 103 | 空合并 `??` | DEGRADED | `bin`（`type="??"`） | 分支链（`Compare(NE,NULL)`） | §5.4 |
| 104 | 空合并赋值 `??=` | DEGRADED | `assign`（`operator="??="`） | 条件写（现在无条件写） | §5.1 §5.4 |
| 105 | 三元 `? :` | KEPT | `retif`（`test, trueExpr, falseExpr`） | 保持 | §5.1 |
| 106 | 简写 `?:` | KEPT | `retif`（`trueExpr` 为 null） | 保持 | §5.1 |
| 107 | 逻辑 `&& \|\| and or xor` | KEPT | `bin` | **短路展开**（现在 `LAND`/`LOR` 无短路） | §6.3 |
| 108 | 位运算 `& \| ^ ~ << >>` | KEPT | `bin` / `unary`（`type, what`） | 保持 | §5.1 |
| 109 | 复合赋值 | KEPT | `assign`（`operator`） | 保持（读 + `BINOP` + 写） | §5.1 |
| 110 | `++` / `--` | DROPPED-NOTED | `pre` / `post`（继承 `operation`） | 读 + `BINOP ADD/SUB` + **写回**（现在**完全不写回**） | §5.1 |
| 111 | 引用运算符 `&` | DROPPED-NOTED | **`byref`（`what`）与 `assignref`（`left, right`）是两个不同的节点，现在两个都没被处理** | 别名 ⇒ `IR-AS` | §5.1 §8.2 |
| 112 | 调用里的展开 `...` | DROPPED-NOTED | `variadic`（`what`） | 剩余实参 ⇒ `IR-AR` | §5.1 |
| 113 | 错误控制 `@` | KEPT | `silent`（`expr`） | 保持 | §5.1 |

### A.13 类型

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 114 | 标量类型标注 | KEPT | `typereference`（`name, raw`） | 保持 | §7.1 |
| 115 | 可空 `?Type` | DEGRADED | **不是 `nullable` 节点** ⇒ 是 `function/parameter/property/classconstant.nullable` 的**布尔字段** | `TacType.nullable=true`；**`PHP/translator.mjs:2179-2181` 的 `if (node.kind === "nullable")` 在 php-parser 3.7.0 里是死分支** | §7.1 §10.1 |
| 116 | 联合 `A\|B` | DEGRADED | `uniontype`（`types`） | `unionMembers`（`IR-K`）；现在只取 `types[0]` | §7.1 |
| 117 | 交集 `A&B` | DEGRADED | `intersectiontype`（`types`） | `intersectionMembers`（`IR-AP`）；现在只取 `types[0]` | §7.1 §8.2 |
| 118 | `mixed` | KEPT | `typereference` | 保持（`UNKNOWN` + `Dynamic`） | §7.1 |
| 119 | `never` | DEGRADED | `typereference` | `VOID`；**现在落成类名 `never`**（`PHP/translator.mjs:2215`） | §7.1 |
| 120 | `static` 类型 | DEGRADED | `typereference`（`name="static"`） | `CLASS<声明类>` + `lateStatic`（`IR-AP`）；现在是类名 `static` | §7.1 |
| 121 | `array` | KEPT | `typereference` | 保持 | §7.1 |
| 122 | `callable` | KEPT | `typereference` | 保持（`UNKNOWN` + `Dynamic`） | §7.1 |
| 123 | `iterable` | KEPT | `typereference` | 保持 | §7.1 |
| 124 | `object` | KEPT | `typereference` | 保持 | §7.1 |
| 125 | 独立的 `true`/`false`/`null` 类型 | KEPT | `typereference` | 保持 | §7.1 |
| 126 | 返回类型 | DEGRADED | `function.type` / `method.type` | **PHP 的 `finishFunction`（`PHP/translator.mjs:378-388`）根本没有 `returnType` 键** ⇒ 补发（纯接线，`FE/sidecar/SidecarTacMapper.java:140` 已会读） | §7.1 §9 |
| 127 | `void` | KEPT | `typereference` | 保持 | §7.1 |
| 128 | 属性类型 | DEGRADED | `property.type` | 进 `TacTypeDecl.fields` 的类型 | §4.1 §7.1 |

### A.14 异常

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 129 | `try`/`catch` | DEGRADED —— **catch 体不可达** | `try`（`body, catches, always`）+ `catch`（`body, what, variable`） | **接 `tryRegions` wire 字段** ⇒ 异常边有了 ⇒ catch 体可达（IR 侧能力已齐，纯接线） | §6.7 |
| 130 | 多个 catch 块 | DEGRADED | `try.catches[]` | 同 129 | §6.7 |
| 131 | 联合 catch `catch (A\|B $e)` | DEGRADED | `catch.what[]` | `catchTypes` 记**全部**类型（`IR-AP`）；现在只取第一个 | §6.7 §8.2 |
| 132 | `finally` | DEGRADED | `try.always` | `finallyExits` 覆盖四个出口 | §6.7 |
| 133 | `throw` | KEPT | `throw`（`what`） | 保持 + `ctorType`（`IR-J`） | §5.1 |
| 134 | 自定义异常层级 | DEGRADED | `class.ext` | `superType` 链 ⇒ 引擎可匹配子类 | §4.1 §7.1 |
| 135 | `Error` vs `Exception` | DEGRADED | 同上 | 引擎侧异常目录（世界知识，不进 IR） | §7.1 |

### A.15 内建函数与构造

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 136 | `echo` | DEGRADED | `echo`（`expressions, shortForm`） | **输出 sink** ⇒ `CALL <php>.echo/n`（现在不发任何指令 ⇒ 无 finding） | §5.1 §10.1 |
| 137 | `print` | DEGRADED | `print`（`expression`） | 同 136 | §5.1 |
| 138 | `printf` | KEPT | `call` | 保持 | §11 |
| 139 | `include` / `require` | KEPT | `include`（`once, require, target`） | 保持 | §5.1 |
| 140 | `include_once` / `require_once` | DEGRADED | 同上的 `once` / `require` **两个布尔字段** | 四种模式 ⇒ 四个不同的 callee key（现在四个都变成 `<php>.include/1`） | §5.1 |
| 141 | `eval` | KEPT | `eval`（`source`） | 保持 `UNINTERPRETED`；**内容不可静态表达** ⇒ §12 的 `PHP5` | §12 |
| 142 | `extract` | KEPT（黑盒） | `call` | 保持；**创建的变量集合不可静态枚举** ⇒ §12 的 `PHP3` | §12 |
| 143 | `compact` | KEPT | `call` | 保持 | §11 |
| 144 | `isset` | DEGRADED | `isset`（`variables`） | `PropertyPresence` 守卫（`IR-AQ`）；**必须不留下「无 problem 的 `HAVOC`」** | §5.1 §8.2 |
| 145 | `empty` | DEGRADED | `empty`（`expression`） | 同 144 + 真值规则（`IR-H` 的 `PHP_LIKE`） | §5.1 |
| 146 | `unset()` | DEGRADED | `unset`（`variables`） | 删除键（`IR-AQ`）⇒ 污点被正确杀死（现在保留 ⇒ 误报） | §5.1 §8.2 |
| 147 | `var_dump` | KEPT | `call` | 保持 | §11 |
| 148 | `serialize` / `unserialize` | KEPT | `call` | 保持；`__sleep`/`__wakeup` 路由 | §4.1 |
| 149 | `json_encode` / `json_decode` | KEPT | `call` | 保持 | §11 |
| 150 | `str_*` | KEPT | `call` | 保持 | §11 |
| 151 | `preg_*` | KEPT | `call` | 保持 | §11 |

### A.16 字符串

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 152 | 单引号字符串 | KEPT | `string`（`isDoubleQuote=false`） | 保持 | §5.1 |
| 153 | 双引号带插值 | DEGRADED | `encapsed`（`value, raw, type`）+ `encapsedpart`（`expression, syntax, curly`） | 保持降级；**但 `"$_GET['x']"` 让整个文件解析失败** ⇒ §10.4 给出精确机制与两条修法 | §10.4 |
| 154 | heredoc | KEPT | `encapsed`（`type=heredoc`） | 保持 | §5.1 |
| 155 | nowdoc | DROPPED-NOTED | `nowdoc`（`value, raw, label`） | `CONST STRING`（**节点自带 `value`，现在被丢成 `HAVOC`**） | §5.1 |
| 156 | 拼接 | KEPT | `bin`（`type="."`） | 保持 | §5.1 |

### A.17 数组

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 157 | 数组字面量 | KEPT | `array`（`shortForm, items`） | 保持 | §5.1 |
| 158 | `array()` 语法 | KEPT | 同上（`shortForm=false`） | 保持 | §5.1 |
| 159 | 嵌套数组 | KEPT | `entry.value` 又是 `array` | 保持 | §5.1 |
| 160 | 数组展开 `[...$a]` | DROPPED-SILENT | `entry.unpack = true` —— **字段名是 `unpack`，不是 `spread`** | 展开 ⇒ `IR-AR`；现在被当成**一个元素** ⇒ 数组形状与下标全错 | §5.1 §8.2 |
| 161 | `[]` 短语法 | KEPT | `array.shortForm=true` | 保持 | §5.1 |
| 162 | 数组解构 `[$a,$b]` | KEPT | `list`（`shortForm=true`） | 保持 | §5.1 |
| 163 | `list()` | KEPT | `list` | 保持 | §5.1 |

### A.18 生成器

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 164 | `yield` | DROPPED-NOTED | `yield` | 状态机展开；**yield 的值表达式必须求值**（现在完全不求值 ⇒ 源不可见） | §5.3 |
| 165 | `yield from` | DROPPED-NOTED | `yieldfrom` | 状态机 + 委托给子生成器 | §5.3 |
| 166 | `Generator` 类 | KEPT | `typereference` | 保持 | §7.1 |

### A.19 其他语句

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 167 | `declare(strict_types=1)` | DROPPED-SILENT | `declare`（`directives, body, mode`）+ `declaredirective`（`key, value`） | 读 `directives` ⇒ 引擎侧强制转换规则（`IR-Q`） | §10.1 |
| 168 | `goto` | DROPPED-NOTED | `goto`（`label`） | `JUMP` | §6.10 |
| 169 | `global` | DROPPED-SILENT | `global`（`items`） | 作用域绑定（`IR-AS`）⇒ 写路由到模块作用域（现在 `global $x` 让 `$x` 成了一个全新的局部） | §4.1 §8.2 |
| 170 | `static` 局部变量 | DROPPED-SILENT | `static`（`variables`） | 函数静态存储槽（`IR-AS`）；现在初值与「跨调用持久」都没有 | §4.1 §8.2 |
| 171 | `unset()` | DEGRADED | `unset`（`variables`） | 同 146 | §5.1 |
| 172 | `print` | DEGRADED | `print`（`expression`） | 同 137 | §5.1 |
| 173 | `exit` / `die` | DROPPED-NOTED | `exit`（`expression, useDie`） | 终止当前执行单元 ⇒ `THROW`（`IR-J`）；现在**终止本身没有建模**，后续代码仍被当作可达 | §5.1 |

### A.20 PHP 7.x → 8.4 特性

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| 174 | 枚举（8.1） | DROPPED-NOTED | `enum` | 同 42 | §4.1 |
| 175 | `readonly`（8.1/8.2） | DROPPED-SILENT | `class.flags` / `property.readonly` | 同 35 / 49 | §4.1 |
| 176 | 命名实参（8.0） | DEGRADED | `namedargument` | 同 22 | §5.1 |
| 177 | 一等可调用（8.1） | DROPPED-NOTED **+ 输出错误** | `variadicplaceholder` | 同 29 | §5.1 |
| 178 | `never`（8.1） | DEGRADED | `typereference` | 同 119 | §7.1 |
| 179 | Fiber（8.1） | DEGRADED | `call` | 体是闭包 ⇒ 按 §5.3；**交错不可静态确定** ⇒ §12 的 `PHP7` | §12 （`B` 档：记 `drop(SEMANTICS, "fiber.interleave")`） |
| 180 | 初值里的 `new`（8.1） | DROPPED-SILENT | `parameter.value` 是 `new` | 默认值求值 ⇒ §4.5 的 A 级 | §4.5 |
| 181 | 有类型的类常量（8.3） | DROPPED-NOTED | `classconstant.type` | 同 51 + 类型进 `TacTypeDecl` | §4.1 |
| 182 | 交集类型（8.1） | DEGRADED | `intersectiontype` | 同 117 | §7.1 |
| 183 | `#[Attribute]`（8.0） | DROPPED-SILENT | `attrgroup` / `attribute` | 同 76 | §4.6 |
| 184 | DNF 类型（8.2） | DEGRADED | `uniontype` 套 `intersectiontype` | 递归结构（`IR-AP`）；现在塌成第一个类型 | §7.1 |

**附录 A 小计**：**184 条**（= 审计 A 节的全部 184 行，一行不缺），逐条给出「降级形状」或「无法完整支持 + 理由」；其中 `KEPT` **81 条**保持现状，需给落点的 **103 条**按落点分类为 **A（完整表达）99 条 / B（显式记账）3 条 / C（永久例外）1 条**，**`⚠` = 0**；
其中 **99 条有完整降级形状**，**4 条**（#4/#5 变量变量等）的主体有形状、但「与静态变量的同一性」等**运行期决定部分**进 §12。
## 附录 B. Python 审计台账 A.1–A.11 逐条去向（177 条）

**审计来源**：`D:\work\tmp\audit\11-python.md`。
**「AST 节点」列**是 CPython 标准库 `ast` 的**真实**节点名（取自 `PY/plumb_translator.py` 里实际 `isinstance` 判断的类）。
凡审计里的行号与当前工作树漂移的，本文一律用**方法名 + 骨架**定位，并在 §2.2 给出工作树行号。

### A.1 模块

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C01 | `__init__.py` 包命名 | KEPT | `ast.Module`（`.name` 由 `main.py` 算） | 保持 | §4.2 |
| C02 | 绝对导入 `import os` | KEPT | `ast.Import`（`.names` = `ast.alias(name, asname)`） | 保持 | §4.2 |
| C03 | 相对导入 `from ..pkg import y` | DEGRADED | `ast.ImportFrom`（`.module, .names, .level`） | **读 `node.level`** ⇒ 相对与绝对的 owner 不同（现在 `node.level` 从未被读） | §10.2 |
| C04 | `import X as Y` | KEPT | `ast.alias.asname` | 保持 | §4.2 |
| C05 | `from X import *` | DEGRADED | `ast.ImportFrom` 里 `alias.name == "*"` | 星号导入的成员集合是**运行期模块内容** ⇒ 绑不出名字 ⇒ `<unknown>.system/1` ⇒ 漏报。归 §12 的 `PY7` 同族（运行期决定） | §12 |
| C06 | 条件导入 `if TYPE_CHECKING:` | KEPT | `ast.If` 里的 `ast.Import` | 保持（整棵树都被走） | §4.2 |
| C07 | `__all__` | KEPT | `ast.Assign`（目标 `ast.Name("__all__")`） | 保持 | §4.2 |
| C08 | 模块级 `__getattr__` | DEGRADED | `ast.FunctionDef`（`name="__getattr__"`） | 模块属性读路由到它；`__getattr__` **只在属性不存在时**被调用 ⇒ 编译期可判定 ⇒ 完全可表达 | §4.2 |

### A.2 函数

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C09 | `def` | KEPT | `ast.FunctionDef` | 保持 | §4.2 |
| C10 | 默认实参（位置） | DEGRADED | `ast.arguments.defaults` | 默认值在被**定义处**求值 ⇒ 调用点求值（§4.5 的 A 级同构） | §10.2 |
| C11 | 默认实参（关键字专用） | DROPPED-SILENT | `ast.arguments.kw_defaults` | **必须读 `kw_defaults`**（`report_non_constant_defaults` 只读 `defaults`） | §10.2 |
| C12 | `*args` | DEGRADED | `ast.arguments.vararg` | 形参类型 = `ARRAY`；剩余实参 ⇒ `IR-AR`；**arity 规则**：现在 `declared_arity` 把 `*args` 算成 1 个 ⇒ 与「收集剩余」的语义不符 | §4.4 §8.2 |
| C13 | `**kwargs` | DEGRADED | `ast.arguments.kwarg` | 形参类型 = `Map`；**现在不在 `declared_arity` 里 ⇒ 与签名不一致 ⇒ 调用解析不到** | §4.4 |
| C14 | 关键字专用实参（裸 `*`） | DEGRADED | `ast.arguments.kwonlyargs` | 同 C13 ⇒ arity 不一致 | §4.4 |
| C15 | 仅位置实参（裸 `/`） | KEPT | `ast.arguments.posonlyargs` | 保持（两处都数） | §4.4 |
| C16 | 标注（形参/返回） | KEPT | `ast.arg.annotation` / `ast.FunctionDef.returns` | 保持 + `IR-K` 的类型维度 | §7.2 |
| C17 | `async def` | DEGRADED | `ast.AsyncFunctionDef` | 同步降级 + `Effect.SUSPEND`（`IR-L`）；**调度时序不可静态确定** ⇒ §12 的 `PY1` | §4.2 §12 （`B` 档：记 `drop(SEMANTICS, "eventLoop.order")`） |
| C18 | lambda | KEPT（体）/ DEGRADED（值） | `ast.Lambda`（`.args, .body`） | 体 ⇒ 独立函数；**值 ⇒ 闭包对象**（§5.3），不再是 `HAVOC` | §5.3 |
| C19 | 嵌套函数 | KEPT | `ast.FunctionDef` 在 `ast.FunctionDef.body` 里 | 保持 | §4.2 |
| C20 | 闭包 | DEGRADED | `ast.Lambda` | 闭包对象形状 ⇒ 捕获**不再是形参** ⇒ 不再自动是污点源（现在干净捕获也被当污点） | §5.3 §13 K5 |
| C21 | 生成器 | DEGRADED | `ast.Yield` | 状态机；**值表达式在生成器自己的流里求值（现状已对）** | §5.3 |
| C22 | 签名里的 `*` 解包 | DEGRADED | `ast.arguments.vararg` | 同 C12 | §4.4 |
| C23 | 无参装饰器 | DEGRADED | `ast.FunctionDef.decorator_list` 里的 `ast.Name` | **身份进 callee key** ⇒ `CALL <owner>.<name>/n`（现在 `ast.Name` 装饰器的名字**在 TAC 里一个字都没有**） | §4.6 §10.2 |
| C24 | 带参装饰器 | DEGRADED | 装饰器是 `ast.Call` | 同上 + 关键字实参按名绑定 | §4.6 |
| C25 | 叠加装饰器 | DEGRADED | `decorator_list` 多个元素 | 逐个降级，身份各自保留；**应用顺序与源码顺序相反** ⇒ 需在 `TacTypeDecl` 记顺序 | §4.6 |
| C26 | `@property` / `@x.setter` / `@x.deleter` | DEGRADED | 装饰器是 `ast.Name("property")` / `ast.Attribute(attr="setter")` | 读路由到 getter、**写路由到 setter**（现在写是 `ARRAYSTORE`，永不调用 setter） | §4.2 |
| C27 | `@classmethod` | DEGRADED | 装饰器 `ast.Name("classmethod")` | `args[0]` 放**类对象**（现在放实例 ⇒ 类永远绑不到 `cls`） | §4.4 |
| C28 | `@staticmethod` | DEGRADED | 装饰器 `ast.Name("staticmethod")` | **不减接收者**（现在错 ⇒ 一参数方法被算成 arity 0） | §4.4 |
| C29 | `@functools.wraps` | DEGRADED | 装饰器 `ast.Attribute` | 装饰器调用可见 + 身份保留；包装与被包装的链接 ⇒ `TacTypeDecl` 元数据（`IR-AP`） | §4.6 |
| C30 | `@functools.lru_cache` | DEGRADED | 同上 | 缓存是黑盒；装饰后函数的身份 ⇒ `IR-AP` 的元数据 | §4.6 |
| C31 | `@contextlib.contextmanager` | DEGRADED | 同上 | 生成器协议 ⇒ `IR-L` 的挂起点 | §4.6 |
| C32 | `@abc.abstractmethod` | DEGRADED | 装饰器 `ast.Name("abstractmethod")` | `TacTypeDecl.isAbstract` | §4.2 |
| C33 | `functools.partial` | DEGRADED | `ast.Call` | 目标是**编译期已知**的 ⇒ 调用点可展开成「实参前置的调用」（`IR-AR`）；partial 对象本身是 `HAVOC` + `VALUE` 级 drop | §5.2 §8.2 |

### A.3 类

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C34 | 类体 | KEPT | `ast.ClassDef.body` | 合成 `<clinit>` 函数（**改名**） | §4.2 §4.3 |
| C35 | 实例方法 | KEPT | `ast.FunctionDef` 在 `ast.ClassDef.body` 里 | 保持 | §4.2 |
| C36 | `self` | KEPT | `ast.arg(arg="self")` | 保持 | §4.2 |
| C37 | `cls` | DEGRADED | `ast.arg(arg="cls")` | `args[0]` 放类对象 | §4.4 |
| C38 | `__init__` | DEGRADED | `ast.FunctionDef`（`name="__init__"`） | **改名 `<init>` + 去掉 `calleeSig` 的 `<unknown>.` 前缀**（现在两者都不同 ⇒ 永远链接不到） | §4.3 |
| C39 | `__new__` | DEGRADED | `ast.FunctionDef`（`name="__new__"`） | 降级为独立函数；**构造路径的选择是运行期的** ⇒ §12 的 `PY2` | §4.2 §12 （`B` 档：记 `drop(STRUCTURE, "constructor.selection")`） |
| C40 | `__init_subclass__` | DEGRADED | `ast.FunctionDef` | 降级；无调用点 ⇒ `STRUCTURE` 级 drop | §4.2 |
| C41 | `__class_getitem__` | DEGRADED | `ast.FunctionDef` + `ast.Subscript` | `K[int]` ⇒ `CALL <Owner>.__class_getitem__/1` | §4.2 |
| C42 | 继承 | DROPPED-SILENT | `ast.ClassDef.bases` | `TacTypeDecl.superType`（**`bases` 现在完全没被访问**） | §4.2 |
| C43 | 多重继承 | DROPPED-SILENT | `ast.ClassDef.bases` | `interfaces` | §4.2 |
| C44 | MRO | DROPPED-NOTED | `bases` + 引擎侧 C3 线性化 | `superType` 链 ⇒ 引擎算 MRO | §4.2 |
| C45 | `super()` | DEGRADED | `ast.Call`（`func` 是 `ast.Call(func=ast.Name("super"))`） | `super().__init__(x)` ⇒ `CALL <Base>.__init__/n`（MRO 由引擎算） | §4.2 |
| C46 | 类属性 vs 实例属性 | KEPT | `ast.Assign` 在 `ast.ClassDef.body` vs 目标是 `ast.Attribute(value=Name("self"))` | 类属性 ⇒ receiver = 类对象；实例属性 ⇒ receiver = `self` | §4.2 |
| C47 | `__slots__` | KEPT | `ast.Assign`（`__slots__`） | 保持 | §4.2 |
| C48 | `__getattr__` | DEGRADED | `ast.FunctionDef` | 属性不在 `fields` ⇒ 路由 | §4.2 |
| C49 | `__setattr__` | DEGRADED | `ast.FunctionDef` | 同上 | §4.2 |
| C50 | `__delattr__` | DEGRADED | `ast.FunctionDef` | 同上 | §4.2 |
| C51 | `__getattribute__` | DEGRADED | `ast.FunctionDef` | 同上 | §4.2 |
| C52 | 元类 | DEGRADED | `ast.ClassDef.keywords`（`metaclass=`） | `TacTypeDecl.metaclass`（`IR-AP`） | §4.2 |
| C53 | 元类的 `__call__` | DEGRADED | `ast.FunctionDef` | 降级；**构造路径的选择是运行期的** ⇒ §12 的 `PY2` | §12 （`B` 档：记 `drop(STRUCTURE, "constructor.selection")`） |
| C54 | `__repr__` / `__eq__` / `__hash__` | DEGRADED | `ast.FunctionDef` | `a == b` ⇒ `CALL <Owner>.__eq__/2`；`hash(x)` ⇒ `CALL <Owner>.__hash__/1` | §4.2 |
| C55 | `__enter__` / `__exit__` | DROPPED-SILENT | `ast.FunctionDef` + `ast.With` | `with` 绑 **`__enter__` 的返回值**（现在绑的是上下文表达式）+ `__exit__` | §6.9 |
| C56 | `__aiter__` / `__anext__` | DEGRADED | `ast.FunctionDef` + `ast.AsyncFor` | 迭代协议 | §6.4 |

### A.4 dataclass / NamedTuple / TypedDict / Enum / Protocol

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C57 | `@dataclass` | DROPPED-SILENT | `ast.ClassDef.decorator_list` | **类装饰器必须被降级** + 合成 `__init__`（逐字段 `ARRAYSTORE`） | §4.2 §10.2 |
| C58 | `field(default_factory=...)` | DEGRADED | `ast.Call`（`.keywords` = `ast.keyword`） | 关键字实参按名绑定（`IR-AR`） | §4.2 |
| C59 | `@dataclass(init=False)` | DEGRADED | 同上 | 同上 ⇒ 不合成 `__init__` | §4.2 |
| C60 | `@dataclass(frozen=True)` | DEGRADED | 同上 | `isFrozen`（`IR-AP`） | §4.2 |
| C61 | `@dataclass(kw_only=True)` | DEGRADED | 同上 | `parameters` 的 `keywordOnly`（`IR-AO`） | §4.2 |
| C62 | `NamedTuple` | DEGRADED | `ast.ClassDef.bases` 里的 `ast.Name("NamedTuple")` | `TacTypeDecl.isRecord` + 字段默认值是类属性 | §4.2 |
| C63 | `TypedDict` | DEGRADED | 同上（`ast.Name("TypedDict")`） | `isTypedDict`（`IR-AP`）；**字段来自注解（`ast.AnnAssign`），不是语句** ⇒ 必须单独处理 | §4.2 |
| C64 | `Enum` / `IntEnum` / `StrEnum` / `Flag` | DEGRADED | 同上（`ast.Name(...)`） | `isEnum` + 成员是类属性 | §4.2 |
| C65 | `typing.Protocol` | DEGRADED | 同上（`ast.Name("Protocol")`） | `isProtocol`（`IR-AP`） | §4.2 |
| C66 | `@runtime_checkable` | DROPPED-SILENT | `ast.ClassDef.decorator_list` | 同 C57 | §4.2 |

### A.5 语句

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C67 | `if` / `elif` / `else` | KEPT | `ast.If` | 保持 + `guard`/`labelRef` | §6.2 |
| C68 | `while` | KEPT | `ast.While` | 保持 + **`else` 语义修正** | §6.4 §6.8 |
| C69 | `for` | DEGRADED | `ast.For` | 迭代协议（`__iter__`/`__next__`），**元素就是 `__next__` 的结果**；**字典迭代得键**（现在得值） | §6.4 |
| C70 | `break` | DEGRADED | `ast.Break` | 跳 `LOOP_BODY_END`（**跳过** else；现在与 else 同块 ⇒ 语义反了） | §6.8 |
| C71 | `continue` | KEPT | `ast.Continue` | 跳 `LOOP_HEAD`（`for` 是 post 标签） | §6.4 |
| C72 | `try` / `except` / `else` / `finally` | DEGRADED | `ast.Try`（`.body, .handlers, .orelse, .finalbody`） | **接 `tryRegions`** ⇒ except 体可达；finally 的四个出口都有边 | §6.7 |
| C73 | `except*` | DROPPED-NOTED | `ast.TryStar` —— **不是 `ast.Try` 的子类** ⇒ 现在落到兜底 | 显式处理 `ast.TryStar` | §10.2 |
| C74 | 多异常类型 `except (A, B):` | DEGRADED | `ast.ExceptHandler.type` 是 `ast.Tuple` | `catchTypes` 记全部 | §6.7 |
| C75 | 裸 `except:` | KEPT | `ast.ExceptHandler.type` 为 None | 类型 = `Exception` | §6.7 |
| C76 | `raise` | KEPT | `ast.Raise`（`.exc, .cause`） | 保持 + `ctorType`（`IR-J`） | §5.2 |
| C77 | `raise ... from ...` | DEGRADED | `ast.Raise.cause` | `__cause__` 链 ⇒ `THROW` 的 `causeType`（`IR-AP` 细化 `IR-J`） | §8.2 |
| C78 | `assert` | DEGRADED | `ast.Assert`（`.test, .msg`） | **失败路径 ⇒ `THROW`（`AssertionError`）**（现在完全没有 `THROW`） | §5.2 §6 |
| C79 | `with` / `async with` | DEGRADED | `ast.With` / `ast.AsyncWith`（`.items` = `ast.withitem`） | 绑 `__enter__` 的返回值 + `__exit__` | §6.9 |
| C80 | `del` | DEGRADED | `ast.Delete`（`.targets`） | 删除键（`IR-AQ`）⇒ 污点被正确杀死（现在保留 ⇒ 误报） | §5.2 §8.2 |
| C81 | `global` | DEGRADED | `ast.Global`（`.names`） | 作用域绑定（`IR-AS`）⇒ 写路由到**模块作用域**（现在写进函数自己的局部） | §4.2 §8.2 |
| C82 | `nonlocal` | DEGRADED | `ast.Nonlocal`（`.names`） | **必须补进 `collect_declared_globals`**（现在只认 `ast.Global` ⇒ 名字被当成本地绑定） | §10.2 |
| C83 | `pass` | KEPT | `ast.Pass` | 保持 | §10.2 |
| C84 | `import` | KEPT | `ast.Import` / `ast.ImportFrom` | 保持 + 读 `node.level` | §10.2 |
| C85 | `return` | KEPT | `ast.Return` | 保持 | §10.2 |
| C86 | `yield` | DEGRADED | `ast.Yield` | 状态机 | §5.3 |
| C87 | `yield from` | DEGRADED | `ast.YieldFrom` | 状态机 + 委托 | §5.3 |
| C88 | `match` 语句 | DROPPED-NOTED | `ast.Match`（`.subject, .cases`；`ast.match_case` = `.pattern, .guard, .body`；模式节点 `ast.MatchValue`/`MatchSingleton`/`MatchSequence`/`MatchMapping`/`MatchClass`/`MatchStar`/`MatchAs`/`MatchOr`） | **每个 case 体都要降级**（现在只有 subject 被降级 ⇒ 每个 case 体全部消失） | §10.2 §6.6 |
| C89 | 海象 `:=` | KEPT | `ast.NamedExpr`（`.target, .value`） | 保持 | §5.2 |

### A.6 表达式

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C90 | 字面量 | KEPT | `ast.Constant`（`.value`） | 保持 | §7.2 |
| C91 | f-string | DEGRADED | `ast.JoinedStr`（`.values`）+ `ast.FormattedValue`（`.value, .conversion, .format_spec`） | 单 part f-string 产**字符串**（现在返回操作数本身）；`!r`/`!s` ⇒ `CAST` | §5.2 |
| C92 | f-string 的 `=` 自文档形式 | KEPT | `ast.FormattedValue` 的常量部分 | 保持 | §5.2 |
| C93 | f-string 的格式说明 `:` | DEGRADED | `ast.FormattedValue.format_spec` | **必须访问**（现在完全没访问） | §5.2 |
| C94 | f-string 的嵌套格式说明 | DROPPED-SILENT | `ast.FormattedValue.format_spec`（嵌套 `ast.JoinedStr`） | 同上 ⇒ 嵌套里的表达式被求值 | §5.2 |
| C95 | 字符串方法 / `%` / `str.format` / `str.join` | KEPT | `ast.Call` | 保持 | §5.2 |
| C96 | list / dict / set / tuple 字面量 | KEPT | `ast.List` / `ast.Dict` / `ast.Set` / `ast.Tuple`（`.elts` / `.keys, .values`） | 保持 | §5.2 |
| C97 | 推导式（list/set/dict/generator） | DEGRADED | `ast.ListComp` / `ast.SetComp` / `ast.DictComp` / `ast.GeneratorExp`（`.elt, .generators`）；`ast.comprehension`（`.target, .iter, .ifs, .is_async`） | **降级成循环**；循环变量写**推导式自己的局部**（现在泄漏进外层） | §5.6 |
| C98 | 推导式的 `if` 过滤 | DROPPED-SILENT | `ast.comprehension.ifs` | **必须降级**（现在完全没访问 ⇒ 过滤表达式里的 sink 不可见） | §5.6 |
| C99 | 异步推导式 | DEGRADED | `ast.comprehension.is_async` | `Effect.SUSPEND`（`IR-L`） | §5.6 |
| C100 | 条件表达式 | KEPT | `ast.IfExp`（`.test, .body, .orelse`） | 保持 | §5.2 |
| C101 | `and` / `or` 短路 | DEGRADED | `ast.BoolOp`（`.op ∈ {ast.And, ast.Or}, .values`） | **短路展开**（现在两个操作数都求值） | §6.3 |
| C102 | 比较链 | KEPT | `ast.Compare`（`.left, .ops, .comparators`） | 保持 | §5.2 |
| C103 | 位运算 | KEPT | `ast.BinOp`（`ast.LShift`/`ast.RShift`/`ast.BitOr`/`ast.BitXor`/`ast.BitAnd`） | 保持 | §5.2 |
| C104 | `is` / `is not` | KEPT | `ast.Compare`（`ast.Is` / `ast.IsNot`） | 保持 | §5.2 |
| C105 | `in` / `not in` | DEGRADED | `ast.Compare`（`ast.In` / `ast.NotIn`） | **补进 `COMPARISON_OPERATORS`**（现在整个比较是 `HAVOC`） | §5.2 |
| C106 | `not` | KEPT | `ast.UnaryOp`（`ast.Not`） | 保持 | §5.2 |
| C107 | 一元 `-` / `+` | KEPT | `ast.UnaryOp`（`ast.USub` / `ast.UAdd`） | 保持 | §5.2 |
| C108 | 表达式里的 `*` / `**` 解包 | DEGRADED | `ast.Starred`（`.value`）；dict 的 `**` 是 `ast.Dict.keys[i] is None` | 剩余实参（`IR-AR`）；dict 的 `**` 现在被**整个跳过** | §5.2 §8.2 |
| C109 | 切片 `a[1:2:3]` | DEGRADED | `ast.Slice`（`.lower, .upper, .step`） | 三个分量分别求值（现在整个切片是 `HAVOC`） | §5.2 |
| C110 | 下标 | KEPT | `ast.Subscript`（`.value, .slice, .ctx`） | 保持 | §5.2 |
| C111 | 属性访问 | KEPT | `ast.Attribute`（`.value, .attr, .ctx`） | 保持 | §5.2 |
| C112 | 调用（位置） | KEPT | `ast.Call.args` | 保持（receiver 槽保留） | §5.2 |
| C113 | 调用（关键字实参） | DEGRADED | `ast.Call.keywords`（`ast.keyword` = `.arg, .value`） | **按名绑定**（现在按位置传 ⇒ 顺序错） | §5.2 §8.2 |
| C114 | 调用（`*` / `**` 实参） | DEGRADED | `ast.Call.args` 里的 `ast.Starred` / `keywords` 里 `arg=None` | 剩余实参（`IR-AR`） | §5.2 §8.2 |
| C115 | `await` | DEGRADED | `ast.Await`（`.value`） | 值域完整；**调度时序不可静态确定** ⇒ §12 的 `PY1` | §12 （`B` 档：记 `drop(SEMANTICS, "eventLoop.order")`） |
| C116 | `slice(...)` | KEPT | `ast.Call` | 保持 | §11 |
| C117 | `type(...)` / `NamedTuple(...)` / `Enum(...)` | KEPT / DEGRADED | `ast.Call` | 类的构造调用 ⇒ `NEW`；基类进 `superType` | §4.2 |

### A.7 动态特性

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C118 | `eval` | KEPT | `ast.Call` | 保持 `UNINTERPRETED`；**内容不可静态表达** ⇒ §12 的 `PY4` | §12 |
| C119 | `exec` | KEPT | `ast.Call` | 同上 | §12 |
| C120 | `compile` | KEPT | `ast.Call` | 保持 | §11 |
| C121 | `getattr` / `setattr` / `delattr` / `hasattr` | DEGRADED | `ast.Call` | 调用可见；但**动态访问的名字不可静态确定** ⇒ §12 的 `PY5` | §12 |
| C122 | `globals()` / `locals()` | DEGRADED | `ast.Call` | 调用可见；**命名空间内容是运行期状态** ⇒ §12 的 `PY6` | §12 |
| C123 | `__import__` | KEPT | `ast.Call` | 保持 | §11 |
| C124 | `vars()` / `dir()` | KEPT | `ast.Call` | 保持 | §11 |
| C125 | `type()` 作为工厂 | KEPT | `ast.Call` | 保持 | §11 |
| C126 | 模块 `__getattr__` 钩子 | DEGRADED | `ast.FunctionDef` | 同 C08 | §4.2 |
| C127 | `ctypes` | KEPT | `ast.Call` | 保持 | §11 |
| C128 | `sys._getframe` | KEPT | `ast.Call` | 保持 | §11 |
| C129 | monkeypatching | DROPPED-SILENT | `ast.Assign`（重绑定导入名） | **模块命名空间被运行期改写** ⇒ §12 的 `PY7` | §12 |

### A.8 类型标注

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C130 | 形参 / 返回标注 | KEPT | `ast.arg.annotation` / `ast.FunctionDef.returns` | 保持 + `IR-K` 维度 | §7.2 |
| C131 | 变量标注 | DEGRADED | `ast.AnnAssign.annotation` | 进 `TacTypeDecl` + `fieldAnnotations`（**注解通道现在恒空** ⇒ `FE/sidecar/SidecarTacMapper.java:144-147`） | §7.2 §9 |
| C132 | `Optional` / `Union` / `List` / `Dict` / `Set` / `Tuple` | DEGRADED | `ast.Subscript`（`value=ast.Name(...)`） | **不再把下标标注塌成基名** ⇒ `unionMembers` / `typeArguments`（`IR-K`） | §7.2 |
| C133 | `Any` / `Literal` / `Final` / `ClassVar` | DEGRADED | 同上 | 同上 | §7.2 |
| C134 | `TypeVar` / `Generic` | DEGRADED | `ast.TypeVar`（3.12+）/ `bases` 里的 `ast.Subscript` | `bound` + `TacTypeDecl.typeParameters`（`IR-K` / `IR-N`） | §7.2 |
| C135 | `TypeAlias` / PEP 695 `type` 别名 | DROPPED-NOTED | `ast.TypeAlias`（3.12+） | 运行期无对应物 ⇒ 零指令 + `STRUCTURE` 级 drop | §4.2 |
| C136 | `NewType` | DEGRADED | `ast.Call` | `CLASS<名字>` | §7.2 |
| C137 | `Callable` / `Iterable` / `Iterator` / `Generator` | DEGRADED | `ast.Subscript` | `typeArguments` | §7.2 |
| C138 | `Annotated` | DEGRADED | `ast.Subscript` | `typeArguments` + `annotationValues` 记元数据 | §7.2 |
| C139 | `Self` | KEPT | `ast.Name(id="Self")` | `CLASS<声明类>` | §7.2 |
| C140 | `from __future__ import annotations` | KEPT | `ast.ImportFrom`（`module="__future__"`） | 保持（字符串标注被接受） | §7.2 |
| C141 | `TYPE_CHECKING` 块 | KEPT | `ast.If` 里的 `ast.Name("TYPE_CHECKING")` | 保持 | §4.2 |
| C142 | `typing.cast` | KEPT | `ast.Call` | 保持 | §11 |

### A.9 Python 3.11 / 3.12 / 3.13

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C143 | `except*` | DROPPED-NOTED | `ast.TryStar`（3.11+，**不是 `ast.Try` 的子类**） | 同 C73 | §10.2 |
| C144 | 新的类型参数语法 `def f[T](x: T)` | DEGRADED | `ast.FunctionDef.type_params`（3.12+；元素 `ast.TypeVar`/`ast.ParamSpec`/`ast.TypeVarTuple`） | **必须访问**（现在完全没访问）⇒ `TacTypeDecl.typeParameters`（`IR-N`） | §4.2 |
| C145 | PEP 695 `type` 别名 | DROPPED-NOTED | `ast.TypeAlias` | 同 C135 | §4.2 |
| C146 | f-string 嵌套 / 引号复用 | KEPT | `ast.JoinedStr` | 保持 | §5.2 |
| C147 | `Self` | KEPT | `ast.Name(id="Self")` | 保持 | §7.2 |
| C148 | `override` 装饰器 | DEGRADED | `decorator_list` 里的 `ast.Name("override")` | 装饰器身份进 callee key | §4.6 |
| C149 | `warnings.deprecated` / `typing_extensions.deprecated` | DEGRADED | 装饰器 `ast.Attribute` | 同上 | §4.6 |

### A.10 与分析有关的标准库调用

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C150 | `open` | KEPT | `ast.Call` | 保持 | §11 |
| C151 | `os.system` | KEPT | `ast.Attribute`（`value=ast.Name("os")`） | 保持 | §11 |
| C152 | `subprocess.run` / `check_output` / `Popen` | KEPT | `ast.Attribute` | 保持 | §11 |
| C153 | `os.popen` | KEPT | `ast.Attribute` | 保持 | §11 |
| C154 | `eval` / `exec` | KEPT | `ast.Call` | 同 C118 / C119 | §12 |
| C155 | `pickle.loads` / `pickle.load` | KEPT | `ast.Attribute` | 保持 | §11 |
| C156 | `marshal.loads` | KEPT | `ast.Attribute` | 保持 | §11 |
| C157 | `shelve` | KEPT | `ast.Attribute` | 保持 | §11 |
| C158 | `yaml.load` | KEPT | `ast.Attribute` | 保持 | §11 |
| C159 | `json.loads` / `json.load` | KEPT | `ast.Attribute` | 保持 | §11 |
| C160 | `sqlite3` 的 `execute` | DEGRADED | `ast.Attribute`（`value=ast.Call`） | **接收者是 `ast.Call` ⇒ `type_of_receiver` 不认 ⇒ `<unknown>.execute/1`** ⇒ 修 `type_of_receiver` 支持 `ast.Call`（返回构造的类型） | §10.2 |
| C161 | `requests.get` / `requests.post` | KEPT | `ast.Attribute` | 保持 | §11 |
| C162 | `urllib.request.urlopen` | KEPT | `ast.Attribute` | 保持 | §11 |
| C163 | `flask.Flask` 的路由处理函数 | DEGRADED | 装饰器 `ast.Attribute` | 装饰器身份进 callee key ⇒ 框架入口点可识别 | §4.6 |
| C164 | `django` 视图 | DEGRADED | 同上 | 同上 | §4.6 |
| C165 | `fastapi` 端点 | DEGRADED | 同上 | 同上 | §4.6 |
| C166 | `socket` | KEPT | `ast.Attribute` | 保持 | §11 |
| C167 | `tempfile` | KEPT | `ast.Attribute` | 保持 | §11 |
| C168 | `pathlib` 的写方法 | DEGRADED | `ast.Attribute`（`value=ast.Call`） | 同 C160 | §10.2 |
| C169 | `shutil` | KEPT | `ast.Attribute` | 保持 | §11 |
| C170 | `logging` | KEPT | `ast.Attribute` | 保持 | §11 |

### A.11 框架 / 装饰器驱动的入口点

| # | 构念 | 现状 | AST 节点 / API | 去向 | 章节 |
|---|---|---|---|---|---|
| C171 | `@app.route` | DEGRADED | 装饰器 `ast.Attribute`（`value=ast.Name("app")`, `attr="route"`） | **身份进 callee key** ⇒ `<owner>.route/1`（现在只在一个无关的 `<unknown>.route/1` 里出现） | §4.6 |
| C172 | `@app.get` / `@app.post` | DEGRADED | 同上 | 同上 | §4.6 |
| C173 | `@login_required` | DROPPED-SILENT | 装饰器 `ast.Name` | 同 C171 | §4.6 |
| C174 | `@task` / `@celery` | DROPPED-SILENT | `ast.Name` | 同上 | §4.6 |
| C175 | `@receiver` | DROPPED-SILENT | `ast.Name` | 同上 | §4.6 |
| C176 | `@field_validator` / `@validator` | DROPPED-SILENT | `ast.Name` | 同上 | §4.6 |
| C177 | `@app.before_request` | DROPPED-SILENT | 装饰器 `ast.Attribute`（**不是 `ast.Call`** ⇒ 现在只发 `ARRAYLOAD` ⇒ 名字在**任何** callee key 里都没有） | 同 C171（属性装饰器也要发 callee） | §4.6 |

**附录 B 小计**：**177 条**（= 审计 A 节的全部 177 行，一行不缺），逐条给出「降级形状」或「无法完整支持 + 理由」；其中 `KEPT` **69 条**保持现状，需给落点的 **108 条**按落点分类为 **A（完整表达）97 条 / B（显式记账）7 条 / C（永久例外）4 条**，**`⚠` = 0**；
其中 **100 条有完整降级形状**，**8 条**（C05、C129、C121、C122、C118/C119/C154、C39/C53 等）的**运行期决定部分**进 §12 —— 其中 `C17`/`C115`（`async`/`await`）、`C39`/`C53`（构造路径）、以及矩阵乘法的**结果值**已判为 **`B` 档**（本体完整，缺的是「调度顺序」「构造路径的选择」「结果值」这三个运行期事实）⇒ 只有 `C05`/`C129`/`C121`/`C122`/`C118`/`C119`/`C154` 这 7 条仍是真例外。

## 附录 C. 本方案的补充 IR 提案索引

本方案**不重复定义** `01` 文档的主提案（`IR-A` .. `IR-Q`），只**依赖**其中 15 条（见 §8.1，其中 `IR-M` 明确不使用）。
`01` 文档 §10.2 的全局分配表（`01-TAC-IR-扩展设计.md:954-982`）**已把 `IR-AO` 起分配给本文**（`:982`）。
下表是本文占用的 5 个补充提案编号的索引，供评审与实现时逐条对账。

| 编号 | 一句话名称 | 依赖的 `01` 文档主提案 | 序列化影响 | 谁消费 |
|---|---|---|---|---|
| `IR-AO` | 形参声明元数据 `TacFunction.parameters` + `CALL/NEW.argCount`（**与 `IR-AC` 同类事实，见 Q11**） | `IR-P`（细化两条细则）+ `IR-E`（形参声明面） | `TacFunction` / `TacInstruction` **末尾追加**字段 ⇒ 兼容 | `DFA/cg/CallGraphBuilder.java` 的 resolver；`DFA/taint/TaintedCheckerBase.java:5267-5279` 的实参→形参映射 |
| `IR-AP` | 类型交集 / 晚绑定 / trait 链 / 修饰符 / 元类 | `IR-K`（加交集维度）+ `IR-E`（`ownerType` 扩展、`TacTypeDecl` 扩展） | `TacType` / `TacTypeDecl` / `TacInstruction` **末尾追加**字段 ⇒ 兼容 | `DFA/checkers/boundary/Range.java:135` 的 `switch(t.kind())`（值域落点）；`DFA/cg/CallGraphBuilder.java:818`/`:864`、`DFA/cg/VtaCallGraphStrategy.java:244`（按 kind 求类型名）；`DFA/taint/TaintedCheckerBase.java` 的 per-class 字段表（读 `ownerType`） |
| `IR-AQ` | 键存在性守卫 `PropertyPresence` + `ARRAYSTORE.deleteKey`（**沿用 `05` 的 `IR-S` + `IR-V` 同一套字段**） | `IR-C`（守卫变体） | `TacGuard` 的 permits 名单**末尾追加** + `TacInstruction` **末尾追加**字段 ⇒ 兼容 | `DFA/solver/GuardStore.java:69`（`add`，写入；载荷是 **DSL 字符串**不是 `TacGuard`）+ `:115`（`impliedGuard`，查询）；`DFA/taint/TaintedCheckerBase.java` 的位置污点表（删除时清污点） |
| `IR-AR` | 实参按名绑定 `namedArgs`（新增）+ 剩余实参 `spreadArgs`（**沿用 `05` 的 `IR-W`**） | `IR-P`（callee 规范） | `TacInstruction` **末尾追加**两个可选字段 ⇒ 兼容 | `DFA/taint/TaintedCheckerBase.java:5267-5279`；`DFA/cg/CallGraphBuilder.java` 的 resolver |
| `IR-AS` | 作用域绑定与别名 `scopeBindings` + `aliasOf` | `IR-B`（配套：被丢弃的 `global` / `nonlocal` 语义由 drops 显式化） | `TacFunction` / `TacInstruction` **末尾追加**字段 ⇒ 兼容 | `DFA/taint/TaintedCheckerBase.java` 的 per-function 变量表与模块级变量表 |

**跨文档编号关系声明**（避免两份文档对同一形状给出两个编号）：

- `05-降级方案-TypeScript与ArkTS.md` 用 `IR-R` .. `IR-Z` 九个编号（`05` 文档第 10 行；登记于 `01:959-967`）。
  其中 **`IR-S`**（`PropertyPresence` 守卫）、**`IR-V`**（`ARRAYSTORE` 删除键标志）、**`IR-W`**（`CALL`/`NEW` 剩余参数数组）
  与本文的 `IR-AQ` / `IR-AR` **指向同一批形状** ⇒ 本文**沿用同一套字段定义**，
  `IR-AQ` 只补 PHP / Python 的生产侧与消费侧、`IR-AR` 只补 `namedArgs` 这一半，**不另起第二套定义**。
- `04-降级方案-Kotlin与Scala.md` 的 **`IR-AC`**（形参的可选性 / 可变参声明，`01:970`）与本文 `IR-AO` **是同一类事实**，
  但数据形状不同（`Map<String,ParamOption>` vs `List<TacParameter>`）⇒ 需拍板，见 **Q11**。
- `01` 文档 §10.2 的硬约定：① `TacGuard` 的多语言扩展（`IR-S` / `IR-AF` / 本文 `IR-AQ`）**必须在同一个 `TacGuard` 层次里一起做**（`01:987-990`）；
  ② `IR-AE`（`04`）是 `IR-P` 的语言细则、**不是新字段** ⇒ 归到 `IR-P` 下（`01:991`）——本文对 `IR-P` 的两条细化同理。

**实现顺序建议**（按对调用图解析率的影响排序，详见 §13.1 的 K2）：

| 顺序 | 编号 | 理由 |
|---|---|---|
| 1 | `IR-AO` | 修的是「每个 Python 方法调用都解析不到」这一**根因**（§4.4）；不做这条，后面四条都无法验证效果 |
| 2 | `IR-AR` | 与 `IR-AO` 配套（按名绑定需要形参名），且直接消除「污点到错形参」的误报 |
| 3 | `IR-AS` | 修的是「`global G; G = cmd` 现在写进**函数自己的局部**」这一跨函数**污点断点**，对真实漏洞检出影响最大 |
| 4 | `IR-AQ` | 修 `isset`/`unset` 的守卫缺失与「删除不清污点」的误报 |
| 5 | `IR-AP` | 纯类型面精度增强，**不改变调用图形状**，可最后做 |

## 附录 D. 引用抽查样本（源码逐行核对）

**口径**：下表是本文**逐条打开源码、逐行比对**过的引用样本（**不是测试执行记录**——本轮未运行任何构建或测试）。
每条给出「引用位置 → 该处源码实际写了什么 → 本文用它证明什么」，便于评审时复核。
覆盖 PHP 侧车、Python 侧车、IR 本体、引擎、前端映射层、`php-parser` 六个面，共 **30 条**。

| # | 引用 | 该处源码实际内容 | 本文用它证明什么 |
|---|---|---|---|
| D1 | `PHP/translator.mjs:12-17` | 注释明写「PHP an object is a NAMESPACE … `$o->name` and `$o['name']` are the same location … lowered as a CONTAINER ACCESS with a string key — an ARRAYLOAD / ARRAYSTORE」 | P9（PHP 对象是命名空间 ⇒ 继续用 `ARRAYLOAD`/`ARRAYSTORE`，不换 `FIELDLOAD`） |
| D2 | `PHP/translator.mjs:378-388` | `finishFunction` 的返回对象只有 `name`/`signature`/`enclosingClass`/`precision`/`synthetic`/`params`/`locals`/`instructions`/`problems` —— **没有 `returnType` 键** | PHP 每个函数的返回类型恒 `UNKNOWN`（`SidecarTacMapper.java:140` 读到 null）⇒ 纯接线可修 |
| D3 | `PHP/translator.mjs:383` | `synthetic: false,` 写死 | K9（类体 `<clinit>` 显然是合成函数 ⇒ 现状与 `PROTOCOL.md:173` 的「Always false」都错） |
| D4 | `PHP/translator.mjs:1107-1119` | `case "isset"` / `case "empty"` 都 `return this.emitHavoc(node, TYPE.BOOLEAN)`，注释明说「the answer is unknown」 | `isset` 发 `HAVOC` 且**不记任何 problem** ⇒ 函数仍声称 `SOURCE_FULL` ⇒ `IR-AQ` 的必要性 |
| D5 | `PHP/translator.mjs:670-679` | `lowerPropertyStatement`：`this.declareLocal(variable)` + `MOVE dest=variable lhs1=value` | 属性初值今天是**类体里的一个局部**，不是字段存储 ⇒ §4.3 要改成 `ARRAYSTORE this["x"]`，并触发 K1 |
| D6 | `PHP/translator.mjs:1810-1820` | `argumentsOf`：`if (argument.kind === "namedargument") out.push(argument.value);` | 命名实参的**名字被丢掉** ⇒ 值被绑到错的形参 ⇒ `IR-AR` 的 `namedArgs` |
| D7 | `PHP/translator.mjs:765` / `:776` | `:765` 读 `node.test`；`:776` 读 `node.loop` | php-parser 的 `for` 节点字段真名是 `init`/`test`/`increment`/`body`/`shortForm`（`PP/parser/loops.js:69`/`:80`/`:97`）⇒ `test` 是**数组**、增量字段叫 **`increment`** ⇒ 条件成 `HAVOC`、增量被丢 |
| D8 | `PHP/translator.mjs:2179-2181` | `if (node.kind === "nullable") { return this.typeNameOf(node.type); }` | php-parser 3.7.0 不产生 `nullable` 节点 ⇒ **死分支**；可空性在 `function/parameter/property/classconstant.nullable` 的布尔字段上 |
| D9 | `PHP/translator.mjs:2175-2178` | `uniontype`/`intersectiontype` ⇒ `types[0]` | 联合 / 交集类型**只取第一个**，其余静默丢失 ⇒ `IR-AP` 的 `unionMembers`/`intersectionMembers` |
| D10 | `PHP/main.mjs:137-142` | `catch (e) { return failure(OP.TRANSLATE, \`the file is not valid PHP: …\`) }` | `"$_GET['x']"` 这类写法导致**整文件解析失败** ⇒ `PhpTacFrontend.java:210-218` 记 `PARSE_PROBLEM` 并返回 0 个函数 |
| D11 | `PHP/main.mjs:159` | `diagnostics: [],` 恒空 | 响应里没有结构化 problem 列表 ⇒ §9.1 要新增 `problems` 键 |
| D12 | `PHP/package.json:12` | `"php-parser": "3.7.0"` | 本文所有 AST 节点名/字段名以 3.7.0 为准 |
| D13 | `PHP/PROTOCOL.md:173` | `\| \`synthetic\` \| Always false: every function in the stream comes from the user's own code. \|` | 协议文档与实现**都**错（类体函数就是合成的）⇒ K9 |
| D14 | `PP/lexer/strings.js:350-372` | `consume_VARIABLE()`：`if (ch == "[") { this.unput(1); this.begin("ST_VAR_OFFSET"); return this.tok.T_VARIABLE; }` | `"$_GET['x']"` 的**第一段是对的**（进入 `ST_VAR_OFFSET`）⇒ 失败点在下一段 |
| D15 | `PP/lexer/property.js:75-81` | `ch === "'" \|\| … ` ⇒ `return this.tok.T_ENCAPSED_AND_WHITESPACE;` | 单引号被归为**填充文本**而不是字符串记号 ⇒ 下一段解析拿不到 `T_STRING` |
| D16 | `PP/parser/variable.js:247-279`（`:266-277`） | `read_encaps_var_offset()` 只接受 `T_STRING`/`T_NUM_STRING`/`-`/`T_VARIABLE`，否则 `this.expect([...])` 失败 | D15 ⇒ **`:266-277` 的 `expect` 失败** ⇒ 整文件解析失败（§10.4 的精确机制） |
| D17 | `PP/lexer/strings.js:428-440` | `else if (ch === "{") { ch = this.input(); if (ch === "$") { this.begin("ST_IN_SCRIPTING"); …` | 修法 A（改写成 `"{$var[...]}"`）走的是 `ST_IN_SCRIPTING` ⇒ 复杂插值可解析 |
| D18 | `PY/plumb_translator.py:381` / `:382` | `self.defined_methods.setdefault(node.name, node)` / `self.class_methods.setdefault((parent.name, node.name), node)` | `defined_methods` **只按方法名**索引 ⇒ 同名方法跨类串味；而 `return_type_of_callee`（`:1960`）查的是按 (类, 方法) 的 `class_methods` ⇒ **返回类型按类、arity 不按类** |
| D19 | `PY/plumb_translator.py:560` / `:571` / `:572` | `collect_loaded_names(node.decorator_list, out, bound_here)` / `collect_loaded_names(node.bases, out, bound_here)` | 装饰器名与基类名被算作**外层作用域的「被读名字」** ⇒ 可能成为外层函数的自由变量 ⇒ 被提升为前导形参 ⇒ 外层函数签名/arity 改变（K10） |
| D20 | `PY/plumb_translator.py:604-613` | `collect_declared_globals`：`if (isinstance(node, ast.Global)) out.update(node.names);` | **不认 `ast.Nonlocal`** ⇒ `nonlocal` 现在被当成局部绑定 ⇒ `IR-AS` 的 `scopeBindings` |
| D21 | `PY/plumb_translator.py:804-813` vs `:2270-2273` | `parameters_of` 含 `posonlyargs + args + vararg + kwonlyargs + kwarg`；`declared_arity` 只数 `posonlyargs + args (+vararg)` | 两处数的是**两套东西** ⇒ 任何带关键字专用形参或 `**kwargs` 的函数，calleeSig arity 与签名**永不相等** ⇒ 调用解析不到 |
| D22 | `PY/plumb_translator.py:632-674` | `lower_lambda` 注释：「The captured names become the LEADING PARAMETERS of the new function … a parameter is a source, so the body is analysed with the captured value already tainted」 | 捕获值今天**自动是污点源** ⇒ 干净的捕获也会被当污点（K5）；§5.3 要改成闭包对象 + 显式传捕获 |
| D23 | `PY/plumb_translator.py:144` / `:2038-2039` | `UNKNOWN_OWNER = "<unknown>"`；`NEW` 的 `calleeSig` 是 `"%s.%s.<init>/%d" % (UNKNOWN_OWNER, class_name, …)` | `NEW` 永远链接不到构造器体 ⇒ 构造器摘要取不到；且 owner 用占位符违反 `IR-P`（Q4） |
| D24 | `PY/plumb_translator.py:860-861` | 类体函数签名 `"%s.<init>():any" % class_name` | 类体签名与 `NEW` 的 calleeSig **owner 不同**、返回类型也不同 ⇒ 即使补上 `<init>` 也要同时统一两处 |
| D25 | `IR/TacType.java:40-64` | `Kind` 枚举 15 个常量（`BOOLEAN`…`UNKNOWN`） | `IR-AP` 为什么**不加 `Kind`**：加 `Kind` 要波及 9 处 `switch (t.kind())` ⇒ 用可选字段 |
| D26 | `IR/TacInstruction.java:194-195` / `:228` | `lhs1`/`lhs2` 两个操作数槽；`public final List<TacOperand> args;` | `ARRAYSTORE` 只能写值不能删键；`args` 是**定长列表** ⇒ `deleteKey` 与 `namedArgs`/`spreadArgs` 必须新增字段 |
| D27 | `IR/TacFunction.java:73` / `:123-126` | `private final List<TacOperand.TacVar> params;`；四个注解通道 `classAnnotations`/`methodAnnotations`/`fieldAnnotations`/`paramAnnotations` | 形参**只有变量列表**、没有「可选/可变参/按引用」⇒ `IR-AO` 的 `parameters`；注解通道已有 ⇒ §9.1 只做接线 |
| D28 | `IR/TacCfgBuilder.java:205` + `FE/sidecar/SidecarTacMapper.java:150` | `buildExceptionSuccessors(blocks, insns, labelToBlock, fn.tryRegions())`；而映射层写死 `List.of()` | **异常边的能力已经在 IR 里**，缺的只是 wire 字段 ⇒ PHP 的 `catch` / Python 的 `except` 可达性是纯接线问题（K6） |
| D29 | `DFA/solver/GuardStore.java:35-37` / `:54` / `:69` / `:115` + `DFA/solver/PathCondition.java:58` | 「Keys are the guarded variable's name (string, not TacVar) … guards are surfaced in the same flat string form the bridge speaks」；`Map<String, List<String>> byVar`；`add(String var, String predicate)`；`impliedGuard(...)` | `GuardStore` 的载荷是**字符串 DSL 谓词**、不是 `TacGuard` 对象 ⇒ `PropertyPresence` 必须先渲染成 DSL 串（`09` §3 的渲染表） |
| D30 | `DFA/taint/TaintedCheckerBase.java:985` / `:5262-5282` | `for (TacOperand.TacVar p : fn.params())`（形参即污点源）；实参→形参映射按「实例调用 `args[i+1]`、静态调用 `args[i]`」两级回退 | 形参即污点源 ⇒ 闭包捕获提成形参就自动带污点（K5）；`IR-AO`/`IR-AR` 要替换这段「位置 + 猜测」的映射 |

**另两条值得单独记的事实**（不在上表，但同样经核对）：

- `DFA/checkers/taint/DivByZeroChecker.java:216` 是 `plumb-dfa/src/main` 里**唯一**的 `guardStore().add(...)` 调用点；
  `DFA/checkers/boundary/BoundaryConstraintChecker.java:694` 的 `emitGuard` 写的是它**自己的局部 `guards` map**（`:718`），
  **并不进 `GuardStore`** ⇒ 「跨 checker 共享守卫」这条机制目前几乎是**单生产者**的。
- `DFA/checkers/boundary/Range.java:135` 的 `switch (t.kind())`（`Range.ofType`）才是 `IR-D` 的 `domain` 真正落点，
  而 `DFA/checkers/boundary/BoundaryConstraintChecker.java:135` 是**空行**（它上面那行 `:134` 才是分隔注释
  `// ─────────── Subclass hooks ───────────`），该文件里根本没有按 `TacType.Kind` 的 switch ——
  本文已按实际代码更正引用。
