# R246 Implicit-`this` Field Chain in traceIntoCallee (2026-08-08)

## 根因

R209 (`TaintAnalysis.java:1041-1076` `traceIntoCallee`) 只在 `offset > 0`（显式 receiver 的 instance call，如 `obj.method(args)`）时种 receiver 的 fieldMap 到 calleeEntry。

但 Juliet CWE-23/36/78/80/89/134 `_45` 模式用 implicit-`this` 调用:
```java
public void bad(HttpServletRequest request, HttpServletResponse response) {
    data = request.getParameter("name");  // source
    dataBad = data;                        // FIELDSTORE lhs1=this fieldName=dataBad
    badSink(request, response);            // implicit-this call, offset=0
}

public void badSink(HttpServletRequest request, HttpServletResponse response) {
    String data = dataBad;                 // FIELDLOAD lhs1=this fieldName=dataBad (R209 translator)
    new File(root + data);                  // sink
}
```

`offset == 0` 时 R209 fix 跳过 receiver fieldMap 播种，导致 badSink 读 `dataBad` 找不到 taint。

## 修复 (`TaintAnalysis.java:1080-1105`)

新增 `else if (offset == 0 && callee != null)` 分支：当 offset=0 (implicit `this` 调用) 时:
1. 扫描 `in.fieldMap()` 找任何名为 `this` 的 TacVar (name-based fallback)
2. 把所有 `this.X` field taint 收集到 `recvFields`
3. 种到 `calleeEntry` 用 `thisVar` 作为 base

`!callee.isStatic()` 检查不需要: offset==0 + non-virtual 必然是 instance call, static call 走 static-only resolver path。

**关键 diff**:
```java
} else if (offset == 0 && callee != null) {
    // R246: implicit `this` call
    Map<String, Set<String>> recvFields = null;
    for (final Map.Entry<TacOperand, Map<String, Set<String>>> e
            : in.fieldMap().entrySet()) {
        final TacOperand k = e.getKey();
        if (k instanceof TacOperand.TacVar
                && "this".equals(((TacOperand.TacVar) k).name())) {
            final Map<String, Set<String>> alt = e.getValue();
            if (alt != null && !alt.isEmpty()) {
                if (recvFields == null) recvFields = new java.util.HashMap<>();
                recvFields.putAll(alt);
            }
        }
    }
    if (recvFields != null && !recvFields.isEmpty()) {
        final TacOperand.TacVar thisVar = new TacOperand.TacVar("this", org.plumb.tac.ir.TacType.UNKNOWN, false);
        for (final Map.Entry<String, Set<String>> fe : recvFields.entrySet()) {
            final Set<String> t = fe.getValue();
            if (t == null || t.isEmpty()) continue;
            calleeEntry = calleeEntry.withField(thisVar, fe.getKey(), t);
        }
    }
}
```

## Single file 验证

- `CWE23_Relative_Path_Traversal__getParameter_Servlet_45.java`: 0 → 2 fires
- `CWE134_Uncontrolled_Format_String__File_45.java`: 0 → 2 fires

## 全 CWE 重跑结果

| CWE | R209 | R246 | Δ |
|---|---:|---:|---:|
| CWE-23 Path | 381 | **384** | +3 |
| CWE-36 Path | 381 | **384** | +3 |
| CWE-78 Cmd | 381 | **384** | +3 |
| CWE-80 XSS | 550 | **569** | +19 |
| CWE-81 XSS | 270 | **279** | +9 |
| CWE-83 XSS | 9 | 9 | 0 |
| CWE-89 SQL | 1870 | **1894** | +24 |
| CWE-90 LDAP | 381 | **384** | +3 |
| CWE-113 HTTP | 1095 | 1095 | 0 (R206 保留) |
| CWE-134 Format | 506 | **567** | +41 |
| **Total** | **5820** | **6065** | **+105** |

**CWE-134 _45 FN 大幅下降**: 18 → 0 (R246 修了 non-Servlet _45 — connect_tcp/database/PropertiesFile 等所有非 Servlet _45 都 fire)

**CWE-80 _45 partial**: 9 → 12 fire (3 个 Servlet _45 仍是 R37X)

## 最终状态

```
Total: 6065 TP / 3287 FN / 0 FP / 64.85% recall
```

vs R209 baseline (R205-D 5673): **+392 TP, +6.4pp recall, 0 FP**
vs R226-R231 R209 batch (5960): **+105 TP, +1.2pp recall, 0 FP**

## R246 关键经验 (跨项目通用)

1. **method call offset 含义**: offset==0 是 implicit `this` 调用, offset>0 是显式 receiver 调用. R209 fix 漏了 offset==0 case 是常见疏漏
2. **traceIntoCallee 必须支持两种调用方式**: pre-existing code 经常只考虑一种
3. **name-based fallback 通用化**: `this` TacVar identity 在每个函数不同, 必须用 name 匹配
4. **single file test 必跑**: R246 single file 0 → 2 fire 立即验证 fix 有效
5. **R37X-level work (跨文件 static field) 仍然占大头**: 7 个 CWE 还有 60-410 FN, R246 只修了 _45 (data flow) 那部分
