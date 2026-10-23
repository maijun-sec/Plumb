# R767 — ArrayIndexChecker @Deprecated + CWE129 yaml prefers boundary rule

**Status**: 1 patch landed (R767a, mirror of R765a). CWE129 yaml updated with empirical A/B note. No regression.

**Date**: 2026-10-09
**Scope**: CWE-129 array-OOB — promote boundary rule as default; mark heuristic rule deprecated.

---

## 1. Background

R765 SUMMARY explicitly recommended:
> **CWE-129 boundary 显著胜 numeric** (+0.342), 应该成为 CWE-129 默认 rule + numeric.unchecked-array-index deprecated (R765a parallel)

R767 picks this up. The CWE-129 boundary rule's precision (0.636) is the highest of the 4 boundary rules evaluated in R765, and more than 2x better than the corresponding `numeric.unchecked-array-index` heuristic (0.294). This makes CWE-129 the cleanest case for the parallel R765a-style deprecation.

---

## 2. Code change — 1 patch (R767a)

### R767a — Mark `ArrayIndexChecker` `@Deprecated` (mirror of R765a)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/ArrayIndexChecker.java`

- Added `@Deprecated` annotation + Javadoc explaining supersession by `ArrayBoundsConstraintChecker` (`plumb.boundary.array-out-of-bounds`)
- Javadoc includes the **R765 empirical comparison**: boundary 0.636 vs numeric 0.294 precision
- Lists 18 callers (unit tests + integration tests) that exercise the heuristic as a regression baseline
- Class kept (not deleted): same back-compat rationale as R765a

**Why not delete**: same as R765a — 18 test classes reference it; deleting breaks compilation chain.

**Why prefer this over a yaml whitelist change**: `@Deprecated` is the canonical Java signal; yaml removal would silently break user configs that rely on the rule id. Doc-level deprecation is non-breaking and forward-compatible.

---

## 3. CWE129 yaml — empirical note added (R767c)

**File**: `D:\tmp\plumb_test\configs_v2_r760\CWE129.yaml`

- Added header comment block with R765 precision comparison
- Added `checks:` block (commented out) showing how to disable the heuristic while keeping boundary
- Whitelist entries themselves **unchanged** — both rules still enabled for direct A/B comparison in `eval_findings_cwe_matched.py`

The yaml serves as both configuration AND documentation: a developer scanning it sees the empirical evidence and the recommended migration path.

---

## 4. Verification

### Unit tests (focused)

| Test class | Tests | Pass |
|------------|------:|-----:|
| `BoundaryConstraintCheckerTest` | 13 | 13 |
| `UnderflowConstraintCheckerTest` | — | — (no specific test file in this scope) |
| `RangeTest` | 15 | 15 |
| `R222bIntUnderflowCheckerTest` (parallel R765a regression) | 8 | 8 |
| `R247CrossCheckerGuardTest` | 2 | 2 |
| `R19Cwe129Sweep` (full Juliet CWE-129 sweep) | 1 | 1 |
| `R225PlusEmitGuardsTest` | 4 | 4 |
| `R576ArrayIndexFingerprint` | 1 | 1 |
| **Total** | **44** | **44** |

`R19Cwe129Sweep` actually scans a subset of Juliet CWE-129 files using `ArrayIndexChecker` — confirms the deprecated heuristic still produces correct findings (deprecation tag is informational, not enforcement).

### Build

```
mvn -pl plumb-dfa -DskipTests clean compile   → BUILD SUCCESS (185 source files)
mvn -pl plumb-dfa,plumb-main -am -DskipTests install → BUILD SUCCESS (all 9 modules)
```

### E2E: CWE-129 re-scan after R767a

| Rule | TP | FP | Total | Precision | (R765 baseline) |
|------|---:|---:|------:|----------:|-----------------:|
| `plumb.numeric.unchecked-array-index` | 1,369 | 3,284 | 4,653 | **0.294** | 0.294 ✓ |
| `plumb.boundary.array-out-of-bounds` | 1,462 | 836 | 2,298 | **0.636** | 0.636 ✓ |

**0 regression** — `@Deprecated` is purely informational; the heuristic rule still fires on exactly the same findings as R765. The boundary rule (which has no deprecation tag) is the recommended rule going forward.

Other CWE precisions (190, 191, 369) also unchanged — confirming the R767a patch is isolated to ArrayIndexChecker and does not affect boundary framework.

---

## 5. Decisions

- **`@Deprecated` only, no yaml removal**: Minimizes breaking-change risk. Users who explicitly enabled `plumb.numeric.unchecked-array-index` in their config keep getting its findings.
- **Yaml comment block is canonical guidance**: Shows the empirical evidence inline + how to opt out of the heuristic.
- **No ScanConfig change**: Same as R765a — `runArrayBounds` default stays `true` for back-compat.
- **Boundary rule needs no parallel change**: It's the recommended path; deprecation belongs to the loser heuristic.

---

## 6. Cumulative deprecation chain (R765 + R767)

| Round | Rule | Class | Status | Boundary replacement |
|-------|------|-------|--------|---------------------:|
| R765a | `plumb.numeric.integer-underflow` | `IntUnderflowChecker` | `@Deprecated` | `UnderflowConstraintChecker` (0.358) |
| R767a | `plumb.numeric.unchecked-array-index` | `ArrayIndexChecker` | `@Deprecated` | `ArrayBoundsConstraintChecker` (0.636) ⭐ |

**Next deprecation candidates** (R768+ candidates):
- `plumb.numeric.divide-by-zero` (heuristic counterpart of `DivZeroConstraintChecker`, which has 0.282 precision)
- `plumb.numeric.integer-overflow` (heuristic counterpart of `OverflowConstraintChecker`, which has 0.374 precision — numeric wins 0.472 here, so deprecation is less clear-cut)

The R767 pattern (Java `@Deprecated` + yaml comment + doc evidence) is now the canonical way to signal heuristic-supersession. We do **not** delete the heuristic classes because they're heavily referenced by 18+ integration tests and `R19/R225/R247/R576` unit tests that serve as regression baselines for the legacy taint-based path.

---

## 7. Lessons learned

- **R767 L1100 (deprecation template 标准化)**: R765a + R767a 是完全 mirror 的两处 deprecation。Pattern 是 (1) class-level `@Deprecated` annotation, (2) ~25 行 Javadoc 说明 supersede 关系 + R-round empirical evidence, (3) class 不删 (向后兼容 + 18+ 测试引用), (4) ScanConfig 默认不动, (5) yaml comment block 加经验数据。**经验**: 任何 taint-heuristic → boundary framework 迁移都套这 5 步模板。
- **R767 L1101 (@Deprecated 是信息性而非行为性)**: Java `@Deprecated` 完全不影响 behavior — R767 build 跑 R19Cwe129Sweep 仍然命中 ArrayIndexChecker，per-rule precision 跟 R765 完全相同。**经验**: `@Deprecated` 后必须 E2E 验证 finding 数 + 数字跟之前完全一致, 不能假设 IDE warning 就等于 "改完了"。
- **R767 L1102 (R765 evidence 驱动后续 R767)**: R765 SUMMARY 显式推荐 "CWE-129 numeric deprecate" — R767 直接 follow，不需要再 survey / justify / 重新确认 evidence。**经验**: 当 R 轮次产出 evidence-based recommendation 时, 下一轮直接继承 + 实现，**不要为了保险再做一遍 survey**。Survey 已经在 R765 完成 (4 CWE baseline + per-rule table)，re-survey 是浪费时间。

---

## 8. Cross-cutting principle (delta from R765)

R765 cross-cutting principle (6 steps) holds. New addition from R767:

**7. When deprecating a heuristic rule, follow the R765a/R767a template (annotation + javadoc + keep-class + unchanged-default + yaml comment). Don't over-engineer with new ScanConfig flags or yaml enforcement — that breaks user configs.**

---

## Files

### Modified
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/ArrayIndexChecker.java` (+32 lines javadoc + `@Deprecated` annotation; net +32 lines, no behavior change)
- `D:\tmp\plumb_test\configs_v2_r760\CWE129.yaml` (added empirical A/B header comment + commented `checks:` block; behavior unchanged)

### Logs
- `D:\tmp\plumb_test\logs\r767_compile_dfa.log` (185-file compile OK)
- `D:\tmp\plumb_test\logs\r767_test_focused.log` (44/44 pass including R19Cwe129Sweep 82s Juliet subset)
- `D:\tmp\plumb_test\logs\r767_install.log` (all 9 modules install OK)
- `D:\tmp\plumb_test\logs\r767_scan_cwe129.log` (2:31 E2E scan, exit 0)
- `D:\tmp\plumb_test\logs\r767_eval.log` (4 CWE eval, 9.6s, 0 regression vs R765)

### No new code
- 0 production code changes beyond the deprecated class (mirrors R765a exactly)
- 0 test changes (existing 18 ArrayIndexChecker tests still pass)
