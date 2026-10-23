# 模块合并方案（plumb-parser → plumb-tac → plumb-main 关系）

本文档说明 Plumb 项目的模块划分、依赖图，以及历史上一次**"试图合并 plumb-parser 到 plumb-main"**的回滚原因。最终结论：**plumb-parser 必须作为独立模块存在**。

## 1. 当前模块结构（最终方案）

```
plumb  (root POM)
├── plumb-parser      ── JavaParser 包装，ParsingSource + JavaSourceParser
├── plumb-tac         ── TAC IR + AST→TAC 翻译器 + CFG builder + IrSerializer
├── plumb-rules       ── RuleDef / RuleId / RuleRegistry 元数据
├── plumb-dfa         ── IFDS 风格 taint + legacy DFA checkers
├── plumb-dfa ── 新一代 DFA 引擎（CallGraph + Z3 + summary）
├── plumb-style       ── 500+ AST 风格类规则
├── plumb-reporter    ── Finding 数据模型 + NDJSON / console 输出
└── plumb-main        ── CLI + YAML 配置 + 流水线编排
```

## 2. 模块依赖图

```
                ┌──────────────────┐
                │   plumb-parser   │  (无 plumb 内部依赖，只用 javaparser)
                └────────┬─────────┘
                         │
            ┌────────────┴─────────────┐
            ▼                          ▼
      ┌──────────┐              ┌──────────────┐
      │ plumb-tac │              │ plumb-style   │  ← plumb-parser + plumb-rules + plumb-reporter
      │           │              └──────┬────────┘
      └─────┬─────┘                     │
            │                           │
            │   ┌────────────┐          │
            │   │ plumb-rules │◀─────────┘
            │   └─────┬──────┘
            │         │
            ▼         ▼
       ┌──────────┐  ┌──────────────┐  ┌──────────────┐
       │ plumb-dfa │  │plumb-reporter│  │plumb-dfa-unif│
       └─────┬─────┘  └──────┬───────┘  └──────┬───────┘
             │               │                │
             └───────────────┴────────────────┘
                             │
                             ▼
                       ┌──────────┐
                       │plumb-main│  (orchestration)
                       └──────────┘
```

关键边：

- `plumb-parser` 是叶子节点，**不依赖**任何 plumb 内部模块（仅 JavaParser）
- `plumb-tac` 只依赖 `plumb-parser`（因为 `TacTranslator` 接收 `ParsedSource`）
- `plumb-style` 只依赖 `plumb-parser` + `plumb-rules` + `plumb-reporter` —— **不依赖 plumb-tac / plumb-dfa**（这是架构原则）
- `plumb-dfa` / `plumb-dfa` 依赖 `plumb-tac` + `plumb-rules` + `plumb-reporter`
- `plumb-main` 依赖全部下游模块，做编排

## 3. 历史尝试：把 `plumb-parser` 合并到 `plumb-main`

### 3.1 背景

用户提出"减少模块数量"，把 `plumb-parser` 合并到 `plumb-main`。

### 3.2 实现

```bash
# 1) 复制源文件
cp plumb-parser/src/main/java/org/plumb/parser/*.java \
   plumb-main/src/main/java/org/plumb/parser/

# 2) 删除 plumb-parser 模块
# 3) 从根 pom 中删除 <module>plumb-parser</module>
# 4) 从 plumb-main/pom.xml 中删除对 plumb-parser 的依赖
```

### 3.3 失败

合并后 `mvn clean compile` 失败：

```
plumb-tac/src/main/java/.../TacTranslator.java:[21,24]
    程序包org.plumb.parser不存在
```

原因：`plumb-tac` 也使用 `org.plumb.parser.ParsedSource`，但**不**通过 `plumb-main` 中转（那会形成循环依赖：`plumb-main → plumb-tac → plumb-main`）。

### 3.4 第一次失败后的退让：把 parser 合并到 `plumb-tac`

把 `plumb-parser` 的源文件改放到 `plumb-tac/src/main/java/org/plumb/parser/`，让 `plumb-style` 显式依赖 `plumb-tac`。

**问题**：`plumb-style` 之前**不**依赖 `plumb-tac`（架构原则是 plumb-style 只看 AST），引入 `plumb-tac` 依赖会传递地把 `sootup.core` / `sootup.java.core` / `sootup.interceptors` 拉进 `plumb-style` 的 classpath（`plumb-tac` 包含 SootUp 用于 `.jar` 分析）。

后果：

- 风格类检查器**运行期**不需要 SootUp
- 任何运行 `sast-scan` 的人都要下载 SootUp 的 native 依赖
- 单测 `plumb-style` 模块时也要拉 SootUp，**无谓的 30 MB 传递依赖**

### 3.5 第二次失败：`Finding` 在 `plumb-dfa` 里的位置

`plumb-style` 的 500+ 风格类 checker 全部需要构造 `Finding` 对象，代码里写的是 `import org.plumb.dfa.Finding`。这意味着 `plumb-style` 间接依赖 `plumb-dfa`，而 `plumb-dfa` 又依赖 `plumb-tac`。即便 `plumb-style` 不写 `plumb-tac` import，传递依赖也会把它拉进来。

### 3.6 解决

1. **恢复 `plumb-parser` 为独立模块**（回到原状）
2. **把 `Finding` 从 `plumb-dfa` 迁移到 `plumb-reporter`**，删除 `plumb-dfa → plumb-reporter` 的循环依赖
3. **删除 `plumb-reporter → plumb-dfa` 的依赖**（现在 reporter 不再用 dfa 的任何类型）
4. 批量重写 600+ 源文件 + 30+ 测试文件里的 `import org.plumb.dfa.Finding` → `import org.plumb.reporter.Finding`

## 4. 最终方案：模块边界

### 4.1 plumb-parser（独立）

```xml
<dependencies>
    <dependency>javaparser-symbol-solver-core</dependency>
    <dependency>slf4j-api</dependency>
    <dependency>logback-classic</dependency>
    <!-- 测试 -->
    <dependency>junit-jupiter (test)</dependency>
    <dependency>assertj-core (test)</dependency>
</dependencies>
```

没有 SootUp，没有 plumb-* 内部依赖。

### 4.2 plumb-tac

```xml
<dependencies>
    <dependency>plumb-parser</dependency>          <!-- 接收 ParsedSource -->
    <dependency>javaparser-symbol-solver-core</dependency>
    <dependency>sootup.core</dependency>           <!-- .jar 分析 -->
    <dependency>sootup.java.core</dependency>
    <dependency>sootup.java.bytecode.frontend</dependency>
    ...
</dependencies>
```

### 4.3 plumb-style

```xml
<dependencies>
    <dependency>plumb-parser</dependency>          <!-- AST 入口 -->
    <dependency>plumb-rules</dependency>          <!-- RuleDef -->
    <dependency>plumb-reporter</dependency>       <!-- Finding -->
    <dependency>javaparser-symbol-solver-core</dependency>
    ...
</dependencies>
```

**不**包含 `plumb-tac`、`plumb-dfa`、`plumb-dfa`。

### 4.4 plumb-reporter

```xml
<dependencies>
    <dependency>plumb-rules</dependency>          <!-- Finding.rule 字段 -->
    ...
</dependencies>
```

**不**依赖 `plumb-dfa`（已移除）。循环依赖破除。

### 4.5 plumb-dfa

```xml
<dependencies>
    <dependency>plumb-tac</dependency>
    <dependency>plumb-rules</dependency>
    <dependency>plumb-reporter</dependency>       <!-- Finding -->
    ...
</dependencies>
```

### 4.6 plumb-main

```xml
<dependencies>
    <dependency>plumb-parser</dependency>
    <dependency>plumb-tac</dependency>
    <dependency>plumb-dfa</dependency>
    <dependency>plumb-dfa</dependency>
    <dependency>plumb-rules</dependency>
    <dependency>plumb-style</dependency>
    <dependency>plumb-reporter</dependency>
    <dependency>snakeyaml</dependency>            <!-- YAML 配置 -->
    <dependency>commons-cli</dependency>          <!-- CLI 解析 -->
    ...
</dependencies>
```

## 5. 流水线数据流

```
.java
  │
  ▼  plumb-parser
ParsedSource
  │
  ├──▶ plumb-style ──────▶ List<Finding> (style)
  │
  ▼  plumb-tac
TacProgram
  │
  ▼  plumb-tac (CFG)
TacCfg
  │
  ▼  plumb-dfa / plumb-dfa
List<Finding> (dfa)
  │
  ▼  plumb-reporter
NDJSON / console
```

注意：plumb-style 和 plumb-tac **共享 ParsedSource** 作为输入，但**互相独立**：

- plumb-style 直接消费 ParsedSource
- plumb-tac 用 TacTranslator 把 ParsedSource 翻译成 TacProgram
- plumb-dfa / plumb-dfa 只看 TacProgram，不看 ParsedSource

这意味着：
- plumb-style 的检查**和 TAC 无关**，可以在没有 TAC 模块的情况下独立运行（用于纯风格检查工具）
- plumb-dfa 的检查**和 AST 无关**，可以在没有 plumb-style 的情况下独立运行（用于纯数据流分析工具）

## 6. 经验教训

1. **架构边界比模块数量更重要**。少一个模块但是把不该连的依赖连上，比多一个模块但边界清晰要差。
2. **传递依赖是真实的耦合**。即便 `plumb-style` 自身不 import `plumb-tac`，只要 `plumb-style` 依赖 `plumb-dfa`，`plumb-tac` 就进了 classpath。
3. **共享数据类型应当放在叶子节点**。`Finding` 是所有 checker 的输出，本应放在最底层的 `plumb-reporter`；`plumb-dfa` 只是消费方。
4. **不要让数据模型依赖实现**。`plumb.dfa.Finding` 这个名字暗示"DFA 的 Finding"，但实际是通用数据模型——名字本身就在鼓励错误的依赖。
5. **合并前先做依赖图审查**。把模块 A 合并到 B 之前，先看 A 的下游使用方：是不是有人**只**依赖 A 而不依赖 B？如果是，合并会强迫它们依赖 B 的全部传递依赖。

## 7. 替代方案：保留独立 + 简化构建

如果用户仍然觉得模块太多，**比合并更温和**的方案：

- **打包优化**：用 `maven-shade-plugin` 把所有 `plumb-*.jar` 打成**单一发布包**（实际已经在 plumb-main/target/ 做了）
- **接口收敛**：把 `plumb-parser` 的 public API 缩到 1-2 个类，未来可以打包成更小的 submodule
- **代码内嵌**：把 500+ style checker 拆成多个 jar，按需加载（已经是 `StyleCheckRegistry.discoverAvailable()` 实现的）

合并 `plumb-parser` 到 `plumb-main` 不可行（导致 plumb-style 传递拉进 plumb-tac）；其它合并需要重新做依赖图审查。
