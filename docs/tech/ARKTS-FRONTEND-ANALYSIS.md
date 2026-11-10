# ArkTS in Plumb: The Sidecar Route, the Compiler's Own Grammar, and the TAC Generation

[English](../README.md) | [中文](../zh/tech/ARKTS-FRONTEND-ANALYSIS.md)

## 0. The conclusion first

Plumb reads ArkTS. The route is a Node sidecar that drives the ArkTS compiler's
own front end, and the TAC it produces is the ordinary TAC: the same
instructions, the same call convention, the same keyed namespace the
JavaScript and TypeScript frontends use.

| Question | Answer |
|---|---|
| Which compiler | `ohos-typescript`, the ArkTS fork of the TypeScript compiler, reached through its classic `createProgram` API |
| Where it runs | A Node process, spoken to over stdio, one frame per request |
| Which files | `.ets` |
| What it produces | `TacFunction`s in the IR, with the file's problems attached |
| What it does not yet produce | Findings: the ArkTS sink catalogue and the ArkTS semantics table are the rules layer's work |

The last row is the honest one. The frontend lowers ArkTS into the TAC
correctly; whether a finding comes out of it depends on the rules that match
the lowered calls, and those rules do not exist for ArkTS yet.

## 1. The JVM cannot reach the ArkTS compiler

### 1.1 The compiler is a program, not a library

ArkTS is read by a fork of the TypeScript compiler that knows the language. It
parses `struct`, it knows the ArkUI decorators, and it parses the declarative
UI DSL. Those are not conveniences: they decide whether a file parses at all.

A JVM re-implementation of that grammar would be a second implementation, and
the second one would disagree with the first about where the UI DSL begins,
which names are components, and what a property's type is. A disagreement
about a type produces a different callee signature, and a callee signature is
what the sink catalogue matches on.

### 1.2 The route: a Node sidecar

So the analysis runs in a sidecar that drives the compiler's own API, over a
length-prefixed JSON framing, with a version handshake on every response. The
Java side maps the wire into the IR; the IR is the IR, and the engine does not
know which language produced it.

### 1.3 Why this sidecar is its own npm project

The ArkTS sidecar shares no file with the TypeScript sidecar. The two are
driven by different compilers with different APIs -- one by the classic
`createProgram`, the other by the version 7 client/server channel -- and a
shared file would force one of them into the other's shape. Each sidecar owns
its own copy of the protocol and its own translator.

## 2. What ArkTS breaks in the TAC

### 2.1 An object is a namespace, not a record

In ArkTS `this.message` and `this["message"]` are the same location, and
`items[0]` is `items["0"]`. So a property access is lowered as a container
access with a string key -- an `ARRAYLOAD` or an `ARRAYSTORE` whose index
operand is the key -- and not as a field access. A field names a field, and a
field is not what a property is.

**An assignment is a write, and the write has to reach the store.** A property
assignment is an `ARRAYSTORE` with the property's name as the key, so that a
later read of the same property finds the value that was written.

### 2.2 The receiver slot is reserved

The TAC's call convention reserves `args[0]` for the receiver, and the real
arguments start at `args[1]`. The slot is reserved whether or not the call has
a receiver object: a free function has none, so the slot holds the name of the
module the function was imported from, as a constant.

### 2.3 The owner is the module the function came from

A script language reaches its libraries through **modules**, not through
classes: there is no class to name, so the owner of a call to a module-level
function is the module the function was imported from.

```
import { parse } from '@ohos.util.json';
parse(text);        // @ohos.util.json.parse

import * as json from '@ohos.util.json';
json.stringify(text); // @ohos.util.json.stringify
```

The alternative -- the module the **file** belongs to -- says where the call was
written rather than what was called, and no sink catalogue could match it,
because a catalogue cannot enumerate the file names of every program.

The alias is resolved away: `import { parse as read }` calls `parse`, so the
name the catalogue has to match is the exported one.

A method's owner is the receiver's class, and the class name is the compiler's
own answer. **[MEASURED]** This compiler does not name a local class instance:
the type of `new Helper()` comes out as the global `Object`, so the owner falls
back to the file the call is written in. The same is true of an untyped global
such as `console`, because this compiler does not load the DOM declarations.
The fallback is pinned by the smoke test, so it is a stated behaviour rather
than a surprise.

### 2.4 The arity of a component call

A call's arity counts the declared parameters. A component of the UI DSL is
the exception: in the SDK's declarations a component is a constant whose type
is an interface, and the DSL calls it as a function, so its declared parameter
count says nothing about the call. The argument count is the honest one.

### 2.5 A struct's state, and where it lives

A struct's properties are its state, and a method that reads a property has to
see what the property holds. So a struct produces two kinds of function: the
struct's own initialiser, which holds every property's initial value, and one
function per method, which starts with the property values it may read.

A property whose initialiser is a pure expression is copied into every method.
A property whose initialiser calls something is not: a call has effects, and
running it once per method would report the same effect once per method. It
stays in the struct's initialiser, where it is seen once.

### 2.6 What is not modelled, and how it is reported

Every one of these is reported as a problem on the function, and the affected
value becomes an unknown value. Never a silent omission.

| Construct | What happens |
|---|---|
| A `break` or a `continue` | Reported as `SOURCE_LOWERED`; the loop's structure carries the control flow |
| A `switch` | Reported as `SOURCE_LOWERED`; the cases are lowered in order, so the data flow survives and the case selection does not |
| A function used as a value | Reported as `SOURCE_LOWERED`; the value becomes unknown |
| A class expression | Reported as `UNHANDLED_NODE` |
| A call whose callee does not resolve | Emitted as `UNINTERPRETED`: a black box whose result may be anything |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes unknown |

A type-only declaration -- an interface, a type alias, an import -- is
skipped, not reported: it declares a type and has no run-time effect, so
there is nothing in it for a finding to be about.

## 3. The project, not the file

### 3.1 A file analysed alone has unknown types

An ArkTS file names the modules it imports, and the compiler resolves those
names against the project. A file analysed on its own has unknown types
wherever it imports something, and an unknown type is an unresolved callee --
a missing finding rather than a missing precision.

So the compiler is given the whole project, and the project is opened once and
reused for every file.

### 3.2 Where the project's boundary is

The boundary is the compile context's module root when the file belongs to
one, and otherwise the nearest ancestor that marks a module.

| Marker | Meaning |
|---|---|
| `build-profile.json5` | The ArkTS toolchain's build profile: a module |
| `oh-package.json5` | The module's package manifest: a module |
| `tsconfig.json` | A project that also uses the TypeScript configuration |
| `package.json` | **Not** a marker: it is a common thing to find in a home directory, and a boundary that walks up to it would make the whole home directory one project |

### 3.3 What is left out, and why

Declaration files hold signatures and no executable body, so analysing them
would report findings against declarations rather than against code. The
dependency directories hold other people's code, the build outputs hold
generated code, and a directory whose name starts with a dot is a tool's own
state.

## 4. The ArkTS TAC: five steps

### 4.1 Open the project

The Java side sends the project's files and the compiler loads them. The
component list is completed with the names the project's own sources use, so a
component the SDK does not declare -- and a component the project defines for
itself -- is still recognised.

### 4.2 Translate

The Java side asks for one file. The sidecar finds it in the program, reads
its text from disk, and lowers it.

The compiler reads the files itself, so the analysis is of the file on disk. A
caller that hands over a different text is analysing something other than what
it passed, and that is reported.

### 4.3 Lower

The struct, its methods and its properties become functions; the UI DSL becomes
a call; a property read and a property write become a container access with a
string key; a binary operator becomes a `BINOP`.

### 4.4 Speak the wire format

The functions travel as length-prefixed JSON. Every response carries the
protocol version and the IR version, including the failed ones.

### 4.5 Report every degradation

A construct that could not be modelled becomes a problem on the function, and
the function's precision drops to `SOURCE_LOWERED`. A file that did not parse
becomes a problem on the file, and the file is still analysed: dropping it
would hide every taint in it.

## 5. The compiler's three settings

Three compiler options decide whether an ArkTS file parses at all. They are
not tuning.

| Option | Value | What it decides |
|---|---|---|
| `ets.render.method` | `["build"]` | Which method is the render method; the UI DSL is only parsed inside it |
| `ets.render.decorator` | `["Builder", "LocalBuilder"]` | Which decorators introduce a builder function |
| `ets.components` | The SDK's names plus the project's | Which names are components; a name that is not on the list is an ordinary call |

The SDK's module mapping is loaded too, from the `paths` the SDK's own
configuration ships. Without it an `import` of an `@ohos.*` module resolves to
nothing, and an unresolved import means an unknown type, which means a
missing finding.

## 6. The cross-check that keeps the two halves honest

The Java side is tested against a stand-in sidecar that speaks the same
protocol, so the framing, the handshake and the wire-to-IR mapping are pinned
wherever the tests run. The Node side is tested by a smoke test that starts
the real sidecar as a process and checks the TAC that comes back over the
wire. An end-to-end test drives the real frontend against the real compiler.

Each half has been mutated on purpose, to check that the other half notices.
A property read turned into a field read, and the receiver slot moved to the
end of the argument list: both make the tests red.

## 7. What this document does not claim

| Claim | Status |
|---|---|
| ArkTS source is lowered into the TAC | True, and tested against the real compiler |
| The lowering finds every taint flow | Not claimed: the analysis is intra-procedural, and a field written in one method is not seen by another |
| ArkTS produces findings | Not yet: the sink catalogue and the semantics table for ArkTS do not exist |
| A component call's arity is the argument count | True, and deliberately different from an ordinary call's arity |

## Appendix: where the facts come from

| Fact | Where it comes from |
|---|---|
| The compiler's API | [`ohos-typescript`](../../plumb-frontend/arkts-sidecar/package.json), read from the installed package |
| The UI DSL's grammar | The compiler's parser: the component expression is one construct, and a call with a block is one too |
| The component names | The SDK's own declarations, regenerated by [`tools/generate-components.mjs`](../../plumb-frontend/arkts-sidecar/tools/generate-components.mjs) |
| The module mapping | The SDK's own configuration file, read rather than copied |
| The wire format | [`plumb-frontend/arkts-sidecar/PROTOCOL.md`](../../plumb-frontend/arkts-sidecar/PROTOCOL.md) |
| The Java frontend | [`ArktsTacFrontend.java`](../../plumb-frontend/src/main/java/org/plumb/tac/frontend/arkts/ArktsTacFrontend.java) |
| The translator | [`plumb-frontend/arkts-sidecar/src/translator.mjs`](../../plumb-frontend/arkts-sidecar/src/translator.mjs) |
