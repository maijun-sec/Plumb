# The PHP sidecar wire protocol

This sidecar lowers PHP source into Plumb's TAC IR. It runs as a separate
process and is spoken to over stdio.

## Why a sidecar

Plumb is a JVM product, and there is no mature PHP parser in the JVM. The
reference PHP parser is a PHP program, and PHP is not installed on the
machines Plumb runs on. The `php-parser` package is a **port of that
reference parser to JavaScript**: its AST is the reference AST, so the node
shapes this sidecar reads are the ones the PHP community writes rules about.

So the analysis runs in a sidecar, which drives that parser.

## Running it locally

The sidecar is a Node program, and the Node program imports the PHP parser.
The parser is an npm dependency, and `node_modules` is not in version
control, so a fresh checkout installs it once:

```
cd plumb-frontend/php-sidecar
npm ci
```

Without that install the sidecar dies on its first request, and the analysis
fails with the sidecar's own last words in the message -- Node's
`Cannot find module` error for the parser the script imports. The same install
is what the end-to-end test needs: without it the test SKIPS instead of
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
  "sources": [ { "path": "C:/src/app/Mailer.php" } ]
}
```

| Field | Meaning |
|---|---|
| `sources` | The project's files, as absolute paths. |
| `sourceRoot` | The directory the project's files live under. Optional; it is what makes a module name a path rather than a file name. |

The parser reads the files from disk. A caller that holds a modified copy of
a file has to write it out before opening the project.

### `translate`

```json
{ "op": "translate", "protocolVersion": 1, "path": "<absolute>", "language": "PHP" }
```

The path names one file of the open project. The sidecar answers with the
functions declared in that file. `language` is `PHP`.

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
      "name": "send",
      "signature": "App\\Services\\Mailer::send(string,string):bool",
      "enclosingClass": "App\\Services\\Mailer",
      "precision": "SOURCE_FULL",
      "synthetic": false,
      "params": [ { "kind": "VAR", "name": "to", "type": { "kind": "STRING" } } ],
      "locals": [],
      "instructions": [
        {
          "kind": "ARRAYLOAD",
          "dest": { "kind": "VAR", "name": "t_t3", "isTemp": true },
          "lhs1": { "kind": "VAR", "name": "this", "type": { "kind": "CLASS", "name": "App\\Services\\Mailer" } },
          "lhs2": { "kind": "CONST", "constKind": "STRING", "value": "log" },
          "line": 17,
          "column": 17
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
| `signature` | The qualified PHP name, the parameter types and the return type: `App\Services\Mailer::send(string,string):bool`. |
| `enclosingClass` | The qualified class the method belongs to. Absent for a free function. |
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
an unhandled node became an unknown value, a `switch` compared its subject
differently -- so a function that was approximated did not come out whole.

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
{ "kind": "VAR",  "name": "cmd", "type": { "kind": "CLASS", "name": "App\\Mailer" } }
{ "kind": "CONST", "constKind": "STRING", "value": "select 1" }
{ "kind": "CONST", "constKind": "NULL" }
```

A variable's `type` is optional; an absent type means `UNKNOWN`. `isTemp`
marks a synthetic temporary.

The constant kinds are `INT`, `LONG`, `FLOAT`, `DOUBLE`, `BOOL`, `CHAR`,
`STRING` and `NULL`.

A type is one of the TAC's kinds:

```json
{ "kind": "CLASS",  "name": "App\\Services\\Mailer" }
{ "kind": "ARRAY",  "component": { "kind": "STRING" } }
{ "kind": "STRING" }
```

The kinds are `BOOLEAN`, `BYTE`, `SHORT`, `CHAR`, `INT`, `LONG`, `FLOAT`,
`DOUBLE`, `DECIMAL`, `STRING`, `CLASS`, `ARRAY`, `NULL` and `VOID`, plus
`UNKNOWN` for a type the sidecar could not resolve.

## The callee signature convention

The signature is the **PHP qualified name**, plus the arity:

```
<namespace-qualified name>/<arity>
```

| PHP source | Callee signature |
|---|---|
| `system($cmd)` in the global namespace | `\system/1` |
| `system($cmd)` in `namespace App\Services;` | `\system/1` |
| `helper($x)` in `namespace App\Services;`, declared there | `App\Services\helper/1` |
| `Mailer::send($to)` | `App\Services\Mailer::send/2` |
| `$mailer->send($to)` | `App\Services\Mailer::send/2` |

**Why the PHP name and not a Java-shaped one.** The sink catalogue matches
on the callee signature, and a rule about PHP code should be written in PHP
terms. A pattern that names `\system` matches `\system/1`; a pattern that
names `App\Services\Mailer::send` matches that method. The names are the
ones PHP itself writes.

**The namespace rules are PHP's own.** An unqualified **function** call
looks in the current namespace first and in the global namespace only when
the current one does not declare it -- so `system($cmd)` inside a namespace
is the global `system`, because the namespace does not declare one. An
unqualified **class** name has **no** global fallback: `new Logger()` inside
`namespace App\Services;` is `App\Services\Logger` and nothing else. A `use`
statement binds an alias to a qualified name, and `use function` binds a
function while a plain `use` binds a class; the two are resolved
independently.

**The receiver comes FIRST.** It is the TAC's own call convention:

```
args[0] = the receiver slot
args[1..] = the actual arguments
```

The slot is reserved whether or not the call has a receiver object. A free
function has none -- it is a function of a namespace, and there is no
instance to call it on -- so the slot holds the **namespace's name** as a
constant. The arity does not count it: the real arguments are at `args[1..]`.

**Why this is worth stating so loudly.** A sidecar that emits
`args = [tainted]` produces TAC that looks perfectly reasonable and matches
the sink catalogue -- and then the engine reads `args[0]`, finds the
*receiver*, finds nothing tainted there, and reports nothing. The finding is
absent while the scan reports success.

The arity counts the **declared parameters** of a function the file
declares, because that is the signature the sink catalogue matches on. A
function from elsewhere has no declaration the analysis can see, so the
arity is the number of arguments passed.

A call of a function the file declares is a `CALL`; a call of anything else
is an `UNINTERPRETED`: a black box whose internals the engine does not see.
Both are looked up in the sink catalogue.

## The keyed namespace: why a property is not a field

In PHP an object is a **namespace**, not a record:

- `$o->name` and `$o['name']` are the same location;
- `$items[0]` and `$items["0"]` are the same location;
- a key the analysis cannot resolve may be any key.

So a property access is lowered as a **container access with a string key** --
an `ARRAYLOAD` or an `ARRAYSTORE` whose index operand is the key -- and not as
a `FIELDLOAD` or a `FIELDSTORE`. A `FIELDLOAD` names a field, and a field is
not what a property is.

The engine's keyed namespace is what gives that access its identity: the
pair (object, key). A named key names one entry; a key the analysis cannot
resolve stands for every key.

**An assignment is a write, and the write has to reach the store.**
`$o->name = $value` is an `ARRAYSTORE` whose receiver is `$o` and whose key
is `name`, so that a later `$o->name` finds the value that was written. A
write that is dropped is a taint that stops halfway.

## The operators

A binary operator is a `BINOP`, and the operator table is keyed by the
operator's **spelling**, because that is what the parser hands out: an
operator node carries the operator's text, not a number.

That distinction is not pedantry. A table keyed by the wrong thing has to be
looked up with a key that is never there, and a lookup that always misses
does not look like a lookup at all: every binary expression would become an
unknown value, the taint that flowed through it would stop there, and every
function would still claim full precision.

| PHP operator | TAC operator |
|---|---|
| `+ - * / %` | `ADD SUB MUL DIV MOD` |
| `.` (concatenation) | `ADD` |
| `**` | `MUL`, and the difference is reported |
| `== === != !== <>` | `EQ NE` |
| `< <= > >=` | `LT LE GT GE` |
| `&& \|\| and or` | `LAND LOR` |
| `& \| ^ << >>` | `AND OR XOR SHL SHR` |
| `??` | `LOR`, and the difference is reported |
| `<=>` | `EQ`, and the difference is reported |
| `instanceof` | the `INSTANCEOF` instruction |

An assignment is not a `BINOP`. A plain assignment is a write; a compound
assignment (`+=` and friends) is a read, a `BINOP`, and then a write.

**The loose comparison is not reported.** PHP's `==` coerces before it
compares, and the TAC's equality test compares the values as they are. The
difference does not change how taint flows, so it is documented here rather
than reported on every function that uses `==`.

## What the sidecar does not model

Every one of these is reported as a problem on the function, and the affected
value becomes a `HAVOC` -- an unknown value. **Never a silent omission.**

| Construct | What happens |
|---|---|
| A closure or an arrow function used as a value | Reported as `SOURCE_LOWERED`; the value becomes a `HAVOC`. |
| A `match` expression whose arms are not comparisons | Reported as `UNHANDLED_NODE`. |
| A call whose callee is not a name | Reported as `UNHANDLED_NODE`; the call is a black box. |
| A method call whose receiver's type is unknown | Reported as `SOURCE_LOWERED`; the owner is the class the code is in. |
| A `switch` | Reported as `SOURCE_LOWERED`: PHP compares its subject loosely and the TAC does not. |
| A `foreach` over an array with string keys | Reported as `SOURCE_LOWERED`: the key variable holds the position. |
| A `goto` | Reported as `UNHANDLED_NODE`. |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes a `HAVOC`. |

## The synthetic stand-ins

A construct the TAC has no counterpart for becomes a call under a synthetic
owner, `<php>`:

| Construct | Callee signature |
|---|---|
| A backtick command (`` `ls $x` ``) | `<php>.shell/1` |
| An `include` or a `require` | `<php>.include/1` |
| The length of a collection in a `foreach` | `<php>.count/1` |

The owner is not a real namespace: a sink catalogue written against
`system/1` must not match `<php>.shell/1` by accident, and a checker that
prints the callee can see at a glance that the call is a stand-in.

**A backtick string is a COMMAND.** PHP runs it through the shell, so it is
lowered as a call to the shell stand-in rather than as an ordinary string.

## Building and running

```bash
npm install          # installs the parser
npm test             # the smoke test: the sidecar, end to end, over the wire
npm start            # the sidecar itself, reading frames from stdin
```

The sidecar is a Node program, so the Java side launches it with the Node
executable and the path to `src/main.mjs`.
