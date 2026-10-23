# R786 — AST-checker skeleton (2026-10-11)

## Goal

R785 established the new mental model: AST rules walk
JavaParser `CompilationUnit`, DFA rules walk TAC IR,
no hybrids. R786 ships the **infrastructure** for AST
rules in plumb-dfa:

1. `AstChecker` — the new marker interface (per-file,
   `name() + run(AstContext)`).
2. `AstContext` — per-file context carrying
   `ParsedSource` + symbol-solver helpers + a `report`
   method.
3. `AstRunner` — the per-file entry point. Reads a
   list of `AstChecker` + a `ParsedSource`, invokes
   each, returns the findings.
4. `AstReport` — per-checker dedup wrapper around
   `AstContext.report` so a `VoidVisitorAdapter` that
   visits the same node from multiple paths emits at
   most one finding per `(line, ruleId)`.

R786 ships **0 behaviour change** to the eval pipeline.
The 4 new files are dead code until R787 migrates the
first source-reading checker. R787 is where AstRunner
gets wired into `ScanRunner` (or DfaEngine, whichever
turns out cleaner).

---

## Code changes (4 new files, 1 test file)

### `plumb-dfa/src/main/java/org/plumb/dfa/checker/AstChecker.java`

Marker interface. Three methods:

```java
String name();                                   // rule id (stable across runs)
void run(AstContext ctx);                        // per-file, no per-instruction
default String getAiPrompt(Finding finding);     // null = no AI dedup
```

The class-level doc explains the two design decisions
that R785's blueprint made:

- **Why a separate interface (not extends DfaChecker)**
  — DfaChecker's 6-method lifecycle doesn't fit AST
  rules. The original "AST rule as DfaChecker with
  no-op methods" is the sin this interface fixes.
- **Why not reuse `org.plumb.style.StyleCheck`** —
  plumb-dfa owns the AST contract. The 564
  plumb-style `StyleCheck` implementations stay in
  plumb-style as reference; a future bulk-import is a
  package rename, not a redesign.

### `plumb-dfa/src/main/java/org/plumb/dfa/checker/ast/AstContext.java`

Per-file context. Mirrors the shape of
`org.plumb.style.StyleContext` (type / declaration
resolution helpers, per-file findings list,
ruleId-scoped parameter override hook is deferred
to R787+ when we have a checker that needs it).

Key methods:

- `unit()` → `CompilationUnit`
- `typeSolver()` → `TypeSolver`
- `report(line, col, ruleId, message, severity, dataflowHead)`
  → constructs a `Finding` and appends to the
  per-file list
- `resolvedTypeOf(Type)` / `resolvedTypeOf(Expression)` →
  `Optional<ResolvedType>` (never throws)
- `resolveDecl(Node)` → `Optional<ResolvedDeclaration>` (never throws)
- `describe(Type)` / `qualifiedNameOf(Type)` → `String`
  (fall back to AST text on resolution failure)

The `cweFor(ruleId)` helper currently returns `""`
(empty string). R787+ will populate it from
`plumb-dfa/.../config/plumb_rule_to_cwe.csv` when
the first AST check needs the CWE tag.

### `plumb-dfa/src/main/java/org/plumb/dfa/checker/ast/AstRunner.java`

The per-file entry point. Constructor takes an
immutable copy of the checker list; `runOn(ParsedSource)`
returns the per-file findings. Exceptions inside a
checker's `run` are logged and that checker's findings
are discarded, but the other checkers still complete.

Static helpers `AstRunner.empty()` and
`AstRunner.runAll(checks, source)` for one-off usage
without constructing a runner.

### `plumb-dfa/src/main/java/org/plumb/dfa/checker/ast/AstReport.java`

Per-checker dedup wrapper. A `VoidVisitorAdapter` may
visit the same node from multiple paths (e.g. once via a
`visit` override and once via a `visit(XExpr, Y)`
overload). Without dedup the same finding would be
emitted multiple times for the same location.

The `AstReport` constructor takes the `AstContext` and
the checker's rule id (either as a string or as an
`AstChecker` instance). `found(line, col, message,
severity, dataflowHead)` emits; later emits at the
same `(line, ruleId)` are dropped.

### `plumb-dfa/src/test/java/org/plumb/dfa/checker/ast/AstRunnerTest.java`

6 unit tests:

1. `emptyRunnerEmitsNothing` — `AstRunner.empty()` on
   any file emits nothing.
2. `runnerInvokesCheckerAndCollectsFindings` —
   `MethodCallCounter` check fires ≥ 2 times on a file
   with `foo(); bar();`.
3. `perFileFindingsAreIsolated` — two files don't
   share findings across the runner.
4. `checkerThrowingIsIsolated` — second checker
   throws, first still runs and emits (WARN logged).
5. `astReportDedupesByLine` — 3 emits at the same
   `(line, col)` produce 1 finding; emits at different
   `(line, col)` produce separate findings.
6. `astContextResolvesTypeSafe` — `AstContext` builds
   from a `ParsedSource`, `unit()` returns a non-null
   `CompilationUnit`, `path()` non-null, no throw.

All 6 pass.

---

## Verification

### Targeted unit suite (6/6 pass)

```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### E2E Juliet 5-CWE scan (R786 vs R784)

| CWE | R784 prec | R786 prec | Δ |
|-----|----------:|----------:|---|
| 129 | 0.407 | 0.407 | 0 |
| 190 | 0.422 | 0.422 | 0 |
| 191 | 0.509 | 0.509 | 0 |
| 369 | 0.360 | 0.360 | 0 |
| 835 | 1.000 | 1.000 | 0 |
| **Total** | **0.4341** | **0.4341** | **0** |

**Zero regression.** Findings, TP, FP, and precision
are bit-identical to R784. R786 is dead code until
R787.

---

## Decisions

### Why no DfaEngine hook in R786

R786 ships the AST contract. R787 migrates the first
source-reading checker (`MethodCallOnThread`) and
that's where the DfaEngine ↔ AstRunner integration
gets done. The reason: a one-line `ScanRunner`
change to invoke `AstRunner.runAll(...)` is
*trivial* but it has to happen in the same R-Round
as the first migration so the migration can be
validated end-to-end. Doing it in R786 would mean
shipping dead code AND a hot path change in the
same R-Round, which violates the "0 behaviour change"
constraint.

### Why a separate `AstReport` (not just `AstContext.report`)

A `VoidVisitorAdapter` may visit the same node from
multiple paths. Without dedup, a check like
`SuspiciousEqualsMethodNameCheck` that visits every
`MethodCallExpr` once via the default `visit(...)` and
once via a `visit(MethodCallExpr, ...)` override would
emit the same finding twice.

Two options for dedup:

- **In AstContext**: one global dedup per (line,
  ruleId). Works but couples AstContext to the
  checker's rule id.
- **In AstReport (per-checker wrapper)**: each
  checker creates its own AstReport at the start of
  `run()`. Dedup is scoped to the checker, not the
  whole context.

R786 chose the per-checker wrapper because it's the
same shape as `StyleContext` (which has
`setCurrentRule` for rule-scoped parameter lookups)
and because a per-checker scope is the right
granularity — two checkers firing on the same line
should both fire (they're different rules).

### Why `AstContext` doesn't have `paramOverride` (yet)

`StyleContext` has a ruleId-scoped parameter override
map for things like `max=20` (the
"max method length" param for
`MethodTooLongCheck`). R786 doesn't add it because
the first AST check to migrate
(`MethodCallOnThread`) doesn't need any params.
R787 will add `paramOverride` if needed; if R787
doesn't need it, R788 / R789+ will.

The implementation, when added, will be a 30-line
copy of the `paramOverride` / `compositeKey` logic
from `StyleContext`. Not before it's needed.

### Why the `cweFor` helper returns `""` for now

The `Finding` accepts a null CWE — see
`plumb-reporter/.../Finding.java:150` (`/* cwe */ null`).
R786 ships the empty-string fallback so the
compiler is happy; R787 will populate it from
`plumb-dfa/.../config/plumb_rule_to_cwe.csv` (which
already maps 211 rule ids to CWEs) when the first
migrated check needs the CWE tag for eval.

### Why the package is `org.plumb.dfa.checker.ast` (not `org.plumb.dfa.ast`)

The `checker/` package is the new home for the rule
class hierarchy: `DfaChecker` lives in
`org.plumb.dfa.engine` (existing); the new
`AstChecker` lives in `org.plumb.dfa.checker` (one
level down from `engine/`, parallel to `fact/`,
`points/`, `solver/`, etc.). The `ast/` sub-package
holds the AST-rule implementations and their
context. This matches the R785 blueprint's target
layout.

---

## Lesson 1150

### 1150 (R786 — interfaces before integrations)

The natural impulse on "we need AST rules" is to wire
the DfaEngine to call `AstRunner.runAll` in the same
PR. R786 deliberately splits this into two steps:

1. **R786**: ship the interface contract, write
   unit tests for the new types, prove the
   contract.
2. **R787+**: do the integration as part of the
   first real migration.

**Lesson**: when adding a new execution model (AST
in this case), the order matters: **interfaces and
their tests first, integration second**. A R-Round
that ships dead code with good tests is cheap; a
R-Round that ships a hot-path change without
end-to-end coverage is expensive to debug. R786's
6 unit tests + zero eval change is the
"interfaces first" template for future rule
subsystems.

---

## Cross-cutting principle (R776 + ... + R786)

Eleven R-Rounds in a row:

- R776-R782: correctness (FlowStep, unknown-file, numeric
  flow, per-type messages, direction split, NE-guard).
- R783: WRONG — overwrote by R785.
- R784: first correct step (delete duplicate
  UncheckedLoopConditionChecker).
- R785: re-framing doc (AST/DFA split, plumb-style
  absorbed).
- R786: AST skeleton, 0 behaviour change, 6/6 tests.

R786 is the first **infrastructure-only** R-Round in
the series. The previous 6 (R776-R782) were all about
**finding correctness**; R784 was a one-file delete;
R785 was a doc rewrite; R786 is the first R-Round that
**adds new code with no immediate caller**. The
discipline: ship the contract first, prove it with
tests, then wire it up in the next R-Round where a
real migration needs it.

---

## Files (R786)

- `plumb-dfa/src/main/java/org/plumb/dfa/checker/AstChecker.java` (NEW, 3.8 KB) — **DELETED in R787a**
- `plumb-dfa/src/main/java/org/plumb/dfa/checker/ast/AstContext.java` (NEW, 7.6 KB) — **DELETED in R787a**
- `plumb-dfa/src/main/java/org/plumb/dfa/checker/ast/AstRunner.java` (NEW, 3.5 KB) — **DELETED in R787a**
- `plumb-dfa/src/main/java/org/plumb/dfa/checker/ast/AstReport.java` (NEW, 3.6 KB) — **DELETED in R787a**
- `plumb-dfa/src/test/java/org/plumb/dfa/checker/ast/AstRunnerTest.java` (NEW, 5.6 KB) — **DELETED in R787a**
- `juliet-coverage/reports/r786/SUMMARY.md` (this file)
- `D:\tmp\plumb_test\scan\r786\CWE{129,190,191,369,835}.ndjson` (5 files, 40,278 findings, 0 unknown-file, 100% flow-step)
- `D:\tmp\plumb_test\eval\r786\global_per_cwe.csv` (5-CWE prec 0.4341 = R784)

---

## R787a supersedes R786 (2026-10-11)

The user's cardinal principle correction ("AST rules belong in
the style module; DFA rules belong in the dfa module; prefer
AST when feasible") invalidated R786's plan to host an AST
runtime inside plumb-dfa. **All 5 R786 files listed above were
deleted in R787a.** The replacement is `plumb-style`'s
pre-existing `AstChecker` / `StyleContext` / `AstDispatcher`
triple, which has been in production use for hundreds of
StyleCheck implementations since before R776. R786's
Lesson 1150 ("interfaces before integration") still holds as
methodology, but the wrong interface was chosen: instead of
adding a parallel one inside plumb-dfa, R787a reuses the one
that already exists in plumb-style.

See `juliet-coverage/reports/r787a/SUMMARY.md` for the full
R787a writeup (ThreadRun source-reading → CallToThreadRunCheck
AST migration, Juliet CWE-572 prec 1.000, 5 baseline CWEs zero
regression).
