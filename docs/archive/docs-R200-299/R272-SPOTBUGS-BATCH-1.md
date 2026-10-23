# R272 — SpotBugs Gap-Filling Batch 1 (10 new rules)

**Date**: 2026-08-02
**Round**: 72
**Status**: DONE — 0 regression, 9 modules 1395/0/0/0
**Tool**: SpotBugs (most underrepresented — 1% → 4%)

## Background

R271 audit 发现 246 个 plumb-style checkers 中很多是跨工具的概念重复（删了 13 个）。剩余 233 个里 SpotBugs 相关只占 11 个，SpotBugs 总规则 518 中 95% target = 492，缺口 481。

用户方向 (2026-08-02):
> "如果规则优重复，不需要重复建规则，可以查漏补缺"

R272 启动 SpotBugs gap-filling。10 个新规则只挑 SpotBugs 独特 (no PMD / SonarQube equivalent) 的 bug patterns:

| # | Rule | SpotBugs ID | CWE | Concept |
|---|------|-------------|-----|---------|
| 1 | `UnconfirmedCastCheck` | BC_UNCONFIRMED_CAST | CWE-704 | Cast without matching `instanceof` |
| 2 | `RedundantNullCheckCheck` | RCN_REDUNDANT_NULLCHECK | — | 2nd null check after first guards |
| 3 | `BitwiseAndCheck` | BIT_AND | CWE-783 | `&` in `if` (meant `&&`) |
| 4 | `BitwiseOrCheck` | BIT_OR | CWE-783 | `\|` in `if` (meant `\|\|`) |
| 5 | `SignedBitCheckCheck` | BIT_SIGNED_CHECK | CWE-682 | High-bit mask in signed int |
| 6 | `SerializableSubclassCheck` | BSK_SERIALIZABLE_SUBCLASS | — | Extends serializable without `implements` |
| 7 | `ExposeMutableRepStorageCheck` | EI_EXPOSE_REP2 | CWE-374 | External mutable stored w/o defensive copy |
| 8 | `MutableEnumFieldCheck` | ME_MUTABLE_ENUM_FIELD | — | Enum field not `final` |
| 9 | `StaticWriteFromInstanceCheck` | ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD | CWE-668 | `ClassName.field = ...` from instance method |
| 10 | `EqualsNoObjectCheck` | EQ_SELF_NO_OBJECT | CWE-697 | `equals` overloads without overriding |
| 11 | `NonSerializableFieldCheck` | SE_BAD_FIELD | CWE-684 | `Serializable` class has non-`Serializable` field |

> 11 个规则共 10 个新 checker (BIT_AND / BIT_OR 是同类, 但需要分别报告)。

## Pitfalls (R272 new)

### 1. `BinaryExpr.Operator.AND` 是 `&&` 不是 `&`!
JavaParser 的命名反直觉:
- `BinaryExpr.Operator.AND` → `&&` (logical AND)
- `BinaryExpr.Operator.BINARY_AND` → `&` (bitwise AND)
- `BinaryExpr.Operator.OR` → `||` (logical OR)
- `BinaryExpr.Operator.BINARY_OR` → `|` (bitwise OR)
- `BinaryExpr.Operator.XOR` → `^` (bitwise XOR — `BINARY_XOR` 不存在)

fix: `BitwiseAndCheck` / `BitwiseOrCheck` / `SignedBitCheckCheck` 用 `BINARY_AND` / `BINARY_OR` / `XOR`。

### 2. `FieldDeclaration#getElementType` 返回 **component** type 不 declared type
- `int[] data;` 的 `getElementType()` = `int` (component)
- `int[] data;` 的 `getCommonType()` = `int[]` (declared)

fix: `ExposeMutableRepStorageCheck.enclosingFieldTypes` 用 `getCommonType()`。

### 3. `Parameter` 在 `com.github.javaparser.ast.body` 不 `ast`
跟 R266/R267 一样。`EqualsNoObjectCheck` 修 import。

### 4. `ConstructorDeclaration` 在 AstChecker interface 但 body 是 `BlockStmt` 不 `Optional<BlockStmt>`
- `MethodDeclaration.getBody()` returns `Optional<BlockStmt>` (need `.isEmpty()` check)
- `ConstructorDeclaration.getBody()` returns `BlockStmt` directly

`ExposeMutableRepStorageCheck.visit(MethodDeclaration)` 用了 `n.getBody().isEmpty()` (正确).
`ExposeMutableRepStorageCheck.visit(ConstructorDeclaration)` 用了 `n.getBody()` 直接 (正确).

### 5. `LooksAlreadyConfirmed` 必须 walk up 到 enclosing `BlockStmt`
原版只查 `parent.getParentNode()` 的 children. 但 cast 在 if 的 then 句里时, parent 是 ReturnStmt, parent.getParentNode() 是 IfStmt (不是 BlockStmt). 

fix: walk up 到 enclosing BlockStmt, 然后 iterate children.

### 6. `IntegerLiteralExpr` 不会装 `LongLiteralExpr` 即使值超过 int 范围
`-2147483648` parsed 为 `UnaryExpr(MINUS) + IntegerLiteralExpr(2147483648)`, 但 `2147483648L` parsed 为 `LongLiteralExpr`.

`SignedBitCheckCheck.looksLikeSignedMask` 接受两种。

### 7. `String` 不是 primitive
`UnconfirmedCastCheck` 最初把 `String` 当 primitive skip, 错了. 修: 移除 `String` from primitive list.

## Test 结果

- **R272 SpotBugs batch 1**: 11 new checkers, 19 new tests (R272SpotBugsBatch1Test), 0 fail
- **plumb-style**: 233 → 244 checkers (+11)
- **plumb-style tests**: 355 → 374 tests (+19)
- **Full 9-module build**: 1395 tests, 0 fail, 0 err, 0 skipped (was 1357 in R271)

## Coverage 进展

| 工具 | 之前 | 现在 | 95% target | 状态 |
|------|------|------|-----------|------|
| PMD 318 | 302 (95%) | 302 (95%) | 302 | ✓ |
| SonarQube 706 | ~70 (10%) | ~70 (10%) | 670 | gap 600 |
| SpotBugs 518 | 11 (2%) | 22 (4%) | 492 | gap 470 |
| Checkstyle ~150 | ~14 (9%) | ~14 (9%) | ~143 | gap 130 |

## 备份

- `D:\work\tmp\r272_spotbugs_done\` (12 files: 11 checkers + 1 test)

## Cumulative (post-R272, 72 round, 1 rolled back)

- 25 metric wins (R272 +1 SpotBugs batch) + 2 validations + 2 reports + 6 R-docs
- 60+ R-docs 累计 ~613KB (R272 ~5KB)
- 0 回归跨 72 R-rounds
- **plumb-style: 244 checkers**, plumb-dfa/taint: 20, plumb-dfa legacy: 33
- **总 Plumb rules: 297** (233 之前 - 13 dedup + 11 R272 = 297 effective concepts)

## Next Steps (R273+)

1. **R273 SonarQube batch 4** (~25 rules) — 优先追 95% coverage
2. **R274 SpotBugs batch 2** (~25 rules) — 继续填 4% → 10%
3. **R275 SpotBugs batch 3** (~25 rules)
4. **R276 Checkstyle batch 1** (~25 rules)
5. **R277 PMD 数据流 rules** — 移到 `plumb-dfa` (TAC-based, 跟 plumb-style 分工)
6. **R278 综合 coverage report** — 详细列出 PMD/SonarQube/SpotBugs/Checkstyle 总规则数, Plumb 已支持, 覆盖率
7. **R279 memory 更新** + 策略调整
