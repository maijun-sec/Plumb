# CLI 典型场景与默认配置对照

[English](../../usage/CLI-EXAMPLES.md) | [中文](../README.md)

本文档收集典型场景与命令-默认配置对照。完整 CLI 文档（快速开始 / 全部 flag / YAML / 启用/禁用 / 输出 / 退出码 / 环境变量 / 故障排查）见 `CLI.md`；项目路径族 / build-system / YAML v2 速查见 `CLI-REFERENCE.md`。

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
  # ---- 总开关 ----
  taint: false
  # ---- DFA 类：48 个 family 全部显式关闭 ----
  divByZero: false
  resourceLeak: false
  nullDeref: false
  hardcodedCred: false
  intOverflow: false
  intUnderflow: false
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
  assignmentInOperand: false
  bigDecimalDoubleConstructor: false
  defaultEncoding: false
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
  errorMessageInfoLeak: false
  commentInfoLeak: false
  resourceExhaustion: false
EOF
```

> `errorMessageInfoLeak` / `commentInfoLeak` 也在列表里：它们参与"要不要跑统一
> DFA 那一层"的判断，两个都关掉才能真的把 DFA 关掉。漏了它们，
> 这个片段仍然会跑 DFA。`checks:` 下的 48 个 family 少一个，
> 那一条就还在跑 —— 用 `scripts/check_doc_claims.py` 守着这个列表。

### 6.4 并行度

```bash
# 8 核机器上限制到 6 个并发翻译线程
java -jar plumb-...-all.jar -i src --max-parallelism 6
```

`--max-parallelism 0` = auto = `Runtime.availableProcessors() - 1`，floor 1。

## 2. 命令与默认配置对照

`conf/default-config.yaml` 随包带。它**大部分**字段是注释掉的，但有 4 个顶层块是生效的：
`maxParallelism: 4`、`cgStrategy: global`、`checks:`、`ai:`。下表给出每个字段的默认值 + 改它的方式：

| 字段 | 默认值 | 改它的方式 |
| --- | --- | --- |
| `inputs` / `input` | `[]` | YAML 加项，或 CLI `--input <path>` 重复 |
| `include` / `exclude` | `[]` / `[]` | 仅 YAML，substring 过滤 |
| `output.path` | (stdout) | YAML `output.path:` 或 `-o` |
| `output.format` | `console` | YAML `output.format:` 或 `-f console\|ndjson\|sarif` |
| `outputDir` | (无) | YAML `outputDir:` 或 `-D <dir>` |
| `cacheMode` | `off` | YAML `cacheMode:` 或 `--cache-mode` |
| `maxParallelism` | `0`（= `nCPU-1`）；随包配置里是 `4` | YAML `maxParallelism:` 或 `--max-parallelism` |
| `cgStrategy` | `global`（ 后） | YAML `cgStrategy:` 或 `--cg-strategy` |
| `sourceRoots` | `[]` | YAML 或 `--source-root`（可重复） |
| `moduleRoots` | `[]` | YAML 或 `--module-root` |
| `classpathJars` | `[]` | YAML 或 `--classpath` |
| `rules.enabled` / `rules.disabled` | `[]` / `[]` | 仅 YAML |
| `checks.*` | 几乎全 `true`（详见 `CLI.md` §3） | YAML 覆盖，CLI 用 `--rule <id>` 临时过滤 |
| `ai.dedup` | `false` | YAML 或 `--ai-dedup` |
| `ai.dropOnAiFp` | `false` | 仅 YAML |
| `ai.provider` | `openai` | 仅 YAML |
| `ai.apiKey` | (无，**必须从 `${ENV_VAR}` 读**) | 仅 YAML |
| `ai.model` | `gpt-4o-mini` | 仅 YAML |
| `ai.timeoutSeconds` | `30` | 仅 YAML |
| `ai.maxConcurrent` | `4` | 仅 YAML |
| `ai.temperature` | `0.0` | 仅 YAML |
