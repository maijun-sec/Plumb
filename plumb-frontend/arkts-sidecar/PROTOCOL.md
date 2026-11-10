# The ArkTS sidecar wire protocol

This sidecar lowers ArkTS source into Plumb's TAC IR. It runs as a separate
process and is spoken to over stdio.

## Why a sidecar, and why its own project

Plumb is a JVM product, and the ArkTS compiler is not a JVM program. ArkTS is
read by a fork of the TypeScript compiler that knows the ArkTS language: it
parses `struct`, it knows the ArkUI decorators, and it parses the declarative
UI DSL. The types it reports are the ones the compiler itself sees, and a type
that resolves differently produces a different callee signature -- which is
what the sink catalogue matches on.

So the analysis runs in a sidecar that drives the compiler's own API.

**The ArkTS sidecar is a separate npm project and shares no file with the
TypeScript sidecar.** The two are driven by different compilers with different
APIs, and a shared file would force one language's compiler into the other's
shape. The wire protocol is the same *shape* -- the same framing, the same
handshake, the same instruction kinds -- but the two sidecars own their own
copy of it, so neither can break the other.

## Running it locally

The sidecar is a Node program, and the Node program imports the ArkTS
compiler. The compiler is an npm dependency, and `node_modules` is not in
version control, so a fresh checkout installs it once:

```
cd plumb-frontend/arkts-sidecar
npm ci
```

Without that install the sidecar dies on its first request, and the analysis
fails with the sidecar's own last words in the message -- Node's
`Cannot find module` error for the compiler the script imports. The same
install is what the end-to-end test needs: without it the test SKIPS instead of
failing, because a machine without the toolchain is missing an environment
rather than carrying a broken product.

## Framing: length-prefixed JSON

Each message is a JSON object, UTF-8 encoded, framed as:

```
<4-byte big-endian length><payload>
```

The length counts the payload bytes only.

**Why not JSON Lines.** A source file may contain any byte in a string
literal, including a newline. A line-oriented protocol then has to either
escape newlines or guess. A length prefix has no such ambiguity: the frame
boundary is explicit and cannot be spoofed by the content.

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
and an explanatory message rather than executing the operation.

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
  "sourceRoot": "C:/src/app",
  "sources": [ { "path": "C:/src/app/entry/src/main/ets/pages/Index.ets" } ]
}
```

| Field | Meaning |
|---|---|
| `sources` | The project's files, as absolute paths. |
| `sourceRoot` | The directory the project's files live under. Optional; it is what makes a module name a path rather than a file name. |

The compiler reads the files from disk. A caller that holds a modified copy of
a file has to write it out before opening the project.

The response adds three fields that say what the compiler had to be told:

| Field | Meaning |
|---|---|
| `components` | How many component names the compiler was given. |
| `sdk` | `available` when the SDK's declarations were found, `absent` when they were not. |
| `addedComponents` | The project's own component names, present only when there were any. |

### `translate`

```json
{ "op": "translate", "protocolVersion": 1, "path": "<absolute>", "language": "ARKTS" }
```

The path names one file of the open project. The sidecar answers with the
functions declared in that file. `language` is the source language of the
file: `ARKTS`.

### `closeProject`

```json
{ "op": "closeProject", "protocolVersion": 1 }
```

### `ping`

```json
{ "op": "ping", "protocolVersion": 1 }
```

## Responses (sidecar → Java)

```json
{
  "op": "translate",
  "protocolVersion": 1,
  "irVersion": 1,
  "ok": true,
  "problems": [],
  "functions": [
    {
      "name": "build",
      "signature": "Index.build():any",
      "enclosingClass": "Index",
      "precision": "SOURCE_FULL",
      "synthetic": false,
      "params": [],
      "locals": [ { "kind": "VAR", "name": "this", "type": { "kind": "UNKNOWN" } } ],
      "instructions": [
        {
          "kind": "CALL",
          "calleeSig": "Index.Text/1",
          "dest": { "kind": "VAR", "name": "t_t2", "type": { "kind": "UNKNOWN" }, "isTemp": true },
          "args": [
            { "kind": "CONST", "constKind": "STRING", "value": "Index" },
            { "kind": "VAR", "name": "t_t1", "type": { "kind": "STRING" }, "isTemp": true }
          ],
          "line": 14,
          "column": 9
        }
      ],
      "problems": []
    }
  ]
}
```

Every response carries `ok`. A failure carries `ok: false` and an `error`
message, and the Java side turns it into a structured problem -- **never into
a silent "no findings"**.

### The file-level `problems`

`problems` at the top level of a `translate` response holds the problems that
belong to the FILE rather than to one function. Today that is the file's
syntax errors: a file that did not parse is a partial file, and the partial
file is still lowered -- dropping it would hide every taint in it -- so the
parse errors are reported instead.

**Only the syntactic diagnostics are reported.** A semantic diagnostic says
the program's types do not line up; that is the user's problem and not the
analysis's, and reporting it would bury the one thing that matters here --
that the file did not parse, so what came back is not the whole program.

## The function object

| Field | Meaning |
|---|---|
| `name` | The function's name. |
| `signature` | `owner.name(ParamTypes):Return`. |
| `enclosingClass` | The struct or class the method belongs to. Absent for a free function. |
| `precision` | `SOURCE_FULL` or `SOURCE_LOWERED`. |
| `synthetic` | True for a function the frontend invented: the module's own code, a struct's initialiser. |
| `params` | The parameters, in declaration order. |
| `locals` | The locals. |
| `instructions` | The instruction stream, in source order. |
| `problems` | What could not be modelled. |

### `precision`

`SOURCE_FULL` means the function was lowered faithfully. `SOURCE_LOWERED`
means a construct was lowered into something the TAC can hold but that is not
the construct itself.

A function carries `SOURCE_LOWERED` when it has at least one problem, and
`SOURCE_FULL` when it has none. A problem means something was approximated --
an unhandled node became an unknown value, a `break` became a jump, a switch
lost its case selection, an unresolved callee became a black box -- so a
function that was approximated did not come out whole.

**A degradation never suppresses a finding.** The precision is a record of
the approximation, not a filter on the results; the analysis engine does not
read it to decide whether to report.

## Instruction, operand and type encoding

The instruction kinds are the TAC's own, by name:

`MOVE`, `BINOP`, `UNARYOP`, `CALL`, `NEW`, `NEWARRAY`, `ARRAYLOAD`,
`ARRAYSTORE`, `FIELDLOAD`, `FIELDSTORE`, `INSTANCEOF`, `BRANCH`, `JUMP`,
`LABEL`, `RETURN`, `THROW`, `NOP`, `CATCH_HANDLER`, `FINALLY_ENTER`,
`NULL_CHECK`, `HAVOC`, `UNINTERPRETED`.

Only the fields that the kind actually uses are present. The Java side ignores
any field it does not know.

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
{ "kind": "CLASS",  "name": "ProfilePage" }
{ "kind": "ARRAY",  "component": { "kind": "STRING" } }
{ "kind": "STRING" }
```

The kinds are `BOOLEAN`, `BYTE`, `SHORT`, `CHAR`, `INT`, `LONG`, `FLOAT`,
`DOUBLE`, `DECIMAL`, `STRING`, `CLASS`, `ARRAY`, `NULL` and `VOID`, plus
`UNKNOWN` for a type the sidecar could not resolve.

## The callee signature convention

The signature is `owner.method/arity`, the same canonical form every other
Plumb frontend emits and the same form the sink catalogue matches on.

**The receiver comes FIRST.** It is the TAC's own call convention:

```
args[0] = the receiver slot
args[1..] = the actual arguments
```

The slot is reserved whether or not the call has a receiver object. A free
function has none -- it is a function of the MODULE, and there is no instance
to call it on -- so the slot holds the **module's name** as a constant. The
arity does not count it: the real arguments are at `args[1..]`.

The arity counts the **declared parameters**. The receiver is not one of
them: a method with one parameter is `ProfilePage.render/1`, with the receiver
at `args[0]`.

**Why this is worth stating so loudly.** A sidecar that emits
`args = [tainted]` produces TAC that looks perfectly reasonable and matches
the sink catalogue -- and then the engine reads `args[0]`, finds the
*receiver*, finds nothing tainted there, and reports nothing. The finding is
absent while the scan reports success.

The owner is the receiver's type for a method, and the module's name for a
free function.

### The one exception: a component of the UI DSL

A component call is `owner` = the module's name and the arity = the number of
arguments the DSL call passes.

The component's own type is a CLASS in the SDK's declarations -- the DSL
calls it as a function -- so its declared parameter count says nothing about
the call. The argument count is the honest one.

## The keyed namespace: why a property is not a field

In ArkTS an object is a **namespace**, not a record:

- `this.message` and `this["message"]` are the same location;
- `arr[0]` and `arr["0"]` are the same location;
- a key the analysis cannot resolve may be any key.

So a property access is lowered as a **container access with a string key** --
an `ARRAYLOAD` or an `ARRAYSTORE` whose index operand is the key -- and not as
a `FIELDLOAD` or a `FIELDSTORE`. A `FIELDLOAD` names a field, and a field is
not what a property is.

The engine's keyed namespace is what gives that access its identity: the
pair (object, key). A named key names one entry; a key the analysis cannot
resolve stands for every key.

**An assignment is a write, and the write has to reach the store.**
`this.message = value` is an `ARRAYSTORE` whose receiver is `this` and whose
key is `message`, so that a later `this.message` finds the value that was
written. A write that is dropped is a taint that stops halfway.

## What the ArkTS compiler is configured with, and why

Three compiler options decide whether an ArkTS file parses at all. They are
not tuning: without them the file does not parse, and what comes back is not
the whole program.

| Option | Value | What it decides |
|---|---|---|
| `ets.render.method` | `["build"]` | Which method is the render method. The declarative UI DSL is only parsed inside it. |
| `ets.render.decorator` | `["Builder", "LocalBuilder"]` | Which decorators introduce a builder function. |
| `ets.components` | the SDK's names plus the project's | Which names are components. A name that is not on the list is an ordinary call. |

**Where the component list comes from.** It is the SDK's own declarations
(`@arkts/declarations`) plus the names the project's own sources use. The
SDK's declarations do not name every component the platform offers, and they
cannot name the components a project defines for itself, so the project's
sources are scanned for the shape a component call has: a name that starts
with a capital, immediately followed by an argument list.

That scan over-approximates on purpose. A name that is really an ordinary
function becomes a component expression, and the only thing that changes is
the arity the callee signature carries -- the call is still a call, and the
taint still flows into its arguments.

The SDK's names are regenerated by `tools/generate-components.mjs`, which
reads the SDK's declarations, so the list is derived rather than written by
hand.

**The SDK's module mapping is loaded too.** The SDK ships the mapping from a
module name to the file that declares it, in its own `tsconfig.base.json`, and
this sidecar reads that file rather than copying the mapping. Without it an
`import` of an `@ohos.*` module resolves to nothing -- and an unresolved
import means an unknown type, which means a missing finding rather than a
missing precision.

## What the sidecar does not model

Every one of these is reported as a problem on the function, and the affected
value becomes a `HAVOC` -- an unknown value. **Never a silent omission.**

| Construct | What happens |
|---|---|
| A `break` or a `continue` | Reported as `SOURCE_LOWERED`. The loop's structure carries the control flow. |
| A `switch` | Reported as `SOURCE_LOWERED`. The cases are lowered in order, so the data flow survives and the case selection does not. |
| A function used as a value (a closure) | Reported as `SOURCE_LOWERED`; the value becomes a `HAVOC`. |
| A class expression | Reported as `UNHANDLED_NODE`. |
| A call whose callee does not resolve | The call is emitted as `UNINTERPRETED`: a black box whose result may be anything. |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes a `HAVOC`. |

A type-only declaration -- an interface, a type alias, an import -- is
**skipped**, not reported. It declares a type and has no run-time effect at
all, so there is nothing in it for a dataflow finding to be about.

## How a struct's state is modelled

A struct's properties are its state, and a method that reads a property has
to see what the property holds. So a struct produces two kinds of function:

- the struct's own **initialiser**, a synthetic function that holds every
  property's initial value, and
- one function per **method**, which starts with the property values it may
  read.

A property whose initialiser is a **pure** expression -- a literal, a name, a
read of another property -- is copied into every method. Copying it cannot
run anything twice, and it is what makes the method see the property's
value.

A property whose initialiser **calls** something is not copied. A call has
effects, and running it once per method would report the same effect once per
method. It stays in the struct's initialiser, where it is seen once.

## Building and running

```bash
npm install          # installs the compiler and the SDK's declarations
npm test             # the smoke test: the sidecar, end to end, over the wire
npm start            # the sidecar itself, reading frames from stdin
```

The sidecar is a Node program, so the Java side launches it with the Node
executable and the path to `src/main.mjs`.
