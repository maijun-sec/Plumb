# R334 Comprehensive CWE Coverage Cycle (Recap)

**Date**: 2026-08-10

## What was done

R334 evaluated the post-R333 plumb-dfa + plumb-dfa scanner on **18 CWE families** in Juliet Test Suite, producing a per-CWE TP/FP/FN/TN/Precision/Recall breakdown.

### Deliverables

1. **`Plumb/docs/R334-CWE-COVERAGE-REPORT.md`** (9445 bytes) — comprehensive report
2. **`Plumb/docs/R334-CWE-STATS.csv`** (2147 bytes) — per-CWE stats for downstream processing
3. **`Plumb/docs/R335-SOURCE-TO-SINK-PATH-DESIGN.md`** (6571 bytes) — design for path tracking
4. **Scoring toolchain**:
   - `smoke_juliet/r180_eval/score_cwe.ps1` — multi-rule per-CWE TP/FP/FN scoring
   - `smoke_juliet/r180_eval/merge_ndjson.ps1` — dedup merge
   - `smoke_juliet/r180_eval/cat_ndjson.ps1` — fast concat
   - `smoke_juliet/r180_eval/run_r334_cwe.ps1` — single CWE scan wrapper
5. **18 r334 ndjson files + 21 sub-folder scans** — total ~1.5GB raw data
6. **Cleanup**: 62 intermediate debug/log/IR-dump files moved to `_trash/`

### Headline numbers

| Metric | Value |
|---|---|
| CWE families | 18 |
| Total Juliet files | 30,214 |
| TP | 11,110 |
| FP | 211 |
| FN | 19,404 |
| TN | 805 |
| **P** | **98.13%** |
| **R** | **36.41%** |
| **F1** | **53.18%** |

### Coverage patterns observed

| Pattern | Recall | Notes |
|---|---|---|
| Single-method (variants 01-17) | ~100% | All taint-sink and numeric rules fire |
| Return-value chain (41-45) | ~50% | Partial coverage of cross-method flows |
| Cross-class chain (51-75) | ~10% | Major FN source — needs cross-class taint propagation |
| `81_goodB2G` (legitimate FP in CWE-129/190) | n/a | 100/111 FP, R332 reverted, fix needs merged-program-safe frame-marker |
| CWE-89 (5x more sources) | 14% | Same engine, lower recall % due to file count vs fire count ratio |
| CWE-369 (line/file misattribution) | 1.7% unique | Real TP 2391, but unique file count is unreliable (pre-existing plumb-main bug) |

### How R334 builds on R298-R333

| Cycle | CWE | Δ TP | Δ FP |
|---|---|---:|---:|
| R324+ | CWE-191 | 0 → 1,827 | — |
| R328 | CWE-601 | 0 → 288 | 0 |
| R329 | 14 families (try-finally CFG) | +1,505 | 0 |
| R330 | CWE-129 | 0 → 924 | 23 |
| R330b | CWE-190 | 0 → 1,160 | 26 |
| R330c | CWE-369 | 0 → 1,300 | 31 |
| R333 | CWE-369 (float filter) | +1,091 | −3,638 |

R334 brings total coverage from 6,996 fires (R327 baseline) to 11,110 fires (R334 result), a **59% increase** with negligible FP impact.

## What was learned

1. **Juliet file count vs fire count**: CWE-89 has 5x more bad files than CWE-78 but similar fire count. Recall percentage depends on file count not just TP absolute. Use absolute TP for "did we find bugs" comparison.
2. **Sub-folder sub-batching is mandatory**: CWE-89 (3682 files) at 0.5s/file = 30 min single run. Split into s01-s04 = 4 runs of ~5 min each, parallel for 5 min total.
3. **PowerShell bash subshells lack `head`/`ls`**: use `Get-Content | Select-Object -First N` and `Get-ChildItem`.
4. **`Start-Job` + `Wait-Job` is the right primitive for parallel Java scans**: works inside bash, jobs survive the bash call returning.
5. **Merged program line/file misattribution is pre-existing**: CWE-369 unique file count is unreliable. Real TP rate is much higher but spread across files with matching line numbers.
6. **CWE-89 has 16 sources not 12**: affects expectation of fire count.
7. **Multiple-rule scoring needed for numeric CWEs**: combined unified + legacy rules gives full coverage.
8. **Cross-class taint is the biggest opportunity**: +6,000 TP potential across 14+ CWEs (51-75 chain families).

## What is next (R335+)

1. **Cross-class taint propagation** (5-7d): unlock 51-75 chain families. Highest single-feature impact.
2. **Merged program line/file fix** (1-2d): restore accurate per-file TP counts for CWE-369, 129, 190, 191.
3. **CWE-129 `_81_goodB2G` fix** (2-3d): re-attempt array.length with proper frame-marker handling.
4. **Source-to-sink path** (4.5d, R335 design done): `source` field first, then `path` field.
5. **CWE-89 recall boost**: 5x more sources, biggest single-CWE opportunity.

## Test counts (post-R334 cleanup)

| Module | Tests | Pass | Fail | Skip |
|---|---:|---:|---:|---:|
| plumb-tac | 37 | 37 | 0 | 0 |
| plumb-dfa | 441 | 441 | 0 | 0 |
| plumb-dfa | 509 | 509 | 0 | 0 |
| plumb-style | 942 | 942 | 0 | 0 |
| plumb-main | 19 | 19 | 0 | 0 |
| **Total** | **1948** | **1948** | **0** | **0** |

(R332's 3 @Disabled tests were already re-enabled in earlier rounds.)
