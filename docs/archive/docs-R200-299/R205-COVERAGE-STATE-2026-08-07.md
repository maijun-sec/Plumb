# R205-D Comprehensive Coverage State — 2026-08-07

> **本报告目标**：回答"针对每个 CWE juliet 用例，正确用例有多少，覆盖了多少，没覆盖的是什么场景，错误用例有多少，误报了多少，误报的场景是什么"
> 
> 数据来源：所有 `.r205d.attributed.ndjson`（已 R200 attribution 归一到 entry point）+ CWE-134 `.r203.attributed.ndjson`（未 R205-D 重跑）+ CWE-193 `.r187.ndjson`（R187 baseline）。
> 工具：`smoke_juliet/r180_eval/coverage_all.cjs` + `coverage_all_full.cjs` + `coverage_by_source.cjs`

## 1. 总览

| CWE | bad | good | TP | FN | FP | TN | Recall | FP-Rate | 状态 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| CWE23_Relative_Path_Traversal | 456 | 16 | 354 | 102 | 0 | 16 | 77.6% | 0.0% | ✓ 主力 CWE |
| CWE36_Absolute_Path_Traversal | 456 | 16 | 354 | 102 | 0 | 16 | 77.6% | 0.0% | ✓ 主力 CWE |
| CWE78_OS_Command_Injection    | 456 | 16 | 354 | 102 | 0 | 16 | 77.6% | 0.0% | ✓ 主力 CWE |
| CWE80_XSS                      | 684 | 28 | 551 | 133 | 0 | 28 | 80.6% | 0.0% | ✓ 主力 CWE |
| CWE81_XSS_Error_Message        | 342 | 13 | 279 | 63  | 0 | 13 | 81.6% | 0.0% | ✓ 主力 CWE |
| CWE83_XSS_Attribute            | 342 | 13 | 279 | 63  | 0 | 13 | 81.6% | 0.0% | ✓ 主力 CWE |
| CWE89_SQL_Injection            | 2280| 142 | 1790| 490 | 0 | 142| 78.5% | 0.0% | ✓ 主力 CWE |
| CWE90_LDAP_Injection           | 456 | 16 | 354 | 102 | 0 | 16 | 77.6% | 0.0% | ✓ 主力 CWE |
| CWE113_HTTP_Response_Splitting | 1368| 88 | 736 | 632 | 0 | 88 | 53.8% | 0.0% | ⚠ 偏低 |
| CWE134_Uncontrolled_Format_String | 684| 46 | 506 | 178 | 0 | 46 | 74.0% | 0.0% | ✓ R201 中游 |
| CWE252_Unchecked_Return_Value  | 17  | 4  | 1   | 16  | 0 | 4  | 5.9%  | 0.0% | ✗ checker 弱 |
| CWE369_Divide_by_Zero          | 1900 | 100 | 111 | 1789 | 0 | 100 | 5.8%  | 0.0% | ✗ 几乎全空 |
| CWE476_NULL_Pointer_Dereference| 202 | 12 | 4   | 198 | 0 | 12 | 2.0%  | 0.0% | ✗ 几乎全空 |
| CWE193_Off_by_One_Error        | 51  | 4  | 0   | 51  | 0 | 4  | 0.0%  | 0.0% | ✗ 全空 |
| **TOTAL** | **9694** | **514** | **5673** | **4021** | **0** | **514** | **58.5%** | **0.0%** | — |

**关键观察**：
- **FP 0%**：所有 CWE、所有 source、所有 sub-pattern，FP=0（R205-D 同 caller-class 消歧，没引入新 sink）
- **Recall 三档**：
  - 高（≥77%）：CWE-23/36/78/80/81/83/89/90 — 全部 R205-D 受益
  - 中（53.8%）：CWE-113 — addCookie sink 仍缺失约一半
  - 低（≤5.9%）：CWE-252/369/476/193 — checker 本身没建

## 2. R205-D 修复重点：跨 CWE `_21` constant if-true / private field dispatch

R205-D 修复的根因是 batch 模式下 `CallResolver.byName` 用 `name + "/" + arity` 作 key，导致多个 Juliet `_21` 文件的 `private void badSink(String)` 共享同一 list；merged walk 只 trace 第一个候选，caller 的 taint 错误地传到非 caller 类的 `badSink`。

`_21` 修复前/后对比（attributed ndjson）：

| CWE | _21 bad | R203 fires | R205-D fires | Δ | Recall |
|---|---:|---:|---:|---:|---:|
| CWE-23 _21 | 12 | 4 | 12 | +8 | 100% |
| CWE-36 _21 | 12 | 4 | 12 | +8 | 100% |
| CWE-78 _21 | 12 | 4 | 12 | +8 | 100% |
| CWE-80 _21 | 18 | 18 | 18 | 0  | 100% |
| CWE-81 _21 | 9  | 9  | 9  | 0  | 100% |
| CWE-83 _21 | 9  | 9  | 9  | 0  | 100% |
| CWE-89 _21 | 60 | 6  | 60 | +54 | 100% |
| CWE-90 _21 | 12 | 4 | 12 | +8 | 100% |
| CWE-113 _21 | 36 | 3 | 36 | +33 | 100% |
| CWE-134 _21 | 18 | 2 | 2  | 0  | 11% (未重跑) |
| **TOTAL _21** | **198** | **63** | **180** | **+117** | **91%** |

R205-D 修复了 117 个 _21 FN（9/10 CWE 100% 命中）。

## 3. 没覆盖什么场景（FN 系统性分析）

### 3.1 R205-D 后剩余的主要 FN buckets

| Flow | 含义 | 涉及 CWE | 单 CWE FN | 总 FN 估计 | 状态 |
|---|---|---|---:|---:|---|
| `42` | cross-class via return value (variant 2) | 9 | 1-2 | ~12 | 待 R205-F |
| `45` | cross-class via private field | 11 | 1 | ~11 | 待 R205-F |
| `68a` | deep nested call | 11 | 1 | ~11 | 待 R205-F |
| `72a/73a/74a/75a` | 各种 cross-class dispatch | 9-11 | 1 each | ~36 | 待 R205-F |
| `81a` | cross-class abstract dispatch | 11 | 1 | ~11 | 待 R205-F |
| `database_*` | SQL source 缺 catalog | 4 | 5-19 | ~70 | R203-A 失败，待 R37X |
| `PropertiesFile_*` | Properties source 缺 catalog | 4 | 5-19 | ~70 | R203-B 失败，待 R37X |
| `addCookieServlet` | CWE-113 sink 缺 catalog | 1 | 6-12 | ~456 (一半) | R203-C 失败，待 R37X |
| `CWE-476*` | 8 source | 1 | 198 | 198 | 需新 null-deref checker |
| `CWE-193*` | 3 loop kind | 1 | 51 | 51 | 需新 OOB checker |
| `CWE-369*` | 16 source | 1 | 581-1789 | 1789 | 需 chain fix |
| `CWE-252*` | 1 source × 17 flow | 1 | 16 | 16 | 需 batch 跨函数 fix |

**总可恢复 TP**（如果这些 0% source 全 cover）：约 **2,800 个 FN**。

### 3.2 R205-D 后 CWE-89 详细 FN

CWE-89 FN 490 中 60 个 _21 已修，仍剩 490。Top FN buckets：
- `_42` × 38 source: 38 FN
- `_45` × 38 source: 38 FN
- `_68a/72a/73a/74a/75a/81a` × 38 source each: 228 FN total
- `_22a/41` cross-class: ~12 FN
- `database_*` source (catalog 缺): 70 FN
- `PropertiesFile_*` source (catalog 缺): 70 FN

修复方向：R205-F（cross-class residual 12 FN）、R37X（catalog 缺 140 FN）。

### 3.3 R205-D 后 CWE-113 详细 FN

CWE-113 FN 632 中 36 个 _21 已修，仍剩 596。Top FN：
- `addCookie*` sink: ~456 FN（sink 完全缺）
- 其它 cross-class flow: ~140 FN

修复方向：R205-F（cross-class 140）、R37X（addCookie 456）。

## 4. 与 R203 对比

| CWE | R203+ TP | R205-D TP | Δ | 增量来源 |
|---|---:|---:|---:|---|
| CWE-23 / CWE-36 / CWE-78 / CWE-90 | 344 | 354 | +10 each (+40) | `_21` 全 fire (8 each) + 其他 cross-class 微调 |
| CWE-80 | 541 | 551 | +10 | `_41` 8 fires 新增（XSS Servlet 跨类） |
| CWE-81 / CWE-83 | 271 | 279 | +8 each (+16) | `_41` 8 fires 新增 |
| CWE-89 | 1701 | 1790 | +89 | `_21` +54 + `_41` +35 |
| CWE-113 | 698 | 736 | +38 | `_21` +33 + `_41` +5 |
| CWE-134 | 506 | 506 | 0 | 未重跑 |
| CWE-252 / CWE-369 / CWE-476 / CWE-193 | 116 | 116 | 0 | checker 不在 R205-D 范围 |
| **TOTAL** | **5480** | **5673** | **+193 (+3.5%)** | |

## 5. 工具与脚本

- `smoke_juliet/r180_eval/coverage_all.cjs` — 入口脚本，ROUND_PRIO 现在优先 `r205d`
- `smoke_juliet/r180_eval/coverage_all_full.cjs` — 完整 markdown 输出
- `smoke_juliet/r180_eval/coverage_by_source.cjs` — per-source 聚合
- `smoke_juliet/r180_eval/loadNdjsonLines` (在 coverage_all.cjs 内) — 优先 `.r205d.attributed.ndjson`，fall back 到 raw；sub-folder CWE 合并 s0N

## 6. 后续 R205-F / R37X 计划

- **R205-F (1d, +12~30 TP)**: 跨 CWE `_22a/41/42/45/68a/72a-75a/81a` 残留。R200 attribution 已大幅修复，但还有 entry 仍 FN 的情况。需要更深的 entry-point propagation。
- **R37X (multi-round)**:
  - R371 CWE-78 cross-TU b_mainSink
  - R372 CWE-78 `_42-_45` control flow
  - R373 CWE-78 Win32 `_w32_spawn*`
  - R374 CWE-78 C++ `std::string` / ifstream
  - R375 CWE-134 w32 va_start-as-sink
  - **R376 CWE-191 R152-style fallback for BinaryOp SUB family** (R366 跳过，CWE-191 仍 68% R)
- **R205-A (1.5d, +150-198 TP)**: CWE-476 null-deref checker（最大单 CWE gap）
- **R205-B (2d, +30-51 TP)**: CWE-193 off-by-one
- **R205-C (1.5d, +16 TP)**: CWE-252 batch 跨函数 fix
