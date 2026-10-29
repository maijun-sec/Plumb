# 规则操作：Style 595 / 启用禁用 / 改参数

[English](../../rules/RULE-STYLE-OP.md) | [中文](../README.md)

本文档讲 Style 规则的操作：有多少条、5 层启用/禁用机制、以及怎么改规则参数。规则主清单（数量 / 严重度 / 类型 / emit-id / taint / DFA）见 `RULES.md`；规则开发（AI prompt / CWE 标签 / 外部工具 ID / 加新规则）见 `RULE-AUTHORING.md`。

## 1. Style 规则（596 个 checker 类）

Style 规则**不写进 `plumb-rules/RuleRegistry`**，而是通过 `AstCheckerRegistry` 在启动时 classpath 扫描。9 大子目录的实际分布见 `docs/rules/RULES.md` 的分类统计一节。

| 目录 | 数量 | 主题 |
| --- | ---: | --- |
| `plumb-style/style/` | **353** | 编码风格（注释、Javadoc、缩进、imports、控制流、声明） |
| `plumb-style/performance/` | **63** | 性能（字符串、boxed、BigDecimal、Random） |
| `plumb-style/naming/` | **51** | 命名约定（类型、方法、字段、参数、局部变量、包） |
| `plumb-style/security/` | **42** | 安全相关（deserialization、URL sanitization） |
| `plumb-style/error/` | **35** | 错误处理（catch、finally、throw、synchronized） |
| `plumb-style/bug/` | **20** | 明确 bug 模式（compareTo、equals、null 检查） |
| `plumb-style/design/` | **18** | 设计度量（圈复杂度、耦合、深度） |
| `plumb-style/quality/` | **10** | 质量（regex confusion、ORM 模式） |
| `plumb-style/`（根目录） | **2** | `AstChecker`（接口）、`ObjectHijackCheck` |
| `plumb-style/concurrency/` | **2** | 并发（servlet 原子性、singleton race） |
| `plumb-style/annotations/` | 0 | 注解 IR 工具（不是 checker） |
| **合计** | **596** | — |

`CheckerCategories.CHECKER_CATEGORIES` 登记了 **593** 条（差的 2 条见
[`../tech/STYLE-RULES.md`](../tech/STYLE-RULES.md) §6）。

> 完整列表 + 每条规则的 Javadoc + PMD/SpotBugs/SonarQube/Checkstyle 等价映射见 `docs/plumb-style-rules.md`（自动生成，由 `scripts/gen_rules_doc.py`）。索引见 `docs/plumb-style-rules-index.md`（由 `scripts/gen_rules_index.py`）。

### 7.1 style 规则的 emit-id

```java
// plumb-style/style/RandomIvCheck.java
public final class RandomIvCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-random-iv-cbc", "RANDOM_IV_CBC"),
          "AES/CBC cipher uses a non-random Initialization Vector (CWE-329).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-329"),
          "org.plumb.style.RandomIvCheck",
          true,
          List.of("ast-style-random-iv-cbc-style", "random-iv-cbc")   // 历史 alias
      );
}
```

emit-id 的格式是 `ast-<category>-<bare>`（category = checker 所在子包名）。
带 `-style` 后缀的 `ast-style-random-iv-cbc-style` **只是 alias**，不再是任何规则的
emit-id —— 实测 `plumb-style/src/main/java` 下 579 个 `new RuleId("...")` 字面量里
没有一个以 `-style` 结尾。

yaml 里写下面几种风格都能匹配：

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc        # emit-id（推荐）
    - ast-style-random-iv-cbc-style  # 老 alias（仍兼容）
    - random-iv-cbc                  # bare slug
```

## 2. 启用 / 禁用规则

### 8.1 大类开关（`checks:`）

通过 `checks.<name>: true|false` 一次性启用 / 禁用一整类检查器。例如：

```yaml
checks:
  taint: false         # 关闭所有 taint 规则（`TaintRuleRegistry` 里 12 条）
  style: false         # 关闭全部 AST 风格规则
  divByZero: false     # 关闭 DIV_BY_ZERO
```

不写 key 时使用默认值（**几乎全部默认 true**，只有少数 off——见 `ScanConfig` 字段初始化：`incorrectReturn` / `threadRun` / `catchGeneric` 默认 false）。

**sub-dict 形式**（同时配 `aiDedup` 或显式 `enabled`）：

```yaml
checks:
  divByZero:           {enabled: true,  aiDedup: true}
  incorrectReturn:     {enabled: false, aiDedup: true}
  catchGeneric:        {enabled: false, aiDedup: false}
```

`aiDedup: true` 表示该规则走 `--ai-dedup` LLM 复审；`aiDedup: false` 在 AI 模式下跳过——给纯 style 类（FP 几乎确定）开 false 能省 token。

### 8.2 细粒度：`rules.enabled` / `rules.disabled`

`rules.enabled` 把列出的规则**强制置为 on**；`rules.disabled` 强制置为 off。接受**规则 UUID**（如 `bu-0201-style-empty-catch`）、**规则名**（如 `STYLE_EMPTY_CATCH`）、**emit-id**（如 `ast-style-system-out-println`）：

```yaml
rules:
  disabled:
    - STYLE_SYSTEM_OUT            # 规则名
    - ast-style-system-out-println  # style emit-id
  enabled:
    - bu-0401-weak-crypto         # 即便大类 off 也强制开
    - dfa-taint-null-dereference  # DFA taint emit-id
```

实际行为：规则注册阶段（`RuleRegistry.withBuiltinDefaults()` 之后）会重新设置 `enabled` 标志。代码路径在 `ScanRunner.buildRegistry()`：

```java
for (String enabled : config.enabledRuleNames) {
    if (enabled.endsWith("." + bare)) return true; // 兼容
    String astStyleBare = enabled;
    if (astStyleBare.startsWith("ast-style-")) {
        astStyleBare = astStyleBare.substring("ast-style-".length());
    }
    if (astStyleBare.endsWith("-style")) {
        astStyleBare = astStyleBare.substring(0, astStyleBare.length() - "-style".length());
    }
    if (astStyleBare.equals(bare)) return true; // 风格
}
```

### 8.3 命令行临时禁用

CLI 没有原生的 `--disable` flag，但可以用一个小 shell alias：

```bash
# 把所有 -D rules.disabled=ID 解析成 YAML 片段并传入
cat > /tmp/disable.yaml <<EOF
rules:
  disabled: [$1]
EOF
java -jar plumb-...-all.jar -c /tmp/disable.yaml -i src/main/java

# 或者直接用 --rule 临时只跑一条
bin/sast-scan -i src --rule TAINT_SINK_SQL_INJECTION
```

### 8.4 5 层 enabled gate 机制

 把"启用/禁用"从 1 层（`checks.*`）扩到 5 层。**修改 disable 行为时必须知道**这 5 层顺序——后一层覆盖前一层的设置：

```
┌──────────────────────────────────────────────────────────┐
│ 1. RuleDef.enabled (checker 静态 DEFAULT_RULE 字段)        │
│ - 默认 true. 22 条规则出厂即 false（opt-in）               │
│    - RuleRegistry.registerFromChecker 走 updateInPlace path 持久化 │
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ 2. RuleRegistry.disabledRuleNames (per-scan 覆盖)         │
│    - 来自 yaml rules.disabled, --rule 黑名单                 │
│    - buildRegistry() loop 重置 enabled = false if 命中     │
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ 3. RuleRegistry.withBuiltinDefaults(true) (smoke test)     │
│    - 反射调 AstCheckerRegistry.builtins() + registerFrom    │
│    - forceAllEnabledIf 把所有 def.enabled() 翻转 true      │
│    - production 永不调 true, smoke test 用                  │
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ 4. StyleContext.forceEnable(String...) (escape hatch)      │
│    - per-context bypass 列表, production 永不调            │
│    - smoke test 用 ctx.forceEnable(ruleId) 显式开           │
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ 5. ScanRunner.isRuleIdAllowed(ruleId, liveRegistry)       │
│    - prod gate: liveRegistry.byName(ruleId).enabled()      │
│    - return !defaultDisabled at end (default off)          │
│    - yaml rules.enabled / --rule 显式开 override           │
└──────────────────────────────────────────────────────────┘
```

**实操要点**：

- 想 disable 某个 rule 走 **层 2**（`yaml rules.disabled`）— 是普通用户路径
- 想 force enable 某个 rule 走 **层 5**（`yaml rules.enabled` 或 `--rule`）— 强制 override
- 改 `RuleDef` 默认值需要 rebuild + 重新发布 image（**层 1** 是 compile-time）
- 当前 `plumb-dfa/src/main/resources/checkers.yaml` 的 16 个 checker 中有 2 个
  `enabled: false`（`SingletonRaceDfa` / `ServletAtomicityDfa`），它们必须显式
  enable 才会跑

## 3. 改规则参数

每条 `RuleDef` 可以挂载 `RuleParameter`（见 `plumb-rules/src/main/java/org/plumb/rules/RuleParameter.java`）。

### 9.1 运行时参数覆盖（已支持）

**YAML 路径是 `checks.<rule-id>.params.<param-name>`**（不是 `rules.parameters.*`）。
`ConfigLoader.fromMapImpl`（`plumb-main/.../ConfigLoader.java:343-357`）把
`checks:` 下每个 rule 块的 `params:` 扁平 map 读进 `ScanConfig.ruleParams`，
值按字面量类型强制成 Integer / Boolean / String。`ScanRunner` 构造
`StyleContext` 时把它传进去（`ScanRunner.java:2061`、`:2454`），
`StyleContext` 再按 rule id 分发（`StyleContext.java:132-140`）。

```yaml
checks:
  line-length:
    enabled: true
    params:
      max: 120                 # 覆盖 LineLengthCheck 的 max 默认值 100
      ignoreComments: false
```

外层 key 必须是 checker 的 `name()`（kebab-case slug，例如 `line-length`）。
**canonical rule id（`ast-style-line-length`）和短名（`LINE_LENGTH`）都不生效**——
查找是 `checker.name() + "." + paramName` 的直接拼键，不做别名解析。
参数名必须和 `RuleParameter` 里声明的名字一致。

没命中 override 时回落到 `RuleDef.parameters()` 里声明的 default。这条回落路径按 slug
解析规则，`RuleRegistry` 保证每个 checker 的 slug 指向它自己那条规则——所以 slug 与
规则名的对应关系是注册表维护的，不依赖每个 checker 手工把 slug 写进 alias 列表。
`CheckerSlugResolutionTest`（`plumb-style/src/test/.../CheckerSlugResolutionTest.java`）
覆盖这条不变式。

`R761ParamsTest`（`plumb-main/src/test/.../R761ParamsTest.java`）覆盖这条路径，
`R822StyleContextParamOverrideTest` 覆盖 `StyleContext` 侧的参数分发。

### 9.2 改默认值（需要 rebuild）

改代码里的 `RuleParameter` 默认值：

1. 找到 `plumb-style/src/main/java/.../<Rule>Check.java` 里的 `new RuleParameter(...)` 调用。
2. 改 default 字符串。
3. `mvn install` 重新构建。

例如把 `LineLengthCheck` 的行长阈值从 100 改到 120：

```java
// plumb-style/style/LineLengthCheck.java
private static final RuleParameter[] PARAMS = {
  new RuleParameter("max", "Maximum allowed line length", RuleParameter.Type.INT, "120"),
  // ...
};
```

**不 rebuild 也能改** —— 走 §9.1 的 YAML 覆盖即可，且优先级更高。

### 9.3 `aggressiveness` 已废弃

`aggressiveness: low|medium|high` 不再被识别。
- CLI 没有 `--aggressiveness` flag（已删除）。
- YAML 里写 `aggressiveness:` 时，`ConfigLoader.fromMapImpl` 往 stderr 打一条
  `[plumb] warning: 'aggressiveness' in YAML is no longer supported; ...`，
  **但扫描照常继续**——这不是致命错误。
- 替代品：`checks.<rule>: {enabled: true|false}` 段或 `--rule <id>` 命令行临时过滤。
