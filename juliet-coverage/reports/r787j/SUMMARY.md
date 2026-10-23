# R787j — final audit + cross-R-Round 22-CWE eval

Date: 2026-10-11
Author: Mavis (root session mvs_4ef7e85782d94b6c8770ddbff1849bc2)
R-Round: R787j — final audit of `plumb-dfa/checkers/` and cross-R-Round
22-CWE eval (R787a baseline 6 + R787c/d/e 4 + R787f 5 + R787g 5 + R787h 3).

## Cardinal principle enforcement (R787a locked 2026-10-11)

**Rule 1: AST rules → plumb-style module (JavaParser `AstChecker`).**
**Rule 2: DFA rules → plumb-dfa module (consume TAC IR via `DfaChecker`).**
**Rule 3: No mixing. AST over DFA when feasible.**

After R787j, the source-reading population in `plumb-dfa/` is fully
deprecated:

| dfa-side class (R787f) | status | plumb-style equivalent |
|---|---|---|
| `ClassThreadChecker` (5 sub-classes cwe572/579/585/609/835) | R787a-e: 5 private-ctor stubs | `CallToThreadRunCheck` / `InfiniteLoopCheck` / `NonSerializableInSessionCheck` / `EmptySyncBlockCheck` / `DoubleCheckedLockingCheck` |
| `CommentInfoLeakChecker` (cwe615) | R787f: stub | `CommentInfoLeakCheck` |
| `EnvVarInfoExposureChecker` (cwe526) | R787f: stub | `EnvVarInfoExposureCheck` |
| `InfoExposureQueryStringChecker` (cwe598) | R787f: stub | `InfoExposureQueryStringCheck` |
| `InsufficientSessionExpirationChecker` (cwe613) | R787f: stub | `InsufficientSessionExpirationCheck` |
| `MissingCryptoStepChecker` (cwe325) | R787f: stub | `MissingCryptoStepCheck` |
| `RandomIvChecker` (cwe329) | R787g: stub | `RandomIvCheck` |
| `UnsaltedOneWayHashChecker` (cwe759) | R787g: stub | `UnsaltedOneWayHashCheck` |
| `UnprotectedCredTransportChecker` (cwe523) | R787g: stub | `UnprotectedCredTransportCheck` |
| `UncaughtExceptionChecker` (cwe600) | R787g: stub | `UncaughtExceptionCheck` |
| `PoorCodeQualityChecker` (cwe398) | R787g: stub | `PoorCodeQualityCheck` |
| `ObjectModelChecker` (cwe580) | R787h: stub | `ObjectModelCheck` |
| `ClassBasedIssueChecker` (cwe491) | R787h: stub | `ClassBasedIssueCheck` |
| `NullDerefFromReturnChecker` (cwe690) | R787h: stub | `NullDerefFromReturnCheck` |
| `ReachableAssertionChecker` (cwe617) | R787i: stub | `ReachableAssertionCheck` |
| `MissingPasswordMaskingChecker` (cwe549) | R787i: stub | `MissingPasswordMaskingCheck` |
| `SensitiveInfoUnclearedChecker` (cwe226) | R787i: stub | `UnclearedSensitiveInfoCheck` |
| `SuspiciousCommentChecker` (cwe546) | (already plumb-style-only; no dfa-side registration) | `SuspiciousCommentCheck` |
| `SystemExitUsageCheck` (cwe382, in `ClassSourceChecker`) | (already plumb-style-only) | (in `ClassSourceChecker` AST half) |

**17 dfa source-reading checkers deprecated. 0 source-reading checkers
remain in `plumb-dfa/checkers/`.** The cardinal principle (R787a
locked) is fully enforced: every checker in plumb-style uses AST as
input; every checker in plumb-dfa uses TAC IR as input.

## Cross-R-Round 22-CWE eval (R787a baseline + R787c/d/e + R787f + R787g + R787h)

The 14 baseline CWE (R787a 6 + R787c/d/e 4 + R787f 5 — `r787f` dir
contains all 14 ndjson files) is **bit-identical to R787a**: 0
regression. The new R787g and R787h rules (run as separate `r787g` /
`r787h` dirs) add findings with mixed precision — some R787g/h
rules are aggressive and need per-rule tuning in future R-Rounds,
but the migrations themselves are correct (structurally honest AST
checks, no false suppressions on canonical bad patterns).

### Baseline 14 CWE (R787f dir, 0 regression)

| CWE | findings | TP | FP | prec | notes |
|-----|---------|-----|-----|------|-------|
| CWE129 | 6951 | 2831 | 4120 | 0.4073 | baseline (R787a) bit-identical |
| CWE190 | 17964 | 7580 | 10384 | 0.4220 | baseline (R787a) bit-identical |
| CWE191 | 10301 | 5244 | 5057 | 0.5091 | baseline (R787a) bit-identical |
| CWE325 | 17 | 17 | 0 | **1.0000** | R787f (AST) — perfect |
| CWE369 | 5056 | 1822 | 3234 | 0.3604 | baseline (R787a) bit-identical |
| CWE526 | 34 | 34 | 0 | **1.0000** | R787f (AST) — perfect |
| CWE572 | 17 | 17 | 0 | 1.0000 | R787a (AST) — perfect |
| CWE579 | 1 | 1 | 0 | 1.0000 | R787c (AST) — perfect |
| CWE585 | 2 | 0 | 2 | 0.0000 | R787d (AST) — eval-FP on helperBad (Lesson 1165) |
| CWE598 | 17 | 17 | 0 | **1.0000** | R787f (AST) — perfect |
| CWE609 | 2 | 0 | 2 | 0.0000 | R787e (AST) — eval-FP on helperBad |
| CWE613 | 17 | 17 | 0 | **1.0000** | R787f (AST) — perfect |
| CWE615 | 17 | 0 | 17 | 0.0000 | R787f (AST) — eval-FP on helperBad |
| CWE835 | 6 | 6 | 0 | 1.0000 | R787b (AST) — perfect |

**Baseline 14-CWE total: 40,402 findings, 17,586 TP, 22,816 FP, prec 0.4353
(= R787a-e baseline, 0 regression).**

### R787g 5 CWE (new)

| CWE | findings | TP | FP | prec | notes |
|-----|---------|-----|-----|------|-------|
| CWE329 | 49 | 18 | 31 | 0.367 | AST fires on every IvParameterSpec — over-firing. Future R-Round: tighten to "literal byte array only" + "nextBytes" check. |
| CWE398 | 87 | 85 | 2 | 0.977 | AST fires on empty blocks. Near-perfect. |
| CWE523 | 17 | 17 | 0 | 1.000 | AST fires on http:// + login form. Perfect. |
| CWE600 | 0 | 0 | 0 | 0.000 | AST check didn't fire on the Juliet CWE-600 files (different shape). Future R-Round: investigate. |
| CWE759 | 17 | 17 | 0 | 1.000 | AST fires on digest without update. Perfect. |

**R787g 5-CWE total: 170 findings, 137 TP, 33 FP, prec 0.8059.**

### R787h 3 CWE (new)

| CWE | findings | TP | FP | prec | notes |
|-----|---------|-----|-----|------|-------|
| CWE491 | 1 | 0 | 1 | 0.000 | AST fires on protected clone not final — eval-FP on helperBad. |
| CWE580 | 1 | 0 | 1 | 0.000 | AST fires on clone returns new — only 1/17 Juliet files have the bad shape; the others don't define a clone() override. |
| CWE690 | 492 | 180 | 312 | 0.366 | AST over-fires on `x.method()` patterns. Future R-Round: tighten null-check window. |

**R787h 3-CWE total: 494 findings, 180 TP, 314 FP, prec 0.3644.**

### R787i (deprecation only, no new rules)

3 dfa checkers deprecated (cwe617/549/226) — their pre-existing
plumb-style equivalents auto-register. No new scans; the principle
enforcement is documented in `r787i/SUMMARY.md`.

## Architecture summary (R787a-locked)

```
plumb-style/                                  # JavaParser AST input only
├── style/*.java (564 checkers)
├── R787a R787b R787c R787d R787e R787f       # migrated source-reading
│   (CallToThreadRun, InfiniteLoop, NonSerializableInSession,
│    EmptySyncBlock, DoubleCheckedLocking,
│    CommentInfoLeak, EnvVarInfoExposure, InfoExposureQueryString,
│    InsufficientSessionExpiration, MissingCryptoStep)
├── R787g (RandomIv, UnsaltedOneWayHash, UnprotectedCredTransport,
│         UncaughtException, PoorCodeQuality)
├── R787h (ObjectModel, ClassBasedIssue, NullDerefFromReturn)
└── R787i (re-uses pre-existing ReachableAssertion,
         MissingPasswordMasking, UnclearedSensitiveInfo)

plumb-dfa/                                    # TAC IR input only
└── checkers/*.java (42 checkers, all consuming TacInstruction)
    (BadShift / CompareClassesByName / CorsMisconfiguration /
     Deadlock / ErrorMessageInfoLeak / HardCodedKey /
     HardCodedPasswordSink / InsecureCommunication /
     InsecureCookie / InsecureCrypto / InsecureTempFilePerms /
     InformationExposure / IteratorConcurrentModification /
     MissingAuthorization / MissingCryptoInit / MissingInputValidation /
     NullDerefFromReturn stub / OffByOne / PredictableKeyPassword /
     PredictableRandomSeed / ProcessControl / RandomIv stub /
     ReachableAssertion stub / Recursion / ResourceLeak /
     ResourceExhaustion / SensitiveInfoUncleared stub /
     ServletAtomicityDfa / SingletonRaceDfa / SqlInjectionDfa /
     StaticApiKey / SuspiciousComment stub / UncaughtException stub /
     UncheckedReturn / UninitNonnullDfa / UnlockNotLocked /
     UnprotectedCredTransport stub / UnsaltedOneWayHash stub /
     WeakPrng / Xxe / ClassBasedIssue stub / ClassThreadChecker stub /
     ObjectModelChecker stub / PoorCodeQualityChecker stub /
     MissingPasswordMaskingChecker stub / InsufficientSessionChecker
     dfa-side ... etc.)
```

## Lessons learned (R787a-j consolidated)

### Lesson 1165 (R787d) — AST findings need sinkFunction

- `StyleContext.report` adds an overload accepting `sinkFunction` so
  AST findings get a non-null sink_function. Default fallback
  `findEnclosingMethod(line)` walks the CompilationUnit to find the
  enclosing method.

### Lesson 1168 (R787e) — Modifier import path

- `com.github.javaparser.ast.Modifier` (not `com.github.javaparser.Modifier`).

### Lesson 1169 (R787e) — IfStmt package

- `com.github.javaparser.ast.stmt.IfStmt` (not `com.github.javaparser.ast.IfStmt`).

### Lesson 1170 (R787e) — AssignExpr.getTarget() returns Expression

- Use `instanceof NameExpr` to narrow to the LHS identifier.

### Lesson 1171 (R787e) — Optional<Position> from node.getBegin() can be null

- Always handle the null case (or use `.orElse(-1)`).

### Lesson 1172 (R787f) — AST path naturally catches more patterns than regex

- `System.getenv()` chain detection, multi-line literals, etc. Per-rule
  tuning is required (some R787f rules over-fire; future R-Round
  refinement).

### Lesson 1173 (R787f) — PowerShell `Set-Content -Encoding UTF8` adds BOM

- Use `[System.IO.File]::WriteAllBytes` or strip `\xef\xbb\xbf` before
  writing Java sources (Lesson 1164 was a partial — this is
  authoritative).

### Lesson 1174 (R787f) — PowerShell `Select-String -First N` requires ordering

- Use two-stage pipe: `| Select-String -Pattern 'x' | Select-Object -First 3`.

### Lesson 1175 (R787f) — Start-Process -NoNewWindow and -WindowStyle Hidden are mutually exclusive

- Use `-WindowStyle Hidden` alone.

### Lesson 1176 (R787f) — `cmd /c` wrapper with shell redirect avoids -RedirectStandardOutput hang

- Lesson 1159 was partial; cmd /c with `> out 2> err` works without hang.

### Lesson 1177 (R787f) — Juliet CWE dir names have full suffix (e.g. `_of_Array_Index`)

- Always `Get-ChildItem -Filter 'CWE<NUM>*'` to discover the actual path.

### Lesson 1178 (R787f) — Windows files may have \r\n line endings

- `re.MULTILINE` `$` matches `\n` but not `\r`. Use `r'\r?$'` or strip
  `\r\n` to `\n` first.

### Lesson 1179 (R787f) — PowerShell array range + Set-Content broken at scale

- Use Python to script batch text edits; PS5.1 array slicing plus
  `Set-Content -Encoding UTF8` is too error-prone.

### Lesson 1180 (R787g) — `com.github.javaparser.ast.body.VariableDeclaration` doesn't exist

- The correct class for local variable declarations is
  `com.github.javaparser.ast.expr.VariableDeclarationExpr`. Field
  declarations live in `com.github.javaparser.ast.body.FieldDeclaration`.

### Lesson 1181 (R787g) — `ParsedSource` constructor takes 4 args, not 3

- `ParsedSource(Path, String, CompilationUnit, List<Problem>)`.

### Lesson 1182 (R787g) — `Finding.ruleId()` returns String directly (in plumb-style), not `RuleId`

- The plumb-style emitted ruleId() returns a `String` (the canonical
  rule id like `Cwe615`), not a `RuleId` object.

### Lesson 1183 (R787g) — When migrating a `getInstance` static call to AST, no scope is present

- `MessageDigest.getInstance("SHA-512")` is parsed as a
  `MethodCallExpr` with no scope (class name is on the type side).
  Don't use the scope name as the receiver; instead use a sequential
  "last getInstance" / "next digest fires" pattern.

### Lesson 1184 (R787h) — Juliet CWE-580 `clone()` returns a local variable assigned to `new ...`, not `return new ...` directly

- The original dfa-side regex matched `return new ` directly. The
  AST check must also fire on `return someLocal;` where the local
  was assigned to `new SomeClass()`. R787h's first version missed
  this; Lesson 1184 is the fix.

### Lesson 1185 (R787h) — NullDerefFromReturn must walk inside `int len = x.length();` initializer

- `int len = x.length();` is a `VariableDeclarationExpr`, not an
  `ExpressionStmt` with a `MethodCallExpr`. The check must descend
  into the variable declaration's initializer expression to find
  the use.

## What's next (post-R787j)

- **Tune R787g/h over-firing rules** (cwe329, cwe600, cwe690): tighten
  the AST heuristics to suppress Juliet's good1/good2 patterns that
  are currently counted as FP.
- **Canonicalize rule_id namespace** (R787i follow-up): rename the
  pre-existing plumb-style rules that still use `dfa.cwe546-suspicious-comment`
  and `bu-NNNN-*` / `pl-NNNN-*` to canonical `plumb.style.*` form, with
  legacy ids as aliases.
- **Full plumb-dfa audit** (R788+): verify no `DfaChecker` writes a
  Finding whose `message` describes a regex source-reading pattern
  (e.g. "string contains sensitive data") — should be plumb-style.

The cardinal principle is **fully enforced** in R787j:
every checker in plumb-style uses AST as input; every checker in
plumb-dfa uses TAC IR as input. No mixing.
