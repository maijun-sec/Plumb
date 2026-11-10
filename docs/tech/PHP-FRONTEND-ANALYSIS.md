# PHP in Plumb: The Sidecar Route, the Reference PHP Parser, and the TAC Generation

[English](../README.md) | [中文](../zh/tech/PHP-FRONTEND-ANALYSIS.md)

## 0. The conclusion first

Plumb reads PHP. The route is a PHP sidecar that drives a JavaScript port of
the reference PHP parser, and the TAC it produces is the ordinary TAC: the
same instructions, the same call convention, the same keyed namespace the
JavaScript, TypeScript and Python frontends use.

| Question | Answer |
|---|---|
| Which parser | A JavaScript port of the reference PHP parser, driven by the Node runtime |
| Where it runs | A Node process, spoken to over stdio, one frame per request |
| Which files | `.php` |
| What it produces | `TacFunction`s in the IR, with the file's problems attached |
| What it does not yet produce | Findings: the PHP sink catalogue and the PHP semantics table are the rules layer's work |

The last row is the honest one. The frontend lowers PHP into the TAC
correctly; whether a finding comes out of it depends on the rules that match
the lowered calls, and those rules do not exist for PHP yet.

## 1. The JVM cannot reach PHP's parser

### 1.1 The parser is a program, not a library

PHP's grammar is decided by the reference parser. It is what decides whether
`match` parses, where a heredoc's value ends, and whether a name is a
function or a class.

A JVM re-implementation of that grammar would be a second implementation, and
the second one would disagree with the first about what a file means. A
disagreement about a name produces a different callee signature, and a callee
signature is what the sink catalogue matches on.

### 1.2 The route: a PHP sidecar

So the analysis runs in a sidecar that drives that parser, over a
length-prefixed JSON framing, with a version handshake on every response. The
Java side maps the wire into the IR; the IR is the IR, and the engine does not
know which language produced it.

### 1.3 Why the parser is a JavaScript port

The reference parser is a PHP program, and PHP is not installed on the
machines Plumb runs on. The parser this sidecar uses is a **port of it to
JavaScript**, and its AST is the reference AST -- so the node shapes a rule
is written against are the ones the PHP community writes rules about.

The sidecar is therefore a Node program, the same way the TypeScript and
ArkTS sidecars are. It needs Node and its one npm dependency, and nothing
else: there is no PHP runtime to install and no Composer to run.

### 1.4 Why the parser reads the files itself

The parser reads the source from disk. It has to: the text a caller passes in
may not be the text on disk, and a file whose text was replaced would be a
**different program**. So the text the caller passes in is checked against the
text on disk, and a difference is **reported**: the analysis used the file on
disk, and saying so is the difference between a known lower bound and a silent
surprise.

## 2. What PHP breaks in the TAC

### 2.1 An object is a namespace, not a record

In PHP `$o->name` and `$o['name']` are the same location, and `$items[0]` is
`$items["0"]`. So a property access is lowered as a container access with a
string key -- an `ARRAYLOAD` or an `ARRAYSTORE` whose index operand is the key
-- and not as a field access. A field names a field, and a field is not what
a property is.

**An assignment is a write, and the write has to reach the store.** A
property assignment is an `ARRAYSTORE` with the property's name as the key,
so that a later read of the same property finds the value that was written.

### 2.2 The receiver slot is reserved

The TAC's call convention reserves `args[0]` for the receiver, and the real
arguments start at `args[1]`. The slot is reserved whether or not the call has
a receiver object: a free function has none, so the slot holds the
**namespace's** name as a constant.

### 2.3 The callee signature is the name PHP writes

A PHP callee is named the way PHP names it, and the signature carries that
name plus the arity:

| The source | The signature |
|---|---|
| `system($cmd)` in the global namespace | `\system/1` |
| `system($cmd)` in `namespace App\Services;` | `\system/1` |
| `helper($x)` in `namespace App\Services;`, declared there | `App\Services\helper/1` |
| `Mailer::send($to)` | `App\Services\Mailer::send/2` |
| `$mailer->send($to)` | `App\Services\Mailer::send/2` |

**Why the PHP name and not a Java-shaped one.** The sink catalogue matches on
the callee signature, and a rule about PHP code should be written in PHP
terms. A pattern that names `\system` matches `\system/1`; a pattern that
names `App\Services\Mailer::send` matches that method.

**The namespace rules are PHP's own.** An unqualified **function** call looks
in the current namespace first and in the global namespace only when the
current one does not declare it -- so `system($cmd)` inside a namespace is the
global `system`, because the namespace does not declare one. An unqualified
**class** name has **no** global fallback: `new Logger()` inside
`namespace App\Services;` is `App\Services\Logger` and nothing else. A `use`
statement binds an alias to a qualified name, and `use function` binds a
function while a plain `use` binds a class; the two are resolved
independently.

**The declarations are read before the file is lowered.** Whether the current
namespace declares a name is a fact about the file, and a call that appears
*before* the declaration it calls still has to carry that declaration's
arity. So the file's declarations are read first, and the file is lowered
second.

A call of a function the file declares is a `CALL`; a call of anything else
is an `UNINTERPRETED`: a black box whose internals the engine does not see.
Both are looked up in the sink catalogue, so a PHP sink such as `system` is
matched either way.

### 2.4 The arity counts the declared parameters

A function the file declares contributes its declared parameters, and the
receiver is not one of them: a method with one parameter is
`App\Services\Mailer::send/1`, with the receiver at `args[0]`. An optional
parameter is still a parameter: the callee has a slot for it, whether or not
the caller fills it.

A function from elsewhere contributes the number of arguments the call
passes. PHP has no static types, so the declared parameters of a library
function are not visible here.

### 2.5 The commands PHP runs

A **backtick string** is a command: PHP runs it through the shell. It is
therefore lowered as a call to a shell stand-in rather than as an ordinary
string, so that the command is visible as a call.

| Construct | The signature |
|---|---|
| `` `ls $x` `` | `<php>.shell/1` |
| `include 'f.php'` | `<php>.include/1` |
| The length of a collection in a `foreach` | `<php>.count/1` |

The owner is not a real namespace: a sink catalogue written against
`system/1` must not match `<php>.shell/1` by accident.

### 2.6 What is not modelled, and how it is reported

Every one of these is reported as a problem on the function, and the affected
value becomes an unknown value. Never a silent omission.

| Construct | What happens |
|---|---|
| A closure or an arrow function used as a value | Reported as `SOURCE_LOWERED`; the value becomes unknown |
| A generator (`yield`) | Reported as `UNHANDLED_NODE`; the value becomes unknown |
| A `switch` | Reported as `SOURCE_LOWERED`: PHP compares its subject loosely and the TAC does not |
| A `foreach` over an array with string keys | Reported as `SOURCE_LOWERED`: the key variable holds the position |
| A method call whose receiver's type is unknown | Reported as `SOURCE_LOWERED`: the owner is the class the code is in |
| A call whose callee is not a name | Reported as `UNHANDLED_NODE`; the call is a black box |
| A `goto` | Reported as `UNHANDLED_NODE` |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes unknown |

One difference is **not** reported, because it cannot change what the
analysis sees: PHP's `==` coerces before it compares, and the TAC's equality
test compares the values as they are. The difference does not change how
taint flows.

**The same problem is reported once, with a count.** A file with fifty
switches has one problem saying so fifty times, not fifty problems.

## 3. The project, not the file

### 3.1 A file's module name is its path

A file's module name is its path under the project's root, with the
separator PHP's own namespaces use. The module name is the owner of the
file's own top-level code, so the root decides what the callee signatures
are.

### 3.2 Where the project's boundary is

The boundary is the compile context's module root when the file belongs to
one, and otherwise the nearest ancestor that marks a project.

| Marker | Meaning |
|---|---|
| `composer.json` | The toolchain's own manifest: a project |

What is deliberately **not** a marker is a bare directory of PHP files: PHP
has no configuration file that says "this directory is a project", and
inventing one would make the boundary a guess.

### 3.3 What is left out, and why

The `vendor` directory holds the dependencies Composer installed, `node_modules`
holds the dependencies of a front-end build, the build outputs hold generated
code, and a directory whose name starts with a dot is a tool's own state.

## 4. The PHP TAC: five steps

### 4.1 Open the project

The Java side sends the project's files and the root they live under. The
sidecar remembers both, and every file is then translated with that root in
mind.

### 4.2 Translate

The Java side asks for one file. The sidecar reads its text from disk and
lowers it.

### 4.3 Lower

The file's own top-level code becomes a synthetic function; the functions
become functions; a class's body becomes a synthetic function and its methods
become functions of their own; a property read and a property write become a
container access with a string key; a binary operator becomes a `BINOP`.

### 4.4 Speak the wire format

The functions travel as length-prefixed JSON. Every response carries the
protocol version and the IR version, including the failed ones.

### 4.5 Report every degradation

A construct that could not be modelled becomes a problem on the function, and
the function's precision drops to `SOURCE_LOWERED`. A file that did not parse
becomes a failure response, and the Java side turns it into a problem: a file
that is not PHP is not a partial PHP file, it is a different program.

## 5. The cross-check that keeps the two halves honest

The Java side is tested against a stand-in sidecar that speaks the same
protocol, so the framing, the handshake and the wire-to-IR mapping are pinned
wherever the tests run. The PHP side is tested by a smoke test that starts
the real sidecar as a process and checks the TAC that comes back over the
wire. An end-to-end test drives the real frontend against the real parser.

Each half has been mutated on purpose, to check that the other half notices:

- a property read turned into a field read -- the tests go red, **but only
  once the test source contains a property access of its own**. A source that
  only used the subscript form passed with the property read broken, because
  the subscript's own container access satisfied the assertion;
- the receiver slot moved to the end of the argument list -- the tests go red;
- the namespace qualification dropped -- the tests go red, because the
  signature stopped being the name PHP gives;
- the short conditional's true arm replaced by a placeholder -- the tests go
  red, because the taint that came in with the test was thrown away;
- the "nothing matched" path of a `match` laid out after the arms -- the tests
  go red, because the path where no arm matched then ran the first arm's
  body, and PHP throws there instead.

## 6. What this document does not claim

| Claim | Status |
|---|---|
| PHP source is lowered into the TAC | True, and tested against the real parser |
| The lowering finds every taint flow | Not claimed: the analysis is intra-procedural, and a property written in one method is not seen by another |
| PHP produces findings | Not yet: the sink catalogue and the semantics table for PHP do not exist |
| A callee's owner is always right | Not claimed: a method whose receiver's type cannot be inferred falls back to the class the code is in, and that is reported |
| A PHP version is checked | Not claimed: the parser is a fixed npm dependency, and there is no runtime whose version could drift |

## Appendix: where the facts come from

| Fact | Where it comes from |
|---|---|
| The grammar | A JavaScript port of the reference PHP parser, driven by the Node runtime |
| The wire format | [`plumb-frontend/php-sidecar/PROTOCOL.md`](../../plumb-frontend/php-sidecar/PROTOCOL.md) |
| The Java frontend | [`PhpTacFrontend.java`](../../plumb-frontend/src/main/java/org/plumb/tac/frontend/php/PhpTacFrontend.java) |
| The translator | [`plumb-frontend/php-sidecar/src/translator.mjs`](../../plumb-frontend/php-sidecar/src/translator.mjs) |
| The smoke test | [`plumb-frontend/php-sidecar/smoketest.mjs`](../../plumb-frontend/php-sidecar/smoketest.mjs) |
