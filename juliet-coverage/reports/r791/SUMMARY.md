# R791: Drop CweNNN legacy aliases + rewrite AST checker DEFAULT_RULE to canonical

**Date:** 2026-10-12
**Rounds:** R791 (this round)

## Goal

The R788 canonical `plumb.style.<slug>` namespace was paired with
legacy `CweNNN` / `dfa.<slug>` aliases on the 18 R787a-j + R788i rules
so the existing AST checkers could keep their hardcoded
`new RuleId("Cwe572", ...)` DEFAULT_RULE while the engine emitted the
canonical form. R791 was the next step: **drop the legacy aliases
entirely, force every AST checker to use the canonical id directly,
and make the yaml whitelist canonical-only**.

The expected outcome is **zero precision change** — the emit pipeline
should look identical (canonical `plumb.style.<slug>` rule_id, no
alias resolution in the hot path), and the 22-CWE eval prec should
match the R790 value of **0.4378**.

## What changed

### 1. AST checker DEFAULT_RULE rewrite (19 files)

For every R787a-j + R788i checker, the
`private static final RuleDef DEFAULT_RULE = new RuleDef(
   new RuleId("CweNNN", "..."), ...)` literal was rewritten to
`new RuleId("plumb.style.<slug>", "<NAME>_STYLE")`. The 19 files
edited:

| File | old `CweNNN` id | new canonical id |
|---|---|---|
| CallToThreadRunCheck | `Cwe572` | `plumb.style.call-to-thread-run` |
| InfiniteLoopCheck | `Cwe835` | `plumb.style.infinite-loop` |
| NonSerializableInSessionCheck | `Cwe579` | `plumb.style.non-serializable-in-session` |
| EmptySyncBlockCheck | `Cwe585` | `plumb.style.empty-sync-block` |
| DoubleCheckedLockingCheck | `Cwe609` | `plumb.style.double-checked-locking` |
| CommentInfoLeakCheck | `Cwe615` | `plumb.style.comment-info-leak` |
| EnvVarInfoExposureCheck | `Cwe526` | `plumb.style.env-var-info-exposure` |
| InfoExposureQueryStringCheck | `Cwe598` | `plumb.style.info-exposure-querystring` |
| InsufficientSessionExpirationCheck | `Cwe613` | `plumb.style.insufficient-session-expiration` |
| MissingCryptoStepCheck | `Cwe325` | `plumb.style.missing-crypto-step` |
| RandomIvCheck | `Cwe329` | `plumb.style.random-iv-cbc` |
| UnsaltedOneWayHashCheck | `Cwe759` | `plumb.style.unsalted-one-way-hash` |
| UnprotectedCredTransportCheck | `Cwe523` | `plumb.style.unprotected-cred-transport` |
| UncaughtExceptionCheck | `Cwe600` | `plumb.style.cwe600-uncaught-exception` |
| PoorCodeQualityCheck | `Cwe398` | `plumb.style.poor-code-quality` |
| ObjectModelCheck | `Cwe580` | `plumb.style.cwe580-clone-without-super` |
| ClassBasedIssueCheck | `Cwe491` | `plumb.style.class-based-issue-491` |
| NullDerefFromReturnCheck | `Cwe690` | `plumb.style.null-deref-from-return` |
| SuspiciousCommentCheck | `dfa.cwe546-suspicious-comment` | `plumb.style.suspicious-comment` |

Script: `D:\tmp\plumb_test\scripts\r791_rewrite_checker_defaults.py`
(idempotent; rerun-safe).

### 2. Unit-test expectation rewrite (9 files, 40 replacements)

The unit tests in `plumb-style/src/test/java/.../style/R787*.java`
and `R790*.java` had hard-coded `isEqualTo("CweNNN")` assertions
that pinned the legacy emit. They were updated to expect the canonical
form, mirroring the mapping above. 40 string-literal replacements
across 9 test files (none of the legacy class-name fixtures like
`Cwe615Pwd` were touched — only the rule_id expected-value strings).

Script: `D:\tmp\plumb_test\scripts\r791_rewrite_test_expectations.py`
(idempotent; rerun-safe).

### 3. ScanRunner.isRuleIdAllowed: canonical-key ending-in-bare loop

Once the AST checkers emit canonical ids but their `name()` method
still returns the bare slug (`"random-iv-cbc"`, `"comment-info-leak"`,
etc.), the pre-emit whitelist filter at `ScanRunner.java:1504` had
to accept both. The pre-R791 logic was:

```java
if (config.enabledRuleNames.contains(ruleId)) return true;
String bare = stripPlumbAndPlPrefix(ruleId);  // plumb.style.X -> X
if (config.enabledRuleNames.contains(bare)) return true;
return false;
```

When the user writes only the canonical form in yaml
(`plumb.style.random-iv-cbc`), and `c.name()` is the bare slug
(`random-iv-cbc`), the second `contains` check fails (yaml has the
canonical, not the bare). R791 added a third check that walks the
yaml whitelist and accepts the rule when any enabled entry ends with
`.<bare>`:

```java
for (String enabled : config.enabledRuleNames) {
    if (enabled.endsWith("." + bare)) return true;
}
```

This makes the canonical-form yaml self-sufficient: no need to
also list `random-iv-cbc` and `RandomIvCheck` for the same rule.

### 4. RuleRegistry aliases removed (already done in earlier pass)

The 18 R788 `register(def(...))` blocks were updated earlier in the
R791 sequence to use `java.util.Collections.emptyList()` for
`aliases` (Lesson 1194: `List.of()` returns `List<Object>`, not
`List<String>`, so the 9-arg `def(...)` overload needs an explicit
typed empty list). The R791 fix in the checkers makes the
registry-side alias-removal a no-op for emission: the emitted
finding's rule_id is now the canonical id directly, with no
`resolveViaAlias()` indirection needed.

## Verification

### Unit tests

`mvn test -pl plumb-style -am -Dtest=R787*Test,R790*Test` —
**84 tests pass, 0 failures**:

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- R787bInfiniteLoopCheckTest
[INFO] Tests run:  9, Failures: 0, Errors: 0, Skipped: 0 -- R787cNonSerializableInSessionCheckTest
[INFO] Tests run:  8, Failures: 0, Errors: 0, Skipped: 0 -- R787dEmptySyncBlockCheckTest
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- R787eDoubleCheckedLockingCheckTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- R787fSourceReadingBatchTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- R787gSourceReadingBatchTest
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- R787hSourceReadingBatchTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- R790cNullDerefFromReturnCheckTest
[INFO] Tests run:  1, Failures: 0, Errors: 0, Skipped: 0 -- R790R788IntegrationTest
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -- R790UncaughtExceptionCheckTest
[INFO] Tests run: 84, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 22-CWE Juliet eval (whitelist)

| CWE    | findings |  TP  |  FP  | prec  | vs R790 |
|--------|---------:|-----:|-----:|------:|---------|
| CWE129 |     6951 | 2831 | 4120 | 0.407 | 0 (baseline) |
| CWE190 |    17964 | 7580 |10384 | 0.422 | 0 (baseline) |
| CWE191 |    10301 | 5244 | 5057 | 0.509 | 0 (baseline) |
| CWE325 |       17 |   17 |    0 | 1.000 | 0 (R787f) |
| CWE329 |       18 |   18 |    0 | 1.000 | 0 (R790a) |
| CWE369 |     5056 | 1822 | 3234 | 0.360 | 0 (baseline) |
| CWE398 |       87 |   85 |    2 | 0.977 | 0 (R787g) |
| CWE491 |        1 |    0 |    1 | 0.000 | 0 (R790d eval-FP) |
| CWE523 |       17 |   17 |    0 | 1.000 | 0 (R787g) |
| CWE526 |       34 |   34 |    0 | 1.000 | 0 (R787f) |
| CWE572 |       17 |   17 |    0 | 1.000 | 0 (R787a) |
| CWE579 |        1 |    1 |    0 | 1.000 | 0 (R787c) |
| CWE580 |        1 |    0 |    1 | 0.000 | 0 (R790d eval-FP) |
| CWE585 |        2 |    0 |    2 | 0.000 | 0 (R790d eval-FP) |
| CWE598 |       17 |   17 |    0 | 1.000 | 0 (R787f) |
| CWE600 |        1 |    1 |    0 | 1.000 | 0 (R790b) |
| CWE609 |        2 |    0 |    2 | 0.000 | 0 (R790d eval-FP) |
| CWE613 |       17 |   17 |    0 | 1.000 | 0 (R787f) |
| CWE615 |       17 |    0 |   17 | 0.000 | 0 (R790d eval-FP) |
| CWE690 |      165 |   97 |   68 | **0.588** | 0 (R790c) |
| CWE759 |       17 |   17 |    0 | 1.000 | 0 (R787g) |
| CWE835 |        6 |    6 |    0 | 1.000 | 0 (R787b) |
| **Total** | **40,709** | **17,821** | **22,888** | **0.4378** | **0** |

**R791 is bit-identical to R790** on every CWE. No precision change.
This is the desired outcome: the cleanup didn't shift any findings
or prec.

## Files changed (R791)

- 19 AST checker DEFAULT_RULE literals
- 9 test files' expected-value strings (40 replacements)
- `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` — 12 lines
  added in `isRuleIdAllowed()` for the canonical-key ending-in-bare
  loop

## Scripts (idempotent, all rerun-safe)

- `D:\tmp\plumb_test\scripts\r791_rewrite_checker_defaults.py`
- `D:\tmp\plumb_test\scripts\r791_rewrite_test_expectations.py`
- `D:\tmp\plumb_test\scripts\r791_scan_all.ps1`
- `D:\tmp\plumb_test\scripts\r791_scan_heavy.ps1`
- `D:\tmp\plumb_test\scripts\r787f_scan_heavy.ps1` (timeout-3600s for CWE129/190/191)

## Lessons (R791-specific)

**Lesson 1195 (architecture)**: When an R-N canonicalization round
reaches the point of "remove legacy aliases from the registry", the
AST checkers' hardcoded `DEFAULT_RULE.ruleId()` literals also need
to be updated to the canonical form, otherwise the emit pipeline
silently breaks. The `StyleContext.resolveViaAlias()` indirection
that R788 introduced is what made this look fine in R788 unit tests
and even the first R791 regression check — the alias was doing the
work, not the registry literal. Lesson: when you remove a layer of
indirection, also remove the dependency on it.

**Lesson 1196 (operational)**: The pre-emit whitelist filter in
`ScanRunner.isRuleIdAllowed(ruleId)` is called with **two different
shapes of input**:
- (a) a `c.name()` from an AST checker — typically the bare slug
  (`"random-iv-cbc"`, `"comment-info-leak"`)
- (b) a `f.rule().ruleId().id()` from a finding — typically the
  canonical form (`"plumb.style.random-iv-cbc"`, ...)

The pre-R791 logic only normalized case (b) by stripping prefixes
and matched against the yaml whitelist. For case (a), a yaml written
in canonical form would never match the bare slug. The fix is the
`endsWith("." + bare)` loop — a yaml entry
`plumb.style.random-iv-cbc` ends with `.random-iv-cbc`, so it
matches the bare slug `random-iv-cbc`. This makes the canonical
form sufficient and lets us drop the legacy `CweNNN` yaml keys.

**Lesson 1197 (operational)**: After R791's checker DEFAULT_RULE
rewrite, the on-disk ndjson from `scan/r788/` predates the fix and
**looks identical to a regression** when comparing rule_id
distributions. Always cross-check by reading the **scan timestamp** in
the file's `LastWriteTime` and the **jar's `LastWriteTime`** to make
sure you are evaluating the same build the code is in. The first
debug pass mistakenly evaluated `scan/r788/CWE329.ndjson` (12:31
build, missing-canonical state) against the new jar (13:09 build,
canonical-state) and concluded "still 0 findings" — when in fact
running the new jar against the Juliet CWE329 directory produces
18 canonical `plumb.style.random-iv-cbc` findings, matching the
R790 value exactly.

## Future R-Rounds

- **R792**: CWE690 remaining 68 FP are mostly `goodG2B` /
  `goodG2B1` / `goodG2B2` family where Juliet puts the "good
  source" inside `data = helper.getStringBuilderGood()`. Cross-
  function nullness analysis is the only way to suppress these
  without Juliet-hardcoding.
- **R793**: CWE190/191 over-firing on boundary rule (~30k of 40k
  total findings at 0.4 prec). Requires TAC-level value-range
  tracking.
- **R794** (optional): drop the dead `StyleContext.resolveViaAlias()`
  indirection now that no checker relies on it.
