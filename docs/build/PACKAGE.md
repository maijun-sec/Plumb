# Packaging and Release

[English](../README.md) | [中文](../zh/build/PACKAGE.md)

This document describes how Plumb is built into a releasable package, what the directory looks like, and how the target machine starts it.

> This document describes **3 release forms** at the same time:
> 1. **Development build** -- `scripts/build.sh` / `scripts/build.ps1` produce the single file `plumb-main-<ver>-all.jar`
> 2. **Slim release package** -- `scripts/package.sh` produces the bin/lib/conf directories and requires a JRE 17+ on the target machine
> 3. **Ready-to-unpack package** -- `scripts/dist-jlink-windows.ps1` / `scripts/dist-jlink-linux.sh` use jlink to bundle a minimal JRE, so **the target machine needs no Java environment at all**
>
> In addition, `scripts/package-slim.ps1` rewrites `default-config.yaml` on top of the slim release package to turn the AI switch off for intranet / audit scenarios.
>
> **There is no native Windows `package.ps1`** -- `package.sh` is a bash script and runs on Windows through
> git-bash / WSL; the pure PowerShell Windows packaging paths are
> `scripts/dist-jlink-windows.ps1` and `scripts/package-slim.ps1`.

## 1. Packaging commands at a glance

| Form | Command | Artifact |
| --- | --- | --- |
| Single-file jar | `pwsh scripts/build.ps1 --skip-tests` | `plumb-main/target/plumb-main-<ver>-all.jar` |
| Slim release package | `bash scripts/package.sh` | `dist/plumb-<ver>/{bin,lib,conf}/` |
| AI-gate-closed slim | `pwsh scripts/package-slim.ps1` | `dist/plumb-<ver>-slim/{bin,lib,conf}/` |
| Ready-to-unpack (Linux x64 / arm64) | `bash scripts/dist-jlink-linux.sh --arch x64` | `dist-jlink/plumb-<ver>-jre-linux-x64.tar.gz` |
| Ready-to-unpack (Windows x64) | `pwsh scripts/dist-jlink-windows.ps1 -Arch x64` | `dist-jlink/plumb-<ver>-jre-windows-x64.zip` |

All the build scripts accept `--skip-build` / `-SkipBuild` to skip Maven and repackage the release based only on the already-built jar -- this is common on CI:

```bash
./scripts/build.sh --skip-tests
./scripts/package.sh
./scripts/dist-jlink-linux.sh
```

## 2. The complete directory layout (slim release package)

The standard layout produced by `scripts/package.sh`:

```
plumb-<ver>/
├── README.txt                  quick start (cmd/bin + conf section description)
├── bin/
│   ├── sast-scan               POSIX launcher (bash)
│   └── sast-scan.bat           Windows cmd launcher
├── lib/
│   └── plumb-main-<ver>-all.jar     single-file uber-jar (≈ 142 MB)
└── conf/
    ├── default-config.yaml     baseline YAML (details in §6)
    └── logback.xml             default logging (root = ERROR, `org.plumb` = DEBUG)
```

> **Note**: the slim release package does **not** package the `testcase/` tree (the test fixtures), to avoid the jar and the fixtures taking up the same space twice. The baseline `conf/default-config.yaml` is the minimum configuration needed to start a real scan; the user feeds their own source code in with `-i <path>`.

### 2.1 The contents of the slim release package

| Path | Size (typical) | Description |
| --- | ---: | --- |
| `bin/sast-scan` | ~300 B | POSIX shell launcher. `exec java -cp $DIR/lib/* -Dlogback.configurationFile=$DIR/conf/logback.xml org.plumb.main.SastMain "$@"` |
| `bin/sast-scan.bat` | ~250 B | Windows cmd launcher. The logic is the same as the POSIX version; it resolves the path via `%~dp0` |
| `lib/plumb-main-<ver>-all.jar` | ~142 MB | The uber-jar after shading: all plumb-* modules, JavaParser, SootUp, Z3 turnkey, Logback, the Kotlin scripting engine |
| `conf/default-config.yaml` | ~9 KB | The baseline with all fields: inputs/output/outputDir/cacheMode/cgStrategy/checks/ai (details in §6) |
| `conf/logback.xml` | ~200 B | Default root = `ERROR`, `org.plumb` / `org.plumb.ai` = `DEBUG`; can be overridden with `-Dlogback.configurationFile=...` |
| `README.txt` | ~200 B | The startup command + the description of the default conf sections |

## 3. The ready-to-unpack package (jlink + launcher)

`scripts/dist-jlink-{windows,linux}.sh` is meant for real deployment scenarios. The script:

1. Runs `mvn -pl plumb-main -am package -DskipTests` to get the uber-jar (can be skipped with `--skip-build` / `-SkipBuild`).
2. Uses the `jlink` that comes with JDK 17+ to cut a **minimal JRE** whose module set is:

   ```
   java.base, java.logging, java.net.http, java.compiler,
   java.instrument, java.management, java.naming, java.security.jgss,
   java.sql, java.xml, jdk.crypto.ec, jdk.httpserver,
   jdk.unsupported   # SootUp reflection dependency
   jdk.zipfs         # z3 turnkey
   ```

3. Assembles the dist directory + writes the launcher + packs the zip/tar.gz.

### 3.1 Directory layout

```
plumb-<ver>-jre-{windows-x64|linux-x64}/
├── README.md                  # from templates/README-{win,linux}.md.template
├── CHANGELOG.md               # from templates/CHANGELOG.template
├── LICENSE                    # the MIT terms the code is shipped under
├── bin/
│   ├── plumb                 # POSIX launcher (bash)
│   └── plumb.bat             # Windows cmd launcher
├── jre/                       # jlink minimal JRE 17 (~95 MB)
├── lib/
│   └── plumb-main-<ver>-all.jar
├── conf/
│   ├── default-config.yaml
│   └── logback.xml
└── docs/
    ├── README.md                (the repository root README)
    ├── plumb-style-rules.md     the rule catalogue
    ├── RULES.md                 the rule overview
    ├── ENVIRONMENT.md           environment variables and AI configuration
    ├── CLI.md                   the CLI manual
    ├── CLI-REFERENCE.md
    └── CLI-EXAMPLES.md
```

> The packaging script collects docs/ with a "copy only if the file exists" approach: when a document is renamed or deleted,
> the packaging **does not** fail because of it; it simply copies one file less.

### 3.2 The launcher logic (plumb / plumb.bat)

Both launchers follow a **three-level fallback** to find Java:

| Order | POSIX (`bin/plumb`) | Windows (`bin/plumb.bat`) |
| --- | --- | --- |
| 1 | `$DIR/jre/bin/java` (bundled JRE) | `%DIR%\jre\bin\java.exe` |
| 2 | `${JAVA_HOME}/bin/java` | `%JAVA_HOME%\bin\java.exe` |
| 3 | `$(command -v java)` | `where java` → `java.exe` |

If none of the three is found → report `ERROR: no java found` and exit with code 1.

The JVM arguments are read from the environment variable `PLUMB_JAVA_OPTS`, default:

```
-Xmx4g -XX:+UseG1GC
```

When `PLUMB_DEBUG=true` is set, `-Droot.level=DEBUG` is added, which pushes the Logback root logger to DEBUG.

The launcher calls:

```bash
exec "$JAVA" $JVM_OPTS \
  -Dplumb.home="$DIR" \
  -Dlogback.configurationFile="$DIR/conf/logback.xml" \
  -cp "$DIR/lib/*" \
  org.plumb.main.SastMain "$@"
```

`-Dplumb.home` tells the application where the installation root is, which makes the relative paths of `conf/` and `logback.xml` easy to resolve.

### 3.3 Installation and startup example (ready-to-unpack)

**Windows**:

```cmd
:: unpack (right-click the zip -> Extract All...)
cd plumb-0.1.0-SNAPSHOT-jre-windows-x64

:: show the help
bin\plumb.bat --help

:: scan your code
bin\plumb.bat -i C:\path\to\your\src\

:: write the NDJSON output to a file
bin\plumb.bat -i C:\path\to\your\src\ -o findings.ndjson

:: enable AI false-positive reduction (optional, needs the OPENAI_API_KEY environment variable)
set OPENAI_API_KEY=sk-...
bin\plumb.bat -c conf\default-config.yaml -i C:\path\to\your\src\ --ai-dedup
```

**Linux / macOS**:

```bash
tar -xzf plumb-0.1.0-SNAPSHOT-jre-linux-x64.tar.gz
cd plumb-0.1.0-SNAPSHOT-jre-linux-x64

./bin/plumb --help
./bin/plumb -i /path/to/your/src/
./bin/plumb -i /path/to/your/src/ -o findings.ndjson

# AI false-positive reduction
export OPENAI_API_KEY=sk-...
./bin/plumb -c conf/default-config.yaml -i /path/to/your/src/ --ai-dedup
```

**Calling the Windows version from PowerShell**:

```powershell
$env:OPENAI_API_KEY = "sk-..."
& ".\bin\plumb.bat" -i "C:\path\to\your\src\" --ai-dedup
```

## 4. The slim distribution (AI off)

`pwsh scripts/package-slim.ps1` **rewrites** `conf/default-config.yaml` on top of the slim release package and flips `ai.dedup` to `false`:

```
dist/plumb-0.1.0-SNAPSHOT-slim/
├── README-SLIM.txt        explains how this package differs
├── bin/sast-scan(.bat)
├── lib/plumb-main-...-all.jar   # still contains the plumb-ai classes, only the gate is off
└── conf/default-config.yaml     # ai.dedup: false
```

> **Note that what is turned off is `ai.dedup`, not `ai.enabled`.** The `ai.enabled` key was removed from
> the configuration schema long ago; `ConfigLoader` only prints a warning for it and ignores it --
> writing it is the same as not turning the AI off at all. The real AI switch is `ai.dedup` (the CLI's
> `--ai-dedup` also sets `cfg.aiDedup = true`).

The applicable scenarios:

- CI / production scans, where an LLM round-trip is not worth it
- Offline / air-gapped network environments
- Wanting statically reproducible scan results (not depending on LLM verdicts)

To strip the plumb-ai classes completely: `plumb-ai` is a **hard dependency** of `plumb-main`
(`plumb-main/pom.xml` depends on it directly and `ScanConfig` directly news up
`org.plumb.ai.agent.LlmConnection`), so you **cannot** simply delete files from `lib/` --
you have to rebuild an uber-jar that does not shade
`plumb-ai` with `mvn -pl plumb-main package -DskipTests=true`. There is **no** reflection guard in the code; a missing class
results in a direct `NoClassDefFoundError`.

## 5. Cross-platform notes

- The POSIX launcher has a `chmod +x` step (`package.sh` / `dist-jlink-linux.sh` add it automatically). The Windows packaging does not need it.
- The Windows bat file still works when the `PATH` contains spaces (`%DIR%` is already quoted).
- The jlink script needs a JDK **with the `jmods/` directory**. The Temurin JRE package does not ship jmods, so install a Temurin JDK or an OpenJDK reference build.
- PowerShell 5.1 decodes UTF-8 as ANSI (CP936) by default → a direct `Get-Content | Set-Content` corrupts CJK. `dist-jlink-windows.ps1` avoids this problem by using external UTF-8 templates (`scripts/templates/*.template`).
- 8 GB of memory is the comfortable zone for a single-process scan. For large projects with 10k+ files, `PLUMB_JAVA_OPTS=-Xmx8g` or more is recommended.

## 6. `conf/default-config.yaml` in detail

The `default-config.yaml` shipped with the package is a baseline with **every field commented out** (the user enables what it needs):

```yaml
# ---- output ----
# output:
#   path: build/sast.ndjson
#   format: ndjson               # console | ndjson | sarif

# ---- on-disk IR cache ----
# outputDir: build/plumb-out
# cacheMode: readwrite           # off | read | write | readwrite

maxParallelism: 4                # parse+translate concurrency; 0 = nCPU-1

# the default is global (not vta) -- required for cross-TU taint analysis
cgStrategy: global               # global | vta | cha | rta | 1cfa | stub | cached:<...>

# ---- rule switches ----
# rules:
#   enabled:                     # force on (even when the default is off)
#     - bu-0401-weak-crypto
#   disabled:                    # force off
#     - STYLE_SYSTEM_OUT

# ---- family switches (each one defaults to true) ----
checks:
  taint: true
  style: true
  divByZero:           {enabled: true, aiDedup: true}
  # ... 47 entries in total ...

# ---- AI augmentation ----
ai:
  dedup: false                    # FP reducer (CLI: --ai-dedup)
  dropOnAiFp: false
  dedupRules: []                  # empty = every rule goes through
  provider: openai
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}
  model: gpt-4o-mini
  timeoutSeconds: 30
  maxConcurrent: 4
  temperature: 0.0
```

> For the complete list of keys and the meaning of every field see `docs/usage/CLI.md` §3.

## 7. The 4 smoke tests (run on every packaging)

```bash
# (a) the launcher prints the help (POSIX)
./dist/plumb-<ver>/bin/sast-scan --help

# (b) scan with the configuration shipped in the package
./dist/plumb-<ver>/bin/sast-scan -c dist/plumb-<ver>/conf/default-config.yaml -i path/to/test/src

# (c) NDJSON output
./dist/plumb-<ver>/bin/sast-scan -i path/to/test/src -o /tmp/findings.ndjson -f ndjson

# (d) the Windows launcher
dist\plumb-<ver>\bin\sast-scan.bat --help
```

Expected:

- (a)/(d) exit code 0, printing the usage + all the flags.
- (b)/(c) exit code 0, the findings on stdout, the NDJSON parses correctly.
- The ready-to-unpack version has one more item: (e) remove the system JAVA: `unset JAVA_HOME`, then run `bin/plumb --help`; it should still work with the `bundled jre/`.

## 8. The difference from the development-mode build artifacts

| Source | Path | Content |
| --- | --- | --- |
| Maven `package` | `plumb-main/target/plumb-main-<ver>-all.jar` | Single-file jar |
| `scripts/package.sh` | `dist/plumb-<ver>/` | bin + lib + conf (needs a system JRE 17+) |
| `scripts/package-slim.ps1` | `dist/plumb-<ver>-slim/` | The same, but with `ai.dedup: false` (the real AI gate) |
| `scripts/dist-jlink-linux.sh` | `dist-jlink/plumb-<ver>-jre-linux-x64.tar.gz` | The above + jre/ + docs/ + a bundled JRE |
| `scripts/dist-jlink-windows.ps1` | `dist-jlink/plumb-<ver>-jre-windows-x64.zip` | The same (zip) |

A release package needs **only a JRE** on the end user's machine (the ready-to-unpack package needs nothing at all). A development-mode build is used only inside the repository.

## 9. The upgrade procedure

```bash
# pull the new code -> rebuild -> repackage the release
git pull
./scripts/build.sh --skip-tests       # or pwsh scripts/build.ps1 --skip-tests
./scripts/package.sh                  # slim release package
./scripts/dist-jlink-linux.sh         # ready-to-unpack package

# the old versions stay in dist/ / dist-jlink/, so they can be kept for a while for rollback
```

## 10. Verification and signing (optional)

Before the release you can use `gpg` to sign the jar:

```bash
gpg --armor --detach-sign --output dist/plumb-<ver>/lib/plumb-main-<ver>-all.jar.asc \
    dist/plumb-<ver>/lib/plumb-main-<ver>-all.jar
```

and package the `.asc` verification file together with it. On the CI side it is verified with `gpg --verify`.

## 11. Size / module manifest (reference values)

| Component | Size |
| --- | ---: |
| `plumb-main-<ver>-all.jar` (shaded uber-jar) | ~142 MB |
| jlink minimal JRE 17 (14 modules included) | ~95 MB |
| Ready-to-unpack tar.gz (Linux x64) | ~80 MB (after gzip compression) |
| Ready-to-unpack zip (Windows x64) | ~180 MB |

The zip is larger because the compression ratio of the PowerShell `Compress-Archive` is worse than that of `tar -czf`; if the size matters on CI, you can run an extra `7z a -t7z -mx=9` repack.
