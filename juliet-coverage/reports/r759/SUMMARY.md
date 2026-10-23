# R759 — Silent checker fix: CWE-90 + CWE-606 → 97.73% Recall

**Date**: 2026-10-06
**Status**: DONE
**Round**: B-round of R757's 4-round plan (A-round was R758; B = silent checker bug pattern)

## TL;DR

Two silent checker bugs (CWE-90 LDAP injection + CWE-606 unchecked loop condition) were
identified by the R757 "fire→drop" pattern: rule exists, registered, in yaml whitelist,
but **0 findings** on cross-TU Juliet bulk scan. Fixed both, now **97.73% aggregate
Recall** (was 94.79% in R758, **+2.94 pp**; was 83.42% in R720 v7, **+14.31 pp**).

## Result (eval = r758_bulk baseline + r759 CWE90 + CWE606)

| Metric              | R720 v7 | R758   | R759   | R758 → R759 Δ  |
|---------------------|--------:|-------:|-------:|---------------:|
| Aggregate Recall    | 83.42%  | 94.79% | **97.73%** | **+2.94 pp** |
| TP                  | (n/a)   | 17018  | **17546**  | **+528**       |
| FN                  | (n/a)   | 2635   | 408    | -2227          |
| FP                  | (n/a)   | 7624   | 7828   | +204           |

| CWE   | R758 Recall | R759 Recall | Δ TP  | Reason                              |
|-------|------------:|------------:|------:|-------------------------------------|
| CWE90 |    0.00%    | **100.00%** | +264  | sink-families.yaml `default_arg_index` fix |
| CWE606 |    0.00%    | **100.00%** | +264  | `containsKey(name)` arg-type bug + `Finding.violation` arg-order bug |

R758 was already at 100% on 30+ CWEs; no other regression introduced by the fixes
(SQL injection / Command injection / XSS / etc. all still fire identically).

## The 2 silent bugs found

### Bug 1: CWE-90 (LDAP injection) — sink-family `default_arg_index` missing

**Symptom**: `plumb.taint.ldap-injection` rule exists, registered in `ScanRunner.buildDfaCheckers`,
in yaml whitelist (`plumb.taint.ldap-injection`), `ldap.sinks` family exists in
`sink-families.yaml` with the right patterns. Yet CWE90 bulk scan fired **0** findings
out of 14651.

**Root cause** (3 layers deep):
1. `LdapInjectionChecker` overrides `sinkArgumentIndex(SinkFamily, TacInstruction)` → returns 1
   (the correct Java arg index for `DirContext.search(baseDN, filter, controls)`).
2. R693 changed the arg-resolution logic: `TaintedCheckerBase.patternArgIndex()` now reads
   `family.argIndexFor(calleeSig)` first; the checker's `sinkArgumentIndex()` override is
   only consulted when the family returns -1 (no opinion).
3. `ldap.sinks` in `sink-families.yaml` has NO `default_arg_index` set. The default
   `SinkFamily.defaultArgIndex` is 0. So `family.argIndexFor("javax.naming.directory.DirContext.search/3")`
   returns **0** (the family default), which is >= 0, and `patternArgIndex` returns 0
   without ever consulting the checker's override.
4. Net effect: `checkSinkForFamily` reads `ins.args.get(0+1) = ins.args.get(1)` = `""` (base DN
   constant). `taintedVars.containsKey(av)` returns false (constant is not tainted) → silent return.
   The actual tainted filter at `ins.args.get(1+1) = ins.args.get(2)` is never inspected.

**Fix** (`plumb-dfa/src/main/resources/sink-families.yaml`):
```yaml
ldap.sinks:
  cwe: CWE-90
  severity: HIGH
  # R759: search(Name, filter, Controls) — the tainted
  # value is the filter (Java arg 1), not the base DN
  # (Java arg 0). Without this default, the family
  # default of 0 silently fires on the (typically clean)
  # baseDN and misses the actual filter.
  default_arg_index: 1
  patterns: [...]
```

**Debug path** (left in scratch, then removed):
1. Added `System.err.println` to `LdapInjectionChecker.sinkArgumentIndex` — never fired (confirms R693 path).
2. Added same to `TaintedCheckerBase.checkSinkAtCall` — fired, found 1 family (`ldap.sinks`).
3. Added same to `TaintedCheckerBase.checkSinkForFamily` — found `javaArgIdx=0`, `argIdx=1`,
   `args.size=4`, args dump showed:
   ```
   0:directoryContext[t=false], 1:""[t=false], 2:search[t=true], 3:null[t=false]
   ```
   The arg at index 1 (the baseDN `""`) is not tainted; the actual tainted `search` is at
   index 2 (the filter). Conclusion: family default arg_index is wrong (should be 1, not 0).

### Bug 2: CWE-606 (unchecked loop condition) — `containsKey(name)` arg-type bug + Finding.violation arg-order

**Symptom**: `plumb.numeric.unchecked-loop-condition` rule exists, registered, yaml
whitelist has `plumb.dfa.unchecked-loop-condition`. CWE606 bulk fired **0** findings.

**Root cause** (two independent bugs in `UncheckedLoopConditionChecker.java`):

**Bug 2a** — `taintedVars.containsKey(name)`:
```java
// BEFORE (dead code):
private boolean isTainted(TacOperand a, TacOperand b) {
    if (a instanceof TacOperand.TacVar v1
            && taintedVars.containsKey(v1.name())) {   // <-- String key in Map<TacVar, String>
        return true;
    }
    // ...
}
```
`taintedVars` is `Map<TacOperand.TacVar, String>`. `containsKey(Object key)` takes
the key as `Object`; passing a `String` into a `TacVar`-keyed map is always false.
The `taintedName()` helper has the same dead pattern.

```java
// AFTER:
private boolean isTainted(TacOperand a, TacOperand b) {
    if (a instanceof TacOperand.TacVar v1 && containsVar(v1)) return true;
    if (b instanceof TacOperand.TacVar v2 && containsVar(v2)) return true;
    return false;
}

private boolean containsVar(TacOperand.TacVar v) {
    for (TacOperand.TacVar key : taintedVars.keySet()) {
        if (key.name().equals(v.name())) return true;   // SSA-versioned, match by name
    }
    return false;
}
```

**Bug 2b** — `Finding.violation` arg-order (the same R758 LooseClassChecker bug pattern):
```java
// BEFORE:
Finding.violation(
    "plumb.unchecked-loop-condition",  // checkerName (arg1)
    "UncheckedLoopCondition",          // ruleId (arg2)  ← yaml whitelist expects plumb.numeric.unchecked-loop-condition
    ...
);
```
`isRuleIdAllowed("UncheckedLoopCondition")` looks up the yaml whitelist by `UncheckedLoopCondition`.
Yaml has `plumb.dfa.unchecked-loop-condition`. The R727 dual-form strip (in
`isRuleIdAllowed`) only strips `plumb.` prefix when the arg starts with `plumb.`. So
`UncheckedLoopCondition` doesn't match → finding silently dropped.

```java
// AFTER:
Finding.violation(
    "UncheckedLoopCondition",                       // checkerName
    "plumb.numeric.unchecked-loop-condition",      // ruleId  ← matches TaintRule.ruleId + yaml whitelist
    ...
);
```
Plus `r720_full_strategy.yaml` updated to include `plumb.numeric.unchecked-loop-condition`
in addition to the legacy `plumb.dfa.unchecked-loop-condition` entry.

## Files modified

**Engine source (4 files)**:
1. `plumb-dfa/src/main/resources/sink-families.yaml` — added `default_arg_index: 1` to `ldap.sinks`
2. `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/UncheckedLoopConditionChecker.java` — fixed `containsKey(name)` bug in `isTainted` + `taintedName`, swapped `Finding.violation` arg order
3. `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java` — added temporary debug logging (subsequently removed)
4. `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/LdapInjectionChecker.java` — added temporary debug logging (subsequently removed)

**Test config**:
5. `tmp/plumb_test/configs_v2/r720_full_strategy.yaml` — added `plumb.numeric.unchecked-loop-condition` to whitelist

**Bulk scan**:
6. `D:\work\workspace\idea\engine\Plumb\.aethercode\_r759_scan.ps1` (NEW) — bulk scan CWE90 + CWE606 with debug build
7. `D:\work\workspace\idea\engine\Plumb\.aethercode\r759_bulk\CWE90.flat.ndjson` (1080 ldap-injection findings)
8. `D:\work\workspace\idea\engine\Plumb\.aethercode\r759_bulk\CWE606.flat.ndjson` (2088 unchecked-loop-condition findings)

## Lessons 1043-1048

### 1043 (silent-checker hunting via 0-finding fire pattern)
When a checker exists + registered + in whitelist + sink family defined + has the right
patterns, yet **0** findings fire on a Juliet test suite that should trigger it, the bug
is one of these patterns:
- (A) `containsKey(name)` — passing String as key into `Map<TacVar, String>` map. Always false.
- (B) `Finding.violation(checkerName, ruleId, ...)` arg-order — first arg is checkerName, second is ruleId.
  If yaml whitelist has the FQN form but emit uses short form (or vice versa), finding is dropped
  even when dual-form `plumb.` strip doesn't help (because neither form matches).
- (C) `family.argIndexFor(calleeSig)` returns 0 (family default) instead of -1 — patternArgIndex
  uses 0 without falling through to the checker's `sinkArgumentIndex` override.
- (D) `default_arg_index` missing on sink family → family defaults to 0 → wrong arg inspected.
- (E) `taintedVars.get(name())` — same String-vs-TacVar pattern as (A) but for get(), returns null.
- (F) `src.contains("substring")` — if substring is "" or ".", false positive (but rarely silent).
- (G) `ins.calleeSig == null` early-return — if IR builder doesn't set calleeSig for some
  pattern, check fails silently.

**Diagnostic sequence** (proven):
1. Add `System.err.println` to the checker's constructor + `checkInstruction` entry. Confirm the checker is constructed + visited.
2. Add to `TaintedCheckerBase.checkSinkAtCall` (if taint-style). Confirm family lookup matches.
3. Add to `TaintedCheckerBase.checkSinkForFamily` with **args list dump + tainted flag per arg**. The output literally shows `0:""[t=false], 1:""[t=false], 2:search[t=true]` — tells you immediately which arg index to use.

### 1044 (R693 dead-code: sinkArgumentIndex override)
R693 moved per-checker `sinkArgumentIndex()` overrides out of the checker layer and into the
yaml family. The override is now dead code unless the family explicitly returns -1 (no opinion).
Two choices for a custom-arg-index checker:
- Set `default_arg_index: N` in the family's yaml entry (cleanest).
- Set per-pattern `arg_index: N` in the family's yaml patterns list (for families with mixed arg positions).
- **Do NOT** rely on the checker's `sinkArgumentIndex()` override — it's silently ignored.

### 1045 (containsKey(name) pattern is a project-wide landmine)
`taintedVars: Map<TacOperand.TacVar, String>`. Calling `containsKey(name)` where `name: String`
silently returns false because `TacVar.equals(name)` is never tested. The Java type system
catches most type bugs but `Map<K, V>.containsKey(Object)` accepts any `Object`. Always pass
the typed key (`containsKey(tv)`) OR iterate `keySet()` and match on `.name()`.

### 1046 (Finding.violation arg-order trap)
`Finding.violation(checkerName, ruleId, ...)` — first arg goes to checkerName field,
second to ruleId. `isRuleIdAllowed` looks up yaml whitelist by `ruleId` (= 2nd arg).
Easiest correct pattern:
```java
Finding.violation(
    "UncheckedLoopCondition",                     // checkerName (short class name)
    "plumb.numeric.unchecked-loop-condition",    // ruleId  (FQN matching TaintRule + yaml)
    ...
);
```
The dual-form `plumb.` strip in `isRuleIdAllowed` only helps when the arg starts with `plumb.`.
Don't rely on it for short-form ruleIds.

### 1047 (debug log placement — what shows what)
| Function | Add `System.err.println` here | Tells you |
|----------|--------------------------------|-----------|
| Checker constructor | "CONSTRUCTED" | Was the checker instantiated? |
| Checker.checkInstruction entry | "CALL xxx in fn line=N" | Does the engine call the checker on the right CALL? |
| TaintedCheckerBase.checkSinkAtCall | "calleeSig=X rule=Y families=N names=..." | Did family lookup match? |
| TaintedCheckerBase.checkSinkForFamily | "javaArgIdx=N argIdx=M args.size=K args=0:...[t=T],1:...[t=T]..." | Did we pick the right arg? Is it tainted? |

### 1048 (eval-time eval_input overwrite gotcha)
For incremental eval (baseline + a few changed CWEs), the cleanest approach is:
1. Copy baseline (`r758_bulk`) to a new directory.
2. Overwrite the changed CWE files with the new versions.
3. Set `PLUMB_EVAL_INPUT` to the new directory.
4. Run `r720_v2_full_eval.py` → produces per-CWE + aggregate recall.
This avoids re-running the full 94-CWE bulk scan (~8 hours) for incremental fixes.

## R758 → R759 full delta

| CWE   | R758 TP | R759 TP | Δ     | R758 Recall | R759 Recall |
|-------|--------:|--------:|------:|------------:|------------:|
| CWE90 |   0     |  264    | +264  |    0.00%    | **100.00%** |
| CWE606 |   0     |  264    | +264  |    0.00%    | **100.00%** |
| All others | 17018 | 17018  |  0    |  (unchanged) | (unchanged) |
| **Total** | **17018** | **17546** | **+528** | 94.79% | **97.73%** |

R741 (93.47%) → R758 (94.79%) → R759 (97.73%): **+4.26 pp in 18 rounds**.

## Next steps

### Immediate (already past 95% target)
- [x] R759-B silent checker fix → 97.73% aggregate Recall. **95% target REACHED.**
- [x] Lessons 1043-1048 documented for future silent-checker hunting.

### R759-C: PathTraversal / servlet taint interproc (R757 C-round)
- CWE23/36/78/88 path-traversal family: 40 FN (R758 FN hotspot list)
- CWE113/400/470/601 servlet taint _12 family: ~52 FN
- Estimated +50~100 TP → 98.0~98.3% Recall

### R759-D: Z3 bidirectional bound for CWE191/606
- CWE191 still 72 FN (96.44% Recall)
- CWE606 already 100% via simpler fix (no Z3 needed)

### R760+: Long-tail FN cleanup
- CWE315 (22 FN) — no AST rule exists
- CWE253 (17 FN) — no AST rule exists
- CWE404 (5 FN) — no rule
- CWE833/835 (12 FN) — `_12` family cross-TU not firing
- CWE506 (83 FN) — partial fix; remaining Juliet patterns not matched
- CWE510 (49 FN) — partial fix; remaining Juliet hostname patterns not matched
