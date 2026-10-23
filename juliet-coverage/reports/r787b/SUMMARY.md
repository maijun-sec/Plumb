# R787b — InfiniteLoopChecker source-reading → plumb-style AST migration

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787b (continuation of R787a's AST/DFA cardinal principle lock-in).

## What R787b did

### 1. Wrote plumb-style `InfiniteLoopCheck` (AstChecker)

`plumb-style/src/main/java/org/plumb/style/style/InfiniteLoopCheck.java`
(new file, ~330 lines):

- `implements AstChecker` and overrides `visit(WhileStmt, StyleContext)`,
  `visit(ForStmt, StyleContext)`, `visit(DoStmt, StyleContext)`.
- The standard `AstDispatcher` walks each file's `CompilationUnit`
  exactly once and dispatches each node type to every registered
  `AstChecker`, so the rule fires alongside the 564 existing
  `StyleCheck` / `AstChecker` implementations without any new runner.
- Two detection strategies are preserved verbatim from the R774
  dfa-side implementation:
  1. **Constant-true condition** — `while (true)`, `for (;;)`,
     `do {} while (true)` with no `break` / `return` / `throw`.
  2. **Value-bounded modulo proof** — loop header is `var >= 0`
     AND the body (or for-step clause) contains
     `var = (var + k) % N` for some positive `N`, with no
     `break` / `return` / `throw`.
- Reporting uses `StyleContext.report(line, col, RuleDef, message,
  severity, dataflowHead)` so findings carry the canonical
  `RuleDef` end-to-end and the NDJSON emitter pulls `cwe` from
  `RuleDef.cwe()` at emit time (style-emitted findings have
  `Finding.cwe() == null`; the eval harness keys off `f.ruleId()`
  and looks up the CWE via `plumb_rule_to_cwe.json`).
- `RuleDef.ruleId()` is `new RuleId("Cwe835", "infinite-loop")`
  with `List.of("CWE-835")` so the eval harness's
  `plumb_rule_to_cwe.json` (`Cwe835 → ["835"]`) hits and the
  finding isn't cwe-mismatch-skipped (Lesson 1154).
- Per-thread dedup set keyed by `shape@line`; cleared on `init()`
  so each file's walk starts fresh. `ThreadLocal` chosen because
  `ScanRunner.scanMerged` runs `translateAndStyleOne` in parallel
  across the file list; a single static set would race under that
  fan-out. Scoped to the dispatch thread, GC'd naturally.
- `name()` returns `"infinite-loop"` (the short name). The
  alias index in `RuleRegistry` looks up the canonical
  `RuleDef` via `byAlias("infinite-loop")`; the R761
  `resolveViaAlias` substitution path in `StyleContext.report`
  substitutes the canonical id at emit time.

### 2. Deprecated the dfa-side `InfiniteLoopChecker`

`plumb-dfa/src/main/java/org/plumb\dfa\checkers\ClassThreadChecker.java`:

- The package-private `final class InfiniteLoopChecker implements
  DfaChecker` was rewritten as a deprecation stub. Its private
  constructor throws `UnsupportedOperationException` with a
  message pointing at the new plumb-style home. All five
  `DfaChecker` methods are no-ops.
- The outer `ClassThreadChecker` aggregator no longer
  instantiates `cwe835`; it now only fans out to the three
  remaining source-reading checkers (cwe579 / cwe585 / cwe609).
- The file-level javadoc was rewritten: CWE-572 (R787a) and
  CWE-835 (R787b) are flagged as migrated to plumb-style; the
  remaining three checkers (cwe579 / cwe585 / cwe609) remain
  here for now.

### 3. Per-CWE YAML for CWE-835

`D:\tmp\plumb_test\configs_v2_r760\CWE835.yaml` (updated):

- Whitelists `infinite-loop`, `InfiniteLoopCheck`, `Cwe835`
  (the plumb-style rule's canonical id and two aliases). The
  R761 alias resolver in `StyleContext` maps these to the
  canonical `Cwe835 → ["835"]` entry in
  `plumb_rule_to_cwe.json`.
- Does NOT enable `plumb.dfa.cwe835-infinite-loop` — that entry
  would now resolve to the deprecated stub and throw at
  instantiation.
- Keeps `plumb.resource.leak` for parity with the other CWE
  yamls (Juliet CWE-835 `new Thread(...)` allocation in the
  Main.java driver generates CWE-404 resource leaks as a
  side-effect).

### 4. Unit test suite

`plumb-style/src/test/java/.../R787bInfiniteLoopCheckTest.java`
(new file, 13 tests, all pass):

- The 10 cases from `R774InfiniteLoopCheckerTest` are mirrored
  here, but driven through the `AstDispatcher` + `StyleContext`
  pipeline instead of `DfaEngine`. The detection logic is
  unchanged; only the registration path moved.
- 3 additional tests covering constant-true on `for(;;)` and
  `do {} while (true)` (the dfa R774 test only covered `while
  (true)` for the constant-true branch).
- A 13th test asserting `f.ruleId()` is `Cwe835` (not the
  legacy `plumb.dfa.cwe835-infinite-loop`) so the rule id
  alignment is tested, not assumed.

The dfa-side test file is preserved with `@Disabled("R787b:
InfiniteLoopChecker migrated to org.plumb.style.style.
InfiniteLoopCheck. The dfa-side checker is a deprecation stub
whose constructor throws. See R787bInfiniteLoopCheckTest in
plumb-style for the replacement test suite covering the same
10 cases.")` so the design history is auditable. The deprecation
stub throws loudly if anything tries to instantiate the dfa
side (Lesson 1148 — silent class-deletion hazard mitigation).

## Eval results

Run: `python eval_findings_cwe_matched.py --baseline-dir
D:\tmp\plumb_test\scan\r787a --whitelist-dir D:\tmp\plumb_test\scan\r787b
--out-dir D:\tmp\plumb_test\eval\r787b`

| CWE    | R787a baseline        | R787b whitelist       | Diff |
|--------|-----------------------|-----------------------|------|
| CWE129 | 0.4073 (2831/4120)    | 0.4073 (2831/4120)    | 0    |
| CWE190 | 0.4220 (7580/10384)   | 0.4220 (7580/10384)   | 0    |
| CWE191 | 0.5091 (5244/5057)    | 0.5091 (5244/5057)    | 0    |
| CWE369 | 0.3604 (1822/3234)    | 0.3604 (1822/3234)    | 0    |
| CWE572 | 1.0000 (17/0)         | 1.0000 (17/0)         | 0    |
| CWE835 | 1.0000 (6/0)          | 1.0000 (6/0)          | 0    |
| **Total** | **0.4343**         | **0.4343**            | **0** |

Zero regression. CWE-835 fires bit-identically on Juliet — the
`InfiniteLoopCheck` AST implementation matches R774's dfa-side
findings line-for-line because the detection logic is preserved
verbatim. The eval harness's `plumb_rule_to_cwe.json` mapping
(`Cwe835 → ["835"]`) hits via the canonical id.

## Files (R787b)

- new: `plumb-style/src/main/java/org/plumb/style/style/InfiniteLoopCheck.java` (~330 lines)
- modified: `plumb-dfa/src/main/java/org/plumb/dfa/checkers/ClassThreadChecker.java` (cwe835 → deprecation stub; outer aggregator drops cwe835; file-level javadoc updated)
- modified: `plumb-style/src/test/java/org/plumb/style/style/R787bInfiniteLoopCheckTest.java` (new file, 13 tests, all pass)
- modified: `plumb-dfa/src/test/java/org/plumb/dfa/checkers/R774InfiniteLoopCheckerTest.java` (`@Disabled` with redirect message; 10 tests skipped, not failed)
- modified: `D:\tmp\plumb_test\configs_v2_r760\CWE835.yaml` (whitelist plumb-style rule names; document the migration)
- new: `D:\Users\maijun\AppData\Local\Temp\r787b_scan_5cwe.ps1` (6-CWE launcher: per-CWE dir input, sequential `cmd /c` with NDJSON-size polling)
- new: this SUMMARY.md

## Lessons / operational notes (R787b additions)

- Lesson 1157: `Optional.map(p -> p.field)` lambda parameter
  inference can fail when the source interface doesn't directly
  declare `getBegin()` returning `Optional<Position>` (e.g.
  when the AST node implements `NodeWithRange` indirectly
  through another path). Symptom: `cannot find symbol 'line';
  location: variable p of type java.lang.Object`. Fix: explicit
  two-step unwrap `Optional<T> begin = n.getBegin(); T p =
  begin.orElse(null); return p == null ? -1 : p.field;`. This
  is more verbose than `n.getBegin().map(...)` but always type
  checks. Apply this pattern when an `Optional.map` lambda
  unexpectedly widens to `Object`.

- Lesson 1158: `Finding.cwe()` is `null` for style-emitted
  findings (those that pass a `RuleDef` via
  `StyleContext.report`). The CWE tag lives on
  `Finding.rule().cwe()` and is emitted by `NdjsonEmitter` via
  `r.cwe()`. Test assertions on `f.cwe()` for AST findings
  will always fail. The eval harness's
  `eval_findings_cwe_matched.py` correctly keys off
  `f.ruleId()` and looks up CWE via `plumb_rule_to_cwe.json`,
  so the end-to-end pipeline is unaffected.

- Lesson 1159: `Start-Process -FilePath java.exe -ArgumentList
  ... -RedirectStandardOutput $file -RedirectStandardError
  $file` hangs even when the java process exits immediately.
  This is independent of `-Wait`. Fix: wrap in `cmd /c` with
  shell redirection (`> out 2> err`). The `cmd /c` wrapper
  preserves exit code and avoids the .NET ProcessStartInfo
  pipe-fill deadlock.

- Lesson 1160: when launching a per-CWE scan over multiple CWEs
  sequentially in PowerShell, poll the output NDJSON file's
  size every 10s rather than `-Wait` (Lesson 1159) or
  `WaitForExit(1200)` (which can race with `HasExited`). Print
  the size each tick — when the scan is computing TAC IR
  silently for 200+ seconds, you'll see size stay at 0, and
  when it finally writes findings, size jumps in one tick.
  This pattern lets you kill long-hung scans with
  `Stop-Process` cleanly.

## R787c-f+ plan (per cardinal principle)

Three source-reading checkers remain in
`plumb-dfa/src/main/java/.../ClassThreadChecker.java`:

| R       | CWE | Checker            | Strategy                                                          |
|---------|-----|--------------------|-------------------------------------------------------------------|
| R787c   | 579 | NonSerializableInSessionChecker | regex `setAttribute(..., new Hashtable/HashMap/ArrayList(...))` → plumb-style AST (JavaParser call expr + symbol-solver check that receiver type descends from `javax.servlet.http.HttpSession`) |
| R787d   | 585 | EmptySyncBlockChecker           | regex `synchronized(...) { }` → plumb-style AST (visit `SynchronizedStmt`, check empty body) |
| R787e   | 609 | DoubleCheckedLockingChecker     | regex `synchronized { if (x == null) { x = new ...` → plumb-style AST (visit `SynchronizedStmt` + nested `IfStmt` + `AssignExpr`; flag when no `volatile` declaration on the field) |

Each follows the R787a/R787b template (Lesson 1151/1152):
deprecation stub + plumb-style `AstChecker` + `RuleDef.ruleId`
aligned with `plumb_rule_to_cwe.json` + `tests` rewritten under
plumb-style + `CWE*.yaml` updated.

After R787e, audit the remaining 7 plumb-dfa source-reading
classes for AST migration candidates (R787f+):

- `CommentInfoLeakChecker`
- `CompareWithSelfChecker`
- `HardCodedKeyChecker`
- `OffByOneChecker`
- `ReachableAssertionChecker`
- `ObjectModelChecker` (already has an `UncaughtExceptionCheck`
  equivalent in plumb-style — likely deletable)
- `UncheckedReturnChecker`