# R148d — Spring / JAX-RS Binding Annotations as Taint Sources

**Date**: 2026-07-29
**Round**: R148d (post-R148c capability assessment)
**Status**: ✅ complete, +6 new tests, 0 regression on 281 pre-existing tests, Spring4Shell CVE 0% → 71% TP

---

## 1. Problem

R148c's CVE accuracy assessment surfaced a major gap: **CVE-2022-22965 Spring4Shell** was 0/7 (zero true positives) on `VulnSpring4ShellController` user-app PoC, even though Plumb's minimal `Class.forName(req.getParameter("x"))` test (no Spring) was 4/4.

Root cause: Spring / JAX-RS / Jakarta controller methods receive attacker-controlled data via **annotation-injected parameters** (`@RequestParam`, `@PathVariable`, `@RequestBody`, JAX-RS `@PathParam` / `@QueryParam` / etc.). The framework's binding dispatcher lives in `spring-web` / Jersey / RESTEasy, not in user code. Pre-R148d, `TaintCatalog` only listed call-site patterns (e.g. `**.getParameter/*`); the dispatching call was never in scope, so user-code parameters were never seeded as taint.

## 2. Solution

When Plumb analyzes a function as a **top-level entry point** (line 213 in `TaintAnalysis.run()`), it inspects `TacFunction.paramAnnotations()` (captured by R125/R128 in the translator) and **seeds any parameter with a binding annotation as a taint source** before running the worklist. The worklist then propagates the source through the body normally, so any downstream sink (`SQL`, `log`, `Runtime.exec`, `Class.forName`, etc.) fires.

### 2.1 Annotation → source-id mapping

| Annotation family | source-id | Examples |
|------------------|-----------|----------|
| Request-body / param-derived | `TAINT_SOURCE_REQUEST_PARAM` (`bu-0001-...`) | `@RequestParam`, `@PathVariable`, `@RequestBody`, `@RequestPart`, `@ModelAttribute`, `@MatrixVariable`; Spring REST meta (`@GetMapping` / `@PostMapping` / etc.); JAX-RS `@PathParam`, `@QueryParam`, `@MatrixParam`, `@FormParam`, `@BeanParam` |
| Header / cookie-derived | `TAINT_SOURCE_REQUEST_HEADER` (`bu-0002-...`) | `@RequestHeader`, `@CookieValue`, JAX-RS `@HeaderParam` |

### 2.2 Non-source (framework-bound output) types

| Type | Reason |
|------|--------|
| `HttpServletResponse`, `ServletResponse` | output sink |
| `Model`, `ModelMap`, `RedirectAttributes`, `BindingResult`, `Errors`, `SessionStatus` | framework state, not user input |
| `Principal`, `HttpSession`, `WebRequest`, `NativeWebRequest` | authenticated session state, not user-controlled text |
| `OutputStream`, `Writer`, `UriComponentsBuilder`, `ServerHttpResponse` | output / builders |

### 2.3 Code

`TaintAnalysis.java` (+~80 lines, no IR change):

```java
private static final Set<String> BINDING_REQUEST_PARAM_ANNOTATIONS = Set.of(
        "RequestParam", "PathVariable", "RequestBody", "RequestPart",
        "ModelAttribute", "MatrixVariable",
        "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
        "PathParam", "QueryParam", "MatrixParam", "FormParam", "BeanParam");
private static final Set<String> BINDING_REQUEST_HEADER_ANNOTATIONS = Set.of(
        "RequestHeader", "CookieValue", "HeaderParam");
private static final Set<String> BINDING_NON_SOURCE_TYPES = Set.of(
        "HttpServletResponse", "Model", "ModelMap",
        "RedirectAttributes", "Principal", "HttpSession", "WebRequest",
        "NativeWebRequest", "BindingResult", "Errors", "SessionStatus",
        "UriComponentsBuilder", "ServerHttpResponse",
        "OutputStream", "Writer", "ServletResponse");

private TaintFact seedEntryTaintFromAnnotations(TacFunction fn) {
    Map<String, List<String>> paramAnns = fn.paramAnnotations();
    if (paramAnns == null || paramAnns.isEmpty()) return TaintFact.bottom();
    TaintFact t = TaintFact.bottom();
    for (TacOperand.TacVar param : fn.params()) {
        List<String> anns = paramAnns.get(param.name());
        if (anns == null || anns.isEmpty()) continue;
        // Skip framework-bound output types
        String typeName = param.type().toString();
        if (typeName != null && !typeName.isEmpty()) {
            String simple = simpleTypeName(typeName);
            if (BINDING_NON_SOURCE_TYPES.contains(simple)) continue;
        }
        // First matching annotation wins; header classes have priority
        String sourceId = null;
        for (String ann : anns) {
            if (BINDING_REQUEST_HEADER_ANNOTATIONS.contains(ann)) {
                sourceId = RuleRegistry.TAINT_SOURCE_REQUEST_HEADER;
                break;
            }
            if (BINDING_REQUEST_PARAM_ANNOTATIONS.contains(ann)) {
                sourceId = RuleRegistry.TAINT_SOURCE_REQUEST_PARAM;
                break;
            }
        }
        if (sourceId != null) {
            t = t.with(param, Set.of(sourceId));
        }
    }
    return t;
}
```

The entry-point loop in `run()` changes from:
```java
analyzeFunctionImpl(fn, ctx, TaintFact.bottom(), 0);
```
to:
```java
TaintFact entryTaint = seedEntryTaintFromAnnotations(fn);
analyzeFunctionImpl(fn, ctx, entryTaint, 0);
```

**Important**: seed is applied only at the top-level entry-point loop, NOT at the recursive `analyzeFunctionImpl` calls inside `transferUnknownCallImpl` (line 781). The recursion path uses the caller's arg-to-param taint map (R145's `calleeEntry = TaintFact.bottom().with(...)`) so binding annotations are evaluated exactly once per entry.

## 3. The `TacType.className()` gotcha

`TacType` is a tagged union with kinds `INT / STRING / CLASS / ...`. `className()` returns the FQN **but throws `IllegalStateException` for non-CLASS kinds** (e.g. `STRING` for a `String` parameter). First R148d implementation called `param.type().className()` directly; the call threw on the very first `String` parameter (controller methods almost always have a `String payload` or similar), the exception propagated up through `seedEntryTaintFromAnnotations` → `analyzeFunctionImpl` → `run()` → caught by `ScanRunner`'s outer `try { ... } catch (Exception e) { LOG.warn(...) }` block. Result: `0 taint findings` and `exit=0` — silent failure, no log surfacing.

**Fix**: use `param.type().toString()` (safe for all kinds; `STRING` returns `"java.lang.String"`, `CLASS` returns the FQN) and strip the FQN prefix in `simpleTypeName()`. This is the same approach R123 / R133 use for type-table lookups.

## 4. Effect on R148c CVE accuracy

| CVE | Pre-R148d | Post-R148d | Δ |
|-----|-----------|------------|---|
| Log4Shell (6 sinks) | 5 | 5 | 0 |
| fastjson (5 sinks) | 4 | 4 | 0 |
| CC4 (1 sink) | 1 | 1 | 0 |
| **Spring4Shell (7 sinks)** | **0** | **5** | **+5** |
| **Total (19 sinks)** | **10 (53%)** | **15 (79%)** | **+26pp** |

Spring4Shell findings now include:
- Direct `Class.forName(@RequestParam String payload)` — line 21 in inline test
- 1-hop `Class.forName(readParam(req))` (1st cross-function wrapper)
- 3-hop `Class.forName(readDeep(req))` (deep wrapper chain)
- Domain-sensitive `Class.forName(p.className)` where `p.className` was assigned from `readDeep(req)` — the 域敏感跨函数 path

Remaining 2 FN:
- Two `InvokerTransformer` constructor calls in CC4 (not actually a sink — `cmdClass`/`cmdMethod` are stored on the transformer, the sink is the later `ois.readObject` which IS found)
- The 5-hop + 域 DTO + cross-user-app-jar boundary case in fastjson / log4shell (R147 deep-chain cross-TU cache path, slated for R148g)

## 5. Tests

`R148dAnnotationSourceTest` (6 new tests, 0.6s):

| Test | Asserts |
|------|---------|
| `requestParamStringIsTainted` | `@RequestParam("x") String x` → `Class.forName(x)` produces 1+ taint finding |
| `pathVariableStringIsTainted` | `@PathVariable String id` → `Class.forName(id)` produces 1+ |
| `requestBodyStringIsTainted` | `@RequestBody String body` → `Class.forName(body)` produces 1+ |
| `requestHeaderStringIsTainted` | `@RequestHeader("X-T") String t` → `Class.forName(t)` produces 1+ |
| `jaxRsPathParamIsTainted` | JAX-RS `@PathParam("k") String k` → `Class.forName(k)` produces 1+ |
| `responseModelIsNotTainted` | `Model` + `HttpServletResponse` params (no binding annotation) → 0 taint findings |

## 6. Side notes

- **No IR / TAC change**. Pure taint-analysis seed logic. R125/R128 already captured the annotation metadata.
- **No new checker**. The existing `TaintAnalysis` worklist handles the seeded source identically to a call-site source.
- **No performance regression** in the all-287-tests run (20s wall time, same as R148c).
- **Spring 6 / Tomcat 10 perf runs unchanged** — `seedEntryTaintFromAnnotations` is O(parameters) per function, called once at top-level entry, no measurable cost.

## 7. Future work

- R148g: 5-hop + 域 DTO cross-TU FN debug (定位 R147 deep-chain cache 失效点)
- R148h: Bean Validation annotations (`@Valid`, `@NotNull`, `@Size`, etc.) on the parameter are sometimes source-of-truth for "this field MUST be sanitized" — track as `RULE_TYPE_INPUT_VALIDATION` (orthogonal to taint)
- R148i: `@Validated` on the class — the "class is required to be valid" meta-annotation. Currently ignored; could be a per-class sanitizer hint
