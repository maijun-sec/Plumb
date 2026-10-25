# 出包与发布

本文档说明 Plumb 怎么打成可发布包、目录长什么样、目标机怎么拉起运行。

> 本文档同时描述 **3 种发布形态**：
> 1. **开发构建** — `scripts/build.sh` / `scripts/build.ps1` 出单文件 `plumb-main-<ver>-all.jar`
> 2. **瘦发布包** — `scripts/package.sh` 出 bin/lib/conf 目录，需要目标机自带 JRE 17+
> 3. **解压即用包** — `scripts/dist-jlink-windows.ps1` / `scripts/dist-jlink-linux.sh` 用 jlink 自带最小 JRE，**目标机无需任何 Java 环境**
>
> 此外 `scripts/package-slim.ps1` 在瘦发布包基础上重写 `default-config.yaml`，把 AI 开关关掉给内网/审计场景。
>
> **没有 Windows 原生的 `package.ps1`** —— `package.sh` 是 bash 脚本，Windows 上
> 通过 git-bash / WSL 运行；纯 PowerShell 的 Windows 出包路径是
> `scripts/dist-jlink-windows.ps1` 和 `scripts/package-slim.ps1`。

## 1. 出包命令速查

| 形态 | 命令 | 产物 |
| --- | --- | --- |
| 单文件 jar | `pwsh scripts/build.ps1 --skip-tests` | `plumb-main/target/plumb-main-<ver>-all.jar` |
| 瘦发布包 | `bash scripts/package.sh` | `dist/plumb-<ver>/{bin,lib,conf}/` |
| AI-gate-closed slim | `pwsh scripts/package-slim.ps1` | `dist/plumb-<ver>-slim/{bin,lib,conf}/` |
| 解压即用 (Linux x64 / arm64) | `bash scripts/dist-jlink-linux.sh --arch x64` | `dist-jlink/plumb-<ver>-jre-linux-x64.tar.gz` |
| 解压即用 (Windows x64) | `pwsh scripts/dist-jlink-windows.ps1 -Arch x64` | `dist-jlink/plumb-<ver>-jre-windows-x64.zip` |

构建脚本都接受 `--skip-build` / `-SkipBuild` 跳过 Maven，仅基于已生成的 jar 重打发布包——CI 上常这么用：

```bash
./scripts/build.sh --skip-tests
./scripts/package.sh
./scripts/dist-jlink-linux.sh
```

## 2. 完整目录布局（瘦发布包）

`scripts/package.sh` 输出的标准布局：

```
plumb-<ver>/
├── README.txt                  快速上手（cmd/bin + conf 段说明）
├── bin/
│   ├── sast-scan               POSIX 启动器（bash）
│   └── sast-scan.bat           Windows cmd 启动器
├── lib/
│   └── plumb-main-<ver>-all.jar     单文件 uber-jar（≈ 115 MB）
└── conf/
    ├── default-config.yaml     baseline YAML（详 §6）
    └── logback.xml             默认日志（INFO 级别）
```

> **注意**：瘦发布包**不打包** `testcase/` 树（测试 fixture），避免 jar 与 fixtures 重复占空间。baseline `conf/default-config.yaml` 是足够拉起一个真实扫描所需的最小配置；用户用 `-i <path>` 把自己的源码喂进来即可。

### 2.1 瘦发布包内容

| 路径 | 大小（典型） | 说明 |
| --- | ---: | --- |
| `bin/sast-scan` | ~300 B | POSIX shell 启动器。`exec java -cp $DIR/lib/* -Dlogback.configurationFile=$DIR/conf/logback.xml org.plumb.main.SastMain "$@"` |
| `bin/sast-scan.bat` | ~250 B | Windows cmd 启动器。逻辑同 POSIX 版，靠 `%~dp0` 解路径 |
| `lib/plumb-main-<ver>-all.jar` | ~115 MB | shade 后 uber-jar：plumb-* 全模块、JavaParser、SootUp、Z3 turnkey、Logback |
| `conf/default-config.yaml` | ~9 KB | 全字段 baseline：inputs/output/outputDir/cacheMode/cgStrategy/checks/ai（详 §6） |
| `conf/logback.xml` | ~200 B | 默认 INFO 级别；可用 `-Dlogback.configurationFile=...` 覆盖 |
| `README.txt` | ~200 B | 启动命令 + 默认 conf 段说明 |

## 3. 解压即用包（jlink + 启动器）

`scripts/dist-jlink-{windows,linux}.sh` 是给真实部署场景用的。脚本会：

1. 跑 `mvn -pl plumb-main -am package -DskipTests` 拿 uber-jar（可用 `--skip-build` / `-SkipBuild` 跳过）。
2. 用 JDK 17+ 自带的 `jlink` 切一个**最小 JRE**，模块集是：

   ```
   java.base, java.logging, java.net.http, java.compiler,
   java.instrument, java.management, java.naming, java.security.jgss,
   java.sql, java.xml, jdk.crypto.ec, jdk.httpserver,
   jdk.unsupported   # SootUp 反射依赖
   jdk.zipfs         # z3 turnkey
   ```

3. 拼装 dist 目录 + 写启动器 + 打 zip/tar.gz。

### 3.1 目录布局

```
plumb-<ver>-jre-{windows-x64|linux-x64}/
├── README.md                  # 来自 templates/README-{win,linux}.md.template
├── CHANGELOG.md               # 来自 templates/CHANGELOG.template
├── bin/
│   ├── plumb                 # POSIX 启动器（bash）
│   └── plumb.bat             # Windows cmd 启动器
├── jre/                       # jlink 最小 JRE 17 (~95 MB)
├── lib/
│   └── plumb-main-<ver>-all.jar
├── conf/
│   ├── default-config.yaml
│   └── logback.xml
└── docs/
    ├── Plumb-OVERVIEW.md
    ├── CHECKERS.md
    ├── ENVIRONMENT.md
    └── CLI.md
```

### 3.2 启动器逻辑（plumb / plumb.bat）

启动器都遵循**三级 fallback** 找 Java：

| 顺序 | POSIX (`bin/plumb`) | Windows (`bin/plumb.bat`) |
| --- | --- | --- |
| 1 | `$DIR/jre/bin/java`（bundled JRE） | `%DIR%\jre\bin\java.exe` |
| 2 | `${JAVA_HOME}/bin/java` | `%JAVA_HOME%\bin\java.exe` |
| 3 | `$(command -v java)` | `where java` → `java.exe` |

三处都找不到 → 报 `ERROR: no java found` 退出码 1。

JVM 参数从环境变量 `PLUMB_JAVA_OPTS` 读，默认：

```
-Xmx4g -XX:+UseG1GC
```

设 `PLUMB_DEBUG=true` 时再加 `-Droot.level=DEBUG`，把 Logback 根 logger 推到 DEBUG。

启动器调：

```bash
exec "$JAVA" $JVM_OPTS \
  -Dplumb.home="$DIR" \
  -Dlogback.configurationFile="$DIR/conf/logback.xml" \
  -cp "$DIR/lib/*" \
  org.plumb.main.SastMain "$@"
```

`-Dplumb.home` 让应用知道安装根，方便 `conf/`、`logback.xml` 的相对路径解析。

### 3.3 安装与拉起示例（解压即用）

**Windows**：

```cmd
:: 解压（右键 zip -> Extract All...）
cd plumb-0.1.0-SNAPSHOT-jre-windows-x64

:: 看帮助
bin\plumb.bat --help

:: 扫你的代码
bin\plumb.bat -i C:\path\to\your\src\

:: NDJSON 输出到文件
bin\plumb.bat -i C:\path\to\your\src\ -o findings.ndjson

:: 启用 AI FP 消减（可选，需 OPENAI_API_KEY 环境变量）
set OPENAI_API_KEY=sk-...
bin\plumb.bat -c conf\default-config.yaml -i C:\path\to\your\src\ --ai-dedup
```

**Linux / macOS**：

```bash
tar -xzf plumb-0.1.0-SNAPSHOT-jre-linux-x64.tar.gz
cd plumb-0.1.0-SNAPSHOT-jre-linux-x64

./bin/plumb --help
./bin/plumb -i /path/to/your/src/
./bin/plumb -i /path/to/your/src/ -o findings.ndjson

# AI 降误报
export OPENAI_API_KEY=sk-...
./bin/plumb -c conf/default-config.yaml -i /path/to/your/src/ --ai-dedup
```

**PowerShell 调用 Windows 版**：

```powershell
$env:OPENAI_API_KEY = "sk-..."
& ".\bin\plumb.bat" -i "C:\path\to\your\src\" --ai-dedup
```

## 4. slim 发行（AI 关闭）

`pwsh scripts/package-slim.ps1` 在瘦发布包基础上**重写** `conf/default-config.yaml`，把 `ai.dedup` 翻成 `false`：

```
dist/plumb-0.1.0-SNAPSHOT-slim/
├── README-SLIM.txt        解释本包差异
├── bin/sast-scan(.bat)
├── lib/plumb-main-...-all.jar   # 仍含 plumb-ai 类，只是 gate 关着
└── conf/default-config.yaml     # ai.dedup: false
```

> **注意关的是 `ai.dedup`，不是 `ai.enabled`。** `ai.enabled` 这个 key 早已从
> 配置 schema 里移除，`ConfigLoader` 遇到它只会打一条 warning 并忽略 ——
> 写它等于没关 AI。真正的 AI 开关是 `ai.dedup`（CLI 的 `--ai-dedup` 也会
> 设 `cfg.aiDedup = true`）。

适用场景：

- CI / 生产扫描，LLM round-trip 不划算
- 离线 / 隔离网络环境
- 想静态可复现的扫描结果（不依赖 LLM 判定）

想彻底剥掉 plumb-ai classes：`plumb-ai` 是 `plumb-main` 的**硬依赖**
（`plumb-main/pom.xml` 直接依赖它，`ScanConfig` 直接 new
`org.plumb.ai.agent.LlmConnection`），所以**不能**只从 `lib/` 删文件 ——
必须用 `mvn -pl plumb-main package -DskipTests=true` 重新打一个不 shade
`plumb-ai` 的 uber-jar。代码里**没有** reflection guard；类找不到会直接
`NoClassDefFoundError`。

## 5. 跨平台注意

- POSIX 启动器有 `chmod +x` 步骤（`package.sh` / `dist-jlink-linux.sh` 自动加）。Windows 打包不需要。
- Windows bat 文件在 `PATH` 含空格时仍能工作（`%DIR%` 已加引号）。
- jlink 脚本需要 JDK **带 `jmods/` 目录**。Temurin 的 JRE 装包不带 jmods，要装 Temurin JDK 或者 OpenJDK reference builds。
- PowerShell 5.1 默认按 ANSI（CP936）解码 UTF-8 → 直接 `Get-Content | Set-Content` 会破坏 CJK。`dist-jlink-windows.ps1` 用外置 UTF-8 模板（`scripts/templates/*.template`）规避这个问题。
- 8 GB 内存是单进程扫描的舒适区。10k+ 文件的大项目建议 `PLUMB_JAVA_OPTS=-Xmx8g` 或更大。

## 6. `conf/default-config.yaml` 详解

随包附的 `default-config.yaml` 是**全部字段都注释掉**的 baseline（用户按需启用）：

```yaml
# ---- output ----
# output:
#   path: build/sast.ndjson
#   format: ndjson               # console | ndjson | sarif

# ---- on-disk IR cache ----
# outputDir: build/plumb-out
# cacheMode: readwrite           # off | read | write | readwrite

maxParallelism: 4                # parse+translate 并发；0 = nCPU-1

# 默认值是 global（不是 vta）—— 跨 TU taint 分析必需
cgStrategy: global               # global | vta | cha | rta | 1cfa | stub | cached:<...>

# ---- 规则开关 ----
# rules:
#   enabled:                     # 强制 on（即便默认是 off）
#     - bu-0401-weak-crypto
#   disabled:                    # 强制 off
#     - STYLE_SYSTEM_OUT

# ---- 大类开关（每条默认 true） ----
checks:
  taint: true
  style: true
  divByZero:           {enabled: true, aiDedup: true}
  # ... 一共 46 项 ...

# ---- AI augmentation ----
ai:
  dedup: false                    # FP reducer (CLI: --ai-dedup)
  dropOnAiFp: false
  dedupRules: []                  # 空 = 每条规则都过
  provider: openai
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}
  model: gpt-4o-mini
  timeoutSeconds: 30
  maxConcurrent: 4
  temperature: 0.0
```

> 完整的 keys 与每个字段语义见 `docs/usage/CLI.md` §3。

## 7. 4 个 smoke test（每次出包必跑）

```bash
# (a) 启动器打印帮助（POSIX）
./dist/plumb-<ver>/bin/sast-scan --help

# (b) 扫描随包配置
./dist/plumb-<ver>/bin/sast-scan -c dist/plumb-<ver>/conf/default-config.yaml -i path/to/test/src

# (c) NDJSON 输出
./dist/plumb-<ver>/bin/sast-scan -i path/to/test/src -o /tmp/findings.ndjson -f ndjson

# (d) Windows 启动器
dist\plumb-<ver>\bin\sast-scan.bat --help
```

期望：

- (a)/(d) 退出码 0，打印 usage + 全部 flag。
- (b)/(c) 退出码 0，stdout 输出 findings，NDJSON 解析合法。
- 解压即用版再加 1 项：(e) 移除系统 JAVA：`unset JAVA_HOME`，再跑 `bin/plumb --help`，应当仍能用 `bundled jre/`。

## 8. 与开发模式构建产物的区别

| 来源 | 路径 | 内容 |
| --- | --- | --- |
| Maven `package` | `plumb-main/target/plumb-main-<ver>-all.jar` | 单文件 jar |
| `scripts/package.sh` | `dist/plumb-<ver>/` | bin + lib + conf（需系统 JRE 17+） |
| `scripts/package-slim.ps1` | `dist/plumb-<ver>-slim/` | 同上，但 `ai.enabled: false` |
| `scripts/dist-jlink-linux.sh` | `dist-jlink/plumb-<ver>-jre-linux-x64.tar.gz` | 上者 + jre/ + docs/ + 自带 JRE |
| `scripts/dist-jlink-windows.ps1` | `dist-jlink/plumb-<ver>-jre-windows-x64.zip` | 同上（zip） |

发布包在最终用户机器上**只需要 JRE**（或解压即用包完全不需要）。开发模式构建只在仓库内部使用。

## 9. 升级流程

```bash
# 拉新代码 → 重新构建 → 重新打发布包
git pull
./scripts/build.sh --skip-tests       # 或 pwsh scripts/build.ps1 --skip-tests
./scripts/package.sh                  # 瘦发布包
./scripts/dist-jlink-linux.sh         # 解压即用包

# 旧版本保留在 dist/ / dist-jlink/，可保留一段时间以便回滚
```

## 10. 校验与签名（可选）

发布前可使用 `gpg` 对 jar 签名：

```bash
gpg --armor --detach-sign --output dist/plumb-<ver>/lib/plumb-main-<ver>-all.jar.asc \
    dist/plumb-<ver>/lib/plumb-main-<ver>-all.jar
```

并把 `.asc` 校验文件一起打包。CI 端用 `gpg --verify` 校验。

## 11. 体积 / 模块清单（参考值）

| 组件 | 大小 |
| --- | ---: |
| `plumb-main-<ver>-all.jar` (shaded uber-jar) | ~115 MB |
| jlink 最小 JRE 17（含 14 个 module） | ~95 MB |
| 解压即用 tar.gz (Linux x64) | ~80 MB（gzip 压缩后） |
| 解压即用 zip (Windows x64) | ~180 MB |

zip 偏大是因为 PowerShell 的 `Compress-Archive` 压缩率不如 `tar -czf`；CI 上若对体积敏感，可以额外跑一遍 `7z a -t7z -mx=9` 重打包。