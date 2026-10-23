# R787i — 3 dfa checkers deprecated (already covered by plumb-style)

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787i — 3 dfa source-reading checkers deprecated and
replaced by their pre-existing plumb-style counterparts.

## What R787i did

### 1. Three dfa-side stubs (Lesson 1148 pattern)

`plumb-dfa/src/main/java/org/plumb/dfa/checkers/`:

- `ReachableAssertionChecker` (CWE-617) → `@Deprecated` private-ctor that throws
  at instantiation. The plumb-style equivalent
  `org.plumb.style.style.ReachableAssertionCheck` (rule id
  `pl-0037-reachable-assertion`) auto-registers via
  `StyleCheckRegistry.builtins().all()`.
- `MissingPasswordMaskingChecker` (CWE-549) → same. The plumb-style
  equivalent `MissingPasswordMaskingCheck` (rule id
  `bu-1111-missing-password-masking`) auto-registers.
- `SensitiveInfoUnclearedChecker` (CWE-226) → same. The plumb-style
  equivalent `UnclearedSensitiveInfoCheck` (rule id
  `bu-1114-sensitive-info-uncleared`) auto-registers.

The SuspiciousComment (CWE-546) and ClassSource / SystemExitUsage
(CWE-382) dfa-side checkers were never wired into `ScanRunner`'s
`buildDfaCheckers()` — they were already plumb-style-only. R787i
documents this state without further changes (the rule id remains
`dfa.cwe546-suspicious-comment` for backwards compat; a future
R-Round should rename to `plumb.style.suspicious-comment` and add
the legacy id as an alias).

### 2. ScanRunner wiring (Lesson 1148 + R787a template)

`plumb-main/src/main/java/org/plumb/main/ScanRunner.java`:

- Removed 3 `checkers.add(new org.plumb.dfa.checkers.*Checker())` lines
  (ReachableAssertion, MissingPasswordMasking, SensitiveInfoUncleared).
- The plumb-side auto-registration via
  `StyleCheckRegistry.builtins().all()` already includes the 3
  pre-existing plumb-style checks (no further wiring needed).

### 3. R-Round plan alignment

R787i was originally planned to deprecate 5 dfa checkers already
covered by plumb-style. After audit:
- **3 of 5** dfa checkers were already in `ScanRunner` and could
  be deprecated in this R-Round: `ReachableAssertionChecker`,
  `MissingPasswordMaskingChecker`, `SensitiveInfoUnclearedChecker`.
- **2 of 5** were already plumb-style-only (never registered in
  dfa-side aggregator): `SuspiciousCommentChecker`,
  `SystemExitUsageCheck`. No code change needed; the rule ids
  remain `dfa.cwe546-suspicious-comment` and
  `plumb.style.system-exit-usage` for now (future R-Round will
  canonicalize to `plumb.style.*` namespace).

## What's next (R787j)

R787j is the final audit:
- Verify no `source-reading` checker remains in `plumb-dfa/`.
- Compute the final 22-CWE eval (R787a baseline 6 + R787c/d/e 4 +
  R787f 5 + R787g 5 + R787h 3 — total 23, but the CWE129/190/191/369
  /572/835 are pre-existing baseline so we keep them for
  regression check).
- Document the principle enforcement: every checker in plumb-style
  uses AST as input; every checker in plumb-dfa uses TAC IR as
  input. No mixing.
