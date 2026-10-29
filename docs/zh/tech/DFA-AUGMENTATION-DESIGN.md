# DFA 增强设计：为什么 AST 不够用，怎么补

[English](../../tech/DFA-AUGMENTATION-DESIGN.md) | [中文](../README.md)

本文档解释 Plumb 在 5 个 Java 缺陷类 checker 上**加 DFA 增强版**的工程取舍——为什么 AST 版本会漏报、DFA 版本如何消除 false negative、4 种通用 DFA 增强技术怎么用。

> 配套：`tech/CROSS-FUNCTION-ANALYSIS.md`（跨函数 summary 完整机制）、`tech/ARCHITECTURE.md §3.0`（DFA 引擎 SPI）、`rules/RULE-TOOL-COMPARISON.md §1.2`（DFA vs AST 取舍）

## 1. 背景：为什么 AST 不够用

- 系列 checker 在 AST 层已经能捕获大部分情况。但 AST 的视野天然受限于**单方法**：

| 漏报场景 | AST 局限 | DFA 怎么修 |
| --- | --- | --- |
| **Helper method 调用链** | `this.foo()` 把字段 f 赋了值，AST 看不到 | 拉 `foo()` 的 FunctionSummary 读 `Operation.WriteToField("f")` |
| **Ctor chaining** | `this(other)` 调用另一个 ctor，AST 难以跟踪另一个 ctor 的字段初始化 | 走 another ctor 的 summary，union 字段集合 |
| **Aliasing** | `y = x` 之后 `if (y == null)`，AST 只看 `x` 处的 null check | MOVE 指令传播 from-load 状态 |
| **跨方法字段访问** | servlet 的 `getAttribute("k")` 在方法 A，`setAttribute("k", v)` 在方法 B | 跨函数累积 getAttribute/setAttribute 调用 |

成熟的商业引擎"真值"用 DFA 实现。本批次在 plumb-dfa 加 5 个 DFA 增强版，与 AST 版**并存**（不同 rule id）——给用户**两条选择**：
- 想**召回优先**：用 DFA 版（牺牲一点性能换漏报少）
- 想**性能优先**：用 AST 版（漏报多但跑得快）

## 2. 4 种通用 DFA 增强技术

### 2.1 FunctionSummary（最常用）

`plumb-dfa` 的框架有两种 summary 类型，回答的是不同的问题：

| 类型 | 装什么 | 回答什么 |
| --- | --- | --- |
| `FunctionSummary` | `Operation` 变体，外加 `StringOp` / `FieldOp` 列表 | "callee 对我的数据做了什么？" |
| `MethodSummary` | 一个 `ReturnShape` 和一个 `SideEffect` | "callee 返回什么，又失效了什么？" |

`Operation` 框架产出的是 `FunctionSummary`：

```java
// Operation.WriteToField 标记某方法会写某字段
FunctionSummary summary = functionSummaries.get(calleeSig);
if (summary.returnOps().contains(new Operation.WriteToField("name"))) {
    // 调用方知道 callee 会写 this.name
}
```

`FunctionSummary.returnOps()` 返回记录在**返回值**上的操作，`paramOpsAt(i)` 返回记录在第 `i`
个形参上的操作。调用方用 `project(predicate)` 只投影自己关心的那部分。

`MethodSummaryCache` 装的是 `MethodSummary`，它有两种查法：`get(FunctionSignature)` 查已解析的
签名，`getByCanonicalString(String)` 查规范拼写。缓存**不是**按裸签名字符串做 key 的——不存在
`cache.get(calleeSig)`；字符串形式走 `getByCanonicalString`。

`DfaEngine` 在 RPO 顺序遍历函数前会做**预热 pass**（`warmUpMethodSummaries`），把每个方法的 summary 先算好，调用方 checkInstruction 时直接查。

完整机制见 `tech/CROSS-FUNCTION-ANALYSIS.md §3`（Phase 1 自底向上构建 summary）。

### 2.2 MOVE alias 传播

```java
// MOVE dest = lhs1: 如果 lhs1 是 from-load 状态，dest 也标 from-load
if (ins.kind == TacInstruction.Kind.MOVE
    && ins.lhs1 != null && ins.lhs1 instanceof TacOperand.TacVar
    && ins.dest != null && ins.dest instanceof TacOperand.TacVar) {
    String from = ((TacOperand.TacVar) ins.lhs1).name();
    String to = ((TacOperand.TacVar) ins.dest).name();
    if (fromLoadVars.containsKey(from)) {
        fromLoadVars.put(to, true);
        loadCallSite.put(to, loadCallSite.get(from));
    }
}
```

`TacOperand` 是 sealed interface，`TacVar` 是它的一个嵌套实现，所以转换写的是
`TacOperand.TacVar`，名字取自 `TacVar.name()`。`lhs1` 和 `dest` 上的 null 判断不是可选项：MOVE
可能携带非变量操作数。

TAC 的 MOVE 指令对应 Java 的 `y = x` / `return x` 等纯赋值场景。这是字段级精度（per-variable）的关键——`y = x; if (y == null)` 的 null check 才会传回 `x` 的 from-load 状态。

### 2.3 BRANCH null 比较

```java
// BRANCH 指令 lhs1 binop lhs2 是 if 条件
if (ins.kind == TacInstruction.Kind.BRANCH && ins.binop != null
        && (ins.binop == BinaryOperator.EQ || ins.binop == BinaryOperator.NE)) {
    // lhs1 / lhs2 其中一边是变量名，另一边是 null 字面量
    String lBare = bareName(ins.lhs1.toString());   // "x"
    String rBare = bareName(ins.lhs2.toString());   // "null"
    // 一边是被跟踪的变量，另一边必须是 null 字面量
    String varSide = fromLoadVars.containsKey(lBare) ? lBare
        : (fromLoadVars.containsKey(rBare) ? rBare : null);
    String otherBare = varSide == lBare ? rBare : lBare;
    if (varSide != null && "null".equals(otherBare)) {
        fire();  // load + null check on l (or its alias)
    }
}
```

裸名提取是每个 checker 自己的 helper：操作数的 `toString()` 里带着声明类型也带着名字
（`"x:UNKNOWN"`），checker 必须把它剥掉。引擎里**没有**共享的 `stripType` helper，也**没有**
`Const.NULL`——null 字面量是按文本 `"null"` 匹配的。参考实现是
`OrmLoadNullCheckDfaChecker`。

**常见 bug 源**：`BinaryOperator.EQ.toString()` 是字符串 `"EQ"`，**不是** `"=="`。写 BRANCH null checker 必须用 `==` 操作符对象的 `.equals()` 比对，不要字符串比较。

### 2.4 跨函数字段跟踪

跨函数字段跟踪在 DFA 引擎里由 `MethodSummary` + `Operation.WriteToField`
承担（操作表在 [`CROSS-FUNCTION-ANALYSIS.md`](./CROSS-FUNCTION-ANALYSIS.md) §3，
副作用那半在 [`CROSS-FUNCTION-OPERATION.md`](./CROSS-FUNCTION-OPERATION.md) §5.7）：
callee 在 summary 里写出它改了哪些字段，caller 侧的机制把这些写进 caller 的 fact bag。

`SingletonRace` 和 `ServletAtomicity` 这两条规则**没有**走这条路 —— 它们已
迁移到 `plumb-style`，是纯 AST checker：

- `plumb-style/.../concurrency/SingletonRaceCheck.java` — 覆写
  `visit(ClassOrInterfaceDeclaration, StyleContext)`
- `plumb-style/.../concurrency/ServletAtomicityCheck.java` — 覆写
  `visit(MethodDeclaration, StyleContext)`

对应的 DFA 版（`SingletonRaceDfaChecker` / `ServletAtomicityDfaChecker`）
仍在源码里但**不注册**：在 `ScanRunner.buildDfaCheckers()` 里，
`config.runServletAtomicity` 和 `config.runSingletonRace` 两个分支是**故意留空的**，
注释说明 `plumb-style` 的 AST 等价实现以更高的精度覆盖同样的 CWE。
这两个 DFA checker 作为参考实现留在源码树里。

## 3. DFA 增强 checker 详解

### 3.1 不可为 null 的字段：只有 AST 版

`@NonNull` 字段的初始化检查目前只有 AST 版
（`plumb-style/.../quality/UninitNonnullCheck.java`）。它看不到的场景是
helper method 赋值：

```java
// illustrative: 这是规则要找的用户代码，不是引擎代码
@Entity
class User {
    @NonNull private final String name;
    @NonNull private String email;

    User(String name) {
        this.name = name;
        this.email = setEmail();   // ← helper method 赋值
    }

    private String setEmail() {
        return "default@example.com";
    }
}
```

AST 看到 `setEmail()` 不知道它会写字段 email。要覆盖这个场景，需要在 ctor 的
`initFunction` 里收集 `@NonNull` 字段作为必填集合，遍历 ctor 的所有 instruction
（`FIELDSTORE` 标字段已写；`CALL` 拉 callee 的 `FunctionSummary.returnOps()`，含
`WriteToField(f)` 就标 f 已写；`this(other)` 走另一个 ctor 的 summary 取并集），最后
`finishFunction` 时必填集合减去已写集合非空就报。

DFA 侧还没有这条规则，所以下面 3.2 到 3.6 是已实现的。

### 3.2 -DA: OrmLoadNullCheckDfaChecker

JPA `find()` 返回的 entity 可能是 null（找不到记录时）。AST 看到 `find()` 调用不知道"接着有没有 null check"。

**DFA 修法**（checker 维护两个 per-function map：`fromLoadVars` 和 `loadCallSite`，不是
单一的 `findResults` 集合）：
1. 对 load 方法的 CALL 把目标变量标成 from-load，并记下源头的 CALL 指令
2. BRANCH `if (x == null)` 时，x **不会**被从 `fromLoadVars` 里移除——检查把
   *null check* 标成发现位置，然后报出这次 load
3. 对**仍**在 `fromLoadVars` 里的变量做 CALL，或对它做 FIELDLOAD → fire

### 3.3 -DA: OrmUnnecessaryGetDfaChecker

JPA `entity.getXxx()` 每次都查 DB。"先 get 后丢弃"模式——结果查出来了，实体内容却从来
没被用过——就是一次没有意义的查询。

**DFA 修法**（checker 维护 per-function 的 `fromGetVars` 和 `getCallSite`）：
1. 对 `get` / `find` / `findById` / `getById` / `read` 的 CALL 把目标变量标成 from-get，
   并记下源头的 CALL
2. 这个标记通过 MOVE 传播，所以别名也会被跟踪
3. 某个 from-get 变量在函数里从来没被读过 → 多余 → 在原始调用点 fire
4. 某个 from-get 变量只被当作参数传给别的调用——对它没有 FIELDLOAD、它不作为接收者被调用、
   它的内容也没被赋给字段——同样多余 → fire

### 3.4 -DA: OrmLostUpdateDfaChecker

JPA `find → 修改 → save` 模式在并发下可能丢失 update（last writer wins）。

**DFA 修法**：summary 记录 callee 持久化了哪个参数（`Operation.PersistsParam`），
所以调用方能看出自己加载的 entity 正是被写回的那一个——也看得出 load 和 persist 之间
隔着那次修改。

### 3.5 -DA: SingletonRaceDfaChecker

singleton 的 field 在多个 servlet 请求间共享；一个方法读、另一个方法写 → race condition。

**DFA 修法**（checker 维护两个 map——`fieldReaders` 和 `fieldWriters`——不是
`state.writtenFields` 这样的单一集合）：
1. `initFunction` 记录 class、它那些非 final / 非 volatile 的字段，以及 class 里的方法签名集合
2. `checkInstruction` 对 `this` 上的每个 FIELDLOAD 记一个**读方**，对每个 FIELDSTORE 记一个
   **写方**（复合赋值两者都算）
3. `finish`：对每个既有读方又有写方、且读写方是**不同方法**的 `(class, field)`，fire 一次

这个 checker 刻意**不**检查同步——方法内访问的同步分析由 AST 版负责。
DFA 版纯粹给出"这个字段是否被一个以上方法碰过"这个信号，AST 看不到这一层。

### 3.6 -DA: ServletAtomicityDfaChecker

`getAttribute` + `setAttribute` 跨方法调用，AST 看不见。

**DFA 修法**（跟 SingletonRace 同形态）：维护 `getSites` 和 `setSites` 两个 map，
键是 attribute 名，值是碰过它的方法签名集合；当同一个键被一个以上方法访问时，
在 `finish` fire。键必须是**字符串字面量**——运行时的 key 无法静态分析，checker 会忽略它。
跟 SingletonRace 一样，同步性分析归 AST 版。

## 4. 设计决策

### 4.1 AST/DFA 并存

`@NonNull` 字段初始化这条规则目前只有 AST 版。它的规范 rule id 是
**`ast-quality-uninit-nonnull`**，裸名 `uninit-nonnull` 作为别名也被接受。
DFA 版还不存在（见 §3.1），所以这条规则是例外而不是常态：同时有两版的 checker
会发出**两个不同的 rule id**，由用户来选。

对两版都有的 checker，开关是 YAML 的 `checks.<rule>: {enabled: true|false}` 段，
或者 CLI 的 `--rule <id>`。`rules.enabled` 列表在出厂的 `default-config.yaml` 里
存在，但它是**被注释掉的**——那是一个 force-enable 列表的示例，不是引擎读取的开关：

```yaml
checks:
  ormLoadNullCheck: {enabled: true, aiDedup: true}   # DFA 侧
```

### 4.2 命名约定

| 侧 | 命名 | 例子 |
| --- | --- | --- |
| AST | `ast-<category>-<bare>` | `ast-quality-uninit-nonnull` |
| DFA，结构性 | `dfa-<category>-<bare>` | `dfa-controlflow-deadlock-ab-ba` |
| DFA，污点 | `dfa-taint-<bare>` | `dfa-taint-sql-injection` |

AST rule id **没有**结尾的 `-style`。`style` 是九个 AST 类别之一
（`ast-style-...`、`ast-quality-...`、`ast-security-...`、`ast-error-...`、`ast-bug-...`、
`ast-design-...`、`ast-naming-...`、`ast-performance-...`、`ast-concurrency-...`），
不是每个 id 的后缀。

DFA 的 id 是每个 checker 各自的常量，所以不一定符合那个形状。四个 ORM checker 和两个
race checker 发的是**裸 id**，没有 `dfa-` 前缀：`orm-load-null-check-dfa`、
`orm-unnecessary-get-dfa`、`orm-lost-update-dfa`、
`null-deref-from-return-dfa`（这个是 `dfa-taint-null-deref-from-return`）、
`singleton-race-dfa` 和 `servlet-atomicity-dfa`。

`plumb.<...>` 命名空间是**另一回事**：它命名的是 *fact kind*
（`plumb.nullable.may-be-null`、`plumb.resource.free-sink`），不是 rule id。

这些拼法都能被 YAML 匹配接受：`ScanRunner.isRuleIdAllowed` 双向 normalize
（详见 `tech/ARCHITECTURE.md §8`）。

### 4.3 key 选择

summary 缓存用什么作 key？
- ❌ 函数名（重载冲突）
- ❌ 类名 + 函数名（inner class / lambda 命名差异）
- ✅ **一个 `FunctionSignature`**，它的规范字符串形式是
  `"package.Class#method(paramType,...)"` —— 决定保留的就是它

规范形式由 `FunctionSignature.canonical()` 构造，它把整个字符串转成小写，
所以跨次运行是稳定的。`toString()` 是同样的形状，但保留原始大小写。
签名的具体规则：
- 类和方法之间用 **`#`** 分隔，不是 `.`
- 参数类型用逗号分隔，不带空格
- 返回类型已知时追加在 `:` 之后——`"pkg.C#m(int):void"`
- lambda 的合成函数叫 `lambda$N`，签名是 `lambda$N(arity)`
  （见 `plumb-tac/.../translate/ExpressionTranslator.java` 的 `translateLambda`）

`MethodSummaryCache` 有两种查 key 的方式：`get(FunctionSignature)` 查已解析的签名，
`getByCanonicalString(String)` 查规范拼写。

### 4.4 AST vs DFA 的判定准则

**如果满足以下任一条件，用 DFA 实现**：
1. 需要**跨方法数据流**（helper method chain / ctor chaining）
2. 需要**字段级精度**（aliasing / MOVE 传播）
3. 需要**类型化别名**（cookie / socket / connection 等"同类型多实例"场景）
4. 需要**路径敏感**（if (x == null) 后再访问 x）

**否则用 AST 实现**：
1. 仅看方法体形态（`if (x = y)` assignment-in-operand）
2. 纯命名 / 格式 / 注释（style rule）
3. 简单类型检查（`catch (Exception)` 抓 generic）
4. 调用方无关（regex 看 `System.out.println`）

这条准则就是本文档自己的：上面两张清单决定了 checker 该落在 AST 侧还是 DFA 侧。

## 5. 测试策略

DFA 增强 checker 需要三类测试覆盖：

| 测试类型 | 目的 |
| --- | --- |
| **正样本** | 给一段能命中的代码，验证 fire |
| **负样本** | 给 AST 版本会漏、DFA 版本能命中的代码 |
| **回归样本** | AST 版本已能命中、DFA 版本不破坏 |

测试放在 `plumb-dfa/src/test/java/org/plumb/dfa/` 下与 checker 匹配的包里：
`checkers/taint/`、`checkers/boundary/`、`checkers/controlflow/` 和 `taint/`。
并不是每个 checker 都有专门的测试类——其中若干的行为是通过共享的引擎测试
（alias-guard 矩阵、null-fact 域、v2 trace 校验）覆盖的，而不是每个 checker 一个文件。

## 6. 规则的默认开关

AST 版和 DFA 版的默认开/关，由 YAML 的 `checks.<rule>: {enabled: true|false}` 段控制
（DFA 侧还有 `plumb-dfa/src/main/resources/checkers.yaml` 里的同名条目）。

有一个分析风格的 `aggressiveness` 旋钮（`--aggressiveness=low|medium|high`）已经
废弃：CLI 没有这个 flag，YAML 里写 `aggressiveness:` 会被 `ConfigLoader` 忽略并往
stderr 打一条警告，扫描照常继续。改用 `checks.<rule>: {enabled: true|false,
aiDedup: true|false}` 段。

## 7. 尚未实现的 DFA 增强

已经落地的 DFA 增强：ORM 的四个（`OrmLoadNullCheckDfaChecker`、
`OrmUnnecessaryGetDfaChecker`、`OrmLostUpdateDfaChecker`、`NullDerefFromReturnDfaChecker`），
以及 `SingletonRaceDfaChecker` / `ServletAtomicityDfaChecker`（这两条有 AST 版且
精度更好，DFA 版留在源码里但不注册）。

还没有对应实现的：

| DFA 增强 | 场景 |
| --- | --- |
| `ORM_LAZY_INIT` | Hibernate lazy 加载 + 跨方法 |
| `CONCURRENT_ITERATION` | 跨方法集合迭代 |
| `THREAD_LOCAL_LEAK` | 跟 servlet 强相关 |

### 7.1 Z3 集成

DFA 增强的"单条 Z3 query"（Phase 2 per-finding 验证）见 `tech/CROSS-FUNCTION-OPERATION.md` §5.1（Operation.SolverZ3Predicate）。

## 8. 经验总结

### 8.1 AST → DFA 的迁移路径

不要试图一次把 AST checker 重写成 DFA。推荐路径：

1. **保留 AST 版**（不同 rule id），保证基本召回
2. **加 DFA 版**作为补强，专注 AST 漏报的具体场景
3. 用一套 per-CWE minimal yaml 让用户在两类间选择
4. 长期看 DFA 召回追上 AST + 1 个数量级后，弃 AST 版

### 8.2 FunctionSummary 的边界

`Operation` 是一个 **sealed** interface，有十个变体，而且变体**不**是无穷扩展。
新增一个变体需要：

- 在 `Operation.java` 加 sealed variant——而且必须加进 `permits` 子句，否则编译不过
- 一个生产者：把该操作记进 summary 的地方
- 一个消费者：看到 CALL 时去找它的 checker
- 配套测试（callee summary + caller 应用两端）

**缺了消费者就会静默失败**——操作被记录下来了，却永远没人读。
`SummaryApplier` **不**按 `Operation` 变体分派：它把 summary 的*返回值形态*和
*副作用记录*编码成 fact。变体是由各个 checker 消费的，每个 checker 只投影自己关心的那部分
（污点 checker 找 `Operation.TaintPropagate`，SQL 注入 checker 找 familyKey 匹配的
`Operation.Sink`）。

其中两个变体目前只有声明、从未被构造：`Operation.UnmodelledCall` 和
`Operation.PointsToPropagate`。它们是 sealed interface 的一部分，但没有任何代码路径
创建它们，所以今天是惰性的。

### 8.3 不要为 style rule 写 DFA

Style rule 的特征：**只需要 AST 形态**。用 DFA 实现：
- ❌ 不提升精度（AST 已经够）
- ❌ 不提升召回（style rule 本来就不漏）
- ❌ 增加维护成本（IR visitor 比 AST visitor 难调试）

如果不确定，问自己：*这条 rule 需不需要"数据从 A 流到 B"的判断？* 不需要 → AST。
