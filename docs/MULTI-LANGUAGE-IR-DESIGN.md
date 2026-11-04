# Unified Multi-Language IR Design: Kotlin / Scala / Other JVM Languages

[English](../README.md) | [中文](zh/MULTI-LANGUAGE-IR-DESIGN.md)

> Status: design draft (not implemented)
> Date: 2026-10-31 (v2, the route has been changed by decision to **source AST → IR as the main line**)
> Scope: **IR generation + data-flow analysis**. AST rules are out of scope for this round; the appendix only gives the mature framework choices for each language.

---

## 0. Conclusion First

**The goal of "one IR covering all analysis" is already 80% done in Plumb, and the remaining 20% is pure incremental work.**

Key findings (based on an actual audit of the existing code, not speculation):

| Conclusion | Evidence |
|---|---|
| The IR layer is **completely language-agnostic** | A full-package grep over `plumb-tac/src/main/java/org/plumb/tac/ir/` finds no `javax`/`javaparser`/language-specific references; `TacType` only expresses the JVM type system (`CLASS`/`ARRAY`/primitives), and Kotlin/Scala generics/Union types degrade into it just as well |
| There are already **two frontends** | `plumb-tac/translate/` (JavaParser AST → TAC) + `plumb-tac/bytecode/jimple/` (SootUp Jimple → TAC). The multi-language frontend is the **third instance of the same pattern**, not a new architecture |
| The rule layer has **zero language coupling** | `checkers.yaml` declares rules with `sink_family` / `source_kind` / `taint_domain`; `TaintRule` is only strings plus a domain enum. Kotlin/Scala rules **need no new syntax** — reuse the existing YAML |
| The analysis engine **needs no change** | The `DfaChecker` lifecycle only consumes `TacInstruction` / `TacFunction`; the Z3 solver, Points-to, and the Summary mechanism are all language-agnostic |
| The IR extension surface is **small and already has precedent** | The `SourceOrigin` enum has already proven that "adding a language dimension to `TacFunction`" is a low-risk operation, and `SourceOrigin.java:24-27` already states the serialization contract: adding a field is backward compatible |

### The Route Change in This Version (v2)

The v1 document proposed a hybrid route of "bytecode as the default channel + source as an enhanced channel". **v2 has been changed by decision to: source AST → IR is the only main line**, and the bytecode channel is demoted to the "library dependency analysis channel" (used only to analyse `.jar` files, never to analyse user source).

Reasons for the change (all hard technical constraints, not preferences):

1. **The bytecode path loses source-level semantics and cannot support Kotlin's null safety or synthetic-code handling.**
2. **The bytecode path cannot see lambda bodies** (Kotlin 2.0+ defaults to `invokedynamic`), so the taint flow breaks outright — that is a false negative, not a precision loss.
3. **The bytecode path cannot obtain param-level annotations** (framework annotations at `CLASS` retention are invisible), so framework rules (`@RequestMapping` / `@Transactional`) fail outright.
4. **Parsing `@Metadata` on the bytecode path is a long-term liability** — the Kotlin metadata format has no stability guarantee.

The bytecode channel is **not removed**, but its responsibility is narrowed to: analysing third-party `.jar` files the user supplies (this is Plumb's existing `LIBRARY` origin capability, and it is unrelated to multi-language support).

### Five Direct Answers

**Q1: Besides Java/Kotlin/Scala, does Coverity support other JVM languages?**

**No.** See §2.1. The JVM languages in Black Duck's official language list are only **Java / Kotlin / Scala**. Groovy, Clojure, JRuby, Gosu and Xtend are **all absent from Coverity's supported-language list**. This confirms our judgement in reverse: in the JVM ecosystem the three languages that genuinely need deep data-flow analysis are exactly these three.

**Q2: Can we convert Kotlin/Scala source directly to IR via AST instead of compiling to class files first?**

**Yes, and that is exactly the main line of v2.** Two verified paths to implementation:
- **Kotlin**: the **Standalone mode** of the `Analysis API` (`buildStandaloneAnalysisAPISession`, officially provided, headless, runs the complete FIR analysis pipeline inside a pure JVM process), or the `IrGenerationExtension` of the **Compiler Plugin API** (which gives direct access to the Kotlin Backend IR tree).
- **Scala 3**: the `PluginPhase` of the **Compiler Plugin API** (`dotty.tools.dotc.plugins.PluginPhase`), which obtains the **`tpd.Tree` with complete type information** after `typer` and before `pickler`.

Details and trade-offs are in §3.3 / §3.4.

**Q3: Does the IR have to change?**

**Yes, but the change surface is strictly squeezed down to 2 fields + 2 enums + 1 opcode + 1 type dimension** (§3.2). The principle is: **the IR only expresses things that are "semantically equivalent"** — no language-specific opcodes are added for Kotlin `when` or Scala `match`; they are instead degraded into a `BRANCH` chain at the frontend.

**Q4: Is Scala 2 supported?**

**No.** The Scala 2 and Scala 3 compiler internal APIs are completely different (`Symbol` vs `Denotation`, `Tree` vs `TypedTree`), and Scala 2 has entered its maintenance period (officially there will be no 2.14; the project goes straight to Scala 3). Writing a compiler plugin for a language in maintenance has an extremely poor ROI. See §3.5.

**Q5: How are versions decided? Including Java.**

**Target the latest stable release, but "following versions" and "pinning versions" are two different things** (§0.5):

| Language | Target version | Follow strategy |
|---|---|---|
| Java | **25** (LTS) | **Free-follow** (JavaParser is a stable API) — **the current parser only reaches `JAVA_21`**; Java 22–25 requires upgrading the JavaParser dependency, see §0.5.6 |
| Kotlin | **2.4.21** | **Pin the minor version** (the Analysis API is an experimental API) |
| Scala | **3.10.0** | **Pin the minor version** (dotc is an internal API) |

> **A real defect found during the audit**: `JavaSourceParser` hardcodes `JAVA_17`, while the `JavaModule.languageLevel` field **is passed in but never used by anyone** — the whole chain is broken. Measured, Java 21 switch patterns **fail to parse outright** under `JAVA_17`.
> Worse, the TAC translator has **zero coverage** for Java 14+ features (`SwitchExpr` / `RecordDeclaration` / `PatternExpr`) — even when parsing succeeds it cannot produce correct TAC. See §0.5.3; this is listed as J1/J2 of Phase 0.
>
> **Already fixed**: the language level chain and the Java 14+ translation have both landed; see §0.5.6.
>
> **Good news that comes along for free**: the accessor of a Java `record` is a **compiler-synthesized method**, and it is the same kind of thing as the `componentN` of a Kotlin `data class` or the `copy` of a Scala `case class`. If we first implement "synthetic method identification" on the Java side, Kotlin/Scala inherit an already-validated implementation paradigm.

### 0.6 Current Status: the Frontends Are Wired into the Scan Path (continued 2026-11-02)

**Kotlin and Scala can really be scanned now.** See §12 for details, but the conclusion goes here first:

| Stage | Status |
|---|---|
| Kotlin / Scala frontend implementation | ✅ |
| Frontend unit tests | ✅ Kotlin 42 / Scala 28 |
| Frontends enter the **real scan path** (`ScanRunner` dispatches by extension) | ✅ §12.1 |
| The callee signature carries the owner, so the sink family can be matched | ✅ §12.2 defect two |
| **The scan produces findings end to end** (SQL injection on `.kt` / `.scala` reports CWE-89) | ✅ §12.1 |
| Style checkers on Kotlin/Scala | ❌ Deliberately not done, see §12.4 |
| Kotlin lambda bodies / Scala closures → standalone `TacFunction` | ❌ Phase 3, see §10.2 |

---

## 0.5 Version Strategy: Target the Latest Stable Release

**Principle**: **follow the latest stable release by default**; do not chase nightly/EAP builds; **pin only one minor version line** per language, and go through an explicit validation process for minor-version upgrades.

### 0.5.1 Target version per language (as of 2026-11-02, verified by measurement)

| Language | Target version | Reason | Remarks |
|---|---|---|---|
| **Java** | **Java 25** (LTS) | The local runtime is already `openjdk 25.0.1 LTS`; Java 25 is the current LTS | **The frontend currently only reaches Java 21** — the ceiling of JavaParser 3.26.4 (see §0.5.6). Genuinely supporting 22–25 syntax requires upgrading JavaParser |
| **Kotlin** | **2.5.0-Beta2** (Analysis API) | **The only version of `kotlin-analysis-api` currently on Maven Central is `2.5.0-Beta2`** — not a stable release, but there is no alternative | Plumb's DSL script engine is currently pinned to **2.2.0** (for JDK 25 compatibility), **do not touch it** — that is another dependency chain (§0.5.4) |
| **Scala** | **3.10.0** | The latest stable release; 3.9 is the LTS line | `scala3-compiler_3:3.10.0` has been verified to resolve from Central. Note that Maven metadata's `release` field still says `3.10.1-RC2`, but `3.10.0` has actually been released as a stable version |
| **JavaParser** | **3.26.4** (in use) | The named ceiling is `JAVA_21` | **`BLEEDING_EDGE` in this version == `JAVA_21`** (verified by identity), not newer syntax support (§0.5.6) |

### 0.5.2 Three "version following" strategies (different risk models)

**The follow strategy differs per language frontend.** This is the central judgement of this section:

| Language | Frontend shape | Follow strategy | Reason |
|---|---|---|---|
| **Java** | JavaParser (**stable API**, semantic versioning) | **Free-follow**, upgraded along with Plumb | JavaParser has a `LanguageLevel` enum; adding a new language feature does not require changes to our code |
| **Kotlin** | Analysis API Standalone (**experimental API**) | **Pin the minor version + explicit upgrade** | The official docs say "subject to incompatible changes"; a minor-version bump requires re-running the cross-validation harness |
| **Scala** | Compiler Plugin `PluginPhase` (**internal API**) | **Pin the minor version + explicit upgrade** | The internal API **may be incompatible even across patch versions** |

**Why can Java follow freely while Kotlin/Scala must be pinned?**

| | JavaParser | Kotlin Analysis API | Scala dotc |
|---|---|---|---|
| API nature | A stable third-party library | Officially from JetBrains but marked `@KaExperimentalApi` | A compiler internal implementation |
| Version compatibility promise | Semantic versioning | "May contain incompatible changes" | None (only `CrossVersion.full` is compatible) |
| Impact of a new language feature on us | Just one more `LanguageLevel` enum value | May change the shape of the API | May change the shape of `tpd.Tree` |
| Upgrade cost | Low (change the pom + run the tests) | Medium (re-run the cross-validation harness) | High (re-run the whole Scala fixture suite) |

> **Corollary — "following the latest" and "pinning the version" are not in conflict; they are two different things.**
> - **At the language-feature level**: follow the latest (Java 25's sealed/record patterns, Kotlin 2.4's new syntax).
> - **At the frontend-API level**: pin (the Kotlin/Scala compiler internal APIs).
>
> These two things do not conflict — because **language features and frontend APIs are decoupled**. Adding a `JAVA_25` enum value to JavaParser does not require us to change a single line of code; adding a new syntax feature to Kotlin does not require us to change the translator either.

### 0.5.3 Gaps in the Current State of the Java Frontend (**already fixed**, see 0.5.6)

> **Status update (2026-11-02)**: this section describes the state **at the time of the audit**. Both J1 and J2 have been implemented;
> what actually landed is described in [§0.5.6](#056-landed-the-java-language-level-chain-and-java-14-translation).

**This is a real problem found in this round of auditing. It is unrelated to multi-language support, but it does affect the requirement of "targeting the latest version".**

**Current state** (`plumb-parser/src/main/java/org/plumb/parser/JavaSourceParser.java`):

```java
public JavaSourceParser() {
  this(ParserConfiguration.LanguageLevel.JAVA_17, ...);   // ← hardcoded JAVA_17
}
public JavaSourceParser(List<JavaModule> modules) {
  this(ParserConfiguration.LanguageLevel.JAVA_17, ...);   // ← hardcoded JAVA_17
}
// Javadoc says: "if all modules report null, falls back to JAVA_17"
```

**Problem 1: `JAVA_17` is hardcoded, and `JavaModule.languageLevel` is never used.**

`JavaModule` has a `String languageLevel` field, and `ScanRunner.java:1193/1203/1221` does indeed pass the build project's language level in — **but no code ever maps it back to `ParserConfiguration.LanguageLevel`**. The whole chain is broken:

```
BuildProject.languageLevel (String)
  → JavaModule.languageLevel (String)   ← passed in
  → ???                                 ← broken
ParserConfiguration.LanguageLevel        ← always JAVA_17
```

**Problem 2: Java 21+ syntax fails to parse outright (measured).**

Measured with JavaParser 3.26.4 on `sealed interface` + `record` + switch pattern:

```
JAVA_17       -> ok=false, problems=2
    "Switch patterns not supported. Pay attention that this feature is supported
     starting from 'JAVA_21' language level..."
JAVA_21       -> ok=true, problems=0
BLEEDING_EDGE -> ok=true, problems=0
```

**Problem 3: The TAC translator has zero coverage for Java 14+ features.**

`TacTranslator.java:71-72` states this itself:

> "Out of scope: lambdas, anonymous inner classes (skipped), **record patterns, sealed-type patterns**, annotations on local decls (ignored for now)."

Auditing the nodes the translator actually supports:
- ✅ `SwitchStmt` (`StatementTranslator.java:141`) — the **old-style** switch (pre-Java 14)
- ❌ `SwitchExpr` (the Java 14+ `switch` expression) — **not found**
- ❌ `RecordDeclaration` (Java 16 record) — **not found**
- ❌ `PatternExpr` (Java 16+ instanceof pattern / Java 21 switch pattern) — **not found**
- ⚠️ `InstanceOfExpr` (`ExpressionTranslator.java:127`) — the **old-style** instanceof (pre-Java 14)

**This means**: even if the language level is raised to 21/25, Java 17+ code can **parse** but cannot **produce correct TAC**. Record accessors, switch-pattern branch bindings, exhaustiveness of sealed types — all are sources of false negatives.

**The fix (in two steps, which can run in parallel with Phase 0)**:

| Step | Content | Risk |
|---|---|---|
| **J1** | **Connect the language level chain**: `JavaModule.languageLevel` (String) → a `ParserConfiguration.LanguageLevel` parser (supporting `"17"` / `"21"` / `"25"` / `"latest"`), and replace the `JAVA_17` hardcoding with "configuration first, default `JAVA_21`" | Low. Pure wiring |
| **J2** | **Add Java 14+ syntax translation**: `SwitchExpr` / `RecordDeclaration` / `PatternExpr` / sealed exhaustiveness | Medium. A `record`'s accessor is a synthetic method (corresponding to the `componentN` of a Kotlin data class) |

> **How J2 relates to the multi-language frontends**: both Kotlin's `data class` and Scala's `case class` generate synthetic accessors. **If Java's `record` is built first, the "synthetic method identification" mechanism has a Java-side validation target**, and Kotlin/Scala can reuse it directly. That turns J2 from "independent Java work" into "the first landing point of multi-language capability" — **so J2 should be done first**.

### 0.5.4 Version Pinning and the Upgrade Process

**Each language frontend module pins its own version**; they are not shared:

```xml
<!-- plumb-frontend/pom.xml -->
<properties>
  <!-- Different from the main repository's pom.xml kotlin.version:
       The main repository pins 2.2.0 because the DSL script engine must be
       compatible with JDK 25 (see the main repository's comment);
       The frontend pins 2.5.0-Beta2 because on Maven Central the Analysis API
       currently has only one published version,
       and that version is 2.5.0-Beta2 (a Beta). -->
  <kotlin.analysis.api.version>2.5.0-Beta2</kotlin.analysis.api.version>
</properties>
```

**What actually landed (2026-11-02)**: the version number lives in the **main repository's** `pom.xml` under `<properties>`,
and the two frontend modules reference it through `<dependencyManagement>`. That way the version number is defined in exactly one place,
but it is still "layered by API risk" — `kotlin.version` (the DSL engine), `kotlin.analysis.api.version` (the frontend) and
`scala3.version` (the Scala frontend) are three independent things.

> **The artifact structure of `kotlin-analysis-api` (measured)**: `org.jetbrains.kotlin:kotlin-analysis-api`
> is an **aggregator POM** whose own jar is only 335 bytes. The real code lives in four artifacts:
>
> | artifact | Content | Scope |
> |---|---|---|
> | `kotlin-analysis-api-surface` | The core API interfaces (2.3MB) | compile |
> | `kotlin-analysis-api-standalone-surface` | The Standalone session interfaces (81KB) | compile |
> | `kotlin-analysis-api-standalone-implementation` | The Standalone implementation (295KB) | runtime |
> | `kotlin-analysis-api-intellij-api-surface-components` | The IntelliJ PSI components (8.7MB) | runtime |
>
> **The packages of the API are split apart**: `KaSession` is in `api`, `KaType` is in `api.types`, `KaSymbol` is in `api.symbols`,
> and `StandaloneAnalysisAPISession` is in `api.standalone`. **Searching under `api.components` for all the types will find nothing** —
> `KaFileSymbol` / `KaDeclarationSymbol` / `KaFunctionSymbol` are all in `api.symbols`, not in the `components` package.

**The upgrade process (on every minor-version crossing)**:

1. Change the version number → compile → run all unit tests.
2. Run the **dual-path cross-validation harness from §6.4** (Kotlin) — this is the primary means of detecting API semantic changes.
3. Run the **three-language equivalence fixtures from §6.1** (Java/Kotlin/Scala) — confirm the finding set has not drifted.
4. Record the changelog in that module's `CHANGELOG.md`.

> **Why the Kotlin frontend version has to be separate from the main repository's kotlin.version**:
> the main repository's `kotlin.version=2.2.0` carries an explicit comment constraint — "2.2.0 is the first release whose script engine understands JDK 25; 2.1.0 fails with `IllegalArgumentException: 25.0.1`". That is the constraint of the **DSL script engine**, and it is a completely different matter from the constraint of the **Analysis API frontend**. Mixing them would tie the two features to each other.
>
> **Corollary — when the same language appears twice in one repository, the version numbers must be decoupled, otherwise an upgrade of one feature ties down the other.**

> **Build environment note (measured)**: the local Maven defaults to the Aliyun mirror, and **it can proxy the POM of the Kotlin Analysis API
> but not the JAR** (`dependency:get` reports "8 required artifacts are missing", while fetching the same URL
> directly with `Invoke-WebRequest` returns 200). **Resolving these dependencies must be forced through Maven Central**
> (via `-s` pointing at a settings file that mirrors `central` to `https://repo1.maven.org/maven2/`).
> This is not a Plumb problem, it is an environment configuration problem — but **it shows up as the illusion of "the dependency does not exist"**, and it is easily misdiagnosed as
> "upstream never published it" or "this path does not work". **Conclusion: when a dependency will not download, first distinguish "upstream does not have it" from "the mirror does not have it".**

### 0.5.5 The Impact of "Targeting the Latest Version" on the IR Design

**Key judgement: a language version upgrade does not require an IR extension.**

| New language feature | Does the IR need to change? | Where the frontend degrades it |
|---|---|---|
| Java 21 switch pattern | ❌ No | Degraded into a BRANCH chain + a MOVE that binds the pattern |
| Java 16 record | ❌ No | The accessor is treated as a synthetic method |
| Java 17 sealed | ❌ No | Handled like an ordinary class (exhaustiveness is not an IR contract) |
| New Kotlin 2.x syntax | ❌ No | Same as above |
| Scala 3.10 Java record patterns | ❌ No | Same as above |

**This is exactly the payoff of the principle "the IR only expresses semantically equivalent things"** — languages produce new features every year, the IR does not move.

> **But there is one exception to watch**: if a new language feature introduces **new taint semantics** (not merely new syntax), then the IR might have to express it.
> For example: Kotlin's `suspend` (coroutines) — it is not syntax, it is an **execution-model** change (a suspension point switches away the execution flow).
> - A simple suspension point (`delay()`, IO suspend) → the taint flow is carried in the `Continuation` and can be treated like an ordinary call (Phase 1).
> - But if the local variables on either side of a suspension point have to be modelled (state-machine fields) → the IR needs to express "variables that live across a suspension point".
>
> **Decision**: Phase 1 does not do state-machine modelling (a suspend function is treated as an ordinary function); Phase 3 evaluates it. **If the evaluation concludes that state-machine fields are needed, then a "variable that lives across a call" concept has to be added to TAC** — this is the only opening in this design that is reserved for a future IR extension, and it is language-agnostic (any language's coroutines/async would apply).

### 0.5.6 Landed: The Java Language Level Chain and the Java 14+ Translation

**J1 (the language level chain) has been connected.**

New file `plumb-parser/src/main/java/org/plumb/parser/JavaLanguageLevel.java`:

```java
public static ParserConfiguration.LanguageLevel resolve(String level);
```

Supports `"8"`–`"25"`, `"1.8"`, `"latest"`, `"current"`, `"bleeding-edge"`.
`JavaSourceParser(List<JavaModule>)` now derives the parse level **from the first module that declares a level**;
`ScanRunner.buildParser()` was changed to `new JavaSourceParser(modules)` (no longer hardcoded).

**J2 (the Java 14+ translation) has been implemented.**

New file `plumb-tac/src/main/java/org/plumb/tac/translate/JavaModernFeatures.java`:

| Java syntax | Degraded into |
|---|---|
| `switch` expressions (`SwitchExpr`) | A result temp + a BRANCH/BINOP/MOVE branch chain; the single expression of an arrow arm is MOVEd directly into the result temp |
| `yield` | **The value is no longer discarded** — it is MOVEd into the result temp (`ExpressionTranslator` passes the target through a ThreadLocal `yieldTarget`) |
| `instanceof` patterns (Java 16+) | INSTANCEOF + a MOVE that binds the pattern |
| Switch pattern labels (Java 21) | INSTANCEOF + a MOVE that binds inside the arm |
| Record patterns (Java 21) | A record-type INSTANCEOF + an accessor CALL + a component MOVE |
| Synthetic members of `record` | The canonical constructor and the component accessors are synthesised automatically; **explicit members win**, to avoid duplication |

> **The signature-consistency trap (already stepped on)**: the synthetic members of a Java record and the explicit members must use **the same signature convention**.
> Top-level methods use `x():int`, constructors use `Point(int,int)`. When the two conventions disagree, the call-site lookup can only hit one of them,
> which shows up as "the synthetic accessor exists but the call site cannot be resolved".

**The key measured fact: `BLEEDING_EDGE == JAVA_21` (an identity comparison returns `true`).**

JavaParser 3.26.4's `LanguageLevel` enum has **no** `JAVA_22`–`JAVA_25` constants, and `BLEEDING_EDGE`
is verified by identity to be `JAVA_21`:

```
BLEEDING_EDGE == JAVA_21 : true
BLEEDING_EDGE name       : JAVA_21
BLEEDING_EDGE toString   : JAVA_21
```

**So the honest mapping for Java 22–25 is `JAVA_21`**, rather than pretending to support newer syntax:

| Input | Mapping result | Explanation |
|---|---|---|
| `"8"`–`"21"` | The corresponding named constant | A normal mapping |
| `"22"`–`"25"` | `JAVA_21` | **JavaParser 3.26.4 has no higher named level** |
| `"latest"` / `"current"` / `"bleeding-edge"` | `JAVA_21` | Same as above; `BLEEDING_EDGE` is not newer than `JAVA_21` |

**Genuinely supporting Java 22–25 syntax requires upgrading the JavaParser dependency; it is not a configuration change.**
The default has already been changed from `JAVA_17` to `JAVA_21` (the highest named level in 3.26.4).

> **This lesson is worth remembering on its own**: the name `BLEEDING_EDGE` sounds newer than `JAVA_21`,
> but in 3.26.4 they are the same constant. **"Looks newer" does not mean "is actually newer"** —
> this kind of thing can only be established by an identity comparison, never by inferring from the name.

---

## 1. Route Argument: Why Source AST → IR

### 1.1 Comparison of Three Routes

| | Route A: Bytecode<br/>(.class → Jimple → TAC) | Route B: Compiler Frontend<br/>(AST/IR → TAC) | **Route C: Source AST → IR<br/>(adopted by this design)** |
|---|---|---|---|
| Dependencies | `sootup` (already present) | `kotlin-compiler` + `scala3-compiler` (~50MB each) | Same as B, but Kotlin can use the Analysis API Standalone |
| Annotations (class/method/field) | Visible via `RuntimeVisibleAnnotations` | Complete | Complete |
| **Annotations (param level)** | **Invisible** (`CLASS` retention) | Complete | Complete |
| **Lambda bodies** | **Invisible** (`invokedynamic`) | Visible | Visible |
| **Kotlin nullability** | Requires decoding the `@Metadata` protobuf | Directly available | Directly available (`KaType` nullability) |
| **data class synthetic methods** | Requires naming heuristics to identify | Directly available (compiler-marked) | Directly available |
| **Line-number precision** | Coroutines/inline cause misalignment | Precise | Precise |
| Cross-language call graph | Naturally connected | Requires your own symbol resolution | Requires your own symbol resolution |
| Incremental compilation | Natural | Must be implemented by you | Must be implemented by you |

### 1.2 Three Unacceptable Defects of the Bytecode Path

**Defect 1: lambda bodies are completely invisible.**
Since Kotlin 1.5 `-Xlambdas=indy` is available, and since 2.0 it is the **default**. A lambda body lives in the bootstrap method of `invokedynamic`, not in an ordinary method body. The consequence is that **the taint flow inside a lambda breaks and findings are simply lost** — that is a false negative. In modern Kotlin codebases, lambdas (`let`/`also`/`map`/`filter`/callbacks) make up a very large share of the code.

**Defect 2: param-level annotations are invisible.**
A Java annotation with `@Retention(CLASS)` is a `RuntimeInvisibleAnnotations` in the `.class` file, which SootUp cannot read by default. Spring's `@RequestParam` / `@PathVariable` / `@RequestHeader` are all `PARAMETER` targets + `RUNTIME` retention (visible), but many custom framework annotations are `CLASS`. **Worse still**: in a `.class` file an annotation can only be attached to a method, so **the position information of param-level annotations is lost** (because method parameters have no independent attribute slot to carry param annotations, only the optional `MethodParameters` attribute).

**Defect 3: Kotlin nullability requires decoding a protobuf.**
Kotlin encodes type information in the protobuf inside the `@Metadata` annotation. Nullability, null safety and the data-class flag all live there. `kotlinx-metadata-jvm` can parse it, but the Kotlin metadata format **has no stability guarantee**, and we only need the single dimension of nullability — introducing a long-term fragile dependency for one dimension has a very poor cost/benefit ratio.

> On the source path, Kotlin's nullability is a **first-class citizen of the type system** (`KaType.isMarkedNullable`), and needs no extra parsing at all.

### 1.3 But the Bytecode Channel Is Not Removed

Plumb's existing `BytecodeAnalyzer` (SootUp Jimple → TAC), which analyses `.jar` files, is **unrelated to multi-language support** — it analyses "third-party libraries the user supplies", not "the user's source code". Kotlin/Scala support **does not affect** this channel.

The responsibilities of the two origins are redefined:

| origin | Responsibility | Change |
|---|---|---|
| `SOURCE` | User source code (Java / Kotlin / Scala), going through the source frontends | **Extended**: new Kotlin/Scala source |
| `LIBRARY` | Third-party `.jar`, going through Jimple | **Unchanged** |
| `SYSTEM_LIBRARY` | JDK code | **Unchanged** |

---

## 2. Benchmarking Against Coverity: The JVM Language Support Boundary and the Checker Inventory

### 2.1 Coverity's JVM Language Support Boundary (the formal answer to Q1)

The JVM languages in the **official Black Duck language list** (`blackduck.com/static-analysis-tools-sast/languages-and-frameworks.html`) number **only three**:

| JVM language | Coverity support | Support level |
|---|---|---|
| **Java** | ✅ | Native deep data-flow analysis (the most mature) |
| **Kotlin** | ✅ | Native data-flow checker (the Sigma engine) + Detekt integration |
| **Scala** | ✅ | Native data-flow checker + framework bindings (Play/http4s/Dropwizard) |
| Groovy | ❌ | Not in the supported list |
| Clojure | ❌ | Not in the supported list |
| JRuby | ❌ | Not in the supported list |
| Gosu | ❌ | Not in the supported list |
| Xtend | ❌ | Not in the supported list |

> **Be careful to distinguish two things**:
> - **Coverity supports the "compilation" of Kotlin/Scala** — `cov-build` can capture the Gradle/sbt Kotlin/Scala compilation commands.
> - **Coverity supports the "data-flow checkers" of Kotlin/Scala** — since 2026.3.0 the Kotlin dataflow checkers have moved to a standalone **Sigma** engine; in 2026.6.0 there are about 16 Kotlin checkers and several Scala checkers.
>
> In other words: **Coverity itself also forks its checker implementations per language on JVM languages, rather than building a unified IR.** This is not the direction we want to follow — our goal is **one IR + one rule set + language bindings**, which is more unified than Coverity's approach.

**The direct impact on us**: dynamic languages such as Groovy/Clojure are **not in our support plan** (the reason is in §2.1), because the industry benchmark Coverity does not do it either — not because it is hard, but because **dynamic dispatch makes data-flow analysis lose its practical value**.

### 2.2 Coverity's Kotlin Checker Inventory (Sigma 2026.3.0–2026.6.0)

| Coverity check name | Corresponding CWE | Mapped to Plumb |
|---|---|---|
| `sqli_core_kotlin` | CWE-89 | Existing `SqlInjectionChecker` |
| `os_cmd_injection_core_kotlin` | CWE-78 | Existing `CommandInjectionChecker` |
| `script_code_injection_core_kotlin` | CWE-94 | Existing `JavaCodeInjectionChecker` |
| `xpath_injection_core_kotlin` | CWE-643 | Existing `XPathInjectionChecker` |
| `path_manipulation_core_kotlin` | CWE-22 | Existing `PathTraversalChecker` |
| `url_manipulation_core_kotlin` | CWE-601 | Existing `OpenRedirectChecker` |
| `regex_injection_core_kotlin` | CWE-1333 | **Newly needed** |
| `header_injection_core_kotlin` | CWE-113 | Existing `HttpResponseSplittingChecker` |
| `sensitive_data_leak_core_kotlin` | CWE-532 | Existing `SensitiveDataLeakChecker` |
| `unencrypted_sensitive_data_core_kotlin` | CWE-319 | Existing `CleartextTxChecker` |
| `hardcoded_credentials_core_kotlin` | CWE-798 | Existing `HardCodedPasswordSinkChecker` |
| `unsafe_deserialization_core_kotlin` | CWE-502 | Existing `UnsafeDeserializationChecker` |
| `unsafe_jni_core_kotlin` | CWE-829 | Existing `UnsafeJniChecker` |
| `unrestricted_access_to_file_core_kotlin` | CWE-732 | **Newly needed** |
| `implicit_intent_core_kotlin` | CWE-926 | Android-specific, not for now |
| `missing_permission_check_android` | CWE-862 | Android-specific, not for now |

**Conclusion: in Coverity's Kotlin checkers, 13/16 already have a corresponding implementation in Plumb.** This validates that Plumb's rule-set selection is right — **what we lack is not rules but the language channel**.

### 2.3 Coverity's Scala Checker Inventory

On the Scala side Coverity clearly takes the **framework-binding** route (Play / http4s / Dropwizard) rather than general-purpose language rules:

- `missing_tls_slick_play_database_config`, `missing_tls_result_play`
- `broad_domain_attribute_cookie_play_config`, `missing_httponly_attribute_play_config`, `missing_secure_attribute_play_config`, `root_path_attribute_cookie_play_config`
- `cors_with_credentials_null_origin_result_play`
- `broad_filesystem_access_dropwizard_setup`
- `missing_tls_apache_datasource`, `missing_tls_jackson_core`, `missing_tls_mongodb`, `missing_tls_vertx` (shared by the three languages Java/Kotlin/Scala)

**An important finding**: many Scala checks are **"Modified existing Java check ... to support Kotlin, Scala"**. In other words Coverity's approach is: **the same rule, expressed with a language-agnostic mechanism, enabled by the checker's multi-language bindings.**

> **This is exactly what Plumb's existing architecture already does** (`sink-families.yaml` + `fact-kinds.yaml` + `framework` bindings). So the way Kotlin/Scala rules land = **adding language bindings to the existing YAML, rather than writing new checkers**.

### 2.4 Our Implementation Direction (Benchmarking Against Coverity)

| Priority | Item | Explanation |
|---|---|---|
| P0 | Kotlin source frontend (Analysis API Standalone) | 14 checkers become available immediately |
| P0 | Scala 3 source frontend (Compiler Plugin) | Same as above |
| P0 | The `missing_tls_*` family | Coverity has already implemented them uniformly for the three languages Java/Kotlin/Scala. We only need to add the sink family |
| P1 | `regex_injection` (CWE-1333) | The only general-purpose rule that has to be written new |
| P1 | `unrestricted_access_to_file` (CWE-732) | File permission checking |
| P2 | Play/http4s/Play-Slick framework bindings | High value in the Scala ecosystem |
| P2 | Kotlin/Android: `implicit_intent` (CWE-926) | Android-specific |

---

## 3. The Unified IR Design

### 3.1 Design Principles

1. **The IR semantics stay unchanged**. `plumb-tac/ir/` is the multi-language contract; every language frontend must adapt to it, and **the IR semantics must not be changed for a new language**.
2. **The IR only expresses "semantically equivalent" things**. Language-specific things (Kotlin's `when`, Scala's `match`) are degraded into TAC semantics **at the frontend**, with no back door in the IR.
3. **Precision is graded and explicitly declared**. Each `TacFunction` carries a "precision level", so a checker knows whether it should trust certain information.
4. **The extension surface is minimised**. Every new field/enum must be "necessary for multi-language" and must have no smaller way of expressing the same thing.
5. **Serialization stays forward compatible**. `SourceOrigin.java:24-27` already fixes the contract: adding a field is backward compatible; only renaming or deleting requires a `serialVersionUID` bump.

### 3.2 What the IR Has to Be Extended With (strictly squeezed down to 5 places)

The existing `TacFunction` already has: `classAnnotations` / `methodAnnotations` / `fieldAnnotations` / `paramAnnotations` / `enclosingClass` / `origin` / `tryRegions`.

| # | Extension | Location | Breaking |
|---|---|---|---|
| 1 | `SourceLanguage` enum | New file | No |
| 2 | `IrPrecision` enum | New file | No |
| 3 | `sourceLanguage` + `irPrecision` fields | `TacFunction` | No (delegating constructor) |
| 4 | `NULL_CHECK` opcode | `TacInstruction.Kind` | No (enum append) |
| 5 | `nullable` dimension | `TacType` | **Conditional** (the `equals` semantics change, see extension 5) |

> **Extension 5 is the only conditionally breaking one**, and it must come with dedicated regression tests (see Phase 0).

#### Extension 1: the `SourceLanguage` enum (new file)

```java
package org.plumb.tac.ir;

/**
 * The source language. It decides whether a checker sees the language's
 * native semantics or the degraded ones.
 *
 * <p>IR-serializable: part of the IrSerializer on-disk cache contract.
 */
public enum SourceLanguage {
  /** Java source. The default. */
  JAVA,
  /** Kotlin source. */
  KOTLIN,
  /** Scala 3 source. */
  SCALA,
  /** Cannot be determined (third-party code from a .jar). */
  UNKNOWN;

  /** Whether this language has a compile-time guaranteed non-null type system. */
  public boolean hasNonNullTypeSystem() {
    return this == KOTLIN;
  }
}
```

> `hasNonNullTypeSystem()` is the only entry point that needs "language-specific semantics", and its direction is strictly limited: **it may only be used to eliminate false positives, never to prove safety** (see §4.4).

#### Extension 2: the `IrPrecision` enum (new file)

```java
package org.plumb.tac.ir;

/**
 * The IR precision level. A checker can use it to decide whether to trust
 * certain information.
 */
public enum IrPrecision {
  /**
   * Source level: the complete annotations (including the param level),
   * nullability, exact line numbers and the lambda bodies are all preserved.
   * The default for the Java / Kotlin / Scala source frontends.
   */
  SOURCE_FULL,

  /**
   * Source level but degraded by the language: the Kotlin coroutines have
   * already been expanded into a state machine, and when/match have been
   * degraded into a BRANCH chain. The semantics are still complete, but the
   * structural information is lost.
   */
  SOURCE_LOWERED,

  /**
   * Bytecode level (used only for the analysis of third-party .jar libraries):
   * the annotations/line numbers may be incomplete, and the lambda bodies are
   * invisible (invokedynamic).
   */
  BYTECODE,

  /**
   * Bytecode level and passed through JVM synthesis (lambda body / accessor /
   * bridge methods).
   */
  BYTECODE_SYNTHETIC
}
```

**Why `IrPrecision` is needed**: it is the key to avoiding a flood of false positives when Kotlin support is added.

A concrete example: Kotlin's `fun foo(s: String) = s.length` is, **under bytecode**:
```
checkNotNullParameter(s, "s")   // ← the null check inserted by Kotlin
ALOAD s
INVOKEVIRTUAL String.length
```
On the bytecode path `NullDerefChecker` reports a false positive — because the Kotlin compiler has already guaranteed non-nullness.

**But under the source path this problem simply does not exist**: the frontend knows whether `s: String` is nullable or non-nullable, and writes the non-null fact directly into the IR, with no need to reverse-engineer it by "recognising the compiler-inserted Intrinsics calls".

> So the main use of `IrPrecision` shifts from "identifying bytecode degradation" to "marking source degradation" (coroutine expansion, `when` degradation).
> `BYTECODE` / `BYTECODE_SYNTHETIC` are retained, because Plumb's existing `.jar` analysis path still needs them.

#### Extension 3: `TacFunction` gains 2 fields

```java
// In TacFunction:
private final SourceLanguage sourceLanguage;   // defaults to JAVA
private final IrPrecision irPrecision;         // defaults to SOURCE_FULL
```

**Compatibility guarantees** (this matters, because `TacFunction` is a class, not a record, and has 7 constructor overloads):

- **No existing constructor signature is changed**. The new fields are assigned only in the **most complete constructor**; the remaining overloads delegate to it and pass the defaults.
- The defaults `JAVA` + `SOURCE_FULL` mean **the existing behaviour is completely unchanged**.
- `serialVersionUID` **does not need a bump** (adding a field is backward compatible, see `SourceOrigin.java:24-27`).

```java
// Added after the existing most complete constructor:
public TacFunction(
    List<TacOperand.TacVar> params,
    List<TacOperand.TacVar> locals,
    List<TacInstruction> instructions,
    List<String> classAnnotations,
    List<String> methodAnnotations,
    Map<String, List<String>> fieldAnnotations,
    Map<String, List<String>> paramAnnotations,
    List<TryRegion> tryRegions,
    SourceLanguage sourceLanguage,   // new
    IrPrecision irPrecision) {       // new
  this(params, locals, instructions, classAnnotations, methodAnnotations,
       fieldAnnotations, paramAnnotations, tryRegions);
  this.sourceLanguage = sourceLanguage == null ? SourceLanguage.JAVA : sourceLanguage;
  this.irPrecision = irPrecision == null ? IrPrecision.SOURCE_FULL : irPrecision;
}
```

> **Note**: the fields of `TacFunction` are `final`, so they cannot be "assigned afterwards" in the existing constructors. Either use the delegation pattern above, or make the fields non-final. **The delegation pattern is better** — it preserves the immutability guarantee of `final`, and none of the existing 7 constructors has to change by even one line.

#### Extension 4: `TacInstruction.Kind` gains `NULL_CHECK`

```java
public enum Kind {
  ...,
  /**
   * The null check inserted by the compiler (Kotlin Intrinsics.checkNotNull*).
   *
   * <p>Semantics: if dest is null then an NPE is thrown; otherwise dest is
   * proven to be non-null.
   *
   * <p>Use [strictly limited to]: letting the null-deref checker know that
   * "this value has been proven non-null", and thereby **eliminating false
   * positives**. It must never be used to **prove safety** -- the compiler
   * switches (-Xno-param-assertions) and ProGuard/R8 stripping can all remove
   * these checks; no check in the bytecode does not mean it is safe at runtime.
   *
   * <p>Note: {@code Intrinsics.areEqual} and
   * {@code Intrinsics.throwUninitializedPropertyAccessException}
   * are neither null checks and must not be mapped onto this opcode.
   */
  NULL_CHECK
}
```

> **On the source path, the usage frequency of `NULL_CHECK` drops sharply.**
> The Kotlin source frontend can obtain nullability straight from the type system — `KaType.isMarkedNullable`, or the nullable marker on the IR's `IrType` — and does **not** need the indirect inference of "recognising compiler-inserted Intrinsics calls".
>
> But `NULL_CHECK` is still needed, because:
> 1. **The Java interop boundary**: Java methods return platform types; on the Kotlin side they are seen as `String!`. The frontend needs to express "this value comes from Java, and the return value may be null".
> 2. **The bytecode path** (`.jar` analysis) still needs it.
> 3. **Scala**: it has no non-null type system, but it has explicit `Option`/`null` semantics, so the same mechanism can express "known to be non-null".

#### Extension 5: expressing nullability (**the key design decision**)

This is the place in this design that is **most prone to error**. The current state of `TacType` (audited at `TacType.java:37-147`):

```java
public final class TacType implements java.io.Serializable {
  private static final long serialVersionUID = 1L;
  public enum Kind { BOOLEAN, BYTE, ..., CLASS, ARRAY, NULL, VOID, UNKNOWN }
  public static final TacType STRING = new TacType(Kind.STRING, null);   // ← singleton constant
  public static final TacType INT    = new TacType(Kind.INT, null);      // ← singleton constant
  ...
  private final Kind kind;
  private final Object payload;      // CLASS→fqn, ARRAY→component
  private TacType(Kind kind, Object payload) { ... }   // ← private constructor

  @Override public boolean equals(Object o) {
    if (!(o instanceof TacType t)) return false;
    if (kind != t.kind) return false;
    return Objects.equals(payload, t.payload);        // ← compares kind + payload only
  }
  @Override public int hashCode() { return Objects.hash(kind, payload); }
}
```

**Three constraints**:
1. **The singleton-constant pattern**: `STRING`/`INT`/`CLASS` etc. are `static final` instances.
2. **`equals` compares `kind` + `payload` only** — adding a field to `equals` changes the existing semantics.
3. **`Kind` already has a `NULL` sentinel**, but it expresses "this value *is* the null literal", **not** "this reference *may be* null". These two concepts are completely different and must not be conflated.

#### The Wrong Approach A: add a `nullable` field to `TacType`

```java
// ❌ not viable
private final boolean nullable;
```

**Why it does not work**:
- Once the field is added it has to go into `equals`/`hashCode` → the semantics of `TacType.STRING.equals(TacType.NON_NULL_STRING)` change.
- The singleton constants (`public static final TacType STRING`) conflict with the concept of "non-null String" — **is the same `String` type both nullable and non-nullable?**
- `TacType` is `final` with a private constructor, so expressing "nullable/non-nullable" requires a **type-level product** (CLASS × nullable = 2N states), and every factory method has to change.

#### The Wrong Approach B: treat the `NULL` kind as "nullable"

```java
// ❌ semantically wrong
// Kind.NULL expresses "this value IS null" (a constant), not "this reference MAY be null"
// (a type attribute)
```

`PointsToSolver.java:302,321,358,447` and `NullDerefChecker.java:106` all use `Const.Kind.NULL` to inspect **an instruction's literal operand** — that is a **value-level** concept. The type-level "nullable" is **another dimension**.

#### The Recommended Approach: nullability goes through an **independent type-modifier dimension**, `TacType` is untouched

**Decision: nullability does not enter `TacType`; it becomes an attribute of `TacVar`.**

Reasons:
- **Nullability is "an attribute of the value of a certain variable", not "an attribute of a certain type"**. With the same `String` type, `x: String` (non-null) and `y: String?` (nullable) are two different **variables**.
- This matches Kotlin's own model — nullability is written on the type of a variable (`String` vs `String?`), while the **class** `String` itself does not know whether its references may be null.
- `TacOperand.TacVar` is precisely "a variable with a type" — nullability belongs here semantically.

```java
// TacOperand.java (the extension)
public record TacVar(int id, String name, TacType type) {
  // new: the nullability marker
}
```

> But `TacOperand.TacVar` is a **record** — adding a field changes the record's canonical constructor and `equals`/`hashCode`. That is a breaking change too.

**So the final decision has two layers**:

**Layer 1 (IR extension, minimal and non-breaking): `TacType` gains a `nullable` dimension, but in the "factory + unchanged singletons" style.**

```java
// TacType.java
public final class TacType implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  public enum Kind { BOOLEAN, ..., CLASS, ARRAY, NULL, VOID, UNKNOWN }

  // ... the existing singleton constants stay unchanged (all nullable = true, the Java semantics) ...

  private final Kind kind;
  private final Object payload;
  private final boolean nullable;     // ← new

  private TacType(Kind kind, Object payload) {
    this(kind, payload, true);        // ← defaults to true: a Java reference type is nullable by default
  }

  private TacType(Kind kind, Object payload, boolean nullable) { ... }

  /** Builds a non-null type. */
  public static TacType nonNull(TacType t) {
    if (t == null || !t.nullable) return t;
    return new TacType(t.kind, t.payload, false);
  }

  public boolean nullable() { return nullable; }

  @Override public boolean equals(Object o) {
    if (!(o instanceof TacType t)) return false;
    if (kind != t.kind) return false;
    if (nullable != t.nullable) return false;      // ← nullable goes into equals
    return Objects.equals(payload, t.payload);
  }
  @Override public int hashCode() { return Objects.hash(kind, payload, nullable); }
}
```

**Why this approach is safe**:
- **All existing singleton constants have `nullable == true`** → the semantics are exactly the same as before the change.
- **The existing practical uses of `equals` are almost all "same kind, same payload"** — adding `nullable` only makes "nullable X" and "non-null X" unequal, which is the **desired behaviour**.
- The existing `TacType.ofClass(fqn)` / `arrayOf(c)` return `nullable=true` → **existing code needs zero changes**.
- Serialization: `serialVersionUID` is not bumped (adding a field is backward compatible, the same contract as `SourceOrigin.java:24-27`).

> ⚠️ **One thing that must be verified**: tools that look up a class name **by type**, such as `FieldOpClassifier.classNameOfType(TacType.STRING)` (there is already a test at `FieldOpClassifierTest.java:88`), will change behaviour if they match types internally using `equals`, because `nullable` has been added. **Phase 0 must audit every call site of `TacType.equals`** and confirm that there is no scenario of "matching a non-null type against a nullable singleton constant".

**Layer 2 (checker layer): `NullDerefChecker` uses "type nullability" as its first criterion.**

```java
// NullDerefChecker.java
// 1) The type of the target variable is non-null -> it cannot be a null deref
TacType targetType = typeOf(ins.lhs1);
if (targetType != null && !targetType.nullable()) return;

// 2) A NULL_CHECK instruction -> record "this variable has been proven non-null"
if (ins.kind == TacInstruction.Kind.NULL_CHECK) {
    markNonNull(dest);
    return;
}
// 3) An ordinary dereference
if (isNull(dest)) { report(); }
```

> **Why layer 2 still needs `NULL_CHECK`**: the type system gives "the nullability at the declaration site", whereas **control-flow narrowing** (smart cast, `requireNotNull`, an explicit `if (x != null)`) is a **runtime, path-sensitive** fact. Type nullability cannot answer "at this branch point, has x already been proven non-null?". That is precisely the responsibility of `NULL_CHECK` and of the `dfa-nullness` fact domain.

**Layer 3 (cross-language): the Java side still follows the existing logic.**

Java has no non-null type system → all reference types have `nullable=true` → **the existing null-dereference detection behaviour is completely unchanged**. That is exactly what "the default value is `true`" buys.

#### Two Corrections After Landing (found during implementation)

**Correction 1: the identity of `TacVar` does not include the type.**

The discussion above assumed that the `equals` of `TacVar` would include the type. During implementation it turned out that this cannot be done:

The identity of `TacOperand.TacVar` **is** the "**storage location**". Every per-variable map of the DFA engine
(`taintedVars`, `taintPaths`, the field / array trackers) uses it as a key.
If the type participates in equality, then the "refined variable" becomes a **different key**:

```
at declaration:  TacVar("x", UNKNOWN)        -> the key of taintedVars
after refinement: TacVar("x", java.lang.String) -> a different key
-> the taint recorded earlier is not found -> the finding is silently lost
```

**There is no exception, just one fewer finding** — that is the hardest kind of bug to detect.

So the final rule is: **identity = `name + isTemp`; the type is refinable metadata and does not participate**.
Nullability is therefore automatically safe: a nullable `String` and a non-null `String` are the same storage location,
and the null-deref checker reads the type directly from the variable rather than from the key.

**Correction 2: `toString()` must not be used as an identity.**

`TacType.toString()` now appends `" (non-null)"` (so that a dump can explain "why this finding was suppressed").
But tools like `FieldOpClassifier` use the **class name** as a map key — a decorated key does not match an undecorated one,
and **the failure is silent**.

So it is split into two methods:

| Method | Purpose | Usable as a key |
|---|---|---|
| `displayName()` | The identity form (plain class name) | ✅ Yes |
| `toString()` | The display form (decorated with `(non-null)`) | ❌ No |

> **Corollary — when you add a "display decoration" to an existing value object, you must also provide an "identity form".**
> Otherwise the decoration leaks into everything that uses it as a key through `toString()`, and once it leaks in it leaks silently.

### 3.3 The Frontend Contract

All frontends implement the same interface. This is the **only** multi-language extension point.

```java
package org.plumb.tac.frontend;

/**
 * The language frontend contract: source code -> TAC.
 * An implementation must be stateless and safe to call in parallel.
 */
public interface TacFrontend {

    /** The language this frontend handles. */
    SourceLanguage language();

    /** The source file extensions this frontend accepts, such as {"kt", "kts"}. */
    Set<String> fileExtensions();

    /**
     * Translates one source file.
     * @param source the content of the source file
     * @param path   the path of the source file (for the error messages and the line number mapping)
     * @param ctx    the compile context (the classpath, the dependent modules, the parse cache)
     * @return the list of functions this file produces (it may be empty, as for a declaration-only file)
     */
    List<TacFunction> translate(SourceFile source, CompileContext ctx);

    /** Whether this frontend needs the external compilation output (the bytecode/classpath). */
    default boolean requiresClasspath() { return false; }
}
```

**Why `List<TacFunction>` and not `TacProgram`**: one source file may produce several functions, and they have to be mergeable into the global program. The existing `MergedProgram` already handles that.

**The new compile-context contract** (the core difficulty of the source path):

```java
package org.plumb.tac.frontend;

/**
 * The compile context: the shared state of one analysis session.
 *
 * <p>[Important] It is session-scoped, not file-scoped. The symbol resolution,
 * the type inference and the module dependency graph all have to be shared
 * across the whole session, otherwise the cross-file analysis degrades into
 * O(n^2).
 */
public interface CompileContext extends AutoCloseable {

    /** The mapping from a source root directory to a module. */
    List<Module> modules();

    /** The cache of the symbols already resolved. It must be reused across files. */
    SymbolResolver symbols();

    /** The cache of the parse results. */
    <T> T computeIfAbsent(String key, Function<String, T> factory);

    @Override
    void close();
}
```

> **Corollary — the compile context is session-scoped, not file-scoped.**
> Kotlin's Analysis API Standalone session and Scala's compilation unit are both shared across a whole build.
> If a context were built per file, cross-file symbol resolution would degrade into N full resolutions.

### 3.4 The Kotlin Source Frontend (the formal answer to Q2)

Kotlin has **two** viable source → IR paths. Their precision, dependencies and cost differ.

#### Path K1: Analysis API Standalone (**recommended as the main path**)

**Verified facts** (from the Kotlin Analysis API's official `Writerside/topics/Index.md` and `StandaloneAnalysisAPISessionBuilder.kt`):

- The Analysis API is built on top of **Kotlin PSI** and provides semantics through the four concepts `KaSession` / `KaSymbol` / `KaType` / `KaScope`.
- The **Standalone mode** is the officially provided headless way of running it:
  - Entry point: `buildStandaloneAnalysisAPISession(projectDisposable) { buildKtModuleProvider { ... } }`
  - Internally it uses `KotlinCoreProjectEnvironment` (from `kotlin.cli.jvm.compiler`) to set up a headless IntelliJ platform core environment
  - **No IDE required**, `java.awt.headless=true`
  - Supports **multiple modules**: `buildKtModuleProvider { addSourceModule(...); addLibraryModule(...) }`
  - Supports **library modules** (`.jar` as a binary dependency)
- **The officially declared limitations** (important, they have to be written into the design):
  > "Currently, the only officially supported use-case is IntelliJ IDEA plugin development. While the Analysis API provides the Standalone mode for command-line tool developers, **it is currently in development and subject to incompatible changes.**"
  >
  > In other words: **the Standalone mode is an officially experimental API and may contain incompatible changes.**

**Advantages**:
- **Native support for mixed Kotlin/Java projects** — the `KaSession` of the Analysis API can resolve Kotlin and Java declarations at the same time (the Java side goes through light classes).
- **Type information is a first-class citizen**: `KaType.isMarkedNullable` gives nullability directly, with no indirect inference whatsoever.
- **Lambda bodies are completely visible** (at the PSI level).
- **No real compilation is needed** — only analysis (the typer level), which is faster than a full compilation.
- **Controllable dependencies**: `analysis-api-standalone` + `analysis-api-fir` + `low-level-api-fir` + `symbol-light-classes`.

**Disadvantages**:
- The API is marked `@KaExperimentalApi`, so **a version upgrade may break things**.
- You have to understand the IntelliJ platform's service-registration model (`registerApplicationService` / `registerProjectService`).
- Process-level side effects: `setupIdeaStandaloneExecution()` + the headless properties + the global thread pool are **JVM-process-level state**, and when several Standalone sessions run in parallel inside the same process these global quantities are shared.

#### Path K2: the `IrGenerationExtension` of the Compiler Plugin API (**recommended as a precision enhancement / alternative**)

**Verified facts**:
- The Kotlin Backend IR is a **regular tree-shaped IR**: `IrModuleFragment` / `IrSimpleFunction` / `IrBlockBody` / `IrExpressionBody` / `IrCall` / `IrWhen` / `IrBranch` / `IrTry` / `IrForLoop` / `IrDoWhileLoop` / `IrConst` / `IrGetValue` / `IrSetValue` / `IrGetField` / `IrSetField` / `IrGetElement` / `IrSetElement` / `IrConstructorCall` / `IrReturn` / `IrThrow` / `IrTypeOperatorCall` / `IrIntrinsic` / `IrFunctionExpression` (lambda bodies).
- The IR tree nodes are **canonically generated** by `IrTree.kt`, and the fields/accessors are machine-readable — **which makes it very suitable for writing an automatic translator**.
- `IrGenerationExtension` is a stable plugin extension point since Kotlin 2.0+.

**Advantages**:
- **The IR tree is the compiler's final internal representation**, and it corresponds almost one-to-one with TAC (see the mapping table in §3.5).
- **The state machine after coroutine expansion is visible** — a capability unique to the source path (at the PSI level K1 sees only the `suspend fun` declaration).
- The null checks the compiler inserts are `IrIntrinsic`/`IrCall` to `Intrinsics.checkNotNull*` in the IR, **but the type system itself also carries nullability**, and the two verify each other.

**Disadvantages**:
- It requires **really running a compilation** (Kotlin compilation is time-consuming) — analysis alone is not enough.
- The compiler internal API is **incompatible across versions** (every Kotlin upgrade may break it).
- The execution point of `IrGenerationExtension` is after lowering, so the IR it gets is already in **degraded/lowered form**.

#### Decision: K1 as the main path, K2 as the **cross-validation** channel

**The recommended combination**:
- **Main path = K1 (Analysis API Standalone)**: covers 90% of the practical needs (symbol resolution, types, nullability, annotations, lambdas) at the lowest cost and with the lightest dependencies.
- **K2 (the IR plugin) is not "phase two", it is the "validation channel"** — see the dual-path cross-validation requirement in §6.4. Both paths use **the same TAC IR**, so the finding sets should agree; wherever they disagree, it is either a precision loss in K1 or a lowering difference in K2, **and that is in itself the most valuable bug detector**.

> **"Only K2" is not recommended**: K2 requires a full compilation, and Kotlin compilation itself is slow (minutes), which is a terrible experience for a "static analysis tool". K1 only does analysis, which is much faster.
>
> **"Only K1" is not recommended either**: K1's API is `@KaExperimentalApi` (the official docs say "subject to incompatible changes"). K2 has to exist as a **contract validation**, to ensure that K1's output has no systematic bias.

### 3.5 The Scala 3 Source Frontend

**Verified facts** (from Scala's official compiler-plugins documentation):

- Scala 3's `PluginPhase` is a **mini-phase**, inheriting from `dotty.tools.dotc.transform.MegaPhase.MiniPhase`.
- It overrides hooks such as `transformApply` / `transformDefDef` / `transformValDef` / `transformMatch` / `transformSelect` / `transformClosure` / `transformBlock`, which the compiler calls in a **fused traversal**.
- The insertion point is specified through `runsAfter` / `runsBefore`:
  ```java
  override val runsAfter = Set("typer")     // after typer: symbols and types are resolved
  override val runsBefore = Set("pickler")  // before pickler: rewrites make it into TASTy
  ```
- The plugin entry point: `extends StandardPlugin`, implementing `initialize(options: List[String])(using Context): List[PluginPhase]`, with a `plugin.properties` in the jar root (`pluginClass=xxx.Yyy`).
- **TASTy serialization happens in the `pickler` phase**, so `runsAfter=typer, runsBefore=pickler` gives access to the **complete tree with types**, and rewrites make it into TASTy.
- **An important limitation**: `PluginPhase` can only see **the files of this compilation**. Under Zinc incremental compilation, cross-file invariants cannot see the whole picture.

**Our approach**:
- **Use only `PluginPhase`, not `ResearchPlugin`.** A research plugin can reorder the whole compilation pipeline, but **it is only enabled with `-experimental` or on nightly/snapshot builds** — you cannot build a production tool on it.
- **The position is `runsAfter = Set("typer")`, `runsBefore = Set("pickler")`**: after the typer you get symbols and types; before the pickler you are guaranteed to see "high-level semantics" (`match` is still `Match`, not yet expanded into a Maranget decision tree).

**The Scala 3 `tpd.Tree` → TAC mapping table:**

| Scala 3 `tpd.Tree` | TAC | Remarks |
|---|---|---|
| `DefDef` | `TacFunction` | `paramss` → params |
| `ValDef` | `MOVE` (local) / `FIELDSTORE` (member) | `Flags.Mutable` distinguishes var/val |
| `Block(stats, expr)` | Instruction sequence | |
| `Apply(fun, args)` | `CALL` | `fun.symbol` → calleeSig |
| `TypeApply(fun, targs)` | `CALL` | Generic call, type parameters discarded |
| `Select(qual, name)` | `FIELDLOAD` | |
| `Assign(lhs, rhs)` | `MOVE` | |
| `New(tpt, args)` | `NEW` | |
| `If(cond, thenp, elsep)` | `BRANCH` | |
| `WhileDo(cond, body)` | `BRANCH` + `JUMP` | |
| `Match(selector, cases)` | `BRANCH` chain | **Degradation point**: match → if/else chain |
| `CaseDef` | Branch target + `MOVE` (pattern binding) | `Bind`/`Typed` → parameters |
| `Closure(meth, params, body)` | Standalone `TacFunction` + `CALL` | **lambda body visible** |
| `Try(body, catches, finalizer)` | `THROW` + `CATCH_HANDLER` + `FINALLY_ENTER` | |
| `Literal(Constant)` | `Const` | |
| `TypeTree` / `Typed` | Discarded / `MOVE` | |
| `Select` to `apply`/`unapply` | `CALL` | Companion-object synthetic method |
| `Inlined` | The expanded tree | **Note**: `shallowFold` applies to the expanded tree |
| `Template` | Skipped | Class members live in `DefDef`/`ValDef` |
| `Import` | Skipped | Imports do not affect semantics |

**Scala 2 is explicitly not done**:
- The Scala 2 compiler internal API is completely different from Scala 3's (`Symbol` vs `Denotation`, `Tree` vs `TypedTree`).
- Scala 2 has entered its maintenance period (the official roadmap has no 2.14).
- **Cost**: a second translator + a second version pin + a second test fixture set, so the ROI is extremely poor.
- **Decision**: **Scala 2 is not supported.** If a user has a Scala 2 codebase, the official path is to migrate to Scala 3.

### 3.6 Other JVM Languages

**The key insight here is: Coverity did not do them either, so we do not need to either.**

| Language | Coverity | Our decision | Reason |
|---|---|---|---|
| Groovy | ❌ Not supported | **Not done** | Dynamic dispatch (`CallSite`), data-flow analysis has no practical accuracy |
| Clojure | ❌ Not supported | **Not done** | `IFn.invoke` dynamic dispatch, same as above |
| JRuby | ❌ Not supported | **Not done** | Dynamic dispatch + Ruby semantics |
| Gosu | ❌ Not supported | **Not done** | Statically typed but a tiny ecosystem, no checker demand |
| Xtend | ❌ Not supported | **Not done** | Compiles to Java source, a detour |

**If Groovy/Clojure are ever supported, the only meaningful form would be "configuration checking" rather than "data-flow analysis"** (for example, plaintext passwords in Gradle build scripts). That belongs to the AST-rule category and is out of scope for this round.

---

## 4. Data-Flow Analysis: What Has to Change

**The answer is: almost nothing, but three places must change.**

### 4.1 The Rule Layer: add language bindings, zero new syntax

The existing `checkers.yaml` / `sink-families.yaml` / `fact-kinds.yaml` structure stays unchanged. Add one language dimension:

```yaml
# the sink-families.yaml extension
sink_families:
  sql.sinks:
    description: "SQL query construction/execution"
    patterns:
      - pattern: "^java\.sql\.Statement\.execute(/\d+)?"
      - pattern: "^jakarta\.persistence\.EntityManager\.createQuery"
    languages: [java, kotlin, scala]      # ← the new field, optional

  regex.sinks:                              # ← the new family (CWE-1333)
    description: "user-controlled regex pattern"
    patterns:
      - pattern: "^java\.util\.regex\.Pattern\.compile"
      - pattern: "^kotlin\.text\.Regex\.<init>"           # Kotlin specific
      - pattern: "^scala\.util\.matching\.Regex\.<init>"  # Scala specific
    languages: [java, kotlin, scala]
```

**Design points**:
- `languages` is an **optional** field. Not writing it = enabled for all languages (keeps backward compatibility).
- **Cross-language sinks are naturally shared**: `java.sql.Statement#execute*` is the same symbol in Kotlin/Scala too. **This holds on the source path as well** — Kotlin's `stmt.executeQuery(...)` resolves to `java.sql.Statement.executeQuery`. So "zero-cost sharing of cross-language sinks" is not a patent of the bytecode path, **the source path holds just as well**.
- **Kotlin-specific sinks** need new patterns: `kotlin.text.Regex#<init>`, `kotlin.io.readLine`, `kotlin.system.getenv`.
- **Scala-specific sinks**: `scala.sys.process.ProcessBuilder#<init>`, `scala.util.matching.Regex#<init>`.

**Note the two existing semantics of `SinkPattern` (`SinkFamily.java:71-104`); new patterns must obey them**:
- **The `argIndex` semantics**: `-1` = use the family's default, `-2` (`RECEIVER_ARG_INDEX`) = the tainted value is on the receiver (as in `ObjectInputStream#readObject`), `>= 0` = the explicit index of a user argument. If a Kotlin/Scala sink has the tainted value at a different position than the Java version, **`argIndex` must be specified explicitly**.
- **The relaxed form is derived automatically**: `SinkPattern.relaxedForm()` makes the **package prefix** of `^fully\.qualified\.Class\.method` optional, but **the class name and the method name must be kept**. This means the relaxed form of `^kotlin\.text\.Regex\.<init>` is `^(?:[\w.]+\.)?Regex\.<init>` — it **does not** falsely match `java.util.regex.Pattern.compile` (the method name differs), but it **does** match `Regex.<init>` under any package. That is acceptable (`Regex.<init>` really is a sink), but it has to be known.

> These two semantics are especially important for Kotlin/Scala: class names such as Kotlin's `Regex` or `ProcessBuilder` may collide with same-named classes from third-party libraries in relaxed mode. **After adding a new pattern you must run a "will this pattern falsely match" validation.**

### 4.2 `TaintRule`: add one `languages` field

```java
public record TaintRule(
    String ruleId,
    String sourceKindName,
    String sinkFamilyKey,
    String cwe,
    String severityLabel,
    TaintDomain domain,
    boolean enabled,
    Set<String> languages) {          // ← new, empty = all languages
  public boolean appliesTo(SourceLanguage lang) {
    return languages == null || languages.isEmpty() || languages.contains(lang.name());
  }
}
```

**Compatibility**: `TaintRule` already has 6-arg and 7-arg constructors. Adding an 8th field requires adding a compatibility constructor (there are already several overloads of `TaintRule.of(...)` in the existing code). This is the **only** breaking change, and it is manageable.

### 4.3 Checker: add a language gate

```java
public interface DfaChecker {
    /**
     * Whether this checker applies to the given function.
     * The default: it applies to every language.
     */
    default boolean supports(TacFunction fn) { return true; }
}
```

Then add a gate in the traversal of `DfaEngine`. **Note: this must be a "skip", not an "error"** — check `fn.sourceLanguage()` rather than "this checker does not know this language".

### 4.4 Kotlin Null Safety: the three-layer mechanism on the source path

This is the **core risk point** of Kotlin support and has to be solved systematically.

**The good news**: on the source path, null safety is **no longer the indirect inference of "recognising compiler-inserted Intrinsics calls"**, but instead **a direct read of the type system's nullability**.

**How Kotlin's null safety is expressed on the source side**:

| Concept | Kotlin source | Analysis API expression | IR-layer expression |
|---|---|---|---|
| Non-null type | `s: String` | `KaType.isMarkedNullable == false` | `IrType` non-nullable |
| Nullable type | `s: String?` | `KaType.isMarkedNullable == true` | `IrType` nullable |
| Platform type (Java interop) | `s: String!` | `KaFlexibleType` (the flexible variant of `KaType`) | — |
| Smart cast | `if (s != null) { s.length }` | Control-flow-sensitive smart cast | The IR after the smart cast |
| Explicit null check | `requireNotNull(s)` | Type narrowing | — |

**Layer 1: the frontend directly produces the non-null fact.**

The Kotlin frontend writes nullability straight into the TAC type information when it handles each variable declaration.

> ⚠️ **The implementation details of this layer are not obvious**, and they are fully derived in extension 5 of §3.2. The core conclusions:
> - `TacType` uses **singleton constants + a private constructor + `equals` comparing `kind`+`payload` only**, so you cannot simply add a `nullable` field and be done.
> - The correct approach is to add `private final boolean nullable` + a `nonNull(TacType)` factory + `nullable` in `equals`/`hashCode`, with **all existing singleton constants keeping `nullable` at `true`**.
> - **The default must be `true`** (Java semantics: a reference type may be null) — otherwise the Java-side null-dereference detection would immediately miss a whole swath of reports.
> - `TacType.Kind.NULL` is the sentinel for "the value *is* null" and **must not** be used for "nullable".

How the Kotlin frontend produces a non-null type:

```java
// The Kotlin frontend: it resolves s: String (non-null)
TacType t = TacType.ofClass("java.lang.String");
TacType nonNull = TacType.nonNull(t);   // nullable = false
```

**Layer 2: `NullDerefChecker` uses type nullability + `NULL_CHECK` as dual criteria.**

```java
// 1) The type system decides directly: the type of the target variable is non-null -> it cannot be a null deref
if (targetType != null && !targetType.nullable()) return;

// 2) A NULL_CHECK instruction: record "this variable has been proven non-null"
//    (the control-flow narrowing that the type system cannot answer)
if (ins.kind == TacInstruction.Kind.NULL_CHECK) {
    markNonNull(dest);
    return;
}
// 3) An ordinary dereference
if (isNull(dest)) { report(); }
```

> **Why step 2 cannot be skipped**: type nullability answers "is this reference **at the declaration site** allowed to be null". The narrowing done by **smart cast / `requireNotNull` / `if (x != null)` is runtime and path-dependent** — the type system cannot produce that information. That is precisely why `NULL_CHECK` and the `dfa-nullness` fact domain exist.
>
> In other words: **type nullability suppresses the false positives that are "statically impossible to be null"; `NULL_CHECK` suppresses the false positives that are "already proven non-null by control flow". The two cover different sources of false positives, and neither can be dropped.**

**Layer 3: the taint checker's "known non-null" set.**

If `s` has already been proven non-null, a subsequent `s.length` should not report an NPE. But **the taint still has to propagate** (`s` may come from user input).

**So "non-null" and "no taint" have to be separated**: these are two orthogonal fact domains. The current `TaintDomain` only handles taint, so an independent fact kind has to be added to the `FactBag`:

```yaml
# fact-kinds.yaml
fact_kinds:
  dfa-nullness:
    description: "value is known non-null (Kotlin non-null type / explicit null check)"
    traits: []
```

> ⚠️ **An important constraint (`FactKind.java:57-78`)**: `FactKind.Trait` is a **declarative intent, not a behaviour the engine executes**. The comment says it explicitly — "No production code reads a trait; the propagation is implemented by the checker that owns the kind."
>
> This means: once the `dfa-nullness` fact kind is added, **it does not propagate automatically**. The propagation of the non-null fact has to be implemented by `NullDerefChecker` itself (it is in any case the only checker that cares about non-nullness). So the implementation of layer 3 lives inside the checker, not in the engine.
>
> That actually makes it simpler — the engine's propagation framework does not have to be touched.
>
> This also answers the **"Cwe476 null deref"** scenario the user mentioned: in Java, the getters Kotlin generates carry null checks, and in a mixed codebase (Java + Kotlin), not distinguishing them would produce a very large number of false positives.

**The directional iron rule — null-safety information may only be used to eliminate false positives, never to prove safety:**

Kotlin's non-null guarantee is not implemented by a type system on the JVM; it is implemented by the compiler inserting `Intrinsics` calls at the "boundaries":

| Intrinsics method | Insertion point | Semantics |
|---|---|---|
| `checkNotNullParameter(value, name)` | The entry of a non-null **parameter** | Non-null guarantee for the parameter |
| `checkNotNullExpressionValue(value, expr)` | The **return value** of a platform type (e.g. a Java method's return) | Non-null guarantee for the expression |
| `checkFieldIsNotNull(value, name)` | A non-null **field** | Non-null guarantee for the field |
| `checkReturnedValueIsNotNull(value, expr)` | A non-null **return value** | Non-null guarantee for the return value |
| `throwUninitializedPropertyAccessException(name)` | Access to an uninitialised `lateinit` property | **Not a null check**, it is a lateinit check |
| `areEqual(a, b)` | The data class `equals()` | **Not a null check**, it is a null-safe comparison |

**Four key details (they decide whether the implementation is correct)**:

1. **No checks are inserted for local variables.** `val x: String = "hello"` compiles to **no** `Intrinsics` call — the compiler can mathematically prove it cannot be null. Checks only appear at the **boundaries** (parameters, return values, fields, platform-type expressions).
2. **`Intrinsics.areEqual(a, b)` must never be classified as a null check.** It is the comparison helper of the data class `equals()`. Misclassifying it as `NULL_CHECK` turns a "two values are equal" comparison into a "non-nullness proof" and pollutes the non-null fact set.
3. **No checks are inserted for nullable parameters.** `fun f(s: String?)` compiles to **no** `checkNotNullParameter`. So the presence of `checkNotNullParameter` in the bytecode ⟺ that parameter is a non-null type in the source.
4. **The checks can be switched off.** `-Xno-call-assertions` / `-Xno-param-assertions` / `-Xno-receiver-assertions` remove the corresponding checks, and ProGuard/R8's `-assumenosideeffects` strips them too. **So on the bytecode path you cannot assume "there is a check ⟹ it is safe at runtime".**

> **Point 4 decides the entire direction of the mechanism**:
> `NULL_CHECK` may only be used to **eliminate false positives**, and **never to prove safety** (true positives).
> Getting the direction wrong hides real vulnerabilities — which is far worse than a false positive.
>
> **On the source path this constraint is even clearer**: source nullability is **language semantics** (guaranteed by the Kotlin type system), not "a check that might be stripped at runtime". So the nullability information on the source path is **more reliable** — but even so, "non-null type ⟹ this particular value is non-null" still depends on the correctness of smart casts, so **the direction of inference is still one-way** (non-null ⟹ do not report an NPE, never the other way round).

### 4.5 Kotlin Compiler-Synthesised Code: the second source of false positives

The compiler generates **6 methods** for a data class:

| Generated method | Purpose | Interference with static analysis |
|---|---|---|
| The constructor | Contains `checkNotNullParameter` | A null check (already handled by 4.4) |
| `equals()` | Structural equality, using `Intrinsics.areEqual` | **Not a null check** (see detail 2 of 4.4) |
| `hashCode()` | Structural hash | A source of dead-code false positives |
| `toString()` | Structural string | A source of dead-code false positives |
| `componentN()` | Destructuring | **A source of dead-code false positives** (the call site is invisible in the source) |
| `copy()` / `copy$default()` | Copying with default values (bitmask mechanism) | A source of dead-code false positives |

**The problem**: `component1()`/`component2()`/`copy()` have **no explicit call site in the source** (the destructuring `val (a, b) = user` compiles into `component1()`/`component2()` calls). `UnusedVariableChecker` / `UnusedParameterChecker` report them as "unused private methods" — **this is exactly the classic SpotBugs/FindBugs false positive on Kotlin**.

**The solution on the source path (far cleaner than on the bytecode path)**:

**The Analysis API path**: `KaSymbol` has an `isSynthetic` flag (symbols synthesised by the compiler are marked). In addition, `KaDeclarationSymbol` has an explicit synthesis marker for a data class's `componentN`/`copy`.

**The IR path**: `IrSimpleFunction` distinguishes synthetic methods through `isSynthetic`/`origin`.

> **Compared with the bytecode path**: on bytecode you have to rely on naming heuristics to identify them (`component<N>` / `$default` / `access$` / `ACC_SYNTHETIC`), which **both misses things and misjudges things**. The source path reads the compiler's markers directly — **zero heuristics**.
>
> This is one of the core payoffs of the source main line.

**The solution**: mark the compiler-synthesised methods as `IrPrecision.SOURCE_LOWERED` (or add a more precise synthesis marker), so that the "unused"-type checkers skip them.

> **This is the second reason for `IrPrecision` to exist** — the first is null safety (4.4), the second is synthetic-code identification (this section).

### 4.6 Coroutines: a capability unique to the source path

Kotlin coroutines (`suspend fun`) are a **source-path-exclusive gain**.

**The shape at the IR layer**: the compiler compiles `suspend fun` into a **state machine** (a `SuspendLambda` + a state-machine class containing a `Continuation` implementation). The IR layer can see `IrSuspendFunction` and the state machine's `when` dispatch.

**The analytical implications**:
- **The taint flow across a suspension point**: in the source, `suspend fun a() { val x = readInput(); b(x) }`, where `b` is another suspend function. In the IR, `a` compiles into a state machine, and `x` lives in a state-machine field.
- **Taint propagation across a suspend boundary**: if `b` is an external function (not in this file), the taint has to be re-analysed at the entry of `b`.

**The decision**:
- **Phase 1 (the Kotlin frontend)**: treat `suspend fun` as an **ordinary function** (`IrSuspendFunction` → `TacFunction`, with an implicit `Continuation` parameter added).
  - **False-negative risk**: the taint of a state-machine field is lost as it crosses the `Continuation`.
  - **But this is still better than the bytecode path** — on the bytecode path the semantics of `suspend fun` are not visible at all.
- **Phase 2 (enhancing the Kotlin frontend)**: explicitly model state-machine fields (`IrField` + the state-machine dispatch).
- **The acceptance requirement**: on the Kotlin coroutine fixture, **the number of findings on the source path must be ≥ the number on the bytecode path**. This is one of the core cases of the §6.4 cross-validation.

### 4.7 The Cross-Language Call Graph

**The problem**: Java calling Kotlin, Kotlin calling Scala, all require **cross-language symbol resolution** on the source path.

**The Analysis API path (Kotlin)**:
- `KaSession` is cross-language by nature — a Kotlin session can see Java declarations (through light classes).
- For a Java → Kotlin call: when the Kotlin frontend processes a Java call site, it has to be able to resolve the Kotlin-side definition. This requires a **shared symbol table**.

**The Scala path**:
- The Scala 3 compiler **can only see the Java binaries on the classpath** (`.class`), not Java source.
- For a cross Scala→Java call: read the `.class` files from the classpath directly and resolve symbols with SootUp (a capability Plumb already has).
- For a cross Java→Scala call: the Java side has to see the Scala definitions — either by reading the Scala compilation output's `.class` + TASTy, or by going through a TASTy inspector.

**The design decision for the cross-language call graph**:

```
        ┌──────────────────────────────────────────┐
        │      the unified TacProgram / MergedProgram │
        └──────────────────────────────────────────┘
             ▲          ▲           ▲          ▲
             │          │           │          │
        ┌────┴───┐ ┌────┴────┐ ┌────┴────┐ ┌───┴──────┐
        │ Java   │ │ Kotlin  │ │ Scala 3 │ │ .jar     │
        │ source │ │ source  │ │ source  │ │ (third   │
        │ front  │ │ front   │ │ front   │ │ Jimple   │
        └────────┘ └─────────┘ └─────────┘ └──────────┘
             │          │           │          │
             └──────────┴─────┬─────┘          │
                              │                │
            cross-language symbol resolution:│
            a Kotlin session can see Java     │
            Scala reads the classpath .class  │
```

**The key design**: **use one shared symbol-resolution layer**, rather than letting each frontend resolve on its own.

```java
package org.plumb.tac.frontend;

/**
 * The cross-language symbol resolver: it abstracts the "call site -> the callee"
 * step away.
 *
 * <p>Each language frontend translates its call-site expression into a unified
 * CalleeRef, and this interface resolves it into the final calleeSig.
 */
public interface SymbolResolver {
  /**
   * Resolves a call site.
   * @param callee  the call description given by the frontend (owner FQN + method name + arity)
   * @param context the context of the function the call site is in (used to disambiguate the enclosing class)
   * @return the resolution result; null when it is ambiguous (conservative: make no cross-language assumption)
   */
  Optional<CalleeRef> resolve(CalleeRef callee, CallContext context);
}
```

**The concrete strategy for cross-language resolution**:

| Call direction | Resolution method |
|---|---|
| Kotlin → Java | Native Analysis API support (Java light classes) |
| Java → Kotlin | The Java frontend cannot resolve the source definition → go through the symbol index produced by the Kotlin frontend (the Kotlin frontend runs first and registers its symbols into the shared table) |
| Scala → Java | Scala reads the classpath `.class` → SootUp resolution |
| Java/Kotlin → Scala | The symbol index produced by the Scala frontend |
| Anything → `.jar` | SootUp resolution (Plumb's existing capability) |

> **The key engineering point**: the **order in which the frontends execute**. The Java/Kotlin frontend has to run before the Scala frontend (or the other way round), because cross-language resolution needs the other side to have registered its symbols first.
>
> **The solution**: separate "symbol registration" from "frontend translation" — the frontends run twice:
> 1. **Pass 1 (symbol collection)**: each frontend only resolves and registers top-level symbols (class/method signatures), and produces no TAC.
> 2. **Pass 2 (translation)**: once the complete symbol table is ready, each frontend produces TAC.
>
> This two-stage design makes the dependencies between frontends **explicit and parallelisable** (the Pass 1s can run in parallel).

---

## 5. The Phased Roadmap

> **Progress snapshot (2026-11-02)**: Phase 0 is **complete**, and the frontends of Phase 1 / Phase 2 have been created and begun.
> The "complete" marks in the table below mean that the item has landed and is locked in by regression tests.

### Phase 0 — The Foundation (produces no new vulnerabilities, but decides everything afterwards) — **complete**

| Task | Deliverable | Acceptance criterion | Status |
|---|---|---|---|
| `SourceLanguage` enum (new file) | IR extension | The serialization round-trip test passes | ✅ Complete |
| `IrPrecision` enum (new file) | IR extension | The serialization round-trip test passes | ✅ Complete |
| `TacFunction` gains 2 fields | IR extension | All existing DFA tests stay green (**backward compatible**: the defaults are `JAVA` + `SOURCE_FULL`). Current baseline: `plumb-tac` + `plumb-dfa` together **Tests run: 418, Failures: 0, Errors: 0, Skipped: 14** | ✅ Complete |
| `TacType` gains the `nullable` marker | IR extension | **The default is `true`** (Java semantics), and the existing behaviour changes not at all | ✅ Complete |
| **Audit every call site of `TacType.equals`** | Compatibility audit | Once `nullable` is in `equals`, "nullable X" and "non-null X" are no longer equal. It has to be confirmed that there is no scenario of "matching a non-null type against a nullable singleton constant" (the focus: tools that look up a class name by type, such as `FieldOpClassifier.classNameOfType`) | ✅ Complete — `FieldOpClassifier` now uses `displayName()` |
| **A regression test that locks down the `nullable` default** | Anti-regression | `assertThat(TacType.STRING.nullable()).isTrue()` + `assertThat(TacType.ofClass("x.Y").nullable()).isTrue()` + the Java null-dereference detection behaviour is unchanged | ✅ Complete — `MultiLanguageIrContractTest` (28 tests) |
| `TaintRule` gains a `languages` field + a compatibility constructor | Rule-layer extension | Loading the existing rules needs zero changes | ✅ Complete |
| The `DfaChecker.supports(TacFunction)` gate | Engine extension | The existing checkers default to `true`, and the behaviour is unchanged | ✅ Complete — the analysis loop and the summary loop are **both** gated |
| `fact-kinds.yaml` gains `dfa-nullness` | Fact-domain extension | The new fact can be stored and retrieved by the `FactBag` | ✅ Complete |
| The `TacFrontend` / `CompileContext` interfaces | Frontend contract | The interfaces are finalised and the Java frontend implements them (**proving the contract is implementable**) | ✅ Complete — `JavaTacFrontend` is the reference implementation |
| **J1: connect the Java language level chain** | Java frontend fix | A `JavaModule.languageLevel` (String) → `ParserConfiguration.LanguageLevel` parser; the two `JAVA_17` hardcodings in `JavaSourceParser` become "configuration first, default `JAVA_21`". Acceptance: a Java 21 syntax fixture can be parsed | ✅ Complete — `JavaLanguageLevelTest` (9 tests) |
| **J2: add the Java 14+ syntax translation** | Java frontend enhancement | `SwitchExpr` / `RecordDeclaration` / `PatternExpr` / sealed exhaustiveness. Acceptance: the TAC instruction sequence of a Java 21 fixture is correct, and the record accessor is recognised as a synthetic method | ✅ Complete — `JavaModernFeaturesTest` (14 tests) |

**Additional Phase 0 landing (beyond the original plan)**:

| Task | Explanation |
|---|---|
| The `TacOperand.TacVar` identity rule | The identity is determined only by `name + isTemp`; **the type does not participate**. The type is refinable metadata; including it in equality would make the "refined variable" a different map key, and the taint would be silently dropped |
| `TacType.displayName()` | `toString()` is the **display** form (it appends `" (non-null)"`), while `displayName()` is the **identity** form. Everywhere a type name is needed as a key, the latter has to be used |
| A language-aware `TacBuilder` | The new `setSourceLanguage` / `setIrPrecision` let the Kotlin / Scala frontends reuse the same builder |
| The rule language bindings in `plumb-dfa` | `SinkFamily.languages`, `TaintRule.languages`, `SinkFamilyRegistry.lookupAll(sig, language)` |
| The Coverity gap rules | CWE-1333 (regex injection) and CWE-732 (unrestricted file access) landed and were registered |

**The core value of Phase 0: it fully prepares the IR/rule layer for "multi-language" while not changing a single line of behaviour.** That way, if Phase 1 goes wrong it can be rolled back immediately without affecting the existing Java capability.

> **The two "must-never-be-wrong" points of Phase 0**:
> 1. **The default value of `TacType.nullable` must be `true`**. If the default were `false`, Java's null-dereference detection would immediately miss a whole swath of reports. **A dedicated regression test has to lock down this default.**
> 2. **The audit of the call sites of `TacType.equals`.** Putting `nullable` into `equals` is the only place where the existing equality semantics change — it is done intentionally ("nullable X" and "non-null X" really should not be equal), but every existing call site has to be confirmed not to depend on the old semantics.

> **Why J1/J2 belong in Phase 0 rather than later**:
> - **J1 is a bug fix**, not a feature. The current `JavaModule.languageLevel` is passed in but used by nobody — it is a broken chain.
> - **J2 is the first brick of the later language capability**: the accessor of a Java `record` is a **compiler-synthesised method** — the same kind of thing as the `componentN` of a Kotlin `data class` or the `copy` of a Scala `case class`. Building "synthetic method identification" on the Java side first gives Kotlin/Scala a validated implementation paradigm, instead of each of the three writing its own.
> - **J1/J2 do not touch the IR** (both are frontend degradations, in line with the principle of §0.5.5).

### Phase 1 — The Kotlin Source Frontend (Analysis API Standalone) — **landed**

> **Status (2026-11-02)**: the module `plumb-frontend-kotlin` is implemented and passes **34 frontend tests + 4 DFA end-to-end tests**.

| Task | Explanation | Status |
|---|---|---|
| Introduce the `analysis-api-standalone` dependency (pinned to **2.5.0-Beta2**) | **Decoupled** from the main repository's `kotlin.version=2.2.0` (§0.5.4); the API is `@KaExperimentalApi` | ✅ |
| Building the Standalone session | `buildStandaloneAnalysisAPISession` + `buildKtModuleProvider` | ✅ |
| The symbol-resolution layer | `KaResolver` → the shared `SymbolResolver` | ✅ |
| Type extraction | `KaType` → `TacType` (including `nullable`) | ✅ |
| The expression → TAC translator | The Kotlin PSI expression tree → TAC instructions | ✅ |
| **Nullability** | `KaType.getNullability()` → the nullable dimension of `TacType`. **Three states**: non-null / nullable / **platform type** | ✅ |
| **Synthetic code** | `KaSymbol.getOrigin()` → `SOURCE_MEMBER_GENERATED` → `markSynthetic()` | ✅ |
| Line-number mapping | PSI offset → line number | ✅ |
| The annotation channel | Four channels: class/method/field | ✅ |
| `sink-families.yaml` gains Kotlin sinks | `kotlin.text.Regex`, `kotlin.system.getenv`, `kotlin.io.readLine` | ✅ |
| **Lambda bodies** | `KtLambdaExpression` → a standalone `TacFunction` | ❌ Not implemented (degraded into a problem + `SOURCE_LOWERED`) |
| **Coroutines / `suspend`** | State-machine modelling | ❌ Not implemented (Phase 3) |
| **Param-level annotations** | — | ❌ Not implemented |
| Mixed Java+Kotlin projects | Native Analysis API support | ✅ (the Java source has to be compiled into class files first before it can be resolved, see below) |

#### Platform types: the thing the Kotlin frontend gets wrong most easily

Kotlin has **three** nullabilities, not two:

| Type | Meaning | Mapped to `TacType` |
|---|---|---|
| `String` | Cannot be null at the declaration site | `nonNull()` |
| `String?` | May be null | The default (nullable) |
| **Platform type** (a Java type) | **The Java declaration carries no null information**, and the assertions the Kotlin compiler inserts can be stripped by `-Xno-call-assertions` / ProGuard | **nullable** |

**Platform types must be treated as nullable**, otherwise real NPEs are missed.

**How do you recognise a platform type?** You cannot rely on `getHasFlexibleNullability()` — for an **explicitly named** Java type
(`s: java.lang.StringBuilder`) it returns `false`, and the API reports it as an ordinary non-null type.
**The reliable signal is the origin of the symbol in the classifier**: `KaSymbolOrigin.JAVA_LIBRARY` / `JAVA_SOURCE`.

> **This is where the Kotlin frontend derives its real value**: on the bytecode path there is no way to distinguish a "platform type" from a "non-null type",
> so you can only guess; on the source path you **read the type system directly — zero heuristics**.

#### Another real trap: the content scope decides whether a file can be parsed

`KotlinStandaloneProjectStructureProvider.getModule` selects a module using `module.contentScope.contains(file)`.
**The default scope is a file list frozen at build time** — a file that is put in after the build falls into
`KaNotUnderContentRootModuleImpl` (a module **with no dependencies at all**), so `StringBuilder` / `Regex`
all become error types and every value is unknown.

**The solution**: `filesScope(project, supplier)` looks like it works, but `LazyFilesScope` **freezes the result of the supplier on the first query**.
`KotlinDirectoryScope` answers "is this file under the root directory" one query at a time, so every file put in later can be parsed.

> **Corollary — any scope that "freezes a file list" is wrong for streaming/incremental scenarios.**
> The files arrive one after another, so the scope has to be **decided one query at a time**.

**Acceptance**:
- ✅ On the Kotlin fixture, a finding set equivalent to Java's is produced (DFA end-to-end test)
- ✅ **A Kotlin data class does not report an "unused method"** (`componentN`/`copy` are compiler-generated)
- ✅ **Kotlin `val x: String` does not report an NPE** (unless it is explicitly `String?`)
- ✅ **Kotlin `val x: String?` reports an NPE on dereference** (**over-suppression must not happen** — this is the red line on the false-negative side)
- ✅ **A method call on a possibly-null receiver reports an NPE** (proving the receiver ends up in `args[0]`)
- ❌ Findings inside lambda bodies (not implemented)

### Phase 2 — The Scala 3 Source Frontend — **landed**

> **Status (2026-11-02)**: the module `plumb-frontend-scala` is implemented and passes **23 frontend tests + 4 DFA end-to-end tests**.

| Task | Explanation | Status |
|---|---|---|
| Introduce the `scala3-compiler` dependency (pinned to **3.10.0**) | The internal API is incompatible across versions, so a version lock at the `CrossVersion.full` level is needed | ✅ |
| `StandardPlugin` + `PluginPhase` | `runsAfter = Set("typer")` | ✅ |
| **The `tpd.Tree` → TAC translator** | According to the mapping table in §3.5 | ✅ |
| The degradation of `match` | A BRANCH chain (**before the pickler**, so the Maranget decision tree is not yet expanded) | ✅ |
| The synthetic methods of a case class | `copy` / `apply` / `unapply` / `copy$default$N` → marked as synthetic | ✅ |
| **The synthetic-detection method** | **The precise `Flags.Synthetic` flag** (not a name heuristic) | ✅ |
| Scala-specific sinks | `scala.sys.process.ProcessBuilder`, `scala.util.matching.Regex` | ✅ |
| **Lambdas / closures** | `tpd.Closure` → a standalone `TacFunction` | ❌ Not implemented |
| **Pattern-match narrowing** | The type narrowing of `case s: String` has no TAC representation | ❌ Degraded into `SOURCE_LOWERED` |
| **The field writes of a constructor** | dotc only synthesises `this.x = x` **after** the typer | ❌ The constructor is degraded into `SOURCE_LOWERED` |
| Scala → Java symbol resolution | Read the classpath `.class` → SootUp | ✅ |
| Java/Kotlin → Scala | The shared symbol table (the two-stage design of §4.7) | ⏳ Phase 3 |

#### The real shape of a Scala plugin phase (measured)

`PluginPhase` **on its own has no transformation hooks** — its only erasure-after method is `runsBefore()`.
The transformation hooks live on `MegaPhase.MiniPhase` / `MegaPhase`:

```java
// On the Scala side: class MyPhase extends MiniPhase with PluginPhase
// On the Java side: it must extend MiniPhase AND implement PluginPhase
final class ScalaTacPhase extends dotty.tools.dotc.transform.MegaPhase.MiniPhase
    implements PluginPhase {
  @Override public Trees.Tree<Type> transformUnit(Trees.Tree<Type> tree, Context ctx) { ... }
}
```

**Why `transformUnit` and not `transformBlock`**: `transformBlock` only fires on `Block` nodes,
whereas a compilation unit is a `PackageDef`, not a `Block` — so `transformBlock` **never fires** for a compilation unit.

**`superPhase` has to be set manually**: the `superPhase` of a `MiniPhase` that belongs to no `MegaPhase` is null,
and the `relaxedTyping` mechanism then throws an NPE. So this phase creates a `MegaPhase` containing only itself.

**How to install the phase**: by imitating how dotc installs its own `-Xplugin` phases — inherit `Contexts.ContextBase`,
override `addPluginPhases(List<List<Phase>>, Context)`, and then call
`Plugins$.schedule(plan, plugin.initialize(List.empty(), ctx))`.
At the same time `_plugins` and `_roughPluginsList` have to be set, to bypass the jar-loading path of `-Xplugin:`.

**Scala's `Flags` is 64 bits and the kind field occupies bits 0–1**, so `flags & Flags.Synthetic()` is **always non-zero**.
The correct test is `((flags >>> 2) & (flag >>> 2)) != 0`.

> **Corollary — confirm the bit width of a mask before testing with it.**
> Scala encodes the kind in the low bits, so a direct `&` also counts the kind bits in,
> and then the test for **any** flag returns true. The symptom is "every symbol looks synthetic".

**Acceptance**:
- ✅ On the Scala fixture, a finding set equivalent to Java's is produced (DFA end-to-end test)
- ✅ **The taint flow of a Scala `match` is tracked on every branch** (correctness of the degradation)
- ✅ **A Scala case class does not report an "unused method"**
- ✅ **Dereferencing a Scala `String` still reports an NPE** — this is the critical **reverse control**:
      it proves the frontend did not quietly borrow Kotlin's type system (Scala has no non-null type system)
- ⏳ **The cross-language taint chain from Scala → Java is unbroken** (Phase 3)

### Phase 3 — The Kotlin Compiler IR Cross-Validation Channel + the coroutine enhancement

| Task | Explanation |
|---|---|
| Introduce the `kotlin-compiler` dependency (the IR backend) | Heavyweight, so it needs a version-pinning strategy |
| `IrGenerationExtension` | Traverse `IrModuleFragment` |
| The `IrVisitor` → TAC translator | According to the mapping table in §3.5 (the Kotlin IR) |
| **The cross-validation harness** | The same fixture on both paths, with a diff of the finding sets (§6.4) |
| The degradation of `when` / `for` / `do-while` | A BRANCH chain |
| **Coroutine state-machine modelling** | `IrSuspendFunction` + the state-machine fields (§4.6) |

**Acceptance**:
- **The cross-validation diff is empty** (or all the differences are explainable)
- On the coroutine fixture, the number of findings is ≥ the number in Phase 1

### Phase 4 — Framework Bindings and Completing the Rules

| Task | Benchmarking Coverity |
|---|---|
| The `missing_tls_*` family | Coverity has already implemented them uniformly for the three languages Java/Kotlin/Scala |
| `regex_injection` (CWE-1333) | The only general-purpose rule that has to be written new |
| `unrestricted_access_to_file` (CWE-732) | File permissions |
| The Play / http4s / Dropwizard bindings | The Scala ecosystem |
| Kotlin/Android: `implicit_intent` (CWE-926) | Android-specific |

---

## 6. The Acceptance Criteria (more important than the phases)

### 6.1 Equivalence: the finding sets of Kotlin/Scala and Java must be equivalent

**This is the most core acceptance criterion.** The same logic written in three languages must produce consistent findings.

```
fixtures/equivalence/
├── sqli/           { Sql.java, Sql.kt, Sql.scala }   -> the expected finding counts are equal
├── cmdinj/         { Cmd.java, Cmd.kt, Cmd.scala }
├── path/           { Path.java, Path.kt, Path.scala }
├── xxe/            { Xxe.java, Xxe.kt, Xxe.scala }
├── nullness/       { Null.java, Null.kt, Null.scala }
└── lambda/         { Lam.java, Lam.kt, Lam.scala }
```

**The verdict**: the finding sets of the three files (CWE + position + taint source) must correspond one to one. Every difference must have an explicit explanation.

### 6.2 The False-Positive Red Lines (the mirror of the false-negative side)

| Case | Must | Must not |
|---|---|---|
| After Kotlin `val x: String`, `x.length` | Does not report an NPE | |
| After Kotlin `val x: String?`, `x.length` | **Reports an NPE** | Does not report |
| After Kotlin `val x: String?` + `if (x != null)`, `x.length` | Does not report an NPE (smart cast) | |
| The `componentN`/`copy` of a Kotlin data class | Does not report "unused" | |
| The `copy`/`apply` of a Scala case class | Does not report "unused" | |
| A Java reference type may be null | Reports an NPE | Does not report (**the `nullable` default-value regression test**) |
| Passing a Kotlin `String?` to a Java `String` parameter | Determined by the platform-type rule | |

### 6.3 The False-Negative Red Lines

| Case | Must |
|---|---|
| A taint finding inside a lambda body | Must report |
| Taint on every branch of a `when`/`match` | Must report |
| Taint across a suspend function | Must report (complete after Phase 3) |
| Cross-file taint (Kotlin ↔ Java ↔ Scala) | Must report |
| Taint on a coroutine state-machine field | Must report (Phase 3) |

### 6.4 Dual-Path Cross-Validation

**Kotlin has two source paths (Analysis API / Compiler IR). They have to corroborate each other.**

```
fixtures/crossvalidate/
├── lambda/         <- validates IrFunctionExpression on the IR path
├── when/           <- validates the degradation of when
├── coroutine/      <- validates the expansion of suspend
├── generic/        <- validates the taint propagation under type erasure
└── smartcast/      <- validates the smart cast
```

**The verdict**:
- The finding sets of the two paths **must agree** (line-number differences are allowed).
- Wherever they disagree, **it is either a bug or a known difference** — it has to be recorded case by case.
- **This harness is the most valuable bug detector**: the Analysis API has its own precision losses and the IR path has its own lowering differences; when the two do not line up, at least one of them is wrong.

---

## 7. The Risks and the Countermeasures

| Risk | Impact | Countermeasure |
|---|---|---|
| **The Kotlin Analysis API is `@KaExperimentalApi`** | High (the official docs say "subject to incompatible changes") | **Pin the version + isolate it into a standalone module + use the compiler IR path for cross-validation** (§6.4). A Kotlin major-version upgrade only has to touch one module |
| **The size of the Kotlin compiler dependency** (~50MB+) | Medium | The Analysis API Standalone dependency is far smaller than the full compiler; use Maven's `provided` scope + isolate it into a standalone frontend module |
| **The Scala compiler internal API is incompatible** | High | A version lock at the `CrossVersion.full` level; a Scala 3 minor-version upgrade requires a re-publication |
| **Kotlin null-safety false positives** | High (they drown the report) | **Direct determination by the type system** (`KaType.isMarkedNullable`) + `TacType.nullable` + the `dfa-nullness` fact domain (three layers, see §4.4) |
| **Kotlin null-safety false negatives** (over-suppression) | High | The red-line cases of §6.2: `String?` must report an NPE. **The default `nullable=true`** is the key line of defence |
| **The breaking change to the `TaintRule` record** | Medium | Add a compatibility constructor; `TaintRule` already has a precedent of 6/7-arg overloads |
| **Getting the `TacType.nullable` default wrong** | High | **A dedicated regression test locks down the default**; it is a Phase 0 acceptance item |
| **The complexity of cross-language symbol resolution** | Medium | The two-stage design (Pass 1 symbol collection / Pass 2 translation); the `SymbolResolver` abstraction |
| **Line-number misalignment** (coroutines/inline) | Medium | The `IrPrecision` marker; when it is not trustworthy, degrade to reporting at the method level |
| **Memory pressure** | Medium | Known: CWE-191 uses 4GB RSS in a single process. **One language per process, run serially** |
| **Scala 2 users** | Medium | Explicitly unsupported; the official path is to migrate to Scala 3. The documentation has to say this clearly |
| **The expectation gap for dynamic languages** | Low (not done) | Do not claim support; §3.6 gives the reasoning |
| **The broken chain of the Java frontend's language level** | **High (a pre-existing defect)** | `JavaModule.languageLevel` was never mapped to `ParserConfiguration.LanguageLevel` → always `JAVA_17` → **Java 21+ syntax fails to parse** (measured). See step J1 of §0.5.3 |
| **Zero translation coverage for Java 14+ syntax** | **High (a pre-existing defect)** | `SwitchExpr` / `RecordDeclaration` / `PatternExpr` are all unimplemented in the translator → even when parsing succeeds, no correct TAC can be produced → **false negatives**. See step J2 of §0.5.3 |
| **Java 25 is the current LTS but we only reach 17** | Medium | After J1+J2 are complete, switch to `JAVA_21` (the ceiling of JavaParser 3.26.4); keep `BLEEDING_EDGE` as an experimental switch, not as the default |

---

## 8. How This Connects to the Existing Work

The user's existing pain points (from the project history):

1. **"fscanf writes a pointer, SSA cannot see it"** → **the source path solves it directly**. The IR layer has no notion of an out-param (Java's `fscanf(ptr, ...)` is just an ordinary parameter in TAC). This is a direct payoff of the source main line.
2. **"TAC simplifies if hMutex"** → language-agnostic.
3. **"Cwe252/253 unchecked return misses"** → language-agnostic, but Kotlin's `Result<T>` and Scala's `Either` are **language-native error-handling mechanisms**, which are worth targeting as a standalone checker (a language-specialised version of CWE-252).
4. **"do-while + multi-syscall IR adaptation"** → Kotlin's `do-while` is `IrDoWhileLoop` in the IR, and degrading it into BRANCH+JUMP is enough; **a natural payoff of the source path**.
5. **"R62 for-loop induction made explicit"** → when Kotlin's `for` is degraded, the induction update of the loop variable is explicit, which is naturally easier to handle than the AST translation of Java.
6. **"Cwe191 integer underflow (Z3 constraint solving)"** → the source path has more complete type information (Kotlin has the unsigned types `ULong`/`UInt`), so the precision of the Z3 constraint solving is higher.

**A new suggestion**: the error-handling checks for Kotlin `Result<T>` / Scala `Either` (the CWE-252 specialisation) are a **differentiated capability that only multi-language support can deliver**, and are worth listing separately.

---

## 9. The Open Questions

> **Status snapshot (2026-11-02)**: questions **1, 2, 3, 4 and 6 have been decided and landed**, and 8 has been answered by the implementation process.

1. **Does the Kotlin frontend use the Analysis API or the Compiler IR?** —— **Decided: Analysis API Standalone as the main path + Compiler IR as the cross-validation channel** (§3.4).
   - The reason: the Analysis API gives nullability directly (`KaType.getNullability()` → `KaTypeNullability.NULLABLE` / `NON_NULLABLE` / `UNKNOWN`) and the synthesis marker (`KaSymbol.getOrigin()` → `KaSymbolOrigin.SOURCE_MEMBER_GENERATED`), with **zero heuristics**.
   - The risk is accepted: the API is experimental, and the mitigation is **version pinning + a standalone module + dual-path cross-validation**.

2. **The target version number for Scala 3?** —— **Decided: 3.10.0**, with the minor version pinned.
   - The alternative is 3.9.0 LTS — if stability matters more than new features.
   - Note: from 3.8 onwards **JDK 17+ is required** (the local JDK 25 satisfies this); 3.8.0/3.8.1 have regressions, **do not use them**.
   - ✅ **The dependency is confirmed resolvable**: `scala3-compiler_3:3.10.0` has been pulled from Central into the local repository.

3. **The target version number for Kotlin?** —— **Decided as `2.5.0-Beta2`** (the Maven version of the Analysis API), decoupled from the main repository's `kotlin.version=2.2.0` (§0.5.4).
   - ⚠️ **Note**: the **only version of `kotlin-analysis-api` currently on Maven Central is `2.5.0-Beta2`** — a Beta, not a stable release. The originally envisaged 2.4.21 **does not exist for that artifact**.
   - For comparison: Coverity 2024.9.x only supports up to Kotlin 2.0.0 — we are newer than it, and there is no need to align.

4. **How are the frontend modules' dependencies isolated?** —— **Decided: standalone Maven modules**.
   - What actually landed: `plumb-frontend-kotlin` / `plumb-frontend-scala`, alongside `plumb-tac`, and **not** entering the transitive dependencies of `plumb-tac`.
   - **Why the isolation is necessary (not merely for tidiness)**: the Analysis API is `@KaExperimentalApi` (the official docs say "subject to incompatible changes"), and dotc's `PluginPhase` is a compiler internal API. **No upstream breaking change should be able to break the Java analysis path** — the isolated module is that boundary.
   - ✅ The dependency is confirmed resolvable (see the artifact-structure table and the mirror notes in §0.5.4).

5. **How is the cross-language symbol table stored?**
   - The two-stage design needs a shared symbol index.
   - **Option A**: an in-memory Map (within a single analysis session) — **already implemented**: `DefaultCompileContext` contains a concurrent in-memory symbol table (`SymbolTable`).
   - **Option B**: on disk (reusing the existing `IrSerializer` disk-cache mechanism) — to be evaluated in Phase 3.

6. **Confirming the default value of `TacType.nullable`?** —— **Confirmed as `true`**, and locked down by a regression test.
   - `MultiLanguageIrContractTest` asserts: all singleton constants, and the types produced by `ofClass` and `arrayOf`, have `isNullable()` equal to `true`.
   - ✅ The audit of the `equals` call sites is complete: `FieldOpClassifier.classNameOfType` now uses `displayName()`.
   - ⚠️ **But the implementation process turned up a problem the design document did not anticipate**: the original design assumed that the `equals` of `TacVar` would include the type. **In reality it cannot** — see "Two Corrections After Landing" in §3.2.

7. **How far is a Scala `match` degraded?** —— **Fully degraded into a BRANCH chain** (retaining taint tracking on every branch). That is what the IR contract requires anyway.
   - Because structural information is lost, the affected functions are marked `IrPrecision.SOURCE_LOWERED`.

8. **The nullability dimension of `TacType` vs the nullability dimension of `TacVar` — which is more suitable?** —— **Answered by the implementation: go with `TacType`, but the identity of `TacVar` must be independent of the type**.
   - The original design chose `TacType` (§3.2 extension 5), because `TacVar` is a record and adding a field would change the canonical constructor + `equals`/`hashCode`, which is more breaking.
   - ✅ During landing it turned out: `TacVar` is already a **class rather than a record**, and its identity semantics are "storage location".
   - **The final rule**: nullability lives in `TacType`; `TacVar.equals` only looks at `name + isTemp`.
   - **Why it has to be this way**: every per-variable map of the DFA uses `TacVar` as its key. If the type participates in equality → the refined variable becomes a different key → **the taint is silently lost** (no exception, just one fewer finding).
   - **If it later turns out that "the same variable has different nullability on different paths" (smart cast) makes the expression hard**, then consider pushing it down into `TacVar` — but that would mean solving the identity-splitting problem at the same time.

9. **What should the default value of the Java language level be?** (§0.5.3 J1)
   - Option A: **`JAVA_21`** (the official ceiling of JavaParser 3.26.4). Recommended.
   - Option B: `BLEEDING_EDGE` — it passes more syntax, but **it is not suitable as a default** (bleeding edge may introduce regressions, and it will change in future versions).
   - **The recommendation**: default to `JAVA_21`, provide a CLI/configuration override, and allow explicitly selecting `BLEEDING_EDGE` as an experimental switch.

10. **The priority of the Java 14+ syntax translation?** (§0.5.3 J2)
    - The three items `SwitchExpr` / `RecordDeclaration` / `PatternExpr`.
    - **The recommendation**: `RecordDeclaration` first (it also validates the "synthetic method identification" mechanism, which Kotlin/Scala will reuse), then `SwitchExpr`, and `PatternExpr` last.
    - **To be confirmed**: whether to finish all of them within Phase 0, or to move `RecordDeclaration` into Phase 0 and the rest before Phase 1.

---

## 10. The Overall Landing Status Table (2026-11-02)

### 10.1 Complete and Locked Down by Regression Tests

| Capability | Location | Tests |
|---|---|---|
| The two enums `SourceLanguage` / `IrPrecision` | `plumb-tac/.../ir/` | `MultiLanguageIrContractTest` |
| The language / precision / synthesis markers of `TacFunction` | `plumb-tac/.../ir/TacFunction.java` | The same |
| The `TacType.nullable` dimension + `displayName()` | `plumb-tac/.../ir/TacType.java` | The same |
| The `TacOperand.TacVar` identity rule (name + isTemp) | `plumb-tac/.../ir/TacOperand.java` | The same + `KotlinTacVarIdentityTest` |
| The `NULL_CHECK` opcode | `plumb-tac/.../ir/TacInstruction.java` | The same |
| The `TacFrontend` / `CompileContext` / `SymbolTable` SPI | `plumb-tac/.../frontend/` | The Java frontend is the reference implementation |
| The Java frontend plugging into the SPI | `plumb-tac/.../frontend/java/JavaTacFrontend.java` | — |
| The Java language level chain (8–25 → named level) | `plumb-parser/.../JavaLanguageLevel.java` | `JavaLanguageLevelTest` (9) |
| The Java 14+ syntax translation (switch expr / pattern / record) | `plumb-tac/.../translate/JavaModernFeatures.java` | `JavaModernFeaturesTest` (14) |
| The rule language bindings (`SinkFamily.languages` / `TaintRule.languages`) | `plumb-dfa/.../config/` | `SinkFamilyLanguageTest` (9) |
| The DFA language gate (both the analysis loop and the summary loop) | `plumb-dfa/.../engine/DfaEngine.java` | `DfaLanguageGateTest` (8) |
| Kotlin null safety (type nullability + `NULL_CHECK`) | `plumb-dfa/.../taint/NullabilityFacts.java` | `KotlinDfaIntegrationTest` (4) |
| CWE-1333 regex injection + CWE-732 unrestricted file access | `plumb-dfa/.../checkers/taint/` | `GapRuleCheckersTest` (9) |
| **The Kotlin source frontend** | `plumb-frontend/.../kotlin/` | **34** + owner 8 + end-to-end 4 |
| **The Scala 3 source frontend** | `plumb-frontend/.../scala/` | **23** + owner 5 + end-to-end 4 |
| **The frontends plugging into the scan path (`.kt` / `.scala` really get scanned)** | `plumb-main/.../main/FrontendRegistry.java` | `MultiLanguageScanE2ETest` (5) |
| **The callee signature's owner unified as `owner.method/arity`** | The two translators | `KotlinCalleeOwnerTest` (8) + `ScalaCalleeOwnerTest` (5) |
| **The module layering (the core / engine / app aggregator POMs)** | `plumb-core/` `plumb-engine/` `plumb-app/` | See §12.3 |
| The Java syntax coverage matrix (the inventory is closed + the real degradation) | `plumb-tac/.../translate/AstNodeInventoryTest.java` | 5 |
| The construct → IR shape contract | `plumb-tac/.../translate/JavaConstructIrContractTest.java` | 19 |
| The annotation value channel (`TacFunction.annotationValues()`) | `plumb-tac/.../ir/TacFunction.java` | The same |
| The two kinds of the Problem channel (`UNHANDLED_NODE` / `PARSE_PROBLEM`) | `plumb-tac/.../translate/TacBuilder.java` | `UnhandledNodeReportingTest` + the same |
| The fix for the silent degradation of `super` / `TypeExpr` / a declaration in expression position | `plumb-tac/.../translate/ExpressionTranslator.java` | The same |
| The type mapping of `VoidType` / `UnionType` / `IntersectionType` / `TypeParameter` | `plumb-tac/.../translate/TypeMapper.java` | The same |
| The degradation of the compact constructor (`CompactConstructorDeclaration`) | `plumb-tac/.../translate/TacTranslator.java` | The same |
| **The front-end/back-end separated consumer entry point** | `plumb-main/.../main/IrAnalysisRunner.java` | **6** |

### 10.2 Not Yet Implemented (listed explicitly, with no ambiguity)

| Capability | Why it was not done | When |
|---|---|---|
| A Kotlin lambda body → a standalone `TacFunction` | The PSI traversal of the Analysis API does not cover it | Phase 3 |
| A Scala closure → a standalone `TacFunction` | The same | Phase 3 |
| The `is` / `in` patterns of a Kotlin `when` | TAC has no type-test opcode; **it is not added** | Phase 3 |
| The type narrowing of a Scala pattern match | The same | Phase 3 |
| The field writes of a Scala constructor | dotc only synthesises `this.x = x` **after** the typer — it cannot be obtained before the pickler | No short-term solution |
| Kotlin coroutine state-machine modelling | The IR needs to express "variables that live across a suspension point" | Phase 3 |
| The param-level annotation channel | Not yet obtained from the Analysis API | Phase 3 |
| The cross-language symbol table (Java ↔ Kotlin ↔ Scala) | The in-memory symbol table is in place, but the frontends are not wired to each other yet | Phase 3 |
| The Kotlin Compiler IR cross-validation channel | It needs a full compilation, which is slow | Phase 3 |

> **When an implemented frontend encounters a construct it cannot degrade, it does not silently drop it** — it reports a problem
> and degrades the precision of that function to `SOURCE_LOWERED`. That way the checker knows it must not trust the precision-related facts of that function.

### 10.3 Two "Reverse Control" Tests (important)

The most dangerous failure mode of a multi-language frontend is **silently borrowing another language's semantics**. So two tests exist specifically to watch out for that:

| Test | Assertion | What it guards against |
|---|---|---|
| `KotlinDfaIntegrationTest` | Dereferencing a Kotlin `String?` **still reports** an NPE | Over-suppression (a false negative) |
| `ScalaDfaIntegrationTest` | Dereferencing a Scala `String` **still reports** an NPE | The frontend secretly using Kotlin's non-null type system |

**Both passing = the frontend neither over-suppresses nor borrows semantics it should not have.**

### 10.4 The Full Regression Baseline

```
mvn test   →   BUILD SUCCESS
```

| Module | Tests | Failures | Errors |
|---|---|---|---|
| plumb-parser | 19 | 0 | 0 |
| plumb-tac | 218 | 0 | 0 |
| plumb-frontend | 70 | 0 | 0 |
| plumb-rules | 27 | 0 | 0 |
| plumb-reporter | 31 | 0 | 0 |
| plumb-style | 1266 | 0 | 0 |
| plumb-dfa | 475 | 0 | 0 |
| plumb-build-core | 77 | 0 | 0 |
| plumb-build-maven | 39 | 0 | 0 |
| plumb-build-capture | 114 | 0 | 0 |
| plumb-ai | 85 | 0 | 0 |
| plumb-dsl-kotlin | 69 | 0 | 0 |
| plumb-main | 129 | 0 | 0 |

**2534 tests in total, 0 failures, 0 errors** (all 18 modules SUCCESS, the full build **5 minutes 17 seconds**).

That is **167 more tests** than the 2367 before the frontend capability push (+7.1%), all of them new ones:
the syntax coverage matrix, the construct contracts, the consumer end-to-end tests, the source-reading boundaries.

> **The baseline was re-recorded after the TAC semantic audit** (§13). The audit added exactly 32 tests
> and repaired 14 data-flow defects, and the suite grew from 2497 to **2529**. The cross-language
> measurement (§14) added 5 more (`CrossLanguageInteropTest`), giving **2534**. The count is reconciled
> line by line: `plumb-tac` +6 (`StaticCallScopeTest`), `plumb-main` +26 (`TacTaintSurvivalTest`)
> +5 (`CrossLanguageInteropTest`). Every other module is unchanged — a regression would have shown up
> as a drop, not a rise.
>
> **One caveat on the totals**: 5 tests in `plumb-ai` (`LlmIntegrationTest`) are **skipped** because they
> need a live LLM endpoint. They are counted as run-but-skipped, not as failures.

> **The build time dropped from 25:52 to 5:17** (roughly 5×). This was **not** the result of optimisation;
> it came from **fixing a contract violation**: the consumer no longer reads source code (§12.6).
> In those earlier 19 minutes, the overwhelming majority of the time was the consumer reading the 48,000
> `.java` files in `%TEMP%` one by one.

> **The discipline of recording the baseline**: every change has to re-record the full baseline and write it into the documentation.
> Recording only "I ran the tests I newly wrote" is not enough — **a regression is a whole-suite matter**.
>
> **One observation (already corrected)**: this baseline once recorded that "the consumer tests of `plumb-main` took 705 seconds,
> because the DFA engine itself is slow and every case has to build a global call graph".
>
> **That attribution is wrong.** The real reason is that **the consumer was reading source code** — the engine happened to build a
> cross-file constant resolver that read the `.java` files in the directory tree; the root directory was "two levels up",
> so JUnit's temporary files dragged the whole `%TEMP%` in (48,631 `.java` files on this machine).
>
> After the fix (§12.6): **795 seconds → 2.3 seconds**. The DFA analysis itself only ever took 58 milliseconds.
>
> > **Corollary — a wrong attribution gets its cost recorded as "the engine is slow" and then accepted.**
> > A 13-minute test suite is easily explained as "this engine is just heavy".
> > The real cost is that **a contract was broken**: a consumer that advertises "no source code" was reading source code.
> > That is not merely slow — it makes the same IR potentially give different answers on different machines.
> >
> > **So a performance anomaly has to be investigated as a contract anomaly.** First ask "is it doing something it should not be doing",
> > and only then ask "can it be faster".

### 10.5 A Record of an "Invisible Pollution"

At the end of the work `git status` reported 206 modified files, but `git diff --stat` only had 28.
The difference was **176 files that "only gained a trailing newline"**.

**The cause**: certain tools (formatting scripts / editors) uniformly add a trailing newline when writing files, while most
files in the repository originally had none. This is **completely unrelated** to this work, but it pollutes the diff and drowns the real changes.

**The handling**: compare file by file (whether they are byte-identical after the trailing newline is removed), keep only the **real changes**,
and restore the rest with `git checkout`.

> **Corollary — the "number of changed files" and the "number of changed lines" are two different quantities, and both have to be looked at.**
> Looking only at `git status` drowns you in noise; looking only at `git diff --stat` misses the stat-cache problems.
> **A byte-for-byte comparison after normalisation, file by file, is the reliable approach.**

There was also found a **bare NUL byte** in `SinkFamilyRegistry.java` (it should have been a `"\0"` escape) —
Java allows a bare NUL inside a string literal, so it **does compile**, but git then treats the file as **binary**,
and the diff stops working completely. Problems like this are **invisible to both the compiler and the tests**; only git says something.

---

## 11. The Frontend Capability Push: Making "every syntactic structure → IR" Measurable (2026-11-02)

The previous sections established the **architecture** (source AST → IR is the main line, the IR is a contract and not a cache).
This section records the **capability**: how much syntax the Java frontend can actually turn into IR, how that is proved, and how regressions are prevented.

### 11.1 The Reprioritisation

The user explicitly asked for **frontend capability** to be the top priority:

> "All the syntactic structures must be converted into IR to support the relevant capabilities, otherwise the backend analysis is certainly a very inaccurate analysis"

This judgement is correct, and there is a concrete mechanism behind it: **when the frontend misses one syntactic structure, what it produces is not a "false negative" but "silent data-flow pollution"**.

The fallback of the translator is to return a `null` constant. So:

```
a tainted value -> an unsupported construct -> a null constant -> every downstream fact about it vanishes -> the finding disappears silently
```

**No link in this chain throws an exception.** There is no exception, no default logging, and the only symptom is "the finding is gone" —
and "the finding is gone" looks **exactly the same** as "the code is safe" downstream.

> **Corollary — the severity of a frontend gap is the square of a backend rule error.**
> If one rule's implementation is wrong, the impact is that one rule.
> If the frontend misses one syntactic structure, the impact is **every** rule that depends on that construct, and it cannot be detected.

### 11.2 Three Measured Gaps of "Silent Degradation"

Everything fixed here was **silent** — they do produce instructions, so a "did it produce instructions?" check cannot see them.

| Gap | Symptom | Root cause | The fix |
|---|---|---|---|
| `SuperExpr` | The receiver of `super.foo()` is `null` → **every `super.method()` reports a null pointer** | The translator returns a `null` constant | Degrade to the `this` local — `super` and `this` are the same object, only the dispatch target differs |
| `TypeExpr` | Using a type as a value becomes `null` | The translator returns a `null` constant | Degrade into a `java.lang.Class` reference (non-null) |
| `VariableDeclarationExpr` (in expression position) | **The declared local variable is never declared at all**; the taint of the initialiser is lost | The translator returns `null` **and emits no instruction at all** | Route it to `StatementTranslator.translateVarDecl` — the statement path shares the same implementation |

The `SuperExpr` one is the most worth recording: **it is not a false negative, it is a false positive**. Every `super.toString()` triggers a null-pointer warning.

### 11.3 The Two New Channels of the IR

#### 11.3.1 The annotation **value** channel (`TacFunction.annotationValues()`)

Until now the IR only had the **names** of annotations. But:

```java
@Value("${app.password}")   // the name is Value
@Value("${app.username}")   // the name is still Value -- but that is a completely different fact
```

The name channel can only answer "is it annotated with Value"; only the value channel can answer "which configuration item is bound".
The latter is the question the consumer actually needs, because **a configuration binding value is a value that comes from outside the program**.

A new `Map<String, List<String>>` is added, whose key is the simple name of the annotation and whose value is the value it carries:

| Annotation form | What it contributes |
|---|---|
| `@Marker` | No value (the key exists, the value is empty — "it is annotated" is itself information) |
| `@Single("v")` | `"v"` |
| `@Normal(a = "v", b = 1)` | `"v"`, `"1"` |
| `@Normal(a = {"x","y"})` | `"x"`, `"y"` (element by element) |
| `@Outer(@Inner("z"))` | `"z"` (attached under the outer annotation's name, not lost) |

**The directional rule**: like the annotation names, this is **metadata**. It may be used to suppress false positives and to identify roles,
but it **must never be used to prove runtime safety** — it comes from the source text, not from a runtime observation.

#### 11.3.2 The two kinds of the Problem channel

The existing problem channel makes the gaps **visible**, but visible does not mean small. Now two kinds of fact are distinguished:

| kind | Meaning | How the consumer should react |
|---|---|---|
| `UNHANDLED_NODE` | The construct is **not supported by the frontend** | A feature has to be added |
| `PARSE_PROBLEM` | **The input is broken** (the source failed to parse, the parser recovered) | The code has to be fixed or the parser replaced |
| `TRANSLATION_ERROR` | An **exception was thrown** during the degradation | A frontend bug has to be investigated |

Conflating the first two means the consumer cannot distinguish "this file uses a feature I do not support" from "this file does not even compile".

The new sources:
- `JavaParser`'s `ParseProblem` → recorded on the **program** (a parse error can occur between two declarations, and belongs to no single function)
- `UnparsableStmt` → recorded on the **function**, as a `PARSE_PROBLEM`

#### 11.3.3 The SPI upgrade: from string problems to structured problems

The frontend SPI's original `ParseResult.problems()` was a `List<String>`. A string can only say "something went wrong",
and cannot say **which construct, which function, which line**.

For a **consumer without source code** this is fatal: it receives the IR and only knows "there is a problem somewhere",
and can neither distinguish "the frontend lacks a feature" from "the source does not compile" nor point at the line to look at.

The new `TacFrontend.StructuredParseResult` and `parseStructured(...)` are added:

| Layer | Shape |
|---|---|
| The old SPI (retained) | `ParseResult(functions, List<String> problems, precision)` |
| The new SPI | `StructuredParseResult(functions, List<FunctionProblem> problems, precision)` |

`parseStructured` has a default implementation (it wraps the strings into structured problems),
so **the existing frontends compile without any change**; but only the frontends that override it can give a construct name and a position.

All three frontends already override it:

| Frontend | The source of its structured problems |
|---|---|
| Java | `TacProgram.programProblems()` (the structured problems the translator already records) |
| Kotlin | The translator's `report(...)`: construct name + line + column |
| Scala | The compiler's diagnostics (the line number is parsed out of the diagnostic text) + the translator's failures |

> **Corollary — the shape of the SPI determines the shape of the IR.**
> If the SPI can only carry strings, the IR can only carry strings,
> and so "how much detail the consumer can see" was already decided at the moment the SPI was defined.
> **To change it, you change the SPI, not the consumer.**

The Scala line number is parsed out of the diagnostic **text** (`path:line: message`),
rather than read from a `SourcePosition` object — because the latter needs the compiler `Context`,
and after the compilation only the reporter survives.
**If information can be obtained from public text, do not touch the compiler internal API**: the internal API changes with the version, the text does not.

### 11.4 The Syntax Coverage Matrix: Measurable, Rather Than Relying on Good Intentions

`AstNodeInventoryTest` does two things, **both directions are required**:

**Direction one — the inventory is closed.**
Read out of the **constant pool of the compiled class files** which AST node classes the frontend references,
and compare them with the set of **concrete** node classes JavaParser actually defines.

- It is not a hand-written inventory: a hand-written inventory rots (a construct is added and the inventory is not updated, the test still passes, and yet it says nothing about the new construct)
- It is not a scan of the source text: that would pick up comments and javadoc
- The constant pool is a **superset** (it also contains classes that are merely imported), and for the question "was it mentioned at all" a superset is the right answer
- Abstract base classes (`LiteralExpr`, `Expression`…) are excluded: they **never** appear in a concrete tree,
  and the frontend matches the concrete subclasses
- Name nodes (`Name`, `SimpleName`) are excluded: they are not expressions and do not appear in expression positions

**Direction two — the real degradation.**
A source file written with **every** supported construct must degrade with **zero problems**.
A closed inventory only says "it was mentioned"; the real degradation is what says "it was really handled".

> **Corollary — doing only direction one yields an engine that "mentions but does not handle";
> doing only direction two yields an engine that "handles but has a stale inventory". The two must be paired.**

### 11.5 The Real Gap the Inventory Test Caught Immediately

This test is not a formality — it **caught on the spot** a real type-mapping bug:

```
Expecting empty but was: ["com/github/javaparser/ast/type/VoidType"]
```

**`void` is not a `PrimitiveType`.** In JavaParser, `VoidType extends Type`,
and it is **on a par with** `PrimitiveType`, not a subclass of it. So the `instanceof PrimitiveType`
branch of `TypeMapper.map()` **cannot catch `void`**, and `void` falls through to the final
`return TacType.UNKNOWN`.

The consequence is not "the type name does not look nice": `TacType.UNKNOWN` means "a value of unknown type",
so a `void` method looks like it **has a return value**. This is the only language type the frontend **cannot name**.

The fix is to add a separate `VoidType` branch after `PrimitiveType`.

> **Corollary — "the frontend can name every type in the language" is a property that should be tested,
> not one that can be assumed.** This property breaks over a single missing `instanceof` branch.

### 11.6 The Consumer Entry Point with the Frontend and Backend Separated

The user's goal is explicit:

> "translate is able to convert the source code into IR, then the IR can be stored locally,
> and in the next stage it is possible to read the IR and analyse it directly, without any source code, and scan the results"

The new `plumb-main/.../IrAnalysisRunner.java`: **IR in, findings out**.

| Method | Input |
|---|---|
| `analyzeMerged(Path)` | A merged IR file (`merged.tac`) |
| `analyzeProgram(Path)` | A single-file IR |
| `analyze(MergedProgram)` / `analyze(TacProgram)` | An in-memory IR |

The consumer **does not need**:
- the source files (they may already be gone)
- the classpath (the analysis only uses the type information the IR carries)
- the Java toolchain (no compilation happens here)

The consumer **does need** (all of it provided by the IR): the instructions, the source-path table, the class hierarchy, the annotation metadata, the problem channel.

The result object `IrAnalysisResult` returns the **findings** and the **problems** separately:

- A finding says "this piece of code has problem X"
- A problem says "I could not fully translate this piece of code, so my findings are a lower bound"

**When the two are mixed in one list, the receiver cannot distinguish "a clean program" from "a program the analyser could not read".**

Every case of `IrAnalysisConsumerTest` (6 tests) **deletes the source files before the analysis** —
that is the only way to prove the analysis did not secretly read the source back. Asserting "the findings are the same" is not enough,
because an analyser that happens to be able to read the neighbouring source would also pass that assertion.

### 11.7 The List of New Tests

| Test | Location | Count | What it guards |
|---|---|---|---|
| `AstNodeInventoryTest` | `plumb-tac/.../translate/` | 5 | A closed inventory + the real degradation + the authenticity of the references |
| `JavaConstructIrContractTest` | `plumb-tac/.../translate/` | 19 | The **shape** contract of construct → IR (not merely "there are instructions") |
| `IrAnalysisConsumerTest` | `plumb-main/.../main/` | 6 | End-to-end analysis with no source and no classpath |
| `JavaSyntaxCoverageTest` (existing, expanded) | `plumb-tac/.../translate/` | 60 | Every syntactic structure produces instructions |

The difference between `JavaConstructIrContractTest` and `JavaSyntaxCoverageTest` is worth spelling out:

- `JavaSyntaxCoverageTest` asks "did this construct **produce instructions**?"
- `JavaConstructIrContractTest` asks "did this construct produce the **right instructions**?"

They have to be separated, because the two kinds of failure look different: a construct degraded into a `null` constant **still produces instructions**,
and still destroys the data flow. Only asserting the **shape** of the instructions (the kind, the operands, the values) can catch it.

---

## 12. Plugging the Frontends into the Scan Path (continued 2026-11-02)

The capability table in §10 says "the Kotlin source frontend is implemented". That sentence was true at the time,
and it was also **incomplete**: the frontend ran, all of its own tests were green, but **no real scan could read
a single Kotlin file**. This section records the step that made it true, and the three problems it exposed.

### 12.1 Three "Implemented ≠ Reachable" Traps

A frontend passing its own unit tests can only prove that **the frontend is good**, not that **the frontend is called**.

The failures of these two things look identical: both are "zero findings".

| Layer | What it can prove | What it cannot prove |
|---|---|---|
| The frontend's unit tests | That the translation logic is correct | That the scan path calls it |
| The DFA integration tests | That the engine can consume that TAC | That the scan path produces that TAC |
| **The scan end-to-end test** | **That the whole chain is connected** | — |

So `MultiLanguageScanE2ETest` was added (`plumb-main`), and it **goes through the real
`ScanRunner`**, using a textbook SQL injection as the probe:

```kotlin
import java.sql.Statement
class Vulnerable {
  fun query(stmt: Statement, userInput: String) {
    stmt.execute(userInput)     // it must report CWE-89
  }
}
```

This test covers six links in one go: the file is **discovered** → the right frontend is **selected by extension** → it is translated into TAC →
the callee signature **carries the owner** (otherwise the sink family does not match) → the taint **propagates** →
the checker **reports the CWE**. Breaking any one link makes it red.

> **Corollary — "zero findings" has two completely different meanings, and they have to be distinguished by different tests.**
> "This frontend was never called" and "this code is clean" look exactly the same downstream.
> The only way to distinguish them is a test that goes through the whole path.

### 12.2 The Three Real Defects Exposed When Plugging into the Scan Path

**Defect one: Kotlin's expression-body functions were degraded into empty functions.**

`KotlinTacFrontend` passes `fn.getBodyBlockExpression()`, but the body of a `fun f() = expr` is not a `KtBlockExpression`,
it is a `KtExpression` — so `getBodyBlockExpression()` returns **null**. Every expression-body function was therefore degraded into an **empty** one:
the data flow passing through it was cut,
and the taint passing through it was **silently lost**.

Expression bodies are extremely common in Kotlin, so this is not a corner case. The fix is to use `getBodyExpression()`,
which returns the function body for both block bodies and expression bodies, leaving it to the translator to tell them apart.

**Defect two: the owner of a Java property was read from the wrong place.**

`sb.length` (`sb: java.lang.StringBuilder`) is resolved by the Analysis API in Kotlin as
**INTERSECTION_OVERRIDE** (the intersection override that Kotlin synthesises for Java members), rather than as
`JAVA_LIBRARY`. Measured:

```
property -> origin=INTERSECTION_OVERRIDE, callableId=java/lang/StringBuilder.length
getter   -> origin=INTERSECTION_OVERRIDE, callableId=null
```

**The declaring class is on the property, not on the getter.** Reading the getter (the most natural way to write it) yields
`null`, so this property silently degrades into a FIELDLOAD — a field that does not exist, and that **never matches
any sink family**, because a sink family is anchored on the **method name**.

Going further, "is this member from Java" cannot be asked of the symbol's origin either (the origin is
`INTERSECTION_OVERRIDE`); you have to ask whether **the class it belongs to** is a Java class.

**Defect three: a dependency version conflict that silently zeroed every Kotlin finding.**

This is the most insidious one, and the most worth recording.

```
NoClassDefFoundError: kotlin/jvm/internal/KotlinGenericDeclaration
```

Analysis API 2.5.0-Beta2 uses `KotlinGenericDeclaration`, which **only exists from stdlib 2.3.x**.
And `plumb-main` depends on both the DSL script engine (transitively stdlib **2.2.0**) and the
Analysis API (transitively stdlib **2.3.21**) — Maven's "nearest wins" rule means **2.2.0 won**.

The consequence is especially nasty: the frontend **does not crash**. It reports the session-startup failure as a *problem*,
and then the scan continues and produces **0 findings**. A piece of Kotlin code with an SQL injection looks clean.

The fix is not "upgrade the script engine" (that is a different matter; `kotlin-scripting-jsr223` 2.2.0 is the only
locally available version), but rather **pinning the stdlib version separately once**, in the root POM's
`dependencyManagement`:

```xml
<kotlin.stdlib.version>2.3.21</kotlin.stdlib.version>
```

`dependencyManagement` makes that version **take effect in every module, no matter which path it came in through**,
and thereby bypasses "nearest wins".

> **Corollary — a frontend that "reported a problem but did not crash" is more dangerous than one that crashes.**
> A crash makes the tests go red; reporting a problem makes the scan quietly finish with zero results.
> This is yet another instance of "silent degradation is more dangerous than a false negative": **failures have to be loud.**

### 12.3 The Module Layering: Why an Aggregator POM and Not Subdirectories

Eleven modules laid out flat under one repository root is indeed hard to read. The natural approach is layered subdirectories:

```
plumb-core/    parser, rules, reporter
plumb-engine/  tac, frontend, dfa, style, dsl-kotlin, ai
plumb-app/     main, build
```

**But measurement showed that this path silently breaks the gates, so it was not taken.**

There are 43 gate scripts in the repository that hardcode the module paths with `REPO / "plumb-<name>"`. Worse,
`scripts/check_test_coverage.py:188`:

```python
for module_dir in sorted(REPO.iterdir()):
    if not (module_dir / "pom.xml").is_file():
        continue
```

It **discovers the modules by walking the repository root**. Once the modules move into subdirectories, this gate **does not see a single module**,
and it **does not report an error** — it quietly becomes an empty gate.

> **Corollary — a gate that "stops checking" is more dangerous than a gate that "reports an error".**
> An error makes someone go and fix it; a gate that stops checking makes people believe the check is still running.

So the layering is expressed with **aggregator POMs**, and the directory layout stays flat:

```xml
<!-- pom.xml -->
<modules>
  <module>plumb-core</module>    <!-- ../plumb-parser, ../plumb-rules, ../plumb-reporter -->
  <module>plumb-engine</module>  <!-- ../plumb-tac, ../plumb-frontend, ../plumb-dfa, ... -->
  <module>plumb-app</module>     <!-- ../plumb-main, ../plumb-build -->
</modules>
```

This is the same pattern as the existing `plumb-build` aggregator POM in the repository (the precedent already exists).
**The layering is expressed in the BUILD, and the source tree stays flat and greppable** — a reader who asks "who depends on whom"
gets one answer from the POM, rather than a second answer from the directory nesting.

Maven sorts the reactor by the dependency graph, so **the layer boundaries are already enforced at build time**,
independently of which layer they are declared in.

### 12.4 The Style Checkers Do Not Run on Kotlin/Scala (an explicit trade-off)

The checkers of `plumb-style` are written **against the JavaParser AST**. Kotlin/Scala files have no
JavaParser AST, so the style check **produces no findings at all** on them.

This is a deliberate trade-off, and the reason is: making the style checkers understand three ASTs would mean writing a second style engine,
and **every rule that has no Kotlin/Scala equivalent makes its coverage claim false**.
Data-flow analysis — the part that carries the security semantics — **runs as usual**.

---


### 12.5 Two Kotlin Compilers Cannot Coexist on One Classpath (the final solution)

This section records the hardest problem, and where the boundary of the final solution lies.

**The problem**: two Kotlin stacks run in the repository —

| | Purpose | Version | IntelliJ platform |
|---|---|---|---|
| The DSL rule engine | `plumb-dsl-kotlin`, the Kotlin script engine | 2.2.0 | **shaded**: `org.jetbrains.kotlin.com.intellij.*` |
| The Kotlin source frontend | `plumb-frontend`, Analysis API Standalone | 2.5.0-Beta2 | **unshaded**: `com.intellij.*` |

Both implementation jars **embed a whole Kotlin compiler** (the Analysis API one contains
594 `org.jetbrains.kotlin.cli` classes, 4771 `fir` classes, 302 `org.jetbrains.kotlin.scripting` classes,
and the entire `kotlin/script/**` API).

**The key fact: this is not a version-skew problem, and changing the version cannot fix it.** The compiler classes on both sides
have the **same class names** (`org.jetbrains.kotlin.cli.*`), but they link against **differently shaded** IntelliJ platforms —
2.2.0 uses shaded, 2.5.0-Beta2 uses unshaded. So the same version conflicts just the same.

The failure forms that were measured:

```
NoSuchMethodError: CompilationContextKt.createIsolatedCompilationContext$default(
    ..., org.jetbrains.kotlin.com.intellij.openapi.Disposable, ...)
```

The same method, with the same number of parameters, but with **different parameter types** — the caller's descriptor does not match the callee's.

```
ClassCastException: org.jetbrains.kotlin.idea.KotlinFileType cannot be cast to
    org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
```

The same shaded/unshaded split, seen once more from the angle of the cast.

**Why it is especially dangerous**: the DSL engine records a compilation failure as a **warning** and then carries on.
So **every DSL rule silently stops matching**, while every built-in rule keeps working.
The scan looks healthy, and the user's rules are gone.

**The two dead ends that were walked** (both are recorded, because they both look very reasonable):

1. **Repackaging the Analysis API jar and deleting the duplicate classes.**
   The idea is natural: delete `kotlin/script/**`, `org/jetbrains/kotlin/scripting/**`,
   and let the script engine's own 2.2.0 copy win. **But the shade plugin skips `provided`-scoped
   dependencies by default**, so the "purified" jar was only **3571 bytes** — an empty shell. The Analysis API therefore
   lost `org.jetbrains.kotlin.cli.jvm.compiler.CompatKt`, and the session could not be built at all.
   *The lesson*: the `artifactSet` filtering of `maven-shade-plugin` happens **after dependency resolution**,
   and `provided` dependencies never enter the dependency set being shaded.

2. **Letting the script engine exclusively own `org.jetbrains.kotlin.*` + `kotlin.*`.**
   The result is a **loader constraint violation**:
   `kotlin.coroutines.CoroutineContext` was loaded once by the app loader and once by the script loader.
   *The lesson*: **the standard library has to be unique across the whole JVM.** It is not a "bring it if you use it" thing.

**The final solution: the boundary is drawn on the classloader, and the boundary has to cover both CLASS and RESOURCE.**

The `ScriptEngineClassLoader` of `plumb-dsl-kotlin` (a child loader, child-first) is responsible for:

- **Classes**: exclusive ownership of `org.jetbrains.kotlin.*` (including the compiler, the script compiler plugin, the IDE classes,
  and the `org.jetbrains.kotlin.com.intellij.*` that the embeddable compiler shades in)
  and `kotlin.script.*` (the script API, i.e. where the base class of every compiled rule script lives).
- **Resources**: child-first as well, serving `META-INF/extensions/**` and
  `org/jetbrains/kotlin/**`, `kotlin/script/**`.
- **Shared**: the `kotlin.*` standard library (it has to be unique), `org.plumb.*` (the DSL API),
  and `com.github.javaparser.*` (the AST the rules inspect).

**Why the "resource" half is equally important** — this is the most insidious link in the chain:

The Kotlin compiler's IntelliJ platform finds its own extension points like this:

```kotlin
PathUtil.getResourcePathForClass(CompilerSystemProperties)
```

It resolves `/org/jetbrains/kotlin/cli/common/CompilerSystemProperties.class` as a **resource**.
And `URLClassLoader.getResource` is **parent-first**, exactly like `loadClass`.
So this resource is resolved from the **app classpath**, landing in the **Analysis API's jar** —
and that jar happens to have a `META-INF/extensions/compiler.xml` too, one with **neither an `<id>` nor a
`<name>`**. IntelliJ's plugin loader then throws:

```
RuntimeException: Neither id nor name are specified
```

**The script engine's REPL cannot start**, every DSL rule fails to compile, and the error message does not mention plugins even once.

→ So **`getResource` / `getResources` have to be overridden to be child-first** for the boundary to be complete.
**Getting the class loading right does not mean the resource loading is right** — those are the two halves of the same class of problem.

**The context classloader during evaluation also has to be switched**:

The compiled script classes are defined by the classloader **at the first evaluation**
(`KJvmCompiledScript.getOrCreateActualClassloader`), and it takes the **context classloader of the current thread**.
If only the compilation-time classloader is switched and the evaluation-time one is not, the script classes end up
on the app loader, and then:

```
ClassCastException: Cannot cast org.jetbrains.kotlin.cli.common.repl.AggregatedReplStageState
to org.jetbrains.kotlin.cli.common.repl.AggregatedReplStageState
```

Same name, different loader. The fix is to switch the context classloader at evaluation time as well.

**The remaining half: the order of the application classpath.**

The Analysis API runs on the **app loader**, so the app classpath must have
**the Analysis API's compiler first**. Otherwise the frontend binds to the script engine's 2.2.0 compiler,
the session fails to start — and the frontend reports the failure as a *problem*, and the scan continues and produces 0 findings.
A piece of Kotlin code with an SQL injection looks clean.

This order is a **build fact** and cannot be fixed at runtime, so it was nailed down as a test:
`KotlinStackClasspathOrderTest` of `plumb-main` asserts that

- on the app classpath, `kotlin-analysis-api-implementation` comes **before**
  `kotlin-compiler-embeddable`;
- the `CompatKt` that the **app loader actually resolves** comes from the Analysis API's jar.

> **Corollary — classloader isolation is a set of four things — "class + resource + context classloader + classpath order" —
> and if only one of them is done, the symptom drifts to somewhere completely unrelated.**
>
> Each one on its own can make the tests look like they pass:
> the class loading is right but the resources were not switched → "Neither id nor name";
> the resources are right but the evaluation-time context was not switched → a CCE on AggregatedReplStageState;
> the isolation is right but the classpath order is wrong → the frontend silently produces zero findings.
>
> **Only running the real scan with both stacks in the same JVM exposes all of them at once.**
> That is also why `DslRulesE2ETest` and `MultiLanguageScanE2ETest` have to **both** pass:
> the former proves the DSL engine is alive, the latter proves the Kotlin frontend is alive, and they share one JVM.

**A module that was deleted along the way**: `plumb-kotlin-analysis-api`.

It existed precisely in order to do the "repackaging of the Analysis API". Now that the boundary is drawn correctly on the classloader,
this module is **completely redundant** — and its implementation (shade + the `provided` scope) was broken anyway.
After deleting it, the Analysis API's jar goes onto the classpath as it is, and the conflict is resolved by the classloader
rather than by the contents of the jar.

> **Corollary — when resolving "two jars collide", first ask "where should the boundary be", and only then ask "should the jar be changed".
> Changing the jar moves the problem into the build; drawing the boundary leaves it in the right layer.**

### 12.6 The "Source-Code-Free Consumer" Was Actually Reading Source Code (a problem caught by its own contract)

At the end of the regression run, the consumer tests of `plumb-main` could no longer finish: a single module timed out after more than 30 minutes,
and the 7 cases of `IrAnalysisConsumerTest` took **795 seconds** — while they were analysing
**two lines of code**.

**The conclusion first**: this is not "the DFA engine is slow", the consumer **was reading source code**.

#### The symptom

The thread dump pointed straight at the location:

```
at org.plumb.dfa.resolve.StaticFieldResolver.indexOne(StaticFieldResolver.java:141)
at org.plumb.dfa.resolve.StaticFieldResolver.index(StaticFieldResolver.java:88)
at org.plumb.dfa.engine.DfaEngine.withFunctionSourcePaths(DfaEngine.java:255)
at org.plumb.main.IrAnalysisRunner.analyze(IrAnalysisRunner.java:263)
```

The responsibility of `StaticFieldResolver` is to resolve **cross-file** `public static final` constants
(`IO.STATIC_FIVE` and the like). The way it does that is **by reading the `.java` files in the directory tree**.

#### Two independent defects

**Defect one (correctness): the consumer read source code.**

`DfaEngine.withFunctionSourcePaths(..)` conveniently built a `StaticFieldResolver`.
The name of that method says "register the source file paths", but its actual side effect is "go and read the source code near those paths".

And `IrAnalysisRunner` is a **source-code-free consumer** — its entire meaning is
"produce findings from the IR alone, on a machine with no source code, no classpath and no Java toolchain".
It advertises that contract while simultaneously grabbing `.java` files off the filesystem.

The consequence is not that the findings change, but that **the same IR may give different answers on different machines**:
depending on what happens to be in that machine's directory tree.

**Defect two (cost): that read was unbounded.**

The rule for picking the root directory is "go two levels up from a sample file". JUnit's temporary files live under
`%TEMP%\junit<random>\`, and two levels up from there is `%TEMP%` itself —
where this machine has **48,631 `.java` files**. Every analysis has to read all of them and
regex-scan all of them.

So: 7 cases × 48,631 files = 795 seconds. And the DFA analysis itself takes only **58 milliseconds**.

#### The fix: make "reading source code" explicit and bounded

Not deleting the capability — it is useful: FIELDLOADs such as `Level.WARNING` and `IO.STATIC_FIVE`
need it in order to attach to the real parent node. It is **moving it to the right layer**:

| | Before | After |
|---|---|---|
| `withFunctionSourcePaths(..)` | Registers the paths **+ reads source code** | Only registers the paths (pure metadata) |
| Reading source code | A silent side effect | An explicit opt-in via `withStaticFieldResolverRoot(Path)` |
| The root directory | "Two levels up" | Specified by the caller; the implicit fallback also **has a file-count budget** |

The new `SourcePathResolver.MAX_INDEXED_FILES = 5000`:
the index stops at that budget and says so (`indexTruncated()`), rather than reading a whole tree.
Over budget = "no cross-file constant resolution is offered", **not** "an empty one is offered".

`ScanRunner` (the path **with** source code) opts in explicitly, with the root directory being the parent of the scan input;
`IrAnalysisRunner` (the path **without** source code) does **not** opt in, and says why in a code comment.

#### The result

| | Before | After |
|---|---|---|
| `IrAnalysisConsumerTest` | 795 s | **2.3 s** |
| A single consumer analysis | ~81 s | **< 1 s** |

#### Why this bug was not found before

Because it **does not affect the result**. With the resolver installed and without it, both sides can produce findings,
and the difference only shows up in the shape of the FIELDLOADs. **A bug that does not affect the result is not found by assertions on the result.**

So two assertions were added:

- `StaticFieldResolverBoundaryTest`: a root directory over the budget must be rejected; a small root directory must still work;
  registering paths **must not** install a resolver; an explicit opt-in must still install one.
- `IrAnalysisConsumerTest.consumerReadsNoSource`: put 400 `.java` files next to the IR,
  and assert that the analysis **does not read them** (an upper bound on the time + no resolver).

> **Corollary — a contract boundary that cannot be observed will be eroded.**
>
> Before the fix, **nothing could prove** the contract "the consumer does not read source code".
> Deleting the source files only proves "that particular file is not needed", not "no other file is read".
>
> **A boundary has to be observable in order to hold.** So the `staticFieldResolverForTest()` accessor itself is part of the fix:
> it turns "is the engine reading source code?" from invisible into visible.

> **Corollary — unbounded is a more serious problem than "slow".**
>
> Slow is measurable; unbounded **only fires on some particular machine**.
> This machine's `%TEMP%` happens to have 48,000 files, so the test became a 13-minute test;
> on a clean CI machine it might pass in 3 seconds — and so **the problem does not reproduce on CI**.
> Giving a heuristic like "go up" a **hard budget** is faster than optimising it.

## 13. The TAC Semantic Audit: "Does the Taint Still Arrive?" (2026-11-03)

### 13.1 What the Previous Tests Actually Proved

Everything up to §12 tested the **shape** of the IR: "this construct produced instructions". That is a
necessary condition, and it is not sufficient. A TAC can be perfectly shaped and still **lose the taint**.

The failure mode is invisible: the IR looks right, the CFG looks right, the checker runs — and the finding
never fires, because the data died somewhere between the source and the sink.

So the audit asked a different question:

> **Take a tainted value, push it through this construct, and check whether it still reaches the sink.**

The probe used a **real** sink (`Runtime.getRuntime().exec(...)`), not a mock, so the whole path had to be
genuinely intact — the TAC translation, the DFA propagation, and the sink matching.

**The first measurement was 18 out of 32 constructs preserving taint.** A focused 13-construct subset
started at **1 out of 13**.

### 13.2 The Fourteen Defects That Were Found

Each of these was a place where the TAC was structurally correct but semantically lossy:

| # | Construct | What was lost | Root cause |
|---|---|---|---|
| 1 | Static call | The receiver | The class scope was emitted as a **variable** (`FIELDLOAD this.C.String`) instead of a **type reference** |
| 2 | Static call | The argument | The static callee's arguments start at `args[1]`, but the taint passthrough always looked at `args[0]` |
| 3 | `String.format` / `join` / `formatted` | The argument | Not in the passthrough catalogue at all |
| 4 | Field write then read | The taint | `recordFieldOpForStore` cleared the field taint **unconditionally** — including on the tainted write that had just recorded it |
| 5 | `StringBuilder.append` | The taint | An accumulator is **state**, not an expression edge: the mutator poisons the receiver, the read-back propagates from it |
| 6 | Array initialiser | The elements | The initialiser emitted no `ARRAYSTORE`; the elements never existed |
| 7 | `while` loop | The taint | The analysis order put the **loop exit** before the **loop body**, so the loop body was analysed after the exit had already fired |
| 8 | Labelled `break` / `continue` | The taint | Same ordering problem, one level deeper |
| 9 | Method reference | The taint | A method reference is a **deferred invocation**; nothing rewrote the application point into a real call |
| 10 | Record accessor | The taint | The synthesised accessor returned into the wrong slot (`dest` instead of the TAC convention `lhs1`) |
| 11 | `throw` / `catch` | The taint | The `Throwable` passthrough was matched against a **fixed owner list**, so any subclass was invisible |
| 12 | `foreach` | The taint | The iterator was not modelled on either side: neither `iterator()` nor `next()` |
| 13 | Static field | The taint | A static field belongs to the **class**, not to an instance; the receiver had to be a class identity |
| 14 | Lambda capture | The taint | Captured variables were not turned into **real parameters** of the lambda, so the call site passed nothing |

Plus two smaller repairs found along the way: the tainted array element was not reported when the array
itself was passed to a sink, and the cross-class summary lookup used the **caller's** class instead of the
**callee's**.

### 13.3 The Measurement, Before and After

| | Constructs preserving taint |
|---|---|
| Before the audit | **18 / 32** |
| Focused subset, before | **1 / 13** |
| Focused subset, after | **13 / 13** |
| After the audit | **31 / 32** |

The one remaining non-reporting fixture is `string-switch` — and it is a **correct true negative**:
the discriminator is tainted, but the assigned variable only ever receives the constants `"safe"` and `"b"`.
A tainted switch discriminant **does not** taint the assignment target. The old probe expected a report
here; that expectation was wrong, and it was corrected.

> **Corollary — "every construct must report" is not a valid acceptance criterion.**
>
> The first version of this probe asserted that **every** fixture reports a finding. That assertion makes
> the analysis that reports **everything** pass — including the ones that are wrong. The probe was only
> meaningful once it had **both** a positive case (the taint must arrive) and a negative case (the taint
> must **not** arrive).
>
> This is the same reason the coverage matrix is paired (§11.4): a test that only asserts "it fired"
> cannot tell a working analysis from a broken one that fires too eagerly.

### 13.4 The Fixes That Went Into the TAC and the DFA

The audit changed both layers:

**In `plumb-tac`** (the translation layer):
- `ExpressionTranslator.classScopeFqn(...)` — a static call's class scope is a **type reference**, and it
  emits a type constant plus an FQN callee signature. The phantom `FIELDLOAD this.C.String` is gone.
- `ExpressionTranslator.isStaticCallee(...)` and `passthroughArgIndices(...)` — the shared parameter
  selection, so the **local** propagation and the **summary** recording can no longer drift apart.
- `ExpressionTranslator.storeArrayInitializerElements(...)` — one `ARRAYSTORE` per element.
- `ExpressionTranslator.capturedVariables(...)` — the enclosing locals/parameters a lambda captures.
- `ExpressionTranslator.staticFieldDeclaringClass(...)` / `fieldReceiver(...)` / `classIdentityReceiver(...)`.
- `TacProgram.deferredTargets` + `ExpressionResult.ofDeferred(...)` — the method-reference deferral table.
- `TacTranslator.staticFieldsByClass` / `recordStaticField` / `isStaticFieldOf` — the static-field table.

**In `plumb-dfa`** (the propagation layer):
- `AccumulatorCatalog` (new) — the accumulator mutator / accumulator-read catalogue.
- `ArrayTaintTracker.alias(...)` / `elementTaintOf(...)` — the array element alias semantics.
- `TaintedCheckerBase.propagateAccumulatorTaint(...)` / `propagateIteratorTaint(...)` — the two state-taint
  propagations.
- `TaintedCheckerBase.recordFieldOpForStore(...)` — a **clean** write clears the field taint; a **tainted**
  write records it. These are different semantics and the old code conflated them.
- `TaintedCheckerBase.isThrowableMessageMethod(...)` — matched by **method name**, not by an owner list.
- `TaintedCheckerBase.calleeClassOf(...)` — the cross-class summary lookup uses the callee's class.
- `DfaEngine.analysisOrder(...)` — the loop-aware order: back edges are identified, natural loops are
  computed, and the successor that **leaves** the loop is explored **after** the body.

### 13.5 The Tests That Keep It Honest

Two permanent regressions, both in the surefire allow list:

- `plumb-main/src/test/java/org/plumb/main/TacTaintSurvivalTest` — **26 tests**. 21 positive constructs, the
  static field and the record accessor, plus the three negative / completeness assertions.
- `plumb-tac/src/test/java/org/plumb/tac/translate/StaticCallScopeTest` — **6 tests**. The static class scope
  must not produce a phantom `FIELDLOAD`, the callee signature must be the FQN, and the argument layout of a
  static callee must be `args[0]` = type constant, `args[1..]` = the real arguments.

The temporary probe was moved to `_trash/temp-probe-20261103`; what remains in the tree is the regression.

> **The discipline of this audit — measure the semantics, not the shape.**
>
> The existing coverage matrix asked "does this construct produce the right instructions?". That question
> was answered, and the answer was yes for all 60 constructs — **while 14 of them silently dropped the taint**.
> "It produced instructions" and "the data survived" are different claims, and only the second one is the
> one a vulnerability scanner actually makes.

## 14. Do the JVM Languages Actually Call Each Other? (measured 2026-11-03)

### 14.1 The question behind the question

Java, Kotlin and Scala share a JVM, so they *can* call each other. But "the languages can call each
other" is a claim about the **platform**. The claim that matters for a scanner is narrower and harder:

> If a tainted value flows from a Kotlin caller into a Java sink, does the analyser report it — and
> does it blame the right file?

### 14.2 The measurement, and the answer: not yet

The answer was **no**, for four separate reasons — one per boundary, plus one that is not about a
boundary at all. Each was confirmed by running the real scan, not by reading the code.

| Case | Call graph edges | Taint source of the finding |
|---|---|---|
| **Java → Java** (control) | **2** ✅ | the caller's value |
| **Kotlin → Java** | **0** ❌ | the sink's **own** parameter — the chain was not followed |
| **Scala → Java** | **0** ❌ | the sink's **own** parameter — the chain was not followed |
| **Java → Kotlin** | **0** ❌ | the sink's **own** parameter — the chain was not followed |

The Java→Java control case works, which is what makes the three failures diagnosable rather than
mysterious: the call graph is not broken, the **cross-language edges** are.

> **On the file a finding is attributed to.** Plumb attributes a finding to the **sink's** file —
> that is the convention, and `testcase/01-taint-crossfile/EXPECTATIONS.json` states it outright:
> "Attribution is to the sink, not to the orchestrator method that started the chain, so the file
> must be the sink file for the case to be measurable." The sink is where the dangerous operation
> happens, and that is the line a developer has to fix. So "the finding is on the caller's file"
> was never the right question. The right question is the **taint source**: when the chain was
> followed the source is the caller's value, and when it was not the source is the sink's own
> entry boundary — which is indistinguishable in the output from "taint never crossed the call
> boundary". That distinction is what the fixed tests now assert.

#### Defect 1 — the two signature forms produce different keys

The Kotlin frontend resolves the owner **correctly**: it emits `demo.JavaSink.run/1`. The Java
frontend registers the same function as `run(String):void` with enclosing class `JavaSink`.

The call graph connects a caller to a callee by comparing *bare name + arity*, and the helper that
computes that key treats the two forms differently:

```
bareNameAndArity("run(String):void")     = "run/1"                (class prefix stripped)
bareNameAndArity("demo.JavaSink.run/1")  = "demo.JavaSink.run/1"  (class prefix KEPT)
```

Different strings, so the bare-name map cannot connect them, so the edge is never built. Java→Java
works because **both** sides use the paren form.

> **The generalisable lesson**: a signature format that is "correct" in isolation can still be wrong
> in combination. The Kotlin format was right for *sink matching* (`owner.method/arity` — the format
> the sink families use), and the Java format was right for *Java*. The call graph needs a third
> notion — a key that is identical on both sides — and nobody had defined it.

**Fixed** by `CalleeKeyNormalizer`: one canonical form, `owner.method/arity`, with the owner matched
by **simple name**, used by every frontend and by the call graph itself.

#### Defect 2 — a Scala call to a Java static names the MODULE CLASS

dotc models a Java class's **static** members as members of a synthetic *module class*, whose name
is the class's name with a `$` appended. So the Scala side says `demo.JavaSink$.readAll/1` where
the Java side says `demo.JavaSink.readAll`. Two spellings of one method, and the owner-aware index
missed.

**Fixed** by normalising the module-class suffix **at match time only** — never in the emitted key,
because `Service$` and `Service` are two genuinely different classes in Scala, and merging them
would merge two real, distinct methods. The same normalisation applies in `CalleeKeyNormalizer`,
in the call graph's owner-aware index, and in the engine's callee resolution.

#### Defect 3 — Scala cannot see the Java sources at all

The Scala frontend runs dotc on the single file with a `-classpath` but the Java class exists only
as **source**. A compiler resolves a source class as `<root>/<package>/Class.java`, so a source
path that does not contain that shape misses:

```
[E006] Not Found Error: S.scala:5:14
-- [E006] Not Found Error: S.scala:6:4
[PARSE_PROBLEM] L6: Not found: JavaSink
```

The file does not compile, the frontend reports a parse problem, and the file contributes **no
function at all** to the store.

**Fixed** by staging the project's Java sources into a **package-shaped tree** next to the staged
Scala file, each under the package its own `package` declaration names. That makes the lookup
succeed for a conventional source tree *and* for the flat layout a scanner is often handed — which
the plain `-sourcepath` fix did not.

> **Note the difference in kind.** The Kotlin failure was a *resolution* failure — the symbol was
> there but could not be named. The Scala failure was a *compilation* failure — the file was rejected
> before any analysis happened. They needed different fixes, and a single "make cross-language
> work" task would have hidden that.

#### Defect 4 — the Kotlin frontend REBUILT the file it was analysing

This one was the largest, and it hid behind a plausible explanation.

The Kotlin frontend used to rebuild the file from its text through the PSI file factory, because a
stub-based file can lose the **body** of a declaration: a stub's call expression has no argument
expressions, so every argument was dropped and the taint that flowed through them stopped at the
call.

But a file built by the factory is an **in-memory** file, and the Analysis API classifies such a
file as *dangling*: it is not physical and it has no original file, so **no module claims it**. A
dangling file is analysed in a module with **no dependencies**, and there every reference resolves
to an error type.

So the Kotlin side could not see the Java class at all, could not resolve the callee's **owner**,
and degraded the callee signature to a bare name — and the cross-language edge was never built.
The symptom was "the finding is not attributed to the caller", and the natural reading of that is
"the call graph is missing the edge". The edge *was* missing, but not because of the call graph:
the caller could not **see** the callee.

**Fixed** by using the file as it is. The physical file is in the source module, so it resolves
against the module's dependencies, and its text is its own text, so the body is there.

> **The lesson, stated so it is not lost twice**: a frontend that REBUILDS a file has silently moved
> it out of the project's module structure. "The file's text is right" is not the same as "the file
> is in the project".

#### Defect 5 — the parameter-origin pre-pass ignored the TAC's operand slots

This one is not about a language boundary at all, and it would have silently capped the accuracy of
every cross-language flow.

Every parameter is seeded as a taint source at its function's entry boundary, so a sink method
always reports — with its own parameter as the source. The engine recovers the *real* provenance in
a pre-pass that propagates an origin label forward over the call graph, and the finding reports
that label when it can be recovered.

The pre-pass computed "which operand carries the label" by scanning the instruction's **argument
list**. But the TAC keeps a call's arguments in `args` and every **other** opcode names its
operands in the fixed slots `lhs1` and `lhs2`. A `MOVE` — which is how an assignment is expressed —
carries its source in `lhs1` and has an **empty** argument list.

So a local assignment never inherited a label. A value that reached a sink through a local variable
was invisible to the pre-pass, and the finding fell back to the honest-but-useless `param:<name>`
label of the parameter it was passed as. The taint had crossed the call boundary; the report simply
could not say so.

**Fixed** by scanning the operand slots the TAC actually uses, in the TAC's own reading order.

### 14.3 The trap that this measurement nearly fell into

A scan of the Java sink **alone** already reports CWE-78. The sink's own parameter is treated as
tainted input, so the finding fires with no caller present at all.

So a test that asserted only *"the scan reports CWE-78 for a mixed-language project"* would have
**passed**, while the cross-language capability was completely absent. The finding would have been
attributed to the sink's own file, with the taint source being the sink's own parameter — and the
real caller, the code that actually passes the untrusted value, would not have been implicated
anywhere.

This is why the regression test asserts on the **taint source**, not on the presence of a finding
and not on the file:

- `theTwoSignatureFormsProduceDifferentKeys` — pins the key mismatch itself.
- `theModuleClassSpellingNamesTheSameMethod` — pins the module-class normalisation, and that it
  does **not** merge two genuinely different classes.
- `javaToJavaEdgeIsFormed` — the control, so the test can tell "cross-language is broken" from
  "the call graph is broken".
- The three cross-language cases — each asserts that the taint source is **not** the sink's own
  parameter, which is the difference between a chain that was followed and one that was not.

> **Corollary — "the finding fired" is a claim about the output; "the edge formed" is a claim about
> the capability.** A scanner can produce a correct-looking finding for entirely wrong reasons. The
> only way to pin a *capability* is to assert on the mechanism that implements it.

`CrossLanguageInteropTest` (6 tests) now pins the **fixed** state.

### 14.4 What changed, and where

| # | Fix | Where |
|---|---|---|
| 1 | One canonical callee key across the frontends — the `owner.method/arity` form, normalised the same way on both sides | `CalleeKeyNormalizer` + the call graph's owner-aware index |
| 2 | Normalise the Scala module-class spelling of a Java static, at match time only | `CalleeKeyNormalizer`, `CallGraphBuilder`, `DfaEngine.resolveCallee` |
| 3 | Stage the Java sources into a package-shaped tree, so dotc resolves a source-only Java class | `ScalaTacFrontend.stageJavaSources` |
| 4 | Analyse the Kotlin file as it is, instead of rebuilding it into an in-memory file | `KotlinTacFrontend.findFile` |
| 5 | Scan the TAC's operand slots in the origin pre-pass, so an assignment inherits its source's label | `DfaEngine.labelOfAnyArg` |
| 6 | Give every Kotlin/Scala function a source path, so findings name a file | the frontends + `ScanRunner.mergedSourcePathMap` |

Fixes 1–4 are what make the **edges** form; fix 5 is what makes the **finding** say that the chain
was followed; fix 6 makes the finding actionable.

### 14.5 The rule inputs, and what they actually are

Two external rule sources were examined. Both are usable, and both are **not** what they look like.

#### The rule pack — a machine-readable dataflow model

One commercial static analysis tool ships its taint rules as a
**machine-readable dataflow model** rather than as code or as prose.
The two JVM-language packs together carry a few thousand rules, and they
fall into five kinds:

| Rule kind | What it expresses | Plumb's counterpart |
|---|---|---|
| a dataflow **sink** rule | where tainted data lands | a sink family + a taint rule |
| a dataflow **passthrough** rule | how data crosses a call | a passthrough entry |
| a dataflow **source** rule | where untrusted data enters | a taint source family |
| a dataflow **cleanse** rule | where data is sanitised | a sanitizer |
| a **structural** / **semantic** rule | a pattern, not a dataflow | a pattern rule |

The shape maps almost 1:1 onto Plumb's model. A sink rule is essentially:

> a rule id, a vulnerability category, the **argument** that carries the
> taint, an optional condition on the data's state, and an
> **identifier** — a namespace, a class, and a function — plus a flag
> saying whether the rule also applies to overrides and
> implementations.

Namespace + class + function + argument index is exactly a Plumb sink
family pattern plus an argument index. Two details make the mapping
*good* rather than merely plausible:

- The namespace and the function name are **regex alternations**
  (`java\.util\.zip|org\.apache...`, `getJarEntry|getManifest`), which
  matches Plumb's regex-based family patterns directly.
- The flag saying the rule applies to *overrides* and *implementations*
  is precisely the "owner is the DECLARING class" rule Plumb already
  follows (§12.2).

The gap is measured: **1722 dataflow rules** across the two packs,
marking **47 distinct CWEs**; Plumb covers **16** of them.

#### The model library — executable Java

The other commercial tool ships its library models not as a data file
but as **executable Java**: a set of stub source files whose method
bodies call *model primitives* that name the defect.

```java
public static Connection getConnection(String url, String user, String password) throws SQLException {
    markSensitiveSource(password, SENSITIVE_PASSWORD);
    markHardcodedCredentialSink(password);
    markUnencryptedPasswordSink(password);
    return newOpenDBConnection();
}
```

The primitive vocabulary is large — a few dozen distinct
source/sink primitives, spread over a handful of classes that cover
credentials, cryptography, ORM access and resource handling.

This is a **different kind of input** from a rule pack: a rule pack
gives *rules*, a model library gives *models of what a library method
does*. The mapping is therefore not "rule → rule" but:

> a model stub for `java.sql.DriverManager.getConnection` **is** the
> specification of a sink family with one entry point and one tainted
> parameter.

The stub is also directly **executable by Plumb's own Java frontend**:
it is Java source, it calls methods whose names mark the source and the
sink, and the callee signature is a plain `owner.method/arity`. A
converter that reads a stub and emits a Plumb sink family does not need
a new parser — it needs to recognise the primitive call shape.

#### The distinction that decides the work

Of the 31 CWEs the rule-pack vendor has and Plumb does not, they are **not** 31 missing taint rules. Counting by
the mechanism the rule-pack vendor uses to express them:

| Mechanism | Example CWEs | What it needs |
|---|---|---|
| **Taint** (source → sink) | 79, 80, 89, 22, 73, 78, 99, 918, 566, 497, 15, 95, 94, 90, 91, 261, 256, 170, 730, 601, 117, 502, 134, 470, 639, 489, 215, 692, 87, 83, 82, 113, 643, 191, 190, 93 | a sink family + a source family + a rule |
| **Structural** (a pattern) | 259, 798, 754, 252, 311, 321, 327, 477, 614, 915, 352, 287, 298, 285, 615, 759, 749, 772, 326, 328, 760, 398, 134, 86, 131, 129, 120, 787, 319, 501, 338 | a pattern checker — a **different** mechanism, not a dataflow one |

> **"Migrate the missing 31 CWEs" is the wrong task.** Roughly half of them are pattern rules that
> have nothing to do with the dataflow engine, and several of the taint ones are the *same defect*
> under a different number (CWE-79/80/83/87 are XSS variants; CWE-82/83/87 are one family).
> The right unit of work is a **defect class**, not a CWE id — which is what Plumb's
> `TaintRule.cwes()` already supports (one rule, several numbers).

### 14.6 What this changes about the plan

1. **Fix the cross-language edges before migrating the rules.** A sink family migrated from the rule-pack vendor
   for the Kotlin pack is worthless if a Kotlin call cannot form a call-graph edge — the rule would
   never fire across the boundary it was written for.
2. **The the rule-pack vendor packs are the higher-yield input.** 1722 machine-readable dataflow rules with CWE
   labels, already in Plumb's shape, versus the model-library vendor's 36 model stubs that encode *library
   semantics* rather than rules.
3. **the model-library vendor's value is the library model, not the rule set.** Plumb's sink families currently
   hand-name a few dozen APIs; the model-library vendor's library encodes the same knowledge for a much wider API
   surface, in a form Plumb's Java frontend can already read.
4. **Both inputs need a conversion step, not a copy.** the rule pack's XML → Plumb's YAML; the model-library vendor's
   stubs → Plumb's YAML. Neither is a file copy, and neither needs new IR.

## 15. The Language-Layered Rule Catalog, and What the Rule Packs Actually Say (2026-11-03)

This section records three things that were measured rather than designed:

1. the **shape** of the language dimension in the rule layer, and the consolidation that
   finally made it a single idea;
2. **what the two external inputs actually contain**, after conversion;
3. **which language to add next**, and what each language would cost.

### 15.1 The rule layer, after the consolidation

The rule layer has three levels, and each one answers a different question:

| Level | Question it answers | Where it lives |
|---|---|---|
| The **family** | "What is the defect?" | `SinkFamily` -- one name, one CWE, one severity |
| The **language table** | "Which symbol reaches it in which language?" | `LanguageRuleSet` -- `patterns:` (shared) + `by_language:` (per language) |
| The **rule** | "Is the flow into that family dangerous?" | `TaintRule` -- watches a family, names a CWE |

The **family** is the defect class, and it is the only thing a rule names. The language table is
the per-language symbol table. This was the design all along, but the catalog did not follow it:
a defect class was spread over one family per language.

| Before | After |
|---|---|
| `regex.sinks`, `kotlin-regex.sinks`, `scala-regex.sinks` | `regex.sinks` with `by_language: kotlin:` and `by_language: scala:` |
| `command.sinks`, `kotlin-process.sinks`, `scala-process.sinks` | `command.sinks` with both tables |
| `taint.sources.console`, `taint.sources.kotlin.console`, `taint.sources.scala.console` | `taint.sources.console` with both tables |
| `taint.sources.env`, `taint.sources.kotlin.env`, `taint.sources.scala.env` | `taint.sources.env` with both tables |
| `el.sinks`, `ognl.sinks` | `expression-injection.sinks` |

The last row is the same idea applied to a different axis: the EL engines and the OGNL engines
are different **symbols** for the same **defect** (CWE-94/95/917). So the family grew a symbol
table for the engines rather than becoming two families.

**What the consolidation bought.** The `RegexInjectionChecker` used to name three families and
had to be edited every time a language was added. It now names one:

```java
new TaintRule(
    "dfa-taint-regex-injection",
    "dfa-taint-untrusted-source",
    "regex.sinks",              // one family; the languages live in its table
    "CWE-1333",
    "MEDIUM",
    TaintDomain.StringTaint.INSTANCE);
```

**The direction rule still holds.** A language table says "this symbol is reachable in this
language". It never says "this language is safe". A language with no entry gets the shared
patterns, which is the correct and conservative answer.

### 15.2 Three defects in the conversion, found by measuring the output

The converters were written to translate the rule pack's XML and the model-library vendor's stubs into Plumb's YAML.
Translating them is mechanical, and mechanical translation has failure modes that a
"does the known sink still match?" test cannot see. So the converted output was measured.

**The catch-all.** One extended rule pack carries three rules whose whole identifier is
`.*`:

```xml
<Label>MyBatis Mapper SQL Injection Sink</Label>
<identifier>
  <namespace><Pattern>.*</Pattern></namespace>
  <class><Pattern>.*</Pattern></class>
  <function><Pattern>.*</Pattern></function>
  <FunctionLabel>MyBatisQueryFuncWithSQLi</FunctionLabel>
</identifier>
```

These rules are scoped by a the label, not by a symbol: the MyBatis SQL lives in an
annotation on the mapper method, so the symbol itself carries no information. Translated
literally, one of them became `^.*\..*\..*(/\d+)?` -- which matches **every** fully qualified
call site in the analysed program. The SQL family would have reported every call in the
codebase.

The converter now refuses a wildcard identifier and reports it instead. 18 rules are refused
this way.

**The unescaped dot.** The rule pack's namespace field is sometimes already a regex
(`java\.util\.zip`) and sometimes a plain package name (`javax.script`). A plain package name
carried into a regex unescaped turns every dot into "any character":

```
^javax.script\.ScriptEngine\.eval     also matches   javaxXscriptXScriptEngine.eval
```

The pattern still matches the intended symbol, so the bug is invisible in a smoke test and
shows up only as cross-family contamination. 1400 patterns were affected.

**The unescaped `$`.** The rule pack writes the nested class with a literal dollar sign in some rules and
with `Response(\$|\.)ResponseBuilder` in others. The first form is **dead**: `$` is an end-of-line
anchor, so `^javax\.ws\.rs\.core\.Response$ResponseBuilder\.cookie` can never match anything.
A rule that can never fire is worse than no rule -- it looks like coverage while reporting
nothing.

**The consequence for the merge.** These three defects were only visible because the merge was
**non-idempotent**: the second run read the hand-written families back out of the merged file,
which no longer had them (the merge had moved them into a managed region), and rebuilt them from
the converter's version alone. That silently dropped `default_arg_index` -- so `ldap.sinks` lost
`default_arg_index: 1`, and every LDAP finding pointed at the base DN instead of the filter.

The merge now reads the hand-written families from a **pristine base file**
(`sink-families-base.yaml`) that is never written to. The merge is a pure function of
(base + fragments): running it twice produces the same file, and a converter fix takes effect at
once.

**What the measurement now shows.** For a symbol that has nothing to do with any sink:

```
com.example.Whatever.someMethod/3   ->  0 families
```

Before the fix it was one -- the SQL family's catch-all.

### 15.3 What the rule packs actually contain

The the rule-pack vendor JVM packs, measured:

| Pack | Sink rules | Defect classes | Distinct symbols | Language-native symbols | CWEs |
|---|---|---|---|---|---|
| one extended rule pack | 3650 | 97 | 969 | 422 | 61 |
| `core_java.xml` | 597 | 47 | 112 | 8 | 46 |
| `core_android.xml` | 477 | 40 | 102 | 99 | 37 |
| `core_scala.xml` | 201 | 32 | 83 | 83 | 31 |
| `core_kotlin.xml` | 47 | 18 | 7 | 7 | 20 |

Two facts in that table decide the whole plan.

**First: the Java pack is almost entirely shared.** `core_java.xml` has 104 of its 112 symbols
in the Java library -- `java.io`, `java.lang`, `java.sql`, `javax.servlet`. Those are on the JVM
for Kotlin and Scala too, so the shared patterns already cover them. Only **8** symbols are
Java-specific.

**Second: the Kotlin pack is almost entirely native, and tiny.** All 7 of its symbols are Kotlin
stdlib: `kotlin.io` (readLine), `kotlin.text` (Regex), `kotlin.io.path`, `kotlin.random`,
`kotlin.text`, `kotlin.String`, `kotlin`. There are **zero** shared Java-library symbols in it,
and it introduces **no** defect class Java does not already have. In other words: **the Kotlin
pack adds seven symbols to families that already exist.** It is a table, not a rule set.

**Third: the Scala pack is native too, but mostly third-party.** All 83 of its symbols are
Scala-ecosystem, but only about 30 are the Scala standard library (`scala.sys.process`,
`scala.util.matching`, `scala.io`, `scala.xml`, `scala.reflect.io`). The rest are **frameworks**:
Akka HTTP (30 symbols), Play (14), Slick, better-files, Twirl views. So the Scala pack's value
is mostly *framework* coverage, which is a per-application question, not a language question.

The the model-library vendor model library is, by construction, **Java-ecosystem only**: 36 Java stub files under
`java/`, `android/`, `org/`, `javax/`, `com/`, and one `play/` stub. There is **no** Kotlin,
Groovy, Clojure, Python, Ruby or JavaScript stub in it. So the model-library vendor contributes library
semantics for the JVM, and nothing for any other language.

### 14.4 What the conversion produced

| | the rule-pack vendor | the model-library vendor |
|---|---|---|
| Input | 4 JVM packs (core+extended Java, core Kotlin, core Scala) | 36 Java model stubs |
| Sink rules / call sites | 4495 | 146 |
| Source rules / call sites | 901 | 15 |
| Defect classes mapped | 25 -> **37** | 13 |
| Families merged | 25 | 13 |

The the rule-pack vendor conversion went from 25 families to **37** once the extended pack was folded in and
the unmapped defect classes were mapped. The largest wins:

| Family | Before | After |
|---|---|---|
| `ldap.sinks` | 5 | 69 |
| `xss.sinks` | 136 | 177 |
| `trust-boundary.sinks` | 0 | 39 |
| `expression-injection.sinks` | 0 | 30 |
| `resource-injection.sinks` | 156 | 173 |
| `header-injection.sinks` | 39 | 48 |
| `weak-crypto.sinks` | 88 | 93 |

Twelve new defect classes needed a **rule**, not just a family: server-side template injection
(CWE-1336), HTTP parameter pollution (CWE-235), dangerous file inclusion (CWE-98), NoSQL
injection (CWE-943), JSON injection (CWE-91), XQuery injection (CWE-943), XSLT injection
(CWE-643), XML injection (CWE-91), plus the per-language tables for the existing ones. A family
with no rule is a family that can never fire.

### 15.5 Which language next, and what it costs

The question the user asked -- "which languages are worth adding, and which need the least
dataflow work" -- has a sharp answer once the packs are measured.

**The cost of a language is NOT the number of rules. It is the number of NATIVE symbols, and
whether the defect classes are new.**

| Language | Native symbols | New defect classes | New families needed | New rules needed | DFA changes |
|---|---|---|---|---|---|
| **Kotlin** | 7 | **0** | 0 | 0 | none -- 7 table entries |
| **Scala** | 83 (30 stdlib) | 6 | 0 (all map to existing families) | 0 | none -- 83 table entries |
| **Groovy** | 0 (no pack) | 0 | 0 | 0 | a frontend, for a language the model-library vendor does not support either |
| **C / C++** | 596 | many | many | many | a different engine (memory model, no GC) |
| **Rust** | 0 (no pack) | 0 | 0 | 0 | a different engine (ownership, lifetimes) |
| **Python** | 461 | many | many | many | dynamic dispatch, no static types |
| **Go** | 507 | many | many | many | goroutines, channels |

The conclusion the measurement supports:

1. **Kotlin and Scala were the right first two, and they were cheap** -- exactly because the
   JVM languages share the Java library. The dataflow engine needed no language-specific
   change; the *table* did.

2. **The next JVM language is Groovy, and it is not worth it.** It has no the rule-pack vendor pack at all,
   the model-library vendor does not support it, and its only native symbol is one console source
   (`groovy.lang.GroovyShell.evaluate`). The cost would be a whole frontend for one rule.

3. **For a language whose rules are mostly native (Go, Python, PHP, Swift, .NET), the dataflow
   engine has to change, not just the table.** Those packs name their own standard library
   (Go: 89 of 90 symbols native; Python: 131 of 132; PHP: 75 of 76). Each of those symbols is a
   *new* API with its own argument roles, and a language whose standard library is not the JVM's
   has no shared patterns to inherit. That is the actual cost: not the rule count, but the fact
   that there is no shared baseline.

4. **C, C++ and Rust are out of scope for a different reason than the others.** Their dataflow
   is not a taint walk over a heap: it is memory safety, lifetimes, and aliasing. the rule-pack vendor's
   `core_cpp.xml` has 596 sink rules but only **7** distinct symbols -- the packs for those
   languages are not taint catalogues in the sense the JVM packs are.

**The practical rule this gives.** For a language on the JVM, the marginal cost of adding it is
the size of its **native symbol table**, not the size of its rule pack. The first two languages
cost 7 and 83 table entries and zero engine changes. A fourth JVM language with a 500-symbol
stdlib would cost 500 table entries and still zero engine changes -- but it would also need a
frontend, which is where the real cost is.

### 15.6 What still has to happen before a new language

1. **The cross-language call-graph edges.** The measurement in section 14 showed that Kotlin ->
   Java and Scala -> Java produce **zero** call-graph edges, so a sink migrated for the Kotlin
   pack can never fire across the boundary it was written for. Three root causes, all
   identified: the canonical callee key differs between the Java and Kotlin/Scala forms, the
   Scala frontend is not given `-sourcepath`, and the Kotlin frontend does not carry a source
   path for a top-level function.
2. **A language table entry for every native symbol**, and a test that asserts the entry is
   reachable in that language and in no other.
3. **A rule for every family**, including the ones the conversion created.

### 15.7 C#: what the evaluation found

C# was evaluated on the same footing as the other languages: what it would cost, and where the
cost actually is. The answer is unusually clean, and it is not the answer the question was
probably expecting.

**The route.** C# source cannot be parsed by a JVM-hosted parser that also resolves types. The
only option that yields a semantic model is Microsoft's open-source compiler, and it runs on
.NET. So the frontend is a **sidecar process**: a small .NET program that parses the sources,
resolves the symbols, and writes TAC-shaped instructions over stdio. The JVM side reads them
through the same interface the other frontends implement.

The two alternatives were measured and rejected on facts rather than preference:

- **Running the .NET compiler in-process on the JVM** would need a Java-to-.NET translation
  layer. The available one translates in the opposite direction, so it cannot help.
- **A grammar-only parser** (the C# grammar of the tree-sitter family is maintained) parses
  well but resolves nothing: no types, no overload resolution, no nullable-reference
  information. A taint engine lives or dies on exactly those, so this route would deliver a
  front end that cannot drive the rules.

**The IR already covers it.** This is the part that makes C# cheaper than its reputation
suggests. The three constructs that would normally force new opcodes -- `async`/`await`,
pattern matching, and nullable reference types -- all have somewhere to go:

| C# construct | Where it goes in the IR | Why it fits |
|---|---|---|
| `async` / `await` | `IrPrecision.SOURCE_LOWERED`, and the task's own function | A suspended method is a function whose body resumes elsewhere; the precedent is Kotlin's `suspend`. |
| Pattern matching | A branch chain | The precedent is Scala's `match` and Kotlin's `when`. |
| Nullable reference types | `TacType`'s nullability | The IR already models a type's nullability, so an annotated reference type is a value the existing lattice can carry. |
| An expression the model cannot represent | `HAVOC` | The IR already has an opcode for "a value we cannot describe", and it demands that the front end report the gap rather than pretend. |

So **a first viable C# release needs no new opcode and no new IR class**. That is the same
property the JVM languages turned out to have, and it is the reason the multi-language design
was worth building.

**The dominant work item is the sink catalogue, not the parser.** The catalogue matches a
callee's signature against per-family patterns, and it is currently a catalogue of JVM APIs.
C# has its own standard library, so every family needs its own `by_language` table: the
`System.Data.SqlClient` command, the ASP.NET request readers, the JSON and XML readers, the
process and file APIs. The `by_language` mechanism already exists and needs no change; the
content does not exist at all.

**The one thing that has to be measured before anything else.** C# exposes a lot of its API as
**properties**, and a property assignment is not a call in the source:

```csharp
cmd.CommandText = tainted;   // the classic C# SQL injection
```

If the front end lowers a property assignment to a field write, then the sink catalogue --
which matches call signatures -- is **structurally blind** to it, and the finding cannot be
reported no matter how complete the catalogue is. A property assignment must therefore lower
to a call. This is a one-day question with a one-day answer, and it should be answered before
any catalogue work starts: it decides whether the catalogue is a list of patterns or a list of
patterns plus a property list.

**What C# would cost, against the other languages.**

| | Kotlin | Scala | C# |
|---|---|---|---|
| New IR opcodes | 0 | 0 | 0 (expected; measure before committing) |
| Engine changes | none | none | none (expected) |
| Native symbols to catalogue | 7 | 83 | the .NET standard library: hundreds |
| Front end | Analysis API, in-process | compiler plugin, in-process | a .NET sidecar process |
| New defect classes | 0 | 6 | many (the .NET standard library is a different API surface) |

The last row is the one that decides the order. Kotlin was cheap because it shared the Java
library; Scala was cheap for the same reason; **C# does not share it**, so C# is the first
language in this plan whose cost is dominated by a catalogue rather than by a table.

## Appendix A: The File Structure That Actually Landed

**The layering is expressed in the BUILD, and the source tree stays flat.** The three aggregator POMs (`plumb-core` /
`plumb-engine` / `plumb-app`) declare the layers, while the module directories stay under the repository root — the reason is in §12.3
(43 gate scripts hardcode the module paths, and `check_test_coverage.py` discovers the modules by walking the root directory).

```
plumb-core/pom.xml            # aggregates: parser, rules, reporter
plumb-engine/pom.xml          # aggregates: tac, frontend, dfa, style, dsl-kotlin, ai
plumb-app/pom.xml             # aggregates: main, build

plumb-tac/
├── ir/                        # the unified IR contract (the extension surface: 2 fields + 2 enums + 1 opcode + 1 dimension)
│   ├── TacFunction.java       #   + sourceLanguage, irPrecision, synthetic, annotationValues
│   ├── TacInstruction.java    #   + NULL_CHECK
│   ├── TacType.java           #   + nullable (defaults to true) + displayName()
│   ├── TacOperand.java        #   the TacVar identity = name + isTemp
│   ├── SourceLanguage.java    # [new] JAVA / KOTLIN / SCALA / GROOVY / UNKNOWN
│   ├── IrPrecision.java       # [new] SOURCE_FULL / SOURCE_LOWERED / BYTECODE / ...
│   ├── TacProgram.java        #   + FunctionProblem (the structured problem channel)
│   └── MergedProgram.java
├── frontend/                  # the language frontend contract (language-agnostic)
│   ├── TacFrontend.java       #   + StructuredParseResult (the native shape of the structured problems)
│   ├── CompileContext.java    #   the session-scoped compile context
│   ├── SymbolTable.java       #   the cross-language symbol table
│   ├── DefaultCompileContext.java
│   └── java/
│       └── JavaTacFrontend.java   # the reference implementation
├── translate/                 # Java AST → TAC
│   ├── TacBuilder.java        #   the language-neutral TAC builder (shared by the three languages)
│   ├── TacTranslator.java
│   ├── StatementTranslator.java
│   ├── ExpressionTranslator.java
│   ├── TypeMapper.java
│   └── JavaModernFeatures.java  # switch expr / pattern / record / the compact constructor
└── bytecode/jimple/           # [existing] Jimple -> TAC (the .jar analysis channel, unchanged)

plumb-frontend/                # [new] the non-Java frontends (Kotlin + Scala, isolated from plumb-tac)
├── pom.xml                    #   isolates the kotlin / scala3 compiler dependencies
├── docs/KOTLIN-ANALYSIS-API-NOTES.md
└── src/main/java/org/plumb/tac/frontend/
    ├── kotlin/                 #   Analysis API Standalone
    │   ├── KotlinTacFrontend.java
    │   ├── KotlinTacTranslator.java    #   owner.method/arity
    │   ├── KotlinTypeMapper.java       #   non-null / nullable / platform type
    │   ├── KotlinModuleStructure.java
    │   └── KotlinDirectoryScope.java
    └── scala/                  #   dotc PluginPhase
        ├── ScalaTacFrontend.java
        ├── ScalaTacPhase.java
        ├── ScalaTacTranslator.java     #   owner.method/arity
        ├── ScalaTypeMapper.java
        ├── PlumbScalaPlugin.java
        └── PlumbScalaContext.java

plumb-parser/
├── JavaSourceParser.java      # [changed] the language level chain (J1): no longer hardcodes JAVA_17
├── JavaLanguageLevel.java     # [changed] "8".."25" / "1.8" / "latest" -> the named levels
└── JavaModule.java            # [changed] languageLevel (String)

plumb-dfa/
├── config/
│   ├── SinkFamily.java        #   + languages
│   ├── SinkFamilyRegistry.java#   + language-aware lookup
│   └── ConfigLoader.java      #   parses the YAML languages:
├── taint/
│   ├── TaintRule.java         #   + languages
│   ├── NullabilityFacts.java  # [new] the Kotlin non-null facts
│   └── FieldOpClassifier.java
├── engine/
│   ├── DfaChecker.java        #   + supports(TacFunction)
│   └── DfaEngine.java         #   the language gate on both the analysis loop and the summary loop
└── checkers/taint/
    ├── NullDerefChecker.java  #   + the nullable decision + NULL_CHECK
    ├── RegexInjectionChecker.java   # [new] CWE-1333
    └── FilePermissionChecker.java   # [new] CWE-732

plumb-main/
├── ScanRunner.java            # [changed] dispatches to the TacFrontend by extension
├── FrontendRegistry.java      # [new] the extension -> frontend mapping (lazily loaded)
└── IrAnalysisRunner.java      # [new] the source-code-free consumer entry point

plumb-dsl-kotlin/              # [changed] the classloader boundary of the script engine (§12.5)
└── src/main/kotlin/org/plumb/dsl/kotlin/
    ├── ScriptEngineClassLoader.kt   # the child loader: both the classes and the resources are child-first
    └── ScriptEngineHolder.kt        # switches the context classloader at both the compile time and the evaluation time
```

**The deleted module**: `plumb-kotlin-analysis-api`. It existed in order to do "the repackaging of the Analysis API jar",
but that path does not work (shade skips `provided` dependencies and produces a 3.5KB empty shell),
and it is **not needed at all** — the correct solution to the conflict is a classloader boundary, not a change to the contents of the jar.
The reasoning is in §12.5.

## Appendix B: References

**the model-library vendor**
- Static Analysis Language and Framework Support (the official language list — the JVM languages are only Java/Kotlin/Scala)
  `blackduck.com/static-analysis-tools-sast/languages-and-frameworks.html`
- the model-library vendor on Polaris 2026.6.0 Release Notes (the complete Kotlin/Scala checker list)
  `docs.blackduck.com/r/cov_polaris/latest/coverity-on-polaris/coverity-on-polaris-2026.6.0-release-notes.html`
- the model-library vendor 2024.9.x: Supported Platforms, Languages, and Compilers (the Kotlin compiler version support)
  `documentation.blackduck.com/.../topics/r_coverity-compatible-platforms_2024.9.html`
- the model-library vendor 2026.3.0 Release Notes (the removal of the SpotBugs/Detekt support, the Kotlin quality checkers disabled by default)

**Kotlin**
- The official Analysis API documentation (KaSession / KaSymbol / KaType / the Standalone mode's limitation statement)
  `github.com/Kotlin/analysis-api/blob/main/Writerside/topics/Index.md`
- StandaloneAnalysisAPISessionBuilder (the service registration and module structure of the Standalone mode)
  `github.com/JetBrains/kotlin/tree/master/analysis/analysis-api-standalone`
- An overview of the Backend IR (IrElement / IrModuleFragment / lowering / serialization)
  `github.com/JetBrains/kotlin/tree/master/compiler/ir`
- Kotlin 2.0+ invokedynamic for lambdas
- **The release process / the version support window** (2.4.21 = the current latest, the 2.4 line is supported until 2027-12-03)
  `kotlinlang.org/docs/releases.html`

**Scala**
- Changes in Compiler Plugins (`PluginPhase` / `StandardPlugin` / `runsAfter` / `runsBefore` / `plugin.properties`)
  `scala-lang.org/api/3.8.4/docs/changed-features/compiler-plugins.html`
- The `MiniPhase` API (the full set of `transformXXX` hooks / fused traversal)
  `dotty.tools.dotc.transform.MegaPhase.MiniPhase`
- The TASTy overview (`docs.scala-lang.org/scala3/guides/tasty-overview.html`)
- **All Available Versions** (3.10.0 = the latest; 3.9.0 = LTS; 3.8.0/3.8.1 have regressions and are unusable)
  `scala-lang.org/download/all.html`
- **The Scala 3.8 release notes** (JDK 17 becomes a hard requirement; the standard library is now compiled with Scala 3)
  `scala-lang.org/news/3.8`
- **The Scala 3.9 LTS release notes** (`into` is stable; the LTS guarantees at least three years of maintenance)
  `scala-lang.org/news/3.9`

**Java / JavaParser**
- The `ParserConfiguration.LanguageLevel` enum of **JavaParser 3.26.4** — the ceiling is `JAVA_21` + `BLEEDING_EDGE`
  `github.com/javaparser/javaparser`
- **JDK 25 is the current LTS** (the local runtime is `openjdk 25.0.1`)

**Other**
- Detekt / ktlint / diktat (the Kotlin PSI ecosystem)
- Scalafix / WartRemover / scala3-tasty-inspector
- Groovy AST Transformations (9-phase compile-time metaprogramming)
- SemanticDB (a language-agnostic metadata format)
- The SpotBugs/FindBugs false-positive problem on Kotlin (a data class `componentN` being misjudged as dead code)
