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

### The keys the entry point has to add

The translator produces the type declarations of the file -- the classes, what
they extend, what they declare -- and the entry point has to carry them:

```json
{ "types": [ { "fqn": "App\\Services\\Mailer", "simpleName": "Mailer",
               "superType": null, "interfaces": [], "annotations": [],
               "fields": [], "fieldAnnotations": [], "members": [],
               "traits": [], "modifiers": [], "metaclass": null,
               "isInterface": false, "isAbstract": false, "isEnum": false,
               "isRecord": false, "isSynthetic": false } ] }
```

The same goes for `capabilities`, `programDrops` and `problems`: the
translator has them, and the envelope is the entry point's.

## The parser's blind spot: `"$_GET['x']"`

`php-parser` 3.7.0 cannot read `"$_GET['x']"`. The lexer puts the quote of the
subscript into the "whitespace" class of the `ST_VAR_OFFSET` state, so the
parser sees a string token where it expects a name, and **the whole file fails
to parse** -- which means every sink in that file disappears from the scan
while the scan reports success.

The fix is a rewrite, on the sidecar's side: the simple interpolation becomes
the complex one, `"$_GET['x']"` becomes `"{$_GET['x']}"`, and the complex form
goes through a different lexer state where the quote is an ordinary string
token.

**The rewrite makes the source longer, so the positions move.** The shift is
recorded and taken back when a position is read, so that a finding still points
at the line the author wrote.

The three writings -- `"$_GET['x']"`, `"$a[0]"` and `"{$a['x']}"` -- all
parse, and all produce the same shape.

## The function object

| Field | Meaning |
|---|---|
| `name` | The function's name. |
| `signature` | The qualified PHP name, the parameter types and the return type: `App\Services\Mailer::send(string,string):bool`. |
| `enclosingClass` | The qualified class the method belongs to. Absent for a free function. |
| `precision` | `SOURCE_FULL`, `SOURCE_LOWERED` or `SOURCE_DEGRADED`. **Derived from `drops`**, not from whether the function has a problem. |
| `synthetic` | True for the functions the translator made: the module's own body and a class's field initializers. |
| `memberKind` | What the function IS inside its owner: `MODULE`, `METHOD`, `CONSTRUCTOR`, `FIELD_INITIALIZER`, `GETTER`, `SETTER`, `DESTRUCTOR`. |
| `returnType` | The type the declaration says the function returns, as a type object -- not a string. |
| `domain` | The value domain of the return type: `{ "domain": "INTEGRAL", "bits": 64, "signed": true }`, or `{ "domain": "FLOATING", "bits": 53, "exponentBits": 11 }`. |
| `drops` | What could not be lowered faithfully, and what that cost. See [the drop object](#the-drop-object). |
| `parameters` | The declared parameters, with the facts a positional list of variables cannot carry. |
| `params` | The parameters, in declaration order. |
| `locals` | The locals. |
| `tryRegions` | The exception regions the function contains. |
| `branchRegions` | The dispatch regions: a `switch` or a `match`. |
| `scopeBindings` | The names this function binds in a scope that is not its own. |
| `classAnnotations` / `methodAnnotations` / `fieldAnnotations` / `paramAnnotations` / `annotationValues` | The five annotation channels the IR has. |
| `generatorStates` | The labels a generator resumes at. |
| `byRef` | True when the function returns by reference. |
| `instructions` | The instruction stream, in source order. |
| `problems` | What could not be modelled. |

### `parameters`

Each entry carries the facts the declaration states and a positional list of
variables cannot:

```json
{ "name": "to", "type": { "kind": "STRING" }, "optional": false,
  "variadic": false, "byRef": false, "keywordOnly": false, "defaultValue": null }
```

`keywordOnly` is always false in PHP -- it is Python's word -- and is carried so
that the two sidecars' shapes are the same.

### `scopeBindings`

Each entry is a name and the scope it belongs to:

```json
{ "name": "config", "kind": "GLOBAL", "byRef": false }
```

The kinds are `LOCAL`, `GLOBAL`, `STATIC_LOCAL`, `CAPTURED`, `PARAM` and
`DECLARE`.

### `tryRegions`

```json
{
  "tryStart": 65, "tryEnd": 66,
  "catchStart": 68, "catchEnd": 70,
  "catchLabels": ["L_CATCH_\\RuntimeException26"],
  "catchTypes": [["\\RuntimeException", "\\LogicException"]],
  "finallyLabel": "L_FINALLY25",
  "finallyStart": 76, "finallyEnd": 78,
  "syntheticFinally": false,
  "finallyExits": ["L_FINALLY25", "L_CATCH_\\RuntimeException26"],
  "resourceVars": []
}
```

`catchTypes` holds every type a catch handles, so that a catch of
`A | B` does not lose the second one. `finallyExits` names the exits the
`finally` runs on.

### `precision`

`SOURCE_FULL` means the function was lowered faithfully. `SOURCE_LOWERED`
means the SHAPE is not whole -- a loop's `else`, a generator's states -- and
`SOURCE_DEGRADED` means the MEANING is not whole: an unknown call target, an
`eval`, a variable variable.

**The precision is derived from the drops, not from the problems.** A drop's
`impact` decides it:

| The function's drops | The precision |
|---|---|
| A `SEMANTICS` drop | `SOURCE_DEGRADED` |
| No `SEMANTICS` drop, but a `STRUCTURE` one | `SOURCE_LOWERED` |
| Only `VALUE` and `TYPE` drops, or none | `SOURCE_FULL` |

**A degradation never suppresses a finding.** The precision is a record of
the approximation, not a filter on the results; the analysis engine does not
read it to decide whether to report.

## The drop object

A drop is the machine-readable receipt of a construct that could not be lowered
faithfully. It has exactly six keys:

```json
{
  "construct": "ctrl.foreach.keyUnknown",
  "detail": "the keys of the collection are not known here, so the key variable holds the position instead of the key",
  "impact": "STRUCTURE",
  "line": 20,
  "column": 9,
  "scope": "FUNCTION"
}
```

| Key | Meaning |
|---|---|
| `construct` | What was not lowered, as a dotted name. |
| `detail` | Why, and what was done instead. |
| `impact` | `SEMANTICS`, `STRUCTURE`, `VALUE` or `TYPE`. |
| `line`, `column` | Where it happened, in the ORIGINAL source. |
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
| `**` | the repeated multiplication it stands for, when the exponent is a constant; otherwise `MUL`, and the difference is reported |
| `== === != !== <>` | `EQ NE` |
| `< <= > >=` | `LT LE GT GE` |
| `&& \|\| and or` | a branch chain: the right operand is only evaluated when the left one says it has to be |
| `& \| ^ << >>` | `AND OR XOR SHL SHR` |
| `??` | a branch chain on "is not null"; the false branch takes the right operand |
| `<=>` | two comparisons and a choice between them |
| `instanceof` | the `INSTANCEOF` instruction |

An assignment is not a `BINOP`. A plain assignment is a write; a compound
assignment (`+=` and friends) is a read, a `BINOP`, and then a write.

**`??` tests for null, not for truth.** `0 ?? $b` is `0` in PHP, and a
truthiness test would take `$b`. So the branch's operand is the comparison
`a != NULL` and the guard says the same thing about the edge the branch takes.

**A power is expanded, not approximated.** The TAC has no power operator, so a
power with a constant exponent becomes the repeated multiplication it stands
for -- which is the same number. An exponent that is only known at run time
cannot be expanded, so it is modelled as a single multiplication and the
difference is reported.

**The loose comparison is not reported.** PHP's `==` coerces before it
compares, and the TAC's equality test compares the values as they are. The
difference does not change how taint flows, so it is documented here rather
than reported on every function that uses `==`.

## The conservative modelling list

Every one of these is lowered into something the TAC can hold, and the
difference between that something and the source is **recorded as a drop** with
an `impact` that says what it cost. Nothing here becomes an unknown value
without a word, and nothing here is silently dropped.

| Construct | What is modelled | The drop |
|---|---|---|
| A closure or an arrow function | Its own function, plus an object that holds it and the values it captured. | -- |
| A `yield` or a `yield from` | The yielded value is evaluated, and the resume points are labelled. The states cannot be split into functions of their own: a TAC jump goes to a label of the same function. | `expr.generator.states`, `STRUCTURE` |
| A generator that delegates to another one | The delegated value is evaluated. | `expr.generator.delegate`, `STRUCTURE` |
| A `match` whose no arm matches | A `THROW` of `UnhandledMatchError`, as PHP does. | -- |
| A `switch` | A chain of equality tests and a branch region. PHP compares loosely and the TAC does not. | -- |
| A `foreach` over a collection whose keys are not known | The key variable holds the position. | `ctrl.foreach.keyUnknown`, `STRUCTURE` |
| A `foreach` over an array literal | The keys are read out of a table of them, so the key variable binds the KEY. | -- |
| A catch of several types | The catch handler carries the first type; the region records them all. | `exc.catch.multiType`, `STRUCTURE` |
| A trait used by a class | The trait's methods stay the trait's own; they are not copied into the class. | `decl.trait.unlinked`, `STRUCTURE` |
| An `isset` | The value at the key, tested against null, and a `PropertyPresence` guard. The guard cannot be rendered into a predicate. | `guard.unrenderable`, `STRUCTURE` |
| An `empty` | The value at the key, tested against null. `empty` is also true for `0`, `""` and `false`. | `expr.empty.falsy`, `VALUE` |
| An `unset` of a key | An `ARRAYSTORE` with `deleteKey`. | -- |
| An `unset` of a plain variable | A move of an unknown value into the variable. | `expr.unset.variable`, `STRUCTURE` |
| A variable variable (`$$x`) | A slot of the scope's variable table; the name it has is only known at run time. | `expr.varvar.alias`, `SEMANTICS` |
| `static::` | The class the code is written in. The class that runs it is only known at run time. | `lateStaticBinding`, `SEMANTICS` |
| `parent::` | The class the code is written in, when the parent is not known here. | `type.parent.unknown`, `TYPE` |
| A construction of a class named by a variable | A `NEW` whose class is unknown. | `type.constructor.unknown`, `SEMANTICS` |
| A reference assignment or a by-reference argument | The write names the location it aliases. | `op.assign.byref`, `SEMANTICS` |
| An unpacked array entry or an unpacked argument list | The number of elements is only known at run time. | `op.array.spread`, `op.call.spread`, `VALUE` |
| A named argument whose parameter names are not known | The argument is passed in its position. | `op.call.namedArg`, `STRUCTURE` |
| A method call whose receiver's type is unknown | The owner is the class the code is in. | `lowered.call`, `STRUCTURE` |
| A power whose exponent is only known at run time | A multiplication. | `op.bin.power`, `STRUCTURE` |
| A `goto` and a label | A `JUMP` and a `LABEL`. | -- |
| A `global`, a `static` or a `declare` | A scope binding, and the writes routed to that scope. | -- |

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
