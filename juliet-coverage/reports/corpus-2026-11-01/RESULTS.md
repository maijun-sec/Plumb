# The Juliet corpus measurement: results of 2026-11-01

**Date:** 2026-11-01
**Corpus:** `D:\work\tmp\Juliet_Java\src\testcases` -- 112 CWE directories, 46,793 testcase files
**Harness:** `juliet-coverage/scripts/measurement/`
**Reports:** `juliet-coverage/reports/corpus/JULIET-REPORT.md`, `SUPPORT-TABLE.md`
**Plan:** `NEXT-STEPS.md`

This document records what the full-corpus measurement found, and what it means for the engine's
capability. It exists because the previous reading of this corpus said the engine was far weaker
than it is, and the reason was worth writing down.

**This is the full 112-category corpus.** `docs/tech/JULIET-MEASUREMENT.md` documents the separate,
seven-category harness under `testcase/`, and the two do not measure the same thing.

---

## 1. The headline: every category is now measured

| outcome | before | after | meaning |
| --- | ---: | ---: | --- |
| MEASURED | 77 | 90 | the CWE's own rules ran and found the defect |
| VALID ZERO | 13 | 12 | the CWE's own rules ran and found nothing |
| CONFIG GAP | 0 | 0 | the engine has the rule, the config enables none of it |
| COVERAGE GAP | 22 | 10 | the engine has no rule for the CWE at all |
| NOT MEASURED | 0 | 0 | the scan did not finish, or produced no status |

**Twenty-two coverage gaps became ten, and the ten that remain are the ones where a static rule
is not the right instrument.** Nothing is unmeasured, and nothing is measured by a configuration
that disables the rule it claims to test.

| | before | after | change |
| --- | ---: | ---: | ---: |
| vulnerable (`bad*`) methods | 46,325 | 46,327 | +2 |
| covered by a finding (TP) | 22,359 | 23,590 | **+1,231** |
| missed (FN) | 23,966 | 22,737 | **−1,229** |
| fixed (`good*`) methods | 128,040 | 128,042 | +2 |
| kept clean (TN) | 96,772 | 98,763 | **+1,991** |
| reported as a false positive (FP) | 31,268 | 29,279 | **−1,989** |

| | before | after |
| --- | ---: | ---: |
| TP coverage | 0.483 | **0.509** |
| TN coverage | 0.756 | **0.771** |
| precision | 0.417 | **0.446** |

**True positives and false positives both improved.** That is the distinction between a capability
gain and a suppression trade: a rule that fires less would raise TN coverage and lower TP coverage
together. Neither did.

---

## 2. Why the earlier reading was low: four defects

### 2.1 The corpus was measured without its support library

A Juliet testcase is **not a self-contained program**. `CWE571_Expression_Always_True__static_01.java`
reads `IO.staticTrue`, and `IO` lives in `src/testcasesupport`, a sibling of `src/testcases`. The
measurement scanned only the CWE directory, so the engine was asked about a program that cannot
compile, and every constant the testcase referenced was unresolvable.

The findings the engine missed were an artifact of the measurement setup, not a defect of the
engine. `src/testcasesupport` is now passed as a `--source-root` -- not as an input, because the
support library is the program the corpus is written against, and scoring its own findings against
the CWE under test would measure the wrong thing.

### 2.2 An array local was declared with its ELEMENT type

`int[] array` was translated as `array:int`. The local looked like a scalar, so `array.length` was
not recognisable as a bound, and the guard that carried it was dropped on the floor.

This is the direct cause of the CWE-129 false positives, and it was invisible: the TAC printed
`array:int` and nothing about the printed form is wrong-looking.

**Fix:** `StatementTranslator` uses the declarator's own type, so `array:int[]`.

### 2.3 The guard tracker could not express "bounded by an array's length"

An array's length is a real bound, but it is **not a number**. `data < array.length` says nothing
about `Integer.MAX_VALUE`, so it cannot stand in for a numeric guard.

An earlier attempt modelled the length as a constant named `<array-length>`, which made it answer
numeric questions. The repo's own disabled test recorded the cost: **555 true positives lost** in
the merged-program analysis, because every array-length comparison started looking like an
overflow guard.

**Fix:** `Guard` carries an explicit kind. A `NUMERIC` guard answers "is the variable below a
number"; an `ARRAY_LENGTH` guard answers only "is the variable below this array's length", and it
names the array it was measured against -- a bound against `other.length` says nothing about
`array`.

`ArrayIndexChecker` now requires **both** bounds before it calls an index safe. Requiring only the
lower bound would suppress a real out-of-bounds access, since `data >= 0` leaves `data >= length`
open.

### 2.4 `&&` and `||` were conflated

| expression | what is proved |
| --- | --- |
| `A && B` is true | every guard that **either** arm proves |
| `A \|\| B` is true | only a guard that **both** arms prove |

The old code required **both** arms of `&&` to be recognised -- so `data >= 0 && data < array.length`
lost the lower bound when the upper arm was unrecognised, and the fixed code was reported as
unsafe. And it treated `||` as `&&`, which pushes guards that do not hold and hides real findings.

**Fix:** `&&` is the union, `||` is the intersection.

### 2.5 The cross-file cache was keyed under `<package>.`

`CompilationUnit.getPrimaryTypeName()` matches the type against the **file name**, and the parser
reads the source from a `String`, so there is no file name and the answer is always empty. Every
entry landed under `testcasesupport.` instead of `testcasesupport.IO`, a key no lookup could
produce.

**No cross-file lookup had ever worked.** The fix takes the unit's first type declaration, which
is what a fully-qualified name means.

---

## 3. What the constant resolver bought

CWE-571 -- "Expression is Always True" -- went from **1 true positive to 15 of 16**, with **zero**
false positives and precision 1.000.

The defect class is one question in different words: *is this condition's value already decided?*
A new `ConstantResolver` answers it for literals, locals, fields, and methods, across files.

| shape | example | before | after |
| --- | --- | ---: | ---: |
| a literal | `if (true)` | detected | detected |
| two decided constants | `if (FIVE == 5)` | missed | detected |
| a boolean field | `if (privateTrue)` | missed | detected |
| a boolean local | `if (b)` where `b = true` | missed | detected |
| a method returning a constant | `if (returnsTrue())` | missed | detected |
| a decided string | `if (s.equals("true"))` | missed | detected |
| two different declared types | `if (!a.getClass().equals(b.getClass()))` | missed | detected |

**What is deliberately not resolved:** a field or method of another class whose body is not in
scope, and a name that is assigned somewhere else. A name with two possible values is not decided,
and an answer about a class the analysis has not read would be a guess.

The one remaining CWE-571 miss is `if (intSecureRandom < Integer.MAX_VALUE)`. It depends on
`nextInt()`'s range, so it is not decidable statically.

---

## 4. The false positives, by the rule that produces them

Twenty rules account for most of the remaining false positives. This is where the next round of
work goes.

| rule | FP | TP | precision |
| --- | ---: | ---: | ---: |
| `dfa-boundary-integer-overflow` | 6,481 | 4,227 | 0.395 |
| `ast-style-info-exposure-querystring` | 6,385 | 2,405 | 0.274 |
| `dfa-boundary-integer-underflow` | 3,519 | 3,197 | 0.476 |
| `dfa-taint-integer-overflow` | 3,318 | 3,248 | 0.495 |
| `dfa-boundary-divide-by-zero` | 3,112 | 1,822 | 0.369 |
| `dfa-taint-resource-exhaustion` | 2,320 | 1,254 | 0.351 |
| `dfa-taint-http-response-splitting` | 1,658 | 1,520 | 0.478 |
| `dfa-taint-integer-underflow` | 1,310 | 1,963 | 0.600 |
| `ast-style-unused-assignment` | 836 | 274 | 0.247 |
| `dfa-taint-sql-injection` | 750 | 1,875 | 0.714 |

**The pattern is visible.** The `dfa-boundary-*` rules -- the Range-lattice plus Z3 family -- carry
the lowest precision of the DFA rules, and the `ast-style-info-exposure-querystring` rule is the
worst of all at 0.274. The boundary rules reason about a value's range; the corpus's fixed methods
usually guard the range in a shape the lattice does not yet model.

---

## 5. The ten coverage gaps, and why they are not all gaps

Ten categories have no rule. They are not all equally tractable.

| CWE | category | a static rule is possible? |
| --- | --- | --- |
| 80 | XSS | partly -- needs an output-encoding model |
| 81 | XSS in an error message | partly -- needs an output-encoding model |
| 83 | XSS in an attribute | partly -- needs an output-encoding model |
| 111 | unsafe JNI | yes -- the JNI call is visible in the IR |
| 499 | sensitive data in a serializable class | partly -- needs to know what is sensitive |
| 506 | embedded malicious code | no -- the defect is the payload's meaning |
| 510 | trapdoor | partly -- needs a heuristic on unreachable entry points |
| 566 | authorization bypass through an SQL primary key | partly -- needs the schema |
| 581 | object model violation | no -- the defect is semantic |
| 617 | reachable assertion | yes -- the assertion and its reachability are both visible |

**The XSS family is one defect class with three numbers.** Giving each its own superficial rule
would raise the count and lower the meaning, so the three stay uncovered until there is a real
output-encoding model.

---

## 6. The twelve valid zeros

A valid zero is a category whose rule ran and found nothing. It is a different claim from "no rule
exists", and the report keeps them apart.

| CWE | category | the rule that ran |
| --- | --- | --- |
| 197 | numeric truncation | `dfa-taint-numeric-truncation` |
| 248 | uncaught exception | `dfa-controlflow-uncaught-throw` |
| 378 | insecure temp-file permissions | `ast-style-insecure-temp-file-perms-DEDUPED` |
| 491 | object hijack | `ast-style-class-based-issue-491`, `ast-style-object-hijack` |
| 500 | public static field not final | `ast-style-mutable-static-should-be-final` |
| 561 | dead code | `ast-style-deadcode` |
| 568 | finalize without super | `ast-error-super-finalize` |
| 580 | clone without super | `ast-style-cwe580-clone-without-super` |
| 609 | double-checked locking | `ast-style-double-checked-locking` |
| 674 | uncontrolled recursion | `ast-style-unchecked-loop-condition`, `dfa-taint-uncontrolled-recursion` |
| 764 | multiple locks | `ast-security-dc-deadlock` |
| 833 | deadlock | `ast-style-wait-notify-in-loop` |

Several of these are **rule-shape** problems rather than engine limits: CWE-580's rule is named for
the defect class but looks for the wrong shape, and CWE-833's rule only fires on
`wait`/`notify` inside a loop, which is not the deadlock the category is about.

---

## 7. Cross-file reach

| | findings |
| --- | ---: |
| true positives whose trace leaves the file | 0 |
| false positives whose trace leaves the file | 0 |

**This is a property of the corpus, not a result.** Juliet's testcases are single-file, so a
cross-file trace would mean the engine followed a value out of the file the finding is reported
in. The measurement therefore says nothing about cross-file reachability -- it does not say the
capability is absent.

The `testcase/01-taint-crossfile` category is the one that exercises it, and
`docs/tech/JULIET-MEASUREMENT.md` section 3 records what it actually measures.

---

## 8. Reproducing this

```
# build
mvn -o -pl plumb-main -am -DskipTests -B install

# split the corpus between two workers, balanced by file count
python juliet-coverage/scripts/measurement/split_run.py

# scan (the harness reads the CWE list; see the README for the worker invocation)
python juliet-coverage/scripts/measurement/measure.py --scan-only \
  --out <out> --findings-dir <out>/findings --status-dir <out>/status-a <cwes>

# score
python juliet-coverage/scripts/measurement/score.py \
  --findings <out>/findings --status <out>/status-a --status <out>/status-b \
  --rule-cwes live-rule-cwes.tsv --out <out>/score

# audit the configs, then report
python juliet-coverage/scripts/measurement/whitelist_audit.py \
  --findings <out>/findings --rule-cwes live-rule-cwes.tsv --out <out>/audit
python juliet-coverage/scripts/measurement/report_juliet.py \
  --score <out>/score/score.json --audit <out>/audit/whitelist-audit.json \
  --rule-cwes live-rule-cwes.tsv --out juliet-coverage/reports/corpus
```

Two constraints the harness enforces, because both were learned the hard way:

- **The heap is a measurement parameter.** The JVM's default is a quarter of the physical memory,
  which on a 16 GB machine is 4 GB, and the three largest categories died with
  `OutOfMemoryError: Java heap space`. A scan that dies is not a measurement. `PLUMB_JULIET_HEAP`
  sets it; the largest categories need `10g` and must be run **alone**.
- **Two workers at a time, no more.** Each scan peaks around 4 GB.

---

## 9. What the measurement does not say

The honest limits of this run:

1. **It is a single-file corpus.** Cross-file reachability is unmeasured here.
2. **A category with a rule is not a category that works.** The twelve valid zeros are categories
   whose rule ran and found nothing; that is a rule-shape question, not a pass.
3. **Precision 0.446 is low.** The false positives in section 4 are the largest remaining problem,
   and they are a real number, not a sampling artifact.
4. **The AI pass was not re-run.** The provider's quota was exhausted, so the AI verdicts in
   `AI-PASS.md` describe an earlier build and are **not comparable** with this one. See
   `NEXT-STEPS.md` section 6.
