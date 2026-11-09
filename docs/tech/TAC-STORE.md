# The TAC Store: Three Stages That Run Apart

[English](../README.md) | [中文](../zh/tech/TAC-STORE.md)

This document describes the TAC store: the on-disk form of the intermediate representation that lets
the three stages of a scan -- capture the command, build the TAC, analyse the TAC -- run at different
times, in different places, on different machines.

## 1. Why the stages are separate

A scan has three kinds of work, and they have three different costs and three different lifetimes:

| Stage | What it does | What it needs | What it produces |
| --- | --- | --- | --- |
| 1. Capture | Record the compiler invocation | The build itself | A capture entry per compilation unit |
| 2. Build | Turn the captures into TAC | The sources and the classpath | A TAC store |
| 3. Analyse | Run the data-flow engine over the TAC | The TAC store | Findings |

Splitting them buys three things.

**The build is not repeated.** Stage 2 reads a capture file, not a build log, so it does not have to
re-run the compiler. The same capture file can be built many times against different analyses.

**The analysis does not need the sources.** Stage 3 reads the TAC store and never opens a source
file. The store can be copied to another machine -- a build agent, a CI runner -- and analysed there.
This is the property that makes the split worth the extra file format: the analysis's inputs are
*data*, not a source tree.

**The three stages fail independently.** A capture that cannot be rebuilt is a stage-2 problem and
does not stop stage 3 from analysing what it already has.

## 2. The capture stage

The capture stage records what the compiler was asked to do. `plumb-build-capture` produces a
`CaptureEntry` per compilation unit, and the export step turns a set of entries into a compilation
database.

### 2.1 The compilation database format

The export is a JSON **array**. Each element has exactly three keys:

```json
[
  {
    "directory": "D:/work/project/src/main/java",
    "arguments": ["javac", "-cp", "lib/a.jar", "Main.java"],
    "file": "D:/work/project/src/main/java/Main.java"
  }
]
```

| Key | What it holds | How it is normalised |
| --- | --- | --- |
| `directory` | The working directory of the compilation | Separators become `/`; the drive letter is preserved; the case is preserved |
| `arguments` | The compiler's argument vector | **Kept verbatim** -- the backslashes stay |
| `file` | The source file the entry describes | Separators become `/`; the drive letter is preserved |

The asymmetry between `arguments` and the two path fields is deliberate. `arguments` is what the
compiler actually received: changing a backslash to a forward slash would change the command, and a
compiler on Windows accepts both while a compiler on Linux accepts only one. `directory` and `file`
are what a consumer has to resolve, and a consumer resolves them against a filesystem, so they are
normalised to one spelling.

### 2.2 The path normalisation rules

| Rule | Effect |
| --- | --- |
| Separators | Every `\` becomes `/` |
| Drive letters | `D:\x` becomes `D:/x` -- the letter and its colon stay |
| Case | Unchanged -- a path's case is not the producer's to decide |
| Absolute paths | `..` above the root is discarded: `D:/a/../../b` becomes `D:/b` |
| Relative paths | `..` is kept: `a/../b` stays `a/../b` |

The last two rules differ on purpose. An absolute path has a root, so a `..` that would climb above
it is meaningless and is dropped. A relative path has no root to climb above, so its `..` is
meaningful and is kept.

### 2.3 How the format is verified

The format is checked against a reference implementation rather than against a recorded output. The
reference implementation is compiled from source into a probe executable, and both implementations
are given the same inputs and their outputs compared byte for byte.

That matters because a golden file records one answer, while the comparison asks a question: *do the
two implementations agree on every input, including the awkward ones?* The probe's inputs include
control characters, non-ASCII characters, drive letters, and parent-directory references -- the
cases where a normalisation rule is most likely to be wrong in a way that a single golden file would
happily record as correct.

The probe's inputs are given in an escaped wire form (`\n`, `\t`, `\uXXXX`) because one case per line
cannot express a string that contains a newline.

## 3. The build stage

The build stage reads captures and produces a TAC store.

### 3.1 From a capture to a module

Each capture entry becomes one compilation module. The mapping is mechanical:

| From the capture | To the module |
| --- | --- |
| The compiler's language | The front end that parses it |
| The source files | The module's source file list |
| The classpath | The module's classpath |
| The source root | The module's source root, when the sources sit under one |

Java, Kotlin, Scala and C# each have their own front end, and the language of a capture selects
between them. The front ends share one form: `parse(source, path, context)`.

### 3.2 The store's shape

The store is a SQLite database. The choice is not about the query language -- there are no queries
over the IR -- it is about the file format:

| Requirement | A SQLite file | One file per program | A directory of files |
| --- | --- | --- | --- |
| Be one copyable artefact | Yes | No | No |
| Be updated while a build runs | Yes | Awkward | Awkward |
| Survive a partial write | Yes (transactions) | No | No |
| Be inspectable without the engine | Yes | Partly | Partly |

The IR itself is a tree of polymorphic objects, so storing it as columns would mean a schema per
node kind and a migration whenever a field changes. The store keeps the IR as a **blob** and lets
the existing serializer define its shape:

```
┌──────────────┬──────────────┬─────────────────┐
│   MAGIC      │   VERSION    │   <Object>      │
│ 0x504C554D   │ 0x00000001   │ Java serialized  │
│  (4 bytes)    │  (4 bytes)    │ (any IR object) │
└──────────────┴──────────────┴─────────────────┘
```

The header is the same one the file-based cache uses, so a blob and a `.tac` file are the same
bytes. A store's contents can be extracted and read by the file reader, and a file can be loaded
into a store.

### 3.3 The tables

| Table | Key | Holds |
| --- | --- | --- |
| `programs` | `cache_key` | One `TacProgram` per source file |
| `merged` | -- | The merged program the analysis consumes |
| `manifest` | key | The metadata the store was built with |

The cache key identifies a program by what it was built from -- its source path and its content --
so a rebuild that produces the same program replaces the old one instead of accumulating copies.

## 4. The analysis stage

The analysis stage reads the store and reports findings. It never opens a source file.

### 4.1 Path remapping

The store records the paths the build saw. The analysis may run on a machine whose filesystem is
laid out differently: a build agent's workspace is not the reviewer's checkout.

So the paths are remapped at load time, and **the IR is not modified**. The path inside the IR is
the store's identity -- it is part of the cache key, and two stores that differ only in where they
were built would be indistinguishable if the path were rewritten. The remapping happens on the way
in, after the store has been opened and before the analysis begins.

A mapping is a pair of roots:

```
<producerRoot>=<consumerRoot>
```

and a path under the producer's root is re-expressed under the consumer's root. A path outside every
producer root is left alone.

### 4.2 What the stage deliberately does not do

The analysis stage does not read the source tree. A source-aware step -- resolving a static field's
value by opening its declaring file, say -- would make the answer depend on what happens to be
present on the machine, and the same store would then produce different findings on two machines.

The engine's own cross-function machinery already carries what it needs across function boundaries,
so nothing is lost by the restriction.

## 5. Running the stages

The stages are subcommands. The existing flat command line is unchanged: a first argument that is
not a subcommand goes down the original path.

| Subcommand | Stage | Reads | Writes |
| --- | --- | --- | --- |
| `capture` | 1 | Captures | A compilation database |
| `build` | 2 | Captures or a compilation database | A TAC store |
| `analyze` | 3 | A TAC store | Findings |

| Subcommand | Options |
| --- | --- |
| `capture` | `--capture-dir <dir>`, `--output <file>` |
| `build` | `--capture-dir <dir>` **or** `--compdb <file>`, `--output-dir <dir>` |
| `analyze` | `--store <file>`, `--output <file>`, `--source-path-map <producer>=<consumer>` |

`capture` writes the compilation database to standard output when `--output` is not given.
`analyze` writes its report to standard output when `--output` is not given.

### 5.1 `build` and its two inputs

`build` reads its input from one of two places, and the two are sources of the same thing: the
capture directory holds this project's own agent's capture files, and `--compdb` names a compilation
database. The database is the interchange format -- it is what a build hands to an analyser, and
what an analyser written against another producer's output is handed as well -- so a database from
any producer is read the same way. The two options are mutually exclusive.

The language of each entry decides which front end reads it, and what it is read for. A C# compiler
command carries the reference assemblies, the preprocessor symbols, the language version and the
nullable context, which are the inputs a C# compilation needs and cannot guess. A Go compiler
command carries the packages, their import paths, their language versions and their import
configurations, which are the inputs a Go type checker needs and cannot invent.

See [`CSHARP-FRONTEND-ANALYSIS.md`](CSHARP-FRONTEND-ANALYSIS.md) and
[`GO-FRONTEND-ANALYSIS.md`](GO-FRONTEND-ANALYSIS.md) for what each front end does with them.

### 5.2 `analyze` and the path remapping

```
plumb analyze --store <store> [--output <file>] [--source-path-map <producer>=<consumer>]
```

The remapping option takes one `producer=consumer` pair, and it may be given more than once -- each
occurrence adds one pair.

### 5.2 A round trip across machines

The point of the split is that the store is portable. The sequence is:

1. On the build machine: `capture` the compilation, then `build` the TAC.
2. Copy the store to the analysis machine.
3. On the analysis machine: `analyze` the store, with `--source-path-map` when the two machines lay
   their paths out differently.

The third step needs no source tree and no compiler.

## 6. The shape of the guarantee

The stages are separate, but they are not independent: the store's contents are exactly what the
build stage produced, and the analysis reports exactly what the store contains.

That is why the path remapping is a load-time concern. If the IR carried the consumer's paths, the
store would no longer be a faithful record of what was built, and a finding would name a location
that the build never saw.

The end-to-end test in `TacPipelineTest` walks the whole sequence -- capture, build, store, delete
the sources, analyse -- and asserts that the finding names the consumer's path rather than the
producer's. Deleting the sources is what makes the test meaningful: it fails if any step quietly
reaches for the source tree.
