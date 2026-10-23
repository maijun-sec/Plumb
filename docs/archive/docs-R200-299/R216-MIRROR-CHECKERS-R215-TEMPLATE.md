# R216 — 5 mirror checker 接 R215 TaintedCheckerBase 模板 (4/5)

**Round**: R216 · **2026-07-31** · **状态**: ✅ DONE (4/5, ResourceLeak 推 R216b)

---

## 1. 背景与设计目标

R215 落地了 `TaintedCheckerBase` 通用 DFA 模板 + 3 mirror (SQL/LDAP/Command)，
证明了 3 行写一个 taint 类规则。R216 的目标是把这个模板推到 **非 taint 类
的 DFA 规则**：
- CWE-369 Divide-by-zero (NUMERIC domain)
- CWE-190 Integer overflow (NUMERIC domain)
- CWE-476 Null dereference (POINTER domain)
- CWE-200 Sensitive data leak (MIXED domain)
- CWE-401/404 Resource leak (POINTER domain) — 推 R216b (path-sensitive)

每个 checker 通过选不同的 `TaintRule` 字段 (source kind, sink family, CWE,
domain) 跟 override `checkInstruction` 适配自己的 sink 识别模式 (BINOP DIV/MOD
对 DivByZero、FIELDLOAD/ARRAYLOAD 对 NullDeref、...)。R215 模板基本工作，
subclass 只补少量的"非 CALL 形态的 sink"逻辑。

---

## 2. 实施清单

### 4 new mirror checkers (~14KB)

- `DivByZeroChecker.java` (4107 bytes) — CWE-369, HIGH, NUMERIC.
  Override `checkInstruction` 识别 `BINOP DIV/MOD`：rhs 是 const 0 直接
  fire (literal-zero 触发)；rhs 是 tainted var (initFunction 标了) 也 fire。
  default arg position 0 (跟 R215 模板兼容)。
- `IntOverflowChecker.java` (4226 bytes) — CWE-190, HIGH, NUMERIC.
  Override `checkInstruction` 识别 `BINOP ADD/SUB/MUL` on INT/LONG：dest
  自动标 overflow-hazard (implicit source)，后续 arithmetic on dest 触发
  链式 fire (e.g. `a = MAX; b = a + 1; c = b * 2` 在 `b * 2` 触发)。
- `NullDerefChecker.java` (3800 bytes) — CWE-476, HIGH, POINTER.
  Override `checkInstruction` 识别 `FIELDLOAD/FIELDSTORE/ARRAYLOAD/ARRAYSTORE`：
  receiver (lhs1) 是 may-be-null 时 fire。initFunction 把所有 params 标
  tainted 提供 source。
- `SensitiveDataLeakChecker.java` (1770 bytes) — CWE-200, HIGH, MIXED.
  **零 override** — 完全靠 R215 模板默认 CALL sink check。这是 R215 模板
  最干净的 mirror，证明模板对 taint 风格的 pure 规则零成本 work。

### 1 综合 test class (9 tests, ~17KB)

- `R215MirrorCheckersTest.java`:
  - `divByZero_literalZero_firesCwe369` — `x / 0` literal 触发
  - `divByZero_taintedDivisor_firesCwe369` — tainted divisor 触发
  - `divByZero_safeDivision_noFire` — `x / 5` 不触发
  - `intOverflow_addOnIntLiftedParam_firesCwe190` — chain `a = n + 1; b = a * 2`
  - `intOverflow_noChainArith_noFire` — 单次 `1 + 2` 不触发
  - `nullDeref_fieldLoadOnNullParam_firesCwe476` — `obj.field` on null param
  - `nullDeref_arrayLoadOnNullHandle_firesCwe476` — `arr[0]` on null param
  - `sensitiveDataLeak_paramToLogSink_firesCwe200` — token → Logger#info
  - `sensitiveDataLeak_untaintedToLog_noFire` — 干净的字面量 → Logger#info

---

## 3. R215 模板稳定性评估 (R216 验证结果)

| Checker | Domain | Source kind | Sink family | override 复杂度 |
|---|---|---|---|---|
| SqlInjection (R215) | STRING | UNTRUSTED_SOURCE | sql.sinks | 0 lines (pure CALL) |
| LdapInjection (R215) | STRING | UNTRUSTED_SOURCE | ldap.sinks | 0 lines |
| CommandInjection (R215) | STRING | UNTRUSTED_SOURCE | command.sinks | 0 lines |
| **DivByZero (R216)** | NUMERIC | MAY_BE_ZERO | numeric.divide.sinks | 35 lines (BINOP DIV/MOD + literal-zero) |
| **IntOverflow (R216)** | NUMERIC | NUMERIC_OVERFLOW_HAZARD | numeric.binop-arith.sinks | 38 lines (BINOP ADD/SUB/MUL + chain) |
| **NullDeref (R216)** | POINTER | MAY_BE_NULL | pointer.deref.sinks | 38 lines (FIELD/ARRAY LOAD/STORE) |
| **SensitiveDataLeak (R216)** | MIXED | SENSITIVE_DATA | sensitive.exfil.sinks | **0 lines (pure CALL)** ✓ |

**关键观察**:
1. R215 模板对 **pure CALL 形态** 的规则完全 zero-cost work (3 of 7)
2. 对 **BINOP 形态** 的规则 (DivByZero, IntOverflow) 需要 override 30-40 行
   来识别 non-CALL sink + 引入 implicit source
3. 对 **LOAD/STORE 形态** 的规则 (NullDeref) 需要 override 30-40 行识别
   non-CALL sink
4. **总计 override 代码 ~110 行** — 7 个 checker 净增 0 行配置, ~110 行
   子类特定代码 (3 个 R215 + 4 个 R216). R215 模板的 ROI 很高.

---

## 4. 模板设计结论 (R216 验证)

R215 模板的 `TaintedCheckerBase` 设计 **通过 4 种 domain (STRING/NUMERIC/POINTER/MIXED) × 3 种 sink 形态 (CALL/BINOP/LOAD-STORE) 的组合验证**。

**R215 模板的 3 个 subclass 钩子 (R215 设计) 全部按预期工作**:
- `sinkArgumentIndex(family, call)` — 0 override 即可 (R215 default 0)
- `findingMessage(...)` — 0 override 即可 (R215 default 含 CWE + callee)
- `isSourceFact(fact)` — 0 override 即可 (R215 default 按 rule.sourceKindName)

**R215 模板的 1 个限制** (R216 暴露):
- `checkInstruction` 只能在 CALL 上做 sink check, BINOP/LOAD/STORE 上需要
  subclass override. 修法: 把 R215 的 `checkInstruction` 改成 template method
  pattern, 加一个 `protected void onOtherInstruction(ins, fn, ctx) {}` 钩子
  让子类注册 non-CALL sink. 这是 R216a 的 polish 项 (低优先级, 现在每个
  checker 自己 override checkInstruction 调 super + 自己的逻辑也清楚).

---

## 5. 测试结果

```
[INFO] Tests run: 133, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Module | R216 new | Cumulative |
|---|---|---|
| plumb-tac | 0 | 24 |
| plumb-dfa | 9 (R216) | 133 (+9) |
| **Total new** | **9** | **+9 from R215 baseline** |

- `R215MirrorCheckersTest`: 9 tests (4 mirror × 2-3 tests each)
- 0 回归
- 备份: `D:\work\tmp\r216_done/` (5 files, ~31KB)
- doc: `docs/R216-MIRROR-CHECKERS-R215-TEMPLATE.md` (this file, ~7KB)

---

## 6. R217 + R216b 路径

- **R216b (~1 day)**: ResourceLeakChecker 走新 base class
  (`PathSensitiveCheckerBase`) — alloc fact 追踪 + 每条 path 检查 close。
  跟 R215 模板不同: 不是"tainted flow to sink", 是"tainted + sink absent"。
  R215 模板硬 fit 不优雅，独立 base class 是正确选择。

- **R217 (~3 day)**: 并行分析框架 (用户新指令):
  ① CallGraph 拓扑序并行 (callee 完成 → caller 立即可分析)
  ② 函数内: BB-RPO + BB 内 insn 逆序 (摘要分析)
  ③ 正向汇总 (Z3 路径约束验证缺陷是否真存在)
  ①②③ 全部可并行。FunctionSummaryCache 改 `ConcurrentMap`，DfaEngine
  改造为 worklist algorithm。

- **R218 (~1.5 day)**: Z3 + summary 集成 — `Operation.SolverZ3Predicate` 让
  callee summary 携带谓词；TacToZ3 port (Fathom ~600 LoC). Z3 optional +
  LocalSolver fallback.

---

## 7. R216 期间踩坑

- **TacType.OBJECT 不存在** (R216 test bug): `TacType` 只暴露原始类型
  + `STRING/CLASS/ARRAY/NULL/VOID/UNKNOWN`. 测 NullDeref 时用 `TacType.OBJECT`
  编译错。修法: 用 `TacType.UNKNOWN` (type 未知是 may-be-null 的合理来源).
- **javac ternary 解析歧义** (R216 DivByZero): `? taintedVars.get(v) : "const"`
  触发了 javac release 17 的类型推断问题。修法: 拆成 if-else。
- **BinaryOperator 是 top-level enum** (不是 `TacInstruction.BinopKind`):
  R216 initial 写成 `TacInstruction.BinopKind.DIV` 编译错。修法: import
  `org.plumb.tac.ir.BinaryOperator` 用 `BinaryOperator.DIV/MOD/ADD/...`。
- **R215 模板的 checkInstruction 钩子设计**: R216 暴露需要 `onOtherInstruction`
  钩子让 non-CALL sink 不需要 override 整个 checkInstruction。这是 R216a
  polish 项。
