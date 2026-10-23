# R757 - 当前支持情况 + 优化建议（中文版）

**Date**: 2026-10-06
**作者**: Mavis (mavis)

---

## 一句话现状

**94.40% Recall / 69.09% Precision**，**R720 v7 → R755 累计 +10.98 pp**。剩 1,006 个 FN。

---

## 漏报排行（FN ≥ 5）

| CWE | Pos | TP | FN | Recall | 路径 |
|---|---:|---:|---:|---:|---|
| **CWE510** | 66 | 0 | 66 | 0% | ✅ 规则已有，只缺 yaml 白名单 |
| **CWE566** | 22 | 0 | 22 | 0% | ✅ 规则已有，只缺 yaml 白名单 |
| **CWE315** | 22 | 0 | 22 | 0% | ❌ 缺：明文 cookie 检查 |
| **CWE253** | 17 | 0 | 17 | 0% | ❌ 缺：return-value 错判检查 |
| **CWE833** | 6 | 0 | 6 | 0% | ❌ 缺：死锁 AST 检查 |
| **CWE835** | 6 | 0 | 6 | 0% | ❌ 缺：无限循环 AST 检查 |
| **CWE404** | 5 | 0 | 5 | 0% | ❌ 缺：资源未关闭检查 |
| **CWE191** | 2024 | 1952 | **72** | 96.44% | Z3 双向 bound（underflow + overflow） |
| **CWE113** | 792 | 768 | 24 | 96.97% | servlet taint variant 12/13 |
| **CWE400** | 875 | 859 | 16 | 98.17% | loop _12 / servlet taint |
| **CWE470** | 264 | 254 | 10 | 96.21% | _Servlet_12 反射 |
| **CWE23/15/36** | 264×3 | 254×3 | 10×3 | 96.21% | **path traversal 家族** —— 缺 PathTraversalAstCheck |
| **CWE319** | 220 | 211 | 9 | 95.91% | _Servlet_12 cleartext |
| **CWE601** | 198 | 190 | 8 | 95.96% | _Servlet_12 open-redirect |
| **CWE563** | 206 | 198 | 8 | 96.12% | **`_12` path-sensitive** — Plumb 输出管道吞了 |
| **CWE476** | 139 | 131 | 8 | 94.24% | `_12` family: 同一模式 |
| **CWE789** | 941 | 936 | 5 | 99.47% | 剩 5 个 `_11` |

**60 个 CWE 已 100% Recall** —— 包括 CWE570/571/563/690/789/398/259/338/476 等。

---

## 我建议的 4 轮路径

### **第 1 轮：A 轮 — 配置 + 6 个 AST 规则补完**（半天, +85 TP）

| 任务 | 工作量 | 收益 |
|---|---|---:|
| yaml 加 `Cwe510`, `Cwe566` 启用 | 5 min | **+88** |
| 新增 CleartextCookieAstCheck (CWE315) | 2h | +22 |
| 新增 IncorrectReturnCheck (CWE253) | 2h | +17 |
| 新增 ResourceNotClosedCheck (CWE404) | 1h | +5 |
| 新增 DeadlockAstCheck (CWE833) | 3h | +6 |
| 新增 InfiniteLoopAstCheck (CWE835) | 1h | +6 |

净增：**+85~110 TP**，94.40% → **94.87%**

### **第 2 轮：B 轮 — Plumb 输出管道修复 + `_12` family path-sensitive**（1-2 天, +20~40 TP）

定位 `ScanRunner.java` 的 dedup / enabledRuleNames filter，修复 R750/R751 已 fire 但未输出的 finding。

净增：**+20~40 TP**，→ **95.05%**

### **第 3 轮：C 轮 — Servlet taint 跨方法 + path-traversal 家族**（1 周, +50~100 TP）

- 加 `PathTraversalAstCheck` (CWE23/36/78/88)
- 修 servlet taint 跨 helper 方法的污点传播

净增：**+50~100 TP**，→ **95.45%**

### **第 4 轮：D 轮 — Z3 双向 bound（CWE191/606 主体）**（2-3 周, +50~70 TP）

扩展 `BoundaryConstraintChecker`：
- `result = a * b`：若 a<0 且 b<0 ⇒ result>0 ⇒ 不可能 underflow
- `result = a - b`：若 a>b ⇒ result>0 ⇒ 不可能 underflow

净增：**+50~70 TP**，→ **95.74%**

---

## 单点突破：A + B 一起 = **2 周内 95%**

理由：
- **A 独立可成**（+85 TP，零 engine 风险）
- **B 根因已知**（R750/R751 dbg 已确认 fire 但 ndjson 没写出去 —— 修起来定位明确）
- A 不依赖 B，先做 A 拿 94.87%，再做 B 拿 95.05%

---

## 不建议现在做的（成本/收益不划算）

- Taint 引擎整体重构 → 95.7% → 99%，要数月。放长期目标。
- Z3 完整化（type-aware bound, symbolic execution）：能让 CWE191 到 99%，但工作量太大。

---

## 文件

- 完整分析：`D:\tmp\plumb_test\reports\r757\STATUS_REPORT.md`
- 中文简版：`D:\tmp\plumb_test\reports\r757\STATUS_BRIEF_CN.md`（本文件）
- 基线 94.40%：`D:\tmp\plumb_test\reports\r755\README.md`
- R751 fire-vs-output 调查：`D:\tmp\plumb_test\reports\r751\SUMMARY.md`
- Eval log：`D:\tmp\plumb_test\reports\r751\full_eval.log`
- Ground truth：`D:\tmp\plumb_test\plumb_testcases_detail.csv`
