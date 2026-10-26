# CLI 速查：项目路径族 / build-system / YAML v2

本文档是 `CLI.md` 的 §10-§12 拆分版本。完整 CLI 文档见 `CLI.md`；典型场景与默认配置对照见 `CLI-EXAMPLES.md`。

## 10. 项目路径族（`--source-root` / `--module-root` / `--classpath`）

（Oct 2026）引入三个**专门给 JavaParser TypeSolver 喂料**的 flag。它们和 `--input` 的根本区别是：

| 维度 | `--input` | `--source-root` / `--module-root` / `--classpath` |
| --- | --- | --- |
| 喂给 `cache/programs/` | 是 | 否 |
| 进入 TAC 流水线 | 是 | 否 |
| 产生 finding | 是 | 否 |
| 喂给 JavaParser `TypeSolver` | 否（仅语法层） | 是 |
| 用途 | 扫代码 | 解析符号，让 `--input` 的代码能引用第三方类型 |

**典型用法 — Spring Boot 项目**：

```bash
# 自己的源码 + 第三方 jar / 依赖源码
bin/sast-scan \
    --input       src/main/java \
    --classpath   ~/.m2/repository/org/springframework/spring-core/6.1.0/spring-core-6.1.0.jar \
    --source-root ~/.m2/repository/org/springframework/spring-core/6.1.0/src \
    -o findings.ndjson
```

这样 Spring 注解的解析能正常进行（`@Autowired`、`@RequestMapping` 等），但 `spring-core.jar` 内部那 3000 多个 class 不会被扫成 finding。

**为什么必须分开**：如果第三方程 jar 也走 TAC 流水线，10k+ 文件的项目 IR 缓存会爆炸、内存峰值翻倍、扫描时间 ×3+。 把"符号解析"和"扫描"严格解耦：

> **Cardinal rule（cardinal）**: `--classpath` 路径**永不**翻译成 TAC IR。
> 它存在的唯一目的是让 `--input` 的用户源码能解析外部类型引用。

**多 module 项目**：

```bash
bin/sast-scan \
    --input        m1/src/main/java,m2/src/main/java \
    --module-root  m1,m2 \
    --classpath    $(find ~/.m2/repository -name '*.jar' | tr '\n' ',')
```

`--source-root` 和 `--module-root` 在 **等价**，只是 UX 上分开——多模块项目可以让用户按模块列出。

## 11. 构建系统自动发现（`--build-system` / `--project-root`）

 把上面"手动喂 `--source-root` / `--classpath`"的繁琐步骤自动化。给定一个项目根，BuildAdapter 会去探：

| Adapter | 探测依据 | 输出 |
| --- | --- | --- |
| `maven` | `pom.xml` | `sourceFiles`（解析 `<sourceDirectory>` / `<testSourceDirectory>`）、`classpathJars`（`~/.m2` 下所有依赖）、`modules`（reactor） |
| `gradle` | `build.gradle(.kts)` | 类似 Maven |
| `bazel` | `BUILD` 文件 | 解析 `java_library` / `java_binary` 规则 |
| `ant` | `build.xml` | 解析 `<javac>` task |

**自动检测**：只传 `--project-root` 时，`BuildSystemRegistry.detect(projectRoot)` 会按顺序试 `pom.xml` → `build.gradle` → `BUILD` → `build.xml`。

**显式指定**：

```bash
bin/sast-scan --project-root /opt/my-spring-app --build-system maven
```

输出到 stderr：

```
Build adapter 'maven' discovered: 1247 sources, 89 classpath jars, 3 modules.
```

Adapter 输出的 `sourceFiles` / `sourceRoots` / `moduleRoots` / `classpathJars` 全部喂给 runner，**用户传的 `--input` / `--classpath` 仍生效**——adapter 提供的是"项目标准集合"，用户可以追加/覆盖。

**注意：build-system 只能走 CLI，没有 YAML 等价键。** `ConfigLoader` 不读
`buildSystem:`，`--build-system` / `--project-root` 是 CLI-only 的（见
`SastMain.applyBuildSystem`）。想用 YAML 表达同样的效果，只能把 adapter
解析出来的路径写进 `inputs:` / `sourceRoots:` / `moduleRoots:` /
`classpath:`。

未实现功能（planned）：`buildSystem.compileClasspath: true`（只在编译期 classpath 跑，默认 true）、`buildSystem.excludeTestSources: true`（剔除 `src/test/java`，默认 false）。

## 12. YAML 格式速查（v2 + checks.sub-dict）

 / 把 YAML 风格统一到一份完整 baseline。**两种检查器写法都接受**：

### 12.1 简单 bool（仅开关）

```yaml
checks:
  taint: true
  style: true
  divByZero: true
  # ... 一共 48 个 checker family，外加 taint / style 两个总开关 = 50 个键
```

`ConfigLoader.fromMapImpl` 一共从 `checks:` map 上读 **48 个 checker family**
（每个对应一个 `ScanConfig.run*` 布尔字段），外加 `taint` / `style` 两个总开关
—— 合计 50 个键。每个 family 都同时接受 camelCase（canonical）和
snake_case（legacy alias）两种拼写，camelCase 优先。

`ScanConfig.run*` 布尔字段一共 55 个，其中 6 个**没有**对应的 YAML key，
只能改代码或走 `--rule` / `rules.enabled`：
`runOrmLoadNullCheck` / `runOrmLostUpdate` / `runOrmUnnecessaryGet` /
`runUnlockNotLocked`（默认恒为 true）、`runServletAtomicity` /
`runSingletonRace`（默认恒为 false，且两者是纯 AST checker）。

各 family 默认值全部为 `true`（除少数 off：`incorrectReturn` / `threadRun` /
`catchGeneric`）。详细字段清单见 §3。

### 12.2 sub-dict 形式（同时配 aiDedup 或显式 enabled）

```yaml
checks:
  divByZero:           {enabled: true, aiDedup: true}
  incorrectReturn:     {enabled: false, aiDedup: true}
  catchGeneric:        {enabled: false, aiDedup: false}
  # ...
```

`aiDedup` 标志决定：启用 `--ai-dedup` 时，**只有** `aiDedup: true` 的检查器走 LLM 复审。给纯 style 类（FP 几乎确定）开 `aiDedup: false` 能省 token。

两种写法**可以混用**：

```yaml
checks:
  taint: true                      # bool = enabled: true, aiDedup: 默认 true
  style: true
  catchGeneric: {enabled: false}   # sub-dict 形式覆盖默认
```

### 12.3 emit-id 风格与 yaml 兼容性

style 规则的 emit-id 格式是 `ast-<category>-<bare>`（category = checker 所在子包名）。
实测 `plumb-style/src/main/java` 下 583 个 `new RuleId("...")` 字面量里，**没有一个
以 `-style` 结尾** —— `ast-style-random-iv-cbc-style` 这类带后缀的字符串只作为
`RuleDef` 的 **alias** 保留（兼容老 YAML 白名单），不再是任何规则的 emit-id。

`ScanRunner.isRuleIdAllowed` 对 yaml entry 做多形态匹配，下列写法都能命中
emit-id `ast-style-random-iv-cbc`：

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc        # emit-id（推荐）
    - ast-style-random-iv-cbc-style  # 老 alias（兼容保留）
    - random-iv-cbc                  # bare slug（剥 prefix 后比较）
```

DFA taint 规则的 emit-id 是 `dfa-taint-<bare>`（例如 `dfa-taint-sql-injection`）。

底层逻辑（`ScanRunner.isRuleIdAllowed`，`plumb-main/.../ScanRunner.java:1773-1863`）：
对每个 yaml entry，先试 `endsWith(.<bare>)`，再剥 `ast-style-` prefix 和 `-style` suffix
跟 bare slug 比较。任一命中即放行。剥前缀的辅助方法是同文件的
`stripPlumbAndPlPrefix`（`:1869-1889`）。

### 12.4 规则参数覆盖（已支持）

运行时参数覆盖的 YAML 路径是 **`checks.<rule-id>.params.<param-name>`**：

```yaml
checks:
  line-length:
    enabled: true
    params:
      max: 120                  # 覆盖源码里 100 的默认值
      ignoreComments: false
```

`ConfigLoader.fromMapImpl`（`plumb-main/.../ConfigLoader.java:343-357`）把它读进
`ScanConfig.ruleParams`，`ScanRunner` 构造 `StyleContext` 时传入，
`StyleContext` 按 rule id 分发（`StyleContext.java:132-140`）。
`<rule-id>` 是 checker 的 `name()` 或它的 canonical rule id；参数名必须和
`RuleParameter` 里声明的名字一致。

覆盖测试见 `R761ParamsTest` 与 `R822StyleContextParamOverrideTest`。

