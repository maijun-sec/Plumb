# R257 — Second fold-aware branch-condition checker

## Why

R256 wired the engine
to fold a callee's
return value at the
call site and taught
`LiteralBooleanConditionChecker`
to consume the resulting
`RETURN_VALUE_FOLDED`
fact. R257 generalises
the same pattern to a
second
branch-condition
checker,
`AssignmentInConditionalChecker`,
and runs a real-Juliet
sweep to measure impact.

## What R257 added

1. **Fold-aware
   `AssignmentInConditionalChecker`**
   (R257). The
   pre-R257 checker
   only fired when the
   branch condition was
   a `TacVar` last
   written by a `MOVE`
   statement. R257
   adds a second path:
   when the condition
   is a `TacVar` whose
   fact bag carries a
   `RETURN_VALUE_FOLDED`
   fact with value
   starting with
   `const:`, the
   checker fires the
   same CWE-481 finding
   with a different
   message that
   mentions the
   cross-function fold.
   The pre-R257 path is
   preserved (regression
   test pinned).

2. **`R257AssignmentInConditionalFoldTest`**
   (new) — 2 unit
   tests on synthetic
   IR:
   - `foldedConstBool_firesAssignmentInConditional`:
     synthetic
     `b = IO.staticReturnsTrue(); if (b) {...}`
     produces one
     fold-derived
     CWE-481 finding.
   - `sourceAssignment_stillFires`:
     regression — the
     pre-R257
     MOVE-detection
     path still fires.

3. **`R257JulietCrossFunctionSweepTest`**
   (new) — 2
   integration tests
   on real Juliet
   (gated on the
   Juliet root being
   present): merges
   CWE113 s01 and
   CWE571 with the
   testcasesupport
   helpers into one
   `TacProgram`, runs
   the engine with
   the two fold-aware
   branch-condition
   checkers, and
   reports the
   source-level vs.
   cross-function-folded
   split.

## Why only two checkers

R256 / R257
fold-awareness is
useful for any checker
that examines a
branch condition.
Five candidates:

| Checker | Looks at the condition? | Fold-relevant? |
|---------|--------------------------|----------------|
| `LiteralBooleanCondition` (CWE-547) | yes (literal const) | yes — R256 |
| `AssignmentInConditional` (CWE-481) | yes (MOVE/CALL dest) | yes — R257 |
| `EmptyIfBlock` (CWE-1109) | no (block content) | no |
| `UnusedLabel` (CWE-1164) | no (label reachability) | no |
| `DeadCodeAfterReturn` (CWE-561) | no (post-RETURN) | no |

The user confirmed
the "改 2 个能受益的"
choice: R256 covered
`LiteralBoolean`, R257
covers
`AssignmentInConditional`.
The other three
examine structural
properties (block
emptiness, label
reachability, post-return
reachability) and
are independent of
whether the branch
condition is constant.

## Real-Juliet findings (R257)

The integration test
ran the merged
program:

```
R257 Juliet CWE113_HTTP_Response_Splitting/s01:
  LiteralBoolean:    source-level=0, fold-derived=0
  AssignmentInCond:  source-level=0, fold-derived=0
  total findings=0
R257 Juliet CWE571_Expression_Always_True/:
  LiteralBoolean:    source-level=0, fold-derived=0
  AssignmentInCond:  source-level=0, fold-derived=0
  total findings=0
```

Both come back with
zero findings. The
unit tests on synthetic
IR confirm the wiring
is correct (3/3 R256 +
2/2 R257). The Juliet
run shows 0 because
the parser / translator
emits only 15
instructions for
Juliet's `bad()`
function while the
source has 100+ lines
of body — the
translator drops most
of the body (likely
on the unsupported
`try { ... } catch ...
} finally { ... }`
constructs that
Juliet uses heavily).
Without the
`if (IO.staticReturnsTrue())`
instruction in the
emitted IR, the
fold-aware checker
has nothing to fire on.

## Pitfall documented: parser / translator coverage

A second limitation
surfaced during R257
debugging: Juliet's
real-world files
contain constructs
the translator
doesn't fully handle,
so the merged IR is
truncated. The
R256 / R257
fold-aware logic
itself is sound —
proven by the
synthetic-IR unit
tests — but a real
end-to-end Juliet
sweep will not show
fold-derived findings
until the translator
covers more Juliet
constructs. This is
an upstream limitation
that R256 / R257 do
not attempt to fix.

## Test coverage

```
R257AssignmentInConditionalFoldTest: 2/0/0/0
R257JulietCrossFunctionSweepTest:    2/0/0/0
```

The full
`plumb-dfa`
suite is now
**442/0/0/0**, with
R257 contributing
4 new tests and zero
regressions.

## R258 (next) — SIDE_EFFECT_RECORDED consumer

R258 will teach a
generic caller-state
invalidator to read
the
`SIDE_EFFECT_RECORDED`
fact that
`SummaryApplier` writes
on every `CALL` and
clear the relevant
read state when the
callee is known to
write to a field. This
plugs the side-effect
half of the
inter-procedural
contract that R254
built and R255 / R256
wired.
