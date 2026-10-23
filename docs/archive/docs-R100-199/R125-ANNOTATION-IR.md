# R125 — Annotation-Aware IR

**Status:** complete (2026-07-27)
**Commits covered:** R125a (IR extensions) + R125b (consumer wiring)
**Tests:** +10 dfa (`AnnotationSuppressionTest`), 128 dfa + 1 skip total
**End-to-end:** 102 findings on `examples/` (was 98 in R124, +4 from R125 demo); zero regression

## Why

Several R124-deferred items and R125+ candidates need to know which
**annotations** decorate a class, method, or field. Until R125, the
TAC IR carried only the *shape* of the program — instruction stream,
CFG, types, but no metadata. The most common requests:

- **MISSING_AUTHZ** (R119c deferred) — flag a controller method that
  lacks `@PreAuthorize` / `@RolesAllowed`
- **@Value suppression** (R124 deferred) — stop flagging
  `private String password = "default"` when the field is annotated
  with Spring's `@Value("${app.password}")`
- **TRUST_BOUNDARY_VIOLATION** — entry-point classification by
  `@RestController` / `@GetMapping`
- **`@ConfigurationProperties` suppression** — class-level DI
  annotation means every field default is a framework placeholder

None of these are data-flow problems. They're **role recognition**
problems, and the only way to recognise a role from a TAC is to read
the annotations.

## R125a — IR extensions

Three new pieces of metadata on `TacFunction`:

| Field | Type | Source | Use case |
|-------|------|--------|----------|
| `classAnnotations` | `List<String>` (simple names) | `TypeDeclaration.getAnnotations()` | `@RestController`, `@ConfigurationProperties`, `@Service` |
| `methodAnnotations` | `List<String>` | `CallableDeclaration.getAnnotations()` | `@PreAuthorize`, `@GetMapping` |
| `fieldAnnotations` | `Map<String, List<String>>` | `FieldDeclaration.getAnnotations()`, keyed by `VariableDeclarator.getNameAsString()` | `@Value`, `@Resource`, `@Inject` |

Both lists / map are **simple-name** form. JavaParser returns
`org.springframework.beans.factory.annotation.Value`; the translator
strips the FQN prefix at fill time so the suppression table only
needs `"Value"`. The simple-name form is also what
catalog-style patterns typically want (e.g. "matches if the class
has any of `@RestController`, `@Controller`, `@Service`,
`@Component`").

### Class annotations propagate to all child functions

A single class-level annotation should be visible from any function
emitted from that class. The translator therefore calls
`b.addClassAnnotations(classAnns)` on the `<clinit>` builder, the
synthetic `<init>` builder (R124), the per-method builder, the
initializer-declaration builder, and the inner-class recursive call.
The simple name is added to every child function; that's what makes
a `@RestController` on the class make every controller method
recognisable as an endpoint from the IR.

### 3-step pipeline

```
JavaParser AST      → TacTranslator.extractAnnotationNames() → TacBuilder.add* → TacFunction fields → checker queries
─────────────       ───────────────────────────────────────── ─────────────────── ────────────────── ────────────────
@Value("...")       "Value"                                  classAnnotations: [..]  classAnns()
                                                          methodAnnotations: [..]  methodAnns()
                                                          fieldAnnotations:       fieldAnns()
                                                              { password: [Value] }
```

### Implementation

- `TacFunction`: 3 new final fields, populated by a new 9-arg
  constructor; old 6-arg constructor preserved for back-compat and
  defaults the new fields to `List.of()` / `Map.of()`.
- `TacBuilder`: 3 new mutator methods (`addClassAnnotations`,
  `addMethodAnnotations`, `addFieldAnnotations`) that dedup and
  ignore nulls. The existing `build()` propagates them.
- `TacTranslator`:
  - `extractAnnotationNames(NodeList<AnnotationExpr>)` —
    strips the leading `@` if present, takes the last `.`-segment
    of the FQN, deduplicates within the list.
  - 7-arg `translateCallable` overload that threads
    `classAnns` + `methodAnns` into the builder; the old 5-arg
    version delegates to the new one with empty lists.
  - The 4 places that emit a function (`<clinit>`, synthetic
    `<init>`, regular method, initializer declaration) each
    receive the class-level annotation list and, where
    appropriate, the field-level annotation map for any
    `FieldDeclaration` they encounter.

## R125b — consumer wiring

`FieldHardcodedPasswordCheck` (R124) was the first checker that
needed to consume the new metadata. The reason is concrete: a
Spring Boot `application.yml`-bound bean like

```java
@ConfigurationProperties(prefix = "db")
class DbSettings {
    private String password = "default-db-password";
}
```

is *not* a hard-coded credential. The literal is the framework
default; the real value comes from the YAML at startup.
Pre-R125, R124 fired on this — a clear false positive. R125b
introduces two suppression sets:

### `INJECTED_VALUE_ANNOTATIONS` (field-level)

| Annotation | Why suppress |
|------------|--------------|
| `Value` | Spring's `@Value("${...}")` — value comes from properties |
| `PropertySource` | Spring property source — same intent |
| `Resource` | Jakarta `@Resource` — JNDI / DI injection |
| `Inject` | CDI / Jakarta `@Inject` |
| `ConfigProperty` | Jakarta EE config-property injection |
| `Builder` / `Singular` | Lombok builders; default placeholder |
| `Autowired` | Spring `@Autowired` constructor / setter injection |
| `JsonProperty` | Jackson deserialisation target |

### `CLASS_LEVEL_DI_ANNOTATIONS` (class-level)

| Annotation | Why suppress (whole class) |
|------------|----------------------------|
| `ConfigurationProperties` | Spring Boot config bean |
| `Configuration` | Spring `@Configuration` |
| `Component` / `Service` / `Repository` | Spring stereotype beans |
| `RestController` / `Controller` | Spring MVC controllers |
| `Named` / `ApplicationScoped` / `SessionScoped` | CDI scope beans |
| `JsonRootName` | Jackson root-bean annotation |

The check is two-tier: a per-field lookup for `INJECTED_VALUE_ANNOTATIONS`,
then a per-class lookup for `CLASS_LEVEL_DI_ANNOTATIONS`. Either
one skips the FIELDSTORE. This matches the way the patterns are
written in real code: most credential fields get a per-field
`@Value`; the few that don't usually belong to a class-level
`@ConfigurationProperties` bean.

### Architectural lesson

R125b is the first place where the new IR is *consumed* by a
checker. The lesson, like R124, is that adding a new dimension to
the TAC pays for itself the moment a checker needs it — and a
silent IR drop is a hidden disaster. R125a would have been free
to do in R119; we left it for now only because no checker asked.

## Key learning

- **TAC builder params mutable**: an annotation list added in one
  pass and consumed by `build()` is the natural pattern. The
  builder is per-function, single-threaded, so the mutability is
  safe.
- **class-level annotations propagate to all child functions**:
  the `<clinit>`, synthetic `<init>`, regular method, and
  initializer-declaration builders all receive the same
  `classAnns` list. That's the only way the IR can answer
  "is this method in a `@RestController`" without a separate
  type-declaration lookup at query time.
- **simple-name form** is what most catalog-style patterns need;
  the FQN-to-simple conversion happens once at fill time, not at
  every checker query.
- **`hasExplicitCtor` flag** is still the way to avoid
  double-emit of the synthetic `<init>`; the R124 translator fix
  continues to work with R125a untouched.
- **JavaParser `getAnnotations()` returns `NodeList`, not
  `Optional<NodeList>`** — early signature mismatch was a
  silent build failure caught only when test compilation ran.

## Tests

`AnnotationSuppressionTest` (10 tests, 100% pass):

1. `valueAnnotationSuppressesPasswordField` — `@Value` on field
2. `resourceAnnotationSuppresses` — Jakarta `@Resource`
3. `injectAnnotationSuppresses` — CDI `@Inject`
4. `naiveFieldStillFires` — no DI annotation, regression
5. `nonCredentialFieldUnaffected` — negative control
6. `classAnnotationVisibleFromMethods` — class-level annotation
   propagates
7. `methodAnnotationOnFunction` — method-level annotation lands
   on the right function
8. `fieldAnnotationMapCaptured` — `fieldAnnotations()` map query
9. `classLevelConfigurationPropertiesSuppressesAll` — Spring Boot
   config bean
10. `classLevelComponentSuppressesAll` — Spring stereotype

`FieldHardcodedPasswordCheckTest` (6 existing tests): all still
pass. The R124 baseline is preserved exactly.

**Counts:** 128 dfa + 1 skip = 129 (was 118 + 1 = 119 in R124,
+10 from R125). 6 style, 2 reporter, 3 main unchanged.

## End-to-end

`examples/r125/R125Demo.java` has 5 inner classes covering the
five scenarios. The expected output is:

- 5 `HARDCODED_CREDENTIAL` findings (R114) — this older check
  doesn't read annotations and is intentionally left as a
  *coarse* pre-filter
- 1 `FIELD_HARDCODED_PASSWORD` finding (R124 + R125b) on the
  `NaiveAdmin.password` line — the only one without a DI
  annotation
- 1 `SENSITIVE_DATA_LEAK` finding (R121) on `main()` where
  `getPassword()` flows to `System.out.println`
- 1 `STYLE_SYSTEM_OUT` finding on the same `main()` line

Total: 9 findings from R125Demo, matching expectations.

## Deferred to R126+

- **MISSING_AUTHZ** (R119c) — `methodAnnotations()` is now
  queryable. The remaining work is the role-recognition table
  (which class annotations mean "this is an endpoint") and the
  per-class aggregation (a class without *any* `@PreAuthorize`
  / `@RolesAllowed` on its methods has at least one unauth'd
  endpoint).
- **TRUST_BOUNDARY_VIOLATION** — entry-point classification
  by `@RestController` + `@GetMapping` / `@PostMapping`. The IR
  is now ready; the remaining work is the untrusted-vs-trusted
  flow model.
- **Direct field assignment** — `password = "literal"` in a
  method body still emits MOVE, not FIELDSTORE. Independent
  translator bug, separate from R125.
- **TypeTable field-name → type** — R123's `TypeTable` doesn't
  track field declarations yet; once added, `SensitiveDataLeak`
  can mark a field as sensitive (e.g. `private String password`)
  and the taint engine can use the marker for cross-method flow.

## Files changed

- `plumb-tac/.../ir/TacFunction.java` — 3 new fields, 9-arg ctor
- `plumb-tac/.../translate/TacBuilder.java` — 3 new mutators
- `plumb-tac/.../translate/TacTranslator.java` —
  `extractAnnotationNames`, 7-arg `translateCallable` overload,
  class-level annotation propagation, field-level annotation
  capture during instance/static field init
- `plumb-dfa/.../FieldHardcodedPasswordCheck.java` —
  `INJECTED_VALUE_ANNOTATIONS`, `CLASS_LEVEL_DI_ANNOTATIONS`,
  `injectedFieldNames()`, `classIsInjected()`, suppression in
  the FIELDSTORE loop
- `plumb-dfa/.../test/.../AnnotationSuppressionTest.java`
  (new) — 10 tests
- `examples/r125/R125Demo.java` (new) — 5-scenario demo with
  stub Spring annotations

## Related docs

- `R124-FIELD-HARDCODED-PASSWORD.md` — the parent checker
- `R123-TYPE-AWARE-TAINT.md` — first consumer of `TypeTable`;
  the architectural pattern is the same: "build a metadata
  table in R117, consume it N rounds later"
- `ROADMAP-R115-R125.md` — cumulative summary, paper-writing
  material
