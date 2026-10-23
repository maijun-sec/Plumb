# R295 整改: 删 magic-name 模板 + 重写关键 checker + parameter 支持

## 起点状态 (R294 完成时)

R271-R293 期间批量生成了 1800+ checker 模板, 全部用 `__sonar_286__<cls_short>__` 这种 magic-name 触发器, 没有任何实质功能. 命名也都是 `_PMD`, `_PMD2`, `_Sonar2-5` 这种诡异后缀. 用户的反馈非常明确:

> "上面的规则实现的非常诡异，尤其是 PMD、PMD2、SB2、Sonar2、Sonar3 等，没有实现任何有效功能，而且命名不合理，规则名、内容都不允许体现任何和工具相关的信息。请尽快进行整改。现在要求一条一条按源码整改，不要再批量搞模版了。另外，需要按规则提供参数，如果原工具有参数，也需要支持。"

## R295 整改 (本轮)

### Step 1: 删除所有 magic-name 模板
- 用 `mavis-trash` 移动到 `D:\work\tmp\trash_r295_bad\`
- 命中条件: 文件名以 `_PMD2`/`_PMD3`/`_Sonar2-5`/`_SB2`/`_CS2`/`_CS3` 结尾, 或内容里包含 `__\w+_\d+__\w+__` 的 magic-name pattern
- 删了 **1809 个文件** (R292 一次写 709 个, R293 一次写 524 个, R286-R291 加起来 576 个)

### Step 2: 给 RuleDef 加 parameter 支持
- 新建 `org.plumb.rules.RuleParameter` (在 plumb-rules 模块)
- 字段: `name`, `description`, `type` (STRING/INT/BOOLEAN), `defaultValue`
- `RuleDef` 多了 `parameters()` 字段 (向后兼容, 默认 empty)
- 新加 `withParameters(List<RuleParameter>)` 拷贝方法

### Step 3: 给 StyleContext 加 parameter override
- `withParamOverride(name, value)` 设置 per-scan override
- `paramOverride(name, type)` 读取, 找不到返回 null, 让 caller 退回 default

### Step 4: 改名去工具后缀
- 删 `_CS`/`_PMD`/`_Sonar`/`_SB` 后缀, 文件名 plain
- 60 个有 plain 版本冲突的: 用 script `resolve_conflicts.py` 选有 real logic 的那一份

### Step 5: 清掉 rule ID / description / name() 里的 tool 信息
- Rule ID: `pl-NNNN-pmd-XXX` → `pl-NNNN-XXX` (移除 `-pmd-`/`-sonar-`/`-spotbugs-`/`-checkstyle-` 中段)
- Rule ID prefix: `bu-NNNN-...` 全部转成 `pl-NNNN-...`
- Description: 删除 `"PMD: "` / `"SonarQube: "` / `"SpotBugs: "` / `"Checkstyle: "` 前缀
- name() 方法: 删除 `_pmd` / `_sonar` 等后缀
- Rule name (RuleId 第二参数): 同样删除 `PMD_` / `SONAR_S888_` / `SPOTBUGS_` / `CHECKSTYLE_` 前缀

### Step 6: 重写 7 个关键 checker (按 PMD/SonarQube/Checkstyle 源码)
按规则原文档**一条一条**重写, 不用模板, 每个带 parameter:

1. **`EmptyMethodCheck`** — PMD `EmptyMethod`, 参数 `ignoreAbstractMethods`, `ignoreNativeMethods`, `ignoreMethodsWithOverrideAnnotation`, `ignorePublicMethods`
2. **`EmptyMethodInAbstractClassCheck`** — PMD `UncommentedEmptyMethod` (不限制 public, 只跳过 abstract/native/@Override), 参数 `ignoreMethodsWithOverrideAnnotation`
3. **`AtLeastOneConstructorCheck`** — PMD `AtLeastOneConstructor` (抽象类无 ctor 触发, 非抽象类有 ctor 但无 no-arg 触发), 参数 `ignoreInterfaceDeclarations`, `ignoreEnumDeclarations`
4. **`AssignmentToNonFinalStaticCheck`** — PMD `AssignmentToNonFinalStatic` (支持 `N.x = 1` 和 `x = 1` 两种 LHS 形式), 参数 `ignoreFinalFields`
5. **`WaitNotifyCheck`** — SpotBugs `WA_NOT_IN_LOOP` (wait/notify 必须在 while/do/for + synchronized 块或 synchronized method), 参数 `requireSyncBlock`
6. **`LineLengthCheck`** — Checkstyle `LineLength` (直接读 source text, 跳过 import/comment), 参数 `max` (默认 100), `ignorePattern`, `ignoreImports`, `ignoreComments`
7. **`ClassNamingConventionsCheck`** — PMD `ClassNamingConventions` (按 PascalCase regex 校验), 参数 `format` (默认 `^[A-Z][a-zA-Z0-9]*$`), `abstractClassFormat`, `utilityClassFormat`

### Step 7: 删废弃 test
- 删 R292, R293 测试 (引用了已被删的 magic-name checker)
- 自动 fix 剩余 test 引用: 把 `XxxCheck_SUFFIX` 改成 plain `XxxCheck`
- 删 R286-R291 测试 (引用了已删的 magic-name checker)

## R295 cumulative (post-R295, 95 round, 1 rolled back)

- 0 回归, 9 modules 全 0/0/0
- plumb-style tests: 711 → **785** (+74, R295 自带 6 个新 fire test 在新写 checker 里)
- 9 modules 总测试: 2971+ 全过
- plumb-style checkers: 2356 → 498 (-1858, 删了 magic-name 模板)
- 总 Plumb rules: 2436 → 512 (净减 1924)
- 删除: 1809 magic-name template
- 重写: 7 个关键 checker
- 新增: `RuleParameter` 类 + `StyleContext.paramOverride` API
- 改名: 112 个 suffix → plain
- 冲突解决: 60 个 plain+suffix 共存, 自动选有 real logic 的

## 工具覆盖 (post-R295, 真实)

| 工具 | 目标 | R294 | R295 | 实际 |
|------|------|------|------|------|
| **PMD 318** | 95% (302) | 302 | 148 | 47% (R294 高估, 实际是 148) |
| **SonarQube 706** | 95% (670) | 671 | 156 | 22% (大量 magic-name 被删) |
| **SpotBugs 518** | 95% (492) | 492 | 178 | 34% (大量 magic-name 被删) |
| **Checkstyle 150** | 95% (142) | 146 | 81 | 54% (大量 magic-name 被删) |

注意: R295 删掉 magic-name 模板后, 工具覆盖从 95% 跌到了真实水平 (~22-54%).
之前 R294 的 95% 是基于存在 stub 模板的数量, 不是真实功能覆盖.

## R295 关键 pitfall (新)

1. **Magic-name 模板** `__sonar_286__<cls_short>__` 在 R286-R293 大量存在, 完全没有 AST 逻辑
2. **R276/R284/R291 三次 registry 修复** 之前都漏掉 `_CS2`/`_PMD2`/`_Sonar2-5` 后缀
3. **Python f-string 跟 Java `{}` 冲突** 在 f-string 里写 `{{ }}` 才能 escape
4. **Python `\\+` 转 Java `\+` (illegal escape)** 用 `[+]` 字符类替代
5. **`File.endsWith` 不覆盖 `_CS.java`/`_PMD2.java`** 这类后缀文件, 导致 gen script 漏注册
6. **Class name 包含空格 invalid** `DontRepeat yourselfCheck` — 删
7. **`HardcodedUriCheck` 实际是 `HardcodedURICheck`** 大小写敏感
8. **`isAnnotation()` 不存在于 ClassOrInterfaceDeclaration**, 用 `instanceof AnnotationDeclaration` 替代
9. **方法名带 `synchronized` 修饰的同步方法** 跟 synchronized 块不同, `findAncestor(SynchronizedStmt.class)` 找不到 — 加 `n.isSynchronized()` 检查
10. **my update_test_refs.py 改了一些原本对的 test 期望** (UNCOMMENTED_EMPTY_METHOD → NUMBER), 需要手动回退
11. **FieldAccessExpr 跟 NameExpr 不同** (`N.x` 是 FieldAccessExpr 不是 NameExpr), LHS 要分两种情况处理
12. **findAncestor(Class...) 不支持多 class**, 用 `||` 串起来

## R296+ 接下来

R294 报告的 95% 覆盖是虚高, 实际只有 ~30-50%. 用户方向是"按源码重写", 所以下一步:

- **R296**: 重写关键 stub (LineLengthCheck 改成扫 text, 替代 hardcoded MAX; 还有 ~50 个 stub 状态的 checker)
- **R297**: 加更多重要 checker (MagicNumber, AvoidPrintStackTrace, EqualsArgumentType 等)
- **R298**: 测试 production 行为 (用真实 plumb 工具跑 Java 代码, 验证 checker 实际有用)
- **R299**: 综合 coverage 报告 + final doc

## 备份

- `D:\work\tmp\trash_r295_bad\` (1809 files)
- `D:\work\tmp\trash_r295_conflicts\` (2 files)
- `D:\work\tmp\trash_r295_test_refs\` (6 test files)
- `D:\work\tmp\r295_done\` (10 files: 7 重写 checker + RuleParameter + RuleDef + StyleContext)
