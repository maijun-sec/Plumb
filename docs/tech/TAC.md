# 三地址码（TAC）格式规范

本文档定义 Plumb 中间表示（IR）的 TAC 格式。所有 Java 源代码 / 字节码最终都翻译成 TAC，再交给 DFA 引擎分析。

## 1. 设计目标

| 目标 | 说明 |
| --- | --- |
| 数据流友好 | 单一定义/单一使用（SSA-lite），便于 forward/backward flow analysis |
| 可序列化 | IR 类（`TacProgram` / `TacFunction` / `TacInstruction` / `TacCfg` / `TacBasicBlock` / `TacType` / `TacOperand` / `MergedProgram` / `SourceMap` 等 10 个）全部 `implements Serializable`，可通过 `IrSerializer` 写盘（见 §6） |
| 与语言无关 | 名字 + 类型的命名约定，不嵌 Java 17 语法糖 |
| 紧凑 | 一份 ~5000 文件的仓库，其 IR 序列化到磁盘约 150 MB 量级 |

## 2. 顶层容器

### 2.1 `TacProgram`

```java
public final class TacProgram implements Serializable {
    private final Path sourcePath;          // 来自 .java / .class / .jar
    private final String typeName;          // 主类名
    private final Map<String, TacFunction> functions;   // signature -> fn
    // 额外：classToParent, classToChildren, lambdaBindings
    // 额外：sourceMap, programProblems
}
```

`typeName` 是短类名（不含包名）。多顶层类的文件只保留第一个作为 `typeName`，其它类的方法也被翻译进 `functions`。

### 2.2 `MergedProgram`

```java
public final class MergedProgram implements Serializable {
    private final List<TacProgram> sources;
    public TacProgram asProgram();          // 合并成一个虚拟 program
}
```

`MergedProgram.asProgram()` 返回一个 `TacProgram` 视图，函数集合是 `sources.flatMap(p -> p.functions().values())`。用于跨 TU 扫描。

## 3. 函数

### 3.1 `TacFunction`

```java
public final class TacFunction implements Serializable {
    private final String name;               // "main"
    private final String signature;         // "Demo#main"
    private final String enclosingClass;    // "Demo"（R149）
    private final TacType returnType;
    private final List<TacVar> params;
    private final List<TacVar> locals;
    private final List<TacInstruction> instructions;  // 线性流
    // 额外：classAnnotations, methodAnnotations, fieldAnnotations,
    //        paramAnnotations, tryRegions
}
```

`signature` 是 `<enclosingClass>#<name>` 的格式（构造器、`<clinit>` 同理）。这是 call graph 的 key。

### 3.2 `TacVar` 与 `TacOperand`

```java
public sealed interface TacOperand
        permits TacOperand.TacVar, TacOperand.Const { }

public static final class TacVar implements TacOperand {
    private final String name;        // "x" / "t0" / "this"
    private final TacType type;
    private final boolean isTemp;     // 翻译器生成的临时变量
}

public static final class Const implements TacOperand {
    public enum Kind { INT, LONG, FLOAT, DOUBLE, BOOL, CHAR, STRING, NULL }
    private final Kind kind;
    private final Object value;
    private final TacType type;
}
```

TAC 是 3 地址：每个 `TacInstruction` 最多 1 个 `dest`（目标）+ 2 个 lhs（操作数）。lhs 可以是 `TacVar`（命名存储）或 `Const`（字面量）。

### 3.3 `TacType`

```java
public final class TacType implements Serializable {
    public enum Kind {
        BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, STRING,
        CLASS, ARRAY, VOID, NULL, UNKNOWN
    }
    private final Kind kind;
    private final Object payload;   // kind == CLASS 时是短类名；
                                   // kind == ARRAY 时是元素类型 (TacType)
}
```

`className()` 在 `kind == CLASS` 时返回 `(String) payload`；`arrayComponent()`
在 `kind == ARRAY` 时返回元素类型。

`STRING` 走 fast path；`CLASS` 只保留短名（不含包）。泛型擦除。

## 4. 指令集

`TacInstruction.Kind` 共 19 种：

| Kind | 字段 | 含义 |
| --- | --- | --- |
| `MOVE` | `dest = lhs1` | 简单赋值 / phi / cast 一边 |
| `BINOP` | `dest = lhs1 <op> lhs2` | 二元运算（+、-、==、&&、\|\|） |
| `UNARYOP` | `dest = <op> lhs1` | 一元运算（-、!、~、++、--、cast） |
| `CALL` | `dest = calleeSig(args...)` | 方法调用；dest 可为 null（`void` 调用） |
| `NEW` | `dest = new <type>(args...)` | 构造对象 |
| `NEWARRAY` | `dest = new <type>[lhs1]` | 数组分配 |
| `ARRAYLOAD` | `dest = lhs1[lhs2]` | 数组读 |
| `ARRAYSTORE` | `lhs1[lhs2] = dest` | 数组写 |
| `FIELDLOAD` | `dest = lhs1.<fieldName>` | 字段读（static 时 lhs1 为 null） |
| `FIELDSTORE` | `lhs1.<fieldName> = dest` | 字段写 |
| `INSTANCEOF` | `dest = lhs1 instanceof <type>` | 类型检查 |
| `BRANCH` | `if (lhs1) goto trueLabel else falseLabel` | 条件分支 |
| `JUMP` | `goto label` | 无条件跳转 |
| `LABEL` | `label:` | 基本块标签 |
| `RETURN` | `return [lhs1]` | 方法返回 |
| `THROW` | `throw lhs1` | 抛异常 |
| `CATCH_HANDLER` | `catch (<type>) -> dest` | 入口 catch 块（dest 是 catch 参数） |
| `FINALLY_ENTER` | `finally:` | 入口 finally 块 |
| `NOP` | — | no-op |

`CATCH_HANDLER` 和 `FINALLY_ENTER` 是 R261 加入的，让 try/catch/finally 显式进入 IR（R261 同时给 CFG 加 `exceptionSuccessors` 边）。具体语义：

- **`CATCH_HANDLER` (`catch (<type>) -> dest`)** —— catch 块入口。`dest` 是 catch 参数绑定（运行时一定非 null，类型由 throws 推导）。`TaintedCheckerBase` 看到 `CATCH_HANDLER` 时把 `dest` 标记为 NNE（Non-Null Evidence），并把关联 `THROW` 指令的 taint label 透传到 `dest`。这是为什么 `NullDerefChecker` 不报"catch (NullPointerException e) { e.x ... }"——`e` 被绑死为非 null。
- **`FINALLY_ENTER` (`finally:`)** —— finally 块入口。CFG builder 给所有 try 内基本块 + 所有 catch 块（以 `THROW` 结尾的）都加一条到 FINALLY 块的 exception edge，模拟"无论 try/catch 怎么走，finally 一定跑"的语义。
- 旧 R151 的 `L_CATCH_<Type>` / `L_FINALLY` 标签名 pattern 保留——Label-based tooling（UnusedLabelChecker / SARIF / R259 cache-key normaliser）不破。

详细背景与 CWE396 调优过程

### 4.1 二元 / 一元运算符

```java
public enum BinaryOperator {
    ADD, SUB, MUL, DIV, MOD,
    AND, OR, XOR, SHL, SHR, USHR,
    EQ, NE, LT, LE, GT, GE,
    LAND, LOR
}

public enum UnaryOperator {
    NEG, NOT, BITNOT,
    PRE_INC, PRE_DEC, POST_INC, POST_DEC,
    CAST
}
```

`CAST` 的目标类型在 `TacInstruction.castType` 上。`LAND`/`LOR` 是短路与/或（不强制求值 rhs）。

### 4.2 CALL 签名

`calleeSig` 用 `<FQN>#<method>(<param-types>)` 格式，例如 `java.lang.Runtime#exec(String)`。重载区分靠完整签名。

对于 lambda 调用，calleeSig 是合成的 `lambda$N`，对应 `TacProgram.bindLambda` 登记的绑定。

## 5. CFG

```java
public final class TacCfg implements Serializable {
    private final TacFunction function;
    private final List<TacBasicBlock> blocks;     // 顺序排列
    private final TacBasicBlock entry;
    private final List<TacBasicBlock> exits;
}

public final class TacBasicBlock implements Serializable {
    private final String label;                   // 唯一
    private final List<TacInstruction> instructions;
    private final List<TacBasicBlock> successors;
    private final List<TacBasicBlock> predecessors;
}
```

`TacCfgBuilder.build(fn)` 把 `TacFunction.instructions` 切分成基本块，正确处理：
- 顺序流（MOVE/BINOP/CALL/...）落在同一块
- `BRANCH` / `JUMP` 结束当前块
- `LABEL` 开始新块
- `RETURN` / `THROW` 终止当前块
- `CATCH_HANDLER` / `FINALLY_ENTER` 开始新块，CFG builder 接入 try/catch 边

## 6. 序列化（IrSerializer）

所有 IR 类实现 `java.io.Serializable` + `serialVersionUID = 1L`。`org.plumb.tac.io.IrSerializer` 提供 `writeProgram / readProgram / writeMerged / readMerged / writeObject / readObject`，后端是 `ObjectOutputStream` + `BufferedOutputStream`，可选 `.gz` 后缀触发 GZIP 包装。

文件格式：

```
┌──────────┬──────────────┬─────────────────┐
│ MAGIC    │ VERSION      │ <Object>        │
│ 0x504C554D (4B)        │ (Java 序列化)   │
│ 0x00000001 (4B)        │                │
└──────────┴──────────────┴─────────────────┘
```

文件命名见 `docs/usage/CLI.md` §5.3 与 `plumb-main/.../OutputLayout.java`。

## 7. TAC 生成流水线

```
.java  →  JavaParser  →  CompilationUnit  →  ParsedSource
                                                  │
                                                  ▼
                                          TacTranslator
                                                  │
                                                  ▼
                                              TacProgram
                                                  │
                                                  ▼
                                          TacCfgBuilder  →  TacCfg
                                                  │
                                                  ▼
                                          DfaEngine（plumb-dfa）
```

`TacTranslator` 的实现位于 `plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java`。它递归访问 CompilationUnit 的每个节点，把 statement 翻译成线性的 `TacInstruction` 流。CFG 由 `TacCfgBuilder` 离线构建（`plumb-tac/.../TacCfgBuilder.java`）。

字节码路径（`.jar` / `.class`）通过 `BytecodeAnalyzer`（plumb-tac 内部），把 SootUp 的 Jimple 进一步降为 TAC，结构与源代码路径相同。

## 8. 与 SSA 的差距

Plumb TAC **不是完整 SSA**：
- 没有 `φ` 函数；同一个变量在分支合并点不会自动 join
- 分支条件会重复出现（如 `if (a > 0) { a = 1; } use(a);` 的 use 看不到 a 被定义过）

这意味着：
- forward dataflow 需要在每条 BRANCH 上复制 last-def
- 跨函数推理靠 `summaryCache`（callee summary 复用），不靠 SSA 形式

后续 R-version 可能引入显式 SSA（参考 `plumb-dfa/.../solver/`）。
