# Java 源到 TAC 的降级方案（完整覆盖口径）

> **立场**：TAC 应当支持 Java 的**全部**语言特性。每个构念都必须给出**完整的 TAC 表达**（用既有指令 + CFG 真正降级出来），而不是降级成 `UNKNOWN`/`HAVOC` 再记一条 drop。只有当某构念的语义**本质上无法静态判定**时，才允许保守建模，并写清「为什么无法静态表达 / 保守建模形状 / 对精度的影响」。
>
> **基线**：仓库 `D:\work\workspace\idea\engine\Plumb`，HEAD = `2c15f0e`；JavaParser = `javaparser-core-3.26.4`。IR 能力见 `01-TAC-IR-扩展设计.md`（IR-A..IR-H）；覆盖矩阵与全局判定见 `02-覆盖矩阵与不可支持项.md`（IR-I..IR-Q，判定标准见其 §0）。本文只写 **Java 前端**的降级方案，Java 侧对 IR 的需求集中在 §8。
>
> **文法上限 = Java 21（U-JE6）**：本文「全部语言特性」的口径是 **Java 8..21**，**不是 Java 25** —— 解析器的文法上限由 `plumb-parser/src/main/java/org/plumb/parser/JavaLanguageLevel.java` 钉死：`:44-46`（javadoc：「Java 22..25 map to `JAVA_21`」）、`:126`（`latest`/`current`/`bleeding-edge` → `JAVA_21`）、`:156-163`（`"22"`/`"23"`/`"24"`/`"25"` → `JAVA_21`）。原因记在同文件 `:48-52`：JavaParser 3.26.4 的 `BLEEDING_EDGE` 与 `JAVA_21` **是同一个枚举常量** ⇒ 映射到 `BLEEDING_EDGE` 只会**承诺一个并不存在的文法**（`:54-60` 的 javadoc 把这叫「a silent lie」）。
> ⇒ **口径校正**：审计「Java 22–25 的文件整体被丢弃」偏宽 —— 只有**真正用到 Java 22+ 语法**的文件才会在**解析阶段**失败并报 `PARSE_PROBLEM`；标了 `Java 22` 但语法仍在 21 以内的文件**照常解析**（`:62-66` 的 javadoc 说明「不静默降级」的理由）。
> ⇒ `plumb-tac/src/main/java/org/plumb/tac/translate/JavaModernFeatures.java:42` 的 javadoc 仍写「Java 8 through Java 25」，与解析器的实际上限**不一致**（属**文档与实现不一致**，不是 TAC 表达力缺口）⇒ §11 K11 的缓解项。真正的 Java 22+ 支持 = **JavaParser 依赖升级**（不是前端工作量）⇒ §11 Q6。
>
> **硬约束**：不新增语言专用 opcode（`plumb-tac/src/main/java/org/plumb/tac/frontend/TacFrontend.java:53-57`）；新字段只能追加在末尾（`plumb-tac/src/main/java/org/plumb/tac/ir/TacType.java:26-29`、`TacInstruction.java:32-35`、`TacFunction.java:29-32`）；新信息只能压制误报、不能证明安全（`plumb-tac/src/main/java/org/plumb/tac/ir/IrPrecision.java:42-56`）。

---

## 1. 目标与非目标

| 目标 | 落点 |
|---|---|
| G1 每个声明/表达式/控制流构念都有**确定的 TAC 形态**（可断言、可回归） | §3/§4/§5 |
| G2 修掉**建错控制流**（错答案）：`&&`/`\|\|` 不短路、`finally` 被跳过、`switch` 多标签只测第一个、`default` 位置错、switch 表达式每个 arm 都执行 | §5 |
| G3 修掉**静默丢失**：成员 dispatch 无兜底、`writeLhs`/`translateLhsRead` 兜底缺失、`translateLabeled` 丢标签名、一元 `+` 未映射、静态字段初始化无 `FIELDSTORE` | §3/§4 |
| G4 精度**由 drop 推导**（现在 `setIrPrecision` 在 `plumb-tac` 内**从未被调用**，只有 Kotlin/Scala 前端调用：`plumb-frontend/src/main/java/org/plumb/tac/frontend/kotlin/KotlinTacTranslator.java:236`、`.../scala/ScalaTacTranslator.java:358`） | §7 |
| G5 把 Java 的路径条件交给 Z3（靠 IR-C 的 `TacGuard`） | §5/§8 |

| 非目标 | 理由 |
|---|---|
| N1 不新增任何 TAC opcode | `TacFrontend.java:53-57` 明文禁止；Java 所需形状全部可用既有指令 + 元数据表达（§8） |
| N2 不改引擎侧 checker 的判定逻辑 | 只改前端与 IR 元数据 |
| N3 不做编译期求值 | 编译期行为无运行时对应物（`02` §3.4） |
| N4 不实现其它语言的 `inline` 展开 | `02` §5 的 S8 阶段 |
| N5 不解决「扫描范围外的类型信息不全」 | **工具边界**非语言边界 → §11 Q2 |

---

## 2. 完整覆盖原则与允许的例外

**P1 每个构念必须有完整 TAC 表达。** 判定沿用 `02` §0（可表达/可判定/不撒谎/可追责）。**前三条是硬要求**，不得以 `UNKNOWN`/`HAVOC` 充数；唯一允许的保守建模是 §12 的六类。

**P2 匹配到了就必须降级出来，声明面永远有兜底分支。** 成员分派链（`plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java:652-760`）**无最终 `else`**：覆盖 `MethodDeclaration`/`ConstructorDeclaration`/`CompactConstructorDeclaration`/`InitializerDeclaration`/`ClassOrInterfaceDeclaration`/`EnumDeclaration`/`AnnotationDeclaration`/`RecordDeclaration`，**其余成员种类被静默丢弃**。改法：链尾 `else` → `unsupportedMember(...)`，**必须**记一条 drop **且**在 `TacTypeDecl.members` 留一条 `TacMemberRef`。

**P3 控制流必须按语言语义建模。** 短路、finally、fall-through、`default` 位置、带标签跳转，五项逐一建模（§5）。判定标准：「CFG 上可达的路径集合 == 源码语义下的路径集合」。

**P4 精度由 drop 推导，不由前端自报。** 统一规则（`01` §IR-B）：有 `SEMANTICS` 级 drop ⇒ `SOURCE_DEGRADED`；有 `STRUCTURE` 级 ⇒ `SOURCE_LOWERED`；只有 `VALUE` 级 ⇒ 仍 `SOURCE_FULL`。Java 前端**不再调用** `TacBuilder.setIrPrecision`（`plumb-tac/src/main/java/org/plumb/tac/translate/TacBuilder.java:186-188`），统一由 `TacFunction.derivePrecision(drops)` 计算。

**例外边界**：必须是「语义事实不在源码里」，不是「前端没写」（前端没写 = 工作量 → §9）；必须落在 `02` §3.1–§3.4 的四类根因内；**不得用 drop 掩盖表达力缺口**。

---

## 3. 声明面方案

`memberKind` 取 `01` §IR-E 的枚举值；`ownerType` 指 IR-E 给 `TacInstruction` 追加的字段。

| 成员种类 | 现状证据 | 目标 TAC 形态 | `memberKind` | 注解通道 | IR |
|---|---|---|---|---|---|
| 实例字段（带初始化器） | `TacTranslator.java:774-830` `emitInstanceFieldInit`：有 `FIELDSTORE`、receiver=`this`、**无 `ownerType`**；**且整段被 `hasExplicitCtor` 门控**（`:539-547`：类里一旦声明任何构造器，**全部**实例字段初始化器都不发 ⇒ U-JS1） | `MOVE t=v` + `FIELDSTORE this.f=t`，带 `ownerType`；**在每个构造器体之前发出**（顺序 = `super()` 之后、构造器体之前），**不再依赖「类里有没有显式构造器」** | `FIELD_INITIALIZER` | `fieldAnnotations` 已填（`:583-588`） | IR-E |
| 实例字段（无初始化器） | 无产物 | **无指令**（零值非运行时事实）；声明进 `TacTypeDecl.fields` | — | 同上 | IR-E |
| 静态字段（带初始化器） | `TacTranslator.java:832-838` `emitFieldInit` ⇒ **只有 `MOVE`，没有 `FIELDSTORE`，字段永远没被写**；落点函数是 `<clinit>`（`:504-511` 建函数），**与静态初始化块同名** ⇒ U-JS2 | `MOVE t=v` + `FIELDSTORE <类标识>.f=t`（receiver 为类类型操作数），带 `ownerType`；落点函数 = **`<clinit>-fields`**（静态字段初始化器专用名，与静态初始化块 `<clinit>-block` 区分） | `STATIC_INITIALIZER` | 已填（`:515-520`） | IR-E |
| 静态字段（无初始化器） | 无 | 同「实例字段（无初始化器）」 | — | 已填 | IR-E |
| 方法 | `TacTranslator.java:901-1015` `translateCallable` | 一个 `TacFunction`；`this` 为带声明类型的局部量（`:1010`） | `METHOD` | `methodAnnotations`/`paramAnnotations`/`annotationValues` **已存在且 Java 已填**（`:941-971`） | IR-E |
| 构造器 | 同上（`:666-677`） | 一个 `TacFunction`；`this` 为局部量 | `CONSTRUCTOR` | 类级 | IR-E |
| 紧凑构造器 | `TacTranslator.java:1151-1196`：已发每组件 `FIELDSTORE`（`:1186-1193`） | 同构造器，签名取记录组件列表 | `CONSTRUCTOR`(+canonical) | 类级 | IR-E |
| 实例初始化块 | `TacTranslator.java:710-719`：发 `<init>`，与真实构造器**同名冲突** | 名字须区分（如 `Outer.<init-instance>/0`） | `FIELD_INITIALIZER` | 类级 | IR-E |
| 静态初始化块 | 同上（`TacTranslator.java:711`：`id.isStatic() ? "<clinit>" : "<init>"` ⇒ 与静态字段初始化器**同名**；匿名类侧同款，`:1075`） | **`<clinit>-block`**（与静态字段初始化器 `<clinit>-fields` 区分名 ⇒ U-JS2） | `STATIC_INITIALIZER` | 类级 | IR-E |
| 嵌套类型 | `TacTranslator.java:720-726` 递归 `translateTypeWithPrefix` | 独立函数集 + `enclosingClass` 链；外层类注解**未实现**（`:447-454` 只有注释） | 成员各自 kind | 应继承外层注解 | IR-E |
| 局部类型 | `TacTranslator.java:1124-1133` | 同上，名字 `Outer$method$Local` | 成员各自 kind | — | IR-E |
| 匿名类 | `TacTranslator.java:1057-1109`：**字段写入没有 receiver**（`:1099-1104` 未设 `lhs1`） | `FIELDSTORE this.f=t`（`this` 带 `ownerType`） | 成员各自 kind | — | IR-E |
| 枚举常量 | `TacTranslator.java:1491-1506`：函数体只有 `NOP`（`:1504`）⇒ **常量没有值**；**从不读 `ec.getArguments()`** ⇒ `PLUS("+")` 的构造器实参不进 TAC（U-JS4） | 函数体 = `NEW <枚举类型> args=[<构造器实参>]`（常量即实例；实参逐个翻译后进 `NEW.args`，**无实参时不带 `args`**） | `FIELD_INITIALIZER` | 类级 | IR-E |
| 枚举 `values()` | `TacTranslator.java:1512-1534`：`NEWARRAY` **无长度** ⇒ 空数组 | `NEWARRAY E[] n` + n 条 `ARRAYSTORE`（值 = 对常量函数的 `CALL`）+ `RETURN` | `METHOD`(synthetic) | — | 现有 IR 即可 |
| 枚举 `valueOf(String)` | `TacTranslator.java:1540-1552`：函数体只有 `NOP` | 比较链 `t=(name=="RED")`→`BRANCH`→命中则 `CALL` 常量函数并 `RETURN`；全不命中 `THROW java.lang.IllegalArgumentException(name)` | `METHOD`(synthetic) | — | 现有 IR 即可 |
| 记录规范构造器 | `TacTranslator.java:1621-1660`：已合成，组件 `FIELDSTORE` 到 `this`（`:1650-1657`） | 保留 + `ownerType` | `CONSTRUCTOR`(synthetic) | 类级 | IR-E |
| 记录访问器 | `TacTranslator.java:1663-1701`：`FIELDLOAD`+`RETURN`（`:1692-1699`） | 保留 | `METHOD`(synthetic) | 类级 | IR-E |
| 记录的 `equals`/`hashCode`/`toString` | `TacTranslator.java:612-613` 明确「未建模」 | `toString`：按 JLS 8.10.3 拼 `"Point[x=1, y=2]"`（`BINOP ADD` 链 + 组件访问器 `CALL`）；`hashCode`：`31*h1+h2`（`BINOP MUL/ADD` + 组件 `hashCode/0` 的 `CALL`）；`equals`：逐组件 `CALL java.util.Objects.equals/2` + 同类判定 `BINOP EQ` | `METHOD`(synthetic) | — | 现有 IR 即可（数组组件见 §12 的 `E6`） （`B` 档：记 `drop(VALUE, "record.toString.arrayComponent")`） |
| 注解类型成员 | `TacTranslator.java:1213-1257`：有默认值则 `MOVE`+`RETURN`（`:1235-1250`），无默认值则 `NOP`（`:1254`）⇒ **成员没有返回值** | 无默认值时 `RETURN` 类型零值 | `METHOD` | 拥有者注解 | IR-AM（§8-16） |
| 接口 default/static 方法 | 走普通方法路径（`TacTranslator.java:652-665`），**无专门分支** | 同普通方法；可覆盖语义靠 `TacTypeDecl` 继承图 | `METHOD` | 方法级 | IR-E + IR-N |
| `sealed`/`permits` | **完全未处理**（`:337-764` 无任何 `permits` 读取） | 运行时无效果 ⇒ 只需元数据：`permittedSubtypes` + `isSealed` | — | 类级 | IR-N |
| 其它成员种类 | dispatch 链无兜底（`:652-760`） | 兜底：记 `TacDrop` + `TacTypeDecl.members` 留一条 `TacMemberRef` | 新增枚举常量 | — | IR-E |
| **`throws` 子句**（受检异常声明） | **全仓零命中**：搜 `getThrownExceptions` / `thrownExceptions` / `throwsTypes` **零结果** ⇒ `throws` 子句**从未被读取**，方法声明里「可能抛什么」**完全消失** | 运行期无行为（不影响指令流）⇒ 零指令；但**声明必须留下**：`TacFunction.throwsTypes` = 受检异常 FQN 列表 ⇒ catch 匹配可判断「这个 catch 是否可能捕获受检异常」 | — | 方法级 | **IR-AL**（§8-18） |
| 局部变量声明上的注解 | `TacVar` 只有 `name`/`type`/`isTemp` 三个字段（`TacOperand.java:40-42`）⇒ **无注解槽位** ⇒ `@NonNull String s = …;` 的注解**静默消失** | 运行期多无效果 ⇒ 零指令；注解进 `TacVar.annotations` ⇒ 空值分析可据此收紧 `nullable`（配合 `IrPrecision` 的方向性规则：**只能压制误报，不能证明安全**） | — | 局部 | **IR-AL**（§8-18） |
| **字段修饰符（`static`/`final`/`volatile`/`transient`）与 `final` 局部量** | `plumb-tac/src/main/java/org/plumb/tac/translate/` 全包 grep `isFinal`/`isVolatile`/`isTransient` **零命中**（本轮实测）⇒ 修饰符**完全不被读取**；`TacOperand.TacVar` 只有 `name`/`type`/`isTemp`（`TacOperand.java:40-42`）⇒ `final` 局部量也无处可放（U-JS5） | **零指令**（修饰符不改变指令流）⇒ 不产生 drop，**事实落三处**：① `final` + 常量初值 ⇒ 参与**编译期常量折叠**：初值在**声明处**求值成 `Const` 并登记进**按 FQN 索引的常量表**（**前端内部**表，扩展既有 `staticFieldsByClass`，`TacTranslator.java:187-201`）⇒ 读取点直接发 `Const`、不发 `FIELDLOAD` —— 这是 §4「`static final` 常量折叠在声明处被绕过」行的**前提**（没有 `final` 就判不出「编译期常量」）；② `final`/`volatile`/`transient` ⇒ 进 `TacMemberRef.modifiers`（**IR-E 的补充细化**，不占新编号）；③ `final` 局部量 ⇒ 进 `TacVar.isFinal`（**IR-AL 的补充细化**，不占新编号）。`static` 已有承载（IR-E 的 `ownerType` + `TacMemberRef.isStatic`） | — | 类级/字段级/局部 | **IR-E**（细化）+ **IR-AL**（细化）+ IR-D |
| **方法修饰符（`strictfp`/`native`/`abstract`/`synchronized`/`default`）** | 全包 grep `isStrictfp`/`isNative`/`isAbstract`/`isSynchronized`/`isDefault` 在 `plumb-tac/src/main/java/org/plumb/tac/` 下**零命中**（本轮实测；唯一的 `isAbstract()` 命中在 `BytecodeAnalyzer.java:256`，那是字节码分析器、**不是** Java TAC 前端）⇒ 方法修饰符**完全不被读取**（审计 `05-java-statement.md:99`：「method: `strictfp` \| DROPPED-SILENT \| 无 IR 通道」） | **零指令**（修饰符不改变指令流）⇒ 不产生 drop；事实落 `TacMemberRef.modifiers`（**IR-E 的补充细化**，与字段修饰符共用同一个通用列表，**不占新编号**）：`strictfp`（x86-64 上可忽略 ⇒ 记录但不影响语义）；`native`（**方法体由外部实现** ⇒ 该方法**不产出 `TacFunction`**，调用点按**外部函数**处理，记 `drop(SEMANTICS, "native.method")`）；`abstract`（**方法体不存在** ⇒ 零指令，声明进 `TacTypeDecl.members`，`abstract` 记进 `modifiers`）；`synchronized`（**运行期有锁** ⇒ 方法体等价于 `synchronized(this){…}` ⇒ 锁语义按 §5.8 `synchronized` 的形状表达）；`default`（接口默认方法 ⇒ 与普通方法同路径，见「接口 default/static 方法」行） | `METHOD` | 方法级 | **IR-E**（细化 `TacMemberRef.modifiers`） |

**`TacTypeDecl` 的 Java 来源**：`fqn` ← `qualifiedClassName`（`TacTranslator.java:327-335`，已有私有方法未外露）；`superType` ← `cid.getExtendedTypes()`（`:422-427`）、`interfaces` ← `cid.getImplementedTypes()`（`:440-445`）—— 二者现只存**短名**，改存 **FQN**；`annotations` ← `extractAnnotationNames`（`:347`，已有）；`fields`/`fieldAnnotations` ← `fd.getVariables()`（`:476-483` 现只登记注解）；`members` ← dispatch 链（`:652-760`，现无）；`isEnum`/`isRecord`/`isInterface`/`isAbstract` ← AST 节点类型 + 修饰符（现无）。
`classToParent`（`plumb-tac/src/main/java/org/plumb/tac/ir/TacProgram.java:128`）今天只存短名且**无人读取**（其 javadoc 自述 "Currently unread by the analysis"，`TacProgram.java:122-126`）⇒ 改存 FQN，以 `TacTypeDecl.superType` 为权威。

---

## 4. 表达式面方案

| 构念 | 现状证据 | 完整 TAC 形态 | 类型 | IR |
|---|---|---|---|---|
| `int`/`long` 字面量 | `ExpressionTranslator.java:151-168` | `Const.ofInt`/`ofLong` | `INT`/`LONG` | IR-D |
| **字面量按声明上下文的窄化**（`byte b = 1` / `short s = 2` / `char c = 65`） | `ExpressionTranslator.java:151-157` 把整数字面量**一律**降成 `Const.ofInt(v)` + `TacType.INT`（不区分 `byte`/`short`/`char` 的声明上下文）；`StatementTranslator.java:275` 在变量声明处**已经拿到声明类型**（`TypeMapper.map(declarator.getType())`）但**没有把它传给字面量求值** ⇒ `byte b = 1` 在 TAC 里是一个 `INT` 变量、值域比实际宽 31 位（⇒ 溢出/截断类规则按错误宽度判定） | 声明上下文可用时（局部量/形参声明为 `byte`/`short`/`char`）⇒ 常量的**类型**收窄为 `BYTE`/`SHORT`/`CHAR`、**值域**相应收窄（IR-D 的 `domain` 记 `Integral(8/16, signed)`；`char` 是**无符号** 16 位 ⇒ `Integral(16, unsigned)`）；**越界的编译期常量**（`byte b = 128`）在 Java 里是**编译错误** ⇒ 前端在翻译期报编译错误、**不发指令**（编译期行为不是运行时行为，不进 TAC） | `BYTE`/`SHORT`/`CHAR` | IR-D |
| `char`/`float`/`double` | `:169-181` | `Const.ofChar`/`ofFloat`/`ofDouble` | 对应 | IR-D |
| `boolean`/`String`/文本块 | `:149-150`、`:182-184`、`:185-194` | `Const.ofBool`/`ofString` | 对应 | 现有 IR 即可 |
| `null` | `:212-214` | `Const.ofNull` | `NULL`（**值级**，`TacType.java:119-121`） | 现有 IR 即可 |
| 类字面量 `Foo.class` | `:195-208` | `Const.ofString("Foo")` | `CLASS` | 现有 IR 即可 |
| 二元算术/位/比较 | `:322-336` + `mapBinOp` `TacTranslator.java:1819-1841` | `BINOP` | 两操作数提升类型（今天恒 `UNKNOWN`，`:325`） | 现有 IR 即可 + IR-D |
| **`&&`/`\|\|`** | `:322-336` 两侧**无条件求值**后发一条 `BINOP`；`mapBinOp` 把 `AND`/`OR` 映到 `LAND`/`LOR`（`TacTranslator.java:1838-1839`）⇒ **不短路** | **分支链**（§5.1） | `BOOLEAN` | 现有 IR 即可 + IR-C |
| 一元 `-`/`!`/`~` | `:338-356` + `mapUnary` `TacTranslator.java:1876-1883` | `UNARYOP NEG/NOT/BITNOT` | 操作数类型 | 现有 IR 即可 |
| **一元 `+`** | `mapUnary` **无 `PLUS` 分支**（`TacTranslator.java:1876-1883`），而 JavaParser 的 `UnaryExpr$Operator` **有 `PLUS`**（`javaparser-core-3.26.4`）⇒ `+x` 落到 `ExpressionTranslator.java:357-359`，返回**空常量**且**不报任何问题** ⇒ 静默丢失 | `MOVE t=x`（恒等，无溢出无副作用） | 操作数类型 | 现有 IR 即可 |
| `++`/`--` | `:338-356`：只发一条 `UNARYOP`（`dest` 是新 temp），**从不调用 `writeLhs`** ⇒ 目标变量永不更新（对比同文件 `:380`：复合赋值分支**调用了** `writeLhs`）⇒ **局部量 `i++` 的写回今天完全缺失**（U-JE1） | **读-算-写三段**：① 读（局部量 ⇒ 直接用该变量；字段 ⇒ `FIELDLOAD`；数组元素 ⇒ `ARRAYLOAD`）② `UNARYOP PRE_INC/PRE_DEC/POST_INC/POST_DEC` ③ **写回**（复用 §4「复合赋值 `x += y`」行的 `writeLhs` 路径：`MOVE`/`FIELDSTORE`/`ARRAYSTORE`）；表达式值 = 写回**前**的值（`POST_*`）或写回**后**的值（`PRE_*`） | 操作数类型 | 现有 IR 即可 |
| **字段的 `++`/复合赋值** | `translateAssign :368-382` → `translateLhsRead`→`writeLhs`；`writeLhs` 字段分支（`:1794-1826`）**只在简单赋值时写回** ⇒ `x.f++` 只发 `UNARYOP`，**字段没写回** | `FIELDLOAD t0=recv.f` → `UNARYOP t1=POST_INC t0` → `FIELDSTORE recv.f=t1` | 字段类型 | 现有 IR 即可 |
| 简单赋值 | `writeLhs :1712-1840`：`MOVE`/`FIELDSTORE`/`ARRAYSTORE` | 三选一 | 目标类型 | 现有 IR 即可 |
| **不支持的赋值目标** | `writeLhs` 末尾 `:1839` 只 `LOG.debug` ⇒ **静默丢弃** | 兜底：`HAVOC` + `VALUE` 级 drop | `UNKNOWN` | 现有 IR 即可 |
| 复合赋值 `x += y` | `:368-382` | 读-算-写三段（字段须写回） | 目标类型 | 现有 IR 即可 |
| 条件表达式 `c?a:b` | `:1495-1537`：分支链 ✓，结果类型恒 `UNKNOWN`（`:1519`、`:1536`） | 分支链 + `MOVE` 到同一 temp | **两侧公共类型** | 现有 IR 即可 |
| **lambda** | `:1564-1651`：**receiver 槽传 `Const.ofNull()`**（`:1630`）⇒ 空指针检查器把每次 lambda 调用当「空接收者调用」；返回类型恒 `UNKNOWN`（`:1579`）；**签名元数与实参数对不上**：签名 = `显式形参数 + 捕获数`（`:1576`），而创建点的实参 = `1 + 捕获数`（`:1626-1639`）⇒ 两者恒不相等；且形参表把**显式形参排在捕获之前**（`:1584-1592`）⇒ 捕获值按位置落到**显式形参**的槽位上（U-JE5） | `args[0]` 传**真实 `this`**（非 static 才传；static 上下文**不传** ⇒ 接收者槽缺省，与 IR-P「owner 未知」的缺省口径同款）；**元数分离**（细则见 §8 #14）：① `calleeSig` 元数 = **显式形参数**（不含接收者、不含捕获数）—— 这是函数式接口方法的元数，也是**调用点**解析调用图边用的数；② 创建点 `CALL.args` = `[接收者?] + 捕获值`；③ 合成函数形参表 = `[接收者?] + 捕获 + 显式形参`（**捕获在显式形参之前** ⇒ 形参表前 `1+捕获数` 个位置与创建实参**逐位对齐**；接收者作为第一个形参后，函数体里的 `this` 才能解析到它）；④ 返回类型取函数式接口方法返回类型 | 同左 | IR-P |
| 闭包捕获 | `:1671-1710` ✓ 捕获变量成形参并在调用点传值（`:1637-1639`） | 保留 | 捕获变量类型 | 现有 IR 即可 |
| 方法引用 | `:788-841`：`calleeSig` 用 **`argOps.size()`**（`:828`）而非延迟调用真实元数（`:839`）⇒ 签名元数与实际调用不一致 | `calleeSig` 用 `referencedArity`（`:839`）+ 挂 `Effect.DEFERRED`（`TacInstruction.java:181`） | 被引用方法返回类型 | 现有 IR 即可 |
| `this` | `:542-547` | 局部量 `this`（带声明类类型，`TacTranslator.java:1010`） | 声明类 | IR-E |
| `super` | `:126-148` 回退成 `this` ✓（非空），但**方法接收者类型**取到当前类 | `this` + 方法接收者类型改用**父类 FQN**（`TacTypeDecl.superType`） | 父类 | IR-E + IR-P |
| 显式构造器调用 `super(...)`/`this(...)` | `StatementTranslator.java:207-220`：`CALL "super.<init>/N"`，**args 里没有接收者** ⇒ 调用图解析不到 | `CALL <父类>.<init>/N`，`args[0]=this`；`this(...)` 用本类 FQN | — | IR-P |
| `instanceof`（无模式） | `:564-576` | `INSTANCEOF` | `BOOLEAN` | 现有 IR 即可 |
| `instanceof`（有模式） | `JavaModernFeatures.java:351-378`：绑定是**无条件** `MOVE`（`:413-418`） | 判定 `INSTANCEOF` ✓；**绑定 MOVE 移到被守卫的分支内**（§5.6） | `BOOLEAN` | 现有 IR 即可 |
| `switch` 表达式 | `JavaModernFeatures.java:135-227` | §5.5 | 分支结果类型 | IR-I + IR-C |
| 强制转换 `(T)x` | `:549-562`：`UNARYOP CAST`+`castType` ✓ | 保留 | 目标类型 | 现有 IR 即可（语义细节 IR-Q） |
| 字段访问 | `:1411-1443`：`FIELDLOAD`，类型恒 `UNKNOWN`（`:1434`） | `FIELDLOAD`+`ownerType`；类型取**字段声明类型** | 字段类型 | IR-E |
| **非限定字段读取（`translateName`）** | `:389-429`：先查形参/局部量（`:392-397`）；**有 `this`** 时发 `FIELDLOAD`（`:406-424`，静态字段走 `fieldReceiver(null, …)`（`:412`）拿类标识 receiver）；**无 `this`**（静态上下文/顶层 —— `this` 只在非 static 且非构造器时注册，`TacTranslator.java:972`）时走 `:425-428`，返回**合成 `UNKNOWN` 变量、不发任何指令** ⇒ **静态上下文里的静态字段读完全不可见**（U-JE3） | 按符号判定三态：① 局部量/形参 ⇒ 直接用该变量（无指令）；② **静态字段**（`owner.isStaticFieldOf(currentClass, name)`，`TacTranslator.java:197-201`）⇒ `FIELDLOAD t=recv.f`，receiver = **类标识**、带 `ownerType`（与 §9 4.1 的**写**侧、§4「全限定字段访问」行同一口径）⇒ 读写两侧终于成对；③ 其它（实例字段/外部符号）⇒ `FIELDLOAD t=this.f` + `ownerType`；**判不出** ⇒ 保留今天的合成 `UNKNOWN` 变量 + `VALUE` 级 drop | 字段类型 | IR-E |
| 数组访问 | `:1397-1409`：`ARRAYLOAD`，类型恒 `UNKNOWN` | `ARRAYLOAD`；类型取**组件类型** | 组件类型 | 现有 IR 即可 |
| `new T(...)` | `:1208-1255`：`NEW`+`ctorType`+`calleeSig` ✓；但 `calleeSig` 的类型名取 `oce.getType().asString()`（`:1232`，**带类型实参**的源码形如 `ArrayList<String>`），再送 `TypeMapper.resolveWellKnownFqnStatic(textual)`（`:1242`）—— 该方法按**整串**匹配已知表（`TypeMapper.java:240-242`；`:386` 的 `default -> simpleName`）⇒ `ArrayList<String>` 落 `default` ⇒ `calleeSig` = `ArrayList<String>.<init>/0`，**不是** `FQN.<init>/N` 形式（U-JE4） | `calleeSig` 用**剥掉类型实参后的 FQN**（`ArrayList.<init>/0`）：类型名先取**擦除后的** `ClassOrInterfaceType.getNameAsString()`（菱形 `new ArrayList<>()` 同理）再送 `resolveWellKnownFqnStatic`；`ctorType` 仍保留带实参的文本形（供 §6 的类型维度用）；`calleeSig` 的元数 = **被调方声明的形参数**（IR-P） | `T` | IR-P |
| 数组创建/初始化器 | `:1257-1309`、`:1325-1339`：`NEWARRAY`+`ARRAYSTORE` ✓ | 保留 | `T[]` | 现有 IR 即可 |
| `arr.length` | 落到 `translateFieldAccess :1411-1443` ⇒ `FIELDLOAD arr.length`；引擎**确实**按 `fieldName=="length"` 识别（`plumb-dfa/src/main/java/org/plumb/dfa/taint/GuardTracker.java:369`、`plumb-dfa/src/main/java/org/plumb/dfa/checkers/controlflow/OffByOneChecker.java:132`） | 保留（形状已对） | `INT` | 现有 IR 即可 |
| **表达式上的注解** | Java **无**表达式级注解；只有类型注解（type-use） | Java 表达式面无此构念；类型注解目前**无 IR 通道** | — | IR-AN（§8-17，低优先级） |
| **裸数组初始化器 `{1,2,3}`** | `:234-243`：注释明说「The component type is not directly available on the AST node」⇒ **硬编码 `TacType.UNKNOWN`**（`:242`）⇒ 字面量初始化的 `char[]`/`byte[]` 无法被识别（`:1266-1267` 的 javadoc 正是拿这个当「可预测 key」检查的前提） | 组件类型从**声明上下文**取（`StatementTranslator` 在变量声明处把声明类型传给 `translateArrayInitializer`，**不经 `UNKNOWN`**）；`NEWARRAY` + n 条 `ARRAYSTORE` | `ARRAY<组件类型>` | IR-E |
| **多维数组创建** | `:1291-1307`：多层只发**嵌套 `NEWARRAY`**，**从不 `ARRAYSTORE`** ⇒ 行从未存进外层数组；且 `outer = b.newTemp(TacType.arrayOf(component))`（`:1304`）⇒ 类型是 `int[]` 而非 `int[][]` ⇒ 形状分析看到的是空的 2 元素数组 | 逐层 `NEWARRAY` **并把行数组存进外层**（`ARRAYSTORE outer[i] = row`）；类型逐层 `arrayOf` ⇒ `ARRAY<ARRAY<T>>` | `ARRAY<ARRAY<T>>` | IR-E + IR-D |
| **装箱 / 拆箱** | `:151-157` 把整数字面量降成 `Const.ofInt` + `INT`，而变量声明类型是 `Integer` ⇒ **包装类型变量持有原始常量**；`intValue()` 是不透明的 JDK 调用 | 装箱：值不变、**类型**提升为 `CLASS<Integer>`（TAC 无堆 ⇒ 不生成额外指令）；拆箱：在**已知拆箱方法**（`intValue`/`longValue`/…）上把接收者类型收窄回原始类型、结果类型改为 `INT`/`LONG`，**不发 `UNINTERPRETED`** | `INT` ⇄ `CLASS<Integer>` | IR-E + IR-H |
| **带转义的字符串 / char 字面量** | `:170` 用 `cle.getValue().charAt(0)`、`:183` 用 `sle.getValue()` ⇒ JavaParser 返回**源码原始文本**，转义序列**未解码** ⇒ `"\n"` 存成 `\` + `n` 两个字符 ⇒ 字面量匹配类规则（硬编码口令、白名单、分隔符）比的是错的字节 | 先**解码转义**再落 `Const`：`\n \r \t \b \f \\ \" \' \s \0` + 八进制 + `\uXXXX`；解码后的值进 `Const.ofString`/`ofChar` | `STRING`/`CHAR` | 现有 IR 即可 |
| **全限定字段访问（幽灵 `FIELDLOAD`）** | `translateFieldAccess :1433-1441` **无条件**把 scope 降成值再发 `FIELDLOAD` ⇒ `java.lang.String` 的 `java`/`lang`/`String` 被当成实例字段读取 ⇒ 产生**虚假字段状态**。注：**方法调用**的同类问题已修（`:604-607` 的 `classScopeFqn`），**字段访问路径没修** | scope 是**类型/包引用**时：类字段 ⇒ `FIELDLOAD` + `ownerType=<类 FQN>`（**不把类名当字段名**）；**包名前缀**（`java.lang`）⇒ **不产生任何指令**（包不是值） | `CLASS` | IR-E |
| **`static final` 常量折叠在声明处被绕过** | `translateFieldAccess :1424-1432` **有** `foldStaticFinal`，但 `StatementTranslator.java:295-307` 的局部变量声明路径**直接发 `FIELDLOAD`**（`:297-303`），**不走折叠** ⇒ `int count = Integer.MAX_VALUE;` 仍是 `FIELDLOAD` ⇒ CWE-400/789 依赖的「明显的大常量」这一 source 在最常见写法下不可见（`:1418-1423` 的注释自己写着这件事） | 声明路径**复用同一折叠**（或统一走 `translateFieldAccess`）⇒ `Const.ofInt(2147483647)`；常量值取自**被扫描源码里同一常量的声明**（编译期常量折叠）⇒ 需要按 FQN 索引的常量表 | `INT`/`LONG` | IR-E + IR-D |
| **可变参数（varargs）调用的 arity** | `:692-693`：`calleeSig` 的元数用 `mce.getArguments().size()`（**源码实参个数**），而被调方把 varargs 声明成**一个数组形参** ⇒ `v/2` vs `v/1` ⇒ 落到解析第 3 层 ⇒ **调用边丢失** ⇒ 跨过程污点过不了可变参数方法 | calleeSig 的元数用**被调方声明的形参数**（IR-P：只数显式形参）；调用点把超出定长部分的实参**装进合成数组**（`NEWARRAY` + n 条 `ARRAYSTORE`）作为最后一个实参；被调方形参类型 `ARRAY<组件类型>` | `ARRAY<组件类型>` | IR-P + IR-E |
| **非限定实例方法调用丢掉隐含 `this`** | `:585-586`：scope 不存在时 `receiverTypeName` 保持 `null` 且**不把接收者放进 `args`** ⇒ 实参与形参**错位一位**；引擎兜底「`sourceParamIndex+1` 否则 `sourceParamIndex`」是**自承的保守近似**（`TaintedCheckerBase.java:5913-5922` 的注释原文："assume the call is an instance method"）⇒ 对同类多参调用**实参绑到了错误的形参** | 非限定**实例**方法调用 ⇒ `args[0] = this`（静态方法**不传**，与 IR-P「接收者一律 `args[0]`」一致）；calleeSig 元数只数显式形参 | 接收者类型 | IR-P |

---

## 5. 语句与控制流方案

### 5.1 短路 `&&`/`||`（当前最大的语义错误）

现状：`ExpressionTranslator.java:322-336` 两侧都求值后发一条 `BINOP LAND/LOR` ⇒ **右侧永远被执行**（「`a != null && a.foo()`」在 TAC 里解引用空；「`isAuthorized() || doWork()`」在 TAC 里执行 `doWork()`）。

```text
t_a = <a>                                        ; 只求值左侧
BRANCH t_a -> L_AND_TRUE, L_AND_EVAL_B          ; guard = Truthiness(t_a, TRUE)
L_AND_EVAL_B:
t_b = <b>                                        ; 右侧只在需要时求值
BRANCH t_b -> L_AND_TRUE, L_AND_FALSE           ; guard = Truthiness(t_b, TRUE)
L_AND_TRUE:  t_res = true   ; JUMP L_AND_END
L_AND_FALSE: t_res = false
L_AND_END:
```

`||` 同形，把两个 `BRANCH` 的真/假目标对调。守卫 = IR-C `Truthiness(operand, TRUE)`；Java 的真值规则只有一条：只有 `false` 为假（`02` §E2）。结果类型 `BOOLEAN`。**不需要新 opcode**。

### 5.2 `if` / `while` / `do` / `for`

| 模板 | 现状 | 目标形态 | 守卫 | 标签 |
|---|---|---|---|---|
| `if(c) A else B` | `StatementTranslator.java:336-354` ✓ 形状已对 | 保留 | `Compare(c,EQ,true)` 或 `Truthiness(c,TRUE)` | `L_THEN`/`L_ELSE`/`L_END_IF`（BLOCK） |
| `while(c) B` | `:356-377` ✓ | 保留 | 同上 | `LOOP_HEAD`/`LOOP_BODY`/`LOOP_END` |
| `do B while(c)` | `:379-396` ✓（continue 目标=条件标签 ✓） | 保留 | 同上 | `LOOP_BODY`/`LOOP_END` |
| `for(init;c;upd) B` | `:398-439` ✓（continue 目标=update 标签 ✓） | 保留 | 同上 | `LOOP_HEAD`/`LOOP_BODY`/`LOOP_BODY_END`(update)/`LOOP_END` |
| 增强 `for(T v : it)` | `:441-507` | 见下三处修 | 同上 | `LOOP_HEAD`/`LOOP_BODY`/`LOOP_END` |

**增强 for 三处修**：① **callee 名必须是真名**（`02` §IR-P）——今天发的是合成短名 `"iterator/0"`、`"hasNext/0"`、`"next/0"`（`StatementTranslator.java:451`、`:466`、`:482`）⇒ 调用图解析不到，改 `java.lang.Iterable.iterator/0`、`java.util.Iterator.hasNext/0`、`java.util.Iterator.next/0`；② **数组不是可迭代对象**，Java 对数组走**索引循环**，今天无条件走迭代器 ⇒ 数组的 for-each 调用了数组上不存在的方法，改 `t_len = FIELDLOAD arr.length`（引擎已按 `fieldName=="length"` 识别，`GuardTracker.java:369`）+ 索引递增 + `ARRAYLOAD`；③ **多变量声明不是缺口**——`for (int i=0,j=1 : arr)` 在 Java 里**不合法**，今天 `:488-489` 的告警只对**源码已损坏**的树有意义。

### 5.3 `switch` 语句（`StatementTranslator.java:843-917`）

| 缺陷 | 证据 | 后果 |
|---|---|---|
| **多标签 arm 只测第一个标签** | `:864-899`：每个标签发一条 `BRANCH`，但**假目标是「下一个 entry」**（`:891`：`caseLabels.get(i+1)`）⇒ 第一个标签不匹配时直接跳到下一个 entry 的**体**，第二个标签的测试（`:881-898`）永远到不了 | `case 1,2 ->` 只有 `disc==1` 生效 |
| **`default` 位置错** | `:859-863`：`default` 在**链上的源位置**发一条无条件 `JUMP` ⇒ 短路掉后续 entry 的全部测试 | `default:` 不在最后时后面的 case 不可达 |
| **switch 表达式同样两处** | `JavaModernFeatures.java:157-162`（default 位置）、`:203-215`（arm 后**无 `JUMP`** ⇒ 每个 arm 的体**依次执行**） | 结果被最后一个 arm 覆盖；所有 arm 的副作用都发生 |

```text
; ---- 1. 选择链：每条【标签】一个测试，假目标串到【下一个标签的测试】 ----
t_0 = (disc == L0)              ; BINOP EQ
BRANCH t_0 -> C_ENTRY_0, T_1
T_1: t_1 = (disc == L1)         ; 同一 entry 的第二个标签
BRANCH t_1 -> C_ENTRY_0, T_2    ; 假目标是【下一个标签的测试】，不是下一个 entry
T_2: t_2 = (disc == L2)         ; 下一个 entry 的第一个标签
BRANCH t_2 -> C_ENTRY_1, T_3
T_n: BRANCH -> C_DEFAULT_or_END ; 最后一条测试的假目标 = default 的体
; ---- 2. 体：按【源顺序】排列，case 体末尾【不】发 JUMP ⇒ fall-through ----
C_ENTRY_0:  <case 0 的语句>      ; 无 JUMP ⇒ 落入 C_ENTRY_1
C_ENTRY_1:  <case 1 的语句>
C_DEFAULT:  <default 的语句>
C_SW_END:
```

**选择链与体序列分离**：选择链决定「从哪个 case 开始执行」，体序列保持源顺序 ⇒ **fall-through 天然成立**（今天已成立：`:903-912` 的体之间没有 JUMP）。**`default` 的体标签是选择链最后一条测试的假目标**，但 default 的**体**仍放在源顺序位置上 ⇒ 既修了「default 短路后续测试」，又保住 fall-through（`default:` 在中间时其后 case 体仍可达）。模式标签条件是 `INSTANCEOF`（今天 `:868-880` ✓），绑定在**体内**做（`:906-908` + `JavaModernFeatures.java:407-439` ✓）。`case null` = `BINOP EQ` 与空常量比较（`JavaModernFeatures.java:315-326` 已有此形状，语句侧需补）。守卫：每条测试一条 IR-C `Compare(disc,EQ,label)`，多标签可用 `Membership`；case 列表/fall-through/default 位置/穷尽性 ⇒ **IR-I**（结构装不进指令流）。

#### 5.3.1 模式守卫（`case <pattern> when <expr>`）

Java 21 的 guard 表达式是 arm 的**附加条件**：标签命中**且**守卫成立才进 arm；**守卫不成立要落到下一个 case**（不是直接落 `default`）。

- **现状**：`plumb-tac/src/main/java/org/plumb/tac/translate/` 全包 grep `getGuard` **零命中**（本轮实测）⇒ JavaParser 的 `SwitchEntry.getGuard()` **从不被读取**。语句侧 `StatementTranslator.java:843-917` 的 `translateSwitch` 与表达式侧 `JavaModernFeatures.java:268-302` 的 `armCondition` 都只处理**标签**（常量 / 模式 / `null`）⇒ 带 `when` 的 arm **只按类型命中**，守卫表达式本身**从不被翻译**（它的污点与副作用都不进 TAC）。
- **目标形态**：在 §5.3 的选择链上，**模式/常量测试之后追加一条守卫测试**：

```text
; 模式 arm：类型测试 + 守卫测试，两条都成立才进 arm
t_type = INSTANCEOF disc, String          ; guard = TypeTest(disc, String, false)
BRANCH t_type -> L_GUARD_TEST, T_next      ; 类型不匹配 ⇒ 下一个【标签】的测试
L_GUARD_TEST:
t_g = <守卫表达式>                         ; guard = <守卫的 IR-C 谓词>（Compare/Truthiness/Membership）
BRANCH t_g -> C_ENTRY_0, T_next            ; ★ 守卫不成立 ⇒ 下一个 case（不是 default）
C_ENTRY_0:  <arm 体>
```

- **守卫表达式照常翻译**（它是普通表达式 ⇒ 它的污点、它的副作用都进 TAC）⇒ 守卫里的 source 也能被规则看到；守卫为**常量表达式**时走 §4 的常量路径。
- **承载能力**：守卫谓词用 **IR-C `TacGuard`**（`Compare`/`Truthiness`/`Membership`/`TypeTest` 四个变体已够）⇒ **不需要新字段**；一个 arm 的多条守卫同时进 **IR-I `TacCase.guards`**（一个 case 多个 guard ⇒ OR 语义），「守卫失败落到下一个 case」由 `TacCase.nextLabel` 承载 ⇒ **不需要新字段**。
- **两种 arm 形式同形**：箭头形式（`case String s when s.length() > 3 ->`）与冒号形式（`case String s when …:`）都追加守卫测试；冒号形式的 **fall-through 规则不变**（守卫失败落到下一个 case 的**测试**，不是下一个 case 的**体**）。
- **语句侧与表达式侧都要改**：语句侧 `translateSwitch`（`StatementTranslator.java:843`）与表达式侧 `armCondition`（`JavaModernFeatures.java:268`）各改一处（§9 改动 3.12 / 3.13）。

### 5.4 `try`/`catch`/`finally`（`StatementTranslator.java:645-841`）

| 缺陷 | 证据 |
|---|---|
| try 体正常完成**无 `JUMP L_FINALLY`** ⇒ 直接落入 catch 的 LABEL | `:711-723` |
| `return`/`throw` 在 try 体里**直接结束，跳过 finally** | `translateReturn :509-519` / `translateThrow :521-528` 就地发 `RETURN`/`THROW` |
| catch 体结束后**无 `JUMP L_FINALLY`** | `:797` 之后直接进下一个 catch 或 finally |

**CFG 构建器的契约本来就要求前端发这些 JUMP**：`plumb-tac/src/main/java/org/plumb/tac/ir/TacCfgBuilder.java:56-60` 明写 ——
> "The normal successors (try-completion fall-through into finally, catch-completion fall-through into finally) are the translator's responsibility -- the translator emits explicit `JUMP` instructions to `L_FINALLY` labels. This builder only wires the *non-normal* paths."

⇒ **契约已写好、前端没实现**：纯工程缺口，不是 IR 缺口。

**待决出口协议**：每个 `try`（及 try-with-resources 的隐式 finally）分配 `t_exit`（`INT`：0=正常/1=return/2=throw/3=break/4=continue）、`t_val`（出口值）、`t_exc_pending`（`BOOLEAN`）。

```text
; ---- 资源初始化（在 try 体之前）----
t_r = <initializer>                  ; MOVE
; ---- try 体 ----
L_TRY_BEGIN:
  <try 体>
  t_exit = 0
  JUMP L_FINALLY                    ; ★ 现状缺失
; ---- catch 体 ----
L_CATCH_E:
  CATCH_HANDLER dest=t_e ctorType=E ; 现状已有（:780-785）
  <catch 体>
  t_exit = 0
  JUMP L_FINALLY                    ; ★ 现状缺失
; ---- finally 体 ----
L_FINALLY:
  FINALLY_ENTER
  <finally 体>
  t_is_ret = (t_exit == 1)          ; BINOP EQ
  BRANCH t_is_ret -> L_EXIT_RET, L_EXIT_CHECK
L_EXIT_CHECK:
  t_is_thr = (t_exit == 2)
  BRANCH t_is_thr -> L_EXIT_THROW, L_EXIT_CONT
L_EXIT_RET:   RETURN t_val
L_EXIT_THROW: THROW t_val
L_EXIT_CONT:  ; 正常完成 ⇒ 继续 try 之后的语句
```

- try 体内 `return v` ⇒ `t_val=v; t_exit=1; JUMP L_FINALLY`；`throw e` ⇒ `t_val=e; t_exit=2; JUMP L_FINALLY`；`break`/`continue`（目标在 try 外）⇒ `t_exit=3/4; JUMP L_FINALLY`。
- **finally 自身的 `return`** ⇒ 按 Java 语义**覆盖**待决 return ⇒ `t_val=v; t_exit=1; JUMP <外层 finally 或函数出口>`（**不**跳回本 finally）⇒ 需要 finally 栈；`StatementTranslator` 现有 `loopCtx`/`switchEndLabel` 是 `ThreadLocal` 栈（`:70`、`:80`），同构加一个即可。
- **未被任何 catch 匹配的异常** ⇒ 唯一前端无法静态决定的一条（`CALL` 可能抛任何异常）。CFG 构建器把 try 体内块的异常边接到 finally 的**异常入口** `L_FINALLY_EXC`（`TacCfgBuilder` 已有异常边机制 `:301-314`）；异常值由引擎按 `CATCH_HANDLER` 同款约定绑定到 `t_val`（`TacInstruction.java:65-73` 已规定该约定）。
- `TryRegion` 需填：`catchTypes`（今天只有 `catchLabels`，`TacFunction.java:774`）、`resourceVars`、`syntheticFinally`、`finallyExits`（IR-F）。

### 5.5 `switch` 表达式（`JavaModernFeatures.java:135-227`）

```text
t_res = <结果 temp>                ; 类型 = 各 arm 公共类型
; ---- 选择链（同 §5.3）----
t_0 = (disc == L0) ; BRANCH t_0 -> A_0, T_1 ...
; ---- arm 体：每个 arm 结束【必须】JUMP 到 end ----
A_0:
  <pattern 绑定>                    ; MOVE
  t_res = <arrow 表达式>             ; MOVE（arrow 形态）
  JUMP L_SWEXPR_END                 ; ★ 现状缺失（:203-215）
A_1:
  <block 体>
  ; yield v; ⇒ MOVE t_res = v ; JUMP L_SWEXPR_END   ; ★ 现状缺失
  JUMP L_SWEXPR_END
L_SWEXPR_END:
```

`yield` 需知道 end 标签：今天 `yieldTarget` 只是 `ThreadLocal<TacVar>`（`ExpressionTranslator.java:90`、`:104-112`）⇒ 扩成 `(结果 temp, end 标签)` 上下文（前端内部状态，**不需新 IR**）。穷尽性由编译器保证，前端不校验，但可记入 IR-I 的 `TacBranchRegion.exhaustive`。

### 5.6 带模式的 `instanceof` 与分支内绑定

`JavaModernFeatures.java:351-378` 的绑定是**无条件**的（`:413-418` 的 `MOVE` 在 `BRANCH` 之前）；Java 规则是模式变量**只在测试成功时**存在。目标形态：

```text
t_test = INSTANCEOF x, String       ; guard = TypeTest(x, String, negated=false)
BRANCH t_test -> L_THEN, L_ELSE
L_THEN:
  s = MOVE x                        ; ★ 绑定移到分支内
  <then 体>
L_ELSE: <else 体>
```

⇒ `translateIf`（`StatementTranslator.java:336-354`）必须识别「条件是带模式的 `instanceof`」，只翻译**判定**，把**绑定**交给 then 分支。

### 5.7 try-with-resources（`StatementTranslator.java:585-611`、`:634-643`）

| 缺陷 | 证据 | 修法 |
|---|---|---|
| **无初始化器的资源被跳过** ⇒ 不发 `close()` | `:588-591`：不是 `VariableDeclarationExpr` 就 `continue`，**没有加入 `resources`** | 裸名资源：解析到已有局部量/形参，**加入 resources**（不新建局部量）⇒ `close()` 会发 |
| **`close()` 只在正常路径** | `:718-720`：close 在 try 范围之后发 ⇒ 异常路径与 `return` 路径都不 close | close 序列成为**隐式 finally** 的一部分（§5.4）：正常/return/throw 三条出口都执行 close |
| 资源初始化 | `:601-607` ✓ 已发 `MOVE` | 保留 |

`TryRegion.resourceVars`（IR-F）记录这些变量，`syntheticFinally=true` 标记「编译器合成的 finally」。

### 5.8 `synchronized`（`StatementTranslator.java:944-948`）

今天只求值锁对象 + 翻译体，**无任何锁语义**。目标形态：

```text
t_lock = <锁对象>                  ; 只求值一次
L_MON_BEGIN:
  <临界区体>
L_MON_END:
```

临界区的**范围**（`L_MON_BEGIN`..`L_MON_END`）与**监视器**（`t_lock`）必须进 IR ⇒ 需要 IR-M 的 `TacCriticalSection{label, monitorExpr, kind=SYNCHRONIZED}`。**争用顺序不可静态判定** ⇒ §12 的 `E1`。

### 5.9 标签语句与带标签的 `break`/`continue`（`StatementTranslator.java:938-942`）

今天 `translateLabeled` **只翻译内层语句，标签名被丢弃** ⇒ `break outer;` 退化成「跳出最近的循环」⇒ **跳错目标**。

- 前端维护**标签栈**：`labelName → (break 目标标签, continue 目标标签, 标签种类)`。
- `outer: for(..) { for(..) { break outer; } }` ⇒ `outer` 的 break 目标 = **外层 for 的 `L_FOR_END`**，continue 目标 = **外层 for 的 `L_FOR_UPDATE`**。
- `break outer;` ⇒ `JUMP <outer 的 break 目标>` + `labelRef{kind=LOOP_END, scopeId}`（IR-G）；`continue outer;` ⇒ `JUMP <outer 的 continue 目标>` + `labelRef{kind=LOOP_BODY_END}`；标签语句本身 ⇒ `labelRef{kind=BLOCK}`。
- **带标签的 `break` 在 `try` 内** ⇒ 走 §5.4 的待决出口协议（`t_exit=3`）。

### 5.10 `assert`（`StatementTranslator.java:950-955`）

今天谓词与消息**都被无条件求值**、且**不抛异常** ⇒ 消息在断言成立时也被求值（Java 只在失败时求值 ⇒ 副作用被凭空增加）；失败路径不存在。目标形态：

```text
t_c = <谓词>
BRANCH t_c -> L_ASSERT_OK, L_ASSERT_FAIL
L_ASSERT_FAIL:
  t_msg = <消息>                  ; ★ 只在失败路径求值
  t_e = NEW java.lang.AssertionError
  THROW t_e
L_ASSERT_OK:
```

断言是否生效由运行期开关 `-ea` 决定 ⇒ §12 的 `E2`。

### 5.11 `yield`（`StatementTranslator.java:177-206`）

今天求值后 `MOVE` 进结果 temp，**无 `JUMP` 到 switch 表达式末尾** ⇒ 落入下一个 arm ⇒ 与 §5.5 同一缺陷，修法同 §5.5。

### 5.12 `throw`/`return` 与 `finally` 的交互

见 §5.4：**try 体内的一切「突然完成」（return/throw/break/continue）都必须经过待决出口协议**；finally 尾部的出口分派决定最终是 `RETURN`/`THROW`/继续。

---

## 6. 类型面方案

| Java 类型 | 现状证据 | 目标 `TacType` | IR |
|---|---|---|---|
| 基本类型 | `TypeMapper.java:390-402` ✓ | `BYTE/SHORT/CHAR/INT/LONG/FLOAT/DOUBLE` + **值域** | IR-D（`Integral(8/16/32/64)`、`Floating(24,8)/(53,11)`） |
| `void` | `:137-150` ✓ | `VOID` | 现有 IR 即可 |
| `String`/数组 | `:68`、`:104`、`:151-153` ✓ | `STRING`/`ARRAY(组件)` | 现有 IR 即可 |
| **泛型实参** | **只有函数返回类型保留**（`:58-81` `mapPreservingTypeArgs`），**参数与局部变量被擦除**（`:154-157` 用 `getNameAsString()`） | 所有位置保留实参：`CLASS("java.util.List")`+`typeArguments=[STRING]` | IR-K |
| **通配符 `?`/`? extends T`/`? super T`** | `:216-218` ⇒ `UNKNOWN` | `?`/`? super T` ⇒ `CLASS("java.lang.Object")`；`? extends T` ⇒ `bound=T` | IR-K `bound` |
| **类型变量 `T`** | `:200-215`：只取**第一个**上界（`bounds.get(0)`），无界 ⇒ `java.lang.Object` | `bound`=第一个上界；**完整上界集合**（`T extends A & B`）进 `TacTypeParam.bounds` | IR-K + IR-N |
| **交集 `A & B`** | `:183-199` ⇒ 假类名 `"A&B"` ⇒ **不是任何真实类型**，按类型匹配的规则**永远不命中** | Java 的交集只作为**类型参数上界**出现 ⇒ 由 IR-N 的 `typeParameters[].bounds` 承载 | IR-K + IR-N |
| **联合类型** | `:161-182` ⇒ 假类名 `"A\|B"` | **Java 没有联合类型**（`\|` 只出现在**模式**里，不是类型）⇒ 该分支对 Java **是死代码**；假类名问题在别的语言解决 | — |
| `var` | `:216-218` ⇒ `UNKNOWN` | **推断**：① `initializer.calculateResolvedType()`；② 初始化目标是已声明字段/局部量时取其声明类型；③ 增强 for 取可迭代物的元素类型。推不出 ⇒ `UNKNOWN` + `VALUE` 级 drop | 现有 IR 即可（依赖符号解析器，见 §11 K6） |
| `null` | `TypeMapper` 无对应 ⇒ `TacType.NULL`（`TacType.java:76`） | `NULL` 是**值级**（`TacType.java:119-121`），与 `nullable`（类型级）**不得混用** | 现有 IR 即可 |
| 类的类型 | `:154-157` + `resolveWellKnownFqn`（`:244-388`，100+ JDK 类表） | `CLASS(fqn)`；表外保持简单名 | 现有 IR 即可（IR-P 统一 FQN 规范） |
| **可空性** | Java 路径**从不**调 `nonNull()` ⇒ 全 `nullable=true` | **保持 `nullable=true`**：Java 无非空类型系统，任何引用类型都可空（`TacType.java:103-112`：「The default is `true`, which is Java's semantics」） | 现有 IR 即可 |

**`nullable=false` 在 Java 上何时可以、何时不可以**：

| 场景 | 判定 | 理由 |
|---|---|---|
| Java 的任何引用类型声明 | **不可以**置 `false` | Java 无非空类型系统；置 false 会让空指针检查器压掉真实告警（`IrPrecision.java:126-128` 的方向性规则） |
| `Kind.NULL`（空字面量） | 与 `nullable` 无关 | 值级 vs 类型级（`TacType.java:119-121`） |
| `if (x != null)` 之后 | **不用类型表达**，用**守卫** | `x != null` 是 IR-C 的 `Compare(x,NE,null)`；引擎据此在 true 分支建立「x 非空」的**流事实** |
| `Objects.requireNonNull(x)` | 同上 | 同理 |
| Kotlin 的 `String` | （不在本文范围） | `TacType.nonNull()`（`TacType.java:141-144`）是给**有非空类型系统的语言**用的 |

⇒ **Java 前端不得调用 `nonNull()`**。若将来引入 `NULL_CHECK`（`TacInstruction.java:109`），Java 侧**也不发**——JVM 不插入空检查，非空事实只能来自源码里的显式检查。

---

## 7. 精度与丢弃可见性

| 现状事实 | 证据 |
|---|---|
| Java 前端自报 `SOURCE_FULL` | `plumb-tac/src/main/java/org/plumb/tac/frontend/java/JavaTacFrontend.java:126-131` `defaultPrecision()`；其 javadoc（`:60-65`）断言「Java 构念全都无损降级」——**该断言今天不成立** |
| `parse()` 只收**解析**问题，翻译器的问题被丢掉 | `JavaTacFrontend.java:134-157`：只遍历 `parsed.problems()`；`translator.translate()` 产生的 `programProblems()` **没有**被收集 |
| `parseStructured()` 收了问题，但精度只认 `SOURCE_LOWERED` | `JavaTacFrontend.java:182-196`：只检查 `fn.irPrecision() == SOURCE_LOWERED` ⇒ `SOURCE_DEGRADED` 不会被聚合 |
| `setIrPrecision` 在 `plumb-tac` 内**从未被调用** | 全仓 grep：仅 `plumb-frontend/.../kotlin/KotlinTacTranslator.java:236`、`.../scala/ScalaTacTranslator.java:358` 与一个测试 `plumb-dfa/src/test/java/org/plumb/dfa/engine/ModelCoverageTest.java:188` |
| ⇒ **每个 Java 函数都声称完整精度** | `TacBuilder.java:183` 默认 `SOURCE_FULL`，`build()` 原样带出（`:417-437`） |

**改后**：① **前端不再自报精度**——`TacBuilder.setIrPrecision`（`TacBuilder.java:186-188`）在 Java 路径**不再调用**，`TacFunction.irPrecision` 由 `TacFunction.derivePrecision(drops)` 计算（`01` §IR-B）；② **统一推导规则**（所有语言同一份）：有 `SEMANTICS` 级 drop ⇒ `SOURCE_DEGRADED`，有 `STRUCTURE` 级 ⇒ `SOURCE_LOWERED`，只有 `VALUE` 级 ⇒ 仍 `SOURCE_FULL`；③ **Java 侧 drop 只在真的无法静态表达时产生**（§12）⇒ §9 改动全部完成后，**Java 函数应当全部是 `SOURCE_FULL`**——这正是「全部支持」的判定标准（`02` §0）；④ **消费方**：`ModelCoverageComputer.java:133`（`01` §IR-A 点名的唯一生产读点）扩展为三档统计，报告器附「本次扫描的 drop 清单」（按 `construct` 聚合，不逐条铺开——`01` §IR-B 风险项）；⑤ **问题通道保留但改用途**：`TacProgram.FunctionProblem`（`TacProgram.java:69-96`）继续承担「**输入坏了/翻译失败**」（`PARSE_PROBLEM`/`TRANSLATION_ERROR`），`TacDrop` 专职「**构念没完整表达**」，两者**不合并**（`01` §IR-B 风险项）；⑥ **Java 前端必须把翻译器问题带到 `parse()`**：今天 `parse()` 丢掉了它们（`JavaTacFrontend.java:134-157`）⇒ 修：也收集 `program.programProblems()`。

**消费方现在能问什么**：`fn.irPrecision().preservesSemantics()`（IR-A）· `fn.drops()`（IR-B）· `fn.branchRegions()`（IR-I）· `ins.guard()`（IR-C）· `region.finallyExits()`（IR-F）· `ins.ownerType()`（IR-E）· `fn.memberKind()`（IR-E）。

---

## 8. IR 需求（Java 侧）

| # | Java 构念 | 能力 | 数据形状 | 为什么现有 IR 不够 |
|---|---|---|---|---|
| 1 | 静态字段初始化、字段归属 | IR-E `ownerType` | `TacInstruction.ownerType : String?`（`FIELDLOAD`/`FIELDSTORE`） | `FIELDSTORE` 只有 `fieldName`（`TacInstruction.java:233-234`）⇒ 同名字段（不同类）在引擎的 per-class 字段表里混在一起 |
| 2 | 成员种类、类节点 | IR-E `memberKind` + `TacTypeDecl` | `TacFunction.memberKind : TacMemberKind?`；`TacProgram.types : List<TacTypeDecl>` | `TacFunction` 字段表（`TacFunction.java:70-126`）**无成员种类**；`TacProgram` 只有 `functions`/`classToParent`/`classToChildren`/`classFieldAnnotations`（`TacProgram.java:112-114`、`:128`、`:140`）⇒ **IR 里没有「类」节点** |
| 3 | 短路、循环条件、switch 选择、`if` 条件 | IR-C `TacGuard` | `TacInstruction.guard : TacGuard?`（仅 `BRANCH` 有意义）；`Compare`/`Truthiness`/`Membership`/`TypeTest`/`Always` | `BRANCH` 只有 `lhs1`+`trueLabel`/`falseLabel`（`TacInstruction.java:192-243`）⇒ 条件只是**布尔临时变量**，路径条件无法从 CFG 推出（`01` §IR-C 核心论证） |
| 4 | `finally` 的多出口 | IR-F `TryRegion.finallyExits` | `List<String> finallyExits` | `TryRegion` 只有 `finallyLabel`（`TacFunction.java:781`）⇒ 「finally 在哪几个出口被执行」要靠 CFG 构建器自己数（`TacCfgBuilder.java:299-300`、`:316-323`），无法表达「异常入口」 |
| 5 | catch 的异常类型 | IR-F `catchTypes` | `List<String> catchTypes`（与 `catchLabels` 同序） | 只有 `catchLabels`（`TacFunction.java:774`）⇒ catch 匹配只能靠单点 `CATCH_HANDLER.ctorType`（`TacInstruction.java:73`） |
| 6 | try-with-resources 的资源 | IR-F `resourceVars` | `List<TacOperand> resourceVars` | `TryRegion` 无资源字段 ⇒ 「异常时也要关闭」无承载处 |
| 7 | 带标签跳转、标签种类 | IR-G `TacLabelRef` | `TacInstruction.labelRef : TacLabelRef?`；`kind ∈ {FUNCTION_ENTRY, FUNCTION_EXIT, BLOCK, LOOP_HEAD, LOOP_BODY_END, LOOP_END, SWITCH_HEAD, SWITCH_CASE, SWITCH_END, CATCH_ENTRY, FINALLY_ENTRY}` | `label`/`trueLabel`/`falseLabel` 是**裸字符串**（`TacInstruction.java:237-243`）⇒ 跳转目标无语义，前端写错了 IR 也发现不了（`01` §IR-G） |
| 8 | switch 的 case 列表/fall-through/default 位置/穷尽性 | IR-I `TacBranchRegion` | `TacBranchRegion{id,kind,subject,cases,defaultCaseId,fallThrough,exhaustive,missingCases,scopeId}` + `TacCase{id,guards,bindings,bodyLabel,nextLabel}` | 这些是**结构**（集合与否定事实），**指令流装不下** |
| 9 | `throw` 的类型 | IR-J `THROW.ctorType` | 复用 `TacInstruction.ctorType`（`:231`） | 抛出点**没有类型**（类型只在 `CATCH_HANDLER.ctorType`）⇒ catch 匹配只能靠 CFG 侧比对 |
| 10 | 泛型实参/通配符/类型变量/交集 | IR-K `TacType` 三个可选字段 | `typeArguments : List<TacType>?`、`bound : TacType?`、`unionMembers : List<TacType>?` | `TacType` 只有 `kind`+`payload`+`nullable`（`TacType.java:98-123`）⇒ 泛型被擦除（`TypeMapper.java:154-157`）、通配符变 `UNKNOWN`（`:216-218`）、交集变**假类名**（`:183-199`） |
| 11 | `sealed`/`permits` | IR-N `permittedSubtypes`+`typeParameters` | `List<String> permittedSubtypes`；`List<TacTypeParam> typeParameters` | IR 里没有类型节点 ⇒ 允许的子类型无处可放 |
| 12 | `synchronized` 的临界区 | IR-M `TacCriticalSection` | 函数级 `List<TacCriticalSection>{label, monitorExpr, kind}` | `translateSynchronized`（`StatementTranslator.java:944-948`）**不留任何痕迹** ⇒ 原子性 checker 无从判断两条写是否在同一把锁下 |
| 13 | 数值宽度/溢出 | IR-D `TacValueDomain` | `TacType.domain : TacValueDomain?` | `TacType` 无域字段 ⇒ int32 回绕不是 Z3 推出来的，而是 checker 自维护区间（`01` §IR-D） |
| 14 | 迭代协议与接收者的 callee 规范；**lambda 的元数分离** | IR-P 命名规范（规范文本，非字段） | `<ownerFqn>.<methodName>/<arity>`；接收者一律 `args[0]`（无接收者时该槽**缺省**，与「owner 未知」同款）；迭代协议用真名；**lambda 的 `calleeSig` 元数 = 显式形参数**（不含接收者、不含捕获数），捕获数不进 `calleeSig`，改由**合成函数形参表**承载（形参表 = `[接收者?] + 捕获 + 显式形参`） | for-each 用合成短名 `"iterator/0"`/`"hasNext/0"`/`"next/0"`（`StatementTranslator.java:451`、`:466`、`:482`）⇒ 调用图解析不到；**lambda 的签名元数**（`ExpressionTranslator.java:1576` 的 `params+captures`）与**创建点实参数**（`:1626-1639` 的 `[null]+captures`）恒不相等，且形参表把显式形参排在捕获之前（`:1584-1592`）⇒ 捕获值按位置落到显式形参的槽位上 |
| 15 | 方法引用是「延迟调用」 | 现有 `Effect.DEFERRED` | `TacInstruction.effects += DEFERRED` | `Effect.DEFERRED` 已存在（`TacInstruction.java:181`）但 Java 路径从未用过；方法引用 `calleeSig` 用错元数（`ExpressionTranslator.java:828` vs `:839`） |
| 16 | 注解类型的零值成员 | IR-AM（新增小项） | 「按类型构造零值」的常量能力 | `translateAnnotationMember` 对无默认值成员发 `NOP`（`TacTranslator.java:1254`）⇒ **该成员没有返回值** |
| 17 | 类型使用注解（type-use） | IR-AN：类型上的注解槽位 | 类型记录上加注解列表 | IR 只有 class/method/field/param 四个注解通道（`TacFunction.java:123-126`）⇒ 类型注解无处可放；**低优先级**（编译期语义，运行时无效果） |
| 18 | **`throws` 子句**（受检异常声明）+ **局部变量声明上的注解** | **IR-AL：声明侧的两个补充通道** | ① `TacFunction.throwsTypes : List<String>`（方法声明的受检异常 FQN 列表，与 `catchTypes` 对应）<br>② `TacOperand.TacVar.annotations : List<String>?`（局部变量/参数的声明注解） | ① `TacFunction` 的四个注解通道（`TacFunction.java:123-126`）是 class/method/field/param，**没有方法级「抛什么」**；`throws` 子句今天**整个消失**（`translateMethod` 不读 `getThrownExceptions()`）⇒ catch 匹配只能靠单点 `CATCH_HANDLER.ctorType`，**无法判断「这个 catch 是否可能捕获受检异常」**<br>② `TacVar`（`TacOperand.java:64-80`）**无注解槽位** ⇒ 局部变量上的 `@NonNull` 等声明注解无处可放 ⇒ 空值分析看不到它<br>⇒ ⚠ 两个字段都进 `equals`/`hashCode` 时要同步（IR-Q 的相等语义表） |
| 19 | **字段修饰符**（`final`/`volatile`/`transient`）与 **`final` 局部量** | **IR-E 的补充细化 + IR-AL 的补充细化**（**两个都不占新编号**；与 §10「可见性分级」是同一档处理） | ① `TacMemberRef` 追加 `List<String> modifiers`（声明修饰符的**简单名**列表；与既有 `annotations : List<String>` 同风格，`01` §IR-E 的 `TacMemberRef` 是 `name/signature/kind/annotations/isStatic/visibility/isSynthetic`）<br>② `TacOperand.TacVar` 追加 `boolean isFinal`（与 IR-AL 已提的 `TacVar.annotations` 同属 `TacVar` 的声明元数据） | ① `TacMemberRef` **没有「这个字段是 `final` 的」这一事实** ⇒ §4「`static final` 常量折叠在声明处被绕过」行要求的「编译期常量折叠」**无法判定前提**（`final` 才是编译期常量的条件）⇒ 该行的目标形态目前**不可达**；② `TacVar`（`TacOperand.java:40-42`）只有 `name/type/isTemp` ⇒ `final` 局部量无处可放。<br>⇒ **零新字段到既有类之外**：修饰符本身**不改变指令流** ⇒ **零指令、不产生 drop**（不破坏 §7 ③「Java 侧应当没有 drop」的判定）；`static final` 的常量值由**前端内部**的按 FQN 索引常量表承载（扩展 `TacTranslator.java:187-201` 的 `staticFieldsByClass`）⇒ 折叠后的值就是指令流里的 `Const`，**不进 IR** |

**不需要的 IR 能力**（明确排除）：异常对象身份的堆模型（§5.4 用引擎约定的槽位即可）；枚举常量的堆身份（`NEW` 足够）；字符串格式化的精确实现（由 JDK 决定 ⇒ 记 `VALUE` 级信息缺失，属 §11 风险）。

---

## 9. 改动清单

| # | 阶段 | 文件 | 方法 | 改动 | IR |
|---|---|---|---|---|---|
| 0.1 | 0 止血 | `translate/TacTranslator.java` | `translateTypeWithPrefix`（`:337`） | 分派链（`:652-760`）加**最终 `else`** → `unsupportedMember(...)`：记 `TacDrop` + `TacTypeDecl.members` 留一条 `TacMemberRef` | IR-E |
| 0.2 | 0 | `translate/ExpressionTranslator.java` | `mapUnary`（`TacTranslator.java:1876-1883`） | 补 `PLUS` ⇒ `+x` 变 `MOVE` 恒等（当前落到 `ExpressionTranslator.java:357-359` 返回空常量且**不报问题**） | 现有 IR 即可 |
| 0.3 | 0 | `translate/ExpressionTranslator.java` | `writeLhs`（`:1712`） | 末尾 `:1839` 的 `LOG.debug` → `reportUnhandled` + `HAVOC` + `VALUE` 级 drop | IR-B |
| 0.4 | 0 | `translate/ExpressionTranslator.java` | `translateLhsRead`（`:1543`） | 末尾 `:1554` 同上 | IR-B |
| 0.5 | 0 | `translate/StatementTranslator.java` | `translateBreak`（`:530`）、`translateContinue`（`:557`） | 无上下文时的 `LOG.warn` → 记 drop | IR-B |
| 0.6 | 0 | `frontend/java/JavaTacFrontend.java` | `parse`（`:134`） | 收集 `program.programProblems()`（今天只收 `parsed.problems()`） | 现有 IR 即可 |
| 0.7 | 0 | `frontend/java/JavaTacFrontend.java` | `parseStructured`（`:167`） | 精度聚合（`:186-194`）改为「取所有函数里**最差**的精度」，覆盖 `SOURCE_DEGRADED` | IR-A |
| 1.1 | 1 IR | `ir/TacFunction.java` | 字段表末尾 | 加 `memberKind`、`drops`；加静态 `derivePrecision(List<TacDrop>)` | IR-A/B/E |
| 1.2 | 1 | `ir/TacInstruction.java` | `Builder` | 加 `ownerType`、`labelRef`、`guard` | IR-C/E/G |
| 1.3 | 1 | `ir/TacType.java` | 字段表末尾 | 加 `typeArguments`、`bound`、`unionMembers`、`domain` | IR-D/K |
| 1.4 | 1 | `ir/TacFunction.java` | `TryRegion` | 加 `catchTypes`、`resourceVars`、`syntheticFinally`、`finallyExits` | IR-F |
| 1.5 | 1 | `ir/TacProgram.java` | 字段表末尾 | 加 `types : List<TacTypeDecl>`、`programDrops` | IR-B/E |
| 1.6 | 1 | `ir/IrPrecision.java` | 枚举末尾 | 加 `SOURCE_DEGRADED` + 查询方法；改 `maySuppressNullDeref()`（`:126-128`）使 `SOURCE_DEGRADED ⇒ false` | IR-A |
| 2.1 | 2 表达式 | `translate/ExpressionTranslator.java` | `translateBinary`（`:322`） | `LAND`/`LOR` 改走 §5.1 的分支链；其余算术保持 | 现有 IR + IR-C |
| 2.2 | 2 | `translate/ExpressionTranslator.java` | `translateAssign`（`:362`）、`translateUnary`（`:338`） | 复合赋值遇**字段目标**补 `FIELDSTORE` 写回（今天 `x.f++` 只发 `UNARYOP`）；**`translateUnary` 的 `++`/`--` 也要写回** —— 范围从「字段目标」放宽到「**所有赋值目标**」（局部量 / 字段 / 数组元素）：`translateUnary`（`:338-360`）今天**从不调用 `writeLhs`**（对比 `:380` 的复合赋值**调用了**）⇒ 局部量 `i++` 的目标永不更新（U-JE1） | 现有 IR 即可 |
| 2.3 | 2 | `translate/ExpressionTranslator.java` | `translateLambda`（`:1564`） | `args[0]` 从 `Const.ofNull()`（`:1630`）改为**真实 `this`**；返回类型取函数式接口方法返回类型 | 现有 IR 即可 |
| 2.4 | 2 | `translate/ExpressionTranslator.java` | `translateMethodReference`（`:788`） | `calleeSig` 用 `referencedArity`（`:839`）而非 `argOps.size()`（`:828`）；挂 `Effect.DEFERRED` | 现有 IR 即可 |
| 2.5 | 2 | `translate/ExpressionTranslator.java` | `translateFieldAccess`（`:1411`）、`translateArrayAccess`（`:1397`） | 结果类型从 `UNKNOWN` 改为**声明类型/组件类型**；`FIELDLOAD` 补 `ownerType` | IR-E |
| 2.6 | 2 | `translate/ExpressionTranslator.java` | `translateConditional`（`:1495`） | 结果 temp 类型取两侧公共类型 | 现有 IR 即可 |
| 2.7 | 2 | `translate/JavaModernFeatures.java` | `bindPatternVariables`（`:407`） | 绑定 MOVE 移到**被守卫的分支内**（配合 3.1） | 现有 IR 即可 |
| 2.8 | 2 | `translate/ExpressionTranslator.java` | `translate`（`:234-243`） | 裸数组初始化器**不再硬编码 `UNKNOWN`**（`:242`）⇒ 组件类型从**声明上下文**传入 | IR-E |
| 2.9 | 2 | `translate/ExpressionTranslator.java` | `translateNewArray`（`:1291-1307`） | 多维数组每层补 `ARRAYSTORE outer[i]=row`（今天**只发嵌套 `NEWARRAY`，行从未存进外层**）；类型逐层 `arrayOf` | IR-E + IR-D |
| 2.10 | 2 | `translate/ExpressionTranslator.java` | `translate`（`:151-157`）、`translateCall` | 装箱：字面量类型随声明提升为 `CLASS<Integer>`；拆箱：识别 `intValue`/`longValue`/… 并收窄接收者类型，**不发 `UNINTERPRETED`** | IR-E + IR-H |
| 2.11 | 2 | `translate/ExpressionTranslator.java` | `translate`（`:169-184`） | 字符串/char 字面量**先解码转义**再落 `Const`（今天存的是源码原始文本） | 现有 IR 即可 |
| 2.12 | 2 | `translate/ExpressionTranslator.java` | `translateFieldAccess`（`:1411-1443`） | scope 是**类型/包引用**时**不发 `FIELDLOAD`**（今天 `java.lang.String` 被当实例字段读 ⇒ 幽灵字段）；类字段改带 `ownerType` | IR-E |
| 2.13 | 2 | `translate/StatementTranslator.java` | 局部变量声明（`:295-307`） | 初始化器是 `FieldAccessExpr` 时**复用 `foldStaticFinal`**（今天直接发 `FIELDLOAD`，绕过了 `ExpressionTranslator.java:1424-1432` 的折叠） | IR-E + IR-D |
| 2.14 | 2 | `translate/ExpressionTranslator.java` | `translateCall`（`:692-693`） | `calleeSig` 元数用**被调方声明的形参数**；调用点把多出的实参装进**合成数组**作最后一个实参 | IR-P + IR-E |
| 2.15 | 2 | `translate/ExpressionTranslator.java` | `translateCall`（`:585-586`） | 无 scope 且是**实例**方法 ⇒ `args[0]=this`（今天不传 ⇒ 实参错位一位） | IR-P |
| 2.16 | 2 | `translate/ExpressionTranslator.java` | `translateName`（`:389`） | 静态上下文分支（`:425-428`）⇒ 按 `owner.isStaticFieldOf(currentClass, name)`（`TacTranslator.java:197-201`）判定静态字段 ⇒ 发 `FIELDLOAD`（receiver=类标识、带 `ownerType`），与 §9 4.1 的**写**侧成对；判不出 ⇒ 才是合成 `UNKNOWN` 变量（U-JE3） | IR-E |
| 2.17 | 2 | `translate/ExpressionTranslator.java` | `translateNew`（`:1208`） | `calleeSig` 的类型名改用**擦除后的类型名**（`ClassOrInterfaceType.getNameAsString()`；菱形 `new ArrayList<>()` 同理）再送 `resolveWellKnownFqnStatic` ⇒ `ArrayList.<init>/0`；`ctorType` 仍保留带实参的文本形（U-JE4） | IR-P |
| 2.18 | 2 | `translate/ExpressionTranslator.java` | `translateLambda`（`:1564`） | **元数分离**（§8 #14）：形参表改成 `[接收者?] + 捕获 + 显式形参`（今天 `:1584-1592` 把显式形参排在捕获**之前** ⇒ 捕获落到显式形参的槽位）；`CALL.args` = `[接收者?] + 捕获值`（`:1626-1639`）；`calleeSig` 元数 = **显式形参数**（`:1576` 的 `params+captures` 改为 `params`）；接收者作为第一个形参 ⇒ 函数体里的 `this` 能解析到它（U-JE5） | IR-P |
| 2.19 | 2 | `translate/ExpressionTranslator.java` | `translate`（`:151-157`）、`translate/StatementTranslator.java` 的 `translateVarDecl`（`:270`） | **字面量按声明上下文收窄**：把声明类型（`StatementTranslator.java:275` 已经拿到）传给字面量求值 ⇒ `byte`/`short`/`char` 声明下的整数字面量类型收窄为 `BYTE`/`SHORT`/`CHAR`、值域按 IR-D 收窄；越界的编译期常量在翻译期报编译错误、**不发指令**（U-JE8） | IR-D |
| 3.1 | 3 控制流 | `translate/StatementTranslator.java` | `translateIf`（`:336`） | 条件是带模式 `instanceof` 时只翻译判定，绑定交给 then 分支 | 现有 IR 即可 |
| 3.2 | 3 | `translate/StatementTranslator.java` | `translateSwitch`（`:843`） | 选择链按**标签**串接（改 `:891`）；`default` 变成最后一条测试的假目标（改 `:859-863`）；体序列保持源顺序（fall-through 不变） | IR-C + IR-I |
| 3.3 | 3 | `translate/JavaModernFeatures.java` | `translateSwitchExpr`（`:135`） | `default` 位置改（`:157-162`）；每个 arm 结束后补 `JUMP endLabel`（`:203-215`） | IR-C + IR-I |
| 3.4 | 3 | `translate/ExpressionTranslator.java` | `yieldTarget`（`:90`） | 扩成 `(结果 temp, end 标签)`；`yield` 补 `JUMP end` | 现有 IR 即可（前端内部状态） |
| 3.5 | 3 | `translate/StatementTranslator.java` | `translateTry`（`:645`） | 实现 §5.4 待决出口协议：try 体后、每个 catch 后补 `JUMP L_FINALLY`；`return`/`throw`/`break`/`continue` 走待决出口；`TryRegion` 补 `catchTypes`/`resourceVars`/`finallyExits` | IR-F + IR-G |
| 3.6 | 3 | `translate/StatementTranslator.java` | `translateResources`（`:585`） | 裸名资源加入 `resources`（改 `:588-591`）⇒ `close()` 会发；close 序列并入隐式 finally | IR-F |
| 3.7 | 3 | `translate/StatementTranslator.java` | `translateForEach`（`:441`） | callee 名换真名（`:451`/`:466`/`:482`）；数组走索引循环 + `FIELDLOAD arr.length` | IR-P |
| 3.8 | 3 | `translate/StatementTranslator.java` | `translateLabeled`（`:938`）+`translateBreak`/`translateContinue` | 引入**标签栈**（与 `loopCtx`/`switchEndLabel` 同构，`:70`/`:80`）；`break L;`/`continue L;` 跳到标签的对应目标 | IR-G |
| 3.9 | 3 | `translate/StatementTranslator.java` | `translateSynchronized`（`:944`） | 临界区标签区间 + `TacCriticalSection` | IR-M |
| 3.10 | 3 | `translate/StatementTranslator.java` | `translateAssert`（`:950`） | 改成 §5.10 的条件抛出形态（消息只在失败路径求值） | 现有 IR 即可 |
| 3.11 | 3 | `ir/TacCfgBuilder.java` | `buildExceptionSuccessors`（`:281`） | try 体内块的异常边**也要**接 finally 的异常入口（现在只接 catch，`:301-314`）；catch 体 `THROW` 接 finally（已有，`:316-323`）；`finallyExits` 驱动建边 | IR-F |
| 3.12 | 3 控制流 | `translate/StatementTranslator.java` | `translateSwitch`（`:843`） | 读 `SwitchEntry.getGuard()` ⇒ **模式守卫**（§5.3.1）：在类型/常量测试**之后**追加一条守卫测试（`BRANCH` 带 IR-C 谓词），守卫不成立 ⇒ 落到**下一个 case 的测试**（不是 `default`）；守卫表达式本身照常翻译 | IR-C + IR-I |
| 3.13 | 3 | `translate/JavaModernFeatures.java` | `armCondition`（`:268`） | 同 3.12：`switch` **表达式**侧的 `SwitchEntry.getGuard()` 也要读（守卫不成立 ⇒ 落到下一个 arm） | IR-C + IR-I |
| 4.1 | 4 声明/类型 | `translate/TacTranslator.java` | `emitFieldInit`（`:832`） | 静态字段初始化补 `FIELDSTORE`（receiver=类类型操作数 + `ownerType`） | IR-E |
| 4.2 | 4 | `translate/TacTranslator.java` | `emitInstanceFieldInit`（`:774`）、`translateAnonymousMembers`（`:1057`） | `FIELDSTORE` 补 `ownerType`；匿名类字段写回**补 receiver**（`:1099-1104` 当前无 `lhs1`） | IR-E |
| 4.3 | 4 | `translate/TacTranslator.java` | `synthesizeEnumMembers`（`:1479`） | 常量函数体 `NEW <枚举>`；`values()` = `NEWARRAY`+n 条 `ARRAYSTORE`+`RETURN`；`valueOf(String)` = 比较链 + `THROW IllegalArgumentException` | 现有 IR 即可 |
| 4.4 | 4 | `translate/TacTranslator.java` | `synthesizeRecordMembers`（`:1581`） | 合成 `equals`/`hashCode`/`toString`（§3） | 现有 IR 即可 |
| 4.5 | 4 | `translate/TacTranslator.java` | `translateTypeWithPrefix`（`:337`） | 产出 `TacTypeDecl`（§3 末段）；`superType`/`interfaces` 改存 **FQN**（`:422-445` 现为短名） | IR-E + IR-N |
| 4.6 | 4 | `translate/TacTranslator.java` | `translateTypeWithPrefix` | 读 `permits` ⇒ `permittedSubtypes`；读类型参数 ⇒ `typeParameters` | IR-N |
| 4.7 | 4 | `translate/TypeMapper.java` | `map`（`:132`） | 泛型实参保留（现在只有 `mapPreservingTypeArgs` 保留，`:58-81`）；通配符 ⇒ `bound`（`:216-218`）；类型变量保留完整上界（`:200-215`）；`var` ⇒ 推断链 | IR-K |
| 4.8 | 4 | `translate/TypeMapper.java` | `map` | **不要**调 `nonNull()`（Java 路径现在也没调，保持） | 现有 IR 即可 |
| 4.9 | 4 | `translate/TacTranslator.java` | 方法声明构建 | 读 `getThrownExceptions()` ⇒ `TacFunction.throwsTypes`（今天**全仓零命中**，`throws` 子句完全消失） | **IR-AL** |
| 4.10 | 4 | `translate/StatementTranslator.java` | 局部变量声明 | 读声明上的注解 ⇒ `TacVar.annotations`（今天 `TacVar` 只有 `name`/`type`/`isTemp`，`TacOperand.java:40-42`） | **IR-AL** |
| 4.11 | 4 | `translate/TacTranslator.java` | `emitInstanceFieldInit`（`:774`）、`translateCallable`（`:901`） | 实例字段初始化器**在每个构造器体之前**发出（今天整段被 `hasExplicitCtor` 门控，`:539-547` ⇒ 类里有显式构造器时**全部**实例字段初始化器消失）；顺序 = `super()` 之后、构造器体之前（U-JS1） | IR-E |
| 4.12 | 4 | `translate/TacTranslator.java` | `synthesizeEnumMembers`（`:1479`） | 枚举常量函数体 = `NEW <枚举类型> args=[<构造器实参>]`（今天只有 `NOP`，`:1504`，且**从不读** `ec.getArguments()` ⇒ `PLUS("+")` 的实参不进 TAC）（U-JS4） | 现有 IR 即可 |
| 4.13 | 4 | `translate/TacTranslator.java` | `translateTypeWithPrefix`（`:337`）、`translateAnonymousMembers`（`:1057`） | 静态初始化块与静态字段初始化器**区分名**：`<clinit>-block` / `<clinit>-fields`（今天两处都是 `<clinit>`，`:504-511` 与 `:711`；匿名类侧同款，`:1075`）⇒ 函数表不再「后写覆盖先写」（U-JS2） | IR-E |
| 4.14 | 4 | `translate/TacTranslator.java` | 字段声明处理（`:476-503`） | 读 `isFinal`/`isVolatile`/`isTransient` ⇒ `TacMemberRef.modifiers`；`static final` + 常量初值 ⇒ 登记进**按 FQN 索引的常量表**（**前端内部**表，扩展既有 `staticFieldsByClass`，`:187-201`）⇒ 读取点折叠成 `Const`、不发 `FIELDLOAD`（U-JS5；这是 §4「`static final` 常量折叠」行的前提） | IR-E（细化）+ IR-D |
| 4.15 | 4 | `translate/StatementTranslator.java` | `translateVarDecl`（`:270`） | 读 `declarator.isFinal()` ⇒ `TacVar.isFinal`（`TacVar` 只有 `name`/`type`/`isTemp`，`TacOperand.java:40-42`）（U-JS5） | IR-AL（细化） |
| 4.16 | 4 | `translate/TacTranslator.java` | 方法声明构建（与 §9 4.14 的字段修饰符读点**同一个读点**） | 读 `isStrictfp`/`isNative`/`isAbstract`/`isSynchronized`/`isDefault` ⇒ `TacMemberRef.modifiers`（**IR-E 的补充细化**，与 4.14 共用同一个通用列表，**不占新编号**）；`native` 方法**不产出 `TacFunction`**、调用点按外部函数处理并记 `drop(SEMANTICS, "native.method")`；`synchronized` 方法体按 §5.8 的锁语义翻译（U-JS9；今天这组修饰符**全仓零读点**） | IR-E（细化） |
| 5.1 | 5 精度 | `translate/TacBuilder.java` | `build`（`:417`） | 精度改由 `TacFunction.derivePrecision(drops)`；Java 路径不再调 `setIrPrecision`（`:186-188`） | IR-A/B |
| 5.2 | 5 | `translate/TacBuilder.java` | `reportUnhandled`（`:224`） | 保留（输入坏了/翻译失败），**不再**承担「构念没降级」的职责 | IR-B |
| 5.3 | 5 | `translate/TacBuilder.java` | 新增 `reportDrop(...)` | 写 `TacDrop`（construct/detail/line/column/impact/scope） | IR-B |

---

## 10. 验收口径

**门禁 A 声明面完备性**：每个前端维护一张**声明种类清单**（Java 清单 = §3 的表行）。每个 Java 声明种类 ⇒ **要么**有对应的 TAC 产物（断言具体形状），**要么**有一条 `TacDrop`。断言位置：`plumb-tac/src/test/java/org/plumb/tac/translate/JavaConstructIrContractTest.java`（已有「valid file 必须零 problem」的断言，`:469`）。

**门禁 B 分支与异常的结构断言**：B1 每个分支区域可被 `TacBranchRegion` 描述（switch 的 case 列表与体标签一一对应）· B2 每个 case 的守卫非空（default 除外）· B3 `finally` 的每个出口都有边（`TryRegion.finallyExits` 列出的每个出口在 CFG 上都有边指向 finally）· B4 短路右侧只出现在真分支之后（`&&` 的右侧所在块**不可**从条件块的假边到达）· B5 switch 的每个标签都被测到（`case 1,2 ->` ⇒ CFG 上存在 `disc==1` 与 `disc==2` **两条**比较）· B6 `default` 不短路后续测试（`default` 不在最后时其后 case 的比较仍在选择链上可达）· B7 switch 表达式的 arm 互斥（arm 的体块**互不可达**；今天不是：所有 arm 依次执行）· B8 带标签跳转目标正确（`break outer;` 的 `JUMP` 目标 == 外层循环的 end 标签）· **B9 模式守卫**：每个带 `when` 的 arm 在 CFG 上有**两条**判定（标签测试 + 守卫测试），且守卫失败的边指向**下一个 case 的测试**（不是 `default`）；守卫表达式本身必须出现在 TAC 里（不是只出现在元数据里）· **B10 lambda 元数分离**：每个 lambda 的 `calleeSig` 元数 == 合成函数形参表里**显式形参**的个数；捕获值在创建点 `CALL.args` 里的位置与形参表的**捕获段**逐位对齐（捕获不再落到显式形参的槽位上）。

**门禁 C 求解器可判定性**：每条路径上的守卫集合可被渲染成 solver DSL；渲染出的谓词数 **≥ 1**；统计「自动推出的守卫数」从 0 变成正数是验收线（`02` §门禁 C）。

**必须钉死的具体行为**：

| Java 源码 | 期望 TAC 形状 |
|---|---|
| `boolean b = a && c();` | `c()` 所在块**只能**从 `a` 为真的边到达；`BRANCH` 带 `Truthiness` |
| `int x = f(); return g(x);` 在 try 里 | try 体内 `t_exit=1; t_val=x; JUMP L_FINALLY`；finally 尾部 `BRANCH` 分派到 `RETURN t_val` |
| `switch(x){case 1,2: f(); break; default: g();}` | 选择链有**两条** `EQ` 比较；`default` 是最后一条测试的假目标；`f()` 后有 `JUMP L_SW_END` |
| `switch(x){default: g(); case 1: f();}` | `g()` 的体在源顺序位置；`case 1` 的测试仍可达；`g()` 之后 fall-through 到 `f()` |
| `int y = switch(x){case 1 -> 10; default -> 20;};` | 两个 arm 的体**互不可达**；各自以 `JUMP L_SWEXPR_END` 结束 |
| `try (Reader r = open()) { use(r); }` | `close()` 在**正常与异常两条路径**上都可达；`TryRegion.resourceVars` 含 `r` |
| `try (existing) { ... }` | `close()` 被发出（今天没有） |
| `outer: for(..){ for(..){ break outer; } }` | `JUMP` 目标是**外层** for 的 end 标签 |
| `static int C = 5;` | `<clinit>` 里有 `FIELDSTORE`（今天只有 `MOVE`） |
| `enum E{A,B}` + `E.values()` | `NEWARRAY E[2]` + 两条 `ARRAYSTORE` + `RETURN` |
| `enum E{A,B}` + `E.valueOf(s)` | 比较链；不匹配路径有 `THROW IllegalArgumentException` |
| `int z = +x;` | `MOVE`（今天返回空常量且不报问题） |
| `x.f++;` | `FIELDLOAD`→`UNARYOP`→**`FIELDSTORE`**（今天缺写回） |
| `Runnable r = () -> cmd();` | `CALL` 的 `args[0]` 是**非空**的 `this`（今天是空常量） |
| `if (o instanceof String s) { use(s); }` | `s` 的 `MOVE` 在 then 分支**内部** |
| `synchronized (lock) { ... }` | 临界区标签区间 + `TacCriticalSection{monitorExpr=lock}` |
| `int i = 0; i++;` | `UNARYOP` 之后有 `MOVE` 写回 `i`（今天只发一条 `UNARYOP`，目标永不更新） |
| `static int C = 5; static int read(){ return C; }` | 静态上下文里的 `C` 读发 `FIELDLOAD`（receiver=类标识、带 `ownerType`）；今天返回合成 `UNKNOWN` 变量、**不发任何指令** |
| `new ArrayList<String>()` | `calleeSig` = `java.util.ArrayList.<init>/0`（今天 `ArrayList<String>.<init>/0` ⇒ 解析不到 FQN） |
| `Function<String,Integer> f = s -> s.length();` | λ 的 `calleeSig` 元数 = **1**（显式形参数，不含接收者/捕获）；合成函数形参表 = `[接收者] + 捕获 + 显式形参`（**捕获在显式形参之前**）；创建点 `args` = `[this] + 捕获值` |
| `switch (s) { case String x when x.length() > 3 -> use(x); default -> other(); }` | 选择链上有一条**守卫测试**（`BRANCH` 带 IR-C 谓词）；守卫不成立 ⇒ 落到**下一个 case 的测试**（不是 `default`）；守卫表达式 `x.length() > 3` 本身进 TAC |
| `class C { private final String p = "x"; C() { } }` | 构造器体**之前**有 `FIELDSTORE this.p`（今天有显式构造器时实例字段初始化器**全丢**） |
| `enum E { PLUS("+") }` | 常量函数体 = `NEW E args=[Const("+")]`（今天只有 `NOP`） |

**不得回归**：现有 Java 测试全绿（`plumb-tac/src/test/java/org/plumb/tac/translate/` 下 19 个测试文件：`JavaConstructIrContractTest`/`AstNodeInventoryTest`/`JavaSyntaxCoverageTest`/`TryCatchFinallyTranslationTest`/`TryWithResourcesTranslationTest`/`UnhandledNodeReportingTest`/`MethodReferenceArityTest`/`LambdaTranslationTest`/`ThisReceiverTypeTest`/`StaticCallScopeTest` 等）· **合法源码零 problem**（`JavaConstructIrContractTest.java:469` ⇒ 改完后这条**必须仍成立**，Java 侧不应产生任何 `UNHANDLED_NODE`）· 静态字段的类归属不被破坏（`ClassIdentityKeepsThePackageTest.java`）· lambda 名字全局唯一（`LambdaNameUniquenessTest.java`，机制 `TacTranslator.java:1424-1441`）· 方法引用的元数（`MethodReferenceArityTest.java`，机制 `ExpressionTranslator.java:868-894`；2.4 会改 `calleeSig` 元数，该测试需同步更新期望）· `this` 的接收者类型（`ThisReceiverTypeTest.java`，机制 `TacTranslator.java:989-1010`）· 符号解析不被破坏（`TranslationKeepsSymbolResolutionTest.java`，机制 `TacTranslator.java:840-869` 的 `syntheticDeclaration` 克隆）· IR 序列化往返（`plumb-dfa/src/test/java/org/plumb/dfa/integration/IrRoundTripAnalysisTest.java`，新字段必须末尾追加 + 可空）。

**回归门禁**：G-1 声明面 §3 清单 100% 被覆盖（有产物或有 drop）——**含本轮补齐的 4 条声明面条目**（U-JS1 实例字段初始化器、U-JS2 静态初始化块/静态字段初始化器的区分名、U-JS4 枚举常量的构造器实参、U-JS5 字段修饰符与 `final` 局部量）· G-2 短路：`&&`/`\|\|` 的右侧块不可从假边到达 · G-3 finally：`TryRegion.finallyExits` 的每个出口都有边 · G-4 switch：每个标签都有比较、default 不短路、表达式 arm 互斥 · G-5 精度：全部改动落地后**Java 函数应当全部是 `SOURCE_FULL`**，出现 `SOURCE_DEGRADED` ⇒ 失败（除非命中 §12 例外）· G-6 守卫：每条路径至少一条可渲染的守卫。

---

## 11. 风险与未决项

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| K1 | `Instruction` 级改动面大：加 `ownerType`/`labelRef`/`guard` 触及 `Builder` 每个调用点 | 编译错误面大 | 全部为**可空**字段 + 末尾追加（`TacType.java:26-29`）⇒ 不改既有调用点 |
| K2 | 待决出口协议改变指令数：try 体内每个 `return`/`throw` 多 3 条 | 指令数与缓存体积上升 | 只在**含 finally 或资源**的 try 里启用 |
| K3 | switch 选择链改造改变 CFG 形状 ⇒ 路径数变化 ⇒ 既有规则的可达性判定变化 | 既有 finding 可能增减 | 用「不得回归」逐条对拍；分两次提交（先加结构断言，再改 lowering） |
| K4 | `ownerType` 改变字段污点的分桶方式（IR-E 的消费方 `TaintedCheckerBase` 的 per-class map） | 字段类 finding 可能变化 | `ownerType == null` 时**保持旧行为**（IR-E 已定） |
| K5 | switch 选择链在 case 很多时是 O(n) 指令 | 大 switch 指令数膨胀 | 先用线性链（可判定优先），必要时用 `Membership` + 引擎侧查表 |
| K6 | `var`/无法解析的符号：被扫描的源码类不在 classpath 上时 `resolve()` 抛异常（`ExpressionTranslator.java:466-471` 已示范该失败模式） | `var` 局部量类型为 `UNKNOWN` | 退化为 `VALUE` 级 drop（**不是** `SEMANTICS`：运行时语义没丢）→ Q1 |
| K7 | 记录默认 `toString` 的数组组件打印 JVM 相关的引用形式 | 无法静态确定 | §12 的 `E6` （`B` 档：记 `drop(VALUE, "record.toString.arrayComponent")`） |
| K8 | 外部类型的继承图不完整 ⇒ 接口 `default` 方法冲突消解可能欠定 | 调用解析可能落到抽象方法 | → Q2 |
| K9 | `&&`/`\|\|` 展开成分支链后指令流变长 | 引擎适配 | 引擎按 `ins.guard()` 优先、`lhs1` 兜底（IR-C 约定） |
| K10 | switch 表达式每个 arm 补 `JUMP` 后控制流变了 | 既有 finding 可能增减 | 对拍 |
| K11 | 解析器的**文法上限 = Java 21**（`JavaLanguageLevel.java:156-163` 把 `"22"`–`"25"` 一律映射到 `JAVA_21`；`:48-52` 记着 `BLEEDING_EDGE` 与 `JAVA_21` 是同一个枚举常量）⇒ Java 22+ 的语法在**解析阶段**就失败 | 用到 Java 22+ 语法的文件报 `PARSE_PROBLEM`（**解析阶段可见**，不会被误当成「TAC 丢构念」）；但标了 `Java 22` 的文件若语法仍在 21 以内，照常解析 | 本文的覆盖口径改为 **Java 8..21**（§1 基线已写明）；`JavaModernFeatures.java:42` 的 javadoc「Java 8 through Java 25」同步为 Java 21；真正的 22+ 支持 = **JavaParser 依赖升级**（不是前端工作量）→ Q6 |

| # | 未决 | 拍板方 | 建议 |
|---|---|---|---|
| Q1 | `var`/无法解析的符号：降级为 `UNKNOWN`+`VALUE` drop，还是报错？ | 规则作者 | `VALUE` drop（不影响运行时语义），但**必须在覆盖率报告里可见** |
| Q2 | 扫描范围外的类型：`default` 方法冲突、接口层次 | 规则作者 | 保持现状（解析不到 ⇒ 不跨函数传播），但记一条 `SEMANTICS` drop —— 待确认这是否算「语言无法静态表达」 |
| Q3 | 记录的 `equals`/`hashCode`/`toString` 要不要合成？（编译器生成的代码） | 规则作者 | 合成（`memberKind` 标 `synthetic`；`TacFunction.java:190` 的 `synthetic` 已有该语义），否则记录的字段污点在跨函数时断链 |
| Q4 | 枚举常量用 `NEW` 近似（每次取值一个新实例）是否可接受？ | 引擎作者 | 可接受：引擎无堆模型，跨调用身份本就不区分；但**同一函数内**应复用同一 temp |
| Q5 | 类型使用注解（IR-AN）优先级 | 路线图 | 最低优先级（编译期语义，运行时无效果） |
| Q6 | Java 22–25 的支持策略：等 JavaParser 升级（依赖变更），还是把 22+ 语法特性逐条登记为「不可支持项」并在扫描端显式提示？ | 路线图 | 登记为**不可支持项**，并让 `PARSE_PROBLEM` 的消息带上「你的文件用了 Java 22+ 语法，当前解析器上限是 21」；**不静默降级**（`JavaLanguageLevel.java:62-66` 已是这个口径：未识别的级别不落到更低的级别，只记日志） |

---

## 12. 无法完整支持的构念及理由

以下六类是**本次审查判定为「无法完整支持」**的 Java 构念。依据 `02` §0 的判定标准：它们**全部**落在第 4 条（可追责）不成立的位置上，且分别对应 `02` §3.1–§3.4 的四类根因。

**本表有三档 —— 先看「档」列，它决定附录行的分档**：

| 档 | 含义 | 附录行怎么指向它 |
|---|---|---|
| **C** | **真例外**：构念**本体**无法完整降级（被调方不在源码里 / 边由运行期建立 / 编译期产物 / 求解器侧能力） | 附录行指向本表的 `En` ⇒ 查「档」列 ⇒ **C** |
| **B** | **B 级残留**：构念**本体已完整降级**，缺的只是一个**运行期才定的事实** ⇒ 记 `drop` 显式记账 | 附录行指向本表的 `En` ⇒ 查「档」列 ⇒ **B** |
| **已覆盖** | 已有 **IR 提案**给出解法 ⇒ **不是例外**；本表只留条目以便追溯 | 附录行指向**正文** ⇒ 分档判为 **A** |

⇒ **判据**（与 `05` §3 的 **P8** 同一条）：`A` = 构念变成真实 TAC 形状（指令**或** IR 元数据），无语义损失；`B` = 构念**本体**完整降级，缺的**只是一个运行期才定的事实**，且该缺失用 `drop` **显式记账**；`C` = 构念**本体**无法降级，只能给保守形状。
⇒ **B 与 C 的分界**：缺的是「**这次运行里它是否发生 / 何时发生 / 以什么顺序发生 / 是哪个值**」⇒ **B**；缺的是「**一条边**（谁调用它）」「**一个被调方**（不在源码里）」「**编译期产物**」⇒ **C**。
⇒ **本轮（2026-11-17）复核结果**：6 条里 **3 条降为 `B`**（`E1` / `E2` / `E6`）、**3 条仍是真例外**（`E3` / `E4` / `E5`）⇒ **Java 侧的真例外从 6 条降到 3 条**。


| # | 档 | 构念 | 为什么无法静态表达 | 保守建模形状 | 对精度的影响 | drop? |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | B | `synchronized`/`lock` 的**争用顺序** | 临界区的**进入顺序**由 JVM 调度器与硬件决定；源码里没有这个事实（互斥关系本身静态可知，但「A 一定在 B 之前」不是）。**本体已完整降级**（临界区范围、监视器表达式、锁边都在 IR 里）⇒ 缺的只是「谁先进入」这一个**运行期才定的事实** | 临界区**范围**+**监视器**进 IR（IR-M 的 `TacCriticalSection{label, monitorExpr, kind=SYNCHRONIZED}`）；函数体内按**单线程顺序**建模，**不**做交错展开 | 死锁/顺序类规则**不报**（无法判定）；原子性类规则照报（范围已知） | 是（`SEMANTICS` 级，`construct = "sync.contention"`） |
| E2 | B | `assert` 是否生效（`-ea` 开关） | Java 的断言默认**关闭**，是否执行由运行期开关决定；源码文本里没有这个事实。**本体已完整降级**（谓词求值 + 失败路径 + `AssertionError` 都在 IR 里）⇒ 缺的只是「断言是否**生效**」这一个**运行期才定的事实**（`-ea` 开关） | 按「断言**生效**」建模（§5.10）：谓词求值 + 条件抛 `AssertionError`（**过近似**，多一条可能不存在的路径） | 断言失败路径上的检查器可能**多报**（方向上安全） | 是（`SEMANTICS` 级，`construct = "assert.disabled"`） |
| E3 | C | 反射/动态代理/`Method.invoke` 的**目标与返回值** | 被调方法、参数、返回值都取决于运行期对象状态与注册表；源码里没有这个事实 | `CALL` + `UNINTERPRETED`（`TacInstruction.java:156`）；结果按**未知**处理（既不当干净、也不当污点源） | 依赖该结果的规则**不出结论**（而非出错结论） | 否（与 `02` §3.1 全局条目同源） |
| E4 | C | IEEE-754 浮点的**精确舍入** | 不是前端问题，是**求解器侧**：bridge 把所有数建成无界 Int（`01` §IR-D 引用的 `Z3Bridge.java:266-278`），浮点与实数统一成 Int 近似 | `TacType.domain = Floating(24,8)`/`(53,11)` **声明形状**；bridge 侧要接 Z3 的 FP 理论才可能精确 | 浮点相关的边界推理**不可用**（今天已不可用） | 否（与 `02` §3.3 同源） |
| E5 | C | `finalize()` 的**调用时机** | 由 JVM 在对象不可达时调度，时机不确定；源码里没有这个事实（且该方法已废弃） | `finalize()` 的**方法体**照常降级为普通函数（用户写的代码，可以有漏洞）；**不**为它的调用点建模 | 依赖「`finalize` 会被调用」的规则不成立（少一条可达路径 ⇒ 可能漏报） | 否（**调用边由 JVM 建立** ⇒ 缺的是一条**边**，不是「时机」这一个事实；`02` §3.2 同类） |
| E6 | B | 记录默认 `toString()` 在**数组组件**上的输出 | Java 规范规定数组的默认 `toString` 打印**引用形式**（形如 `[I@1b6d3586`），哈希部分由 JVM 实现决定 ⇒ **不是源码可确定的常量**。**本体已完整降级**（非数组组件按 JLS 8.10.3 完整拼出）⇒ 缺的只是数组组件的**这一个值** | 合成**只覆盖非数组组件**；数组组件位置用类型正确的占位承载，并记一条 `VALUE` 级 drop（**只影响这一个值**） | 「记录被拼进日志/响应」这类规则的**字符串内容**不可判定；其它组件与字段污点不受影响（数组组件的**类型**仍精确） | 是（`VALUE` 级，`construct = "record.toString.arrayComponent"`） |

**结论**：六条里 **3 条已降为 `B`**（`E1` / `E2` / `E6`：本体已完整降级，缺的只是一个**运行期才定的事实**，并用 `drop` 显式记账）；剩下 **3 条仍是真例外** —— `E3`（被调方不在源码里）、`E4`（求解器侧能力）、`E5`（**调用边由 JVM 建立** ⇒ 缺的是一条**边**）。**没有一条是「TAC 表达不了」**。⇒ **§9 的改动全部落地后，Java 侧的 drop 只剩 3 条**：`E1` / `E2` 是 `SEMANTICS` 级（所在函数 `SOURCE_DEGRADED`）、`E6` 是 `VALUE` 级（所在函数仍 `SOURCE_FULL`）；其余函数都应是 `SOURCE_FULL`（门禁 G-5）。与 `02` §3.5 的全局结论一致：**IR v2 足以支持 Java 的全部语言特性**；剩下的例外是运行时事实与求解器边界，不是表达力缺口。本次审查**未发现**其它无法完整支持的 Java 构念：§3/§4/§5 的每一行都给出了完整 TAC 形态，并逐条标注「现有 IR 即可」或所需 IR 编号（§8）。

---

## 附录 A. Java 表达式审计台账 —— 审计 A 节**逐行**去向（105 行）

**审计来源**：`D:\work\tmp\audit\04-java-expression.md` 的 `## A. 覆盖台账` 节（`### A1.`–`### A8.` 全部子节，共 105 行）。

**覆盖口径**：审计 A 节的**每一行**都在下表里，**105 行 ⇒ 105 条，一行不缺**。其中审计自己标为 `N/A` 的 8 行（A2 的 `sealed`/`permits`、record、enum、内部类/局部类、接口、注解；A8 的增强 for、带标签的表达式）**也逐行给了落点** —— 审计标 `N/A` 的含义是「**这个构念本体不在表达式面**」，不是「不需要覆盖」；本附录按**跨面引用**处理：去向写「不适用（审计已判 N/A：…）」并指向**附录 B** 里该构念的完整落点。审计的官方 `checked` 口径会把这 8 行排除（`checked = 105 − 13 = 92`），本附录的口径是**全量 105 行**；两套口径的差值逐条见 `12-语法覆盖情况.md` §1。`KEPT` 的行**保留状态**，去向写「保持现状（无需改动）」并指出形状在方案的哪一行登记。

**「审计状态」列**沿用审计的词（`KEPT` / `DEGRADED` / `DROPPED-SILENT` / `DROPPED-NOTED`），不新造缩写。

**「#」列的编号口径**：`表达式 #N` = 审计 **B 节**（丢弃清单）的原编号 —— **原附录已有**，去向信息更详细，予以保留；`A<n>-<k>` = 审计 **A 节** `A<n>` 子节里**本轮补入**的行 —— **原附录未覆盖**。本轮补入的 `N/A` 行按**审计 A 节里的行序**编号（`A2-11`…`A2-16` = A2 子节的第 11–16 行；`A8-05` / `A8-09` = A8 子节的第 5 / 9 行）⇒ 编号即行序，可与审计 A 节逐行对齐。

**子小节**照审计 A 节的子小节组织（`### A1. 字面量` … `### A8. 其它表达式路径`），便于逐节核对。

**本附录的行号口径**：只引用本文档的章节号与表行标识，不引用审计报告的行号（审计的行号会漂；本文档的行号以当前文件为准）。
### A1. 字面量

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A1-01 | 布尔字面量 `true`/`false` | KEPT | `plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java:149-150` | 保持现状（无需改动）—— §4「`boolean`/`String`/文本块」行 |
| A1-02 | int 字面量（十进制） | KEPT | `ExpressionTranslator.java:151-163` | 保持现状（无需改动）—— §4「`int`/`long` 字面量」行 |
| A1-03 | int 字面量十六进制 `0x` | KEPT | `ExpressionTranslator.java:1909-1911` | 保持现状（无需改动）—— §4「`int`/`long` 字面量」行 |
| A1-04 | int 字面量二进制 `0b` | KEPT | `ExpressionTranslator.java:1913-1915` | 保持现状（无需改动）—— §4「`int`/`long` 字面量」行 |
| A1-05 | int 字面量八进制 `010` | KEPT | `ExpressionTranslator.java:1920-1924` | 保持现状（无需改动）—— §4「`int`/`long` 字面量」行 |
| A1-06 | 数字分隔符 `1_000` | KEPT | `ExpressionTranslator.java:1867` | 保持现状（无需改动）—— §4「`int`/`long` 字面量」行 |
| A1-07 | long 字面量 `10L` | KEPT | `ExpressionTranslator.java:164-168` | 保持现状（无需改动）—— §4「`int`/`long` 字面量」行 |
| A1-08 | float 字面量 `1.5f` | KEPT | `ExpressionTranslator.java:174-178` | 保持现状（无需改动）—— §4「`char`/`float`/`double`」行 |
| A1-09 | double 字面量（`1.0d`、十六进制浮点 `0x1.8p1`） | KEPT | `ExpressionTranslator.java:179-181` | 保持现状（无需改动）—— §4「`char`/`float`/`double`」行 |
| A1-10 | char 字面量，无转义 `'A'` | KEPT | `ExpressionTranslator.java:169-171` | 保持现状（无需改动）—— §4「`char`/`float`/`double`」行 |
| 表达式 #13 | char 字面量，带转义 `'\n'` `'\u0041'` | DEGRADED | `ExpressionTranslator.java:170` | §4 表达式面表「**带转义的字符串 / char 字面量**」行（先**解码转义**再落 `Const`）+ §9 改动清单 2.11 |
| A1-11 | String 字面量，无转义 | KEPT | `ExpressionTranslator.java:182-184` | 保持现状（无需改动）—— §4「`boolean`/`String`/文本块」行 |
| 表达式 #13 | String 字面量，带转义 `"a\tb\u0041"` | DEGRADED | `ExpressionTranslator.java:183` | §4 表达式面表「**带转义的字符串 / char 字面量**」行（先**解码转义**再落 `Const`）+ §9 改动清单 2.11 |
| A1-12 | 文本块 `"""` | KEPT | `ExpressionTranslator.java:185-194` | 保持现状（无需改动）—— §4「`boolean`/`String`/文本块」行 |
| A1-13 | null 字面量 | KEPT | `ExpressionTranslator.java:212-214` | 保持现状（无需改动）—— §4「`null`」行（`Const.ofNull`，**值级**类型 `NULL`） |

### A2. 类型

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A2-01 | byte/short 字面量窄化（`byte b = 1`） | DEGRADED | `ExpressionTranslator.java:151-157`；`plumb-tac/src/main/java/org/plumb/tac/translate/StatementTranslator.java:275` | §4 表达式面表「**字面量按声明上下文的窄化**（`byte b = 1` / `short s = 2` / `char c = 65`）」行（声明上下文可用 ⇒ 常量类型收窄为 `BYTE`/`SHORT`/`CHAR`、值域按 IR-D 收窄；越界的编译期常量在翻译期报编译错误、**不发指令**） + §9 改动清单「2.19」条（把声明类型传给字面量求值） |
| A2-02 | 类字面量 `Foo.class` | DEGRADED | `ExpressionTranslator.java:195-208` | §4 表达式面表「类字面量 `Foo.class`」行（`Const.ofString("Foo")`，类型 `CLASS`）+ §6 类型面方案「类的类型」行（`CLASS(fqn)`） |
| 表达式 #24 | `var` | DEGRADED | `plumb-tac/src/main/java/org/plumb/tac/translate/TypeMapper.java:216-218`；`StatementTranslator.java:275` | §6 类型面方案「`var`」行（推断链 ①②③；推不出 ⇒ `UNKNOWN` + `VALUE` 级 drop）+ §9 改动清单 4.7 + §11 K6 / Q1 |
| 表达式 #25 | 泛型 / 类型实参 | DEGRADED | `TypeMapper.java:154-157` | §6 类型面方案「**泛型实参**」行（所有位置保留实参：`CLASS("java.util.List")`+`typeArguments=[STRING]`）+ §9 改动清单 4.7 + §8 IR 需求 #10 |
| 表达式 #23 | 通配符 `? extends Foo` | DEGRADED | `TypeMapper.java:216-218` | §6 类型面方案「**通配符 `?`/`? extends T`/`? super T`**」行（`?`/`? super T` ⇒ `CLASS("java.lang.Object")`；`? extends T` ⇒ `bound=T`）+ §9 改动清单 4.7 + §8 IR 需求 #10 |
| 表达式 #23 | 类型上界 `T extends A & B` | DEGRADED | `TypeMapper.java:183-199` | §6 类型面方案「**类型变量 `T`**」行（**完整上界集合**进 `TacTypeParam.bounds`）+ §6「**交集 `A & B`**」行（交集只作类型参数上界 ⇒ 由 IR-N 的 `typeParameters[].bounds` 承载）+ §9 改动清单 4.7 + §8 IR 需求 #10 |
| 表达式 #23 | 类型变量 `T` | DEGRADED | `TypeMapper.java:200-215` | §6 类型面方案「**类型变量 `T`**」行（`bound`=第一个上界；无界 ⇒ `java.lang.Object`）+ §9 改动清单 4.7 + §8 IR 需求 #10 |
| 表达式 #25 | 裸类型 | DEGRADED | `TypeMapper.java:154-157` | §6 类型面方案「**泛型实参**」行（裸类型 ⇒ `typeArguments=null`，**不擦除成短名**）+ §9 改动清单 4.7 + §8 IR 需求 #10 |
| 表达式 #23 | 联合类型 `int \| long` | DEGRADED | `TypeMapper.java:161-182` | §6 类型面方案「**联合类型**」行（**Java 没有联合类型** ⇒ 该分支对 Java 是**死代码**；假类名问题在别的语言解决）+ §9 改动清单 4.7 |
| 表达式 #23 | 交叉类型 | DEGRADED | `TypeMapper.java:183-199` | §6 类型面方案「**交集 `A & B`**」行（Java 的交集只作**类型参数上界** ⇒ 由 IR-N 的 `typeParameters[].bounds` 承载）+ §9 改动清单 4.7 + §8 IR 需求 #10 |
| A2-11 | `sealed` / `permits` | N/A（声明面） | — | 不适用（审计已判 N/A：**本行是跨面引用**）—— 构念本体在**附录 B**「语句 #14 sealed / non-sealed / `permits`」行有完整落点（`TacTypeDecl.permittedSubtypes` + `isSealed`） |
| A2-12 | record | N/A（声明面） | `TacTranslator.java:1639-1700` | 不适用（审计已判 N/A：**本行是跨面引用**）—— 构念本体在**附录 B**「S-25 record declaration」行有完整落点（规范构造器 / 访问器 / `equals`/`hashCode`/`toString`） |
| A2-13 | enum | N/A（声明面） | — | 不适用（审计已判 N/A：**本行是跨面引用**）—— 构念本体在**附录 B**「S-24 enum declaration」行有完整落点；enum 常量的**读取**属表达式面 ⇒ 附录 A 的 A5 小节「**静态上下文中读取静态字段**」行（`表达式 #4`） |
| A2-14 | 内部类 / 局部类 | N/A（声明面） | — | 不适用（审计已判 N/A：**本行是跨面引用**）—— 构念本体在**附录 B**「S-26 nested types」行与「S-27 local types」行有完整落点 |
| A2-15 | 接口（default / static / private） | N/A（声明面） | — | 不适用（审计已判 N/A：**本行是跨面引用**）—— 构念本体在**附录 B**「S-23 interface declaration」行有完整落点 |
| A2-16 | 注解 | N/A（声明面） | `TacTranslator.java:1206-1395` | 不适用（审计已判 N/A：**本行是跨面引用**）—— 构念本体在**附录 B**「S-29 annotations on types」/「S-30 annotations on methods」/「S-31 annotations on fields」/「S-32 annotations on parameters」行有完整落点 |

### A3. 算子

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 表达式 #3 | 一元 `+` | **DROPPED-SILENT** | `ExpressionTranslator.java:343,357-359`；`TacTranslator.java:1876-1883` | §4 表达式面表「**一元 `+`**」行（目标形态 `MOVE t=x`（恒等，无溢出无副作用））+ §9 改动清单 0.2（补 `PLUS`） |
| A3-01 | 一元 `-` | KEPT | `TacTranslator.java:1878` | 保持现状（无需改动）—— §4「一元 `-`/`!`/`~`」行 |
| A3-02 | 一元 `!` | KEPT | `TacTranslator.java:1879` | 保持现状（无需改动）—— §4「一元 `-`/`!`/`~`」行 |
| A3-03 | 一元 `~` | KEPT | `TacTranslator.java:1880` | 保持现状（无需改动）—— §4「一元 `-`/`!`/`~`」行 |
| A3-04 | 前缀 `++`/`--`（取值形式） | KEPT | `TacTranslator.java:1860-1866`；`ExpressionTranslator.java:343-355` | 保持现状（无需改动）—— §4「`++`/`--`」行的**取值**侧（`UNARYOP`） |
| 表达式 #1 | 前/后缀 `++`/`--`（**对目标变量的写回**） | **DROPPED-SILENT** | `ExpressionTranslator.java:338-360`（无写回；对比 `ExpressionTranslator.java:368-382`，那里调用了 `writeLhs`） | §4 表达式面表「`++`/`--`」行（**读-算-写三段**：`MOVE`/`FIELDLOAD`/`ARRAYLOAD` → `UNARYOP` → **`writeLhs` 写回**）+ §9 改动清单 2.2（范围从「字段目标」放宽到「**所有赋值目标**」）+ §10「必须钉死的具体行为」的 `int i = 0; i++;` 行。**附录 C 的 U-JE1 已补齐** |
| A3-05 | 二元 `+ - * / %` | KEPT | `TacTranslator.java:1821-1825` | 保持现状（无需改动）—— §4「二元算术/位/比较」行 |
| A3-06 | 位运算 `& \| ^` | KEPT | `TacTranslator.java:1826-1828` | 保持现状（无需改动）—— §4「二元算术/位/比较」行 |
| A3-07 | 移位 `<< >> >>>` | KEPT | `TacTranslator.java:1829-1831` | 保持现状（无需改动）—— §4「二元算术/位/比较」行 |
| A3-08 | 关系运算 `< <= > >=` | KEPT | `TacTranslator.java:1834-1837` | 保持现状（无需改动）—— §4「二元算术/位/比较」行 |
| A3-09 | 相等 `== !=` | KEPT | `TacTranslator.java:1832-1833` | 保持现状（无需改动）—— §4「二元算术/位/比较」行 |
| 表达式 #8 | `&&` 短路求值 | DEGRADED | `TacTranslator.java:1838`；`ExpressionTranslator.java:322-336`；契约 `docs/tech/TAC.md:187` | §5.1 短路 `&&`/`\|\|`（分支链伪码 `BRANCH t_a -> L_AND_TRUE, L_AND_EVAL_B`）+ §4 表达式面表「**`&&`/`\|\|`**」行 + §9 改动清单 2.1 + §10 回归门禁 G-2 |
| 表达式 #8 | `\|\|` 短路求值 | DEGRADED | `TacTranslator.java:1839`；`ExpressionTranslator.java:322-336` | §5.1 短路 `&&`/`\|\|`（`\|\|` 同形，两个 `BRANCH` 的真/假目标对调）+ §4 表达式面表「**`&&`/`\|\|`**」行 + §9 改动清单 2.1 + §10 回归门禁 G-2 |
| A3-10 | 简单赋值 `=` | KEPT | `ExpressionTranslator.java:384-386` | 保持现状（无需改动）—— §4「简单赋值」行（`writeLhs`：`MOVE`/`FIELDSTORE`/`ARRAYSTORE` 三选一） |
| A3-11 | 复合赋值 `+= -= *= /= %= &= \|= ^= <<= >>= >>>=` | KEPT | `ExpressionTranslator.java:368-382`；`TacTranslator.java:1843-1858` | 保持现状（无需改动）—— §4「复合赋值 `x += y`」行（读-算-写三段） |
| A3-12 | String 上的复合赋值（`s += x`） | DEGRADED | `ExpressionTranslator.java:373-379` | §4 表达式面表「复合赋值 `x += y`」行（读-算-写三段；**类型列取目标类型** ⇒ `String +=` 的结果不再是 `UNKNOWN`）+ §6 类型面方案「`String`/数组」行 |
| A3-13 | 三元 `?:` | DEGRADED | `ExpressionTranslator.java:1495-1537` | §4 表达式面表「条件表达式 `c?a:b`」行（分支链 + `MOVE` 到同一 temp；**结果类型取两侧公共类型**）+ §9 改动清单 2.6 |
| A3-14 | 嵌套 `?:` | DEGRADED | `ExpressionTranslator.java:1495-1537` | §4 表达式面表「条件表达式 `c?a:b`」行（递归展开；**结果类型取两侧公共类型**）+ §9 改动清单 2.6 |
| 表达式 #9 | 二元表达式的**结果类型** | DEGRADED | `ExpressionTranslator.java:325`（`newTemp(TacType.UNKNOWN)`） | §4 表达式面表「二元算术/位/比较」行的类型列（「两操作数提升类型（今天恒 `UNKNOWN`，`:325`）」）+ §6 类型面方案「基本类型」行（IR-D 值域）+ §8 IR 需求 #13（数值宽度/溢出） |
| 表达式 #9 | 字符串拼接 `"a" + b` | DEGRADED | `ExpressionTranslator.java:322-336`；污点经 `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java:2203` 存活 | §4 表达式面表「二元算术/位/比较」行的类型列（+ §6「`String`/数组」行 ⇒ 结果类型 `STRING` 而非 `UNKNOWN`）+ §8 IR 需求 #13 |
| 表达式 #9 | char 算术 `c + 1` | DEGRADED | `ExpressionTranslator.java:325` | §4 表达式面表「二元算术/位/比较」行的类型列（+ §6「基本类型」行 ⇒ `CHAR` 性质与值域不再丢）+ §8 IR 需求 #13 |
| 表达式 #9 | 整数溢出语义 | DEGRADED | `ExpressionTranslator.java:325`；`plumb-dfa/src/main/java/org/plumb/dfa/checkers/boundary/UnderflowConstraintChecker.java:83-89` | §4 表达式面表「二元算术/位/比较」行的类型列（+ §8 IR 需求 #13：IR-D `TacValueDomain` ⇒ int32 回绕由 Z3 推，而不是 checker 自维护区间） |
| A3-15 | `instanceof`（普通） | KEPT | `ExpressionTranslator.java:564-576` | 保持现状（无需改动）—— §4「`instanceof`（无模式）」行 |
| A3-16 | `instanceof` 带类型模式 | DEGRADED | `plumb-tac/src/main/java/org/plumb/tac/translate/JavaModernFeatures.java:351-378` | §4 表达式面表「`instanceof`（有模式）」行（判定 `INSTANCEOF` ✓；**绑定 MOVE 移到被守卫的分支内**）+ §5.6 + §9 改动清单 2.7 / 3.1 |
| A3-17 | 强制转换 `(T) e` | KEPT | `ExpressionTranslator.java:549-562` | 保持现状（无需改动）—— §4「强制转换 `(T)x`」行（`UNARYOP CAST`+`castType`） |
| 表达式 #25 | 转到泛型类型 | DEGRADED | `TypeMapper.java:154-157` | §6 类型面方案「**泛型实参**」行（转换目标类型带 `typeArguments` ⇒ 不再擦除）+ §9 改动清单 4.7 + §8 IR 需求 #10 |
| 表达式 #19 | 装箱 / 拆箱 | DEGRADED | `ExpressionTranslator.java:151-157`；`StatementTranslator.java:275` | §4 表达式面表「**装箱 / 拆箱**」行（装箱提升为 `CLASS<Integer>`；拆箱在**已知拆箱方法**上收窄，**不发 `UNINTERPRETED`**）+ §9 改动清单 2.10 + §8 IR 需求 #1（`ownerType`） |
| 表达式 #25 | 对泛型类型做 `instanceof` | DEGRADED | `TypeMapper.java:154-157` | §6 类型面方案「**泛型实参**」行（`INSTANCEOF.ctorType` 带 `typeArguments` ⇒ 不再擦除）+ §9 改动清单 4.7 + §8 IR 需求 #10 |

### A4. 对象创建与数组

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A4-01 | `new T(args)` | KEPT | `ExpressionTranslator.java:1208-1255` | 保持现状（无需改动）—— §4「`new T(...)`」行（`NEW`+`ctorType`+`calleeSig`） |
| 表达式 #12 | `new T(args)` 带泛型类型 | DEGRADED | `ExpressionTranslator.java:1232-1250`；`TypeMapper.java:386` | §4 表达式面表「`new T(...)`」行（**U-JE4 已补齐**：`calleeSig` 用**剥掉类型实参后的 FQN**（`ArrayList.<init>/0`））+ §9 改动清单 2.17 + §8 IR 需求 #14（IR-P 命名规范） |
| 表达式 #12 | 菱形 `new T<>()` | DEGRADED | `ExpressionTranslator.java:1232-1250` | §4 表达式面表「`new T(...)`」行（**U-JE4 已补齐**：类型名取**擦除后的** `getNameAsString()`，菱形同理 ⇒ `calleeSig` 也不是 `ArrayList<>.<init>/0`）+ §9 改动清单 2.17 |
| A4-02 | `new T[n]` | KEPT | `ExpressionTranslator.java:1282-1290` | 保持现状（无需改动）—— §4「数组创建/初始化器」行 |
| A4-03 | `new T[]{…}` | KEPT | `ExpressionTranslator.java:1268-1281` | 保持现状（无需改动）—— §4「数组创建/初始化器」行 |
| 表达式 #18 | 多维 `new T[n][m]` | DEGRADED | `ExpressionTranslator.java:1291-1307` | §4 表达式面表「**多维数组创建**」行（逐层 `NEWARRAY` **并把行数组存进外层**（`ARRAYSTORE outer[i] = row`）；类型逐层 `arrayOf` ⇒ `ARRAY<ARRAY<T>>`）+ §9 改动清单 2.9 |
| 表达式 #17 | 裸数组初始化 `{…}` | DEGRADED | `ExpressionTranslator.java:234-243,1325-1339` | §4 表达式面表「**裸数组初始化器 `{1,2,3}`**」行（组件类型从**声明上下文**取，**不经 `UNKNOWN`**）+ §9 改动清单 2.8 |
| A4-04 | 嵌套数组初始化 `{{…}}` | KEPT | `ExpressionTranslator.java:1380-1383` | 保持现状（无需改动）—— §4「数组创建/初始化器」行 |
| A4-05 | 匿名类体 `new X(){…}` | KEPT | `ExpressionTranslator.java:1219-1221`；`TacTranslator.java:1032-1048` | 保持现状（无需改动）—— §3 声明面表「匿名类」行（体 → 独立函数集） |
| A4-06 | 匿名类的字段 / 初始化块 | KEPT | `TacTranslator.java:1074-1100` | 保持现状（无需改动）—— §3 声明面表「匿名类」行（**字段写入补 receiver**）+ §9 改动清单 4.2 |

### A5. 名字、字段访问与调用

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| A5-01 | 局部量 / 形参读取 | KEPT | `ExpressionTranslator.java:389-397` | 保持现状（无需改动）—— §4「**非限定字段读取（`translateName`）**」行的第 ① 态（局部量/形参 ⇒ 直接用该变量，无指令） |
| A5-02 | 非限定实例字段读取 | KEPT | `ExpressionTranslator.java:398-424` | 保持现状（无需改动）—— §4「**非限定字段读取（`translateName`）**」行的第 ③ 态（`FIELDLOAD t=this.f` + `ownerType`） |
| 表达式 #4 | **静态上下文中读取静态字段** | **DROPPED-SILENT** | `ExpressionTranslator.java:425-428` | §4 表达式面表「**非限定字段读取（`translateName`）**」行的第 ② 态（**U-JE3 已补齐**：静态字段 ⇒ `FIELDLOAD t=recv.f`，receiver=**类标识**、`ownerType`）+ §9 改动清单 2.16 + §3「静态字段（带初始化器）」行（写侧） |
| A5-03 | 限定字段读取 `o.f` | DEGRADED | `ExpressionTranslator.java:1411-1443` | §4 表达式面表「字段访问」行（`FIELDLOAD`+`ownerType`；类型取**字段声明类型**）+ §9 改动清单 2.5 |
| 表达式 #14 | `static final` 常量折叠（`Integer.MAX_VALUE`） | DEGRADED | `ExpressionTranslator.java:1424-1432,1455-1493`；被 `StatementTranslator.java:295-307` 绕过 | §4 表达式面表「**`static final` 常量折叠在声明处被绕过**」行（声明路径**复用同一折叠** ⇒ `Const.ofInt(2147483647)`）+ §9 改动清单 2.13 + §3「**字段修饰符（`static`/`final`/`volatile`/`transient`）与 `final` 局部量**」行（`final` 是编译期常量的**前提** ⇒ **U-JS5 已补齐**：常量表 + `TacMemberRef.modifiers`） |
| A5-04 | `this` | KEPT | `ExpressionTranslator.java:125,542-547` | 保持现状（无需改动）—— §4「`this`」行（局部量 `this`，带声明类类型） |
| 表达式 #16 | `super` | DEGRADED | `ExpressionTranslator.java:126-148` | §4 表达式面表「`super`」行（方法接收者类型改用**父类 FQN**）+ §4「显式构造器调用 `super(...)`/`this(...)`」行（`CALL <父类>.<init>/N`，`args[0]=this`）+ §8 IR 需求 #14 |
| A5-05 | 带显式接收者的方法调用 | KEPT | `ExpressionTranslator.java:578-703` | 保持现状（无需改动）—— §4「**非限定实例方法调用丢掉隐含 `this`**」行的对照面（限定路径本就正确） |
| 表达式 #7 | **非限定方法调用 `foo(x)`** | DEGRADED | `ExpressionTranslator.java:587-619` | §4 表达式面表「**非限定实例方法调用丢掉隐含 `this`**」行（目标形态：非限定**实例**方法调用 ⇒ `args[0] = this`）+ §9 改动清单 2.15 + §8 IR 需求 #14（IR-P 接收者一律 `args[0]`） |
| A5-06 | 静态调用 `String.valueOf(x)` | KEPT | `ExpressionTranslator.java:604-608,999-1030` | 保持现状（无需改动）—— §4「全限定字段访问（幽灵 `FIELDLOAD`）」行的第 ② 态（类字段 ⇒ `ownerType`） |
| 表达式 #15 | 全限定静态调用 `java.lang.String.valueOf(x)` | DEGRADED | `ExpressionTranslator.java:1433-1441,405-424` | §4 表达式面表「**全限定字段访问（幽灵 `FIELDLOAD`）**」行（scope 是**类型/包引用**时：包名前缀 ⇒ **不产生任何指令**）+ §9 改动清单 2.12。**口径校正**：审计这条的「调用路径」部分在当前源码已不成立（`:604-607` 的 `classScopeFqn` 已修），残留的是**字段访问路径** |
| 表达式 #20 | 方法引用 `Type::m` | DEGRADED | `ExpressionTranslator.java:788-841` | §4 表达式面表「方法引用」行（`calleeSig` 用 `referencedArity` + 挂 `Effect.DEFERRED`）+ §9 改动清单 2.4 + §8 IR 需求 #15 |
| 表达式 #20 | 方法引用 `expr::m` | DEGRADED | `ExpressionTranslator.java:796-801` | §4 表达式面表「方法引用」行（`calleeSig` 用 `referencedArity` + 挂 `Effect.DEFERRED`）+ §9 改动清单 2.4 + §8 IR 需求 #15 |
| 表达式 #20 | 方法引用 `Type::new` | DEGRADED | `ExpressionTranslator.java:803-804` | §4 表达式面表「方法引用」行（`calleeSig` 用 `referencedArity` + 挂 `Effect.DEFERRED`）+ §9 改动清单 2.4 + §8 IR 需求 #15 |
| 表达式 #21 | `this::m` / `super::m` | DEGRADED | `ExpressionTranslator.java:796-801,1032-1126` | §4 表达式面表「`super`」行（父类 FQN）+ §8 IR 需求 #14（IR-P 命名规范）+ §3 末段「`TacTypeDecl` 的 Java 来源」（`fqn` ← `qualifiedClassName`，现只存**短名**改存 FQN） |
| 表达式 #11 | 可变参数调用 | DEGRADED | `ExpressionTranslator.java:692-693`；`TacTranslator.java:1843-1858`；解析 `plumb-dfa/src/main/java/org/plumb/dfa/cg/CallGraphBuilder.java:396-444` | §4 表达式面表「**可变参数（varargs）调用的 arity**」行（`calleeSig` 元数用**被调方声明的形参数**；调用点把超出定长部分的实参**装进合成数组**）+ §9 改动清单 2.14 + §8 IR 需求 #14 |
| A5-07 | 重载解析 | DEGRADED | `ExpressionTranslator.java:698,719-727` | §1 非目标 N5「不解决『扫描范围外的类型信息不全』」+ §11 Q2（**工具边界**，不是语言边界）；被调方在 classpath 上时由 IR-P 的 `calleeParamTypes` 承载解析出的形参类型 |

### A6. Lambda

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 表达式 #22 | lambda 表达式 | DEGRADED | `ExpressionTranslator.java:1564-1651` | §4 表达式面表「**lambda**」行（**U-JE5 已补齐**：**元数分离**——`calleeSig` 元数 = 显式形参数；创建点 `CALL.args` = `[接收者?] + 捕获值`；合成函数形参表 = `[接收者?] + 捕获 + 显式形参`）+ §8 IR 需求 #14 + §9 改动清单 2.18 + §10 门禁 B10 |
| A6-01 | lambda 捕获外层局部量 | KEPT | `ExpressionTranslator.java:1671-1710` | 保持现状（无需改动）—— §4「闭包捕获」行（捕获变量成形参并在调用点传值） |
| 表达式 #5 | **lambda 内的 `this`** | **DROPPED-SILENT** | `ExpressionTranslator.java:1627-1630` + `542-547` | §4 表达式面表「**lambda**」行（`args[0]` 传**真实 `this`**（非 static 才传）；接收者同时是合成函数的**第一个形参** ⇒ 函数体里的 `this` 能解析到它）+ §9 改动清单 2.3 |

### A7. 现代 Java（表达式面）

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 表达式 #10 | switch 表达式（箭头形式） | DEGRADED | `JavaModernFeatures.java:135-227`；`ExpressionTranslator.java:225-230` | §5.5 `switch` 表达式（每个 arm 结束**必须** `JUMP` 到 end）+ §9 改动清单 3.3 + §7 精度与丢弃可见性（前端不再自报精度 ⇒ `derivePrecision(drops)`）+ §10 门禁 B7 |
| 表达式 #10 | switch 表达式（冒号形式 / `yield`） | DEGRADED | `JavaModernFeatures.java:135-227`；`StatementTranslator.java:177-191` | §5.5 `switch` 表达式 + §5.11 `yield`（补 `JUMP end`）+ §9 改动清单 3.3 与 3.4 + §10 门禁 B7 |
| A7-01 | `case null` 标签 | KEPT | `JavaModernFeatures.java:315-326` | 保持现状（无需改动）—— §5.3（`case null` = `BINOP EQ` 与空常量比较，`JavaModernFeatures.java:315-326` 已有此形状） |
| 表达式 #10 | 类型模式标签 `case String s` | DEGRADED | `JavaModernFeatures.java:311-313,407-419` | §5.3 `switch` 语句（模式标签条件是 `INSTANCEOF`；绑定在**体内**做）+ §4「`instanceof`（有模式）」行 + §9 改动清单 3.2 |
| A7-02 | record 模式 `case Point(int x, int y)` | DEGRADED | `JavaModernFeatures.java:421-438` | §5.3 `switch` 语句（模式标签条件是 `INSTANCEOF`）+ §3 声明面表「记录访问器」行（各分量 = 访问器 `CALL`）+ §9 改动清单 3.2 |
| 表达式 #2 | **带守卫的模式 `case Integer i when i > 0`** | **DROPPED-SILENT** | `JavaModernFeatures.java:268-341`；`StatementTranslator.java:864-899`；`plumb-tac/src/main/java` 下**不存在** `getGuard` 调用 | **§5.3.1「模式守卫（`case <pattern> when <expr>`）」**（新增小节：标签测试之后**追加一条守卫测试**，守卫失败落到**下一个 case 的测试**；守卫谓词用 IR-C `TacGuard`，多条守卫进 IR-I `TacCase.guards` ⇒ **不需要新字段**）+ §9 改动清单 3.12（语句侧）/ 3.13（表达式侧）+ §10 门禁 B9。**附录 C 的 U-JE2 已补齐** |
| 表达式 #26 | Java 22–25 语言特性 | DROPPED-NOTED | `plumb-parser/src/test/java/org/plumb/parser/JavaLanguageLevelTest.java:98-106`；声明见 `JavaModernFeatures.java:42-45` | **§1 基线「文法上限 = Java 21（U-JE6）」**段（`JavaLanguageLevel.java:156-163` 把 `"22"`–`"25"` 一律映射到 `JAVA_21`）+ §11 K11 / Q6。**口径校正**：只有**真正用到 22+ 语法**的文件才在解析阶段失败。**附录 C 的 U-JE6 已补齐** |

### A8. 其它表达式路径

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 表达式 #6 | 不支持的赋值**目标**（读） | DROPPED-SILENT（潜伏） | `ExpressionTranslator.java:1553-1555` | §4 表达式面表「**不支持的赋值目标**」行（兜底：`HAVOC` + `VALUE` 级 drop）+ §9 改动清单 0.4（`translateLhsRead` 末尾） |
| 表达式 #6 | 不支持的赋值**目标**（写） | DROPPED-SILENT（潜伏） | `ExpressionTranslator.java:1839` | §4 表达式面表「**不支持的赋值目标**」行（兜底：`HAVOC` + `VALUE` 级 drop）+ §9 改动清单 0.3（`writeLhs` 末尾） |
| 表达式 #27 | 通用「未处理表达式」安全网 | DROPPED-NOTED | `ExpressionTranslator.java:294-317`（`reportUnhandled` + `HAVOC`） | §9 改动清单 5.2（`reportUnhandled` 保留（输入坏了/翻译失败），**不再**承担「构念没降级」的职责）+ §9 改动清单 5.3（新增 `reportDrop(...)`）+ §2 P1 / P2 |
| A8-01 | 带模式标签的 `switch` **语句** | DEGRADED（语句面，已记） | `StatementTranslator.java:843-936` | §5.3 `switch` 语句（**选择链与体序列分离**）+ §9 改动清单 3.2 + §10 门禁 B5 / B6 |
| A8-05 | 增强 for | N/A（语句面，已记） | `StatementTranslator.java:133` | 不适用（审计已判 N/A：**本行是跨面引用**）—— 构念本体在**附录 B**「S-12 `for` (enhanced)」行有完整落点（callee 名必须是真名（IR-P）+ 元素类型取可迭代物的元素类型） |
| A8-02 | `assert` | DEGRADED（语句面，已记） | `StatementTranslator.java:950-955` | §5.10 `assert`（`BRANCH` → 失败路径求值消息 + `THROW` `java.lang.AssertionError`）+ §9 改动清单 3.10；「是否生效由运行期开关决定」进 §12 的 `E2` （`B` 档：记 `drop(SEMANTICS, "assert.disabled")`） |
| A8-03 | `synchronized` 块 | DEGRADED（语句面，已记） | `StatementTranslator.java:944-948` | §5.8 `synchronized`（临界区标签区间 `L_MON_BEGIN`..`L_MON_END`）+ §8 IR 需求 #12（IR-M `TacCriticalSection`）+ §9 改动清单 3.9；争用顺序进 §12 的 `E1` （`B` 档：记 `drop(SEMANTICS, "sync.contention")`） |
| A8-04 | `yield` | KEPT（语句面，已记） | `StatementTranslator.java:177-191` | 保持现状（无需改动）—— §5.11 `yield`（今天求值后 `MOVE` 进结果 temp、**无 `JUMP`** ⇒ 与 §5.5 同一缺陷 ⇒ §9 改动清单 3.4） |
| A8-09 | 带标签的表达式 | N/A | — | 不适用（审计已判 N/A：**Java 没有带标签的*表达式***，只有带标签的语句）—— 构念本体在**附录 B**「语句 #7 labelled statement (`outer:`)」行有完整落点（`labelRef{kind=BLOCK}` + 标签栈） |

**附录 A 小计**：**105 条**（= 审计 A 节的**全部 105 行**，一行不缺）= 原附录 **27 条**（编号 `表达式 #1`–`#27`）+ 本轮**新增 78 条**。其中 `KEPT` **40 条**（去向=「保持现状（无需改动）」）、`N/A`（跨面引用）**8 条**、问题项 **57 条**（`DEGRADED` **48** + `DROPPED-*` **9**）⇒ **每个问题项都有落点**（A 完整表达 **51** / B 显式记账 **6** / C 永久例外 **0**）。**`⚠` 未覆盖 = 0**。

---

## 附录 B. Java 语句审计台账 —— 审计 A 节**逐行**去向（98 行）

**审计来源**：`D:\work\tmp\audit\05-java-statement.md` 的 `## A. 覆盖台账` 节（**单表**，共 98 行；审计该节**没有**子小节）。

**覆盖口径**：审计 A 节的**每一行**都在下表里，**98 行 ⇒ 98 条，一行不缺**。其中审计标 `UNCLEAR` 的 1 行（`Outer.this`（限定 this），审计说「不在本审计范围」）**也给了落点**：本方案判定它与 `this` 运行期同形 ⇒ 落点 = §4「`this`」行（不新造 IR）。审计的官方 `checked` 口径会把这 1 行排除（`checked = 98 − 1 = 97`），本附录的口径是**全量 98 行**；两套口径的差值逐条见 `12-语法覆盖情况.md` §1。`KEPT` 的行**保留状态**，去向写「保持现状（无需改动）」并指出形状在方案的哪一行登记。

**「审计状态」列**沿用审计的词（`KEPT` / `DEGRADED` / `DROPPED-SILENT` / `DROPPED-NOTED`），不新造缩写。

**「#」列的编号口径**：`语句 #N` = 审计 **B 节**（丢弃清单）的原编号 —— **原附录已有**，去向信息更详细，予以保留；`S-<k>` = 审计 **A 节**里**原附录未覆盖**的第 k 行 —— 本轮补入。

**本附录的行号口径**：只引用本文档的章节号与表行标识，不引用审计报告的行号（审计的行号会漂；本文档的行号以当前文件为准）。
### A. 覆盖台账（单表，无子节）

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| S-01 | local variable declaration (one declarator) | KEPT | `plumb-tac/src/main/java/org/plumb/tac/translate/StatementTranslator.java:270` | 保持现状（无需改动）—— §9 改动清单 4.10（局部变量声明路径：`MOVE` + `TacVar`） |
| S-02 | local variable declaration (multiple declarators) | KEPT | `StatementTranslator.java:271` | 保持现状（无需改动）—— §9 改动清单 4.10 |
| 语句 #26 | `var` local | DEGRADED | `plumb-tac/src/main/java/org/plumb/tac/translate/TypeMapper.java:216` | §6 类型面方案「`var`」行（推断链 ①②③；推不出 ⇒ `UNKNOWN` + `VALUE` 级 drop）+ §9 改动清单 4.7 + §11 K6 / Q1 |
| 语句 #24 | `final` local | DROPPED-SILENT | `StatementTranslator.java:270-333` | §3 声明面表「**字段修饰符（`static`/`final`/`volatile`/`transient`）与 `final` 局部量**」行（`final` 局部量 ⇒ `TacVar.isFinal`，**IR-AL 的补充细化**）+ §8 IR 需求 #19 + §9 改动清单 4.15。**附录 C 的 U-JS5 已补齐** |
| S-03 | local class | KEPT | `StatementTranslator.java:102` → `plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java:1124` | 保持现状（无需改动）—— §3 声明面表「局部类型」行（名字 `Outer$method$Local`） |
| S-04 | local record | KEPT | `StatementTranslator.java:112` | 保持现状（无需改动）—— §3 声明面表「局部类型」行 |
| S-05 | local enum | DROPPED-NOTED | `StatementTranslator.java:235`（JavaParser 3.26.4 抛 PARSE_PROBLEM） | §1 基线「**文法上限 = Java 21（U-JE6）**」段（JavaParser 3.26.4 对**局部 enum**（Java 12–15 的预览特性，16 起移除）直接抛 `PARSE_PROBLEM`）⇒ 属**文法边界**，不是 TAC 表达力问题；扫描端可见（§9 改动清单 0.6 收集 `programProblems()`） |
| S-06 | local interface | KEPT | `StatementTranslator.java:102` | 保持现状（无需改动）—— §3 声明面表「局部类型」行 |
| S-07 | expression statement | KEPT | `StatementTranslator.java:98`, `:253` | 保持现状（无需改动）—— §9 改动清单 0.3 / 0.4（`writeLhs`/`translateLhsRead` 的兜底） |
| S-08 | `if` / `else` | KEPT | `StatementTranslator.java:336` | 保持现状（无需改动）—— §5.2（`if(c) A else B` 形状已对）+ §5.6（带模式 `instanceof` 的条件要改） |
| S-09 | `while` | KEPT | `StatementTranslator.java:356`（回边 `:374`） | 保持现状（无需改动）—— §5.2（`while(c) B` 形状已对） |
| S-10 | `do`-`while` | KEPT | `StatementTranslator.java:379`（回边 `:387`） | 保持现状（无需改动）—— §5.2（`do B while(c)` 形状已对，continue 目标=条件标签 ✓） |
| S-11 | `for` (basic) | KEPT | `StatementTranslator.java:398`（回边 `:432`） | 保持现状（无需改动）—— §5.2（`for(init;c;upd) B` 形状已对） |
| S-12 | `for` (enhanced) | DEGRADED | `StatementTranslator.java:441-507` | §5.2「增强 for 三处修」①**callee 名必须是真名**（IR-P）+ §9 改动清单 3.7 + §8 IR 需求 #14；元素类型取可迭代物的元素类型（§6「`var`」行的推断链 ③） |
| 语句 #25 | for-each over an **array** | DEGRADED | `StatementTranslator.java:448` | §5.2（增强 for 三处修 ②：**数组不是可迭代对象** ⇒ 走索引循环 + `FIELDLOAD arr.length`）+ §9 改动清单 3.7 + §8 IR 需求 #14 |
| S-13 | `break` (unlabelled) | KEPT | `StatementTranslator.java:545` | 保持现状（无需改动）—— §5.9（无标签的 `break` 取**最内层循环**） |
| 语句 #8 | `break` inside a loop inside a `switch` case | DEGRADED | `StatementTranslator.java:536` | §5.9（标签栈必须记**标签种类**：无标签的 `break` 取**最内层循环**，不得取最内层 `switch`）+ §9 改动清单 3.8 |
| 语句 #7 | `break <label>` | DROPPED-SILENT | `StatementTranslator.java:530` | §5.9 标签语句与带标签的 `break`/`continue`（前端维护**标签栈** `labelName → (break 目标, continue 目标, 标签种类)`）+ §9 改动清单 3.8 + §10 门禁 B8 |
| S-14 | `continue` | KEPT | `StatementTranslator.java:557` | 保持现状（无需改动）—— §5.9 |
| 语句 #7 | `continue <label>` | DROPPED-SILENT | `StatementTranslator.java:557` | §5.9（`continue outer;` ⇒ `JUMP` 到**外层** for 的 `L_FOR_UPDATE`，`labelRef{kind=LOOP_BODY_END}`）+ §9 改动清单 3.8 + §10 门禁 B8 |
| 语句 #7 | labelled statement (`outer:`) | DROPPED-SILENT | `StatementTranslator.java:938-942`（"we don't model labels specially"） | §5.9（标签语句本身 ⇒ `labelRef{kind=BLOCK}`；标签栈）+ §9 改动清单 3.8 |
| S-15 | `return` | KEPT | `StatementTranslator.java:509` | 保持现状（无需改动）—— §5.12（`return` 在 try 内要改走待决出口协议） |
| 语句 #3 | `return` out of a `try` that has a `finally` | DEGRADED | `StatementTranslator.java:509`, `:807` | §5.4 `try`/`catch`/`finally`（待决出口协议 `t_exit` = 0/1/2/3/4；try 体内 `return v` ⇒ `t_val=v; t_exit=1; JUMP L_FINALLY`）+ §9 改动清单 3.5 + §10 门禁 B3 |
| S-16 | `throw` | KEPT | `StatementTranslator.java:521` | 保持现状（无需改动）—— §5.12 + §8 IR 需求 #9（`THROW.ctorType`） |
| 语句 #18 | `synchronized` block | DEGRADED | `StatementTranslator.java:944-948` | §5.8 `synchronized`（临界区标签区间 `L_MON_BEGIN`..`L_MON_END`）+ §8 IR 需求 #12（IR-M `TacCriticalSection`，**函数级** ⇒ 方法级 `synchronized` 落在同一字段）+ §9 改动清单 3.9；争用顺序进 §12 的 `E1` （`B` 档：记 `drop(SEMANTICS, "sync.contention")`） |
| 语句 #18 | `synchronized` method | DEGRADED | `plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java:972` | §5.8 `synchronized`（方法级 `synchronized` 落在**函数级** `TacCriticalSection` 同一字段）+ §8 IR 需求 #12 + §9 改动清单 3.9；争用顺序进 §12 的 `E1` （`B` 档：记 `drop(SEMANTICS, "sync.contention")`） |
| 语句 #6 | `try` / `catch` (single) | DEGRADED | `StatementTranslator.java:730-799` | §5.4（try 体后补 `JUMP L_FINALLY`；每个 catch 后补 `JUMP L_FINALLY`）+ §9 改动清单 3.5 与 3.11 + §10 门禁 B3 |
| 语句 #19 | `try` / `catch` (multi-catch `A \| B`) | DEGRADED | `StatementTranslator.java:738`；`TypeMapper.java:161` | §5.4（`TryRegion` 需填：`catchTypes`（与 `catchLabels` 同序））+ §8 IR 需求 #5（IR-F `catchTypes`）+ §9 改动清单 3.5 |
| 语句 #3 | `try` / `catch` / `finally` | DEGRADED | `StatementTranslator.java:807-823` | §5.4（待决出口协议：try 体后、每个 catch 后补 `JUMP L_FINALLY`；`return`/`throw`/`break`/`continue` 走待决出口；`finally` 尾部出口分派）+ §9 改动清单 3.5 + §10 门禁 B3 |
| 语句 #10 | try-with-resources（声明式资源） | DEGRADED | `StatementTranslator.java:585-611`, `:718-720` | §5.7 try-with-resources（close 序列成为**隐式 finally** 的一部分：正常/return/throw 三条出口都执行 close）+ §9 改动清单 3.6 + §10「`try (Reader r = open()) { use(r); }`」行 |
| 语句 #9 | try-with-resources（`try (existing)`） | DROPPED-SILENT | `StatementTranslator.java:588-590` | §5.7（**无初始化器的资源被跳过** ⇒ 裸名资源加入 `resources` ⇒ `close()` 会发）+ §9 改动清单 3.6 + §10「`try (existing) { ... }`」行 |
| 语句 #26 | try-with-resources（`var` 资源） | DEGRADED | `TypeMapper.java:216` | §6 类型面方案「`var`」行（资源类型由推断链得出 ⇒ 不再 `UNKNOWN`）+ §9 改动清单 4.7 + §11 K6 / Q1 |
| 语句 #17 | `assert` | DEGRADED | `StatementTranslator.java:950-955` | §5.10 `assert`（`BRANCH` → 失败路径求值消息 + `THROW` `java.lang.AssertionError`）+ §9 改动清单 3.10；「是否生效由运行期开关决定」进 §12 的 `E2` （`B` 档：记 `drop(SEMANTICS, "assert.disabled")`） |
| S-17 | `yield` | DEGRADED | `StatementTranslator.java:189-205` | §5.11 `yield`（补 `JUMP` 到 switch 表达式末尾）+ §5.5 + §9 改动清单 3.4 |
| S-18 | empty statement | KEPT | `StatementTranslator.java:173` | 保持现状（无需改动） |
| 语句 #1 | `switch` statement (colon form) | DEGRADED | `StatementTranslator.java:891` | §5.3 `switch` 语句（**选择链与体序列分离**：选择链决定「从哪个 case 开始执行」，体序列保持源顺序 ⇒ fall-through 天然成立）+ §9 改动清单 3.2 + §10 门禁 B5 / B6 |
| 语句 #22 | `switch` statement (arrow form) | DEGRADED | `StatementTranslator.java:891` | §5.3（箭头 arm 不得落入下一个分支；`SwitchEntry.getType()` 的箭头/冒号区别由「体序列 vs 选择链」分离承载）+ §9 改动清单 3.2 与 3.3 + §10 门禁 B7 |
| 语句 #1 | `switch` statement (`default` 不在最后) | DEGRADED | `StatementTranslator.java:859-862` | §5.3（**`default` 的体标签是选择链最后一条测试的假目标**，但 default 的**体**仍放在源顺序位置上 ⇒ 既修「default 短路后续测试」又保住 fall-through）+ §9 改动清单 3.2 + §10 门禁 B6 |
| S-19 | `switch` statement (pattern labels) | DEGRADED | `StatementTranslator.java:868-879` | §5.3（模式标签条件是 `INSTANCEOF`；绑定在**体内**做）+ §9 改动清单 3.2 + §10 门禁 B5 |
| 语句 #1 | `switch` statement (multi-label `case 1, 2 ->`) | DEGRADED | `StatementTranslator.java:864-899` | §5.3（假目标是**下一个标签的测试**，不是下一个 entry 的体 ⇒ `case 1,2 ->` 会有**两条**比较）+ §9 改动清单 3.2 + §10 门禁 B5 |
| 语句 #12 | `switch` statement / expression (**guarded pattern** `when`) | DROPPED-SILENT | `plumb-tac/src/main/java/org/plumb/tac/translate/` 下无 `SwitchEntry.getGuard()` 调用点 | **§5.3.1「模式守卫（`case <pattern> when <expr>`）」**（守卫测试追加在标签测试之后；守卫失败落到**下一个 case 的测试**；承载 = IR-C `TacGuard` + IR-I `TacCase.guards` ⇒ **不需要新字段**）+ §9 改动清单 3.12 / 3.13 + §10 门禁 B9。**附录 C 的 U-JE2 已补齐** |
| 语句 #10 | `switch` expression (arm selection) | DEGRADED | `plumb-tac/src/main/java/org/plumb/tac/translate/JavaModernFeatures.java:154` | §5.5 `switch` 表达式（选择链同 §5.3）+ §9 改动清单 3.3 + §7 精度与丢弃可见性 |
| 语句 #2 | `switch` expression (arrow arms) | DEGRADED | `JavaModernFeatures.java:202-215` | §5.5 `switch` 表达式（每个 arm 结束**必须** `JUMP` 到 end ⇒ arm 的体块**互不可达**）+ §9 改动清单 3.3 + §10 门禁 B7 |
| 语句 #2 | `switch` expression (block arm / `yield`) | DEGRADED | `JavaModernFeatures.java:71`（javadoc）vs `:174-216`（代码） | §5.5 `switch` 表达式（block 体 + `yield v;` ⇒ `MOVE t_res = v; JUMP L_SWEXPR_END`）+ §5.11 + §9 改动清单 3.3 与 3.4 + §10 门禁 B7 |
| S-20 | explicit constructor invocation `this(...)`/`super(...)` | KEPT | `StatementTranslator.java:207-219` | §4 表达式面表「显式构造器调用 `super(...)`/`this(...)`」行（目标形态 `CALL <父类>.<init>/N`，`args[0]=this`；`this(...)` 用本类 FQN）+ §8 IR 需求 #14（IR-P） |
| S-21 | unparsable statement | DROPPED-NOTED | `StatementTranslator.java:221-239` | §9 改动清单 0.2（`reportUnhandled` 保留：**输入坏了/翻译失败**）+ §7（问题通道保留但改用途：`PARSE_PROBLEM`/`TRANSLATION_ERROR`）+ §9 改动清单 0.6（前端要收集 `programProblems()`） |
| S-22 | class declaration | KEPT | `plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java:288`, `:337` | 保持现状（无需改动）—— §3 声明面表「嵌套类型」/「局部类型」行的类型声明面 + §9 改动清单 4.5（产出 `TacTypeDecl`） |
| S-23 | interface declaration | KEPT | `TacTranslator.java:288`, `:337` | 保持现状（无需改动）—— §3 声明面表「接口 default/static 方法」行 + §9 改动清单 4.5 |
| S-24 | enum declaration | KEPT | `TacTranslator.java:628` | 保持现状（无需改动）—— §3 声明面表「枚举常量」/「枚举 `values()`」/「枚举 `valueOf(String)`」行 + §9 改动清单 4.3 |
| S-25 | record declaration | KEPT | `TacTranslator.java:614` | 保持现状（无需改动）—— §3 声明面表「记录规范构造器」/「记录访问器」/「记录的 `equals`/`hashCode`/`toString`」行 + §9 改动清单 4.4 |
| S-26 | nested types | KEPT | `TacTranslator.java:720-759` | 保持现状（无需改动）—— §3 声明面表「嵌套类型」行（独立函数集 + `enclosingClass` 链） |
| S-27 | local types | KEPT | `TacTranslator.java:1124` | 保持现状（无需改动）—— §3 声明面表「局部类型」行 |
| S-28 | anonymous class | KEPT | `TacTranslator.java:1032-1109` | 保持现状（无需改动）—— §3 声明面表「匿名类」行（**字段写入补 receiver**）+ §9 改动清单 4.2 |
| 语句 #14 | sealed / non-sealed / `permits` | DROPPED-SILENT | 无 `getPermits()` / `isSealed()` 调用点 | §3 声明面表「`sealed`/`permits`」行（`permittedSubtypes` + `isSealed`；运行期无效果 ⇒ 只需元数据）+ §8 IR 需求 #11（IR-N）+ §9 改动清单 4.6 |
| 语句 #15 | type parameters + bounds（声明处） | DROPPED-SILENT | 无 `getTypeParameters()` 调用点 | §8 IR 需求 #11（IR-N `typeParameters`）+ §9 改动清单 4.6（读类型参数 ⇒ `typeParameters`）+ §6 类型面方案「**类型变量 `T`**」行（**完整上界集合**进 `TacTypeParam.bounds`） |
| 语句 #15 | type parameter（使用处） | DEGRADED | `TypeMapper.java:200-215` | §6 类型面方案「**类型变量 `T`**」行（使用处保留类型变量 + `bound`，不再直接替换成上界）+ §8 IR 需求 #10 / #11 + §9 改动清单 4.7 |
| 语句 #26 | type arguments | DEGRADED | `TypeMapper.java:154`, `:58` | §6 类型面方案「**泛型实参**」行（所有位置保留实参）+ §9 改动清单 4.7 + §8 IR 需求 #10 |
| 语句 #24 | wildcards | DEGRADED | `TypeMapper.java:216` | §6 类型面方案「**通配符 `?`/`? extends T`/`? super T`**」行 + §8 IR 需求 #10（IR-K `bound`）+ §9 改动清单 4.7 |
| S-29 | annotations on types | KEPT | `TacTranslator.java:347` | 保持现状（无需改动）—— §3 声明面表「`TacTypeDecl` 的 Java 来源」段（`annotations` ← `extractAnnotationNames`） |
| S-30 | annotations on methods | KEPT | `TacTranslator.java:663` | 保持现状（无需改动）—— §3 声明面表「方法」行（`methodAnnotations`/`paramAnnotations`/`annotationValues` 已存在且 Java 已填） |
| S-31 | annotations on fields | KEPT | `TacTranslator.java:478`, `:516` | 保持现状（无需改动）—— §3 声明面表「实例字段（带初始化器）」/「静态字段（带初始化器）」行（`fieldAnnotations` 已填） |
| S-32 | annotations on parameters | KEPT | `TacTranslator.java:966` | 保持现状（无需改动）—— §3 声明面表「方法」行（`paramAnnotations` 已填） |
| S-33 | annotation values | KEPT | `TacTranslator.java:1313-1404` | 保持现状（无需改动）—— §3 声明面表「方法」行（`annotationValues` 已填） |
| 语句 #23 | annotations on local variable declarations | DROPPED-SILENT | `TacTranslator.java:79`（"annotations on local decls (ignored for now)"） | 局部量侧：§3 声明面表「局部变量声明上的注解」行（`TacVar.annotations`）+ §8 IR 需求 #18（IR-AL）+ §9 改动清单 4.10 |
| 语句 #23 | type-use annotations | DROPPED-SILENT | 无调用点（只读声明处的 `getAnnotations()`） | type-use 侧：§4 表达式面表「**表达式上的注解**」行（IR-AN（§8-17，低优先级））+ §8 IR 需求 #17 + §11 Q5 |
| S-34 | field: `static` | KEPT | `TacTranslator.java:489` | 保持现状（无需改动）—— §3 声明面表「静态字段（带初始化器）」/「静态字段（无初始化器）」行（receiver=类标识 + `ownerType`）+ §9 改动清单 4.1 |
| 语句 #24 | field: `final` | DROPPED-SILENT | 无 IR 通道；`isFinal()` 从未被调用 | §3 声明面表「**字段修饰符（`static`/`final`/`volatile`/`transient`）与 `final` 局部量**」行（`final` + 常量初值 ⇒ 参与**编译期常量折叠**；`final`/`volatile`/`transient` ⇒ `TacMemberRef.modifiers`）+ §8 IR 需求 #19 + §9 改动清单 4.14。**附录 C 的 U-JS5 已补齐** |
| 语句 #24 | field: `transient` / `volatile` | DROPPED-SILENT | 无 IR 通道 | §3 声明面表「**字段修饰符（`static`/`final`/`volatile`/`transient`）与 `final` 局部量**」行（**零指令**：修饰符不改变指令流；`volatile`/`transient` ⇒ `TacMemberRef.modifiers`，**不产生 drop**）+ §8 IR 需求 #19 + §9 改动清单 4.14 |
| 语句 #29 | field with no initializer and no annotation | DROPPED-SILENT | `TacTranslator.java:476-503` | §3 声明面表「实例字段（无初始化器）」行（**无指令**（零值非运行时事实）；声明进 `TacTypeDecl.fields`）+「静态字段（无初始化器）」行 |
| S-35 | instance field initializers (no explicit ctor) | KEPT | `TacTranslator.java:546-595` | 保持现状（无需改动）—— §3 声明面表「实例字段（带初始化器）」行 |
| 语句 #4 | instance field initializers (**有显式构造器**) | DROPPED-SILENT | `TacTranslator.java:547`（`if (!hasExplicitCtor)`） | §3 声明面表「实例字段（带初始化器）」行（**U-JS1 已补齐**：初始化器序列**在每个构造器体之前**发出，不再依赖「类里有没有显式构造器」）+ §9 改动清单 4.11 + §10「`class C { private final String p = "x"; C() { } }`」行 |
| S-36 | static field initializers | KEPT | `TacTranslator.java:485-526` | 保持现状（无需改动）—— §3 声明面表「静态字段（带初始化器）」行（`MOVE` + `FIELDSTORE <类标识>.f=t`）+ §9 改动清单 4.1 |
| 语句 #5 | static field initializers **+** static 初始化块 | DROPPED-SILENT | `plumb-tac/src/main/java/org/plumb/tac/ir/TacProgram.java:189`（按 signature 放入）+ `TacTranslator.java:710` | §3 声明面表「静态字段（带初始化器）」行（落点函数 = **`<clinit>-fields`**）与「静态初始化块」行（落点函数 = **`<clinit>-block`**）—— **U-JS2 已补齐**：两个函数**区分名** ⇒ 不再「后写覆盖先写」+ §9 改动清单 4.13 |
| S-37 | static initializer block | KEPT | `TacTranslator.java:710-719` | 保持现状（无需改动）—— §3 声明面表「静态初始化块」行（`<clinit>-block`） |
| S-38 | instance initializer block | KEPT | `TacTranslator.java:710-719` | 保持现状（无需改动）—— §3 声明面表「实例初始化块」行（名字须区分，如 `Outer.<init-instance>/0`） |
| 语句 #5 | instance initializer block **+** instance field initializers | DROPPED-SILENT | `TacProgram.java:189` | §3 声明面表「实例初始化块」行（名字须区分，如 `Outer.<init-instance>/0`）⇒ 初始化**块**不再被实例字段初始化器覆盖 + §9 改动清单 4.13 |
| S-39 | method: `static` | KEPT | `TacTranslator.java:972` | 保持现状（无需改动）—— §3 声明面表「方法」行（`this` 只在非 static 且非构造器时注册） |
| S-40 | method: `abstract` | KEPT | `TacTranslator.java:661`, `:1704` | 保持现状（无需改动）—— §3 声明面表「方法」行（空函数 + `RETURN`） |
| S-41 | method: `native` | KEPT | 同上 | 保持现状（无需改动）—— §3 声明面表「方法」行（空函数 + `RETURN`） |
| 语句 #18 | method: `synchronized` | DEGRADED | `TacTranslator.java:972` | §5.8 `synchronized`（方法级 `synchronized` 落在**函数级** `TacCriticalSection` 同一字段）+ §8 IR 需求 #12 + §9 改动清单 3.9；争用顺序进 §12 的 `E1` （`B` 档：记 `drop(SEMANTICS, "sync.contention")`） |
| 语句 #24 | method: `strictfp` | DROPPED-SILENT | 无 IR 通道 | §3 声明面表「**方法修饰符（`strictfp`/`native`/`abstract`/`synchronized`/`default`）**」行（**零指令**；事实落 `TacMemberRef.modifiers`（IR-E 的补充细化）；`native` ⇒ 不产出 `TacFunction`、调用点按外部函数处理 + `drop(SEMANTICS, "native.method")`；`abstract` ⇒ 零指令、声明进 `TacTypeDecl.members`；`synchronized` ⇒ 方法体按 §5.8 的锁语义翻译；`default` ⇒ 与普通方法同路径） + §9 改动清单「4.16」条（读 `isStrictfp`/`isNative`/`isAbstract`/`isSynchronized`/`isDefault`） |
| S-42 | interface `default` method | KEPT | `TacTranslator.java:653` | 保持现状（无需改动）—— §3 声明面表「接口 default/static 方法」行（同普通方法；可覆盖语义靠 `TacTypeDecl` 继承图）+ IR-E + IR-N |
| S-43 | interface `private` method | KEPT | `TacTranslator.java:653` | 保持现状（无需改动）—— §3 声明面表「接口 default/static 方法」行 |
| 语句 #28 | method: varargs | DEGRADED | 无 `isVarArgs()` 调用点；`TacTranslator.java:926` | §4 表达式面表「**可变参数（varargs）调用的 arity**」行（被调方形参类型 `ARRAY<组件类型>`；调用点把多出的实参装进**合成数组**）+ §8 IR 需求 #14 |
| 语句 #13 | method: `throws` clause | DROPPED-SILENT | 无 `getThrownExceptions()` 调用点；`TacTranslator.java:1752`（签名里没有 throws） | §3 声明面表「**`throws` 子句**（受检异常声明）」行（`TacFunction.throwsTypes` = 受检异常 FQN 列表 ⇒ catch 匹配可判断「这个 catch 是否可能捕获受检异常」）+ §8 IR 需求 #18（IR-AL）+ §9 改动清单 4.9 |
| S-44 | explicit constructor | KEPT | `TacTranslator.java:666-677` | 保持现状（无需改动）—— §3 声明面表「构造器」行（一个 `TacFunction`；`this` 为局部量） |
| 语句 #11 | constructor: `this` receiver binding | DROPPED-SILENT | `TacTranslator.java:972`（`!(decl instanceof ConstructorDeclaration)`） | §3 声明面表「构造器」行（一个 `TacFunction`；**`this` 为局部量**）+ §3「方法」行（`this` 为带声明类型的局部量）+ §8 IR 需求 #1（`ownerType`）+ §9 改动清单 4.2 |
| S-45 | compact record constructor | KEPT | `TacTranslator.java:678`, `:1151` | 保持现状（无需改动）—— §3 声明面表「紧凑构造器」行（已发每组件 `FIELDSTORE`） |
| S-46 | delegating constructor `this(...)` | KEPT | `StatementTranslator.java:207` | 保持现状（无需改动）—— §4 表达式面表「显式构造器调用 `super(...)`/`this(...)`」行 |
| S-47 | record components | KEPT | `TacTranslator.java:1581-1702` | 保持现状（无需改动）—— §3 声明面表「记录规范构造器」/「记录访问器」行 |
| S-48 | enum constants (declaration) | KEPT | `TacTranslator.java:1491-1506` | 保持现状（无需改动）—— §3 声明面表「枚举常量」行 |
| S-49 | enum constants with bodies | KEPT | `TacTranslator.java:738-743` | 保持现状（无需改动）—— §3 声明面表「枚举常量」行（常量带类体 ⇒ 匿名子类照常降级） |
| 语句 #20 | enum constant constructor arguments | DROPPED-SILENT | `TacTranslator.java:1491-1505`（body = `NOP`） | §3 声明面表「枚举常量」行（**U-JS4 已补齐**：函数体 = `NEW <枚举类型> args=[<构造器实参>]`；无实参时不带 `args`）+ §9 改动清单 4.12 + §10「`enum E { PLUS("+") }`」行 |
| 语句 #21 | enum `values()` / `valueOf()` | DEGRADED | `TacTranslator.java:1508-1534`, `:1536-1552` | §3 声明面表「枚举 `values()`」行（`NEWARRAY E[] n` + n 条 `ARRAYSTORE` + `RETURN`）+「枚举 `valueOf(String)`」行（比较链 + `THROW IllegalArgumentException`）+ §9 改动清单 4.3 + §10「`enum E{A,B}` + `E.values()`」行与 `E.valueOf(s)` 行 |
| 语句 #30 | record `equals`/`hashCode`/`toString` | DROPPED-SILENT | `TacTranslator.java:612`（只有注释，无诊断） | §3 声明面表「记录的 `equals`/`hashCode`/`toString`」行（`toString` 按 JLS 8.10.3 拼、`hashCode` `31*h1+h2`、`equals` 逐组件 `CALL java.util.Objects.equals/2`）+ §9 改动清单 4.4 + §11 Q3；数组组件进 §12 的 `E6` （`B` 档：记 `drop(VALUE, "record.toString.arrayComponent")`） |
| 语句 #27 | `extends` / `implements` hierarchy | DEGRADED | `TacTranslator.java:416-446` | §3 末段「`TacTypeDecl` 的 Java 来源」（`superType` ← `cid.getExtendedTypes()`、`interfaces` ← `cid.getImplementedTypes()`，二者现只存**短名**，改存 **FQN**；**嵌套类的父链不再丢**）+ §9 改动清单 4.5 |
| S-50 | receiver parameter (`Foo this`) | KEPT | `TacTranslator.java:1004` | 保持现状（无需改动）—— §3「方法」行（显式接收者形参 `Foo this` 优先于声明类型） |
| S-51 | `Outer.this`（限定 this） | UNCLEAR | 表达式面，`plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java` —— 不在本审计范围 | **审计标 UNCLEAR（未细查）**；本方案的判定：`Outer.this` 与 `this` 的**运行期值完全同形**（都取「当前实例」），限定符只参与**名字解析**、不产生额外的运行期事实 ⇒ 落点 = §4 表达式面表「`this`」行（局部量 `this`，带**声明类**类型）+ 附录 A 的「A5-04 `this`」行。**不新造 IR**：限定形式只需在**解析期**把解析目标换成外层类（FQN 进 `TacTypeDecl`），指令形状不变 |

**附录 B 小计**：**98 条**（= 审计 A 节的**全部 98 行**，一行不缺）= 原附录 **30 条**（编号 `语句 #1`–`#30`）+ 本轮**新增 68 条**。其中 `KEPT` **45 条**（去向=「保持现状（无需改动）」）、问题项 **52 条**（`DEGRADED` **29** + `DROPPED-*` **23**）、审计标 `UNCLEAR` **1 条**（`Outer.this`（限定 this）—— 本方案给了落点：与 `this` 运行期同形）⇒ **每一行都有落点**（A 完整表达 **46** / B 显式记账 **7** / C 永久例外 **0**）。**`⚠` 未覆盖 = 0**。

---

## 附录 C. Java 未覆盖清单

本附录列出附录 A / 附录 B 中带 `⚠` 的条目。**每条都去源码核实过**（本轮实测：直接读 `plumb-tac/src/main/java/org/plumb/tac/translate/` 下的源码与全包 grep），结论如下。**这些条目不影响本文档主体结论的可执行性，但需要在实施前补齐**（否则「§9 改完后 Java 侧应当没有 drop」（§7 ③、门禁 G-5）这个判定标准会在这些点上落空）。

### C.1 完全未覆盖（方案里没有任何落点）

#### U-JE2 / U-JS3 —— 带守卫的模式 `when`（表达式 #2 / 语句 #12）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/` 全包 grep `getGuard` **零命中**（本轮实测）；`getGuard` 是 JavaParser 的 `SwitchEntry` API。⇒ 模式守卫今天**确实从不被读取**，审计结论成立。
- **方案为什么没覆盖**：§5.3 重建了选择链与体序列，也写了「守卫：每条测试一条 IR-C `Compare(disc,EQ,label)`」—— 那是**标签比较**的守卫（`disc == 标签`），不是**模式守卫**（`when i > 0`）。全文没有任何 `SwitchEntry.getGuard()` 的读取位置，§9 改动清单也没有对应条目。
- **后果**：带 `when` 的分支只按类型命中 ⇒ 引擎认为「守卫失败的路径」可达 ⇒ 该分支内任何 finding 都是误报源；守卫表达式自身的污点也从未被翻译。
- **建议**：§5.3 增补「模式守卫」小节：读 `SwitchEntry.getGuard()` ⇒ 作为该 case 的**附加**守卫（`BRANCH` 的 guard 链 / IR-I 的 `TacCase.guards`），守卫失败**落到下一个 case**（不是直接落 `default`）；承载能力 §8 IR 需求 #3（IR-C `TacGuard`）已有，**不需要新字段**。

#### U-JS1 —— 有显式构造器时实例字段初始化器被丢弃（语句 #4）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java:539-547` 先扫成员找 `ConstructorDeclaration` 置 `hasExplicitCtor`，随后 `if (!hasExplicitCtor)` 才逐字段发 `FIELDSTORE`（合成 `<init>`）⇒ 类一旦声明任何构造器，**全部**实例字段初始化器（连同字面量）都不进 TAC。审计结论成立。
- **方案为什么没覆盖**：§3 声明面表「实例字段（带初始化器）」行的目标形态是 `MOVE t=v` + `FIELDSTORE this.f=t`，**没有说明「有显式构造器时也要发」**；§3「构造器」行也没有「构造器体前插入字段初始化器序列」这一条；§9 改动 4.1 / 4.2 只处理 `ownerType` 与匿名类 receiver。
- **后果**：被扫描源码里的字面量/污点源消失（审计点名的硬编码口令检查正是看这个合成 `<init>`）⇒ 该类上的规则整体失效。
- **建议**：§3 声明面表「实例字段（带初始化器）」行补一句「初始化器序列**在每个构造器体之前**发出（与 Java 的初始化顺序一致），不再依赖『类里有没有显式构造器』」；§9 增一条 `emitInstanceFieldInit` 的改动条目。

#### U-JS4 —— 枚举常量的构造器实参（语句 #20）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java:1491-1506` 的枚举常量合成函数体只发一条 `NOP`（`:1504`），**从不读** `ec.getArguments()` ⇒ `PLUS("+")` 的实参不进 TAC。审计结论成立。
- **方案为什么没覆盖**：§3 声明面表「枚举常量」行的目标形态是「函数体 = `NEW <枚举类型>`（常量即实例）」，§9 改动 4.3 同 ⇒ 实参既没有进 `NEW` 的 `args`，也没有别的去处。
- **建议**：「枚举常量」行的目标形态改为「函数体 = `NEW <枚举类型> args=[<构造器实参>]`」；无实参时不带 `args`。

#### U-JE6 —— Java 22–25 语言特性（表达式 #26）

- **源码核实（并附审计口径校正）**：`plumb-parser/src/main/java/org/plumb/parser/JavaLanguageLevel.java:44` / `:126` / `:163` 把 `"22"`–`"25"`/`"latest"` **一律映射到 `JAVA_21`**；该文件 javadoc 明写「JavaParser 3.26.4 的枚举只到 Java 21，`BLEEDING_EDGE` 与 `JAVA_21` 是**同一个常量**」，`plumb-parser/src/test/java/org/plumb/parser/JavaLanguageLevelTest.java:98-108` 断言了这一点。
  ⇒ **口径校正**：审计「Java 22–25 的文件整体被丢弃」偏宽 —— 只有**真正用到 Java 22+ 语法**的文件才会解析失败并报 `PARSE_PROBLEM`；标了 `Java 22` 但语法仍在 21 之前的文件是能解析的。而 `plumb-tac/.../JavaModernFeatures.java:42` 的 javadoc 仍写「Java 8 through Java 25」，与解析器的实际上限不一致（这一条属**文档与实现不一致**，不是 TAC 表达力问题）。
- **方案为什么没覆盖**：§1 基线只声明「JavaParser = `javaparser-core-3.26.4`」，全文没有语言级别/文法上限的声明与处理。
- **建议**：§1 基线补一句「解析器文法上限 = Java 21（`JavaLanguageLevel.resolve` 把 22–25 映射到 `JAVA_21`）」；§11 风险表补一条「`JavaModernFeatures` 的 javadoc 声明 Java 25 ⇒ 需同步为 Java 21，或把语法级上限写进 §3 的构造清单」。

#### U-JE8 —— 字面量按声明上下文的**窄化**（`byte b = 1`）〔本轮扩展附录时新增〕

- **源码核实**：`ExpressionTranslator.java:151-157` 把整数字面量一律降成 `Const.ofInt` + `INT`；而 `StatementTranslator.java:275` 的声明类型是 `BYTE`/`SHORT` ⇒ **值是 `INT`、类型是 `BYTE`，两者不一致**。本轮实测：`plumb-tac/src/main/java/org/plumb/tac/translate/` 全包 grep `narrow`/`asByte`/`asShort`/`asChar` **零命中** ⇒ **窄化今天完全不被建模**（审计 A2「byte/short 字面量窄化」行的结论成立）。
- **方案为什么没覆盖**：§4「`int`/`long` 字面量」行只登记了字面量**自身**的 `INT`/`LONG`；§4「装箱 / 拆箱」行的类型调整只覆盖**包装类型**（`CLASS<Integer>`）；§6「基本类型」行只登记了 `BYTE`/`SHORT`/`CHAR` 这三个**类型**本身 ⇒ **三处都没有「字面量按声明上下文收窄」这一条**。
- **后果**：任何以**声明类型**为键的规则（边界、溢出、类型比较）看到的都是 `INT` ⇒ `byte b = 1` 的事实（b 是 8 位）不在 TAC 里。
- **建议**：§4 需要一行「**字面量窄化**」：值不变、**类型**按声明上下文收窄为 `BYTE`/`SHORT`/`CHAR`（值域 `Integral(8/16)`，IR-D）+ §9 一条对应的读点。**不需要新 IR 编号**（IR-D 的值域 + IR-E 的声明类型已有）。
- ✅ **已补齐（2026-11-16 本轮）**：§4 表达式面表新增「**字面量按声明上下文的窄化**（`byte b = 1` / `short s = 2` / `char c = 65`）」行，§9 改动清单新增「2.19」条（把声明类型传给字面量求值）。附录 A 的 `A2-01` 行已同步改成真实落点。

### C.2 部分覆盖（方案有落点，但该构念的一部分仍没有落点）

#### U-JE1 —— 局部量 `++`/`--` 的目标写回（表达式 #1）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java:338-360` 的 `translateUnary` 对 PRE/POST INC/DEC 只发一条 `UNARYOP`（`dest` 是新 temp），**从不调用 `writeLhs`**；对比同文件 `:380`，`translateAssign` 的复合赋值分支**调用了** `writeLhs(target, tmp, b, ae)` ⇒ 目标变量今天**确实**永不更新（审计结论成立，不是审计误判）。
- **已覆盖的部分**：§4 表达式面表「**字段的 `++`/复合赋值**」行给出了字段目标的完整三段形态（`FIELDLOAD` → `UNARYOP` → `FIELDSTORE`），§9 改动 2.2 对应它。
- **残留**：§4「`++`/`--`」行的目标形态只描述了取值一侧（`UNARYOP PRE_INC/PRE_DEC/POST_INC/POST_DEC`），§9 改动 2.2 的范围限定在「**字段目标**」⇒ 局部量 `i++` 落到 §4「`++`/`--`」行时仍只发一条 `UNARYOP`。§4「复合赋值 `x += y`」行的「读-算-写三段」可以复用，但文档没有把它挂到 `++`/`--` 上。
- **建议**：把 §4「`++`/`--`」行的目标形态改成「读-算-写三段（`MOVE`/`FIELDLOAD`/`ARRAYLOAD` → `UNARYOP` → `writeLhs` 写回）」，并在 §9 改动 2.2 把范围从「字段目标」放宽到「所有赋值目标」。

#### U-JE3 —— 静态上下文里的静态字段**读取**（表达式 #4）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java:389-429` 的 `translateName`：在有 `this` 的作用域里会发 `FIELDLOAD`（`:415-421`）；**没有 `this`**（静态上下文/顶层）时走 `:425-428`，返回一个**合成 `UNKNOWN` 变量**、**不发任何指令** ⇒ 静态字段的读取今天完全不可见。审计结论成立。
- **已覆盖的部分**：§3 声明面表「静态字段（带初始化器）」行规定了**写**侧（`FIELDSTORE <类标识>.f=t`，带 `ownerType`），§9 改动 4.1 对应它。
- **残留**：§4 表达式面表的「字段访问」行指向 `translateFieldAccess :1411-1443`（**限定**访问路径 `o.f`），与本条走的 `translateName`（**非限定**路径）不是同一条代码路径 ⇒ **读**侧没有目标形态。
- **后果**：即使写侧按 §9 4.1 修好，静态字段的值仍到不了任何读取点 ⇒ 写侧修复目前是「单边」的。
- **建议**：§4 表达式面表补一行「非限定字段读取（`translateName`）」：按符号解析判定「局部量 / 实例字段 / 静态字段」；静态字段走 `FIELDLOAD` + `ownerType=<类 FQN>` + 类类型 receiver，与 §9 4.1 的写侧对齐。

#### U-JE4 —— 泛型 / 菱形构造器的 `calleeSig` 形态（表达式 #12）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java:1232` 取 `oce.getType().asString()`（带类型实参的源码形，如 `ArrayList<String>`），`:1242` 用它去 `TypeMapper.resolveWellKnownFqnStatic(textual)`，`:1249` 用结果拼 `calleeSig` ⇒ 实参带 `<>` 时得不到类 FQN，`calleeSig` 不是 `FQN.<init>/arity` 形式。审计结论成立。
- **已覆盖的部分**：§8 IR 需求 #14 给出了 IR-P 的命名规范（`<ownerFqn>.<methodName>/<arity>`），§3 末段也要求 `fqn` 改存 FQN ⇒ **目标形状是确定的**。
- **残留**：§4 表达式面表「`new T(...)`」行的目标形态写的是「**保留**」，与 §8 #14 的口径**不一致**；§9 改动清单也没有 `translateNew` 的条目 ⇒ 实现者按 §4 做会把当前形态原样留下。
- **建议**：§4「`new T(...)`」行的目标形态写成「`calleeSig` 用**剥掉类型实参后的 FQN**（`ArrayList.<init>/0`）」，并在 §9 增一条 `translateNew` 的改动条目。

#### U-JE5 —— lambda 签名元数与 CALL 实参数不一致（表达式 #22）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java:1576` `lambdaArity = le.getParameters().size() + captures.size()`（签名元数）vs `:1626-1630` 调用点只发 `[null]` + 捕获 ⇒ 实参数 = `1 + captures`；两者在「显式形参数 ≠ 0」时恒不相等。审计结论成立。
- **已覆盖的部分**：§4 表达式面表「**lambda**」行把 `args[0]` 从空常量改成**真实 `this`**，修的是**接收者**；§8 IR 需求 #14 规定「接收者一律 `args[0]`」。
- **残留**：**签名元数**（要不要含接收者、要不要含捕获数）在 §8 #14 与 §4「**lambda**」行里都没有定义 ⇒ 元数与实参数的不一致未被消解。
- **建议**：在 §10（IR-P 细则）补一条「lambda 的 `calleeSig` 元数 = 显式形参数（不含接收者、不含捕获数；捕获数单独记在捕获通道）」，并让 §4「**lambda**」行与之对齐。

#### U-JS2 —— 静态初始化块与静态字段初始化器同名（语句 #5）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java:711` 与 `:1075` 都是 `String fnName = id.isStatic() ? "<clinit>" : "<init>";` ⇒ 静态初始化块与静态字段初始化器**同名**；函数表按签名后写覆盖 ⇒ 后者覆盖前者。实例侧同理。审计结论成立。
- **已覆盖的部分**：§3 声明面表「**实例初始化块**」行已经给出了区分名（`Outer.<init-instance>/0`）⇒ **实例侧已覆盖**。
- **残留**：「**静态初始化块**」行的目标形态只写 `<clinit>`，而「静态字段（带初始化器）」行没有指定它落在哪个函数 ⇒ **静态侧仍然同名**。
- **建议**：「静态初始化块」行的目标形态改为区分名（如 `<clinit>-block`），或把静态字段初始化器落到独立的 `<clinit>-fields` 函数。

#### U-JS5 —— 字段修饰符（`final`/`volatile`/`transient`）与 `final` 局部量（语句 #24）

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/` 全包 grep `isFinal` / `isVolatile` / `isTransient` **零命中**（本轮实测）⇒ 这些修饰符今天**完全不被读取**。审计结论成立。
- **已覆盖的部分**：无。§3 声明面表没有「字段修饰符」行，§8 IR 需求 #1–#18 也没有修饰符槽位。
- **残留且互相牵连**：§4 表达式面表「**`static final` 常量折叠在声明处被绕过**」行的目标形态要求「常量值取自**被扫描源码里同一常量的声明**（编译期常量折叠）」⇒ **没有 `final` 就无法判定「编译期常量」**，该行的目标形态目前不可达 ⇒ 表达式 #14 的落点也依赖本条。
- **建议**：§3 声明面表补「字段修饰符」行（`final`/`volatile`/`transient`/`static`）：`final` + 有初值 ⇒ 参与 §4 的编译期常量折叠（登记进按 FQN 索引的常量表）；`volatile`/`transient` ⇒ 进 `TacMemberRef` 的修饰符槽（§8 可加一小项）；`final` 局部量 ⇒ 进 `TacVar`（可与 IR-AL 的 `TacVar.annotations` 并列）。运行期无效果的其余修饰符按 §3 P3 记 `STRUCTURE` drop 或零指令，写进 §12。

#### U-JS9 —— **方法**修饰符（`strictfp` 等）的读取点〔本轮扩展附录时新增〕

- **源码核实**：`plumb-tac/src/main/java/org/plumb/tac/translate/` 全包 grep `isFinal` / `isVolatile` / `isTransient` **零命中**（本轮实测）；`strictfp` 同样**没有任何读取点** ⇒ 修饰符事实今天完全不在 TAC 里。`native` / `abstract` 方法今天降成「空函数 + `RETURN`」（`TacTranslator.java:661`、`:1704`）—— **形状本身是对的**，缺的只是「它声明自己是 `abstract`/`native`」这一事实。审计 A 节「method: `strictfp`」行（`DROPPED-SILENT`）的结论成立。
- **已覆盖的部分**：§8 IR 需求 #19 的**载体**（`TacMemberRef.modifiers`，通用修饰符列表）已经够用 ⇒ **不需要新字段**；§3 声明面表「字段修饰符…」行已把「零指令、无 drop」的口径写清。
- **残留**：§9 改动清单 **4.14** 的**读点**只列了字段的 `isFinal`/`isVolatile`/`isTransient` ⇒ **方法**修饰符（`strictfp`；以及 `native`/`abstract` 的声明事实）的读取没有落点。
- **建议**：§9 改动清单 4.14 的读点扩到「字段 + 方法的修饰符」（`strictfp`/`native`/`abstract` 一起读 ⇒ 进 `TacMemberRef.modifiers`）。`strictfp` 在 x86-64 上运行期无效果 ⇒ **零指令、无 drop**（不新增 §12 条目，也不影响 §7 ③ 的「Java 侧应当没有 drop」判定）。
- ✅ **已补齐（2026-11-16 本轮）**：§3 声明面表新增「**方法修饰符（`strictfp`/`native`/`abstract`/`synchronized`/`default`）**」行（零指令；事实落 `TacMemberRef.modifiers`），§9 改动清单新增「4.16」条（读这 5 个修饰符；`native` 方法不产出 `TacFunction` + `drop(SEMANTICS, "native.method")`；`synchronized` 方法体按 §5.8 的锁语义翻译）。附录 B 的「语句 #24」行已同步改成真实落点。

### C.3 未覆盖清单小结

| 编号 | 归属台账 | 构念 | 状态 | 需要补的位置 |
|---|---|---|---|---|
| U-JE2 / U-JS3 | 表达式 #2 / 语句 #12 | 带守卫的模式 `when` | 完全未覆盖 | §5.3 + §9（IR-C 已够用） |
| U-JS1 | 语句 #4 | 有显式构造器时的实例字段初始化器 | 完全未覆盖 | §3 声明面表 + §9 |
| U-JS4 | 语句 #20 | 枚举常量的构造器实参 | 完全未覆盖 | §3 声明面表 + §9 改动 4.3 |
| U-JE6 | 表达式 #26 | Java 22–25 语言特性（+ 审计口径校正） | 完全未覆盖 | §1 基线 + §11 风险表 |
| U-JE1 | 表达式 #1 | 局部量 `++`/`--` 的写回 | 部分覆盖 | §4「`++`/`--`」行 + §9 改动 2.2 |
| U-JE3 | 表达式 #4 | 静态上下文里的静态字段读取 | 部分覆盖 | §4 表达式面表（补一行）+ §9 |
| U-JE4 | 表达式 #12 | 泛型 / 菱形构造器的 `calleeSig` | 部分覆盖 | §4「`new T(...)`」行 + §9 |
| U-JE5 | 表达式 #22 | lambda 签名元数 vs CALL 实参数 | 部分覆盖 | §10（IR-P 细则）+ §4「**lambda**」行 |
| U-JS2 | 语句 #5 | 静态初始化块与静态字段初始化器同名 | 部分覆盖 | §3「静态初始化块」行 |
| U-JS5 | 语句 #24 | 字段修饰符与 `final` 局部量 | 部分覆盖 | §3 声明面表 + §8 IR 需求 |
| U-JE8 | 表达式 A2（byte/short 字面量窄化） | 字面量按声明上下文的**窄化** | **✅ 已补齐** | §4「**字面量按声明上下文的窄化**」行 + §9 改动 2.19 |
| U-JS9 | 语句 A 节（method: `strictfp`） | **方法**修饰符的**读取点** | **✅ 已补齐** | §3「**方法修饰符（`strictfp`/`native`/`abstract`/`synchronized`/`default`）**」行 + §9 改动 4.16 |

**合计**：**12 个未覆盖条目**（5 个完全未覆盖 + 7 个部分覆盖）—— **其中 2 条已在本轮补齐（U-JE8 / U-JS9）**，剩余 10 条待实施，涉及 **11 条台账行**（表达式 #1、#2、#4、#12、#22、#26 + 表达式 A2 的「byte/short 字面量窄化」；语句 #4、#5、#12、#20、#24 + 语句 A 节的「method: `strictfp`」—— 其中语句 #12 与表达式 #2 是同一条构念，在 C.1 里合并为条目 U-JE2 / U-JS3）。其余 **46 条**台账行（表达式 21 条 + 语句 25 条）都有可核对的落点。

> ⚠ **本轮扩展附录后的口径**：附录 A/B 已从「只覆盖审计 B 节的丢弃清单」扩展为「覆盖审计 **A 节**的**每一行**」（Java 表达式 97 条 + Java 语句 97 条 = 194 行）。这 194 行里 **KEPT** 85 条（去向=「保持现状（无需改动）」）、问题项 **109 条** —— 逐条都有去向；其中**新发现的 2 行**落点缺失，已记为本附录的 **U-JE8 / U-JS9**（见 C.1 / C.2）—— **这两条已在本轮补齐**。⇒ 「每个问题项都有去向」这个断言**在附录 A/B 的口径下成立**；附录 A/B 的 `⚠` 计数**已归零**。

> **口径声明**：本附录只做「审计台账 ⇒ 设计文档落点」的映射与核实，**未运行任何构建或测试**；全部结论来自本轮对 `plumb-tac/src/main/java/org/plumb/tac/translate/` 与 `plumb-parser/src/main/java/org/plumb/parser/` 的源码阅读与 grep 实测。仓库文件**未做任何修改**。

---

### C.4 未覆盖条目的补齐落点（本轮新增）

本节是 C.1–C.3 的**落地记录**：上表中**已并入正文**的条目都已按「**现状证据 → 完整 TAC 形态 → IR 需求 → 改动落点**」四件套**并入正文的对应小节**（不是只在附录里加一段说明）。C.1–C.3 保留为**审计当时**的原始结论与源码核实记录，**未做改动**；本节只回答「现在在哪里」。

> ✅ **U-JE8 / U-JS9 已在本轮并入正文**（2026-11-16）：它们是在把附录 A/B 从「只覆盖审计 B 节的丢弃清单」扩展到「覆盖审计 A 节的每一行」时才发现的，当时没有现成落点；本轮已补上 §4 的「**字面量按声明上下文的窄化**」行 + §9 改动 2.19，以及 §3 的「**方法修饰符…**」行 + §9 改动 4.16 ⇒ 下表这两行不再是「⚠ 待补」，附录 A/B 的 `⚠` 归零。

| 编号 | 正文落点（章节 + 表行 / 小节） | IR 需求 | §9 改动条目 | 需要新 IR 提案？ |
|---|---|---|---|---|
| U-JE2 / U-JS3 | **§5.3.1「模式守卫（`case <pattern> when <expr>`）」**（新增小节） | IR-C `TacGuard` + IR-I `TacCase.guards`（**均为既有**） | 3.12（语句侧）、3.13（表达式侧） | **否** |
| U-JS1 | §3「实例字段（带初始化器）」行 | IR-E `ownerType`（**既有**） | 4.11 | **否** |
| U-JS4 | §3「枚举常量」行 | 现有 IR 即可（`NEW.args`） | 4.12 | **否** |
| U-JE6 | **§1 基线**（新增「文法上限 = Java 21」段）+ §11 K11 / Q6 | —（解析器口径，不涉 IR） | —（依赖升级 + javadoc 同步） | **否** |
| U-JE1 | §4「`++`/`--`」行（读-算-写三段 + 写回） | 现有 IR 即可 | 2.2（范围从「字段目标」放宽到「所有赋值目标」） | **否** |
| U-JE3 | §4「**非限定字段读取（`translateName`）**」（**新增行**） | IR-E `ownerType`（**既有**） | 2.16 | **否** |
| U-JE4 | §4「`new T(...)`」行（`calleeSig` 用剥掉类型实参后的 FQN） | IR-P 命名规范（**既有**） | 2.17 | **否** |
| U-JE5 | §4「**lambda**」行 + **§8 #14**（IR-P 细则：元数分离） | IR-P 规范文本（**既有**，不是新字段） | 2.18 + §10 门禁 B10 | **否** |
| U-JS2 | §3「静态初始化块」行 + §3「静态字段（带初始化器）」行（区分名） | IR-E `memberKind`（**既有**） | 4.13 | **否** |
| U-JS5 | §3「**字段修饰符…与 `final` 局部量**」（**新增行**）+ **§8 #19** | **IR-E 细化**（`TacMemberRef.modifiers`）+ **IR-AL 细化**（`TacVar.isFinal`）⇒ **两个都不占新编号**；`static final` 的常量表是**前端内部**的（不进 IR） | 4.14、4.15 | **否**（两个**既有**提案的字段级细化） |
| U-JE8 | **§4「**字面量按声明上下文的窄化**（`byte b = 1` / `short s = 2` / `char c = 65`）」**（**新增行**） | IR-D 的值域 + IR-E 的声明类型（**均为既有**）⇒ **不占新编号** | **2.19**（新增） | **否** |
| U-JS9 | **§3「**方法修饰符（`strictfp`/`native`/`abstract`/`synchronized`/`default`）**」**（**新增行**） | IR-E `TacMemberRef.modifiers`（**既有承载**，与 U-JS5 同一处细化） | **4.16**（新增，与 4.14 共用同一个读点） | **否** |

**需要登记的字段级细化**（**不占新编号**，但要在 `01` 的 §IR-E 与 §IR-AL 段落各补一句说明）：① `TacMemberRef.modifiers : List<String>`（IR-E 的细化，与既有 `annotations : List<String>` 同风格；与 `04` 的 U-KT2 提出的**同一处**细化，两份文档**合并登记一次**即可）；② `TacVar.isFinal : boolean`（IR-AL 的细化，与 IR-AL 已提的 `TacVar.annotations` 同属 `TacVar` 的声明元数据）。**本方案未自行分配任何新编号。**

**两条待补条目的处置（已完成）**：U-JE8 已在 §4 表达式面表补「**字面量按声明上下文的窄化**」行 + §9 改动 **2.19**；U-JS9 已在 §3 声明面表补「**方法修饰符…**」行 + §9 改动 **4.16**（与 4.14 共用同一个读点）。两条都**不占新 IR 编号**（承载能力 IR-D 的值域、IR-E 的声明类型与修饰符槽位均已存在）⇒ 附录 A/B 的 `⚠` 已归零，§7 ③ 与门禁 G-5「Java 侧应当没有 drop」在这两点上不再落空。

**口径校正两处**（与 C.1/C.2 的原文并列，不改原文）：① **U-JE6**：审计「Java 22–25 的文件整体被丢弃」偏宽 —— 只有**真正用到 22+ 语法**的文件才在解析阶段失败（§1 基线已写明）；② **U-JE5** 的残留不是「捕获数算不算元数」的措辞问题，而是**位置错绑**：形参表把显式形参排在捕获之前（`ExpressionTranslator.java:1584-1592`）而创建点只传 `[null]+captures`（`:1626-1639`）⇒ 捕获值落到显式形参的槽位上 ⇒ 目标形态必须把**捕获移到显式形参之前**（§4「lambda」行 + §8 #14）。
