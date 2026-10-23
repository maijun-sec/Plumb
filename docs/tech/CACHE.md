# 磁盘 IR 缓存

本文档说明 Plumb 的 `--output-dir` 机制如何把 TAC 中间结果持久化到磁盘，让多次扫描增量复用。

## 1. 设计目标

| 目标 | 方案 |
| --- | --- |
| 减少重复 parse + translate | 把 TacProgram 按源文件粒度写盘 |
| 控制内存峰值 | 阶段间通过磁盘交换数据 |
| 不破坏现有 DFA 引擎 | 序列化是 IR 类"透明"的（`implements Serializable`） |
| 可压缩 | 接受 `.gz` 后缀触发 GZIP 包装 |
| 版本可识别 | 文件头 `MAGIC` + `VERSION` 两个 int |

## 2. 目录布局

```
<outputDir>/
├── cache/
│   ├── programs/
│   │   ├── <sha1>.tac              一文件一 TacProgram
│   │   └── <sha1>.tac.gz           可选 gzip 压缩
│   ├── merged.tac                  整个 MergedProgram（暂未自动写）
│   └── callgraph.tac               全局 call graph（暂未自动写）
└── report/
    ├── findings.ndjson             实际写入的发现（当 -f ndjson）
    └── findings.console            实际写入的发现（当 -f console）
```

`OutputLayout`（`plumb-main/.../OutputLayout.java`）负责构造路径 + 创建目录。

## 3. 文件格式

```
┌──────────────┬──────────────┬─────────────────┐
│   MAGIC      │   VERSION    │   <Object>      │
│ 0x504C554D   │ 0x00000001   │ Java 序列化     │
│  (4 字节)    │  (4 字节)    │ (任意 IR 对象)  │
└──────────────┴──────────────┴─────────────────┘
```

`MAGIC = 0x504C554D` 是 ASCII "PLUM"。`VERSION = 1`。

`writeObject(tag, value, file)` 额外写一个 `UTF string` tag（解析时只读出来丢弃，目前仅 informational）。

### 3.1 序列化方式：为什么用 Java 原生而不是 Kryo

Plumb 用 **`ObjectOutputStream` / `ObjectInputStream`**（Java 内建序列化）做磁盘缓存，不用 Kryo / Protobuf / Avro 等第三方框架。理由：

| 维度 | Java 原生 | Kryo |
| --- | --- | --- |
| 依赖 | 0（标准库） | 1（`com.esotericsoftware:kryo`），还要写每类的 `register` / 字段映射 |
| 速度 | 慢 5-10x（小数据） | 快 5-10x |
| 输出大小 | 偏大（每对象 ~50 bytes 元数据） | 偏小 |
| Schema 演进 | 加字段不破坏旧 reader（保持 `serialVersionUID`） | 加字段要手动维护 `FieldSerializer` |
| 不支持的类型 | JDK 17+ 对 `Path` / 复杂泛型要 custom `writeObject` | 支持更广，但每个不识别的类要 `addDefaultSerializer` |

Plumb 的 TAC IR 是**小数据量 + 异构字段 + 长生命周期**（cache 跨 run 复用）的场景：

- **数据量小**：21 个 example 文件总共 174 KB；Spring 6 估算 150 MB，对磁盘 IO 不是瓶颈
- **字段异构**：15 个 IR 类有各自的可变字段（`TacInstruction.line` / `column`、`TacOperand` 的 sealed 形态、`SourceMap.Loc` record、`TacProgram.sourcePath` 是 `java.nio.file.Path`）—— 每加一个字段 Kryo 都要重新注册
- **长生命周期**：cache 文件可能跨 JVM 启动被读回；标准库是"装载-实例化-读"的稳定接口

> **何时切到 Kryo**：等磁盘 IO 真成瓶颈（TB 级 cache 或 10000+ 文件仓库）再换。当前的 `Performance` §10 数据（单文件 10 ms / 21 文件 150 ms）说明原生够用。

### 3.2 源映射（line / column / SourceMap）保留契约

磁盘缓存要回答的核心问题：**从 cache 读回的 IR 还能不能告诉用户"finding 出在哪个文件的哪一行？"**——这是所有 reporter 的基础。保留链路有 3 处：

| 字段 | 类型 | 在哪个类 | 来源 |
| --- | --- | --- | --- |
| `TacInstruction.line` | `int` | `TacInstruction` | JavaParser / Jimple 在 translate 时记录的源行号 |
| `TacInstruction.column` | `int` | `TacInstruction` | JavaParser / Jimple 在 translate 时记录的源列号 |
| `SourceMap.Loc` record | `record(line, column)` | `SourceMap.Loc` | 后置处理器（CFG builder 等）补充的位置 |
| `TacProgram.sourcePath` | `java.nio.file.Path` | `TacProgram` | 原 .java 文件的绝对路径（cache 文件名由它的 SHA-1 派生） |

每个字段都通过测试覆盖（`plumb-tac/src/test/.../IrSerializerTest.java`）：

- `roundTripPreservesSourceLineAndColumn` —— 验证 `line` / `column` 三个不同值精确 round-trip
- `roundTripPreservesSourceMap` —— 验证 `SourceMap.Loc` record 通过 `writeObject` / `readObject` 完整保留
- `roundTripPreservesSourcePath` —— 验证 `TacProgram.sourcePath` 通过 `transient` + 自定义 `writeObject` / `readObject` 恢复为 `Path`

#### 3.2.1 `TacProgram.sourcePath` 序列化的特殊性

`java.nio.file.Path` 在任何目标 JDK 上**都不实现 `Serializable`**。`TacProgram` 的解法：

```java
private transient Path sourcePath;          // 标记为不参与默认序列化
...
private void writeObject(ObjectOutputStream out) throws IOException {
    out.defaultWriteObject();
    out.writeObject(sourcePath == null ? null : sourcePath.toString());
}
private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    Object raw = in.readObject();
    this.sourcePath = raw == null ? null : Path.of((String) raw);
}
```

注意：
- 字段是 `transient`，所以 Java 默认机制不参与；我们手动以 `String` 形式写入
- **字段不是 `final`**（虽然在所有调用方都是构造时一次性设的）：`final` + `transient` + `readObject` 重新赋值在 JDK 17+ 会触发"反射 setAccessible + 反射 strip final 修饰符"的反射黑魔法，脆弱；用 `transient` + 可变字段最简单
- `null` 是允许的：synthetic 程序的 `sourcePath` 可能为 `null`（cache 文件名才是不变量）

#### 3.2.2 `SourceMap.Loc` record 序列化的特殊性

`record` 默认**不**实现 `Serializable`，即使外层 `SourceMap` 是 `Serializable` 的。需要显式：

```java
public record Loc(int line, int column) implements java.io.Serializable {
    public Loc { Objects.requireNonNull(this); }
    private static final long serialVersionUID = 1L;
}
```

否则 `writeObject` 在外层 `SourceMap` 上会抛 `NotSerializableException: SourceMap$Loc`。

## 4. 缓存 Key

每源文件一个 cache file，文件名是：

```
SHA-1(absolute(canonicalize(sourcePath)))
```

`canonicalize` 在 Windows 上是规范化大小写、替换 `/` 为 `\`；在 POSIX 上是 symlink 解析 + 去尾部 `/`。两次扫同一仓库得到同一 key，缓存命中。

## 5. 模式

`ScanConfig.CacheMode` 四种：

| 模式 | 行为 |
| --- | --- |
| `OFF` | 不读不写（默认） |
| `READ` | 命中缓存则跳过 parse + translate；不写 |
| `WRITE` | 总是重新 parse + translate，**写**缓存到磁盘 |
| `READWRITE` | 命中则用缓存；未命中则计算并**写**缓存 |

CLI： `--cache-mode readwrite` ；YAML： `cacheMode: readwrite`。

## 6. 调用链

```
ScanRunner.run() [plumb-main]
  ├── layout.ensureDirectories()    // mkdir -p cache/programs report
  ├── expandInputs → List<Path>
  ├── if (files.size() > 1) scanMerged()
  │     ├── for each file in parallel:
  │     │     ├── loadCachedProgram(file) → TacProgram (or null)
  │     │     ├── if null: parse + translate
  │     │     └── storeCachedProgram(file, program)
  │     ├── merge into MergedProgram
  │     └── runDfaCheckers + runUnifiedCheckers
  └── emit findings
```

关键代码：

```java
private TranslatedFile translateAndStyleOne(Path file, RuleRegistry rules) {
    TacProgram program = loadCachedProgram(file);
    if (program != null) {
        // re-parse only for style; cheap
        ps = new JavaSourceParser().parse(file);
    } else {
        ps = new JavaSourceParser().parse(file);
        program = new TacTranslator(ps).translate();
        storeCachedProgram(file, program);
    }
    // style always runs fresh (rule set may have changed)
    return new TranslatedFile(file, ps, program, runStyleChecks(ps, file, rules));
}
```

## 7. 兼容性

### 7.1 向前兼容

新加字段（用 `transient` 标记的除外）→ ObjectInputStream 仍能反序列化旧对象，**只要没改 `serialVersionUID`**。

每个 IR 类的 `serialVersionUID = 1L`。新增字段时**保持** 1L。

### 7.2 向后不兼容

如果改了 `serialVersionUID` 或重命名字段，旧 cache 文件读会抛 `InvalidClassException`。`IrSerializer.readProgram` 捕获后用 `IOException("IR class missing on classpath: ...")` 包装，ScanRunner 日志 WARN 后回到正常 translate 流程。

### 7.3 整体不兼容

`VERSION` 字段。ScanRunner 永远检查 `if (version != IrSerializer.VERSION)`，不匹配就拒绝。

## 8. 何时失效

清空 `<outputDir>/cache/` 的条件：

- 升级 Plumb 大版本后（VERSION 改了）
- 改了源文件路径（key 变了，旧 cache 自然失效）
- 改 `RuleRegistry` 的某些硬编码源/汇签名（虽然 cache 内容没变，但 finding 结果会变）
- 改 Z3 / SootUp 版本（影响污点 / 路径条件求解）

## 9. 序列化失败的处理

`storeCachedProgram` 是 best-effort：失败 WARN 日志，不影响扫描主流程。

`loadCachedProgram` 失败时返回 null，触发重新 translate。

## 10. 性能

- 单文件 (Java 源码 ~5 KB)：序列化 ~10 ms，反序列化 ~5 ms
- 21 文件 (examples/ 目录)：序列化 ~150 ms，磁盘 100 KB ~ 130 KB
- Spring 6 (~5000 文件)：估算序列化 30-60 秒，磁盘 ~150 MB

## 11. 未来扩展

- **merged.tac 写盘**：当前 scanMerged 在内存中合 MergedProgram，后续可写盘，让"先做 style、再做 dfa"能跨进程分两段跑
- **callgraph.tac 写盘**：unified 引擎的 call graph 单独写盘，大项目能省下 5-10 分钟
- **mtime-based invalidation**：现在只用 SHA-1(path) 作 key。改用 `SHA-1(path + mtime + size)` 可以更安全（路径不变但内容变了也能识别）
- **多平台支持**：Windows 大小写不敏感会让同一个文件产生不同 key（canonicalize 已处理，但需要小心）
