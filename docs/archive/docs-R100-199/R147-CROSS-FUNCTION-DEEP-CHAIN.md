# R147 — Cross-Function Deep (Multi-Step) Chain-Var Field Propagation

**Date**: 2026-07-28
**Round**: R147 (R146 follow-up)
**Status**: ✅ complete, 7 new tests pass, 0 regression on 274 pre-existing tests

---

## 1. Problem

R146 introduced cross-function chain-var field-write propagation: when a
helper writes `param.a.b = tainted` (chain var `t1` with single-step
chain `(param, "a")`, `fieldMap[t1][b] = tainted`), the propagation
records `((arg, "a")) → {b: tainted}` in a side table, and the caller's
`String t1' = arg.a;` FIELDLOAD consults the side table and applies the
write to `t1'`.

**R146 limitation**: the side-table key is `FieldChain(immediateBase,
fieldName)` — a single step. R146 only propagates writes from chain vars
whose chain's `immediateBase` is the param (depth 1). Chain vars at
deeper levels — `t2` with chain `(t1, "b")` and `t1` chained to the
param — are filtered out by the check
`if (!chain.immediateBase().equals(calleeParam)) continue;`.

**The R147 problem case** (helper):
```java
String t1 = param.a;     // t1.chain = (param, "a")  — 1-step, R146 covers
String t2 = t1.b;        // t2.chain = (t1, "b")     — 2-step, R146 misses
t2.c = req.getParameter("p");
```

R146 only sees the write on `t1` (if any), missing the write on `t2`. The
caller's chain walk at `sink(t2'.c)` finds no taint because
`fieldMap[t2'][c]` is empty.

This is the last "域敏感 跨过程 field analysis" gap flagged in the
R141b/R145/R146 doc notes ("field chain (R141b) 跨函数 — R146
candidate / R147+ follow-up").

---

## 2. R147 Solution: Generalize to `DeepChain`

R147 unifies the 1-step and N-step cases by introducing a
`TaintFact.DeepChain(root, fields)` record. The deep chain is the
canonical "access path" representation in field-sensitive pointer
analysis (Andersen, Steensgaard), simplified to depth-only (no array
index, no offset).

### 2.1 `TaintFact.DeepChain`

```java
public record DeepChain(TacOperand root, List<String> fields) {
    public DeepChain {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(fields, "fields");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("DeepChain must have at least 1 field");
        }
        fields = Collections.unmodifiableList(new ArrayList<>(fields));
    }
}
```

The field list is ordered **root → leaf**, so iterating
`root.f0.f1.…fn` recovers the original access path. A 1-element deep
chain `(param, ["a"])` is the R146 case; an N-element deep chain
`(param, ["a", "b", "c"])` is the R147 case.

### 2.2 `TaintFact.deepChainOf(TacOperand)`

The deep chain for a chain var is computed by walking the
single-step `FieldChain` side table back to the non-chain-var root:

```java
public DeepChain deepChainOf(TacOperand op) {
    if (!(op instanceof TacOperand.TacVar v)) return null;
    return walkDeepChain(v, new IdentityHashMap<>());
}

private DeepChain walkDeepChain(TacOperand.TacVar v,
                                IdentityHashMap<TacOperand, Boolean> visited) {
    if (visited.containsKey(v)) return null;  // cycle guard
    visited.put(v, Boolean.TRUE);
    FieldChain chain = fieldChain.get(v);
    if (chain == null) return null;  // plain var
    TacOperand base = chain.immediateBase();
    String thisField = chain.fieldName();
    if (!(base instanceof TacOperand.TacVar baseVar)) {
        return new DeepChain(base, List.of(thisField));
    }
    DeepChain baseDeep = walkDeepChain(baseVar, visited);
    if (baseDeep == null) {
        return new DeepChain(base, List.of(thisField));
    }
    List<String> fields = new ArrayList<>(baseDeep.fields().size() + 1);
    fields.addAll(baseDeep.fields());
    fields.add(thisField);
    return new DeepChain(baseDeep.root(), Collections.unmodifiableList(fields));
}
```

The walk appends each step's `fieldName` to the end, so the result is
root → leaf. The `IdentityHashMap` cycle guard is per-TacVar identity
(stable SSA version), same as R141b's existing `walkChain` for the
recursive taint walk.

For:
```java
String t1 = arg.a;     // t1.chain = (arg, "a")
String t2 = t1.b;     // t2.chain = (t1, "b")
String t3 = t2.c;     // t3.chain = (t2, "c")
```

The deep chains rooted at `arg` are:
- `t1` → `(arg, ["a"])`
- `t2` → `(arg, ["a", "b"])`
- `t3` → `(arg, ["a", "b", "c"])`

### 2.3 Side-Table Key Change

`AnalysisContext.chainPropagations` is now keyed by `DeepChain`
(previously `FieldChain`):

```java
private java.util.Map<TaintFact.DeepChain,
        java.util.Map<String, java.util.Set<String>>> chainPropagations;
```

The value is unchanged: a per-field map of writes the callee performed
on a chain var, propagated to the caller's arg var.

### 2.4 `propagateChainVarToCaller` Rewrite

The R146 implementation filtered to chain vars directly linked to the
param (`chain.immediateBase().equals(calleeParam)`). R147 enumerates
**all** chain vars in the callee's exit TaintFact, computes the deep
chain for each, and propagates when the deep chain's root is the param:

```java
private void propagateChainVarToCaller(AnalysisContext ctx,
                                        TacFunction callee,
                                        TaintFact calleeExit,
                                        TacInstruction callIns,
                                        int arity,
                                        int offset) {
    List<TacOperand.TacVar> params = callee.params();
    for (int pi = 0; pi < params.size() && pi < arity; pi++) {
        int callerArgIdx = pi + offset;
        if (callerArgIdx >= callIns.args.size()) continue;
        TacOperand callerArg = callIns.args.get(callerArgIdx);
        TacOperand calleeParam = params.get(pi);
        for (var chainEntry : chainVarsAttachedTo(calleeExit).entrySet()) {
            TacOperand chainVar = chainEntry.getKey();
            TaintFact.DeepChain deepChain = calleeExit.deepChainOf(chainVar);
            if (deepChain == null) continue;
            if (!deepChain.root().equals(calleeParam)) continue;
            Map<String, Set<String>> chainVarFields = calleeExit.fieldMapOf(chainVar);
            if (chainVarFields == null || chainVarFields.isEmpty()) continue;
            TaintFact.DeepChain callerChainSig =
                    new TaintFact.DeepChain(callerArg, deepChain.fields());
            // ...merge into ctx.chainPropagations()...
        }
    }
}
```

The `chainVarsAttachedTo` helper is now parameterless (no longer filters
by a specific param). It iterates `TaintFact.chainEntries()`, a new
read-only view of the `fieldChain` side table exposed by R147.

### 2.5 `FIELDLOAD` Consult Rewrite

The FIELDLOAD consult was previously keyed by 1-step
`FieldChain(lhs, fieldName)`. R147 looks up by the dest's **deep chain**,
computed after `withFieldChain`:

```java
if (ins.lhs1 instanceof TacOperand.TacVar lhs) {
    next = next.withFieldChain(ins.dest, new TaintFact.FieldChain(lhs, ins.fieldName));
    if (ctx != null) {
        TaintFact.DeepChain destDeep = next.deepChainOf(ins.dest);
        if (destDeep != null) {
            java.util.Map<String, java.util.Set<String>> prop =
                    ctx.chainPropagations().get(destDeep);
            if (prop != null && !prop.isEmpty()) {
                for (var e : prop.entrySet()) {
                    next = next.withField(ins.dest, e.getKey(), e.getValue());
                }
            }
        }
    }
}
```

For `String t2' = t1'.b;` (after `String t1' = arg.a;`):
- `t2'.chain = (t1', "b")`
- `t2'.deepChainOf()` walks: `t2' → t1' (with "b") → arg (with "a")`, root `arg`, fields `["a", "b"]`
- Lookup `chainPropagations[(arg, ["a", "b"])]` matches the propagated entry

---

## 3. Why a Side Table (Temporal Bridge)

The chain var `cv` is callee-local. The caller has its **own** chain
var for the same access path, but it doesn't exist yet at the time of
the CALL — the R141b-aware translator creates it at the caller's
successive FIELDLOADs. R146 first used this "temporal bridge" pattern,
R147 generalizes it: the CALL records the propagation, the later
FIELDLOADs consume it as the caller's deep chain materializes link by
link.

This is the same IFDS-style "summary at exit, re-instantiate at entry"
pattern as R141a (`returnTaintCache`) and R145 (`exitTaintCache`), but
the temporal axis is different: R141a/R145 cache by entry-taint
fingerprint (re-running the worklist on the same entry taint is
amortized); R146/R147 cache by chain signature (the chain var in the
caller is created later than the CALL, so direct in-place propagation
would lose the new chain var).

---

## 4. Sibling Isolation Preserved at Every Depth

The side table records **per-field** writes. For a chain var at depth 2
with `fieldMap[cv] = {b: X, c: Y}`, the side table entry is
`(arg, ["a", "b"]) → {b: X, c: Y}`. Reading `cv.d` sees an empty
`d` slot and doesn't fire, matching R118b's weak-update semantics.

Sibling isolation is per-field at the side-table level, so a multi-step
chain write to one field does not bleed taint to sibling fields at the
same or different depths. The `helperChainWriteIsolatesSiblingField`
test in R146 and the new
`twoLevelChainWriteSiblingIsolation` test in R147 both verify this.

---

## 5. Test Coverage

### 5.1 New `MultiStepChainVarTest` (7 tests)

| # | Test | What it verifies |
|---|------|------------------|
| 1 | `twoLevelChainWritePropagatesToCallerTwoLevelChainRead` | Canonical R147: helper `t2.c = X` (chain `(param, ["a","b"])` deep) → caller `sink(t2'.c)` fires |
| 2 | `twoLevelChainWriteSiblingIsolation` | Helper writes `t2.c`, caller reads `t2'.d` — no fire |
| 3 | `twoLevelChainWritePropagatesAcrossTwoHops` | m → wrapper → leaf 2-step chain fire |
| 4 | `oneLevelChainRegressionGuard` | R146 1-step case still fires (regression guard) |
| 5 | `twoLevelChainImplicitCallerRead` | Implicit form: caller reads `arg.a.b.c` without explicit locals, still fires |
| 6 | `noChainWriteInHelperDoesNotFireOnCallerTwoLevelChainRead` | Negative control: helper has no chain write, no fire |
| 7 | `twoLevelChainWritePropagatesToMultipleCallSites` | 2 call sites, 2 findings (side table accumulation) |

### 5.2 Regression Coverage

- `CrossFunctionChainVarTest` (R146): 6 tests, all pass (1-step case
  unchanged under the generalized key)
- `CrossFunctionFieldMapTest` (R145): 7 tests, all pass
- `NestedFieldChainTest` (R141b): 7 tests, all pass
- `ReturnTaintCacheTest` (R141a): 5 tests, all pass
- `TaintPerformanceTest` (R141c): 1 test, all pass (25-hop chain still
  scales linearly)
- All 242 dfa tests + 39 non-dfa tests = **281 total, 0 fail, 1 documented skip**

### 5.3 CLI Smoke (Real Artifacts)

- `D:\r147_e2e\MultiStepChain.java`: 2-step chain → 2 findings
  (CWE-117 + CWE-532) on `logger.info(t2.c)` line
- `D:\r147_e2e\ThreeLevelChain.java`: 3-step chain → 2 findings on
  `logger.info(t2.c)` line
- `D:\r147_e2e\TwoHopWrapper.java`: 2-step chain + m → wrapper → leaf
  → 2 findings
- `D:\r147_e2e\SiblingIsolation.java`: 2-step chain write to `c`,
  caller reads `d` → 0 findings (sibling isolation works in CLI)
- `D:\r147_e2e\OneStepRegression.java`: R146 1-step case still fires
  (regression guard in CLI)

---

## 6. Cumulative Wins (R115 → R147, 32 rounds)

| Round | Capability | Cross-fn/Class | Lines | New tests |
|-------|-----------|----------------|-------|-----------|
| R115 | Sanitizer-aware suppression | call-site | ~50 | +5 |
| R116 | Recursive cross-fn taint (worklist) | fn | ~150 | +3 |
| R117 | MergedProgram (cross-TU) | TU | ~200 | +4 |
| R118a | Per-fn source path | TU | ~30 | +2 |
| R118b | Field sensitivity (RHS writes) | fn | ~80 | +3 |
| R119a | Taint catalog pat() + first-match-wins | — | ~20 | +1 |
| R119b | Catalog endsWith + literal-arg | — | ~20 | +1 |
| R119c | MOVE-chain hardening | fn | ~10 | +1 |
| R120 | `1 << -1` parse + for(;;) jumps | — | ~10 | +1 |
| R121 | Independent TaintCatalog + sink dedup | fn | ~40 | +1 |
| R122 | NEWARRAY literal-sized + list-factory | — | ~15 | +1 |
| R123 | TypeTable wire-up (3-tier receiver) | — | ~80 | +3 |
| R124 | Instance field init via `<init>` | — | ~10 | +1 |
| R125 | 3 annotation channels (class/method/field) | fn | ~120 | +3 |
| R125b | 4th param annotation channel | fn | ~15 | +1 |
| R126 | 5 annotation sets (31 entries) | — | ~40 | +2 |
| R127 | FIELDSTORE fall-through | — | ~5 | +1 |
| R128 | FIELDSTORE for-loop induction | — | ~10 | +1 |
| R131 | Recursive CALL-arg chase (5-hop) | fn | ~80 | +3 |
| R132 | TypeTable type-args + FQN strip | — | ~80 | +2 |
| R133 | 17-entry JDK_CONTAINER_CLASSES | — | ~20 | +1 |
| R134 | 7-entry SAFE_RETURN_TYPES | — | ~30 | +1 |
| R135 | INHERENTLY_SAFE / WRAPPER_TYPES split | — | ~50 | +2 |
| R136 | PetClinic smoke (real code maturity) | — | ~0 | +0 |
| R137a | 90-entry ANNOTATION_TYPE_NAMES + 2-line threshold | — | ~30 | +1 |
| R137b | class-level @PreAuthorize | — | ~10 | +1 |
| R138a | catch param MOVE NULL fix | — | ~3 | +0 |
| R138b | 14-entry FRAMEWORK_NONNULL_METHOD_ANNOTATIONS | — | ~15 | +1 |
| R139 | path segment vs substring boundary | — | ~5 | +1 |
| R140 | SootUp 3.0.0 bytecode front-end | jar/war | ~600 | +0 (infra) |
| R141a | Function summary cache (entry-taint-fp) | fn | ~80 | +5 |
| R141b | Translator cooperation for field chain walk | fn | ~50 | +7 |
| R141c | 30+ layer perf guard | fn | ~5 | +1 |
| R142 | Cross-jar type hierarchy (3 input locations) | jar | ~120 | +4 |
| R143 | Per-class source path in cross-jar scans | jar | ~50 | +5 |
| R145 | Cross-fn fieldMap propagation (IFDS flow comp) | fn | ~80 | +7 |
| R146 | Cross-fn chain-var propagation (side table) | fn | ~150 | +6 |
| **R147** | **Cross-fn deep-chain propagation** | **fn** | **~150** | **+7** |
| Total | — | — | ~3000 | +95 |

- **R147 added 0 IR / 0 TAC / 0 new checker categories** (8th round of
  "0 IR" pattern)
- **R147 added 7 dfa tests**: 235 → 242 (R141b=213, R142=217, R143=222,
  R145=229, R146=235, R147=242), 0 fail, 1 documented skip
- **Total tests**: 281 (242 dfa + 19 tac + 3 parser + 2 rules + 10 style
  + 2 reporter + 3 main)
- **Cumulative wins**: 32 rounds, 39 checker categories, 27+ CWE
  families, 95 new tests since R115

---

## 7. Paper-Ready Insights

### 7.1 "Build infra, then use it" — 8th instance

R141a (return-taint cache) + R141b (intra-fn field chain) + R118b
(fieldMap) + R116 (recursive taint) + R145 (cross-fn fieldMap) + R146
(cross-fn chain var) → **R147** (cross-fn deep chain) is the 8th
instance. The pattern: build propagation infrastructure, then generalize
the keying as new requirements surface. R147's `DeepChain` is the
**natural generalization** of R146's `FieldChain` — same propagation
rule, deeper key.

### 7.2 "TAC as universal IR" — 7th empirical proof

R147's generalization is a **3-line key change** (FieldChain → DeepChain)
+ accessor additions + ~80 lines of propagation + ~80 lines of
FIELDLOAD consult + 7 new tests. The IR (TAC + TaintFact) is unchanged;
the new deep chain is a **derived view** computed by walking existing
side tables. Same payload, different lens.

### 7.3 "Pre-existing bug顺手 fix" — 8th instance

No new pre-existing bug fixed in R147. The R146 `withField`/`with`/
`union` `fieldChain`-preservation fixes (3 lines) carry over and the
new `deepChainOf` benefits from the same 3-arg constructor invariant.
R147 is **net additive**, not a bug fix.

### 7.4 "Side-table temporal bridge" — 2nd instance

R146 introduced the "chain var in caller doesn't exist at CALL time;
side table bridges CALL ↔ FIELDLOAD" pattern. R147 is the same
pattern, but the side table key is now a deep access path, so
multiple FIELDLOADs in the caller (creating the deep chain link by
link) progressively consume the propagated writes. This is the
"temporal-iterative consumption" variant of the R146 pattern.

### 7.5 "域敏感 + 跨过程" 完整闭环

R141b (intra-fn chain walk) + R145 (cross-fn fieldMap) + R146
(cross-fn chain var 1-step) + R147 (cross-fn chain var N-step) =
**域敏感 跨过程 field analysis 完整闭环**. The user-flagged
"域敏感的分析, 跨TU和跨函数的分析" requirement is now satisfied
across all chain depths that real Java code uses. The R141b / R145
/ R146 / R147 four-round sequence is a tractable shape for
field-sensitive IFDS — same R141a entry-taint-fingerprint cache
strategy, same per-call-site propagation rule, with the chain key
depth as the only generalization axis.

---

## 8. Files Changed

| File | Change | Lines |
|------|--------|-------|
| `TaintFact.java` | + `DeepChain` record, + `deepChainOf`, + `chainEntries`, + `ChainEntry` | ~85 |
| `AnalysisContext.java` | Change `chainPropagations` key from `FieldChain` to `DeepChain` (with javadoc rewrite) | ~30 (mostly javadoc) |
| `TaintAnalysis.java` | + `propagateChainVarToCaller` deep-chain walk, + `chainVarsAttachedTo` parameterless, FIELDLOAD consult rewrite | ~50 net |

**New test file**:
- `MultiStepChainVarTest.java` (7 tests, ~14KB)

**New doc**: this file (`docs/R147-CROSS-FUNCTION-DEEP-CHAIN.md`, ~10KB)

---

## 9. R148+ Candidates (Beyond R147)

- **Alias analysis (R117+ points-to infra)**: `helper(myObj);
  helper(myObj2)` same object over-propagate. R117's points-to
  foundation is the starting point.
- **Path-sensitive may-analysis (R148+)**: callee conditional writes
  still conservative. R148 may add a path-sensitive variant.
- **R118c — exception-path taint (CFG exception edges)**: deferred
  since R118, big TAC extension.
- **R140c — real Spring Boot `.war` validation**: CVE-2022-42889 +
  CVE-2021-44228 real recall baseline on a Spring Boot fat jar.
- **TypeTable field-name → type** (deferred from R134): receiver's
  field types for sharper chain matching.
- **Param type-args still lossy** (R135 only handled return type).
- **Diamond `<>` conservative fall-back** (R132/R135 known scope
  limit).
- **`Arrays.asList` through helper chain > 5 hops** (R131 limit).
- **TypeTable → CallResolver wire-up** for type-aware call resolution.
- **Code quality** (OVERLY_BROAD_EXCEPTION, DEADCODE, WRONG_METHOD,
  SWAPPED_ARGUMENTS).
- **Concurrency** (RACE_CONDITION, SERVLET_ATOMICITY, SINGLETON_RACE).
- **Test configuration support** (`-i test` to include test code in
  scan).
- **Case-sensitive flag for path filter** (Linux vs Windows).
- **R138 still misses**: cross-TU @EnableMethodSecurity, custom
  @NonNull annotation, field-init unboxing.
- **Paper writing (R115-R147 contributions, paper §3.7 / §4 / §6)**:
  - "域敏感 + 跨过程 field analysis 完整闭环" — R141b + R145 + R146 + R147
  - "Side-table temporal bridge" pattern — R146 + R147
  - "DeepChain as natural generalization of FieldChain" — R147
  - "Ifds-style flow composition applied to chain walks" — R146 + R147
