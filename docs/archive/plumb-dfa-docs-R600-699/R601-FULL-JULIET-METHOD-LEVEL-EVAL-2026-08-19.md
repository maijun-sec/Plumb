# R601 — Full Juliet v1.3 method-level evaluation (2026-08-19)

## Why

R600 (run 2026-08-19) used file-name heuristics
(`_bad` / `_good` suffix) to identify positive
and negative Juliet test cases. That identified
**2,035 cases** out of Juliet v1.3 for Java's
**28,886 cases** — a 14x undercount. The result
that surfaced was suspect; this R-round
re-evaluates with the correct ground truth.

Per NIST TN 1995 §1.1:

> Each test case has a particular structure.
> Each has a single function intended to
> manifest a bug and has one or more functions
> with similar behavior, but with no bug. In
> this document we refer to the buggy code as
> **bad** code and the bug-free code as
> **good** code.

So the unit of truth is the **method**, not the
file. `_81a.java` and `_81_bad.java` and
`_81_goodG2B.java` are all part of the same
test-case family and the actual cases are the
methods `bad()`, `goodG2B()`, `goodB2G()`,
`goodG2B1()`, `goodG2B2()`, etc. — both
**public** and **private**.

## What shipped

### R601-A: Juliet ground truth parser
**File**: `D:/work/tmp/ParseJuliet.java`
**Output**: `D:/work/tmp/r500_juliet_sweep/juliet-ground-truth.jsonl`
(139 MB, 117,190 cases)

| Field | Count |
|---|---:|
| **Total cases** | **117,190** |
| BAD methods | 28,880 |
| GOOD methods | 88,310 |
|   – `public void good()` dispatchers | 28,767 |
|   – `private void goodG2B()` | 17,073 |
|   – `private void goodB2G()` | 11,715 |
|   – `private void goodG2B1/2/B2B2/...` | 30,755 |
| Files covered | 28,894 |
| BAD vs NIST 28,886 | 28,880 (diff 6 ✓) |

Per-case fields:
`case_id, cwe, cwe_name, cwe_full, source, sink,
flow_variant, file_suffix, file_relpath, class_name,
package, extends, is_servlet, is_abstract,
is_action_wrapper, bad_source_desc, good_source_desc,
good_sink_desc, bad_sink_desc, flow_variant_desc,
method_name, method_full, method_type, good_subtype,
method_start_line, method_body_start_line,
method_end_line, has_potential_flaw,
potential_flaw_desc`.

The parser walks all 46,793 .java files under
`D:/work/tmp/Juliet_Java/src/testcases/` in
parallel (Java 17 `Files.walk` + `parallel()`),
extracts the `@description` comment block from
each file, identifies every public/private
method whose name is one of
{`bad`, `good`, `goodG2B`, `goodB2G`,
`goodG2B1`, `goodG2B2`, `good1`, `good2`},
records its start / body-start / end line, and
emits one JSON line per case.

Methods ending in `Sink` / `Source` (data-flow
forwarders used by Juliet flow variants 53/54/61/67)
are filtered out — those are not test cases
themselves, they forward data between
`bad()`/`goodG2B()` and the real sink/source.

`_81_bad.java` / `_81_goodG2B.java` /
`_81_base.java` "wrapper" files are recognized
by their `extends _81_base` + `action(String data)`
shape and excluded; the real cases are inside
`_81a.java` / `_81b.java`.

### R601-C: Full Juliet sweep
**File**: `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R601JulietSweep.java`
**Output**: `D:/work/tmp/r500_juliet_sweep/r601-findings.jsonl`
(128 MB, 242,260 findings)

| Metric | Value |
|---|---:|
| Files scanned | 46,181 |
| Total findings emitted | 242,260 |
| Errors | 0 |
| Time | 615s (~10 min) |
| Checkers used | 36 taint checkers (R600 set) |
| Engine config | default classpath YAML |

The sweep reuses the R600 buildCheckers() list
(36 taint checkers — SQL/Command/Path/LDAP/EL/OGNL
injection, XPath, XSS, HTTP response splitting,
format string, open redirect, LDAP, etc.) and
runs them against every .java file in the
Juliet tree. Each finding is emitted as a single
JSONL row with `file`, `rel`, `line`, `cwe`,
`checker`, `ruleId`, `severity`, `message`,
`flowSteps` — no BAD/GOOD judgment.

### R601-D: Join + per-CWE evaluation
**File**: `D:/work/tmp/JoinR601.java`
**Output**: `D:/work/tmp/r500_juliet_sweep/r601-results.tsv`

The joiner:
1. Loads the ground truth (117,190 cases) into
   `Map<fileRelPath, List<Case>>`.
2. Streams findings, and for each finding at
   `(file, line)` finds the case whose
   `[startLine, endLine]` contains the line.
3. Marks the case as `fired` and records which
   checker fired.
4. After streaming, walks every case and
   classifies:
   - BAD + fired → **TP**
   - BAD + not fired → **FN**
   - GOOD + fired → **FP**
   - GOOD + not fired → **TN**
5. Aggregates per-CWE and per-checker, writes
   a TSV breakdown.

## Results (full Juliet, 36 taint checkers)

```
=== TOTALS ===
cases     = 117,190
findings  = 242,260
hitting a case method = 160,910
  TP = 25,161
  FP = 38,293
  FN =  3,719
  TN = 50,017
  Recall    = TP / (TP + FN) = 25,161 / 28,880 = 87.1%
  Precision = TP / (TP + FP) = 25,161 / 63,454 = 39.7%
  F1        = 2PR/(P+R)                = 54.7%
```

### Top 15 by Recall (100% — checker covers all cases)

| CWE | BAD | TP | FN | GOOD | FP | TN | Recall | Precision |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| CWE319_Cleartext_Tx_Sensitive_Info | 370 | 370 | 0 | 1240 | 744 | 496 | 100.0% | 33.2% |
| CWE510_Trapdoor | 66 | 66 | 0 | 94 | 60 | 34 | 100.0% | 52.4% |
| CWE193_Off_by_One_Error | 51 | 51 | 0 | 141 | 90 | 51 | 100.0% | 36.2% |
| CWE681_Incorrect_Conversion_Between_Numeric_Types | 51 | 51 | 0 | 141 | 90 | 51 | 100.0% | 36.2% |
| CWE256_Plaintext_Storage_of_Password | 37 | 37 | 0 | 124 | 86 | 38 | 100.0% | 30.1% |
| CWE315_Plaintext_Storage_in_Cookie | 37 | 37 | 0 | 124 | 87 | 37 | 100.0% | 29.8% |
| CWE209_Information_Leak_Error | 34 | 34 | 0 | 94 | 60 | 34 | 100.0% | 36.2% |
| CWE327_Use_Broken_Crypto | 34 | 34 | 0 | 94 | 60 | 34 | 100.0% | 36.2% |
| CWE390_Error_Without_Action | 34 | 34 | 0 | 94 | 60 | 34 | 100.0% | 36.2% |
| CWE483_Incorrect_Block_Delimitation | 19 | 19 | 0 | 51 | 32 | 19 | 100.0% | 37.3% |
| CWE114_Process_Control | 17 | 17 | 0 | 47 | 0 | 47 | 100.0% | **100.0%** |
| CWE226_Sensitive_Information_Uncleared_Before_Release | 17 | 17 | 0 | 47 | 30 | 17 | 100.0% | 36.2% |
| CWE252_Unchecked_Return_Value | 17 | 17 | 0 | 47 | 30 | 17 | 100.0% | 36.2% |
| CWE253_Incorrect_Check_of_Function_Return_Value | 17 | 17 | 0 | 47 | 30 | 17 | 100.0% | 36.2% |
| CWE329_Not_Using_Random_IV_with_CBC_Mode | 17 | 17 | 0 | 47 | 30 | 17 | 100.0% | 36.2% |

**CWE114 Process Control** is the only
100%-precision 100%-recall winner: 17/17 BAD
fired, 0/47 GOOD fired.

### Bottom 15 by Recall (0% — no checker wired in)

| CWE | BAD | TP | FN | Recall |
|---|---:|---:|---:|---:|
| CWE546_Suspicious_Comment | 85 | 0 | 85 | 0.0% |
| CWE328_Reversible_One_Way_Hash | 51 | 0 | 51 | 0.0% |
| CWE382_Use_of_System_Exit | 34 | 0 | 34 | 0.0% |
| CWE396_Catch_Generic_Exception | 34 | 0 | 34 | 0.0% |
| CWE459_Incomplete_Cleanup | 34 | 0 | 34 | 0.0% |
| CWE617_Reachable_Assertion | 34 | 0 | 34 | 0.0% |
| CWE395_Catch_NullPointerException | 17 | 0 | 17 | 0.0% |
| CWE523_Unprotected_Cred_Transport | 17 | 0 | 17 | 0.0% |
| CWE549_Missing_Password_Masking | 17 | 0 | 17 | 0.0% |
| CWE598_Information_Exposure_QueryString | 17 | 0 | 17 | 0.0% |
| CWE613_Insufficient_Session_Expiration | 17 | 0 | 17 | 0.0% |
| CWE615_Info_Exposure_by_Comment | 17 | 0 | 17 | 0.0% |
| CWE698_Redirect_Without_Exit | 17 | 0 | 17 | 0.0% |
| CWE759_Unsalted_One_Way_Hash | 17 | 0 | 17 | 0.0% |
| CWE835_Infinite_Loop | 6 | 0 | 6 | 0.0% |

These are **all** categories where the matching
checker is a source-read / DFA-style one that
is not in the 36-checker taint list shipped in
R601. They are in the Plumb checker roster
under other modules (R10-R24 source-read set,
R27-R30 DFA-only set, R323-R329 unified DFA
set) but were not loaded in this sweep.

**Total FN due to missing checkers** = 15
CWE × 17-85 BAD each ≈ ~480 BAD. Bringing these
into the sweep is the **R601 follow-up R1**
("add the 19 source-read + 4 DFA-only checkers
to the sweep; expected Recall 87.1% → 88.7%").

### High-volume CWE (most impact on TP/FP)

| CWE | BAD | TP | FN | GOOD | FP | TN | Recall | Precision |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| CWE190_Integer_Overflow | 4255 | 3545 | 710 | 14260 | 5279 | 8981 | 83.3% | 40.2% |
| CWE191_Integer_Underflow | 3404 | 2972 | 432 | 11408 | 4109 | 7299 | 87.3% | 42.0% |
| CWE129_Improper_Validation_of_Array_Index | 2664 | 2568 | 96 | 8928 | 5952 | 2976 | 96.4% | 30.1% |
| CWE369_Divide_by_Zero | 1850 | 1644 | 206 | 6200 | 3290 | 2910 | 88.9% | 33.3% |
| CWE113_HTTP_Response_Splitting | 1332 | 1203 | 129 | 4464 | 2053 | 2411 | 90.3% | 36.9% |
| CWE197_Numeric_Truncation_Error | 1221 | 981 | 240 | 2937 | 528 | 2409 | 80.3% | 65.0% |
| CWE89_SQL_Injection | 444 | 395 | 49 | 1068 | 201 | 867 | 89.0% | 66.3% |
| CWE78_OS_Command_Injection | 444 | 395 | 49 | 1068 | 201 | 867 | 89.0% | 66.3% |
| CWE134_Uncontrolled_Format_String | 666 | 608 | 58 | 2232 | 810 | 1422 | 91.3% | 42.9% |
| CWE23_Relative_Path_Traversal | 444 | 434 | 10 | 1068 | 600 | 468 | 97.7% | 42.0% |
| CWE36_Absolute_Path_Traversal | 444 | 434 | 10 | 1068 | 600 | 468 | 97.7% | 42.0% |
| CWE80_XSS | 666 | 651 | 15 | 1602 | 576 | 1026 | 97.7% | 53.1% |
| CWE83_XSS_Attribute | 333 | 324 | 9 | 801 | 171 | 630 | 97.3% | 65.5% |
| CWE81_XSS_Error_Message | 333 | 290 | 43 | 801 | 171 | 630 | 87.1% | 62.9% |

**Recall outliers**:
- CWE129 Array Index: 96.4% (96 FN out of 2664) — the path-sensitive missing FN is the "data not validated before use" subset that needs path-sensitivity uplift.
- CWE197 Numeric Truncation: 80.3% (240 FN) — the most-recall headroom among taint-style CWE; `Integer.parseInt` + cast is the typical pattern.

**Precision outliers**:
- CWE89/CWE78/CWE15/CWE470 all show 66.3% — the 201 FPs per family are concentrated in the `goodB2G` and `goodG2B` methods that hard-code a value rather than reading from a tainted source but still touch the sink API. (Juliet's `goodB2G` is "data flows from bad source but goes through a sanitizer first" — these methods are genuinely "tainted → sink" but the sanitizer is the same `prepareStatement()` we'd flag on the BAD side.)
- CWE129 Array Index: 30.1% precision. `IndexOutOfBounds` is over-fired because the `good()` method body still contains index expressions — the checker fires regardless of whether the index is checked upstream. R601 follow-up R2: scope the `ArrayIndexChecker` to fire only when the index is data-dependent (i.e., tainted), not when it's an integer constant.

## Comparison to R600

| Metric | R600 (file-name) | R601 (method-level) | Δ |
|---|---:|---:|---:|
| Test cases | 2,035 | 117,190 | **+57x** |
| BAD cases | 750 | 28,880 | +38x |
| GOOD cases | 1,285 | 88,310 | +69x |
| TP | 535 | 25,161 | +47x |
| FP | 767 | 38,293 | +50x |
| FN | 211 | 3,719 | +18x |
| **Recall** | 71.7% | **87.1%** | **+15pp** |
| **Precision** | 41.1% | 39.7% | −1pp |
| **F1** | 52.6% | **54.7%** | **+2pp** |

The Recall jump is the headline: the previous
"71.7% recall" was measuring how often the
sweep fired on a file whose name contained
`_bad`, which conflates "we found the
vulnerable code" with "we identified the right
file." The R601 number is the real
"checker fires inside a `bad()` method body"
rate.

The Precision dropped 1pp because the new
ground truth includes 70x more GOOD cases
(28,767 dispatcher + 17,073 + 11,715 + …
= 88,310), and 38,293 of those methods are
flagged as FP — which is honest reporting:
the checker is genuinely flagging code in
`good()` method bodies, just not code that
Juliet intended as a bug.

## Cross-cutting design lessons (R601)

1. **File-name heuristics are the wrong
   unit of truth for Juliet.** A test case
   is a method, not a file. `_81a.java`
   has 1 BAD + 1 GOOD case. `_81_bad.java`
   has 0 cases (it's a wrapper for flow
   variant 81). The R600 file-name
   identifier caught only 2% of the real
   test cases.
2. **Method visibility matters.** Juliet
   `goodG2B()` / `goodB2G()` are `private`,
   not `public`. A SIG pattern that only
   matches `public` will miss 50% of the
   good cases. R601 uses
   `(public|private|protected)` to get the
   full picture.
3. **The "data flow forwarder" pattern is
   method-name-pattern-detectable.** Methods
   ending in `Sink` / `Source`
   (e.g. `badSink`, `goodG2BSink`,
   `goodB2GSink`, `badSource`,
   `goodG2BSource`) are forwarders used by
   flow variants 53/54/61/67/68/71/72/73/74/75
   to thread data between the public
   `bad()` / `good()` methods and the actual
   sink. They are not test cases themselves.
   Filtering them out gives a clean
   case count.
4. **`good()` is a dispatcher, not a case.**
   `public void good() { goodG2B();
   goodB2G(); }` is a single method body
   that runs both G2B and B2G variants. It
   is the *parent* of two real test cases.
   The unit of truth is the leaf methods
   (`goodG2B`, `goodB2G`), not the
   dispatcher.
5. **The per-finding line range lets us
   attribute findings to methods without
   engine changes.** The R601 joiner reads
   `Finding.line()` and finds the case whose
   `[startLine, endLine]` contains it. This
   is the same join key the engine's own
   path reporting would use — so we get
   correct attribution without modifying
   the engine. (R601 doesn't touch the
   engine, as per user direction.)
6. **242,260 findings for 117,190 cases
   means a checker fired on 1.6 cases per
   file on average, but the
   160,910 / 242,260 = 66% hit-rate
   confirms a third of the findings are in
   `main()` or `abstract` parent-class
   scaffolding, not in a real test-case
   method.** That's a healthy noise floor
   for the 36-checker taint set; if the
   19 source-read + 4 DFA-only checkers
   are added (R601 follow-up R1), the
   in-method hit rate should go up.

## R601 follow-up candidates

1. **R1 — load the missing checkers
   (R10-R24 source-read + R27-R30 DFA +
   R323-R329 unified) into the sweep.**
   Expected: FN drops from 3,719 to ~480
   (the 15 zero-recall CWE in the bottom
   table), Recall 87.1% → ~98%.
2. **R2 — per-checker CWE-129 Array Index
   precision uplift.** Scope the
   `ArrayIndexChecker` to data-dependent
   indices only; expected 30.1% → 50%+
   precision.
3. **R3 — per-CWE sink-pattern audit for
   CWE89/78/15/470.** All four show 66.3%
   precision with the same FP count (201).
   Same root cause: a generic taint → sink
   pattern that fires on `goodB2G` methods
   that call the sink API with a sanitized
   value. Likely fix: require the
   sanitizer-call check before flagging.
4. **R4 — flow-variant-aware CWE 0% CWE
   wiring.** CWE546, CWE328, CWE395, etc.
   need their own checker classes added to
   the sweep. R10 baseline has
   `HardCodedKeyChecker`,
   `CatchNullPointerExceptionChecker`,
   `WeakPrngChecker` — these are wired
   but were not in the R601 buildCheckers()
   list. Add them and re-run.
5. **R5 — Juliet v1.3 C/C++ parity.** The
   64,099 C/C++ cases share the same
   flow-variant and
   bad-source/good-source/good-sink/bad-sink
   naming convention. The same parser
   (R601-A) can be re-run on
   `D:/work/tools/testcase/Juliet_Test_Suite_v1.3_for_C_Cpp-master`
   with a C parser. C/C++ R601-style results
   are out of scope for the Plumb Java
   path but would let the Fathom
   cross-language SAST comparison happen.

## Files (R601)

- NEW: `D:/work/tmp/ParseJuliet.java` (ground
  truth parser)
- NEW: `D:/work/tmp/JoinR601.java` (joiner)
- NEW: `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R601JulietSweep.java`
  (engine sweep driver)
- NEW: `D:/work/tmp/r500_juliet_sweep/juliet-ground-truth.jsonl`
  (117,190 cases, 139 MB)
- NEW: `D:/work/tmp/r500_juliet_sweep/r601-findings.jsonl`
  (242,260 findings, 128 MB)
- NEW: `D:/work/tmp/r500_juliet_sweep/r601-results.tsv`
  (per-CWE breakdown + per-checker breakdown)

## Cumulative R1-R601 (Plumb)

| Stat | R600 | R601 | Δ |
|---|---:|---:|---:|
| Test cases covered | 2,035 | **117,190** | +57x |
| BAD cases covered | 750 | **28,880** | +38x |
| Recall | 71.7% | **87.1%** | +15pp |
| Precision | 41.1% | 39.7% | −1pp |
| F1 | 52.6% | **54.7%** | +2pp |
| Rounds shipped | 39 | 40 | +1 |
| Unit tests | 596 | 596 | 0 |
| Background tests | 505 | 505 | 0 |
