# R797 PLAN: Module-aware Symbol/Type Solver + 第三方 jar 列表支持

**Date:** 2026-10-12 (rev 2, after user feedback)
**Status:** PLAN, awaiting user approval (rev 2)
**Scope:** R797 only. R792/R793/R795/R796 follow later.

## 0. 用户原话（背景，第二轮反馈）

> source root 和 module root 不同，source root 是整个项目根路径，module root 是下面的模块，但是我们在分析时，源码、classpath 等，都是基于 module 来的，所以，我们要具备的能力：
> 1、在 source root 下找模块的能力  -- 这个可以随后再补，但是这个需要输出一个文件，包括 模块名、模块下面的源码列表、模块所用到的 classpath 信息，模块用到的其他模块；
> 2、识别到模块的依赖，在 translate 阶段，可以并行，但是不同模块之间，要把信息给足，但是按理来说，classpath 应该在 模块层，不是项目层；
> 3、在分析时，真正的 CallGraph 构造完，完全按照数据流分析完成。

> 另外，我想起一个事情：如果用户就是想分析 jar 文件，我们应该也具备分析能力的，因为我们支持 jar 转成 TAC IR，也就支持了基于 TAC IR 的所有的 jar 文件分析能力，可以加个参数补充，当然，如果现在出于项目整洁，可以先不支持。

> 第一轮："不希望分析 第三方 jar 的内容" (--classpath 的 jar 不进 TAC/DFA)

## 1. 现状 (rev 1 调研结果，不变)

### 1.1 `JavaSourceParser` 现状

- 默认 `CombinedTypeSolver(new ReflectionTypeSolver())` —— 只有 JDK
- `withTypeSolver(extra)` 方法存在但**生产代码 0 调用**
- 4 处 `new JavaSourceParser()` 在 ScanRunner

### 1.2 TAC translator / BytecodeAnalyzer 现状

- TacTranslator 只翻译单文件 AST，不跨文件 resolve
- BytecodeAnalyzer(List<Path>) 已支持 multi-input (sootup JavaView)
- 字节码路径的 use case 是 `-i foo.jar` —— 已经 work, 第三方 classpath 还没接

### 1.3 CLI / ScanConfig 现状

- 无 `--source-root` / `--module-root` / `--classpath`
- ScanConfig 无相应字段

## 2. 重新设计 —— Module-aware 三层模型

### 2.1 核心数据模型: Module (新概念)

```java
// 新类型，放在 plumb-parser 包
public record JavaModule(
    String name,                  // 模块名 (e.g. "m1" 或 "org.example:m1")
    Path sourceRoot,              // 模块的源码根 (含 src/main/java)
    List<Path> sourceFiles,       // 模块下所有 .java 源文件
    List<Path> classpath,         // 模块的 classpath (第三方 jar 列表)
    List<String> moduleDeps) {    // 该模块依赖的其他 module name 列表
}
```

### 2.2 CLI 参数 (rev 2 修订)

```
--source-root <dir>
    整个项目根路径 (e.g. 包含 m1/, m2/ 的目录)。
    R797 阶段: 如果指定, plumb 把这个目录本身当作一个 module 来对待
    (因为自动扫模块的 logic "可以随后再补" —— 留给 R797+)
    
--module-root <dir>
    某个模块的根路径 (e.g. m1/)。
    R797 阶段: --source-root 与 --module-root 行为相同
    (CLI 上暴露两个名字仅 UX 差异, 跟用户原话 "source root 是整个项目根,
    module root 是下面的模块" 的语义对齐; 真正的差异留 R797+ 走 ModuleResolver)

--classpath <jar-or-dir>
    第三方依赖 jar (或 class-folder 目录)。**可重复**。
    R797 阶段: 作为**全局 classpath** 喂给所有 module 共享
    (per-module classpath 留 R797+ 配合 ModuleResolver 实现)
    
--module-classpath <name>:<jar>
    (R797+ future work) per-module classpath. R797 不暴露, 留位.
```

### 2.3 三类输入的精确角色 (rev 2 强化)

| 来源 | symbol solver | TAC/DFA | BytecodeAnalyzer |
|---|---|---|---|
| `-i xxx.java` | ✅ 必有 | ✅ 必有 | ❌ |
| `-i xxx.jar` (用户输入) | ✅ 必有 | ❌ | ✅ 必有 |
| `--classpath foo.jar` (第三方) | ✅ **仅类型解析** | ❌ **绝不翻译** | ❌ |
| `--source-root` / `--module-root` (源码) | ✅ **仅类型解析** | ❌ **不参与** TAC pipeline | ❌ |
| `--bytecode-classpath foo.jar` (R797+ defer) | TBD | TBD | TBD |

**红线**：第三方 jar (--classpath) **永远不**进 `translateAndStyleOne` / `BytecodeAnalyzer` 输入列表。**只**作为 JavaParser symbol solver 的 lookup 源。

### 2.4 数据流 (rev 2 强化)

```
user source file (xxx.java, 来自 -i 或 source-root 下的 .java)
  │
  ├─[1] JavaParser AST ─(JavaSymbolSolver + TypeSolver)─→ CompilationUnit
  │      │
  │      └─ sym resolve: 找 .class 的来源
  │          ├─ 在 -i 列表的其他源文件? → 同 module 内的其他 ParsedSource
  │          ├─ 在 --source-root / --module-root 下的其他源文件?
  │          │   → JavaParserTypeSolver
  │          ├─ 在 --classpath jar? → JarTypeSolver (索引 .class, 不解 bytecode)
  │          └─ JDK? → ReflectionTypeSolver
  │
  ├─[2] TacTranslator ──(只翻译本文件)─→ TacProgram
  │      └─ 不发跨文件 call resolve 流量到 jar (R797 范围)
  │      └─ 跨文件 CallGraph 构造 → R792+
  │
  └─[3] AST style checks
         └─ StyleContext 内的 StyleContext.resolveDecl / resolvedTypeOf
            走同一套 TypeSolver; symbol solver 解析率上升 → 准确率提升
```

### 2.5 用户原话 #3 "完全按数据流分析" 的解读

> "在分析时，真正的 CallGraph 构造完，完全按照数据流分析完成。"

含义：
- 走完整 DFA pipeline (TAC IR → 引擎 → finding), **不**退回到 "只跑 AST 不跑 DFA" 的浅分析
- R797 阶段：在 source / classpath 输入更完整后，**DFA checkers 的输出更准** (symbol 解析率上 → context 信息更准 → finding 更准)
- **不在 R797 范围**：跨文件 CallGraph 构造 (跨 module 间的 method call resolve 到 callee body) → R792+

## 3. 实现路径 (rev 2)

### 3.1 新增 `JavaModule` 数据类

`plumb-parser/.../module/JavaModule.java` (新文件, record)

```java
public record JavaModule(
    String name,
    Path sourceRoot,
    List<Path> sourceFiles,
    List<Path> classpath,
    List<String> moduleDeps
) {
    /** Build a flat single-module: just one sourceRoot, all .java under it, given classpath. */
    public static JavaModule singleRoot(String name, Path sourceRoot,
                                          List<Path> classpath) {
        List<Path> sources = ...  // walk sourceRoot for *.java
        return new JavaModule(name, sourceRoot, sources, classpath, List.of());
    }
}
```

### 3.2 `JavaSourceParser` 加 module-aware 构造器 (rev 2 新增)

```java
/** Build a parser whose TypeSolver combines JDK + each module's source + classpath. */
public JavaSourceParser(ParserConfiguration.LanguageLevel level,
                        List<JavaModule> modules) {
    this(buildConfig(level, modules));
}

private static ParserConfiguration buildConfig(LanguageLevel level, List<JavaModule> modules) {
    CombinedTypeSolver combined = new CombinedTypeSolver();
    combined.add(new ReflectionTypeSolver());  // JDK types always available
    for (JavaModule m : modules) {
        // each module's source files available for sym resolution
        combined.add(new JavaParserTypeSolver(m.sourceRoot().toFile()));
        // each module's classpath jars
        for (Path cp : m.classpath()) {
            combined.add(toJarTypeSolver(cp));
        }
    }
    return new ParserConfiguration()
        .setLanguageLevel(level)
        .setCharacterEncoding(StandardCharsets.UTF_8)
        .setAttributeComments(true)
        .setSymbolResolver(new JavaSymbolSolver(combined));
}

private static TypeSolver toJarTypeSolver(Path p) {
    if (p.toString().toLowerCase().endsWith(".jar")) {
        return new JarTypeSolver(p.toFile());
    } else {
        // class-folder: JarTypeSolver accepts a directory root
        return new JarTypeSolver(p.toFile());
    }
}
```

> 旧 `new JavaSourceParser()` 构造器**保留**——默认行为不变。

### 3.3 `ScanConfig` 新增字段 (rev 2)

```java
// R797 (Oct 2026): external source / classpath inputs for symbol resolution.
// All consumed by JavaSourceParser's TypeSolver only — never enter the
// TAC translation pipeline or the SootUp bytecode path.
public List<Path> sourceRoots = new ArrayList<>();   // --source-root
public List<Path> moduleRoots = new ArrayList<>();   // --module-root
public List<Path> classpathJars = new ArrayList<>(); // --classpath
```

注：未来 per-module classpath 进来时再加 `Map<String, List<Path>> moduleClasspath` 字段。

### 3.4 `ScanRunner` 改动 (rev 2)

```java
// R797: build JavaModules from config.sourceRoots + moduleRoots + classpathJars.
// In R797, each source-root / module-root is its own module;
// per-module classpath is deferred (R797+).
private List<JavaModule> buildModules() {
    List<JavaModule> modules = new ArrayList<>();
    int idx = 0;
    for (Path root : config.sourceRoots) {
        modules.add(JavaModule.singleRoot("src" + idx++, root, config.classpathJars));
    }
    for (Path root : config.moduleRoots) {
        modules.add(JavaModule.singleRoot("mod" + idx++, root, config.classpathJars));
    }
    return modules;
}

private JavaSourceParser buildParser() {
    return new JavaSourceParser(
        ParserConfiguration.LanguageLevel.JAVA_17,
        buildModules());
}
```

4 处 `new JavaSourceParser().parse(file)` 全部换成 `buildParser().parse(file)`。

> **Per-file 找 module**: 源文件自己知道在哪个 source-root 下 (通过 relative path 匹配), 解析时用对应 module 的 TypeSolver. 这是细化的 R797+ 优化; **R797 阶段所有 module 共享一个 CombinedTypeSolver**, 简化实现.

### 3.5 `BytecodeAnalyzer` / jar 路径

R797 阶段**不动** BytecodeAnalyzer 路径。现状：
- `-i foo.jar` → `isBytecodeArtifact` 路由进 `scanBytecode` → `new BytecodeAnalyzer(inputs).analyze()`
- `--classpath` 仍**不**进 BytecodeAnalyzer (用户原话: "不希望分析第三方 jar 的内容")

**R797+ future work (在 plan 中标 deferred)**:
- 加 `--bytecode-classpath <jar>` (可重复) option
- 加到 BytecodeAnalyzer 构造器的 multi-input list
- 让 sootup 帮你解析 callee (call graph 更准)
- 等用户明确说"我要分析用户的 jar 同时知道它的依赖"时再做

### 3.6 `SastMain` 改动 (rev 2 跟 plan rev 1 一样)

3 个新 option, 3 处 parse:

```java
opts.addOption(Option.builder().longOpt("source-root")
    .hasArg().argName("dir")
    .desc("Project source root for cross-file symbol resolution. "
        + "Not scanned for findings; only used by JavaParser's TypeSolver.")
    .build());
opts.addOption(Option.builder().longOpt("module-root")
    .hasArg().argName("dir")
    .desc("Module root for cross-file symbol resolution. "
        + "Same effect as --source-root in R797.")
    .build());
opts.addOption(Option.builder().longOpt("classpath")
    .hasArg().argName("jar|dir")
    .desc("Third-party dependency jar or classpath directory. "
        + "Used by JavaParser's TypeSolver only; never translated to TAC IR.")
    .build());
```

### 3.7 `ConfigLoader` 改动 (rev 2 跟 plan rev 1 一样)

yaml 加 `source_roots` / `module_roots` / `classpath` 段。

## 4. 验证方案

### 4.1 单元测试

`plumb-parser/.../JavaSourceParserTest.java` (新建):

1. 配一个 module, 模块下源文件引用 `java.util.ArrayList` —— assert `n.calculateResolvedType()` 返回 ArrayList
2. 配一个 module, 模块下源文件引用 module 内另一个类的全限定名 —— assert `n.resolve()` 返回那个类
3. 配 `--classpath` 指向一个含自定义类的 jar —— assert `n.resolve()` 能找到

`plumb-main/.../ConfigLoaderTest.java` (或新文件): 解析 yaml 的 source_roots/module_roots/classpath.

### 4.2 Juliet 22-CWE 验证

跟 R791 一样 22 CWE 跑一遍:

- **不 regression**: 不带新参数跑 → prec ≥ 0.4378, scan 时间差 < 5%
- **symbol resolver 解析率**: log 行 "calculateResolvedType failed" 数下降
- (可选) 给 CWE190/191/690 配 servlet-api.jar 看能否解析 servlet 类型

### 4.3 性能 / 爆炸性验证

- 把一个大 jar (e.g. servlet-api.jar ~5MB) 喂进 `--classpath` —— 验证 scan 时间涨但 22-CWE eval prec 不掉
- 关键: 即使喂多个 jar, TAC/DFA 翻译时间**不**随 jar 数线性增长 (因为只喂 type solver)

## 5. 关键文件 (改动清单)

| 文件 | 改动 |
|---|---|
| `plumb-parser/.../module/JavaModule.java` | **新建** record |
| `plumb-parser/.../JavaSourceParser.java` | +1 构造器 (level + List<JavaModule>) |
| `plumb-parser/src/test/.../JavaSourceParserTest.java` | **新建** +3 test |
| `plumb-main/.../ScanConfig.java` | +3 List<Path> 字段 |
| `plumb-main/.../ConfigLoader.java` | +3 yaml loadPaths |
| `plumb-main/.../SastMain.java` | +3 option +3 parse |
| `plumb-main/.../ScanRunner.java` | +buildModules() +buildParser() helpers, 4 处替换 |

## 6. 验收

- [ ] `mvn test -pl plumb-parser -am` 全过
- [ ] `mvn test -pl plumb-main -am` 全过
- [ ] `mvn clean install -pl plumb-main -am -Dmaven.test.skip=true` 成功
- [ ] 不带新参数跑 22 CWE: prec ≥ 0.4378, scan 时间差 < 5%
- [ ] 带合理 `--classpath` 跑 22 CWE: prec ≥ 0.4378
- [ ] R797 SUMMARY + memory append

## 7. 不在 R797 范围 (R797+ future work 清单)

| 项 | 描述 | 触发条件 |
|---|---|---|
| ModuleResolver (R797+ stage 2) | 从 `--source-root` 自动扫 Maven/Gradle 找模块, 输出 modules.json | 用户报告"我有 Maven 多模块项目" |
| per-module classpath (R797+ stage 2) | `--module-classpath <name>:<jar>`; classpath 下沉到 module | 跟 ModuleResolver 一起做 |
| 跨文件 CallGraph (R792) | 跨 module / 跨文件的 method call resolve 到 callee body | R792 主任务 |
| `--bytecode-classpath` (R797+ defer) | 让 BytecodeAnalyzer 路径消费 classpath jar, 解析 callee | 用户报告"我要分析 foo.jar 同时知道它的依赖" |
| 真正的 data-flow end-to-end (R792+) | plumb 跑出全 data-flow analysis, 不是浅 AST | R792+ 路线 |

## 8. 写完先做什么

按你 1-5 列表的次序, **R797 (第 5 个) 先做**, R792/R793/R795/R796 接着推。
