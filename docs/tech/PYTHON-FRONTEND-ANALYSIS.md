# Python in Plumb: The Sidecar Route, CPython's Own Parser, and the TAC Generation

[English](../README.md) | [中文](../zh/tech/PYTHON-FRONTEND-ANALYSIS.md)

## 0. The conclusion first

Plumb reads Python 3. The route is a Python sidecar that drives CPython's
own `ast` module, and the TAC it produces is the ordinary TAC: the same
instructions, the same call convention, the same keyed namespace the
JavaScript and TypeScript frontends use.

| Question | Answer |
|---|---|
| Which parser | CPython 3's own `ast` module, through the interpreter that runs the sidecar |
| Where it runs | A Python process, spoken to over stdio, one frame per request |
| Which files | `.py` |
| What it produces | `TacFunction`s in the IR, with the file's problems attached |
| What it does not yet produce | Findings: the Python sink catalogue and the Python semantics table are the rules layer's work |

The last row is the honest one. The frontend lowers Python into the TAC
correctly; whether a finding comes out of it depends on the rules that match
the lowered calls, and those rules do not exist for Python yet.

**Python 2 is not supported, and it is not a gap in the future.** The
enumeration is `PYTHON3`, the parser is Python 3's grammar, and a sidecar
that turns out to run on a Python 2 interpreter is refused rather than
trusted.

## 1. The JVM cannot reach Python's parser

### 1.1 The parser is a program, not a library

Python's grammar is decided by the reference implementation's own parser. It
is what decides whether `async` is a keyword, whether a match statement
parses, and where a walrus operator's value ends.

A JVM re-implementation of that grammar would be a second implementation, and
the second one would disagree with the first about what a file means. A
disagreement about a name produces a different callee signature, and a callee
signature is what the sink catalogue matches on.

### 1.2 The route: a Python sidecar

So the analysis runs in a sidecar that drives the interpreter's own parser,
over a length-prefixed JSON framing, with a version handshake on every
response. The Java side maps the wire into the IR; the IR is the IR, and the
engine does not know which language produced it.

### 1.3 Why this sidecar needs nothing installed

The other sidecars carry their own compiler as a dependency: the TypeScript
one needs npm, the ArkTS one needs the ArkTS fork, the Go one needs a Go
toolchain. The Python one needs **nothing**: `ast` is in the standard library.

That is not a small convenience. It means the Python frontend has no
installation step, no lock file, and no version to keep in step with a
package manager -- and it means the parser is the one the interpreter on the
machine actually uses.

### 1.4 Why the interpreter's version is checked

A PATH lookup cannot tell a Python 2 from a Python 3 by its name: both are
called `python`. So the version is not assumed -- it is asked for. The
sidecar reports the interpreter it runs on in the `ping` response, and the
frontend refuses one that is not a Python 3.

The reason is not pedantry. A Python 2 interpreter would read a Python 3 file
as a broken one -- or worse, read it as a Python 2 file, and report findings
against a program the user never wrote.

## 2. What Python breaks in the TAC

### 2.1 An object is a namespace, not a record

In Python `obj.user_id` and `obj["user_id"]` are the same location, and
`items[0]` is `items["0"]`. So an attribute access is lowered as a container
access with a string key -- an `ARRAYLOAD` or an `ARRAYSTORE` whose index
operand is the key -- and not as a field access. A field names a field, and a
field is not what an attribute is.

**An assignment is a write, and the write has to reach the store.** An
attribute assignment is an `ARRAYSTORE` with the attribute's name as the key,
so that a later read of the same attribute finds the value that was written.

### 2.2 The receiver slot is reserved

The TAC's call convention reserves `args[0]` for the receiver, and the real
arguments start at `args[1]`. The slot is reserved whether or not the call has
a receiver object: a free function has none, so the slot holds the module's
name as a constant.

### 2.3 The owner of a call is the name the imports give it

Python resolves a call by NAME, and the name is what the catalogue matches
on. So the imports are **read**, not merely executed: they are what makes a
callee resolvable at all.

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

A callee that resolves to nothing is a **missing finding**, not a missing
precision: the signature would be a name that matches no family. So the
unresolved case is reported, and the call is emitted as `UNINTERPRETED` -- a
black box whose result may be anything, which is the conservative direction.

### 2.4 The arity counts the positional parameters

A function the module declares contributes its declared positional
parameters, and the receiver is not one of them: a method with one parameter
is `UserStore.find/1`, with the receiver at `args[0]`.

A function from elsewhere contributes the number of positional arguments the
call passes. Python has no static types, so the declared parameters of an
imported function are not visible here -- and a keyword argument is not a
positional one. `subprocess.run(cmd, shell=True)` is therefore
`subprocess.run/1`, which is what the API's own signature says.

### 2.5 What is not modelled, and how it is reported

Every one of these is reported as a problem on the function, and the affected
value becomes an unknown value. Never a silent omission.

| Construct | What happens |
|---|---|
| A lambda | Reported as `SOURCE_LOWERED`; the value becomes unknown |
| A generator (`yield`) | Reported as `UNHANDLED_NODE`; the value becomes unknown |
| A match statement | Reported as `UNHANDLED_NODE`; the subject is still lowered |
| A comprehension | Reported as `SOURCE_LOWERED`: a comprehension has a scope of its own, so the entries after the first are not modelled |
| A call whose callee does not resolve | Emitted as `UNINTERPRETED`: a black box whose result may be anything |
| A keyword argument | Reported as `SOURCE_LOWERED`: it is passed as a positional one |
| A starred argument | Reported as `SOURCE_LOWERED`: it is passed as one argument |
| A non-constant default | Reported as `SOURCE_LOWERED`: a default is evaluated where the function is defined |
| A set literal | Reported as `SOURCE_LOWERED`: a set has no order |
| Any other statement or expression | Reported as `UNHANDLED_NODE`; the value becomes unknown |

Two things are lowered without a report, because the difference cannot change
what the analysis sees: an `await` produces the awaited value, because the
TAC has no coroutine; and a `with` binds the context manager's value to the
target, because the TAC has no `__enter__` and no `__exit__`. The context
expression itself is lowered, so a call inside it is still a call.

**The same problem is reported once, with a count.** A file with fifty calls
to a name the analysis cannot resolve has one problem saying so fifty times,
not fifty problems.

## 3. The project, not the file

### 3.1 A file's module name is its path

A module's name is its path under the project's root, with the dots: `app.py`
is `app`, `app/routes/user.py` is `app.routes.user`, and a package's
`__init__.py` **is** the package, so `app/__init__.py` is `app`. The module
name is the owner of the module's own functions, so the root decides what the
callee signatures are.

### 3.2 Where the project's boundary is

The boundary is the compile context's module root when the file belongs to
one, and otherwise the nearest ancestor that marks a project.

| Marker | Meaning |
|---|---|
| `pyproject.toml` | The project's own configuration: a project |
| `setup.py` / `setup.cfg` | The setuptools project's configuration: a project |
| `__init__.py` | **Not** a marker: it says that a directory is a package, not that the directory is a project -- every subdirectory of a package would then be a project of its own |

### 3.3 What is left out, and why

A type stub (`.pyi`) declares the shape of other modules and holds no
executable body, so analysing it would report findings against declarations
rather than against code. The virtual environments hold the dependencies, the
build outputs hold generated code, and a directory whose name starts with a
dot is a tool's own state.

## 4. The Python TAC: five steps

### 4.1 Open the project

The Java side sends the project's files and the root they live under. The
sidecar remembers both, and every file is then translated with that root in
mind.

### 4.2 Translate

The Java side asks for one file. The sidecar reads its text from disk and
lowers it.

The parser reads the files itself, so the analysis is of the file on disk. A
caller that hands over a different text is analysing something other than
what it passed, and that is reported.

### 4.3 Lower

The module's own code becomes a synthetic function; the functions become
functions; a class's body becomes a synthetic function and its methods become
functions of their own; an attribute read and an attribute write become a
container access with a string key; a binary operator becomes a `BINOP`.

### 4.4 Speak the wire format

The functions travel as length-prefixed JSON. Every response carries the
protocol version and the IR version, including the failed ones.

### 4.5 Report every degradation

A construct that could not be modelled becomes a problem on the function, and
the function's precision drops to `SOURCE_LOWERED`. A file that did not parse
becomes a failure response, and the Java side turns it into a problem: a
Python 2 file is not a partial Python 3 file, it is a different program.

## 5. The cross-check that keeps the two halves honest

The Java side is tested against a stand-in sidecar that speaks the same
protocol, so the framing, the handshake and the wire-to-IR mapping are pinned
wherever the tests run. The Python side is tested by a smoke test that starts
the real sidecar as a process and checks the TAC that comes back over the
wire. An end-to-end test drives the real frontend against the real parser.

Each half has been mutated on purpose, to check that the other half notices.
An attribute read turned into a field read, and the receiver slot moved to the
end of the argument list: both make the tests red.

## 6. What this document does not claim

| Claim | Status |
|---|---|
| Python 3 source is lowered into the TAC | True, and tested against the real parser |
| The lowering finds every taint flow | Not claimed: the analysis is intra-procedural, and an attribute written in one method is not seen by another |
| Python produces findings | Not yet: the sink catalogue and the semantics table for Python do not exist |
| A callee's owner is always right | Not claimed: a method whose receiver's type cannot be inferred falls back to the module's name, and that is reported |
| Python 2 is supported | False, and deliberately: the enumeration is `PYTHON3` and a Python 2 interpreter is refused |

## Appendix: where the facts come from

| Fact | Where it comes from |
|---|---|
| The grammar | CPython 3's own `ast` module, through the interpreter that runs the sidecar |
| The builtins | The running interpreter's own `builtins` module, read rather than copied |
| The wire format | [`plumb-frontend/python-sidecar/PROTOCOL.md`](../../plumb-frontend/python-sidecar/PROTOCOL.md) |
| The Java frontend | [`PythonTacFrontend.java`](../../plumb-frontend/src/main/java/org/plumb/tac/frontend/python/PythonTacFrontend.java) |
| The translator | [`plumb-frontend/python-sidecar/src/plumb_translator.py`](../../plumb-frontend/python-sidecar/src/plumb_translator.py) |
| The smoke test | [`plumb-frontend/python-sidecar/smoketest.py`](../../plumb-frontend/python-sidecar/smoketest.py) |
