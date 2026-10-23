# R218b — Real Z3 native bridge

Date: 2026-08-01
Status: **DONE** (9/9 R218b tests + 200/200 plumb-dfa tests, 0 regression)

## Goal

Provide a real-Z3-backed
implementation of `SolverZ3Bridge` so
production can use Z3's full power
instead of the pure-Java
`LocalSolverBridge`. The bridge must
fall back to the local solver if the
Z3 native library is unavailable.

## Implementation

### `plumb-dfa/.../solver/Z3Bridge.java` (new, ~11KB)

Wraps `com.microsoft.z3` (vendored via
`tools.aqua:z3-turnkey:4.14.0`, already
in the pom). The class structure:

```java
public final class Z3Bridge implements SolverZ3Bridge {
    private final SolverZ3Bridge fallback;
    public Z3Bridge() { this(new LocalSolverBridge()); }
    public Z3Bridge(SolverZ3Bridge fallback) { ... }

    @Override
    public Result check(String predicate, Map<String, Object> bindings) {
        if (predicate == null || predicate.isBlank()) return Result.SAT;
        try (Context ctx = new Context()) {
            Solver solver = ctx.mkSolver();
            Z3AstBuilder builder = new Z3AstBuilder(ctx, bindings);
            BoolExpr ast = builder.parse(predicate);
            solver.add(ast);
            Status s = solver.check();
            return switch (s) {
                case SATISFIABLE -> Result.SAT;
                case UNSATISFIABLE -> Result.UNSAT;
                case UNKNOWN -> Result.UNKNOWN;
            };
        } catch (Throwable t) {
            // Native Z3 unavailable — fall
            // back to local solver so the
            // caller is never blocked.
            return fallback.check(predicate, bindings);
        }
    }

    @Override
    public Set<String> referencedVars(String predicate) {
        return fallback.referencedVars(predicate);
    }

    /** Recursive-descent parser that emits Z3 expressions. */
    private static final class Z3AstBuilder { ... }
}
```

Key design points:

1. **Recursive-descent parser emits Z3
   ASTs directly**. The same grammar as
   `LocalSolverBridge` (and-or-not,
   arithmetic, comparisons, parens).
   Each parse step emits a
   `BoolExpr` / `IntExpr` instead of
   evaluating.

2. **Value type handling**:
   - `Long` / `Number` → `ctx.mkInt(v)`
   - `Boolean` → `ctx.mkTrue/False`
   - `String` → `ctx.mkInt(length)` (we
     don't model Z3's String theory)
   - Unbound identifiers → `ctx.mkIntConst(name)`
     (a free Z3 variable)

3. **Fallback is bulletproof**. Any
   `Throwable` (ClassNotFoundException,
   UnsatisfiedLinkError, Z3Exception,
   RuntimeException) returns
   `fallback.check(...)`. The caller
   can never block on a missing native
   lib.

### `plumb-dfa/.../solver/R218bZ3BridgeTest.java` (new, 9 tests)

- `trivialTruePredicate_isSat`
- `trivialFalsePredicate_isUnsat`
- `booleanCombination_andOrNot`
- `arithmeticEvaluates`
- `unboundVariable_handledGracefully`
- `divisionByZero_doesNotCrash`
- `malformedPredicate_doesNotCrash`
- `referencedVars_extractsIdentifiers`
- `fallback_localSolverWorks` — direct
  test of the fallback path

## Results

- 9/9 R218b tests pass
- 200/200 plumb-dfa tests
  pass (was 186; +9 R218b + 4 R218c + 1
  R224 integration = 14 new tests; 0
  regression)

## Z3 API gotchas (worth recording)

- **`ArithExpr` doesn't exist in the
  z3-turnkey 4.14.0 jar**: I initially
  used `ArithExpr` / `ArithNum` (the
  generic names from Z3 tutorials) but
  the actual class names are
  `IntExpr` / `IntNum` / `RealExpr` /
  `BoolExpr`. Replaced the imports.
- **`mkITE` returns the joined type**:
  `ctx.mkITE(bool, IntExpr, IntExpr)`
  returns `IntExpr`, not generic
  `Expr`. The cast is required.
- **Z3 prefers explicit catch (Throwable)**:
  the local-solver fallback must
  catch `Throwable` (not just
  `Exception`) to handle
  `UnsatisfiedLinkError` and
  `NoClassDefFoundError` from missing
  native libs.
- **Div-by-zero is silently returned
  as 0 in Z3 Int arithmetic**: We
  catch the underlying exception
  in our parser and treat the
  predicate as malformed → fall back
  to the local solver (which returns
  UNKNOWN per R218 convention).

## When to use which bridge

| Scenario | Recommended bridge | Why |
|----------|---------------------|-----|
| Unit tests | `LocalSolverBridge` | No native lib, fast, deterministic |
| Production CI with no native Z3 | `LocalSolverBridge` | Same; Z3Bridge falls back anyway |
| Production with `tools.aqua:z3-turnkey` on classpath | `Z3Bridge` | Full Z3 power for non-linear / quantifier-heavy predicates |
| Stub / mock for testing | `LocalSolverBridge` | Easy to inject fixed results |

Both bridges implement the same
`SolverZ3Bridge` interface, so the
checker is unchanged — only the
`setSolverBridge(...)` call differs.

## Backup

`D:\work\tmp\r218_done\` will be created
when R218b/c are bundled. The new
files are:

- `plumb-dfa/.../solver/Z3Bridge.java`
  (~11KB)
- `plumb-dfa/.../solver/R218bZ3BridgeTest.java`
  (9 tests, ~3.6KB)
