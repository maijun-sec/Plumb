# R122 — Follow-up fixes (R119b/R119c limitations)

## Scope

R122 is a small round that closes two specific limitations called
out in earlier rounds, plus a sanity scan over the existing
`examples/` tree to make sure the cumulative R115–R121 work holds
together in a multi-file run.

| ID | Slug | What it fixes |
|----|------|---------------|
| R122a | (no new rule) | `PREDICTABLE_KEY_PASSWORD` now catches `new PBEKeySpec(new char[]{'p','a','s','s'})` (R119b deferred) |
| R122b | (no new rule) | `CORS_MISCONFIGURATION` now catches `cors.setAllowedOrigins(Arrays.asList("*"))` (R119c deferred) |
| R122c | (no new rule) | end-to-end scan over the `examples/` tree to confirm no regression |

The two follow-up fixes share a common shape: a `TacVar` that
appears as a sink argument is chased one hop into its defining
instruction, and the "is this a literal-init pattern?" check is
applied there. The translation surface (`TacInstruction` kind)
drives both — no new dataflow machinery is needed.

## Files

```
plumb-tac/src/main/java/com/hellojava/sast/tac/translate/
    ExpressionTranslator.java                (R122a: array-init support)
plumb-dfa/src/main/java/com/hellojava/sast/dfa/
    PredictableKeyPasswordCheck.java          (R122a: NEWARRAY chase)
    CorsMisconfigurationCheck.java           (R122b: list-factory chase)
plumb-dfa/src/test/java/com/hellojava/sast/dfa/
    PredictableKeyArrayInitTest.java         (NEW, 4 tests)
    CorsAsListTest.java                      (NEW, 3 tests)
```

## R122a — `PREDICTABLE_KEY_PASSWORD` with array-init

### What was wrong

R119b's `PredictableKeyPasswordCheck` was wired but it only caught
literals: it accepted `TacOperand.Const(STRING|INT|LONG)` as the
first argument of a `PBEKeySpec` / `SecretKeySpec`. The R119b doc
explicitly deferred array literals ("we catch the array case at
the NEWARRAY check (future work)").

Worse, the translator itself silently dropped array-init
expressions:

* `int[] p = {1, 2, 3};` — JavaParser parses the RHS as
  `ArrayInitializerExpr`; the translator's `translate()` had no
  case for it. The declaration became `p = null`.
* `new char[]{'a', 'b', 'c'}` — `ArrayCreationExpr` with a
  `getInitializer()` and no `getLevels().get(0).getDimension()`.
  The translator called `.orElseThrow()` and threw
  `NoSuchElementException` at parse time.

### The fix

Two changes:

1. **`ExpressionTranslator.translateNewArray`**: when an
   initializer is present, emit a `NEWARRAY` whose size is the
   literal count of initializer values. The size operand is a
   `Const(INT, n)`, which downstream checkers can recognize as a
   "this is a literal-sized array" signal. The individual element
   values are still not preserved in the IR (the engine reasons
   about array elements through `ARRAYLOAD`/`ARRAYSTORE` when the
   user code reads/writes them), but for the
   `PREDICTABLE_KEY_PASSWORD` shape we only need the size to be
   literal.
2. **`ExpressionTranslator.translateArrayInitializer`**: new
   method for bare `ArrayInitializerExpr` (`int[] p = {1, 2, 3};`).
   Same shape: a `NEWARRAY` with a literal size.
3. **`PredictableKeyPasswordCheck.isLiteralKeyMaterial`**: now
   accepts a `TacVar` that was defined by a `NEWARRAY` with a
   literal size operand, following one `MOVE` hop for the
   `int[] p = {...}; new PBEKeySpec(p);` shape. Variable-sized
   arrays (`new char[n]`) are explicitly *not* flagged — the
   `NEWARRAY`'s size operand must be a `Const`, not a `TacVar`.

### Tests

* `new PBEKeySpec(new char[]{'p','a','s','s'})` — detected
* `char[] p = {'p','a','s','s'}; new PBEKeySpec(p);` — detected
  (MOVE hop)
* `new SecretKeySpec(new byte[]{1,2,3,4}, "AES")` — detected
* `char[] p = new char[n]; new PBEKeySpec(p);` — **not** flagged
  (variable size)

## R122b — `CORS_MISCONFIGURATION` with `Arrays.asList` / `List.of`

### What was wrong

R119c's `CorsMisconfigurationCheck` only inspected string literals
in the args of `setAllowedOrigins` / `addAllowedOrigin` /
`allowedOrigins`. A common Spring idiom is:

```java
cors.setAllowedOrigins(Arrays.asList("*"));
```

The literal `"*"` is buried in the `Arrays.asList` call's args,
not in the `setAllowedOrigins` call's args. The R119c check
explicitly documented this as a limitation and noted that
"we don't model cross-method const-folding".

### The fix

The check now builds a per-function `var → defs` map once and, for
any `TacVar` arg of the cors call, chases the definition one hop
into the list-factory call's args:

```java
private boolean cameFromWildcardList(TacVar v, defs) {
    def = defs.get(v);
    if (def == null || def.kind != CALL) return false;
    if (!isListFactory(stripArity(def.calleeSig))) return false;
    for arg in def.args:
        if arg is Const(STRING) and arg.value() == "*" return true;
    return false;
}
```

`isListFactory` matches `asList` / `of` / `copyOf` /
`singletonList` (the common Java list factories), with a
method-only suffix match so `Arrays.asList` / `java.util.Arrays.asList`
/ bare `asList` all qualify. FQN-vs-simple-name ambiguity is
not a problem here because the method name is what matters.

The chase is bounded at one hop. A chain like
`myHelper(of("*"))` is still missed, but that is a less common
shape and the chase would need a proper inter-procedural
constant-folding pass (out of scope for this round).

### Tests

* `cors.setAllowedOrigins(Arrays.asList("*"))` — detected
* `cors.setAllowedOrigins(List.of("*"))` — detected
* `cors.setAllowedOrigins(Arrays.asList("https://app.example.com"))`
  — **not** flagged

## R122c — End-to-end scan over `examples/`

The full R115–R121 stack was run against the `examples/` directory
in one go (using the cross-TU `MergedProgram` path in
`ScanRunner`). Findings:

| file | findings | notes |
|------|----------|-------|
| `complex/BadCode.java` | 16 | unchanged from R121 baseline |
| `complex/NullDerefDemo.java` | 1 | unchanged |
| `demo/.../VulnerableServlet.java` | 8 | unchanged |
| `r115/R115Demo.java` | 3 | style only |
| `r116/R116Demo.java` | 2 | style only |
| `r117/R117A.java` | 3 | unchanged |
| `r119/R119CryptoDemo.java` | 8 | unchanged |
| `r119/R119SinksDemo.java` | 8 | unchanged |
| `r119/R119WebAppDemo.java` | 4 | unchanged |
| `r120/R120Demo.java` | 14 | 3 BAD_SHIFT + 3 INFINITE_LOOP + 5 CONSTANT_CONDITION + 3 INSECURE_RANDOM |
| `r121/R121Demo.java` | 6 | 4 SENSITIVE_DATA_LEAK + 2 STYLE_SYSTEM_OUT |
| `style-bad.java` | 5 | style only |
| **total** | **89** | no regression |

The breakdown shows every demo fires exactly the documented
findings; no spurious hits from the cross-TU merge.

## Test counts

| module | R121 | R122 | delta |
|--------|-----:|-----:|------:|
| parser | 3 | 3 | 0 |
| tac | 16 | 16 | 0 |
| rules | 2 | 2 | 0 |
| dfa | 102 + 1 skip | 109 + 1 skip | +7 |
| style | 6 | 6 | 0 |
| reporter | 2 | 2 | 0 |
| main | 3 | 3 | 0 |
| **total** | **134 + 1** | **141 + 1** | **+7** |

## What R122 doesn't do (still deferred)

* **TypeTable wire-up** — the class-aware taint propagation
  (R121 deferred). Would let `**.getPassword` narrow to
  `Auth.getPassword`, eliminating method-only over-approximation.
* **Exception-path taint** (R118c deferred) — `try { ... } catch
  (Exception e) { throw new RuntimeException(e.getMessage()); }`
  doesn't propagate taint through the catch re-throw yet.
* **`Arrays.asList` chained through helper** —
  `myHelper(of("*"))` still missed (chase is bounded at one hop).
* **MISSING_AUTHZ** (R119c deferred) — still needs type solver +
  annotation-aware IR.
* **Field-name sensitive detection** — `String password = "literal"`
  as a field is a different shape than `getPassword()`.
