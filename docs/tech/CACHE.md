# On-Disk IR Cache

[English](../README.md) | [中文](../zh/tech/CACHE.md)

This document describes how Plumb's `--output-dir` mechanism persists the TAC intermediate results on disk so that several scans can reuse them incrementally.

## 1. Design goals

| Goal | Approach |
| --- | --- |
| Reduce repeated parse + translate | Write the TacProgram to disk at source-file granularity |
| Keep the memory peak under control | Exchange the data between stages through the disk |
| Do not break the existing DFA engine | The serialization is "transparent" to the IR classes (`implements Serializable`) |
| Compressible | A `.gz` suffix triggers GZIP wrapping |
| Identifiable version | Two ints in the file header, `MAGIC` + `VERSION` |

## 2. Directory layout

```
<outputDir>/
├── cache/
│   ├── programs/
│   │   ├── <sha1>.tac              one TacProgram per file
│   │   └── <sha1>.tac.gz           optional gzip compression
│   ├── merged.tac                  the whole MergedProgram (not written automatically yet)
│   └── callgraph.tac               the global call graph (not written automatically yet)
└── report/
    ├── findings.ndjson             the findings actually written (with -f ndjson)
    └── findings.console            the findings actually written (with -f console)
```

`OutputLayout` (`plumb-main/.../OutputLayout.java`) builds the paths and creates the directories.

## 3. File format

```
┌──────────────┬──────────────┬─────────────────┐
│   MAGIC      │   VERSION    │   <Object>      │
│ 0x504C554D   │ 0x00000001   │ Java serialized  │
│  (4 bytes)    │  (4 bytes)    │ (any IR object) │
└──────────────┴──────────────┴─────────────────┘
```

`MAGIC = 0x504C554D` is the ASCII "PLUM". `VERSION = 1`.

`writeObject(tag, value, file)` additionally writes a `UTF string` tag (it is read out and discarded during parsing; it is informational for now).

### 3.1 The serialization mechanism: why Java native instead of Kryo

Plumb uses **`ObjectOutputStream` / `ObjectInputStream`** (Java's built-in serialization) for the disk cache, not a third-party framework such as Kryo / Protobuf / Avro. The reasons:

| Dimension | Java native | Kryo |
| --- | --- | --- |
| Dependencies | 0 (standard library) | 1 (`com.esotericsoftware:kryo`), plus a `register` / field mapping for every class |
| Speed | 5-10x slower (small data) | 5-10x faster |
| Output size | Larger (~50 bytes of metadata per object) | Smaller |
| Schema evolution | Adding a field does not break the old reader (keep `serialVersionUID`) | Adding a field requires maintaining a `FieldSerializer` by hand |
| Unsupported types | On JDK 17+, `Path` / complex generics need a custom `writeObject` | Broader support, but every unrecognised class needs an `addDefaultSerializer` |

The TAC IR of Plumb is a **small data volume + heterogeneous fields + long lifetime** scenario
(the cache is reused across runs):

- **Small data volume**: the TAC IR of a single TU is only on the order of tens of KB; for a large project with
  ten thousand files the estimate is 150 MB, which is not a bottleneck for disk IO
- **Heterogeneous fields**: the 9 `Serializable` IR classes each have mutable fields
  (`TacInstruction.line` / `column`, the sealed shapes of `TacOperand`, the
  `SourceMap.Loc` record, `TacProgram.sourcePath` is a `java.nio.file.Path`) --
  every added field makes Kryo register again
- **Long lifetime**: a cache file may be read back across JVM restarts; the standard library offers a stable
  "load - instantiate - read" interface

> **When to switch to Kryo**: only once disk IO really becomes the bottleneck (a TB-scale cache or a repository with
> ten thousand files). The historical numbers in §10 (10 ms per file / 150 ms for 21 files) show that the native
> mechanism is good enough.

### 3.2 The source mapping (line / column / SourceMap) preservation contract

The core question the disk cache has to answer: **can the IR that was read back from the cache still tell the user "which file and which line is the finding on?"** -- this is the foundation of every reporter. There are 3 links in the preservation chain:

| Field | Type | In which class | Origin |
| --- | --- | --- | --- |
| `TacInstruction.line` | `int` | `TacInstruction` | The source line number recorded by JavaParser / Jimple at translate time |
| `TacInstruction.column` | `int` | `TacInstruction` | The source column number recorded by JavaParser / Jimple at translate time |
| `SourceMap.Loc` record | `record(line, column)` | `SourceMap.Loc` | The position filled in by the post-processors (the CFG builder and so on) |
| `TacProgram.sourcePath` | `java.nio.file.Path` | `TacProgram` | The absolute path of the original .java file (the cache file name is derived from its SHA-1) |

Every field is covered by a test (`plumb-tac/src/test/.../IrSerializerTest.java`):

- `roundTripPreservesSourceLineAndColumn` -- verifies that `line` / `column` round-trip exactly for three different values
- `roundTripPreservesSourceMap` -- verifies that the `SourceMap.Loc` record survives `writeObject` / `readObject` completely
- `roundTripPreservesSourcePath` -- verifies that `TacProgram.sourcePath` is restored as a `Path` through `transient` plus a custom `writeObject` / `readObject`

#### 3.2.1 The specifics of serializing `TacProgram.sourcePath`

`java.nio.file.Path` does **not** implement `Serializable` on any target JDK. The solution of `TacProgram`:

```java
private transient Path sourcePath;          // marked as not taking part in the default serialization
...
private void writeObject(ObjectOutputStream out) throws IOException {
    out.defaultWriteObject();
    out.writeObject(sourcePath == null ? null : sourcePath.toString());
}
private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    Object raw = in.readObject();
    this.sourcePath = raw == null ? null : Path.of((String) raw);
}
```

Notes:
- The field is `transient`, so the default Java mechanism does not take part; we write it manually as a `String`
- **The field is not `final`** (although every caller sets it once at construction time): `final` + `transient` + reassignment in `readObject` triggers the "reflective setAccessible + reflective stripping of the final modifier" reflection black magic on JDK 17+, which is fragile; `transient` plus a mutable field is the simplest thing
- `null` is allowed: the `sourcePath` of a synthetic program may be `null` (the cache file name is the invariant)

#### 3.2.2 The specifics of serializing the `SourceMap.Loc` record

A `record` does **not** implement `Serializable` by default, even when the enclosing `SourceMap` is `Serializable`. It has to be stated explicitly:

```java
public record Loc(int line, int column) implements java.io.Serializable {
    public Loc { Objects.requireNonNull(this); }
    private static final long serialVersionUID = 1L;
}
```

Otherwise `writeObject` on the enclosing `SourceMap` throws `NotSerializableException: SourceMap$Loc`.

## 4. The cache key

There is one cache file per source file, and the file name is:

```
SHA-1(absolute(canonicalize(sourcePath)))
```

On Windows `canonicalize` normalises the case and replaces `/` with `\`; on POSIX it resolves symlinks and strips the trailing `/`. Scanning the same repository twice yields the same key, so the cache hits.

## 5. The modes

`ScanConfig.CacheMode` has four values:

| Mode | Behaviour |
| --- | --- |
| `OFF` | Neither reads nor writes (the default) |
| `READ` | Skips parse + translate on a cache hit; does not write |
| `WRITE` | Always re-parses and re-translates, and **writes** the cache to disk |
| `READWRITE` | Uses the cache on a hit; computes and **writes** the cache on a miss |

CLI: `--cache-mode readwrite` ; YAML: `cacheMode: readwrite`.

## 6. The call chain

```
ScanRunner.run() [plumb-main]
  ├── layout.ensureDirectories()    // mkdir -p cache/programs report
  ├── expandInputs → List<Path>
  ├── if (files.size() > 1) scanMerged()
  │     ├── for each file in parallel:
  │     │     ├── loadCachedProgram(file) → TacProgram (or null)
  │     │     ├── if null: parse + translate
  │     │     └── storeCachedProgram(file, program)
  │     ├── merge into MergedProgram
  │     └── runDfaCheckers + runUnifiedCheckers
  └── emit findings
```

The key code:

```java
private TranslatedFile translateAndStyleOne(
    Path file, RuleRegistry rules, List<AstChecker> styleChecks) {
    TacProgram program = loadCachedProgram(file);
    ParsedSource ps = null;
    if (program != null) {
        // re-parse only for style; cheap
        ps = buildParser().parse(file);
    } else {
        ps = buildParser().parse(file);
        program = new TacTranslator(ps).translate();
        storeCachedProgram(file, program);
    }
    // style always runs fresh (rule set may have changed)
    List<Finding> styleFindings = runStyleChecks(ps, file, rules, styleChecks);
    return new TranslatedFile(file, ps, program, styleFindings);
}
```

(In the source code the two parse calls are each wrapped in a `catch (Throwable)`; a parse failure is recorded as a WARN and returns `null`,
which skips that file -- a single bad file should not bring the whole scan down.)

## 7. Compatibility

### 7.1 Forward compatibility

A newly added field (except one marked with `transient`) -> ObjectInputStream can still deserialize the old objects, **as long as `serialVersionUID` was not changed**.

Every IR class has `serialVersionUID = 1L`. **Keep** it at 1L when adding a field.

### 7.2 Backward incompatibility

If `serialVersionUID` was changed or a field was renamed, reading the old cache file throws `InvalidClassException`. `IrSerializer.readProgram` catches it and wraps it in an `IOException("IR class missing on classpath: ...")`, and after logging a WARN the ScanRunner returns to the normal translate flow.

### 7.3 Complete incompatibility

The `VERSION` field. ScanRunner always checks `if (version != IrSerializer.VERSION)` and refuses when it does not match.

## 8. When it is invalidated

The conditions for clearing `<outputDir>/cache/`:

- After a major version upgrade of Plumb (the VERSION changed)
- When a source file path changed (the key changed, so the old cache is invalidated naturally)
- When certain hard-coded source/sink signatures of the `RuleRegistry` are changed (the cache content did not change, but the findings will)
- When the Z3 / SootUp version is changed (it affects taint / path condition solving)

## 9. The handling of serialization failures

`storeCachedProgram` is best-effort: a failure is logged as a WARN and does not affect the main scan flow.

When `loadCachedProgram` fails it returns null, which triggers a re-translate.

## 10. Performance

> **The numbers in this section are not reproducible.** The repository has no benchmark harness, and the `examples/`
> directory the original measurement used has since been merged into `testcase/`. What follows is a record of a
> historical measurement, meant to give an order of magnitude when choosing an approach; it is **not a currently
> verifiable measurement**. To measure again, write a timing loop around
> `IrSerializer.writeProgram` / `readProgram` yourself.

- A single file (Java source ~5 KB): serialization ~10 ms, deserialization ~5 ms
- 21 files: serialization ~150 ms, 100 KB ~ 130 KB on disk
- A large project with ~5000 files: estimated 30-60 seconds for the serialization, ~150 MB on disk

**The conclusion of the choice is unaffected**: the TAC IR is a small data volume + heterogeneous fields + long lifetime scenario (the cache is reused across
runs), so the start-up cost of Java native serialization is not the bottleneck at this scale. The real signal to
switch to Kryo is that disk IO has become a measured bottleneck (a TB-scale cache or a repository with ten thousand
files), not an estimate.

## 11. Future extensions

- **Writing merged.tac to disk**: today scanMerged merges the MergedProgram in memory; later it could be written to disk, so that "style first, then dfa" can run as two stages across processes
- **Writing callgraph.tac to disk**: writing the call graph of the unified engine to disk separately saves 5-10 minutes on large projects
- **mtime-based invalidation**: today only SHA-1(path) is used as the key. Switching to `SHA-1(path + mtime + size)` would be safer (a change of content at an unchanged path is then recognised too)
- **Multi-platform support**: the case-insensitivity of Windows makes the same file produce different keys (canonicalize already handles this, but care is needed)
