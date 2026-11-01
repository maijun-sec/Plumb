# Juliet corpus support

Every CWE category in the Juliet_Java testcase corpus, with what the
engine supports today.

**The unit is the method.** A Juliet file declares the vulnerable method
(`bad*`) and the fixed one (`good*`). A finding inside a `bad*`
method is a true positive; inside a `good*` method it is a false
positive.

**Only a finding whose rule is about the CWE under test is scored.** A
finding from a rule labelled with another CWE is counted separately,
because counting it as a false positive would measure the breadth of
the per-CWE whitelist rather than the engine's precision.

| column | meaning |
| --- | --- |
| rules on | the per-CWE config enables N rules, of which M are the engine's own rules for this CWE |
| vulnerable | the number of `bad*` methods in the corpus |
| TP / FN | vulnerable methods with / without a finding |
| FP | fixed (`good*`) methods that carry a finding |
| TP cov | TP / (TP + FN) |
| TN cov | fixed methods kept clean / all fixed methods |
| dTP / dFP | the change in TP and FP against the previous measurement |

A category with **no rule at all** is a coverage gap. Its numbers are
zero because there was no rule to run -- not because a rule ran and
missed.

### MEASURED -- Supported - the rule ran and found the defect  (90 categories)

| CWE | config | rules on | vulnerable | TP | FN | FP | TP cov | TN cov | dTP | dFP |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CWE-15 | whitelist | 5/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 | +0 | +0 |
| CWE-23 | whitelist | 7/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 | +366 | +138 |
| CWE-36 | whitelist | 5/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 | +366 | +138 |
| CWE-78 | all | 0/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 | +0 | +0 |
| CWE-89 | whitelist | 10/3 | 3660 | 2255 | 1405 | 6105 | 0.616 | 0.462 | +0 | +0 |
| CWE-90 | whitelist | 5/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 | +0 | +0 |
| CWE-113 | all | 0/1 | 2196 | 1123 | 1073 | 1477 | 0.511 | 0.783 | +0 | +0 |
| CWE-114 | whitelist | 4/2 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-129 | whitelist | 10/6 | 4392 | 1963 | 2429 | 966 | 0.447 | 0.929 | +405 | -2314 |
| CWE-134 | whitelist | 5/1 | 1098 | 558 | 540 | 216 | 0.508 | 0.937 | +0 | +0 |
| CWE-190 | whitelist | 7/5 | 7015 | 4159 | 2856 | 6512 | 0.593 | 0.700 | +0 | +0 |
| CWE-191 | whitelist | 5/2 | 5612 | 3383 | 2229 | 3827 | 0.603 | 0.780 | +0 | +0 |
| CWE-193 | whitelist | 6/2 | 51 | 51 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-209 | all | 0/5 | 34 | 34 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-226 | whitelist | 4/1 | 17 | 17 | 0 | 30 | 1.000 | 0.362 | +0 | +0 |
| CWE-252 | whitelist | 7/5 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-253 | whitelist | 7/3 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-256 | whitelist | 5/1 | 61 | 31 | 30 | 88 | 0.508 | 0.534 | +0 | +0 |
| CWE-259 | whitelist | 8/4 | 183 | 108 | 75 | 153 | 0.590 | 0.557 | +0 | +0 |
| CWE-315 | whitelist | 6/1 | 61 | 35 | 26 | 18 | 0.574 | 0.905 | +35 | +18 |
| CWE-319 | all | 0/4 | 610 | 424 | 186 | 688 | 0.695 | 0.636 | +0 | +0 |
| CWE-321 | whitelist | 5/1 | 61 | 18 | 43 | 31 | 0.295 | 0.730 | +18 | +31 |
| CWE-325 | whitelist | 3/1 | 34 | 17 | 17 | 0 | 0.500 | 1.000 | +0 | +0 |
| CWE-327 | whitelist | 4/2 | 34 | 34 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-328 | whitelist | 4/1 | 51 | 51 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-329 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-336 | whitelist | 4/2 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-338 | whitelist | 3/1 | 34 | 34 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-369 | whitelist | 8/2 | 3050 | 1800 | 1250 | 3068 | 0.590 | 0.675 | +0 | +0 |
| CWE-379 | whitelist | 4/1 | 17 | 17 | 0 | 30 | 1.000 | 0.362 | +0 | +0 |
| CWE-382 | whitelist | 3/1 | 34 | 34 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-383 | whitelist | 3/1 | 16 | 16 | 0 | 0 | 1.000 | n/a | +0 | +0 |
| CWE-390 | whitelist | 11/5 | 34 | 17 | 17 | 0 | 0.500 | 1.000 | +0 | +0 |
| CWE-395 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-396 | whitelist | 8/5 | 34 | 34 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-397 | whitelist | 6/1 | 4 | 4 | 0 | 0 | 1.000 | 1.000 | +2 | +0 |
| CWE-398 | whitelist | 7/5 | 137 | 137 | 0 | 0 | 1.000 | 1.000 | +1 | +0 |
| CWE-400 | whitelist | 10/3 | 2396 | 1215 | 1181 | 2281 | 0.507 | 0.693 | +0 | +0 |
| CWE-404 | whitelist | 3/1 | 5 | 2 | 3 | 0 | 0.400 | 1.000 | +0 | +0 |
| CWE-459 | whitelist | 5/2 | 34 | 18 | 16 | 30 | 0.529 | 0.681 | +0 | +0 |
| CWE-470 | whitelist | 5/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 | +0 | +0 |
| CWE-476 | whitelist | 11/9 | 293 | 163 | 130 | 452 | 0.556 | 0.493 | +0 | +0 |
| CWE-477 | whitelist | 5/3 | 68 | 68 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-478 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-481 | whitelist | 4/2 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-482 | whitelist | 3/1 | 17 | 17 | 0 | 30 | 1.000 | 0.362 | +0 | +0 |
| CWE-483 | whitelist | 3/1 | 19 | 18 | 1 | 0 | 0.947 | 1.000 | +0 | +0 |
| CWE-484 | whitelist | 4/2 | 17 | 17 | 0 | 30 | 1.000 | 0.362 | +0 | +0 |
| CWE-486 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +17 | +0 |
| CWE-511 | whitelist | 3/1 | 51 | 51 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-523 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-526 | whitelist | 5/3 | 34 | 34 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-533 | whitelist | 5/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-534 | whitelist | 5/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-535 | whitelist | 5/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-539 | whitelist | 4/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-546 | whitelist | 3/1 | 85 | 85 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-549 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-563 | whitelist | 10/7 | 218 | 214 | 4 | 492 | 0.982 | 0.313 | +0 | +0 |
| CWE-570 | whitelist | 6/3 | 16 | 16 | 0 | 1 | 1.000 | 0.969 | +0 | +0 |
| CWE-571 | whitelist | 5/2 | 16 | 15 | 1 | 0 | 0.938 | 1.000 | +14 | +0 |
| CWE-572 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-579 | whitelist | 4/2 | 1 | 1 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-582 | whitelist | 3/1 | 1 | 1 | 0 | 0 | 1.000 | 1.000 | +1 | +0 |
| CWE-584 | whitelist | 6/3 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-585 | whitelist | 5/1 | 2 | 1 | 1 | 0 | 0.500 | 1.000 | +1 | +0 |
| CWE-586 | whitelist | 6/4 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-597 | whitelist | 3/1 | 17 | 17 | 0 | 30 | 1.000 | 0.362 | +0 | +0 |
| CWE-598 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-600 | whitelist | 3/1 | 1 | 1 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-601 | whitelist | 6/2 | 549 | 276 | 273 | 126 | 0.503 | 0.878 | +0 | +0 |
| CWE-605 | whitelist | 4/1 | 17 | 17 | 0 | 1 | 1.000 | 0.979 | +0 | +0 |
| CWE-606 | whitelist | 7/1 | 732 | 420 | 312 | 588 | 0.574 | 0.741 | +0 | +0 |
| CWE-607 | whitelist | 3/1 | 1 | 1 | 0 | 0 | 1.000 | 1.000 | +1 | +0 |
| CWE-613 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-614 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-615 | whitelist | 4/2 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-643 | whitelist | 8/1 | 732 | 375 | 357 | 150 | 0.512 | 0.934 | +0 | +0 |
| CWE-667 | whitelist | 3/1 | 1 | 1 | 0 | 0 | 1.000 | 1.000 | +1 | +0 |
| CWE-681 | whitelist | 3/1 | 51 | 51 | 0 | 90 | 1.000 | 0.362 | +0 | +0 |
| CWE-690 | whitelist | 7/2 | 488 | 241 | 247 | 342 | 0.494 | 0.774 | +0 | +0 |
| CWE-698 | whitelist | 3/1 | 17 | 16 | 1 | 1 | 0.941 | 0.979 | +0 | +0 |
| CWE-759 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-760 | whitelist | 3/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |
| CWE-765 | whitelist | 3/1 | 2 | 1 | 1 | 0 | 0.500 | 1.000 | +1 | +0 |
| CWE-772 | whitelist | 4/1 | 2 | 1 | 1 | 0 | 0.500 | 1.000 | +1 | +0 |
| CWE-775 | whitelist | 4/1 | 2 | 2 | 0 | 0 | 1.000 | 1.000 | +2 | +0 |
| CWE-789 | all | 0/2 | 2579 | 1269 | 1310 | 597 | 0.492 | 0.878 | -2 | +0 |
| CWE-832 | whitelist | 3/1 | 2 | 1 | 1 | 0 | 0.500 | 1.000 | +1 | +0 |
| CWE-835 | whitelist | 3/1 | 6 | 6 | 0 | 0 | 1.000 | 1.000 | +0 | +0 |

### VALID ZERO -- the rule ran and found nothing  (12 categories)

| CWE | config | rules on | vulnerable | TP | FN | FP | TP cov | TN cov | dTP | dFP |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CWE-197 | whitelist | 5/1 | 2013 | 0 | 2013 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-248 | whitelist | 6/1 | 1 | 0 | 1 | 1 | 0.000 | 0.500 | +0 | +0 |
| CWE-378 | whitelist | 4/1 | 17 | 0 | 17 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-491 | whitelist | 6/2 | 1 | 0 | 1 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-500 | whitelist | 5/1 | 1 | 0 | 1 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-561 | whitelist | 3/1 | 1 | 0 | 1 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-568 | whitelist | 3/1 | 2 | 0 | 2 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-580 | whitelist | 6/1 | 1 | 0 | 1 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-609 | whitelist | 4/2 | 2 | 0 | 2 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-674 | whitelist | 6/2 | 2 | 0 | 2 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-764 | whitelist | 3/1 | 2 | 0 | 2 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-833 | whitelist | 3/1 | 6 | 0 | 6 | 0 | 0.000 | 1.000 | +0 | +0 |

### CONFIG GAP -- the engine has the rule, the config enables none of it  (0 categories)

none

### COVERAGE GAP -- the engine has no rule for this CWE at all  (10 categories)

| CWE | config | rules on | vulnerable | TP | FN | FP | TP cov | TN cov | dTP | dFP |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CWE-80 | all | 0/0 | 1098 | 0 | 1098 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-81 | all | 0/0 | 549 | 0 | 549 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-83 | all | 0/0 | 549 | 0 | 549 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-111 | whitelist | 2/0 | 1 | 0 | 1 | 0 | 0.000 | n/a | +0 | +0 |
| CWE-499 | whitelist | 4/0 | 0 | 0 | 0 | 0 | n/a | n/a | +0 | +0 |
| CWE-506 | whitelist | 3/0 | 116 | 0 | 116 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-510 | whitelist | 4/0 | 66 | 0 | 66 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-566 | whitelist | 3/0 | 61 | 0 | 61 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-581 | whitelist | 4/0 | 2 | 0 | 2 | 0 | 0.000 | 1.000 | +0 | +0 |
| CWE-617 | whitelist | 2/0 | 34 | 0 | 34 | 0 | 0.000 | 1.000 | +0 | +0 |

### NOT MEASURED -- the scan did not finish, so nothing was measured  (0 categories)

none

