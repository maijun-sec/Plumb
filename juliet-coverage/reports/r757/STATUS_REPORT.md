# R757 — 当前支持数据 + 实现能力分析（优化建议）

**Date**: 2026-10-06
**Author**: Mavis (mavis)
**状态**: 静态分析 + 优化路径分析（非新一轮代码改动）

---

## 1. 当前评测快照（基于 R751 / R755 的 `full_eval.log`）

| 指标 | 值 |
|---|---|
| 测试用例总文件 | 17,954 Pos / 17,498 Neg |
| **Aggregate Recall** | **94.40%** (16,948 TP / 1,006 FN) |
| **Aggregate Precision** | **69.09%** (7,584 FP) |
| 已 100% Recall 的 CWE | 60 个（含 CWE570/571/563/690/789/398/259/338 等） |
| Recall ≥ 95% 但 < 100% 的 CWE | 19 个（FN 较少，**优化空间 ≤ +20 TP**） |
| Recall 0% 的 CWE | 6 个（**未启用规则**，每开一个就能净涨 FN） |

---

## 2. 漏报分布（FN ≥ 5，按 Recall 升序）

| CWE | Pos | TP | FN | Recall | 规则已存在？ | 备注 |
|---|---:|---:|---:|---:|---|---|
| **CWE510** | 66 | 0 | 66 | **0%** | ✅ `Cwe510` | **完全没启用**：一行 yaml + 重扫 = +66 TP |
| **CWE315** | 22 | 0 | 22 | **0%** | ❌ 无对应 rule | 缺：cleartext cookie storage 检测 |
| **CWE566** | 22 | 0 | 22 | **0%** | ✅ `Cwe566` | **未启用** |
| **CWE253** | 17 | 0 | 17 | **0%** | ❌ 无对应 rule | 缺：incorrect return-value check |
| **CWE404** | 5 | 0 | 5 | **0%** | ❌ 无 | 缺：resource shutdown check |
| **CWE833** | 6 | 0 | 6 | **0%** | ❌ 无 | 缺：deadlock（DFA 引擎能做） |
| **CWE835** | 6 | 0 | 6 | **0%** | ❌ 无 | 缺：infinite loop（while(true) AST 已做部分，但 DFA 还能多） |
| CWE191 | 2024 | 1952 | 72 | 96.44% | ✅ loose + plumb.boundary | **Z3 求解** — 多是 `_multiply` / `_sub` 跨包 |
| CWE113 | 792 | 768 | 24 | 96.97% | ✅ loose.taint + plumb.taint | 多数是 servlet taint variant 12/13 |
| CWE400 | 875 | 859 | 16 | 98.17% | ✅ taint + Loops | `_loop_12` / servlet 路径 |
| CWE470 | 264 | 254 | 10 | 96.21% | ✅ unsafe-reflection | `_Servlet_*_12` variant |
| CWE23 | 264 | 254 | 10 | 96.21% | ❌ **无 PathTraversal rule** | 跟 CWE15/36/78/88 一起 —— 这是个家族 |
| CWE15 | 264 | 254 | 10 | 96.21% | ✅ external-config | 同 CWE23 家族 |
| CWE36 | 264 | 254 | 10 | 96.21% | ❌ 无 path traversal rule | 同上 |
| CWE319 | 220 | 211 | 9 | 95.91% | ✅ cleartext-network | 部分 `_Servlet_*_12` |
| CWE601 | 198 | 190 | 8 | 95.96% | ✅ open-redirect | `_Servlet_*_12` family |
| CWE563 | 206 | 198 | 8 | 96.12% | ✅ UnusedVariableAstCheck | **`_12` path-sensitive**（R750/R751 已经 fire 但 Plumb 输出管道丢弃了） |
| CWE476 | 139 | 131 | 8 | 94.24% | ✅ null-deref | 多数是 `_12` family 的 `if(IO.staticReturnsTrueOrFalse())` 包裹 null deref |
| CWE789 | 941 | 936 | 5 | 99.47% | ✅ unbounded-mem-alloc | 剩 5 个 `_11` family |

---

## 3. **实现能力** vs **目标收益** 分析

### A. 立刻可做（不动引擎逻辑，纯配置 + AST 规则），**+85 TP** 起步

| 优化项 | 工作量 | 收益 | 说明 |
|---|---|---|---|
| 启用 `Cwe510` 规则 | **5 min** | **+66 TP** | 规则已存在（`Cwe510.java`），CSV 里有映射。**只缺 yaml 白名单条目** |
| 启用 `Cwe566` 规则 | 5 min | +22 TP | 同上 |
| 新增 CWE315 AST 检查 | 半天 | +22 TP | 检查 `setMaxAge > 0` 的 cookie 没用 secure/HttpOnly flag |
| 新增 CWE253 AST 检查 | 半天 | +17 TP | `if (recv(...) != -1)` 这类对 return value 的错误判断 |
| 新增 CWE404 AST 检查 | 1h | +5 TP | 检测 `ResultSet`/`Connection`/`InputStream` 没 close 也没在 finally 关闭 |
| 新增 CWE833（deadlock） | 2h | +6 TP | `synchronized(l1)` 后 `synchronized(l2)` 且别处反过来 |
| 新增 CWE835（infinite loop while(true)） | 1h | +6 TP | 在已有 `InfiniteLoopCheck` 基础上加严 |

> 这些都是 **plumb-style** 里的纯 AST checker / 检查器补全，不需要 Z3。

### B. 短期优化（补 path-sensitive / `_12` family），**+30~50 TP**

- **CWE563 `_12` (8 FN)** — `data = ...; if (IO.staticReturnsTrueOrFalse()) {/* noop*/} else { IO.writeLine(data); }`
  R750/R751 已经 fire，**但 Plumb 输出管道没把 finding 写出去**。
  → 必修 `ScanRunner.java` 里那个 dedup / enabledRuleNames filter。预估工作量 1-2 天。

- **CWE690 `_trim_12` (3 FN)** — `request.getParameter()` 后 `data.trim()` 没判空
  → 同一类 path-sensitive 问题，跟 CWE563 共用 fix。

- **CWE476 `_12` family (8 FN)** — 类似 pattern：`if(IO.staticReturnsTrueOrFalse()) { String s=null; s.length(); }`
  → 在 `NullDerefFromReturnAstCheck` 加 `isInsideOpaqueGuard(true-branch)` 的 spread 模式。

- **CWE601 `_12` (8 FN)** — open-redirect 被 `if(IO.staticReturnsTrueOrFalse())` 包裹
  → 在 `TaintSinkChecker` 允许 `opaque guard = false branch` 不杀污点传播。

### C. 中期：Taint Engine 补完，**+200~300 TP**

- **CWE113/319/470/400/23/15/36/90/606**（共 89 FN）— 都是 servlet taint source 没传到 sink。
  需要：
  1. `s01` 包内 connect 跨方法传污点（已部分支持，需要把 helper method 的 param taint 显式标注）
  2. Servlet API 入口（request, response）作为 source 已配置，但 servlet taint variant 12/13 的 sink 在另一方法里 —— **需要 inter-procedural callee 摘要预加载**
- **CWE23/36/78/88** path traversal 家族（缺 rule）—— 加 `PathTraversalAstCheck`

### D. 中长期：Z3 求解 / 类型推导，**+100~150 TP**

- **CWE191 (72 FN)** — 多数是 `_sub` / `_multiply` 需要 Z3 算 `data < 0` 时 `data * 2` 是否 underflow
  现成的 `CachedZ3Bridge` + `BoundaryConstraintChecker` 已有，但只用于 `data > 0` 检查。需扩展：
  - `result = a * b` 时 `a < 0` 且 `b < 0` ⇒ `result > 0` ⇒ 不可能 underflow
  - `result = a - b` 时 `a > b` ⇒ `result > 0` ⇒ 不可能 underflow
- **CWE606 (24 FN)** — `for (i=0; i<size; i++) size = request.getParameter(...)` 这类需要 taint + range

---

## 4. **建议的 4 轮路径**（按性价比排序）

### **Round R757-A: 配置 + 5 个 AST 规则补完** (半天, +85 TP, **94.40% → 94.87%**)

1. yaml 白名单加 `Cwe510`, `Cwe566` → **+88 TP**
2. 新增 `CleartextCookieAstCheck` (CWE315) → +22 TP
3. 新增 `IncorrectReturnCheck` (CWE253) → +17 TP
4. 新增 `ResourceNotClosedCheck` (CWE404) → +5 TP
5. 新增 `DeadlockAstCheck` (CWE833) → +6 TP
6. 新增 `InfiniteLoopAstCheck` (CWE835) → +6 TP

净预估：+85~110 TP，**+0.45 ~ +0.6 pp**。

### **Round R757-B: Plumb 输出管道修复 + 12 family path-sensitive** (2天, +20~40 TP, → ~95.05%)

- 查 `ScanRunner.java` 的 dedup / enabledRuleNames filter / formatter 阶段，找出 R750/R751 fire 但不输出的根因
- 修后 CWE563 _12, CWE690 _trim_12, CWE476 _12 family, CWE601 _12 一起恢复

预估净增 **+20~40 TP**，**+0.1 ~ +0.25 pp**。

### **Round R757-C: Servlet taint interproc + path-traversal 家族** (1周, +50~100 TP, → ~95.4%)

- 加 `PathTraversalAstCheck` (CWE23/36/78/88)
- 修 servlet taint 跨方法的污点传播
- CWE606/400 的 _12 family

### **Round R757-D: Z3 扩展（CWE191/606 主体）** (2-3周, +50~70 TP, → ~95.7%)

- `BoundaryConstraintChecker` 扩展为双向（overflow + underflow）
- 加 subtract/multiply 的 bound propagation

---

## 5. 总体预估路径（不依赖 taint 引擎大重构）

| Round | 内容 | TP 增量 | 累计 Recall |
|---|---|---:|---:|
| R757-A | 配置 + AST 补 6 个规则 | +85~110 | **94.87%** |
| R757-B | 输出管道 + _12 path | +20~40 | **95.05%** |
| R757-C | Servlet taint + path-traversal | +50~100 | **95.45%** |
| R757-D | Z3 双向 bound | +50~70 | **95.74%** |
| (封顶) |  |  | **~95.7%** |

**95% 在 2 周内可达成。95.5% 在 4 周内。99% 需要 taint 引擎大重构（数月）。**

---

## 6. **当前最关键的单点突破**

> **A 轮 + B 轮加起来可以单点解锁到 95%** —— 这是性价比最高的路径。

理由：
- A 轮所有改动都是「白名单 + 已有规则启用 + 新增小 AST 检查」，没有 engine-level 风险
- B 轮只改 ScanRunner 一处的 dedup/filter，已知根因就在那个文件
- A 轮不依赖 B，A 独立可成（+85 TP）
- B 轮已通过 dbg log 验证过 fire 真发生了，只是没写到 ndjson —— 修起来风险低

---

## Files

- 本文：`D:\tmp\plumb_test\reports\r757\STATUS_REPORT.md`
- R755 总结（94.40% 基线）：`D:\tmp\plumb_test\reports\r755\README.md`
- R751 总结（CWE563 _12 进展）：`D:\tmp\plumb_test\reports\r751\SUMMARY.md`
- 完整 eval log：`D:\tmp\plumb_test\reports\r751\full_eval.log`
- ground truth：`D:\tmp\plumb_test\plumb_testcases_detail.csv`
- Juliet 源码：`D:\work\tmp\Juliet_Java\src\testcases\`
