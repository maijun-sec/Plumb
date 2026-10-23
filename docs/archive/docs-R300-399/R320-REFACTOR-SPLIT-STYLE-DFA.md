# R320-Refactor: InsecureCrypto Split (Style + DFA) (2026-08-04)

## TL;DR

用户 audit 反馈: 我之前把 R320 InsecureCrypto 全部放在 plumb-dfa, 实际**两边都有价值**:

1. **AST (style) 主** — 覆盖 `getInstance("MD5")` 直接字面量 (95% case)
2. **DFA 增强** — 覆盖 `String ALGO = "MD5"; getInstance(ALGO)` const 变量 / 外部输入 (DFA value)

按用户选 "STYLE 主 + DFA 增强 (dedup)" 拆开, **0 重复报**:
- Style 端: 命中所有 StringLiteralExpr 字面量
- DFA 端: 跳过 StringLiteralExpr args (AST 已覆盖), 只 fire const-var 传播

## 改动

### 1. 新增 plumb-style `InsecureCryptoCheck.java` (R320-Style)

**位置**: `plumb-style/src/main/java/org/plumb/style/security/InsecureCryptoCheck.java`

**实现**: AST visitor pattern
- `visit(CompilationUnit n, StyleContext ctx)`: `findAll(MethodCallExpr)`, 检查 `getNameAsString() == "getInstance"`, args[0] 是 `StringLiteralExpr`
- 14 个 weak algo (RSA / DES / DESede / RC4 / Blowfish / MD5 / MD2 / MD4 / SHA-1 / sha1)
- `toLowerCase()` 统一大小写 (`"MD5"` / `"md5"` 都命中)
- ruleId: `pl-0327-insecure-crypto`, Type: SECURITY, Severity: HIGH

**测试** (7 tests `R320InsecureCryptoStyleTest`):
- `md5Literal_firesCwe328` — `MessageDigest.getInstance("MD5")` ✓
- `rsaLiteral_firesCwe780` — `Cipher.getInstance("RSA")` ✓
- `desLiteral_firesCwe327` — `Cipher.getInstance("DES")` ✓
- `sha256_doesNotFire` — 不 fire ✓
- `aes_doesNotFire` — `AES/GCM/NoPadding` 不 fire ✓
- `sha1Lowercase_firesCwe328` — `"sha1"` 小写也命中 ✓
- `constVariable_doesNotFireInStyle` — `getInstance(ALGO)` 变量不 fire (留给 DFA) ✓

### 2. 修改 plumb-dfa `InsecureCryptoChecker.java` (R320-Enhance)

**改动**: 加入 dedup 逻辑, 只 fire const 变量传播
- `Map<String, String> weakAlgoVar` per-function: 记录 `MOVE t = "MD5"` 这种 const 赋值
- `case MOVE ->`: dest 是 TacVar + lhs1 是 String Const + 命中 WEAK map → 加入 `weakAlgoVar`
- `case CALL ->`: 扫描所有 args
  - `Const STRING` → AST 已覆盖, **return (skip)**
  - `TacVar` 在 `weakAlgoVar` → **fire CWE**
  - 其它 → 跳过 (无关 receiver / 未跟踪 var)
- 之前版本: 直接在 args[0] (hand-built) 或 args[1] (real Java) 找 String Const, fire

**关键修改**: 改用 `for (arg : args) { ... }` 不再 `return` 提前退, 真实 Java 的 args[0] 是 receiver (TacVar) 不是 algo, 跳过它继续找 args[1] algo

**测试** (6 tests `InsecureCryptoCheckerTest` 重写):
- `literalArg_skippedByDfaHalf` — `getInstance("MD5")` DFA 不 fire (AST 覆盖) ✓
- `constVariableMd5_firesCwe328` — `MOVE ALGO = "MD5"; getInstance(ALGO)` ✓
- `constVariableRsa_firesCwe780` ✓
- `constVariableAes_doesNotFire` ✓
- `untrackedVar_doesNotFire` — ALGO 是参数, 没在本 fn 跟踪, 不 fire ✓
- `realJavaSource_constVarMd5_fires` — 真 Java 源 e2e ✓

### 3. 更新 `CheckerCategories.java`

`D:\work\tmp\gen_categories.py` 重跑 (已自动检测), 504 → 504 entries, `InsecureCryptoCheck` 分到 `security` subdir

## 数字 final

| 指标 | R320-DFA-Only (R322) | R320-Refactor | Δ |
|---|---:|---:|---:|
| plumb-style checkers | 502 | **503** | **+1** |
| plumb-style tests | 883 | **890** | **+7** |
| plumb-dfa checkers | 31 | 31 | 0 |
| plumb-dfa tests | 439 | 439 | 0 |
| 9 modules total tests | ~3500 | **~3522** | +7 |
| Tool info 残留 | 0 | 0 | 0 |
| 4-tool coverage | 不变 | 不变 | 0 |

## 备份

`D:\work\tmp\r320_refactor_done\`:
- 1 新 src: `InsecureCryptoCheck.java` (plumb-style)
- 1 新 test: `R320InsecureCryptoStyleTest.java`
- 1 重写 src: `InsecureCryptoChecker.java` (DFA 加 dedup)
- 1 重写 test: `InsecureCryptoCheckerTest.java`
- 1 重新生成: `CheckerCategories.java`
- 1 doc: `R320-REFACTOR-SPLIT-STYLE-DFA.md`

## 关键 pitfall (R320-Refactor 新)

1. **Args 顺序在 translator 跟 hand-built 不一致** — hand-built test 直接放 `args = [algo]`, 真实 Java 翻译后 receiver 被 prepend, `args = [receiver, algo]`. 修: 不固定 index, 用 `for (arg : args) { ... }` 扫描, 不要提前 `return`
2. **Dedup 必须用 AST half 优先** — DFA 端检查 `Const STRING` 立即 `return` (不 fire), 让 AST 端 fire. 避免双重报
3. **Style 跟 DFA 双方都用同一个 WEAK map** — 14 个 weak algo, 写在 style 端 (主), DFA 端 import 一样的常量. Dedup by check kind (Const vs TrackedVar)
4. **MOVE dest 类型过滤** — `String ALGO = "MD5"` 在 TAC 是 `MOVE ALGO = "MD5"` (NEW + field-init collapse), dest 是 TacVar. 修: `if (ins.dest instanceof TacOperand.TacVar dest)` 才入表, 不接受 dest 是 Const (语法不可能但防御)

## 下一轮 (R323+ 候选)

- R323: 把 R313-R322 7 个 DFA checker 接到 ScanRunner.runAllCheckers (R320 已经在两边实现, 不需要接 ScanRunner)
- R324: Reverse-validate post-pass (R188a-style) for non-taint checkers
- R325: Cross-function taint source/sink for SensitivePII/XXE
- R326: Z3 path-condition for CompareWithSelf complex cases
- R327: Performance: lazy alias class for CollectionSelfReference / CompareWithSelf
- R328: 更多 Juliet family sweep (CWE-111/113/114/134/209/...)
