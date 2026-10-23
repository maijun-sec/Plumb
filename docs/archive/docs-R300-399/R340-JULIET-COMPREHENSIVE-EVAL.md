# R340 — Juliet Java Test Suite Comprehensive Evaluation

**Status:** ✅ DONE (2026-08-04)
**R-rounds:** R340 (eval) + R325 (config + sanitizer fixes)
**Scope:** 113 Juliet Java CWE families, 46323 case files
**Output:** `D:\work\tmp\r340_eval_v2.csv` (108 families w/ data)
**R-docs:** this file + `R325-REVERSE-VALIDATE-TAINT-FP.md`

---

## 1. Background and Motivation

Pre-R340, the engine had been growing checker coverage through
~330 R-rounds, but every round was a "metric win" framed in
terms of "this family now has findings" or "this check is wired
in". There was no single document that answered the user's
honest question:

> 跑全量的 juliet 用例,我要知道每类 cwe 的 正报数量 和 误报
> 数量,给出准确率和误报率,然后结合误报和漏报综合进行优化

R340 answers "what do we actually have?" per CWE family, and
R325 implements the first round of optimizations that those
numbers motivated.

## 2. Methodology

### 2.1 Source data

Two sweeps, both already on disk:

1. **r190_v3_results/** — legacy `plumb-dfa` engine, single
   per-family NDJSON, ~13k findings, 113 families.
2. **r328_results/** — new `plumb-dfa` engine
   (R312-R324 + R329), ~474 findings, 14 DFA-related families.

The two were combined into `r340_combined/` (112 families
with findings, 18136 unique findings across the union).

### 2.2 Per-finding classification

For every Juliet source file referenced in a finding, we parse
the file's method ranges using a brace-matching pass. Each
finding's source line is mapped to a containing method:

- inside `bad()`         → counts toward **TP** for the family
- inside `good*()`       → counts toward **FP** for the family
- inside neither         → ignored (utility methods etc.)

A finding's CWE field is matched against the family's CWE
number so cross-rule findings (e.g. a `bu-0115-taint-sink-ognl`
firing in a CWE-134 file) are attributed correctly.

### 2.3 File-level evaluation (the metric)

For each CWE family, we walk **every** `.java` file under the
family directory (recursively, excluding `antbuild/` and
`ServletMain.java`). The case-file count is roughly 530 per
family on average, with CWE-134 alone having 1100 files.

For each case file:

- `bad()` with at least one finding whose CWE matches the
  family → **TP_file** (correctly identified vulnerable code)
- `bad()` with zero matches                       → **FN_file**
  (missed vulnerability)
- any `good*()` with at least one match           → **FP_file**
  (false alarm on clean code)
- any `good*()` with zero matches                 → **TN_file**
  (correct silence)

The pre-R340-v2 analyzer used only referenced files, which
under-counted FN: a Juliet file with zero findings wasn't
counted at all. The R340-v2 fix switched `case_files` to
**all** `.java` files in the family directory. See
"Methodology fix" below.

### 2.4 Metrics

| Metric | Formula | Meaning |
|--------|---------|---------|
| **正报 (TP)** | matching finding inside `bad()` | correct detection |
| **误报 (FP)** | matching finding inside `good*()` | false alarm |
| **漏报 (FN)** | `bad()` with no matching finding | missed vulnerability |
| **正确沉默 (TN)** | `good*()` with no matching finding | clean silence |
| **准确率 (precision)** | TP / (TP + FP) | how often is a flag right |
| **召回率 (recall)**    | TP / (TP + FN) | what fraction of vulns are caught |
| **误报率 (FP rate)**   | FP / (FP + TN) | how often is a clean file flagged |
| **漏报率 (FN rate)**   | FN / (FN + TP) | what fraction of vulns are missed |
| **composite**          | (FN_rate + FP_rate) / 2 | balanced error budget |

The composite ranks families worst-first so we can prioritise
optimization effort.

## 3. Methodology fix (R340-v2)

The first pass of the analyzer restricted `case_files` to
"referenced files" — files that produced at least one
finding. A Juliet case file with **zero** findings was dropped,
so its `bad()` was never counted as FN. The numbers came out
as:

| Family | TP | FP | FN |
|---|---|---|---|
| CWE-134 | 90 | 132 | 3 (!!) |
| CWE-78  | 158 | 0  | 18 |
| CWE-369 | 9   | 76 | 71 |

The CWE-134 FN=3 was obviously wrong: Juliet has 666 `bad()`
methods for CWE-134. The fix:

- `case_files` is now the set of **every** `.java` file under
  the family directory, minus `antbuild/` and `ServletMain.java`.
- A case file with zero findings is now correctly counted as
  FN (its `bad()` was missed) or TN (its `good*()` was
  correctly silenced).

After the fix, the v2 numbers look more honest:

| Family | TP | FP | FN | precision | recall | 误报率 | 漏报率 |
|---|---|---|---|---|---|---|---|
| CWE-134 | 90 | 132 | 576 | 0.405 | 0.135 | 12.9% | 86.5% |
| CWE-78  | 158 | 0  | 270 | 1.000 | 0.369 | 0.0%  | 63.1% |
| CWE-369 | 9   | 76 | 1841 | 0.106 | 0.005 | 2.7%  | 99.5% |

## 4. Top issues (R340 v2)

### 4.1 Top families by误报率 (FP rate)

| Family | TP | FP | 准确率 | 误报率 | 备注 |
|---|---|---|---|---|---|
| CWE-643 Xpath_Injection | 147 | 215 | 0.406 | 31.4% | goodB2G 用 `StringEscapeUtils.escapeXml`,未在 R325 之前的 SANITIZER list |
| CWE-134 Uncontrolled_Format_String | 90 | 132 | 0.405 | 12.9% | `bu-0603-constant-condition` 大量 FP + taint-sink-format 在 goodB2G fire |
| CWE-614 Sensitive_Cookie_Without_Secure | 1 | 2 | 0.333 | 11.8% | taint-sink-header 命中 http-only 检测 |
| CWE-572 Thread_run_Instead_of_start | 1 | 1 | 0.500 | 5.9% | single-file 检测,ratio 噪声 |
| CWE-369 Divide_by_Zero | 9 | 76 | 0.106 | 2.7% | DivByZeroCheck + taint 双重 fire |

### 4.2 Top families by漏报率 (FN rate)

| Family | TP | FN | 召回率 | 漏报率 | 备注 |
|---|---|---|---|---|---|
| CWE-369 Divide_by_Zero | 9 | 1841 | 0.005 | 99.5% | 大量 Juliet 家族未触发,因为 taint source 通常不直接接 div |
| CWE-113 HTTP_Response_Splitting | 0 | 1332 | 0.000 | 100.0% | 没专用 checker (sink 列表无 HTTP response body write) |
| CWE-23/36/80/81/83/601/15/259/400/606/789/197/506/319/643 | 0 or low | many | 0.000 | 100.0% | 部分有 coverage,大部分 0 TP |
| CWE-614 Sensitive_Cookie_Without_Secure | 1 | 16 | 0.06 | 94.1% | 检查 http-only attribute,Juliet 测试 rare pattern |

### 4.3 Root-cause analysis

The R340 sweep was actually a **taint-only** sweep — the
sweep yaml `r190_juliet_smoke.yaml` set `taint: true` and
turned off the obvious noise (`style`, `div_by_zero`,
`constant_condition`, ...). But the snake_case keys in that
yaml (`div_by_zero`, `constant_condition`, ...) were silently
ignored by `ConfigLoader` because the loader only checked
camelCase keys (`divByZero`, `constantCondition`). So **every
default-true checker ran in the sweep**, and they produced the
bulk of the FP load.

That single bug accounted for:

- `bu-0603-constant-condition` (10197 findings) — CWE-570/571
  noise, mostly on Juliet's `if (false)` / `if (true)` dead-code
  blocks that R115 introduced as incidental flow controls.
- `bu-0301-div-by-zero` (5532 findings) — Juliet denominators
  that DivByZeroCheck correctly flagged as `CWE-369`, but
  attributed incorrectly to other families.
- `bu-0501-insecure-comms` (3885 findings) — overly broad
  socket / URL matchers.
- `bu-0115-taint-sink-ognl` (3038 findings) — Juliet's
  `SpelExpressionParser` patterns are different from the
  production OGNL pattern.
- `bu-0503-insecure-cookie` (1944 findings) — same.

The CWE-643 / CWE-134 FP was a different problem: the
`TaintCatalog` SANITIZER pattern list missed
`StringEscapeUtils.escapeXml`, so Juliet's goodB2G cleanup
helper wasn't recognised.

R325 fixes both.

## 5. R325 — reverse-validate post-pass (FP reduction)

The user asked to "结合误报和漏报综合进行优化". R325 is
the first round of that, focused on the **FP** side of the
ledger — the FN side needs new checkers (R341+).

### 5.1 R325a — config backward compat

`ConfigLoader` previously only recognised camelCase keys.
`r190_juliet_smoke.yaml` used snake_case, so every
"`xxx_yyy: false`" was a no-op. The R325 fix:

- Add a `firstNonNull(...)` helper that accepts both forms.
- camelCase wins when both are present.
- All 30+ legacy checker flags now work from the legacy
  snake_case yaml.

Tested by `R325ConfigLoaderCompatTest` (4 tests, all pass).

### 5.2 R325b — TaintCatalog SANITIZER expansion

Added patterns that the pre-R325 list missed:

- `**.StringEscapeUtils.escapeXml*` — CWE-643 Juliet
  goodB2G pattern.
- `**.StringEscapeUtils.escapeJavaScript*`,
  `escapeCsv*`, `escapeJson*` — sibling escape helpers.
- `**.StringEscapeUtils.*` — catch-all for the
  StringEscapeUtils family.
- `**.escapeJs*`, `**.escapeJson*`, `**.xmlEscape*` — direct
  aliases of common libraries.
- `**.encodeForCSS*`, `**.encodeForURL*` — OWASP Encoder
  sibling methods.

Deliberately **not** added:

- `trim`, `strip`, `normalize` — they only remove whitespace
  or normalize representation; they do **not** block
  injection. Adding them would regress
  `CrossTUTaintTest#crossTUNonSanitizerChainPropagates`.
- `clean` — too broad. A user-defined `clean()` method
  in `CrossTUTaintTest#crossTUNonSanitizerChainPropagates`
  actually just calls `trim()`, not anything that blocks
  injection. The pattern would over-sanitize.

Tested by `R325SanitizerCatalogTest` (3 tests, all pass).

### 5.3 R325 — sweep results on 4 noisy families

Re-running the 4 most-FP-heavy families with the R325 fix
in place, against the same Juliet case files:

| Family | R340 v2 findings | R325 findings | Reduction |
|---|---|---|---|
| CWE-134 | 822 | 353 | -57% |
| CWE-643 | 940 | 320 | -66% |
| CWE-369 | 6882 | 189 | **-97%** |
| CWE-78  | 224 | 270 | +21% (more findings exposed because taint has more room) |

At the file level (TP/FP counts), the most striking wins:

| Family | R340 v2 precision | R325 precision | R340 v2 TP | R325 TP |
|---|---|---|---|---|
| CWE-643 | 0.406 | **0.817** | 147 | 165 |
| CWE-369 | 0.106 | **0.486** | 9   | 70 |
| CWE-78  | 1.000 | 1.000 | 158 | 174 |
| CWE-134 | 0.405 | 0.405 | 90  | 90  |

CWE-643's precision doubled because the `escapeXml` sanitizer
now correctly clears taint in the goodB2G `String
username = StringEscapeUtils.escapeXml(tokens[0])` pattern,
suppressing the xpath-evaluate false alarm.

CWE-369's TP jumped 7× because turning off the
inherited-from-other-families div-by-zero noise lets the
legitimate CWE-369 findings show through (Juliet's
intentional div-by-zero test files).

CWE-78 is unchanged at 100% precision: the taint-only engine
already nails that case.

CWE-134 is unchanged at the file level: the
`constant-condition` FP was finding *additional* files rather
than *additional* findings in the same files. The 822→353
reduction is a per-finding cleanup, not a per-file cleanup.
CWE-134 still needs a real `format` first-arg-is-const
distinction (R341 candidate) to push the file-level precision
further.

## 6. Aggregate (file-level, R340 v2)

Across the 108 families with data:

```
TP (正报)     : 737
FP (误报)     : 437
FN (漏报)     : 15600
TN (正确沉默) : 23546
准确率 (precision) = 0.628
召回率 (recall)    = 0.045
误报率 (FP rate)   = 0.018
漏报率 (FN rate)   = 0.955
```

- **precision 0.628** means: when the engine flags something,
  it's right 63% of the time. That's acceptable for a
  v1 release.
- **recall 0.045** means: the engine catches about 5% of
  the actual vulnerabilities across the full Juliet corpus.
  This is the headline gap that motivates the R341+
  "add new checkers" push.

## 7. Next steps (R341+)

The R340 numbers draw a clear roadmap:

| Direction | R-round | Target |
|---|---|---|
| CWE-369 FN (99%漏报) | R341 | Add a real interval-analysis div-by-zero that recognizes Juliet's "data = readInt() / divisor" pattern |
| CWE-113 FN (100%漏报, 1332 files) | R342 | HTTP response body write detection |
| CWE-80/81/83 XSS FN (100%漏报) | R343 | XSS sink in `HttpServletResponse.getWriter().write()` and `getOutputStream().write()` paths |
| CWE-23/36 Path_Traversal FN (100%漏报) | R344 | `new File(tainted)` sink — already in catalog as `bu-0103-taint-sink-path` but the taint engine isn't reaching it for some Juliet patterns |
| CWE-134 file-level precision | R345 | Format-string first-arg-must-be-const distinction in `TaintAnalysis#transferInvoke` (case SINK) |
| CWE-643 residual FP | R346 | Reverse-validate: if a taint-xpath finding is followed by an `escapeXml` call within 5 lines, downgrade |
| CWE-606 Unchecked_Loop_Condition FN (100%漏报) | R347 | Loop condition tainted from request → infinite / untrusted loop |
| CWE-789 Uncontrolled_Mem_Alloc FN (100%漏报) | R348 | `new int[tainted]`, `new byte[tainted]` patterns |
| CWE-601 Open_Redirect FN (100%漏报) | R349 | `sendRedirect(tainted)` already in catalog as `bu-0117-taint-sink-open-redirect`; investigate why it's not firing |

## 8. Artifacts

- `D:\work\tmp\r340_analyze.py` — analyzer (TP/FP/FN per family)
- `D:\work\tmp\r340_aggregate.py` — combines r190 + r328 sweeps
- `D:\work\tmp\r340_rank.py` — composite ranking
- `D:\work\tmp\r340_summary.py` — friendly per-family report
- `D:\work\tmp\r340_eval_v2.csv` — primary 108-family CSV
- `D:\work\tmp\r340_combined/*.ndjson` — 112 family NDJSON union
- `D:\work\tmp\r340_summary/{summary,top_fp,top_fn}.txt` —
  human-friendly reports (UTF-8)
- `D:\work\tmp\r325_results/*.ndjson` — R325 re-sweep on
  4 noisy families
- `D:\work\tmp\r325_eval.csv` — R325 file-level eval
- `D:\work\tmp\r340_done\` — backup

## 9. Test impact

- `plumb-dfa`: 397 tests, 0 fail, 1 skip (pre-existing)
- `plumb-dfa`: 461 tests, 0 fail, 0 skip
- `plumb-main`: 19 tests, 0 fail, 0 skip (R325 added 4)
- `plumb-style`: unchanged

**Net: +7 tests (4 R325 ConfigLoader + 3 R325 Sanitizer), 0 regression.**

## 10. Pitfalls learned

1. **Snake_case yaml was silently ignored.** The legacy
   `r190_juliet_smoke.yaml` looked like a careful taint-only
   config, but every `xxx_yyy: false` was a no-op. The bulk
   of the 10197 constant-condition findings and the 5532
   div-by-zero findings were inheritance, not user choice.
2. **`clean` is too broad a SANITIZER pattern.** The
   `CrossTUTaintTest#crossTUNonSanitizerChainPropagates`
   regression caught it — a user-defined `C.clean()` that
   just calls `trim()` would over-sanitize.
3. **`trim` / `strip` / `normalize` are NOT sanitizers.**
   Removing whitespace doesn't block `'; DROP TABLE x; --`.
   These belong in the dataflow-aware reverse-validate
   post-pass (R325 future work), not the catalog match.
4. **Juliet flow variants: goodG2B and goodB2G use different
   cleanup helpers.** goodG2B swaps the *source* (e.g. a
   hardcoded string), goodB2G keeps the bad source but uses
   a *sanitizer* on the data. The TaintCatalog sanitizer
   list is what catches goodB2G; source substitution is
   invisible to the engine (it doesn't see literal data
   through `getParameter`).
5. **CWE-369 is a "denominator" family.** Most other
   families' Juliet files have a taint source → sink chain.
   CWE-369 has a taint source → *denominator* chain. The
   div-by-zero detection must run on tainted data through
   `/` or `%` — which the current engine has, but the
   DivByZeroCheck reports CWE-369 even on data that isn't
   tainted, polluting the cross-family counts.
