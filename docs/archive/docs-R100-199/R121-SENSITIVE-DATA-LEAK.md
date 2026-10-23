# R121 — Sensitive Data Leak (Coverity gap fill, round 3)

## Scope

R121 fills one of the most impactful Coverity categories:
`SENSITIVE_DATA_LEAK` (CWE-532). The check flags any flow of
sensitive data (passwords, sessions, secrets, tokens) into a leak
outlet (log, throw, file write, network out). One new checker:

| ID | Slug | CWE |
|----|------|-----|
| bu-0701 | `SENSITIVE_DATA_LEAK` | CWE-532 |

A new supporting source-id:

| ID | Slug | CWE |
|----|------|-----|
| bu-0008 | `TAINT_SOURCE_SENSITIVE_DATA` | CWE-532 (informational) |

This brings the project to **35 checker categories** total.

## Files

```
plumb-dfa/src/main/java/com/hellojava/sast/dfa/
    SensitiveDataLeakCheck.java                (NEW — wraps TaintAnalysis)
plumb-dfa/src/main/java/com/hellojava/sast/dfa/taint/
    TaintCatalog.java                          (sensitiveBuiltin())
plumb-dfa/src/main/java/com/hellojava/sast/dfa/taint/
    TaintAnalysis.java                         (dedup key includes sink sig)
plumb-dfa/src/test/java/com/hellojava/sast/dfa/
    SensitiveDataLeakCheckTest.java            (NEW — 7 tests)
plumb-rules/src/main/java/com/hellojava/sast/rules/
    RuleRegistry.java                          (2 new bu-0xxx defs)
plumb-main/src/main/java/com/hellojava/sast/main/
    ScanConfig.java                            (runSensitiveDataLeak)
    ScanRunner.java                            (wire-up)
examples/r121/
    R121Demo.java                              (end-to-end demo)
```

## Architecture

R121 introduces a **dedicated catalog** for sensitive data
(`TaintCatalog.sensitiveBuiltin()`), separate from the main
`builtin()`. The catalog contains:

* **Sources** (mark taint `bu-0008-taint-source-sensitive-data`):
  * HttpSession / ServletContext / `getAttribute` reads (any prefix)
  * `getUserPrincipal` (servlet principal)
  * Method-only matches: `getPassword` / `getSecret` / `getToken` /
    `getApiKey` / `getAPIKey` / `getCredential(s)` /
    `getSecretKey` / `getPrivateKey` / `getSessionId` /
    `getAuthToken` / `getAccessToken` / `getRefreshToken` / `decrypt`

* **Sinks** (all report with rule `bu-0701-sensitive-data-leak`):
  * `logger.*` and `Logger.*` methods (info / warn / error / debug /
    fatal / trace / log)
  * `System.out` / `System.err` (println / print / printf / format /
    write)
  * `new <Throwable>(msg)` for the common subclasses
    (RuntimeException, Exception, IllegalStateException,
    IllegalArgumentException, SecurityException) — both FQN and
    simple-name forms
  * `**.FileWriter.<init>` / `FileOutputStream.<init>` /
    `Files.write` / `Files.writeString`
  * Method-only fallbacks: `**.write` / `**.println` / `**.printf` /
    `**.format` (any receiver)
  * `HttpURLConnection.setRequestProperty` / `URLConnection.*` /
    `URL.openConnection` / `URL.openStream` / `new URL`

The `SensitiveDataLeakCheck` is a thin wrapper around a
`TaintAnalysis` configured with `sensitiveBuiltin()`. It runs its
own worklist. The main `TaintAnalysis` does **not** see the
sensitive source rule, so:

* **User input → log** fires only `LOG_INJECTION` (existing).
* **Sensitive data → log** fires only `SENSITIVE_DATA_LEAK` (new).
* No double-reporting on the same line.

The cost is one extra worklist pass per scan; the second pass only
does work when sensitive sources exist in the program.

### Method-only patterns: precision vs recall

The sensitive getter patterns are method-only (e.g. `**.getPassword/*`)
because the receiver type is unknown without a type solver. This is
the standard Coverity trade-off: a few false positives are
acceptable for a `CRITICAL` security finding on a class of bug
where missing a real leak is much worse than a noise report.

## A subtle dedup fix

R121 surfaced a pre-existing limitation in `TaintAnalysis.reportFinding`:
the dedup key was

```
ruleId | line | fn.signature()
```

which is too coarse. When the same source line had two different
sinks (e.g. `System.out.println` + `throw new RuntimeException`),
both reported as the same rule and only the first was emitted.

R121 fixes this by adding the **sink identity** (calleeSig for
`CALL`, `new <ctorType>` for `NEW`) to the dedup key:

```
ruleId | line | sinkSig | fn.signature()
```

This is more correct globally (not just for sensitive data) and
means multi-sink lines now report each leak. The
`SensitiveDataLeakCheckTest` exercises this with the
`apiKeyToFile` + `secretToThrow` cases that share the same
`m(...)` signature but different sink identities.

## Tests cover

* `password → System.out.println` — detected (CRITICAL)
* `token → log.info` — detected
* `secret → throw new RuntimeException(secret)` — detected
* `apiKey → FileWriter.write` — detected (using the
  method-only `**.write/*` fallback)
* `session.getAttribute → log` — detected
* `benign String → log` — **not** flagged (no sensitive source)
* `sensitive read but only used in `.equals()` comparison — **not**
  flagged (no leak sink)

## End-to-end (`examples/r121/R121Demo.java`)

The demo has six methods:

| method | sink | finding |
|--------|------|---------|
| `passwordToConsole` | `System.out.println` | SENSITIVE_DATA_LEAK (line 25) |
| `tokenToLog` | `log.info` | SENSITIVE_DATA_LEAK (line 30) |
| `secretToThrow` | `throw new RuntimeException(secret)` | SENSITIVE_DATA_LEAK (line 35) |
| `apiKeyToFile` | `w.write` | SENSITIVE_DATA_LEAK (line 41) |
| `noFindingBenign` | `System.out.println` | (correctly silent) |
| `noFindingCompare` | `.equals` | (correctly silent) |

`examples/complex/BadCode.java` is stable at 16 findings — no
regression from the new dedup behavior.

## Test counts

| module | R120 | R121 | delta |
|--------|-----:|-----:|------:|
| parser | 3 | 3 | 0 |
| tac | 16 | 16 | 0 |
| rules | 2 | 2 | 0 |
| dfa | 95 + 1 skip | 102 + 1 skip | +7 |
| style | 6 | 6 | 0 |
| reporter | 2 | 2 | 0 |
| main | 3 | 3 | 0 |
| **total** | **127 + 1** | **134 + 1** | **+7** |

## What R121 doesn't do (deferred to R122+)

* **MISSING_AUTHZ** (R119c deferred) — still needs annotation-aware
  IR (servlet entry-point detection + `@PreAuthorize` /
  `@RolesAllowed` recognition + sensitive data API list). Not
  feasible without a type solver.
* **TRUST_BOUNDARY_VIOLATION** (Coverity) — the sibling
  categorization. The work overlaps with R121's leak sinks; the
  difference is the source side (e.g. flow from outside the
  trust boundary, not from a sensitive getter). Requires
  *entry-point* classification of functions, which is itself an
  R122+ effort.
* **`Arrays.asList("*")` constant chasing** (R119c deferred) — the
  cross-method const-folding. Would unlock `CorsMisconfiguration`
  for `cors.allowedOrigins(Arrays.asList("*"))`.
* **TypeTable → CallResolver wire-up** — would let
  method-only sensitive getters narrow to class-qualified patterns
  (e.g. only `Auth.getToken` fires, not `Random.nextInt`).
* **Field-name sensitive detection** — `password = "literal"` as a
  `String` field is a different code shape than
  `getPassword()`. Field-name-based marking would broaden the
  catch but is a separate feature.
* **Exception-path taint** (R118c deferred) — still needs CFG
  exception edges; once landed, this would let a sensitive value
  caught in a `try` and re-thrown in `catch` be tracked.
