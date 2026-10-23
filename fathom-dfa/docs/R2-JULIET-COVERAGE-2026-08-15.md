# R1 + R2 — Juliet Corpus Coverage Optimization (2026-08-15)

## Goal
Push Juliet CWE recall up aggressively, accepting FP cost. The user
asked for "宁可多报点 FP 也要提升 TP / recall" and continuous 50+ round
iteration. This doc covers R1 + R2 (5 core bugs + 6 new checkers +
GETTER_RETURNS hardcoded map + NEW instruction taint flow).

## R1 + R2 per-CWE cweFire improvement

| CWE dir                              | v2 cweFire | R1+R2 cweFire |  Δ      |
|--------------------------------------|-----------:|--------------:|--------:|
| CWE89 SQL Injection                  |          0 |         2,030 |  +2,030 |
| CWE78 OS Command Injection           |          0 |           359 |    +359 |
| CWE23 Relative Path Traversal        |          0 |           782 |    +782 |
| CWE36 Absolute Path Traversal        |          0 |           782 |    +782 |
| CWE80 XSS                            |          0 |           560 |    +560 |
| CWE81 XSS Error Message              |          0 |             0 |     +0* |
| CWE83 XSS Attribute                  |          0 |             ? |     ?   |
| CWE90 LDAP Injection                 |          0 |           391 |    +391 |
| CWE113 HTTP Response Splitting       |          0 |           796 |    +796 |
| CWE134 Uncontrolled Format String    |          0 |           634 |    +634 |
| CWE601 Open Redirect                 |          0 |           280 |    +280 |
| CWE643 Xpath Injection               |          0 |           406 |    +406 |
| CWE789 Uncontrolled Mem Alloc        |          0 |         1,548 |  +1,548 |

* CWE81 XSS Error Message: the actual sink in those files
  is `throw new ServletException(e)` or `getWriter().print(...)` —
  variants of header.sinks that get caught. CWE-113 fires but
  CWE-81 expectedCwe is 0 (it expects CWE-79 or CWE-81 family).

**Total R1+R2 taint-rule cweFire improvement: 0 → 8,568 findings across
12 CWE families.**

## Root-cause fixes (R1 + R2)

### Bug A — call-sig format mismatch
`propagateStringPassthroughs` matched callee sigs like
`java.lang.String#split` (hash separator, no arity) but the TAC actually
emits `java.lang.String.split/1` (dot separator, with arity). Every
passthrough (`split`, `substring`, `trim`, `toLowerCase`, `valueOf`,
`Integer.toString`, `StringBuilder.append`, `String.concat`) was
silently never firing.

Fix: change all 14 substring-prefix checks to the
`FQN.method/arity`-prefix form, in
`plumb-dfa/.../taint/TaintedCheckerBase.java`.

### Bug B — source operand on CALL is `args[0]`, not `lhs1`
TAC CALL builder (`plumb-tac/.../ExpressionTranslator.emitCall`)
prepends the receiver to `ins.args`, so the receiver is at `args[0]`
and `ins.lhs1` is `null` for CALL instructions. `propagateStringPassthroughs`
was reading `ins.lhs1` and seeing null. Fix: use `ins.args[0]` for the
primary source operand (and `ins.args[1]` for `append`/`concat`).

### Bug C — sink arg indexing missed the receiver offset
`DEFAULT_SINK_ARG_INDEX=0` was used to read the taint arg. For
instance methods, the receiver is `args[0]`, so the taint-bearing
arg is at `args[javaArgIdx + 1]`. Fix: in `checkSinkForFamily`, add 1
to the java-arg-idx for instance calls (all sink families are
instance methods in the catalog).

### Bug D — `Runtime.getRuntime().exec(...)` chain-call receiver
`Runtime.getRuntime()` returned a TacVar with type `TacType.UNKNOWN`
(the method-call dest's type wasn't being resolved), so the chained
`.exec(...)` emitted `scope.exec/1` and the `command.sinks` pattern
(`^java\.lang\.Runtime\.exec`) never matched.

Fix in `plumb-tac/.../ExpressionTranslator.translateCall`: read
`mce.calculateResolvedType()` and translate via
`TypeMapper.mapResolved(...)`. Add 20+ entries to
`TypeMapper.resolveWellKnownFqn` (HttpServletResponse, InitialContext,
DirContext, XPath, EntityManager, etc.) so the resolved type
(`"Runtime"` → `"java.lang.Runtime"`) lands on the FQN anchor.

### Bug E — constructor + static-field receivers
For `new File(...)` and `System.out.format(...)`, the IR emitted
`File.<init>/1` and `System.out.format/1` (short class names). Sink
patterns anchored on FQN (`^java\.io\.File\.<init>`) never matched.
Fix: hardcode `GETTER_RETURNS` map for the 16 most common chain-call
getters (getWriter, getOutputStream, getRequest, getResponse, etc.)
and hardcode the `System.out` / `System.err` static-field receiver
to `java.lang.System`. Use `TypeMapper.resolveWellKnownFqn` for the
constructor signature so `new File(...)` becomes
`java.io.File.<init>/1`.

### Bug F (R2) — NEW instructions never ran taint flow
`TaintedCheckerBase.checkInstruction` only had `case CALL` for the
sink / source / passthrough pipeline. `case NEW -> {}` was missing.
This silently skipped every ctor sink (mem-alloc.sinks, file-path.sinks
for `new FileInputStream`, sql.sinks for `new PreparedStatement`).

Fix: add `case NEW -> { checkSinkAtNew(...) }` plus a
`checkSinkForCtor` helper. The args list is the raw ctor arg list
(no receiver prepended), so the `+1` offset is SKIPPED for NEW.

### Bug G (R2) — UncontrolledMemAllocChecker used wrong taint domain
The initial version of UncontrolledMemAllocChecker used
`TaintDomain.StringTaint.INSTANCE` with the
`plumb.taint.untrusted-source` source kind. But the actual Juliet
CWE-789 pattern is `Integer.parseInt(readLine()) → int data →
new HashMap(int)`. The numeric taint comes from the IntOverflow
numeric propagation path (`plumb.numeric.overflow-hazard`).

Fix: use `TaintDomain.NumericTaint.INSTANCE` and the
`plumb.numeric.overflow-hazard` source kind (sharing the
IntOverflowChecker's numeric taint propagation path).

### Bug H (R2) — `sinkArgumentIndex` not overridden for multi-arg sinks
For `DirContext.search("", filter, controls)` the taint is at Java
arg 1 (the filter), not 0 (the base DN). For
`HttpServletResponse.setHeader(name, value)` taint is at Java arg 1
(the value). The default `sinkArgumentIndex=0` would pick a clean
arg and silently never fire.

Fix: override `sinkArgumentIndex` in `LdapInjectionChecker` (returns 1)
and `HttpResponseSplittingChecker` (returns 1). Documented in the
class javadoc.

## New checkers (R1 + R2)

- `XssInjectionChecker` (CWE-79, xss.sinks)
- `XPathInjectionChecker` (CWE-643, xpath.sinks)
- `HttpResponseSplittingChecker` (CWE-113, header.sinks) — argIdx=1
- `FormatStringChecker` (CWE-134, format-string.sinks)
- `OpenRedirectChecker` (CWE-601, open-redirect.sinks)
- `UncontrolledMemAllocChecker` (CWE-789, mem-alloc.sinks) — numeric
- `LdapInjectionChecker` rewritten — argIdx=1 for search

## Sink-pattern additions (sink-families.yaml)

- `sql.sinks`: +PreparedStatement.addBatch/setString/setObject/setInt/Long/Null, +Connection.prepareStatement, +EntityManager.createNamedQuery
- `command.sinks`: +ProcessBuilder.start
- `file-path.sinks`: all FQN, +Scanner/PrintWriter/BufferedWriter.<init>, +Files.write/copy/move/delete
- `format-string.sinks`: +PrintStream/PrintWriter.format, +Formatter, +System.out/err.format/printf
- `xpath.sinks`: +XPath.compile, +XPathExpression.evaluate, +XPathAPI.selectNodeList
- `header.sinks`: +sendError, +setStatus, +encodeURL, +encodeRedirectURL, +ResponseEntity, +HttpHeaders.set/add
- `xss.sinks`: NEW family — PrintWriter/PrintStream print/println/write/append/format, +HttpServletResponse.getWriter, +String.replaceAll
- `open-redirect.sinks`: all FQN
- `ldap.sinks`: +DirContext.search (FQN), +LdapContext.search, +LdapTemplate.find
- `url.sinks`: cleaned up
- `mem-alloc.sinks`: NEW family — StringBuffer/ByteArrayOutputStream/etc. ctor patterns

## InputSourcePatterns (R2 — 514)
Added 20 HTTP request source patterns: `getParameter`, `getHeader`,
`getQueryString`, `getCookies`, `getRequestURI`, `getRequestURL`,
`getPathInfo`, `getMethod`, `getInputStream`, `getReader`, etc. These
match as substrings on the TAC callee sig, so e.g. `getParameter` is
contained in
`javax.servlet.http.HttpServletRequest.getParameter/1`.

## R3+ candidates (still 0 cweFire or unmeasured)

- CWE83 XSS Attribute — not yet measured
- CWE-477 Obsolete Functions — DFA-style (Runtime.getRuntime exec etc.)
- CWE-500 Public Static Field Not Final — DFA-style
- CWE-572 Call to Thread run Instead of start — DFA-style
- CWE-609 Double-Checked Locking — DFA-style
- CWE-617 Reachable Assertion — DFA-style
- CWE-674 Uncontrolled Recursion — DFA-style
- CWE-681 Incorrect Conversion Between Numeric Types — DFA-style
- CWE-764/765 Multiple Locks/Unlocks — DFA-style
- CWE-832 Unlock Not Locked — DFA-style
- CWE-835 Infinite Loop — DFA-style
- CWE-833 Deadlock — DFA-style

The above are DFA-style checkers, not taint-style. R3+ would need to
implement these as separate `DfaChecker` subclasses (not
TaintedCheckerBase) because they look at control-flow / locking /
numeric-casting patterns rather than source-to-sink taint.

## Test status

- 1500+ tests, all PASS, no regression
- New tests added: R503 (CWE89 trace), R504 (CWE78 trace), R505
  (callee-sig dump), R507 (all-checkers per CWE), R508 (XSS trace),
  R511 (format-string trace), R516 (mem-alloc trace)

## Cross-project lessons (R1 + R2)

1. **TAC CALL puts receiver at args[0], not lhs1** — same lesson
   appears in 3 places (passthrough source, sink arg, mem-alloc
   arg). Always remember.

2. **TAC IR callee-sig format is FQN.method/arity with "." separator
   and `/N` arity suffix**. Patterns using `#method` (JVM bytecode
   format) never match. Patterns must accept both `(/\\d+)?` for
   arity-optional flexibility and `\\.` for class-method separator.

3. **Symbol solver in plumb-tac is not wired to JDK types**. Chain
   calls like `response.getWriter().println(...)` lose type info
   for the intermediate result. Hardcode the GETTER_RETURNS map
   for the 16 most common getters. Static fields
   (`System.out`/`System.err`) need their own hardcode.

4. **`case NEW` is missing from taint flow**. `TaintedCheckerBase`
   has cases for `MOVE`, `BINOP`, `FIELDLOAD`, `ARRAYLOAD`,
   `UNARYOP`, `CALL`, `RETURN`, `CATCH_HANDLER`, `THROW`, but not
   `NEW`. Every ctor-based sink (mem-alloc.sinks, file-path.sinks
   for `new FileInputStream`, sql.sinks for `new PreparedStatement`)
   was silently not firing. Add `case NEW -> checkSinkAtNew(...)`
   with NO +1 receiver offset (ctor args are raw).

5. **Multi-arg sinks need `sinkArgumentIndex` overrides**. Default
   of 0 is wrong for `search(base, filter, controls)` or
   `setHeader(name, value)`. The taint is at the *value* position,
   not the *name* position. Each checker family must override.

6. **String taint vs Numeric taint domain**. `Integer.parseInt(s)`
   is a numeric sink, not a string sink. CWE-789 (uncontrolled
   memory allocation) needs the IntOverflowChecker's numeric
   taint propagation path (`plumb.numeric.overflow-hazard`), not
   the standard `plumb.taint.untrusted-source`. Sharing the
   source kind is the cleanest way to share the taint path.

7. **Aggressive mode Juliet coverage = accept cross-CWE noise**.
   Each CWE dir generates 2k+ cross-CWE noise (CWE-404 resource
   leak on every CWE dir with Socket/Reader). This is acceptable
   for aggressive mode where the user wants maximum recall.
