# Plumb CLI User Guide

[English](../README.md) | [中文](../zh/usage/CLI.md)

This document is the complete usage guide for the Plumb command line tool
`sast-scan`.

## 1. Quick start

```bash
# Launcher (POSIX)
./dist/plumb-0.1.0-SNAPSHOT/bin/sast-scan --help

# Or use the jar directly
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar --help

# Minimal scan (using the built-in rules)
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
     -i testcase/01-taint-crossfile/src/ChainOrchestrator.java

# Configuration-file driven
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
     -c testcase/config/min.yaml -i src/main/java
```

## 2. All command line arguments

The output of `sast-scan --help` is:

```
usage: sast-scan [--ai-dedup] [--ai-dedup-stats] [--build-system
       <maven-resolver|maven|gradle|bazel|ant>] [-c <yaml>] [--cache-mode
       <off|read|write|readwrite>] [--capture-dir <dir>] [--cg-strategy
       <cha|rta|vta|1cfa|stub|global|cached:<name>:<path>>] [--classpath <jar|dir>] [-D <dir>]
       [--dsl-rules <file>] [--dsl-strict] [-f <console|ndjson|sarif>] [-h] [-i <path>]
       [--list-rules] [--max-parallelism <n>] [--module-root <dir>] [-o <path>] [--project-root
       <dir>] [--rule <id>] [--rule-prompts-dir <path>] [--source-root <dir>] [-v]
```

| Short option | Long option | Argument | Default | Description |
| --- | --- | --- | --- | --- |
| `-h` | `--help` | — | — | Print the help and exit |
| `-v` | `--version` | — | — | Print the version and exit |
| `-c` | `--config` | `<yaml>` | — | Load the scan configuration from YAML (overrides / extends the command line flags) |
| `-i` | `--input` | `<path>` | — | Input file or directory, **repeatable**. Supports `.java`, `.jar`, `.class`, `.war`, `.ear`, `.zip` |
| `-o` | `--output` | `<path>` | `stdout` | Report output file |
| `-f` | `--format` | `console` \| `ndjson` \| `sarif` | `console` | Output format |
| `-D` | `--output-dir` | `<dir>` | — | Report + cache output root directory. Sub-directories: `cache/programs/*.tac`, `report/findings.{ndjson,console}` |
| — | `--cache-mode` | `off` \| `read` \| `write` \| `readwrite` | `off` | Usage mode of the on-disk IR cache |
| — | `--cg-strategy` | See the table below | `global` | Call-graph construction strategy, passed to the `plumb-dfa` engine |
| — | `--max-parallelism` | `<n>` | `nCPU-1` (minimum 1) | Upper bound of concurrency in the parse + translate phase |
| — | `--ai-dedup` | — | off | FP reducer switch. Sends every finding to the LLM to judge TP/FP/UNKNOWN. The verdict is written to the `ai_verdict` + `ai_reason` fields of the NDJSON. The LLM endpoint + key are configured in YAML (`ai.apiKey` / `ai.baseUrl` / `ai.model`) |
| — | `--ai-dedup-stats` | — | off | After the AI dedup run, prints the verdict distribution + the top 10 rules by FP ratio to **stderr**. The NDJSON on stdout is unchanged. Use it together with `--ai-dedup`; on its own it is a no-op. |
| — | `--rule` | `<id>` | — | Run only the given rule (**repeatable**). Overrides the `checks.*` flags from YAML. **Exact match** -- you have to use a real rule id, for example `--rule TAINT_SINK_SQL_INJECTION`; a non-existent id silently produces 0 findings. Use `--list-rules` to look up all ids |
| — | `--rule-prompts-dir` | `<path>` | — | Directory of user-supplied rule prompt `.md` files. Overrides the default prompts packaged on the classpath |
| — | `--dsl-rules` | `<file>` | — | AST rule file of the Kotlin DSL engine (**repeatable**). In XML or YAML format, see [`../tech/KOTLIN-DSL.md`](../tech/KOTLIN-DSL.md) |
| — | `--dsl-strict` | — | off | **Abort the scan** when a DSL rule fails to compile, instead of skipping that rule |
| — | `--list-rules` | — | — | List all known rule ids and exit |

** / project path family** (see the "Project path family" table in §2):

| Short option | Long option | Argument | Default | Description |
| --- | --- | --- | --- | --- |
| — | `--source-root` | `<dir>` | — | Source root for cross-file type resolution (**repeatable**). Treated as a `JavaModule`; it only feeds `JavaSourceParser.TypeSolver`, and **does not enter the TAC pipeline and does not produce findings**. It is used to feed third-party sources into symbol resolution, but is not indexed by `cache/programs/` |
| — | `--module-root` | `<dir>` | — | Same as `--source-root`; the semantics are separated so that multi-module projects can list them per module |
| — | `--classpath` | `<jar\|dir>` | — | Third-party dependency jars or class directories (**repeatable**). They only feed the TypeSolver and are **never translated into TAC IR**. The only purpose of the third-party jars is to let the user sources resolve references to external types |
| — | `--project-root` | `<dir>` | — | The project root passed to the build-system adapter |
| — | `--build-system` | `maven-resolver\|maven\|gradle\|bazel\|ant` | — | Explicitly pick the build-system adapter. When absent it is auto-detected from `pom.xml` / `build.gradle` / `BUILD` / `build.xml`. `maven-resolver` computes the **complete transitive dependency tree** via Maven Resolver and takes priority over `maven` during auto-detection; `maven` only takes the dependencies declared directly in the pom, which is lighter and starts faster |
| — | `--capture-dir` | `<dir>` | — | Directory of javac captures (the `plumb-build-capture` agent's output) to cross-check against what the build declared. The two answer the same questions from two sides — language level, encoding, classpath — and a disagreement means one of them is wrong. The differences are **reported** on stderr, with both values; nothing is silently resolved. Needs `--project-root` and the `maven-resolver` adapter |
| — | `--format` | `console\|ndjson\|sarif` | `console` | Output format (`sarif` emits SARIF 2.1.0, for IDE / GitHub Code Scanning integration) |

`--output-dir` and `--cache-mode` are **not required to be used together**, but
to be meaningful they should be:

- Only `--output-dir` given: `ScanRunner` still creates the `cache/` + `report/`
  directory tree (`OutputLayout.ensureDirectories()`), but `--cache-mode`
  defaults to `off`, so the cache directory stays empty
- Only `--cache-mode readwrite` given: nothing is written to disk -- the cache
  path is determined by `--output-dir`, and without it there is no cache
  directory to read or write
- Both given: `--cache-mode` decides the read/write behaviour of
  `cache/programs/*.tac`

### 2.1 AI behaviour of a single rule

> **Current model**: every `DfaChecker` / `AstChecker` holds its own
> `getAiPrompt(Finding) -> String`. Returning `null` = the rule **does not**
> go through AI dedup; returning non-null = the LLM receives this rule's
> prompt + the finding details + the dataflow trace + the source window, and
> outputs TP / FP / UNKNOWN.
>
> The prompt of a rule is held by the checker by default. 23 files in
> `plumb-dfa` mention `getAiPrompt` (of which 21 are real overrides, and 2 are
> the interface declaration of `DfaChecker` and the call in `DfaEngine`), for
> example the individual checkers under
> `plumb-dfa/src/main/java/org/plumb/dfa/checkers/...`; the `plumb-style`
> side has 3 overrides: `style/BadShiftCheck.java`,
> `error/EmptyCatchBlockCheck.java`,
> `security/HardcodedCredentialsCheck.java`.
> The user's `--rule-prompts-dir` can still override (a `<rule-name>.md` file
> overrides the inline `getAiPrompt` return value of the matching checker).

### 2.2 AI integration

The `plumb-ai` module provides a Re-Act multi-turn LLM Agent (works with
OpenAI-compatible / Anthropic), which runs over every finding once
`--ai-dedup` is enabled. The switches all live in the `ai.*` block of the
YAML:

```yaml
ai:
  dedup: false                  # FP reducer (CLI: --ai-dedup)
  dropOnAiFp: false             # false=keep the FP mark, true=drop it
  dedupRules: []                # empty=every rule is reviewed; filled=only these

  provider: openai              # openai | anthropic | openai_compatible
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}     # ${ENV_VAR} syntax
  model: gpt-4o-mini
  header:                       # optional extra request headers
  timeoutSeconds: 30
  maxConcurrent: 4              # number of concurrent dedup requests
  temperature: 0.0              # 0 = deterministic FP judging
```

`ai.dedupRules` accepts **two spellings** of a rule, both are recognized:

- slug (the `name()` of the checker, which is also the name shown in the rule
  catalogue) -- `bc-equals-no-null`
- canonical emit id (the `rule_id` carried in the finding) --
  `ast-bug-bc-equals-no-null`

The override files in `--rule-prompts-dir` work the same way: naming the file
after the slug is enough.

**Removed keys** (`ConfigLoader` prints a warning and ignores them):

| key | Replacement |
| --- | --- |
| `ai.enabled` | CLI `--ai-dedup` |
| `ai.scan` | None (AI only does FP judging) |
| `ai.contextLines` | None (the source window is hardcoded to 3 lines in the runner) |
| `ai.rulePromptsDir` | CLI `--rule-prompts-dir` |
| `ai.sendAstContext` | None |

CLI shorthand:

- `--ai-dedup` = `ai.dedup: true` (`SastMain` only sets `cfg.aiDedup = true`;
  there is **no** implicit `ai.enabled` switch)

**Agent -- FP reducer** (`AgentPrompts.FP_REDUCER`)

Input: rule prompt (from `DfaChecker.getAiPrompt` / `StyleCheck.getAiPrompt` / `AstChecker.getAiPrompt`) + finding details + source window + dataflow trace
Output: `{"verdict":"TP|FP|UNKNOWN","reason":"..."}`
Purpose: label static findings as TP/FP, and drop or mark the FPs

**Three-level rule prompt override chain**:
1. `TaintedCheckerBase` generates it automatically from the `TaintRule` metadata (ruleId / cwe / severity / source / sink / domain)
2. Hand-written inline `getAiPrompt` in DfaChecker / AstChecker (a few high-value rules)
3. The user's `.md` override via `--rule-prompts-dir` (highest priority)

Returning null = the rule does not take part in AI dedup (the prompt `(no-rule-prompt)` counts as UNKNOWN).

#### Complete example

```bash
# 1. Default (minimal)
bin/sast-scan -c conf/default-config.yaml -i src

# 2. AI false-positive reduction (recommended for auditing)
OPENAI_API_KEY=sk-... \
bin/sast-scan -c conf/default-config.yaml -i src \
    --ai-dedup

# 3. SQL injection only
#    --rule is an exact match: you have to use a real rule id (TAINT_SINK_SQL_INJECTION);
#    SQL_INJECTION matches no rule at all and the scan silently produces 0 findings.
bin/sast-scan -c conf/default-config.yaml -i src --rule TAINT_SINK_SQL_INJECTION

# 4. Bring your own rule prompt override
bin/sast-scan -c conf/default-config.yaml -i src \
    --ai-dedup --rule-prompts-dir ./my-rule-prompts/
```

### 2.3 Re-Act multi-turn tool calling

`--ai-dedup` uses a **Re-Act multi-turn loop** by default (not a single
question-and-answer round) -- before judging TP/FP the LLM can call 12 tools
to look at more code, check whether the caller has a sanitizer, and check
whether there is a guard on the trace path:

| Tool | What it does | IR dependency | When to use it |
|------|------|--------|--------|
| `read_source` | Read file lines L\<start\>..L\<end\> | ✗ | The LLM wants code outside the ±5 line window |
| `grep` | regex-grep files / directories | ✗ | Search a pattern across files |
| `glob` | Find files by glob on the file name | ✗ | "Find the test file of UserService" |
| `list_dir` | List a directory (depth 0-3) | ✗ | Inspect the package structure, locate a class |
| `search_pattern` | Search a symbol with a camelCase 4-variant regex | ✗ | "Where is constantFoo defined / used?" |
| `find_definitions` | Find the definition site of a var / method | ✓ (Grep fallback) | Trace `q = ... + userInput` back to its source |
| `find_callers` | Find all call sites of a method | ✓ (Grep fallback) | Verify whether a caller sanitizes |
| `has_sanitizer_on_path` | Check whether there is a guard / sanitizer in [from..to] | ✓ (Grep fallback) | Decide a FP in one shot |
| `get_method_annotations` | Read @Annotation on class/method/param | ✓ (regex fallback) | "Is this finding in an @PreAuthorize method?" |
| `get_class_hierarchy` | Find extends / implements | ✗ | "Does this controller extend TrustedService?" |
| `get_full_class` | Read the whole .java source by class name | ✗ | "Look at the controller's full surface" |
| `get_dataflow_trace_with_facts` | Re-format the dataflow trace + Z3 facts | ✗ | "Is the BRANCH at L7 on the trace path a sanitizer?" |

There are **13** concrete tool classes under `plumb-ai/.../tools/`, of which
**12** are registered in `ScanRunner.buildToolRegistry()` (exactly the 12 in
the table above). The remaining one is `GetCallHierarchyTool` (recursively
walks the caller chain, BFS depth ≤ 4) -- it is **not registered** in
`ScanRunner.buildToolRegistry()`, so the LLM behind `--ai-dedup` cannot get
it.

`grep` / `glob` / `list_dir` / `search_pattern` / `get_class_hierarchy` /
`get_full_class` / `get_dataflow_trace_with_facts` **do not depend on AST/TAC
at all** -- like the Read/Grep/Glob/Bash of Claude Code. The three `find_*`
tools use the precise TAC call graph when IR is available and automatically
fall back to raw-text regex when there is no IR, so **the tool chain never
breaks**. `read_source` always reads from disk only.

A Re-Act run over one finding is roughly 1-3 LLM calls (FP reducer max 5
turns / bug scanner max 8 turns), and the framework stops on one of 4
conditions:

1. The LLM outputs `FINAL: {...}` -- the happy path
2. `maxTurns` is exceeded -- the LLM is stuck in a tool-call loop, marked `UNKNOWN`
3. The provider is null -- network / auth failure, marked `UNKNOWN`
4. A tool throws -- the error message is fed back to the LLM so it can adapt

**Re-Act cannot be turned off from YAML.** The `ai:` block has no switch like
`reactive` -- `ConfigLoader` only recognizes `provider` / `baseUrl` /
`apiKey` / `model` / `header` / `timeoutSeconds` / `maxConcurrent` /
`temperature` / `dedup` / `dropOnAiFp` / `dedupRules`. `--ai-dedup` is the
Re-Act multi-turn loop; there is no single-turn mode to switch to.

**plumb-ai is a compile-time dependency of plumb-main**: `plumb-main`
directly imports `org.plumb.ai.agent.*` to construct `LlmProvider` /
`ReActAgent` / `ToolRegistry`, with no reflection. The `plumb-ai` module
itself only depends on `plumb-rules` + `plumb-reporter` (not `plumb-dfa`),
which keeps the agent framework shell cleanly scoped.

What is optional is the **runtime behaviour**, not the dependency itself:
without `--ai-dedup` the static analysis does not depend on any LLM call and
runs to completion as usual.

**Slim dist** (generated with `pwsh scripts/package-slim.ps1`):
- bin / conf / lib are the same as in the full dist
- `conf/default-config.yaml` is rewritten to `ai.dedup: false` (the real AI
  gate; `ai.enabled` was removed from the schema long ago)
- the all-in-one jar contains the plumb-ai classes (turned off), but the
  runner short-circuits the AI passes
- completely stripping the plumb-ai classes is impossible: the field type of
  `ScanConfig` is `org.plumb.ai.agent.LlmConnection`, which `plumb-main`
  needs at compile time. What you can do is turn the AI gate off (which is
  what the slim dist does), or fork the repository and delete both the
  plumb-ai module and the AI-related code in `plumb-main`.

**Real LLM E2E**: set the `PLUMB_AI_E2E=1` env var and run `mvn -pl plumb-ai
test` -- the 5 tests of `LlmIntegrationTest` really call the LLM endpoint
(default `https://api.minimax.chat` / `MiniMax-Text-01`). This verifies that
the Re-Act multi-turn tool calling protocol works against a real LLM (FP
reducer 1-shot + read_source tool call). The dev inner loop does not need
this -- the 5 LLM tests are skipped by default via
`@EnabledIfEnvironmentVariable`.

**Why not Spring-AI**: `plumb-ai` uses plain `java.net.http` + Jackson, the
client code is ~50 lines, and the OpenAI function-calling JSON is assembled
directly. Spring-AI / LangChain4j add 3MB of Spring dependencies + a call
stack 6-8 levels deep, and the debugging print cannot reach the raw reply.
See `docs/tech/CROSS-FUNCTION-AI-TRACE.md` §14 for the detailed
implementation.

### 2.4 AI dedup report (`--ai-dedup-stats`)

`--ai-dedup` sends every finding to the LLM to judge TP/FP, but the verdict
is buried in the `ai_verdict` field of each NDJSON record -- **there is no way
to see the overall AI performance at a glance**. `--ai-dedup-stats` is
exactly for that: after the AI dedup run it prints a verdict distribution +
the top 10 rules by FP ratio to **stderr**. The NDJSON on stdout stays clean
and can still be piped to a downstream consumer.

```bash
bin/sast-scan -c conf/default-config.yaml -i src \
    --ai-dedup --ai-dedup-stats 2>ai-stats.txt >findings.ndjson
```

The output looks like this:

```
AI dedup stats
==============
Total findings:   297
Reviewed:         297 (100.0%)
Dropped (FP):     158
Kept:             139
Elapsed:          6m14s

Verdict distribution:
  TP            135  (45.5%)
  FP            158  (53.2%)
  UNKNOWN         4  ( 1.3%)

Top 10 rules by FP ratio (FP / total):
  StaticApiKey                    8/10 (80.0%)
  HardcodedCredential             6/8  (75.0%)
  ...
```

Meaning of each field:

| Field | Meaning |
|------|------|
| `Total findings` | Total number of findings produced by the static rules |
| `Reviewed` | The ones actually sent to the LLM (generally = total, unless the dedupRules allow-list filtered them) |
| `Dropped (FP)` | Number of FPs dropped because `ai.dropOnAiFp: true` |
| `Kept` | Number of findings that made it into the final report |
| `Elapsed` | Total time spent in the AI dedup phase (ms / s / m+s) |
| `Verdict distribution` | The share of TP / FP / UNKNOWN |
| `Top 10 rules by FP ratio` | The 10 rules with the highest FP ratio |

`UNKNOWN` usually comes from: network timeouts, the model failing to produce
JSON, or the model producing a verdict that is neither TP nor FP (normalized
to UNKNOWN). If `UNKNOWN` is high, look at the `ai_reason` field at the end of
stderr.

**Implementation details**: see `docs/tech/CROSS-FUNCTION-AI-TRACE.md` §14.
`--ai-dedup-stats` without `--ai-dedup` is a no-op; it is only useful
without `--ai-dedup` when the YAML sets `ai.dedup: true`.

### 2.5 Call-graph strategies (`--cg-strategy`)

The call-graph construction strategy passed to the `plumb-dfa` engine.
Available values:

| Strategy | Type | Speed | Precision | Notes |
| --- | --- | --- | --- | --- |
| `vta` | Variable Type Analysis | Slow | Tracks the assignment type of variables, more precise than RTA | TU-local strategy. Suits large projects with much dynamic dispatch |
| `cha` | Class Hierarchy Analysis | **Fastest** | Over-approx (accepts all same-name + same-arity) | For large repositories where precision does not matter; the `MergedProgram` merged view is enough across TUs |
| `rta` | Rapid Type Analysis | Medium | More precise than CHA (drops unreachable receivers) | Slightly slower, needs a receiver type set |
| `1cfa` | 1-CFA context-sensitive | Slowest | Distinguishes calls to the same method from different call sites | Suits library code / framework-internal analysis |
| `stub` | direct calls only | **Fastest** | Only looks at direct calls, ignores virtual dispatch | For debugging / testing only |
| `global` | cross-TU signature lookup | Medium | Does not depend on the type hierarchy, looks up directly by `calleeSig` | **The default**. Cross-file calls are resolved through `GlobalTACStore`; the `DfaEngine.runWithGlobalStore` entry point |
| `cached:<name>:<path>` | A disk wrapper around any of the strategies above | — | — | Serializes the result of the strategy `<name>` to `<path>`, the cache key is a hash of the source-file set. The second run on the same project reads straight from disk |

**YAML equivalent**:

```yaml
cgStrategy: rta                # camelCase (recommended)
cg-strategy: vta               # snake_case is also accepted
```

The validation happens in `ScanConfig.validate()`; an unknown strategy name
or a malformed `cached:...` raises an `IllegalArgumentException` at startup
and prints the available values.

See `docs/tech/CROSS-FUNCTION-ANALYSIS.md` §2.1 for a comparison of the "two
paths".

## 3. YAML configuration in detail

The YAML file given with `-c`, where **the command line wins when it overlaps
with a command line flag** (the flags are processed after the ConfigLoader).
The complete schema:

```yaml
# ---- input ----
inputs:                       # merged with the --input list
  - src/main/java
  - /opt/projects/foo/src

input: src/main/java          # single-input shorthand, cannot be given together with inputs

include:                      # path allow-list (glob substring, OR relation)
  - "/com/company/"

exclude:                      # path deny-list (glob substring, AND relation, takes priority)
  - "/test/"
  - "/generated/"

# ---- output ----
output:
  path: build/sast.ndjson     # equivalent to --output
  format: ndjson              # console | ndjson

outputDir: build/plumb-out    # equivalent to --output-dir; the snake_case spelling is output-dir
cacheMode: readwrite          # off | read | write | readwrite; the snake_case spelling is cache-mode
maxParallelism: 4             # concurrency upper bound; the snake_case spelling is max-parallelism
cgStrategy: rta               # equivalent to --cg-strategy, see §2.5 Call-graph strategies

# ---- rule switches ----
rules:
  enabled:                    # force-enable (even if the default is off)
    - bu-0401-weak-crypto
    - EXTENDS_OBJECT          # either the rule ID or the rule name
  disabled:                   # force-disable
    - STYLE_SYSTEM_OUT
    - pl-0006-system-out

# ---- checker families ----
# Both spellings are accepted:
#
#   (a) simple bool -- for the families that only need a switch
checks:
  taint: true                 # master switch for the 13 taint rules
  style: true                 # all AST style rules
  divByZero: true
  # ...
#
#   (b) sub-dict -- for when you also want to configure aiDedup or an explicit enabled
checks:
  divByZero:           {enabled: true,  aiDedup: true}
  resourceLeak:        {enabled: true,  aiDedup: true}
  nullDeref:           {enabled: true,  aiDedup: true}
  hardcodedCred:       {enabled: true,  aiDedup: true}
  intOverflow:         {enabled: true,  aiDedup: true}
  intUnderflow:        {enabled: true,  aiDedup: true}
  arrayBounds:         {enabled: true,  aiDedup: true}
  weakCrypto:          {enabled: true,  aiDedup: true}
  insecureRandom:      {enabled: true,  aiDedup: true}
  predictableSeed:     {enabled: true,  aiDedup: true}
  predictableKey:      {enabled: true,  aiDedup: true}
  staticApiKey:        {enabled: true,  aiDedup: true}
  insecureComms:       {enabled: true,  aiDedup: true}
  corsMisconfig:       {enabled: true,  aiDedup: true}
  insecureCookie:      {enabled: true,  aiDedup: true}
  badShift:            {enabled: true,  aiDedup: true}
  infiniteLoop:        {enabled: true,  aiDedup: true}
  constantCondition:   {enabled: true,  aiDedup: true}
  sensitiveDataLeak:   {enabled: true,  aiDedup: true}
  fieldHardcodedPassword: {enabled: true, aiDedup: true}
  missingAuthorization:   {enabled: true, aiDedup: true}
  missingInputValidation: {enabled: true, aiDedup: true}
  informationExposure:     {enabled: true, aiDedup: true}
  uncheckedReturn:         {enabled: true, aiDedup: true}
  incorrectReturn:         {enabled: false, aiDedup: true}   # style-ish
  threadRun:               {enabled: false, aiDedup: false}  # style
  stringEquality:           {enabled: true,  aiDedup: true}
  catchGeneric:            {enabled: false, aiDedup: false}  # style
  obsoleteFunction:        {enabled: true,  aiDedup: true}
  tryCatchFinally:         {enabled: true,  aiDedup: false}
  offByOne:                {enabled: true,  aiDedup: true}
  equalsCalledOnNull:      {enabled: true,  aiDedup: true}
  compareWithSelf:         {enabled: true,  aiDedup: true}
  xxe:                     {enabled: true,  aiDedup: true}
  returnFromFinally:       {enabled: true,  aiDedup: false}
  emptyCatchHandler:       {enabled: true,  aiDedup: true}
  iteratorConcurrentModification: {enabled: true, aiDedup: true}
  collectionSelfReference:  {enabled: true, aiDedup: false}
  uncaughtExceptionPropagator:    {enabled: true, aiDedup: false}
  uncheckedLoopCondition:   {enabled: true, aiDedup: true}
  uncontrolledMemAlloc:    {enabled: true, aiDedup: true}
  numericTruncation:       {enabled: true, aiDedup: true}
  resourceExhaustion:      {enabled: true, aiDedup: true}  # CWE-400
  # the two DfaChecker ports migrated over from plumb-dfa
  bigDecimalDoubleConstructor: {enabled: true, aiDedup: true}  # CWE-682
  defaultEncoding:             {enabled: true, aiDedup: true}  # CWE-176
  errorMessageInfoLeak: {enabled: true, aiDedup: true}
  commentInfoLeak:      {enabled: true, aiDedup: true}
  assignmentInOperand:  {enabled: true, aiDedup: true}   # accepted but ignored, see below

# checks.assignmentInOperand is a key that is **accepted but ignored**: it belongs
# to the style family, and switching it off on its own makes no sense. If you write it,
# ConfigLoader prints a warning to stderr, but the scan continues as usual. To really
# turn it off, use the AST rule id:
#   rules:
#     disabled:
#       - ast-style-assignment-in-operand
#
# The two keys errorMessageInfoLeak / commentInfoLeak are parsed by ConfigLoader,
# and they **still switch something**: they take part in the ScanRunner decision on
# whether to run the unified DFA layer; setting both to false makes that layer be
# skipped entirely.
# To turn off just these two rules, use the AST rule ids:
#   rules:
#     disabled:
#       - ast-error-error-message-info-leak
#       - ast-style-comment-info-leak

# ---- Kotlin DSL AST rules ----
# AST rules (syntactic shapes, local patterns) are written in the Kotlin DSL;
# dataflow / taint rules still live in the YAML of plumb-dfa, not here.
# See tech/KOTLIN-DSL.md for details.
dsl:
  rules:                        # list of rule files (XML or YAML format)
    - rules/ast-rules.yaml
  strict: false                 # true = abort the scan when a rule fails to compile

# ---- project paths ----
# — used together with the build-system adapter. See the "project path family"
# table in §2.
inputs:
  - src/main/java              # main input (same as --input, repeatable)
sourceRoots:
  - /opt/dependency-sources    # third-party sources (TypeSolver only, not into the TAC)
moduleRoots:
  - /opt/multi-module/m1       # same as sourceRoots, separated for UX semantics
classpathJars:
  - /opt/lib/spring-core.jar   # third-party jars (TypeSolver only, not into the TAC)

# ---- build system auto-discovery ----
# There is no buildSystem block in the YAML. The build system can only be selected
# through the CLI:
#   --build-system maven --project-root /path/to/proj
# These two flags are handled by SastMain.applyBuildSystem() (ConfigLoader does not
# parse them); the inputs/sourceRoots/moduleRoots/classpathJars filled in by the
# adapter are merged into the scan inputs.

# ---- style thresholds ----
# style.methodLengthThreshold has been removed: it was a dead field that no checker
# read. Checker-level thresholds will return to the style: block later on.
```

### 3.1 camelCase vs snake_case

`ConfigLoader` accepts both camelCase and snake_case YAML keys, with camelCase
taking priority. This is a compatibility patch that keeps old configurations
working.

```yaml
checks:
  intOverflow: true    # recommended
  int_overflow: true   # compatibility, still recognized
```

The top-level keys have aliases as well; the **left** key in the table has
priority (whichever is matched first wins):

| Priority | Alias |
| --- | --- |
| `outputDir` | `output-dir` |
| `cacheMode` | `cache-mode` |
| `maxParallelism` | `max-parallelism` |
| `cgStrategy` | `cg-strategy` |
| `classpath` | `classpathJars` |
| `source_roots` | `sourceRoots` |
| `module_roots` | `moduleRoots` |
| `ai` | `aiDedup` / `ai-dedup` |

Note that `classpath` and `classpathJars` are a pair, and **`classpath` has
priority**.

## 4. Enabling / disabling rules

### 4.1 Family switches (`checks:`)

Enable or disable a whole class of checkers at once with
`checks.<name>: true|false`. For example:

```yaml
checks:
  taint: false         # turn off all 13 taint rules
  style: false         # turn off all AST style rules
  divByZero: false     # turn off DIV_BY_ZERO
```

When a key is not written the default is used (**almost all defaults are
true**, only a few are off -- see the `ScanConfig` field initialization).

### 4.2 Fine-grained: `rules.enabled` / `rules.disabled`

`rules.enabled` **forces** the listed rules on; `rules.disabled` forces them
off. Both **rule UUIDs** (such as `bu-0201-style-empty-catch`) and **rule
names** (such as `STYLE_EMPTY_CATCH`) are accepted:

```yaml
rules:
  disabled:
    - STYLE_SYSTEM_OUT          # turn off the println hint
    - pl-0006-system-out        # same thing (both spellings work)
  enabled:
    - bu-0401-weak-crypto       # force on even if the family is off
```

Actual behaviour: at the rule registration stage (after
`RuleRegistry.withBuiltinDefaults()`) the `enabled` flag is set again. The
code path is in `ScanRunner.buildRegistry()`:

```java
if (config.disabledRuleNames.contains(def.ruleId().name())
        || config.disabledRuleNames.contains(def.ruleId().id())) {
    enabled = false;
}
```

### 4.3 Temporary disabling from the command line

The CLI has no native `--disable` flag, but you can use a small shell alias:

```bash
# Parse every -D rules.disabled=ID into a YAML fragment and pass it in
cat > /tmp/disable.yaml <<EOF
rules:
  disabled: [$1]
EOF
java -jar plumb-...-all.jar -c /tmp/disable.yaml -i src/main/java
```

### 4.4 Changing rule parameters

Every `RuleDef` can carry a `RuleParameter` (see
`plumb-rules/src/main/java/org/plumb/rules/RuleParameter.java`).
**Runtime parameter overrides on the YAML side are supported**, via the path
`checks.<rule-id>.params.<param-name>`:

```yaml
checks:
  line-length:
    enabled: true
    params:
      max: 120                  # overrides the default value 100 of LineLengthCheck in the source
      ignoreComments: false
```

The chain: `ConfigLoader.fromMapImpl` collects it into `ScanConfig.ruleParams`
(the outer key = the rule id, the inner key = the parameter name, the value
keeps the YAML literal type String / Integer / Boolean), `ScanRunner` passes it
in when constructing the `StyleContext`, `AstDispatcher` calls
`setCurrentRule(checker.name())` before each checker's `init()` / `visit()`,
and the checker reads it via `ctx.paramOverride(name, type)`. When an override
matches, the override is used; when it does not, the default declared in
`RuleDef.parameters()` is used.

> The outer key must be the checker's `name()`, i.e. the kebab-case slug
> (`line-length`).
> **Neither the canonical rule id (`ast-style-line-length`) nor the short
> name (`LINE_LENGTH`) works** -- the lookup is a direct key concatenation of
> `checker.name() + "." + paramName`, with no alias resolution.

When no override matches, the default declared in `RuleDef.parameters()` is
used. This fallback path resolves the rule by slug: the slug of every checker
points in the `RuleRegistry` to **its own rule**, so any default declared by
a checker can be read through its own slug. The mapping between slug and rule
name is guaranteed by the registry, and does not depend on every checker
manually writing its slug into its own alias list.

Only the checkers of plumb-style use this path -- the other rule types that
declare a `RuleParameter` in `plumb-rules` but have no `StyleContext` context
still use the hard-coded default in the code.

#### 4.4.1 `aggressiveness` is deprecated

`aggressiveness: low|medium|high` is no longer recognized.
- The CLI has no `--aggressiveness` flag (it was removed).
- When you write `aggressiveness:` in the YAML, `ConfigLoader.fromMapImpl`
  prints to stderr a
  `[plumb] warning: 'aggressiveness' in YAML is no longer supported; ...`,
  **but the scan continues as usual** -- this is not a fatal error.
- The replacement: the `checks.<rule>: {enabled: true|false}` block or the
  `--rule <id>` command line filter.

## 5. Output formats

### 5.1 NDJSON (the default for `-f ndjson`)

One JSON finding per line:

```json
{"file":"src/main/java/Foo.java","line":42,"column":5,"severity":"high",
 "rule_id":"bu-0101-taint-sink-sql","rule_name":"TAINT_SINK_SQL_INJECTION",
 "rule_type":"taint","cwe":["CWE-89"],
 "message":"Tainted id flows into executeQuery (...)",
 "dataflow":"id:String -> [bu-0001-taint-request-param] -> executeQuery/1"}
```

Meaning of each field:

| Field | Type | Description |
| --- | --- | --- |
| `file` | string | Path relative to the current working directory |
| `line` | int | 1-based line number (`"-1"` means it cannot be located) |
| `column` | int | 1-based column number |
| `severity` | enum | `info`/`low`/`medium`/`high`/`critical` |
| `rule_id` | string | Rule UUID, e.g. `bu-0101-taint-sink-sql` |
| `rule_name` | string | Short name, e.g. `TAINT_SINK_SQL_INJECTION` |
| `rule_type` | enum | `taint` / `style` / `naming` / `correctness` / `security` / ... |
| `cwe` | string[] | The related CWE numbers |
| `message` | string | Human-readable description |
| `dataflow` | string? | The dataflow chain (only for taint / DFA rules) |

### 5.2 console

The format of each line:

```
src/main/java/Foo.java:42:5  [HIGH] TAINT_SINK_SQL_INJECTION  Tainted id flows into executeQuery (...)  [id:String -> [bu-0001-taint-request-param] -> executeQuery/1]
```

### 5.3 Output directory layout (`--output-dir`)

```
<outputDir>/
├── cache/
│   ├── programs/
│   │   ├── <sha1>.tac           one per source file
│   │   └── ...
│   ├── merged.tac               the merged MergedProgram (optional)
│   └── callgraph.tac            the global call graph (optional)
└── report/
    ├── findings.ndjson          the findings actually written (if -f ndjson)
    └── findings.console         human-readable (if -f console)
```

See `docs/build/PACKAGE.md` §5 for details.

## 6. Exit codes

| Exit code | Meaning |
| --- | --- |
| `0` | The scan succeeded (**whether or not findings were produced**) |
| `2` | Argument / configuration error (on the user side) |
| `3` | An uncaught exception occurred during the scan (runtime bug) |

Note: Plumb does not provide exit code gating like `-fail-on-high` -- wiring
the findings into a CI decision is left to the caller.

## 7. Environment variables

The engine itself reads only two environment variables (see
[`ENVIRONMENT.md`](ENVIRONMENT.md)):

| Variable | Purpose |
| --- | --- |
| `OPENAI_API_KEY` | The default when `ai.apiKey` is not set in the YAML |
| Any `${VARNAME}` written in the YAML | An explicit reference, e.g. `ai.apiKey: ${MY_KEY}` |

**JVM arguments and the log level do not go through environment variables.**
They are decided by the launcher script:

| Variable | Read by | Purpose |
| --- | --- | --- |
| `PLUMB_JAVA_OPTS` | `scripts/dist-jlink-windows.ps1` / `scripts/dist-jlink-linux.sh` | Passed through to the JVM, defaults to `-Xmx4g -XX:+UseG1GC` |
| `PLUMB_DEBUG` | Same as above | Appends `-Droot.level=DEBUG` when set to `true` |
| `JAVA_HOME` | Same as above | Locating `java` |

The log level itself is written in `plumb-main/src/main/resources/logback.xml`
(root = `ERROR`, `org.plumb` and `org.plumb.ai` = `DEBUG`); there is no
`LOG_LEVEL` environment variable.

None of these variables take effect when you run `java -jar ...` directly; you
have to add the JVM arguments yourself:
`java -Xmx4g -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar -i src`.

## 8. Troubleshooting

| Symptom | Solution |
| --- | --- |
| `java.lang.OutOfMemoryError: Java heap space` | When you use the launcher, set `PLUMB_JAVA_OPTS=-Xmx4g`; when you run `java` directly, add the `-Xmx4g` argument |
| Every finding says `Parse problem` | Check whether the Java source conforms to the JavaParser 17 grammar; verify by compiling with javac 17 |
| `--output-dir` reports `Permission denied` | The directory is not writable; use `chmod` or pick another directory |
| `--cache-mode readwrite` reports `Cache read failure` | The cache file is corrupt; delete `<output-dir>/cache/` and rerun |
| `--max-parallelism 0` runs slower than sequential | This is normal for fewer than 800 files (the ForkJoin scheduling overhead outweighs the gain) |
| `--source-root` is given but no findings appear | Expected behaviour: source-root only feeds the TypeSolver and does not enter the TAC pipeline. Put the sources you want to scan under `--input` |
| No change after `--classpath /path/spring.jar` | Expected behaviour: the classpath is for JavaParser to resolve types, not to be scanned as input. To scan the classes inside a jar, use `--input /path/spring.jar` |
| `--build-system maven` reports "no adapter found" | Check whether `pom.xml` is under the `--project-root` path; or switch to `--build-system gradle` |
| The classpath resolved by `--build-system maven-resolver` is smaller than expected | A transitive dependency only enters the classpath if it can be fetched. Check whether the mirror id in `settings.xml` matches the `_remote.repositories` record in the local repository; when they differ, Aether considers an already cached artifact to be "present, but unavailable" |
| A high share of `ai_verdict: UNKNOWN` in `findings.ndjson` | Network / authentication / model error. Look at the `ai_reason` on stderr; or raise `ai.timeoutSeconds` |
