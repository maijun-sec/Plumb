# Engine Gap Analysis: What We Still Lack, and the Worklist

[English](../README.md) | [中文](../zh/tech/ENGINE-GAP-ANALYSIS.md)

> Status: measured audit of the engine as it stands
> Scope: the data-flow engine's **precision and completeness** — not the rule catalogue's breadth
> Method: every claim below is a **measured** property of this repository, cited to `file:line`.

---

## 0. Conclusion First

**The rule catalogue is not the bottleneck. The engine's precision is.**

Two measurements settle this:

| Measurement | Value | Meaning |
|---|---|---|
| Sink families in the runtime catalogue | 43 | 33 sink families + 10 source families |
| Pattern entries | 2 466 | with 2 065 explicit argument-position declarations |
| Taint rules bound by shipped checkers | 55 | each binding a family and a CWE |
| **Precision features declared in configuration but never consumed** | **6** | see §1 |

The last row is the finding that matters. The engine **declares** path sensitivity, k-CFA context
depth, summary-cache bounds and reverse-walk depth in its configuration, and **no analysis code
reads any of them**. A user who sets `path_sensitivity: full` and reads the comment describing
three precision tiers is describing an engine that does not have them.

That is worse than a missing feature. A missing feature is a known boundary; a declared-but-inert
knob is a false promise, and it is the kind of gap that survives for years because nothing fails
when it is wrong.

**The worklist below is ordered by "how much precision per unit of work".** Items 1–4 are
deliberately cheap; item 5 is the expensive one and is broken into stages.

---

## 1. The inert configuration: declared but never consumed

Each row was verified by grepping every `src/main` consumer in the repository. "Consumers" means
a call site that reaches analysis behaviour, not a field that is stored and read back.

| # | Declared at | Claimed meaning | Actual consumers | Status |
|---|---|---|---|---|
| 1 | `plumb-dfa/src/main/resources/tuning.yaml:28` | `full` / `windowed` / `none` path condition | **0** | **inert** |
| 2 | `plumb-dfa/src/main/resources/tuning.yaml:14` | k-CFA depth (0/1/2/3) | **0** | **inert** |
| 3 | `plumb-dfa/src/main/resources/tuning.yaml:16` | summary cache size | **0** | **inert** |
| 4 | `plumb-dfa/src/main/resources/tuning.yaml:17` | reverse-walk depth | **0** | **inert** — the class it names does not exist |
| 5 | `plumb-dfa/src/main/resources/checkers.yaml` (per checker) | per-checker context depth | **0** | **inert** |
| 6 | `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java:4123` | path condition for a sink | declaration + 2 call sites, **0 overrides** | **never fed** |

Row 6 is the sharpest one. The hook is complete and correct: `composePathCondition`
(`TaintedCheckerBase.java:607`) merges the local predicate with callee-supplied fragments, the
bridge is consulted at `TaintedCheckerBase.java:3973`, and an `UNSAT` verdict suppresses the
finding. **The entire machinery is present and the input is always `null`.**

Consequence: the solver is reachable for exactly **one** family of checkers — the boundary
checkers, which build their own conjunction (`checkers/boundary/BoundaryConstraintChecker.java:826`).
The 55 taint rules, which are where the false positives actually live, **never consult the
solver at all**.

### 1.1 Two more dead structures in the same shape

Both of these were dead structures at the time this section was written, and both are now wired
(§2.3 for the points-to operation, §2.4 for the access-path store). They are kept here because the
shape they had is the point: **a declared capability with no producer reads exactly like a working
one.**

- `plumb-dfa/src/main/java/org/plumb/dfa/taint/AccessPathTaintStore.java` — a path-keyed taint
  store for `TacAccessPath` (`plumb-tac/src/main/java/org/plumb/tac/ir/TacAccessPath.java`).
  It had **zero consumers outside its own file**, and the chain was broken at **both** ends: a
  whole-tree count of `TacAccessPath` outside its own definition found only this store and the
  IR's own test — **no engine code constructed a path at all**. So it was not a store waiting for
  a reader; it was a store and a vocabulary waiting for a **producer**. §2.4 adds both.
- `plumb-dfa/src/main/java/org/plumb/dfa/summary/Operation.java:259` —
  `PointsToPropagate(allocSites)`. **Zero producers, zero consumers.** The points-to solver
  computes heap objects, and the heap model never reaches the taint lattice. §2.3 connects it.

### 1.2 The honest boundary of the current analysis

Stated plainly, so nothing downstream has to guess:

- The analysis is **flow-sensitive** — instructions are visited in BB-RPO order, forward inside a
  block (`plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:94`).
- The analysis is **not path-sensitive** — there is no CFG join and no lattice meet. One forward
  pass per function (`DfaEngine.java:1738-1743`), a flat fact map, no re-iteration.
- The analysis is **call-string context-sensitive at the call site, and context-free inside the
  callee** — the callee's own facts are computed once and keyed by plain signature
  (`plumb-dfa/src/main/java/org/plumb/dfa/summary/FunctionSummaryCache.java:77`); the caller's side
  projects those facts through the call site's parameter binding, keyed by
  `SummaryKey(signature, callString)` (`FunctionSummaryCache.java:90`). The call string is the
  caller's signature and the instruction's index inside the caller, so two call sites of one callee
  no longer share one answer — see [§2.2a](#22a-g8-the-summary-is-only-true-relative-to-the-call-site).
- The analysis is **object-sensitive for FIELD taint, and not for scalar taint** — a field's taint is
  recorded under the receiving object's allocation identity (`AccessPathTaintStore`,
  `TaintedCheckerBase.java:3166`), so a field written on one instance is not read back on another.
  Scalar taint is still attached to a `TacVar`, never to an allocation site, and the points-to
  solver's results reach the taint lattice only through the two chains in §2.3 and §2.4 — never as
  a direct sharpening of a scalar.

---

## 2. The optimisation worklist

Every item states the **acceptance test** that closes it. An item is only closed when a test
fails before the change and passes after.

### Tier 1 — cheap, and they remove false promises

| ID | Work | Acceptance | Status |
|---|---|---|---|
| **G1** | Make `path_sensitivity` mean something: it gates the source-to-sink reachability check. `none` disables it, `full` enables it. | A test with `path_sensitivity: none` reports a finding that `full` suppresses. | **done** |
| **G2** | Enforce `summary_cache_size`: the cache refuses a summary beyond the bound and says so, instead of growing without limit. | A test that fills the cache past the bound observes the refusal and its count. | **done** |
| **G3** | Enforce `context_depth`: zero switches the cross-function summary folding off, so the analysis stays inside one function. | A test observes a cross-function flow reported at depth 1 and absent at depth 0. | **done** |
| **G4** | Stop claiming a `reverse_walk_max_depth` that nothing reads. | The configuration file no longer names a class that does not exist. | **done** |
| **G5** | Report the summary cache's hit/miss ratio and the refusal count in the analysis summary. | The report contains the number. | **done** |

G1–G4 are not features. They are the removal of a lie. **They are listed first because a tool
that says "path sensitivity: full" and then behaves as "none" will lose a user's trust in every
other number it prints.**

G3 is deliberately narrower than the name suggests, and the configuration file says so. The
engine's summary is itself a fold -- a function's summary holds the facts its own callees
contributed -- so a chain of calls accumulates into one summary and the depth of the chain is
not separately bounded. The setting therefore distinguishes "no cross-function analysis" from
"there is cross-function analysis". It does not yet distinguish a k-CFA depth, which is G8.

### G5 is a counter nobody read

The cache's `hits()` / `misses()` counters have existed since the cache was written, and the
cache's own javadoc said "the post-run log reads these via `hits()` / `misses()`". No call
site existed. The counters were maintained, correct, and invisible -- so a reader of the log
could not tell whether the cross-function analysis was earning its cost.

The run log now carries the ratio:

```
Summary cache: 3 hits, 18 misses (14% hit rate), 8 entries, capacity 1000
```

The ratio is LOGGED rather than RECORDED as a budget exhaustion, because a low hit rate is
not an error -- it is the cost of the analysis. A run that never consulted the cache says
`Summary cache: not used (0 lookups)` rather than printing a `0/0` ratio.

### Tier 2 — the precision the rules actually need

| ID | Work | Acceptance | Status |
|---|---|---|---|
| **G6** | Make the reachability check reachable for every rule: collect the function's guard facts once, and decide unreachability from them. | A source whose guards contradict each other does not reach the sink; the same code without the contradiction still reports. | **done, differently** |
| **G7** | Feed the solver a **path condition composed of the guards along the actual path**, not a per-sink predicate. | A finding is suppressed only when the conjunction of the path's guards is `UNSAT`. | **done** |
| **G8** | Make the summary cache **call-string aware** when the call-graph strategy is `1cfa` or `2cfa`, so the k-CFA depth knob becomes real. | Two call sites of one callee with different arguments produce different summaries. | **done, differently** |
| **G9** | Wire the points-to solver into the taint lattice, so `Operation.PointsToPropagate` has a producer and a consumer. | Taint on two distinct instances of one class no longer merges. | **done** |
| **G10** | Give the access-path store a producer and a consumer, so a field's taint belongs to an OBJECT rather than to a variable name. | A field written on one object is not read back on another. | **done** |

### 2.1 G6 was implemented differently, and the reason is worth keeping

The plan was: give the taint base class a guard tracker, implement `pathConditionForSink` in the
base class, and let the solver decide whether the path is reachable. The mechanism is now in
place and it works -- but **the decision is made arithmetically, not by the solver**, and that
is not a stylistic choice.

**Both solver bridges answer SAT for a variable they cannot bind.** That is their designed
behaviour: the bridges are false-positive filters, and "the path might be reachable" is the
conservative answer. A question about whether some value satisfies two contradictory bounds is
not that question -- it is a question about arithmetic, and arithmetic answers it exactly.
So the contradiction is decided where the answer is exact, and the solver is left for the
predicates it can actually evaluate.

The consequences worth knowing:

- The check works **without** the solver, so it does not depend on the native library being
  present. A run on a machine where Z3 could not load still filters the contradictions.
- The filter is **narrow on purpose**. Only the SOURCE variable's guards are consulted, because
  a guard on another variable says nothing about whether this source reaches the sink, and two
  guards recorded at different points in the function are not both in force at the sink. Both
  restrictions exist so the check cannot suppress a finding that is real.
- The guard facts are collected **once per function** by the engine, not once per checker. A
  guard is a fact about the function; deriving it per checker is how two checkers end up
  disagreeing about whether a value was guarded.

The remaining solver-shaped work is G7: a path condition over several variables at once, which
needs the solver to reason about a conjunction rather than about one variable's bounds.

### 2.2 G7 removes a FALSE NEGATIVE, it is not a missing feature

G7 asks for "a path condition composed of the guards along the actual path". Reading the
**function's whole guard set** -- the union of every guard the function ever pushed -- and
treating the contradictory pairs in it as a contradiction on the path is unsound, and the
unsoundness costs a **finding**.

```java
int n = Integer.parseInt(s);
if (n > 5) { System.out.println("big"); }   // guard: n > 5
if (n < 3) { System.out.println("small"); } // guard: n < 3
new ByteArrayOutputStream(n);               // the sink
```

The two guards contradict each other, but the path to the sink does not require **both** of them.
The allocation is reached with `n = 1`. Reading the union produced the condition
`n > 5 && n < 3`, which is unsatisfiable, so the finding was suppressed — and the scan reported a
clean program for code that allocates an attacker-sized buffer.

The `GuardTracker`'s active stack was already path-scoped: a guard is pushed on its `BRANCH` and
popped at the end of its block. So the guards genuinely in force at an instruction are exactly the
ones its path establishes. The fix records that stack's contents at each instruction position,
and a sink now asks "what was in force **here**?":

| | Guards in force at the sink |
|---|---|
| sequential ifs (the shape above) | **none** — both if-blocks have ended, so the finding stands |
| nested ifs | **both** — reaching the sink requires both, so it is unsatisfiable and the finding is suppressed |

The engine now records the instruction's index per thread (`AnalysisContext.currentInstructionIndex`),
and the guard pre-pass snapshots the active stack at each position (`GuardTracker.recordPosition`).

**What this changed about the test suite.** The `PathConditionVerificationTest` fixture is the
NESTED shape — the one that actually expresses "both guards are required" — and the SEQUENTIAL
shape is kept as the **negative** case, asserting that the finding is NOT suppressed. The
sequential shape is reachable (the allocation is reached with `n = 1`), so suppressing a finding
there would be a false NEGATIVE.

> **A filter that suppresses everything passes a test that only checks that a finding disappeared.**
> The negative case is the one that makes the positive case mean something, and here the negative
> case was the one that was wrong.

### 2.2a G8: the summary is only true relative to the call site

The plan was: key the summary cache by `(signature, call string)` when the call-graph strategy is
`1cfa` or `2cfa`. The mechanism is in place and it works -- but **the context sensitivity is on the
caller's side, not the callee's**, and the call string does not come from the call graph.

**The shape of the problem.** A summary says "this function propagates its parameters' taint to its
return value". That is a statement about the function GIVEN which of its parameters were tainted. A
helper called once with attacker input and once with a constant does not return tainted data at both
call sites -- it returns tainted data at one of them. With one shared summary per function, the two
call sites share the union, so the constant call inherits the tainted call's taint and the sink fires
on a value that never was tainted.

**What was built.** The summary is still built ONCE from the callee's body -- the
summary-building method is not re-run per call site, and the callee's own facts stay
context-free. What is context-sensitive is the CALLER's side: the summary is **projected through
the call site's parameter binding** -- "which argument was tainted, and which formal parameter does
it bind to" -- and the projection keeps only the formal parameters this call site actually feeds
with taint.

| Piece | What it is |
|---|---|
| `SummaryKey` | The identity of a summary entry: the callee's signature **and** the call context |
| `CallSiteBinding` | The caller's statement about one call: which arguments were tainted, and which formal each binds to |
| `FunctionSummary.returnTaintCauses()` | The formal parameters the return value's taint flows through |
| `FunctionSummary.projectThrough(binding)` | The projection: keep only the facts reachable through that binding |

**Why the call string does not come from the call graph.** The call graph's
`CallSite.callerInstructionIndex` is `-1` in six of the nine places a call site is constructed --
including every strategy named `1cfa` and `2cfa`, which are the ones a reader would most expect to
carry a call site. The instruction index, by contrast, is recorded by the engine before each
instruction is analysed (`AnalysisContext.currentInstructionIndex`), so it is available wherever the
analysis actually is. The call string is the caller's signature and that index, joined by `#` --
the format `PointsToSolver` already builds for its own contexts, so the engine's two
context-sensitive mechanisms speak the same vocabulary.

**Why the projection can only remove taint.** It is a subset operation, and a lookup that finds no
projection falls back to the context-free summary. So a context-sensitive lookup finds strictly LESS
than a context-insensitive one, never more, and never nothing when the context-free one had
something. That is what makes it safe to enable: it cannot introduce a false negative.

**Why the contextual entries live in their own map.** Merging them into the context-free map would
let a context-free lookup return a projection -- and a projection is missing the operations that
call site could not reach, so returning one as if it were the whole summary would under-report. For
the same reason, a projection is dropped when the summary it was derived from is replaced or merged:
a stale projection is a silent under-report, which is the one failure mode a context-sensitive layer
must not have.

**What it does NOT do.** The `context_depth` setting is still not k-CFA depth -- it distinguishes "no
cross-function analysis" from "there is cross-function analysis", as G3 says. The call string is
one call site deep (1-CFA), not a bounded k-call-string prefix, and the projection applies to the
taint-propagation path only.

### 2.3 G9 was a record with no producer and no consumer

`Operation.PointsToPropagate(Set<String> allocSites)` was declared, documented, and referenced by
nothing. The points-to solver knew which objects a method returns — but that knowledge stayed
inside the solver: the summary carried nothing about it, and no consumer read it.

The chain now has all three links:

| Link | Where |
|---|---|
| The solver knows what a method returns | `PointsToSolver.returnObjectsOf(signature)` |
| The summary carries it | `DfaEngine.attachReturnPointsTo` → `Operation.PointsToPropagate` in `returnOps` |
| The caller's destination inherits it | `TaintedCheckerBase.applyReturnPointsTo` → `PointsToSolver.addPointsTo` |

The operation carries the alloc sites as **labels** (`site.type@context`), because an
`Operation` is a value type and must not embed the solver's internals. The consumer reconstructs
the solver's own `Obj` identities from those labels rather than inventing a parallel set — and a
label that names no known object is treated as **stale** and recorded as nothing, because
recording a stale identity as a fresh allocation would be worse than recording none.

The union over the method's contexts is the sound over-approximation: a caller does not know
which context it will run in, so it must be able to receive any of the objects the method may
return. The union can name an object the caller's particular context would not receive, but it
never omits one it would.

**What it buys.** Two instances of one class become separable: the caller's destination inherits
the *callee's* allocation rather than merging with every other allocation of that type.

**How we know the chain is connected.** Three links can each be present and still not connected:
a producer that computes the right answer, a carrier that never receives it, and a consumer that
never looks. The unit tests for the three links call the solver directly, which proves each piece
works and proves nothing about the wiring.

`PointsToSummaryWiringTest` therefore reads the summary **out of the engine's own published cache**
(`FindingReporter.context()` → `AnalysisContext.summaryCache()`) after a real scan, and asserts
that some summary carries a `PointsToPropagate` operation. It reads the engine's copy rather than
rebuilding one, because rebuilding it would test the test's own copy of the logic.

The test was run against a deliberately broken engine — the producer line commented out — and it
failed on exactly that assertion. A wiring test that passes against a disconnected implementation
is worse than no wiring test, because it certifies the disconnection.

> **A working part that is connected to nothing looks exactly like a working part.**
> The only thing that distinguishes them is a test that reads the connection from the far end.

### Tier 3 — the expressiveness ceiling

The IR has 22 opcodes (`plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java:44`). It has
no loop, no switch, no lambda, no concurrency and no dynamic-dispatch opcode. That is not a defect
in itself — the frontend degrades those constructs into branches and calls, which is the intended
design. It matters because **the three precision tiers of Tier 2 are all bounded by it**:

| Missing in IR | Blocks |
|---|---|
| loop-carried induction variable | loop-sensitive taint |
| dynamic dispatch marker | receiver-type-sensitive sink matching |
| explicit capture / write-back | closure state propagation |

### 2.4 G10 was broken at both ends, and the fix needed a producer

`AccessPathTaintStore` was a complete, well-designed class with **zero consumers**, and the
access-path vocabulary was never constructed by anything: the whole-tree count of `TacAccessPath`
outside its own definition was the store and the IR's own test.

G9 landed the object identity a path could point at, so the remaining work was to build the paths.
`AccessPathBuilder` does that: it asks the points-to solver which objects a receiver may hold, and
roots each path at the object's **allocation identity** rather than at the receiver's name. The
engine now records a field write under that identity and reads it back by the same identity.

| Link | Where |
|---|---|
| The receiver resolves to objects | `AccessPathBuilder.fieldPaths` → `PointsToSolver.pointsToAcrossContexts` |
| The write is recorded at the location | `TaintedCheckerBase.recordAccessPathFieldWrite` |
| The read finds it | `TaintedCheckerBase.readAccessPathField` |

**The path's root is the allocation identity, and that is the whole point.** A variable name
cannot distinguish two objects: the same name is reused for different objects across a function,
and a different name may hold the same object. The allocation site can.

**What it buys.** Two objects of one class become two locations: a field written on one is not
read back on the other. The test that pins this is
`AccessPathLocalObjectsTest`, and its negative case is the one that matters — with the
location-sensitive read disconnected, the SAME-object case loses its finding, so the path is
demonstrably doing the work rather than decorating it.

**A measurement that changed the plan.** The cross-CALLER case does **not** get separated, and the
reason is not the field tracker. The engine marks **every method parameter as a tainted source**
(`TaintedCheckerBase.initFunction`), and a field read through a tainted receiver propagates that
taint to the field. So a helper that receives an object reports a finding on that object's fields
whatever was written to them.

That rule is conservative in the right direction for a scanner — a parameter is the most common way
untrusted data enters a program — and it is a **different question** from field sensitivity. The
two are easy to confuse, because both are about "whose taint is this". `AccessPathParameterIsASourceTest`
records which rule produced a helper's finding so that the next reader of it is not misled.

> **A precision feature cannot be measured by a test whose premise is another rule's behaviour.**
> The first cross-caller test asserted that two callers' objects are separated, and it failed — not
> because the field tracker merges them, but because the parameter was already a source. The fix
> was to correct the test's premise, not to change the parameter rule.

### Tier 4 — declared as absent, and that is the right call for now

| ID | Work | Rationale |
|---|---|---|
| **G11** | Per-language `TaintBehaviour.PRODUCES_TAINT` and `BLACK_BOX` | Both enum constants exist and **no table populates them**. The registry now covers Java, Kotlin, Scala and C# (`plumb-dfa/src/main/java/org/plumb/dfa/semantics/LanguageSemanticsRegistry.java:79-82`); only Groovy and `UNKNOWN` get the empty table. |
| **G12** | A `ReverseTaintWalker` | Named in configuration, does not exist. G4 removes the name; if the technique is wanted later, it comes back as a real class. |

### 2.5 G11 is inert at BOTH ends, and populating the tables alone would change nothing

The gap is usually described as "no table populates `PRODUCES_TAINT` or `BLACK_BOX`". That is true,
and it is the smaller half of it.

**The accessors have no consumer either.** `LanguageSemantics.producesTaint` and
`LanguageSemantics.isBlackBox` are called from nowhere outside their own file — the only caller is
the registry's own self-test (`LanguageSemanticsTest.java:354`). So even a fully populated table
would change no analysis result: the engine would look up the behaviour and nothing would read the
answer.

**What the engine does instead.** The only behaviour the engine actually consults is
`preservesTaint` (`TaintedCheckerBase.java:1176`), and it consults it on the `UNINTERPRETED`
branch. `UNKNOWN` and `PRESERVES` both return `true` there, so a call with no table entry and a
call that is known to preserve its arguments' taint are treated identically. That is the safe
default, and it is why the missing tables have not produced visible noise.

**So the work is two halves, and the second half is the one that matters.** Populating the tables
is the cheap half. The other half is deciding what each behaviour means for the engine:

- `PRODUCES_TAINT` says the result may be tainted **regardless of the arguments** — a clock read, a
  random generator. The engine's notion of tainted is "attacker-controlled", and this behaviour
  means "not attacker-controlled, but not clean either". Marking such a result tainted would make
  every rule fire on it, which is a different rule (a "weak random" rule, say) and not a taint
  rule.
- `BLACK_BOX` says the engine does not model the call. The javadoc already names the intended
  representation -- an `UNINTERPRETED` instruction rather than a plain `CALL`, so the "I do not know
  this" is recorded in the IR instead of being implied by the absence of a summary. That is a
  **frontend** decision, and it is the one place the behaviour would actually bite.

**Why this is not a quick win.** Both behaviours need a semantic decision about what they mean for
the engine, and a table without a consumer is exactly the shape this document was written to
identify. Adding the entries and leaving the accessors unread would turn one inert mechanism into
two.

---

## 3. Where the three languages stand against the benchmark

This section answers a narrower question than §2: **is the language channel the reason we report
fewer findings, or is it the engine?** The answer was measured, not assumed.

### 3.1 What the language channel already does

- The IR layer is language-agnostic: `plumb-tac/src/main/java/org/plumb/tac/ir/` contains no
  reference to a language-specific API.
- The rule layer is language-agnostic: `TaintRule` carries an optional `languages` set, and the
  same family serves all three languages unless a `by_language` block narrows it.
- The sink catalogue has 26 `by_language` blocks, so the language difference is expressed in
  configuration rather than in code.

### 3.2 The language-specific gaps that remain

| Gap | Where it shows | Consequence |
|---|---|---|
| Scala closure captured mutable state is not written back to the caller | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java` | A closure's write to a captured `var` is lost; the caller reads a stale value. Silent, so it produces a wrong "clean" conclusion rather than a warning. |
| Scala `for` comprehensions lower into an anonymous function | same file | The accumulator's value at the call site does not reflect the loop body's effect. |
| Kotlin `is`-pattern branches | `plumb-frontend/src/main/java/org/plumb/tac/frontend/kotlin/KotlinTacTranslator.java` | Now **reported** as a lowering gap rather than silently skipped — it was made explicit, not made correct. |
| Java switch guards, enum constant body overrides | `plumb-tac/src/main/java/org/plumb/tac/translate/` | Still silent. |

The contract for all of these is `docs/tech/TAC-COVERAGE.md`: **a gap is either lowered correctly,
or reported as a `FunctionProblem`, or it is a defect.** A silent gap that produces a wrong
answer is the only unacceptable outcome.

### 3.3 The engine gaps apply to all three languages equally

This is the important structural point. The items in §2 are **not** Kotlin-specific or
Scala-specific. A Kotlin file and a Java file with the same shape get the same precision, because
they reach the same `TaintedCheckerBase`. Fixing the engine fixes all three languages at once.

**This is why §2 is ordered before §3.** Optimising the language frontends while the engine
declares precision it does not have would multiply a false promise by three.

---

## 4. How to verify a claim in this document

Every number here is measured from the repository, not estimated:

```bash
# the rule and checker counts
python scripts/check_doc_claims.py

# the catalogue's shape (families, patterns, argument positions)
python scripts/capability_baseline.py --check

# the source-side coverage contract
python scripts/check_all.py
```

A reviewer who disagrees with a row should be able to reproduce the grep that shows it is inert.
The `Consumers` column is a claim about **absence**, and absence is only credible when it is
searchable — so each inert row names the file whose field or hook was checked.

---

## 5. Appendix: the raw measurements

| Measurement | Value | How measured |
|---|---|---|
| TAC opcodes | 22 | `TacInstruction.Kind` enum constants |
| Sink families | 43 | runtime `sink-families.yaml` |
| Pattern entries | 2 466 | runtime `sink-families.yaml` |
| Explicit `arg_index` declarations | 2 065 | runtime `sink-families.yaml` |
| `by_language` blocks | 26 | runtime `sink-families.yaml` |
| Taint rules bound by shipped checkers | 55 | 55 `TaintedCheckerBase` subclasses |
| `TaintBehaviour.PRODUCES_TAINT` entries | 0 | language-semantics registry |
| `TaintBehaviour.BLACK_BOX` entries | 0 | language-semantics registry |
| `producesTaint` / `isBlackBox` production consumers | 0 / 0 | whole-tree grep outside their own file |
| Languages with a populated semantics table | 4 | Java, Kotlin, Scala, C# (`LanguageSemanticsRegistry.java:79-82`) |
| Context-free summary cache read sites | 9 | whole-tree grep, 6 of them in the taint path |
| Context-sensitive projection read sites | 1 | `TaintedCheckerBase.java:4812` |
| `AccessPathTaintStore` consumer files / call sites | 1 / 3 | whole-tree grep; the file is `TaintedCheckerBase` |
| `PointsToPropagate` producers / consumers | 1 / 2 | whole-tree grep |
| `pathConditionForSink` overrides | 0 | whole-tree grep |
