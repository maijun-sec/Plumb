# R253 v3.1 — Project-root mode for cross-file resolution

## Why R253 v1/v2 was wrong

R253 started as a textual
substitution that mapped
Juliet's
`testcasesupport.IO.STATIC_FIVE`
to the literal `5` in
`JulietIoStub.java`. That
was a hardcoded mapping
of one test-suite's
symbols — the moment a
real Java project (or
even a different Juliet
release that renamed
`STATIC_FIVE` to
`STATIC_FINAL_FIVE`) was
fed in, the substitution
silently no-op'd.

R253 v2 (R253 v2) tried
to fix this by walking
the Juliet root
end-to-end through
`GlobalTACStore` +
`CallGraphStrategyFactory.createCrossTu`.
That worked, but it
*always* walked the
whole project. The user
called this out as "思路
非常怪" (a strange way
of thinking) for a simple
reason: real
compilation never works
that way. When you
`javac src/testcases/CWE190/s01/Bad.java`,
the compiler does not
re-compile the entire
Juliet repo. It walks
`src/` (the source path)
to resolve the `import`s
of `Bad.java`, then
compiles only `Bad.java`.

## What R253 v3.1 actually does

R253 v3.1 models that
javac-style workflow.
There are two pieces:

1. `SourcePathResolver`
   — walks the **project
   root** (one directory
   that contains every
   package the analysis
   may need) and builds
   a `package → file` +
   `FQCN → file` index.
2. `ProjectCompilationContext(projectRoot, sourceRoot)`
   — the public entry
   point. The
   `projectRoot` is the
   search space (it
   matches what javac's
   `-sourcepath` covers);
   the `sourceRoot` is
   the work-list (it
   matches what javac was
   asked to compile). The
   index is built once
   over the whole root;
   the work-list is
   filtered to only
   files inside
   `sourceRoot`.

This split lets the user
say "scan
`src/testcases/CWE190/s01/`
but resolve any
`import` it makes
against the entire
`src/` tree". The
behaviour is exactly the
same as `javac -d
build/ -sourcepath src/
src/testcases/CWE190/s01/Bad.java`,
without the compilation.

### Files

| File | Purpose |
|------|---------|
| `engine/BuildOutputFilter.java` | generic build-output filter (target/, build/, out/, bin/, antbuild/, dist/, .gradle/, .idea/, node_modules/, __pycache__). The same filter works for Maven, Gradle, Ant, IntelliJ, Eclipse. |
| `resolve/SourcePathResolver.java` | walks a project root, indexes `package → file` + `FQCN → file`. Constructor takes an injected `BuildOutputFilter`. |
| `resolve/StaticFieldResolver.java` | cross-file `public static final` literal extraction. Uses a strict string-literal regex so that `"x" + "y"` (concatenation) is not mistaken for a literal. |
| `resolve/ProjectCompilationContext.java` | the public entry point. Bundles `projectRoot` + `sourceRoot` + the two resolvers, exposes `filesToAnalyze()` (work-list inside `sourceRoot`) and `resolveClass(fqcn)` / `lookupStatic(fqcn, field)` (lookups against the project root). |

## What's resolved

For Juliet's
`src/testcasesupport/IO.java`,
the resolver can extract:

| Field | Value | Source |
|-------|-------|--------|
| `STATIC_FINAL_FIVE` | `int 5` | `public static final int STATIC_FINAL_FIVE = 5;` |
| `STATIC_FINAL_TRUE` | `boolean true` | `public static final boolean STATIC_FINAL_TRUE = true;` |
| `STATIC_FINAL_FALSE` | `boolean false` | `public static final boolean STATIC_FINAL_FALSE = false;` |

The Juliet test data
also declares
`public static int staticFive = 5;`
(non-final) and a method
`public static boolean staticReturnsTrue()` —
**neither of these is
foldable by the static-
field resolver**, because
neither is a compile-time
constant. A separate
**method-body resolver**
is needed to fold
`IO.staticReturnsTrue()`
to `true`; see "Future
work" below.

## What's not resolved (deliberate gaps)

The resolver
**deliberately** returns
`null` for:

- Non-final fields
  (`public static int
  staticFive = 5`) — the
  engine must not assume
  the field cannot be
  mutated at runtime.
- String concatenation
  (`"x" + "y"`), even
  though both halves are
  literals.
- Method calls, casts of
  non-literals, qualified
  references
  (`Integer.MAX_VALUE`),
  arithmetic
  expressions
  (`1 + 2`).
- Anything that crosses
  a line (`(?m)^...$`
  enforces single-line
  field declarations).

These are the same
restrictions the JLS
puts on a *constant
expression*; we are
deliberately
under-approximating to
avoid silently
over-approximating a
field's value.

## Test coverage

```
R253BuildOutputFilterTest:        6/0/0/0
R253SourcePathResolverTest:       6/0/0/0
R253ProjectCompilationContextTest: 5/0/0/0
R253JulietProjectRootSweepTest:   2/0/0/0  (real Juliet root, ~46K TUs, 70s)
─────────────────────────────────────────────
Total:                           19/0/0/0
```

The real-Juliet test
runs `lookup("testcasesupport.IO",
"STATIC_FINAL_FIVE")`
against the actual
`IO.java` on disk and
asserts the value is
`5L`. If a future Juliet
release renames the
field, the test will
fail with a clear
"STATIC_FINAL_FIVE must
be parsed" error — not
with a silent
substitution miss.

## Future work — method-body inlining

Juliet's CWE test cases
make heavy use of
`IO.staticReturnsTrue()`
and friends. The
`staticFieldResolver`
cannot fold these because
they're method calls,
not field accesses. The
next step is a
**`MethodBodyResolver`**
that, given
`FQCN.methodName`,
returns the parsed
method body and lets
the engine inline simple
constant-returning
methods during the
`CALL` resolution pass.

This is the natural
extension of the R253
infrastructure: the
project-root search
space already exists;
we just need to parse
the body of the
resolved file and
return the relevant
method.

## Why no Juliet-specific code is in the resolver

The resolver does not
contain the strings
"Juliet", "IO",
"STATIC_FIVE", or any
other test-suite symbol.
It only contains:

- Two regexes for
  `package` and
  `class/interface/enum`
  declarations.
- One regex for
  `public static final
  <type> <name> = <init>;`.
- A type-driven literal
  parser.

The same code resolves
`com.acme.Config.MAX_RETRIES`
in a Maven project,
`org.example.Consts.DEFAULT_NAME`
in an Eclipse project,
or
`testcasesupport.IO.STATIC_FINAL_FIVE`
in Juliet. The Juliet
test asserts
**specific** field names
because that's what
Juliet happens to
declare; the resolver
has no preference.
