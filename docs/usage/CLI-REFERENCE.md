# CLI 速查：项目路径族 / build-system / YAML v2

本文档是 `CLI.md` 的 §10-§12 拆分版本。完整 CLI 文档见 `CLI.md`；典型场景与默认配置对照见 `CLI-EXAMPLES.md`。

## 10. 项目路径族（`--source-root` / `--module-root` / `--classpath`）


R797（Oct 2026）引入三个**专门给 JavaParser TypeSolver 喂料**的 flag。它们和 `--input` 的根本区别是：

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

**为什么必须分开**：如果第三方程 jar 也走 TAC 流水线，10k+ 文件的项目 IR 缓存会爆炸、内存峰值翻倍、扫描时间 ×3+。R797 把"符号解析"和"扫描"严格解耦：

> **Cardinal rule（cardinal）**: `--classpath` 路径**永不**翻译成 TAC IR。
> 它存在的唯一目的是让 `--input` 的用户源码能解析外部类型引用。

**多 module 项目**：

```bash
bin/sast-scan \
    --input        m1/src/main/java,m2/src/main/java \
    --module-root  m1,m2 \
    --classpath    $(find ~/.m2/repository -name '*.jar' | tr '\n' ',')
```

`--source-root` 和 `--module-root` 在 R797 **等价**，只是 UX 上分开——多模块项目可以让用户按模块列出。

## 11. 构建系统自动发现（`--build-system` / `--project-root`）

R833 把上面"手动喂 `--source-root` / `--classpath`"的繁琐步骤自动化。给定一个项目根，BuildAdapter 会去探：

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

**YAML 等价**：

```yaml
buildSystem:
  name: maven
  projectRoot: /opt/my-spring-app
```

未实现功能（planned）：`buildSystem.compileClasspath: true`（只在编译期 classpath 跑，默认 true）、`buildSystem.excludeTestSources: true`（剔除 `src/test/java`，默认 false）。

## 12. YAML 格式速查（v2 + checks.sub-dict）

R836 / R837 把 YAML 风格统一到一份完整 baseline。**两种检查器写法都接受**：

### 12.1 简单 bool（仅开关）

```yaml
checks:
  taint: true
  style: true
  divByZero: true
  # ... 一共 42 项
```

`Configs.bool` 字段全部默认 `true`（除少数 off：`incorrectReturn` / `threadRun` / `catchGeneric`）。详细字段清单见 §3。

### 12.2 sub-dict 形式（同时配 aiDedup 或显式 enabled）

```yaml
checks:
  divByZero:           {enabled: true, aiDedup: true}
  incorrectReturn:     {enabled: false, aiDedup: true}
  catchGeneric:        {enabled: false, aiDedup: false}
  # ...
```

`aiDedup` 是 R-? Step 7 引入：当 `--ai-dedup` 启用时，**只有** `aiDedup: true` 的检查器走 LLM 复审。给纯 style 类（FP 几乎确定）开 `aiDedup: false` 能省 token。

两种写法**可以混用**：

```yaml
checks:
  taint: true                      # bool = enabled: true, aiDedup: 默认 true
  style: true
  catchGeneric: {enabled: false}   # sub-dict 形式覆盖默认
```

### 12.3 R-836 emit-id 风格与 yaml 兼容性

R-836 引入新 emit-id 命名规范（`ast-style-<bare>-style`），同时保留兼容老风格（`plumb.style.<bare>`）。

**ScanRunner.isRuleIdAllowed 已修复**（R837 P2）：yaml entry 写哪种风格都能匹配 emit-id：

```yaml
# 这两种 yaml 写法都对 emit-id `ast-style-random-iv-cbc-style` 生效：
rules:
  enabled:
    - ast-style-random-iv-cbc-style   # R-836 风格
    - plumb.style.random-iv-cbc       # R791 旧风格（兼容保留）
    - random-iv-cbc                  # bare slug（也会被剥 prefix + suffix 后匹配）
```

底层逻辑（`ScanRunner.java` line 1874-1896 + 2459-2461）：对每个 yaml entry，先试 `endsWith(.<bare>)`，再剥 `ast-style-` prefix 和 `-style` suffix 跟 bare slug 比较。三种命中任一即放行。

### 12.4 `rules.parameters`（planned）

R-参数化路线图。当前 `plumb-main` 不解析 `rules.parameters.*` 段——改参数要改源码 `new RuleParameter(...)` 的 default 重建。**未来**：

```yaml
rules:
  parameters:
    pl-0249-method-too-long:
      maxLines: 120                  # 覆盖源码里 80 的默认值
    pl-0233-avoid-print-stack-trace:
      allowInTests: true
```

实现路径：在 `ConfigLoader.fromMapImpl` 加一段处理 `rules.parameters` 段，把覆盖值塞进运行时 `RuleDef.parameters` 的 `value`，checker 调 `param.value()` 时优先用 YAML。

