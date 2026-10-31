# Juliet corpus support

Every CWE category in the Juliet_Java testcase corpus, with what the
engine supports today.

**The unit is the method.** A Juliet file declares the vulnerable method
(`bad*`) and the fixed one (`good*`). A finding inside a `bad*`
method is a true positive; inside a `good*` method it is a false
positive.

**Only a finding whose rule is about the CWE under test is scored.** A
finding from a rule labelled with another CWE is counted separately,
because counting it as a false positive would measure the breadth of the
per-CWE whitelist rather than the engine's precision.

| column | meaning |
| --- | --- |
| rules on | the engine's own rules for this CWE, and how many of them the per-CWE config enables |
| vulnerable | the number of `bad*` methods in the corpus |
| TP / FN | vulnerable methods with / without a finding |
| FP | fixed (`good*`) methods that carry a finding |
| TP cov | TP / (TP + FN) |
| TN cov | fixed methods kept clean / all fixed methods |

A category with **no rule at all** is a coverage gap. Its numbers are
zero because there was no rule to run -- not because a rule ran and
missed.

### MEASURED -- Supported - the rule ran and found the defect  (77 categories)

| CWE | config | rules on | vulnerable | TP | FN | FP | TP cov | TN cov |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CWE-15 | whitelist | 1/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 |
| CWE-78 | all | 1/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 |
| CWE-89 | whitelist | 3/3 | 3660 | 2255 | 1405 | 6105 | 0.616 | 0.462 |
| CWE-90 | whitelist | 1/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 |
| CWE-113 | all | 1/1 | 2196 | 1123 | 1073 | 1477 | 0.511 | 0.783 |
| CWE-114 | whitelist | 2/2 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-129 | whitelist | 6/6 | 4392 | 1558 | 2834 | 3280 | 0.355 | 0.759 |
| CWE-134 | whitelist | 1/1 | 1098 | 558 | 540 | 216 | 0.508 | 0.937 |
| CWE-190 | whitelist | 5/5 | 7015 | 4159 | 2856 | 6512 | 0.593 | 0.700 |
| CWE-191 | whitelist | 2/2 | 5612 | 3383 | 2229 | 3827 | 0.603 | 0.780 |
| CWE-193 | whitelist | 2/2 | 51 | 51 | 0 | 0 | 1.000 | 1.000 |
| CWE-209 | all | 5/5 | 34 | 34 | 0 | 0 | 1.000 | 1.000 |
| CWE-226 | whitelist | 1/1 | 17 | 17 | 0 | 30 | 1.000 | 0.362 |
| CWE-252 | whitelist | 5/5 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-253 | whitelist | 3/3 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-256 | whitelist | 1/1 | 61 | 31 | 30 | 88 | 0.508 | 0.534 |
| CWE-259 | whitelist | 4/4 | 183 | 108 | 75 | 153 | 0.590 | 0.557 |
| CWE-319 | all | 4/4 | 610 | 424 | 186 | 688 | 0.695 | 0.636 |
| CWE-325 | whitelist | 1/1 | 34 | 17 | 17 | 0 | 0.500 | 1.000 |
| CWE-327 | whitelist | 2/2 | 34 | 34 | 0 | 0 | 1.000 | 1.000 |
| CWE-328 | whitelist | 1/1 | 51 | 51 | 0 | 0 | 1.000 | 1.000 |
| CWE-329 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-336 | whitelist | 2/2 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-338 | whitelist | 1/1 | 34 | 34 | 0 | 0 | 1.000 | 1.000 |
| CWE-369 | whitelist | 2/2 | 3050 | 1800 | 1250 | 3068 | 0.590 | 0.675 |
| CWE-379 | whitelist | 1/1 | 17 | 17 | 0 | 30 | 1.000 | 0.362 |
| CWE-382 | whitelist | 1/1 | 34 | 34 | 0 | 0 | 1.000 | 1.000 |
| CWE-383 | whitelist | 1/1 | 16 | 16 | 0 | 0 | 1.000 | 0 |
| CWE-390 | whitelist | 5/5 | 34 | 17 | 17 | 0 | 0.500 | 1.000 |
| CWE-395 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-396 | whitelist | 5/5 | 34 | 34 | 0 | 0 | 1.000 | 1.000 |
| CWE-397 | whitelist | 1/1 | 2 | 2 | 0 | 0 | 1.000 | 1.000 |
| CWE-398 | whitelist | 5/5 | 137 | 136 | 1 | 0 | 0.993 | 1.000 |
| CWE-400 | whitelist | 3/3 | 2396 | 1215 | 1181 | 2281 | 0.507 | 0.693 |
| CWE-404 | whitelist | 1/1 | 5 | 2 | 3 | 0 | 0.400 | 1.000 |
| CWE-459 | whitelist | 2/2 | 34 | 18 | 16 | 30 | 0.529 | 0.681 |
| CWE-470 | whitelist | 1/1 | 732 | 366 | 366 | 138 | 0.500 | 0.900 |
| CWE-476 | whitelist | 9/9 | 293 | 163 | 130 | 452 | 0.556 | 0.493 |
| CWE-477 | whitelist | 3/3 | 68 | 68 | 0 | 0 | 1.000 | 1.000 |
| CWE-478 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-481 | whitelist | 2/2 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-482 | whitelist | 1/1 | 17 | 17 | 0 | 30 | 1.000 | 0.362 |
| CWE-483 | whitelist | 1/1 | 19 | 18 | 1 | 0 | 0.947 | 1.000 |
| CWE-484 | whitelist | 2/2 | 17 | 17 | 0 | 30 | 1.000 | 0.362 |
| CWE-511 | whitelist | 1/1 | 51 | 51 | 0 | 0 | 1.000 | 1.000 |
| CWE-523 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-526 | whitelist | 3/3 | 34 | 34 | 0 | 0 | 1.000 | 1.000 |
| CWE-533 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-534 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-535 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-539 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-546 | whitelist | 1/1 | 85 | 85 | 0 | 0 | 1.000 | 1.000 |
| CWE-549 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-563 | whitelist | 7/7 | 218 | 214 | 4 | 492 | 0.982 | 0.313 |
| CWE-570 | whitelist | 3/3 | 16 | 16 | 0 | 1 | 1.000 | 0.969 |
| CWE-571 | whitelist | 2/2 | 16 | 1 | 15 | 0 | 0.062 | 1.000 |
| CWE-572 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-579 | whitelist | 2/2 | 1 | 1 | 0 | 0 | 1.000 | 1.000 |
| CWE-584 | whitelist | 3/3 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-586 | whitelist | 4/4 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-597 | whitelist | 1/1 | 17 | 17 | 0 | 30 | 1.000 | 0.362 |
| CWE-598 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-600 | whitelist | 1/1 | 1 | 1 | 0 | 0 | 1.000 | 1.000 |
| CWE-601 | whitelist | 2/2 | 549 | 276 | 273 | 126 | 0.503 | 0.878 |
| CWE-605 | whitelist | 1/1 | 17 | 17 | 0 | 1 | 1.000 | 0.979 |
| CWE-606 | whitelist | 1/1 | 732 | 420 | 312 | 588 | 0.574 | 0.741 |
| CWE-613 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-614 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-615 | whitelist | 2/2 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-643 | whitelist | 1/1 | 732 | 375 | 357 | 150 | 0.512 | 0.934 |
| CWE-681 | whitelist | 1/1 | 51 | 51 | 0 | 90 | 1.000 | 0.362 |
| CWE-690 | whitelist | 2/2 | 488 | 241 | 247 | 342 | 0.494 | 0.774 |
| CWE-698 | whitelist | 1/1 | 17 | 16 | 1 | 1 | 0.941 | 0.979 |
| CWE-759 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-760 | whitelist | 1/1 | 17 | 17 | 0 | 0 | 1.000 | 1.000 |
| CWE-789 | all | 2/2 | 2579 | 1271 | 1308 | 597 | 0.493 | 0.878 |
| CWE-835 | whitelist | 1/1 | 6 | 6 | 0 | 0 | 1.000 | 1.000 |

### VALID ZERO -- Rule exists, ran, found nothing  (13 categories)

| CWE | config | rules on | vulnerable | TP | FN | FP | TP cov | TN cov |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CWE-197 | whitelist | 1/1 | 2013 | 0 | 2013 | 0 | 0.000 | 1.000 |
| CWE-248 | whitelist | 1/1 | 1 | 0 | 1 | 1 | 0.000 | 0.500 |
| CWE-378 | whitelist | 1/1 | 17 | 0 | 17 | 0 | 0.000 | 1.000 |
| CWE-491 | whitelist | 2/2 | 1 | 0 | 1 | 0 | 0.000 | 1.000 |
| CWE-500 | whitelist | 1/1 | 1 | 0 | 1 | 0 | 0.000 | 1.000 |
| CWE-561 | whitelist | 1/1 | 1 | 0 | 1 | 0 | 0.000 | 1.000 |
| CWE-568 | whitelist | 1/1 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-580 | whitelist | 1/1 | 1 | 0 | 1 | 0 | 0.000 | 1.000 |
| CWE-585 | whitelist | 1/1 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-609 | whitelist | 2/2 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-674 | whitelist | 2/2 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-764 | whitelist | 1/1 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-833 | whitelist | 1/1 | 6 | 0 | 6 | 0 | 0.000 | 1.000 |

### COVERAGE GAP -- No rule at all  (22 categories)

| CWE | config | rules on | vulnerable | TP | FN | FP | TP cov | TN cov |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CWE-23 | whitelist | 0/0 | 732 | 0 | 732 | 0 | 0.000 | 1.000 |
| CWE-36 | whitelist | 0/0 | 732 | 0 | 732 | 0 | 0.000 | 1.000 |
| CWE-80 | all | 0/0 | 1098 | 0 | 1098 | 0 | 0.000 | 1.000 |
| CWE-81 | all | 0/0 | 549 | 0 | 549 | 0 | 0.000 | 1.000 |
| CWE-83 | all | 0/0 | 549 | 0 | 549 | 0 | 0.000 | 1.000 |
| CWE-111 | whitelist | 0/0 | 1 | 0 | 1 | 0 | 0.000 | 0 |
| CWE-315 | whitelist | 0/0 | 61 | 0 | 61 | 0 | 0.000 | 1.000 |
| CWE-321 | whitelist | 0/0 | 61 | 0 | 61 | 0 | 0.000 | 1.000 |
| CWE-486 | whitelist | 0/0 | 17 | 0 | 17 | 0 | 0.000 | 1.000 |
| CWE-499 | whitelist | 0/0 | 0 | 0 | 0 | 0 | 0 | 0 |
| CWE-506 | whitelist | 0/0 | 116 | 0 | 116 | 0 | 0.000 | 1.000 |
| CWE-510 | whitelist | 0/0 | 66 | 0 | 66 | 0 | 0.000 | 1.000 |
| CWE-566 | whitelist | 0/0 | 61 | 0 | 61 | 0 | 0.000 | 1.000 |
| CWE-581 | whitelist | 0/0 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-582 | whitelist | 0/0 | 1 | 0 | 1 | 0 | 0.000 | 1.000 |
| CWE-607 | whitelist | 0/0 | 1 | 0 | 1 | 0 | 0.000 | 1.000 |
| CWE-617 | whitelist | 0/0 | 34 | 0 | 34 | 0 | 0.000 | 1.000 |
| CWE-667 | whitelist | 0/0 | 1 | 0 | 1 | 0 | 0.000 | 1.000 |
| CWE-765 | whitelist | 0/0 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-772 | whitelist | 0/0 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-775 | whitelist | 0/0 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |
| CWE-832 | whitelist | 0/0 | 2 | 0 | 2 | 0 | 0.000 | 1.000 |

### Totals

- categories measured: 112
- with a rule that ran: 90
- with no rule at all: 22
- vulnerable methods covered: 22359 of 40184 (0.556)

