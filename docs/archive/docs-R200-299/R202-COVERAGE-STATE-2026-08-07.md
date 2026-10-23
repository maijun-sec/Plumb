# R202 Comprehensive Coverage State — 2026-08-07

> **本报告目标**：回答"针对每个 CWE juliet 用例，正确用例有多少，覆盖了多少，没覆盖的是什么场景，错误用例有多少，误报了多少，误报的场景是什么"
> 
> 数据来源：所有 `.r200.ndjson`（已 R200 attribution 归一到 entry point）+ CWE-134 `.r201.attributed.ndjson`（R201 printf fix）+ CWE-193 `.r187.ndjson`（R187 baseline）。
> 工具：`smoke_juliet/r180_eval/coverage_all.cjs` + `coverage_all_full.cjs` + `coverage_by_source.cjs`

## 1. 总览

| CWE | bad | good | TP | FN | FP | TN | Recall | FP-Rate | 状态 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| CWE23_Relative_Path_Traversal | 456 | 16 | 288 | 168 | 0 | 16 | 63.2% | 0.0% | ✓ 主力 CWE |
| CWE36_Absolute_Path_Traversal | 456 | 16 | 288 | 168 | 0 | 16 | 63.2% | 0.0% | ✓ 主力 CWE |
| CWE78_OS_Command_Injection    | 456 | 16 | 288 | 168 | 0 | 16 | 63.2% | 0.0% | ✓ 主力 CWE |
| CWE80_XSS                      | 684 | 28 | 429 | 255 | 0 | 28 | 62.7% | 0.0% | ✓ 主力 CWE |
| CWE81_XSS_Error_Message        | 342 | 13 | 215 | 127 | 0 | 13 | 62.9% | 0.0% | ✓ 主力 CWE |
| CWE83_XSS_Attribute            | 342 | 13 | 215 | 127 | 0 | 13 | 62.9% | 0.0% | ✓ 主力 CWE |
| CWE89_SQL_Injection            | 2280| 142| 1421| 859 | 0 | 142| 62.3% | 0.0% | ✓ 主力 CWE |
| CWE90_LDAP_Injection           | 456 | 16 | 288 | 168 | 0 | 16 | 63.2% | 0.0% | ✓ 主力 CWE |
| CWE113_HTTP_Response_Splitting | 1368| 88 | 584 | 784 | 0 | 88 | 42.7% | 0.0% | ⚠ 偏低 |
| CWE134_Uncontrolled_Format_String | 684| 46 | 394 | 290 | 0 | 46 | 57.6% | 0.0% | ⚠ R201 后中游 |
| CWE252_Unchecked_Return_Value  | 17  | 4  | 1   | 16  | 0 | 4  | 5.9%  | 0.0% | ✗ 几乎全空 |
| CWE369_Divide_by_Zero          | 608 | 36 | 27  | 581 | 0 | 36 | 4.4%  | 0.0% | ✗ 几乎全空 |
| CWE476_NULL_Pointer_Dereference| 202 | 12 | 4   | 198 | 0 | 12 | 2.0%  | 0.0% | ✗ 几乎全空 |
| CWE193_Off_by_One_Error        | 51  | 4  | 0   | 51  | 0 | 4  | 0.0%  | 0.0% | ✗ 全空 |
| **TOTAL** | **8402** | **450** | **4442** | **3960** | **0** | **450** | **52.9%** | **0.0%** | — |

**关键观察**：
- **FP 0%**：所有 CWE、所有 source、所有 sub-pattern，FP=0（attribution 修正 + 没有新加 sink 的副作用）
- **Recall 三档**：
  - 高（≥57%）：CWE-23/36/78/80/81/83/89/90/134 — taint-tracing 主体 CWE
  - 中（42.7%）：CWE-113 — sink catalog 不全（addCookie）
  - 低（≤5.9%）：CWE-252/369/476/193 — checker 本身没建

## 2. 没覆盖什么场景（FN 系统性分析）

### 2.1 Source 维度的"系统性 0%"

下面这些 data origin 对当前 sink 来说**一个 TP 都没有**（10/10 sub-pattern 全 FN）：

| Source (data origin) | 涉及 CWE | 总 FN | 性质 | 修复方向 |
|---|---|---:|---|---|
| `database_*` (java.sql.ResultSet.getString) | CWE-23/36/78/89/90/134 | **190** | Source 缺 catalog | **R203-A**: 加 `**.getString/*` 等 SQL source pattern |
| `PropertiesFile_*` (Properties.load) | CWE-23/36/78/89/90/134 | **190** | Source 缺 catalog | **R203-B**: 加 `Properties` / `Properties.load` source |
| `*_addCookieServlet` | CWE-113 | **456** | Sink 缺 catalog | **R203-C**: 加 `addCookie` sink pattern |
| `CWE-476*` (8 个 source) | CWE-476 | **198** | Null-deref checker 没建 | **R205-A**: 修 null-deref detection |
| `CWE-193*` (3 个 loop kind) | CWE-193 | **51** | OOB checker 没建 | **R205-B**: 修 off-by-one / loop bound tracking |
| `CWE-369*` (16 个 source) | CWE-369 | **581** | Div-by-zero checker 链断 | **R204**: 修 div-by-zero 整链 |
| `CWE-252*` (1 source × 17 flow) | CWE-252 | **16** | Unchecked-return 链断 | **R203-D**: 修 unchecked-return per-call |

**总可恢复 TP**（如果这些 0% source 全 cover 到平均水平 ~74%）：约 **1,682 个 FN**。

### 2.2 Flow 维度的"系统性 missing"（跨所有 source 共享）

即使 source 覆盖了，每个 CWE 仍有一些 flow variant 100% miss（apply to **所有** source）：

| Flow | 含义 | 涉及 CWE 数量 | 单 CWE FN | 总 FN 估计 |
|---|---|---:|---:|---:|
| `21` | constant if-true（taint 设在 dead branch） | 11 | 11-12 | ~120 |
| `22a` | cross-class a→b（attribution 修正后残留） | 9 | 1-2 | ~12 |
| `41` | cross-class via return value | 9 | 1 | ~9 |
| `42` | cross-class via return value (variant) | 9 | 1 | ~9 |
| `45` | cross-class via private field | 11 | 1 | ~11 |
| `68a` | deep nested call | 11 | 1 | ~11 |
| `72a/73a/74a/75a` | 各种 cross-class dispatch | 9-11 | 1 each | ~36 |
| `81a` | cross-class abstract dispatch | 11 | 1 | ~11 |

**Flow 维度的 FN 估计**：每个 CWE ~12 个 flow 1-file 1-FN，跨 11 个 CWE ≈ **~240 个 FN**。
其中 `21` 是单点最大缺口（R181 修了 _15 switch-case，_21 constant-if-true 是下一个）。

**修复方向**：
- **`_21` 修法**：constant if-true 是 dead-branch 数据，理论上不该报（`if (true) { data = ... } else { /* unreachable */ }`），但 Juliet 期望 bad 函数也用 dead-branch 来引入 taint。这是 R183 已诊断但未修的最大单一缺口。**R203-E**。
- **`_22a/41/42/45/68a/72a-75a/81a` 残留**：R200 attribution 已大幅修复，但还有 entry 仍然 FN 的情况。**R203-F** 进一步深挖。

### 2.3 每个 CWE 的 missed scenarios（按 source 分类）

详细见 `smoke_juliet/r180_eval/coverage_all_full.md` 和 `coverage_by_source.md`。摘要：

#### CWE-23/36/78/90（path / cmd / LDAP）— 100% mirror
- **覆盖的 source（74-82%）**：connect_tcp, console_readLine, Environment, File, getCookies_Servlet, getParameter_Servlet, getQueryString_Servlet, listen_tcp, Property, URLConnection
- **0% 的 source**：database, PropertiesFile → **76 个 FN / CWE = 228 总**
- **73-82% source 内的 FN**：都是 `_21` `_22a` `_41` `_42` `_45` `_68a` 等 flow pattern
- 共同点：R200 attribution 后剩余 ~17-20 个 FN 跨各种 source，所有是"cross-class entry"或"dead-branch data"

#### CWE-80/81/83（XSS）— 与上面几乎相同分布
- 同样的 0% database / PropertiesFile
- 同样的 ~25% 内部 FN

#### CWE-89 SQL — 数量最大（2280 bad）
- **0% 的 source（catalog 缺 sink/source）**：
  - `database_execute{Query,Update,Batch}` / `database_prepareStatement` 5×38 = **190 FN**（SQL source → SQL execute sink，taint 链同源）
  - `PropertiesFile_execute{...}` 4×38 = **152 FN**（PropertiesFile source 缺 + 部分 sink 缺）
- **74-79% 的 source 内部 ~10 个 FN**：cross-class / dead-branch

#### CWE-113 HTTP Response Splitting — 0% addCookie
- **0% 的 sink 类别**：`addCookieServlet` × 12 source = **456 FN**（整个 sink 缺 catalog）
- 其它 sink `addHeaderServlet` / `setHeaderServlet` 在 76% 水平（与 CWE-23 同档）

#### CWE-134 Uncontrolled Format String — R201 printf fix 后
- 仍然 290 FN
- **0% source**：`database_format/printf`、`PropertiesFile_format/printf` 共 4×38 = 152 FN
- 其它 source 74-76% 水平

#### CWE-369 Divide by Zero — 几乎全空
- `float_connect_tcp_divide` 32%（稍好，但仍差）
- 其余 15 个 source 全 3%（每 source 仅 1 个 TP，可能是误判或巧合）
- **总 FN 581**，分布均匀

#### CWE-476 NULL Pointer Dereference — 全 0-12%
- 7 个 source × ~30 个 flow，**几乎全 FN**
- `Integer` 5%（2/38），`binary_if` 12%（2/17），其它 0%
- **总 FN 198**

#### CWE-193 Off by One — 0%
- 3 个 source（do / for / while）× 17 flow，全 0%
- **总 FN 51**

#### CWE-252 Unchecked Return — 5.9%
- 1 source（FileInputStream）× 17 flow，仅 `_01` TP
- **总 FN 16**

## 3. 误报（FP）场景

**总 FP = 0**。

所有 14 个 CWE、所有 450 个 good 文件（应该不报的都正确地没报）。

**原因**：
- R181 + R182 + R200 没引入新 sink（只修了 propagation / attribution）
- 现有 sink 命中本身就是精确的（catalog 里 `*.executeQuery/1`、`*.addHeader/2` 等都是 method signature-level 精确匹配）

## 4. R202+ 优化 ROI 排序

按"cost-low, gain-high"排序，**全部 1-2 天内可达成**：

| 优先级 | Round | 描述 | 预期 TP | Effort | 累计 Recall |
|---|---|---|---:|---|---|
| **P0** | R203-A | Catalog: 加 `**.ResultSet.getString/*` 等 SQL source | +190 | 0.25d | 55.1% |
| **P0** | R203-B | Catalog: 加 `Properties` load source | +190 | 0.25d | 57.4% |
| **P0** | R203-C | Catalog: 加 `addCookie` sink | +456 | 0.25d | 62.8% |
| **P1** | R204   | CWE-369 div-by-zero 整链修复 | +450 | 1.5d | 68.2% |
| **P1** | R203-D | CWE-252 unchecked-return 修 | +15  | 0.5d | 68.4% |
| **P1** | R203-E | `_21` constant-if-true taint 保留 | +120 | 0.5d | 69.8% |
| **P2** | R205-A | CWE-476 null-deref 修复 | +150 | 1.5d | 71.6% |
| **P2** | R205-B | CWE-193 off-by-one 修复 | +30  | 2.0d | 71.9% |
| **P3** | R203-F | 残余 cross-class / 21 边角 | +100 | 1.0d | 72.7% |

**Total R203-R205 累计 Recall 目标**：**52.9% → 72.7%**（+1,701 TP，0 FP）

## 5. 数据来源文件

| 文件 | 大小 | 内容 |
|---|---:|---|
| `smoke_juliet/r180_eval/coverage_all.json` | 178 KB | 程序化数据，所有 CWE × source × flow |
| `smoke_juliet/r180_eval/coverage_all_full.md` | 423 KB | 完整 markdown 报告（每个 CWE 列出所有 FN/FP sub-pattern） |
| `smoke_juliet/r180_eval/coverage_by_source.md` | 18 KB | Per-source 覆盖质量表（最适合看"哪个 source 系统性 0%"） |
| `smoke_juliet/r180_eval/coverage_all.cjs` | 8.6 KB | 综合 coverage 脚本（按 CWE 选 round） |
| `smoke_juliet/r180_eval/coverage_all_full.cjs` | 7.4 KB | 全量 markdown 输出 |
| `smoke_juliet/r180_eval/coverage_by_source.cjs` | 4.4 KB | Per-source 汇总 |
| `smoke_juliet/r180_eval/attribution_r200.cjs` | 2.7 KB | R200 归一化脚本（_NN[b-z] → _NNa） |

## 6. 下一步建议

按 P0 → P3 顺序执行：
1. **R203-A/B/C** (1d 累计)：纯 catalog 改动，0 风险，预期 Recall +20pp（45.1%→62.8%）
2. **R204** (1.5d)：CWE-369 是最大单一 CWE 缺口，预期 Recall +5.4pp（62.8%→68.2%）
3. **R205-A/B** (3.5d)：CWE-476/193 是新 checker，预期 Recall +3.7pp（68.2%→71.9%）

如果只追求 60% 整体 Recall，**R203-A/B/C 一天就够**。
