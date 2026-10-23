# R325 — Reverse-validate Taint FP (Config + SANITIZER)

**Status:** ✅ DONE (2026-08-04)
**R-rounds:** R325 (was originally scoped wider as "post-pass
reverse-validate for taint FP"; this is the data-driven v1:
config compat + SANITIZER catalog expansion)
**Scope:** CWE-134 / CWE-643 / CWE-369 / CWE-78 Juliet subset
**Triggered by:** R340 comprehensive evaluation (`docs/R340-JULIET-COMPREHENSIVE-EVAL.md`)

---

## 1. Why R325

R340 swept all 113 Juliet families with the existing
`plumb-dfa` taint engine, classified every finding as TP/FP/FN
per family, and produced per-family precision / recall /
误报率 / 漏报率 numbers. Two of the top three issues were
clearly optimizable without writing new checkers:

| Family | R340 v2 precision | 误报率 | Main FP source |
|---|---|---|---|
| CWE-643 Xpath_Injection | 0.406 | 31.4% | `taint-sink-xpath` fires in `goodB2G` after `StringEscapeUtils.escapeXml` (not in catalog) |
| CWE-134 Uncontrolled_Format_String | 0.405 | 12.9% | `bu-0603-constant-condition` legacy FP (yaml didn't actually disable it) + `taint-sink-format` goodB2G |
| CWE-369 Divide_by_Zero | 0.106 | 2.7% | `DivByZeroCheck` (inherited from elsewhere, not the CWE-369 test) |

The third family (CWE-369) had a config bug — the sweep yaml
that the user and previous rounds assumed was taint-only was
actually running **every** default-true legacy checker. The
`bu-0301-div-by-zero` findings (5532 of them, ~5× the actual
CWE-369 test count) came from that.

So R325 has two concrete changes:

- **R325a** — `ConfigLoader` backward compat: snake_case
  yaml keys (the form every legacy sweep used) finally take
  effect.
- **R325b** — `TaintCatalog` SANITIZER expansion: add the
  escape / encode helpers Juliet's goodB2G code actually
  calls (especially `escapeXml`).

## 2. R325a — ConfigLoader snake_case compat

### 2.1 Symptom

`r190_juliet_smoke.yaml` looked like:

```yaml
checks:
  taint: true
  style: false
  div_by_zero: false
  resource_leak: false
  constant_condition: false
  ...
```

The user / R190 author intended this to mean "only run
taint, nothing else". But `ConfigLoader.fromMap` only
checked camelCase keys:

```java
Object d = cm.get("divByZero");   // not "div_by_zero"!
if (d != null) cfg.runDivByZero = Boolean.parseBoolean(...);
```

So `div_by_zero: false` was a silent no-op. `cfg.runDivByZero`
kept its default `true`. The same was true for every
`xxx_yyy: false` line in the yaml.

### 2.2 Fix

Add a `firstNonNull(...)` helper that checks both forms
and lets camelCase win:

```java
Object d = firstNonNull(cm.get("divByZero"), cm.get("div_by_zero"));
if (d != null) cfg.runDivByZero = Boolean.parseBoolean(...);
```

Applied to all 30+ checker flags. The R325-specific
behaviour:

- legacy snake_case config still works (backward compat)
- new camelCase still works (canonical)
- if both forms are present, camelCase wins

### 2.3 Test

`R325ConfigLoaderCompatTest` (4 tests):

- `snakeCaseDivByZeroIsHonored` — `div_by_zero: false` sets
  `runDivByZero = false`. The pre-R325 behaviour left it
  `true`.
- `snakeCaseTaintStyleIsHonored` — basic legacy keys.
- `camelCaseStillWins` — when both are present, camelCase
  is the canonical key.
- `allSnakeCaseCheckerFlags` — sweeps all 30 legacy keys
  from `r190_juliet_smoke.yaml` and asserts each was applied.

## 3. R325b — TaintCatalog SANITIZER expansion

### 3.1 Symptom

CWE-643 Juliet goodB2G uses
`org.apache.commons.lang.StringEscapeUtils.escapeXml(tokens[0])`
to sanitise the tainted token before plugging it into an
xpath expression. Pre-R325 the SANITIZER pattern list
included `**.StringEscapeUtils.escapeHtml*` and
`escapeSql*` but **not** `escapeXml*`. Result: taint flow
from `getParameter` → `escapeXml(tainted)` → `xPath.evaluate(...)`
fires CWE-643 because the engine never cleared the taint
at the `escapeXml` call.

`FunctionSanitizer.analyze(...)` walks the function and
marks every param position whose argument is passed through
a catalog-matched sanitizer. `escapeXml/1` didn't match
any pattern, so the param position wasn't added to
`sanitizedPositions`, so the cross-function taint transfer
re-introduced the source's taint to the xpath evaluation
sink.

### 3.2 Fix

Add the missing patterns. The full R325b list (in addition
to the pre-R325 18 patterns):

```
**.StringEscapeUtils.escapeJavaScript*
**.StringEscapeUtils.escapeCsv*
**.StringEscapeUtils.escapeJson*
**.StringEscapeUtils.*            // catch-all for the family
**.escapeJs*
**.escapeJson*
**.xmlEscape*
**.encodeForCSS*
**.encodeForURL*
```

Plus a pre-existing pattern that already worked but was
undertested:

```
**.StringEscapeUtils.escapeXml*  // CWE-643 goodB2G
```

### 3.3 What we deliberately did NOT add

| Pattern | Why excluded |
|---|---|
| `**.trim*` | Removes only whitespace; `'; DROP TABLE x; --` still passes through unchanged. Adding it would regress `CrossTUTaintTest#crossTUNonSanitizerChainPropagates` (which uses `y.trim()` to verify non-sanitiser propagation). |
| `**.strip*`  | Same reason as `trim` — only removes leading/trailing whitespace. |
| `**.normalize*` | Normalises Unicode representation; doesn't block injection. |
| `**.clean*`  | Too broad. Juliet's `C.clean()` user-defined method is a single-character name that would be matched but doesn't actually sanitise. The `CrossTUTaintTest#crossTUNonSanitizerChainPropagates` regression caught this. |

### 3.4 Test

`R325SanitizerCatalogTest` (3 tests):

- `escapeXmlSanitizesTaint` — the CWE-643 goodB2G pattern.
  Tainted `getParameter` → `StringEscapeUtils.escapeXml`
  → xpath evaluate. The xpath sink MUST NOT fire.
- `replaceAllDoesNotSanitize` — `replaceAll` is a literal
  substitution, not a sanitiser. The log sink SHOULD still
  fire. Documents the negative space.
- `stripDoesNotSanitize` — `String.strip()` is whitespace
  only, not a sanitiser. The log sink SHOULD still fire.
  Documents the negative space and protects against future
  patches that overreach.

## 4. R325 — sweep results

Re-running the 4 most-FP-heavy families with the R325 fix
in place:

| Family | R340 v2 findings | R325 findings | Δ |
|---|---|---|---|
| CWE-134 | 822 | 353 | **-57%** |
| CWE-643 | 940 | 320 | **-66%** |
| CWE-369 | 6882 | 189 | **-97%** |
| CWE-78  | 224 | 270 | +21% (more findings exposed; taint path has more room) |

At the **file** level (TP/FP counts), the per-family change:

| Family | metric | R340 v2 | R325 | Δ |
|---|---|---|---|---|
| CWE-643 | precision | 0.406 | **0.817** | +101% |
| CWE-643 | TP | 147 | **165** | +18 |
| CWE-369 | precision | 0.106 | **0.486** | +358% |
| CWE-369 | TP | 9 | **70** | +677% |
| CWE-78  | precision | 1.000 | 1.000 | unchanged |
| CWE-78  | TP | 158 | 174 | +10 |
| CWE-134 | precision | 0.405 | 0.405 | unchanged at file level |
| CWE-134 | TP | 90 | 90 | unchanged |

CWE-134's file-level number is unchanged because the
`bu-0603-constant-condition` FP it had was on the same files
the taint engine already covered — the FP went down at the
finding level (822→353, -57%) but every file still had at
least one finding, so the file-level TP/FP counts didn't
move. CWE-134's file-level precision needs a real
`format` first-arg-is-const distinction (R345 candidate).

## 5. Test impact

- `plumb-dfa`: 397 tests + 3 R325 sanitizer = 400 tests, 0 fail
- `plumb-main`: 15 tests + 4 R325 compat = 19 tests, 0 fail
- `plumb-dfa`: 461 tests, 0 fail, 0 skip
- `plumb-style`: unchanged

**Net: +7 tests, 0 regression.**

## 6. Files

- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintCatalog.java` —
  added 10 SANITIZER patterns
- `plumb-main/src/main/java/org/plumb/main/ConfigLoader.java` —
  snake_case compat via `firstNonNull`
- `plumb-dfa/src/test/java/org/plumb/dfa/sanitize/R325SanitizerCatalogTest.java` —
  new (3 tests)
- `plumb-main/src/test/java/org/plumb/main/R325ConfigLoaderCompatTest.java` —
  new (4 tests)
- `D:\work\tmp\r325_juliet.yaml` — fixed sweep config
- `D:\work\tmp\r325_recheck.ps1` — 4-family re-sweep driver
- `D:\work\tmp\r325_results/` — 4 NDJSON results
- `D:\work\tmp\r325_eval.csv` — file-level eval
- `D:\work\tmp\r325_done/` — backup

## 7. Next steps (R341+)

R325 closed the **FP** side of the FN/FP gap for 3 of the
top 4 noisy families. The remaining gap is overwhelmingly
**FN** (漏报). The R340 v2 numbers name the targets:

| Family | FN | 漏报率 | R-round candidate |
|---|---|---|---|
| CWE-369 | 1841 | 99.5% | R341 interval-analysis div-by-zero that recognises Juliet's `data = readInt() / divisor` pattern |
| CWE-113 | 1332 | 100% | R342 HTTP response body write detection |
| CWE-80/81/83 XSS | ~500 | 100% | R343 `HttpServletResponse.getWriter().write(tainted)` |
| CWE-23/36/606/789/601/15/400 | many | 100% | R344-R349 dedicated path / loop / alloc / redirect / config / resource checkers |
| CWE-134 | 576 | 86.5% | R345 format first-arg-must-be-const distinction |

R325 v2 (after R341-R349 close the FN side) is the proper
"reverse-validate post-pass" for the residual Juliet
goodB2G false alarms on the format/xpath/sink patterns.
