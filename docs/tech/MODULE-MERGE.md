# 模块结构与依赖边界

本文档说明 Plumb 项目的模块划分、依赖图，以及每个模块的边界。

一句话结论：**`plumb-parser` 必须是独立模块**。它被 `plumb-tac`、`plumb-style` 和 `plumb-dfa` 同时消费，如果它被合并进任何一个下游模块，另外两个就会被迫拉进那个模块的全部传递依赖。

## 1. 当前模块结构

根 `pom.xml` 的 `<modules>` 里一共 9 个模块：

```
plumb  (root POM)
├── plumb-parser      ── JavaParser 包装，ParsedSource + JavaSourceParser
├── plumb-tac         ── TAC IR + AST→TAC 翻译器 + CFG builder + IrSerializer
├── plumb-rules       ── RuleDef / RuleId / RuleParameter / RuleRegistry 元数据
├── plumb-style       ── AST 风格 checker（JavaParser AST visitor）
├── plumb-reporter    ── Finding 数据模型 + NDJSON / console / SARIF 输出
├── plumb-dfa         ── DFA 引擎（CallGraph + Z3 + 跨函数 summary + checkers）
├── plumb-build       ── build-system adapter（Maven / Gradle / Bazel / Ant）
├── plumb-ai          ── LLM FP 消减（ReActAgent + ToolRegistry）
└── plumb-main        ── CLI + YAML 配置 + 流水线编排
```

## 2. 模块依赖图

（从各模块 `pom.xml` 的 `<artifactId>plumb-*</artifactId>` 实测得出）

```
                ┌──────────────────┐
                │   plumb-parser   │  (无 plumb 内部依赖，只用 javaparser)
                └────────┬─────────┘
                         │
            ┌────────────┴─────────────┐
            ▼                          ▼
      ┌──────────┐              ┌──────────────┐
      │ plumb-tac │              │ plumb-style  │
      └─────┬─────┘              └──────┬───────┘
            │                           │
            ▼                           ▼
      ┌──────────┐               ┌─────────────┐
      │plumb-rules│◀──────────────┤plumb-reporter│
      └────┬─────┘               └──────┬──────┘
           │                            │
           │   ┌────────────┐           │
           └──▶│  plumb-dfa  │◀──────────┘
               └─────┬──────┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │plumb-build│ │ plumb-ai │ │plumb-style│
  └────┬─────┘ └────┬─────┘ └────┬─────┘
       │            │            │
       └────────────┴────────────┘
                    │
                    ▼
              ┌──────────┐
              │plumb-main│  (orchestration)
              └──────────┘
```

实际的直接依赖（每个模块的 `pom.xml`）：

| 模块 | 依赖的 plumb 模块 |
| --- | --- |
| `plumb-parser` | （无） |
| `plumb-tac` | `plumb-parser` |
| `plumb-rules` | `plumb-tac` |
| `plumb-reporter` | `plumb-rules` |
| `plumb-style` | `plumb-parser`, `plumb-rules`, `plumb-reporter` |
| `plumb-dfa` | `plumb-parser`, `plumb-tac`, `plumb-rules`, `plumb-reporter` |
| `plumb-build` | `plumb-rules` |
| `plumb-ai` | `plumb-rules`, `plumb-reporter` |
| `plumb-main` | 以上全部 |

关键边：

- `plumb-parser` 是叶子节点，**不依赖**任何 plumb 内部模块（仅 JavaParser）
- `plumb-tac` 只依赖 `plumb-parser`（因为 `TacTranslator` 接收 `ParsedSource`）
- `plumb-style` 只依赖 `plumb-parser` + `plumb-rules` + `plumb-reporter` —— **不依赖 plumb-tac / plumb-dfa**（这是架构原则）
- `plumb-dfa` / `plumb-dfa` 依赖 `plumb-tac` + `plumb-rules` + `plumb-reporter`
- `plumb-main` 依赖全部下游模块，做编排


## 3. 模块边界

### 3.1 plumb-parser（独立）

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

### 3.2 plumb-tac

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

### 3.3 plumb-style

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

### 3.4 plumb-reporter

```xml
<dependencies>
    <dependency>plumb-rules</dependency>          <!-- Finding.rule 字段 -->
    ...
</dependencies>
```

**不**依赖 `plumb-dfa`。

### 3.5 plumb-dfa

```xml
<dependencies>
    <dependency>plumb-tac</dependency>
    <dependency>plumb-rules</dependency>
    <dependency>plumb-reporter</dependency>       <!-- Finding -->
    ...
</dependencies>
```

### 3.6 plumb-main

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

## 4. 流水线数据流

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
  ▼  plumb-dfa
List<Finding> (dfa)
  │
  ▼  plumb-reporter
NDJSON / console
```

注意：plumb-style 和 plumb-tac **共享 ParsedSource** 作为输入，但**互相独立**：

- plumb-style 直接消费 ParsedSource
- plumb-tac 用 TacTranslator 把 ParsedSource 翻译成 TacProgram
- plumb-dfa 只看 TacProgram，不看 ParsedSource

这意味着：
- plumb-style 的检查**和 TAC 无关**，可以在没有 TAC 模块的情况下独立运行（用于纯风格检查工具）
- plumb-dfa 的检查**和 AST 无关**，可以在没有 plumb-style 的情况下独立运行（用于纯数据流分析工具）


