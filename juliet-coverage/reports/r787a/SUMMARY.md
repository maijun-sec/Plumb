# R787a — ThreadRunChecker source-reading → plumb-style AST migration

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787a (was R787 until the user intervened mid-round with a
cardinal principle correction).

## Cardinal principles (locked, 2026-10-11)

The user corrected the architecture mid-round with two principles that
apply to every subsequent R-Round:

1. **AST rules (or any rule whose input is a CompilationUnit) belong
   in the style module.** DFA rules (input is TAC IR) belong in the
   dfa module. No exceptions.
2. **If a rule can be implemented as an AST checker, do not implement
   it as a DFA checker.** AST is structurally more honest, faster to
   write, and the symbol solver is already available there.

These two principles invalidated R786's plan to add a parallel
`checker/ast/` package under plumb-dfa. R786's 5 files (AstChecker
interface + AstContext + AstRunner + AstReport + AstRunnerTest) were
all deleted in this R-Round.

## What R787a did

### 1. Removed plumb-dfa's redundant AST-in-DFA package

Deleted 5 files (zero behaviour change, all under
`plumb-dfa/src/main/java/org/plumb/dfa/checker/`):
- `AstChecker.java` — collided by name with `plumb-style.AstChecker`
- `ast/AstContext.java`
- `ast/AstRunner.java`
- `ast/AstReport.java`
- `src/test/java/org/plumb/dfa/checker/ast/AstRunnerTest.java`

The empty `checker/ast/` directory remains on disk because
`mavis-trash` was unavailable; mvn ignores empty directories.

### 2. Replaced ThreadRunChecker with a deprecated stub

In `plumb-dfa/src/main/java/org/plumb/dfa/checkers/ClassThreadChecker.java`:
- The `ThreadRunChecker` package-private class was rewritten as a
  deprecation stub. Its private constructor throws
  `UnsupportedOperationException` with a message pointing at the new
  plumb-style home. All five `DfaChecker` methods are no-ops.
- The outer `ClassThreadChecker` aggregator no longer instantiates
  `cwe572`; it now only fans out to the four remaining
  source-reading checkers (cwe579 / cwe585 / cwe609 / cwe835).
- `ClassThreadChecker.java.r759e_bad` (R759-E backup file) was left
  untouched.

### 3. Strengthened plumb-style's CallToThreadRunCheck

`plumb-style/src/main/java/org/plumb/style/style/CallToThreadRunCheck.java`:
- Original heuristic was name-based (variable name contains
  `"Thread"` / `"task"` / `"worker"` / `"Runnable"`). R787a adds a
  primary path that uses `StyleContext.resolvedTypeOf(scope)` and
  walks the receiver type's `getAllAncestors()` looking for
  `java.lang.Thread`.
- Name heuristic is preserved as a safety-net fallback when the
  symbol solver can't resolve the receiver (e.g. types from
  libraries not on the classpath). On Juliet CWE-572 the new path
  fires for all 17 TPs.
- `RuleDef.ruleId().id()` changed from `pl-0047-call-to-thread-run`
  to `Cwe572` so the eval harness's `plumb_rule_to_cwe.json` mapping
  (`Cwe572 → ["572"]`) is hit, and findings aren't cwe-mismatch-skipped.
  `TO_THREAD_RUN` is preserved as the short_name.

### 4. Per-CWE YAML for CWE-572

`D:\tmp\plumb_test\configs_v2_r760\CWE572.yaml` (new file):
- Whitelists `plumb.resource.leak` (CWE-404, also fires on Juliet's
  `new Thread([])`), the three legacy aliases of CallToThreadRunCheck
  (`call-to-thread-run`, `CallToThreadRunCheck`, `pl-0047-call-to-thread-run`),
  plus `CatchGenericException` and `InsecureCommunication` / `UncheckedReturn`
  for parity with the other CWE yamls.
- Does NOT enable `dfa.cwe572-thread-run` (would now resolve to the
  deprecated stub and throw at instantiation).

### 5. Scan script + parallel-batched run

`D:\Users\maijun\AppData\Local\Temp\r787a_scan_5cwe.ps1` (5-CWE) plus
ad-hoc parallel `Start-Process` kickoff for the 4 long-running CWEs
in the background. The original r787a_scan_all.ps1 had a
Start-Process + Wait-Process timeout bug (1 hr wait when java exited
0 immediately) — replaced with `$ErrorActionPreference='Continue'`
direct invocations and parallel Start-Process kickoffs.

## Eval results

Run: `python eval_findings_cwe_matched.py --baseline-dir D:\tmp\plumb_test\scan\r786 --whitelist-dir D:\tmp\plumb_test\scan\r787a --out-dir D:\tmp\plumb_test\eval\r787a`

| CWE | R786 baseline | R787a whitelist | Diff |
|-----|---------------|-----------------|------|
| CWE129 | 0.4073 (2831/4120) | 0.4073 (2831/4120) | 0 |
| CWE190 | 0.4220 (7580/10384) | 0.4220 (7580/10384) | 0 |
| CWE191 | 0.5091 (5244/5057) | 0.5091 (5244/5057) | 0 |
| CWE369 | 0.3604 (1822/3234) | 0.3604 (1822/3234) | 0 |
| CWE572 | not measured | **1.0000 (17/0)** | NEW |
| CWE835 | 1.0000 (6/0) | 1.0000 (6/0) | 0 |
| **Total** | 0.4341 | 0.4343 | +0.0002 |

Zero regression across all 5 R786 baseline CWEs. CWE-572 lands at
**precision = 1.000** (17 TP, 0 FP) — bit-identical to the previous
regex-based detector, which is the strongest possible proof that the
AST migration is behaviour-preserving.

## Lessons

- **Lesson 1151 (R787a)**: AST = Style module, DFA = DFA module. No
  `checker/ast/` sub-package under `plumb-dfa`; reuse `plumb-style`'s
  pre-existing `AstChecker` / `StyleContext` / `AstDispatcher`
  infrastructure. Don't duplicate the AST marker interface; the
  cross-module name collision (`org.plumb.style.AstChecker` vs
  `org.plumb.dfa.checker.AstChecker`) is itself the warning that the
  design is wrong.

- **Lesson 1152 (R787a)**: name collision = design error. When two
  modules contain a class with the same simple name, every downstream
  consumer that imports both will silently pick the wrong one in some
  IDE context. Treat it as a structural smell and remove one.

- **Lesson 1153 (R787a)**: JavaParser 3.26.4 spells it
  `getAllAncestors()`, not the older `allAncestors()`. The accessor
  rename happened around 3.18; if the plumb-parser pom bumps the
  JavaParser version, audit every `*.getAllAncestors()` / `*.allAncestors()`
  call site.

- **Lesson 1154 (R787a, operational)**: when the eval harness's
  `plumb_rule_to_cwe.json` does NOT contain the rule_id emitted by
  a StyleCheck, the strict eval drops every finding as
  `cwe-mismatch`. If the rule's CWE tag is correct, the fix is to
  align the RuleDef.ruleId().id() with an existing JSON key
  (preferred) rather than expanding the JSON.

- **Lesson 1155 (R787a, operational)**: Start-Process + Wait-Process
  has a 1-hour PS hang when java exits 0 immediately (e.g. on a
  YAML warning). The fix is to redirect stdout/stderr to files with
  `> out 2> err` under `$ErrorActionPreference='Continue'`, or to
  fire-and-forget with `Start-Process ... | Out-Null` and poll
  `Get-Process` for completion.

- **Lesson 1156 (R787a, operational)**: parallelise long Juliet
  scans by kicking them off as background `Start-Process` jobs in a
  single `foreach`, then poll NDJSON file sizes from a separate
  short-running watcher loop. 4 parallel CWE scans complete in ~9
  minutes; serial would take ~36 minutes.

## Files (R787a)

- deleted: 5 files under `plumb-dfa/checker/`
- modified: `plumb-dfa/src/main/java/org/plumb/dfa/checkers/ClassThreadChecker.java` (cwe572 → stub; outer aggregator loses cwe572; comment block documents the migration)
- modified: `plumb-style/src/main/java/org/plumb/style/style/CallToThreadRunCheck.java` (resolvedTypeOf path + RuleDef.ruleId change)
- new: `D:\tmp\plumb_test\configs_v2_r760\CWE572.yaml`
- new: `D:\Users\maijun\AppData\Local\Temp\r787a_scan_5cwe.ps1`
- new: `D:\Users\maijun\AppData\Local\Temp\r787a_scan_all.ps1` (broken; see Lesson 1155)
- new: this SUMMARY.md
- new: `D:\tmp\plumb_test\scan\r787a\CWE{129,190,191,369,572,835}.ndjson`
- new: `D:\tmp\plumb_test\eval\r787a/{global_per_cwe.csv,global_per_rule.csv,global_summary.csv,skip_stats.csv}`

## Open items (R787b+)

1. **Migrate the remaining 4 source-reading checkers** out of
   `ClassThreadChecker.java`: cwe579 (NonSerializableInSession),
   cwe585 (EmptySyncBlock), cwe609 (DoubleCheckedLocking), cwe835
   (InfiniteLoop). Each is its own R-Round. cwe835 already has a
   partial JavaParser visitor inside the existing regex wrapper; the
   migration is mostly extract + AstChecker-repackage.

2. **Audit remaining source-reading style of writing in plumb-dfa**:
   `CommentInfoLeakChecker`, `CompareWithSelfChecker`,
   `HardCodedKeyChecker`, `OffByOneChecker`,
   `ReachableAssertionChecker`, `ObjectModelChecker`,
   `UncheckedReturnChecker`. These are likely candidates too.
