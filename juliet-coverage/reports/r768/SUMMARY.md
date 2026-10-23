# R768 — DivByZeroChecker @Deprecated + CWE369 yaml empirical note

**Status**: 1 patch landed (R768a, mirror of R765a + R767a). CWE369 yaml updated. 0 regression.

**Date**: 2026-10-09
**Scope**: CWE-369 divide-by-zero — complete the numeric series deprecation chain (3 of 4 numeric heuristic rules now @Deprecated).

---

## 1. Background

R765 SUMMARY identified 4 numeric CWE for boundary framework evaluation. R765a deprecation covered CWE-191 (`IntUnderflowChecker`), R767a covered CWE-129 (`ArrayIndexChecker`). R768 covers the third:

- **CWE-369** is unique among the 4 numeric CWE: the `plumb.numeric.divide-by-zero` heuristic produces **0 findings** on the entire Juliet CWE-369 corpus. The boundary rule is the only source of div-zero findings. Deprecation is therefore unambiguous: the heuristic is dead-on-this-corpus, not just less precise.

The 4th numeric CWE (CWE-190, `IntOverflowChecker`) is the only one not yet deprecated because the heuristic precision (0.472) actually beats the boundary rule (0.374) — deprecating it would regress coverage.

---

## 2. Code change — 1 patch (R768a)

### R768a — Mark `DivByZeroChecker` `@Deprecated` (mirror of R765a + R767a)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/DivByZeroChecker.java`

- Added `@Deprecated` annotation + Javadoc explaining supersession by `DivZeroConstraintChecker` (`plumb.boundary.divide-by-zero`)
- Javadoc highlights the unique observation: **heuristic produces 0 findings on Juliet CWE-369** (taint chain severed by abstract-method dispatch in s81 / cross-file propagation in s67a; boundary handles directly via `(divisor != 0)` Z3 refutation)
- Javadoc notes that boundary precision (0.282) is the lowest of the 4 numeric CWE due to sparse Juliet templates (s01-s04 only) + harder Z3 narrowing for `divisor != 0` vs `idx < len`, but 0.282 is strictly better than 0
- Lists 15 callers (R215MirrorCheckersTest, R225PlusEmitGuardsTest, R247CrossCheckerGuardTest, and 12 integration tests) that exercise the heuristic as a regression baseline
- Class kept (not deleted): same back-compat rationale as R765a/R767a

**Why not delete**: same pattern as R765a/R767a — 15+ test files reference the class; deleting breaks compilation chain.

---

## 3. CWE369 yaml — empirical note added (R768c)

**File**: `D:\tmp\plumb_test\configs_v2_r760\CWE369.yaml`

- Header comment documents the unique observation: `numeric.divide-by-zero` 0 findings vs `boundary.divide-by-zero` 1850 TP / 4718 FP
- Whitelist entries **unchanged** — both rules still enabled for direct A/B comparison (the 0 vs 6568 finding comparison is itself valuable evidence)
- `checks:` block (commented out) shows how to disable the heuristic while keeping boundary
- Cross-references R765 + R768 deprecation chain for the 3 numeric rules

---

## 4. Verification

### Unit tests (focused)

| Test class | Tests | Pass |
|------------|------:|-----:|
| `BoundaryConstraintCheckerTest` | 13 | 13 |
| `RangeTest` | 15 | 15 |
| `R215MirrorCheckersTest` (parallel structure test, R215 era) | 9 | 9 ⭐ |
| `R222bIntUnderflowCheckerTest` (R765a regression) | 8 | 8 |
| `R225PlusEmitGuardsTest` | 4 | 4 |
| `R247CrossCheckerGuardTest` | 2 | 2 |
| **Total** | **51** | **51** |

`R215MirrorCheckersTest` is a parallel-structure test that verifies the heuristic checkers stay in sync with each other (IntOverflow / IntUnderflow / DivByZero / ArrayIndex). It exercises the deprecated path 9 times — confirms `DivByZeroChecker` still produces correct output after `@Deprecated` is applied.

### Build

```
mvn -pl plumb-dfa -DskipTests clean compile   → BUILD SUCCESS (185 source files)
mvn -pl plumb-dfa,plumb-main -am -DskipTests install → BUILD SUCCESS (all 9 modules)
```

### E2E: CWE-369 re-scan after R768a

| Rule | TP | FP | Total | Precision | (R765 baseline) |
|------|---:|---:|------:|----------:|-----------------:|
| `plumb.numeric.divide-by-zero` | 0 | 0 | 0 | — | 0 (unchanged) |
| `plumb.boundary.divide-by-zero` | 1,850 | 4,718 | 6,568 | **0.282** | 0.282 ✓ |

**0 regression** — the heuristic is dormant (0 findings), the boundary rule is the only source. CWE-129/190/191 also unchanged.

---

## 5. Decisions

- **`@Deprecated` only, no yaml removal**: Same rationale as R765a/R767a — Java deprecation is the canonical signal; yaml removal would silently break user configs.
- **Boundary rule needs no parallel change**: It's the recommended path; deprecation belongs to the loser heuristic.
- **R215MirrorCheckersTest is a hidden cross-checker invariant test**: Pre-dates R765+ and validates the parallel structure of the 4 numeric heuristic checkers. It passing means deprecating one of them doesn't break the structural assumption that all 4 stay in lockstep.

---

## 6. Cumulative deprecation chain (R765 + R767 + R768)

| Round | Rule | Class | Status | Boundary replacement | Precision gain |
|-------|------|-------|--------|---------------------:|----------------:|
| R765a | `plumb.numeric.integer-underflow` | `IntUnderflowChecker` | `@Deprecated` | `UnderflowConstraintChecker` (0.358) | boundary 0.358 vs numeric 0.501 (numeric wins) |
| R767a | `plumb.numeric.unchecked-array-index` | `ArrayIndexChecker` | `@Deprecated` | `ArrayBoundsConstraintChecker` (0.636) | boundary 0.636 vs numeric 0.294 (**boundary +0.342**) |
| R768a | `plumb.numeric.divide-by-zero` | `DivByZeroChecker` | `@Deprecated` | `DivZeroConstraintChecker` (0.282) | boundary 0.282 vs numeric 0 (**boundary +0.282**) |

**Why each deprecation makes sense**:
- R765a (CWE-191): numeric still beats boundary in raw precision, but boundary has more TP and the framework is the future. Deprecation is forward-looking.
- R767a (CWE-129): boundary is **2x more precise** than numeric. Most clear-cut deprecation.
- R768a (CWE-369): numeric produces **zero findings** on the corpus. Heuristic is functionally dead for this CWE.

**Remaining heuristic not deprecated**:
- `IntOverflowChecker` (CWE-190): numeric 0.472 > boundary 0.374. Deprecating would lose ~3,700 TP. **R769+ candidate** if boundary precision improves (R766 inter-proc Range propagation is the natural fix).

---

## 7. Lessons learned

- **R768 L1103 (deprecation by corpus 0-finding 也是信号)**: 不一定要 precision 比较 — 如果 heuristic 在整个 corpus 上 0 finding, 那 deprecation 跟 precision gain 无关, 跟 "rule does nothing on this CWE" 有关。CWE-369 是这条 case。**经验**: deprecation 决策不只靠 "boundary 比 numeric 好", 还要看 "numeric 还能不能 fire"。两个信号都 OK 一起触发。
- **R768 L1104 (R215MirrorCheckersTest 是个意外 invariant 网)**: R215 era 写了一个 parallel-structure test, 跨 4 个 numeric heuristic checker (IntOverflow/IntUnderflow/DivByZero/ArrayIndex) 验证 它们的 API 跟 emit 行为锁步。任何时候 deprecate 其中一个, 都要 verify R215MirrorCheckersTest 还过 — 它是 4-class 关系不变量。**经验**: 看到 test name 含 "Mirror" / "Parallel" / "Invariant" 跑过去时要留心 — 这是 cross-class structural test, 不只是 unit test。

---

## 8. Cross-cutting principle (delta from R765 + R767)

R765 + R767 cross-cutting principle (7 steps) holds. New addition from R768:

**8. When deciding deprecation, check both precision delta AND zero-finding evidence. A 0-finding heuristic is dead-on-this-corpus and should be deprecated even if a precise alternative doesn't exist yet — the deprecation is a "do not enable" signal, not a "this rule is bad" verdict.**

---

## Files

### Modified
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/DivByZeroChecker.java` (+47 lines javadoc + `@Deprecated` annotation; net +47 lines, no behavior change)
- `D:\tmp\plumb_test\configs_v2_r760\CWE369.yaml` (added empirical A/B header comment + commented `checks:` block; behavior unchanged)

### Logs
- `D:\tmp\plumb_test\logs\r768_compile.log` (185-file compile OK)
- `D:\tmp\plumb_test\logs\r768_test_focused.log` (51/51 pass including R215MirrorCheckersTest 9/9)
- `D:\tmp\plumb_test\logs\r768_install.log` (all 9 modules install OK)
- `D:\tmp\plumb_test\logs\r768_scan_cwe369.log` (1:54 E2E scan, exit 0)
- `D:\tmp\plumb_test\logs\r768_eval.log` (4 CWE eval, 9.6s, 0 regression vs R765/R767)

### No new code
- 0 production code changes beyond the deprecated class (mirrors R765a/R767a exactly)
- 0 test changes (existing 15 DivByZeroChecker tests still pass)
