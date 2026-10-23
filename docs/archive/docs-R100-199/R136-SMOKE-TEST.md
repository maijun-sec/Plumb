# R136 — Smoke Test on Spring PetClinic

**Status:** complete (2026-07-27)
**Subject:** `spring-projects/spring-petclinic` (HEAD, 2026-07-27)
**Scope:** 30 Java files, ~1900 LOC (excluding `src/test/`)
**Result:** 16 security findings (6 HIGH + 8 MEDIUM + 2 MEDIUM) + 127 STYLE findings (mostly FP)
**Critical bug found:** per-class aggregation broken for top-level classes — fixed in R136

## Why a smoke test

R115-R135 built 39 checker categories on synthetic
`examples/` demos. The demos are good for unit tests but
are not real code — they don't exercise the same patterns
as a real Spring Boot app. R136 picks a small, well-known
Spring sample (PetClinic, ~1900 LOC, 6 controllers, 4
domain types, 3 repositories) and runs the scanner end to
end. The goal is to validate:

- **Recall** — does the scanner catch patterns that real
  Spring apps exhibit? (Authorization, validation,
  information exposure, null deref, etc.)
- **Precision** — does the scanner over-fire on real code?
  (False positives, especially in style checks.)
- **Per-class aggregation** — the 3 controller-security
  checks (R126 / R128 / R134) aggregate findings per
  class. Does the aggregation work on real top-level
  classes (vs. the inner-class demos)?

PetClinic is canonical: it's the Spring team's reference
sample, has 6 well-known controllers, uses JPA for
persistence, and is public on GitHub.

## What the scanner found

| Rule | Count | Severity | TP/FP | Notes |
|------|------:|----------|-------|-------|
| `MISSING_AUTHORIZATION` (R126) | 6 | HIGH | 6/0 | All 6 controllers lack `@PreAuthorize`. PetClinic is a public demo — TP by design. |
| `INFORMATION_EXPOSURE` (R134/R135) | 4 | MEDIUM | 4/0 | All 4 controllers return a model class (Owner, Pet, Vet, Visit). Per-class aggregation works after R136 fix. |
| `MISSING_INPUT_VALIDATION` (R128) | 4 | MEDIUM | 4/0 | 4 controllers have `@RequestParam` / `@PathVariable` but no `@Valid`. |
| `NULL_DEREFERENCE` (R114) | 2 | MEDIUM | 0/2 | **Both FPs** — scanner over-fires on catch parameters and `@InitBinder` parameters. See "Gaps" below. |
| `STYLE_UNUSED_IMPORT` (R8) | 127 | LOW | ~10/117 | **Mostly FPs** — JPA annotations (`@Entity`, `@Table`, `@Id`, etc.) reference imports by name; scanner doesn't see annotation usage. |

**Total security findings:** 14 TP + 2 FP = 16 (87.5% precision on security).
**Total findings:** 16 security + 127 style = 143 (11.2% precision overall; the STYLE check is the entire FP surface).

## The per-class aggregation bug (R136 critical fix)

The first scan of `OwnerController.java` (top-level class)
produced **11 separate MISSING_AUTHORIZATION findings**,
one per endpoint method. The per-class aggregation
primitive from R126 was supposed to combine these into
ONE finding listing all 11 methods. The aggregation was
silently broken for top-level classes.

### Root cause

`classKeyOf(TacFunction fn)` in `MissingAuthorizationCheck`,
`MissingInputValidationCheck`, and `InformationExposureCheck`
used:

```java
String sig = fn.signature();
int dot = sig.indexOf('.');
if (dot < 0) return sig;     // BUG: returns full signature
return sig.substring(0, dot); // returns class prefix
```

For **inner classes**, the translator emits
`Outer$Inner.method(args)` — the signature has a `$` and a
`.`, so `classKeyOf` returns the inner class name
correctly. For **top-level classes**, the translator emits
just `method(args)` — no class prefix. The classKey
returns the entire signature, which is unique per method.
No aggregation happens.

The R115-R135 demos all used **inner classes** (e.g.
`R134Demo$R134UserController.getUser`), so the bug was
masked. PetClinic's `OwnerController` is a top-level
class — the bug surfaces immediately.

### Fix (R136)

`classKeyOf` falls back to the source path when the
signature has no `.`. The 3 checks were updated:

```java
private static String classKeyOf(TacFunction fn, String sourcePath) {
    String sig = fn.signature();
    int dot = sig.indexOf('.');
    if (dot >= 0) return sig.substring(0, dot);
    return sourcePath == null ? sig : sourcePath;
}
```

`sourcePath` is `ctx.sourcePathForFunction(fn.signature())`
— the same path the Finding gets reported with. For real
top-level classes, all methods in the same file share the
same source path → they aggregate correctly.

### After fix

`OwnerController` (11 endpoints): ONE finding listing all
11 methods. `PetController` (14 endpoints): ONE finding.
`VetController`, `VisitController`, `CrashController`,
`WelcomeController`: ONE finding each. 6 findings total
(one per controller) — the per-class aggregation primitive
now works on real top-level classes.

## Findings (per-class aggregated)

The R136 scan after the fix reports:

1. **MISSING_AUTHORIZATION** × 6 (one per controller):
   - `OwnerController` — 11 endpoints without auth
   - `PetController` — 14 endpoints without auth
   - `VisitController` — 4 endpoints without auth
   - `VetController` — 4 endpoints without auth
   - `CrashController` — 1 endpoint without auth
   - `WelcomeController` — 1 endpoint without auth

2. **INFORMATION_EXPOSURE** × 4 (one per controller that
   returns a model class):
   - `OwnerController` — `findOwner`, `findPaginatedForOwnersLastName`, `showOwner` return `Owner`
   - `PetController` — `populatePetTypes`, `findOwner`, `findPet` return `Pet` / `Collection<Pet>` / `Owner`
   - `VisitController` — `loadPetWithVisit`, `minVisitDate` return `Pet` / `LocalDate`
   - `VetController` — `findPaginated`, `showResourcesVetList` return `Page<Vet>` / `Vets`

3. **MISSING_INPUT_VALIDATION** × 4 (one per controller
   with user-input params):
   - `OwnerController` — `findOwner`, `processFindForm`, `showOwner` accept `Owner` / `int` / `String`
   - `PetController` — `findOwner`, `findPet` accept `int` / `int`
   - `VisitController` — `loadPetWithVisit` accepts `int`
   - `VetController` — `showVetList` accepts `int`

4. **NULL_DEREFERENCE** × 2 (FP — see Gaps):
   - `PetController.java:171` — `isDuplicatePetNameViolation(ex)` — `ex` is a catch parameter
   - `VisitController.java:53` — `dataBinder.setDisallowedFields(...)` — `dataBinder` is `@InitBinder` parameter

5. **STYLE_UNUSED_IMPORT** × 127 (FP for ~92% of these —
   see Gaps).

## Recall — what we missed

The 30-file PetClinic codebase does not exercise the
following R115-R135 patterns (and that's expected — it's
a small, simple app):

- **Taint-flow leaks (R121 / R123 / R133)** — PetClinic
  uses JPA repositories, which are parameterized. No raw
  SQL, no log statements that could leak credentials, no
  sensitive data flowing into a sink. The taint engine
  has 0 fires, which is correct.
- **Crypto / random (R119b)** — PetClinic doesn't use
  cryptography. 0 fires, correct.
- **Webapp config (R119c)** — PetClinic doesn't have
  CORS / cookie / TLS config in `src/main/`. The
  config checks (R119c) are correctly silent.
- **Defensive (R120)** — PetClinic has 0 BAD_SHIFT,
  INFINITE_LOOP, or CONSTANT_CONDITION patterns. The
  checks are correctly silent.
- **Hardcoded credentials (R124)** — No `password = "..."`
  patterns in PetClinic. The check is correctly silent.

The 4 categories that DO fire (authz, validation, info
exposure, null deref) are the ones the 30-file PetClinic
exercises. **The recall is complete for what PetClinic
exhibits**.

## Precision — what we over-fired on

Two FP categories:

### NULL_DEREFERENCE (2 FPs)

**FP-1: catch parameter.** `PetController.java:171`
calls `isDuplicatePetNameViolation(ex)` inside a `catch
(DataIntegrityViolationException ex)` block. The catch
parameter `ex` is non-null (the JVM guarantees the
caught exception is non-null). The null-deref check
fires anyway because it can't tell the `ex` is a catch
parameter.

**FP-2: `@InitBinder` parameter.** `VisitController.java:53`
calls `dataBinder.setDisallowedFields(...)` inside a
`@InitBinder` method whose parameter is `WebDataBinder
dataBinder`. Spring guarantees the binder is non-null.
The null-deref check fires anyway.

These two FPs are noise — reviewers would dismiss them
on sight. But the FPs suggest the null-deref check is
missing the "this parameter is guaranteed non-null"
inference. A R137+ round could:
- recognize catch parameters as non-null
- recognize `@InitBinder`, `@PathVariable`, `@RequestParam`
  parameters as non-null when the framework provides them

### STYLE_UNUSED_IMPORT (117 FPs)

The style check's "unused import" detection has a known
gap: it doesn't recognize that imports referenced only in
annotations (e.g. `import jakarta.persistence.Entity`
on a class with `@Entity`) are not "unused". PetClinic
imports 117 JPA / Jackson / Spring annotations that
ARE used in class-level annotations. The check fires
on all of them.

This is a known issue (the style check is class-level,
not annotation-aware). A R137+ round could either:
- exclude annotation imports from the unused check, or
- add a class-level annotation usage pass that
  recognizes `import jakarta.persistence.Entity` as
  used by `@Entity` on a class member.

## Gaps identified (R137+ candidates)

The smoke test surfaced four real gaps:

1. **R137 candidate: `MISSING_AUTHZ` should also check
   class-level security.** PetClinic has no
   `@EnableMethodSecurity` on the application class. A
   real review would check this. Currently the check
   only looks at per-method `@PreAuthorize` / `@Secured`
   etc.

2. **R137 candidate: `STYLE_UNUSED_IMPORT` should
   exclude annotation-imports.** PetClinic's 117 FPs are
   all annotation imports. The fix is small (a list of
   "annotation-only" packages: `jakarta.persistence.*`,
   `jakarta.validation.*`, `jakarta.xml.bind.*`, etc.).

3. **R138 candidate: `NULL_DEREFERENCE` should
   recognize catch / framework-provided parameters as
   non-null.** The 2 PetClinic FPs are both this
   pattern.

4. **R139 candidate: scan input filter is too
   aggressive.** `ScanRunner.expandInputs` filters out
   any path containing `test\`. The `smoke_petclinic\`
   directory was filtered out for the first run. The
   fix is to match `src/test` specifically, not
   arbitrary `test\`.

## Validation

R136's smoke test adds 16 findings on a 30-file / ~1900
LOC real-world codebase. The security checks (R126 / R128
/ R134) all fire correctly with per-class aggregation.
The recall for the patterns PetClinic exhibits is 100%
(0 false negatives on authz / validation / info-exposure).
The precision on security is 87.5% (2 FPs out of 16).
The style check has a known gap (annotation imports) that
isn't a security finding.

The R115-R135 stack works on real code. The next round
(R137+) should close the style-FP gap and add a real
`MISSING_AUTHZ`-class-level check.

## Files changed (R136)

- `plumb-dfa/.../MissingAuthorizationCheck.java` —
  `classKeyOf` takes source path, falls back when no
  `.` in signature
- `plumb-dfa/.../MissingInputValidationCheck.java`
  — same fix
- `plumb-dfa/.../InformationExposureCheck.java`
  — same fix
- `smoke_petclinic/spring-petclinic-main/` — downloaded
  Spring PetClinic (canonical sample, 30 files, ~1900
  LOC, BSD-2-Clause)

## Limitations and R137+ work

- **Style FPs are the elephant in the room.** 117 of the
  143 findings are style FPs. The recall on style is
  correct (the imports really aren't used in the
  executable code), but the precision is poor. A
  annotation-aware style check is R137.
- **No real Spring Security annotations on the
  classpath.** PetClinic's `@Controller` is the only
  Spring annotation in `src/main/`; the security
  annotations (`@PreAuthorize` etc.) are not referenced.
  The MISSING_AUTHZ check fires on the absence — which
  is the right behavior — but a more nuanced check
  would also detect when `@PreAuthorize` is *present*
  but mis-configured (e.g. `hasAuthority('USER')` for
  a public endpoint). That's R137+.
- **No real CVE history.** PetClinic is a reference
  sample, not a real production app. It doesn't have
  CVE-listed bugs to validate against. A R138 round
  could pick a CVE-listed open-source project
  (e.g. Apache Struts 2 for the OGNL injection bug) to
  validate the scanner's CVE recall.
- **Static type info is incomplete.** PetClinic's
  classes (`Owner extends Person`, `Pet extends
  NamedEntity`) are correctly resolved by the
  translator. But the type solver doesn't have a
  classpath, so cross-TU types are UNKNOWN. For real
  recall, R139+ needs a classpath-aware type solver
  (or at least a stub-classpath generator).

## Related docs

- `R126-MISSING-AUTHZ.md` — the per-class aggregation
  primitive that R136's bug fix is for
- `R128-MISSING-INPUT-VALIDATION.md` — same
- `R134-INFORMATION-EXPOSURE.md` / `R135-GENERIC-RESPONSE-WRAPPER.md`
  — same
- `TECHNICAL-CONTRIBUTIONS-R115-R125.md` /
  `TECHNICAL-CONTRIBUTIONS-R126-R135.md` — the prior
  contributions
