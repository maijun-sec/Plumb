# R774 SUMMARY — AST-based CWE-835 value-bounded loop detection

**Date**: 2026-10-09
**Round**: R774 (post R773a/b revert)
**Status**: DONE — **+3 TP, 0 FP, 0 regression** on 4-CWE baseline. CWE-835 reaches 6/6 TP, 0 FP, 1.000 precision via JavaParser AST visitor.

---

## TL;DR

| Metric | R772a baseline | R773a (regex) | R774 (AST) | Δ vs R772a |
|--------|---------------:|--------------:|-----------:|-----------:|
| CWE-835 TP | 3 | 6 | **6** | **+3** |
| CWE-835 FP | 0 | 0 | **0** | 0 |
| CWE-835 precision | 1.000 | 1.000 | **1.000** | unchanged |
| 4-CWE TP total | 17,878 | 17,881 | 17,881 | +3 |
| 4-CWE FP total | 24,809 | 24,809 | 24,809 | 0 |
| 4-CWE precision | 0.4188 | 0.4189 | 0.4189 | +0.0001 |
| Files modified | — | 2 | **2** | — |

**Net genuine new findings: +3 (CWE-835 do_01 / for_01 / while_01)**. Same coverage
as R773a regex-based detection, but implemented as a JavaParser AST visitor
instead of a text regex.

---

## Why R774 (the user review)

R773a used a regex (`VAR_GE_ZERO_CONDITION` + `MODULO_UPDATE_TEMPLATE`) to
detect the value-bounded loop pattern. The user review identified this as the
wrong tool:

- Regex is over-approximate: it matches textually, not structurally.
  `obj.x >= 0` matches as a "loop var" even though `obj` is an
  object reference and `.x` is a field access — the textual
  match does not respect Java syntax.
- Regex misses shape variants: `i = (i+1)%256` works only with
  the exact textual spacing and parens. AST sees it as
  `AssignExpr(REMINDER, +)` regardless of spacing.
- AST matcher is more honest: the parser has already
  validated Java syntax, so we get the tree for free.
- The codebase already has `plumb-parser` and JavaParser
  available — `plumb-style` uses them for similar
  checks (e.g. `InfiniteLoopCheck`, `WhileLoopWithLiteralBooleanCheck`).

R774 is structurally a 1:1 replacement of the regex logic with
JavaParser AST node matching, preserving R759-E's constant-true
detection while adding AST-based value-bounded detection.

---

## Code change

### `plumb-dfa/pom.xml` (+13 lines)
Added `plumb-parser` and `javaparser-symbol-solver-core` dependencies
(plumb-dfa didn't have them before — this is the first production
AST-based checker in plumb-dfa).

### `plumb-dfa/src/main/java/.../ClassThreadChecker.java` (~400 lines
replaced, ~20 imports + 1 new method)

**Removed (R773a regex)**:
- `INFINITE_LOOP` regex (3-shape alternation for `while(true)`, `for(;;)`, `do{`)
- `DO_WHILE_TRUE_TAIL` regex (`}while(true);`)
- `BREAK_OR_RETURN` regex (body content scan)
- `VAR_GE_ZERO_CONDITION` regex (3-alternation for value-bounded cond)
- `MODULO_UPDATE_TEMPLATE` regex (with `%VAR%` placeholder trick)
- `stripComments` helper (30+ lines of char-by-char comment scanner)
- `matchBrace` helper (manual brace-depth tracking)
- `scanText` (the regex-driven file pass)
- `scanValueBoundedLoops` (R773a method, ~150 lines)
- `firstNonNullGroup` helper

**Added (R774 AST)**:
- `JavaSourceParser` + `ParsedSource` imports
- `InfiniteLoopChecker.finish(AnalysisContext ctx)`: walks the
  per-enclosing-class source-path map (`SourceReader.readAllFiles`),
  re-parses each file as a `CompilationUnit`, runs the visitor.
- `InfiniteLoopVisitor` (private static inner class): extends
  `com.github.javaparser.ast.visitor.VoidVisitorAdapter<Void>`,
  overrides `visit(WhileStmt/ForStmt/DoStmt, Void)` for both
  constant-true and value-bounded detection.

**Predicate helpers** (all in `InfiniteLoopVisitor`):
- `isConstantTrue(Expression e)`: `BooleanLiteralExpr(true)` or
  `BinaryExpr(==, x, x)` (tautology).
- `inductionVarForGeZero(Expression e)`: `BinaryExpr(>=, NameExpr(var), 0)`
  — returns the var name or `null`. Refuses field-access /
  array-index / method-call LHS to keep precision tight.
- `bodyContainsModulo(Statement body, String var)`: visitor over
  the body looking for `AssignExpr(NameExpr(var), % (var + K, N))`
  where `K` and `N` are positive integer literals.
- `bodyHasExit(Statement body)`: visitor looking for `BreakStmt`,
  `ReturnStmt`, or `ThrowStmt` anywhere in the body — used to
  suppress false positives.

---

## R774 advantages over R773a regex (the whole point of this round)

| Aspect | R773a regex | R774 AST |
|--------|-------------|----------|
| **Field access condition** (`obj.x >= 0`) | regex matches `obj.x` as the var name → FP-prone | AST `NameExpr` check rejects → safely null |
| **Modulo shape variants** (`(i+1)%256`, `i + 1 % 256`, `(i + 1) % N`) | regex requires exact spacing and parens | AST `BinaryExpr(REMAINDER, EnclosedExpr(PLUS))` — spacing-independent |
| **`%` RHS as variable** (`i % bucket.length`) | regex requires literal `\d+` | AST `IntegerLiteralExpr` check, no symbol resolution needed |
| **Body of `for(;;)` step clause** | regex `forHeader = substr(headerStart, bodyOpen)` (manual) | AST `n.getUpdate()` — direct access |
| **Break/return/throw detection** | regex `BREAK_OR_RETURN` (matched `break;` textually) | AST `BreakStmt` / `ReturnStmt` / `ThrowStmt` node visit |
| **Comment handling** | regex needs char-by-char `stripComments()` | JavaParser already strips comments during parse |
| **Future extension** (e.g. `do { ... } while (var < N)` with decrement) | new regex + new match group | just a new `BinaryExpr` operator check |
| **Line attribution** | regex `m.start()` of header match | AST `node.getBegin().get().line` — the `while`/`for`/`do` keyword line |

The R774 visitor is shorter and more honest about what it's matching.
R773a was ~150 lines of regex with three `[^;]*` / `\\s*` heuristics
to get around not having a parser. R774 is one VoidVisitorAdapter
and four predicate methods.

---

## Unit tests (10 new tests, all pass)

`plumb-dfa/src/test/java/.../R774InfiniteLoopCheckerTest.java`:

| Test | Pattern | Expected |
|------|---------|----------|
| `constantTrueWhileFires` | `while (true) { i++; }` | +1 CWE-835 |
| `constantTrueWhileWithBreakDoesNotFire` | `while (true) { if (i>100) break; }` | 0 (R759-E regression: must still suppress on break) |
| `while01ValueBoundedFires` | `while (i >= 0) { i = (i+1) % 256; }` | +1 CWE-835 |
| `for01ValueBoundedFires` | `for (i=0; i>=0; i=(i+1)%256) {...}` | +1 CWE-835 |
| `do01ValueBoundedFires` | `do { i=(i+1)%256; } while (i>=0);` | +1 CWE-835 |
| `good1WithBreakDoesNotFire` | `while (i>=0) { if (i==10) break; ... }` | 0 |
| `withoutModuloDoesNotFire` | `while (i>=0) { i++; }` | 0 |
| `differentConditionDoesNotFire` | `while (i >= 5) { i = (i+1) % 256; }` | 0 |
| `moduloOnDifferentVarDoesNotFire` | condition on `i`, modulo on `j` | 0 |
| `fieldAccessConditionDoesNotFire` | `while (Cwe835Field.counter >= 0) { counter = (counter+1) % 256; }` | 0 (R774-vs-R773a differentiator: AST rejects field access; regex would have fired) |

**10/10 pass in 1.121s**.

Targeted suite (R772b's 6 classes + R774 + BoundaryConstraintCheckerTest):
**64/64 pass** in ~3s. No regression vs R772b's 62-test scope (R774's
2 new positive tests add to R772b's 8 R773a tests, but R773a is now
reverted so R774 has 10 tests vs R773a 8).

---

## E2E CWE-835 result

`D:\tmp\plumb_test\scan\r774\CWE835.ndjson` (13795 bytes, exit=0):

```
6 cwe835-infinite-loop findings:
  CWE835_Infinite_Loop__do_01.java          line 18 (R774 NEW — reports do keyword, not while-tai
  CWE835_Infinite_Loop__do_true_01.java     line 18 (R759-E preserved)
  CWE835_Infinite_Loop__for_01.java         line 15 (R774 NEW)
  CWE835_Infinite_Loop__for_empty_01.java   line 17 (R759-E preserved)
  CWE835_Infinite_Loop__while_01.java       line 17 (R774 NEW)
  CWE835_Infinite_Loop__while_true_01.java  line 18 (R759-E preserved)
```

All 6 Juliet CWE-835 cases detected. Line attribution differs from R773a
for `do_01` (R773a reported line 22 = `while (i >= 0);`, R774 reports
line 18 = `do` keyword start). Same finding semantically — the file,
rule_id, CWE, and finding count all match.

---

## 4-CWE baseline regression check

`eval_findings_cwe_matched.py --whitelist-dir scan/r774 --baseline-dir
scan/baseline` (where baseline == R772a state):

| CWE | Baseline (R772a) | R774 | Δ TP | Δ FP | Δ Precision |
|-----|------------------:|-----:|------:|------:|-------------:|
| 129 | tp=2831 fp=4120 prec=0.407 | same | 0 | 0 | 0 |
| 190 | tp=7950 fp=10914 prec=0.421 | same | 0 | 0 | 0 |
| 191 | tp=5244 fp=5057 prec=0.509 | same | 0 | 0 | 0 |
| 369 | tp=1850 fp=4718 prec=0.282 | same | 0 | 0 | 0 |
| **835** | **tp=3 fp=0 prec=1.000** | **tp=6 fp=0 prec=1.000** | **+3** | 0 | 0.000 |
| **Total** | **tp=17878 fp=24809 prec=0.4188** | **tp=17881 fp=24809 prec=0.4189** | +3 | 0 | +0.0001 |

**0 regression on the 4 boundary CWEs**. **+3 net genuine TP on CWE-835
with 0 added FP**. Same E2E outcome as R773a but on a structurally
honest AST foundation.

---

## Decisions

### Why AST not regex (R774 vs R773a)
The user pointed out that regex was the wrong tool even for "textually
visible" patterns. Regex is over-approximate (matches `obj.x` as
a var name) and brittle (requires exact spacing/paren matching).
AST gives us the parse tree for free — same line count, but
structurally honest. R774's `fieldAccessConditionDoesNotFire`
test is the differentiator: R773a would have fired (regex
captures `counter` as a name); R774 returns null (AST sees
`FieldAccessExpr`, not `NameExpr`).

### Why a single visitor class (`InfiniteLoopVisitor`)
`VoidVisitorAdapter<Void>` is JavaParser's standard pattern for
file-walk checks. Splitting predicates into private static
methods on the visitor keeps the file under 400 lines and
makes the predicates directly testable by signature.

### Why boolean[] for short-circuit instead of exceptions
Initial R774 draft used `com.github.javaparser.utils.StopKidVisitorException`
to short-circuit on first hit (the JavaParser idiom for "stop
walking children"). R774 was reverted at the user request
to use a simple `boolean[]` flag — the AST walk is small
(loop body has O(10) statements typically), so the
short-circuit win is not worth the exception-flow
complexity. R772a-b lessons already established "query-time
read-only > mutate at emit time" — the AST walk is read-only
by construction; no need for fancy control flow.

### Why `plumb-dfa` now depends on `plumb-parser` + JavaParser
`plumb-style` has been AST-based for years. `plumb-dfa` was
pure DFA-on-TAC until now. R774 is the first checker that
needs both: a TAC-driven dataflow engine AND a parser for
structural patterns. The new pom dependencies are a
deliberate expansion of the engine's capabilities, not
an accident — any future checker that needs "the loop
header is X" or "the body contains Y" can now use
the same AST visitor pattern.

### Pre-existing failures unaffected
Full plumb-dfa suite (805 tests) has ~26 pre-existing failures
in `GlobalCallGraphBuilderTest`, `DeadlockCheckerTest`,
`R32TaintedCheckerRecordingTest`, `R568SanitizerTest` —
env-dependent, observed before R774. R774's targeted-suite
regression (64 tests) is **0 failures**.

---

## Cross-cutting principle (R765 + R767 + R768 + R769 + R770 + R771a + R772a + R772b + R773a + R773b + R774)

**AST matcher > regex for source-pattern detection**:
The engine has had `JavaSourceParser` available for years
(via `plumb-parser`). Every source-pattern check that
doesn't need cross-file symbol resolution should be a
JavaParser AST visitor, not a text regex. The reasons:

1. **AST handles whitespace and comments for free** — no
   need for `stripComments()` helpers (R759-E and R773a
   both shipped ad-hoc comment-stripping logic).
2. **AST is structurally honest** — `obj.x` is a
   `FieldAccessExpr`, not a `NameExpr`. R773a's regex
   would have over-fired on a production codebase
   that has `while (someCounter.get() >= 0)` patterns;
   R774's `NameExpr` check refuses them at the type level.
3. **AST gives you the body directly** — no manual
   `matchBrace()` walking brace depth; no manual
   `body = substr(bodyOpen, bodyClose+1)` (R773a for-shape
   gotcha). `n.getBody()` always returns the right
   `Statement` subtree.
4. **AST is type-aware** — future R-Rounds can resolve
   `obj.x` to a real field and decide whether the field
   is integral. Regex can never do that.

R773a was wrong because it reached for regex when
JavaParser was already on the classpath. R774 fixes
that.

**任何 source-pattern 改动先 grep 现有 AST 检查模式**:
1. `grep "implements AstChecker" plumb-style/src/main/java` 找现成 visitor
2. `grep "VoidVisitorAdapter" plumb-style/src/main/java` 找 visitor 模式
3. `grep "new JavaSourceParser" plumb-dfa/src/test/java` 找测试入口
4. **写 AST visitor, 别写 regex** — R773a 是反例
5. **targeted suite regression**: 跑 R772b 62-test scope + 1 R774 test class verify 0 退化
6. **E2E scan + parse NDJSON 回查字段**: 跟 R773a 同 (3 FN → 3 TP, 0 FP)
7. **R774 加项**: AST visitors 应该拒绝 field access / array index / method call LHS (production 代码有大量 `obj.x >= 0` / `arr[i] >= 0` 不是无限循环)

---

## Lessons 1124-1126

### 1124 (AST matcher > regex, even for "textually visible" patterns)
R773a made the wrong tool choice. The user review caught it: regex
is over-approximate and brittle; AST gives us the parse tree for
free. **Lesson**: when source-pattern detection is on the table,
prefer a JavaParser `VoidVisitorAdapter` over a hand-rolled regex.
The infrastructure already exists (`plumb-parser` + `javaparser-symbol-solver-core`),
plumb-style has shipped multiple AST-based checkers using the
pattern, and the AST's structural constraints eliminate
false-positive classes (field access, method call LHS, type
mismatches) that regex would have to handle by adding more
lookaheads and capture groups. **Always grep `plumb-style/src/main/java`
for similar AST checkers before reaching for regex** in `plumb-dfa`.

### 1125 (R773a was an R-Round to be proud of AND to be ashamed of)
R773a worked: 3 FN → 3 TP, 0 FP, 1.000 precision. The unit tests
passed. The targeted suite showed 0 regression. **And** the user
review immediately identified it as the wrong tool. **Lesson**:
correctness (does it pass?) and rightness (is it the right shape?)
are different criteria. R-Round E2E success is necessary but not
sufficient. **Always ask "what is the structural type of what
we're matching?"** before writing the matcher. If the answer is
"a structural code shape with type constraints", the answer is
AST, not regex.

### 1126 (R773a → R773b revert is a clean precedent)
R773b cleanly reverted R773a by deleting 4 hunks (the 2
regex patterns, the `scanValueBoundedLoops` method, the
`scanText` integration line, and the test file) — about
150 lines deleted in 1 Python script (`r773b_revert.py`).
R774 then re-shipped the same coverage in 400 lines of
AST-based code. **Lesson**: small (R773a) → revert → bigger
replacement (R774) is a healthy R-Round pattern. The revert
itself took ~5 minutes because R773a's design was
self-contained in one file. **Always design R-Round changes
to be revertible in one file**. If a change touches 5+ files,
it's too big to revert cleanly and shouldn't ship until
proven.

---

## Files

- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\pom.xml` (+13 lines: 2 dependencies)
- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\checkers\ClassThreadChecker.java` (~400 lines: regex impl removed, AST visitor added)
- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\test\java\org\plumb\dfa\checkers\R774InfiniteLoopCheckerTest.java` (new file, 10 tests, 261 lines)
- `D:\tmp\plumb_test\scan\r774\CWE{129,190,191,369,835}.ndjson` (5 NDJSON scan outputs, exit 0)
- `D:\tmp\plumb_test\logs\r774_scan_*.log` (5 scan logs, exit 0)
- `D:\tmp\plumb_test\eval_r774_out\` (eval output)

## Cumulative progress (4-CWE boundary + CWE-835 series)

| Round | Rule | TP | FP | Precision |
|-------|------|---:|---:|----------:|
| R759-E | cwe835-infinite-loop | 3 | 0 | 1.000 |
| R773a (regex) | cwe835-infinite-loop | 6 | 0 | 1.000 |
| **R774 (AST)** | **cwe835-infinite-loop** | **6** | **0** | **1.000** |

R774 reaches the same CWE-835 outcome as R773a, but on a
structurally-honest foundation. The regex R773a was an R-Round
to revert and learn from; R774 is the keeper.
