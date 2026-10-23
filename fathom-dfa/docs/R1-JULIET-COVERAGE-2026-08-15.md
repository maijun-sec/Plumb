# Round 1 — Juliet Corpus Coverage Optimization (2026-08-15)

## Goal
Push Juliet CWE recall up aggressively, accepting FP cost. The user
asked for "宁可多报点 FP 也要提升 TP / recall" and continuous 50+ round
iteration. Round 1 is the diagnostic + first batch of fixes.

## Pre-Round-1 baseline (R500 v2, "cha" strategy, 27 checkers)
- 46,181 files processed
- 211,562 total findings (211k mostly cross-CWE noise: CWE-404 resource
  leak + CWE-190 int overflow + CWE-476 null deref)
- CWE_fire (the expected-CWE family per dir):
  - CWE129: 3997
  - CWE190: 5163
  - CWE191: 1827
  - CWE369: 1030
  - CWE404: 16
  - CWE476: 120
  - **All injection / XSS / format-string / LDAP / XPath families: 0**

## Round 1 root cause findings (5 distinct bugs)

### Bug A — call-sig format mismatch
`propagateStringPassthroughs` matched callee sigs like
`java.lang.String#split` (hash separator, no arity) but the TAC actually
emits `java.lang.String.split/1` (dot separator, with arity). Every
passthrough (`split`, `substring`, `trim`, `toLowerCase`, `valueOf`,
`Integer.toString`, `StringBuilder.append`, `String.concat`) was
silently never firing, so `data.split("-")` and string-concat
lowering to `StringBuilder.append` did NOT carry taint.

Fix: change all 14 substring-prefix checks to the
`FQN.method/arity`-prefix form, in
`plumb-dfa/.../taint/TaintedCheckerBase.java`.

### Bug B — source operand on CALL is `args[0]`, not `lhs1`
TAC CALL builder (`plumb-tac/.../ExpressionTranslator.emitCall`)
prepends the receiver to `ins.args`, so the receiver is at `args[0]` and
`ins.lhs1` is `null` for CALL instructions. `propagateStringPassthroughs`
was reading `ins.lhs1` and seeing null. Fix: use `ins.args[0]` for the
primary source operand (and `ins.args[1]` for `append`/`concat`).

### Bug C — sink arg indexing missed the receiver offset
`DEFAULT_SINK_ARG_INDEX=0` was used to read the taint arg. For instance
methods, the receiver is `args[0]`, so the taint-bearing arg is at
`args[javaArgIdx + 1]`. Fix: in `checkSinkForFamily`, add 1 to the
java-arg-idx for instance calls (all sink families are instance
methods in the catalog).

### Bug D — `Runtime.getRuntime().exec(...)` chain-call receiver
`Runtime.getRuntime()` returns a TacVar with type `TacType.UNKNOWN` (the
method-call dest's type wasn't being resolved), so the chained
`.exec(...)` emitted `scope.exec/1` and the `command.sinks` pattern
(`^java\.lang\.Runtime\.exec`) never matched.

Fix in `plumb-tac/.../ExpressionTranslator.translateCall`: read
`mce.calculateResolvedType()` and translate via
`TypeMapper.mapResolved(...)`. Also add 20+ entries to
`TypeMapper.resolveWellKnownFqn` (HttpServletResponse, InitialContext,
DirContext, XPath, EntityManager, etc.) so the resolved type
(`"Runtime"` → `"java.lang.Runtime"`) lands on the FQN anchor.

### Bug E — constructor + static-field receivers
For `new File(...)` and `System.out.format(...)`, the IR emits
`File.<init>/1` and `System.out.format/1` (short class names). Sink
patterns anchored on FQN (`^java\.io\.File\.<init>`) never matched.
Fix: hardcode `GETTER_RETURNS` map for the 16 most common chain-call
getters (getWriter, getOutputStream, getRequest, getResponse, etc.) and
hardcode the `System.out` / `System.err` static-field receiver to
`java.lang.System`. Use `TypeMapper.resolveWellKnownFqn` for the
constructor signature so `new File(...)` becomes
`java.io.File.<init>/1`.

## Sink-pattern additions (sink-families.yaml)

- `sql.sinks`: +PreparedStatement.addBatch, +setString, +setObject,
  +setInt/setLong/setNull, +Connection.prepareStatement,
  +EntityManager.createNamedQuery
- `command.sinks`: +ProcessBuilder.start
- `file-path.sinks`: rewritten as FQN ctor patterns; +Scanner.<init>,
  +PrintWriter/BufferedWriter.<init>, +Files.write/copy/move/delete
- `format-string.sinks`: +PrintStream.format, +PrintWriter.printf,
  +Formatter.format, +System.out.format/printf, +System.err.format/printf
- `xpath.sinks`: +XPath.compile, +XPathExpression.evaluate,
  +XPathAPI.selectNodeList
- `header.sinks`: +sendError, +setStatus, +encodeURL,
  +encodeRedirectURL, +ResponseEntity.<init>, +HttpHeaders.set/add
- `xss.sinks`: NEW family — PrintWriter/PrintStream
  print/println/write/append/format, +HttpServletResponse.getWriter,
  +String.replaceAll
- `open-redirect.sinks`: rewritten with FQN anchors
- `ldap.sinks`: +DirContext.search (FQN), +LdapContext.search,
  +LdapTemplate.find
- `url.sinks`: cleaned up

## New checkers (R506-R511)

- `XssInjectionChecker` (CWE-79, xss.sinks)
- `XPathInjectionChecker` (CWE-643, xpath.sinks)
- `HttpResponseSplittingChecker` (CWE-113, header.sinks) — override
  `sinkArgumentIndex=1` for setHeader/addHeader
- `FormatStringChecker` (CWE-134, format-string.sinks)
- `LdapInjectionChecker` already existed; rewritten to override
  `sinkArgumentIndex=1` (search has 3 args, taint at arg 1)

## Per-CWE coverage improvement (R507 + R502, post Round 1)

| CWE dir                              | v2 cweFire | v3 cweFire |  Δ    |
|--------------------------------------|-----------:|-----------:|------:|
| CWE89 SQL Injection                  |          0 |      1,915 | +1,915|
| CWE78 OS Command Injection           |          0 |        359 |   +359|
| CWE23 Relative Path Traversal        |          0 |        718 |   +718|
| CWE36 Absolute Path Traversal        |          0 |        718 |   +718|
| CWE80 XSS                            |          0 |        496 |   +496|
| CWE90 LDAP Injection                 |          0 |        359 |   +359|
| CWE113 HTTP Response Splitting       |          0 |        752 |   +752|
| CWE643 Xpath Injection               |          0 |        383 |   +383|
| CWE134 Uncontrolled Format String    |          0 |          ? | running|

## Module test status

- 1,500+ tests, all PASS, no regression
- Module test counts unchanged from Step 7 SHIPPED (516 plumb-dfa
  + 50 plumb-main + 54 plumb-ai + 941 in other modules)

## What is still broken / Round 2 candidates

- CWE601 Open Redirect: 0 cweFire — patterns are anchored, but
  `sendRedirect` may not be reaching the chain properly. TBD.
- CWE789 Uncontrolled MemAlloc: 0 cweFire — needs a dedicated
  MemAllocChecker with `new byte[tainted]` / `new StringBuffer(tainted)`
  patterns.
- CWE134 Format String: running in R500 v3, expected to fire.
- Cross-CWE noise: 11k+ CWE-404 resource-leak findings across
  injection-CWE dirs is acceptable for aggressive mode (Juliet's
  resource-leak template triggers any file with an unclosed Socket /
  Reader).
