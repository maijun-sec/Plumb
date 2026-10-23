# R218a — SolverZ3Bridge 接到 TaintedCheckerBase (false positive filter)

**Round**: R218a · **2026-08-01** · **状态**: ✅ DONE

---

## 1. 背景与设计目标

R218 ship `SolverZ3Bridge` interface + `LocalSolverBridge`
implementation + `Operation.SolverZ3Predicate` record. 但还没接到
TaintedCheckerBase — checker 还没用上 path-condition validation.

R218a 目标:
- TaintedCheckerBase 加可选 `solverBridge` 字段
- `checkSinkAtCall` 在 emit finding 前调 `bridge.check(pathCondition)`
- UNSAT → skip (false positive elimination)
- SAT / UNKNOWN → 正常 emit
- 加 hook `pathConditionForSink(...)` 让 subclass 提供 path
  condition (默认 null = 不 verify)
- 4 tests: no bridge, SAT bridge, UNSAT bridge, null pc

---

## 2. 实施清单

### TaintedCheckerBase.java (modified, +~80 行)

- **新字段** `protected SolverZ3Bridge solverBridge` (default null)
- **新方法** `setSolverBridge(SolverZ3Bridge bridge)` setter
- **新方法** `solverBridge()` accessor
- **新方法 (subclass hook)** `pathConditionForSink(arg, family, call, ctx)`
  — 默认返 `null` (skip verify)
- **wire in checkSinkAtCall**: 在 emit finding 前 if
  `solverBridge != null && pathCondition != null && !pc.isEmpty()`:
  - `result = bridge.check(pc, Map.of())`
  - `if (result == UNSAT) return;` (suppress)
  - SAT / UNKNOWN: fall through to emit

### R218aZ3BridgeWireTest.java (new, 4 tests, ~12KB)

- `noBridge_defaultBehavior_firesFinding` — 没 bridge = R215 行为
- `bridgeReturnsSat_firesFinding` — bridge 返 SAT = 正常 emit
- `bridgeReturnsUnsAt_suppressesFinding` — bridge 返 UNSAT = 0
  findings (false positive 消除)
- `nullPathCondition_skipsBridgeCheck` — `pathConditionForSink` 返
  null (默认), bridge 不被调 (即使配了) — finding 正常 emit

每个 test 用 `ConstPathConditionChecker` (anonymous TaintedCheckerBase
subclass with hard-coded path condition) 来驱动 bridge 验证。

---

## 3. 5 大设计创新点

1. **Bridge 作为 strict false-positive filter, 永不是 false-negative**
   (R218a design). `pathConditionForSink` 返 null → bridge 不被调
   → 正常 emit. 只在 subclass 显式提供 path condition 时才 verify.
   这样 R215/R216 现有 mirror (Sql/Ldap/Command/Div/Int/Null/Sens) 不
   override 任何方法, 行为完全不变. UNSAT 只在 bridge **主动** 判定
   path 不可达时 suppress. 这是 over-approximation 安全选择.

2. **`solverBridge` 是 per-checker field, 不是 per-engine global**
   (R218a design). 让 SqlInjectionChecker / LdapInjectionChecker 等
   各自配 bridge. 不同 rule 用不同 bridge impl (R218a 后 polish
   可以让 YAML 配置 engine-global bridge). 当前 design 简化: setter
   method 直接 set.

3. **Path condition 字符串用 LocalSolver 同样的 DSL** (R218a design).
   Subclass 写 `"input != null && !input.isEmpty()"`, 直接进
   `LocalSolverBridge.check()`. 跟 R218 ship 的 bridge 完全一致 —
   不需要新格式. Z3 native (R218b future) 也是同样 DSL.

4. **Hook 是 default-null 模式** (R218a design). `pathConditionForSink`
   默认返 null = "我不知道 path condition, 不 verify". 跟 R215 已有
   的 `sinkArgumentIndex` / `findingMessage` / `isSourceFact` 钩子
   模式一致. Subclass 显式 override 才参与. 这让 R218a 不需要
   改任何 R215/R216 现有 mirror.

5. **Test 用 anonymous TaintedCheckerBase subclass** (R218a test
   design). 因为 `pathConditionForSink` 是 protected, foreign
   package 不能直接 mock. Test 在同一 package 写一个
   `ConstPathConditionChecker` 测试不同 pc 字符串. 4 个 test
   覆盖 4 种场景 (no bridge / SAT / UNSAT / null pc).

---

## 4. 测试结果

```
[INFO] Tests run: 162, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Module | R218a new | Cumulative |
|---|---|---|
| plumb-tac | 0 | 24 |
| plumb-dfa | 4 (R218a) | 162 (+4) |
| **Total new** | **4** | **+4 from R218 baseline** |

- 0 回归 (R211 5 + R213 5 + R214b 4 + R215 17 + R216 9 + R217 7 +
  R217c 6 + R218 12 + R218a 4 = 69 framework tests pass)
- 4 R218a tests pass
- 备份: `D:\work\tmp\r218a_done/` (2 files, ~15KB)
- doc: `docs/R218a-Z3-BRIDGE-WIRE.md` (this file, ~5KB)

---

## 5. 接下来

- **R218b (~0.5 day)**: 真正的 Z3 native bridge — wrap
  `com.microsoft.z3.Context` (z3-turnkey 已在 classpath). 跟
  LocalSolverBridge 同样的 DSL parser.
- **R218c (~0.5 day)**: 跨 function path-condition
  propagation — Operation.SolverZ3Predicate 通过 R213 framework
  传到 caller, caller 跟自己的 pc 合并后再 bridge 验证.
- **R219 (~1 day)**: end-to-end 验证 — Juliet 46,793 文件跑
  plumb-dfa (R213/215/216/217/217c/218/218a framework) 对比
  plumb-dfa legacy. 用户的"③ 正向汇总验证" 终极价值 — FP 率
  下降.

---

## 6. R218a 期间踩坑

- **No major pitfalls** — R218a 是 R218 的 polish step, R218 阶段
  已踩过 UNBOUND / div-by-zero 等坑. R218a 主要 wire up
  existing components.
- **Subclass hook 模式 (default null) 选择** (R218a design
  choice). 考虑过 "bridge 是 null 时也调 pathConditionForSink 拿
  null 然后 fallback". 但更 clean: bridge == null OR pc == null
  → skip verify. 简化 caller logic, 减少 branches.
