# Juliet Java Coverage Test — Operational Kit

> Plumb static analyzer on Juliet Java v1.3 test suite.
> Per-CWE precision/FP/Recall metrics, evolved over R720 → R759-E.
> **R-PATCH v2** (Phase 1-10) shipped 2026-10-07 — rule-ID unification complete.

This directory bundles everything needed to:

1. **Reproduce** a per-CWE scan + eval run on the Juliet Java v1.3 test suite.
2. **Inspect** the rule → CWE mapping that drives per-CWE whitelist evaluation.
3. **Diagnose** 0%-Recall CWE and 100%-FP CWE without re-inventing the diagnostic steps.
4. **Read** end-of-round summaries (R757 plan → R759-E close-out).
5. **Understand** the R-PATCH v2 rule-ID unification (see `RULE-UNIFICATION-SPEC.md`).

## Contents

```
juliet-coverage/
├── README.md                          # this file
├── PITFALLS.md                        # verified "gotchas" / operational traps
├── RULE-UNIFICATION-SPEC.md           # R-PATCH v2 spec (v2.0 SHIPPED)
├── PHASE10-SUMMARY.md                 # R-PATCH v2 regression verification
├── configs/                           # per-CWE minimal yaml + R720 v7 full strategy
│   ├── r720_full_strategy.yaml        #   399 enabled rules, no Juliet hardcoding
│   ├── CWE90.yaml                     #   example: minimal per-CWE whitelist
│   ├── CWE113.yaml
│   ├── ...                            #   112 files, one per tracked CWE
├── scripts/                           # eval / compare / diagnostic scripts
│   ├── eval_findings.py               #   core: load ndjson findings, classify TP/FP
│   ├── eval_findings_strict.py        #   strict: skip driver/support/unknown/cwe-mismatch
│   ├── compare_fast.py                #   per-rule/per-CWE stats
│   ├── cwe_to_rules_v2.py             #   build per-CWE yaml whitelist from DFA-emitted rule ids
│   ├── build_rule_cwe_map.py          #   rule → CWE mapping builder
│   ├── r759d_*.py                     #   R759-D diagnostic suite (delta, recall, regress)
│   ├── r759e_delta.py                 #   R759-E per-CWE delta
│   ├── r759e_report_data.py           #   R759-E detection report aggregator
│   ├── rpatch_v2_eval.py              #   alias-rewrite R759-D ndjson to canonical rule_id
│   ├── rpatch_v2_compare.py           #   per-rule delta: pre-alias vs post-alias
│   ├── generate_json_metadata.py      #   bulk-generate rule JSON from withBuiltinDefaults()
│   └── fix_migration_names.py         #   normalise 16 illegal-id JSON files
├── reports/                           # end-of-round summaries
│   ├── R688_summary.md                #   R688 baseline
│   ├── r757/                          #   4-round plan
│   ├── r758/                          #   +0.39pp (LooseClassChecker)
│   ├── r759/                          #   B-round plan
│   ├── r759c/                         #   silent taint-drop fix
│   ├── r759d/                         #   CWE-191 100% single-file
│   └── r759e/                         #   CWE-833 + CWE-835 cross-TU registration
└── data/                              # rule maps, ground truth, javap dumps
    ├── juliet_testcases_detail.csv    #   Juliet ground truth: 9500+ (file, CWE, role, line, ...)
    ├── plumb_rule_to_cwe.csv          #   R720 v7 rule → CWE map (current, 277 rows)
    ├── plumb_rule_to_cwe.json         #   JSON variant
    ├── plumb_dfa_rule_cwe_map.csv     #   DFA-specific (subset of plumb_rule_to_cwe.csv)
    ├── plumb_cwe_to_rule.json         #   reverse index: CWE → rules
    ├── plumb_rule_cwe_map.csv         #   legacy R688 era (superseded)
    ├── list_rules.txt                 #   `--list-rules` output
    ├── rule_ids_inventory.csv         #   R-PATCH v2: 696 unique legacy rule IDs
    ├── rule_id_migration.csv          #   R-PATCH v2: 694 old→canonical mappings
    ├── rule_id_migration_summary.md   #   R-PATCH v2: migration summary
    ├── rule_id_migration_review_needed.csv  # R-PATCH v2: chunks needing review
    ├── rule_id_review_chunks.csv      #   R-PATCH v2: 30 review chunks
    ├── rule_id_classification.csv     #   R-PATCH v2: per-rule classification
    ├── a3_cross_tool_aliases.csv      #   R-PATCH v2: 73 PMD/SonarQube/SpotBugs/Coverity aliases
    ├── a3_plumb_ids.csv               #   R-PATCH v2: 51 unique Plumb rules in A3
    ├── javap_ScanRunner.txt           #   ScanRunner CLI signature dump (226 KB)
    └── javap_RuleRegistry.txt         #   RuleRegistry class structure
```

## Quick start (reproduce a per-CWE scan + eval)

### 0. Prerequisites

- Java 17+ (JDK 25 tested)
- Python 3.10+ (only standard library + `compare_fast` deps)
- Maven 3.8+ (for building Plumb)
- Juliet Java v1.3 test suite at `D:\work\tmp\Juliet_Java\`
  (CSV ground truth at `D:\work\tmp\Juliet_Java\testcases_detail.csv`)

### 1. Build Plumb

```powershell
cd D:\work\workspace\idea\engine\Plumb
mvn -DskipTests clean package
```

### 2. Pick a CWE and run scan (with per-CWE minimal yaml)

```powershell
$env:JAVA_HOME = 'D:\development\jdk-25.0.1'
$jvmOpts = '-Xmx5g --enable-native-access=ALL-UNNAMED'
$cfg = 'D:\work\workspace\idea\engine\Plumb\juliet-coverage\configs\CWE90.yaml'
$root = 'D:\work\tmp\Juliet_Java\src\testcases\CWE90_LDAP_Injection'
$out = 'D:\tmp\plumb_test\r759e_eval\CWE90.ndjson'
$jar = 'D:\work\workspace\idea\engine\Plumb\plumb-main\target\plumb-main-*.jar'

cmd /c "$env:JAVA_HOME\bin\java.exe $jvmOpts -jar $jar --config $cfg --root $root --out $out > log 2>&1"
```

### 3. Run strict eval (whitelist vs baseline)

```powershell
python D:\work\workspace\idea\engine\Plumb\juliet-coverage\scripts\eval_findings_strict.py `
  --whitelist-dir D:\tmp\plumb_test\r759e_eval `
  --baseline-dir D:\work\workspace\idea\engine\Plumb\.aethercode\r758_bulk `
  --out-dir D:\tmp\plumb_test\r759e_eval_out
```

### 4. Per-CWE delta (R759-D vs R759-E)

```powershell
python D:\work\workspace\idea\engine\Plumb\juliet-coverage\scripts\r759e_delta.py
```

See [`PITFALLS.md`](./PITFALLS.md) for verified operational traps.

## Cumulative progress (R720 v7 → R759-E)

| Round | Recall | Δ TP | Description |
|-------|-------:|-----:|-------------|
| R720 v7 | 83.42% | — | baseline |
| R741 | 93.47% | +1095 | AST-level checkers |
| R748 | 94.39% | +136 | |
| R751 | 94.40% | +1 | |
| R758 | 94.79% | +70 | LooseClassChecker composite |
| R759-B | 97.73% | +528 | silent checker fix (CWE90 + CWE606) |
| R759-C | ~97.97% | +71 | silent taint-drop fix |
| R759-D | CWE-191 100% | +72 | dest-type UNARYOP fallback |
| **R759-E (BONUS)** | **~98.4%** | **+5** | CWE833/835 cross-TU registration |

For detailed results, see `reports/<round>/SUMMARY.md`.

## R-PATCH v2 (rule-ID unification, 2026-10-07)

11+ rule-ID styles collapsed onto one canonical form:

```
plumb.<domain>.<name>     (e.g. plumb.taint.sql-injection, plumb.boundary.integer-overflow)
```

10 domains: `taint` / `dfa` / `boundary` / `ast` / `style` / `concurrency` / `resource` / `crypto` / `naming` / `info`.

**What changed**:
- 760-entry alias map (694 migration + 73 A3 cross-tool + JSON aliases)
- 280+ JSON metadata files in `plumb-rules/src/main/resources/rules/<domain>/<name>.json`
- `RuleJsonLoader` + `RuleRegistry.fromResources(ClassLoader)` (classpath auto-discovery)
- Alias resolution at `StyleContext.report()` + `FindingReporter.report()` boundaries — **640 checkers unchanged**
- `validate()` returns boolean + `mergeAliases()` for collision handling (80 collision groups auto-merged)

**Regression verification** (Phase 10): bulk rewrite of 1,613,855 R759-D findings via alias map
yielded **Total gains = Total losses = 1,044,648** (perfectly balanced). Recall and Precision
unchanged. See `PHASE10-SUMMARY.md` for the full report.

**Production smoke** (CWE-90 scan): 11 distinct rule IDs emitted, all canonical
(`plumb.unchecked-return`, `plumb.taint.ldap-injection`, `plumb.style.unused-assignment`, etc.).
No legacy IDs leak.

For the full design rationale, schema, and lessons, see `RULE-UNIFICATION-SPEC.md`.

## Constraints (do not break)

- **No Juliet-specific hardcoding in engine source.** Per-CWE evaluation config
  lives in `configs/`, not in any Java file. The yaml widening in R758 / regex
  alternation in R759-E was made general-purpose, applicable to real code too.
- **Use per-CWE minimal yaml** for scan/eval. Do NOT use `r720_full_strategy.yaml`
  for per-CWE Recall evaluation (it includes 399 rules, many of which produce
  coincidental TP/FP noise on Juliet).
- **Use a `MergedProgram`-aware checker pattern** for source-reading checkers
  (see PITFALLS.md Lesson 1056).
- **Register every checker in `ScanRunner.buildDfaCheckers()`** — checker file
  existing does NOT mean it's instantiated (Lesson 1055).

## Where to find what

| Question | Look here |
|---|---|
| What's the current CWE → rules whitelist? | `configs/CWE<N>.yaml` |
| Why is CWE-X at 0% Recall? | `PITFALLS.md` "Diagnostic sequence for 0% recall CWE" |
| What are the latest rules added? | `data/plumb_rule_to_cwe.csv` |
| What are the latest round-level gains? | `reports/r759d/SUMMARY.md`, `reports/r759e/SUMMARY.md` |
| How to run a per-CWE scan? | This file, "Quick start" §2 |
| How to compare two rounds? | `scripts/r759e_delta.py` (or `r759d_delta.py`) |
| What's the full Plumb CLI signature? | `data/javap_ScanRunner.txt` |
| What does the latest ground-truth look like? | `data/juliet_testcases_detail.csv` (8.5 MB, 9500+ rows) |
| How does rule-ID unification work? | `RULE-UNIFICATION-SPEC.md` (v2.0 SHIPPED) |
| Where's the legacy → canonical ID map? | `data/rule_id_migration.csv` (694 entries) |
| Was R-PATCH v2 regression-safe? | `PHASE10-SUMMARY.md` (gains=losses=1,044,648) |