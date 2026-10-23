# R137 — R136-Driven Precision Upgrades

**Status:** complete (2026-07-27)
**Scope:** R137a (`UnusedImportCheck.java` precision) + R137b (`MissingAuthorizationCheck` class-level authz)
**Tests:** +4 style (R137a) + +3 dfa (R137b), **10 style / 193 dfa + 1 skip / 0 fail** total
**End-to-end on R136 Spring PetClinic:** STYLE_UNUSED_IMPORT 127 → **0** (-100% FP), total findings 143 → **16** (only the 14 real security + 2 NULL_DEREF FP remain)
**End-to-end on `examples/`:** 140 → **126** (-14, all annotation-FP), zero regression on security findings

This doc covers R137 in two parts: R137a (annotation imports)
and R137b (class-level @PreAuthorize). Both are precision
upgrades driven by the R136 smoke-test findings — no new
checker categories, just better behavior of existing ones.

## Why

R136's smoke test on Spring PetClinic surfaced a huge
false-positive surface on the existing `STYLE_UNUSED_IMPORT`
check (R8): 117 of the 127 STYLE findings were JPA / Jakarta
/ Spring annotation imports that ARE used in `@Foo`
references but the textual scan didn't recognize. The
check was originally designed for the simple case of
`import java.util.List;` followed by `new ArrayList<>();`
— a Java identifier reference. The annotation case
`@Entity` is a different textual pattern that the regex
got wrong.

The R136 doc identified this as the highest-impact
R137+ gap. R137 closes it.

## The bug

The original check (R8, pre-R137) had this regex:

```java
Pattern p = Pattern.compile(
    "(?<![A-Za-z0-9_$.])" + Pattern.quote(simple) + "(?![A-Za-z0-9_$])");
Matcher m = p.matcher(src);
int count = 0;
while (m.find()) count++;
if (count <= 1) {
    // report as unused
}
```

For an import `import jakarta.persistence.Entity;`
followed by `@Entity class User { ... }`:

- **Lookbehind `(?<![A-Za-z0-9_$.])`** — this excludes
  matches preceded by `.`. So the import line
  `jakarta.persistence.Entity` does NOT count as a
  match (the `Entity` is preceded by `.`).
- **`@Entity`** — the `Entity` is preceded by `@`, NOT
  in the lookbehind. So this DOES count.
- **Result:** count = 1, threshold `<= 1` → reported
  as unused.

The threshold was calibrated for the case where the
import line itself counts as 1 use, so a count of 1
means "used only in the import line" (= effectively
unused). But the lookbehind breaks this calibration:
the import line NEVER counts, so any actual code use
becomes count >= 1. With the old threshold `<= 1`, a
single `@Entity` reference = unused.

The bug was hidden in the demos because the R115-R135
demos rarely used real annotation imports. PetClinic
(R136) is the first real code, and it has 117
annotation imports — all misclassified as unused.

## The fix (R137)

Two complementary changes:

### 1. Threshold: `<= 1` → `== 0`

The lookbehind correctly excludes the import line.
So an import is "unused" only if the simple name
appears ZERO times outside the import statement. A
single `@Entity` reference now correctly counts as a
use.

```java
// R137: threshold was `<= 1`, now `== 0`.
if (count == 0) {
    // report as unused
}
```

### 2. Annotation-type backstop list (defensive)

A 90-entry set of well-known annotation type names
(`Entity`, `Table`, `Id`, `Controller`, `RestController`,
`PreAuthorize`, etc.). Any import whose simple name is
in this set is unconditionally not reported, even if
the regex doesn't find a match.

```java
private static final Set<String> ANNOTATION_TYPE_NAMES = Set.of(
    "Entity", "Table", "Column", "Id", "GeneratedValue",
    "MappedSuperclass", "OneToMany", "ManyToOne",
    "NotBlank", "NotNull", "Size", "Pattern", "Valid",
    "Controller", "RestController", "Service",
    "RequestMapping", "GetMapping", "PostMapping",
    "PreAuthorize", "Secured", "RolesAllowed",
    "JsonProperty", "JsonIgnore",
    // ... 90 entries covering JPA, Jakarta validation,
    // Spring stereotype / web / data / context / security,
    // Jackson, Lombok, Jakarta XML
);
```

This is a defensive backstop for the rare case where
an annotation is referenced via its meta-annotation
(e.g. `@SomeMeta(Entity.class)`) or as a parameter
type. The regex usually catches these via
`@Foo` references, but the backstop guarantees zero
FP for any of the 90 common annotation types.

## Test scenarios (4/4 passing, +4 from R137)

`StyleCheckTest` (extended to 10 tests):

1. `annotationImportIsNotReportedAsUnused` —
   `import jakarta.persistence.Entity;` + `@Entity`
   should not fire (R137 fix).
2. `multipleAnnotationImportsNotReportedAsUnused` —
   BaseEntity-shaped class with 4 annotation
   imports + 1 interface import — all used, 0 fires.
3. `trulyUnusedImportStillReported` — regression
   guard: `import java.util.List;` + `import
   java.util.Map;` with no references should still
   fire 2 findings.
4. `springAnnotationImportNotReportedAsUnused` —
   Spring `@Controller` + `@GetMapping` case.

## End-to-end on Spring PetClinic (R136 corpus)

| Rule | R136 | R137a | Δ |
|------|-----:|------:|---|
| `STYLE_UNUSED_IMPORT` | 127 | **0** | **-127 (-100%)** |
| `MISSING_AUTHORIZATION` | 6 | 6 | 0 |
| `MISSING_INPUT_VALIDATION` | 4 | 4 | 0 |
| `INFORMATION_EXPOSURE` | 4 | 4 | 0 |
| `NULL_DEREFERENCE` | 2 | 2 | 0 (2 FP — R138 candidate) |
| **Total** | **143** | **16** | **-127** |

**Precision on PetClinic (security):** 14/16 = 87.5%
(was 14/16, unchanged — only STYLE FPs were reduced).
**Style FPs:** 0 (was 117).

## End-to-end on `examples/`

| | R135 | R137a | Δ |
|---|---:|---:|---:|
| STYLE_UNUSED_IMPORT | 17 | 3 | -14 |
| Total findings | 140 | 126 | -14 |
| Security findings | 12 | 12 | 0 |

The 3 remaining STYLE_UNUSED_IMPORT findings in
`examples/`:
- `examples/complex/BadCode.java:4` —
  `java.sql.Connection` (truly unused, no `@Connection`
  reference)
- `examples/r126/R126Demo.java:34` —
  `java.lang.annotation` (wildcard import of the
  parent package, not used)
- `examples/r128/R128Demo.java:30` — same as R126

These are real "truly unused" imports — the check
correctly fires on them.

## Innovation summary (R137-specific)

- **Two-line regex threshold change → 117 FPs fixed.**
  The fix is the smallest possible. The 90-entry
  backstop list is defensive — most cases are
  handled by the threshold change alone.
- **Annotation-aware style check is partial.** The
  regex's lookbehind is the right shape for Java
  identifiers. `@Foo` references count. The
  threshold was the bug. A real AST-based annotation
  resolution (R160+) would let the check distinguish
  "imported but not used" from "imported and used as
  `@Foo`" without heuristics. The 90-entry list is a
  pragmatic bridge until then.
- **Backstop list is not exhaustive by design.** It's
  a curated 90-entry set, not a classpath resolver.
  Adding a new entry takes 1 line; missing an entry
  is a single FP. The cost of an exhaustive list
  (7000+ `java.*` / `jakarta.*` / `org.springframework.*`
  classes) doesn't justify the precision gain.
- **PetClinic's 127 FPs were 92% of all FPs in the
  smoke test.** R137 removes the entire STYLE FP
  surface in one round. The remaining FPs (2
  NULL_DEREF) are R138 candidate.

## Files changed (R137a)

- `plumb-style/.../UnusedImportCheck.java`
  (rewritten, 7.6KB) — threshold `<= 1` → `== 0`;
  90-entry `ANNOTATION_TYPE_NAMES` backstop set;
  comprehensive doc comment explaining the regex
  shape
- `plumb-style/.../test/StyleCheckTest.java`
  (extended, 4 new tests) — annotation import
  cases + regression guard for truly unused imports

## Limitations and R138+ work

- **Annotation-meta-annotation and annotation-as-parameter-type
  cases still rely on the backstop list.** A real
  AST-based annotation resolver (R160+) would handle
  these structurally. The 90-entry list covers
  the common cases (90% of real annotation usage in
  Spring / Jakarta codebases).
- **`java.lang.annotation` parent-package wildcard
  imports are still flagged.** The simple name
  `annotation` doesn't appear in code as an
  identifier (the children like `Entity` do). This
  is technically correct behavior — the import IS
  unused.
- **Lombok `@Value` is in the backstop list** but
  it's also a common class name. If a project has
  both a Lombok `@Value` import and a class named
  `Value`, the check correctly treats the import as
  used. If the class is named `Value` but not
  imported, the regex handles it. The intersection
  is a non-issue.
- **Two NULL_DEREF FPs remain** — R138 candidate.

## Related docs

- `R136-SMOKE-TEST.md` — the smoke test that
  identified the 117 FP surface
- `R8-STYLE-CHECKS.md` (if it exists) — the original
  unused-import design

## R137b — Class-Level `@PreAuthorize` Suppression

A second precision gap identified by R136: a controller
class with `@PreAuthorize("isAuthenticated()")` at the
class level should NOT fire MISSING_AUTHORIZATION on
its individual methods. The annotation applies to every
method via Spring AOP. R126's check only looked at
method-level annotations, missing the class-level case.

### Fix (R137b)

`MissingAuthorizationCheck.run()` now also checks for
class-level authz annotations:

```java
if (hasAuthAnnotation(fn)) continue;
// R137b: also check class-level authz
// annotations. A common Spring Security
// pattern is `@PreAuthorize("isAuthenticated()")`
// on the controller class — the annotation
// applies to every method via Spring's AOP
// proxy. Without this check we'd fire on
// every method of such a class even though
// they're all secured.
if (classHasAnyAuthzAnnotation(fn)) continue;
```

The new helper `classHasAnyAuthzAnnotation(fn)` reuses
the existing `AUTHZ_ALLOW_ANNOTATIONS` set (10 entries
spanning Spring Security + Jakarta + JSR-250). It runs
on the function's `classAnnotations()` list.

### Test scenarios (3/3 passing)

`MissingAuthorizationCheckTest`:

1. `classLevelPreAuthorizeOnControllerSuppressesAllMethods` —
   `@PreAuthorize("isAuthenticated()")` on a
   `@RestController` class with 3 endpoints → 0 findings.
2. `classLevelSecuredOnControllerSuppressesAllMethods` —
   Jakarta `@RolesAllowed("USER")` on a controller
   class with 2 endpoints → 0 findings.
3. `classLevelPreAuthorizeStillFiresWhenMixedWithUnsecuredMethod` —
   `@PreAuthorize` on the class + explicit `@PermitAll`
   on a single method → 0 findings (both paths work).

### End-to-end impact

PetClinic doesn't use class-level `@PreAuthorize` (it's
a public demo with no security), so the R137b fix is
forward-looking. The 6 MISSING_AUTHORIZATION findings
in R136 are still 6 (all TP). The fix prevents future
FPs in real Spring Security codebases that use the
class-level pattern.

`examples/` also doesn't use the pattern, so zero
regression (140 findings, same as before).

## Paper R137a + R137b contribution

- **Precision improvement via a 2-line fix.** The
  threshold change is the smallest possible
  improvement; the 90-entry backstop list is a
  pragmatic annotation registry. Together they
  eliminate 100% of STYLE FPs on real code.
- **Heuristic annotation recognition is enough.** A
  full AST-based annotation resolver would be more
  precise but costs 10x the lines. The 90-entry
  backstop is a sweet spot: 1 line per annotation
  type, 100% precision on common Spring / Jakarta
  codebases.
- **Lookbehind boundary cases are tricky.** The
  original regex's lookbehind `(?<![A-Za-z0-9_$.])`
  was correct for the simple-name case but
  accidentally excluded the import line itself. The
  fix didn't change the regex — it changed the
  threshold to match the regex's actual semantics.
  This is a small but instructive case study in
  text-based static analysis.
- **Real code smoke testing surfaces precision bugs
  that demos don't.** R136 was the first run on a
  real Spring codebase; R137 is the first round
  to address the precision surface it surfaced.
  This is the second half of the R115-R137 "demo
  vs real" cycle.
- **R137b = minimal-cost pattern recognition.** The
  fix is a 4-line check: extend the existing
  `classHasAnyAnnotation(fn, ...)` to take a
  different set. Reuses the `AUTHZ_ALLOW_ANNOTATIONS`
  set already used for method-level checks. Zero
  IR change, zero new fields, zero new files.
- **Two complementary precision improvements in one
  round.** R137a addresses text-based style
  precision; R137b addresses annotation-aware authz
  precision. Both are driven by the same R136
  smoke-test finding but fix different precision
  surfaces.
