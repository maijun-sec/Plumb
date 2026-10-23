# R-833 构建工具适配器 (Maven / Gradle / Bazel / Ant) (2026-10-15)

## 1. 背景

R-833 之前, plumb-main CLI 只支持 `--input <path>` / `--source-root <dir>` / `--classpath <jar|dir>`
这些**手动**指定的输入。用户如果想跑一个真实的 Maven/Gradle 项目, 必须手工读 `pom.xml` /
`build.gradle` 把 source dirs + classpath 提出来才能扫 — 这对真实工程完全不可用。

**R-833 目标**:
- 提供**统一**的 `BuildAdapter` 接口 + `BuildProject` 数据类
- 实现 Maven / Gradle / Bazel / Ant 4 个主流 JVM 构建工具的 adapter
- 集成 plumb-main CLI (`--build-system` + `--project-root`)
- 保证 `mvn test` 0 退化

## 2. 架构

```
plumb-main --build-system maven --project-root <dir>
  -> BuildSystemRegistry.detect(<dir>)      # ServiceLoader 拉所有 adapter
  -> BuildAdapter.discover(<dir>)           # 选中的 adapter
       -> BuildProject { sources, classpathJars, sourceRoots, moduleRoots, modules }
  -> cfg.inputs.addAll(bp.sourceFiles())
  -> cfg.sourceRoots.addAll(bp.sourceRoots())
  -> cfg.moduleRoots.addAll(bp.moduleRoots())
  -> cfg.classpathJars.addAll(bp.classpathJars())
  -> ScanRunner.run(cfg)                   # 既有 pipeline
```

### 2.1 核心抽象

| 类型 | 职责 |
|------|------|
| `BuildAdapter` | 接口; 4 个方法: `systemName()`, `probe(Path)`, `discover(Path)`, `sourceExtensions()` |
| `BuildProject` | 数据类; 6 个 List/Map: sourceFiles, testSourceFiles, classpathJars, sourceRoots, moduleRoots, modules |
| `BuildProject.Module` | 子模块; 5 个字段: id, sourceRoot, sources, testSources, classpathJars |
| `BuildSystemRegistry` | ServiceLoader 单例; `get()`, `adapters()`, `byName()`, `detect(Path)` |
| `BuildAdapterException` | 错误; 包含 path + cause, CLI 退出码 2 |
| `SourceFiles` | 共享工具: `walk(root, extensions)`, `splitClasspath(cp)` |

### 2.2 SPI 注册

`plumb-build/src/main/resources/META-INF/services/org.plumb.build.BuildAdapter`:
```
org.plumb.build.maven.MavenAdapter
org.plumb.build.gradle.GradleAdapter
org.plumb.build.bazel.BazelAdapter
org.plumb.build.ant.AntAdapter
```

第三方可以通过把 jar 放到 classpath 上, 加一个 `META-INF/services/org.plumb.build.BuildAdapter`
文件来注册新 adapter — **零代码改动**。

## 3. 4 个 Adapter 实现要点

### 3.1 MavenAdapter

- **依赖**: `org.apache.maven:maven-model:3.9.9` + `org.codehaus.plexus:plexus-utils:3.5.1`
  (MavenXpp3Reader + XmlPullParser)
- **probe**: 文件 `pom.xml` 存在
- **解析**:
  - 读根 pom.xml → 多模块 (`<modules>`) vs 单模块
  - 单模块: `src/main/java`, `src/test/java` 默认 layout
  - 多模块: 递归 child pom.xml, 每个 module 一个 `BuildProject.Module`
- **Classpath 解析** (核心创新):
  - 优先用 sibling module 的 `target/classes` (in-tree dep, 不需要 m2 repo)
  - 否则用 `~/.m2/repository/<gid>/<aid>/<version>/<aid>-<version>.jar`
    (默认 local repo, 可用 `-Dmaven.repo.local=...` 覆盖)
  - 跳过 test/provided/system scope, 只用 compile/runtime
- **未解析**依赖给 warning, 不报错 (允许部分依赖缺失)

### 3.2 GradleAdapter

- **依赖**: 无 (纯 regex)
- **probe**: 文件 `build.gradle` 或 `build.gradle.kts` 存在
- **解析**:
  - `settings.gradle(.kts)` 读 `include 'a', 'b:c'` (`:c` 翻译为 `/c`)
  - 每个子模块用 `src/main/java`, `src/test/java` 默认 layout
  - module id 是 leaf (e.g. `lib/helpers` -> `helpers`)
- **Classpath 解析** (regex):
  - `dependencies { implementation 'g:a:v' }` SHORT_DEP 模式
  - `implementation group: 'g', name: 'a', version: 'v'` MAP_DEP 模式
    (先归一化成 `g:a:v`, 再用 SHORT_DEP)
  - 同样用 `~/.m2/repository/...` 解析
- **不解析**: buildSrc, composite builds, version catalog
  (用户用 `--classpath` 手动补)

### 3.3 BazelAdapter

- **依赖**: 无 (纯 regex + Files.walk)
- **probe**: project root 5 层深度内存在 `BUILD` 或 `BUILD.bazel`
- **解析**:
  - 扫所有 `BUILD` 文件, 每个 file = 1 package
  - 用 regex 切 `java_library(`, `java_binary(`, `java_test(` 块 (括号 nested-aware)
  - 提取 `name`, `srcs` 属性
  - `srcs = glob([".../*.java"])` 走 `Files.walk` 展开
  - `srcs = ["Foo.java", "Bar.java"]` 走 literal list
  - module key = `<package>:<target>` (e.g. `:core`)
- **不解析**: deps, classpath (用户用 `bazel cquery` 跑后用 `--classpath` 喂入)

### 3.4 AntAdapter

- **依赖**: JDK 内置 `javax.xml.parsers` (零依赖)
- **probe**: 文件 `build.xml` 存在
- **解析** (JDK DOM):
  - 读所有 `<property name="X" value="Y">` 进 map
  - 遍历所有 `<target>` 找 `<javac srcdir="...">`
  - 解析 `${prop}` 占位符
  - 嵌套 `<classpath>` 里 `<pathelement path="..."/>` 跟 `<pathelement location="..."/>`,
    跟 OS `path.separator` split, **相对路径 resolve 到 projectRoot**
- **XXE 安全**: `dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`
  + external entities 关闭
- **不解析**: macrodef, import, custom tasks

## 4. CLI 集成

新选项 (`plumb-main/src/main/java/org/plumb/main/SastMain.java`):
- `--build-system <maven|gradle|bazel|ant>` — 显式选 adapter
- `--project-root <dir>` — adapter 解析的根

`--project-root` 单独给, 走 `BuildSystemRegistry.detect()` (按 ServiceLoader 顺序探)。
任何显式 `--input` / `--source-root` / `--classpath` 仍生效 (跟 adapter 输出合并)。

CLI 集成代码 (`SastMain.applyBuildSystem(cfg, cli)`) 大约 35 行 — 0 main pipeline 改动。

## 5. 测试

`plumb-build/src/test/java/...`:
- `BuildProjectTest` — 4 tests, 数据类基本语义
- `BuildSystemRegistryTest` — 3 tests, ServiceLoader 工作
- `MavenAdapterTest` — 9 tests, 单/多模块 + classpath + sibling short-circuit + 错误
- `GradleAdapterTest` — 7 tests, single/multi + map-style dep + kts
- `BazelAdapterTest` — 4 tests, glob + literal list + dedup
- `AntAdapterTest` — 5 tests, properties + pathelement + literal srcdir
- `AdapterE2ETest` — 7 tests, 每个 adapter smoke test + auto-detect

**总计: 39 tests, 0 fail, 0 err, 0 skip**

R-833 在 plumb-main / 其他模块 0 退化 (full reactor mvn test 全 0 退化)。

## 6. E2E 验证

真实 plumb 项目 self-scan:
```bash
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar scan \
     --build-system maven --project-root D:/work/workspace/idea/engine/Plumb/plumb-style \
     -f ndjson -o tmp/r833_e2e_plumb_style.ndjson
```

stderr 输出:
```
Build adapter 'maven' discovered: 602 sources, 0 classpath jars, 0 modules.
```

输出 73,039 findings — adapter 正确识别 plumb-style 的 602 个 source 文件。

## 7. 关键设计决策

1. **零侵入集成** — adapter 只通过 `BuildProject` 数据类跟 main pipeline 交互,
   ScanRunner / ScanConfig / JavaSourceParser 0 改动。Lesson 1276 (R-815
   per-method 聚合型 checker 不用 Pattern D 同样原则) 应用到跨模块边界。
2. **ServiceLoader SPI** — 不用 Spring/OSGi/DI 容器, 走 JDK 标准 SPI。
   新增 adapter = 加一个 `META-INF/services` 文件 + 一个 class, 不需要
   改 plumb-main / registry 代码。
3. **Parser-light** — 不依赖 Maven Embedder / Gradle Tooling API /
   Bazel Build API (避免 ~200MB 依赖 + running daemon + JDK 17/21
   compatibility 麻烦), 用 regex + 文件 walk 覆盖 80% 用例。
4. **默认保守** — adapter 找不到依赖/解析错误 → warning, 不 fail;
   user 永远可以用 `--classpath` 兜底。
5. **Classpath 委派 m2 repo** — 跟 Gradle/Bazel 的 default 行为一致
   (Gradle 用 `mavenLocal()` 默认, Bazel 通过 `rules_jvm_external` 用 m2 cache)。
6. **multi-module source dedup** — Bazel/Gradle 可能多个 target 引用同一 .java,
   adapter 在 project 级别 dedupe, 但 per-module 仍报全部 sources。

## 8. 关键 Lessons

**Lesson 1290 (R-833 P0)**: Maven `maven-model:3.9.9` 配 `plexus-utils:3.5.1`
是 MavenXpp3Reader 读 pom.xml 的最低依赖组合; 不需要 maven-core / maven-resolver
(那些带 daemon + embedder 副作用)。

**Lesson 1291 (R-833 P0)**: 测试时**强制用 hermetic empty local repo** (`tmp/empty-repo`),
不能用 `~/.m2/repository` — 开发者环境不确定, 让测试 flaky。Lesson 1265
(编码问题) 的精神: 测试 = 确定性。

**Lesson 1292 (R-833 P1)**: Bazel `glob(["**/*.java"])` 里的 `**/*.java` 在 Java
源码里**不写** `*/` — 否则 javac 误解析为 block comment 结束符。改用文档 HTML
entity `&#42;&#42;/&#42;.java` 或用单独的常量拼接 (`"**" + sep + "*.java"`)。

**Lesson 1293 (R-833 P1)**: Ant `<pathelement path="lib/jar"/>` 是 **relative to
projectRoot** (跟 javac 自身行为一致), 不能直接 `Path.of("lib/jar")` — 必须
`projectRoot.resolve(seg)`. `location` 同理。

**Lesson 1294 (R-833 P1)**: Gradle `include 'a:b:c'` 里的 `:` 是 project path
分隔符 (类似文件系统 path), 不是 Java 字符。`Path.of("a:b")` 抛 InvalidPathException —
**必须**先 `replace(':', '/')` 翻译成相对路径。

**Lesson 1295 (R-833 P1)**: Ant XML 解析必须 XXE hardening: 关 `disallow-doctype-decl`
+ 关 `external-general-entities` + `external-parameter-entities`, 否则 hostile
`build.xml` 可读本地文件或触发 SSRF。Lesson 1267 (Remove-Item safety) 同精神:
不可信输入零信任。

**Lesson 1296 (R-833 P2)**: CLI 集成用 `applyBuildSystem(cfg, cli)` 独立静态方法,
不在 main body 嵌代码。`ScanConfig` 0 字段加, `ScanRunner` 0 字段加 — 维持
Lesson 1277 (lambda 闭包不 capture mutable primitives) 跟 R-815 per-method
聚合型 checker 同样的低耦合原则。

## 9. 备份

```
tmp/_backup_r832_2026-10-15/
  R832-JULIET-COVERAGE-2026-10-15.md   摸底报告
  r832p5_per_cwe.csv                   per-CWE post-fix baseline
  r832p5_per_rule.csv                  per-rule post-fix baseline
  r832_pre_fix_per_cwe.csv             pre-fix baseline (跟 P5 diff)
  r832_pre_fix_per_rule.csv
  r832_p4_check.py                     0 老 id check + Top 20 FP driver
  r832_p5_codemod.py                   RuleRegistry / DFA inline id codemod
  r832_p5_fix_tests.py                 14 个 test 文件老 id literal codemod
  RuleRegistry.java                    plumb-rules + plumb-style 备份
  plumb_rule_to_cwe.json               278 rules with CWE 映射
  plumb_cwe_to_rules.json              133 CWE keys
```

新增文件 (R-833):
```
plumb-build/
  pom.xml
  src/main/java/org/plumb/build/
    BuildAdapter.java
    BuildAdapterException.java
    BuildProject.java
    BuildSystemRegistry.java
    SourceFiles.java
    ant/AntAdapter.java
    bazel/BazelAdapter.java
    gradle/GradleAdapter.java
    maven/MavenAdapter.java
  src/main/resources/META-INF/services/
    org.plumb.build.BuildAdapter      4 entries
  src/test/java/org/plumb/build/
    AdapterE2ETest.java
    BuildProjectTest.java
    BuildSystemRegistryTest.java
    ant/AntAdapterTest.java
    bazel/BazelAdapterTest.java
    gradle/GradleAdapterTest.java
    maven/MavenAdapterTest.java
```

修改:
- `pom.xml` (根) — 加 plumb-build module + maven-model dep
- `plumb-main/pom.xml` — 加 plumb-build 依赖
- `plumb-main/src/main/java/org/plumb/main/SastMain.java` — 加 2 个 CLI flag + `applyBuildSystem()`

## 10. Test totals progression (correction)

| Round | Tests | Fail | Err | Skip | 备注 |
|-------|-------|------|-----|------|------|
| R-831 | 1471 | 0 | 0 | 19 | +2 R831IdNamingLintTest |
| R-832 P5 | 1471 | 0 | 0 | 19 | pre-fix jar 残留老 id, post-fix rebuild 0 |
| R-833 P0 | 1480 | 0 | 0 | 19 | +9 MavenAdapterTest |
| R-833 P1 | 1510 | 0 | 0 | 19 | +11 (Bazel 4 + Gradle 7 + Ant 5 + Registry 3) |
| R-833 P2 | 1517 | 0 | 0 | 19 | +7 AdapterE2ETest |

## 11. 已知遗留 (跨 round)

- GradleAdapter 不解析 buildSrc / composite builds / version catalog —
  user 用 `--classpath` 兜底
- BazelAdapter 不解析 `deps` attribute / classpath — user 用
  `bazel cquery --output=files` 后 `--classpath` 喂入
- MavenAdapter 不解析 `<dependencyManagement>` (用 `import` 范围 / BOM) —
  那些走 warning 路径
- AntAdapter 不解析 `<import>` / `<macrodef>` / custom tasks — 同 Maven
- 4 个 adapter 都不解析 `provided` / `optional` scope 依赖进 classpath
  (provided 通常来自运行环境, 不需要 type resolution)

## 12. R-834+ 候选

- R-834 (高优): 28 个 0 TP pure FP rule disable-by-default — Juliet -3M FP
- R-835: ast-performance-duplicated-string-literal threshold tuning
- R-836: 7 个 0 TP CWE 各加至少 1 个 checker
- R-837: 21 个 fallback checker 加 DEFAULT_RULE (R-831 已知遗留)
- R-838: 26 个 `dfa-` prefix checker review 是否真 DFA-style
- R-839: Build adapter plugin — 给 Maven / Gradle 提供一等公民
  `org.plumb:plumb-maven-plugin` / `id "org.plumb" version "0.1.0"`, 在
  用户的 build 脚本里直接调用, 免去 adapter 自动探测。Lesson 1286
  (确定性逻辑走确定性路径) 适用 — `mvn plumb:scan` 是 deterministic
  RPC, 比 `plumb scan --build-system` 用户友好。
- R-840: 增量扫描 (基于 Bazel build graph / Maven changed-modules /
  Gradle `--configure-on-demand`)
- R-841: Bazel 解析 deps 跟 classpath (集成 `bazel cquery`)