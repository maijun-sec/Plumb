# R783 — Checker architecture restructure plan (2026-10-10)

## Goal

R782 closed the CWE-369 NE-guard gap. The user then surfaced
three structural problems with the rule layer:

1. **Regex over AST**: many checkers parse source with
   `Pattern.compile(...)` when a JavaParser `VoidVisitorAdapter`
   would be both more precise and more readable.
2. **DFA overkill**: 26 of the 60 root-level `checkers/` files
   don't actually walk the TAC IR — they read the source file
   once in `finish()` and fire on regex matches. These are
   `DfaChecker` in name only; they should not be in the DFA
   engine.
3. **Directory chaos**: `checkers/`, `checkers/taint/`, and
   `boundary/` are siblings but the files inside mix DFA,
   taint, path-sensitive, source-reading, and tool-class
   categories. The same CWE-606 rule exists in two files
   (`checkers/UncheckedLoopConditionChecker` and
   `checkers/taint/UncheckedLoopConditionChecker`).
   `plumb-style/` (647 files) is an orphan AST-style
   framework that `plumb-dfa` doesn't reference at all.

This R-Round records the plan. The user opted for the
"structure only" branch (R783 → R792, 10 R-Rounds, no
behavioural change) over the higher-ROI but
higher-regret "structure + DFA→AST migration" branch.

---

## Code changes (this R-Round)

**No code changes.** This R-Round ships a planning
document only.

- `docs/architecture/checker-restructure-2026-10-10.md`
  — the target layout, the interface split, the
  R-Round breakdown, the per-R constraints, and the
  out-of-scope notes.

---

## Target layout (after R792)

```
plumb-dfa/src/main/java/org/plumb/dfa/
├── engine/                          (unchanged)
├── config/                          (unchanged)
├── fact/                            (unchanged)
├── solver/                          (unchanged)
├── taint/                           (base classes only)
├── points/, resolve/, summary/, cg/, context/   (unchanged)
├── checker/                         (NEW, replaces flat "checkers")
│   ├── DfaChecker.java              (existing, unchanged)
│   ├── SourceReadingChecker.java    (R788 — new marker)
│   ├── AuxChecker.java              (R791 — new marker)
│   ├── ast/                         (R787 — plumb-style adapter)
│   ├── dfa/                         (NEW — true DFA, ~33 files)
│   ├── taint/                       (NEW — TaintedCheckerBase, ~40 files)
│   ├── boundary/                    (NEW — path-sensitive, ~9 files)
│   ├── source/                      (NEW — source-reading, ~26 files)
│   └── util/                        (NEW — auxiliary, 3 files)
└── (boundary/ is gone — moved under checker/boundary/)
```

---

## Verification

R783 ships no code, so no compile / scan / eval. The
verification of this R-Round is the planning document
itself:

- 60 root-level checkers classified (33 DFA / 26 source-reading / 3 auxiliary)
- 1 duplicate file identified (`UncheckedLoopConditionChecker`)
- 2 misplaced files identified (`taint/ResourceLeakChecker`, root/`ResourceExhaustionChecker`)
- 26 source-reading checkers flagged for migration to `SourceReadingChecker`
- 647-file orphan framework (`plumb-style/`) documented and an integration path (R787's `AstChecker` adapter) designed

---

## Decisions

### Why a planning R-Round (R783) before any code change

A non-trivial restructure (10 R-Rounds, 30+ files moved,
3 new interfaces, 1 framework integration) is best done
with a doc first. The doc makes the constraints
**explicit** so every subsequent R-Round can hold itself
to them. It also gives the user a single place to review
the *whole* plan before any irreversible move.

### Why structure only (not structure + AST migration)

The user explicitly chose the structure-only branch.
The reasons it's the right call for *this* batch:

1. **Risk isolation**: moves and package updates are
   trivially testable (same name, same logic, different
   package). AST migration involves a semantic rewrite
   and a regression-risk window.
2. **Surface area for the next reviewer**: after R792,
   the next reviewer opens a directory and immediately
   sees which checks are DFA-style, which are
   source-reading, which are auxiliary. That's enough
   cognitive surface for one round.
3. **Migration is independent**: once structure is
   clean, the AST migration is one file at a time
   (R800+ for each candidate), with the user able to
   see the per-rule FP delta.

### Why three interfaces (DfaChecker / SourceReadingChecker / AuxChecker)

The current `DfaChecker` interface is a single contract
covering all three. Forcing source-reading checks to
implement `checkInstruction` (as a no-op) and
`summarizeFunction` (also a no-op) produces 30+ copies of
boilerplate that obscures intent. The split:

- Lets the engine **know** that a `SourceReadingChecker`
  has no per-instruction work (e.g. it can skip the
  per-instruction call site and run the regex/AST pass
  in `finish()`).
- Lets the engine **know** that an `AuxChecker` is a
  helper, not a rule (so it's not registered as a rule
  and is available via `AnalysisContext.aux(Class)`).
- Keeps `DfaChecker` as the strict "I walk IR" contract
  for the 33 true DFA checks.

### Why plumb-style integration is an adapter, not a fork

The user opted to "activate plumb-style". But plumb-style
has 647 files. The right move is:

1. Add a thin `AstChecker` adapter in
   `checker/ast/AstChecker.java` that takes a
   `org.plumb.style.StyleCheck` and exposes it as a
   `DfaChecker`. (R787)
2. Have DfaEngine treat it like any other checker. (R787)
3. **Don't** migrate the 647 plumb-style checks in this
   batch. The next batch (R800+) picks one
   source-reading checker at a time, migrates it to a
   `StyleCheck` implementation, and measures the FP
   delta.

This keeps the structure work cheap and the migration
work measurable.

### What's deliberately not in this batch

- **No DFA→AST migration**. That's per-rule work in
  R800+ territory.
- **No plumb-style bulk import**. 647 files is a
  separate project, and only a handful will be
  useful — the per-rule migration finds out which.
- **No `BoundaryConstraintChecker` split into base +
  per-hazard subclasses**. Currently a 1500+ line
  abstract class with 4 concrete subclasses. Splitting
  it would be a separate R-Round (likely R800+).
- **No rule-id renames**. The registry keys
  (`plumb.xxx.yyy`) are the contract with the eval
  harness; they stay bit-identical through the move.

---

## Lesson 1147

### 1147 (R783 — package name should never lie)

When a `checkers/` package contains 60 files where 33 are
true DFA checks, 26 are source-reading, 1 contains 2
inner DFA classes, and 3 are auxiliary helpers, the
package name has become a **lie**. It claims "checks"
but actually means "everything that wasn't big enough
to deserve its own directory".

**Lesson**: a package's name should be a 1-line summary
of how the code inside it works. When the summary
becomes a lie, rename or move. The boundary of the
package is a **documentation artefact** as much as a
file-system one. Today's `checkers/` boundary
documents "everything goes here", which is the same as
no boundary at all.

---

## Cross-cutting principle (R776 + R777 + R778 + R779 + R780 + R781 + R782 + R783)

Eight R-Rounds in a row, each holding the bar on
"**every DFA finding is correct**" and "**every artifact
has a clear home**":

1. **R776**: structured `FlowStep`; taint-style + resource checkers use it.
2. **R777**: `Finding.dfa` factory preserves empty filePath.
3. **R778**: 3-bug fix for unknown-file findings.
4. **R779**: 3 numeric checkers ported to 11-arg `Finding.dfa`.
5. **R780**: numeric checker messages name the actual Java type.
6. **R781**: ADD/SUB direction split.
7. **R782**: Tier-2 NE-guard refutation for CWE-369.
8. **R783**: this R-Round — restructure plan for the rule layer.

R783 is the first **non-bug-fix** R-Round in this series.
The previous 7 were about correctness; R783 is about
**maintainability** so the next 7 (R800+ AST migration)
can be done one rule at a time with a low cognitive
load.

---

## Files (R783)

- `docs/architecture/checker-restructure-2026-10-10.md` (17 KB)
- `juliet-coverage/reports/r783/SUMMARY.md` (this file)
