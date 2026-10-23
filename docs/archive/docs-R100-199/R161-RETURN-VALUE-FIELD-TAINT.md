# R161 — Cross-Function DTO Field Taint Propagation (2026-07-31)

## Background

R158 step 1 made the 4 cross-TU caches (analyzing / summaryCache /
exitTaintCache / returnTaintCache) key on `mergedKey(fn)` instead of bare
`fn.signature()`. R158 step 4 also fixed the file-lookup + dedup-key path
to use `mergedKey(fn)`. Together these unblocked the cross-TU flow 81+
chain (CWE-78 +200 TP, 100x unlock). R159 added the JNDI `lookup/*` sink
pattern for CVE-2021-44228 / Log4Shell. R159 also fixed the depth-tracking
across `pullReturnTaint` recursion (R158 step 1's higher cache hit rate
made `pullReturnTaint` recurse 1024+ frames and overflow the stack).

R160 ran the 4-CVE detection evaluation and found 4/4 CVEs detected —
but with 2 specific gaps that were the target of R161:
- **log4shell L71**: `log.info("login attempt: " + form.username)` —
  the DTO field `form.username` is tainted via `readForm(req)` → `f.username =
  readDeep(req)` → `return f;` → `Form form = readForm(req)`. The taint
  path is **cross-function** (helper writes field, returns object, caller
  reads field).
- **fastjson L73**: `JSON.parseObject(p.value)` — same pattern with
  `readAsDto(req)`.

Pre-R161, both these L71 / L73 calls were missed by the taint analysis
even though the catalog sinks (`log.info`, `JSON.parseObject`) recognized
the call sites. The reason is structural: when the helper does
`Form f = new Form(); f.username = tainted; return f;` and the caller
does `Form form = readForm(req); form.username;` the caller's
`form` local has **no base taint** (the helper's `f` was clean — only
its field was dirty), and the caller's `form` had **no field taint**
either (the call-site MOVE / use-site read were the only places the
field taint could appear, and neither copied it). The base-taint path
(`pullReturnTaint`) only handled scalar taint; the fieldMap propagation
(`propagateFieldMapToCaller`, R145) only handled callee-param →
caller-arg. Neither handled the **return-value → caller's dest**
half of the same story.

R161 closes this gap.

## Implementation

### Sub-fix 1: `returnFieldMapCache` field

`TaintAnalysis.java` adds a parallel cache to `returnTaintCache`,
keyed identically (`mergedKey(callee)` × `entryTaintFingerprint(entryTaint)`)
but holding the field map (`Map<fieldName, Set<String>>`) of the
callee's RETURN lhs. The structure mirrors `returnTaintCache`:

```java
private final Map<String, Map<String, Map<String, Set<String>>>> returnFieldMapCache;
```

Initialized in the constructor, cleared in `run()` and `resetCaches()`.

### Sub-fix 2: `pullReturnFieldTaint` method

The new helper `pullReturnFieldTaint(callee, entryTaint) -> Map<String, Set<String>>`
mirrors `pullReturnTaint`'s structure exactly:
1. Compute the entry-taint fingerprint (`fp`).
2. Check `returnFieldMapCache` first.
3. If not cached, check the exit-taint cache fast-path (R159
   sub-fix B's optimization): if the callee's exit-taint is already
   in the cache, extract the field maps of every RETURN's lhs
   directly from the cached exit-taint. The cached exit-taint's
   `fieldMapOf(RETURN.lhs1)` IS the field taint of the return
   value at the moment of the RETURN.
4. If still not cached, run a fresh worklist (same shape as
   `pullReturnTaint`'s slow path) and walk every RETURN's lhs
   field map at the moment of the RETURN.
5. Cache and return.

For multiple RETURNs, the field map is the union across all of
them — same shape as `pullReturnTaint`'s union over base taint.

### Sub-fix 3: `traceIntoCallee` wires the return field taint

After the existing `in.with(ins.dest, returnTaint)`, R161 adds:

```java
final Map<String, Set<String>> returnFields =
        this.pullReturnFieldTaint(callee, calleeEntry);
if (returnFields != null && !returnFields.isEmpty()) {
    for (final Map.Entry<String, Set<String>> fe : returnFields.entrySet()) {
        final Set<String> sources = fe.getValue();
        if (sources == null || sources.isEmpty()) continue;
        out = out.withField(ins.dest, fe.getKey(), sources);
    }
}
```

The `withField` call uses R118b weak update, so prior caller-side
field writes on the same dest are preserved (union'd with the
incoming sources).

### Sub-fix 4: MOVE transfer propagates fieldMap

The first R161 attempt set the field taint on `ins.dest` of the CALL,
but the test still failed: the caller's TAC shape is
`t_t0 = readForm(req); form = t_t0;` (CALL + MOVE), and the
FIELDLOAD `t_t1 = form.username` reads from `form`, not from `t_t0`.
The MOVE transfer only copied the base taint and the chain var, not
the fieldMap. R161 fix #4: MOVE copies the rhs's fieldMap to the
dest's fieldMap (union, weak update). This is the same
"reference-aliasing" semantics the rest of the analysis uses
(field taint follows the reference, not the SSA temp).

This fix is general — it applies to ALL MOVEs, not just the
R161 pattern. R141a / R145 / R146 / R147 unit tests confirm no
regression.

## Test Results

### R161 unit tests (9 new)

`R161ReturnFieldTaintTest.java`:
- `helperWritesTaintedFieldThenReturnsObjectCallerFieldReadFires` —
  canonical R161 case (L71 pattern)
- `helperFieldWriteIsolatesSiblingField` — sibling isolation
  (write `o.x`, read `o.y`, only x fires)
- `helperFieldWriteAffectsMultipleCallSites` — two call sites
  each fire (cache correctness)
- `fastjsonStyleCvePattern` — deserialization sink via field read
- `helperDoesNotWriteAnyFieldDoesNotFire` — negative control
- `fieldTaintSurvivesMultiHopReturn` — 2-hop wrapper → leaf,
  field taint survives
- `returnBaseTaintStillWorksAlongsideReturnFieldTaint` — R141a
  regression guard
- `r145CrossFunctionFieldMapStillWorks` — R145 regression guard
- `sameCalleeSameTaintConsistentAcrossCallSites` — R161 cache
  correctness

9/9 PASS.

### plumb-dfa full test suite

- 326 tests, 0 fail, 0 error, 1 skip (R151 expected disabled)
- R141a / R145 / R146 / R147 / R158 / R159 unit tests all preserved
- 0 regression across the 9 dfa checker test files (R152+ era)

### CVE evaluation (R161 vs R160)

| App | R160 | R161 | Delta | New line(s) |
|---|---|---|---|---|
| cc4 (CVE-2015-7501) | 1 taint + 2 other | 1 taint + 2 other | 0 | none (preserved) |
| fastjson (CVE-2017-18349) | 4 deserialize | **5** deserialize | **+1** | **L73** (DTO field) |
| log4shell (CVE-2021-44228) | 5 log | **6** log | **+1** | **L71** (DTO field) |
| spring4shell (CVE-2022-22965) | 2 taint + 5 other | 2 taint + 5 other | 0 | none (preserved) |

**R161 net: +2 CVE findings** (log4shell L71 + fastjson L73), 0
regressions.

The new findings are exactly the "跨 function DTO field taint" pattern
that motivated R161. R161 closes the L71 / L73 gaps.

## Performance

- R161 unit tests: 1.3s
- log4shell test (with log4j-api jar): 6s (R160 was 5s; R161
  adds a small per-call cost for the new `pullReturnFieldTaint`,
  mitigated by the exit-taint cache fast-path — the worklist is
  only run when the exit-taint cache misses)
- fastjson test: 4s
- cc4 test: 17s
- spring4shell test: 510s (R160 was 510s; R161 same)

The exit-taint fast-path keeps R161's overhead minimal in the
common case (when the callee's exit-taint is already cached from
the main doWorklist).

## Innovation Summary

R161 is a 4-sub-fix implementation that closes a structural gap in
the cross-function taint propagation story. The R145 + R161
combination now covers BOTH halves:
- R145: callee-param → caller-arg field taint propagation
- R161: callee-return-value → caller-dest field taint propagation

The two halves are complementary; together they form a complete
"reference-aliasing" model for cross-function field taint.

### 4 创新点 (post-R160)

1. **Cross-function field taint has TWO halves (R161)**. R145 covered
   callee-param → caller-arg. R161 covers callee-return-value →
   caller-dest. The two are symmetric: both are reference-aliasing
   through the SSA temp at the call site. **General lesson**: any
   "cross-X propagation" in a SAST engine has at least two halves
   (in and out); the second half often slips through if the
   developer fixes the first half in isolation. R161 is the explicit
   closing of the second half.
2. **MOVE transfer is the missing link (R161 sub-fix 4)**. The
   R161 fix initially wrote the field taint to the CALL's
   `ins.dest` (the SSA temp `t_t0` of `t_t0 = readForm(req)`), but
   the FIELDLOAD on the next line read from the caller's local
   `form`, not from `t_t0`. The IR's MOVE instruction
   `form = t_t0` is what bridges them. Pre-R161 MOVE only copied
   base taint + chain var. R161 also copies the fieldMap.
   **General lesson**: in a SAST IR with explicit MOVE instructions
   (rather than implicit copy-on-use), the MOVE transfer is the
   natural place to propagate fieldMap. Forgetting MOVE leaves a
   silent gap — the analysis looks right but doesn't work.
3. **Pull-vs-push symmetry for field taint (R161 + R145 + R141b)**.
   R141b pushed field taint through the worklist (intra-fn).
   R145 pulled field taint from callee-param to caller-arg
   (cross-fn in). R161 pulls field taint from callee-return to
   caller-dest (cross-fn out). The three together form a coherent
   model: every place a reference can change hands (intra-fn
   assign, cross-fn param, cross-fn return), the field taint
   follows. **General lesson**: SAST field-sensitive analysis
   must enumerate every "reference handover" point and wire
   field taint through all of them. R141b → R145 → R161 is the
   explicit enumeration of the 3 handover points in Plumb's IR.
4. **Exit-taint fast-path is the right place for cross-fn return
   queries (R161 + R159 sub-fix B)**. R161's `pullReturnFieldTaint`
   uses the same exit-taint-cache fast path as R159's
   `pullReturnTaint`. The cached exit-taint's `fieldMapOf(RETURN.lhs1)`
   IS the field taint of the return value at the moment of the
   RETURN. The worklist (slow path) is only needed when the
   exit-taint cache misses. **General lesson**: SAST engines that
   maintain BOTH an exit-taint cache and a return-taint query
   should reuse the former for the latter. Exit-taint is the more
   general data structure; return-taint is a derived view of it
   (return values are the temps that flow into RETURN). Avoid
   building a parallel "return-taint" data structure when
   exit-taint already contains the answer.

## Disaster Backup

`D:\work\tmp\r161_backup\java_source/`:
- `TaintAnalysis.java` (R161 final, 61741 bytes)
- `TaintFact.java` (R161 final, 23939 bytes — unchanged from R160)

## CVE Net Wins (post-R158)

Cumulative CVE coverage for the 4 recent Java CVEs:
- **CVE-2015-7501** (cc4): 1 finding (R160 baseline)
- **CVE-2017-18349** (fastjson): 5 findings (4 R160 + **1 R161**)
- **CVE-2021-44228** (log4shell): 6 findings (5 R160 + **1 R161**)
- **CVE-2022-22965** (spring4shell): 7 findings (R160 baseline)

R161 closes the 跨 function DTO field taint gap for 2 of the 4 CVEs.

## Next R-round Candidates (post-R161)

- **R162 real Spring4Shell full suite** (复测 s4s 4 个 .java:
  Simple1/Simple2/TestSpring4ShellInline/VulnSpring4ShellController)
- **R163 real fastjson full suite** (cross-class StrSubstitutor
  sink-layer for log4shell)
- **R164 CWE-674 Unbounded Recursion** (narrow whitelist)
- **R165 CWE-481 Assign instead of Compare** (narrow whitelist)
- **R166 deeper cross-class taint** (R160 deeper — model
  string-content-aware taint for log.info → StrSubstitutor →
  JndiLookup, requires Z3 / chain extension)
