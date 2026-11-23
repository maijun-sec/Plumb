# 降级方案：TypeScript / JavaScript 与 ArkTS 前端（IR v2 前端侧）

**路径缩写**（下文所有 `file:line` 均相对于仓库根 `D:\work\workspace\idea\engine\Plumb`）：
`TS/` = `plumb-frontend/ts-sidecar/src/`；`AK/` = `plumb-frontend/arkts-sidecar/src/`；
`IR/` = `plumb-tac/src/main/java/org/plumb/tac/ir/`；`FE/` = `plumb-frontend/src/main/java/org/plumb/tac/frontend/`；
`DFA/` = `plumb-dfa/src/main/java/org/plumb/dfa/`；
`npm/ts` = `plumb-frontend/ts-sidecar/node_modules/typescript/`；`npm/ohos` = `plumb-frontend/arkts-sidecar/node_modules/ohos-typescript/`。

**前提**：IR v2 形状已定稿（`01-TAC-IR-扩展设计.md`，下称 `IR v2 §IR-A`…`§IR-H`），本文只做前端降级方案，不重设 IR。
**本文的补充 IR 提案编号**：`IR-R`…`IR-Z`（§8 的 9 条）+ `IR-AT`（§8 的第 10 条，2026-11 补齐九处缺口时新增）—— 与全套文档共享同一编号空间，`01` 占用 `IR-A`…`IR-Q`，`06` PHP/Python 已占用 `IR-AO`…`IR-AS`（`01` §10.2 的全局分配表），故本文新编号从 `IR-AT` 起；`IR-AT` 已在 `01` §10.2 的分配表里登记为 `05` TS/ArkTS 的条目。
**口径**：**完整支持优先**。能降级出来的一律降级出来；只在「运行期才确定 / 外部世界副作用顺序 / 语言本身无静态语义」三类下允许保守建模（§12 逐条论证）。
**硬约束**：不新增 IR opcode（IR v2 §0.1）；不点名任何商用分析工具。

---

## 1. 目标与非目标

| 编号 | 目标 | 落点 |
|---|---|---|
| G1 | TS/JS 与 ArkTS 的**每个**构念都有完整 TAC 表达；没有「不认识 ⇒ `HAVOC` + drop」的默认路径 | §4 §5 §6 §11 |
| G2 | 语义保真优先于结构保真：求值顺序、短路、可选链、switch 选择、异常边、迭代协议全部建模 | §6 |
| G3 | 类型保真：联合保留成员、`number` 是 double、`bigint` 不是 double、类型实参不丢 | §7 |
| G4 | 分支必带结构化守卫（§IR-C），跳转必带标签语义（§IR-G） | §6 §9 |
| G5 | 两个 sidecar 共享同一 wire 契约与同一构念注册表，防漂移 | §2 |
| G6 | 精度由丢弃推导（§IR-B）；丢弃只在 §12 例外里产生 ⇒ 正常路径应为 `SOURCE_FULL` | §3 |

**非目标**：① 不新增 opcode，确需新能力走 §8；② 不建模事件循环/微任务时序（TAC 是顺序指令流，`IR/TacInstruction.java:194-242`）；③ 只建模与分析相关的内建子集：迭代协议、`Object.keys`、数组 `length`；④ 不做跨文件类型推断，类型取自各自 checker（`TS/translator.mjs:1351-1361`、`AK/translator.mjs:1633-1647`）。

---

## 2. 两个 sidecar 的关系与同步策略

### 2.1 现状：同一协议的两个 fork

| 维度 | TS/JS | ArkTS | 证据 |
|---|---|---|---|
| 编译器 | npm `typescript` 7.0.2（原生预览 API） | npm `ohos-typescript` 4.9.5-r10 | `plumb-frontend/ts-sidecar/package.json:12`、`plumb-frontend/arkts-sidecar/package.json:15` |
| AST 入口 | `typescript/unstable/ast` 的 `isXxx` + `unstable/sync` 的 `TypeFlags` | `ohos-typescript` 的 `ts.isXxx` | `TS/translator.mjs:33-79`、`AK/translator.mjs:40` |
| 可选链 | 节点上的 `questionDotToken` 字段 | 独立的 `isPropertyAccessChain`/`isElementAccessChain`/`isCallChain` 节点种类 | `npm/ts/dist/ast/ast.generated.d.ts:485,491,497`、`npm/ohos/lib/typescript.d.ts:5259-5262` |
| 协议表 | `KIND`/`TYPE`/`CONST`/`PRECISION`/`PROBLEM`/`OP` | **同六张表，逐字复制** | `TS/protocol.mjs:26-111` 与 `AK/protocol.mjs:33-118` |
| Java 映射 | `FE/sidecar/SidecarTacMapper.toFunction`/`toInstruction` | 同一份 | `FE/sidecar/SidecarTacMapper.java:75-156,159-227`；`FE/ts/TsTacFrontend.java:275`、`FE/arkts/ArktsTacFrontend.java:265` |

ArkTS 的协议头部已写明分叉理由（`AK/protocol.mjs:1-10`）：两边编译器不同、AST 不同，共享文件会把一种编译器塞进另一种的形状。**该顾虑只对 AST 层成立**——协议常量表与构念注册表里没有一行 AST 代码。

### 2.2 必须同步 / 可以不同

| 必须同步（wire 契约） | 可以不同（降级实现） |
|---|---|
| 指令/类型/常量种类名；`owner.method/arity` 键形；receiver 恒在 `args[0]`；类型编码 | AST 分派（谓词名、节点字段形状） |
| `drops`/`guard`/`labelRef`/`domain`/`memberKind`/`ownerType`/`tryRegions`/`types` 的键名与形状 | 语言特有构念：TS 的 class/namespace/装饰器 vs ArkTS 的 struct/`@State`/组件 DSL |
| 构念 id 词表（§2.3） | 构念 id 的子集；保守建模的判定（各自论证） |

### 2.3 防漂移机制

| 机制 | 内容 |
|---|---|
| **M1 共享语言中立注册表** | 新建 `plumb-frontend/sidecar-shared/protocol.mjs`（**只含常量，无 AST 代码**）+ `constructs.json`（构念 id → 期望 TAC 形状 / guard 种类 / memberKind / wire 键）；两侧改相对路径 import |
| **M2 一致性语料** | `sidecar-shared/conformance/<construct-id>.{ts,ets}.txt` + 期望 TAC 骨架 JSON；两侧各跑一遍，断言**同一 construct id 产出同样 TAC 形状**（字段级比较，忽略行号） |
| **M3 单一来源门禁** | CI：两侧不得再持有 `protocol.mjs` 副本（只许 re-export）；`constructs.json` 过 schema；新增 wire 键必须同时出现在 `constructs.json` 与 Java 映射里 |

构念 id 词表（节选，完整见 `constructs.json`）：`expr.short_circuit.and`、`expr.logical.or`、`expr.nullish_coalesce`、`expr.ternary`、`expr.optional_chain`、`expr.instanceof`、`expr.in_operator`、`expr.typeof`、`expr.await`、`expr.spread`、`expr.closure`、`expr.generator_yield`、`stmt.switch`、`stmt.try_catch_finally`、`stmt.labelled_jump`、`stmt.for_of`、`stmt.for_in`、`decl.class_field`、`decl.accessor`、`decl.enum`（两侧都有）；`decl.struct`、`decl.component_dsl`（仅 ArkTS）。

**本轮补齐 141 行台账缺口后新增登记的构念 id**（仍只进 `constructs.json`，本节只列节选）：`expr.optional_call`、`expr.optional_index`、`expr.logical_assignment`、`expr.unary_operator`、`expr.comma`、`expr.void_operator`、`expr.regex_literal`、`expr.jsx_element`、`expr.eval_call`、`expr.new_function`、`expr.dynamic_import`（已有）、`expr.type_only_import`、`expr.module_export`、`expr.commonjs_export`、`expr.callback_argument`；`stmt.module_top`、`stmt.using`、`stmt.for_await`、`stmt.labelled_jump`（已有）；`decl.implements`、`decl.interface_member`、`decl.type_alias`、`decl.parameter_property`、`decl.visibility`、`decl.readonly_field`、`decl.enum_member`、`decl.string_enum`、`decl.const_enum`、`decl.module_scope`；`decl.state_decorator`（仅 ArkTS）、`decl.idata_source`（仅 ArkTS）、`decl.runtime_context`（仅 ArkTS）。⇒ 两侧的 `constructs.json` **必须同步**（§2.3 M3 的单一来源门禁）。

---

## 3. 完整覆盖原则与允许的例外

| 编号 | 原则 |
|---|---|
| P1 | 每个分派分支都产出完整 TAC；**不得**保留「不认识 ⇒ `HAVOC` + drop」的兜底。兜底仅在编译器内部错误时触发并记 `SEMANTICS` 级 drop ⇒ 删除 `TS/translator.mjs:608` 与 `AK/translator.mjs:680` 的兜底 `report` |
| P2 | 值级保守建模只允许三类：(a) 运行期才确定的值；(b) 外部世界的副作用顺序；(c) 语言本身无静态语义。其余一律建模 |
| P3 | 每次保守建模必须写清：为什么无法静态表达 / 保守形状 / 精度影响（§12） |
| P4 | 精度由 drops 推导（§IR-B：`SEMANTICS`⇒`SOURCE_DEGRADED`；`STRUCTURE`⇒`SOURCE_LOWERED`；仅 `VALUE`⇒仍 `SOURCE_FULL`）。现状把「有 problem ⇒ `SOURCE_LOWERED`」写死在 `TS/translator.mjs:522-527` 与 `AK/translator.mjs:551-556`，须改成读 drops |
| P5 | 每个 `BRANCH` 必带 `guard`（§IR-C），否则 Z3 拿不到路径条件 |
| P6 | 每个 `LABEL`/`JUMP`/`BRANCH` 必带 `labelRef`（§IR-G），且 `labelRef.name` 必须等于 `label`/`trueLabel`/`falseLabel` |
| P7 | 语义等价优先于结构保真：能表达就不写 drop。`switch` 降级成分派链但选择语义完整 ⇒ 仍是 `SOURCE_FULL` |
| **P8** | **分档判据（可执行）**：`A` = 构念变成真实 TAC 形状（指令**或** IR 元数据），无语义损失；`B` = 构念**本体**完整降级，缺的**只是一个运行期才定的事实**，且该缺失用 `drop` **显式记账**；`C` = 构念**本体**无法降级（被调方不在源码里 / 边由框架建立 / 编译期产物 / 语言本身无静态语义），只能给保守形状。判定只看附录「去向」列：**指向 §12 的 `TSn` ⇒ 查 §12 该条的「档」列**；不指向但有 `drop` 记账 ⇒ `B`；都没有 ⇒ `A`。§12 的表头有完整对照 |

**与旧口径的差异**：旧口径把「近似」当降级——`TS/translator.mjs:153-162` 的近似表把 `??` 映成 `LOR`、`**` 映成 `MUL`，而 `:1002-1005` 先查近似表 ⇒ `BINARY_OPERATORS` 里的 `"NULL_COALESCE"`（`:107`）与 `"POW"`（`:96`）**是死条目**。更糟：二者都不是合法 IR 算子（`IR/BinaryOperator.java:30-53` 只有 `ADD…USHR`/`EQ…GE`/`LAND LOR`）；若真发上线，`FE/sidecar/SidecarTacMapper.java:198` 的 `valueOf` 抛异常会被 `:199-201` 吞成 `ADD`。⇒ **口径反转**：`??` 与 `**` 不再近似，按 §5 真正展开。

---

## 4. 声明面方案

**分界线**：普通对象是**命名空间**（`obj.userId` 与 `obj["userId"]` 同一位置）⇒ 沿用 `ARRAYLOAD`/`ARRAYSTORE`（约定 `plumb-frontend/ts-sidecar/PROTOCOL.md:271-291`，实现 `TS/translator.mjs:855-882,1066-1103`）；**类字段是真字段**（有声明、owner、类型、初始化顺序）⇒ 用 `FIELDLOAD`/`FIELDSTORE` + `ownerType`（§IR-E）。这让 `DFA/checkers/taint/NullDerefChecker.java:261-272` 对类字段给出正确措辞，而不是把 `this.name` 报成「数组句柄读」。

**列的读法**：「AST 节点」列给**解析器里的节点名**（TS 侧是 `typescript/unstable/ast` 的 `isXxx` 谓词，ArkTS 侧是 `ohos-typescript` 的 `ts.isXxx`）；「现状」列是**审计台账的证据串**（相对 sidecar 的 `src/` 目录，两面各给一条），与「完整 TAC 表达」列里的 `TS/`、`AK/` 前缀（本文档的路径缩写，见文首）是同一批行号的两种写法。

| 源构念 | AST 节点（TS / ArkTS sidecar） | 完整 TAC 表达 | `memberKind` | 现状（审计证据） | 需要的 IR 能力 |
|---|---|---|---|---|---|
| 类字段（有初始化器）`x = 1` | `PropertyDeclaration`（TS `isPropertyDeclaration` / ArkTS `ts.isPropertyDeclaration`） | 合成 `FIELD_INITIALIZER` 函数：receiver 为 `this`，`FIELDSTORE this.x = <init>`，`ownerType` = 类名 | `FIELD_INITIALIZER` | `translator.mjs:473-479`（TS）/ `translator.mjs:406-424`（ArkTS） | §IR-E `memberKind`+`ownerType` |
| 类字段（无初始化器） | 同上 | 运行期无行为 ⇒ 零指令；字段名与类型进 `TacTypeDecl.fields` | `FIELD_INITIALIZER` | `translator.mjs:473-479`（TS）/ `translator.mjs:406-424`（ArkTS） | §IR-E |
| 静态字段 | 同上（`ModifierFlags.Static`） | receiver 是类对象（合成变量 `t_cls_<Name>`，类型 `CLASS<Name>`）⇒ `FIELDSTORE t_cls_Name.x = <init>` | `FIELD_INITIALIZER` | `translator.mjs:473-479`（TS）/ `translator.mjs:344, 406-424`（ArkTS） | §IR-E |
| 构造函数 | `ConstructorDeclaration`（TS `isConstructorDeclaration` / ArkTS `ts.isConstructorDeclaration`） | 独立函数，名字 `<init>`，签名 `mod.Class.<init>(参数)`；`super(...)` ⇒ `CALL mod.Base.<init>/n`（args[0]=`this`） | `CONSTRUCTOR` | `translator.mjs:473-479`（TS）/ `translator.mjs:487-501`（ArkTS） | §IR-E |
| 方法 | `MethodDeclaration`（TS `isMethodDeclaration` / ArkTS `ts.isMethodDeclaration`） | 现状即是（`TS/translator.mjs:457-467`） | `METHOD` | `translator.mjs:476-478`（TS）/ `translator.mjs:392-394`（ArkTS） | 现有 IR 即可 |
| getter `get x()` | `GetAccessorDeclaration`（TS `isGetAccessorDeclaration` / ArkTS `ts.isGetAccessorDeclaration`） | 独立函数（owner = 所属类）；体内 `this.x` ⇒ `FIELDLOAD`；调用点 `obj.x` ⇒ `CALL <Owner>.x/0`（args[0]=obj） | `GETTER` | `translator.mjs:473-479`（TS）/ `translator.mjs:387-395`（ArkTS） | §IR-E |
| setter `set x(v)` | `SetAccessorDeclaration`（TS `isSetAccessorDeclaration` / ArkTS `ts.isSetAccessorDeclaration`） | 独立函数；体内 `this.x = v` ⇒ `FIELDSTORE`；调用点 `obj.x = v` ⇒ `CALL <Owner>.x/1`（args[0]=obj, args[1]=v） | `SETTER` | `translator.mjs:473-479`（TS）/ `translator.mjs:387-395`（ArkTS） | §IR-E |
| 静态块 `static {…}` | `ClassStaticBlockDeclaration`（TS `isClassStaticBlockDeclaration` / ArkTS `ts.isClassStaticBlockDeclaration`） | 合成函数；类初始化时 `CALL mod.Class.<clinit>/0` | `STATIC_INITIALIZER` | `translator.mjs:473-479`（TS）/ `translator.mjs:387-395`（ArkTS） | §IR-E |
| 索引签名 `[k: string]: T` | `IndexSignatureDeclaration`（TS `isIndexSignatureDeclaration`） | 运行期无行为 ⇒ 零指令；进 `TacTypeDecl`（需 **IR-Y** 扩展） | — | `translator.mjs:608`（TS） | §IR-E 扩展 |
| 枚举 `enum E {A,B}` | `EnumDeclaration` + `EnumMember`（TS `isEnumDeclaration` / `isEnumMember` / ArkTS `ts.isEnumDeclaration`） | 合成函数：`NEW CLASS<E>` + `ARRAYSTORE t_e["A"]=0`、`["B"]=1`、反向映射 `["0"]=t_e["A"]` | `FIELD_INITIALIZER` | `translator.mjs:608`（TS）/ `translator.mjs:432-462`（ArkTS） | §IR-E |
| 命名空间 `namespace N {…}` | `ModuleDeclaration`（TS `isModuleDeclaration` / ArkTS `ts.isModuleDeclaration`） | 编译成 IIFE ⇒ `CALL` + 块；成员各自独立函数 | `METHOD` | `translator.mjs:608`（TS）/ `translator.mjs:680`（ArkTS） | 现有 IR 即可 |
| 重载声明 | `FunctionDeclaration`（无体）/ `MethodDeclaration`（无体） | 只有签名无体 ⇒ 不生成函数；进 `TacTypeDecl.members` | — | `translator.mjs:457-467`（TS）/ `translator.mjs:480-482`（ArkTS） | §IR-E |
| `declare` 声明 | `ModuleDeclaration` + `ModifierFlags.Ambient` / `PropertyDeclaration` + `DeclareKeyword` | 运行期无实体 ⇒ 不生成函数；进 `TacTypeDecl` | — | `translator.mjs:473-479`（TS）/ `translator.mjs:407-410`（ArkTS） | §IR-E |
| 抽象方法 | `MethodDeclaration`（无体）+ `ModifierFlags.Abstract` | 无体 ⇒ 不生成函数；进 `TacTypeDecl.members`，类标 `isAbstract` | — | `translator.mjs:473-479`（TS）/ `translator.mjs:480-482`（ArkTS） | §IR-E |
| 嵌套函数 | `FunctionDeclaration` / `ArrowFunction`（TS `isFunctionDeclaration` / `isArrowFunction`） | 提升为独立函数（`TS/translator.mjs:490-507`；ArkTS 同形 `AK/translator.mjs:510-536`） | `METHOD` | `translator.mjs:490-507`（TS）/ `translator.mjs:510-536`（ArkTS） | 现有 IR 即可 |
| 变量解构 `const {a,b}=obj` | `ObjectBindingPattern`（TS `isObjectBindingPattern` / ArkTS `ts.isObjectBindingPattern`） | `t_a = ARRAYLOAD obj["a"]; MOVE a = t_a`；`b` 同理 | — | `translator.mjs:611-629`（TS）/ `translator.mjs:688-700`（ArkTS） | 现有 IR 即可 |
| 数组解构 `const [a,b]=arr` | `ArrayBindingPattern`（TS `isArrayBindingPattern` / ArkTS `ts.isArrayBindingPattern`） | `t_a = ARRAYLOAD arr[0]; MOVE a = t_a`；`b` 同理 | — | `translator.mjs:614`（TS）/ `translator.mjs:688-700`（ArkTS） | 现有 IR 即可 |
| 剩余元素 `const {a,...rest}=obj` | `BindingElement` + `DotDotDotToken`（TS `isBindingElement`） | `t_rest = NEW CLASS<Object>`；用 `Object.keys(obj)` 键循环逐键判断，命中被取走的键则跳过，否则 `ARRAYSTORE t_rest, t_key, t_value`（`lhs2` 支持变量） | — | `translator.mjs:614`（TS）/ `translator.mjs:688-700`（ArkTS） | 现有 IR 即可 |
| **参数解构** `f({a,b}: Props)` | `ParameterDeclaration` + `ObjectBindingPattern`（TS `isParameterDeclaration`） | 造合成参数 `p`（类型 = 声明类型 `Props`），函数体开头 `t_a = ARRAYLOAD p["a"]; MOVE a = t_a`；callee 签名仍 `mod.f(Props)`，目录匹配不受影响 | — | `translator.mjs:539`（TS）/ `translator.mjs:565-575`（ArkTS） | 现有 IR 即可 |
| 装饰器 `@dec`（**TS 与 ArkTS 的普通装饰器**） | `Decorator`（TS `isDecorator` / ArkTS `ts.isDecorator`） | **运行期就是一个被调用的函数** ⇒ 发真实 `CALL`（§4.1）：类装饰器 `@dec class C` ⇒ `t_d = CALL <dec>/1 args=[C]`；成员装饰器 `@dec m()` ⇒ `t_d = CALL <dec>/2 args=[C.prototype, "m"]`；**legacy** 语义下类装饰器的返回值**替换** `C`，**standard** 语义下不替换；装饰器链、阶段与替换语义进 `TacTypeDecl.decorators` / `TacMemberRef.decorators`（**IR-AV**）⇒ **装饰器实参的污点能流进被装饰实体** | `METHOD` | `translator.mjs:473-479`（TS）/ `translator.mjs:205-257`（ArkTS） | **IR-AV** |
| ArkTS `struct` | `StructDeclaration`（ArkTS `ts.isStructDeclaration`） | 现状「一个 struct 初始器 + 每方法一个函数」（`AK/translator.mjs:341-367`）；补访问器、静态成员；**初始器只发一次**，字段初始化器不复制进每个方法 | 同上 | `translator.mjs:341-367`（ArkTS） | §IR-E |
| ArkTS `@State`/`@Prop` 属性 | `PropertyDeclaration` + `ModifierLike`（ArkTS `ts.isPropertyDeclaration`） | 装饰器由框架调用（清单 `AK/compiler.mjs:53-65`）⇒ §12 例外；**属性本身**仍降级为 `FIELDSTORE` | `FIELD_INITIALIZER` | `translator.mjs:205-257`（ArkTS） | §IR-E |
| ArkTS 组件 `Column(){…}` | `EtsComponentExpression`（ArkTS `ts.isEtsComponentExpression`） | 现状即 `CALL` + 体内降级（`AK/translator.mjs:1160-1179`），保留 | `METHOD` | `translator.mjs:1160-1179`（ArkTS） | 现有 IR 即可 |
| **类继承 `extends`**（TS/ArkTS 都有） | `HeritageClause`（`token` = `ExtendsKeyword`；TS `isHeritageClause` / ArkTS `ts.isHeritageClause`） | 基类字段初始化器与基类构造器**先于**派生类跑：派生类 `<init>` 的第一条是 `CALL <基类>.<init>/n`（`args[0]`=`this`）；基类字段初始化器落在**基类 `<init>` 体内**（不是独立初始化器函数）；`super(…)` ⇒ `CALL <基类>.<init>/n`（`args[0]`=`this`）；父类名进 `TacTypeDecl.superType`；**原型链方法查找**：实例上的方法解析按 `superType` 链**由近到远**找第一个定义 ⇒ 引擎按同一条链找 `owner.method/arity` | `CONSTRUCTOR` | `translator.mjs:470-481`（TS）/ `translator.mjs:370-397`（ArkTS） | §IR-E（`superType`，`01` §IR-E 的 `TacTypeDecl`） |
| ArkTS `@Component` / `@Entry` | `ClassDeclaration` + `ModifierLike`（ArkTS `ts.isModifier`） | 装饰器名进 `classAnnotations`（函数级）与 `TacTypeDecl.annotations`（类型级）；组件本体照常降级为「初始器 + 每方法一个函数」；**页面由框架代为实例化** ⇒ §12 | `METHOD` | `translator.mjs:205-257`（ArkTS） | §IR-E |
| ArkTS `@Builder` 方法 / `@BuilderParam` 形参 | `MethodDeclaration` / `ParameterDeclaration` + `ModifierLike` | 方法照常降级；装饰器名进 `methodAnnotations`；`@BuilderParam` 形参名进 `paramAnnotations`；装饰器实参（如 `@Watch('onWatch')`）进 `annotationValues`；**绑定由框架在实例化时完成** ⇒ §12 | `METHOD` | `translator.mjs:358-365`（ArkTS） | §IR-E |
| **`implements`**（接口实现关系） | `HeritageClause`（`token` = `ImplementsKeyword`）+ `ExpressionWithTypeArguments` | 运行期零行为（接口是纯类型）⇒ **零指令**；被实现的接口名列表进 `TacTypeDecl.interfaces` ⇒ 「按接口命名的规则」据此判定「这个类满足该接口」；`implements` 的**接口成员**不复制进类（JS 里接口没有运行期实体） | — | `translator.mjs:470-481`（TS）/ `translator.mjs:369-397`（ArkTS） | §IR-E（`interfaces`） |
| **`accessor` 字段** `accessor x = 1` | `AccessorDeclaration`（TS `isAccessorDeclaration`；ArkTS 无此构念） | 自动访问器 = 私有后备字段 + getter + setter ⇒ 合成 `GETTER` 函数（`CALL <Owner>.x/0`，args[0]=obj）与 `SETTER` 函数（`CALL <Owner>.x/1`，args[0]=obj, args[1]=v）；后备字段 `FIELDSTORE this._x = <init>`；访问点 `obj.x` 走 getter 的 `CALL`（见 §5.2 的「getter 的调用 `obj.x`（x 是 getter）」行） | `GETTER` / `SETTER` | `translator.mjs:473-479`（TS） | §IR-E |
| **构造器参数属性** `constructor(private x: T)` | `ParameterDeclaration` + `ModifierFlags.ParameterPropertyModifier`（掩码 `= 31`） | 形参照常进签名（`mod.C.<init>/1`）；构造器体里补一条 `FIELDSTORE this.x = <param>` ⇒ 参数属性既是形参又是字段 | `CONSTRUCTOR` | `translator.mjs:473-479`（TS） | §IR-E |
| **接口 call signature** `interface I { (x: T): U }` | `CallSignatureDeclaration`（TS `isCallSignatureDeclaration`） | 无体 ⇒ 不生成函数；进 `TacTypeDecl.members`（调用签名，名字取接口名 + `call`） | — | `translator.mjs:608`（TS） | §IR-E |
| **接口 construct signature** `new (x: T): U` | `ConstructSignatureDeclaration`（TS `isConstructSignatureDeclaration`） | 无体 ⇒ 不生成函数；进 `TacTypeDecl.members`（构造签名，名字取接口名 + `<init>`） | — | `translator.mjs:608`（TS） | §IR-E |
| **接口方法签名**（无体） | `MethodSignatureDeclaration`（TS `isMethodSignatureDeclaration`） | 无体 ⇒ 不生成函数；进 `TacTypeDecl.members` ⇒ 引擎能把「按接口声明的方法」与「按类实现的方法」对上 | — | `translator.mjs:608`（TS） | §IR-E |
| **剩余形参** `f(...xs)` | `ParameterDeclaration` + `DotDotDotToken`（TS `isParameterDeclaration` / ArkTS `ts.isParameterDeclaration`） | 形参类型 = `ARRAY`（元素类型 = 实参类型）；被调方内部 `xs` 是**数组**；调用点把尾部实参打包成数组 ⇒ 调用侧走 **IR-W** 的 `spreadArgs`，被调侧靠形参元数据（**IR-W 的字段扩展**：`restParamIndex`） | — | `translator.mjs:536-543`（TS）/ `translator.mjs:565-575`（ArkTS） | **IR-W 的字段扩展** |
| **可选形参** `f(x?: T)` | `ParameterDeclaration` + `QuestionToken` | 形参类型 = `T` 且 `nullable=true`；**可缺省**语义：callee 键的 arity 记**声明的形参数**（可选形参占位），实参少传时由被调方补 `undefined` ⇒ `NULL`（**IR-X** 采纳后为 `UNDEFINED`） | — | `translator.mjs:536-543`（TS）/ `translator.mjs:565-575`（ArkTS） | 现有 IR + **IR-X** |
| **默认参数值** `f(x = 1)` | `ParameterDeclaration` + `initializer` | 形参绑定之后、函数体第一行之前补 `MOVE x = <default>`（默认值里的 source / sink 调用**照常降级**，不能消失） | — | `translator.mjs:536-543`（TS）/ `translator.mjs:565-575`（ArkTS） | 现有 IR 即可 |
| **`this` 形参** `function f(this: C, x)` | `ParameterDeclaration` + `ThisKeyword` | `this` 形参是**类型标注**，运行期不占参数位 ⇒ 不进签名、不进 `args`；`this` 的类型进 `TacTypeDecl` | — | `translator.mjs:536-543`（TS） | §IR-E |
| **模式里的默认值** `const {a = 1} = obj` | `BindingElement` + `initializer`（TS `isBindingElement` / ArkTS `ts.isBindingElement`） | 取到的值为 `undefined` 时取默认值：`t_a = ARRAYLOAD obj["a"]; BRANCH t_a -> L_HAVE, L_DEF` + `L_DEF: t_a = <default>`（`BRANCH` 带 `guard = Compare(t_a, NE, NULL)`） | — | `translator.mjs:614`（TS）/ `translator.mjs:688-700`（ArkTS） | 现有 IR + §IR-C |
| **嵌套模式** `const {a:{b}} = obj` | `ObjectBindingPattern` 嵌套 `ObjectBindingPattern`（TS `isObjectBindingPattern`） | **递归**展开：`t_a = ARRAYLOAD obj["a"]`；`t_b = ARRAYLOAD t_a["b"]; MOVE b = t_b` ⇒ 嵌套多少层就展开多少层 | — | `translator.mjs:614`（TS）/ `translator.mjs:688-700`（ArkTS） | 现有 IR 即可 |
| **赋值式解构** `({j} = o)` | `DestructuringAssignment`（TS `isDestructuringAssignment`） | **不产生新变量** ⇒ 对已存在的变量赋值：`t_j = ARRAYLOAD o["j"]; MOVE j = t_j`；左边是标识符时等价于一次普通赋值 | — | `translator.mjs:1102`（TS） | 现有 IR 即可 |
| **数组剩余元素** `const [a, ...rest] = arr` | `ArrayBindingPattern` + `BindingElement`（`DotDotDotToken`） | `t_rest = NEWARRAY`；从被取走的下标起逐个 `ARRAYSTORE t_rest, t_i, t_v`（下标与元素类型都来自模式） | — | `translator.mjs:614`（TS） | 现有 IR 即可 |
| **`declare global`** | `ModuleDeclaration` + `ModifierFlags.Ambient`（TS `isModuleDeclaration`） | 运行期无实体 ⇒ 零指令；增强的名字进 `TacTypeDecl`（全局作用域的声明） | — | `translator.mjs:608`（TS） | §IR-E |
| **`readonly` 属性** | `PropertyDeclaration` + `ModifierFlags.Readonly`（TS）/ `ReadonlyKeyword`（ArkTS） | 运行期**不做检查**（TS 的 `readonly` 只在编译期约束）⇒ TAC 里仍发同样的 `FIELDSTORE`；**只读事实**进 `TacTypeDecl.fields`（**IR-E 的字段扩展**：字段增 `readonly` 标记）⇒ 引擎可据此判定「不该出现的写」 | `FIELD_INITIALIZER` | `translator.mjs:1490-1505`（TS）/ `translator.mjs:369-397`（ArkTS） | §IR-E 的字段扩展 |
| **可见性修饰符** `public`/`private`/`protected` | `ModifierFlags.AccessibilityModifier`（掩码 `= 7`） | 运行期**无行为**（TS 的 `private` 不是运行期强制）⇒ TAC 里仍发同样的 `FIELDSTORE`/`FIELDLOAD`；可见性进 `fieldAnnotations`（取值 `public`/`private`/`protected`）⇒ 引擎可判定「跨类写私有字段」 | `FIELD_INITIALIZER` | `translator.mjs:473-479`（TS）/ `translator.mjs:369-397`（ArkTS） | §IR-E（`fieldAnnotations` 通道，取值扩展不占新编号） |
| **`override` 属性** | `ModifierFlags.Override`（`= 16`） | 运行期零行为（`override` 是编译期检查）⇒ 零指令；标记进 `fieldAnnotations` 的 `override` 取值 ⇒ 引擎可判定「覆盖了基类成员」 | `FIELD_INITIALIZER` | `translator.mjs:473-479`（TS）/ `translator.mjs:387-395`（ArkTS） | §IR-E（`fieldAnnotations` 取值扩展） |
| **`#private` 字段** | `PrivateIdentifier`（TS `isPrivateIdentifier`） | 名字**保留 `#` 前缀** ⇒ `FIELDSTORE this.#x = <init>` 的字段名是 `#x` ⇒ 引擎按名字匹配时 `#x` 与 `x` 是**两个不同的字段**（私有性天然可见） | `FIELD_INITIALIZER` | `translator.mjs:473-479`（TS）/ `translator.mjs:1878-1880`（ArkTS） | 现有 IR 即可 |
| **字符串枚举** `enum E { A = "a" }` | `EnumDeclaration` + `EnumMember`（TS `isEnumMember`） | 同数值枚举的形状，但成员值是字符串常量：`ARRAYSTORE t_e["A"] = CONST STRING "a"`；**不**生成反向映射（字符串枚举没有反向映射） | `FIELD_INITIALIZER` | `translator.mjs:608`（TS）/ `translator.mjs:432-462`（ArkTS） | §IR-E |
| **混合枚举**（数值 + 字符串成员） | `EnumDeclaration` + `EnumMember[]` | 每个成员各自的值类型（数字或字符串）；反向映射**只对数值成员**生成 | `FIELD_INITIALIZER` | `translator.mjs:608`（TS） | §IR-E |
| **`const enum`** | `EnumDeclaration` + `ModifierFlags.Const`（`= 4096`） | 编译期**完全内联** ⇒ 运行期**没有枚举对象** ⇒ 零指令；成员值只进类型元数据（`TacTypeDecl` 的枚举成员表）；引用点 `E.A` 在编译期已被替换成常量 ⇒ 运行期就是那个常量 | — | `translator.mjs:608`（TS） | §IR-E |
| **枚举计算成员** `A = 1+1` | `EnumMember` + `initializer` | 成员值 = 初始化表达式的**编译期常量求值**结果；求不出常量 ⇒ 该成员的值建模为未知值 + `VALUE` 级 drop | `FIELD_INITIALIZER` | `translator.mjs:608`（TS） | §IR-E + §IR-B |
| **模块级语句** | `SourceFile` 的 `statements`（TS `isSourceFile`） | 模块级语句**不并进任何函数** ⇒ 拆成**模块级合成函数** `<module>.__top/0`（按源码顺序装全部模块级语句）；模块里的每个函数仍各自独立（TS 侧 `TS/translator.mjs:280-295`、ArkTS 侧 `AK/translator.mjs:205-257`） | `METHOD` | `translator.mjs:280-295`（TS）/ `translator.mjs:205-257`（ArkTS） | 现有 IR 即可 |
| **变量语句的逐个声明项** | `VariableStatement` → `VariableDeclarationList` → `VariableDeclaration[]`（TS `isVariableStatement` / `isVariableDeclarationList` / `isVariableDeclaration`） | **逐个** `declaration` 处理：初始化器是函数的提升为独立函数（见上面「嵌套函数」行），其余的照常 `MOVE` 声明 ⇒ 一条语句里既有函数又有变量时**两类都不丢** | `METHOD` | `translator.mjs:280-286`（TS） | 现有 IR 即可 |
| **ArkTS 状态管理装饰器全集** | `ModifierLike` 上的装饰器（ArkTS `ts.isModifier` + `node.modifiers`） | 装饰器**名**进 `fieldAnnotations`（`State`/`Local`/`Prop`/`Param`/`Link`/`Provide`/`Consume`/`Require`/`Once`/`Watch`/`Observed`/`ObjectLink`/`Trace`/`Event`/`Monitor`/`Reusable`/`MonoStorage`/`Type`）；**属性本身**照常降级为 `FIELDSTORE`；`@Provide`/`@Consume` 的**组件间链接边**与 `@Observed`/`@ObjectLink` 的**观察边**由框架在实例化时建立 ⇒ §12 例外 | `FIELD_INITIALIZER` | `translator.mjs:205-257`（ArkTS） | §IR-E |
| **ArkTS `IDataSource` 实现** | `ClassDeclaration` + `HeritageClause`（ArkTS `ts.isClassDeclaration` / `ts.isHeritageClause`） | 普通类降级（初始器 + 每方法一个函数）；`totalCount()` / `getData(i)` 是**普通方法** ⇒ `CALL <Owner>.getData/1 args=[this, i]`；实现关系进 `TacTypeDecl.interfaces` | `METHOD` | `translator.mjs:369-397, 1109-1145`（ArkTS） | §IR-E |
| **ArkTS 运行时上下文对象** `abilityContext` | `Identifier`（全局绑定，ArkTS `ts.isIdentifier`） | 零指令（它是一个全局对象）；它的**属性值**由框架在运行期注入 ⇒ 建模为未知值 + `VALUE` 级 drop（§12） | — | `translator.mjs:1633-1647`（ArkTS） | §IR-E + §IR-B |
| **ArkTS `@Concurrent` / `@Sendable` 函数** | `ModifierLike`（ArkTS `ts.isModifier`） | 方法照常降级；装饰器名进 `methodAnnotations`；**并发标记**进指令的 `effect` 集合（`CONCURRENT`）⇒ 引擎知道这条调用不在本线程的顺序流里 | `METHOD` | `translator.mjs:205-257`（ArkTS） | §IR-C 的 `Effect`（`IR/TacInstruction.java:172` 的 `Effect` 枚举已有 `DEFERRED`/`CONCURRENT`） |

**必须补上的 `else`**：`TS/translator.mjs:470-481` 的 `lowerClass` 只有方法分支、**无 `else`** ⇒ 属性声明、构造器、getter/setter、索引签名、静态块**全部消失**而函数仍报 `SOURCE_FULL`。ArkTS 同类问题在 `AK/translator.mjs:341-367`（struct）与 `:370-397`（class）：访问器与静态块被丢。⇒ 两个 `lowerClass` 改成**穷尽分派**：兜底不是发 `HAVOC`，而是按上表默认形状降级（字段 ⇒ `FIELDSTORE`，未知成员 ⇒ 进 `TacTypeDecl`）。

### 4.1 装饰器的运行期应用（IR-AV）

**关键事实**：装饰器**不是**「编译期标注」，也**不是**「外部世界调用」——
它**运行期就是一个被调用的函数**（TS 规范如此；ArkTS 侧要看是不是编译器内建，见本节末尾的判据）。
因此装饰器可以像普通函数调用一样降级，**不需要新的指令种类**。

**两种语义的差别**（TS 的 legacy decorators 与 TC39 stage-3 标准装饰器）：

| | legacy decorators | 标准装饰器（stage-3） |
|---|---|---|
| 类装饰器 `@dec class C` | 运行期 `dec(C)`，**返回值替换** `C` | 运行期 `dec(C)`，**返回值不替换** |
| 成员装饰器 `@dec m()` | 运行期 `dec(C.prototype, "m", desc)`，返回值替换描述符 | 运行期 `dec(C.prototype, "m", ctx)`，返回值替换描述符 |
| 执行顺序 | 类装饰器**先**、成员装饰器**后**（各自自上而下） | **成员阶段先**（自上而下）、**类阶段后** |

**降级形状**（类装饰器，legacy）：

```
声明点： t_d = CALL <dec>/1 args=[C]      // replacesTarget=true ⇒ 之后 C 的引用指向 t_d
```

**降级形状**（成员装饰器）：

```
声明点： t_d = CALL <dec>/2 args=[C.prototype, "m"]
```

**IR-AV 的数据形状**（完整版在本文 §8）：

- 新增元数据类 `TacDecorator { String calleeSig; TacDecoratorPhase phase; boolean replacesTarget; }`
- `TacDecoratorPhase`：`MEMBER`（成员装饰器）/ `CLASS`（类装饰器）
- 载体：`TacTypeDecl.decorators`（类装饰器）/ `TacMemberRef.decorators`（成员装饰器）
- **零新指令**：运行期部分**复用现有 `CALL`**；IR-AV 只承载「装饰器链 + 阶段 + 返回值是否替换」

**生产方**：`lowerClass` / `lowerFunction` 读 `node.modifiers` 里的 `Decorator` 节点 ⇒
装饰器**表达式本身**照常降级（它可以带参数求值，副作用要保留），
装饰器**标识符**给出 `calleeSig`，参数列表给出实参。

**消费方**（引擎侧，这条是 IR-AV 存在的理由）：

1. **调用图**：装饰器调用是一条**真实边** ⇒ 装饰器实参的污点能流进被装饰的类 / 成员
   （今天这条流**完全不可见** —— 装饰器被当成纯标注丢掉了）。
2. **形状**：legacy 类装饰器可以**换掉** `C` ⇒ 引擎知道 `C` 的字段布局**不是**源码写的那一份
   ⇒ 字段级规则对被装饰类**不出结论**，而不是给出错误结论。
3. **顺序**：标准装饰器里「成员阶段先于类阶段」是**确定的** ⇒ 引擎按 `phase` 排序即可。

**与 ArkTS 框架装饰器的分界（判据是可机械执行的）**：

ArkTS 的**编译器内建**装饰器**不是**普通装饰器 —— 它们由编译器或框架处理，**没有对应的运行期函数**：

| 类别 | 具体 | 判据 | 落点 |
|---|---|---|---|
| 普通装饰器 | 用户自己写的 `@MyDecorator` | **不命中**内建清单 | **§4.1**（发 `CALL`） |
| ArkTS 状态装饰器 | `@State`/`@Prop`/`@Local`/`@Link`/`@Param`/`@Provide`/`@Consume`/`@Require`/`@Once`/`@Watch`/`@Observed`/`@ObjectLink`/`@Trace`/`@Event`/`@Monitor`/`@Reusable`/`@MonoStorage`/`@Type` | **命中** `AK/compiler.mjs:53-65` 的内建清单 | §12 的 `TS3`（永久例外） |
| ArkTS 编译期宏 | `@Builder`/`@BuilderParam` | 同上 | §12 的 `TS5` |
| ArkTS 框架标记 | `@Component`/`@Entry` | 同上 | §12 的 `TS6` |
| ArkTS 并发标记 | `@Concurrent`/`@Sendable` | 同上 | §12 的 `TS27` |

⇒ **判定规则**：装饰器标识符能解析到一个**用户可见的函数** ⇒ 走 §4.1；
命中 ArkTS 内建清单 ⇒ 走 §12。
⇒ 这条规则让「装饰器」这一类**不再整类是例外**：TS 侧全部走 §4.1；
ArkTS 侧只有命中内建清单的那些走 §12。

---

## 5. 表达式面方案

### 5.1 算子表重构

现状两张表键**类型不同**：二元/复合表按 `SyntaxKind` **数字**（`TS/translator.mjs:90-114,175-197`），一元表按算子**拼写字符串**（`:205-209`）。任一 `SyntaxKind` 常量在该编译器构建里不存在时，`[SyntaxKind.X]` 变成键 `"undefined"`，与其它缺失 token 静默相撞。⇒ 改为按**语法种类名**索引（`"PlusToken"` 等稳定字符串），装载时用编译器 `SyntaxKind` 建数值→名映射并**断言唯一**：缺一个即启动报错，而不是降级成 `HAVOC`。

### 5.2 表达式面总表

**列的读法**：「AST 节点」列给**解析器里的节点名**（TS 侧是 `typescript/unstable/ast` 的 `isXxx` 谓词，ArkTS 侧是 `ohos-typescript` 的 `ts.isXxx`）；「现状」列是**审计台账的证据串**（相对 sidecar 的 `src/` 目录，两面各给一条），与「完整 TAC 表达」列里的 `TS/`、`AK/` 前缀（本文档的路径缩写，见文首）是同一批行号的两种写法。

| 源构念 | AST 节点（TS / ArkTS sidecar） | 完整 TAC 表达 | 类型 | 现状（审计证据） | 需要的 IR 能力 |
|---|---|---|---|---|---|
| 算术/比较/位运算 | `BinaryExpression`（TS `isBinaryExpression` / ArkTS `ts.isBinaryExpression`） | `BINOP`（`ADD SUB MUL DIV MOD AND OR XOR SHL SHR USHR EQ NE LT LE GT GE`） | 按操作数 | `translator.mjs:90-114`（TS）/ `translator.mjs:51-57`（ArkTS） | 现有 IR 即可（`IR/BinaryOperator.java:32-50`） |
| `**` | 同上（运算符 token `AsteriskAsteriskToken`） | 不再近似成 `MUL`；需真幂运算 | `DOUBLE` | `translator.mjs:154-157`（TS）/ `translator.mjs:90-93`（ArkTS） | **IR-R** |
| `&&`/`\|\|`/`??` | `BinaryExpression` | 短路展开成 `BRANCH` 链（§6.2） | 操作数类型（JS 的 `&&` 返回操作数而非 BOOLEAN） | `translator.mjs:158-161`（TS）/ `translator.mjs:94-97`（ArkTS） | 现有 IR + §IR-C |
| `instanceof` | `BinaryExpression` | `INSTANCEOF`（`IR/TacInstruction.java:58`）。**TS 侧今天没有**（`TS/translator.mjs:90-114` 无该条目），ArkTS 侧已有（`AK/translator.mjs:1247-1258`）⇒ 对齐 | `BOOLEAN` | `translator.mjs:1008-1011`（TS）/ `translator.mjs:1247-1258`（ArkTS） | 现有 IR 即可 |
| `in` | `BinaryExpression` | `BRANCH` + 新守卫 `PropertyPresence(receiver,key)`，真/假两支各 `MOVE` 一个 `BOOL` | `BOOLEAN` | `translator.mjs:1008-1011`（TS）/ `translator.mjs:1266-1269`（ArkTS） | **IR-S** |
| 可选链 `a?.b` | `PropertyAccessExpression` + `questionDotToken`（TS `isPropertyAccessExpression` / ArkTS `ts.isPropertyAccessExpression`） | `BRANCH`（`Compare(t_a,NE,NULL)`）+ 读或短路；链上每环各自分支，短路后跳到链尾不再求值（§6.3）；链上的**可选调用**与**可选下标**见下面两行 | 见 §6.3 | `translator.mjs:855-867`（TS）/ `translator.mjs:1014-1019, 1087-1099`（ArkTS） | 现有 IR + §IR-C；`undefined` 值见 **IR-X** |
| 三元 `c?a:b` | `ConditionalExpression`（TS `isConditionalExpression`） | `BRANCH` + 两条 `MOVE` 支 | 按分支 | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | 现有 IR + §IR-C |
| `as`/`satisfies`/`<T>` | `AsExpression` / `SatisfiesExpression` / `TypeAssertion`（TS `isAsExpression` / `isSatisfiesExpression` / `isTypeAssertion`） | 运行期零行为 ⇒ 不发指令；语义是类型收窄 ⇒ 后续分支用 `TypeTest` 表达 | 类型改写 | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | §IR-C `TypeTest` + **IR-Z** |
| `typeof x` | `TypeOfExpression`（TS `isTypeOfExpression` / ArkTS `ts.isTypeOfExpression`） | 操作数类型精确 ⇒ 直接发常量字符串（8 种之一）；`any`/`unknown`/类型参数 ⇒ 结果是**运行期才定的 8 个字符串之一** ⇒ 建模为**未知值** + `VALUE` 级 drop（**本体已完整降级**：能精确的那一支完全精确；不能精确的那一支只丢「是哪一种字符串」这一个事实，值本身仍是「一个字符串」） | `STRING` | `translator.mjs:791-793`（TS）/ `translator.mjs:1008-1010`（ArkTS） | 现有 IR 即可（精确分支）+ §IR-B |
| `await p` | `AwaitExpression`（TS `isAwaitExpression` / ArkTS `ts.isAwaitExpression`） | **值域完整**：`t_a = MOVE p`（解包值与 promise 同源）；**微任务时序**不表达 ⇒ 记 `SEMANTICS` 级 drop（**本体已完整降级**：解包后的值与污点完全正确，缺的只是「这个 `await` 之后的代码什么时候跑」） | 解包后类型 | `translator.mjs:788-790`（TS）/ `translator.mjs:1005-1007`（ArkTS） | 现有 IR 即可 + §IR-B |
| 展开 | `SpreadElement`（数组字面量 / 调用参数）/ `SpreadAssignment`（对象字面量）；TS `isSpreadElement` / `isSpreadAssignment` | 数组字面量内展开 ⇒ `NEWARRAY` + 逐元素 `ARRAYSTORE`（个数已知时）；**对象字面量展开 `{...o}`** ⇒ `NEW CLASS<Object>` + 逐键 `ARRAYSTORE t["<key>"] = <value>`（键与值都来自运行期 ⇒ 键是运行期值）；**调用参数展开**的参数个数运行期才定 ⇒ **IR-W** | 依表达式 | `translator.mjs:843-844`（TS）/ `translator.mjs:1463-1487`（ArkTS） | IR-W（调用场景） |
| 模板字符串 | `TemplateExpression` / `NoSubstitutionTemplateLiteral`（TS `isTemplateExpression` / `isNoSubstitutionTemplateLiteral`） | 现状即 `ADD` 链（`TS/translator.mjs:1232-1258`） | `STRING` | `translator.mjs:1232-1258`（TS）/ `translator.mjs:1496-1525`（ArkTS） | 现有 IR 即可 |
| `delete obj.k` | `DeleteExpression`（TS `isDeleteExpression`） | 删除键 ⇒ **IR-V** | `BOOLEAN` | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | IR-V |
| `yield v` | `YieldExpression`（TS `isYieldExpression` / ArkTS `ts.isYieldExpression`） | 生成器 ⇒ 降级成**状态机**：状态存生成器对象字段；`yield v` ⇒ `MOVE gen.yielded = v` + `JUMP` 挂起标签；恢复入口即函数入口（状态派发在入口）；**委托 yield** 见下面「`yield*`」行；**驱动方语义**（谁在何时调 `next()`）不表达 ⇒ 记 `SEMANTICS` 级 drop（**⚠ R21 实测：那句「本体已完整降级」是错的** —— 改之前 TS 侧车的 `yield` **没有任何分支** ⇒ ⇒ **操作数从未被访问** ⇒ ⇒ **子树整条丢失**（零回执 + 两条 `UNHANDLED_NODE`）⇒ ⇒ **R21 已修**（`14` §3.44）：**操作数就地求值** ⇒ ⇒ 子树进 TAC；协议与丢值各一条回执（`expr.generator_yield` / `SEMANTICS`，`expr.generator_yield.value` / `VALUE`）⇒ ⇒ 精度由 `SOURCE_LOWERED` 回到 `SOURCE_DEGRADED` ⇒ ⇒ **而状态机那一步仍未做**（它要 `IR-L`）） | 生成器类型 | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | 现有 IR + §IR-E + §IR-B |**⚠ R21：TS 侧部分落地**（操作数已降级，协议未建模）；**ArkTS 侧未做** |
| 类表达式 `const C = class {…}` | `ClassExpression`（TS `isClassExpression` / ArkTS `ts.isClassExpression`） | 类即构造器 ⇒ `t_c = NEW CLASS<C>`；成员按 §4 降级为独立函数 | `CLASS<C>` | `translator.mjs:833-836`（TS）/ `translator.mjs:1050-1053`（ArkTS） | 现有 IR 即可 |
| 箭头/函数表达式（闭包） | `ArrowFunction` / `FunctionExpression`（TS `isArrowFunction` / `isFunctionExpression`） | **闭包对象**方案：定义点 `t_cl = NEW CLASS<Closure>`，每个捕获变量 `FIELDSTORE t_cl.<cap> = <captured>`；被调函数以闭包对象为 receiver（`args[0]`），体内读捕获变量 = `FIELDLOAD receiver.<cap>`；**`async` 标记**见下面「`async` 函数 / `async` 箭头」行 | 函数类型 | `translator.mjs:837-842`（TS）/ `translator.mjs:1054-1059`（ArkTS） | 现有 IR + §IR-E |
| 对象字面量 | `ObjectLiteralExpression`（TS `isObjectLiteralExpression` / ArkTS `ts.isObjectLiteralExpression`） | `NEW CLASS<Object>` + **逐成员形态**（`TS/translator.mjs:1176-1198`）：**普通属性** `{a: v}` ⇒ `ARRAYSTORE t["a"] = v`；**简写** `{v}` ⇒ `ARRAYSTORE t["v"] = v`；**方法** `{m(){}}` ⇒ 降级为独立函数 + `FIELDSTORE t["m"] = <闭包对象>`（调用走 §4 的方法形状）；**访问器** `{get x(){}}` ⇒ 独立 getter 函数 + `CALL <Owner>.x/0`；**计算键** `{[k]: v}` ⇒ 键取**表达式的值**（不是源码文本）⇒ `ARRAYSTORE t[<k 值>] = v` | `CLASS<Object>` | `translator.mjs:1176-1198`（TS）/ `translator.mjs:1445-1458`（ArkTS） | 现有 IR + §IR-E |
| 数组字面量 | `ArrayLiteralExpression`（TS `isArrayLiteralExpression`） | 现状即 `NEWARRAY` + 逐元素 `ARRAYSTORE`（`:1201-1223`） | `ARRAY` | `translator.mjs:1201-1223`（TS）/ `translator.mjs:1463-1487`（ArkTS） | 现有 IR 即可 |
| 属性读 `obj.x`（对象） | `PropertyAccessExpression` | 现状即 `ARRAYLOAD obj["x"]`（`:855-867`） | 字段类型 | `translator.mjs:855-867`（TS）/ `translator.mjs:1087-1099`（ArkTS） | 现有 IR 即可 |
| 属性读 `obj.x`（**类实例**） | `PropertyAccessExpression` | `FIELDLOAD obj.x` + `ownerType` = 类名 | 字段类型 | `translator.mjs:855-867`（TS）/ `translator.mjs:1087-1099`（ArkTS） | §IR-E |
| getter 的调用 `obj.x`（x 是 getter） | `PropertyAccessExpression`（目标是访问器） | `CALL <Owner>.x/0`（args[0]=obj），不是 `FIELDLOAD` ⇒ 引擎能区分「读字段」与「调用访问器」 | — | `translator.mjs:473-479`（TS）/ `translator.mjs:387-395`（ArkTS） | §IR-E |
| **松散相等 `a == b` / `a != b`** | `BinaryExpression`（运算符 token 是 `EqualsEqualsToken` 还是 `EqualsEqualsEqualsToken`） | `BINOP` 的 binop 用 **`LOOSE_EQ` / `LOOSE_NE`**（**IR-AT**：枚举末尾追加）；`===` / `!==` 仍是 `EQ` / `NE`；分支守卫同形（`Compare(lhs, LOOSE_EQ, rhs)`） | `BOOLEAN` | `translator.mjs:97-100`（TS）/ `translator.mjs:58-61`（ArkTS） | **IR-AT** + IR-Q |
| **标签模板**（`tag` + 模板字面量） | `TaggedTemplateExpression`（TS `isTaggedTemplateExpression`） | `t_s = NEWARRAY` + 逐片段 `ARRAYSTORE`（静态字符串数组）；`t_r = CALL <tag>/<1+洞数>`，`args[0]`=接收者、`args[1]`=字符串数组、其后逐个是插值表达式 | tag 的返回值 | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | 现有 IR 即可 |
| **动态 `import(spec)`** | `ImportExpression`（TS `isImportExpression`） | `t_spec = <spec>`；`t_ns = CALL <owner>.import/1`，`args[0]`=接收者槽、`args[1]`=说明符；`spec` 是**字面量** ⇒ owner = 模块路径（目录可静态匹配）；否则 owner **缺失**（IR-P 的无 owner 形状）⇒ 匹配不上但不误匹配 | `UNKNOWN`（Promise） | `translator.mjs:892-931, 1447-1460`（TS）/ `translator.mjs:1728-1743`（ArkTS） | IR-P + 现有 IR 即可 |
| **`import.meta`** | `MetaProperty`（TS `isMetaProperty`） | 每模块合成一个函数 `<module>.__meta/0`：`t_m = NEW CLASS<Object>` + 逐属性 `ARRAYSTORE`；使用处 `t_m = CALL <module>.__meta/0`；属性**值**运行期才定 ⇒ §12 | `CLASS<Object>` | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | 现有 IR 即可 |
| **调用的 owner**（`require()` / `document.*` / 字符串方法） | `CallExpression`（TS `isCallExpression`） | owner 解析**五级链**（§5.3.8）：命名空间导入 → 接收者的类名 → 基本类型的包装类 → 已知全局内建 → **结构化 owner 缺失**（IR-P：无 owner 段 + `ownerType=null`）；**禁止**回落到模块名或 `UNKNOWN_OWNER` 哨兵 | — | `translator.mjs:419-437, 892-931`（TS）/ `translator.mjs:1675-1703`（ArkTS） | IR-P + §IR-E |
| **一元算子 `!` / `~` / 一元 `-`** | `PrefixUnaryExpression`（TS `isPrefixUnaryExpression` / ArkTS `ts.isPrefixUnaryExpression`） | `UNARYOP` 的算子取 `UnaryOperator`（`IR/UnaryOperator.java:31-38`）：`!` ⇒ `NOT`（`UNARYOP NOT(t)`，BOOLEAN）；`~` ⇒ `BITNOT`（`UNARYOP BITNOT(t)`，整型）；一元 `-` ⇒ `NEG`（`UNARYOP NEG(t)`，DOUBLE / 整型）；**表键按语法种类名索引**（§5.1），不按运算符拼写 | 按操作数 | `translator.mjs:205-209, 1121-1129`（TS）/ `translator.mjs:141-145, 1381`（ArkTS） | 现有 IR 即可 |
| **前缀 `++` / `--`** | `PrefixUnaryExpression`（运算符 token `PlusPlusToken` / `MinusMinusToken`） | `UNARYOP t1 = i PRE_INC ; MOVE i = t1`（**先改后取值**）；后缀形态见 §6.3 的「**`for` 的初始化器与步进**」段 | 按操作数 | `translator.mjs:1106, 1121-1129`（TS） | 现有 IR 即可 |
| **复合赋值 `&&=` / `\|\|=` / `??=`** | `BinaryExpression`（运算符 token `AmpersandAmpersandEqualsToken` / `BarBarEqualsToken` / `QuestionQuestionEqualsToken`） | 展开成「读 → 短路判定 → 条件写」：`t_a = <lhs>; BRANCH t_a -> L_SKIP, L_RHS`（`&&=` 极性；`\|\|=` 反过来；`??=` 用 `Compare(t_a, NE, NULL)`）；`L_RHS: t_b = <rhs>; MOVE lhs = t_b`；`L_SKIP:`（右操作数**只在需要时**求值） | 按左操作数 | `translator.mjs:191-196`（TS）/ `translator.mjs:127-132`（ArkTS） | 现有 IR + §IR-C |
| **`void` 运算符** | `VoidExpression`（TS `isVoidExpression`） | 求值操作数并**丢弃结果**（副作用保留）：`t_v = <operand>`；`MOVE t_res = CONST NULL`（`void` 的值恒为 `undefined` ⇒ **IR-X** 采纳后为 `UNDEFINED`） | `VOID` | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | 现有 IR 即可 |
| **逗号运算符** | `BinaryExpression`（运算符 token 为 `CommaToken`；本编译器的 `typescript/unstable/ast` **没有** `isCommaListExpression` ⇒ 按 `isBinaryExpression` + `CommaToken` 判定） | 依次求值、**只保留最后一个**：`t_a = <e1>; t_b = <e2>; MOVE t_res = t_b`（求值顺序严格保真，前面的值被丢弃但副作用保留） | 最后一个操作数的类型 | `translator.mjs:1008-1011`（TS）/ `translator.mjs:1060`（ArkTS） | 现有 IR 即可 |
| **可选调用 `a?.()`** | `CallExpression` + `questionDotToken`（TS `isCallExpression`） | `t_a = <被调表达式>; BRANCH t_a -> L_CALL, L_NULL`（`guard = Compare(t_a, NE, NULL)`）；`L_CALL: t_r = CALL <owner>.<m>/n args=[receiver, …]`；`L_NULL: t_r = <undefined>`；短路时**不发出** `CALL` | 被调者的返回类型 | `translator.mjs:892-931`（TS）/ `translator.mjs:1109-1145`（ArkTS） | 现有 IR + §IR-C；`undefined` 值见 **IR-X** |
| **可选下标 `a?.[k]`** | `ElementAccessExpression` + `questionDotToken`（TS `isElementAccessExpression`） | `t_a = <obj>; BRANCH t_a -> L_IDX, L_NULL`；`L_IDX: t_k = <key>; t_v = ARRAYLOAD t_a[t_k]`；`L_NULL: t_v = <undefined>` | 元素类型 | `translator.mjs:870-882`（TS）/ `translator.mjs:1087-1099`（ArkTS） | 现有 IR + §IR-C；`undefined` 值见 **IR-X** |
| **静态 `import` / `export` 语句** | `ImportDeclaration` / `ExportDeclaration` / `ExportAssignment`（TS `isImportDeclaration` / `isExportDeclaration` / `isExportAssignment`） | 静态 `import` **不产生运行期调用**（绑定在编译期完成）⇒ 零指令；导入的符号进模块导出表（调用图按导出名建边）；`export` 同理（零指令）；**匿名默认导出** `export default function () {}` ⇒ 合成一个匿名函数（名字 `default`）；**再导出** `export {x} from "m"` / `export * from "m"` / `export * as ns from "m"` ⇒ 零指令 + **别名边**（`m` 的 `x` 在本模块以 `x` 再导出）进模块导出表 | — | `translator.mjs:317-399`（TS）/ `translator.mjs:273-314`（ArkTS） | 现有 IR 即可 |
| **CommonJS 互操作** `module.exports = x` | `BinaryExpression`（左操作数是 `module.exports` 的属性访问） | `module.exports` 是**普通命名空间属性** ⇒ `ARRAYSTORE module["exports"] = x`；与之对应的 ES 导出一律走上面「静态 `import` / `export` 语句」那行的零指令 + 导出表 ⇒ 同一份导出在两种写法下都能被 `import` 侧解析到 | — | `translator.mjs:1066-1103`（TS） | 现有 IR 即可 |
| **`import type` / `export type`** | `ImportDeclaration` / `ExportDeclaration` 的 `isTypeOnly`（TS `isImportDeclaration` / `isExportDeclaration`） | 编译期**完全擦除** ⇒ **零指令**（这就是它完整的运行期形态）；**类型图上的模块边**进 `TacTypeDecl.typeOnlyImports`（**IR-AU**）⇒ 引擎解析跨模块类型时能顺着这条边走，但它**不进调用图**（类型导入本来就不带值）⇒ 落点见 §7.4 的「`import type` / `export type`」行 | — | `translator.mjs:608`（TS）/ `translator.mjs:663-670`（ArkTS） | **IR-AU** |
| **JSX 元素** | `JsxElement` / `JsxSelfClosingElement` / `JsxFragment`（TS `isJsxElement` / `isJsxSelfClosingElement` / `isJsxFragment`） | JSX 是**语法糖** ⇒ 降级成一次函数调用：`t_e = CALL <owner>.<comp>/<n>`，`args[0]`=接收者、其后逐个是子元素与属性；`JsxExpression` 里的 `{expr}` 先求值再作为实参；`JsxText` 是静态字符串常量；只给运行期框架用的属性（`key` / `ref`）进 `annotationValues` | 组件的返回类型 | `translator.mjs:843-844`（TS） | 现有 IR 即可 |
| **正则字面量** | `RegularExpressionLiteral`（TS `isRegularExpressionLiteral`） | 正则是**不可变常量** ⇒ 新增常量种类 `CONST.REGEX`（`CONST` 表**末尾追加**，见 §9 的常量表段落）：`t_r = CONST REGEX "/ab+c/gi"`；**匹配语义**不在 TAC 里（它不是污点 / 数据流事实） | `STRING`（带正则标记） | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | 现有 IR 即可（常量表新增一种，不占新编号） |
| **`new Function(...)`** | `NewExpression`（TS `isNewExpression`） | 构造出的函数体是**运行期由字符串拼出来的** ⇒ `t_f = NEW CLASS<Function>`，其**函数体**建模为未知值 + `VALUE` 级 drop ⇒ 对 `t_f` 的调用是黑盒（§12 例外「`new Function(...)`」） | `CLASS<Function>` | `translator.mjs:934-950`（TS） | 现有 IR + §IR-B |
| **`eval(x)`** | `CallExpression`（被调者是 `Identifier` `eval`） | 参数 `x` 照常求值（副作用保留）；**返回值**是运行期才求出来的任意值 ⇒ 建模为未知值 + `VALUE` 级 drop（§12 例外「`eval(x)` 的返回值」） | `UNKNOWN` | `translator.mjs:892-931`（TS） | 现有 IR + §IR-B |
| **回调形参位置**（`setTimeout(cb, ms)` / `setInterval` / `ForEach` 的 item generator） | `CallExpression` 的实参位置上是 `ArrowFunction` / `FunctionExpression`（ArkTS 侧 `ts.isArrowFunction`） | 回调体按**闭包**降级（上面「箭头/函数表达式（闭包）」行）；实参槽放**闭包对象**：`t_cl = NEW CLASS<Closure>; FIELDSTORE t_cl.<cap> = <captured>; CALL <owner>.setTimeout/2 args=[receiver, t_cl, t_ms]`；`ForEach` 的 item generator 同形 | 回调的返回类型 | `translator.mjs:892-931`（TS）/ `translator.mjs:1109-1145`（ArkTS） | 现有 IR + §IR-E |
| **已知内建的污点源 / sink** | `CallExpression`（owner 命中 §9 的 `GLOBAL_OWNERS`） | 调用本身与普通调用**同形**（`CALL <owner>.<m>/n`）；**污点分类**由引擎侧的源 / sink 名表按 `owner.method` 键决定（`JSON.parse` 是源、`JSON.stringify` 是 sink、`localStorage.getItem` 是源、`document.cookie` 双向）⇒ 前端不特殊降级（§12 例外「已知内建的污点源 / sink 分类」） | 按调用 | `translator.mjs:892-931`（TS）/ `translator.mjs:1675-1703`（ArkTS） | 现有 IR 即可 |
| **`fetch(url)`** | `CallExpression`（owner = `fetch`） | `t_u = <url>; t_p = CALL fetch/1 args=[receiver, t_u]`（返回 Promise）；**响应体**是运行期才到的 ⇒ §12 例外「`fetch` 的响应体」 | `UNKNOWN`（Promise） | `translator.mjs:892-931`（TS）/ `translator.mjs:1109-1145`（ArkTS） | 现有 IR 即可 |
| **`super.m()` / `super.prop`** | `SuperExpression`（TS `isSuperExpression`） | `super.m()` ⇒ `CALL <基类>.m/n args=[this, …]`（`this` 是接收者）；`super.prop` ⇒ `FIELDLOAD this.prop` + `ownerType` = **基类名**（不是派生类名）⇒ 引擎据此区分「从基类读的字段」 | 按成员 | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | §IR-E |
| **`yield*`（委托）** | `YieldExpression` 带 `asteriskToken`（TS `isYieldExpression`） | 委托给另一个可迭代对象 ⇒ 展开成**迭代协议循环**（§6.4）：每次 `Iterator.next()` 的 `value` 作为 yield 值（`MOVE gen.yielded = v`），直到 `done` ⇒ 与上面「`yield v`」行同一个状态机，多一个「委托迭代器」字段 | 生成器类型 | `translator.mjs:843-844`（TS）/ `translator.mjs:1060`（ArkTS） | 现有 IR + §IR-E |**⚠ R21 已修 TS 侧**（`14` §3.44.4）：委托 yield 的**操作数就地求值** ⇒ ⇒ 委托的那棵子树进 TAC；迭代协议仍不在 TAC 里 ⇒ ⇒ 回执 `expr.generator_yield.delegate`（`SEMANTICS`）⇒ ⇒ **ArkTS 侧同形缺口，已实测，本轮未做**（`14` §3.44.3） |
| **`async` 函数 / `async` 箭头** | `FunctionDeclaration` / `ArrowFunction` + `ModifierFlags.Async`（`= 1024`） | 函数的**返回值是 Promise** ⇒ 函数体末尾 `t_p = NEW CLASS<Promise>; FIELDSTORE t_p.value = <返回值>; RETURN t_p`；`async` 标记进 `methodAnnotations`（取值 `async`）⇒ 引擎知道这个函数返回 Promise；`await` 的**微任务时序**不表达 ⇒ 记 `SEMANTICS` 级 drop（**本体已完整降级**：返回值包成 Promise 的形状完整，缺的只是「Promise 在微任务队列里的位置」） | `CLASS<Promise>` | `translator.mjs:457-467`（TS）/ `translator.mjs:466-485`（ArkTS） | 现有 IR + §IR-E + §IR-B |

**闭包细节**：`const add = (a) => a + captured;`
```
定义点： t_cl_1 = NEW CLASS<Closure> ; FIELDSTORE t_cl_1.captured = captured ; MOVE add = t_cl_1
被调函数 fn add(receiver, a) { t_c = FIELDLOAD receiver.captured ; t_r = BINOP ADD(a,t_c) ; RETURN t_r }
调用点： CALL mod.add/1   args = [add, 1]
```
`calleeSig` 仍是 `owner.method/arity`；`ownerOf`（`TS/translator.mjs:1387-1426`）需认识「receiver 是闭包对象」。

### 5.3 覆盖缺口补齐：九项逐条方案（2026-11）

本节把「审计 B 节点名、而本文初稿未覆盖」的九项逐条定案。每项给**四件套**：① 现状证据（`file:line`，行号已逐条打开源码核对，不抄审计报告）② 完整 TAC 形态 ③ IR 需求 ④ 改动清单（改动清单本体在 §10）。「**落点**」一栏写明它进哪张表、要不要新 IR 编号。

> **编号约定**：本节唯一新增的补充 IR 提案是 **`IR-AT`**（§8 的第 10 条，`BinaryOperator` 末尾追加 `LOOSE_EQ` / `LOOSE_NE`）。`IR-AO`…`IR-AS` 已被 `06` PHP/Python 文档占用，故本文新编号从 `IR-AT` 起，**不复用 `IR-R`…`IR-Z`**；`IR-AT` 已登记在 `01` §10.2 的全局分配表（归属 `05` TS/ArkTS）。

#### 5.3.1 松散相等 `==` 与严格相等 `===`

- **落点**：§5.2 表达式面表（新行）+ §8（**IR-AT**）+ §10。**不进 §12**（可完整表达）。
- **现状证据**：ArkTS 侧 `AK/translator.mjs:58-59` 把 `EqualsEqualsToken`（`==`）与 `EqualsEqualsEqualsToken`（`===`）**都映成 `"EQ"`**；`:60-61` 把 `!=` 与 `!==` 都映成 `"NE"`。TS 侧同形：`TS/translator.mjs:97-98`（`==`/`===` ⇒ `"EQ"`）、`:99-100`（`!=`/`!==` ⇒ `"NE"`）。运算符 token 在 `AK/translator.mjs:1239`（`node.operatorToken.kind`）是拿得到的，但 `:1259` 只取映射结果、`:1273-1280` 发指令时**只带 `binop` 字符串** ⇒ token 种类在 TAC 里彻底丢失，强制转换（coercion）的差别无从表达。IR 侧：比较算子只有 `EQ NE LT LE GT GE`（`IR/BinaryOperator.java:45-50`，加上 `LAND LOR` 到 `:53`），`BINOP` 指令只带 `binop`（`IR/TacInstruction.java:196`），分支守卫 `Compare` 复用的也是 `BinaryOperator`（`01` §IR-C 的 `record Compare(TacOperand lhs, BinaryOperator op, TacOperand rhs)`）。
- **完整 TAC 形态**：

```
// a === b   —— 严格相等（现状即此形态，保持）
  t_eq = BINOP EQ(a, b)              ; 分支守卫：Compare(a, EQ, b)
// a == b    —— 松散相等：新增算子
  t_eq = BINOP LOOSE_EQ(a, b)        ; 分支守卫：Compare(a, LOOSE_EQ, b)
// a != b / a !== b
  t_ne = BINOP LOOSE_NE(a, b)        ; 分支守卫：Compare(a, LOOSE_NE, b)
```

  语义由**引擎侧的规则表**决定（IR-Q 的「强制转换与相等语义的引擎侧规则表」，键取 `SourceLanguage` —— `JAVASCRIPT` / `TYPESCRIPT` / `ARKTS`，见 `IR/SourceLanguage.java:105,119,135`）：两侧静态类型相同 ⇒ 与 `EQ` 等价；一侧字符串一侧数字 ⇒ 先 `ToNumber`；一侧是 `null`/`undefined` ⇒ `null == undefined` 为真；其余不可判定 ⇒ 守卫**不贡献约束**（与 `TypeTest` 同处理，见 `01` §IR-C 的 Z3 映射表）。
- **IR 需求**：**IR-AT** —— `BinaryOperator` 枚举**末尾追加** `LOOSE_EQ` / `LOOSE_NE`（`enum BinaryOperator { …, LOOSE_EQ, LOOSE_NE }`，按名字序列化，`IR/BinaryOperator.java:55` 的 `serialVersionUID` 不变）。**不新增 instruction Kind、不新增 operand 变体、不新增 `TacType.Kind`**。爆炸半径实测 **0**：引擎里按 `BinaryOperator` 分派的 `switch` **全部带 `default`**（`DFA/taint/GuardTracker.java:708-716`、`:923-928`；`DFA/solver/GuardPredicateTranslator.java:142-155`、`:278-285`、`:294-301`）⇒ 新常量落进 `default`，语义正是「不是定界算子 / 不可翻译」。
- **改动清单**：§10 的「`TS/translator.mjs` / `lowerBinary`」「`AK/translator.mjs` / `lowerBinary`」「`IR/BinaryOperator.java` / 枚举体」三行。

#### 5.3.2 标签模板（tagged template）

- **落点**：§5.2 表达式面表（新行）+ §10。**不进 §12**（可完整表达）。**不需要新 IR**。
- **现状证据**：ArkTS 侧只认**无标签**的模板：`AK/translator.mjs:1041-1043`（`ts.isTemplateExpression(node)` ⇒ `lowerTemplate`），`:1496-1525` 的 `lowerTemplate` 把片段用 `ADD` 串起来；带标签的 `TaggedTemplateExpression` **没有任何分支**，落到 `:1060` 的兜底 ⇒ `UNHANDLED_NODE` + `HAVOC`。TS 侧同样：`:830-832` 只判 `isTemplateExpression(node)`，而 `:36-75` 的导入清单里**没有** `isTaggedTemplateExpression` ⇒ 同样落进 `:843-844` 的兜底（`UNHANDLED_NODE` + `HAVOC`）。
- **完整 TAC 形态**（标签函数被调用，字符串数组是第一个实参，插值逐个跟在后面）：

```
// tag`a${x}b${y}`   —— tag 是被调者
  t_s = NEWARRAY                       ; 静态片段数组
  ARRAYSTORE t_s[0] = CONST STRING "a"
  ARRAYSTORE t_s[1] = CONST STRING "b"
  t_x = <x> ; t_y = <y>
  t_r = CALL <owner>.tag/3   args = [receiver, t_s, t_x, t_y]
```

  洞的个数在源码里是**静态已知**的 ⇒ `CALL` 的 arity 定长 ⇒ **不需要 IR-W**。`args[0]` 恒为接收者槽（无接收者时放 owner 常量，规则同 `AK/translator.mjs:1118-1120`）。
- **IR 需求**：**无新 IR**。`CALL` + `NEWARRAY` + `ARRAYSTORE` 三种既有指令即可；owner 解析走 §5.3.8 的链（`tag` 是裸标识符时 owner = 导入它的模块，或结构化缺失）。
- **改动清单**：§10 的「两个 `translator.mjs` / `lowerExpression` 的表达式分派」行 —— 新增标签模板分支。

#### 5.3.3 动态 `import()` 的目标

- **落点**：§5.2 表达式面表（新行）+ §10。**不进 §12**（可完整表达）。**不需要新 IR**。
- **现状证据**：被调者的名字取不出来 ⇒ 名字退化成字面量 `"<call>"`：`AK/translator.mjs:1728-1743`（`methodNameOf`，`:1742` 是兜底 `return "<call>"`）—— 动态 `import()` 的被调表达式既不是属性访问也不是标识符，正好落这一支；TS 侧同形：`TS/translator.mjs:1447-1459`（`:1459` 的 `return "<call>"`）。owner 同时退化成**模块名**：`AK/translator.mjs:1675-1703` 的 `ownerOf`，`:1702` 的 `return this.moduleName;`。⇒ 最终的 `calleeSig` 由 `AK/translator.mjs:1139` 拼成 `${owner}.${method}/${arity}`，目录（sink catalogue）**匹配不上**，而且模块名这一段还可能**误匹配**别的模块。
- **完整 TAC 形态**：

```
// import("./helpers")   —— 说明符是字面量：目标可命名
  t_spec = CONST STRING "./helpers"
  t_ns   = CALL ./helpers.import/1   args = [CONST STRING "./helpers", t_spec]
// import(spec)         —— 说明符运行期才定：目标不可命名
  t_spec = <spec>
  t_ns   = CALL import/1              args = [CONST STRING "", t_spec]   ; 无 owner 段（IR-P 形状）
```

  两条的差别**只在 `calleeSig` 的 owner 段**：字面量 ⇒ owner = 模块路径（目录可静态匹配）；动态 ⇒ **无 owner 段**（IR-P 的「`owner 未知` ⇒ 结构化 owner 缺失」，`02` §2 IR-P 的规范表）。**不得**回落到模块名，也**不得**用 `UNKNOWN_OWNER` 哨兵。
- **IR 需求**：**无新 IR**。`calleeSig` 是既有字符串字段（`IR/TacInstruction.java:206`），无 owner 的形状（`<methodName>/<arity>`）是 IR-P 已定的第二种形状；引擎照样能接上 —— `DFA/cg/CallGraphBuilder.java:520-537` 解析 `calleeSig` 时会剥掉 owner 前缀（`"QueryBuilder.setQuery/1"` ⇒ `"setQuery/1"`），无 owner 时该步是空操作。
- **改动清单**：§10 的「两个 `translator.mjs` / `methodNameOf`」行 —— 动态 `import()` 不再退化成 `"<call>"`。

#### 5.3.4 `import.meta`

- **落点**：§5.2 表达式面表（新行）+ §12（属性**值**那一半）。**不需要新 IR**。
- **现状证据**：ArkTS 侧没有任何分支认它 ⇒ `AK/translator.mjs:1060` 的兜底（`UNHANDLED_NODE` + `HAVOC`）；TS 侧同样没有：`:36-75` 的导入清单里**没有** `isMetaProperty`，`lowerExpression`（`:762-845`）里也没有任何 `meta` 相关的分支 ⇒ 落进 `:843-844` 的兜底。
- **完整 TAC 形态**：`import.meta` 是**每个模块一个**的对象 ⇒ 用一个模块级合成函数承载，多个函数共用同一个对象（TAC 没有全局变量槽，这是唯一能表达「模块内共享」的形状）：

```
; 模块级合成函数 <module>.__meta/0（synthetic = true）
  t_m = NEW CLASS<Object>
  ARRAYSTORE t_m["url"] = <运行期才定的模块 URL>    ; VALUE 级 drop
  ARRAYSTORE t_m["env"] = <运行期才定的环境对象>    ; VALUE 级 drop
  RETURN t_m

; 使用处（任何函数内）
  t_m  = CALL <module>.__meta/0
  t_u  = ARRAYLOAD t_m["url"]
```

- **IR 需求**：**无新 IR**。`NEW` / `ARRAYSTORE` / `ARRAYLOAD` / `CALL` / `RETURN` 全是既有指令。属性**值**运行期才定 ⇒ §12 的「运行期才确定的值」这一类，**不进**「无法完整支持」的其余两格。
- **精度影响**：属性值建模为**未知值** ⇒ 既不当作干净、也不当作污点源（与 `IR/TacInstruction.java:110-134` 对 `HAVOC` 的规定同口径）⇒ 依赖 `import.meta.env.*` 的规则**不出结论**，而不是出错结论。
- **改动清单**：§10 的「两个 `translator.mjs` / `lowerExpression` 的表达式分派」行 —— 新增 `import.meta` 分支。

#### 5.3.5 ArkTS 的 `arkts-no-*` 规则

- **落点**：§9 wire 协议改动清单（文件级 `problems` / `programDrops`）+ §10。**不进 §12**（见下方「提法修正」）。**不需要新 IR**。
- **现状证据**：sidecar 只读**语法**诊断：`AK/compiler.mjs:346-352` 的 `syntaxErrorsOf` 只取 `program.getSyntacticDiagnostics(sourceFile)`；`AK/main.mjs:164-166` 的文件级 `problems` 也只来自它（`:199` 装进响应）。**语义**诊断（`getSemanticDiagnostics`）与 ArkTS 自己的 linter 诊断都没被读 ⇒ 一份违反 ArkTS 限制的文件被当成完全合法的文件分析。
- **提法修正（与上游审计的口径不同）**：这些规则**不是「解析器禁止的语法」**，而是**linter 规则** ⇒ 源码里**写得出来**，编译器也**报得出来**。证据：编译器包里有一张 152 槽的消息表 `cookBookTag`（`npm/ohos/lib/tsserver.js:166728-166731` 的 `for (let i = 0; i <= 151; i++)`），消息文本里**带规则 id**：`:166735` `arkts-no-var`、`:166789` `arkts-no-delete`、`:166796` `arkts-no-in`、`:166804` `arkts-no-destruct-decls`、`:166810` `arkts-no-for-in`；整个包里 **115** 个不同的 `arkts-*` 规则 id。而 linter 的诊断**不在** `getSemanticDiagnostics` 里 —— `npm/ohos/lib/tsserver.js:108605-108610` 的 `getSemanticDiagnosticsForFile` 只拼 `filterSemanticDiagnostics(...)` 与 `getProgramDiagnostics(...)`；linter 的诊断被单独放在 `programState.arktsLinterDiagnosticsPerFile`（`:170329`），对外的入口是 `runArkTSLinter`（`:170288`）。
- **完整 TAC 形态**（不是指令，是**文件级 drop**）：违反事实由编译器给出，前端只负责把它**记账**：

```
{ construct: "arkts-no-any-unknown",
  detail:   "Use explicit types instead of \"any\", \"unknown\" (arkts-no-any-unknown)",
  line: 12, column: 18,
  impact: "SEMANTICS", scope: "file" }        ; ⇒ programDrops[]（§IR-B）
```

  机器可读性靠 `construct` 里的规则 id；人读的信息靠 `detail`（编译器原文）。
- **IR 需求**：**无新 IR**。§IR-B 的 drop 通道现成（`TacFunction.drops` / `TacProgram.programDrops`）；文件级问题也有现成通道（`IR/TacProgram.java:113` 的 `programProblems`、`:403` 的 `addProgramProblem`），且 `FunctionProblem.kind` 是**自由字符串**（`IR/TacProgram.java:69-71`），Java 侧原样透传（`FE/sidecar/SidecarTacMapper.java:124`）⇒ 新增一种 kind **不需要改 IR**。
- **取到诊断的路径（前端工作量，不是能力缺口）**：`runArkTSLinter` 是编译器**公开导出**的 API —— `npm/ohos/lib/typescript.d.ts:8984` 与 `:9712`：`runArkTSLinter(tsBuilderProgram: BuilderProgram, srcFile?: SourceFile, buildInfoWriteFile?: WriteFileCallback, arkTSVersion?: string): Diagnostic[]`。⇒ 前端要为 lint 另建一个 **BuilderProgram**（现在是普通 Program：`AK/compiler.mjs:275` 与 `:287` 的 `ts.createProgram(...)`），然后调一次 `runArkTSLinter`。注意 linter **只 lint `.ets`**（`npm/ohos/lib/tsserver.js:170315` 的 `scriptKind !== 8` 跳过）。
- **改动清单**：§10 的「`AK/compiler.mjs` / `openProgram`」行与「`AK/main.mjs` / 文件级 `problems`」行 —— 建 BuilderProgram、跑 linter、把规则违反记成文件级 drop。

#### 5.3.6 `@Builder` / `@BuilderParam` 绑定

- **落点**：§4 声明面表（新行）+ §12（**绑定**那一半）+ §9（注解通道已列）+ §10。**不需要新 IR**。
- **现状证据**：装饰器**从头到尾没被读过**：`AK/translator.mjs:205-257` 的模块级语句循环与 `:341-367`（struct）、`:370-397`（class）的成员循环都**不碰** `node.modifiers`；`@Builder` 方法在 `:358-365` 被降级成普通方法，方法体在、**角色没了**。`@BuilderParam` 是**形参**上的装饰器，`AK/translator.mjs:565-575` 的 `lowerParameters` 只取名字与类型 ⇒ 哪个形参是 builder 参数同样看不见。**关键事实**：sidecar **已经**把 `@Builder` 告诉编译器了 —— `AK/compiler.mjs:194` 的 `render: { method: ["build"], decorator: ["Builder", "LocalBuilder"] }`（选项形状见 `npm/ohos/lib/typescript.d.ts:3416-3420` 的 `EtsOptions.render`）⇒ 编译器知道，**前端不读回来**。`AK/compiler.mjs:53-65` 的 `PROPERTY_DECORATORS`（11 个状态装饰器）同理：只被编译器自己的「用前未初始化」检查用（`needInitialization`），前端一个都不读。
- **完整 TAC 形态**：**标记**这一半完全可表达，IR 通道现成：

```
; 方法上的 @Builder
  methodAnnotations = ["Builder"]            ; IR/TacFunction.java:124
; 形参上的 @BuilderParam（按形参名索引）
  paramAnnotations = { "content": ["BuilderParam"] }   ; IR/TacFunction.java:126
; 装饰器实参
  annotationValues = { "onWatch": ["onWatch"] }        ; IR/TacFunction.java:154
```

  **绑定**这一半（父组件把值写进子组件的 `@BuilderParam`）**不可静态表达** ⇒ §12：那条边由框架在实例化时建立，源码里没有。
- **IR 需求**：**无新 IR**。`methodAnnotations` / `paramAnnotations` / `annotationValues` 三个字段**已在** `TacFunction` 里（`IR/TacFunction.java:124`、`:126`、`:154`）⇒ 缺的只是 wire 上把它们送过去（§9 已列 `classAnnotations`/`methodAnnotations`/`fieldAnnotations`/`paramAnnotations`/`annotationValues`）。
- **改动清单**：§10 的「`AK/translator.mjs` / `lowerStruct`、`lowerClass`」行 —— 读 `node.modifiers` 并填三个注解通道。

**状态管理装饰器的两类边**（同属「框架建立的边」，处理方式与 `@BuilderParam` 的绑定相同）：

| 装饰器 | 标记落在哪 | 边 | 能不能静态表达 |
|---|---|---|---|
| `@Provide` / `@Consume` | `fieldAnnotations`（取值 `Provide` / `Consume`）+ `annotationValues`（`@Provide('key')` 的 key） | **组件间的状态链接边**：`@Provide('key')` 的提供者 ⇒ `@Consume('key')` 的消费者 | **不能** —— 链接由框架在**实例化时**按 key 建立，源码里没有「谁在运行期被谁注入」的这条边 ⇒ §12 例外 |
| `@Observed` / `@ObjectLink` | `fieldAnnotations`（取值 `Observed` / `ObjectLink`） | **观察边**：`@ObjectLink` 指向的 `@Observed` 对象，其属性变化会触发观察者 | **不能** —— 观察关系是运行期建立的（谁被观察、什么时候变化）⇒ §12 例外 |

⇒ 两类的**标记**都能进 `fieldAnnotations`（通道现成，不占新编号）；两类的**边**都不建，改为在 §12 显式记账（`SEMANTICS` 级 drop）。

#### 5.3.7 `@Entry` 组件标识

- **落点**：§4 声明面表（新行）+ §12（**框架实例化**那一半）+ §9 + §10。**不需要新 IR**。
- **现状证据**：`AK/translator.mjs:341-367` 的 `lowerStruct` 只把属性与方法降级出来，**不读 `node.modifiers`** ⇒ 一个页面组件与一个普通组件在 TAC 里**完全一样**；`:225-233` 的模块级分派也只按「是 struct / 是 class」分流，看不到组件身份。**关键事实**：ArkTS 编译器的 `EtsOptions` 里**没有** `entry` 之类的字段（`npm/ohos/lib/typescript.d.ts:3416-3459`）⇒ `@Entry` 是**纯语法标记**，只能从 `node.modifiers` 读，编译器侧没有对应的开关可借力。顺带：sidecar 的 `ets` 选项（`AK/compiler.mjs:192-199`）只声明了 `render` / `components` / `libs` / `extend` / `propertyDecorators`；`extend.decorator` 是**空数组**（`:197`）⇒ 连 `@Extend` 都没声明；`styles` 与 `concurrent` 两个子块（`npm/ohos/lib/typescript.d.ts:3431-3442`）**根本没设** ⇒ `@Styles` / `@Concurrent` 同样对编译器不可见。
- **完整 TAC 形态**：

```
; 类型级（首选）：类级注解进类型声明
  TacTypeDecl(fqn = "mod.pages.IndexPage", simpleName = "IndexPage",
              annotations = ["Component", "Entry"], ...)      ; 01 §IR-E 的 TacTypeDecl.annotations
; 函数级（wire 上已有的通道）
  classAnnotations = ["Component", "Entry"]                 ; IR/TacFunction.java:123
```

- **IR 需求**：**无新 IR**。`classAnnotations`（`IR/TacFunction.java:123`）与 `TacTypeDecl.annotations`（`01` §IR-E）都在；`01` §IR-E 的生产消费方一栏已经写了「入口点分类：已读 `classAnnotations`」⇒ 这个通道**本来就是为入口点分类准备的**，今天只是没人往里填。
- **不可静态表达的部分**：路由在运行期实例化这个页面（页面参数由框架给）⇒ §12。
- **`@Concurrent` / `@Sendable`**（ArkTS 的并发标记，与 `@Component` / `@Entry` 走同一条 `node.modifiers` 通道）：方法照常降级；装饰器名进 `methodAnnotations`；**并发标记**进指令的 `effect` 集合（`CONCURRENT`，`IR/TacInstruction.java:172` 的 `Effect` 枚举已有 `DEFERRED` / `CONCURRENT`）⇒ 引擎知道这条调用不在本线程的顺序流里。`@Sendable`（可跨线程发送 ⇒ 捕获的变量必须可发送）是**编译期**约束 ⇒ 运行期不变 ⇒ TAC 里零额外指令，标记本身进 `methodAnnotations`。**跨线程的执行顺序**是外部事实 ⇒ §12 例外。
- **改动清单**：§10 的「`AK/translator.mjs` / `lowerStruct`、`lowerClass`」行 —— `@Component`/`@Entry` 进 `classAnnotations` 与 `TacTypeDecl.annotations`；`@Concurrent`/`@Sendable` 进 `methodAnnotations` 与 `effect`。

#### 5.3.8 `require()` / `document.*` / 字符串方法的 owner

- **落点**：§5.2 表达式面表（新行）+ §10。**不进 §12**（可完整表达）。**不需要新 IR**（走 IR-P + §IR-E 的 `ownerType`）。
- **现状证据**：ArkTS 侧 `AK/translator.mjs:1675-1703` 的 `ownerOf`：`:1690-1693` 取接收者的类名，取不到就在 `:1702` `return this.moduleName;` ⇒ **`require("./x")`、`document.getElementById(…)`、字符串方法的 owner 全是模块名** ⇒ 目录匹配不上，且模块名这一段还可能误匹配别的模块。**不对称的根因**在 `AK/translator.mjs:1829-1854` 的 `classNameOf`：`:1834-1836` 判 `!type.isObjectType()` ⇒ **基本类型不是 object type ⇒ 返回 undefined** ⇒ owner 落模块名；而数组类型的符号名是 `"Array"` ⇒ owner = `Array` ⇒ 同一个文件里，数组方法叫 `Array.join/1`、字符串方法叫模块名。TS 侧 `ownerOf` 是 `TS/translator.mjs:1387-1426`：`:1409`（基本类型接收者）、`:1422`（全局）、`:1425`（非名字被调者）三处都 `return UNKNOWN_OWNER`（`:139` 的 `const UNKNOWN_OWNER = "<unknown>";`）。**新事实**：`TS/translator.mjs:1428-1435` 的 javadoc 写的是「the call's owner falls back to the file's module」，而 `:1409` 的代码返回的是 `UNKNOWN_OWNER` ⇒ **注释与代码相反**。另外 `AK/translator.mjs:1752-1760` 的 `arityOf`：签名解析成功但参数个数为 0 时直接返回 0 ⇒ 未解析的 callee 的 arity 是 **0**（而实参是 N 个）。
- **完整 TAC 形态**（owner 解析五级链，前四级给名字，第五级**结构化缺失**）：

```
1) 命名空间导入   import * as vm from "vm"  ⇒  vm.runInNewContext(x)   owner = "vm"
2) 接收者的类名   obj.render()             ⇒  owner = "Rect"（checker 的类型符号名）
3) 基本类型的包装类 "abc".trim()            ⇒  owner = "String"；123..toFixed(2) ⇒ "Number"；
                                                 true..toString() ⇒ "Boolean"；1n..toString() ⇒ "BigInt"
4) 已知全局内建   require("./x") ⇒ owner = "require"；console.log(x) ⇒ "console"；
                 document.getElementById(x) ⇒ "document"；process.env.X ⇒ "process"
                 （表放 sidecar-shared/protocol.mjs，§2.3 M1 的常量表，两侧共用）
5) 其它           结构化 owner 缺失：calleeSig = "getElementById/1"（无 owner 段）+ ownerType = null
                 ★ 禁止回落到模块名；禁止 UNKNOWN_OWNER 哨兵（IR-P 的硬规则）
```

  arity 一并修正：**显式实参个数**（IR-P 的规定），未解析的 callee 也不再是 0（`AK/translator.mjs:1752-1760` 改成先看实参数）。
- **IR 需求**：**无新 IR**。owner 段在 `calleeSig` 字符串里（`IR/TacInstruction.java:206`），无 owner 的形状是 IR-P 已定的第二种形状；`ownerType` 是 §IR-E 已有的可选字段（`01` §IR-E 的 (b)）。**要改的是前端**：现状的 TS 侧用 `UNKNOWN_OWNER` 哨兵，**违反** `02` §2 IR-P 的规范表（「owner 未知 **不允许**用 `UNKNOWN_OWNER`；改用结构化 owner 缺失」）；ArkTS 侧回落到模块名，同样违反。
- **改动清单**：§10 的「两个 `translator.mjs` / `ownerOf`」「`arityOf`」与「新增 `sidecar-shared/protocol.mjs` 的 `GLOBAL_OWNERS`」三行。

#### 5.3.9 `extends`（类继承）

- **落点**：§4 声明面表（新行）+ §7.1 类型面表（新行）+ §10。**不进 §12**（可完整表达）。**不需要新 IR**。
- **现状证据**：heritage 子句**从未被读**：`AK/translator.mjs:370-397` 的 `lowerClass` 里，`:387-395` 的成员循环只认构造器与方法，`:375-385` 的初始化器只写**派生类自己**的字段初始化器 ⇒ **基类的字段初始化器与基类构造器都不会在派生类里跑** ⇒ 派生类的 `this.id` 在 TAC 里是空的（上游审计的 s02 就是这个）。TS 侧更彻底：`TS/translator.mjs:470-481` 的 `lowerClass` **只有方法分支、没有 `else`** ⇒ 构造器、字段、访问器、静态块**全部消失**。构造器本身的签名也是残的：`AK/translator.mjs:487-501` 的 `lowerConstructor` 拼出 `${moduleName}.${enclosingClass}.<init>()` —— **没有 arity、没有形参类型**，而 `super(…)` **没有任何分支** ⇒ `super` 落到 `:1060` 的兜底。
- **完整 TAC 形态**（顺序是 JS 规范定的：基类字段初始化器 → 基类构造器体 → 派生类字段初始化器 → 派生构造器体）：

```
; 基类的 <init>：字段初始化器放在构造器体内（不是独立初始化器函数）
  fn mod.Base.<init>/1 (this) {
    FIELDSTORE this.id = 1                 ; 基类字段初始化器
    ; …基类构造器体…
  }
; 派生类的 <init>
  fn mod.Derived.<init>/1 (this) {
    CALL mod.Base.<init>/1   args = [this]   ; ★ 基类构造器 + 基类字段初始化器，先跑
    FIELDSTORE this.own = 2                 ; 派生类字段初始化器，后跑
    ; …派生类构造器体…
  }
; 派生类构造器体里的 super(x)
  CALL mod.Base.<init>/1   args = [this, x]  ; ★ args[0] 恒为 this
```

  父类名同时进 `TacTypeDecl.superType`（`01` §IR-E 的 `TacTypeDecl`）⇒ 引擎能顺着 `superType` 链找到继承来的字段，`this.id` 才认得出是哪个类的字段。
- **`super` 的另外两种形态**（`SuperExpression`，TS `isSuperExpression`）：构造器里的 `super(…)` 是上面那条；**基类成员访问**的形状是：
  ```
  ; super.m(x)      —— 调用基类方法，接收者仍是 this
    t_r = CALL mod.Base.m/1   args = [this, x]
  ; super.prop      —— 读基类字段：ownerType 是「基类名」，不是派生类名
    t_v = FIELDLOAD this.prop   ownerType = "Base"
  ```
  ⇒ `ownerType` 记的是**声明该成员的那个类** ⇒ 引擎能区分「从基类读的字段」与「派生类自己的字段」。**原型链方法查找**（实例上调用的方法沿 `superType` 链由近到远找第一个定义）同形 ⇒ 引擎按同一条链解析 `owner.method/arity`。
    - **运行期原型改写的残留**：静态原型链（`extends` 链）已完整表达；但「谁在运行期往原型上挂了一个方法」（`X.prototype.m = …`）是**运行期行为** ⇒ 建模为**未知值** + `VALUE` 级 drop（**本体已完整降级**：按 `superType` 链的静态查找完全正确，缺的只是「运行期被加上去的那部分成员」）⇒ 落点 §7.4 之外的 §5.3.9 本段；附录 A 的 `TS #171` 行指向本段。
- **IR 需求**：**无新 IR**。`superType` 在 §IR-E 里现成；`CALL` + `FIELDSTORE` 都是既有指令。**注意**：`lowerConstructor` 的签名补上 arity 与形参类型后，必须与 `new` 点的键**逐字对齐** —— 现状两半**对不上**：`new` 点发的是 `calleeSig: this.constructorSig(node, args.length)`（`AK/translator.mjs:1191`），该助手拼出 `${name}.<init>/${arity}`（`:1212-1215`，**不带模块前缀、带 arity**）；而构造器自己的签名串是 `${moduleName}.${enclosingClass}.<init>()`（`:490`，**带模块前缀、不带 arity**）⇒ 分配点与构造器**匹配不上**，跨函数污点在构造器边界断掉。这是 §4「构造函数」那一行与 §11.1 的验收口径共同要求的。
- **改动清单**：§10 的「`AK/translator.mjs` / `lowerConstructor`」「`AK/translator.mjs` / `lowerClass`、`lowerConstructor`（heritage）」与「`TS/translator.mjs` / `lowerClass`」三行。

---

## 6. 控制流方案

`t_*` 为临时变量，`L_*` 为标签；每个 `BRANCH` 带 `guard`（§IR-C），每个标签带 `labelRef`（§IR-G）。

### 6.1 `if` 与短路算子

```
// if (c) A else B
  t_c = <c>
  BRANCH t_c -> L_THEN, L_ELSE        ; guard = Truthiness(t_c, TRUE)
L_THEN:  <A> ; JUMP L_END
L_ELSE:  <B>
L_END:

// a && b   结果 = a（a 假时）或 b（a 真时）
  t_a = <a> ; MOVE t_res = t_a
  BRANCH t_a -> L_RHS, L_END         ; guard = Truthiness(t_a, TRUE)
L_RHS:   t_b = <b> ; MOVE t_res = t_b ; JUMP L_END
L_END:

// a || b
  t_a = <a> ; MOVE t_res = t_a
  BRANCH t_a -> L_END, L_RHS         ; guard = Truthiness(t_a, TRUE)
L_RHS:   t_b = <b> ; MOVE t_res = t_b ; JUMP L_END
L_END:

// a ?? b   a 为 null/undefined 时取 b
  t_a = <a> ; MOVE t_res = t_a
  BRANCH t_a -> L_END, L_RHS         ; guard = Compare(t_a, NE, NULL)
L_RHS:   t_b = <b> ; MOVE t_res = t_b ; JUMP L_END
L_END:

// a &&= b   a 为假时整个表达式不求值 b
  t_a = <a>
  BRANCH t_a -> L_SKIP, L_RHS         ; guard = Truthiness(t_a, TRUE)
L_RHS:   t_b = <b> ; MOVE a = t_b
L_SKIP:  MOVE t_res = t_a

// a ||= b   a 为真时整个表达式不求值 b
  t_a = <a>
  BRANCH t_a -> L_SKIP, L_RHS         ; guard = Truthiness(t_a, TRUE)
L_RHS:   t_b = <b> ; MOVE a = t_b
L_SKIP:  MOVE t_res = t_a

// a ??= b   a 为 null/undefined 时才赋值
  t_a = <a>
  BRANCH t_a -> L_SKIP, L_RHS         ; guard = Compare(t_a, NE, NULL)
L_RHS:   t_b = <b> ; MOVE a = t_b
L_SKIP:  MOVE t_res = t_a
```
求值顺序严格保真（右操作数只在需要时求值）；`t_res` 两条路径都有定义（进分支前先 `MOVE`）⇒ 引擎的到达定值合并是并集，无需新指令。条件是 `==`/`!=`/`<`… ⇒ `Compare(lhs,op,rhs)`；裸值 ⇒ `Truthiness`。**不要**把 `if (x === 1)` 写成 `Truthiness(t_eq)`——那会丢掉 Z3 可用的比较事实。

**复合赋值**（`&&=` / `||=` / `??=`）按同一套模板展开：读左值 → 短路判定 → **只在需要时**求值右操作数并写回；表达式的值是**写回后的左值**（不是右操作数）⇒ 两条路径都用 `MOVE t_res = t_a` 收口。

### 6.2 三元与可选链

```
// c ? a : b
  t_c = <c>
  BRANCH t_c -> L_THEN, L_ELSE        ; guard = Truthiness(t_c, TRUE)
L_THEN:  t_a = <a> ; MOVE t_res = t_a ; JUMP L_END
L_ELSE:  t_b = <b> ; MOVE t_res = t_b
L_END:

// a?.b.c   a 为 nullish 时整条链取 undefined，且 .c 不求值
  t_a = <a>
  BRANCH t_a -> L_READ, L_NULL       ; guard = Compare(t_a, NE, NULL)
L_READ:  t_b = ARRAYLOAD t_a["b"]
  BRANCH t_b -> L_READ2, L_NULL2     ; guard = Compare(t_b, NE, NULL)   // 仅当 b 也可选
L_READ2: t_c = ARRAYLOAD t_b["c"] ; MOVE t_res = t_c ; JUMP L_END
L_NULL2: MOVE t_res = <undefined> ; JUMP L_END
L_NULL:  MOVE t_res = <undefined>
L_END:

// a?.()   a 为 nullish 时不发出 CALL，整条表达式取 undefined
  t_a = <被调表达式>
  BRANCH t_a -> L_CALL, L_NULL       ; guard = Compare(t_a, NE, NULL)
L_CALL:  t_r = CALL <owner>.<m>/n  args=[receiver, …] ; MOVE t_res = t_r ; JUMP L_END
L_NULL:  MOVE t_res = <undefined>
L_END:

// a?.[k]   a 为 nullish 时不发 ARRAYLOAD，整条表达式取 undefined
  t_a = <obj>
  BRANCH t_a -> L_IDX, L_NULL        ; guard = Compare(t_a, NE, NULL)
L_IDX:   t_k = <key> ; t_v = ARRAYLOAD t_a[t_k] ; MOVE t_res = t_v ; JUMP L_END
L_NULL:  MOVE t_res = <undefined>
L_END:
```
可选链的三种环（属性 `?.`、调用 `?.()`、下标 `?.[`）共用同一套形状：**先判空、再求值**；被短路的那一支**不发**读指令 / `CALL`，只把结果置为 `undefined`（**IR-X** 采纳后是 `CONST UNDEFINED`，否则是 `NULL`）。链上每一环都各自判空 ⇒ `a?.b.c` 在 `a` 为 nullish 时连 `c` 都不求值。

### 6.3 循环

```
// while (c)
L_HEAD:   t_c = <c>
  BRANCH t_c -> L_BODY, L_END        ; guard = Truthiness(t_c, TRUE)
L_BODY:   <body> ; JUMP L_HEAD
L_END:

// do { b } while (c)   条件在体之后
L_BODY:   <body>
L_COND:   t_c = <c>
  BRANCH t_c -> L_BODY, L_END        ; guard = Truthiness(t_c, TRUE)
L_END:

// for (init; c; step)
  <init>
L_HEAD:   t_c = <c>                  ; 无 c 时直接 JUMP L_BODY
  BRANCH t_c -> L_BODY, L_END
L_BODY:   <body>
L_STEP:   <step> ; JUMP L_HEAD
L_END:
```
`labelRef`：`LOOP_HEAD` = 循环头，`LOOP_BODY_END` = 体尾，`LOOP_END` = 循环尾（IR v2 §IR-G 要求 `LOOP_BODY_END` 与 `LOOP_END` 分开）。`continue` 目标 = `L_HEAD`（`while`/`for`）或 `L_COND`（`do..while`）；`break` 目标 = `L_END`。

**`for` 的初始化器与步进**（审计点名 `translator.mjs:678-684`）：现状 `lowerFor` 用
`isVariableStatement(node.initializer)` 判定 ⇒ 但 TS 编译器给 `for (let i = 0; …)` 的
initializer 是 **`VariableDeclarationList`**（**不是** `VariableStatement`）⇒ 判定恒假 ⇒
落进 `else` 的 `lowerExpression` ⇒ **声明与初始化式一起变成 `HAVOC`，`i` 从未被声明**。
⇒ 完整形态：

```ts
// for (let i = 0; i < n; i++) { ... }
  DECLARE i : INT              ; 局部量声明（**不是** HAVOC）
  MOVE i = 0                   ; 初始化器：逐个声明 + 逐个初始化
L_HEAD: t_c = MOVE (i < n)
  BRANCH t_c -> L_BODY, L_END
L_BODY: <body>
L_STEP: UNARYOP t1 = i POST_INC ; MOVE i = t1 ; JUMP L_HEAD
L_END:
```

- **守卫的判据要换**：从 `isVariableStatement` 改成 **`VariableDeclarationList`**。
  `for (;;)`（无 initializer）与 `for (x = 1; …)`（表达式式初始化器）各走自己的分支，
  **都不能落进「当表达式降级」的兜底**。
- 步进式 `i++` 走 §5.2 的一元算子表 ⇒ **循环归纳变量真的在变**
  （今天 `i` 恒定 ⇒ 引擎看到的是一个死循环）。
- `for (const x of y)` / `for (const k in o)` 的初始化器**形状不同**（元素 vs 键），
  见 §6.4 / §6.5。

**前缀 `++` / `--`**（`PrefixUnaryExpression`，运算符 token `PlusPlusToken` / `MinusMinusToken`）：**先改后取值** ⇒ 与后缀形态只有一条指令的差别：

```
// ++i   （先自增，再取新值）
  UNARYOP t1 = i PRE_INC ; MOVE i = t1 ; MOVE t_res = t1
// --i
  UNARYOP t1 = i PRE_DEC ; MOVE i = t1 ; MOVE t_res = t1
// i++   （先取旧值，再自增）—— 对照形态
  UNARYOP t1 = i POST_INC ; MOVE i = t1 ; MOVE t_res = <旧值>
```

⇒ 同一个 `i` 在 `x = i++` 与 `x = ++i` 里落到 `x` 的值**不同** ⇒ 前缀 / 后缀必须**分开**建模，不能都发 `POST_INC`。算子取 `UnaryOperator` 的 `PRE_INC` / `PRE_DEC` / `POST_INC` / `POST_DEC`（`IR/UnaryOperator.java:34-37`）。

### 6.4 `for..of`（迭代协议）

**必须绑元素，不能绑可迭代对象**——今天两侧都绑错了：TS 侧 `TS/translator.mjs:721-735`（注释说「The iterated value is the element」，代码却把 `iterable` 直接 `MOVE` 给循环变量），ArkTS 侧 `AK/translator.mjs:822-834` 与 `:856-868` 同病。

```
// 数组（元素类型已知）快路径
  t_arr = <iterable> ; t_len = FIELDLOAD t_arr.length ; t_i = CONST INT 0
L_HEAD:  t_cmp = BINOP LT(t_i, t_len)
  BRANCH t_cmp -> L_BODY, L_END      ; guard = Compare(t_i, LT, t_len)
L_BODY:  t_item = ARRAYLOAD t_arr[t_i] ; MOVE <loopVar> = t_item
         t_i = BINOP ADD(t_i, CONST INT 1) ; <body> ; JUMP L_HEAD
L_END:

// 通用迭代协议
  t_iter = <iterable> ; t_it = CALL Iterator[Symbol.iterator]/1  args=[t_iter]
L_HEAD:  t_step = CALL Iterator.next/0  args=[t_it]
         t_done = ARRAYLOAD t_step["done"]
  BRANCH t_done -> L_END, L_BODY     ; guard = Truthiness(t_done, TRUE)
L_BODY:  t_item = ARRAYLOAD t_step["value"] ; MOVE <loopVar> = t_item
         <body> ; JUMP L_HEAD
L_END:
```

**`for await`（异步迭代）**（`ForOfStatement` 带 `awaitModifier`）：形状 = §6.4 的通用迭代协议 + **每次取值后多一个 `await`**：

```
// for await (const x of asyncIterable) { … }
  t_iter = <iterable> ; t_it = CALL Iterator[Symbol.iterator]/1  args=[t_iter]
L_HEAD:  t_step = CALL Iterator.next/0  args=[t_it]
         t_done = ARRAYLOAD t_step["done"]
  BRANCH t_done -> L_END, L_BODY     ; guard = Truthiness(t_done, TRUE)
L_BODY:  t_raw = ARRAYLOAD t_step["value"]
         t_item = MOVE t_raw          ; ★ await：值域完整（解包值与 promise 同源）
         MOVE <loopVar> = t_item
         <body> ; JUMP L_HEAD
L_END:
```

`await` 的**微任务时序**不表达 ⇒ §12 例外「`await` 的**时序**」；但 `await` 的**值**是完整的 ⇒ 循环变量拿到的是**解包后的元素**，不是 promise。ArkTS 侧**没有** `for await`（ArkTS 不支持异步迭代），该构念只在 TS 侧出现。

### 6.5 `for..in`（键）

```
  t_obj = <obj> ; t_keys = CALL Object.keys/1  args=[t_obj] ; t_len = FIELDLOAD t_keys.length ; t_i = CONST INT 0
L_HEAD:  t_cmp = BINOP LT(t_i, t_len)
  BRANCH t_cmp -> L_BODY, L_END      ; guard = Compare(t_i, LT, t_len)
L_BODY:  t_key = ARRAYLOAD t_keys[t_i] ; MOVE <loopVar> = t_key
         t_i = BINOP ADD(t_i, CONST INT 1) ; <body> ; JUMP L_HEAD
L_END:
```

### 6.6 `switch`（真选择 + fall-through）

必须建出 case 选择，且 `default` 落在**链尾**（不能落在源码位置）。ArkTS 现状把所有 clause 按源码顺序内联（`AK/translator.mjs:897-919`，`:904-915` 顺序展开，`:918` 求值 discriminant 后丢弃）⇒ 所有 case 顺序执行、`default` 在源码位置执行。

```
  t_d = <discriminant>
  BRANCH t_d -> L_C1, L_T2           ; guard = Compare(t_d, EQ, v1)   ; labelRef = SWITCH_HEAD
L_T2: BRANCH t_d -> L_C2, L_T3        ; guard = Compare(t_d, EQ, v2)   ; labelRef = SWITCH_CASE
L_T3: BRANCH t_d -> L_C3, L_DEF_ENTRY ; guard = Compare(t_d, EQ, v3)
L_DEF_ENTRY: JUMP L_D                 ; 无 case 命中时的入口；L_D 是 default 体的源码位置
L_C1: <case 1 体> ; JUMP L_SW_END     ; 仅当该 case 以 break 结束时才发
L_C2: <case 2 体>                    ; 无 break ⇒ 自然落到下一个体（fall-through）
     JUMP L_SW_END
L_C3: <case 3 体>
L_D:  <default 体>
L_SW_END:                             ; labelRef = SWITCH_END
```
- **fall-through = 不发 `JUMP`**；`break` = `JUMP L_SW_END`。
- `default` 在源码中间时用 `L_DEF_ENTRY → JUMP L_D` 跳板：既保证「无命中才进 default」，又保证前一个 case 无 break 时 fall-through 进 default。
- 守卫用 `Compare(EQ)`（JS 的 case 体各不相同，`Membership` 不适用）。

### 6.7 `try / catch / finally`

```
L_TRY:     <try 体> ; JUMP L_FIN                     ; 正常完成
L_CATCH:   CATCH_HANDLER dest=e ctorType=<类型或无> ; <catch 体> ; JUMP L_FIN
L_RETHROW: THROW <expr> ; JUMP L_FIN                  ; catch 里再抛 ⇒ 也要过 finally
L_FIN:     FINALLY_ENTER label=L_FIN ; <finally 体>
L_END:
```
`labelRef`：`L_TRY` = `BLOCK`，`L_CATCH` = `CATCH_ENTRY`，`L_FIN` = `FINALLY_ENTRY`。

必填的 `tryRegions` 字段（§IR-F；IR 侧已有 `IR/TacFunction.java:749-835`，其中 `tryStart:751`、`tryEnd:754`、`catchStart:760`、`catchEnd:766`、`catchLabels:774`、`finallyLabel:781`、`finallyStart:787`、`finallyEnd:793`）：

| 字段 | 取值 |
|---|---|
| `tryStart`/`tryEnd` | try 体在指令流里的起止下标 |
| `catchStart`/`catchEnd` | 第一个 catch 体的起止下标 |
| `catchLabels` | 每个 catch 的入口标签（`L_CATCH`、`L_CATCH_1`…） |
| `catchTypes`（§IR-F 新增） | TS 的 `catch (e)` 无类型 ⇒ 约定 `"*"`；有类型标注时填类型名 |
| `finallyLabel` | `L_FIN` |
| `finallyStart`/`finallyEnd` | finally 体的起止下标 |
| `syntheticFinally`（§IR-F 新增） | `false`（TS 的 finally 是语言级的，非编译器合成） |
| `finallyExits`（§IR-F 新增） | `["L_FIN"]` + 每个会抛出的出口（如 `L_RETHROW`） |
| `resourceVars`（§IR-F 新增） | 空（TS/ArkTS 无 try-with-resources）；**有 `using` 声明时**填 `using` 变量名（见下面「`using` / `await using`」） |

现状：wire 上**根本没有** `tryRegions`，Java 侧硬编码空列表 —— `FE/sidecar/SidecarTacMapper.java:150`（该参数在 `IR/TacFunction.java:492` 与 `:543` 的构造器里是第 13 位）⇒ 六个 sidecar 语言的异常流边全是零。

**`using` / `await using`（显式资源管理）**：`using r = x` 在**作用域退出**时调用 `r.dispose()`；`await using r = x` 调用 `await r.disposeAsync()`。⇒ 它是**语言级**的「作用域退出钩子」，形状 = **合成的 finally**：

```
// using r = x   （所在作用域的每一个出口都要 dispose）
  t_r = <x>                          ; 资源变量照常绑定
  <作用域体>
L_SCOPE_END:                          ; 作用域的**每一个**出口都跳到这里
  t_d = CALL <Owner>.dispose/1  args=[t_r]
L_END:

// await using r = x   （dispose 是异步的 ⇒ 先 await 再继续）
L_SCOPE_END:
  t_p = CALL <Owner>.disposeAsync/1  args=[t_r]
  t_d = MOVE t_p                     ; await：值域完整
L_END:
```

- `resourceVars` 填 `["r"]`；`syntheticFinally` 填 `true`（这个 finally 是**编译器合成**的，不是用户写的 `finally`）⇒ **IR-F 的字段扩展**（`resourceVars` 的取值从「恒空」变成「可非空」，不占新编号）。
- 作用域的**每一个**出口（正常结束、`return`、`break`、`continue`、`throw`）都要走到 `L_SCOPE_END` ⇒ 与 §6.8 的标签栈共用同一套 `JUMP` 改写。
- `dispose` / `disposeAsync` 的**存在性**是运行期事实（对象可能没实现）⇒ 找不到方法时该调用记 `STRUCTURE` 级 drop。
- `await using` 的 `disposeAsync` 是 Promise ⇒ **值域完整**，但**微任务时序**不表达 ⇒ §12 例外「`await` 的**时序**」。

### 6.8 带标签的 break / continue

- 翻译器维护**标签栈**（标签名、目标标签、`labelRef.kind`、`scopeId`）。
- `break` ⇒ `JUMP` 到最内层 `break` 目标（循环 ⇒ `LOOP_END`；switch ⇒ `SWITCH_END`；带标签 ⇒ 该标签语句的结束标签）。
- `continue` ⇒ `JUMP` 到最内层 `continue` 目标（循环头 ⇒ `LOOP_HEAD`；`do..while` ⇒ 条件标签）。
- 带标签者跳到**标签语句**的标签（`labelRef.name` = 目标标签名，`kind` = 目标种类，`scopeId` = 嵌套层号）。
- 用户标签本身不产生额外指令，只是给已有标签起名 ⇒ `labelRef.name` 与 `label` 的一致性规则（§IR-G 风险条）仍成立。
- ArkTS 现状：`AK/translator.mjs:655-658` 直接降级内层语句，**标签被丢弃**。

### 6.9 守卫载荷速查

| 源条件 | `TacGuard` |
|---|---|
| `if (x)` / `if (x)`（`x` 声明为 `boolean`） | `Truthiness(x, TRUE)`（引擎按语言真值规则求值，§IR-H） |
| `if (x == y)` / `!=` / `<` / `<=` / `>` / `>=` | `Compare(lhs, op, rhs)` |
| `if (x instanceof Y)` | `TypeTest(x, Y, negated=false)` |
| `if (k in obj)` | `PropertyPresence(obj, k)`（**IR-S**） |
| `switch` 的 case | `Compare(discriminant, EQ, caseValue)` |
| `for` 的计数条件 | `Compare(i, LT, len)` |
| 恒真分支 | `Always(true)` |

### 6.10 声明种类、作用域与提升

三种声明关键字在 TAC 里的差别**只有一处**：变量的**初始化时机**与**作用域起点**。

| 声明 | 作用域 | 初始化时机 | `DECLARE` 的落点 |
|---|---|---|---|
| `var` | **函数**作用域（不是块作用域） | 声明处**提升**，赋值在声明处 | 提升到函数入口：函数体开头统一 `DECLARE v : <type>`（值为**未初始化** ⇒ `NULL`），赋值仍在原处 |
| `let` | 块作用域 | 声明处 | `DECLARE v : <type>` + `MOVE v = <init>` 在声明处 |
| `const` | 块作用域 | 声明处（**必须**有初始化器） | 同 `let`；`const` 的「不可重新赋值」是**编译期**约束 ⇒ 运行期不变 ⇒ TAC 里仍发同样的 `MOVE` |

- **提升（hoisting）**：`var` 与函数声明在**进入作用域**时就被绑定（值为 `undefined`），赋值在执行到声明处时发生。⇒ `var` 的 `DECLARE` 放在**函数入口**（不是声明处），赋值留在原处 ⇒ 「先读后写」在 TAC 里是「读到一个未初始化的值」，不是「读不到」。
- **TDZ（暂时性死区）**：`let` / `const` 在**声明之前**访问是运行期错误（`ReferenceError`）。⇒ 降级成一条**抛错边**：在声明点之前、任何读取该变量的位置插 `THROW <错误值>`，**错误的类型**已知 ⇒ `ctorType = "ReferenceError"`（IR-J 的 `THROW` 异常类型）⇒ 引擎知道这条边会抛什么、能被哪个 `catch` 接住；错误**实例**是运行期才造的 ⇒ 建模为未知值（不另记 drop：分析要用的是异常的**类型**，不是实例）。⇒ **不**把 TDZ 建成「值恒为 `undefined`」—— 那是 `var` 的语义。
- `DECLARE` 本身**不是** IR 指令：局部量的名字与类型进 `TacFunction.locals`（`IR/TacFunction.java:74`）⇒ 引擎看到的是「这个局部量在函数入口就已存在」，值由后续 `MOVE` 决定。`var` 提升 ⇒ 入口处就有一条 `MOVE v = CONST NULL`（未初始化）。

---

## 7. 类型面方案

### 7.1 TS 类型 → `TacType`（改造后）

现状是按 `TypeFlags` 逐位先匹配（`TS/translator.mjs:1490-1505`，ArkTS 同形 `AK/translator.mjs:1775-1806`）⇒ 联合塌成第一个命中成员（`string | null` ⇒ `STRING`），`any`/`unknown`/类型参数/字面量类型全落进 `:1504` 的 `UNKNOWN`，`Array<T>` 元素类型被丢（`:1501`）。

**列的读法**：「AST 节点」列给**解析器里的节点名**（TS 侧是 `typescript/unstable/ast` 的 `isXxx` 谓词，ArkTS 侧是 `ohos-typescript` 的 `ts.isXxx`）；「现状」列是**审计台账的证据串**（相对 sidecar 的 `src/` 目录，两面各给一条）。**纯类型层构念**（条件类型、映射类型、索引访问类型、模板字面量类型、`keyof`、`infer`、DNF、`const` 类型参数）**不产生任何运行期值** ⇒ 它们没有 `TacType` 形状，按 `UNKNOWN` 建模并记 `TYPE` 级 drop ⇒ §12 的 `TS11`–`TS19`。

| TS 类型 | AST 节点（TS / ArkTS sidecar） | `TacType` | `domain`（§IR-D） | 现状（审计证据） | 说明与需要的 IR 能力 |
|---|---|---|---|---|---|
| `string` | `KeywordTypeNode`（`StringKeyword`） | `STRING` | — | `translator.mjs:1490-1505` | |
| `number` | `KeywordTypeNode`（`NumberKeyword`） | `DOUBLE` | `Floating(53,11)` | `translator.mjs:1490-1505` | **JS 的 number 就是 double**：无 int，精度 2^53 ⇒ 整数溢出类检查器在 TS 上不应触发 |
| `boolean` | `KeywordTypeNode`（`BooleanKeyword`） | `BOOLEAN` | — | `translator.mjs:1490-1505` | |
| `bigint` | `KeywordTypeNode`（`BigIntKeyword`） | `LONG` + `domain` | 任意精度 | `translator.mjs:1490-1505` | JS `BigInt` 是**任意精度**整数，`LONG`（64 位）不够 ⇒ **IR-U**。现状映成 `LONG`（`TS/translator.mjs:1495`）是错的 |
| `symbol` | `KeywordTypeNode`（`SymbolKeyword`） | `UNKNOWN` | `Dynamic` | `translator.mjs:1496` | 符号身份不可静态判定 |
| `void` | `KeywordTypeNode`（`VoidKeyword`） | `VOID` | — | `translator.mjs:1490-1505` | |
| `null` | `LiteralTypeNode`（`NullKeyword`） | `NULL` | — | `translator.mjs:1490-1505` | |
| `undefined` | `KeywordTypeNode`（`UndefinedKeyword`） | `NULL`（现状）⇒ **IR-X** 采纳后为**独立的 `UNDEFINED` 常量** | — | `translator.mjs:1792-1797`（ArkTS） | **已覆盖**：常量层区分 `undefined` / `null`（**IR-X**：`TacOperand.Const` 增 `UNDEFINED`，wire 侧 `CONST.UNDEFINED`）⇒ 可选链短路结果不再被空指针检查器当成「可能为 null」；落点见 §5.2 的「可选链 `a?.b`」行与「可选调用 `a?.()`」行 |
| `any`/`unknown` | `KeywordTypeNode`（`AnyKeyword` / `UnknownKeyword`） | `UNKNOWN` | `Dynamic` | `translator.mjs:1490-1505` | 语义是「动态」，不是「种类未知」 |
| `never` | `KeywordTypeNode`（`NeverKeyword`） | `VOID` | — | `translator.mjs:1490-1505` | 无值；IR 无 `NEVER` |
| `object` | `KeywordTypeNode`（`ObjectKeyword`） | `CLASS<Object>` | — | `translator.mjs:1490-1505` | |
| 字面量 `"a"`/`1`/`true` | `LiteralTypeNode`（TS `isLiteralTypeNode`） | `STRING`/`DOUBLE`/`BOOLEAN` | — | `translator.mjs:1490-1505` | 值进 `Const`，类型仍是基础类型 |
| 联合 `string \| null` | `UnionTypeNode`（TS `isUnionTypeNode`） | `STRING`（`nullable=true`） | — | `translator.mjs:1490-1505` | `TacType` 已有 `nullable` 字段（`IR/TacType.java:231-247`） |
| 一般联合 `string \| number` | `UnionTypeNode` | 联合成员列表 | — | `translator.mjs:1490-1505` | **IR-T** |
| 交叉 `A & B` | `IntersectionTypeNode`（TS `isIntersectionTypeNode`） | `CLASS<A>` | — | `translator.mjs:1490-1505` | 取首成员并记 `STRUCTURE` 级说明（结构丢失、语义保留） |
| 类型参数 `T` | `TypeParameterDeclaration`（TS `isTypeParameterDeclaration` / ArkTS `ts.isTypeParameterDeclaration`） | `UNKNOWN` | `Dynamic` | `translator.mjs:545-552` | 实参在实例化处才确定；**约束**（`T extends U`）与**默认实参**（`T = U`）是**声明侧**事实 ⇒ 进 `TacTypeDecl.typeParameters`（**IR-Y 的字段扩展**：每个类型参数带 `constraint` 与 `defaultType`）⇒ 引擎能判断「这个实参是否满足约束」 |
| `Array<T>` / `T[]` | `TypeReferenceNode` / `ArrayTypeNode`（TS `isTypeReferenceNode` / `isArrayTypeNode`） | `ARRAY(component = map(T))` | — | `translator.mjs:1501` | **元素类型必须保留**：`checker.getTypeArguments(...)`（TS 侧 `npm/ts/dist/api/sync/api.d.ts:327`；ArkTS 侧 `npm/ohos/lib/typescript.d.ts:2576`）、`aliasTypeArguments`（`:450`/`:2966`） |
| 元组 `[A,B]` | `TupleTypeNode`（TS `isTupleTypeNode`） | `ARRAY(component = A)` + 长度 | — | `translator.mjs:1490-1505` | 逐元素类型无法逐个表达 ⇒ 记 `STRUCTURE` 级 drop，元素类型取首个；**元组里的剩余元素** `[A, ...T[]]` ⇒ 长度不定 ⇒ 同一条 `STRUCTURE` 级 drop（元素类型仍取首个） |
| 枚举 `E` | `EnumDeclaration` + `EnumMember` | `CLASS<E>` | — | `translator.mjs:608` | 底层是 number，成员值另存 |
| 类/接口 | `ClassDeclaration` / `InterfaceDeclaration`（TS `isClassDeclaration` / `isInterfaceDeclaration`） | `CLASS<名称>` | — | `translator.mjs:1528-1545` | 名称取自编译器符号名（`TS/translator.mjs:1528-1545` 的 `classNameOf`） |
| 类的父类（`extends`） | `HeritageClause`（`token` = `ExtendsKeyword`） | `CLASS<父类名>` | — | `translator.mjs:470-481` | 父类名取自 heritage 子句（`AK/translator.mjs:370-397` 现在**不读**它，`TS/translator.mjs:470-481` 同样不读）；父类链进 `TacTypeDecl.superType`（`01` §IR-E 的 `TacTypeDecl`），引擎才能顺着链找到继承来的字段 |
| 函数类型 | `FunctionTypeNode`（TS `isFunctionTypeNode`） | `CLASS<Function>` | — | `translator.mjs:1490-1505` | 参数与返回类型进 `TacTypeDecl.members` |
| `as`/`satisfies`/`<T>` | `AsExpression` / `SatisfiesExpression` / `TypeAssertion` | 不改类型 | — | `translator.mjs:843-844` | 运行期零行为；收窄靠后续分支的 `TypeTest` |
| **条件类型** `T extends U ? X : Y` | `ConditionalTypeNode`（TS `isConditionalTypeNode`） | **条件与两个分支都进 `TacType`**（IR-AU 的 `CONDITIONAL`）；`T` 具体 ⇒ 按编译期求值结果**直接取分支类型** | — | `translator.mjs:608` | **已覆盖**：零指令 —— 条件类型不产生运行期值，但它的**判定条件**与**两个分支**都是可静态求出的形状 ⇒ 进 `TacType.typeLayer`（**IR-AU**）⇒ 引擎能判断「这个值的类型在两个分支里分别是哪个」；落点 §7.4 |
| **映射类型** `{[K in keyof T]: T[K]}` | `MappedTypeNode`（TS `isMappedTypeNode`） | **键名模式 + 值类型**进 `TacType.typeLayer`（IR-AU 的 `MAPPED`） | — | `translator.mjs:608` | **已覆盖**：零指令 —— 展开后的成员表是**可静态求出的形状**（`keyof T` 可求 ⇒ 逐成员展开进 `TacTypeDecl.fields`；不可求 ⇒ 记「键名来自 `keyof T`」的模式 + 值类型）⇒ 引擎能判定「这个属性名在这个类型上存在吗」；落点 §7.4 |
| **索引访问类型** `T[K]` | `IndexedAccessTypeNode`（TS `isIndexedAccessTypeNode`） | `T`/`K` 都具体 ⇒ **直接取该成员的类型**；否则记 `TacType.typeLayer`（IR-AU 的 `INDEXED_ACCESS`：主体 + 键） | — | `translator.mjs:608` | **已覆盖**：零指令 —— `T[K]` 的取值依赖类型实参，但**依赖关系本身**是静态的 ⇒ 记「取 `T` 的第 `K` 个成员」这一关系，实例化时即可求值；落点 §7.4 |
| **模板字面量类型** `` `${A}-${B}` `` | `TemplateLiteralTypeNode`（TS `isTemplateLiteralTypeNode`） | `STRING` + **模式**进 `TacType.typeLayer`（IR-AU 的 `TEMPLATE_LITERAL`） | — | `translator.mjs:608` | **已覆盖**：零指令 —— 具体的字符串取值只在编译期存在，但**取值模式**（`${number}-${string}`）是静态的 ⇒ 引擎的路径 / 格式检查能用这个模式判定「这个值一定不含 `/`」；落点 §7.4 |
| **`keyof` T** | `TypeOperatorNode`（`operator` = `KeyOfKeyword`；TS `isTypeOperatorNode`） | 具体类型 ⇒ 键名的联合（`STRING` 字面量联合，**IR-T**）；类型参数 ⇒ `TacType.typeLayer`（IR-AU 的 `KEYOF`：主体 = `T`） | — | `translator.mjs:608` | **已覆盖**：零指令 —— `keyof T` 的键名集合在 `T` 具体时**完全可求**；`T` 是类型参数时「键名来自 `keyof T`」这一关系是静态的 ⇒ 记进 `TacType.typeLayer`；落点 §7.4 |
| **类型查询** `typeof x`（类型层） | `TypeQueryNode`（TS `isTypeQueryNode`） | 被查询表达式的 `TacType` | — | `translator.mjs:608` | **类型层**的 `typeof`（与 §5.2 的**运行期** `typeof` 运算符不是一回事）：类型本身可静态求出 ⇒ 记该 `TacType`；它**不产生运行期值** ⇒ 零指令（§5.2 的「`typeof x`」行只管运行期那一个） |
| **`infer`** | `InferTypeNode`（TS `isInferTypeNode`） | **绑定出的类型参数进 `TacTypeDecl.typeParameters[]`**（IR-Y 的 `constraint` 通道） | — | `translator.mjs:608` | **已覆盖**：`infer` 本身不是一个类型，是**类型绑定子** ⇒ 它绑定出的类型参数带上界后进 `TacTypeDecl.typeParameters`（**IR-Y 的字段扩展**：`constraint` + `defaultType`）⇒ 引擎能判断「这个实参是否满足 `infer` 出来的约束」；落点 §7.4 |
| **DNF 类型**（析取联合） | `UnionTypeNode` / `IntersectionTypeNode`（规范化后的形状） | 联合成员列表（**IR-T**） | — | `translator.mjs:608` | **已覆盖**：零指令 —— DNF 是**类型层规范化**规则，规范化后的**成员集合**完全可求 ⇒ 进 `TacType` 的联合成员列表（**IR-T**）；规范化**过程**是编译期计算，**不产生运行期事实** ⇒ 不表达（也不需要表达） |
| **`const` 类型参数** | `TypeParameterDeclaration` + `ModifierFlags.Const` | 实参推成**字面量类型**（`42` 而不是 `number`）⇒ 记该字面量 `TacType` | — | `translator.mjs:608` | **已覆盖**：零指令 —— `const` 只影响**实参推导的字面量宽度**，而推导结果是**静态可知**的字面量类型 ⇒ 直接记该字面量类型（不拓宽）；落点 §7.4 |
| **只读类型** `readonly T[]` / `Readonly<T>` | `TypeOperatorNode`（`ReadonlyKeyword`）/ `TypeReferenceNode` | `ARRAY(component = T)` | — | `translator.mjs:1490-1505` | 只读是**编译期**约束 ⇒ 运行期不变 ⇒ `TacType` 与非只读版本相同；只读事实进 `TacTypeDecl.fields`（**IR-E 的字段扩展**：字段增 `readonly` 标记） |
| **可选属性 / 可选形参**（`?`） | `PropertySignature` / `ParameterDeclaration` + `QuestionToken` | `T` 且 `nullable=true` | — | `translator.mjs:536-543, 1469-1477` | 类型的**可空**侧见上面「联合 `string \| null`」行；**「可缺省」语义**（调用点少传实参、callee 键的 arity 口径）⇒ callee 键的 arity 记**声明的形参数**（可选形参占位），实参少传时由被调方补 `undefined`（**IR-X** 采纳后为 `UNDEFINED`） |
| **类型别名** `type X = …` | `TypeAliasDeclaration`（TS `isTypeAliasDeclaration` / ArkTS `ts.isTypeAliasDeclaration`） | 别名**展开后**的 `TacType` | — | `translator.mjs:663-670`（ArkTS） | 运行期零行为 ⇒ 零指令；别名指向的类型直接进 `TacType`，别名本身进 `TacTypeDecl`（标 `isAlias`）⇒ 引擎按别名查到的就是展开后的形状 |
| **接口的 `extends`**（接口继承链） | `HeritageClause`（`token` = `ExtendsKeyword`） | `CLASS<接口名>` | — | `translator.mjs:608` | 父接口名进 `TacTypeDecl.superType`（与类的 `extends` 同一字段、同一语义）⇒ 引擎顺着链找继承来的成员；接口继承**不产生**运行期构造器调用（与 §5.3.9 的类继承不同） |
| **声明合并**（同名的多个接口声明） | 多个 `InterfaceDeclaration`（TS `isInterfaceDeclaration`） | `CLASS<名称>`（合并后的名字） | — | `translator.mjs:608` | 合并规则：同名接口的成员取**并集**进同一个 `TacTypeDecl`（字段取并、方法签名取并、重载不覆盖）⇒ 引擎按名字查到的就是合并后的形状；合并是**编译期**行为，运行期零指令 |

### 7.2 收窄（narrowing）

| 收窄来源 | AST 节点（TS / ArkTS sidecar） | 表达 |
|---|---|---|
| `if (typeof x === "string")` | `TypeOfExpression` + `BinaryExpression` | 分支守卫 = `Compare(t_typeof, EQ, CONST STRING "string")`；分支内 `x` 收窄为 `STRING` ⇒ 需「分支内类型改写」落点 ⇒ **IR-Z** |
| `if (x instanceof Y)` | `BinaryExpression`（`instanceof`） | 守卫 = `TypeTest(x, CLASS<Y>, false)`；分支内 `x` 收窄 ⇒ **IR-Z** |
| `if (k in obj)` | `BinaryExpression`（`in`） | 守卫 = `PropertyPresence(obj, k)`；分支内 `obj.k` 存在 ⇒ **IR-Z** |
| **类型谓词** `x is T` | `TypePredicateNode`（TS `isTypePredicateNode`） | 守卫 = `TypeTest(x, CLASS<T>, false)`（与 `instanceof` 同形）；分支内 `x` 收窄为 `T` ⇒ **IR-Z**；谓词为 `asserts` 前缀时见下面「断言函数」行 |
| **断言函数** `asserts x is T` | `AssertsKeyword`（TS `isAssertsKeyword`）+ `TypePredicateNode` | 函数体**正常返回** ⇒ 断言成立；断言失败 ⇒ 抛错 ⇒ 抛错路径建模为 `THROW <错误值>`（错误对象是运行期才造的 ⇒ 未知值 + `VALUE` 级 drop）；调用点之后的 `x` 收窄用 `TypeTest` 守卫表达 ⇒ **IR-Z**；「断言成立」这个事实本身**不是**运行期可观察的值 ⇒ 不发多余指令 |
| **非空断言** `x!` | `NonNullExpression`（TS `isNonNullExpression` / ArkTS `ts.isNonNullExpression`） | 运行期零行为（`x!` 就是 `x`；TS **不**插入任何运行期检查）⇒ **零指令**；「非空事实」是**类型层**的事实 ⇒ 该表达式在后续代码里的静态类型是不可空的 `T`（不是 `T \| null`）⇒ 落点是 `TacType` 的 `nullable=false`（与 §7.1 的「联合 `string \| null`」行同一机制） |

§IR-C 的 `TypeTest` 能表达「类型测试」，但**收窄结果**（分支内变量的类型）没有落点 ⇒ IR-Z。

### 7.3 数值域落点

| 语言事实 | 落点 |
|---|---|
| JS `number` 是 double | `DOUBLE` + `Floating(53,11)` ⇒ Z3 侧是 Real 而非 Int ⇒ Cwe190/191 在 TS 上**不应**按整数溢出判定 |
| JS `bigint` 任意精度 | `Unbounded()`（§IR-D），但 Kind 仍是 64 位 `LONG` ⇒ **IR-U** |
| `+` 既是加法也是拼接 | 共用 `ADD`，由 `TacType` 判定 ⇒ IR 不需改 |

### 7.4 类型层计算的落地（IR-AU）

**要解决的问题**：`keyof` / 条件类型 / 映射类型 / 索引访问类型 / 模板字面量类型 / `infer` /
`const` 类型参数 / `import type` 这 8 类构念**不产生任何运行期值** ——
但它们**也不是「没有信息」**：它们产生**可静态求出的形状**
（键名集合、成员表、字符串模式、类型之间的关系）。
原来的做法是「按 `UNKNOWN` 建模 + `TYPE` 级 drop」⇒ **形状信息整体丢失**，
「按类型推断的规则」在引擎侧拿不到任何依据。⇒ 这一轮把它们从**永久例外**改成**有精确落点**。

**IR-AU 的数据形状**：

```
record TacTypeLayer(
    TacTypeLayerKind kind,       // KEYOF | MAPPED | CONDITIONAL | INDEXED_ACCESS | TEMPLATE_LITERAL | LITERAL
    TacType subject,             // 计算对象（T）
    TacType key,                 // KEYOF / MAPPED / INDEXED_ACCESS 的键
    TacType valueType,           // MAPPED 的值类型
    TacType condition,           // CONDITIONAL 的判定类型
    TacType thenType,            // CONDITIONAL 的 then 分支
    TacType elseType,            // CONDITIONAL 的 else 分支
    String pattern               // TEMPLATE_LITERAL 的模式 / LITERAL 的字面量文本
)
```

- 载体：`TacType.typeLayer`（可选，挂在结果类型上）+ `TacTypeDecl.typeLayers`（列表，声明级）
- **零指令**：类型层约束**不产生任何 TAC 指令** —— 它是**类型元数据**
- `TacTypeLayerKind` 是**新枚举**（沿用「枚举末尾追加、按名字序列化」的既有纪律）

**逐构念的落点**：

| 构念 | 落到 `TacTypeLayer` 的什么 | 能静态求出的部分 | 求不出时的保守形状 |
|---|---|---|---|
| **`keyof` T** | `KEYOF(subject = T)` | `T` 具体 ⇒ 键名集合（进 `TacType` 联合成员，**IR-T**） | `T` 是类型参数 ⇒ `KEYOF(subject = T)` |
| **条件类型** `T extends U ? X : Y` | `CONDITIONAL(condition = U, thenType = X, elseType = Y)` | `T` 具体 ⇒ 按编译期结果**直接取分支类型** | 条件与两个分支都记下来 |
| **映射类型** `{[K in keyof T]: X}` | `MAPPED(key = KEYOF(T), valueType = X)` | `T` 具体 ⇒ 逐成员展开进 `TacTypeDecl.fields` | 记「键来自 `keyof T`、值类型是 `X`」 |
| **索引访问类型** `T[K]` | `INDEXED_ACCESS(subject = T, key = K)` | `T`/`K` 都具体 ⇒ 直接取该成员类型 | 记「取 `T` 的第 `K` 个成员」这一关系 |
| **模板字面量类型** `` `${A}-${B}` `` | `TEMPLATE_LITERAL(pattern = "…")` | 模式静态可知 ⇒ 引擎可按模式判定字符串性质 | 记模式（`${number}-${string}`） |
| **`infer`** | 不进 `TacTypeLayer` ⇒ 绑定出的类型参数进 `TacTypeDecl.typeParameters[]` 的 `constraint`（**IR-Y 的字段扩展**） | 约束静态可求 | — |
| **`const` 类型参数** | `LITERAL(pattern = "42")` ⇒ 实参推成**字面量类型** | 实参的字面量值静态可知 | — |
| **`import type` / `export type`** | 不进 `TacTypeLayer` ⇒ 模块边进 `TacTypeDecl.typeOnlyImports` | 边静态可知 | — |

**生产方**：`mapType`（`TS/translator.mjs:1490-1505` 的类型映射入口，ArkTS 同形
`AK/translator.mjs:1775-1806`）遇到**类型层节点**时
⇒ **不再返回 `UNKNOWN`**，而是返回「基础类型 + `typeLayer` 约束」；
实参由类型查询 API 给出（TS 侧 `npm/ts/dist/api/sync/api.d.ts:327` 一带；
ArkTS 侧 `npm/ohos/lib/typescript.d.ts:2576`）⇒ 实参具体时按上表「能静态求出的部分」直接求值。

**消费方**（IR-AU 存在的理由）：

1. **属性存在性**：引擎拿到 `keyof T` 的键名集合 ⇒ 「`obj.k` 这个属性在类型上**不存在**」
   可以**判定**（而不是「不知道」）⇒ 属性级规则从「不出结论」变成「有结论」。
2. **字符串性质**：模板字面量类型的模式 ⇒ 路径穿越 / 格式检查可以判定「这个值一定不含 `/`」。
3. **分支内类型**：条件类型的两个分支 ⇒ 引擎在分支里可以把类型取成对应那一个（与 §7.2 的收窄同源）。
4. **跨模块类型解析**：`import type` 的模块边 ⇒ 跨文件类型解析能顺着走，不必整目录扫描。

**明确边界（避免过度承诺）**：

- 类型层约束**不产生指令** ⇒ 引擎**不能**靠它推断运行期值；它只影响「按类型推断的规则」。
- 条件类型的**判定本身**（`T extends U` 为真还是假）是编译期事实，不是运行期事实
  ⇒ `T` 具体时按编译期结果取分支（与 TS 前端一致），`T` 不具体时两个分支都记着。
- 映射类型的**成员表**在 `T` 是类型参数时**不可完全展开** ⇒ 记模式，不展开。

---

## 8. IR 需求（需要新 IR 能力才能完整表达的构念）

每条：构念 → 需要的 IR 能力 → 数据形状 → 为什么现有 IR 不够。**不新增 opcode**；全部是枚举末尾追加或既有结构上的可选字段。

| 编号 | 构念 | 需要的 IR 能力 | 数据形状 | 为什么现有 IR 不够 |
|---|---|---|---|---|
| **IR-R** | `**` | `BinaryOperator` 增 `POW` | `enum BinaryOperator { …, POW }`（末尾追加，按名字序列化，`IR/BinaryOperator.java:55` 的 `serialVersionUID` 不变） | 现枚举只有 `ADD…USHR`/`EQ…GE`/`LAND LOR`（`:32-53`）；近似成 `MUL`（`TS/translator.mjs:153-157`）**语义不同**；线上若发 `"POW"`，`FE/sidecar/SidecarTacMapper.java:198` 的 `valueOf` 抛异常被 `:199-201` 吞成 `ADD` ⇒ 静默错误 |
| **IR-S** | `in` | `TacGuard` 增一变体 | `record PropertyPresence(TacOperand receiver, TacOperand key) implements TacGuard {}` | §IR-C 的 `Compare/Truthiness/Membership/TypeTest/Always` 都不表达「键是否存在」；`Membership` 是「值 ∈ 集合」，语义不同 |
| **IR-T** | 一般联合类型 | `TacType` 能表达成员集合 | (a) `Kind` 增 `UNION` + `List<TacType> unionMembers`；(b) `TacType` 增可选字段 `unionMembers`（`kind` 仍 `UNKNOWN`） | `IR/TacType.java:40-63` 只有 14 个基础种类；(a) 波及引擎按 `TacType.Kind` 分派的 **9 处 `switch (t.kind())`（分布在 8 个文件）**：`DFA/cg/CallGraphBuilder.java:818,864`（**2 处**）、`DFA/checkers/boundary/Range.java:135`、`UnderflowConstraintChecker.java:144`、`NumericTruncationChecker.java:149`、`OverflowConstraintChecker.java:161`、`ArrayBoundsConstraintChecker.java:111`、`DivZeroConstraintChecker.java:97`、`DFA/cg/VtaCallGraphStrategy.java:244`（各 1 处）；另有 2 处 `switch (c.kind())` 是按 `Const.Kind` 分派（`LiteralBooleanConditionChecker.java:97`、`TaintedCheckerBase.java:2126`），**不受** `TacType.Kind` 新增影响⇒ (b) 爆炸半径 0 |
| **IR-U** | `bigint` 任意精度 | 数值域与种类一致 | (a) `Kind` 增 `BIGINT`；(b) 规定 `domain=Unbounded()` 时以 domain 为准 | `LONG` 会被边界检查器读成 64 位（§IR-D 默认推断 `LONG→Integral(64,true)`）；现状把 `bigint` 映成 `LONG`（`TS/translator.mjs:1495`）是错的 |
| **IR-V** | `delete obj.k` | `ARRAYSTORE` 加「删除」标志 | `private final boolean deleteKey;`（仅 `ARRAYSTORE` 用） | `ARRAYSTORE` 只能写值不能删键 ⇒ 「键不存在」与「键值为 `undefined`」无法区分（影响 `in` 判定与属性存在性检查） |
| **IR-W** | 调用参数展开 `f(...args)` | `CALL`/`NEW` 加「剩余参数数组」 | `private final TacOperand spreadArgs;`（`args` 固定参数在前） | `IR/TacInstruction.java:227` 的 `args` 是定长列表，表达不了「参数个数运行期才定」 |
| **IR-X** | 可选链的 `undefined` 值 | 常量层区分 `undefined`/`null` | `TacOperand.Const` 增 `UNDEFINED`（wire 侧 `CONST.UNDEFINED`） | 常量种类无 `undefined`（`TS/protocol.mjs:75-84`、`AK/protocol.mjs:82-91`），`NULL` 同时承担两者 ⇒ 空指针检查器会把可选链结果当成可能为 null |
| **IR-Y** | 索引签名 / 泛型实参表 | `TacTypeDecl` 描述索引器与类型参数 | `TacTypeDecl` 增 `List<TacTypeDecl.IndexSignature> indexSignatures`、`List<TacTypeDecl.TypeParameter> typeParameters`（**IR-Y 的字段扩展**：`TypeParameter` 再带 `constraint` 与 `defaultType` 两个字段 ⇒ 泛型**约束**与**默认实参**有落点，见 §7.1 的「类型参数 `T`」行） | §IR-E 的 `TacTypeDecl` 有 `fields`/`members`，无索引签名与类型参数的位置 |
| **IR-Z** | 分支内类型收窄 | 守卫能带「收窄后的类型」 | `TacInstruction` 上加可选字段 `Map<String,TacType> narrowedTypes`（仅 `BRANCH` 用） | §IR-C 只让引擎推出路径条件；`TypeTest` 的结果没有回写到变量类型 ⇒ `if (x instanceof Y)` 之后的 `x.y` 仍看不到 `Y` 的字段 |
| **IR-AT** | 松散相等 `==` / `!=` 与严格相等 `===` / `!==` 的区别 | `BinaryOperator` 增两个比较算子 | `enum BinaryOperator { …, LOOSE_EQ, LOOSE_NE }`（**枚举末尾追加**，按名字序列化，`IR/BinaryOperator.java:55` 的 `serialVersionUID` 不变） | 现枚举的比较组只有 `EQ NE LT LE GT GE`（`IR/BinaryOperator.java:45-50`）；`==` 与 `===` 今天都映成 `EQ`（`AK/translator.mjs:58-59`、`TS/translator.mjs:97-98`）⇒ 强制转换的差别在 TAC 里彻底丢失，而 `Compare` 守卫复用的也是 `BinaryOperator`（`01` §IR-C）⇒ 守卫里也分不出来。**爆炸半径 0**：引擎里按 `BinaryOperator` 分派的 `switch` 全部带 `default`（`DFA/taint/GuardTracker.java:708-716`、`:923-928`；`DFA/solver/GuardPredicateTranslator.java:142-155`、`:278-285`、`:294-301`）⇒ 新常量落进 `default`，语义正是「不是定界算子 / 不可翻译」；相等语义本身由 IR-Q 的引擎侧规则表按 `SourceLanguage`（`IR/SourceLanguage.java:105,119,135`）决定 |

| **IR-AU** | **类型层计算**（`keyof` / 条件类型 / 映射类型 / 索引访问类型 / 模板字面量类型 / `infer` / `const` 类型参数 / `import type`） | 把「编译期类型计算的结果」变成 IR 里的**类型层约束**（零指令） | `record TacTypeLayer(TacTypeLayerKind kind, TacType subject, TacType key, TacType valueType, TacType condition, TacType thenType, TacType elseType, String pattern)`；载体 `TacType.typeLayer`（可选）+ `TacTypeDecl.typeLayers`（列表）+ `TacTypeDecl.typeOnlyImports`（类型级模块边）；`enum TacTypeLayerKind { KEYOF, MAPPED, CONDITIONAL, INDEXED_ACCESS, TEMPLATE_LITERAL, LITERAL }`（**新枚举**，末尾追加的同款纪律） | 这些构念**不产生任何运行期值**，但**产生可静态求出的形状**（键名集合、成员表、字符串模式、类型之间的关系）。今天的做法是「按 `UNKNOWN` 建模 + `TYPE` 级 drop」⇒ **形状信息整体丢失**，「按类型推断的规则」在引擎侧拿不到任何依据 ⇒ 需要一个**承载形状**的元数据。**零指令** |
| **IR-AV** | **装饰器的运行期应用** | 装饰器链 + 阶段顺序 + 「返回值是否替换被装饰实体」 | `record TacDecorator(String calleeSig, TacDecoratorPhase phase, boolean replacesTarget)`；`enum TacDecoratorPhase { MEMBER, CLASS }`；载体 `TacTypeDecl.decorators` + `TacMemberRef.decorators`；**运行期部分复用现有 `CALL`** | 装饰器**运行期就是一个被调用的函数** ⇒ 装饰器实参的污点必须能流进被装饰实体；「legacy 类装饰器的返回值替换 `C`」也是运行期事实。但「装饰器链 / 阶段 / 是否替换」**没有任何既有 IR 位置** ⇒ 需要一个**新元数据类**（不新增 instruction） |

`IR-R`–`IR-Z`、`IR-AT`、`IR-AU`、`IR-AV` 之外**不要求任何新 opcode**；全部是枚举末尾追加、既有结构上的可选字段或**新元数据类**（元数据类**不产生指令**），与 §0.3 兼容约束一致。

### 8.1 既有提案的字段级扩展（**不占新编号**）

下面六项是**既有提案的字段扩展**，不是新提案 ⇒ **不申请新的 IR 编号**（`01` §10.2 的全局分配表不动；本轮新增的 `IR-AU` / `IR-AV` 是**新提案**，它们**落在既有 IR 类上的字段**登记在 `01` §10.3）。每项只给「扩展哪个字段、加什么」一句话。

| 扩展自 | 扩展的字段 | 加什么 | 为什么需要 | 落点 |
|---|---|---|---|---|
| **IR-Y 的字段扩展** | `TacTypeDecl.TypeParameter` | `constraint`（约束类型）+ `defaultType`（默认实参）两个字段 | 泛型的**约束**与**默认实参**是声明侧事实；没有它们，引擎既判断不了「这个实参是否满足约束」，也表达不了「没传实参时用什么」 | §7.1 类型面表「类型参数 `T`」行 + §4 声明面表「泛型接口」侧的类型参数 |
| **IR-W 的字段扩展** | `TacFunction` 的形参元数据 | `restParamIndex`（剩余形参的位置；-1 表示没有） | **调用点**的展开由 IR-W 的 `spreadArgs` 表达；**被调方**的 `f(...xs)` 需要一个「最后一个形参是数组」的位置 ⇒ 两边才能对上 | §4 声明面表「剩余形参」行 + §7.1 类型面表「类型参数 `T`」行（泛型实参表） |
| **IR-E 的字段扩展** | `TacTypeDecl.fields` 的每个字段 | `readonly` 标记 + `visibility`（`public`/`private`/`protected`）+ `override` 标记 | `readonly` / 可见性 / `override` 都是**编译期**约束 ⇒ 运行期不变 ⇒ 不需要新指令；但它们是**声明面的事实**，必须有个落点，否则引擎判不了「不该出现的写」 | §4 声明面表「`readonly` 属性」「可见性修饰符」「`override` 属性」三行 |
| **IR-F 的字段扩展** | `TryRegion.resourceVars` | 取值从「恒空」变成「可非空」（`using` 变量的名字列表） | TS 的 `using` / `await using` 是**语言级**的作用域退出钩子 ⇒ 合成 finally ⇒ `resourceVars` 必须能装下它，否则「退出时要 `dispose`」这件事在 TAC 里没有形状 | §6.7 小节「`try / catch / finally`」的「`using` / `await using`」段 |

| **IR-AU 的字段扩展** | `TacTypeDecl.typeOnlyImports` | 类型级导入 / 导出的模块边列表（`String` 模块标识） | 纯类型导入**编译后完全擦除** ⇒ 运行期零指令；但「本模块在**类型图上**依赖了哪个模块」是真实存在的边，引擎做跨模块类型解析时需要它 ⇒ 不能只靠「什么都不记」 |
| **IR-AV 的字段扩展** | `TacTypeDecl.decorators` + `TacMemberRef.decorators` | 装饰器链（`List<TacDecorator>`，按运行期调用顺序；类装饰器与成员装饰器**分开两个列表**，因为标准装饰器里两者**分属不同阶段**） | 装饰器链是**声明面的事实**（谁被装饰、装饰器是谁、哪个返回值会替换被装饰实体）⇒ 与 `annotations` 同属声明面元数据，不另开容器 |

⇒ 六项都是「既有结构上多一个可选字段」，爆炸半径与 §0.2 的口径一致（按名字序列化、`serialVersionUID` 不变、旧数据读出来是 `null`）。

---

## 9. wire 协议改动清单

| 键 | 层级 | 形状 | TS sidecar | ArkTS sidecar | Java 消费点 |
|---|---|---|---|---|---|
| `precision` | 函数 | `"SOURCE_FULL"\|"SOURCE_LOWERED"\|"SOURCE_DEGRADED"` | 改：由 drops 推导（现 `TS/translator.mjs:522-527` 写死） | 改（`AK/translator.mjs:551-556`） | `FE/sidecar/SidecarTacMapper.java:132-135` 扩三种值 |
| `drops` | 函数 | `[{construct,detail,line,column,impact,scope}]` | 增 | 增 | 增 → `TacFunction.drops`（§IR-B） |
| `programDrops` | 文件 | 同上 | 增 | 增 | 增 → `TacProgram.programDrops` |
| `memberKind` | 函数 | `"METHOD"\|"CONSTRUCTOR"\|"GETTER"\|"SETTER"\|"STATIC_INITIALIZER"\|"FIELD_INITIALIZER"\|…` | 增 | 增 | 增 → `TacFunction.memberKind`（§IR-E） |
| `tryRegions` | 函数 | `[{tryStart,tryEnd,catchStart,catchEnd,catchLabels,catchTypes,finallyLabel,finallyStart,finallyEnd,syntheticFinally,finallyExits,resourceVars}]` | 增 | 增 | 增：`FE/sidecar/SidecarTacMapper.java:150` 的 `List.of()` 换真实映射 |
| `returnType` | 函数 | 类型对象 | **修**：现发 `undefined`（`TS/translator.mjs:521`），JSON 后键消失 ⇒ `FE/sidecar/SidecarTacMapper.java:140` 读到 null ⇒ 返回类型恒 `UNKNOWN` | **修**（`AK/translator.mjs:550`） | 已有读取（`:140`），无需改 |
| `guard` | 指令 | `{kind:"Compare",lhs,op,rhs}` / `"Truthiness"` / `"Membership"` / `"TypeTest"` / `"Always"` / `"PropertyPresence"` | 增 | 增 | 增：`toInstruction`（`FE/sidecar/SidecarTacMapper.java:159-227`）→ `TacInstruction.guard` |
| `ownerType` | 指令 | 字符串 | 增（类字段用） | 增 | 增 → `TacInstruction.ownerType`（§IR-E） |
| `labelRef` | 指令 | `{name,kind,scopeId}` | 增 | 增 | 增 → `TacInstruction.labelRef`（§IR-G） |
| `domain` | 类型 | `{kind:"Integral",bits,signed}`/`"Unbounded"`/`"FixedPoint"`/`"Floating"`/`"Dynamic"` | 增 | 增 | 增：`toType`（`FE/sidecar/SidecarTacMapper.java:282-312`）→ `TacType.domain`（§IR-D） |
| `unionMembers` | 类型 | 类型对象数组（**若采纳 IR-T(b)**） | 增 | 增 | 增（随 IR-T） |
| `types` | 文件 | `[{fqn,simpleName,superType,interfaces,annotations,fields,fieldAnnotations,members,isInterface,isAbstract,isEnum,isRecord,isSynthetic}]` | 增 | 增 | 增 → `TacProgram.types`（§IR-E） |
| `classAnnotations`/`methodAnnotations`/`fieldAnnotations`/`paramAnnotations`/`annotationValues` | 函数 | 字符串 / 字符串表 / `Map<string,string[]>` ×2 | 增（`fieldAnnotations` 另装 `readonly` / `visibility` / `override` 标记，见 §8.1 的 IR-E 字段扩展） | 增（ArkTS 的 `@State`/`@Prop` 与 18 个状态装饰器全集在此落） | 增：`FE/sidecar/SidecarTacMapper.java:144-147,154` 的 `List.of()`/`Map.of()` 换真实映射 |
| `calleeParamTypes` | 指令 | 字符串表（每个形参一个类型，形如 `"string"` / `"number[]"`） | 增 | 增 | **已有读取**：`IR/TacInstruction.java:225` 的 `List<String> calleeParamTypes`（今天恒为空表 ⇒ 消费方分不出「同名同参数的两个重载」）；改的是 `toInstruction`（`FE/sidecar/SidecarTacMapper.java:159-227`）的填充 |
| `effects` | 指令 | `["DEFERRED"]` / `["CONCURRENT"]`（`IR/TacInstruction.java:258` 的 `Set<Effect>`） | 增（`async` 标记走 `methodAnnotations`，不进 `effects`） | 增（`@Concurrent` / `@Sendable` ⇒ `CONCURRENT`） | 已有字段：`IR/TacInstruction.java:258`；改的是 `toInstruction` 的填充 |
| `problems`（文件级） | 响应 | `[{kind,message,nodeClass,line,column}]` | **补**：现恒为空 `diagnostics: []`（`TS/main.mjs:217`） | 已有（`AK/main.mjs:164-173,199`） | `FE/ts/TsTacFrontend.java` 需像 `FE/arkts/ArktsTacFrontend.java:310-330` 那样消费；`kind` 是自由字符串（`IR/TacProgram.java:69-71`，`FE/sidecar/SidecarTacMapper.java:124` 原样透传）⇒ 新增 kind 不改 IR |
| `programDrops`（文件级，续） | 文件 | 同 `drops` | 增：ArkTS 规则违反记 `{construct:"arkts-no-<rule>", …, impact:"SEMANTICS", scope:"file"}`（§5.3.5） | 增 | 增 → `TacProgram.programDrops`（§IR-B） |

**常量表新增**：`KIND` 不变；`CONST` 增 `UNDEFINED`（若采纳 IR-X）与 `REGEX`（正则字面量，§5.2 的「正则字面量」行；**枚举末尾追加**，不占新编号）；`TYPE` 增 `BIGINT`（若采纳 IR-U）；`PRECISION` 增 `DEGRADED: "SOURCE_DEGRADED"`；`PROBLEM` 增 `ARKTS_RULE: "ARKTS_RULE_VIOLATION"`（§5.3.5，枚举末尾追加）。**另加一张 `GLOBAL_OWNERS` 全局内建名表**（`require` / `console` / `document` / `process` / `globalThis` / `JSON` / `Math` / `fetch` / `localStorage` / `setTimeout` / `setInterval` / `eval` / `Function` / …，§5.3.8 的 owner 解析第 4 级）—— 与 `KIND`/`TYPE`/`CONST` 一样是**纯常量表**，放 `sidecar-shared/protocol.mjs`（§2.3 M1），不含任何 AST 代码。**注意**：`GLOBAL_OWNERS` 只给**名字**（用来定 owner），不给污点语义；「谁是源、谁是 sink」由**引擎侧**的源 / sink 名表按 `owner.method` 键决定（§5.2 的「已知内建的污点源 / sink」行）。
**协议表本身要共享**：现在两份 `protocol.mjs` 各写一份（`TS/protocol.mjs:26-111`、`AK/protocol.mjs:33-118`），内容逐字相同 ⇒ 改一处忘另一处是必然事件 ⇒ 迁到 `plumb-frontend/sidecar-shared/protocol.mjs`（§2.3 M1/M3）。

---

## 10. 改动清单

| 文件 | 函数 / 位置 | 要加什么 | 用到的 IR / wire 能力 |
|---|---|---|---|
| `TS/translator.mjs` | `lowerClass`（`:470-481`） | 穷尽分派：字段、构造器、getter/setter、静态块、索引签名；`else` 兜底降级为字段 | §IR-E `memberKind`+`ownerType` |
| 同上 | `lowerStatement`（`:556-609`） | 增加 `switch`/`try`/`do..while`/`for..in`/带标签语句；删兜底 | §IR-C/§IR-F/§IR-G |
| 同上 | `lowerBinary`（`:990-1022`） | 删近似优先逻辑；`instanceof`/`in` 单独处理；`&&`/`\|\|`/`??` 改短路展开 | IR-R/IR-S + §IR-C |
| 同上 | 新增 `lowerShortCircuit`/`lowerConditional`/`lowerOptionalChain`/`lowerSwitch`/`lowerTry`/`lowerForOf`/`lowerForIn`/`lowerDo`/`lowerLabels` | §6 各模板的实现 | §IR-C/§IR-F/§IR-G |
| 同上 | `mapType`（`:1490-1505`） | 改按类型结构分派：联合、字面量、类型参数、`Array<T>`、元组、`bigint` | §IR-D + IR-T/IR-U |
| 同上 | `finishFunction`（`:515-534`） | 精度由 drops 推导；补 `returnType`/`memberKind`/`drops`/`tryRegions` | §IR-A/§IR-B/§IR-E/§IR-F |
| 同上 | 新增 `emitDrop(construct,detail,impact)` | 统一丢弃出口 | §IR-B |
| `AK/translator.mjs` | `lowerClass`（`:370-397`）、`lowerStruct`（`:341-367`） | 补访问器、静态块、静态成员 | §IR-E |
| 同上 | `lowerSwitch`（`:897-919`） | 换 §6.6 真分派模板 | §IR-C/§IR-G |
| 同上 | `lowerTry`（`:928-959`） | 接异常边 + `tryRegions` 各字段 | §IR-F |
| 同上 | `lowerForOf`（`:822-834`）/`lowerForIn`（`:856-868`） | 绑元素/键 | §6.4/§6.5 |
| 同上 | `lowerBinary`（`:1238-1282`） | 同 TS 侧 | IR-R/IR-S |
| 同上 | `mapType`（`:1775-1806`）、`finishFunction`（`:544-563`） | 同 TS 侧 | §IR-A/§IR-B/§IR-D/§IR-E |
| `TS/translator.mjs` | `lowerBinary`（`:990-1022`） | `==`/`!=` 改用 `LOOSE_EQ`/`LOOSE_NE`，`===`/`!==` 保持 `EQ`/`NE`（运算符 token 种类不再丢） | **IR-AT** + IR-Q |
| `AK/translator.mjs` | `lowerBinary`（`:1238-1282`） | 同 TS 侧 | **IR-AT** + IR-Q |
| `IR/BinaryOperator.java` | 枚举体（`:45-53`） | **末尾追加** `LOOSE_EQ` / `LOOSE_NE`（`serialVersionUID` 不变，按名字序列化） | **IR-AT** |
| 两个 `translator.mjs` | `lowerExpression` 的表达式分派（TS `:762-845`、AK `:969-1062`） | 新增**标签模板**分支：静态片段 `NEWARRAY` + `ARRAYSTORE`，再 `CALL <tag>/<1+洞数>`（洞数定长，不需要 IR-W） | 现有 IR 即可 |
| 同上 | 同上 | 新增 **`import.meta`** 分支：每模块合成一个 `<module>.__meta/0` 函数（`NEW CLASS<Object>` + 逐属性 `ARRAYSTORE`），使用处 `CALL` 它 | 现有 IR 即可 |
| 同上 | `methodNameOf`（TS `:1447-1460`、AK `:1728-1743`） | **动态 `import()`** 不再退化成 `"<call>"`：`calleeSig` 的 owner 段取字面量模块路径，或取**无 owner** 的形状 | IR-P |
| `AK/compiler.mjs` | `openProgram`（`:260-295`） | 为 lint 另建一个 **BuilderProgram**（现在是普通 `ts.createProgram`），调 `runArkTSLinter`（编译器公开 API）取 `arkts-no-*` 诊断 | §IR-B 的 drop 通道 |
| `AK/main.mjs` | 文件级 `problems`（`:164-166`、`:199`） | 收 ArkTS 规则违反（kind = `ARKTS_RULE_VIOLATION`）；机器可读形式（`construct = "arkts-no-<rule>"`）进 `programDrops` | §IR-B |
| `AK/translator.mjs` | `lowerStruct`（`:341-367`）、`lowerClass`（`:370-397`） | 读 `node.modifiers`：`@Component`/`@Entry` ⇒ `classAnnotations`；`@Builder` 方法 ⇒ `methodAnnotations`；`@BuilderParam` 形参 ⇒ `paramAnnotations`；装饰器实参 ⇒ `annotationValues` | §IR-E |
| 同上 | `lowerConstructor`（`:487-501`） | 签名补 arity 与形参类型（与 `new` 点的 `Impl.<init>/1` 对齐）；`super(…)` ⇒ `CALL <基类>.<init>/n args=[this, …]` | §IR-E |
| 同上 | `lowerClass`（`:370-397`）、`lowerConstructor`（`:487-501`） | 读 `heritageClauses`：派生类 `<init>` 的**第一条**是 `CALL <基类>.<init>/n args=[this]`；基类字段初始化器落**基类 `<init>` 体内**（不再是独立的初始化器函数）；父类名进 `TacTypeDecl.superType` | §IR-E（`superType`） |
| `TS/translator.mjs` | `lowerClass`（`:470-481`） | 补 `else` 穷尽分派 + 读 `heritageClauses`（同 AK 侧）；派生类 `<init>` 的首条同样是基类 `<init>` 的 `CALL` | §IR-E |
| 两个 `translator.mjs` | `ownerOf`（TS `:1387-1426`、AK `:1675-1703`） | owner 解析改**五级链**（§5.3.8）：命名空间导入 → 接收者类名 → 基本类型包装类 → 全局内建 → **结构化 owner 缺失**。**删** TS 侧的 `UNKNOWN_OWNER` 哨兵（`:139`）与 AK 侧的模块名回落（`:1702`） | IR-P + §IR-E |
| 同上 | `arityOf`（TS `:1469-1477`、AK `:1752-1760`） | 未解析的 callee 的 arity 改为**显式实参个数**（现在是 0，而实参是 N 个） | IR-P |
| 新增 `sidecar-shared/protocol.mjs` 的 `GLOBAL_OWNERS` | — | 全局内建名表（`require` / `console` / `document` / `process` / `globalThis` / `JSON` / `Math` / `fetch` / `localStorage` / `setTimeout` / `setInterval` / `eval` / `Function` / …），纯常量、无 AST 代码 | §IR-E 的 `ownerType` |
| 两个 `main.mjs` | `translate`（TS `:172-220`、AK `:143-202`） | 响应带 `programDrops`、`types`、文件级 `problems`（TS 补） | §IR-B/§IR-E |
| 两个 `protocol.mjs` | 整文件 | 迁到 `sidecar-shared/protocol.mjs`，本地只留 re-export | §2.3 M1 |
| 新增 `sidecar-shared/constructs.json` | — | 构念 id → TAC 形状 / guard 种类 / memberKind / wire 键 | §2.3 M1 |
| 新增 `sidecar-shared/conformance/` | — | 每个构念 id 一份 TS 语料 + 一份 ArkTS 语料 + 期望 TAC 骨架 | §2.3 M2 |
| `FE/sidecar/SidecarTacMapper.java` | `toFunction`（`:75-156`）、`toInstruction`（`:159-227`）、`toType`（`:282-312`） | 读 `drops`/`memberKind`/`tryRegions`/`guard`/`ownerType`/`labelRef`/`domain`/`types`/注解通道；`:150` 换真实 tryRegions；`:132-135` 扩三种精度 | §IR-A…§IR-G |
| `FE/ts/TsTacFrontend.java` | `parse`（`:168-309`） | 消费文件级 `problems`；精度汇总（`:280-286`）扩三种值 | §IR-A/§IR-B |
| `FE/arkts/ArktsTacFrontend.java` | `parse`（`:168-294`） | 同上 | §IR-A/§IR-B |
| 两个 `PROTOCOL.md` | 全文 | 补新键（字段表 `plumb-frontend/ts-sidecar/PROTOCOL.md:175-239`）；「What the sidecar does not model」表（`:307-318`）改写为「保守建模清单」 | — |
| `TS/translator.mjs` | `lowerClass`（`:470-481`） | 补：`implements`（进 `TacTypeDecl.interfaces`）、`accessor` 字段（合成 getter + setter）、构造器参数属性（`FIELDSTORE this.<param>`）、`readonly` / 可见性 / `override` 标记（进 `fieldAnnotations`）、`#private` 字段（名字保留 `#` 前缀） | §IR-E（`interfaces` / `fieldAnnotations`） |
| `TS/translator.mjs` | `lowerParameters`（`:536-543`） | 补：可选形参（`nullable` + arity 口径）、默认参数值（函数体开头补 `MOVE`）、剩余形参（`ARRAY` 类型 + `restParamIndex`）、`this` 形参（只进类型不进签名） | §IR-E + **IR-W 的字段扩展** |
| 同上 | `lowerVariableStatement`（`:611-629`） | 逐个 `declaration` 处理：函数型提升为独立函数，其余照常 `MOVE` ⇒ 一条语句里既有函数又有变量时**两类都不丢**；解构模式补**默认值**与**嵌套**两支；数组模式补**剩余元素** | 现有 IR 即可 |
| 同上 | `lowerExpression` 的表达式分派（`:762-845`） | 新增分支：一元算子（`NOT`/`BITNOT`/`NEG`）、前缀 `++`/`--`（`PRE_INC`/`PRE_DEC`）、复合赋值（`&&=`/`\|\|=`/`??=`）、`void` 运算符、逗号运算符、正则字面量（`CONST.REGEX`）、可选调用 `?.()`、可选下标 `?.[`、静态 `import`/`export`（零指令 + 导出表）、再导出（别名边）、CommonJS 互操作、`import type`/`export type`（零指令 + `TYPE` 级 drop）、JSX 元素（降级成一次 `CALL`）、`eval(x)` 与 `new Function(...)`（未知值 + `VALUE` 级 drop） | §IR-C + §IR-B（`drop(TYPE)` / `drop(VALUE)`） |
| 同上 | `lowerStatement`（`:556-609`） | 补：`using` / `await using`（合成 finally + `resourceVars`）、`for await`（迭代协议 + `await`）、赋值式解构（`DestructuringAssignment`） | §IR-F（`resourceVars` 字段扩展）+ §IR-C |
| 同上 | `mapType`（`:1490-1505`） | 补类型层构念的形状：条件类型 / 映射类型 / 索引访问类型 / 模板字面量类型 / `keyof` / `infer` / DNF / `const` 类型参数（**按 §7.4 逐条记进 `TacType.typeLayer`，不再统一按 `UNKNOWN` + `TYPE` 级 drop**）、`readonly` 类型、类型别名、接口 `extends`、声明合并、可选属性 | §IR-T + **IR-AU** + **IR-Y 的字段扩展** |
| 同上 | 新增 `lowerDecorator` | 读 `node.modifiers` 里的 `Decorator` 节点：装饰器**表达式**照常降级（参数求值、副作用保留），装饰器**标识符**给出 `calleeSig`；类装饰器发 `CALL <dec>/1 args=[C]`、成员装饰器发 `CALL <dec>/2 args=[C.prototype, "m"]`；装饰器链与阶段顺序进 `TacTypeDecl.decorators` / `TacMemberRef.decorators`；**ArkTS 侧**命中 `AK/compiler.mjs:53-65` 内建清单的装饰器**不发** `CALL`（走 §12 的例外路径） | **IR-AV** |
| 同上 | `lowerImport` / `lowerExport`（`:317-399` 一带） | `isTypeOnly` 的导入 / 导出 ⇒ **零指令**，但模块边进 `TacTypeDecl.typeOnlyImports` | **IR-AU** |
| 同上 | 模块级语句循环（`:280-295`） | 模块级语句**不并进任何函数** ⇒ 拆成模块级合成函数 `<module>.__top/0` | 现有 IR 即可 |
| `AK/translator.mjs` | `lowerStruct`（`:341-367`）、`lowerClass`（`:370-397`） | 补：`implements`、可见性 / `override` / `readonly` 标记、`@Concurrent` / `@Sendable`（进 `methodAnnotations` 与 `effect`）、18 个状态管理装饰器全集（进 `fieldAnnotations`）、`IDataSource` 实现类（普通类降级）、`abilityContext`（零指令 + `VALUE` 级 drop） | §IR-E + §IR-B |
| 同上 | `lowerParameters`（`:565-575`） | 同 TS 侧：可选形参 / 默认参数值 / 剩余形参（ArkTS 禁止参数属性） | §IR-E + **IR-W 的字段扩展** |
| 同上 | `lowerStruct`（`:341-367`） | 明确「**初始器只发一次**」：字段初始化器**不**复制进每个方法（否则一次副作用被报多次） | §IR-E |
| `FE/ts/TsTacFrontend.java` | `parse`（`:168-309`） | **跳过 ambient `.d.ts`**：声明文件不翻译，但要报一条文件级 `PARSE_PROBLEM`（否则声明文件里的声明被当成丢了） | §IR-B |
| `FE/arkts/ArktsTacFrontend.java` | `parse`（`:168-294`） | 跳过 ambient `.d.ts` / `.d.ets`（报文件级 `PARSE_PROBLEM`）；**`.ts` 文件只参与类型解析、不被翻译**（显式取舍：ArkTS 前端只接受 `.ets`，`fileExtensions() == {ets}`） | §IR-B |

---

## 11. 验收口径

### 11.1 必须产生的 TAC 形状

| 源构念 | 必须出现 | 守卫 / 标签 |
|---|---|---|
| `a && b` | `BRANCH` 链；右操作数**不在**真分支之前求值；结果变量两条路径都有定义 | `Truthiness` |
| `a \|\| b` / `a ?? b` | 同上，极性相反；`??` 用 `Compare(NE,NULL)` | `Truthiness`/`Compare` |
| `c ? a : b` | 两条 `MOVE` 支，`a`/`b` 各只求值一次 | `Truthiness` |
| `a?.b` | 先 `BRANCH` 再 `ARRAYLOAD`；空值分支不发 `ARRAYLOAD` | `Compare(NE,NULL)` |
| `switch` | N 个 `BRANCH` 分派 + N 个 case 体；`default` 只在无命中时可进入；有 `break` 的 case 末尾有 `JUMP` | 每 case 一个 `Compare(EQ)` |
| `try/catch/finally` | `CATCH_HANDLER` + `FINALLY_ENTER`；`tryRegions` 非空，`catchTypes`/`finallyExits` 有值 | — |
| `for..of` | 循环变量绑**元素**（不是可迭代对象）；存在元素值来源 | — |
| `for..in` | 循环变量绑**键**（字符串） | — |
| `break`/`continue` | 产生 `JUMP`（今天两侧都不产生） | `labelRef` ∈ {LOOP_HEAD, LOOP_END, SWITCH_END} |
| `x instanceof Y` | 产生 `INSTANCEOF`（TS 侧今天没有） | `TypeTest` |
| `k in obj` | 产生 `BRANCH` + 真假两支 | `PropertyPresence` |
| 类字段 | 产生 `FIELDSTORE`（带 `ownerType`），不是 `ARRAYSTORE` | — |
| 构造器/getter/setter/静态块 | 各自独立函数，`memberKind` 正确 | — |
| 联合 `string \| null` | 类型为 `STRING` 且可空；**不得**塌成裸 `STRING` | — |
| `number` | `DOUBLE` + `domain=Floating(53,11)` | — |
| `bigint` | 带 `domain=Unbounded()`（或 `Kind=BIGINT`） | — |
| `Array<string>` | `ARRAY(component=STRING)`，**不得**是 `ARRAY(component=UNKNOWN)` | — |
| 任何函数 | `returnType` 键**存在**且非 `UNKNOWN`（除非声明就是 `any`/`unknown`） | — |
| `a?.()` / `a?.[k]` | 先 `BRANCH` 再 `CALL`/`ARRAYLOAD`；空值分支**不发** `CALL`/`ARRAYLOAD`，结果为 `undefined` | `Compare(NE,NULL)` |
| `a &&= b` / `a \|\|= b` / `a ??= b` | 展开成「读 → 短路判定 → 条件写」；右操作数**只在需要时**求值；写回后的左值是表达式结果 | `Truthiness` / `Compare(NE,NULL)` |
| `!x` / `~x` / `-x` | 产生 `UNARYOP`，算子分别是 `NOT` / `BITNOT` / `NEG` | — |
| `++i` / `--i` | 产生 `UNARYOP`，算子是 `PRE_INC` / `PRE_DEC`（**不是** `POST_INC`） | — |
| `void x` | 先求值 `x`（副作用保留），结果为 `undefined` | — |
| `(a, b)` | 两个操作数**都**求值，只有最后一个进结果 | — |
| 静态 `import` / `export` / 再导出 | **零指令**（编译期擦除）；导出名进模块导出表 | — |
| `module.exports = x` | `ARRAYSTORE module["exports"] = x` | — |
| 正则字面量 | 产生 `CONST REGEX` 常量（不是 `HAVOC`） | — |
| JSX 元素 | 产生一次 `CALL`（组件函数），子元素与属性逐个作为实参 | — |
| 类字段 `readonly` / 可见性 / `override` | 指令与普通字段**相同**（编译期约束）；标记进 `fieldAnnotations` | — |
| `implements` | **零指令**；接口名进 `TacTypeDecl.interfaces` | — |
| `super.m()` / `super.prop` | `CALL <基类>.m/n args=[this, …]` / `FIELDLOAD` 且 `ownerType` = **基类名** | — |
| `using r = x` | 作用域的**每一个**出口都有 `CALL <Owner>.dispose/1 args=[r]`；`resourceVars` 非空 | — |
| `var` / `let` / `const` | `var` 的声明在**函数入口**；`let`/`const` 的声明在**声明处** | — |
| `x!`（非空断言） | **零指令**（TS 不插入运行期检查） | — |
| 条件类型 / 映射类型 / 索引访问类型 / 模板字面量类型 / `keyof` / `infer` / DNF / `const` 类型参数 | **零指令**；但类型信息**不得**整体塌成 `UNKNOWN`：`keyof` 的键名集合进 `TacType` 联合成员（具体类型时）、否则进 `TacType.typeLayer`；条件类型的条件与两分支、映射类型的键/值、索引访问的主体/键、模板字面量的模式、`const` 的字面量类型**各自可查** | — |
| 装饰器 `@dec class C`（TS / ArkTS 的普通装饰器） | 产生一次 `CALL <dec>/1 args=[C]`；legacy 语义下返回值**替换** `C`；装饰器链进 `TacTypeDecl.decorators` | — |
| 装饰器 `@dec m()`（成员装饰器） | 产生一次 `CALL <dec>/2 args=[C.prototype, "m"]`；进 `TacMemberRef.decorators` | — |
| `import type` / `export type` | **零指令**；模块边进 `TacTypeDecl.typeOnlyImports`（**不得**进调用图） | — |

### 11.2 必须不变的

| 项 | 依据 |
|---|---|
| `args[0]` 恒为 receiver 槽；callee 键形状 `owner.method/arity` | `plumb-frontend/ts-sidecar/PROTOCOL.md:241-269` |
| 对象（非类实例）的属性访问仍是 `ARRAYLOAD`/`ARRAYSTORE` | `plumb-frontend/ts-sidecar/PROTOCOL.md:271-291` |
| 构造键形状 `Class.<init>/arity` | `TS/translator.mjs:964-967` |
| 未知 owner 用 `<unknown>` 角括号 | `TS/translator.mjs:139` |
| 旧 IR 行为（`domain==null`、`labelRef==null`、`drops` 空） | IR v2 §0.3 / §IR-D 风险条 |
| 同一语料上**非新增能力处**的 finding 数不变 | IR v2 §6 验收口径 |

### 11.3 回归门禁

| 门禁 | 位置 | 内容 |
|---|---|---|
| G-TS | `plumb-frontend/ts-sidecar/smoketest.mjs` | §11.1 逐条断言 |
| G-ArkTS | `plumb-frontend/arkts-sidecar/smoketest.mjs` | 同上 |
| G-wire-TS | `plumb-frontend/src/test/java/org/plumb/tac/frontend/ts/TsSidecarWireTest.java` | 新键映射（`drops`/`guard`/`labelRef`/`domain`/`tryRegions`/`memberKind`） |
| G-e2e-TS | `plumb-frontend/src/test/java/org/plumb/tac/frontend/ts/TsSidecarEndToEndTest.java` | 端到端：函数精度 + 指令序列 |
| G-wire-ArkTS | `plumb-frontend/src/test/java/org/plumb/tac/frontend/arkts/ArktsSidecarWireTest.java` | 同 G-wire-TS |
| G-e2e-ArkTS | `plumb-frontend/src/test/java/org/plumb/tac/frontend/arkts/ArktsSidecarEndToEndTest.java` | 同 G-e2e-TS |
| **G-conform** | 新增 | **两侧对同一 construct id 产出同样的 TAC 形状**（字段级比较，忽略行号）⇒ 防漂移主门禁 |
| G-drift | 新增 | CI 检查两侧无 `protocol.mjs` 副本；`constructs.json` 与 wire 键一致 |
| G-baseline | 新增 | 同语料新旧对比：新增能力处 finding **增加**，其它处 **不变** |
| **G-typelayer** | 新增 | 对 `05` §7.4 的每种类型层构念断言：**零指令** 且 `TacType.typeLayer` 非空（`keyof` 的键名集合、条件类型的两个分支、模板字面量的模式逐项可查）⇒ 防「又塌回 `UNKNOWN`」 |
| **G-decorator** | 新增 | 对 §4.1 断言：普通装饰器产生 `CALL` 且装饰器链非空；**ArkTS 内建装饰器**（`AK/compiler.mjs:53-65` 清单）**不产生** `CALL` ⇒ 防两边漂移 |

---

## 12. 无法完整支持的构念及理由

**结论先行：本次未发现「既无法完整表达、又无法保守建模」的构念。** 下列各项属 P2 允许的三类例外，逐条给出「为什么无法静态表达 / 保守形状 / 精度影响」。

**本表有三种档 —— 先看「档」列，它决定附录里的分档**：

| 档 | 含义 | 附录行怎么指向它 |
|---|---|---|
| **C** | **真例外**：构念**本体**无法完整降级（没有运行时对应物，或那个事实不在源码里） | 附录行**指向**本表的 `TSn` ⇒ 分档判为 **C** |
| **B** | **B 级残留**：构念**本体已完整降级**，缺的只是一个**运行期才定的事实** ⇒ 记 `drop` 记账 | 附录行**不指向**本表，改指**正文**（§4.1 / §5.2 / §7.4）+ `drop` ⇒ 分档判为 **B** |
| **已覆盖** | 已有 **IR 提案**给出解法（`IR-X` / `IR-U` / `IR-AU` / `IR-AV`）⇒ **不是例外**；本表只留条目以便追溯 | 附录行指向**正文** ⇒ 分档判为 **A** |

⇒ **引用方向**：附录行 → 本表（**只对 C 档**）；本表 → 附录行（对 B 档与「已覆盖」档，反向追溯到具体台账行）。
⇒ 这样分档是**机械可判定**的：附录的去向列里**出现 `§12` ⇒ C**；不出现但有 `drop` 记账 ⇒ B；都没有 ⇒ A。

**编号**：`TS1`–`TS10` 是本文档原有的 10 条（顺序与 `02` §3.6 的对账表**逐条对应**，`02` §3.6 按 `05` TS1…TS10 引用）；`TS11`–`TS29` 是补齐 141 行台账缺口时**新增**的 19 条；`TS30` 是本轮（2026-11-17）拆分 `TS3` 得到的 **ArkTS 状态管理装饰器**条目。

**本轮（2026-11-17）这张表的变化**：

- **12 条从「永久例外」变成「已覆盖」**：`TS11`–`TS19`（纯类型层计算）由 **IR-AU** 覆盖，
  `TS8`（`undefined`）由 **IR-X** 覆盖，`TS9`（`bigint`）由 **IR-U** 覆盖；
  `TS3`（TS 普通装饰器）由 **IR-AV** 覆盖，并拆出 `TS30` 留给 ArkTS 的状态管理装饰器。
- **10 条从「永久例外」降为「B 级残留」**：`TS1`（`await` 的时序）、`TS2`（`typeof`）、
  `TS4`（`import.meta` 的属性值）、`TS10`（生成器的驱动方）、`TS20`（`eval(x)` 的返回值）、
  `TS22`（定时器回调的触发时机）、`TS23`（`fetch` 的响应体）、`TS27`（`@Concurrent`/`@Sendable` 的跨线程顺序）、
  `TS28`（`abilityContext` 的属性值）、`TS29`（运行期改写原型）—— 这 10 条的**本体早已完整降级**，
  缺的只是一个运行期才定的事实 ⇒ 记 `drop` 显式记账 ⇒ **不再是例外**。
- **真例外（C 档）只剩 8 条**：`TS5`、`TS6`、`TS7`、`TS21`、`TS24`、`TS25`、`TS26`、`TS30`。


| 编号 | 档 | 构念 | 为什么无法静态表达 | 保守建模的形状 | 精度影响 |
|---|---|---|---|---|---|
| TS1 | B | `await` 的**时序** | 事件循环与微任务队列不在 TAC 表达能力内（TAC 是顺序指令流，`IR/TacInstruction.java:194-242`） | **本体已完整降级**：**值域完整**（`t_a = MOVE p`，解包值与 promise 同源）；残留的「时序」记 `SEMANTICS` 级 drop | 值 / 污点层面**无影响**（污点不依赖时序）；仅并发 / 重入类 checker 看不到 await 边界。落点：§5.2 的「`await p`」行；附录 A 的 `TS #151`、附录 B 的 `AK #134` 行 |
| TS2 | B | `typeof x`（`x` 为 `any`/`unknown`/类型参数） | 结果是运行期才定的 8 个字符串之一 | **本体已完整降级**：类型精确时直接给常量字符串；不精确时建模为未知值 + `VALUE` 级 drop | 该表达式值不可知；类型精确时**无影响**（直接给常量字符串）。落点：§5.2 的「`typeof x`」行；附录 A 的 `TS #121`、附录 B 的 `AK #93` 行 |
| TS3 | 已覆盖 | **TS 普通装饰器**（legacy decorators / TC39 stage-3 标准装饰器） | 装饰器**运行期就是一个被调用的函数** ⇒ 不是「无法静态表达」的构念 | 发真实 `CALL`：类装饰器 `CALL <dec>/1 args=[C]`、成员装饰器 `CALL <dec>/2 args=[C.prototype, "m"]`；装饰器链、阶段顺序与「返回值是否替换被装饰实体」进 `TacTypeDecl.decorators` / `TacMemberRef.decorators`（**IR-AV**） | 装饰器实参的污点**能流进**被装饰实体（今天完全不可见）；legacy 类装饰器可以换掉 `C` ⇒ 字段级规则对被装饰类**不出结论**，而不是给出错误结论。落点：§4.1 |
| TS4 | B | `import.meta` 的**属性值**（`url` / `env` / …） | 属性**值**由**运行期**决定：模块 URL 取决于模块是怎么被加载的（`file://` / `http://` / 打包后的 id），`env` 取决于部署环境 ⇒ 源码里没有这个事实 | 每个模块合成一个 `<module>.__meta/0` 函数：`NEW CLASS<Object>` + 逐属性 `ARRAYSTORE`，属性值建模为**未知值**（§5.3.4） | 读 `import.meta.env.*` 的规则**不出结论**（既不当干净、也不当污点源），而不是出错结论；`import.meta` 的**形状**（有哪些属性）是静态的，已完整表达 |
| TS5 | C | ArkTS `@Builder` / `@BuilderParam` 的**绑定** | 父组件把值写进子组件的 `@BuilderParam`，框架再把它作为 `build()` / builder 方法的实参传下去 —— 这条边**由框架在实例化时建立**，源码里没有 | **不建边**；只把标记记下来：`@Builder` ⇒ `methodAnnotations`，`@BuilderParam` 形参 ⇒ `paramAnnotations`，装饰器实参 ⇒ `annotationValues`（§5.3.6） | 跨组件的**参数级**污点流不可见（组件内部的流仍可见）；**方法本体已完整降级**，精度不受影响 |
| TS6 | C | ArkTS `@Entry` / `@Component` 的**框架实例化** | 路由在运行期实例化这个页面，页面参数由框架给定 ⇒ 谁在什么时候用什么参数创建它，源码里没有 | **不建边**；只把标记记下来：`classAnnotations` / `TacTypeDecl.annotations` 记 `"Component"` / `"Entry"`（§5.3.7） | 框架注入的页面参数被当成**无来源**（既不干净也不污点）⇒ 依赖页面入参的规则**不出结论**；组件本体已完整降级 |
| TS7 | C | `Proxy`/`with`/`eval`/`arguments` | **语言本身无静态语义**：属性解析运行期决定 | 降级为「未知键的容器访问」（`ARRAYLOAD` 的 key 是未知量），与既有命名空间约定一致 | 属性级污点仍在（键未知 ⇒ 匹配任意键），精确到具体属性的规则失效 |
| TS8 | 已覆盖 | `null` 与 `undefined` 的区分 | 常量种类无 `undefined`（`TS/protocol.mjs:75-84`、`AK/protocol.mjs:82-91`）⇒ 现状用 `NULL` 兼两者 | **IR-X** 采纳后 `TacOperand.Const` 增 `UNDEFINED`（wire 侧 `CONST.UNDEFINED`）⇒ 两者分开 | 可选链短路结果不再被空指针检查器当成「可能为 null」⇒ 多报消除。落点：§7.1 的「`undefined`」行、§5.2 的「可选链 `a?.b`」行 |
| TS9 | 已覆盖 | `bigint` 的任意精度 | 类型种类无任意精度整数（`IR/TacType.java:40-63`）⇒ 现状把 `bigint` 映成 `LONG`（`TS/translator.mjs:1495`）是错的 | **IR-U**：`Kind` 增 `BIGINT`，或规定 `domain=Unbounded()` 时以 domain 为准 | 64 位以上的 `bigint` 按无界处理（求解器侧仍受根因三限制）。落点：§7.1 的「`bigint`」行、§7.3 的「数值域落点」表 |
| TS10 | B | 生成器的**驱动方**语义 | 「谁在何时调 `next()`」由外部驱动代码决定 | **⚠ R21 实测：那个前提本身是错的** —— 改之前 TS 侧车的 `yield` **没有任何分支**，**生成器的体根本没有被遍历**（零回执 + 两条 `UNHANDLED_NODE`）⇒ ⇒ **R21 修掉了「体没被遍历」这一半**（操作数就地求值 ⇒ ⇒ 子树进 TAC，`14` §3.44.4）；**状态机仍未建**（它要 `IR-L`：`Effect.SUSPEND` + 恢复点 ⇒ ⇒ **那是改 TAC**）⇒ ⇒ 所以本条的**准确说法**是：**体已完整，协议未建模** | 多消费者交替 resume 的顺序不可见（状态本身共享，**值**正确）。落点：§5.2 的「`yield v`」行；附录 A 的 `TS #152`、附录 B 的 `AK #135` 行 |
| TS11 | 已覆盖 | **条件类型** `T extends U ? X : Y` | **纯类型层计算**：条件类型的取值是**编译期**按类型实参算出来的，不产生任何运行期值 ⇒ 运行期没有对应物；但**判定条件**与**两个分支**是**可静态求出的形状** | **IR-AU**：条件与两个分支进 `TacType.typeLayer`（`CONDITIONAL`）；`T` 具体时按编译期结果**直接取分支类型**（零指令） | **对运行时不产生影响**；但按类型推断的规则现在能判断「这个值在两个分支里分别是哪个类型」。落点：§7.4 |
| TS12 | 已覆盖 | **映射类型** `{[K in keyof T]: T[K]}` | **纯类型层计算**：成员表是编译期按 `keyof T` 展开出来的，运行期不存在这个类型；但**展开后的形状**是可静态求出的 | **IR-AU**：`MAPPED(key = KEYOF(T), valueType = X)`；`T` 具体 ⇒ 逐成员展开进 `TacTypeDecl.fields` | 引擎能判定「这个属性名在这个类型上存在吗」⇒ 属性级规则从「不出结论」变成「有结论」。落点：§7.4 |
| TS13 | 已覆盖 | **索引访问类型** `T[K]`（`T` / `K` 为**类型参数**时） | **纯类型层计算**：取值依赖类型实参 ⇒ 编译期才算得出；但**依赖关系本身**是静态的 | **IR-AU**：`INDEXED_ACCESS(subject = T, key = K)`；两者都具体 ⇒ 直接取该成员类型 | 跨模块的索引访问可以静态求值。落点：§7.4 |
| TS14 | 已覆盖 | **模板字面量类型** `` `${A}-${B}` `` | **纯类型层计算**：具体的字符串取值只在编译期存在；但**取值模式**是静态的 | **IR-AU**：`TEMPLATE_LITERAL(pattern = "…")` ⇒ 模式静态可知 | 路径穿越 / 格式检查能按模式判定字符串性质（如「这个值一定不含 `/`」）。落点：§7.4 |
| TS15 | 已覆盖 | **`keyof` T**（`T` 为**类型参数**时） | **纯类型层计算**：键名集合依赖类型实参；但「键名来自 `keyof T`」这一关系是静态的 | **IR-AU**：`KEYOF(subject = T)`；`T` 具体 ⇒ 键名集合进 `TacType` 的联合成员（**IR-T**） | 属性存在性从「不知道」变成「有结论」。落点：§7.4 |
| TS16 | 已覆盖 | **`infer`** | `infer` 是**类型绑定子**（只在条件类型里出现），本身不是一个类型 ⇒ 无法映射到 `TacType`；但它绑定出的类型参数**带上界**是有位置的 | 绑定出的类型参数进 `TacTypeDecl.typeParameters[]` 的 `constraint`（**IR-Y 的字段扩展**：`constraint` + `defaultType`） | 引擎能判断「这个实参是否满足 `infer` 出来的约束」。落点：§7.4 |
| TS17 | 已覆盖 | **DNF 类型**（析取联合） | **纯类型层计算**：DNF 是编译期的**类型规范化**规则，规范化**过程**在运行期没有对应物 | 规范化后的**成员集合**进 `TacType` 的联合成员列表（**IR-T**）（零指令） | 成员集合本身是完整的 ⇒ 依赖联合成员的规则不受影响。落点：§7.4 |
| TS18 | 已覆盖 | **`const` 类型参数** | **纯类型层计算**：`const` 只影响**实参推导**的字面量宽度 ⇒ 运行期类型不变；但推导结果是**静态可知**的字面量 | 实参推成**字面量类型**（不拓宽）⇒ 直接记该字面量 `TacType`（零指令） | 类型精确度提升。落点：§7.4 |
| TS19 | 已覆盖 | **`import type` / `export type`** | **纯类型级导入 / 导出**：编译后**完全擦除** ⇒ 没有任何运行期实体；但「本模块在**类型图上**依赖了哪个模块」是真实存在的边 | **零指令**（这就是它完整的运行期形态）+ **IR-AU** 的 `TacTypeDecl.typeOnlyImports` 记下那条类型级模块边 | 该模块的**类型级**依赖不参与调用图（类型导入本来就不带值）⇒ 跨模块的**值**流不受影响。落点：§7.4 |
| TS20 | B | **`eval(x)` 的返回值** | 结果是**运行期**由字符串算出来的任意值（根因一） | 参数 `x` 照常求值；返回值建模为**未知值** + `VALUE` 级 drop（§5.2 的「`eval(x)`」行） | 该表达式的值**不可知** ⇒ 既不当干净、也不当污点源；依赖它的规则**不出结论**而不是出错结论；`eval` 的**参数**的污点照常传播 |
| TS21 | C | **`new Function(...)` 构造出的函数体** | 函数体是**运行期**由字符串拼出来的（根因一）⇒ 它的行为在源码里不存在 | `t_f = NEW CLASS<Function>`，**函数体**建模为未知值 + `VALUE` 级 drop（§5.2 的「`new Function(...)`」行）⇒ 对它的调用是**黑盒** | 动态构造的函数里的污点流不可见；构造**实参**的污点照常传播 |
| TS22 | B | **定时器回调**（`setTimeout` / `setInterval`）的**触发时机** | 回调**何时**被调用由**外部世界**（事件循环 / 定时器队列）决定（根因二）⇒ 源码里没有这个时刻 | 回调**本体**完整降级成闭包（§5.2 的「回调形参位置」行）；**时机**不表达 ⇒ 回调与主流程之间的先后关系不可知 | 回调体**内部**的流完全可见；回调体与触发它的代码之间的**顺序**不可见 ⇒ 依赖「回调先于 / 后于某段代码」的规则失效 |
| TS23 | B | **`fetch` 的响应体** | 响应体是**运行期**从网络另一端回来的（根因一）⇒ 它的内容在源码里不存在 | `fetch` 调用照常降级（§5.2 的「`fetch(url)`」行）；**响应体**建模为未知值 + `VALUE` 级 drop | 读响应体的规则**不出结论**；请求**参数**的污点照常传播 |
| TS24 | C | **已知内建的污点源 / sink 分类**（`JSON.parse` / `JSON.stringify` / `localStorage.getItem` / `document.cookie`） | 「哪个调用是源、哪个是 sink」**不是语言语义**，而是**规则集**的判定 ⇒ 同一个调用在不同规则集下身份不同 ⇒ 这个事实**不在源码里** | 调用本身与普通调用**同形**（`CALL <owner>.<m>/n`）；污点分类由**引擎侧**的源 / sink 名表按 `owner.method` 键决定（§5.2 的「已知内建的污点源 / sink」行、§9 的 `GLOBAL_OWNERS` 只给名字不给语义） | 污点分类随**引擎的名表**走，不由前端决定 ⇒ 前端不产生差异；名表缺项时该调用既不当源也不当 sink |
| TS25 | C | ArkTS `@Provide` / `@Consume` 的**状态链接边** | 链接由**框架在实例化时**按 key 建立（谁被注入给谁，运行期才知道）⇒ 源码里没有这条边 | **不建边**；只把标记记下来：`fieldAnnotations` 记 `Provide` / `Consume`，`annotationValues` 记 key（§5.3.6） | 跨组件的**状态级**污点流不可见（组件内部的流仍可见）；属性本体的字段污点仍可见 |
| TS26 | C | ArkTS `@Observed` / `@ObjectLink` 的**观察边** | 观察关系是**运行期**建立的（谁被观察、什么时候变化）⇒ 源码里没有这条边 | **不建边**；只把标记记下来：`fieldAnnotations` 记 `Observed` / `ObjectLink`（§5.3.6） | 经由观察边传播的污点不可见；被观察对象**内部**的流仍可见 |
| TS27 | B | ArkTS `@Concurrent` / `@Sendable` 的**跨线程执行顺序** | 线程交错由**调度器**决定（根因二）⇒ 源码里没有这个顺序 | 标记进 `methodAnnotations`，并发标记进指令的 `effect` 集合（`CONCURRENT`，§5.3.7）；**顺序**不表达 | 跨线程的顺序不可知 ⇒ 依赖 happens-before 的 checker 失效；线程内的流完全可见 |
| TS28 | B | ArkTS `abilityContext` 的**属性值** | 属性值由**框架在运行期注入**（依赖哪个 Ability、哪个上下文）⇒ 源码里没有这个事实 | 零指令（它是一个全局对象）；属性值建模为**未知值** + `VALUE` 级 drop（§4 的「ArkTS 运行时上下文对象」行） | 读 `abilityContext.*` 的规则**不出结论**；对象**存在**这件事是静态的，已完整表达 |
| TS29 | B | **运行期改写原型**（`X.prototype.m = …`） | 原型链的**静态**部分（类的 `extends` 链）可静态求出，但「谁在运行期往原型上挂了一个方法」是**运行期**行为 ⇒ 源码里没有这个事实 | 静态原型链完整表达（§4 的「**类继承 `extends`**」行的「原型链方法查找」）；运行期的原型改写建模为未知值 + `VALUE` 级 drop | 经由**运行期**原型改写的方法解析不可见（按基类链的静态查找不受影响） |
| TS30 | C | **ArkTS 状态管理装饰器**（`@State`/`@Prop`/`@Local`/`@Link`/`@Param`/`@Provide`/`@Consume`/`@Require`/`@Once`/`@Watch`/`@Observed`/`@ObjectLink`/`@Trace`/`@Event`/`@Monitor`/`@Reusable`/`@MonoStorage`/`@Type`） | 由**编译器**处理（ArkTS 内建清单 `AK/compiler.mjs:53-65`）：编译器为带 `@State` 的属性**生成** getter / setter，装饰器表达式本身**不在运行期出现** ⇒ 没有对应的运行期函数 ⇒ 不是 §4.1 那种「运行期被调用的装饰器」 | **不发** `CALL`；只把标记记下来：`fieldAnnotations` 记装饰器名、`annotationValues` 记实参；**属性本身**照常降级为 `FIELDSTORE` | 框架注入的行为不可见（**属性本身的字段污点仍可见**）。落点：§4.1 的「与 ArkTS 框架装饰器的分界」表；附录 B 的 `AK #115` / `AK #150` 行 |

---

## 13. 风险与未决项

### 13.1 风险

| 编号 | 风险 | 证据 / 说明 | 缓解 |
|---|---|---|---|
| K1 | 类字段改用 `FIELDLOAD`/`FIELDSTORE` 会改变既有 sink 匹配（命名空间约定下 `obj.userId` 是 `ARRAYLOAD`） | `plumb-frontend/ts-sidecar/PROTOCOL.md:271-291` | sink catalogue 同步；门禁：同语料 sink 命中数不变 |
| K2 | **`for..of` 绑元素后污点来源改变**：今天循环变量继承「可迭代对象」的污点，改后继承「元素」的污点 | `TS/translator.mjs:721-735`、`AK/translator.mjs:822-834` | 若引擎不从「被污染容器」传播到「元素读出」，TS/ArkTS 的污点 finding 会**减少** ⇒ 必须先在 `plumb-dfa` 侧确认容器→元素传播，否则这是一次**倒退**。**本方案第一风险** |
| K3 | 短路展开成 `BRANCH` 链后指令数暴涨（每个 `&&` 约 4 条） | — | 设函数指令数上限，超限记 `STRUCTURE` 级 drop；测量真实语料膨胀比 |
| K4 | 闭包用「闭包对象 + `FIELDLOAD`」后，调用图需认识「receiver 是闭包对象」 | owner 解析 `TS/translator.mjs:1387-1426` | `CallGraphBuilder` 与 `VtaCallGraphStrategy` 需能解析这种 callee |
| K5 | switch 分派链使 case 判定变成 O(n) 次比较 | — | 编译期常量 case 可折叠为 `Membership`；`default` 用跳板 |
| K6 | `undefined` 与 `null` 合并会多报空指针 | §12 | IR-X 采纳后消除 |
| K7 | 两侧各写各的 drops/精度口径 ⇒ §IR-B 的「统一推导」失效 | — | 推导函数放 IR 侧（§IR-B 生产消费方表已指定 `TacFunction.derivePrecision`），前端只报 drops |
| K8 | 共享 `protocol.mjs` 被误解成「共享 AST」 | `AK/protocol.mjs:1-10` | 共享文件**禁止出现任何 AST/编译器相关代码**，由 G-drift 门禁检查 |

### 13.2 待拍板

| 编号 | 事项 | 选项 | 建议 |
|---|---|---|---|
| Q1 | IR-T 联合类型形态 | (a) `Kind.UNION`（波及按 `TacType.Kind` 分派的 **9 处 `switch (t.kind())`**）；(b) 可选字段 `unionMembers` | (b)：爆炸半径 0；缺点是 `kind` 仍 `UNKNOWN`，消费方须显式读 `unionMembers` |
| Q2 | IR-U `bigint` 形态 | (a) `Kind.BIGINT`；(b) `LONG`+`domain=Unbounded()` 且 domain 优先 | 长期 (a)，短期 (b)；需同步改 `BoundaryConstraintChecker` 的区间推断（§IR-D 生产消费方表） |
| Q3 | `tryRegions.catchTypes` 对无类型 catch 的表示 | `"*"` / `""` / `"java.lang.Throwable"` | `"*"`（语言中立；不得用 Java 类名，那会把一种语言的约定塞进共享 wire） |
| Q4 | `labelRef.scopeId` 编号规则 | 作用域深度 / 单调递增 id | 单调递增 id（嵌套层号在函数内可能重名） |
| Q5 | `guard` 的极性表示 | 只描述真分支（§IR-C 已定） | 保持现状；假分支是它的否定，**不新增极性字段** |
| Q6 | `returnType` 修好后，`any` 函数的返回类型 | `UNKNOWN` / `CLASS<Object>` | `UNKNOWN`（保持现状语义），且**不记 drop**（`any` 是用户声明，不是降级） |
| Q7 | 迭代协议 callee 名（`Iterator.next`）是否进 sink catalogue | 进 / 不进 | 不进（不是业务 sink），但必须在 `constructs.json` 登记，避免被误配 |

---

## 14. 本轮落地（2026-11-18）：探针实测出来的五处，外加一处 Java 侧

这一轮**没有**先读这份设计，而是先写探针：直接驱动 sidecar 走它的 wire 协议，把构造喂进去，把 TAC 打出来。**这份设计早就写明了这些构念该长什么样，代码却不是那样** —— 而且缺的那部分**在扫描里完全看不出来**：TAC 格式良好、标签全解析、精度仍是 `SOURCE_FULL`、扫描报告成功。

| 构念 | 原状 | 现在 |
|---|---|---|
| `if` / `while` 的**条件** | **条件根本没有被求值**：比较没有指令、分支**没有主语**、guard 没有 operand ⇒ **路径条件**与**条件的污点**全丢 | 条件按 `conditionOf(node)` 读 ⇒ 比较有指令、分支有主语、guard 有 operand |
| `continue <label>` | 跳到**最内层**的循环（扫作用域的方向反了）⇒ 控制流是错的 | 跳到**标签所标注的那个循环**（标签之后的**第一个** `LOOP`） |
| `catch ({ message })` | 捕获值被**整个丢掉**，模式里的名字**从未被赋值**，且**没有 drop** | `CATCH_HANDLER` 落一个临时量 + 用**已经存在**的 `bindPattern` 把模式里的名字绑上去 |
| `{ ...rec }` | 展开被跳过 ⇒ 新对象**只有显式写出的属性**，源对象的污点全丢 | 建模成 `UNINTERPRETED Object.assign/2` —— **故意**用 `UNINTERPRETED`：引擎对**认不出来的被调方**按「保留实参的污点」处理（`LanguageSemantics.preservesTaint` 对未知返回 true），写成普通 `CALL` 则因为没有 callee summary 而**不会**染色 ⇒ 等于没修 |
| `rec.f?.()` | 调用的**接收者是函数值** ⇒ 看起来像是「函数自己的方法」 | 接收者是**函数被读出来的那个对象**（`this` 就是它）；**空值检查的对象仍然是函数**（可能缺的是函数） |

**根因是编译器家族，不是翻译逻辑**：`typescript/unstable/ast`（TS 7.0.2）与 `ohos-typescript@4.9.5` 里，`if` / `while` / `do` 的条件字段叫 **`expression`**；而 `for` 与条件表达式叫 **`condition`**。翻译器读的是 `.condition` ⇒ 对 `if` / `while` 读到的是 **undefined** ⇒ `lowerExpression(undefined)` 返回 undefined ⇒ 分支**没有主语**。

⇒ 因此修法是**读两个并取先到的那个**（`node?.condition ?? node?.expression`），**不是**改成某一个名字 —— 换一族编译器就会再坏一次。这个 helper 的注释里写明了这一点。

**Java 侧的一处**：`SidecarTacMapper.toLabelRef` 用 `getString("scopeId")` 读一个**数字**字段 ⇒ 拿到 null；而结构类标签（`LOOP_HEAD` / `LOOP_END` / `SWITCH_CASE` / `SWITCH_END` / `BLOCK` / `CATCH` / `FINALLY_ENTRY`）的 `name` 本来就是 null（结构类标签没有自己的名字）⇒ **整个 labelRef 被丢弃** ⇒ **所有 sidecar** 的「哪个标签是循环头 / 循环尾 / case 体」这一层事实全部消失 ⇒ 引擎无法判断「这条分支是不是离开了它所在的循环」。

**这一轮刻意没改的**（逐条理由见 `14` §3.8.7）：默认参数（两条修法都要先定口径）、**两条协议调用的 `args[0]`**（**改了会把「多报」变成「漏报」**：引擎的 `propagateIteratorTaint` 正是从 `args[0]` 取被迭代的数组）、基类构造器实参、getter、IIFE。

**验收**：新增两个测试类（TS 6 例 / ArkTS 4 例），只断言**形状**、不钉指令位置；并且**做过变异测试** —— 把两个 sidecar 换回仓库里的旧版本再跑 ⇒ **10 个用例全红**，每个失败都指向对应的缺陷；恢复后逐字节校验 sha256 一致。

---

## 附录 A. TypeScript / JavaScript 审计台账 A.1–A.21 逐条去向（192 行）

**审计来源**：`D:\work\tmp\audit\08-typescript.md` 的 `## A. Coverage ledger` 节（子小节 A.1–A.21）。
**本附录覆盖的是审计 A 节（完整台账），不是 B 节（丢弃清单）** —— 与 `06` 文档附录 A / B 覆盖 PHP / Python 完整 A 台账的口径一致，便于五个语言面横向比较覆盖数字。
**编号**：审计 A 节各表**没有** `#` 列 ⇒ 本附录按 **A 节出现顺序**自加连续编号（`TS #1`–`TS #192`），只用于本附录内部索引，不改写审计的行。
**「审计状态」列**沿用审计自己的状态词（`KEPT` / `DEGRADED` / `DROPPED-SILENT` / `DROPPED-NOTED`；审计另有 `NOT FOUND` 与 `N/A` 两个非构念状态词，也按原文保留），**不改写审计的结论**。
**「审计证据」列**是审计原文 `file:line` 的**转录**，**不重新核对行号**（审计的行号会漂）。
**「本方案的去向」列**给出本文档里**真实存在**的落点：**章节号 + 表行原文片段**（片段可在本文件内直接 grep 到）。
**`KEPT` 行**的去向列写「保持现状（无需改动）」；**`N/A` 行**写「不适用（审计已判 N/A）」。
**本轮已把原先带 `⚠ 未覆盖 —— ` 的行全部补齐** ⇒ 台账行里**没有一行**是「本文档没有落点」的；逐条的补齐清单见本节末尾的「原『⚠ 未覆盖清单』—— 已全部补齐」。
**审计状态里的 `· NOT FOUND`**：审计 §E 把它定义为 DROPPED 行的**子集**（含义是「本面**没有任何**构念专属代码」），不是第五种丢弃口径 ⇒ 本附录的状态列取**前一个状态词**，并在该行末尾以 `〔审计标 NOT FOUND〕` 标出；状态列本身就是 `NOT FOUND` 的那一行（协议能力行）照原文保留。

### A.1 Type system

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #1 | primitives (string/number/boolean/bigint/void/null) | KEPT | translator.mjs:1490-1505 | 保持现状（无需改动） |
| TS #2 | literal types (`"a"`, `42`) | DEGRADED | translator.mjs:1490-1505 | §7.1 类型面表「字面量 `"a"`/`1`/`true`」行（值进 `Const`，类型仍是基础类型）+ §10 改动清单「`mapType`（`:1490-1505`）」行 |
| TS #3 | union types | DEGRADED | translator.mjs:1490-1505 | §7.1 类型面表「一般联合 `string \| number`」行（联合成员列表）+ §8 IR 需求「**IR-T**」行 + §9 wire 表「`unionMembers`」行 |
| TS #4 | intersection types | DEGRADED | translator.mjs:1490-1505 | §7.1 类型面表「交叉 `A & B`」行（取首成员并记 `STRUCTURE` 级 drop） |
| TS #5 | conditional types | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**条件类型** `T extends U ? X : Y`**」行（**IR-AU**：条件与两个分支进 `TacType.typeLayer` 的 `CONDITIONAL`；`T` 具体时按编译期结果直接取分支类型；**零指令**） |
| TS #6 | mapped types | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**映射类型** `{[K in keyof T]: T[K]}`**」行（**IR-AU**：`MAPPED(key = KEYOF(T), valueType = X)`；`T` 具体 ⇒ 逐成员展开进 `TacTypeDecl.fields`；**零指令**） |
| TS #7 | indexed-access types | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**索引访问类型** `T[K]`**」行（**IR-AU**：`INDEXED_ACCESS(subject = T, key = K)`；`T`/`K` 都具体 ⇒ 直接取该成员类型；**零指令**） |
| TS #8 | template-literal types | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**模板字面量类型** `` `${A}-${B}` ``**」行（**IR-AU**：`TEMPLATE_LITERAL(pattern = …)` ⇒ 取值模式静态可知；**零指令**） |
| TS #9 | tuple types | DROPPED-NOTED | translator.mjs:608 | §7.1 类型面表「元组 `[A,B]`」行（逐元素类型无法逐个表达 ⇒ 记 `STRUCTURE` 级 drop，元素类型取首个）+ §10 改动清单「`mapType`（`:1490-1505`）」行 |
| TS #10 | rest types | DROPPED-NOTED | translator.mjs:608, 536-543 | §7.1 类型面表「元组 `[A,B]`」行（补「元组里的剩余元素」子句：长度不定 ⇒ 同一条 `STRUCTURE` 级 drop，元素类型仍取首个） |
| TS #11 | optional properties / parameters | DEGRADED | translator.mjs:536-543, 1469-1477 | §7.1 类型面表「可选属性 / 可选形参」行（`nullable` 侧 + 「可缺省」的 arity 口径）+ §4 声明面表「可选形参」行 |
| TS #12 | `keyof` | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**`keyof` T**」行（**IR-AU**：`KEYOF(subject = T)`；`T` 具体 ⇒ 键名集合进 `TacType` 的联合成员，**IR-T**；**零指令**） |
| TS #13 | type-level `typeof` | DROPPED-NOTED | translator.mjs:608 | §7.1 类型面表「类型查询」行（类型可静态求出 ⇒ 记该 `TacType`；不产生运行期值 ⇒ 零指令） |
| TS #14 | `infer` | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**`infer`**」行（绑定出的类型参数进 `TacTypeDecl.typeParameters[]` 的 `constraint`，**IR-Y 的字段扩展**；**零指令**） |
| TS #15 | generics + constraints + defaults | DEGRADED | translator.mjs:545-552 | §7.1 类型面表「类型参数 `T`」行（约束与默认实参进 `TacTypeDecl.typeParameters`）+ §8.1 的「**IR-Y 的字段扩展**」行 |
| TS #16 | `readonly` | DEGRADED | translator.mjs:1490-1505 | §4 声明面表「`readonly` 属性」行（只读标记进 `TacTypeDecl.fields`）+ §7.1 类型面表「只读类型」行 |
| TS #17 | `unknown` | KEPT | translator.mjs:1490-1505 | 保持现状（无需改动） |
| TS #18 | `never` | DEGRADED | translator.mjs:1490-1505 | §7.1 类型面表「`never`」行（`VOID`；无值；IR 无 `NEVER`） |
| TS #19 | `any` | KEPT | translator.mjs:1490-1505 | 保持现状（无需改动） |
| TS #20 | type predicates (`x is T`) | DEGRADED | translator.mjs:1373-1381 | §7.2 收窄表「类型谓词」行（守卫 `TypeTest(x, CLASS<T>, false)`；分支内收窄 ⇒ **IR-Z**） |
| TS #21 | assertion functions (`asserts x is T`) | DEGRADED | translator.mjs:1373-1381 | §7.2 收窄表「断言函数」行（抛错路径 `THROW` 带 `ctorType = "ReferenceError"`；调用点之后的收窄用 `TypeTest` ⇒ **IR-Z**） |
| TS #22 | `abstract` members | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「抽象方法」行（无体 ⇒ 不生成函数；进 `TacTypeDecl.members`，类标 `isAbstract`）+ §4 末段「**必须补上的 `else`**」段；〔审计标 NOT FOUND〕 |
| TS #23 | `unique symbol` | DEGRADED | translator.mjs:1496 | §7.1 类型面表「`symbol`」行（`UNKNOWN`；符号身份不可静态判定） |
| TS #24 | `import type` | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**`import type` / `export type`**」行（**IR-AU**：**零指令** + 类型图上的模块边进 `TacTypeDecl.typeOnlyImports`） |
| TS #25 | `satisfies` (type-level) | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「`as`/`satisfies`/`<T>`」行（运行期零行为 ⇒ 不发指令；语义是类型收窄）+ §7.1 类型面表「`as`/`satisfies`/`<T>`」行 + §7.2 收窄小节 |
| TS #26 | `as const` | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「`as`/`satisfies`/`<T>`」行（运行期零行为；语义是类型收窄）；字面量侧的落点是 §7.1 类型面表「字面量 `"a"`/`1`/`true`」行 |
| TS #27 | `const` type parameters | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**`const` 类型参数**」行（实参推成**字面量类型**、不拓宽 ⇒ 直接记该字面量 `TacType`；**零指令**） |
| TS #28 | DNF types | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**DNF 类型**」行（规范化后的**成员集合**进 `TacType` 的联合成员列表，**IR-T**；**零指令**） |

### A.2 Type assertions

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #29 | `as T` | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「`as`/`satisfies`/`<T>`」行 + §7.1 类型面表「`as`/`satisfies`/`<T>`」行 + §7.2 收窄小节（分支内类型改写 ⇒ **IR-Z**） |
| TS #30 | `satisfies T` | DROPPED-NOTED | translator.mjs:843-844 | 同 TS #29 的落点：§5.2 表达式面表「`as`/`satisfies`/`<T>`」行 + §7.1 类型面表「`as`/`satisfies`/`<T>`」行 + §7.2 收窄小节 |
| TS #31 | angle-bracket `<T>expr` | DROPPED-NOTED | translator.mjs:843-844 | 同 TS #29 的落点：§5.2 表达式面表「`as`/`satisfies`/`<T>`」行 + §7.1 类型面表「`as`/`satisfies`/`<T>`」行 + §7.2 收窄小节 |
| TS #32 | non-null assertion `expr!` | DEGRADED | translator.mjs:800-802 | §7.2 收窄表「非空断言」行（运行期零指令；非空事实落 `TacType.nullable=false`） |

### A.3 Interfaces

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #33 | declaration merging | DROPPED-NOTED | translator.mjs:608 | §7.1 类型面表「声明合并」行（同名接口的成员取并集进同一个 `TacTypeDecl`） |
| TS #34 | `extends` | DROPPED-NOTED | translator.mjs:608 | §7.1 类型面表「接口的 `extends`」行（父接口进 `TacTypeDecl.superType`；接口继承不产生构造器调用） |
| TS #35 | index signatures `[k: string]: T` | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「索引签名 `[k: string]: T`」行（运行期无行为 ⇒ 零指令；进 `TacTypeDecl`）+ §8 IR 需求「**IR-Y**」行 + §10 改动清单「`lowerClass`（`:470-481`）」行 |
| TS #36 | optional / readonly properties | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「`readonly` 属性」行（只读）+ §7.1 类型面表「可选属性 / 可选形参」行（可选） |
| TS #37 | call signatures | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「接口 call signature」行（无体 ⇒ 不生成函数；进 `TacTypeDecl.members`） |
| TS #38 | construct signatures | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「接口 construct signature」行（无体 ⇒ 进 `TacTypeDecl.members`） |
| TS #39 | method signatures | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「接口方法签名」行（无体 ⇒ 进 `TacTypeDecl.members`） |
| TS #40 | generic interfaces | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「接口 call signature」行 + §8.1 的「**IR-Y 的字段扩展**」行（接口的类型参数进 `TacTypeDecl.typeParameters`，带约束与默认实参） |

### A.4 Classes

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #41 | methods | KEPT | translator.mjs:476-478 | 保持现状（无需改动） |
| TS #42 | property declarations with initialisers | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「类字段（有初始化器）」行（合成 `FIELD_INITIALIZER` 函数；`FIELDSTORE this.x = <init>`；`ownerType` = 类名）+ §4 末段「**必须补上的 `else`**」段 + §10 改动清单「`lowerClass`（`:470-481`）」行；〔审计标 NOT FOUND〕 |
| TS #43 | `declare` properties | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「`declare` 声明」行（运行期无实体 ⇒ 不生成函数；进 `TacTypeDecl`）+ §4 末段「**必须补上的 `else`**」段；〔审计标 NOT FOUND〕 |
| TS #44 | `abstract` properties | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「抽象方法」行（无体 ⇒ 不生成函数；进 `TacTypeDecl.members`，类标 `isAbstract`）+ §4 末段「**必须补上的 `else`**」段；〔审计标 NOT FOUND〕 |
| TS #45 | `readonly` properties | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「`readonly` 属性」行（只读标记进 `TacTypeDecl.fields`） |
| TS #46 | `public` / `private` / `protected` properties | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「可见性修饰符」行（可见性进 `fieldAnnotations`；运行期无行为 ⇒ 指令不变） |
| TS #47 | `static` properties | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「静态字段」行（receiver 是类对象 `t_cls_<Name>`，类型 `CLASS<Name>` ⇒ `FIELDSTORE t_cls_Name.x = <init>`）+ §10 改动清单「`lowerClass`（`:470-481`）」行；〔审计标 NOT FOUND〕 |
| TS #48 | `override` properties | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「`override` 属性」行（标记进 `fieldAnnotations`；运行期零指令） |
| TS #49 | `accessor` fields | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「`accessor` 字段」行（合成 getter + setter + 后备字段） |
| TS #50 | **constructors** | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「构造函数」行（独立函数 `<init>`；`super(...)` ⇒ `CALL mod.Base.<init>/n`）+ §4 末段「**必须补上的 `else`**」段 + §11.1 验收表「构造器/getter/setter/静态块」行；〔审计标 NOT FOUND〕 |
| TS #51 | constructor parameter properties (`constructor(private x: T)`) | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「构造器参数属性」行（构造器体内 `FIELDSTORE this.<param>`） |
| TS #52 | constructor overloads | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「重载声明」行（只有签名无体 ⇒ 不生成函数；进 `TacTypeDecl.members`）+ §4 末段「**必须补上的 `else`**」段；〔审计标 NOT FOUND〕 |
| TS #53 | getters | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「getter `get x()`」行（独立函数，owner = 所属类；体内 `this.x` ⇒ `FIELDLOAD`；调用点 `obj.x` ⇒ `CALL <Owner>.x/0`）+ §5.2 表达式面表「getter 的调用 `obj.x`（x 是 getter）」行；〔审计标 NOT FOUND〕 |
| TS #54 | setters | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「setter `set x(v)`」行（独立函数；体内 `this.x = v` ⇒ `FIELDSTORE`；调用点 ⇒ `CALL <Owner>.x/1`）；〔审计标 NOT FOUND〕 |
| TS #55 | `implements` | DEGRADED | translator.mjs:470-481 | §4 声明面表「`implements`」行（接口名进 `TacTypeDecl.interfaces`）+ §9 wire 表「`types`」行 |
| TS #56 | `extends` | DEGRADED | translator.mjs:470-481 | §4 声明面表「**类继承 `extends`**」行（派生类 `<init>` 首条是 `CALL <基类>.<init>/n`；父类名进 `TacTypeDecl.superType`）+ §5.3.9 小节「`extends`（类继承）」+ §7.1 类型面表「类的父类（`extends`）」行 |
| TS #57 | abstract members | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「抽象方法」行（无体 ⇒ 不生成函数；进 `TacTypeDecl.members`，类标 `isAbstract`）+ §4 末段「**必须补上的 `else`**」段；〔审计标 NOT FOUND〕 |
| TS #58 | `#private` fields | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「`#private` 字段」行（字段名保留 `#` 前缀 ⇒ 与 `x` 是两个字段） |
| TS #59 | static initialisation blocks | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「静态块 `static {…}`」行（合成函数；类初始化时 `CALL mod.Class.<clinit>/0`）+ §4 末段「**必须补上的 `else`**」段；〔审计标 NOT FOUND〕 |
| TS #60 | class index signatures | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4 声明面表「索引签名 `[k: string]: T`」行 + §8 IR 需求「**IR-Y**」行；〔审计标 NOT FOUND〕 |
| TS #61 | class expressions (anonymous classes) | DROPPED-NOTED | translator.mjs:833-836 | §5.2 表达式面表「类表达式 `const C = class {…}`」行（类即构造器 ⇒ `t_c = NEW CLASS<C>`；成员按 §4 降级为独立函数） |
| TS #62 | `declare` class | DEGRADED | translator.mjs:476-478 | §4 声明面表「`declare` 声明」行（运行期无实体 ⇒ 不生成函数；进 `TacTypeDecl`） |

### A.5 Enums

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #63 | numeric enums | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「枚举 `enum E {A,B}`」行（合成函数：`NEW CLASS<E>` + `ARRAYSTORE t_e["A"]=0`、`["B"]=1`、反向映射）+ §7.1 类型面表「枚举 `E`」行 |
| TS #64 | string enums | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「字符串枚举」行（成员值是字符串常量；不生成反向映射） |
| TS #65 | heterogeneous enums | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「混合枚举」行（各成员各自的值类型；反向映射只对数值成员生成） |
| TS #66 | `const enum` | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「`const enum`」行（编译期内联 ⇒ 运行期没有枚举对象 ⇒ 零指令） |
| TS #67 | computed members | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「枚举计算成员」行（编译期常量求值；求不出 ⇒ 未知值 + `VALUE` 级 drop） |
| TS #68 | reverse mapping | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「枚举 `enum E {A,B}`」行（反向映射 `["0"]=t_e["A"]`）+ §7.1 类型面表「枚举 `E`」行（底层是 number，成员值另存） |

### A.6 Functions

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #69 | function declarations | KEPT | translator.mjs:272-274, 457-467 | 保持现状（无需改动） |
| TS #70 | function expressions | DEGRADED | translator.mjs:837-842 | §5.2 表达式面表「箭头/函数表达式（闭包）」行 + §5.2 小节「闭包细节」 |
| TS #71 | arrow functions | DEGRADED | translator.mjs:837-842, 490-507 | §5.2 表达式面表「箭头/函数表达式（闭包）」行 + §5.2 小节「闭包细节」+ §13.1 风险表「K4」行（调用图需认识「receiver 是闭包对象」） |
| TS #72 | async arrow functions | DEGRADED | translator.mjs:490-507 | §5.2 的「**`async` 函数 / `async` 箭头**」行（返回值包成 Promise 的形状完整）+ §5.2 的「**`await p`**」行；**本体已完整降级**：残留的「**微任务时序**」记 `SEMANTICS` 级 drop |
| TS #73 | overloads | DEGRADED | translator.mjs:457-467 | §4 声明面表「重载声明」行（只有签名无体 ⇒ 不生成函数；进 `TacTypeDecl.members`）+ §4 末段「**必须补上的 `else`**」段 |
| TS #74 | generics | DEGRADED | translator.mjs:545-552 | §7.1 类型面表「类型参数 `T`」行 + §8 IR 需求「**IR-Y**」行 |
| TS #75 | rest parameters (`...xs`) | DEGRADED | translator.mjs:536-543 | §4 声明面表「剩余形参」行（形参类型 `ARRAY`）+ §8.1 的「**IR-W 的字段扩展**」行 |
| TS #76 | optional parameters | DEGRADED | translator.mjs:536-543 | §4 声明面表「可选形参」行（`nullable` + 「可缺省」的 arity 口径） |
| TS #77 | `this` parameter | DEGRADED | translator.mjs:536-543 | §4 声明面表「`this` 形参」行（只进类型、不进签名、不占参数位） |
| TS #78 | **default parameter values** | DROPPED-SILENT · NOT FOUND | translator.mjs:536-543 | §4 声明面表「默认参数值」行（函数体开头补 `MOVE x = <default>`；默认值里的调用照常降级） |
| TS #79 | **destructured parameters** | DROPPED-SILENT · NOT FOUND | translator.mjs:539 | §4 声明面表「**参数解构** `f({a,b}: Props)`」行（造合成参数 `p`；函数体开头 `t_a = ARRAYLOAD p["a"]; MOVE a = t_a`；callee 签名仍 `mod.f(Props)`）；〔审计标 NOT FOUND〕 |
| TS #80 | IIFE | DEGRADED | translator.mjs:837-842 | §5.2 表达式面表「箭头/函数表达式（闭包）」行 + §5.2 小节「闭包细节」；命名空间形态的 IIFE 见 §4 声明面表「命名空间 `namespace N {…}`」行 |

### A.7 Destructuring

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #81 | object patterns | DROPPED-SILENT · NOT FOUND | translator.mjs:611-629 | §4 声明面表「变量解构 `const {a,b}=obj`」行（`t_a = ARRAYLOAD obj["a"]; MOVE a = t_a`）；〔审计标 NOT FOUND〕 |
| TS #82 | array patterns | DROPPED-SILENT · NOT FOUND | translator.mjs:614 | §4 声明面表「数组解构 `const [a,b]=arr`」行（`t_a = ARRAYLOAD arr[0]; MOVE a = t_a`）；〔审计标 NOT FOUND〕 |
| TS #83 | default values in patterns | DROPPED-SILENT · NOT FOUND | translator.mjs:614 | §4 声明面表「模式里的默认值」行（`BRANCH` + 守卫 `Compare(t_a, NE, NULL)`） |
| TS #84 | rest in patterns | DROPPED-SILENT · NOT FOUND | translator.mjs:614 | §4 声明面表「剩余元素 `const {a,...rest}=obj`」行（`t_rest = NEW CLASS<Object>`；用 `Object.keys(obj)` 键循环逐键判断）；〔审计标 NOT FOUND〕 |
| TS #85 | nested patterns | DROPPED-SILENT · NOT FOUND | translator.mjs:614 | §4 声明面表「嵌套模式」行（递归展开：先取外层键，再取内层键） |
| TS #86 | parameter destructuring | DROPPED-SILENT · NOT FOUND | translator.mjs:539 | §4 声明面表「**参数解构** `f({a,b}: Props)`」行；〔审计标 NOT FOUND〕 |
| TS #87 | assignment destructuring (`({j} = o)`) | DROPPED-NOTED | translator.mjs:1102 | §4 声明面表「赋值式解构」行（不产生新变量 ⇒ 对已存在的变量赋值） |

### A.8 Spread / rest

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #88 | array spread (`[...a]`) | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「展开」行（数组/对象字面量内展开 ⇒ `NEWARRAY` + 逐元素 `ARRAYSTORE`；个数已知时） |
| TS #89 | **object spread (`{...o}`)** | DROPPED-SILENT · NOT FOUND | translator.mjs:1185-1186 | §5.2 表达式面表「展开」行（对象字面量展开 `{...o}` ⇒ 逐键 `ARRAYSTORE`） |
| TS #90 | call spread (`f(...a)`) | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「展开」行（调用参数展开的参数个数运行期才定 ⇒ **IR-W**）+ §8 IR 需求「**IR-W**」行 |
| TS #91 | rest in parameters | DEGRADED | translator.mjs:536-543 | §4 声明面表「剩余形参」行 + §8.1 的「**IR-W 的字段扩展**」行 |
| TS #92 | rest in an array literal/pattern | DROPPED-NOTED | translator.mjs:843-844 | §4 声明面表「数组剩余元素」行（`NEWARRAY` + 逐元素 `ARRAYSTORE`） |
| TS #93 | rest in an object pattern | DROPPED-SILENT · NOT FOUND | translator.mjs:1185-1186 | §4 声明面表「剩余元素 `const {a,...rest}=obj`」行；〔审计标 NOT FOUND〕 |

### A.9 Control flow

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #94 | `if` / `else` | KEPT | translator.mjs:637-655 | 保持现状（无需改动） |
| TS #95 | ternary `? :` | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「三元 `c?a:b`」行（`BRANCH` + 两条 `MOVE` 支）+ §6.2 小节「三元与可选链」+ §11.1 验收表「`c ? a : b`」行 |
| TS #96 | `switch` | DROPPED-NOTED | translator.mjs:608 | §6.6 小节「`switch`（真选择 + fall-through）」+ §10 改动清单「`lowerSwitch`（`:897-919`）」行 + §11.1 验收表「`switch`」行 |
| TS #97 | `for` | DEGRADED | translator.mjs:677-710 | §6.3 小节「循环」（`for (init; c; step)` 模板；`labelRef`：`LOOP_HEAD` / `LOOP_BODY_END` / `LOOP_END`） |
| TS #98 | `for` initializer (`for (let i = 0; …)`) | DROPPED-NOTED | translator.mjs:679-683 | §6.3 小节「循环」的「**`for` 的初始化器与步进**」段（`DECLARE i : INT` + `MOVE i = 0`；守卫判据从 `isVariableStatement` 改成 `VariableDeclarationList`） |
| TS #99 | `for … in` | DROPPED-NOTED | translator.mjs:608 | §6.5 小节「`for..in`（键）」+ §10 改动清单「`lowerForOf`（`:822-834`）/`lowerForIn`（`:856-868`）」行 + §11.1 验收表「`for..in`」行 |
| TS #100 | `for … of` | DEGRADED | translator.mjs:721-752 | §6.4 小节「`for..of`（迭代协议）」+ §10 改动清单「`lowerForOf`（`:822-834`）/`lowerForIn`（`:856-868`）」行 + §11.1 验收表「`for..of`」行 |
| TS #101 | `for … of` loop-variable binding | DROPPED-SILENT · NOT FOUND | translator.mjs:723-735 | §6.4 小节「`for..of`（迭代协议）」（必须绑元素，不能绑可迭代对象；`t_item = ARRAYLOAD t_step["value"]; MOVE <loopVar> = t_item`）+ §13.1 风险表「K2」行；〔审计标 NOT FOUND〕 |
| TS #102 | `while` | KEPT | translator.mjs:657-675 | 保持现状（无需改动） |
| TS #103 | `do … while` | DROPPED-NOTED | translator.mjs:608 | §6.3 小节「循环」（`do { b } while (c)` 模板；`continue` 目标 = `L_COND`）+ §10 改动清单「`lowerShortCircuit`/`lowerConditional`/`lowerOptionalChain`/`lowerSwitch`/`lowerTry`/`lowerForOf`/`lowerForIn`/`lowerDo`/`lowerLabels`」行 |
| TS #104 | `try` / `catch` / `finally` | DROPPED-NOTED | translator.mjs:608 | §6.7 小节「`try / catch / finally`」+ §9 wire 表「`tryRegions`」行 + §10 改动清单「`lowerTry`（`:928-959`）」行 + §11.1 验收表「`try/catch/finally`」行 |
| TS #105 | `throw` | KEPT | translator.mjs:575-579 | 保持现状（无需改动） |
| TS #106 | labelled `break` / `continue` | DROPPED-NOTED | translator.mjs:608 | §6.8 小节「带标签的 break / continue」（翻译器维护**标签栈**：标签名、目标标签、`labelRef.kind`、`scopeId`）+ §10 改动清单「`lowerShortCircuit`/`lowerConditional`/`lowerOptionalChain`/`lowerSwitch`/`lowerTry`/`lowerForOf`/`lowerForIn`/`lowerDo`/`lowerLabels`」行 |
| TS #107 | unlabelled `break` / `continue` | DROPPED-NOTED | translator.mjs:600-607 | §6.8 小节「带标签的 break / continue」（`break` ⇒ `JUMP` 到最内层 `break` 目标；`continue` ⇒ `JUMP` 到最内层 `continue` 目标）+ §11.1 验收表「`break`/`continue`」行 |

### A.10 Operators

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #108 | arithmetic `+ - * / %` | KEPT | translator.mjs:90-114 | 保持现状（无需改动） |
| TS #109 | comparison `< <= > >=` | KEPT | translator.mjs:101-104 | 保持现状（无需改动） |
| TS #110 | strict vs loose equality (`===` vs `==`) | DEGRADED | translator.mjs:97-100 | §5.2 表达式面表「**松散相等 `a == b` / `a != b`**」行 + §5.3.1 小节「松散相等 `==` 与严格相等 `===`」+ §8 IR 需求「**IR-AT**」行 + §10 改动清单「枚举体（`:45-53`）」行 |
| TS #111 | `??` | DEGRADED | translator.mjs:158-161 | §5.2 表达式面表「`&&`/`\|\|`/`??`」行（短路展开成 `BRANCH` 链）+ §6.1 小节「`if` 与短路算子」 |
| TS #112 | `??=` | DEGRADED | translator.mjs:193-196 | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 |
| TS #113 | `&&=` | DEGRADED | translator.mjs:192 | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 |
| TS #114 | `\|\|=` | DEGRADED | translator.mjs:191 | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 |
| TS #115 | `**` | DEGRADED | translator.mjs:154-157 | §5.2 表达式面表「`**`」行（不再近似成 `MUL`；需真幂运算）+ §8 IR 需求「**IR-R**」行 |
| TS #116 | `!` | DROPPED-NOTED | translator.mjs:205-209, 1121-1129 | §5.2 表达式面表「一元算子」行（`!` ⇒ `NOT`）+ §5.1 小节「算子表重构」 |
| TS #117 | `~` | DROPPED-NOTED | translator.mjs:205-209, 1121-1129 | §5.2 表达式面表「一元算子」行（`~` ⇒ `BITNOT`） |
| TS #118 | unary `-` | DROPPED-NOTED | translator.mjs:205-209, 1121-1129 | §5.2 表达式面表「一元算子」行（一元 `-` ⇒ `NEG`） |
| TS #119 | `++` / `--` (prefix) | DROPPED-NOTED | translator.mjs:1106, 1121-1129 | §6.3 小节「循环」的「前缀 `++` / `--`」段（`PRE_INC` / `PRE_DEC`，与后缀形态分开） |
| TS #120 | `++` / `--` (postfix) | DROPPED-NOTED | translator.mjs:1150-1152 | §6.3 小节「循环」的「**`for` 的初始化器与步进**」段（`L_STEP: UNARYOP t1 = i POST_INC ; MOVE i = t1 ; JUMP L_HEAD`）+ §5.1 小节「算子表重构」 |
| TS #121 | `typeof` | DEGRADED | translator.mjs:791-793 | §5.2 的「**`typeof x`**」行（操作数类型精确 ⇒ 直接发常量字符串）；**本体已完整降级**：残留的「运行期才定的 8 个字符串之一」记 `VALUE` 级 drop |
| TS #122 | `void` | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「`void` 运算符」行（求值后丢弃，结果恒为 `undefined`） |
| TS #123 | `delete` | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「`delete obj.k`」行（删除键 ⇒ **IR-V**）+ §8 IR 需求「**IR-V**」行 |
| TS #124 | `in` | DROPPED-NOTED | translator.mjs:1008-1011 | §5.2 表达式面表「`in`」行（`BRANCH` + 新守卫 `PropertyPresence(receiver,key)`）+ §8 IR 需求「**IR-S**」行 + §11.1 验收表「`k in obj`」行 |
| TS #125 | `instanceof` | DROPPED-NOTED | translator.mjs:1008-1011 | §5.2 表达式面表「`instanceof`」行（`INSTANCEOF`；TS 侧今天没有 ⇒ 对齐）+ §7.2 收窄表「`if (x instanceof Y)`」行 + §11.1 验收表「`x instanceof Y`」行 |
| TS #126 | comma operator | DROPPED-NOTED | translator.mjs:1008-1011 | §5.2 表达式面表「逗号运算符」行（依次求值、只保留最后一个） |
| TS #127 | logical assignment (`&&=`, `\|\|=`, `??=`) | DEGRADED | translator.mjs:191-196 | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 |
| TS #128 | `?.` optional chaining (property) | DEGRADED | translator.mjs:855-867 | §5.2 表达式面表「可选链 `a?.b`」行 + §6.2 小节「三元与可选链」+ §11.1 验收表「`a?.b`」行 |
| TS #129 | `?.()` optional call | DEGRADED | translator.mjs:892-931 | §5.2 表达式面表「可选调用 `a?.()`」行 + §6.2 小节「三元与可选链」 |
| TS #130 | `?.[` optional element | DEGRADED | translator.mjs:870-882 | §5.2 表达式面表「可选下标 `a?.[k]`」行 + §6.2 小节「三元与可选链」 |
| TS #131 | bitwise `& \| ^ << >> >>>` | KEPT | translator.mjs:108-113 | 保持现状（无需改动） |

### A.11 Modules

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #132 | `import` | DROPPED-NOTED | translator.mjs:608, 317-399 | §5.2 表达式面表「静态 `import` / `export` 语句」行（零指令 + 模块导出表） |
| TS #133 | `export` (as a modifier) | KEPT | translator.mjs:272-274 | 保持现状（无需改动） |
| TS #134 | default export (named) | KEPT | translator.mjs:272-274 | 保持现状（无需改动） |
| TS #135 | default export (anonymous) | DROPPED-NOTED | translator.mjs:608 | §5.2 表达式面表「静态 `import` / `export` 语句」行（匿名默认导出 ⇒ 合成名字为 `default` 的匿名函数） |
| TS #136 | named export | KEPT | translator.mjs:272-274 | 保持现状（无需改动） |
| TS #137 | re-export (`export { x } from "m"`) | DROPPED-NOTED | translator.mjs:608 | §5.2 表达式面表「静态 `import` / `export` 语句」行（再导出 ⇒ 别名边进导出表） |
| TS #138 | `export *` | DROPPED-NOTED | translator.mjs:608 | §5.2 表达式面表「静态 `import` / `export` 语句」行（再导出 ⇒ 别名边进导出表） |
| TS #139 | `export * as ns` | DROPPED-NOTED | translator.mjs:608 | §5.2 表达式面表「静态 `import` / `export` 语句」行（再导出 ⇒ 别名边进导出表） |
| TS #140 | dynamic `import()` | DEGRADED | translator.mjs:892-931, 1447-1460 | §5.2 表达式面表「**动态 `import(spec)`**」行 + §5.3.3 小节「动态 `import()` 的目标」+ §10 改动清单「`methodNameOf`（TS `:1447-1460`、AK `:1728-1743`）」行 |
| TS #141 | `import.meta` | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「**`import.meta`**」行 + §5.3.4 小节「`import.meta`」+ §12 的 `TS4`行 |
| TS #142 | `require()` | DEGRADED | translator.mjs:419-437, 892-931 | §5.2 表达式面表「**调用的 owner**（`require()` / `document.*` / 字符串方法）」行 + §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」+ §10 改动清单「`GLOBAL_OWNERS`」行 |
| TS #143 | CommonJS interop (`module.exports = x`) | DEGRADED | translator.mjs:1066-1103 | §5.2 表达式面表「CommonJS 互操作」行（`module.exports` 是普通命名空间属性） |
| TS #144 | `import type` / `export type` | DROPPED-NOTED | translator.mjs:608 | §7.4 的「**`import type` / `export type`**」行（**IR-AU**：**零指令** + 类型图上的模块边进 `TacTypeDecl.typeOnlyImports`） |

### A.12 Decorators

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #145 | legacy decorator on a class | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4.1 的「**装饰器的运行期应用（IR-AV）**」小节（类装饰器 ⇒ `CALL <dec>/1 args=[C]`；**legacy** 语义下返回值**替换** `C`；装饰器链进 `TacTypeDecl.decorators`） |
| TS #146 | legacy decorator on a class member | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4.1 的「**装饰器的运行期应用（IR-AV）**」小节（成员装饰器 ⇒ `CALL <dec>/2 args=[C.prototype, "m"]`；装饰器链进 `TacMemberRef.decorators`） |
| TS #147 | legacy decorator on a parameter | DROPPED-SILENT · NOT FOUND | translator.mjs:536-543 | §9 wire 表「`classAnnotations`/`methodAnnotations`/`fieldAnnotations`/`paramAnnotations`/`annotationValues`」行（`paramAnnotations` 通道）+ §4 声明面表「装饰器 `@dec`」行；〔审计标 NOT FOUND〕 |
| TS #148 | TC39 stage-3 decorator | DROPPED-SILENT · NOT FOUND | translator.mjs:473-479 | §4.1 的「**装饰器的运行期应用（IR-AV）**」小节（标准装饰器：**成员阶段先、类阶段后**；类装饰器的返回值**不替换** `C`） |
| TS #149 | **wire-protocol field for annotations/decorators** | NOT FOUND | protocol.mjs (whole file: the wire function object is `name/signature/enclosingClass/precision/synthetic/params/locals/instructions/problems`), PROTOCOL.md:175-185 | §9 wire 协议改动清单「`classAnnotations`/`methodAnnotations`/`fieldAnnotations`/`paramAnnotations`/`annotationValues`」行（wire 上新增五个注解通道）+ §10 改动清单「`toFunction`（`:75-156`）、`toInstruction`（`:159-227`）、`toType`（`:282-312`）」行（读这五个通道） |

### A.13 Async / generators

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #150 | `async` function | DEGRADED | translator.mjs:457-467 | §5.2 的「**`async` 函数 / `async` 箭头**」行（返回值包成 Promise 的形状完整）+ §5.2 的「**`await p`**」行；**本体已完整降级**：残留的「**微任务时序**」记 `SEMANTICS` 级 drop |
| TS #151 | `await` | DEGRADED | translator.mjs:788-790 | §5.2 的「**`await p`**」行（**值域完整**：`t_a = MOVE p`）；**本体已完整降级**：残留的「**微任务时序**」记 `SEMANTICS` 级 drop |
| TS #152 | generator function | DEGRADED | translator.mjs:457-467 | §5.2 的「**`yield v`**」行（降级成**状态机**）+ §5.2 的「**`yield*`（委托）**」行；**⚠ R21 实测：那句「本体已完整降级」在改之前是错的** —— `yield` **没有任何分支** ⇒ ⇒ **体没被遍历** ⇒ ⇒ **R21 已修**（`14` §3.44.4：操作数就地求值 ⇒ ⇒ 子树进 TAC）；**状态机仍未建**（要 `IR-L`） |
| TS #153 | `yield` | DEGRADED | translator.mjs:843-844 | §5.2 表达式面表「`yield v`」行（`MOVE gen.yielded = v` + `JUMP` 挂起标签；恢复入口即函数入口，状态派发在入口）|**⚠ R21 已做**（`14` §3.44.4）：**操作数就地求值** ⇒ ⇒ 子树进 TAC ⇒ ⇒ 协议与丢值各一条回执（`SEMANTICS` / `VALUE`）⇒ ⇒ **状态机那一步仍未做**（要 `IR-L`） |
| TS #154 | `yield*` | DEGRADED | translator.mjs:843-844 | §5.2 表达式面表「`yield*`（委托）」行（展开成迭代协议循环）|**⚠ R21 已做**（`14` §3.44.4）：**委托的操作数就地求值** ⇒ ⇒ 委托的那棵子树进 TAC ⇒ ⇒ 迭代协议仍不在 TAC 里 ⇒ ⇒ 回执 `SEMANTICS` |
| TS #155 | `for await` | DEGRADED | translator.mjs:721-752 | §6.4 小节「`for..of`（迭代协议）」的「`for await`（异步迭代）」段 |

### A.14 JSX / TSX

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #156 | JSX elements / TSX | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「JSX 元素」行（语法糖 ⇒ 降级成一次 `CALL`） |

### A.15 `namespace` / `module` / ambient declarations

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #157 | `namespace` / `module` declarations | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「命名空间 `namespace N {…}`」行（编译成 IIFE ⇒ `CALL` + 块；成员各自独立函数） |
| TS #158 | `declare global` | DROPPED-NOTED | translator.mjs:608 | §4 声明面表「`declare global`」行（零指令；增强的名字进 `TacTypeDecl`） |
| TS #159 | ambient `.d.ts` files | DROPPED-NOTED | TsTacFrontend.java:185-196 | §10 改动清单「`FE/ts/TsTacFrontend.java`」行（跳过 ambient `.d.ts` + 报文件级 `PARSE_PROBLEM`） |

### A.16 Template literals, regex, `super` / `this`

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #160 | template literal (no substitution) | KEPT | translator.mjs:782-784 | 保持现状（无需改动） |
| TS #161 | template literal (with holes) | KEPT | translator.mjs:1232-1258 | 保持现状（无需改动） |
| TS #162 | tagged template | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「**标签模板**（`tag` + 模板字面量）」行 + §5.3.2 小节「标签模板（tagged template）」+ §10 改动清单「`lowerExpression` 的表达式分派（TS `:762-845`、AK `:969-1062`）」行 |
| TS #163 | regular-expression literal | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「正则字面量」行（`CONST.REGEX`）+ §9 的「常量表新增」段落 |
| TS #164 | `super` | DROPPED-NOTED | translator.mjs:843-844 | §5.3.9 小节「`extends`（类继承）」的「`super` 的另外两种形态」段 + §4 声明面表「类继承 `extends`」行 |
| TS #165 | `this` | DEGRADED | translator.mjs:794-799, 1400-1409 | §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」的 owner 解析**第 2 级**（接收者的类名）+ §5.2 表达式面表「属性读 `obj.x`（**类实例**）」行（`FIELDLOAD obj.x` + `ownerType` = 类名） |

### A.17 Explicit resource management

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #166 | `using r = x` | DEGRADED | translator.mjs:611-629 | §6.7 小节「`try / catch / finally`」的「`using` / `await using`（显式资源管理）」段（合成 finally + `resourceVars`） |
| TS #167 | `await using r = x` | DEGRADED | translator.mjs:611-629 | §6.7 小节「`try / catch / finally`」的「`using` / `await using`（显式资源管理）」段（`disposeAsync` 的 `await`） |

### A.18 JS-specific semantics

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #168 | `var` vs `let` / `const` | DEGRADED | translator.mjs:611-629 | §6.10 小节「声明种类、作用域与提升」（`var` 提升到函数入口，`let`/`const` 是块作用域） |
| TS #169 | hoisting / TDZ | DEGRADED | translator.mjs:762-766 | §6.10 小节「声明种类、作用域与提升」（提升与 TDZ 两段；TDZ ⇒ `THROW` 边） |
| TS #170 | `arguments` | DEGRADED | translator.mjs:762-766 | §12 的 `TS7`行（降级为「未知键的容器访问」；属性级污点仍在，精确到具体属性的规则失效） |
| TS #171 | prototype chains | DEGRADED | translator.mjs:470-481 | §5.3.9 的「**类继承 `extends`**」小节（静态原型链按 `superType` 链由近到远查找，**完整表达**）+ 该小节的「**运行期原型改写的残留**」段；**本体已完整降级**：残留的「运行期挂到原型上的成员」记 `VALUE` 级 drop |
| TS #172 | `with` | DROPPED-NOTED | translator.mjs:608 | §12 的 `TS7`行 |
| TS #173 | `eval` (language construct) | DEGRADED | translator.mjs:892-931 | §12 的 `TS7`行 + §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」（owner 解析第 4 级「已知全局内建」） |
| TS #174 | `delete` semantics | DROPPED-NOTED | translator.mjs:843-844 | §5.2 表达式面表「`delete obj.k`」行 + §8 IR 需求「**IR-V**」行（`ARRAYSTORE` 加「删除」标志 ⇒ 「键不存在」与「键值为 `undefined`」可区分） |

### A.19 Object and array literals

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #175 | object literal property assignment (`{a: v}`) | KEPT | translator.mjs:1185-1196 | 保持现状（无需改动） |
| TS #176 | object literal **shorthand** (`{v}`) | DROPPED-SILENT · NOT FOUND | translator.mjs:1187-1188 | §5.2 表达式面表「对象字面量」行（简写 ⇒ `ARRAYSTORE t["v"] = v`） |
| TS #177 | object literal **method** (`{m(){}}`) | DROPPED-SILENT · NOT FOUND | translator.mjs:1187-1188 | §5.2 表达式面表「对象字面量」行（方法 ⇒ 独立函数 + `FIELDSTORE t["m"] = <闭包对象>`） |
| TS #178 | object literal **getter/setter** | DROPPED-SILENT · NOT FOUND | translator.mjs:1187-1188 | §5.2 表达式面表「对象字面量」行（访问器 ⇒ 独立 getter 函数 + `CALL <Owner>.x/0`） |
| TS #179 | object literal **computed key** (`{[k]: v}`) | DROPPED-SILENT · NOT FOUND | translator.mjs:1187 | §5.2 表达式面表「对象字面量」行（计算键 ⇒ 键取表达式的值） |
| TS #180 | array literal | KEPT | translator.mjs:1201-1223 | 保持现状（无需改动） |

### A.20 Built-ins that matter to analysis

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #181 | `eval(x)` | DEGRADED | translator.mjs:892-931 | §5.2 表达式面表「`eval(x)`」行（参数照常求值；返回值未知值 + `VALUE` 级 drop）+ §12 的 `TS20`行 |
| TS #182 | `new Function(...)` | DEGRADED | translator.mjs:934-950 | §5.2 表达式面表「`new Function(...)`」行（函数体未知值 + `VALUE` 级 drop ⇒ 调用是黑盒）+ §12 的 `TS21`行 |
| TS #183 | `setTimeout(cb, ms)` | DEGRADED | translator.mjs:892-931 | §5.2 表达式面表「回调形参位置」行（回调体按闭包降级；触发时机不可静态表达）+ §12 的 `TS22`行 |
| TS #184 | `fetch(url)` | DEGRADED | translator.mjs:892-931 | §5.2 表达式面表「`fetch(url)`」行（响应体未知值 + `VALUE` 级 drop）+ §12 的 `TS23`行 |
| TS #185 | `JSON.parse(s)` | DEGRADED | translator.mjs:892-931 | §5.2 表达式面表「已知内建的污点源 / sink」行（污点分类由引擎侧的源 / sink 名表决定）+ §12 的 `TS24`行 |
| TS #186 | `JSON.stringify(v)` | DEGRADED | translator.mjs:892-931 | §5.2 表达式面表「已知内建的污点源 / sink」行（污点分类由引擎侧的源 / sink 名表决定）+ §12 的 `TS24`行 |
| TS #187 | `localStorage.getItem(k)` | DEGRADED | translator.mjs:892-931 | §5.2 表达式面表「已知内建的污点源 / sink」行（污点分类由引擎侧的源 / sink 名表决定）+ §12 的 `TS24`行 |
| TS #188 | `document.*` | DEGRADED | translator.mjs:855-867 | §5.2 表达式面表「**调用的 owner**（`require()` / `document.*` / 字符串方法）」行 + §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」 |
| TS #189 | `process.env.X` | DEGRADED | translator.mjs:855-867 | §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」（owner 第 4 级含 `process`）+ §10 改动清单「`GLOBAL_OWNERS`」行（全局内建名表含 `process`） |
| TS #190 | `require(m)` | DEGRADED | translator.mjs:892-931 | §5.2 表达式面表「**调用的 owner**（`require()` / `document.*` / 字符串方法）」行 + §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」 |

### A.21 Module scope (structural)

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| TS #191 | module-level statements | DROPPED-SILENT | translator.mjs:280-295 | §4 声明面表「模块级语句」行（模块级合成函数 `<module>.__top/0`） |
| TS #192 | a variable statement that also holds a function | DROPPED-SILENT · NOT FOUND | translator.mjs:280-286 | §4 声明面表「变量语句的逐个声明项」行（逐个 `declaration` 处理 ⇒ 两类都不丢） |

**附录 A 小计**：192 行（= 审计 A 节 `## A. Coverage ledger` 的全部数据行：191 条构念行 + 1 条「wire 协议注解字段」协议能力行）。**192 行全部有落点**（`⚠` = 0）：其中 `KEPT` **18 行**（保持现状），需给落点的 **174 行**按落点分类为 **A（完整表达）153 行 / B（显式记账）14 行 / C（永久例外）7 行**。原先带 `⚠` 的 **91 行**已在本轮补齐，逐条清单见本节末尾的「原『⚠ 未覆盖清单』—— 已全部补齐（TypeScript / JavaScript）」。

### 原『⚠ 未覆盖清单』—— 已全部补齐（TypeScript / JavaScript）

| 编号 | construct | 现在的落点 | 分类 |
|---|---|---|---|
| U-TS1 | TS #5 条件类型 | §7.4 的「**条件类型**」行（**IR-AU**：条件与两个分支进 `TacType.typeLayer`；零指令） | A |
| U-TS2 | TS #6 映射类型 | §7.4 的「**映射类型**」行（**IR-AU**：`MAPPED(key = KEYOF(T), valueType = X)`；零指令） | A |
| U-TS3 | TS #7 索引访问类型（`T[K]`） | §7.4 的「**索引访问类型**」行（**IR-AU**：`INDEXED_ACCESS(subject = T, key = K)`；零指令） | A |
| U-TS4 | TS #8 模板字面量类型 | §7.4 的「**模板字面量类型**」行（**IR-AU**：`TEMPLATE_LITERAL(pattern = …)`；零指令） | A |
| U-TS5 | TS #10 剩余类型（元组里的 `...T[]`） | §7.1 类型面表「元组 `[A,B]`」行（补「元组里的剩余元素」子句：长度不定 ⇒ 同一条 `STRUCTURE` 级 drop，元素类型仍取首个） | A |
| U-TS6 | TS #11 可选属性 / 可选形参 | §7.1 类型面表「可选属性 / 可选形参」行（`nullable` 侧 + 「可缺省」的 arity 口径）+ §4 声明面表「可选形参」行 | A |
| U-TS7 | TS #12 `keyof` | §7.4 的「**`keyof` T**」行（**IR-AU**：`KEYOF(subject = T)`；具体类型 ⇒ 键名集合进 `TacType` 联合成员，**IR-T**；零指令） | A |
| U-TS8 | TS #13 类型层 `typeof` | §7.1 类型面表「类型查询」行（类型可静态求出 ⇒ 记该 `TacType`；不产生运行期值 ⇒ 零指令） | A |
| U-TS9 | TS #14 `infer` | §7.4 的「**`infer`**」行（绑定出的类型参数进 `TacTypeDecl.typeParameters[]` 的 `constraint`，**IR-Y 的字段扩展**；零指令） | A |
| U-TS10 | TS #15 泛型 + 约束 + 默认实参 | §7.1 类型面表「类型参数 `T`」行（约束与默认实参进 `TacTypeDecl.typeParameters`）+ §8.1 的「**IR-Y 的字段扩展**」行 | A |
| U-TS11 | TS #16 `readonly` | §4 声明面表「`readonly` 属性」行（只读标记进 `TacTypeDecl.fields`）+ §7.1 类型面表「只读类型」行 | A |
| U-TS12 | TS #20 类型谓词（`x is T`） | §7.2 收窄表「类型谓词」行（守卫 `TypeTest(x, CLASS<T>, false)`；分支内收窄 ⇒ **IR-Z**） | A |
| U-TS13 | TS #21 断言函数（`asserts x is T`） | §7.2 收窄表「断言函数」行（抛错路径 `THROW` 带 `ctorType = "ReferenceError"`；调用点之后的收窄用 `TypeTest` ⇒ **IR-Z**） | A |
| U-TS14 | TS #24 / TS #144 `import type` / `export type` | §7.4 的「**`import type` / `export type`**」行（**IR-AU**：零指令 + 模块边进 `TacTypeDecl.typeOnlyImports`） | A |
| U-TS15 | TS #27 `const` 类型参数 | §7.4 的「**`const` 类型参数**」行（实参推成**字面量类型**、不拓宽 ⇒ 直接记该字面量 `TacType`；零指令） | A |
| U-TS16 | TS #28 DNF 类型 | §7.4 的「**DNF 类型**」行（成员集合进 `TacType` 的联合成员列表，**IR-T**；零指令） | A |
| U-TS17 | TS #32 非空断言 `expr!` | §7.2 收窄表「非空断言」行（运行期零指令；非空事实落 `TacType.nullable=false`） | A |
| U-TS18 | TS #33 声明合并 | §7.1 类型面表「声明合并」行（同名接口的成员取并集进同一个 `TacTypeDecl`） | A |
| U-TS19 | TS #34 接口 `extends` | §7.1 类型面表「接口的 `extends`」行（父接口进 `TacTypeDecl.superType`；接口继承不产生构造器调用） | A |
| U-TS20 | TS #36 可选 / 只读属性 | §4 声明面表「`readonly` 属性」行（只读）+ §7.1 类型面表「可选属性 / 可选形参」行（可选） | A |
| U-TS21 | TS #37 接口 call signature | §4 声明面表「接口 call signature」行（无体 ⇒ 不生成函数；进 `TacTypeDecl.members`） | A |
| U-TS22 | TS #38 接口 construct signature | §4 声明面表「接口 construct signature」行（无体 ⇒ 进 `TacTypeDecl.members`） | A |
| U-TS23 | TS #39 接口方法签名 | §4 声明面表「接口方法签名」行（无体 ⇒ 进 `TacTypeDecl.members`） | A |
| U-TS24 | TS #40 泛型接口 | §4 声明面表「接口 call signature」行 + §8.1 的「**IR-Y 的字段扩展**」行（接口的类型参数进 `TacTypeDecl.typeParameters`，带约束与默认实参） | A |
| U-TS25 | TS #45 只读属性 | §4 声明面表「`readonly` 属性」行（只读标记进 `TacTypeDecl.fields`） | A |
| U-TS26 | TS #46 可见性修饰符（`public`/`private`/`protected`） | §4 声明面表「可见性修饰符」行（可见性进 `fieldAnnotations`；运行期无行为 ⇒ 指令不变） | A |
| U-TS27 | TS #48 `override` 属性 | §4 声明面表「`override` 属性」行（标记进 `fieldAnnotations`；运行期零指令） | A |
| U-TS28 | TS #49 `accessor` 字段 | §4 声明面表「`accessor` 字段」行（合成 getter + setter + 后备字段） | A |
| U-TS29 | TS #51 构造器参数属性 | §4 声明面表「构造器参数属性」行（构造器体内 `FIELDSTORE this.<param>`） | A |
| U-TS30 | TS #55 `implements` | §4 声明面表「`implements`」行（接口名进 `TacTypeDecl.interfaces`）+ §9 wire 表「`types`」行 | A |
| U-TS31 | TS #58 `#private` 字段 | §4 声明面表「`#private` 字段」行（字段名保留 `#` 前缀 ⇒ 与 `x` 是两个字段） | A |
| U-TS32 | TS #64 字符串枚举 | §4 声明面表「字符串枚举」行（成员值是字符串常量；不生成反向映射） | A |
| U-TS33 | TS #65 混合枚举 | §4 声明面表「混合枚举」行（各成员各自的值类型；反向映射只对数值成员生成） | A |
| U-TS34 | TS #66 `const enum` | §4 声明面表「`const enum`」行（编译期内联 ⇒ 运行期没有枚举对象 ⇒ 零指令） | A |
| U-TS35 | TS #67 计算成员 | §4 声明面表「枚举计算成员」行（编译期常量求值；求不出 ⇒ 未知值 + `VALUE` 级 drop） | A |
| U-TS36 | TS #72 异步箭头函数 | §5.2 的「**`async` 函数 / `async` 箭头**」行（返回值包成 Promise 的形状完整）；**本体已完整降级**：残留的「`await` 的**微任务时序**」记 `SEMANTICS` 级 drop | B |
| U-TS37 | TS #75 / TS #91 剩余形参（`...xs`） | §4 声明面表「剩余形参」行（形参类型 `ARRAY`）+ §8.1 的「**IR-W 的字段扩展**」行 | A |
| U-TS38 | TS #76 可选形参 | §4 声明面表「可选形参」行（`nullable` + 「可缺省」的 arity 口径） | A |
| U-TS39 | TS #77 `this` 形参 | §4 声明面表「`this` 形参」行（只进类型、不进签名、不占参数位） | A |
| U-TS40 | TS #78 默认参数值 | §4 声明面表「默认参数值」行（函数体开头补 `MOVE x = <default>`；默认值里的调用照常降级） | A |
| U-TS41 | TS #83 模式里的默认值 | §4 声明面表「模式里的默认值」行（`BRANCH` + 守卫 `Compare(t_a, NE, NULL)`） | A |
| U-TS42 | TS #85 嵌套模式 | §4 声明面表「嵌套模式」行（递归展开：先取外层键，再取内层键） | A |
| U-TS43 | TS #87 赋值式解构（`({j} = o)`） | §4 声明面表「赋值式解构」行（不产生新变量 ⇒ 对已存在的变量赋值） | A |
| U-TS44 | TS #89 对象字面量展开（`{...o}`） | §5.2 表达式面表「展开」行（对象字面量展开 `{...o}` ⇒ 逐键 `ARRAYSTORE`） | A |
| U-TS45 | TS #92 数组/模式里的剩余元素 | §4 声明面表「数组剩余元素」行（`NEWARRAY` + 逐元素 `ARRAYSTORE`） | A |
| U-TS46 | TS #112 / TS #113 / TS #114 / TS #127 复合赋值（`??=` / `&&=` / `\|\|=`） | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 | A |
| U-TS47 | TS #116 / TS #117 / TS #118 一元 `!` / `~` / `-` | §5.2 表达式面表「一元算子」行（`!` ⇒ `NOT`）+ §5.1 小节「算子表重构」 | A |
| U-TS48 | TS #119 前缀 `++` / `--` | §6.3 小节「循环」的「前缀 `++` / `--`」段（`PRE_INC` / `PRE_DEC`，与后缀形态分开） | A |
| U-TS49 | TS #122 `void` 运算符 | §5.2 表达式面表「`void` 运算符」行（求值后丢弃，结果恒为 `undefined`） | A |
| U-TS50 | TS #126 逗号运算符 | §5.2 表达式面表「逗号运算符」行（依次求值、只保留最后一个） | A |
| U-TS51 | TS #129 可选调用 `?.()` | §5.2 表达式面表「可选调用 `a?.()`」行 + §6.2 小节「三元与可选链」 | A |
| U-TS52 | TS #132 静态 `import` 语句 | §5.2 表达式面表「静态 `import` / `export` 语句」行（零指令 + 模块导出表） | A |
| U-TS53 | TS #135 匿名默认导出 | §5.2 表达式面表「静态 `import` / `export` 语句」行（匿名默认导出 ⇒ 合成名字为 `default` 的匿名函数） | A |
| U-TS54 | TS #137 / TS #138 / TS #139 再导出（`export {x} from` / `export *` / `export * as ns`） | §5.2 表达式面表「静态 `import` / `export` 语句」行（再导出 ⇒ 别名边进导出表） | A |
| U-TS55 | TS #143 CommonJS 互操作（`module.exports = x`） | §5.2 表达式面表「CommonJS 互操作」行（`module.exports` 是普通命名空间属性） | A |
| U-TS56 | TS #150 `async` 函数 | §5.2 的「**`async` 函数 / `async` 箭头**」行（返回值包成 Promise 的形状完整）；**本体已完整降级**：残留的「`await` 的**微任务时序**」记 `SEMANTICS` 级 drop | B |
| U-TS57 | TS #154 `yield*` | §5.2 表达式面表「`yield*`（委托）」行（展开成迭代协议循环） | A |
| U-TS58 | TS #155 `for await` | §6.4 小节「`for..of`（迭代协议）」的「`for await`（异步迭代）」段 | A |
| U-TS59 | TS #156 JSX / TSX | §5.2 表达式面表「JSX 元素」行（语法糖 ⇒ 降级成一次 `CALL`） | A |
| U-TS60 | TS #158 `declare global` | §4 声明面表「`declare global`」行（零指令；增强的名字进 `TacTypeDecl`） | A |
| U-TS61 | TS #159 ambient `.d.ts` | §10 改动清单「`FE/ts/TsTacFrontend.java`」行（跳过 ambient `.d.ts` + 报文件级 `PARSE_PROBLEM`） | A |
| U-TS62 | TS #163 正则字面量 | §5.2 表达式面表「正则字面量」行（`CONST.REGEX`）+ §9 的「常量表新增」段落 | A |
| U-TS63 | TS #164 `super` | §5.3.9 小节「`extends`（类继承）」的「`super` 的另外两种形态」段 + §4 声明面表「类继承 `extends`」行 | A |
| U-TS64 | TS #166 / TS #167 `using` / `await using` | §6.7 小节「`try / catch / finally`」的「`using` / `await using`（显式资源管理）」段（合成 finally + `resourceVars`） | A |
| U-TS65 | TS #168 `var` vs `let` / `const` | §6.10 小节「声明种类、作用域与提升」（`var` 提升到函数入口，`let`/`const` 是块作用域） | A |
| U-TS66 | TS #169 提升 / TDZ | §6.10 小节「声明种类、作用域与提升」（提升与 TDZ 两段；TDZ ⇒ `THROW` 边） | A |
| U-TS67 | TS #171 原型链 | §5.3.9 的「**类继承 `extends`**」小节（静态原型链按 `superType` 链查找，**完整表达**）+ 该小节的「**运行期原型改写的残留**」段；**本体已完整降级**：残留的「运行期挂到原型上的成员」记 `VALUE` 级 drop | B |
| U-TS68 | TS #176 / TS #177 / TS #178 / TS #179 对象字面量的简写 / 方法 / 访问器 / 计算键 | §5.2 表达式面表「对象字面量」行（简写 ⇒ `ARRAYSTORE t["v"] = v`） | A |
| U-TS69 | TS #181 `eval(x)` | §5.2 表达式面表「`eval(x)`」行（参数照常求值；返回值未知值 + `VALUE` 级 drop）+ §12 的 `TS20`行 | B |
| U-TS70 | TS #182 `new Function(...)` | §5.2 表达式面表「`new Function(...)`」行（函数体未知值 + `VALUE` 级 drop ⇒ 调用是黑盒）+ §12 的 `TS21` | C |
| U-TS71 | TS #183 `setTimeout(cb, ms)` | §5.2 表达式面表「回调形参位置」行（回调体按闭包降级；触发时机不可静态表达）+ §12 的 `TS22`行 | B |
| U-TS72 | TS #184 `fetch(url)` | §5.2 表达式面表「`fetch(url)`」行（响应体未知值 + `VALUE` 级 drop）+ §12 的 `TS23`行 | B |
| U-TS73 | TS #185 / TS #186 / TS #187 `JSON.parse` / `JSON.stringify` / `localStorage.getItem` | §5.2 表达式面表「已知内建的污点源 / sink」行（污点分类由引擎侧的源 / sink 名表决定）+ §12 的 `TS24`行 | C |
| U-TS74 | TS #191 模块级语句 | §4 声明面表「模块级语句」行（模块级合成函数 `<module>.__top/0`） | A |
| U-TS75 | TS #192 混合声明语句（一条语句里既有函数又有普通变量） | §4 声明面表「变量语句的逐个声明项」行（逐个 `declaration` 处理 ⇒ 两类都不丢） | A |

---

## 附录 B. ArkTS 审计台账 A.1–A.22 逐条去向（167 行）

**审计来源**：`D:\work\tmp\audit\09-arkts.md` 的 `## A. Coverage ledger` 节（子小节 A.1–A.22）。
**本附录覆盖的是审计 A 节（完整台账），不是 B 节（丢弃清单）** —— 与附录 A 同一口径。
**编号**：审计 A 节各表**没有** `#` 列 ⇒ 本附录按 **A 节出现顺序**自加连续编号（`AK #1`–`AK #167`），只用于本附录内部索引，不改写审计的行；与附录 A 的 `TS #N` 区分。
**「审计状态」列**沿用审计自己的状态词（`KEPT` / `DEGRADED-SILENT` / `DEGRADED-NOTED` / `DROPPED-SILENT` / `DROPPED-NOTED`；审计另有 `N/A` 与带括号的限定词，也按原文保留），**不改写审计的结论**。ArkTS 审计用 `DEGRADED-SILENT` / `DEGRADED-NOTED` 两级（TS 侧只有 `DEGRADED` 一级），这里按**各面审计自己的词表**填写，不互相换算。
**「审计证据」列**是审计原文 `file:line` 的**转录**，**不重新核对行号**（审计的行号会漂）。
**「本方案的去向」列**给出本文档里**真实存在**的落点：**章节号 + 表行原文片段**（片段可在本文件内直接 grep 到）。
**`KEPT` 行**的去向列写「保持现状（无需改动）」；**`N/A` 行**写「不适用（审计已判 N/A）」。
**本轮已把原先带 `⚠ 未覆盖 —— ` 的 50 行全部补齐** ⇒ 台账行里**没有一行**是「本文档没有落点」的；逐条的补齐清单见本节末尾的「原『⚠ 未覆盖清单』—— 已全部补齐」。

### A.1 Type system

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #1 | primitives (string/number/boolean/bigint/void) | KEPT | plumb-frontend/arkts-sidecar/src/translator.mjs:1775-1806 | 保持现状（无需改动） |
| AK #2 | literal types | DEGRADED-SILENT | translator.mjs:982-1000 | §7.1 类型面表「字面量 `"a"`/`1`/`true`」行（值进 `Const`，类型仍是基础类型） |
| AK #3 | union types | DEGRADED-SILENT | translator.mjs:1775-1806 | §7.1 类型面表「一般联合 `string \| number`」行 + §8 IR 需求「**IR-T**」行 |
| AK #4 | `undefined` | DEGRADED-SILENT | translator.mjs:1792-1797 | §7.1 的「**`undefined`**」行（**IR-X**：`TacOperand.Const` 增 `UNDEFINED` ⇒ 与 `null` 分开）+ §5.2 的「**可选链 `a?.b`**」行（短路结果不再是 `NULL`） |
| AK #5 | `null` | KEPT | translator.mjs:994-998 | 保持现状（无需改动） |
| AK #6 | class types | KEPT | translator.mjs:1798-1804 | 保持现状（无需改动） |
| AK #7 | interface types | DROPPED-SILENT (type-only) | translator.mjs:663-670 | §7.1 类型面表「类/接口」行（名称取自编译器符号名）+ §9 wire 表「`types`」行（`isInterface`）〔注：审计丢的是*接口声明被跳过*这一事实本身，本文档没有为它单列一行，但接口的类型信息有落点〕 |
| AK #8 | generics + constraints | DEGRADED-SILENT | translator.mjs:1775-1806 | §7.1 类型面表「类型参数 `T`」行（约束与默认实参进 `TacTypeDecl.typeParameters`）+ §8.1 的「**IR-Y 的字段扩展**」行 |
| AK #9 | `readonly` | DROPPED-SILENT (type-only) | translator.mjs:369-397 | §4 声明面表「`readonly` 属性」行 + §7.1 类型面表「只读类型」行 |
| AK #10 | optional properties / optional params | DEGRADED-SILENT | translator.mjs:1775-1806 | §7.1 类型面表「可选属性 / 可选形参」行（`nullable` 侧 + arity 口径）+ §4 声明面表「可选形参」行 |
| AK #11 | type assertion `as` | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「`as`/`satisfies`/`<T>`」行 + §7.1 类型面表「`as`/`satisfies`/`<T>`」行 + §7.2 收窄小节 |
| AK #12 | non-null assertion `!` | DEGRADED-SILENT | translator.mjs:1011-1013 | §7.2 收窄表「非空断言」行（运行期零指令；非空事实落 `TacType.nullable=false`） |
| AK #13 | `satisfies` | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「`as`/`satisfies`/`<T>`」行 + §7.1 类型面表「`as`/`satisfies`/`<T>`」行 + §7.2 收窄小节 |
| AK #14 | enums (number / string) | DEGRADED-SILENT | translator.mjs:432-462 | §4 声明面表「字符串枚举」行 + 「枚举计算成员」行（显式初值之后继续递增） |
| AK #15 | `type` alias | DROPPED-SILENT (type-only) | translator.mjs:663-670 | §7.1 类型面表「类型别名」行（别名展开后的 `TacType`；运行期零指令） |
| AK #16 | `interface` declaration | DROPPED-SILENT (type-only) | translator.mjs:663-670 | §7.1 类型面表「类/接口」行 + §9 wire 表「`types`」行（`isInterface`）+ §4 声明面表「`declare` 声明」行（无运行期实体 ⇒ 进 `TacTypeDecl`）〔注：接口**声明**被跳过这一事实本身没有单列一行〕 |
| AK #17 | `implements` | DROPPED-SILENT | translator.mjs:369-397 | §4 声明面表「`implements`」行（接口名进 `TacTypeDecl.interfaces`）+ §9 wire 表「`types`」行 |
| AK #18 | `extends` | DEGRADED-SILENT | translator.mjs:370-397 | §4 声明面表「**类继承 `extends`**」行 + §5.3.9 小节「`extends`（类继承）」+ §7.1 类型面表「类的父类（`extends`）」行 |

### A.2 ArkTS type-system restrictions

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #19 | no `any` / `unknown` / `never` | DROPPED-SILENT | plumb-frontend/arkts-sidecar/src/compiler.mjs:346-352 | §5.3.5 小节「ArkTS 的 `arkts-no-*` 规则」（违反事实由编译器给出，前端只负责把它**记账**：文件级 `programDrops`，`impact:"SEMANTICS"`, `scope:"file"`）+ §9 wire 表「`programDrops`（文件级，续）」行 |
| AK #20 | no index signatures | DROPPED-SILENT | compiler.mjs:346-352 | 同 AK #19 的落点：§5.3.5 小节「ArkTS 的 `arkts-no-*` 规则」+ §9 wire 表「`programDrops`（文件级，续）」行 |
| AK #21 | no mapped / conditional / template-literal types | DROPPED-SILENT | compiler.mjs:346-352 | 同 AK #19 的落点：§5.3.5 小节「ArkTS 的 `arkts-no-*` 规则」+ §9 wire 表「`programDrops`（文件级，续）」行 |
| AK #22 | no `object` / `Function` types | DROPPED-SILENT | compiler.mjs:346-352 | 同 AK #19 的落点：§5.3.5 小节「ArkTS 的 `arkts-no-*` 规则」+ §9 wire 表「`programDrops`（文件级，续）」行 |
| AK #23 | no structural typing for class types | KEPT (the nominal name is kept) | translator.mjs:1798-1804 | 保持现状（无需改动） |
| AK #24 | no `typeof` type query | DROPPED-SILENT (type-only) | translator.mjs:663-670 | §7.1 类型面表「类型查询」行（类型可静态求出 ⇒ 记该 `TacType`；不产生运行期值 ⇒ 零指令） |
| AK #25 | no `keyof` | DROPPED-SILENT (type-only) | compiler.mjs:346-352 | §7.4 的「**`keyof` T**」行（**IR-AU**：`KEYOF(subject = T)`；`T` 具体 ⇒ 键名集合进 `TacType` 的联合成员，**IR-T**；**零指令**） |
| AK #26 | no tuples | DROPPED-SILENT | compiler.mjs:346-352 | §5.3.5 小节「ArkTS 的 `arkts-no-*` 规则」+ §9 wire 表「`programDrops`（文件级，续）」行（规则违反记成文件级 drop）〔可表达侧的形状见 §7.1 类型面表「元组 `[A,B]`」行〕 |
| AK #27 | no union of unrelated types | DROPPED-SILENT | compiler.mjs:346-352 | §5.3.5 小节「ArkTS 的 `arkts-no-*` 规则」+ §9 wire 表「`programDrops`（文件级，续）」行〔可表达侧的落点是 §7.1 类型面表「一般联合 `string \| number`」行 + §8 IR 需求「**IR-T**」行〕 |
| AK #28 | no `delete` | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「`delete obj.k`」行 + §8 IR 需求「**IR-V**」行〔`delete` 在 ArkTS 里被禁，但 `delete` **表达式**仍要有形状 —— 记账通道见 §5.3.5〕 |
| AK #29 | no `Proxy` / `Reflect` | DROPPED-SILENT | compiler.mjs:346-352 | §12 的 `TS7`行（降级为「未知键的容器访问」）+ §5.3.5 记账通道 |
| AK #30 | no `eval` / `with` | DEGRADED-NOTED | translator.mjs:1109-1145, translator.mjs:680 | §12 的 `TS7`行（`eval` 与 `with` 同归「语言本身无静态语义」这一类） |
| AK #31 | no `arguments` | DEGRADED-SILENT | translator.mjs:973-975 | §12 的 `TS7`行 |

### A.3 Classes

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #32 | property declarations with initialisers | KEPT | translator.mjs:406-424 | 保持现状（无需改动） |
| AK #33 | `declare` | DROPPED-SILENT (type-only) | translator.mjs:407-410 | §4 声明面表「`declare` 声明」行（运行期无实体 ⇒ 不生成函数；进 `TacTypeDecl`） |
| AK #34 | `readonly` | DROPPED-SILENT (type-only) | translator.mjs:369-397 | §4 声明面表「`readonly` 属性」行（只读标记进 `TacTypeDecl.fields`） |
| AK #35 | `public` / `private` / `protected` | DROPPED-SILENT (type-only) | translator.mjs:369-397 | §4 声明面表「可见性修饰符」行（可见性进 `fieldAnnotations`；运行期无行为 ⇒ 指令不变） |
| AK #36 | `static` property | DEGRADED-SILENT | translator.mjs:344, 406-424 | §4 声明面表「静态字段」行（receiver 是类对象 `t_cls_<Name>`，类型 `CLASS<Name>` ⇒ `FIELDSTORE t_cls_Name.x = <init>`）+ §10 改动清单「`lowerClass`（`:370-397`）、`lowerStruct`（`:341-367`）」行（补静态成员） |
| AK #37 | `static` method | DEGRADED-SILENT | translator.mjs:392-394, 476-479 | §4 声明面表「静态字段」行 + §10 改动清单「`lowerClass`（`:370-397`）、`lowerStruct`（`:341-367`）」行（补静态成员）〔静态**方法**的 receiver / owner 口径见 §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」的 owner 第 2 级〕 |
| AK #38 | `override` | DROPPED-SILENT (type-only) | translator.mjs:387-395 | §4 声明面表「`override` 属性」行（标记进 `fieldAnnotations`；运行期零指令） |
| AK #39 | methods | KEPT | translator.mjs:392-394 | 保持现状（无需改动） |
| AK #40 | constructors | KEPT (body) / DEGRADED-SILENT (signature) | translator.mjs:487-501 | §4 声明面表「构造函数」行（独立函数，名字 `<init>`，签名 `mod.Class.<init>(参数)`）+ §5.3.9 小节「`extends`（类继承）」（`lowerConstructor` 的签名补 arity 与形参类型，必须与 `new` 点的键**逐字对齐**）+ §10 改动清单「`lowerConstructor`（`:487-501`）」行 |
| AK #41 | parameter properties | N/A (ArkTS forbids them) | translator.mjs:565-575 | 不适用（审计已判 N/A：ArkTS 禁止参数属性） |
| AK #42 | overloads | DEGRADED-SILENT | translator.mjs:480-482 | §4 声明面表「重载声明」行（只有签名无体 ⇒ 不生成函数；进 `TacTypeDecl.members`） |
| AK #43 | getters / setters | DROPPED-SILENT | translator.mjs:387-395 | §4 声明面表「getter `get x()`」行与「setter `set x(v)`」行 + §5.2 表达式面表「getter 的调用 `obj.x`（x 是 getter）」行 + §10 改动清单「`lowerClass`（`:370-397`）、`lowerStruct`（`:341-367`）」行（补访问器） |
| AK #44 | static initialisation blocks | DROPPED-SILENT | translator.mjs:387-395 | §4 声明面表「静态块 `static {…}`」行（合成函数；类初始化时 `CALL mod.Class.<clinit>/0`）+ §10 改动清单「`lowerClass`（`:370-397`）、`lowerStruct`（`:341-367`）」行 |
| AK #45 | `#private` fields | KEPT | translator.mjs:1878-1880 | 保持现状（无需改动） |
| AK #46 | abstract members | DEGRADED-SILENT | translator.mjs:480-482 | §4 声明面表「抽象方法」行（无体 ⇒ 不生成函数；进 `TacTypeDecl.members`，类标 `isAbstract`） |
| AK #47 | class expressions | DROPPED-NOTED | translator.mjs:1050-1053 | §5.2 表达式面表「类表达式 `const C = class {…}`」行（类即构造器 ⇒ `t_c = NEW CLASS<C>`） |

### A.4 Structs

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #48 | `struct` vs `class` | KEPT | translator.mjs:341-367 | 保持现状（无需改动） |
| AK #49 | what a struct may contain | DEGRADED-SILENT | translator.mjs:358-365 | §4 声明面表「ArkTS `struct`」行（现状「一个 struct 初始器 + 每方法一个函数」；补访问器、静态成员）+ §10 改动清单「`lowerClass`（`:370-397`）、`lowerStruct`（`:341-367`）」行 |
| AK #50 | is `struct` lowered at all | KEPT | translator.mjs:341-367 | 保持现状（无需改动） |
| AK #51 | struct inheritance | N/A (ArkTS forbids it) | translator.mjs:341-367 | 不适用（审计已判 N/A：ArkTS 禁止 struct 继承） |
| AK #52 | the synthetic struct initializer | DEGRADED-SILENT | translator.mjs:406-424, 1896-1910 | §4 声明面表「ArkTS `struct`」行（补「初始器只发一次」子句） |

### A.5 Functions

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #53 | declarations | KEPT | translator.mjs:240-243, 466-485 | 保持现状（无需改动） |
| AK #54 | expressions | DROPPED-NOTED | translator.mjs:1054-1059 | §5.2 表达式面表「箭头/函数表达式（闭包）」行 + §5.2 小节「闭包细节」（定义点 / 被调函数 / 调用点三段形态） |
| AK #55 | arrow functions | DEGRADED-NOTED | translator.mjs:510-536, 1054-1059 | §5.2 表达式面表「箭头/函数表达式（闭包）」行 + §5.2 小节「闭包细节」〔箭头函数在**实参位置**（回调）的形态见 §5.2 小节「闭包细节」的调用点写法〕 |
| AK #56 | overloads | DEGRADED-SILENT | translator.mjs:480-482 | §4 声明面表「重载声明」行 |
| AK #57 | generics | DEGRADED-SILENT | translator.mjs:1775-1806 | §7.1 类型面表「类型参数 `T`」行 + §8 IR 需求「**IR-Y**」行 |
| AK #58 | rest params | DEGRADED-SILENT | translator.mjs:565-575 | §4 声明面表「剩余形参」行（形参类型 `ARRAY`）+ §8.1 的「**IR-W 的字段扩展**」行 |
| AK #59 | optional params | DEGRADED-SILENT | translator.mjs:565-575 | §4 声明面表「可选形参」行（`nullable` + 「可缺省」的 arity 口径） |
| AK #60 | default params | DEGRADED-SILENT | translator.mjs:565-575 | §4 声明面表「默认参数值」行（函数体开头补 `MOVE x = <default>`） |
| AK #61 | destructured params | DROPPED-SILENT | translator.mjs:565-575 | §4 声明面表「**参数解构** `f({a,b}: Props)`」行（造合成参数 `p`；函数体开头 `t_a = ARRAYLOAD p["a"]; MOVE a = t_a`） |
| AK #62 | IIFE | DEGRADED-NOTED | translator.mjs:1109-1145, 1728-1743 | §5.2 表达式面表「箭头/函数表达式（闭包）」行 + §5.3.3 小节「动态 `import()` 的目标」（callee 不再退化成 `"<call>"`；owner 缺失走 IR-P 的结构化形状）+ §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」（第 5 级：结构化 owner 缺失） |
| AK #63 | local functions | DROPPED-NOTED | translator.mjs:680 | §4 声明面表「嵌套函数」行（提升为独立函数） |

### A.6 Destructuring / spread / rest

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #64 | object patterns | DROPPED-SILENT | translator.mjs:688-700 | §4 声明面表「变量解构 `const {a,b}=obj`」行 |
| AK #65 | array patterns | DROPPED-SILENT | translator.mjs:688-700 | §4 声明面表「数组解构 `const [a,b]=arr`」行 |
| AK #66 | defaults in a pattern | DROPPED-SILENT | translator.mjs:688-700 | §4 声明面表「模式里的默认值」行（`BRANCH` + 守卫 `Compare(t_a, NE, NULL)`） |
| AK #67 | rest in a pattern | DROPPED-SILENT | translator.mjs:688-700 | §4 声明面表「剩余元素 `const {a,...rest}=obj`」行 |
| AK #68 | parameter destructuring | DROPPED-SILENT | translator.mjs:565-575 | §4 声明面表「**参数解构** `f({a,b}: Props)`」行 |
| AK #69 | array spread | DEGRADED-SILENT | translator.mjs:1463-1487 | §5.2 表达式面表「展开」行（数组/对象字面量内展开 ⇒ `NEWARRAY` + 逐元素 `ARRAYSTORE`；个数已知时） |
| AK #70 | object spread | DROPPED-SILENT | translator.mjs:1445-1458 | §5.2 表达式面表「展开」行（对象字面量展开 `{...o}` ⇒ 逐键 `ARRAYSTORE`） |

### A.7 Control flow

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #71 | `if` / `else` | KEPT | translator.mjs:710-732 | 保持现状（无需改动） |
| AK #72 | ternary | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「三元 `c?a:b`」行 + §6.2 小节「三元与可选链」+ §11.1 验收表「`c ? a : b`」行 |
| AK #73 | `switch` | DEGRADED-NOTED (soundness loss) | translator.mjs:897-919 | §6.6 小节「`switch`（真选择 + fall-through）」（必须建出 case 选择，`default` 落在**链尾**，不能落在源码位置）+ §10 改动清单「`lowerSwitch`（`:897-919`）」行 + §11.1 验收表「`switch`」行 |
| AK #74 | `for` | DEGRADED-NOTED | translator.mjs:774-811, 776 | §6.3 小节「循环」的「**`for` 的初始化器与步进**」段（`DECLARE i : INT` + `MOVE i = 0`；守卫判据从 `isVariableStatement` 改成 `VariableDeclarationList`） |
| AK #75 | `for-in` | DEGRADED-SILENT | translator.mjs:856-887, 858 | §6.5 小节「`for..in`（键）」（`t_key = ARRAYLOAD t_keys[t_i]; MOVE <loopVar> = t_key`）+ §10 改动清单「`lowerForOf`（`:822-834`）/`lowerForIn`（`:856-868`）」行（绑键） |
| AK #76 | `for-of` | DEGRADED-SILENT | translator.mjs:822-853, 824 | §6.4 小节「`for..of`（迭代协议）」（必须绑元素，不能绑可迭代对象）+ §10 改动清单「`lowerForOf`（`:822-834`）/`lowerForIn`（`:856-868`）」行（绑元素）+ §13.1 风险表「K2」行 |
| AK #77 | `while` | KEPT | translator.mjs:734-754 | 保持现状（无需改动） |
| AK #78 | `do-while` | KEPT | translator.mjs:756-772 | 保持现状（无需改动） |
| AK #79 | `try` / `catch` / `finally` | DROPPED-NOTED (function-aborting) | translator.mjs:928-959, 932 | §6.7 小节「`try / catch / finally`」+ §9 wire 表「`tryRegions`」行 + §10 改动清单「`lowerTry`（`:928-959`）」行（接异常边 + `tryRegions` 各字段）+ §11.1 验收表「`try/catch/finally`」行 |
| AK #80 | `throw` | KEPT (instruction) / DROPPED-SILENT (edges) | translator.mjs:606-609; SidecarTacMapper.java:137-156 | §6.7 小节「`try / catch / finally`」（`THROW` / `CATCH_HANDLER` / `FINALLY_ENTER`；必填的 `tryRegions` 字段表）+ §9 wire 表「`tryRegions`」行〔**异常边**的缺失由 §6.7 与 §9 共同补上〕 |
| AK #81 | labelled `break` / `continue` | DROPPED-NOTED | translator.mjs:639-646 | §6.8 小节「带标签的 break / continue」（翻译器维护**标签栈**）+ §10 改动清单「`lowerShortCircuit`/`lowerConditional`/`lowerOptionalChain`/`lowerSwitch`/`lowerTry`/`lowerForOf`/`lowerForIn`/`lowerDo`/`lowerLabels`」行 + §11.1 验收表「`break`/`continue`」行 |

### A.8 Operators

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #82 | arithmetic (`+ - * / %`) | KEPT | translator.mjs:51-57 | 保持现状（无需改动） |
| AK #83 | comparison | KEPT | translator.mjs:58-65 | 保持现状（无需改动） |
| AK #84 | strict vs loose equality | DEGRADED-SILENT | translator.mjs:58-61 | §5.2 表达式面表「**松散相等 `a == b` / `a != b`**」行 + §5.3.1 小节「松散相等 `==` 与严格相等 `===`」+ §8 IR 需求「**IR-AT**」行 + §10 改动清单「枚举体（`:45-53`）」行 |
| AK #85 | `??` | DEGRADED-NOTED | translator.mjs:94-97 | §5.2 表达式面表「`&&`/`\|\|`/`??`」行（短路展开成 `BRANCH` 链）+ §6.1 小节「`if` 与短路算子」 |
| AK #86 | `??=` | DEGRADED-NOTED | translator.mjs:129-132 | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 |
| AK #87 | `&&=` | DEGRADED-SILENT | translator.mjs:128 | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 |
| AK #88 | `\|\|=` | DEGRADED-SILENT | translator.mjs:127 | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 |
| AK #89 | `**` | DEGRADED-NOTED | translator.mjs:90-93 | §5.2 表达式面表「`**`」行（不再近似成 `MUL`；需真幂运算）+ §8 IR 需求「**IR-R**」行 |
| AK #90 | `!` | DROPPED-NOTED | translator.mjs:141-145, 1381 | §5.2 表达式面表「一元算子」行（`!` ⇒ `NOT`） |
| AK #91 | `~` | DROPPED-NOTED | translator.mjs:141-145, 1381 | §5.2 表达式面表「一元算子」行（`~` ⇒ `BITNOT`） |
| AK #92 | unary `-` | DROPPED-NOTED | translator.mjs:141-145, 1381 | §5.2 表达式面表「一元算子」行（一元 `-` ⇒ `NEG`） |
| AK #93 | `typeof` | DEGRADED-SILENT | translator.mjs:1008-1010 | §5.2 的「**`typeof x`**」行（操作数类型精确 ⇒ 直接发常量字符串）；**本体已完整降级**：残留的「运行期才定的 8 个字符串之一」记 `VALUE` 级 drop |
| AK #94 | `void` | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「`void` 运算符」行（求值后丢弃，结果恒为 `undefined`） |
| AK #95 | `delete` | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「`delete obj.k`」行 + §8 IR 需求「**IR-V**」行 |
| AK #96 | `in` | DROPPED-NOTED | translator.mjs:1266-1269 | §5.2 表达式面表「`in`」行（`BRANCH` + 新守卫 `PropertyPresence(receiver,key)`）+ §8 IR 需求「**IR-S**」行 + §11.1 验收表「`k in obj`」行 |
| AK #97 | `instanceof` | DEGRADED-SILENT | translator.mjs:1247-1258 | §5.2 表达式面表「`instanceof`」行（`INSTANCEOF`；ArkTS 侧已有 ⇒ 对齐）+ §7.2 收窄表「`if (x instanceof Y)`」行 + §11.1 验收表「`x instanceof Y`」行 |
| AK #98 | optional chaining `?.` | DEGRADED-SILENT | translator.mjs:1014-1019, 1087-1099 | §5.2 表达式面表「可选链 `a?.b`」行 + §6.2 小节「三元与可选链」+ §11.1 验收表「`a?.b`」行 |
| AK #99 | optional call `?.()` | DEGRADED-SILENT | translator.mjs:1109-1145 | §5.2 表达式面表「可选调用 `a?.()`」行 + §6.2 小节「三元与可选链」 |
| AK #100 | optional index `?.[` | DEGRADED-SILENT | translator.mjs:1087-1099 | §5.2 表达式面表「可选下标 `a?.[k]`」行 + §6.2 小节「三元与可选链」 |
| AK #101 | bitwise (`& \| ^ << >> >>>`) | KEPT | translator.mjs:69-74 | 保持现状（无需改动） |
| AK #102 | comma operator | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「逗号运算符」行（依次求值、只保留最后一个） |

### A.9 Modules

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #103 | `import` (named / default / namespace) | KEPT | translator.mjs:273-314, 1675-1703 | 保持现状（无需改动） |
| AK #104 | default export | KEPT | translator.mjs:240-243 | 保持现状（无需改动） |
| AK #105 | named export | KEPT | translator.mjs:240-243, 245-252 | 保持现状（无需改动） |
| AK #106 | re-export (`export { x as y }`) | DROPPED-SILENT | translator.mjs:671-676 | §5.2 表达式面表「静态 `import` / `export` 语句」行（再导出 ⇒ 别名边进导出表） |
| AK #107 | `export * from` | DROPPED-SILENT | translator.mjs:671-676 | §5.2 表达式面表「静态 `import` / `export` 语句」行（再导出 ⇒ 别名边进导出表） |
| AK #108 | dynamic `import()` | DEGRADED-SILENT | translator.mjs:1728-1743 | §5.2 表达式面表「**动态 `import(spec)`**」行 + §5.3.3 小节「动态 `import()` 的目标」+ §10 改动清单「`methodNameOf`（TS `:1447-1460`、AK `:1728-1743`）」行 |
| AK #109 | `import.meta` | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「**`import.meta`**」行 + §5.3.4 小节「`import.meta`」+ §12 的 `TS4`行 |
| AK #110 | `require()` | DEGRADED-SILENT | translator.mjs:1675-1703 | §5.2 表达式面表「**调用的 owner**（`require()` / `document.*` / 字符串方法）」行 + §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」+ §10 改动清单「`GLOBAL_OWNERS`」行 |
| AK #111 | `import type` / `export type` | DROPPED-SILENT (type-only) | translator.mjs:663-670 | §7.4 的「**`import type` / `export type`**」行（**IR-AU**：**零指令** + 类型图上的模块边进 `TacTypeDecl.typeOnlyImports`） |
| AK #112 | `export struct` | KEPT | translator.mjs:225-229 | 保持现状（无需改动） |
| AK #113 | `export default struct` | KEPT | translator.mjs:225-229 | 保持现状（无需改动） |

### A.10 Decorators

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #114 | `Component` / `ComponentV2` / `Entry` / `Previewer` / `Extend` / `CustomDialog` | DROPPED-SILENT | translator.mjs:205-257 (no `modifiers` read); protocol.mjs:33-56; PROTOCOL.md:200-210 | §4 声明面表「ArkTS `@Component` / `@Entry`」行（装饰器名进 `classAnnotations` 与 `TacTypeDecl.annotations`）+ §5.3.7 小节「`@Entry` 组件标识」+ §9 wire 表「`classAnnotations`/`methodAnnotations`/`fieldAnnotations`/`paramAnnotations`/`annotationValues`」行〔`ComponentV2` / `Previewer` / `Extend` / `CustomDialog` 这几个具体名字没有单独成行，但**同一通道**覆盖它们〕 |
| AK #115 | `State` / `Local` / `Prop` / `Param` / `Link` / `Provide` / `Consume` / `Require` / `Once` / `Watch` / `Observed` / `ObjectLink` / `Trace` / `Event` / `Monitor` / `Reusable` / `MonoStorage` / `Type` | DROPPED-SILENT | translator.mjs:205-257 (no `modifiers` read); protocol.mjs:33-56; PROTOCOL.md:200-210 | §4 声明面表「ArkTS 状态管理装饰器全集」行（标记进 `fieldAnnotations`）+ §5.3.6 的「状态管理装饰器的两类边」表（`@Provide`/`@Consume` 的链接边不可静态表达 ⇒ §12 的 `TS25`行） |
| AK #116 | `Builder` / `BuilderParam` | DEGRADED-SILENT | translator.mjs:358-365 | §4 声明面表「ArkTS `@Builder` 方法 / `@BuilderParam` 形参」行 + §5.3.6 小节「`@Builder` / `@BuilderParam` 绑定」+ §12 的 `TS5`行 |
| AK #117 | `@Concurrent` / `@Sendable` functions | DROPPED-SILENT | translator.mjs (never read); SidecarTacMapper.java:184-193 | §5.3.7 小节「`@Entry` 组件标识」的「`@Concurrent` / `@Sendable`」段（标记进 `methodAnnotations` 与 `effect`）+ §12 的 `TS27`行 |

### A.11 ArkUI / the `build()` method

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #118 | the `build()` method itself | KEPT | translator.mjs:358-361 | 保持现状（无需改动） |
| AK #119 | `Entry` components | DEGRADED-SILENT | translator.mjs:341-367 | §4 声明面表「ArkTS `@Component` / `@Entry`」行 + §5.3.7 小节「`@Entry` 组件标识」+ §12 的 `TS6`行 |
| AK #120 | `Column` / `Row` / `Stack` / `Text` / `Image` / `Button` / `List` | KEPT | translator.mjs:1160-1179 | 保持现状（无需改动） |
| AK #121 | `if` / `else` in `build()` | KEPT | translator.mjs:710-732 | 保持现状（无需改动） |
| AK #122 | `ForEach` | DEGRADED-NOTED | translator.mjs:1160-1179, 1054-1059 | §5.2 表达式面表「回调形参位置」行（`ForEach` 的 item generator 按闭包降级） |
| AK #123 | `LazyForEach` | DEGRADED-NOTED | translator.mjs:1160-1179, 1054-1059 | §5.2 表达式面表「回调形参位置」行（`LazyForEach` 的 item generator 按闭包降级） |
| AK #124 | `ForEach` with an item generator | DEGRADED-NOTED | translator.mjs:1054-1059 | §5.2 表达式面表「回调形参位置」行（`ForEach` 带 item generator ⇒ 回调形参位置放闭包对象） |
| AK #125 | `LazyForEach` with an `IDataSource` | DEGRADED-NOTED | translator.mjs:369-397, 1109-1145 | §4 声明面表「`IDataSource` 实现」行（普通类降级 + `implements` 进 `TacTypeDecl.interfaces`） |

### A.12 State-management runtime

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #126 | `AppStorage` / `AppStorageV2` | DEGRADED-SILENT | translator.mjs:1675-1703, 1752-1760 | §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」（owner 解析五级链：第 4 级「已知全局内建」；第 5 级 = **结构化 owner 缺失**，禁止回落模块名）+ §10 改动清单「`arityOf`（TS `:1469-1477`、AK `:1752-1760`）」行（未解析的 callee 的 arity 改为**显式实参个数**） |
| AK #127 | `LocalStorage` / `PersistentStorage` / `PersistentStorageV2` | DEGRADED-SILENT | translator.mjs:1675-1703 | 同 AK #126 的落点：§5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」+ §10 改动清单「`arityOf`（TS `:1469-1477`、AK `:1752-1760`）」行 |
| AK #128 | `router` | KEPT | translator.mjs:1675-1703 | 保持现状（无需改动） |
| AK #129 | `promptAction` | KEPT | translator.mjs:1675-1703 | 保持现状（无需改动） |
| AK #130 | `abilityContext` | DEGRADED-SILENT | translator.mjs:1633-1647 | §4 声明面表「运行时上下文对象」行（属性值未知值 + `VALUE` 级 drop）+ §12 的 `TS28`行 |
| AK #131 | `common` | KEPT (as a type only) | translator.mjs:1633-1647 | 保持现状（无需改动） |
| AK #132 | `hilog` | KEPT | translator.mjs:1675-1703 | 保持现状（无需改动） |

### A.13 Async / generators

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #133 | `async` functions | DEGRADED-SILENT | translator.mjs:466-485 | §5.2 的「**`async` 函数 / `async` 箭头**」行（返回值包成 Promise 的形状完整）+ §5.2 的「**`await p`**」行；**本体已完整降级**：残留的「**微任务时序**」记 `SEMANTICS` 级 drop |
| AK #134 | `await` | DEGRADED-SILENT | translator.mjs:1005-1007 | §5.2 的「**`await p`**」行（**值域完整**：`t_a = MOVE p`）；**本体已完整降级**：残留的「**微任务时序**」记 `SEMANTICS` 级 drop |
| AK #135 | generator functions | DEGRADED-NOTED | translator.mjs:466-485, 1060 | §5.2 的「**`yield v`**」行（降级成**状态机**）+ §5.2 的「**`yield*`（委托）**」行；**本体已完整降级**：残留的「**驱动方语义**」记 `SEMANTICS` 级 drop |**⚠ R21 实测：那句「本体已完整降级」在 ArkTS 侧同样是错的** —— ArkTS 侧车的 `yield` **也没有任何分支**（探针：`yield cmd.length` ⇒ ⇒ **只有一条 `HAVOC`**，操作数从未被访问，零回执 + 一条 `UNHANDLED_NODE`）⇒ ⇒ **与 TS 侧同形** ⇒ ⇒ **本轮只修了 TS 侧**（`14` §3.44.3）⇒ ⇒ **两侧不一致这件事记在这里** ⇒ ⇒ **ArkTS 侧是下一轮的第一件事** |
| AK #136 | `yield` | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「`yield v`」行（`MOVE gen.yielded = v` + `JUMP` 挂起标签） |
| AK #137 | `yield*` | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「`yield*`（委托）」行（展开成迭代协议循环） |

### A.14 JSX / TSX

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #138 | JSX / TSX | N/A (ArkTS uses the declarative DSL) | ArktsTacFrontend.java:136-138 | 不适用（审计已判 N/A：ArkTS 前端只接受 `.ets`，用声明式 DSL） |

### A.15 `namespace` / ambient declarations

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #139 | `namespace` / `module` declarations | DROPPED-NOTED | translator.mjs:680 | §4 声明面表「命名空间 `namespace N {…}`」行（编译成 IIFE ⇒ `CALL` + 块；成员各自独立函数） |
| AK #140 | ambient `.d.ts` / `.d.ets` | DROPPED-NOTED | ArktsTacFrontend.java:185-196 | §10 改动清单「`FE/arkts/ArktsTacFrontend.java`」行（跳过 ambient `.d.ts`/`.d.ets` + 报文件级 `PARSE_PROBLEM`） |

### A.16 Template literals and tagged templates

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #141 | template literals | KEPT | translator.mjs:1496-1525, 982-984 | 保持现状（无需改动） |
| AK #142 | tagged templates | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「**标签模板**（`tag` + 模板字面量）」行 + §5.3.2 小节「标签模板（tagged template）」+ §10 改动清单「`lowerExpression` 的表达式分派（TS `:762-845`、AK `:969-1062`）」行 |

### A.17 Regex literals

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #143 | regex literals | DROPPED-NOTED | translator.mjs:1060 | §5.2 表达式面表「正则字面量」行（`CONST.REGEX`）+ §9 的「常量表新增」段落 |

### A.18 `super` / `this`

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #144 | `super` | DROPPED-NOTED | translator.mjs:1060 | §5.3.9 小节「`extends`（类继承）」的「`super` 的另外两种形态」段 + §4 声明面表「类继承 `extends`」行 |
| AK #145 | `this` | DEGRADED-SILENT | translator.mjs:976-981, 1594-1597; protocol.mjs:137-141 | §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」（owner 解析**第 2 级**：接收者的类名）+ §5.2 表达式面表「属性读 `obj.x`（**类实例**）」行（`FIELDLOAD obj.x` + `ownerType` = 类名）+ §9 wire 表「`ownerType`」行 |

### A.19 Explicit resource management

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #146 | `using` / `await using` | DROPPED-NOTED (file level) | compiler.mjs:346-352 | §6.7 小节「`try / catch / finally`」的「`using` / `await using`（显式资源管理）」段（合成 finally + `resourceVars`） |

### A.20 ArkTS interop

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #147 | `@Concurrent` / `@Sendable` functions | DROPPED-SILENT | translator.mjs:205-257 (no `modifiers` read); SidecarTacMapper.java:184-193 | §5.3.7 小节「`@Entry` 组件标识」的「`@Concurrent` / `@Sendable`」段（标记进 `methodAnnotations` 与 `effect`）+ §12 的 `TS27`行 |
| AK #148 | `SharedArrayBuffer` / `ArrayBuffer` | KEPT (as an ordinary type or call) | translator.mjs:1182-1198 | 保持现状（无需改动） |
| AK #149 | `ArrayBuffer` transfer | N/A (a runtime API, not a language construct) | — | 不适用（审计已判 N/A：运行期 API，不是语言构念） |
| AK #150 | `@Observed` / `@ObjectLink` observation semantics | DROPPED-SILENT | translator.mjs:205-257 (no `modifiers` read); protocol.mjs:33-56 | §5.3.6 的「状态管理装饰器的两类边」表（观察边不可静态表达 ⇒ 不建边）+ §12 的 `TS26`行 |
| AK #151 | `arkts-no-*` lint rules | DROPPED-SILENT | compiler.mjs:346-352 | §5.3.5 小节「ArkTS 的 `arkts-no-*` 规则」（取诊断的路径：为 lint 另建 BuilderProgram + 调 `runArkTSLinter`；记成文件级 `programDrops`）+ §9 wire 表「`programDrops`（文件级，续）」行 + §10 改动清单「`openProgram`（`:260-295`）」行与「文件级 `problems`（`:164-166`、`:199`）」行 |

### A.21 Built-ins that matter to analysis

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #152 | `eval` | KEPT | translator.mjs:1109-1145 | 保持现状（无需改动） |
| AK #153 | `Function` | KEPT | translator.mjs:1182-1198 | 保持现状（无需改动） |
| AK #154 | `setTimeout` | DEGRADED-NOTED | translator.mjs:1109-1145, 1054-1059 | §5.2 表达式面表「回调形参位置」行（回调体按闭包降级；触发时机不可静态表达）+ §12 的 `TS22`行 |
| AK #155 | `fetch` | KEPT | translator.mjs:1109-1145 | 保持现状（无需改动） |
| AK #156 | `JSON.parse` / `JSON.stringify` | KEPT | translator.mjs:1675-1703 | 保持现状（无需改动） |
| AK #157 | `AppStorage` | DEGRADED-SILENT | see A.12 | 同 AK #126 的落点：§5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」+ §10 改动清单「`arityOf`（TS `:1469-1477`、AK `:1752-1760`）」行 |
| AK #158 | `router.pushUrl` | KEPT | translator.mjs:1675-1703 | 保持现状（无需改动） |
| AK #159 | `document.*` | DEGRADED-SILENT | translator.mjs:1675-1703 | §5.2 表达式面表「**调用的 owner**（`require()` / `document.*` / 字符串方法）」行 + §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」 |
| AK #160 | `process.env` | KEPT | translator.mjs:1087-1099 | 保持现状（无需改动） |
| AK #161 | `require` | DEGRADED-SILENT | translator.mjs:1675-1703 | §5.2 表达式面表「**调用的 owner**（`require()` / `document.*` / 字符串方法）」行 + §5.3.8 小节「`require()` / `document.*` / 字符串方法的 owner」 |

### A.22 Cross-cutting: the wire protocol and the mapper (ArkTS-relevant)

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| AK #162 | the callee's parameter types | DROPPED-SILENT | SidecarTacMapper.java:159-227 (never set); the wire has no field | §9 wire 表「`calleeParamTypes`」行 + §10 改动清单「`FE/sidecar/SidecarTacMapper.java`」行 |
| AK #163 | the function's return type | DROPPED-SILENT | translator.mjs:550 (`returnType: undefined`); SidecarTacMapper.java:140 | §9 wire 表「`returnType`」行（**修**：现发 `undefined`，JSON 后键消失 ⇒ 返回类型恒 `UNKNOWN`）+ §10 改动清单「`finishFunction`（`:515-534`）」行（补 `returnType`）+ §11.1 验收表「任何函数」行 |
| AK #164 | the try / catch / finally regions | DROPPED-SILENT | SidecarTacMapper.java:137-156 (`tryRegions = List.of()`); the wire has no field; TacCfgBuilder.java:193, 205, 225, 286 | §6.7 小节「`try / catch / finally`」（必填的 `tryRegions` 字段表）+ §9 wire 表「`tryRegions`」行 + §10 改动清单「`toFunction`（`:75-156`）、`toInstruction`（`:159-227`）、`toType`（`:282-312`）」行（`:150` 换真实 tryRegions）+ §11.1 验收表「`try/catch/finally`」行 |
| AK #165 | the module-level statements | DEGRADED-SILENT | translator.mjs:205-257 | §4 声明面表「模块级语句」行（模块级合成函数 `<module>.__top/0`） |
| AK #166 | the class / struct initializer's signature | DEGRADED-SILENT | translator.mjs:346-351, 375-380 | §4 声明面表「构造函数」行（签名 `mod.Class.<init>(参数)`）+ §5.3.9 小节「`extends`（类继承）」（`lowerConstructor` 的签名补 arity 与形参类型；必须与 `new` 点的键**逐字对齐**）+ §10 改动清单「`lowerConstructor`（`:487-501`）」行 |
| AK #167 | the project's `.ts` files | DEGRADED-SILENT | ArktsTacFrontend.java:136-138; ArktsProjectDiscovery.java:78 | §10 改动清单「`FE/arkts/ArktsTacFrontend.java`」行（`.ts` 只参与类型解析、不被翻译） |

**附录 B 小计**：167 行（= 审计 A 节 `## A. Coverage ledger` 的全部数据行：163 条构念行 + 4 条 `N/A` 行 —— 参数属性、struct 继承、JSX/TSX、`ArrayBuffer` transfer）。**167 行全部有落点**（`⚠` = 0）：其中 `KEPT` **38 行**（保持现状）、`N/A` **4 行**（该构念在本语言里不存在），需给落点的 **125 行**按落点分类为 **A（完整表达）108 行 / B（显式记账）10 行 / C（永久例外）7 行**。原先带 `⚠` 的 **50 行**已在本轮补齐，逐条清单见本节末尾的「原『⚠ 未覆盖清单』—— 已全部补齐（ArkTS）」。

### 原『⚠ 未覆盖清单』—— 已全部补齐（ArkTS）

| 编号 | construct | 现在的落点 | 分类 |
|---|---|---|---|
| U-AK1 | AK #8 泛型 + 约束 | §7.1 类型面表「类型参数 `T`」行（约束与默认实参进 `TacTypeDecl.typeParameters`）+ §8.1 的「**IR-Y 的字段扩展**」行 | A |
| U-AK2 | AK #9 / AK #34 `readonly` | §4 声明面表「`readonly` 属性」行 + §7.1 类型面表「只读类型」行 | A |
| U-AK3 | AK #10 / AK #59 可选属性 / 可选形参 | §7.1 类型面表「可选属性 / 可选形参」行（`nullable` 侧 + arity 口径）+ §4 声明面表「可选形参」行 | A |
| U-AK4 | AK #12 非空断言 `!` | §7.2 收窄表「非空断言」行（运行期零指令；非空事实落 `TacType.nullable=false`） | A |
| U-AK5 | AK #14 枚举（字符串 / 显式初值后的自动递增） | §4 声明面表「字符串枚举」行 + 「枚举计算成员」行（显式初值之后继续递增） | A |
| U-AK6 | AK #15 `type` 别名 | §7.1 类型面表「类型别名」行（别名展开后的 `TacType`；运行期零指令） | A |
| U-AK7 | AK #17 `implements` | §4 声明面表「`implements`」行（接口名进 `TacTypeDecl.interfaces`）+ §9 wire 表「`types`」行 | A |
| U-AK8 | AK #24 / AK #111 类型层 `typeof` 查询 | §7.1 类型面表「类型查询」行（类型可静态求出 ⇒ 记该 `TacType`；不产生运行期值 ⇒ 零指令） | A |
| U-AK9 | AK #25 `keyof` | §7.4 的「**`keyof` T**」行（**IR-AU**：`KEYOF(subject = T)`；具体类型 ⇒ 键名集合进 `TacType` 联合成员，**IR-T**；零指令） | A |
| U-AK10 | AK #35 可见性修饰符（`public`/`private`/`protected`） | §4 声明面表「可见性修饰符」行（可见性进 `fieldAnnotations`；运行期无行为 ⇒ 指令不变） | A |
| U-AK11 | AK #38 `override` | §4 声明面表「`override` 属性」行（标记进 `fieldAnnotations`；运行期零指令） | A |
| U-AK12 | AK #52 合成 struct 初始器 | §4 声明面表「ArkTS `struct`」行（补「初始器只发一次」子句） | A |
| U-AK13 | AK #58 剩余形参 | §4 声明面表「剩余形参」行（形参类型 `ARRAY`）+ §8.1 的「**IR-W 的字段扩展**」行 | A |
| U-AK14 | AK #60 默认参数值 | §4 声明面表「默认参数值」行（函数体开头补 `MOVE x = <default>`） | A |
| U-AK15 | AK #66 模式里的默认值 | §4 声明面表「模式里的默认值」行（`BRANCH` + 守卫 `Compare(t_a, NE, NULL)`） | A |
| U-AK16 | AK #70 对象字面量展开（`{...o}`） | §5.2 表达式面表「展开」行（对象字面量展开 `{...o}` ⇒ 逐键 `ARRAYSTORE`） | A |
| U-AK17 | AK #86 / AK #87 / AK #88 复合赋值（`??=` / `&&=` / `\|\|=`） | §5.2 表达式面表「复合赋值」行 + §6.1 小节「`if` 与短路算子」 | A |
| U-AK18 | AK #90 / AK #91 / AK #92 一元 `!` / `~` / `-` | §5.2 表达式面表「一元算子」行（`!` ⇒ `NOT`） | A |
| U-AK19 | AK #94 `void` 运算符 | §5.2 表达式面表「`void` 运算符」行（求值后丢弃，结果恒为 `undefined`） | A |
| U-AK20 | AK #99 / AK #100 可选调用 `?.()` / 可选下标 `?.[` | §5.2 表达式面表「可选调用 `a?.()`」行 + §6.2 小节「三元与可选链」 | A |
| U-AK21 | AK #102 逗号运算符 | §5.2 表达式面表「逗号运算符」行（依次求值、只保留最后一个） | A |
| U-AK22 | AK #106 / AK #107 再导出（`export {x as y}` / `export * from`） | §5.2 表达式面表「静态 `import` / `export` 语句」行（再导出 ⇒ 别名边进导出表） | A |
| U-AK23 | AK #115 状态管理装饰器全集（18 个） | §4 声明面表「ArkTS 状态管理装饰器全集」行（标记进 `fieldAnnotations`）+ §4.1 的「与 ArkTS 框架装饰器的分界」表 + §12 的 `TS30` | C |
| U-AK24 | AK #117 / AK #147 `@Concurrent` / `@Sendable` 函数 | §5.3.7 小节「`@Entry` 组件标识」的「`@Concurrent` / `@Sendable`」段（标记进 `methodAnnotations` 与 `effect`）+ §12 的 `TS27`行 | B |
| U-AK25 | AK #122 / AK #123 / AK #124 `ForEach` / `LazyForEach` 及其 item generator | §5.2 表达式面表「回调形参位置」行（`ForEach` 的 item generator 按闭包降级） | A |
| U-AK26 | AK #125 `LazyForEach` 与 `IDataSource` 实现 | §4 声明面表「`IDataSource` 实现」行（普通类降级 + `implements` 进 `TacTypeDecl.interfaces`） | A |
| U-AK27 | AK #130 `abilityContext` | §4 声明面表「运行时上下文对象」行（属性值未知值 + `VALUE` 级 drop）+ §12 的 `TS28`行 | B |
| U-AK28 | AK #133 `async` 标记 | §5.2 的「**`async` 函数 / `async` 箭头**」行（返回值包成 Promise 的形状完整）；**本体已完整降级**：残留的「`await` 的**微任务时序**」记 `SEMANTICS` 级 drop | B |
| U-AK29 | AK #137 `yield*` | §5.2 表达式面表「`yield*`（委托）」行（展开成迭代协议循环） | A |
| U-AK30 | AK #140 ambient `.d.ts` / `.d.ets` | §10 改动清单「`FE/arkts/ArktsTacFrontend.java`」行（跳过 ambient `.d.ts`/`.d.ets` + 报文件级 `PARSE_PROBLEM`） | A |
| U-AK31 | AK #143 正则字面量 | §5.2 表达式面表「正则字面量」行（`CONST.REGEX`）+ §9 的「常量表新增」段落 | A |
| U-AK32 | AK #144 `super` | §5.3.9 小节「`extends`（类继承）」的「`super` 的另外两种形态」段 + §4 声明面表「类继承 `extends`」行 | A |
| U-AK33 | AK #146 `using` / `await using` | §6.7 小节「`try / catch / finally`」的「`using` / `await using`（显式资源管理）」段（合成 finally + `resourceVars`） | A |
| U-AK34 | AK #150 `@Observed` / `@ObjectLink` 观察语义 | §5.3.6 的「状态管理装饰器的两类边」表（观察边不可静态表达 ⇒ 不建边）+ §12 的 `TS26` | C |
| U-AK35 | AK #154 `setTimeout` 回调 | §5.2 表达式面表「回调形参位置」行（回调体按闭包降级；触发时机不可静态表达）+ §12 的 `TS22`行 | B |
| U-AK36 | AK #162 `calleeParamTypes` | §9 wire 表「`calleeParamTypes`」行 + §10 改动清单「`FE/sidecar/SidecarTacMapper.java`」行 | A |
| U-AK37 | AK #165 模块级语句 | §4 声明面表「模块级语句」行（模块级合成函数 `<module>.__top/0`） | A |
| U-AK38 | AK #167 项目里的 `.ts` 文件 | §10 改动清单「`FE/arkts/ArktsTacFrontend.java`」行（`.ts` 只参与类型解析、不被翻译） | A |

---

## 附录 C. 两面台账小计与 IR 引用自查

| 语言面 | 审计台账来源 | A 节行数 | `KEPT` | 有落点 | 带 `⚠` | 本轮补齐 A | 本轮补齐 B | 本轮补齐 C | 未覆盖编号 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| TypeScript / JavaScript | `08-typescript.md` `## A.` 节（`### A.1`–`### A.21`） | 192 | 18 | 192 | 0 | 74 | 4 | 13 | 无 |
| ArkTS | `09-arkts.md` `## A.` 节（`### A.1`–`### A.22`） | 167 | 38 | 167 | 0 | 42 | 6 | 2 | 无 |
| **合计** | — | **359** | **56** | **359** | **0** | **116** | **10** | **15** | **0 行 / 0 个编号** |

**「有落点」的口径**：台账的每一行都在本文档里有一个**真实存在**的落点（章节号 + 表行原文片段，可用脚本逐条 grep 校验，见本节末「IR 引用自查」）。**「本轮补齐 A / B / C」三列只统计原先那 141 行**（TS 91 + ArkTS 50）按补齐后落点的分类：**A** = 完整表达（构念有运行期语义且能落进 TAC）、**B** = 显式记账（运行期语义只能部分表达 ⇒ 正文给出保守形态**并显式记一条 drop**）、**C** = 永久例外（纯类型层计算 / 事实不在源码里 ⇒ §12 逐条论证）。其外 218 行的落点沿用上一版，未改动。

**行数与审计自身 §E 的计数一致**：TS 面 `checked 192`（191 条构念行 + 1 条协议能力行）、`KEPT 18` / `DEGRADED 62` / `DROPPED 111`（silent 39 / noted 72）；ArkTS 面 `checked 167`（163 条构念行 + 4 条 `N/A` 行）、`KEPT 36` + 2 条混合行、`DEGRADED 62`（silent 48 / noted 14）、`DROPPED 65`（silent 40 / noted 25）。ArkTS 的两条混合行（`constructors` = KEPT 体 / DEGRADED-SILENT 签名；`throw` = KEPT 指令 / DROPPED-SILENT 边）在本附录里按**斜杠前的状态**记 ⇒ `KEPT` 38、`DEGRADED-SILENT` 47、`DROPPED-SILENT` 39；按审计「按斜杠后状态计」的规则则分别是 36 / 48 / 40。⇒ 两个语言面的台账**逐行可核对**，覆盖数字可与其他语言面横向比较。

**本轮补齐的缺口的形状（可机械统计）**：原先 141 个 `⚠` 行里，**类型层 / 声明层**占多数 —— 条件类型、映射类型、索引访问类型、模板字面量类型、`keyof`、`infer`、DNF、`readonly`、可见性修饰符、`override`、参数属性、`implements`、接口继承与签名、非空断言、类型谓词；**运算符层**次之 —— 一元 `!`/`~`/一元 `-`、前缀 `++`/`--`、`void`、逗号运算符、复合赋值（`&&=`/`||=`/`??=`）、可选调用 `?.()`、可选下标 `?.[`；**模块 / 内建层**再次 —— 静态 `import`/`export` 与再导出、匿名默认导出、CommonJS 互操作、模块级语句拆分、正则字面量、`using`、`fetch` / `JSON.parse` / `localStorage` 的污点与 sink 语义、`calleeParamTypes`、ArkTS 的 `@Concurrent`/`@Sendable`/`@Observed`/`@ObjectLink`/`ForEach`/`IDataSource`。⇒ 这三类在本轮**全部**落到了 §4 / §5.2 / §6 / §7 的表行里；其中**纯类型层计算**（条件类型、映射类型、索引访问类型、模板字面量类型、`keyof`、`infer`、DNF、`const` 类型参数）与**纯类型级导入导出**按「没有运行时对应物」归 **C**（§12 的 `TS11`–`TS19`），其余归 **A**；**B** 只留给「运行期语义只能部分表达」的 10 条（`eval` / `new Function` / 定时器时机 / `fetch` 响应体 / 内建污点分类 / ArkTS 的 `@Provide`-`@Consume` 链接 / `@Observed`-`@ObjectLink` 观察边 / `@Concurrent`-`@Sendable` 跨线程顺序 / `abilityContext` 属性值）。

**IR 引用自查**：本附录只引用本文档**已登记**的 IR 编号 —— `IR-A`..`IR-Q`（`01`/`02` 已定稿段；`01` §10.2 全局登记表里跳过的 `IR-O` 本文档不使用）、`IR-R`..`IR-Z`（本文档 §8 的 9 条）、`IR-AT`（本文档 §8 的第 10 条）；实际在附录正文里出现的只有 `IR-P`、`IR-R`、`IR-S`、`IR-T`、`IR-V`、`IR-W`、`IR-X`、`IR-Y`、`IR-Z`、`IR-AT`。**本附录未引入任何新的 IR 编号**：141 个原缺口的承载能力（IR-B `TacDrop`、IR-C `TacGuard`、IR-E `ownerType` + 既有 `FIELDLOAD`/`FIELDSTORE`、IR-F `tryRegions`、IR-G `labelRef`、IR-P 真实 callee 键、IR-Q 引擎侧相等语义规则表、§3 的 P2 三类例外）都已具备 —— 缺的是**本文档的落点与取舍声明**，不是 IR 表达力。**需要新 IR 提案的条目：0 个**。

**本轮补齐引入的 IR 变化：只有 4 处「既有提案的字段级扩展」，一律不占新编号**（§8.1 专表）：**IR-Y 的字段扩展**（`typeParameters` 带 `constraint` / `defaultType`）、**IR-W 的字段扩展**（形参元数据带 `restParamIndex`）、**IR-E 的字段扩展**（`fields` 带 `readonly` / `visibility` / `override` 标记）、**IR-F 的字段扩展**（`resourceVars` 从「恒空」变成「可非空」）。另有两处**不占编号的表级新增**：`CONST` 表末尾追加 `REGEX`（正则字面量）、`GLOBAL_OWNERS` 常量表补 `fetch` / `localStorage` / `setTimeout` / `setInterval` / `eval` / `Function` 等名字。**IR 引用自查结论：附录里出现的 IR 编号仍然只有 `IR-P`、`IR-R`、`IR-S`、`IR-T`、`IR-V`、`IR-W`、`IR-X`、`IR-Y`、`IR-Z`、`IR-AT` 这 10 个，全部已登记；新增编号 0 个。**
