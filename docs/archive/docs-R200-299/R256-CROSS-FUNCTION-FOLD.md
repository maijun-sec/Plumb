# R256 — Cross-function fold wiring + LiteralBooleanCondition

## Why

R255 wired the
`MethodSummary` cache
into the engine and
shipped a
`SummaryApplier` utility
that folds a callee's
return value at the
call site. But R255
*also* had a critical
gap: the
`SummaryApplier` was a
utility with no
automatic caller. A
checker that wanted
to see a folded
constant had to call
`SummaryApplier.apply`
itself, and no checker
did. R256 closes the
loop: the engine
*automatically* calls
`SummaryApplier.apply`
on every `CALL`
instruction *before*
any checker sees it,
and
`LiteralBooleanConditionChecker`
(R256 first consumer)
is taught to read the
resulting
`RETURN_VALUE_FOLDED`
fact and emit a
CWE-547 finding with a
note that the constant
came from a
cross-function fold.

## What R256 added

1. **Engine-level
   `SummaryApplier`
   auto-apply**. The
   RPO loop in
   `DfaEngine.analyseOneFunction`
   now intercepts
   every
   `TacInstruction.Kind.CALL`
   and runs
   `SummaryApplier.apply`
   on it *before*
   dispatching to the
   per-checker
   `checkInstruction`.
   Failures are
   non-fatal
   (logged at
   `WARN`, the
   checkers proceed
   with the
   pre-fold state).
   This is the missing
   R255 piece that
   makes the
   cross-function
   fold actually
   visible to the
   rest of the engine.

2. **SummaryApplier
   null-check on
   `FunctionSignature.parse`**.
   The signature
   parser returns
   `null` (not an
   exception) for
   non-canonical call
   signatures like
   `Integer.parseInt/1`
   or
   `System.getenv/1`.
   R255 missed the
   null case; R256
   adds the check
   and returns
   `null` cleanly
   (the engine then
   hits the cache's
   UNKNOWN sentinel).

3. **`LiteralBooleanConditionChecker`
   fold-aware**. R239
   originally fired
   only on
   *source-level*
   literal boolean
   conditions
   (`if (true) ...`).
   R256 adds a
   second detection
   path: when the
   branch condition is
   a `TacVar` with a
   `RETURN_VALUE_FOLDED`
   fact whose value
   starts with
   `const:`, the
   checker fires a
   CWE-547 finding
   with the message
   "...folds to a
   literal constant:
   <value> — branch is
   dead/redundant
   (after cross-function
   fold)". Per R256
   design call, both
   source-level and
   fold-derived are
   reported but with
   distinct messages
   so the reviewer
   can tell the
   difference.

4. **R256CrossFunctionFoldTest**
   — 3 unit tests on
   synthetic IR:
   - `foldedConstBool_firesLiteralBooleanCondition`:
     synthetic
     `b = IO.staticReturnsTrue(); if (b) {...}`
     produces one
     folded
     CWE-547
     finding.
   - `sourceLiteralBool_stillFires`:
     regression test —
     R256 must not
     change the
     pre-R256
     source-level
     detection.
   - `foldedParam_doesNotFire`:
     fold to a
     parameter
     (not a const)
     is *not* a
     literal-boolean
     condition; the
     checker stays
     silent.

5. **R256JulietCrossFunctionSweepTest**
   — 2 integration
   tests on real
   Juliet (gated on
   the Juliet root
   being present):
   - `julietCwe129FoldsIOStaticReturnsTrue`:
     merges
     CWE129 s01
     + the
     `testcasesupport`
     helpers into
     one
     `TacProgram`,
     runs the
     engine with
     `LiteralBooleanConditionChecker`,
     reports the
     source-level
     vs.
     cross-function
     -folded
     split.
   - `julietCwe252FoldsIOStaticReturnsTrue`:
     same shape
     for CWE252.

## CWE-129 / CWE-252 Juliet findings

The integration test
runs the merged
program and reports:

```
R256 Juliet CWE129 s01: 0 LiteralBooleanCondition findings
  (source-level=0, cross-function-folded=0, of 980 total CWE files)
R256 Juliet CWE252 s01: 0 LiteralBooleanCondition findings
  (source-level=0, cross-function-folded=0)
```

Both come back with
zero findings because
Juliet's CWE129 and
CWE252 family files
do not, in practice,
write the
`if (IO.staticReturnsTrue())`
pattern in the form
`if (b)` where
`b = IO.staticReturnsTrue()`.
The calls in those
families are usually
`IO.writeLine(...)`,
`IO.logger.log(...)`,
`System.getenv(...)`,
etc. — none of which
return a boolean
constant. The Juliet
families that *do*
heavily use
`if (IO.staticReturnsTrue())`
are
CWE-561 (Dead Code)
and CWE-572 (Call to
Thread run) —
expected sweep
follow-ups in R257.

## R255 → R256 correction: SummaryApplier had no caller

A subtle but
important point
worth keeping in
the record: the
R255 design assumed
that checkers would
*opt in* to calling
`SummaryApplier` from
their own
`checkInstruction(CALL)`
hook. R256 found
that no checker
actually did this,
and no other engine
code path was calling
the applier either —
the result was that
the
`RETURN_VALUE_FOLDED`
fact was never
written to the fact
bag in any of the
R255 unit tests, the
R253 v2 cross-TU
sweep, or the R246 /
R250 single-TU
sweeps. R256 fixes
this by making the
engine itself call
the applier on every
`CALL` before
dispatching to
checkers. The
follow-up is to teach
more checkers
(branch-condition
peers of
`LiteralBooleanCondition`)
to consume the
result.

## Pitfalls fixed during R256

- **`SummaryApplier.apply`
  didn't null-check
  `FunctionSignature.parse`**.
  Same root cause as
  the R255 cache-load
  NPE, but caught in
  a different
  call-site. The
  parser returns
  `null` for
  non-canonical call
  sigs; the applier
  now skips cleanly
  and the engine
  falls back to the
  UNKNOWN cache
  sentinel.
- **R255 claimed
  `SummaryApplier` is
  automatically
  called but no
  engine path
  actually called
  it.** R256 adds the
  engine-level
  interception; this
  is a design-doc
  correction, not a
  code fix.

## Test coverage

```
R256CrossFunctionFoldTest: 3/0/0/0
- foldedConstBool_firesLiteralBooleanCondition
- sourceLiteralBool_stillFires
- foldedParam_doesNotFire

R256JulietCrossFunctionSweepTest: 2/0/0/0
- julietCwe129FoldsIOStaticReturnsTrue
- julietCwe252FoldsIOStaticReturnsTrue
```

The full
`plumb-dfa`
suite is now
**438/0/0/0**, with
R256 contributing
5 new tests and zero
regressions.

## R257 / R258 (next)

- **R257**: teach
  the other
  branch-condition
  checkers
  (`AssignmentInConditional`,
  `EmptyIfBlock`,
  `UnusedLabel`,
  `DeadCodeAfterReturn`)
  to consume the
  `RETURN_VALUE_FOLDED`
  fact for their
  respective
  conditions. Run
  the Juliet source
  + DFA sweeps
  across the full
  CWE252/561/572
  families and
  compare against
  the R246 / R250
  baselines.

- **R258**: teach
  the
  `SIDE_EFFECT_RECORDED`
  consumer — a
  generic
  caller-state
  invalidator that
  reads the fact
  bag and clears
  read state for
  any operand that
  the callee is
  known to write.
