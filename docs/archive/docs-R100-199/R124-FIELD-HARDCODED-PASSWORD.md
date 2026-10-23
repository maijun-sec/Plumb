# R124 — Field-hardcoded credential check (Coverity gap fill, round 4)

## Scope

R124 closes the gap that R121's `SENSITIVE_DATA_LEAK` couldn't
reach: a credential declared as a class field with a literal
value. The check flags any instance field whose name contains a
credential token and whose value is a string literal.

| ID | Slug | CWE |
|----|------|-----|
| bu-0801 | `FIELD_HARDCODED_PASSWORD` | CWE-798 / CWE-259 |

This brings the project to **36 checker categories** total.

The R124 work also surfaced (and fixed) a long-standing
translator limitation: instance-field initializers were being
silently dropped (only `static final` initializers were captured
into `<clinit>`). The fix emits a synthetic `<init>` that holds
one `FIELDSTORE` per initialized instance field, so
`private String password = "literal"` is now visible to
`FIELDSTORE`-based checks.

## Files

```
plumb-tac/src/main/java/com/hellojava/sast/tac/translate/
    TacTranslator.java                       (R124: emitInstanceFieldInit + synthetic <init>)
plumb-dfa/src/main/java/com/hellojava/sast/dfa/
    FieldHardcodedPasswordCheck.java          (NEW)
plumb-dfa/src/test/java/com/hellojava/sast/dfa/
    FieldHardcodedPasswordCheckTest.java      (NEW, 6 tests)
plumb-rules/src/main/java/com/hellojava/sast/rules/
    RuleRegistry.java                         (1 new bu-0801 def)
plumb-main/src/main/java/com/hellojava/sast/main/
    ScanConfig.java                           (runFieldHardcodedPassword)
    ScanRunner.java                           (wire-up)
examples/r124/
    R124Demo.java                             (end-to-end demo, 4 findings)
```

## Technical advantages

1. **Covers a previously-uncovered attack surface.** R121's
   `SENSITIVE_DATA_LEAK` follows a credential through runtime
   flows. R119b's `STATIC_API_KEY` finds `static final` secrets.
   **Neither covered the most common case**: a plain
   `private String password = "literal"` in a config / model
   class. The credential is right there in the .class file
   (`javap -c -p` shows it as a string constant). R124 is the
   one that catches it.

2. **Drives an architectural fix in the translator.** R124
   required the TAC IR to surface instance-field initializers
   as `FIELDSTORE` instructions. The previous behavior dropped
   them silently. The fix (a synthetic `<init>` per class with
   no explicit constructor) is small and contained, and opens
   the door for other FIELDSTORE-based checks (e.g. a future
   `FIELD_TAINTED_BY_TAINTCONFIG` checker for credentials
   populated from `request.getParameter`).

3. **Reuses the StaticApiKeyCheck name filter as a starting
   point.** The sensitive-name regex is a superset of the
   static-key one (it also catches `password`, `passwd`, `pwd`,
   `clientSecret`, `client[_-]?secret`, `access[_-]?token`,
   `auth[_-]?token`, `bearer[_-]?token`, `refresh[_-]?token`,
   `signing[_-]?key`). The placeholder set is identical
   (`"..."`, `"changeme"`, `"your-api-key"`, etc.) so config
   templates aren't flagged.

4. **One-hop MOVE chase.** The TAC for a field initializer is
   `t = "literal"; this.password = t;`. The check's
   `resolveRhs` walks the `FIELDSTORE.dest` one step back to
   the `MOVE.lhs1`, recovering the literal value. Without
   this, the dest of the `FIELDSTORE` is a temp var, not the
   literal, and the check would miss the value.

5. **`<clinit>` is excluded.** `StaticApiKeyCheck` owns the
   static-field-initializer space. The two checkers don't
   double-report on the same line.

## Field-name coverage

The `SENSITIVE_NAME` regex is intentionally broad to catch
real-world naming variations:

| category | examples |
|----------|----------|
| password variants | `password`, `passwd`, `pwd`, `userPassword` |
| secret variants | `secret`, `clientSecret`, `signingSecret` |
| API key | `apiKey`, `api_key`, `api-key`, `accessKey` |
| tokens | `authToken`, `accessToken`, `bearerToken`, `refreshToken` |
| credentials | `credential`, `credentials` |
| private key | `privateKey`, `signingKey` |

Anything that doesn't match the regex is not flagged. The
check is conservative — a field named `userSecret` (which
matches `secret`) is flagged, but a field named `userHash` is
not.

## Tests cover

* `private String password = "P@ssw0rd123!"` — detected
* `private String apiKey = "sk_live_AbCdEf12345Example"` — detected
* `private String clientSecret = "super-secret-string"` — detected
* `private String password = "changeme"` — **not** flagged (placeholder)
* `private String description = "Some text"` — **not** flagged
  (non-sensitive name)
* Multiple fields in one class — each flagged independently

## End-to-end (`examples/r124/R124Demo.java`)

The demo has 5 static inner classes. 4 fields fire
`FIELD_HARDCODED_PASSWORD` (CRITICAL severity). 2 fields are
correctly silent (one placeholder, one non-sensitive name).

`examples/complex/BadCode.java` is unchanged at 16 findings
(no regression from the translator change — BadCode doesn't
have any instance-field initializers with sensitive names).

## Test counts

| module | R123 | R124 | delta |
|--------|-----:|-----:|------:|
| parser | 3 | 3 | 0 |
| tac | 16 | 16 | 0 |
| rules | 2 | 2 | 0 |
| dfa | 112 + 1 skip | 118 + 1 skip | +6 |
| style | 6 | 6 | 0 |
| reporter | 2 | 2 | 0 |
| main | 3 | 3 | 0 |
| **total** | **144 + 1** | **150 + 1** | **+6** |

End-to-end on `examples/`: 98 findings total (was 89 in R122,
+4 from R123's TypeAware sensitive refinement not changing the
count, +5 from R124: 4 new in R124Demo, 1 picked up elsewhere).

## What R124 doesn't do (still deferred)

* **Direct field assignment** in a method body
  (`this.password = "literal"`). The translator currently
  emits a `MOVE` for `password = "literal"` in a method rather
  than a `FIELDSTORE`. Fixing this is a separate translator
  change; the R124 scope is field-declaration-time
  credential detection (the more common and more dangerous
  shape — the value is in the .class file).
* **Tainted-source credential assignment** — `password =
  request.getParameter("password")` is a different
  (also-bad) pattern, but a different checker (would belong
  to the existing taint engine; the credential would be marked
  as user-input at the assignment site).
* **Annotation-based suppression** — Spring `@Value("${...}")`
  and similar DI annotations currently look like literal
  strings to the regex. The check could filter
  `FIELDSTORE` whose RHS is a `@Value`-tagged expression, but
  annotations aren't currently preserved in the IR.
* **MISSING_AUTHZ** (R119c/R121/R123 deferred) — still needs
  annotation-aware IR. R123's TypeTable groundwork plus
  R124's instance-field / annotation-preservation work is
  the necessary foundation.
* **Field-name sensitive detection at the `TypeTable` level**
  — R123's `TypeTable` doesn't track field declarations at all
  (only var types and field hints). The next round would
  extend TypeTable to record field-name → type, opening the
  door to "field named `password` is a String" → mark taint
  semantic for the SensitiveDataLeakCheck.
