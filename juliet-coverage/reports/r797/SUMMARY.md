# R797: Module-aware Symbol/Type Solver + 第三方 jar 列表支持

**Date:** 2026-10-12
**Status:** DONE, validated
**Scope:** R797 only. R792/R793/R795/R796 follow later.

## Goal

让 plumb 在分析用户源文件时能解析"用户源文件用到的类定义在哪"——
不再局限于 JDK types。具体三条：
1. **项目源码根**（`--source-root`）: IDE-style checkout root，plumb 把它当一个 module
2. **模块根**（`--module-root`）: 单个 module 的 source root，R797 阶段跟 source-root 等价
3. **第三方 jar**（`--classpath`）: 第三方依赖，仅供 symbol resolver 解析，**绝不进 TAC pipeline**

## 设计原则

### Cardinal rule: 第三方 jar 永不进 TAC

`--classpath` 列表**只**被 `JavaSourceParser` 的 `TypeSolver` 消费。它**绝不**进入 `BytecodeAnalyzer` 输入列表，**绝不**进入 `translateAndStyleOne` 的文件列表。这跟用户原话"不希望分析 第三方 jar 的内容"严格对齐。

### 三类输入的精确角色

| 来源 | symbol solver | TAC/DFA | BytecodeAnalyzer |
|---|---|---|---|
| `-i xxx.java` | ✅ | ✅ | ❌ |
| `-i xxx.jar` (用户 jar) | ✅ | ❌ | ✅ |
| `--classpath foo.jar` (第三方) | ✅ **仅类型解析** | ❌ **绝不翻译** | ❌ |
| `--source-root` / `--module-root` | ✅ **仅类型解析** | ❌ **不进 TAC** | ❌ |

### JavaModule 数据模型

新建 `org.plumb.parser.JavaModule` record:
- `name` (e.g. `"src0"`, `"mod0"`)
- `sourceRoot` (Path)
- `sourceFiles` (eager walk 出的 .java 列表)
- `classpath` (List<Path>, R797 阶段所有 module 共享)
- `moduleDeps` (R797 留空，per-module classpath + ModuleResolver 是 R797+ future work)

`JavaModule.singleRoot(name, root, classpath)` 静态工厂：walk `root` 出所有 .java，跳过 hidden directory（`.git`/`.idea`/`.gradle`）。

### JavaSourceParser 加 module-aware 构造器

```java
public JavaSourceParser(ParserConfiguration.LanguageLevel level,
                        List<JavaModule> modules)
```

内部 `buildTypeSolverFor(modules)` 拼装 `CombinedTypeSolver`:
1. `ReflectionTypeSolver()` 永远在最前（保证 JDK 类型能解析）
2. 每个 module 的 `sourceRoot` → `JavaParserTypeSolver`
3. 每个 module 的 `classpath` 元素 → `JarTypeSolver`（.jar 跟 class-folder 都接受）
4. `null` module entry 静默跳过（防止 CLI 错配搞死整个 scan）

旧构造器 `new JavaSourceParser()` 保留：默认 JDK-only，行为不变。

## 实现路径 (6 个文件改动 + 1 个新建)

| 文件 | 改动 |
|---|---|
| `plumb-parser/.../JavaModule.java` | **新建** record + singleRoot factory + walkJavaFiles |
| `plumb-parser/.../JavaSourceParser.java` | +1 构造器 (level + List<JavaModule>) + `buildTypeSolverFor` |
| `plumb-parser/src/test/.../JavaSourceParserTest.java` | +3 test (sibling resolve, empty module list, null-tolerant) |
| `plumb-main/.../ScanConfig.java` | +3 List<Path> 字段 (sourceRoots / moduleRoots / classpathJars) |
| `plumb-main/.../ConfigLoader.java` | +3 yaml loadPaths (snake_case + camelCase alias) |
| `plumb-main/.../SastMain.java` | +3 option + 3 parses |
| `plumb-main/.../ScanRunner.java` | +buildModules() + buildParser() helpers, 4 处 `new JavaSourceParser()` 替换 |

## 验证

### plumb-parser unit test

`mvn test -pl plumb-parser -am` — **6/6 通过**:
- 3 R791 之前的 (`parsesSimpleClass`, `capturesProblemsOnSyntaxError`, `parsesDefaultPackageClass`)
- 3 R797 新增:
  - `moduleAwareSourceRootResolvesSibling`: 同 root 下两个源文件，Bar 引用 Foo，symbol resolver 通过 `JavaParserTypeSolver` 解析
  - `emptyModuleListKeepsJdkResolution`: 空 module list 时 `ArrayList` 还能解析（确认 JDK solver 永远在前）
  - `nullModuleEntryIsTolerated`: 列表里塞一个 null module entry，parse 仍 OK

### plumb-style regression

`mvn test -pl plumb-style -am -Dtest=R787*Test,R790*Test` — **84/84 通过** (R791 baseline 全部保留)

### 22-CWE Juliet eval (whitelist, 不带新参数)

跟 R791 baseline **bit-identical**:

| CWE | R791 (findings) | R797 (findings) | diff |
|---|---|---|---|
| CWE129 | 36633 | 36633 | identical |
| CWE190 | 52512 | 52512 | identical |
| CWE191 | 39622 | 39622 | identical |
| CWE325 | 110 | 110 | identical |
| CWE329 | 175 | 175 | identical |
| CWE369 | 27692 | 27692 | identical |
| CWE398 | 406 | 406 | identical |
| CWE491 | 16 | 16 | identical |
| CWE523 | 44 | 44 | identical |
| CWE526 | 78 | 78 | identical |
| CWE572 | 93 | 93 | identical |
| CWE579 | 14 | 14 | identical |
| CWE580 | 21 | 21 | identical |
| CWE585 | 18 | 18 | identical |
| CWE598 | 44 | 44 | identical |
| CWE600 | 12 | 12 | identical |
| CWE609 | 30 | 30 | identical |
| CWE613 | 44 | 44 | identical |
| CWE615 | 44 | 44 | identical |
| CWE690 | 1739 | 1739 | identical |
| CWE759 | 44 | 44 | identical |
| CWE835 | 22 | 22 | identical |

**R797 不带新参数 = R791 baseline. 0 regression. 0 finding drift. prec 0.4378 unchanged.**

### 22-CWE Juliet eval with `--classpath`

带 4 个 Juliet lib (servlet-api.jar, javamail, commons-lang, commons-codec) 跑全套
22 CWE 是 R797+ work (R797 阶段只确认了**不崩 + 不退化**):

- CWE600 + 4 个 classpath 跑出来 312KB ndjson，no exception
- 行为符合"classpath 只进 TypeSolver"的 cardinal rule
- Symbol solver 解析率提升的量化指标待 R797+ 跟 Juliet 多模块样本一起测

## 不在 R797 范围 (R797+ future work 清单)

| 项 | 描述 | 触发条件 |
|---|---|---|
| ModuleResolver | 从 `--source-root` 自动扫 Maven/Gradle/Bazel 找模块, 输出 modules.json | 用户报告"我有 Maven 多模块项目" |
| per-module classpath | `--module-classpath <name>:<jar>`; classpath 下沉到 module 层 | 跟 ModuleResolver 一起做 |
| 跨文件 CallGraph | 跨 module / 跨文件的 method call resolve 到 callee body | R792 主任务 |
| `--bytecode-classpath` | 让 BytecodeAnalyzer 路径消费 classpath jar, 解析 callee | 用户报告"我要分析 foo.jar 同时知道它的依赖" |
| 真正 data-flow end-to-end | plumb 跑出全 data-flow analysis, 不是浅 AST | R792+ |

## 关键文件 (R797 final)

- `plumb-parser/src/main/java/org/plumb/parser/JavaModule.java` (新建)
- `plumb-parser/src/main/java/org/plumb/parser/JavaSourceParser.java` (+1 ctor)
- `plumb-parser/src/test/java/org/plumb/parser/JavaSourceParserTest.java` (+3 tests)
- `plumb-main/src/main/java/org/plumb/main/ScanConfig.java` (+3 fields)
- `plumb-main/src/main/java/org/plumb/main/ConfigLoader.java` (+3 yaml)
- `plumb-main/src/main/java/org/plumb/main/SastMain.java` (+3 options)
- `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` (+2 helpers, 4 处替换)
- `juliet-coverage/reports/r797/PLAN.md` (14KB 设计文档)
- `juliet-coverage/reports/r797/SUMMARY.md` (本文件)

## Lessons (R797-specific)

**Lesson 1198 (architecture)**: When wiring `JavaParserTypeSolver`
+ `JarTypeSolver` into a `CombinedTypeSolver`, the
`ReflectionTypeSolver()` MUST be added first. Otherwise JDK
`String` / `Object` / `Throwable` resolution fails and every
`n.calculateResolvedType()` throws — the symptom is "0 findings
emitted from style checks" with no obvious stack trace because
the symbol solver swallows the failure into a
`SymbolResolutionException` that bubbles up as
"unsolved symbol". Adding JDK first keeps the symbol-solver
short-circuits fast on common types.

**Lesson 1199 (operational)**: The `CombinedTypeSolver.add(TypeSolver)`
order matters. If a class is found in BOTH `JavaParserTypeSolver`
(project source) AND `JarTypeSolver` (e.g. user shadowed a JDK
class), the FIRST registered wins. Putting `ReflectionTypeSolver`
first means JDK types are the *highest priority*, which can mask
user source with the same FQN — a desirable property for security
analysis (we want to see the actual deployed class, not the user's
re-declared shadow).

**Lesson 1200 (operational)**: `JarTypeSolver` accepts a directory
root as well as a `.jar` path. JavaParser doesn't care; the
underlying implementation walks the directory for `.class`
files and indexes them by FQCN. This makes the
`--classpath` argument accept both "third-party JAR" and
"compiled-class-folder" (e.g. `build/classes/java/main` in a
Gradle project). One CLI option, two use cases.

**Lesson 1201 (operational)**: `JarTypeSolver(Path)` throws
`IOException` (not `RuntimeException`) on bad jars. Wrap the
constructor call in a try/catch that logs + skips, otherwise a
single corrupted classpath entry takes down the entire scan.
The same defensive try/catch is needed for `JavaParserTypeSolver`
even though that one is a `RuntimeException` — the parser library
will log "unable to read X" but we still want graceful degradation.
