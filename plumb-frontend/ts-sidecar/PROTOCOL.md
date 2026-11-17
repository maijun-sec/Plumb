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
      "memberKind": "METHOD",
      "precision": "SOURCE_FULL",
      "synthetic": false,
      "returnType": { "kind": "STRING" },
      "domain": null,
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
      "drops": [],
      "tryRegions": [],
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
| `memberKind` | What kind of member the function is. |
| `precision` | `SOURCE_FULL`, `SOURCE_LOWERED` or `SOURCE_DEGRADED`. |
| `synthetic` | Whether the function is the sidecar's own rather than the user's. |
| `returnType` | The function's return type, as a type object. |
| `domain` | The value domain of the return type, or `null` when it has none. |
| `params` | The parameters, in declaration order. |
| `locals` | The locals. |
| `instructions` | The instruction stream, in source order. |
| `drops` | What was lowered less faithfully than the source says, and how much was lost. |
| `tryRegions` | The try / catch / finally regions of the function. |
| `classAnnotations` | The decorators the class carries. |
| `methodAnnotations` | The decorators the method carries. |
| `fieldAnnotations` | The decorators and modifiers of each field, by field name. |
| `paramAnnotations` | The decorators of each parameter, by parameter name. |
| `annotationValues` | The literal arguments of the decorators, by decorator name. |
| `superType` | The base class, for a derived class. `null` otherwise. |
| `interfaces` | The interfaces the class implements. |
| `problems` | What could not be modelled. |

The decorator channels carry the decorator's NAME. The same name is in the
callee key of the decorator's CALL, so a rule can match a decorator either
way -- and the call is what carries the decorator's arguments and their
taint. A decorator that is called -- `@trace("tag")` -- is named after the
factory it calls.

### `memberKind`

| Value | Meaning |
|---|---|
| `METHOD` | An ordinary method, or a free function. |
| `CONSTRUCTOR` | A constructor: the name is `<init>`. |
| `GETTER` | A getter. |
| `SETTER` | A setter. |
| `FIELD_INITIALIZER` | The class's own initialiser: the field initial values. |
| `STATIC_INITIALIZER` | A static block: the class's own initialiser, run once. |

### `precision`

`SOURCE_FULL` means the function was lowered faithfully. `SOURCE_LOWERED`
means a construct was lowered into something the TAC can hold but that is not
the construct itself. `SOURCE_DEGRADED` means something was lost that the
engine's results depend on -- the value's meaning, not merely its shape.

The precision is derived from the function's `drops`:

| Drop impact | Precision |
|---|---|
| `SEMANTICS` | `SOURCE_DEGRADED` |
| `STRUCTURE` | `SOURCE_LOWERED` |
| `VALUE`, `TYPE` | `SOURCE_FULL` (the shape is intact; only a value or a type fact is missing) |

A function with a problem is `SOURCE_LOWERED` as well: a problem means
something was approximated -- an unhandled node became an unknown value, an
unresolved callee became a black box -- so a function that was approximated did
not come out whole.

**A degradation never suppresses a finding.** The precision is a record of
the approximation, not a filter on the results; the analysis engine does not
read it to decide whether to report.

### `drops`

A drop records one construct that was modelled less faithfully than the source
says. It is an accounting entry, not an instruction: the value is still in the
stream, and the engine still sees it.

| Field | Meaning |
|---|---|
| `construct` | The name of the construct, e.g. `implements`, `closure`, `runtime-context`. |
| `detail` | What was lost, in one sentence. |
| `impact` | How much was lost: `SEMANTICS`, `STRUCTURE`, `VALUE` or `TYPE`. |
| `line` | The line the construct is on. |
| `column` | The column the construct is on. |
| `scope` | `FUNCTION` or `PROGRAM`. |

### `tryRegions`

One entry per try statement. Each entry says where the parts are:

| Field | Meaning |
|---|---|
| `tryStart`, `tryEnd` | The instruction range of the try body. |
| `catchStart`, `catchEnd` | The instruction range of the catch body, or `-1`. |
| `catchLabels` | The entry label of each catch clause. |
| `catchTypes` | The type each catch clause names, or `*` when it names none. |
| `finallyLabel` | The label the finally block is entered at, or `null`. |
| `finallyStart`, `finallyEnd` | The instruction range of the finally body, or `-1`. |
| `syntheticFinally` | Whether the finally block is the sidecar's own. |
| `finallyExits` | Every label that leaves the region through the finally block. |
| `resourceVars` | The variables a `using` declaration owns. |

## Instruction, operand and type encoding

The instruction kinds are the TAC's own, by name:

`MOVE`, `BINOP`, `UNARYOP`, `CALL`, `NEW`, `NEWARRAY`, `ARRAYLOAD`,
`ARRAYSTORE`, `FIELDLOAD`, `FIELDSTORE`, `INSTANCEOF`, `BRANCH`, `JUMP`,
`LABEL`, `RETURN`, `THROW`, `NOP`, `CATCH_HANDLER`, `FINALLY_ENTER`,
`NULL_CHECK`, `HAVOC`, `UNINTERPRETED`.

Only the fields that the kind actually uses are present. The Java side
ignores any field it does not know.

Four instruction fields carry facts that are not operands:

| Field | Meaning |
|---|---|
| `guard` | What a `BRANCH` tests. |
| `labelRef` | What a `LABEL` marks. |
| `ownerType` | The class that owns the field, on a `FIELDLOAD` or a `FIELDSTORE`. |
| `fieldName` | The field's name, on a `FIELDLOAD` or a `FIELDSTORE`. |

A `guard` is one of:

| Kind | Meaning |
|---|---|
| `Truthiness` | The operand is tested for truthiness. It carries `operand`. |
| `Compare` | Two operands are compared. It carries `lhs`, `op` and `rhs`. |
| `PropertyPresence` | A key is tested for presence in an object. It carries `receiver` and `key`. |

A `labelRef` is one of:

| Kind | Meaning |
|---|---|
| `LOOP_HEAD` | The head of a loop. |
| `LOOP_END` | The end of a loop. |
| `SWITCH_CASE` | The body of a switch case. |
| `SWITCH_END` | The end of a switch. |
| `BLOCK` | The entry of a block. |
| `CATCH` | The entry of a catch clause. It carries the caught name. |
| `FINALLY_ENTRY` | The entry of a finally block. |

An operand is either a variable or a constant:

```json
{ "kind": "VAR",  "name": "cmd", "type": { "kind": "CLASS", "name": "os.File" } }
{ "kind": "CONST", "constKind": "STRING", "value": "select 1" }
{ "kind": "CONST", "constKind": "NULL" }
{ "kind": "CONST", "constKind": "REGEX", "value": "ab+c" }
```

A variable's `type` is optional; an absent type means `UNKNOWN`. `isTemp`
marks a synthetic temporary.

The constant kinds are `INT`, `LONG`, `FLOAT`, `DOUBLE`, `BOOL`, `CHAR`,
`STRING`, `NULL` and `REGEX`. A `REGEX` constant is a regular expression
literal: it is an immutable value, not an unknown one.

A type is one of the TAC's kinds:

```json
{ "kind": "CLASS",  "name": "UserStore" }
{ "kind": "ARRAY",  "component": { "kind": "STRING" } }
{ "kind": "STRING" }
{ "kind": "STRING", "nullable": true, "unionMembers": [ { "kind": "NULL" }, { "kind": "STRING" } ] }
{ "kind": "DOUBLE", "domain": { "kind": "Floating", "bits": 53, "exponentBits": 11 } }
{ "kind": "BIGINT", "domain": { "kind": "Unbounded" } }
```

The kinds are `BOOLEAN`, `BYTE`, `SHORT`, `CHAR`, `INT`, `LONG`, `FLOAT`,
`DOUBLE`, `DECIMAL`, `STRING`, `CLASS`, `ARRAY`, `NULL` and `VOID`, plus
`UNKNOWN` for a type the sidecar could not resolve and `BIGINT` for an
arbitrary-precision integer.

Three type fields say what the type does not say on its own:

| Field | Meaning |
|---|---|
| `nullable` | The value can be null. |
| `unionMembers` | The members of a union. The type itself is the first non-null member. |
| `domain` | The range of the values: `Floating` for a double, `Unbounded` for a bigint, `Dynamic` for a type nothing is known about. |

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
free function the file itself declares.

### When the owner is missing

A receiver whose type the compiler cannot name -- `any`, for instance -- has
no owner. The callee key then has **no owner section at all**: `method/1`,
not `app.method/1`.

The file's own name used to stand in for it, and that was wrong: the owner of
a call is whatever the receiver is, and naming the file claims a module the
call never reached. The key says "the owner is missing", which matches nothing
rather than matching the wrong thing.

A global the language itself provides keeps its own name as the owner:
`eval(x)` is `eval.eval/1`, `require("./x")` is `require.require/1`. That is
**not** the same as the global object as a RECEIVER -- `console.log(x)` names
`console` because the method belongs to that object -- and the difference
matters: a call whose receiver nobody could type has no owner at all, so the
key tells the global and the untyped receiver apart.

This is the shape the sink catalogue is written against. An earlier version
of this document claimed the catalogue was written against a *stand-in* owner
(`<unknown>.eval`); it was not -- the catalogue still named the stand-in after
the front end had stopped producing it, so the global `eval` matched no
pattern at all and a program that evaluated tainted input scanned clean. The
finding was absent, and the scan reported success.

A dynamic `import()` carries the module it names -- the literal in the source
-- as the owner, rather than a file name.

## The decorators

A decorator is a call, and it is a call **where the thing it decorates is
defined**:

```
@trace
method(a: string): string { ... }

@trace("tag")
function topLevel(a: string): string { ... }
```

The decorator's call is not in the body of the function it decorates. A
decorator runs once, where the declaration is; a call in the body would run
it once per call of the decorated function instead.

Where the call goes depends on what is decorated:

| Decorated | The call is in |
|---|---|
| A method, a getter, a setter or a field | The class's own stream: the constructor's, or the field initialiser's. |
| The class itself | The class's own stream, the same one. |
| A top-level function | The module's own stream: the synthetic `__top` function. |
| A function nested in another function | The enclosing function's body -- the place the nested function is declared. |

The call has the shape of every other call:

```
calleeSig = <owner>.<decoratorName>/<arity>
args      = [ the constant owner slot, the decorator's value, the holder ]
```

- The **owner** is the decorator's own: the module it was imported from, or
  the module that declares it. It is never a placeholder: an owner that
  names nothing at all would make a rule that matches on a decorator's name
  match nothing too.
- The **arity** counts the decorator's own arguments plus the decorated
  thing itself: `@trace` is `trace/1`, `@trace("tag")` is `trace/2`.
- The **first argument** is the receiver slot, and it holds the owner as a
  constant -- the same slot every other call has.
- The **second argument** is the decorator's own value. A decorator that is
  called -- `@trace("tag")` -- runs its factory first, so the TAC has two
  calls: the factory's, and the decorator's.
- The **third argument** is the **holder**: the variable that holds the
  decorated thing, and that holds the decorator's result afterwards. A
  `MOVE` writes the result back to it, because a decorator is what the
  name holds once it has run.

A **parameter decorator** has no TAC lowering: it runs where the function is
defined and it replaces nothing, so there is no holder for it to write back
to. It is reported as `UNHANDLED_NODE` rather than dropped quietly.

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

The comparison operators keep their identity: `===` and `!==` are `EQ` and
`NE`, while `==` and `!=` -- which coerce their operands -- are `LOOSE_EQ` and
`LOOSE_NE`. The two are different facts about a value, and a catalogue that
matches on the operator cannot tell them apart otherwise.

A short-circuit operator (`&&`, `||`, `??`) is not a `BINOP` either: it is
expanded into a branch, because the right-hand side is only evaluated on one
side of it.

## What the sidecar models conservatively

Every one of these is recorded as a **drop** on the function, with the impact
that says how much was lost. The affected value stays in the stream -- it is
not dropped from the analysis -- and the drop says that it is no longer the
thing the source wrote.

| Construct | What happens |
|---|---|
| `implements` | A `TYPE` drop. The interface names are in `interfaces`; the relation itself has no run-time behaviour. |
| `import type` / `export type` | A `TYPE` drop. The module edge is erased at compile time. |
| A closure (a function used as a value) | A `STRUCTURE` drop; the value becomes a `HAVOC`. |
| `eval(x)` and `new Function(...)` | A `VALUE` drop: the result is whatever the run-time string says. |
| `import.meta` | A `VALUE` drop: the property values are filled in per environment. |
| A dynamic `import(x)` | A `SEMANTICS` drop: the module it names is decided at run time. |
| `await` | A `SEMANTICS` drop: the value is complete, the micro-task timing is not in the TAC. |
| `typeof` | A `VALUE` drop: the result is one of eight strings decided at run time. |
| A call whose callee does not resolve | A `SEMANTICS` drop, and the call is emitted as `UNINTERPRETED`: a black box whose result may be anything. |
| A power operator (`**`) | A `STRUCTURE` drop: it is modelled as a multiplication. |
| The rest of a destructuring pattern | A `STRUCTURE` drop: it is modelled as an empty object or array. |
| A regular expression | A `REGEX` constant. The pattern is kept; the flags are not analysed. |
| A class expression | Reported as `UNHANDLED_NODE`. |
| A parameter decorator | Reported as `UNHANDLED_NODE`: it runs where the function is defined and it replaces nothing. |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes a `HAVOC`. |

**A drop is never a silent omission.** A construct that is not modelled
exactly is named, located and priced, so that a reader of the wire can tell
what the engine did not see.

## Building and running

```bash
npm install          # installs the compiler
npm test             # the smoke test: the sidecar, end to end, over the wire
npm start            # the sidecar itself, reading frames from stdin
```

The sidecar is a Node program, so the Java side launches it with the Node
executable and the path to `src/main.mjs`.
