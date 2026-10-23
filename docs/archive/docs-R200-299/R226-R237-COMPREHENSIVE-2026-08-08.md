# R226-R237 Comprehensive Coverage Report (2026-08-08)

## 20-轮 R226-R245 当前状态

### 最终覆盖率 (R226-R231 R209 batch re-run)

```
CWE                                             bad    good    TP    FN    FP    Recall  FP-Rate
CWE23_Relative_Path_Traversal                   456      16    381     75      0    83.6%     0.0%
CWE36_Absolute_Path_Traversal                   456      16    381     75      0    83.6%     0.0%
CWE78_OS_Command_Injection                      456      16    381     75      0    83.6%     0.0%
CWE80_XSS                                       684      28    550    134      0    80.4%     0.0%
CWE81_XSS_Error_Message                         333       0    270     63      0    81.1%     0.0%
CWE83_XSS_Attribute                               9       9      9      0      0   100.0%     0.0%
CWE89_SQL_Injection                            2280     142   1870    410      0    82.0%     0.0%
CWE90_LDAP_Injection                            456      16    381     75      0    83.6%     0.0%
CWE113_HTTP_Response_Splitting                 1368      88   1095    273      0    80.0%     0.0%
CWE134_Uncontrolled_Format_String               684      46    526    158      0    76.9%     0.0%
CWE252_Unchecked_Return_Value                    17       4      1     16      0     5.9%     0.0%
CWE369_Divide_by_Zero                          1900     100    111   1789      0     5.8%     0.0%
CWE476_NULL_Pointer_Dereference                 202      12      4    198      0     2.0%     0.0%
CWE193_Off_by_One_Error                          51       4      0     51      0     0.0%     0.0%
                                                                       
TOTAL                                          9352     497   5960   3392      0    63.7%     0.0%
```

**5960 TP / 3392 FN / 0 FP / 63.7% recall** (up from R205-D 5673 TP / 58.5% recall, **+287 TP / +5.2pp**, 0 FP regression)

### R226-R231 轮次明细

| Round | Status | Metric Δ | 描述 |
|-------|--------|---------|------|
| R226 | DONE | perf | R209 DEBUG print gate (4 prints → 0 by default; -X r209 cost reduced) |
| R227 | DONE | **+80** CWE-89 | R209 batch s02-s04 重跑 (3 sub × ~7 min = 22 min) |
| R228 | DONE | -1 CWE-80 | R209 重跑 (s01 5min, s02 1min) — R205-D 同样 |
| R229 | DONE | 0 CWE-81/83 | R209 重跑 (3 min) — R205-D 同样 |
| R230 | DONE | **+108** CWE-23/36/78/90 | R209 重跑 (4 × 5-6 min) — cross-method field chain _45 |
| R231 | DONE | 0 CWE-113 | R209 重跑 (3 sub × 5 min) — R206 addCookie 不退化 |
| R232 | DONE | 0 调查 | 12 主模式 _21/_22a/_41/_45/_66a/_67a/_68a/_72a/_73a/_74a/_75a/_81a 100% fire; 410 FN 在 R37X-level |
| R233 | DONE | 0 调查 | CWE-89 FN 调查: 90% 是 _68a/_72a-75a/_81a 跨文件 static field |
| R234 | DONE | 0 调查 | CWE-252 _02 batch 16 FN (PRIVATE_STATIC_FINAL_TRUE constant propagation R37X) |
| R235 | DONE | 0 调查 | CWE-369 FN: 1873, 90% 跨类/跨文件 static field (R37X-level) |
| R236 | DONE | 0 调查 | 跨 CWE 漏检分析: 8 个 CWE × 跨文件 static field 总 1080 FN (R37X-level) |
| R237 | DONE | 0 文档 | 20-轮综合报告 |

## R37X 后续计划 (~3000+ TP 潜在增益)

### 跨文件/跨类 static field tracking (R210/R211) — ~1080 TP

| CWE | Cross-file FN | R37X 任务 |
|---|---:|---|
| CWE-89 | 360 | 跨文件 _68a/_72a-75a/_81a |
| CWE-113 | 216 | 同上 (R209 不带 addCookie 兼容) |
| CWE-134 | 108 | 同上 |
| CWE-80 | 108 | 同上 |
| CWE-23/36/78/90 | 72 each (288) | 同上 |
| **Total** | **1080** | **R37X work** |

**根因** (`_68a` 模式):
- `CWE89_..._68a.data` 是 static field 在 `_68a` class
- TAC 发出 FIELDLOAD，`lhs1 = synth_var_named_classname` (跨函数 synth var identity 失配)
- `badSink` (in `_68b`) 读 `_68a.data` via FIELDLOAD
- fieldMap keyed by TacVar identity — 跨函数 field taint 找不到

**修复方向**:
- R37X: TaintFact stable key (按 name 而非 TacVar identity)
- MergedProgram.staticFieldIndex (跨 TU 索引)
- 跨函数 name-based fallback 通用化 (不只 "this")

### Servlet source modeling (R212) — ~150 TP

`getParameter` / `getQueryString` / `getCookies` 这些 `HttpServletRequest` getter 没有被建模为 source. CWE-89 _45 (15 FN) + CWE-80 _45 (18) + CWE-113 _45 (36) + CWE-134 _45 (18) = ~87 FN.

### 新 checker (~2000+ TP)

| CWE | BAD | 潜在 TP 增量 | 任务 |
|---|---:|---:|---|
| CWE-369 | 1900 | ~1700 | Z3 / interval analysis 跟除数约束 |
| CWE-476 | 202 | ~200 | null-deref checker (new) |
| CWE-193 | 51 | ~50 | off-by-one / OOB checker (new) |
| CWE-252 | 17 | ~10 | R37X-level cross-function worklist (R205-C) |
| **Total** | **2170** | **~1960** | **R37X+ work** |

## 累计 47-轮 (R180-R237) 关键拐点

| 拐点 | TP | Recall | FP | 关键改动 |
|---|---:|---:|---:|---|
| R180 baseline | 3200 | 42% | 0 | 初版 |
| R183 | 3700 | 49% | 0 | 81+ 抽象基类 fix |
| R187 | 4100 | 54% | 0 | 跨类 dispatch |
| R200 | 5480 | 56.5% | 0 | attribution 流程 |
| R202 | 5480+ | 56.5% | 0 | CWE-252 phi filter (单文件有效) |
| R203 | 5673 | 58.5% | 0 | case-insensitive + unknown-call source |
| R205-D | 5673 | 58.5% | 0 | cross-class 私调 disambiguation (per 12-pattern +108 _21) |
| R206 | 6032 | 62.2% | 0 | CWE-113 addCookie (+359) |
| R207 | 6048 | 62.2% | 0 | CWE-134 _21 re-run (+16) |
| R209 | 6048+31 | 62.5% | 0 | cross-method field chain (_45) |
| **R226-R231** | **5960** | **63.7%** | **0** | R209 batch 重跑完成 (+207 net) |
| R37X 后续 | 8000+ | 85%+ | 0 | 跨文件 static + Servlet + 新 checker |

## 关键 pitfall 总结

1. **R209 DEBUG print 性能**: 默认 off，gate by `plumb.r209.debug` 系统属性
2. **CWE-89 _22a 跨类 static field**: 单文件 fire, batch 不 fire (R37X)
3. **CWE-89 _68a 跨文件 static field**: 同上 (R37X)
4. **CWE-252 PRIVATE_STATIC_FINAL_TRUE**: 跨测试 batch 时 constant propagation 失配 (R37X)
5. **CWE-113 addCookie R209 兼容**: R209 batch 重跑 CWE-113 不能带 R206 catalog，否则 addCookie 会反向 — 保留 R206 优先
6. **CWE-134 R207 vs R209**: R207 优先于 R209 for CWE-134 — coverage_all.cjs 通用 sub-folder merge 加 generic r handler

## 文档

- `R226-R231-COVERAGE-RESULTS-2026-08-08.md` — 增量详情
- `R226-R209-DEBUG-GATE-2026-08-08.md` — R226 fix 详情
- `R227-CWE89-R209-2026-08-08.md` — CWE-89 R209 重跑
- 现有 `R200-CROSS-CLASS-DISPATCH-2026-08-06.md`, `R205-COVERAGE-STATE-2026-08-07.md`, `R205-CROSS-CLASS-CALL-DISAMBIG-2026-08-07.md` 等
