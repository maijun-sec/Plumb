# The Go sidecar wire protocol

The Java frontend cannot host Go's type resolver: Plumb is a JVM product, and
`go/parser`, `go/types` and the toolchain's export data are Go programs.
There is no JVM port of them, and re-implementing Go's type system in the JVM
would mean writing a second implementation that disagrees with the real one --
and a type that resolves differently produces a different callee signature,
which is what the sink catalogue matches on.

So the Go frontend launches a **sidecar**: a long-lived Go process that
translates Go source into Plumb's TAC and speaks a framed JSON protocol over
stdio.

This file is the contract. The C# sidecar's `PROTOCOL.md` is the reference
for the shared parts (the framing, the instruction kinds, the operand and
type encodings, the callee signature convention); this file documents what is
specific to Go.

---

## Why the sidecar is PROJECT-SCOPED and LONG-LIVED

A Go package's files only type-check **together**: a file that uses a
declaration from a sibling file of the same package does not compile on its
own. So the unit of analysis is the package, and the unit of translation is
the file -- but the file is translated against its package's type
information, never against its own.

Parsing and type-checking a package is the expensive part, and the Java side
asks for one file at a time. Doing the work once per package and reusing it
is what makes the approach usable at all.

---

## Framing: length-prefixed JSON

Each message is a JSON object, UTF-8 encoded, framed as:

```
<4-byte big-endian length><payload>
```

The length counts the payload bytes only.

**Why not JSON Lines.** A Go source file may contain any byte in a string
literal, including a newline. A line-oriented protocol then has to either
escape newlines or guess. A length prefix has no such ambiguity: the frame
boundary is explicit and cannot be spoofed by the content.

---

## The version handshake

Every response carries two fields:

```json
{ "protocolVersion": 1, "irVersion": 1 }
```

| Field | Meaning |
|---|---|
| `protocolVersion` | The wire protocol version. Bump it when the request or response shape changes in a way an older frontend would misinterpret. |
| `irVersion` | The IR version this sidecar was built against. |

**A response that carries no `protocolVersion` is refused by the Java side**,
and so is one whose `protocolVersion` is not `1`, or whose `irVersion` is
present and not `1`. The sidecar therefore stamps both fields on **every**
response, including the failed ones -- a failure response is exactly the one
a caller reads when something has gone wrong, and it is the worst place to
discover that the handshake failed.

The check is symmetric: the sidecar **refuses a request whose
`protocolVersion` is missing or is not `1`**, and answers with `ok: false`
and an explanatory message rather than executing the operation. A peer too
old to know about the handshake is exactly the case the handshake exists to
catch.

---

## Operations (Java → sidecar)

`op` is one of:

| `op` | Meaning |
|---|---|
| `openProject` | Create (or replace) the project. |
| `translate` | Translate one file of the open project into TAC. |
| `closeProject` | Drop the project and its cached state. |
| `ping` | Liveness check. Answers `{"op":"ping","ok":true}`. |
| `shutdown` | Exit the process. No response is written. |

### `openProject`

```json
{
  "op": "openProject",
  "protocolVersion": 1,
  "irVersion": 1,
  "projectId": "<opaque>",
  "goroot": "C:/go",
  "sources": [ { "path": "<absolute>", "text": "<the caller's text>" } ],
  "modules": [
    { "path": "example.com/lib", "dir": "C:/src/lib" }
  ],
  "packages": [
    {
      "importPath": "github.com/example/proj/internal/store",
      "dir": "C:/src/proj/internal/store",
      "files": ["C:/src/proj/internal/store/store.go"],
      "goVersion": "go1.23",
      "buildTags": ["linux", "amd64"],
      "imports": [
        { "importPath": "fmt", "exportData": "C:/gocache/fmt.a", "dir": "C:/src/proj/vendor/fmt" }
      ]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `packages` | The project's packages. A Go project is a SET of packages; a package is the compilation unit. |
| `packages[].importPath` | The package's import path. |
| `packages[].dir` | The package's source directory. |
| `packages[].files` | The package's files, as absolute paths. |
| `packages[].goVersion` | The Go version the package was built with, e.g. `go1.23`. |
| `packages[].buildTags` | The build tags the build ran with, e.g. `["linux","amd64"]`. A tag that names an operating system or an architecture becomes the build's GOOS or GOARCH; the rest are ordinary build tags. |
| `packages[].imports[]` | The package's dependencies. |
| `imports[].importPath` | The dependency's import path. |
| `imports[].exportData` | The dependency's **compile-time export data** file (a `.a` archive or an export data file). |
| `imports[].dir` | The dependency's source directory. Optional: it is used only when there is no export data. |
| `modules` | The **modules that live on disk**, each with the directory that holds it. Optional: it names the modules the toolchain's own lookup cannot find -- a REPLACED dependency, or one that belongs to another module of a workspace. |
| `sources` | The **caller's copy** of each file's text. |
| `goroot` | The **Go toolchain's root directory**. Optional; see below. |

#### `goroot`: the toolchain, not the environment

The standard library's source lives under the Go toolchain's root, and the
sidecar has to find it to type-check the project's dependencies.

It does **not** take that root from the environment. `GOROOT` is a leftover
of whatever toolchain was installed last, it outlives the toolchain it names,
and a `GOROOT` that points at a directory which no longer exists makes every
standard library import fail **silently**: the imports go unresolved, the
calls into the standard library lose their types, and the findings that
depend on them disappear while the scan reports success. A directory that is
not there looks exactly like a standard library that is not there.

The Java side derives the root from the captured command, whose compiler
lives at `<root>/pkg/tool/<goos>_<goarch>/compile`. That is the toolchain
which **built this project**, so it is the toolchain whose standard library
the project was compiled against.

The order the sidecar uses is:

1. the root the caller **named**;
2. the environment's `GOROOT`, but only when it names a **toolchain** -- a
   directory that holds the standard library's source, `<root>/src`. A
   directory alone is not enough: an emptied `GOROOT` that still holds the
   module cache looks exactly like a toolchain to a directory check, and the
   lookup that follows then finds no source at all;
3. the `go` command on the `PATH`, which lives in `<root>/bin`.

When none of them works, the failure is **reported**: a project-level
diagnostic says the standard library's source could not be located, and every
import that fails says so too. It is never a silent "no findings".

**This field does not change the protocol version.** An older frontend that
does not send it still works -- the sidecar falls back -- and an older
sidecar that ignores it still works.

#### `sources` wins over the disk

**A file whose absolute path appears in `sources` is parsed and type-checked
from that text.** Only a file that does not appear there is read from the
disk.

The reason is that `sources` is the caller's copy, and it may be an unsaved
editor buffer that differs from what is on disk. Reading the disk instead
would be a silent inconsistency: the findings would describe a version of the
file the caller never saw, while the scan reported success.

The build constraints of an inline file are judged by **its own text**, not by
the disk's copy, for the same reason.

#### The dependencies come from export data

The export data is what the build compiled. Re-type-checking a dependency's
source would mean asking the compiler a second time, and the second answer
can differ from the first -- a different set of build tags, a different Go
version, a different set of files in the directory -- and then the frontend
would be reasoning about types the program was never compiled against.

So the export data is preferred, and the dependency's source directory is
the fallback. The export data is read with the standard library's
`go/importer`, in the `gc` mode, with a lookup that resolves an import path
to the file the request named:

```go
importer.ForCompiler(fset, "gc", lookup)
```

**The toolchain that reads the export data must be able to read its format.**
Export data carries a version, and a toolchain that cannot read it says so;
that error is reported as a diagnostic, never swallowed.

The export data of a dependency names the packages it refers to but does not
carry their contents, so the sidecar walks the dependency closure and reads
those export data too. A package that is only named would otherwise be an
empty placeholder, and every type that mentions it would come out invalid.

#### Where a dependency's source comes from

When there is no export data -- and after a finished build there usually is
none, because the build deletes its temporary directory -- the dependency is
type-checked from its **source**. There are three places where that source
lives, and the sidecar looks in all of them.

| The dependency | Where its source is | How it is found |
|---|---|---|
| The **standard library** | `<goroot>/src/<import path>` | The toolchain's root -- see [`goroot`](#goroot-the-toolchain-not-the-environment) |
| A **third-party module** | `$GOMODCACHE/<module>@<version>/<package path>` | Searched directly |
| A **replaced or workspace module** | The directory the module's own build description names | Named by the Java side, in `modules` |

The module cache is searched directly because `go/build` does not know it: the
cache is keyed by **module path and version**, while the import path names a
**package**, which may be a package *inside* a module. Every prefix of the
import path is therefore tried as a module path, and the newest version of
the module is the one read.

One twist in the cache's layout: an uppercase letter in the module path is
stored as an exclamation mark followed by the lowercase letter, so
`github.com/Azure` is stored as `github.com/!azure`.

A **replaced** dependency (`replace other.example/mod => ../other-repo`) and a
dependency that belongs to another module of a **workspace** (`go.work`) are
not in the cache at all: they are directories on disk. `go/build` does not know
them either, so the sidecar could never find them on its own. The Java side
reads the module's own build description -- the only place the replacement is
written down -- and names the directories in `modules`; the sidecar then
resolves an import path against them by the **longest** matching module path.

When none of the three places yields the dependency, the import fails
**loudly**: the failure names the import path and says which places were
searched. A dependency whose types are missing is a dependency whose findings
are missing, so it is never reported as "no findings".

### `translate`

```json
{ "op": "translate", "protocolVersion": 1, "path": "<absolute>" }
```

The path names one file of the open project. The sidecar answers with the
functions declared in that file.

### `closeProject`

```json
{ "op": "closeProject", "protocolVersion": 1 }
```

### `ping`

```json
{ "op": "ping", "protocolVersion": 1 }
```

---

## Responses (sidecar → Java)

```json
{
  "op": "translate",
  "protocolVersion": 1,
  "irVersion": 1,
  "ok": true,
  "diagnostics": [
    { "severity": "error", "message": "...", "path": "...", "line": 12, "column": 5 }
  ],
  "functions": [
    {
      "name": "Write",
      "signature": "example.com/proj.Writer.Write([]byte):error",
      "enclosingClass": "Writer",
      "returnType": { "kind": "CLASS", "name": "error" },
      "precision": "SOURCE_LOWERED",
      "synthetic": false,
      "params": [ { "kind": "VAR", "name": "data", "type": { "kind": "ARRAY", "component": { "kind": "BYTE" } } } ],
      "locals": [],
      "instructions": [
        {
          "kind": "CALL",
          "calleeSig": "os.WriteFile/3",
          "dest": { "kind": "VAR", "name": "t1", "type": { "kind": "INT" }, "isTemp": true },
          "args": [ { "kind": "VAR", "name": "name" }, { "kind": "VAR", "name": "tainted" } ],
          "line": 14,
          "column": 9
        }
      ],
      "problems": [
        { "kind": "SOURCE_LOWERED", "message": "...", "nodeClass": "DeferStmt", "line": 14, "column": 3 }
      ],
      "deferredCalls": [
        { "calleeSig": "os.WriteFile/3", "args": [ { "kind": "VAR", "name": "name" } ], "line": 14, "column": 3 }
      ],
      "startsGoroutine": false
    }
  ]
}
```

Every response carries `ok`. A failure carries `ok: false` and an `error`
message, and the Java side turns it into a structured problem -- **never into
a silent "no findings"**.

---

## The function object

| Field | Meaning |
|---|---|
| `name` | The function's name. |
| `signature` | `owner.name(ParamTypes):Return`, in Go's own type text. |
| `enclosingClass` | The receiver type's short name. Absent for a package-level function. |
| `returnType` | The first declared result's type. |
| `precision` | `SOURCE_FULL` or `SOURCE_LOWERED`. |
| `synthetic` | Always false: every function in the stream comes from the user's own code. |
| `params` | The parameters, in declaration order. |
| `locals` | The locals. |
| `instructions` | The instruction stream, in source order. |
| `problems` | What could not be modelled. |
| `deferredCalls` | Go-specific. The deferred calls, with their source position. |
| `startsGoroutine` | Go-specific. Whether the function starts a goroutine. |

### `precision`

`SOURCE_FULL` means the function was lowered faithfully. `SOURCE_LOWERED`
means a construct was lowered into something the TAC can hold but that is not
the construct itself.

**A degradation never suppresses a finding.** The precision is a record of
the approximation, not a filter on the results; the analysis engine does not
read it to decide whether to report.

### `deferredCalls`

The `defer` calls of the function, with the source position of the `defer`
statement.

The deferred call is **also emitted as an ordinary `CALL` instruction** at the
`defer` statement. That is deliberate:

* Go evaluates a deferred call's **arguments at the `defer` statement**, so
  the arguments the inline call carries are exactly the arguments the
  deferred call will receive. The **taint is right**: a tainted value passed
  to a deferred call reaches the call in the instruction stream, where the
  engine can see it.
* What is **wrong** is the order: Go runs the call at the function's exit,
  and the instruction puts it at the `defer` statement.

A side table alone would be much worse: it is not part of the instruction
stream, so a `defer os.WriteFile(name, tainted, ...)` would leave no trace
in the TAC at all. The finding would be absent, and the scan would still
report success.

So the call is emitted **and** the side table is kept **and** the function's
precision is lowered with a `SOURCE_LOWERED` problem that says why.

### `startsGoroutine`

Whether the function contains a `go` statement.

**It does not mean any happens-before guarantee.** It does not say the
goroutine runs concurrently, does not order the goroutine against anything
else, and does not say that the code is free of a data race. The analysis
models no concurrency at all: the `go` statement is lowered to an ordinary
call in the ordinary order, and the function's precision is lowered with a
`SOURCE_LOWERED` problem that says so. A checker that reads the flag as a
concurrency guarantee would be inventing one the analysis does not have.

---

## Instruction, operand and type encoding

The instruction kinds are the TAC's own, by name:

`MOVE`, `BINOP`, `UNARYOP`, `CALL`, `NEW`, `NEWARRAY`, `ARRAYLOAD`,
`ARRAYSTORE`, `FIELDLOAD`, `FIELDSTORE`, `INSTANCEOF`, `BRANCH`, `JUMP`,
`LABEL`, `RETURN`, `THROW`, `NOP`, `CATCH_HANDLER`, `FINALLY_ENTER`,
`NULL_CHECK`, `HAVOC`, `UNINTERPRETED`.

Only the fields that the kind actually uses are present. The Java side
ignores any field it does not know.

An operand is either a variable or a constant:

```json
{ "kind": "VAR",  "name": "cmd", "type": { "kind": "CLASS", "name": "os.File" } }
{ "kind": "CONST", "constKind": "STRING", "value": "select 1" }
{ "kind": "CONST", "constKind": "NULL" }
```

A variable's `type` is optional; an absent type means `UNKNOWN`. `isTemp`
marks a synthetic temporary.

The constant kinds are `INT`, `LONG`, `FLOAT`, `DOUBLE`, `BOOL`, `CHAR`,
`STRING` and `NULL`.

A type is one of the TAC's kinds:

```json
{ "kind": "CLASS",  "name": "os.File" }
{ "kind": "ARRAY",  "component": { "kind": "INT" } }
{ "kind": "INT" }
```

The kinds are `BOOLEAN`, `BYTE`, `SHORT`, `CHAR`, `INT`, `LONG`, `FLOAT`,
`DOUBLE`, `DECIMAL`, `STRING`, `CLASS`, `ARRAY`, `NULL` and `VOID`, plus
`UNKNOWN` for a type the sidecar could not resolve.

---

## The callee signature convention

The signature is `owner.method/arity`, the same canonical form every other
Plumb frontend emits and the same form the sink catalogue matches on.

**The receiver comes FIRST.** It is the TAC's own call convention:

```
args[0] = the receiver slot
args[1..] = the actual arguments
```

The slot is reserved whether or not the call has a receiver object. A
package-level function has none -- `fmt.Println` is a function of the
PACKAGE, and there is no instance to call it on -- so the slot holds the
**package's import path** as a constant. The arity does not count it: the
real arguments of `fmt.Println` are at `args[1..]`.

The arity counts the **declared parameters**. The receiver is not one of
them: `func (f *os.File) Close() error` is `os.File.Close/0`, with the file
at `args[0]`, and `func Println(a ...any) int` is `fmt.Println/1`.

**Why this is worth stating so loudly.** A sidecar that emits
`args = [tainted]` produces TAC that looks perfectly reasonable and matches
the sink catalogue -- and then the engine reads `args[0]`, finds the
*receiver*, finds nothing tainted there, and reports nothing. The finding is
absent while the scan reports success.

The owner is the receiver type's qualified name for a method, and the
package's import path for a package-level function.

---

## The Go-specific lowering decisions

Every one of these is a **loss**, and each one is reported through the
function's `problems` array. Nothing is silently dropped.

### Multiple return values

Go's common shape is `(T, error)`.

| Go | TAC |
|---|---|
| `v, _ := f()` | one `CALL`, `dest` = `v`; the error is discarded on purpose, so nothing is reported |
| `v, err := f()` | one `CALL`, `dest` = `v`; the error is bound to an **unknown value** and the loss is reported |
| `return v, nil` | `RETURN v` |
| `return v, err` | `THROW err` -- the error is the function's exceptional exit, and `THROW` is exactly that |
| `return a, b` (two meaningful results) | `RETURN a`, with a `SOURCE_LOWERED` problem: the TAC's `RETURN` carries one value |

**The cost**: the TAC's `CALL` has one destination, so the second result
cannot be bound. The engine sees that the function may exit exceptionally,
but not what the error was, and not whether the error was tainted. The
function's precision is lowered.

### `defer`

See [`deferredCalls`](#deferredcalls) above.

**The cost**: the call is placed at the `defer` statement rather than at the
function's exit, so the ORDER is wrong. The taint is right.

### `go`

See [`startsGoroutine`](#startsgoroutine) above.

**The cost**: the call is placed in the ordinary order, and no happens-before
relation is modelled at all.

### Package-level variables and `init`

Go initialises a package's variables before its `init` functions run, and runs
those functions once per program. The TAC has no package initialiser of its
own, so a file's package-level variables and the bodies of its `init`
functions are lowered into **one function of their own**, named after the
file. The variables are bound under their **qualified** name (`pkg.name`), so
the files of a package read and write the same variable.

**The cost**: the order between the files of a package is not modelled -- each
file's declarations are initialised in the file that declares them -- and the
report says so.

### Function literals

A function literal becomes a **function of its own**, named after the file it
is in, and the variables it captured become **leading parameters** of that
function. A call through the literal passes the captured values, so the taint
reaches the body.

**The cost**: the TAC has no function value, so the literal is a name rather
than a value. A literal that is stored in a variable and called through that
variable is followed; a literal that is merely passed around is not the same
thing as the function it denotes.

### The type mapping

| Go | TAC |
|---|---|
| `*T` | `CLASS(T)` |
| `[]T`, `[N]T` | `ARRAY(T)` |
| `map[K]V` | `CLASS("map[K]V")` |
| `struct{...}` | `CLASS` |
| any interface, including `error` | `CLASS` |
| `string` | `STRING` |
| `bool` | `BOOLEAN` |
| `int8` / `uint8` | `BYTE` |
| `int16` / `uint16` | `SHORT` |
| `int`, `int32`, `uint`, `uint32` | `INT` |
| `int64`, `uint64`, `uintptr` | `LONG` |
| `float32` | `FLOAT` |
| `float64` | `DOUBLE` |
| generics | erased, as they are for the other frontends |

**The cost**: a pointer to `T` and a `T` are the same class, so the
distinction is lost -- but a separate `*T` class would never join with the
method's owner, so the taint would be tracked but never connected to the
call. A map, a channel and a struct are all `CLASS`, so the engine cannot
tell them apart. `int` is modelled as a 32-bit `INT` although it is 64 bits
on most targets, so an integer-range check on an `int` sees a narrower range
than the program has.

### The synthetic callee signatures

The runtime operations the sidecar models are not calls to anything the user
wrote, so they get a synthetic owner, `<go>`, that can never be confused
with a real package:

```
<go>.len/1        <go>.cap/1        <go>.append/2      <go>.copy/2
<go>.delete/2     <go>.make/1        <go>.print/1       <go>.recover/0
<go>.mapIndex/2   <go>.mapStore/3    <go>.mapIter/1     <go>.mapNext/1
<go>.mapKey/1     <go>.mapValue/1    <go>.chanSend/2    <go>.chanRecv/1
<go>.selectReady/1 <go>.slice/3      <go>.complex/2     <go>.real/1
<go>.imag/1       <go>.min/2         <go>.max/2         <go>.clear/1
<go>.close/1
```

The arity is the number of arguments the call had. The variadic ones
(`append`, `print`, `min`, `max`, `slice`) and `make` vary with the call:
`make`'s **type** argument is not an argument at all, and its size argument is
optional.

A `range` over a slice is an indexed loop, a `range` over a map is a call
on the map iterator, and a `range` over a channel is a single receive.

### The constructs the sidecar does not model

Each of these produces a problem; none is silent:

* **Method values.** A method used as a *value* -- `f := x.M` -- is a
  function the TAC has no way to name. (A method that is *called* is a call,
  and is lowered as one.)
* **Pointer dereferences.** There is no "load through a reference" opcode, so
  a dereference is modelled as a conversion of the pointer. The pointer's
  taint survives; the loaded value's own taint does not.
* **The address of a value.** The TAC has no pointer type.
* **`goto`.** A jump to an arbitrary label has no TAC counterpart.
* **A type switch's narrowing.** The bound variable keeps the original
  value's type.
* **A channel range's blocking read**, and a **`select`**'s readiness
  ordering.

---

## Building and running

### Building

```powershell
.\build.ps1
```

The script puts the executable at `bin/plumb-go-sidecar.exe`
(`bin/plumb-go-sidecar` elsewhere), which is where the Java side looks for
it. It also runs `go vet`.

The Go build cache and the module cache are placed **outside** the
repository, under the temporary directory: a build cache inside the working
tree is a large pile of generated files that has no business in version
control.

By hand:

```powershell
$env:GOCACHE = Join-Path $env:TEMP "plumb-go-sidecar-build\cache"
go build -trimpath -o bin/plumb-go-sidecar.exe .
```

The module has **no dependencies outside the Go standard library**, so the
build never needs the network.

### Testing

```powershell
go test ./...
```

### The smoke test

`smoketest.py` drives the sidecar over the real framing and checks the
contract end to end: the handshake, the inline source text, the `defer` and
`go` lowering, and the refusal of a request for another protocol version.

```powershell
python smoketest.py --sidecar bin\plumb-go-sidecar.exe
```

It needs a Python 3 interpreter and nothing else.

### Running the sidecar by hand

The sidecar reads frames from stdin and writes frames to stdout. Its own
chatter goes to stderr, because stdout carries the protocol and a stray line
there would corrupt it.

---

## Known limitations

* The sidecar's Go toolchain must be able to read the export data it is
  given. An export data version the toolchain does not know is reported as a
  diagnostic.
* A third-party dependency that has no export data is read from the **newest**
  version of its module in the module cache, not from the version the build
  used. The build's own version was named in the import configuration, which
  the build deleted. A `replace` directive pointing at a local directory, and
  a module of a `go.work` workspace, are found through the `modules` the Java
  side names -- see [where a dependency's source comes from](#where-a-dependencys-source-comes-from).
* A `defer` is placed at the `defer` statement rather than at the exit. The
  taint is right; the order is not.
* No concurrency is modelled. A `go` statement is an ordinary call.
* A function literal becomes a function of its own, named after the file it is
  in, and the variables it captured become leading parameters. The call is
  lowered, so the taint reaches the body; but the TAC has no function value,
  so a literal that is *passed around* rather than called is not the same
  thing as the function it denotes.
