# 环境变量与 AI 配置

[English](../../usage/ENVIRONMENT.md) | [中文](../README.md)

Plumb 读取的环境变量**很少**。整个代码库里只有两处真正读环境变量，都在
`plumb-main/.../ConfigLoader.java`：

```java
String apiKey = System.getenv("OPENAI_API_KEY");        // :487  默认值
...
String envName = s.substring(2, s.length() - 1);
apiKey = System.getenv(envName);                          // :525  "${VAR}" 展开
```

本文档的每个结论都对照代码核实过。

---

## 1. 环境变量总表

### 1.1 被读取的

| 变量 | 读取位置 | 用途 |
| --- | --- | --- |
| `OPENAI_API_KEY` | `ConfigLoader.java:487` | `ai.apiKey` 未在 YAML 里指定时的默认值 |
| YAML 中 `${任意变量名}` | `ConfigLoader.java:525` | 显式引用，`ai.apiKey: ${MY_KEY}` |

只有这两个。`ai.apiKey: ${MINIMAX_API_KEY}` 是**通过第二行**生效的——引擎并不知道
MiniMax 这个名字，它只看到 `${...}` 就去读那个变量。

### 1.2 不被读取的（常见误解）

以下变量在 **Java 代码**里**零引用**：

| 变量 | 状态 | 说明 |
| --- | --- | --- |
| `PLUMB_HOME` | 未读取 | 没有 `bin/plumb` 启动器，仓库里不存在该脚本。启动器用 `-Dplumb.home=` 系统属性传路径，不是环境变量 |
| `PLUMB_CACHE_DIR` | 未读取 | 缓存目录由 `--output-dir` 决定 |
| `MINIMAX_API_KEY` | 仅测试用 | `LlmIntegrationTest.java:144` 读取；主代码不读 |
| `ANTHROPIC_API_KEY` | 未读取 | 想用 Claude 需在 YAML 里写 `apiKey: ${ANTHROPIC_API_KEY}` |
| `http_proxy` / `https_proxy` | 间接 | JDK `HttpClient` 默认读标准代理变量，非 Plumb 显式实现 |

**但出包脚本会读两个**——它们不是 Java 代码，但随包发布，用户真的会设：

| 变量 | 读取位置 | 用途 |
| --- | --- | --- |
| `PLUMB_JAVA_OPTS` | `scripts/dist-jlink-linux.sh` / `scripts/dist-jlink-windows.ps1` | 透传 JVM 参数，默认 `-Xmx4g -XX:+UseG1GC` |
| `PLUMB_DEBUG` | `scripts/dist-jlink-linux.sh` / `scripts/dist-jlink-windows.ps1` | 设为 `true` 时给启动器加 `-Droot.level=DEBUG` |

用 `java -jar` 直接跑 uber-jar 时这两个变量不起作用——JVM 参数由启动 `java` 命令的人自己决定。

**AI 功能完全不用环境变量也可以跑**——不设任何变量、不传 `--ai-dedup` 即可，只做静态分析。

---

## 2. AI 配置

### 2.1 默认配置

`plumb-main/src/main/resources/default-config.yaml:182-193`：

```yaml
ai:
  dedup: false                      # FP reducer（CLI: --ai-dedup）
  dropOnAiFp: false                 # 丢弃被判 FP 的 finding，而不是标记 ai_verdict=FP
  dedupRules: []                    # 空 = 所有规则都过一遍；填了则只过这些
  provider: openai                  # openai | openai_compatible | anthropic
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}         # 启动时从环境读取
  model: gpt-4o-mini                # 你的 endpoint 接受什么就填什么
  header:                           # 可选的额外请求头
  timeoutSeconds: 30
  maxConcurrent: 4                  # 并发 dedup 请求数
  temperature: 0.0                  # 确定性判决
```

### 2.2 provider 取值

`LlmConnection.Provider`（`plumb-ai/.../LlmConnection.java:147`）只有三个值：

| 取值 | 说明 |
| --- | --- |
| `OPENAI` | OpenAI 自己的 `/v1/chat/completions` |
| `OPENAI_COMPATIBLE` | **线协议完全相同**。写 Azure / Ollama / LocalAI / vLLM / MiniMax 时用它。`openai` 是它的别名，两者走同一段代码（`ConfigLoader.java:483-485`） |
| `ANTHROPIC` | Anthropic 原生 `/v1/messages`，仅支持单 prompt；tool-calling 会抛 `UnsupportedOperationException` |

### 2.3 指向 MiniMax

```yaml
ai:
  provider: openai_compatible
  baseUrl: https://api.minimax.chat
  apiKey: ${MINIMAX_API_KEY}
  model: MiniMax-M3
```

### 2.4 API key 的来源（优先级从高到低）

1. YAML 里的字面量 `ai.apiKey: sk-...` —— **不推荐**，会进版本库
2. `ai.apiKey: ${SOME_VAR}` 展开的环境变量（`ConfigLoader.java:523-504`）
3. YAML 未写 `ai.apiKey` 时，`ConfigLoader.java:487` 兜底读 `OPENAI_API_KEY`
4. 都没有 → AI 连接不可用；不开 `--ai-dedup` 就完全不影响静态分析

---

## 3. CI 示例

### 3.1 GitHub Actions

```yaml
env:
  OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
steps:
  - uses: actions/checkout@v4
  - run: java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
         -c plumb-main/src/main/resources/default-config.yaml \
         -i src/ --ai-dedup -o build/findings.ndjson
```

### 3.2 完全离线（无 AI）

不设任何变量、不传 `--ai-dedup`：

```bash
java -jar plumb-main-...-all.jar -c conf/default-config.yaml -i src/ -o build/findings.ndjson
```

输出 NDJSON 不含 `ai_verdict` / `ai_reason` 字段。

### 3.3 大仓库内存

Plumb 不读 JVM 参数变量。大仓库扫描请直接在 java 命令上给：

```bash
java -Xmx8g -jar plumb-main-...-all.jar -i large-repo/ -o out/
```

---

## 4. Plumb 不做的事

1. **不写用户 home 目录**：没有 `~/.plumbrc` / `~/.config/plumb/`。配置只来自 `--config` 指定的文件与 classpath 内的 `default-config.yaml`。
2. **不写注册表 / 系统配置目录**（Windows registry、macOS `~/Library/Application Support`、Linux `~/.config`）。
3. **API key 不进 NDJSON**：provider 不打印 key。
4. **缓存目录可随时删**：`<outputDir>/cache/` 下都是可重建的产物。

---

## 5. 故障排查

### AI 没出 `ai_verdict`

1. `ai.dedup: false` 是默认值——必须显式开（YAML 或 `--ai-dedup`）。
2. `ai.apiKey` 解析成了 `null`：检查 `${VAR}` 里的变量名是否真的存在。`${...}` 解析不到时 **静默得到 null**，不会报错。
3. endpoint 不可达：先用 curl 打通 `/v1/chat/completions` 再排查代理。

```bash
curl -X POST "$BASE_URL/v1/chat/completions" \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}]}'
```

### 推理模型的 `<think>` 泄漏

reasoning 模型（MiniMax-M3、带 thinking 的 Claude 等）可能在 `ai_reason` 里留下
`<think>...</think>` 块。`OpenAiCompatibleLlmProvider.stripThinkBlocks(...)` 会剥离它；
若仍出现，说明 NDJSON 是旧版本产出的，重跑即可。

---

## 6. 维护

新增环境变量时必须同时更新：

1. 本文档 §1
2. `ConfigLoader.java` 里对应的 `System.getenv(...)`
3. 若它是 AI 相关，`default-config.yaml` 的 `ai:` 注释

反向检查：本仓库现存的环境变量**读取**只有 `ConfigLoader.java` 的两处。

```bash
grep -rn 'System\.getenv(' --include=*.java plumb-*/src/main
```

这条命令会返回十几行，但只有两行是**真正的读取**——`ConfigLoader.java` 的
`OPENAI_API_KEY` 和 `${VAR}` 展开。其余命中都是**检查器在用户代码里查找的字符串**
（`EnvVarCheck` / `EnvVarInfoExposureCheck` 之类的规则在找 `System.getenv` 这个调用形状，
它们自己不读环境变量）。区分方法是看命中处是不是一个真正的调用：
`System.getenv(` 后面跟着参数并被赋值/使用，是读取；出现在字符串字面量或 javadoc 里，是规则模式。
