# DFA 增强设计：为什么 AST 不够用，怎么补

本文档解释 Plumb 在 5 个 Coverity Java checker 上**加 DFA 增强版**的工程取舍——为什么 AST 版本会漏报、DFA 版本如何消除 false negative、4 种通用 DFA 增强技术怎么用。

> 配套：`tech/CROSS-FUNCTION-ANALYSIS.md`（跨函数 summary 完整机制）、`tech/ARCHITECTURE.md §3.0`（DFA 引擎 SPI）、`rules/RULE-TOOL-COMPARISON.md §1.2`（DFA vs AST 取舍）

## 1. 背景：为什么 AST 不够用

- 系列 checker 在 AST 层已经能捕获大部分情况。但 AST 的视野天然受限于**单方法**：

| 漏报场景 | AST 局限 | DFA 怎么修 |
| --- | --- | --- |
| **Helper method 调用链** | `this.foo()` 把字段 f 赋了值，AST 看不到 | 拉 `foo()` 的 FunctionSummary 读 `Operation.WriteToField("f")` |
| **Ctor chaining** | `this(other)` 调用另一个 ctor，AST 难以跟踪另一个 ctor 的字段初始化 | 走 another ctor 的 summary，union 字段集合 |
| **Aliasing** | `y = x` 之后 `if (y == null)`，AST 只看 `x` 处的 null check | MOVE 指令传播 from-load 状态 |
| **跨方法字段访问** | servlet 的 `getAttribute("k")` 在方法 A，`setAttribute("k", v)` 在方法 B | 跨函数累积 getAttribute/setAttribute 调用 |

Coverity 商业引擎"真值"用 DFA 实现。本批次在 plumb-dfa 加 5 个 DFA 增强版，与 AST 版**并存**（不同 rule id）——给用户**两条选择**：
- 想**召回优先**：用 DFA 版（牺牲一点性能换漏报少）
- 想**性能优先**：用 AST 版（漏报多但跑得快）

## 2. 4 种通用 DFA 增强技术

### 2.1 FunctionSummary（最常用）

`plumb-dfa` 的框架已有 `FunctionSummary` 缓存机制：

```java
// Operation.WriteToField 标记某方法会写某字段
FunctionSummary summary = cache.get(calleeSig);
if (summary.returnOps().contains(new Operation.WriteToField("name"))) {
    // 调用方知道 callee 会写 this.name
}
```

`DfaEngine` 在 RPO 顺序遍历函数前会做**预热 pass**（`warmUpMethodSummaries`），把每个方法的 summary 先算好，调用方 checkInstruction 时直接查。

完整机制见 `tech/CROSS-FUNCTION-ANALYSIS.md §3`（Phase 1 自底向上构建 summary）。

### 2.2 MOVE alias 传播

```java
// MOVE dest = lhs1: 如果 lhs1 是 from-load 状态，dest 也标 from-load
if (ins.kind == MOVE && ins.lhs1 instanceof TacVar
        && ins.dest instanceof TacVar) {
    if (fromLoadVars.containsKey(ins.lhs1.name())) {
        fromLoadVars.put(ins.dest.name(), true);
    }
}
```

TAC 的 MOVE 指令对应 Java 的 `y = x` / `return x` 等纯赋值场景。这是字段级精度（per-variable）的关键——`y = x; if (y == null)` 的 null check 才会传回 `x` 的 from-load 状态。

### 2.3 BRANCH null 比较

```java
// BRANCH 指令 lhs1 binop lhs2 是 if 条件
if (ins.binop == BinaryOperator.EQ || ins.binop == BinaryOperator.NE) {
    // lhs1 / lhs2 其中一边是 TacVar 名字，另一边是 Const.NULL
    String l = ins.lhs1.toString();   // "x:UNKNOWN"
    String r = ins.ins.lhs2.toString();   // "null"
    String lBare = stripType(l);      // "x"
    String rBare = stripType(r);      // "null"
    if (fromLoadVars.containsKey(lBare) && "null".equals(rBare)) {
        fire();  // load + null check on l (or its alias)
    }
}
```

**常见 bug 源**：`BinaryOperator.EQ.toString()` 是字符串 `"EQ"`，**不是** `"=="`。写 BRANCH null checker 必须用 `==` 操作符对象的 `.equals()` 比对，不要字符串比较。

### 2.4 跨函数字段跟踪

跨函数字段跟踪在 DFA 引擎里由 `FunctionSummary` + `Operation.WriteToField`
承担（见 [`CROSS-FUNCTION-OPERATION.md`](./CROSS-FUNCTION-OPERATION.md) §5.7）：
callee 在 summary 里写出它改了哪些字段，caller 的 `SummaryApplier` 把这些
写进 caller 的 fact bag。

`SingletonRace` 和 `ServletAtomicity` 这两条规则**没有**走这条路 —— 它们已
迁移到 `plumb-style`，是纯 AST checker：

- `plumb-style/.../concurrency/SingletonRaceCheck.java` — 覆写
  `visit(ClassOrInterfaceDeclaration, StyleContext)`
- `plumb-style/.../concurrency/ServletAtomicityCheck.java` — 覆写
  `visit(MethodDeclaration, StyleContext)`

对应的 DFA 版（`SingletonRaceDfaChecker` / `ServletAtomicityDfaChecker`）
仍在源码里但**不注册**（`ScanRunner.buildDfaCheckers()` 里注册语句被注释掉），
因为 AST 版的精度更好。

## 3. 5 个 DFA 增强 checker 详解

### 3.1 -DA: UninitNonnullDfaChecker

**AST 漏报场景**：

```java
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

AST 看到 `setEmail()` 不知道它会写字段 email。DFA 看 `setEmail()` 的 FunctionSummary（含 `Operation.WriteToField("email")`），在 ctor 里就标 email 已被赋值。

**DFA 检测**：

```java
// 在每个 ctor 的 initFunction
// 1. 收集 @NonNull 字段 → 必填集合 required = {name, email}
// 2. 遍历 ctor 的所有 instruction
//    - FIELDSTORE: 标字段已写
//    - CALL with dest that is FIELDSTORE: 标字段已写
//    - CALL 拉 callee FunctionSummary.returnOps()，如含 WriteToField(f)，标 f 已写
//    - "this(other)" 走 another ctor 的 summary，union
// 3. finishFunction: required - 已写 != ∅ → fire
```

### 3.2 -DA: OrmLoadNullCheckDfaChecker

JPA `EntityManager.find()` 返回的 entity 可能是 null（找不到记录时）。AST 看到 `find()` 调用不知道"接着有没有 null check"。

**DFA 修法**：
1. CALL `EntityManager.find` 拉 callee summary，dest 加进 `findResults`
2. BRANCH `if (x == null)` 时从 `findResults` 移除 x
3. CALL `x.getXxx()` / `x.setXxx()` / `x.field` 命中**仍**在 `findResults` 的 x → fire

### 3.3 -DA: OrmUnnecessaryGetDfaChecker

JPA `entity.getXxx()` 每次都查 DB，**但赋值给 local var 后多次用**——典型的"先 get 后丢弃"模式。

**DFA 修法**：MOVE alias 传播跟踪 `local = entity.getXxx()`，第二次 `entity.getXxx()` 时 fire（除非中间修改了 entity）。

### 3.4 -DA: OrmLostUpdateDfaChecker

JPA `find → 修改 → save` 模式在并发下可能丢失 update（last writer wins）。

**DFA 修法**：跨函数累积 `find` + `save` 调用对，每对之间检查是否有 @Version 字段读写。

### 3.5 -DA: SingletonRaceDfaChecker

singleton 的 field 在多个 servlet 请求间共享，非同步读写 → race condition。

**DFA 修法**：
1. 第一次 checkInstruction 遇到 FIELDSTORE，把"哪些字段被写"加到 `state.writtenFields`
2. 第二次遇到 FIELDLOAD，从 `state.writtenFields` 移除
3. finishFunction：如果 `state.writtenFields != ∅` 且 class 是 singleton（私有 ctor + 静态 getInstance）→ fire

### 3.6 -DA: ServletAtomicityDfaChecker

`getAttribute` + `setAttribute` 跨方法调用，AST 看不见。

**DFA 修法**（跟 SingletonRace 同模式）：
1. 跨函数累积 `getAttribute` / `setAttribute` 键集
2. finishFunction：如果某 key 多次 get/set 但没在 synchronized 块内 → fire

## 4. 设计决策

### 4.1 AST/DFA 并存

每个 checker 出**两个 rule id**：
- AST 版：`ast-style-uninit-nonnull-style`（ 命名规范）
- DFA 版：`plumb.dfa.uninit-nonnull`（ 命名规范）

用户通过 yaml `rules.enabled` 二选一：

```yaml
rules:
  enabled:
    - ast-style-uninit-nonnull-style   # 快，漏报多
    - plumb.dfa.uninit-nonnull        # 慢，漏报少
```

### 4.2 命名约定

| 模式 | 命名 | 例子 |
| --- | --- | --- |
| AST 版 | `ast-style-<bare>-style` | `ast-style-uninit-nonnull-style` |
| DFA 版 | `plumb.dfa.<bare>` | `plumb.dfa.uninit-nonnull` |
| Taint 版 | `plumb.taint.<bare>` | `plumb.taint.sql-injection` |

3 种风格都支持任意一种写法的 yaml 匹配（`ScanRunner.isRuleIdAllowed` 双向 normalize，详见 `tech/ARCHITECTURE.md §8`）。

### 4.3 key 选择

FunctionSummary 用什么作 key？
- ❌ 函数名（重载冲突）
- ❌ 类名.函数名（inner class / lambda 命名差异）
- ✅ **signature = "Class.method(param1,param2,...)"**（ 决定保留）

签名规范化的具体规则：
- 内部类用 `$` qualifier（JVM 风格）
- lambda 合成函数的 signature 是 `lambda$N(arity)`（见
  `plumb-tac/.../translate/ExpressionTranslator.java` 的 `translateLambda`）
- 重载消歧用 arity + 参数类型

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

这条准则的来源：`CROSS-FUNCTION-ANALYSIS.md §12.3`（不迁移规则清单）。

## 5. 测试策略

每个 DFA 增强 checker 配 3 类测试：

| 测试类型 | 目的 | 例子 |
| --- | --- | --- |
| **正样本** | 给一段能命中的代码，验证 fire | `UninitNonnullDfaCheckerTest.bothFieldsAssigned_helperMethod()` |
| **负样本** | 给 AST 版本会漏、DFA 版本能命中的代码 | `UninitNonnullDfaCheckerTest.helperMethodWritesField()` |
| **回归样本** | AST 版本已能命中、DFA 版本不破坏 | `UninitNonnullDfaCheckerTest.directFieldAssign()` |

所有测试在 `plumb-dfa/src/test/.../unified/checkers/` 下，每个 checker 至少 5 个测试。-DA 通过 8 个测试，含 -DA OrmLostUpdateDfaChecker。

## 6. 规则的默认开关

AST 版和 DFA 版的默认开/关，由 YAML 的 `checks.<rule>: {enabled: true|false}` 段控制
（DFA 侧还有 `plumb-dfa/src/main/resources/checkers.yaml` 里的同名条目）。

有一个 Coverity 风格的 `aggressiveness` 旋钮（`--aggressiveness=low|medium|high`）已经
废弃：CLI 没有这个 flag，YAML 里写 `aggressiveness:` 会被 `ConfigLoader` 忽略并往
stderr 打一条警告，扫描照常继续。改用 `checks.<rule>: {enabled: true|false,
aiDedup: true|false}` 段。

## 7. 后续工作

### 7.1 -DA — 更多 DFA 增强

- 之后，下一批候选（按 ROI 倒序）：

| DFA 增强 | ROI | 备注 |
| --- | ---: | --- |
| `ORM_LAZY_INIT` | 高 | Hibernate lazy 加载 + 跨方法 |
| `NULL_DEREF_FROM_RETURN` | 高 | R83 已修（UseAfterFree 路径） |
| `CONCURRENT_ITERATION` | 中 | 跨方法集合迭代 |
| `THREAD_LOCAL_LEAK` | 低 | 跟 servlet 强相关 |

### 7.2 Z3 集成

DFA 增强的"单条 Z3 query"（Phase 2 per-finding 验证）见 `tech/CROSS-FUNCTION-ANALYSIS.md §5.5`（Operation.SolverZ3Predicate）。

## 8. 经验总结

### 8.1 AST → DFA 的迁移路径

不要试图一次把 AST checker 重写成 DFA。推荐路径：

1. **保留 AST 版**（不同 rule id），保证基本召回
2. **加 DFA 版**作为补强，专注 AST 漏报的具体场景
3. 用一套 per-CWE minimal yaml 让用户在两类间选择
4. 长期看 DFA 召回追上 AST + 1 个数量级后，弃 AST 版

### 8.2 FunctionSummary 的边界

`Operation` 变体**不**是无穷扩展。每个新加的 Operation 都要：
- 在 `Operation.java` 加 sealed variant
- 在 `MethodSummaryBuilder` 加 builder
- 在 `SummaryApplier` 加 applier
- 配套测试（callee summary + caller 应用两端）

**3 个步骤缺一个就静默失败**——

### 8.3 不要为 style rule 写 DFA

Style rule 的特征：**只需要 AST 形态**。用 DFA 实现：
- ❌ 不提升精度（AST 已经够）
- ❌ 不提升召回（style rule 本来就不漏）
- ❌ 增加维护成本（IR visitor 比 AST visitor 难调试）

如果不确定，问自己：*这条 rule 需不需要"数据从 A 流到 B"的判断？* 不需要 → AST。
