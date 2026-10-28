# Plumb

[English](README.md) | **中文**

> **Java 静态分析工具链** — JavaParser → TAC IR → DFA（含 taint）+ AST 风格规则 → NDJSON 报告，**可选** LLM 误报消减。

[![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-blue)](docs/zh/README.md)
[![Tests](https://img.shields.io/badge/tests-262%20java%20test%20files-green)](pom.xml)
[![Support](https://img.shields.io/badge/tests-passing-brightgreen)](testcase/SUPPORT-MATRIX.md)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

独立 Java 项目（NOT a port of Fathom），架构灵感来自 Fathom 但完全 Java-only。

---

## 一句话

```bash
# 解压后，在 dist 目录里：
./bin/plumb -i path/to/your/src/   # 静态扫描
./bin/plumb --ai-dedup -i src/    # 静态 + LLM 误报消减
```

---

## 1. 它能做什么

| 维度 | 数据 | 来源 |
| --- | --- | --- |
| Style checker | **596** 个（`CheckerCategories` 登记 593） | `plumb-style/**` |
| DFA checker | **67** 个实际注册 | `ScanRunner.buildDfaCheckers()` |
| Taint rule | **12** 条 | `TaintRuleRegistry` |
| Taint source / sink 模式 | **19** / **397** | `sink-families.yaml` |
| 单元测试 | **262** 个 java test 文件（其中 219 个 `*Test.java`） | 各模块 `src/test/` |

> 下面的行需要外部语料 / 基准环境才能复现，本仓库里没有对应的数据或 harness：
> Juliet 用例集的 CWE 覆盖率与召回、AI dedup 准确率、启动与单文件扫描耗时、
> 含 JRE 的发布包体积。

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

完整索引见 [`docs/zh/README.md`](docs/zh/README.md)。

---

## 3. 快速开始

### 3.1 从源码构建（开发者）

需要 JDK 17+ 和 Maven 3.8+。

```bash
git clone <plumb-repo>
cd Plumb

# 构建 + 测试
./scripts/build.sh                       # Linux / macOS
./scripts/build.ps1                      # Windows PowerShell

# 产物
ls -lh plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar
```

### 3.2 代码格式

源码用 Google Java Format 风格（2 空格缩进、100 列）。提交前必须格式化：

```bash
./scripts/format.sh                      # Linux / macOS
./scripts/format.ps1                     # Windows PowerShell

./scripts/format.sh --check              # 只检查，不修改（有未格式化文件时退出码 1）
```

格式器版本是钉死的 **google-java-format 1.37.0**。不用 `fmt-maven-plugin`，
因为它捆绑的是 1.7 —— 那个版本读不懂 text block，会报"需要';'"。

仓库自带一个 git pre-commit 钩子，**含有未格式化 Java 的提交会被直接拒绝**。
新克隆的仓库需要装一次：

```bash
sh scripts/install-hooks.sh
```

钩子只拦截**本次提交暂存的文件**。已经存在于 HEAD 里的未格式化文件不会阻塞
后续提交，除非你这次动了它。全量检查一次约 30 秒（1107 个源文件，单次 JVM 运行），
所以"提交前先格式化"不会变成一个你等不起的步骤。

被排除在格式化之外的有两类文件，原因都是它们不是产品源码：

- `plumb-dfa/src/main/resources/scripts/` —— 614 个 Juliet 语料样本，故意包含
  无法解析的文件；重新格式化会破坏用来度量引擎的语料。
- `testcase/` —— 带手工行号锚点的度量样本；重新格式化会移动清单指向的行。

### 3.3 提交前的门禁

仓库里有一组门禁，每个都检查一件会静默腐化的事。**一个没人跑的门禁不是门禁**，
所以它们都由一个命令统一驱动：

```bash
python scripts/check_all.py
```

它依次跑：

| 门禁 | 检查什么 |
| --- | --- |
| `format --check` | 每个源文件都符合 google-java-format |
| `gen_categories --check` | 生成的 checker 注册表是最新的 |
| `gen_framework_annotations --check` | 生成的注解清单是最新的 |
| `check_framework_annotations` | 污点引擎和 AST 校验检查器对"什么是用户输入"的理解一致 |
| `check_catalogue_drift_numbers` | javadoc 里引用的历史漂移数字与 git 历史对得上 |
| `check_dataflow_constraint` | 清单的 `dataflow_contains` 约束不是空断言 |
| `check_dataflow_claims` | 数据流文档的行引用仍指向它声称的声明位置，它引用的计数和类名仍与源码一致 |
| `mutate_dataflow_claims` | 数据流门禁不是空检查：每一种可能的漂移都能被抓到 |
| `check_dfaaug_claims` | DFA 增强设计文档仍在描述代码真实拥有的机制：规则 id、校验器、代码片段用到的 API、那些从未被构造的 `Operation` 变体 |
| `mutate_dfaaug_claims` | 设计文档门禁不是空检查：每一种可能的漂移都能被抓到 |
| `check_ruleauthoring_claims` | 规则开发文档仍在教读者怎么加一条**能用**的规则：它点名的文件都存在、它给的命令都跑得起来、它说的计数和源码一致、它引用的别名就是源码声明的那几个 |
| `mutate_ruleauthoring_claims` | 规则开发门禁不是空检查：每一种可能的漂移都能被抓到 |
| `check_license` | 每个 Java 源文件都带 license 头，`LICENSE` 文件和头里写的是同一件事，而且生成器仍然会产出这个头 |
| `mutate_license` | license 门禁不是空检查：每一种可能的漂移都能被抓到 |
| `check_support_matrix_coverage` | 支持矩阵覆盖了每个已测量的类别，且每行的数字和 `RESULT.json` 对得上 |
| `check_javadoc_links` | javadoc 里 `{@link}` 指向的类型真的存在 |
| `check_doc_index` | 文档索引列全了 `docs/` 下的每一份文档，索引里的链接也都能打开 |
| `check_doc_hygiene` | 产品文档描述的是**当前**状态：没有轮次号、没有过去式措辞、没有无法复现的测量当事实 —— markdown、源码注释、随包发布的配置文件都查 |
| `check_doc_numbers` | 手写文档里引用的数字仍然和源码对得上 |
| `check_doc_claims` | 文档的结构性断言仍然和源码对得上：YAML schema 里出现的每个 key 都是 loader 真读的，CLI flag 都存在，章节编号不重复、交叉引用不悬空 |
| `check_tac_claims` | IR 规范里的结构性断言仍然和源码对得上：读者照着写的那些类名、字段名、枚举常量、计数都还在 |
| `check_cross_fn_claims` | 跨函数 Operation 文档里点名的符号都还存在：类名、`Operation` 变体、return shape |
| `check_environment_claims` | 环境变量文档跟代码对得上：哪些变量真的被读、哪些没被读（启动脚本读的也算）、行号引用指的是不是那一行 |
| `check_cache_claims` | 缓存文档跟代码对得上：文件头常量、四种缓存模式及其行为、source map 保留契约里字段的类型 |
| `check_style_rules_claims` | 风格规则文档跟代码对得上：接口方法、派发器 API、上下文 API、visit 重载数 |
| `check_i18n_docs` | 中英文档树结构一致，每一页都链得到它的对照版本 |
| `check_readme_claims` | 根 README 跟代码对得上：它引用的数字、它引用的体积，以及体积有没有写明是哪个产物的 |
| `check_section_refs` | 跨文档的章节引用都指向真实存在的章节 |
| `check_package_claims` | 出包脚本拷的每个文件都真的存在（少一个就中断整个出包），出包文档里的体积和日志级别跟构建产物对得上 |
| `capability_baseline --check` | 没有丢掉任何规则、sink 模式、污点源模式、checker 或事实种类 |
| `gen_tool_comparison --check` | 工具对比文档里的数字仍然和源码对得上 |
| `gen_rules_overview --check` | 规则总览里的数字仍然和源码对得上 |
| `linkcheck` | markdown 里的相对链接没有断链 |

跑完还会重新生成 `docs/plumb-style-rules*.md`，并报告生成结果和已提交版本是否一致。

**关于"手写文档里的数字"**：生成的文档（`RULES.md`、`RULE-TOOL-COMPARISON.md`）的数字
由生成器实测，`--check` 模式能证明它们没过期。但**手写**文档里的数字没人管 —— 仓库里
同时写着"595 个 checker 类"和"596 个"就会和实际值对不上，而实际是 596；`ARCHITECTURE.md` 写着
"71 个 DFA checker"，而引擎实际注册 67 个。`check_doc_numbers` 把这些数字对着源码
重新测一遍，包括 `RULE-STYLE-OP.md` 里那张按子目录分类的表。数字要么改成实测值，
要么搬进生成文档里让生成器负责 —— 不能靠记忆。

**关于"注释里的数字"**：注释里引用一个数字，这个数字就会腐烂 —— 仓库里同时写着
"thirteen" 和 "seventeen"，描述的是同一个漂移，两个数就都对不上了。`check_catalogue_drift_numbers`
从 git 历史里读出漂移前的两份清单，算出真实的数字，再和注释里写的对账；注释写错就报错。
历史不可用（浅克隆）时它会说明原因并放行，而不是假装通过。

**关于"注释里的类名"**：同一件事也发生在类名上。仓库里曾经有一整套 javadoc 描述一个
叫 `CallResolver` 的组件，说它"沿类层次解析虚调用" —— 而这个类在仓库里根本不存在，
实际的虚调用解析是**按方法名 + 参数个数**匹配的（见 `ChaCallGraphStrategy`）。
`check_javadoc_links` 用 Maven 给出的真实依赖 classpath 来解析每一个 `{@link}`：
`org.plumb.*` 必须在源码里能找到，裸名字必须在源码、JDK 或依赖 jar 里能找到。
找不到就报错 —— 注释里指向一个不存在的类，是对代码的假陈述。

**关于"能力没有变弱"**：类别矩阵只覆盖了少数规则，所以"少了一条规则"这件事
矩阵未必看得出来。`capability_baseline` 直接测量能力面本身：已注册的规则、
已注册的 checker、checker 类、污点源模式、事实种类、sink 家族及其模式。
任何一项减少都会被拒绝；增加会被报告出来，并且需要**显式**更新基线才算接受。
这样"顺手清理一下"就不可能悄悄变成"少支持一类问题"。

事实种类有两个来源，门禁**分开**测量（`builtin:` / `yaml:` 前缀）：`BuiltinFactKinds`
里注册的 15 个内置 kind，和 `fact-kinds.yaml` 里的 12 个条目。两者命名风格不同
（`dfa-taint-untrusted-source` vs `UntrustedSource`），但 `ConfigLoader.parseFactKinds`
会把它们合进同一个 registry —— 所以合起来是 27 个。分开测量是为了防止其中一个
清单把另一个遮住：有一版测量用一个永远匹配不到的 pattern 去数内置 kind，
于是那 15 个**根本不在基线里**，删掉一个也不会被发现。

### 3.4 最小扫描

```bash
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -i path/to/your/src/

# 输出到 NDJSON 文件
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -i path/to/your/src/ \
  -o findings.ndjson
```

### 3.5 用 YAML 配置

```bash
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -c conf/default-config.yaml \
  -i path/to/your/src/
```

`default-config.yaml` 含 50 个 `checks:` 键（48 个 checker family + `taint` / `style` 两个总开关）、
AI 配置、调用图策略、缓存模式。

### 3.6 启用 AI 误报消减

```bash
# 设置 API key（环境变量 + 配置文件双通道，详见 docs/zh/usage/ENVIRONMENT.md）
export MINIMAX_API_KEY=sk-...

# 跑扫描 + AI dedup
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar \
  -c conf/default-config.yaml \
  -i path/to/your/src/ \
  --ai-dedup
```

NDJSON 输出每行多两个字段：`ai_verdict`（TP/FP/UNKNOWN）+ `ai_reason`。

### 3.7 解压即用（生产）

下载 `plumb-0.1.0-SNAPSHOT-jre-linux-x64.tar.gz` 或 `-windows-x64.zip`，解压后：

```bash
cd plumb-0.1.0-SNAPSHOT-jre
./bin/plumb -i /path/to/scan          # Linux / macOS
bin\plumb.bat -i C:\path\to\scan     # Windows
```

**不需要安装 Java**。已捆绑最小 JRE（jlink 优化后）。

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
| `-i, --input <path>` | 输入文件/目录（可重复） |
| `-o, --output <path>` | 输出文件（默认 stdout） |
| `-D, --output-dir <dir>` | 输出目录（含 cache/、report/） |
| `-f, --format <console\|ndjson>` | 输出格式 |
| `--cache-mode <off\|read\|write\|readwrite>` | 磁盘 IR 缓存模式 |
| `--max-parallelism <n>` | 并行度上限（0 = auto） |
| `--cg-strategy <cha\|rta\|vta\|1cfa\|stub\|global\|cached:...>` | 调用图策略（默认 `global`） |
| `--ai-dedup` | 启用 LLM 误报消减 |
| `--ai-dedup-stats` | 打印 AI 统计（TP/FP/UNKNOWN 分布） |
| `--rule <id>` | 只跑特定规则（可重复） |
| `--rule-prompts-dir <path>` | 覆盖内置 rule prompts |
| `--list-rules` | 列出所有规则 |
| `-h, --help` | 帮助 |
| `-v, --version` | 版本 |

完整文档见 [`docs/zh/README.md`](docs/zh/README.md) 与 [`docs/zh/usage/CLI.md`](docs/zh/usage/CLI.md)。

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
| 文档总索引 | [`docs/zh/README.md`](docs/zh/README.md) |
| 规则清单 | [`docs/zh/rules/RULES.md`](docs/zh/rules/RULES.md) |
| 精确率 / 召回率实测 | [`testcase/SUPPORT-MATRIX.md`](testcase/SUPPORT-MATRIX.md) |
| 环境变量 + AI 配置 | [`docs/zh/usage/ENVIRONMENT.md`](docs/zh/usage/ENVIRONMENT.md) |
| 完整 CLI + YAML | [`docs/zh/usage/CLI.md`](docs/zh/usage/CLI.md) |
| 打包 / 启动 | [`docs/zh/build/PACKAGE.md`](docs/zh/build/PACKAGE.md) |
| 项目架构 + 关键决策 | [`docs/zh/tech/ARCHITECTURE.md`](docs/zh/tech/ARCHITECTURE.md) |
| TAC IR 格式 | [`docs/zh/tech/TAC.md`](docs/zh/tech/TAC.md) |
| **数据流分析（污点 / 边界 / 指向 / 控制流）** | [`docs/zh/tech/DATA-FLOW.md`](docs/zh/tech/DATA-FLOW.md) |
| 风格规则实现 | [`docs/zh/tech/STYLE-RULES.md`](docs/zh/tech/STYLE-RULES.md) |
| 缓存机制 | [`docs/zh/tech/CACHE.md`](docs/zh/tech/CACHE.md) |
| 跨函数分析 | [`docs/zh/tech/CROSS-FUNCTION-ANALYSIS.md`](docs/zh/tech/CROSS-FUNCTION-ANALYSIS.md) |

每份文档都有英文版，索引见 [`docs/README.md`](docs/README.md)。

### 6.1 两种语言

产品文档有两种语言：

| 目录树 | 语言 | 定位 |
| --- | --- | --- |
| [`docs/`](docs/zh/README.md) | 英文 | **权威源。** 所有数字、类名引用、行为断言都从这里出发 |
| [`docs/zh/`](docs/zh/README.md) | 中文 | 同步的对照版本，文件名完全一致 |

每一页顶部都有一行语言切换：`[English](...) | [中文](...)`。这些链接是相对的，
所以从本地文件系统、`file://` URL、或发布包里的一份拷贝打开时，切换都能正常工作 ——
不需要服务器，不需要构建步骤，不需要装任何东西。

两棵树由 `check_i18n_docs` 保持一致：它要求文档集合相同、标题结构相同、表格行数相同、
代码块数量相同，所有相对链接都能解析，并且每页都链得到它的对照版本。
`mutate_i18n_docs` 证明这个门禁不是空转：十二种刻意的漂移，每一种都必须让它失败。

有两个例外：自动生成的规则目录（`plumb-style-rules.md` 与
`plumb-style-rules-index.md`）。它们由 `scripts/gen_rules_doc.py` 和
`scripts/gen_rules_index.py` 从源码树生成，翻译一份会在每次生成时产生漂移，所以只保留英文。

新增一份文档就要在两棵树里各加一份。少一份，门禁就失败。

---

## 7. 打包（生产 / CI）

### 7.1 uber-jar（无 JRE，需要用户装 Java 17+）

```bash
./scripts/build.sh
# → plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar
#   uber-jar：内联所有依赖，约 142 MB
```

uber-jar 之所以大，是因为它内联了 Z3 的各平台 native library 和整个 Kotlin 脚本引擎。
文档目录完全是另一回事：整个 `docs/` 目录树（中英文加起来）约 1.1 MB。

### 7.2 dist 目录（含启动器，无 JRE）

```bash
./scripts/package.sh
# → dist/plumb-0.1.0-SNAPSHOT/
#     bin/sast-scan(.bat)
#     lib/plumb-*.jar
#     conf/default-config.yaml
#     conf/logback.xml
#     README.txt
```

### 7.3 解压即用（含 JRE）

需要本地 JDK 17+ + `jlink`（JDK 自带）。

```bash
./scripts/dist-jlink-linux.sh             # 写好后
# → dist-jlink/plumb-0.1.0-SNAPSHOT-jre-linux-x64.tar.gz
#   含 jlink 最小 JRE + shaded jar + 启动器
# 用户机器无需任何 Java 环境，解压即跑
```

Windows：

```powershell
.\scripts\dist-jlink-windows.ps1
# → dist-jlink\plumb-0.1.0-SNAPSHOT-jre-windows-x64.zip
```

---

## 8. 测试

```bash
# 单元测试（R 系列集成测试默认跳过，约 5 min）
mvn -pl plumb-dfa test -Dtest='!R*'

# 全部测试（含 R* 集成，需要 Juliet fixtures，约 10 min）
mvn test

# 单个模块
mvn -pl plumb-style test
mvn -pl plumb-ai test
```

---

## 9. License

MIT —— 见 [`LICENSE`](LICENSE)。

每个 Java 源文件都带这个头，`check_license` 门禁负责强制：头必须是文件的
**第一样东西**，必须和 `LICENSE` 文件写的是同一个版权人和同一个年份，
而且生成器也必须产出这个头——否则下一次重新生成就会悄悄把它弄掉。

```bash
python scripts/add_license_headers.py            # 给还没有头的源文件补上
python scripts/add_license_headers.py --check    # 报告哪些源文件还没有头
```

头的文本只有一个出处：`scripts/license_header.py`。补头的脚本、门禁和生成器
都从那里读，所以这三者不可能对"头长什么样"有分歧。

---

## 10. 维护

| 维度 | 状态 |
| --- | --- |
| 上游 | 独立项目 |
| 维护者 | Plumb team |
| 已知限制 | 见各 category 的 `testcase/*/EXPECTATIONS.json` 与 [`testcase/SUPPORT-MATRIX.md`](testcase/SUPPORT-MATRIX.md) |

路线图与已知限制集中在 `docs/zh/tech/ARCHITECTURE.md` 与 `testcase/SUPPORT-MATRIX.md`。
