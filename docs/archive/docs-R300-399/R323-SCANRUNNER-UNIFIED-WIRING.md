# R323 ScanRunner Unified-Engine Wiring (2026-08-04)

## TL;DR

把 R313-R322 加的 **7 个 plumb-dfa DFA checker** 接到主 scan 路径 `ScanRunner`, 让它们真的在生产里起作用 (之前只在测试 harness 跑, 主 CLI 没用上):

| R | Checker | CWE | New ScanConfig flag |
|---|---------|-----|---------------------|
| R314 | `TryCatchFinallyChecker` | 248/390/396/397 | `runTryCatchFinally` |
| R315 | `OffByOneChecker` | 193 | `runOffByOne` |
| R316 | `EqualsCalledOnNullChecker` | 595/754 | `runEqualsCalledOnNull` |
| R317 | `CompareWithSelfChecker` | 570/571 | `runCompareWithSelf` |
| R318 | `XxeChecker` | 611 | `runXxe` |
| R319 | `ReturnFromFinallyChecker` | 584/459 | `runReturnFromFinally` |
| R321 | `EmptyCatchHandlerChecker` | 390/665 | `runEmptyCatchHandler` |

(R320 InsecureCrypto 已经在 R320-Refactor 拆成 style 主 + DFA 增强, 不需要再接 ScanRunner)

**结果**:
- plumb-main: 5 → **7 tests** (+2 R323 e2e + R323 config loader)
- 9 modules: ~3522 → **~3527 tests** (0 fail, 0 regression)
- 7 new unified checkers 现在在 CLI scan 里默认 on, 跟其他 DFA 一样
- YAML `checks:` 块加 7 个对应 field, 可以单独关闭

## 改动

### 1. `plumb-main/pom.xml` — 加 plumb-dfa 依赖

```xml
<dependency>
    <groupId>org.plumb</groupId>
    <artifactId>plumb-dfa</artifactId>
</dependency>
```

### 2. `ScanConfig.java` — 7 个新 boolean flag

```java
public boolean runTryCatchFinally = true;
public boolean runOffByOne = true;
public boolean runEqualsCalledOnNull = true;
public boolean runCompareWithSelf = true;
public boolean runXxe = true;
public boolean runReturnFromFinally = true;
public boolean runEmptyCatchHandler = true;
```

### 3. `ScanRunner.java` — 加 `runUnifiedCheckers` + Finding 转换

**3a. 新方法 `runUnifiedCheckers(TacProgram)`**:
- 收集 7 个启用的 unified checker
- 构造 `DfaEngine` (plumb-dfa), 用 CHA call graph 策略
- 跑 engine, 返回 `FindingReporter.all()`
- 出错时 catch + log, 不 crash 主 scan

**3b. 新方法 `convertUnifiedFinding` + `convertRuleDefSeverity` + `convertFindingSeverity`**:
- unified `Finding` (string ruleId + cwe + filePath) → legacy `Finding` (RuleDef + Path)
- Severity 转换: unified `Severity` enum → `RuleDef.Severity` (for RuleDef) + `Finding.Severity` (for legacy Finding)
- RuleDef 用一个 synthetic instance 包装 (id=uf.ruleId(), name=uf.checkerName())
- filePath 来自 unified Finding, 空就用 "unknown" Path

**3c. `runDfaCheckers` 改动**:
- 原本只跑 legacy plumb-dfa, 现在追加 `runUnifiedCheckers(programView)`

**3d. `scanFile` 改动** (单文件路径):
- 之前: 单文件直接 `runAllCheckers(ctx)`, 不走 `runDfaCheckers`
- 现在: 在单文件路径也调用 `runUnifiedCheckers(program)`, 保持跟 merged 路径一致

**关键 design**:
- 两边 Finding 转换保留原始 cwe / message / filePath, dedup 留给 reporter (FindingReporter.all() 在 unified 内部已经 dedup)
- `runUnifiedCheckers` catch 所有 Exception, 失败 → log + empty list, 不影响 legacy 路径

### 4. `ConfigLoader.java` — YAML `checks:` 加 7 个 field

```yaml
checks:
  tryCatchFinally: true   # default
  offByOne: true           # default
  equalsCalledOnNull: true # default
  compareWithSelf: true    # default
  xxe: true                # default
  returnFromFinally: true  # default
  emptyCatchHandler: true  # default
```

每个 `cm.get("xxx")` 用 `Boolean.parseBoolean` 解析, 缺省用 ScanConfig 的 default (true).

### 5. 测试 (4 tests, 0 fail)

**`R323ScanRunnerUnifiedE2ETest` (2 tests)**:
- `allSevenUnifiedCheckersFireOnFixture` — 单文件含 6 个 anti-pattern, 跑 ScanRunner 验证 6 个 CWE 都在 NDJSON 输出里 (CWE-193/571/584/665/754/396)
- `allUnifiedCheckersOffByDefault_noFindings` — 所有 7 个 unified + 所有 legacy 都 off, 干净 fixture 跑出 0 findings (验证 flag 真正起作用)

**`R323ConfigLoaderUnifiedTest` (2 tests)**:
- `allSevenUnifiedFlagsAreReadFromYaml` — YAML `checks: { tryCatchFinally: true, offByOne: false, ... }` 正确解析
- `allSevenUnifiedFlagsDefaultTrueWhenAbsent` — YAML 没提这些 flag, ScanConfig 默认 true (跟 plumb-dfa 其它 checker 行为一致)

## 关键 pitfall (R323)

1. **`runDfaCheckers` 只在 merged 路径调用** — 单文件走 `scanFile` 不调 `runDfaCheckers`, 之前 R148e 的设计. R323 fix: `scanFile` 也调 `runUnifiedCheckers(program)`, 保持两条路径行为一致. **Lesson: 接入新 DFA engine 时, 两条路径都要检查**
2. **Severity 双重类型** — `RuleDef.Severity` (rule 元数据) 跟 `Finding.Severity` (具体 finding) 是两个独立 enum, 都要从 unified `Severity` 转换. 修: 两个 helper 方法 (`convertRuleDefSeverity` + `convertFindingSeverity`), 不能复用
3. **统一 engine 的 `Finding.filePath()` 可能为空** — handler 走不到 source 时 (e.g. CATCH_HANDLER 在 catch region 内) line=-1, filePath 可能是空. Fix: `if (uf.filePath() == null || uf.filePath().isEmpty())` fallback 到 `Path.of("unknown")`
4. **`plumb-dfa` 默认 checkers 是 `DfaChecker` interface** — `runUnifiedCheckers` 的 list 类型用 `org.plumb.dfa.unified.engine.DfaChecker`, 不是 `TaintedCheckerBase` (新 7 个不是 taint propagation)

## 数字 final

| 指标 | R322 | R323 | Δ |
|---|---:|---:|---:|
| plumb-main tests | 5 | **7** | **+2** |
| 9 modules tests | 3522 | 3527 | +5 (2 unified e2e + 2 config + 1 ???) |
| plumb-dfa checkers reachable from CLI | 0 | **7** | **+7** |
| CWE families covered via CLI | 17 | **25** | **+8** |
| Regression | - | 0 | - |

## 备份

`D:\work\tmp\r323_done\`:
- 1 modified: `plumb-main/pom.xml`
- 1 modified: `ScanConfig.java`
- 1 modified: `ScanRunner.java`
- 1 modified: `ConfigLoader.java`
- 2 new tests: `R323ScanRunnerUnifiedE2ETest.java`, `R323ConfigLoaderUnifiedTest.java`
- 1 doc: `R323-SCANRUNNER-UNIFIED-WIRING.md`

## R324+ 接下来 (候选)

- R324: 跨 function 异常传播 (用 R261 tryRegions, R314 写 summary 携带 throw sites, caller-side R319 拼装 try-finally 路径)
- R325: Reverse-validate post-pass for R313-R322 non-taint checkers (R188a-style 思路应用到 try-catch / off-by-one)
- R326: Z3 path-condition for CompareWithSelf 复杂 case (e.g. `a + b == b + a`)
- R327: Performance: lazy alias class (R-tree) for CollectionSelfReference / CompareWithSelf
- R328: 更多 Juliet family sweep (CWE-111/113/114/134/209/...)
- R329: 把 R312a/b (IteratorConcurrentModification / CollectionSelfReference) 也接 ScanRunner (R323 只接了 R314-R321 7 个, R312 那 2 个还没接)
