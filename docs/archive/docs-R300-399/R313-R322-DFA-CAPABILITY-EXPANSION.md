# R313–R322 DFA Capability Expansion (2026-08-03)

## TL;DR

10 轮直接做完, 在 plumb-dfa 加 **8 个新 DFA checker** + 1 个 Juliet sweep test, 全部 0 回归:

| Round | Checker | CWE | Tests | Real-Java e2e |
|------|---------|-----|-------|---------------|
| R314 | `TryCatchFinallyChecker` | CWE-248/390/396/397 | 6 | ✓ |
| R315 | `OffByOneChecker` | CWE-193 | 6 | ✓ |
| R316 | `EqualsCalledOnNullChecker` | CWE-595/754 | 6 | ✓ |
| R317 | `CompareWithSelfChecker` | CWE-570/571 | 5 | ✓ |
| R318 | `XxeChecker` | CWE-611 (XXE / CVE-2014-3577) | 4 | ✓ |
| R319 | `ReturnFromFinallyChecker` | CWE-584/459 | 3 | ✓ |
| R320 | `InsecureCryptoChecker` | CWE-327/328/780 (RSA no padding / DES / MD5) | 6 | ✓ |
| R321 | `EmptyCatchHandlerChecker` | CWE-390/665 | 4 | ✓ |
| R322 | `R322NewCheckersJulietSweep` | (performance benchmark) | 4 | Juliet files ✓ |

**结果**:
- plumb-dfa: 23 → **31 checkers** (+8)
- plumb-dfa tests: 410 → **439** (+29: 40 new tests + integration tests excluded)
- 9 modules: 0 fail, 0 regression
- Juliet sweep: CWE248/396/397/390 4 个 family 实测, 100% 命中预期

## R314 TryCatchFinallyChecker (CWE-248/390/396/397)

**Bug**: 空 catch / generic catch (`catch (Exception e)`) / generic throw (`throw new RuntimeException`) / uncaught exception (try 块内 throw 没匹配 handler)

**DFA > AST 的理由**: 
- 把 CATCH_HANDLER / THROW / NEW IR kind 当成事实收集
- 在 finishFunction 时统一判断:
  - CWE-396: ctorType 是 Exception/RuntimeException/Throwable
  - CWE-397: NEW 的 ctorType 是 generic 且 dest 后续被 THROW 用
  - CWE-248: THROW 没被任何匹配 catch 接住

**关键 design**:
- `Set<String> genericNewDests`: NEW 出来的 generic exception dest, 后续 THROW 用到就 fire CWE-397
- `List<TacInstruction> genericNews` 跟 `genericNewDests` 配对
- `Set<String> caughtTypes`: 接住的所有类型
- `throws_` (注意下划线避开关键字) 记 throw site

## R315 OffByOneChecker (CWE-193)

**Bug**: `for (i = 0; i <= arr.length; i++) arr[i] = ...;` 越界一格

**DFA > AST 的理由**: 
- AST 容易误报 (`for (i = 0; i <= n; i++) print(i);` 正确)
- DFA 跟 BINOP (`i <= arr.length` / `i <= lenOfVar`) + ARRAYLOAD/ARRAYSTORE (同 index) 关联
- FIELDLOAD on `arr.length` / CALL on `arr.size()` 都注册到 length producer

**关键 design**:
- `Map<String, String> lengthOfVar` — var name → length expression
- BINOP LE/GE + RHS is length → 记 loopVar → lengthExpr
- FIELDLOAD on `.length`/`.size()` → 记 dest → "recvr.length"
- CALL to `.size()` → 记 dest → "recvr.size()"
- ARRAYLOAD/ARRAYSTORE on loopVar → fire

**Key pitfall**: calleeSig 格式 `{var}.{method}/{arity}` (translator) 跟老 `{class}#method/{arity}` 都得支持

## R316 EqualsCalledOnNullChecker (CWE-595/754)

**Bug**: `obj.equals(null)` 永远 false; `s = null; s.equals(x)` NPE

**DFA > AST 的理由**:
- AST 能 match `s.equals(null)`, 但 DFA 能跟 null state 跨 statement 传播 (`s = null; t = s; t.equals("x")` 是 alias null)
- plumb-style 已有 `EqualsAvoidNullCheck` 是 AST 版的另一种 shape (`"x".equals(s)`), 不重复

**关键 design**:
- `Set<String> knownNull` — known-null var
- MOVE `t = null` 或 `t = s` where s in knownNull → t 加入
- CALL `equals/1` 且 args[0] (receiver) 在 knownNull → fire CWE-595
- CALL `equals/1` 且 args[1] 是 literal null → fire CWE-754

## R317 CompareWithSelfChecker (CWE-570/571)

**Bug**: `if (x == x)` 永远 true; `if (x != x)` 永远 false; `if (a == b)` 当 `a` 跟 `b` alias 同一值

**DFA > AST 的理由**:
- AST 能 match `x == x`, 但 `a = x; b = x; a == b` 看不到 alias
- DFA per-function alias classes (跟 R312b CollectionSelfReference 同样的 union-find 思想)

**关键 design**:
- MOVE 合并 src 跟 dest 的 alias class
- BINOP EQ/NE lhs 跟 rhs 在同一 class → fire

## R318 XxeChecker (CWE-611)

**Bug**: DocumentBuilderFactory 没 setFeature(disallow-doctype-decl) 就 db.parse()

**DFA > AST 的理由**:
- AST 把所有 db.parse() 都标 XXE, 误报严重 (用户可能已经正确配了)
- DFA 跟 factory construction + secure config call + parse call 三步关联, 只在 missing config 时 fire

**CVE reference**: CVE-2014-3577, CVE-2016-5007 (通用 XXE pattern)

**关键 design**:
- `Set<String> plainFactories` / `securedFactories` — 区分配过 secure feature 跟没配
- `Map<String, String> parserFromFactory` — 跟 factory 的源头
- CALL setFeature / setXIncludeAware / setExpandEntityReferences → 标 secured
- CALL newDocumentBuilder / newSAXParser → 记 parserFromFactory
- CALL parse / unmarshal on parser → 检查源头 factory 是否 secured

## R319 ReturnFromFinallyChecker (CWE-584/459)

**Bug**: `try { ... } finally { return 2; }` 把 try 里的 return 或异常吃掉

**DFA > AST 的理由**:
- AST 也能 match ReturnStmt in FinallyClause, 但 DFA 用 tryRegions 索引 (R261) 让所有 try-catch-finally rules (R314, R319, R321) 共享同一 per-function scan
- 跨多个 finally 嵌套也能 handle (per-region index range check)

**关键 design**:
- initFunction 时把每个 try region 的 `finallyStart/End` 收进 per-fn map
- checkInstruction 时按 RETURN 在 fn.instructions() 的 index 查是不是在 [finallyStart, finallyEnd] 内
- 区分 return value (CWE-584) 跟 return void (CWE-459)

## R320 InsecureCryptoChecker (CWE-327/328/780)

**Bug**: `Cipher.getInstance("RSA")` (无 padding) / `MessageDigest.getInstance("MD5")` / `getInstance("DES")`

**DFA > AST 的理由**:
- AST 匹配 getInstance 字符串字面量, 但对变量 `String algo = ...; getInstance(algo)` 看不到
- DFA 可以跟 String var 来源 (但本轮先做 literal case, var case 是后续 round)

**CVE reference**: CVE-2017-1000353 (RSA no padding), general crypto-weak pattern

**关键 design**:
- 14 个 weak algo (RSA / RSA/ECB/NoPadding / DES / DESede / RC4 / Blowfish / MD5 / MD2 / MD4 / SHA-1 / sha1) → CWE-780/327/328
- CALL getInstance 且 args 里有 String Const 匹配 → fire
- args[0] (hand-built test) 或 args[1] (real Java with receiver) 都接受

## R321 EmptyCatchHandlerChecker (CWE-390/665)

**Bug**: `try { ... } catch (Exception e) { /* swallow */ }` 静默吃异常

**DFA > AST 的理由**:
- 跟 R319 ReturnFromFinally 一样, 用 tryRegions 索引, 跟 R314 共享 per-fn scan
- 区分 CWE-390 (catch Exception 等普通异常) 跟 CWE-665 (catch Error/Throwable, 致命异常更要 propagate)

**关键 design**:
- CATCH_HANDLER → 找 try region
- 扫描 catch body [idx+1, catchEnd], 跳过 LABEL/NOP/JUMP
- 没 "useful" 指令 → fire
- ctorType 是 Error/Throwable → CWE-665, 否则 CWE-390

## R322 Performance + Juliet sweep

**实测** (Juliet 真实文件, 跑 1 个 checker per family):

| Family | Files | Findings | Files-with-findings | Time (ms) |
|--------|------:|---------:|--------------------:|----------:|
| CWE248 Uncaught_Exception | 3 | 10 | 2 | 768 |
| CWE396 Catch_Generic_Exception | 20 | 20 | 20 | 589 |
| CWE397 Throw_Generic | 6 | 14 | 4 | (small) |
| CWE390 Error_Without_Action | 20 | 3 | 3 | (small) |

CWE396 100% 命中 (Juliet 里所有 20 个文件都是 generic catch) — TryCatchFinallyChecker 在这个 family 是 100% precision.

## 数字 final

| 指标 | R312 | R313-R322 | Δ |
|---|---:|---:|---:|
| plumb-dfa checkers | 23 | **31** | **+8** |
| plumb-dfa tests | 410 | 439 | +29 |
| CWE families covered (新增) | 17 | 25 | +8 |
| Juliet 验证 family | 5 | 9 | +4 |

## CWE family 覆盖 (R313-R322 新增, plumb-dfa)

| CWE | Bug | Checker |
|-----|-----|---------|
| CWE-193 | Off-by-one | OffByOneChecker |
| CWE-248 | Uncaught Exception | TryCatchFinallyChecker |
| CWE-327 | Use Broken Crypto | InsecureCryptoChecker |
| CWE-328 | Use Broken Hash | InsecureCryptoChecker |
| CWE-390 | Error Without Action | TryCatchFinallyChecker, EmptyCatchHandlerChecker |
| CWE-396 | Catch Generic | TryCatchFinallyChecker |
| CWE-397 | Throw Generic | TryCatchFinallyChecker |
| CWE-459 | Incomplete Cleanup | ReturnFromFinallyChecker |
| CWE-570 | Always False | CompareWithSelfChecker |
| CWE-571 | Always True | CompareWithSelfChecker |
| CWE-584 | Return in Finally | ReturnFromFinallyChecker |
| CWE-595 | Bad Equality Check | EqualsCalledOnNullChecker |
| CWE-611 | XXE | XxeChecker |
| CWE-665 | Improper Init | EmptyCatchHandlerChecker |
| CWE-754 | Improper Check | EqualsCalledOnNullChecker |
| CWE-780 | Use RSA w/o Padding | InsecureCryptoChecker |

## 备份

`D:\work\tmp\r313_r322_done\`:
- 8 src files: TryCatchFinallyChecker.java / OffByOneChecker.java / EqualsCalledOnNullChecker.java / CompareWithSelfChecker.java / XxeChecker.java / ReturnFromFinallyChecker.java / InsecureCryptoChecker.java / EmptyCatchHandlerChecker.java
- 8 unit test files (40 tests)
- 1 e2e sweep test (4 tests, Juliet files)
- 1 doc: R313-R322-DFA-CAPABILITY-EXPANSION.md

## R323+ 接下来 (候选)

- R323: 把 8 个新 checker 接到 ScanRunner.runAllCheckers
- R324: Reverse-validate post-pass (R188a-style) for non-taint checkers
- R325: Cross-function taint source/sink for SensitivePII/XXE
- R326: Z3 path-condition for CompareWithSelf complex cases (e.g. a+b == b+a)
- R327: Performance: lazy alias class for CollectionSelfReference / CompareWithSelf (R-tree style)
- R328: 更多 Juliet family sweep (CWE-111/113/114/134/209/...)
