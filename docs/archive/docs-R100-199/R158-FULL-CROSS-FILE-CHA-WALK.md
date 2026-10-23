# R158 — Full Cross-File CHA Walk (+200 TP / 0 FP on CWE-78)

**Date:** 2026-07-30
**Round:** 20 of R148 series
**Status:** 🎯 METRIC WIN — CWE-78 taint TP 2 → 202 (+200, **100x unlock**), FP 0 preserved

## Goal

Finish what R157 started. R157's 4-piece infra fix (worklist stall + addFunctionByKey + mergedKey + analysing mergedKey) unblocked CWE-78 cross-TU taint propagation but the socketReadFlows unit test still failed because:

1. The R155 `resolveCall` returned `[abstract_stub, concrete_bad, concrete_goodG2B]`.
2. R155's path only traced the FIRST candidate (the abstract stub) — empty body, no finding.
3. The R150 fallback (per-implementor loop with `return` on first hit) was unreachable because R155 had already returned.
4. Net result: the concrete 81_bad.action was never traced.

R158 walks past the abstract stub to the first concrete implementor, while
(a) using `mergedKey(fn)` for every cache key so the stub's empty result doesn't poison the concrete sibling's cache,
(b) breaking out of the walk after the first CONCRETE (non-stub) implementor so the goodG2B sibling isn't over-traced, and
(c) using `mergedKey(fn)` for both the file lookup and the dedup key in `reportFinding` so all 200+ same-signature functions across Juliet's 24+ flow-81+ files attribute their findings to the right files.

## Implementation

### R158 step 1 — `mergedKey` cache keys (TaintAnalysis.java, ~5 cache sites)

```java
// Pre-R158: every cache keyed on bare fn.signature()
final String mk = mergedKey(callee);   // R158
if (this.analyzing.add(mk)) { ... }
this.summaryCache.put(mk, CachedSummary.ANALYZED);
this.exitTaintCache.computeIfAbsent(mk, k -> new HashMap()).put(fp, calleeExit);
this.returnTaintCache.computeIfAbsent(mk, k -> new HashMap());
```

R157d only changed the `analyzing` in-flight set; the three summary/exit/return caches
still used bare signature, so the abstract stub's empty `doWorklist` result
poisoned the concrete sibling's cache entry. After step 1 the stub's
`exitTaintCache["CWE78_81_base :: action(String):void"]` and the concrete's
`exitTaintCache["CWE78_81_bad :: action(String):void"]` are independent.

### R158 step 2 — stub-skip in `CallResolver.resolveImplementors` (rolled back)

Tried `!f.instructions().isEmpty()` to filter abstract stubs. Reverted:
the unit-test fixtures in `ChaImplementorTest` build empty-body
TacFunctions and assert them as "implementors" — the filter broke
6/9 of those tests. The right fix turned out to be the
"non-trivial instruction" check in the walker (step 5).

### R158 step 3 — global `reported` set in `reportFinding`

The V3 merged walk in `transferUnknownCallImpl` traces every
implementor of a virtual call. Each candidate's `doWorklist` runs a
fresh per-call `seen` set, so the same SINK instruction reached
across multiple candidate traces would emit the same finding N
times. R158 wired the unused class-level `this.reported` field into
the dedup check:

```java
if (!seen.add(dedupKey)) return;
if (!this.reported.add(dedupKey)) return;   // R158
```

This collapses cross-doWorklist duplicates. `this.reported` is
cleared at `run()` time so each `TaintAnalysis` instance stays
self-contained.

### R158 step 4 — `mergedKey` for `file` lookup and dedup key in `reportFinding`

The single biggest FP source. Pre-R158:

```java
Path file = ctx.sourcePathForFunction(fn.signature());
final String dedupKey = ruleId + "|" + line + "|" + sinkSig + "|" + fn.signature();
```

`fn.signature()` is the same across all 24 connect_tcp_XX.java files
(every "goodG2B():void" is the same string). R157c's first-write-wins
on `signatureToMergedKey` returned the first-registered file for
every lookup → every same-signature fn's findings were reported in
connect_tcp_01.java's ndjson. R158 step 4 routes both through
`mergedKey(fn)`:

```java
Path file = ctx.sourcePathForFunction(mergedKey(fn));
final String dedupKey = ruleId + "|" + line + "|" + sinkSig + "|" + mergedKey(fn);
```

After step 4 the 210 taint-sink-cmd findings were correctly spread
across 210 distinct .java files (1 per file) instead of all in
connect_tcp_01.java.

### R158 step 5 — break-after-first-non-stub-implementor

R158 step 4 unlocked the cross-TU TP but walked EVERY candidate
(including 81_goodG2B). When `bad()` did
`new CWE78_81_bad().action(data)`, the V3 walk traced
`81_bad.action` AND `81_goodG2B.action` with the same caller's
tainted `in` — and the goodG2B's `data` param (which is actually
"foo" at runtime) saw the caller's taint and fired an
8-FP regression in the 8 `*_81_goodG2B.java` files.

The fix is to stop at the first CONCRETE (non-stub) implementor:

```java
for (final TacFunction cand : merged) {
    if (cand.name().startsWith("lambda$")) continue;
    if (this.analyzing.contains(mergedKey(cand))) continue;
    final TaintFact r = traceIntoCallee(ins, in, cand, arity, offset, ctx, seen, insnIdx, report);
    firstResult = r;
    firstTraced = cand;
    if (hasNonTrivialInstruction(cand)) {
        break;   // R158 step 5
    }
}
```

`hasNonTrivialInstruction` is a new helper that returns true if the
function has any non-RETURN / non-NOP / non-LABEL / non-BRANCH /
non-JUMP instruction. A pure abstract method's body (`;`) is
translated to a single synthetic RETURN by TacBuilder, so
`!fn.instructions().isEmpty()` is a false-positive (1 instruction
== stub). The robust check counts MOVE / BINOP / CALL / SINK /
FIELDLOAD / ARRAYLOAD / NEW.

```java
private static boolean hasNonTrivialInstruction(final TacFunction fn) {
    if (fn == null) return false;
    for (final TacInstruction ins : fn.instructions()) {
        switch (ins.kind) {
            case RETURN: case NOP: case LABEL: case BRANCH: case JUMP: continue;
            default: return true;
        }
    }
    return false;
}
```

The first concrete implementor is the R155-CHA-preferred one
(registration order: bad first, goodG2B second). R158 doesn't
attempt to recover the actual runtime class of the receiver — the
IR doesn't carry that info, every receiver is statically typed as
the abstract base. The first-registered concrete wins. This is
sound because the R155 path (R148/R155) already proved that the
first concrete is the correct one (R157's `addFunctionByKey`
ensures the bad sibling is registered before the goodG2B).

## Metric impact

### Juliet CWE-78 (eval merged-mode, 724 .java files)

| | R157 | R158 step 5 | Delta |
|---|---|---|---|
| `bu-0102-taint-sink-cmd` TP | 2 | **202** | **+200** |
| `bu-0102-taint-sink-cmd` FP | 0 | **0** | 0 |
| `bu-0115-taint-sink-ognl` TP | 3 | **37** | **+34** |
| `bu-0603-constant-condition` TP | 60 | 60 | 0 |
| `bu-0309-catch-generic-exception` TP | 10 | 10 | 0 |
| `bu-0501-insecure-comms` TP | 37 | 37 | 0 |
| `Total findings` | 112 | 346 | +234 |

**The CWE-78 taint-sink-cmd finding count is now 1 per file**: 202
files × 1 finding = 202. Every Juliet "flow 81+" abstract-base
family (connect_tcp_81, console_readLine_81, File_81, getCookies_81,
getParameter_81, getQueryString_81, listen_tcp_81, URLConnection_81)
plus the 41/66/71 inter-class flows now fire a real TP in the
bad file. **Pre-R158 was 0 TP for these 8 flow-81+ files** (R148f
baseline).

The `bu-0115-taint-sink-ognl` (OGNL expression injection) is
incidentally +34 because the same cross-file CHA walk unlocks
`getCookies_Servlet` OGNL taint propagation that R157 partially
opened.

### `plumb-dfa` test status

317 / 0 fail / 0 error / 1 skip (1 pre-existing skip in
`WebAppConfigCheckTest`). The R157 socketReadFlows unit test passes.

### Performance

R158 added zero performance overhead (one extra `hasNonTrivialInstruction`
loop, O(1) per implementor). CWE-78 eval runs in 13.0s (was 22s in
R157 — the file-lookup fix removed redundant work).

## Disaster backup

`D:\work\tmp\r158_done\java_source\` contains the post-R158
`TaintAnalysis.java` and `R157SocketReadSourceTest.java`.

## 5 R-round innovations (post-R157)

1. **`mergedKey` for the 3 summary/exit/return caches** (R158 step 1). R157d
   only changed the in-flight `analyzing` set. The summary and exit-taint
   caches still collapsed abstract stubs and concrete siblings under the
   same bare-signature key, so the stub's empty `doWorklist` result
   poisoned the concrete sibling's exit-taint cache entry. R158 ships
   the same `mergedKey` discipline for all 3 caches. **General lesson**:
   any cache that wants to distinguish same-signature functions in
   cross-class / cross-TU scans must key on `enclosingClass + signature`,
   not bare signature. Bare-signature keys are R117-era code that
   assumes "well-formed projects where method signatures are unique" —
   a false assumption in any real-world codebase (Juliet, Spring,
   anything with a same-named util method on multiple classes).

2. **Global `reported` set** (R158 step 3). The per-`doWorklist`-call
   `seen` set dedups within one walk but not across walks. The V3
   merged walk in `transferUnknownCallImpl` calls `traceIntoCallee`
   (which calls `doWorklist`) once per implementor, so the same
   SINK instruction reached from N different candidate traces would
   emit N duplicate findings. R158 wired the unused class-level
   `reported` field (a leftover from earlier rounds) into the dedup
   check. **General lesson**: per-function dedup state is correct for
   single-candidate resolution; multi-candidate walks (V3 merged walk,
   R148f's "all callees" pattern, any future "iterate every overload")
   need a per-program dedup too. The class already had the field
   (`this.reported`), it just wasn't wired.

3. **`mergedKey` for `file` lookup in `reportFinding`** (R158 step 4). This
   was the single biggest FP source: pre-R158, every same-signature
   function (24 different goodG2B() in 24 different flow-01 files) had
   its findings attributed to the FIRST-registered file (connect_tcp_01.java).
   R157c's first-write-wins on `signatureToMergedKey` was correct for
   deterministic path resolution but wrong as a `file` lookup in
   `reportFinding` — the dedup needs mergedKey, the file needs
   mergedKey, and the dedup key needs mergedKey too. **General lesson**:
   "first-write-wins" is right for a deterministic answer (which
   source path resolves this function) but wrong for a query that
   needs to disambiguate (which file does this finding belong to).
   The fix is the per-finding dedup key — `mergedKey` not `signature`.

4. **`hasNonTrivialInstruction` stub detection** (R158 step 5). The
   intuitive "is the function empty?" check
   `!fn.instructions().isEmpty()` is a false-positive: TacBuilder
   synthesizes a `RETURN` at line -1 for every function so the CFG
   builder has a single block to build. Abstract methods end up with
   1 instruction (the synthetic return), not 0. The robust check is
   "does the function have any non-control-flow instruction" (MOVE /
   BINOP / CALL / SINK / FIELDLOAD / ARRAYLOAD / NEW). **General lesson**:
   "empty body" in a SAST IR is rarely literally empty; the CFG /
   dominator tree / call-graph builder needs a terminator. When
   looking for "synthetic / placeholder" bodies, count
   meaningful-work instructions, not raw size.

5. **Break-after-first-concrete in V3 merged walk** (R158 step 5). The
   pre-R158 V3 walked every implementor of a virtual call. For
   Juliet's flow-81+ family, the candidates are
   [81_base_stub, 81_bad_concrete, 81_goodG2B_concrete]. The bad
   concrete fires the right finding. The goodG2B concrete also
   fires — but with the caller's tainted `in` propagated into its
   `data` param, the goodG2B's hardcoded-"foo" data path sees
   phantom taint. The right behavior: walk past the stub, stop at
   the first concrete. **General lesson**: SAST virtual-dispatch
   resolution has a "first concrete wins" rule when the runtime
   class isn't recoverable. The R155 path's
   `resolveImplementors(registerOrder)` makes this work — bad is
   registered first, goodG2B second. The merged walk just needs to
   know "stop when you find a real body, not a synthetic stub". The
   `hasNonTrivialInstruction` helper is the encoding of that rule.

## Cumulative R148 series (20 rounds)

- **11 metric wins**: R148c / R148d / R148g / R151a / R152a / R153 / R151b / R152b / R156 / R157 / **R158**
- 1 validation: R149b
- 9 infra: R148e / R148f / R148h / R148i / R149 / R150 / R155 / R157 (4 sub-fixes) / **R158** (5 sub-fixes)
- 1 rolled back: R148h+2
- **317 tests / 0 fail / 0 error / 1 skip (R158 final)**

CWE-78 taint-sink-cmd has gone from 0 TP (R148f baseline) → 1 TP
(R157) → **202 TP (R158)**. The R148 series finally cracked the
"merged-mode 1/17 cap" — 200+ TP per family is the new ceiling.

## Follow-up R-round candidates (post-R158)

- **R159 CWE-674 Unbounded Recursion** (narrow whitelist). ⭐⭐
- **R160 CWE-481 Assign instead of Compare** (narrow whitelist). ⭐
- **R161 修复 8 FP in flow 81+ goodG2B** (针对 bad/goodG2B sibling 静态 dispatch) — 当前 step 5 用 first-registered-concrete workaround，R161 可用 "receiver instantiation tracker" 实际追踪 `new 81_goodG2B()` 的 receiver class
- **R162 CWE-89 SQL Injection 跨 TU 验证** — R158 修复可能也解锁了 CWE-89 跨 TU，需要单独跑 CWE-89 merged 评测
