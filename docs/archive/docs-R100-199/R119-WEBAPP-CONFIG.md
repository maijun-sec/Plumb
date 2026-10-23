# R119c — Webapp Config Checkers

**Date**: 2026-07-27
**Status**: ✅ Complete (3 checkers, 1 deferred)
**Reference**: Coverity `INSECURE_COMMUNICATION`, `CORS_MISCONFIGURATION`, `INSECURE_COOKIE`, `MISSING_AUTHZ`

R119c fills the webapp config gap surfaced by the Coverity gap analysis. Three new structural DFA checkers plus a documented deferral.

## New checkers

| Rule ID | Rule name | CWE | Severity | Triggers on |
|---|---|---|---|---|
| `bu-0501-insecure-comms` | `INSECURE_COMMUNICATION` | CWE-319 | MEDIUM | `new URL("http://...")`, `new URL("ftp://...")`, `new URL("ldap://...")`, etc. |
| `bu-0502-cors-misconfig` | `CORS_MISCONFIGURATION` | CWE-942 | HIGH | `.setAllowedOrigins("*")`, `.addAllowedOrigin("*")`, `.allowedOrigins("*")` (method-only) |
| `bu-0503-insecure-cookie` | `INSECURE_COOKIE` | CWE-614, CWE-1004 | MEDIUM | `new Cookie(...)` without both `setSecure(true)` AND `setHttpOnly(true)` in the same function |

**Deferred**: `MISSING_AUTHZ` (CWE-862). The check would need to detect servlet entry points (`@RequestMapping` / `HttpServlet.doGet`/`doPost`), trace their data flow into a sensitive API (`findUserById`, `getAccount`, ...), and confirm the absence of `@PreAuthorize` / `@RolesAllowed` / `@Secured` annotations. This is a multi-week R-round (R120+) that needs a type solver and annotation-aware IR.

## Implementation notes

### InsecureCommunicationCheck
- The check inspects `NEW` instructions whose target type is `URL` / `URI` (short or FQN) and whose first arg is a string literal.
- The protocol is extracted by splitting on `:`. Insecure protocols (cleartext or legacy): `http`, `ftp`, `telnet`, `ldap`, `jdbc`.
- The `https`, `ssh`, `git+ssh` families are correctly silent.
- Conservative: fires on every `http://` URL even for localhost dev (the false-positive cost is much lower than missing a real cleartext endpoint).

### CorsMisconfigurationCheck
- Matches call sites whose calleeSig ends with `setAllowedOrigins/N` or `addAllowedOrigin/1`, plus a method-only `allowedOrigins/1` fallback (for chained CorsRegistry lambdas where the class name is lost).
- Scans all args for a `STRING` constant equal to `"*"` (after `trim()`).
- Limitation: `Arrays.asList("*")` (where the literal is inside a `List`) is not caught because the literal is in the `asList` call's args, not the `setAllowedOrigins` call's args. Would require chasing the list-construction call graph. Documented in the test suite as a known limitation.

### InsecureCookieCheck
- Two-pass intra-procedural analysis:
  1. **First pass**: collect which var names have had `setSecure(true)` and `setHttpOnly(true)` called on them.
  2. **Fix-point walk**: propagate hardening through MOVE chains (`c = t_t0; c.setSecure(true)` ⇒ `t_t0` is also marked secure). Iterate until no change.
- A `new Cookie(...)` is reported if its dest var has NOT been hardened with **both** flags.
- Partial hardening (only `setSecure` or only `setHttpOnly`) still fires — both are required for the "fully hardened" suppression. CWE-614 / CWE-1004 best-practice is to enable both.
- Conservative on cross-method hardening: a `private void harden(Cookie c)` helper is not modeled. Real fix would use R116 cross-function analysis.

## Files changed

- `dfa/InsecureCommunicationCheck.java` (NEW)
- `dfa/CorsMisconfigurationCheck.java` (NEW)
- `dfa/InsecureCookieCheck.java` (NEW)
- `rules/RuleRegistry.java` — 3 new rule IDs + register
- `main/ScanConfig.java` — 3 new boolean flags
- `main/ScanRunner.java` — 3 new wires (both single-file and merged), condition flag updated
- `examples/r119/R119WebAppDemo.java` (NEW) — end-to-end demo
- `dfa/WebAppConfigCheckTest.java` (NEW, 8 tests, 1 documented skip) — unit tests

## End-to-end verification

`examples/r119/R119WebAppDemo.java` (real CLI run) reports **4 webapp config findings**:

| Rule | Count | Lines |
|---|---|---|
| `INSECURE_COMMUNICATION` | 1 | 12 (http URL) |
| `CORS_MISCONFIGURATION` | 1 | 23 (wildcard origin) |
| `INSECURE_COOKIE` | 2 | 34 (unhardened), 39 (partial — only setSecure) |

`httpsEndpoint`, `specificCors`, and `hardenedCookie` (lines 17, 28, 45) are correctly silent.

## No regression

- `examples/complex/BadCode.java` — 16 findings, unchanged from R119b.
- Full test suite — 68/68 dfa (was 60 in R119b, +8 from `WebAppConfigCheckTest`).

## Key learnings (R119c)

1. **MOVE-chain hardening propagation**: A common idiom is `Cookie t = new Cookie(...); Cookie c = t; c.setSecure(true);` where the hardening call is on `c` but the NEW is on `t`. A naive var-name check would miss this. Solution: walk MOVE chains after collecting direct hardening, with a fixpoint loop until no new var becomes hardened.

2. **Partial hardening is a finding**: Suppressing a Cookie on `setSecure` alone (without `setHttpOnly`) would mask real security gaps. The check tracks the two hardening methods separately and requires BOTH for suppression.

3. **Method-only `endsWith` for the inner method name**: For chained CorsRegistry calls like `registry.allowedOrigins(...)` where the class name is lost in the IR, matching on the method name only (`.endsWith("allowedOrigins/1")`) is acceptable. The risk of false positives is low because `allowedOrigins` is a Spring-specific term.

4. **Literal inside `Arrays.asList("*")` is unreachable**: The literal "*" is in the `asList` call's args, not the `setAllowedOrigins` call's args. Cross-call constant chasing is needed to flag this; out of scope for this round (would need a more sophisticated constant-folding + call-chain analyzer).

5. **MISSING_AUTHZ is not implementable without a type solver**: The check would need to know that a public servlet method reads sensitive data and confirm the absence of authorization annotations. We don't track types or annotations in the current IR. Defer to a future round that adds annotation-aware analysis.

## Limitations / future work

- **MISSING_AUTHZ** — annotation-aware auth check (CWE-862). Defer to R120+ when annotation and type-solver infrastructure is in place.
- **`setAllowedOrigins(Arrays.asList("*"))`** — literal inside a list construction is not chased. Would need call-chain const-folding (R119b already noted this is partial).
- **Cross-method Cookie hardening** — a `private void harden(Cookie c)` helper is not modeled. Could be added via R116 cross-function analysis (the recursive call would have to recognize the hardening side-effects on the param).
- **Cookie from `addCookie(response, ...)`** — Spring's `ResponseCookie` builder and `HttpServletResponse.addCookie(...)` are different patterns. Currently we only check the servlet `Cookie` class directly.
- **InsecureCommunicationCheck** for `URL.openConnection()` / `URL.openStream()` over an http URL — would need to look at the URL var, not the NEW site. Future R-round can extend.
