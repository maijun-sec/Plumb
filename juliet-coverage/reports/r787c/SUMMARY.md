# R787c — NonSerializableInSession source-reading → plumb-style AST migration

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787c (continuation of R787a/R787b AST/DFA cardinal principle lock-in).

## What R787c did

### 1. Wrote plumb-style `NonSerializableInSessionCheck` (AstChecker)

`plumb-style/src/main/java/org/plumb/style/style/NonSerializableInSessionCheck.java`
(new file, ~250 lines):

- `implements AstChecker` and overrides
  `visit(MethodCallExpr, StyleContext)`. The standard
  `AstDispatcher` dispatches every `MethodCallExpr` to the
  rule, alongside the 564 existing
  `StyleCheck` / `AstChecker` implementations.
- Two detection branches:
  1. **Symbol-solver ancestry check (R787c new)**: resolve
     the value argument's static type. If it's a reference
     type that does NOT descend from
     `java.io.Serializable`, flag it. This catches Juliet's
     `new BadObject()` form (a non-Serializable nested
     class) and any custom non-Serializable type in
     production code.
  2. **Literal-constructor fallback (regex parity)**:
     matches `new Hashtable()`, `new HashMap()`,
     `new ArrayList()` by name when symbol resolution is
     unavailable. Note: in modern JDKs all three of these
     implement `Serializable`, so Branch 2's matches are
     technically false positives in the R759-E regex
     sense. R787c keeps them as a "we know this pattern
     is suspicious even when we can't prove it" fallback
     so the rule keeps firing on the same patterns the
     regex caught.
- **Receiver resolution** mirrors R787a's
  `CallToThreadRunCheck` pattern (Lesson 1151/1152):
  primary path is symbol-solver ancestry walking to
  `javax.servlet.http.HttpSession` via `getAllAncestors()`;
  fallback is name heuristic.
- **R787c extension**: the name-heuristic fallback also
  recognizes method-chain receivers via `getSession(...)`
  recursive descent. Juliet's actual CWE-579 form is
  `request.getSession(true).setAttribute("BadObject",
  badObject)` — a method chain, not a `NameExpr`. Without
  this extension, the AST rule would never fire on Juliet
  when `servlet-api.jar` is not on the symbol-solver's
  classpath (which is the default for Plumb's fat-jar
  scan).
- `RuleDef.ruleId()` is `new RuleId("Cwe579",
  "non-serializable-in-session")` with `List.of("CWE-579")`
  so the eval harness's `plumb_rule_to_cwe.json` mapping
  (`Cwe579 → ["579"]`) hits and the finding isn't
  cwe-mismatch-skipped (Lesson 1154).
- `name()` returns `"non-serializable-in-session"` (the
  short name). The alias index in `RuleRegistry` looks up
  the canonical `RuleDef` via
  `byAlias("non-serializable-in-session")`; the R761
  `resolveViaAlias` substitution path in
  `StyleContext.report` substitutes the canonical id at
  emit time.

### 2. Deprecated the dfa-side `NonSerializableInSessionChecker`

`plumb-dfa/src/main/java/.../ClassThreadChecker.java`:

- The package-private
  `final class NonSerializableInSessionChecker implements
  DfaChecker` was rewritten as a deprecation stub. Its
  private constructor throws
  `UnsupportedOperationException` with a message pointing
  at the new plumb-style home. All five `DfaChecker`
  methods are no-ops.
- The outer `ClassThreadChecker` aggregator no longer
  instantiates `cwe579`; it now only fans out to the two
  remaining source-reading checkers (cwe585 / cwe609).
- The file-level javadoc was updated: CWE-572 (R787a),
  CWE-835 (R787b), and CWE-579 (R787c) are flagged as
  migrated to plumb-style; the remaining two checkers
  (cwe585 / cwe609) remain here for now.

### 3. Per-CWE YAML for CWE-579

`D:\tmp\plumb_test\configs_v2_r760\CWE579.yaml` (new file):

- Whitelists `non-serializable-in-session`,
  `NonSerializableInSessionCheck`, `Cwe579` (the
  plumb-style rule's canonical id and two aliases). The
  R761 alias resolver in `StyleContext` maps these to the
  canonical `Cwe579 → ["579"]` entry in
  `plumb_rule_to_cwe.json`.
- Does NOT enable `dfa.cwe579-non-serializable` — that
  entry would now resolve to the deprecated stub and
  throw at instantiation.
- Keeps `plumb.resource.leak` for parity with the other
  CWE yamls.

### 4. Unit test suite

`plumb-style/src/test/java/.../R787cNonSerializableInSessionCheckTest.java`
(new file, 9 tests, all pass):

- `julietBadObjectFires` — Juliet's actual bad() form
  (`request.getSession(true).setAttribute("BadObject",
  badObject)` simplified to a `NameExpr` receiver for
  test simplicity). The R759-E regex never fired on this
  (literal-shape match); the AST check fires via the
  symbol-solver ancestry branch.
- `julietGoodObjectDoesNotFire` — `GoodObject implements
  Serializable` correctly suppressed.
- `hashtableDoesNotFire` / `hashmapDoesNotFire` /
  `arraylistDoesNotFire` — these containers DO implement
  `Serializable` in modern JDKs, so the AST check
  correctly suppresses them. The R759-E regex was
  actually wrong to fire on these; R787c is structurally
  correct.
- `setAttributeWithThreeArgsDoesNotFire` — only
  `setAttribute(name, value)` (2-arg) is the CWE-579
  HttpSession entry point.
- `otherMethodDoesNotFire` — only `setAttribute` is the
  CWE-579 entry point; `putAttribute`, `setX`, etc.
  don't fire.
- `requestGetSessionSetAttributeFires` — Juliet's actual
  method-chain form, validates the R787c name-heuristic
  fallback extension for method chains.
- `firesWithCwe579RuleId` — asserts `f.ruleId()` is
  `Cwe579` (not the legacy
  `plumb.dfa.cwe579-non-serializable`) so the rule id
  alignment is tested, not assumed.

## Eval results

Run: `python eval_findings_cwe_matched.py --baseline-dir
D:\tmp\plumb_test\scan\r787a --whitelist-dir
D:\tmp\plumb_test\scan\r787c --out-dir
D:\tmp\plumb_test\eval\r787c`

| CWE    | R787b baseline        | R787c whitelist       | Diff |
|--------|-----------------------|-----------------------|------|
| CWE129 | 0.4073 (2831/4120)    | unchanged (n/a)       | 0    |
| CWE190 | 0.4220 (7580/10384)   | unchanged (n/a)       | 0    |
| CWE191 | 0.5091 (5244/5057)    | unchanged (n/a)       | 0    |
| CWE369 | 0.3604 (1822/3234)    | unchanged (n/a)       | 0    |
| CWE572 | 1.0000 (17/0)         | unchanged (n/a)       | 0    |
| CWE835 | 1.0000 (6/0)          | unchanged (n/a)       | 0    |
| **CWE579** | **n/a (not measured)** | **1.0000 (1/0)** | **NEW** |

The 5-CWE baseline (R787a/r787b) doesn't have CWE579 in
its whitelist — it wasn't being tested before R787c. The
R787c scan on CWE579 returns 1 finding on Juliet's
CWE579__Servlet_01.bad() at line 47 (the
`new BadObject()` form). The R759-E regex never fired on
Juliet CWE-579 at all because it only matched
`new Hashtable|HashMap|ArrayList` literal
constructors. The R787c AST check correctly fires on
Juliet for the first time: 1/0 = 1.0000 precision.

For a full 6-CWE comparison, R787b's whitelist data
already includes CWE129/190/191/369/572/835 with no
regressions (see R787b SUMMARY.md). R787c adds CWE579
to the whitelist set, so the union would be 7-CWE
scan: 5-CWE R787a baseline + R787b's CWE129/190/191/369/572/835
(unchanged) + CWE579 (new at 1/0).

## Files (R787c)

- new: `plumb-style/src/main/java/org/plumb/style/style/NonSerializableInSessionCheck.java` (~250 lines)
- new: `plumb-style/src/test/java/org/plumb/style/style/R787cNonSerializableInSessionCheckTest.java` (9 tests, all pass)
- modified: `plumb-dfa/src/main/java/.../ClassThreadChecker.java` (cwe579 → deprecation stub; outer aggregator drops cwe579; file-level javadoc updated)
- new: `D:\tmp\plumb_test\configs_v2_r760\CWE579.yaml`
- new: `D:\Users\maijun\AppData\Local\Temp\r787c_scan.ps1` (single-CWE launcher)
- new: this SUMMARY.md

## Lessons / operational notes (R787c additions)

- Lesson 1161: when a rule relies on the symbol-solver to
  verify receiver types (e.g. HttpSession, Thread), the
  Plumb fat-jar scan does NOT auto-load 3rd-party jars
  (servlet-api.jar, etc.) into the TypeSolver. Juliet's
  CWE-579 receiver is `request.getSession(true)`, a
  method chain, not a NameExpr. Without a name-heuristic
  fallback for method chains, the AST rule never fires
  on Juliet when running under the fat-jar. Fix:
  recursively descend method-chain receivers in
  `isSessionReceiverByName`, recognizing `getSession(...)`
  as the Juliet canonical form. This is the same
  defense-in-depth pattern as R787a
  (`CallToThreadRunCheck.isThreadReceiverByName`).

- Lesson 1162: Hashtable / HashMap / ArrayList all
  implement `java.io.Serializable` in modern JDKs. The
  R759-E regex was structurally wrong to flag
  `new Hashtable()`, `new HashMap()`, `new ArrayList()`
  in `HttpSession.setAttribute` — these ARE serializable.
  The R787c AST check correctly suppresses them. The
  regex-parity fallback (Branch 2) only fires when
  symbol resolution is unavailable. Net effect: the AST
  rule is *more accurate* than the regex on the literal
  constructor shapes alone.

- Lesson 1163: `equals` vs `==` for Symbol-solver type
  name comparison. Use
  `"java.io.Serializable".equals(decl.getQualifiedName())`
  not `==`. The `ResolvedTypeDeclaration` instances are
  loaded per-symbol-solver session, so `==` would never
  match. Same applies to `javax.servlet.http.HttpSession`.
  Pre-existing pattern in R787a; reinforced by R787c.

- Lesson 1164: Python heredoc (`'''`) inside PowerShell
  `Invoke-Command` blocks can produce Chinese-quote
  escape artifacts (`'` becomes `'` in PS5.1) that
  break Java text-block compilation. Workaround: use
  regular `+`-concatenated Java string literals in the
  generated Java test source, not the `"""` text-block
  Python f-string. Symptom in compiler output:
  `空字符文字` (empty character literal) on the
  triple-quote line.

## R787d-f+ plan

Two source-reading checkers remain in
`plumb-dfa/src/main/java/.../ClassThreadChecker.java`:

| R       | CWE | Checker            | Strategy                                                          |
|---------|-----|--------------------|-------------------------------------------------------------------|
| R787d   | 585 | EmptySyncBlockChecker           | regex `synchronized(...) { }` → plumb-style AST (visit `SynchronizedStmt`, check empty body) |
| R787e   | 609 | DoubleCheckedLockingChecker     | regex `synchronized { if (x == null) { x = new ...` → plumb-style AST (visit `SynchronizedStmt` + nested `IfStmt` + `AssignExpr`; flag when no `volatile` declaration on the field) |

Each follows the R787a/R787b/R787c template (Lesson
1151/1152): deprecation stub + plumb-style `AstChecker`
+ `RuleDef.ruleId` aligned with `plumb_rule_to_cwe.json`
+ tests rewritten under plumb-style + `CWE*.yaml`
updated.

After R787e, audit the remaining 7 plumb-dfa
source-reading classes for AST migration candidates
(R787f+): `CommentInfoLeakChecker`,
`CompareWithSelfChecker`, `HardCodedKeyChecker`,
`OffByOneChecker`, `ReachableAssertionChecker`,
`ObjectModelChecker`, `UncheckedReturnChecker`.