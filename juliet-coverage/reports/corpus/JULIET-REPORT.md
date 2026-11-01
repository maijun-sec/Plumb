# Juliet measurement

The corpus is the real Juliet_Java testcase tree: 112 CWE directories, 46793 testcase files.

**The unit is the method.** A Juliet file declares the vulnerable method (`bad*`) and the fixed one (`good*`). A finding inside a `bad*` method is a true positive; inside a `good*` method it is a false positive; a `good*` method with no finding is a true negative; a `bad*` method with no finding is a false negative.

**Only a rule that is about the CWE under test is scored.** A finding from a rule labelled with another CWE is counted separately, because counting it as a false positive would measure the breadth of the per-CWE whitelist rather than the engine's precision.

## What each CWE's number means

| outcome | count | meaning |
| --- | ---: | --- |
| MEASURED | 90 | the CWE's own rules ran and found the defect |
| VALID ZERO | 12 | the CWE's own rules ran and found nothing |
| CONFIG GAP | 0 | the engine has rules for the CWE, the config enables none of them, so the coverage number says nothing about the rule |
| COVERAGE GAP | 10 | the engine has no rule for the CWE at all |
| NOT MEASURED | 0 | the scan did not finish, or produced no status |

## Per CWE

| CWE | outcome | mode | own rules on | bad | good | TP | FN | FP | TN | TP cov | TN cov | prec |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CWE-15 | MEASURED | whitelist | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-23 | MEASURED | whitelist | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-36 | MEASURED | whitelist | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-78 | MEASURED | all | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-80 | COVERAGE GAP | all | 0/0 | 1098 | 2070 | 0 | 1098 | 0 | 2070 | 0.000 | 1.000 | n/a |
| CWE-81 | COVERAGE GAP | all | 0/0 | 549 | 1035 | 0 | 549 | 0 | 1035 | 0.000 | 1.000 | n/a |
| CWE-83 | COVERAGE GAP | all | 0/0 | 549 | 1035 | 0 | 549 | 0 | 1035 | 0.000 | 1.000 | n/a |
| CWE-89 | MEASURED | whitelist | 3/3 | 3660 | 11340 | 2255 | 1405 | 6105 | 5235 | 0.616 | 0.462 | 0.375 |
| CWE-90 | MEASURED | whitelist | 1/1 | 732 | 1380 | 366 | 366 | 138 | 1242 | 0.500 | 0.900 | 0.726 |
| CWE-111 | COVERAGE GAP | whitelist | 0/0 | 1 | 0 | 0 | 1 | 0 | 0 | 0.000 | n/a | n/a |
| CWE-113 | MEASURED | all | 1/1 | 2196 | 6804 | 1123 | 1073 | 1477 | 5327 | 0.511 | 0.783 | 0.478 |
| CWE-114 | MEASURED | whitelist | 2/2 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-129 | MEASURED | whitelist | 6/6 | 4392 | 13608 | 1963 | 2429 | 966 | 12642 | 0.447 | 0.929 | 0.683 |
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
| CWE-315 | MEASURED | whitelist | 1/1 | 61 | 189 | 35 | 26 | 18 | 171 | 0.574 | 0.905 | 0.660 |
| CWE-319 | MEASURED | all | 4/4 | 610 | 1890 | 424 | 186 | 688 | 1202 | 0.695 | 0.636 | 0.380 |
| CWE-321 | MEASURED | whitelist | 1/1 | 61 | 115 | 18 | 43 | 31 | 84 | 0.295 | 0.730 | 0.367 |
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
| CWE-397 | MEASURED | whitelist | 1/1 | 4 | 8 | 4 | 0 | 0 | 8 | 1.000 | 1.000 | 1.000 |
| CWE-398 | MEASURED | whitelist | 5/5 | 137 | 378 | 137 | 0 | 0 | 378 | 1.000 | 1.000 | 1.000 |
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
| CWE-486 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
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
| CWE-571 | MEASURED | whitelist | 2/2 | 16 | 32 | 15 | 1 | 0 | 32 | 0.938 | 1.000 | 1.000 |
| CWE-572 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-579 | MEASURED | whitelist | 2/2 | 1 | 2 | 1 | 0 | 0 | 2 | 1.000 | 1.000 | 1.000 |
| CWE-580 | VALID ZERO | whitelist | 1/1 | 1 | 2 | 0 | 1 | 0 | 2 | 0.000 | 1.000 | n/a |
| CWE-581 | COVERAGE GAP | whitelist | 0/0 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-582 | MEASURED | whitelist | 1/1 | 1 | 2 | 1 | 0 | 0 | 2 | 1.000 | 1.000 | 1.000 |
| CWE-584 | MEASURED | whitelist | 3/3 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-585 | MEASURED | whitelist | 1/1 | 2 | 4 | 1 | 1 | 0 | 4 | 0.500 | 1.000 | 1.000 |
| CWE-586 | MEASURED | whitelist | 4/4 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-597 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 30 | 17 | 1.000 | 0.362 | 0.416 |
| CWE-598 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-600 | MEASURED | whitelist | 1/1 | 1 | 2 | 1 | 0 | 0 | 2 | 1.000 | 1.000 | 1.000 |
| CWE-601 | MEASURED | whitelist | 2/2 | 549 | 1035 | 276 | 273 | 126 | 909 | 0.503 | 0.878 | 0.687 |
| CWE-605 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 1 | 46 | 1.000 | 0.979 | 0.900 |
| CWE-606 | MEASURED | whitelist | 1/1 | 732 | 2268 | 420 | 312 | 588 | 1680 | 0.574 | 0.741 | 0.412 |
| CWE-607 | MEASURED | whitelist | 1/1 | 1 | 1 | 1 | 0 | 0 | 1 | 1.000 | 1.000 | 1.000 |
| CWE-609 | VALID ZERO | whitelist | 2/2 | 2 | 12 | 0 | 2 | 0 | 12 | 0.000 | 1.000 | n/a |
| CWE-613 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-614 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-615 | MEASURED | whitelist | 2/2 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-617 | COVERAGE GAP | whitelist | 0/0 | 34 | 94 | 0 | 34 | 0 | 94 | 0.000 | 1.000 | n/a |
| CWE-643 | MEASURED | whitelist | 1/1 | 732 | 2268 | 375 | 357 | 150 | 2118 | 0.512 | 0.934 | 0.714 |
| CWE-667 | MEASURED | whitelist | 1/1 | 1 | 2 | 1 | 0 | 0 | 2 | 1.000 | 1.000 | 1.000 |
| CWE-674 | VALID ZERO | whitelist | 2/2 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-681 | MEASURED | whitelist | 1/1 | 51 | 141 | 51 | 0 | 90 | 51 | 1.000 | 0.362 | 0.367 |
| CWE-690 | MEASURED | whitelist | 2/2 | 488 | 1512 | 241 | 247 | 342 | 1170 | 0.494 | 0.774 | 0.488 |
| CWE-698 | MEASURED | whitelist | 1/1 | 17 | 47 | 16 | 1 | 1 | 46 | 0.941 | 0.979 | 0.941 |
| CWE-759 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-760 | MEASURED | whitelist | 1/1 | 17 | 47 | 17 | 0 | 0 | 47 | 1.000 | 1.000 | 1.000 |
| CWE-764 | VALID ZERO | whitelist | 1/1 | 2 | 4 | 0 | 2 | 0 | 4 | 0.000 | 1.000 | n/a |
| CWE-765 | MEASURED | whitelist | 1/1 | 2 | 4 | 1 | 1 | 0 | 4 | 0.500 | 1.000 | 1.000 |
| CWE-772 | MEASURED | whitelist | 1/1 | 2 | 4 | 1 | 1 | 0 | 4 | 0.500 | 1.000 | 1.000 |
| CWE-775 | MEASURED | whitelist | 1/1 | 2 | 4 | 2 | 0 | 0 | 4 | 1.000 | 1.000 | 1.000 |
| CWE-789 | MEASURED | all | 2/2 | 2579 | 4877 | 1269 | 1310 | 597 | 4280 | 0.492 | 0.878 | 0.667 |
| CWE-832 | MEASURED | whitelist | 1/1 | 2 | 4 | 1 | 1 | 0 | 4 | 0.500 | 1.000 | 1.000 |
| CWE-833 | VALID ZERO | whitelist | 1/1 | 6 | 12 | 0 | 6 | 0 | 12 | 0.000 | 1.000 | n/a |
| CWE-835 | MEASURED | whitelist | 1/1 | 6 | 13 | 6 | 0 | 0 | 13 | 1.000 | 1.000 | 1.000 |

## Totals over the measured CWEs

| | methods |
| --- | ---: |
| vulnerable (`bad*`) | 46327 |
| covered by a finding | 23590 |
| missed | 22737 |
| fixed (`good*`) | 128042 |
| kept clean | 98763 |
| reported as a false positive | 29279 |

TP coverage over the vulnerable methods: **0.509** (23590 of 46327)

TN coverage over the fixed methods: **0.771** (98763 of 128042)

## AST rules and DFA rules

The two engines have different reachability, so their coverage is not interchangeable and is reported apart.

| engine | true positives | false positives |
| --- | ---: | ---: |
| ast | 5661 | 9534 |
| dfa | 29231 | 28157 |

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
| `dfa-taint-resource-exhaustion` | dfa | 2320 | 1 |
| `dfa-taint-http-response-splitting` | dfa | 1658 | 1 |
| `dfa-taint-integer-underflow` | dfa | 1310 | 1 |
| `ast-style-unused-assignment` | ast | 836 | 1 |
| `dfa-taint-sql-injection` | dfa | 750 | 1 |
| `dfa-boundary-array-out-of-bounds` | dfa | 748 | 1 |
| `dfa-taint-unchecked-array-index` | dfa | 720 | 1 |
| `ast-style-icast-int-mult-long` | ast | 612 | 1 |
| `dfa-taint-unchecked-loop-condition` | dfa | 600 | 1 |
| `dfa-taint-path-traversal` | dfa | 552 | 2 |
| `dfa-taint-uncontrolled-mem-alloc` | dfa | 516 | 1 |
| `dfa-taint-cleartext-network-credential-sink` | dfa | 513 | 1 |
| `dfa-taint-null-dereference` | dfa | 507 | 2 |
| `ast-bug-equals-avoid-null` | ast | 301 | 1 |
| `dfa-taint-format-string` | dfa | 216 | 1 |
| `dfa-taint-null-deref-from-return` | dfa | 215 | 1 |
| `ast-security-unused-variable-assignment` | ast | 196 | 1 |
| `ast-security-unbounded-mem-alloc` | ast | 166 | 1 |
| `ast-security-insecure-communication` | ast | 159 | 1 |
| `ast-security-hardcoded-password-sink-ast` | ast | 153 | 1 |

## Findings about another CWE

These fired in the corpus but are not about the CWE under test, so they are neither a hit nor a miss here. Two very different sources are separated, because lumping them together would make a deliberate no-whitelist measurement look like engine noise.

| source | findings |
| --- | ---: |
| a whitelist config that enables a rule about another CWE | 57135 |
| a `mode: all` config, which enables every rule by design | 623765 |

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

- CWE-80 (1098 vulnerable methods, 2070 fixed)
- CWE-81 (549 vulnerable methods, 1035 fixed)
- CWE-83 (549 vulnerable methods, 1035 fixed)
- CWE-111 (1 vulnerable methods, 0 fixed)
- CWE-499 (0 vulnerable methods, 0 fixed)
- CWE-506 (116 vulnerable methods, 143 fixed)
- CWE-510 (66 vulnerable methods, 94 fixed)
- CWE-566 (61 vulnerable methods, 115 fixed)
- CWE-581 (2 vulnerable methods, 4 fixed)
- CWE-617 (34 vulnerable methods, 94 fixed)

**A CWE whose own rules ran and found nothing** -- a valid zero. The rule is enabled, it ran, and it did not find the defect in these testcases.

- CWE-197: 2013 vulnerable methods, 3795 fixed, rule(s) `dfa-taint-numeric-truncation`
- CWE-248: 1 vulnerable methods, 2 fixed, rule(s) `dfa-controlflow-uncaught-throw`
- CWE-378: 17 vulnerable methods, 47 fixed, rule(s) `ast-style-insecure-temp-file-perms-DEDUPED`
- CWE-491: 1 vulnerable methods, 2 fixed, rule(s) `ast-style-class-based-issue-491`, `ast-style-object-hijack`
- CWE-500: 1 vulnerable methods, 2 fixed, rule(s) `ast-style-mutable-static-should-be-final`
- CWE-561: 1 vulnerable methods, 2 fixed, rule(s) `ast-style-deadcode`
- CWE-568: 2 vulnerable methods, 4 fixed, rule(s) `ast-error-super-finalize`
- CWE-580: 1 vulnerable methods, 2 fixed, rule(s) `ast-style-cwe580-clone-without-super`
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

A per-rule finding count tells the engine change apart from the configuration change, because a rule that was already enabled keeps its count when the configuration did not change.

What changed between the previous measurement and this one:

1. The engine's array-length bound, the guard-combination semantics for `&&` and `||`, and a constant resolver for the always-true rule.
2. The measurement now includes the corpus's support library, `src/testcasesupport`, as a source root. A Juliet testcase is not a self-contained program: `CWE571_...__static_01.java` reads `IO.staticTrue`, and `IO` lives outside `src/testcases`. Without that directory the scan saw a program that cannot compile, and the findings the engine missed were an artifact of the measurement setup rather than a defect of the engine.
3. The per-CWE configs are unchanged from the previous measurement, so the difference between the two runs is the engine and the corpus scope, not the configuration.

| CWE | config | rule | before | after |
| --- | --- | --- | ---: | ---: |
| CWE-23 | changed | `dfa-taint-path-traversal` | 0 | 1056 |
| CWE-36 | changed | `dfa-taint-path-traversal` | 0 | 1056 |
| CWE-78 | unchanged | `ast-quality-always-true` | 48 | 276 |
| CWE-80 | unchanged | `ast-naming-final-local-variable` | 989 | 995 |
| CWE-80 | unchanged | `ast-performance-dup-string` | 590 | 742 |
| CWE-80 | unchanged | `ast-quality-always-true` | 72 | 213 |
| CWE-80 | unchanged | `ast-security-null-deref-from-return-ast` | 511 | 520 |
| CWE-80 | unchanged | `ast-security-unused-variable-assignment` | 2648 | 2641 |
| CWE-80 | unchanged | `ast-style-avoid-print-stack-trace` | 1 | 2 |
| CWE-80 | unchanged | `ast-style-emptylineseparatorcheck-cs` | 2 | 1 |
| CWE-80 | unchanged | `ast-style-expression-always-true` | 274 | 301 |
| CWE-80 | unchanged | `ast-style-literal-boolean-condition` | 146 | 162 |
| CWE-80 | unchanged | `ast-style-string-eq-operator` | 3084 | 3086 |
| CWE-81 | unchanged | `ast-quality-always-true` | 36 | 207 |
| CWE-83 | unchanged | `ast-quality-always-true` | 36 | 207 |
| CWE-113 | unchanged | `ast-naming-final-local-variable` | 4729 | 4514 |
| CWE-113 | unchanged | `ast-performance-dup-string` | 4528 | 5166 |
| CWE-113 | unchanged | `ast-quality-always-true` | 504 | 1584 |
| CWE-113 | unchanged | `ast-security-null-deref-from-return-ast` | 1844 | 1876 |
| CWE-113 | unchanged | `ast-security-plaintext-cookie` | 0 | 1056 |
| CWE-113 | unchanged | `ast-security-unused-variable-assignment` | 7197 | 7206 |
| CWE-113 | unchanged | `ast-style-avoid-print-stack-trace` | 2 | 3 |
| CWE-113 | unchanged | `ast-style-emptylineseparatorcheck-cs` | 6 | 3 |
| CWE-113 | unchanged | `ast-style-expression-always-true` | 2289 | 2562 |
| CWE-113 | unchanged | `ast-style-literal-boolean-condition` | 593 | 684 |
| CWE-113 | unchanged | `ast-style-string-eq-operator` | 11310 | 11335 |
| CWE-129 | changed | `dfa-taint-unchecked-array-index` | 4562 | 2532 |
| CWE-209 | unchanged | `ast-quality-always-true` | 8 | 46 |
| CWE-315 | changed | `ast-security-plaintext-cookie` | 0 | 55 |
| CWE-319 | unchanged | `ast-quality-always-true` | 140 | 760 |
| CWE-319 | unchanged | `ast-security-hardcoded-crypto-key` | 0 | 486 |
| CWE-321 | changed | `ast-security-hardcoded-crypto-key` | 0 | 49 |
| CWE-486 | changed | `ast-security-compare-classes-by-name` | 0 | 17 |
| CWE-571 | changed | `ast-quality-always-true` | 1 | 15 |
| CWE-582 | changed | `ast-security-mutable-static-field` | 0 | 3 |
| CWE-607 | changed | `ast-security-mutable-static-field` | 0 | 2 |
| CWE-667 | changed | `ast-concurrency-lock-pairing` | 0 | 1 |
| CWE-765 | changed | `ast-concurrency-lock-pairing` | 0 | 2 |
| CWE-772 | changed | `ast-security-unclosed-resource` | 0 | 2 |
| CWE-775 | changed | `ast-security-unclosed-resource` | 0 | 3 |
| CWE-789 | unchanged | `ast-naming-final-local-variable` | 5752 | 5896 |
| CWE-789 | unchanged | `ast-performance-dup-string` | 127 | 65 |
| CWE-789 | unchanged | `ast-quality-always-true` | 270 | 644 |
| CWE-789 | unchanged | `ast-security-null-deref-from-return-ast` | 1119 | 1127 |
| CWE-789 | unchanged | `ast-security-unbounded-mem-alloc` | 285 | 270 |
| CWE-789 | unchanged | `ast-security-unused-variable-assignment` | 3705 | 3733 |
| CWE-789 | unchanged | `ast-style-emptylineseparatorcheck-cs` | 12 | 5 |
| CWE-789 | unchanged | `ast-style-expression-always-true` | 711 | 793 |
| CWE-789 | unchanged | `ast-style-literal-boolean-condition` | 338 | 403 |
| CWE-789 | unchanged | `ast-style-string-eq-operator` | 3579 | 3562 |
| CWE-832 | changed | `ast-concurrency-lock-pairing` | 0 | 2 |

**The rows whose config did not change are the engine's doing.** A `mode: all` config enables every rule, so it is the same in both runs, and every difference in such a CWE comes from the engine.

Those differences are not zero, so the engine change was not semantically neutral: it changed a number of verdicts, in both directions.

## Against the previous measurement

What changed between this measurement and the previous one:

1. The engine's array-length bound, the guard-combination semantics for `&&` and `||`, and a constant resolver for the always-true rule.
2. The measurement now includes the corpus's support library, `src/testcasesupport`, as a source root. A Juliet testcase is not a self-contained program: `CWE571_...__static_01.java` reads `IO.staticTrue`, and `IO` lives outside `src/testcases`. Without that directory the scan saw a program that cannot compile, and the findings the engine missed were an artifact of the measurement setup rather than a defect of the engine.
3. The per-CWE configs are unchanged from the previous measurement, so the difference between the two runs is the engine and the corpus scope, not the configuration.

The table below shows the COMBINED effect of those changes. It is not a measure of any one of them on its own.

A CWE whose true-positive count went from 0 to a positive number was almost certainly unmeasured before, not undetected.

18 of 112 CWEs changed their true-positive or false-positive method count.

| CWE | TP before | TP after | FP before | FP after |
| --- | ---: | ---: | ---: | ---: |
| CWE-23 | 0 | 366 | 0 | 138 |
| CWE-36 | 0 | 366 | 0 | 138 |
| CWE-129 | 1558 | 1963 | 3280 | 966 |
| CWE-315 | 0 | 35 | 0 | 18 |
| CWE-321 | 0 | 18 | 0 | 31 |
| CWE-397 | 2 | 4 | 0 | 0 |
| CWE-398 | 136 | 137 | 0 | 0 |
| CWE-486 | 0 | 17 | 0 | 0 |
| CWE-571 | 1 | 15 | 0 | 0 |
| CWE-582 | 0 | 1 | 0 | 0 |
| CWE-585 | 0 | 1 | 0 | 0 |
| CWE-607 | 0 | 1 | 0 | 0 |
| CWE-667 | 0 | 1 | 0 | 0 |
| CWE-765 | 0 | 1 | 0 | 0 |
| CWE-772 | 0 | 1 | 0 | 0 |
| CWE-775 | 0 | 2 | 0 | 0 |
| CWE-789 | 1271 | 1269 | 597 | 597 |
| CWE-832 | 0 | 1 | 0 | 0 |

