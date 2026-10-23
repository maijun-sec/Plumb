# R785 — AST/DFA re-framing (2026-10-11)

## Goal

R783 had the wrong mental model. The user caught it:
"AST is AST, based on JavaParser; DFA is DFA, based on
TAC IR. Don't mix them."

R785 replaces R783 with a new blueprint. The new
contract:

- **AST rule** = JavaParser `VoidVisitorAdapter` walking
  a `CompilationUnit`. Has `ResolvedType` /
  `ResolvedDeclaration` via the symbol solver. Fires on
  syntactic / structural patterns.
- **DFA rule** = walks the TAC IR (per-instruction state
  machine: taint propagation, range lattice, path-
  sensitive guards). Fires on dataflow / state-sensitive
  patterns.
- **No hybrid**. A "regex on source text inside a
  `DfaChecker`" is **an AST rule that hasn't been
  written yet**. R785+R787 plan to start writing them.

The 26 source-reading checkers in `checkers/` that
currently use `DfaChecker` with `checkInstruction` as
a no-op and regex-on-source-text in `finish()` are
AST rules pretending to be DFA rules. R785 (this
doc) re-frames the restructure, R786 introduces the
new `checker/ast/` package, R787 migrates the first
checker as a proof of concept.

---

## What R783 got wrong (R785 corrects)

R783 said "activate plumb-style" and treated
`plumb-style` as an "orphan framework" needing an
"adapter". R785 says plumb-style is **not dormant** —
it has 564 implementations of `StyleCheck` and the
existing `StyleContext` API is the right shape. The
mistake was keeping it as a *sibling* Maven module; the
right move is to make it the AST sub-package of
`plumb-dfa`. The 564 reference checks stay in
`plumb-style`; `plumb-dfa` owns the AST contract and
absorbs a small `StyleContext`-shaped context class
into `plumb-dfa.checker.ast`.

R783 also said "SourceReadingChecker interface for 26
file-scanners" — keeping them on regex. R785 says
**they are AST rules and must be rewritten with
JavaParser visitor** — one checker per R-Round in
R787-R790+.

---

## Code changes (this R-Round)

**No code changes.** R785 ships a planning document
only.

- `docs/architecture/checker-restructure-2026-10-11.md`
  (20 KB) — the new blueprint, replacing
  `checker-restructure-2026-10-10.md` (R783).
- This SUMMARY.md.

---

## Verification

R785 ships no code, so no compile / scan / eval. The
verification of this R-Round is the planning document
itself:

- Mental model: AST = JavaParser, DFA = TAC IR, no
  hybrids (rejected as the third option).
- Interface plan: `DfaChecker` (unchanged), `AstChecker`
  (new, `name() + run(AstContext)`), `AuxChecker` (new,
  for non-emitting helpers).
- Target layout: `checker/{ast,dfa,taint,boundary,util}/`
  with the 26 source-reading checkers eventually living
  in `checker/ast/` as `VoidVisitorAdapter`
  implementations.
- 7 R-Round plan (R786-R792), each 0 behavior change
  unless explicitly listed as a "rewrite to AST" R-Round.

---

## Decisions

### Why the new doc replaces R783 (doesn't revise it)

R783's foundational claim — "plumb-style is an orphan
framework needing an adapter" — is wrong. R785 is not
a "revision"; it's a "you had the wrong mental model,
here's the right one". Leaving R783 in place would
mislead the next reader. R785 overwrites
`checker-restructure-2026-10-10.md` (R783) with
`checker-restructure-2026-10-11.md` (R785) and the
R785 SUMMARY explicitly states "replaces R783".

### Why only ONE AST rule per R-Round in R787-R790

A bulk migration of 26 checkers in one R-Round would
produce a 2000+ line diff and any FP regression would
be unauditable. The per-R-Round limit (one rule at a
time) is the right cadence for empirical validation:
ship the migration, scan, see the delta, ship the next
one.

### Why `AstChecker` is a separate interface (not a sub-interface of `DfaChecker`)

An AST rule has **no per-instruction work** — its input
is the `CompilationUnit` and its output is a list of
findings. There is no `initFunction` /
`checkInstruction` / `summarizeFunction` to call. Making
`AstChecker extends DfaChecker` would force every AST
rule to inherit 5 no-op methods; making it a separate
interface gives it the minimum contract (`name() +
run(AstContext)`).

### Why R787 picks `MethodCallOnThread` (not `CommentInfoLeak`)

`MethodCallOnThread` is the simplest of the 26:
single method-call pattern, one regex
`\.run\s*\(\s*\)\s*;` becomes one `MethodCallExpr`
visit. The migration fits in 30 lines and proves the
pattern.

`CommentInfoLeak` is more complex (HTML + Java + block
+ line comment detection) and is a better R790 target
— once R787 proves the pattern, R790 applies it to a
less-trivial case.

### Why `plumb-style` stays untouched in R785-R792

The 564 `StyleCheck` implementations are a separate
project. R785-R792 are about giving `plumb-dfa` a
correct AST contract. Bulk-importing plumb-style's 564
checks would be a 100-file PR and would require
deciding which ones map to which CWEs — a research
task, not a refactor.

The reference implementation in `checker/ast/AstContext`
deliberately mirrors `StyleContext`'s API so that any
future bulk-import is a package rename + import
rewrite, not a redesign.

### What about the 33 real DFA checkers (OffByOne, UncheckedReturn, ...)

They stay as `DfaChecker` implementations, move from
`checkers/` to `checker/dfa/`, and the package change
is the only diff. The exception is `CompareWithSelf`
which has both a DFA variant — alias-class based — and
will get an AST variant in R790+ for the
syntactic-only case. The DFA variant stays in
`checker/dfa/`.

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
**an AST rule that hasn't been written yet** — the
writer chose regex because it was the path of least
resistance, not because the problem is fundamentally
textual.

R787 picks `MethodCallOnThread` because it makes this
point concretely: the regex `\.\s*run\s*\(\s*\)`
becomes `scope.contains("Thread") && methodName ==
"run"`. The latter is **strictly more precise** (no
false-positive on `stream.run()` or
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
- R785: this doc — **the new mental model** is "AST is
  AST, DFA is DFA, no hybrids".

R785 is the first **re-framing** R-Round in the
series. The previous 7 (R776-R782) were all about
**finding correctness** — emit the right finding at
the right line. R784-R792 are about **rule
architecture** — make it possible to add the next
200 rules without the architecture rotting.

---

## Files (R785)

- `docs/architecture/checker-restructure-2026-10-11.md` (20 KB)
- `juliet-coverage/reports/r785/SUMMARY.md` (this file)
