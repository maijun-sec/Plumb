# R654 plan

## Goal

Generalize R653 (CWE190/191 cross-CWE noise filter) to all 17 numeric / taint
CWE families. Each CWE has a "primary" checker; fires on GOOD cases from
non-primary checkers are cross-CWE noise from the same setup code (read from
user input, etc.) that doesn't represent the CWE's bug.

## Per-CWE primary checkers

| CWE | Primary checker(s) | Drop if only non-primary fires? |
|-----|--------------------|---------------------------------|
| CWE89 SQL_Injection | plumb.taint.sql-injection | yes |
| CWE129 Array_Index | plumb.numeric.unchecked-array-index, loose.numeric.unchecked-array-index | yes |
| CWE113 HTTP_Response_Splitting | plumb.taint.http-response-splitting | yes |
| CWE369 Divide_by_Zero | plumb.numeric.divide-by-zero, loose.numeric.divide-by-zero | yes |
| CWE400 Resource_Exhaustion | plumb.dfa.resource-exhaustion | yes |
| CWE606 Unchecked_Loop_Condition | plumb.dfa.unchecked-loop-condition | yes |
| CWE134 Uncontrolled_Format_String | plumb.taint.format-string, loose.numeric.format-string | yes |
| CWE90 LDAP_Injection | plumb.taint.ldap-injection | yes |
| CWE15 External_Control | plumb.taint.external-config-setting | yes |
| CWE23/36 Path_Traversal | plumb.taint.path-traversal | yes |
| CWE470 Unsafe_Reflection | plumb.taint.unsafe-reflection | yes |
| CWE789 Uncontrolled_Mem_Alloc | plumb.taint.uncontrolled-mem-alloc | yes |
| CWE197 Numeric_Truncation | plumb.numeric.unchecked-numeric-truncation, loose.numeric.truncation | yes |
| CWE80/81/83 XSS | plumb.taint.xss-injection | yes |
| CWE190/191 (already R653) | loose.numeric.integer-overflow, etc. | already done |

## Cross-CWE noise checkers (the ones that fire spuriously)

- `plumb.resource.leak` (the biggest FP source for CWE89/129/113/369/400/606/134/90/23/36)
- `plumb.dfa.null-deref-from-return`
- `plumb.unchecked-return`
- `plumb.ai.suspicious-comment`
- `plumb.ai.comment-info-leak`
- `plumb.insecure-comms`
- `plumb.taint.trust-boundary-violation`

## Expected impact

From the analysis of r652-fixed-findings.jsonl:
- CWE89 GOOD: 4980 fired, of which ~4340 are null-deref + 2590 resource.leak + 3615 unchecked-return + 1980 suspicious-comment = high overlap
- CWE129 GOOD: 6012 fired
- CWE113 GOOD: 2098 fired
- CWE369 GOOD: 4170 fired
- CWE400 GOOD: 2450 fired
- CWE606 GOOD: 996 fired
- CWE134 GOOD: 852 fired
- CWE90 GOOD: 600 fired
- CWE15 GOOD: 600 fired
- CWE23 GOOD: 600 fired
- CWE36 GOOD: 600 fired
- CWE470 GOOD: 600 fired
- CWE789 GOOD: 2088 fired
- CWE197 GOOD: 1617 fired
- CWE80 GOOD: 324 fired
- CWE81 GOOD: 162 fired
- CWE83 GOOD: 162 fired

If R654 drops ~80% of these (as R653 did for CWE190/191), total FP reduction
expected: 13,000-18,000.

## Implementation

R654 = generalize R653 to all 17 CWE. Same pattern: for each GOOD case, if
NONE of the fired checkers is in the CWE's primary set, drop the fire.
