# C# in Plumb: How Two Commercial Tools Produce IR, and What the TAC Must Change

[English](../README.md) | [中文](../zh/tech/CSHARP-FRONTEND-ANALYSIS.md)

This document answers three questions in order:

1. **How do Coverity and Fortify actually get C# into an IR?** Not "they support
   C#" — the concrete pipeline, stage by stage, from the evidence.
2. **What does that imply for Plumb?** A route decision, with the alternatives
   measured rather than preferred.
3. **What must the TAC change** so a C# frontend can be written against it?

The third question is the load-bearing one. The first two decide *where* the
C# frontend lives; the third decides *what it is allowed to emit*. A frontend
written against the wrong TAC contract produces a scan that reports success and
finds nothing — the failure mode this project has been bitten by repeatedly.

Everything marked **[MEASURED]** was read out of this repository's code. Everything
marked **[EXTERNAL]** comes from vendor documentation or a public write-up, and
is cited.

---

## 0. The conclusion first

| Question | Answer |
| --- | --- |
| How does Coverity get C# into IR? | Build capture (`cov-build`) over a real MSBuild/`dotnet build`, then a **platform-independent IR** built from the *source*, with CFG + call graph + symbol table + types. C# support **relies on a .NET runtime shipped inside the analysis tool** ([EXTERNAL]). |
| How does Fortify get C# into IR? | Two stages historically: C# → debug DLL/EXE → `ildasm` → **MSIL** → Fortify's NST. Since 16.20 the translator can take **.NET source directly**. The analyser itself runs in a **JVM** ([EXTERNAL]). |
| What does that mean for Plumb? | The two commercial tools sit at opposite ends of the axis: **Coverity = source-level + tool-hosted .NET runtime**; **Fortify = binary-level + external JVM + IL as the pivot**. Plumb already owns the source-level end and already owns a JVM. Neither vendor's exact route is reusable; the *hybrid* is. |
| The route for Plumb | **A .NET sidecar process** that runs Roslyn, emits TAC over a length-prefixed stdio protocol, and the JVM reads it. |
| Does the TAC need new opcodes? | **No new opcode. But three IR contract changes**, and one is load-bearing: **a C# property assignment must lower to a `CALL`, not a `FIELDSTORE`.** |

The last row is the one that decides whether C# can produce findings at all, and
it is the item the multi-language design doc left open as "a one-day question
with a one-day answer" ([MULTI-LANGUAGE-IR-DESIGN.md §15.7](../MULTI-LANGUAGE-IR-DESIGN.md)).
Section 4 answers it against the code.

---

## 1. What the two commercial tools actually do

### 1.1 Coverity: source-level IR, with a .NET runtime in the box

Coverity's pipeline is build-capture-first:

```
cov-build --dir cov-int <real build command>      # capture the build
        │
        ▼
   intermediate directory (cov-int)                # translation units, compiler
        │                                           # options, dependencies
        ▼
cov-analyze --dir cov-int                          # analysis over the model
```

Three facts from the vendor documentation matter for Plumb:

**(a) The IR is platform-independent and built from source.** The vendor
describes stage two as: "Based on the captured compilation instructions,
Coverity re-parses each source file and generates a platform-independent
intermediate representation (IR). This IR not only contains the syntax tree, but
also integrates: control flow graph (CFG), call graph, symbol table and type
information, and memory access patterns" ([EXTERNAL], vendor technical
description). So Coverity's IR is **not** the compiler's own bytecode — it is a
*re-derived* model built from the source text, with the captured build
providing the context (which files, which references, which flags).

**(b) C# is a first-class source language in that pipeline, and it needs a
.NET runtime.** The build-options table states it plainly:

> **C#, Visual Basic** — "C# support relies upon the **.NET 10 run time that ships
> with Coverity Analysis**, and any Linux platform that Coverity Analysis
> build-capture is run on must support .NET 10. See .NET build capture; also see
> 'C# compilers' in the Coverity 2026.3.2 Installation and Upgrade Guide."
> ([EXTERNAL], vendor capture documentation)

This is the single most important external fact in this document. Coverity does
**not** analyse C# by reading IL out of a DLL. It analyses C# by **driving the
real C# compiler inside its own distribution**, and then re-deriving an IR from
the source.

**(c) Build capture is mandatory, and it is MSBuild-shaped.** C#, VB and the
other compiled languages are in the "use build capture" column of the same
table; only the interpreted languages get the "capture without a build command"
shortcut. The coverity analysis for a C# project therefore requires a working
`dotnet build` / MSBuild, and the "C# compilers" section of the installation
guide is where the compiler identities are configured — the same role
`cov-configure` plays for GCC and Clang.

**What this means for Plumb.** Coverity's shape is: *source + captured build
context → own IR*. That is exactly the shape of Plumb's Java/Kotlin/Scala
frontends. The difference is only in **where the compiler runs**: Coverity ships
a .NET runtime; Plumb runs on a JVM.

### 1.2 Fortify: binary-level first, source-level since 16.20

Fortify's pipeline is the classic translate-then-scan split:

```
sourceanalyzer -b <build-id> msbuild /t:rebuild Sample.sln   # translation
sourceanalyzer -b <build-id> -scan -f results.fpr            # analysis
```

The translation step writes an intermediate model; the analysis step reads it
and applies the rulepacks. Fortify's own user guide describes the historical
.NET path precisely:

> "SCA works on the **Common Intermediate Language (CIL)**, and therefore
> supports all of the .NET languages that compile to CIL, including C# and VB
> .NET." … "Fortify SCA `sourceanalyzer` is looking for the **DLL's (and
> corresponding PDB files)** so it does not pickup the source files for
> scanning." ([EXTERNAL], vendor user guide)

And the mechanism, from the same era of documentation:

> "Your source code is translated into an intermediate model … Some types of code
> require multiple stages of translation. For instance, **a C# file needs first
> to be compiled into a debug .DLL or .EXE, and then that .NET binary file
> disassembled into Microsoft Intermediate Language (MSIL) by the .NET SDK
> utility `ildasm.exe`.** Whereas other files like a Java file or ASP file are
> translated in one pass by the appropriate Fortify SCA translator."
> ([EXTERNAL], vendor community thread, corroborated by the vendor user guide)

Two additions changed this:

- **Since 16.20, the translator takes .NET source directly.** "Starting with
  Fortify 16.20, SCA now supports scanning .Net C#/ASP/VB source code directly -
  no longer requiring pre-compilation." ([EXTERNAL], same thread)
- **The analyser still runs on a JVM**, even for .NET. The Visual Studio
  extension guide states: "Note: Fortify SCA runs scans in a **Java Virtual
  Machine (JVM)**." ([EXTERNAL], vendor user guide)

**What this means for Plumb.** Fortify's historical route (C# → IL) is the
route Plumb explicitly rejected for its own JVM languages, and §1 of the
multi-language design doc explains why: IL loses the source structure (nullable
reference annotations, property semantics, pattern-matching shape) that a
taint rule needs. Fortify's *current* route (direct .NET source) converges on
Coverity's route — but Fortify does it by shipping a .NET-capable translator
inside a JVM-hosted product, which is precisely the shape that is awkward for
Plumb.

### 1.3 The axis, stated plainly

| | **Coverity** | **Fortify** |
| --- | --- | --- |
| Entry point | Build capture of a real build | Build capture, or direct source list |
| C# model source | **Source text**, re-parsed by the tool | **MSIL** (historically), **source** (since 16.20) |
| Where the C# compiler runs | Inside the tool (shipped .NET runtime) | Outside the tool (your `dotnet build`), or the tool's own translator |
| Analyser runtime | Native analysis engine | **JVM** |
| Cross-language story | Separate language-specific checkers over one shared engine | One NST, many analysers (data flow / control flow / semantic / structural / configuration) |

Two observations follow.

**Observation 1 — the two tools are not competitors on this axis; they are
opposite ends of it.** Coverity proves that a *source-level* C# model is
achievable inside a non-.NET product, at the cost of shipping a .NET runtime.
Fortify proves that a *JVM-hosted* product can analyse C#, at the cost of
either an IL detour or a .NET-capable translator on the side.

**Observation 2 — for Plumb, the interesting cell is the one neither tool
occupies natively.** Plumb *is* a JVM product that wants *source-level* C#
semantics. Fortify's JVM-hosted-with-.NET-sidecar shape is the cell Plumb should
occupy; Coverity's shape requires Plumb to become a .NET product, which it must
not.

---

## 2. The route decision for Plumb

Four routes were considered. Two were rejected on facts, two on cost.

### 2.1 Rejected: run the .NET compiler in-process on the JVM

Not possible. The available Java-to-.NET translation layer translates in the
*opposite* direction, so it cannot help ([MULTI-LANGUAGE-IR-DESIGN.md §15.7](../MULTI-LANGUAGE-IR-DESIGN.md),
which reached the same conclusion independently).

### 2.2 Rejected: a grammar-only parser (tree-sitter C#, or similar)

Rejected because it resolves nothing. No types, no overload resolution, no
nullable-reference information. A taint engine lives or dies on exactly those:
the sink catalogue matches on a **callee signature** and a **receiver type**, and
the null-dereference checker reads a **nullability** dimension off the type
([MEASURED]: `SinkFamily.matches(String, SourceLanguage)` matches on the callee
signature and the receiver type; `TacType.nullable` is the nullability
dimension). A grammar-only frontend cannot produce either, so it would deliver a
frontend that parses C# perfectly and drives **zero** rules.

The multi-language design doc reached the same conclusion for C#
([MULTI-LANGUAGE-IR-DESIGN.md](../MULTI-LANGUAGE-IR-DESIGN.md) §15.7).

### 2.3 Rejected: IL / bytecode channel

Rejected on the same grounds the JVM languages were rejected: IL discards
exactly the facts the rules need, and the multi-language design doc's
[§1](../MULTI-LANGUAGE-IR-DESIGN.md) already establishes this for Java bytecode.

There is one genuine advantage worth naming: an IL channel needs **no .NET
toolchain at all** — no SDK, no Roslyn, no source path. For a user with only
binaries, it is the only route that works. That is a real option for a
*later* phase, but it is not a first release: it cannot produce the
property-assignment sink (section 4), and it cannot produce nullable-reference
facts.

### 2.4 Chosen: a .NET sidecar process over a stdio protocol

```
┌─ JVM (Plumb) ──────────────────┐        ┌─ .NET sidecar ──────────────┐
│  CSharpTacFrontend             │        │  Roslyn CSharpCompilation    │
│    ├ spawn sidecar (long-lived) │◀──────▶│    ├ SyntaxTree (per file)   │
│    ├ write: {file, refs, ...}   │ stdio  │    ├ SemanticModel            │
│    ├ read:  TAC instruction     │        │    └ emit TAC lines          │
│    └ map into TacFunction       │        │                              │
└─────────────────────────────────┘        └──────────────────────────────┘
```

The sidecar:

- runs on .NET (the .NET SDK is a hard requirement, exactly as Coverity made it
  a hard requirement for itself);
- uses **Roslyn** — the open-source compiler platform — for parsing *and* for
  the semantic model (types, overload resolution, nullable annotations,
  `partial`/generated code, `#if` regions);
- is **long-lived for the whole scan**, not per file: a Roslyn
  `CSharpCompilation` over a whole project resolves far better than one
  compilation per file, and a per-file frontend would reproduce exactly the
  cross-language call-graph failure documented in
  [§14](../MULTI-LANGUAGE-IR-DESIGN.md) of the multi-language design doc;
- emits **TAC-shaped instructions**, not a separate IR, so the TAC stays the
  only contract between a frontend and the engine.

**Why a sidecar rather than in-process.** Roslyn is .NET. Plumb is JVM. The
bridge is a process boundary; the only question is what crosses it. Crossing it
with *TAC* rather than with an AST keeps the boundary narrow: the sidecar owns
C# semantics, and the TAC — which is already language-neutral by construction —
carries the result.

**Why not the Fortify route (IL).** Because IL cannot express a property
assignment as a call, and the sink catalogue is call-based (section 4). It also
cannot express nullable-reference types. Fortify could afford that because its
.NET rules were designed around the .NET binary model; Plumb's rules are shared
with Java, Kotlin and Scala, and the C# rules must join the *same* catalogue.

### 2.5 The route summary

| Route | Verdict | Reason |
| --- | --- | --- |
| .NET compiler in-process on the JVM | Impossible | No Java→.NET translation available in that direction |
| Grammar-only parser | Rejected | Resolves no types → drives no rules |
| IL / bytecode | Rejected for v1 | Loses property semantics and nullable annotations |
| **.NET sidecar (Roslyn) emitting TAC** | **Chosen** | Source-level semantics, narrow boundary, TAC stays the contract |

---

## 3. What C# costs, in Plumb's own units

The multi-language design doc
[§15.5](../MULTI-LANGUAGE-IR-DESIGN.md) established the cost model: **the cost of
a language is the size of its native symbol table, not the size of its rule
pack.** Kotlin cost 7 table entries and 0 engine changes; Scala cost 83 and 0.

C# breaks the pattern that made Kotlin and Scala cheap, and the reason is
structural:

| | Kotlin | Scala | **C#** |
| --- | --- | --- | --- |
| New IR opcodes | 0 | 0 | **0** |
| Engine changes | none | none | **none** |
| Native symbols to catalogue | 7 | 83 | **hundreds** (the .NET standard library) |
| Frontend | Analysis API, in-process | compiler plugin, in-process | **a .NET sidecar process** |
| New defect classes | 0 | 6 | **many** |
| **Shares the Java library?** | yes | yes | **no** |

Kotlin and Scala were cheap **because they share the Java library**: their
standard-library symbols are recognisable by the same patterns, so the work was
table entries and nothing else. **C# does not share it.** The .NET standard
library is a different API surface with a different shape, and every family
needs its own `by_language` table.

That mechanism already exists and needs no change. `sink-families.yaml` already
carries a `by_language:` block per family, and `LanguageFilter.narrowFamily`
already narrows the per-language pattern table to the selected languages
([MEASURED]). What does not exist is the **content**: the .NET symbols.

The size of that content is the real budget. It is also the part that is
mechanically derivable rather than creative, which matters for a plan: the
table is a list of .NET API shapes, and .NET's API shape is documented.

---

## 4. The TAC adjustments for C#

This is the section that decides whether the C# frontend can produce findings.

### 4.1 The load-bearing finding: property assignment must be a `CALL`

The multi-language design doc
[§15.7](../MULTI-LANGUAGE-IR-DESIGN.md) flagged this as the thing to measure
first:

> "If the front end lowers a property assignment to a field write, then the sink
> catalogue — which matches call signatures — is **structurally blind** to it,
> and the finding cannot be reported no matter how complete the catalogue is."

**Measured against this repository's code, the answer is: yes, it must be a
`CALL`.**

The chain of evidence, each link read out of the code:

1. **The sink catalogue matches callee signatures.** `SinkFamilyRegistry.lookup`
   and `.lookupAll` call `f.matches(calleeSignature)`, and
   `SinkFamily.matches(String, SourceLanguage)` returns false when the
   signature does not match one of the family's patterns ([MEASURED]:
   `plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamily.java:661`,
   `SinkFamilyRegistry.java:88`).
2. **The engine only consults the catalogue on `CALL` instructions.**
   `DfaEngine`'s instruction walk special-cases `ins.kind == TacInstruction.Kind.CALL`
   for the summary application and the side-effect consumer, and the taint
   checkers receive the instruction and match on its `calleeSig`
   ([MEASURED]: `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:1976`).
3. **A `FIELDSTORE` carries a field name, not a callee signature.**
   `TacInstruction` gives `FIELDSTORE` a `fieldName` and a `dest`, and no
   `calleeSig` ([MEASURED]:
   `plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java:173`).

So a C# frontend that lowers

```csharp
cmd.CommandText = tainted;   // the classic C# SQL injection
```

to a `FIELDSTORE` produces a TAC in which **the sink does not exist**. The
catalogue cannot see it, the engine never asks about it, and the scan reports
success while finding nothing. Not a precision loss — a missing capability,
invisible in the output.

**Therefore the C# frontend must lower a property assignment to a `CALL`.**

The shape it should take, and why:

```
// cmd.CommandText = tainted;
CALL  System.Data.SqlClient.SqlCommand::set_CommandText/1
      receiver = cmd
      args    = [tainted]
```

Note the method name: **`set_CommandText`**, not `CommandText`. This matters,
because it determines the shape of the .NET sink catalogue:

- The catalogue entry is `^System\.Data\.SqlClient\.SqlCommand\.set_CommandText(/\d+)?`.
- The `arg_index` is `0` — the value being assigned — which the existing
  `by_language` mechanism already supports per-pattern ([MEASURED]:
  `sink-families.yaml:63-73`, and `SinkFamily.argIndexesFor(String, SourceLanguage)`).

**The generalisation, stated as a rule the C# frontend must follow:**

> A C# **property** assignment is a `CALL` to the property's setter.
> A C# **field** assignment is a `FIELDSTORE`.

This is not a hack. It is what the C# language actually says: a property is a
*pair of methods* (`get_X` / `set_X`), and a property assignment *is* a call to
`set_X`. The compiler emits it that way. Lowering it to a field write would be
the frontend **disagreeing with the language**, and it is the disagreement that
would be invisible.

This also gives C# something the JVM languages do not have: a single, uniform
place where "assignment to a member" becomes "call to a method", which means
the .NET sink catalogue does not need a second matching mechanism (a
property-aware matcher) alongside the existing call matcher.

**A second, smaller instance of the same rule.** `using` / `IDisposable` and
`await` have the same shape of problem — they are calls in the language, so
they should be calls in the TAC. This is not new work; it is the same rule
applied to different constructs, and it is listed in §4.5.

### 4.2 No new opcode is needed — the four constructs have homes

| C# construct | Where it goes in the IR | Why it fits |
| --- | --- | --- |
| `async` / `await` | `IrPrecision.SOURCE_LOWERED` + the task's own function | A suspended method is a function whose body resumes elsewhere; the precedent is Kotlin's `suspend`. |
| Pattern matching (`switch` expressions, `is` patterns) | A branch chain | The precedent is Scala's `match` and Kotlin's `when`. |
| Nullable reference types (`string?` vs `string`) | `TacType`'s nullability dimension | `TacType` already has a `nullable` flag, `nonNull()`/`nonNullArray()` factories, and the null-dereference checker already reads it ([MEASURED]: `plumb-tac/.../ir/TacType.java:113`). |
| An expression the model cannot represent | `HAVOC` | The opcode exists, is language-neutral, and **demands** that the frontend report the gap rather than pretend ([MEASURED]: `TacInstruction.Kind.HAVOC`). |
| A call the engine cannot see inside | `UNINTERPRETED` | The opcode exists, is language-neutral, and carries a defined default treatment ([MEASURED]: `TacInstruction.Kind.UNINTERPRETED`). |

`NULL_CHECK` covers the C# null-forgiving operator `!` and nullable-flow
narrowing: `x!` asserts non-null, which is exactly what the opcode means
([MEASURED]: `TacInstruction.Kind.NULL_CHECK`).

So: **a first viable C# release needs no new opcode and no new IR class.**

### 4.3 The three IR contract changes that ARE needed

New opcodes: none. But three *contract* changes are needed, and one of them is
load-bearing.

#### (1) `SourceLanguage` gains `CSHARP` — and it must be appended

`SourceLanguage` currently has `JAVA, KOTLIN, SCALA, GROOVY, UNKNOWN`
([MEASURED]: `plumb-tac/.../ir/SourceLanguage.java:45`).

**`CSHARP` must be added at the END of the enum, before `UNKNOWN`… and
`UNKNOWN` must stay last.** The reason is the disk cache: the enum is
serialised **by name**, so a rename or a reorder invalidates every cached
program ([MEASURED]: the comment at `SourceLanguage.java:24-26`, and the
contract test `MultiLanguageIrContractTest.SourceLanguageTest.enumConstantsAreStable`
asserts the exact order). The test must be updated to include `CSHARP`, and it
must assert the *new* order — a test that only checks "contains" would not catch
a future reorder.

`SourceLanguage.parse` must accept `csharp`, `cs`, and (following the existing
convention of `jvm-` prefixes) `dotnet-csharp` / `dotnet-cs`.

`hasNonNullTypeSystem()` — currently `true` only for `KOTLIN` ([MEASURED]) —
**must stay `false` for C#**, and this is a subtle point worth stating.

C# *does* have a nullable reference type system (`#nullable enable`, `string?`
vs `string`). So why is the answer still `false`?

Because of what the flag means. Its javadoc says: *"Whether this language has a
compile-time non-null type system. Kotlin's `String` (as opposed to `String?`)
guarantees non-nullness **at the declaration site**."* And: *"A `true` answer
means 'the compiler rejects a null literal for this type', which is a fact about
the **declaration**. It says nothing about whether a particular runtime value is
null."*

C#'s nullable reference types are **warnings, not errors**. The C# compiler
emits a *warning* (CS8600-series), not an error, for a null assignment to a
non-nullable reference. A program with `#nullable disable` — the default for
pre-C#8 projects — has no nullability information at all. So a C# `string` is
**not** guaranteed non-null the way a Kotlin `String` is.

**Therefore: `hasNonNullTypeSystem()` stays `false` for C#, and the C#
frontend must NOT emit non-nullable types for a plain `string`.** The
nullability that C# *does* provide is available through the per-value
`NULL_CHECK` opcode and through the frontend's own fact set — but the
*declaration-site* guarantee does not hold, so it must not be used to suppress a
null-dereference finding.

This is exactly the "directional rule" the IR already states: a language
dimension may **suppress** a finding or **select** a rule, but must never
**prove** code is safe.

`supportsFullDataflow()` must return `true` for C# — it is statically typed.

#### (2) `TacType` needs two new primitive kinds: `DECIMAL` and `VOID`/value semantics

**[MEASURED]** `TacType.Kind` currently has: `BOOLEAN, BYTE, SHORT, CHAR, INT,
LONG, FLOAT, DOUBLE, STRING, CLASS, ARRAY, NULL, VOID, UNKNOWN`.

C# has three type-system facts the IR cannot currently express:

| C# fact | Why the current IR cannot express it | Required change |
| --- | --- | --- |
| `decimal` is a **value type** with 28–29 significant digits | There is no `DECIMAL` kind, so `decimal` would map to `DOUBLE` or `UNKNOWN` — and the numeric-range analysis (`checkSlt`) would reason about the wrong domain | Add `Kind.DECIMAL` |
| `int`/`long`/`short`/`byte` are **value types** (copied on assignment) | `TacType` has no value/reference distinction at all — `isReference()` returns true only for `STRING, CLASS, ARRAY`, and `isPrimitive()` covers only the eight numeric/boolean kinds | Add a `valueType` flag, or a `Kind.VALUE` umbrella |
| `string` is a **reference type** but is **immutable** and has `==` **by value** | `TacType.STRING` exists and is a reference; the IR has no notion of value-equality on a reference type | Language-specific handling in the equality operator, not an IR change |

**The value/reference distinction is the one that matters for dataflow**, and it
is the one the IR does not have. In Java, `int` and `Integer` differ; in C#, a
`struct` assignment copies. For a taint engine the consequence is concrete:
**aliasing**. A C# `struct` field write does not alias a caller's copy, whereas
a Java object field write does. If the TAC says `FIELDSTORE` for both, the field
taint tracker will carry taint across a copy that C#'s semantics say did not
happen.

**Recommended minimal change:** add a `boolean valueType` flag to `TacType`
(defaulting to `true` for the eight primitives and `false` for `STRING`, `CLASS`
and `ARRAY`), plus a `Kind.DECIMAL`. Both are **additive** — field additions
are backwards compatible for the serialisation contract, and enum constants
added at the end are safe.

The `valueType` flag must be **excluded from `TacVar` identity**, exactly as
the type already is ([MEASURED]: `TacOperand.TacVar.equals` compares only
`name` and `isTemp`). A refined type must not change a variable's identity, or
the taint recorded under the old key is silently dropped.

#### (3) `TacOperand.Const` needs `DECIMAL` and a `BYTE[]`-like literal form

**[MEASURED]** `TacOperand.Const.Kind` has: `INT, LONG, FLOAT, DOUBLE, BOOL,
CHAR, STRING, NULL`.

A C# literal needs `decimal` (`1.5m`), and — more importantly — C#'s
`string` interpolation and `verbatim` strings are fine, but C# also has
`char` literals with escape sequences (fine) and **`$@"..."` interpolated
verbatim strings** (fine, still a string).

The real gap is `decimal`. A `decimal` literal must not be silently widened to
`double` — the numeric-range analysis would then reason about a value that
cannot exist (`decimal` has no `NaN`/`Infinity`).

`Const.Kind` must add `DECIMAL` at the end of the enum (the same
backwards-compatibility rule applies). The `value` field is an `Object`, so it
holds a `BigDecimal` without a schema change.

### 4.4 The one place where the engine — not the TAC — changes

**Nothing in the DFA engine changes.** That is the point of the multi-language
design, and it holds here. But one *behaviour* is worth stating because it is
where C# findings will differ from Java findings:

**C# has no checked exceptions.** In Java, `TaintedCheckerBase`'s exception
propagation (`exceptionPropagator`) reasons about which checked exceptions a
call declares, and a `catch` handler's `ctorType` decides whether it catches
([MEASURED]: `TacInstruction.Kind.CATCH_HANDLER` carries `ctorType`; the
multi-language design doc [§4.4](../MULTI-LANGUAGE-IR-DESIGN.md) describes the
Kotlin null-safety mechanism that builds on it).

For C#, **every** exception is unchecked, and a `catch` may catch anything. So
the C# frontend should emit `CATCH_HANDLER` with the **declared** catch type
(or `System.Exception` for a bare `catch`), and the engine's existing
"is the thrown type a subtype of the caught type" logic applies with C#'s
rules: a `catch (Exception)` catches everything, a `catch (IOException)` does
not catch a `System.InvalidOperationException`.

This needs **no engine change** — the subtype relation is already expressed
through `TacType`. It needs a **frontend discipline**: emit the declared type
faithfully, and do not invent a narrower one.

### 4.5 The full list of TAC/IR changes

| # | Change | Kind | Blocking? |
| --- | --- | --- | --- |
| 1 | `SourceLanguage.CSHARP`, appended before `UNKNOWN`; `parse` accepts `csharp`/`cs`/`dotnet-csharp` | enum + method | yes |
| 2 | `hasNonNullTypeSystem()` stays **false** for C# | behaviour | yes (a wrong `true` silently suppresses findings) |
| 3 | `supportsFullDataflow()` returns **true** for C# | behaviour | yes |
| 4 | **Property assignment → `CALL` to `set_X`** | frontend rule | yes |
| 5 | `TacType.Kind.DECIMAL` | enum | yes |
| 6 | `TacType.valueType` flag (excluded from `TacVar` identity) | field | yes |
| 7 | `TacOperand.Const.Kind.DECIMAL` | enum | yes |
| 8 | `CalleeKeyNormalizer` — C#'s `owner.method/arity` form already fits | none | no |
| 9 | `IrPrecision.SOURCE_LOWERED` for `async`/`await` bodies | existing | no |
| 10 | `NULL_CHECK` for `x!` and nullable flow narrowing | existing | no |
| 11 | `HAVOC` for unrepresentable expressions | existing | no |
| 12 | `UNINTERPRETED` for calls the engine cannot model | existing | no |
| 13 | `CATCH_HANDLER` carries the **declared** C# catch type | existing | no |
| 14 | `SourceLanguage` order test updated to include `CSHARP` | test | yes |
| 15 | A `by_language` table for every family, with .NET symbols | data | yes (for findings) |

### 4.6 Why the callee-key contract already fits C#

**[MEASURED]** `CalleeKeyNormalizer.canonicalCalleeKey` accepts:

- `run(String):void` (the Java paren form),
- `run/1` (the bare slash form),
- `demo.JavaSink.run/1` (the qualified slash form),
- `demo.JavaSink.run(String):void` (the qualified paren form),
- and a Jimple-style `G#use` form.

The canonical form is `owner.method/arity`, and owners are compared by **simple
name**, with a special case that strips a trailing `$` (Scala's module-class
suffix) when comparing two owners.

C#'s natural shape — `System.Data.SqlClient.SqlCommand.set_CommandText/1` —
is **already the qualified slash form**, and needs **no change at all**.

Two details worth stating because they will bite otherwise:

- **Overloads are distinguished by arity, not by parameter types.** C# overloads
  can differ by type at the *same* arity (`Foo(int)` vs `Foo(string)`). The
  canonical key does not distinguish them, so two overloads share a key. This is
  the same limitation the JVM languages have, and it is acceptable for a first
  release: it affects which *sink* is matched, and the sink catalogue is
  type-driven anyway (a `SqlCommand.set_CommandText` pattern does not collide
  with another overload of the same name at the same arity in any realistic
  .NET sink family).
- **The `$` suffix rule is already there, and C# does not need it.** C# has no
  module-class equivalent, so no new suffix rule is required. This is a case
  where the *absence* of a change is a finding.

---

## 5. The .NET sink catalogue: the real work item

### 5.1 The shape

The mechanism already exists. A family with a .NET-specific symbol looks like
this ([MEASURED], `sink-families.yaml:59-85`):

```yaml
  taint.sources.console:
    patterns:
      - '^java\.io\.Console\.readLine(/\d+)?'
      - '^java\.lang\.System\.console(/\d+)?'
    by_language:
      kotlin:
        - pattern: '^kotlin\.io\.readLine(/\d+)?'
          arg_index: 0
      scala:
        - pattern: '^scala\.io\.StdIn\$\$anon\$1\.readLine(/\d+)?'
          arg_index: 0
```

A C# entry slots into the same block:

```yaml
      csharp:
        - pattern: '^System\.Console\.ReadLine(/\d+)?'
          arg_index: 0
```

**The `arg_index` is where the C# property rule pays off.** For
`cmd.CommandText = tainted`, the sink is the *setter call*, and the tainted
value is argument 0 of `set_CommandText/1` — so `arg_index: 0`.

### 5.2 The families that need .NET content

The families that already exist for Java and have a .NET counterpart:

```
taint.sources.console    System.Console.ReadLine
                         System.Console.In.ReadLine
taint.sources.env        System.Environment.GetEnvironmentVariable
taint.sources.network    System.Net.WebClient.DownloadString
                         System.Net.Http.HttpClient (response)
taint.sources.request    HttpRequest.QueryString        (ASP.NET)
                         HttpRequest.Form
                         HttpRequest.Params
                         HttpRequest.Cookies
sql.sinks                System.Data.SqlClient.SqlCommand.set_CommandText
                         System.Data.SqlClient.SqlCommand.ExecuteNonQuery
                         System.Data.SqlClient.SqlCommand.ExecuteReader
                         System.Data.SqlClient.SqlCommand.ExecuteScalar
cmd.sinks                System.Diagnostics.Process.Start
                         System.Diagnostics.ProcessStartInfo.set_FileName
                         System.Diagnostics.ProcessStartInfo.set_Arguments
path.sinks               System.IO.File.*
                         System.IO.Directory.*
                         System.IO.Path.*
xss.sinks                System.Web.UI.HtmlTextWriter
                         Response.Write                (ASP.NET)
                         System.Web.UI.Literal.Text
deserialization.sinks    System.Text.Json.JsonSerializer.Deserialize
                         BinaryFormatter.Deserialize
ldap.sinks               System.DirectoryServices.*
crypto.sinks             System.Security.Cryptography.*
weak-crypto.sinks        System.Security.Cryptography.*
xxe.sinks                System.Xml.XmlDocument.Load
                         System.Xml.XmlReaderSettings
log.sinks                System.Diagnostics.Trace.WriteLine
                         ILogger.Log
```

The **complete** list is larger than this; the point is that it is a
**mechanical enumeration of the .NET standard library**, not a research
project. The .NET API surface is documented, and the families are already
defined.

### 5.3 The property-setter consequence, spelled out

Every .NET sink that is reached through a property needs a `set_X` pattern.
This roughly **doubles** the .NET sink table relative to a naive
method-only count, and it is the single most important structural fact about
the C# catalogue.

The families most affected: `sql.sinks` (CommandText, CommandType,
ConnectionString), `cmd.sinks` (FileName, Arguments), `path.sinks` (some
overloads are properties), `xss.sinks` (Text, Value).

### 5.4 The measurement that must come first

Before writing catalogue content, one measurement decides the shape of the work:

> **Does the C# frontend emit `set_CommandText/1` as a callee signature, and does
> the sink catalogue match it?**

This is the one-day question from
[§15.7](../MULTI-LANGUAGE-IR-DESIGN.md) of the multi-language design doc,
restated as a measurement. It has two possible answers and they lead to
different plans:

- **If yes** (the property rule works): the catalogue is a list of method
  patterns plus setter patterns. One matching mechanism. Clean.
- **If no**: the catalogue needs a second, property-aware matching mechanism
  that matches on `(receiverType, propertyName)` instead of a callee
  signature. That is a change to `SinkFamily` and `SinkFamilyRegistry`, and it
  is **much** more expensive.

**The measurement should be the first thing built**, because the answer changes
the size of the second thing.

---

## 6. The plan

### Phase 0 — the measurement (do this first)

Build the smallest possible C# path: a sidecar that parses **one** file, emits
TAC, and a test that asserts:

1. `cmd.CommandText = tainted;` produces a `CALL` whose `calleeSig` is
   `...SqlCommand.set_CommandText/1`;
2. that `calleeSig` matches a `by_language` pattern with `arg_index: 0`;
3. `await` produces `SOURCE_LOWERED` and a task function;
4. `x!` produces a `NULL_CHECK`;
5. a `catch (IOException)` produces a `CATCH_HANDLER` with
   `ctorType = System.IO.IOException`.

**Gate:** if (1) or (2) fails, stop and fix the property rule before writing
any catalogue content.

### Phase 1 — the IR contract changes

Items 1–7 and 14 of §4.5. These are small, additive, and each one has a
contract test:

- `SourceLanguage.CSHARP` + the updated order test;
- `TacType.Kind.DECIMAL` + `valueType`, with a test that the flag is excluded
  from `TacVar` identity;
- `TacOperand.Const.Kind.DECIMAL`.

**Gate:** `MultiLanguageIrContractTest` passes with the new constants in the
new order, and the existing Java/Kotlin/Scala findings are **unchanged** (the
nullability default stays `true` for every existing factory).

### Phase 2 — the sidecar

- The Roslyn sidecar: parse → semantic model → TAC emission.
- Long-lived for the whole scan (project-level `CSharpCompilation`), so that
  cross-file references resolve.
- The protocol: length-prefixed frames over stdio, one request per file, one
  response per file, plus a `FunctionProblem` channel for structured problems
  ([MEASURED]: `TacFrontend.StructuredParseResult` already defines this
  contract).
- **Total**: the frontend must not throw on malformed input. A C# file that
  does not compile yields the compiler's diagnostics as problems, and whatever
  could be parsed.

### Phase 3 — the catalogue

The .NET `by_language` tables for every family that has a .NET counterpart
(§5.2), with the `set_X` setter patterns (§5.3).

**Gate:** a .NET sink table exists for every family that has a .NET symbol, and
a test asserts each entry is reachable in C# and in no other language — the
same gate the multi-language design doc
[§15.6](../MULTI-LANGUAGE-IR-DESIGN.md) specifies.

### Phase 4 — the rules and the acceptance criteria

The acceptance criteria in the multi-language design doc
[§6](../MULTI-LANGUAGE-IR-DESIGN.md) apply unchanged, and the one that matters
most for C# is the **false-negative** side: a C# analogue of every Java finding
class must be detectable.

**The C#-specific acceptance test** is the one worth writing first, and it is
the analogue of the Juliet sweep used for Java:

| C# construct | Expected finding |
| --- | --- |
| `cmd.CommandText = request.QueryString["id"]` | SQL injection (CWE-89) |
| `Process.Start("cmd.exe", userInput)` | Command injection (CWE-78) |
| `File.ReadAllText(Path.Combine(root, userInput))` | Path traversal (CWE-22) |
| `Response.Write(userInput)` | Reflected XSS (CWE-79) |
| `new SqlCommand(sql)` with a concatenated string | SQL injection |

The first row is the one that proves the property rule.

---

## 7. The risks, and what mitigates each

| Risk | Consequence | Mitigation |
| --- | --- | --- |
| Property assignment lowered to `FIELDSTORE` | The C# SQL-injection sink class is **structurally invisible** | The `CALL` rule (§4.1) + the Phase 0 gate |
| `hasNonNullTypeSystem()` set to `true` for C# | Null-dereference findings silently suppressed — a **false negative** | C#'s nullable annotations are warnings, not errors; keep it `false` (§4.3.1) |
| A per-file sidecar | Cross-file references do not resolve → cross-language call graph produces zero edges (the [§14](../MULTI-LANGUAGE-IR-DESIGN.md) failure) | One long-lived sidecar per scan, project-level compilation |
| The `SourceLanguage` enum reordered | Every cached IR program is invalidated | Append only; the contract test pins the order |
| `valueType` included in `TacVar` identity | Taint silently dropped on a refined variable | Mirror the existing rule: identity is `name` + `isTemp` only |
| C#'s `decimal` mapped to `double` | The numeric-range analysis reasons about a domain that cannot exist | `Kind.DECIMAL` as a distinct kind |
| The .NET catalogue is written but the sink families do not fire | The scan reports success and finds nothing | The Phase 4 acceptance test, per construct |
| The `.NET` SDK is a hard dependency | C# cannot be scanned on a machine without it | Same position as Coverity ([EXTERNAL]); report the missing toolchain as a **problem**, not as "no findings" |

The last row deserves emphasis. **A missing toolchain must be a loud failure.**
A scan that analyses nothing and reports nothing is indistinguishable from a
clean project — which is exactly the failure mode the `LanguageFilter.parse`
javadoc already calls out for an unknown language name: *"a typo in `--language
kotlin` would otherwise produce a scan that analyses nothing and reports
nothing, which is indistinguishable from a clean project"* ([MEASURED]:
`plumb-dfa/.../config/LanguageFilter.java:91-94`).

The C# frontend must follow the same discipline: if the sidecar cannot start,
the scan reports that it could not analyse C#, and exits non-zero.

---

## 8. What this document does not claim

- **It does not claim C# is worth adding on cost grounds alone.** §3 shows the
  catalogue is hundreds of entries, not 7 or 83. That is a real budget.
- **It does not claim the engine changes are zero.** The *engine* changes are
  zero; the *IR contract* changes are three, and one of them (the property
  rule) decides whether the whole thing works.
- **It does not claim the property rule is implemented.** §4.1 answers the
  question the multi-language design doc left open, from the code; the
  implementation is Phase 0.
- **It does not claim the .NET catalogue is complete.** §5.2 is a sketch of the
  families that need content, not the content itself.
- **It does not claim the external facts are current beyond their sources.**
  The vendor statements are cited inline; a reader should check the vendor
  documentation for the release they actually install.

---

## Appendix A: The evidence, collected

**[MEASURED]** — read out of this repository:

| Fact | Location |
| --- | --- |
| `SourceLanguage` has `JAVA, KOTLIN, SCALA, GROOVY, UNKNOWN`, serialised by name | `plumb-tac/src/main/java/org/plumb/tac/ir/SourceLanguage.java:45` |
| `hasNonNullTypeSystem()` is Kotlin-only | `SourceLanguage.java:81` |
| `supportsFullDataflow()` is Java/Kotlin/Scala | `SourceLanguage.java:86` |
| The IR contract test pins the enum order | `plumb-tac/src/test/java/org/plumb/tac/ir/MultiLanguageIrContractTest.java:99` |
| `TacType` has a `nullable` flag; every existing factory is nullable | `plumb-tac/src/main/java/org/plumb/tac/ir/TacType.java:113` |
| `TacType.Kind` has 14 kinds, no `DECIMAL` | `plumb-tac/src/main/java/org/plumb/tac/ir/TacType.java:40` |
| `TacType` has no value/reference distinction | `TacType.java:197-209` |
| `TacVar` identity is `name` + `isTemp`, type excluded | `plumb-tac/src/main/java/org/plumb/tac/ir/TacOperand.java:95` |
| `Const.Kind` has no `DECIMAL` | `plumb-tac/src/main/java/org/plumb/tac/ir/TacOperand.java:109` |
| `TacInstruction.Kind` includes `NULL_CHECK`, `HAVOC`, `UNINTERPRETED`, `CATCH_HANDLER` | `plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java:44-153` |
| `FIELDSTORE` carries `fieldName`, not `calleeSig` | `plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java:173` |
| The canonical callee key is `owner.method/arity`; the `$` suffix rule exists | `plumb-tac/src/main/java/org/plumb/tac/ir/CalleeKeyNormalizer.java:45` |
| The sink catalogue matches on the callee signature | `plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamily.java:661` |
| The registry looks families up by callee signature | `plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamilyRegistry.java:88` |
| `by_language` carries per-language patterns with `arg_index` | `plumb-dfa/src/main/resources/sink-families.yaml:59-85` |
| `LanguageFilter` narrows the per-language table | `plumb-dfa/src/main/java/org/plumb/dfa/config/LanguageFilter.java:190` |
| An unknown language is an error, not a silent skip | `plumb-dfa/src/main/java/org/plumb/dfa/config/LanguageFilter.java:100` |
| The engine's instruction walk special-cases `CALL` | `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:1976` |
| Cross-function edges come from `CALL` instructions | `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:1215` |
| `TacFrontend` is the one extension point; no language-specific opcodes allowed | `plumb-tac/src/main/java/org/plumb/tac/frontend/TacFrontend.java` |
| The structured-problem channel exists | `plumb-tac/src/main/java/org/plumb/tac/frontend/TacFrontend.java` (`StructuredParseResult`) |
| The Scala frontend runs the compiler in-process via a plugin phase | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacFrontend.java` |

**[EXTERNAL]** — vendor documentation and public write-ups:

| Fact | Source |
| --- | --- |
| Coverity's IR is platform-independent, built from source, with CFG + call graph + symbol table + types | Vendor technical description of the four analysis stages |
| "C# support relies upon the .NET 10 run time that ships with Coverity Analysis" | Vendor capture documentation, "Build options by language" |
| C#, VB are in the "use build capture" column; the interpreted languages get the no-build shortcut | Same table |
| Coverity's build capture wraps a real build (`cov-build`), analysis reads the intermediate directory | Vendor user documentation |
| "SCA works on the Common Intermediate Language (CIL) … including C# and VB .NET" | Vendor user guide, "Translating .NET Source Code" |
| "a C# file needs first to be compiled into a debug .DLL or .EXE, and then that .NET binary file disassembled into Microsoft Intermediate Language (MSIL) by the .NET SDK utility `ildasm.exe`" | Vendor community thread, corroborated by the user guide |
| "Starting with Fortify 16.20, SCA now supports scanning .Net C#/ASP/VB source code directly" | Same thread |
| "Fortify SCA runs scans in a Java Virtual Machine (JVM)" | Vendor user guide, Visual Studio extension |
| `sourceanalyzer -b <id> msbuild /t:rebuild Sample.sln` is the .NET translation command | Vendor user guide, "Visual Studio and MSBuild Project Translation Command-Line Syntax" |
| A `.dll` without a matching `.pdb` is ignored | Vendor user guide |
| The JVM-to-.NET translation layer runs in the opposite direction | [MULTI-LANGUAGE-IR-DESIGN.md](../MULTI-LANGUAGE-IR-DESIGN.md) §15.7 |
