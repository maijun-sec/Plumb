# Module Structure and Dependency Boundaries

[English](../README.md) | [中文](../zh/tech/MODULE-MERGE.md)

This document describes the module decomposition of the Plumb project, the dependency graph, and the boundary of each module.

The one-sentence conclusion: **`plumb-parser` has to be a module of its own**. It is consumed by `plumb-tac`, `plumb-style` and `plumb-dfa` at the same time, so if it were merged into any one of those downstream modules, the other two would be forced to pull in the whole transitive dependency set of that module.

## 1. The current module structure

The root `pom.xml` lists 10 modules in its `<modules>`:

```
plumb  (root POM)
├── plumb-parser      ── JavaParser wrapper, ParsedSource + JavaSourceParser
├── plumb-tac         ── TAC IR + AST→TAC translator + CFG builder + IrSerializer
├── plumb-rules       ── RuleDef / RuleId / RuleParameter / RuleRegistry metadata
├── plumb-style       ── AST style checkers (JavaParser AST visitor)
├── plumb-reporter    ── Finding data model + NDJSON / console / SARIF output
├── plumb-dfa         ── DFA engine (CallGraph + Z3 + cross-function summary + checkers)
├── plumb-dsl-kotlin  ── Kotlin DSL AST rule engine (standalone AST rule engine)
├── plumb-build       ── parent pom, aggregates the build introspection submodules
│   ├── plumb-build-core    ── BuildAdapter SPI + Maven / Gradle / Bazel / Ant adapter
│   ├── plumb-build-maven   ── Maven Resolver (Aether) transitive dependency resolution
│   └── plumb-build-capture ── ByteBuddy agent capturing the real javac command line
├── plumb-ai          ── LLM FP reduction (ReActAgent + ToolRegistry)
└── plumb-main        ── CLI + YAML configuration + pipeline orchestration
```

## 2. The module dependency graph

(measured from the `<artifactId>plumb-*</artifactId>` entries of each module's `pom.xml`)

```
                        ┌──────────────────┐
                        │   plumb-parser   │  (no internal plumb dependency, only javaparser)
                        └───┬──────────┬───┘
                            │          │
             ┌──────────────┘          └──────────────┐
             ▼                                        ▼
      ┌──────────┐                             ┌──────────────┐
      │ plumb-tac │                             │ plumb-style  │
      └─────┬─────┘                             └──────┬───────┘
            │                                          │
            ▼                                          ▼
      ┌──────────┐   ◀─────────────────────────┌─────────────┐
      │plumb-rules│                            │plumb-reporter│
      └────┬─────┘                            └──────┬──────┘
           │                                          │
           │   ┌─────────────┐                       │
           └──▶│  plumb-dfa  │◀──────────────────────┘
               └──────┬──────┘
                      │
       ┌──────────────┼──────────────┬────────────────┐
       ▼              ▼              ▼                ▼
 ┌───────────┐ ┌───────────┐ ┌───────────┐   ┌──────────────┐
 │plumb-build│ │ plumb-ai │ │plumb-style│   │plumb-dsl-    │
 └─────┬─────┘ └─────┬─────┘ └─────┬─────┘   │kotlin        │
       └──────────────┴──────────────┘         └──────┬───────┘
                     │                               │
                     ▼                               │
               ┌──────────┐                         │
               │plumb-main│◀────────────────────────┘
               └──────────┘
```

(`plumb-style` appears twice because it really does not depend on `plumb-tac` / `plumb-dfa`:
the edge on the left is `plumb-parser → plumb-style`, the one on the right is
`plumb-rules` / `plumb-reporter → plumb-style`.)

(`plumb-dsl-kotlin` depends on `plumb-style` -- it implements the `AstChecker` SPI of
`plumb-style`. This edge is one-way: `plumb-style` does not depend on `plumb-dsl-kotlin`.)

The actual direct dependencies (from each module's `pom.xml`):

| Module | Depends on these plumb modules |
| --- | --- |
| `plumb-parser` | (none) |
| `plumb-tac` | `plumb-parser` |
| `plumb-rules` | `plumb-tac` |
| `plumb-reporter` | `plumb-rules` |
| `plumb-style` | `plumb-parser`, `plumb-rules`, `plumb-reporter` |
| `plumb-dfa` | `plumb-parser`, `plumb-tac`, `plumb-rules`, `plumb-reporter` |
| `plumb-dsl-kotlin` | `plumb-parser`, `plumb-rules`, `plumb-reporter`, `plumb-style` |
| `plumb-build` | (parent pom, no internal dependency) |
| `plumb-build-core` | `plumb-rules` |
| `plumb-build-maven` | `plumb-build-core` |
| `plumb-build-capture` | (no internal dependency) |
| `plumb-ai` | `plumb-rules`, `plumb-reporter` |
| `plumb-main` | `plumb-parser`, `plumb-tac`, `plumb-dfa`, `plumb-dsl-kotlin`, `plumb-build-core`, `plumb-build-maven`, `plumb-ai`, `plumb-rules`, `plumb-style`, `plumb-reporter` |

The key edges:

- `plumb-parser` is a leaf node and **does not depend** on any internal plumb module (only JavaParser)
- `plumb-tac` depends only on `plumb-parser` (because `TacTranslator` takes a `ParsedSource`)
- `plumb-style` depends only on `plumb-parser` + `plumb-rules` + `plumb-reporter` -- **it does not depend on plumb-tac / plumb-dfa** (that is an architectural principle)
- `plumb-dfa` depends on `plumb-parser` + `plumb-tac` + `plumb-rules` + `plumb-reporter`
- `plumb-dsl-kotlin` depends on `plumb-parser` + `plumb-rules` + `plumb-reporter` + `plumb-style` --
  it implements the `AstChecker` SPI of `plumb-style`, so it has to depend on `plumb-style`;
  and `plumb-style` does **not** depend on it, so there is no cycle
- `plumb-build-maven` depends on `plumb-build-core` (it needs `BuildAdapter` / `BuildProject`)
- `plumb-build-capture` **does not depend on any internal plumb module** -- it is a standalone agent
  that runs inside the JVM of any compiler, and it should not drag the plumb dependencies in
- `plumb-main` depends on every downstream module and does the orchestration


## 3. Module boundaries

### 3.1 plumb-parser (standalone)

```xml
<dependencies>
    <dependency>javaparser-symbol-solver-core</dependency>
    <dependency>slf4j-api</dependency>
    <dependency>logback-classic</dependency>
    <!-- test -->
    <dependency>junit-jupiter (test)</dependency>
    <dependency>assertj-core (test)</dependency>
</dependencies>
```

No SootUp, no internal plumb-* dependency.

### 3.2 plumb-tac

```xml
<dependencies>
    <dependency>plumb-parser</dependency>          <!-- receives ParsedSource -->
    <dependency>javaparser-symbol-solver-core</dependency>
    <dependency>sootup.core</dependency>           <!-- .jar analysis -->
    <dependency>sootup.java.core</dependency>
    <dependency>sootup.java.bytecode.frontend</dependency>
    ...
</dependencies>
```

### 3.3 plumb-style

```xml
<dependencies>
    <dependency>plumb-parser</dependency>          <!-- AST entry point -->
    <dependency>plumb-rules</dependency>          <!-- RuleDef -->
    <dependency>plumb-reporter</dependency>       <!-- Finding -->
    <dependency>javaparser-symbol-solver-core</dependency>
    ...
</dependencies>
```

It does **not** contain `plumb-tac`, `plumb-dfa`, `plumb-dfa`.

### 3.4 plumb-reporter

```xml
<dependencies>
    <dependency>plumb-rules</dependency>          <!-- the Finding.rule field -->
    ...
</dependencies>
```

It does **not** depend on `plumb-dfa`.

### 3.5 plumb-dfa

```xml
<dependencies>
    <dependency>plumb-tac</dependency>
    <dependency>plumb-rules</dependency>
    <dependency>plumb-reporter</dependency>       <!-- Finding -->
    ...
</dependencies>
```

### 3.6 plumb-dsl-kotlin

```xml
<dependencies>
    <dependency>plumb-parser</dependency>          <!-- ParsedSource -->
    <dependency>plumb-rules</dependency>          <!-- RuleDef / RuleRegistry -->
    <dependency>plumb-reporter</dependency>       <!-- Finding -->
    <dependency>plumb-style</dependency>           <!-- AstChecker / StyleContext SPI -->
    <dependency>javaparser-core</dependency>
    <dependency>javaparser-symbol-solver-core</dependency>
    <dependency>kotlin-scripting-jsr223</dependency>
    <dependency>kotlin-stdlib</dependency>
    ...
</dependencies>
```

**The dependency direction is one-way**: `plumb-dsl-kotlin → plumb-style`, not the other way round.
`plumb-style` holds only the SPI interfaces (`AstChecker` / `StyleContext`), the implementation lives in
`plumb-dsl-kotlin`, so there is no cycle.

It **does not depend** on `plumb-tac` / `plumb-dfa` -- AST rules and TAC / data flow are two different things.
Data-flow / taint rules remain driven by the YAML configuration of `plumb-dfa`.

See [`KOTLIN-DSL.md`](KOTLIN-DSL.md) for the details.

### 3.7 plumb-main

```xml
<dependencies>
    <dependency>plumb-parser</dependency>
    <dependency>plumb-tac</dependency>
    <dependency>plumb-dfa</dependency>
    <dependency>plumb-dsl-kotlin</dependency>      <!-- plugs in the DSL AST rules -->
    <dependency>plumb-rules</dependency>
    <dependency>plumb-style</dependency>
    <dependency>plumb-reporter</dependency>
    <dependency>snakeyaml</dependency>            <!-- YAML configuration -->
    <dependency>commons-cli</dependency>          <!-- CLI parsing -->
    ...
</dependencies>
```

## 4. Pipeline data flow

```
.java
  │
  ▼  plumb-parser
ParsedSource
  │
  ├──▶ plumb-style ──────▶ List<Finding> (style)
  │      ▲
  │      └── plumb-dsl-kotlin (DSL AST rules, implements the AstChecker SPI)
  │
  ▼  plumb-tac
TacProgram
  │
  ▼  plumb-tac (CFG)
TacCfg
  │
  ▼  plumb-dfa
List<Finding> (dfa)
  │
  ▼  plumb-reporter
NDJSON / console
```

Note: plumb-style and plumb-tac **share the ParsedSource** as input, but they are **independent of each other**:

- plumb-style consumes the ParsedSource directly
- plumb-tac translates the ParsedSource into a TacProgram with TacTranslator
- plumb-dfa only looks at the TacProgram, not at the ParsedSource

This means:
- the checks of plumb-style are **independent of TAC** and can run on their own without the TAC module (for a pure style checking tool)
- the checks of plumb-dfa are **independent of the AST** and can run on their own without plumb-style (for a pure data flow analysis tool)

`plumb-dsl-kotlin` follows the plumb-style edge: its rules are evaluated on the AST,
they **do not enter the TAC pipeline** and they **do not take part in the data flow analysis**.
