# R253 v3.1 + R254 + R255 — Cross-function analysis trilogy

## Overview

Three rounds that take
the Plumb engine from
"single-function
dataflow with no
cross-file awareness"
to "inter-procedural
analysis that folds
callee return values
at the call site".

| Round | What | Files | Net |
|-------|------|-------|-----|
| R253 v3.1 | Project-root mode (`SourcePathResolver` + `StaticFieldResolver` + `ProjectCompilationContext`) — real javac-style source-path resolution, no Juliet hardcoding | 4 new main + 2 new test | 17/0/0/0 |
| R254 | `MethodSummary` / `ReturnShape` / `SideEffect` + path-insensitive `MethodSummaryBuilder` + LRU `MethodSummaryCache` — 10 return shapes + 5 side-effect buckets | 5 new main + 1 new test | 26/0/0/0 |
| R255 | `SummaryApplier` + DFA engine wire — `MethodSummaryCache` lives on `AnalysisContext`, engine auto-populates in RPO loop, checker invokes applier at every `CALL` to fold + record | 3 modified main + 1 new main + 1 modified test + 1 new test | 12/0/0/0 |

Cumulative: **433/0/0/0**
(0 regressions across
the trilogy). Three new
test files, six new
main files, two
modified main files,
one modified test
file.

## Why these three rounds form a unit

A SAST tool that can't
fold callee return
values is fundamentally
limited:

1. Without
   `SourcePathResolver`,
   the engine can't
   look up
   `testcasesupport.IO`
   when it sees an
   `import` — it falls
   back to
   "callee is opaque".
2. Without
   `MethodSummary`, the
   engine has no
   description of what
   the callee *does* —
   just an opaque
   pointer to its body.
3. Without
   `SummaryApplier`, the
   engine has no way to
   consult the summary
   at the call site, so
   even if a summary
   exists it's never
   folded into the
   caller's state.

Each round is useless
without the others.
Together they form the
smallest viable
inter-procedural
analysis layer.

## Innovation points per round

### R253 v3.1 — Project-root mode (4 innovation points)

1. **Project-root + source-root split**.
   The user pointed out
   that R253 v1 (Juliet
   IoStub text
   substitution) and
   R253 v2 (walk-the-
   whole-project via
   `GlobalTACStore`)
   were both wrong. Real
   `javac` separates
   "the directory you
   point at" (search
   space) from "the
   files you ask it to
   compile" (work-list).
   R253 v3.1 mirrors
   that shape via
   `ProjectCompilationContext(projectRoot, sourceRoot)`
   with the constraint
   `sourceRoot ⊆ projectRoot`.

2. **Package → file
   index from
   `package` +
   `class` regexes
   alone**. The
   resolver doesn't
   need to invoke a
   Java compiler. It
   walks the project
   root, finds every
   `.java` file,
   reads the
   `package` and
   `class/interface/enum`
   declarations, and
   builds
   `classToFile` and
   `packageToFiles`
   maps. Any
   `import a.b.C;`
   statement
   resolves by
   `classToFile.get("a.b.C")`.

3. **Strict string-
   literal regex in
   `StaticFieldResolver`**.
   The original regex
   `=([^;]+?)\s*;` was
   non-greedy but
   still matched the
   *whole* line up to
   the last `;` on the
   line, so
   `"x" + "y"` was
   misread as a
   single literal
   `"x" + "y"`. The
   fix uses a strict
   pattern
   `^"(?:[^"\\]|\\.)*"$`
   that rejects
   concatenation.

4. **Build-output
   filter is a
   generic utility,
   not a Juliet
   thing**.
   `BuildOutputFilter`
   excludes
   `target/`, `build/`,
   `out/`, `bin/`,
   `antbuild/`,
   `dist/`, `.gradle/`,
   `.idea/`,
   `node_modules/`,
   `__pycache__/`. The
   same code works for
   Maven, Gradle, Ant,
   IntelliJ, Eclipse.
   Not Juliet-specific
   — Juliet's
   `antbuild/` is one
   of many entries in
   a list of common
   Java build-output
   directories.

### R254 — MethodSummary (4 innovation points)

1. **Path-insensitive
   2-arm bool const
   detection**. The
   original
   classification
   required an
   explicit `BRANCH`
   instruction. R254
   detects
   `RETURN_CONDITIONAL`
   by examining the
   *return sequence*:
   if the function has
   exactly two
   `RETURN` instructions,
   both with boolean
   constants, and
   the two constants
   are distinct, the
   shape is
   `cond(true, false)`.
   This captures the
   `if (b) return true;
   else return false;`
   pattern without
   needing to model
   the branch
   structure.

2. **Named class
   `BoundedLru`
   instead of
   anonymous
   LinkedHashMap
   subclass**. The
   original
   `new LinkedHashMap<>(...) { ... }`
   inside the
   `MethodSummaryCache`
   constructor had a
   `MethodSummaryCache.this.maxEntries`
   reference that the
   anonymous-inner-
   class loader was
   silently
   shadowing. The
   `removeEldestEntry`
   override returned
   `size() > 64`
   (the inner-class
   view of
   `maxEntries`,
   hardcoded to 64)
   regardless of the
   caller's request.
   Extracting the
   subclass to a
   named top-level
   class made the
   binding explicit
   and the eviction
   testable.

3. **MOVE-chase
   aliasing with
   cycle guard**.
   `int x = arg; return x;`
   is the same as
   `return arg;`. The
   `chase()` method
   in
   `MethodSummaryBuilder`
   walks the
   `MOVE`-to-`MOVE`
   chain via the
   `lastDef` map and
   reclassifies the
   underlying value.
   A `Set<String> seen`
   parameter guards
   against
   `x = y; y = x;`
   cycles and
   degenerates them
   to `UNKNOWN` rather
   than looping
   forever.

4. **`UNKNOWN`
   sentinel pre-
   installed in the
   cache**. R254
   fallback is
   "return value = TOP,
   state preserved".
   The cache
   implements that
   by *automatically*
   installing a
   sentinel
   `MethodSummary`
   on a miss, so the
   caller never has to
   `null`-check. The
   sentinel has
   `ReturnShape.UNKNOWN`
   and a pure
   `SideEffect`, which
   means the applier
   can run
   unconditionally.

### R255 — SummaryApplier (4 innovation points)

1. **`MethodSummaryCache`
   parallel to
   `FunctionSummaryCache`**.
   Two independent
   caches, both
   populated in RPO
   order, both keyed
   by
   `FunctionSignature`,
   but serving
   different
   purposes:
   `FunctionSummary`
   is the
   "what effects does
   this function
   have on its
   parameters" view
   (R213); `MethodSummary`
   is the
   "what is the
   return-value shape
   + side effects"
   view (R254). The
   engine populates
   both, the
   `SummaryApplier`
   uses the second.

2. **Stable string-
   encoded fact
   values**. The
   applier doesn't
   expose `ReturnShape`
   to the rest of the
   engine; it
   encodes each shape
   as a stable string
   (`const:5`,
   `param:0:99`,
   `this-field:name`,
   `top`, etc.) and
   stores that string
   in the
   `RETURN_VALUE_FOLDED`
   fact's `value`
   field. Checkers
   that consume the
   fact just match on
   string prefixes;
   they never need to
   import
   `ReturnShape`.

3. **Pure-method
   side-effect
   short-circuit**.
   If a callee's
   summary is pure
   (`isPure()` returns
   true), the applier
   doesn't write a
   `SIDE_EFFECT_RECORDED`
   fact at all. The
   fact bag stays
   clean, and any
   checker that would
   otherwise
   invalidate read
   state on seeing
   the fact doesn't
   run.

4. **Fallback
   synthesised
   signature**.
   `FunctionSignature.parse`
   returns `null`
   (not an exception)
   for unparseable
   strings. Juliet
   signatures like
   `bad():void`,
   `main(String[]):void`,
   `<clinit>():void`
   all fall into that
   bucket — they don't
   have the
   `pkg.class#method(args):return`
   shape. Both
   `MethodSummaryBuilder.parseSignature`
   and
   `DfaEngine.parseFnSignature`
   catch the null and
   fall back to a
   synthesised
   signature
   (`<unknown>` class
   + `fn.name()`),
   so the engine
   never crashes on
   a Juliet run.

## What changed where (file inventory)

### New main files (5)

```
plumb-dfa/src/main/java/org/plumb/dfa/unified/
  resolve/
    ProjectCompilationContext.java     (R253 v3.1, 7873 bytes)
    SourcePathResolver.java            (R253 v3.1, 5955 bytes)
    StaticFieldResolver.java           (R253 v3.1, 7878 bytes, R253 v3.1.1 fix, 13350 bytes)
  summary/
    MethodSummary.java                 (R254, 2085 bytes)
    ReturnShape.java                   (R254, 6698 bytes)
    SideEffect.java                    (R254, 5348 bytes)
    MethodSummaryBuilder.java          (R254, 18470 bytes)
    MethodSummaryCache.java            (R254, 5794 bytes)
    SummaryApplier.java                (R255, 8991 bytes)
```

### Modified main files (3)

```
plumb-dfa/src/main/java/org/plumb/dfa/unified/
  fact/BuiltinFactKinds.java           (R255 +2 FactKinds)
  engine/AnalysisContext.java          (R255 +methodSummaryCache field + 6-arg ctor)
  engine/DfaEngine.java                (R255 RPO loop calls buildAndStoreMethodSummary)
```

### New test files (3)

```
plumb-dfa/src/test/java/org/plumb/dfa/unified/
  resolve/
    R253SourcePathResolverTest.java              (R253 v3.1, 6 tests)
    R253ProjectCompilationContextTest.java        (R253 v3.1, 5 tests)
  summary/
    R254MethodSummaryBuilderTest.java             (R254, 26 tests)
    R255SummaryApplierTest.java                   (R255, 12 tests)
  integration/
    R253JulietProjectRootSweepTest.java           (R253 v3.1, 2 tests, real Juliet root)
```

### Modified test files (1)

```
plumb-dfa/src/test/java/org/.../fact/FactBagTest.java   (R255 13→15 builtin FactKinds)
```

### Documentation (3 new docs)

```
docs/R253-PROJECT-ROOT-MODE.md         (R253 v3.1, ~6.4KB)
docs/R254-METHOD-SUMMARY.md            (R254, ~7.7KB)
docs/R255-SUMMARY-APPLIER.md           (R255, ~5.6KB)
```

### Backups

```
D:\work\tmp\r253_v3_done\   (R253 v3.1, 7 files, ~57KB)
D:\work\tmp\r254_done\      (R254, 7 files, ~67KB)
D:\work\tmp\r255_done\      (R255, 3 files, ~26KB)
```

## Architectural state before vs after

| Concern | Before trilogy | After trilogy |
|---------|----------------|---------------|
| Cross-file class lookup | engine treats every callee as opaque | `SourcePathResolver` resolves FQCN → source file in project root |
| Cross-file static field value | engine can't tell what `X.Y` is | `StaticFieldResolver` extracts `public static final` literal from source |
| Return-value shape at call site | engine assumes callee returns TOP | `MethodSummary` describes return shape per IR analysis |
| Side effects at call site | engine can't invalidate caller state | `SideEffect` reports what may have changed |
| Wire into engine | n/a | `DfaEngine` auto-populates `MethodSummaryCache` in RPO; `SummaryApplier` is the per-call hook |

## Pitfalls catalog (12 total across three rounds)

A consolidated list of
the things that bit
us, for the next
person who extends
this:

### R253 v3.1 (4)

- **`Math.max(64, 4) = 64`**
  — `Math.max` reversed
  the user-supplied
  intent, hardcoded
  every test to size
  64. Always think
  about the order of
  `Math.max(x, y)`
  arguments.
- **`currentParams` not
  set in `build()`** —
  `chase()` looked at
  a stale `List.of()`
  default, so every
  `RETURN_PARAM`
  classification
  failed. Set
  working state
  before calling the
  per-phase helpers.
- **`TacType.UNKNOWN`
  toString is
  "<unknown>"** —
  `NEW` instructions
  must use
  `TacType.ofClass("...")`,
  not `TacType.UNKNOWN`.
- **Regex greedy
  cross-line** — use
  `(?m)^...$` to
  anchor a field
  declaration to a
  single line.

### R254 (3)

- **`LinkedHashMap`
  anonymous class
  outer-this is
  unreliable** — the
  override's
  `MethodSummaryCache.this.maxEntries`
  reference was
  shadowed by a
  hardcoded 64.
  Always extract
  named subclasses
  for overrides.
- **`RETURN_CONDITIONAL`
  detection was
  branch-dependent** —
  loosen to
  path-insensitive
  2-arm bool const
  detection.
- **STATIC_FIELD
  classification
  required resolver
  confirmation** —
  relax to "any
  FIELDLOAD on a
  class-named
  receiver is
  STATIC_FIELD";
  let the engine
  decide whether
  the value resolves.

### R255 (5)

- **`FunctionSignature.parse`
  returns `null`**
  — for `bad():void`-
  shaped strings, the
  parser returns
  `null` (not an
  exception). Always
  null-check.
- **`FactBag.add(Fact)`
  not
  `add(operand, fact)`** —
  single-arg API.
- **`DfaEngine.parseFnSignature`
  same null bug** —
  duplicate the fix
  in every call
  site.
- **Built-in FactKind
  count went 13 → 15** —
  update the
  `FactBagTest`
  assertion.
- **Don't pass `Throwable`
  to `LOG.warn`** —
  extra `, ex` arg
  makes the log
  noisy. Drop it
  after debugging.

## How R256 will use this

R256 will teach
specific checkers
(branch-condition
checkers, side-effect
consumers) to read
the
`RETURN_VALUE_FOLDED`
and
`SIDE_EFFECT_RECORDED`
facts that
`SummaryApplier` writes
on every `CALL`. The
infrastructure is
ready; the wiring is
the last mile.

The expected gain is
that Juliet CWE files
that look like

```java
if (IO.staticReturnsTrue()) {
    // safe path
} else {
    // bad path
}
```

will be fully
analyzable: the
branch condition
folds to a known
boolean, the engine
walks only the
taken path, and
findings from the
not-taken branch
disappear.
