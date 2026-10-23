# Plumb checker architecture restructure (R783-R792, 2026-10-10)

## TL;DR

R782 closed the CWE-369 NE-guard gap. The user then surfaced three
structural problems:

1. **Regex over AST**: many checkers parse source with `Pattern.compile(...)`
   when a JavaParser `VoidVisitorAdapter` would be both more precise and
   more readable.
2. **DFA overkill**: 26 of the 60 root-level `checkers/` files don't
   actually walk the TAC IR — they read the source file once in
   `finish()` and fire on regex matches. These are
   `DfaChecker` in name only; they should not be in the
   DFA engine.
3. **Directory chaos**: `checkers/`, `checkers/taint/`, and
   `boundary/` are siblings but the files inside mix DFA,
   taint, path-sensitive, source-reading, and tool-class
   categories. The same CWE-606 rule exists in two
   files (`checkers/UncheckedLoopConditionChecker` and
   `checkers/taint/UncheckedLoopConditionChecker`).
   `plumb-style/` (647 files) is an orphan AST-style framework
   that `plumb-dfa` doesn't reference at all.

This document records the target layout, the interface
split, and the R-Round breakdown. The decision is to
**restructure only** (no DFA→AST migration in this batch —
that's a follow-up once the structure is stable).

---

## Current state (R782 baseline, 2026-10-10)

```
plumb-dfa/src/main/java/org/plumb/dfa/
├── checkers/                       (60 .java files)
│   ├── BadShiftChecker.java
│   ├── ClassBasedIssueChecker.java
│   ├── CommentInfoLeakChecker.java
│   ├── CompareWithSelfChecker.java
│   ├── ...                          (60 total, all implements DfaChecker)
│   └── UncheckedLoopConditionChecker.java       <- CWE-606 (DFA, alias classes)
│   ├── ResourceExhaustionChecker.java           <- TaintedCheckerBase 派生
│   ├── EntropyDetector.java                     <- auxiliary, not a checker
│   ├── LiteralSourcedTracker.java               <- auxiliary
│   ├── SourceReader.java                        <- helper
│   ├── ObjectModelChecker.java                  <- 2 inner classes (CWE-580, CWE-581)
│   └── ...
├── checkers/taint/                  (42 .java files, all extend TaintedCheckerBase)
│   ├── UncheckedLoopConditionChecker.java       <- CWE-606 (duplicate of above)
│   ├── ResourceLeakChecker.java                 <- extends PathSensitiveCheckerBase, MISMATCH
│   └── ...                                      (40 other taint checks)
├── boundary/                        (6 .java files)
│   ├── BoundaryConstraintChecker.java
│   ├── DivZeroConstraintChecker.java
│   ├── OverflowConstraintChecker.java
│   ├── UnderflowConstraintChecker.java
│   ├── ArrayBoundsConstraintChecker.java
│   └── Range.java
├── engine/                          (DfaEngine, AnalysisContext, ...)
├── config/                          (CheckerRegistry, ...)
├── fact/                            (BuiltinFactKinds, ...)
├── points/                          (loop-bound, range, etc.)
├── resolve/                         (caller/callee resolution)
├── solver/                          (SolverZ3Bridge, LocalSolverBridge)
├── summary/                         (FunctionSummary, Operation, ...)
├── taint/                           (TaintedCheckerBase, GuardTracker, PathSensitiveCheckerBase)
├── cg/                              (call graph)
└── context/                         (TypeContext, etc.)

plumb-style/src/main/java/org/plumb/style/    (647 .java files)
├── StyleCheck.java                              (interface)
├── StyleContext.java
├── AstChecker.java
├── AstDispatcher.java
├── CheckerCategories.java
├── ObjectHijackCheck.java
├── UncaughtExceptionCheck.java
├── annotations/
└── ... (640 more, all orphan — no `implements StyleCheck`)
```

### Classification of the 60 root-level checkers/

| Bucket | Count | Examples | Implication |
|---|---:|---|---|
| True DFA (TAC IR walk) | 33 | OffByOne, CompareWithSelf, UncheckedReturn, IteratorConcurrentMod, ORM\*, Deadlock, ... | Keep `DfaChecker` interface |
| Source-reading (no IR walk) | 26 | CommentInfoLeak, SuspiciousComment, ReachableAssertion, NullDerefFromReturn, ClassBasedIssue, ClassThread, CleartextSocket, EnvVarInfoExposure, ... | Wrong base class — should be `SourceReadingChecker` or `StyleCheck` |
| Auxiliary (tool, not checker) | 3 | EntropyDetector, LiteralSourcedTracker, SourceReader | Should be `AuxChecker` or moved to `util/` |
| Inner classes (multiple rules) | 1 | ObjectModelChecker (2 inner DfaChecker impls) | Keep as one file but document |

### Concrete duplications & misplacements

- `checkers/UncheckedLoopConditionChecker.java` and
  `checkers/taint/UncheckedLoopConditionChecker.java` both
  implement CWE-606. The taint/ one is richer (range-aware
  suppression) and has tests. Delete the root one.
- `checkers/taint/ResourceLeakChecker.java` extends
  `PathSensitiveCheckerBase` but is in the `taint/` package.
  Should move to `boundary/` (path-sensitive) or a new
  `checkers/path/` dir.
- `checkers/ResourceExhaustionChecker.java` is also
  path-sensitive (extends `TaintedCheckerBase` but uses
  flow + path tracking); same misplacement.
- `boundary/BoundaryConstraintChecker.java` is the only file
  in `boundary/` that doesn't directly model a numeric hazard
  (Overflow/DivZero/Underflow/ArrayBounds are the 4 concrete
  rules). It's a 1500+ line base class. Belongs in a
  `boundary/base/` sub-package, not as a sibling of its
  concrete subclasses.

---

## Target state (after R792)

```
plumb-dfa/src/main/java/org/plumb/dfa/
├── engine/                          (unchanged: DfaEngine, AnalysisContext, ...)
├── config/                          (unchanged: CheckerRegistry, SinkFamily, ...)
├── fact/                            (unchanged: BuiltinFactKinds, ...)
├── solver/                          (unchanged: SolverZ3Bridge, ...)
├── taint/                           (base classes only: TaintedCheckerBase, GuardTracker, PathSensitiveCheckerBase)
├── points/                          (unchanged: loop-bound, range, etc.)
├── resolve/                         (unchanged)
├── summary/                         (unchanged)
├── cg/                              (unchanged)
├── context/                         (unchanged)
├── checker/                         (NEW, replaces the flat "checkers" package)
│   ├── DfaChecker.java              (existing, unchanged signature)
│   ├── SourceReadingChecker.java    (R788 — new marker interface, no IR walk)
│   ├── AuxChecker.java              (R791 — new marker interface, non-emitting)
│   ├── ast/                         (R787 — JavaParser-based, plumb-style adapter)
│   │   ├── AstChecker.java          (NEW: DfaEngine-side adapter for StyleCheck)
│   │   └── (none yet — populated in follow-up R-Round)
│   ├── dfa/                         (NEW — true DFA checks: IR walk + state)
│   │   ├── CompareWithSelfChecker.java
│   │   ├── OffByOneChecker.java
│   │   ├── UncheckedReturnChecker.java
│   │   ├── EqualsCalledOnNullChecker.java
│   │   ├── ... (~33 files)
│   ├── taint/                       (NEW — TaintedCheckerBase derivatives)
│   │   ├── SqlInjectionChecker.java
│   │   ├── PathTraversalChecker.java
│   │   ├── XssInjectionChecker.java
│   │   ├── ... (~40 files; UncheckedLoopConditionChecker here)
│   ├── boundary/                    (NEW — path-sensitive + numeric)
│   │   ├── BoundaryConstraintChecker.java    (moved + renamed package)
│   │   ├── DivZeroConstraintChecker.java
│   │   ├── OverflowConstraintChecker.java
│   │   ├── UnderflowConstraintChecker.java
│   │   ├── ArrayBoundsConstraintChecker.java
│   │   ├── ResourceLeakChecker.java          (moved from taint/)
│   │   ├── ResourceExhaustionChecker.java    (moved from root checkers/)
│   │   └── Range.java
│   ├── source/                      (NEW — SourceReadingChecker derivatives)
│   │   ├── CommentInfoLeakChecker.java
│   │   ├── SuspiciousCommentChecker.java
│   │   ├── ReachableAssertionChecker.java
│   │   ├── NullDerefFromReturnChecker.java
│   │   ├── ClassBasedIssueChecker.java
│   │   ├── ClassThreadChecker.java
│   │   ├── CleartextSocketUsageChecker.java
│   │   ├── EnvVarInfoExposureChecker.java
│   │   ├── HardCodedPasswordSinkChecker.java
│   │   ├── InfoExposureQueryStringChecker.java
│   │   ├── InsufficientSessionExpirationChecker.java
│   │   ├── MissingCryptoStepChecker.java
│   │   ├── MissingPasswordMaskingChecker.java
│   │   ├── ObjectModelChecker.java
│   │   ├── PoorCodeQualityChecker.java
│   │   ├── ProcessControlChecker.java
│   │   ├── RandomIvChecker.java
│   │   ├── RecursionChecker.java
│   │   ├── SensitiveInfoUnclearedChecker.java
│   │   ├── UncaughtExceptionChecker.java
│   │   ├── UnprotectedCredTransportChecker.java
│   │   ├── UnsaltedOneWayHashChecker.java
│   │   └── ... (26 files total)
│   └── util/                        (NEW — AuxChecker derivatives)
│       ├── EntropyDetector.java
│       ├── LiteralSourcedTracker.java
│       └── SourceReader.java
└── (boundary/ is gone — moved under checker/boundary/)
```

### Interface split

Three new marker interfaces alongside the existing `DfaChecker`:

```java
// checker/SourceReadingChecker.java
//
// Implemented by checkers that read the source file once in
// finish() and emit findings based on regex/AST matches. The
// DfaEngine skips the per-instruction call for these — the
// `checkInstruction` method on the interface is a no-op default.
public interface SourceReadingChecker extends DfaChecker {
    @Override default void checkInstruction(TacInstruction ins,
                                            TacFunction fn,
                                            AnalysisContext ctx) { }
}

// checker/AuxChecker.java
//
// Implemented by helper classes that contribute cross-cutting
// state (e.g. entropy scoring, literal-sourced tracking,
// source-line caching) but never emit findings themselves.
// AuxCheckers are not registered as rules; they are
// auto-discovered by the engine and made available to other
// checkers via AnalysisContext.aux(Class).
public interface AuxChecker {
    String name();
    void initEngine(AnalysisContext ctx);
    void finish(AnalysisContext ctx);
}

// checker/ast/AstChecker.java (R787)
//
// Adapter that bridges a plumb-style `StyleCheck` into the
// DfaEngine pipeline. The engine calls AstChecker once per
// compilation unit, hands it the CompilationUnit, and the
// adapter invokes the StyleCheck's `run(StyleContext)`.
public final class AstChecker implements DfaChecker {
    private final StyleCheck delegate;
    public AstChecker(StyleCheck delegate) { ... }
    @Override public String name() { return delegate.name(); }
    @Override public void initFunction(AnalysisContext, TacFunction) { }
    @Override public void checkInstruction(...) { }  // no per-instruction
    @Override public void finish(AnalysisContext ctx) { /* invoke delegate */ }
}
```

### Why three interfaces instead of one

The current `DfaChecker` interface requires five methods
(`initEngine`, `initFunction`, `checkInstruction`,
`summarizeFunction`, `finish`). A source-reading checker
implements four of them as no-ops; an aux helper has
different obligations entirely. Forcing them all through
one interface obscures intent and produces 30+ copy-pasted
boilerplate methods. The split lets:

- `DfaChecker` remain the strict "I walk IR" contract.
- `SourceReadingChecker` signal "I read the source file,
  per-instruction is a no-op" (so the engine can document
  it and the author can delete the stub).
- `AuxChecker` signal "I contribute state but I don't emit
  findings" (so the engine can keep them out of the
  rule-config list and the registry).

### Why plumb-style goes under `checker/ast/`, not a sibling

The user opted to "activate plumb-style". But plumb-style is
a **peer framework** with its own StyleCheck contract and
647 check implementations. Rather than pull 647 files into
`plumb-dfa`, we add `checker/ast/AstChecker.java` (the
adapter) and have it depend on plumb-style. The first
migration target is a single existing check (R789); the
plumb-style 647 backlog is *not* this batch's problem.

---

## R-Round plan (R783 → R792)

Each R-Round is 1 PR, ≤2 files changed, all 5-CWE eval
must remain bit-identical or improve (R-Risk = 0, no
regression allowed).

| R | Goal | Files | Risk |
|---|---|---|---|
| R783 | This document + R783 SUMMARY | docs/architecture/ + juliet-coverage/reports/r783/ | 0 |
| R784 | Delete `checkers/UncheckedLoopConditionChecker.java`; keep `checkers/taint/UncheckedLoopConditionChecker.java` as canonical CWE-606 (it has tests; the root copy is the no-tests branch) | 1 deletion + import audit | 0 (file unused — verify with grep) |
| R785 | Move `checkers/taint/ResourceLeakChecker.java` to `boundary/ResourceLeakChecker.java` (update package declaration + imports) | 1 file move + import audit | 0 |
| R786 | Move `checkers/ResourceExhaustionChecker.java` to `boundary/ResourceExhaustionChecker.java` | 1 file move + import audit | 0 |
| R787 | Add `AstChecker` adapter in `plumb-dfa/src/main/java/org/plumb/dfa/checker/ast/AstChecker.java`; wire it into `DfaEngine` so StyleChecks run alongside DfaCheckers | 2 new files + 1 engine hook | 0 (no StyleCheck registered yet) |
| R788 | Add `SourceReadingChecker` interface in `plumb-dfa/src/main/java/org/plumb/dfa/checker/SourceReadingChecker.java` | 1 new file | 0 (no checker implements it yet) |
| R789 | Convert 26 source-reading checkers from `DfaChecker` to `SourceReadingChecker`; move them to `checkers/source/` | 26 file moves + 26 package updates | 0 (semantics unchanged) |
| R790 | Split `DfaChecker` into the 3 working categories: leave DfaChecker intact but introduce 3 sub-interfaces? No — keep DfaChecker as the engine-facing interface; the 3 sub-types are: `DfaTaintChecker` (TaintedCheckerBase), `DfaPathChecker` (PathSensitiveCheckerBase / BoundaryConstraintChecker), `DfaAuxChecker` (helpers). New file `checker/DfaCheckerCategories.java` mirrors the plumb-style `CheckerCategories` pattern. | 1 new file | 0 |
| R791 | Convert EntropyDetector / LiteralSourcedTracker / SourceReader to `AuxChecker`; move to `checkers/util/` | 3 file moves + 3 impls | 0 |
| R792 | Full eval: `mvn -pl plumb-dfa -am clean compile test-compile`, 5-CWE scan, 35-CWE scan, all plumb-dfa tests pass, 0 unknown-file, 0 flow-step regression. | 0 file changes (verification only) | — |

### Constraints on every R-Round

1. **No behavior change** in this batch. Every move is a
   pure relocation + package update.
2. **No file deletion outside R784**. Even the duplicate
   `UncheckedLoopConditionChecker` is kept as a comment-only
   stub for R784 + R785 if it turns out something
   transitively imports it.
3. **All registry references updated**. The
   `CheckerRegistry.byName(...)` map and the
   `SinkFamily` config files are the entry points; every
   renamed class must keep its rule id (`plumb.xxx.yyy`)
   bit-identical so eval doesn't move.
4. **Each R-Round ships a SUMMARY.md** with: 5-CWE prec
   table, list of moved files, list of new imports, and a
   one-paragraph rationale per moved file.

### Out of scope (next batch)

- Migrating any DFA check to AST. R789 keeps the regex
  shape for source-reading checks; R800+ (post-R792) will
  rewrite individual checks to use `VoidVisitorAdapter` and
  measure FP delta.
- Plumbing the 647-file plumb-style backlog. R787 only
  wires the adapter; no StyleCheck gets registered.
- Renaming `plumb-style` itself or its package
  (`org.plumb.style` is the right namespace already).
- Splitting `BoundaryConstraintChecker` into
  `BoundaryConstraintCheckerBase` + per-hazard
  `*ConstraintChecker` — that's an R-Round in the
 100s.

---

## Cross-cutting principle

After R792, the directory tree reflects the **implementation
mechanism** rather than the rule name. Anyone looking at
`checkers/source/` knows immediately: "no IR walk, regex
or AST on raw source". Anyone looking at `checkers/taint/`
knows: "TAC IR + taint propagation". Anyone looking at
`checkers/boundary/` knows: "TAC IR + range lattice +
path-sensitive state". The package name is a 1-line
summary of how the rule works.

The package name should never lie. Today's `checkers/`
package mixes all three; tomorrow's `checker/{ast,dfa,taint,boundary,source,util}/`
can't.

---

## Files (this doc)

- `docs/architecture/checker-restructure-2026-10-10.md` (this file)
- R783 SUMMARY: `juliet-coverage/reports/r783/SUMMARY.md`
- R784-R791: see per-R SUMMARY in `juliet-coverage/reports/r78{4..9}1/`
- R792: verification only
