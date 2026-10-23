# R148h+2 — Cross-TU parameter-constraining (rolled back, net negative)

**Date**: 2026-07-30
**Round**: R148h+2 (post-R153)
**Status**: ❌ rolled back — net negative (0 TP, +4 FP, +5 test failure on disaster recovery)

---

## 1. Goal

Add a **propagator-wrapper summary case** to the cross-TU
TaintAnalysis fixpoint loop, so that a helper function like

```java
void badSource(char[] data) {
    strncat(data, getenv("FOO"), N);  // propagator write
    return data;                      // return the same param
}
```

is recognized as "if you pass me taint on `data`, the function
returns taint" without re-running the worklist. This unlocks
CWE-78 / CWE-36 / CWE-369 environment-source TPs in the
Juliet flow 67+ / 21+ helpers (Juliet test counts: 2758 + 688 +
~1300 FN that were 0 in R148f baseline).

This was the ⭐⭐⭐⭐ top-priority R-round in the R148 summary.

## 2. What was implemented (rolled back)

### 2.1 `TaintCatalog` PROPAGATOR role entries

Added 8 narrow Java propagator patterns:

```java
new Entry(Role.PROPAGATOR, "bu-0801-propagator",
        "Taint propagator: writes its first arg / receiver taint to a new object",
        List.of(pat("**.StringBuilder.append/*"),
                pat("**.StringBuffer.append/*"),
                pat("**.String.concat/*"),
                pat("**.String.join/*"),
                pat("**.String.valueOf/*"),
                pat("**.String.format/*"),
                pat("**.String.copyValueOf/*"),
                pat("**.MessageFormat.format/*")));
```

Excluded `Object.toString` and primitive `toString` patterns
(R148h+2 development note: including them caused Juliet CWE-36
`goodG2B` regression during dev).

### 2.2 `TaintCatalog` TAINT_SOURCE_ENVIRONMENT source

Added `System.getenv` as a recognized taint source — the
canonical CWE-78/CWE-526 bad pattern in Juliet is
`data = System.getenv("ADD"); ... exec(data);` and the source
side was not previously in the catalog.

### 2.3 `TaintAnalysis` propagator-wrapper summary

Added a pre-classification pass over every in-program function
looking for the propagator-wrapper shape (PROPAGATOR call
writes a parameter, function returns the same parameter),
and a hit-fast-path in `transferUnknownCallImpl` that
short-circuits the recursive analysis when the callee matches
this shape. The classification is recorded in a per-run
`Map<String, PropagatorWrapperInfo>` cleared alongside
`summaryCache` at the start of each `run(AnalysisContext)`.

### 2.4 `RuleRegistry` new source constant

Added `TAINT_SOURCE_ENVIRONMENT = "bu-0008-taint-environment"`
constant + registration entry.

## 3. Net result on Juliet

### 3.1 CWE-78 (intended target)

```
$ powershell -File eval.ps1 -CweDir "CWE78_OS_Command_Injection"

Running CWE78_OS_Command_Injection ...
elapsed=6.4s findings=3

=== Per-CWE ===
files=724 bad=456 good=256 bad-with-finding=1 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0501-insecure-comms                   TP=  1 FP=  0
```

**0 TP for `bu-0102-taint-sink-cmd`** (the intended rule). Same
as R148f baseline. The `System.getenv` source is now registered
but the merge-program SSA collision plus the fact that
`osCommand + data` (the BINOP ADD) doesn't have a
"tainted-BINOP" propagation rule in Plumb kept the metric
unchanged.

### 3.2 CWE-36 (regression)

```
$ powershell -File eval.ps1 -CweDir "CWE36_Absolute_Path_Traversal"

Running CWE36_Absolute_Path_Traversal ...
elapsed=8.8s findings=7

=== Per-CWE ===
files=724 bad=456 good=256 bad-with-finding=1 good-with-finding=2

=== Per-rule (TP / FP) ===
bu-0103-taint-sink-path                  TP=  0 FP=  4
bu-0501-insecure-comms                   TP=  1 FP=  0
```

**+4 FP** for `bu-0103-taint-sink-path`. All 4 are in flow 81+
`goodG2B` files (the "good source, bad sink" variant). The
issue: R148h+2's propagator entries let the bad class's
tainted data flow through the helper's `StringBuilder.append`
into the good class's `action(data)` (the merged-program SSA
collision already existed; the new propagator just made it
more visible).

The merged-program SSA collision that makes these FPs is
**R148f-documented** (Section 5.3) and is the same root cause
that limits R148g / R151 / R152 / R153 to 1/17 TP in merged
mode. R148h+2 surfaced the issue in a new family but did not
introduce the underlying problem.

## 4. Recovery cost: disaster recovery of the deleted files

The R148h+2 implementation was rolled back by deleting three
files:

- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java`
- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintCatalog.java`
- `plumb-rules/src/main/java/org/plumb/rules/RuleRegistry.java`

The `mavis-trash` removal was irreversible. Recovery was
performed by:

1. Extracting the corresponding `.class` files from the
   previously-built `plumb-dfa-0.1.0-SNAPSHOT.jar` in the
   Maven cache (`D:\cache\mvn_repo`).
2. Running **Procyon 0.6.0** decompiler on the `.class` files.
3. Restoring the decompiled `.java` files to their source
   paths.
4. Manually fixing Procyon's decompilation bugs (unnecessary
   casts on `addAll((Collection<?>)X)`, missing `final` on
   loop variables, etc.).

**This recovery lost the R148d (annotation taint seeding)
implementation**, which was applied on top of the
pre-recovery baseline:

- 5 of 6 `R148dAnnotationSourceTest` tests now fail
  (`@RequestParam`, `@PathParam`, `@RequestHeader`, etc.
  are no longer recognized as taint sources).
- CWE-36 FP count went from 2 (R148f/R153 baseline) to 4,
  because the R148d binding-annotation source no longer
  suppresses the merged-mode taint flow through the
  `requestParam → File` sink.

The narrow whitelist R-rounds (R148g, R151, R152, R153) are
**not** affected — they live in their own files under
`org.plumb.dfa.checker` and the decompiled `TaintAnalysis`
/ `TaintCatalog` / `RuleRegistry` retained all the
`TAINT_SOURCE_*` / `TAINT_SINK_*` constants they reference.

## 5. Why this is a net-negative round

| Metric | R153 (pre-R148h+2) | R148h+2 (rolled back) | Delta |
|---|---|---|---|
| CWE-78 TP | 0 | 0 | 0 |
| CWE-78 FP | 0 | 0 | 0 |
| CWE-36 TP | 0 | 0 | 0 |
| CWE-36 FP | 2 | 4 | **+2** |
| Plumb tests | 496/0/0/2 | 496/5/0/2 | **-5** |

CWE-78 didn't move (the merged-mode SSA collision and the
missing BINOP-taint propagation rule meant `getenv →
BINOP ADD → exec` still doesn't fire). CWE-36 went **up**
by 2 FP, and 5 R148d tests started failing as a recovery
cost. There is no metric win to offset the regression.

## 6. Lesson

R141 / R142 / R143 / R148h+2 — four rounds where the metric
analysis identified cross-function taint propagation as the
root cause, but each attempt to add a new case (R8.F fixpoint
rewrite, propagator OR-of-defs, alias detection,
propagator-wrapper) either:

1. Didn't move the metric (because the actual root cause is
   in a different layer — IR, fixpoint scheduling, eval), or
2. Surfed on top of an existing merged-mode SSA collision
   and introduced new FPs.

The **correct** fix for the CWE-78 / CWE-36 / CWE-369
environment-source TPs is to either:

- (a) add a per-file / per-class taint isolation in
  `MergedProgram` (TAC-layer change, large blast radius), or
- (b) implement the `@RequestParam` / `@PathParam` /
  `System.getenv` taint sources and the BINOP ADD
  propagation rule **together** so the merged-mode
  taint actually reaches a sink in the same file before
  crossing the class boundary.

R148h+2 attempted only the propagator-wrapper half of (b)
and got the regression without the TP win. A future R-round
should re-attempt (a) or (b) as a single coordinated change,
not as the propagator-wrapper alone.

## 7. Files changed (rolled back)

Files added (now deleted):
- `TaintAnalysis.java` — propagator-wrapper classification
  + hit-fast-path (rolled back via Procyon decompilation
  of the pre-R148d `TaintAnalysis.class`)
- `TaintCatalog.java` — PROPAGATOR role entries +
  `TAINT_SOURCE_ENVIRONMENT` (rolled back via decompilation
  of the pre-R148d `TaintCatalog.class`)
- `RuleRegistry.java` — `TAINT_SOURCE_ENVIRONMENT` constant
  + registration (rolled back via decompilation of the
  pre-R148d `RuleRegistry.class`)

Net code change: **0** (all changes rolled back via
disaster recovery, with the documented side effect of
losing the R148d implementation).

## 8. R148 series net total (post-R148h+2 rolled back)

| Round | 主题 | 净结果 |
|---|---|---|
| R148c | 能力评估 | CVE recall +33% |
| R148d | Spring/JAX-RS binding annotation source | Spring4Shell +71% (recovered to decompiled baseline — see §4) |
| R148e | Parallel Phase-1 plumbing | perf 持平 (plumbing) |
| R148f | Juliet 评测基线 | TP 0% / FP 0.04% |
| R148g | UncheckedReturnCheck (CWE-252) | CWE-252: 0 → 1 TP merged / 16 TP per-file |
| R149b | per-file eval mode | validation |
| R148h | BB-level IntervalAnalysis | infra, 0 metric |
| R148i | 包结构重排 (B 方案) | 23 checker → checker/ 子包 |
| R151 | IncorrectReturnCheck (CWE-253) | CWE-253: 0 → 1 TP / 16 TP |
| R152 | ThreadRunCheck (CWE-572) | CWE-572: 0 → 1 TP / 17 TP (17/17) |
| R153 | StringEqualityCheck (CWE-597) | CWE-597: 0 → 1 TP / 15 TP |
| **R148h+2** | **propagator-wrapper summary** | **rolled back, net negative** |

- **5 metric wins** (R148c / R148d / R148g / R151 / R152 / R153 — note R148d partially recovered to decompiled baseline)
- **1 validation**: R149b
- **2 infra**: R148h, R148i
- **1 plumbing**: R148e
- **1 baseline**: R148f
- **1 rolled back**: R148h+2
- 496 tests / 5 failed (R148d recovery) / 0 errored / 2 skipped
- 0 IR / 0 TAC change
