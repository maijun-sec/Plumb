# Coverity Java Checker — DFA 增强设计 (2026-08-08)

> 本文档描述 5 个 Coverity Java checker 的 DFA 增强版（R540-DA / R541-DA / R542-DA / R560-DA / R561-DA），
> 解释为什么 AST 版本不够用、DFA 版本如何消除漏报（false negative）。

## 1. 背景

R540-R561 系列 checker 在 AST 层已经能捕获大部分情况。但 AST 的视野天然受限于**单方法**：

- **Helper method 调用链断掉**：`this.foo()` 把字段 f 赋了值，AST 看不到。
- **Ctor chaining**：`this(other)` 调用另一个 ctor，AST 难以跟踪另一个 ctor 的字段初始化。
- **Aliasing**：`y = x` 之后 `if (y == null)`，AST 只看 `x` 处的 null check。
- **跨方法字段访问**：servlet 的 `getAttribute("k")` 在方法 A，`setAttribute("k", v)` 在方法 B。

Coverity 的"真值"用 DFA 实现。本批次在 plumb-dfa 加 5 个 DFA 增强版，与 AST 版**并存**（不同 rule id）。

## 2. 通用 DFA 增强技术

### 2.1 FunctionSummary

`plumb-dfa` 的 R215 框架已有 `FunctionSummary` 缓存机制：

```java
// Operation.WriteToField 标记某方法会写某字段
// (R213 框架：Operation.WRITE_TO_FIELD 标记 method 写了哪个字段)
FunctionSummary summary = cache.get(calleeSig);
if (summary.returnOps().contains(new Operation.WriteToField("name"))) {
    // 调用方知道 callee 会写 this.name
}
```

DfaEngine 在 RPO 顺序遍历函数前会做**预热 pass**（`warmUpMethodSummaries`），把每个方法的 summary
先算好，调用方 checkInstruction 时直接查。

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

TAC 的 MOVE 指令对应 Java 的 `y = x` / `return x` 等纯赋值场景。

### 2.3 BRANCH null 比较

```java
// BRANCH 指令 lhs1 binop lhs2 是 if 条件
if (ins.binop == BinaryOperator.EQ || ins.binop == BinaryOperator.NE) {
    // lhs1 / lhs2 其中一边是 TacVar 名字，另一边是 Const.NULL
    String l = ins.lhs1.toString();   // "x:UNKNOWN"
    String r = ins.lhs2.toString();   // "null"
    String lBare = stripType(l);      // "x"
    String rBare = stripType(r);      // "null"
    if (fromLoadVars.containsKey(lBare) && "null".equals(rBare)) {
        fire();  // load + null check on l (or its alias)
    }
}
```

注意 `BinaryOperator.EQ.toString()` 是字符串 `"EQ"`，**不是** `"=="`。常见 bug 源。

### 2.4 Inter-procedural field tracking

SingletonRace 和 ServletAtomicity 走的是同一种模式：

1. 第一次 checkInstruction 遇到 CALL 时，把 callee 的 FunctionSummary 拉过来。
2. 维护一个**跨函数累积**的状态：每条 CALL 的"做了什么"都记到当前 function 的状态里。
3. finishFunction 时如果累积状态命中 bad pattern，报。

## 3. 六个 DFA 增强 checker 详解

### 3.1 R540-DA: UninitNonnullDfaChecker

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

AST 看到 `setEmail()` 不知道它会写字段 email。DFA 看 `setEmail()` 的 FunctionSummary
（包含 `Operation.WriteToField("email")`），在 ctor 里就标 email 已被赋值。

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

**关键代码**：

```java
@Override
public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.kind == TacInstruction.Kind.CALL) {
        // R213 inter-procedural: look up callee summary
        FunctionSummary calleeSum = ctx.summaryCache().get(ins.calleeSig);
        if (calleeSum != null) {
            for (Operation op : calleeSum.returnOps()) {
                if (op instanceof Operation.WriteToField wf) {
                    assignedFields.add(wf.fieldName());
                }
            }
        }
    }
    if (ins.kind == TacInstruction.Kind.FIELDSTORE) {
        if (ins.lhs1 instanceof TacOperand.TacVar self
                && "this".equals(self.name())) {
            assignedFields.add(ins.fieldName);
        }
    }
}
```

### 3.2 R541-DA: OrmLoadNullCheckDfaChecker

**AST 漏报场景**：

```java
User u = session.load(User.class, 1L);  // load
User v = u;                              // alias
if (v == null) {                         // null check on alias
    ...
}
```

AST 只看 `u` 后紧接的 `if (u == null)`。遇到 `v = u; if (v == null)` 就漏。

**DFA 检测**：

```java
// 1. CALL 命中 load / getReference / getOne / loadById → 标 dest 为 from-load
// 2. MOVE: lhs1 是 from-load → dest 也 from-load (alias 传播)
// 3. finishFunction 扫所有 BRANCH，lhs1/lhs2 其中一边是 from-load 的 var 名，另一边是 null
```

### 3.3 R542-DA: OrmUnnecessaryGetDfaChecker

**AST 漏报场景**：

```java
User u = session.get(User.class, 1L);  // get
User v = u;                            // alias
foo(v);                                // 传给别的函数，AST 看不到
```

AST 只看 `u` 后紧接的语句。`foo(v)` 不是直接对 u 做内容访问，AST 报。DFA 跟踪 v 是 u 的 alias，
到 `foo(v)` 时发现 v 从未被 content access，fire。

**DFA 检测**：

```java
// 1. CALL 命中 get / find → 标 dest 为 from-get
// 2. MOVE: 传播 alias
// 3. finishFunction 扫所有 use-from-get 的 var
//    - 如果 use 是 field load / method call with content access: 标 "used in content"
//    - 如果 use 只是 argument passing / field store / array store: 标 "used as reference only"
//    - 如果所有 use 都是 reference-only: fire
```

### 3.4 R560-DA: SingletonRaceDfaChecker

**AST 漏报场景**：

```java
@Controller
class MyController {
    private int counter;   // 字段，无 final / volatile

    @GetMapping("/a")
    void handleA() {
        counter++;          // ← 写
    }
    @GetMapping("/b")
    int handleB() {
        return counter;     // ← 读 (在另一个方法)
    }
}
```

AST 只看单方法，handleA 写 counter 没问题（同一方法没冲突），但跨方法 read+write 是 race。

**DFA 检测**：

```java
// 1. 跨 class-level 聚合：所有 method 的 field read / write 集合
//    - R260 预热 pass 把每个 method 的 read+write set 算好
//    - 跨方法合并: readFields = union(methods.read), writeFields = union(methods.write)
// 2. finishClass: 字段 f 满足 (f in readFields) && (f in writeFields) && !f.isFinal && !f.isVolatile → race
// 3. 报在 write 的 method 上（最早的竞争点）
```

### 3.5 R561-DA: ServletAtomicityDfaChecker

**AST 漏报场景**：

```java
@WebServlet("/foo")
class FooServlet extends HttpServlet {
    void doGet(HttpServletRequest req, HttpServletResponse resp) {
        Object v = getServletContext().getAttribute("k");   // 读
        // ... 一段无关代码
        doSomething(v);
    }
    void doPost(HttpServletRequest req, HttpServletResponse resp) {
        getServletContext().setAttribute("k", newValue);    // 写
    }
}
```

AST 只看 doGet 一个方法，getAttribute 后没 setAttribute，不报。但 doPost 在另一个方法写同一个 key，
GET/SET 不在同一 sync block 是 race。

**DFA 检测**：

```java
// 1. 跨 class-level 聚合：getAttribute("k") / setAttribute("k") 在哪些 method
// 2. 同一 key 在不同 method 同时出现 GET 和 SET → fire
// 3. 报在 GET 的方法上
```

### 3.6 R543-DA: OrmLostUpdateDfaChecker (cross-method persistence)

**AST 漏报场景**：

```java
class UserService {
    void createUser() {
        User u = new User();           // 1) allocate
        u.setName("x");                 // 2) mutate via setter (FIELDSTORE inside setName)
        service.save(u);                // 3) persist through wrapper
    }
}
class UserDao {
    void save(User u) {
        entityManager.persist(u);       // actual persist — one level deeper
    }
}
```

AST `OrmLostUpdateCheck` 看 `service.save(u)` 调的是 `save` 但不知道 `save` 内部是否会 `persist`。
DFA `OrmLostUpdateDfaChecker` 看 `service.save` 的 FunctionSummary 知道它"persists param 0"，
递归查 `entityManager.persist` 也"persists param 0"，所以 `u` 标 persisted。

**新增 Operation: `PersistsParam(int paramIndex)`**（`Operation.java` 内的 sealed record）:

```java
public sealed interface Operation
        permits Operation.PassThrough,
                Operation.Sink,
                Operation.WriteToField,
                Operation.TaintPropagate,
                Operation.HasUnmodelledBehavior,
                Operation.UnmodelledCall,
                Operation.SolverZ3Predicate,
                Operation.PointsToPropagate,
                Operation.PersistsParam { ... }

record PersistsParam(int paramIndex) implements Operation { ... }
```

**DFA 检测**：

```java
// 1. CALLEE 解析: new X#<init> 且 X 不是 JDK 类型 → dest 标 transient
// 2. CALLEE 解析: persist/save/merge/update/saveOrUpdate/saveOrUpdateCopy/store 调用的 TacVar 参数 → 标 persisted
// 3. CALLEE 解析: 在 summaryCache 查 callee 的 FunctionSummary
//    - paramOpsAt(pi) 含 PersistsParam → ins.args[pi] 标 persisted
//    - paramOpsAt(pi) 含 WriteToField → ins.args[pi] 标 mutated (helper setter)
// 4. FIELDSTORE: lhs1 是 transient var → 标 mutated
// 5. finishFunction: transient + mutated + !persisted → fire
// 6. summarizeFunction: 把"this function persists param N"记录到自己的 summary.paramOps(N)
```

**Heuristic for "is X an entity?"**: 任何 `new X()` 其中 X 不在 `NON_ENTITY_TYPES` deny-list（String/Integer/ArrayList/UUID/...）
就标 transient。这是有意宽松的，因为"mutated + not persisted"模式在普通 class 上几乎也是 bug。
比 AST 严格基于 `@Entity` 注解更宽松，但误报风险低。

## 4. 设计决策

### 4.1 为什么 AST 和 DFA 并存？

| 维度 | AST | DFA |
|------|-----|-----|
| 速度 | 极快（单 pass） | 慢（pre-pass + RPO + summary） |
| 范围 | 单方法 | 跨方法 |
| 精度 | 高（语义明确） | 中（alias 误报） |
| 配置 | 自动注册 | yaml 注册 |

**并存策略**：AST 在 plumb-style 跑默认。DFA 在 plumb-dfa 跑，rule id 加 `-dfa` 后缀。
两个都报，deduplication 留给上层 sarif 输出。

### 4.2 命名约定

- AST: `orm-load-null-check` (plumb-style `quality.OrmLoadNullCheckCheck`)
- DFA: `orm-load-null-check-dfa` (plumb-dfa `OrmLoadNullCheckDfaChecker`)
- yaml 里的 checker name 也带 `-Dfa` 后缀（`OrmLoadNullCheckDfa`）

### 4.3 跨方法状态的"key"选择

| Checker | Key | 原因 |
|---------|-----|------|
| R540-DA | field name | 字段初始化是 class 级别 |
| R541-DA | TacVar name | 同一 function 内 alias 传播 |
| R542-DA | TacVar name | 同上 |
| R543-DA | TacVar name | 跨方法持久化追踪，每个 var 独立 |
| R560-DA | field name | class 级别 |
| R561-DA | attribute key string | 跨方法按 key 聚合 |

## 5. 测试策略

每个 DFA checker 配 5-7 个 unit test：

| 测试名 | 模式 |
|--------|------|
| `firesOn_<base pattern>` | AST 等价场景，DFA 也能 fire |
| `firesAfter_<aliasing>` | DFA 独占场景，AST 漏报 |
| `noFireWhen_<guard>` | false positive 防御 |
| `noFireOn_<unrelated call>` | 跨 sink family 不会误报 |
| `firesOn_<cross-method>` | 跨方法场景，AST 单方法看不到 |

测试基类统一用 `DfaEngine` + `CheckerRegistry.empty()` 构造，最小依赖。

## 6. 后续工作

- **R543-DA** (ORM_LOST_UPDATE 跨方法持久化)：✅ 已完成 (R584, 2026-08-08) — 跨方法场景用 FunctionSummary + 新增 `Operation.PersistsParam` record 解决。
- **R581-DA / R582-DA** (INSECURE_RANDOM / JAVA_CODE_INJECTION)：已经是 TaintCheckerBase
  子类，DFA 的核心 (taint propagation) 已在用，单独 -Dfa 版本 ROI 低。
- **R526-DA** (WEAK_URL_SANITIZATION 跨 helper method 验证)：URL 验证通常链多个 helper，
  DFA 跟踪"validated" flag。P3。

## 6.1 R585 — Coverity-style aggressiveness knob

Coverity 在 `cov-analyze` 命令里提供 `--aggressiveness-level` 和
`--webapp-security-aggressiveness-level` 选项 (low / medium / high)，控制"误报 vs 漏报"的
tradeoff。本批次在 plumb 加了等价的 per-checker 字段：

### 6.1.1 框架

**plumb-dfa**: `CheckerConfig` 加 `aggressiveness` 字段 (默认 `medium`)
+ `isAtLeast(String)` 访问器。`ConfigLoader` 解析 yaml 的 `aggressiveness:` 字段。

**plumb-style**: `StyleContext` 加 `aggressiveness` 字段
+ `withAggressiveness(String)` / `isAtLeast(String)` 访问器。

### 6.1.2 应用示例 (R543-DA OrmLostUpdateDfaChecker)

```java
// R585 — aggressiveness-gated "setter-via-CALL counts as mutation"
if ("high".equals(aggressiveness)) {
    for (TacOperand arg : ins.args) {
        if (arg instanceof TacOperand.TacVar) {
            String n = ((TacOperand.TacVar) arg).name();
            if (transientVars.containsKey(n)) {
                mutatedVars.put(n, true);
            }
        }
    }
}

// R585 — aggressiveness-gated "skip cross-method summary lookup"
if ("low".equals(aggressiveness)) return;  // skip section 3
```

| Mode | 行为 | 适用场景 |
|------|------|----------|
| **low** | 跳过跨方法 summary 查找，最严格 | CI / production 误报敏感场景 |
| **medium** | 默认 (跨方法 + direct FIELDSTORE) | 通用 |
| **high** | setter-via-CALL 算 mutation，最大覆盖 | security audit 漏报敏感场景 |

### 6.1.3 yaml 配置示例

```yaml
checkers:
  OrmLostUpdateDfa:
    enabled: true
    severity: MEDIUM
    cwe: CWE-459
    description: "..."
    class: org.plumb.dfa.unified.checkers.OrmLostUpdateDfaChecker
    aggressiveness: medium  # or "low" / "high"
```

### 6.1.4 测试覆盖

R543-DA 新增 3 个测试验证 aggressiveness 行为：
- `highAggressivenessFiresOnSetterViaCall` — high 模式触发
- `lowAggressivenessSkipsCrossMethodSummary` — low 模式严格
- `noFireWhenEntityIsNotMutated` — 默认 (medium) 严格

总计 8/8 通过。

## 7. 测试现状 (2026-08-08)

| Module | Tests | Pass | Fail |
|--------|------:|-----:|-----:|
| plumb-style | 941 | 941 | 0 |
| plumb-dfa | 498 | 498 | 0 |
| **总** | **1439** | **1439** | **0** |

6 个 DFA checker 各 2-8 个测试，共 26 个新测试，0 回归。
新增 AST checker (R520-R526 + R530 + R540-R544 + R560-R561) 共 +60 测试。
11 个新 Taint checker (R500-R507 + R580-R582) 共 +11 测试。
R585 aggressiveness 测试 +3 (OrmLostUpdateDfa × 2 + SingletonRaceDfa × 1)。
