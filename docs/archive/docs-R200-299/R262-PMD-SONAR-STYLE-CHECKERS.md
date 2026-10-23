# R262 — 5 PMD / SonarQube / SpotBugs style checkers

## Goal

Increment the PMD / SonarQube / SpotBugs-style rule
coverage by 5 rules, per the user's standing
directive (delivered in summary form: total rules
per tool, Plumb-supported count, pending count).
R262 ships 5 style checkers that complement the
R148f / R254b / R251-R258 rounds:

1. **EmptyCatchBlockChecker** (CWE-390) — a
   `catch (E e) {}` silently swallows the
   exception. Detected via the R261
   `CATCH_HANDLER` IR + try-region structure.
2. **LiteralsFirstInComparisonsChecker** (CWE-595)
   — `x.equals(constant)` should be
   `constant.equals(x)` to avoid NPE on
   `x = null`. PMD / SonarQube S1132.
3. **UnnecessaryBoxingChecker** (CWE-1164) —
   `new Integer(5)` instead of
   `Integer.valueOf(5)` or autoboxing.
   PMD UnnecessaryBoxing.
4. **MissingSerialVersionUIDChecker** (CWE-758) —
   `Serializable` class without
   `serialVersionUID`. PMD MissingSerialVersionUID.
   Requires R149 classToParent map to track
   `implements Serializable` (extended in R262 to
   walk the `implementedTypes` list as well as
   `extendedTypes`).
5. **UseTryWithResourcesChecker** (CWE-400) —
   `try { x = new Resource(); ... } finally {
   x.close(); }` should be try-with-resources.
   PMD UseTryWithResources. Detected via the R261
   `CATCH_HANDLER` / `FINALLY_ENTER` IR + the
   try-region structure.

## Tool coverage summary (post-R262)

| Tool          | Total rules | Plumb covered | Pending | Coverage |
|---------------|-------------|---------------|---------|----------|
| PMD           | 318         | ~36           | ~282    | ~11.3%   |
| SonarQube     | 706         | ~28           | ~678    | ~4.0%    |
| SpotBugs      | 518         | ~28           | ~490    | ~5.4%    |
| Coverity      | 255+        | ~6            | ~249    | ~2.4%    |
| **Combined**  | **~1797**   | **~98**       | **~1699** | **~5.5%** |

The Plumb-covered count is the union of unique
rule IDs in `plumb-dfa` (66 rules
post-R262) and `plumb-dfa` legacy (32 checkers),
where the legacy checkers often have a 1:1
correspondence with PMD / SonarQube / SpotBugs
rules. R262 added 5 unique rule IDs to the
plumb-dfa inventory.

## Decisions

1. **Each R262 checker is a stand-alone
   `TaintedCheckerBase` subclass** with a 1-rule
   `TaintRule`. The R215 framework was designed
   for general dataflow categories, and a style
   check that doesn't need a flow category
   (EmptyCatchBlock, LiteralsFirstInComparisons)
   works fine with `PointerTaint` /
   `BuiltinFactKinds.MAY_BE_NULL` as the
   rule-source-kind placeholder — the actual
   matching is done in the
   `checkInstruction` override, not in the
   generic taint machinery. This keeps the R262
   additions a small, isolated patch.

2. **R261 IR integration for catch / finally
   checks.** EmptyCatchBlockChecker walks the
   function's `CATCH_HANDLER` instructions and
   looks for an empty body between the handler
   and the next `CATCH_HANDLER` /
   `FINALLY_ENTER` / function-exit terminator.
   UseTryWithResourcesChecker walks the
   `tryRegions()` list and fires when a `NEW`
   in the try body matches a `.close()` call in
   the corresponding `finally` range. Both
   checks require R261 IR; without the
   `CATCH_HANDLER` / `FINALLY_ENTER` opcodes and
   the `tryRegions()` list, the data flow
   wouldn't see catch / finally structure at
   all.

3. **R149 extension to walk `implements`.**
   MissingSerialVersionUIDChecker uses the
   R149 `classToParent` map to detect
   `implements Serializable`. Pre-R262, the
   map only walked the `extends` clause; R262
   extends `TacTranslator.translateTypeWithPrefix`
   to also walk `getImplementedTypes()` and
   register each interface as a synthetic
   parent. The check then walks the chain
   (depth-bounded at 32 hops) looking for a
   parent named `Serializable` or
   `java.io.Serializable`. The depth bound
   guards against the (theoretical) class-
   hierarchy cycle.

4. **Style severity is LOW for non-correctness
   issues.** EmptyCatchBlock is MEDIUM (it's
   a real anti-pattern that can hide bugs);
   MissingSerialVersionUID is MEDIUM (it's a
   real deserialisation hazard); the other
   three are LOW. This matches PMD / SonarQube's
   own severity defaults for the corresponding
   rules.

## Implementation

```
plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/checker/
  EmptyCatchBlockChecker.java           (R262 new, 4823 bytes)
  LiteralsFirstInComparisonsChecker.java (R262 new, 4307 bytes)
  UnnecessaryBoxingChecker.java          (R262 new, 4382 bytes)
  MissingSerialVersionUIDChecker.java   (R262 new, 4080 bytes)
  UseTryWithResourcesChecker.java        (R262 new, 5258 bytes)

plumb-tac/src/main/java/org/plumb/tac/translate/
  TacTranslator.java                    (R262 +18 lines, walks
                                         getImplementedTypes() and
                                         registers each interface
                                         as a synthetic class
                                         parent so MissingSerialVersionUIDChecker
                                         can find the Serializable
                                         parent.)

plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/checker/
  R262PMDStyleCheckersTest.java         (R262 new, 11269 bytes,
                                         7 tests covering all 5
                                         checkers + their
                                         negative cases.)
```

## Test results

- `plumb-tac`: 37 tests, 0 fail, 0 err, 0 skip.
- `plumb-dfa`: 514 tests, 0 fail, 0 err, 0 skip
  (498 base + 4 R261 catch-flow + 3 R261 Juliet + 7 R262
  PMD + 2 from earlier rounds).
- 0 regressions across the existing 60+ R-rounds.

## Innovation

1. **R261 IR consumers.** R262 is the first
   round that consumes the R261 try-region
   structure. The pattern (R261 producer
   → R262 consumer) is reusable for future
   checkers: any style / correctness rule
   that needs to see catch / finally structure
   can now subclass `TaintedCheckerBase` and
   read `fn.tryRegions()` instead of trying
   to reverse-engineer the structure from a
   linear instruction list.
2. **R149 classToParent extension.** Walking
   `implements` in the same flat map as
   `extends` lets a checker answer "is class X
   a Y?" in O(depth) without a separate
   interface-tracking pass. The depth-bounded
   walk is safe against the (theoretical)
   class-hierarchy cycle; in practice the
   translator never produces a cycle because
   it only registers direct parents.
3. **Rule source-kind as placeholder.** Style
   checks don't carry taint, so the
   `TaintedCheckerBase` machinery is a
   heavyweight harness for the rule logic.
   The R215 framework's design — that the
   `TaintRule` is just a "config record" and
   the checker can override
   `checkInstruction` for the actual logic —
   accommodates this without contorting
   either side. We use
   `BuiltinFactKinds.MAY_BE_NULL` as the
   rule's source-kind even though no MAY_BE_NULL
   fact ever participates, because the rule
   shape (ruleId / cwe / severity) is what
   reaches `Finding`.

## Pitfalls

1. **R149 classToParent doesn't see
   `implements`.** The first version of
   MissingSerialVersionUIDChecker tried to
   read `fn.classAnnotations().contains("Serializable")`,
   but `classAnnotations` only carries
   `@Annotation` names, not
   `implements` clauses. R262 fixes this by
   extending `TacTranslator.translateTypeWithPrefix`
   to walk `getImplementedTypes()` and
   register each interface as a synthetic
   parent. The fix is one block in
   `translateTypeWithPrefix` and a
   depth-bounded walk in the checker.

2. **`Class#method` vs `receiver.method/arity`
   callee signatures.** The R262
   `LiteralsFirstInComparisonsChecker`
   originally matched
   `endsWith("#equals") || endsWith(".equals")`,
   but the AST translator lowers
   `x.equals("foo")` to a CALL with
   `calleeSig = "x.equals/1"`, which ends
   with `/1` not `.equals`. The fix is to
   match `contains("#equals") ||
   contains(".equals/")`, which covers both
   the CHA-resolved and unresolved callee
   signature shapes.

3. **Fresh program discards `classToParent`.**
   The first version of the
   `missingSerialVersionUID` test called
   `new TacProgram(...)` and added the
   function to it, which produced a program
   with an empty `classToParent` map. The
   fix is to use the original program that
   the translator returned, which still has
   the populated `classToParent` (the
   map is filled during `translateTypeWithPrefix`,
   not during function emission). The test
   helper `programOf(src)` returns the
   original program so checkers that consult
   `program.classToParent()` see the populated
   map.

4. **Console DEBUG prints left over.** Two
   iterations of the `missingSerialVersionUID`
   test left a `System.out.println("DEBUG
   ...")` in the production checker. The
   final patch removes both prints; the
   surefire log no longer carries the
   diagnostic noise.

## Cumulative (post-R262, 64 round, 1 rolled back)

- 24 metric wins + 2 validations + 2 reports + 1 R261 doc
  + 1 R262 doc (this one)
- 53+ R-docs cumulative, ~580KB (R262 ~10KB)
- 21 R-round tests (R261 + 17: 7 plumb-tac IR + 6
  plumb-tac CFG + 4 plumb-dfa catch-flow +
  3 plumb-dfa Juliet integration − 6 from
  R260 baseline + R262 + 7 = 21 change)
  0 regressions across the existing 60+ R-rounds.
- 5 new rule IDs in plumb-dfa: 61 → 66.
- New tool coverage: ~5.4% of the union
  (PMD + SonarQube + SpotBugs + Coverity).
- New file: `plumb-tac/src/test/java/org/plumb/tac/ir/TryCatchFinallyCfgTest.java`
  (R261, 6 tests).
- New file: `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/R261CatchFlowTest.java`
  (R261, 4 tests).
- New file: `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R261JulietTryCatchSweepTest.java`
  (R261, 3 tests).
- New file: `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/checker/R262PMDStyleCheckersTest.java`
  (R262, 7 tests).
- New file: `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/checker/EmptyCatchBlockChecker.java`
  (R262, 4823 bytes).
- New file: `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/checker/LiteralsFirstInComparisonsChecker.java`
  (R262, 4307 bytes).
- New file: `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/checker/UnnecessaryBoxingChecker.java`
  (R262, 4382 bytes).
- New file: `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/checker/MissingSerialVersionUIDChecker.java`
  (R262, 4080 bytes).
- New file: `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/checker/UseTryWithResourcesChecker.java`
  (R262, 5258 bytes).
- Modified: `plumb-tac/.../TacTranslator.java`
  (R262, +18 lines: walk `getImplementedTypes()`
  and register each interface as a synthetic
  parent).

## R263+ follow-up

- R263 — SootUp `finally` block support (read
  subroutine info from `Body.getTraps()` or
  model `jsr` / `ret`).
- R264 — cross-function throw taint propagation
  (e.g. a callee throws an exception constructed
  from a parameter; the catch block in the
  caller should see the parameter's taint).
- R265 — try-with-resources IR expansion (the
  R261 partial already emits a
  `ResourceLeakChecker`-friendly IR; R265 adds
  the explicit `RESOURCE_OPEN` /
  `RESOURCE_CLOSE` instructions).
- R266+ — 5-8 more PMD / SonarQube /
  SpotBugs style checkers (per-tool
  coverage target: bring each tool's
  Plumb-covered count above 10% by R270).
