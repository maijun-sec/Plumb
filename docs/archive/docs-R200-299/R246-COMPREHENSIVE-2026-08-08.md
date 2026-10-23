# R246 Implicit-`this` Field Chain + Cross-CWE 重跑 (2026-08-08)

## R246 根因

R209 (TaintAnalysis.java:1041-1076 traceIntoCallee) 只在 `offset > 0` (显式 receiver) 时种 receiver fieldMap。但 Juliet `_45` 模式用 implicit-`this` 调用:
```java
public void bad(...) {
    data = request.getParameter("name");  // source
    dataBad = data;                        // FIELDSTORE this.dataBad
    badSink(request, response);            // offset=0
}
public void badSink(...) {
    String data = dataBad;                 // FIELDLOAD this.dataBad
    new File(root + data);                  // sink
}
```

`offset == 0` 跳过 receiver fieldMap 播种，badSink 读 `dataBad` 找不到 taint。

## 修复

`TaintAnalysis.java` 新增 `else if (offset == 0 && callee != null)` 分支:
- 扫描 `in.fieldMap()` 找名为 `this` 的 TacVar
- 收集所有 `this.X` field taint 到 `recvFields`
- 种到 `calleeEntry` 用 `thisVar` 作为 base

## R246 batch 重跑结果

| CWE | R209 | R246 | Δ | 关键修复模式 |
|---|---:|---:|---:|---|
| CWE-23 | 381 | **384** | +3 | _45 Servlet (getParameter 等) |
| CWE-36 | 381 | **384** | +3 | 同上 |
| CWE-78 | 381 | **384** | +3 | 同上 |
| CWE-80 | 550 | **569** | +19 | _45 (12 fires, 0 之前) + _53a/_54a (7) + _01/_02 (6) |
| CWE-81 | 270 | **279** | +9 | _45 私有 field 链 |
| CWE-83 | 9 | 9 | 0 | (无 _45) |
| CWE-89 | 1870 | **1894** | +24 | _45 15 fires + _53a/_54a/_52a/_01-04 |
| CWE-90 | 381 | **384** | +3 | _45 |
| CWE-113 | 1095 | **1134** | +39 | _45 (s01: 485→503, s02: 486→503, s03: 124→128) |
| CWE-134 | 506 | **567** | +41 | _45 12 fires (was 0) + 一些 _22a/_42/_01-04 |
| CWE-252/369/476/193 | - | - | 0 | (新 checker 需要) |
| **Total** | **5820** | **6104** | **+144** | **+1.2pp recall** |

**Total: 6104 TP / 3248 FN / 0 FP / 65.27% recall**

vs R205-D baseline (5673): **+431 TP, +6.8pp recall, 0 FP**
vs R226-R231 R209 batch (5960): **+144 TP, +1.2pp recall, 0 FP**

## R246 关键经验 (跨项目通用)

1. **method call offset 含义**: offset==0 是 implicit `this` 调用, offset>0 是显式 receiver 调用. R209 fix 漏了 offset==0 case 是常见疏漏
2. **traceIntoCallee 必须支持两种调用方式**: pre-existing code 经常只考虑一种
3. **name-based fallback 通用化**: `this` TacVar identity 在每个函数不同, 必须用 name 匹配
4. **single file test 必跑**: R246 single file 0 → 2 fire 立即验证 fix 有效
5. **CWE-113 也受益**: R246 fix 适用于所有 `_45` (data flow: private class member variable) 模式, 不只是 Servlet

## 剩余 FN (R37X-level 1132)

- CWE-23/36/78/90: 72 each (288 total) — 跨文件 static field
- CWE-80: 115 (108 跨文件 + 7 other)
- CWE-89: 386 (360 跨文件 + 18 _53a 跨类 3 层 + 8 _01-04 batch)
- CWE-113: 234 (216 跨文件 + 18 small)
- CWE-134: 117 (108 跨文件 + 9 small)
- CWE-252/369/476/193: 16+1789+198+51 = 2054 (新 checker 需要)

**R37X 后续**: TaintFact stable key + MergedProgram static field index (~1132 TP) + Z3 chain (~1700 TP for CWE-369)
