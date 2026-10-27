# CLI Quick Reference: Project Path Family / build-system / YAML v2

[English](../README.md) | [中文](../zh/usage/CLI-REFERENCE.md)

This document is the quick reference: the project path family, build-system
auto-discovery, and the YAML v2 schema. For the complete CLI documentation
see `CLI.md`; for typical scenarios and the default configuration reference
see `CLI-EXAMPLES.md`.

## 10. Project path family (`--source-root` / `--module-root` / `--classpath`)

(Oct 2026) introduced three flags that exist **solely to feed the JavaParser
TypeSolver**. The fundamental difference from `--input` is:

| Dimension | `--input` | `--source-root` / `--module-root` / `--classpath` |
| --- | --- | --- |
| Fed into `cache/programs/` | Yes | No |
| Enters the TAC pipeline | Yes | No |
| Produces findings | Yes | No |
| Fed into the JavaParser `TypeSolver` | No (syntax layer only) | Yes |
| Purpose | Scan code | Resolve symbols so that the code under `--input` can reference third-party types |

**Typical usage -- Spring Boot project**:

```bash
# own sources + third-party jars / dependency sources
bin/sast-scan \
    --input       src/main/java \
    --classpath   ~/.m2/repository/org/springframework/spring-core/6.1.0/spring-core-6.1.0.jar \
    --source-root ~/.m2/repository/org/springframework/spring-core/6.1.0/src \
    -o findings.ndjson
```

This way Spring annotation resolution works normally (`@Autowired`,
`@RequestMapping` and so on), but the 3000-plus classes inside
`spring-core.jar` are not scanned into findings.

**Why they must be kept separate**: if third-party jars also went through the
TAC pipeline, the IR cache of a 10k-plus file project would blow up, peak
memory would double, and scan time would multiply by 3 or more. Keep
"symbol resolution" and "scanning" strictly decoupled:

> **Cardinal rule (cardinal)**: a `--classpath` path is **never** translated
> into TAC IR. The only reason it exists is to let the user sources under
> `--input` resolve references to external types.

**Multi-module projects**:

```bash
bin/sast-scan \
    --input        m1/src/main/java,m2/src/main/java \
    --module-root  m1,m2 \
    --classpath    $(find ~/.m2/repository -name '*.jar' | tr '\n' ',')
```

`--source-root` and `--module-root` are **equivalent**; they are split apart
only for UX reasons, so that multi-module projects can list them per module.

## 11. Build system auto-discovery (`--build-system` / `--project-root`)

This automates the tedious "feed `--source-root` / `--classpath` manually"
step described above. Given a project root, the BuildAdapter probes for:

| Adapter | Detected by | Output |
| --- | --- | --- |
| `maven-resolver` | `pom.xml` | Same as `maven`, but `classpathJars` is computed by Maven Resolver as the **complete transitive dependency tree** (including version conflict resolution) |
| `maven` | `pom.xml` | `sourceFiles` (resolved from `<sourceDirectory>` / `<testSourceDirectory>`), `classpathJars` (all dependencies under `~/.m2`), `modules` (the reactor) |
| `gradle` | `build.gradle(.kts)` | Similar to Maven |
| `bazel` | `BUILD` file | Resolves `java_library` / `java_binary` rules |
| `ant` | `build.xml` | Resolves `<javac>` tasks |

**Auto-detection**: when only `--project-root` is given,
`BuildSystemRegistry.detect(projectRoot)` tries them in order
`maven-resolver` -> `maven` -> `gradle` -> `bazel` -> `ant`.
`maven-resolver` comes before `maven` because the transitive dependency tree
it computes is more accurate; `maven` is the fallback because it has no
external dependencies and therefore does not fail when the local repository is
broken.

**Explicit selection**:

```bash
bin/sast-scan --project-root /opt/my-spring-app --build-system maven
```

For the complete transitive dependencies:

```bash
bin/sast-scan --project-root /opt/my-spring-app --build-system maven-resolver
```

Output to stderr:

```
Build adapter 'maven' discovered: 1247 sources, 89 classpath jars, 3 modules.
```

Everything the adapter outputs -- `sourceFiles` / `sourceRoots` /
`moduleRoots` / `classpathJars` -- is fed to the runner, and the
user-supplied `--input` / `--classpath` still take effect: the adapter
provides the "standard project set", which the user can extend or override.

**Note: build-system is CLI-only; there is no YAML equivalent.** `ConfigLoader`
does not read `buildSystem:`; `--build-system` / `--project-root` exist only
on the CLI (see `SastMain.applyBuildSystem`). To express the same effect in
YAML, you have to write the paths the adapter resolved into `inputs:` /
`sourceRoots:` / `moduleRoots:` / `classpath:`.

Unimplemented features (planned): `buildSystem.compileClasspath: true` (run
only against the compile classpath, default true),
`buildSystem.excludeTestSources: true` (drop `src/test/java`, default false).

## 12. YAML format quick reference (v2 + checks.sub-dict)

This unifies the YAML style into one complete baseline. **Both checker
spellings are accepted**:

### 12.1 Simple bool (switch only)

```yaml
checks:
  taint: true
  style: true
  divByZero: true
  # ... 48 checker families in total, plus the taint / style master switches = 50 keys
```

`ConfigLoader.fromMapImpl` reads **48 checker families** from the `checks:`
map in total (each corresponding to a `ScanConfig.run*` boolean field), plus
the two master switches `taint` / `style` -- 50 keys in all. Every family
accepts both camelCase (canonical) and snake_case (legacy alias)
spellings, with camelCase taking priority.

There are 55 `ScanConfig.run*` boolean fields in total, of which 6 have
**no** corresponding YAML key and can only be changed in code or via `--rule` /
`rules.enabled`:
`runOrmLoadNullCheck` / `runOrmLostUpdate` / `runOrmUnnecessaryGet` /
`runUnlockNotLocked` (always true by default), `runServletAtomicity` /
`runSingletonRace` (always false by default, and both are pure AST checkers).

All families default to `true` (except a few that are off: `incorrectReturn` /
`threadRun` / `catchGeneric`). See `CLI.md` §3 for the detailed field list.

### 12.2 sub-dict form (for aiDedup or an explicit enabled)

```yaml
checks:
  divByZero:           {enabled: true, aiDedup: true}
  incorrectReturn:     {enabled: false, aiDedup: true}
  catchGeneric:        {enabled: false, aiDedup: false}
  # ...
```

The `aiDedup` flag decides: when `--ai-dedup` is enabled, **only** the
checkers with `aiDedup: true` go through the LLM review. Setting
`aiDedup: false` for pure style families (where a false positive is nearly
certain) saves tokens.

The two spellings **can be mixed**:

```yaml
checks:
  taint: true                      # bool = enabled: true, aiDedup: default true
  style: true
  catchGeneric: {enabled: false}   # sub-dict form overrides the default
```

### 12.3 emit-id style and yaml compatibility

The emit-id format of a style rule is `ast-<category>-<bare>` (category = the
name of the sub-package the checker lives in). Out of the 583
`new RuleId("...")` literals under `plumb-style/src/main/java`, **not one
ends with `-style`** -- strings like `ast-style-random-iv-cbc-style` are kept
only as an **alias** of the `RuleDef` (for compatibility with old YAML
allow-lists) and are no longer the emit-id of any rule.

`ScanRunner.isRuleIdAllowed` matches a yaml entry in several shapes; the
following spellings all hit the emit-id `ast-style-random-iv-cbc`:

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc        # emit-id (recommended)
    - ast-style-random-iv-cbc-style  # old alias (kept for compatibility)
    - random-iv-cbc                  # bare slug (compared after stripping the prefix)
```

The emit-id of a DFA taint rule is `dfa-taint-<bare>` (for example
`dfa-taint-sql-injection`).

The underlying logic (`ScanRunner.isRuleIdAllowed`,
`plumb-main/.../ScanRunner.java:1773-1863`): for each yaml entry it first
tries `endsWith(.<bare>)`, then strips the `ast-style-` prefix and the
`-style` suffix and compares against the bare slug. Any hit lets the rule
through. The helper that strips the prefix is `stripPlumbAndPlPrefix` in the
same file (`:1869-1889`).

### 12.4 Rule parameter override (supported)

The YAML path for runtime parameter overrides is
**`checks.<rule-id>.params.<param-name>`**:

```yaml
checks:
  line-length:
    enabled: true
    params:
      max: 120                  # overrides the default value 100 in the source
      ignoreComments: false
```

`ConfigLoader.fromMapImpl` (`plumb-main/.../ConfigLoader.java:343-357`) reads
it into `ScanConfig.ruleParams`, `ScanRunner` passes it in when constructing
the `StyleContext`, and `StyleContext` dispatches by rule id
(`StyleContext.java:132-140`). `<rule-id>` is the checker's `name()` or its
canonical rule id; the parameter name must match the name declared in
`RuleParameter`.

The override tests are `R761ParamsTest` and
`R822StyleContextParamOverrideTest`.
