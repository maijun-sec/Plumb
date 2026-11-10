# TypeScript and JavaScript in Plumb: The Sidecar Route, The Keyed Namespace, and the TAC Generation

[English](../README.md) | [中文](../zh/tech/TYPESCRIPT-FRONTEND-ANALYSIS.md)

This document answers four questions, in order:

1. **Can a JVM-hosted engine analyse TypeScript at all?** The answer decides the route, and it is
   not a preference.
2. **What does TypeScript break in the TAC?** One thing does, and it is the thing every dynamic
   language does: an object is a namespace rather than a record.
3. **What does the engine need in order to read a namespace?** The access path already has a map
   form. What it did not have was a decision about which languages use it.
4. **How is a TypeScript TAC produced, end to end?**

The route is the one the C# and Go frontends already use: a separate process that speaks a framed
JSON protocol, and a JVM that reads it. What changes is that this sidecar is a **Node program**,
because the TypeScript compiler is not a JVM program.

Everything marked **[MEASURED]** was read out of this repository's code or measured against it.
The parts that describe what the compiler does are marked **[COMPILER]**, and are statements about
the compiler's own behaviour rather than about Plumb.

---

## 0. The conclusion first

| Question | Answer |
| --- | --- |
| Can the JVM analyse TypeScript? | **Not directly.** The compiler is a native program with its own parser, binder and type checker, and the types it reports are the ones the compiler itself sees. A JVM re-implementation of them would be a second implementation that disagrees with the real one -- and a type that resolves differently produces a different callee signature, which is what the sink catalogue matches on. |
| What is the route? | **A Node sidecar** that drives the compiler's own API, over the same length-prefixed JSON protocol the C# and Go sidecars already speak, feeding the same Java-serialised IR into the same engine. |
| Does the TAC IR contract change? | **No.** No new opcode, no new type kind, no change to the callee-key contract. A property access becomes a container access with a string key -- an `ARRAYLOAD` or an `ARRAYSTORE` -- which is an opcode the TAC already had. |
| What does the engine need? | **A decision, not a new mechanism.** The access path already carries a map form; `SourceLanguage.hasKeyedContainers()` says which languages use it. The five script languages use it, and the five static languages do not, so the existing findings do not move. |
| What is the real cost? | **The sink catalogue.** `sink-families.yaml` has 67 families, and the rule layer scopes a bucket of them to TypeScript, JavaScript and ArkTS. The frontend is the small half; the catalogue is the large half. |

The last row is the one that decides whether TypeScript produces findings at all. The frontend
exists and lowers the language faithfully, and the catalogue's script-language bucket names the
Node APIs by the module they come from. The two halves meet on a naming convention -- the owner
of a call to a module-level function is the module it was imported from -- and **nothing checks
that agreement on its own**: a catalogue test builds its TAC by hand, and a front end test proves
the front end emits what it was written to emit. Neither notices when the two disagree. When they
do, the symptom is the worst kind: no crash, no warning, a scan that reports the program clean.

---

## 1. The JVM cannot reach the compiler

### 1.1 The compiler is a program, not a library

**[COMPILER]** The TypeScript compiler is not a JVM library. It is a native program, and the
JavaScript front ends that exist for it -- the ones a bundler embeds, the ones an editor talks to
-- are the compiler's own entry points, not re-implementations of it.

That is what makes a sidecar the route rather than a preference. Plumb needs three facts from the
compiler: the type of an expression, the resolved signature of a call, and the name of the symbol
a call reaches. Each of the three is a decision the compiler makes, and each of them changes the
callee signature the sink catalogue matches on. A second implementation of those decisions would
agree with the compiler on the common cases and disagree on the rest, and the disagreement would
show up as a missing finding rather than as an error.

### 1.2 The route: a Node sidecar

**[MEASURED]** The sidecar is a Node program under `plumb-frontend/ts-sidecar/`, and the Java
side launches it with two words -- the Node runtime and the script:

```javascript
import { API } from "typescript/unstable/sync";
```

The process handling and the framing are shared with the other sidecars: see
[`SidecarProcess`](../../plumb-frontend/src/main/java/org/plumb/tac/frontend/sidecar/SidecarProcess.java).
What is specific to this one is that the command has two elements rather than one, which is why
`SidecarProcess.launch` grew a `List<String>` overload.

### 1.3 Version 7 has no compiler API at the root

**[COMPILER]** The sidecar targets the latest release, and that release moved the API. The root
export of the package carries the version and nothing else; the compiler's API is reached through
`typescript/unstable/sync`, which is a client of a compiler **server**:

| Channel | What it is | What Plumb uses it for |
| --- | --- | --- |
| `typescript` (root export) | The version, and the compiler binary | Nothing. There is no compiler API here. |
| `typescript/unstable/sync` | A synchronous client of the compiler server | Opening the project and asking the compiler for types. |
| `typescript/unstable/ast` | Guards for the AST the server returns | Recognising the node kinds the translator walks. |

**[COMPILER]** The client spawns the server itself, which is why the project is opened **once** and
held: opening it per file would re-parse the standard library for every file, and the standard
library is the bulk of the work.

---

## 2. What TypeScript breaks in the TAC

### 2.1 An object is a namespace, not a record

This is the one decision that matters, and it is the same decision Python and PHP make.

In a language with records, `obj.userId` names a **field**: the location is identified by the
object and the field's name, and the type system says what may be stored there. In JavaScript an
object is a **namespace**: `obj.userId` and `obj["userId"]` are the same location, `arr[0]` and
`arr["0"]` are the same location, and a key the analysis cannot resolve may be any key.

So a property access is **not** a field access. It is a container access whose key is a string:

```
ARRAYLOAD  dest=t1, lhs1=req, lhs2=CONST(STRING, "userId")
```

Not `FIELDLOAD`. A field names a field, and a field is not what a property is. The identity of a
property access is the pair (object, key), and that pair is what the engine's **keyed namespace**
carries.

**[MEASURED]** The IR already had the vocabulary for this. `TacAccessPath` carries a `MapKey` and
a `LazyMapKey` beside its `Reference`, and `SourceLanguage.hasKeyedContainers()` decides which
languages read it. The five script languages use it; Java, Kotlin, Scala, C# and Go do not, so
their findings do not move.

| Construct | Lowered as | Why |
| --- | --- | --- |
| `obj.name` | `ARRAYLOAD` with a `STRING` key | A property is a namespace entry, not a field. |
| `obj.name = v` | `ARRAYSTORE` with a `STRING` key | The same location, written. |
| `obj[key]` | `ARRAYLOAD` with a computed key | The key may be any key, and the engine says so. |
| `new Thing(a)` | `NEW` with the arguments | The constructor is a call with a receiver. |
| `obj.method(a)` | `CALL` with the receiver in `args[0]` | See below. |

### 2.2 The receiver slot is reserved

**[MEASURED]** `args[0]` is the receiver, always. A free function has no receiver object -- it is a
function of the **module** it was imported from -- so the slot holds that module's name as a
constant. The real arguments are at `args[1..]`, and the arity counts the **declared parameters**,
not the receiver.

```
args[0] = the receiver slot   (the receiver, or CONST(STRING, the module the function came from))
args[1..] = the actual arguments
```

This is worth stating loudly because a sidecar that emits `args = [tainted]` produces TAC that
looks perfectly reasonable and matches the sink catalogue -- and then the engine reads `args[0]`,
finds the *receiver*, finds nothing tainted there, and reports nothing. The finding is absent
while the scan reports success.

### 2.3 The owner is the module the function came from

**[MEASURED]** The callee signature is `owner.method/arity`. The owner is the receiver's type for
a method, and for a module-level function it is the **module the function was imported from**:

```
import { exec } from "child_process";
exec(cmd);                 // child_process.exec

import * as vm from "vm";
vm.runInNewContext(code);  // vm.runInNewContext
```

The second form matters as much as the first. A script language reaches its libraries through
**modules**, not through classes: `child_process.exec` is a function *of the child_process module*,
and there is no class to name. Naming the file instead would produce `app.exec`, which says where
the call was written rather than what was called -- and no sink catalogue could match it, because a
catalogue cannot enumerate the file names of every program. The sink catalogue is written against
the module name, so the front end has to produce it.

The alias is resolved away: `import { execFile as spawner }` calls `execFile`, so the name the
catalogue has to match is the exported one.

A method's owner is the receiver's **class**, and the class name is the compiler's own answer:

```ts
class Repo {
  find(id: string): string { ... }
}
const r = new Repo();
r.find(id);                // Repo.find
```

**[MEASURED]** The class name is read from the compiler's symbol as a **property**. The accessor
`getName()` is not implemented in the native preview, and asking for it yields `undefined` -- so a
class instance used to come out as `Object`, and its methods as calls on the FILE's module. A
symbol without a name is a class without a name.

The two free functions with no module to name are the globals. `eval` and `fetch` are not imported
from anywhere, so their owner is the module the **file** belongs to -- and the catalogue matches
them by the method name alone.

**[MEASURED]** The arity counts the **declared parameters**, not the arguments passed, when the
compiler can resolve them: a method with one parameter is `UserStore.find/1`, with the receiver at
`args[0]`. A function reached through an import is the exception -- the compiler's resolved
signature carries an empty parameter list for it, so the arity comes out as zero. The sink
catalogue matches the arity as optional, so the name still matches.

### 2.4 What is not modelled, and how it is reported

**[MEASURED]** Every construct the translator cannot lower is reported as a problem on the
function, and the affected value becomes a `HAVOC` -- an unknown value. Never a silent omission.

| Construct | What happens | The problem kind |
| --- | --- | --- |
| A `break` or a `continue` | The loop's structure carries the control flow | `SOURCE_LOWERED` |
| A function used as a value | The value becomes a `HAVOC` | `SOURCE_LOWERED` |
| A class expression | The value becomes a `HAVOC` | `UNHANDLED_NODE` |
| A call whose callee does not resolve | The call is emitted as `UNINTERPRETED` | `SOURCE_LOWERED` |
| Any other statement or expression | The value becomes a `HAVOC` | `UNHANDLED_NODE` |

**[MEASURED]** A function carries `SOURCE_LOWERED` when it has at least one problem, and
`SOURCE_FULL` when it has none. The precision is a record of the approximation, not a filter: the
engine does not read it to decide whether to report.

---

## 3. The project, not the file

### 3.1 A file analysed alone has unknown types

**[COMPILER]** A TypeScript file names the modules it imports, and the compiler resolves those
names against the project. A file analysed on its own therefore has unknown types wherever it
imports something -- and an unknown type is an unresolved callee, which is a **missing finding**
rather than a missing precision.

So the sidecar holds the whole project and translates out of it.

### 3.2 Where the project's boundary is

**[MEASURED]** The boundary is the compile context's module root when the file belongs to one --
that is the root the scan was told to analyse -- and otherwise the nearest ancestor holding a
`tsconfig.json`, which is the language's own statement of what the project is.

A package manifest is deliberately **not** a project marker. The compiler does not read it to
decide what the project contains, and a manifest is a common thing to find in a home directory: a
boundary that walked up to one would make the whole home directory a project.

### 3.3 What is left out, and why

**[MEASURED]** Every file the discovery leaves out is a file whose types stay unknown, so each
exclusion is a decision about what the analysis sees.

| Left out | Why |
| --- | --- |
| A `.d.ts` file | It declares signatures and holds no executable body; analysing it would report findings against declarations rather than against code. |
| `node_modules` | The dependencies, not the project's own code. |
| `dist`, `build`, `out`, `coverage` | Build output and reports. |
| A directory whose name starts with a dot | A tool's own state. |

**[MEASURED]** A project with more than five thousand source files is cut short, and the cut is
**reported**: the types of the files that were left out may be unknown, and that is a lower bound
on the findings rather than an answer.

---

## 4. The TypeScript TAC: five steps

### 4.1 Open the project

**[MEASURED]** The sidecar opens the project once and holds it. The request names the project's
files by absolute path and the root they live under:

```json
{ "op": "openProject", "protocolVersion": 1, "projectId": "ts:C:/src/app",
  "sourceRoot": "C:/src/app", "sources": [ { "path": "C:/src/app/routes/user.ts" } ] }
```

**[MEASURED]** The compiler reads the files from disk. It has to: its types are the types of the
files as they are, and a file whose text was replaced by a caller's copy would be a **different
program** -- the compiler would resolve the imports of one version and the positions of another.
So the text the caller hands over is checked against the text on disk, and a difference is
reported.

### 4.2 Translate

**[MEASURED]** One file at a time, against the project that is already open. A tree may hold
several compiler projects -- one per `tsconfig.json` -- so the sidecar asks **all** of them for the
file, and takes the one that holds it.

### 4.3 Lower

**[MEASURED]** The translator walks the compiler's own AST. Every construct it cannot lower
becomes a `HAVOC` and a problem; nothing is dropped.

### 4.4 Speak the wire format

**[MEASURED]** The framing is the one every sidecar uses: a four-byte big-endian length, then a
UTF-8 JSON payload. The handshake is symmetric -- a request that names no protocol version is
refused, and a response that names none is refused by the Java side.

| Operation | Meaning |
| --- | --- |
| `openProject` | Create or replace the project. |
| `translate` | Translate one file of the open project into TAC. |
| `closeProject` | Drop the project and its cached state. |
| `ping` | Liveness check. |
| `shutdown` | Exit the process. |

The full contract is in
[`PROTOCOL.md`](../../plumb-frontend/ts-sidecar/PROTOCOL.md).

### 4.5 Report every degradation

**[MEASURED]** The problems travel in both forms: as strings in the plain parse result, and as
structured problems -- with the construct, the line and the column -- in the structured one. The
structured form is what travels through the IR.

**[MEASURED]** A frontend must be total. A missing Node runtime, a missing sidecar, a malformed
response: each of them is reported as a problem, and each of them yields whatever could be
parsed. None of them is a silent empty result, because a silent empty result reads as "no
findings".

---

## 5. The cross-check that keeps the two halves honest

**[MEASURED]** The Java side and the Node side are built independently, and a disagreement between
them produces TAC that looks reasonable and finds nothing. So there are two tests, and they are a
pair:

| Test | What it drives | What it pins |
| --- | --- | --- |
| The stand-in sidecar | A peer written in Java, speaking the same protocol | The Java side's framing, handshake and wire-to-IR mapping, on every machine |
| The end-to-end test | The real Node sidecar and the real compiler | That the wire the Java side reads is the wire the real sidecar writes |

And the assertions are the three that fail silently when they break:

- a property read is an `ARRAYLOAD` with a string key, and **never** a `FIELDLOAD`;
- a call keeps the receiver in `args[0]`;
- a module-level call names the **module it was imported from**, and not the file it is written
  in.

**[MEASURED]** The first two were checked by mutation: changing the translator to emit a
`FIELDLOAD`, and then moving the receiver to the end of the argument list, each turned the
end-to-end test red with the assertion that names the defect. The third was checked the same way,
and it is the one that matters most, because the two halves of the naming convention live in
different files: naming the file instead of the module turned the script-language cases of the
end-to-end test red while leaving the Python and PHP cases green -- which is exactly what a
one-sided break looks like. A test that has never been seen to fail is not evidence.

---

## 6. What this document does not claim

- **It does not claim that a scan of a JavaScript project is complete.** The catalogue names the
  module-level Node APIs; a program that reaches a dangerous API through an application class is
  not in it, and a table that guessed would be worse than one that abstains.
- **It does not claim that the translator covers the language.** The table in section 2.4 is the
  list of what it does **not** model. Everything else it lowers, and everything it lowers it also
  reports.
- **It does not claim that a property access is a field access.** The opposite, and section 2.1
  says why.
- **It does not claim that the five script languages are equally far along.** This document
  describes the TypeScript one. ArkTS, Python 3 and PHP have their own sidecars, their own
  frontends, and their own documents.

---

## Appendix: where the facts come from

| Fact | Where it is |
| --- | --- |
| The sidecar's protocol | [`plumb-frontend/ts-sidecar/PROTOCOL.md`](../../plumb-frontend/ts-sidecar/PROTOCOL.md) |
| The translator | [`plumb-frontend/ts-sidecar/src/translator.mjs`](../../plumb-frontend/ts-sidecar/src/translator.mjs) |
| The Java frontend | [`plumb-frontend/src/main/java/org/plumb/tac/frontend/ts/TsTacFrontend.java`](../../plumb-frontend/src/main/java/org/plumb/tac/frontend/ts/TsTacFrontend.java) |
| The project's discovery | [`plumb-frontend/src/main/java/org/plumb/tac/frontend/ts/TsProjectDiscovery.java`](../../plumb-frontend/src/main/java/org/plumb/tac/frontend/ts/TsProjectDiscovery.java) |
| The keyed namespace | [`plumb-tac/src/main/java/org/plumb/tac/ir/TacAccessPath.java`](../../plumb-tac/src/main/java/org/plumb/tac/ir/TacAccessPath.java) and [DATA-FLOW.md](DATA-FLOW.md) |
| The language gate | [`plumb-tac/src/main/java/org/plumb/tac/ir/SourceLanguage.java`](../../plumb-tac/src/main/java/org/plumb/tac/ir/SourceLanguage.java) |
| The Go route, for comparison | [GO-FRONTEND-ANALYSIS.md](GO-FRONTEND-ANALYSIS.md) |
