# CLI 典型场景与默认配置对照

本文档是 `CLI.md` 的 §6 + §13 拆分版本。完整 CLI 文档（快速开始 / 全部 flag / YAML / 启用/禁用 / 输出 / 退出码 / 环境变量 / 故障排查）见 `CLI.md`；项目路径族 / build-system / YAML v2 速查见 `CLI-REFERENCE.md`。

## 1. 典型场景示例

### 6.1 单文件快速扫描

```bash
java -jar plumb-...-all.jar -i Foo.java
# 默认开全部规则，console 输出到 stdout
```

### 6.2 大型仓库 + 增量缓存

```bash
# 第一次：写缓存
java -jar plumb-...-all.jar \
     -i /opt/big-project/src \
     -f ndjson -o findings.ndjson \
     --output-dir .plumb-cache \
     --cache-mode write

# 第二次：只翻译修改过的文件
java -jar plumb-...-all.jar \
     -i /opt/big-project/src \
     -f ndjson -o findings.ndjson \
     --output-dir .plumb-cache \
     --cache-mode readwrite
```

### 6.3 仅风格类检查

```bash
java -jar plumb-...-all.jar -c - <<EOF
inputs: [src/main/java]
checks:
  taint: false
  divByZero: false
  nullDeref: false
  intOverflow: false
  # 其余 DFA 类显式关闭
  resourceLeak: false
  hardcodedCred: false
  arrayBounds: false
  weakCrypto: false
  insecureRandom: false
  predictableSeed: false
  predictableKey: false
  staticApiKey: false
  insecureComms: false
  corsMisconfig: false
  insecureCookie: false
  badShift: false
  infiniteLoop: false
  constantCondition: false
  sensitiveDataLeak: false
  fieldHardcodedPassword: false
  missingAuthorization: false
  missingInputValidation: false
  informationExposure: false
  uncheckedReturn: false
  incorrectReturn: false
  threadRun: false
  stringEquality: false
  catchGeneric: false
  obsoleteFunction: false
  tryCatchFinally: false
  offByOne: false
  equalsCalledOnNull: false
  compareWithSelf: false
  xxe: false
  returnFromFinally: false
  emptyCatchHandler: false
  iteratorConcurrentModification: false
  collectionSelfReference: false
  uncaughtExceptionPropagator: false
  uncheckedLoopCondition: false
  uncontrolledMemAlloc: false
  numericTruncation: false
EOF
```

### 6.4 并行度

```bash
# 8 核机器上限制到 6 个并发翻译线程
java -jar plumb-...-all.jar -i src --max-parallelism 6
```

`--max-parallelism 0` = auto = `Runtime.availableProcessors() - 1`，floor 1。

## 2. 命令与默认配置对照

`conf/default-config.yaml` 随包带——它的全部字段都注释掉，等于"全部默认"。下表给出每个字段的默认值 + 改它的方式：

| 字段 | 默认值 | 改它的方式 |
| --- | --- | --- |
| `inputs` / `input` | `[]` | YAML 加项，或 CLI `--input <path>` 重复 |
| `include` / `exclude` | `[]` / `[]` | 仅 YAML，substring 过滤 |
| `output.path` | (stdout) | YAML `output.path:` 或 `-o` |
| `output.format` | `console` | YAML `output.format:` 或 `-f console\|ndjson\|sarif` |
| `outputDir` | (无) | YAML `outputDir:` 或 `-D <dir>` |
| `cacheMode` | `off` | YAML `cacheMode:` 或 `--cache-mode` |
| `maxParallelism` | `0`（= `nCPU-1`） | YAML `maxParallelism:` 或 `--max-parallelism` |
| `cgStrategy` | `global`（ 后） | YAML `cgStrategy:` 或 `--cg-strategy` |
| `sourceRoots` | `[]` | YAML 或 `--source-root`（可重复） |
| `moduleRoots` | `[]` | YAML 或 `--module-root` |
| `classpathJars` | `[]` | YAML 或 `--classpath` |
| `buildSystem.name` | (无) | YAML 或 `--build-system` |
| `buildSystem.projectRoot` | (无) | YAML 或 `--project-root` |
| `rules.enabled` / `rules.disabled` | `[]` / `[]` | 仅 YAML |
| `checks.*` | 几乎全 `true`（详见 §3） | YAML 覆盖，CLI 用 `--rule <id>` 临时过滤 |
| `ai.dedup` | `false` | YAML 或 `--ai-dedup` |
| `ai.dropOnAiFp` | `false` | 仅 YAML |
| `ai.provider` | `openai` | 仅 YAML |
| `ai.apiKey` | (无，**必须从 `${ENV_VAR}` 读**) | 仅 YAML |
| `ai.model` | `gpt-4o-mini` | 仅 YAML |
| `ai.timeoutSeconds` | `30` | 仅 YAML |
| `ai.maxConcurrent` | `4` | 仅 YAML |
| `ai.temperature` | `0.0` | 仅 YAML |
