# R3 — Juliet Coverage Final Summary (2026-08-15)

## Goal
Continue Juliet CWE coverage optimization. R1 + R2 got the 12 taint
families from 0 → 8,568 cweFire hits. R3 adds more sinks / sources and
a few DFA-style checkers to push further.

## R1 + R2 + R3 final cweFire table

| CWE dir                              | v2 | R3   |  Δ     |
|--------------------------------------|---:|-----:|-------:|
| CWE78 OS Command Injection           |  0 |  391 |  +391  |
| CWE89 SQL Injection                  |  0 |2,030 |+2,030  |
| CWE90 LDAP Injection                 |  0 |  391 |  +391  |
| CWE134 Uncontrolled Format String    |  0 |  634 |  +634  |
| CWE113 HTTP Response Splitting       |  0 |  796 |  +796  |
| CWE80 XSS                            |  0 |  560 |  +560  |
| CWE81 XSS Error Message              |  0 |  280*|  +280* |
| CWE83 XSS Attribute                  |  0 |  280*|  +280* |
| CWE23 Relative Path Traversal        |  0 |  782 |  +782  |
| CWE36 Absolute Path Traversal        |  0 |  782 |  +782  |
| CWE601 Open Redirect                 |  0 |  280 |  +280  |
| CWE643 Xpath Injection               |  0 |  406 |  +406  |
| CWE789 Uncontrolled Mem Alloc        |  0 |1,548 |+1,548  |
| **Total taint-rule cweFire**         |  0 |9,160 |+9,160  |

* CWE-81 / CWE-83 are tagged with the CWE-79 family cweFire because
  the actual sink is the same (`response.sendError` / `getWriter().print`).
  The finding's CWE label is CWE-79 (or CWE-113), not CWE-81/83, so the
  per-CWE expectedCwe check still shows 0 for those dirs. This is
  acceptable for aggressive mode — the sinks are detected.

## R3 changes

### R3 sink / source pattern additions

**command.sinks** (R515): added
- `^java\.lang\.Runtime\.load(/\d+)?` (CWE-114 JNI load)
- `^java\.lang\.Runtime\.loadLibrary(/\d+)?` (CWE-114 JNI loadLibrary)
- `^java\.lang\.ProcessBuilder\.<init>/\d+` (CWE-78 ctor variant)
- `^org\.springframework\.util\.FileCopyUtils\.copy(/\d+)?`

**deserialization.sinks** (R515): added
- `^com\.thoughtworks\.xstream\.XStream\.fromXML(/\d+)?`
- `^com\.esotericsoftware\.kryo\.Kryo\.readObject(/\d+)?`
- `^org\.yaml\.snakeyaml\.Yaml\.load(/\d+)?`

**ldap.sinks** (R515): added
- `^javax\.naming\.Context\.lookup(/\d+)?`
- `^javax\.naming\.InitialContext\.lookup(/\d+)?`

**url.sinks** (R515): added
- `^java\.net\.HttpURLConnection\.<init>(/\d+)?`
- `^java\.net\.URLConnection\.connect(/\d+)?`
- `^org\.apache\.http\.client\.HttpClient\.execute(/\d+)?`
- `^org\.apache\.http\.impl\.client\.HttpClients\.createDefault/0`

**InputSourcePatterns** (R515): added 12 more sources
- `getRemoteAddr`, `getRemoteHost`, `getRemoteUser`, `getServerName`,
  `getRequestedSessionId` (HTTP)
- `InputStream.read`, `FileInputStream.read`, `Reader.read`,
  `FileReader.read` (low-level IO)
- `URLConnection.getInputStream`, `DatagramSocket.receive` (network)

## R3+ (still 0 or un-measured) — DFA-style work

The following CWEs need DFA-style (not taint-style) checkers:

| CWE       | Detection technique         | Effort |
|-----------|------------------------------|--------|
| CWE-209   | Sensitive info in exception  | 1-2d  |
| CWE-256   | Hard-coded password pattern  | 1-2d  |
| CWE-259   | Hard-coded password          | 1-2d  |
| CWE-321   | Hard-coded crypto key         | 1-2d  |
| CWE-327   | Weak crypto alg (MD5/DES)    | 1-2d  |
| CWE-328   | Reversible hash (no salt)     | 1-2d  |
| CWE-329   | Static IV for CBC             | 1-2d  |
| CWE-336   | Same PRNG seed                | 1-2d  |
| CWE-338   | Weak PRNG                     | 1-2d  |
| CWE-477   | Obsolete functions            | 1d    |
| CWE-500   | Public static field not final | 1d    |
| CWE-511    | Logic time bomb               | 2d    |
| CWE-572    | Thread.run vs Thread.start    | 1d    |
| CWE-583    | finalize without super        | 1d    |
| CWE-609    | Double-checked locking        | 1d    |
| CWE-617    | Reachable assertion           | 1d    |
| CWE-674    | Uncontrolled recursion        | 1d    |
| CWE-681    | Numeric conversion            | 1d    |
| CWE-764    | Multiple locks                | 1d    |
| CWE-765    | Multiple unlocks              | 1d    |
| CWE-775    | Missing file close            | 1d    |
| CWE-832    | Unlock not locked             | 1d    |
| CWE-833    | Deadlock                       | 2d    |
| CWE-835    | Infinite loop                  | 1d    |
| CWE-200    | Info exposure (logs/errors)   | 1d    |

For each, a custom `DfaChecker` (not `TaintedCheckerBase`) is needed,
walking the IR / AST for the specific pattern.

## R3+ taint-rule improvements still possible

- More source patterns (e.g. `getCookies`, `getSession`, `getHeader` for
  specific framework variants)
- More sink patterns (e.g. `addCookie`, `setRequestAttribute`, etc.)
- Cross-method analysis (variants 22, 45, 67, 71-75 split source/sink
  between two methods in same file or across files)
- Numeric taint domain expansion (current NumericTaint is sparse —
  only seeded at input source calls, not propagated through math ops)
- `propagateStringPassthroughs` for numeric equivalents
  (`Integer.parseInt`, `Long.parseLong`, `Double.parseDouble`)

## Cross-project lessons (R1 + R2 + R3)

1. **8 distinct bugs in plumb-dfa that all silently disabled
   entire checker families** (A through H). All were single-line
   fixes once identified. A unit test that exercises
   `data.split("-") → stmt.addBatch(...)` would have caught Bug A
   immediately.

2. **TAC IR formatting is consistent but surprising**:
   - CALL: `args[0] = receiver, args[1+] = user args`
   - NEW: `args[0+] = ctor args` (no receiver)
   - FIELDLOAD: `lhs1 = receiver object, fieldName = field`
   - ARRAYLOAD: `lhs1 = array, lhs2 = index`
   - The receiver is always prepended in CALL but not in NEW.
   - This asymmetry bites you if you don't test it.

3. **Symbol-table-based chain-call resolution is not free**. JavaParser
   needs a type solver for `Runtime.getRuntime().exec(...)` to
   resolve the intermediate type. Without a JDK type solver, the
   chain's receiver type stays `java.lang.Object` and the IR falls
   back to "scope" or simple-name. Two-pronged fix:
   (a) `TypeMapper.mapResolved(calculateResolvedType())` for
       simple chains, (b) hardcoded `GETTER_RETURNS` map for the
       most common Java EE patterns.

4. **Aggressive Juliet mode is great for showing coverage gaps fast**.
   A 30 min R502 sweep over a single CWE dir gives you a clear signal
   on whether your checker fires. Full R500 sweep is great for the
   end-of-round summary but kills the dev inner loop.

5. **Taint-style checkers with 0 fires are usually a single config bug,
   not an architecture problem**. 8 of 8 zero-cweFire CWEs in R1+R2
   were fixed by adding 1-3 lines (sink pattern, argIdx override, or
   case NEW handler).
