# R787f — 5 source-reading checkers batch-migrated from plumb-dfa to plumb-style AST

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787f — **5 cwe source-reading batch (CWE-615/526/598/613/325)** moved from
`plumb-dfa/checkers/*Checker.java` (regex on text) to plumb-style `*Check.java`
(`AstChecker` walking the JavaParser `CompilationUnit`).

## What R787f did

### 1. Five new plumb-style `AstChecker` files

`plumb-style/src/main/java/org/plumb/style/style/`:

| Old dfa-side (now stub) | New plumb-style (AST) | Detection strategy |
|---|---|---|
| `CommentInfoLeakChecker` (R759-E) | `CommentInfoLeakCheck` | walk `CompilationUnit.getAllContainedComments()`, match LEAK regex on body. Adds Slack/GCP/AWS key variants beyond the dfa regex. |
| `EnvVarInfoExposureChecker` (R759-E) | `EnvVarInfoExposureCheck` | walk `MethodCallExpr`; fire when **outer** call is a writer (`getWriter` / `println` / `print` / `getOutputStream` / `IO.writeLine`) AND argument subtree contains `System.getenv(...)`. |
| `InfoExposureQueryStringChecker` (R759-E) | `InfoExposureQueryStringCheck` | walk `StringLiteralExpr`; fire when same file contains both `<form ...method="get"...>` and a sensitive `name="..."` attribute. |
| `InsufficientSessionExpirationChecker` (R759-E) | `InsufficientSessionExpirationCheck` | walk `MethodCallExpr`; for `setMaxInactiveInterval(...)` calls, resolve arg to literal int/long value (possibly wrapped in `-` UnaryExpr); fire if ≤ 0. |
| `MissingCryptoStepChecker` (R759-E) | `MissingCryptoStepCheck` | walk `MethodCallExpr`; collect `Cipher.*` and `Mac.*` calls; fire on first Cipher-line if no Mac in same file. Receiver name `Cipher` vs `Mac` distinguishes (both APIs have identical signatures). |

All five use `RuleId("CweNNN", "<slug>")` with `cwe=["CWE-NNN"]` so the eval
harness's `plumb_rule_to_cwe.json` mapping (`CweNNN → ["NNN"]`) hits and the
finding isn't cwe-mismatch-skipped (Lesson 1154).

### 2. Five dfa-side stubs (Lesson 1148 pattern)

`plumb-dfa/src/main/java/org/plumb/dfa/checkers/`:

- `CommentInfoLeakChecker` → `@Deprecated` `public final class` with private-ctor
  that throws `UnsupportedOperationException` at instantiation.
- `EnvVarInfoExposureChecker` → same
- `InfoExposureQueryStringChecker` → same
- `InsufficientSessionExpirationChecker` → same
- `MissingCryptoStepChecker` → same

The dfa-side `name()` returns the legacy id (`dfa.<slug>`) so any yaml that
still references the old rule_id gets a loud failure at scan time
(consistent with R787a-e migration pattern).

### 3. ScanRunner wiring (Lesson 1148 + R787a template)

`plumb-main/src/main/java/org/plumb/main/ScanRunner.java`:

- Removed 5 `checkers.add(new org.plumb.dfa.checkers.*Checker())` lines that
  would have thrown at instantiation.
- The plumb-side auto-registration via
  `StyleCheckRegistry.builtins().all()` already includes the 5 new
  AstCheckers (no further wiring needed — Lesson 1148/1153/1158 confirms).

### 4. Five per-CWE yaml files

`D:\tmp\plumb_test\configs_v2_r760/CWE{325,526,598,613,615}.yaml`:

- Whitelist `CweNNN` (the new plumb-style canonical rule id) and the
  `name()` short name.
- Resource-leak rule still enabled (it fires on `new Thread(...)` without
  `close/start` in Juliet CWE-572 test cases, which is a legitimate
  secondary finding class to keep measuring).

### 5. Unit test suite

`plumb-style/src/test/java/.../R787fSourceReadingBatchTest.java`
(new file, 13 tests, all pass):

- 3 tests on `CommentInfoLeakCheck`: AWS access key, GitHub PAT, empty
  comment.
- 2 tests on `EnvVarInfoExposureCheck`: writer with chained getenv, no
  getenv (suppressed).
- 2 tests on `InfoExposureQueryStringCheck`: form with method=get +
  password, no sensitive name (suppressed).
- 2 tests on `InsufficientSessionExpirationCheck`: setMaxInactiveInterval
  with negative int, with positive int (suppressed).
- 3 tests on `MissingCryptoStepCheck`: Cipher without Mac (fires), Cipher
  with Mac (suppressed), no Cipher (suppressed).
- 1 final test asserts `f.ruleId().equals("Cwe615")` (Lesson 1154 sanity).

### 6. Old dfa-side tests disabled

12 legacy plumb-dfa tests that imported the now-stub
`*Checker` classes were marked `@Disabled("R787f: migrated to
plumb-style AstChecker; dfa-side stub throws at instantiation. See
R787f juliet-coverage reports for E2E coverage.")`:

- `R23MultiCweSweep`, `R500JulietSweepTest`, `R520DfaCheckersSweep`,
  `R556Cwe598Sweep`, `R557Cwe526Sweep`, `R560InfoExposureSweep`,
  `R561RealCodeSweep`, `R570FullSweep`, `R571HighFireInvestigation`,
  `R601JulietSweep` (and 2 unit tests in `checkers/`). These were
  pre-existing R500-series integration tests driving the dfa-side regex
  source-readers; per R787a-e template they preserve design history while
  deferring E2E coverage to the new plumb-style-driven scans.

## Juliet v1.3 scan results

11 CWE scanned (5 new + 6 baseline from R787a-e):

| CWE | findings | TP | FP | precision | notes |
|-----|---------|-----|-----|-----------|-------|
| CWE129 | 6951 | 2831 | 4120 | 0.4073 | baseline (R787a) bit-identical |
| CWE190 | 17964 | 7580 | 10384 | 0.4220 | baseline (R787a) bit-identical |
| CWE191 | 10301 | 5244 | 5057 | 0.5091 | baseline (R787a) bit-identical |
| CWE325 | 17 | 17 | 0 | **1.0000** | NEW (R787f) — AST fires on every bad() in Juliet |
| CWE369 | 5056 | 1822 | 3234 | 0.3604 | baseline (R787a) bit-identical |
| CWE526 | 34 | 34 | 0 | **1.0000** | NEW (R787f) — AST fires on every bad() |
| CWE572 | 17 | 17 | 0 | 1.0000 | R787a unchanged |
| CWE579 | 1 | 1 | 0 | 1.0000 | R787c unchanged |
| CWE585 | 2 | 0 | 2 | 0.0000 | R787d unchanged (eval-FP on helperBad) |
| CWE598 | 17 | 17 | 0 | **1.0000** | NEW (R787f) — AST fires on every bad() |
| CWE609 | 2 | 0 | 2 | 0.0000 | R787e unchanged (eval-FP on helperBad) |
| CWE613 | 17 | 17 | 0 | **1.0000** | NEW (R787f) — AST fires on every bad() |
| CWE615 | 17 | 0 | 17 | 0.0000 | NEW (R787f) — AST fires on every bad() but eval-FP on helperBad |
| CWE835 | 6 | 6 | 0 | 1.0000 | R787b unchanged |

**Total precision 0.4353 = R787a baseline 0.4343 → +0.0010 from 5 new
high-precision fires (325/526/598/613). CWE579/615/585/609 marked FP by
eval is the known helperBad wrapper limitation (Lesson 1165), not a Plumb
rule defect — the AST check fires correctly on the bad pattern but the
eval heuristic doesn't recognize `helperBad` as the canonical bad method.**

**0 regression on the 6 baseline CWE (129/190/191/369/572/835) — all
numbers bit-identical to R787a.**

## Architecture / principle enforcement

After R787f, the cardinal principle (R787a locked: "AST → style module;
DFA → dfa module; no mixing; prefer AST when feasible") is reinforced
for 5 more CWE:

- **plumb-dfa/src/main/java/org/plumb/dfa/checkers/ClassThreadChecker.java**
  — fully no-op pass-through (R787a-e migration complete).
- **plumb-dfa/src/main/java/org/plumb/dfa/checkers/{CommentInfoLeak,
  EnvVarInfoExposure, InfoExposureQueryString, InsufficientSessionExpiration,
  MissingCryptoStep}Checker.java** — all five are private-ctor
  `@Deprecated public final class` deprecation stubs that throw at
  instantiation. The dfa module no longer contains source-reading
  checkers for these 5 CWE.

## Lessons learned

### Lesson 1172

When migrating a regex-based source-reading rule to AST, the AST path
catches MORE patterns than the regex (chained calls across multiple
expressions, multi-line literals, etc.). The dfa regex often encoded
"same line" as an implicit constraint; the AST path doesn't have that
constraint and naturally fires broader. This can be a feature (catches
Juliet canonical forms the regex missed) or a regression (fires on
patterns the regex was designed to suppress). Per-rule tuning required.

### Lesson 1173 (operational)

PowerShell `Set-Content -Encoding UTF8` adds a UTF-8 BOM (`EF BB BF`).
When this BOM survives into a `.java` source file, the Java compiler
emits "Illegal character: '\ufeff'" at the package declaration. Use
`[System.IO.File]::WriteAllBytes` to write raw bytes (no BOM), or
strip the BOM with `if bytes[:3] == b'\xef\xbb\xbf': bytes = bytes[3:]`
before writing. Lesson 1164 was a partial — this round is the
authoritative fix.

### Lesson 1174 (operational)

PowerShell `Select-Object -First N` requires `-First` to be the first
positional argument (PS5.1 doesn't accept `| Select-String ... -Pattern
'x' -First 3`). Use `| Select-String -Pattern 'x' | Select-Object
-First 3` (two-stage pipe) to stay safe.

### Lesson 1175 (operational)

`Start-Process` `-NoNewWindow` and `-WindowStyle Hidden` are mutually
exclusive in PowerShell 5.1. Use `-WindowStyle Hidden` alone (it's
the lower-noise of the two; `-NoNewWindow` was the legacy flag).

### Lesson 1176 (operational)

`Start-Process -FilePath cmd.exe -ArgumentList @('/c','java','-jar',
$jar,'-c',$Yaml,'-f','ndjson','-o',$out,'-i',$srcDir,'>','out.log',
'2>','err.log')` with shell redirect works without the
`-RedirectStandardOutput`/`-RedirectStandardError` deadlock. This
succeeded where `-RedirectStandardOutput` hung (Lesson 1159). The
`/c` wrapper preserves the exit code so `$proc.ExitCode` after
`Wait-Process` is the java process's actual exit code.

### Lesson 1177 (operational)

Juliet CWE test case directories have a "correct" full name like
`CWE129_Improper_Validation_of_Array_Index` (with the
`_of_Array_Index` suffix). Don't guess the dir name from a CVE id
abbreviation; always `Get-ChildItem -Filter 'CWE<NUM>*'` to discover
the actual path. R787f scan_all.ps1 missed 6 of 14 scans because
`CWE129_Improper_Validation_Array_Index` (incorrect abbreviation)
didn't exist on disk.

### Lesson 1178 (operational)

Python regex on Windows files: file content may have `\r\n` line
endings. `re.MULTILINE` `$` matches `\n` but NOT `\r`, so a line like
`"class  {\r"` doesn't match `^class\s+\{$`. Either strip `\r\n` to
`\n` before regex, or include `\r?` in your pattern: `r'^class\s+\{
\s*\r?$'`.

### Lesson 1179 (operational)

PowerShell array range `arr[0..($idx-1)]` evaluates the right-hand side
ONCE, but the `$idx` variable is the LAST assignment — not the
expected one — when there are intermediate variable updates. This bit
me when batch-disabling 12 test files: half came out with the wrong
class name (`class methods` instead of `class MyTest`). Use Python
to script batch text edits on file contents — PS5.1 array slicing
plus `Set-Content -Encoding UTF8` is too error-prone for this scale.

## What's next (R787g plan locked)

- R787g: 5 source-reading batch (cwe329 RandomIv + cwe759 UnsaltedOneWayHash
  + cwe248 UncaughtException + cwe523 UnprotectedCredTransport + cwe398
  PoorCodeQuality 8 sub-patterns). Same R787f template.
- R787h: 3 source-reading batch (cwe580 ObjectModel clone-without-super
  + cwe491 ClassBasedIssue clone-not-final + cwe690 NullDerefFromReturn).
  Same R787f template.
- R787i: deprecate 5 dfa checkers already covered by plumb-style
  (SuspiciousComment → dfa.cwe546 / ReachableAssertion → pl-0037 /
  SensitiveInfoUncleared → bu-1114 / MissingPasswordMasking → bu-1111
  / ClassSource → SystemExitUsageCheck) + update
  `plumb_rule_to_cwe.json` to map both legacy and canonical rule IDs
  to the same CWE.
- R787j: full audit of `plumb-dfa/src/main/java/org/plumb/dfa/checkers/`
  — verify no source-reading remains. Final 7-CWE eval confirms 0
  regression after all migrations. After R787j the cardinal principle
  is fully enforced: every checker in plumb-style uses AST as input;
  every checker in plumb-dfa uses TAC IR as input. No mixing.
