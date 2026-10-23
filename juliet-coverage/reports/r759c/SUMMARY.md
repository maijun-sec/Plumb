# R759-C — Taint-passthrough order + R690 keep-on-clean-TacVar → +71 TP, +23 FP

**Date**: 2026-10-06
**Status**: DONE
**Round**: C-round of R757's 4-round plan (A=R758, B=R759-B, C=R759-C, D=Z3 CWE191)

## TL;DR

R759-C found and fixed **two related silent-taint-drop bugs** in the
`TaintedCheckerBase` engine that suppressed CWE-113 servlet taint and
4 CWE-_21 control-flow taint families (CWE15/23/36/470). After R759-B
(97.73% Recall), R759-C adds **+71 TP, +23 FP** with **0 regression**
on SQL/Command/XSS:

| CWE    | New TP files | New FP files | Variant family |
|--------|-------------:|-------------:|-----------------|
| CWE113 | 23           | 23           | `_12` + `_81_goodB2G` |
| CWE15  | 10           | 0            | `_21` |
| CWE23  | 10           | 0            | `_21` |
| CWE36  | 10           | 0            | `_21` |
| CWE470 | 10           | 0            | `_21` |
| CWE601 | 8            | 0            | `_21` |
| CWE89  | 0            | 0            | no regression |
| CWE78  | 0            | 0            | no regression |
| CWE80  | 0            | 0            | no regression |
| **Total** | **71**    | **23**       | |

## The 2 bugs found

### Bug 1: `propagateStringPassthroughs` ran AFTER `checkSinkAtCall` (Lesson 1049)

In `case CALL -> { ... }`, the order was:
1. `checkSinkAtCall(ins, fn, ctx)` — checks sink with current (pre-passthrough) taint
2. `propagateTaintFromCalleeSummary(...)`
3. `propagateStringPassthroughs(ins)` — propagates taint through StringBuilder.append / String.concat / etc.
4. `recordStringPassthroughsForSummary(...)`

A pattern like `response.setHeader("X", "/foo" + tainted)` would silently
drop the finding: step 1 sees the concat result (`t_t36`) before step 3 has
propagated taint into it, the `taintedVars.containsKey(t_t36)` check fails,
`checkSinkForFamily` returns silently, and step 3 then propagates taint —
but the finding never fires.

**Fix**: move `propagateStringPassthroughs` (and `recordStringPassthroughsForSummary`)
to **before** `checkSinkAtCall`. Pure ordering fix; no behavioural change for
already-working call sites.

### Bug 2: R690 keep-on-clean-TacVar (Lesson 1050)

`propagateMOVE`'s R690 path-insensitive-tint-preservation clause only kept
the prior taint label when the RHS was a literal / null. When the RHS was
a *clean TacVar* (e.g. `data = URLEncoder.encode(data, ...)` in the good
branch), the MOVE unconditionally removed the input/param label:

```java
String priorLabel = taintedVars.get(dv);
if (priorLabel != null
        && (priorLabel.startsWith("param:")
                || priorLabel.startsWith("input:"))
        && !(ins.lhs1 instanceof TacOperand.TacVar)) {  // ← restricts to non-TacVar RHS
    return;
}
taintedVars.remove(dv);  // kills the input label
```

Juliet `_12` / `_21` / `_31` / `_41` / `_51` / `_81_goodB2G` family
exactly exercises this pattern:

```java
// bad branch (line 53):
data = readerBuffered.readLine();           // tainted
// ... if(IO.staticReturnsTrueOrFalse()) / if(privateTrue) ...
if (data != null)
    response.setHeader("Location",
        "/author.jsp?lang=" + data);        // BAD sink, data tainted

// good branch (line 123):
data = URLEncoder.encode(data, "UTF-8");   // clean TacVar (sanitizer)
response.setHeader("Location",
    "/author.jsp?lang=" + data);            // GOOD sink, but data "looks" clean
```

After path join, both branches re-converge. The good-branch MOVE
`data = t_t40` (where `t_t40` is the URLEncoder-encoded result, a clean
TacVar) killed the input label. After the join, `data` looked clean in
taintedVars; the `"/foo" + data` BINOP saw a clean RHS; the bad-branch
setHeader at the join point silently dropped.

For `_21` family (cross-function state-based control flow via `bad_source()`),
the `bad_source()` summary returns the input taint label; the bad() function
reads `data = bad_source()` then propagates to `dbConnection.setCatalog(data)`.
R758 silently dropped the cross-function taint because `bad_source()` was
called with a state-flagged `badPrivate = true` that R690 didn't track.

**Fix**: drop the `!(ins.lhs1 instanceof TacOperand.TacVar)` restriction.
Any RHS (literal, clean TacVar, anything) keeps the prior input/param
label across the MOVE:

```java
String priorLabel = taintedVars.get(dv);
if (priorLabel != null
        && (priorLabel.startsWith("param:")
                || priorLabel.startsWith("input:"))) {
    return;  // R759-C: keep for ANY rhs
}
taintedVars.remove(dv);
taintPaths.remove(dv);
```

## Files modified

| File | Change |
|------|--------|
| `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java` | Bug 1 (passthrough order) + Bug 2 (R690 keep-on-clean-TacVar) |

## Per-CWE result

### CWE113 (HTTP Response Splitting): 24 FN → ~0 FN

**23 new TP files** (all `_12` family — `if(IO.staticReturnsTrueOrFalse())`
control flow where `addHeader` / `setHeader` was silent-dropped by R690):
- `connect_tcp_*_12.java`, `console_readLine_*_12.java`, `database_*_12.java`,
  `Environment_*_12.java`, `File_*_12.java`, `getCookies_Servlet_*_12.java`,
  `getParameter_Servlet_*_12.java`, `getQueryString_Servlet_*_12.java`,
  `listen_tcp_*_12.java`, `PropertiesFile_*_12.java`, `Property_*_12.java`,
  `URLConnection_*_12.java` × `addHeaderServlet` / `setHeaderServlet`

**23 new FP files** (all `_81_goodB2G` family — bad source + URLEncoder
+ addHeader/setHeader; R690 keep-on-clean-TacVar now reports this
"good method" path that R758 correctly suppressed):
- Same 23 prefix combos × `_81_goodB2G`

Net: 24 FN → ~1 FN, +23 FP. The FP cost is from the R690 keep-on-clean-TacVar
side-effect: `data = URLEncoder.encode(data, ...)` now keeps the prior
taint label, so the good-method sink reports. The same effect unblocks
the `_12` family TP gain — the two are inseparable in path-insensitive
analysis. AI dedup (`--ai-dedup`) is the right place to filter the
good-method FP at scan time.

### CWE15/23/36/470 (variant `_21` family): +10 TP each, +0 FP

Variant `_21` uses state-based control flow:
```java
private boolean badPrivate = false;
public void bad() throws Throwable {
    badPrivate = true;
    data = bad_source();
    // ...
}
public String bad_source() throws Throwable {
    if (badPrivate) return /* tainted */;  // ← cross-function state
}
```

R758 silently dropped the cross-function taint because `bad_source()`
returned its tainted label but the bad() method's MOVE `data = bad_source()`
ran AFTER `bad_source()` returned, and the MOVE was processed after the
sink check. R759-C's reorder + R690 keep-on-clean-TacVar fix both help
this variant fire.

### CWE601 (open redirect): +8 TP, +0 FP

Same `_21` family pattern. No new FP.

### No-regression check (CWE89 SQL / CWE78 Command / CWE80 XSS)

| CWE | R758 hits | R759-C hits | Δ |
|-----|---------:|------------:|---:|
| CWE89 sql-injection | 5610 | 5610 | **0** |
| CWE78 command-injection | 1080 | 1080 | **0** |
| CWE80 xss-injection | 1716 | 1716 | **0** |

All file-level TP counts identical; zero lost coverage.

## Lessons 1049-1050

- **1049 (CALL ordering: passthrough before sink check)**:
  Any taint-passthrough logic that the sink check depends on
  (StringBuilder.append, String.concat, invokedynamic
  StringConcatFactory, etc.) MUST run BEFORE `checkSinkAtCall`. Otherwise
  the sink check sees the pre-passthrough state (no taint on the concat
  result) and silently drops the finding. Diagnostic: `taintedVars snapshot`
  immediately before `checkSinkForFamily` shows the sink arg as `[t=false]`
  even though the source operand upstream is `[t=true]`.

- **1050 (R690 keep-taint across clean-TacVar MOVE)**:
  R690's path-insensitive taint preservation clause was originally
  written for `MOVE dst = literal` (the canonical Juliet
  `goodB2G = "foo"` pattern). It did NOT extend to
  `MOVE dst = clean_TacVar` (the canonical Juliet
  `data = URLEncoder.encode(data, ...)` sanitizer pattern). The
  `!(ins.lhs1 instanceof TacOperand.TacVar)` guard silently
  generalised to "any non-literal RHS kills the prior label" — and
  plumb processed clean TacVar RHS as non-literal, so the guard fired
  for them too. Drop the guard. The Juliet `_12` / `_21` / `_31` / `_41` /
  `_51` / `_81_goodB2G` family silently drops cross-TU taint without
  this extension. The trade-off: every `_81_goodB2G` that combines
  bad-source + URLEncoder.clean + addHeader now reports as a finding
  (FP), but every `_12` family that combines bad-source + URLEncoder.clean
  + addHeader in the path-sensitive bad branch now reports as a TP.
  Net: +71 TP, +23 FP for the 7 re-scanned CWE dirs.

## Files

- `D:\work\workspace\idea\engine\Plumb\.aethercode\_r759c_scan.ps1` — initial bulk scan (timed out)
- `D:\work\workspace\idea\engine\Plumb\.aethercode\_r759c_scan_resume.ps1` — failed (CWE36 prefix-substring match bug)
- `D:\work\workspace\idea\engine\Plumb\.aethercode\_r759c_scan_resume2.ps1` — final fix (CWE<num>_ prefix match)
- `D:\work\workspace\idea\engine\Plumb\.aethercode\r759c_bulk\*.r759c.ndjson` — 14 ndjson files (12 CWE × whole-dir scan)
- `D:\tmp\plumb_test\reports\r759c\eval_strict.log` — initial eval (wrong whitelist dir)
- `D:\tmp\plumb_test\reports\r759c\eval_strict2.log` — second eval (mixed baseline)
- `D:\tmp\plumb_test\reports\r759c\eval_strict3.log` — third eval (clean baseline = r758_bulk + CWE113 override)
- `D:\tmp\plumb_test\reports\r759c\R759C-NOTES.md` — initial notes (created before bulk scan finished)

## Next

R759-D (Z3 CWE191 integer bound): 72 FN at 96.44%. Implementing Z3
bidirectional bound for `MIN_VALUE / -1` and signed shift over/underflow.
Estimated +50-70 TP, 2-3 weeks.

## Final incremental eval (eval_strict_final.log)

| Metric | R758 (eval v8) | R759-C (incremental) |
|--------|---------------:|---------------------:|
| TP     | 1,819,906      | 1,085,030*           |
| FP     | 4,669,304      | 1,657,388*           |
| Precision | 0.281        | 0.396                |

*Note: TP/FP comparison is not 1:1 - R758 bulk was produced from a
multi-rule multi-scan-setup baseline (R720 v7 had additional AST/Loose
checkers enabled), while R759-C bulk is the R759-C engine scanning
12 targeted CWE dirs. The apples-to-apples per-file TP/FP delta on
the 7 re-scanned CWE dirs is:

| CWE | New TP files | New FP files |
|-----|-------------:|-------------:|
| CWE113 | 23 | 23 |
| CWE15  | 10 | 0  |
| CWE23  | 10 | 0  |
| CWE36  | 10 | 0  |
| CWE470 | 10 | 0  |
| CWE601 | 8  | 0  |
| **Total** | **71** | **23** |

These deltas are file-level (the same Juliet source file is hit by
plumb.taint.<X> finding in R759-C but not R758, or vice versa),
verified by grepping the rule-id pattern in both bulk directories.
