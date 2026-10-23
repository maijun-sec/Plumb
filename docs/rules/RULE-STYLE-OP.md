# 规则操作：Style 590 / 启用禁用 / 改参数

本文档是 `RULES.md` 的 §7-§9 拆分版本。规则主清单（数量 / 严重度 / 类型 / emit-id / taint / DFA）见 `RULES.md`；规则开发（AI prompt / CWE 标签 / 外部工具 ID / 加新规则）见 `RULE-AUTHORING.md`。

## 1. Style 规则（590 条）

Style 规则**不写进 `plumb-rules/RuleRegistry`**，而是通过 `StyleCheckRegistry` 在启动时 classpath 扫描。9 大子目录的实际分布（实测 2026-10-16）：

| 目录 | 数量 | 主题 |
| --- | ---: | --- |
| `plumb-style/style/` | **352** | 编码风格（注释、Javadoc、缩进、imports、控制流、声明） |
| `plumb-style/performance/` | **63** | 性能（字符串、boxed、BigDecimal、Random） |
| `plumb-style/naming/` | **51** | 命名约定（类型、方法、字段、参数、局部变量、包） |
| `plumb-style/security/` | **40** | 安全相关（deserialization、URL sanitization） |
| `plumb-style/error/` | **35** | 错误处理（catch、finally、throw、synchronized） |
| `plumb-style/bug/` | **19** | 明确 bug 模式（compareTo、equals、null 检查） |
| `plumb-style/design/` | **18** | 设计度量（圈复杂度、耦合、深度、NPath） |
| `plumb-style/quality/` | **10** | 质量（regex confusion、ORM 模式） |
| `plumb-style/concurrency/` | **2** | 并发（servlet 原子性、singleton race） |
| `plumb-style/annotations/` | 0 | 注解 IR 工具（不是 checker） |
| **合计** | **590** | — |

> 完整列表 + 每条规则的 Javadoc + PMD/SpotBugs/SonarQube/Coverity 等价映射见 `docs/plumb-style-rules.md`（自动生成，由 `scripts/gen_rules_doc.py`）。索引见 `docs/plumb-style-rules-index.md`（由 `scripts/gen_rules_index.py`）。

### 7.1 style 规则的 emit-id

```java
// plumb-style/security/RandomIvCheck.java
public class RandomIvCheck extends AstCheck {
    public Rule rule() {
        return new RuleId("ast-style-random-iv-cbc-style", ...);  // R-836 风格
    }
}
```

yaml 里写三种风格都能匹配：

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc-style   # 推荐
    - plumb.style.random-iv-cbc       # R791 旧风格（仍兼容）
    - random-iv-cbc                  # bare slug
```

## 2. 启用 / 禁用规则

### 8.1 大类开关（`checks:`）

通过 `checks.<name>: true|false` 一次性启用 / 禁用一整类检查器。例如：

```yaml
checks:
  taint: false         # 关闭所有 17 条 taint 规则
  style: false         # 关闭 590 条 AST 风格规则
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

`rules.enabled` 把列出的规则**强制置为 on**；`rules.disabled` 强制置为 off。接受**规则 UUID**（如 `bu-0201-style-empty-catch`）、**规则名**（如 `STYLE_EMPTY_CATCH`）、**emit-id**（如 `ast-style-empty-catch-style` 或 `plumb.style.empty-catch`）：

```yaml
rules:
  disabled:
    - STYLE_SYSTEM_OUT            # 规则名
    - pl-0006-system-out          # UUID
    - ast-style-system-out-style  # R-836 emit-id
  enabled:
    - bu-0401-weak-crypto         # 即便大类 off 也强制开
    - plumb.boundary.integer-overflow  # DFA unified
```

实际行为：规则注册阶段（`RuleRegistry.withBuiltinDefaults()` 之后）会重新设置 `enabled` 标志。代码路径在 `ScanRunner.buildRegistry()`：

```java
for (String enabled : config.enabledRuleNames) {
    if (enabled.endsWith("." + bare)) return true;     // R791 兼容
    String astStyleBare = enabled;
    if (astStyleBare.startsWith("ast-style-")) {
        astStyleBare = astStyleBare.substring("ast-style-".length());
    }
    if (astStyleBare.endsWith("-style")) {
        astStyleBare = astStyleBare.substring(0, astStyleBare.length() - "-style".length());
    }
    if (astStyleBare.equals(bare)) return true;        // R-836 风格
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

### 8.4 5 层 enabled gate 机制（R-834 引入）

R-834 把"启用/禁用"从 1 层（`checks.*`）扩到 5 层。**修改 disable 行为时必须知道**这 5 层顺序——后一层覆盖前一层的设置：

```
┌──────────────────────────────────────────────────────────┐
│ 1. RuleDef.enabled (checker 静态 DEFAULT_RULE 字段)        │
│    - 默认 true. R-834 codemod 改 25 个 disabled rule 为 false│
│    - Registry.fromChecker 走 updateInPlace path 持久化       │
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
- 当前 `plumb-dfa/src/main/resources/checkers.yaml` 的 16 个 checker 中有 3 个
  `enabled: false`，它们必须显式 enable 才会跑

## 3. 改规则参数

每条 `RuleDef` 可以挂载 `RuleParameter`（见 `plumb-rules/RuleParameter.java`）。

### 9.1 当前路径

**plumb-main 的 YAML 加载器尚未支持运行时参数覆盖**——所有规则使用代码里 hard-code 的 default。改法：

1. 找到 `plumb-style/src/main/java/.../<Rule>Check.java` 里的 `new RuleParameter(...)` 调用。
2. 改 default 字符串。
3. `mvn install` 重新构建。

例如把 `MethodLengthCheck` 的阈值从 80 改到 120：

```java
// plumb-style/style/MethodLengthCheck.java
public Rule rule() {
    return new Rule("pl-0249-method-too-long")
        .parameter(new RuleParameter("maxLines", "80", "Maximum lines per method"));  // ← 改这里
        // ...
}
```

### 9.2 未来 R-参数化路径（planned）

引入 `rules.parameters.<rule-id>.<param-name>: <value>` 段，逻辑写在 `ConfigLoader.fromMapImpl` 里（参考同文件的 `checks.*` 处理代码段）。届时 `RuleParameter.value()` 会优先返回 YAML 提供的值，fallback 才是 `default`。

```yaml
rules:
  parameters:
    pl-0249-method-too-long:
      maxLines: 120
    pl-0233-avoid-print-stack-trace:
      allowInTests: true
```

### 9.3 `aggressiveness` 已废弃

**REMOVED 2026-10-08 (audit C-3)**：`aggressiveness: low|medium|high` 不再被识别。
- CLI 没有 `--aggressiveness` flag（已删除）。
- YAML 里写 `aggressiveness:` 会被 `ConfigLoader.fromMapImpl` 报清晰错误。
- 替代品：`checks.<rule>: {enabled: true|false}` 段或 `--rule <id>` 命令行临时过滤。

