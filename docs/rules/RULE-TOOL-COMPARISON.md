# Rule Comparison: Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle

[English](../README.md) | [中文](../zh/rules/RULE-TOOL-COMPARISON.md)

This document compares the **implementation mechanism** and the **rule coverage** of
Plumb with the mainstream Java static-analysis tools, so that a reader can see where
Plumb sits and where its boundaries are.

> **Which numbers in this document are reproducible.**
>
> Every number in the Plumb column is MEASURED from the source by
> `scripts/gen_tool_comparison.py`; regenerating the document reproduces them.
> The numbers in the other tools' columns **come from public third-party material
> that this repository cannot substantiate**; they are a magnitude reference only.
> This document contains no benchmark scores -- the repository has no benchmark
> harness, so any "faster than X" number would be an unreproducible assertion.

## 1. Implementation mechanism

| Dimension | Plumb | PMD | SonarQube | SpotBugs | Coverity | Checkstyle |
| --- | --- | --- | --- | --- | --- | --- |
| **Main implementation** | TAC IR + 596 AST checkers + 12 taint rules + 67 registered DFA checkers | AST | AST + symbol table | Bytecode scanning | Abstract syntax tree + path sensitivity | AST + text matching |
| **Taint engine** | Summary-based cross-function data flow (two phases) | No complete taint | SonarCFamily engine (commercial) | Partial data flow | Commercial engine | None |
| **Cross-function analysis** | Two-phase summary: Phase 1 path-insensitive -> Phase 2 flow-sensitive + Z3 path conditions | Limited | Partial (call graph 1-hop) | Partial (shared BugInstance) | Global | None |
| **AST checkers** | 596 (JavaParser) | Hundreds | Hundreds | -- | -- | Over a hundred |
| **DFA checkers** | 67 registered | Partial | Partial | Bytecode bug patterns | Yes | None |
| **Z3 path conditions** | Yes (a Phase 2 per-finding query, used for false-positive filtering) | None | None | None | Yes (commercial) | None |
| **AI integration** | Optional Re-Act agent (12 tools) | None | SonarAI (experimental) | None | Coverity Assist | None |
| **rule -> CWE mapping** | Carried by the rules, covering 59 CWEs | Carried by the rule | Carried by the rule | Carried by the BugPattern | Carried by the checker | Carried by the check |

> "AST checkers" and "DFA checkers" are **two different granularities**: the first counts
> checker classes, the second counts the checkers the engine actually registers. The
> "total" you get by adding them depends on what you are counting, so this document
> does not give a combined figure.

### 1.1 Plumb's two-phase cross-function model

PMD / SonarQube / Checkstyle are **AST-only** -- a single-method or single-class view,
blind to cross-function data flow.

Plumb does it in two phases:

- **Phase 1**: the callee computes a path-insensitive, context-insensitive summary
  bottom-up, cached by function signature, and the caller reuses it with an O(1)
  table lookup.
- **Phase 2**: the caller analyses top-down with context-sensitivity and
  path-sensitivity, and Z3 evaluates per finding.

That balances precision (0 false negatives, per-call-site discrimination) against
speed. See
[`../tech/CROSS-FUNCTION-ANALYSIS.md`](../tech/CROSS-FUNCTION-ANALYSIS.md) for details.

### 1.2 How the DFA engine and the AST divide the work

| Shape | Count | Meaning |
| --- | ---: | --- |
| **AST checker (`plumb-style`)** | 596 | Rule classes, looking at AST shapes |
| **Registered in `CheckerCategories`** | 594 | The subset of those that is registered into the rule table |
| **DFA checker (registered)** | 67 | The ones the engine actually assembles; they involve data flow / cross-function analysis |
| **Of those, `extends TaintedCheckerBase`** | 47 | Inherit the common taint-propagation base class |
| **Fact kinds** | 27 | 15 built-in + 12 from `fact-kinds.yaml` |

**The division of labour**: a rule that depends only on the AST shape belongs in
`plumb-style`. Implementing a style rule with the DFA raises neither precision nor
recall -- it only forces "count the AST nodes" logic into an IR visitor.

The complete classification of the DFA checkers is in
[`../tech/CROSS-FUNCTION-AI-TRACE.md`](../tech/CROSS-FUNCTION-AI-TRACE.md) §12.

## 2. Rule coverage

### 2.1 How Plumb's rules are composed

| Category | Count | Source |
| --- | ---: | --- |
| AST checker classes | 596 | `plumb-style/**` |
| Of those, registered into the rule table | 594 | `CheckerCategories` |
| Total registered rules | 1148 | The actual registrations of AST + DFA |
| DFA checker (registered) | 67 | `ScanRunner.buildDfaCheckers()` |
| Taint rule | 12 | `TaintRuleRegistry` |
| Taint source family / patterns | 5 / 19 | The `taint.sources.*` of `sink-families.yaml` |
| Taint sink family / patterns | 34 / 348 | The `*.sinks` of `sink-families.yaml` |
| Input source patterns (framework entry points) | 65 | `InputSourcePatterns` |
| Fact kinds | 27 | Built-in + YAML |

### 2.2 Key CWE coverage

Only the Plumb side is listed here, and it is **not compared item by item with the
other tools** -- those numbers come from external material and cannot be reproduced.

| CWE | The Plumb-side implementation |
| --- | --- |
| CWE-22 Path traversal | taint sink (`url.sinks` / `file.sinks`) |
| CWE-78 Command injection | taint sink |
| CWE-89 SQL injection | taint sink (`sql.sinks`) + a MyBatis `${}` raw-interpolation check (AST layer) |
| CWE-94 Code injection | taint sink (`ognl.sinks` / `el.sinks` / `jcr.sinks` / `jsp-include.sinks`) |
| CWE-117 Log injection | taint sink (`log.sinks`) |
| CWE-190/191 Integer overflow/underflow | DFA + Z3 path conditions |
| CWE-252/253 Unchecked return value | DFA (`UncheckedReturnCheck`) |
| CWE-470 Unsafe reflection | taint sink (`reflection.sinks`) |
| CWE-502 Deserialization | taint sink |
| CWE-532 Sensitive information in logs | taint source + taint sink |
| CWE-601 Open redirect | taint sink (`open-redirect.sinks`) |
| CWE-918 SSRF | taint sink (`url.sinks`) |
| CWE-1333 ReDoS | taint sink (`regex.sinks`) |

**Framework entry points**: the binding annotations of Spring MVC / JAX-RS / Quarkus
REST / Micronaut / Jakarta Servlet (`@RequestParam`, `@QueryParam`, `@RestQuery`,
`@QueryValue` and so on) are driven by one shared annotation list, which serves both
the taint engine and the input-validation checkers.

## 3. False-positive control

Plumb controls false positives in three layers:

| Layer | Mechanism |
| --- | --- |
| **Path sensitivity** | `SolverZ3Bridge` / `BoundaryConstraintChecker` evaluate the path conditions per finding, used to negate numeric warnings |
| **Source trustworthiness** | A taint source carries a trustworthiness weight; the source tag looks like `param:customer@spring-mvc:RequestParam` |
| **AI noise reduction (optional)** | The Re-Act agent of `plumb-ai`, whose prompt can be overridden per rule |

> **The principle in one sentence**: a missing fact is **not** the same as safety. An
> empty points-to set, an absent null fact and an uncovered alias **cannot** be used to
> suppress a warning -- they only mean "unknown", and "unknown" has to be treated as
> "possibly broken". The precondition for suppressing a warning is that the guard
> covers the whole set of relevant possibilities.

## 4. AI integration

| Capability | Description |
| --- | --- |
| Shape | Re-Act agent (`ReActAgent` + `LlmConnection` + `ToolRegistry`) |
| Tools | 12 registered (the source has 14 tool classes, including the `Tool` interface itself) |
| Provider | `OpenAiCompatibleLlmProvider` (the OpenAI-compatible interface) |
| Position | **An optional layer**. The static analysis alone is already enough; AI reduces false positives further and does not replace the static rules |
| Per-rule prompt | Written in the `getAiPrompt()` of each checker |

## 5. Where it fits and where it does not

**A good fit**:
- You need **cross-function taint** (Spring / Jakarta / large enterprise projects)
- You need **path-sensitive numeric analysis** (CWE-190 / 191 / 252 / 253)
- You want **one tool to cover several families of style rules** instead of running
  PMD + Sonar + SpotBugs + Checkstyle side by side
- You need **transparent implementation** -- the SPI of every rule is documented
- You need to feed the results into CI / an IDE (NDJSON, SARIF output)

**Not a good fit**:
- You need C / C++ / JS / Go (Plumb does Java only)
- You need the path-sensitive coverage of a commercial engine
- You need real-time incremental checking inside an IDE (Plumb is CLI-first)

## 6. Related documents

- [`RULES.md`](RULES.md) -- the rule catalogue and its categories
- [`RULE-AUTHORING.md`](RULE-AUTHORING.md) -- how to add a rule
- [`RULE-STYLE-OP.md`](RULE-STYLE-OP.md) -- the rule operations manual
- [`../tech/ARCHITECTURE.md`](../tech/ARCHITECTURE.md) -- the engine architecture
- [`../tech/CROSS-FUNCTION-ANALYSIS.md`](../tech/CROSS-FUNCTION-ANALYSIS.md) -- cross-function analysis
- [`../../README.md`](../../README.md) -- getting started
