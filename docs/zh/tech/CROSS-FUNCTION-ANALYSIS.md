# 跨函数分析：自底向上构建 Summary，自顶向下精确分析

[English](../../tech/CROSS-FUNCTION-ANALYSIS.md) | [中文](../README.md)

本文档解释 `plumb-dfa` 引擎（ / /
 / ）的两阶段跨函数分析设计。

**两阶段的精度模型完全不同**：

| 阶段 | 敏感性 | 解释 |
| --- | --- | --- |
| **Phase 1（自底向上 Summary 构建）** | **path-insensitive** + **context-insensitive** | summary 是 callee 内"所有路径 + 所有 caller 共享"的合并结果；同一个 callee 算一次，到处复用 |
| **Phase 2（自顶向下精确分析）** | **fully context-sensitive**（每个 call site 独立） + **path-sensitive（Z3 验证）** | caller 把 summary 折叠到自己的 fact bag / 路径条件 / points-to map，所有结果都是 caller 视角的具体值 |

> 配套源码：
> - `plumb-dfa/src/main/java/org/plumb/dfa/summary/Operation.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/summary/FunctionSummary.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/summary/MethodSummary.java` + `MethodSummaryBuilder.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/summary/SummaryApplier.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintDomain.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/points/PointsToAnalysis.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/solver/PathCondition.java` + `GuardStore.java` + `Z3Bridge.java` + `LocalSolverBridge.java`
> - `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java` —— 引擎调度

## 1. 设计动机

tabulation（IFDS / IDE）风格的跨函数分析把所有 fact 装进一个共享 worklist：

```text
每个 caller 的 (call-site, fact) → 沿 call/return 边串成一条工作流
```

这在单 checker 的纯污点分析里是合理的，但 Plumb 的 unified 引擎要并行回答多种
异构问题：taint（StringTaint / NumericTaint / PointerTaint / MixedTaint）、null-deref、
points-to、resource leak、Z3 路径条件、side effect（写 this.field / 写 static / 调
用其它方法）。把每一种 fact 都塞进一个统一的 lattice，worklist 会在
`O(functions × call-sites × domain_kinds)` 级别膨胀，cache 命中率低、并行化困难。

Plumb 的两阶段设计是 **把"callee 的语义"和"caller 的具体使用"解耦**：

```text
Phase 1 (callee 自报家门)  ──►  Summary  =  模板（path-insensitive / context-insensitive）
Phase 2 (caller 套用模板)  ──►  精确分析  =  caller 视角的具体值（context-sensitive / path-sensitive via Z3）
```

| 阶段 | 顺序 | 任务 | 输出 |
| --- | --- | --- | --- |
| **Phase 1（自底向上）** | callee 在 caller 之前 | 走完函数体、收集 per-param 的 `Operation` 集合、计算 `MethodSummary`（返回值形状 + 副作用） | `FunctionSummaryCache` + `MethodSummaryCache` |
| **Phase 2（自顶向下）** | caller 分析时遇到 CALL | 查 cache → 把 summary 折叠成 caller 自己的 fact / 路径条件 / points-to / 副作用 | `RETURN_VALUE_FOLDED` fact / `SIDE_EFFECT_RECORDED` fact / `taintedVars` 增量 / `GuardStore` 增量 / `pointsTo` 增量 |

**关键性质**：callee 的摘要只算一次（cache by signature）；caller 之间不再互相
等待——只要它所有 callee 算完，就可以开始。两阶段之间是**单向依赖**，因此可以做
worklist 并行。

## 2. CallGraph 视角：跨 TU 怎么工作？

在进入 Phase 1/Phase 2 之前，必须先回答一个问题：引擎看到的是**一个 TU 的
TacProgram**，还是**多个 TU 合并的 TacProgram**？这决定跨文件调用能不能被解析。

### 2.1 两条路

| 路径 | 入口 | 怎么用 | 谁在用 |
| --- | --- | --- | --- |
| **(A) Merged Program + CHA**（**默认**） | `MergedProgram(List<TacProgram>).asProgram()` | 把多个 .java 文件的 `TacProgram` 合并成单个 `TacProgram` 视图（`MergedProgram` 用 `enclosing-class :: signature` 作为 merged key，避免同名方法冲突） | `plumb-main` 的 `ScanRunner`（生产 CLI） |
| **(B) GlobalTACStore + Global strategy** | `GlobalTACStore(programs).freeze()` + `CallGraphStrategyFactory.createCrossTu("global", store, null)` | 直接把多个 `TacProgram` 注册到全局签名索引，`GlobalCallGraphStrategy` 按 `calleeSig` 直接 lookup 解析 | `plumb-main` 的 `ScanRunner`（**默认**，`cgStrategy=global` 时走 `DfaEngine.runWithGlobalStore`） |

> 关键事实：`plumb-main` 默认走的是**路径 B**——`ScanConfig.cgStrategy` 的默认值是
> `"global"`，`ScanRunner` 因此调 `DfaEngine.runWithGlobalStore(...)`，把每个 TU
> 注册进 `GlobalTACStore`，跨 TU 调用按 `calleeSig` 解析。
>
> 路径 A 仍然可用：把 `cgStrategy` 设成 `cha` / `rta` / `vta` / `1cfa` / `stub`
> 就走 `DfaEngine.run(programView)`，其中 `programView` 是
> `MergedProgram.asProgram()` 合并后的视图。两条路径都能解析跨 TU 直接调用，
> 区别在于路径 A 靠**合并后的单个 TacProgram**，路径 B 靠**全局签名索引**。

### 2.2 路径 A：MergedProgram + CHA 怎么支持跨 TU

```java
// plumb-main/ScanRunner.java (摘录) —— cgStrategy != "global" 时
List<TacProgram> programs = translated.stream().map(t -> t.program).toList();
MergedProgram merged = new MergedProgram(programs);
TacProgram programView = merged.asProgram();
DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
engine.run(programView);   //  ← 整个工程作为一个 TacProgram
```

`MergedProgram.rebuild()` 给每个函数生成 merged key：

```text
原始签名    com.foo.A#m(int):int
原始签名    com.bar.B#m(int):int
                       ↓
merged key  com.foo.A :: m(int):int
merged key  com.bar.B :: m(int):int
```

`ChaCallGraphStrategy` 在合并后的 `TacProgram.functions()` 上做：

1. **Direct call 解析**：CALL 的 `calleeSig` 已经是 `"com.bar.B#m(int):int"`，在
   合并索引里能直接找到（因为 B#m 在合并视图里）→ 跨 TU 直接调用解析成功。
2. **Virtual call over-approximation**：`ChaCallGraphStrategy.indexBySimpleName(program)`
   按 `method/arity` 建索引（如 `m/1`），CHA 把"任何同 simple-name + 同 arity"
   的函数都当作 override 加进 call graph——这是标准的 CHA over-approx
   （sound but imprecise）。如果 A 和 B 没有继承关系，CHA 也会把 B#m 当
   作 A#m 的可能目标，可能引入 false positive call edges。
3. **Library 过滤**：默认 `includeLibraries = false`，`java.* / javax.* / com.sun.*
   / jdk.* / sun.*` 的调用不参与 call graph（当成 opaque library）。

> 跨 TU 间接调用（A.m → A.helper → B.work）：CHA 沿 call graph 边一阶一阶
> 走，因为 A.helper 和 B.work 都在合并视图里，RPO 调度能正确地把 callee
> 算完再算 caller。

### 2.3 路径 B：GlobalTACStore + Global strategy

`GlobalTACStore` 是**显式跨 TU 注册表**——它把多个 `TacProgram`
的 `TacFunction` 按 canonical signature 索引到一张表里，提供 O(1) 解析。

```java
GlobalTACStore store = GlobalTACStore.of(programs);  // 内部 addProgram + freeze
CallGraphStrategy strategy = CallGraphStrategyFactory
        .createCrossTu("global", store, null);
CallGraph cg = strategy.build();
DfaEngine engine = new DfaEngine(cfg, checkers, "global", null);  // 但 engine 默认走 TU-local factory
//  ↑ 这个用法其实不工作：DfaEngine.run() 内部调的是
//  CallGraphStrategyFactory.create(name, program, cachePath)，
//  对 "global" 会抛 "Unknown call-graph strategy"。
//  Global strategy 必须直接用 builder 走，跟 DfaEngine.run() 解耦。
```

**正确的跨 TU 用法**（自己控 call graph，不走 DfaEngine.run）：

```java
GlobalTACStore store = GlobalTACStore.of(programs);
CallGraph cg = new GlobalCallGraphBuilder(store).build();
// 后续分析直接消费 cg，不再走 DfaEngine.run
```

路径 B 的优点：
- **不依赖 Java type hierarchy**：直接 signature lookup，virtual call 也能精确
  解析（前提是 IR 的 `calleeSig` 已经反映动态类型，否则还是要靠 caller 自己
  的 type analysis）。
- **不合并 program**：保留每 TU 的 `sourcePath` 映射（`GlobalTACStore.sourceOf(sig)`），
  报告 finding 时能精确定位回原文件。
- **Cross-TU cache key**：cache 文件名包含 source file 集合的 hash，加新
  .java 自动失效。

路径 B 的缺点：
- CLI 没接——`DfaEngine` 的 4-arg/5-arg 构造函数只接受 TU-local strategy name。
- 想要走 Global 策略，得绕过 `DfaEngine.run`，直接用 `GlobalCallGraphBuilder` +
  自定义遍历；这跟引擎的并行 worklist / 预热都不兼容（`DfaEngine.run`
  内部用 `CallGraphStrategyFactory.create(name, program, ...)`，Global 策略
  走不到）。

### 2.4 结论：默认行为 = 跨 TU + 路径敏感

把两条路放在一起看：

| 维度 | 路径 A（Merged + CHA） | 路径 B（GlobalTACStore） |
| --- | --- | --- |
| 跨 TU 直接调用 | ✅ 静态 `calleeSig` 在合并索引里命中 | ✅ 显式 lookup 命中 |
| 跨 TU 虚调用 | ⚠️ CHA 同 simple-name + arity 全收，可能 over-approx | ✅ 严格按 signature 匹配（如果 IR 标了动态类型） |
| Call graph 精度 | sound + 偏 over-approx | 取决于 IR 信息完整度 |
| CLI 支持 | ✅ `cgStrategy` 设成 `cha` / `rta` / `vta` / `1cfa` / `stub` | ✅ **默认**（`cgStrategy=global`） |
| 跟 `DfaEngine` 集成 | ✅ 一行 `engine.run(programView)` | ✅ `engine.runWithGlobalStore(programs, threads)` |
| 跨 TU 缓存 | ❌ cache key 只看单文件 SHA-1 | ✅ cache key 含 source file set 哈希 |
| 用在哪些测试 | 几乎所有 | 几乎所有 |

**所以"在 plumb-dfa 中，分析使用的 CallGraph 是否为全局的 CallGraph，涉
及到是否能指向跨 TU 分析"的答案是**：

- **生产 CLI 默认就是全局的**——`ScanConfig.cgStrategy` 默认 `"global"`，
  `ScanRunner` 走 `DfaEngine.runWithGlobalStore(...)`：`GlobalTACStore.of(programs)`
  把每个 TU 注册进全局签名索引，`GlobalCallGraphStrategy` 按 `calleeSig` 解析。
- **路径 A 仍然可用**——把 `cgStrategy` 换成别的名字就退回
  `DfaEngine.run(merged.asProgram())`，靠合并视图上的 CHA 解析跨 TU 调用。
- **两条路都不是按 Java type hierarchy 显式构造的精确 call graph**：
  虚调用仍然是 simple-name + arity 匹配。
- **更精确的"Global" 路径存在但 CLI 没接**——`GlobalTACStore` +
  `GlobalCallGraphStrategy` 是给集成测试用的，要上线需要给 `DfaEngine`
  加 cross-TU 构造路径（接受 `GlobalTACStore` 而不是单 `TacProgram`）。

> **TODO**（未来 R？）：把 Global strategy 接到 `DfaEngine` 主路径——新增
> `DfaEngine.createCrossTu(EngineConfig, List<DfaChecker>, GlobalTACStore, int)`
> 工厂方法，内部用 `createCrossTu` factory 建 strategy，跟 worklist
> 集成。这样 CLI 就能用 `--cg-strategy global` 切到精确跨 TU 模式。

## 3. Phase 1（自底向上）—— Summary 构建

### 2.1 调用时机

单线程模式按 `CallGraph.reversePostOrder()` 遍历，callee 永远先于 caller。
并行模式用 dep-count worklist：每个函数的 dep 是"在程序里有 body 的 callee
数量"，dep=0 的入 ready 队列，处理完后 decrement 其所有 caller 的 dep，归零的
caller 进 ready 队列。两种模式都保证 Phase 1 早于 Phase 2。

引擎在每个函数上的完整生命周期：

```text
initEngine (per-engine, 一次)
  └─ for each fn in RPO:
       initFunction(ctx, fn)
         for ins in bbRpoOrder(fn):   // BB-RPO + block 内 reverse
           if ins.kind == CALL:
             # Phase 2: caller 视角的精确分析入口
             SummaryApplier.apply(ins, ctx, ins.args) // 折叠
             ctx.sideEffectConsumer().recordCall(ins, ctx) // 副作用入袋
           checkInstruction(ins, fn, ctx)   # 每个 checker 一遍（Phase 2 checker logic）
         finishFunction(ctx, fn)
       # Phase 1: 每 checker 自报家门
       merged = summariseOneFunction(ctx, fn)
       ctx.summaryCache().put(fn.signature(), merged) # 写入 cache
       finish(ctx) (per-engine, 一次)
```

注意 Phase 2（SummaryApplier）**在 `checkInstruction` 之前**调用——意味着 checker
在 `checkInstruction(ins)` 中看到的 fact bag 已经包含了上一行 CALL 的
`RETURN_VALUE_FOLDED` 与 `SIDE_EFFECT_RECORDED` fact。 引入这一位置正是为了
让"调用 + 用返回值"这种 pattern 在一次 instruction 访问里闭合。

**Phase 1 跟 Phase 2 都在 `analyseOneFunction` 内部完成**——一个函数的所有 CALL
都已经被 fold（Phase 2 局部完成），然后 `summariseOneFunction`（Phase 1 局部完
成）写 summary，下一个 caller 才能用。

### 2.2 MethodSummary —— 返回值形状 + 副作用

`MethodSummaryBuilder.build(fn)` 对一个 `TacFunction` 做一次**路径不敏感**
（path-insensitive）的扫描。

- **返回值形状**（9 种 `ReturnShape.Kind`）：

  ```text
  RETURN_VOID          // 没有 return / bare `return;`
  RETURN_CONST         // return 5 / return true
  RETURN_PARAM         // return p0
  RETURN_THIS_FIELD    // return this.field
  RETURN_STATIC_FIELD  // return ClassName.field
  RETURN_LOCAL_VAR     // return localVar (alias chain 追不到底)
  RETURN_BINARY_OP     // return a + b
  RETURN_CONDITIONAL   // `if (b) return true; else return false;` 模式
  RETURN_NEW_INSTANCE  // return new Foo()
  UNKNOWN              // 多分支 return 形状不一致 / chase 失败
  ```

  通过 last-def 链 + 别名追踪（`chase`），沿着 `MOVE / FIELDLOAD / NEW / BINOP` 一
  直追到根。

- **副作用**（`SideEffect`）：扫一遍
  →  `FIELDSTORE this / FIELDSTORE static / FIELDSTORE paramAlias / ARRAYSTORE paramAlias /
   CALL / THROW` 收集成四个集合。

`MethodSummary` 是 immutable，构建后写进 `MethodSummaryCache`，由引擎在
caller 侧查。

> **路径不敏感的代价**：`staticReturnsTrue` 永远返回 `const:true`，无论 caller
> 怎么传参。这是 conservative over-approximation——caller 看到的常量是 callee 在
> 任何路径下都"可能"返回的值，因此**永远不会漏报**，但可能误把多分支 return 折
> 叠成 `top`（`ReturnShape.Kind.UNKNOWN`），从而丢精度。

### 2.3 FunctionSummary —— 每个形参的 Operation 集合

`DfaChecker.summarizeFunction(ctx, fn)` 是 checker 主动汇报的钩子。
`FunctionSummary` 由四部分组成：

| 字段 | 内容 |
| --- | --- |
| `paramOps : Map<Integer, Set<Operation>>` | 形参下标（0-based，不含 receiver）→ 该形参的 `Operation` 集合 |
| `returnOps : Set<Operation>` | 函数返回值上观察到的 `Operation` 集合 |
| `calleesReached : Set<String>` | 函数内调用的所有 callee 签名（用于 cross-reference） |
| `hasUnmodelledBehavior : boolean` | 是否有 `HasUnmodelledBehavior`（O(1) 查询标志） |

`Operation` 是 **sealed interface**，9 个变体每个表达一个 cross-function 事实：

| 变体 | 携带信息 | 触发场景 |
| --- | --- | --- |
| `PassThrough()` | 形参原样流过函数 | 参数被 named / used 但没改 |
| `Sink(familyKey, atParamIndex)` | 形参直接或间接流到 sink | 例如 SQL checker 看到 `prepareStatement(arg)` |
| `WriteToField(fieldName)` | 形参的值被写到 enclosing class 的字段 | `this.field = arg` |
| `TaintPropagate(sourceId, confidence)` | 形参带着 source 的污点流出 | taint framework 写出 |
| `HasUnmodelledBehavior(reason)` | 函数有 native / JNI / reflection 等无法建模的副作用 | `native` 方法 / `Class.forName` 链 |
| `UnmodelledCall(calleeSig, isLibrary)` | 函数调了一个 body 不可见的 callee | 未解析的虚调用 / 库函数 |
| `SolverZ3Predicate(predicate, pathVars)` | 把这条调用链上的 guard 条件往 Z3 送 | path-condition bridge |
| `PointsToPropagate(allocSites)` | 函数暴露出来的 points-to 集合 | 跨函数 points-to |
| `PersistsParam(paramIndex)` | 函数把形参对象 persist 进 DB | -DA ORM_LOST_UPDATE |

Sealed interface 的好处：caller 侧的 checker 用 Java 21 pattern matching 做
exhaustive switch，编译器在新增变体时强制你考虑要不要消费它。

### 2.4 MethodSummaryCache 预热

之前，`MethodSummary` 是 analysis-loop 串行"算一个写一个"。 在真
Juliet 上的 sweep 暴露了一个 corner case：merged program 里 `bad()` 跟它的 helper
`staticReturnsTrue()` 在同一 RPO 边界上时，`bad()` 偶尔先被 visit，于是 cache 是
冷的、`SummaryApplier` 看到 `UNKNOWN`，fold 失败。

在 analysis 之前**对程序里每一个 function 都跑一遍 `MethodSummaryBuilder.build()`**
写入 cache：

```java
public int warmUpMethodSummaries(AnalysisContext ctx, TacProgram program) {
    for (TacFunction fn : program.functions()) {
        if (!analysedOrigins.contains(fn.origin())) continue;
        FunctionSignature sig = parseFnSignature(fn);
        ctx.methodSummaryCache().put(sig, fn);   // put 内部 lazy-build
        built++;
    }
    return built;
}
```

预热是 O(instructions) 的纯计算，无副作用，所以单线程顺序跑就行。这一步把"分析
顺序影响 fold 结果"的 corner case 彻底关掉，并行 worker 看到的 cache 也 100% 暖。

## 4. Phase 2（自顶向下）—— 精确分析

### 3.1 核心：为什么说 Phase 2 是 **fully context-sensitive**？

summary 本身是**不区分 caller 的模板**，但 Phase 2 把它折叠到 caller 自己的状
态后，分析就完全是 caller 视角的。三处机制确保 context-sensitivity：

**(1) Per-call-site fact bag**：每个 caller 函数有自己的 `FactBag` 实例，
`SummaryApplier.apply(call, ctx, args)` 把 `RETURN_VALUE_FOLDED` fact 写
到 `call.dest`（caller 的局部变量），不是某个全局表。两个 caller 调同一个
callee 看到的是不同的 fact bag、不同的事实。

**(2) `RETURN_PARAM` 携带 caller 实参身份**：

```java
case RETURN_PARAM: {
    int i = r.paramIndex;
    if (args != null && i >= 0 && i < args.size()
            && args.get(i) != null) {
        return "param:" + i + ":" + args.get(i);   //  ←  caller 的实参名字
    }
    return "param:" + i;
}
```

summary 里 `RETURN_PARAM(0)` 只说"返回形参 0"，但折叠出来的 fact 编码
`param:0:<caller 的实参>`。caller 拿着这个 fact 做下游分析时，看到的是
**自己**的实参的 SSA version / 名称——是 caller 的具体变量，不是 callee 的。

**(3) Per-call-site path condition**：`GuardStore` 按 `var name → predicates`
索引，caller 路径上每条 BRANCH 都把谓词加进 caller 自己的 `PathCondition`，
Z3 验证时 `bindings` 用 caller 的具体值。同一个 callee 在不同 caller 里被
展开成不同的 fact + 不同的 guard 集合。

** 例子**说明 context-sensitivity：

```java
// IO.java
public static boolean staticReturnsTrue() { return true; }

// CWE252_bad.java
void bad() {
    boolean b = IO.staticReturnsTrue();
    if (b) { ... }   // b 永远是 true，else 是死分支
}
```

- Phase 1 算 `IO.staticReturnsTrue` 的 summary：`MethodSummary.returnShape
  = RETURN_CONST(true)`、`FunctionSummary` 几乎是空。
- Phase 2 走 `bad()`：CALL `IO.staticReturnsTrue()` 命中 cache →
  `SummaryApplier` 在 `b` 上写 `RETURN_VALUE_FOLDED(b, "const:true")`。
  这里的 `b` 是 `bad()` 的局部变量（`TacVar("b")`），fact 存在于 `bad()`
  的 `FactBag` 里——**和 `good()`（如果有同名 checker）看到的 fact 完全独
  立**。
- 接着 `if (b)` 是个 BRANCH：`LiteralBooleanConditionChecker` 在
  `b` 上读到 `const:true` fact → 知道 `b == true` 必然成立 → else 分支是
  dead code → 报 finding。
- 如果另一个 caller 调 `IO.staticReturnsTrue()` 写进自己的 `c`，那条
  fact 在另一个 `FactBag` 里，分析互不干扰。

这就是"摘要不精准，但分析是 fully context-sensitive"的含义：summary 描述
的是"callee 一般会返回什么"，但 caller 用的时候，**值是绑死在 caller 自己的
变量上、走过 caller 自己的 BRANCH、用 caller 自己的 Z3 bindings 验证**。

### 3.2 SummaryApplier.apply(call, ctx, args)

每个 CALL 指令在 `checkInstruction` 之前由引擎调用：

```java
public static MethodSummary apply(TacInstruction call,
                                   AnalysisContext ctx,
                                   List<TacOperand> args) {
    String calleeSig = call.calleeSig;
    FunctionSignature sig = FunctionSignature.parse(calleeSig);
    if (sig == null) sig = fallbackParse(calleeSig); // 兜底
    MethodSummary s = ctx.methodSummaryCache().get(sig);
    applyReturnValue(call, s, ctx, args);   // → RETURN_VALUE_FOLDED fact
    applySideEffect(call, s, ctx);          // → SIDE_EFFECT_RECORDED fact
    return s;
}
```

折叠结果是两条 `Fact`，追加到 caller 的 `FactBag`：

| Fact kind | 写入位置 | 用途 |
| --- | --- | --- |
| `RETURN_VALUE_FOLDED` | `call.dest` | 消费者按 `value` 字符串决定后续分析：`const:true` / `const:42` / `param:0` / `binop:+:a:b` / `top` |
| `SIDE_EFFECT_RECORDED` | `call.dest` 或 sentinel | pure 函数无 fact；否则写 `writes-this:f, writes-param:0, calls:io,X, may-throw` 等组合 |

编码规则（稳定字符串）：

```text
RETURN_VOID          →  "void"
RETURN_CONST         →  "const:<literal>"
RETURN_PARAM         →  "param:<i>"  或  "param:<i>:<caller-arg>"
RETURN_THIS_FIELD    →  "this-field:<name>"
RETURN_STATIC_FIELD  →  "static-field:<FQCN.field>"
RETURN_LOCAL_VAR     →  "local:<name>"
RETURN_BINARY_OP     →  "binop:<op>:<lhs>:<rhs>"
RETURN_CONDITIONAL   →  "cond:<trueArm>:<falseArm>"
RETURN_NEW_INSTANCE  →  "new:<type>"
UNKNOWN              →  "top"
SIDE_EFFECT          →  "writes-this:f,writes-param:0,calls:io.X,may-throw"  // 或 "pure"
```

Checkers（比如 `LiteralBooleanConditionChecker`）只看 `value` 字符串是否以
`const:true` 开头，就能报 CWE-547 "dead branch"——它不需要知道 callee 是怎么
得到 `true` 的。

### 3.3 TaintedCheckerBase 用 Operation.TaintPropagate

Taint 类的 checker（`SqlInjectionChecker` / `CommandInjectionChecker` / 等 30 多个）
继承 `TaintedCheckerBase`，由它统一处理 cross-function taint flow：

```java
case CALL -> {
    checkSinkAtCall(ins, fn, ctx);                  // 1) sink hit?
    propagateTaintFromCalleeSummary(ins, ctx);      // 2) callee summary → caller taint
}
```

`propagateTaintFromCalleeSummary` 的逻辑：

```text
calleeSummary = ctx.summaryCache().get(call.calleeSig)
for (i, op) in calleeSummary.paramOps():
    if op is TaintPropagate(sourceId, confidence):
        arg = call.args[i]
        taintedVars[arg] = sourceId + ":" + confidence   // 标 caller 实参为 tainted
    if op is PassThrough():
        # 形参原样流过，caller 传入的 taint 仍保留
    if op is Sink(familyKey, k):
        # callee 自己有 sink，caller 侧不再二次报告（避免重复）
```

一个简化例子：

```java
// helper.java
public String loadUser(int id) {
    return request.getParameter("name");    // source
}
void saveUser(int id) {
    String name = loadUser(id);
    db.execute("INSERT INTO t VALUES ('" + name + "')");  // sink
}
```

- Phase 1 跑 `loadUser`：body 里有 source 但没 sink → summary 写入
  `paramOps[0] = PassThrough()`（参数未用），`returnOps = {TaintPropagate("bu-0001-taint-request-param", 1.0)}`。
- Phase 2 跑 `saveUser`：CALL `loadUser(id)` 时
  - `MethodSummaryCache` 返回 `RETURN_PARAM(0)` → 写入
    `RETURN_VALUE_FOLDED(name, "param:0:id")`。
  - `FunctionSummaryCache` 返回上面的 `FunctionSummary` →
    `propagateTaintFromCalleeSummary` 看到 `returnOps` 里有
    `TaintPropagate("bu-0001-taint-request-param")` → 把 `name` 标为
    `taintedVars[name] = "bu-0001-taint-request-param"`，caller 视
    角下 `name` 就是 tainted。
- 继续到 `db.execute(...)`：sink 命中 + `name` 在 `taintedVars` →
  `SqlInjectionChecker` 报 finding，路径里标 `(after cross-function fold)`。

整条链跨两个函数、3 条指令，引擎不维护全局 worklist——只需要 cache lookup。

### 3.4 跨函数 PointsTo

`PointsToAnalysis.applyCalleeSummary(dest, summary)` 把 `PointsToSummary.returnAllocSites`
并入 caller 的 points-to map。流程：

1. `PointsToAnalysis.buildSummary()` 在 Phase 1 收集 callee 内所有 `NEW /
   NEWARRAY` 产生的 `AllocSite`，编码为 `"functionSig\0insnIdx\0type"` 字符串集
   合（写进 `FunctionSummary` 的 `Operation.PointsToPropagate(allocSites)`）。
2. `PointsToAnalysis.applyCalleeSummary` 在 Phase 2 解析这些字符串、回填到
   caller 的 `pointsTo(dest)`，于是 `isUseAfterFree` 能在 caller 视角正确
   看到 "callee 内部分配的指针，流到了 caller 的 dest"。

use-after-free / alias 类的 checker 是这套设计最大的受益者——tabulation 方案把
points-to 当成又一个 fact 加进 worklist 会让状态空间膨胀；Plumb 用独立的指向性求解器
（`PointsToSolver`，全程序一次求解）+ summary 把这一维度旁路出去。

### 3.5 cross-checker GuardStore

`GuardStore` 是**一个 engine run 共享**、**多个 checker 共同贡献**的"已知 guard"
池。Checkers 在 `checkInstruction` 中遇到 `if (x > 0)` 之类的 BRANCH 就调
`ctx.guardStore().add("x", "x > 0")`；任何 checker 想知道"我的 sink 安全条件
是不是已经被别人证过了"就调 `ctx.guardStore().impliedGuard("x", "x != 0", bindings)`。

两阶段在 guard 维度上的对应：

- Phase 1 不写 guard（callee 的 BRANCH 不会进 caller 的 guard 池）。
- Phase 2 把 caller 自己走过的 BRANCH 加进 `GuardStore`，且调用
  `Z3Bridge.check(antecedent && !consequent, bindings)` 用 Z3 验证
  implication——`bindings` 用 caller 自己的 fact bag 里的具体值。

具体例子：`DivByZeroChecker` 在看到 `data / x` 时查
`impliedGuard("x", "x != 0", bindings)`；如果 `IntUnderflowChecker` 之前
记过 `x >= 1`（隐含 `x != 0`），要么走 `PathCondition.structurallyImplies` 的
快速路径（纯语法推断），要么走 Z3 完整验证。`impliedGuard` 返回 true →
`DivByZeroChecker` 抑制 finding。
