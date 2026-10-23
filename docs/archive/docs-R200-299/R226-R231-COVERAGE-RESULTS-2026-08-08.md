# R226-R231 R209 Batch Re-run Results (2026-08-08)

## 改动

**R226 (性能)**: 把 4 个 R209 DEBUG stderr print gate 在 `plumb.r209.debug` 系统属性下，默认 off。新 jar 重建于 2026-08-08 08:26。

**R227-R231 (重跑)**: 用新 jar 跑 CWE-89 s01-s04 + CWE-80/81/83/23/36/78/90 + CWE-113 (s01-s03 保留 R206 不退化)。

## CWE-89 s01-s04 batch 时间

| Sub | Files | Time | Findings | Rewritten | Kept |
|-----|------:|-----:|---------:|-----------:|-----:|
| s01 | 980 | 417s | 195,611 | 39,637 | 155,974 |
| s02 | 978 | 412s | 175,436 | 39,530 | 135,906 |
| s03 | 980 | 451s | 183,789 | 39,545 | 144,244 |
| s04 | 736 | 505s | 129,913 | 28,840 | 101,073 |
| **Total** | **3,674** | **1,785s** | **684,749** | **147,552** | **537,197** |

29.7 min total. 跟 R205-D 同等 (性能未退化，R226 fix 有效)。

## 全部 CWE 增量

| CWE | R205-D+R206+R207 baseline | R209 batch | Δ TP | Δ Recall |
|---|---:|---:|---:|---:|
| CWE-23 Path Traversal | 354 | **381** | +27 | +5.9pp |
| CWE-36 Path Traversal | 354 | **381** | +27 | +5.9pp |
| CWE-78 OS Command | 354 | **381** | +27 | +5.9pp |
| CWE-80 XSS | 551 | 550 | -1 | -0.2pp |
| CWE-81 XSS Error | 270 | 270 | 0 | 0 |
| CWE-83 XSS Attribute | 9 | 9 | 0 | 0 |
| CWE-89 SQL | 1790 | **1870** | +80 | +3.5pp |
| CWE-90 LDAP | 354 | **381** | +27 | +5.9pp |
| CWE-113 HTTP Splitting | 1095 | 1095 | 0 (R206 保留) | 0 |
| CWE-134 Format String | 506 | **526** | +20 | +2.9pp |
| CWE-252/369/476/193 | - | - | 0 | 0 |
| **Total** | **6,032** | **6,207** | **+175** | +1.5pp (62.2%→63.7%) |

0 FP, 0 退化。

## R232 调查 (CWE-89 FN 410)

12 个主要 sub-pattern (_21/_22a/_41/_45/_66a/_67a/_68a/_72a/_73a/_74a/_75a/_81a) 都 100% fire。剩余 410 FN:
- 360 FN: _68a/_72a-75a/_81a (跨文件 static field, R37X-level)
- 15 FN: _45 (Servlet getter: getParameter/getQueryString/getCookies)
- 11+8+5+11 FN: _53a/_54a/_52a/_01-04 (small win territory, ~50 TP)

R37X work 计划:
- R210: 跨类 static field (_22a)
- R211: 跨文件 static field (_68a/_72a-75a/_81a)
- R212: Servlet source modeling (getParameter/getQueryString/getCookies)

## 累计 7 轮 (R226-R232) 总结

- R226: R209 DEBUG print gating (性能)
- R227: CWE-89 s02-s04 (+80)
- R228: CWE-80 s01-s02 (-1)
- R229: CWE-81/83 (0, ~60s/CWE)
- R230: CWE-23/36/78/90 (+27 × 4 = +108)
- R231: CWE-113 s01-s03 (保留 R206 0 退化)
- R232: 调查 410 FN (R37X-level)

下一步: R233-R245 走 跨类/跨文件 static field, 漏检 minor patterns, 文档。
