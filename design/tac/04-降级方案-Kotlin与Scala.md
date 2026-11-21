# 降级方案：Kotlin 与 Scala

> 上游：`01-TAC-IR-扩展设计.md`（IR v2，已定稿）、`02-覆盖矩阵与不可支持项.md`（覆盖矩阵 A–E、提案 IR-A..IR-Q）。本文只做**前端降级方案**，不重新设计 IR；IR 缺口集中在 §10。
> 立场（用户口径）：**TAC 应当支持全部语言的全部特性；确实无法支持的，必须给出理由。**

**路径**（均在 `plumb-frontend/src/main/java/org/plumb/tac/frontend/` 下）：`KTT`=`kotlin/KotlinTacTranslator.java`；`KTF`=`kotlin/KotlinTacFrontend.java`；`KTM`=`kotlin/KotlinTypeMapper.java`；`ST`=`scala/ScalaTacTranslator.java`；`STF`=`scala/ScalaTacFrontend.java`；`STM`=`scala/ScalaTypeMapper.java`；`IR`=`plumb-tac/src/main/java/org/plumb/tac/ir/`；`FE`=`plumb-tac/src/main/java/org/plumb/tac/frontend/`（**注意 `TacFrontend.java` 在 `FE` 下，不在 `IR` 下** —— 它是 SPI 接口，不属于 IR 本体）。

**IR 记号**（§4/§5/§11 的「IR」列）：`A`=IR-A 精度档位；`B`=IR-B 丢弃记录；`C`=IR-C 守卫；`D`=IR-D 值域；`E`=IR-E 声明面元数据；`F`=IR-F 异常区域；`G`=IR-G 标签；`I`=IR-I 分支区域；`J`=IR-J 抛出类型；`K`=IR-K 类型三维度；`L`=IR-L 挂起恢复；`N`=IR-N 类型声明；`P`=IR-P callee 规范；`IR-AA`/`IR-AB`/`IR-AC`/`IR-AD`=§10 新增；`X`=现有 IR 即可。

---

## 1. 目标与非目标

**目标**：①全部构念在 TAC 中**完整表达**（判定标准取 `02` §0 四条）；②每构念给出**可验证的 TAC 形态**并指明落在哪条 IR 能力上；③IR v2 之外的新增能力集中在 §10（4 条，全是「加字段」或「枚举末尾追加常量」，**不新增 opcode / operand 变体 / `TacType.Kind`**，`01` §0.2）；④§12 给出可执行门禁。

**非目标**：不重新设计 IR（`01` §0.1–§0.5）；不引入语言专用 opcode（`FE/TacFrontend.java:51-57` 禁止）；不实现引擎侧 checker（只点名消费方）；不处理其余 8 条语言线。

---

## 2. 两条线的现状差距

缺口**形状不同**，不能用同一套修法：Kotlin 是「声明面整类缺失 + 赋值缺失 + 控制流缺失」，Scala 是「声明面部分缺失 + 表达式值缺失 + 模式结构缺失」。

| 维度 | Kotlin | Scala 3 |
|---|---|---|
| 声明面 dispatch | `translateFile` 只取 `KtClassOrObject`+`KtNamedFunction`（`KTF:455-468`）；`translateClass` 成员只取 `KtNamedFunction`（`KTF:501-507`）⇒ 类属性/`init`/访问器/伴生对象/枚举条目/值类**从未被访问** | `collectDefs` 收所有 `DefDef`（`ST:309-324`），成员面完整；但 **Template 级 `ValDef`**（字段初始化器/`given`/`lazy val`/枚举 case 值）不是 `DefDef`⇒同样从未被访问 |
| `this` | `THIS="this"` 只有常量（`KTT:1933`），**从未**入 `locals`（只写参数 `KTT:252`、局部属性 `KTT:432`/`KTT:443`）⇒ `translatePropertyRead` 的 `locals.get(THIS)` 恒 null（`KTT:896-900`）⇒ 属性读产生**无定义指令的临时量** | `thisReceiver` 真创建并注册名为 `"this"` 的局部量（`ST:1250-1261`）⇒ 本来就对 |
| 赋值 | 赋值是 `KtBinaryExpression`，落进「比较/算术当语句」分支（`KTT:378-384`）⇒**一条 store 都不发** | `translateAssign` 发 `MOVE`/`FIELDSTORE`（`ST:1689-1741`、`ST:1753-1766`）⇒ 本来就对 |
| 控制流 | `while`/`do-while`/`for` 全落进兜底（`KTT:393-406`）⇒**没有回边** | `while` 有回边（`ST:1816-1842`）；`match` 有分支链（`ST:1876-2000`） |
| 闭包 | lambda **完全不降级**（`KTT:764-821`），实参换 null 常量（`KTT:1556`） | 体=合成函数+`CALL`（`ST:2196-2241`）；捕获=共享 holder cell（`ST:157-173`、`ST:719-727`、`ST:903-947`） |
| 分派兜底 | 落兜底会记问题（`KTT:807-819`），但「赋值误分类成比较」**静默** | 落兜底会置 `degraded`+记结构化问题（`ST:1028-1037`）⇒ 至少可见 |
| 精度标记 | `SOURCE_FULL` 起步（`KTT:236`），`when` 明确**拒绝**降级（`KTT:557-563`） | `SOURCE_FULL` 起步（`ST:358`、`STF:108-114`），`match` 一律降级（`ST:1877`） |

---

## 3. 完整覆盖原则与允许的例外

| # | 原则 | 落地口径 |
|---|---|---|
| P1 | **可表达** | 运行期语义由「指令流+CFG+元数据」完整刻画；**不得**用 `UNKNOWN`/`HAVOC` 代替 |
| P2 | **不撒谎** | TAC 里不得出现与源码语义相反的东西（如把「多条件只测第一个」写成真） |
| P3 | **可追责** | 确实无法静态刻画的，必须有 `TacDrop`（`B`）且精度随之下调（`A`） |
| P4 | **不留静默丢弃** | 「没做」的地方要么有完整 TAC 形态，要么有 `TacDrop`；不得「既无 TAC 又无 drop」 |
| P5 | **与 IR v2 对齐** | 优先用既有 opcode 组合；需要新形状的写进 §10 |

允许的例外**只有四类**（`02` §3 的四类根因）：**根因一** 结果由运行期决定；**根因二** 并发/调度决定；**根因三** 求解器侧能力（与前端无关）；**根因四** 无运行期对应物。**禁止**用「UNKNOWN/HAVOC+记 drop」冒充完整支持。

> ⚠ **编号约定**：本节用「根因一~四」指 `02` §3 的四类根因，**不用 `E1..E4`**——
> `03`（Java）文档的 `E1..E6` 指的是 **Java 侧的构念例外**，两者含义不同。
> 跨文档引用一律写成「`03` E3」这种带文档前缀的形式。

---

## 4. 声明面方案

### 4.1 Kotlin

| 构念 | TAC 形态 | `memberKind` | 注解通道 | IR |
|---|---|---|---|---|
| 类/接口/密封类 | 每成员一个 `TacFunction`（`enclosingClass`=类 FQN）+`TacTypeDecl{fqn,supertype,interfaces,permittedSubtypes,members}` | `METHOD` | `classAnnotations` | `E`,`N` |
| 对象 `object` | 同类；状态=该类型的字段（带 `ownerType`）；初始化=`STATIC_INITIALIZER` | `STATIC_INITIALIZER` | `classAnnotations` | `E` |
| 伴生对象 | 成员 `enclosingClass`=**外层类 FQN**；`Holder.make()`→`CALL <外层类>.make/0`（owner 归一见 §10） | `COMPANION_MEMBER` | `classAnnotations` | `E`,`P` |
| 枚举类 | `TacTypeDecl(isEnum=true)`；条目=合成访问函数，体 `NEW <EnumType>`；`E.A`→`CALL <Enum>.A/0`；`values()`→`NEWARRAY` | `ENUM_ENTRY`（**`IR-AB`**） | `classAnnotations` | `E`,`IR-AB` |
| 数据类 | 构造器+**真实体**：`componentN`=`MOVE`(字段)、`copy`=`NEW`+`FIELDSTORE`、`equals`=逐字段 `BINOP EQ`、`hashCode`=组合、`toString`=`BINOP ADD` | `CONSTRUCTOR`/`METHOD` | `methodAnnotations` | `X` |
| 值类 | 运行期表示=被包装类型⇒构造器=`MOVE`（**不** `NEW`）、访问器=恒等 `MOVE` | `CONSTRUCTOR`/`PROPERTY_ACCESSOR` | — | `E` |
| 注解类 | `TacTypeDecl(annotations=[...])`；注解成员=`PROPERTY_ACCESSOR` | `PROPERTY_ACCESSOR` | `classAnnotations` | `E` |
| 类型别名 | **无运行期效果⇒不产生函数**；`TacDrop(STRUCTURE,"typealias")`→`programDrops` | — | — | `B` |
| 顶层 `val`/`var` | 合成 `FIELD_INITIALIZER`（体=`MOVE`+`FIELDSTORE ownerType=<文件级 holder>`）；读=`CALL` 该函数 | `FIELD_INITIALIZER` | `fieldAnnotations` | `E` |
| 类属性 `val`/`var` 与自定义 getter/setter | backing field=`FIELDLOAD`/`FIELDSTORE`（带 `ownerType`）；访问=合成 getter/setter（自定义访问器的体=用户代码）；`val` 无 setter | `GETTER`/`SETTER` | `fieldAnnotations`/`methodAnnotations` | `E` |
| 委托属性 `by lazy`/`by Delegates.observable` | 读=`CALL <delegate>.getValue(this,prop)`；写=`CALL <delegate>.setValue(this,prop,v)` | `PROPERTY_ACCESSOR` | `fieldAnnotations` | `E` |
| `lateinit` | backing field+合成布尔字段 `<p>$init`；读前 `BRANCH` 检查，未初始化则 `THROW ctorType=kotlin.UninitializedPropertyAccessException`（`IR/TacInstruction.java:100-102` 禁止映射成 `NULL_CHECK`） | `GETTER` | — | `E`,`J` |
| `const val` | 编译期常量⇒**使用点直接内联常量**；`TacDrop(STRUCTURE,"const.inlined")` | — | — | `B` |
| `init` 块 | 构造器体内的一段 `FIELDSTORE` 序列（参数赋值之后） | `FIELD_INITIALIZER` | — | `E` |
| 主/次构造器 | 每个构造器一个 `CONSTRUCTOR`；体=`FIELDSTORE`(参数→字段)+`init` 块+属性初始化器 | `CONSTRUCTOR` | `paramAnnotations` | `E` |
| 嵌套类 / 局部类 / 匿名对象 | 各自合成类型（FQN 带外层类名或 `$local`/`$anon$N`）+`CONSTRUCTOR` | `METHOD`/`CONSTRUCTOR` | `classAnnotations` | `E` |
| 扩展函数 / 扩展属性 | 接收者=**首个 TAC 形参**；`CALL <owner>.ext/1 args=[recv,...]`；属性读=合成 `GETTER` | `EXTENSION_FUNCTION`/`PROPERTY_ACCESSOR` | `methodAnnotations`/`fieldAnnotations` | `E`,`P` |
| 挂起函数 | 状态机展开：按挂起点切段，每段一个合成函数；挂起点挂 `Effect.SUSPEND`；函数级 `resumePoints` | `METHOD` | — | `L` |
| 内联函数 / `reified` | 声明侧同普通函数；**调用点做展开**（S8）；展开前 `TacDrop(SEMANTICS,"inline.notExpanded")`；`T::class`→`CLASS("java.lang.Class")`，类型实参被擦除⇒该值 `TacDrop(VALUE)` | `METHOD` | — | `X`（§13 的 `KS3`） |
| 尾递归 | 标注即可；TAC 的 `JUMP` 回边就是尾递归形态 | `METHOD` | — | `X` |
| 操作符重载/中缀 | `OPERATOR`；调用点 `CALL <owner>.plus/2`（现状是 `BINOP`，`KTT:1759-1778`） | `OPERATOR` | — | `E`,`P` |
| 局部函数 | 合成函数，名字=`<enclosing>.<name>$local`；调用点用**同一名字**（现状两边不一致⇒调用图边指向不存在的函数） | `METHOD` | — | `X` |
| 上下文接收者 | 作为**前导** TAC 形参 | `METHOD` | — | `X` |
| **可见性修饰符**（`public`/`internal`/`private`/`protected`） | `KTF:722-851` 的 `translateFunction` **不区分 `internal` 与 `public`** ⇒ `internal`（模块作用域）与 `public` 不可区分 ⇒ **跨模块分析无法利用可见性**。完整形态：`visibility` 进 `TacMemberRef`（`IR-E` 的**可见性分级**：`PUBLIC/PROTECTED/INTERNAL/PRIVATE/PACKAGE` 枚举，**不是** `boolean isPublic` —— 布尔会把 `internal` 与 `private` 混在一起）；`TacFunction` 侧同步一个可选 `visibility` | `METHOD` | — | `E`（可见性分级） |
| **成员修饰符**（`override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion`） | `KTF:722-851` 的 `translateFunction` **不读任何成员修饰符**（审计 `02-kotlin.md:56`-`:59`：`override` 是 `DEGRADED`「the keyword is invisible; only VTA points-to compensates」、`open` 与 `final` 是 `DROPPED-SILENT`「invisible — the function looks final」⇒ **今天 `open` 的函数在 TAC 里与 `final` 完全一样**）。**目标形态**：**零指令**（成员修饰符不改变指令流）⇒ 不产生 drop；事实落 `TacMemberRef.modifiers`（**IR-E 的补充细化**，与 §4.1「可见性修饰符」行**共用同一个通用修饰符列表**，**不占新编号**）：`override` ⇒ 该成员**覆写**父类成员 ⇒ 父类调用点必须能看到 override（与 §5.1「操作符重载」行同款：调用点发**一组** `CALL`，覆盖静态声明 + 已知 override，`calleeSig` 用各自 owner FQN，IR-P）；`open` ⇒ 该成员**可被覆写** ⇒ 同上；`final` ⇒ **不可被覆写** ⇒ 父类调用点**只**发静态声明那一条 `CALL`（⇒ 派发集合确定，不产生「可能到达未知实现」的过近似）；`abstract` ⇒ **无方法体** ⇒ 零指令、声明进 `TacTypeDecl.members`；`data`/`sealed`/`inner`/`companion` ⇒ 由 §4.1 已有行（数据类合成成员 / 密封类 `permittedSubtypes` / 内部类 `enclosingClass` / 伴生对象 `COMPANION_MEMBER`）承担，**不重复记** | `METHOD` | — | `E`（细化 `TacMemberRef.modifiers`） |

### 4.2 Scala 3

| 构念 | TAC 形态 | `memberKind` | 注解通道 | IR |
|---|---|---|---|---|
| 类 / trait | `TacTypeDecl`；成员 `enclosingClass`=类 FQN；trait `isInterface=true`；自类型 `{ self: A => }`=前导隐式形参 | `METHOD` | `classAnnotations` | `E`,`P` |
| object（模块类/package object） | `TacTypeDecl`（FQN 含 `$`）；成员静态化⇒`ownerType`=模块类；初始化=`STATIC_INITIALIZER` | `STATIC_INITIALIZER` | — | `E` |
| enum | 条目=合成访问函数（体 `NEW`）；`values`=`NEWARRAY` | `ENUM_ENTRY`（`IR-AB`） | — | `IR-AB` |
| case class | 构造器+`apply`/`unapply`/`copy`/`toString`/`equals`/`hashCode`/`_N` 全部**真实体**；`unapply` 返回合成元组（`NEW scala.Tuple2`） | `CONSTRUCTOR`/`METHOD` | — | `E` |
| 伴生对象 | 同 Kotlin | `COMPANION_MEMBER` | — | `E` |
| opaque type / 类型别名 | 无运行期效果⇒`TacDrop(STRUCTURE)`；别名关系记 `TacTypeDecl` | — | — | `B` |
| `given` | 合成 `FIELD_INITIALIZER` 返回该值；`summon[T]`→`CALL <given>`；隐式实参在调用点解析后**显式传参** | `FIELD_INITIALIZER` | — | `E`,`P` |
| 扩展方法 | 接收者=首个 TAC 形参 | `EXTENSION_FUNCTION` | — | `E`,`P` |
| `inline`/`transparent inline`/`inline match` | 同 Kotlin（展开=S8） | `METHOD` | — | `X` |
| 字段初始化器（类体里的 `val x = expr`） | `FIELD_INITIALIZER`（体=`MOVE`+`FIELDSTORE ownerType`），在构造器里调用 | `FIELD_INITIALIZER` | `fieldAnnotations` | `E` |
| `lazy val` | 同上+合成 `GETTER`（惰性初始化 `BRANCH`+持有者字段） | `GETTER` | — | `E` |
| 枚举 case 值 | 同枚举条目 | `ENUM_ENTRY` | — | `IR-AB` |
| 多参数表/`implicit`/`using` | **全部展平**成一个 TAC 形参表（现状 `buildParams` 只取第一张表，`ST:423-438`⇒后续表的形参被读成 `this.b`） | `METHOD` | `paramAnnotations` | `E`,`P` |
| by-name 形参 `x: => T` | 降级为**函数值**：合成零参函数+每次使用一个 `CALL` | `METHOD` | — | `X` |
| 局部 `def`/局部类 | 合成函数；名字取**符号全名**（与 Scala 闭包已有做法一致，`ST:2259-2276`） | `METHOD` | — | `X` |
| **类/trait 修饰符**（`final`/`sealed`/`abstract`/`case`） | **现状**：`ScalaTacTranslator.java:554-571` 只读三种修饰符（`Flags.Synthetic` `:555`、`Flags.CaseClass`/`Flags.CaseVal` `:570`、`Flags.ModuleClass` `:571`）⇒ **`final`/`sealed`/`abstract` 今天不被读取**（本轮实测：全文件无 `Flags.Final`/`Flags.Sealed`/`Flags.Abstract`）。**目标形态**：① `sealed` ⇒ `TacTypeDecl.permittedSubtypes`（**IR-N 既有字段**）= **同一编译单元内**的直接子类型 —— dotc 要求 `sealed` 的子类与它**同文件** ⇒ 这份清单**静态可数**（不是运行期事实）⇒ 直接供 §6.3 的 `exhaustive` 判定与 `missingCases`；另加 `isSealed`（**IR-N 的补充细化**，不占新编号）以区分「sealed 但本单元内无子类」与「非 sealed」；② `abstract` ⇒ `TacTypeDecl.isAbstract`（**IR-E 既有**）；③ `final` ⇒ `TacTypeDecl.isFinal`（**IR-E 的补充细化**，不占新编号）—— 运行期**零指令**，它约束的是「可否被继承/覆盖」这一**编译期**事实；④ `case` ⇒ **不单独记**：由 `TacTypeDecl.members` 里存在 `apply`/`unapply`/`copy` 推出（IR-E） | — | 类级 | `N` + `E`（两个补充细化，不占新编号） |

---

## 5. 表达式面方案

### 5.1 Kotlin

| 构念 | TAC 形态（伪 TAC） | 结果类型 | IR |
|---|---|---|---|
| 各种数值/字符字面量（十进制/十六进制/二进制/下划线/无符号/转义） | `MOVE dest=t lhs1=<const>`；`0xFF`=255、`1_000`=1000、`42u`=无符号 32 位（现状遇 `NumberFormatException` 返回 null，`KTT:1421-1426`） | INT/LONG/… | `D` |
| 字符串模板 | `BINOP ADD` 链（字面量段与插值值交替），现状 `KTT:1269-1308` 已是 | STRING | `X` |
| `if` 表达式 | §6.4 | 两臂公共类型 | `C` |
| `when`（subject / subject-less / `is` / 绑定 / 守卫 / `in`） | §6.1、§6.2 | 分支公共类型 | `C`,`I` |
| lambda/匿名函数 | 合成函数 `L$<n>`（闭包形参在前、捕获形参在后）+`CALL L$<n>/k`；与 Java lambda 同一模式（`plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java:1564-1592`） | `CLASS("kotlin.Function")` | `X`（§8） |
| 高阶函数（lambda 传给别的函数） | 已识别的高阶操作⇒**展开成显式循环**（§6.7）；未识别⇒§13 的 `KS1` | — | `X`/§13 的 `KS1` |
| 函数引用 `::f` | 立即应用 `f::(x)`→`CALL f/1`；作为值传递⇒§13 的 `KS1` | `CLASS("kotlin.Function")` | §13 的 `KS1` |
| 可空调用 `?.` / Elvis `?:` | §6.8 | 接收者类型（可空）/ 左操作数非空类型 | `C` |
| `!!` | `NULL_CHECK dest=t lhs1=x`（`IR/TacInstruction.java:104-107` 点名 `!!` 式收窄是 `NULL_CHECK` 的用途；现状 `KTT:764-821` 整个丢掉） | 非空类型 | `X` |
| `as`/`as?` | `UNARYOP CAST castType=T`（`IR/TacInstruction.java:198`、`IR/UnaryOperator.java:38`）；`as?` 需「失败即 null」的 CAST 形态⇒**IR 需求 `IR-AD`** | 目标类型 | `IR-AD` |
| `is`/`!is` | `INSTANCEOF dest=t lhs1=x ctorType=T`+`BRANCH` 带 `TacGuard.TypeTest` | BOOLEAN | `C` |
| 区间 `..`/`..<` | `NEW dest=t ctorType=kotlin.ranges.IntRange args=[start,end]`（`..<` 用 `end-1`） | CLASS | `X` |
| `in`/`!in` | `CALL <Collections owner>.contains/2 args=[collection,x]`→BOOLEAN；`!in` 取反 | BOOLEAN | `P` |
| 集合字面量 `listOf(1,2)` | `CALL <符号解析出的 owner>.listOf/2`（owner 由符号给出，**不猜**；现状是无 owner 的裸名，`KTT:1516`） | CLASS | `P` |
| 集合操作链 `xs.map{}.filter{}` | 识别出的操作⇒展开成 TAC 循环（§6.7）；未识别⇒`CALL`+`UNINTERPRETED` | 集合类型 | `P`/§13 的 `KS1` |
| 数组下标 | `ARRAYLOAD`/`ARRAYSTORE`（现状 `KTT:764-821` 落兜底） | 元素类型 | `X` |
| 作用域函数 `let`/`run`/`also`/`apply`/`with` | 展开成显式块：`let{it->body}`→`MOVE dest=it lhs1=recv`+body；`apply`→`MOVE dest=recv`+body | — | `X` |
| `require`/`check`/`error`/`TODO()` | `CALL`+`THROW ctorType=java.lang.IllegalArgumentException`/`IllegalStateException` | — | `J` |
| `requireNotNull`/`checkNotNull` | 现有 `NULL_CHECK`+`CALL`（`KTT:1461-1491`），保留 | 非空 | `X` |
| 自增/自减 `x++` | `BINOP ADD`+`MOVE`（`x++` 先取值后写） | — | `X` |
| 复合赋值 `x += v` / 赋值 `x = v` | `MOVE dest=x lhs1=(BINOP x v)` / `MOVE dest=x lhs1=v`；属性⇒`FIELDSTORE ownerType=` | — | `E` |
| 智能转换（smart cast） | 非空性部分：`NULL_CHECK`（**完整**）；类型收窄部分⇒§13 的 `KS5` | — | §13 的 `KS5` （**已覆盖**：**IR-Z**，`01` §10.2 已登记 ⇒ 不再是例外） |
| `this`/`super`/`this@label` | `this`=名为 `"this"` 的局部量（Kotlin 侧**要补注册**，常量在 `KTT:1933` 但从未入表）；`super.f()`=`CALL` 的 `args[0]` 放 super 接收者（现状是 null 常量，`KTT:944`） | — | `X` |
| 中缀 `a pow2 b` / 用户类型 `a + b` | `CALL <owner>.pow2/2` / `CALL <owner>.plus/2`（现状 `KTT:1191-1199` 记「unsupported binary operator」） | — | `P` |
| **展开实参（spread，`f(*arr)`）** | 现状 `KTT:1548-1559` 把 `*arr` 当成**一个**数组实参传（审计 `02-kotlin.md:47`：「passed as one array argument (`va/1 args=[array]`), not spread」）⇒ 被调方收到的是**一个数组**，不是**展开后的元素序列** ⇒ 调用点的实参数与被调方的形参数**对不上**，跨过程污点流在展开调用处**整体错位**。**目标形态**：调用点把被展开的数组**逐元素**放进 `CALL.args`（元素个数由**静态可数**的数组类型/长度得出 ⇒ 可数则展开；不可数（`Array<out T>` 的长度未知）⇒ 记 `drop(VALUE, "spread.dynamic-length")` 并按「变长实参」的保守形态处理：形参侧按 **IR-AO 的 `List<TacParameter>` 变长标记**（§10）承载，调用侧实参数 = **变长部分**）。变长实参的个数与被调方**变长形参**的对应关系由 IR-P 的「元数只数显式形参」口径给出：变长形参**不计入** `calleeSig` 的元数 | 变长形参的元素类型 | `P`,`IR-AO` |

### 5.2 Scala 3

| 构念 | TAC 形态（伪 TAC） | 结果类型 | IR |
|---|---|---|---|
| 字面量 | `MOVE`；`Char` 必须走 `charValueOf`（dotc 把 `Char` 装箱成 `java.lang.Character`，`ST:1052-1063`） | — | `D` |
| 字符串插值 `s"..."`/`f"..."`/`raw"..."` | dotc 把**字面量段与插值值**都包进 `Trees.SeqLiteral`（现状不识别）⇒拆开后建 `BINOP ADD` 链 | STRING | `X` |
| `if` 作为表达式 | §6.4（现状 `translateIf` 末尾 `return null`，`ST:1806`⇒两臂的值全丢） | 两臂公共类型 | `C` |
| `match` | §6.3 | 分支公共类型 | `C`,`I`,`IR-AA` |
| 模式定义 `val (a,b) = t` | `MOVE` 到 `a`/`b`（元组 `_1`/`_2` 读取） | — | `X` |
| **复合字面量 / 元组字面量** `(1, 2)` | dotc 把 `(1, 2)` 编译成 `scala.Tuple2` 的构造（`Apply` + `TypeApply`）；现状 `translateExpr`（`ST:955-1038`）**没有这一分支** ⇒ 审计标 `NOT FOUND`（`03-scala.md:53`）⇒ **整条构念不产生任何指令**（连值都丢）。目标形态：`NEW scala.Tuple2`（`ctorType`=`CLASS("scala.Tuple2")`）+ 逐组件 `FIELDSTORE`（`$1`/`$2`）；**分量类型**取各元素类型（与 §5.2「模式定义」行的 `_1`/`_2` 读取对齐）；`calleeSig` 用**剥掉类型实参后的 FQN**（IR-P） | `CLASS("scala.Tuple2")` | `X`,`P` |
| lambda/占位符 `_ + 1` | 合成函数+`CALL`（现状 `ST:2196-2241` 已是；占位符=普通闭包形参） | — | `X` |
| eta-expansion `f _`/部分函数 | 立即应用⇒`CALL`；作为值⇒§13 的 `KS1` | — | §13 的 `KS1` |
| 块 | 最后一个表达式的值（现状 `ST:1066-1075` 已是） | — | `X` |
| `this` | `thisReceiver` 创建名为 `"this"` 的局部量（`ST:1250-1261`），保留 | — | `X` |
| `Option`/`Either`/`Try` | 普通类引用（`STM:43-46` 明确 `Option` **不是**空安全机制，保留） | CLASS | `X` |
| 命名实参 `d(a = 1, b = 3)` | 按**声明序**重排实参（现状 `Trees.NamedArg` 落兜底⇒调用零实参） | — | `P` |
| 范围 `to`/`until` | 普通 `CALL`（`scala.collection.immutable.Range.*`） | — | `P` |
| `super` | `CALL` 的 `args[0]` 放 super 接收者（现状 `ST:1030-1037` 落兜底） | — | `X` |
| **运算符面**（`+`/`-`/`*`/`/`/`%`/`&`/`\|`/`^`/`<<`/`>>`/`>>>`/`==`/`!=`/`<`/`<=`/`>`/`>=`/`&&`/`\|\|`/`::`/`:::`/`++`/`++=`/`->`/`<-`/`?`/`!`） | `binaryOperator`（`ST:1567-1622`）是一张**固定名字表**：表内的名字⇒`BINOP`（`:1569-1618`）；表外的名字（`::`/`:::`/`++`/`++=`/`->`/`<-`/`?`/`!`）⇒ 返回 `null` ⇒ **整条运算符被丢掉**。更要紧的是表内的三处**语义错误**（审计 `03-scala.md:75`）：① `&&`/`\|\|` 映到 `LAND`/`LOR`（`:1615-1618`）⇒ **不短路**（两侧无条件求值）——与 Java 侧同一类缺陷；② `\|` 无条件映到 `OR`（`:1587-1589`）⇒ **按位或与模式备选共用一个 opcode**（审计原话：「a bitwise-or and a pattern-alternative share one opcode」）；③ `^` 映到 `XOR`（`:1590-1592`），但 `^` 也可能是**用户类型的重载**⇒ 静态绑定丢失 override。**目标形态**：① `&&`/`\|\|` ⇒ **分支链**（与 §6.1 的 `when` 同一套 `BRANCH` + `guard` 模板；短路语义必须可见，否则「先校验后使用」类规则在该表达式上不成立）；② `\|` ⇒ **按上下文分流**：**按位或**（`Trees.Apply` 语境、两侧是整型）⇒ `BINOP OR`；**模式备选**（`Trees.Alternative` 语境）⇒ **类型层构念 ⇒ 零指令**，进 §6.3 的模式链（**不发 `BINOP`**）；③ `^`/`+`/`-` 等若是**用户类型的重载** ⇒ 按 §5.1「操作符重载」行的口径发 `CALL <owner>.<方法名>/2`（IR-P 真实方法名），否则才是 `BINOP`；④ 表外的名字逐个给出目标形态：`::`/`:::` ⇒ `CALL <owner>.::/2`（列表构造 / 字符串拼接）、`++` ⇒ `CALL <owner>.++/1`（序列拼接）、`++=` ⇒ `CALL <owner>.$plus$plus$eq/1`（序列追加）、`!` ⇒ `UNARYOP NOT`；⑤ `->`（右结合箭头，只出现在**类型**与 by-name 位置）、`<-`（for 推导式的生成器）、`?`（存在类型通配）**都是类型层 / 可去糖构念 ⇒ 零指令**：`<-` 在 §6.7 的 for 推导式里被**去糖成循环**，`->`/`?` 记 `drop(STRUCTURE, "type-level.operator")` | 两侧公共类型 | `C`,`P`,`B` |

---

## 6. 控制流方案

### 6.1 `when`（有 subject）

```
  subject = <expr>
  L_case1: t1 = BINOP EQ subject, v1        ; guard = Membership(subject,[v1,v2])
           BRANCH t1 -> L_body1, L_case2
  L_body1: x = MOVE subject                  ; TacCase.bindings = [(x,T,S,nullable)]
           ...body...  JUMP L_end
  L_case2: t2 = INSTANCEOF subject, T2       ; guard = TypeTest(subject,T2,false)
           BRANCH t2 -> L_body2, L_case3
  L_body2: y = MOVE subject   ...body...  JUMP L_end
  L_case3: ...else body...  JUMP L_end
  L_nomatch: THROW ctorType=kotlin.NoWhenBranchException   ; 仅当源码无 else
  L_end:  result = <merge temp>
```

- 多条件条目 `1, 2 ->`：**一条** `Membership(subject,[1,2])`，不是两次 `BINOP`（现状 `firstConditionOf` 只取第一个，`KTT:1823-1831`）。
- `is` 条目⇒`INSTANCEOF`+`TypeTest`，绑定用**收窄后的类型**+`MOVE`（现状整条分支被跳过并记 `UNSUPPORTED_CONSTRUCT`，`KTT:630-651`）。
- `in` 条目⇒`CALL contains`+`Truthiness`；subject-less 形式的条件⇒`Truthiness`（现状降级成常量 `false`，`KTT:829-874`⇒该分支静态不可达）。
- 绑定：`x is String ->` 的 `x` 用收窄类型；`when (y) { val z = f(y) -> }` 的 `z`=`MOVE`。
- `else` 可在源码中间⇒降级时**必须重排到链尾**，语义不变。
- **无 `else`**：源码在不匹配时抛 `NoWhenBranchException`⇒链尾必须 `THROW`，**不能**让结果临时量悬空（现状 `KTT:618` 分配临时量、`KTT:744-751` 从不赋值⇒引擎相信函数返回已定义值）。
- 整条 `when` 记 `TacBranchRegion{kind=WHEN,subject,cases,defaultCaseId,exhaustive,missingCases}`；`exhaustive` 由 sealed 层次（`N` 的 `permittedSubtypes`）判定。

### 6.2 `when`（无 subject）

`TacBranchRegion{kind=IF_CHAIN,subject=null}`；每个 case 的 guard=该条件的 `Truthiness`；`else` 排链尾。

### 6.3 `match`（Scala）

| 模式 | 判定 | 绑定 | 备注 |
|---|---|---|---|
| 类型 `case s: String` | `INSTANCEOF`+`TypeTest` | `MOVE`，类型收窄 | 雏形已有（`ST:1909-1939`） |
| 常量 `case 1` | `BINOP EQ`+`Compare` | — | 雏形已有（`ST:1940-1974`） |
| 绑定 `case x @ 1` | 先测常量 `1`，再 `MOVE x ← selector` | `x`=整个被匹配值 | 现状 `constantOf` 对 `Bind` 返回 null⇒走通配路径 |
| 元组 `case (a, b)` | `INSTANCEOF subject, scala.Tuple2` | `a=FIELDLOAD subject.$1`、`b=FIELDLOAD subject.$2` | 路径写进 `TacBinding.path`（`IR-AA`） |
| case class/提取器 `case P(a, b)` | `opt=CALL <owner>.unapply/1 args=[subject]`；`ok=CALL Option.isDefined/1 args=[opt]`；`BRANCH ok` | `a=FIELDLOAD opt.$1`、`b=FIELDLOAD opt.$2` | `unapply` 的 `CALL` 必须进 TAC（现状完全不在 TAC 里） |
| 备选 `case 1 \| 2` | 一个 `Membership`（多 guard，语义 OR） | — | 现状 `Trees.Alternative` 不识别 |
| 序列 `case Seq(a, b, rest*)` | 逐元素 `ARRAYLOAD`/迭代+长度守卫 | `a`/`b`/`rest` 各带 path | |
| 守卫 `case x if p` | 守卫失败**落到下一个 case** | — | 现状 `ST:1976-1992` 已是，但被通配路径抢在前面（`ST:1944-1952`） |
| 通配 `case _` | 无测试，直接落链尾 | — | |

致命缺陷：`constantOf` 只认裸 `Literal`（`ST:2048-2057`），其它模式全部走通配路径（`ST:1944-1952`）⇒**无条件匹配且后续 case 全被丢弃**、绑定被读成 `this.a`/`this.b`。本方案逐类消除该路径。穷尽性：`exhaustive` 由 sealed 层次+`permittedSubtypes`（`N`）判定，`missingCases` 列出未覆盖的子类型。

### 6.4 `if` 表达式

```
  cond = <expr>
  BRANCH cond -> L_then, L_else            ; guard = Truthiness(cond)
  L_then: r_then = <expr>   MOVE result, r_then   JUMP L_end
  L_else: r_else = <expr>   MOVE result, r_else
  L_end:   result
```

两个分支**都必须**写 `result`（Scala 现状 `ST:1806` 直接 `return null`⇒两臂的值全丢）。

### 6.5 循环 / break / continue / label

```
  L_loopHead:                              ; labelRef = LOOP_HEAD
              c = <cond>
              BRANCH c -> L_body, L_end     ; guard = Truthiness(c)
  L_body:    ...body...   JUMP L_loopHead
  L_end:                                    ; labelRef = LOOP_END
```

`for (x in xs)` 走**迭代协议**（IR-P 规定真实方法名，不得用合成短名）：

```
  it = CALL java.lang.Iterable.iterator/0 args=[xs]     ; L_loopHead:
  h = CALL java.util.Iterator.hasNext/0 args=[it]       ; BRANCH h -> L_body, L_end
  L_body: x = MOVE (CALL java.util.Iterator.next/0 args=[it])   ; ...body...  JUMP L_loopHead
```

- `do-while`：先执行体再测条件⇒额外一个 `LOOP_BODY_END` 标签。
- `break`/`continue`⇒`JUMP`，`labelRef.kind`=`LOOP_END`/`LOOP_HEAD`，`scopeId`=循环作用域。
- 带标签的 `break@outer`⇒`JUMP` 到 `outer` 的 `LOOP_END`（`G` 的 kind+scopeId 必须能区分内外层）。
- `return@label`（Kotlin 非局部返回）⇒`JUMP` 到该标签函数的 `FUNCTION_EXIT`；**必须**与普通 `return` 区分（现状 `translateReturn` 忽略标签，`KTT:483-500`⇒非局部返回变成外层函数的返回）。`when` 里的 `break@when` 同理。
- **Scala 的带标签 `return`**（审计标 `UNCLEAR`，`03-scala.md:117`/`:144`）：`return` 出现在 lambda 体内、语义是「从**外层**方法返回」⇒ 与 Kotlin 的 `return@label` 是**同一种非局部返回**（审计只说「未判定」，本方案按同一套处理）：`JUMP` 到该标签函数的 `FUNCTION_EXIT`；**必须**与普通 `return` 区分。**例外**：若该 `return` 位于 **`inline` 方法**的 lambda 内，返回目标由**展开**决定 ⇒ 归 §13 的 `KS3`（`inline` 展开后的非局部返回，根因四「无运行时对应物」）。

### 6.6 try / catch / finally

```
  L_try:    ...try body...   JUMP L_finally
  L_catch1: CATCH_HANDLER dest=e ctorType=java.lang.Exception  ; catchTypes[0]
             ...catch body...   JUMP L_finally
  L_finally: FINALLY_ENTER   ...finally body...
  TryRegion{ catchTypes=[...], resourceVars=[...], syntheticFinally=false,
             finallyExits=[L_tryNormal, L_catch1End, L_catch2End, L_rethrow] }
```

- 四个出口（正常完成/每个 catch 完成/rethrow）**各有一条**到 `FINALLY_ENTER` 的边（`F` 的 `finallyExits`）；`THROW` 用 `ctorType` 携带抛出类型（`J`）。
- Kotlin `runCatching { }`⇒展开成 `try { Result.success(v) } catch (e: T) { Result.failure(e) }`。
- Scala `catch { case e: E => ... }`⇒catch 体是一个 `match`，用 §6.3 降级。
- `runBlocking { }`⇒`CALL kotlinx.coroutines.runBlocking/1`，块是闭包（§8），调用点挂 `Effect.CONCURRENT`。

### 6.7 for 推导式与集合操作（Scala）

dotc 已把 `for (x <- xs) yield f(x)` 脱糖成 `xs.map(x => f(x))`（`ST:1297-1322`）。**前端要把脱糖后的形状还原成显式循环**：

```
  it = CALL <Iterable.iterator/0>(xs)   ; L_head:
  h = CALL <Iterator.hasNext/0>(it)     ; BRANCH h -> L_body, L_end
  L_body: x = MOVE (CALL <Iterator.next/0>(it))   ; guard: withFilter ⇒ if (!p(x)) JUMP L_head
          acc = MOVE (CALL <closure>/k args=[x, captures...])   JUMP L_head
  L_end:  result = MOVE acc
```

带 `yield` 累加到结果；不带的是副作用循环；`flatMap`=两层迭代协议。**前置修复**：脱糖后的调用是 `Trees.TypeApply`，而 `receiverOf` 只认 `Trees.Select`（`ST:1334-1356`）⇒**接收者与其整棵子树从未被访问**，必须先解 `TypeApply`/`Apply` 包装。Kotlin 侧同构：`for (x in xs)` 走 §6.5 的迭代协议，`xs.map{}` 等操作走同一套展开。

### 6.8 可空调用与 Elvis

```
  ; a?.b()                        ; a ?: b
  recv = MOVE <expr>              BRANCH a != null -> L_ok, L_else   ; Compare(a,NE,null)
  BRANCH recv != null -> L_call, L_null   L_ok:  r = MOVE a   JUMP L_end
  L_call: r = CALL <owner>.b/1 args=[recv] L_else: r = <expr b>
  L_null: r = MOVE null           L_end:
```

Kotlin 的 `?.` 在**非空**接收者上等价于普通调用（编译器插入断言）⇒接收者静态类型非空时可省分支，但必须保留 `NULL_CHECK` 语义说明；接收者可空时分支是**必需**的。

### 6.9 协程挂起

`suspend fun` 的体按挂起点切段，每段一个合成函数 `f$state$N`；挂起点挂 `Effect.SUSPEND`；函数级 `resumePoints`（`L`）记录恢复标签。`launch{}/async{}/runBlocking{}`⇒块是闭包⇒合成函数+`CALL`，调用点挂 `Effect.CONCURRENT`；`await()`⇒`CALL`+`Effect.SUSPEND`。恢复顺序⇒§13 的 `KS2`。

---

## 7. 类型面方案

| 源类型 | `TacType` | `domain`（`D`） | 类型实参（`K`） | 可空 |
|---|---|---|---|---|
| Kotlin `Int`/`Long`/`UInt`/`ULong` | `INT`/`LONG` | `Integral(32/64,true/false)` | — | — |
| Kotlin `Short`/`Byte`/`Char` | `SHORT`/`BYTE`/`CHAR` | `Integral(16,true)`/`Integral(8,true)`/`Integral(16,false)` | — | — |
| Kotlin `Boolean`/`Float`/`Double` | `BOOLEAN`/`FLOAT`/`DOUBLE` | —/`Floating(24,8)`/`Floating(53,11)` | — | — |
| Kotlin `String`/`Unit`/`Any` | `STRING`/`VOID`/`CLASS("kotlin.Any")` | — | — | `?` 决定 |
| **平台类型**（Java 类型） | 同其类 | — | — | **恒可空**（`KTM:294-308`） |
| Kotlin `List<String>`/`Array<Int>`/`IntArray` | `CLASS("kotlin.collections.List")`/`ARRAY(INT)`（`KTM:183-219`、`KTM:189-190`） | — | `[STRING]` | `?` 决定 |
| Kotlin 函数类型 `(Int)->String` | `CLASS("kotlin.Function")` | — | `[INT,STRING]`（现状 `KTM:125-127` 只留类名） | — |
| Kotlin 类型参数 `T` | `UNKNOWN`+`bound` | — | `TacTypeParam{variance}`（`N`） | — |
| Scala `Int`/`Long`/`Float`/`Double`/`Any`/`AnyRef`/`Null` | 同 Kotlin / `CLASS(...)`（`STM:107-109`）/ `NULL`（`STM:110-111`） | 同 Kotlin | — | 恒可空 |
| Scala `Option[T]`/`Either[L,R]` | `CLASS("scala.Option")`/`CLASS("scala.util.Either")` | — | `[T]`/`[L,R]` | `Option` **不是**空安全机制（`STM:43-46`） |
| Scala 路径依赖类型 `a.B` / 单例类型 `x.type` | `CLASS("a.B")` / `CLASS(<x 的类>)` | — | — | 单例类型记 `STRUCTURE` drop |
| Scala union `A \| B` / 交集 `A & B` | `unionMembers=[A,B]` / `bound`（`K`） | — | — | **不得**拼成 `"A\|B"` 假类名 |
| Kotlin/Scala 值类 | 底层类型（脱糖表示） | 随底层 | — | — |
| 变型 `in`/`out`/`*` | 不进 `TacType`，进 `TacTypeDecl.typeParameters`（`N`） | — | — | — |

**不进运行期 IR 的类型信息**（写明理由）：①**变型标注**——运行期被擦除，只影响编译期检查⇒进 `TacTypeDecl.typeParameters`，不进指令；②**类型别名/opaque type 的别名关系**——无运行期对应物（`02` §3.4 根因四）⇒`TacDrop(STRUCTURE)`；③**类型级计算**（路径依赖类型的精化、单例类型的唯一性、`deriving` 产物）——不产生任何运行期值⇒对 TAC 运行期无影响，可静态判定的部分（解析后的类名）照常进 `TacType`；④**编译期契约/opt-in**（Kotlin `contracts`、`@RequiresOptIn`）——编译期约束⇒`TacDrop(STRUCTURE)`；⑤**`reified` 类型实参**——只有展开后才有具体类型⇒该值记 `TacDrop(VALUE)`；⑥**擦除标记**（Scala `erasedValue[T]`、`transparent inline`）——只是**给编译器看**的类型标记，运行期既无值也无类型⇒`TacDrop(STRUCTURE)`（`transparent inline` 的**展开**语义另归 §13 的 `KS3`）；⑦**编译期常量**（Scala `scala.compiletime`）——值只在编译期存在，运行期没有这个值⇒该表达式记 `drop(VALUE, "compiletime.constant")`，**类型**照常进 `TacType`。

---

## 8. 闭包与捕获方案

### 8.1 现状核对

- Scala 共享 holder cell：捕获变量建模为合成 holder 的**字段**（`ST:157-173` 的 `CaptureCell`、`ST:719-727` 的 `newCellFor`、前缀 `CaptureHolder_` 在 `ST:181`）；调用方与闭包体用**同一个 holder 名**。
- holder 名的语义（`ST:138-156` 的注释）：引擎的字段追踪按 `(receiver,field)`；receiver 的名字**必须像类名**（大写首字母）才会跨函数共享。cell 读为 `FIELDLOAD`（`ST:918-929`）、写为 `FIELDSTORE`（`ST:938-947`），写回方向因此可见。捕获检测 `detectCaptures`（`ST:663-687`）**先于**任何降级跑，用编译器的符号+所属方法判定（`ST:795-806`）。
- Scala 闭包体：合成函数，使用点=`CALL`（`ST:2196-2241`），目标名由符号全名决定（`ST:2259-2276`）⇒ 调用图能命中。
- Java 既有模式：体=合成函数，**捕获变量作为尾部形参**（`plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java:1564-1592`），调用点按序传入（`:1626-1643`）。
- Kotlin 现状：**完全不降级**（`KTT:764-821`），实参被换成 null 常量（`KTT:1556`）。

### 8.2 方案

1. **IR 里不需要新的捕获表示。** 沿用语言中立的「**合成函数 + 捕获变量作形参**」（Java lambda 已在用），Kotlin 与 Scala 都改成这一套。
2. **只读捕获 vs 写捕获分两种形态**：只读捕获⇒尾部形参（**值语义**），调用方按声明序传值；被闭包**写入**的捕获⇒共享 holder cell（**引用语义**），保证写回可见。判定依据是「闭包体内是否有该变量的写入」，前端可静态判定。
3. **Scala 的 holder cell 保留，但共享性判定从名字启发式换成 `ownerType`**：cell 的 `FIELDLOAD`/`FIELDSTORE` 带 `ownerType=<捕获持有者>`，持有者同时登记为合成 `TacTypeDecl`⇒引擎按 owner 分桶（`E` 的生产消费方之一是字段污点追踪），不再依赖「首字母大写」。
4. **Kotlin 的 lambda 从「完全丢弃」改为「合成函数+`CALL`」**，捕获变量按 §8.2.2 分流。
5. **高阶调用的回调形参绑定**：TAC 的 `CALL` 是**静态绑定**的，一个函数值没有运行期表示（Java lambda 的现状即如此：`ExpressionTranslator.java:1576` 的 arity 含捕获数，而 `:1626-1639` 的调用点只传 `[null,captures...]`，形参对不上）。因此：
   - **主路线**：前端把**可识别**的高阶操作（`map`/`flatMap`/`filter`/`forEach`/`fold`/`collect`/`sum`/`sorted`/`groupBy`/`withFilter`/`exists`/`forall`/`take`/`drop`/`distinct`）**展开成显式循环**（§6.7），回调形参在循环体里被精确绑定⇒**不需要任何新 IR**。与 `02` §B5「推导式→循环+`CALL`」、§C3「迭代协议→`CALL iterator/hasNext/next`」一致。
   - **残留**：**不可静态识别**的高阶调用（用户自写的高阶函数、把方法当值传）⇒§13 的 `KS1`。

---

## 9. 注解与元数据方案

- IR 已有四个注解通道（`IR/TacFunction.java:123-126`）+`annotationValues`（`IR/TacFunction.java:154`）；写入方法在 `plumb-tac/src/main/java/org/plumb/tac/translate/TacBuilder.java:299-340`。**两条线一个都没调用过**⇒不是 IR 缺口，是前端没读。
- Kotlin：读 `KtAnnotatedExpression` 与声明的 annotation entries⇒类注解→`classAnnotations`；函数注解→`methodAnnotations`；参数注解→`paramAnnotations`（键=参数名）；属性注解→`fieldAnnotations`（键=字段名）；注解实参值→`annotationValues`（键=注解简单名，value 用文本形式）。Scala：dotc 的 `Symbol.annotations`（声明注解）与 `Trees.Annotated`（类型注解）⇒同样四通道。
- **保留期规则（运行期 IR 的边界）**：只有运行期可见的保留期（Kotlin `RUNTIME`、Scala `StaticAnnotation`）才进 IR；**源码期**保留期的注解没有运行期对应物（`02` §3.4 根因四）⇒记 `TacDrop(STRUCTURE)`，不进通道。
- 注解驱动的**语义分歧**必须一并处理：`@JvmField`（属性不生成访问器，直接是字段）、`@JvmOverloads`（生成额外重载⇒合成函数）、`@JvmStatic`（成员静态化⇒`ownerType` 归到宿主类）、`@JvmInline`（值类）、`@Volatile`/`@tailrec`/`@targetName`/`@throws`（进 `methodAnnotations`；`@targetName` 影响调用名⇒调用点的 `calleeSig` 必须用改名后的名字）。`@RequiresOptIn`/`contracts`/`deriving` 属编译期约束⇒`STRUCTURE` drop（§7 末尾第 ④ 条）。

---

## 10. IR 需求

IR v2 之外需要 **4 条**新能力，全部是「加字段」或「枚举末尾追加常量」，符合 `01` §0.2/§0.3。
**另加 1 条对 `01` §IR-E 的细化**（可见性分级），它**不占新编号**。

- **`IR-AA` 模式绑定的访问路径与模式种类**：`TacBinding` 追加 `String path`（`_1`/`$1`/字段名/`""`=整体）；`TacCase` 追加 `PatternKind patternKind`（TYPE/CONSTANT/BINDING/EXTRACTOR/TUPLE/ALTERNATIVE/SEQUENCE/WILDCARD）。现有 IR 不够的原因见 `02` §IR-I：`TacBinding(name,type,sourceType,nullable)` 只有名字与类型⇒无法表达「绑定是被匹配值的第几个槽」；Scala 现状正是因此把绑定读成 `this.a`/`this.b`（`ST:1944-1952`、`ST:2018-2057`）。爆炸半径 0 处指令分派点。
- **`IR-AB` 枚举条目的成员种类**：`TacMemberKind` **末尾**追加 `ENUM_ENTRY`（`01` §IR-E 的 11 个常量之后；`01` §0.3 允许末尾追加，枚举按名字序列化）。枚举条目既不是方法也不是字段⇒无对应 kind 时引擎无法把「枚举条目」与「静态字段」区分，`values()` 与条目访问也没有统一形态。爆炸半径 0 处指令分派点。
- **`IR-AC` 形参的可选性/可变参声明**：**已按 Q11 裁定并入 `06` 的 `IR-AO`**（`01` §10.2 登记）—— `IR-AO` 的 `List<TacParameter>` 是**唯一存储形状**，`IR-AC` 降级为它的**退化视图**：不再新增 `Map<String,ParamOption> paramOptions` 这个第二套存储，改为由 `TacFunction.parameters` 的每个元素携带 `optional` / `vararg` / `defaultFunction` 三个布尔/字符串字段（`IR-AO` 的 `TacParameter` 已有这三个槽位）。调用方要按**声明序**重排命名实参、并给缺失的默认参数补一个调用，都需要知道「哪些形参可选」；现状 Kotlin 的实参按**源码序**发（`KTT:1548-1559`）、arity 用**源码实参个数**（`KTT:1457`）⇒`calleeSig`（`KTT:1613-1620`）与被调函数签名对不上⇒**调用图边直接断**。**可先不做**：把默认参数物化成合成函数 `<callee>$default$<i>`（Kotlin 编译器自身的形状；Scala 侧已有 `d$default$2` 这类名字，`ST:456-464`），调用方 `CALL` 它；这样只需「哪些形参可选」这一条信息，同模块内可从符号表直接得到⇒不必等 `IR-AC`；`IR-AC` 只在**跨模块**时必需⇒可延后。
- **`IR-AD` `as?` 的「失败即 null」转换形态**：`UNARYOP CAST` 的 `castType`（`IR/TacInstruction.java:198`）表达的是「一定会成功」。最小兼容形状：给 `TacInstruction` 追加 `boolean nullableCast`（默认 false；`true` 时 CAST 失败产生 null 而非抛异常），或等价地复用 `castType.nullable`。**不新增 opcode、不新增 operand 变体。**
- **可见性分级（`01` §IR-E 的补充细化，**不占新编号**）**：`TacMemberRef` 的 `boolean isPublic`（`01` §IR-E）**升级为枚举** `TacVisibility{PUBLIC, PROTECTED, INTERNAL, PRIVATE, PACKAGE}`。理由：布尔分不出 Kotlin 的 `internal`（模块作用域）与 `private` ⇒ 跨模块分析用不了可见性。**枚举末尾追加**（`01` §0.3）；语言特有级别经 IR-H 映射到这五档，**不在 IR 里加语言专用枚举值**。
- **`IR-N` / `IR-E` 的 Scala 修饰符细化**（**不占新编号**，与上面「可见性分级」同一档处理）：`TacTypeDecl` 追加 `boolean isSealed`（**IR-N 的补充细化**）与 `boolean isFinal`（**IR-E 的补充细化**）；`abstract` 复用 IR-E **已有**的 `isAbstract`；`sealed` 的**允许子类型**复用 IR-N **已有**的 `permittedSubtypes`；`case` 不需要槽位（由 `members` 里的 `apply`/`unapply`/`copy` 推出）。**不新增 opcode、不新增 operand 变体、不新增 `TacType.Kind`。** 理由：Scala 的 `sealed` 要求子类与父类**同文件**（dotc 的语言规则）⇒ 允许子类型集合**静态可数** ⇒ §6.3 的 `exhaustive` 判定**不需要任何运行期信息** ⇒ 这条**不产生 drop**、不进 §13。

**`IR-AE`：`IR-P` 的 Kotlin/Scala 细则**（不是新字段，是规范；登记于 `01` §10.2 的 `IR-AE` 行）：

| 规范项 | Kotlin | Scala |
|---|---|---|
| 形状 | `<ownerFqn>.<method>/<arity>` | 同 |
| arity | **显式实参个数**：不含接收者；含默认参数填充后的个数；含闭包捕获值 | 同 |
| 接收者 | 一律显式作为 `args[0]`（现状 `KTT:1536-1545` 已是） | 同（现状 `ST:1377-1385` 已是，但 `receiverOf` 要先解 `TypeApply` 包装，`ST:1334-1356`） |
| 顶层函数/扩展函数的 owner | 顶层函数 owner=所在**包名**（现状 `ownerOf` 对 `classId==null` 返回 null，`KTT:1641-1667`⇒裸名⇒命中不到任何以 FQN 锚定的 sink family） | 模块类 FQN（`demo.TopLevel$`） |
| 伴生对象 owner | 归一为 `<外层类>`（去掉 `.Companion`） | 归一为 `<外层类>`（去掉 `$.Companion`） |
| 迭代协议 | `java.lang.Iterable.iterator/0`、`java.util.Iterator.hasNext/0`、`java.util.Iterator.next/0`（**真实方法名**，不得用合成短名） | 同 |
| 枚举条目 | `<Enum>.<ENTRY>/0` | 同 |

**明确不新增**：不新增 opcode（`FE/TacFrontend.java:51-57`）；不新增 operand 变体（`01` §0.2 实测 411 处 `instanceof` 风险）；不新增 `TacType.Kind`（`02` §IR-K 的理由）。

---

## 11. 改动清单

| 文件 | 方法（file:line） | 加什么 | IR |
|---|---|---|---|
| `KotlinTacFrontend.java` | `translateFile`（`KTF:449-469`）、`translateClass`（`KTF:479-515`） | 遍历**全部**顶层/类成员声明：`KtProperty`（访问器+backing field）、`KtSecondaryConstructor`、`KtClassInitializer`、companion object、`KtEnumEntry`、嵌套类、`typealias`；顶层属性⇒`FIELD_INITIALIZER`；`typealias`⇒`programDrops` STRUCTURE | `E`,`IR-AB`,`B` |
| `KotlinTacFrontend.java` | `translateGeneratedMembers`（`KTF:525-597`）、`translateFunction`（`KTF:722-851`） | data class 合成成员改为**真实体**；读修饰符与注解、`memberKind` 判定、签名带 owner、`expect`/`actual` 合并 | `X`,`E`,`P` |
| `KotlinTacTranslator.java` | `translateStatement`（`KTT:343-407`） | 赋值⇒`MOVE`/`FIELDSTORE`；`while`/`do-while`/`for`⇒循环模板；`break`/`continue`⇒`JUMP`+`labelRef`；`throw`⇒`THROW`+`ctorType`；`try`⇒try/catch/finally+`TryRegion`；局部函数⇒合成函数 | `C`,`F`,`G`,`J` |
| `KotlinTacTranslator.java` | `translateExpression`（`KTT:764-821`）、`translateWhen`（`KTT:610-752`） | 新增分派：`?.`/`!!`/`as`/`as?`/`is`/lambda/`::`/对象表达式/数组下标/集合字面量/`++`/`--`；`when` 的多条件⇒`Membership`、`is`⇒`INSTANCEOF`+`TypeTest`、`in`⇒`CALL contains`、绑定收窄、`else` 重排链尾、无 `else`⇒`THROW`、产出 `TacBranchRegion` | `C`,`I`,`IR-AD` |
| `KotlinTacTranslator.java` | `translatePropertyRead`（`KTT:895-911`）、`translateCall`/`translateArguments`/`calleeSignature`/`ownerOf`（`KTT:1455`/`1548`/`1613`/`1641`） | 注册名为 `"this"` 的局部量（常量在 `KTT:1933`）；属性读⇒`FIELDLOAD`+`ownerType`；命名实参按声明序重排；默认参数填 `d$default$N`；扩展接收者入 `args[0]`；companion owner 归一；顶层函数 owner=包名；迭代协议真实名 | `E`,`P` |
| `KotlinTypeMapper.java` | `map`（`KTM:112-132`） | 泛型实参；类型变量 `bound`；函数类型的参数列表；`domain` 映射 | `D`,`K` |
| `ScalaTacTranslator.java` | `collectDefs`（`ST:309-324`）、`buildParams`（`ST:423-438`） | 追加 **Template 级 `ValDef`**⇒字段初始化器/`given`/`lazy val`/枚举 case 值全部产生 `FIELD_INITIALIZER`；全部参数表展平；by-name⇒函数值；`implicit`/`using` 解析后显式化 | `E`,`P` |
| `ScalaTacTranslator.java` | `translateIf`（`ST:1776-1807`）、`translateMatch`（`ST:1876-2000`） | `if` 返回结果临时量（表达式语义）；`match` 逐类模式（类型/常量/绑定/元组/提取器/备选/序列/守卫/穷尽性），消除通配路径 | `C`,`I`,`IR-AA` |
| `ScalaTacTranslator.java` | `receiverOf`（`ST:1334-1356`）、`translateExpr`（`ST:955-1038`） | 解 `TypeApply`/`Apply` 包装⇒泛型调用的接收者不再丢；新增分派：`Trees.Try`、`NamedArg`、`SeqLiteral`（插值）、`Super`、`Inlined`、`TypeDef`、`This` | `P`,`F`,`C` |
| `ScalaTacTranslator.java` | `translateClosure`（`ST:2196-2241`）、`detectCaptures`/`newCellFor`/`readCapture`/`writeCapture`（`ST:663`/`719`/`918`/`938`）、`translateCall`/`calleeSignature`（`ST:1377`/`1448`） | `CALL` 带上闭包形参与捕获值（只读捕获走尾部形参，写捕获走 cell）；cell 的读写加 `ownerType`，持有者登记为合成 `TacTypeDecl`；命名实参按声明序重排；默认参数填 `d$default$N`；companion/模块 owner 规范 | `X`,`E`,`P` |
| `ScalaTypeMapper.java` | `mapDealiased`（`STM:80-121`） | 类型实参、union 成员、路径依赖类型；禁止假类名 | `K` |
| `ScalaTacFrontend.java` / `KotlinTacFrontend.java` | `defaultPrecision`/精度聚合（`STF:108-114`/`236-242`）、`parseInSession`/精度聚合（`KTF:390-437`） | 精度由 `drops` 推导（`A`/`B` 统一口径），不再手工判定 | `A`,`B` |
| `ScalaTacTranslator.java` | `collectDefs`（`ST:309-324`）、`translateDef`（`ST:332`） | 读 `Flags.Final`/`Flags.Sealed`/`Flags.Abstract`/`Flags.Case` ⇒ `TacTypeDecl` 的 `isSealed`/`isFinal`/`isAbstract`；`sealed` 的 `permittedSubtypes` = **同一编译单元内**的直接子类型（dotc 要求同文件 ⇒ **静态可数**）⇒ 供 §6.3 的 `exhaustive` 判定与 `missingCases` | `E`,`N` |
| `ScalaTacTranslator.java` / `ScalaTacPhase.java` | `ProblemCollector.messages`（`ST:2143`）、`problemMessages()`（`ST:304`） | 翻译器的**文本**诊断接上对外通道：`problemMessages()` 今天**零调用方**（本轮全仓 grep 只有 `ST:304` 的声明本身）⇒ 文本腿**没有出口**。要么删掉这个死访问器，要么让 `ScalaTacPhase` 把 `messages` 并入 phase 的 `problems` 列表（`ScalaTacPhase.java:75`/`:185`）⇒ 经 `ScalaTacFrontend.java:232` 的 `problems.addAll(phase.problems())` 到达 `ParseResult.problems()` | `B` |
| `KotlinTacTranslator.java` | `translateFunction`（`KTF:722-851`） | 读**成员修饰符** `override`/`open`/`final`/`abstract` ⇒ `TacMemberRef.modifiers`（**IR-E 的补充细化**，与「可见性修饰符」行共用同一个列表，**不占新编号**）；`open`/`override` ⇒ 调用点发**一组** `CALL`（静态声明 + 已知 override，IR-P）；`final` ⇒ 只发静态声明那一条；`abstract` ⇒ 零指令、只留声明（U-KT2） | `E`（细化）,`P` |
| `KotlinTacTranslator.java` | `translateArguments`（`KTT:1548-1559`） | **展开实参**：`f(*arr)` 的 `*arr` **逐元素**放进 `CALL.args`（数组长度静态可数时）；长度不可数 ⇒ 记 `drop(VALUE, "spread.dynamic-length")` + 变长形参按 IR-AO 承载（U-KT1） | `P`,`IR-AO` |
| `ScalaTacTranslator.java` | `binaryOperator`（`ST:1567-1622`） | **运算符面**：`&&`/`\|\|` 改**分支链**（短路）；`\|` 按语境分流（按位或 ⇒ `BINOP OR` / 模式备选 ⇒ **零指令**，进 §6.3 的模式链）；表外名字逐个给目标形态（`::`/`:::`/`++`/`++=` ⇒ 真实方法名 `CALL`，`!` ⇒ `UNARYOP NOT`）；`->`/`<-`/`?` ⇒ 类型层/去糖 ⇒ 零指令 + `drop(STRUCTURE)`；用户类型上的运算符 ⇒ `CALL <owner>.<方法名>/2`（IR-P）（U-SC4） | `C`,`P`,`B` |
| `KotlinTacTranslator.java` | `translateFunction`（`KTF:722-851`） | **上下文接收者**（context receivers）：读 `KtContextReceiver`（审计 `02-kotlin.md:39` 实测「kotlin 包里零命中」⇒ 今天完全没有）⇒ 隐式接收者作为**前导** TAC 形参（§4.1「上下文接收者」行）；调用点把接收者作为 `args[0]` 传入（IR-P 的「接收者一律 `args[0]`」口径）⇒ **不新造 IR**；Kotlin 2.2 起该语法被 `context` 形参取代，**两者在 TAC 里的形态相同**（隐式接收者 = 前导形参） | `E`,`P` |
| `ScalaTacTranslator.java` | `translateExpr`（`ST:955-1038`） | **复合字面量** `(1, 2)`：新增分支 ⇒ `NEW scala.Tuple2` + 逐组件 `FIELDSTORE`（审计 `03-scala.md:53` 实测「`955-1038` 之间无处理代码」⇒ 今天整条构念不产生指令）；`calleeSig` 用剥掉类型实参后的 FQN（IR-P） | `X`,`P` |
| `ScalaTacTranslator.java` | `translateReturn`（`ST:1013-1020`） | **带标签 `return`**：审计标 `UNCLEAR`（`03-scala.md:117`/`:144`）⇒ 本方案按**非局部返回**处理（§6.5）：`JUMP` 到该标签函数的 `FUNCTION_EXIT`，**必须**与普通 `return` 区分；位于 `inline` 方法的 lambda 内时归 §13 的 `KS3` | `G`,`X` |

---

## 12. 验收口径

**门禁 A：声明面完备性**（防「整类成员从未被访问」复发）。每个前端维护一张**声明种类清单**，逐项断言「有对应物」或「有 `TacDrop`」；清单来源=§4 两张表的**每一行**（Kotlin 25 项、Scala 16 项），前端每遇到一个声明必须在清单上打勾或记 drop，门禁脚本断言覆盖率 100%。

**门禁 B：分支与异常的结构断言**。①每个 `BRANCH`/`CATCH_HANDLER`/`FINALLY_ENTER` 归属唯一 `TacBranchRegion`；②每个 case 的 `guards` 非空（default 除外）；③`finally` 的四个出口都有边（`F` 的 `finallyExits` 覆盖 try/catch/rethrow/return）；④Kotlin `when` 的每个条目都在某个 `TacCase` 里，**不存在**「多条件只测第一个」的分支（`KTT:1823-1831` 的现状）；⑤Scala `match` 的每个 case 都在某个 `TacCase` 里，**不存在**「未识别模式走通配路径并丢弃后续 case」的分支（`ST:1944-1952` 的现状）。不满足⇒报**前端 bug**，不是「降级」。

**门禁 C：求解器可判定性**。每条路径上的守卫集合可渲染成 solver DSL，且渲染后的谓词数量 ≥ 1。守卫来源：`TacGuard`（`C`）+ 引擎侧既有 checker 写入。

**门禁 D：两条线专属的形状断言**。

| 断言 | 内容 |
|---|---|
| D1（Kotlin） | 任意 `KtProperty`（顶层或类内）必须产生至少一条 `FIELDLOAD`/`FIELDSTORE` 或一个 `GETTER`/`SETTER` 函数；任意 `KtClassInitializer` 必须产生 `FIELDSTORE`；任意 `KtEnumEntry` 必须产生一个 `ENUM_ENTRY` 函数；任意 `KtWhenEntry` 必须出现在某个 `TacCase` 里 |
| D2（Scala） | 任意 Template 级 `ValDef` 必须产生一个 `FIELD_INITIALIZER` 函数；任意 `Trees.Match` 的每个 case 必须出现在某个 `TacCase` 里；任意多参数表的形参必须出现在 `params` 里 |
| D3（通用） | 含 `TacDrop(impact=SEMANTICS)` 的函数其 `irPrecision` 必须是 `SOURCE_DEGRADED`；含 `STRUCTURE` 则是 `SOURCE_LOWERED`；只有 `VALUE` 则仍是 `SOURCE_FULL`（`A`/`B` 的统一推导） |
| D4（诊断出口） | 任一降级问题**至少出现在一条对外通道上**。Scala 侧 `ProblemCollector.add`（`ST:2153-2158`）写**两条腿**：结构化腿走 `structuredProblems()`（`ST:297`）→ `ScalaTacPhase.java:161` → `ScalaTacFrontend.java:234`；**文本腿**走 `problemMessages()`（`ST:304`）—— 今天**零调用方** ⇒ 文本腿无出口 ⇒ 本门禁断言「文本腿也必须到达 `ParseResult.problems()`」（`ScalaTacFrontend.java:232` 的 `problems.addAll(phase.problems())`）。Kotlin 侧按同款口径自查（本轮**未实测**，不作断言） |

---

## 13. 无法完整支持的构念及理由

**本表有三档 —— 先看「档」列，它决定附录行的分档**：

| 档 | 含义 | 附录行怎么指向它 |
|---|---|---|
| **C** | **真例外**：构念**本体**无法完整降级（被调方不在源码里 / 边由运行期建立 / 编译期产物 / 求解器侧能力） | 附录行指向本表的 `KSn` ⇒ 查「档」列 ⇒ **C** |
| **B** | **B 级残留**：构念**本体已完整降级**，缺的只是一个**运行期才定的事实** ⇒ 记 `drop` 显式记账 | 附录行指向本表的 `KSn` ⇒ 查「档」列 ⇒ **B** |
| **已覆盖** | 已有 **IR 提案**给出解法 ⇒ **不是例外**；本表只留条目以便追溯 | 附录行指向**正文** ⇒ 分档判为 **A** |

⇒ **判据**（与 `05` §3 的 **P8** 同一条）：`A` = 构念变成真实 TAC 形状（指令**或** IR 元数据），无语义损失；`B` = 构念**本体**完整降级，缺的**只是一个运行期才定的事实**，且该缺失用 `drop` **显式记账**；`C` = 构念**本体**无法降级，只能给保守形状。
⇒ **B 与 C 的分界**：缺的是「**这次运行里它是否发生 / 何时发生 / 以什么顺序发生 / 是哪个值**」⇒ **B**；缺的是「**一条边**（谁调用它）」「**一个被调方**（不在源码里）」「**编译期产物**」⇒ **C**。
⇒ **本轮（2026-11-17）复核结果**：7 条里 **2 条降为 `B`**（`KS2` / `KS7`）、**1 条判为「已覆盖」**（`KS5` ⇒ **IR-Z**，`01` §10.2 已登记）、**4 条仍是真例外**（`KS1` / `KS3` / `KS4` / `KS6`）⇒ **Kotlin/Scala 侧的真例外从 7 条降到 4 条**。


| # | 档 | 构念 | 为什么无法静态表达 | 保守建模形状 | 对精度的影响 |
| --- | --- | --- | --- | --- | --- |
| KS1 | C | **一等函数值与高阶调用的回调绑定**（Kotlin `::f`、把方法当值传；Scala eta-expansion `f _`、部分函数、作为值的占位符） | TAC 的 `CALL` 是**静态绑定**的：函数值没有运行期表示，被调方在运行期才决定调用哪个函数、传什么实参。前端**可识别**的高阶操作已展开成显式循环（§6.7、§8.2.5），**不可识别**的无法确定回调实参 | `CALL`+`UNINTERPRETED`（污点屏障）+`TacDrop(VALUE)`；回调形参按未知处理 | 回调体里的污点在未识别的高阶调用处断链（漏报）；不产生误报 |
| KS2 | B | **协程的恢复顺序与线程交错**（`launch`/`async`/`await`/`Flow`） | 由运行期调度器与硬件决定，源码里没有这个事实（`02` §3.2）。**但协程的本体已完整降级**（状态机展开：按挂起点切段，每段一个合成函数；挂起点挂 `Effect.SUSPEND`；函数级 `resumePoints`）⇒ 缺的只是「谁在何时恢复」这一个**运行期才定的事实** | 挂起点 `Effect.SUSPEND`+恢复点（`L`）+`Effect.CONCURRENT`；跨挂起点的数据流按「任意挂起点可能到达」合并 | 并发类规则报「**可能**发生」而非「发生了」；跨挂起点的数据流被过近似 |
| KS3 | C | **`inline` 展开后的非局部返回与类型实参特化**（Kotlin `inline`/`reified`；Scala `inline`/`transparent inline`） | 展开是编译期产物：源码里没有展开后的控制流；`return` 在内联 lambda 里返回的是**内联函数**而非 lambda，不展开就无法表达这个返回目标 | 展开落地前：调用点按普通 `CALL` 处理+`TacDrop(SEMANTICS)`；`reified` 的类型实参丢失处记 `VALUE` drop | 早退路径的污点可能被误判。**可由 S8 的展开工作消除**，属工作量而非能力缺口 |
| KS4 | C | **Scala 编译期宏** | 宏的展开体不在源码树里（展开发生在前端所处阶段之后），运行期效果由宏实现决定，静态不可知 | `CALL`+`UNINTERPRETED`+`TacDrop(VALUE)` | 宏生成的代码不被分析 |
| KS5 | 已覆盖 | **Kotlin 智能转换的「类型收窄」**（非空性部分除外） | `TacType` 是**静态**类型，今天没有流敏感的收窄表示。`is` 检查后的**非空性**收窄由 `NULL_CHECK` **完整**表达（IR 既有）；但「这个值现在是 `String` 而不是 `Any`」需要**分支内类型改写** | **IR-Z 落地后完整支持**：`BRANCH` 带 `Map<String,TacType> narrowedTypes`（`05` §IR-Z 的形状，TS/ArkTS 的 `if (x is Y)` 走同一条）⇒ Kotlin 的 `if (x is String) { x.length }` 里的 `x` 被改写成 `STRING`。**IR-Z 未落地时**才退回：保留声明类型+`TacDrop(STRUCTURE)` | IR-Z 落地：依赖收窄类型的规则（如「只有 String 才有 length」）**正常触发**；未落地：这些点上不触发（不误报也不漏报地「不知道」） |
| KS6 | C | **结构类型与反射式成员访问**（Scala `{ def f: Int }`、反射、动态成员查找） | 成员是否存在、值是什么，取决于运行期对象状态与注册表；源码里没有这个事实（`02` §3.1） | `CALL`+`UNINTERPRETED`；结果按**未知**处理（既不当干净、也不当污点源） | 依赖该结果的规则**不出结论** |
| KS7 | B | **锁争用顺序**（`synchronized`/`lock` 谁先进入） | 运行期决定（`02` §3.2）。**临界区范围与锁边已完整降级**（IR-M）⇒ 缺的只是「谁先进入」这一个**运行期才定的事实** | 临界区范围进 IR（IR-M）+`Effect` 标记 | 死锁/顺序类规则**不报**；原子性规则照报 |

**这 7 条经本轮（2026-11-17）按「构念本体是否可降级」复核后，只有 4 条（`KS1` / `KS3` / `KS4` / `KS6`）仍是真例外**；`KS2` / `KS7` 是 `B` 级残留（本体已完整降级，缺的只是一个运行期才定的事实，已用 `drop` 记账），`KS5` 由 **IR-Z** 覆盖、**不是例外**。**除这 7 条外，本次未发现其它无法完整支持的构念。** 依据：审计台账里 Kotlin 的 76 条丢弃与 Scala 的 52 条丢弃，逐条映射到本文 §4–§9 的 TAC 形态后，**没有任何一条需要「降级成 UNKNOWN/HAVOC+记 drop」才能落地**；剩下的都是「前端没写」或「IR 少字段」，而所需字段已在 IR v2（IR-A..IR-Q）里给出；本文新增的 4 条（`IR-AA`/`IR-AB`/`IR-AC`/`IR-AD`）也都是加字段或枚举末尾追加常量。

---

## 14. 风险与未决项

1. **IR v2 尚未落地**：全仓库 grep，`SOURCE_DEGRADED`/`TacDrop`/`TacGuard`/`TacValueDomain`/`TacMemberKind`/`TacTypeDecl`/`TacLabelRef`/`TacBranchRegion` **一个都不存在**⇒本方案的前置是 IR v2 先落地；§10 的 4 条同样未落地。
2. **IR-P 的 owner 规范会改动调用图键**⇒已有规则集与签名目录要同步；跨语言边界的边会一次性变化（已有 finding 数量随之变）。
3. **高阶操作展开是前端最重的一块**（集合操作×语言×变体）。建议顺序：先做「迭代协议+`for`」与「`map`/`filter`/`forEach`」六项；做完再评估是否需要「回调实参标注」这类 IR 能力（本文未提，它只服务于不可识别的高阶调用，而那部分已归入 §13 的 `KS1`）。
4. **`inline` 展开（S8）的工作量被低估**：它要求前端在 dotc 的 inliner 之后运行，或自己实现展开器。
5. **Kotlin 补注册 `this` 会改变既有 TAC 形状**：属性读从「没有定义指令的临时量」变成 `FIELDLOAD`⇒此前被静默丢弃的字段污点会开始流动⇒**finding 数量会跳变**，属预期而非回归。
6. **Scala 的 holder cell 改用 `ownerType`**⇒需要引擎侧字段追踪按 owner 分桶（`01` §IR-E 的生产消费方之一）⇒迁移期必须保证新旧两条路径给出同样的结果。
7. **未决**：`TacBinding.path` 的取值格式（`"$1"` vs `"_1"` vs 字段名）要与引擎侧的解构规则一致；`ParamOption` 是否真的需要（跨模块场景）取决于模块边界的最终定义；Kotlin `expect`/`actual` 的合并规则（同一限定名两个函数⇒合并成一个）会改变合并程序的键，须与 IR-P 的 owner 规范一起定。
8. **未决**：Kotlin 编译器的「插入式空检查」是否保留。IR 的 javadoc 明确指出这些检查可被 `-Xno-param-assertions`/ProGuard 剥掉（`IR/TacInstruction.java:87-92`）⇒`NULL_CHECK` 只能用于**压制**误报，不能用来证明安全；本方案的所有 `NULL_CHECK` 都遵守这一方向性规则。
9. **未决（**不属于本方案的承载范围**）**：**前端 javadoc 与代码不一致**（审计 Scala #B19）。本轮实测三处：① `ScalaTacTranslator.java:55-59` 的类 javadoc 把 **closures** 列进「What this does not lower」，但闭包**已经**降级（`:1003-1006` → `translateClosure` `:2196`）；② 同文件 `:415-422` 的 javadoc 说后续参数表「are lowered as locals instead」，而 `buildParams`（`:423-438`）只取**第一张表**（`:432` 的 `it.next()`）⇒ 后续表的形参**既不是形参也不是局部量，直接消失**；③ 同文件 `:61-64` 的 javadoc 把 `NOP` 列进「本文件会发出的指令种类」，但全文件**没有一处** `Kind.NOP`。⇒ **口径校正**：附录 C.1 的 U-SC3 把第 ③ 条写成「`THROW` 从未发出」—— **与代码不符**（`THROW` **确实**发出，`:1021-1026`）；准确表述是「**`NOP`** 从未发出」。⇒ **谁在哪个提交里同步**：本方案只改**降级行为**、不改 javadoc；建议把 javadoc 同步作为**实现改动的前置**（同一提交里改），因为 §4/§6 的目标形态落地后，javadoc 描述的会是另一套行为。**待拍板。**

---

## 附录 A. Kotlin 审计台账 —— 审计 A 节**逐行**去向（127 行）

**审计来源**：`D:\work\tmp\audit\02-kotlin.md` 的 `## A. Coverage ledger` 节（`### A.1 Declarations` … `### A.6 Cross-cutting`，共 127 行）。

**覆盖口径**：审计 A 节的**每一行**都在下表里，**127 行 ⇒ 127 条，一行不缺**。其中审计标 `NOT FOUND` 的 3 行（`context receivers` ×2、`contracts`）**也给了落点** —— 审计标 `NOT FOUND` 的含义是「**现有实现里没有任何构念专属代码**」，不是「这个构念不存在」：2 行是**真实语言特性**（Kotlin 的 context receivers）⇒ 本方案**新增支持**（隐式接收者 = 前导形参，不新造 IR）；1 行是**编译期契约**（`contracts`）⇒ 按 §7 第 ④ 条记 `STRUCTURE` 级 drop（根因四「无运行时对应物」）。审计的官方 `checked` 口径把 `NOT FOUND` 的 3 行**算在内**（Kotlin = 127）⇒ **两套口径在本面一致**。`KEPT` 的行**保留状态**，去向写「保持现状（无需改动）」并指出形状在方案的哪一行登记。

**「审计状态」列**沿用审计的词（`KEPT` / `DEGRADED` / `DROPPED-SILENT` / `DROPPED-NOTED`），不新造缩写。

**「#」列的编号口径**：`Kotlin #N` = 审计 **B 节**（Dropped list）的原编号 —— **原附录已有**，去向信息更详细，予以保留；`A<n>-<k>` = 审计 **A 节** `A.n` 子节的第 k 行 —— **原附录未覆盖**，本轮补入。

**子小节**照审计 A 节的子小节组织（`### A.1 Declarations` … `### A.6 Cross-cutting`），便于逐节核对。
### A.1 Declarations

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A-01 | class declaration | KEPT | `KTF:455-461`, `KTF:479-515` | 保持现状（无需改动）—— §4.1「类/接口/密封类」行（每成员一个 `TacFunction`（`enclosingClass`=类 FQN）+`TacTypeDecl{fqn,supertype,interfaces,permittedSubtypes,members}`） |
| A-02 | interface + its members | DEGRADED | `KTF:501-507`; `KTT:256-262` | §4.1「类/接口/密封类」行（`memberKind=METHOD`；每个成员一个 `TacFunction`）+ §11 改动清单「`translateFile`（`KTF:449-469`）、`translateClass`（`KTF:479-515`）\| 遍历**全部**顶层/类成员声明」+ §12 门禁 A |
| A-03 | object declaration (its functions) | KEPT | `KTF:501-507` | 保持现状（无需改动）—— §4.1「对象 `object`」行（同类；初始化=`STATIC_INITIALIZER`） |
| A-04 | object properties | DROPPED-SILENT | `KTF:501-507` (only `KtNamedFunction` is taken) | §4.1「对象 `object`」行（状态=该类型的字段（带 `ownerType`）；初始化=`STATIC_INITIALIZER`）+ §11 改动清单「`translateFile`…遍历**全部**顶层/类成员声明」+ §12 门禁 D1 |
| Kotlin #30 | enum class + entries | DROPPED-SILENT | `KTF:455-507` (entries are `KtClass`, not `KtNamedFunction`) | §4.1「枚举类」行（条目=合成访问函数，体 `NEW <EnumType>`；`E.A`→`CALL <Enum>.A/0`；`values()`→`NEWARRAY`）+ §10 `IR-AB`（`TacMemberKind` 末尾追加 `ENUM_ENTRY`）+ §12 门禁 D1 |
| A-05 | annotation class | DROPPED-SILENT | `KTF:455-507` | §4.1「注解类」行（`TacTypeDecl(annotations=[...])`；注解成员=`PROPERTY_ACCESSOR`）+ §11 改动清单 + §12 门禁 D1 |
| A-06 | fun interface | DEGRADED | `KTT:256-262` | §4.1「类/接口/密封类」行（`fun interface` 仍是接口 ⇒ 走同一条声明面；其唯一抽象方法 ⇒ 一个 `TacFunction`，`memberKind=METHOD`） |
| A-07 | data class generated members | DEGRADED | `KTF:512-514`, `KTF:525-597` | §4.1「数据类」行（合成成员改为**真实体**）+ §11 改动清单「`translateGeneratedMembers`（`KTF:525-597`）」 |
| A-08 | value class | DROPPED-SILENT | `KTF:455-507` | §4.1「值类」行（运行期表示=被包装类型⇒构造器=`MOVE`（**不** `NEW`）、访问器=恒等 `MOVE`）+ §11 改动清单 + §12 门禁 D1 |
| Kotlin #27 | sealed class / sealed interface | DROPPED-SILENT | `KTF:455-507` | §4.1「类/接口/密封类」行（`TacTypeDecl{…,permittedSubtypes,members}`）+ §6.1（`exhaustive` 由 sealed 层次（`N` 的 `permittedSubtypes`）判定）+ §7 类型面方案「变型 `in`/`out`/`*`」行（进 `TacTypeDecl.typeParameters`，`N`） |
| A-09 | companion object | DROPPED-SILENT | `KTF:501-507` | §4.1「伴生对象」行（成员 `enclosingClass`=**外层类 FQN**；`Holder.make()`→`CALL <外层类>.make/0`）+ §10 IR-P 细则表「伴生对象 owner」行 |
| A-10 | extension function | DEGRADED | `KTT:1613-1620`, `KTT:1641-1667` | §4.1「扩展函数 / 扩展属性」行（接收者=**首个 TAC 形参**；`CALL <owner>.ext/1 args=[recv,...]`）+ §10 IR-P 细则表「顶层函数/扩展函数的 owner」行 |
| A-11 | extension property | DROPPED-SILENT | `KTF:455-507` | §4.1「扩展函数 / 扩展属性」行（属性读=合成 `GETTER`）+ §10 IR-P 细则表「顶层函数/扩展函数的 owner」行 |
| A-12 | inline function | DEGRADED | `KTF:722-851` | §4.1「内联函数 / `reified`」行（声明侧同普通函数；**调用点做展开**（S8）；展开前 `TacDrop(SEMANTICS,"inline.notExpanded")`）+ §13 的 `KS3` |
| A-13 | infix function (declaration) | KEPT | `KTF:501-507` | 保持现状（无需改动）—— §4.1「操作符重载/中缀」行（`memberKind=OPERATOR`） |
| Kotlin #22 | infix call site (`a pow2 b`) | DROPPED-NOTED | `KTT:1186-1199` | §5.1「中缀 `a pow2 b` / 用户类型 `a + b`」行（`CALL <owner>.pow2/2`）+ §4.1「操作符重载/中缀」行 + §10 IR-P 细则 |
| A-14 | operator function (declaration) | KEPT | `KTF:501-507` | 保持现状（无需改动）—— §4.1「操作符重载/中缀」行 |
| Kotlin #22 | operator call site (`a + b` on a user type) | DEGRADED | `KTT:1759-1778` | §5.1「中缀 `a pow2 b` / 用户类型 `a + b`」行（`CALL <owner>.plus/2`）+ §4.1「操作符重载/中缀」行 + §10 IR-P 细则 |
| A-15 | tailrec function | KEPT | `KTF:501-507` | 保持现状（无需改动）—— §4.1「尾递归」行（标注即可；TAC 的 `JUMP` 回边就是尾递归形态） |
| Kotlin #14 | suspend function | DEGRADED | `KTT:236`, `KTT:307-309` | §6.9 协程挂起（状态机展开：按挂起点切段，每段一个合成函数；挂起点挂 `Effect.SUSPEND`；函数级 `resumePoints`）+ §4.1「挂起函数」行；**恢复顺序与线程交错**进 §13 的 `KS2` （`B` 档：记 `drop(SEMANTICS, "coroutine.resumeOrder")`） |
| A-53 | context receivers | **NOT FOUND** | no `KtContextReceiver` reference anywhere in the kotlin package | §4.1「**上下文接收者**」行（隐式接收者作为**前导** TAC 形参；调用点把它作为 `args[0]` 传入 ⇒ IR-P 的「接收者一律 `args[0]`」口径）+ §11 改动清单「`translateFunction`（`KTF:722-851`）…上下文接收者」行。**审计标 NOT FOUND** ⇒ 现有实现里**完全没有**这个构念的处理代码 ⇒ 本方案**新增支持**，且**不新造 IR**（隐式接收者 = 前导形参，与扩展接收者同形）；Kotlin 2.2 起该语法被 `context` 形参取代，**两者在 TAC 里的形态相同** |
| Kotlin #29 | expect / actual | DEGRADED | `KTT:256-262` | §11 改动清单「`translateGeneratedMembers`（`KTF:525-597`）、`translateFunction`（`KTF:722-851`）\| …`expect`/`actual` 合并」+ §14 风险 7 |
| A-16 | typealias | DROPPED-SILENT | `KTF:455-469` | §4.1「类型别名」行（**无运行期效果⇒不产生函数**；`TacDrop(STRUCTURE,"typealias")`→`programDrops`）+ §11 改动清单 + §12 门禁 A |
| A-17 | value parameter | KEPT | `KTF:749-802` | 保持现状（无需改动）—— §4.1「主/次构造器」行（每个构造器一个 `CONSTRUCTOR`；体=`FIELDSTORE`(参数→字段)+`init` 块+属性初始化器） |
| A-18 | parameter default value | DROPPED-SILENT | `KTF:749-802` | §10 `IR-AC`（已按 Q11 裁定**并入 `06` 的 `IR-AO`**：默认参数的可选性由 `TacFunction.parameters` 的每个元素携带 `optional`/`vararg`/`defaultFunction` 三个槽位承载；**可先不做** —— 把默认参数物化成合成函数 `<callee>$default$<i>`）+ §11 改动清单「`calleeSignature`（`KTT:1613`）\| …默认参数填 `d$default$N`」 |
| Kotlin #11 | caller-side default-arg filling | DROPPED-SILENT | `KTT:1457`, `KTT:1613-1620` | §10 `IR-AC`（**可先不做**：把默认参数物化成合成函数 `<callee>$default$<i>`；这样只需「哪些形参可选」这一条信息，同模块内可从符号表直接得到⇒不必等 `IR-AC`；`IR-AC` 只在**跨模块**时必需⇒可延后）+ §11 改动清单「`calleeSignature`（`KTT:1613`）\| …默认参数填 `d$default$N`」 |
| Kotlin #10 | named arguments | DROPPED-SILENT | `KTT:1548-1559` | §10 `IR-AC`（调用方要按**声明序**重排命名实参；已并入 `IR-AO`）+ §11 改动清单「`translateCall`/`translateArguments`（`KTT:1455`/`1548`）\| …命名实参按声明序重排」 |
| A-19 | vararg parameter | DEGRADED | `KTF:749-802` | §10 `IR-AC`（形参的**可变参**声明；已并入 `IR-AO` 的 `TacParameter.vararg` 槽位）+ §4.1「主/次构造器」行 |
| A-20 | spread argument | DEGRADED | `KTT:1548-1559` | §5.1 Kotlin 表达式面表「**展开实参（spread，`f(*arr)`）**」行（调用点把被展开的数组**逐元素**放进 `CALL.args`；长度不可数 ⇒ `drop(VALUE, "spread.dynamic-length")` + 变长形参按 **IR-AO** 承载；变长形参**不计入** `calleeSig` 元数，IR-P） + §11 改动清单「`translateArguments`（`KTT:1548-1559`）」行 |
| A-21 | primary constructor | DROPPED-SILENT | `KTF:501-507` | §4.1「主/次构造器」行（每个构造器一个 `CONSTRUCTOR`）+ §11 改动清单「`translateFile`（`KTF:449-469`）…`KtSecondaryConstructor`」+ §12 门禁 D1 |
| A-22 | secondary constructor | DROPPED-SILENT | `KTF:501-507` | §4.1「主/次构造器」行 + §11 改动清单「`translateFile`…`KtSecondaryConstructor`」+ §12 门禁 D1 |
| A-23 | init block | DROPPED-SILENT | `KTF:501-507` | §4.1「`init` 块」行（构造器体内的一段 `FIELDSTORE` 序列（参数赋值之后））+ §11 改动清单 + §12 门禁 D1 |
| A-24 | property with custom getter/setter | DROPPED-SILENT | `KTF:501-507` | §4.1「类属性 `val`/`var` 与自定义 getter/setter」行（backing field=`FIELDLOAD`/`FIELDSTORE`（带 `ownerType`）；访问=合成 getter/setter（自定义访问器的体=用户代码）；`val` 无 setter）+ §11 改动清单 + §12 门禁 D1 |
| A-25 | backing field (`field`) | DROPPED-SILENT | `KTF:501-507` | §4.1「类属性 `val`/`var` 与自定义 getter/setter」行（backing field=`FIELDLOAD`/`FIELDSTORE`（带 `ownerType`）） |
| A-26 | `lateinit` property | DROPPED-SILENT | `KTF:501-507` | §4.1「`lateinit`」行（backing field+合成布尔字段 `<p>$init`；读前 `BRANCH` 检查，未初始化则 `THROW ctorType=kotlin.UninitializedPropertyAccessException`）+ §11 改动清单 + §12 门禁 D1 |
| A-27 | delegated property (`by lazy` / `by observe` / custom) | DROPPED-SILENT | `KTF:501-507` | §4.1「委托属性 `by lazy`/`by Delegates.observable`」行（读=`CALL <delegate>.getValue(this,prop)`；写=`CALL <delegate>.setValue(this,prop,v)`）+ §11 改动清单 + §12 门禁 D1 |
| A-28 | `const val` | DROPPED-SILENT | `KTF:455-469` | §4.1「`const val`」行（编译期常量⇒**使用点直接内联常量**；`TacDrop(STRUCTURE,"const.inlined")`）+ §11 改动清单 + §12 门禁 D1 |
| A-29 | `override` | DEGRADED | `KTF:722-851` | §4.1 Kotlin 声明面表「**成员修饰符**（`override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion`）」行（**零指令**；事实落 `TacMemberRef.modifiers`（IR-E 的补充细化，与「可见性修饰符」行共用同一个列表，**不占新编号**）；`override` ⇒ 父类调用点发**一组** `CALL`（静态声明 + 已知 override，`calleeSig` 用各自 owner FQN，IR-P）） + §11 改动清单「`translateFunction`（`KTF:722-851`）」行 |
| A-30 | `open` | DROPPED-SILENT | `KTF:722-851` | 同 A-29 的落点（§4.1「**成员修饰符**（`override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion`）」行）：`open` ⇒ 该成员**可被覆写** ⇒ 父类调用点同样发**一组** `CALL`（静态声明 + 已知 override）⇒ 现状被修掉（审计 `02-kotlin.md:57`：「the function looks final」） |
| A-31 | abstract member | DEGRADED | `KTT:256-262` | §4.1「类/接口/密封类」行（抽象成员 ⇒ 一个 `TacFunction`（空体）+ `memberKind=METHOD`）+ §11 改动清单「`translateFunction`（`KTF:722-851`）\| …读修饰符与注解、`memberKind` 判定」—— **其 `abstract` 修饰符本身**见 U-KT2 |
| A-32 | `final` | DROPPED-SILENT | `KTF:722-851` | 同 A-29 的落点（§4.1「**成员修饰符**（`override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion`）」行）：`final` ⇒ **不可被覆写** ⇒ 父类调用点**只**发静态声明那一条 `CALL` ⇒ 派发集合确定，不产生「可能到达未知实现」的过近似 |
| Kotlin #26 | `internal` | DROPPED-SILENT | `KTF:722-851` | §4.1「**可见性修饰符**（`public`/`internal`/`private`/`protected`）」行（`visibility` 进 `TacMemberRef`；`internal`（模块作用域）与 `public` 不可区分 ⇒ **跨模块分析无法利用可见性**）+ §10「可见性分级」（`TacVisibility{PUBLIC, PROTECTED, INTERNAL, PRIVATE, PACKAGE}`，**枚举末尾追加**，不占新编号） |
| Kotlin #26 | `private` / `public` / `protected` | DROPPED-SILENT | `KTF:722-851` | §4.1「**可见性修饰符**」行 + §10「可见性分级」（`TacVisibility` 五档；语言特有级别经 IR-H 映射，不在 IR 里加语言专用枚举值） |
| A-33 | `@JvmStatic` / `@JvmField` / `@JvmOverloads` | DROPPED-SILENT | grep: no `Annotation` reference in the kotlin package | §9 注解与元数据方案（**注解驱动的语义分歧必须一并处理**：`@JvmField`（属性不生成访问器，直接是字段）、`@JvmOverloads`（生成额外重载⇒合成函数）、`@JvmStatic`（成员静态化⇒`ownerType` 归到宿主类））+ §11 改动清单「`translateGeneratedMembers`…读修饰符与注解」 |
| Kotlin #6 | **any** annotation (class / method / param / field) | DROPPED-SILENT | `TAC/TacFunction.java:116-126` defines four channels; `TacBuilder.java:300-332` exposes the setters; **the Kotlin lane calls none of them** | §9 注解与元数据方案（Kotlin：读 `KtAnnotatedExpression` 与声明的 annotation entries⇒类注解→`classAnnotations`；函数注解→`methodAnnotations`；参数注解→`paramAnnotations`；属性注解→`fieldAnnotations`；注解实参值→`annotationValues`）+ §11 改动清单 + §4.1 声明面表的注解通道列 |
| A-34 | decimal literal | KEPT | `KTT:1387-1420` | 保持现状（无需改动）—— §5.1「各种数值/字符字面量」行 |
| Kotlin #18 | hex / binary literal | DROPPED-SILENT | `KTT:1421-1426` | §5.1「各种数值/字符字面量（十进制/十六进制/二进制/下划线/无符号/转义）」行（`0xFF`=255、`1_000`=1000、`42u`=无符号 32 位（现状遇 `NumberFormatException` 返回 null，`KTT:1421-1426`）） |
| Kotlin #18 | underscored literal (`1_000`) | DROPPED-SILENT | `KTT:1421-1426` | §5.1「各种数值/字符字面量」行（`1_000`=1000） |
| Kotlin #18 | unsigned literal (`42u`) | DROPPED-SILENT | `KTT:1421-1426` | §5.1「各种数值/字符字面量」行（`42u`=无符号 32 位） |
| A-35 | char literal | KEPT | `KTT:1364-1370` | 保持现状（无需改动）—— §5.1「各种数值/字符字面量」行 |
| Kotlin #18 | escaped char literal (`'\n'`) | DROPPED-SILENT | `KTT:1364-1370` | §5.1「各种数值/字符字面量」行（转义字符字面量） |
| A-36 | string literal | KEPT | `KTT:1371-1373` | 保持现状（无需改动）—— §5.1「各种数值/字符字面量」行 |
| A-37 | raw string (`"""…"""`) | KEPT | `KTT:1329-1331` | 保持现状（无需改动）—— §5.1「各种数值/字符字面量」行 |
| A-38 | multiline string | KEPT | `KTT:1329-1331` | 保持现状（无需改动）—— §5.1「各种数值/字符字面量」行 |
| A-39 | string template | KEPT | `KTT:1269-1308` | 保持现状（无需改动）—— §5.1「字符串模板」行（`BINOP ADD` 链） |
| A-40 | `if` as expression | KEPT | `KTT:1094-1150` | 保持现状（无需改动）—— §5.1「`if` 表达式」行（§6.4） |
| A-41 | `when` with subject | DEGRADED | `KTT:620-703` | §6.1 `when`（有 subject）（分支链 + `TacCase.bindings` + `TacBranchRegion{kind=WHEN,…}`）+ §5.1「`when`（subject / subject-less / `is` / 绑定 / 守卫 / `in`）」行 + §11 改动清单「`translateWhen`（`KTT:610-752`）」 |
| A-42 | `when` without subject | DEGRADED | `KTT:704-743` | §6.2 `when`（无 subject）（`TacBranchRegion{kind=IF_CHAIN,subject=null}`；每个 case 的 guard=该条件的 `Truthiness`）+ §5.1「`when`」行 |
| Kotlin #15 | `when` entry with multiple conditions (`1, 2 ->`) | DEGRADED | `KTT:1823-1831` | §6.1（多条件条目 `1, 2 ->`：**一条** `Membership(subject,[1,2])`，不是两次 `BINOP`）+ §12 门禁 B④ |
| Kotlin #16 | `when` `is` condition (subject form) | DROPPED-NOTED | `KTT:630-651` | §6.1（`is` 条目⇒`INSTANCEOF`+`TypeTest`，绑定用**收窄后的类型**+`MOVE`）+ §11 改动清单「`translateWhen`…`is`⇒`INSTANCEOF`+`TypeTest`、绑定收窄」 |
| Kotlin #16 | `when` `is`/`in` condition (subject-less form) | DEGRADED | `KTT:829-874` | §6.2（subject-less 形式的条件⇒`Truthiness`（现状降级成常量 `false`，`KTT:829-874`⇒该分支静态不可达））+ §6.1 |
| Kotlin #16 | `when` `in`/range condition | DROPPED-NOTED | `KTT:630-651` | §6.1（`in` 条目⇒`CALL contains`+`Truthiness`）+ §11 改动清单「`translateWhen`…`in`⇒`CALL contains`」 |
| Kotlin #17 | `when` without `else` | DEGRADED | `KTT:618`, `KTT:744-751` | §6.1（**无 `else`**：源码在不匹配时抛 `NoWhenBranchException`⇒链尾必须 `THROW`，**不能**让结果临时量悬空）+ §11 改动清单「`translateWhen`…无 `else`⇒`THROW`」 |
| Kotlin #9 | `try`/`catch`/`finally` as expression | DROPPED-NOTED | `KTT:343-407` | §6.6 try / catch / finally（`TryRegion{ catchTypes, resourceVars, syntheticFinally, finallyExits }`；四个出口**各有一条**到 `FINALLY_ENTER` 的边）+ §11 改动清单「`translateStatement`（`KTT:343-407`）\| …`try`⇒try/catch/finally+`TryRegion`」 |
| Kotlin #25 | `run` / `let` / `also` / `apply` / `with` | DEGRADED | `KTT:1548-1559` | §5.1「作用域函数 `let`/`run`/`also`/`apply`/`with`」行（展开成显式块：`let{it->body}`→`MOVE dest=it lhs1=recv`+body）+ §11 改动清单 |
| Kotlin #21 | elvis `?:` | DROPPED-NOTED | `KTT:1186-1199` | §5.1「可空调用 `?.` / Elvis `?:`」行 + §6.8 可空调用与 Elvis（分支伪码 `BRANCH a != null -> L_ok, L_else`） |
| Kotlin #21 | elvis-throw `?: throw` | DROPPED-NOTED | `KTT:1186-1199` | §5.1「可空调用 `?.` / Elvis `?:`」行（`?:` 的**右侧**是 `throw` ⇒ 右臂是 §5.1「`require`/`check`/`error`/`TODO()`」行的 `THROW` 形态）+ §6.8 |
| Kotlin #21 | safe call `?.` | DROPPED-NOTED | `KTT:764-821` | §5.1「可空调用 `?.` / Elvis `?:`」行 + §6.8（接收者可空时分支是**必需**的） |
| Kotlin #21 | not-null assertion `!!` | DROPPED-NOTED | `KTT:764-821` | §5.1「`!!`」行（`NULL_CHECK dest=t lhs1=x`）+ §14 风险 8（`NULL_CHECK` 只能用于**压制**误报，不能用来证明安全） |
| Kotlin #20 | `as` / `as?` | DROPPED-NOTED | `KTT:764-821` | §5.1「`as`/`as?`」行（`UNARYOP CAST castType=T`；`as?` 需「失败即 null」的 CAST 形态⇒**IR 需求 `IR-AD`**）+ §10 `IR-AD` |
| Kotlin #20 | `is` / `!is` | DROPPED-NOTED | `KTT:764-821` | §5.1「`is`/`!is`」行（`INSTANCEOF dest=t lhs1=x ctorType=T`+`BRANCH` 带 `TacGuard.TypeTest`）+ §11 改动清单「`translateExpression`…新增分派：…`is`…」 |
| A-43 | ranges `..` / `..<` | DROPPED-NOTED | `KTT:1186-1199` | §5.1「区间 `..`/`..<`」行（`NEW dest=t ctorType=kotlin.ranges.IntRange args=[start,end]`） |
| A-44 | `in` / `!in` | DROPPED-NOTED | `KTT:1186-1199` | §5.1「`in`/`!in`」行（`CALL <Collections owner>.contains/2 args=[collection,x]`→BOOLEAN） |
| Kotlin #5 | lambda | DROPPED-NOTED | `KTT:764-821` | §8 闭包与捕获方案 §8.2 第 4 条（**Kotlin 的 lambda 从「完全丢弃」改为「合成函数+`CALL`」**，捕获变量按 §8.2.2 分流）+ §5.1「lambda/匿名函数」行 + §11 改动清单「`translateExpression`（`KTT:764-821`）…新增分派：…lambda…」 |
| A-45 | anonymous function | DROPPED-NOTED | `KTT:343-407` | §5.1「lambda/匿名函数」行（合成函数 `L$<n>`（闭包形参在前、捕获形参在后）+`CALL L$<n>/k`）+ §8.2 |
| A-46 | function reference `::` | DROPPED-NOTED | `KTT:764-821` | §5.1「函数引用 `::f`」行（立即应用 `f::(x)`→`CALL f/1`；作为值传递⇒§13 的 `KS1`）+ §13 的 `KS1` |
| A-47 | object expression (`object : X {}`) | DROPPED-NOTED | `KTT:764-821` | §4.1「嵌套类 / 局部类 / 匿名对象」行（各自合成类型 + `CONSTRUCTOR`）+ §11 改动清单「`translateFile`…`KtEnumEntry`、嵌套类…」+ §12 门禁 A |
| A-48 | collection literal (`listOf(1,2)`) | DEGRADED | `KTT:1509-1522` | §5.1「集合字面量 `listOf(1,2)`」行（`CALL <符号解析出的 owner>.listOf/2`；owner 由符号给出，**不猜**） |
| A-49 | `repeat` | DROPPED-NOTED | `KTT:1548-1559` | §5.1「高阶函数（lambda 传给别的函数）」行（**可识别**的高阶操作⇒展开成显式循环；`repeat` 不在已识别清单里 ⇒ 归入 §13 的 `KS1` 的「不可静态识别的高阶调用」）+ §13 的 `KS1` |
| A-50 | `requireNotNull` / `checkNotNull` | KEPT | `KTT:1461-1491` | 保持现状（无需改动）—— §5.1「`requireNotNull`/`checkNotNull`」行（现有 `NULL_CHECK`+`CALL`，保留） |
| A-51 | `require` / `check` | DEGRADED | `KTT:1509-1522` | §5.1「`require`/`check`/`error`/`TODO()`」行（`CALL`+`THROW ctorType=java.lang.IllegalArgumentException`/`IllegalStateException`）+ §11 改动清单 |
| A-52 | `error` / `TODO()` | DEGRADED | `KTT:1509-1522` | §5.1「`require`/`check`/`error`/`TODO()`」行（`CALL`+`THROW ctorType=…`） |
| Kotlin #24 | smart casts | DEGRADED | `KTT:829-874` | §5.1「智能转换（smart cast）」行（**非空性部分**：`NULL_CHECK` ⇒ **完整**；**类型收窄部分**：§13 的 `KS5` —— **IR-Z 落地后完整支持**（`BRANCH` 带 `Map<String,TacType> narrowedTypes`），IR-Z 未落地时才退回「保留声明类型+`TacDrop(STRUCTURE)`」）+ §5.1「`is`/`!is`」行 （**已覆盖**：**IR-Z**，`01` §10.2 已登记 ⇒ 不再是例外） |
| Kotlin #19 | `this` / `super` / `this@label` | DROPPED-NOTED | `KTT:764-821` | §5.1「`this`/`super`/`this@label`」行（`this`=名为 `"this"` 的局部量（Kotlin 侧**要补注册**，常量在 `KTT:1933` 但从未入表）；`super.f()`=`CALL` 的 `args[0]` 放 super 接收者）+ §11 改动清单「`translatePropertyRead`（`KTT:895-911`）\| 注册名为 `"this"` 的局部量」 |
| Kotlin #25 | implicit `it` | DROPPED-SILENT | (only reachable inside a dropped lambda) | §5.1「作用域函数 `let`/`run`/`also`/`apply`/`with`」行（`let{it->body}`→`MOVE dest=it lhs1=recv` ⇒ 隐式 `it` 就是**普通闭包形参**）+ §8.2.2（合成函数的形参表：闭包形参在前、捕获形参在后） |

### A.3 Coroutines

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| Kotlin #14 | `suspend fun` body | DEGRADED | `KTT:236`, `KTT:307-309` | §6.9 协程挂起（状态机展开：按挂起点切段，每段一个合成函数 `f$state$N`；挂起点挂 `Effect.SUSPEND`；函数级 `resumePoints`）+ §4.1「挂起函数」行；**恢复顺序与线程交错**进 §13 的 `KS2` （`B` 档：记 `drop(SEMANTICS, "coroutine.resumeOrder")`） |
| Kotlin #14 | `launch` / `async` / `coroutineScope` | DROPPED-NOTED | `KTT:764-821` | §6.9（`launch{}/async{}/runBlocking{}`⇒块是闭包⇒合成函数+`CALL`，调用点挂 `Effect.CONCURRENT`）+ §8.2 + §13 的 `KS2` （`B` 档：记 `drop(SEMANTICS, "coroutine.resumeOrder")`） |
| Kotlin #14 | `await` | DEGRADED | `KTT:1509-1522` | §6.9（`await()`⇒`CALL`+`Effect.SUSPEND`）+ §13 的 `KS2`（恢复顺序） （`B` 档：记 `drop(SEMANTICS, "coroutine.resumeOrder")`） |
| Kotlin #14 | `Flow` `collect` / `emit` | DROPPED-NOTED | `KTT:764-821` | §6.9（块是闭包⇒合成函数+`CALL`，调用点挂 `Effect.CONCURRENT`）+ §8.2 + §13 的 `KS2` （`B` 档：记 `drop(SEMANTICS, "coroutine.resumeOrder")`） |
| Kotlin #14 | concurrency effect | DROPPED-SILENT | `TAC/TacInstruction.java:172-190` (`Effect.CONCURRENT`) is never emitted | §6.9（调用点挂 `Effect.CONCURRENT`；`Effect.SUSPEND` 标挂起点）+ §13 的 `KS2`（并发类规则报「**可能**发生」而非「发生了」） （`B` 档：记 `drop(SEMANTICS, "coroutine.resumeOrder")`） |

### A.4 Statements

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| Kotlin #8 | `while` | DROPPED-NOTED | `KTT:343-407` | §6.5 循环 / break / continue / label（循环模板 + 迭代协议伪码 `it = CALL java.lang.Iterable.iterator/0 args=[xs]`）+ §11 改动清单「`translateStatement`（`KTT:343-407`）\| …`while`/`do-while`/`for`⇒循环模板」 |
| Kotlin #8 | `do-while` | DROPPED-NOTED | `KTT:343-407` | §6.5（先执行体再测条件⇒额外一个 `LOOP_BODY_END` 标签）+ §11 改动清单 |
| Kotlin #8 | `for` | DROPPED-NOTED | `KTT:343-407` | §6.5（`for (x in xs)` 走**迭代协议**（IR-P 规定真实方法名，不得用合成短名））+ §11 改动清单 |
| Kotlin #8 | `break` / `continue` | DROPPED-NOTED | `KTT:343-407` | §6.5（`break`/`continue`⇒`JUMP`，`labelRef.kind`=`LOOP_END`/`LOOP_HEAD`，`scopeId`=循环作用域）+ §11 改动清单 |
| Kotlin #8 | labelled `break` / `continue` | DROPPED-NOTED | `KTT:343-407` | §6.5（带标签的 `break@outer`⇒`JUMP` 到 `outer` 的 `LOOP_END`（`G` 的 kind+scopeId 必须能区分内外层））+ §11 改动清单 |
| A-01 | `return` | KEPT | `KTT:483-500` | 保持现状（无需改动）—— §6.5（`return@label` 与普通 `return` **必须**区分 ⇒ §11 改动清单「`translateStatement`…`return`」） |
| Kotlin #23 | labelled `return@label` | DEGRADED | `KTT:483-500` | §6.5（`return@label`（Kotlin 非局部返回）⇒`JUMP` 到该标签函数的 `FUNCTION_EXIT`；**必须**与普通 `return` 区分） |
| Kotlin #12 | `throw` | DROPPED-NOTED | `KTT:343-407` | §11 改动清单「`translateStatement`（`KTT:343-407`）\| …`throw`⇒`THROW`+`ctorType`」+ §5.1「`require`/`check`/`error`/`TODO()`」行 + §8 IR 记号 J（`THROW.ctorType`） |
| Kotlin #9 | `try`/`catch`/`finally` (statement) | DROPPED-NOTED | `KTT:343-407` | §6.6 try / catch / finally + §11 改动清单「`translateStatement`（`KTT:343-407`）\| …`try`⇒try/catch/finally+`TryRegion`」 |
| Kotlin #1 | **assignment** (`x = v`) | **DROPPED-SILENT** | `KTT:376-384` | §5.1 Kotlin 表达式面表「复合赋值 `x += v` / 赋值 `x = v`」行（`MOVE dest=x lhs1=v`）+ §11 改动清单「`KotlinTacTranslator.java` \| `translateStatement`（`KTT:343-407`）\| 赋值⇒`MOVE`/`FIELDSTORE`」 |
| Kotlin #2 | **compound assignment** (`x += v`) | **DROPPED-SILENT** | `KTT:376-384` | §5.1「复合赋值 `x += v` / 赋值 `x = v`」行（`MOVE dest=x lhs1=(BINOP x v)`；属性⇒`FIELDSTORE ownerType=`）+ §11 改动清单「赋值⇒`MOVE`/`FIELDSTORE`」 |
| A-02 | `++` / `--` | DROPPED-NOTED | `KTT:764-821` | §5.1「自增/自减 `x++`」行（`BINOP ADD`+`MOVE`（`x++` 先取值后写））+ §11 改动清单「`translateExpression`…新增分派：…`++`/`--`」 |
| A-03 | local function | DROPPED-NOTED | `KTT:343-407` | §4.1「局部函数」行（合成函数，名字=`<enclosing>.<name>$local`；调用点用**同一名字**）+ §11 改动清单 |
| A-04 | local class | DROPPED-NOTED | `KTT:343-407` | §4.1「嵌套类 / 局部类 / 匿名对象」行（各自合成类型 + `CONSTRUCTOR`）+ §11 改动清单「`translateStatement`…局部函数⇒合成函数」 |
| A-05 | `when` (statement form) | DEGRADED | `KTT:610-752` | §6.1 / §6.2（`when` 的两种形式）+ §11 改动清单「`translateWhen`（`KTT:610-752`）」 |

### A.5 Type-system features

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A-01 | nullability | KEPT | `KTM:269-283` | 保持现状（无需改动）—— §7 类型面方案「Kotlin `String`/`Unit`/`Any`」行（`?` 决定可空）+ §5.1「智能转换」行（非空性部分由 `NULL_CHECK` **完整**表达） |
| A-02 | platform types (Java seen from Kotlin) | KEPT | `KTM:294-308` | 保持现状（无需改动）—— §7 类型面方案「**平台类型**（Java 类型）」行（**恒可空**，`KTM:294-308`） |
| A-03 | generics | DEGRADED | `KTM:117-119` | §7 类型面方案「Kotlin `List<String>`/`Array<Int>`/`IntArray`」行（`typeArguments`）+「Kotlin 类型参数 `T`」行（`UNKNOWN`+`bound`；`TacTypeParam{variance}`，`N`）+ §11 改动清单「`KotlinTypeMapper.java` \| `map`（`KTM:112-132`）\| 泛型实参；类型变量 `bound`」 |
| Kotlin #27 | variance / type projections | DROPPED-SILENT | `KTM:112-132` | §7 类型面方案「变型 `in`/`out`/`*`」行（不进 `TacType`，进 `TacTypeDecl.typeParameters`，`N`）+ §11 改动清单「`KotlinTypeMapper.java` \| `map`」 |
| Kotlin #27 | sealed hierarchies | DROPPED-SILENT | `KTF:455-507` | §4.1「类/接口/密封类」行（`permittedSubtypes`）+ §6.1（`exhaustive` 由 sealed 层次判定） |
| A-04 | extension receivers | DEGRADED | `KTT:1613-1620` | §4.1「扩展函数 / 扩展属性」行（接收者=**首个 TAC 形参**）+ §10 IR-P 细则表「接收者」行（一律显式作为 `args[0]`） |
| A-09 | contracts | **NOT FOUND** | no contract handling in the kotlin package | §7 类型面方案「**不进运行期 IR 的类型信息**」第 ④ 条（**编译期契约/opt-in**（Kotlin `contracts`、`@RequiresOptIn`）——编译期约束⇒`TacDrop(STRUCTURE)`）+ §9 注解与元数据方案。**审计标 NOT FOUND** ⇒ 编译期约束，**运行期没有对应物**（`02` §3.4 根因四）⇒ 记 `STRUCTURE` 级 drop，**对 TAC 运行时不产生影响**；只影响「依赖类型推断的规则」 |
| A-05 | `@RequiresOptIn` | DROPPED-SILENT | no annotation reading at all | §7 类型面方案「**不进运行期 IR 的类型信息**」第 ④ 条（**编译期契约/opt-in**（Kotlin `contracts`、`@RequiresOptIn`）——编译期约束⇒`TacDrop(STRUCTURE)`）+ §9 注解与元数据方案 |
| Kotlin #11 | default arguments | DROPPED-SILENT | `KTT:1457`, `KTT:1613-1620` | §10 `IR-AC`（已并入 `IR-AO`）+ §11 改动清单「`calleeSignature`（`KTT:1613`）\| …默认参数填 `d$default$N`」 |
| A-06 | data class generated members | DEGRADED | `KTF:525-597` | §4.1「数据类」行（合成成员改为**真实体**）+ §11 改动清单「`translateGeneratedMembers`（`KTF:525-597`）」 |
| A-07 | `inline` / `reified` | DEGRADED | `KTT:764-821` | §4.1「内联函数 / `reified`」行 + §13 的 `KS3`（`inline` 展开是编译期产物 ⇒ 不展开就无法表达非局部返回；**可由 S8 的展开工作消除**） |
| A-08 | value classes | DROPPED-SILENT | `KTF:455-507` | §4.1「值类」行（运行期表示=被包装类型）+ §11 改动清单 + §12 门禁 D1 |
| A-10 | context receivers | **NOT FOUND** | — | 同附录 A.1 的「A-53 context receivers」行（**同一个构念在审计里被登记了两次**：一次在声明面 A.1、一次在类型系统面 A.5）⇒ §4.1「**上下文接收者**」行 + §11 改动清单「`translateFunction`（`KTF:722-851`）…上下文接收者」行 |
| Kotlin #29 | expect / actual | DEGRADED | `KTT:256-262` | §11 改动清单「`translateGeneratedMembers`（`KTF:525-597`）、`translateFunction`（`KTF:722-851`）\| …`expect`/`actual` 合并」+ §14 风险 7 |

### A.6 Cross-cutting (things that are not one construct but break many)

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| Kotlin #3 | read of the enclosing class's own property | DROPPED-SILENT | `KTT:888-911` | §5.1「`this`/`super`/`this@label`」行（`this`=名为 `"this"` 的局部量）+ §11 改动清单「`translatePropertyRead`（`KTT:895-911`）\| 注册名为 `"this"` 的局部量…属性读⇒`FIELDLOAD`+`ownerType`」+ §2 现状差距表「`this`」行 |
| Kotlin #4 | write of a class property | DROPPED-SILENT | `KTT:376-384`; `FIELDSTORE` appears 0× in the kotlin package | §5.1「复合赋值 `x += v` / 赋值 `x = v`」行（属性⇒`FIELDSTORE ownerType=`）+ §11 改动清单「`translateStatement`…赋值⇒`MOVE`/`FIELDSTORE`」+ §4.1「类属性 `val`/`var` 与自定义 getter/setter」行 |
| A-01 | read of a top-level property | DROPPED-SILENT | `KTT:895-900` | §4.1「顶层 `val`/`var`」行（合成 `FIELD_INITIALIZER`；读=`CALL` 该函数）+ §11 改动清单「`translateFile`…顶层属性⇒`FIELD_INITIALIZER`」 |
| Kotlin #6 | annotations (all four channels) | DROPPED-SILENT | `TAC/TacFunction.java:116-126` | §9 注解与元数据方案 + §11 改动清单 + §4.1 声明面表的注解通道列 |
| Kotlin #7 | callee owner for top-level / extension Kotlin functions | DROPPED-SILENT | `KTT:1641-1667` | §10 IR-P 细则表「顶层函数/扩展函数的 owner」行（顶层函数 owner=所在**包名**）+ §11 改动清单「`ownerOf`（`KTT:1641`）\| …顶层函数 owner=包名」+ §4.1「扩展函数 / 扩展属性」行 |
| Kotlin #26 | visibility modifiers | DROPPED-SILENT | `KTF:722-851` | §4.1「**可见性修饰符**」行 + §10「可见性分级」（`TacVisibility`） |
| Kotlin #12 | exception modelling (`THROW`/`CATCH_HANDLER`/`FINALLY_ENTER`/`tryRegions`) | DROPPED-NOTED | `KTT:343-407`; `TAC/TacFunction.java:171` | §6.6 try / catch / finally（`CATCH_HANDLER dest=e ctorType=…`；`FINALLY_ENTER`；`TryRegion{ catchTypes, resourceVars, syntheticFinally, finallyExits }`）+ §8 IR 记号 J + §11 改动清单 |
| Kotlin #28 | array access (`ARRAYLOAD`/`ARRAYSTORE`/`NEWARRAY`) | DROPPED-NOTED | `KTT:764-821` | §5.1「数组下标」行（`ARRAYLOAD`/`ARRAYSTORE`）+ §11 改动清单「`translateExpression`（`KTT:764-821`）\| 新增分派：…数组下标…」 |

**附录 A 小计**：**127 条**（= 审计 A 节的**全部 127 行**，一行不缺）= 原附录 **30 条**（编号 `Kotlin #1`–`#30`）+ 本轮**新增 97 条**。其中 `KEPT` **17 条**（去向=「保持现状（无需改动）」）、问题项 **107 条**（`DEGRADED` **31** + `DROPPED-*` **76**）⇒ **每个问题项都有落点**（A 完整表达 **95** / B 显式记账 **11** / C 永久例外 **4**；含审计标 `NOT FOUND` 的 3 行）。**`⚠` 未覆盖 = 0**。

---

## 附录 B. Scala 审计台账 —— 审计 A 节**逐行**去向（127 行）

**审计来源**：`D:\work\tmp\audit\03-scala.md` 的 `## A. Coverage ledger` 节（`### A.1 Definitions` … `### A.4 Type-system features`，共 127 行）。

**覆盖口径**：审计 A 节的**每一行**都在下表里，**127 行 ⇒ 127 条，一行不缺**。其中审计标 `N/A` 的 3 行（symbol literal、`do … while`（表达式面）、`do`-`while` 语句 —— **该构念在本语言里不存在**，Scala 3.10.0 在解析期就拒绝）**也给了落点**（去向写「不适用（审计已判 N/A：…）」）；审计标 `UNCLEAR` 的 2 行（labelled `return`，表达式面 + 语句面）**也给了落点**（本方案判定它 = **非局部返回** ⇒ §6.5）；审计标 `NOT FOUND` 的 6 行（compound literals、erased value types、`transparent inline`、`scala.compiletime`）**也给了落点**（本方案**新增支持**，或按「编译期标记」记 drop）。审计的官方 `checked` 口径会把这 5 行排除（`checked = 127 − 5 = 122`），本附录的口径是**全量 127 行**；两套口径的差值逐条见 `12-语法覆盖情况.md` §1。`KEPT` 的行**保留状态**，去向写「保持现状（无需改动）」并指出形状在方案的哪一行登记。

**「审计状态」列**沿用审计的词（`KEPT` / `DEGRADED` / `DROPPED-SILENT` / `DROPPED-NOTED`），不新造缩写。

**「#」列的编号口径**：`Scala #B N` = 审计 **B 节**（Dropped list）的原编号 —— **原附录已有**，去向信息更详细，予以保留；`A<n>-<k>` = 审计 **A 节** `A.n` 子节的第 k 行 —— **原附录未覆盖**，本轮补入。

**子小节**照审计 A 节的子小节组织（`### A.1 Definitions` … `### A.4 Type-system features`），便于逐节核对。
### A.1 Definitions

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A-01 | class | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:332-413` | 保持现状（无需改动）—— §4.2「类 / trait」行（`TacTypeDecl`；成员 `enclosingClass`=类 FQN） |
| A-02 | trait | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:332-413` | 保持现状（无需改动）—— §4.2「类 / trait」行（trait `isInterface=true`） |
| A-03 | object | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:466-495` | 保持现状（无需改动）—— §4.2「object（模块类/package object）」行（成员静态化⇒`ownerType`=模块类；初始化=`STATIC_INITIALIZER`） |
| A-04 | package | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacFrontend.java:587-593` | 保持现状（无需改动）—— §4.2「object（模块类/package object）」行（模块类 FQN `demo.TopLevel$`） |
| A-05 | package object | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:477-495` | 保持现状（无需改动）—— §4.2「object（模块类/package object）」行 |
| Scala #B13 | enum (Scala 3) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1876-2000` | §4.2「enum」行（条目=合成访问函数（体 `NEW`）；`values`=`NEWARRAY`）+ §4.2「枚举 case 值」行 + §10 `IR-AB`（`ENUM_ENTRY`）+ §12 门禁 D2 |
| Scala #B12 | case class | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:552-581`, `:583-605` | §4.2「case class」行（构造器+`apply`/`unapply`/`copy`/`toString`/`equals`/`hashCode`/`_N` 全部**真实体**；`unapply` 返回合成元组（`NEW scala.Tuple2`））+ §11 改动清单 + §12 门禁 D2 |
| A-06 | case object | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:568-573` | §4.2「object（模块类/package object）」行（同 `object` 的模块类处理）+ §4.2「字段初始化器（类体里的 `val x = expr`）」行（`FIELD_INITIALIZER`）+ §11 改动清单「`collectDefs`（`ST:309-324`）\| 追加 **Template 级 `ValDef`**」 |
| Scala #B17 | sealed trait + permitted types | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:309-324` | §4.2「**类/trait 修饰符**（`final`/`sealed`/`abstract`/`case`）」行（`sealed` ⇒ `permittedSubtypes`（IR-N 既有字段）= **同一编译单元内**的直接子类型 ⇒ 直接供 §6.3 的 `exhaustive` 判定与 `missingCases`；另加 `isSealed`）—— **附录 C 的 U-SC1 已补齐** + §10「`IR-N` / `IR-E` 的 Scala 修饰符细化」条 + §11 改动清单 |
| Scala #B17 | abstract / final / sealed modifiers | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:332-413` | §4.2「**类/trait 修饰符**（`final`/`sealed`/`abstract`/`case`）」行（`abstract` ⇒ `TacTypeDecl.isAbstract`（IR-E 既有）；`final` ⇒ `TacTypeDecl.isFinal`（IR-E 补充细化）；`sealed` ⇒ `permittedSubtypes`+`isSealed`）—— **附录 C 的 U-SC1 已补齐** + §10 + §11 改动清单 |
| Scala #B15 | lazy val | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:309-324` | §4.2「`lazy val`」行（字段初始化器 + 合成 `GETTER`（惰性初始化 `BRANCH`+持有者字段））+ §11 改动清单「`collectDefs`（`ST:309-324`）\| 追加 **Template 级 `ValDef`**⇒…`lazy val`…」+ §12 门禁 D2 |
| A-07 | def | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:332-413` | 保持现状（无需改动）—— §4.2「类 / trait」行（成员 `memberKind=METHOD`；`enclosingClass`=类 FQN）+ §12 门禁 A |
| A-08 | var | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:852-879`, `:1689-1741` | 保持现状（无需改动）—— §4.2「字段初始化器（类体里的 `val x = expr`）」行 + §2 现状差距表「赋值」行（`translateAssign` 发 `MOVE`/`FIELDSTORE` ⇒ 本来就对） |
| A-09 | val | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:852-879` | 保持现状（无需改动）—— §4.2「字段初始化器（类体里的 `val x = expr`）」行（`FIELD_INITIALIZER`（体=`MOVE`+`FIELDSTORE ownerType`），在构造器里调用）+ §11 改动清单「`collectDefs`…追加 **Template 级 `ValDef`**」+ §12 门禁 D2 |
| Scala #B3 | class field initializers (`val x = expr` in a class/object body) | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:309-324` | §4.2「字段初始化器（类体里的 `val x = expr`）」行 + §11 改动清单「`collectDefs`（`ST:309-324`）\| 追加 **Template 级 `ValDef`**⇒字段初始化器/`given`/`lazy val`/枚举 case 值全部产生 `FIELD_INITIALIZER`」+ §12 门禁 D2 |
| A-10 | constructor params | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:423-438`, `:456-464` | §4.2「类 / trait」行（成员 `memberKind=CONSTRUCTOR`）+ §11 改动清单「`buildParams`（`ST:423-438`）\| 全部参数表展平」—— 编译器合成的**字段写**由 §4.2「字段初始化器」行的 `FIELD_INITIALIZER` 承载 |
| A-11 | default args | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:456-464` | §10 `IR-AC`（形参的可选性；**已按 Q11 裁定并入 `06` 的 `IR-AO`**）+ §11 改动清单「`translateClosure`…`calleeSignature`（`ST:1448`）\| …默认参数填 `d$default$N`」（Scala 侧已有 `d$default$2` 这类名字，`ST:456-464`） |
| Scala #B7 | named args | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §5.2 Scala 3 表达式面表「命名实参 `d(a = 1, b = 3)`」行（按**声明序**重排实参）+ §11 改动清单「`translateClosure`…`calleeSignature`（`ST:1448`）\| …命名实参按声明序重排」 |
| A-12 | varargs | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:423-438` | §4.2「多参数表/`implicit`/`using`」行（**全部展平**成一个 TAC 形参表）+ §10 `IR-AC`/`IR-AO`（形参的 `vararg` 槽位）+ §11 改动清单「`buildParams`…全部参数表展平」 |
| Scala #B5 | implicit param list | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:423-438` | §4.2「多参数表/`implicit`/`using`」行（**全部展平**成一个 TAC 形参表（现状 `buildParams` 只取第一张表，`ST:423-438`））+ §11 改动清单「`buildParams`…全部参数表展平；…`implicit`/`using` 解析后显式化」 |
| Scala #B5 | using param list | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:423-438` | §4.2「多参数表/`implicit`/`using`」行 + §11 改动清单「`buildParams`…`implicit`/`using` 解析后显式化」 |
| A-13 | context function (given/using) | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:423-438` | §4.2「`given`」行（合成 `FIELD_INITIALIZER` 返回该值；`summon[T]`→`CALL <given>`；隐式实参在调用点解析后**显式传参**）+ §4.2「多参数表/`implicit`/`using`」行 + §11 改动清单 |
| A-14 | extension methods | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | §4.2「扩展方法」行（接收者=首个 TAC 形参）+ §10 IR-P 细则表「接收者」行 |
| A-15 | type parameters + bounds | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「变型 `in`/`out`/`*`」行（类型参数与上界进 `TacTypeDecl.typeParameters`，IR-N —— **同一机制**覆盖 Scala 的 `type parameters + bounds`）+ §10「`IR-N` / `IR-E` 的 Scala 修饰符细化」条（`TacTypeDecl` 上的类型声明面） |
| A-16 | variance (`in`/`out`/`+`) | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「变型 `in`/`out`/`*`」行（不进 `TacType`，进 `TacTypeDecl.typeParameters`，`N`） |
| A-17 | type members | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §7 类型面方案「**不进运行期 IR 的类型信息**」第 ③ 条（**类型级计算**——路径依赖类型的精化、单例类型的唯一性、`deriving` 产物——不产生任何运行期值⇒对 TAC 运行期无影响；可静态判定的部分（解析后的类名）照常进 `TacType`）+ §11 改动清单「`translateExpr`（`ST:955-1038`）\| 新增分派：…`TypeDef`…」 |
| A-18 | structural types | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §13 的 `KS6`（**结构类型与反射式成员访问**——**无法完整支持**：成员是否存在、值是什么，取决于运行期对象状态与注册表；源码里没有这个事实）+ 其保守建模形状「`CALL`+`UNINTERPRETED`；结果按**未知**处理」 |
| A-24 | compound literals | NOT FOUND | no handling code between `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:955-1038` | §5.2 Scala 表达式面表「**复合字面量 / 元组字面量** `(1, 2)`」行（`NEW scala.Tuple2` + 逐组件 `FIELDSTORE`；`calleeSig` 用**剥掉类型实参后的 FQN**，IR-P）+ §11 改动清单「`translateExpr`（`ST:955-1038`）」行。**审计标 NOT FOUND** ⇒ 现有实现**完全没有**这一分支 ⇒ 本方案**新增支持**（不新造 IR） |
| A-19 | type projections | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「Scala union `A \| B` / 交集 `A & B`」行 +「变型 `in`/`out`/`*`」行（类型维度进 IR-K 的 `typeArguments`/`bound`/`unionMembers`）+ §11 改动清单「`ScalaTypeMapper.java` \| `mapDealiased`（`STM:80-121`）\| 类型实参、union 成员、路径依赖类型；禁止假类名」 |
| A-20 | dependent method types | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「Scala 路径依赖类型 `a.B` / 单例类型 `x.type`」行（`CLASS("a.B")` / `CLASS(<x 的类>)`；单例类型记 `STRUCTURE` drop）+ §11 改动清单「`ScalaTypeMapper.java` \| `mapDealiased`」 |
| Scala #B11 | annotations (`@main`, `@tailrec`, `@inline`, `@throws`, `@volatile`) | DROPPED-SILENT | no `Annotation` call anywhere under `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/` (grep: zero matches) | §9 注解与元数据方案（Scala：dotc 的 `Symbol.annotations`（声明注解）与 `Trees.Annotated`（类型注解）⇒同样四通道）+ §11 改动清单 + §4.2 Scala 3 声明面表的注解通道列 |
| A-21 | opaque type aliases | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §4.2「opaque type / 类型别名」行（无运行期效果⇒`TacDrop(STRUCTURE)`；别名关系记 `TacTypeDecl`） |
| A-22 | export clauses | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | 保持现状（无需改动）—— dotc 自己把 `export` 展开成转发方法 ⇒ 那条转发方法走 §4.2「类 / trait」行的普通成员路径 |
| Scala #B16 | `given` (Scala 3) | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:309-324` | §4.2「`given`」行（合成 `FIELD_INITIALIZER` 返回该值；`summon[T]`→`CALL <given>`；隐式实参在调用点解析后**显式传参**）+ §11 改动清单「`collectDefs`…追加 **Template 级 `ValDef`**⇒…`given`…」 |
| A-23 | inline defs | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | §4.2「`inline`/`transparent inline`/`inline match`」行（同 Kotlin：展开=S8）+ §13 的 `KS3`（`inline` 展开是编译期产物 ⇒ 不展开就无法表达非局部返回） |
| A-25 | erased value types | NOT FOUND | no handling code between `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:955-1038` | §7 类型面方案「**不进运行期 IR 的类型信息**」第 ⑥ 条（**擦除标记**——`erasedValue[T]` 只是**给编译器看**的类型标记，运行期既无值也无类型⇒`TacDrop(STRUCTURE)`）+ §11 改动清单「`translateExpr`（`ST:955-1038`）」行。**审计标 NOT FOUND** ⇒ 现有实现**完全没有**这个构念 ⇒ 本方案按「编译期标记」处理（**不新造 IR**） |
| A-26 | `transparent inline` | NOT FOUND | no handling code between `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:955-1038` | §4.2「`inline`/`transparent inline`/`inline match`」行（同 Kotlin：展开=S8）+ §13 的 `KS3`（`inline` 展开是编译期产物 ⇒ 不展开就无法表达非局部返回）+ §7 类型面方案第 ⑥ 条（`transparent inline` 本身只是**擦除标记**⇒`STRUCTURE` drop）。**审计标 NOT FOUND** ⇒ 现有实现**完全没有**这个构念 ⇒ 本方案**新增支持** |

### A.2 Expressions

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A-01 | literals (int/long/float/double/bool/string) | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1078-1113` | 保持现状（无需改动）—— §5.2「字面量」行 |
| A-40 | literal — symbol literal | N/A | Scala 3.10.0 rejects `'sym` at parse time ("symbol literal 'sym is no longer supported"); the file then fails to compile and is reported as a `PARSE_PROBLEM` | 不适用（审计已判 N/A：**Scala 3.10.0 在解析期就拒绝** `'sym` ⇒ **本语言里不存在这个构念**）—— 构造不出可分析的 TAC；该失败本身走 §11 改动清单「翻译器的**文本**诊断接上对外通道」行（对应附录 C 的 U-SC2「翻译器的**文本**诊断通道没有出口」；`PARSE_PROBLEM` 必须能到对外通道，否则这类文件会被当成「扫描成功」） |
| A-02 | BigInt / BigDecimal | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | 保持现状（无需改动）—— §5.2「字面量」行（Scala 3 里它们是普通构造器调用） |
| A-03 | char literal | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1052-1063`, `:1090-1095` | 保持现状（无需改动）—— §5.2「字面量」行（`Char` 必须走 `charValueOf`，dotc 把 `Char` 装箱成 `java.lang.Character`） |
| Scala #B8 | interpolated strings `s"..."` / `f"..."` / `raw"..."` | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037`, `:998-1002` | §5.2「字符串插值 `s"..."`/`f"..."`/`raw"..."`」行（dotc 把**字面量段与插值值**都包进 `Trees.SeqLiteral` ⇒拆开后建 `BINOP ADD` 链）+ §11 改动清单「`translateExpr`…新增分派：…`SeqLiteral`（插值）…」 |
| A-04 | multi-line strings | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1104-1105` | 保持现状（无需改动）—— §5.2「字面量」行 |
| A-05 | arithmetic/bitwise/relational operators | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1567-1622` | 保持现状（无需改动）—— §5.2「字面量」行（同族的普通算术/比较走同一条 `BINOP` 路径） |
| A-06 | `::` `:::` `++` `++=` `->` `<-` `?` `^` `&&` `\|\|` `!` | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1567-1622` | §5.2 Scala 表达式面表「**运算符面**」行：① `&&`/`\|\|` ⇒ **分支链**（短路语义必须可见）；② `\|` ⇒ **按语境分流**（按位或 ⇒ `BINOP OR`；**模式备选 ⇒ 零指令**，进 §6.3 的模式链，不与按位或共用 opcode）；③ 表外的名字逐个给目标形态（`::`/`:::`/`++`/`++=` ⇒ **真实方法名** `CALL`（IR-P），`!` ⇒ `UNARYOP NOT`）；④ `->`/`<-`/`?` ⇒ 类型层 / 可去糖 ⇒ 零指令（`<-` 在 §6.7 去糖成循环，`->`/`?` 记 `drop(STRUCTURE)`）；⑤ 用户类型上的运算符 ⇒ `CALL <owner>.<方法名>/2`（IR-P），不再静态绑定 + §11 改动清单「`binaryOperator`（`ST:1567-1622`）」行 |
| A-07 | assignment operators (`+=` etc.) | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1689-1741` | 保持现状（无需改动）—— §2 现状差距表「赋值」行（`translateAssign` 发 `MOVE`/`FIELDSTORE`（`ST:1689-1741`、`:1753-1766`）⇒ 本来就对） |
| A-08 | `if`/`else` as a **statement** | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1776-1807` | 保持现状（无需改动）—— §5.2「`if` 作为表达式」行（§6.4；两臂的值今天全丢 ⇒ 那是**表达式**那一半） |
| Scala #B2 | `if`/`else` as an **expression** | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1806` | §5.2「`if` 作为表达式」行（§6.4：两个分支**都必须**写 `result`，Scala 现状 `ST:1806` 直接 `return null`⇒两臂的值全丢）+ §11 改动清单「`translateIf`（`ST:1776-1807`）\| `if` 返回结果临时量（表达式语义）」 |
| A-09 | `match` — typed pattern (`case s: String`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1909-1939` | §6.3 `match`（Scala）表的「类型 `case s: String`」行（`INSTANCEOF`+`TypeTest`；绑定 `MOVE`，类型收窄） |
| A-10 | `match` — literal pattern (`case 1 =>`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1940-1974` | §6.3「常量 `case 1`」行（`BINOP EQ`+`Compare`） |
| Scala #B4 | `match` — binding pattern (`case x @ 1`) | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2048-2057` | §6.3「绑定 `case x @ 1`」行（先测常量 `1`，再 `MOVE x ← selector`；`x`=整个被匹配值）+ §10 `IR-AA`（`TacBinding.path` + `PatternKind`）+ §12 门禁 B⑤ |
| Scala #B4 | `match` — wildcard (`case _ =>`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1944-1952` | §6.3「通配 `case _`」行（无测试，直接落链尾）+ §10 `IR-AA` + §12 门禁 B⑤（消除「未识别模式走通配路径并丢弃后续 case」的分支） |
| A-11 | `match` — typed wildcard (`case _: T =>`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1909-1939` | §6.3「通配 `case _`」行 + 「类型 `case s: String`」行（`case _: T` ⇒ **只有类型测试**，不绑定变量） |
| Scala #B4 | `match` — tuple pattern | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2018-2057` | §6.3「元组 `case (a, b)`」行（`INSTANCEOF subject, scala.Tuple2`；`a`/`b`=`FIELDLOAD subject.$1/$2`；路径写进 `TacBinding.path`，IR-AA）+ §10 `IR-AA` |
| Scala #B4 | `match` — case class / `unapply` pattern | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2018-2057` | §6.3「case class/提取器 `case P(a, b)`」行（`opt=CALL <owner>.unapply/1 args=[subject]`；`ok=CALL Option.isDefined/1`；`BRANCH ok`；`unapply` 的 `CALL` **必须**进 TAC）+ §10 `IR-AA` |
| Scala #B4 | `match` — alternative pattern (`case 1 \| 2`) | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2018-2057` | §6.3「备选 `case 1 \| 2`」行（一个 `Membership`（多 guard，语义 OR））+ §10 `IR-AA` |
| A-12 | `match` — nested pattern (`case Some(Some(v))`) | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2018-2057` | §6.3（嵌套模式 ⇒ **逐层**展开：外层 `INSTANCEOF` + 内层 `unapply`/`INSTANCEOF`；每层的绑定路径写进 `TacBinding.path`，IR-AA）+ §10 `IR-AA` |
| A-13 | `match` — guard (`case x if p`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1976-1992` | §6.3「守卫 `case x if p`」行（守卫失败**落到下一个 case**）+ §12 门禁 B⑤ |
| Scala #B6 | `try`/`catch`/`finally` as an expression | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §6.6 try / catch / finally（`CATCH_HANDLER dest=e ctorType=…`；Scala `catch { case e: E => ... }`⇒catch 体是一个 `match`，用 §6.3 降级）+ §11 改动清单「`translateExpr`…新增分派：`Trees.Try`…」 |
| Scala #B10 | `for` comprehension — generator | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1334-1336` | §6.7 for 推导式与集合操作（Scala）（显式循环伪码 `it = CALL <Iterable.iterator/0>(xs)`）+ §11 改动清单「`receiverOf`（`ST:1334-1356`）\| 解 `TypeApply`/`Apply` 包装⇒泛型调用的接收者不再丢」 |
| Scala #B10 | `for` comprehension — guard (`if` inside `for`) | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1334-1336` | §6.7（guard 的 `withFilter` ⇒ if (!p(x)) JUMP L_head）+ §11 改动清单「`receiverOf`…解 `TypeApply`/`Apply` 包装」 |
| A-14 | `for` comprehension — pattern generator (`for ((a,b) <- ps)`) | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2018-2057` | §6.7（元素绑定）+ §6.3「元组 `case (a, b)`」行（元组绑定路径写进 `TacBinding.path`，IR-AA） |
| A-15 | `for` comprehension — value form (`yield`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1334-1336` | §6.7（带 `yield` 累加到结果）+ §11 改动清单 |
| A-16 | `for` comprehension — nested | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1334-1336` | §6.7（`flatMap`=两层迭代协议）+ §11 改动清单 |
| A-17 | `while` | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1816-1842` | 保持现状（无需改动）—— §6.5（循环模板 + 回边） |
| A-41 | `do … while` | N/A | Scala 3.10.0 rejects `do` ("Illegal start of statement") — the construct does not exist in this language | 不适用（审计已判 N/A：**Scala 3.10.0 在解析期就拒绝** `do` ⇒ **本语言里不存在这个构念**）—— 构造不出可分析的 TAC；本方案**不为此设计任何 TAC 形态** |
| A-18 | `throw` | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1021-1027` | §8 IR 记号 J（`THROW.ctorType`：抛出点带类型）+ §6.6 try / catch / finally（catch 侧 `CATCH_HANDLER dest=e ctorType=…`）+ §11 改动清单「`translateExpr`…新增分派」—— **注**：§5.2 的 Scala 表达式面表**没有 `throw` 行**，抛出点本身的形状写在 IR 记号 J 与 §6.6 里 |
| A-19 | `Try` / `Success` / `Failure` | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | 保持现状（无需改动）—— §5.2「`Option`/`Either`/`Try`」行 |
| A-20 | `Either` / `Option` / `Some` / `None` | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | 保持现状（无需改动）—— §5.2「`Option`/`Either`/`Try`」行（`Option` **不是**空安全机制，`STM:43-46`） |
| A-21 | lambdas (all forms) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1003-1007`, `:2196-2241` | §8 闭包与捕获方案 §8.2（体=合成函数+`CALL`（`ST:2196-2241`）；捕获=共享 holder cell）+ §5.2「lambda/占位符 `_ + 1`」行 + §11 改动清单「`translateClosure`（`ST:2196-2241`）…`CALL` 带上闭包形参与捕获值」 |
| A-22 | partial functions | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2196-2241` | §5.2「eta-expansion `f _`/部分函数」行（作为值⇒§13 的 `KS1`）+ §13 的 `KS1`（**一等函数值与高阶调用的回调绑定**——**无法完整支持**：函数值没有运行期表示） |
| A-23 | eta-expansion (`f _`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1231-1233` | §5.2「eta-expansion `f _`/部分函数」行（立即应用⇒`CALL`；作为值⇒§13 的 `KS1`） |
| A-24 | placeholder syntax (`_ + 1`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2196-2241` | §5.2「lambda/占位符 `_ + 1`」行（占位符=普通闭包形参） |
| A-25 | `new` / anonymous class | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1637-1658` | §4.2「类 / trait」行（`NEW` + 合成构造器；成员 `enclosingClass`=类 FQN）+ §11 改动清单「`translateExpr`（`ST:955-1038`）\| 新增分派」 |
| Scala #B14 | `this` as a value | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1008-1012` | §5.2「`this`」行（`thisReceiver` 创建名为 `"this"` 的局部量（`ST:1250-1261`），保留）+ §2 现状差距表「`this`」行 |
| A-26 | `super` | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §5.2「`super`」行（`CALL` 的 `args[0]` 放 super 接收者）+ §11 改动清单「`translateExpr`…新增分派：…`Super`…」 |
| A-27 | self-type (`{ self: A => }`) | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:332-413` | §4.2「类 / trait」行（自类型 `{ self: A => }`=前导隐式形参） |
| A-28 | `synchronized` | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1334-1336` | §13 的 `KS7`（锁争用顺序：**无法完整支持**）+ 其保守建模形状「临界区范围进 IR（IR-M）+`Effect` 标记」 （`B` 档：记 `drop(SEMANTICS, "lock.contention")`） |
| Scala #B15 | lazy (evaluation) | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:309-324` | §4.2「`lazy val`」行（合成 `GETTER`（惰性初始化 `BRANCH`+持有者字段））+ §11 改动清单「`collectDefs`…追加 **Template 级 `ValDef`**」 |
| A-29 | by-name parameters (`x: => T`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:423-438` | §4.2「by-name 形参 `x: => T`」行（降级为**函数值**：合成零参函数+每次使用一个 `CALL`） |
| A-30 | implicit conversions | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §11 改动清单「`translateExpr`…新增分派：…`Inlined`…」+ §7 类型面方案「**不进运行期 IR 的类型信息**」第 ③ 条 |
| Scala #B16 | implicit values | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:309-324` | §4.2「`given`」行（合成 `FIELD_INITIALIZER` 返回该值）+ §11 改动清单「`collectDefs`…追加 **Template 级 `ValDef`**⇒…`given`…」 |
| Scala #B5 | `using` | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:423-438` | §4.2「多参数表/`implicit`/`using`」行（全部展平成一个 TAC 形参表）+ §11 改动清单「`buildParams`…`implicit`/`using` 解析后显式化」 |
| Scala #B7 | `NamedArg` | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §5.2「命名实参 `d(a = 1, b = 3)`」行（按**声明序**重排实参）+ §11 改动清单「`translateClosure`…`calleeSignature`（`ST:1448`）\| …命名实参按声明序重排」 |
| A-31 | blocks | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:821-832` | 保持现状（无需改动）—— §5.2「块」行（最后一个表达式的值） |
| A-32 | `return` | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1013-1020` | 保持现状（无需改动）—— §6.5 |
| A-42 | labelled `return` | UNCLEAR | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1013-1020` | **审计标 UNCLEAR（未判定）**；本方案的判定：Scala 的带标签 `return` 与 Kotlin 的 `return@label` 是**同一种非局部返回** ⇒ §6.5「**Scala 的带标签 `return`**」条（`JUMP` 到该标签函数的 `FUNCTION_EXIT`；**必须**与普通 `return` 区分）+ §11 改动清单「`translateReturn`（`ST:1013-1020`）」行；**例外**：位于 `inline` 方法的 lambda 内时归 §13 的 `KS3`（`inline` 展开后的非局部返回） |
| A-33 | `?` wildcard type | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「Scala union `A \| B` / 交集 `A & B`」行（`?` 通配 ⇒ `bound`；**禁止**拼成 `"A\|B"` 假类名）+ §11 改动清单「`ScalaTypeMapper.java` \| `mapDealiased`…禁止假类名」 |
| A-34 | `deriving` | DROPPED-SILENT | no handling code in `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java` | §7 类型面方案「**不进运行期 IR 的类型信息**」第 ③ 条（**类型级计算**——`deriving` 产物不产生任何运行期值）+ §9 注解与元数据方案（`deriving` 属编译期约束⇒`STRUCTURE` drop） |
| Scala #B8 | `f` interpolation | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §5.2「字符串插值 `s"..."`/`f"..."`/`raw"..."`」行（同 `SeqLiteral` 那条路径）+ §11 改动清单 |
| A-35 | `to` / `until` ranges | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | 保持现状（无需改动）—— §5.2「范围 `to`/`until`」行（普通 `CALL`） |
| Scala #B5 | using clauses | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:423-438` | §4.2「多参数表/`implicit`/`using`」行 + §11 改动清单「`buildParams`…`implicit`/`using` 解析后显式化」 |
| A-36 | `@targetName` | DROPPED-SILENT | no `Annotation` call under `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/` | §9 注解与元数据方案（`@targetName` 影响调用名⇒调用点的 `calleeSig` 必须用改名后的名字）+ §4.2 声明面表的注解通道列 |
| A-37 | `inline` + `erasedValue` | DEGRADED / NOT FOUND | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` (`inline`); `erasedValue` — no handling code | §4.2「`inline`/`transparent inline`/`inline match`」行（展开=S8）+ §13 的 `KS3` |
| A-38 | `summon` | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §4.2「`given`」行（`summon[T]`→`CALL <given>`） |
| A-39 | `identity` | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | 保持现状（无需改动）—— §5.2「范围」等同族的普通 `CALL` |
| A-43 | `scala.compiletime` | NOT FOUND | no handling code between `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:955-1038` | §7 类型面方案「**不进运行期 IR 的类型信息**」第 ⑦ 条（**编译期常量**（Scala `scala.compiletime`）——值只在编译期存在，运行期没有这个值⇒该表达式记 `drop(VALUE, "compiletime.constant")`，**类型**照常进 `TacType`）+ §11 改动清单「`translateExpr`（`ST:955-1038`）」行。**审计标 NOT FOUND** ⇒ 现有实现**完全没有**这个构念 ⇒ 本方案**新增支持**（不新造 IR） |
| Scala #B1 | **receiver of a call with explicit type arguments** | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1334-1336` | §6.7 for 推导式与集合操作（Scala）**前置修复**（脱糖后的调用是 `Trees.TypeApply`，而 `receiverOf` 只认 `Trees.Select`（`ST:1334-1356`）⇒**接收者与其整棵子树从未被访问**，必须先解 `TypeApply`/`Apply` 包装）+ §11 改动清单 + §10 IR-P 细则表「接收者」行 |

### A.3 Statements

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A-01 | `val` / `var` definitions | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:852-879` | 保持现状（无需改动）—— §4.2「字段初始化器（类体里的 `val x = expr`）」行 + §11 改动清单 |
| A-02 | pattern definitions (`val (a,b) = t`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:2018-2057` | §5.2「模式定义 `val (a,b) = t`」行（`MOVE` 到 `a`/`b`（元组 `_1`/`_2` 读取））+ §6.3「元组 `case (a, b)`」行 |
| A-03 | multi-definition (`val a = 1, b = 2`) | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:852-879` | 保持现状（无需改动）—— dotc 脱糖成独立的 `ValDef` ⇒ 走 §4.2「字段初始化器」行 |
| A-04 | type ascriptions (`x: Int`) | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:998-1002` | 保持现状（无需改动）—— §7 类型面方案（Scala `Int`/`Long`/… 同 Kotlin） |
| A-05 | `while` statement | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1816-1842` | 保持现状（无需改动）—— §6.5（循环模板 + 回边） |
| A-14 | `do`-`while` statement | N/A | Scala 3.10.0 rejects `do` | 不适用（审计已判 N/A：**Scala 3.10.0 拒绝** `do` ⇒ **本语言里不存在这个构念**）—— 构造不出可分析的 TAC；本方案**不为此设计任何 TAC 形态** |
| Scala #B10 | `for` statement | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1334-1336` | §6.7 for 推导式与集合操作（Scala）+ §11 改动清单「`receiverOf`…解 `TypeApply`/`Apply` 包装」 |
| A-06 | `if` statement | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1776-1807` | 保持现状（无需改动）—— §5.2「`if` 作为表达式」行（§6.4） |
| A-07 | `match` as a statement | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1876-2000` | §6.3 `match`（Scala）表（分支链 + `TacCase`）+ §10 `IR-AA` + §12 门禁 B⑤ |
| A-08 | `return` statement | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1013-1020` | 保持现状（无需改动）—— §6.5 |
| A-15 | labelled `return` | UNCLEAR | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1013-1020` | **审计标 UNCLEAR（未判定）**；本方案的判定同**附录 B** 的 A.2（Expressions）小节「A-42 labelled `return`」行（**同一种非局部返回**）⇒ §6.5「**Scala 的带标签 `return`**」条 + §11 改动清单「`translateReturn`（`ST:1013-1020`）」行；**例外**：位于 `inline` 方法的 lambda 内时归 §13 的 `KS3` |
| A-09 | `throw` statement | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1021-1027` | §8 IR 记号 J（`THROW.ctorType`）+ §6.6 try / catch / finally |
| Scala #B6 | `try`/`catch`/`finally` statement | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §6.6 try / catch / finally + §11 改动清单「`translateExpr`…新增分派：`Trees.Try`…」 |
| A-10 | assignment | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1689-1741` | 保持现状（无需改动）—— §2 现状差距表「赋值」行（`translateAssign` 发 `MOVE`/`FIELDSTORE` ⇒ 本来就对） |
| A-11 | compound assignment | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1689-1741` | 保持现状（无需改动）—— §2 现状差距表「赋值」行 |
| Scala #B9 | local `def` | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:840-844`, `:309-324` | §4.2「局部 `def`/局部类」行（合成函数；名字取**符号全名**（与 Scala 闭包已有做法一致，`ST:2259-2276`））⇒ 合成侧与调用点用**同一**符号全名 |
| Scala #B9 | local classes / objects | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:840-844` | §4.2「局部 `def`/局部类」行（同一符号全名口径） |
| A-12 | imports (wildcard, renaming, given imports) | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacFrontend.java:587-593` | 保持现状（无需改动）—— §4.2「类 / trait」行（import 由 dotc 解析；import 无运行期语义可丢） |
| A-13 | `export` | KEPT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1296-1322` | 保持现状（无需改动）—— dotc 的转发方法照常降级为普通 `CALL`（§4.2「类 / trait」行） |

### A.4 Type-system features whose information the engine could have used

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A-01 | nullability (`Null` / `\| Null`) | DEGRADED (deliberate) | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:36-42`, `plumb-tac/src/main/java/org/plumb/tac/ir/SourceLanguage.java:187-189` | 保持现状（无需改动，**故意的**）—— §7 类型面方案（Scala 无非空类型系统 ⇒ 任何引用都保持可空；`Option` **不是**空安全机制，`STM:43-46`） |
| A-02 | path-dependent types | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「Scala 路径依赖类型 `a.B` / 单例类型 `x.type`」行（`CLASS("a.B")`；单例类型记 `STRUCTURE` drop）+ §11 改动清单「`ScalaTypeMapper.java` \| `mapDealiased`」 |
| A-03 | refinement types | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「**不进运行期 IR 的类型信息**」第 ③ 条（**类型级计算**：精化不产生任何运行期值）+ §11 改动清单「`mapDealiased`」 |
| A-04 | singleton types | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「Scala 路径依赖类型 `a.B` / 单例类型 `x.type`」行（单例类型记 `STRUCTURE` drop） |
| A-05 | union types | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「Scala union `A \| B` / 交集 `A & B`」行（`unionMembers=[A,B]`；**不得**拼成 `"A\|B"` 假类名）+ §11 改动清单「`mapDealiased`」 |
| A-06 | implicit conversions | DROPPED-NOTED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:1030-1037` | §11 改动清单「`translateExpr`…新增分派：…`Inlined`…」+ §7 类型面方案「**不进运行期 IR 的类型信息**」第 ③ 条 |
| A-07 | value classes (`extends AnyVal`) | DEGRADED | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「Kotlin/Scala 值类」行（底层类型=脱糖表示）+ §4.2「case class」行的合成成员口径 |
| Scala #B15 | laziness | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:309-324` | §4.2「`lazy val`」行（合成 `GETTER`（惰性初始化 `BRANCH`+持有者字段））+ §11 改动清单 |
| A-08 | variance | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「变型 `in`/`out`/`*`」行（进 `TacTypeDecl.typeParameters`，`N`） |
| A-09 | type bounds | DROPPED-SILENT | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTypeMapper.java:80-121` | §7 类型面方案「变型 `in`/`out`/`*`」行（类型参数与上界进 `TacTypeDecl.typeParameters`，IR-N）+ §10「`IR-N` / `IR-E` 的 Scala 修饰符细化」条 |

**附录 B 小计**：**127 条**（= 审计 A 节的**全部 127 行**，一行不缺）= 原附录 **19 条**（编号 `Scala #B1`–`#B19`）+ 本轮**新增 108 条**。其中 `KEPT` **34 条**（去向=「保持现状（无需改动）」）、`N/A`（该构念在本语言里不存在）**3 条**、问题项 **84 条**（`DEGRADED` **32** + `DROPPED-*` **52**）⇒ **每一行都有落点**（A 完整表达 **77** / B 显式记账 **5** / C 永久例外 **8**；含审计标 `UNCLEAR` 的 2 行与 `NOT FOUND` 的 6 行）。**`⚠` 未覆盖 = 0**。

---

## 附录 C. Kotlin/Scala 未覆盖清单

本附录列出附录 A / 附录 B 中带 `⚠` 的条目。**每条都去源码核实过**（本轮实测：直接读 `plumb-frontend/src/main/java/org/plumb/tac/frontend/{kotlin,scala}/` 下的源码与全仓 grep），结论如下。**本轮把附录 A / B 从「只覆盖审计 B 节的丢弃清单」扩展到「覆盖审计 A 节的每一行」之后，Kotlin 线（附录 A）也出现了未覆盖条目**（U-KT1 / U-KT2，见 C.1）。

### C.1 完全未覆盖（方案里没有任何落点）

#### U-KT1 —— 展开实参（spread argument，`f(*arr)`）（附录 A `A-20`）〔本轮扩展附录时新增〕

- **源码核实**：`KotlinTacTranslator.java:1548-1559` 的 `translateArguments` 逐个遍历 `call.getValueArguments()`，对每个实参只取 `arg.getArgumentExpression()` 并翻译成**一个** operand ⇒ `f(*arr)` 的 `*arr` 被当成**一个普通实参**（整个数组当作一个值）；同文件 `:1457` 的 `arity = call.getValueArguments().size()` 也把 spread 记成**一个**实参。本轮实测：`plumb-frontend/src/main/java/org/plumb/tac/frontend/kotlin/` 全包 grep `getSpreadElement`/`spread` **零命中** ⇒ 展开语义今天**完全不被建模**（审计 A 节「spread argument」行的结论成立）。
- **方案为什么没覆盖**：§5.1 的 Kotlin 表达式面表**没有「spread」行**；§10 的 `IR-AC`（已按 Q11 裁定并入 `06` 的 `IR-AO`）与 `IR-AO` 只讲**形参侧**的「可选 / 可变参 / 默认参数」，**不讲调用点的展开**；§11 改动清单里 `translateArguments`（`KTT:1455`/`1548`）那一行只写了「命名实参按声明序重排；默认参数填 `d$default$N`」，没有展开。
- **后果**：① 实参与形参**个数对不上**（传 1 个 vs 期望 N 个）⇒ `calleeSig` 的 arity 错 ⇒ 调用图边指向不存在的函数（与 §10 `IR-AC` 记的「arity 用源码实参个数」是**同一根源**的另一面）；② 「数组里每个元素都是污点」被压成「数组本身一个值」⇒ 数组**元素级**污点在被调方**丢失**。
- **建议**：§5.1 需要一行「**spread 实参**」：调用点把数组**逐元素展开**进 `args`（元素个数静态可知时按元素数展开；否则按 §3 P3 记 `TacDrop(VALUE,"spread.dynamic")`）+ §11 一条读点（读 `KtValueArgument.getSpreadElement()`）。⇒ **IR 侧不需要新提案**：`args` 是既有列表，变长那一端由 `IR-AO` 的 `TacParameter.vararg` 槽位承载；**动态长度**属「运行期决定」（`02` §3 根因一）⇒ 记 drop 即可，本方案**暂不**为它新增 §13 条目。
- ✅ **已补齐（2026-11-16 本轮）**：§5.1 Kotlin 表达式面表新增「**展开实参（spread，`f(*arr)`）**」行（调用点把被展开的数组**逐元素**放进 `CALL.args`；长度不可数 ⇒ `drop(VALUE, "spread.dynamic-length")` + 变长形参按 **IR-AO** 承载；变长形参**不计入** `calleeSig` 元数，IR-P）；§11 改动清单新增对应条目。附录 A 的 `A-20` 行已同步改成真实落点。

#### U-KT2 —— Kotlin **成员修饰符**（`override`/`open`/`final`/`abstract`）（附录 A `A-29`/`A-30`/`A-32`）〔本轮扩展附录时新增〕

- **源码核实**：本轮实测 `plumb-frontend/src/main/java/org/plumb/tac/frontend/kotlin/` 全包 grep `ModifierList`/`getModifier`/`isOpen`/`isOverride`/`isAbstract`/`isFinal`/`isSealed` **零命中**；唯一被读到的修饰符事实是**类级**的 `isData`（`KotlinTacFrontend.java:490`/`:494`，以及 `KotlinTacTranslator.java:1953`/`:1973` 的 `isDataClassGenerated`）⇒ **成员**修饰符今天完全不被读取（审计 A 节 `override`/`open`/`final` 三行的结论成立）。
- **方案为什么没覆盖**：§4.1 的 Kotlin 声明面表最后一行只给了「**可见性修饰符**」（`public`/`internal`/`private`/`protected`），§10 的「可见性分级」也只覆盖可见性 ⇒ `override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion` 这组**成员修饰符**没有落点。
- **后果**：`open`/`final` 不参与「是否可覆盖」的判定；`override` 不参与「是否实现父类成员」的判定 ⇒ 继承/多态类分析只能部分靠 VTA 补偿。
- **建议**：§4.1 需要一行「**成员修饰符**（`override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion`）」⇒ 进 `TacMemberRef.modifiers`（`01` §IR-E 的**补充细化**，与「可见性分级」同款，**不占新编号**；与 `03` 的 U-JS5 提出的 `TacMemberRef.modifiers` 是**同一处**细化，两份文档合并登记一次即可）。这些修饰符是**编译期事实**，应当被记录而不是被丢弃；真正无运行期对应物、又无规则可用的部分才按 §3 P3 记 `STRUCTURE` drop（口径与 `03` U-JS5 的「零指令、无 drop」对齐）。
- ✅ **已补齐（2026-11-16 本轮）**：§4.1 Kotlin 声明面表新增「**成员修饰符**（`override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion`）」行（**零指令**；事实落 `TacMemberRef.modifiers`，与「可见性修饰符」行共用同一个列表）；§11 改动清单新增「`KotlinTacTranslator.java` / `translateFunction`（`KTF:722-851`）」条目。附录 A 的 `A-29`/`A-30`/`A-32` 三行已同步改成真实落点。

#### U-SC2 —— 翻译器的**文本**诊断通道没有出口（Scala #B18）

- **源码核实**：本轮全仓 grep `problemMessages` **只有一处命中** —— `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:304` 的**声明本身**，**没有任何调用方** ⇒ 翻译器自己攒的文本问题确实到不了扫描端（结构化问题是另一条通道，能出去）。审计结论成立。
- **方案为什么没覆盖**：§3 的 P3（「确实无法静态刻画的，必须有 `TacDrop`（`B`）且精度随之下调（`A`）」）与 P4（「不留静默丢弃」）都把可追责性绑定在 `TacDrop` 上；§11 改动清单最后一行只改「精度由 `drops` 推导（`A`/`B` 统一口径），不再手工判定」，**没有安排把翻译器的文本问题接到前端/扫描端**；§12 的门禁 A–D 也只断言 TAC 形状与精度，没有一条断言「问题一定出现在某条对外通道上」。
- **后果**：Scala 侧一切「有诊断但文本通道丢」的降级在扫描报告里看起来是干净的 ⇒ §3 P4 在 Scala 上缺少兑现手段。
- **建议**：§11 改动清单补一行：把翻译器的文本问题并入与结构化问题同一条出口（`ProblemCollector` / 前端 `FunctionProblem`）；§12 门禁 D 加一条断言「任一降级问题至少出现在一条对外通道上」。

#### U-SC3 —— javadoc 与代码冲突（Scala #B19）

- **源码核实**：审计点名的三处与代码不符 —— `ScalaTacTranslator.java:45-71` 的类 javadoc 仍把闭包列为「未降级」（闭包已在 `:2196-2241` 降级）；`:415-422` 的 javadoc 说后续参数表被降成局部量（代码 `:423-438` 只取第一张表）；`:61-64` 声称会发 `THROW`/`NOP`（`THROW` 从未发出）。审计结论成立。
- **方案为什么没覆盖**：本文是**降级方案**，不安排 javadoc 同步；§14 风险与未决项里也没有「文档同步」条目。
- **后果**：文档与代码不一致 ⇒ 后续实现者按 javadoc 写会写错。
- **建议**：§14 加一条未决项「Scala/Java 前端的 javadoc 与代码不一致（审计 Scala #B19 及 Java 表达式审计的同类项）：由谁在哪个提交里同步」；本方案本身不承载。

#### U-SC4 —— Scala 的**运算符面**（`&&`/`||` 的短路语义 + `|` 的双义）（附录 B `A-06`）〔本轮扩展附录时新增〕

- **源码核实**：`ScalaTacTranslator.java:1567-1622` 的 `binaryOperator` 把 `&&`→`BinaryOperator.LAND`（`:1616`）、`||`→`BinaryOperator.LOR`（`:1618`）、`|`→`BinaryOperator.OR`（`:1587-1589`）；本轮实测全文件 grep `LAND|LOR` **只有 `:1616`/`:1618` 这两处** ⇒ `&&`/`||` 走的是**单条 BINOP**（**非短路**）路径；全文件 grep `Alternative` **零命中** ⇒ 模式备选（`case A | B`）也没有单独的建模路径。
- **已覆盖的部分**：§6.3 的模式表**已有**「备选 `case 1 | 2` ⇒ 一个 `Membership`（多 guard，语义 OR）」这一行 ⇒ 模式备选的**目标形态**本身有落点。
- **残留**：① §5.2 的 Scala 表达式面表**没有「运算符面」行** ⇒ `&&`/`||` 的**短路**语义（右侧只在左侧成立时求值）与位运算 `|` 都**没有目标形态**；② 「同一个 `|` 在两个语境里分属两种语义」这条**消歧规则**没有写出来 ⇒ 位或（`BinaryOperator.OR`）与模式备选（`Membership`）**必须走不同路径**，不得共用同一个 opcode / 同一个翻译入口。⇒ 本条按「**运算符面整体**没有落点」记为完全未覆盖。
- **后果**：① `a && b` 被当成两个**都会执行**的操作 ⇒ `b` 的副作用与 `b` 里的空指针风险在 `a` 为假时**仍然可达** ⇒ 误报源；② 消歧规则缺位 ⇒ 实现者容易把 `case A | B` 落成位或求值 ⇒ **两个模式都变成立即求值** ⇒ 语义错。
- **建议**：§5.2 需要一行「**运算符面**」——`&&`/`||` ⇒ 与 §6.4（`if` 表达式）同款的**短路分支链**（IR-C `TacGuard`：`BRANCH` 带守卫 + 两臂，右侧只在守卫成立的那一臂里求值）；位运算 `|` ⇒ `BinaryOperator.OR`；模式备选 `|` ⇒ §6.3 已有的 `Membership`（IR-C 的 `TacCase.guards`），**二者不得共用一个 opcode**；§6.3 的模式表再补一句「备选**不是**位或」。⇒ **IR 侧不需要新提案**：IR-C 的 `TacGuard`/`TacCase.guards` 既有；模式种类里的 `ALTERNATIVE` 已在 §10 `IR-AA` 的 `PatternKind` 枚举中（`TYPE/CONSTANT/BINDING/EXTRACTOR/TUPLE/ALTERNATIVE/SEQUENCE/WILDCARD`）⇒ **不占新编号**。
- ✅ **已补齐（2026-11-16 本轮）**：§5.2 Scala 表达式面表新增「**运算符面**」行（五条子规则：短路分支链 / `|` 的语境分流 / 表外名字逐个给形态 / 类型层构念零指令 / 用户类型运算符改 `CALL`）；§11 改动清单新增「`ScalaTacTranslator.java` / `binaryOperator`（`ST:1567-1622`）」条目。附录 B 的 `A-06` 行已同步改成真实落点。

### C.2 部分覆盖（方案有落点，但该构念的一部分仍没有落点）

#### U-SC1 —— Scala 的 `final`/`sealed`/`abstract` 修饰符（Scala #B17）

- **源码核实**：`plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java:332-413` 只读「synthetic」一种修饰符 ⇒ `final`/`sealed`/`abstract` 今天确实不被读取。审计结论成立。
- **已覆盖的部分**：§6.3（以及 §6.1）用 sealed 层次 + `permittedSubtypes`（`N`）判定 `exhaustive` ⇒ **sealed 层次**这一半有落点；§4.1 的 Kotlin 行也给了 `permittedSubtypes` 的位置。
- **残留**：**修饰符本身**（`final`/`sealed`/`abstract`/`case`）没有落点 —— §4.2 Scala 3 声明面表没有修饰符行；§10 的四条新能力（`IR-AA`/`IR-AB`/`IR-AC`/`IR-AD`）里也没有修饰符槽位（可见性分级那条只改 `TacMemberRef.isPublic` ⇒ `TacVisibility`）。
- **后果**：`sealed`/`abstract` 修饰符不参与「成员是否可实例化 / 层次是否封闭」的判定；`final` 不参与「是否可覆盖」的判定（VTA 只能部分补偿）。
- **建议**：§4.2 补一行「类/trait 修饰符（`final`/`sealed`/`abstract`/`case`）」：进 `TacTypeDecl`（IR-N）；`sealed` ⇒ `permittedSubtypes` 非空 ⇒ 直接供 §6.3 的 `exhaustive` 判定；不可静态判定的部分按 §3 P3 记 `STRUCTURE` drop。

### C.3 未覆盖清单小结

| 编号 | 归属台账 | 构念 | 状态 | 需要补的位置 |
|---|---|---|---|---|
| U-KT1 | 附录 A `A-20`（Kotlin A 节「spread argument」） | 展开实参（`f(*arr)`） | **✅ 已补齐** | §5.1「**展开实参（spread，`f(*arr)`）**」行 + §11 |
| U-KT2 | 附录 A `A-29`/`A-30`/`A-32`（`override`/`open`/`final`） | Kotlin **成员修饰符** | **✅ 已补齐** | §4.1「**成员修饰符**」行 ⇒ `TacMemberRef.modifiers`（IR-E 细化，不占新编号）+ §11 |
| U-SC2 | Scala #B18 | 翻译器的**文本**诊断通道没有出口 | 完全未覆盖 | §11 改动清单 + §12 门禁 D |
| U-SC3 | Scala #B19 | javadoc 与代码冲突 | 完全未覆盖（且**不属本方案范围**） | §14 未决项（文档同步） |
| U-SC4 | 附录 B `A-06`（Scala A 节「运算符面」） | `&&`/`\|\|` 的**短路**语义 + `\|` 的**双义** | **✅ 已补齐** | §5.2「**运算符面**」行 + §11 |
| U-SC1 | Scala #B17 | `final`/`sealed`/`abstract` 修饰符 | 部分覆盖 | §4.2 声明面表 + §10（IR-N） |

**合计**：**6 条**（5 条完全未覆盖 + 1 条部分覆盖）—— **其中 3 条已在本轮补齐（U-KT1 / U-KT2 / U-SC4）**，剩余 3 条待实施，涉及 **3 条**台账行（附录 A 的 `A-20`/`A-29`/`A-30`/`A-32` 四行 + 附录 B 的 `A-06` 一行 + 原来的 Scala #B17/#B18/#B19 三行）。其余 **46 条**（Kotlin 30 + Scala 16）台账行都有可核对的落点。

> ⚠ **本轮扩展附录后的口径**：附录 A/B 已从「只覆盖审计 B 节的丢弃清单」扩展为「覆盖审计 **A 节**的**每一行**」（Kotlin 124 条 + Scala 118 条 = 242 行）。这 242 行里 **KEPT** 51 条（17 + 34）、问题项 **191 条**（107 + 84）—— 逐条都有去向；其中 **5 行**落点缺失，已分别记为本附录的 **U-KT1 / U-KT2 / U-SC4**（见 C.1）—— **这 5 行已在本轮全部补齐** ⇒ 「每个问题项都有去向」这个断言**在附录 A/B 的口径下成立**，附录 A/B 的 `⚠` 计数**已归零**。

> **口径声明**：本附录只做「审计台账 ⇒ 设计文档落点」的映射与核实，**未运行任何构建或测试**；全部结论来自本轮对 `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/` 的源码阅读与全仓 grep 实测。仓库文件**未做任何修改**。

---

### C.4 未覆盖条目的补齐落点（本轮新增）

本节是 C.1–C.3 的**落地记录**：上表中**已并入正文**的条目都已按「**现状证据 → 完整 TAC 形态 → IR 需求 → 改动落点**」四件套**并入正文的对应小节**（不是只在附录里加一段说明）。C.1–C.3 保留为**审计当时**的原始结论与源码核实记录，**未做改动**；本节只回答「现在在哪里」。

> ✅ **U-KT1 / U-KT2 / U-SC4 已在本轮并入正文**（2026-11-16）：它们是在把附录 A/B 从「只覆盖审计 B 节的丢弃清单」扩展到「覆盖审计 A 节的每一行」时才发现的，当时没有现成落点；本轮已补上 §5.1 的「**展开实参（spread，`f(*arr)`）**」行、§4.1 的「**成员修饰符**」行、§5.2 的「**运算符面**」行，以及 §11 的三条对应改动项 ⇒ 下表这三行不再是「⚠ 待补」，附录 A/B 的 `⚠` 归零。

| 编号 | 正文落点（章节 + 表行 / 小节） | IR 需求 | §11 改动条目 | 需要新 IR 提案？ |
|---|---|---|---|---|
| U-SC2 | **§11 改动清单**（新增「`ScalaTacTranslator.java` / `ScalaTacPhase.java`」行）+ **§12 门禁 D4** | `B`（`TacDrop` 口径不变；本条是**出口**问题，不是新能力） | §11 新增行（文本腿接 `ParseResult.problems()`） | **否** |
| U-SC3 | **§14 风险与未决项**第 9 条 | —（**不属本方案范围**：文档同步，不是 TAC 表达力） | — | **否** |
| U-SC1 | **§4.2 声明面表**（新增「类/trait 修饰符」行）+ **§10**（新增「`IR-N` / `IR-E` 的 Scala 修饰符细化」条） | `N`（`permittedSubtypes` **既有** + `isSealed` 补充细化）+ `E`（`isAbstract` **既有** + `isFinal` 补充细化）⇒ **不占新编号** | §11 新增行（`ScalaTacTranslator.java` 读 `Flags.*`） | **否**（两个**既有**提案的字段级细化） |
| U-KT1 | **§5.1「**展开实参（spread，`f(*arr)`）**」**（**新增行**） | `args` 列表（**既有**）+ `IR-AO` 的 `TacParameter.vararg` 槽位 ⇒ **不占新编号** | **§11 新增行**（`translateArguments`，`KTT:1548-1559`） | **否** |
| U-KT2 | **§4.1「**成员修饰符**（`override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion`）」**（**新增行**） | `E`（`TacMemberRef.modifiers` **补充细化**，与「可见性分级」同款）⇒ **不占新编号** | **§11 新增行**（`translateFunction`，`KTF:722-851`） | **否** |
| U-SC4 | **§5.2「**运算符面**」**（**新增行**）+ **§6.3** 模式表「备选**不是**位或」 | `C`（`TacGuard`/`TacCase.guards` **既有**）+ `IR-AA` 的 `PatternKind.ALTERNATIVE`（**既有枚举值**）⇒ **不占新编号** | **§11 新增行**（`binaryOperator`，`ST:1567-1622`） | **否** |

**需要登记的字段级细化**（**不占新编号**，但要在 `01` 的 §IR-N 与 §IR-E 段落各补一句说明）：① `TacTypeDecl.isSealed : boolean`（IR-N 的细化）；② `TacTypeDecl.isFinal : boolean`（IR-E 的细化）；③ `TacMemberRef.modifiers : List<String>`（IR-E 的细化，**U-KT2 所需**）—— ③ 与 `03` 的 U-JS5 提出的**同一处**细化，两份文档**合并登记一次**即可。**本方案未自行分配任何新编号，也未改动 `01` 的 §10.2 登记表。**

**三条待补条目的处置（已完成）**：U-KT1 已在 §5.1 表达式面表补「**展开实参（spread，`f(*arr)`）**」行 + §11 一条读点；U-KT2 已在 §4.1 声明面表补「**成员修饰符**」行 + §11 一条（读 `KtDeclaration` 的修饰符）；U-SC4 已在 §5.2 表达式面表补「**运算符面**」行 + §11 一条（`binaryOperator` 拆出短路路径与语境分流）。三条都**不占新 IR 编号**（承载能力均已存在：IR-AO 的 `vararg` 槽位、IR-E 的修饰符槽位、IR-C 的守卫与 IR-AA 的 `ALTERNATIVE`）⇒ 附录 A/B 的 `⚠` 已归零，「每个问题项都有去向」在这 5 行上不再落空。三条都**不占新 IR 编号**（承载能力均已存在：IR-AO 的 `vararg` 槽位、IR-E 的修饰符槽位、IR-C 的守卫与 IR-AA 的 `ALTERNATIVE`）；但在正文补齐之前，「每个问题项都有去向」在这三点上**仍然落空** ⇒ 实施时必须同批补上。

**口径校正一处**（与 C.1 的原文并列，不改原文）：**U-SC3** 的第 ③ 条 —— 原文写「`:61-64` 声称会发 `THROW`/`NOP`（`THROW` 从未发出）」；本轮实测 **`THROW` 确实发出**（`ScalaTacTranslator.java:1021-1026`），**从未发出的是 `NOP`**（全文件无 `Kind.NOP`）⇒ 准确表述见 §14 第 9 条。**另两处（闭包已在 `:2196` 降级、后续参数表在 `:423-438` 只取第一张表）原文成立。**

**关于 U-SC2 的「后果」口径校正**：附录 C.1 说「Scala 侧一切『有诊断但文本通道丢』的降级在扫描报告里看起来是干净的」——本轮实测 `ProblemCollector.add`（`ST:2153-2158`）**同时**写 `messages` 与 `structured` 两条腿 ⇒ **结构化那条腿带着同样的文本出去**（`structuredProblems()` `:297` → `ScalaTacPhase.java:161` → `ScalaTacFrontend.java:234`）⇒ **信息没有丢**，丢的是**文本形态的可见性**（日志里看不到）。⇒ 本条的准确定性是**可观测性缺陷**（通道半死），不是**数据丢失**；修法与门禁 D4 已按此口径写。
