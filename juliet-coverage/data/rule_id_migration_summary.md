# Rule ID Migration Summary

Total migrations: **694**
Collisions (multiple old → same new): **173**

## Distribution by target domain

- `ast`: 569
- `taint`: 60
- `style`: 22
- `boundary`: 19
- `dfa`: 15
- `info`: 5
- `naming`: 1
- `crypto`: 1
- `resource`: 1
- `concurrency`: 1

## Distribution by detect_reason

- pl- default: 179
- UPPER_SNAKE default: 128
- PascalCase default: 83
- bu- default: 46
- lowercase-kebab default: 39
- plumb.taint.* → mapped to plumb.taint.*: 32
- bu- slug taint: 28
- fallback: 17
- bu- slug style: 15
- dfa.* generic fallback: 15
- plumb.dfa.* → mapped to plumb.dfa.*: 13
- cov- default (need manual review): 12
- loose.* prefix: 9
- plumb.style.* → mapped to plumb.style.*: 7
- CweXXX malformed: 6
- bu- slug ast: 5
- plumb.ai.* → mapped to plumb.ast.*: 5
- plumb.numeric.* → mapped to plumb.boundary.*: 5
- plumb.ast.* → mapped to plumb.ast.*: 4
- plumb.boundary.* → mapped to plumb.boundary.*: 4
- dfa.cwe prefix + CWE-398: 3
- dfa.cwe prefix + CWE-390: 2
- plumb.pointer.* → mapped to plumb.dfa.*: 2
- plumb.sensitive.* → mapped to plumb.info.*: 2
- UPPER_SNAKE naming/style: 1
- dfa.cwe prefix + CWE-193: 1
- dfa.cwe prefix + CWE-325: 1
- dfa.cwe prefix + CWE-379: 1
- dfa.cwe prefix + CWE-382: 1
- dfa.cwe prefix + CWE-396: 1
- dfa.cwe prefix + CWE-397: 1
- dfa.cwe prefix + CWE-404: 1
- dfa.cwe prefix + CWE-477: 1
- dfa.cwe prefix + CWE-478: 1
- dfa.cwe prefix + CWE-481: 1
- dfa.cwe prefix + CWE-482: 1
- dfa.cwe prefix + CWE-483: 1
- dfa.cwe prefix + CWE-506: 1
- dfa.cwe prefix + CWE-510: 1
- dfa.cwe prefix + CWE-511: 1
- dfa.cwe prefix + CWE-526: 1
- dfa.cwe prefix + CWE-535: 1
- dfa.cwe prefix + CWE-539: 1
- dfa.cwe prefix + CWE-546: 1
- dfa.cwe prefix + CWE-566: 1
- dfa.cwe prefix + CWE-570: 1
- dfa.cwe prefix + CWE-571: 1
- dfa.cwe prefix + CWE-572: 1
- dfa.cwe prefix + CWE-579: 1
- dfa.cwe prefix + CWE-580: 1
- dfa.cwe prefix + CWE-581: 1
- dfa.cwe prefix + CWE-585: 1
- dfa.cwe prefix + CWE-600: 1
- dfa.cwe prefix + CWE-609: 1
- dfa.cwe prefix + CWE-681: 1
- dfa.cwe prefix + CWE-835: 1
- plumb.resource.* → mapped to plumb.ast.*: 1

## Review priority (ast domain has 569 — likely over-classified)

Rules that ended up in `plumb.ast.*` should be reviewed for potential re-classification to:
- `plumb.concurrency.*` (multi-threading: deadlock, race, lock)
- `plumb.boundary.*` (numeric: overflow, divide-by-zero, float)
- `plumb.crypto.*` (encryption / keys)
- `plumb.info.*` (logging / error message exposure)
- `plumb.style.*` (pure code style: naming, formatting)
- `plumb.resource.*` (resource leaks)

Use `rule_id_review_chunks.csv` for batched review (30 rules per chunk).

## Collisions (old rules that map to same new id)

- `plumb.ast.explicit-finalize` ← 4 aliases: `EXPLICIT_FINALIZE`, `bu-1107-explicit-finalize`, `explicit-finalize`, `pl-0030-explicit-finalize`
- `plumb.ast.missing-break` ← 3 aliases: `MISSING_BREAK`, `cov-0001-missing-break`, `missing-break`
- `plumb.ast.null-deref-from-return` ← 3 aliases: `NullDerefFromReturn`, `dfa.null-deref-from-return`, `plumb.ast.null-deref-from-return`
- `plumb.ast.print-stack-trace` ← 3 aliases: `PRINT_STACK_TRACE`, `pl-0724-print-stack-trace`, `print-stack-trace`
- `plumb.ast.redirect-without-exit` ← 3 aliases: `REDIRECT_WITHOUT_EXIT`, `pl-0039-redirect-without-exit`, `redirect-without-exit`
- `plumb.ast.sensitive-info-uncleared` ← 3 aliases: `SensitiveInfoUncleared`, `bu-1114-sensitive-info-uncleared`, `dfa.sensitive-info-uncleared`
- `plumb.ast.uninit-nonnull` ← 3 aliases: `UNINIT_NONNULL`, `cov-uninit-nonnull`, `dfa.uninit-nonnull`
- `plumb.ast.unused-formal-parameter` ← 3 aliases: `UNUSED_FORMAL_PARAMETER`, `bu-1102-unused-formal-parameter`, `pl-0101-unused-formal-parameter`
- `plumb.ast.missing-password-masking` ← 3 aliases: `bu-1111-missing-password-masking`, `missing-password-masking`, `plumb.ai.missing-password-masking`
- `plumb.boundary.divide-by-zero` ← 3 aliases: `loose.numeric.divide-by-zero`, `plumb.boundary.divide-by-zero`, `plumb.numeric.divide-by-zero`
- `plumb.boundary.integer-overflow` ← 3 aliases: `loose.numeric.integer-overflow`, `plumb.boundary.integer-overflow`, `plumb.numeric.integer-overflow`
- `plumb.boundary.integer-underflow` ← 3 aliases: `loose.numeric.integer-underflow`, `plumb.boundary.integer-underflow`, `plumb.numeric.integer-underflow`
- `plumb.ast.ambiguous-precedence` ← 2 aliases: `AMBIGUOUS_PRECEDENCE`, `bu-0612-ambiguous-precedence`
- `plumb.ast.assignment-in-operand` ← 2 aliases: `ASSIGNMENT_IN_OPERAND`, `bu-0400-assignment-in-operand`
- `plumb.ast.compare-classes` ← 2 aliases: `COMPARE_CLASSES`, `pl-0028-compare-classes`
- `plumb.ast.compare-objects-with-equals` ← 2 aliases: `COMPARE_OBJECTS_WITH_EQUALS`, `pl-0154-compare-objects-with-equals`
- `plumb.ast.covariant-equals` ← 2 aliases: `COVARIANT_EQUALS`, `pl-0728-covariant-equals`
- `plumb.ast.ctor-calling-overridable` ← 2 aliases: `CTOR_CALLING_OVERRIDABLE`, `pl-0441-ctor-calling-overridable`
- `plumb.ast.equals-arg-type` ← 2 aliases: `EQUALS_ARG_TYPE`, `pl-0444-equals-arg-type`
- `plumb.ast.equals-avoid-null` ← 2 aliases: `EQUALS_AVOID_NULL`, `pl-0707-equals-avoid-null`

Total collided new_ids: 80 (out of 601 unique new_ids)
