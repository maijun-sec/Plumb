# R203 Catalog Case-Insensitive + Unknown-Call Source Match — 2026-08-07

> **R203 目标**：把 7 个主力 CWE 全部从 0%-60% 命中率的 source (database / PropertiesFile) 推到 70%+。把数据库 source 跨类 dispatch 的 0% 命中率归零。
>
> 全部跑通 9 个 CWE（23/36/78/80/81/83/89/90/113），recall **52.9% → 62.9%**（+10pp，+842 TP），0 FP。

## 1. 根因

### 1.1 catalog pattern 大小写不匹配
R200 之前的 catalog pattern 写 `**.ResultSet.getString/*`（大写 `R`），但 TAC 翻译出的 `calleeSig` 是 `resultSet.getString/1`（小写 `r`）。Juliet 用 `ResultSet resultSet = ...; data = resultSet.getString(1)` —— 局部变量名 `resultSet` 是小写，Java parser 保留小写，TAC 也用小写。

`pat()` 函数 (line 48-86) 用 `Pattern.compile(sb.toString())` —— **默认大小写敏感**。所以 `**.ResultSet.getString/*` 永远不匹配 `resultSet.getString/1`。

### 1.2 同样的问题
- `**.Properties.getProperty/*` 不匹配 `properties.getProperty/1`（CWE-23/36/78/89/90/134）
- `**.HttpServletResponse.addCookie/*` 不匹配 `response.addCookie/1`（CWE-113 addCookie）

### 1.3 根本的 catalog.match() 调用只在 resolved callee
`TaintAnalysis.transferInvokeImpl` (line 591) 调 `catalog.match(ins)` —— 这条 path 只处理已 resolve 成 TacFunction 的 callee。但 JDK 方法（`ResultSet.getString`、`Properties.getProperty` 等）永远没在 program 里，**走 `transferUnknownCallImpl`**，那里**完全没查 catalog**。

结果是：R200 之前跑 CWE-89/23/36/78 时，`resultSet.getString(1)` 这个 source **完全没识别**，`data` 永远没被标记为 tainted，sink 不触发。

## 2. 修复

### 2.1 `pat()` 加 CASE_INSENSITIVE flag (`TaintCatalog.java:85`)

```java
// before
return Pattern.compile(sb.toString());
// after
return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
```

效果：catalog 里所有 pattern 自动大小写不敏感。`ResultSet.getString` ≡ `resultSet.getString`。

### 2.2 `transferUnknownCallImpl` 也调 catalog (`TaintAnalysis.java:722-738`)

```java
// R203: catalog-match unknown calls too. Pre-R203 only
// transferInvokeImpl (where the callee resolved to a
// TacFunction) consulted the catalog. JDK methods like
// ResultSet.getString never resolve, so their SOURCE entry
// was never triggered, leaving the dest un-tainted.
final TaintCatalog.Entry unresolvedEntry = this.catalog.match(ins);
if (unresolvedEntry != null && unresolvedEntry.role() == TaintCatalog.Role.SOURCE
        && ins.dest != null) {
    in = in.with(ins.dest, java.util.Set.of(unresolvedEntry.sourceId()));
}
```

## 3. 单文件验证 (`CWE23_Relative_Path_Traversal__database_01.java`)

R203 改动后 trace 输出（取自 R203-MATCH debug stderr）：

```
ins.calleeSig='IO.getDBConnection/0' entry=null in bad
ins.calleeSig='connection.prepareStatement/1' entry=null in bad
ins.calleeSig='preparedStatement.executeQuery/0' entry=null in bad
ins.calleeSig='resultSet.getString/1' entry=bu-0007-taint-db-result in bad   ← 触发
ins.calleeSig='System.getProperty/1' entry=bu-0009-taint-source-system-property in bad
ins.calleeSig='File.<init>/1' entry=bu-0103-taint-sink-path in bad
ins.calleeSig='FileInputStream.<init>/1' entry=bu-0103-taint-sink-path in bad
```

最终 finding 输出：
```
bu-0103-taint-sink-path | line 118
  Tainted t_t27:<unknown> ([bu-0007-taint-db-result]) flows into File.<init>/1
  (Filesystem path built from tainted data)
```

完整 source→sink 链：**resultSet.getString → data → File.<init>**。R200 之前此链断开，无任何 finding。

## 4. Per-CWE 增量

| CWE | bad | R200 TP | R203 TP | Δ TP | R200 Recall | R203 Recall | Δ Recall |
|---|---:|---:|---:|---:|---:|---:|---:|
| CWE-23 path traversal | 456 | 288 | 344 | **+56** | 63.2% | **75.4%** | +12.2% |
| CWE-36 absolute path | 456 | 288 | 344 | **+56** | 63.2% | **75.4%** | +12.2% |
| CWE-78 OS cmd | 456 | 288 | 344 | **+56** | 63.2% | **75.4%** | +12.2% |
| CWE-80 XSS | 684 | 429 | 541 | **+112** | 62.7% | **79.1%** | +16.4% |
| CWE-81 XSS error | 342 | 215 | 271 | **+56** | 62.9% | **79.2%** | +16.3% |
| CWE-83 XSS attr | 342 | 215 | 271 | **+56** | 62.9% | **79.2%** | +16.3% |
| CWE-89 SQL injection | 2280 | 1421 | 1701 | **+280** | 62.3% | **74.6%** | +12.3% |
| CWE-90 LDAP | 456 | 288 | 344 | **+56** | 63.2% | **75.4%** | +12.2% |
| CWE-113 HTTP response | 1368 | 584 | 698 | **+114** | 42.7% | **51.0%** | +8.3% |
| CWE-134 format string | 684 | 394 | 394 | 0 (R201 baseline) | 57.6% | 57.6% | 0 |
| CWE-252 unchecked return | 17 | 1 | (not re-run) | — | 5.9% | — | — |
| CWE-369 div by zero | 608 | 27 | (not re-run) | — | 4.4% | — | — |
| CWE-476 null deref | 202 | 4 | (not re-run) | — | 2.0% | — | — |
| CWE-193 off by one | 51 | 0 | (not re-run) | — | 0.0% | — | — |
| **TOTAL** | **8402** | **4442** | **5284** | **+842** | **52.9%** | **62.9%** | **+10.0%** |

**FP 全 0**。Recall 提升 10pp 全部来自新增 TP（无任何 FP 副作用）。

## 5. 漏的 3118 FN 分布（按 sub-pattern）

| 类别 | FN 估计 | 修复路径 |
|---|---:|---|
| `_21` constant if-true (dead-branch 引入 taint) | ~120 | **R203-D** `transferInvokeImpl` 对 `if (true) { data = source }` 也让 taint flow |
| `_22a / _41 / _42 / _45 / _68a / _72a-75a / _81a` 跨类 entry 残留 | ~200 | **R203-F** attribution 强化 + scope-aware propagation |
| `addCookie` (CWE-113 s01/s02/s03 partial) | ~250 | **R203-G** case-insensitive addCookie 已经在 R203 全应用，剩 FN 是跨 TU |
| CWE-369 整链 | ~581 | **R204** div-by-zero 整 chain |
| CWE-476 null-deref | ~198 | **R205-A** null check 跟踪 |
| CWE-193 off-by-one | ~51 | **R205-B** loop induction var |
| CWE-252 unchecked return | ~16 | **R203-D** per-call Phi filter |
| CWE-80/81/83 sparse FN | ~143 | `connect_tcp_81_*` cross-class |

## 6. 文件 & 工具

- **Source change 1**: `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintCatalog.java:85` — `Pattern.compile` 加 `CASE_INSENSITIVE`
- **Source change 2**: `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java:722-738` — `transferUnknownCallImpl` 加 `catalog.match(ins)` source 触发
- **Catalog additions (R203-A/B/C, may be removed since R203 case-insensitive covers them)**:
  - `pat("getString/*")`, `pat("getObject/*")`, `pat("getInt/*")`, `pat("getLong/*")` (bare, no class prefix) — `TaintCatalog.java:90-91`
  - `pat("getProperty/*")`, `pat("getProperty")`, `pat("Properties.getProperty")`, `pat("Properties.get")` — `TaintCatalog.java:92-93`
  - `pat("addCookie/*")` (bare) — `TaintCatalog.java:95`
- **Build**: `mvn -DskipTests -Dmaven.test.skip=true -pl plumb-dfa,plumb-main -am package`
- **Eval**: 9 个 CWE 用 `run_r203_all.ps1` + `run_r203_remaining.ps1` (sub-batched)
- **Attribution**: `attribution_r200.cjs` (existing) — for each CWE's `.r203.ndjson` → `.r203.attributed.ndjson`
- **Coverage**: `coverage_all.cjs` (updated with `r203` priority) + `coverage_all_full.cjs`

## 7. 关键经验

1. **TAC `calleeSig` 用变量名**（小写），catalog pattern 用 class 名（大写）—— 必须 case-insensitive
2. **未知 callee 不查 catalog = 整类 source/sink 失效** —— transferUnknownCallImpl 必须也调 `catalog.match`
3. **0 FP** — case-insensitive + bare pattern 没引入任何误报：Java 命名规范下，`getString`/`getProperty`/`addCookie` 几乎只来自一个 class
4. **Source-side 修复比 sink-side 修复回报高** —— 一个 source pattern 修复影响所有 sink 类别
5. **R200 attribution 同样适用 R203** —— 同一个 attribution script 处理 1320+ finding 重写，0 改动
6. **multi-sub-folder CWE** (CWE-89, CWE-113) 必须用 sub-batching 否则 30 min 跑不完

## 8. R200 之后状态 vs R203 之后状态

| Metric | R190 (baseline) | R200 | R203 | R200→R203 |
|---|---:|---:|---:|---:|
| Total TP | 2925 | 4442 | **5284** | **+842** |
| Total FN | 5477 | 3960 | 3118 | -842 |
| Total FP | 0 | 0 | 0 | 0 |
| Total TN | — | 450 | 450 | 0 |
| Recall | 34.8% | 52.9% | **62.9%** | **+10.0pp** |
| FP-Rate | 0% | 0% | 0% | 0 |

**R203 累计 R200 之后 +10pp recall，0 FP。** 主线 CWE (23/36/78/89/90) 全部 75% 左右，XSS 80% 左右。
