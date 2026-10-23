# Plumb 环境变量与配置

> **R684 / 2026-08-24** · Plumb 0.1.0-SNAPSHOT
> 列出 Plumb 运行时读取的所有环境变量 + API key 双通道配置机制。

---

## 1. 概览

| 变量 | 必须? | 默认 | 作用域 |
| --- | ---: | --- | --- |
| `JAVA_HOME` | optional | `java` 在 PATH | 启动器找 JRE |
| `MINIMAX_API_KEY` | optional | — | MiniMax (含 M3 reasoning) |
| `OPENAI_API_KEY` | optional | — | OpenAI / 兼容 endpoint |
| `ANTHROPIC_API_KEY` | optional | — | Anthropic |
| `PLUMB_HOME` | optional | 脚本自身目录 | 安装根 |
| `PLUMB_JAVA_OPTS` | optional | `-Xmx4g` | JVM 参数透传 |
| `PLUMB_DEBUG` | optional | false | DEBUG 日志开关 |
| `PLUMB_CACHE_DIR` | optional | `<outputDir>/cache` | IR 缓存目录 |
| `http_proxy` / `https_proxy` | optional | — | AI 出站代理 |
| `no_proxy` | optional | — | AI 代理白名单 |

**双通道配置优先级 (R684 新)**:

```
CLI flag  >  环境变量  >  YAML 配置文件  >  内置 default
```

---

## 2. `JAVA_HOME` (可选,推荐)

**作用**:启动器 (`bin/plumb` / `bin/plumb.bat`) 找 JRE 的依据。

**默认行为**:
- 如果 `bin/plumb` 在 PATH 中找到 `java`,**直接用** (不管 `JAVA_HOME`)
- 否则读 `$JAVA_HOME/bin/java` 或 `%JAVA_HOME%\bin\java.exe`
- 否则 (解压即用包):用捆绑的 `jre/bin/java` (`./bin/plumb` 脚本自动找 `<PLUMB_HOME>/jre/`)

**推荐设置**:
- Linux: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk`
- macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
- Windows: `setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot"`

**JRE 最低版本**:**Java 17** (因为 Plumb 用 `java.net.http.HttpClient` 强类型 API + records + switch 表达式)。

**JRE 推荐版本**:
- 开发:Java 17 LTS 或 Java 21 LTS
- 生产:Java 17 LTS (与 maven-compiler-plugin 目标一致)

---

## 3. AI API Key 三家 (R684 双通道)

### 3.1 三家 key 互斥,优先级如下

```
MINIMAX_API_KEY  >  OPENAI_API_KEY  >  ANTHROPIC_API_KEY  >  YAML ai.apiKey
```

如果同时设置 `MINIMAX_API_KEY` 和 `OPENAI_API_KEY`,Plumb 用 MiniMax (因为 MiniMax 兼容 OpenAI 协议,优先级靠前避免混淆)。

**不推荐**同时设多家 — Plumb 用一个 provider 就够。

### 3.2 `MINIMAX_API_KEY`

**作用**:MiniMax 系列模型 (含 `MiniMax-M3` reasoning + `MiniMax-Text-01` default)。

**取得**:[MiniMax 开发者平台](https://api.minimax.chat/) 创建 key。

**设置**:
```bash
# Linux / macOS
export MINIMAX_API_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

# Windows cmd
set MINIMAX_API_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...

# Windows PowerShell
$env:MINIMAX_API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

**对应 YAML 配置** (`conf/default-config.yaml`):
```yaml
ai:
  provider: openai_compatible
  baseUrl: https://api.minimax.chat
  apiKey: ${MINIMAX_API_KEY}   # 自动展开环境变量
  model: MiniMax-M3            # reasoning model
  timeoutSeconds: 30
```

**R683 修复**:
- `stripThinkBlocks()` 自动去掉 `<think>...</think>` 块 (M3 / Claude thinking 思维链)
- 自动加 `max_tokens=4096` 和 `reasoning_effort=low` (M3 默认会把全部 token 烧在 thinking 上,不加这两个参数 verdict 永远拿不到)
- 兼容 `MiniMax-Text-01` (非 reasoning) 也工作

### 3.3 `OPENAI_API_KEY`

**作用**:OpenAI / Azure / Ollama / vLLM / LocalAI / 任何 OpenAI 兼容的 `/v1/chat/completions` endpoint。

**设置**:同 `MINIMAX_API_KEY`。

**YAML 配置**:
```yaml
ai:
  provider: openai              # 或 openai_compatible
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}
  model: gpt-4o-mini
```

### 3.4 `ANTHROPIC_API_KEY`

**作用**:Anthropic Claude (R670+ 支持,**但 tool-calling 未实现**,只支持单 prompt)。

**YAML 配置**:
```yaml
ai:
  provider: anthropic
  baseUrl: https://api.anthropic.com
  apiKey: ${ANTHROPIC_API_KEY}
  model: claude-3-5-sonnet-20241022
```

### 3.5 配置文件 fallback (YAML `ai.apiKey: <literal>`)

**不推荐**!硬编码 key 会泄露到 git。

但**有合法用途**:
- CI 环境用 secret manager 注入文件
- 容器内 baked-in image (假设 image 是私有的)

```yaml
# 危险!勿提交到 git
ai:
  apiKey: sk-...literal...
```

**R684 起,优先级**:`环境变量 > YAML ai.apiKey > 内置 default`。

---

## 4. `PLUMB_HOME` (可选)

**作用**:Plumb 安装根目录。

**默认行为**:`bin/plumb` 启动器从 `$0` (自身路径) 反推 `..` 作为 `PLUMB_HOME`。

**设置**:
```bash
# 显式覆盖 (当启动器不在标准 bin/ 位置)
export PLUMB_HOME=/opt/plumb-0.1.0-SNAPSHOT
```

**Plumb 内部使用**:
- `PLUMB_HOME/conf/default-config.yaml` (如果存在,优于 classpath 内的)
- `PLUMB_HOME/conf/logback.xml` (同上)
- `PLUMB_HOME/lib/*.jar` (额外 jar 路径,`bin/plumb` 自动加到 classpath)
- `PLUMB_HOME/conf/rule-prompts/` (AI prompts,自动加到 prompt registry)

---

## 5. `PLUMB_JAVA_OPTS` (可选)

**作用**:透传 JVM 参数。

**默认**:`-Xmx4g -XX:+UseG1GC` (大仓库扫描友好;小仓库 4 GB 也够用)。

**设置**:
```bash
# 限制内存 (小仓库 / CI)
export PLUMB_JAVA_OPTS="-Xmx2g -XX:+UseSerialGC"

# 调试 (开启 JFR + 详细 GC)
export PLUMB_JAVA_OPTS="-Xmx4g -XX:+UseG1GC -XX:StartFlightRecording=duration=60s,filename=plumb.jfr -verbose:gc"
```

**`bin/plumb` 启动器用法**:
```bash
# 内置启动器读取顺序:
#   1. $PLUMB_JAVA_OPTS
#   2. 默认 "-Xmx4g -XX:+UseG1GC"
# → 实际 exec: java $PLUMB_JAVA_OPTS org.plumb.main.SastMain "$@"
```

---

## 6. `PLUMB_DEBUG` (可选)

**作用**:开启 DEBUG 日志。

**默认**:false (INFO 级别)。

**设置**:
```bash
export PLUMB_DEBUG=true
# → logback 配置切换到 DEBUG
# → 在 stderr 上看到:
#   [main] DEBUG org.plumb.tac.TacTranslator - translating method Foo.bar
#   [main] DEBUG org.plumb.dfa.engine.DfaEngine - entering function Foo.bar
#   [main] DEBUG org.plumb.dfa.taint.TaintDomain - new fact: {var: t1, taint: TAINTED, source: ...}
```

**生产慎用**:日志可能膨胀到 GB 级别。

---

## 7. `PLUMB_CACHE_DIR` (可选)

**作用**:IR 缓存目录覆盖。

**默认**:`<outputDir>/cache/programs/<sha1>.tac` (R218a-R218c 序列化方案)。

**设置**:
```bash
# 共享 IR 缓存 (多用户,多项目)
export PLUMB_CACHE_DIR=/var/cache/plumb-ir
# 注意:目录不存在会自动创建
```

**用途**:
- CI 缓存层 (`-DcacheMode=readwrite`)
- 大项目增量扫描 (`-DcacheMode=readwrite` + 持续集成)

---

## 8. HTTP 代理 (`http_proxy` / `https_proxy`)

**作用**:AI dedup 调用 LLM endpoint 走代理。

**默认**:无代理。

**设置**:
```bash
# HTTP 代理
export http_proxy=http://proxy.corp.example.com:8080
export https_proxy=http://proxy.corp.example.com:8080

# 跳过本地 / 内部
export no_proxy=localhost,127.0.0.1,.corp.example.com,10.*
```

Plumb 用 JDK `java.net.http.HttpClient` (R672),自动读 `http_proxy` / `https_proxy` / `no_proxy` 标准环境变量。

**注意**:非 ASCII URL 走 AI 工具 (`GetClassHierarchyTool` 等) 不通过 HTTP,代理不影响。

---

## 9. 不需要的环境变量 (常见误解)

| 变量 | Plumb 是否读 | 备注 |
| --- | ---: | --- |
| `JAVA_OPTS` | ❌ | 用 `PLUMB_JAVA_OPTS` 替代 |
| `MAVEN_OPTS` | ❌ | 仅 `mvn` 用,不影响 `plumb` 启动 |
| `PATH` | ✅ (间接) | 找 `java` 备用 |
| `CLASSPATH` | ❌ | `bin/plumb` 自己加 `lib/*` |
| `HOME` / `USERPROFILE` | ❌ | 不写 `~/.plumbrc` |
| `LD_LIBRARY_PATH` | ❌ | Plumb 无 native deps (z3 / sootup 纯 Java) |

---

## 10. 完整示例

### 10.1 CI (GitHub Actions)

```yaml
env:
  MINIMAX_API_KEY: ${{ secrets.MINIMAX_API_KEY }}
  PLUMB_JAVA_OPTS: "-Xmx4g"
steps:
  - uses: actions/checkout@v4
  - run: |
      java -jar plumb-...-all.jar \
        -c conf/default-config.yaml \
        -i src/ \
        --ai-dedup \
        -o build/findings.ndjson
```

### 10.2 本地开发 (Linux / macOS ~/.bashrc)

```bash
# Plumb
export PLUMB_HOME=$HOME/opt/plumb-0.1.0-SNAPSHOT
export PATH=$PLUMB_HOME/bin:$PATH
export MINIMAX_API_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
export PLUMB_JAVA_OPTS="-Xmx4g -XX:+UseG1GC"

# 代理 (企业内网)
export http_proxy=http://proxy.corp:8080
export https_proxy=http://proxy.corp:8080
export no_proxy=localhost,127.0.0.1,.corp.example.com
```

### 10.3 Windows (PowerShell $PROFILE)

```powershell
$env:PLUMB_HOME = "C:\tools\plumb-0.1.0-SNAPSHOT"
$env:PATH = "$env:PLUMB_HOME\bin;$env:PATH"
$env:MINIMAX_API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
$env:PLUMB_JAVA_OPTS = "-Xmx4g -XX:+UseG1GC"
$env:http_proxy = "http://proxy.corp:8080"
```

### 10.4 Air-gapped (无 AI)

不设任何 API key。Plumb 自动跳过 AI 阶段,只跑静态分析。

```bash
# 完全 air-gapped
unset MINIMAX_API_KEY
unset OPENAI_API_KEY
unset ANTHROPIC_API_KEY
unset http_proxy
unset https_proxy

java -jar plumb-...-all.jar -c conf/default-config.yaml -i src/
# → 静态 NDJSON 输出,无 ai_verdict 字段
```

---

## 11. 故障排查

### 11.1 "AI 静默, 没出 ai_verdict"

**原因 1**:`ai.enabled: false` (YAML 默认)。

```yaml
# 改 YAML 或 CLI
ai:
  enabled: true   # 关键
  dedup: true
```

**原因 2**:`ai.apiKey` 没设,Plumb 默认 `disabled()`。

```bash
# 检查
echo $MINIMAX_API_KEY
# 必须非空

# 或
java -jar ... -Dlogback.configurationFile=conf/logback.xml -i src/ --ai-dedup --ai-dedup-stats
# 看 stderr 的 "ai-disabled" / "provider-unavailable" / 等
```

**原因 3**:Endpoint 不可达 (代理 / DNS / 防火墙)。

```bash
# 测试
curl -X POST $MINIMAX_API_KEY_BASE/v1/chat/completions \
  -H "Authorization: Bearer $MINIMAX_API_KEY" \
  -d '{"model":"MiniMax-Text-01","messages":[{"role":"user","content":"ping"}]}'
```

### 11.2 "OOM (OutOfMemoryError)"

```bash
export PLUMB_JAVA_OPTS="-Xmx8g"
java -jar plumb-...-all.jar ...
```

**R683 修复**:`ScanRunner.scanMerged()` 不再 `.toList()` 47k files(用 per-CWE directory 扫描避免 OOM)。

**Juliet 整体扫描**:必须 `-Xmx6g`(全量 47k files),per-CWE `-Xmx4g` 够。

### 11.3 "LlmProvider 报 <think> 没去掉"

R683 已修。检查 plumb-ai 是不是 R683+ 版本:
```bash
java -jar plumb-...-all.jar -v
# 应该是 plumb 0.1.0-SNAPSHOT (R683+)
```

如果旧版本,NDJSON 里 `ai_reason` 会包含 `<think>...</think>` 块。**升级**或手动 strip:

```python
# 后处理
for line in open("findings.ndjson"):
    line = re.sub(r"<think>.*?</think>", "", line, flags=re.DOTALL)
    print(line.strip())
```

### 11.4 "M3 model 一直等不到 verdict"

R683 已修 (强制 `max_tokens=4096` + `reasoning_effort=low`)。如果还卡:
- 升级到 R683+ 版本
- 或者 YAML 手动加:
  ```yaml
  ai:
    maxTokens: 4096
    reasoningEffort: low
  extraHeader: '{"X-Reasoning-Effort":"low"}'   # 备用,看 endpoint 是否支持
  ```

---

## 12. 关键不变量

1. **不写 home 目录**:`~/.plumbrc` / `~/.config/plumb/` 之类。配置全在 `--config` 指定或 `<PLUMB_HOME>/conf/`。
2. **不写注册表**(Windows) / `~/Library/Application Support/`(macOS) / `~/.config/`(Linux)。
3. **API key 不进 NDJSON**:`OpenAiCompatibleLlmProvider` 永远不 log key (R672 验证)。
4. **缓存目录可清**:`<outputDir>/cache/` 完全可删,下次扫描重新生成。
5. **Temp 文件**:`<outputDir>/report/` + Java NIO tmp;不污染 `$TMPDIR` 之外的目录。

---

## 13. 维护

| 变更 | 影响的 R-round | 验证 |
| --- | --- | --- |
| 加新 env var | R* | (本文件) |
| 改 API key 优先级 | R684 | (本文件 + `OpenAiCompatibleLlmProvider` 行为) |
| 改 proxy 行为 | R* | JDK `HttpClient` 默认 |

新增 env var 时请同步更新本文档 + `default-config.yaml` 注释。
