# The next steps: closing the Juliet corpus gaps

**Date:** 2026-11-01
**Baseline:** `juliet-coverage/reports/corpus/JULIET-REPORT.md` -- TP coverage 0.509, TN coverage
0.771, precision 0.446, 112 of 112 categories measured.
**Evidence:** `RESULTS.md`

This document is the plan that follows the 2026-11-01 corpus measurement. It says what is left,
what each item is worth, and what it would take -- in that order, because the order is the
argument.

**The rule the plan follows: a percentage is not a goal.** Every item below names the defect it
fixes. An item that cannot name one is not in this plan, and an item that only moves a number
without fixing a defect is not either.

---

## 1. The shape of what is left

| | count | share of the false positives |
| --- | ---: | ---: |
| TP coverage | 0.509 | |
| TN coverage | 0.771 | |
| precision | 0.446 | |

| the largest false-positive sources | FP | share of all FPs |
| --- | ---: | ---: |
| `dfa-boundary-integer-overflow` | 6,481 | 22.1% |
| `ast-style-info-exposure-querystring` | 6,385 | 21.8% |
| `dfa-boundary-integer-underflow` | 3,519 | 12.0% |
| `dfa-taint-integer-overflow` | 3,318 | 11.3% |
| `dfa-boundary-divide-by-zero` | 3,112 | 10.6% |

**Five rules produce 77.8% of the false positives.** The work is not spread across the engine; it
is concentrated, and that is good news: five defects, not fifty.

---

## 2. Priority 1 -- the boundary rules' precision

**The defect.** The `dfa-boundary-*` family reasons about a value's range with a Range lattice and
Z3. Its precision is 0.395 (overflow) and 0.476 (underflow) -- lower than the taint family's
0.495 and 0.600 on the same categories. The boundary rules are supposed to be the *more precise*
family, because they reason about the value rather than about its provenance. They are not.

**Why.** The corpus's fixed methods guard a range in a shape the lattice does not model. The
guards the taint family learned to recognise -- a lower bound, an array's length -- are not
something the Range lattice can consume, because a range needs an interval and
`data < array.length` is not one.

**The fix.** The array-length bound that was added to `GuardTracker` in this round is exactly the
missing input. The boundary family should consume it:

| step | what | worth |
| --- | --- | --- |
| B1 | let the Range lattice hold an interval whose upper end is an array's length | the largest single FP group |
| B2 | let a lower bound and an array-length upper bound meet in one constraint, so `0 <= i < n` is one fact rather than two | |
| B3 | make the boundary rules prefer a proven range over a taint heuristic when both would report | removes the duplicate reports |

**How it will be known.** `dfa-boundary-integer-overflow`'s precision rises above the taint
family's, and CWE-190's FP count falls by more than its TP count falls. If the TP count falls as
much, the fix was a suppression and it is not a fix.

---

## 3. Priority 2 -- `ast-style-info-exposure-querystring`

**The defect.** The worst precision in the engine: 6,385 false positives against 2,405 true
positives, precision 0.274. It is also the second-largest FP source, and it fires on CWE-89 --
so it is a large part of why SQL injection looks bad.

**Why.** The rule reports a query string as an information exposure. The corpus's *fixed* methods
also build query strings, because the fixed shape of a SQL-injection testcase is "build the query
safely" -- and building a query is still building a query.

**The fix.** The rule needs to distinguish "the query string is exposed" from "a query string was
built". The exposure is what matters, and the exposure is usually a *sink*: a response, a log, a
header.

| step | what | worth |
| --- | --- | --- |
| Q1 | report at the point the value reaches a response, a log, or a header -- not at the construction | the single largest FP group after the boundary rules |
| Q2 | suppress when the value reaches a query that is parameterised | |
| Q3 | keep the construction-time report under a different rule id, so the two are separable | makes the measurement interpretable |

**How it will be known.** precision above 0.7 with a TP count that does not fall by more than a
tenth.

---

## 4. Priority 3 -- the twelve valid zeros

**The defect.** Twelve categories have a rule, the rule ran, and it found nothing. A rule that
fires on the right shape but the wrong *variant* is indistinguishable from no rule at all, and
these twelve are worth more than the ten coverage gaps: the machinery is there and the shape is
wrong.

| CWE | category | what the rule looks for | what the category is about |
| --- | --- | --- | --- |
| 580 | clone without super | the wrong shape | a `clone()` that does not call `super.clone()` |
| 833 | deadlock | `wait`/`notify` inside a loop | a lock acquired twice |
| 568 | finalize without super | the wrong shape | a `finalize()` that does not call `super.finalize()` |
| 764 | multiple locks | the wrong shape | two locks held at once |
| 674 | uncontrolled recursion | the wrong shape | a method that calls itself |
| 609 | double-checked locking | the wrong shape | the classic broken double-check |
| 500 | public static field not final | the wrong shape | a mutable public static |
| 561 | dead code | the wrong shape | an unreachable statement |
| 491 | object hijack | the wrong shape | a class that can be replaced |
| 248 | uncaught exception | the wrong shape | a `Throwable` that escapes |
| 197 | numeric truncation | the wrong shape | a narrowing conversion |
| 378 | insecure temp-file permissions | the wrong shape | a temp file created without restrictive permissions |

**The fix.** For each, read the category's fixed method, find the shape the rule does not match,
and extend the rule. **Not a new rule per category** -- one rule id per defect class, with every
CWE that numbers the class on the same rule. Several of these will turn out to be the *same*
missing capability (a `super` call that is not there; a lock that is held twice), and one rule
that covers the capability is the right answer.

**How it will be known.** each category moves from VALID ZERO to MEASURED, with a TP count and
no new FP group.

---

## 5. Priority 4 -- CWE-191, integer underflow

**The state.** TP coverage 0.603 (3,383 of 5,612), FP 3,827, precision 0.469. Two rules, both
enabled, both producing findings.

**The defect.** The taint rule's precision is 0.600 and the boundary rule's is 0.476 -- the same
gap as the overflow family, and for the same reason. This is not a separate problem from
Priority 1; it is the same problem seen from the other side.

**The fix.** Priority 1's boundary work covers it. What is specific to CWE-191 is the *lower*
bound: an underflow is a subtraction below zero, so the lower bound is the whole question, and
the lower bound is exactly what the array-length work made expressible.

**How it will be known.** CWE-191's TP coverage rises above 0.7 with a fall in FP.

---

## 6. Priority 5 -- the AI false-positive pass

**The state.** Blocked, not skipped. MiniMax-M3's Token Plan cap was exhausted (error 2056);
`glm-4-flash` answered but about half its findings exhausted the 30-turn cap without a verdict;
`glm-4.6`, `glm-4.5` and `glm-5` were rate-limited.

**What must not happen.** The AI verdicts in `AI-PASS.md` describe an earlier build. They are
**not comparable** with the 2026-11-01 baseline, and a report that put them next to it would
credit a change that was not measured. The current `AI-PASS.md` says so; it must keep saying so
until the pass is re-run.

**What to do when quota exists.**

| step | what |
| --- | --- |
| A1 | re-run the pass on the 2026-11-01 findings, one shard at a time, `--concurrency 4` |
| A2 | three concurrent shards tripped the provider's request rate limit; the harness counts a rate-limited request separately from a model verdict, and a category that was rate-limited is reported as `rate_limited`, not as a measurement |
| A3 | keep the sample balanced by method role -- half from `bad*` files and half from `good*` files, or a false-positive reduction cannot be measured |
| A4 | report the fallback model by name when it is not MiniMax-M3 |

**How it will be known.** a paired before/after sample: the same files scanned twice with only
`--ai-dedup` differing, so the only difference between the two numbers is the AI.

---

## 7. Priority 6 -- the ten coverage gaps

**The rule for this section: a category that cannot be decided statically is not a gap to close.**
Forcing a rule onto it would raise the count and lower the meaning, and the measurement would
then be reporting a capability the engine does not have.

| CWE | category | the call | the alternative |
| --- | --- | --- | --- |
| 617 | reachable assertion | **do it** -- the assertion and its reachability are both in the IR | |
| 111 | unsafe JNI | **do it** -- the JNI call is visible | |
| 510 | trapdoor | **maybe** -- a heuristic on unreachable entry points | mark as a deliberate gap |
| 499 | sensitive data in a serializable class | **maybe** -- needs to know what is sensitive | |
| 566 | authorization bypass through an SQL primary key | **maybe** -- needs the schema | |
| 80, 81, 83 | XSS, in three shapes | **no** -- needs a real output-encoding model | one rule for the class, not three |
| 581 | object model violation | **no** -- the defect is semantic | |
| 506 | embedded malicious code | **no** -- the defect is the payload's meaning | |

**The XSS family is the case that matters.** Three numbers, one defect class. Giving each its own
superficial rule would make the support table look better and the engine no better. The right
answer is one rule that knows about output encoding, carrying `CWE-80,CWE-81,CWE-83` on the same
rule id -- which is the same "one rule id per defect class" rule the corpus already applies
everywhere else.

---

## 8. Priority 7 -- the measurement itself

Three things about the harness still need work, and they are not cosmetic.

### 8.1 The cross-file capability is still unmeasured

Juliet is a single-file corpus, so the 112-category measurement says nothing about cross-file
reachability: 0 cross-file traces is a property of the corpus, not a result. The
`testcase/01-taint-crossfile` category exercises it, and `docs/tech/JULIET-MEASUREMENT.md`
section 3 records that it does not yet measure what its name claims -- the chain's origin is a
stand-in the engine does not recognise as a source, and every method parameter is seeded as a
source, so the finding does not depend on the chain.

**The work:** a cross-file category whose chain is the *only* possible explanation for the
finding. Until that exists, the cross-file capability has no number, and a report that implies
otherwise is misleading.

### 8.2 The support table is generated, and it should stay that way

`gen_support_table.py` derives it from the measurement, using the corpus report's own
classification, so the two documents cannot disagree about what a CWE's zero means. A
hand-written table drifts from the run it describes, and the drift is invisible.

### 8.3 The engine change and the configuration change are told apart by per-rule counts

A `mode: all` config enables every rule, so it is identical between runs, and every difference in
such a category is the engine's. This is the only mechanism that separates the two causes, and
it is why the report takes the change as an argument rather than describing it in prose.

---

## 9. The order, and why

| order | item | because |
| ---: | --- | --- |
| 1 | the boundary rules' precision | five rules produce 77.8% of the false positives |
| 2 | `ast-style-info-exposure-querystring` | the worst precision in the engine, and it inflates CWE-89 |
| 3 | the twelve valid zeros | the machinery exists; the shape is wrong |
| 4 | CWE-191 | the same defect as 1, seen from the other side |
| 5 | the AI pass | blocked on quota, not on work |
| 6 | the ten coverage gaps | deliberately last: most are not gaps |
| 7 | the measurement | continuous, not a phase |

**Items 1 and 2 are worth more than everything else together.** Between them they are 43.9% of
the false positives, and both are precision defects in rules that already exist -- so both are
defects with a shape, not a research problem.

---

## 10. What is explicitly not in this plan

- **A new rule for every uncovered category.** One rule id per defect class, every CWE that
  numbers the class on the same rule.
- **A percentage target.** The plan names defects. If the percentages move because the defects
  were fixed, that is the plan working.
- **Forcing a rule onto a category that cannot be decided statically.** A deliberate gap, stated
  as one, is better than a rule that fires on the wrong shape.
- **Reusing the AI verdicts from the earlier build.** They are not comparable, and the report
  says so.
