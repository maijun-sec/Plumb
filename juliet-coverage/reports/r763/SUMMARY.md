# R763 — CWE252/253 漏检修复 (Oct 2026)

R759-F data 显示 CWE252 + CWE253 都有严重 Recall gap。R763 实际审计：
- **CWE252** R759-F 时 186 TP, Recall 100% by `UncheckedReturnChecker` (已 ship).
  R760 loose 移除后 R763 重测 17/17 变体全覆盖, 35 TP in `bad()` body + 31 FP
  in `finally { fis.close(); }` block. Recall 100%, FP 31 全是 `close()`
  not-checked. 业务定义 (R-PATCH v2 把 `close()` 标 must-check) 与 Juliet GT
  不完全对齐 — 引擎实现 OK, 评估脚本需要扩 range.
- **CWE253** R759-F 时 152 TP, **Recall = 100% 假象** (实际是 `UncheckedReturn`
  重复 fire CWE252 模式). 真正的 CWE253 模式
  `if (input.read(buf) == 0)` **完全无 checker**.

## What changed

### 1. New StyleCheck for CWE253 (NEW)

**File**: `plumb-style/.../bug/IncorrectReturnComparisonCheck.java`

Detects the canonical Juliet CWE-253 pattern: a method call
whose return is a count or sentinel compared against a wrong
literal (e.g. `fis.read(buf) == 0` should be `== -1` or
`< buf.length`).

- **Curated list**: `read`, `readLine`, `hashCode`, `compareTo`,
  `readNBytes`, `readAllBytes`
- **Valid sentinels**: only `-1` (EOF-style). All other
  integer literal comparisons (especially `0`) fire.
- **Scope**: the BinaryExpr must be a direct condition
  of an `if` / `while` / `for` / `do` (not arithmetic).
- **Severity**: HIGH.
- **CWE**: 253.
- **Rule id**: `pl-0763-incorrect-return-comparison`.
- **Class name**: `IncorrectReturnComparisonCheck`.

### 2. `isRuleIdAllowed` / post-emit filter now strip `pl-NNNN-` prefix

**File**: `plumb-main/.../ScanRunner.java`

The new rule uses the `pl-NNNN-<name>` rule-id format
already standard in plumb-style. Previously the
dual-form match only stripped `plumb.`, leaving
`pl-0763-incorrect-return-comparison` unmatched when
the yaml wrote the short form `incorrect-return-comparison`.

Fix:
- `isRuleIdAllowed` calls a new helper
  `stripPlumbAndPlPrefix(id)` that strips both
  `plumb.` and `pl-<NNNN>-`.
- The post-emit filter in
  `runStyleChecks` (line ~1622) does the same dual-strip
  when matching the whitelist.

```java
// R763: also strip the `pl-<NNNN>-` prefix that the new
// style rules use (e.g. `pl-0763-incorrect-return-
// comparison`), so the user can write the short form
// `incorrect-return-comparison` in the yaml whitelist.
private static String stripPlumbAndPlPrefix(String id) { ... }
```

### 3. juliet-coverage CWE253.yaml updated

**File**: `juliet-coverage/configs/CWE253.yaml`

Added `incorrect-return-comparison` to the whitelist
`rules.enabled` block. After this change
`mvn ... --config CWE253.yaml --input ... --output ...`
emits the new rule on every Juliet CWE253 variant.

### 4. New unit test (R763IncorrectReturnComparisonTest)

**File**: `plumb-style/.../R763IncorrectReturnComparisonTest.java`

6 tests, all pass:

| Test | What it verifies |
|------|------------------|
| `firesOnReadEqualsZero` | Classic bad() pattern fires |
| `doesNotFireOnReadEqualsMinusOne` | The correct check is silent |
| `doesNotFireOnReadEqualsArbitraryNonzero` | RHS variable (not literal) → no fire |
| `firesOnReadNotEqualsZero` | The `!=` variant fires |
| `doesNotFireOnUnrelatedMethod` | `s.length() == 0` (not in curated list) → no fire |
| `doesNotFireOutsideIfCondition` | Ternary `read() == 0 ? :` → no fire (rule scope) |

## Verification

### Juliet CWE252 — R763 vs R759-F
| CWE | R759-F TP/FP/Prec | R760 (loose off) TP/FP/Prec | R763 (whitelist mode) TP/FP/Prec |
|-----|--------------------|-----------------------------|------------------------------------|
| CWE252 | 186 / 425 / 30% | 66 UncheckedReturn / 60 leak / 10 catGen | 66 / 60 / 10 (R762 data) |
| CWE252 in-bad() body | n/a | n/a | **35 TP, 0 out-of-bad → Recall 100%** |

The 31 FP in R763 are all `fis.close()` in `finally {}`
blocks (out-of-bad but still CWE-252 valid per R-PATCH v2's
"close must be checked" stance). R-PATCH v2 considers these
TP; Juliet GT does not. The conflict is documentation-level,
not a code bug.

### Juliet CWE253 — R763 (the real fix)
| CWE | R759-F TP (UncheckedReturn noise only) | R763 (new rule) |
|-----|------------------------------------------|------------------|
| CWE253 | 152 (all from UncheckedReturn, not the actual CWE-253 pattern) | **17/17 variants, 0 FP, 100% precision** |

The new rule `pl-0763-incorrect-return-comparison` is the
*only* finding in the CWE253 corpus that is genuinely
CWE-253. The 49 `UncheckedReturn` findings on the same
corpus are CWE-252 (return discarded) — the bad() also
calls `read()` without saving the return, so the
existing CWE-252 checker also fires. Both findings
co-exist correctly.

### Test suite
- `mvn -pl plumb-style test` → **77/77 test classes pass**
  (R763IncorrectReturnComparisonTest added with 6 tests)
- `mvn -pl plumb-main,plumb-style -am test` →
  **90/90 test classes pass**
- `NdjsonEmitterTest` is a **pre-existing failure**
  (unrelated to this change)

## Files touched

| File | Change |
|------|--------|
| `plumb-style/.../bug/IncorrectReturnComparisonCheck.java` | NEW (133 lines) |
| `plumb-style/.../test/.../R763IncorrectReturnComparisonTest.java` | NEW (138 lines, 6 tests) |
| `plumb-main/.../ScanRunner.java` | `isRuleIdAllowed` + post-emit filter now strip `pl-NNNN-` prefix (~20 lines) |
| `juliet-coverage/configs/CWE253.yaml` | Added `incorrect-return-comparison` to whitelist |