# Environment Variables and AI Configuration

[English](../README.md) | [中文](../zh/usage/ENVIRONMENT.md)

Plumb reads **very few** environment variables. Across the whole codebase
there are exactly two real reads, both in
`plumb-main/.../ConfigLoader.java`:

```java
String apiKey = System.getenv("OPENAI_API_KEY");        // :466  default value
...
String envName = s.substring(2, s.length() - 1);
apiKey = System.getenv(envName);                          // :504  "${VAR}" expansion
```

Every conclusion in this document was checked against the code.

---

## 1. The environment variables

### 1.1 The ones that are read

| Variable | Read at | Purpose |
| --- | --- | --- |
| `OPENAI_API_KEY` | `ConfigLoader.java:466` | Default for `ai.apiKey` when the YAML does not set it |
| Any `${VARNAME}` in the YAML | `ConfigLoader.java:504` | Explicit reference, e.g. `ai.apiKey: ${MY_KEY}` |

Those two are the only ones. `ai.apiKey: ${MINIMAX_API_KEY}` works through
the second line -- the engine does not know the name "MiniMax"; it sees
`${...}` and reads that variable.

### 1.2 The ones that are not read (common misconceptions)

The following variables have **zero references** in the **Java code**:

| Variable | Status | Explanation |
| --- | --- | --- |
| `PLUMB_HOME` | ❌ 未读取 (not read) | There is no `bin/plumb` launcher in this repository. The launcher passes the path as the `-Dplumb.home=` system property, not as an environment variable |
| `PLUMB_CACHE_DIR` | ❌ 未读取 (not read) | The cache directory is determined by `--output-dir` |
| `MINIMAX_API_KEY` | Test only | Read by `LlmIntegrationTest.java:144`; the main code does not read it |
| `ANTHROPIC_API_KEY` | ❌ 未读取 (not read) | To use Claude, set `apiKey: ${ANTHROPIC_API_KEY}` in the YAML |
| `http_proxy` / `https_proxy` | Indirect | The JDK `HttpClient` reads the standard proxy variables by default; Plumb does not implement this itself |

**But the packaging scripts read two of them** -- they are not Java code,
but they ship with the package and users really do set them:

| Variable | Read at | Purpose |
| --- | --- | --- |
| `PLUMB_JAVA_OPTS` | `scripts/dist-jlink-linux.sh` / `scripts/dist-jlink-windows.ps1` | Passed through to the JVM; defaults to `-Xmx4g -XX:+UseG1GC` |
| `PLUMB_DEBUG` | `scripts/dist-jlink-linux.sh` / `scripts/dist-jlink-windows.ps1` | When set to `true`, adds `-Droot.level=DEBUG` to the launcher |

Neither works when you run the uber-jar directly with `java -jar` -- the JVM
arguments are then decided by whoever starts `java`.

**The AI feature runs without any environment variable at all** -- set
nothing, pass no `--ai-dedup`, and you get static analysis only.

---

## 2. AI configuration

### 2.1 The defaults

`plumb-main/src/main/resources/default-config.yaml:182-193`:

```yaml
ai:
  dedup: false                      # FP reducer (CLI: --ai-dedup)
  dropOnAiFp: false                 # Drop findings judged FP instead of marking ai_verdict=FP
  dedupRules: []                    # Empty = every rule; non-empty = only these
  provider: openai                  # openai | openai_compatible | anthropic
  baseUrl: https://api.openai.com
  apiKey: ${OPENAI_API_KEY}         # Read from the environment at startup
  model: gpt-4o-mini                # Whatever your endpoint accepts
  header:                           # Optional extra request headers
  timeoutSeconds: 30
  maxConcurrent: 4                  # Concurrent dedup requests
  temperature: 0.0                  # Deterministic verdicts
```

### 2.2 The provider values

`LlmConnection.Provider` (`plumb-ai/.../LlmConnection.java:147`) has
exactly three values:

| Value | Explanation |
| --- | --- |
| `OPENAI` | OpenAI's own `/v1/chat/completions` |
| `OPENAI_COMPATIBLE` | **Identical wire protocol**. Use this for Azure / Ollama / LocalAI / vLLM / MiniMax. `openai` is an alias; both take the same code path (`ConfigLoader.java:483-485`) |
| `ANTHROPIC` | Anthropic's native `/v1/messages`; single prompt only; tool-calling throws `UnsupportedOperationException` |

### 2.3 Pointing at MiniMax

```yaml
ai:
  provider: openai_compatible
  baseUrl: https://api.minimax.chat
  apiKey: ${MINIMAX_API_KEY}
  model: MiniMax-M3
```

### 2.4 Where the API key comes from (highest priority first)

1. A literal `ai.apiKey: sk-...` in the YAML -- **not recommended**, it ends up in version control
2. An environment variable expanded from `ai.apiKey: ${SOME_VAR}` (`ConfigLoader.java:502-504`)
3. When the YAML does not set `ai.apiKey` at all, `ConfigLoader.java:466` falls back to `OPENAI_API_KEY`
4. Neither -> the AI connection is unavailable; without `--ai-dedup` this does not affect static analysis at all

---

## 3. CI examples

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

### 3.2 Fully offline (no AI)

Set no variables, pass no `--ai-dedup`:

```bash
java -jar plumb-main-...-all.jar -c conf/default-config.yaml -i src/ -o build/findings.ndjson
```

The NDJSON output then contains no `ai_verdict` / `ai_reason` fields.

### 3.3 Memory on a large repository

Plumb reads no JVM-argument variable. On a large repository, pass the
argument on the `java` command itself:

```bash
java -Xmx8g -jar plumb-main-...-all.jar -i large-repo/ -o out/
```

---

## 4. What Plumb does not do

1. **It does not write to the user's home directory**: there is no `~/.plumbrc` / `~/.config/plumb/`. Configuration comes only from the file given by `--config` and the `default-config.yaml` on the classpath.
2. **It does not write to the registry or system configuration directories** (Windows registry, macOS `~/Library/Application Support`, Linux `~/.config`).
3. **The API key does not reach the NDJSON**: the provider does not print the key.
4. **The cache directory can be deleted at any time**: everything under `<outputDir>/cache/` is a rebuildable artifact.

---

## 5. Troubleshooting

### No `ai_verdict` in the output

1. `ai.dedup: false` is the default -- you have to turn it on explicitly (in the YAML or with `--ai-dedup`).
2. `ai.apiKey` resolved to `null`: check that the variable name inside `${VAR}` actually exists. When `${...}` cannot be resolved you get **`null` silently**, with no error.
3. The endpoint is unreachable: use curl to reach `/v1/chat/completions` first, then debug the proxy.

```bash
curl -X POST "$BASE_URL/v1/chat/completions" \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}]}'
```

### `<think>` leaking from a reasoning model

Reasoning models (MiniMax-M3, Claude with thinking, and so on) may leave a
`<think>...</think>` block in `ai_reason`.
`OpenAiCompatibleLlmProvider.stripThinkBlocks(...)` removes it; if you
still see one, the NDJSON was produced by an older version -- rerun it.

---

## 6. Maintenance

When you add an environment variable, update all of these:

1. Section 1 of this document
2. The corresponding `System.getenv(...)` in `ConfigLoader.java`
3. If it is AI-related, the `ai:` block in `default-config.yaml`

The reverse check: across the whole repository there are exactly two real reads of
the environment, and both of them are the two in `ConfigLoader.java`.

```bash
grep -rn 'System\.getenv(' --include=*.java plumb-*/src/main
```

That command returns a dozen lines, but only two are **real reads** -- the
`OPENAI_API_KEY` read and the `${VAR}` expansion in `ConfigLoader.java`.
Every other hit is a **checker searching user code for the string**
(`EnvVarCheck` / `EnvVarInfoExposureCheck` and similar rules look for the
*call shape* `System.getenv`; they do not read the environment themselves).
The way to tell them apart is to look at whether the hit is an actual call:
`System.getenv(` followed by an argument and used as a value is a read; a
hit inside a string literal or a javadoc is a rule pattern.
