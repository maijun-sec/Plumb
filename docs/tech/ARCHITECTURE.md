# Architecture Overview

[English](../README.md) | [中文](../zh/tech/ARCHITECTURE.md)

This document is the global architecture view of the Plumb project. In-depth descriptions of the individual modules are in:

- `TAC.md` — the intermediate representation format
- `DATA-FLOW.md` — the dataflow analysis algorithms
- `STYLE-RULES.md` — the implementation of the style-class rules
- `CROSS-FUNCTION-ANALYSIS.md` — cross-function analysis and Z3
- `MODULE-MERGE.md` — the module boundaries

## 1. Overall goals

Plumb is a **pure Java** static analysis tool, an **independent project** (not a port of Fathom). The architecture was inspired by Fathom, but the **implementation is completely independent**, and the language is limited to Java.

| Goal | Approach |
| --- | --- |
| Taint analysis as the core | Per-checker forward dataflow (`TaintedCheckerBase` handles the propagation uniformly), with source/sink regex tables driving the rules |
| Multiple input sources | JavaParser (source code) and SootUp (bytecode) both feed the same TAC IR |
| Style checks kept separate | The AST checkers do not depend on TAC/DFA |
| Scalable to large repositories | On-disk IR cache, staged pipeline, configurable concurrency |
| Industry-standard reporting | NDJSON (machine-readable) + console (human-readable) + SARIF (IDE) |
| AI assistance | The plumb-ai Re-Act multi-turn LLM agent gives a TP/FP verdict (optional) |

## 2. The staged pipeline

```
                          sast-scan CLI (plumb-main)
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        │                              │                              │
        ▼                              ▼                              ▼
   Phase 1: parse              Phase 1: parse (jar)         Phase 1: parallel
   JavaParser → AST            SootUp → Jimple → TAC       (for > 800 files)
        │                              │                              │
        ▼                              │                              │
   Phase 2: style (parallel)             │                              │
   AstDispatcher → 595 checks           │                              │
        │                              │                              │
        └──────────────────────────────┴──────────────────────────────┘
                                       │
                                       ▼
                              Phase 3: translate (parallel)
                              AST → TacProgram
                              (cached: cache/programs/<sha1>.tac)
                              --source-root/--module-root/--classpath
                              only fed to the TypeSolver, not to this phase
                                       │
                                       ▼
                              Phase 4: merge
                              Multi-file → MergedProgram
                              (cached: cache/merged.tac)
                                       │
                                       ▼
                              Phase 5: DFA
                              ┌────────┬────────┬────────┐
                              │ taint  │ dfa    │ path-  │
                              │        │ (ir)   │ cond   │
                              └────────┴────────┴────────┘
                                       │
                                       ▼
                              Phase 6: emit
                              NDJSON / console / SARIF
                                       │
                                       ▼
                              Phase 7 (optional): AI dedup
                              plumb-ai Re-Act agent
                              → ai_verdict / ai_reason in NDJSON
```

The concurrency model and resource usage of each stage:

| Phase | Parallelism | Peak memory | On-disk artefact |
| --- | --- | --- | --- |
| 1. parse | `nCPU-1` | 1 CompilationUnit | None |
| 2. style | `nCPU-1` | 1 CompilationUnit + StyleContext | None |
| 3. translate | `nCPU-1` | 1 TacProgram | `cache/programs/<sha1>.tac` |
| 4. merge | No (sequential) | 1 MergedProgram | `cache/merged.tac` |
| 5a. taint | No | 1 MergedProgram + summary cache | `cache/callgraph.tac` |
| 5b. dfa | No | 1 MergedProgram | None |
| 5c. unified | Partial | call graph + Z3 ctx | None |
| 6. emit | No | 0 (streaming) | `report/findings.{ndjson,console,sarif}` |
| 7. ai-dedup | `ai.maxConcurrent` | LLM response buffer | (the fields written back into the NDJSON) |

## 3. One sentence per module

| Module | One sentence | Dependencies |
| --- | --- | --- |
| `plumb-parser` | Turns .java text into a JavaParser CompilationUnit | javaparser-symbol-solver-core |
| `plumb-tac` | CompilationUnit → TacProgram, including the CFG builder and the IR serialization | plumb-parser, sootup |
| `plumb-rules` | Rule metadata (ID/Name/Severity/Type/CWE) | None |
| `plumb-reporter` | The Finding model + NDJSON/console/SARIF output | plumb-rules |
| `plumb-style` | AST visitor checkers (single source: JavaParser); for the count see `docs/rules/RULES.md` | plumb-parser, plumb-rules, plumb-reporter |
| `plumb-dfa` | The unified DFA engine for the four analysis categories: taint / boundary / points-to / control-flow | plumb-tac, plumb-rules, plumb-reporter |
| `plumb-dsl-kotlin` | The Kotlin DSL AST rule engine (user-written AST rules, no Java required) | plumb-parser, plumb-rules, plumb-reporter, plumb-style |
| `plumb-main` | CLI + YAML configuration + pipeline orchestration + build-system adapter | All of the above + plumb-build-core + plumb-build-maven |
| `plumb-build` | Parent pom, aggregating the build self-inspection submodules | None (pom) |
| `plumb-build-core` | BuildAdapter SPI + Maven / Gradle / Bazel / Ant adapter → sourceFiles/classpathJars | plumb-rules, maven-model |
| `plumb-build-maven` | Maven Resolver (Aether) computes the full transitive dependency tree; reads the mirror from settings.xml | plumb-build-core, maven-resolver |
| `plumb-build-capture` | The ByteBuddy agent captures the real javac command line | byte-buddy, byte-buddy-agent |
| `plumb-ai` | The Re-Act multi-turn LLM agent (OpenAI / Anthropic / compatible) | plumb-rules, plumb-reporter |

### 3.0 The implementation shapes of the DFA checkers

There are 151 `.java` files under `plumb-dfa/src/main/java`, and not every one of them is an independent analyser. By
**actual implementation mechanism** they fall into a few categories (the counts are measured):

| Shape | Count | Meaning |
| --- | ---: | --- |
| **`TaintedCheckerBase` subclasses** | 47 | Inherit the unified taint propagation base class; the vast majority under `checkers/taint/` (excluding the base class itself) |
| **Directly `implements DfaChecker`** | 19 | Bring their own logic and do not share the propagation layer; all 15 of `checkers/controlflow/` are in this group |
| **`PathSensitiveCheckerBase` subclasses** | 1 | The base for reverse detection (the alloc-without-close kind) |
| **Solvers / lattices / engine / SPI** | The rest | `points/`, `solver/`, `summary/`, `taint/`, `engine/` |

Among them:

- **Annotation channel**: outside the dataflow, it uses `TacFunction.classAnnotations()` / `methodAnnotations()` /
  `paramAnnotations()` to make structural decisions (Spring `@GetMapping`, `@RequestParam`, `@NonNull`)
- **SRC-regex**: it goes through `SourceReader` to read the source file and matches with pure regex; the TAC IR is not used at all (legacy)

See [`DATA-FLOW.md`](./DATA-FLOW.md) for the detailed description of the four analysis categories: taint / boundary / points-to / control-flow.

#### 3.0.1 The three core SPIs

```
DfaChecker (plumb-dfa/engine/DfaChecker.java):
  initEngine(ctx)                                  // once at engine start-up
  for each fn in reverse post-order:
      initFunction(ctx, fn)
      for each ins in fn:
          checkInstruction(ins, fn, ctx)           // the main hot path
      finishFunction(ctx, fn)
      summary = summarizeFunction(ctx, fn)         // the side-effect list for the callers
      ctx.summaryCache().put(fn.signature, summary)
  finish(ctx)                                      // once at engine shut-down

TaintedCheckerBase (plumb-dfa/taint/TaintedCheckerBase.java):
  - derives from TaintRule(ruleId, sourceKind, sinkFamilyKey, cwe, severity, TaintDomain)
  - a subclass overrides only 4 hooks: isSourceFact / sinkArgumentIndex / findingMessage / extending checkInstruction
  - the overall propagation rules (MOVE / BINOP / cross-function CALL) are handled by the base class
  - adding a sink rule under taint/ is often just a few lines of code

PathSensitiveCheckerBase (plumb-dfa/taint/PathSensitiveCheckerBase.java):
  - the base for reverse detection of the alloc-without-close kind
  - tracks allocSites and closeSites; at finishFunction the subclass decides
  - the parent class of ResourceLeakChecker
```

Direct findings (a bug that lies entirely inside the function body) are emitted immediately through
`ctx.reporter().report(...)` and **do not go into the summary**; only "what the caller needs to know" goes into
`FunctionSummary` (the R-7 constraint).

#### 3.0.2 A mitigation is a fact, and some facts are about the SINK's receiver

`UnsafeDeserializationChecker` names `ObjectInputFilter` and `resolveClass` as sanitizers it must honour. For a long
time nothing implemented that: the names appeared in prose and nowhere else, so a build that shipped the fix for a
deserialization CVE was still reported. A rule that is registered at CRITICAL severity and can never fire is worse than an
absent rule, because the coverage report claims the CWE is handled.

The JEP-290 filter is now a first-class fact, held by `DeserializationFilterTracker` (`plumb-dfa/taint/`). Three
properties define it, and each one is a decision the tracker has to get right:

| Property | Why it is the way it is |
| --- | --- |
| **It is about the stream, not the value.** | The same untrusted bytes are safe through a filtered stream and unsafe through an unfiltered one. The filter is installed on the STREAM, so the sink check asks about the RECEIVER it is about to report on. |
| **It restricts only when it can REJECT.** | A filter whose only outcomes are `ALLOWED` and `UNDECIDED` cannot reject anything, so it restricts nothing -- that is the partial mitigation, and the finding must stand. A PATTERN filter restricts when its pattern ends in `!*`; a pattern without it allows every class it does not mention. A LAMBDA filter restricts when its body can return `REJECTED`. |
| **It is scoped to its family.** | A filter says "these classes may be reconstructed". It says nothing about a log entry, a command line, or a SQL statement. |

The tracker's state is per-function (the filter is installed and read in the same function), but the lambda BODIES are
indexed once per program, in `initEngine()` -- before any function is visited, because the sink check asks "is this
stream filtered?" *while* the instruction walk is still running, and a lambda filter can only be answered if its body has
already been read.

Three TAC-level invariants had to hold before the fact could be represented at all, and each one had been hiding a real
finding:

- **A try-with-resources' resource is a declaration.** The resource declarations are translated before the body, and the
  resource variable is a local. Otherwise the body's reference to it falls through the name resolver's `this.<name>`
  fallback and becomes a FIELDLOAD off `this` with an `UNKNOWN` type -- and the initializer is never translated, so no
  taint can reach it. A `try (ObjectInputStream ois = new ObjectInputStream(untrusted)) { ois.readObject(); }` would then be
  structurally incapable of producing a finding, while the same code outside a try-with-resources did produce one.
- **A lambda's name is unique across the program.** The counter lives on the translator, not on the per-method builder.
  With a per-builder counter, every method's first lambda was called `lambda$0`, and anything that resolves a lambda BY
  NAME got an arbitrary answer -- the tracker would conclude that a permissive filter could reject.
- **The resource's `close()` is modelled, as an ordinary CALL.** Java's try-with-resources closes the resource when the block
  ends, so the TAC emits the close as a call on the resource variable. Emitting it is what keeps the resource-leak checker
  honest: the checker reports an allocation unless it sees a close on that value, so a try-with-resources that was NOT
  closed would be reported as a leak -- a false positive on code that closes the resource correctly.

Two more facts had to hold before the resource-leak checker could be right about closings, and both were pre-existing gaps
that the try-with-resources work brought to the surface:

- **A close on a local closes the allocation it was assigned from.** The TAC lowers `X x = new X(...); x.close();` to a
  `NEW` into a TEMPORARY followed by a `MOVE` into the local, so the allocation lives on the temporary and the close on the
  local. Without an alias between them, the checker reported a leak on code that closes the resource correctly.
- **A close on SOME path is not a close on EVERY path.** The rule fires when the resource is "not closed on every path", so
  a close inside a branch arm does not discharge the obligation. The branch-arm structure is read from the TAC's `BRANCH`
  and label structure.

`scripts/mutate_deserialization_filter.py` mutates the tracker's five load-bearing decisions and asserts that the tests
catch each one: a permissive pattern, a lambda that never rejects, a filter attached to the wrong stream, an unscoped
suppression, and an absent suppression.

### 3.1 Module dependency graph

```
                    ┌──────────────┐
                    │ plumb-parser │
                    └──────┬───────┘
                           │
                ┌──────────┴──────────┐
                ▼                     ▼
          ┌──────────┐          ┌──────────┐
          │ plumb-tac │          │ plumb-   │
          │           │          │ rules    │
          └─────┬─────┘          └────┬─────┘
                │                     │
                │              ┌──────┴──────┐
                ▼              ▼             │
        ┌──────────┐   ┌──────────┐         │
        │ plumb-dfa│   │ plumb-   │         │
        │          │   │ style    │         │
        └────┬─────┘   └────┬─────┘         │
             │              │              │
             │       ┌──────┴──────┐       │
             ▼       ▼             ▼       ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ plumb-   │ │ plumb-   │ │ plumb-   │
        │ reporter │ │ style    │ │ dfa      │
        └────┬─────┘ └──────────┘ └────┬─────┘
             │                         │
             └────────────┬────────────┘
                          ▼
                  ┌──────────────┐
                  │ plumb-main   │
                  └──────┬───────┘
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ plumb-   │ │ plumb-   │ │ plumb-   │
        │ ai       │ │ build    │ │ reporter │
        └──────────┘ └──────────┘ └──────────┘
```

`plumb-ai` and `plumb-build-core` are both **compile-time dependencies** of `plumb-main` (in `pom.xml` they are ordinary
`<dependency>` entries, without `<optional>`) — `plumb-main` imports their types directly, so
removing either of these two modules breaks the build. The same is true of `plumb-build-maven`: `plumb-main` depends on it,
which is what allows `MavenResolverAdapter` to be discovered by the ServiceLoader.

What is optional is the **runtime behaviour**, not the dependency itself:

- `plumb-ai`: without `--ai-dedup` the static analysis runs as usual; an LLM endpoint that cannot be configured only means
  the verdict is marked UNKNOWN.
- `plumb-build-*`: without `--build-system` / `--project-root` the build-system
  adapter is never called, and the scan target is determined by `--input`.

### 3.2 Key design decisions

The following 8 items explain the architectural trade-offs of Plumb.

#### 1. The identity of a Fact comes from `TacOperand`

An IR variable is `TacOperand.TacVar` (with three fields: `name` / `type` / `isTemp`), and
`equals` / `hashCode` cover **all three fields** (`Objects.hash(name, type, isTemp)`).
A `Fact` holds the `TacOperand` itself, so the key of the fact bag is that object — variables with
the same name but a different type, or where one is a temp and the other is not, **will not** be treated as the subject of the same fact.
The price is that the translator must guarantee that the SSA version names within one function are unique, otherwise two variables
with different semantics share an identity.

#### 2. Call graph edges use a call-site-style key

Edges use a call-site-style key such as `wrapper/1`, while functions use a qualified signature. This avoids fully
qualified name resolution, and the same name with a different arity is easy to distinguish. The price is that overloads
cannot be disambiguated by name alone, which requires type-aware matching on top.

#### 3. Independent rule registries, to avoid duplicate reporting

The sensitive-data rules and the ordinary taint rules each have their own registry and sink family, and they do not interfere with each other: user input → log
goes through log-injection, while sensitive data → log goes through sensitive-data-leak; the two rule sets trigger independently.

#### 4. String prefixes distinguish user classes from JDK classes

A string-prefix and simple-name set distinguishes user classes from JDK classes. The cost is bounded (an O(1) table lookup + a fixed
set), with no classloader dependency. The conservative fallback: an unknown type still triggers → zero coverage loss. This is the control paradigm of "improving precision without sacrificing
coverage".

#### 5. The dedup key contains the sink identity

`ruleId + line + sinkSig + fn.signature()`, rather than only `ruleId + line + fn.signature()`. When one line
has multiple sinks (println + throw), each of them should be reported.

#### 6. A 1-hop def chase replaces cross-function const-folding

Inside a check, one hop is chased with the defs map, and no cross-call constant folding is added at the registry level. The cost is bounded
(one O(1) table lookup per parameter), and it covers the common cases. The price: helper chains are still missed, and that is accepted as a reasonable cost of bounded analysis.

#### 7. A synthetic `<init>` restores instance field initialisation

The translator generates a synthetic `<init>` automatically, otherwise the FIELDSTORE of an instance field would be silently
lost in the IR — that would be the loss of a whole dimension.

#### 8. Annotation IR + layered DI suppression

`TacFunction` carries three dimensions of annotation lists (class-level + method-level + field-level), and the consumers use them to
suppress the false positives caused by Spring / Jakarta DI placeholders. The class-level and method-level are decided together, which avoids
two visitor passes.

→ **The overall principle**: single-pass dispatch + name-keyed IR + summary reuse + independent registries + bounded
analysis.

## 4. Data contracts

### 4.1 Finding

```java
public final class Finding {
    Path file;
    int line, column;
    RuleDef rule;     // from plumb-rules
    String message;
    Severity severity;  // INFO/LOW/MEDIUM/HIGH/CRITICAL
    String dataflowHead; // optional, the dataflow chain of the taint kind
    String aiVerdict;    // optional, "TP"|"FP"|"UNKNOWN"
    String aiReason;     // optional, the short reason given by the LLM
    String sinkFunction; // optional, the sink name dedicated to the DFA-kind rules
}
```

`Finding` lives in the `plumb-reporter` module. `plumb-dfa` / `plumb-style` / `plumb-ai` all import it.

### 4.2 RuleDef

```java
public final class RuleDef {
    RuleId id;             // UUID + name
    String description;
    Severity severity;     // INFO/LOW/MEDIUM/HIGH/CRITICAL
    Type type;             // TAINT/STYLE/NAMING/...
    List<String> cwe;
    String checkerClass;   // "org.plumb.style.EmptyCatchCheck"
    boolean enabled;
    List<String> aliases;  // the corresponding IDs of external tools (PMD/SpotBugs/...)
    List<RuleParameter> parameters;
}
```

### 4.3 TacProgram

See `TAC.md`. All IR classes implement `Serializable` to support the on-disk cache.

## 5. Caching and persistence

See `CACHE.md`. The current implementation:

- `plumb-tac/.../io/IrSerializer.java` provides `writeProgram` / `readProgram` / `writeMerged` / `readMerged` / `writeObject` / `readObject`
- `plumb-main/.../OutputLayout.java` manages the directory layout of `<outputDir>/cache/` and `<outputDir>/report/`
- The cache key = `SHA-1(canonicalize(source-path))`, which guarantees that different processes get the same key for the same file
- `cache-mode`: `off` / `read` / `write` / `readwrite`

## 6. The families of project paths (decoupled from the TypeSolver)

Plumb strictly separates "the paths fed to the TypeSolver" from "the paths scanned for findings", decoupling the three
layers CLI / Config / Scanner:

| Path family | flag | Enters the TAC | Produces findings | Feeds the TypeSolver |
| --- | --- | --- | --- | --- |
| `--input` | `-i` | ✅ | ✅ | ✅ |
| `--source-root` | `--source-root` | ❌ | ❌ | ✅ |
| `--module-root` | `--module-root` | ❌ | ❌ | ✅ |
| `--classpath` | `--classpath` | ❌ | ❌ | ✅ |

**The core rule**: a `--classpath` path is **never** translated into the TAC IR. The only purpose it serves is to let the user's
source code under `--input` resolve references to external types.

The implementation:

- The three `List<Path>` fields `ScanConfig.sourceRoots` / `moduleRoots` / `classpathJars`
- `SastMain.main` parses these 3 flags
- `JavaSourceParser` feeds these three lists to `JavaParserTypeSolver`, but **never** traverses them as input files
- The TAC translate stage only looks at `cfg.inputs`

## 7. Automatic build-system discovery (plumb-build)

`plumb-build` automates "feeding `--source-root` / `--classpath` by hand". It is a **parent pom**
with three submodules hanging below it:

```
┌───────────────────────────────────────────────────────────┐
│ plumb-build (parent pom)                                  │
│                                                           │
│  ┌──────────────────┐   ┌──────────────────┐             │
│  │ plumb-build-core │   │ plumb-build-maven│             │
│  │                  │   │                  │             │
│  │ BuildSystemReg.  │   │ TransitiveResolv.│             │
│  │    ├── Maven     │   │ MavenSettings    │             │
│  │    ├── Gradle    │   │ MavenResolverAd. │             │
│  │    ├── Bazel     │   │                  │             │
│  │    └── Ant       │   └──────────────────┘             │
│  └──────────────────┘                                    │
│                                                           │
│  ┌──────────────────┐                                    │
│  │plumb-build-      │   real compile capture (ByteBuddy agent)│
│  │capture           │                                    │
│  │ CaptureAgent     │                                    │
│  │ CompilerHooks    │                                    │
│  │ CompilerEntryPt. │                                    │
│  │ JavacOptions     │                                    │
│  └──────────────────┘                                    │
└───────────────────────────────────────────────────────────┘
              │
              ▼
       ScanConfig.inputs / sourceRoots / moduleRoots / classpathJars
              │
              ▼
       ScanRunner takes the paths fed by the BuildAdapter and continues down the standard pipeline
```

The actual classes:

- `plumb-build/plumb-build-core/src/main/java/org/plumb/build/` — `BuildAdapter`, `BuildProject`,
  `BuildSystemRegistry`, `MavenAdapter`, `GradleAdapter`, `BazelAdapter`, `AntAdapter`,
  `SourceFiles`, `BuildAdapterException`
- `plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/` — `MavenSettings`,
  `TransitiveResolver`, `MavenResolverAdapter`, `MavenPom`, `ModuleDescriptor`
- `plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/` — `CaptureAgent`,
  `CompilerHooks`, `CompilerEntryPoints`, `CompilerOption`, `GradleHooks`, `CaptureRecorder`,
  `CaptureEntry`, `CaptureSink`, `CaptureReader`, `JavacOptions`, `CompiledSourcesAdvice`,
  `CompiledSourcesBridge`

Each adapter implements `BuildAdapter.discover(Path)` and returns a `BuildProject`:

- `MavenAdapter` parses `pom.xml`, treats `<sourceDirectory>` / `<testSourceDirectory>` as sourceFiles,
  and walks the dependencies under the m2 repository as classpathJars
- `MavenResolverAdapter` (`maven-resolver`) goes through the Maven Resolver (Aether) to compute the **full transitive dependency tree**,
  and along the way reads the mirror / localRepository from `settings.xml`
- `GradleAdapter` is the same as Maven, but parses `build.gradle(.kts)`
- `BazelAdapter` parses the `java_library` / `java_binary` rules in the `BUILD` files
- `AntAdapter` parses the `<javac>` task of `build.xml`

`BuildSystemRegistry.detect(projectRoot)` probes in the order `maven-resolver` → `maven` → `gradle` → `bazel` →
`ant`. `maven-resolver` comes before `maven` because the transitive dependency tree it computes is more accurate;
`maven` is the fallback because it has no external dependency and therefore does not fail when the local repository is broken.

### 7.0 The two ways to obtain the classpath

| | `maven` (core) | `maven-resolver` (the maven module) | `plumb-build-capture` |
| --- | --- | --- | --- |
| Means | Statically parses `pom.xml` | Aether resolves the transitive dependencies | Captures the real javac command line |
| Transitive dependencies | ❌ only the directly declared ones | ✅ the complete closure + version conflict resolution | ✅ what the compiler actually sees |
| Dependencies | only `maven-model` | the whole Aether stack | ByteBuddy |
| Suitable for | Fast, offline, good enough | Maven projects with complex dependencies | A classpath assembled by the build scripts |

`plumb-build-capture` is a complement to the first two, not a replacement: it solves the kind of project where "the classpath is
assembled by a script / a plugin / the IDE", which static analysis cannot handle. Usage:

```bash
# Attach the agent at compile time and write the real javac command line to disk
PLUMB_CAPTURE_DIR=/tmp/captures \
  javac -javaagent:plumb-build-capture.jar -d out Foo.java

# Read it back afterwards
java -cp plumb-build-capture.jar ... # CaptureReader.readAll(Path.of("/tmp/captures"))
```

### 7.0a The two sides of the compile parameters, and the gate that joins them

The two sides of the compile-parameter story answer the same questions, and they are used
together: a build is described by **reading its pom** and by **capturing what it really compiled
with**.

| the question | `plumb-build-maven` — what the build DECLARED | `plumb-build-capture` — what the compiler PERFORMED |
| --- | --- | --- |
| the language level | the compiler plugin's `<release>`, then `<source>` | `--release`, then `-source` |
| the class-file version | the plugin's `<target>` | `--target` |
| the encoding | the plugin's `<encoding>`, then `project.build.sourceEncoding` | `-encoding` |
| the classpath | the Maven Resolver's transitive closure | the `-classpath` the compiler received |
| the output directory | the pom's `<outputDirectory>` | the `-d` the compiler received |
| the source roots | the pom's `<sourceDirectory>` | the files the compiler was given |

**They have to mean the same things.** When the two disagree about what a parameter means, one of
them is wrong, and the analysis is wrong in a way nobody can see. So
`scripts/check_compile_parameters.py` checks them **against each other**:

- every compile parameter the Maven side reads has a counterpart in the capture's option table —
  the pom's `<release>` is the same parameter as the capture's `--release`;
- the two agree on the **order that decides**: `<release>` beats `<source>`, and `--release` beats
  `-source`. A build that declares `<source>8</source>` and pins `<release>17</release>` compiles
  at 17, and a side that reported 8 would be confidently wrong;
- the two agree that an **undeclared parameter is unknown**, not a default. The upstream project
  hard-coded `"1.8"` and `"UTF-8"`, so every project was reported as if it compiled at 1.8 in
  UTF-8 — a confident wrong answer, which is worse than an absent one, because the analyser cannot
  tell that the value is a guess.

`scripts/mutate_compile_parameters.py` is the gate's own mutation test: six deliberate drifts, all
of which must make the gate fail.

**One deliberate difference, stated rather than hidden.** The classpath is a parameter on both
sides, but it is obtained in two different ways: the Maven side resolves the dependency closure
through the Maven Resolver, because a plugin's own declaration is not what the compiler is given;
the capture side observes the classpath the compiler actually received. The gate checks that
**both** ways exist, so neither can be dropped silently.

### 7.0aa The Maven side reads what the build declared, along the parent chain

A multi-module build inherits its compile parameters from its **parent pom**, and the parameters
are not in one place: they are in the `maven-compiler-plugin`'s configuration, and they are
overridden by properties, and a property's value is itself a property.

So `MavenPom` reads the compiler plugin's configuration **along the parent chain**, with the
module's own configuration first. The rules it follows, each of them measured:

- the plugin's configuration **beats** the property, because the plugin's configuration is what
  the compiler plugin actually used;
- `<release>` **beats** `<source>`, because `--release` pins the language level *and* the
  platform API, and a build that sets both compiles at the release;
- a `${...}` placeholder is **resolved**, and an unresolvable one leaves the value unknown
  rather than passing the placeholder through as if it were a version;
- an **undeclared** parameter stays unknown. The upstream project hard-coded `"1.8"` and
  `"UTF-8"`, so every project was reported as if it compiled at 1.8 in UTF-8.

The plugin's configuration also carries two things that are not parameters but change the
compile, and they are read too:

- `<compilerArgs>` — the extra arguments the plugin passes to the compiler;
- `<annotationProcessorPaths>` — where the annotation processors are found, which is a different
  path from the classpath.

Both reach `ModuleDescriptor` and both are read by the resolver adapter, so a build that puts a
processor on a separate path is described correctly.

The module's own **directories** are read from the pom as well — `<sourceDirectory>`,
`<testSourceDirectory>`, `<build><outputDirectory>` and `<build><testOutputDirectory>` — rather
than assumed to be `src/main/java`, `src/test/java`, `target/classes` and `target/test-classes`.
A build that puts its sources somewhere else is now described correctly.

### 7.0ab The two sides are compared against each other, and the differences are reported

The two sides of §7.0a answer the same questions, so a build can be checked for **internal
consistency**: what the pom declares against what the compiler was actually given.

`plumb-build-core` holds `CompileDiscrepancy` — one difference, with the declared value, the
observed value and the parameter they disagree about. `plumb-build-maven` holds
`CompileCrossCheck`, which reads the capture directory and the pom and produces the
discrepancies.

Three rules make the comparison trustworthy:

- **A value missing on either side is unknown, not a discrepancy.** The capture does not know
  the encoding when the build did not pass `-encoding`, and the pom does not know the classpath
  when it was inherited. Reporting those as differences would be noise, and noise is how a gate
  stops being read.
- **The differences keep both values.** A discrepancy that only says "they differ" cannot be
  acted on; one that says "the pom declares 1.8 and the compiler was given 17" can.
- **The differences are sorted deterministically**, so the same build always produces the same
  report and a diff between two runs means something changed.

The comparison is a **report, not a resolution**. A silently resolved disagreement is a
confidently wrong analysis: the point is to show that the build says one thing and the compiler
did another.

`plumb-main` exposes it as `--capture-dir <dir>`, and
`scripts/check_compile_crosscheck.py` is the gate that keeps the comparison honest;
`scripts/mutate_compile_crosscheck.py` is its mutation test.

### 7.0.1 Which entry points the capture hooks

The "javac command line" is not one method but **fifteen**, depending on how the compiler is reached:

| Entry point | Who goes down this path |
| --- | --- |
| `com.sun.tools.javac.Main#compile(String[])` | the `javac` command line (the static layer) |
| `com.sun.tools.javac.Main#compile(String[], PrintWriter)` | the same, with the diagnostics going to the writer supplied by the caller |
| `com.sun.tools.javac.main.Main#compile(String[])` | the `javac` command line (the instance layer) |
| `com.sun.tools.javac.main.Main#compile(String[], Context)` | the call paths that carry a context |
| `com.sun.tools.javac.api.JavacTool#getTask(...)` | **Gradle / Maven / IDE** (the `javax.tools` API) |
| `com.sun.tools.javac.api.JavacTool#run(...)` | the old-style run API |
| `com.sun.tools.javac.api.JavacTaskImpl#call()` | task-driven (build tool / IDE) |
| `com.sun.tools.javac.api.JavacTaskImpl#generate()` | task-driven (the IDE path) |
| `com.sun.tools.javac.main.JavaCompiler#parse(...)` | **every source the compiler parses** |
| `com.sun.tools.javac.main.JavaCompiler#compile(...)` | **every compilation, at the moment it ends** |
| `com.sun.tools.javac.main.JavaCompiler#generate(...)` | the IDE's generate path (which does not go through `compile`) |
| `org.eclipse.jdt.internal.compiler.batch.Main#compile(String[])` | the Eclipse compiler (ECJ) |
| `com.sun.tools.sjavac.client.ClientMain#run(String[])` | the sjavac client |
| `com.sun.tools.sjavac.server.ServerMain#run(String[])` | the sjavac server |

The consequence of hooking only one of them is a **silent mismatch**: the scan still runs, but the compilation on that path is
never captured. Therefore the list of entry points is **declarative** (`CompilerEntryPoints`), and adding an entry point means changing data, not code.

**The class names of the entry points were measured, not copied from documentation.** This one cost some pain:
`javax.tools.JavaCompiler` is an **interface** on a modern JDK, with only `getTask` and
`getStandardFileManager`, and **no `run` method at all** — a hook attached to it never fires,
so every caller that uses the run-style API gets an empty capture. `run` actually lives
on `com.sun.tools.javac.api.JavacTool`. `CompilerEntryPointsTest` now **verifies the method name and the
parameter count of every entry point by reflecting on the real class**, so writing a wrong class name again fails the build directly.

The hooking uses ByteBuddy's **`Advice`** (inline weaving), not a hand-written ASM visitor:
`Advice` **copies** the hook's bytecode into the method under test, so it needs no super method and no delegation target,
and a static method such as `Main#compile(String[])` can be hooked just as well. A hand-written visitor has to compute the local slot of
the parameters itself (0 for a static method, 1 for an instance method), and getting that wrong corrupts the compiler's stack.

### 7.0.1.1 The sources the compiler really compiled

The command line names the sources the compiler was **given**. The compiler also compiles the
sources it **finds itself**: a class that is referenced but not named is looked up on the source
path, parsed, and compiled. A capture that lists only the command-line sources therefore misses
code the compiler really compiled — and that code is exactly what a static analyser has to look at.

**The place that knows is `JavaCompiler#parse(JavaFileObject, CharSequence)`.** Every source the
compiler parses goes through it, whichever way it got there: the ones it was given, through
`parseFiles`, and the ones it found on the source path, through `readSourceFile`. So one hook
there sees exactly the sources the compiler parsed — and a file in the source path that nobody
referenced is never parsed, so it never appears.

**The file manager's content cache was tried first, and it does not work.** It is the obvious
place: it is the compiler's own record of the files it read. It stays **empty** after a normal
compile, because the compiler reads a source file's content through the file object rather than
through the manager's cache. That was measured, not assumed: a real compile of one explicit
source plus one source-path source left the cache with zero entries. A cache that never fills
cannot answer the question.

**The capture is written when the compilation ends, not when it starts.** The sources the compiler
really compiled are only known at the end, so a capture written at the start cannot report them.
The options are known at the start, so they are **staged** until the compilation ends.

**The staged compilation is matched, not taken by position.** One compilation is seen through
several entry points — the command line reaches three of them — so it is staged more than once.
And a build may have more than one compilation in flight: Gradle, for one, creates the task for
the next module **before** the previous module's compilation has finished. So the staged
compilation is chosen by **matching**: the one whose sources are the ones the compiler actually
parsed. Taking the most recent one instead would report the next module's options for this
module's compilation.

### 7.0.2 Where the options of the IDE path come from

`JavacTaskImpl#call()` and `#generate()` **carry no compilation options** — the options are already fixed when `getTask` creates the task.

And they **cannot be read back**: the `com.sun.tools.javac.api` package is not exported by the `jdk.compiler` module, and
reflecting on the task's `options` field throws `InaccessibleObjectException`,
unless the JVM is given `--add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED` —
which would make the agent unusable in an ordinary build.

So the options are remembered **where they can still be seen**: `getTask` gets both the options and the task it returns,
and it associates the two by **object identity**; when the task is driven, they are retrieved by identity again.
An `IdentityHashMap` is the key here — using `equals` would mix up two tasks with the same content.

### 7.0.3 What the capture records

Not the argv, but **what this compilation actually used**: the **actual content** of the classpath (not the `-classpath` argument itself),
the source path, the encoding, `--release`, the list of source files, the output directory, and the JDK version used for the compilation.
The reason is the same as for Coverity: `javac` without `-classpath` and `javac -classpath ""` mean different things, and
only by looking at the environment together can it be reconstructed.

### 7.0.4 The option table is data, not a switch

The compiler's options are described in **`CompilerOption`**, as a table. Each entry says:

| field | what it decides |
| --- | --- |
| the name | how the compiler spells it (`-cp`, `--classpath`, ...) |
| the canonical name | what the option is recorded under — the synonyms share one |
| the value kind | `none` / `single` / `path-list` / `comma-list` |
| the repetition rule | `last-wins` (the classpath) or `all-apply` (`--add-modules`) |
| the effect | `compilation` or `not-compilation` (`-Xprint`, `-proc:only`) |
| the compiler | which compiler it belongs to, or all of them |

**Why a table and not a `switch`.** The first implementation had a dozen option names hard-coded
in a `switch`, and everything it did not know fell through to a default branch that treated
**any argument ending in `.java` as a source file**. So `--processor-path some/dir.jar` was
dropped entirely, and an option whose value happened to end in `.java` was mistaken for a
source. Adding an option is now **one line of data**.

**The repetition rule is the part that decides what the compilation actually did.** `-classpath`
given twice means the **last** one wins, not both — javac does not concatenate them.
`--add-modules` given twice means **both** apply. Reading either wrongly produces a classpath
or a module set that never existed.

**The value kind decides how the value is split.** A path list is split on the **platform's**
separator, so a Windows drive letter stays intact. A comma list — `--add-exports a/b=c/d` — is
split on commas, which on Unix would be wrong to split on the path separator even though on
Windows it would be right by accident.

**The compilers differ, and the table says so.** ECJ has options javac does not have — `-src`,
`-profile`, `-log` — and parsing ECJ's arguments with javac's table would swallow a value as an
option javac does not have. So the option knows which compiler it belongs to, and the lookup is
compiler-aware.

**Two compilers may spell an option the same way and still mean different things by it.** ECJ
and sjavac both have `-src`, `-copy`, `-x`, `-i`, `-tr` and `-j`, and sjavac's `-copy` is
repeatable while ECJ's is not. The index is therefore keyed by the name **and** the compiler, and
the compiler's own entry wins over a shared one. See
[§7.0.5aa](#705aa-the-other-two-compilers-are-measured-too).

**The compiler is the yardstick for which spellings exist.** The table and its test both claim
that javac does not know `--class-path`. The claim is wrong: javac's own help lists
`--class-path <path>, -classpath <path>, -cp <path>`, and a real javac run accepts the
spelling. The cost of the wrong belief is a **lost classpath** — the option is unknown, so the
value that follows it is not a classpath entry either, and both vanish. Every option in the table
is checked against a real compiler, and `scripts/check_compile_options.py` is the gate that
keeps them in step.

`--release` is kept apart from `-source` and `-target`: `--release` pins the language level
**and** the platform API, while `-source` pins only the language level. Folding them together
would lose that.

### 7.0.5 The table is measured against Coverity

`plumb-build-capture/src/main/resources/coverity-java-options.toml` holds Coverity's option
model, extracted from `JavacOptionParser` in cov-analysis 2023.6.0. It is the yardstick.

`scripts/check_capture_options.py` compares the two, **in both directions**: every option in
Coverity's categories has to exist in our table with the same value kind, the same repetition
rule and the same compiler; every alias has to be a spelling we know; and every option we have
that Coverity does not has to be **listed as an addition**, so "we support more" is a visible
fact rather than an accident.

`scripts/mutate_capture_options.py` is the gate's own mutation test: ten deliberate drifts,
all of which must make the gate fail. It found three real holes in the first version — the
gate only looked at the *first* spelling of an option, it never checked that the canonical
name itself matched, and it never checked that the reference agreed with itself.

The comparison is **per compiler**. The reference names an option by its canonical name and
lists the spellings as aliases, and two compilers may spell an option the same way, so the
reference's entry says which compiler it is and the comparison is made against **that**
compiler's entry. Falling back to whichever entry happened to come last would compare the wrong
thing and hide exactly the mistake that matters: an option that belongs to one compiler and is
attributed to another is the mistake the capture's compiler-aware lookup turns into a lost
option.

### 7.0.5a The compiler's own option list is the yardstick

`javac --help` and `javac --help-extra` are the authority on which options exist and how they
are spelled, and a **real javac run** is the authority on whether they are accepted. The table
was written from memory, and memory drops the double-dash spelling of an option.

The options that were missing, and what each one cost:

| the option | what it cost when it was missing |
| --- | --- |
| `--source`, `--target` | the language level was unknown for a build that spelled it the long way |
| `--class-path` | **the whole classpath** — the option was unknown and the value vanished with it |
| `-proc:none`, `-proc:full` | the annotation-processing switch was half-known |
| `-implicit:none`, `-implicit:class` | the same, for the implicit-compilation switch |
| `-g:none`, `-g:lines`, `-g:vars`, `-g:source` | the debug-info switch was half-known |
| `-Akey[=value]` | the annotation processor's own options were unknown |
| `--default-module-for-created-files` | the module a generated class file belongs to was unknown |

A missing option does not fail: it is silently unknown, and the value that follows it is consumed
as a positional argument — which is a source file only if it ends in `.java`. So a missing option
loses **two** things, the option and its value.

### 7.0.5aa The other two compilers are measured too

`javac` is not the only compiler a build runs, and the other two have their own option sets. They
are measured against the **real** compiler, the same way.

**ECJ** is measured by running its own `-help` (ECJ 3.33.0). Four options were missing from the
table, and the first one is the interesting one:

| the option | what it cost when it was missing |
| --- | --- |
| `-annotationpath` | **the whole annotation path** — the option was unknown and the path vanished with it |
| `-warn:none` | ECJ's spelling of the warning switch was unknown |
| `-?:warn`, `-help:warn` | the advanced warning options were unknown |

`scripts/check_compile_options_ecj.py` is the gate, and
`scripts/mutate_compile_options_ecj.py` is its mutation test. The gate checks two things: that
every option ECJ lists is in the table, and that every option ECJ lists is **reachable by ECJ's
lookup** — an option attributed to another compiler is rejected by the lookup, so the option would
be dropped and its value read as a source file.

**sjavac** is the interesting one, because sjavac was **removed from the JDK after Java 11**. A
modern JDK has no `com.sun.tools.sjavac` at all, so the reference is a JDK 11's own
`com.sun.tools.sjavac.options.Option` enum — which *is* sjavac's option table, and which carries
the spelling every constant accepts. The enum is read reflectively, because `jdk.compiler` does
not export the package.

The measurement produced three facts that shape the table:

1. **sjavac has its own option table, and it consumes those options itself.** Everything sjavac
   does not recognise is **forwarded to javac**. Measured on the real compiler, sjavac forwards
   `-encoding`, `-source`, `-target`, `--release`, `-g`, `-nowarn`, `-proc:none`, `-processor`,
   `-processorpath`, `-bootclasspath`, `--add-modules`, `-Xlint`, `-parameters` and `-A`. Those
   are **javac's** options, so the table must not claim them for sjavac — otherwise javac's own
   lookup would reject them.
2. **sjavac's own options are 26**: `-src`, `--source-path`, `-sourcepath`, `--module-path`, `-p`,
   `--class-path`, `-classpath`, `-cp`, `-x`, `-i`, `-tr`, `-copy`, `-j`, `--server:`,
   `--startserver:`, `-implicit:`, `--log=`, `-verbose`, `--permit-artifact=`,
   `--permit-unidentified-artifacts`, `--permit-sources-without-package`,
   `--compare-found-sources`, `-d`, `-s`, `-h`, `--state-dir=`.
3. **sjavac takes the value of an option from the next token.** `-j 2` works and `-j=2` is
   **rejected**; the same holds for `--server:`, `--startserver:` and `--state-dir=`. The spelling
   says where the value *may* go, not that it must be joined.

`scripts/check_compile_options_sjavac.py` is the gate. It reads the compiler's own enum, checks
that every option sjavac has is in the table, checks that every option sjavac has is reachable by
**sjavac's** lookup, and checks that no option sjavac **forwards** to javac is claimed as
sjavac's. `scripts/mutate_compile_options_sjavac.py` is its mutation test.

**The index is keyed by the name AND the compiler.** That is not tidiness: ECJ and sjavac both
have `-src`, `-copy`, `-x`, `-i`, `-tr` and `-j`, and they mean different things by some of them
— sjavac's `-copy` may repeat and ECJ's may not. An index keyed by the name alone holds **one**
option per name, so the second entry silently overwrote the first and **ECJ's lookup rejected all
six**: the option was dropped, and the value that followed it was read as a source file. So the
index holds one option per `(name, compiler)` pair, and the compiler's own entry wins over a
shared one.

### 7.0.5b An argument file is part of the command line

javac reads its options from a file when an argument starts with `@`. A build that puts its
classpath in such a file has told javac everything through the file, and the command line carries
almost nothing — so a capture that did not open the file recorded a compile with an empty
classpath and an unknown language level.

The format was measured against javac, not guessed:

- the file is split on **whitespace**;
- the quotes around a token are **stripped**, but they do **not** group it — so `-d "C:\a b"` is
  the option `-d` with the value `C:\a` and a stray `b"` that javac treats as a source file;
- an argument file that cannot be read leaves the argument alone, so the record shows that the
  compile was driven by an argument file.

### 7.0.6 One compilation produces only one record

**One compilation goes through several entry points**: the command-line path first goes through `com.sun.tools.javac.Main#compile`,
then through `com.sun.tools.javac.main.Main#compile`; the API path first goes through `JavacTool#getTask`,
then through `JavacTaskImpl#call`. Without deduplication, one compilation would produce several nearly identical records.

The deduplication key is **not the argv**: when the CLI and the API reach the same compilation the argv differs,
while two genuinely different compilations may have exactly the same argv. The key is **the identity of
this compilation** (source files + output directory + classpath + source path + encoding + language level),
see `CaptureEntry.compileIdentity()`. The source files are **sorted** before the comparison,
because the same set of files arrives in a different order from the API and from the command line.

### 7.0.7 Gradle: the daemon, the build cache, and which channel reaches the JVM

Gradle is the most troublesome one: the compilation happens **inside the daemon process** (invisible to the JVM the agent is attached to),
and when the build cache is enabled the compilation **never runs at all** (so there is nothing to capture).

`GradleHooks` hooks Gradle's own configuration API the way Coverity's `common-capture.properties` does,
changing two things: `StartParameter#setBuildCacheEnabled` is forced to false, and
`DaemonParameters#isEnabled()` is forced to false.

These two hooks are **optional** — turning off the daemon makes the build slower, and users who do not
need the capture should not have to pay for it. They are enabled by `plumb.capture.gradle.disable-daemon=true`.

#### The classpath comes from the compiler hook, not from a Gradle hook

The obvious thing to do is hook Gradle's own compile spec
(`DefaultJvmLanguageCompileSpec#setCompileClasspath(List<File>)`), because that is where Gradle hands its
resolved classpath to the compiler. **Measured, that hook never fires.**

Gradle assembles the compile in the daemon but *performs* it in the same JVM through `javax.tools`. The
compile spec lives in the `gradle-language-jvm` jar and is loaded lazily into a class loader of Gradle's
own, so it is not even loaded in the JVM that holds the agent — `Instrumentation#getAllLoadedClasses()`
does not contain it. Hooking it would therefore record nothing, and even if it did, it would record a
second and worse copy of a compile the compiler hook already recorded exactly.

So the classpath comes from the **`JavacTool#getTask` hook**, and it is complete: the capture names the
**output directories** of the upstream modules, the dependency jars, the module path, the release level,
the encoding, the source roots and the destination directory. The upstream output directories are the
reason this matters: they are directories that only exist after those modules have been built, so
reading `build.gradle` cannot tell you about them — but asking the compiler can.

`GradleCaptureE2EIT` asserts exactly that, on a real two-module Gradle build: the downstream module's
capture names the upstream module's output directory and the dependency's jar.

#### Which channel reaches Gradle's JVM

This is not obvious, and getting it wrong makes the configuration silently inert. Measured on Gradle
9.2.0, by reading the command line of the JVM Gradle forked:

| Channel | Reaches the Gradle JVM? |
| --- | --- |
| `org.gradle.jvmargs` with `-javaagent` | **yes** |
| `org.gradle.jvmargs` with `-D` | no — every `-D` is dropped |
| `GRADLE_OPTS` | no — it applies to the launcher, not the daemon |
| `systemProp.name=value` in `gradle.properties` | no — it applies to the build JVM, and the compile runs in the forked daemon |
| the agent's own arguments (`-javaagent:jar=key=value`) | **yes** |
| the environment | **yes** — the JVM inherits it |

So the way to configure the agent inside Gradle is to pass the settings as the **agent's arguments**:

```properties
org.gradle.jvmargs=-Xmx1g \
  -javaagent:/path/plumb-build-capture.jar=plumb.capture.gradle.disable-daemon=true
```

The agent turns its own arguments into system properties (`CaptureAgent.applyAgentArguments`), so the
property is then set inside the JVM that actually performs the compile. An explicit system property
still wins over an agent argument, because it was set deliberately.

The environment variable `PLUMB_CAPTURE_GRADLE_HOOKS` is the second channel, for the case where the agent
is not attached through `-javaagent`.

#### One record per compile

Gradle's compile reaches the hook through `javax.tools`, and a Gradle build compiles several modules.
Each module's compile is a different compile, so each produces its own record — and the dedup by
compile identity (`CaptureEntry.compileIdentity()`) keeps them apart, because the source files and the
output directory differ.

### 7.1 Key design decisions

1. **Zero-intrusion integration** — the adapter only interacts with the main pipeline through the `BuildProject` data class, so
   `ScanRunner` / `ScanConfig` / `JavaSourceParser` need no changes.
2. **ServiceLoader SPI** — no Spring / OSGi / DI container; the standard JDK SPI is used
   (`META-INF/services/org.plumb.build.BuildAdapter`). A new adapter = one services file +
   one class, with no change to the `plumb-main` or registry code.
3. **Parser-light (core)** — no dependency on Maven Embedder / Gradle Tooling API / Bazel Build API (to avoid
   huge dependencies + running daemons + JDK version compatibility trouble); file parsing covers the common cases.
   `plumb-build-maven` is the **enhanced path** beyond core: it needs Aether, at the price of the dependency volume and one real resolution.
   Note that it is still a compile-time dependency of `plumb-main` (the pom has no `<optional>`) — "enhanced path" refers to
   whether it is used at runtime, not to whether it can be absent at build time.
4. **Conservative by default** — the adapter cannot find the dependencies / a parse error → a warning, not a failure. The user can always fall back on
   `--classpath`.
5. **The classpath delegates to the m2 repo** — consistent with the default behaviour of Gradle / Bazel.
6. **Multi-module source dedup** — Bazel / Gradle may have several targets referencing the same `.java`; the adapter
   dedupes at the project level.
7. **Aether's repo id must match settings.xml** — next to every artefact in the local repository, `_remote.repositories`
   records which repo id it was downloaded from. Hardcoding `central` while the local repository is actually
   filled through an `alimaven` mirror makes the offline resolution report `(present, but unavailable)`.
   Therefore `MavenSettings` reads the `<mirrors>` of `settings.xml` to get the real id + url.

### 7.2 Implementation constraints (the pitfalls)

The following are the constraints that must be respected when implementing `plumb-build`; they are recorded here so that
the pitfalls are not repeated:

- **Maven pom parsing**: `maven-model` + `plexus-utils` (MavenXpp3Reader) is the minimum
  dependency combination for reading `pom.xml`; `maven-core` / `maven-resolver` are not needed (they bring daemon + embedder side effects).
  → This constraint applies to `plumb-build-core`. `plumb-build-maven` **does** want `maven-resolver`,
  because what it computes is the transitive dependency tree, which cannot be obtained by statically reading the pom.
- **Test isolation**: the tests must use a hermetic empty local repo, not the developer's real `~/.m2/repository`
  — an uncertain environment makes the tests flaky.
  → `plumb-build-core` respects this rule. The `TransitiveResolverTest` of `plumb-build-maven` is the **exception**:
  it deliberately uses a real local repository, because what it has to verify is exactly that "the transitive dependencies really were resolved";
  with an empty repository that test would be meaningless. Its assertions do not depend on which version happens to be on the machine (it skips when it is not found).
- **Bazel glob literals**: the `*/` in a Bazel `glob(["**/*.java"])` cannot be written directly into a Java source string
  (javac would mis-parse it as the end of a block comment), so it has to be split and concatenated.
- **Ant relative paths**: a `<pathelement path="lib/jar"/>` is relative to the projectRoot (consistent with javac's own
  behaviour), so it has to be resolved with the projectRoot as the base, and not directly with `Path.of("lib/jar")`.
- **The ByteBuddy version must support the target JDK**: 1.15.11 does not support Java 25 (class file 69), and
  reports `IllegalArgumentException: Java 25 (69) is not supported`.
  And this exception **only shows up in `AgentBuilder.Listener.onError`**, because the default listener is `NoOp` →
  completely silent. The diagnostic means: always attach a `Listener.Adapter` and print the root cause.
- **`Advice` can hook a method that overrides nothing**: the early conclusion was
  that `com.sun.tools.javac.main.Main#compile(String[])` has no parent method and `Advice` reports
  `Cannot call super (or default) method`, so one had to fall back to hand-written ASM. **That conclusion is wrong** —
  `Advice` is an **inline weaving**: it copies the hook's bytecode into the method under test, so it needs neither a super method
  nor a delegation target. In practice `Main#compile(String[])`, `JavacTool#getTask` and
  `JavacTaskImpl#call` can all be hooked with `Advice`.
  → So **do not hand-write ASM**: `Advice` saves the manual computation of the local variable slots (slot 0 is the parameter for a static method,
  slot 0 is `this` and the parameters are in slot 1 for an instance method — getting that wrong corrupts the compiler's stack), and the
  bytecode shape of the JDK's internal classes changes, which breaks a hand-written visitor as well.
- **The classes the agent hooks live in the named module `jdk.compiler`**: the instrumented `com.sun.tools.javac.Main`
  is loaded by the application class loader, while the agent jar is only visible to the agent's own loader →
  `NoClassDefFoundError`. The solution: **inject the hook classes into the bootstrap class loader**
  (`appendToBootstrapClassLoaderSearch`), which is the ancestor of all loaders and visible to everyone.
  The price: the hook classes **must not reference anything outside the bootstrap** (no SLF4J, and no direct reference to
  `javax.tools.FileObject`). The bootstrap jar must have a **complete dependency closure** — including nested classes
  (`JavacOptions$Builder`). `Advice` **swallows `Throwable`**, so a missing class does not raise an error, it
  just **silently records nothing**. → Use `BootstrapInjectionTest` to read the constant pool of the compiled class as a structural gate.
- **The bootstrap side must call through the exported interfaces by reflection**: the implementation classes of `com.sun.tools.javac` are not exported API and
  are unreachable by a direct reference in the bootstrap scenario. Reading the source path and the like always goes through the **exported interfaces**
  (`javax.tools.JavaFileObject` etc.) by reflection.
- **The classpath has to be split by the platform separator**: splitting on both `:` and `;` breaks the Windows drive letter
  (`C:\foo` → `C` + `\foo`). → On Windows only split `;`, on the other platforms only split `:`.
- **The two javac entry points do not overlap**: the command line goes through `com.sun.tools.javac.Main#compile` (static), while
  the `javax.tools` API goes through `com.sun.tools.javac.main.Main#compile` (instance).
  One command-line compilation **goes through both of them in turn**, so the capture has to be deduplicated.

## 8. rule-id consistency

The emit-id of the style rules follows the `ast-<category>-<bare>` naming scheme (the category is the name of the sub-package the checker lives in:
`style` / `naming` / `security` / `error` / `bug` / `design` / `performance` /
`quality` / `concurrency`). Measured over the 579
`new RuleId("...")` literals under `plumb-style/src/main/java`, **not one of them ends with `-style`**:

| Prefix | Count | | Prefix | Count |
| --- | ---: | --- | --- | ---: |
| `ast-style` | 347 | | `ast-security` | 37 |
| `ast-performance` | 62 | | `ast-error` | 34 |
| `ast-naming` | 50 | | `ast-bug` | 19 |
| `ast-design` | 18 | | `ast-quality` | 10 |
| `ast-concurrency` | 2 | | `dfa-style` / `dfa-security` | 3 / 1 |

This naming is referenced in **three** places, and any one of them getting out of sync silently drops findings:

| Referrer | Role |
| --- | --- |
| `new RuleId("ast-style-foo", ...)` in `*Check.java` | produces the emit id |
| `plumb-style/.../CheckerCategories.java` | the classpath-scan registry (594 `m.put(...)` entries) |
| `plumb-main/.../ScanRunner.java::isRuleIdAllowed` (`:1823`, `:1834`) | rule filtering; it has to accept the canonical id, the bare slug and the dashed form |

**The data flow**:

```
*Check.java                 new RuleId("ast-style-foo", ...)
    │                              │
    ▼                              ▼
emit rule_id="ast-style-foo"
    │
    ▼
ScanRunner.isRuleIdAllowed(emit, registry)   ← canonical / bare / dashed multi-form matching
    │
    ▼
emit enters the final set of findings
    │
    ▼
NDJSON output, the cwe field comes from the rule's RuleDef.cwe()
```

The finding key → CWE mapping on the DFA side comes from `plumb-dfa/src/main/resources/cwe-mappings.yaml`,
which is an independent source from the `RuleDef.cwe()` on the AST side.

**Aliases**: a string with the `-style` suffix (`ast-style-random-iv-cbc-style`) is only kept as
a **alias** of the `RuleDef` (for compatibility with the old YAML whitelists); it is not the
emit-id of any rule. The `ast-style-` prefix / `-style` suffix stripping logic in `ScanRunner.isRuleIdAllowed`
exists precisely to match these aliases.

**Architectural takeaways**:

1. Changing the naming convention means that **all referrers** have to change: emit-id ↔ mapping ↔ yaml whitelist ↔ Scanner matching.
   Missing one of them silently drops findings, without an error.
2. The mapping should be **rebuilt automatically** rather than maintained by hand: `scripts/gen_rules_index.py` generates the index from the source, keeping the mapping
   consistent with the source.
3. The classpath-scan registration mechanism needs **double validation**: a checker must both (a) be registered in `CHECKER_CATEGORIES` and
   (b) provide a `rule()` method. Missing one of them means a silent drop.

## 9. The test system

Each module is tested independently with mvn. The number of tests per module is generated from the surefire reports by
`scripts/gen_rules_overview.py`, and is therefore measured; see the test statistics section of
[`RULES.md`](../rules/RULES.md).

The end-to-end measurement is done by the categories under `testcase/`: every category has its own fixture, manifest and
`RESULT.json`, recording the TP / TN / FN / FP of each case. The supported categories and the current measured results are in
[`testcase/SUPPORT-MATRIX.md`](../../testcase/SUPPORT-MATRIX.md).

The real end-to-end LLM test of `plumb-ai` only runs when `PLUMB_AI_E2E=1` is set.

## 10. Extension points

| What you want | Where to add it |
| --- | --- |
| A new taint source / sink pattern | `plumb-dfa/src/main/resources/sink-families.yaml` (add `families.<name>.patterns`) |
| A new fact kind | `plumb-dfa/src/main/resources/fact-kinds.yaml` (the configuration catalogue) or the `registryWithBuiltins()` of `plumb-dfa/.../fact/BuiltinFactKinds.java` (the built-in catalogue) |
| A new taint rule | `plumb-dfa/.../checkers/taint/<Name>Checker.java`, extending `TaintedCheckerBase` |
| A new boundary rule | `plumb-dfa/.../checkers/boundary/`, extending `BoundaryConstraintChecker` |
| A new control-flow check | `plumb-dfa/.../checkers/controlflow/`, implementing `DfaChecker` |
| Enabling / disabling a checker | `plumb-dfa/src/main/resources/checkers.yaml` |
| A new style rule | `plumb-style/<category>/<Name>Check.java` + an `m.put(...)` in `CheckerCategories.java` |
| A new **Kotlin DSL** AST rule (without writing Java) | Write a rule file (XML / YAML) and pass it in with `--dsl-rules <file>`. See [`KOTLIN-DSL.md`](KOTLIN-DSL.md) |
| A new CLI flag | The `buildOptions()` of `plumb-main/.../SastMain.java` |
| A new YAML section | The `fromMapImpl()` of `plumb-main/.../ConfigLoader.java` |
| A new output format | `plumb-reporter/.../FindingFormatter.java` + a sibling of `NdjsonEmitter` / `SarifEmitter` |
| A new call graph strategy | `plumb-dfa/.../cg/CallGraphStrategy.java` + `CallGraphStrategyFactory.java` |
| A new build-system adapter | `plumb-build/plumb-build-core/.../<Name>Adapter.java` implementing `BuildAdapter` + the `META-INF/services` registration |
| A new AI rule prompt | The `getAiPrompt(Finding)` method of the checker (inline) or a `--rule-prompts-dir/<id>.md` override |

### 10.1 The concrete steps for adding a new style rule

1. Write the rule in `plumb-style/src/main/java/org/plumb/style/<category>/FooCheck.java`, implementing the `AstChecker`
   interface (override the per-node `visit(X, ctx)` overload you care about)
2. **Add `m.put("FooCheck", "<category>");` in `CheckerCategories.java`** (in alphabetical order; that
   file is generated by `scripts/gen_categories.py`)
3. Rebuild: `mvn -o -pl plumb-style install`
4. Verify: `sast-scan --list-rules | grep FooCheck`

⚠️ The consequence of missing step 2 is a **silent loss**: `AstCheckerRegistry.byCategory(...)` does not find the rule and
neither emits it nor reports an error. (`AstCheckerRegistry.builtins()` goes through a classpath scan and does not depend on
`CheckerCategories`; a missing registration affects the path that loads by category.)
The classpath-scan registration mechanism requires both (a) registration in `CHECKER_CATEGORIES` and
(b) a `rule()` method, and missing one of them means a silent drop.

### 10.2 Adding an AST rule with the Kotlin DSL (without writing Java)

When you do not want to write a Java checker, you can use the rule engine of `plumb-dsl-kotlin`:

1. Write a rule file (in XML or YAML format) — see [`KOTLIN-DSL.md`](KOTLIN-DSL.md) §2
2. `sast-scan -i src/main/java --dsl-rules rules/ast-rules.yaml`

The DSL rules and the built-in style rules go through **the same** production path: a hit produces the same `Finding`,
enters the same registry, goes through the same whitelist / blacklist filtering and the same reporter.

The only structural difference is **how they are triggered**: a built-in checker relies on the per-node
dispatch of `AstDispatcher`, while a DSL rule runs in `AstChecker.finish(ctx)` — because the dispatcher dispatches by exact
node class, so it never hands a `CompilationUnit` to a checker and cannot express
abstract node types such as `Statement` / `Comment`. See
[`KOTLIN-DSL.md`](KOTLIN-DSL.md) §5.3 for the details.

The dataflow / taint rules are **not** in this engine; they are still driven by the YAML configuration of `plumb-dfa`.

## 11. Known boundaries

The following behaviours are the known boundaries of the current implementation, not promises about defects:

- **Rule id matching**: `<category>-<bare>` is the canonical emit id; the `-style` suffix is a compatibility alias,
  not an independent rule.
- **Virtual call resolution**: matched by method name + parameter count (`ChaCallGraphStrategy`), without a transitive class-hierarchy
  closure; the `classToParent()` / `classToChildren()` tables are populated and aggregated across compilation units, but no analysis
  consumes them today.
- **Array taint**: it does not propagate across function boundaries.
- **Path sensitivity**: implemented by `SolverZ3Bridge` / `BoundaryConstraintChecker`, but not every sink goes
  down this path.
- **A missing fact does not mean safety**: an empty points-to set, a missing null fact and a may-alias overlap all cannot
  be used to prove that a piece of code is safe.

## 12. The size of the released artefact

The released artefact is `plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar`. The product resources are only the configuration
the engine needs at runtime:

| Resource | Role |
| --- | --- |
| `sink-families.yaml` | the sink family and pattern catalogue |
| `fact-kinds.yaml` | the fact kind catalogue (merged with the built-in kinds) |
| `checkers.yaml` | the DFA checker switches and parameters |
| `cwe-mappings.yaml` | the DFA finding key → CWE mapping |
| `receiver-passthrough.yaml` | the receiver pass-through patterns |
| `tuning.yaml` | the engine tuning |

The test corpus is in `plumb-dfa/src/test/resources/juliet-corpus/`; it is a test resource and
is not shipped with the product jar.
