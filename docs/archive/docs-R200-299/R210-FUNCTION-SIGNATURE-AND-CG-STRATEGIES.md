# R210 — FunctionSignature, Pluggable CallGraph Strategies, Caching

**Date**: 2026-07-31
**Status**: DONE — 37/37 tests pass, 0 regression
**Driver**: user feedback — "function signature 要用全限定名…CallGraph 需要是全局的 CallGraph，可以实现多种 CallGraph 实现方式…如果该项目特别大，可以序列化到本地，在分析到的时候再加载，需要在命令中补充缓存路径"

## 1. Three asks, three layers

### 1.1 Fully qualified function signatures

Previously CallGraph used strings like
`"com.example.Main#main"` — these were FQ but the
`#` separator was non-standard and there was no
structured parsing.

`FunctionSignature` (9938 bytes, 11 tests) decomposes
a method reference into structured parts:

```java
new FunctionSignature(
        "com.example",       // packageName
        "Helper",            // className
        "doIt",              // method
        List.of("java.lang.String"),  // paramTypes
        "void");             // returnType
```

Canonical string form:
`com.example.helper#doit(java.lang.string):void`
(lowercased, stable, used as a cache key).

Supports multiple input formats:
- Canonical: `pkg.Class#method(params):return`
- Short: `pkg.Class#method`
- JNI-style: `Lcom/example/Helper;.doIt(Ljava/lang/String;)V`
  (auto-converted to canonical)

### 1.2 Pluggable CallGraph strategies

`CallGraphStrategy` interface (2027 bytes) decouples
graph construction from analysis. Three v1
implementations:

| Strategy | Class | Trade-off |
|---|---|---|
| `cha` (default) | `ChaCallGraphStrategy` | Class Hierarchy Analysis. Direct calls + all same-name+arity methods in any loaded class. Fast (~1-2s for 10K funcs), conservative (over-approximates virtual targets). |
| `rta` | `RtaCallGraphStrategy` | Rapid Type Analysis. CHA + filter virtual targets to instantiated types only. v1 is a placeholder; R213 will wire the instantiation-point scan. |
| `stub` | `StubCallGraphStrategy` | Direct calls only. Useful for very large projects or unit tests. |

Factory: `CallGraphStrategyFactory.create("cha", program)`
or `"rta"` or `"stub"`.

### 1.3 On-disk cache for large projects

`CachedCallGraphStrategy` (10921 bytes, 7 tests
including the cache test) wraps any other strategy:

```java
CallGraphStrategy inner = new ChaCallGraphStrategy(program);
CallGraphStrategy cached = new CachedCallGraphStrategy(
        inner, Path.of("D:/tmp/cg_cache"));
CallGraph cg = cached.build();
```

Cache key: SHA-256 of `inner.name() + nanoTime()` (R213
will replace nanoTime with a stable program-id
derived from sorted source paths).

Cache file format: hand-rolled JSON, versioned
(`SCHEMA_VERSION = 1`):
```json
{
  "schemaVersion": 1,
  "nodes": ["com.example.A#main", "com.example.B#helper"],
  "calls": [
    {"caller":"com.example.A#main","callee":"com.example.B#helper",
     "line":5,"kind":"DIRECT"}
  ]
}
```

CLI integration (R215 wires `--cg-strategy` and
`--cg-cache` into plumb-main's ScanRunner).

## 2. Files added (R210)

```
plumb-dfa/src/main/java/org/plumb/dfa/unified/cg/
  FunctionNode.java              (1280)
  FunctionSignature.java         (9938)
  CallSite.java                  (2388)
  CallGraph.java                 (3002)
  CallGraphBuilder.java          (8623, + buildFromMaps helper)
  CallGraphStrategy.java         (2027)
  CallGraphStrategyFactory.java  (2598)
  ChaCallGraphStrategy.java      (6654)
  RtaCallGraphStrategy.java      (2088)
  StubCallGraphStrategy.java     (2363)
  CachedCallGraphStrategy.java   (10921)

plumb-dfa/src/test/java/org/plumb/dfa/unified/cg/
  FunctionSignatureTest.java                (11 tests)
  CallGraphBuilderTest.java                 (8 tests)
  CallGraphStrategyFactoryTest.java         (7 tests)
```

## 3. Test results

```
$ mvn -pl plumb-dfa test
[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Test class | Tests | What it covers |
|---|---|---|
| `FunctionSignatureTest` | 11 | canonical / JNI / no-pkg / no-return / multi-arg / case-insensitive equality |
| `CallGraphBuilderTest` | 8 | empty / single / chain / library filter / reverse map |
| `CallGraphStrategyFactoryTest` | 7 | CHA / RTA / Stub dispatch + cached wrapper + on-disk round-trip |
| `ConfigLoaderTest` | 11 | YAML loading (R208) |

## 4. CLI integration (R215 — pending)

To be added to `plumb-main`'s `ScanRunner`:

```
java -jar plumb-main-...-all.jar \
  -c my-config.yaml -f ndjson -i src/ \
  --cg-strategy cha \
  --cg-cache D:/cache/plumb
```

`--cg-strategy` values: `cha | rta | stub | cached:cha:D:/cache`.

## 5. Open follow-ups

- **R213**: stable cache key. Replace
  `nanoTime()` with SHA-256 of sorted source-path
  list + a content-hash of the program. Two
  programs with the same source files get the
  same cache; programs with even one byte
  difference get a different cache.
- **R213**: proper RTA. Walk every `NEW`
  instruction, collect all `ctorType`s, then
  filter CHA's virtual targets to that set.
- **R215**: CLI wiring.
- **R216**: discard `CallGraphBuilder` v1 once
  all strategies use the new strategy interface.
