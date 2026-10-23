# R787d — EmptySyncBlockChecker source-reading → plumb-style AST migration

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787d (continuation of R787a/R787b/R787c cardinal principle lock-in).

## What R787d did

### 1. Wrote plumb-style `EmptySyncBlockCheck` (AstChecker)

`plumb-style/src/main/java/org/plumb/style/style/EmptySyncBlockCheck.java`
(new file, ~150 lines):

- `implements AstChecker` and overrides
  `visit(SynchronizedStmt, StyleContext)`. The standard
  `AstDispatcher` dispatches every `SynchronizedStmt` to
  the rule alongside the 564 existing
  `StyleCheck` / `AstChecker` implementations.
- Detects an effectively empty body:
  - `null` body (malformed code)
  - `BlockStmt` with no statements
  - `BlockStmt` containing only `EmptyStmt`s
- Note (Lesson 1166): per JLS, a `synchronized` statement
  requires a block. The form `synchronized(LOCK);`
  (single `EmptyStmt` body) is a Java syntax error and
  JavaParser refuses to parse it. The check is
  belt-and-suspenders in case a future parser version
  allows it.
- `RuleDef.ruleId()` is `new RuleId("Cwe585",
  "empty-sync-block")` with `List.of("CWE-585")` so
  the eval harness's `plumb_rule_to_cwe.json` mapping
  (`Cwe585 → ["585"]`) hits and the finding isn't
  cwe-mismatch-skipped (Lesson 1154).
- `name()` returns `"empty-sync-block"` (the short
  name). The alias index in `RuleRegistry` looks up the
  canonical `RuleDef` via
  `byAlias("empty-sync-block")`; the R761
  `resolveViaAlias` substitution path in
  `StyleContext.report` substitutes the canonical id at
  emit time.

### 2. R787d sinkFunction support in StyleContext.report

`plumb-style/src/main/java/.../StyleContext.java`:

- Added a new overload
  `report(line, col, rule, msg, severity, dataflowHead, sinkFunction)`
  that lets the AST checker set the `sinkFunction` field
  on the emitted `Finding`.
- Added a default fallback `findEnclosingMethod(line)`
  that walks the `CompilationUnit` to find the enclosing
  `MethodDeclaration` and formats its signature as
  `name(params):returnType`. This way AST findings
  always have a `sink_function` field set, even when
  the checker doesn't pass one explicitly. The DFA
  path (which doesn't go through `StyleContext.report`)
  is unchanged.
- Lesson 1165: DFA findings already have
  `sink_function` (set via the dataflow analysis). AST
  findings previously had `sink_function = null`,
  which broke the eval harness's
  `eval_findings_cwe_matched.py` heuristic
  (`classify_role` uses the method name to decide TP/FP).
  R787d fixes this for the new checker; R787a-c
  checkers will benefit from the new `findEnclosingMethod`
  fallback automatically (no per-checker code change).

### 3. EmptySyncBlockCheck uses findAncestor(MethodDeclaration)

The checker explicitly computes the `sinkFunction` from
`n.findAncestor(MethodDeclaration.class)` so the eval
harness can find the enclosing method. Juliet CWE-585
puts the bad pattern in `helperBad()` (called from
`bad()`); without the sink function, the finding
falls outside the eval's "bad" classification.

### 4. Deprecated the dfa-side `EmptySyncBlockChecker`

`plumb-dfa/src/main/java/.../ClassThreadChecker.java`:

- The package-private
  `final class EmptySyncBlockChecker implements
  DfaChecker` was rewritten as a deprecation stub. Its
  private constructor throws
  `UnsupportedOperationException` with a message pointing
  at the new plumb-style home.
- The outer `ClassThreadChecker` aggregator no longer
  instantiates `cwe585`; only `cwe609` remains.
- The file-level javadoc was updated: CWE-572 (R787a),
  CWE-835 (R787b), CWE-579 (R787c), and CWE-585 (R787d)
  are flagged as migrated to plumb-style; the remaining
  checker (CWE-609) is slated for R787e.

### 5. Per-CWE YAML for CWE-585

`D:\tmp\plumb_test\configs_v2_r760\CWE585.yaml` (new file):

- Whitelists `empty-sync-block`,
  `EmptySyncBlockCheck`, `Cwe585` (the plumb-style
  rule's canonical id and two aliases). The R761
  alias resolver in `StyleContext` maps these to the
  canonical `Cwe585 → ["585"]` entry in
  `plumb_rule_to_cwe.json`.
- Does NOT enable `dfa.cwe585-empty-sync` — that
  entry would now resolve to the deprecated stub and
  throw at instantiation.

### 6. Unit test suite

`plumb-style/src/test/java/.../R787dEmptySyncBlockCheckTest.java`
(new file, 8 tests, all pass):

- `emptyBracesFires` — Juliet's actual bad() form
  (simplified to a single test method for
  unit-test simplicity).
- `emptyBracesWithOnlyCommentFires` — comments don't
  count as statements; the body is still empty.
- `singleStatementSemicolonFormIsParseError` —
  documents that `synchronized(LOCK);` is a Java
  syntax error (per JLS) and JavaParser refuses to
  parse it. The AST check is robust to this case
  anyway.
- `nestedEmptySyncFires` — two empty sync blocks in
  one method → 2 findings.
- `nonEmptyBodyDoesNotFire` — Juliet's good1()
  body (synchronized with a real statement inside).
- `onlyEmptyStatementsInBodyDoesNotCountAsEmpty` —
  body with only `;` EmptyStmts is effectively
  empty → fires.
- `noSynchronizedAtAllDoesNotFire` — sanity check.
- `firesWithCwe585RuleId` — asserts `f.ruleId()` is
  `Cwe585` (not the legacy `plumb.dfa.cwe585-empty-sync`)
  so the rule id alignment is tested, not assumed.

## Eval results

Run: `python eval_findings_cwe_matched.py --baseline-dir
D:\tmp\plumb_test\scan\r787a --whitelist-dir
D:\tmp\plumb_test\scan\r787d_full --out-dir
D:\tmp\plumb_test\eval\r787d_full`

where `r787d_full/` = r787b's 5-CWE (CWE129/190/191/369/572/835)
+ r787d's CWE585.

| CWE    | R787a baseline        | R787d whitelist       | Diff |
|--------|-----------------------|-----------------------|------|
| CWE129 | 0.4073 (2831/4120)    | 0.4073 (2831/4120)    | 0    |
| CWE190 | 0.4220 (7580/10384)   | 0.4220 (7580/10384)   | 0    |
| CWE191 | 0.5091 (5244/5057)    | 0.5091 (5244/5057)    | 0    |
| CWE369 | 0.3604 (1822/3234)    | 0.3604 (1822/3234)    | 0    |
| CWE572 | 1.0000 (17/0)         | 1.0000 (17/0)         | 0    |
| CWE585 | **n/a (not measured)** | **0.0000 (0/2)**    | **NEW** |
| CWE835 | 1.0000 (6/0)          | 1.0000 (6/0)          | 0    |
| **Total** | **0.4343**         | **0.4343**            | **0** |

The 5-CWE baseline is bit-identical. CWE-585 is a new
addition: R787d finds 2 findings on Juliet (one in
CWE585__Servlet_01 line 24, one in CWE585__Thread_01
line 18), both inside `helperBad()` (the Juliet
canonical bad pattern wrapper). The eval's
`classify_role` returns "other" for `helperBad` (not
"bad"), so the 2 findings are technically FP by the
eval heuristic. This is an **eval framework limitation,
not a Plumb rule defect**: the R787d AST check
correctly identifies the empty synchronized block in
Juliet's marked-bad helper, and a future eval
enhancement that knows about Juliet's helperBad /
helperGood1 wrapper functions will mark these as TP.
The plumb_rule_to_cwe.json mapping already includes
`Cwe585 → ["585"]` so the finding is correctly
classified by CWE tag, just not by method role.

## Files (R787d)

- new: `plumb-style/src/main/java/org/plumb/style/style/EmptySyncBlockCheck.java` (~150 lines)
- new: `plumb-style/src/test/java/org/plumb/style/style/R787dEmptySyncBlockCheckTest.java` (8 tests, all pass)
- modified: `plumb-style/src/main/java/org/plumb/style/StyleContext.java` (added `report` overload with `sinkFunction`; added `findEnclosingMethod` fallback)
- modified: `plumb-dfa/src/main/java/.../ClassThreadChecker.java` (cwe585 → deprecation stub; outer aggregator drops cwe585; file-level javadoc updated)
- new: `D:\tmp\plumb_test\configs_v2_r760\CWE585.yaml`
- new: `D:\Users\maijun\AppData\Local\Temp\r787d_scan.ps1` (single-CWE launcher)
- new: this SUMMARY.md

## Lessons / operational notes (R787d additions)

- Lesson 1165: AST findings need a `sinkFunction` on
  the emitted `Finding` for the eval harness's
  `eval_findings_cwe_matched.py` to work correctly. The
  eval's `classify_role` (in `compare_fast.py`) decides
  TP/FP by checking if the enclosing method name starts
  with "bad" (or "goodG2B" / "goodB2G"). If `sinkFunction`
  is null, the eval's `find_method_span` falls back to
  "any span containing the line" — which is correct in
  principle but doesn't help when Juliet uses
  `helperBad()` wrappers. The new `StyleContext.report`
  overload accepts an explicit `sinkFunction`; the
  default fallback `findEnclosingMethod(line)` walks
  the CompilationUnit to derive it. This benefits all
  new AST checkers (R787a-c can optionally re-emit to
  use the new explicit-sinkFunction path; their
  existing behavior is unchanged).

- Lesson 1166: per JLS, a `synchronized` statement
  requires a block. The form `synchronized(LOCK);` is
  a Java syntax error and JavaParser refuses to parse
  it (parse error: "Found ';', expected '{'"). The
  AST check doesn't need to handle this case but the
  `isEffectivelyEmpty` helper still has a defensive
  `EmptyStmt` direct-body branch as a belt-and-suspenders
  guard for hypothetical future parser versions.

- Lesson 1167: `MethodDeclaration.getParameters()` returns
  `NodeList<Parameter>`, not a `ParameterList` class.
  In JavaParser 3.18+ the type was renamed/removed. The
  Lesson 1157 `Optional.map(p -> p.field)`-on-Object bug
  was a related symptom of the same JavaParser API
  drift. Always use `getParameters()` and iterate the
  `NodeList<Parameter>` directly.

## R787e+ plan

One source-reading checker remains in
`plumb-dfa/src/main/java/.../ClassThreadChecker.java`:

| R       | CWE | Checker            | Strategy                                                          |
|---------|-----|--------------------|-------------------------------------------------------------------|
| R787e   | 609 | DoubleCheckedLockingChecker     | regex → plumb-style AST (visit `SynchronizedStmt` + nested `IfStmt` + `AssignExpr`; flag when no `volatile` declaration on the field) |

After R787e, audit the remaining 7 plumb-dfa
source-reading classes for AST migration candidates
(R787f+): `CommentInfoLeakChecker`,
`CompareWithSelfChecker`, `HardCodedKeyChecker`,
`OffByOneChecker`, `ReachableAssertionChecker`,
`ObjectModelChecker`, `UncheckedReturnChecker`.