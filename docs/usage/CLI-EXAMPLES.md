# CLI Scenarios and Default Configuration Reference

[English](../README.md) | [中文](../zh/usage/CLI-EXAMPLES.md)

This document collects the typical scenarios and the command-to-default
configuration reference. For the complete CLI documentation (quick start /
all flags / YAML / enabling and disabling rules / output / exit codes /
environment variables / troubleshooting) see `CLI.md`; for the project path
family, build system and YAML v2 quick reference see `CLI-REFERENCE.md`.

## 1. Scenario examples

### 6.1 Single-file quick scan

```bash
java -jar plumb-...-all.jar -i Foo.java
# All rules are on by default, console output goes to stdout
```

### 6.2 Large repository + incremental cache

```bash
# First run: write the cache
java -jar plumb-...-all.jar \
     -i /opt/big-project/src \
     -f ndjson -o findings.ndjson \
     --output-dir .plumb-cache \
     --cache-mode write

# Second run: only translate the files that changed
java -jar plumb-...-all.jar \
     -i /opt/big-project/src \
     -f ndjson -o findings.ndjson \
     --output-dir .plumb-cache \
     --cache-mode readwrite
```

### 6.3 Style-only checks

```bash
java -jar plumb-...-all.jar -c - <<EOF
inputs: [src/main/java]
checks:
  # ---- master switches ----
  taint: false
  # ---- DFA family: all 48 families explicitly disabled ----
  divByZero: false
  resourceLeak: false
  nullDeref: false
  hardcodedCred: false
  intOverflow: false
  intUnderflow: false
  arrayBounds: false
  weakCrypto: false
  insecureRandom: false
  predictableSeed: false
  predictableKey: false
  staticApiKey: false
  insecureComms: false
  corsMisconfig: false
  insecureCookie: false
  badShift: false
  infiniteLoop: false
  constantCondition: false
  sensitiveDataLeak: false
  fieldHardcodedPassword: false
  missingAuthorization: false
  missingInputValidation: false
  informationExposure: false
  uncheckedReturn: false
  incorrectReturn: false
  threadRun: false
  stringEquality: false
  catchGeneric: false
  obsoleteFunction: false
  assignmentInOperand: false
  bigDecimalDoubleConstructor: false
  defaultEncoding: false
  tryCatchFinally: false
  offByOne: false
  equalsCalledOnNull: false
  compareWithSelf: false
  xxe: false
  returnFromFinally: false
  emptyCatchHandler: false
  iteratorConcurrentModification: false
  collectionSelfReference: false
  uncaughtExceptionPropagator: false
  uncheckedLoopCondition: false
  uncontrolledMemAlloc: false
  numericTruncation: false
  errorMessageInfoLeak: false
  commentInfoLeak: false
  resourceExhaustion: false
EOF
```

> `errorMessageInfoLeak` / `commentInfoLeak` are in the list as well: they
> participate in the decision of "whether to run the unified DFA layer", so
> both have to be off for DFA to actually be off. If you leave them out, this
> snippet still runs the DFA. Missing one of the 48 families under `checks:`
> leaves that one still running -- `scripts/check_doc_claims.py` guards this
> list.

### 6.4 Parallelism

```bash
# Limit to 6 concurrent translation threads on an 8-core machine
java -jar plumb-...-all.jar -i src --max-parallelism 6
```

`--max-parallelism 0` = auto = `Runtime.availableProcessors() - 1`, floor 1.

## 2. Command and default configuration reference

`conf/default-config.yaml` ships with the package. **Most** of its fields are
commented out, but 4 top-level blocks are in effect: `maxParallelism: 4`,
`cgStrategy: global`, `checks:` and `ai:`. The table below gives the default
value of every field and how to change it:

| Field | Default | How to change it |
| --- | --- | --- |
| `inputs` / `input` | `[]` | Add an entry in YAML, or repeat the CLI `--input <path>` |
| `include` / `exclude` | `[]` / `[]` | YAML only, substring filtering |
| `output.path` | (stdout) | YAML `output.path:` or `-o` |
| `output.format` | `console` | YAML `output.format:` or `-f console\|ndjson\|sarif` |
| `outputDir` | (none) | YAML `outputDir:` or `-D <dir>` |
| `cacheMode` | `off` | YAML `cacheMode:` or `--cache-mode` |
| `maxParallelism` | `0` (= `nCPU-1`); `4` in the shipped configuration | YAML `maxParallelism:` or `--max-parallelism` |
| `cgStrategy` | `global` (later) | YAML `cgStrategy:` or `--cg-strategy` |
| `sourceRoots` | `[]` | YAML or `--source-root` (repeatable) |
| `moduleRoots` | `[]` | YAML or `--module-root` |
| `classpathJars` | `[]` | YAML or `--classpath` |
| `rules.enabled` / `rules.disabled` | `[]` / `[]` | YAML only |
| `checks.*` | Almost all `true` (see `CLI.md` §3 for details) | Override in YAML, filter temporarily on the CLI with `--rule <id>` |
| `ai.dedup` | `false` | YAML or `--ai-dedup` |
| `ai.dropOnAiFp` | `false` | YAML only |
| `ai.provider` | `openai` | YAML only |
| `ai.apiKey` | (none, **must be read from `${ENV_VAR}`**) | YAML only |
| `ai.model` | `gpt-4o-mini` | YAML only |
| `ai.timeoutSeconds` | `30` | YAML only |
| `ai.maxConcurrent` | `4` | YAML only |
| `ai.temperature` | `0.0` | YAML only |
