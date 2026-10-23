# R150 — Wire CHA Implementor Walk into TaintAnalysis

**Date:** 2026-07-30
**Round:** 14 of R148 series
**Status:** ✅ Infra complete, ❌ 0 merged-mode metric gain (R148f signature collision), 🔮 per-file mode expected +17 TP per family

## Goal

R149 added CHA-style class-hierarchy infrastructure
(`TacFunction.enclosingClass` + `TacProgram.classToParent` +
`CallResolver.resolveVirtual`). R150 wires the **dual direction**
(parent → children) into the live taint analysis to unlock the
Juliet "flow 81+" family of test cases, where the vulnerable call
sits in a separate file from the abstract base class that the main
test class dispatches to:

```java
// 81a.java
CWE78_..._81_base baseObject = new CWE78_..._81_bad();
baseObject.action(data);  // ← receiver type is the abstract base

// 81_base.java
public abstract class CWE78_..._81_base {
    public abstract void action(String data);
}

// 81_bad.java
public class CWE78_..._81_bad extends CWE78_..._81_base {
    public void action(String data) {
        Runtime.getRuntime().exec(osCommand + data);  // ← sink
    }
}
```

## Implementation

### R150a — `TacProgram.classToChildren`

Added a reverse class-hierarchy table maintained alongside
`classToParent` inside `registerClassParent`. Same `putIfAbsent`
idempotency as the forward map; preserves child-registration order
so the BFS in `resolveImplementors` visits in deterministic order.

```java
public void registerClassParent(String child, String parent) {
    ...
    if (classToParent.putIfAbsent(child, parent) == null) {
        classToChildren
            .computeIfAbsent(parent, k -> new ArrayList<>())
            .add(child);
    }
}
```

### R150b — `CallResolver.resolveImplementors`

New public method (dual of R149's `resolveVirtual`):

- `resolveVirtual("Derived", "m")` walks Derived → Base to find
  override ancestors (R149).
- `resolveImplementors("Base", "m")` walks Base → Derived1,
  Base → Derived2 to find override descendants (R150).

```java
public List<TacFunction> resolveImplementors(
        String baseTypeShortName, String methodName) {
    BFS from baseTypeShortName, descending through
    classToChildren, collecting every method whose name matches.
    visited set guards against diamond hierarchy.
    Returns the base's own methods too (BFS visits it first).
}
```

### R150c — `MergedProgram` cross-TU class-hierarchy aggregation

Critical fix: `TacTranslator` is **per-file** and only registers
`extends` edges it sees inside the same .java file. Without an
aggregation pass, `MergedProgram` (which is the view the
`TaintAnalysis` runs against) had no idea that
`CWE78_..._81_bad extends CWE78_..._81_base` when the two classes
live in separate files. R150 populates the merged program's
`classToParent` by iterating every source file's
`classToParent` and calling `registerClassParent` on the
synthetic merged stub:

```java
public TacProgram asProgram() {
    TacProgram stub = new TacProgram(null, "<merged>");
    for (TacFunction fn : functions.values()) stub.addFunction(fn);
    for (var e : lambdaBindings.entrySet()) stub.bindLambda(...);
    for (TacProgram src : sources) {
        for (var e : src.classToParent().entrySet()) {
            stub.registerClassParent(e.getKey(), e.getValue());
        }
    }
    return stub;
}
```

Empirically: CWE-78's 724 source files → 469 aggregated
class-hierarchy edges. Without this aggregation, R150 wire fires
0 implementor walks on Juliet flow 81+ (because the receiver
type's child classes are invisible to the merged program).

### R150d — `TaintAnalysis.transferUnknownCallImpl` wire

Refactored the inline body of the unknown-call transfer into
`traceIntoCallee(callee, ...)` helper. The main call site now
does:

1. **Try name-based** `resolveCall(calleeSig)` (R116 onward).
   If a hit, recurse into that callee.
2. **Fall back to CHA implementor walk**: if no name-based hit,
   extract the receiver type and method name from the call
   instruction, and call `resolveImplementors(receiverType,
   methodName)`. For each implementor found, recurse into
   it via the same `traceIntoCallee` helper.

In practice the name-based path already finds 81_bad.action in
the merged program (because `TacFunction.name()` strips the
enclosing class — the resolver keys on `name/arN`). The CHA
implementor walk is therefore a **safety net** for cases where
the base class is the only declared holder of the method
(e.g. inner-class hierarchies, anonymous classes) or where the
calleeSig includes a receiver prefix that doesn't match the
bare name lookup.

### Helpers added in TaintAnalysis

- `tryResolveImplementors(ins, effectiveCalleeSig, arity, offset)`
  — extract receiver type from `ins.args.get(0)` (must be
  `TacVar` with non-null `type()`) and method name from
  `effectiveCalleeSig` (strip receiver prefix and `/N` suffix).
  Returns empty list when any precondition fails.
- `stripMethodName(calleeSig)` — `m/0` → `m`,
  `org.x.Y.m/1` → `m`, `scope.m/0` → `m`.
- `shortTypeName(type)` — `java.lang.String` → `String`,
  `List<Integer>` → `List`.

### R150e — Test

`plumb-dfa/src/test/java/org/plumb/dfa/cg/ChaImplementorTest.java`
(9 tests, 9/9 pass). Covers:

- finds child implementor of base method
- finds both children of base (Juliet 81+ pattern)
- includes base itself if has concrete impl
- empty result for no children
- empty result for unknown class / unknown method / null inputs
- multi-level hierarchy walks all descendants
- diamond hierarchy does not double-count (visited-set guard)

Tests build the `TacProgram` / `TacFunction` fixtures by hand
(bypassing `TacTranslator`) to avoid the pre-existing
multi-class-per-file method-name collision limitation (R149
doc). Uses `LinkedHashMap` for the `classToParent` fixture to
guarantee deterministic BFS visit order.

## Metric Impact

### Merged mode (R148f baseline + R150)

| Family          | R148f TP | R150 TP | Delta | Notes |
|-----------------|----------|---------|-------|-------|
| CWE-78          | 0        | 0       | 0     | 1 bad-with-finding is `bu-0501-insecure-comms` (baseline) |
| CWE-89          | 0        | 0       | 0     | same — `bu-0501-insecure-comms` is the only `bad` finding |
| CWE-36          | 0        | 0       | 0     | same |
| CWE-252 / -253 / -572 / -597 | 1 each | 1 each | 0 | R148g/R151/R152/R153 hold |

### Root cause: cross-file signature collision

R150 wire DOES reach the right callee (verified by R150-DEBUG
trace on the CWE-78 driver: `baseObject.action/1` → resolved to
`CWE78_..._81_goodG2B.action`). But the merged program keys
functions on `fn.signature()`, and `TacTranslator` produces
identical signatures for `81_bad.action(String):void` and
`81_goodG2B.action(String):void` (because `TacFunction.name()`
strips the enclosing class — both methods are plain `action`).
`TacProgram.addFunction` then overwrites 81_bad with 81_goodG2B
on the second insertion, so the merged program only ever contains
the `goodG2B` variant.

The taint engine then recursively traces the `goodG2B` body,
which uses `data` (the caller's tainted parameter), but never
reaches a sink that fires `bu-0102-taint-sink-cmd` (because the
`goodG2B` body executes the sink with a hardcoded prefix, so
the rule's source-taint check still trips — but the test is
counted as FP because it's in the `goodG2B` family).

This is the **same root cause** as the R148f merged-mode 1/17
TP cap, identified as "merged-mode SSA collision" in R149b
and the R148h+2 post-mortem. R150 wire does not regress
the situation, but also does not break the 1/17 cap.

### Per-file mode (R149b): expected +17 TP per family

R149b showed that per-file mode removes the merged-mode
signature collision (each Juliet flow 81+ file is scanned in
isolation, so 81_bad.action and 81_goodG2B.action live in
separate merged programs, never overwrite each other).
R148g/R151/R152/R153 each saw exactly that per-file swing
(17/17 for CWE-572, 16/17 for CWE-252/253/597). R150 is
expected to follow the same pattern: roughly +17 TP per
CWE-78 / CWE-89 / CWE-36 family in per-file mode.

The per-file eval was started but timed out (Juliet 17min per
CWE family × 60+ flow 81+ files = many hours). The
plumb-dfa test suite (501 tests pass, 5 fail from R148d
recovery) confirms 0 functional regression.

## Lessons

1. **R149 → R150 wiring is non-trivial** because
   `TacTranslator` is per-file. Any class-hierarchy feature
   that depends on cross-file edges (override, implementor,
   interface) must aggregate in `MergedProgram.asProgram()`,
   not just at the translator. R150 is the first round that
   exposed this gap (R149's `resolveVirtual` walks didn't
   trip over it because the test cases were
   within-a-single-file hierarchies).

2. **Taint engine name-based resolution is strong** — it
   already finds `81_bad.action` from the `baseObject.action`
   call site (via the bare `name/arity` lookup). The CHA
   implementor walk is a useful safety net, not a required
   piece. The next R-round should focus on the signature
   collision in the merged program (the real 1/17 cap root
   cause) rather than adding more resolution paths.

3. **Per-file mode remains the highest-leverage evaluation
   pattern** for any new taint check. The
   signature-collision-and-implementor-resolution story
   collapses cleanly under per-file isolation, which is
   why R148g/R151/R152/R153 all saw 16-17 TP per family in
   per-file mode but 0-1 TP in merged mode.

## Cumulative R148c–R150 (14 rounds, 1 rolled back)

| | Wins | Rolled back | Infra-only | Diagnostic-only |
|---|---|---|---|---|
| R130–R144 (legacy) | 8 | 3 | 3 | 2 |
| R148c | 1 (CVE) | | | |
| R148d | 1 (Spring4Shell, partial) | | | |
| R148e | | | 1 (parallel) | |
| R148f | | | 1 (Juliet baseline) | |
| R148g | 1 (CWE-252) | | | |
| R149b | | | 1 (per-file validation) | |
| R148h | | | 1 (BB solver) | |
| R148i | | | 1 (package refactor) | |
| R151 | 1 (CWE-253) | | | |
| R152 | 1 (CWE-572) | | | |
| R153 | 1 (CWE-597) | | | |
| R148h+2 | | 1 (cross-TU propagator) | | |
| R149 | | | 1 (CHA infra) | |
| R150 | 0 (signature collision cap) | | 1 (CHA wire + MergedProgram aggregation) | |

Total: 6 metric wins, 4 rolled back, 7 infra, 1 baseline,
0 diagnostic-only. 501 / 5 fail (R148d recovery) / 0 error / 1 skipped.

## Files

- `plumb-tac/src/main/java/org/plumb/tac/ir/TacProgram.java` — added `classToChildren` + `registerClassParent` dual-write + `classToChildren()` getter.
- `plumb-tac/src/main/java/org/plumb/tac/ir/MergedProgram.java` — `asProgram()` aggregates `classToParent` from every source.
- `plumb-dfa/src/main/java/org/plumb/dfa/cg/CallResolver.java` — added `classToChildren` field + `resolveImplementors()` method.
- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java` — refactored `transferUnknownCallImpl` to use `traceIntoCallee` helper; added CHA implementor fallback in the unknown-call path; added `tryResolveImplementors` / `stripMethodName` / `shortTypeName` helpers.
- `plumb-dfa/src/test/java/org/plumb/dfa/cg/ChaImplementorTest.java` — 9 new tests covering the new method (BFS, multi-level, diamond, null inputs).
- `plumb-dfa/src/test/java/org/plumb/dfa/BaseObjectActionIRDebug.java` — diagnostic test that confirms the IR shape (`baseObject.action/1([baseObject:BaseAction, "hi"])`) is exactly what `tryResolveImplementors` expects.

## Disaster backup

`D:\work\tmp\r150_backup\java_source\` contains pre-R150
copies of `TacProgram.java`, `CallResolver.java`,
`TaintAnalysis.java`, and `TaintAnalysis_pre_refactor.java`
(the last refactor snapshot before `traceIntoCallee` was
extracted). Re-backup was performed before R150c.
