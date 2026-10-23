# Plumb

> **Java 静态分析工具链** — JavaParser → TAC IR → DFA (含 taint) + AST 风格规则 → NDJSON 报告,**可选** LLM FP 消减。

[![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)](Plumb-OVERVIEW.md)
[![Tests](https://img.shields.io/badge/tests-~320%20files-green)](pom.xml)
[![Checkers](https://img.shields.io/badge/checkers-626-orange)](docs/CHECKERS.md)
[![License](https://img.shields.io/badge/license-internal-lightgrey)](LICENSE)

独立 Java 项目 (NOT a port of Fathom),架构灵感来自 Fathom 但完全 Java-only。

---

## 一句话

```bash
# 解压后,在 dist 目录里:
./bin/plumb -i path/to/your/src/   # 静态扫描
./bin/plumb --ai-dedup -i src/     # 静态 + LLM FP 消减
```

---

## 1. 它能做什么

| 维度 | 数据 |
| --- | --- |
| 检查器总数 | **626** (515 AST + 111 DFA/taint) |
| CWE 家族覆盖 | **112/112** Juliet Test Suite v1.3 |
| 单元测试 | **~320** java test files |
| 召回 (raw, Juliet) | **100%** |
| AI dedup 准确率 (M3) | **99.96%** in-scope (R683, 9 CWE sample) |
| 启动 | ~1.5 s |
| 单文件扫描 | ~30 ms (中位) |
| 产物 (uber-jar) | 76 MB |
| 产物 (含 JRE) | ~180 MB |

---

## 2. 模块架构

```
                ┌────────────────────────┐
                │   plumb-parser         │  → AST
                └──────┬─────────────────┘
                       │
       ┌───────────────┼───────────────────┐
       │               │                   │
┌──────▼─────┐ ┌───────▼────────┐ ┌───────▼────────┐
│ plumb-tac  │ │ plumb-style    │                 │
└──────┬─────┘ └───────┬────────┘                 │
       │               │                          │
       │       ┌───────▼────────┐                 │
       │       │ plumb-rules    │                 │
       │       └───────┬────────┘                 │
       │               │                          │
┌──────▼───────────────▼──────┐  ┌──────────────┐ │
│      plumb-dfa             │  │ plumb-ai     │ │
└──────┬─────────────────────┘  └──────┬───────┘ │
       │                                │         │
       │       ┌────────────────────────▼─────┐   │
       │       │  plumb-reporter               │   │
       │       └────────────┬─────────────────┘   │
       │                    │                     │
       └────────┬───────────┘                     │
                │                                 │
       ┌────────▼────────┐                        │
       │   plumb-main    │  ← CLI                │
       └─────────────────┘                        │
```

详见 [`Plumb-OVERVIEW.md`](Plumb-OVERVIEW.md)。

---

## 3. 快速开始

### 3.1 从源码构建 (开发者)

需要 JDK 17+ 和 Maven 3.8+。

```bash
git clone <plumb-repo>
cd Plumb

# 构建 + 测试
./scripts/build.sh                       # Linux / macOS
./scripts/build.ps1                      # Windows PowerShell

# 产物
ls -lh plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar
# ~76 MB
```

### 3.2 最小扫描

```bash
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -i path/to/your/src/

# 输出到 NDJSON 文件
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -i path/to/your/src/ \
  -o findings.ndjson
```

### 3.3 用 YAML 配置

```bash
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -c conf/default-config.yaml \
  -i path/to/your/src/
```

`default-config.yaml` 含 41 个规则家族的开关、AI 配置、调用图策略、缓存模式。

### 3.4 启用 AI FP 消减

```bash
# 设置 API key (环境变量 + 配置文件双通道,详见 docs/ENVIRONMENT.md)
export MINIMAX_API_KEY=sk-...

# 跑扫描 + AI dedup
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -c conf/default-config.yaml \
  -i path/to/your/src/ \
  --ai-dedup
```

NDJSON 输出每行多两个字段:`ai_verdict` (TP/FP/UNKNOWN) + `ai_reason`。

### 3.5 解压即用 (生产)

下载 `plumb-0.1.0-SNAPSHOT-jre-linux-x64.tar.gz` (~180 MB) 或 `-windows-x64.zip`,解压后:

```bash
cd plumb-0.1.0-SNAPSHOT-jre
./bin/plumb -i /path/to/scan          # Linux / macOS
bin\plumb.bat -i C:\path\to\scan     # Windows
```

**不需要安装 Java**。已捆绑最小 JRE (~95 MB,jlink 优化后)。

---

## 4. 完整命令

```bash
java -jar plumb-...-all.jar --help
```

```
usage: sast-scan [-c <yaml>] [--cache-mode <off|read|write|readwrite>] [-D <dir>]
                 [-f <console|ndjson>] [-h] [-i <path>] [--max-parallelism <n>]
                 [-o <path>] [-v] [--ai-dedup] [--cg-strategy <name>] [--rule <id>] ...
```

| Flag | 含义 |
| --- | --- |
| `-c, --config <yaml>` | 加载 YAML 配置 |
| `-i, --input <path>` | 输入文件/目录 (可重复) |
| `-o, --output <path>` | 输出文件 (默认 stdout) |
| `-D, --output-dir <dir>` | 输出目录 (含 cache/, report/) |
| `-f, --format <console\|ndjson>` | 输出格式 |
| `--cache-mode <off\|read\|write\|readwrite>` | 磁盘 IR 缓存模式 |
| `--max-parallelism <n>` | 并行度上限 (0 = auto) |
| `--cg-strategy <cha\|rta\|vta\|1cfa\|stub\|global\|cached:...>` | 调用图策略 (默认 `global`) |
| `--ai-dedup` | 启用 LLM FP 消减 |
| `--ai-dedup-stats` | 打印 AI 统计 (TP/FP/UNKNOWN 分布) |
| `--rule <id>` | 只跑特定规则 (可重复) |
| `--rule-prompts-dir <path>` | 覆盖内置 rule prompts |
| `--list-rules` | 列出所有规则 |
| `-h, --help` | 帮助 |
| `-v, --version` | 版本 |

完整文档:[`doc/usage/CLI.md`](doc/usage/CLI.md)。

---

## 5. NDJSON 输出示例

```json
{"file":".../VulnerableServlet.java","line":"29","severity":"high",
 "rule_id":"bu-0101-taint-sink-sql","rule_name":"TAINT_SINK_SQL_INJECTION",
 "cwe":["CWE-89"],"message":"Tainted id flows into executeQuery (...)"}

{"file":".../LoginController.java","line":"42","severity":"medium",
 "rule_id":"pl-0204-null-deref","rule_name":"NULL_DEREF",
 "cwe":["CWE-476"],"message":"request.getParameter(...) may be null",
 "ai_verdict":"TP","ai_reason":"…"}
```

---

## 6. 规则 / CWE / 文档索引

| 想了解… | 看 |
| --- | --- |
| 626 个检查器清单 | [`docs/CHECKERS.md`](docs/CHECKERS.md) |
| 项目架构 + 关键决策 | [`Plumb-OVERVIEW.md`](Plumb-OVERVIEW.md) |
| 环境变量 + API key | [`docs/ENVIRONMENT.md`](docs/ENVIRONMENT.md) |
| 完整 CLI + YAML | [`doc/usage/CLI.md`](doc/usage/CLI.md) |
| 打包 / 启动 | [`doc/build/PACKAGE.md`](doc/build/PACKAGE.md) |
| TAC IR 格式 | [`doc/tech/TAC.md`](doc/tech/TAC.md) |
| 数据流算法 | [`doc/tech/DATA-FLOW.md`](doc/tech/DATA-FLOW.md) |
| 风格规则实现 | [`doc/tech/STYLE-RULES.md`](doc/tech/STYLE-RULES.md) |
| 缓存机制 | [`doc/tech/CACHE.md`](doc/tech/CACHE.md) |
| 跨函数分析 | [`doc/tech/CROSS-FUNCTION-ANALYSIS.md`](doc/tech/CROSS-FUNCTION-ANALYSIS.md) |
| 历史 R-round | `docs/R*.md` (R1-R355) + agent memory (R356+) |

---

## 7. 打包 (生产 / CI)

### 7.1 uber-jar (无 JRE,需要用户装 Java 17+)

```bash
./scripts/build.sh
# → plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar
#   ~76 MB,含所有依赖
```

### 7.2 dist 目录 (含启动器,无 JRE)

```bash
./scripts/package.sh
# → dist/plumb-0.1.0-SNAPSHOT/
#     bin/sast-scan(.bat)
#     lib/plumb-*.jar
#     conf/default-config.yaml
#     conf/logback.xml
#     README.txt
```

### 7.3 解压即用 (含 JRE,~180 MB)

需要本地 JDK 17+ + `jlink` (JDK 自带)。

```bash
./scripts/dist-jlink-linux.sh             # 写好后
# → dist-jlink/plumb-0.1.0-SNAPSHOT-jre-linux-x64.tar.gz
#   ~180 MB,含 jlink 最小 JRE + shaded jar + 启动器
# 用户机器无需任何 Java 环境,解压即跑
```

Windows:
```powershell
.\scripts\dist-jlink-windows.ps1
# → dist-jlink\plumb-0.1.0-SNAPSHOT-jre-windows-x64.zip
```

---

## 8. 测试

```bash
# 单元测试 (R 系列集成测试默认跳过,只需 5 min)
mvn -pl plumb-dfa test -Dtest='!R*'

# 全部测试 (含 R* 集成,需要 Juliet fixtures, ~10 min)
mvn test

# 单个模块
mvn -pl plumb-style test
mvn -pl plumb-ai test
```

---

## 9. License

Internal / 内部使用。具体 license 询问项目维护者。

---

## 10. 维护

| 维度 | 状态 |
| --- | --- |
| 上游 | 独立项目 |
| 维护者 | Plumb team |
| 当前 round | **R684** (收尾 wrap-up) |
| 上次 release | R683 (2026-08-23) |
| 已知限制 | 见 `Plumb-OVERVIEW.md` § 14 |

R685+ 路线图见 `Plumb-OVERVIEW.md` § 16。
