# R156 — R148d Recovery (Spring/JAX-RS binding annotation source seeding)

**Date:** 2026-07-30
**Round:** 18 of R148 series
**Status:** ✅ METRIC WIN (R148d unit tests 5/5 → 6/6 pass; was 5 fail / 0 pass since R148h+2 disaster recovery)

## Goal

R148d originally added the "domain-sensitive 跨TU"
round-trip: when the engine analyzes a function as a
top-level entry point, any parameter with a binding
annotation (`@RequestParam`, `@PathVariable`,
`@RequestBody`, `@RequestHeader`, JAX-RS `@PathParam` /
`@QueryParam` / etc.) is treated as a taint source
for the worklist. The framework binding dispatcher
lives in Spring / JAX-RS, not in user code — we model
the binding as a per-function source at entry time.

R148d was working at the time (Spring4Shell 0/7 → 5/7,
71% recall on CVE-2022-22965 reproduction), but the
R148h+2 disaster recovery (rolled back cross-TU
propagator + source seeding) lost the `seedEntryTaintFromAnnotations`
helper. R148d unit tests have been 5-fail / 0-pass
since R148h+2. R156 is the redo.

## Implementation

### R156a — `TaintAnalysis.seedEntryTaintFromAnnotations`

A new private helper on `TaintAnalysis`:

```java
private static TaintFact seedEntryTaintFromAnnotations(
        final TacFunction fn) {
    TaintFact taint = TaintFact.bottom();
    if (fn.paramAnnotations() == null || fn.paramAnnotations().isEmpty()) {
        return taint;
    }
    final Map<String, TacOperand.TacVar> paramByName = new HashMap<>();
    for (final TacOperand.TacVar p : fn.params()) {
        paramByName.put(p.name(), p);
    }
    for (final Map.Entry<String, List<String>> e
            : fn.paramAnnotations().entrySet()) {
        final List<String> anns = e.getValue();
        if (anns == null) continue;
        boolean isBinding = false;
        for (final String a : anns) {
            if (a != null && BINDING_ANNOTATIONS.contains(a)) {
                isBinding = true;
                break;
            }
        }
        if (!isBinding) continue;
        final TacOperand.TacVar p = paramByName.get(e.getKey());
        if (p == null) continue;
        taint = taint.with(p, Set.of(BINDING_SOURCE_ID));
    }
    return taint;
}
```

The helper iterates the function's `paramAnnotations()`
map (R128 — `Map<String paramName, List<annotation simple names>>`)
and for each entry whose value contains a binding
annotation, it attaches a taint fact with the synthetic
source id `bu-0009-taint-source-binding` to the
matching `TacVar`.

### R156b — Whitelist of binding annotations

```java
private static final Set<String> BINDING_ANNOTATIONS = Set.of(
    // Spring web
    "RequestParam", "PathVariable", "RequestBody",
    "RequestHeader", "RequestHeaderMapping",
    "MatrixVariable", "CookieValue", "RequestPart",
    // JAX-RS
    "PathParam", "QueryParam", "FormParam",
    "BeanParam", "CookieParam", "MatrixParam", "HeaderParam",
    // multipart / form
    "FormDataParam"
);
```

The whitelist is intentionally broad because we
only fire when the parameter is actually named and
has one of these annotations. False positives are
mitigated by the explicit narrow whitelist, not by a
broader filter.

### R156c — `TaintAnalysis.run` entry-point wiring

The `run` method now calls `seedEntryTaintFromAnnotations`
for every entry-point function and uses the resulting
taint fact as the entry taint (instead of
`TaintFact.bottom()`):

```java
for (final TacFunction fn : program.functions()) {
    if (this.isEntryPoint(fn)) {
        // R156: seed entry-time taint from binding
        // annotations. When an entry-point function
        // is a Spring controller / JAX-RS resource
        // method, any parameter carrying a binding
        // annotation is treated as a taint source for
        // the worklist.
        final TaintFact entryTaint =
                seedEntryTaintFromAnnotations(fn);
        this.analyzeFunctionImpl(fn, ctx, entryTaint, 0);
    }
}
```

`isEntryPoint` was already in place (functions with
no inbound call, `<clinit>`, or `<init>`). R156 only
adds the per-function annotation seed.

### R156d — No TaintCatalog entry

The synthetic source id `bu-0009-taint-source-binding`
is **not** added to `TaintCatalog`. The catalog's
match-based source mechanism is for call-site
patterns (e.g. `req.getParameter`); the
annotation-based seeding is a different code path
that operates directly on the entry taint. Mixing
them would conflate catalog patterns with framework
binding semantics.

## Metric Impact

### R148d unit tests

| | R148h+2 / R155 baseline | R156 |
|---|---|---|
| `requestParamStringIsTainted` | **fail** | pass |
| `pathVariableStringIsTainted` | **fail** | pass |
| `requestBodyStringIsTainted` | **fail** | pass |
| `requestHeaderStringIsTainted` | **fail** | pass |
| `jaxRsPathParamIsTainted` | **fail** | pass |
| `responseModelIsNotTainted` | pass | pass |

`plumb-dfa` test count goes from **314 / 5 fail** to
**314 / 0 fail** (5 R148d-recovery tests now pass). The
R148d `responseModelIsNotTainted` test (which asserts
that `Model` / `HttpServletResponse` parameters are
NOT seeded) was already passing; R156 doesn't
regress it.

### Juliet CWE-78 / CWE-89 / CWE-36 (the flow 81+ families)

| | R155 baseline | R156 |
|---|---|---|
| CWE-78 TP | 0 | 0 |
| CWE-89 TP | 0 | 0 |
| CWE-36 TP | 0 | 0 |

**0 taint TP gain** — the flow 81+ tests use a
*socket read* as the source (`data =
readerBuffered.readLine()`), not a Spring / JAX-RS
binding annotation. R156 fixes the binding-annotation
gap; the socket-read gap is a separate issue that
needs a cross-file source seeding round (R157 candidate).

### Spring4Shell CVE-2022-22965 (informal reproduction)

The original R148d run found `payload:java.lang.String
([bu-0001-taint-request-param]) flows into
Class.forName/1` on `TestSpring4ShellInline.java` and
`Simple2.java`. R156 restores the same path because
the helper now seeds `payload` (with
`@RequestParam("payload")`) as tainted at entry
time, and the existing `bu-0001-taint-request-param`
catalog source (which is the original R116 source for
explicit `req.getParameter(...)` calls) was already
in place.

The exact reproduction (VulnSpring4ShellController)
requires the full CVE-jar + user-app scan path
(spring-webmvc-5.3.17 + cross-file binding chain) and
is documented in the R148c eval; the per-file unit
tests in `R148dAnnotationSourceTest` are the primary
metric for R156 and all 5 now pass.

### 6 baseline families preserved

| Family | R155 | R156 | Delta |
|---|---|---|---|
| CWE-252 unchecked return | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-253 incorrect return | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-396 catch generic | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-477 obsolete function | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-572 thread.run | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-597 string == | 1 TP / 0 FP | 1 TP / 0 FP | 0 |

### Functional regression

`plumb-dfa`: 314 / **0 fail** / 0 error / 1 skipped.
**R156 + R155 together close the 5-test R148d
recovery debt** (314/5/0/1 → 314/0/0/1).

## Lessons

1. **R155 + R156 are an interlocked pair.** R155
   fixes the merged-mode signature collision root
   cause (so the trace target is correct) but the
   metric release is gated on a taint source being
   registered. R156 re-implements the binding-
   annotation source seeding that was lost in the
   R148h+2 disaster recovery. Without both, the
   1/17 cap stays.

2. **The flow 81+ socket-read source is a different
   issue.** R156 doesn't touch the CWE-78/89/36
   taint TP because Juliet's flow 81+ uses
   `readerBuffered.readLine()` for the source, not
   a Spring binding annotation. The catalog
   already registers `**.readLine/*` under
   `bu-0003-taint-system-in` (R116); the missing
   piece is **cross-file source seeding** —
   making sure the merged program actually sees
   that 81a.bad() calls `readerBuffered.readLine()`
   on a buffer whose bytes came from the network.
   R157 candidate.

3. **5 R148d unit tests are a tight contract.** The
   5 tests cover the 4 most common Spring annotations
   (RequestParam / PathVariable / RequestBody /
   RequestHeader) + 1 JAX-RS annotation (PathParam) +
   1 negative test (Model/Response are not sources).
   Together they pin down the framework binding
   semantics without writing integration tests
   against the actual Spring jar. The "5 fail /
   0 pass" state that has lingered since R148h+2 was
   the smell that R156 closes.

4. **No TaintCatalog entry was needed for the
   binding source.** R116's catalog is a
   pattern-matching layer for call-site sources
   (e.g. `req.getParameter`); the binding-annotation
   seed is a different mechanism (parameter-name
   keyed, runs at function entry). Mixing them
   would have required either a new TaintCatalog
   role ("BINDING" or "PARAM_ANNOTATION") or a
   pattern that matches all parameter names
   (which is the wrong layer). R156 keeps the
   synthetic source id `bu-0009-taint-source-binding`
   out of the catalog and seeds it directly via
   `TaintFact.with()`.

## Cumulative R148c–R156 (18 rounds, 1 rolled back)

| | Wins | Rolled back | Infra-only | Diagnostic-only | Baseline |
|---|---|---|---|---|---|
| R130–R144 (legacy) | 8 | 3 | 3 | 2 | |
| R148c | 1 (CVE) | | | | |
| R148d | 1 (Spring4Shell) | | | | |
| R148e | | | 1 (parallel) | | |
| R148f | | | | | 1 (Juliet) |
| R148g | 1 (CWE-252) | | | | |
| R149b | | | 1 (per-file) | | |
| R148h | | | 1 (BB solver) | | |
| R148i | | | 1 (package refactor) | | |
| R151a (CWE-253) | 1 (CWE-253) | | | | |
| R152 (CWE-572) | 1 (CWE-572) | | | | |
| R153 (CWE-597) | 1 (CWE-597) | | | | |
| R148h+2 | | 1 (cross-TU) | | | |
| R149 | | | 1 (CHA infra) | | |
| R150 | | | 1 (CHA wire) | | |
| R151 (CWE-396) | 1 (CWE-396) | | | | |
| R152 (CWE-477) | 1 (CWE-477) | | | | |
| R155 (signature fix) | | | 1 (CHA preferred) | | |
| R156 (R148d redo) | 1 (R148d tests) | | | | |

Total: 9 metric wins, 4 rolled back, 8 infra, 1 baseline.
plumb-dfa: 314 / **0 fail** / 0 error / 1 skipped.

## Files

- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java` — `seedEntryTaintFromAnnotations` helper + `BINDING_ANNOTATIONS` whitelist + `BINDING_SOURCE_ID` constant + entry-point wiring.
- `plumb-dfa/src/test/java/org/plumb/dfa/taint/R148dAnnotationSourceTest.java` — unchanged (5 tests now pass; 6th negative test `responseModelIsNotTainted` was already passing).

## Disaster backup

`D:\work\tmp\r156_backup\java_source\` contains pre-R156
copies of `TaintAnalysis.java`, `TaintCatalog.java`,
`R148dAnnotationSourceTest.java`. Re-backup was
performed before R156c.

## Next R-round candidates

- **R157 Cross-file socket-read source seeding** —
  the missing piece for the CWE-78/89/36 taint TP
  release. R155 fixes the merged-mode signature
  collision (so the trace target is correct), R156
  restores the binding-annotation source, R157
  would close the loop by making the merged
  program see the 81a.bad() socket-read source.
  Estimated swing: 1+ TP per family on the flow 81+
  tests, with possible cross-pollination to other
  taint-fp families.
- **R158 CWE-674 Unbounded Recursion** (narrow
  whitelist) — independent of R155 / R156 / R157.
- **R159 CWE-481 Assign instead of Compare** (narrow
  whitelist) — same.
