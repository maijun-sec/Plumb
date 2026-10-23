# R192 — Coverity MISSING_BREAK Mirror (2026-07-31)

## Goal

Pilot the Coverity (D=Coverity) integration path with a narrow AST
mirror. MISSING_BREAK is a classic Coverity check: a switch case that
falls through to the next case without a terminating `break` /
`return` / `throw` / `continue`. The Apple "goto fail" SSL bug
(CVE-2014-1266) was a single missing `break` — entire switch flowed
into the wrong case.

## Implementation

- `plumb-style/.../MissingBreakCheck.java` (~3.3 KB)
- `visit(SwitchStmt, StyleContext)` — walks every `SwitchEntry` in the
  switch
- A case "ends" if its last statement is `BreakStmt`, `ReturnStmt`,
  `ThrowStmt`, or `ContinueStmt`
- Skip empty cases (intentional fall-through, Duff's device idiom)
- Skip the last case (no fall-through target)
- AST-only check, no dataflow

## R192 4 创新点

1. **Coverity mirrors map cleanly to plumb-style visitors** (R192
   design). Just like R170/R171/R182/R183 mapped SpotBugs checks to
   narrow AST or DFA checkers, Coverity's MISSING_BREAK is a single
   AST walk. The same single-pass dispatcher pattern applies.
2. **"Terminating statement" is a closed category** (R192
   implementation). 4 statement types exit the case body: `break`,
   `return`, `throw`, `continue`. The same `isTerminating(Statement)`
   predicate generalizes to other "missing terminator" rules (e.g.
   R193's missing-else-after-throw).
3. **"Empty case = intentional fall-through" is the Duff's device
   exception** (R192 trade-off). We skip empty cases entirely. False
   negatives are rare (Duff's device is uncommon in modern code);
   false positives on legitimate intentional fall-throughs are
   common. Same narrow-whitelist over-suppress tolerance as R164.
4. **R192 adds `SwitchStmt` to AstChecker/AstDispatcher** (R192
   infra). R192 was the first rule that needed the SwitchStmt node
   type in the A1 single-pass AST walker. Other future rules
   (Sonar S00121 "missing break", PMD MissingBreakInSwitch, etc.)
   now share the dispatcher support.

## Test Results (5/5 pass)

| Test | Pattern | Result |
|---|---|---|
| `firesOnAppleGotoFailFallThrough` | `case 0: foo(); case 1: bar();` no break | fires ✓ |
| `noFireWhenCaseEndsWithReturn` | `case 0: ...; return 0;` | suppressed ✓ |
| `noFireWhenCaseEndsWithThrow` | `case 0: ...; throw ...;` | suppressed ✓ |
| `noFireOnEmptyCaseIntentionalFallThrough` | `case 0: case 1: ...;` (Duff's) | suppressed ✓ |
| `noFireOnLastCase` | `case 1: ...;` (no break, but last) | suppressed ✓ |

## 注册

- `RuleRegistry.COV_MISSING_BREAK = "cov-0001-missing-break"`
- CWE-484 (Block Delimiters)
- aliases: `["Coverity:MISSING_BREAK", "SonarQube:java:S00121"]`

## Files

- 新: `plumb-style/.../MissingBreakCheck.java` (3346 bytes)
- 改: `plumb-style/.../AstChecker.java` (+ SwitchStmt default no-op)
- 改: `plumb-style/.../AstDispatcher.java` (+ SwitchStmt visit override)
- 改: `plumb-rules/.../RuleRegistry.java` (+ COV_MISSING_BREAK, def, alias)
- 新: `plumb-style/.../R192CoverityMissingBreakTest.java` (4887 bytes, 5 tests)
- 备份: `D:\work\tmp\r192_done\java_source\`
- doc: `docs/R192-COVERITY-MISSING-BREAK.md`

## Source 集成 status post-R192 (13 narrow mirrors)

- A (PMD): R166/R167/R168/R169 (4 rules)
- **B (Coverity): R192 (1 rule, 254 remaining)**
- C (SpotBugs): R170/R171/R182/R183 (4 rules)
- D (SonarQube): R176/R177/R180/R181 (4 rules)
- E (Juliet): 0 rules, baseline 49/68

## Cumulative R148 series (38 rounds, 1 rolled back)

- 14 metric wins
- 1 validation
- 20 infra (R186, R187, R188, R189, **R192**)
- 1 rolled back
- **427 tests / 0 fail / 0 error / 1 skip** (381 dfa + 46 style, R192 +5)
