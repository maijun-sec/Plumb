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
      "memberKind": "METHOD",
      "precision": "SOURCE_FULL",
      "synthetic": false,
      "returnType": { "kind": "VOID" },
      "domain": null,
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
| `memberKind` | What kind of member the function is. |
| `precision` | `SOURCE_FULL`, `SOURCE_LOWERED` or `SOURCE_DEGRADED`. |
| `synthetic` | True for a function the frontend invented: the module's own code, a struct's initialiser. |
| `returnType` | The function's return type, as a type object. |
| `domain` | The value domain of the return type, or `null` when it has none. |
| `params` | The parameters, in declaration order. |
| `locals` | The locals. |
| `instructions` | The instruction stream, in source order. |
| `drops` | What was lowered less faithfully than the source says, and how much was lost. |
| `tryRegions` | The try / catch / finally regions of the function. |
| `classAnnotations` | The decorators the struct or class carries. |
| `methodAnnotations` | The decorators the method carries. |
| `fieldAnnotations` | The decorators and modifiers of each field, by field name. |
| `paramAnnotations` | The decorators of each parameter, by parameter name. |
| `annotationValues` | The literal arguments of the decorators, by decorator name. |
| `superType` | The base class, for a derived class. `null` otherwise. |
| `interfaces` | The interfaces the class implements. |
| `problems` | What could not be modelled. |

### `memberKind`

| Value | Meaning |
|---|---|
| `METHOD` | An ordinary method, or a free function. |
| `CONSTRUCTOR` | A constructor: the name is `<init>`. |
| `GETTER` | A getter. |
| `SETTER` | A setter. |
| `FIELD_INITIALIZER` | The struct's or class's own initialiser: the field initial values. |
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
| `construct` | The name of the construct, e.g. `implements`, `runtime-context`. |
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

Only the fields that the kind actually uses are present. The Java side ignores
any field it does not know.

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
{ "kind": "CLASS",  "name": "ProfilePage" }
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

## The operators

A binary operator is a `BINOP`, and the operator table is keyed by the
compiler's **operator token kind**, not by the operator's spelling.

The comparison operators keep their identity: `===` and `!==` are `EQ` and
`NE`, while `==` and `!=` -- which coerce their operands -- are `LOOSE_EQ` and
`LOOSE_NE`.

A short-circuit operator (`&&`, `||`, `??`) is not a `BINOP`: it is expanded
into a branch, because the right-hand side is only evaluated on one side of
it.

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
free function the file itself declares.

### When the owner is missing

A receiver whose type the compiler cannot name -- `any`, for instance -- has
no owner. The callee key then has **no owner section at all**: `method/1`,
not `NoClass.method/1`.

The file's own name used to stand in for it, and that was wrong: the owner of
a call is whatever the receiver is, and naming the file claims a module the
call never reached -- a file called `logging.ets` would then be the logging
module. The key says "the owner is missing", which matches nothing rather
than matching the wrong thing.

A global the language itself provides keeps its own name as the owner:
`eval(x)` is `eval.eval/1`, `require("./x")` is `require.require/1`. That is
**not** the same as the global object as a RECEIVER -- `console.log(x)` names
`console` because the method belongs to that object -- and the difference
matters: a call whose receiver nobody could type has no owner at all, so the
key tells the global and the untyped receiver apart.

This is the shape the sink catalogue is written against. The catalogue still
named the *stand-in* owner (`<unknown>.eval`) after the front end had stopped
producing it, so the global `eval` matched no pattern at all and a program
that evaluated tainted input scanned clean.

The same rule applies to a dynamic `import()`: the module it names is a
literal in the source, and the key carries that literal rather than a file
name.

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
| `strictNullChecks` | `true` | Whether a value can be null. Without it the compiler collapses `string \| null` into `string` before the translator sees it, and a value the source calls nullable becomes a value the source does not. The rest of the strict family is off: those are a matter of taste for the analysed program, and this one is a matter of fact. |

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

## What the sidecar models conservatively

Every one of these is recorded as a **drop** on the function, with the impact
that says how much was lost. The affected value stays in the stream -- it is
not dropped from the analysis -- and the drop says that it is no longer the
thing the source wrote.

| Construct | What happens |
|---|---|
| `implements` | A `TYPE` drop. The interface names are in `interfaces`; the relation itself has no run-time behaviour. |
| `import type` / `export type` | A `TYPE` drop. The module edge is erased at compile time. |
| `abilityContext` | A `VALUE` drop: the framework fills the object's properties in at run time. |
| A closure (a function used as a value) | A `STRUCTURE` drop; the value becomes a `HAVOC`. |
| `new Function(...)` | A `VALUE` drop: the body is decided at run time. |
| `import.meta` | A `VALUE` drop: the property values are filled in per environment. |
| A dynamic `import(x)` | A `SEMANTICS` drop: the module it names is decided at run time. |
| `await` | A `SEMANTICS` drop: the value is complete, the micro-task timing is not in the TAC. |
| `typeof` | A `VALUE` drop: the result is one of eight strings decided at run time. |
| A call whose callee does not resolve | A `SEMANTICS` drop, and the call is emitted as `UNINTERPRETED`: a black box whose result may be anything. |
| A power operator (`**`) | A `STRUCTURE` drop: it is modelled as a multiplication. |
| The rest of a destructuring pattern | A `STRUCTURE` drop: it is modelled as an empty object or array. |
| A regular expression | A `REGEX` constant. The pattern is kept; the flags are not analysed. |
| A class expression | Reported as `UNHANDLED_NODE`. |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes a `HAVOC`. |

**A drop is never a silent omission.** A construct that is not modelled
exactly is named, located and priced, so that a reader of the wire can tell
what the engine did not see.

A type-only declaration -- an interface, a type alias, an import -- is
**skipped**, not reported. It declares a type and has no run-time effect at
all, so there is nothing in it for a dataflow finding to be about.

## How a struct's state is modelled

A struct's properties are its state, and a method that reads a property has
to see what the property holds. So a struct produces two kinds of function:

- the struct's own **initialiser**, a synthetic function that holds every
  property's initial value, and
- one function per **method**.

**The initialisers are emitted ONCE.** Copying a property's initial value
into every method would run the same initialiser once per method, and an
initialiser that calls something would report that call once per method -- the
same effect, reported again and again. The initialiser stays in the struct's
own initialiser, where it is seen once.

A class that declares a constructor has the same rule with one difference: the
field initialisers run inside the constructor, after the base class's
constructor, because that is the order the language defines.

## Building and running

```bash
npm install          # installs the compiler and the SDK's declarations
npm test             # the smoke test: the sidecar, end to end, over the wire
npm start            # the sidecar itself, reading frames from stdin
```

The sidecar is a Node program, so the Java side launches it with the Node
executable and the path to `src/main.mjs`.
