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

- `plumb-dfa/src/main/java/org/plumb/dfa/taint/AccessPathTaintStore.java` — a path-keyed taint
  store for `TacAccessPath` (`plumb-tac/src/main/java/org/plumb/tac/ir/TacAccessPath.java`).
  **Zero consumers outside its own file.** The access-path vocabulary is designed, serializable and
  tested, but the analysis still tracks field taint by the coarser `receiver.fieldName` string.
- `plumb-dfa/src/main/java/org/plumb/dfa/summary/Operation.java:259` —
  `PointsToPropagate(allocSites)`. **Zero producers, zero consumers.** The points-to solver
  computes heap objects, and the heap model never reaches the taint lattice.

### 1.2 The honest boundary of the current analysis

Stated plainly, so nothing downstream has to guess:

- The analysis is **flow-sensitive** — instructions are visited in BB-RPO order, forward inside a
  block (`plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:94`).
- The analysis is **not path-sensitive** — there is no CFG join and no lattice meet. One forward
  pass per function (`DfaEngine.java:1737`), a flat fact map, no re-iteration.
- The analysis is **not call-string context-sensitive** — the summary cache is keyed by plain
  function signature (`plumb-dfa/src/main/java/org/plumb/dfa/summary/FunctionSummaryCache.java:77`),
  so two call sites of one callee share one summary regardless of the call-graph strategy.
- The analysis is **not object-sensitive** — taint is attached to a `TacVar`, never to an
  allocation site. The points-to solver exists and runs, but its results do not sharpen taint.

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
| **G5** | Report the summary cache's hit/miss ratio and the refusal count in the analysis summary. | The report contains the number. | **open** |

G1–G4 are not features. They are the removal of a lie. **They are listed first because a tool
that says "path sensitivity: full" and then behaves as "none" will lose a user's trust in every
other number it prints.**

G3 is deliberately narrower than the name suggests, and the configuration file says so. The
engine's summary is itself a fold -- a function's summary holds the facts its own callees
contributed -- so a chain of calls accumulates into one summary and the depth of the chain is
not separately bounded. The setting therefore distinguishes "no cross-function analysis" from
"there is cross-function analysis". It does not yet distinguish a k-CFA depth, which is G8.

### Tier 2 — the precision the rules actually need

| ID | Work | Acceptance | Status |
|---|---|---|---|
| **G6** | Make the reachability check reachable for every rule: collect the function's guard facts once, and decide unreachability from them. | A source whose guards contradict each other does not reach the sink; the same code without the contradiction still reports. | **done, differently** |
| **G7** | Feed the solver a **path condition composed of the guards along the actual path**, not a per-sink predicate. | A finding is suppressed only when the conjunction of the path's guards is `UNSAT`. | **open** |
| **G8** | Make the summary cache **call-string aware** when the call-graph strategy is `1cfa` or `2cfa`, so the k-CFA depth knob becomes real. | Two call sites of one callee with different arguments produce different summaries. | **open** |
| **G9** | Wire the points-to solver into the taint lattice, so `Operation.PointsToPropagate` has a producer and a consumer. | Taint on two distinct instances of one class no longer merges. | **open** |

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

### Tier 4 — declared as absent, and that is the right call for now

| ID | Work | Rationale |
|---|---|---|
| **G10** | Field sensitivity via `TacAccessPath` | The vocabulary exists but the store has no consumer. Revisit when object sensitivity (G9) lands; without a heap model, access paths have nothing to point at. |
| **G11** | Per-language `TaintBehaviour.PRODUCES_TAINT` and `BLACK_BOX` | Both enum constants exist and **no table populates them**. The language-semantics registry covers Java and Kotlin only (`plumb-dfa/src/main/java/org/plumb/dfa/semantics/LanguageSemanticsRegistry.java:77`); Scala and Groovy get the empty table. |
| **G12** | A `ReverseTaintWalker` | Named in configuration, does not exist. G4 removes the name; if the technique is wanted later, it comes back as a real class. |

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
| `AccessPathTaintStore` consumers | 0 | whole-tree grep |
| `PointsToPropagate` producers / consumers | 0 / 0 | whole-tree grep |
| `pathConditionForSink` overrides | 0 | whole-tree grep |
