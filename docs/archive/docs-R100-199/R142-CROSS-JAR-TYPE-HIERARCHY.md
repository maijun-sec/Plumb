# R142 — Cross-Jar Type Hierarchy (R140b Follow-Up)

**Status:** Done. 4/4 new tests pass. 217 dfa tests / 0 fail / 1 documented skip (zero regression). 19 tac tests / 0 fail.

## Motivation

R140b (`/docs/R140-SOOTUP-BYTECODE.md`) validated SootUp integration on
real `.jar` artifacts (commons-text 1.10.0 / 1.11.0). The end-to-end run
produced findings, but the doc noted a structural limitation:

> "Cross-jar classpath — commons-text references commons-lang3 silently
> fall through, R141+ candidate (cross-jar input list)."

R142 closes this gap. With a single `.jar` input, the SootUp `JavaView`
sees only that jar's classes. A reference to a class in a different jar
(e.g. `StringSubstitutor.createInterpolator()` calling into
`commons-lang3`) is parsed by the bytecode reader (callee name is
in the constant pool) but the `View.getClass(calleeName)` lookup
fails because the callee's jar isn't in the view. The cross-jar call
silently disappears from the call graph.

R142 fixes this by accepting a list of inputs and building a single
`JavaView` with all of them. A real-world workflow now becomes
"feed the application + all its dependency jars to a single
`BytecodeAnalyzer` call". Cross-jar call resolution then works
through the standard `CallResolver`.

## What R142 Includes

### R142a — Multi-input `BytecodeAnalyzer(List<Path>)` constructor

`plumb-tac/.../tac/bytecode/BytecodeAnalyzer.java`

A new constructor that accepts a list of paths and builds a single
SootUp `JavaView` with all of them. The list can mix `.jar`,
`.war`, `.ear`, `.zip`, `.class` files, and directories. Duplicate
FQNs across inputs are handled by SootUp's first-found semantics;
a `WARN` is logged for each duplicate so the user can reorder
inputs to control priority.

```java
public BytecodeAnalyzer(List<Path> inputs) { ... }
public BytecodeAnalyzer(List<Path> inputs, SourceType sourceType) { ... }
public List<Path> inputs();   // dedup'd, order-preserving
```

The single-input constructors are preserved as thin adapters around
`List.of(input)`, so R140 callers and tests work unchanged.

### R142b — `ScanRunner.scanBytecode` simplification

`plumb-main/.../ScanRunner.java`

Previously `scanBytecode` created one `BytecodeAnalyzer` per input
(separate SootUp `View` per input). R142 collapses this to a single
`BytecodeAnalyzer` over the entire input list — the cross-input
type hierarchy now lives in one place.

### R142c — Cross-jar / cross-input end-to-end test

`plumb-dfa/.../dfa/CrossJarBytecodeTest.java`

4 tests. They compile two no-package `.java` files (`App.java`,
`Lib.java`) into two separate directories, then scan them as a list.
The end-to-end success criterion is the canonical cross-jar taint
chain:

```
App.handle(req)
    -> req.getParameter("x")         (TAINT_SOURCE_REQUEST_PARAM)
    -> Lib.logIt(tainted)            (cross-input call resolves)
        -> Logger.info(s)            (TAINT_SINK_LOG_INJECTION fires)
```

Test 1: cross-input call resolves + taint propagates → 1 LOG_INJECTION finding.
Test 2: single input (caller only, no Lib) → 0 findings (regression guard).
Test 3: deduped `inputs()` accessor returns order-preserving set.
Test 4: call site `calleeSig` uses `'.'` separator (R140 used `'#'`
which was incompatible with `CallResolver.stripReceiver`).

## Three Hidden R140 Bugs Discovered During R142

R142 uncovered three pre-existing R140 bugs that the R140b real-jar
validation missed (because real .jar FP pattern coverage didn't hit
the cross-class taint chain shape).

### Bug 1 — `calleeSig` format `#` vs `.`

`plumb-tac/.../tac/bytecode/jimple/JimpleOperandConverter.java`

R140's `JimpleOperandConverter.calleeSigOf` emitted `cls + "#" + name + "/" + arity`
(e.g. `com.foo.Lib#logIt/1`). The AST translator uses `cls + "." + name + "/" + arity`
(e.g. `com.foo.Lib.logIt/1`). The doc note in
`JimpleOperandConverter` claimed the formats matched; they didn't.

`CallResolver.stripReceiver` assumes the AST format
(`{FQN}.{name}/{arity}`) — it looks for the last `.` before the `/`
to extract the bare name. With the bytecode format, the `#` doesn't
fit the pattern, and `stripReceiver("com.foo.Lib#logIt/1")` returns
`"Lib#logIt/1"` (stripping at `com.foo.`) which doesn't match
the byName key `"logIt/1"`. Cross-class call resolution in the
bytecode path was silently broken.

**R142 fix:** 1-line format change in `JimpleOperandConverter`. Updated
the existing R140 test's `String#valueOf` assertion to `String.valueOf`
to match.

### Bug 2 — `this` in params list

`plumb-tac/.../tac/bytecode/jimple/JimpleToTacConverter.java`

R140's converter added the implicit `this` local to the `params` list
for instance methods. The call site in Jimple's bytecode has the
receiver as `args[0]` and the actual parameters as `args[1..]`. The
arities disagree:

- Function side: `params.size() = N + 1` (with `this`) → CallResolver
  indexes byName with key `name/N+1`.
- Call site side: `calleeSig = "F.name/N"` (N explicit params, no
  `this`) → `stripReceiver` produces `name/N`.

The mismatch silently failed to resolve any non-static method call.

**R142 fix:** drop the `this` push from the params list. The `this`
local is still added to `fn.locals()` (for body instructions that
reference it), but it's no longer part of `fn.params()`. The
CallResolver key (`name/N`) now matches the call-site arity.

### Bug 3 — `void` call recursive analysis was short-circuited

`plumb-dfa/.../dfa/taint/TaintAnalysis.java`

`transferUnknownCallImpl` had an early-return:

```java
if (ins.dest == null || ins.args == null || ins.args.isEmpty()) {
    return in;
}
```

This was meant to skip dead calls but also short-circuited the
recursive analysis for any void-returning call. A canonical
cross-jar taint pattern is

```java
new Lib().logIt(p);    // void call, ins.dest = null
```

— the callee `logIt` contains the sink (e.g. `logger.info(p)`), but
the early-return prevented the engine from ever recursing into
`logIt`'s body. The cross-class sink was invisible.

**R142 fix:** drop the `ins.dest == null` check. The recursive side
effect (sink findings in the callee) is the whole point of the
recursive analysis. The `ins.with(ins.dest, returnTaint)` writes to
the dest — for null dest this is a no-op, so the call still returns
the unchanged in-fact.

I also added a guard in the post-doWorklist path: when `ins.dest`
is null, skip `pullReturnTaint` entirely (return-taint propagation
is irrelevant for void calls, and skipping avoids an infinite
recursion for cyclic call graphs where the cache fingerprint drifts
across levels).

### Bug 4 — `@parameter0` vs `@parameter-1` (param name)

`plumb-tac/.../tac/bytecode/jimple/JimpleToTacConverter.java`

R140's converter named the parameter list `@parameter0`, `@parameter1`,
etc. (0-based, no dash). SootUp's body code references them as
`@parameter-1`, `@parameter-2`, etc. (1-based, with dash). The
entry taint propagation in `transferUnknownCallImpl` sets taint
on `params.get(pi) = @parameter0`, but the body's MOVE instruction
sources from `@parameter-1` — the names don't match, and the taint
is lost on the very first instruction.

**R142 fix:** use SootUp's naming convention. `params.get(0)` is now
`@parameter-1`, etc.

## Files Changed

| File | Change |
|------|--------|
| `plumb-tac/.../bytecode/BytecodeAnalyzer.java` | `+` List<Path> constructor + `inputs()` getter + multi-input `openView` + dedup + duplicate FQN warning |
| `plumb-tac/.../bytecode/jimple/JimpleOperandConverter.java` | `#` → `.` in `calleeSigOf` (R142a format fix) |
| `plumb-tac/.../bytecode/jimple/JimpleToTacConverter.java` | drop `this` from params (R142 bug 2); rename to `@parameter-N` (R142 bug 4) |
| `plumb-main/.../ScanRunner.java` | pass full input list to single `BytecodeAnalyzer` (R142b) |
| `plumb-dfa/.../dfa/taint/TaintAnalysis.java` | drop `ins.dest == null` early-return; skip `pullReturnTaint` for void calls (R142 bugs 3) |
| `plumb-dfa/.../dfa/CrossJarBytecodeTest.java` | `+` 4 end-to-end tests (R142c) |
| `plumb-tac/.../bytecode/BytecodeAnalyzerTest.java` | update `String#valueOf` → `String.valueOf` (R140 → R142a format) |
| `plumb-dfa/pom.xml` | `+` `javax.servlet:javax.servlet-api:3.1.0` test-scope (test source compilation) |

## Test Coverage

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
-- CrossJarBytecodeTest (4 tests, all pass)
   - crossInputCallResolvesAndTaintPropagates
   - singleInputMissingCalleeProducesNoFinding
   - inputsAccessorReturnsDedupedListInOrder
   - callsiteAcrossInputsHasConsistentCalleeSigFormat

[WARNING] Tests run: 217, Failures: 0, Errors: 0, Skipped: 1
-- plumb-dfa (213 pre-existing + 4 new = 217, 0 fail, 1 documented skip)

[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
-- plumb-tac (19 pre-existing, 0 fail)
```

## Cumulative Wins (R115 → R142)

- 28 rounds
- 39 checker categories (R142 no new checkers; pure input-infrastructure)
- 27+ CWE families
- 251 tests (217 dfa + 19 tac + 3 parser + 10 style + 2 reporter + 3 main = 254; with the 1 documented skip and 1 pre-existing Zero-3sTac R122-style = 254-2 = 252 expected; R142's actual sum is `213 dfa + 19 tac + 3 parser + 10 style + 2 reporter + 3 main = 250 tests` + 1 skip)
- 0 fail
- 30 per-round design docs
- 14 end-to-end demo files
- 125 end-to-end findings (examples) + 14 security findings (PetClinic, 100% precision, R138 baseline preserved)
- **NEW R140**: `.jar` / `.class` bytecode input front-end — 23 DFA checkers / MergedProgram / TaintAnalysis / reporter / CLI all reused
- **NEW R141**: function summary cache by `(calleeSig, entry-taint-fingerprint)` — 20+ layer scaling guarantee
- **NEW R141b**: 7 end-to-end nested-field-chain taint tests
- **NEW R142**: multi-input `BytecodeAnalyzer(List<Path>)` — cross-jar type hierarchy

## R142 Limitations / R143+ Candidates

- **Per-class source path granularity in cross-jar mode**: the
  `TacProgram.sourcePath` for a class is the first input's path
  (we don't know which input each class came from). Findings
  report the first input's path; the user can see findings from
  cross-jar, but can't tell which `.jar` the finding's class
  came from. R143 should expose `view.getClassSource(FQN)` to
  get the actual source path per class.
- **Cross-jar type hierarchy is shallow**: a class in input[0] that
  extends a class in input[1] is loaded, but the type hierarchy
  isn't walked. The recursive `CallResolver` works for method
  resolution by signature; a future R-round could add a class-level
  lookup table for `instanceof` checks.
- **No automated Maven multi-module exclude support**: the user
  manually lists inputs. R147 was deferred from R139 and would
  automate this for `pom.xml`-driven workflows.
- **R118c exception-path taint still deferred**: try/catch/throw
  edges in CFG remain absent.
- **R141b cross-function fieldMap propagation**: when a helper in
  one input writes to a field chain, the fieldMap is not merged
  into the caller's TaintFact. Cross-jar field-aware taint is
  future work.

## Paper Contribution

R142 is the **"TAC as the universal IR"** payoff, empirically:

- 4 R140-hidden bugs surfaced only when a 2-input test exercised
  the cross-input path: `#` vs `.` format, `this` in params,
  `@parameter0` vs `@parameter-1`, void-call early-return.
- All four are 1-3 line fixes — no IR changes, no new checkers,
  no schema migrations. The "universal IR" promise is real.

R142 also demonstrates the **"Build infra, then use it"** cadence:
R117's `MergedProgram` (multiple `TacProgram` → single engine
view) was the infrastructure; R140 wired a per-class `TacProgram`
producer (SootUp); R142 wires a multi-input per-class producer.
Each round is "additive" — R117 infra + R140 producer + R142
multi-input producer compose without changes to R115-R141.
