# R205-D: Cross-class same-arity same-name private method call disambiguation

**Date:** 2026-08-07
**Scope:** Plumb/Java engine — caller-class disambiguation for non-virtual call resolution in merged-program batch mode
**Status:** DONE (+117 _21 TP, +193 total TP, 0 FP)

## 1. Background

The Juliet CWE-89 _21 ("control flow driven by private instance field") test pattern
declares a `private void badSink(String data)` helper inside the same file as
`public void bad()`. The call site `bad.badSink(data)` is the "this" instance
call from `bad` into `badSink`. In a single-file TAC analysis (one program per
file), the `byName` resolver returns exactly one candidate — the file's own
`badSink` — and the taint propagates correctly. The Juliet ground truth labels
the file as a TP and the engine produces a `bu-0101-taint-sink-sql` finding at
the `executeBatch` call inside `badSink`.

In merged-program batch mode (R117 cross-TU), all 60 CWE-89 _21 files (10
sources × 6 sinks) are merged into a single `MergedProgram`. The
`CallResolver.byName` map is keyed by `name + "/" + param_count`
(`CallResolver.java:82-83`), so the entry `badSink/1` now contains 60
different `badSink` functions, one per file. When `transferInvokeImpl` resolves
`bad.badSink/1` it walks the `else if (!callees.isEmpty())` branch (line 902
in `TaintAnalysis.java`), which traces `callees.get(0)` — the first candidate
in iteration order. This first candidate is whichever file was registered
first by `MergedProgram.rebuild()`. The right `badSink` (the one in the same
class as `bad()`) is never traced, the taint never reaches the SQL sink, and
the file is FN.

R181 added a worklist re-enqueue fix for `_15` switch-case / `_21`
constant-if-true patterns that worked in single-file mode. R203 added
case-insensitive catalog matching + unknown-call source resolution that
unblocked many taint sources, but the cross-class same-arity name collision in
the call resolver was not fixed. The empirical state at the end of R203 was:

```
CWE-89 _21: 6 fires / 60 bad files  (10% recall)
CWE-113 _21: 3 fires / 36 bad files
CWE-23/36/78/90 _21: 4 fires / 12 bad files each
TOTAL _21: 63 fires / 198 bad files  (32% recall)
```

## 2. Root cause (debug log evidence)

I added a `[R205D-MW]` debug log inside the merged walk and a `[R205D-TI]`
log in `transferImpl` to trace every CALL instruction transferred during a
small 5-file batch run (`smoke_juliet/r180_eval/_r205_small_batch.ps1`):

```
[R205D-TI] kind=CALL calleeSig=badSink/1 dest=t_t23 in.fn=bad insnIdx=57
```

The `badSink/1` call IS emitted as a CALL instruction in TAC, but the
`merged.size` in `[R205D-MW]` is 0 — meaning `transferUnknownCallImpl`
short-circuits through the `else if` branch and traces `callees.get(0)`. The
debug log confirms the call goes through:

```
} else if (!callees.isEmpty()
        && (lambdaBridged || !callees.get(0).name().startsWith("lambda$"))
        && !this.analyzing.contains(mergedKey(callees.get(0)))) {
    return traceIntoCallee(ins, in, callees.get(0), arity, offset, ctx, seen, insnIdx, report);
}
```

`badSink/1` has `args.size() == 1` (only the `data` arg — no `this`), so
`offset = 1 - 1 = 0`, and the call routes to the `else if` branch. The branch
unconditionally traces `callees.get(0)`, the first candidate from `byName`
which is the wrong `badSink` (a different file's).

## 3. The fix

In `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java`, modify
the `else if` branch to prefer the same-class callee when there are multiple
candidates. The pre-R205-D guard `!this.analyzing.contains(mergedKey(callees.get(0)))`
is preserved (a recursive call still skips the branch).

```java
} else if (!callees.isEmpty()
        && (lambdaBridged || !callees.get(0).name().startsWith("lambda$"))) {
    // R205-D fix: when the call is non-virtual (offset == 0) and the
    // resolver returns multiple same-name/same-arity candidates from
    // different classes (cross-file batch mode where many Juliet
    // CWE-89 _21 files each declare a `private void badSink(String)`),
    // prefer the candidate in the caller's enclosing class for the
    // same-class implicit-`this` call. Fall back to the first
    // candidate (and the pre-R205-D guard) when no same-class
    // candidate exists or every candidate is being analyzed.
    TacFunction calleeToTrace = null;
    if (callees.size() > 1 && fn != null && fn.enclosingClass() != null
            && !fn.enclosingClass().isEmpty()) {
        final String callerClass = fn.enclosingClass();
        for (final TacFunction cand : callees) {
            if (cand.enclosingClass() != null
                    && callerClass.equals(cand.enclosingClass())
                    && !this.analyzing.contains(mergedKey(cand))) {
                calleeToTrace = cand;
                break;
            }
        }
    }
    if (calleeToTrace == null) {
        calleeToTrace = callees.get(0);
        if (this.analyzing.contains(mergedKey(calleeToTrace))) {
            calleeToTrace = null;
        }
    }
    if (calleeToTrace != null) {
        return traceIntoCallee(ins, in, calleeToTrace, arity, offset, ctx, seen, insnIdx, report);
    }
}
```

The fix has two branches:

1. **Same-class disambiguation (new):** when `callees.size() > 1` and the
   caller has an enclosing class, walk the candidate list and pick the
   candidate whose `enclosingClass()` matches the caller's. This handles
   `bad.badSink(data)` where `bad` and `badSink` are in the same file.

2. **Pre-R205-D fall-through (preserved):** if no same-class candidate
   exists (cross-class dispatch, e.g. Juliet `_51a` / `_52a` patterns
   where `bad_a` calls a `badSink` defined in a separate class file), the
   code falls through to `callees.get(0)`. The pre-R205-D guard
   `!analyzing.contains(mergedKey(callees.get(0)))` is preserved: if the
   first candidate is being analyzed (recursive call), the entire branch
   is skipped and the existing CHA-implementor / conservative-propagation
   fallbacks run.

## 4. Validation

### Small-batch (5 CWE-89 _21 files together)

```
PS> powershell -File .\_r205_small_batch.ps1
elapsed=17.6s exit=0
sqli-fire: 5
  FIRE: CWE89_SQL_Injection__connect_tcp_execute_21.java
  FIRE: CWE89_SQL_Injection__console_readLine_executeQuery_21.java
  FIRE: CWE89_SQL_Injection__Environment_executeQuery_21.java
  FIRE: CWE89_SQL_Injection__File_execute_21.java
  FIRE: CWE89_SQL_Injection__PropertiesFile_executeUpdate_21.java
```

5/5 fire (was 1/5 pre-fix).

### Per-CWE _21 fire counts (R205-D attributed vs R203)

| CWE | bad | R203 fires | R205-D fires | Δ |
|---|---:|---:|---:|---:|
| CWE-23 _21 | 12 | 4 | 12 | +8 |
| CWE-36 _21 | 12 | 4 | 12 | +8 |
| CWE-78 _21 | 12 | 4 | 12 | +8 |
| CWE-80 _21 | 18 | 18 | 18 | 0 |
| CWE-81 _21 | 9  | 9  | 9  | 0 |
| CWE-83 _21 | 9  | 9  | 9  | 0 |
| CWE-89 _21 | 60 | 6  | 60 | +54 |
| CWE-90 _21 | 12 | 4 | 12 | +8 |
| CWE-113 _21 | 36 | 3  | 36 | +33 |
| CWE-134 _21 | 18 | 2  | 2 (unchanged, no r205d batch) | 0 |
| **TOTAL _21** | **198** | **63** | **180** | **+117** |

CWE-134 _21 unchanged because R205-D didn't sub-batch the CWE-134
ndjson (the r205d entry in the cover script's ROUND_PRIO falls through to
`r203` for CWE-134, preserving the previous 2 fires).

### Overall CWE metric (R203+ → R205-D)

| CWE | R203+ TP | R205-D TP | Δ |
|---|---:|---:|---:|
| CWE-23 / CWE-36 / CWE-78 / CWE-90 | 344 | 354 | +10 each (+40 total) |
| CWE-80 | 541 | 551 | +10 |
| CWE-81 / CWE-83 | 271 | 279 | +8 each (+16 total) |
| CWE-89 | 1701 | 1790 | +89 |
| CWE-113 | 698 | 736 | +38 |
| CWE-134 | 506 | 506 | 0 |
| CWE-252 / CWE-369 / CWE-476 / CWE-193 | unchanged | unchanged | 0 |
| **GRAND TOTAL** | **5480** | **5673** | **+193 (+3.5%)** |

Recall: 56.5% (R203+) → 58.5% (R205-D). FP unchanged at 0.

## 5. Pitfalls / false-friend risks

1. **The pre-R205-D guard `!analyzing.contains(mergedKey(callees.get(0)))`
   must be preserved.** The first attempt at R205-D removed this guard and
   caused a 1000+ TP regression across all CWE because the fix would trace
   the first candidate even during a recursive call (where the
   pre-R205-D code would skip the branch and let the CHA-implementor
   walk handle it). Always keep the guard; the fix is in the
   `calleeToTrace == null → callees.get(0)` path.

2. **R200 attribution must run on R205-D ndjsons before coverage.** The
   R205-D raw ndjson fires `bu-0101-taint-sink-sql` at the helper file
   (e.g. `_22b.java`), not the entry-point file (`_22a.java`). The cover
   script's `aux` filter `/_\d+[b-z]\.java$/` would silently drop those
   findings. Run `node attribution_r200.cjs <in> <out> <index>` on each
   `.r205d.ndjson` (and each per-sub-folder `s0N.r205d.ndjson`) before
   computing the final TP count. The `coverage_all.cjs` script's
   `loadNdjsonLines` was updated to prefer `.r205d.attributed.ndjson`
   over the raw `.r205d.ndjson`.

3. **Sub-folder merges need per-sub-folder attributed files.** CWE-89 was
   batch-run per sub-folder (s01-s04) in R203. R205-D re-ran each
   sub-folder separately. The cover script's `SUBFOLDER_CWE` map and
   `loadNdjsonLines` concatenate the per-sub-folder `.r205d.attributed.ndjson`
   files. Ensure each sub-folder has both the raw and attributed ndjson
   before running the cover.

4. **The merged walk path (R158 first-concrete-sibling break) is
   untouched.** The R205-D fix is strictly in the `else if` non-virtual
   branch. The 81+ abstract-base flow (`baseObject.action(data)`,
   `offset > 0`) still uses the merged walk with its first-concrete
   break — that path is correct and changing it would re-introduce the
   8-FP regression that R158 fixed. Don't merge the two paths.

## 6. Files changed

- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java` —
  the `else if` branch at line 902-906 in the original was replaced
  with the same-class-disambiguation logic.
- `smoke_juliet/r180_eval/coverage_all.cjs` — added
  `loadNdjsonLines()` to handle sub-folder merges and prefer
  `.attributed.ndjson`. `ROUND_PRIO` now lists `r205d` first.
- `smoke_juliet/r180_eval/run_r205_d_all.ps1` and
  `run_r205_d_missing.ps1` — batch scripts for re-running all 9
  CWE with the R205-D jar (CWE-89 / CWE-113 / CWE-80 sub-batched).
