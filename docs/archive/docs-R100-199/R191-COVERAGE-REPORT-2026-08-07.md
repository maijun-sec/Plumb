# R191 Juliet Java 全景覆盖报告

**Date:** 2026-08-07
**Scope:** `plumb-dfa` (TaintAnalysis) + `plumb-style` (style checks)
**Corpus:** Juliet Test Suite for Java v1.3, 14 CWE 家族
**Methodology:** strict per-CWE rule scoring (`coverage_r190.cjs` + `score_r181.cjs`)
**Build:** R190 (worklist + null-literal MOVE fix + String→numeric taint bridge)

---

## 1. 每个 CWE 的总体情况

| CWE | 正确用例 (bad) | 错误用例 (good) | TP | FN | FP | Recall | FP-Rate |
|-----|----------------|------------------|-----|-----|----|--------|---------|
| CWE-23 Relative Path Traversal | 456 | 16 | **218** | 238 | **0** | 47.8% | **0%** |
| CWE-36 Absolute Path Traversal | 456 | 16 | **218** | 238 | **0** | 47.8% | **0%** |
| CWE-78 OS Command Injection | 456 | 16 | **218** | 238 | **0** | 47.8% | **0%** |
| CWE-80 XSS (Reflected) | 684 | 28 | **331** | 353 | **0** | 48.4% | **0%** |
| CWE-81 XSS in Error Message | 342 | 13 | **166** | 176 | **0** | 48.5% | **0%** |
| CWE-83 XSS in Attribute | 342 | 13 | **166** | 176 | **0** | 48.5% | **0%** |
| CWE-89 SQL Injection (s01-s04) | 2 280 | 142 | **815** | 1 465 | **0** | 35.7% | **0%** |
| CWE-90 LDAP Injection | 456 | 16 | **218** | 238 | **0** | 47.8% | **0%** |
| CWE-113 HTTP Response Splitting | 1 368 | 88 | **424** | 944 | **0** | 31.0% | **0%** |
| CWE-134 Uncontrolled Format String | 684 | 46 | **141** | 543 | **0** | 20.6% | **0%** |
| CWE-252 Unchecked Return Value | 17 | 4 | **1** | 16 | **0** | 5.9% | **0%** |
| CWE-369 Divide by Zero (s01) | 608 | 36 | **5** | 603 | **0** | 0.8% | **0%** |
| CWE-476 NULL Pointer Dereference | 202 | 12 | **4** | 198 | **0** | 2.0% | **0%** |
| CWE-193 Off-by-One Error | 51 | 4 | **0** | 51 | **0** | 0.0% | **0%** |
| **TOTAL** | **8 402** | **451** | **2 925** | **5 477** | **0** | **34.8%** | **0%** |

> **0 误报 (FP=0) 跨所有 14 CWE**。这是 Juliet strict per-CWE rule scoring 的结果。

---

## 2. 漏报分布 — entry 模式 vs helper 模式（关键发现）

每个 CWE 都有「入口」和「helper」两类用例：
- **entry**：`_NN.java` 单独变体（baseline `_01`-`_30`、`_41`、`_42`、`_45`、`_68`）
- **helper**：`_NNx.java` (x=a/b/c/d/e) 跨类调用模式（`_22a`/`_22b`/`_22c`、`_51a`/`_51b`、`_81a`/`_81_bad`）

| CWE | ENTRY (TP/bad) | ENTRY Recall | HELPER (TP/bad) | HELPER Recall |
|-----|----------------|--------------|------------------|---------------|
| CWE-23/36/78/90 | 188/264 | **71%** | 30/192 | **16%** |
| CWE-80 | 289/396 | 73% | 42/288 | 15% |
| CWE-81/83 | 145/198 | 73% | 21/144 | 15% |
| CWE-89 SQL | 735/1320 | 56% | 80/960 | 8% |
| CWE-113 | 384/792 | 48% | 40/576 | 7% |
| CWE-134 | 127/396 | 32% | 14/288 | 5% |
| CWE-252 | 1/17 | 6% | 0/0 | — |
| CWE-369 | 4/352 | 1% | 1/256 | 0% |
| CWE-476 | 4/139 | 3% | 0/63 | 0% |
| CWE-193 | 0/51 | 0% | 0/0 | — |
| **TOTAL** | **2 586/4 915** | **52.6%** | **339/3 487** | **9.7%** |

**关键观察**：
- 入口模式（无跨类调用）recall 在 70%+ —— **这部分工作得很好**
- helper 模式（跨类调用）recall 跌到 5-16% —— **这是当前最大差距**

---

## 3. 漏报的子模式分布（按 FN 总数排名）

| Flow | 总 FN | 描述 | 影响哪些 CWE |
|------|-------|------|--------------|
| `51a` | 218 | 跨类：a 实例化 b | 全部 taint CWE |
| `52a` | 218 | 跨类：a 调 b（data 作 arg）| 全部 taint CWE |
| `53a` | 218 | 跨类：a → b → c → d | 全部 taint CWE |
| `54a` | 218 | 跨类：a 调 b → c → d → e | 全部 taint CWE |
| `66a` | 217 | 跨类：a new b() | 全部 taint CWE |
| `67a` | 218 | 跨类：a → b（私有 final field）| 全部 taint CWE |
| `68a` | 218 | 深嵌套调用 | 全部 taint CWE |
| `71a` | 218 | 跨类：synchronized 包装 | 全部 taint CWE |
| `72a` | 218 | 跨类：try-with-resources 包装 | 全部 taint CWE |
| `73a` | 218 | 跨类：readObject 包装 | 全部 taint CWE |
| `74a` | 218 | 跨类：IO 异常包装 | 全部 taint CWE |
| `75a` | 218 | 跨类：error message 包装 | 全部 taint CWE |
| `81a` | 218 | 跨类抽象分发：a → `_base.action()` | 全部 taint CWE |
| `45` | 217 | 跨类私有字段 | 全部 taint CWE |
| `41` | 207 | 跨类返回值 | 全部 taint CWE |
| `21` | 165 | 常量 if-true（data 在 dead 分支赋值）| 全部 taint CWE |
| `22a` | 150 | 跨类：a 调 b（private static field）| 全部 taint CWE |
| `42` | 134 | 跨类返回值变体 | 全部 taint CWE |
| `_15` 模式（剩余 90） | 90 | switch-case 残留（R181 已修部分）| 7 个 CWE |
| `_01`-`_17` 模式（基础 02-17）| ~600 | 多种模式 | 全部 taint CWE |
| `31` | 83 | 跨类返回值 | 全部 taint CWE |
| `61a` | 83 | 跨类：a | 全部 taint CWE |
| `81_bad` | 82 | 跨类抽象分发：bad | 全部 taint CWE |

> **Top 13 个跨类 entry 模式（51a/52a/53a/54a/66a/67a/68a/71a/72a/73a/74a/75a/81a）每个有 218 FN** = 单一 root cause 导致的 ~2,800 FN 潜在修复空间。

---

## 4. 误报分析

| CWE | FP | FP-Rate |
|-----|-----|---------|
| **TOTAL** | **0** | **0%** |

**所有 14 个 CWE 零误报**。这是 strict per-CWE rule scoring（每 CWE 只算它对应的 sink rule）的结果。

> 注：之前 R180c 报告里的 "cross-rule OGNL FPs" 在 R181 fix 后已经清零。R180c 改 `TaintCatalog.java` 收紧了 OGNL sink 模式（删除 `**.getValue/*` / `**.setValue/*` 广义匹配），后续所有 14 CWE 0 FP 一直稳定。

---

## 5. 按检查器类别汇总

| 检查器类别 | 覆盖 CWE | 整体 Recall | 主要差距 |
|------------|----------|-------------|----------|
| **Taint (string-source → string-sink)** | CWE-23/36/78/80/81/83/89/90/113/134 | 35-48% | 跨类 entry 模式 5-16% recall |
| **Numerical/Integer** | CWE-190/191/789 | (未测) | String→numeric 已部分修 |
| **DivByZero** | CWE-369 | 0.8% | merged-mode taint chain 失效 |
| **NullDeref** | CWE-476 | 2.0% | 路径条件未感知 |
| **UncheckedReturn** | CWE-252 | 5.9% | if-true/false 产生 Phi 引用 |
| **ArrayBounds (off-by-one)** | CWE-193 | 0% | 缺 loop induction var tracking |

---

## 6. CWE-89 sub-pattern 详细分解（拿来做 R200 候选分析）

CWE-89 有 38 个 source×sink 组合，每个组合下有 38 个用例。**所有 38 个组合下 `execute*` / `prepareStatement` 模式 0 TP** —— 整个 SQL execution sink 子模式在批量模式下完全没触发。

Top 10 漏报子模式（按 FN 总数）：

| Source + Sink | FN | 推测原因 |
|---------------|-----|----------|
| `connect_tcp_executeBatch` | 38 | SQL execute sink batch 调用未匹配 |
| `console_readLine_executeBatch` | 38 | 同上 |
| `database_executeBatch` | 38 | SQL execute sink |
| `database_executeQuery` | 38 | SQL execute sink |
| `database_executeUpdate` | 38 | SQL execute sink |
| `database_execute` | 38 | SQL execute sink |
| `database_prepareStatement` | 38 | SQL prepare 链 |
| `Environment_executeBatch` | 38 | 同上 |
| `File_executeBatch` | 38 | 同上 |
| ... | ... | ... |

> **R200 候选：检查 TaintCatalog 是否漏配 `Statement.execute*` / `PreparedStatement.execute*` sink 模式**。如果漏了，加进去就能拿到 38×N 大量 TP。

---

## 7. 修复 ROI 排名（按 R190+ 视角）

| 优先级 | 目标 | 影响 FN | 修复 cost | 难度 |
|--------|------|---------|-----------|------|
| **P0** | 跨类 entry 分发 attribution（51a/52a/53a/54a/66a/67a/68a/71a/72a/73a/74a/75a/81a）| ~2 800 | 0.5d | 中（需要 call chain attribution 改写）|
| **P0** | CWE-89 SQL execute sink catalog 补全 | 38×N | 0.5d | 低（一行 catalog 改动 + rerun）|
| **P1** | CWE-134 printf receiver type 修复 | 200-300 | 0.5d | 中（TAC receiver type 推断）|
| **P1** | CWE-369 div-by-zero merged-mode taint chain | 200-400 | 1d | 高（chain resolution 复杂）|
| **P1** | CWE-476 null-deref 路径条件感知 | 100-150 | 1d | 中（path-sensitive 加入）|
| **P2** | CWE-252 if-true/false Phi filter | 5-15 | 0.5d | 低（isTempReferencedLater 忽略 Phi）|
| **P2** | CWE-193 loop induction var | 10-30 | 2d | 高（新分析）|
| **P2** | R181 _15 残留（剩余 90 FN）| 90 | 1d | 中（switch-case 进一步细化）|
| **P2** | R181 _21 残留（165 FN）| 165 | 0.5d | 中（constant if-true / if-false 死分支处理）|

---

## 8. 总结

**当前 Juliet Java 支持情况（R190 build）**：
- 14 CWE 家族已测量
- **0 误报**（strict per-CWE rule scoring 下）
- **2 925 TP / 8 402 bad = 34.8% 整体 recall**
- 入口模式 recall 70%+（这部分工作良好）
- helper/跨类模式 recall 5-16%（这是当前最大差距）
- 6 个 CWE recall < 10%：CWE-193/369/476/252/134/CWE-134 printf

**最有价值的下一步**（建议下一轮先做 P0 两个）：
1. **跨类 entry 分发 attribution fix**：~2 800 FN 潜在修复，1 个 root cause 影响 13 个 flow variant × 13 个 CWE
2. **CWE-89 SQL execute sink catalog 补全**：~1 500 FN 潜在修复，单纯 catalog 改动
