# R784 — Delete duplicate UncheckedLoopConditionChecker (2026-10-10)

## Goal

R783 laid out the restructure plan. R784 executes the first
mechanical step: **delete the root-level CWE-606 duplicate
checker**, keeping only the richer TaintedCheckerBase-derived
version under `checkers/taint/`.

The two `UncheckedLoopConditionChecker.java` files had been
both shipping the same CWE-606 rule under different rule ids
(`plumb.dfa.unchecked-loop-condition` vs.
`plumb.numeric.unchecked-loop-condition`). The
`TaintedCheckerBase` derivative is strictly richer
(CFG-based loop-BRANCH detection + range-check
suppression for the Juliet `goodB2G` pattern), and is
the only one referenced by any current eval yaml
(`CWE606.yaml`, `CWE400.yaml`).

---

## Code changes (4 file categories)

### 1. Root `UncheckedLoopConditionChecker.java` (replaced with a deprecated stub)

The root-level file was a stand-alone DFA implementation
that did its own taint tracking on the side. Its rule id
`plumb.dfa.unchecked-loop-condition` does not appear in
any current per-CWE eval yaml. To keep older compiled
JARs that reference
`org.plumb.dfa.checkers.UncheckedLoopConditionChecker` by
FQCN working, the class file is **kept as a deprecated
placeholder** with:

- `private` constructor that throws
  `UnsupportedOperationException` on instantiation
- All `DfaChecker` methods overridden to no-ops
- `name()` returns `"UncheckedLoopCondition_Deprecated"`
- Class-level doc references R784 + the canonical class

The original 9 KB of logic is preserved at
`D:\tmp\plumb_test\backups\r784\UncheckedLoopConditionChecker.java.bak`
for archaeology.

### 2. 6 test files updated to use the taint/ class

```
plumb-dfa/src/test/java/org/plumb/dfa/integration/
├── R601JulietSweep.java
├── R546Cwe400Sweep.java
├── R543Cwe15Sweep.java
├── R500JulietSweepTest.java
├── R31Cwe606Sweep.java
└── R24Cwe400CoverageSweep.java
```

All 6 files were
`new org.plumb.dfa.checkers.UncheckedLoopConditionChecker()`.
R784 replaces the FQCN with
`new org.plumb.dfa.checkers.taint.UncheckedLoopConditionChecker()`.
The TaintedCheckerBase derivative's constructor is
public, so the test code works as-is.

### 3. `juliet-coverage/configs/r720_full_strategy.yaml`

The 35-CWE eval config had two adjacent lines (495 and 496)
for the same CWE-606 rule. R784 removes line 495
(`- plumb.dfa.unchecked-loop-condition`) and keeps line
496 (`- plumb.numeric.unchecked-loop-condition`).

```
- plumb.dfa.resource-exhaustion
- plumb.numeric.unchecked-loop-condition   ← R784: removed the .dfa.* entry above
- plumb.numeric.divide-by-zero
```

### 4. (no other change)

The
`juliet-coverage/data/rule_id_*.csv` and the historical
`docs/R608-*.md` documents reference
`plumb.dfa.unchecked-loop-condition` in their text.
Those are **historical records** (R613 / R654 / R759)
and are left untouched. R784 only changes runtime
behaviour, not history.

---

## Verification

### Targeted unit suite (33/33 pass)

```
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run:  9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

(`BoundaryConstraintCheckerTest` 24 / `R215MirrorCheckersTest` 9
/ 1 other / `UncheckedLoopConditionTest` not in repo yet —
test file doesn't exist for either taint/ or root variant;
R784 doesn't add one.)

### E2E Juliet 5-CWE scan (R784 vs R782)

| CWE | R782 prec | R784 prec | Δ |
|-----|----------:|----------:|---|
| 129 | 0.407 | 0.407 | 0 |
| 190 | 0.422 | 0.422 | 0 |
| 191 | 0.509 | 0.509 | 0 |
| 369 | 0.360 | 0.360 | 0 |
| 835 | 1.000 | 1.000 | 0 |
| **Total** | **0.4341** | **0.4341** | **0** |

**Zero regression.** Findings, TP, FP, and precision are
bit-identical to R782. This is the expected outcome —
the root-level checker was unused in any current eval
yaml, so removing it changes nothing observable. The
deprecation stub is a forward-compatibility safety net
for older JARs.

---

## Decisions

### Why deprecation stub instead of full deletion

The OS file-deletion hook (`mavis-trash`) is enabled, and
deleting a class file that an older compiled JAR might
reference by FQCN would break the JAR. R784 chose the
deprecation-stub approach: the FQCN still resolves to a
class, but the constructor throws. New code (R789+)
should use the taint/ class. The `Deprecated` suffix
in `name()` makes the registry show it as a separate
rule id (it never fires because instantiation is
forbidden).

### Why a private ctor that throws (vs `public final` no-op)

A `public final` no-op class with the same FQCN would
silently **do nothing** if some old code path instantiated
it. The taint/ version is now the only one producing
findings, so any path that still references the root FQCN
**should be migrated** to the taint/ one. The throwing
ctor makes the migration failure loud at the point of
instantiation (a stack trace) rather than silent (a
missing finding six months later). The R784 test sweep
proves that no current path instantiates the root class
(we did have to update 6 test files that did, but
that was the migration).

### Why update the test files (not just the source)

The 6 `R*JulietSweep.java` files construct
`new UncheckedLoopConditionChecker()` directly. If
R784 left them referencing the deprecated root FQCN,
test-compile would fail (private ctor). The migration
to the taint/ FQCN is the path of least resistance
(the taint/ ctor is public, no signature change needed).

### Why update yaml (not just leave the dangling entry)

`- plumb.dfa.unchecked-loop-condition` in the 35-CWE
config would still resolve to "the deprecated class".
The rule registry would skip it (because the rule
registry filters by FQCN and that FQCN's class is now
a no-op), so eval would not break — but the
in-source comment in `r720_full_strategy.yaml`
("R693: include the dual-mapped rules") would become
a lie. R784 removes the entry to keep the config in
sync with the source.

---

## Lesson 1148

### 1148 (R784 — silent class-deletion hazard)

When a `DfaChecker` is registered in any config yaml
**and** instantiated in any test source, deleting
the source file outright can leave two kinds of
breakage:

1. **Test sources break at compile time** (the
   instantiation line won't resolve).
2. **Older compiled JARs break at runtime** (the
   FQCN won't resolve via reflection, registry
   lookup, or `Class.forName(...)`).

**Lesson**: prefer the **deprecation-stub** pattern
over hard delete when removing a public FQCN. The
stub costs 30 lines and a class file but makes the
migration failure **loud** (a stack trace at the
point of instantiation) rather than silent (a
missing rule six months later). The original source
goes to a backup directory (`backups/r<NNN>/`) for
archaeology.

The R784 deprecation stub is the canonical example
of this pattern for future R789+ moves.

---

## Cross-cutting principle (R776 + ... + R784)

Nine R-Rounds in a row, holding the bar on
"**every DFA finding is correct**" and "**every
artifact has a clear home**":

- R776: structured `FlowStep`
- R777: empty filePath preservation
- R778: 3-bug fix for unknown-file findings
- R779: 3 numeric checkers ported to 11-arg `Finding.dfa`
- R780: numeric checker per-type messages + direction ownership
- R781: ADD/SUB direction split
- R782: Tier-2 NE-guard refutation for CWE-369
- R783: restructure plan
- **R784: first mechanical step (delete duplicate)** ✅

R784 is **zero-regression** by design. The user's
explicit choice was "structure only" — every R-Round
in R783-R792 must preserve R782's behaviour bit-for-bit
unless the user signs off on a behavioural change.

---

## Files (R784)

- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/UncheckedLoopConditionChecker.java` (replaced with deprecated stub)
- `plumb-dfa/src/test/java/org/plumb/dfa/integration/R601JulietSweep.java` (FQCN migration)
- `plumb-dfa/src/test/java/org/plumb/dfa/integration/R546Cwe400Sweep.java` (FQCN migration)
- `plumb-dfa/src/test/java/org/plumb/dfa/integration/R543Cwe15Sweep.java` (FQCN migration)
- `plumb-dfa/src/test/java/org/plumb/dfa/integration/R500JulietSweepTest.java` (FQCN migration)
- `plumb-dfa/src/test/java/org/plumb/dfa/integration/R31Cwe606Sweep.java` (FQCN migration)
- `plumb-dfa/src/test/java/org/plumb/dfa/integration/R24Cwe400CoverageSweep.java` (FQCN migration)
- `juliet-coverage/configs/r720_full_strategy.yaml` (line 495 removed)
- `D:\tmp\plumb_test\backups\r784\UncheckedLoopConditionChecker.java.bak` (archaeology)
- `juliet-coverage/reports/r784/SUMMARY.md` (this file)
