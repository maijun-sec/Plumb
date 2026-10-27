# Plumb Documentation Index

[English](../README.md) | [中文](zh/README.md)

`docs/` holds **product documentation only**: what the system *is*, how it
works, and how to extend it. Everything here describes the current code --
change the code, change the corresponding document.

The test for whether something belongs here: **if it still holds after a
refactor, it belongs in the product documentation.** Otherwise -- if it
records a particular change, audit round, or evaluation -- leave it out.
Once such material settles into the product documentation it becomes an
unverifiable assertion.

---

## `tech/` -- Technical architecture

| File | Contents | 中文 |
| --- | --- | --- |
| [`ARCHITECTURE.md`](tech/ARCHITECTURE.md) | Global architecture: the 7-stage pipeline, module dependency graph, checker implementation shape, key design decisions, build-system auto-discovery, rule-id consistency, extension points, the test system | [中文](zh/tech/ARCHITECTURE.md) |
| [`TAC.md`](tech/TAC.md) | The three-address code format: IR classes, instruction set, CFG, serialization | [中文](zh/tech/TAC.md) |
| [`DATA-FLOW.md`](tech/DATA-FLOW.md) | **Data-flow analysis**: engine skeleton, fact domains, the four analyses (taint / boundary / points-to / control flow), tri-valued nullability, what Z3 actually does, call-graph strategies | [中文](zh/tech/DATA-FLOW.md) |
| [`CROSS-FUNCTION-ANALYSIS.md`](tech/CROSS-FUNCTION-ANALYSIS.md) | The cross-function analysis backbone: the summary mechanism, the call-graph view | [中文](zh/tech/CROSS-FUNCTION-ANALYSIS.md) |
| [`CROSS-FUNCTION-OPERATION.md`](tech/CROSS-FUNCTION-OPERATION.md) | The `Operation` system: constant propagation / points-to / null / numeric range / side effects, on the summary side and the consumption side | [中文](zh/tech/CROSS-FUNCTION-OPERATION.md) |
| [`CROSS-FUNCTION-AI-TRACE.md`](tech/CROSS-FUNCTION-AI-TRACE.md) | The periphery: the parallel worklist, cross-file validation, known limitations, the AI augmentation layer, the Re-Act framework | [中文](zh/tech/CROSS-FUNCTION-AI-TRACE.md) |
| [`DFA-AUGMENTATION-DESIGN.md`](tech/DFA-AUGMENTATION-DESIGN.md) | Why the AST is not enough, and the DFA augmentation techniques | [中文](zh/tech/DFA-AUGMENTATION-DESIGN.md) |
| [`STYLE-RULES.md`](tech/STYLE-RULES.md) | The style-rule implementation: `AstDispatcher`, `AstCheckerRegistry`, typical checker patterns | [中文](zh/tech/STYLE-RULES.md) |
| [`KOTLIN-DSL.md`](tech/KOTLIN-DSL.md) | **The Kotlin DSL AST rule engine**: rule file format, the matcher language, the compile/evaluate model, `--dsl-rules` usage | [中文](zh/tech/KOTLIN-DSL.md) |
| [`MODULE-MERGE.md`](tech/MODULE-MERGE.md) | Module structure and dependency boundaries | [中文](zh/tech/MODULE-MERGE.md) |
| [`CACHE.md`](tech/CACHE.md) | The on-disk IR cache: the `--output-dir` mechanism, the file format, the key, compatibility | [中文](zh/tech/CACHE.md) |

## `rules/` -- Rules

| File | Contents | 中文 |
| --- | --- | --- |
| [`RULES.md`](rules/RULES.md) | The main catalogue: counts (every number measured from the configuration), severity / type / emit-id, taint and DFA checkers | [中文](zh/rules/RULES.md) |
| [`RULE-STYLE-OP.md`](rules/RULE-STYLE-OP.md) | Style rule operations: the distribution, the 5-layer enable/disable mechanism, changing rule parameters | [中文](zh/rules/RULE-STYLE-OP.md) |
| [`RULE-AUTHORING.md`](rules/RULE-AUTHORING.md) | Rule development: the steps to add a rule, CWE label consistency, choosing an AST checker | [中文](zh/rules/RULE-AUTHORING.md) |
| [`RULE-TOOL-COMPARISON.md`](rules/RULE-TOOL-COMPARISON.md) | Plumb vs PMD / SonarQube / SpotBugs / Coverity / Checkstyle | [中文](zh/rules/RULE-TOOL-COMPARISON.md) |
| [`plumb-style-rules.md`](plumb-style-rules.md) | Generated: the Javadoc of every style rule plus the equivalent-tool mapping | English only |
| [`plumb-style-rules-index.md`](plumb-style-rules-index.md) | Generated: the style rule index | English only |

## `usage/` -- Usage

| File | Contents | 中文 |
| --- | --- | --- |
| [`CLI.md`](usage/CLI.md) | The complete CLI manual: the flag list, the YAML schema, AI augmentation, exit codes, troubleshooting | [中文](zh/usage/CLI.md) |
| [`CLI-EXAMPLES.md`](usage/CLI-EXAMPLES.md) | Typical scenarios plus a command-to-default-config reference | [中文](zh/usage/CLI-EXAMPLES.md) |
| [`CLI-REFERENCE.md`](usage/CLI-REFERENCE.md) | Project path families / build-system auto-discovery / a YAML v2 quick reference | [中文](zh/usage/CLI-REFERENCE.md) |
| [`ENVIRONMENT.md`](usage/ENVIRONMENT.md) | Environment variables and AI configuration: which variables the code actually reads, the AI provider configuration, CI examples | [中文](zh/usage/ENVIRONMENT.md) |

## `build/` -- Packaging and release

| File | Contents | 中文 |
| --- | --- | --- |
| [`PACKAGE.md`](build/PACKAGE.md) | The release shapes, the launcher logic, the smoke test, cross-platform notes | [中文](zh/build/PACKAGE.md) |

## End-to-end measurement

| File | Contents | 中文 |
| --- | --- | --- |
| [`../testcase/SUPPORT-MATRIX.md`](../testcase/SUPPORT-MATRIX.md) | The measured TP / TN numbers for every testcase category, with the known limitations | English only |
| [`../testcase/README.md`](../testcase/README.md) | How to run a category, what the harness means, the case status definitions | English only |

The numbers in the support matrix are generated by
`scripts/write_support_matrix.py` from each category's `RESULT.json`. Do not
edit them by hand.

---

## Quick links

| Question | Go to |
| --- | --- |
| First time using it | [`usage/CLI.md`](usage/CLI.md) |
| A concrete scenario | [`usage/CLI-EXAMPLES.md`](usage/CLI-EXAMPLES.md) |
| A flag or YAML snippet | [`usage/CLI-REFERENCE.md`](usage/CLI-REFERENCE.md) |
| Environment variables / API key | [`usage/ENVIRONMENT.md`](usage/ENVIRONMENT.md) |
| Which rules exist | [`rules/RULES.md`](rules/RULES.md) |
| Adding a custom rule | [`rules/RULE-AUTHORING.md`](rules/RULE-AUTHORING.md), [`tech/ARCHITECTURE.md`](tech/ARCHITECTURE.md) section 10 |
| Writing an AST rule with the Kotlin DSL | [`tech/KOTLIN-DSL.md`](tech/KOTLIN-DSL.md) |
| How the system runs as a whole | [`tech/ARCHITECTURE.md`](tech/ARCHITECTURE.md) |
| **How the data-flow analysis works** | **[`tech/DATA-FLOW.md`](tech/DATA-FLOW.md)** |
| Points-to / nullability / taint | [`tech/DATA-FLOW.md`](tech/DATA-FLOW.md) sections 3-5 |
| How cross-function summaries work | [`tech/CROSS-FUNCTION-ANALYSIS.md`](tech/CROSS-FUNCTION-ANALYSIS.md) |
| The on-disk cache | [`tech/CACHE.md`](tech/CACHE.md) |
| Shipping a release | [`build/PACKAGE.md`](build/PACKAGE.md) |
| How it compares to other tools | [`rules/RULE-TOOL-COMPARISON.md`](rules/RULE-TOOL-COMPARISON.md) |
| Current precision / recall | [`../testcase/SUPPORT-MATRIX.md`](../testcase/SUPPORT-MATRIX.md) |

---

## Maintenance conventions

1. **Every class name you cite must be findable in the source.** Every Java class the documentation mentions has to really exist -- a refactor invalidates the reference silently (a deleted package path segment is the most common case).
2. **Do not write numbers by hand.** Rule counts, source/sink pattern counts, and testcase scores all have a generation or measurement path; if you cannot state the measurement command, do not state the number.
3. **Change the documentation when you change the architecture.** `DATA-FLOW.md` describes the engine skeleton, `ARCHITECTURE.md` describes the module boundaries; when you change one of them, check that the other still says the same thing.
4. **Do not write round-by-round logs.** Change history belongs in git log, not in `docs/`. A document says only "what it is now".
5. **Process documents do not enter `docs/`.** Audit reports, coverage snapshots, optimisation paths: either leave them out, or fold the **already verified** conclusion into the corresponding product document and drop the round number.

---

## Elsewhere in the repository

- `testcase/config/min.yaml` -- A minimal configuration example: `inputs` points at sample source inside this repository, and it runs as is.

---

## Two languages

English is the canonical source and lives in `docs/`. The Chinese
counterpart lives in `docs/zh/`, with the same file names and the same
structure. `scripts/check_i18n_docs.py` fails the build when the two trees
drift apart: a missing counterpart, a different heading structure, a
dropped table row, a removed code block, a broken link, or a missing
language switch.

The rule catalogue (`plumb-style-rules.md` and
`plumb-style-rules-index.md`) is generated from the source tree and stays
English-only; a translated copy would drift from the generator on every
run.

### Adding a document

A new document is added twice, in both trees, under the same relative
path. The gate fails until the counterpart exists.

The two versions must have the same **shape**, because that is what the
gate compares:

| Property | Requirement |
| --- | --- |
| File name and path | Identical, relative to `docs/` and `docs/zh/` |
| Headings | The same number, at the same levels, in the same order |
| Tables | The same number of data rows in each table |
| Code blocks | The same number |
| Links | Every relative link resolves |

Identifiers are never translated: class names, flag names, package names,
file paths, commands, rule ids, and the contents of code blocks stay
exactly as they are. What is translated is the prose around them.

Each page carries a language line at the top, and the link has to point at
the other language's page. For a document in `docs/usage/`, the English
line links up one level to the English index and across into the Chinese
tree; the Chinese line links up two levels to the English document and
back to the Chinese index. The links are relative, so the switch works
from a local filesystem, a `file://` URL, or a copy inside the packaged
distribution. Nothing has to be served or built.
The prose is not compared between the languages -- a sentence-by-sentence
comparison would need a translation model in the build, and pretending
otherwise would make the gate lie. What the gate compares is the
structure, which is the thing a reader navigates by. A wording difference
is not a failure; a dropped section is.
