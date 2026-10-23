# R128 — Missing Input Validation (MISSING_INPUT_VALIDATION)

**Status:** complete (2026-07-27)
**Tests:** +8 dfa (`MissingInputValidationCheckTest`), **151 dfa + 1 skip** total
**End-to-end:** 124 findings on `examples/` (R127 115 → R128 124, +9 R128 demo)
**Zero regression**

## Why

CWE-20 ("Improper Input Validation") is the highest-frequency
CWE in CWE Top 25 (2023) for "Improper Input Validation" in
general, and every Spring / Jakarta controller that takes a
raw `@RequestParam String foo` without any validation is a
candidate. R128 closes the gap. It's also the first consumer
of the **R128 IR extension** (the fourth annotation channel:
parameter-level annotations), which makes it the natural
follow-up to R125a (class/method/field) and R125b
(FieldHardcodedPasswordCheck consumer).

## What it does

For each function in the program, R128 classifies it as an
*endpoint* (same logic as R126 — class has `@RestController` /
`@Controller` or method has a Spring / Jakarta mapping
annotation). The check then asks: does the method have any
parameter that carries a user-input annotation
(`@RequestParam`, `@PathVariable`, `@RequestBody`,
`@RequestHeader`, `@CookieValue`, `@ModelAttribute`, plus
the Jakarta RS equivalents)?

If yes, the method should opt into Bean Validation. The opt-in
can take three forms:
- **Class-level** `@Validated` (Spring's
  `org.springframework.validation.annotation.Validated`) —
  every parameter binding on every method is validated by
  Hibernate Validator.
- **Method-level** `@Validated` — same, but scoped to the
  method.
- **Per-parameter** `@Valid` (Jakarta) on at least one of the
  user-input parameters — Spring still runs the validator
  chain on the binding.

If none of the three is present, the method is unvalidated,
and the check fires.

## Per-class aggregation

Same shape as R126: a controller with N unvalidated endpoints
produces one finding listing the affected methods, not N. The
message includes up to 8 method names verbatim; the rest are
summarized as "+N more".

## Test scenarios (8/8 passing)

`MissingInputValidationCheckTest` covers:

1. `firesOnUnvalidatedRequestParam` — basic Spring `@RequestParam` fires
2. `firesOnUnvalidatedPathVariable` — `@PathVariable` fires
3. `suppressesWhenClassLevelValidated` — class-level `@Validated` suppresses
4. `suppressesWhenParamLevelValid` — per-parameter `@Valid` suppresses
5. `firesOnUnvalidatedRequestBody` — `@RequestBody` without `@Valid` fires
6. `doesNotFireOnPlainPojo` — negative control
7. `paramAnnotationsAreCapturedByIR` — IR extension verification
8. `aggregatesMultipleUnvalidatedMethodsOnSameClass` — per-class aggregation

## End-to-end (`examples/r128/R128Demo.java`)

Six inner classes covering all six scenarios from the test:

| Class | Should fire? | Why |
|-------|--------------|-----|
| R128NaiveController | YES (L63) | Plain @RestController, no validation |
| R128ClassValidatedController | NO | Class-level @Validated |
| R128ParamValidController | NO | Per-parameter @Valid |
| R128BodyController | YES (L106) | @RequestBody without @Valid |
| R128PrimitiveController | YES (L115) | Primitive @RequestParam without constraint |
| R128NotAController | NO | Plain POJO, no endpoint |

End-to-end matches: **3 MISSING_INPUT_VALIDATION findings** at
L63 / L106 / L115 (plus 5 MISSING_AUTHORIZATION from R126 since
these controllers also lack auth annotations, and 1
STYLE_UNUSED_IMPORT).

## IR extension: parameter-level annotation channel

R128 adds a fourth annotation channel to `TacFunction`. Where
R125a provided:

- `classAnnotations: List<String>`
- `methodAnnotations: List<String>`
- `fieldAnnotations: Map<String, List<String>>`

R128 now provides:
- `paramAnnotations: Map<String, List<String>>` — keyed on
  parameter name, value is the list of annotation simple
  names on that parameter.

The translator captures this in `translateCallable` (the
R125a overload that already threads class + method
annotations), adding a `b.addParamAnnotations(p.getNameAsString(), pAnns)`
call for each parameter. The back-compat constructors
preserve the R125a 6-arg / 9-arg shapes; a new 10-arg
constructor takes the param annotations map.

`TacBuilder.build()` propagates the new map to
`TacFunction.paramAnnotations()`. The IR is unchanged for
existing checkers; the new field is just an additional
query surface.

## Innovation summary (R128-specific)

- **IR grows by one annotation channel, not a new dimension.**
  R128 doesn't introduce a new TAC kind or a new SSA
  primitive; it adds a fourth *metadata* channel to the
  existing `TacFunction` shape. The cost is one new
  `Map<String, List<String>>` field plus the corresponding
  mutator and translator code. The benefit is that
  parameter-level decisions (Bean Validation markers,
  injection-site classifications, ...) become queryable from
  any checker that wants them.
- **3 forms of validation opt-in is the right precision.**
  Spring's `@Validated` (class or method) and Jakarta's
  `@Valid` (per parameter) are three different idioms for
  the same intent. R128 recognizes all three and the
  check fires only when none of the three is present. This
  is the standard "any explicit opt-in wins" rule that
  R125b and R126 also follow.
- **Conservative fall-back on unknown validation annotations.**
  A custom `@MyProjectValidated` annotation is not in the
  recognized set; the method is flagged. This is the same
  trade-off as R125b / R126: better to over-fire than to
  under-fire on a security check.

## Files changed

- `plumb-tac/.../ir/TacFunction.java` — 4th annotation channel
  (`paramAnnotations: Map<String, List<String>>`)
- `plumb-tac/.../translate/TacBuilder.java` — `addParamAnnotations`
  mutator, `paramAnnotations()` accessor, `build()` propagates
- `plumb-tac/.../translate/TacTranslator.java` —
  `translateCallable` extracts parameter-level annotations
  from the JavaParser AST
- `plumb-dfa/.../MissingInputValidationCheck.java` (new, 11KB)
- `plumb-dfa/.../test/.../MissingInputValidationCheckTest.java`
  (new, 8 tests)
- `plumb-rules/.../RuleRegistry.java` — `bu-1001-missing-input-validation`
- `plumb-main/.../ScanConfig.java` — `runMissingInputValidation = true`
- `plumb-main/.../ScanRunner.java` — wire-up in both
  merged and per-file paths
- `examples/r128/R128Demo.java` (new, 6 scenarios)

## Related docs

- `R125-ANNOTATION-IR.md` — the 3-channel annotation IR that
  R128 extends
- `R126-MISSING-AUTHZ.md` — the sibling checker that
  reuses the same endpoint classification and per-class
  aggregation pattern
- `R127-DIRECT-FIELD-ASSIGNMENT.md` — the prior round, a
  translator repair rather than a new checker
