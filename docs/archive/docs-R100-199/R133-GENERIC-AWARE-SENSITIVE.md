# R133 — Generic-Aware Sensitive Suppression (TaintAnalysis upgrade)

**Status:** complete (2026-07-27)
**Tests:** +4 dfa (`GenericAwareSensitiveTest`), **170 dfa + 1 skip** total
**End-to-end:** 124 findings on `examples/` (zero regression vs R132)
**Scope:** TaintAnalysis upgrade — first consumer of R132's
`typeArgsOf(...)` infrastructure

## Why

R121 introduced the sensitive-data-leak check, which fires on
method-only patterns like `getPassword`. R123 added a
type-aware suppression: when the receiver is a JDK class
(`HashMap`, `Random`, ...), the call is suppressed because
`HashMap.getPassword()` doesn't really exist.

R132 added the `typeArgs` channel to `TypeTable` — but
without a consumer. R133 is the first consumer, and it
closes the obvious gap: when the receiver is a JDK
*container* (`List`, `Map`, ...), the check should also
look at the type arguments. `List<HashMap>.getPassword()`
doesn't exist; `List<User>.getPassword()` does.

## What R133 does

Two pieces in `TaintAnalysis`:

1. **JDK_CONTAINER_CLASSES set** — 17 entries (the
   top-level interfaces plus common JDK impls). When the
   receiver of a sensitive-getter call resolves to one of
   these classes, R133 consults `TypeTable.typeArgsOf(...)`
   for the type-argument list.

2. **All-JDK type-args suppress** — if all the type args
   are JDK classes (per the existing
   `TypeTable.isUserClass(...)` heuristic), the call is
   suppressed. If any type arg is a user class, the call
   is the real R121 pattern and fires as before.

This is a strict superset of R123: R133 adds a new
suppression path (JDK container with all-JDK type args)
without changing any existing fire / suppress decision.

## Test scenarios (4/4 passing)

`GenericAwareSensitiveTest`:

1. `listOfHashMapGetPasswordIsSuppressed` — `List<HashMap>`
   is a JDK container with all-JDK type args → suppress
2. `listOfUserGetPasswordIsFired` — `List<User>` is a JDK
   container with a user type arg → fire (the real R121
   pattern)
3. `typeTableTypeArgsAreRecorded` — locks in the R132
   contract: `TypeTable.typeArgsOf(...)` returns the
   parameter list
4. `typeTableRecognizesJdkContainers` — locks in the
   `JDK_CONTAINER_CLASSES` set

## Innovation summary (R133-specific)

- **R132 was the infra; R133 is the first consumer.** This
  is the canonical "build it, then use it" pattern that
  runs through the project — R117 designed `TypeTable`,
  R123 was the first consumer. R132 added `typeArgs`,
  R133 is the first consumer. The lesson generalizes:
  every infrastructure addition eventually gets a
  consumer, and the consumer is what justifies the
  infra.
- **The container set is a 17-entry `Set<String>`, not a
  classpath resolver.** This is the same simple-table
  approach that R123 / R125 use. The cost is one
  `HashSet` lookup per fire decision; the precision is
  the same as R123.
- **No new CWE / no new checker.** R133 is a precision
  improvement on an existing check. The infrastructure
  work (R132) was 1 round; the consumer (R133) is
  another round. The two together form a "build infra,
  consume infra" pair that demonstrates the project's
  iterative pattern.

## Files changed

- `plumb-dfa/.../taint/TaintAnalysis.java` — added
  `JDK_CONTAINER_CLASSES` set, `isJdkContainer(...)` and
  `allArgsAreJdk(...)` helpers, and a new branch in the
  CALL processing that consumes `typeArgsOf(...)`
- `plumb-dfa/.../test/.../GenericAwareSensitiveTest.java`
  (new, 4 tests)

## Related docs

- `R121-SENSITIVE-DATA-LEAK.md` — the original check
- `R123-TYPE-AWARE-TAINT.md` — the first TypeTable
  consumer; R133 is the second
- `R132-GENERIC-AWARE-TYPE.md` — the infra R133 consumes
