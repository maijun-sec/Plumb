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

## The function object

| Field | Meaning |
|---|---|
| `name` | The function's name. |
| `signature` | `owner.name(ParamTypes):Return`. |
| `enclosingClass` | The class the method belongs to. Absent for a free function. |
| `precision` | `SOURCE_FULL` or `SOURCE_LOWERED`. |
| `synthetic` | True for a function the sidecar made up: the module's own code, and a class's body. |
| `params` | The parameters, in declaration order. |
| `locals` | The locals. |
| `instructions` | The instruction stream, in source order. |
| `problems` | What could not be modelled. |

### `precision`

`SOURCE_FULL` means the function was lowered faithfully. `SOURCE_LOWERED`
means a construct was lowered into something the TAC can hold but that is
not the construct itself.

A function carries `SOURCE_LOWERED` when it has at least one problem, and
`SOURCE_FULL` when it has none. A problem means something was
approximated -- an unhandled node became an unknown value, a callee became
a black box -- so a function that was approximated did not come out whole.

**A degradation never suppresses a finding.** The precision is a record of
the approximation, not a filter on the results; the analysis engine does
not read it to decide whether to report.

**The same problem is reported once, with a count.** A file with fifty
calls to a name the analysis cannot resolve has one problem saying so
fifty times, not fifty problems.

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
| `helper(x)` where `helper` is in this module | the module | `app.helper/1` |

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

The TAC has no power operator and no matrix-multiplication operator. A
power is modelled as a multiplication and a matrix product as an unknown
value, and **both are reported**.

## What the sidecar does not model

Every one of these is reported as a problem on the function, and the
affected value becomes a `HAVOC` -- an unknown value. **Never a silent
omission.**

| Construct | What happens |
|---|---|
| A lambda | Reported as `SOURCE_LOWERED`; the value becomes a `HAVOC`. |
| A generator (`yield`) | Reported as `UNHANDLED_NODE`; the value becomes a `HAVOC`. |
| A match statement | Reported as `UNHANDLED_NODE`; the subject is still lowered. |
| A comprehension | Reported as `SOURCE_LOWERED`: a comprehension has a scope of its own, so the entries after the first are not modelled. |
| A call whose callee does not resolve | The call is emitted as `UNINTERPRETED`: a black box whose result may be anything. |
| A keyword argument | Reported as `SOURCE_LOWERED`: it is passed as a positional one. |
| A starred argument | Reported as `SOURCE_LOWERED`: it is passed as one argument. |
| A default value that is not a constant | Reported as `SOURCE_LOWERED`: a default is evaluated where the function is defined. |
| A set literal | Reported as `SOURCE_LOWERED`: a set has no order. |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes a `HAVOC`. |

Two things are lowered without a report, because the difference cannot
change what the analysis sees:

- **An `await`** produces the awaited value: the TAC has no coroutine.
- **A `with`** binds the context manager's value to the target: the TAC
  has no `__enter__` and no `__exit__`. The context expression itself is
  lowered, so a call inside it -- `with open(path) as fp` -- is still a
  call.

## Building and running

There is nothing to build: the sidecar is a Python program and `ast` is
in the standard library.

```bash
python smoketest.py   # the smoke test: the sidecar, end to end, over the wire
python src/main.py    # the sidecar itself, reading frames from stdin
```

The sidecar is a Python program, so the Java side launches it with the
Python interpreter and the path to `src/main.py`.
