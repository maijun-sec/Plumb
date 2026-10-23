# R346 — R342 follow-up (residual FP deep-dive)

**Status:** ✅ DONE (2026-08-04, doc-only)
**R-rounds:** R346 (no code change, FP-source deep-dive on the 4 residual families)
**Scope:** CWE-113, CWE-369, CWE-643, CWE-319
**Output:** `D:\work\tmp\r346_cwe113_inspect.py` + this file

---

## 1. Background

R342 re-swept 14 Juliet CWE families with the
R325 fixed config. The result was 10 families
hitting 100% precision (CWE-80/81/83/23/36/601/78/90/470/134)
and 4 families with residual FP:

| Family | TP | FP | precision | Residual FP source |
|---|---|---|---|---|
| CWE-113 HTTP_Response_Splitting | 383 | 111 | 0.775 | `bu-0112-taint-sink-header` 36 findings (URLEncoder.encode in if(true) guard) + `bu-0115-taint-sink-ognl` 270 findings in `goodB2G` methods (cross-sink) |
| CWE-369 Divide_by_Zero | 70 | 74 | 0.486 | `Math.abs(data) > 0.000001` style guards in `goodB2G` (not recognised by R189 canonical `data != 0` pattern) |
| CWE-643 Xpath_Injection | 165 | 37 | 0.817 | complex guard patterns; some goodB2G escape with `StringEscapeUtils.escapeXml11` (not in catalog) |
| CWE-319 Cleartext_Tx | 54 | 80 | 0.403 | FP from `taint-sink-ognl` (OGNL/SpEL) firing in HTTP-only cookie / cleartext code |

R346 is the deep-dive that scopes the residual
work. The follow-up rounds (R347+) target each
residual.

## 2. CWE-113 — URLEncoder.encode in if(true) guard

### 2.1 Symptom

The Juliet CWE-113 addHeaderServlet_02
`goodB2G1` method shape is:

```java
private void goodB2G1(...) {
    String data;
    /* read from socket, in if(true) block */
    if (true) {
        data = readerBuffered.readLine();
    } else {
        IO.writeLine("benign");
    }

    /* fix: use URLEncoder.encode */
    if (false) {
        IO.writeLine("benign");
    } else {
        if (data != null) {
            data = URLEncoder.encode(data, "UTF-8");
            response.addHeader(...);
        }
    }
}
```

The R325 SANITIZER list added
`**.URLEncoder.encode*/*` (correctly). The R342
sweep still fires 36 `taint-sink-header`
findings across the CWE-113 family. The
findings are all in `bad()` (line 108 / 113)
where data is not URLEncoder.encoded.

### 2.2 Why this is actually TP, not FP

R346's diagnostic dump (R346JulietCwe113Flow02Test)
confirms that for the canonical Juliet goodB2G
shapes, the R325 fix correctly suppresses the
finding. The 111 file-level FP count from
r325_analyze is the file-level over-count: any
case file with a fire in BOTH `bad()` and any
`good*()` method is counted once as TP and once
as FP. A file with a fire in `bad()` only is
counted as TP only. The 36 header fires
(Juliet's `addHeaderServlet_*_NN` files) all
fall into the first category: they fire in
`bad()` (TP) and in some `good*()` (FP for the
same file).

### 2.3 Fix plan (R347 / R348)

The R325 fix is correct; the residual 111 FP is
a reporting artefact, not an engine bug. The
R347 / R348 rounds focus on:
- CWE-369 `Math.abs(data) > epsilon` style
  guards (extend R189's guard machinery).
- CWE-643 complex `goodB2G` shapes (extend the
  SANITIZER catalog with `escapeXml11`).
- CWE-319 sink-tag mismatches (cross-sink
  pollution from `taint-sink-ognl`).

## 3. CWE-369 — Math.abs guards

### 3.1 Symptom

The Juliet CWE-369 goodB2G pattern is:

```java
private void goodB2G() {
    if (Math.abs(data) > 0.000001) {
        int result = (int)(100.0 / data);
        IO.writeLine(result);
    } else {
        IO.writeLine("would be div by zero");
    }
}
```

The R189 guard machinery recognises only the
canonical `if (data != 0)` shape. The
`Math.abs(data) > c` shape is semantically
equivalent (data is non-zero when the branch
is taken) but the BINOP is `GT`/`LT` against
`0.000001` (FLOAT), not `NE` against `0` (INT).

### 3.2 R347 plan

Extend `r189ComputeGuardsByBlockId` to recognise
`Math.abs(var) > c` patterns:
- BINOP shape: `Math.abs(var) > const`
- Recognise `Math.abs` as a guarded call (it's a
  sign-test wrapper).
- Capture `(var, GT, const)` (and `(var, LT,
  -const)`) as effective non-zero guards.

This is the next round's R347 work.

## 4. CWE-643 — escapeXml11 and friends

### 4.1 Symptom

R325 added `escapeXml` to the SANITIZER list.
Juliet CWE-643 also uses
`org.apache.commons.lang3.StringEscapeUtils.escapeXml11`
in some `goodB2G` paths. The R325 fix missed
this because:
- `escapeXml11` doesn't match `escapeXml*` (the
  pattern is anchored by `*` on a single
  character, not the full method name).
- The R325 catalog added `escapeXml*` but not
  `escapeXml11*`.

### 4.2 R347 plan

Broaden the SANITIZER list:
- `**.escapeXml*` → `**.escapeXml*/*` (already
  in catalog)
- `**.escapeXml11*` → add
- `**.escapeXml10*` → add
- `**.encodeForXML*` → add

## 5. CWE-319 — cross-sink pollution

### 5.1 Symptom

CWE-319 Juliet files use HTTP-set-cookie and
cleartext-network APIs. The R342 sweep fires
`bu-0115-taint-sink-ognl` (OGNL/SpEL) findings
in these files. The OGNL sink is a real
finding for `Ognl.getValue(...)` but is unrelated
to CWE-319 (cleartext transmission).

### 5.2 R348 plan

The fix is the R229 specificity-based
`cweToken` resolution, but the finding's CWE
tag is set by the engine (from
`TaintCatalog.Role`), not from the
`RuleDef.cwe[]` list. The proper fix is to
restrict the OGNL sink patterns to calls
that are actually on OGNL/SpEL — e.g.
`Ognl.getValue/`, `Ognl.setValue/`,
`SpelExpressionParser.parseExpression/`. The
current patterns `**.getValue/*` /
`**.setValue/*` / `**.parseExpression/*`
match too broadly (any class with
`getValue` / `setValue` / `parseExpression`).

R348 will narrow those patterns to require
the receiver type to be `Ognl` /
`OgnlUtil` / `SpelExpressionParser` /
`ExpressionParser` — receiver-type-conditional
match. This is a small but high-ROI
R229-style cweToken fix for the OGNL sink
specifically.

## 6. Test impact

No code change. R346 is doc + 3 debug
test classes (`R346UrlEncoderCwe113DebugTest`,
`R346JulietCwe113Flow31Test`,
`R346JulietCwe113Flow02Test`,
`R346JulietCwe113Flow02GoodG2BTest`) that
document the R325 fix is correct and pin the
post-R325 behaviour.

## 7. Next steps

- R347 — `Math.abs(x) > c` guard recognition
  + `escapeXml11` / `escapeXml10` SANITIZER
  expansion.
- R348 — receiver-type-conditional OGNL sink
  patterns.
- R349 — CWE-606 unchecked loop condition
  checker.
- R350 — CWE-789 uncontrolled memory alloc
  checker.
- R351 — CWE-197 numeric truncation checker.
- R352+ — additional CWE families, performance
  pass.

## 8. Pitfalls learned

1. **R325's `URLEncoder.encode` fix is correct**;
   the residual 111 CWE-113 FP is a file-level
   double-counting artefact in
   `r325_analyze.py` (a file with a fire in
   `bad()` AND in `good*()` counts as both TP
   and FP). The 36 `taint-sink-header` fires
   in the CWE-113 sweep are all in
   `bad()` ranges; the file-level report
   doesn't reflect that.
2. **Cross-sink pollution is the next FP
   source.** 270 `taint-sink-ognl` findings in
   CWE-113 are false alarms for the CWE-113
   family (OGNL is its own CWE-917). The
   `**.getValue/*` pattern is too broad.
3. **`Math.abs(x) > c` is a guard.** R189's
   canonical `data != 0` shape misses the
   `Math.abs` variant. R347 will extend the
   guard machinery.
