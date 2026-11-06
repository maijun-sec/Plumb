# The C# sidecar wire protocol

The Java frontend cannot host Roslyn: Plumb is a JVM product, Roslyn is a .NET
library. So the C# frontend launches a **sidecar** — a long-lived .NET process —
and talks to it over stdio.

This file is the contract between the two halves. Both sides implement it, and
both sides' comments point here.

## Why a sidecar at all

The alternatives were considered and rejected:

| Option | Why it was rejected |
|---|---|
| Read C# IL instead of source | IL loses everything source-level: nullable annotations are erased, attributes survive only in a restricted form, and `await`/`async` has already been rewritten into a state machine. It also means writing an IL reader. |
| Port Roslyn's semantics into the JVM | Not possible. Roslyn is a .NET library with no JVM port. |
| Re-implement C# semantics in the JVM | That is a second compiler, and it would disagree with Roslyn on every corner case. The value of using Roslyn is precisely that it is *the* C# compiler. |

The sidecar keeps the source-level semantics (types, overload resolution,
nullable annotations, property accessors) and costs one process boundary.

## Why the sidecar is PROJECT-SCOPED and LONG-LIVED

The sidecar holds ONE `CSharpCompilation` per project and reuses it across
files.

A per-file `CSharpCompilation` cannot resolve a reference to a type declared in
another file of the same project. That is not a precision loss: the file's
symbols would silently fail to bind, and the frontend would report "no findings"
while the project actually contains a vulnerability. A project-scoped
compilation is therefore not an optimisation, it is a correctness requirement.

For the same reason the sidecar is **long-lived**: building a `CSharpCompilation`
means parsing every source file and binding every reference, which is the
expensive part. Doing it once per project and once per session is what makes the
approach viable.

## Framing: length-prefixed JSON

Each message is a JSON object, UTF-8 encoded, framed as:

```
<4-byte big-endian length><payload>
```

The length counts the payload bytes only.

**Why not JSON Lines.** JSON Lines is simpler to eyeball, but a C# source file
may contain any byte in a string literal — including a newline inside a
verbatim string, and a `\r` that some encoders treat specially. A line-oriented
protocol then has to either escape newlines (making the payload unreadable) or
guess. A length prefix has no such ambiguity: the frame boundary is explicit
and cannot be spoofed by the content.

The trade-off is that a frame is not human-readable on the wire. That is
accepted deliberately: the protocol carries a **machine-generated instruction
stream**, not something a human reads, and correctness of the framing matters
more than legibility. The debug log (stderr) is where a human looks.

## Message shapes

### Request (Java → sidecar)

```json
{
  "op": "openProject",
  "projectId": "<opaque string>",
  "sources": [
    { "path": "<absolute path>", "text": "<file contents>" }
  ],
  "references": ["<absolute path to a .dll>"],
  "preprocessorSymbols": ["DEBUG"],
  "langVersion": "latest",
  "nullableContextOptions": "enable",
  "unsafe": false
}
```

`op` is one of:

| `op` | Meaning |
|---|---|
| `openProject` | Create (or replace) the project compilation. |
| `translate` | Translate one file of the open project into TAC. |
| `closeProject` | Drop the project compilation and its cached state. |
| `ping` | Liveness check. Returns `{"op":"pong"}`. |
| `shutdown` | Exit the process. |

`translate` carries the file's path; the sidecar looks the file up in the
project it already holds.

### Response (sidecar → Java)

```json
{
  "op": "openProject",
  "ok": true,
  "diagnostics": [
    { "severity": "error", "message": "...", "path": "...", "line": 12, "column": 5 }
  ],
  "functions": [
    {
      "name": "Run",
      "signature": "demo.Sink.Run(System.String):System.Void",
      "enclosingClass": "Sink",
      "returnType": { "kind": "VOID" },
      "precision": "SOURCE_FULL",
      "synthetic": false,
      "params": [
        { "name": "input", "type": { "kind": "CLASS", "name": "System.String" } }
      ],
      "locals": [],
      "instructions": [
        {
          "kind": "CALL",
          "calleeSig": "System.Data.SqlClient.SqlCommand.set_CommandText/1",
          "dest": null,
          "args": [ { "kind": "VAR", "name": "input" } ],
          "line": 14,
          "column": 9
        }
      ],
      "problems": [
        { "kind": "UNHANDLED_NODE", "message": "...", "nodeClass": "...", "line": 20, "column": 3 }
      ]
    }
  ]
}
```

Every response carries `ok`. A failure carries `ok: false` and an `error`
message, and the Java side turns it into a structured problem — never into a
silent "no findings".

### Instruction encoding

The instruction kinds are the TAC's own kinds, by name:
`MOVE`, `BINOP`, `UNARYOP`, `CALL`, `NEW`, `NEWARRAY`, `ARRAYLOAD`, `ARRAYSTORE`,
`FIELDLOAD`, `FIELDSTORE`, `INSTANCEOF`, `BRANCH`, `JUMP`, `LABEL`, `RETURN`,
`THROW`, `NOP`, `CATCH_HANDLER`, `FINALLY_ENTER`, `NULL_CHECK`, `HAVOC`,
`UNINTERPRETED`.

Only the fields that the kind actually uses are present. The Java side ignores
any field it does not know, so adding a field to this protocol is
backwards-compatible in one direction (old sidecar → new Java side works).

### Operand encoding

```json
{ "kind": "VAR",  "name": "cmd", "type": { "kind": "CLASS", "name": "System.Data.SqlClient.SqlCommand" } }
{ "kind": "CONST", "constKind": "STRING", "value": "select 1" }
{ "kind": "CONST", "constKind": "NULL" }
```

A variable's `type` is optional; an absent type means `UNKNOWN`.

### Type encoding

```json
{ "kind": "CLASS",  "name": "System.String" }
{ "kind": "ARRAY",  "component": { "kind": "INT" } }
{ "kind": "INT" }
```

## The callee signature format

The signature is `owner.method/arity`, the same canonical form every other
Plumb frontend emits and the same form `CalleeKeyNormalizer` canonicalises:

```
System.Data.SqlClient.SqlCommand.set_CommandText/1
```

This is what makes the sink catalogue work without a second matching mechanism:
the catalogue matches on the callee signature with a regex, and a property
setter is just a method whose name happens to start with `set_`.

**The receiver comes FIRST.** This is the TAC's own call convention, and it is
not optional:

```
args[0] = the receiver, when the call has one
args[1..] = the actual arguments
```

A static call has no receiver, so its first real argument is at `args[0]`.

The engine reads the catalogue's `arg_index` against this convention: a
catalogue entry that says `arg_index: 0` names the **first user argument**, and
the engine adds the receiver offset itself when it looks the argument up.

So a C# property assignment produces:

```
CALL  System.Data.SqlClient.SqlCommand.set_CommandText/1
      args = [ cmd, tainted ]
              ^^^^  ^^^^^^^
              receiver  the assigned value
```

**Why this is worth stating so loudly.** A sidecar that emits
`args = [tainted]` produces TAC that looks perfectly reasonable and matches the
sink catalogue — and then the engine reads `args[0]`, finds the *receiver*,
finds nothing tainted there, and reports nothing. The finding is absent while
the scan reports success. That is the exact failure this whole design was
chosen to avoid, and it is invisible without an end-to-end test.

## Why a C# property assignment must be a CALL

In C#, `cmd.CommandText = tainted` is, at the language level, a call to
`SqlCommand.set_CommandText`. There is no other reading of it: a property
accessor is a method, and the assignment is an invocation of it.

If the frontend emitted `FIELDSTORE` instead, every property-shaped sink would
be structurally invisible — the DFA engine only queries the sink catalogue on
`CALL` instructions, and `FIELDSTORE` carries no callee signature at all. The
finding would not be "less precise"; it would be **absent**, while the scan
still reported success.

So the rule is absolute:

* **property assignment → `CALL owner.set_X/arity`**, with the receiver at
  args[0] and the assigned value at args[1].
* **field assignment → `FIELDSTORE`**, because a field really is a field.

The distinction is decided by the resolved symbol: Roslyn's `IPropertySymbol`
versus `IFieldSymbol`. It is never guessed from the spelling.
