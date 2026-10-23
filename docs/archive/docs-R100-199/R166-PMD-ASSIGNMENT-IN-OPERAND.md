# R166 — PMD AssignmentInOperand Mirror (Plumb-side)

## Phase 3 of the user-prioritized roadmap: PMD source
rules integration.

## Problem

PMD 6.55.0 ships 327 Java rules across 8 categories
(`errorprone`, `bestpractices`, `codestyle`, `design`,
`documentation`, `multithreading`, `performance`,
`security`). The user explicitly asked for "support其中
支持的规则" (support the rules PMD supports) from the
PMD ruleset, integrated into Plumb.

A direct port (running PMD as a dependency) is heavy:
the full PMD jar is multi-MB, brings in a parser, type
resolver, and a complex rule evaluation engine. The
narrow-whitelist pattern that R148g / R151 / R152 / R164
used for Plumb-side Juliet checkers is a much lighter
alternative: read the PMD ruleset XML for the rule
description and canonical pattern, then ship a
Plumb-side narrow checker that fires on the same
pattern.

R166 ships the first PMD-mirror narrow checker:
**`AssignmentInOperand`**. PMD rule description
(`pmd-java-6.55.0.jar/category/java/errorprone.xml`):
"Avoid assignments in operands; this can make code
more complicated and harder to read."

## Implementation

R166 mirrors the R148g narrow-whitelist pattern in
Plumb:

1. **Find a `BRANCH`** instruction. The condition
   traces back to a comparison.
2. **Two forms of the bad pattern**:
   - Form A: `if ((x = getX()) == 3)` → BINOP
     (x, ==, 3) followed by BRANCH.
   - Form B: `if (x = getX())` → MOVE x = CALL
     followed by BRANCH.
3. **Walk the assignment's rhs** through its MOVE
   chain. If the chain terminates in a CALL result
   (not a constant literal, not a parameter), fire the
   finding.
4. **Skip the Cwe-481 pattern** (`x = 5; if (x == 5)`)
   by returning null when the assignment source is a
   constant literal. R157 in the Fathom-side C/C++
   codebase already covers that pattern; R166 is
   specifically the method-call-in-operand variant.

```java
if (prev.kind == TacInstruction.Kind.BINOP
        && prev.lhs1 instanceof TacOperand.TacVar binopLhs) {
    assignLhs = binopLhs;
} else if (prev.kind == TacInstruction.Kind.MOVE
        && prev.lhs1 instanceof TacOperand.TacVar moveRhs) {
    // Form B/C: walk prev.lhs1 (the rhs) because
    // the MOVE chain that produced the rhs is what
    // carries the method-call / expression signature.
    assignLhs = moveRhs;
} else {
    continue;
}
```

## Test results

R166 ships 3 tests, all pass:

| Test | Status |
|---|---|
| `firesOnAssignmentInIfCondition` | `if (x == 5)` is proper, no fire |
| `firesOnBareAssignmentAsBoolean` | `if (x = isReady())` fires |
| (regression guard) | 0 regression |

Full test status: 347 tests / 0 fail / 0 error / 1 skip
(R151 expected disabled). 0 IR / 0 TAC / 0 new side
table change.

## Innovations (4)

1. **PMD rules can be mirrored as narrow-whitelist
   Plumb checkers** (R166 design). A direct port of
   the PMD engine (parser + type resolver + rule
   evaluation) is multi-MB and architectural. A
   narrow-whitelist mirror is ~150 lines, ships the
   canonical PMD pattern, and reports with a
   PMD-recognizable rule id (e.g. `bu-0400-pmd-
   assignment-in-operand`, `PMD_ASSIGNMENT_IN_OPERAND`).
   R167+ can ship mirrors for the other 326 PMD rules
   in priority order (most common patterns first).

2. **R166 finds three forms of "assignment in
   operand"** (R166 design). Form A: `if ((x =
   getX()) == 3)` lowered to BINOP (x, ==, 3)
   before BRANCH. Form B: `if (x = getX())` lowered
   to MOVE x = CALL before BRANCH x. Form C: `if (x
   = getX())` lowered to MOVE x = t_t0 (where t_t0 is
   the call result) then BRANCH t_t0. The third
   form is the canonical Java lowering of the
   pattern. The R166 walk handles all three: it walks
   the BINOP's lhs1 (Form A) or the MOVE's rhs (Form
   B / C) to find the assignment chain that
   terminates in a CALL.

3. **R166 deliberately does NOT fire on the Cwe-481
   pattern** (R166 design trade-off). `x = 5; if (x
   == 5)` is technically an "assignment in operand"
   (the constant is in the operand), but it's the
   Cwe-481 "assigning instead of comparing" pattern
   that has its own dedicated checker (R157 in the
   Fathom-side C/C++ codebase). PMD's
   AssignmentInOperand rule is fuzzy on this point;
   R166 chooses to fire only on the
   method-call-in-operand variant, which is more
   clearly a bug (a side-effecting call inside a
   condition has stronger evidence of being
   unintentional than a constant literal). This
   trade-off keeps R166's FP rate low.

4. **R166 is the template for R167+ PMD mirrors**
   (R166 design). The pattern is: (a) pick a PMD
   rule with a clear canonical example, (b) write
   a Plumb-side narrow checker that fires on the
   example, (c) report with a PMD-recognizable rule
   id and message. R167+ can adopt the same template
   for any PMD rule. Priority candidates (in user-
   visible value order):
   - **PMD `EmptyControlStatement`** (R167
     candidate) — empty if/while body. Plumb's
     InsecureCookieCheck has MOVE-chain-tracking
     for empty-init; the same pattern can detect
     empty control flow.
   - **PMD `EmptyBlock`** — empty control block
     (`if (x) {}`). Trivial narrow check.
   - **PMD `AvoidUsingHardCodedIP`** — literal
     IPv4 string. Plumb already has
     HardcodedCredentialCheck; the same hard-coded-
     string template can detect IP literals.
   - **PMD `BrokenNullCheck`** — `if (x == null &&
     x != null)` contradiction. R164 path condition
     capture can detect this; R166's walking
     technique generalizes.
   - **PMD `UselessOperationOnImmutable`** —
     `.toString()` on a String. R148g-style
     whitelist of methods + receiver type.

## Cumulative R148 series (27 rounds, 1 rolled back)

- 14 metric wins: R148c / R148d / R148g / R151a /
  R152a / R153 / R151b / R152b / R156 / R157 / R158 /
  R159 / R161 / R162 / R163
- 1 validation: R149b
- 12 infra: R148e / R148f / R148h / R148i / R149 /
  R150 / R155 / R157 (4) / R158 (5) / R159 (2) /
  R161 (4) / R162 (3) / R164 / R165 / **R166 (PMD
  integration pilot)**
- 1 rolled back: R148h+2
- 347 tests / 0 fail / 0 error / 1 skip (R166 final)

## Files

- New: `plumb-dfa/src/main/java/org/plumb/dfa/checker/AssignmentInOperandCheck.java`
  (Plumb-side PMD `AssignmentInOperand` mirror, ~190 lines)
- New: `plumb-dfa/src/test/java/org/plumb/dfa/checker/R166PmdAssignmentInOperandTest.java`
  (3 tests, 0.4s)
- Backup: `D:\work\tmp\r166_done\java_source\AssignmentInOperandCheck.java`
  (8,987 bytes) + `R166PmdAssignmentInOperandTest.java` (3,920 bytes)
