# 降级方案：Go 与 C#

> TAC IR v2 的**前端落地**方案，范围只有两条 sidecar 线：Go（`plumb-frontend/go-sidecar/`）与 C#（`plumb-frontend/csharp-sidecar/`）。
> IR 设计已定稿：`01-TAC-IR-扩展设计.md`（IR-A..IR-H）、`02-覆盖矩阵与不可支持项.md`（IR-I..IR-Q）。**本文不重新设计 IR**，只在确有必要时提出新的 IR 需求（§10）。
> 证据基线：`D:\work\tmp\audit\10-go.md`、`06-csharp.md`、`12-total-report.md`。本文所有 `file:line` 已逐条在仓库复核（与审计快照 HEAD 不同，行号以本文为准）。

**记号与编号约定**（全套设计文档共享）

| 记号 | 含义 | 归属 |
|---|---|---|
| `IR-A`..`IR-Q` | 主命名空间的补充 IR 提案 | `01`（已定稿，不动；`IR-O` 跳过） |
| `IR-AF`..`IR-AK` | **本文件新增**的补充 IR 提案（6 条，§10） | 本文件（`07`） |
| `IR-R`..`IR-Z` / `IR-AA`..`IR-AE` / `IR-AM`.. | 其它语言文档的提案段 | `05` / `04` / `06`（本文件不使用） |
| `K<n>` / `Q<n>` / `U<n>` | 风险 / 待拍板 / 无法完整支持的构念 | §14.1 / §14.2 / §13 |
| `P0-1`/`P2-6` | 审计优先级（全局共享） | `12-total-report.md` §6 |

**AST 列约定**：§5（声明面）与 §6（表达式面）每行给出**解析器节点名**——Go 用标准库 `go/ast`；C# 用 Roslyn 语法 API（`Microsoft.CodeAnalysis.CSharp`，`SyntaxKind` 以 `SyntaxKind.` 前缀给出）。**解析器里没有专属节点**的构念（修饰符 token、编译期上下文关键字、`SyntaxAnnotation` 等）明确标注——这类事实本身就是实现时的关键信息。§7 的控制流模板同样在正文中给出节点名。

---

## 1. 目标与非目标

**目标**：① **完整支持**——Go（133 行台账）与 C#（160 行台账）的每一条构念都给出**真正降级出来的 TAC 形态**（既有指令组合），不允许用「`HAVOC` + 记 drop」代替；② 降不下来的地方在 IR 留机器可读的 `TacDrop`（IR-B），精度按统一口径推导（IR-A）；③ 每条 `BRANCH` 带 `TacGuard`（IR-C），路径条件可从 CFG 推出；④ 成员种类/owner/类型声明进 IR（IR-E、IR-I、IR-N）；⑤ 数值宽度进 `TacValueDomain`（IR-D）。

**非目标**：不新增 instruction `Kind`、不新增 operand 变体（`plumb-tac/src/main/java/org/plumb/tac/frontend/TacFrontend.java:50-57` 明文禁止；`01` §0.2 已量爆炸半径）；不改 `TacType.Kind` 既有 15 值；不改引擎侧 checker 行为；不动 sink 目录；**不做保守降级方案**。

---

## 2. 两条线的现状差距

**Go 与 C# 不是同一个问题，修法也不能通用。**

| 维度 | Go 线 | C# 线 |
|---|---|---|
| 台账 / 丢弃率 | 133 行 / DROPPED 17（silent 8）/ **12.8%**（`12-total-report.md:218`） | 160 行 / DROPPED 80（silent 44）/ **50.0%**（`12-total-report.md:211`） |
| 病形 | **做错**：算术/比较/分支目标被换成别的语义，函数仍报 `SOURCE_FULL` | **没做**：整类成员、整块语句、整条异常流从未进 TAC |
| ① 算子 | 复合赋值退化成 `MOVE`（`translateAssign` 不看 `statement.Tok`，`go-sidecar/translator.go:815-872`，落点 `:852-853`）；`&^` 落到兜底 `return "ADD"`（`translator.go:2182-2222`，兜底 `:2221`） | 复合赋值落到兜底 `"ADD"`（`BinaryOperatorOf` 只认**表达式** kind，`csharp-sidecar/TacTranslator.cs:2057-2080`） |
| ② 声明面 | 类型声明不产生任何 TAC 产物（`translator.go:1025-1028`）；泛型类型参数被擦成 `UNKNOWN`（`types.go:96-99`） | dispatch 只有 4 个 case 且**无 `default`**：终结器/运算符/转换运算符/索引器/字段与属性初始化器/基类构造器初始化器/位置记录成员/主构造器/事件访问器**全部从未被访问**（`csharp-sidecar/ProjectSession.cs:398-428`）；特性一个都不读（全仓无 `GetAttributes`/`AttributeLists` 调用） |
| ③ 控制流 | `case 1, 2:` 只有第一个值可达——假分支跳到下一 case 的**体**而非**测试**（`translator.go:1507-1522` + `nextCaseLabel` `:1548-1553`）；`goto` 降成 `NOP`，其后语句仍执行（`translator.go:1891-1897`） | `do`/`checked`/`unchecked`/`unsafe`/`fixed`/带标签语句/`goto` 落到 `default:` 报告并**丢块体**（`TacTranslator.cs:239-315`，兜底 `:310-313`）；类型模式 `case int i:` 被忽略 ⇒ **总走 default**（`TacTranslator.cs:1042`） |
| ④ 闭包/异常 | 闭包按**值**捕获，写回方向丢失且不声明（`translator.go:445-459`；注释 `:480-488` 自认「TAC 没有引用」）；异常区域为零（`GoTacFrontend.java:666` 传 `List.of()`） | 局部函数捕获环境**完全丢失**（`ProjectSession.cs:572-591`；对比 lambda 的捕获传递 `TacTranslator.Closures.cs:147-163`）；`catch` 体在**正常路径**上（`TacTranslator.cs:768-807`），JVM 侧同样 `List.of()`（`CSharpTacFrontend.java:737`） |
| wire / mapper | **有** `effect`（`go-sidecar/protocol.go:240-245`）、`deferredCalls`、`startsGoroutine`；自有 `toInstruction` 已读 `effect`（`GoTacFrontend.java:711-720`） | **无** `effect`、无 `deferredCalls`（`csharp-sidecar/Protocol.cs:89-167`、`:195-237`）；自有 `toInstruction` **不读** `effect`（`CSharpTacFrontend.java:764-775`） |

⇒ Go 线要**逐条改写算子与控制流**（多为局部改动）；C# 线要**先补声明面与语句面 dispatch 的兜底**（一次补一整类成员），再谈控制流精度。

---

## 3. 完整覆盖原则与允许的例外

**五条判据**（同时满足才算完整支持）：① 语义在 TAC 里可表达（存在由既有 `Kind` 组合出的等价指令序列）；② 结构信息不丢，或丢得**可记账**（丢结构 ⇒ `drop(STRUCTURE)`；丢语义 ⇒ `drop(SEMANTICS)` + 精度 `SOURCE_DEGRADED`）；③ 真分支条件可写成 `TacGuard`；④ 声明身份可区分（`memberKind`/`ownerType`/`TacTypeDecl`）；⑤ 数值宽度可判定（`TacValueDomain`）。

**允许的例外只有四类根因**（与 `02` §3.1–§3.4 同源）：① **运行时决定**（Go `select` 就绪顺序、goroutine 交错、C# `dynamic` 绑定、Go typed-nil interface）；② **调度决定**（`lock` 争用顺序、协程恢复顺序）；③ **求解器能力**（IEEE-754 精确舍入、C# `decimal` 定点舍入）；④ **无运行时对应物**（Go `//go:embed` 的文件字节、C# 特性里的编译期常量值）。四类都必须给出保守形状 + 精度影响 + 「源码里没有这个事实」的理由（§13）。

**逐条被替代掉的错误做法**：❌ 复合赋值 → `MOVE`（`translator.go:852-853`）⇒ ✅ 读-算-写三步；❌ `&^` → `ADD`（`translator.go:2221`）⇒ ✅ `UNARYOP BITNOT` + `BINOP AND` 两步；❌ `do`/`checked` 整块丢（`TacTranslator.cs:310-313`）⇒ ✅ 块体照降 + 外层语义标记；❌ 特性 / Go struct tag 丢 ⇒ ✅ 进既有注解槽位（`TacFunction.java:123-126`、`:154`）；❌ `case int i:` 忽略（`TacTranslator.cs:1042`）⇒ ✅ `TypeTest` + 绑定 `MOVE`。

**精度口径统一（禁止各线各写）**：`SEMANTICS` drop ⇒ `SOURCE_DEGRADED`；`STRUCTURE` drop ⇒ `SOURCE_LOWERED`；仅 `VALUE` drop ⇒ `SOURCE_FULL`。⇒ **禁止**再用「问题条数 > 0 ⇒ LOWERED」派生精度（该派生使任何静默丢弃必然得到 `SOURCE_FULL`，总报告 §5.3）；两条线 mapper 均改为按 `drops` 推导。

---

## 4. wire 协议现状与两条线各自要改的字段

字段的**数据形状见 `01` §2 / `02` §2**，此处只列「IR 提案 → wire 字段 → 两条线现状 → 改什么」。

| IR 提案 | wire 字段 | Go 现状 | C# 现状 | 本方案的改动 |
|---|---|---|---|---|
| IR-A | `precision` | 两档（`go-sidecar/protocol.go:82-83`） | 两档（`csharp-sidecar/Protocol.cs:214-216`） | 接受 `"SOURCE_DEGRADED"`；mapper 改枚举解析（现 Go `:613-616`、C# `:725-728` 只认一个字面量） |
| IR-B | 函数 `drops` / 文件 `programDrops` | 无 | 无 | 新增 |
| IR-C | 指令 `guard`（仅 BRANCH） | 无 | 无 | 新增 |
| IR-D | 类型 `domain` | 无（`TypeRef` 只有 `kind/name/component/nullable`，`go-sidecar/protocol.go:122-157`） | 无（`csharp-sidecar/Protocol.cs:~25-53`） | 新增 |
| IR-E | 函数 `memberKind` / 指令 `ownerType` / 文件 `types` | 无 | 无 | 新增 |
| IR-F | 函数 `tryRegions` | 无；mapper `List.of()`（`GoTacFrontend.java:666`） | 无；mapper `List.of()`（`CSharpTacFrontend.java:737`） | 新增（含 `catchTypes`/`finallyExits`/`resourceVars`/`syntheticFinally`） |
| IR-G | 指令 `labelRef` | 无 | 无 | 新增 |
| IR-I | 函数 `branchRegions` | 无 | 无 | 新增 |
| IR-J | 复用 `ctorType`（THROW） | 已有（`protocol.go:221`） | 已有（`Protocol.cs:135-138`） | **无新字段**，只需 THROW 填它 |
| IR-K | 类型 `typeArguments`/`unionMembers`/`bound` | 无 | 无 | 新增 |
| IR-L | 指令 `effect:"SUSPEND"` + 函数 `resumePoints` | `effect` 只有 `DEFERRED`/`CONCURRENT`（`protocol.go:240-245`） | **wire 无 `effect` 字段** | Go：枚举加 `SUSPEND`；C#：**新增整个 `effect`** |
| IR-M | 函数 `criticalSections` | 无 | 无 | 新增 |
| IR-N | `TacTypeDecl.typeParameters`/`permittedSubtypes` | 无 | 无 | 新增 |
| IR-P | `calleeSig` | `owner.method/arity`（`go-sidecar/types.go:221-223`） | `owner.method/arity`（`csharp-sidecar/TypeMapper.cs:154-158`） | 两线改 `<ownerFqn>.<methodName>/<arity>`；arity 只数显式实参，接收者恒在 `args[0]` |
| — | `calleeParamTypes` | 无（`protocol.go:191-246`） | 无（`Protocol.cs:89-167`） | 新增（重载精确匹配的前提） |
| P0-6 | `annotations`/`annotationValues` | 无 | 无 | 新增；C# 读特性；Go struct tag 走**独立字段** `fieldTags`（tag 不是注解） |

**C# 不走共享 mapper 的影响**。共享 mapper 在 `plumb-frontend/src/main/java/org/plumb/tac/frontend/sidecar/SidecarTacMapper.java`；Go 与 C# 各有**私有副本**（`GoTacFrontend.java:681-754`、`CSharpTacFrontend.java:752-812`）。后果：① 改共享 mapper 对这两条线**零效果**——`SidecarTacMapper.java:144-147` 把 4 个注解槽位硬编码为空、`:150` 把 `tryRegions` 硬编码 `List.of()`、`:184` 读 `effect`，这三处修好后 Go 与 C# 仍什么都没拿到；② 两份私有 mapper **已经分叉**：Go 版读 `effect`（`GoTacFrontend.java:711-720`），C# 版不读（`CSharpTacFrontend.java:764-775`），同一协议版本下行为不同。⇒ 本方案要求两条线的 `toInstruction`/`toFunction` **各自实现全部字段**，字段集合与共享 mapper 对齐（验收门禁 G）。

---

## 5. 声明面方案

### 5.1 Go

| 构念 | 解析器 AST 节点（`go/ast`） | TAC 形态 | `memberKind` | 注解通道 | IR 能力 |
|---|---|---|---|---|---|
| package clause | `ast.File.Name`（`*ast.Ident`） | 无节点；作 `ownerFqn` 前缀进 callee key 与 `TacTypeDecl.fqn` | — | — | IR-P |
| **包可见性（`internal`）** | **没有专属节点**：是**导入路径**上的作用域规则（`…/internal/…` 只能被同一父目录树导入） | **本方案不实现包级可见性** ⇒ 引擎把 `internal` 包**当公开包分析**（过近似：不会漏报，只可能把不可达的包当成可达）⇒ 记一条 `drop(SEMANTICS, "pkg.visibility")` 让精度标注可见（现状：侧车完全没有可见性处理（审计记 `NOT FOUND`，`10-go.md:194`、`:270`）） | — | — | **不需新 IR**（取舍声明，见 §13 的 `U10`） |
| import（别名/点/空） | `ast.GenDecl{Tok: token.IMPORT}` → `ast.ImportSpec{Path, Name}` | 无节点；别名解析到真实 import path（现状正确） | — | — | — |
| **import 解析 / vendoring** | **没有专属节点**：是**构建期**事实（`vendor/` 目录树 + `vendor/modules.txt`） | **侧车不解析 `vendor/` 目录**：依赖必须由 **Java 侧在请求的 import 列表里点名**（带 export data 或源码目录）⇒ **被点名的 vendored 依赖照常降级**，未被点名的不存在于 TAC 里（现状：现状正是如此（审计 `10-go.md:193`：「a vendored dependency is resolved only if the Java side names it in the request's import list with export data or a source directory; the sidecar does not consult…」）） | — | — | **不需新 IR**（边界声明，见 §13 的 `U9`） |
| **Go 版本假设**（`go.mod` 的 `go` 指令 / 构建标签） | **没有专属节点**：是**构建期**事实（`go.mod` 的 `go 1.22` 指令；`//go:build` 标签，侧车已读，`session.go:1191-1202`） | 声明为**会话级假设** `goVersion`；影响点只有一处：**循环变量捕获语义**（pre-1.22 共享 cell / post-1.22 每轮新 cell，见 §6.1「闭包**按引用捕获**」行）；版本未知 ⇒ 记 `drop(SEMANTICS, "go.version")`；已知 ≥1.22 ⇒ 记 `drop(SEMANTICS, "go.loopVar.scope")`（**⚠ R20 实测更正**：见 §7.1 同名段 —— 「取哪一侧」的方向反了，而现状是**共享 cell**，不是 post-1.22） | — | — | **不需新 IR**（会话级假设，见 §6.1 / §7.1） |
| `const`/`var`（含分组） | `ast.GenDecl{Tok: token.CONST\|token.VAR}` → `ast.ValueSpec{Names, Values, Type}` | `TacOperand.Const`（现状正确，`translator.go:2127-2164`）；包级变量由合成 `STATIC_INITIALIZER` 承载 | `STATIC_INITIALIZER` | — | IR-E |
| `iota` | **没有专属节点**：是 `ast.ValueSpec.Values` 中的 `*ast.Ident{Name:"iota"}` | 由 `go/types` 求值成 `Const` | — | — | — |
| `func` / 方法 | `ast.FuncDecl{Name, Recv, Type}`；接收者是 `FuncDecl.Recv`（`*ast.FieldList`，首元素 `*ast.StarExpr` 或 `*ast.Ident`） | 一个 `TacFunction`；`ownerType` = 接收者元素类型 | `METHOD` | — | IR-E + **IR-AI** |
| `type` 声明 | `ast.GenDecl{Tok: token.TYPE}` → `ast.TypeSpec{Name, Assign, Type}` | — | — | — | IR-E |
| `type X struct` / `type X interface` | `ast.StructType{Fields}` / `ast.InterfaceType{Methods}` | `TacTypeDecl{fqn, fields, members}` / `TacTypeDecl{isInterface=true, members=方法集}`；`NEW` 用 `X` 作 `ctorType` | — | 字段级 struct tag | IR-E / IR-N |
| 别名 vs 定义 | **同一节点**，靠 `ast.TypeSpec.Assign.IsValid()` 区分 | 别名**不产生新 `TacTypeDecl`**；定义**产生新 `TacTypeDecl`**（`MyInt` ≠ `int`） | — | — | IR-E |
| 嵌入字段 | `ast.Field`（`len(Names)==0`） | `TacTypeDecl.fields` 记声明处字段；访问按嵌入链**逐级展开**（下） | — | — | **不需新 IR** |
| 泛型（类型参数+约束） | `ast.FuncDecl.Type.TypeParams` / `ast.TypeSpec.TypeParams`（`*ast.FieldList`）；约束 `*ast.InterfaceType` 或 `*ast.UnaryExpr{Op: token.TILDE}` | `TacTypeDecl.typeParameters`（含约束）+ 实例化点 `TacType.typeArguments` | — | — | IR-N / IR-K |
| `init()` | `ast.FuncDecl{Name.Name=="init"}` | 并入包初始化器（现状正确，`session.go:295-302`、`:377-388`） | `STATIC_INITIALIZER` | — | IR-E |
| 函数字面量（闭包） | `ast.FuncLit{Type, Body}` | 合成 `TacFunction`，捕获作前导形参 | `CLOSURE`（**IR-AG**） | — | IR-E |
| struct tag | **没有专属节点**：`ast.Field.Tag`（`*ast.BasicLit`，反引号字符串，须用 `reflect.StructTag` 解析） | `TacTypeDecl.fieldAnnotations` 或独立 `fieldTags` | — | **独立字段** | wire 字段（IR 不需新类） |

**嵌入字段提升的完整降级（不需新 IR）**：现状把提升字段拍平成接收者自己的字段名、指令上又无 owner，于是 `d.ID` 与 `d.Base.ID` 落进两个桶（`translator.go:2266-2280` 只写 `FieldName`）。改为按嵌入链逐级展开：`FIELDLOAD t1 = d.Base`（ownerType=Derived, fieldName=Base）→ `FIELDLOAD t2 = t1.ID`（ownerType=Base, fieldName=ID）。⇒ 同一份存储只有一个键（`Base`+`ID`），两条访问路径自动汇合。依赖 **IR-E 的 `ownerType`**。

### 5.2 C#

`ProjectSession.cs:398-428` 的 dispatch 改成**穷尽 switch + `default` 发 drop**，补齐下列成员种类：

| 构念 | 解析器 AST 节点（Roslyn `Microsoft.CodeAnalysis.CSharp`） | TAC 形态 | `memberKind` | 注解通道 | IR 能力 |
|---|---|---|---|---|---|
| class / struct / interface / record | `ClassDeclarationSyntax` / `StructDeclarationSyntax` / `InterfaceDeclarationSyntax` / `RecordDeclarationSyntax` | `TacTypeDecl`（`isInterface`/`isRecord` 区分） | — | 类型级特性 → `TacTypeDecl.annotations` | IR-E |
| **泛型类型参数 + 约束 + 变型** | `TypeParameterListSyntax` → `TypeParameterSyntax`（`VarianceKeyword` = `in`/`out`）/ `TypeParameterConstraintClauseSyntax`（`where T : IDisposable`、构造器约束、notnull 约束） | 类型参数进 `TacTypeDecl.typeParameters`（IR-N）；**约束**进同一条目的 `bound`（IR-N 已登记）；**变型**进同一条目的 `variance`（`INVARIANT`/`COVARIANT`/`CONTRAVARIANT`）——**这是 IR-N 的字段扩展，不新增 IR 编号**；调用点的**实参替换**按变型方向决定是否合法：`out` ⇒ 实参须是形参类型的**子类型**（协变），`in` ⇒ 须是**父类型**（逆变），`invariant` ⇒ 须**相等**；不满足 ⇒ 记 `drop(TYPE, "variance")`（现状：类型实参按设计擦除（`TypeMapper.cs:99-108`）；**变型与约束从未被读**（审计 `06-csharp.md:38`：「Never read (`in`/`out` on type params)」、`:39`：「Never read; `where T : IDisposable` is a real security fact and is invisible」）） | — | — | IR-N（`typeParameters` + `bound` + **变型字段扩展**） |
| enum / delegate | `EnumDeclarationSyntax` / `DelegateDeclarationSyntax` | `TacTypeDecl{isEnum=true}` + 成员常量 / 委托类型 + `Invoke` 成员 | — / `DELEGATE_INVOKE` | — | IR-E |
| event | `EventFieldDeclarationSyntax`（字段形式）/ `EventDeclarationSyntax`（带访问器）；访问器 = `AccessorDeclarationSyntax` | `add_X`/`remove_X` 两个访问器 + 字段 | `EVENT_ACCESSOR`（**IR-AG**） | — | IR-E + IR-AG |
| 字段 | `FieldDeclarationSyntax` → `VariableDeclaratorSyntax` | `FIELDSTORE`/`FIELDLOAD` 带 `ownerType` | — | 字段级特性 → `fieldAnnotations` | IR-E |
| **通过裸标识符写字段**（`X = x;`，出现在构造器体 / setter 体里） | `IdentifierNameSyntax` → `GetSymbolInfo(identifier).Symbol` 是 `IFieldSymbol` | 现状 `TacTranslator.cs:391-417` 的 `IdentifierNameSyntax` 分支**只认 `ILocalSymbol`（`:394`）与 `IParameterSymbol`（`:404`）** ⇒ 字段落进 `:414` 的 `Report("UNHANDLED_NODE", "the assignment's target could not be resolved")` ⇒ **构造器与 setter 里的字段初始化（秘密落地的地方）在 TAC 里从未发生**（审计 B18：观测到 `Point.<init>`、`BaseThing.<init>`、`ExprWithBody.set` 都没有 —— 审计当时这两个构造器的**函数名**是 `.ctor`，现已改成 `<init>`，见本文件 §5.2「构造器 / 构造器链」行）。完整形态：加 `IFieldSymbol` 分支 ⇒ `FIELDSTORE dest=this.X lhs1=value ownerType=<声明类型 FQN>`；`static` 字段的 receiver 换成类型占位；`const`/`readonly` 字段记 `VALUE` 级 drop | — | — | IR-E（`ownerType`） |
| 自动属性 / `init` 访问器 | `PropertyDeclarationSyntax` → `AccessorDeclarationSyntax`（`SyntaxKind.GetAccessor`/`SetAccessor`/`InitAccessor`）；表达式体 = `ArrowExpressionClauseSyntax` | **合成 backing field**，`get_X`/`set_X` 变成对该字段的 `FIELDLOAD`/`FIELDSTORE`（现状访问器是空函数，taint 不回环）；`init` 标为初始化期写入 | `GETTER` / `SETTER` | — | IR-E |
| **`field` 关键字**（C# 13 支撑属性） | **没有专属节点**：是属性访问器体里的 `IdentifierNameSyntax`（`SyntaxKind.IdentifierName`），语义模型给出它指向该属性的**合成 backing field** | `field` ⇒ 对合成 backing field 的 `FIELDLOAD`/`FIELDSTORE`（`ownerType` = 声明类型 FQN）⇒ 与「自动属性」行的 backing field **同一个存储键**，两条访问路径自动汇合（现状：侧车只认 `ILocalSymbol`/`IParameterSymbol`（`TacTranslator.cs:391-417`）⇒ `field` 落进 `UNHANDLED_NODE` 分支（`:414-416`）⇒ 支撑属性的读写**从未发生**） | — | — | IR-E（`ownerType`） |
| 索引器 | `IndexerDeclarationSyntax` → `AccessorDeclarationSyntax` | `get_Item`/`set_Item` | `PROPERTY_ACCESSOR` | — | IR-E |
| 方法 / 重载 | `MethodDeclarationSyntax`（重载 = 多个同名节点，靠 `IMethodSymbol` 区分） | 一个 `TacFunction`；重载靠 `calleeParamTypes` 区分 | `METHOD` | 方法级特性 → `methodAnnotations` | IR-E + wire `calleeParamTypes` |
| **`virtual`/`override`/`abstract` 动态派发** | **没有专属节点**：都是 `SyntaxKind.*Keyword` 修饰符 token；override 关系 = 语义模型里 `IMethodSymbol.OverriddenMethod` | **不静态绑定**：对一个 `virtual` 方法的调用点，发**一组** `CALL`——静态声明 `<owner>.M/1` **加上该类型层次里所有已知 override**（每个 override 一个 `CALL`，`calleeSig` 用各自 owner 的 FQN ⇒ 引擎的 callee summary 天然取并集）⇒ 引擎看到的是「**可能**到达任一实现」；层次里可能存在的**外部 override**（本编译单元之外）用 `drop(SEMANTICS, "virtual.open-hierarchy")` 显式记账，并让该调用的返回值按**最坏情况**（可能来自未知实现 ⇒ 视作 tainted/HAVOC）处理（现状：派发是**静态绑定**的符号，无 vtable（审计 `06-csharp.md:68`：「Dispatch is the statically-bound symbol; no vtable」）⇒ override 的实现体在 TAC 里与基类调用**合流**，可能漏报） | — | — | **不需新 IR**（多条 `CALL` + `calleeSig` 区分，IR-P） |
| 扩展方法 | `ExtensionDeclarationSyntax`（C# 14）/ `MethodDeclarationSyntax` + `SyntaxKind.ExtensionKeyword` | 首形参即接收者 | `EXTENSION_FUNCTION` | — | IR-E |
| 运算符 / 转换运算符 | `OperatorDeclarationSyntax` / `ConversionOperatorDeclarationSyntax` | callee key 用规范化名 | `OPERATOR` | — | IR-E + IR-P |
| 默认接口成员 | `MethodDeclarationSyntax`（在 `InterfaceDeclarationSyntax` 内） | 普通 `METHOD`，`ownerType` = 接口 FQN | `METHOD` | — | IR-E |
| 局部函数 | `LocalFunctionStatementSyntax` | 合成 `TacFunction` + **捕获作前导形参**（现状完全没传，`ProjectSession.cs:572-591`） | `LOCAL_FUNCTION`（**IR-AG**） | — | IR-E + IR-AG |
| 静态成员 | **没有专属节点**：`SyntaxKind.StaticKeyword` 修饰符 token | `ownerType` = 声明类型；接收者槽放类型占位（现状正确，`TacTranslator.cs:1554-1555`） | 依成员种类 | — | IR-E |
| 构造器 / 构造器链 | `ConstructorDeclarationSyntax`；构造器链 = `ConstructorInitializerSyntax`（`: base(...)` / `: this(...)`） | 构造器 `calleeSig` = `<owner>.<init>/<arity>`；`: base(...)` 是一条 `CALL`。**构造器的函数名也是 `<init>`** —— `.ctor` 是 Roslyn 的**元数据名**，而 TAC 其余各处一律用 `<init>`（IR-P 的约定），两者不该并存 | `CONSTRUCTOR` | — | IR-E + IR-P |
| 静态构造器 | `ConstructorDeclarationSyntax` + `SyntaxKind.StaticKeyword` | 一个 `TacFunction` | `STATIC_INITIALIZER` | — | IR-E |
| 终结器 | `DestructorDeclarationSyntax` | 一个 `TacFunction` | `FINALIZER`（**IR-AG**） | — | IR-E + IR-AG |
| 字段/属性初始化器 | `VariableDeclaratorSyntax.Initializer`（`EqualsValueClauseSyntax`）/ `PropertyDeclarationSyntax.Initializer` | 构造器体内的一段 `FIELDSTORE` 序列 | `FIELD_INITIALIZER` | — | IR-E |
| 位置记录 / 主构造器 | `RecordDeclarationSyntax` + `ParameterListSyntax` / `ClassDeclarationSyntax.ParameterList`（C# 12） | 位置记录合成主构造器 + `get_X`/`set_X` + `Deconstruct`；主构造器合成函数 + 捕获形参 | `CONSTRUCTOR`/`GETTER`/`SETTER`/`METHOD` | — | IR-E |
| `partial` | **没有专属节点**：`TypeDeclarationSyntax.Modifiers` 含 `SyntaxKind.PartialKeyword` | 两半成员合并进同一 `TacTypeDecl` | 依成员种类 | — | IR-E |
| 特性 | `AttributeListSyntax` → `AttributeSyntax`（`Name`、`ArgumentListSyntax`） | 进 `annotations`/`annotationValues` 既有槽位（`TacFunction.java:123-126`、`:154`） | — | **本方案的主要通道** | **不需新 IR**（总报告 P0-6） |
| 顶层语句 | `GlobalStatementSyntax` | 合成 `<Main>$`（现状正确，`ProjectSession.cs:452-459`） | `METHOD` | — | IR-E |
| `readonly`/`volatile`/`sealed`/`new`/`required`/`ref struct` | **没有专属节点**：都是 `SyntaxKind.*Keyword` 修饰符 token | 修饰符，无运行时行为 ⇒ 不进 TAC | — | — | — |

---

## 6. 表达式面方案

### 6.1 Go

| 构念 | 解析器 AST 节点（`go/ast`） | TAC 形态（伪 TAC） | 现状（file:line） | IR 能力 |
|---|---|---|---|---|
| 复合赋值 `x += e`（11 算子） | `ast.AssignStmt{Tok: token.ADD_ASSIGN \| token.SUB_ASSIGN \| … \| token.AND_NOT_ASSIGN}` | `LOAD t1 = x` ; `BINOP t2 = t1 ADD e` ; `STORE x = t2`；`&^=` 走 `BITNOT`+`AND` | 退化成 `MOVE`（`translator.go:815-872`，落点 `:852-853`） | — |
| `&^` | `ast.BinaryExpr{Op: token.AND_NOT}` | `UNARYOP u = BITNOT b` ; `BINOP t = u AND c` | 兜底 `"ADD"`（`translator.go:2182-2222`） | —（`UnaryOperator.BITNOT` 已存在，`UnaryOperator.java:33`） |
| `&&`/`\|\|` | `ast.BinaryExpr{Op: token.LAND \| token.LOR}` | 短路分支链 `BRANCH a ? L_rhs : L_end ▸ Truthiness(a)` | 直线性 `BINOP`（`translator.go:2167-2181`） | IR-C |
| 单值断言 `x.(T)` / comma-ok 断言 | `ast.TypeAssertExpr{X, Type}`（`Type==nil` ⇒ `x.(type)`） | 单值：`UNARYOP t = CAST x (T)` + **失败路径 `THROW`**（`ctorType`=`*runtime.TypeAssertionError`）；comma-ok：再加 `INSTANCEOF ok = x (T)` | 无 THROW（`translator.go:2409-2422`）；`ok` 变 HAVOC（`translator.go:920-937`） | IR-J / IR-C |
| 多返回值 / map 双返回 / channel 双返回 / 裸 `return` / 变参 | `ast.AssignStmt{Lhs, Rhs}`（`len(Lhs)>1`）/ `ast.ReturnStmt{Results}`（`len==0`）/ `ast.CallExpr{Ellipsis != 0}` | **全部靠「调用的多个输出」**：CALL 的 `dest=v` + `resultDests[i]=第 i 个结果`；`out`/`ref` 语义用 `refOutDests`；裸 `return` 取具名结果当前值；变参在调用点打包 `NEWARRAY` | **多结果已做**（2026-11-23/24：Go 侧车发 `resultDests`，引擎按索引把每个结果的污点绑到调用方各自的变量，含异常出口 `return 0, err`）⇒ **而裸 `return`（具名结果当前值）与变参仍未做**：`translator.go:1077-1080` 仍发无值 RETURN、`translator.go:2677-2679` 仍不打包 | **IR-AH**（多结果那半已落地）＋ 具名结果 / 变参（仍未落地） |
| map 读写 `m[k]` | `ast.IndexExpr{X, Index}` | `CALL <go>.mapIndex/2` / `CALL <go>.mapStore/3`；内容流用合成 map 类型 + `FIELDLOAD/FIELDSTORE` | 内容不建模（`translator.go:991-1012`、`:2343-2356`） | IR-E（合成类） |
| 三索引切片 `a[1:2:3]` | `ast.SliceExpr{X, Low, High, Max, Slice3}` | `CALL <go>.slice/3(a,1,2)` + `CALL <go>.sliceCap/3(a,1,2,3)` | `Max` 未读（`translator.go:2359-2380`） | — |
| 函数值 / 方法值 / 方法表达式 | `ast.Ident` / `ast.SelectorExpr{X, Sel}`（`go/types` 的 `types.Selection` 区分 `MethodVal`/`MethodExpr`） | 合成闭包 + `MOVE` | 报 UNHANDLED（`translator.go:2284`、`:2302`、`:2305`） | IR-E |
| **把函数值传给注册型 API**（`http.HandleFunc("/x", h)` / `http.Handle` / `ServeMux.Handle`） | `ast.CallExpr{Fun: *ast.SelectorExpr{X: *ast.SelectorExpr{X: Ident("http"), Sel: Ident("HandleFunc")}, Sel: Ident(...)}}`；第二个实参是 `ast.Ident`/`ast.FuncLit` | 注册调用本身**必须可见**：`CALL <net/http>.HandleFunc/2 args=[t_url, t_handler]`，且 `t_handler` 是**合成闭包**（与上一行同一形状）⇒ 引擎据此知道「这个 handler 是可达的入口」 | **注册调用不可见**（`translator.go:2093`、`:2823`）⇒ handler 的体只有它是闭包时才看得见（`translator.go:407-478`）；作为具名函数传入时**注册关系丢失** ⇒ 入口点分类与「可达性」类规则都看不到它 | IR-P（callee 规范）+ IR-E（`ownerType`） |
| 闭包**按引用捕获** | `ast.FuncLit` + 体内 `ast.Ident`（捕获集由 `types.Info.ObjectOf` 求得） | 捕获变量提升为 **cell 对象**：`NEW cell = <go>.cell/0()` + `FIELDSTORE cell.value = x`；闭包体 `FIELDLOAD t = cell.value` / `FIELDSTORE cell.value = v`。**循环变量捕获按 Go 版本分两种形态**（版本来自 §5.1「Go 版本假设」行的会话级 `goVersion`）：**post-1.22（每轮新变量）** ⇒ 每轮循环迭代**新建一个 cell**（`NEW cell` 在循环体内）；**pre-1.22（共享变量）** ⇒ 整个循环**共用一个 cell**（`NEW cell` 在循环体外），所有闭包看到最后一次赋值 | 按值捕获（`translator.go:445-459`）⇒ 闭包**写**外层变量只写自己的副本；且**无版本假设**，pre-1.22 程序按 post-1.22 语义分析（审计 `10-go.md:180`、`:258`、`:324`） | **不需新 IR**（FIELDLOAD/FIELDSTORE + 会话级 `goVersion` 假设） |
| 取址 `&x` / 解引用 `*p` / `*p = v` | `ast.UnaryExpr{Op: token.AND}` / `ast.StarExpr{X}` / `ast.AssignStmt`（目标是 `*ast.StarExpr`） | `&x` → `NEW p = <go>.addr/1(x)`（cell）；`*p` → `FIELDLOAD t = p.value`；`*p = v` → `FIELDSTORE p.value = v` | CAST + 只报（`translator.go:2393-2406`、`:867-869`） | IR-E（合成 cell 类） |
| 内建 `append`/`copy`/`delete`/`min`/`max`/`len`/`cap` | `ast.CallExpr{Fun: *ast.Ident}`（`go/types` 判为 `*types.Builtin`） | `CALL <go>.xxx/N`；操作数与结果的污点关系由 callee summary 表达 | 无关系（`translator.go:2938-2940`） | — |
| `clear` | `ast.CallExpr{Fun: *ast.Ident}`（`types.Builtin`） | `UNARYOP t = CLEAR e`（Go 语义是**移除**元素 taint） | 普通调用，方向相反 | **IR-AJ** |
| `make`/`new`/`panic`/`recover` | `ast.CallExpr{Fun: *ast.Ident}`（`make` 的类型实参在 `Args[0]`；`panic`/`recover` 是 `types.Builtin`） | 切片：`NEWARRAY t = T(n)`，容量进 `Lhs2`（`TacInstruction.java:194-195`）；map/chan：`NEW t = <go>.makeMap/0>` / `<go>.makeChan/0>`，容量与方向进 `TacTypeDecl`；`new(T)` = `NEW t = T` + callee key `<init>/0`（现状正确，`translator.go:2634-2648`） | 容量丢（`translator.go:2732-2754`）；身份不建模（`:2756-2774`） | IR-E |
| 类型转换 `T(x)` | `ast.CallExpr{Fun: *ast.Ident}`（`go/types` 判为 `types.Conversion`） | `UNARYOP CAST`（刻意不建 CALL，现状正确，`translator.go:2612-2626`） | — | IR-Q（引擎侧） |
| 复合字面量（键/位/嵌套） | `ast.CompositeLit{Type, Elts}`；键控元素 = `ast.KeyValueExpr{Key, Value}` | 键控：`NEW` + 逐字段 `FIELDSTORE`；**位置式**按字段序绑定；嵌套递归 | 位置式只报（`translator.go:2479-2481`） | — |
| 字面量 / `nil` | `ast.BasicLit{Kind: token.INT \| token.FLOAT \| token.IMAG \| token.CHAR \| token.STRING}` / `ast.Ident{Name:"nil"}` | `TacOperand.Const`；`nil` → `CONST NULL` | 现状正确（`translator.go:2070-2072`、`:2101-2124`） | — |
| 虚数 literal `3i` | `ast.BasicLit{Kind: token.IMAG}` | TAC 无复数类型 ⇒ `UNINTERPRETED` + `drop(VALUE)` | 只报（`translator.go:2107-2112`） | IR-B |
| 无隐式转换 / 数值模型 | **没有专属节点**：是 `go/types` 的性质（`types.Basic` + `types.Identical`） | 每处转换显式 `CAST`，窄化溢出**回绕**；`int` → `INT` + `domain=Integral(bits,signed)`，bits 由目标平台决定 | `int` 硬编 32 位（`types.go:119-129`） | IR-D + IR-Q |
| `unsafe.Pointer` / `reflect` 读写 | `types.Basic`（`types.UnsafePointer`）/ `ast.CallExpr`（`reflect` 包） | 指针 = CLASS(`unsafe.Pointer`) + 指针语义；反射 = `UNINTERPRETED`（黑盒）+ `drop(VALUE)` | 仅名字（`types.go:144-145`）；普通调用（`translator.go:2603-2712`） | IR-E / IR-B |

### 6.2 C#

| 构念 | 解析器 AST 节点（Roslyn `Microsoft.CodeAnalysis.CSharp`） | TAC 形态（伪 TAC） | 现状（file:line） | IR 能力 |
|---|---|---|---|---|
| 复合赋值 `x *= e` | `AssignmentExpressionSyntax`（`SyntaxKind.MultiplyAssignmentExpression` 等） | `LOAD t1 = x` ; `BINOP t2 = t1 MUL e` ; `STORE x = t2`（`BinaryOperatorOf` 须扩到赋值 kind） | 兜底 `"ADD"`（`TacTranslator.cs:2057-2080`） | — |
| `??` / `??=` / `?.` / **`?[]`** | `BinaryExpressionSyntax`（`SyntaxKind.CoalesceExpression`）/ `AssignmentExpressionSyntax`（`SyntaxKind.CoalesceAssignmentExpression`）/ `ConditionalAccessExpressionSyntax` / `ConditionalElementAccessExpressionSyntax` | `??`：`BRANCH a ? L_val : L_alt ▸ Truthiness(a)`；`??=` 真分支额外发 `STORE`；`?.` 现状分支 + merge，补 `guard`；**`?[]`**（条件元素访问，`a?[i]`）与 `?.` **同形**：接收者非空 ⇒ `L_ok: ARRAYLOAD t = a[i]`；为空 ⇒ `L_null`（结果为 null），两路 merge ⇒ `BRANCH ▸ Truthiness(a)` + `TernaryRegion` | `??` 映射 `ADD`（`TacTranslator.cs:2078`）；`?.` 已对（`:1727-1765`）；**`?[]` 整块未降级**（`TacTranslator.cs:1292-1295` 的兜底路径） | IR-C |
| `is T` / `is not T` / `as T` | `IsPatternExpressionSyntax`（`PatternSyntax`）/ `BinaryExpressionSyntax`（`SyntaxKind.AsExpression`） | `is`：`INSTANCEOF t = x (T)`，`not` 用 `TypeTest.negated`；`as`：`UNARYOP t = CAST x (T)` + `NULL_CHECK t`（失败返回 null，不抛异常） | `not` 变 `is object` 恒真（`TacTranslator.cs:1214`）；`as` 落入 binary case，无 BINOP（`:1142`） | IR-C |
| switch 表达式 | `SwitchExpressionSyntax` → `SwitchExpressionArmSyntax{Pattern, WhenClause, Expression}` | 分支链 + merge temp + **每 arm 末尾 `JUMP L_end`** + `TacBranchRegion{kind=SWITCH, exhaustive, missingCases}` | 整个表达式消失（`TacTranslator.cs:1292-1295`） | IR-I + IR-C |
| 常量模式 / 关系模式 | `ConstantPatternSyntax` / `RelationalPatternSyntax` | 常量：`BINOP t = disc EQ 1` ; `BRANCH ▸ Compare(disc, EQ, 1)`；关系：`BINOP t = disc GT 3` ; `BRANCH ▸ Compare(disc, GT, 3)` | 常量已对（`TacTranslator.cs:1042`）；关系未降级 | IR-C |
| 列表模式 / 属性模式 | `ListPatternSyntax` / `PropertyPatternSyntax`（`Subpattern`） | 列表：长度守卫 `Compare(len,EQ,3)` + 逐元素 `Membership`（**合取**）；属性：`FIELDLOAD t = x.Length` ; `BINOP c = t GT 3` ; `BRANCH ▸ Compare(t, GT, 3)` | 均未降级（`TacTranslator.cs:1210-1219`） | **IR-AF** / IR-C（`ownerType` 定归属） |
| `and`/`or`/`not` 模式 / `when` 守卫 / 模式绑定 | `BinaryPatternSyntax`（`SyntaxKind.AndPattern`/`OrPattern`/`NotPattern`）/ `WhenClauseSyntax` / `DeclarationPatternSyntax` | `not` → `TypeTest.negated=true`；`and`/`or` → 合取/析取；`when` = case 体前第二条 `BRANCH ▸ Compare(...)`；绑定 = `MOVE i = x`，`TacBinding` 记 name/type/sourceType | `and`/`or` 未建模（`TacTranslator.cs:1210-1219`） | **IR-AF** / IR-C / IR-I |
| `default(T)` / `typeof(X)` / `nameof(x)` / `sizeof(T)` | `DefaultExpressionSyntax` / `TypeOfExpressionSyntax` / `InvocationExpressionSyntax`（`nameof` 是上下文关键字，靠语义模型识别）/ `SizeOfExpressionSyntax` | `default`：值类型 → `CONST`（0/false），引用类型 → `CONST NULL`；`typeof` = `MOVE t = CONST("System.Int32")`（**不发 `NEW`**）；`nameof` = `MOVE t = CONST("x")`；`sizeof` = `MOVE t = CONST(8)`（按目标平台常量表） | `default` 一律 NULL（`TacTranslator.cs:1240-1241`）；`typeof` 发 `NEW`（幻影分配，`:1243-1249`）；`nameof` 是 HAVOC（`:1579-1589`） | IR-D（sizeof） |
| 插值字符串 `$"{a}"` | `InterpolatedStringExpressionSyntax` → `InterpolatedStringTextSyntax` / `InterpolationSyntax` | `CALL <cs>.string.Concat/N(...)`（格式/对齐子句进实参个数）；污点取并集 | 丢格式（`TacTranslator.cs:1251-1281`） | — |
| `stackalloc`/`new T[n]`/集合表达式/元组/解构/`with` | `StackAllocArrayCreationExpressionSyntax` / `ArrayCreationExpressionSyntax` / `CollectionExpressionSyntax` / `TupleExpressionSyntax` / `DeclarationExpressionSyntax` / `WithExpressionSyntax` | `NEWARRAY t = T(n)` + 逐元素 `ARRAYSTORE`；元组 = 合成元组类 + `NEW` + `FIELDSTORE`，解构 = 逐字段 `FIELDLOAD`；`with` = `NEW t = <copy>` + 逐字段 `FIELDLOAD`/`FIELDSTORE` | 全部未降级（`TacTranslator.cs:1292-1295`）；`NEWARRAY` 从不发（kind 已存在，`TacInstruction.java:53`） | IR-E |
| 对象初始化器 / 集合初始化器 | `ObjectCreationExpressionSyntax.Initializer` → `InitializerExpressionSyntax`（`AssignmentExpressionSyntax` / `CollectionInitializerExpressionSyntax`） | 对象：**逐个 setter 调用** `CALL <owner>..set_P/1`；集合：逐元素 `CALL <owner>..Add/1` | 只报（`TacTranslator.cs:1697-1699` + `:414-416`）；静默跳过（`:1697`） | IR-E |
| 方法组 `Action h = Console.WriteLine;` | `IdentifierNameSyntax` / `MemberAccessExpressionSyntax`（语义模型给出 `IMethodSymbol` 且上下文不是调用） | **不发 CALL**；改为合成零参转发闭包 + `MOVE h = closure` | 发 CALL（`TacTranslator.cs:1546-1557`） | IR-E |
| `ref`/`out`/`in` / `params T[]` / 可选与命名实参 | **三者都没有专属节点**：是 `ParameterSyntax` 的 `RefKindKeyword`（`SyntaxKind.RefKeyword`/`OutKeyword`/`InKeyword`）与 `ParamsKeyword` 修饰符；调用点 = `ArgumentSyntax`（`RefOrOutKeyword`、`NameColon`）、可选实参 = `ParameterSyntax.Default` | `out`/`ref`：`CALL f(...)` + `refOutDests` 指回调用点变量；`params`：调用点 `NEWARRAY` + `CALL f(arr)`；可选/命名实参：调用点**补齐**省略的默认实参、命名实参按形参序重排 | **`out`/`ref` 已做**（2026-11-24）：调用点识别 `RefOrOutKeyword` 为 `OutKeyword`/`RefKeyword`，产出与 `args` 等长平行的 `refOutDests`（`TacTranslator.cs:2840`/`:2895`），`in` 排除在外 ⇒ **而 `params` / 可选 / 命名实参仍未做**（`NameColon` 未读 ⇒ 实参与形参可能错位；`params` 未打包） | **IR-AH**（`out`/`ref` 已落地）＋ IR-AR / IR-AC（命名 / 可选 / 变参仍未落地） |
| `dynamic` / `decimal` / `nint`,`nuint` / `T?`（引用）/ `int?` | `dynamic` **没有专属节点**（`IdentifierNameSyntax`，靠语义模型 `TypeKind.Dynamic`）；`decimal`/`nint`/`nuint` = `PredefinedTypeSyntax`；引用 `T?` = `TypeSyntax` 上的 `NullableAnnotation`（**是 `SyntaxAnnotation`，不是节点**）；值类型 `int?` = `NullableTypeSyntax` | `dynamic` = `UNKNOWN` + `domain=Dynamic`，调用发 `UNINTERPRETED`；`decimal` = `DECIMAL` + `FixedPoint(128, scale)`；`nint`/`nuint` = **INT/LONG** + `Integral(64,signed)`；`T?` 引用**不映射**到 `nullable`（维持 `TypeMapper.cs:15-21` 与 `SourceLanguage.java:210`）；`int?` = `INT` + `nullable=true`，`HasValue` → `INSTANCEOF` | 假类名 `dynamic`（`TypeMapper.cs:73`）；`decimal` 只给 Kind（`:49`）；`nint` → `System.IntPtr`（一个类）；`int?` 擦除为 UNKNOWN | IR-D |
| `checked`/`unchecked` / `unsafe`/指针 / LINQ 查询 | `CheckedStatementSyntax` / `UncheckedStatementSyntax` / `UnsafeStatementSyntax` + `PointerTypeSyntax` / `QueryExpressionSyntax` | `checked` 块体照降 + 按 `domain` 判溢出抛 `OverflowException`，`unchecked` 为回绕；指针读写用 `FIELDLOAD`/`FIELDSTORE` on 指针 cell；LINQ = 方法调用链 `CALL Select/2`、`Where/2`、`ToList/0`（真实方法名） | 整块丢（`TacTranslator.cs:239-315`）；指针存变数组存；`QueryExpression` 未降级（`:1292-1295`） | IR-D / IR-E / IR-P |
| 字面量（数值/字符/字符串/原始/verbatim/u8） | `LiteralExpressionSyntax`（`SyntaxKind.NumericLiteralExpression`/`StringLiteralExpression`/`CharacterLiteralExpression`） | `TacOperand.Const` | 现状正确（`TacTranslator.cs:1343-1360`、`:1393-1429`） | — |
| **`Span<T>` / `ReadOnlySpan<T>` 索引器写** | `IndexerAccessExpressionSyntax`（`SyntaxKind.ElementAccessExpression` + `ITypeSymbol` 是 `Span<T>`/`ReadOnlySpan<T>`） | `Span<T>` = **合成类**（`TacTypeDecl{fqn="System.Span<T>"}`），持有一个 backing 数组；索引器**读** = `ARRAYLOAD t = span.data[i]`；索引器**写** = `ARRAYSTORE span.data[i] = v`（`ReadOnlySpan<T>` 只有读路径，写路径记 `drop(TYPE, "readonly-span")`） | 索引器**写**被丢：侧车报「the indexer 'this[]' has no setter」（`TacTranslator.cs:590-596`）⇒ 写进 `Span<T>` 的数据**从未出现在 TAC 里**（审计 `06-csharp.md:120`、`:187`、`:238`、`:295`） | IR-E（合成类 + `ownerType`） |

---

## 7. 控制流方案

### 7.1 Go

**`for` 三段式 / 无限** = `ast.ForStmt{Cond, Post, Body}`（现状方向正确，`translator.go:1157-1191`），补 `guard` 与 `labelRef`：`LABEL L_head(LOOP_HEAD)` → `<init>` → `LABEL L_cond` → `BRANCH c ? L_body : L_end ▸ Compare(cond, NE, 0)`（无限循环无此条）→ `LABEL L_body(LOOP_BODY_END)` → `<body>`（`continue` → `JUMP L_post`）→ `LABEL L_post` → `<post>` → `JUMP L_cond` → `LABEL L_end(LOOP_END)`。

**`if`/`else if`/`else`** = `ast.IfStmt{Init, Cond, Body, Else}`（现状正确，`translator.go:1124-1149`），补 `guard`：`BRANCH ▸ Compare(cond, NE, 0)`。

**`range`** = `ast.RangeStmt{Key, Value, Tok, X, Body}`：over slice/array = 显式下标循环，边界 `Compare(i, LT, len)`，`len` 由 `CALL <go>.len/1` 求出（现状正确，`translator.go:1309-1375`）；over map = 迭代协议用**真实方法名**（IR-P）：`CALL <owner>..Range/0` → `CALL <owner>..Next/0` → `MOVE k = <go>.mapKey/1(it)`、`MOVE v = <go>.mapValue/1(it)`（现状内容不建模，`translator.go:1386-1446`）；over channel = **循环体内**发 `CALL <go>.chanRecv/1(ch)`，退出条件 `Compare(ok, EQ, false)`（现状整块循环体被丢，`translator.go:1452-1465`）；over string = 按 rune 宽度展开为下标循环（现状按字节 ARRAYLOAD，`translator.go:1309-1375`）；over int（Go 1.22）= 计数循环（现状正确，`translator.go:1236-1284`）。

**Go 版本假设（全局声明，不只属于 `range`）**：本方案**在会话级声明 `goVersion`**（来源见 §5.1「Go 版本假设」行：`go.mod` 的 `go` 指令 / 构建标签）。它只影响**两处**语义：
1. **`range` over int**：`goVersion ≥ 1.22` 时 `for i := range n` 是计数循环（语义自 1.22 才有）；`goVersion < 1.22` 时该语法不存在 ⇒ 不出现。
2. **循环变量捕获**：`goVersion ≥ 1.22` ⇒ 每轮新 cell；`goVersion < 1.22` ⇒ 共享一个 cell（见 §6.1「闭包**按引用捕获**」行）。

**版本未知** ⇒ 在该会话记 `drop(SEMANTICS, "go.version")`（**版本是构建期事实，调用方才知道；不知道的时候，这条记账才成立**）。

**⚠ R20 实测更正了本段的两句话，方向与现状都跟原来写的相反。**

**更正一：「取哪一侧」的方向反了。** 原来这里写的是「取**更保守**的一侧（pre-1.22 共享 cell：所有闭包看到最后一次赋值 ⇒ 跨迭代的污点流被高估 ⇒ 可能多报、不会漏报）」。**而那个理由是反的**：共享 cell 用在 **≤1.22** 的程序上是**精确**的（程序本来就是这样）；用在 **≥1.22** 的程序上才会**少报** —— 早一轮的值被后一轮覆盖，早一轮的污点就没了。反过来，**per-iteration 模型用在 ≤1.22 的程序上是多报**（每个闭包都看见自己那一轮的值，而程序其实让它们都看见最后一次赋值）⇒ **per-iteration 才是保守的那一侧**。

**更正二：现状不是「按 post-1.22 静默分析」。** R20 的探针实测：侧车把 range 变量**声明一次**，循环体每次往**同一个槽**里写，闭包捕获的就是那个槽 ⇒ **侧车建模的是 pre-1.22 的共享 cell**（`walk` 的 local 只有一个 `item`，闭包的前导参数就是它）。原来写的「现状是按 post-1.22 静默分析 ⇒ 对 pre-1.22 程序是漏报方向」**不成立**：那个方向上侧车本来就是对的。

**⇒ 而按更正后的方向，这一轮记的是**：**版本未知** ⇒ 程序级 `drop(SEMANTICS, "go.version")`；**已知 ≥1.22** ⇒ 函数级 `drop(SEMANTICS, "go.loopVar.scope")`（`go.loopVar.scope` 是 R20 新增的 id，见 `14-实现结果与不可支持清单.md` §3.25）。

**⇒ 而「按版本分两种捕获形态」这一条本身还没有实现**（它是本表 §11.1 改动清单里的一条）：现在**两种版本都用共享 cell**，并把可能出错的那一处记下来。**这是一件独立的事，估计要一轮独立的侧车工作** —— 而按 R19 起的口径，写「估计」，不写「不需要」。

**`switch`（表达式）** = `ast.SwitchStmt{Init, Tag, Body}` → `ast.CaseClause{List, Body}`（现状假分支跳到下一 case 的**体**，`translator.go:1507-1522`）⇒ 修正为跳到下一 case 的**测试**；每个 case 的多个值合并成**一条** `BRANCH ▸ Membership(tag, [1,2])`（IR-C）；`case nil` 用 `Compare(x, EQ, NULL)` 而非 `INSTANCEOF` 打 `NULL` 类型（现状如此，`translator.go:1614-1621`）。`fallthrough` = `ast.BranchStmt{Tok: token.FALLTHROUGH}` ⇒ case 体末尾**不发** `JUMP`（IR-I 的 `fallThrough=true`，现状 `:1884-1890`）。**类型 `switch`** = `ast.TypeSwitchStmt{Assign, Body}` → `ast.CaseClause{List}`（`List` 元素是 `*ast.TypeAssertExpr`，`Type==nil` 即 `case nil`）⇒ `INSTANCEOF t = x (T)` + `BRANCH ▸ TypeTest(x, T, false)`，绑定变量 `MOVE v = x`（narrowing 记 `TacBinding.sourceType`；现状不窄化，`translator.go:1561-1663`）。**⇒ ⚠ R21 实测并改了这条（见 `14` §3.40）**：**链**那一半**已经落地**（每个子句一个测试标签、类型在子句内串联、default 的体是 no-match 的落点 ⇒ 假分支指向下一子句的**测试**而不是它的**体**），而**窄化**那一半**仍然没做**、`case nil` **仍然是 `INSTANCEOF(NULL)`**（那两条是本表的第 27 / 28 条，独立）⇒ ⇒ **而实测的缺陷比本表写的大**：不只「多个类型」，**每个**子句的测试都是死的、三子句时 **default 的体不可达** ⇒ ⇒ **而「分支条件的形状」并没有变**（每个类型仍是 `INSTANCEOF` + `BRANCH`）⇒ ⇒ **⇒ 规则匹配面没动**。三者结构进 `TacBranchRegion`。

**`select`** = `ast.SelectStmt{Body}` → `ast.CommClause{Comm, Body}`（`Comm` 是 `*ast.ExprStmt`（接收）或 `*ast.SendStmt`（发送））。**解析器里有节点，但「就绪顺序」不是解析器事实** ⇒ **保守展开为全部 case 可达**：`CALL ready1 = <go>.selectReady/1(ch1)` → `BRANCH ready1 ? L_case1 : L_test2 ▸ Truthiness(ready1)` → …；`default` 排链尾；两值接收的通道解析必须修好（现状解析失败致 case **不可达**且变量未绑定，`translator.go:1728-1739`）；记一条 `drop(SEMANTICS, "select.readiness")`。

**`defer`** = `ast.DeferStmt{Call}`（现状把调用放在 defer 语句处，`translator.go:1771-1827`）⇒ 改为**放在函数所有出口之前**：`L_def1: FINALLY_ENTER L_def1` → `CALL release(...)`（`deferredCalls` 侧表仍保留）→ `JUMP L_next_exit`。每个 `defer` 一个 `TryRegion{syntheticFinally=true, finallyExits=[全部出口标签]}`（IR-F）；Go 的 defer 是 LIFO 且 panic 时也执行 ⇒ `finallyExits` 必须含异常路径。实参在 defer 语句处求值（现状正确，`translator.go:1787-1796`），保留。

**⇒ ⚠ R21 实测后，这一段处方**没有**实施（见 `14` §3.39）**：把那条 CALL 移到所有出口，会**静默删掉 CWE-772**（`DeferredReleaseInLoopChecker` 的判据**就是**那条 deferred CALL 在不在循环里），而且出口放置还需要一个「defer 有没有被登记过」的可达性表示，否则早退路径上会多报一次。⇒ ⇒ **实测出来的损失只有一处读数**：CWE-832 把 Go 接受的程序报成「unlock of unlocked mutex」⇒ ⇒ **已在检查器那一层修掉**（deferred unlock 的配对 lock 可以取在函数体的任何地方）⇒ ⇒ **调用在流里的位置没变**，`go.defer.order` 那条 drop 仍在。

**`panic`/`recover`**：`panic` = `ast.CallExpr`（`panic` 是 `types.Builtin`）⇒ `THROW v` 且 `ctorType` = 值类型（IR-J，现状 `translator.go:2628-2632`）。`recover` = `ast.CallExpr`（`types.Builtin`），deferred 闭包里的 `recover()` 调用点降级为 `CATCH_HANDLER`（`ctorType`=`interface{}`，捕获一切），并在 deferred 闭包函数的 `TryRegion` 里声明 `catchTypes=["interface{}"]` ⇒ 「被 recover 的 panic」第一次可见。「`recover` 只在 deferred 函数里直接调用才有效」是**源码可判定**的 ⇒ 完整支持：出现在非 deferred 函数时记 `drop(SEMANTICS)`。

**`goto` 与标签** = `ast.BranchStmt{Tok: token.GOTO, Label}` / `ast.LabeledStmt{Label, Stmt}` ⇒ `JUMP L_goto` + `LABEL L_goto` + `labelRef={BLOCK, scopeId}`（IR-G）；前向 `goto` 让 CFG 出现环（CFG builder 已能处理回边）。`break`/`continue`/带标签 = `ast.BranchStmt{Tok: token.BREAK | token.CONTINUE, Label}`（现状正确，`translator.go:1870-1883`），补 `labelRef`：`break`→`LOOP_END`，`continue`→`LOOP_HEAD`。

**错误处理惯用法** = `ast.BinaryExpr{Op: token.NEQ}` + `ast.Ident{Name:"nil"}`：`if err != nil` = `BINOP t = err NE NULL` + `BRANCH ▸ Compare(err, NE, NULL)`（现状正确，`translator.go:2167-2179`）；`errors.New`/`fmt.Errorf("%w", e)` 建模成**带 cause 的 error 对象**：`NEW err = <fmt>.Errorf/1` + `FIELDSTORE err.cause = e` ⇒ `errors.Is`/`errors.As` 的 unwrap 关系可见（现状两者都是普通调用，`translator.go:2603-2712`）。

### 7.2 C#

**`switch` 语句** = `SwitchStatementSyntax` → `SwitchSectionSyntax` → `CaseSwitchLabelSyntax` / `CasePatternSwitchLabelSyntax` / `DefaultSwitchLabelSyntax`（现状只处理 `CaseSwitchLabelSyntax`，`TacTranslator.cs:1042`）⇒ 补 `CasePatternSwitchLabelSyntax` + `guard` + `TacBranchRegion`：`BRANCH c1 ? L_case1 : L_test2 ▸ Compare(disc, EQ, 1)` → `L_case1: <body> ; JUMP L_end` → `L_test2: INSTANCEOF t = disc (System.Int32)` → `BRANCH t ? L_case2 : L_test3 ▸ TypeTest(disc, Int32, false)` → `L_case2: MOVE i = disc ; <body> ; JUMP L_end`。`default` 排链尾；`exhaustive`/`missingCases` 取编译器诊断。**`switch` 表达式** = `SwitchExpressionSyntax` → `SwitchExpressionArmSyntax`：同一模板 + merge temp，且**每 arm 末尾必须 `JUMP L_end`**（否则落入下一 arm）。

**`try`/`catch`/`finally`** = `TryStatementSyntax` → `CatchClauseSyntax{Declaration, Filter}` / `FinallyClauseSyntax`（现状 catch 体在正常路径上，`TacTranslator.cs:768-807`）：`L_try: <try body> ; JUMP L_after_try` → `L_catch1: CATCH_HANDLER c1 = e1 (ctorType=System.IO.IOException)` → `<catch body> ; JUMP L_finally` → `L_finally: FINALLY_ENTER L_finally ; <finally body>` → `L_after_try:`；+ `TryRegion{tryStart, tryEnd, catchLabels, catchTypes, finallyLabel, finallyStart, finallyEnd, finallyExits=[正常出口, 每个 catch 出口, rethrow 出口]}`（IR-F），异常边由 CFG builder 依 `TryRegion` 建。**`catch (E e) when (f)`**：filter = `CatchClauseSyntax.Filter`（**解析器里有节点，但现状从未被读**，`TacTranslator.cs:775-798`）⇒ 降级为 catch 入口的一条 `BRANCH ▸ Compare(f, NE, false)`，为假时 `JUMP L_next_catch`。`throw` 表达式 = `ThrowExpressionSyntax` ⇒ `THROW`（现状未降级）。

**`using`/`await using`** = `UsingStatementSyntax` / `LocalDeclarationStatementSyntax` + `SyntaxKind.UsingKeyword` 修饰符 / `AwaitUsingStatementSyntax`（现状 `Dispose()` 从不调用，`TacTranslator.cs:1097-1108`）⇒ 编译成 `try/finally`：`L_try: <resource init> ; <body>` → `L_finally: FINALLY_ENTER L_finally ; CALL dispose() (calleeSig=<owner>..Dispose/0)`；+ `TryRegion{syntheticFinally=true, resourceVars=[res], finallyExits=[...]}`；`await using` 用 `CALL <owner>..DisposeAsync/0` + `Effect.SUSPEND`。

**`lock`** = `LockStatementSyntax{Expression, Statement}`（现状锁表达式都不求值，`TacTranslator.cs:302-308`）：`<求值 monitor → m>` → `CALL Monitor.Enter(m) (System.Threading.Monitor#Enter/1)` → `LABEL L_crit`（`criticalSections=[{label:L_crit, monitorExpr:m, kind:SYNCHRONIZED}]`）→ `<body>` → `LABEL L_crit_end` → `CALL Monitor.Exit(m) (System.Threading.Monitor#Exit/1)`；+ 函数级 `criticalSections`（IR-M）；`finally` 中的 `Monitor.Exit` 用 `TryRegion` 表达。

**`foreach`** = `ForEachStatementSyntax{Declaration, Expression, Statement}`（现状把集合绑给循环变量且无出口边，`TacTranslator.cs:943-990`）⇒ 迭代协议用**真实方法名**（IR-P）：`CALL it = <collType>#.GetEnumerator/0` → `L_head: CALL moved = <enumType>#.MoveNext/0` → `BRANCH moved ? L_body : L_end ▸ Compare(moved, EQ, true)` → `L_body: MOVE elem = <enumType>#.Current/0 ; <body> ; JUMP L_head` → `L_end: CALL <enumType>#.Dispose/0`。

**`await foreach`（异步枚举）** = `ForEachStatementSyntax` + `SyntaxKind.AwaitKeyword`（或 `AwaitForeachStatementSyntax`），集合类型是 `IAsyncEnumerable<T>` ⇒ **同一模板的异步变体**：`CALL it = <collType>#.GetAsyncEnumerator/0` → `L_head: CALL movedTask = <enumType>#.MoveNextAsync/0` + **`Effect.SUSPEND`**（挂起点）+ `resumePoints=[{L_head, SUSPEND}]`（IR-L）→ `L_body: MOVE elem = <enumType>#.Current/0 ; <body> ; JUMP L_head` → `L_end: CALL <enumType>#.DisposeAsync/0`。⇒ **挂起点、恢复点、枚举协议三者都可见**（现状 `await` 完全不可见、精度却停在 `SOURCE_FULL`，审计 `06-csharp.md:124`、`:182`、`:230`、`:290`）。`await using` 的异步释放同理走 `DisposeAsync`（见上条 `using`/`await using`）。

**`yield return`/`yield break`** = `YieldStatementSyntax`（现状整个迭代器体被丢，`TacTranslator.cs:239-315`）⇒ 编译成状态机——合成 `MoveNext` 函数，每个 `yield return` 是一个状态边界：`L_state_k: MOVE current = v ; Effect.SUSPEND ; resumePoints=[{L_state_k, SUSPEND}] ; RETURN true` ⇒ 复用 IR-L（`Effect.SUSPEND` 追加在枚举末尾 + `resumePoints`）。

**`async`/`await`** = `AwaitExpressionSyntax`（现状状态机被压平，`TacTranslator.cs:1164-1175`）⇒ `await e` → `CALL t = await e` + `Effect.SUSPEND`，恢复点记进函数级 `resumePoints`（IR-L）；`async` 方法返回类型保持 `Task<T>`。

**`do`/`while`/`for`** = `DoStatementSyntax` / `WhileStatementSyntax` / `ForStatementSyntax`：`do` 现状整块丢失 ⇒ 补 `DoStatementSyntax` case，模板为「先执行体，再测条件」（与 `while` 相反）；`while`/`for` 补 `labelRef`（`LOOP_HEAD`/`LOOP_BODY_END`/`LOOP_END`）。

**`goto`/带标签语句** = `GotoStatementSyntax` / `LabeledStatementSyntax`（现状连标签内的语句一起丢）⇒ `JUMP L` + `LABEL L` + `labelRef={BLOCK, scopeId}`（IR-G）。

**`checked`/`unchecked`/`unsafe`/`fixed`** = `CheckedStatementSyntax` / `UncheckedStatementSyntax` / `UnsafeStatementSyntax` / `FixedStatementSyntax`（现状整块丢）⇒ 块体**照常降级**；`checked` 额外记「块内算术溢出抛 `OverflowException`」（由 `domain` + checker 表达）；`unsafe`/`fixed` 块内指针访问按指针语义降级（见 §6.2）。

---

## 8. 类型面方案

| 源类型 | `TacType.Kind` | `domain` | 实参/可空 | 说明 |
|---|---|---|---|---|
| Go `bool` / `string` / `nil` | BOOLEAN / STRING / NULL | — | — | `types.go:111-112`、`:136-137`、`:142-143` |
| Go `int8/uint8`、`int16/uint16`、`int32/uint32` | BYTE / SHORT / INT | `Integral(8\|16\|32, signed)` | — | 现状无 domain（`types.go:113-118`） |
| Go `int`/`uint` | INT | `Integral(bits, signed)`，`bits` 由目标平台决定 | — | **现状硬编 32 位**（`types.go:119-129`）；侧车已知构建目标 ⇒ 宽度可定；未知时 `Dynamic()` + drop(VALUE) |
| Go `int64/uint64` / `float32/64` | LONG / FLOAT,DOUBLE | `Integral(64, signed)` / `Floating(24,8)`,`Floating(53,11)` | — | `types.go:130-135` |
| Go 切片 `[]T` / 数组 `[N]T` | ARRAY(T) | 取 T 的域 | — | `types.go:53-59`；数组长度丢失 |
| Go map `map[K]V` / chan `chan T` | CLASS(合成类型) | — | — | 内容由 FIELDLOAD/FIELDSTORE 表达；容量与方向进 `TacTypeDecl`（现状全丢，`types.go:72-88`） |
| Go 命名类型 `type MyInt int` | **CLASS**（不再等于 `int`） | 底层域 | — | 现状 `MyInt == int`（`types.go:61-70`） |
| Go 结构体 / 接口 / `error` | CLASS(fqn) | — | — | `TacTypeDecl` 给出字段表 / 方法集；`error` 决定多值降级口径（`types.go:23-27`、`:239-250`） |
| Go 类型参数 `T` | UNKNOWN | — | — | 现状 `types.go:96-99`；改进：`TacType.bound`（IR-K）给出约束 |
| C# `bool/byte/sbyte/short/ushort/char/int/uint/long/ulong` | BOOLEAN/BYTE/SHORT/CHAR/INT/LONG | `Integral(bits, signed)` | 值类型否 | 现状只给 Kind（`TypeMapper.cs:37-48`） |
| C# `float`/`double` / `decimal` | FLOAT,DOUBLE / DECIMAL | `Floating(24,8)`,`Floating(53,11)` / `FixedPoint(128, scale)`，scale ∈ 0..28 | 否 | `TypeMapper.cs:47-49` 现状只给 Kind |
| C# `string` / `T`（引用）/ `T?`（引用注解） | CLASS(fqn) | — | `typeArguments`（IR-K）；**是** | 引用类型一律可空（`TypeMapper.cs:15-21`）；现状泛型实参被擦除（`TypeMapper.cs:99-108`）；**不因注解改变可空性**（`SourceLanguage.java:210`） |
| C# **泛型型变的变型与约束** | 见 §5.2「泛型类型参数 + 约束 + 变型」行 | — | `typeParameters` + `bound` + `variance`（IR-N + 字段扩展） | 变型决定实参替换的**合法方向**（协变/逆变/不变）；约束决定**可用成员集**（`where T : IDisposable` ⇒ `T` 上有 `Dispose()` ⇒ 调用点必须能被看见）。**引擎侧必须消费**：`TacType.sameType` / 子类型判定要按变型方向放宽，否则泛型接口上的污点流会漏 |
| C# `int?`（`Nullable<int>`） | INT | `Integral(32,true)` | **是** | 值类型可空 = 真可空 ⇒ `TacType.nullable=true`；现状擦除为 UNKNOWN |
| C# `dynamic` / `nint`,`nuint` | **UNKNOWN** / **INT,LONG** | `Dynamic()` / `Integral(64,signed)` | 是 / 否 | 现状假类名 `dynamic`（`TypeMapper.cs:73`）与 `System.IntPtr`（整数变引用类型） |
| C# `void` / 枚举 / 委托 / `Span<T>` | VOID / CLASS(fqn) | 枚举取底层域 | `typeArguments`；是 | `TacTypeDecl{isEnum=true}`；`Span<T>` = 合成类 + backing 数组，索引器**写**目标形态见 §6.2「**`Span<T>` / `ReadOnlySpan<T>` 索引器写**」行（现状被丢，`TacTranslator.cs:590-596`） |

**数值模型的两条根本差异（必须进 IR，不能留在前端）**

| 维度 | Go | C# |
|---|---|---|
| 整数宽度 | `int` 平台相关（32/64）⇒ `Integral(bits,signed)`，bits 来自构建目标 | 全部定宽 ⇒ `domain` 精确 |
| 隐式转换 | **无**：`int32→int64` 必须显式 ⇒ 每处转换是 `UNARYOP CAST`，窄化溢出**回绕** | **有**（隐式数值转换 + 显式强制转换），窄化默认不检查（`unchecked`） |
| 浮点/定点 | `float64` 唯一精度档 | `float`/`double`/`decimal` 三档；`decimal` 是 128 位定点，**非** IEEE-754 |
| 溢出行为 | 定宽整数回绕 | `unchecked` 回绕；`checked` 抛 `OverflowException`（⇒ 可建模为 `THROW`） |
| 可空性 | `nil` 是**每个**类型的一个值，无编译期非空 | 引用类型无编译期非空（`SourceLanguage.java:210`）；值类型 `Nullable<T>` 可空 |

⇒ **IR-Q（引擎侧强制转换与相等语义规则表）是必需的**：Go 的接口比较、C# 的 `==` vs `Equals`、隐式窄化的溢出行为都不能由各 checker 自行假设。

---

## 9. 并发与同步方案

**判据**：能在**源码里判定**的事实 ⇒ 完整表达；由**运行时/调度器**决定的事实 ⇒ 保守建模 + 显式 drop。

| 构念 | 可完整表达 | 保守部分（运行时决定） | 形状 |
|---|---|---|---|
| Go `go f()` | 「该调用在另一执行流上跑」是源码事实 | 与其它指令的**交错顺序** | `Effect.CONCURRENT`（现状已有，`translator.go:1859`）+ 函数级 `startsGoroutine`；不建模 happens-before |
| Go `defer f()` | 全部：实参求值点、退出时执行、LIFO、panic 时也执行 | 无 | 移至所有出口 + `TryRegion{syntheticFinally=true, finallyExits=[全部出口]}`（IR-F） ⇒ ⇒ **⚠ R21 未实施**（`14` §3.39）：移到出口会**静默删掉 CWE-772**，且需要「defer 有没有被登记过」的可达性表示；**实参求值点现状就是对的**，而位置造成的唯一读数（CWE-832 误报）已在检查器那层修掉 |
| Go channel send/recv/close | 全部：有序点对点；close 后接收立刻返回零值 + `ok=false` | 无 | `<go>.chanSend/2` / `<go>.chanRecv/1` / `<go>.close/1` + `INSTANCEOF ok`（现状 `translator.go:1972-1980`、`:2938-2940`） |
| Go `select` | case 的**集合**与每个 case 的接收语义 | **哪个 case 就绪**（含随机选择） | 全部 case 可达 + `Truthiness(ready)` + `drop(SEMANTICS)` |
| Go `sync.Mutex`/`RWMutex`/`WaitGroup`/`Once` / 原子操作 | 临界区**范围**与「同一把锁」的关系；Add/Done 的**配对计数**；`Do` 的**唯一性位置**；原子操作的原子性（API 契约） | 谁先进入；实际完成时刻 | `criticalSections{kind=SYNCHRONIZED}`（IR-M）+ `Lock`/`Unlock`/`WaitGroup#Add`/`WaitGroup#Done`/`Once#Do` 调用；死锁类规则不报，配对由 checker 做；原子操作用 `kind=ATOMIC` |
| Go `context` 取消 / `errors.Is`,`errors.As` 的 wrapping | 「`Done()` 被关闭」是事实；`fmt.Errorf("%w", e)` 构造出**带 cause 的 error 对象** ⇒ unwrap 关系可见 | 取消发生的时刻 | `CALL context.Context#Done/0` + `#Err/0`，`Err() != nil` 走 `Compare`；`NEW err = <fmt>.Errorf/1` + `FIELDSTORE err.cause = e` |
| C# `lock (m) { }` | 临界区**范围**、monitor 对象、`Monitor.Enter/Exit` 调用 | 争用顺序 | `criticalSections{kind=SYNCHRONIZED}`（IR-M）+ 显式 `Monitor.Enter/Exit`；死锁/顺序类规则不报，原子性规则照报 |
| C# `async`/`await` / `IAsyncEnumerable` | 挂起点与恢复点（源码结构决定）；枚举协议（含**异步**枚举：见 §7.2「**`await foreach`（异步枚举）**」段） | 恢复**顺序**、与其它线程的交错 | `Effect.SUSPEND` + `resumePoints`（IR-L）；恢复后按「任意挂起点可能到达」合并；迭代协议用真实方法名（IR-P），异步变体用 `GetAsyncEnumerator`/`MoveNextAsync`/`DisposeAsync` |
| C# `Monitor.Wait/Pulse` / `Interlocked.*`/`Volatile.*` / `TransactionScope` / `Channel<T>` | 使用点、原子性（API 契约）、事务**范围**；`Channel<T>` 与 Go channel 同构 | 唤醒顺序；提交/回滚时刻 | `Monitor#Wait/2`；`criticalSections{kind=ATOMIC}`；`criticalSections{kind=TRANSACTION}`；`CALL System.Threading.Channels#WriteAsync/2` / `#ReadAsync/1` |

**边界一句话**：临界区范围、挂起点、原子性、锁对象**都可完整表达**；只有「谁先进入 / 谁先恢复 / 哪个 case 就绪」不可表达——这三类事实源码里没有，只能保守（全部候选路径可达 + 显式 drop）。

---

## 10. IR 需求

以下 6 条是本方案**确实需要**的新补充 IR 提案，编号占用本文件的 `IR-AF`..`IR-AK` 段（`IR-AL` 留空供追加）。其余全部用既有 `Kind` 与 IR v2 已提案的字段表达。

**IR-AF `TacGuard` 需要合取/析取组合子**
构念：C# 的 `and`/`or` 模式、`[1,2,3]` 列表模式、`is string { Length: > 3 }` 属性模式（属性测试与类型测试的合取）。
数据形状：`sealed interface TacGuard` 末尾追加两个 record：`AllOf(List<TacGuard>)`（合取）、`AnyOf(List<TacGuard>)`（析取）。
为什么现有 IR 不够：IR-C 的 `TacGuard` 只有叶子形态（`Compare`/`Truthiness`/`Membership`/`TypeTest`/`Always`）；`TacBranchRegion.cases[].guards` 是 `List<TacGuard>` 且语义是 **OR**（`02` §2-IR-I）。合取无处安放。消费方：Z3 桥（`AllOf`→`mkAnd`、`AnyOf`→`mkOr`）、模式匹配 checker。

**IR-AG `TacMemberKind` 需要追加 4 个成员种类**
构念：C# 终结器、事件 `add_`/`remove_` 访问器、局部函数；Go/C# 的合成闭包函数。
数据形状：末尾追加 `FINALIZER, EVENT_ACCESSOR, LOCAL_FUNCTION, CLOSURE`（枚举按名字序列化、末尾追加兼容，`IrPrecision.java:24-26` 同款约定）。
为什么现有 IR 不够：IR-E 的枚举只有 `METHOD/CONSTRUCTOR/STATIC_INITIALIZER/GETTER/SETTER/PROPERTY_ACCESSOR/OPERATOR/EVENT_HANDLER/FIELD_INITIALIZER/COMPANION_MEMBER/EXTENSION_FUNCTION`——**没有终结器**（C# 的 `~T()`）、**没有事件访问器**（`add_X`/`remove_X` 与 `EVENT_HANDLER` 语义不同：前者是订阅协议，后者是处理函数）、**没有局部函数**（捕获语义与成员不同）、**没有闭包**（合成函数，不是用户写的）。消费方：死代码 checker（读 `TacTypeDecl.members` 排除反射可达成员）、入口点分类（终结器不可作入口）。

**IR-AH `TacInstruction` 需要表达「调用的多个输出」与「按引用实参」**
构念：Go 多返回值、Go 的 `v, ok := m[k]` / `x.(T)` / `<-ch`、Go 的裸 `return`（具名返回值）、C# 的 `out`/`ref` 参数。
数据形状：`TacInstruction` 末尾追加两个可选字段：`List<TacOperand> refOutDests`（与 `args` 同序，非空元素表示该实参按引用传递、返回时写回该变量）、`List<TacOperand> resultDests`（与 `args` 同序，非空元素表示该实参位置的返回值）。
为什么现有 IR 不够：`TacInstruction` 只有一个 `dest`（`TacInstruction.java:193`）与一条 `args`（`:228`）。Go 的 `(T, error)` 返回、C# 的 `out` 写回、Go 的 comma-ok 全部无法表达——现状靠发 `HAVOC` + 降精度（`translator.go:928-937`、`:1103-1105`；C# 侧签名里没有任何 modifier 字段，`Protocol.cs:194-237`）。消费方：引擎的 callee summary（把 formal *i* 绑到 `resultDests`/`refOutDests` 而不是 `args[i+1]`）、方法摘要构建器。

**⇒ 2026-11-24：两半都已落地。** `resultDests`（`TacInstruction.java:278`）与 `refOutDests`（`:295`）两个字段都在 IR 里；Go 侧车发多结果、C# 侧车发 `out`/`ref` 实参（`in` 按契约只读，排除在外）；引擎侧消费它们。**⇒ 而「形参被写回了什么」不在 IR 里，在摘要里**：`FunctionSummary.paramWriteOps` / `paramWriteCauses`（`:180` / `:186`）⇒ ⇒ **⇒ 因为写回的值走的是「形参」这条路，不是「结果」这条路** ⇒ ⇒ ⇒ **⇒⇒ 而「写回值的污点从哪个形参流过来」与「返回值的污点从哪个形参流过来」是同一个问题 ⇒ ⇒ ⇒⇒ ⇒⇒⇒ ⇒ ⇒ ⇒ ⇒ ⇒ 落地记录见 `14` §3.48.10。**

**IR-AI 方法集归属：值接收者 vs 指针接收者**
构念：Go 的 `func (t T) M()` 与 `func (t *T) M()`——**方法集不同**，`T` 的方法集不含 `*T` 的方法。
数据形状：`TacMemberRef` 末尾追加 `MethodSetKind methodSet`（`VALUE`/`POINTER`/`BOTH`）；`TacTypeDecl` 里每个方法各一条 `TacMemberRef`。
为什么现有 IR 不够：IR-E 的 `TacMemberRef` 只有 `name/signature/kind/annotations/isStatic/isPublic/isSynthetic`，**没有接收者形态**。现状 Go 侧为不丢指针接收者，索引的是**指针**的方法集（`interfaces.go:127-149`），于是「值类型是否满足某接口」判不出来；C# 侧无此概念。消费方：接口实现解析（Go 的隐式满足关系，`interfaces.go:231-260`）、调用图的目标集合。

**IR-AJ `UnaryOperator` 需要追加 `CLEAR`（Go `clear`）**
构念：Go 1.21 的 `clear(m)`/`clear(s)`——把 map/slice 元素**清零**（Go 语义是**移除**元素的 taint）。
数据形状：`UnaryOperator` 末尾追加 `CLEAR`。
为什么现有 IR 不够：`UnaryOperator`（`UnaryOperator.java:30-38`）只有 `NEG/NOT/BITNOT/PRE_INC/PRE_DEC/POST_INC/POST_DEC/CAST`。现状 `clear` 降成普通调用 `<go>.clear/1`（`translator.go:2938-2940`），**语义方向相反**——引擎会以为结果与元素同 taint。消费方：污点传播（`CLEAR` 结果**不带**原元素 taint）、map/slice 状态更新。

**IR-AK `TacValueDomain` 需要「宽度未定的整数」这一形状**
构念：Go 的 `int`（平台相关）在**构建目标未知**时的诚实表达。
数据形状：`TacValueDomain` 末尾追加 `record PlatformDependentIntegral(boolean signed)`。
为什么现有 IR 不够：IR-D 的 `Integral(bits, signed)` 要求位宽**已知**；`Dynamic()` 会让该值完全不参与数值约束（`01` §2-IR-D 的 Z3 表），比「知道是整数但宽度未知」更弱。**是否真的需要：通常不需要**——侧车已知目标平台（`//go:build` 约束在 `session.go:1191-1202` 已求值），能给出确定位宽；**只在构建目标缺失时**退到 `PlatformDependentIntegral`。⇒ 兜底形状，优先级最低。

---

## 11. 改动清单

### 11.1 Go 线（`plumb-frontend/go-sidecar/` + `GoTacFrontend.java`）

| 文件 | 方法 / 位置 | 加什么 | IR 能力 |
|---|---|---|---|
| `protocol.go` | `Instruction`（`:191-246`）、`Function`（`:305-361`）、`TypeRef`（`:122-157`）、常量（`:82-83`） | 指令加 `guard`/`labelRef`/`ownerType`/`calleeParamTypes`，`effect` 加 `SUSPEND`；函数加 `memberKind`/`drops`/`tryRegions`/`resumePoints`/`criticalSections`/`branchRegions`；类型加 `domain`/`typeArguments`/`bound`；新增 `programDrops`/`types`/`fieldTags` | IR-B/C/D/E/F/G/I/K/L/M/N |
| `types.go` | `mapGoType`（`:33-106`）、`mapBasic`（`:109-153`）、`calleeSignature`（`:221-223`） | 补 `domain`；`int` 宽度按平台；命名类型 ≠ 底层类型；map/chan 走合成类型；callee key 改 `<ownerFqn>.<methodName>/<arity>` | IR-D、IR-E、IR-P |
| `interfaces.go` | `methodSetOf`（`:135-149`）、`implementationsOf`（`:231-250`） | **同时**索引值方法集与指针方法集并分开记录 | **IR-AI** |
| `translator.go` | `translateAssign`（`:815-872`）、`binaryOperator`（`:2182-2222`）、`translateBinary`（`:2167-2181`）、`translateStar`（`:2393-2406`）、`translateTypeAssert`（`:2409-2422`） | 按 `statement.Tok` 分派复合赋值；补 `*p = v` 的存储路径（现状 `:867-869` 只报）；删兜底 `return "ADD"` 改编译期报错；`&&`/`\|\|` 改分支链 + guard；解引用 → `FIELDLOAD`；断言失败 → `THROW` | —、IR-C、IR-J |
| `translator.go` | `translateSwitch`（`:1476-1545`）、`nextCaseLabel`（`:1548-1553`）、`translateTypeSwitch`（`:1561-1663`）、`translateSelect`（`:1665-1725`）、`selectChannel`（`:1728-1739`） | 多值 case 合并为 `Membership`；假分支指向**下一测试**；`case nil` 用 `Compare`；select 全部 case 可达 + guard + 两值接收的通道解析修好 | IR-B、IR-C、IR-I |
| `translator.go` | **⚠ R21 实测后改了这条处方（见 `14` §3.40）**：`translateTypeSwitch` 的**链** | **类型 switch 的链**（`:2219-2265`）**已经落地**：每个子句一个**测试标签**（`switch_test_N`）、**子句内的多个类型串成一条链**（`switch_value_N`）、**default 不是测试**（链越过它继续）而**它的体是 no-match 的落点** ⇒ ⇒ **⇒ 与 `translateSwitch` 同形**（值 switch 早就这么写，注释里写着「它以前指向**体**」）⇒ ⇒ **而「分支条件的形状」没变**（每个类型仍是 `INSTANCEOF` + `BRANCH`）⇒ ⇒ **⇒ 规则匹配面没动**；**窄化**与 **`case nil`** 两条**仍然没做**（本表第 27 / 28 条） | IR-C |
| `translator.go` | `translateDefer`（`:1771-1827`）、`translateGo`（`:1848-1865`）、`translateBranch`（`:1868-1899`）、`translateFuncLit`（`:407-478`）、`capturedVariables`（`:489-520`）、`translateSelector`（`:2263-2307`） | **⚠ R21 实测后改了这条处方（见 `14` §3.39）**：`defer` **不**移到所有出口 —— 移过去会**静默删掉 CWE-772**（`DeferredReleaseInLoopChecker` 的判据就是那条 deferred CALL 在不在循环里），而且还需要一个「defer 有没有被登记过」的可达性表示，否则早退路径上会多报；**实测出来的损失只有一处读数**（CWE-832 把 Go 接受的程序报成「unlock of unlocked mutex」），**已在检查器那一层修掉**（deferred unlock 的配对 lock 可以取在函数体的任何地方）；`goto` → `JUMP`+`LABEL`+`labelRef`；补 `labelRef`；捕获按引用（cell 对象化）+ `memberKind=CLOSURE`；**循环变量捕获按 `goVersion` 分形态**（pre-1.22 共享 cell / post-1.22 每轮新 cell，见 §6.1）；嵌入字段按链展开 + `ownerType`；方法值/表达式 → 合成闭包 | IR-E、IR-F、IR-G、**IR-AG** |
| `translator.go` | `translateReturn`（`:1077-1106`）、`translateMultiAssign`（`:893-938`）、`translateCall`（`:2603-2721`）、`translateCompositeLit`（`:2425-2503`）、`translateMake`（`:2722-2775`）、`resolveBuiltin`（`:2929-2945`） | 裸 `return` 取具名结果；第 2..n 结果走 `resultDests`；**解析失败时仍遍历实参**（现状 `:2604-2609` 直接 `havoc` 返回，实参子树全丢）；位置字面量按字段序绑定（现状 `:2479-2481` 只报）；容量进 `NEWARRAY.lhs2`；`clear` 用 `CLEAR` | **IR-AH**、**IR-AJ**、IR-B |
| `session.go` | 包级变量初始化（`:427-447`）、`init`（`:295-302`、`:377-388`）、`inlineSelected`（`:1191-1202`） | 多值初始化只发**一次**调用（现状发两次）；记录目标平台供 `int` 宽度用；**读 `go.mod` 的 `go` 指令得出会话级 `goVersion`**（供循环变量捕获形态用，见 §5.1「Go 版本假设」行）；**vendor 依赖必须由 Java 侧在 import 列表里点名**（侧车不解析 `vendor/`，见 §13 的 `U9`） | **IR-AH**、IR-D |
| `README.md` | `:570-586` 的「does not model」清单 | **重写**：逐条对应本方案；不再声称「none is silent」 | — |
| `GoTacFrontend.java` | `toFunction`（`:559-678`）、`toInstruction`（`:681-754`）、`toType`（`:807-837`） | 读全部新字段；`tryRegions` 不再 `List.of()`（`:666`）；精度按 `drops` 推导（`:613-616`） | 全部 |

### 11.2 C# 线（`plumb-frontend/csharp-sidecar/` + `CSharpTacFrontend.java`）

| 文件 | 方法 / 位置 | 加什么 | IR 能力 |
|---|---|---|---|
| `Protocol.cs` | `Instruction`（`:89-167`）、`Function`（`:195-237`）、`TypeRef`（`:~25-53`） | **新增整个 `effect` 字段**；加 `guard`/`labelRef`/`ownerType`/`calleeParamTypes`；函数加 `memberKind`/`drops`/`tryRegions`/`resumePoints`/`criticalSections`/`branchRegions`；类型加 `domain`/`typeArguments`/`bound` | 全部 |
| `ProjectSession.cs` | 声明面 dispatch（`:398-428`）、`TranslateLocalFunction`（`:572-591`） | 改穷尽 `switch` + `default` 发 drop；补终结器/运算符/转换运算符/索引器/字段与属性初始化器/基类构造器初始化器/位置记录/主构造器/事件访问器/委托；局部函数捕获作前导形参；**类型参数变型（`in`/`out`）与约束（`where T : IDisposable`）进 `TacTypeDecl.typeParameters`**（IR-N + 变型字段扩展） | IR-E、**IR-AG**、IR-N |
| `TacTranslator.cs` | `TranslateStatement`（`:239-315`） | 补 `DoStatementSyntax`/`CheckedStatementSyntax`/`UncheckedStatementSyntax`/`UnsafeStatementSyntax`/`FixedStatementSyntax`/`GotoStatementSyntax`/`LabeledStatementSyntax`/`YieldStatementSyntax`/`ThrowExpressionSyntax`/`SwitchExpressionSyntax`/`AwaitUsingStatementSyntax` | IR-C、IR-G、IR-I |
| `TacTranslator.cs` | `TranslateSwitch`（`:992-1085`）、`TranslateTry`（`:768-807`）、`TranslateUsing`（`:1097-1108`）、`TranslateForEach`（`:943-990`）、`lock` 分支（`:302-308`） | 补 `CasePatternSwitchLabelSyntax` + 每 arm 末尾 `JUMP L_end` + `guard` + `branchRegions`；`clause.Filter` 求值 + guard；`catchTypes`/`finallyExits`；`using` 合成 `try/finally` + `Dispose`；迭代协议（真实方法名）+ 出口边；**`await foreach` 走异步枚举协议**（`GetAsyncEnumerator`/`MoveNextAsync`/`DisposeAsync` + `Effect.SUSPEND` + `resumePoints`，见 §7.2）；`Monitor.Enter/Exit` + `criticalSections` | IR-C、IR-F、IR-I、IR-L、IR-M、IR-P |
| `TacTranslator.cs` | `TranslateObjectCreation`（`:1663-1704`）、`TranslateMemberAccess`（`:1454-1544`）、`TranslateStaticMemberAccess`（`:1546-1568`）、`BinaryOperatorOf`（`:2057-2080`）、`IsCompoundAssignment`（`:2032-2047`） | 对象/集合初始化器逐 setter/逐元素；`FIELDLOAD`/`FIELDSTORE` 补 `ownerType`；方法组 → 合成转发闭包（现状发 CALL）；赋值 kind 扩进算子表；`??` 不再映射 `ADD`；**`?[]` 条件元素访问**（与 `?.` 同形，见 §6.2）；**`Span<T>`/`ReadOnlySpan<T>` 索引器写**（合成类 + backing 数组 + `ARRAYSTORE`，见 §6.2）；**`field` 关键字**（对合成 backing field 的 `FIELDLOAD`/`FIELDSTORE`）；**`virtual`/`override` 动态派发**（一组 `CALL` 覆盖静态声明 + 已知 override，`calleeSig` 用各自 owner FQN，见 §5.2） | IR-E、IR-P |
| `TacTranslator.cs` | `IsPatternExpressionSyntax`（`:1210-1219`）、`AwaitExpressionSyntax`（`:1164-1175`）、`default`/`typeof`（`:1240-1249`） | `not` → `negated` + 子模式求值 + `and`/`or` 合取/析取；`Effect.SUSPEND` + `resumePoints`；`default(T)` 按值类型给常量；`typeof` 不发 `NEW` | **IR-AF**、IR-L |
| `TacTranslator.Closures.cs` | `TranslateLambda`（`:124-208`） | 合并 `sub._closureFunctions`（现状内层 lambda 的函数不进 store，`:206`） | IR-E |
| `TypeMapper.cs` | `Map`（`:28-74`）、`Signature.For`（`:154-158`） | 补 `domain`；`Nullable<T>` → `nullable=true`；`nint/nuint` → INT/LONG；`dynamic` → UNKNOWN+Dynamic；callee key 改 `<owner>.<methodName>/<arity>` | IR-D、IR-K、IR-P |
| `PROTOCOL.md` | 字段表（`:114-186`） | 同步新字段 | — |
| `CSharpTacFrontend.java` | `toFunction`（`:669-749`）、`toInstruction`（`:752-812`）、`toType`（`:865-903`） | 读全部新字段（**含 `effect`**）；`tryRegions` 不再 `List.of()`（`:737`） | 全部 |

### 11.3 本轮落地（2026-11-18）：C# 的值流与可达性、Go 的 channel range

上表写的都是**该做什么**。这一轮用**探针**（直接驱动 sidecar 走 wire 协议，把构造喂进去、把 TAC 打出来）逐条核对，**发现其中六行「做了，但做错了」** ⇒ 这一轮把它们**改成了设计里写的形状**：

| §11 的行 | 落地情况 | 这一轮改掉的**具体错** |
|---|---|---|
| §11.2「`TranslateSwitch`… 补 `CasePatternSwitchLabelSyntax` + 每 arm 末尾 `JUMP L_end`」 | **已落地** | 比较链的**假边指向下一个 case 的体**（体标签定义在链之后）⇒ 第二个及以后的比较**从不执行**，最后一个 case 与 `default` 的体**在 CFG 里不可达** ⇒ 里面的 sink 走不到。改成：每个 case 标签一个**测试标签**，假边指向**下一个测试**。**模式 case** 与 `when` 守卫一并补上（守卫读的是刚绑定的模式变量 ⇒ 绑定必须在守卫之前） |
| §11.2「`TranslateForEach`… 迭代协议（真实方法名）+ 出口边」 | **已落地** | 循环变量绑的是**集合本身**（`MOVE item = list`）⇒ 值是错的类型、错的污点。改成真协议 `GetEnumerator/0` → `MoveNext/0` → `get_Current/0` → `Dispose/0`；**数组**走下标循环（数组的枚举器是编译器生成的隐藏类型） |
| §11.2「`TranslateObjectCreation`… 对象/集合初始化器逐 setter/逐元素」 | **已落地** | 初始化器被降成**两条独立赋值**，独立赋值按**外层作用域**解析 ⇒ 写进了别的对象；**属性初始化器被整个丢弃**（裸名不是属性）⇒ 没有 `CALL` ⇒ 规则目录（按 `CALL` 匹配）**没有可匹配的目标**。改成：接收者是**刚创建的对象**，属性 ⇒ `CALL <owner>.set_P/1`，字段 ⇒ `FIELDSTORE`，集合 ⇒ `CALL <owner>.Add/1` |
| §11.2「`TranslateStatement`… 补 `SwitchExpressionSyntax`」 | **已落地** | `switch` 表达式**整个被丢** ⇒ 函数返回一个谁都没算过的值。改成：分支链 + **merge temp**（每个 arm 写同一个 temp，每 arm 末尾 `JUMP L_end`） |
| §6.2「`stackalloc`/集合表达式/元组/`with`… 全部未降级」 | **部分落地** | `new T[] { … }`（`NEWARRAY` + 逐元素 `ARRAYSTORE`）、元组（`NEW` + 逐元素 `FIELDSTORE`）、`with`（`NEW` 出副本 + 未改成员逐个搬过去 + 改动成员写进副本）已落地；`stackalloc` 与**集合表达式**（`[...]`）仍未做 |
| §7.1 Go「over channel = 循环体内发 `CALL <go>.chanRecv/1(ch)`，退出条件 `Compare(ok, EQ, false)`」 | **已落地** | `range` over channel 原先是**一次接收、然后什么都不做** ⇒ **循环体整块消失** ⇒ 循环里的 sink 永远走不到。改成：接收在循环头、`ok` 是退出条件、体在自己的标签下 |

> **两条要注意的**：
>
> 1. **`creation.Type` 是类型的语法，不是符号** —— 在它上面查成员永远查不到（集合的 `Add` 就这样丢的）。查成员要用**语义类型**（`_model.GetTypeInfo(creation).Type`）。
> 2. **Go 侧车早就写好了这个形状**（`rangeOverMap` 的写法），**只有 channel 这一支**是「一次接收然后什么都不做」；而 **C# 的 `switch` 与 Go 的 `switch` 是同一个缺陷**——Go 修过（`translator.go` 里有一段注释写着「假分支指向**下一测试**……它以前指向**体**」），C# 没同步。

---

### 11.4 本轮落地（2026-11-24）：把 Go 侧车那一层接进 Maven 回归

这一轮**没有**改 Go 侧车的任何一行。改的是**「Go 侧车那一层被量到了吗」**这件事。

#### 11.4.1 起点：台账自己记了这个洞，而它一直没有人补

`14` §7.2.21 里有一句标着「**老实说**」的话：

> 「**本轮的门禁缺口（老实说）**：**Go 侧车的 102 条用例不在任何一张 surefire 允许列表里** —— 因为**它根本不在 maven reactor 里**（`plumb-frontend/pom.xml` 没有 `exec-maven-plugin`，也没有 `go test`）。**⇒ 所以它那一层的门禁是「自己那 102 条」＋「那条元测试」，而它**必须单独跑**。」

**⇒ 而本轮把它变成了一个会红的东西。** 详细记录在 `14` §3.46；这一节只记 Go 这一侧。

#### 11.4.2 两个洞（逐条实测）

| 洞 | 实测 | 后果 |
|---|---|---|
| **一：Go 侧车自己的测试从不在回归里跑** | 全仓库检索 `go test`：**只命中 `build.ps1`、README、`smoketest.py`、`.gitignore`**；而 `plumb-frontend/pom.xml` **没有 `exec-maven-plugin`** ⇒ ⇒ **12 个 `*_test.go`（102 个测试函数）在 Maven 回归里是 0 条** | 改 `translator.go` 时，Maven 回归**测不到任何东西** —— 而那一层的读数是**「0 条」**，不是红的 |
| **二：Java 侧测的是手工构建、不入库的二进制** | `SidecarLocator.buildOutputs`（`:129`）的候选顺序是 `go-sidecar/bin` → `bin/release` → `bin/debug` → `.` ⇒ ⇒ 而本机 `go-sidecar/bin/plumb-go-sidecar`（**Linux 版**）是 **2026-11-07**，而 `translator.go` 是 **2026-11-21** ⇒ ⇒ **⇒ Linux 上第一候选就是那个两周前的旧件** | **改了 Go 源码不重建 ⇒ Java 侧那几条 Go 用例测的是旧代码，而它们报绿** ⇒ ⇒ **⇒ 而 Windows 上第一候选是新件 ⇒ 本机测不出来** |

#### 11.4.3 实现：两个检查（`plumb-frontend/.../go/GoSidecarBuildTest.java`）

| 检查 | 做什么 | 缺什么时 |
|---|---|---|
| 侧车自己的测试套件 | 在 `plumb-frontend/go-sidecar` 里跑 **`go test ./...`**，断言退出码 0 且输出里有 `ok` | Go 工具链不在 PATH ⇒ **跳过** |
| 二进制不比源码旧 | 断言**二进制的 mtime ≥ 最新源码文件的 mtime**（源码文件 = `*.go`（不含 `_test.go`）＋ `go.mod`） | 找不到二进制 ⇒ 跳过；**找到的不是本仓库的构建输出** ⇒ 跳过 |

**⇒ 而「不是本仓库的构建输出就跳过」这一条是必须的**：用户自己安装的 release 二进制**不该被本仓库的源码时间戳判死** ⇒ ⇒
**⇒ 而环境变量 `PLUMB_GO_SIDECAR` 指过来的那一支，也因此被排除在外**（它本来就不受仓库源码管）。

**⇒ 而缓存出仓库**：`GOCACHE` / `GOMODCACHE` 放在 `%TEMP%\plumb-go-sidecar-build`（与 `build.ps1` 一致），
`GOPROXY=off`、`GOFLAGS=-mod=mod`、`GOROOT` 指向探测到的工具链 ⇒ ⇒ **⇒ 回归不需要网络**。

#### 11.4.4 变异（两个，红集合逐条对应）

| 变异 | 实测 |
|---|---|
| 往侧车源码目录放一条**故意失败**的 Go 测试 | **红**：`Tests run: 1, Failures: 1` ⇒ `BUILD FAILURE` ⇒ 红在 `assertThat(exit).isZero()`（`:88`） |
| 把 `translator.go` 的 mtime 调到比二进制新 | **红**：`Tests run: 1, Failures: 1` ⇒ `BUILD FAILURE` ⇒ 红在 `:123` |

**⇒ 两个突变都在 `try/finally` 里还原，还原后按 blob hash 核对工作树：`tracked 0 差异 / untracked 只剩那一个新增测试文件`。**

#### 11.4.5 顺带查到的三条 Go 侧的事实

**① 三下标 slice 是真缺口，而且**没有回执**。**
`translator.go:3294` 的 `translateSlice` 读 `expression.X` / `Low` / `High`，**从不读 `expression.Max`**
⇒ ⇒ `a[f():g():h()]` 里的 **`h()` 从未求值** ⇒ ⇒ 其中的 sink 不可见（**漏报**）⇒ ⇒ ⇒ **而且这一条没有对应的 drop id** ⇒ ⇒ ⇒ **⇒ 它是「零静默」原则的一个活样本** ⇒ ⇒ ⇒ **⇒ 而它要的东西不大**：第三下标 + 一条回执。

**② 语句层没有静默跳过。** `translator.go:1048`–`1084` 的分派覆盖 **18 种语句**（`AssignStmt` / `DeclStmt` / `ExprStmt` / `ReturnStmt` / `IfStmt` / `ForStmt` / `RangeStmt` / `SwitchStmt` / `TypeSwitchStmt` / `SelectStmt` / `BlockStmt` / `DeferStmt` / `GoStmt` / `BranchStmt` / `LabeledStmt` / `IncDecStmt` / `SendStmt` / `EmptyStmt`），
而 `default`（`:1084`）**发 `ProblemUnhandledNode`** ⇒ ⇒ **⇒ 认不出来的语句会被报出来，不会安静地消失**。

**③ `ast.Ellipsis` 在整个侧车里零命中。** ⇒ ⇒ 变参调用（`f(args...)`）与 composite literal 的省略**都不被读**
⇒ ⇒ 而 composite literal 的省略会落进 `default` ⇒ ⇒ **⇒ 它是**响的**（不是静默）；
⇒ ⇒ ⇒ **⇒ 而变参展开**按 R21 的裁定**是约定不是缺口**（`sink-families.yaml` 的 Go 表明文写着「变参的每个值各占一个实参槽」）。

#### 11.4.6 留在案上的两笔小账

1. **仓库里有两个旧二进制残留**：`go-sidecar/plumb-go-sidecar.exe`（2026-11-20）与 `go-sidecar/bin/plumb-go-sidecar`（2026-11-07，**Linux 版**）⇒ ⇒ 它们都在 `.gitignore` 里（不进版本库），但**留在工作树里会污染本地读数** ⇒ ⇒ **⇒ 而新加的那个检查就是为了让这件事「看得见」**（在 Linux 上它会**红**）。
2. **时间戳法不是内容法**：`git checkout` 会改 mtime（⇒ 刚 clone 下来时任何旧二进制都会被判「旧」），而 `git checkout` 到更早的提交则判不出来 ⇒ ⇒ **⇒ 更强的做法是「把源码哈希写进二进制」**，那要改侧车的构建脚本与 wire 协议 ⇒ ⇒ **⇒ 本轮不做**。

---

### 11.5 本轮落地（2026-11-24）：IR-J —— Go 侧车的 `THROW` 带上类型

这一轮改的是 Go 侧车的**三处 `THROW` 发射点**，以及引擎侧的**类型关系判别**（那一半记在 `14` §3.47）。

#### 11.5.1 改了什么

| 发射点 | 之前 | 现在 |
|---|---|---|
| **返回 error**（`translator.go` 的多值返回路径） | `THROW`，**没有类型** | `THROW` 带 `CtorType` = **第二个返回值的声明类型**（`error` 接口） |
| **`panic(x)`** | `THROW`，**没有类型** | `THROW` 带 `CtorType` = **被 panic 值的静态类型**（`panic("boom")` ⇒ 字符串；`panic(myErr{})` ⇒ `sample.myErr`） |
| **类型断言失败** | **已经有** `runtime.TypeAssertionError` | 不变（**测试是为了让它别悄悄消失**） |

**⇒ 而这条链的后半段本来是通的**：`SidecarTacMapper.java:395` 是**无条件**读 `ctorType` 的（`builder.ctorType(toType(wire.getObject("ctorType")))`）
⇒ ⇒ **⇒ 侧车一发，IR 侧自动就有 ⇒ Java 侧零改动** ⇒ ⇒ ⇒ **⇒ 而这也说明「补齐一条链路」有时只需要把它的一端填上**。

#### 11.5.2 它买到什么（今天还买不到）

`14` §3.47 实现了「CFG 按类型生成 catch 匹配边」⇒ ⇒ **⇒ 而 Go 侧今天**用不上**它**：
`translator.go:252` 声明了 `tryRegions`、`:464` 发送它，但**全仓没有任何 append** ⇒ ⇒ ⇒ **⇒ 永远是空的**；
而 Java 侧那条线**是通的**（`GoTacFrontend.java:713` 读 `wire.getArray("tryRegions")`）⇒ ⇒ ⇒ **⇒ ⇒ 所以缺的是侧车那一半**。

**⇒ ⇒ 而这就是下一件 Go 的活**：把 `recover` 降成 `CATCH_HANDLER`（`ctorType` = `interface{}`，捕获一切），
并在 deferred 闭包函数的 `TryRegion` 里声明 `catchTypes=["interface{}"]`（§7.1 的「`panic`/`recover`」小节已经写好了形状）⇒ ⇒ ⇒ **⇒ 有了它，Go 的 `panic` 才第一次有「被谁接住」这件事** ⇒ ⇒ ⇒ **⇒ ⇒ 而本轮先把类型这条链修好，是为了那一天到来时不用再改一遍协议**。

## 12. 验收口径

| 门禁 | 内容 | 判据 |
|---|---|---|
| **A 声明面完备** | 每条线**每个**成员种类都有 `case`，`default` 必发 `TacDrop` | C#：`ProjectSession.cs:398-428` 穷尽；Go：`translator.go:1025-1028` 的 `TypeSpec` 产生 `TacTypeDecl`。任何未识别成员必须产生 `programDrops`，不得静默 |
| **B 控制流结构** | ① 多值 case 的第 2..n 个值**可达**；② `switch` 每个 arm 末尾有 `JUMP L_end`；③ `catch` 体**不在**正常路径上；④ `finally` 的每个出口（正常/每个 catch/rethrow/return）都有边；⑤ 每个循环有 `LOOP_HEAD`/`LOOP_BODY_END`/`LOOP_END` 三个标签 | 断言写在各 sidecar 自检里，失败即发 `drop(STRUCTURE)` |
| **C 守卫可解** | 每个 `BRANCH` 都带 `guard`；`guard` 里 `TacVar` 名与 `GuardStore` 的 key 一致 | 引擎侧统计「从 CFG 自动推出的守卫数」> 0，`GuardStore` 出现 CFG 来源条目 |
| **D 台账归零** | Go 133 行、C# 160 行台账每行必须落到：① 有完整 TAC 形态，或 ② 有 `TacDrop` 且 `impact` 正确 | 审计 §A 的每一行在本方案都有对应条目，不允许「未提及」 |
| **E AST 覆盖** | §5/§6 每一行给出的解析器节点名在对应 AST 里**确实存在**；标注「没有专属节点」的构念，其信息必须真的在别处可取（修饰符 token、`SyntaxAnnotation`、语义模型） | 实现者按节点名直接写出 dispatch 的 `case`；写不出 `case` 的行必须说明信息来自哪个 API |
| **F 精度口径** | 精度**只**由 `drops` 推导，不看 `problems.length` | 注入一条 `SEMANTICS` drop ⇒ 精度变 `SOURCE_DEGRADED`；仅 `VALUE` drop ⇒ 仍 `SOURCE_FULL` |
| **G 两线同形** | Go 与 C# 的 mapper 读取的 wire 字段集合**完全一致** | 两份 `toInstruction` 的字段清单逐项比对，任一差异算门禁失败 |
| **H 零静默** | 任何「丢了一块」的地方都必须有 drop | 构造测试用例：每个未识别节点都产生 drop；断言「函数体为空但无 drop」不可能出现 |
| **I 老行为不变** | 同一语料跑新旧两版 | 新增能力生效处 finding **增加**，其它处 finding **不变**（`01` §6 的统一要求） |
| **J Go 侧车那一层接进回归**（**⚠ R23 新增**） | ① 侧车自己的 `go test ./...` 在 **Maven 回归里**跑；② 侧车二进制**不比源码旧** | `plumb-frontend/.../go/GoSidecarBuildTest.java` 的 2 条用例；**缺工具链 / 缺二进制时跳过** ⇒ ⇒ **⇒ 跳过不是通过** ⇒ ⇒ **⇒ 而这两个洞的形状是「读数是绿的，而读数没有覆盖被声称覆盖的东西」**（§11.4） |
| **K `THROW` 携带类型**（**⚠ R24 新增**） | ① 侧车的 `THROW` 发 `ctorType`；② IR 的 `THROW` 带上类型；③ CFG 按类型生成 catch 匹配边，**判不出来时保守保留** | `plumb-frontend/go-sidecar/throwtype_test.go` 的 **3** 条 ＋ `plumb-tac/.../ThrowCarriesTypeTest.java` 的 **4** 条 ＋ `plumb-tac/.../CatchEdgeTypeMatchingTest.java` 的 **8** 条 ⇒ ⇒ **⇒ 而「保守」那一档必须自己证明会红**：删掉 `CATCHES` 之后的 `break` ⇒ 「后面的 handler 被遮蔽」那两条断言**红**（`14` §3.47.6 的 M1） |

---

## 13. 无法完整支持的构念及理由

以下 10 条（编号 `U1`..`U10`）是本次审查列出的**候选例外**。**本轮（2026-11-17）按「构念本体是否可降级」复核后**：`U1`–`U5` 降为 **`B` 档**（本体已完整降级，缺的只是一个**运行期才定的事实**，已用 `drop` 显式记账），**`U6`–`U10` 仍是真例外** ⇒ **Go/C# 侧的真例外 = 5 条**。除这 10 条外，本次**未发现其它无法完整支持的构念**——Go（133 行）与 C#（160 行）台账的其余每一行，在 §5–§9 都有对应的完整 TAC 形态，或对应的 IR 需求（§10）。

> **U9/U10 的性质与 U1–U8 不同**：它们不是「运行时事实」，而是**编译期事实**——「依赖从哪来」（vendoring）与「谁能看见谁」（包可见性）都**在编译期就定死了**。⇒ 但这两条**规则本身是可静态判定的**（`vendor/modules.txt` + `go.mod` 给出依赖集；`…/internal/…` 的导入路径作用域给出可见性），**不是 IR 能力缺口**，而是**前端工作量 + 显式边界**（本方案未实现 ⇒ 记 `drop(SEMANTICS)` 让取舍在精度标注里可见）。

**本表有三档 —— 先看「档」列，它决定附录行的分档**：

| 档 | 含义 | 附录行怎么指向它 |
|---|---|---|
| **C** | **真例外**：构念**本体**无法完整降级（被调方不在源码里 / 边由运行期建立 / 编译期产物 / 求解器侧能力） | 附录行指向本表的 `Un` ⇒ 查「档」列 ⇒ **C** |
| **B** | **B 级残留**：构念**本体已完整降级**，缺的只是一个**运行期才定的事实** ⇒ 记 `drop` 显式记账 | 附录行指向本表的 `Un` ⇒ 查「档」列 ⇒ **B** |
| **已覆盖** | 已有 **IR 提案**给出解法 ⇒ **不是例外**；本表只留条目以便追溯 | 附录行指向**正文** ⇒ 分档判为 **A** |

⇒ **判据**（与 `05` §3 的 **P8** 同一条）：`A` = 构念变成真实 TAC 形状（指令**或** IR 元数据），无语义损失；`B` = 构念**本体**完整降级，缺的**只是一个运行期才定的事实**，且该缺失用 `drop` **显式记账**；`C` = 构念**本体**无法降级，只能给保守形状。
⇒ **B 与 C 的分界**：缺的是「**这次运行里它是否发生 / 何时发生 / 以什么顺序发生 / 是哪个值**」⇒ **B**；缺的是「**一条边**（谁调用它）」「**一个被调方**（不在源码里）」「**编译期产物**」⇒ **C**。
⇒ **本轮（2026-11-17）复核结果**：10 条里 **5 条降为 `B`**（`U1`–`U5`）、**5 条仍是真例外**（`U6`–`U10`）⇒ **Go/C# 侧的真例外从 10 条降到 5 条**。


| # | 档 | 构念 | 为什么无法静态表达 | 保守建模形状 | 对精度的影响 |
| --- | --- | --- | --- | --- | --- |
| U1 | B | Go `select` 哪个 case 就绪 | 就绪由运行时 channel 状态决定，源码里没有这个事实（解析器有 `ast.SelectStmt`，但**就绪顺序不是解析器事实**）。**但全部 case 的体与 channel 表达式已完整降级** ⇒ 缺的只是「哪个 case 就绪」这一个**运行期才定的事实** | 全部 case 可达 + `Truthiness(ready)` + `drop(SEMANTICS)` | 分支可达性过近似 ⇒ 该 switch 内多报 |
| U2 | B | Go goroutine / C# async 的**交错顺序** | 交错由调度器与硬件决定。**但 goroutine 体 / `async` 状态机已完整降级**（`Effect.CONCURRENT` / `Effect.SUSPEND` + `resumePoints`（IR-L））⇒ 缺的只是「交错顺序」这一个**运行期才定的事实** | `Effect.CONCURRENT` / `Effect.SUSPEND` + `resumePoints`（IR-L） | 竞态类规则报「**可能**发生」，不报「发生了」；跨挂起点数据流过近似 |
| U3 | B | `lock` / `sync.Mutex` 的**争用顺序** | 谁先进入临界区由运行时决定。**但临界区范围与锁边已完整降级**（IR-M + 显式 `Monitor.Enter/Exit`）⇒ 缺的只是「谁先进入」这一个**运行期才定的事实** | 临界区范围进 IR（IR-M）+ 显式 `Monitor.Enter/Exit` | 死锁/顺序类规则**不报**；原子性规则照报 |
| U4 | B | 协程 / `yield` 的**恢复顺序** | 同 U2。**但协程 / `yield` 的状态机已完整降级**（`resumePoints` + 「任意挂起点可能到达」的合并）⇒ 缺的只是「恢复顺序」这一个**运行期才定的事实** | `resumePoints` + 「任意挂起点可能到达」的合并 | 跨恢复点的数据流过近似 |
| U5 | B | Go 的**求值顺序未定义处** | Go 规范明确函数实参、map 下标与赋值的求值顺序未指定。**但本方案固定一种顺序（过近似）⇒ 本体已完整降级** ⇒ 缺的只是「实际是哪种顺序」这一个**运行期才定的事实** | 固定一种顺序，并在该函数记 `drop(SEMANTICS, "eval-order")` | 依赖求值顺序的规则（如「先校验后使用」）在该函数上不成立 |
| U6 | C | Go typed-nil interface、C# `dynamic` 的**运行期绑定** | 接口里装的是 nil 还是带类型的 nil、动态绑定到哪个成员，都取决于运行时对象状态 | `TacType.UNKNOWN` + `domain=Dynamic` + `UNINTERPRETED` 调用；`TypeTest` 走动态形式 | 类型门控类规则在这些点上不触发（不误报，也漏报） |
| U7 | C | C# `decimal` 定点**舍入**、IEEE-754 **精确舍入** | 求解器侧没有十进制定点理论 / FP 理论未接入 | `FixedPoint(128, scale)` / `Floating(53,11)` 声明形状并标注**不精确** | 浮点与定点相关的边界推理不可用（当前已不可用） |
| U8 | C | Go `//go:embed` 的**文件字节**、C# 特性里的**编译期常量值** | 内容在构建期/运行期才确定，源码里没有 | 该值按未知处理 + `drop(VALUE)` | 依赖该常量值的规则不触发 |
| U9 | C | Go **vendoring**（`vendor/` 目录树 + `vendor/modules.txt`） | 依赖从哪来是**构建期**事实，**没有运行时对应物**；且侧车**不解析 `vendor/` 目录**（审计 `10-go.md:193`）。**这条规则本身是可静态判定的**（`go.mod` + `vendor/modules.txt` 给出依赖集），本方案**未实现** ⇒ 属**前端工作量 + 显式边界**，**不是 IR 能力缺口** | **显式边界**：依赖必须由 **Java 侧在请求的 import 列表里点名**（带 export data 或源码目录）⇒ **被点名的 vendored 依赖照常降级**；**未被点名的不存在于 TAC 里**（落点见 §5.1「**import 解析 / vendoring**」行、§11.1 `session.go` 行） | 未点名的 vendored 依赖**不可分析**（不是多报/漏报，是「根本没进来」）；这与「调用了一个未知外部函数」是同一类风险 |
| U10 | C | Go **包级可见性**（`internal` 包只能被同父目录树导入） | 可见性规则是**编译期**约束，**没有运行时对应物**（运行时不存在「可见性」这件事）；且它是**跨 TU** 的导入路径作用域规则（`…/internal/…`）。**这条规则本身是可静态判定的**（导入路径的作用域：只能被同父目录树导入），本方案**未实现** ⇒ 属**前端工作量 + 显式取舍**，**不是 IR 能力缺口** | **显式取舍**：本方案**不实现包级可见性** ⇒ 引擎把 `internal` 包**当公开包分析**（过近似：不会漏报，只可能把不可达的包当成可达）⇒ 记 `drop(SEMANTICS, "pkg.visibility")` 让取舍在精度标注里可见（落点见 §5.1「**包可见性（`internal`）**」行）。注意：**成员级**可见性（public/internal/private/protected）由 `01` §IR-E 的 `TacVisibility` 覆盖，与本条不是同一件事 | 跨包可见性违规**不报**（可能多报：把 `internal` 包的 API 当成公开 API 使用） |

> U5 严格说不是「IR 表达不了」，而是「源码里没有这个事实」（求值顺序未指定 ⇒ 固定一种顺序是过近似）；U6–U8 是「**被调方不在源码里**」或「**求解器侧能力不足**」。U9/U10 则是「**编译期事实**」—— 但这两条**规则本身可静态判定**（不是能力缺口），本方案**未实现** ⇒ 属**前端工作量 + 显式边界**。按 `02` §3.5 与 §3.4 的口径，它们都属于**当前尚未覆盖**；其余条目要么是**前端工作量**（§11），要么是**IR 需求**（§10）。

---

## 14. 风险与未决项

### 14.1 风险（`K<n>`）

| # | 风险 | 影响 | 处置 |
|---|---|---|---|
| K1 | **wire 无版本协商**：`go-sidecar/protocol.go:99`/`:105` 与 `csharp-sidecar/Protocol.cs:350`/`:359` 都是 `PROTOCOL=1, IR=1` | 灰度期新旧混用 ⇒ 静默丢能力 | 加 `wireVersion` 能力位（`01` §8 待定项 3）；本方案所有新字段**可选**（缺省 = 现状行为），保证双向兼容 |
| K2 | **三份 mapper**（共享 + Go 私有 + C# 私有）会再次分叉 | 共享 mapper 的修复到不了 Go/C# | 门禁 G 强制字段集合一致；中期把两线私有 mapper 抽成基类 |
| K3 | **精度第一次有行为**：`IrPrecision` 今天只被 `ModelCoverageComputer.java:133` 与 `ScannedOrigin.java:81` 读 | 一旦有 checker 消费 `SOURCE_DEGRADED`，历史基线全变 | 每条改动单独一轮并记录基线；`SOURCE_DEGRADED` **不得**用于「证明安全」（`IrPrecision.java:42-56`） |
| K4 | **闭包 cell 化**改变捕获模型：现状捕获形参在引擎侧被**当成污点源**（`TaintedCheckerBase.java:1001` 附近的 `markSource(p, "param:"+...)`） | 会**系统性减少**误报（总报告 P1-1 的判断） | 必须先有基线；cell 形参要能在引擎侧与「真形参」区分，否则全部参数都变成源 |
| K5 | **`refOutDests`/`resultDests` 需引擎侧配合**：callee summary 现在把 formal *i* 绑到 `args[i+1]` | 不改引擎则新字段只写不读 ⇒ 等于没加 | **已处置（2026-11-24）**：摘要新增「形参被写入的污点 + 起因形参」两个维度（`FunctionSummary.paramWriteOps` / `paramWriteCauses`），调用点按 `refOutDests` 把写回污点绑到调用方变量 ⇒ **⇒ 而「引擎侧配合」不只是消费方点名，它要**新维度**：写回走的是形参，不是结果** ⇒ ⇒ ⇒ **⇒⇒ 落地记录见 `14` §3.48.10** |
| K6 | **Go 侧车二进制陈旧**：仓库里的 `bin/plumb-go-sidecar.exe` 比 `translator.go` 早 7 分钟（审计期观测） | 实测结论与源码漂移 | 重建二进制后再复核 §5/§6 每一条。**⇒ 2026-11-24 补一条同族的**：**C# 侧车也不在 Maven 构建里**（`dotnet build` 单独构建），且 `CSharpSidecarClient` 的查找顺序是 `bin/Release/net8.0` → `bin/Debug/net8.0` ⇒ ⇒ **⇒ 改完侧车必须重建 Release，否则测的是旧二进制** |
| K7 | **`TacGuard` 组合子（IR-AF）改变 Z3 输入形状** | Z3 桥要认 `AllOf`/`AnyOf` | 与 `01` §2-IR-C 的 Z3 表同步扩展；`TypeTest` 仍不贡献数值约束（IR-C 已声明） |

### 14.2 待拍板（`Q<n>`）

| # | 待拍板项 | 需要谁拍 | 备选 | 现状处置 |
|---|---|---|---|---|
| Q1 | **Go `int` 的宽度**：构建目标由 `//go:build` 约束与 `openProject` 的 build tags 决定（`session.go:1191-1202`）；两者都缺时退到 IR-AK 的 `PlatformDependentIntegral` | 引擎 + 前端 | (a) 按目标平台给确定位宽；(b) 永远按 64 位；(c) `PlatformDependentIntegral` | 按 (a) 实现；未知时 (c) + drop(VALUE) |
| Q2 | **C# 特性值的表达深度**：本方案只进 `annotations`（名字）与 `annotationValues`（字符串值）；`[Authorize(Roles="Admin")]` 的命名参数、`[Obsolete("msg", true)]` 的位置参数需稳定约定 | 规则侧 + 前端 | (a) `name(arg1,arg2)` 文本形式；(b) 结构化键值表 | (a)（与 Go tag 处理一致，但字段独立） |
| Q3 | **Go struct tag 与语言注解是否合并**（总报告 P0-6 建议「两个不同的东西，分开字段」） | 引擎 | (a) 分字段（`annotations` / `fieldTags`）；(b) 合并 | (a) |
| Q4 | **C# `partial` 的跨文件合并时机**：侧车按文件翻译 ⇒ 同一类型的成员分散在多个 `TacTypeDecl` | 引擎 | (a) 引擎按 FQN 合并；(b) 侧车做跨文件合并 | (a)，与 IR-E 的「迁移期新旧两套并存」策略一致 |
| Q5 | **`//go:embed` 与 C# 特性编译期常量是否值得做** | 产品 | (a) 保守建模（§13 的 `U8`）；(b) 完整建模（需构建期数据） | (a)；不列入首批 |

---

**一句话立场**：Go 与 C# 的全部审计条目中，**只有 10 条**（`U1`..`U10`）确实无法完整支持：前 8 条是「运行时事实」或「求解器能力」，后 2 条（`U9` vendoring / `U10` 包级可见性）是「**编译期事实、没有运行时对应物**」——**没有一条是「IR 表达不了」**，而那 6 条补充 IR 提案（`IR-AF`..`IR-AK`）覆盖了剩下的全部缺口。

---

## 附录 A. Go 审计台账 A 节（编号 1–133）逐条去向（133 条）

**审计来源**：`D:\work\tmp\audit\10-go.md` 的 `## A. Coverage ledger` 节（`### A.1 Declarations` … `### A.14 Cross-cutting …`，共 14 个子小节）。
**台账行数**：**133 行**（审计 A 节的物理表行；与 `12-total-report.md:218` 的「Go | 133 | 68 | 45 | 17 | 8 | 9 | 3」一致，也与本文档 §1/§13 的「Go（133 行台账）」一致）。
**子小节标题原样保留**审计的 `### A.x …`，便于逐节核对；「#」列按 A 节**物理行顺序**连续编号 1–133（跨子小节不重排）。
**「审计状态」列**沿用审计自己的状态词（`KEPT` / `DEGRADED` / `DEGRADED (body dropped)` / `DROPPED-SILENT` / `DROPPED-NOTED` / `NOT FOUND`），**不改写审计的结论**。
**「审计证据」列**是审计原文 `file:line` 的**转录**，**不重新核对行号**（审计的行号会漂）。
**「本方案的去向」列**给出本文档里**真实存在**的落点：**章节号 + 表行原文片段**（片段可在本文件内直接 grep 到）。`KEPT` 行按审计结论写「保持现状（无需改动）」；`DEGRADED` / `DROPPED-*` / `NOT FOUND` 行给出落点，或以 `⚠ 未覆盖 —— ` 开头说明缺什么。
**与审计 §B 节的关系**：审计 §B（丢弃清单，编号 1–28）是本 A 节的**子集**，两套编号**各自独立**；本附录覆盖的是**完整 A 节**（与 `06-降级方案-PHP与Python.md` 附录 A/B 的口径一致）。
**本附录服务于 §12 验收门禁 D「台账归零」**（Go 133 行、C# 160 行台账每行必须落到：① 有完整 TAC 形态，或 ② 有 `TacDrop` 且 `impact` 正确）。

### A.1 Declarations

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 1 | package clause | KEPT | `plumb-frontend/go-sidecar/translator.go:305`; `session.go:344` | 保持现状（无需改动） |
| 2 | imports, aliased (`al "pkg"`) | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 3 | imports, dot-import (`. "pkg"`) | KEPT | `translator.go:2784-2808` | 保持现状（无需改动） |
| 4 | imports, blank-import (`_ "pkg"`) | KEPT | `session.go:1117-1180` | 保持现状（无需改动） |
| 5 | constants (typed/untyped, grouped `const (...)`, `iota`) | KEPT | `translator.go:1044-1052`, `:2127-2164` | 保持现状（无需改动） |
| 6 | variables, grouped `var (...)` | KEPT | `translator.go:1037-1065` | 保持现状（无需改动） |
| 7 | variables, multi-name `var a, b = f()` at package level | DEGRADED | `session.go:427-447` | §11.1 改动清单「`session.go`」行（包级变量初始化（`:427-447`）… 多值初始化只发**一次**调用（现状发两次））+ §6.1 Go 表达式面表「多返回值 / map 双返回 / channel 双返回 / 裸 `return` / 变参」行（**全部靠「调用的多个输出」**：CALL 的 `dest=v` + `resultDests[i]=第 i 个结果`；IR 能力列 **IR-AH**）+ §10「**IR-AH `TacInstruction` 需要表达「调用的多个输出」与「按引用实参」**」 |
| 8 | functions (incl. methods) | KEPT | `translator.go:280-346` | 保持现状（无需改动） |
| 9 | generic functions (type parameters + constraints) | DEGRADED | `translator.go:2877-2884`; `types.go:96-99` | §5.1 Go 声明面表「泛型（类型参数+约束）」行（`TacTypeDecl.typeParameters`（含约束）+ 实例化点 `TacType.typeArguments`；IR 能力列 IR-N / IR-K）+ §8 类型面方案表「Go 类型参数 `T`」行（改进：`TacType.bound`（IR-K）给出约束）+ §11.1 改动清单「`interfaces.go`」行（**IR-AI**：同时索引值方法集与指针方法集并分开记录） |
| 10 | types: `type X struct` / `interface` / named / alias / definition / generic type | DEGRADED | `translator.go:1025-1028`; `types.go:61-70` | §5.1 Go 声明面表「`type` 声明」行 +「`type X struct` / `type X interface`」行（`TacTypeDecl{fqn, fields, members}` / `TacTypeDecl{isInterface=true, members=方法集}`；IR-E / IR-N）+「别名 vs 定义」行（别名**不产生新 `TacTypeDecl`**；定义**产生新 `TacTypeDecl`**（`MyInt` ≠ `int`））+ §8 类型面方案表「Go 命名类型 `type MyInt int`」行（**CLASS**（不再等于 `int`）；现状 `MyInt == int`）+ §11.1 改动清单「`types.go`」行（命名类型 ≠ 底层类型） |
| 11 | `init()` functions | KEPT | `session.go:295-302`, `:377-388` | 保持现状（无需改动） |

### A.2 Structs

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 12 | fields | KEPT | `translator.go:2266-2280` (read), `:970-988` (write) | 保持现状（无需改动） |
| 13 | embedded fields and their promotion | DEGRADED | `translator.go:2264-2286` | §5.1 Go 声明面表「嵌入字段」行（`TacTypeDecl.fields` 记声明处字段；访问按嵌入链**逐级展开**）+ §5.1 末段「**嵌入字段提升的完整降级（不需新 IR）**」（`FIELDLOAD t1 = d.Base`（ownerType=Derived, fieldName=Base）→ `FIELDLOAD t2 = t1.ID`（ownerType=Base, fieldName=ID）⇒ 同一份存储只有一个键）+ §11.1 改动清单「`translator.go`」行（`translateSelector`（`:2263-2307`）… 嵌入字段按链展开 + `ownerType`） |
| 14 | struct tags (`json:"..."`, `xml:"..."`) | DROPPED-SILENT | no `ast.StructType.Tag` / `reflect.StructTag` use anywhere in `plumb-frontend/go-sidecar/`; the wire `Instruction` has no tag field (`protocol.go:190-246`) | §5.1 Go 声明面表「struct tag」行（`TacTypeDecl.fieldAnnotations` 或独立 `fieldTags`；注解通道列「**独立字段**」）+ §4 wire 协议现状表 P0-6 行（Go struct tag 走**独立字段** `fieldTags`（tag 不是注解））+ §11.1 改动清单「`protocol.go`」行（新增 `programDrops`/`types`/`fieldTags`）+ §14.2 Q3（分字段 `annotations` / `fieldTags`） |
| 15 | methods on value vs pointer receivers | KEPT | `types.go:191-208` | 保持现状（无需改动） |
| 16 | struct literals, keyed | KEPT | `translator.go:2476-2495` | 保持现状（无需改动） |
| 17 | struct literals, positional (`Base{1}`) | DROPPED-NOTED | `translator.go:2479-2481` | §6.1 Go 表达式面表「复合字面量（键/位/嵌套）」行（**位置式**按字段序绑定；嵌套递归）+ §11.1 改动清单「`translator.go`」行（`translateCompositeLit`（`:2425-2503`）… 位置字面量按字段序绑定（现状 `:2479-2481` 只报）） |
| 18 | struct literals, nested | KEPT | `translator.go:2425-2497` (recursive) | 保持现状（无需改动） |
| 19 | `&T{}` vs `T{}` | DEGRADED | `translator.go:2244-2254` | §6.1 Go 表达式面表「取址 `&x` / 解引用 `*p` / `*p = v`」行（`&x` → `NEW p = <go>.addr/1(x)`（cell）⇒ `&T{}` 与 `T{}` 是**两个不同的存储**；IR-E（合成 cell 类））+ §11.1 改动清单「`translator.go`」行（`translateStar`（`:2393-2406`）… 解引用 → `FIELDLOAD`） |

### A.3 Interfaces

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 20 | method sets | KEPT | `interfaces.go:127-149` | 保持现状（无需改动） |
| 21 | implicit satisfaction | KEPT | `interfaces.go:231-260` | 保持现状（无需改动） |
| 22 | embedding interfaces | KEPT | `interfaces.go:151-173` | 保持现状（无需改动） |
| 23 | empty interface `any` | DEGRADED | `interfaces.go:236-241` | §5.1 Go 声明面表「`type X struct` / `type X interface`」行（`TacTypeDecl{isInterface=true, members=方法集}` ⇒ **空接口 `any` 的方法集为空**，调用无派发目标）+ §10「**IR-AI 方法集归属：值接收者 vs 指针接收者**」的消费方（接口实现解析（Go 的隐式满足关系，`interfaces.go:231-260`）、调用图的目标集合） |
| 24 | type assertions, single-value (`v := x.(T)`) | DEGRADED | `translator.go:2409-2422` | §6.1 Go 表达式面表「单值断言 `x.(T)` / comma-ok 断言」行（单值：`UNARYOP t = CAST x (T)` + **失败路径 `THROW`**（`ctorType`=`*runtime.TypeAssertionError`）；IR 能力列 IR-J / IR-C）+ §11.1 改动清单「`translator.go`」行（`translateTypeAssert`（`:2409-2422`）… 断言失败 → `THROW`） |
| 25 | type assertions, comma-ok (`v, ok := x.(T)`) | DEGRADED | `translator.go:920-921`, `:928-937` | §6.1 Go 表达式面表「单值断言 `x.(T)` / comma-ok 断言」行（comma-ok：再加 `INSTANCEOF ok = x (T)`；IR 能力列 **IR-AH**）+ §11.1 改动清单「`translator.go`」行（`translateMultiAssign`（`:893-938`）… 第 2..n 结果走 `resultDests`） |
| 26 | type switches, multiple types per case | KEPT | `translator.go:2219-2265`（`translateTypeSwitch` 的链）| §7.1 Go 控制流「**`switch`（表达式）**」小节（**类型 `switch`** = `ast.TypeSwitchStmt{Assign, Body}` → `ast.CaseClause{List}`（`List` 元素是 `*ast.TypeAssertExpr`）⇒ `INSTANCEOF t = x (T)` + `BRANCH ▸ TypeTest(x, T, false)`）+ §12 门禁 B ① **⚠ R21 已做（见 `14` §3.40）**：本表原来写的处方（假分支指向**下一测试**）**只做了一半** —— 它说的是「下一子句的**测试**」，而**子句内部**的多个类型没有串联 ⇒ ⇒ **实测的缺陷比这一行写的更大**：① **每一个**子句的类型测试都是死的（假分支直接进了下一子句的**体**）；② 多类型子句的**第二个类型**根本没被测；③ **三子句时 default 的体不可达** ⇒ ⇒ **已按 `translateSwitch` 的同形做法修掉**（每个子句一个测试标签、类型在子句内串联、default 的体是 no-match 的落点）⇒ ⇒ **而「分支条件的形状」没变** ⇒ ⇒ **⇒ 规则匹配面没动**，而**读数也没动**（九个形状逐条相同）⇒ ⇒ **这一条买到的不是 finding，是一张对的图** |
| 27 | type switches, `case nil` | DEGRADED | `translator.go:1614-1621` | §7.1 Go 控制流「**`switch`（表达式）**」小节（`case nil` 用 `Compare(x, EQ, NULL)` 而非 `INSTANCEOF` 打 `NULL` 类型（现状如此，`translator.go:1614-1621`）） |
| 28 | type switch narrowing | DEGRADED | `translator.go:1562`, `:1641-1645` | §7.1 Go 控制流「**`switch`（表达式）**」小节（**类型 `switch`**：绑定变量 `MOVE v = x`（narrowing 记 `TacBinding.sourceType`；现状不窄化，`translator.go:1561-1663`）） |

### A.4 Control flow

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 29 | `if` / `else if` / `else`, with init statement | KEPT | `translator.go:1124-1149` | 保持现状（无需改动） |
| 30 | `for` — condition-only, three-clause, infinite | KEPT | `translator.go:1157-1191` | 保持现状（无需改动） |
| 31 | `for ... range` over slice / array | KEPT | `translator.go:1309-1375` | 保持现状（无需改动） |
| 32 | `for ... range` over string | DEGRADED | `translator.go:1309-1375` | §7.1 Go 控制流「**`range`**」小节（over string = 按 rune 宽度展开为下标循环（现状按字节 ARRAYLOAD，`translator.go:1309-1375`）） |
| 33 | `for ... range` over map | DEGRADED | `translator.go:1386-1446` | §7.1 Go 控制流「**`range`**」小节（over map = 迭代协议用**真实方法名**（IR-P）：`CALL <owner>..Range/0` → `CALL <owner>..Next/0` → `MOVE k = <go>.mapKey/1(it)`、`MOVE v = <go>.mapValue/1(it)`（现状内容不建模，`translator.go:1386-1446`）） |
| 34 | `for ... range` over channel | DEGRADED (body dropped) | `translator.go:1452-1465` | §7.1 Go 控制流「**`range`**」小节（over channel = **循环体内**发 `CALL <go>.chanRecv/1(ch)`，退出条件 `Compare(ok, EQ, false)`（现状整块循环体被丢，`translator.go:1452-1465`）） |
| 35 | `for ... range` over an integer (Go 1.22) | KEPT | `translator.go:1236-1284` | 保持现状（无需改动） |
| 36 | `switch`, expression form | KEPT | `translator.go:1476-1545` | 保持现状（无需改动） |
| 37 | `switch` with several values per case | DEGRADED | `translator.go:1507-1521` | §7.1 Go 控制流「**`switch`（表达式）**」小节（每个 case 的多个值合并成**一条** `BRANCH ▸ Membership(tag, [1,2])`（IR-C）；现状假分支跳到下一 case 的**体**，`translator.go:1507-1522`）+ §11.1 改动清单「`translator.go`」行（`translateSwitch`（`:1476-1545`）、`nextCaseLabel`（`:1548-1553`）… 多值 case 合并为 `Membership`；假分支指向**下一测试**）+ §12 门禁 B ① |
| 38 | `switch`, tagless with init | KEPT | `translator.go:1479-1482` | 保持现状（无需改动） |
| 39 | `fallthrough` | KEPT | `translator.go:1884-1890`, `:1923-1933` | 保持现状（无需改动） |
| 40 | `select` (cases, `default`) | DEGRADED | `translator.go:1665-1725` | §7.1 Go 控制流「**`select`**」小节（**保守展开为全部 case 可达**：`CALL ready1 = <go>.selectReady/1(ch1)` → `BRANCH ready1 ? L_case1 : L_test2 ▸ Truthiness(ready1)`；记一条 `drop(SEMANTICS, "select.readiness")`）+ §13 的 `U1`（Go `select` 哪个 case 就绪：全部 case 可达 + `Truthiness(ready)` + `drop(SEMANTICS)`）+ §11.1 改动清单「`translator.go`」行（`translateSelect`（`:1665-1725`）… select 全部 case 可达 + guard） （`B` 档：记 `drop(SEMANTICS, "select.readiness")`） |
| 41 | `select` with a two-value receive (`case v, ok := <-ch:`) | DROPPED-NOTED | `translator.go:1728-1739` | §7.1 Go 控制流「**`select`**」小节（两值接收的通道解析必须修好（现状解析失败致 case **不可达**且变量未绑定，`translator.go:1728-1739`））+ §11.1 改动清单「`translator.go`」行（`selectChannel`（`:1728-1739`）… 两值接收的通道解析修好） |
| 42 | `goto` and labels | DROPPED-NOTED | `translator.go:1891-1897` | §7.1 Go 控制流「**`goto` 与标签**」小节（⇒ `JUMP L_goto` + `LABEL L_goto` + `labelRef={BLOCK, scopeId}`（IR-G））+ §11.1 改动清单「`translator.go`」行（`translateBranch`（`:1868-1899`）… `goto` → `JUMP`+`LABEL`+`labelRef`） |
| 43 | `break` / `continue` with labels | KEPT | `translator.go:1905-1919`, `:1944-1950` | 保持现状（无需改动） |

### A.5 Concurrency

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 44 | `go` statements | KEPT | `translator.go:1848-1865` | 保持现状（无需改动） |
| 45 | `defer` | DEGRADED | `translator.go:1771-1827` | §7.1 Go 控制流「**`defer`**」小节（改为**放在函数所有出口之前**：`L_def1: FINALLY_ENTER L_def1` → `CALL release(...)`（`deferredCalls` 侧表仍保留）→ `JUMP L_next_exit`；每个 `defer` 一个 `TryRegion{syntheticFinally=true, finallyExits=[全部出口标签]}`（IR-F）；Go 的 defer 是 LIFO 且 panic 时也执行 ⇒ `finallyExits` 必须含异常路径）+ §9 并发与同步方案表「Go `defer f()`」行 + §11.1 改动清单「`translator.go`」行（`translateDefer`（`:1771-1827`）… defer 移到所有出口 + `TryRegion`） ⇒ ⇒ **⚠ R21 未实施**（`14` §3.39）：移到出口会**静默删掉 CWE-772**（它的判据就是那条 deferred CALL 的位置），且出口放置需要「defer 有没有被登记过」的可达性表示 ⇒ ⇒ **本轮改的是检查器的「回看范围」**：deferred unlock 配对的 lock 可以取在函数体的任何地方 |
| 46 | `defer` of a closure | DEGRADED | `translator.go:1771-1796`, `:2886-2900` | §7.1 Go 控制流「**`defer`**」小节（deferred 闭包的调用移到函数出口；实参在 defer 语句处求值，保留）+ §6.1 Go 表达式面表「闭包**按引用捕获**」行（捕获变量提升为 **cell 对象** ⇒ 闭包在出口处写回的是**同一个**存储）+ §11.1 改动清单「`translator.go`」行（`translateDefer`（`:1771-1827`）、`translateFuncLit`（`:407-478`）… 捕获按引用（cell 对象化）） |
| 47 | `defer` of a method call | KEPT | `translator.go:1772-1796` | 保持现状（无需改动） |
| 48 | `defer` argument-evaluation timing | KEPT | `translator.go:1787-1796` | 保持现状（无需改动） |
| 49 | `sync.WaitGroup`, `sync.Mutex`, `sync.Once`, atomic ops | DEGRADED | `translator.go:2603-2712` | §9 并发与同步方案表「Go `sync.Mutex`/`RWMutex`/`WaitGroup`/`Once` / 原子操作」行（可完整表达：临界区**范围**与「同一把锁」的关系；Add/Done 的**配对计数**；`Do` 的**唯一性位置**；原子操作的原子性（API 契约）\| 保守部分（运行时决定）：谁先进入；实际完成时刻 \| 形状 `criticalSections{kind=SYNCHRONIZED}`（IR-M）+ `Lock`/`Unlock`/`WaitGroup#Add`/`WaitGroup#Done`/`Once#Do` 调用；死锁类规则不报，配对由 checker 做；原子操作用 `kind=ATOMIC`）+ §4 wire 协议现状表 IR-M 行 + §13 的 `U3` （`B` 档：记 `drop(SEMANTICS, "lock.contention")`） |
| 50 | `context` cancellation | DEGRADED | `translator.go:2603-2712` | §9 并发与同步方案表「Go `context` 取消 / `errors.Is`,`errors.As` 的 wrapping」行（可完整表达：「`Done()` 被关闭」是事实 \| 保守部分：取消发生的时刻 \| 形状 `CALL context.Context#Done/0` + `#Err/0`，`Err() != nil` 走 `Compare`） |
| 51 | channels: send / receive | KEPT | `translator.go:1972-1980`, `:2948-2960` | 保持现状（无需改动） |
| 52 | channels: `close` | KEPT | `translator.go:2938-2940` | 保持现状（无需改动） |
| 53 | channels: buffered / unbuffered / directional | DEGRADED | `types.go:87-88` | §8 类型面方案表「Go map `map[K]V` / chan `chan T`」行（CLASS(合成类型)；内容由 FIELDLOAD/FIELDSTORE 表达；**容量与方向进 `TacTypeDecl`**（现状全丢，`types.go:72-88`））+ §11.1 改动清单「`types.go`」行（map/chan 走合成类型） |

### A.6 Error handling

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 54 | the `error` interface | KEPT | `types.go:23-27`, `:239-250` | 保持现状（无需改动） |
| 55 | `errors.New`, `fmt.Errorf`, `%w` wrapping | DEGRADED | `translator.go:2603-2712` | §7.1 Go 控制流「**错误处理惯用法**」小节（`errors.New`/`fmt.Errorf("%w", e)` 建模成**带 cause 的 error 对象**：`NEW err = <fmt>.Errorf/1` + `FIELDSTORE err.cause = e` ⇒ `errors.Is`/`errors.As` 的 unwrap 关系可见（现状两者都是普通调用，`translator.go:2603-2712`））+ §9 并发与同步方案表「Go `context` 取消 / `errors.Is`,`errors.As` 的 wrapping」行（`NEW err = <fmt>.Errorf/1` + `FIELDSTORE err.cause = e`） |
| 56 | `if err != nil` idiom | KEPT | `translator.go:2167-2179` | 保持现状（无需改动） |
| 57 | `panic` | KEPT | `translator.go:2628-2632` | 保持现状（无需改动） |
| 58 | `recover` | DEGRADED | `translator.go:2938-2940` | §7.1 Go 控制流「**`panic`/`recover`**」小节（`recover` = `ast.CallExpr`（`types.Builtin`），deferred 闭包里的 `recover()` 调用点降级为 `CATCH_HANDLER`（`ctorType`=`interface{}`，捕获一切），并在 deferred 闭包函数的 `TryRegion` 里声明 `catchTypes=["interface{}"]` ⇒ 「被 recover 的 panic」第一次可见） |
| 59 | custom error types | KEPT | `types.go:61-70` | 保持现状（无需改动） |
| 60 | `errors.Is` / `errors.As` | DEGRADED | `translator.go:2603-2712` | §7.1 Go 控制流「**错误处理惯用法**」小节（`errors.Is`/`errors.As` 的 unwrap 关系可见（现状两者都是普通调用，`translator.go:2603-2712`））+ §9 并发与同步方案表「Go `context` 取消 / `errors.Is`,`errors.As` 的 wrapping」行 |
| 61 | exception regions (`CATCH_HANDLER` / `FINALLY_ENTER`) | NOT FOUND | no emitter in `plumb-frontend/go-sidecar/`; the JVM side passes `List.of()` for try regions (`GoTacFrontend.java:666-669`) | §4 wire 协议现状表 IR-F 行（函数 `tryRegions`；Go 现状「无；mapper `List.of()`（`GoTacFrontend.java:666`）」⇒ 新增（含 `catchTypes`/`finallyExits`/`resourceVars`/`syntheticFinally`））+ §7.1 Go 控制流「**`panic`/`recover`**」小节（`CATCH_HANDLER` + `catchTypes=["interface{}"]`）+ §11.1 改动清单「`GoTacFrontend.java`」行（`tryRegions` 不再 `List.of()`（`:666`）；精度按 `drops` 推导（`:613-616`））+ §12 门禁 H 行「**H 零静默**」 |

### A.7 Operators

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 62 | arithmetic (`+ - * / %`) | KEPT | `translator.go:2167-2179` | 保持现状（无需改动） |
| 63 | compound assignment (`+= -= *= /= %= <<= >>= &= \|= ^= &^=`) | DROPPED-SILENT | `translator.go:815-872` | §6.1 Go 表达式面表「复合赋值 `x += e`（11 算子）」行（`LOAD t1 = x` ; `BINOP t2 = t1 ADD e` ; `STORE x = t2`；`&^=` 走 `BITNOT`+`AND`；现状「退化成 `MOVE`（`translator.go:815-872`，落点 `:852-853`）」）+ §3「**逐条被替代掉的错误做法**」段（❌ 复合赋值 → `MOVE`（`translator.go:852-853`）⇒ ✅ 读-算-写三步）+ §11.1 改动清单「`translator.go`」行（`translateAssign`（`:815-872`）… 按 `statement.Tok` 分派复合赋值） |
| 64 | comparison | KEPT | `translator.go:2204-2215` | 保持现状（无需改动） |
| 65 | string concatenation `+` | KEPT | `translator.go:2184-2185` | 保持现状（无需改动） |
| 66 | bitwise `& \| ^` | KEPT | `translator.go:2194-2199` | 保持现状（无需改动） |
| 67 | `&^` (and not) | DROPPED-SILENT | `translator.go:2182-2222` (the fall-through at `:2221` returns `"ADD"`) | §6.1 Go 表达式面表「`&^`」行（`UNARYOP u = BITNOT b` ; `BINOP t = u AND c`；现状兜底 `"ADD"`（`translator.go:2182-2222`））+ §3「**逐条被替代掉的错误做法**」段（❌ `&^` → `ADD`（`translator.go:2221`）⇒ ✅ `UNARYOP BITNOT` + `BINOP AND` 两步）+ §11.1 改动清单「`translator.go`」行（`binaryOperator`（`:2182-2222`）… 删兜底 `return "ADD"` 改编译期报错） |
| 68 | shifts `<< >>` | KEPT | `translator.go:2200-2203` | 保持现状（无需改动） |
| 69 | logical `&& \|\|` | KEPT | `translator.go:2216-2219` | 保持现状（无需改动） |
| 70 | `++` / `--` | KEPT | `translator.go:1953-1969` | 保持现状（无需改动） |
| 71 | address-of `&` | DEGRADED | `translator.go:2244-2254` | §6.1 Go 表达式面表「取址 `&x` / 解引用 `*p` / `*p = v`」行（`&x` → `NEW p = <go>.addr/1(x)`（cell）；IR 能力列 IR-E（合成 cell 类）） |
| 72 | dereference `*p` | DEGRADED | `translator.go:2393-2406` | §6.1 Go 表达式面表「取址 `&x` / 解引用 `*p` / `*p = v`」行（`*p` → `FIELDLOAD t = p.value`；IR-E（合成 cell 类））+ §11.1 改动清单「`translator.go`」行（`translateStar`（`:2393-2406`）… 解引用 → `FIELDLOAD`） |
| 73 | assignment through a pointer (`*p = v`) | DROPPED-NOTED | `translator.go:867-869` | §6.1 Go 表达式面表「取址 `&x` / 解引用 `*p` / `*p = v`」行（`*p = v` → `FIELDSTORE p.value = v`）+ §11.1 改动清单「`translator.go`」行（`translateAssign`（`:815-872`）… 补 `*p = v` 的存储路径（现状 `:867-869` 只报）） |
| 74 | slice expression `a[1:2]` | KEPT | `translator.go:2359-2380` | 保持现状（无需改动） |
| 75 | slice expression `a[1:2:3]` | DEGRADED | `translator.go:2359-2380` | §6.1 Go 表达式面表「三索引切片 `a[1:2:3]`」行（`CALL <go>.slice/3(a,1,2)` + `CALL <go>.sliceCap/3(a,1,2,3)`；现状「`Max` 未读（`translator.go:2359-2380`）」） |
| 76 | variadic spread `f(xs...)` | DEGRADED | `translator.go:2677-2679` | §6.1 Go 表达式面表「多返回值 / map 双返回 / channel 双返回 / 裸 `return` / 变参」行（变参在调用点打包 `NEWARRAY`；IR 能力列 **IR-AH**）+ §11.1 改动清单「`translator.go`」行（`translateCall`（`:2603-2721`）… 变参打包） |

### A.8 Values and types

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 77 | basic literals (int / float / char / string) | KEPT | `translator.go:2101-2124` | 保持现状（无需改动） |
| 78 | imaginary literals (`3i`) | DROPPED-NOTED | `translator.go:2107-2112` | §6.1 Go 表达式面表「虚数 literal `3i`」行（TAC 无复数类型 ⇒ `UNINTERPRETED` + `drop(VALUE)`；IR 能力列 IR-B）+ §3「**精度口径统一（禁止各线各写）**」段（仅 `VALUE` drop ⇒ `SOURCE_FULL`） |
| 79 | raw string literals (backticks) | KEPT | `translator.go:2115-2120` | 保持现状（无需改动） |
| 80 | interpreted strings with escapes | KEPT | `translator.go:2115-2120` | 保持现状（无需改动） |
| 81 | `nil` | KEPT | `translator.go:2070-2072` | 保持现状（无需改动） |
| 82 | `new` | KEPT | `translator.go:2634-2648` | 保持现状（无需改动） |
| 83 | `make` (slice) | DEGRADED | `translator.go:2732-2754` | §6.1 Go 表达式面表「`make`/`new`/`panic`/`recover`」行（切片：`NEWARRAY t = T(n)`，容量进 `Lhs2`（`TacInstruction.java:194-195`）；IR 能力列 IR-E）+ §11.1 改动清单「`translator.go`」行（`translateMake`（`:2722-2775`）… 容量进 `NEWARRAY.lhs2`） |
| 84 | `make` (map / chan) | DEGRADED | `translator.go:2756-2774` | §6.1 Go 表达式面表「`make`/`new`/`panic`/`recover`」行（map/chan：`NEW t = <go>.makeMap/0>` / `<go>.makeChan/0>`，容量与方向进 `TacTypeDecl`；IR-E）+ §8 类型面方案表「Go map `map[K]V` / chan `chan T`」行（容量与方向进 `TacTypeDecl`） |
| 85 | composite literals | KEPT | `translator.go:2425-2497` | 保持现状（无需改动） |
| 86 | `len` / `cap` | KEPT | `translator.go:2938-2940` | 保持现状（无需改动） |
| 87 | `append` | DEGRADED | `translator.go:2938-2940` | §6.1 Go 表达式面表「内建 `append`/`copy`/`delete`/`min`/`max`/`len`/`cap`」行（`CALL <go>.xxx/N`；操作数与结果的污点关系由 callee summary 表达；现状「无关系（`translator.go:2938-2940`）」） |
| 88 | `copy` | DEGRADED | `translator.go:2938-2940` | §6.1 Go 表达式面表「内建 `append`/`copy`/`delete`/`min`/`max`/`len`/`cap`」行（`CALL <go>.xxx/N`；操作数与结果的污点关系由 callee summary 表达） |
| 89 | `delete` | DEGRADED | `translator.go:2938-2940` | §6.1 Go 表达式面表「内建 `append`/`copy`/`delete`/`min`/`max`/`len`/`cap`」行（`CALL <go>.xxx/N`；操作数与结果的污点关系由 callee summary 表达） |
| 90 | `min` / `max` | DEGRADED | `translator.go:2938-2940` | §6.1 Go 表达式面表「内建 `append`/`copy`/`delete`/`min`/`max`/`len`/`cap`」行（`CALL <go>.xxx/N`；操作数与结果的污点关系由 callee summary 表达） |
| 91 | `clear` | DEGRADED | `translator.go:2938-2940` | §6.1 Go 表达式面表「`clear`」行（`UNARYOP t = CLEAR e`（Go 语义是**移除**元素 taint）；IR 能力列 **IR-AJ**）+ §10「**IR-AJ `UnaryOperator` 需要追加 `CLEAR`（Go `clear`）**」 |
| 92 | type conversion `T(x)` | KEPT | `translator.go:2612-2626` | 保持现状（无需改动） |
| 93 | the `unsafe` package | DEGRADED | `types.go:144-145` | §6.1 Go 表达式面表「`unsafe.Pointer` / `reflect` 读写」行（指针 = CLASS(`unsafe.Pointer`) + 指针语义；反射 = `UNINTERPRETED`（黑盒）+ `drop(VALUE)`；IR 能力列 IR-E / IR-B；现状「仅名字（`types.go:144-145`）」） |

### A.9 Functions

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 94 | parameters, grouped (`a, b int`) | KEPT | `translator.go:329-331` | 保持现状（无需改动） |
| 95 | variadic parameters (`xs ...int`) | DEGRADED | `translator.go:329-331`, `:2677-2679` | §6.1 Go 表达式面表「多返回值 / map 双返回 / channel 双返回 / 裸 `return` / 变参」行（变参在调用点打包 `NEWARRAY`；IR-AH）+ §11.1 改动清单「`translator.go`」行（`translateCall`（`:2603-2721`）… 变参打包） |
| 96 | multiple return values | DEGRADED | `translator.go:1103-1105` | §6.1 Go 表达式面表「多返回值 / map 双返回 / channel 双返回 / 裸 `return` / 变参」行（**全部靠「调用的多个输出」**：CALL 的 `dest=v` + `resultDests[i]=第 i 个结果`；`out`/`ref` 语义用 `refOutDests`；IR 能力列 **IR-AH**）+ §10「**IR-AH `TacInstruction` 需要表达「调用的多个输出」与「按引用实参」**」+ §11.1 改动清单「`translator.go`」行（`translateMultiAssign`（`:893-938`）… 第 2..n 结果走 `resultDests`） |
| 97 | named return values | DEGRADED | `translator.go:335-341`, `:1078-1079` | §6.1 Go 表达式面表「多返回值 / map 双返回 / channel 双返回 / 裸 `return` / 变参」行（裸 `return` 取具名结果当前值；IR 能力列 **IR-AH**）+ §11.1 改动清单「`translator.go`」行（`translateReturn`（`:1077-1106`）… 裸 `return` 取具名结果） |
| 98 | results | KEPT | `translator.go:1077-1106` | 保持现状（无需改动） |
| 99 | deferred modification of a named result | DROPPED-SILENT | `translator.go:445-459` | §6.1 Go 表达式面表「闭包**按引用捕获**」行（捕获变量提升为 **cell 对象** ⇒ deferred 闭包写的是**同一个**存储，不再是形参副本）+ §6.1 Go 表达式面表「多返回值 / map 双返回 / channel 双返回 / 裸 `return` / 变参」行（裸 `return` 取具名结果当前值）+ §7.1 Go 控制流「**`defer`**」小节（改为**放在函数所有出口之前**） |
| 100 | `recover` only works in a deferred function | DEGRADED | `translator.go:2938-2940` | §7.1 Go 控制流「**`panic`/`recover`**」小节（「`recover` 只在 deferred 函数里直接调用才有效」是**源码可判定**的 ⇒ 完整支持：出现在非 deferred 函数时记 `drop(SEMANTICS)`） |

### A.10 Closures

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 101 | the closure body as its own function, captures as leading parameters | DEGRADED | `translator.go:396-407` (doc + entry), `:445-459` (captures become `sub.params`), `:466-471` (the closure record and its callee key), `:852-862` (the closure stored in a variable), `:2811-2824` (the call site passes the captures) | §5.1 Go 声明面表「函数字面量（闭包）」行（合成 `TacFunction`，捕获作前导形参；`memberKind` 列 `CLOSURE`（**IR-AG**）；IR-E）+ §6.1 Go 表达式面表「闭包**按引用捕获**」行（捕获变量提升为 **cell 对象**）+ §11.1 改动清单「`translator.go`」行（`translateFuncLit`（`:407-478`）… 捕获按引用（cell 对象化）+ `memberKind=CLOSURE`）+ §14.1 K4（现状捕获形参在引擎侧被**当成污点源** ⇒ cell 形参要能在引擎侧与「真形参」区分） |
| 102 | capturing a loop variable (pre-1.22 vs post-1.22 semantics) | DEGRADED | `translator.go:489-520` | §5.1 Go 声明面表「**Go 版本假设**（`go.mod` 的 `go` 指令 / 构建标签）」行（会话级 `goVersion`）+ §6.1 Go 表达式面表「闭包**按引用捕获**」行（**按 Go 版本分两种捕获形态**：post-1.22 每轮新 cell / pre-1.22 共享一个 cell）+ §7.1 Go 控制流「**Go 版本假设（全局声明，不只属于 `range`）**」段（版本未知 ⇒ 记 `drop(SEMANTICS, "go.version")`；已知 ≥1.22 ⇒ `drop(SEMANTICS, "go.loopVar.scope")`；**⚠ R20 实测更正了「取哪一侧」的方向**）+ §11.1 改动清单「`session.go`」行（读 `go.mod` 的 `go` 指令） |
| 103 | capturing by reference (the write-back) | DROPPED-SILENT | `translator.go:445-459` | §6.1 Go 表达式面表「闭包**按引用捕获**」行（捕获变量提升为 **cell 对象**：`NEW cell = <go>.cell/0()` + `FIELDSTORE cell.value = x`；闭包体 `FIELDLOAD t = cell.value` / `FIELDSTORE cell.value = v` ⇒ 写回方向恢复；IR 能力列「**不需新 IR**（FIELDLOAD/FIELDSTORE + 会话级 `goVersion` 假设）」）+ §11.1 改动清单「`translator.go`」行（`translateFuncLit`（`:407-478`）、`capturedVariables`（`:489-520`）… 捕获按引用（cell 对象化）+ `memberKind=CLOSURE`） |
| 104 | immediately-invoked func literals | KEPT | `translator.go:2886-2900` | 保持现状（无需改动） |
| 105 | function values (`f := g`) | DROPPED-NOTED | `translator.go:2093`, `:2823` | §6.1 Go 表达式面表「函数值 / 方法值 / 方法表达式」行（合成闭包 + `MOVE`；现状 报 UNHANDLED（`translator.go:2284`、`:2302`、`:2305`）；IR-E）+ §11.1 改动清单「`translator.go`」行（`translateSelector`（`:2263-2307`）… 方法值/表达式 → 合成闭包） |
| 106 | method values (`x.M`) | DROPPED-NOTED | `translator.go:2284` | §6.1 Go 表达式面表「函数值 / 方法值 / 方法表达式」行（合成闭包 + `MOVE`；IR-E）+ §11.1 改动清单「`translator.go`」行（`translateSelector`（`:2263-2307`）… 方法值/表达式 → 合成闭包） |
| 107 | method expressions (`T.M`) | DROPPED-NOTED | `translator.go:2302` | §6.1 Go 表达式面表「函数值 / 方法值 / 方法表达式」行（`go/types` 的 `types.Selection` 区分 `MethodVal`/`MethodExpr`；合成闭包 + `MOVE`；IR-E）+ §11.1 改动清单「`translator.go`」行（`translateSelector`（`:2263-2307`）… 方法值/表达式 → 合成闭包） |

### A.11 Packages and build

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 108 | `//go:build` constraints | KEPT | `session.go:1191-1246`, `:1279-1321` | 保持现状（无需改动） |
| 109 | `//go:embed` | DROPPED-SILENT | `session.go:1209-1246` (only *build constraints* are read from the header) | §13 的 `U8`（Go `//go:embed` 的**文件字节**：保守建模形状「该值按未知处理 + `drop(VALUE)`」；对精度的影响「依赖该常量值的规则不触发」）+ §3「**允许的例外只有四类根因**」段 ④ **无运行时对应物**（Go `//go:embed` 的文件字节、C# 特性里的编译期常量值）+ §14.2 Q5（保守建模（§13 的 `U8`）；不列入首批） |
| 110 | vendoring | DEGRADED | `session.go:798-942` | §13 的 `U9`（Go **vendoring**：为什么无法静态表达「依赖从哪来是**构建期**事实，**没有运行时对应物**」；保守建模形状「**显式边界**：依赖必须由 **Java 侧在请求的 import 列表里点名**（带 export data 或源码目录）⇒ **被点名的 vendored 依赖照常降级**；**未被点名的不存在于 TAC 里**」）+ §5.1 Go 声明面表「**import 解析 / vendoring**」行 + §11.1 改动清单「`session.go`」行 |
| 111 | `internal` visibility | NOT FOUND | no handling in `plumb-frontend/go-sidecar/` | §13 的 `U10`（Go **包级可见性**：为什么无法静态表达「可见性规则是**编译期**约束，**没有运行时对应物**」；保守建模形状「**显式取舍**：本方案**不实现包级可见性** ⇒ 引擎把 `internal` 包**当公开包分析**（过近似：不会漏报，只可能把不可达的包当成可达）⇒ 记 `drop(SEMANTICS, "pkg.visibility")` 让取舍在精度标注里可见」）+ §5.1 Go 声明面表「**包可见性（`internal`）**」行 |
| 112 | `go.mod` module paths | KEPT | `protocol.go:527-534`; `session.go:906-941` | 保持现状（无需改动） |
| 113 | import alias resolution | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |

### A.12 Reflection and tags

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 114 | `reflect` package usage | DEGRADED | `translator.go:2603-2712` | §6.1 Go 表达式面表「`unsafe.Pointer` / `reflect` 读写」行（反射 = `UNINTERPRETED`（黑盒）+ `drop(VALUE)`；IR 能力列 IR-E / IR-B；现状「普通调用（`translator.go:2603-2712`）」） |
| 115 | struct tags as a serialization boundary | DROPPED-SILENT | no tag reader in the sidecar; no tag field in `protocol.go:190-246` | §5.1 Go 声明面表「struct tag」行（`TacTypeDecl.fieldAnnotations` 或独立 `fieldTags`；注解通道列「**独立字段**」）+ §4 wire 协议现状表 P0-6 行（Go struct tag 走**独立字段** `fieldTags`（tag 不是注解））+ §11.1 改动清单「`protocol.go`」行（新增 `programDrops`/`types`/`fieldTags`）+ §14.2 Q3（分字段 `annotations` / `fieldTags`） |

### A.13 Standard-library calls that matter to analysis

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 116 | `os.Open` / `os.ReadFile` / `os.Create` | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 117 | `net/http` server handlers | DROPPED-NOTED | `translator.go:2093`, `:2823` | §6.1 Go 表达式面表「**把函数值传给注册型 API**（`http.HandleFunc("/x", h)` / `http.Handle` / `ServeMux.Handle`）」行（注册调用本身**必须可见**：`CALL <net/http>.HandleFunc/2 args=[t_url, t_handler]`，且 `t_handler` 是**合成闭包**（与上一行同一形状）⇒ 引擎据此知道「这个 handler 是可达的入口」；IR-P（callee 规范）+ IR-E（`ownerType`）） |
| 118 | `http.Get` / `http.Post` | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 119 | `io.ReadAll` | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 120 | `fmt.Fprintf` / `fmt.Sprintf` | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 121 | `exec.Command` | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 122 | `os/exec` | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 123 | `sql` drivers | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 124 | `encoding/json` `Unmarshal` / `Marshal` | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |
| 125 | `log.*` | KEPT | `translator.go:2856-2870` | 保持现状（无需改动） |

### A.14 Cross-cutting (things the checklist asks about that are not single constructs)

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 126 | the **arguments of an unresolved call** | DROPPED-SILENT | `translator.go:2603-2609` | §11.1 改动清单「`translator.go`」行（`translateCall`（`:2603-2721`）… **解析失败时仍遍历实参**（现状 `:2604-2609` 直接 `havoc` 返回，实参子树全丢））+ §12 门禁 H 行「**H 零静默**」 |
| 127 | **map contents** (read / write / literal) | DEGRADED | `translator.go:991-1003` (store), `:2343-2356` (read), `:2434-2451` (literal) | §6.1 Go 表达式面表「map 读写 `m[k]`」行（`CALL <go>.mapIndex/2` / `CALL <go>.mapStore/3`；内容流用合成 map 类型 + `FIELDLOAD/FIELDSTORE`；IR-E（合成类））+ §8 类型面方案表「Go map `map[K]V` / chan `chan T`」行（内容由 FIELDLOAD/FIELDSTORE 表达）+ §6.1 Go 表达式面表「复合字面量（键/位/嵌套）」行（键控：`NEW` + 逐字段 `FIELDSTORE`） |
| 128 | the callee's **parameter types** (`calleeParamTypes`) | NOT FOUND | the wire `Instruction` has no `calleeParamTypes` field (`protocol.go:190-246`); the mapper never sets one (`GoTacFrontend.java:681-754`) | §4 wire 协议现状表「`calleeParamTypes`」行（Go 现状「无（`protocol.go:191-246`）」⇒ 新增（重载精确匹配的前提））+ §11.1 改动清单「`protocol.go`」行（指令加 `guard`/`labelRef`/`ownerType`/`calleeParamTypes`） |
| 129 | the **receiver's type** is visible | KEPT | `types.go:191-208`; `translator.go:2843-2849` | 保持现状（无需改动） |
| 130 | a **BRANCH per branch** and a **back-edge per loop** | KEPT | `translator.go:1124-1191`, `:1236-1375`, `:1476-1545` | 保持现状（无需改动） |
| 131 | **closure / goroutine bodies** are visible as functions | KEPT | `translator.go:407-478`, `:1848-1865` | 保持现状（无需改动） |
| 132 | the **side table** `deferredCalls` and the `startsGoroutine` flag | KEPT | `protocol.go:290-360`; `GoTacFrontend.java:627-657` | 保持现状（无需改动） |
| 133 | the **execution effects** `DEFERRED` / `CONCURRENT` | KEPT | `protocol.go:240-245`; `translator.go:1804-1811`, `:1848-1861`; `GoTacFrontend.java:706-720` | 保持现状（无需改动） |

**附录 A 小计**：133 条（`KEPT` 68 条按审计结论保持现状）。**133 条全部有落点**（A 完整表达 **55** / B 显式记账 **7** / C 永久例外 **3**；连同 68 条 `KEPT` 正好 133），**`⚠` = 0**——本轮初稿的 3 条 `⚠`（Go #102、Go #110、Go #111）已全部补齐，逐条见下一节。

### A.15 原「⚠ 未覆盖清单」（Go）—— 已全部补齐

本节初稿列了 3 条 `⚠`（Go #102 循环变量捕获语义、Go #110 vendoring、Go #111 `internal` 可见性）。
**这 3 条已在本轮全部补齐**：台账正文里对应的行已改成真实落点，**附录内 `⚠` 归零**。

| 编号 | construct | 现在的落点 | 分类 |
|---|---|---|---|
| U-GO1 | Go #102 循环变量捕获语义（pre- vs post-1.22） | §5.1 Go 声明面表「**Go 版本假设**（`go.mod` 的 `go` 指令 / 构建标签）」行 + §6.1 Go 表达式面表「闭包**按引用捕获**」行（**按版本分两种捕获形态**）+ §7.1 Go 控制流「**Go 版本假设（全局声明，不只属于 `range`）**」段 + §11.1 改动清单「`session.go`」行 | **完整表达**（版本未知时记 `drop(SEMANTICS, "go.version")`、已知 ≥1.22 时记 `drop(SEMANTICS, "go.loopVar.scope")`；**⚠ R20 实测更正了「取哪一侧」的方向** —— 保守的那一侧是 per-iteration，不是共享 cell；承载能力 = 既有 `FIELDLOAD`/`FIELDSTORE` + 会话级假设，**不需新 IR**） |
| U-GO2 | Go #110 vendoring | §13 的 `U9` + §5.1 Go 声明面表「**import 解析 / vendoring**」行 + §11.1 改动清单「`session.go`」行 | **`C` 档**（**规则本身可静态判定** —— `go.mod` + `vendor/modules.txt` 给出依赖集 —— 本方案**未实现** ⇒ **前端工作量 + 显式边界**，不是 IR 能力缺口：依赖必须由 Java 侧在 import 列表里点名） |
| U-GO3 | Go #111 `internal` 可见性 | §13 的 `U10` + §5.1 Go 声明面表「**包可见性（`internal`）**」行 | **`C` 档**（**规则本身可静态判定** —— 导入路径的作用域 —— 本方案**未实现** ⇒ **前端工作量 + 显式取舍**，不是 IR 能力缺口：按公开包分析，过近似 + `drop(SEMANTICS, "pkg.visibility")`） |

---

## 附录 B. C# 审计台账 A 节（编号 1–162）逐条去向（162 条）

**审计来源**：`D:\work\tmp\audit\06-csharp.md` 的 `## A. Coverage ledger` 节（`### A.1 Types, classes, members` … `### A.4 Wire / IR channels …`，共 4 个子小节）。
**台账行数**：**162 行（逻辑行）**。审计 A 节的**物理**表行是 160 行，但 `06-csharp.md:110` **丢了一个换行**，把**三行**（`event` / `add`/`remove` accessors / auto-properties）焊在同一物理行上 ⇒ 逻辑行 = 160 + 2 = **162**。`12-total-report.md:211` 的「C# | 160 | 32 | 46 | 80 | 44 | 36 | 2」用的是**物理行**口径（它的 32+46+80+2 = 160）⇒ **官方 `checked` 比逻辑行数少 2**；本附录按**逻辑行**全覆盖 **162** 行。两套口径的差值逐条见 `12-语法覆盖情况.md` §1。
**子小节标题原样保留**审计的 `### A.x …`（C# 侧审计自己的 A 节编号，**与附录 A 的 Go `A.1`–`A.14` 是两套不同的标题**，靠标题文字区分）；「#」列按 A 节**逻辑行顺序**连续编号 1–162（跨子小节不重排）。
**状态词映射（按审计自己的图例）**：审计 §A 开头的图例写「`DS` = DROPPED-SILENT · `DN` = DROPPED-NOTED」，本附录把缩写**展开成全称**（`DS` → `DROPPED-SILENT`，`DN` → `DROPPED-NOTED`），**结论未改写**；`NOT FOUND` 是审计 §A 的第五个状态词，原样保留。
**「审计证据」列**是审计原文 `file:line` 的**转录**，**不重新核对行号**（审计的行号会漂）。
**「本方案的去向」列**给出本文档里**真实存在**的落点：**章节号 + 表行原文片段**（片段可在本文件内直接 grep 到）。`KEPT` 行按审计结论写「保持现状（无需改动）」；其余行给出落点。**本附录初稿有 12 行以 `⚠ 未覆盖 —— ` 开头，本轮已全部改为真实落点（见 A.15 / B.5），`⚠` 归零。**
**一处审计排版说明（已在本附录修掉）**：审计 A.1 的 `event`、`add`/`remove` accessors、auto-properties 三行被**焊在同一物理行**上（`06-csharp.md:110`）⇒ 本附录按**逻辑行**拆成 **#85 / #86 / #87** 三行，各自保留原状态与原证据；`12-total-report.md:273` 对此的记法是「两侧都按 1 行计」。
**与审计 §B 节的关系**：审计 §B（丢弃清单，编号 B1–B40）是本 A 节的**子集**，两套编号**各自独立**；本附录覆盖的是**完整 A 节**。

### A.1 Types, classes, members

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 1 | class | DEGRADED | `ProjectSession.cs:398`; `Protocol.cs:194-237` | §5.2 C# 声明面表「class / struct / interface / record」行（`TacTypeDecl`（`isInterface`/`isRecord` 区分）；IR-E）+ §11.2 改动清单「`TypeMapper.cs`」行（callee key 改 `<owner>.<methodName>/<arity>` ⇒ owner 是 **FQN**）+ §14.2 Q4（引擎按 FQN 合并） |
| 2 | struct | DEGRADED | `TacTranslator.cs:391-417` | §5.2 C# 声明面表「class / struct / interface / record」行 + §5.2 C# 声明面表「**通过裸标识符写字段**（`X = x;`，出现在构造器体 / setter 体里）」行（完整形态：加 `IFieldSymbol` 分支 ⇒ `FIELDSTORE dest=this.X lhs1=value ownerType=<声明类型 FQN>` ⇒ 构造器里的字段写入终于发生；IR-E（`ownerType`））+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateMemberAccess`（`:1454-1544`）… `FIELDLOAD`/`FIELDSTORE` 补 `ownerType`） |
| 3 | record (positional) | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「class / struct / interface / record」行 + §5.2 C# 声明面表「位置记录 / 主构造器」行（位置记录合成主构造器 + `get_X`/`set_X` + `Deconstruct`；主构造器合成函数 + 捕获形参；`memberKind` 列 `CONSTRUCTOR`/`GETTER`/`SETTER`/`METHOD`）+ §11.2 改动清单「`ProjectSession.cs`」行（改穷尽 `switch` + `default` 发 drop；补 … 位置记录/主构造器 …） |
| 4 | record (nominal) | DEGRADED | `ProjectSession.cs:410` | §5.2 C# 声明面表「位置记录 / 主构造器」行 + §5.2 C# 声明面表「class / struct / interface / record」行 |
| 5 | interface | KEPT | `ProjectSession.cs:402,410` | 保持现状（无需改动） |
| 6 | enum | DEGRADED | `TacTranslator.cs:1476-1480,2159-2183` | §5.2 C# 声明面表「enum / delegate」行（`TacTypeDecl{isEnum=true}` + 成员常量；IR-E）+ §8 类型面方案表「C# `void` / 枚举 / 委托 / `Span<T>`」行（枚举取底层域；`TacTypeDecl{isEnum=true}`） |
| 7 | delegate | DEGRADED | `TypeMapper.cs:73` | §5.2 C# 声明面表「enum / delegate」行（委托类型 + `Invoke` 成员；`memberKind` 列 `DELEGATE_INVOKE`）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 委托） |
| 8 | static class | KEPT | `ProjectSession.cs:398` | 保持现状（无需改动） |
| 9 | abstract | KEPT | `TacTranslator.cs:143-149` | 保持现状（无需改动） |
| 10 | sealed | DROPPED-SILENT | — | §5.2 C# 声明面表「`readonly`/`volatile`/`sealed`/`new`/`required`/`ref struct`」行（**没有专属节点**：都是 `SyntaxKind.*Keyword` 修饰符 token \| 修饰符，无运行时行为 ⇒ 不进 TAC） |
| 11 | partial (class/method/property) | KEPT | `ProjectSession.cs:398` | 保持现状（无需改动） |
| 12 | generic type | DEGRADED | `TypeMapper.cs:99-108` | §8 类型面方案表「C# `string` / `T`（引用）/ `T?`（引用注解）」行（`typeArguments`（IR-K）；现状泛型实参被擦除（`TypeMapper.cs:99-108`））+ §5.2 C# 声明面表「方法 / 重载」行（一个 `TacFunction`；重载靠 `calleeParamTypes` 区分；IR-E + wire `calleeParamTypes`）+ §4 wire 协议现状表 IR-K 行 |
| 13 | variance | DROPPED-SILENT | — | §5.2 C# 声明面表「**泛型类型参数 + 约束 + 变型**」行（`variance` = `INVARIANT`/`COVARIANT`/`CONTRAVARIANT`，是 **IR-N 的字段扩展**，不新增 IR 编号；调用点的**实参替换**按变型方向判定合法性，不满足 ⇒ 记 `drop(TYPE, "variance")`）+ §8 类型面方案表「C# **泛型型变的变型与约束**」行（**引擎侧必须消费**：子类型判定按变型方向放宽）+ §11.2 改动清单「`ProjectSession.cs`」行 |
| 14 | generic constraints | DROPPED-SILENT | — | §5.2 C# 声明面表「**泛型类型参数 + 约束 + 变型**」行（`where T : IDisposable` 等约束进 `TacTypeDecl.typeParameters` 条目的 `bound`，**IR-N** 已登记）+ §8 类型面方案表「C# **泛型型变的变型与约束**」行（约束决定**可用成员集** ⇒ 调用点必须能被看见）+ §11.2 改动清单「`ProjectSession.cs`」行 |
| 15 | nested types | DEGRADED | `TypeMapper.cs:110-113` | §5.2 C# 声明面表「class / struct / interface / record」行（`TacTypeDecl`）+ §14.2 Q4（引擎按 FQN 合并）+ §11.2 改动清单「`TypeMapper.cs`」行（callee key 改 `<owner>.<methodName>/<arity>`） |
| 16 | local functions | DEGRADED | `ProjectSession.cs:572-591` | §5.2 C# 声明面表「局部函数」行（合成 `TacFunction` + **捕获作前导形参**（现状完全没传，`ProjectSession.cs:572-591`）；`memberKind` 列 `LOCAL_FUNCTION`（**IR-AG**）；IR-E）+ §11.2 改动清单「`ProjectSession.cs`」行（`TranslateLocalFunction`（`:572-591`）… 局部函数捕获作前导形参） |
| 17 | local types | DROPPED-SILENT | — | 保持现状（无需改动）—— 审计结论即：不存在这个 C# 构念（`file` 类型单列，见本表 `file` types 行） |
| 18 | indexers | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「索引器」行（`get_Item`/`set_Item`；`memberKind` 列 `PROPERTY_ACCESSOR`；IR-E）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 索引器 …） |
| 19 | operators | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「运算符 / 转换运算符」行（callee key 用规范化名；`memberKind` 列 `OPERATOR`；IR-E + IR-P）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 运算符 …） |
| 20 | conversion operators | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「运算符 / 转换运算符」行 + §11.2 改动清单「`ProjectSession.cs`」行（补 … 转换运算符 …） |
| 21 | finalizers | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「终结器」行（一个 `TacFunction`；`memberKind` 列 `FINALIZER`（**IR-AG**）；IR-E + IR-AG）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 终结器 …）+ §10「**IR-AG `TacMemberKind` 需要追加 4 个成员种类**」 |
| 22 | `IDisposable` | DEGRADED | `TypeMapper.cs:73` | §7.2 C# 控制流「**`using`/`await using`**」小节（编译成 `try/finally`：`CALL dispose()`（calleeSig=`<owner>..Dispose/0`）；`await using` 用 `CALL <owner>..DisposeAsync/0` + `Effect.SUSPEND`） |
| 23 | `IAsyncDisposable` | DROPPED-SILENT | `TacTranslator.cs:1097-1108` | §7.2 C# 控制流「**`using`/`await using`**」小节（`await using` 用 `CALL <owner>..DisposeAsync/0` + `Effect.SUSPEND`） |
| 24 | `using` (statement) | DEGRADED | `TacTranslator.cs:1097-1108` | §7.2 C# 控制流「**`using`/`await using`**」小节（编译成 `try/finally`：`L_try: <resource init> ; <body>` → `L_finally: FINALLY_ENTER L_finally ; CALL dispose()` + `TryRegion{syntheticFinally=true, resourceVars=[res], finallyExits=[...]}`）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateUsing`（`:1097-1108`）… `using` 合成 `try/finally` + `Dispose`） |
| 25 | `await using` | DROPPED-SILENT | `TacTranslator.cs:333-361` | §7.2 C# 控制流「**`using`/`await using`**」小节（`await using` 用 `CALL <owner>..DisposeAsync/0` + `Effect.SUSPEND`）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `AwaitUsingStatementSyntax`） |
| 26 | extension methods | KEPT | `TacTranslator.cs:1617,1647-1654` | 保持现状（无需改动） |
| 27 | partial methods | KEPT | `ProjectSession.cs:402` | 保持现状（无需改动） |
| 28 | `ref` / `out` / `in` params | DEGRADED | `Protocol.cs:194-237` (no modifier field); `TypeMapper.cs:163-173` | §6.2 C# 表达式面表「`ref`/`out`/`in` / `params T[]` / 可选与命名实参」行（`out`/`ref`：`CALL f(...)` + `refOutDests` 指回调用点变量；IR 能力列 **IR-AH**）+ §10「**IR-AH `TacInstruction` 需要表达「调用的多个输出」与「按引用实参」**」 |
| 29 | `params` arrays | DEGRADED | `Protocol.cs:194-237` | §6.2 C# 表达式面表「`ref`/`out`/`in` / `params T[]` / 可选与命名实参」行（`params`：调用点 `NEWARRAY` + `CALL f(arr)`；IR-AH） |
| 30 | optional / default params | DEGRADED | `Protocol.cs:194-237` | §6.2 C# 表达式面表「`ref`/`out`/`in` / `params T[]` / 可选与命名实参」行（可选/命名实参：调用点**补齐**省略的默认实参；IR-AH） |
| 31 | named arguments | DEGRADED | `TacTranslator.cs:1632-1642` | §6.2 C# 表达式面表「`ref`/`out`/`in` / `params T[]` / 可选与命名实参」行（命名实参按形参序重排；IR-AH） |
| 32 | `default` literal | DROPPED-SILENT | `TacTranslator.cs:1240-1241` | §6.2 C# 表达式面表「`default(T)` / `typeof(X)` / `nameof(x)` / `sizeof(T)`」行（`default`：值类型 → `CONST`（0/false），引用类型 → `CONST NULL`；现状「`default` 一律 NULL（`TacTranslator.cs:1240-1241`）」）+ §11.2 改动清单「`TacTranslator.cs`」行（`default`/`typeof`（`:1240-1249`）… `default(T)` 按值类型给常量） |
| 33 | tuples | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`stackalloc`/`new T[n]`/集合表达式/元组/解构/`with`」行（元组 = 合成元组类 + `NEW` + `FIELDSTORE`；IR-E） |
| 34 | named tuples | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`stackalloc`/`new T[n]`/集合表达式/元组/解构/`with`」行（元组 = 合成元元组类 + `NEW` + `FIELDSTORE` ⇒ 元素名成为合成类的字段名 ⇒ 值与名字都可见） |
| 35 | deconstruction | DROPPED-NOTED | `TacTranslator.cs:430-433` | §6.2 C# 表达式面表「`stackalloc`/`new T[n]`/集合表达式/元组/解构/`with`」行（解构 = 逐字段 `FIELDLOAD` ⇒ 被绑定的名字终于被赋值） |
| 36 | `dynamic` | DEGRADED | `TypeMapper.cs:73`; `TacTranslator.cs:1580-1589` | §6.2 C# 表达式面表「`dynamic` / `decimal` / `nint`,`nuint` / `T?`（引用）/ `int?`」行（`dynamic` = `UNKNOWN` + `domain=Dynamic`，调用发 `UNINTERPRETED`；IR-D）+ §13 的 `U6`（C# `dynamic` 的**运行期绑定**）+ §11.2 改动清单「`TypeMapper.cs`」行（`dynamic` → UNKNOWN+Dynamic） |
| 37 | object initializer | DROPPED-NOTED | `TacTranslator.cs:391-417`, `:1693-1702` | §6.2 C# 表达式面表「对象初始化器 / 集合初始化器」行（对象：**逐个 setter 调用** `CALL <owner>..set_P/1`；IR-E）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateObjectCreation`（`:1663-1704`）… 对象/集合初始化器逐 setter/逐元素） |
| 38 | collection initializer | DROPPED-SILENT | `TacTranslator.cs:1697` | §6.2 C# 表达式面表「对象初始化器 / 集合初始化器」行（集合：逐元素 `CALL <owner>..Add/1`；IR-E）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateObjectCreation`（`:1663-1704`）… 逐元素） |
| 39 | `readonly` | DROPPED-SILENT | — | §5.2 C# 声明面表「`readonly`/`volatile`/`sealed`/`new`/`required`/`ref struct`」行（修饰符，无运行时行为 ⇒ 不进 TAC） |
| 40 | `const` | KEPT | `TacTranslator.cs:1446-1450,2159-2183` | 保持现状（无需改动） |
| 41 | `volatile` | DROPPED-SILENT | — | §5.2 C# 声明面表「`readonly`/`volatile`/`sealed`/`new`/`required`/`ref struct`」行（修饰符，无运行时行为 ⇒ 不进 TAC） |
| 42 | `static` | KEPT | `TacTranslator.cs:1616-1630` | 保持现状（无需改动） |
| 43 | `virtual` | DEGRADED | `TacTranslator.cs:1579` | §5.2 C# 声明面表「**`virtual`/`override`/`abstract` 动态派发**」行（**不静态绑定**：发一组 `CALL` = 静态声明 `<owner>.M/1` + 该类型层次里**所有已知 override**，各自 `calleeSig` 用 owner FQN ⇒ 引擎的 callee summary 天然取并集；层次外可能存在的 override ⇒ `drop(SEMANTICS, "virtual.open-hierarchy")` + 返回值按最坏情况处理）+ §11.2 改动清单「`TacTranslator.cs`」行 |
| 44 | `override` | KEPT | `ProjectSession.cs:402` | 保持现状（无需改动） |
| 45 | `new` (member hiding) | DROPPED-SILENT | — | §5.2 C# 声明面表「`readonly`/`volatile`/`sealed`/`new`/`required`/`ref struct`」行（修饰符，无运行时行为 ⇒ 不进 TAC） |
| 46 | `extern` | KEPT | `TacTranslator.cs:143-149` | 保持现状（无需改动） |
| 47 | `unsafe` (modifier) | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（块体**照常降级**；`unsafe`/`fixed` 块内指针访问按指针语义降级（见 §6.2））+ §6.2 C# 表达式面表「`checked`/`unchecked` / `unsafe`/指针 / LINQ 查询」行（指针读写用 `FIELDLOAD`/`FIELDSTORE` on 指针 cell）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `UnsafeStatementSyntax`/`FixedStatementSyntax`） |
| 48 | `async` | DEGRADED | `TacTranslator.cs:1164-1175` | §7.2 C# 控制流「**`async`/`await`**」小节（`await e` → `CALL t = await e` + `Effect.SUSPEND`，恢复点记进函数级 `resumePoints`（IR-L）；`async` 方法返回类型保持 `Task<T>`）+ §4 wire 协议现状表 IR-L 行（C#：**新增整个 `effect`**）+ §9 并发与同步方案表「C# `async`/`await` / `IAsyncEnumerable`」行 + §13 的 `U2` （`B` 档：记 `drop(SEMANTICS, "concurrency.order")`） |
| 49 | iterator | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`yield return`/`yield break`**」小节（编译成状态机——合成 `MoveNext` 函数，每个 `yield return` 是一个状态边界 ⇒ 复用 IR-L）+ §13 的 `U4`（协程 / `yield` 的**恢复顺序**） （`B` 档：记 `drop(SEMANTICS, "yield.resumeOrder")`） |
| 50 | nullable reference types `?` | DEGRADED | `TypeMapper.cs:15-21`; `SourceLanguage.java:187-189` | §6.2 C# 表达式面表「`dynamic` / `decimal` / `nint`,`nuint` / `T?`（引用）/ `int?`」行（`T?` 引用**不映射**到 `nullable`（维持 `TypeMapper.cs:15-21` 与 `SourceLanguage.java:210`））+ §8 类型面方案表「C# `string` / `T`（引用）/ `T?`（引用注解）」行（引用类型一律可空（`TypeMapper.cs:15-21`）；**不因注解改变可空性**（`SourceLanguage.java:210`）） |
| 51 | `??` | DEGRADED | `TacTranslator.cs:2078` | §6.2 C# 表达式面表「`??` / `??=` / `?.`」行（`??`：`BRANCH a ? L_val : L_alt ▸ Truthiness(a)`；IR-C）+ §11.2 改动清单「`TacTranslator.cs`」行（`BinaryOperatorOf`（`:2057-2080`）… `??` 不再映射 `ADD`） |
| 52 | `??=` | DEGRADED | `TacTranslator.cs:2045,2078` | §6.2 C# 表达式面表「`??` / `??=` / `?.`」行（`??=` 真分支额外发 `STORE`；IR-C）+ §11.2 改动清单「`TacTranslator.cs`」行（`BinaryOperatorOf`（`:2057-2080`）… `??` 不再映射 `ADD`） |
| 53 | `?.` | KEPT | `TacTranslator.cs:1727-1765` | 保持现状（无需改动） |
| 54 | `?[]` | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`??` / `??=` / `?.` / **`?[]`**」行（`?[]` 与 `?.` **同形**：接收者非空 ⇒ `L_ok: ARRAYLOAD t = a[i]`；为空 ⇒ `L_null`，两路 merge + `BRANCH ▸ Truthiness(a)` + `TernaryRegion`）+ §11.2 改动清单「`TacTranslator.cs`」行 |
| 55 | `is` (type pattern) | DEGRADED | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「`is T` / `is not T` / `as T`」行（`is`：`INSTANCEOF t = x (T)`，`not` 用 `TypeTest.negated`；IR-C）+ §11.2 改动清单「`TacTranslator.cs`」行（`IsPatternExpressionSyntax`（`:1210-1219`）… `not` → `negated` + 子模式求值 + `and`/`or` 合取/析取） |
| 56 | `as` | DROPPED-NOTED | `TacTranslator.cs:1142` (Roslyn: `AsExpressionSyntax` **is** a `BinaryExpressionSyntax`) | §6.2 C# 表达式面表「`is T` / `is not T` / `as T`」行（`as`：`UNARYOP t = CAST x (T)` + `NULL_CHECK t`（失败返回 null，不抛异常）；IR-C） |
| 57 | switch expression | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「switch 表达式」行（分支链 + merge temp + **每 arm 末尾 `JUMP L_end`** + `TacBranchRegion{kind=SWITCH, exhaustive, missingCases}`；IR-I + IR-C）+ §7.2 C# 控制流「**`switch` 语句**」小节末（**`switch` 表达式** = `SwitchExpressionSyntax` → `SwitchExpressionArmSyntax`：同一模板 + merge temp，且**每 arm 末尾必须 `JUMP L_end`**）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `SwitchExpressionSyntax`） |
| 58 | switch statement (value) | DEGRADED | `TacTranslator.cs:992-1085`, `:998` | §7.2 C# 控制流「**`switch` 语句**」小节（⇒ `BRANCH c1 ? L_case1 : L_test2 ▸ Compare(disc, EQ, 1)` → … → `L_test2: INSTANCEOF t = disc (System.Int32)` ⇒ `BRANCH t ? L_case2 : L_test3 ▸ TypeTest(disc, Int32, false)`；`default` 排链尾；`exhaustive`/`missingCases` 取编译器诊断）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateSwitch`（`:992-1085`）… `guard` + `branchRegions`） |
| 59 | switch statement (type) | DROPPED-SILENT | `TacTranslator.cs:1042` | §7.2 C# 控制流「**`switch` 语句**」小节（现状只处理 `CaseSwitchLabelSyntax`，`TacTranslator.cs:1042`）⇒ 补 `CasePatternSwitchLabelSyntax` + `guard` + `TacBranchRegion`）+ §3「**逐条被替代掉的错误做法**」段（❌ `case int i:` 忽略（`TacTranslator.cs:1042`）⇒ ✅ `TypeTest` + 绑定 `MOVE`） |
| 60 | relational patterns | DROPPED-SILENT | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「常量模式 / 关系模式」行（关系：`BINOP t = disc GT 3` ; `BRANCH ▸ Compare(t, GT, 3)`；IR-C） |
| 61 | logical patterns (`and`/`or`/`not`) | DROPPED-SILENT | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「`and`/`or`/`not` 模式 / `when` 守卫 / 模式绑定」行（`not` → `TypeTest.negated=true`；`and`/`or` → 合取/析取；**IR-AF** / IR-C / IR-I）+ §10「**IR-AF `TacGuard` 需要合取/析取组合子**」 |
| 62 | property patterns | DROPPED-SILENT | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「列表模式 / 属性模式」行（属性：`FIELDLOAD t = x.Length` ; `BINOP c = t GT 3` ; `BRANCH ▸ Compare(t, GT, 3)`；**IR-AF** / IR-C（`ownerType` 定归属））+ §11.2 改动清单「`TacTranslator.cs`」行（`IsPatternExpressionSyntax`（`:1210-1219`）… 子模式求值） |
| 63 | list patterns | DROPPED-SILENT | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「列表模式 / 属性模式」行（列表：长度守卫 `Compare(len,EQ,3)` + 逐元素 `Membership`（**合取**）；**IR-AF**）+ §10「**IR-AF `TacGuard` 需要合取/析取组合子**」 |
| 64 | property subpatterns | DROPPED-SILENT | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「列表模式 / 属性模式」行（属性模式的 `Subpattern` 逐项求值；**IR-AF**） |
| 65 | `goto` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`goto`/带标签语句**」小节（现状连标签内的语句一起丢）⇒ `JUMP L` + `LABEL L` + `labelRef={BLOCK, scopeId}`（IR-G）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `GotoStatementSyntax`/`LabeledStatementSyntax`） |
| 66 | labeled statements | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`goto`/带标签语句**」小节 + §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `LabeledStatementSyntax`） |
| 67 | `checked` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（块体**照常降级**；`checked` 额外记「块内算术溢出抛 `OverflowException`」（由 `domain` + checker 表达））+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `CheckedStatementSyntax`） |
| 68 | `unchecked` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（`unchecked` 为回绕）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `UncheckedStatementSyntax`） |
| 69 | `stackalloc` | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`stackalloc`/`new T[n]`/集合表达式/元组/解构/`with`」行（`NEWARRAY t = T(n)` + 逐元素 `ARRAYSTORE`；IR-E）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateObjectCreation`（`:1663-1704`）… `NEWARRAY` 从不发（kind 已存在，`TacInstruction.java:53`）） |
| 70 | `fixed` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（`fixed` 块内指针访问按指针语义降级）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `FixedStatementSyntax`） |
| 71 | `lock` | DEGRADED | `TacTranslator.cs:302-308` | §7.2 C# 控制流「**`lock`**」小节（`<求值 monitor → m>` → `CALL Monitor.Enter(m) (System.Threading.Monitor#Enter/1)` → `LABEL L_crit`（`criticalSections=[{label:L_crit, monitorExpr:m, kind:SYNCHRONIZED}]`）→ `CALL Monitor.Exit(m)`）+ §11.2 改动清单「`TacTranslator.cs`」行（`lock` 分支（`:302-308`）… `Monitor.Enter/Exit` + `criticalSections`）+ §9 并发与同步方案表「C# `lock (m) { }`」行 + §13 的 `U3` （`B` 档：记 `drop(SEMANTICS, "lock.contention")`） |
| 72 | `unsafe` / pointers | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`checked`/`unchecked` / `unsafe`/指针 / LINQ 查询」行（指针读写用 `FIELDLOAD`/`FIELDSTORE` on 指针 cell；IR-D / IR-E / IR-P）+ §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节 |
| 73 | `nameof` | DROPPED-NOTED | `TacTranslator.cs:1579-1589` | §6.2 C# 表达式面表「`default(T)` / `typeof(X)` / `nameof(x)` / `sizeof(T)`」行（`nameof` = `MOVE t = CONST("x")`）+ §11.2 改动清单「`TacTranslator.cs`」行（`IsPatternExpressionSyntax`（`:1210-1219`）… `nameof`/`sizeof`） |
| 74 | `typeof` | DEGRADED | `TacTranslator.cs:1243-1249` | §6.2 C# 表达式面表「`default(T)` / `typeof(X)` / `nameof(x)` / `sizeof(T)`」行（`typeof` = `MOVE t = CONST("System.Int32")`（**不发 `NEW`**）；现状「`typeof` 发 `NEW`（幻影分配，`:1243-1249`）」）+ §11.2 改动清单「`TacTranslator.cs`」行（`default`/`typeof`（`:1240-1249`）… `typeof` 不发 `NEW`） |
| 75 | `sizeof` | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`default(T)` / `typeof(X)` / `nameof(x)` / `sizeof(T)`」行（`sizeof` = `MOVE t = CONST(8)`（按目标平台常量表）；IR-D（sizeof）） |
| 76 | `default` | DROPPED-SILENT | `TacTranslator.cs:1240-1241` | §6.2 C# 表达式面表「`default(T)` / `typeof(X)` / `nameof(x)` / `sizeof(T)`」行（`default`：值类型 → `CONST`（0/false），引用类型 → `CONST NULL`）+ §11.2 改动清单「`TacTranslator.cs`」行（`default`/`typeof`（`:1240-1249`）… `default(T)` 按值类型给常量） |
| 77 | interpolation | DEGRADED | `TacTranslator.cs:1251-1281` | §6.2 C# 表达式面表「插值字符串 `$"{a}"`」行（`CALL <cs>.string.Concat/N(...)`（格式/对齐子句进实参个数）；污点取并集）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateObjectCreation`（`:1663-1704`）… 插值） |
| 78 | raw string literals | KEPT | `TacTranslator.cs:1343-1354` | 保持现状（无需改动） |
| 79 | verbatim strings | KEPT | `TacTranslator.cs:1343-1348` | 保持现状（无需改动） |
| 80 | char literals | KEPT | `TacTranslator.cs:1355-1360` | 保持现状（无需改动） |
| 81 | numeric literals (hex/binary/separators) | KEPT | `TacTranslator.cs:1393-1429` | 保持现状（无需改动） |
| 82 | checked arithmetic | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（`checked` 额外记「块内算术溢出抛 `OverflowException`」） |
| 83 | attributes `[Attr]`, `[Attr(...)]`, positional/named args, `AttributeUsage` | DROPPED-SILENT | sidecar has **no** `GetAttributes`/`AttributeLists` call anywhere (grep: 0 matches); `Protocol.cs:194-237`; `CSharpTacFrontend.java:737-740`; `SidecarTacMapper.java:144-147` | §5.2 C# 声明面表「特性」行（`AttributeListSyntax` → `AttributeSyntax`（`Name`、`ArgumentListSyntax`）\| 进 `annotations`/`annotationValues` 既有槽位（`TacFunction.java:123-126`、`:154`）\| 注解通道列「**本方案的主要通道**」\| IR 能力列「**不需新 IR**（总报告 P0-6）」）+ §4 wire 协议现状表 P0-6 行（新增；C# 读特性）+ §3「**逐条被替代掉的错误做法**」段（❌ 特性 / Go struct tag 丢 ⇒ ✅ 进既有注解槽位）+ §14.2 Q2 |
| 84 | `nameof` inside attributes | DROPPED-SILENT | same as above | §5.2 C# 声明面表「特性」行 + §4 wire 协议现状表 P0-6 行 + §14.2 Q2（`[Authorize(Roles="Admin")]` 的命名参数、`[Obsolete("msg", true)]` 的位置参数需稳定约定） |
| 85 | `event` | DROPPED-NOTED | `TacTranslator.cs:391-417` (identifier form), `:507-518` (member form) | §5.2 C# 声明面表「event」行（`add_X`/`remove_X` 两个访问器 + 字段；`memberKind` 列 `EVENT_ACCESSOR`（**IR-AG**）；IR-E + IR-AG）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 事件访问器 …）+ §10「**IR-AG `TacMemberKind` 需要追加 4 个成员种类**」 |
| 86 | `add`/`remove` accessors | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「event」行（与上一行同一落点：`add_X`/`remove_X` 两个访问器 + 字段；`memberKind` 列 `EVENT_ACCESSOR`，**IR-AG**）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 事件访问器 …）。**审计把这两行焊在同一物理行上**（`06-csharp.md:110` 丢了一个换行）⇒ 本附录按**逻辑行**拆成 85 / 86 两行 |
| 87 | auto-properties (`get`/`set`/`init`) | DEGRADED | `ProjectSession.cs:410-421` | §5.2 C# 声明面表「自动属性 / `init` 访问器」行（**合成 backing field**，`get_X`/`set_X` 变成对该字段的 `FIELDLOAD`/`FIELDSTORE`；现状访问器是**空函数** ⇒ 经 `set_X` 写入的污点对 `get_X` 不可见，审计实测 `Auto.get`/`Auto.set` = 0 条指令）+ §11.2 改动清单「`ProjectSession.cs`」行。**与上一行同源**：审计把三行焊在同一物理行上（`06-csharp.md:110`）⇒ 本附录按**逻辑行**拆成 85 / 86 / 87 三行 |
| 88 | property accessors with bodies | KEPT | `ProjectSession.cs:414-417,525-545` | 保持现状（无需改动） |
| 89 | expression-bodied members | KEPT | `TacTranslator.cs:216-225`; `ProjectSession.cs:418-421,547-570` | 保持现状（无需改动） |
| 90 | primary constructors (C# 12) | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「位置记录 / 主构造器」行（主构造器合成函数 + 捕获形参；`memberKind` 列 `CONSTRUCTOR`/`GETTER`/`SETTER`/`METHOD`）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 主构造器 …） |
| 91 | collection expressions `[1,2,3]` | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`stackalloc`/`new T[n]`/集合表达式/元组/解构/`with`」行（`NEWARRAY t = T(n)` + 逐元素 `ARRAYSTORE`；IR-E） |
| 92 | `required` members | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「`readonly`/`volatile`/`sealed`/`new`/`required`/`ref struct`」行（修饰符，无运行时行为 ⇒ 不进 TAC）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 位置记录/主构造器 …） |
| 93 | `file` types | DEGRADED | `ProjectSession.cs:398` | §5.2 C# 声明面表「class / struct / interface / record」行（`TacTypeDecl`）+ §14.2 Q4（引擎按 FQN 合并） |
| 94 | `with` expressions | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`stackalloc`/`new T[n]`/集合表达式/元组/解构/`with`」行（`with` = `NEW t = <copy>` + 逐字段 `FIELDLOAD`/`FIELDSTORE`；IR-E） |
| 95 | `init` / `set` | KEPT | `ProjectSession.cs:414-417` | 保持现状（无需改动） |
| 96 | `checked` operators | DROPPED-SILENT | `ProjectSession.cs:398-428` | §5.2 C# 声明面表「运算符 / 转换运算符」行（callee key 用规范化名；`memberKind` 列 `OPERATOR`）+ §11.2 改动清单「`ProjectSession.cs`」行（补 … 运算符 …） |
| 97 | `Span<T>` / `ReadOnlySpan<T>` | DEGRADED | `TacTranslator.cs:590-596` | 类型部分：§8 类型面方案表「C# `void` / 枚举 / 委托 / `Span<T>`」行（`typeArguments`；是）；**索引器写**：§6.2 C# 表达式面表「**`Span<T>` / `ReadOnlySpan<T>` 索引器写**」行（`Span<T>` = 合成类 + backing 数组；写 = `ARRAYSTORE span.data[i] = v`；`ReadOnlySpan<T>` 的写路径记 `drop(TYPE, "readonly-span")`）+ §11.2 改动清单「`TacTranslator.cs`」行 |
| 98 | `ref struct` | DROPPED-SILENT | — | §5.2 C# 声明面表「`readonly`/`volatile`/`sealed`/`new`/`required`/`ref struct`」行（修饰符，无运行时行为 ⇒ 不进 TAC） |
| 99 | `nint` / `nuint` | DEGRADED | `TypeMapper.cs:73` | §8 类型面方案表「C# `dynamic` / `nint`,`nuint`」行（**INT,LONG** \| `Integral(64,signed)`；现状 `nint` → `System.IntPtr`（一个类））+ §6.2 C# 表达式面表「`dynamic` / `decimal` / `nint`,`nuint` / `T?`（引用）/ `int?`」行（`nint`/`nuint` = **INT/LONG** + `Integral(64,signed)`；IR-D）+ §11.2 改动清单「`TypeMapper.cs`」行（`nint/nuint` → INT/LONG） |
| 100 | LINQ query syntax | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`checked`/`unchecked` / `unsafe`/指针 / LINQ 查询」行（LINQ = 方法调用链 `CALL Select/2`、`Where/2`、`ToList/0`（真实方法名）；IR-P）+ §11.2 改动清单「`TacTranslator.cs`」行（`IsPatternExpressionSyntax`（`:1210-1219`）… LINQ 查询） |
| 101 | `await foreach` | DROPPED-SILENT | `TacTranslator.cs:943-990` | §7.2 C# 控制流「**`await foreach`（异步枚举）**」段（`GetAsyncEnumerator/0` → `MoveNextAsync/0` + **`Effect.SUSPEND`** + `resumePoints=[{L_head, SUSPEND}]`（**IR-L**）→ `Current/0` → `DisposeAsync/0`）+ §9 并发与同步方案表「C# `async`/`await` / `IAsyncEnumerable`」行 + §11.2 改动清单「`TacTranslator.cs`」行 |
| 102 | `IAsyncEnumerable` | DEGRADED | `TypeMapper.cs:73` | §7.2 C# 控制流「**`foreach`**」小节（迭代协议用**真实方法名**（IR-P））+ §9 并发与同步方案表「C# `async`/`await` / `IAsyncEnumerable`」行（枚举协议用真实方法名（IR-P）） |
| 103 | `yield return` / `yield break` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`yield return`/`yield break`**」小节（编译成状态机——合成 `MoveNext` 函数，每个 `yield return` 是一个状态边界：`L_state_k: MOVE current = v ; Effect.SUSPEND ; resumePoints=[{L_state_k, SUSPEND}] ; RETURN true` ⇒ 复用 IR-L）+ §13 的 `U4` （`B` 档：记 `drop(SEMANTICS, "yield.resumeOrder")`） |
| 104 | exception filters `catch (E e) when (...)` | DROPPED-SILENT | `TacTranslator.cs:775-798` — `clause.Filter` is **never read** | §7.2 C# 控制流「**`try`/`catch`/`finally`**」小节（**`catch (E e) when (f)`**：filter = `CatchClauseSyntax.Filter`（**解析器里有节点，但现状从未被读**，`TacTranslator.cs:775-798`）⇒ 降级为 catch 入口的一条 `BRANCH ▸ Compare(f, NE, false)`，为假时 `JUMP L_next_catch`）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateTry`（`:768-807`）… `clause.Filter` 求值 + guard） |
| 105 | `throw` expressions | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §7.2 C# 控制流「**`try`/`catch`/`finally`**」小节末（`throw` 表达式 = `ThrowExpressionSyntax` ⇒ `THROW`（现状未降级））+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `ThrowExpressionSyntax`） |
| 106 | `using` declarations vs statements | DEGRADED | `TacTranslator.cs:1097-1108`, `:333-361` | §7.2 C# 控制流「**`using`/`await using`**」小节（两种形式都编译成 `try/finally`：`CALL dispose()` / `CALL <owner>..DisposeAsync/0` + `Effect.SUSPEND`）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateUsing`（`:1097-1108`）… `using` 合成 `try/finally` + `Dispose`） |
| 107 | `unsafe` blocks | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（`unsafe` 块内指针访问按指针语义降级）+ §6.2 C# 表达式面表「`checked`/`unchecked` / `unsafe`/指针 / LINQ 查询」行（指针读写用 `FIELDLOAD`/`FIELDSTORE` on 指针 cell） |
| 108 | `goto case` / `goto default` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`goto`/带标签语句**」小节（`goto case` / `goto default` 同样降成 `JUMP L` + `LABEL L` + `labelRef={BLOCK, scopeId}`（IR-G）） |
| 109 | `default` in a switch arm | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「switch 表达式」行（分支链 + merge temp + **每 arm 末尾 `JUMP L_end`** + `TacBranchRegion{kind=SWITCH, exhaustive, missingCases}`；`default` 排链尾） |
| 110 | `is` pattern with `not` | DROPPED-SILENT | `TacTranslator.cs:1214` | §6.2 C# 表达式面表「`is T` / `is not T` / `as T`」行（`not` 用 `TypeTest.negated`）+ §11.2 改动清单「`TacTranslator.cs`」行（`IsPatternExpressionSyntax`（`:1210-1219`）… `not` → `negated`） |
| 111 | `and` / `or` patterns | DROPPED-SILENT | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「`and`/`or`/`not` 模式 / `when` 守卫 / 模式绑定」行（`and`/`or` → 合取/析取；**IR-AF**）+ §10「**IR-AF `TacGuard` 需要合取/析取组合子**」 |
| 112 | list patterns | DROPPED-SILENT | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「列表模式 / 属性模式」行（列表：长度守卫 `Compare(len,EQ,3)` + 逐元素 `Membership`（**合取**）；**IR-AF**） |
| 113 | `u8` literals | KEPT | `TacTranslator.cs:1349-1354` | 保持现状（无需改动） |
| 114 | `field` keyword | NOT FOUND | — | §5.2 C# 声明面表「**`field` 关键字**（C# 13 支撑属性）」行（`field` ⇒ 对**合成 backing field** 的 `FIELDLOAD`/`FIELDSTORE`，`ownerType` = 声明类型 FQN ⇒ 与「自动属性 / `init` 访问器」行的 backing field **同一个存储键**）+ §11.2 改动清单「`TacTranslator.cs`」行 |
| 115 | partial properties | KEPT | `ProjectSession.cs:410` | 保持现状（无需改动） |
| 116 | `extension` blocks | NOT FOUND | — | §5.2 C# 声明面表「扩展方法」行（`ExtensionDeclarationSyntax`（C# 14）/ `MethodDeclarationSyntax` + `SyntaxKind.ExtensionKeyword` \| 首形参即接收者 \| `memberKind` 列 `EXTENSION_FUNCTION` \| IR-E） |

### A.2 Expressions / statements

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 117 | assignments (incl. compound) | DEGRADED | `TacTranslator.cs:2032-2047` vs `:2057-2080` | §6.2 C# 表达式面表「复合赋值 `x *= e`」行（`LOAD t1 = x` ; `BINOP t2 = t1 MUL e` ; `STORE x = t2`（`BinaryOperatorOf` 须扩到赋值 kind）；现状「兜底 `"ADD"`（`TacTranslator.cs:2057-2080`）」）+ §11.2 改动清单「`TacTranslator.cs`」行（`BinaryOperatorOf`（`:2057-2080`）、`IsCompoundAssignment`（`:2032-2047`）… 赋值 kind 扩进算子表） |
| 118 | `++` / `--` | KEPT | `TacTranslator.cs:1980-2030` | 保持现状（无需改动） |
| 119 | `if` / `else` | KEPT | `TacTranslator.cs:719-743` | 保持现状（无需改动） |
| 120 | `while` | KEPT | `TacTranslator.cs:851-882` | 保持现状（无需改动） |
| 121 | `do` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`do`/`while`/`for`**」小节（`do` 现状整块丢失 ⇒ 补 `DoStatementSyntax` case，模板为「先执行体，再测条件」（与 `while` 相反））+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `DoStatementSyntax`）+ §3「**逐条被替代掉的错误做法**」段（❌ `do`/`checked` 整块丢（`TacTranslator.cs:310-313`）⇒ ✅ 块体照降 + 外层语义标记） |
| 122 | `for` | KEPT | `TacTranslator.cs:884-941` | 保持现状（无需改动） |
| 123 | `foreach` | DEGRADED | `TacTranslator.cs:943-990` | §7.2 C# 控制流「**`foreach`**」小节（迭代协议用**真实方法名**（IR-P）：`CALL it = <collType>#.GetEnumerator/0` → `L_head: CALL moved = <enumType>#.MoveNext/0` → `BRANCH moved ? L_body : L_end ▸ Compare(moved, EQ, true)` → `L_body: MOVE elem = <enumType>#.Current/0` → `L_end: CALL <enumType>#.Dispose/0`）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateForEach`（`:943-990`）… 迭代协议（真实方法名）+ 出口边） |
| 124 | `break` / `continue` | KEPT | `TacTranslator.cs:809-849` | 保持现状（无需改动） |
| 125 | `return` | KEPT | `TacTranslator.cs:745-754` | 保持现状（无需改动） |
| 126 | `throw` | KEPT | `TacTranslator.cs:756-766` | 保持现状（无需改动） |
| 127 | `try` / `catch` / `finally` | DEGRADED | `TacTranslator.cs:768-807`; `Protocol.cs:194-237` (no region field); `CSharpTacFrontend.java:743` / `SidecarTacMapper.java:150` (regions hard-coded `List.of()`) | §7.2 C# 控制流「**`try`/`catch`/`finally`**」小节（`L_try: <try body> ; JUMP L_after_try` → `L_catch1: CATCH_HANDLER c1 = e1 (ctorType=System.IO.IOException)` → `L_finally: FINALLY_ENTER L_finally ; <finally body>`；+ `TryRegion{…finallyExits=[正常出口, 每个 catch 出口, rethrow 出口]}`（IR-F），异常边由 CFG builder 依 `TryRegion` 建）+ §4 wire 协议现状表 IR-F 行 + §11.2 改动清单「`TacTranslator.cs`」行（`TranslateTry`（`:768-807`）… `catchTypes`/`finallyExits`）+ §12 门禁 B ③④ |
| 128 | `lock` | DEGRADED | `TacTranslator.cs:302-308` | §7.2 C# 控制流「**`lock`**」小节 + §9 并发与同步方案表「C# `lock (m) { }`」行 + §13 的 `U3` （`B` 档：记 `drop(SEMANTICS, "lock.contention")`） |
| 129 | `using` | DEGRADED | `TacTranslator.cs:1097-1108` | §7.2 C# 控制流「**`using`/`await using`**」小节 + §11.2 改动清单「`TacTranslator.cs`」行（`TranslateUsing`（`:1097-1108`）… `using` 合成 `try/finally` + `Dispose`） |
| 130 | lambdas (all forms) | KEPT | `TacTranslator.Closures.cs:124-208,228-267` | 保持现状（无需改动） |
| 131 | anonymous methods | KEPT | `TacTranslator.Closures.cs:169-191` | 保持现状（无需改动） |
| 132 | local functions | DEGRADED | `ProjectSession.cs:572-591` | §5.2 C# 声明面表「局部函数」行（合成 `TacFunction` + **捕获作前导形参**（现状完全没传，`ProjectSession.cs:572-591`）；IR-E + IR-AG）+ §11.2 改动清单「`ProjectSession.cs`」行（`TranslateLocalFunction`（`:572-591`）… 局部函数捕获作前导形参） |
| 133 | delegates | DEGRADED | `TypeMapper.cs:73` | §5.2 C# 声明面表「enum / delegate」行（委托类型 + `Invoke` 成员；`memberKind` 列 `DELEGATE_INVOKE`） |
| 134 | method groups | DROPPED-SILENT | `TacTranslator.cs:1534-1537,1546-1557` | §6.2 C# 表达式面表「方法组 `Action h = Console.WriteLine;`」行（**不发 CALL**；改为合成零参转发闭包 + `MOVE h = closure`；IR-E）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateMemberAccess`（`:1454-1544`）、`TranslateStaticMemberAccess`（`:1546-1568`）… 方法组 → 合成转发闭包（现状发 CALL）） |
| 135 | `is` | DEGRADED | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「`is T` / `is not T` / `as T`」行（`is`：`INSTANCEOF t = x (T)`；IR-C）+ §11.2 改动清单「`TacTranslator.cs`」行（`IsPatternExpressionSyntax`（`:1210-1219`）… `not` → `negated` + 子模式求值） |
| 136 | `as` | DROPPED-NOTED | `TacTranslator.cs:1142` | §6.2 C# 表达式面表「`is T` / `is not T` / `as T`」行（`as`：`UNARYOP t = CAST x (T)` + `NULL_CHECK t`） |
| 137 | pattern matching | DEGRADED | `TacTranslator.cs:1210-1219` | §6.2 C# 表达式面表「列表模式 / 属性模式」行 + §6.2 C# 表达式面表「`and`/`or`/`not` 模式 / `when` 守卫 / 模式绑定」行 + §10「**IR-AF `TacGuard` 需要合取/析取组合子**」 |
| 138 | interpolated strings | DEGRADED | `TacTranslator.cs:1251-1281` | §6.2 C# 表达式面表「插值字符串 `$"{a}"`」行（`CALL <cs>.string.Concat/N(...)`；污点取并集） |
| 139 | string concatenation | KEPT | `TacTranslator.cs:1967-1978` | 保持现状（无需改动） |
| 140 | `??` | DEGRADED | `TacTranslator.cs:2078` | §6.2 C# 表达式面表「`??` / `??=` / `?.`」行（`??`：`BRANCH a ? L_val : L_alt ▸ Truthiness(a)`；IR-C）+ §11.2 改动清单「`TacTranslator.cs`」行（`BinaryOperatorOf`（`:2057-2080`）… `??` 不再映射 `ADD`） |
| 141 | `?.` | KEPT | `TacTranslator.cs:1727-1765` | 保持现状（无需改动） |
| 142 | ternaries | KEPT | `TacTranslator.cs:1177-1208` | 保持现状（无需改动） |
| 143 | `checked` / `unchecked` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（块体**照常降级**；`checked` 记「块内算术溢出抛 `OverflowException`」，`unchecked` 为回绕）+ §11.2 改动清单「`TacTranslator.cs`」行（`TranslateStatement`（`:239-315`）… 补 `CheckedStatementSyntax`/`UncheckedStatementSyntax`） |
| 144 | `stackalloc` | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`stackalloc`/`new T[n]`/集合表达式/元组/解构/`with`」行（`NEWARRAY t = T(n)` + 逐元素 `ARRAYSTORE`） |
| 145 | `fixed` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（`fixed` 块内指针访问按指针语义降级） |
| 146 | `unsafe` | DROPPED-NOTED | `TacTranslator.cs:239-315` | §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节（`unsafe` 块体**照常降级**）+ §6.2 C# 表达式面表「`checked`/`unchecked` / `unsafe`/指针 / LINQ 查询」行 |

### A.3 Constructs that carry security meaning

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 147 | attributes marking entry point / authz / validation / data-binding | DROPPED-SILENT | sidecar: 0 `Attribute` reads; `Protocol.cs:194-237`; `CSharpTacFrontend.java:737-740` | §5.2 C# 声明面表「特性」行（进 `annotations`/`annotationValues` 既有槽位；**本方案的主要通道**；**不需新 IR**）+ §4 wire 协议现状表 P0-6 行（新增；C# 读特性）+ §14.2 Q2 + §3「**逐条被替代掉的错误做法**」段（❌ 特性 / Go struct tag 丢 ⇒ ✅ 进既有注解槽位） |
| 148 | `async` / `await` (concurrency) | DEGRADED | `TacTranslator.cs:1164-1175`; `Protocol.cs:89-167` (no `effect` field); `CSharpTacFrontend.java:752-812` (no `effect` reader, unlike `SidecarTacMapper.java:184`) | §7.2 C# 控制流「**`async`/`await`**」小节（`await e` → `CALL t = await e` + `Effect.SUSPEND`，恢复点记进函数级 `resumePoints`（IR-L））+ §4 wire 协议现状表 IR-L 行（C#：**新增整个 `effect`**）+ §11.2 改动清单「`Protocol.cs`」行（**新增整个 `effect` 字段**）与「`CSharpTacFrontend.java`」行（读全部新字段（**含 `effect`**））+ §9 并发与同步方案表「C# `async`/`await` / `IAsyncEnumerable`」行 + §13 的 `U2` （`B` 档：记 `drop(SEMANTICS, "concurrency.order")`） |
| 149 | `IEnumerable` laziness | DEGRADED | `TacTranslator.cs:943-990` | §7.2 C# 控制流「**`foreach`**」小节（迭代协议用**真实方法名**（IR-P）⇒ 延迟执行按协议调用展开） |
| 150 | `IAsyncEnumerable` laziness | DROPPED-SILENT | `TacTranslator.cs:943-990` | §7.2 C# 控制流「**`await foreach`（异步枚举）**」段（异步枚举协议 + `Effect.SUSPEND` + `resumePoints`，与本表 `await foreach` 行**同一条构念**）+ §9 并发与同步方案表「C# `async`/`await` / `IAsyncEnumerable`」行 |
| 151 | `IDisposable` lifetime | DROPPED-SILENT | `TacTranslator.cs:1097-1108` | §7.2 C# 控制流「**`using`/`await using`**」小节（`CALL dispose()`（calleeSig=`<owner>..Dispose/0`）；`await using` 用 `CALL <owner>..DisposeAsync/0` + `Effect.SUSPEND` ⇒ 资源生命周期首次可见） |
| 152 | `unsafe` / pointers | DROPPED-NOTED | `TacTranslator.cs:239-315,1292-1295` | §6.2 C# 表达式面表「`checked`/`unchecked` / `unsafe`/指针 / LINQ 查询」行（指针读写用 `FIELDLOAD`/`FIELDSTORE` on 指针 cell）+ §7.2 C# 控制流「**`checked`/`unchecked`/`unsafe`/`fixed`**」小节 |
| 153 | `dynamic` (runtime dispatch) | DEGRADED | `TacTranslator.cs:1580-1589` | §6.2 C# 表达式面表「`dynamic` / `decimal` / `nint`,`nuint` / `T?`（引用）/ `int?`」行（`dynamic` = `UNKNOWN` + `domain=Dynamic`，调用发 `UNINTERPRETED`）+ §13 的 `U6`（C# `dynamic` 的**运行期绑定**） |
| 154 | `stackalloc` | DROPPED-NOTED | `TacTranslator.cs:1292-1295` | §6.2 C# 表达式面表「`stackalloc`/`new T[n]`/集合表达式/元组/解构/`with`」行（`NEWARRAY t = T(n)` + 逐元素 `ARRAYSTORE`） |
| 155 | `Span<T>` | DEGRADED | `TacTranslator.cs:590-596` | 与本表 `Span<T>` / `ReadOnlySpan<T>` 行**同一条构念**：类型部分 §8 类型面方案表「C# `void` / 枚举 / 委托 / `Span<T>`」行；**索引器写** §6.2 C# 表达式面表「**`Span<T>` / `ReadOnlySpan<T>` 索引器写**」行 |

### A.4 Wire / IR channels (the shared-mapper losses, and their C#-specific consequence)

| # | 审计的 construct | 审计状态 | 审计证据 (file:line) | 本方案的去向（章节号 + 表行/小节） |
|---|---|---|---|---|
| 156 | annotations on the wire | DROPPED-SILENT | `Protocol.cs:194-237`; `CSharpTacFrontend.java:737-740`; `SidecarTacMapper.java:144-147` | §4 wire 协议现状表 P0-6 行（`annotations`/`annotationValues`：无 ⇒ 新增；C# 读特性）+ §5.2 C# 声明面表「特性」行（进 `annotations`/`annotationValues` 既有槽位）+ §3「**逐条被替代掉的错误做法**」段（❌ 特性 / Go struct tag 丢 ⇒ ✅ 进既有注解槽位） |
| 157 | try/catch region structure on the wire | DROPPED-SILENT | `Protocol.cs:194-237`; `CSharpTacFrontend.java:743`; `SidecarTacMapper.java:150` | §4 wire 协议现状表 IR-F 行（函数 `tryRegions`；C# 现状「无；mapper `List.of()`（`CSharpTacFrontend.java:737`）」⇒ 新增）+ §11.2 改动清单「`CSharpTacFrontend.java`」行（`tryRegions` 不再 `List.of()`（`:737`））+ §7.2 C# 控制流「**`try`/`catch`/`finally`**」小节（异常边由 CFG builder 依 `TryRegion` 建）+ §12 门禁 B ③④ |
| 158 | callee parameter types on the wire | DROPPED-SILENT | `Protocol.cs:89-167`; `CSharpTacFrontend.java:752-812`; `SidecarTacMapper.java:159-227` | §4 wire 协议现状表「`calleeParamTypes`」行（C# 现状「无（`Protocol.cs:89-167`）」⇒ 新增（重载精确匹配的前提））+ §11.2 改动清单「`Protocol.cs`」行（加 `guard`/`labelRef`/`ownerType`/`calleeParamTypes`）+ §5.2 C# 声明面表「方法 / 重载」行（重载靠 `calleeParamTypes` 区分） |
| 159 | nullability flag on the wire | DROPPED-SILENT | `Protocol.cs:44-46`; `CSharpTacFrontend.java:864-895` | §8 类型面方案表「C# `string` / `T`（引用）/ `T?`（引用注解）」行（引用类型一律可空（`TypeMapper.cs:15-21`）；**不因注解改变可空性**（`SourceLanguage.java:210`）⇒ 审计所记的按设计正确的结论在本文档里被保留）+ §6.2 C# 表达式面表「`dynamic` / `decimal` / `nint`,`nuint` / `T?`（引用）/ `int?`」行（`int?` = `INT` + `nullable=true`，`HasValue` → `INSTANCEOF`） |
| 160 | generic type arguments on the wire | DROPPED-SILENT | `TypeMapper.cs:99-108` | §4 wire 协议现状表 IR-K 行（类型 `typeArguments`/`unionMembers`/`bound`：无 ⇒ 新增）+ §8 类型面方案表「C# `string` / `T`（引用）/ `T?`（引用注解）」行（`typeArguments`（IR-K）；现状泛型实参被擦除（`TypeMapper.cs:99-108`）） |
| 161 | `Effect` channel on the wire | DROPPED-SILENT | `Protocol.cs:89-167` (no field); `SidecarTacMapper.java:184` reads it, `CSharpTacFrontend.java:752-812` does not | §4 wire 协议现状表 IR-L 行（**wire 无 `effect` 字段** ⇒ Go：枚举加 `SUSPEND`；C#：**新增整个 `effect`**）+ §4「**C# 不走共享 mapper 的影响**」段（自有 `toInstruction` **不读** `effect`）+ §11.2 改动清单「`Protocol.cs`」行（**新增整个 `effect` 字段**）与「`CSharpTacFrontend.java`」行（读全部新字段（**含 `effect`**））+ §12 门禁 G「**G 两线同形**」 |
| 162 | the C# frontend does **not** use the shared mapper | DEGRADED | `CSharpTacFrontend.java:669-895` (private copy) vs `SidecarTacMapper.java:75-227` (used by TS/ArkTS/Python/PHP) | §4「**C# 不走共享 mapper 的影响**」段（两份私有 mapper **已经分叉**…⇒ 本方案要求两条线的 `toInstruction`/`toFunction` **各自实现全部字段**，字段集合与共享 mapper 对齐（验收门禁 G））+ §12 门禁 G「**G 两线同形**」（两份 `toInstruction` 的字段清单逐项比对，任一差异算门禁失败）+ §14.1 K2（三份 mapper…会再次分叉） |

**附录 B 小计**：**162 条**（= 审计 A 节的**全部 162 个逻辑行**；物理 160 行 —— `06-csharp.md:110` 焊接的三行已拆成 #85 / #86 / #87）。`KEPT` **32 条**按审计结论保持现状。**162 条全部有落点**（A 完整表达 **119** / B 显式记账 **9** / C 永久例外 **2**；连同 32 条 `KEPT` 正好 162），**`⚠` = 0**。

### B.5 原「⚠ 未覆盖清单」（C#）—— 已全部补齐

本节初稿列了 7 个编号 / 9 行 `⚠`（C# #13、#14、#43、#54、#95、#99、#112、#148、#153）。
**这 9 行已在本轮全部补齐**：台账正文里对应的行已改成真实落点，**附录内 `⚠` 归零**。

| 编号 | construct | 现在的落点 | 分类 |
|---|---|---|---|
| U-CS1 | C# #99 `await foreach`、#148 `IAsyncEnumerable` 惰性 | §7.2 C# 控制流「**`await foreach`（异步枚举）**」段 + §9 并发与同步方案表「C# `async`/`await` / `IAsyncEnumerable`」行 + §11.2 改动清单「`TacTranslator.cs`」行 | **完整表达**（`GetAsyncEnumerator`/`MoveNextAsync`/`DisposeAsync` + `Effect.SUSPEND` + `resumePoints`，IR-L + IR-P 已够用，**不需新 IR**） |
| U-CS2 | C# #95 `Span<T>` / `ReadOnlySpan<T>`、#153 `Span<T>` | §6.2 C# 表达式面表「**`Span<T>` / `ReadOnlySpan<T>` 索引器写**」行 + §8 类型面方案表「C# `void` / 枚举 / 委托 / `Span<T>`」行 + §11.2 改动清单「`TacTranslator.cs`」行 | **完整表达**（合成类 + backing 数组 + `ARRAYSTORE`，IR-E `ownerType` 已够用） |
| U-CS3 | C# #13 `variance`（`in`/`out`） | §5.2 C# 声明面表「**泛型类型参数 + 约束 + 变型**」行 + §8 类型面方案表「C# **泛型型变的变型与约束**」行 + §11.2 改动清单「`ProjectSession.cs`」行 | **完整表达**（变型进 `typeParameters` 条目的 `variance` 字段 = **IR-N 的字段扩展**，不新增 IR 编号） |
| U-CS4 | C# #14 泛型约束（`where T : IDisposable`） | 同 U-CS3 的行（约束进同一条目的 `bound`，**IR-N** 已登记） | **完整表达** |
| U-CS5 | C# #43 `virtual` 动态派发 | §5.2 C# 声明面表「**`virtual`/`override`/`abstract` 动态派发**」行 + §11.2 改动清单「`TacTranslator.cs`」行 | **完整表达**（一组 `CALL` 覆盖已知 override；开放层次用 `drop(SEMANTICS, "virtual.open-hierarchy")` 显式记账） |
| U-CS6 | C# #54 `?[]` 条件元素访问 | §6.2 C# 表达式面表「`??` / `??=` / `?.` / **`?[]`**」行 + §11.2 改动清单「`TacTranslator.cs`」行 | **完整表达**（与 `?.` 同形，IR-C 已够用） |
| U-CS7 | C# #112 `field` 关键字 | §5.2 C# 声明面表「**`field` 关键字**（C# 13 支撑属性）」行 + §11.2 改动清单「`TacTranslator.cs`」行 | **完整表达**（对合成 backing field 的 `FIELDLOAD`/`FIELDSTORE`，IR-E 已够用） |

---

## 附录 C. 两面台账小计与 IR 引用自查

| 语言面 | 审计台账来源 | A 节行数 | `KEPT`（保持现状） | 非 `KEPT` 行 | 其中有落点 | 其中带 `⚠` |
|---|---|---:|---:|---:|---:|---:|
| Go | `10-go.md` `## A.` 节（`### A.1`–`### A.14`） | 133 | 68 | 65 | **65** | **0** |
| C# | `06-csharp.md` `## A.` 节（`### A.1`–`### A.4`） | **162**（逻辑行；物理 160 行） | 32 | **130** | **130** | **0** |
| **合计** | — | **295** | **100** | **195** | **195** | **0** |

> 两条 `C` 档条目在 Go 侧：`U9` vendoring、`U10` 包级可见性（§13）——它们**有落点**（§5.1 的显式边界 / 显式取舍行），
> 落点本身就是「这里不建模、且不建模这件事是可见的」；**两条的规则本身都是可静态判定的** ⇒ 属**前端工作量**，不是 IR 能力缺口。

**IR 引用自查**：本附录只引用本文档**已登记**的 IR 编号——`IR-A`..`IR-Q`（`01`/`02` 已定稿段；按 `01` 的全局登记表跳过的那一个号本文档不使用），以及本文件 §10 的 `IR-AF`/`IR-AG`/`IR-AH`/`IR-AI`/`IR-AJ`/`IR-AK`。**本附录未引入任何新的 IR 编号**：初稿那 10 个 `⚠` 条目的承载能力（IR-B `TacDrop`、IR-C `TacGuard`、IR-K `typeArguments`/`bound`、IR-L `Effect.SUSPEND`+`resumePoints`、IR-N `typeParameters`（+ 变型字段扩展）、IR-P 真实方法名、IR-E `ownerType` + 既有 `FIELDLOAD`/`FIELDSTORE`、§3 的「运行时决定」例外类别）都已具备——缺的是**本文档的落点与取舍声明**，不是 IR 表达力；本轮补的正是这些落点。
