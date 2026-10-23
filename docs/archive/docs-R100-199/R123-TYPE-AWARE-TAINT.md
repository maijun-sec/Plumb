# R123 — Type-aware source matching (TypeTable wire-up)

## Scope

R123 connects the long-existing {@link
org.plumb.dfa.type.TypeTable} to the taint engine. The
end result is that method-only sensitive-getter patterns (e.g.
`**.getPassword/*`) now fire **only** on user-declared classes,
not on JDK / framework classes like `HashMap` or `Random`. This
is the precision improvement the R121 doc called out as a
deferred item, and it's a prerequisite for `MISSING_AUTHZ` and
`TRUST_BOUNDARY_VIOLATION` (which need class-aware analysis to
identify servlet entry points / annotated methods).

| ID | Slug | What it does |
|----|------|--------------|
| R123a | (no new rule) | `TypeTable` exposes `receiverClassName(fnSig, receiver)` for instance-method calls |
| R123b | (no new rule) | `TypeTable.isUserClass(fqn)` distinguishes user-declared classes from JDK / framework classes (handles both FQN and simple-name forms) |
| R123c | (no new rule) | `TaintCatalog.Entry.matches(TacInstruction, TypeTable, fnSig)` is a type-aware variant of the existing match; the legacy single-arg form is preserved |
| R123d | (no new rule) | `TaintAnalysis` consults the type table on `TAINT_SOURCE_SENSITIVE_DATA` source matches; `SensitiveDataLeakCheck` builds the table once and stashes it in the analysis context |

## Files

```
plumb-dfa/src/main/java/com/hellojava/sast/dfa/
    AnalysisContext.java                   (R123d: typeTable() / setTypeTable)
    SensitiveDataLeakCheck.java           (R123d: build TypeTable on first run)
    type/TypeTable.java                   (R123a: receiverClassName; R123b: isUserClass + KNOWN_JDK_SIMPLE)
    taint/TaintAnalysis.java              (R123c: type filter in SOURCE case)
    taint/TaintCatalog.java               (R123c: Entry.matches overload)
plumb-dfa/src/test/java/com/hellojava/sast/dfa/
    TypeAwareSensitiveTest.java           (NEW, 3 tests)
```

## Technical advantages

1. **Precision without losing coverage.** The R121 sensitive
   catalog uses method-only patterns (`**.getPassword/*` etc.)
   because we have no type solver. Without type information,
   these patterns match every object's `getX` call — including
   `HashMap.getPassword()` (a method that doesn't even exist on
   `HashMap`, but the catalog can't tell). R123 narrows the
   pattern to user-declared classes: when the type table knows
   the receiver is a JDK / framework class, the source is
   suppressed. When the type table is silent (cross-TU,
   reflective, etc.), the R121 behaviour is preserved.

2. **Reuses existing infrastructure.** The `TypeTable` was
   already built for the R117 type-aware flow but never wired
   into the taint engine. R123 is the first concrete consumer,
   validating the data shape and providing a template for
   future class-aware checks (MISSING_AUTHZ, TRUST_BOUNDARY).

3. **Cost is bounded.** The `TypeTable.analyze(program)` pass
   walks the IR once (linear in function size). The
   `receiverClassName` lookup is O(1) via the per-function
   `HashMap`. The type filter in `TaintAnalysis.transferInvoke`
   is one extra `if`-branch and a string-prefix / set-membership
   check. No new dataflow machinery.

4. **Conservative suppression.** `isUserClass(null)` returns
   `false` (so we don't accidentally treat an unknown type as
   user). `isUserClass("HashMap")` returns `false` (HashMap is
   a known JDK simple name). `isUserClass("MyUserClass")`
   returns `true`. The simple-name set is intentionally
   additive: classes not in the set are treated as user
   classes, so an application that names one of its own
   classes "Util" is still classified as user.

## The `KNOWN_JDK_SIMPLE` set

TAC records the simple class name (`HashMap`, `Random`,
`StringBuilder`, …) rather than the FQN when the source
imports the class and the translator doesn't re-symbol-resolve.
The `KNOWN_JDK_SIMPLE` set is a hard-coded list of common JDK
simple names that the type filter treats as system classes.
The list covers:

* collections (`HashMap`, `HashSet`, `ArrayList`, `Map`, …)
* random / numbers (`Random`, `Integer`, `BigDecimal`, …)
* strings (`String`, `StringBuilder`, `Pattern`, `Matcher`)
* I/O (`File`, `InputStream`, `URL`, `URLEncoder`, …)
* misc (`Object`, `Throwable`, `System`, `Class`, `Process`,
  `Optional`, `Stream`, `Collectors`, `Date`, …)

It's not exhaustive. The point is to cover the well-known
classes that show up in everyday code; uncommon JDK classes
fall through to the user-class branch (the conservative
default), and only fire on a real user-declared class with
that name in the program (rare in practice).

## Why the test passes

`TypeAwareSensitiveTest.suppressesSystemClassReceiver` writes
`HashMap<String,String> m = new HashMap<>(); m.getPassword()`.
The TAC records `m`'s type as `HashMap` (simple name from
`TypeMapper.map`). The `TaintAnalysis` SOURCE case calls
`typeTable.receiverClassName(fnSig, m)` → `"HashMap"`. The
filter calls `isUserClass("HashMap")` → `false` (in the
known-JDK-simple set). The source is suppressed and the
catalyst `System.out.println(p)` does not fire
`SENSITIVE_DATA_LEAK`.

`firesOnUserClassReceiver` writes the same shape with a
`User u; u.getPassword();` argument. The TAC records `u`'s
type as `User`. `isUserClass("User")` → `true` (not in the
known-JDK set). The source fires and the leak is reported.

## Test counts

| module | R122 | R123 | delta |
|--------|-----:|-----:|------:|
| parser | 3 | 3 | 0 |
| tac | 16 | 16 | 0 |
| rules | 2 | 2 | 0 |
| dfa | 109 + 1 skip | 112 + 1 skip | +3 |
| style | 6 | 6 | 0 |
| reporter | 2 | 2 | 0 |
| main | 3 | 3 | 0 |
| **total** | **141 + 1** | **144 + 1** | **+3** |

End-to-end on `examples/`:
* `R121Demo.java` still fires exactly 4 `SENSITIVE_DATA_LEAK`
  findings (no false negatives; the demo's `User` / `Auth` /
  `Keyring` / `ApiConfig` classes are user classes).
* `BadCode.java` is unchanged at 16 findings.
* `examples/complex/NullDerefDemo.java` and others are
  unchanged.

## What R123 doesn't do (still deferred)

* **`MISSING_AUTHZ`** (R119c/R121 deferred) — needs a type
  solver that resolves method-level annotations
  (`@PreAuthorize` / `@RolesAllowed`). R123 narrows the
  sensitive-getter patterns but doesn't add the authz-aware
  pattern matching.
* **`TRUST_BOUNDARY_VIOLATION`** (Coverity) — needs entry-point
  classification (servlet / controller methods). The
  `TypeTable.receiverClassName` primitive is the first step;
  the next round would extend it to "is this method a
  controller entry point?" by looking at class-level
  annotations.
* **Field-name sensitive detection** — `String password =
  "literal"` as a `String` field is a different shape than
  `getPassword()`. TypeTable doesn't track field names today.
* **Cross-TU type resolution** — when the receiver's class
  lives in a different translation unit (e.g. a `User` class
  in `auth/` used by `web/`), the type table sees the
  receiver as a parameter / field and can't always resolve
  the FQN. The fall-back to "user class" still fires, so
  coverage is preserved; precision could improve with a
  cross-TU class index.
* **Generic-aware type inference** — `List<User>` vs
  `List<HashMap>` would let the check distinguish "the list
  itself is a system class" from "the list's element type is
  a user class". Not currently modelled; would need a
  parameterized type solver.
