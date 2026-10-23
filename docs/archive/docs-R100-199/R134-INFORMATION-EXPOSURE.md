# R134 — Information Exposure (INFORMATION_EXPOSURE)

**Status:** complete (2026-07-27)
**Tests:** +8 dfa (`InformationExposureCheckTest`), **178 dfa + 1 skip** total
**End-to-end:** 132 findings on `examples/` (R133 124 → R134 132, +8 R134 demo)
**Zero regression**

## Why

CWE-200 ("Exposure of Sensitive Information to an
Unauthorized Actor") is a CWE Top 25 entry and one of the
most common API-misuse findings in real Spring / Jakarta
codebases: a controller method that returns the full
`User` entity (with password hash, salt, internal IDs,
email, etc.) is the standard anti-pattern. R134 closes
the gap with a type-aware return-type check: if a web
endpoint's declared return type is a user-declared
class, the check fires.

This is the third consumer of the annotation-aware IR
(R125a) — and the **fourth consumer** of the
`typeArgsOf` channel, in the sense that R134 walks the
function's return type (a metadata shape that wasn't
exposed before R132's translator fix).

## What it does

For each function in the program, R134 classifies it
as an *endpoint* (same logic as R126 / R128 — class
has `@RestController` / `@Controller` or method has a
Spring / Jakarta mapping annotation). The check then
asks: does the function's declared return type look
like a sensitive domain object?

- **Primitive** (`int`, `boolean`, `void`): safe.
- **`String`** / `CharSequence`: safe.
- **`ResponseEntity<T>`** / `HttpEntity<T>`: safe
  (the wrapper is framework-managed; the underlying
  type is what gets serialized — a future R135+ will
  inspect the type args).
- **JDK class** (`HashMap`, `String`, ...): safe
  (per `TypeTable.isUserClass`).
- **User-declared class** (`User`, `Account`, `Order`,
  `OrderItem`, ...): fire.

Conservative fall-back: an UNKNOWN return type, a
cross-TU reference, or a reflective getter all pass
without firing. The check errs on the side of
over-flag user classes — false positives are easy to
review and a one-line suppression.

## Per-class aggregation

Same shape as R126 / R128: a controller with N
endpoints that return user classes produces ONE
finding listing the affected methods, not N. The
message includes up to 8 method names verbatim; the
rest are summarized as "+N more". A reviewer can
make one decision per controller ("add a DTO layer
to `UserController`") rather than N per-handler
churn.

## Test scenarios (8/8 passing)

`InformationExposureCheckTest`:

1. `firesOnUserClassReturnType` — basic `User` return fires
2. `firesOnAccountClassReturnType` — `Account` return fires
3. `doesNotFireOnStringReturnType` — `String` is safe
4. `doesNotFireOnVoidReturnType` — `void` is safe
5. `doesNotFireOnPrimitiveReturnType` — `int` is safe
6. `firesOnResponseEntityOfUserClass` — current behavior
   is no fire (ResponseEntity is in SAFE_RETURN_TYPES;
   type-arg inspection is R135+). The test records
   the current behavior and flags the future work.
7. `doesNotFireOnNonEndpoint` — plain non-endpoint
   method is out of scope
8. `aggregatesMultipleEndpointsOnSameClass` — per-class
   aggregation: ONE finding listing the affected methods

## End-to-end (`examples/r134/R134Demo.java`)

Six inner classes covering all six scenarios from the
test:

| Class | Should fire? | Why |
|-------|--------------|-----|
| R134UserController | YES (L53) | Plain `User` return |
| R134AccountController | YES (L64) | Plain `Account` return |
| R134PingController | NO | `String` return is safe |
| R134DeleteController | NO | `void` return is safe |
| R134ResponseEntityController | NO | `String` return (R135+ would inspect `ResponseEntity<User>`) |
| R134NotAController | NO | Plain non-endpoint class |

End-to-end matches: **2 INFORMATION_EXPOSURE findings**
at L53 and L64, plus 5 MISSING_AUTHORIZATION from R126
(these controllers also lack auth annotations) and 1
STYLE_UNUSED_IMPORT.

## Innovation summary (R134-specific)

- **The return-type metadata was already in the IR — R134
  just queries it.** `TacFunction.returnType()` has been
  there since R99; no IR change was needed for R134.
  The "infrastructure" is the existing function
  metadata. The check is a thin query on top.
- **Type-aware return-type check is a different shape
  from taint-flow checks.** R121 / R123 / R133 are
  flow-based: data moves from source to sink, the
  check is about the *path*. R134 is shape-based:
  the function's declared signature leaks the
  domain object directly. The two styles are
  complementary, and a real codebase needs both.
- **Per-class aggregation is a three-R pattern.**
  R126 (MISSING_AUTHZ) introduced it, R128
  (MISSING_INPUT_VALIDATION) reused it, R134 is the
  third. The pattern is now stable: one finding
  per controller class, methods listed in the
  message, truncated to 8 inline.

## Files changed

- `plumb-dfa/.../InformationExposureCheck.java`
  (new, 8.6KB) — the check
- `plumb-dfa/.../test/.../InformationExposureCheckTest.java`
  (new, 8 tests)
- `plumb-rules/.../RuleRegistry.java` — `bu-1101-information-exposure`
- `plumb-main/.../ScanConfig.java` — `runInformationExposure = true`
- `plumb-main/.../ScanRunner.java` — wire-up in both
  merged and per-file paths
- `examples/r134/R134Demo.java` (new, 6 scenarios)

## Limitations and R135+ work

R134 has a known scope limit:

- **`ResponseEntity<T>` is in SAFE_RETURN_TYPES, so
  `ResponseEntity<User>` does NOT fire.** A future
  R135 would inspect the type args via
  `TypeTable.typeArgsOf(...)` (R132 infra) and check
  whether the wrapped type is a user class. The
  R134 test records the current behavior and flags
  the future work.
- **Generics on the function level (the wrapped
  type) is not yet inspected.** Same fix path.
- **Cross-TU return types are UNKNOWN** and pass
  through without firing. The conservative
  fall-back is the project's standing rule.

## Related docs

- `R121-SENSITIVE-DATA-LEAK.md` — the original
  taint-flow check; R134 is a sibling shape-based check
- `R126-MISSING-AUTHZ.md` — the per-class aggregation
  pattern
- `R132-GENERIC-AWARE-TYPE.md` — the `typeArgsOf`
  infrastructure R135 will consume
- `R128-MISSING-INPUT-VALIDATION.md` — the second
  per-class-aggregated check
