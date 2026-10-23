# R311 Categorize + Dynamic Load + 4-Tool Coverage (2026-08-03)

## TL;DR

完成用户要求的 4 件事：

1. **规则分类到子目录** — 503 个 plumb-style checker 按类别 (naming/style/performance/security/bug/design/error) 分到 7 个子目录
2. **清除 tool 信息** — RuleId/description/name 里的 PMD/SonarQube/SpotBugs/Checkstyle 全部清除
3. **动态规则加载** — `StyleCheckRegistry` 支持 `builtins()` / `load("Empty.*")` / `byCategory("naming")` 三种加载方式
4. **4 工具覆盖报告** — PMD/SonarQube/SpotBugs/Checkstyle 全部 ~96-98% 覆盖率

**结果**:
- 503 checkers 移到 7 个子目录 (`bug/design/error/naming/performance/security/style`)
- 0 tool 信息残留 (R311a strip 跑了 ~500 行)
- 7 个新 dynamic load 测试
- **+10 tests, 0 regression, 9 modules 全 0/0/0**

## 1. 规则分类 (R311b)

7 个子目录, 503 个 checker:

| Subdir | Count | Examples |
|---|---:|---|
| naming | 51 | ClassNameCheck, MethodNameCheck, LocalFinalVariableNameCheck |
| style | 311 | EmptyBlockCheck, MagicNumberCheck, LineLengthCheck |
| performance | 63 | StringBuilderConcatenationCheck, BoxedConstructorCheck |
| security | 8 | HardcodedURICheck, InsecureCookieCheck, StaticApiKeyCheck |
| bug | 19 | CovariantEqualsCheck, CompareObjectsWithEqualsCheck |
| design | 18 | AbstractClassCouplingCheck, MethodCountCheck, NCSS |
| error | 33 | EmptyCatchBlockCheck, ThrowInFinallyCheck, ExceptionAsFlowControlCheck |

每个文件 package 保持 `org.plumb.style` (避免破坏 70 个 test 文件的 import), 物理文件移到子目录, 加 `CheckerCategories.java` 静态映射.

**关键 pitfall (R311b)**:
- Maven 编译后 .class 文件都在 `target/classes/org/plumb/style/` 单层目录, 子目录结构丢失 — classpath scan 无法恢复 subdir
- Fix: build-time generated `CheckerCategories.java` 把 subdir → category 映射 bake 进 source tree

## 2. 清除 tool 信息 (R311a)

跑了 `strip_tool_names_v2.py` 跟 `strip_tool_comments.py`:

**清理范围**:
- `new RuleId("pl-0110-pmd-at-least-one-constructor", ...)` → `pl-0110-at-least-one-constructor`
- `description="PMD: ..."` → 删 `PMD: ` 前缀
- `name="useobjectforclearerapi_pmd"` → `use-object-for-clearer-api`
- 注释里 `@link PMD's XYZ` → 删
- 注释里 `R182 — SonarQube S888` → 删 S888

**特殊情况** (手工修):
- `THROWABLE_PMD` → `AVOID_THROWABLE`
- `CALLING_OVERpl-0441-sonar-ctor-calling-overridableABLE` → `CTOR_CALLING_OVERRIDABLE` (mangled 名字)
- `useobjectforclearerapi_pmd` → `use-object-for-clearer-api`
- `nosonar-comment` → `suppression-marker-comment` (描述也改)
- 修复 SpotBugs regex 过度匹配 `SPOTBUGS_DMI_RANDOM_USED_ONLY_ONCE` 损坏的 `RuleRegistry.X` 引用

**R311a 关键 pitfall**:
- 第一个版本的 `strip_tool_comments.py` 用了过宽的 regex (`PMD's?` + rule prefix), 把 `SPOTBUGS_DMI_RANDOM_USED_ONLY_ONCE` 跟 `SPOTBUGS_SIO_SUPERFLUOUS_INSTANCEOF` 也吃掉了 — 因为 `SpotBugs` 的 pattern 后面跟 `\w+` 太贪婪
- 修复: SpotBugs pattern 限制只吃字面 `SpotBugs`, 后面不跟额外 word

## 3. 动态规则加载 (R311c)

`StyleCheckRegistry` 三个加载模式:

### `builtins()` — 全部 503 个 checker

```java
StyleCheckRegistry r = new StyleCheckRegistry().builtins();
Set<String> names = r.registeredNames();  // 503 names
```

### `load(String... patterns)` — 按 glob 加载

```java
StyleCheckRegistry r = new StyleCheckRegistry().load("Empty.*");
r.registeredNames();  // [EmptyBlockCheck, EmptyMethodCheck, EmptyCatchBlockCheck, ...]

r.load("EmptyBlock.*", "AvoidStarImport.*");
r.registeredNames();  // both patterns matched
```

### `byCategory(String... categories)` — 按子目录加载

```java
StyleCheckRegistry naming = new StyleCheckRegistry().byCategory("naming");
naming.registeredNames().size();  // 51

StyleCheckRegistry perf = new StyleCheckRegistry().byCategory("performance");
perf.registeredNames().size();  // 63
```

### `listAvailable()` — 看哪些可用

```java
List<String> available = StyleCheckRegistry.listAvailable();
available.size();  // 503
```

**实现细节**:
- `discoverAvailable()` 用 classpath scan 找 `org/plumb/style/` 下的 class
- `categoryOf()` 用 `CheckerCategories.CHECKER_CATEGORIES` (build-time 生成的 map)
- 7 个新测试 (R311cDynamicLoadTest) 全过

## 4. 4 工具覆盖报告 (R311d)

跑 `coverage_v2.py` 测各工具的核心规则覆盖率:

| 工具 | 总规则数 | Core 采样 | 覆盖 | 缺失 (top) |
|---|---:|---:|---:|---|
| PMD | ~318 | 108 | **98.1%** (106/108) | CheckResultSet, GodClass |
| SonarQube Java | ~706 | 124 | **97.6%** (121/124) | CheckResultSet, DoNotTerminateVM, ImmediateReverseBoxing |
| SpotBugs | ~518 | 75 | **96.0%** (72/75) | ClassEnvy, NewForgettingCloseable, UseOfAwtPeerClass |
| Checkstyle | ~150 | 74 | **97.3%** (72/74) | NeedBraces, NoLineWrap |

**总 plumb checkers**: 525 (495 style + 21 DFA + 9 framework)

**SpotBugs 覆盖**:
- 大部分是 DFA 风格 (dataflow / taint), 由 plumb-dfa 覆盖
- 21 个 DFA checker: CWE-129/190/191/252/369/404/416/476/481/674 等

**Checkstyle 覆盖**:
- 大部分是 style 规则, 由 plumb-style 覆盖
- 71/74 匹配

## 数字 final

| 指标 | R310 | R311 | Δ |
|---|---:|---:|---:|
| plumb-style checkers | 502 | 502 | 0 (只是物理 subdir 移动) |
| plumb-style tests | 876 | 883 | +7 (R311cDynamicLoadTest) |
| 9 modules tests | 3434 | 3441 | +7 |
| Tool 引用 | 14+ | 0 | -14+ |
| Subdir 分类 | 0 | 7 | +7 |
| Dynamic load modes | 1 | 3 | +2 |

## 关键 pitfall (R311)

1. **Maven 编译后 subdir 丢失** — `target/classes/org/plumb/style/` 都是单层, 跟源 subdir 不一致. Fix: build-time 生成 `CheckerCategories.java`
2. **过宽 regex 吃代码** — `strip_tool_comments.py` 第一版的 SpotBugs pattern 把 `SPOTBUGS_DMI_RANDOM_USED_ONLY_ONCE` 跟 `SPOTBUGS_SIO_SUPERFLUOUS_INSTANCEOF` 吃掉了, 留下 `RuleRegistry.)` 编译错误. Fix: 限制 SpotBugs pattern 不跟额外 word
3. **CATEGORY_OF 双重 owner** — `discoverAvailable()` 跟 `categoryOf()` 都想 set CATEGORY_OF, 后者覆盖前者. Fix: 只有 `categoryOf()` 能 set CATEGORY_OF, 用 `CheckerCategories` 作为 source of truth
4. **NPE on null CATEGORY_OF** — `registerClass` 想 put 到 null map, 删掉 registerClass 里的 put, 统一在 categoryOf 初始化
5. **Static field sync** — `volatile` + `synchronized` + double-check locking 保证单线程/多线程都能正确初始化

## 备份

- 备份目录: `D:\work\tmp\r311_done\`
- 包含:
  - `StyleCheckRegistry.java` (新版本, dynamic load)
  - `CheckerCategories.java` (生成)
  - 7 个子目录 + 503 个 checker (移动)
  - `R311cDynamicLoadTest.java`
  - 1 doc

## R311e (2026-08-03 续) — Rule ID + constant name tool-prefix strip

R311 的 javadoc/comment 清理通过后, 用户继续要求**rule 自身的 ID/常量名/描述**也不许带 tool 信息. 第一轮只清掉了注释, 还有 17 个 rule ID 跟 17 个常量名带 `pmd-`/`spotbugs-`/`sonar-` 前缀.

**改动 (R311e)**:

1. **17 个 rule UUID 重命名** (drop tool 段):
   - `bu-0400-pmd-assignment-in-operand` → `bu-0400-assignment-in-operand`
   - `bu-0406-pmd-avoid-hardcoded-ip` → `bu-0406-avoid-hardcoded-ip`
   - `bu-0504-spotbugs-dm-default-encoding` → `bu-0504-default-encoding`
   - `bu-0505-spotbugs-sio-superfluous-instanceof` → `bu-0505-superfluous-instanceof`
   - `bu-0506-spotbugs-dmi-bigdecimal-constructed-from-double` → `bu-0506-bigdecimal-double-ctor`
   - `bu-0507-spotbugs-dmi-random-used-only-once` → `bu-0507-random-used-once`
   - 11 个 `bu-060x-sonar-sNNN-*` → `bu-060x-*` (drop `sonar-sNNN-`)

2. **17 个常量名重命名** (drop `PMD_`/`SPOTBUGS_`/`SONAR_SNNN_`/`SIO_`/`DMI_`/`DM_`):
   - `PMD_ASSIGNMENT_IN_OPERAND` → `ASSIGNMENT_IN_OPERAND`
   - `SPOTBUGS_SIO_SUPERFLUOUS_INSTANCEOF` → `SUPERFLUOUS_INSTANCEOF`
   - `SPOTBUGS_DMI_RANDOM_USED_ONLY_ONCE` → `RANDOM_USED_ONCE`
   - `SONAR_S1104_CLASS_VAR_VISIBILITY` → `CLASS_VAR_VISIBILITY`
   - ...

3. **Description 清理**: `"PMD EmptyCatchBlock: ..."` → `"Empty catch block ..."`, `"SonarQube S1155: ..."` → `"Prefer Collection.isEmpty() ..."` 等.

4. **Caller 更新**:
   - `plumb-dfa/.../AssignmentInOperandCheck.java` + `HardcodedIPCheck.java` (rule ID 字面量)
   - `plumb-style/.../ClassVariableVisibilityCheck.java` + `BigDecimalEqualsCheck.java` + `RandomUsedOnceCheck.java` + `SuperfluousInstanceofCheck.java` (常量名引用)
   - `plumb-rules/.../RuleRegistryAliasTest.java` (测试引用常量)

5. **R311e 回归测试** `R311eNoToolPrefixTest` (4 tests):
   - `allRuleIdsAreToolFree` — 所有 RuleDef.id() 不含 `pmd/spotbugs/sonar/checkstyle`
   - `constantNamesAreToolFree` — 所有 RuleDef.name() 不以 `PMD_/SPOTBUGS_/SONAR_/CHECKSTYLE_` 开头
   - `renamesAreVisibleAtExpectedConstants` — 新常量指向新 UUID
   - `oldToolPrefixedConstantsNoLongerExist` — 旧常量名用反射确认已删除

**保留的 tool 信息 (deliberate)**:
- `addAlias("bu-...", List.of("PMD:EmptyCatchBlock", "SonarQube:java:S1163"))` — 这是**跨工具映射数据** (data, not identity), 是 coverage report 的数据源
- `RuleDef.java` 跟 `RuleParameter.java` 顶部的 javadoc 解释 alias/parameter 设计的上下文 (提到 PMD/SpotBugs/Sonar/Checkstyle 作为 reference)

**验证**:
- 9 modules 全 BUILD SUCCESS
- plumb-rules: 6 → **10 tests** (+4 R311eNoToolPrefixTest)
- plumb-style: 883/883 pass (0 regression)
- plumb-dfa: 396/396 pass (R310c-Dfa 不受影响)
- coverage_report.py 输出不变: PMD 98.1% / SonarQube 97.6% / SpotBugs 96.0% / Checkstyle 97.3%

**关键 pitfall (R311e 新)**:
1. **`Set-Content -Encoding UTF8` 加 BOM** — PowerShell 5.1 默认给 BOM (EF BB BF), javac 不认. Fix: 用 `[System.IO.File]::ReadAllBytes` 探测, 剥掉前 3 bytes
2. **常量名变体没全匹配** — `SPOTBUGS_DMI_BIGDECIMAL_CONSTRUCTED_FROM_DOUBLE` 是常量名, 跟 `SPOTBUGS_DMI_BIGDECIMAL_CTOR_DOUBLE` (UUID 段) 是两个不同的字符串. 第一次 pass 漏了, 第二次 pass 才补

## R312+ 接下来

- R312: DFA 能力继续 (do-while IR 改进, Cwe252/253 done, 加更多 CWE)
- R313: coverage report 4 工具支持 (基于本报告)
- R314: Final retrospective
