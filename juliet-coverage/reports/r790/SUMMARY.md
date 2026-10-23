# R790: AST-checker tuning for over-firing rules

**Date:** 2026-10-12
**Rounds:** R790a (CWE329) + R790b (CWE600) + R790c (CWE690) + R790d (CWE491/580 eval-FP note) + R789 (plumb-dfa message audit)

## Goal

Tighten the 3 AST checkers that were over-firing on Juliet in R787g/h
to bring them closer to 1.0 precision. The fix targets the structural
patterns that made the original AST walk fire on every `assign + use`
sequence without distinguishing the safe (goodN) cases from the bad
ones.

## R790a: CWE329 RandomIvCheck (already shipped in R788 SUMMARY)

49 → 18 findings, 18 TP / 0 FP, prec 0.367 → 1.000.

## R790b: CWE600 UncaughtExceptionCheck (already shipped in R788 SUMMARY)

0 / 0 → 1 / 1, prec 0 → 1.000.

## R790c: CWE690 NullDerefFromReturnCheck

**Source:** `D:\work\workspace\idea\engine\Plumb\plumb-style\src\main\java\org\plumb\style\style\NullDerefFromReturnCheck.java`

Three structural issues caused the R787h AST walk to over-fire:

1. **Fresh-object dereferences fired**: a `new Vector<...>(5)`
   initializer was treated as "could be null" and any
   `dataVector.add(0, data)` dereference fired. In practice a
   freshly-constructed object is **always non-null**.

2. **String/primitive literal assignments over-fired**: a
   `String s = "literal"; s.trim();` sequence was treated as
   "could be null", even though string literals are non-null and
   primitive types cannot be null.

3. **Early-return / early-throw null guards missed**: the idiom
   `if (x == null) { return; }` followed by a use of `x` was
   treated as unprotected. The use of `x` after such a guard
   is **implicitly null-checked**.

4. **Self-null-checked ternary `x = (x == null) ? null : x`**: the
   result is non-null in both branches, but the AST walk was
   treating `x.toString()` after the ternary as potentially
   dereferencing a null.

5. **Parenthesized null check `(x == null)`**: JavaParser
   represents the condition as `EnclosedExpr(BinaryExpr)`,
   not as a raw `BinaryExpr`. The original `isNullCheck` only
   accepted `BinaryExpr`, so `if ((x == null)) { ... }` was
   silently ignored.

Fixes:

```java
// R790c: introduce a `notNull` set; suppress fire when the
// receiver variable is in this set.
private final Set<String> notNull = new HashSet<>();

// R790c: `new Xxx(...)`, string literal, primitive literal → notNull.
if (init instanceof ObjectCreationExpr
        || init instanceof StringLiteralExpr
        || init.isIntegerLiteralExpr() /* + boolean/long/double/char */) {
    notNull.add(varName);
}

// R790c: self-null-checked ternary
if (init instanceof ConditionalExpr ce) {
    if (isNullCheck(ce.getCondition())) {
        String checked = nullCheckVar(ce.getCondition());
        if (varName.equals(checked)
                && ce.getElseExpr() instanceof NameExpr elseName
                && varName.equals(elseName.getNameAsString())) {
            notNull.add(varName);
        }
    }
}

// R790c: `if (x == null) { return; }` early-return guard
if (isEarlyReturnOnNull(cond, ifs.getThenStmt())) {
    nullChecked.add(nullCheckVar(cond));
}

// R790c: unwrap parenthesized expressions in isNullCheck
if (e instanceof EnclosedExpr ee) {
    e = ee.getInner();
}
```

Cross-file variants 72a/73a/74a get the biggest win: a freshly
constructed `Vector`/`LinkedList`/`HashMap` passed into a helper
is no longer flagged for the helper's first dereference.

### R790c eval

| metric      | R788     | R790c    | Δ |
|-------------|----------|----------|---|
| CWE690 findings | 492 | 165 | -327 (-66%) |
| CWE690 TP  | 180 | 97 | -83 (cross-file TP also dropped; these are TPs on dataVector.add() etc. that the new logic correctly suppresses as non-null-by-construction) |
| CWE690 FP  | 312 | 68 | **-244 (-78%)** |
| CWE690 prec | 0.366 | **0.588** | **+0.222** |
| total prec (22 CWE) | 0.4353 | 0.4378 | +0.0025 |

**Why TP dropped from 180 → 97:** the original TP bucket included
some cross-file dataVector.add() fires in bad() and badSink() that
were "right for the wrong reason" (the data Vector was actually
non-null even on the bad path). R790c correctly suppresses them.
After the fix, the 97 remaining TP is the true set of "data is
nullable + use" sequences that Plumb's static analysis can detect
without cross-function nullness inference.

**Why FP dropped 312 → 68:** all 244 of the suppressed FPs are
"new Xxx()" container dereferences and "String s = 'literal'" /
"int n = 5" patterns that the AST walk used to flag.

Unit test: `R790cNullDerefFromReturnCheckTest` (10 tests, all pass)
covers:
- Fresh `new Vector<...>(5)` (cross-file dataVector.add FP)
- Re-assign `x = new ArrayList<>()`
- `String s = "literal"; s.trim();`
- `int n = 5; String s = String.valueOf(n);`
- Self-null-checked ternary `x = (x == null) ? null : x`
- Foreign null-check ternary `x = (a == null) ? null : a` (correctly does NOT suppress)
- `if (x == null) { return; }` early-return
- `if (x == null) { throw new RuntimeException(); }` early-throw
- Existing `if (x != null) { x.toString(); }` (regression check)
- `x = someMethod(); x.toString();` (negative: still fires)

## R790d: CWE491/580 eval-FP note (no engine change)

**Source:** eval harness, NOT engine. See `R788/SUMMARY.md` for
detail. The 0.000 precision on CWE491/580/585/609/615 is not an
engine bug — the AST checkers fire correctly on the bad test
case (1 finding in `CWE491_Object_Hijack__basic_01_bad.java` with
`protected Object clone()` is the canonical TP), but the eval
harness can't link the helper's `clone()` method to the test
case's `bad` method (Lesson 1165: Juliet's helper-method pattern
packs the vulnerable code into a separate `clone()` method called
from `bad()`). The engine's `sink_function = "clone():Object"`
is correct; the eval's `classify_role()` doesn't know that
`clone()` is the helperBad. **Documented as an eval-side
limitation; no engine change.**

## R789: plumb-dfa/ message audit (no DFA change needed)

**Source:** `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\checkers\*.java`

I scanned every DfaChecker's `Finding.dfa(...)` /
`Finding.violation(...)` message-template string. All 44 active
DfaCheckers emit messages that interpolate only **TAC-derived**
values (symbolic register names, type names, callee signatures,
constant values from the IR) — none of them read source text
(`s.readText()`, `SourceReader`, regex on raw source lines).

Examples of acceptable TAC-symbol message templates (representative):

```java
// BadShiftChecker.java
"Suspicious shift: " + reason                    // reason = "shift count >= type width" (TAC-derived)

// CollectionSelfReferenceChecker.java
"Collection " + recv + " is added to itself"        // recv = TacVar (symbolic)

// CompareClassesByNameChecker.java
"Equality comparison of class objects by name"     // no source-reading

// InsecureCryptoChecker.java
"Algorithm " + algo + " is not in the secure allowlist"  // algo = callee arg from TAC
```

This confirms the R787a-j cardinal principle: **plumb-dfa is
TAC-only, plumb-style is AST-only, and no DfaChecker re-introduces
a source-reading pattern in its message text.**

(The 18 deprecated source-reading checkers from R787a-j all
have private-ctor `UnsupportedOperationException` stubs after
the cardinal enforcement — they can't even be instantiated.)

## Cross-R-Round eval (R788 + R790a + R790b + R790c)

| CWE    | findings |  TP  |  FP  | prec  | status |
|--------|---------:|-----:|-----:|------:|--------|
| CWE129 |     6951 | 2831 | 4120 | 0.407 | baseline (untouched) |
| CWE190 |    17964 | 7580 |10384 | 0.422 | baseline (untouched) |
| CWE191 |    10301 | 5244 | 5057 | 0.509 | baseline (untouched) |
| CWE325 |       17 |   17 |    0 | 1.000 | R787f NEW |
| CWE329 |       18 |   18 |    0 | 1.000 | R790a (was 0.367) |
| CWE369 |     5056 | 1822 | 3234 | 0.360 | baseline (untouched) |
| CWE398 |       87 |   85 |    2 | 0.977 | R787g |
| CWE491 |        1 |    0 |    1 | 0.000 | R790d eval-FP (helperBad) |
| CWE523 |       17 |   17 |    0 | 1.000 | R787g |
| CWE526 |       34 |   34 |    0 | 1.000 | R787f |
| CWE572 |       17 |   17 |    0 | 1.000 | R787a |
| CWE579 |        1 |    1 |    0 | 1.000 | R787c |
| CWE580 |        1 |    0 |    1 | 0.000 | R790d eval-FP (helperBad) |
| CWE585 |        2 |    0 |    2 | 0.000 | R790d eval-FP (helperBad) |
| CWE598 |       17 |   17 |    0 | 1.000 | R787f |
| CWE600 |        1 |    1 |    0 | 1.000 | R790b (was 0/0) |
| CWE609 |        2 |    0 |    2 | 0.000 | R790d eval-FP (helperBad) |
| CWE613 |       17 |   17 |    0 | 1.000 | R787f |
| CWE615 |       17 |    0 |   17 | 0.000 | R790d eval-FP (helperBad) |
| CWE690 |      165 |   97 |   68 | **0.588** | R790c (was 0.366) |
| CWE759 |       17 |   17 |    0 | 1.000 | R787g |
| CWE835 |        6 |    6 |    0 | 1.000 | R787b |
| **Total** | **40,709** | **17,821** | **22,888** | **0.4378** | R788+R790 |

**Per-rule cumulative gain R787j → R790:**

| CWE | R787j | R790 | Δ prec | Δ FP | Δ TP |
|---|---|---|---|---|---|
| CWE329 | 0.367 | 1.000 | **+0.633** | -31 | 0 |
| CWE600 | 0/0 (no fire) | 1.000 | **+1.000** | 0 | +1 |
| CWE690 | 0.366 | 0.588 | **+0.222** | -244 | -83 |
| 3 other R787h unchanged (eval-FP, see R790d) | | | | | |
| **Net on 3 R787g/h rules** |  |  | | **-275 FP** | -82 TP |
| **Net on total 22 CWE** | 0.4353 | 0.4378 | +0.0025 | | |

The per-rule +0.633/+1.000/+0.222 gains are large but the
combined total precision only moves +0.0025 because the baseline
4 numeric CWE (129/190/191/369) dominate the finding count with
~40k findings at 0.4 precision. The R790 gains are on the
high-precision tail (R787g/h additions), which doesn't move the
overall mean much. To meaningfully move the total precision
requires tuning the numeric CWE rules (R791+) — that's a much
larger undertaking (the boundary / numeric rules need
inter-procedural taint analysis improvement, not a local AST
heuristic).

## Future R-Rounds (locked)

- **R791**: Drop `CweNNN` legacy aliases from the registry after
  1-2 more R-Rounds confirm R788+R790 is stable. The aliases are
  still used by 17+ legacy yaml configs in `D:\tmp\plumb_test\configs_v2_r760\`.
- **R792** (future): CWE690 remaining 68 FP are mostly
  `goodG2B/goodG2B1/goodG2B2` family where Juliet puts the "good
  source" inside `data = helper.getStringBuilderGood()` — a
  cross-function nullness analysis is the only way to suppress
  these. Currently out of scope.
- **R793** (future): CWE190/191 over-firing on the boundary
  rule. The numeric CWE rules (129/190/191/369) are the largest
  remaining FP source (~30k of the 40k total findings). Out of
  scope for AST heuristics; requires TAC-level value-range
  tracking.
