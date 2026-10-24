# Plumb CLI 使用手册

本文档是 Plumb 命令行工具 `sast-scan` 的完整使用说明。

## 1. 快速开始

```bash
# 启动器（POSIX）
./dist/plumb-0.1.0-SNAPSHOT/bin/sast-scan --help

# 或直接用 jar
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar --help

# 最小扫描（使用内置规则）
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
     -i testcase/01-taint-crossfile/src/ChainOrchestrator.java

# 配置文件驱动
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
     -c testcase/config/min.yaml -i src/main/java
```

## 2. 全部命令行参数

执行 `sast-scan --help` 输出：

```
usage: sast-scan [--ai-dedup] [--ai-dedup-stats] [--build-system <maven|gradle|bazel|ant>]
                 [-c <yaml>] [--cache-mode <off|read|write|readwrite>]
                 [--cg-strategy <cha|rta|vta|1cfa|stub|global|cached:<name>:<path>>]
                 [--classpath <jar|dir>...] [-D <dir>] [-f <console|ndjson|sarif>] [-h]
                 [-i <path>...] [--list-rules] [--max-parallelism <n>] [-o <path>]
                 [--project-root <dir>] [--rule <id>...] [--rule-prompts-dir <path>]
                 [--module-root <dir>...] [--source-root <dir>...] [-v]
```

| 短选项 | 长选项 | 参数 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `-h` | `--help` | — | — | 打印 help 并退出 |
| `-v` | `--version` | — | — | 打印版本并退出 |
| `-c` | `--config` | `<yaml>` | — | 从 YAML 加载扫描配置（覆盖 / 扩展命令行 flag） |
| `-i` | `--input` | `<path>` | — | 输入文件或目录，**可重复**。支持 `.java`、`.jar`、`.class`、`.war`、`.ear`、`.zip` |
| `-o` | `--output` | `<path>` | `stdout` | 报告输出文件 |
| `-f` | `--format` | `console` \| `ndjson` \| `sarif` | `console` | 输出格式 |
| `-D` | `--output-dir` | `<dir>` | — | 报告 + 缓存输出根目录。子目录：`cache/programs/*.tac`、`report/findings.{ndjson,console}` |
| — | `--cache-mode` | `off` \| `read` \| `write` \| `readwrite` | `off` | 磁盘 IR 缓存使用模式 |
| — | `--cg-strategy` | 见下表 | `global` | Call-graph 构造策略，传给 `plumb-dfa` 引擎 |
| — | `--max-parallelism` | `<n>` | `nCPU-1`（最小 1） | parse + translate 阶段并发上限 |
| — | `--ai-dedup` | — | off | R-? — FP reducer 开关。把每条 finding 发给 LLM 判 TP/FP/UNKNOWN。verdict 写到 NDJSON 的 `ai_verdict` + `ai_reason` 字段。LLM 端点 + key 走 YAML（`ai.apiKey` / `ai.baseUrl` / `ai.model`） |
| — | `--ai-dedup-stats` | — | off | R-? — AI dedup 跑完后，往 **stderr** 打 verdict 分布 + 每条规则的 FP 占比 Top 10。stdout 的 NDJSON 不变。配合 `--ai-dedup` 一起用，单独开 no-op。 |
| — | `--rule` | `<id>` | — | 只跑指定 rule（**可重复**）。覆盖 YAML 的 `checks.*` 标志。**精确匹配** —— 要用真实的 rule id，例如 `--rule TAINT_SINK_SQL_INJECTION`；写不存在的 id 会静默产出 0 条 finding。用 `--list-rules` 查全部 id |
| — | `--rule-prompts-dir` | `<path>` | — | 用户自带的 rule prompt `.md` 目录。覆盖打包在 classpath 里的默认 prompt |
| — | `--list-rules` | — | — | 列出所有已知 rule id 并退出 |

**R797 / R833 项目路径族**（详见 §10）：

| 短选项 | 长选项 | 参数 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| — | `--source-root` | `<dir>` | — | 跨文件类型解析用的源码根（**可重复**）。R797 当成 `JavaModule` 看待；只喂 `JavaSourceParser.TypeSolver`，**不进 TAC 流水线也不扫 findings**。用于把第三方源码喂给符号解析，但不被 `cache/programs/` 索引 |
| — | `--module-root` | `<dir>` | — | 同 `--source-root`，语义分开是为了多 module 项目可按模块列出 |
| — | `--classpath` | `<jar\|dir>` | — | 第三方依赖 jar 或 class 目录（**可重复**）。只喂 TypeSolver，**绝不被翻译成 TAC IR**。第三方程 jar 存在的唯一目的是让用户源码能解析外部类型引用 |
| — | `--project-root` | `<dir>` | — | 传给 build-system adapter 的项目根 |
| — | `--build-system` | `maven\|gradle\|bazel\|ant` | — | 显式挑 build-system adapter。缺省时按 `pom.xml` / `build.gradle` / `BUILD` / `build.xml` 自动探测 |
| — | `--format` | `console\|ndjson\|sarif` | `console` | 输出格式（`sarif` 是 R-803 增加，IDE / GitHub Code Scanning 集成） |

`--output-dir` 和 `--cache-mode` **不强制成对使用**，但要有意义就得配齐：

- 只给 `--output-dir`：`ScanRunner` 仍会创建 `cache/` + `report/` 目录树
  （`OutputLayout.ensureDirectories()`），但 `--cache-mode` 默认为 `off`，
  所以缓存目录会是空的
- 只给 `--cache-mode readwrite`：没有任何地方会写盘 —— 缓存路径由
  `--output-dir` 决定，没给就没有 cache 目录可读可写
- 两者都给：`--cache-mode` 决定 `cache/programs/*.tac` 的读写行为

### 2.1 R-? 单条规则的 AI 行为

> **Step 7 之后的模型**：每条 `DfaChecker` / `AstChecker` 自己持有
> `getAiPrompt(Finding) -> String`。返回 `null` = 该规则**不走** AI dedup；
> 返回非 null = LLM 收到这条规则的 prompt + finding 详情 +
> dataflow trace + 源码窗口，输出 TP / FP / UNKNOWN。
>
> 规则的 prompt 默认由 checker 持有。`plumb-dfa` 里 23 个文件提到 `getAiPrompt`
> （其中 21 个是真正的覆写，另 2 个是 `DfaChecker` 的接口声明和 `DfaEngine` 里的调用），
> 例如 `plumb-dfa/src/main/java/org/plumb/dfa/checkers/...` 下的各 checker；`plumb-style`
> 侧有 3 个覆写：`style/BadShiftCheck.java`、`error/EmptyCatchBlockCheck.java`、
> `security/HardcodedCredentialsCheck.java`。
> 用户的 `--rule-prompts-dir` 仍可 override（`<rule-name>.md` 文件覆盖对应 checker 的 inline `getAiPrompt` 返回值）。

### 2.2 R-? AI 集成

`plumb-ai` 模块提供一个 Re-Act 多轮 LLM Agent（OpenAI 兼容 / Anthropic 都行），
启动 `--ai-dedup` 后给每条 finding 跑一遍。开关都在 YAML 的 `ai.*` 块：

```yaml
ai:
  dedup: false                  # FP reducer（CLI: --ai-dedup）
  dropOnAiFp: false             # false=保留 FP 标记，true=丢掉
  dedupRules: []                # 空=每条规则都过；填了只审这些

  provider: openai              # openai | anthropic | openai_compatible
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}     # ${ENV_VAR} 语法
  model: gpt-4o-mini
  header:                       # 可选的额外请求头
  timeoutSeconds: 30
  maxConcurrent: 4              # 并发 dedup 请求数
  temperature: 0.0              # 0 = 确定性判 FP
```

`ai.dedupRules` 里可以写规则的**两种拼法**，两种都认：

- slug（checker 的 `name()`，也就是规则目录里显示的名字）——`bc-equals-no-null`
- canonical emit id（finding 里带的 `rule_id`）——`ast-bug-bc-equals-no-null`

`--rule-prompts-dir` 里的覆盖文件同理：文件名按 slug 命名即可。

**已被移除的 key**（`ConfigLoader` 会打 warning 并忽略）：

| key | 替代 |
| --- | --- |
| `ai.enabled` | CLI `--ai-dedup` |
| `ai.scan` | 无（AI 只做 FP 判定） |
| `ai.contextLines` | 无（源码窗口在 runner 里硬编码为 3 行） |
| `ai.rulePromptsDir` | CLI `--rule-prompts-dir` |
| `ai.sendAstContext` | 无 |

CLI 简写：

- `--ai-dedup` = `ai.dedup: true`（`SastMain` 只设 `cfg.aiDedup = true`，
  **不存在** `ai.enabled` 这个隐含开关）

**Agent — FP reducer** (`AgentPrompts.FP_REDUCER`)

输入：rule prompt (来自 `DfaChecker.getAiPrompt` / `StyleCheck.getAiPrompt` / `AstChecker.getAiPrompt`) + finding 详情 + 源码窗口 + dataflow trace
输出：`{"verdict":"TP|FP|UNKNOWN","reason":"..."}`
用途：给静态 finding 标 TP/FP，把 FP 丢掉或标记

**Rule prompt 三级覆盖链**：
1. `TaintedCheckerBase` 自动从 `TaintRule` 元数据生成（ruleId / cwe / severity / source / sink / domain）
2. DfaChecker / AstChecker 手写 inline `getAiPrompt`（少数高价值规则）
3. `--rule-prompts-dir` 用户 `.md` 覆盖（最优先）

返回 null = 该规则不参与 AI dedup（提示 `(no-rule-prompt)` 计入 UNKNOWN）。

#### 完整例子

```bash
# 1. 默认（最简）
bin/sast-scan -c conf/default-config.yaml -i src

# 2. AI 降误报（推荐审计用）
OPENAI_API_KEY=sk-... \
bin/sast-scan -c conf/default-config.yaml -i src \
    --ai-dedup

# 3. 只看 SQL 注入
#    --rule 是精确匹配：必须用真实的 rule id（TAINT_SINK_SQL_INJECTION），
#    写 SQL_INJECTION 匹配不到任何规则，扫描会静默产出 0 条 finding。
bin/sast-scan -c conf/default-config.yaml -i src --rule TAINT_SINK_SQL_INJECTION

# 4. 自带 rule prompt 覆盖
bin/sast-scan -c conf/default-config.yaml -i src \
    --ai-dedup --rule-prompts-dir ./my-rule-prompts/
```

### 2.3 R-? Re-Act 多轮工具调用

`--ai-dedup` 默认走 **Re-Act 多轮循环**（不是单次问答）—— LLM 在判 TP/FP 之前可以调
12 个工具去看更多代码、查 caller 有没有 sanitize、检查 trace 路径上有无 guard：

| 工具 | 干啥 | IR 依赖 | 何时用 |
|------|------|--------|--------|
| `read_source` | 读文件 L\<start\>..L\<end\> | ✗ | LLM 想看 ±5 行窗口之外的代码 |
| `grep` | regex-grep 文件 / 目录 | ✗ | 跨文件搜 pattern |
| `glob` | 按文件名 glob 找文件 | ✗ | "找 UserService 的测试文件" |
| `list_dir` | 列目录（深度 0-3）| ✗ | 看包结构、定位 class 位置 |
| `search_pattern` | camelCase 4-variant regex 搜 symbol | ✗ | "constantFoo 在哪定义 / 在哪用？" |
| `find_definitions` | 找 var / method 定义点 | ✓ (Grep fallback) | 追溯 `q = ... + userInput` 找 source |
| `find_callers` | 找方法全部调用点 | ✓ (Grep fallback) | 验证 caller 有没有 sanitize |
| `has_sanitizer_on_path` | 检查 [from..to] 内有无 guard / sanitize | ✓ (Grep fallback) | 一击判定 FP |
| `get_method_annotations` | 读 class/method/param 上 @Annotation | ✓ (regex fallback) | "this finding 在 @PreAuthorize 方法里？" |
| `get_class_hierarchy` | 找 extends / implements | ✗ | "这个 controller 继承 TrustedService 吗？" |
| `get_full_class` | 按 class name 读整个 .java 源 | ✗ | "看 controller 完整 surface" |
| `get_dataflow_trace_with_facts` | 重新格式化 dataflow trace + Z3 facts | ✗ | "trace 路径上 L7 的 BRANCH 是不是 sanitizer？" |

`plumb-ai/.../tools/` 下还有第 13 个工具类 `GetCallHierarchyTool`（递归 walk caller
chain，BFS depth ≤ 4），但它**没有注册进** `ScanRunner.buildToolRegistry()` ——
`--ai-dedup` 的 LLM 拿不到它。

`grep` / `glob` / `list_dir` / `search_pattern` / `get_class_hierarchy` /
`get_full_class` / `get_dataflow_trace_with_facts` **完全不依赖 AST/TAC** —
类似 Claude Code 的 Read/Grep/Glob/Bash。`find_*` 三个有 IR 时走精准的 TAC
call graph，没有 IR 时自动降级到 raw-text regex，**保证工具链不破**。
`read_source` 一直只读磁盘。

Re-Act 一次 finding 大致 1-3 轮 LLM 调用（FP reducer max 5 turn / bug
scanner max 8 turn），框架在 4 个条件之一停：

1. LLM 输出 `FINAL: {...}` — happy path
2. `maxTurns` 超出 — LLM 死循环调 tool，标 `UNKNOWN`
3. provider null — 网络 / auth 失败，标 `UNKNOWN`
4. tool 抛错 — 错误信息回喂 LLM 让它 adapt

**Re-Act 不能从 YAML 关闭。** `ai:` 块里没有 `reactive` 之类的开关——
`ConfigLoader` 只认 `provider` / `baseUrl` / `apiKey` / `model` / `header` /
`timeoutSeconds` / `maxConcurrent` / `temperature` / `dedup` / `dropOnAiFp` /
`dedupRules`。`--ai-dedup` 走的就是 Re-Act 多轮循环，没有单轮模式可切。

**plumb-ai 是 hard dep of plumb-main**（Step 7 之后）：`plumb-main` 直接
`import org.plumb.ai.agent.*` 构造 `LlmProvider` / `ReActAgent` /
`ToolRegistry`，没有 reflection。`plumb-ai` 模块本身只依赖
`plumb-rules` + `plumb-reporter`（不是 `plumb-dfa`），保持
agent 框架壳的干净定位。静态分析本身**不**依赖 plumb-ai；只是
`--ai-dedup` 开关触发它。

**Slim dist** (用 `pwsh scripts/package-slim.ps1` 生成):
- bin / conf / lib 跟 full dist 一样
- `conf/default-config.yaml` 重写为 `ai.dedup: false`（真正的 AI 闸门；`ai.enabled` 早已从 schema 删除）
- 全 jar 包含 plumb-ai classes (off)，但 runner 短路 AI passes
- 想完全剥 plumb-ai classes: 改用 `mvn -pl plumb-main package
  -Pno-ai -DskipTests=true` (mvn profile)，或者直接 fork 仓库
  删除 plumb-ai 模块 + plumb-main 的 `runAiDedup` 逻辑

**真实 LLM E2E**: 设 `PLUMB_AI_E2E=1` env var 跑 `mvn -pl plumb-ai
test` —— `LlmIntegrationTest` 的 5 个测试会真打 LLM 端点（默认
`https://api.minimax.chat` / `MiniMax-Text-01`）。这验证 Re-Act 多轮
tool calling 协议在真实 LLM 上跑通（FP reducer 1-shot + read_source
tool call）。Dev inner loop 不需要这个 — 5 个 LLM test 默认
`@EnabledIfEnvironmentVariable` skip.

**为什么不用 Spring-AI**：`plumb-ai` 用裸 `java.net.http` + Jackson，client 代码 ~50
行，OpenAI function-calling JSON 直接拼。Spring-AI / LangChain4j 多 3MB Spring
依赖 + 调用栈深 6-8 层，调试 print 不到 raw reply。详细实现见
`docs/tech/CROSS-FUNCTION-AI-TRACE.md` §14。

### 2.4 R-? AI dedup 报告（`--ai-dedup-stats`）

`--ai-dedup` 把每条 finding 发给 LLM 判 TP/FP，但 verdict 是埋进 NDJSON
每条记录的 `ai_verdict` 字段里——**没法一眼看 AI 整体表现**。`--ai-dedup-stats`
就是干这个：AI dedup 跑完后，往 **stderr** 打一份 verdict 分布 + 每条规则的
FP 占比 Top 10。stdout 的 NDJSON 仍然干净，可以继续 pipe 给下游。

```bash
bin/sast-scan -c conf/default-config.yaml -i src \
    --ai-dedup --ai-dedup-stats 2>ai-stats.txt >findings.ndjson
```

输出长这样：

```
AI dedup stats
==============
Total findings:   297
Reviewed:         297 (100.0%)
Dropped (FP):     158
Kept:             139
Elapsed:          6m14s

Verdict distribution:
  TP            135  (45.5%)
  FP            158  (53.2%)
  UNKNOWN         4  ( 1.3%)

Top 10 rules by FP ratio (FP / total):
  StaticApiKey                    8/10 (80.0%)
  HardcodedCredential             6/8  (75.0%)
  ...
```

各字段含义：

| 字段 | 含义 |
|------|------|
| `Total findings` | 静态规则扫出来的总 finding 数 |
| `Reviewed` | 实际送到 LLM 判的（一般 = total，除非 dedupRules 白名单过滤） |
| `Dropped (FP)` | 因 `ai.dropOnAiFp: true` 被丢的 FP 数 |
| `Kept` | 进最终报告的 finding 数 |
| `Elapsed` | AI dedup 阶段总耗时（ms / s / m+s） |
| `Verdict distribution` | TP / FP / UNKNOWN 占比 |
| `Top 10 rules by FP ratio` | FP 占比最高的 10 条规则 |

`UNKNOWN` 通常来自：网络超时、模型编不出 JSON、模型编出非 TP/FP 的 verdict
（被规范化为 UNKNOWN）。`UNKNOWN` 高的话看 stderr 末尾 `ai_reason` 字段。

**实现细节**：见 `docs/tech/CROSS-FUNCTION-AI-TRACE.md` §14。`--ai-dedup-stats`
不带 `--ai-dedup` 是 no-op；不带 `--ai-dedup` 又配 `ai.dedup: true` 的 YAML
才有用。

### 2.1 Call-graph 策略（`--cg-strategy`）

传给 `plumb-dfa` 引擎的 call-graph 构造策略。可选值：

| 策略 | 类型 | 速度 | 精度 | 备注 |
| --- | --- | --- | --- | --- |
| `vta` | Variable Type Analysis | 慢 | 跟踪变量赋值类型，比 RTA 精确 | TU-local 策略。适合动态分派多的大项目 |
| `cha` | Class Hierarchy Analysis | **最快** | over-approx（同 simple-name + arity 全收） | 大仓库且不在乎精度的场景；用 `MergedProgram` 合并视图就足够跨 TU |
| `rta` | Rapid Type Analysis | 中 | 比 CHA 精确（剔除非可达 receiver） | 略慢，需要建 receiver type 集合 |
| `1cfa` | 1-CFA context-sensitive | 最慢 | 区分同一方法在不同 call site 的调用 | 适合库代码 / framework 内部分析 |
| `stub` | direct calls only | **最快** | 只看直接调用，忽略虚分派 | 仅 debug / 测试用 |
| `global` | cross-TU signature lookup | 中 | 不依赖 type hierarchy，按 `calleeSig` 直接查 | **默认**。跨文件调用通过 `GlobalTACStore` 解析；`DfaEngine.runWithGlobalStore` 入口 |
| `cached:<name>:<path>` | 任何上面策略的磁盘包装 | — | — | 把策略 `<name>` 的结果序列化到 `<path>`，cache key 是 source-file 集合的 hash。第二次跑同项目直接读盘 |

**YAML 等价**：

```yaml
cgStrategy: rta                # camelCase（推荐）
cg-strategy: vta               # snake_case 也接受
```

校验在 `ScanConfig.validate()` 里做，未知策略名 / 格式错误的 `cached:...` 会在
启动时报 `IllegalArgumentException` 并打印可用值。

实现细节见 `docs/tech/CROSS-FUNCTION-ANALYSIS.md` §2.1"两条路"对比。

## 3. YAML 配置详解

`-c` 指定的 YAML 文件，**与命令行 flag 重叠时命令行优先**（flag 在 ConfigLoader 之后被处理）。完整 schema：

```yaml
# ---- 输入 ----
inputs:                       # 与 --input 列表合并
  - src/main/java
  - /opt/projects/foo/src

input: src/main/java          # 单输入便捷写法，与 inputs 不能同时给

include:                      # 路径白名单（glob 子串，OR 关系）
  - "/com/company/"

exclude:                      # 路径黑名单（glob 子串，AND 关系，优先）
  - "/test/"
  - "/generated/"

# ---- 输出 ----
output:
  path: build/sast.ndjson     # 与 --output 等价
  format: ndjson              # console | ndjson

outputDir: build/plumb-out    # 与 --output-dir 等价
cacheMode: readwrite          # off | read | write | readwrite
maxParallelism: 4             # 并发上限
cgStrategy: rta               # 与 --cg-strategy 等价，见 §2.1 Call-graph 策略

# ---- 规则开关 ----
rules:
  enabled:                    # 强制启用（即便默认是 off）
    - bu-0401-weak-crypto
    - EXTENDS_OBJECT          # 规则 ID 或规则名均可
  disabled:                   # 强制禁用
    - STYLE_SYSTEM_OUT
    - pl-0006-system-out

# ---- 检查器大类 ----
# 两种写法都接受：
#
#   (a) 简单 bool — 适用于只需开关的大类
checks:
  taint: true                 # 33 unified taint 主开关
  style: true                 # 全部 AST 风格规则
  divByZero: true
  # ...
#
#   (b) sub-dict — 当想同时配 aiDedup 或显式 enabled 时用
checks:
  divByZero:           {enabled: true,  aiDedup: true}
  resourceLeak:        {enabled: true,  aiDedup: true}
  nullDeref:           {enabled: true,  aiDedup: true}
  hardcodedCred:       {enabled: true,  aiDedup: true}
  intOverflow:         {enabled: true,  aiDedup: true}
  intUnderflow:        {enabled: true,  aiDedup: true}
  arrayBounds:         {enabled: true,  aiDedup: true}
  weakCrypto:          {enabled: true,  aiDedup: true}
  insecureRandom:      {enabled: true,  aiDedup: true}
  predictableSeed:     {enabled: true,  aiDedup: true}
  predictableKey:      {enabled: true,  aiDedup: true}
  staticApiKey:        {enabled: true,  aiDedup: true}
  insecureComms:       {enabled: true,  aiDedup: true}
  corsMisconfig:       {enabled: true,  aiDedup: true}
  insecureCookie:      {enabled: true,  aiDedup: true}
  badShift:            {enabled: true,  aiDedup: true}
  infiniteLoop:        {enabled: true,  aiDedup: true}
  constantCondition:   {enabled: true,  aiDedup: true}
  sensitiveDataLeak:   {enabled: true,  aiDedup: true}
  fieldHardcodedPassword: {enabled: true, aiDedup: true}
  missingAuthorization:   {enabled: true, aiDedup: true}
  missingInputValidation: {enabled: true, aiDedup: true}
  informationExposure:     {enabled: true, aiDedup: true}
  uncheckedReturn:         {enabled: true, aiDedup: true}
  incorrectReturn:         {enabled: false, aiDedup: true}   # style-ish
  threadRun:               {enabled: false, aiDedup: false}  # style
  stringEquality:           {enabled: true,  aiDedup: true}
  catchGeneric:            {enabled: false, aiDedup: false}  # style
  obsoleteFunction:        {enabled: true,  aiDedup: true}
  tryCatchFinally:         {enabled: true,  aiDedup: false}
  offByOne:                {enabled: true,  aiDedup: true}
  equalsCalledOnNull:      {enabled: true,  aiDedup: true}
  compareWithSelf:         {enabled: true,  aiDedup: true}
  xxe:                     {enabled: true,  aiDedup: true}
  returnFromFinally:       {enabled: true,  aiDedup: false}
  emptyCatchHandler:       {enabled: true,  aiDedup: true}
  iteratorConcurrentModification: {enabled: true, aiDedup: true}
  collectionSelfReference:  {enabled: true,  aiDedup: false}
  uncaughtExceptionPropagator:    {enabled: true,  aiDedup: false}
  uncheckedLoopCondition:   {enabled: true,  aiDedup: true}
  uncontrolledMemAlloc:    {enabled: true,  aiDedup: true}
  numericTruncation:       {enabled: true,  aiDedup: true}
  resourceExhaustion:      {enabled: true,  aiDedup: true}  # CWE-400
  # R-? — 8 plumb-dfa → plumb-dfa DfaChecker ports
  bigDecimalDoubleConstructor: {enabled: true, aiDedup: true}  # CWE-682
  defaultEncoding:             {enabled: true, aiDedup: true}  # CWE-176

# errorMessageInfoLeak / commentInfoLeak 这两个 key 会被 ConfigLoader 解析，
# 但对应的 DFA checker 已经迁到 plumb-style，现在**不再切换任何东西**。
# 要关掉它们请用 AST rule id:
#   rules:
#     disabled:
#       - ast-error-error-message-info-leak
#       - ast-style-comment-info-leak

# ---- 项目路径 ----
# R797/R833 — 跟 build-system adapter 配合使用。详见 §10。
inputs:
  - src/main/java              # 主输入（同 --input，可重复）
sourceRoots:
  - /opt/dependency-sources    # 第三方源码（只喂 TypeSolver，不进 TAC）
moduleRoots:
  - /opt/multi-module/m1       # 同 sourceRoots，UX 语义分开
classpathJars:
  - /opt/lib/spring-core.jar   # 第三方程 jar（TypeSolver only，不进 TAC）

# ---- 构建系统自动发现 ----
# buildSystem:
#   name: maven                 # maven | gradle | bazel | ant
#   projectRoot: /path/to/proj  # 由 BuildAdapter 自动填 inputs/sourceRoots/moduleRoots/classpathJars

# ---- 风格类阈值 ----
# R761 audit (Oct 2026): 删了 `style.methodLengthThreshold`。
# 死字段（无 checker 读它）。后续 checker 级阈值仍会回到 `style:` 块。
```

### 3.1 camelCase vs snake_case

`ConfigLoader` 同时接受 camelCase 和 snake_case 两种 YAML 键，camelCase 优先。这是 R325 兼容性补丁，避免老配置失效。

```yaml
checks:
  intOverflow: true    # 推荐
  int_overflow: true   # 兼容，仍然识别
```

## 4. 启用 / 禁用规则

### 4.1 大类开关（`checks:`）

通过 `checks.<name>: true|false` 一次性启用 / 禁用一整类检查器。例如：

```yaml
checks:
  taint: false         # 关闭所有 17 条 taint 规则
  style: false         # 关闭全部 AST 风格规则
  divByZero: false     # 关闭 DIV_BY_ZERO
```

不写 key 时使用默认值（**几乎全部默认 true**，只有少数 off——见 `ScanConfig` 字段初始化）。

### 4.2 细粒度：`rules.enabled` / `rules.disabled`

`rules.enabled` 把列出的规则**强制置为 on**；`rules.disabled` 强制置为 off。接受**规则 UUID**（如 `bu-0201-style-empty-catch`）或**规则名**（如 `STYLE_EMPTY_CATCH`）：

```yaml
rules:
  disabled:
    - STYLE_SYSTEM_OUT          # 关掉 println 提示
    - pl-0006-system-out        # 同上（两种写法都行）
  enabled:
    - bu-0401-weak-crypto       # 即便大类 off 也强制开
```

实际行为：规则注册阶段（`RuleRegistry.withBuiltinDefaults()` 之后）会重新设置 `enabled` 标志。代码路径在 `ScanRunner.buildRegistry()`：

```java
if (config.disabledRuleNames.contains(def.ruleId().name())
        || config.disabledRuleNames.contains(def.ruleId().id())) {
    enabled = false;
}
```

### 4.3 命令行临时禁用

CLI 没有原生的 `--disable` flag，但可以用一个小 shell alias：

```bash
# 把所有 -D rules.disabled=ID 解析成 YAML 片段并传入
cat > /tmp/disable.yaml <<EOF
rules:
  disabled: [$1]
EOF
java -jar plumb-...-all.jar -c /tmp/disable.yaml -i src/main/java
```

### 4.4 改规则参数

每条 `RuleDef` 可以携带 `RuleParameter`（见 `plumb-rules/src/main/java/org/plumb/rules/RuleParameter.java`）。
**YAML 端运行时参数覆盖已支持**，路径是 `checks.<rule-id>.params.<param-name>`：

```yaml
checks:
  line-length:
    enabled: true
    params:
      max: 120                  # 覆盖源码里 LineLengthCheck 的默认值 100
      ignoreComments: false
```

链路：`ConfigLoader.fromMapImpl` 把它收进 `ScanConfig.ruleParams`
（外层 key = 规则 id，内层 key = 参数名，值按 YAML 字面量类型保留
String / Integer / Boolean），`ScanRunner` 构造 `StyleContext` 时传进去，
`AstDispatcher` 在每个 checker 的 `init()` / `visit()` 前调
`setCurrentRule(checker.name())`，checker 通过
`ctx.paramOverride(name, type)` 读取。命中 override 就用 override，
没命中则回落到 `RuleDef.parameters()` 里声明的 default。

> 外层 key 必须是 checker 的 `name()`，也就是 kebab-case slug（`line-length`）。
> **canonical rule id（`ast-style-line-length`）和短名（`LINE_LENGTH`）都不生效**——
> 查找是 `checker.name() + "." + paramName` 的直接拼键，不做别名解析。

没有命中 override 时，回落到 `RuleDef.parameters()` 里声明的 default。这条回落路径
按 slug 解析规则：每个 checker 的 slug 都在 `RuleRegistry` 里指向**它自己那条规则**，
所以任何 checker 声明的 default 都能通过自己的 slug 读到。slug 与规则名的对应关系由
注册表保证，不依赖每个 checker 手工把 slug 写进自己的 alias 列表。

只有 plumb-style 的 checker 走这条路径——`plumb-rules` 里声明了
`RuleParameter` 但没有 `StyleContext` 上下文的其他规则类型，用的还是
代码里的 hard-code default。

#### 4.4.1 `aggressiveness` 已废弃

`aggressiveness: low|medium|high` 不再被识别。
- CLI 没有 `--aggressiveness` flag（已删除）。
- YAML 里写 `aggressiveness:` 时，`ConfigLoader.fromMapImpl` 会往 stderr 打一条
  `[plumb] warning: 'aggressiveness' in YAML is no longer supported; ...`，
  **但扫描照常继续**——这不是致命错误。
- 替代品：`checks.<rule>: {enabled: true|false}` 段或 `--rule <id>` 命令行临时过滤。

## 5. 输出格式

### 5.1 NDJSON（默认 for `-f ndjson`）

每行一条 JSON finding：

```json
{"file":"src/main/java/Foo.java","line":42,"column":5,"severity":"high",
 "rule_id":"bu-0101-taint-sink-sql","rule_name":"TAINT_SINK_SQL_INJECTION",
 "rule_type":"taint","cwe":["CWE-89"],
 "message":"Tainted id flows into executeQuery (...)",
 "dataflow":"id:String -> [bu-0001-taint-request-param] -> executeQuery/1"}
```

字段含义：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `file` | string | 相对当前工作目录的路径 |
| `line` | int | 1-based 行号（`"-1"` 表示无法定位） |
| `column` | int | 1-based 列号 |
| `severity` | enum | `info`/`low`/`medium`/`high`/`critical` |
| `rule_id` | string | 规则 UUID，例 `bu-0101-taint-sink-sql` |
| `rule_name` | string | 简短名字，例 `TAINT_SINK_SQL_INJECTION` |
| `rule_type` | enum | `taint` / `style` / `naming` / `correctness` / `security` / ... |
| `cwe` | string[] | 相关 CWE 编号 |
| `message` | string | 人类可读说明 |
| `dataflow` | string? | 数据流链（仅 taint / DFA 类规则） |

### 5.2 console

每行格式：

```
src/main/java/Foo.java:42:5  [HIGH] TAINT_SINK_SQL_INJECTION  Tainted id flows into executeQuery (...)  [id:String -> [bu-0001-taint-request-param] -> executeQuery/1]
```

### 5.3 输出目录布局（`--output-dir`）

```
<outputDir>/
├── cache/
│   ├── programs/
│   │   ├── <sha1>.tac           每源文件一个
│   │   └── ...
│   ├── merged.tac               合并后的 MergedProgram（可选）
│   └── callgraph.tac            全局 call graph（可选）
└── report/
    ├── findings.ndjson          实际写入的发现（如果 -f ndjson）
    └── findings.console         人类可读（如果 -f console）
```

详见 `docs/build/PACKAGE.md` §5。

## 6. 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | 扫描成功（**无论是否发现 finding**） |
| `2` | 参数 / 配置错误（用户侧） |
| `3` | 扫描过程中出现未捕获异常（runtime bug） |

注意：Plumb 不提供 `-fail-on-high` 之类的退出码门控——把 findings 接到 CI 决策上交给调用方处理。

## 7. 环境变量

| 变量 | 用途 |
| --- | --- |
| `JAVA_OPTS` | 传给 JVM 的额外参数，例如 `-Xmx4g -Dfile.encoding=UTF-8` |
| `LOG_LEVEL` | Logback 根 logger 级别（覆盖 `conf/logback.xml`） |

启动器自动把 `JAVA_OPTS` 拼到 `java` 命令上。

## 8. 故障排查

| 症状 | 解决 |
| --- | --- |
| `java.lang.OutOfMemoryError: Java heap space` | `JAVA_OPTS=-Xmx4g` |
| finding 全是 `Parse problem` | 检查 Java 源码是否符合 JavaParser 17 语法；javac 17 编译验证 |
| `--output-dir` 报错 `Permission denied` | 目录不可写；用 `chmod` / 选其他目录 |
| `--cache-mode readwrite` 报 `Cache read failure` | 缓存文件 corrupt，删 `<output-dir>/cache/` 重跑 |
| `--max-parallelism 0` 跑得比顺序慢 | 文件数 < 800 是正常的（ForkJoin 调度开销大于收益） |
| `--source-root` 写了，但 finding 不出现 | 预期行为：source-root 只给 TypeSolver，不进 TAC 流水线。要扫的源码放 `--input` |
| `--classpath /path/spring.jar` 后无变化 | 预期行为：classpath 是给 JavaParser 解析类型用的，不是把它当输入扫。要扫 jar 内 class 走 `--input /path/spring.jar` |
| `--build-system maven` 报 "no adapter found" | 检查 `pom.xml` 是否在 `--project-root` 路径下；或换 `--build-system gradle` |
| `findings.ndjson` 里 `ai_verdict: UNKNOWN` 占比高 | 网络/认证/模型出错。看 stderr 的 `ai_reason`；或调大 `ai.timeoutSeconds` |

