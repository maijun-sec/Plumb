# The Python sidecar wire protocol

This sidecar lowers Python 3 source into Plumb's TAC IR. It runs as a
separate process and is spoken to over stdio.

## Why a sidecar

Plumb is a JVM product. Python's own parser is not a JVM program: it is
part of the reference implementation, it decides what Python 3 means, and
there is no second implementation of it in the JVM that would agree with
it on every corner case.

So the analysis runs in a sidecar, which drives **CPython's own `ast`
module**. That module is in the standard library, so the sidecar needs
nothing installed: no package manager, no wheel, no lock file.

**Python 3 only.** The sidecar parses with `ast.parse`, which is Python
3's grammar. A Python 2 source file is a syntax error there, and the
sidecar says so instead of half-reading it. The interpreter's own version
is reported in the `ping` response, and the frontend refuses one that is
not a Python 3.

## Running it locally

There is nothing to install: the sidecar parses with the interpreter's own
`ast` module, so a Python 3 on the PATH is the whole requirement. A machine
without one is missing an environment rather than carrying a broken product,
so the end-to-end test SKIPS instead of failing.

## Framing: length-prefixed JSON

Each message is a JSON object, UTF-8 encoded, framed as:

```
<4-byte big-endian length><payload>
```

The length counts the payload bytes only.

**Why not JSON Lines.** A source file may contain any byte in a string
literal, including a newline. A line-oriented protocol then has to either
escape the newlines or guess where a line ends, and a length prefix has no
such ambiguity: the frame boundary is explicit and cannot be spoofed by the
content.

**The protocol is on stdout and the chatter is on stderr.** The two are
separate pipes, so a stray diagnostic line can never corrupt the frame
stream.

## The version handshake

Every response carries two fields:

```json
{ "protocolVersion": 1, "irVersion": 1 }
```

| Field | Meaning |
|---|---|
| `protocolVersion` | The wire protocol version. Bump it when the request or response shape changes in a way an older frontend would misinterpret. |
| `irVersion` | The IR version this sidecar was built against. |

**A response that carries no `protocolVersion` is refused by the Java
side**, and so is one whose `protocolVersion` is not `1`, or whose
`irVersion` is present and not `1`. The sidecar therefore stamps both
fields on **every** response, including the failed ones -- a failure
response is exactly the one a caller reads when something has gone wrong,
and it is the worst place to discover that the handshake failed.

The check is symmetric: the sidecar **refuses a request whose
`protocolVersion` is missing or is not `1`**, and answers with `ok: false`
and an explanatory message rather than executing the operation.

## Operations (Java -> sidecar)

`op` is one of:

| `op` | Meaning |
|---|---|
| `openProject` | Remember the project's files and its root. |
| `translate` | Translate one file into TAC. |
| `closeProject` | Drop the project's state. |
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
  "sources": [ { "path": "C:/src/app/routes/user.py" } ]
}
```

| Field | Meaning |
|---|---|
| `sources` | The project's files, as absolute paths. |
| `sourceRoot` | The directory the project's files live under. It is what makes a module name a path rather than a file name. |

The sidecar reads the files from disk. A caller that holds a modified copy
of a file has to write it out before opening the project.

### `translate`

```json
{ "op": "translate", "protocolVersion": 1, "path": "<absolute>", "language": "PYTHON3" }
```

The path names one file. The sidecar answers with the functions declared
in that file. `language` is the source language of the file: `PYTHON3`.

### `ping`

```json
{ "op": "ping", "protocolVersion": 1 }
```

The answer carries the interpreter's own version:

```json
{ "op": "ping", "ok": true, "protocolVersion": 1, "irVersion": 1,
  "runtime": { "name": "python", "version": "3.14.3", "major": 3 } }
```

`major` is what the frontend checks: a sidecar on a Python 2 interpreter
is refused, because it would read Python 2 files as Python 3 ones.

## Responses (sidecar -> Java)

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
      "signature": "app.handle(str):str",
      "enclosingClass": "UserStore",
      "precision": "SOURCE_FULL",
      "synthetic": false,
      "params": [ { "kind": "VAR", "name": "request", "type": { "kind": "CLASS", "name": "str" } } ],
      "locals": [],
      "instructions": [
        {
          "kind": "ARRAYLOAD",
          "dest": { "kind": "VAR", "name": "t_t1", "type": { "kind": "STRING" }, "isTemp": true },
          "lhs1": { "kind": "VAR", "name": "request" },
          "lhs2": { "kind": "CONST", "constKind": "STRING", "value": "user_id" }
        }
      ],
      "problems": []
    }
  ]
}
```

Every response carries `ok`. A failure carries `ok: false` and an `error`
message, and the Java side turns it into a structured problem -- **never
into a silent "no findings"**.

### The keys the entry point has to add

The translator produces the type declarations of the module -- the classes,
what they extend, what they declare -- and the entry point has to carry them:

```json
{ "types": [ { "fqn": "UserStore", "simpleName": "UserStore",
               "superType": null, "interfaces": [], "annotations": [],
               "fields": [], "fieldAnnotations": [], "members": [],
               "traits": [], "modifiers": [], "metaclass": null,
               "isInterface": false, "isAbstract": false, "isEnum": false,
               "isRecord": false, "isSynthetic": false,
               "typeParameters": [] } ] }
```

The same goes for `capabilities`, `programDrops` and `problems`: the
translator has them, and the envelope is the entry point's.

## The function object

| Field | Meaning |
|---|---|
| `name` | The function's name. |
| `signature` | `owner.name(ParamTypes):Return`. |
| `enclosingClass` | The class the method belongs to. Absent for a free function. |
| `precision` | `SOURCE_FULL`, `SOURCE_LOWERED` or `SOURCE_DEGRADED`. **Derived from `drops`**, not from whether the function has a problem. |
| `synthetic` | True for a function the sidecar made up: the module's own code, and a class's body. |
| `memberKind` | What the function IS inside its owner: `MODULE`, `METHOD`, `CONSTRUCTOR`, `FIELD_INITIALIZER`, `GETTER`, `SETTER`, `DESTRUCTOR`. |
| `returnType` | The type the annotation says the function returns, as a type object -- not a string. |
| `domain` | The value domain of the return type: `{ "domain": "INTEGRAL", "bits": 64, "signed": true }`, or `{ "domain": "FLOATING", "bits": 53, "exponentBits": 11 }`. |
| `drops` | What could not be lowered faithfully, and what that cost. See [the drop object](#the-drop-object). |
| `parameters` | The declared parameters, with the facts a positional list of variables cannot carry. |
| `params` | The parameters, in declaration order. |
| `locals` | The locals. |
| `tryRegions` | The exception regions the function contains. |
| `branchRegions` | The dispatch regions: a `match`. |
| `scopeBindings` | The names this function binds in a scope that is not its own. |
| `classAnnotations` / `methodAnnotations` / `fieldAnnotations` / `paramAnnotations` / `annotationValues` | The five annotation channels the IR has. |
| `generatorStates` | The labels a generator resumes at. |
| `instructions` | The instruction stream, in source order. |
| `problems` | What could not be modelled. |

### `precision`

`SOURCE_FULL` means the function was lowered faithfully. `SOURCE_LOWERED`
means the SHAPE is not whole -- a loop's `else`, a set's order -- and
`SOURCE_DEGRADED` means the MEANING is not whole: an unknown call target, a
variable that is not known to exist.

**The precision is derived from the drops, not from the problems.** A drop's
`impact` decides it:

| The function's drops | The precision |
|---|---|
| A `SEMANTICS` drop | `SOURCE_DEGRADED` |
| No `SEMANTICS` drop, but a `STRUCTURE` one | `SOURCE_LOWERED` |
| Only `VALUE` and `TYPE` drops, or none | `SOURCE_FULL` |

**A degradation never suppresses a finding.** The precision is a record of
the approximation, not a filter on the results; the analysis engine does
not read it to decide whether to report.

**The same problem is reported once, with a count.** A file with fifty
calls to a name the analysis cannot resolve has one problem saying so
fifty times, not fifty problems.

### `parameters`

Each entry carries the facts the declaration states and a positional list of
variables cannot:

```json
{ "name": "b", "type": { "kind": "INT" }, "optional": true, "variadic": false,
  "byRef": false, "keywordOnly": true, "defaultValue": null }
```

### `scopeBindings`

Each entry is a name and the scope it belongs to:

```json
{ "name": "G", "kind": "CAPTURED", "byRef": false }
```

The kinds are `LOCAL`, `GLOBAL`, `STATIC_LOCAL`, `CAPTURED`, `PARAM` and
`DECLARE`. A `nonlocal` name is a `CAPTURED` one: it belongs to the scope
around the function.

### `tryRegions`

```json
{
  "tryStart": 65, "tryEnd": 66,
  "catchStart": 68, "catchEnd": 70,
  "catchLabels": ["L_CATCH_OSError4"],
  "catchTypes": [["OSError"]],
  "finallyLabel": "L_FINALLY5",
  "finallyStart": 76, "finallyEnd": 78,
  "syntheticFinally": false,
  "finallyExits": ["L_FINALLY5", "L_CATCH_OSError4"],
  "resourceVars": []
}
```

`catchTypes` holds every type a handler catches, so that a handler of
`A | B` does not lose the second one. `finallyExits` names the exits the
`finally` runs on: the try's own completion, every handler's completion, a
rethrow, and a `return`.

## The drop object

A drop is the machine-readable receipt of a construct that could not be lowered
faithfully. It has exactly six keys:

```json
{
  "construct": "lowered.Call",
  "detail": "the callee could not be resolved, so the call is modelled as a black box",
  "impact": "STRUCTURE",
  "line": 23,
  "column": 9,
  "scope": "FUNCTION"
}
```

| Key | Meaning |
|---|---|
| `construct` | What was not lowered, as a dotted name. |
| `detail` | Why, and what was done instead. |
| `impact` | `SEMANTICS`, `STRUCTURE`, `VALUE` or `TYPE`. |
| `line`, `column` | Where it happened. |
| `scope` | `FUNCTION` or `PROGRAM`. |

The drops are deduped by WHERE they happened, not only by what they say: two
calls of an unresolvable name are two different places the analysis is blind
at, and the reader is told about both.

## Instruction, operand and type encoding

The instruction kinds are the TAC's own, by name:

`MOVE`, `BINOP`, `UNARYOP`, `CALL`, `NEW`, `NEWARRAY`, `ARRAYLOAD`,
`ARRAYSTORE`, `FIELDLOAD`, `FIELDSTORE`, `INSTANCEOF`, `BRANCH`, `JUMP`,
`LABEL`, `RETURN`, `THROW`, `NOP`, `CATCH_HANDLER`, `FINALLY_ENTER`,
`NULL_CHECK`, `HAVOC`, `UNINTERPRETED`.

Only the fields that the kind actually uses are present. The Java side
ignores any field it does not know.

### The fields an instruction may carry

| Field | On | Meaning |
|---|---|---|
| `guard` | `BRANCH` | The STRUCTURED condition of the branch. |
| `labelRef` | `LABEL`, `JUMP`, `BRANCH`, `FINALLY_ENTER` | What the label IS: `{ "name": "L3", "kind": "LOOP_END", "scopeId": 2 }`. |
| `ownerType` | `ARRAYLOAD`, `ARRAYSTORE` | The class the entry belongs to, when the receiver's type is known. |
| `branchRegion` | `BRANCH` | The dispatch region the branch belongs to. |
| `argCount` | `CALL`, `NEW`, `UNINTERPRETED` | The number of arguments passed, keyword arguments included. |
| `namedArgs` | `CALL`, `UNINTERPRETED` | The arguments that were passed by name. |
| `spreadArgs` | `CALL`, `UNINTERPRETED` | The arguments that were unpacked. |
| `aliasOf` | `MOVE`, `ARRAYSTORE` | The location the written name aliases. |
| `deleteKey` | `ARRAYSTORE` | True when the store removes the key instead of writing it. |

### The guard

A guard is the condition of a branch in a shape the solver can use. Without one
the branch is a boolean temporary, and the path condition is empty on every
branch of the function.

```json
{ "kind": "Compare", "lhs": { "kind": "VAR", "name": "i" }, "op": "LT", "rhs": { "kind": "CONST", "constKind": "INT", "value": "3" } }
{ "kind": "Truthiness", "operand": { "kind": "VAR", "name": "rows" }, "sense": "TRUE" }
{ "kind": "Always", "value": true }
{ "kind": "PropertyPresence", "receiver": { "kind": "VAR", "name": "rows" }, "key": { "kind": "CONST", "constKind": "STRING", "value": "k" } }
```

The kinds are `Compare`, `Truthiness`, `Membership`, `TypeTest`, `Always` and
`PropertyPresence`.

**The guard describes the branch's TRUE edge.** A `Compare(NE, NULL)` guard
means the true edge is the one where the value is not null, and the false edge
is the one where it is. A guard that described the other edge would make the
solver's path condition the opposite of the code's.

A guard that cannot be rendered into a predicate is still carried, and the loss
is recorded as a `guard.unrenderable` drop.

### The label kinds

`FUNCTION_ENTRY`, `FUNCTION_EXIT`, `BLOCK`, `LOOP_HEAD`, `LOOP_BODY_END`,
`LOOP_END`, `SWITCH_HEAD`, `SWITCH_CASE`, `SWITCH_END`, `CATCH_ENTRY` and
`FINALLY_ENTRY`.

`LOOP_BODY_END` and `LOOP_END` are two different labels on purpose: a `break`
jumps to the body end, and the loop's `else` -- the code after the loop that
runs when it finished without a break -- hangs off the loop's end.

An operand is either a variable or a constant:

```json
{ "kind": "VAR",  "name": "cmd", "type": { "kind": "CLASS", "name": "str" } }
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
{ "kind": "ARRAY",  "component": { "kind": "UNKNOWN" } }
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

The slot is reserved whether or not the call has a receiver object.

**Who the owner is.** Python resolves a call by NAME, and the name is what
the catalogue matches on. So the imports are read, not merely executed:

| The source | The owner | The signature |
|---|---|---|
| `import os` then `os.system(cmd)` | `os` | `os.system/1` |
| `from os import system` then `system(cmd)` | `os` | `os.system/1` |
| `import numpy as np` then `np.array(x)` | `numpy` | `numpy.array/1` |
| `from os import path` then `path.join(a, b)` | `os.path` | `os.path.join/2` |
| `eval(x)` | `builtins` | `builtins.eval/1` |
| `store.find(key)` where `store` is a `UserStore` | `UserStore` | `UserStore.find/1` |
| `self.find(key)` | the enclosing class | `UserStore.find/1` |
| `UserStore.find(key)` -- a class of this module | `UserStore` | `UserStore.find/1` |
| `helper(x)` where `helper` is in this module | the module | `app.helper/1` |

**A class is not an instance.** A call on a class of the module names that
class as its owner, and the receiver slot holds the class object -- the
synthetic variable `t_cls_<Class>` -- and not an instance. A static method is
called on the class as well, and a class method's receiver is the class.

**A free function has no receiver object** -- it is a function of the
MODULE -- so the slot holds the **module's name** as a constant. A
function of another module has that module as its receiver, and the slot
holds it too.

**Why this is worth stating so loudly.** A sidecar that emits
`args = [tainted]` produces TAC that looks perfectly reasonable and matches
the sink catalogue -- and then the engine reads `args[0]`, finds the
*receiver*, finds nothing tainted there, and reports nothing. The finding
is absent while the scan reports success.

## The arity

The arity counts the **positional** parameters.

- A function the module declares: its declared positional parameters, and
  the receiver is not one of them. A method with one parameter is
  `UserStore.find/1`, with the receiver at `args[0]`.
- A STATIC method has no receiver at all: `@staticmethod def s(a)` is
  `K.stat/1`, and the arity is the number of the user's arguments.
- A CLASS method's receiver is the CLASS: `@classmethod def c(cls, a)` is
  `K.c/1`, and `args[0]` holds the class object.
- A function from elsewhere: the number of positional arguments the call
  passes. Python has no static types, so the declared parameters of an
  imported function are not visible here -- and a keyword argument is not
  a positional one, so `subprocess.run(cmd, shell=True)` is
  `subprocess.run/1`, which is what the API's own signature says.

## The keyed namespace: why an attribute is not a field

In Python an object is a **namespace**, not a record:

- `obj.user_id` and `obj["user_id"]` are the same location;
- `items[0]` and `items["0"]` are the same location;
- a key the analysis cannot resolve may be any key.

So an attribute access is lowered as a **container access with a string
key** -- an `ARRAYLOAD` or an `ARRAYSTORE` whose index operand is the key
-- and not as a `FIELDLOAD` or a `FIELDSTORE`. A `FIELDLOAD` names a
field, and a field is not what an attribute is.

The engine's keyed namespace is what gives that access its identity: the
pair (object, key). A named key names one entry; a key the analysis
cannot resolve stands for every key.

**An assignment is a write, and the write has to reach the store.**
`obj.name = value` is an `ARRAYSTORE` whose receiver is `obj` and whose key
is `name`, so that a later `obj.name` finds the value that was written. A
write that is dropped is a taint that stops halfway.

## The operators

A binary operator is a `BINOP`, and the operator table is keyed by the
AST's operator **node**, not by the operator's spelling.

That distinction is not pedantry. A table keyed by the spelling has to be
looked up with a string, and the AST hands out a node -- and a lookup that
always misses does not look like a lookup at all: every binary expression
would become an unknown value, the taint that flowed through it would
stop there, and every function would still claim full precision.

An assignment is not a `BINOP`. A plain assignment is a write; a compound
assignment (`+=` and friends) is a read, a `BINOP`, and then a write.

**A short-circuit operator is a branch chain.** `and` and `or` only evaluate
their right operand when the left one says it has to be, so the right operand
is on the branch that reaches it -- and that is also what makes the taint of
the right operand reach the result only when it is evaluated.

The TAC has no power operator and no matrix-multiplication operator. A
power is modelled as a multiplication and a matrix product as an unknown
value, and **both are reported**.

## The conservative modelling list

Every one of these is lowered into something the TAC can hold, and the
difference between that something and the source is **recorded as a drop** with
an `impact` that says what it cost. Nothing here becomes an unknown value
without a word, and nothing here is silently dropped.

| Construct | What is modelled | The drop |
|---|---|---|
| A lambda | A function of its own, plus a closure object that holds it and the values it captured. The captures live in the object and are NOT parameters: a parameter is a taint source, and a capture is not one. | -- |
| A generator (`yield`) | The yielded value is evaluated in the generator's own stream, so a sink inside it is a sink inside the generator. The value the generator produces is unknown. | `unhandled.Yield`, `SEMANTICS` |
| A `yield from` | The delegated value is evaluated. | `unhandled.YieldFrom`, `SEMANTICS` |
| A `match` | Every case body is lowered, and a match that matches nothing throws. | -- |
| A comprehension | Lowered into loops, with the filters lowered too, and its loop variable in a scope of its own. | -- |
| A set literal | Its entries are stored under indices that say nothing about which entry is which. | `lowered.Set`, `STRUCTURE` |
| A call whose callee does not resolve | The call is emitted as `UNINTERPRETED`: a black box whose result may be anything. | `lowered.Call`, `STRUCTURE` |
| A keyword argument | It is passed as a positional one. | `lowered.Call`, `STRUCTURE` |
| A starred argument | It is passed as one argument. | `lowered.Starred`, `STRUCTURE` |
| A default value that is not a constant | The expression is lowered, but a default is evaluated where the function is defined. | `lowered.Call`, `STRUCTURE` |
| A power | A multiplication. | `lowered.BinOp`, `STRUCTURE` |
| A matrix product | An unknown value. | `lowered.BinOp`, `STRUCTURE` |
| A handler of several types | The catch handler carries the first type; the region records them all. | `exc.catch.multiType`, `STRUCTURE` |
| An `except*` handler | It catches a GROUP of exceptions, and the TAC catches one. | `exc.handler.group`, `STRUCTURE` |
| An iteration whose iterable's type is not known | The loop is modelled both as an index over it and through the `__iter__` and `__next__` protocol. | `ctrl.iter.protocol`, `STRUCTURE` |
| A `global` or a `nonlocal` | A scope binding, and the writes routed to that scope. | -- |
| A dataclass's generated constructor | The fields are taken as parameters and written into the instance. | -- |
| A dataclass field whose default is computed | The default is evaluated when the class is created, so the generated constructor takes the field as a required argument. | `decl.dataclass.default`, `VALUE` |

Two things are lowered without a report, because the difference cannot
change what the analysis sees:

- **An `await`** produces the awaited value: the TAC has no coroutine.
- **A `with`** binds the value `__enter__` returns, and calls `__exit__` when
  the block is done. The context expression itself is lowered, so a call inside
  it -- `with open(path) as fp` -- is still a call.

## Building and running

There is nothing to build: the sidecar is a Python program and `ast` is
in the standard library.

```bash
python smoketest.py   # the smoke test: the sidecar, end to end, over the wire
python src/main.py    # the sidecar itself, reading frames from stdin
```

The sidecar is a Python program, so the Java side launches it with the
Python interpreter and the path to `src/main.py`.
