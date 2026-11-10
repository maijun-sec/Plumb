# The TypeScript and JavaScript sidecar wire protocol

This sidecar lowers TypeScript and JavaScript source into Plumb's TAC IR. It
runs as a separate process and is spoken to over stdio.

## Why a sidecar

Plumb is a JVM product, and the TypeScript compiler is not a JVM program. It
is a native compiler with its own parser, its own binder and its own type
checker, and the types it reports are the ones the compiler itself sees. A
JVM re-implementation of them would be a second implementation that disagrees
with the real one -- and a type that resolves differently produces a
different callee signature, which is what the sink catalogue matches on.

So the analysis runs in a sidecar, which drives the compiler's own API.

The compiler's API is reached through its `unstable/sync` entry point: the
compiler runs as a server process and the sidecar is its client. That is why
the project is opened once and kept open -- opening it per file would
re-parse the standard library for every file, and the standard library is the
bulk of the work.

## Running it locally

The sidecar is a Node program, and the Node program imports the TypeScript
compiler. The compiler is an npm dependency, and `node_modules` is not in
version control, so a fresh checkout installs it once:

```
cd plumb-frontend/ts-sidecar
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
  "sources": [ { "path": "C:/src/app/routes/user.ts" } ]
}
```

| Field | Meaning |
|---|---|
| `sources` | The project's files, as absolute paths. |
| `sourceRoot` | The directory the project's files live under. Optional; it is what makes a module name a path rather than a file name. |

The compiler reads the files from disk. A caller that holds a modified copy
of a file has to write it out before opening the project.

### `translate`

```json
{ "op": "translate", "protocolVersion": 1, "path": "<absolute>", "language": "TYPESCRIPT" }
```

The path names one file of the open project. The sidecar answers with the
functions declared in that file. `language` is the source language of the
file: `TYPESCRIPT` or `JAVASCRIPT`.

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
  "diagnostics": [],
  "functions": [
    {
      "name": "handle",
      "signature": "app.handle(string):string",
      "enclosingClass": "UserStore",
      "precision": "SOURCE_FULL",
      "synthetic": false,
      "params": [ { "kind": "VAR", "name": "req", "type": { "kind": "CLASS", "name": "Object" } } ],
      "locals": [],
      "instructions": [
        {
          "kind": "ARRAYLOAD",
          "dest": { "kind": "VAR", "name": "t_t1", "type": { "kind": "STRING" }, "isTemp": true },
          "lhs1": { "kind": "VAR", "name": "req" },
          "lhs2": { "kind": "CONST", "constKind": "STRING", "value": "userId" },
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

## The function object

| Field | Meaning |
|---|---|
| `name` | The function's name. |
| `signature` | `owner.name(ParamTypes):Return`. |
| `enclosingClass` | The class the method belongs to. Absent for a free function. |
| `precision` | `SOURCE_FULL` or `SOURCE_LOWERED`. |
| `synthetic` | Always false: every function in the stream comes from the user's own code. |
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
an unhandled node became an unknown value, a `break` became a jump, an
unresolved callee became a black box -- so a function that was approximated
did not come out whole.

**A degradation never suppresses a finding.** The precision is a record of
the approximation, not a filter on the results; the analysis engine does not
read it to decide whether to report.

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
{ "kind": "CLASS",  "name": "UserStore" }
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
them: a method with one parameter is `UserStore.find/1`, with the receiver at
`args[0]`.

**Why this is worth stating so loudly.** A sidecar that emits
`args = [tainted]` produces TAC that looks perfectly reasonable and matches
the sink catalogue -- and then the engine reads `args[0]`, finds the
*receiver*, finds nothing tainted there, and reports nothing. The finding is
absent while the scan reports success.

The owner is the receiver's type for a method, and the module's name for a
free function.

## The keyed namespace: why a property is not a field

In JavaScript, Python and PHP an object is a **namespace**, not a record:

- `obj.userId` and `obj["userId"]` are the same location;
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
`obj.name = value` is an `ARRAYSTORE` whose receiver is `obj` and whose key is
`name`, so that a later `obj.name` finds the value that was written. A write
that is dropped is a taint that stops halfway.

## The operators

A binary operator is a `BINOP`, and the operator table is keyed by the
compiler's **operator token kind**, not by the operator's spelling.

That distinction is not pedantry. A table keyed by the spelling has to be
looked up with a string, and the compiler hands out a number -- and a lookup
that always misses does not look like a lookup at all: every binary
expression would become an unknown value, the taint that flowed through it
would stop there, and every function would still claim full precision.

An assignment is not a `BINOP`. A plain assignment is a write; a compound
assignment (`+=` and friends) is a read, a `BINOP`, and then a write.

## What the sidecar does not model

Every one of these is reported as a problem on the function, and the affected
value becomes a `HAVOC` -- an unknown value. **Never a silent omission.**

| Construct | What happens |
|---|---|
| A `break` or a `continue` | Reported as `SOURCE_LOWERED`. The loop's structure carries the control flow. |
| A function used as a value (a closure) | Reported as `SOURCE_LOWERED`; the value becomes a `HAVOC`. |
| A class expression | Reported as `UNHANDLED_NODE`. |
| A call whose callee does not resolve | The call is emitted as `UNINTERPRETED`: a black box whose result may be anything. |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes a `HAVOC`. |

## Building and running

```bash
npm install          # installs the compiler
npm test             # the smoke test: the sidecar, end to end, over the wire
npm start            # the sidecar itself, reading frames from stdin
```

The sidecar is a Node program, so the Java side launches it with the Node
executable and the path to `src/main.mjs`.
