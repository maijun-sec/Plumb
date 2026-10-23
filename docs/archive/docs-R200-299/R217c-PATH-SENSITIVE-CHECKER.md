# R217c — PathSensitiveCheckerBase + ResourceLeakChecker (CWE-404)

**Round**: R217c · **2026-08-01** · **状态**: ✅ DONE

---

## 1. 背景与设计目标

R213/R215 落地 `TaintedCheckerBase`（"taint 流向 sink"模式）。R216 验证 4
domain × 3 sink-form 组合。但 **CWE-404/401 Resource Leak** 不属于
"taint 流向 sink"——它属于 **"tainted but no sink"**：分配的资源没
关闭就出 function → leak。

R217c 落地一个平行的 base class `PathSensitiveCheckerBase`，服务
"path-sensitive"（alloc + close 配对）类型的 DFA 规则。ResourceLeak
是第一个 mirror。

---

## 2. 实施清单

### plumb-dfa/.../taint/PathSensitiveCheckerBase.java (new, ~13KB)

- **抽象类**继承 `DfaChecker`（R211）：
  - `allocSites: Map<TacVar, TacInstruction>` — forward 收集的
    分配点
  - `closeSites: Map<TacVar, List<TacInstruction>>` — forward 收
    集的 close 点（支持多次 close）
  - `passedOut: List<TacVar>` — 跨函数传递的 alloc（通过 RETURN）
  - `initFunction` — 清空三个 map（per-function 隔离）
  - `checkInstruction` 4 大分支：
    1. **forward alloc**: `isAllocSite(ins)` + `dest` 是 TacVar
       → `allocSites.put`
    2. **forward close**: `isCloseCall(ins)` →
       `recordCloseIfMatches` → `closeSites.put`
    3. **cross-function alloc**: CALL dest 是 TacVar, callee
       summary `returnOps` 含 `TaintPropagate` → 标记 alloc
       （callee return alloc → caller 继承 close 义务）
    4. **forward pass-out**: RETURN lhs1 是 TacVar →
       `passedOut.add`（该 alloc 传到 caller）
  - **子类钩子** 4 个:
    - `isAllocSite(ins)` — 抽象
    - `isCloseCall(ins)` — 默认 `callee.contains("#close")`
    - `isAllocReturnOp(op)` — 默认任何 `TaintPropagate`
    - `closeArgumentIndex(ins)` — 默认 0
  - `summarizeFunction` — 写 summary:
    - 对每个 close 过的 param → `Operation.PassThrough`
    - 对每个 passed-out alloc → `Operation.TaintPropagate`
      (returnOps) — caller 继承
  - `emitLeakFinding` — 共享 finding 模板

### plumb-dfa/.../taint/checker/ResourceLeakChecker.java (new, ~4.4KB)

- **CWE-404, HIGH, POINTER domain**
- `isAllocSite` — NEW/NEWARRAY + 16 个资源 ctor 名字 (FileInputStream,
  FileOutputStream, FileReader, FileWriter, BufferedReader, BufferedWriter,
  Scanner, Socket, ServerSocket, Connection, Statement, PreparedStatement,
  ResultSet, InputStream, OutputStream)
- `summarizeFunction` — 三个条件都不满足的 alloc emit:
  1. `closeSites.containsKey` (本地 close)
  2. `passedOut.contains` (传给 caller)
  3. emit leak
- 走 `Operation.TaintPropagate` (returnOps) 让 caller 知道有
  dangling alloc

### plumb-dfa/src/test/.../taint/checker/R217cResourceLeakTest.java (new, 6 tests, ~9KB)

- `allocWithoutClose_firesCwe404` — `new FileInputStream()` 没 close
- `allocWithClose_doesNotFire` — `new FileInputStream(); close()`
- `allocPassedOutToCaller_carriesSummaryButDoesNotFireLocally` —
  return 不 fire（callee 端）但 summary 携带 obligation
- `multipleAllocs_someClosed_someLeaked` — 2 个 alloc, 1 close, 1
  leak finding
- `isAllocSite_recognisesResourceCtors` — Connection/Statement 等
- `checkerNameIsRuleId` — name = ruleId

---

## 3. 5 大设计创新点

1. **PathSensitiveCheckerBase 是 R215 TaintedCheckerBase 的平行类**
   (R217c design). 跟"flow-to-sink"模式 (`TaintedCheckerBase`)
   平行的 "taint-without-sink" 模式 (`PathSensitiveCheckerBase`).
   两个 base class 都 ~200 行, 各服务不同 DFA 模型. 不混在一起
   避免 kitchen-sink effect.

2. **Cross-function alloc 通过 returnOps TaintPropagate 传播** (R217c
   design). `Lib#open()` 返回 `Connection`, 写到 summary 的
   `returnOps` (TaintPropagate). caller 端 `checkInstruction` 看到
   CALL → 查 summary → 看到 TaintPropagate → 把 caller dest
   标 allocSites. 这是 R213/R215/R217 框架的"summary bridge"
   通用模式的 path-sensitive 应用.

3. **`isCloseCall` 用 `#close` 不用 `.close`** (R217c pitfall fix).
   IR 的 calleeSig 是 `Class#method` 形式, 不是 `Class.method`.
   我最初写 `.contains(".close")` 时匹配失败 — 因为 calleeSig
   实际是 `"java.io.FileInputStream#close"`, `#` 在 `close` 前面,
   不包含 `.close`. 修法: `.contains("#close")`. 这是 IR
   约定相关的实现细节, 跟 plumb-tac 的 TAC 编码相关.

4. **path-sensitive 检测规则** (R217c design). 一个 alloc 是
   leak 当且仅当:
   - 没在本地 close (`closeSites.containsKey(allocVar)`)
   - 没传给 caller (`passedOut.contains(allocVar)`)
   第三个条件 implicit (callee 自己的 `summarizeFunction` 只看
   local close + pass-out, 不知道 caller 是否会 close — 那是
   caller 端 check 的事). 这是 sound 但 incomplete: 如果
   caller 把 received alloc 又传下去给另一个 caller, R217c
   链式通过 `TaintPropagate` 传递. R218 配合 Z3 可以验证
   path condition satisfiable (例如 close 在 conditional
   branch 里, R217c 现在的简化是 "any close counts", 优化
   留给 R218).

5. **3 个并行模板 (R215 + R217c + R213) 证明 R213 framework
   通用** (R217c design). R213 framework 现在支持:
   - **flow-to-sink** (R215): TaintCheckerBase, 3 R215 mirror
     + 4 R216 mirror
   - **taint-without-sink** (R217c): PathSensitiveCheckerBase,
     1 R217c mirror (ResourceLeak)
   - **summary-bridge** (R213): `summarizeFunction` 写
     `Operation.*` 到 cache, caller-side 读
   R218 + future 会再加:
   - **path-condition validation** (R218): Z3 forward validation
   - **cross-function deallocator** (R217d): @Cleanup 模式
   - 任何 DFA 规则只要落入这几种模式就能复用 framework.

---

## 4. 测试结果

```
[INFO] Tests run: 146, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Module | R217c new | Cumulative |
|---|---|---|
| plumb-tac | 0 | 24 |
| plumb-dfa | 6 (R217c) | 146 (+6) |
| **Total new** | **6** | **+6 from R217 baseline** |

- 0 回归 (R211 5 + R213 5 + R214b 4 + R215 17 + R216 9 + R217 7 +
  R217c 6 = 53 framework tests pass)
- 6 R217c tests pass (alloc/close/passedOut/multi-alloc/ctor-name/
  ruleId)
- 备份: `D:\work\tmp\r217c_done/` (3 files, ~26KB)
- doc: `docs/R217c-PATH-SENSITIVE-CHECKER.md` (this file, ~9KB)

---

## 5. R218 接下来 (~1.5 day)

- `Operation.SolverZ3Predicate` (新 record subtype) — 携带
  z3 predicate 字符串 + 关联的 path context
- `SolverZ3Bridge.java` (新 sub-package `solver/`) — Z3 调用
  wrapper, plumb-dfa 已经有 z3-turnkey 依赖
- `TaintedCheckerBase` 扩展 — summary 阶段可选调 Z3 验证
  source → var → sink 路径上的 path condition satisfiable
- R218 测试 3-5 个 (path condition 验证正向 false positive 消除)

---

## 6. R217c 期间踩坑

- **`callee.contains(".close")` 不匹配** (R217c pitfall): IR 用
  `Class#method` 形式, 不是 `Class.method`. calleeSig
  `"java.io.FileInputStream#close"` 不包含 `.close` 子串（#在
  close 前面，没有 "." 在 close 前面）。修法:
  `callee.contains("#close")`. 这是 R217c 实际 debug 后
  才发现的, R217c 的 6 tests 一开始 fail 1 个 allocWithClose.
- **`isAllocSite` 也含 ".close" 字符串** (R217c pitfall): ResourceLeak
  `isAllocSite` 检查 `callee.contains("FileInputStream")`,
  close call 的 callee 也含 "FileInputStream"（"FileInputStream#close"），
  所以 close call 被误判为 alloc. 修法: 改用 `"#close"` 后
  正常，但 R218 还要进一步加 "alloc 不带 #close" 的 sanity check.
- **TAC 跨函数: `passedOut` 通过 RETURN 传递** (R217c design
  choice). 简化：只支持单层 `RETURN lhs1` 是 TacVar. 复杂
  的 out-parameter 留 R218.
