# Plumb

**English** | [中文](README.zh.md)

> **A Java static-analysis toolchain** — JavaParser → TAC IR → DFA (with taint) + AST style rules → NDJSON reports, with **optional** LLM false-positive reduction.

[![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)](docs/README.md)
[![Tests](https://img.shields.io/badge/tests-262%20java%20test%20files-green)](pom.xml)
[![Support](https://img.shields.io/badge/tests-passing-brightgreen)](testcase/SUPPORT-MATRIX.md)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

An independent Java project (NOT a port of Fathom). The architecture is
inspired by Fathom, but the implementation is entirely Java.

---

## In one line

```bash
# After unpacking, in the dist directory:
./bin/plumb -i path/to/your/src/   # static scan
./bin/plumb --ai-dedup -i src/    # static scan + LLM false-positive reduction
```

---

## 1. What it does

| Dimension | Number | Source |
| --- | --- | --- |
| Style checkers | **596** (`CheckerCategories` registers 593) | `plumb-style/**` |
| DFA checkers | **67** actually registered | `ScanRunner.buildDfaCheckers()` |
| Taint rules | **12** | `TaintRuleRegistry` |
| Taint source / sink patterns | **19** / **397** | `sink-families.yaml` |
| Unit tests | **262** Java test files (219 of them `*Test.java`) | each module's `src/test/` |

> The rows below need an external corpus or a benchmark environment to
> reproduce, and this repository has neither the data nor the harness for
> them: the CWE coverage and recall over the Juliet test suite, the AI
> dedup accuracy, the startup and single-file scan latency, and the
> release package size including a JRE.

---

## 2. Module architecture

```
                ┌────────────────────────┐
                │   plumb-parser         │  → AST
                └──────┬─────────────────┘
                       │
       ┌───────────────┼───────────────────┐
       │               │                   │
┌──────▼─────┐ ┌───────▼────────┐ ┌───────▼────────┐
│ plumb-tac  │ │ plumb-style    │                 │
└──────┬─────┘ └───────┬────────┘                 │
       │               │                          │
       │       ┌───────▼────────┐                 │
       │       │ plumb-rules    │                 │
       │       └───────┬────────┘                 │
       │               │                          │
┌──────▼───────────────▼──────┐  ┌──────────────┐ │
│      plumb-dfa             │  │ plumb-ai     │ │
└──────┬─────────────────────┘  └──────┬───────┘ │
       │                                │         │
       │       ┌────────────────────────▼─────┐   │
       │       │  plumb-reporter               │   │
       │       └────────────┬─────────────────┘   │
       │                    │                     │
       └────────┬───────────┘                     │
                │                                 │
       ┌────────▼────────┐                        │
       │   plumb-main    │  ← CLI                │
       └─────────────────┘                        │
```

See [`docs/README.md`](docs/README.md) for the full index.

---

## 3. Getting started

### 3.1 Building from source (developers)

Requires JDK 17+ and Maven 3.8+.

```bash
git clone <plumb-repo>
cd Plumb

# Build and test
./scripts/build.sh                       # Linux / macOS
./scripts/build.ps1                      # Windows PowerShell

# Artifact
ls -lh plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar
```

### 3.2 Code formatting

The source follows Google Java Format (2-space indent, 100 columns). You
must format before committing:

```bash
./scripts/format.sh                      # Linux / macOS
./scripts/format.ps1                     # Windows PowerShell

./scripts/format.sh --check              # check only, do not modify (exit code 1 if unformatted)
```

The formatter version is pinned to **google-java-format 1.37.0**. The
project does not use `fmt-maven-plugin`, because it bundles version 1.7,
and that version cannot parse text blocks -- it reports "expected ';'".

The repository ships a git pre-commit hook, and **a commit that contains
unformatted Java is rejected outright**. A fresh clone needs it installed
once:

```bash
sh scripts/install-hooks.sh
```

The hook only inspects **the files staged for this commit**. Unformatted
files that are already in HEAD do not block later commits unless you touch
them. A full check takes about 30 seconds (1107 source files, one JVM
run), so "format before you commit" does not become a step you cannot
afford to wait for.

Two kinds of file are excluded from formatting, and in both cases the
reason is that they are not product source:

- `plumb-dfa/src/main/resources/scripts/` -- 614 samples from the Juliet test suite, deliberately containing files that do not parse; reformatting them would corrupt the fixtures used to measure the engine.
- `testcase/` -- measurement samples with hand-placed line anchors; reformatting them would move the lines the manifest points at.

### 3.3 The gates that run before a commit

The repository has a set of gates, each checking one thing that would
silently rot. **A gate nobody runs is not a gate**, so they are all driven
by a single command:

```bash
python scripts/check_all.py
```

It runs, in order:

| Gate | What it checks |
| --- | --- |
| `format --check` | Every source file matches google-java-format |
| `gen_categories --check` | The generated checker registry is current |
| `gen_framework_annotations --check` | The generated annotation catalogue is current |
| `check_framework_annotations` | The taint engine and the AST checkers agree on "what counts as user input" |
| `check_catalogue_drift_numbers` | The historical drift numbers quoted in javadoc match the git history they describe |
| `check_dataflow_constraint` | The catalogue's `dataflow_contains` constraint is not a no-op |
| `check_dataflow_claims` | The data-flow specification's citations still point at the declarations they name, and the counts and class names it quotes still match the source |
| `mutate_dataflow_claims` | The data-flow gate is not checking air: every plausible drift is caught |
| `check_dfaaug_claims` | The DFA-augmentation design document still describes the mechanism the code has: the rule ids, the checkers, the API its snippets use, the inert `Operation` variants |
| `mutate_dfaaug_claims` | The design-document gate is not checking air: every plausible drift is caught |
| `check_ruleauthoring_claims` | The rule-authoring document still tells a reader how to add a rule that works: the files it names exist, the commands it gives run, the counts it states match the source, and the aliases it quotes are the ones the source declares |
| `mutate_ruleauthoring_claims` | The rule-authoring gate is not checking air: every plausible drift is caught |
| `check_license` | Every Java source carries the licence header, the `LICENSE` file says the same thing the headers say, and the generator still emits the header |
| `mutate_license` | The licence gate is not checking air: every plausible drift is caught |
| `check_support_matrix_coverage` | The support matrix covers every measured category, and every row matches its `RESULT.json` |
| `check_javadoc_links` | Every `{@link}` target in a javadoc names a type that exists |
| `check_doc_index` | The documentation index lists every document under `docs/`, and every link in it resolves |
| `check_doc_hygiene` | The product documentation describes the **current** state: no round numbers, no past-state wording, no unreproducible measurement presented as fact -- in the markdown, in the source comments, and in the shipped configuration files |
| `check_doc_numbers` | The counts quoted in the hand-written documentation still match the source |
| `check_doc_claims` | The documentation's structural claims still match the source: every key the YAML schema shows is one the loader reads, every CLI flag exists, section numbers are unique, cross-references resolve |
| `check_tac_claims` | The IR specification's structural claims still match the source: the class names, fields, enum constants and counts a reader would code against |
| `check_cross_fn_claims` | The cross-function operation document's named symbols still exist: the classes, the `Operation` variants, the return shapes |
| `check_environment_claims` | The environment document still matches the code: the variables that are read, the ones that are not (the launchers count too), and whether the line citations point at those lines |
| `check_cache_claims` | The cache document still matches the code: the file header constants, the four cache modes and their behaviour, the field types in the source-map contract |
| `check_style_rules_claims` | The style-rules document still matches the code: the interface methods, the dispatcher API, the context API, the visit-overload count |
| `check_i18n_docs` | The English and Chinese documentation trees have the same shape, and every page links to its counterpart |
| `check_readme_claims` | The root README still matches the code: the counts it quotes, the sizes it quotes, and whether a size names the artifact it is about |
| `check_section_refs` | Every cross-document section reference points at a section that exists |
| `check_package_claims` | Every file the packaging scripts copy really exists (one missing file aborts the whole package), and the packaging documentation's sizes and log level match the build |
| `capability_baseline --check` | No rule, sink pattern, source pattern, checker or fact kind was lost |
| `gen_tool_comparison --check` | The tool comparison document's numbers still match the source |
| `gen_rules_overview --check` | The rules overview's numbers still match the source |
| `linkcheck` | No broken relative link in the markdown |

Afterwards it regenerates `docs/plumb-style-rules*.md` and reports whether
the generated result matches the committed version.

**About "the numbers in the hand-written documentation":** the generated
documents (`RULES.md`, `RULE-TOOL-COMPARISON.md`) have their numbers
measured by the generator, and `--check` mode proves they have not gone
stale. But the numbers in **hand-written** documents are nobody's
responsibility -- the repository at one point carried both "595 checker
classes" and "596" when the real number was 596; `ARCHITECTURE.md` claimed
"71 DFA checkers" when the engine actually registers 67.
`check_doc_numbers` measures these numbers against the source again,
including the per-subdirectory table in `RULE-STYLE-OP.md`. A number
either becomes the measured value, or it moves into a generated document
where the generator owns it -- it cannot be written from memory.

**About "the numbers in comments":** a comment that quotes a number lets
that number rot -- the repository at one point carried both "thirteen" and
"seventeen" describing the same historical drift.
`check_catalogue_drift_numbers` reads the two pre-drift lists out of the
git history, computes the real number, and reconciles it against the
comment; a wrong comment is an error. When the history is unavailable (a
shallow clone), it says why and passes, rather than pretending to have
passed.

**About "the class names in comments":** the same thing happens to class
names. The repository at one point had a whole set of javadoc describing a
component called `CallResolver`, saying it "resolves virtual calls along the
class hierarchy" -- and that class did not exist in the repository at all;
the actual virtual-call resolution matches on **method name plus argument
count** (see `ChaCallGraphStrategy`). `check_javadoc_links` resolves every
`{@link}` against the real dependency classpath Maven provides:
`org.plumb.*` has to be found in the source, and a bare name has to be
found in the source, the JDK, or a dependency jar. Not finding it is an
error -- a comment that points at a class that does not exist is a false
statement about the code.

**About "the capability did not get weaker":** the category matrix covers
only a few rules, so "a rule was dropped" does not necessarily show up in
the matrix. `capability_baseline` measures the capability surface itself:
the registered rules, the registered checkers, the checker classes, the
taint source patterns, the fact kinds, the sink families and their
patterns. Any reduction is rejected; an addition is reported and requires
an **explicit** baseline update to be accepted. That way "a little
cleanup while we are here" cannot silently become "one class of problem is
no longer supported".

Fact kinds have two sources, and the gate measures them **separately**
(the `builtin:` / `yaml:` prefixes): the 15 built-in kinds registered in
`BuiltinFactKinds`, and the 12 entries in `fact-kinds.yaml`. The two lists
use different naming styles (`dfa-taint-untrusted-source` vs
`UntrustedSource`), but `ConfigLoader.parseFactKinds` merges them into one
registry -- so together they are 27. Measuring them separately prevents
one list from hiding the other: one version of the measurement used a
pattern that could never match, so the 15 built-in kinds were **not in the
baseline at all** and deleting one of them would not have been noticed.

### 3.4 The smallest possible scan

```bash
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -i path/to/your/src/

# Write the output to an NDJSON file
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -i path/to/your/src/ \
  -o findings.ndjson
```

### 3.5 Configuring with YAML

```bash
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -c conf/default-config.yaml \
  -i path/to/your/src/
```

`default-config.yaml` contains 50 `checks:` keys (48 checker families plus
the two master switches `taint` and `style`), the AI configuration, the
call-graph strategy, and the cache mode.

### 3.6 Turning on AI false-positive reduction

```bash
# Set the API key (environment variable and configuration file both work;
# see docs/usage/ENVIRONMENT.md)
export MINIMAX_API_KEY=sk-...

# Run the scan plus the AI dedup
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -c conf/default-config.yaml \
  -i path/to/your/src/ \
  --ai-dedup
```

Each NDJSON line gains two fields: `ai_verdict` (TP/FP/UNKNOWN) and
`ai_reason`.

### 3.7 Unpack and run (production)

Download `plumb-0.1.0-SNAPSHOT-jre-linux-x64.tar.gz` or
`-windows-x64.zip`, unpack, and:

```bash
cd plumb-0.1.0-SNAPSHOT-jre
./bin/plumb -i /path/to/scan          # Linux / macOS
bin\plumb.bat -i C:\path\to\scan     # Windows
```

**No Java installation is required.** A minimal JRE (jlink-optimised) is
bundled.

---

## 4. The full command

```bash
java -jar plumb-...-all.jar --help
```

```
usage: sast-scan [-c <yaml>] [--cache-mode <off|read|write|readwrite>] [-D <dir>]
                 [-f <console|ndjson>] [-h] [-i <path>] [--max-parallelism <n>]
                 [-o <path>] [-v] [--ai-dedup] [--cg-strategy <name>] [--rule <id>] ...
```

| Flag | Meaning |
| --- | --- |
| `-c, --config <yaml>` | Load a YAML configuration |
| `-i, --input <path>` | Input file or directory (repeatable) |
| `-o, --output <path>` | Output file (stdout by default) |
| `-D, --output-dir <dir>` | Output directory (holds cache/, report/) |
| `-f, --format <console\|ndjson>` | Output format |
| `--cache-mode <off\|read\|write\|readwrite>` | On-disk IR cache mode |
| `--max-parallelism <n>` | Upper bound on parallelism (0 = auto) |
| `--cg-strategy <cha\|rta\|vta\|1cfa\|stub\|global\|cached:...>` | Call-graph strategy (default `global`) |
| `--ai-dedup` | Enable LLM false-positive reduction |
| `--ai-dedup-stats` | Print the AI statistics (TP/FP/UNKNOWN distribution) |
| `--rule <id>` | Run only the given rule (repeatable) |
| `--rule-prompts-dir <path>` | Override the built-in rule prompts |
| `--list-rules` | List every rule |
| `-h, --help` | Help |
| `-v, --version` | Version |

The complete documentation is in [`docs/README.md`](docs/README.md) and
[`docs/usage/CLI.md`](docs/usage/CLI.md).

---

## 5. An NDJSON output sample

```json
{"file":".../VulnerableServlet.java","line":"29","severity":"high",
 "rule_id":"bu-0101-taint-sink-sql","rule_name":"TAINT_SINK_SQL_INJECTION",
 "cwe":["CWE-89"],"message":"Tainted id flows into executeQuery (...)"}

{"file":".../LoginController.java","line":"42","severity":"medium",
 "rule_id":"pl-0204-null-deref","rule_name":"NULL_DEREF",
 "cwe":["CWE-476"],"message":"request.getParameter(...) may be null",
 "ai_verdict":"TP","ai_reason":"…"}
```

---

## 6. Rules / CWE / documentation index

| Want to know | Read |
| --- | --- |
| The documentation index | [`docs/README.md`](docs/README.md) |
| The rule catalogue | [`docs/rules/RULES.md`](docs/rules/RULES.md) |
| The measured precision / recall | [`testcase/SUPPORT-MATRIX.md`](testcase/SUPPORT-MATRIX.md) |
| Environment variables and the AI configuration | [`docs/usage/ENVIRONMENT.md`](docs/usage/ENVIRONMENT.md) |
| The complete CLI and the YAML schema | [`docs/usage/CLI.md`](docs/usage/CLI.md) |
| Packaging and launching | [`docs/build/PACKAGE.md`](docs/build/PACKAGE.md) |
| The project architecture and the key decisions | [`docs/tech/ARCHITECTURE.md`](docs/tech/ARCHITECTURE.md) |
| The TAC IR format | [`docs/tech/TAC.md`](docs/tech/TAC.md) |
| **The data-flow analysis (taint / boundary / points-to / control flow)** | [`docs/tech/DATA-FLOW.md`](docs/tech/DATA-FLOW.md) |
| The style rule implementation | [`docs/tech/STYLE-RULES.md`](docs/tech/STYLE-RULES.md) |
| The cache mechanism | [`docs/tech/CACHE.md`](docs/tech/CACHE.md) |
| The cross-function analysis | [`docs/tech/CROSS-FUNCTION-ANALYSIS.md`](docs/tech/CROSS-FUNCTION-ANALYSIS.md) |

Every document also exists in Chinese under [`docs/zh/`](docs/zh/README.md).

### 6.1 The two languages

The product documentation exists in two languages:

| Tree | Language | Role |
| --- | --- | --- |
| [`docs/`](docs/README.md) | English | **Canonical.** Every number, class reference and behavioural claim originates here |
| [`docs/zh/`](docs/zh/README.md) | Chinese | The synchronised counterpart, with the same file names |

Every page carries a language line at the top: `[English](...) | [中文](...)`.
The links are relative, so the switch keeps working from a local
filesystem, a `file://` URL, or a copy inside the packaged distribution --
no server, no build step, nothing to install.

The two trees are kept aligned by `check_i18n_docs`, which requires the
same documents, the same heading structure, the same table row counts and
the same number of code blocks, resolves every relative link, and requires
each page to link to its counterpart. `mutate_i18n_docs` proves that gate
is not checking air: twelve deliberate kinds of drift, each of which must
make it fail.

The two exceptions are the generated rule catalogue
(`plumb-style-rules.md` and `plumb-style-rules-index.md`). They are
produced from the source tree by `scripts/gen_rules_doc.py` and
`scripts/gen_rules_index.py`, and a translated copy would drift from the
generator on every run, so they stay English-only.

Adding a document means adding it twice, in both trees. The gate fails
until the counterpart exists.

---

## 7. Packaging (production / CI)

### 7.1 The uber-jar (no JRE; the user installs Java 17+)

```bash
./scripts/build.sh
# → plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar
#   the uber-jar: every dependency inlined, about 142 MB
```

The uber-jar is large because it inlines the Z3 native libraries for every
platform and the whole Kotlin script engine. The documentation directory
is a separate thing entirely: the `docs/` tree is about 1.1 MB of documentation,
English and Chinese together.

### 7.2 The dist directory (with launchers, no JRE)

```bash
./scripts/package.sh
# → dist/plumb-0.1.0-SNAPSHOT/
#     bin/sast-scan(.bat)
#     lib/plumb-*.jar
#     conf/default-config.yaml
#     conf/logback.xml
#     README.txt
```

### 7.3 Unpack and run (with a JRE)

Requires a local JDK 17+ with `jlink` (which ships with the JDK).

```bash
./scripts/dist-jlink-linux.sh             # once it has been written
# → dist-jlink/plumb-0.1.0-SNAPSHOT-jre-linux-x64.tar.gz
#   a jlink minimal JRE + the shaded jar + the launchers
# the user's machine needs no Java environment at all
```

Windows:

```powershell
.\scripts\dist-jlink-windows.ps1
# → dist-jlink\plumb-0.1.0-SNAPSHOT-jre-windows-x64.zip
```

---

## 8. Tests

```bash
# Unit tests (the R-series integration tests are skipped by default; about 5 min)
mvn -pl plumb-dfa test -Dtest='!R*'

# Everything (including the R* integrations, which need the Juliet fixtures; about 10 min)
mvn test

# A single module
mvn -pl plumb-style test
mvn -pl plumb-ai test
```

---

## 9. License

MIT -- see [`LICENSE`](LICENSE).

Every Java source carries the header, and the gate `check_license`
enforces it: the header has to be the first thing in the file, it has
to name the same copyright holder and year the `LICENSE` file does,
and the generator has to emit it too -- otherwise the next
regeneration would silently drop it.

```bash
python scripts/add_license_headers.py            # put the header on sources that lack it
python scripts/add_license_headers.py --check    # report the sources that lack it
```

The header text lives in exactly one place, `scripts/license_header.py`.
The applier, the gate and the generator all read it from there, so the
three cannot disagree about what the header says.

---

## 10. Maintenance

| Dimension | Status |
| --- | --- |
| Upstream | An independent project |
| Maintainers | The Plumb team |
| Known limitations | See each category's `testcase/*/EXPECTATIONS.json` and [`testcase/SUPPORT-MATRIX.md`](testcase/SUPPORT-MATRIX.md) |

The roadmap and the known limitations live in
`docs/tech/ARCHITECTURE.md` and `testcase/SUPPORT-MATRIX.md`.
