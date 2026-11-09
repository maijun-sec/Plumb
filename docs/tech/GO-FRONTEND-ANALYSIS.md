# Go in Plumb: The Sidecar Route, the Capture Contract, and the TAC Generation

[English](../README.md) | [中文](../zh/tech/GO-FRONTEND-ANALYSIS.md)

This document answers four questions, in order:

1. **Can a JVM-hosted engine analyse Go at all?** The answer decides the route, and it is
   not a preference.
2. **What does Go break in the TAC?** Four constructs do not have a home in the IR as it
   stands. Three of them are closed by a decision; the fourth is closed by accepting a
   coarser type system.
3. **What does the build capture have to supply?** The Go build produces two different
   kinds of command, and only one of them says what is in the binary.
4. **How is a Go TAC produced, end to end?**

The route is the same one C# already uses: a native process that speaks a framed JSON
protocol, and a JVM that reads it. What changes is that the Go sidecar has to be a **Go
program**, because Go's type resolver is a Go program.

Everything marked **[MEASURED]** was read out of this repository's code or measured
against it. The parts that describe what the Go toolchain does are marked
**[TOOLCHAIN]**, and are statements about the toolchain's documented behaviour rather
than about Plumb.

---

## 0. The conclusion first

| Question | Answer |
| --- | --- |
| Can the JVM analyse Go? | **Not directly, and the gap cannot be closed.** Resolving a Go type means running `go/types`, which is Go source in the Go standard library. There is no JVM port of it. A JVM frontend that cannot resolve a type cannot produce a callee signature, and a callee signature is what the sink catalogue matches on. |
| What is the route? | **A native Go sidecar**, over the same length-prefixed JSON protocol the C# sidecar already speaks, feeding the same Java-serialised IR into the same engine. |
| Does the TAC IR contract change? | **No.** Zero changes to the IR contract: no new opcode, no new type kind, no change to `RETURN`, no change to the callee-key contract. Go is the first language to reach the engine without moving the IR, and that is a consequence of decisions, not of luck. |
| What does the capture have to give? | The per-package compile invocations **and** the link invocation. The link invocation's import configuration is the authoritative statement of which packages are in the binary; the compile invocations say what each package was compiled against. |
| What is the real cost? | **The sink catalogue.** `sink-families.yaml` has 82 families, 38 of them scoped by language, and **none** of them is scoped to Go. Go does not share the JVM's standard library, so the catalogue for Go is a new body of work, not a port. |

The last row is the one that decides whether Go produces findings at all, and it is the
same shape of problem the C# catalogue was
([CSHARP-FRONTEND-ANALYSIS.md](CSHARP-FRONTEND-ANALYSIS.md)).

---

## 1. The JVM cannot resolve Go types

### 1.1 What a JVM-hosted Go frontend would have to do

A frontend's job is to turn source into TAC, and a TAC instruction carries a **callee
signature**: the owner, the method name and the arity, in the canonical form
`owner.method/arity` (`CalleeKeyNormalizer.java:45`). The engine looks the signature up
in the sink catalogue, and the catalogue is a set of patterns over signatures.

So a frontend cannot avoid resolving types. It cannot fake them either, because the
catalogue's patterns are written over resolved signatures: a pattern that matches
`net/http.Get` has to be matched against a signature that says `net/http.Get/1`, and
only a type resolver can produce that.

Resolving Go types means:

- `go/parser`, to produce the syntax tree;
- `go/types`, to check it and to give every expression a type;
- `go/build` and `golang.org/x/tools/go/packages`, to find the packages and the export
  data.

All four are **Go source**. There is no JVM implementation of them, and there is not
going to be one: `go/types` is a type checker for a language whose type system is
expressed in Go code, and it depends on the Go standard library's own data structures.
A JVM frontend would have to reimplement Go's type system from scratch, and it would
have to reimplement it *exactly*, because a type that resolves differently produces a
different signature and therefore a different catalogue match.

That is not a hard problem in the way "write a Go parser" is hard. It is a hard problem
in the way "be a second implementation of a language" is hard, and it buys nothing:
the Go toolchain already has it, and it is right.

### 1.2 The route: a native Go sidecar

The C# frontend already established the shape: a native process that owns the
language's own compiler, connected by a framed JSON protocol, with the JVM reducing the
result to the same IR every other frontend produces
([CSHARP-FRONTEND-ANALYSIS.md section 2.4](CSHARP-FRONTEND-ANALYSIS.md)).

Go takes the same shape with one substitution: the sidecar is a Go program that links
`go/parser` and `go/types`, rather than a .NET program that links Roslyn. Everything
downstream is unchanged:

| Layer | What it is | Does it change for Go? |
| --- | --- | --- |
| The sidecar | A native process that runs the language's own front end | Yes: it is a Go program, not a .NET one |
| The wire format | Length-prefixed JSON, mirrored on the TAC's shape | No |
| The IR | `org.plumb.tac.ir.*`, produced by the frontend, serialised by `IrSerializer` | No |
| The engine | The CFG builder, the DFA engine, the sink catalogue | The catalogue gains Go families; the engine does not change |
| The disk cache | The same `.tac` contract, the same version handshake | No |

Two independent commercial static analysis tools use this same shape for Go, and the
shape is forced rather than chosen: a JVM-hosted analyser that wants Go's types has to
run Go's type checker, and the only way to run Go's type checker is to run Go.

---

## 2. What Go breaks in the TAC

The TAC has 22 opcodes (`TacInstruction.Kind`), a `RETURN` that carries at most one
value operand (`TacInstruction.java:362`), a type lattice of 14 kinds (`TacType.Kind`),
and a callee-key contract of `owner.method/arity`. Four Go constructs do not fit. None
of them needs a new opcode.

### 2.1 The four gaps

| Go construct | What the TAC has | How it is closed |
| --- | --- | --- |
| Multiple results, `(T, error)` | `RETURN` carries at most one value; `ReturnShape` is a single-value shape | A decision: the value result is the call's destination, the error result is an exceptional exit |
| `defer` | `CATCH_HANDLER` and `FINALLY_ENTER` are markers inside one function's control flow | A side table on the function, not a control-flow region |
| `go` statements and channels | No program-level concurrency model in the engine | Explicitly asynchronous, with no happens-before claim |
| Maps, pointers, slices, structs | 14 type kinds, none of them a pointer, a map or a struct | A coarser mapping onto the kinds that exist |

### 2.2 Multiple results: the value result and the exceptional exit

Go's overwhelmingly common two-result shape is `(T, error)`, and Go's convention is
that `error` is the **last** result. That convention is what makes the following
lowering possible without touching the IR:

| The Go shape | Lowered as | What the analysis cannot say |
| --- | --- | --- |
| `v, _ := f()` | A `CALL` whose destination is unused | Nothing is lost; the discarded result is simply not read |
| `v, err := f()` | A `CALL` whose destination is `v`; the error is the call's exceptional exit | The error is not a value the caller can inspect |
| `return v, nil` | `RETURN v` | -- |
| `return v, err` | `THROW err` | The caller sees an exceptional exit, not a returned error |
| A function with two meaningful results | Not modelled | The function is reported as `SOURCE_LOWERED` |

The `THROW` lowering is the load-bearing part. Go's own idiom is

```go
v, err := f()
if err != nil {
    return err
}
```

which is exactly "on the error path, leave the function abnormally". Modelling the
error result as an exceptional exit turns that idiom into the TAC's own `THROW`, and
the TAC already has `THROW` and `CATCH_HANDLER`. **The IR does not change.**

What it costs is worth stating plainly, because it is a real loss and not a technicality:

- The error is no longer a value. A checker for "an error return that is not checked"
  cannot read `err` in the caller, because there is no `err` value there any more.
  Such a checker has to be specified for Go on the exceptional-exit model instead.
- A function that returns two values **both** of which matter to the analysis is not
  modelled. That case is rare, it is detectable at translation time, and it is
  reported as a degradation rather than silently dropped.

### 2.3 `defer`: a call at the defer site, and a record of what it really is

`defer` is not a `finally` block, and the TAC's `FINALLY_ENTER` is the wrong home for
it, for two reasons:

- A `defer` runs at **function** exit, not at the exit of the block that contains it.
  A `FINALLY_ENTER` marker is a point in a control-flow region.
- A `defer`'s **arguments are evaluated where the `defer` statement is**, not where the
  deferred call runs. A control-flow region evaluates at the region.

So a `defer` is modelled in two places at once:

- The **call is emitted into the instruction stream**, right after the instructions that
  evaluate its arguments. The arguments are the ones the defer site produced, so the
  edge from a tainted value into the deferred call is the real one: a value reassigned
  after the `defer` is not seen by it, exactly as in Go.
- The **deferred-call record** is attached to the function as well: the callee
  signature, the operands, and the position of the defer site. It says what the
  instruction stream approximates.

Why both? Because the call alone misplaces the call in time, and the record alone
loses the edge. A record that only the side table holds is invisible to a checker
that reads the instruction stream, so `defer os.WriteFile(name, tainted, 0644)`
would have no path into `os.WriteFile` at all -- and a missing path is a missing
finding, not a missing precision.

What is lost, and said out loud: the call really runs at the function's exit, and the
instruction stream puts it at the defer site. A checker that depends on the order of
the statements around the `defer` would conclude something the program does not
guarantee. So the function's precision is lowered to `SOURCE_LOWERED` and a problem
says why. The lowering does **not** suppress any finding: it records the
approximation rather than filtering the results.

### 2.4 `go` and channels: asynchronous, but no ordering claim

The engine analyses one function at a time and has no program-level concurrency model.
Two independent implementations of Go analysis in commercial tools take the same
position: the goroutine is **explicit**, and no happens-before relation is claimed.

So:

- `go f(x)` is lowered as an ordinary `CALL`, plus a flag on the enclosing function
  saying that this function starts a goroutine.
- A channel send and a channel receive are ordinary calls on the channel's type.
- The analysis makes **no claim** about whether a goroutine's write happens before the
  starter's read.

The last point is the one that has to be said out loud, because it is where a reader
would otherwise assume too much. A checker that reports "a value written by a
goroutine is read without synchronisation" is **out of scope**, and pretending
otherwise would be a false-positive machine.

### 2.5 Maps, pointers, slices and structs: a coarser type system

`TacType.Kind` has 14 kinds -- `BOOLEAN`, `BYTE`, `SHORT`, `CHAR`, `INT`, `LONG`,
`FLOAT`, `DOUBLE`, `DECIMAL`, `STRING`, `CLASS`, `ARRAY`, `NULL`, `VOID` -- and none of
them is a pointer, a map, a struct or a slice. The mapping is therefore:

| The Go type | The TAC type | Note |
| --- | --- | --- |
| `*T` | `CLASS` | A reference; the taint engine only asks whether it is tainted |
| `[]T` | `ARRAY` | |
| `map[K]V` | `CLASS` | A reference; reads and writes are calls on the map |
| `struct{...}` | `CLASS` | |
| `error` (an interface) | `CLASS` | |
| `string` | `STRING` | |
| `int`, `int64`, `float64`, ... | The matching numeric kind | |

This is acceptable because the taint engine asks "is this value tainted", not "what is
its exact type". A checker that needs to know that a value is specifically a `[]byte`
needs the Go-specific layer, and would get it from the Go front end's own type
information rather than from the TAC's type lattice.

### 2.6 What has to be added to the IR's own vocabulary

Exactly one thing: a `SourceLanguage` constant. The enum has six constants today
(`JAVA`, `KOTLIN`, `SCALA`, `GROOVY`, `CSHARP`, `UNKNOWN`), and adding one at the end
is backwards compatible, because the enum is serialised **by name** in the disk-cache
contract (`SourceLanguage.java:24-25`).

The language semantics registry has to learn Go as well: `LanguageSemanticsRegistry.withDefaults`
registers Java, Kotlin, Scala and C# today, and a language with no registered semantics
has no passthrough rules, no sanitizer rules and no source rules.

---

## 3. The build capture: what `go build` has to give

### 3.1 The two levels of a Go build

`go build` does two different kinds of work, and the capture has to see both. The
compiler is invoked once per package, and then the linker is invoked once:

| Level | The command | What it carries | Why it matters |
| --- | --- | --- | --- |
| Compile | `compile -p <import path> -importcfg <file> -lang=go<version> ... -o $WORK/<n>/_pkg_.a <files>` | The package's own source files, its language version, and the **export data** of its dependencies | This is what each package was compiled against |
| Link | `link -importcfg <file> -o <binary> ...` | The **package list** that is actually linked into the binary | This is the authoritative statement of what the program contains |

The distinction is the point of the whole section. The compile commands say what each
package *saw*; the link command says what the program *is*. A dependency that is
compiled but never linked -- a tool, a test, a build-tagged-out variant -- must not
appear in the analysis, and only the link level knows that.

### 3.2 What the capture must extract

| From the compile command | Why the analysis needs it |
| --- | --- |
| The package's import path (`-p`) | The package identity; the call graph and the module graph are keyed on it |
| The source files | The files to translate |
| The language version (`-lang=go1.23`) | Which language features the package was compiled with; a type checker has to be told the same |
| The import configuration's export data | The **types** of the dependencies, without reading their source |
| The build tags, `GOOS`, `GOARCH` | Which files were in the compilation at all |
| **The toolchain's own path** | **Where the standard library's source is.** The compiler lives at `<root>/pkg/tool/<goos>_<goarch>/compile`, so the root is the part before `/pkg/tool/`, and it is the toolchain that *built this project* |

| From the link command | Why the analysis needs it |
| --- | --- |
| The package list in the import configuration | The set of packages that are really in the program |

The export data is the load-bearing item. A Go front end that resolves a dependency by
reading its **source** will get a different answer than the build did whenever the
build used a different build tag, a different GOOS, or a vendored or replaced
dependency. Resolving against the **captured export data** is what makes the analysis
agree with the build.

**But the export data is usually gone by the time the analysis runs.** The toolchain
writes the import configuration into the build's *temporary* directory, and the build
deletes that directory when it ends. A capture taken from a build therefore names an
import configuration that no longer exists, and the export data it pointed at went with
it.

So the front end has to survive two states: the export data is there, and the analysis
agrees with the build exactly; or the export data is gone, and the dependencies are
resolved from their **source**, which is a second opinion about the types rather than
the build's own. The second state is the normal one for a Go build, so it is not an
error -- but it is **reported**, because the analysis is then reasoning about something
other than what the build compiled, and a reader has to know that. A capture that said
"no dependencies" instead would be a lie, and a silent one.

### 3.3 The capture must have a degradation path

**A capture that only exists on a successful build is not a capture.** The build can
fail for reasons that have nothing to do with the analysis: a code-generation step that
needs a tool that is not installed, a module fetch that needs the network, a
toolchain whose source tree is incomplete. When that happens, a capture that waits for
the build to succeed captures **nothing at all** -- and "nothing captured" is
indistinguishable from "nothing to analyse", which is exactly the outcome a scanner
must never produce silently.

So the capture has two layers:

| Layer | What it produces | What it knows |
| --- | --- | --- |
| Layer 1: the intercepted build | The `compile` and `link` invocations, verbatim | Everything, including the exact language version and build tags of each package |
| Layer 2: `go list -json -deps -export ./...` | One JSON object per package, carrying `ImportPath`, `Module`, `GoFiles`, `Imports` and `Deps`, plus the export data of every dependency | The package graph and the source files, without compiling anything |

Layer 2 does not need the build to work, and it does not need the network when the
module cache is warm. It is strictly weaker than layer 1 -- in particular it does not
tell the analysis which files a build tag selected -- but it is enough to produce a
TAC, and "a TAC with a stated degradation" beats "no TAC".

### 3.4 Where the capture goes

The capture belongs in the capture repository, not in this one: a Go adapter beside the
existing C, C++, MSVC, C# and JVM adapters, registered in the same option-mapping and
registry tables. The Go build's commands are not `csc`-shaped and are not `clang`-
shaped, so the generic adapter will not recognise them; a Go adapter has to recognise
the `compile` and `link` invocations and read their import configurations.

Two things have to be added to the shared model, because Go's build has no analogue of
them in a C compile:

- **An import configuration.** The compile command names a file; the file names the
  packages and their export data. That is a per-command attachment, like an
  `-importcfg` in the GCC world but with a different shape.
- **A package identity per compile command.** The C world identifies a translation
  unit by its source file; the Go world identifies it by its import path.

---

## 4. The Go TAC: five steps

### 4.1 Parse

`go/parser` over the captured source files, with comments retained. The parser is
where the file set matters: the file set must be the captured one, because the
captured one is the one the build used.

### 4.2 Type-check

`go/types` over the parsed files, with an `Importer` that resolves a dependency in
this order:

1. **A package of the project** is type-checked from its own files. The project's own
   packages are always in this set, so a call from one package of the project into
   another resolves even when nothing else does.
2. **A dependency with export data** is read from that export data, which is what the
   build compiled, so the types are the build's own.
3. **A dependency with no export data** is type-checked from its **source**, located in
   one of two places:

   - **The standard library**, under the **Go toolchain's root**. The root comes from the
     capture first -- the toolchain's compiler lives at
     `<root>/pkg/tool/<goos>_<goarch>/compile`, so the root is the part before
     `/pkg/tool/`, and it is the toolchain that *built this project* -- therefore the
     toolchain whose standard library the project was compiled against. A scan with no
     capture has no such command to read, so the environment's `GOROOT` and the `go`
     command on the `PATH` are the fallbacks.

     The environment's `GOROOT` is **not** the first answer, and it is only used when it names
     a **toolchain** -- a directory that holds the standard library's source, `<root>/src`. A
     directory alone is not enough: an emptied `GOROOT` that still holds the module cache
     looks exactly like a toolchain to a directory check, and the lookup that follows then
     finds no source at all. The environment's `GOROOT` is a leftover of whatever toolchain was
     installed last, it outlives the toolchain it names, and a `GOROOT` that points at a
     directory which no longer exists makes every standard library import fail **silently**:
     the imports go unresolved, the calls into the standard library lose their types, and the
     findings that depend on them disappear while the scan reports success. A directory that
     is not there looks exactly like a standard library that is not there.

   - **The module cache**, for a third-party dependency: `$GOMODCACHE/<module>@<version>/<package>`,
     where an uppercase letter in the module path is stored as `!` followed by the
     lowercase letter. `go/build` does **not** know this layout -- the cache is keyed by
     module path and version, while the import path names a package, which may be a
     package *inside* a module -- so the cache is searched directly, and the newest
     version of a module is the one read.

   This is the state a capture from a finished build is normally in -- see section 3.2 --
   and it costs a type check of the dependency's source rather than a read of its export
   data.

The order matters: the export data wins, because it is what the build compiled. The
source is the fallback, and a fallback that is *reported* rather than silent.

When neither place yields the dependency, the import fails **loudly**: the failure says
which of the two places was searched and why neither had it. A dependency whose types
are missing is a dependency whose findings are missing, so it cannot be reported as
"no findings".

The type checker's configuration carries the sizes for the target architecture
(`types.SizesFor`), because Go's `int` is 32-bit on some targets and 64-bit on
others, and the TAC's numeric kinds depend on it.

### 4.3 Lower

Per function: build the control-flow graph and lower the statements to TAC. The Go
specific lowerings are the four of section 2 -- the multi-result model, the `defer`
call and its record, the asynchronous `go` call, and the coarse type mapping -- plus
the ordinary control flow (`if`, `for`, `range`, `switch`, `select`).

A `select` statement is a branch over its cases, with the default case as the
fall-through. A `range` over a slice is an indexed loop; over a map it is a call on
the map's iterator; over a channel it is a receive.

### 4.4 Speak the wire format

The same length-prefixed JSON the C# sidecar speaks, with the same two operations:
`openProject` to establish the project, `translate` to translate one file.

**The version handshake has to exist before there are two sidecars.** The wire
protocol currently carries no version field, and an instruction kind this build does
not know silently becomes `HAVOC` -- which the engine never matches against the sink
catalogue, because it only looks up sinks on `CALL` and `UNINTERPRETED`
([CSHARP-FRONTEND-ANALYSIS.md section 4.7](CSHARP-FRONTEND-ANALYSIS.md)). With one
sidecar that is a maintenance risk; with two it is a source of findings that vanish
without a trace, because the two sidecars drift at different times.

### 4.5 Report every degradation

A Go construct that is not fully modelled must produce a `FunctionProblem`. It must
not be absent.

| The situation | What must be reported |
| --- | --- |
| A function returns two meaningful results | A degradation on that function, naming it |
| A package's export data could not be read | A degradation on every function in the package that used it |
| A source file in the captured file set produced no function | A degradation on the file, not silence |
| A build tag or a GOOS changed the file set | A degradation on the packages affected |
| The capture fell back to layer 2 | A project-level degradation, naming the packages whose language version or build tags are unknown |
| **No Go toolchain root could be located** | **A project-level degradation, naming the root that was tried.** The standard library's source is unreachable without it, so every standard library import is unresolved |
| **A dependency could be found neither in the toolchain nor in the module cache** | **A degradation naming the import path and both places that were searched** |

This is the same rule the C# path already learned the hard way: a degradation that is
not reported is a false negative with no trace.

---

## 5. The cross-check that keeps the capture honest

The capture and the front end are two independent readings of the same build, and they
can disagree. The check is the same shape as the one the Maven path already has
(`CompileCrossCheck`, `CompileDiscrepancy`):

| The check | What a discrepancy means |
| --- | --- |
| Every package in the link import configuration appears in the TAC | The analysis is missing a package that is in the binary |
| Every source file of every captured compile unit produced at least one function | The front end dropped a file |
| The number of functions in the TAC matches the number the front end reported | The translation lost something between the type checker and the TAC |
| The packages the capture saw and the packages the front end saw are the same set | The capture and the front end disagree about the program |

The last one is the one that catches a **stale capture**: a capture from a previous
build, or from a different configuration, produces a TAC for a program that is not the
one on disk. Nothing else would notice.

---

## 6. What this document does not claim

- **No claim about Go rule coverage.** `sink-families.yaml` has 82 families, 38 of
  them scoped by language, and none scoped to Go. The catalogue for Go is the largest
  single piece of work in this plan and it has not been started.
- **No claim that the multi-result model is complete.** It covers `(T, error)` and the
  discarded result. A function with two meaningful results is degraded, and reported.
- **No claim about concurrency.** No happens-before, no goroutine ordering, no
  channel-buffer reasoning.
- **No claim about cgo.** The C code behind a cgo directive is out of scope, and the
  Go side of the boundary is treated as an ordinary call.
- **No claim of an end-to-end measurement.** The design above is stated against the Go
  toolchain's documented behaviour and against two independent implementations of Go
  analysis. It has not been run end to end here, and the numbers that would prove it
  -- the captured command shapes, the export data format, the lowering's agreement
  with the compiler -- have to be measured before any of it is believed.

---

## Appendix: where the facts come from

| Claim | Source |
| --- | --- |
| 22 TAC opcodes; `RETURN` carries one value | `plumb-tac/.../ir/TacInstruction.java:59`, `:362` |
| 14 type kinds | `plumb-tac/.../ir/TacType.java` |
| The callee key is `owner.method/arity` | `plumb-tac/.../ir/CalleeKeyNormalizer.java:45` |
| `SourceLanguage` is serialised by name | `plumb-tac/.../ir/SourceLanguage.java:24-25` |
| Four languages have registered semantics | `plumb-dfa/.../semantics/LanguageSemanticsRegistry.java:77` |
| 82 families, 38 language-scoped, 0 for Go | `plumb-dfa/src/main/resources/sink-families.yaml` |
| The engine looks up sinks on `CALL` and `UNINTERPRETED` only | `plumb-dfa/.../taint/TaintedCheckerBase.java:1130`, `:1194` |
| The wire format has no version field | `plumb-frontend/.../csharp/CSharpTacFrontend.java:319-327`, `:533-566` |
| The C# route, and the catalogue's cost | [CSHARP-FRONTEND-ANALYSIS.md](CSHARP-FRONTEND-ANALYSIS.md) |
