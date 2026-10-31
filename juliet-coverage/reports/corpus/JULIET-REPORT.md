# Juliet measurement

*Engine build `b1fde78`. The per-CWE configs are derived from the engine's own rule table. 112 CWE categories, 46,793 testcase files, every scan finished.*

The corpus is the real Juliet_Java testcase tree: 112 CWE directories, 46793 testcase files.

**The unit is the method.** A Juliet file declares the vulnerable method (`bad*`) and the fixed one (`good*`). A finding inside a `bad*` method is a true positive; inside a `good*` method it is a false positive; a `good*` method with no finding is a true negative; a `bad*` method with no finding is a false negative.

**Only a rule that is about the CWE under test is scored.** A finding from a rule labelled with another CWE is counted separately, because counting it as a false positive would measure the breadth of the per-CWE whitelist rather than the engine's precision.

## What each CWE's number means

| outcome | count | meaning |
| --- | ---: | --- |
| MEASURED | 77 | the CWE's own rules ran and found the defect |
| VALID ZERO | 13 | the CWE's own rules ran and found nothing |
| CONFIG GAP | 0 | the engine has rules for the CWE, the config enables none of them, so the coverage number says nothing about the rule |
| COVERAGE GAP | 22 | the engine has no rule for the CWE at all |
| NOT MEASURED | 0 | the scan did not finish, or produced no status |

## Per CWE

| CWE | outcome | mode | own rules on | bad | good | TP | FN | FP | TN | TP cov | TN cov | prec |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CWE-15 | MEASURED | whitelist | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-23 | COVERAGE GAP | whitelist | 0/0 | 732 | 1380 | 0 | 732 | 0 | 1380 | 0.000 | 1.000 | n/a |
| CWE-36 | COVERAGE GAP | whitelist | 0/0 | 732 | 1380 | 0 | 732 | 0 | 1380 | 0.000 | 1.000 | n/a |
| CWE-78 | MEASURED | all | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-80 | COVERAGE GAP | all | 0/0 | 1098 | 2070 | 0 | 1098 | 0 | 2070 | 0.000 | 1.000 | n/a |
| CWE-81 | COVERAGE GAP | all | 0/0 | 549 | 1035 | 0 | 549 | 0 | 1035 | 0.000 | 1.000 | n/a |
| CWE-83 | COVERAGE GAP | all | 0/0 | 549 | 1035 | 0 | 549 | 0 | 1035 | 0.000 | 1.000 | n/a |
| CWE-89 | MEASURED | whitelist | 3/3 | 3660 | 11340 | 2255 | 1405 | 6105 | 5235 | 0.616 | 0.462 | 0.375 |
| CWE-90 | MEASURED | whitelist | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-111 | COVERAGE GAP | whitelist | 0/0 | 1 | 0 | 0 | 1 | 0 | 0 | 0.000 | n/a | n/a |
| CWE-113 | MEASURED | all | 1/1 | 2196 | 6804 | 1123 | 1073 | 1477 | 5327 | 0.511 | 0.783 | 0.478 |
| CWE-114 | MEASURED | whitelist | 2/2 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-129 | MEASURED | whitelist | 6/6 | 4392 | 13608 | 1558 | 2834 | 3280 | 10328 | 0.355 | 0.759 | 0.421 |
| CWE-134 | MEASURED | whitelist | 1/1 | 1098 | 3402 | 558 | 540 | 216 | 3186 | 0.508 | 0.937 | 0.721 |
| CWE-190 | MEASURED | whitelist | 5/5 | 7015 | 21735 | 4159 | 2856 | 6512 | 15223 | 0.593 | 0.700 | 0.425 |
| CWE-191 | MEASURED | whitelist | 2/2 | 5612 | 17388 | 3383 | 2229 | 3827 | 13561 | 0.603 | 0.780 | 0.517 |
| CWE-193 | MEASURED | whitelist | 2/2 | 51 | 141 | 51 | 0 | 0 | 141 | 1.000 | 1.000 | 1.000 |
| CWE-197 | VALID ZERO | whitelist | 1/1 | 2013 | 3795 | 0 | 2013 | 0 | 3795 | 0.000 | 1.000 | n/a |
| CWE-209 | MEASURED | all | 5/5 | 34 | 94 | 34 | 0 | 0 | 94 | 1.000 | 1.000 | 1.000 |
| CWE-226 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 30 | 17 | 1.000 | 0.362 | 0.367 |
| CWE-248 | VALID ZERO | whitelist | 1/1 | 1 | 2 | 0 | 1 | 1 | 1 | 0.000 | 0.500 | 0.000 |
| CWE-252 | MEASURED | whitelist | 5/5 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-253 | MEASURED | whitelist | 3/3 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-256 | MEASURED | whitelist | 1/1 | 61 | 189 | 31 | 30 | 88 | 101 | 0.508 | 0.534 | 0.262 |
| CWE-259 | MEASURED | whitelist | 4/4 | 183 | 345 | 108 | 75 | 153 | 192 | 0.590 | 0.557 | 0.414 |
| CWE-315 | COVERAGE GAP | whitelist | 0/0 | 61 | 189 | 0 | 61 | 0 | 189 | 0.000 | 1.000 | n/a |
| CWE-319 | MEASURED | all | 4/4 | 610 | 1890 | 424 | 186 | 688 | 1202 | 0.695 | 0.636 | 0.380 |
| CWE-321 | COVERAGE GAP | whitelist | 0/0 | 61 | 115 | 0 | 61 | 0 | 115 | 0.000 | 1.000 | n/a |
| CWE-325 | MEASURED | whitelist | 1/1 | 34 | 94 | 17 | 17 | 0 | 94 | 0.500 | 1.000 | 1.000 |
| CWE-327 | MEASURED | whitelist | 2/2 | 34 | 94 | 34 | 0 | 0 | 94 | 1.000 | 1.000 | 1.000 |
| CWE-328 | MEASURED | whitelist | 1/1 | 51 | 141 | 51 | 0 | 0 | 141 | 1.000 | 1.000 | 1.000 |
| CWE-329 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-336 | MEASURED | whitelist | 2/2 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-338 | MEASURED | whitelist | 1/1 | 34 | 94 | 34 | 0 | 0 | 94 | 1.000 | 1.000 | 1.000 |
| CWE-369 | MEASURED | whitelist | 2/2 | 3050 | 9450 | 1800 | 1250 | 3068 | 6382 | 0.590 | 0.675 | 0.369 |
| CWE-378 | VALID ZERO | whitelist | 1/1 | 17 | 47 | 0 | 17 | 0 | 47 | 0.000 | 1.000 | n/a |
| CWE-379 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 30 | 17 | 1.000 | 0.362 | 0.367 |
| CWE-382 | MEASURED | whitelist | 1/1 | 34 | 94 | 34 | 0 | 0 | 94 | 1.000 | 1.000 | 1.000 |
| CWE-383 | MEASURED | whitelist | 1/1 | 16 | 0 | 16 | 0 | 0 | 0 | 1.000 | n/a | 1.000 |
| CWE-390 | MEASURED | whitelist | 5/5 | 34 | 94 | 17 | 17 | 0 | 94 | 0.500 | 1.000 | 1.000 |
| CWE-395 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-396 | MEASURED | whitelist | 5/5 | 34 | 94 | 34 | 0 | 0 | 94 | 1.000 | 1.000 | 1.000 |
| CWE-397 | MEASURED | whitelist | 1/1 | 2 | 6 | 2 | 0 | 0 | 6 | 1.000 | 1.000 | 1.000 |
| CWE-398 | MEASURED | whitelist | 5/5 | 137 | 378 | 136 | 1 | 0 | 378 | 0.993 | 1.000 | 1.000 |
| CWE-400 | MEASURED | whitelist | 3/3 | 2396 | 7418 | 1215 | 1181 | 2281 | 5137 | 0.507 | 0.693 | 0.351 |
| CWE-404 | MEASURED | whitelist | 1/1 | 5 | 10 | 2 | 3 | 0 | 10 | 0.400 | 1.000 | 1.000 |
| CWE-459 | MEASURED | whitelist | 2/2 | 34 | 94 | 18 | 16 | 30 | 64 | 0.529 | 0.681 | 0.367 |
| CWE-470 | MEASURED | whitelist | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-476 | MEASURED | whitelist | 9/9 | 293 | 892 | 163 | 130 | 452 | 440 | 0.556 | 0.493 | 0.336 |
| CWE-477 | MEASURED | whitelist | 3/3 | 68 | 188 | 68 | 0 | 0 | 188 | 1.000 | 1.000 | 1.000 |
| CWE-478 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-481 | MEASURED | whitelist | 2/2 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-482 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 30 | 17 | 1.000 | 0.362 | 0.530 |
| CWE-483 | MEASURED | whitelist | 1/1 | 19 | 51 | 18 | 1 | 0 | 51 | 0.947 | 1.000 | 1.000 |
| CWE-484 | MEASURED | whitelist | 2/2 | 17 | 47 | 17 | 0 | 30 | 17 | 1.000 | 0.362 | 0.366 |
| CWE-486 | COVERAGE GAP | whitelist | 0/0 | 17 | 47 | 0 | 17 | 0 | 47 | 0.000 | 1.000 | n/a |
| CWE-491 | VALID ZERO | whitelist | 2/2 | 1 | 2 | 0 | 1 | 0 | 2 | 0.000 | 1.000 | n/a |
| CWE-499 | COVERAGE GAP | whitelist | 0/0 | 0 | 0 | 0 | 0 | 0 | 0 | n/a | n/a | n/a |
| CWE-500 | VALID ZERO | whitelist | 1/1 | 1 | 2 | 0 | 1 | 0 | 2 | 0.000 | 1.000 | n/a |
| CWE-506 | COVERAGE GAP | whitelist | 0/0 | 116 | 143 | 0 | 116 | 0 | 143 | 0.000 | 1.000 | n/a |
| CWE-510 | COVERAGE GAP | whitelist | 0/0 | 66 | 94 | 0 | 66 | 0 | 94 | 0.000 | 1.000 | n/a |
| CWE-511 | MEASURED | whitelist | 1/1 | 51 | 141 | 51 | 0 | 0 | 141 | 1.000 | 1.000 | 1.000 |
| CWE-523 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-526 | MEASURED | whitelist | 3/3 | 34 | 94 | 34 | 0 | 0 | 94 | 1.000 | 1.000 | 1.000 |
| CWE-533 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-534 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-535 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-539 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-546 | MEASURED | whitelist | 1/1 | 85 | 235 | 85 | 0 | 0 | 235 | 1.000 | 1.000 | 1.000 |
| CWE-549 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-561 | VALID ZERO | whitelist | 1/1 | 1 | 2 | 0 | 1 | 0 | 2 | 0.000 | 1.000 | n/a |
| CWE-563 | MEASURED | whitelist | 7/7 | 218 | 716 | 214 | 4 | 492 | 224 | 0.982 | 0.313 | 0.332 |
| CWE-566 | COVERAGE GAP | whitelist | 0/0 | 61 | 115 | 0 | 61 | 0 | 115 | 0.000 | 1.000 | n/a |
| CWE-568 | VALID ZERO | whitelist | 1/1 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-570 | MEASURED | whitelist | 3/3 | 16 | 32 | 16 | 0 | 1 | 31 | 1.000 | 0.969 | 0.950 |
| CWE-571 | MEASURED | whitelist | 2/2 | 16 | 32 | 1 | 15 | 0 | 32 | 0.062 | 1.000 | 1.000 |
| CWE-572 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-579 | MEASURED | whitelist | 2/2 | 1 | 2 | 1 | 0 | 0 | 2 | 1.000 | 1.000 | 1.000 |
| CWE-580 | VALID ZERO | whitelist | 1/1 | 1 | 2 | 0 | 1 | 0 | 2 | 0.000 | 1.000 | n/a |
| CWE-581 | COVERAGE GAP | whitelist | 0/0 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-582 | COVERAGE GAP | whitelist | 0/0 | 1 | 2 | 0 | 1 | 0 | 2 | 0.000 | 1.000 | n/a |
| CWE-584 | MEASURED | whitelist | 3/3 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-585 | VALID ZERO | whitelist | 1/1 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-586 | MEASURED | whitelist | 4/4 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-597 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 30 | 17 | 1.000 | 0.362 | 0.416 |
| CWE-598 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-600 | MEASURED | whitelist | 1/1 | 1 | 2 | 1 | 0 | 0 | 2 | 1.000 | 1.000 | 1.000 |
| CWE-601 | MEASURED | whitelist | 2/2 | 549 | 1035 | 276 | 273 | 126 | 909 | 0.503 | 0.878 | 0.687 |
| CWE-605 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 1 | 46 | 1.000 | 0.979 | 0.900 |
| CWE-606 | MEASURED | whitelist | 1/1 | 732 | 2268 | 420 | 312 | 588 | 1680 | 0.574 | 0.741 | 0.412 |
| CWE-607 | COVERAGE GAP | whitelist | 0/0 | 1 | 1 | 0 | 1 | 0 | 1 | 0.000 | 1.000 | n/a |
| CWE-609 | VALID ZERO | whitelist | 2/2 | 2 | 12 | 0 | 2 | 0 | 12 | 0.000 | 1.000 | n/a |
| CWE-613 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-614 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-615 | MEASURED | whitelist | 2/2 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-617 | COVERAGE GAP | whitelist | 0/0 | 34 | 94 | 0 | 34 | 0 | 94 | 0.000 | 1.000 | n/a |
| CWE-643 | MEASURED | whitelist | 1/1 | 732 | 2268 | 375 | 357 | 150 | 2118 | 0.512 | 0.934 | 0.714 |
| CWE-667 | COVERAGE GAP | whitelist | 0/0 | 1 | 2 | 0 | 1 | 0 | 2 | 0.000 | 1.000 | n/a |
| CWE-674 | VALID ZERO | whitelist | 2/2 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-681 | MEASURED | whitelist | 1/1 | 51 | 141 | 51 | 0 | 90 | 51 | 1.000 | 0.362 | 0.367 |
| CWE-690 | MEASURED | whitelist | 2/2 | 488 | 1512 | 241 | 247 | 342 | 1170 | 0.494 | 0.774 | 0.488 |
| CWE-698 | MEASURED | whitelist | 1/1 | 17 | 47 | 16 | 1 | 1 | 46 | 0.941 | 0.979 | 0.941 |
| CWE-759 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-760 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-764 | VALID ZERO | whitelist | 1/1 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-765 | COVERAGE GAP | whitelist | 0/0 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-772 | COVERAGE GAP | whitelist | 0/0 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-775 | COVERAGE GAP | whitelist | 0/0 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-789 | MEASURED | all | 2/2 | 2579 | 4877 | 1271 | 1308 | 597 | 4280 | 0.493 | 0.878 | 0.667 |
| CWE-832 | COVERAGE GAP | whitelist | 0/0 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-833 | VALID ZERO | whitelist | 1/1 | 6 | 12 | 0 | 6 | 0 | 12 | 0.000 | 1.000 | n/a |
| CWE-835 | MEASURED | whitelist | 1/1 | 6 | 13 | 6 | 0 | 0 | 13 | 1.000 | 1.000 | 1.000 |

## Totals over the measured CWEs

| | methods |
| --- | ---: |
| vulnerable (`bad*`) | 46325 |
| covered by a finding | 22359 |
| missed | 23966 |
| fixed (`good*`) | 128040 |
| kept clean | 96772 |
| reported as a false positive | 31268 |

TP coverage over the vulnerable methods: **0.483** (22359 of 46325)

TN coverage over the fixed methods: **0.756** (96772 of 128040)

## AST rules and DFA rules

The two engines have different reachability, so their coverage is not interchangeable and is reported apart.

| engine | true positives | false positives |
| --- | ---: | ---: |
| ast | 5570 | 9489 |
| dfa | 27401 | 29971 |

## Cross-file reach

A finding whose trace names a step in another file crossed a translation unit, so the global call graph reached it. Every other finding was decided inside one file.

| | findings |
| --- | ---: |
| true positives whose trace leaves the file | 0 |
| false positives whose trace leaves the file | 0 |

Juliet's testcases are single-file, so a cross-file trace here means the engine followed the value out of the file the finding is reported in -- it is not the corpus testing cross-file coverage.

## False positives by cause

| rule | engine | false-positive findings | CWEs affected |
| --- | --- | ---: | ---: |
| `dfa-boundary-integer-overflow` | dfa | 6481 | 1 |
| `ast-style-info-exposure-querystring` | ast | 6385 | 1 |
| `dfa-boundary-integer-underflow` | dfa | 3519 | 1 |
| `dfa-taint-integer-overflow` | dfa | 3318 | 1 |
| `dfa-boundary-divide-by-zero` | dfa | 3112 | 1 |
| `dfa-taint-unchecked-array-index` | dfa | 3086 | 1 |
| `dfa-taint-resource-exhaustion` | dfa | 2320 | 1 |
| `dfa-taint-http-response-splitting` | dfa | 1658 | 1 |
| `dfa-taint-integer-underflow` | dfa | 1310 | 1 |
| `ast-style-unused-assignment` | ast | 836 | 1 |
| `dfa-taint-sql-injection` | dfa | 750 | 1 |
| `dfa-boundary-array-out-of-bounds` | dfa | 748 | 1 |
| `ast-style-icast-int-mult-long` | ast | 612 | 1 |
| `dfa-taint-unchecked-loop-condition` | dfa | 600 | 1 |
| `dfa-taint-uncontrolled-mem-alloc` | dfa | 516 | 1 |
| `dfa-taint-cleartext-network-credential-sink` | dfa | 513 | 1 |
| `dfa-taint-null-dereference` | dfa | 507 | 2 |
| `ast-bug-equals-avoid-null` | ast | 301 | 1 |
| `dfa-taint-format-string` | dfa | 216 | 1 |
| `dfa-taint-null-deref-from-return` | dfa | 215 | 1 |
| `ast-security-unused-variable-assignment` | ast | 196 | 1 |
| `ast-security-unbounded-mem-alloc` | ast | 170 | 1 |
| `ast-security-insecure-communication` | ast | 159 | 1 |
| `ast-security-hardcoded-password-sink-ast` | ast | 153 | 1 |
| `ast-security-cleartext-socket-usage` | ast | 153 | 1 |

## Findings about another CWE

These fired in the corpus but are not about the CWE under test, so they are neither a hit nor a miss here. Two very different sources are separated, because lumping them together would make a deliberate no-whitelist measurement look like engine noise.

| source | findings |
| --- | ---: |
| a whitelist config that enables a rule about another CWE | 57135 |
| a `mode: all` config, which enables every rule by design | 623738 |

The second row is not a defect: a `mode: all` config is a deliberate measurement of the whole catalogue, and every rule that fires is expected. The first row is a measurement of how broad the per-CWE whitelists are.

| rule | findings in a whitelist config |
| --- | ---: |
| `dfa-taint-null-dereference` | 29624 |
| `dfa-boundary-integer-overflow` | 12934 |
| `ast-security-unchecked-return` | 6216 |
| `dfa-taint-leak` | 4227 |
| `ast-security-insecure-communication` | 2239 |
| `dfa-taint-resource-exhaustion` | 1706 |
| `ast-error-throw-generic-exception` | 128 |
| `ast-security-insecure-crypto` | 51 |
| `ast-style-compare-instead-of-assigning` | 3 |
| `dfa-taint-assignment-in-conditional` | 3 |
| `dfa-taint-unchecked-array-index` | 2 |
| `ast-style-clone-without-super` | 1 |

## The CWEs with no measurement to report

**A CWE the engine has no rule for** -- a coverage gap, not a recall of zero. The rule would have to be written.

- CWE-23 (732 vulnerable methods, 1380 fixed)
- CWE-36 (732 vulnerable methods, 1380 fixed)
- CWE-80 (1098 vulnerable methods, 2070 fixed)
- CWE-81 (549 vulnerable methods, 1035 fixed)
- CWE-83 (549 vulnerable methods, 1035 fixed)
- CWE-111 (1 vulnerable methods, 0 fixed)
- CWE-315 (61 vulnerable methods, 189 fixed)
- CWE-321 (61 vulnerable methods, 115 fixed)
- CWE-486 (17 vulnerable methods, 47 fixed)
- CWE-499 (0 vulnerable methods, 0 fixed)
- CWE-506 (116 vulnerable methods, 143 fixed)
- CWE-510 (66 vulnerable methods, 94 fixed)
- CWE-566 (61 vulnerable methods, 115 fixed)
- CWE-581 (2 vulnerable methods, 4 fixed)
- CWE-582 (1 vulnerable methods, 2 fixed)
- CWE-607 (1 vulnerable methods, 1 fixed)
- CWE-617 (34 vulnerable methods, 94 fixed)
- CWE-667 (1 vulnerable methods, 2 fixed)
- CWE-765 (2 vulnerable methods, 4 fixed)
- CWE-772 (2 vulnerable methods, 4 fixed)
- CWE-775 (2 vulnerable methods, 4 fixed)
- CWE-832 (2 vulnerable methods, 4 fixed)

**A CWE whose own rules ran and found nothing** -- a valid zero. The rule is enabled, it ran, and it did not find the defect in these testcases.

- CWE-197: 2013 vulnerable methods, 3795 fixed, rule(s) `dfa-taint-numeric-truncation`
- CWE-248: 1 vulnerable methods, 2 fixed, rule(s) `dfa-controlflow-uncaught-throw`
- CWE-378: 17 vulnerable methods, 47 fixed, rule(s) `ast-style-insecure-temp-file-perms-DEDUPED`
- CWE-491: 1 vulnerable methods, 2 fixed, rule(s) `ast-style-class-based-issue-491`, `ast-style-object-hijack`
- CWE-500: 1 vulnerable methods, 2 fixed, rule(s) `ast-style-mutable-static-should-be-final`
- CWE-561: 1 vulnerable methods, 2 fixed, rule(s) `ast-style-deadcode`
- CWE-568: 2 vulnerable methods, 4 fixed, rule(s) `ast-error-super-finalize`
- CWE-580: 1 vulnerable methods, 2 fixed, rule(s) `ast-style-cwe580-clone-without-super`
- CWE-585: 2 vulnerable methods, 4 fixed, rule(s) `ast-style-empty-sync-block`
- CWE-609: 2 vulnerable methods, 12 fixed, rule(s) `ast-style-double-checked-locking`, `ast-style-missing-serial-version-uid`
- CWE-674: 2 vulnerable methods, 4 fixed, rule(s) `ast-style-unchecked-loop-condition`, `dfa-taint-uncontrolled-recursion`
- CWE-764: 2 vulnerable methods, 4 fixed, rule(s) `ast-security-dc-deadlock`
- CWE-833: 6 vulnerable methods, 12 fixed, rule(s) `ast-style-wait-notify-in-loop`

## Configuration defects

**A whitelist entry that selects no rule the engine emits** -- the entry is dead, and the CWE was measured without the rule it names.

| CWE | dead entry |
| --- | --- |
| CWE-193 | `OffByOne` |
| CWE-248 | `UncaughtExceptionPropagator` |
| CWE-248 | `UncaughtExceptionCheck` |
| CWE-390 | `UncaughtExceptionPropagator` |
| CWE-396 | `UncaughtExceptionPropagator` |
| CWE-397 | `UncaughtExceptionPropagator` |
| CWE-491 | `ObjectHijackCheck` |
| CWE-500 | `PublicStaticMutableFieldCheck` |
| CWE-580 | `SuperCloneCheck` |
| CWE-584 | `UncaughtExceptionPropagator` |
| CWE-585 | `EmptySyncBlockCheck` |
| CWE-690 | `EqualsCalledOnNull` |

**A CWE whose own rules the config does not enable at all** -- the coverage number for these CWEs is uninterpretable, not zero.

none

**A CWE whose own rules are only partly enabled.**

none

## The engine change and the configuration change, told apart

Two things changed between the previous measurement and this one: the engine's parameter-origin fixpoint, and the per-CWE configs. A per-rule finding count tells them apart, because a rule that was already enabled keeps its count if the engine change was semantically neutral.

| CWE | config | rule | before | after |
| --- | --- | --- | ---: | ---: |
| CWE-15 | changed | `dfa-taint-external-config-setting` | 0 | 528 |
| CWE-80 | unchanged | `ast-naming-final-local-variable` | 974 | 989 |
| CWE-80 | unchanged | `ast-performance-dup-string` | 582 | 590 |
| CWE-80 | unchanged | `ast-security-null-deref-from-return-ast` | 522 | 511 |
| CWE-80 | unchanged | `ast-security-unused-variable-assignment` | 2619 | 2648 |
| CWE-80 | unchanged | `ast-style-avoid-print-stack-trace` | 2 | 1 |
| CWE-80 | unchanged | `ast-style-emptylineseparatorcheck-cs` | 4 | 2 |
| CWE-80 | unchanged | `ast-style-expression-always-true` | 286 | 274 |
| CWE-80 | unchanged | `ast-style-string-eq-operator` | 3079 | 3084 |
| CWE-90 | changed | `dfa-taint-ldap-injection` | 0 | 528 |
| CWE-113 | unchanged | `ast-naming-final-local-variable` | 4507 | 4729 |
| CWE-113 | unchanged | `ast-performance-dup-string` | 4396 | 4528 |
| CWE-113 | unchanged | `ast-quality-always-false` | 71 | 72 |
| CWE-113 | unchanged | `ast-quality-always-true` | 502 | 504 |
| CWE-113 | unchanged | `ast-quality-env-var` | 268 | 269 |
| CWE-113 | unchanged | `ast-security-null-deref-from-return-ast` | 1861 | 1844 |
| CWE-113 | unchanged | `ast-security-unused-variable-assignment` | 7224 | 7197 |
| CWE-113 | unchanged | `ast-style-avoid-print-stack-trace` | 0 | 2 |
| CWE-113 | unchanged | `ast-style-emptylineseparatorcheck-cs` | 4 | 6 |
| CWE-113 | unchanged | `ast-style-expression-always-true` | 2341 | 2289 |
| CWE-113 | unchanged | `ast-style-literal-boolean-condition` | 612 | 593 |
| CWE-113 | unchanged | `ast-style-string-eq-operator` | 11317 | 11310 |
| CWE-114 | changed | `ast-security-process-control` | 0 | 17 |
| CWE-190 | changed | `ast-style-icast-int-mult-long` | 0 | 852 |
| CWE-190 | changed | `dfa-taint-integer-overflow` | 0 | 6842 |
| CWE-191 | changed | `dfa-taint-integer-underflow` | 0 | 3273 |
| CWE-193 | changed | `dfa-controlflow-off-by-one` | 0 | 54 |
| CWE-193 | changed | `dfa-style-off-by-one-loop` | 0 | 51 |
| CWE-226 | changed | `ast-style-uncleared-sensitive-info` | 0 | 49 |
| CWE-248 | changed | `dfa-controlflow-uncaught-throw` | 0 | 2 |
| CWE-256 | changed | `dfa-taint-plaintext-password-sink` | 0 | 125 |
| CWE-259 | changed | `ast-security-hardcoded-password-sink-ast` | 0 | 267 |
| CWE-325 | changed | `ast-style-missing-crypto-step` | 0 | 17 |
| CWE-327 | changed | `ast-style-weak-crypto` | 0 | 102 |
| CWE-328 | changed | `ast-security-weak-hash` | 0 | 51 |
| CWE-329 | changed | `ast-style-random-iv-cbc` | 0 | 18 |
| CWE-336 | changed | `ast-style-seeded-random` | 0 | 17 |
| CWE-338 | changed | `ast-security-weak-prng` | 0 | 34 |
| CWE-379 | changed | `dfa-security-insecure-temp-file` | 0 | 49 |
| CWE-382 | changed | `ast-style-system-exit-usage` | 0 | 35 |
| CWE-383 | changed | `ast-style-thread-in-servlet` | 0 | 64 |
| CWE-390 | changed | `ast-error-catch-log` | 0 | 17 |
| CWE-390 | changed | `ast-error-throwable-not-thrown` | 0 | 10 |
| CWE-395 | changed | `ast-style-catch-nullpointer-exception` | 0 | 17 |
| CWE-396 | changed | `ast-error-catch-of-throwable` | 0 | 26 |
| CWE-396 | changed | `ast-error-catch-throwable` | 0 | 26 |
| CWE-396 | changed | `ast-style-overly-broad-catch` | 0 | 44 |
| CWE-398 | changed | `ast-style-empty-statement-block` | 0 | 103 |
| CWE-398 | changed | `ast-style-no-effect-statement` | 0 | 17 |
| CWE-398 | changed | `ast-style-poor-code-quality` | 0 | 112 |
| CWE-398 | changed | `dfa-style-empty-error-block` | 0 | 17 |
| CWE-398 | changed | `dfa-style-self-assign` | 0 | 17 |
| CWE-459 | changed | `ast-style-incomplete-cleanup` | 0 | 49 |
| CWE-470 | changed | `dfa-taint-unsafe-reflection` | 0 | 528 |
| CWE-476 | changed | `ast-bug-equals-avoid-null` | 0 | 378 |
| CWE-476 | changed | `ast-style-literals-first` | 0 | 2 |
| CWE-476 | changed | `dfa-taint-null-deref-from-return` | 0 | 393 |
| CWE-478 | changed | `dfa-style-missing-default-case` | 0 | 17 |
| CWE-481 | changed | `ast-style-assignment-in-condition` | 0 | 17 |
| CWE-482 | changed | `ast-style-compare-instead-of-assigning` | 0 | 66 |
| CWE-483 | changed | `dfa-style-incorrect-block-delimitation` | 0 | 18 |
| CWE-484 | changed | `ast-style-absent-codeblock-in-case` | 0 | 136 |
| CWE-484 | changed | `ast-style-missing-break` | 0 | 17 |
| CWE-491 | changed | `ast-style-class-based-issue-491` | 0 | 1 |
| CWE-500 | changed | `ast-style-mutable-static-should-be-final` | 0 | 1 |
| CWE-511 | changed | `dfa-security-runtime-exec` | 0 | 51 |
| CWE-523 | changed | `ast-style-unprotected-cred-transport` | 0 | 17 |
| CWE-526 | changed | `ast-quality-env-var` | 0 | 34 |
| CWE-526 | changed | `ast-security-env-var-info-exposure-ast` | 0 | 12 |
| CWE-526 | changed | `ast-style-env-var-info-exposure` | 0 | 34 |
| CWE-533 | changed | `ast-style-log-info-leak` | 0 | 17 |
| CWE-534 | changed | `ast-style-log-info-leak` | 0 | 17 |
| CWE-535 | changed | `dfa-security-shell-error-exposure` | 0 | 17 |
| CWE-539 | changed | `dfa-security-persistent-cookie` | 0 | 17 |
| CWE-546 | changed | `ast-style-suspicious-comment` | 0 | 85 |
| CWE-549 | changed | `ast-style-missing-password-masking` | 0 | 17 |
| CWE-563 | changed | `ast-security-unused-variable-assignment` | 0 | 476 |
| CWE-563 | changed | `ast-style-unused-assignment` | 0 | 1119 |
| CWE-568 | changed | `ast-error-super-finalize` | 0 | 2 |
| CWE-570 | changed | `ast-quality-always-false` | 0 | 2 |
| CWE-570 | changed | `ast-style-conditional-constant` | 0 | 1 |
| CWE-570 | changed | `ast-style-literal-boolean-condition` | 0 | 17 |
| CWE-571 | changed | `ast-quality-always-true` | 0 | 1 |
| CWE-572 | changed | `ast-style-call-to-thread-run` | 0 | 17 |
| CWE-579 | changed | `ast-style-non-serializable-in-session` | 0 | 1 |
| CWE-580 | changed | `ast-style-cwe580-clone-without-super` | 0 | 1 |
| CWE-584 | changed | `ast-style-return-from-finally` | 0 | 17 |
| CWE-584 | changed | `ast-style-return-in-finally` | 0 | 17 |
| CWE-586 | changed | `ast-error-explicit-finalize` | 0 | 18 |
| CWE-586 | changed | `ast-error-finalize-only-overridden` | 0 | 1 |
| CWE-586 | changed | `ast-style-explicit-finalize` | 0 | 18 |
| CWE-597 | changed | `ast-style-string-eq-operator` | 0 | 219 |
| CWE-598 | changed | `ast-style-form-get-password` | 0 | 17 |
| CWE-600 | changed | `ast-style-cwe600-uncaught-exception` | 0 | 1 |
| CWE-601 | changed | `dfa-taint-open-redirect` | 0 | 420 |
| CWE-605 | changed | `ast-style-multiple-binds-same-port` | 0 | 20 |
| CWE-606 | changed | `dfa-taint-unchecked-loop-condition` | 0 | 1044 |
| CWE-609 | changed | `ast-style-double-checked-locking` | 0 | 2 |
| CWE-613 | changed | `ast-style-insufficient-session-expiration` | 0 | 17 |
| CWE-615 | changed | `ast-style-comment-info-leak` | 0 | 17 |
| CWE-615 | changed | `ast-style-info-exposure-in-comment` | 0 | 17 |
| CWE-643 | changed | `dfa-taint-xpath-injection` | 0 | 549 |
| CWE-681 | changed | `dfa-style-numeric-conversion` | 0 | 147 |
| CWE-690 | changed | `ast-security-null-deref-from-return-ast` | 0 | 338 |
| CWE-690 | changed | `ast-style-null-deref-from-return` | 0 | 165 |
| CWE-698 | changed | `ast-style-redirect-without-exit` | 0 | 17 |
| CWE-759 | changed | `ast-style-unsalted-one-way-hash` | 0 | 17 |
| CWE-760 | changed | `ast-style-predictable-salt` | 0 | 17 |
| CWE-835 | changed | `ast-style-infinite-loop` | 0 | 6 |

**The rows whose config did not change are the engine's doing.** A `mode: all` config enables every rule, so it is the same in both runs, and every difference in such a CWE comes from the fixpoint fix.

Those differences are small but they are not zero, so the fixpoint fix is NOT purely a performance fix: keying the parameter origins by the merged key stopped one method's labels from leaking into another, and a few verdicts changed because of it.

## The AI false-positive pass

The AI pass judges every finding the engine reports and drops the ones it calls false positives. For each CWE the SAME sample of Juliet files is scanned twice -- once with the AI off and once with it on -- so the only difference between the two numbers is the AI pass.

**A rate-limited request is not a verdict.** The provider's plan has a request rate limit, and a rate-limited request comes back as an UNKNOWN, which is indistinguishable from the model being unsure. A CWE whose verdicts were rate-limited is reported as rate-limited, not as a measurement.

| CWE | provider | TP before | TP after | FP before | FP after | TP cov before | TP cov after | other-CWE before | after | verdicts |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| CWE-15 | mixed | 0 | 0 | 0 | 0 | 0.000 | 0.000 | 0 | 0 | TP=29, UNKNOWN=5 |
| CWE-89 | mixed | 4 | 4 | 14 | 14 | 1.000 | 1.000 | 0 | 0 | TP=24, UNKNOWN=25 |
| CWE-190 | mixed | 6 | 6 | 7 | 7 | 0.600 | 0.600 | 0 | 0 | UNKNOWN=20 |

Over the sampled CWEs: true positives **10 -> 10**, false positives **21 -> 21**, findings about another CWE **0 -> 0**.

The last column is the pass's real effect on the report: a finding that is not about the CWE under test still costs the reader time, and the AI removes it whether or not the CWE's own false-positive count moves.

**Rate-limited and therefore not measured:** CWE-190 (140 requests)

## Against the previous measurement

Two things changed between this measurement and the previous one, and this table shows the COMBINED effect. It is not a measure of either change on its own:

1. The engine's parameter-origin fixpoint is keyed by the merged key (enclosing class + signature) instead of the signature alone. Before the fix, every same-named method in the corpus shared one label map, so the fixpoint never converged and the taint labels of one method leaked into another.
2. The per-CWE configs are derived from the engine's own rule table instead of from one past scan. Before the fix, most of the CWEs had a rule the engine ships and a config that did not enable it, so their coverage number said nothing about the rule.

A CWE whose true-positive count went from 0 to a positive number was almost certainly unmeasured before, not undetected.

63 of 112 CWEs changed their true-positive or false-positive method count.

| CWE | TP before | TP after | FP before | FP after |
| --- | ---: | ---: | ---: | ---: |
| CWE-15 | 0 | 366 | 0 | 138 |
| CWE-89 | 1875 | 2255 | 750 | 6105 |
| CWE-90 | 0 | 366 | 0 | 138 |
| CWE-114 | 0 | 17 | 0 | 0 |
| CWE-190 | 3935 | 4159 | 6223 | 6512 |
| CWE-191 | 3174 | 3383 | 3473 | 3827 |
| CWE-193 | 0 | 51 | 0 | 0 |
| CWE-226 | 0 | 17 | 0 | 30 |
| CWE-248 | 0 | 0 | 0 | 1 |
| CWE-256 | 0 | 31 | 0 | 88 |
| CWE-259 | 0 | 108 | 0 | 153 |
| CWE-325 | 0 | 17 | 0 | 0 |
| CWE-328 | 0 | 51 | 0 | 0 |
| CWE-329 | 0 | 17 | 0 | 0 |
| CWE-336 | 0 | 17 | 0 | 0 |
| CWE-338 | 0 | 34 | 0 | 0 |
| CWE-379 | 0 | 17 | 0 | 30 |
| CWE-382 | 0 | 34 | 0 | 0 |
| CWE-383 | 0 | 16 | 0 | 0 |
| CWE-390 | 0 | 17 | 0 | 0 |
| CWE-395 | 0 | 17 | 0 | 0 |
| CWE-396 | 0 | 34 | 0 | 0 |
| CWE-398 | 0 | 136 | 0 | 0 |
| CWE-459 | 0 | 18 | 0 | 30 |
| CWE-470 | 0 | 366 | 0 | 138 |
| CWE-476 | 126 | 163 | 196 | 452 |
| CWE-478 | 0 | 17 | 0 | 0 |
| CWE-481 | 0 | 17 | 0 | 0 |
| CWE-482 | 0 | 17 | 0 | 30 |
| CWE-483 | 0 | 18 | 0 | 0 |
| CWE-484 | 0 | 17 | 0 | 30 |
| CWE-511 | 0 | 51 | 0 | 0 |
| CWE-523 | 0 | 17 | 0 | 0 |
| CWE-526 | 0 | 34 | 0 | 0 |
| CWE-533 | 0 | 17 | 0 | 0 |
| CWE-534 | 0 | 17 | 0 | 0 |
| CWE-535 | 0 | 17 | 0 | 0 |
| CWE-539 | 0 | 17 | 0 | 0 |
| CWE-546 | 0 | 85 | 0 | 0 |
| CWE-549 | 0 | 17 | 0 | 0 |
| CWE-563 | 0 | 214 | 0 | 492 |
| CWE-570 | 0 | 16 | 0 | 1 |
| CWE-571 | 0 | 1 | 0 | 0 |
| CWE-572 | 0 | 17 | 0 | 0 |
| CWE-579 | 0 | 1 | 0 | 0 |
| CWE-584 | 0 | 17 | 0 | 0 |
| CWE-586 | 0 | 17 | 0 | 0 |
| CWE-597 | 0 | 17 | 0 | 30 |
| CWE-598 | 0 | 17 | 0 | 0 |
| CWE-600 | 0 | 1 | 0 | 0 |
| CWE-601 | 0 | 276 | 0 | 126 |
| CWE-605 | 0 | 17 | 0 | 1 |
| CWE-606 | 0 | 420 | 0 | 588 |
| CWE-613 | 0 | 17 | 0 | 0 |
| CWE-615 | 0 | 17 | 0 | 0 |
| CWE-643 | 0 | 375 | 0 | 150 |
| CWE-681 | 0 | 51 | 0 | 90 |
| CWE-690 | 142 | 241 | 254 | 342 |
| CWE-698 | 0 | 16 | 0 | 1 |
| CWE-759 | 0 | 17 | 0 | 0 |
| CWE-760 | 0 | 17 | 0 | 0 |
| CWE-789 | 1264 | 1271 | 563 | 597 |
| CWE-835 | 0 | 6 | 0 | 0 |

