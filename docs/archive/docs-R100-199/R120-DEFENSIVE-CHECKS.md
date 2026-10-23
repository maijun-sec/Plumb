# R120 — Defensive checks (Coverity gap fill, round 2)

## Scope

R120 fills three more entries in the Coverity checker reference that
are local, single-file analyses (no taint, no inter-proc, no type
solver required). All three are correctness rules that surface
provably-wrong code patterns:

| ID | Slug | CWE | Pattern |
|----|------|-----|---------|
| bu-0601 | `BAD_SHIFT` | CWE-758 | shift count &lt; 0 or &ge; operand bit width |
| bu-0602 | `INFINITE_LOOP` | CWE-835 | `while (true)` / `for (;;)` / `do-while (true)` with no break / return / throw |
| bu-0603 | `CONSTANT_CONDITION` | CWE-570/571 | `if (true)`, `if (false)`, `while (false)`, … |

This brings the project to **34 checker categories** total (7 original
DFA + 8 taint sinks R119a + 5 crypto R119b + 3 webapp R119c + 3
defensive R120 + 8 style).

## Files

```
plumb-dfa/src/main/java/com/hellojava/sast/dfa/
    BadShiftCheck.java
    InfiniteLoopCheck.java
    ConstantConditionCheck.java
plumb-dfa/src/test/java/com/hellojava/sast/dfa/
    BadShiftCheckTest.java        (10 tests)
    InfiniteLoopCheckTest.java    (11 tests)
    ConstantConditionCheckTest.java (6 tests)
plumb-rules/src/main/java/com/hellojava/sast/rules/
    RuleRegistry.java             (3 new bu-06xx defs)
plumb-tac/src/main/java/com/hellojava/sast/tac/translate/
    StatementTranslator.java      (set line on for(;;) JUMPs)
plumb-main/src/main/java/com/hellojava/sast/main/
    ScanConfig.java               (3 new run* flags)
    ScanRunner.java               (3 new wire-ups)
examples/r120/
    R120Demo.java                 (end-to-end demo, 11+ findings)
```

## R120a — `BadShiftCheck` (CWE-758)

Detects shifts whose count is a compile-time constant and is either
negative or &ge; the operand's bit width.

### Implementation notes

* TAC representation: `BINOP` with `binop = SHL | SHR | USHR`,
  `lhs2` is the shift count.
* The count is *not* always a direct `Const` — the parser often
  emits `NEG <const>` first (e.g. `1 << -1` becomes
  `t_t0 = NEG 1; t_t1 = 1 SHL t_t0`). The check therefore
  implements a small constant-folding helper that follows:
  - direct `Const(INT | LONG)`
  - `MOVE` of a const
  - `UNARYOP NEG` of a const (chase further via `MOVE`)
* Bit width is read from the source operand's type (Java promotes
  the value being shifted to `int` / `long`). When the TAC's
  destination type is `<unknown>`, the source type is used as the
  source of truth — otherwise the check would mis-report
  `1L << 63` as a long-overflow.
* Severity: `MEDIUM` (the pattern is usually a logic bug; the
  runtime cost is masking into a small value, not a crash).
* Non-constant shift counts (variables, parameters) are out of
  scope: they need range analysis, deferred to a future round.

### Tests cover

* `1 << -1`, `1 << 32`, `1 << 100`, `1L << 64` — all detected
* `1 << 5`, `1 << 31`, `1L << 63` — safe boundary, no finding
* `1 << n` with variable — no finding
* `>>` and `>>>` with too-large counts — detected

## R120b — `InfiniteLoopCheck` (CWE-835)

Detects three loop shapes whose condition is provably constant and
whose body has no visible exit.

### TAC shapes

| Source | TAC shape |
|--------|-----------|
| `while (true) { body }` | `BRANCH(true) [true → BODY] [false → END] … body … JUMP → HEADER` |
| `do { body } while (true)` | `BODY … BRANCH(true) [true → BODY] [false → END]` |
| `for (;;) { body }` | `HEADER: JUMP → BODY … body … JUMP → HEADER` |

For each detected loop:

1. **Find the body region.** The body is the set of instructions
   between the body's entry label and the loop's end label (or the
   BRANCH's index, in the do-while case).
2. **Require a back-edge.** For `while`, look for a `JUMP` inside
   the body whose target label's position is at or before the
   BRANCH. For `do-while`, the BRANCH itself is the back-edge (skip
   the check). For `for(;;)`, the explicit back-jump to the header.
3. **Walk the body for exits.** An exit is:
   * `RETURN` or `THROW` (function exit)
   * a `JUMP` to the loop's end label (the `break` idiom)
   * a `JUMP` to any label *after* the body region (labelled break
     / goto out)
   * a `BRANCH` whose any target is outside the body region
4. A `JUMP` to a label *before* the body region is a back-edge to
   the loop header and is not an exit. This is the key invariant
   that lets nested loops work without recursing.
5. The loop's own terminator (e.g. the do-while BRANCH) is skipped
   so the natural "fall-through to end label" isn't treated as a
   break.

### Test coverage

* `while (true) { ... }` — detected
* `for (;;) { ... }` — detected
* `do { ... } while (true)` — detected
* `while (true) { if (cond) break; }` — not flagged
* `while (true) { return 1; }` — not flagged
* `while (true) { throw ...; }` — not flagged
* nested: outer fires, inner doesn't (inner has break)

### Translator fix

`StatementTranslator.translateFor` was emitting two `JUMP`
instructions without `.line(...)` — the unconditional jump from the
cond region to the body, and the back-jump to the cond region. Both
now carry the `for` statement's line, so `for(;;)` findings report
the correct source line.

## R120c — `ConstantConditionCheck` (CWE-570/571)

A short check: any `BRANCH` whose condition is `Const(BOOL, _)` is
flagged. The check is intentionally local — only literal
`true` / `false` predicates. Variables that turn out to be always
true at runtime are out of scope (needs constant-propagation).

### Tests cover

* `if (true)`, `if (false)`, `while (false)` — detected
* `if (x > 0)` (parameter) — not flagged
* `while (true)` — also fires (overlaps with INFINITE_LOOP; that's
  expected, both are valid concerns on the same line)

## Test counts

| Module | R119c | R120 | delta |
|--------|------:|-----:|------:|
| parser | 3 | 3 | 0 |
| tac | 16 | 16 | 0 |
| rules | 2 | 2 | 0 |
| dfa | 68 + 1 skip | 95 + 1 skip | +27 - some removed |
| style | 6 | 6 | 0 |
| reporter | 2 | 2 | 0 |
| main | 3 | 3 | 0 |
| **total** | **100 + 1** | **127 + 1** | **+27** |

The 27 new dfa tests are 10 (BadShift) + 11 (InfiniteLoop) + 6
(ConstantCondition). The other dfa test changes are debug-only files
that were created during development and moved out of the source
tree (not counted).

## End-to-end (`examples/r120/R120Demo.java`)

The demo is a single file with three sections, one per new checker.
Each labelled method fires exactly one finding at the documented
line; non-firing methods are also included to confirm no false
positives. Running the fat jar against this file produces:

```
3  × BAD_SHIFT          (negative int / int-width / long-width)
3  × INFINITE_LOOP      (while / for / do-while)
5  × CONSTANT_CONDITION (if-true / if-false / while-false / 2 others)
1  × INSECURE_RANDOM    (new Random() helper)
```

`examples/complex/BadCode.java` is stable at 16 findings, no
regressions.

## What R120 doesn't do (deferred to R121+)

* **INCOMPATIBLE_CAST** (CWE-704) — needs a type solver.
* **REVERSE_INULL** — needs null-state analysis across the function.
* **OVERRUN** (string/array index out of bounds, CWE-129) — needs
  bounds tracking; the existing `ArrayBoundsCheck` covers the
  simplest local case.
* **Race conditions / atomicity** (CWE-362, CWE-573) — needs
  inter-thread analysis, an architectural change.
* **Swapped arguments** (CWE-683) — needs API-shape heuristics, not
  a single-file TAC walk.
* **Trust boundary / sensitive data leak** (Coverity
  `TRUST_BOUNDARY_VIOLATION`, `SENSITIVE_DATA_LEAK`) — needs the
  `TypeTable` for type-aware call resolution, then more taint
  sources / sinks.

These all stay in the R121+ queue.
