# Plumb checker restructure v2 (R785-R792, 2026-10-11)

## TL;DR

R783 had the wrong mental model. R784 (delete duplicate
UncheckedLoopConditionChecker) is the only correct piece.
This doc **replaces** R783 entirely.

**The new mental model**:

- **AST rule** = JavaParser `VoidVisitorAdapter` walking a
  `CompilationUnit`. Has `ResolvedType` and
  `ResolvedDeclaration` available via the symbol solver.
  Fires on syntactic / structural patterns.
- **DFA rule** = walks the TAC IR (per-instruction state
  machine: taint propagation, range lattice, path-sensitive
  guards). Fires on dataflow / state-sensitive patterns.
- **No hybrid** — a checker is one or the other. The 26
  source-reading checkers in `checkers/` that currently
  use `DfaChecker` with `checkInstruction` as a no-op and
  regex-on-source-text in `finish()` are **AST rules
  pretending to be DFA rules**. They must be migrated to
  proper AST (JavaParser visitor + `StyleContext`-style
  report API) one by one.

**plumb-style is not an orphan framework** — it is the
existing AST-rule home. The mistake was keeping it as a
*sibling* Maven module; the right move is to make it the
AST sub-package of `plumb-dfa`. Specifically:

- `plumb-style`'s `StyleCheck` interface, `StyleContext`
  class, and `AstDispatcher` are the correct abstractions
  for AST rules.
- They are physically moved into
  `plumb-dfa/src/main/java/org/plumb/dfa/checker/ast/`
  with package `org.plumb.dfa.checker.ast`.
- The 26 source-reading checkers migrate to this package
  one at a time, with each migration replacing the
  regex-on-source-text implementation with a
  `VoidVisitorAdapter` implementation.

The `plumb-style` Maven module is **kept** as a thin
shared library (the existing `StyleCheck` types and the
564 existing `Check` implementations stay there) but
`plumb-dfa` no longer depends on it as a JAR — instead
plumb-dfa **owns** the AST contract. The 564 plumb-style
checks become reference implementations and an
eventual source for new plumb-dfa AST checks. Nothing
in the plumb-dfa CI/eval path changes when plumb-style
gets touched.

---

## What's wrong with the current state (R783 was wrong about)

R783 (already overwritten — see git log) had the wrong
framing:

- ❌ "activate plumb-style" — plumb-style is not dormant;
  it has 564 implementations of `StyleCheck`. R783 framed
  it as an "orphan framework" needing an "adapter".
- ❌ "SourceReadingChecker interface for 26 file-scanners"
  — the 26 checkers are not "source-reading" in a
  special sense; they are **AST rules that happen to
  use regex on the source string**. The right move is
  not to keep them on regex, it's to convert them to
  JavaParser visitor.
- ❌ "boundary/ contains path-sensitive + numeric" —
  correct as far as it goes, but doesn't help because
  the path-sensitive and numeric checkers ARE
  DFA rules (TAC IR), and the AST rules are the
  ones that need a home separate from DFA.
- ❌ "AuxChecker interface for EntropyDetector /
  LiteralSourcedTracker / SourceReader" — these are
  utilities, not checkers. They should live in
  `plumb-dfa.util` (or stay where they are) but
  not pretend to be `DfaChecker` siblings.

## What's correct from R784 (keep)

- ✅ `checkers/UncheckedLoopConditionChecker.java` is
  a deprecated stub. The real CWE-606 rule lives at
  `checkers/taint/UncheckedLoopConditionChecker.java`.
- ✅ `r720_full_strategy.yaml` has only the
  `plumb.numeric.unchecked-loop-condition` entry.

## New target state (after R792)

```
plumb-dfa/src/main/java/org/plumb/dfa/
├── engine/                          (DfaEngine, AnalysisContext — unchanged)
├── config/                          (CheckerRegistry, SinkFamily, ...)
├── fact/                            (BuiltinFactKinds, ...)
├── solver/                          (SolverZ3Bridge, ...)
├── taint/                           (TaintedCheckerBase, GuardTracker, PathSensitiveCheckerBase)
├── points/, resolve/, summary/, cg/, context/   (unchanged)
├── checker/                         (NEW package, replaces flat "checkers/")
│   ├── DfaChecker.java               (interface, unchanged contract)
│   ├── AstChecker.java               (NEW marker interface; the 26 AST rules will use this)
│   ├── AuxChecker.java               (NEW marker interface; utilities use this, not DfaChecker)
│   ├── ast/                          (NEW: AST rule home, absorded from plumb-style)
│   │   ├── AstRunner.java            (NEW: entry point that wires CompilationUnit into AstChecker.run)
│   │   ├── AstReport.java            (NEW: thin wrapper over Finding; lives in this package)
│   │   ├── MethodCallOnThreadCheck.java    (migrated from checkers/ClassThreadChecker)
│   │   ├── CompareWithSelfAstCheck.java    (migrated from checkers/CompareWithSelfChecker; AST variant)
│   │   ├── HardCodedKeyAstCheck.java       (migrated from checkers/HardCodedKeyChecker; AST variant)
│   │   ├── ... (one R-Round per migration)
│   ├── dfa/                          (NEW: real DFA checks, ~33 files)
│   │   ├── OffByOneChecker.java
│   │   ├── UncheckedReturnChecker.java
│   │   ├── CompareWithSelfChecker.java   (DFA variant; alias-class based, kept as DFA)
│   │   ├── ...
│   ├── taint/                        (NEW: TaintedCheckerBase derivatives, ~40 files)
│   │   ├── SqlInjectionChecker.java
│   │   ├── UncheckedLoopConditionChecker.java
│   │   ├── ...
│   ├── boundary/                     (NEW: path-sensitive + numeric, ~9 files)
│   │   ├── BoundaryConstraintChecker.java
│   │   ├── DivZeroConstraintChecker.java
│   │   ├── OverflowConstraintChecker.java
│   │   ├── UnderflowConstraintChecker.java
│   │   ├── ArrayBoundsConstraintChecker.java
│   │   ├── ResourceLeakChecker.java          (moved from checkers/taint/)
│   │   ├── ResourceExhaustionChecker.java     (moved from checkers/)
│   │   └── Range.java
│   ├── util/                         (NEW: non-rule utilities, 3 files)
│   │   ├── EntropyDetector.java              (was checkers/; AuxChecker only, never emits)
│   │   ├── LiteralSourcedTracker.java        (was checkers/; AuxChecker only, never emits)
│   │   └── SourceReader.java                 (was checkers/; pure helper, not a checker at all)
│   └── (checkers/ package is gone — every file has been moved to ast/, dfa/, taint/, boundary/, or util/)
└── (boundary/ at top level is gone — moved under checker/boundary/)
```

### Key difference from R783

- **R783 kept the 26 source-reading checkers on regex.**
  This doc says: **they are AST rules** and must be
  rewritten with JavaParser visitor.
- **R783 had 3 new interfaces** (SourceReadingChecker,
  AstCheckerAdapter, AuxChecker). This doc has **2**:
  `AstChecker` (the AST-rule marker) and `AuxChecker`
  (for non-emitting helpers). The DfaChecker interface
  is unchanged.
- **R783 said "plumb-style is the AST framework"** —
  passive observation. This doc says: **plumb-dfa owns
  the AST contract**, and the existing plumb-style
  `StyleCheck` / `StyleContext` are the reference
  implementation that plumb-dfa absorbs.

### `AstChecker` interface (NEW)

```java
// checker/AstChecker.java
package org.plumb.dfa.checker;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;

/**
 * A checker that walks a {@link CompilationUnit} via
 * JavaParser. Implementations are typically a
 * {@link com.github.javaparser.ast.visitor.VoidVisitorAdapter}
 * subclass that calls
 * {@link AstReport#report(AstCheck, int, int, String, Finding.Severity)}
 * to emit findings.
 *
 * <p>AST rules never look at the TAC IR. They see only
 * the source-level AST and (optionally) the symbol
 * solver for type / declaration resolution.</p>
 */
public interface AstChecker {
    /** The rule id (e.g. {@code "plumb.style.method-call-on-thread"}). */
    String name();

    /** Run the check on the given compilation unit. */
    void run(AstContext ctx);

    /**
     * Default {@code null} (no AI dedup). AST rules that
     * need AI judgement override this to return a short
     * prompt describing the rule.
     */
    default String getAiPrompt(Finding finding) { return null; }
}
```

```java
// checker/ast/AstContext.java
package org.plumb.dfa.checker.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;

/**
 * Per-file AST-check context. Similar in spirit to
 * {@link org.plumb.style.StyleContext} but owned by
 * plumb-dfa and trimmed to the bits AST checks
 * actually use.
 */
public final class AstContext {
    private final ParsedSource source;
    private final List<Finding> findings = new ArrayList<>();

    public AstContext(ParsedSource source) { ... }
    public ParsedSource source() { return source; }
    public CompilationUnit unit() { return source.unit(); }
    public TypeSolver typeSolver() { return source.typeSolver(); }
    public List<Finding> findings() { return findings; }

    public void report(int line, int col, String message,
                      Finding.Severity sev, String dataflowHead) {
        // ... builds Finding, sets path from source.path(), appends to findings
    }
}
```

### `AuxChecker` interface (NEW)

```java
// checker/AuxChecker.java
package org.plumb.dfa.checker;

/**
 * Marker for utility classes that contribute cross-cutting
 * state to the engine (entropy scoring, literal-sourced
 * tracking, source-line caching) but never emit findings
 * themselves. AuxCheckers are not registered as rules;
 * the engine makes them available via
 * {@code AnalysisContext.aux(Class)}.
 */
public interface AuxChecker {
    String name();
    default void initEngine(AnalysisContext ctx) {}
    default void finishEngine(AnalysisContext ctx) {}
}
```

### Why `DfaChecker` is unchanged

A `DfaChecker` is a per-instruction state machine over
the TAC IR. Its lifecycle is `initEngine` /
`initFunction` / `checkInstruction` /
`summarizeFunction` / `finishFunction` / `finish`. The
33 real DFA checks (OffByOne, UncheckedReturn,
CompareWithSelf alias-class version, Iterator
concurrent mod, ORM\*, Deadlock, CorsMisconfig, ...)
need exactly this contract. No simplification is
possible without breaking them.

### What goes in `util/`

Three current `checkers/` files are not checkers at all:

- `EntropyDetector.java` — helper, computes Shannon
  entropy + char-class diversity for hard-coded-password
  FP suppression. Has no `DfaChecker` logic, just a
  pure-function API.
- `LiteralSourcedTracker.java` — per-function map of
  "TacVars assigned from string literals" used by
  HardCodedKeyChecker and HardCodedPasswordSinkChecker
  to share state.
- `SourceReader.java` — file-line cache used by
  ObjectModelChecker and other source-reading checkers
  to avoid re-reading the same file twice.

After R791 these become plain utility classes in
`checker/util/`. They lose the `DfaChecker` interface
and are wired into the engine via `AuxChecker` (or
removed from the engine entirely if they're only used
by the AST-runner that already has the source in
memory — that's the case for `SourceReader`).

### What about the 33 real DFA checkers

They stay as `DfaChecker` implementations, move from
`checkers/` to `checker/dfa/`, and the package change
is the only diff. (The exception is `CompareWithSelf`
which has both a DFA variant — alias-class based — and
will get an AST variant in R790+ for the
syntactic-only case. The DFA variant stays in
`checker/dfa/`.)

---

## R-Round plan (R785-R792)

| R | Goal | Files | Risk |
|---|---|---|---|
| R785 | This doc (replacing R783) | docs/architecture/ + reports/r785/ | 0 |
| R786 | New `checker/ast/` package: `AstChecker` interface, `AstContext`, `AstRunner`, `AstReport`. Wire into `DfaEngine` so a list of `AstChecker` is invoked per `ParsedSource` after the IR is built. | 4 new files + 1 engine hook | 0 (no AstChecker registered yet) |
| R787 | Pick the simplest source-reading checker and migrate to AST. Target: `MethodCallOnThread` (the `t.run()` regex check in ClassThreadChecker). Write it as a `VoidVisitorAdapter` walking `MethodCallExpr`, looking at scope chain to confirm receiver is `Thread`, emitting via `AstContext.report`. Verify Juliet CWE-572 prec bit-identical to R784. | 1 file move + 1 new ast file + tests | 0 |
| R788 | Move `checkers/taint/ResourceLeakChecker.java` → `checker/boundary/ResourceLeakChecker.java`. (Extends `PathSensitiveCheckerBase` — wrong directory.) Update package + imports. Verify R784 prec. | 1 file move + import audit | 0 |
| R789 | Move `checkers/ResourceExhaustionChecker.java` → `checker/boundary/ResourceExhaustionChecker.java`. Update package + imports. Verify R784 prec. | 1 file move + import audit | 0 |
| R790 | Migrate one more AST rule. Pick from { `CommentInfoLeak`, `SuspiciousComment`, `ReachableAssertion` } — the three that are 100% source-text patterns. R787 proved the pattern; R790 confirms it's repeatable. | 1-2 file moves + new ast files | 0 |
| R791 | Add `AuxChecker` interface. Move `EntropyDetector` / `LiteralSourcedTracker` / `SourceReader` to `checker/util/`. Remove their `DfaChecker` impls. Verify R784 prec. | 1 new file + 3 moves | 0 |
| R792 | Full eval: `mvn -pl plumb-dfa -am clean compile test-compile`, 5-CWE scan, all plumb-dfa tests pass, 0 unknown-file, 0 flow-step regression. | 0 file changes (verification only) | — |

### Constraints on every R-Round

1. **No behavior change in this batch.** Every move
   is a pure relocation + package update unless
   explicitly listed as a "rewrite to AST" R-Round.
2. **All registry references updated.** Rule ids
   (`plumb.xxx.yyy`) are the contract with the eval
   harness; they stay bit-identical.
3. **Plumb-style stays untouched** in R785-R792. The
   564 `StyleCheck` implementations are a separate
   project; the AST contract for plumb-dfa is the
   new `checker/ast/*` package, which deliberately
   shares the `StyleContext` shape (because it's the
   right shape) but does not import from
   `org.plumb.style`.
4. **One AST rule per R-Round.** R787 migrates ONE
   rule. R790 migrates ONE more. R800+ migrates the
   rest. The point is to keep the per-R diff small
   enough that any FP regression is auditable.

### Out of scope (R800+ backlog)

- Migrating the remaining 23 source-reading checkers
  to AST. One R-Round per checker (or batch by CWE
  family if all 4 in a family share a pattern).
- Splitting `BoundaryConstraintChecker` into
  base + per-hazard subclasses.
- Wiring AI-dedup defaults for the migrated AST rules.
- Bulk-importing the 564 plumb-style `StyleCheck`
  implementations into `checker/ast/`. The right
  time is when a plumb-style check covers a
  Juliet CWE that plumb-dfa doesn't have.

---

## Decision log

### Why AST is a separate marker interface (not a sub-interface of DfaChecker)

An AST rule has **no per-instruction work** — its
input is the `CompilationUnit` and its output is a
list of `Finding`. There is no `initFunction` /
`checkInstruction` / `summarizeFunction` to call.
Forcing every AST rule to implement those methods (as
no-ops) is the original sin R784 inherited.

`AstChecker` has the minimum contract: `name()` and
`run(AstContext)`. The engine calls it once per file,
not once per instruction.

### Why `plumb-style` is absorbed into `plumb-dfa` (not kept as a sibling)

Three reasons:

1. **Cohesion.** A user adding a new CWE rule should
   have exactly one place to add it
   (`checker/ast/...` for AST, `checker/dfa/...` for
   DFA). Today they have to choose between two
   Maven modules, two package roots, two sets of
   "where do I report findings" APIs, and two rule
   registries.
2. **Plumb-style's 564 checks are reference, not
   runtime.** They live on disk; they don't ship
   with plumb-main's runtime classpath. Carrying
   them as a "sibling module" is an integration
   pretence that we don't pay for. The right time
   to bulk-import is when a specific check covers a
   Juliet CWE that plumb-dfa doesn't have — that's
   a per-rule decision, not a "merge 564 files"
   decision.
3. **The `StyleContext` API is small.** 230 lines.
   Re-implementing it in `plumb-dfa.checker.ast` is
   cheaper than carrying the cross-module
   dependency for a one-method `report(int, int, String, Severity)`.

### Why this batch doesn't touch the 564 plumb-style checks

R785-R792 ships in this session: 7 R-Rounds, 0
behavior change, audit-able diffs. Bulk-importing
564 plumb-style checks would be a 100-file PR and
would require deciding which ones map to which
CWEs — a research task, not a refactor.

The reference implementation in
`checker/ast/AstContext` deliberately mirrors
`StyleContext`'s API so that any future bulk-import
is a package rename + import rewrite, not a redesign.

### Why R787 picks MethodCallOnThread (not CommentInfoLeak)

`MethodCallOnThread` is the simplest of the 26:
single method-call pattern, one regex (`\.run\s*\(\s*\)\s*;`)
becomes one `MethodCallExpr` visit. The migration
fits in 30 lines and proves the pattern.

`CommentInfoLeak` is more complex (HTML + Java +
block + line comment detection) and is a better R790
target — once R787 proves the pattern, R790
applies it to a less-trivial case.

---

## Lesson 1149

### 1149 (R785 — AST and DFA are not options on a spectrum, they're different kinds of work)

The R783 framing was that AST rules are "lesser DFA
rules" — weaker, less state, just text matching. That's
backwards. An AST rule is **type-resolved structural
analysis** (JavaParser `CompilationUnit` + symbol
solver). A DFA rule is **dataflow analysis** (TAC IR +
per-instruction state). They have different inputs,
different output paths, different testing strategies,
and different failure modes.

**Lesson**: when classifying a rule, the question is
not "how much state does it carry" but "what is its
input". If the input is the `CompilationUnit`, it's
AST. If the input is the TAC IR, it's DFA. There is
no third option. A "regex on source text" check is
**an AST rule that hasn't been written yet** —
the writer chose regex because it was the path of
least resistance, not because the problem is
fundamentally textual.

R787 picks `MethodCallOnThread` because it makes
this point concretely: the regex `\.\s*run\s*\(\s*\)`
becomes `scope.contains("Thread") &&
methodName == "run"`. The latter is **strictly more
precise** (no false-positive on `stream.run()` or
`runnable.run()` where the receiver is a non-Thread
Runnable). The migration is not a "switch to a
different framework" — it's a "use the right tool".

---

## Cross-cutting principle (R776 + ... + R785)

Ten R-Rounds in a row:

- R776-R782: correctness (FlowStep, unknown-file, numeric
  flow, per-type messages, direction split, NE-guard).
- R783: WRONG — overwrote by R785.
- R784: first correct step (delete duplicate
  UncheckedLoopConditionChecker).
- R785: this doc — **the new mental model** is
  "AST is AST, DFA is DFA, no hybrids".
- R786-R792: the restructure per this doc.

R785 is the first **re-framing** R-Round in the
series. The previous 7 (R776-R782) were all about
**finding correctness** — emit the right finding at
the right line. R784-R792 are about **rule
architecture** — make it possible to add the next
200 rules without the architecture rotting.

---

## Files (this doc)

- `docs/architecture/checker-restructure-2026-10-11.md` (this file; replaces R783)
- R785 SUMMARY: `juliet-coverage/reports/r785/SUMMARY.md`
- R784 stays as-is (correct as far as it went).
- R786-R791: see per-R SUMMARY in `juliet-coverage/reports/r78{6..9}1/`
- R792: verification only

---

## Correction: R787a invalidates R785's "sibling module" stance (2026-10-11)

This doc was correct in saying "AST and DFA are different kinds
of work" (Lesson 1149), but **wrong** in two important ways
that R787a corrected:

1. **`plumb-style`'s 564 checks are NOT "reference, not
   runtime"** — they ship as part of the build, are loaded by
   `StyleCheckRegistry` via classpath scan, and are already
   invoked by `ScanRunner`. They are **live production
   infrastructure**, not a code-reading reference. R785's
   framing here was sloppy.

2. **The right AST-runner is `plumb-style`'s existing
   `AstDispatcher` + `StyleContext` + `AstChecker` triple**,
   not a new `checker/ast/AstContext` package in plumb-dfa.
   Re-implementing those classes in plumb-dfa is what R786
   did, and R787a deleted all 5 of those files. The
   cardinality constraint from the user is **"use AST for
   what AST solves; use DFA for what DFA solves; never mix"**,
   which means a single shared AST runtime (in plumb-style),
   not two parallel implementations.

The corrected principle statement lives in
`juliet-coverage/reports/r787a/SUMMARY.md` ("Cardinal
principles"). Future migrations of source-reading checkers
(CWE-579, 585, 609, 835, plus the seven others in
plumb-dfa/checkers/) follow the R787a template: deprecation
stub in plumb-dfa, real implementation in plumb-style under
the existing `AstChecker` interface.
