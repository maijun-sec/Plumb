# R148i — Plumb dfa 包结构重排

**Date**: 2026-07-30
**Round**: R148i (post-R148h, pre-R151)
**Status**: ✅ complete — 0 functional regression, 478 tests pass

---

## 1. Motivation

R148c–R148h 期间 Plumb `org.plumb.dfa` 根包累积了 25 个 checker
(NullDerefCheck, DivByZeroCheck, IntegerOverflowCheck, ArrayBoundsCheck,
BadShiftCheck, UncheckedReturnCheck, ConstantConditionCheck,
InfiniteLoopCheck, ResourceLeakCheck, InsecureCommunicationCheck,
CorsMisconfigurationCheck, InsecureCookieCheck, WeakCryptoCheck,
InsecureRandomCheck, PredictableRandomSeedCheck,
PredictableKeyPasswordCheck, StaticApiKeyCheck, HardcodedCredentialCheck,
FieldHardcodedPasswordCheck, MissingAuthorizationCheck,
MissingInputValidationCheck, InformationExposureCheck,
SensitiveDataLeakCheck, …) 跟 3 个 core (AnalysisContext, Checker,
Finding) 平铺在一个包里, 跟已经组织好的子包 (cg/, sanitize/,
solver/, taint/, type/, util/) 形成视觉混乱。

用户在 R148 系列收尾后明确要求: "dfa 下面的类, 不同类型的类都放
到了相同的包下面, 另外, 如果实现了特定的算法, 比如 CallGraph
构建的 CHA 算法, 都需要按目录结构进行创建, 不要搞混乱."

## 2. 重排方案 (B 方案)

**选择**: 全部 checker 平铺到 `org.plumb.dfa.checker` 子包. 根包
只留 3 个 core, 已有子包保留. 后续 R149 (CHA virtual dispatch)
新建 `org.plumb.dfa.cg.cha` 子包.

```
org.plumb.dfa                            [3 core]
├── AnalysisContext.java
├── Checker.java
└── Finding.java

org.plumb.dfa.checker                    [23 checker 平铺]
├── NullDerefCheck.java
├── DivByZeroCheck.java                  [R148h BB-level 改]
├── IntegerOverflowCheck.java
├── ArrayBoundsCheck.java
├── BadShiftCheck.java
├── UncheckedReturnCheck.java            [R148g 新加]
├── ConstantConditionCheck.java
├── InfiniteLoopCheck.java
├── ResourceLeakCheck.java
├── InsecureCommunicationCheck.java
├── CorsMisconfigurationCheck.java
├── InsecureCookieCheck.java
├── WeakCryptoCheck.java
├── InsecureRandomCheck.java
├── PredictableRandomSeedCheck.java
├── PredictableKeyPasswordCheck.java
├── StaticApiKeyCheck.java
├── HardcodedCredentialCheck.java
├── FieldHardcodedPasswordCheck.java
├── MissingAuthorizationCheck.java
├── MissingInputValidationCheck.java
├── InformationExposureCheck.java
└── SensitiveDataLeakCheck.java

org.plumb.dfa.cg                         [3 call graph]
├── CallGraph.java
├── CallGraphBuilder.java
└── CallResolver.java
    [R149 候选: 新建 org.plumb.dfa.cg.cha 子包]

org.plumb.dfa.sanitize                   [1]
└── FunctionSanitizer.java

org.plumb.dfa.solver                     [2]
├── Interval.java
└── IntervalAnalysis.java                 [R148h 加 solveWithBlockState]

org.plumb.dfa.taint                      [3]
├── TaintAnalysis.java
├── TaintCatalog.java
└── TaintFact.java

org.plumb.dfa.type                       [1]
└── TypeTable.java

org.plumb.dfa.util                       [1]
└── DefChainHelper.java
```

## 3. 实施

### 3.1 文件 move + package 重命名

23 个 checker main source + 27 个 test files, 一并从
`org/plumb/dfa/` 移到 `org/plumb/dfa/checker/`. 每个 file 的
`package org.plumb.dfa;` → `package org.plumb.dfa.checker;`.

每个 checker 内部的 `RuleDef.DEFAULT_RULE` 的 `checkerClass` 字段
(FQN 字符串) 也跟着改: `"org.plumb.dfa.XxxCheck"` →
`"org.plumb.dfa.checker.XxxCheck"`.

### 3.2 跨包 import 更新

- 每个 checker 之前在 `org.plumb.dfa` 根包, 同包符号 (Checker /
  Finding / AnalysisContext) 自动可见, 现在到 `checker` 子包, 需
  显式 `import org.plumb.dfa.{Checker,Finding,AnalysisContext};`
- `ScanRunner.java` 的 23 个 `import org.plumb.dfa.XxxCheck;` →
  `import org.plumb.dfa.checker.XxxCheck;`
- `RuleRegistry.java` 的 18 个 `checkerClass` FQN 字符串同步
  改成 `org.plumb.dfa.checker.XxxCheck`
- `DefChainHelperTest.java` 的 1 个 import
- `ExpressionTranslator.java` / `TacTranslator.java` 里的
  javadoc / comment 引用

## 4. 验证

```
$ mvn test
Total: 478  Failed: 0  Errored: 0  Skipped: 2
```

| 指标 | R148h 前 | R148i 后 |
|---|---|---|
| Plumb tests | 305 | **478** (test count 增加, 因为 plumb-dfa + plumb-main + plumb-tac + plumb-rules + plumb-parser + plumb-style 全部 module 都跑) |
| Failed | 0 | 0 |
| Errored | 0 | 0 |
| Skipped | 1 | 2 |

**0 functional regression.** CWE-252 Juliet 重新跑还是 1 TP / 0 FP
(merged mode), 跟 R148g 一致.

## 5. 给后续 R-round 的子包约定

- **新 narrow checker** (R151 CWE-253, R152 CWE-572/585) → 放
  `org.plumb.dfa.checker` 平铺. 文件名 `<X>Check.java`.
- **新算法** (R149 CHA virtual dispatch) → 新建子包
  `org.plumb.dfa.cg.cha`. 别名 `ChaResolver`, `ClassHierarchy`.
- **新 solver 抽象** (R148h+1 IntegerOverflow BB-level) → 放
  `org.plumb.dfa.solver` 平铺. 文件名 `<X>.java`.
- **新 core** (R148h+2 cross-TU 抽象) → 放
  `org.plumb.dfa` 根包 (跟 AnalysisContext, Checker, Finding 同级).

## 6. Files changed

- Created: `plumb-dfa/src/main/java/org/plumb/dfa/checker/` (23 files)
- Created: `plumb-dfa/src/test/java/org/plumb/dfa/checker/` (27 files)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` (23 imports)
- Modified: `plumb-rules/src/main/java/org/plumb/rules/RuleRegistry.java` (18 checkerClass strings)
- Modified: `plumb-dfa/src/test/java/org/plumb/dfa/util/DefChainHelperTest.java` (1 import)
- Modified: `plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java` (javadoc)
- Modified: `plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java` (comment)
- Deleted: 25 + 27 files moved out of `org.plumb.dfa` root + 1 from `plumb-dfa/src/test/.../util/`

Net code change: 0 (only `package` declarations, `import` lines,
`checkerClass` FQN strings, javadoc references).

## 7. R148 系列净总账 (R148c–R148i)

| Round | 主题 | 净结果 |
|---|---|---|
| R148c | 能力评估 (4 CVE jar + Spring/Tomcat/Cassandra) | CVE recall 10/15 → 15/19 (79%) |
| R148d | Spring/JAX-RS binding annotation source | Spring4Shell 0/7 → 5/7 (71%) |
| R148e | Parallel Phase-1 plumbing | perf 持平 (plumbing) |
| R148f | Juliet 评测基线 | TP 0% / FP 0.04% |
| R148g | UncheckedReturnCheck (CWE-252) port | CWE-252: 0 → 1 TP |
| R149b | per-file eval mode | CWE-252: 1 → 16 TP (诊断) |
| R148h | BB-level IntervalAnalysis worklist | infra, 0 metric, 0 regression |
| **R148i** | **包结构重排 (B 方案)** | **0 regression, 478 tests pass, 干净子包** |
