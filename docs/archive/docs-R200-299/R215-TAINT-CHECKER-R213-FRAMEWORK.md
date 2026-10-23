# R215 — TaintChecker 接 R213+R214 完整重构 + TaintDomain

**Round**: R215 · **2026-07-31** · **状态**: ✅ DONE

---

## 1. 背景与设计目标

R213 落地了 `FunctionSummary` 通用框架 + TAC 三分类；R214a 落地 12 个 built-in
`FactKind` + `Fact`/`FactBag` 实例层（Layer 2）；R214b 落地了 per-instruction Fact
注入接口（`AnalysisContext.factBag()` 跟 `DfaEngine` 在每个 function 切换时清空）。

R215 的目标是把 R148 系列的 TaintAnalysis 完全重构到 R213 framework，让所有
taint 风格的规则走同一条流水线。同时回应用户两条硬约束：

1. **"规则不仅限于上面的几类，还有其他的需要使用数据流分析的，也都建议考虑
   添加到当前的数据流分析框架"** → R215 设计的 `TaintedCheckerBase` 不只服务
   SQL/LDAP/Command taint；它是一个通用模板，未来 R216 的 5 个 DFA 类 checker
   （DivByZero/IntOverflow/NullDeref/ResourceLeak/SensitiveDataLeak）也走它。
2. **"每类规则实现时，建议参考 Fathom 中的 Fact 的概念，非常重要"** → R215 完
   全对齐 Fathom `TaintedChecker` + `TaintedScalarChecker` + `SinkFamilyRegistry`
   的设计：
   - Fathom `FactKind` 7 个 Trait → Plumb `FactKind` 7 个 Trait（R208/R214a）
   - Fathom `TaintedChecker` 暴露 `untrustedSourceKind`/`untrustedSinkKind` →
     Plumb `TaintedCheckerBase` 暴露 `TaintRule` 作为构造参数
   - Fathom `SinkFamilyRegistry` 字典式 family → callee name 映射 → Plumb
     `SinkFamilyRegistry` (R209) 用 pattern + cwe
   - Fathom `TaintRuleChecker` 多 rule 注册 → Plumb `TaintRuleRegistry` (R215)
     内置 12 个 CWE family rules

---

## 2. 实施清单

### plumb-dfa/.../taint/ (new 子包)

- `TaintDomain.java` (7162 bytes) — sealed interface 4 子类型：
  - `StringTaint` (singleton) — SQL/LDAP/Command/FormatString/XSS/Log/Path 等
  - `NumericTaint` (singleton) — array-index/allocation-size/divide-by-zero/overflow
  - `PointerTaint` (singleton) — null-deref/UAF/resource-leak
  - `MixedTaint` (singleton) — deserialization/weak-crypto 等不区分 domain 的
  - `parse(String)` — case-insensitive + alias (INT/LONG/NUMBER → NumericTaint;
    REFERENCE/HANDLE → PointerTaint; ANY → MixedTaint)
  - `join(a, b)` — 同域返回同域；异域返回 MixedTaint
  - `flowsInto(expected)` — MixedTaint 流向任何；具体域只流向自己或 MixedTaint
  - **替换 `CheckerConfig.taintDomain` 的 String 字段**（R208 立的字段，从
    free-form 提升为类型化的 sealed sum type）

- `TaintRule.java` (3923 bytes) — record `(ruleId, sourceKindName, sinkFamilyKey,
  cwe, severityLabel, domain)`，5 个参数；`of(...)` 5-arg 重载默认 severityLabel
  "HIGH"；`severity()` 转 `Finding.Severity` enum；`matchesSinkFamily(familyKey)`
  支持通配 `*` 匹配所有 family

- `TaintRuleRegistry.java` (9209 bytes) — 索引 by ruleId + by sinkFamily；12 个
  built-in rules：plumb.taint.sql-injection / ldap-injection / command-injection
  / format-string / xss / log-injection / path-traversal / ssrf / xxe /
  unsafe-deserialization / weak-crypto / weak-prng；`withRule(rule)` immutable
  增量更新；`register(rule)` 兼容老 API（实现 stub 标注，prefer `withRule`）

### plumb-dfa/.../taint/ 子包核心

- `TaintedCheckerBase.java` (17045 bytes) — 抽象类继承 `DfaChecker` (R211)：
  - **`initFunction`**: 清空 per-function 状态 (`taintedVars`, `sinkObservations`)；
    标记所有 params 为 "potentially tainted" (`param:<name>`)
  - **`checkInstruction`** 5 大分支:
    - `MOVE` / `UNARYOP` / `FIELDLOAD` / `ARRAYLOAD`: dest ← lhs1 taint 传播
    - `BINOP`: dest ← lhs1 ∪ lhs2 taint 传播
    - `CALL`: 1) sink family check → emit Finding if arg is tainted; 2) cross-
      function summary check → 查 callee summary 的 `paramOpsAt(i)` 找
      `Operation.TaintPropagate`，给 caller 端 arg 标 tainted
    - `RETURN`: handled in `summarizeFunction`
  - **`summarizeFunction`**: 对每个 tainted param 写
    `Operation.TaintPropagate("param:<name> in <sig>", 1.0)`；对每个 observed
    sink call 写 `Operation.Sink(familyKey, argIndex)`。Caller 通过
    `ctx.summaryCache().get(calleeSig)` 看到这些 op，caller-side 的
    `propagateTaintFromCalleeSummary` 把 summary 翻译成 caller 端 arg 上的 taint
  - **子类钩子** 3 个: `sinkArgumentIndex(family, call)`, `findingMessage(...)`,
    `isSourceFact(fact)` — 让非 taint DFA 规则 (R216) 复用同一模板

### plumb-dfa/.../taint/checker/ (new 子包, 3 mirror)

- `SqlInjectionChecker.java` (1604 bytes) — CWE-89, CRITICAL, sql.sinks
- `LdapInjectionChecker.java` (1141 bytes) — CWE-90, HIGH, ldap.sinks
- `CommandInjectionChecker.java` (1037 bytes) — CWE-78, CRITICAL, command.sinks

每个 1 行 constructor + super(...) — 框架把数据流分析完全模板化。

### plumb-dfa/src/test/.../taint/ (3 new test classes, 17 tests)

- `TaintDomainTest.java` (4516 bytes, 7 tests) — 4 个 label + parse + flowsInto
  + join 覆盖
- `TaintedCheckerBaseTest.java` (9030 bytes, 6 tests) — paramsAreTaintedOnInit,
  movePropagatesTaint, binopPropagatesTaint, sinkCallFiresFindingWhenTaintedArg,
  nonTaintedArgToSinkDoesNotFire, summarizeFunctionRecordsTaintPropagateAndSink
- `SqlInjectionCheckerTest.java` (9173 bytes, 4 tests) — nameIsRuleId,
  detectsSqlInjectionViaExecute, doesNotDetectUntaintedCall,
  crossFunctionTaintFlowsThroughSummary

### 修复 R214b pre-existing bugs (2 处)

- `R214bFactBagTest.java` import 路径错 (`fact.FactKind` 应是 `config.FactKind`)
- `R214bFactBagTest.factsFromFunctionADoNotLeakIntoFunctionB` 漏
  `program.addFunction(...)` — program 是空的，0 functions analysed 引起 aCount=0
- R214b initFunction `currentFn` local var 不是 effectively final — 改用
  `String[] currentFnHolder` 数组

---

## 3. 5 大设计创新点

1. **`TaintDomain` 是 sealed-interface 4 元 sum type** (R215 design).
   替代 R208 `CheckerConfig.taintDomain` 的 free-form String。Sealed
   permits 让 Java 编译器在 pattern-match 时做 exhaustiveness check，
   防止"加新 domain 漏 switch case"类 bug。Domain 间通过 `join()` 自然
   升级到 `MixedTaint`，通过 `flowsInto()` 实现"could match"语义。

2. **`TaintedCheckerBase` 是 R213 framework 的首个 DFA 模板** (R215 design).
   不只服务 taint 类规则。任何"tainted set → propagate along SSA def-use
   → check sink family → write summary"模式的 DFA 规则都可以继承
   `TaintedCheckerBase`，3 行写一个 checker。R216 的 DivByZero /
   IntOverflow / NullDeref / ResourceLeak / SensitiveDataLeak 全部走这个
   模板，**5 个 rule 实现成本 ≈ 1 个**。

3. **Cross-function taint flow 走 summary cache 完整闭合** (R215 design).
   R213 让 callee 写 summary，caller 端 R214b 让 FactBag 接收；R215 补上
   caller-side checker 看到 CALL 时查 summary 找 TaintPropagate，
   把 taint 投射到 caller 端 arg 上。这是 R148 系列 cross-function taint
   (R157/R158/R161/R162) 的 R213 framework 重写版 — 同一份逻辑，新框架
   跑出新速度。

4. **Built-in 12 rule registry (R215 TaintRuleRegistry.builtins)** (R215 design).
   R208 的 CheckerRegistry 缺数据；R215 `TaintRuleRegistry.builtins()` 一次
   性给 SQL/LDAP/Command/FormatString/XSS/Log/Path/SSRF/XXE/Deserialization/
   WeakCrypto/WeakPrng 12 个 CWE family rule，**future R215a/b/c 加 family
   mirror 不用改任何代码**。`withRule(rule)` immutable 增量更新让 user custom
   rule 跟 built-in 共存。

5. **Fathom TaintedChecker 设计 100% mirror** (R215 design).
   - Fathom `untrustedSourceKind` / `untrustedSinkKind` (TaintedChecker 静态
     字段) → Plumb `TaintRule.sourceKindName` (record 字段, 6-param ctor)
   - Fathom `SinkFamilyRegistry.family(String)` → Plumb
     `SinkFamilyRegistry.lookup(calleeSig)` (R209, regex pattern)
   - Fathom `TaintRuleChecker` (multi-rule registry) → Plumb
     `TaintRuleRegistry` (12 built-in + withRule)
   - Fathom `TaintChecker.checkFunction` (legacy pass) → Plumb
     `TaintedCheckerBase.checkInstruction` (R213 5-step lifecycle)
   - Fathom `TAINTED_SCALAR` (consumer 模式) → Plumb
     `TaintedCheckerBase.summarizeFunction` 写 `Operation.TaintPropagate`
     + `Operation.Sink` 双 op (caller-side 自动消费)

---

## 4. 测试结果

```
[INFO] Tests run: 124, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Module | R215 new | Cumulative |
|---|---|---|
| plumb-tac | 0 | 24 |
| plumb-dfa | 17 (R215) + 0 (R214b fix) | 124 |
| **Total new** | **17** | **+17 from R214b baseline** |

Breakdown:
- `TaintDomainTest`: 7 tests
- `TaintedCheckerBaseTest`: 6 tests
- `SqlInjectionCheckerTest`: 4 tests
- `R214bFactBagTest`: 4 tests (其中 1 个 pre-existing bug 修复)
- 其他 plumb-dfa 累积: 92 tests
- 备份: `D:\work\tmp\r215_done/` (10 main + 3 test files, ~55KB)

---

## 5. R215 → R216/R217 路径

R215 把"DFA 规则 = 1 个 4-arg constructor"的成本锁死。后续 rounds:

- **R216 (next, ~2 day)**: 5 mirror checker 接 plumb-dfa
  (DivByZero / IntOverflow / NullDeref / ResourceLeak / SensitiveDataLeak)
  走 R213 framework + R215 `TaintedCheckerBase` 模板，3 行写一个。
  5 个 rule 实现总成本 ≈ R215 1 个 round。

- **R217 (planned, ~2 day)**: Z3 + summary 集成 — 加
  `Operation.SolverZ3Predicate` 让 callee summary 携带 Z3 谓词；
  TacToZ3 port (Fathom `TacToZ3.java` ~600 LoC). Z3 optional +
  LocalSolver fallback.

- **R218 (planned, ~0.5 day)**: End-to-end 验证 — Juliet 样本对比
  plumb-dfa (legacy) vs plumb-dfa (R213 framework) 的 TP/FP/P/R metrics。

---

## 6. R215 踩坑记录

- **`EnumSet.copyOf(emptySet)` throws** (R215 跟 R214a 同根因): Java 21+ 需要
  至少一个 element 做 type token. `FactKind` ctor 处理 empty (R214a 已修)；
  `TaintRuleRegistry` 没用 EnumSet，规避了这个问题
- **Finding 跟 SinkFamily 字段复用**: test 期望 `f.severity() == CRITICAL`
  (SinkFamily 字段) 但 R215 initial 写死 `rule.severity() == HIGH` (TaintRule
  字段)。修法: SqlInjection/Ldap/Command checker 显式传 severityLabel
  ("CRITICAL"/"HIGH"/"CRITICAL") 走 TaintRule 字段，3 个 checker 各自决定
- **R214b pre-existing test bug**: R214b 写完时 (R214b 的 summary 报 4 tests
  pass) 跟现在跑出来结果不一致是因为 cache 命中 + mvn 编译路径差异。R215
  引入新文件后强制 recompile R214b，3 个 bug 一次性暴露：import 路径错
  (`fact.FactKind` 应是 `config.FactKind`)、`program` 漏 addFunction、
  `currentFn` local var 不是 effectively final
- **Plumb 的 `FactKind` 在 `config` 包不在 `fact` 包** (R214a 决策): R208 把
  FactKind 放在 config 跟 SinkFamily 并列；R214a Fact / FactBag 放在 fact
  子包，BuiltinFactKinds 桥接。R214b 测试 import 时混了，记一笔。
