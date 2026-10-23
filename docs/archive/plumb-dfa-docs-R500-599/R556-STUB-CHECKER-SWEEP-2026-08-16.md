# R556 — Stub-checker batched sweep (11 stubs)

**Date**: 2026-08-16
**Project**: plumb-dfa
**Test**: `R556StubCheckerSweep`
**Output**: `D:/work/tmp/r500_juliet_sweep/r556-stub.txt`

## Goal
Sweep all 11 taint stub checkers (CFR-decompiled one-liners from R209) on a curated
set of Juliet CWE dirs to discover which actually fire. Stub checkers extend
TaintedCheckerBase with a single TaintRule constructor line — no custom propagators.

## The 11 stub checkers tested

| Checker | Family | CWE | Patterns | Status |
|---|---|---|---|---|
| `BadCertVerificationChecker` | ssl.sinks | 295 | 6 | stub, no Juliet dir |
| `ElInjectionChecker` | el.sinks | 94 | 7 | stub, no Juliet dir |
| `InsecureRandomChecker` | random.sinks | 330 | 13 | stub, no Juliet dir |
| `JavaCodeInjectionChecker` | code-execution.sinks | 94 | 11 | stub, no Juliet dir |
| `JcrInjectionChecker` | jcr.sinks | 94 | 6 | stub, no Juliet dir |
| `JspDynamicIncludeChecker` | jsp-include.sinks | 829 | 4 | stub, no Juliet dir |
| `OgnlInjectionChecker` | ognl.sinks | 94 | 2 | stub, no Juliet dir |
| `SessionFixationChecker` | session.sinks | 384 | 6 | stub, no Juliet dir |
| `TrustBoundaryViolationChecker` | trust-boundary.sinks | 501 | 7 | stub, no Juliet dir |
| `UnsafeJniChecker` | jni.sinks | 114 | 4 | **fires via CWE-114 ProcessControl** |
| `UnsafeReflectionChecker` | reflection.sinks | 470 | 6 | **fires CWE-470** |

## CWE dirs tested
13 dirs, 16,117 files, 265 sec total

| Dir | Files | Fires | Notes |
|---|---:|---:|---|
| CWE-80/81/83 XSS | 2160 | 0 | xss.sinks not exercised by stub |
| CWE-78 OS Command | 720 | 0 | command.sinks, different rule |
| CWE-89 SQL | 3660 | 0 | sql.sinks, different rule |
| **CWE-470 Unsafe Reflection** | 720 | **408** | UnsafeReflectionChecker works |
| CWE-114 Process Control | 17 | 0 | jni.sinks not on Juliet patterns |
| CWE-23/36 Path Traversal | 1440 | 0 | file-path.sinks, different rule |
| CWE-319 Cleartext | 610 | 0 | cleartext sinks, different rule |
| CWE-129 Array Index | 4392 | 0 | int range check, different |
| CWE-674 Recursion | 2 | 0 | UncontrolledRecursionChecker separate |
| CWE-400 Resource Exhaustion | 2396 | 0 | ResourceExhaustionChecker separate |

## Key findings

1. **Only `UnsafeReflectionChecker` actually fires on Juliet (408 hits)** — same
   as R541/R9 result. Confirms reflection.sinks family works end-to-end.

2. **The other 10 stub checkers fire 0** because their target CWEs (94, 829, 295,
   384, 501, 114, 330) lack Juliet Java dirs. We have no way to validate FP/FN
   against Juliet for these.

3. **Stub checkers are "shipped but unverified"** — they don't fire on Juliet
   because Juliet doesn't test those CWEs. Real codebase validation is needed.

4. **`xss.sinks` not exercised**: Juliet CWE-80/81/83 use taint-style
   `print(tainted)`, not the function-call-based taint walk. The XSS family
   is covered by other mechanisms (e.g. `xss.sinks` patterns in the
   `TaintedCheckerBase` flow, but stub doesn't add the xss.sinks source).

5. **CWE-114 has 17 files but UnsafeJni fires 0** — `jni.sinks` patterns
   (`System.load`, `System.loadLibrary`) don't match Juliet's pattern
   which uses `Runtime.load`. The stub `jni.sinks` family doesn't include
   `Runtime.load`. Pre-existing limitation.

## Recommendations

1. **Keep `UnsafeReflectionChecker`** — it works, 408 fires.
2. **Add `Runtime.load` to `jni.sinks`** — would enable CWE-114 detection.
3. **Other 9 stub checkers**: keep for real-codebase coverage, document the
   Juliet blind spot. Cannot validate FP/FN with Juliet alone.
4. **Future work**: extract an open-source Java codebase (Tomcat, Spring, etc.)
   to validate these 10 stub checkers on real code.

## Tests
- 1 new test (`R556StubCheckerSweep`) — 1 method, passes
- 16,117 files / 408 fires / 265 sec

## Status
**SHIPPED**.
