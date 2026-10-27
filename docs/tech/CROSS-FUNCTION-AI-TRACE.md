# Around Cross-Function Analysis: Parallel Worklist / Comparison with Tabulation / Trace Auditing / Checker Migration / AI Enhancement

[English](../README.md) | [中文](../zh/tech/CROSS-FUNCTION-AI-TRACE.md)

This document covers the periphery of the cross-function analysis: the
parallel worklist, the comparison with tabulation, the trace auditing, the
checker migration, and the AI enhancement layer. The Phase 1+2 backbone
(motivation, CallGraph, Phase 1 Summary, Phase 2 precise analysis) is in
`CROSS-FUNCTION-ANALYSIS.md`; the Operation system and the six
sub-analyses are in `CROSS-FUNCTION-OPERATION.md`.

## 6. Parallel worklist

The "callee before caller" of Phase 1 is a hard constraint of RPO, but there is no need to wait for a whole
RPO level to finish before starting the next one — as soon as all of a function's **callees that have a body in
the program** have been summarised, the caller can start.

The dep-count algorithm:

```text
deps[fn] = the number of callees called in the body that can be found in program.function(sig)
readyQ = { fn | deps[fn] == 0 }
worker:
  sig = readyQ.poll()
  analyseOneFunction(sig)            # Phase 2 (caller's point of view) runs together with Phase 1 (the callee writes the summary)
  summaryCache.put(sig, merged)       # Phase 1 is done
  for caller in callersOf[sig]:
    if deps[caller].decrementAndGet() == 0: readyQ.add(caller)
```

Self-recursion (direct / indirect) is actively skipped when the dep count is computed (`if (cs.callee().signature().equals(fn.signature())) continue;`),
so the deadlock is broken by construction — the self-recursive callee has no summary yet when the caller is
analysed, `FunctionSummaryCache.get(sig)` returns `FunctionSummary.empty()`, and the caller seeing "no taint
flows out" is the conservative upper bound.

Concurrency safety rests on two things:

- `FunctionSummaryCache` was upgraded to `ConcurrentHashMap` + `LongAdder`,
  so cross-worker writes / reads are lock-free.
- Per-function state such as `FactBag` and `taintedVars` is `clear()`ed at the entry of
  `analyseOneFunction` and discarded at the exit — at any moment it is held by only one worker.

The design trade-offs of the ` worklist` are described in the comment of `DfaEngine.runWorklist`.

## 7. Comparison with the tabulation (IFDS / IDE) approach

> This section is an **argument about a design trade-off**, not a description of Plumb itself. Plumb is
> neither IFDS nor IDE; its actual structure is described in [`DATA-FLOW.md`](./DATA-FLOW.md).

| Dimension | IFDS / IDE with a single worklist | Plumb summary-based |
| --- | --- | --- |
| State | One unified `(program-point × fact)` graph + a global worklist | One `FunctionSummary` + `MethodSummary` per function; per-function checker state |
| Two phases | Not split into two phases; facts propagate in real time along the call/return edges | Summary warm-up (path-insensitive) + forward traversal (flow-sensitive) |
| Cost of a call | Every CALL edge expands one copy of the callee's facts | O(1) cache lookup; the summary is built once and reused many times |
| Parallelisation | The worklist is shared, so the partitioning strategy is complex | The callees are independent of each other, so they can be parallelised by dependency count |
| Extending the domain | Adding a new kind of fact (such as points-to) requires rebuilding the lattice | Adding a new `Operation` variant leaves the old checkers untouched |
| Call graph strategy | Coupled to the solver (IFDS maintains the explode / context itself) | The summary is decoupled from the call graph — `cha` / `rta` / `vta` / `1cfa` / `2cfa` / `stub` are all pluggable |
| Heterogeneous analysis | One lattice struggles to hold several analyses | The summary is a template; several sub-analyses (constant / points-to / null / numeric / side effect) share the framework |

**Why a summary was chosen over tabulation**:

1. **Heterogeneous facts are hard to unify into one lattice**. Taint comes in several kinds (String / Numeric / Pointer / Mixed),
   nullability is three-valued, and points-to is a set-of-alloc. Tabulation has to flatten them into unified IDs,
   which makes it easy for the checkers to pollute each other.
2. **Extensibility**. A new checker only needs a new `Operation` variant (or the reuse of `TaintPropagate` /
   `Sink` / `WriteToField`); the RPO scheduling itself does not have to be touched.
3. **A naturally high cache hit rate**. The same callee called by N callers → the summary is computed only
   N times; a tabulation solution has to compute one copy per call site.
4. **Parallelism is not an after-the-fact patch**. The worklist is built directly on the dependency count and
   processes the call graph in topological batches.
5. **The call graph strategy is pluggable**. `FunctionSummaryCache` is a signature→summary map; the call graph
   algorithm only decides "which callees are reachable" and does not take part in the solving.

The price is **over-approximation at the summary boundary**: a multi-branch return is folded into `top`, and the
caller sees the merged facts, which may lose precision. Note that Z3 here is a **false-positive filter**, not a
precision restorer — it is only called when `pathConditionForSink` is non-empty and cannot restore return details
that the summary has already merged away.

## 8. Cross-file verification

- `plumb-dfa/src/test/.../summary/FunctionSummaryTest` — the contract of `FunctionSummary`:
  merging, projection, and the validation of illegal constructions.
- `plumb-dfa/src/test/.../summary/R32FunctionSummaryEnhancementTest` —
  the shape of the summaries produced by `MethodSummaryBuilder`.
- `plumb-dfa/src/test/.../taint/GuardTrackerTest` — the retention and invalidation of
  guard facts in cross-function propagation.
- `plumb-dfa/src/test/.../points/R1106PointsToSolverTest` —
  `applyCalleeSummary` connecting a `new` inside the callee to the caller's dest.

## 9. Known limitations / future work

1. **Multi-branch returns are folded into `top`**. `MethodSummaryBuilder`'s `buildReturnShape`
   returns `UNKNOWN("multiple returns with mismatched shapes")` when `allSame == false && !(boolean-conditional-pattern)`.
   To keep the precision of multiple branches, `ReturnShape` could be extended with a `RETURN_MULTI` shape
   (a multi-value lattice), but the cost of path sensitivity would then fall on all callers.
2. **Cross-TU lacks caller-free parameter propagation**. A use-after-free still requires the caller to
   do the free after the callee call when crossing a TU. The summary can already see
   `WriteToField`, but the caller cannot see "the callee closed the pointer that was passed in" — this
   information is currently merged into `HasUnmodelledBehavior`, losing precision. The plan is to
   add an `Operation.CloseParam(int)` variant.
3. **The `.tac` cache does not reuse summaries**. Currently `IrSerializer` only serialises the IR, and the
   summary is recomputed per run. Serialising `FunctionSummaryCache` together with `merged.tac`
   (the invariant = the source path is unchanged + the rule set is unchanged) would save Phase 1. See
   "Future extensions" in `docs/tech/CACHE.md`.
4. **Native / Reflection is invisible**. `HasUnmodelledBehavior("native")` makes the caller
   fall back directly to the most conservative "may produce any side effect". To do whitelist analysis
   for JNI, a `nativeSemantics` sub-structure would have to be added to `MethodSummary`.
5. **`GlobalCallGraphStrategy` is not wired into the CLI**. The constructor of `DfaEngine` only accepts a
   TU-local strategy name (`"cha" / "rta" / "vta" / "1cfa" / "stub" / "cached:..."`), not `GlobalTACStore`.
   `GlobalCallGraphStrategy` can only be used by the integration tests; to use it
   in the CLI with `--cg-strategy global`, `DfaEngine` needs a
   `createCrossTu(cfg, checkers, store, threads)` factory, integrated with the worklist,
   and the "name in {global}" branch of `CallGraphStrategyFactory.create(...)` has to be
   deleted (to avoid the "Unknown strategy" false report).

## 10. Further reading

- `docs/tech/DATA-FLOW.md` — interval abstraction + Z3 path conditions
- `docs/tech/ARCHITECTURE.md` — module boundaries (the old `plumb-dfa` engine vs `plumb-dfa`)
- `docs/tech/TAC.md` — the TAC IR (the input of the summary)
- `docs/tech/CACHE.md` — the on-disk IR cache and `merged.tac`

## 11. How Trace (`dataflow_steps`) is produced

Every finding can carry a list of `FlowStep`s, which is output in JSON as `dataflow_steps`.
There are 5 possible values of the `role` of a `FlowStep`:

| role | Meaning | Where it is produced |
| --- | --- | --- |
| `source` | The start of the taint (user input, parameter, resource allocation site) | `TaintedCheckerBase.markSource(...)`; `PathSensitiveCheckerBase` at resource allocation sites |
| `propagate` | The taint propagates through an assignment / a binary operation | The MOVE / BINOP / CALL branches of `TaintedCheckerBase` |
| `summary-fold` | Cross-function folding: the callee's summary is merged into the fact bag on the caller side | `FieldTaintTracker`, the summary applier of `TaintedCheckerBase` |
| `sink` | A sink family is hit | `TaintedCheckerBase.checkSinkAtCall(...)` |
| `violation` | The violation point (pattern-based checker without taint) | The `Finding.violation(...)` static factory |

### 11.1 How the `source` role is recognised

`InputSourcePatterns` (`plumb-dfa/.../taint/InputSourcePatterns.java`) maintains a table of
source patterns over callee signatures. The signature of the same method in the IR can have two
forms (`java.io.BufferedReader.readLine` and `BufferedReader#readLine`), so
`matches(...)` and `isInputSourceCall(...)` first normalise `#` into `.` before doing the
`endsWith` / `contains` comparison.

### 11.2 How the `summary-fold` role is produced

In a cross-function scenario, the field writes that happen inside the callee are invisible to the caller.
`FieldTaintTracker` records `Operation.WriteToField(...)` inside the callee body, and when the caller
applies the summary this information is folded into a single `summary-fold` step, so that the trace reads
as "source → propagate → summary-fold → sink".

The `case RETURN` of `TaintedCheckerBase` maintains the `passedOutTaint` set;
`summarizeFunction` emits `Operation.TaintPropagate(sourceId, confidence)` on the `returnOps`, and
`propagateTaintFromCalleeSummary` on the caller side marks the corresponding actual argument as tainted.

### 11.3 How the `violation` role is produced

`Finding.violation(checkerName, ruleId, msg, severity, cwe, line, col, fnSig, flow)`
(`plumb-reporter/.../Finding.java`) is the recommended entry point for pattern-based checkers:
it produces a finding with 1 `FlowStep(role="violation", ...)`,
and sets the `flow` field at the same time, for compatibility with the reporters that have not yet
migrated to structured steps.

### 11.4 How the `propagate` role is produced

`TaintedCheckerBase` appends a `propagate` step on the MOVE (`MOVE src -> dst`) and BINOP
(`BINOP lhs+rhs -> dst`, taking the longer of the two paths when both operands carry taint) branches,
recording the line / column / target variable of that instruction.

## 12. Classification and registration of the DFA checkers

The checkers on the `plumb-dfa` side come in three implementation shapes:

| Category | Base class | Count | Explanation |
| --- | --- | ---: | --- |
| **taint-style** | `TaintedCheckerBase` | 47 (`checkers/taint/`) | A `TaintRule(ruleId, factKind, sinkFamily, cwe, severity, taintDomain)` is passed in the constructor; a finding is emitted when a sink family is hit |
| **constraint-style** | `checkers/boundary/` | 1 | `BoundaryConstraintChecker`, which uses Z3 to solve the path conditions |
| **pattern-based** | directly `implements DfaChecker` | 16 (`checkers/controlflow/`) + 2 (`checkers/taint/`) | No taint propagation; it relies on local pattern matching over the IR; a single-step trace is emitted with `Finding.violation(...)` |

`ScanRunner.buildDfaCheckers()` (`plumb-main/.../ScanRunner.java`) **actually registers
67** DFA checkers. The registration is controlled by the per-rule flags in the YAML (for example
`config.runIntOverflow`, `config.runNullDeref`); `config.runTaint` is the master switch for the
whole taint family.

The following CWEs are covered by the AST checkers of `plumb-style` (automatically registered by `AstCheckerRegistry.builtins()`):

| CWE | plumb-style checker |
| --- | --- |
| CWE-319 cleartext transmission | `CleartextSocketUsageCheck` |
| CWE-273 class comparison | `CompareClassesByNameCheck` |
| CWE-114 invocation of an external process | `ProcessControlCheck` |
| CWE-674 loop without an exit | `UncheckedLoopConditionCheck` (covers the same CWE, but the semantics are a loop without an exit rather than self-recursion) |
| CWE-457 uninitialised field | `UninitNonnullCheck` |

Likewise, `ServletAtomicityDfaChecker` and `SingletonRaceDfaChecker` **are still in the source but
not registered**: the `ServletAtomicityCheck` / `SingletonRaceCheck` of `plumb-style`
cover the same CWE-362 / CWE-366 with better precision. To enable them, turn on the corresponding
YAML flags (`runServletAtomicity` / `runSingletonRace`).

**How to add a new DFA rule**: write a `<Name>Checker.java` under
`plumb-dfa/src/main/java/org/plumb/dfa/checkers/<family>/`, extending `TaintedCheckerBase` (taint kind)
or directly `implements DfaChecker` (pattern kind), then add a line
`checkers.add(new ...)` in `ScanRunner.buildDfaCheckers()` and hook up the corresponding YAML flag.

## 13. The AI enhancement layer (plumb-ai)

`plumb-ai` adds a layer of LLM adjudication after the static analysis, used to **label findings
TP/FP/UNKNOWN**. It has exactly one pipeline: the FP reducer.

### 13.1 How to enable it

```bash
sast-scan --ai-dedup ...        # the CLI switch
```

```yaml
ai:
  dedup: true                   # the YAML equivalent switch, false by default
  dropOnAiFp: false             # true = drop the findings judged FP; false = keep them and mark ai_verdict=FP
  dedupRules: []                # empty = every rule goes through; if filled, only these rules do
  provider: openai_compatible
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}
  model: gpt-4o-mini  timeoutSeconds: 30
  maxConcurrent: 4
  temperature: 0.0
```

Only the rules that **carry their own prompt** enter the AI adjudication: the checker provides the prompt
through `getAiPrompt(Finding)`, and returning `null` means the rule does not go through AI. `--rule-prompts-dir <dir>`
can override the checker's inline prompt with a `<rule-name>.md` file.

### 13.2 Module structure

There are 29 classes under `plumb-ai/src/main/java/org/plumb/ai/`, split into three packages:

```
org.plumb.ai.agent/     —— the LLM connection and the Re-Act loop
  LlmConnection            connection parameters (provider / baseUrl / apiKey / model / maxTokens / ...)
  LlmProvider              the provider interface (easy to replace in tests)
  OpenAiCompatibleLlmProvider  a raw java.net.http.HttpClient implementation
  LlmMessage / LlmReply    request and response
  ReActAgent               the tool-calling loop
  AgentTask / AgentResult  task and result
  ToolCall / ToolDefinition / ToolResult
  ToolRegistry             the tool registry

org.plumb.ai.tools/     —— 13 tool classes + the context
  Tool                     the tool interface
  ToolContext              the context accessible to the tools
  ToolRegistry             tool registration and invocation
  ReadSourceTool / ListDirTool / GlobTool / GrepTool / SearchPatternTool
  GetFullClassTool / GetClassHierarchyTool / GetCallHierarchyTool
  FindCallersTool / FindDefinitionsTool
  GetMethodAnnotationsTool / GetDataflowTraceWithFactsTool
  HasSanitizerOnPathTool
  V2TraceSummarizer        the trace summary

org.plumb.ai.prompts/    —— AgentPrompts (the system prompt)
```

### 13.3 Where it sits in the ScanRunner

```text
parse -> translate -> style + DFA
                    |
                    v
             [static findings]
                    |
                    v
             AI dedup (only with --ai-dedup and aiConn.isUsable())
                    |
                    v
                  emit
```

`ScanRunner.runAiDedup(...)` (`plumb-main/.../ScanRunner.java:223`) filters by
`ai.dedupRules`, finds the checker that owns the rule for each finding, takes its prompt,
hands it to `ReActAgent` for adjudication, and writes the result back into `ai_verdict` / `ai_reason`.
`--ai-dedup-stats` additionally prints the distribution of the verdicts.

**How a rule is matched (both spellings are accepted)**: a finding carries the canonical emit id
(such as `ast-bug-bc-equals-no-null`), while the `name()` of a checker is a kebab-case slug
(such as `bc-equals-no-null`). **For AST checkers not one of these two strings is equal**,
so the prompt table is registered under both kinds of key:

- **slug** (`checker.name()`) — the rule name the user sees, and also the key corresponding to the
  file name in `--rule-prompts-dir`;
- **emit id** (`checker.rule().ruleId().id()`) — the id the finding actually carries.

The `ai.dedupRules` whitelist also accepts both spellings. The DFA side is unaffected:
`TaintedCheckerBase.name()` returns `rule.ruleId()` directly, so the two spellings are equal there anyway.

### 13.4 Why not Spring-AI

`plumb-ai` uses a raw `java.net.http.HttpClient` + Jackson, avoiding the whole
Spring Boot core dependency of Spring-AI. Any OpenAI-compatible endpoint (OpenAI / Azure / Ollama / vLLM /
LocalAI / MiniMax) can be connected; Anthropic goes through the native
`/v1/messages` of `Provider.ANTHROPIC`, but only a single prompt is supported and tool-calling throws
`UnsupportedOperationException`.

**The AI layer has no bug scanner.** `--ai-scan` (the LLM actively looking for bugs that the static rules
missed) and `--aggressiveness` do not exist: the rule-level switches are controlled by the YAML's
`checks.<rule>.enabled` and by `--rule <id>` (the `aggressiveness` key in the YAML is ignored by
`ConfigLoader`, and `SastMain` never reads the CLI value); the per-rule prompt is held by the checker itself.

## 14. The Re-Act framework: letting the LLM "see the code"

Static rules can only match hard-coded patterns. The Re-Act loop lets the LLM **actively call tools** while
adjudicating a finding — to read the code, look up the callers, follow the dataflow trace — instead of
concluding from a truncated piece of context.

### 14.1 The three core types

```java
// plumb-ai/.../agent/ReActAgent.java
public ReActAgent(LlmProvider provider, ToolRegistry tools) { ... }
public AgentResult run(AgentTask task, ToolContext ctx) { ... }
```

- **`LlmProvider`** — the provider interface; the production implementation is `OpenAiCompatibleLlmProvider`
  (a raw `java.net.http.HttpClient` + Jackson). It is an interface so that tests can inject a fake provider.
- **`AgentTask`** — a record describing what the agent should do this time. `AgentTask.fpReduction(...)` and
  `AgentTask.bugScan(...)` are the two factory methods; `withMaxTurns(int)` and
  `withForceFirstTurnRequired(boolean)` tune the loop parameters.
- **`AgentResult`** — a record with a `Kind` enum (final answer / max turns exceeded /
  provider error / tool error). `isFinalAnswer()` distinguishes "the LLM reached a conclusion" from "the loop did not finish".

### 14.2 The tool set: 13 tool classes, 12 registered

There are **13** concrete tool classes under `plumb-ai/.../tools/` (plus the `Tool` interface, `ToolContext`,
`ToolRegistry`, `ToolCall`, `ToolDefinition`, `ToolResult` base types).

`ScanRunner.buildToolRegistry()` (`plumb-main/.../ScanRunner.java:450`) **registers 12**:

| Tool | Purpose |
| --- | --- |
| `ReadSourceTool` | Read the content of a source file |
| `GrepTool` | Regex search |
| `GlobTool` | Find files by glob |
| `ListDirTool` | List a directory |
| `SearchPatternTool` | Structured pattern search |
| `FindDefinitionsTool` | Find definitions |
| `FindCallersTool` | Find callers |
| `HasSanitizerOnPathTool` | Ask "is there a sanitizer on this path?" |
| `GetMethodAnnotationsTool` | Get the method annotations |
| `GetClassHierarchyTool` | Get the class inheritance chain |
| `GetFullClassTool` | Get the source of a whole class |
| `GetDataflowTraceWithFactsTool` | Get the dataflow trace with facts |

`GetCallHierarchyTool` exists but is **not registered** in `buildToolRegistry()` — the LLM
cannot get it when adjudicating with `--ai-dedup`. There is also `V2TraceSummarizer` (a trace summary, not a Tool).

### 14.3 The call chain in the ScanRunner

```java
org.plumb.ai.tools.ToolRegistry tools = buildToolRegistry();                       // :242
org.plumb.ai.agent.ReActAgent agent = new org.plumb.ai.agent.ReActAgent(provider, tools);  // :243
...
org.plumb.ai.tools.ToolContext ctx = new org.plumb.ai.tools.ToolContext(...);      // :399
```

Every finding goes through one `agent.run(task, ctx)`, and the verdict in the result is written back into `ai_verdict` / `ai_reason`.

### 14.4 Static analysis is the core, the AI **run** is optional

`plumb-ai` is a **compile-time dependency** of `plumb-main` — `ConfigLoader`, `ScanConfig` and
`ScanRunner` all directly use types from `org.plumb.ai.agent.*`, so removing the module breaks the build.

What is optional is the **runtime behaviour**: without `--ai-dedup` the static analysis runs to completion as
usual, and an unavailable LLM only means the verdict is marked UNKNOWN. `plumb-main` interacts with `plumb-ai`
through `org.plumb.ai.agent.LlmConnection` (a pure data record). See
[`../usage/ENVIRONMENT.md`](../usage/ENVIRONMENT.md) for the configuration.

### 14.5 The AI only does FP adjudication

The AI layer has no bug scanner. The factory method `AgentTask.bugScan(...)` exists, but
**nothing calls it** — there is no such branch in the scan pipeline. The AI currently only does FP adjudication
(`AgentTask.fpReduction(...)`).

The per-rule prompt is held by the checker itself (`AstChecker.getAiPrompt(Finding)`),
not centralised in a registry.
