# R126 — Missing Authorization (MISSING_AUTHORIZATION)

**Status:** complete (2026-07-27)
**Tests:** +9 dfa (`MissingAuthorizationCheckTest`), **137 dfa + 1 skip** total
**End-to-end:** 111 findings on `examples/` (R125 107 → R126 111, +4 R126 demo)
**Zero regression**

## Why

CWE-862 ("Missing Authorization") is the most common API-misuse
finding in real Spring / Jakarta codebases: a controller method
exposes a web endpoint without any `@PreAuthorize` /
`@RolesAllowed` gate. R119c deferred MISSING_AUTHORIZATION
because the annotation-aware IR (R125a) wasn't in place yet.
R125b was the first consumer; R126 is the second.

## What it does

For each function in the program, R126 classifies it as an
*endpoint* if either (a) the enclosing class has
`@RestController` or `@Controller`, or (b) the method itself has
a Spring / Jakarta mapping annotation (`@GetMapping`,
`@PostMapping`, `@Path`, ...). The check then asks: does the
endpoint have *any* of the recognized authorization annotations
(`@PreAuthorize`, `@RolesAllowed`, `@Secured`,
`@RequiresPermissions`, ...)? If not, the method is
unauth'd.

Three suppression paths:
- **Method-level `@PermitAll` / `@AnonymousAllowed`** — the
  developer has explicitly declared the endpoint anonymous.
- **Class-level `@EnableMethodSecurity` /
  `@EnableGlobalMethodSecurity`** — the developer has opted
  in to framework defaults for the whole class; per-method
  annotation is delegated to the framework.
- **Method-level `@PreAuthorize` / `@RolesAllowed` /
  `@Secured` / `@PostAuthorize` / `@DenyAll` /
  `@RequiresPermissions` / `@RequiresRoles` /
  `@Authorize` / `@AuthorizeResource`** — any one of these
  is the developer's "this is gated" statement; we don't
  second-guess.

## Per-class aggregation

A controller with N unauth'd endpoints produces **one finding**
listing the affected methods, not N findings. This matches
what a reviewer wants to see: one decision per controller
class ("add `@EnableMethodSecurity`" or "add `@PreAuthorize`
to each method"), not N per-handler noise. The message
includes up to 8 method names verbatim; the rest are
summarized as "+N more".

## Test scenarios (9/9 passing)

`MissingAuthorizationCheckTest` covers:

1. `firesOnUnauthController` — basic Spring controller fires
2. `suppressesWhenPreAuthorizePresent` — `@PreAuthorize` suppresses
3. `suppressesWhenRolesAllowedPresent` — Jakarta `@RolesAllowed` suppresses
4. `firesOnlyOnUnauthMethodInMixedController` — mixed controller aggregates per-class, lists only unauth'd methods
5. `suppressesWhenPermitAllPresent` — explicit "anonymous" suppresses
6. `firesOnNonControllerClassWithMappingMethod` — `@Service` class with `@GetMapping` fires
7. `suppressesWhenEnableMethodSecurityOnClass` — class-level security enables suppresses
8. `doesNotFireOnPlainPojoClass` — negative control
9. `aggregatesMultipleUnauthMethodsOnSameClass` — multi-method aggregation

## End-to-end (`examples/r126/R126Demo.java`)

Six inner classes covering all six scenarios from the test +
the working case. Expected findings:

| Class | Should fire? | Why |
|-------|--------------|-----|
| R126NaiveController | YES | Plain @RestController, no auth |
| R126LockedDownController | NO | @PreAuthorize on every endpoint |
| R126MixedController | YES (1 finding) | One method unauth'd |
| R126PublicController | NO | @PermitAll explicit |
| R126DirectEndpointService | YES | @Service with @GetMapping |
| R126ClassSecuredController | NO | @EnableMethodSecurity on class |

End-to-end matches: **3 MISSING_AUTHORIZATION findings** at
L64 / L94 / L119. **Plus 1 STYLE_UNUSED_IMPORT** at L34
(R126Demo itself; the test runner doesn't suppress style).

## Implementation

- `MissingAuthorizationCheck.java` (12.8KB) — checker with
  per-class aggregation
- 4 constant sets:
  - `CONTROLLER_CLASS_ANNOTATIONS` (2)
  - `ENDPOINT_METHOD_ANNOTATIONS` (13)
  - `AUTHZ_ALLOW_ANNOTATIONS` (10)
  - `PERMIT_ANONYMOUS_ANNOTATIONS` (2)
  - `CLASS_LEVEL_SECURITY` (4)
- Per-class aggregation via `Map<String, ClassAcc>` keyed on
  `signature().substring(0, firstDot)` — the class's
  qualified name as the natural key
- `report()` formats the message with up to 8 method names
  inline; truncates with "+N more" past that

## Innovation summary (R126-specific)

- **Class-level aggregation is the right granularity.** A
  reviewer wants to make one decision per controller, not
  N. Naïve "one finding per method" floods the report.
- **Simple-name annotation set is the unblocker.** R126
  would not be possible without R125's annotation IR; the
  cost of adding 4 new `Set<String>` constants is trivial
  compared to the per-endpoint correctness the check gains.
- **Conservative fall-back on unknown annotations.** A
  user-defined `@MyProjectAuthorized` annotation will not
  match `AUTHZ_ALLOW_ANNOTATIONS`; the method is flagged.
  This preserves coverage on real-world code where the
  user has added a custom annotation we don't recognize.
  The cost is a small false-positive surface on
  heavily-customized frameworks, which is the right
  trade-off for a security tool.

## Files changed

- `plumb-dfa/.../MissingAuthorizationCheck.java` (new, 12.8KB)
- `plumb-dfa/.../test/.../MissingAuthorizationCheckTest.java` (new, 9 tests)
- `plumb-rules/.../RuleRegistry.java` — `bu-0901-missing-authorization`
- `plumb-main/.../ScanConfig.java` — `runMissingAuthorization = true`
- `plumb-main/.../ScanRunner.java` — wire-up in both
  merged and per-file paths
- `examples/r126/R126Demo.java` (new, 4 scenarios + 2 negative controls)

## Related docs

- `R125-ANNOTATION-IR.md` — the IR that makes R126 possible
- `R119-CRYPTO-CHECKS.md` — the prior R119 webapp checks
- `TECHNICAL-CONTRIBUTIONS-R115-R125.md` — C5 is the
  parent of R126
