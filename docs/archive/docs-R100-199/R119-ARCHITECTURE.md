# R119 — Coverity-Driven Capability Expansion

**Date**: 2026-07-27
**Status**: R119a complete; R119b/R119c planned
**Reference**: `D:/wsl/tools/cov-analysis-linux64-2023.6.0/doc/en/cov_checker_ref.html`

R119 uses the Coverity checker reference as a gap-analysis source. We surveyed Coverity's Java checkers and identified what's missing from `plumb`, then started filling the gap. R119a is the first step — eight new taint sinks covering high-priority CWE families.

## R119a — Extended Taint Sinks

### Gap analysis

| CWE | Coverity checker | plumb (before R119a) | R119a |
|---|---|---|---|
| CWE-90 | `LDAP_INJECTION` | ✗ | ✓ `TAINT_SINK_LDAP_INJECTION` (bu-0110) |
| CWE-91 | `XML_INJECTION` | ✗ | ✓ `TAINT_SINK_XML_INJECTION` (bu-0111) |
| CWE-93 | `HEADER_INJECTION` | ✗ | ✓ `TAINT_SINK_HEADER_INJECTION` (bu-0112) |
| CWE-134 | `FORMAT_STRING_INJECTION` | ✗ | ✓ `TAINT_SINK_FORMAT_STRING` (bu-0113) |
| CWE-943 | `NOSQL_QUERY_INJECTION` | ✗ | ✓ `TAINT_SINK_NOSQL_INJECTION` (bu-0114) |
| CWE-94/95 | `OGNL_INJECTION` / `EL_INJECTION` | ✗ | ✓ `TAINT_SINK_OGNL_INJECTION` (bu-0115) |
| CWE-1333 | `REGEX_INJECTION` | ✗ | ✓ `TAINT_SINK_REGEX_INJECTION` (bu-0116) |
| CWE-601 | `OPEN_REDIRECT` | ✗ | ✓ `TAINT_SINK_OPEN_REDIRECT` (bu-0117) |

### Implementation

1. **RuleRegistry** — added 8 new `TAINT_SINK_*` rule IDs (`bu-0110` through `bu-0117`) and 8 corresponding `register(def(...))` calls.
2. **TaintCatalog.builtin()** — added 8 new `Entry(Role.SINK, ...)` records. Each entry mixes:
   - **Class-qualified patterns** for static APIs (`Ognl.getValue`, `Pattern.compile`)
   - **Method-only fallbacks** for instance calls (where TAC loses the receiver type)
3. **Catalog reordering** — `REGEX_INJECTION` placed *before* `XPATH_INJECTION` because both have a `**.compile/*` method-only fallback; first-match-wins semantics require the more-specific regex to come first.
4. **R119a: leading-dot-optional fix in `pat()`** — discovered a pre-existing limitation: `**.Foo.bar/*` did not match `Foo.bar/N` (simple-name static call after an import). Rewrote the leading `**` followed by `.` to emit `(?:.*\.)?` (optional) so the pattern matches both FQN and simple-name. This is a *non-regressive* fix because the existing patterns gain an additional match (the previously-missed simple-name case) without losing the FQN case.
5. **End-to-end example** — `examples/r119/R119SinksDemo.java` exercises all 8 sinks. End-to-end CLI run reports 8 taint findings + 2 style findings.

### Catalog patterns (per sink)

| Sink | Class-qualified | Method-only fallback |
|---|---|---|
| LDAP | `**.InitialContext.search/*`, `**.DirContext.search/*`, `**.LdapTemplate.search/*`, etc. | `**.search/*` |
| XML | `**.Transformer.transform/*`, `**.DocumentBuilder.parse/*`, `**.SAXParser.parse/*`, `**.XMLDecoder.readObject/*`, `**.Unmarshaller.unmarshal/*` | `**.transform/*`, `**.unmarshal/*` |
| HEADER | `**.HttpServletResponse.setHeader/*`, `**.HttpServletResponse.addHeader/*`, `**.HttpServletResponse.setDateHeader/*`, `**.HttpServletResponse.setIntHeader/*`, `**.HttpServletResponse.addCookie/*` | `**.setHeader/*`, `**.addHeader/*`, `**.setDateHeader/*`, `**.setIntHeader/*` |
| FORMAT | `**.String.format/*`, `**.Formatter.format/*`, `**.PrintStream.printf/*`, `**.PrintWriter.printf/*` | `**.format/*` |
| NOSQL | `**.MongoCollection.find/*`, `**.MongoCollection.findOne/*`, `**.MongoCollection.aggregate/*`, `**.MongoTemplate.find/*`, `**.MongoTemplate.findOne/*`, `**.MongoTemplate.aggregate/*`, `**.Document.parse/*` | (none) |
| OGNL | `**.Ognl.getValue/*`, `**.Ognl.setValue/*`, `**.OgnlUtil.getValue/*`, `**.ExpressionParser.parseExpression/*`, `**.SpelExpressionParser.parseExpression/*`, `**.StandardEvaluationContext.*/*` | `**.getValue/*`, `**.setValue/*`, `**.parseExpression/*` |
| REGEX | `**.Pattern.compile/*`, `**.Pattern.matches/*` | `**.compile/*` |
| OPEN_REDIRECT | `**.HttpServletResponse.sendRedirect/*`, `**.RedirectView.setUrl/*` | `**.sendRedirect/*`, `**.setUrl/*` |

### Over-approximation policy

Method-only fallbacks are conservative — better a few extra findings than missing a real one. The trade-offs are explicit:

- `**.search/*` (LDAP): may fire for any `search` call (e.g. `List.search`); low noise in real code.
- `**.transform/*` (XML): may fire for any `transform` call; matches things like Apache Spark's `transform`.
- `**.compile/*` (REGEX): may fire for any `compile` call; but `**.compile` is in a fallback position, so the more-specific `**.Pattern.compile/*` and `**.XPath.compile/*` (now removed from XPath after reordering) come first.
- `**.sendRedirect/*` (OPEN_REDIRECT): safe — `sendRedirect` is a Servlet-specific name.
- `**.format/*` (FORMAT): may fire for any `format` call (e.g. `Date.format`); low noise.
- `**.getValue/*` (OGNL): very generic; may fire for `Map.getValue`, etc. Trade-off accepted.
- `**.setHeader/*` (HEADER): may fire for any `setHeader` call; but in practice these are HTTP-headers.
- `**.addHeader/*` (HEADER): same.

### Verification

- **Unit tests** — `ExtendedSinksTaintTest` (9 tests, 9/9 pass): one per new sink + a clean-code negative test.
- **End-to-end** — `examples/r119/R119SinksDemo.java` reports 8 taint findings (one per new sink) + 2 style findings.
- **No regression** — `examples/complex/BadCode.java` still reports 14 findings (same as R118).
- **Full test suite** — 51/51 dfa (was 42), 6/6 style, 2/2 reporter, 3/3 main.

### Key learning (the pat() bug)

The pre-existing `pat()` function emitted `.*\.X\.Y\/N` for the glob `**.X.Y/*`. This required a literal dot before `X`, which failed to match the simple-name calleeSig `X.Y/N` (e.g. `Document.parse/1` after an import statement). Fix: when the leading `**` is at the start of the glob and followed by `.`, emit `(?:.*\.)?` instead of `.*\.`. The resulting regex matches both `X.Y/N` and `prefix.X.Y/N`.

Bug was discovered by the NoSQL end-to-end run failing — the unit test passed because it used the FQN form. This is a good example of why end-to-end tests are valuable: the unit test's pattern matched FQN but missed the simple-name case. End-to-end caught the gap.

### Limitations

- **Ognl is class-qualified** for the static call (`Ognl.getValue(expr, ctx)`); instance calls on a user-typed `Ognl` wrapper object would fire as the method-only `**.getValue/*` fallback (over-approximate).
- **NoSQL instance-method calls** like `collection.find(filter)` (where `collection` is a local var of `MongoCollection<Document>`) do not match the class-qualified patterns; the test was rewritten to use `Document.parse(json)` (static) to demonstrate the sink. To catch instance calls, we'd need a type solver — R119+ territory.
- **Mongo find with `Filters.eq(...)`** — the filter builder itself is a separate class (`Filters.eq` returns a `Bson`); we don't model the filter construction. The static `Document.parse(json)` is the more reliable NoSQL-injection entry point for now.

## R119b — Crypto & Insecure Random (planned)

Add 5 new checkers (Coverity gap: `WEAK_CRYPTO`, `INSECURE_RANDOM`, `PREDICTABLE_RANDOM_SEED`, `PREDICTABLE_KEY_PASSWORD`, `STATIC_API_KEY`):

- `WeakCryptoCheck` — flags `MessageDigest.getInstance("MD5" / "SHA-1")` and `Cipher.getInstance("DES" / "RC4" / "DES/ECB/...")`.
- `InsecureRandomCheck` — flags `new java.util.Random()` used in security-sensitive contexts (e.g. token generation).
- `PredictableRandomSeedCheck` — flags `new Random(<literal>)` or `Random.setSeed(<literal>)`.
- `PredictableKeyPasswordCheck` — flags `KeySpec(byte[], "literal")` or `PBEKeySpec(..., char[])` with a non-constant password.
- `StaticApiKeyCheck` — flags static final String fields whose name contains `API_KEY` / `SECRET_KEY` / `PRIVATE_KEY` and value is a literal (overlaps with `HardcodedCredentialCheck` but with stricter pattern).

## R119c — Webapp Config (planned)

Add 4 new checkers (Coverity gap: `INSECURE_COOKIE`, `INSECURE_COMMUNICATION`, `CORS_MISCONFIGURATION`, `MISSING_AUTHZ`):

- `InsecureCookieCheck` — flags `new Cookie(name, value)` without `setSecure(true)` and `setHttpOnly(true)`.
- `InsecureCommunicationCheck` — flags `new URL("http://...")` for sensitive endpoints.
- `CorsMisconfigurationCheck` — flags `setAllowedOrigins("*")`.
- `MissingAuthzCheck` — flags public servlet methods that read sensitive data without an `@PreAuthorize` / `rolesAllowed` check.

## Files changed (R119a)

- `rules/RuleRegistry.java` — 8 new `TAINT_SINK_*` constants + 8 `register(...)` calls.
- `dfa/taint/TaintCatalog.java` — 8 new `Entry(Role.SINK, ...)` records; `REGEX_INJECTION` moved before `XPATH_INJECTION`; `pat()` leading-dot-optional fix.
- `examples/r119/R119SinksDemo.java` (NEW) — end-to-end demo.
- `dfa/taint/ExtendedSinksTaintTest.java` (NEW, 9 tests) — unit tests.

## Test counts

- dfa: 42 (R118) → 51 (R119a) — +9: ExtendedSinksTaintTest 9.
- Other modules: unchanged.
- End-to-end: BadCode 14 findings (no regression); R119SinksDemo 8 new taint findings + 2 style.
