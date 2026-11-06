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
| Where does the .NET sink catalogue come from? | **Converted, not hand-written.** 2519 .NET sink rules in the packs, 2491 converted (98.9 %), 66 of 72 defect classes mapped, **1284 C# patterns across 36 families**. See [§5.5](#55-the-catalogue-is-converted-not-hand-written--and-what-that-cost). |
| How does the other tool's checker list compare? | **37 of its 49 C# security checkers are covered.** The 7 that were not are closed — five by reading the vendor's non-dataflow rules, one (`RESOURCE_LEAK`) by extending a *checker* that had never been applied to C#, and one (`TAINT_ASSERT`) is a rule about the analyser and has no counterpart in the program. See [§5.6](#56-the-comparative-review-what-the-other-tools-checker-list-exposes). |

The last row is the one that decides whether C# can produce findings at all, and
it is the item the multi-language design doc left open as "a one-day question
with a one-day answer" ([MULTI-LANGUAGE-IR-DESIGN.md §15.7](../MULTI-LANGUAGE-IR-DESIGN.md)).
Section 4 answers it against the code.

**That question has now been MEASURED, and the answer is yes.** A C# property
assignment lowers to a `CALL` whose callee signature the existing sink
catalogue matches, and the finding is reported end to end. The measurement
also corrected one thing this document got wrong: the TAC's call convention
puts the **receiver** at `args[0]`, so the assigned value is the first *user*
argument. See [§6, Phase 0](#phase-0--the-measurement-taken).

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

**MEASURED.** The families that carry C# patterns, with the number of patterns
each holds:

```
privacy.sinks              261    CWE-215,CWE-226,CWE-359,CWE-489,CWE-497
database-access.sinks      144    CWE-566
xss.sinks                  133    CWE-79,CWE-80,CWE-82,CWE-83,CWE-87,CWE-692
sql.sinks                   87    CWE-89,CWE-564
file-path.sinks             77    CWE-22,CWE-73
weak-crypto.sinks           72    CWE-261,CWE-326
credential.sinks            66    CWE-256,CWE-522,CWE-798
setting-manipulation.sinks  58    CWE-15
resource-injection.sinks    53    CWE-94,CWE-95,CWE-99,CWE-502
header-injection.sinks      45    CWE-93,CWE-113
trust-boundary.sinks        31    CWE-501,CWE-264,CWE-287,CWE-359,CWE-732
deserialization.sinks       27    CWE-502
formula-injection.sinks     19    CWE-1236
log-injection.sinks         16    CWE-117
open-redirect.sinks         16    CWE-601
ssrf.sinks                  16    CWE-918
ldap.sinks                  14    CWE-90,CWE-20,CWE-639
cookie.sinks                13    CWE-113
dos.sinks                   13    CWE-730,CWE-776
xml-injection.sinks         13    CWE-91
code-injection.sinks         9    CWE-94,CWE-95,CWE-494
nosql.sinks                  8    CWE-943,CWE-89
regex.sinks                  8    CWE-1333,CWE-185,CWE-730
command.sinks                7    CWE-78,CWE-77,CWE-88,CWE-93,CWE-114,CWE-147,CWE-494
reflection.sinks             6    CWE-470,CWE-494
xpath.sinks                  5    CWE-643
xxe.sinks                    5    CWE-611
xslt-injection.sinks         3    CWE-494
json-injection.sinks         2    CWE-91
file-inclusion.sinks         1    CWE-94,CWE-98,CWE-494
http-parameter-pollution.sinks 1   CWE-235
xquery.sinks                 1    CWE-643,CWE-652
```

A sample of the symbols each family picks up:

```
sql.sinks                System.Data.Common.DbCommand.set_CommandText
                         System.Data.IDbCommand.set_CommandText
                         System.Data.SqlClient.SqlCommand.set_CommandText
                         System.Data.Entity.DbSet.SqlQuery
                         System.Data.Linq.DataContext.ExecuteQuery
command.sinks            System.Diagnostics.Process.Start
                         System.Diagnostics.ProcessStartInfo.set_FileName
                         System.Diagnostics.ProcessStartInfo.set_Arguments
                         System.Reflection.Assembly.Load
deserialization.sinks    System.Text.Json.JsonSerializer.Deserialize
                         System.Runtime.Serialization.Formatters.BinaryFormatter
ldap.sinks               System.DirectoryServices.AccountManagement.PrincipalContext
credential.sinks         System.Net.NetworkCredential.<init>
                         System.Security.Cryptography.X509Certificates.X509Certificate
weak-crypto.sinks        System.Security.Cryptography.*
xss.sinks                System.Web.HttpUtility.HtmlEncode
                         System.Web.UI.WebControls.TextBox.set_Text
ssrf.sinks               System.Net.HttpWebRequest
                         System.Net.WebClient
```

The .NET API surface is documented, so the enumeration is mechanical — which is
exactly why it was worth **converting** rather than typing (§5.5).

### 5.3 The property-setter consequence, spelled out

Every .NET sink that is reached through a property needs a `set_X` pattern.
This roughly **doubles** the .NET sink table relative to a naive
method-only count, and it is the single most important structural fact about
the C# catalogue.

**MEASURED.** The property accessors are not a small minority of the converted
patterns — they are the majority. In the shipped catalogue the property setters
alone account for several hundred of the 1284 C# patterns, and the two largest
single-symbol entries are `set_CommandText` (three owner types: the abstract
`System.Data.Common.DbCommand`, the interface `System.Data.IDbCommand`, and the
concrete `System.Data.SqlClient.SqlCommand`) and the ASP.NET
`set_(SelectCommand|DeleteCommand|InsertCommand|UpdateCommand)` family.

The families most affected: `sql.sinks` (CommandText, CommandType,
ConnectionString), `command.sinks` (FileName, Arguments), `file-path.sinks`
(some overloads are properties), `xss.sinks` (Text, Value).

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

### 5.5 The catalogue is converted, not hand-written — and what that cost

**MEASURED.** The .NET sink catalogue is **not** hand-written. It is converted
from an external rule pack by a conversion script, which reads the pack's
decoded rule files and emits a fragment that a merger script folds into
`sink-families.yaml`. The
hand-written base file keeps only the patterns a person wrote; the converted
ones land in each family's `by_language: csharp:` table.

The numbers, measured against the two .NET packs:

| Quantity | Value |
| --- | --- |
| .NET sink rules in the packs | 2519 |
| .NET sink rules converted | 2491 (**98.9 %**) |
| .NET sink defect classes | 72 |
| .NET defect classes mapped to a Plumb family | 66 |
| Plumb families carrying C# patterns | 36 |
| C# sink patterns in the shipped catalogue | **1284** |

The 6 unmapped defect classes are 28 rules, and none of them has a Plumb family
to map to: three are Java-only concepts, two are code-quality findings rather
than security defects, and one would need a new family.

**The mapping is by DEFECT CLASS, not by vendor rule.** An ORM variant is still
`sql.sinks`: NHibernate, LINQ, Castle ActiveRecord, PartiQL, SubSonic, Hibernate
and JDO all land in the same family, because they all express the same defect.
A family is a thing a rule author types, so the family name must be searchable;
`sql.nhibernate.sinks` is not.

#### 5.5.1 The four conversions that are not textual

The conversion is mostly mechanical, but four things in the .NET packs do not
survive a literal translation. Each of them produced a rule that either
**matched nothing** or **matched the wrong thing**, and each is now a named
case in the converter with a stated rule.

**Generic arity.** The packs spell a generic type with its arity — `DbSet@1`,
`ObjectQuery@1` — and Plumb's TAC erases generics: a callee signature is
`owner.method/arity`, where the owner carries no type arguments. A pattern that
kept `@1` would match nothing at all. The marker is stripped before anything
else, because it can sit inside a group that is itself inside an alternation
(`(DropDownList|ListBox)(For(@1)?)?`), and stripping it afterwards would leave
an empty group behind — a regex that matches the empty string, which silently
*widens* the rule.

**A wildcard around a real name.** The packs write `.*Operation` and
`(HqlBased|Simple|Scalar)Query.*` for "every class whose name ends in
`Operation`" and "every class whose name starts with `Query`". The `.*` is a
regex wildcard, but the dot inside it is a *bare* dot, and the owner's normaliser
turns a bare dot into an escaped one. The literal translation was
`Model\..*Operation` — a literal dot followed by a star — which demands an empty
namespace segment between `Model` and the wildcard, and matches nothing. The
wildcard is now peeled off before the owner is normalised, so it stays a
wildcard.

**A repeated group.** `set_(Legend)*Url` and `lookup(Link)?` are the two shapes,
and they want **opposite** answers for the "group absent" variant:

- `lookup(Link)?` — `?` means zero-or-one, so the name is either `lookup` *or*
  `lookupLink`. **Both are real methods.** The absent variant must be kept;
  dropping it lost the JNDI lookup sink entirely, and the resource-injection
  family stopped claiming `javax.naming.Context.lookup`.
- `set_(Legend)*Url` — the group is in the **middle**. The absent variant is
  `set_Url`, which is not a property of any charting class. Keeping it would
  add a name that no symbol has: a rule that looks like coverage and matches
  nothing.

So the rule is **positional**: a suffix group keeps its absent variant (the
name is a prefix of its own variant, and both are real), a middle group drops
it (its absence changes what the name *is*).

**An escaped pipe.** Scala's operator methods are spelled with pipes, and the
packs write them as `#(##|&&|\||\|\|)`. The converter's "drop the empty
alternatives" pass was not escape-aware, so it read the `|` of the literal
`||` as an alternation and deleted it. The result — `(\#\#|&&|\||\|\)` — is not
a valid regex at all, and **the whole catalogue failed to load**: "Unclosed
group near index 63". Every sink family stopped working, for every language.
That is not a precision trade-off; it is a total outage caused by one rule, and
it is invisible unless something compiles every pattern.

#### 5.5.2 The configuration gate, and why it is not a value claim

A converted sink may be gated on a *taint flag*. There are two kinds, and they
are not equivalent:

- A **mitigation** gate (`VALIDATED_*`) says "this value was *not* validated".
  Plumb models sanitizers, so ignoring the gate makes Plumb narrower — safe.
- A **value or configuration** gate says something about the *value* or about
  *how the object is set up*: `SHELL_PROCESS` ("the tainted value IS the
  command"), `INSECURE_TYPENAMEHANDLING_SETTING` ("the mapper resolves type
  names unsafely").

Plumb's receiver position means "the object carries taint", which is a
*different proposition* from either of those. The Jackson rule is the sharp
case: its gate is `INSECURE_TYPENAMEHANDLING_SETTING`, and its claim is about
the mapper's **configuration**. Translating the receiver made Plumb report
deserialization on every Jackson call that received untrusted data — whether or
not `TypeNameHandling` was ever enabled. Worse, the pattern
`read(Tree|Value|Values)` also matches `readValue`, so the rule *shadowed* the
narrow one and moved the reported argument from the data (0) to the receiver.

So a receiver position under a value or configuration gate is dropped, and the
argument position under the same gate is kept: the position claim ("the tainted
value lands in argument N") survives the flattening.

#### 5.5.3 What proves the conversion

Three things, and each one catches a different failure:

1. **Every pattern in the catalogue compiles.** A pattern that does not compile
   takes the whole catalogue down with it. This is the test that would have
   caught the Scala pipe.
2. **No C# pattern carries a generic-arity marker, and no C# pattern contains an
   escaped wildcard.** Both are rules that can never fire, and both look like
   coverage in the family list.
3. **A converted pattern is reached end to end.** `ProcessStartInfo.set_Arguments`
   is a *converted* sink (not a hand-written one), and the test drives
   `startInfo.Arguments = tainted` through the frontend, the catalogue and the
   engine, and requires the finding. A conversion bug is otherwise invisible:
   the rule is in the family list, the scan reports success, and nothing is
   found.

The tests are `SinkCatalogueCsharpTest` and `CSharpDfaIntegrationTest` in
`plumb-dfa`.

#### 5.5.4 What the other tool contributes: nothing, and that is a measured fact

The other external source has a **model library** that Plumb converts
(a conversion script reads it and emits a fragment that the
same merger folds in). **MEASURED: that library has no .NET model library at
all.** Its `library/` tree carries `generic/` (C and C++), `go/`, `java/` and
`security/` — and nothing for C#. The converter's default path is
`library/java/`, and the shipped fragment for that source has **zero** C# patterns.

So the C# catalogue comes entirely from the rule packs, and the second source
contributes no C# content. That is worth stating plainly rather than leaving
it as an apparent gap: the two sources are not interchangeable suppliers of .NET
sink knowledge, and a plan that assumed both would contribute would be wrong
about one of them.

### 5.6 The comparative review: what the other tool's checker list exposes

The other tool ships a **checker reference** — a per-checker HTML document
([EXTERNAL]) describing every weakness it detects, per language. That document
is not a rule pack and it carries no API list, but it **is** a taxonomy of
weaknesses, and a taxonomy is exactly what a coverage review needs: it says
which defect classes exist for C#, so a family that has no C# patterns is
visible as a hole rather than as an absence nobody noticed.

#### 5.6.1 The measurement

The reference carries **299 checker sections**; **80** apply to C#, of which
**49** are Security Checkers. Mapping each of the 49 onto the Plumb family
that carries the same defect class gave the first review result:

| | count |
| --- | --- |
| Security Checkers that apply to C# | 49 |
| Covered by a Plumb family that has C# patterns | 31 |
| The family existed but had **no** C# patterns | 5 |
| The family did not exist at all | 3 |
| Not a taint rule (configuration / value / control-flow) | 11 |

The last row matters as much as the first. A checker like
`CONFIG.ENABLED_DEBUG_MODE` is a *configuration* rule — "the debug mode must
be off" — and `DIVIDE_BY_ZERO` is a value rule. Neither is a taint sink, and
neither can be expressed as one. Mapping them onto sink families would produce
rules that report taint findings for code that has no taint in it.

#### 5.6.2 What the review actually changed

The review was not a report; it changed the catalogue. Each of the seven
uncovered checkers was re-examined against the vendor's own data, and the
result was three different answers.

**Five were real gaps, and they are now closed.** The family existed but had
no C# pattern, because the external .NET packs express those defect classes
**without a dataflow rule at all** — as a *semantic* rule, a *structural* rule or
a *control-flow* rule. The dataflow parser skipped them, so they never reached
the catalogue. The API list is recoverable from those rules' own
`<FunctionIdentifier>`, and the converter now reads it.

That reading has to be filtered by what KIND of assertion the rule makes:

| The rule's claim | Is it a taint sink? | Example |
| --- | --- | --- |
| The tainted value is an argument | **Yes** | "the method invokes a SQL query built with input from an untrusted source" |
| The object's CONFIGURATION is wrong | No | "the application is configured to communicate with its database in plain text" |
| The ALGORITHM is weak | No | "the random number generator cannot withstand a cryptographic attack" |

The second and third rows are the trap. *Insecure Transport* and *Insecure
Randomness* look like taint weaknesses — they have a "Security Checker"
label and a CWE — but the vendor's own description says the defect is a
property of the setup, not of a value flowing into it. Converting them into
taint sinks would report a cleartext-transport finding on a connection string
that is a literal, and a weak-PRNG finding on code with no taint at all.

**MEASURED: of the 571 .NET rules that have no dataflow rule, 104 are value
claims and are converted; 467 are configuration or control-flow claims and are
counted but not converted.** The 104 add the EF Core `FromSqlRaw` /
`ExecuteSqlRaw` family, `DbBatchCommand.set_CommandText`, the
`XmlReaderSettings.<init>` constructors and the Dapper query methods — APIs the
dataflow rules missed.

**Two remain open, and they are open for a stated reason.** `RESOURCE_LEAK` and
`TAINT_ASSERT` are not taint rules at all. `TAINT_ASSERT` compares a
user-asserted non-taintedness against the computed taint — it is a rule about
the *analyser*, not about the program, and Plumb has no family for it.

`RESOURCE_LEAK` turned out to be a different story, and the review found it.
See [§5.6.3](#563-the-checker-the-comparison-found--and-the-sink-families-could-not).

#### 5.6.3 The checker the comparison found — and the sink families could not

`RESOURCE_LEAK` is **not a taint rule**. It fires when an allocation is *not*
closed (CWE-404), so it belongs to no sink family — it is a **checker**, and its
tracked-type list is the mechanism.

And that list was **Java-only**. Every name in it was a Java type, so:

- a C# `new FileStream(...)` was **not an allocation site** at all, and the
  rule never fired on C#;
- even had it fired, .NET spells the close **`Dispose`**, not `close` — so a
  correctly-disposed .NET resource would have been reported as a leak. That is
  the worst shape of false positive: it fires on *correct* code, and on
  *every* resource in the program.

**This is what a comparative review is for.** A review that compares sink
*families* would never have found it: the rule has no sink family, so it does
not appear in a family-by-family comparison at all. It appears only because
the other tool's checker list names a `RESOURCE_LEAK` checker for C#, and
"Plumb has a rule with this rule id, but does it work for C#?" is a question
worth asking.

Two changes close it:

1. the tracked-type list gained the .NET resources (`FileStream`,
   `StreamReader`, `SqlConnection`, `NetworkStream`, `XmlReader`, …) — the
   same discipline as the Java list: a type is a resource when it has a
   `Dispose`;
2. `Dispose` is recognised as a close, alongside `close`.

MEASURED: a C# `FileStream` that is never disposed is reported; the same
resource with a `Dispose()` is not.

#### 5.6.4 The hand-written patterns are measured, not remembered

The five gaps that needed a *hand-written* pattern (rather than a converted
one) raised the question of where the API names come from. A sink pattern
naming an API that does not exist is a **dead rule**: it looks like coverage
in the catalogue and it can never fire.

So the .NET API surface is **measured** rather than recalled, with the same
Roslyn the C# frontend uses: a probe compiles a C# file that references each
API, and an API the compiler rejects is an API the frontend cannot resolve
either. The harness is
`.aethercode/rule-config/apiprobe/` (Roslyn 4.12, the pinned version).

MEASURED, and every one of these is a correction the measurement forced:

| The pattern said | The framework says | The correction |
| --- | --- | --- |
| `DataContractSerializer.Deserialize` | the method is `ReadObject` | renamed |
| `Random.NextInt32` / `NextInt64` | neither exists on `System.Random` | removed |
| `HttpWebRequest.<init>` | the constructor is not public | removed — a protected constructor is a rule that can never fire |
| `XmlReaderSettings.set_XmlResolver` | the property has no getter | the setter is kept, the read is not |
| `System.Net.Http.HttpResponseHeaders` | the type is `System.Net.Http.Headers.HttpResponseHeaders` | renamed — the owner must be the type that **declares** the member |

**MEASURED: 37 hand-written C# patterns, 37 confirmed.** The remaining 6 name
an API outside the shared framework (`System.Data.SqlClient`,
`Microsoft.Data.SqlClient`, `System.DirectoryServices` — a NuGet package or a
Windows-only assembly). Those are not dead patterns — the vendor's rules name
the same APIs — but the probe cannot see them, and the check says so rather
than claiming a measurement it did not make.

#### 5.6.5 The constructor gap, and why it was not a TAC problem

The comparison surfaced one genuine TAC-level defect, and it is worth stating
because it was invisible in every other way.

A C# `new` expression lowers to a `NEW` instruction. The DFA engine queries
the sink catalogue for a `NEW` **only when the instruction carries a callee
signature** (`TaintedCheckerBase.checkSinkAtNew` returns immediately when it
is null). The C# frontend emitted `NEW` with the constructed *type* only — so
**every constructor sink was structurally unreachable**: the catalogue carried
the pattern, the code was right, and the finding was simply absent while the
scan reported success.

That is the same failure mode the property-setter rule was chosen to avoid,
in a place nobody was looking. The fix follows the contract the Kotlin frontend
already uses: a C# `NEW` now carries **both** `ctorType` and the
`owner.<init>/arity` callee signature.

MEASURED: of the .NET packs' 321 constructor rules, **314 name a concrete
type** — and the frontend resolves those exactly, so they now fire. **7 name
an interface** (`System.Data.IDbCommand.<init>` and friends), and an interface
has no constructor: Roslyn resolves `new SqlCommand(sql)` to
`System.Data.SqlClient.SqlCommand.<init>/1`, so the interface-named pattern is
a dead rule. The converter expands those 7 into their concrete
implementations, alongside the interface form.

#### 5.6.6 The result

| | before | after |
| --- | --- | --- |
| C# security checkers covered by a sink family | 31 | **36** |
| C# security checkers covered by a checker | 0 | **37** (the resource-leak rule) |
| Families with C# patterns | 32 | **36** |
| C# sink patterns | 1230 | **1284** |
| Checkers still uncovered | 7 | **1** (`TAINT_ASSERT`, a rule about the analyser) |

**MEASURED: 37 of the 49 C# security checkers are covered.** The one that is
not is `TAINT_ASSERT`, which compares a user-asserted non-taintedness against
the computed taint — it is a rule about the *analyser*, not about the
program, and there is nothing in the program for it to find.

---

### 5.7 Four TAC defects the comparative review exposed

The checker list says *which defect classes* exist. It does not say whether
the frontend's TAC can express them. A frontend can cover every checker and
still produce TAC that mis-states the program — and that failure is invisible
in a scan that reports success.

So the review did not stop at the catalogue. It compared the C# lowering
against the Java and Kotlin lowerings, construct by construct, and four
differences turned out to be **defects** rather than simplifications.

The common shape of all four is the same, and it is worth stating once:

> **A C# construct that lowers into a shape the engine reads literally is
> wrong in a way no catalogue can correct.** The catalogue says *where* to look
> for a sink; it says nothing about whether the control flow around the sink
> is the program's control flow.

| # | Construct | What the lowering produced | What the program does |
| --- | --- | --- | --- |
| 1 | `switch` | the case label was emitted **before** the comparison that selects it | the branch enters the case **body** |
| 2 | a literal | every literal was a `STRING` | an `int` is an `int` |
| 3 | `break` / `continue` | **silently dropped** | a jump |
| 4 | `for` | the condition's true edge went to the **increment** | to the **body** |

#### 5.7.1 The switch: a label defined before the branch that targets it

The Java reference lowers a switch in two phases: the whole comparison chain
first, then the case bodies under their labels. The C# lowering interleaved
them — label, comparison, body, label, comparison, body.

The consequence is a **backward edge**. The branch that selects the first case
targets a label that was emitted *above* it, so the "true" edge lands on the
comparison again rather than on the body:

```
LABEL  case_1        <- the branch below targets THIS
BINOP  t = (mode == 1)
BRANCH t ? case_1 : case_2
```

The result is still a **well-formed** CFG: every label resolves and the CFG
builder accepts it. It is just semantically wrong, and wrong in the way a scan
cannot see — the scan reports success.

The fix is the Java reference's two-phase shape. The `default:` section needed
its own treatment: it has no comparison, so it is reached by the chain's last
false edge and by the jump that follows the chain. Without both, the default
body is unreachable.

**MEASURED:** the comparison branch targets a label defined **after** it, and
the default body is reachable.

#### 5.7.2 The literal: a switch on the wrong kind

Every literal in a C# file came out as a `STRING` — every number, every
boolean, every character. The cause was one word:

```csharp
return token.Kind() switch        // NumericLiteralToken, TrueKeyword, ...
{
    SyntaxKind.NumericLiteralExpression => ...,   // never matches
```

The switch was written against the **syntax node** kinds but asked the
**token** for its kind. The two are different vocabularies: the token kind is
`NumericLiteralToken`, the node kind is `NumericLiteralExpression`. No case
matched, so every literal took the `_ =>` default, which is `STRING`.

This is the worst shape of bug in the set, because the constant still carried
the right **value**. The TAC looked plausible, every test that checked values
passed, and the thing that was lost was the constant's **kind** — which is
exactly what a range check on an integer, or a boolean test, reads.

**MEASURED:** an `int` is `INT`, a `long` is `LONG`, a `double` is `DOUBLE`, a
`float` is `FLOAT`, a `decimal` is `DECIMAL`, a `bool` is `BOOL`, a `char` is
`CHAR`, a `string` is `STRING`, `null` is `NULL`, and no literal is a `STRING`
unless it is one.

#### 5.7.3 `break` and `continue`: dropped, not lowered

A `break` and a `continue` are jumps, and a jump needs a target. The C# lowering
dropped both.

Dropping them does not make the TAC smaller; it makes the TAC describe a
control flow the program does not have:

- a dropped `break` inside a switch lets the case body **fall through** into
  the next case, so the dataflow engine follows a path the program never takes;
- a dropped `continue` inside a loop runs the **rest of the body** instead of
  restarting the iteration, so the taint state at the top of the next iteration
  is wrong.

The fix needs a context, because the target depends on where the statement
sits:

| Statement | Target |
| --- | --- |
| `break` in a `switch` | the switch's end label |
| `break` in a `loop` | the loop's end label |
| `continue` in a `while` | the condition label |
| `continue` in a `for` | the increment label |
| `continue` in a `foreach` | the increment label |
| `continue` in a `switch` | the enclosing **loop's** increment — C#'s switch has no continue target of its own |

The innermost context wins, and the context is saved and restored around every
loop and switch so a nested one does not leak its labels outward.

#### 5.7.4 The `for` loop: the condition's edge went to the increment

The `for` lowering routed the condition's true edge to the **increment**
label. With a `continue` also targeting the increment, the incrementor ran
twice on that path — and on the *first* iteration too, because the condition's
true edge skipped the body entirely.

The Java reference has four labels for this (`cond`, `body`, `update`, `end`)
and the C# lowering now does the same: the condition's true edge goes to the
**body**, and only a `continue` goes to the increment.

#### 5.7.5 What the four defects had in common

Every one of them was a case of the same mistake: **assuming the TAC's shape
is the program's shape**.

- The switch assumed a label's position in the instruction stream is a detail.
- The literal assumed a token kind and a node kind are the same thing.
- `break` assumed a statement with no body is a statement with no effect.
- The `for` assumed "the next thing after the condition" is the increment.

The countermeasure is not a review checklist; it is a comparison. The Java and
Kotlin lowerings already encode the answers, so the question is not "is this
right?" but "does the C# lowering do what the Java one does?" — and that
question is answerable by reading two files side by side.

**MEASURED:** the C# lowering tests went from 11 to **32** — the property
lowering's 11, plus 6 for the switch, 10 for the literal kinds and 5 for
`break`/`continue` — and each of the four defects has a test that fails against
the old lowering.

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

#### Phase 0 — the measurement, taken

**The measurement was taken, and the answer is YES.** The property rule works:
a C# property assignment becomes a `CALL` whose callee signature the existing
catalogue matches, so **one matching mechanism is enough** and the expensive
second mechanism is not needed.

The measured facts:

| Question | Answer |
|---|---|
| `cmd.CommandText = tainted` → `CALL`? | **Yes.** `calleeSig` is `System.Data.Common.DbCommand.set_CommandText/1`. |
| Does the catalogue match it? | **Yes.** `sql.sinks`' `by_language: csharp:` pattern matches, `arg_index: 0`. |
| Is the finding reported end to end? | **Yes.** `taint: param:input -> CWE-89,CWE-564`. |
| A genuine field assignment | Still a `FIELDSTORE`. The property/field split comes from the resolved symbol, never the spelling. |
| `await` | `SOURCE_LOWERED`, and the awaited value keeps its taint. |
| `x!` | **Not** a `NULL_CHECK` — see below. |
| `catch (IOException)` | `CATCH_HANDLER` with `ctorType = System.IO.IOException`. |

The implementation is in `plumb-frontend/csharp-sidecar/` (the .NET side) and
`plumb-frontend/src/main/java/org/plumb/tac/frontend/csharp/` (the Java side).
The protocol is `plumb-frontend/csharp-sidecar/PROTOCOL.md`.

**The measurement changed one thing the plan had wrong.** The plan's
assumption — and the first implementation — put the assigned value at
`args[0]`. That is wrong, and the TAC's own call convention says so:

```
args[0] = the receiver, when the call has one
args[1..] = the actual arguments
```

The engine reads the catalogue's `arg_index` against that convention: a
catalogue entry saying `arg_index: 0` names the **first user argument**, and
the engine adds the receiver offset itself.

So the first implementation produced TAC that looked right and matched the
catalogue — and then the engine read `args[0]`, found the *receiver*, found
nothing tainted there, and reported **nothing**. The scan still reported
success. This is precisely the failure mode the whole design was chosen to
avoid, and it is invisible without an end-to-end test: the frontend's own
tests all passed, because the TAC really did contain the right `CALL`.

**That is the finding worth keeping.** A "property → `CALL`" measurement is
not enough on its own. The measurement that matters is **property → `CALL` →
catalogue match → finding reported**, and the last link is where the argument
convention lives.

Two more corrections the measurement forced:

- **`x!` is not a `NULL_CHECK`.** The null-forgiving operator tells the
  *compiler* that a value is not null; it inserts **no runtime check**. There
  is no `NullReferenceException` to catch, because the check does not exist at
  run time. Emitting a `NULL_CHECK` would claim a check that does not exist,
  and the null-dereference checker would then suppress a finding that is
  entirely real.
- **The sink's receiver type is `System.Data.Common.DbCommand`,** the
  abstract base class in the shared framework. The concrete providers
  (`System.Data.SqlClient`) ship as NuGet packages, so a machine that has not
  restored them cannot resolve the concrete type — and a test that needs one
  would fail for a reason that has nothing to do with the lowering. The base
  class declares the same `CommandText` property, so the setter's signature —
  the thing the catalogue matches — is identical.

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

**MEASURED — this phase is done, by conversion rather than by hand.** The
catalogue is generated from the .NET rule packs and merged into
`sink-families.yaml`: **1284 C# patterns across 36 families**, covering **66 of
the 72** .NET defect classes (§5.5). The four conversions that are not textual
— arity, wildcards, repeated groups, and the configuration gate — are §5.5.1
and §5.5.2.

**Gate:** a .NET sink table exists for every family that has a .NET symbol, and
a test asserts each entry is reachable in C# and in no other language — the
same gate the multi-language design doc
[§15.6](../MULTI-LANGUAGE-IR-DESIGN.md) specifies. **MET:** the language
filter is asserted in `SinkCatalogueCsharpTest` (I3), and the whole-catalogue
compile check (I1) is the assertion that a conversion bug cannot pass.

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

## 6.5 The C# dataflow gap: every .NET spelling of a passthrough was missed

§5.7 was about the **TAC**. This section is about what happens **after** the TAC: the engine's
dataflow. And the finding is the same shape as the §5.7 ones — the frontend produced a correct
TAC, and the engine still got the wrong answer.

### 6.5.1 What was measured

`SourceLanguage.CSHARP` appeared **nowhere** in `plumb-dfa/src/main/java`. Every C# callee
reached the engine as `TaintBehaviour.UNKNOWN`, and the engine's conservative default treats an
unknown callee as **taint-preserving**.

That default is right for an API nobody has modelled. It is wrong in two directions at once:

| The .NET call | What it does | What the engine did |
|---|---|---|
| `s.Trim()`, `s.Substring()`, `s.Split()`, `s.Replace()` | Returns a piece of the input | **DROPPED the taint** — a false negative |
| `int.Parse(s)`, `double.Parse(s)` | Returns a number, not the text | **KEPT the taint** — a false positive |

Both directions were measured, not assumed.

### 6.5.2 The false negative: a case-sensitive method-name comparison

The engine recognises a taint-passthrough call by **method name**, through a family of helpers
(`isTrimLike`, `isSubstringLike`, `isCaseLike`, `isSplitLike`, `isValueOfLike`, `isAppendLike`,
`isFormatterLike`, `isCharByteLike`). Every one of them compared the name **case-sensitively**
against Java's spelling:

```java
private static boolean isTrimLike(String calleeSig) {
  String m = methodNameOf(calleeSig);
  return "trim".equals(m) || "strip".equals(m) || ... ;
}
```

Java writes `s.trim()`; .NET writes `s.Trim()`. `methodNameOf` returns the name **verbatim**, so
`"trim".equals("Trim")` is `false` and **every** .NET string operation fell through the
recognition entirely.

The affected calls, and what each one cost:

| Helper | Java spelling | .NET spelling missed | Cost |
|---|---|---|---|
| `isTrimLike` | `trim` | `Trim` | false negative |
| `isSubstringLike` | `substring` | `Substring` | false negative |
| `isSplitLike` | `split` | `Split` | false negative |
| `isCaseLike` | `replace` | `Replace` | false negative |
| `isCaseLike` | `toUpperCase` | `ToUpper` | false negative |
| `isValueOfLike` | `toString` | `ToString` | false negative |
| `isAppendLike` | `append` | `Append` | false negative |
| `isFormatterLike` | `format`, `join` | `Format`, `Join` | false negative |
| `isCharByteLike` | `toCharArray`, `getBytes` | `ToCharArray`, `GetBytes` | false negative |

A **false negative** is the expensive kind: the finding that should have been reported never was,
and the scan reported a clean program for code that flows user input into a command.

The fix is one comparison: `equalsIgnoreCase`. The helpers recognise a call by **what it does**,
and `TRIM`, `Trim` and `trim` are the same operation in every language that has it — so a
case-insensitive comparison cannot make an unrelated method match.

### 6.5.3 The false positive: .NET's parsers were not sanitizers

The mirror image. `isSanitizer` lists the Java numeric parsers by their Java **owner prefix**
(`java.lang.Integer.parseInt/1`), so a .NET `System.Int32.Parse/1` never matched. A C# program
that parses untrusted input into an `int` therefore reported the parsed value as still tainted.

The owner prefix cannot be made case-insensitive away — `java.lang.Integer` and `System.Int32` are
different spellings of the same operation. So the .NET parsers are listed **by their own owner**,
which is also what the semantics table does.

### 6.5.4 The semantics table

Both fixes need the same thing the Java and Scala tables already had: **a per-language statement
of what the standard library does**. C# had none.

`LanguageSemanticsRegistry.csharpSemantics()` now holds **~80 measured entries**, and every prefix
in it was **measured** by running the C# frontend over a probe of the .NET string and IO
operations. The measurement matters because .NET spells several operations differently from the
source text suggests:

| Source | Measured callee key |
|---|---|
| `s.Length` | `System.String.get_Length/0` — a **property is a method call** |
| `s + "x"` | `System.String.Concat/2` — the `+` operator is a call |
| `string.Format("{0}", x)` | `System.String.Format/2` — the arity counts the format string |
| `s.Trim()` | `System.String.Trim/0` |

A table written from the source text alone would have named `System.String.Length` — a key the
frontend never emits — and every entry would have been dead.

### 6.5.5 The rule this section earns

> **A naming convention is not a behaviour.** When an engine recognises a call by its name, the
> comparison must survive the conventions of every language it analyses — or the recognition
> silently fails, and the failure is a false negative in the direction that costs the most.

And the shape of the measurement generalises:

> **The frontend's own tests are not enough.** The C# frontend emitted a perfect `CALL` for
> `s.Trim()`, and the frontend's tests passed. The defect was in the *consumer* of that TAC, two
> modules away. A test that asserts the IR contains the right instruction says nothing about
> whether the finding is reported.

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
