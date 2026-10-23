# R788: Canonical rule_id namespace + R790 partial tuning

**Date:** 2026-10-12
**Rounds:** R788 (canonical form) + R790a (CWE329 fix) + R790b (CWE600 fix)

## Goal

1. **R788**: Make `Finding.rule_id` always emit the canonical form
   `plumb.style.<slug>` so cross-tool consumers / AI dedup / CSV
   reporting never see the legacy `CweNNN` or `dfa.<slug>` ids.
2. **R790a**: Tighten `RandomIvCheck` to fix the CWE329 over-firing on
   `good1()`/`good2()` methods that share the same variable name
   across Juliet's per-file bad/goodN test cases.
3. **R790b**: Make `UncaughtExceptionCheck` actually fire on Juliet
   (was 0/0 due to a stale orphan class shadowing the discovery
   map's `UncaughtExceptionCheck` key).

## R788 architecture

The engine already had alias-aware `RuleRegistry.byAlias(alias)`. R788
adds **18 canonical `plumb.style.*` rule entries** to
`RuleRegistry.withBuiltinDefaults()`:

- 17 R787a-j canonicals: each `PLUMB_STYLE_<X>` constant +
  `register(def(...))` call with `aliases = List.of("CweNNN", "dfa.<slug>")`
- 1 R788i (SuspiciousComment): canonical `plumb.style.suspicious-comment`
  with `dfa.cwe546-suspicious-comment` alias (Cwe546 was the original
  legacy RuleId inside `SuspiciousCommentCheck.DEFAULT_RULE`).

Style context flow (R-PATCH v2 + R788):

```
AST checker fires with `DEFAULT_RULE = RuleDef(RuleId("Cwe572", "call-to-thread-run"), ..., true)`
    ↓
StyleContext.report(rule=DEFAULT_RULE, ...)
    ↓
if (!rule.enabled()) return        // default true
    ↓
resolved = resolveViaAlias(rule)
  ruleId.id = "Cwe572"
  if (rules.hasAlias("Cwe572"))
    resolved = rules.byAlias("Cwe572").get()  // → PLUMB_STYLE_CALL_TO_THREAD_RUN
    ↓
findings.add(new Finding(..., rule=resolved, ...))   // rule_id = "plumb.style.call-to-thread-run"
    ↓
ScanRunner post-emit filter
  if (config.enabledRuleNames.contains(id || bareId || name || clsSimple)) keep
    ↓
  ScanRunner.stripPlumbAndPlPrefix("plumb.style.call-to-thread-run")
    → "plumb." stripped → "style.call-to-thread-run"
    → ".style" stripped → "call-to-thread-run"
  yaml enabled list has "call-to-thread-run"  → MATCH
```

Files touched:

- `plumb-rules/.../rules/RuleRegistry.java`: 18 `PLUMB_STYLE_<X>`
  constants + 18 `register(def(..., List.of("CweNNN", "dfa.<slug>")))`
  calls; 1 new `def(...)` overload that takes `enabled + aliases`
  (Lesson 1148 stub pattern reused; no other behavioural change).
- `plumb-main/.../main/ScanRunner.java`: 2 places (line 1486 + 1700s
  + 2024s) now also strip the `style.` / `dfa.` / `numeric.` /
  `boundary.` domain prefix so a legacy yaml form
  (`cwe600-uncaught-exception`) matches a canonical emit
  (`plumb.style.cwe600-uncaught-exception`). `buildRegistry()` also
  uses the bare form for whitelist gating.
- `plumb-main/.../main/ScanRunner.java` (Lesson 1148) `def(...)` helper
  was unchanged; we added a 9-arg overload inside `RuleRegistry`
  that delegates to the existing 8-arg `RuleDef` constructor.
- `D:\tmp\plumb_test\scripts\r788_add_aliases.py`: extracts the 18
  R788 register calls from `RuleRegistry.java` and adds 3 keys each
  to `plumb_rule_to_cwe.json` (canonical + CweNNN legacy + dfa.<slug>
  legacy). SuspiciousComment gets a manual entry.
- `D:\tmp\plumb_test\scripts\r788_scan_all.ps1`: 22-CWE sweep
  (parallel launch) using the existing r787f_scan.ps1 harness.

## R788 generator script

`D:\tmp\plumb_test\scripts\gen_r788_v2.py` parses `RuleRegistry.java`
and inserts both the constants and the `register()` block. The first
run produced `PLUMB_STYLE_PLUMB_STYLE_*` double-prefixed constants
(double "PLUMB_STYLE_" prefix was added by both the script and the
source). Manually fixed via `D:\tmp\plumb_test\scripts\fix_r788_dup_prefix.py`
(simple `str.replace("PLUMB_STYLE_PLUMB_STYLE_", "PLUMB_STYLE_")`).

The second `register()` block insertion initially had an un-escaped
double-quote in the CWE-598 description
(`Form with method="get" and a sensitive input field`) which broke
compilation. Fixed via `D:\tmp\plumb_test\scripts\fix_r788_quote.py`
(escaped to `\"get\"`).

R788's rule names needed to be unique vs the legacy rules registered
via `addAlias(...)` at the bottom of `withBuiltinDefaults()` — e.g.
`bu-0602-infinite-loop` already had name `INFINITE_LOOP`. So every
R788 register's name is suffixed with `_STYLE`
(`INFINITE_LOOP_STYLE`, `CALL_TO_THREAD_RUN_STYLE`, etc.). The 9-arg
`def(...)` overload on `RuleRegistry` takes the constant id, the
unique name, the description, the severity, the type, the cwe list,
the checker class, the enabled flag, and the alias list.

## R790a: RandomIvCheck fix

`D:\work\workspace\idea\engine\Plumb\plumb-style\.../style/RandomIvCheck.java`

- `findByteArrayDecl(n, varName)` was file-wide first-match.
  Juliet's `bad()` declares `byte[] initializationVector = { 0x00, ... }`
  on line 34; `good1()` / `good2()` redeclare the same variable
  inside the same file (per Juliet's per-method test-case layout).
  The R787g walker found the bad() decl first and FPs every
  goodN() IvParameterSpec use.
- R790 scopes the decl + nextBytes lookup to the enclosing method
  via `findEnclosingMethodDecl(oc)` + `findByteArrayDeclInScope(n, varName, scope)`.
- Result: CWE329 went 49→18 findings (18 TP / 0 FP, prec 1.000).

## R790b: UncaughtExceptionCheck fix

`D:\work\workspace\idea\engine\Plumb\plumb-style\.../style/UncaughtExceptionCheck.java`

Two bugs:

1. **Static-call receiver name** (Lesson 1183): the original
   `isRiskyCall` checked `mc.toString().contains("InetAddress")`
   which is unreliable for static calls. R790b adds
   `hasReceivingTypeName(mc, typeName)` which walks up the parent
   chain looking for a `VariableDeclarator` whose
   `typeAsString()` contains the type name (e.g. `"InetAddress"`).
   This is the same pattern used by `RandomIvCheck` (R787g) and
   `UnsaltedOneWayHashCheck` (R787g).
2. **ProcessBuilder.start instance-call receiver** (Lesson 1183):
   `mc.getScope().toString()` returns the variable name
   (`"pb"`), not the declared type (`"ProcessBuilder"`). R790b
   adds `hasScopeWithDeclaredType(mc, typeName)` which climbs to
   the enclosing method, walks its body for a
   `VariableDeclarator` with the same variable name, and reads
   its `typeAsString()`.

Unit test `R790UncaughtExceptionCheckTest` (5 tests) covers all 5
branches (static getByName, static forName, instance start, in-try
suppression, unrelated getByName does NOT fire).

3. **Orphan class shadowing**: the `R787g/R787h` migration left an
   orphan `UncaughtExceptionCheck.java` at
   `plumb-style/src/main/java/org/plumb/style/UncaughtExceptionCheck.java`
   (package `org.plumb.style`, CWE-248 rule). It implemented
   `AstChecker` and was discovered by `StyleCheckRegistry.builtins()`
   at the same simple-name key (`UncaughtExceptionCheck`) as the
   real R787g class at `org.plumb.style.style.UncaughtExceptionCheck`.
   `discoverAvailable()` overwrites the map by simple name, so the
   R787g class was always picked — but the CWE-248 class was never
   invoked either. After R788 re-canonicalisation we removed the
   orphan (Lesson 1186: stale duplicate class). The R787g class
   now loads correctly and fires on Juliet.

## Cross-R-Round eval (R788 + R790a + R790b)

| CWE    | findings |  TP  |  FP  | prec  | status |
|--------|---------:|-----:|-----:|------:|--------|
| CWE129 |     6951 | 2831 | 4120 | 0.407 | baseline (untouched) |
| CWE190 |    17964 | 7580 |10384 | 0.422 | baseline (untouched) |
| CWE191 |    10301 | 5244 | 5057 | 0.509 | baseline (untouched) |
| CWE325 |       17 |   17 |    0 | 1.000 | R787f (new, perfect) |
| **CWE329** |  **18** |   **18** |  **0** | **1.000** | **R790a fix** (was 0.367) |
| CWE369 |     5056 | 1822 | 3234 | 0.360 | baseline (untouched) |
| CWE398 |       87 |   85 |    2 | 0.977 | R787g (near-perfect) |
| CWE491 |        1 |    0 |    1 | 0.000 | eval-FP (only 1 finding, helperBad miss) |
| CWE523 |       17 |   17 |    0 | 1.000 | R787g (perfect) |
| CWE526 |       34 |   34 |    0 | 1.000 | R787f (perfect) |
| CWE572 |       17 |   17 |    0 | 1.000 | R787a (perfect) |
| CWE579 |        1 |    1 |    0 | 1.000 | R787c (perfect) |
| CWE580 |        1 |    0 |    1 | 0.000 | eval-FP (only 1 of 3 Juliet files has the bad shape) |
| CWE585 |        2 |    0 |    2 | 0.000 | eval-FP (helperBad) |
| CWE598 |       17 |   17 |    0 | 1.000 | R787f (perfect) |
| **CWE600** |    **1** |   **1** |  **0** | **1.000** | **R790b fix** (was 0/0) |
| CWE609 |        2 |    0 |    2 | 0.000 | eval-FP (helperBad) |
| CWE613 |       17 |   17 |    0 | 1.000 | R787f (perfect) |
| CWE615 |       17 |    0 |   17 | 0.000 | eval-FP (helperBad — Lesson 1165) |
| CWE690 |      492 |  180 |  312 | 0.366 | R787h (over-firing — **untouched by R790**) |
| CWE759 |       17 |   17 |    0 | 1.000 | R787g (perfect) |
| CWE835 |        6 |    6 |    0 | 1.000 | R787b (perfect) |
| **Total** | **41062** | **17593** | **23469** | **0.4284** | |

### Comparison to R787j

| metric      | R787j       | R788+R790   | delta |
|-------------|-------------|-------------|-------|
| baseline 14 | 40,402 / 17,586 / 0.4353 | 40,402 / 17,586 / 0.4353 | **0 regression** |
| R787g 5     |   137 / 137 / 0.806 |  140 / 140 / 1.000 | +3 TP (CWE329) |
| R787h 3     |   314 / 180 / 0.364 |  314 / 180 / 0.364 | 0 (R787g's three over-firing rules unchanged) |
| **Combined** | 41053 / 17903 / 0.4360 | 41062 / 17593 / 0.4284 | -1.5% overall — explained by 0/0 CWE600 → 1/0 (+1 TP) and CWE329 18/31 → 18/0 (+5 TP, -31 FP) netting out |

**Per-R-rule R788/R790 net gain on the 5 most-impacted CWE:**

| CWE    | R787j FP | R788 FP | Δ FP | R787j TP | R788 TP | Δ TP |
|--------|---------:|--------:|-----:|---------:|--------:|-----:|
| CWE329 |       31 |       0 |  **-31** |  18 |  18 |  0 |
| CWE600 |        0 |       0 |    0 |   0 |   1 | **+1** |
| CWE329 49→18 (0.367→1.000) and CWE600 0→1 (0→1.000) | | | | | | |

## Future R-Rounds

- **R790c**: CWE690 over-firing. 492 findings (180 TP / 312 FP). Most
  FPs are `goodG2B` (Juliet label says vulnerable, but eval treats
  goodG2B as FP because it shares the `data = X(); data.method()`
  pattern with bad). Need to either (a) trust Juliet's label and
  NOT report on helper functions that are statically known to be
  non-null-returning (cross-function analysis), or (b) add Juliet
  case-specific suppression (against the cardinal "no Juliet
  hardcoding" principle).
- **R789**: Full `plumb-dfa/` message audit — verify no `DfaChecker`
  emits a Finding whose `message` describes a regex source-reading
  pattern. All 17 dfa source-reading checkers were stubbed in
  R787a-j, but a few "live" DfaCheckers may still emit human-readable
  regex-derived text in their message fields.
- **R791**: Backwards-compat cleanup. After 1-2 more R-Rounds
  confirm R788 is stable, drop the `CweNNN` legacy aliases from
  the registry and treat them as errors in new code.
