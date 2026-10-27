# Rule Operations: Style 595 / Enabling and Disabling / Changing Parameters

[English](../README.md) | [中文](../zh/rules/RULE-STYLE-OP.md)

This document covers the operations on style rules: how many there are,
the five layers of enabling and disabling, and how to change a rule's
parameters. The main rule catalogue (counts / severity / type / emit-id /
taint / DFA) is in `RULES.md`; rule development (AI prompt / CWE labels /
external tool IDs / adding a rule) is in `RULE-AUTHORING.md`.

## 1. Style rules (596 checker classes)

Style rules are **not written into `plumb-rules/RuleRegistry`**; they are
classpath-scanned at startup by `AstCheckerRegistry`. The actual distribution
across the 9 sub-directories is in the category-statistics section of
`docs/rules/RULES.md`.

| Directory | Count | Topic |
| --- | ---: | --- |
| `plumb-style/style/` | **353** | Coding style (comments, Javadoc, indentation, imports, control flow, declarations) |
| `plumb-style/performance/` | **63** | Performance (strings, boxed types, BigDecimal, Random) |
| `plumb-style/naming/` | **51** | Naming conventions (types, methods, fields, parameters, local variables, packages) |
| `plumb-style/security/` | **42** | Security-related (deserialization, URL sanitization) |
| `plumb-style/error/` | **35** | Error handling (catch, finally, throw, synchronized) |
| `plumb-style/bug/` | **20** | Clear bug patterns (compareTo, equals, null checks) |
| `plumb-style/design/` | **18** | Design metrics (cyclomatic complexity, coupling, depth) |
| `plumb-style/quality/` | **10** | Quality (regex confusion, ORM patterns) |
| `plumb-style/`（根目录） | **2** | `AstChecker` (the interface), `ObjectHijackCheck` |
| `plumb-style/concurrency/` | **2** | Concurrency (servlet atomicity, singleton race) |
| `plumb-style/annotations/` | 0 | Annotation IR utilities (not checkers) |
| **合计** | **596** | — |

`CheckerCategories.CHECKER_CATEGORIES` registers **593** of them (for the
difference of 2, see [`../tech/STYLE-RULES.md`](../tech/STYLE-RULES.md) §6).

> The full list plus the Javadoc of every rule plus the PMD/SpotBugs/SonarQube/Coverity
> equivalent mapping is in `docs/plumb-style-rules.md` (generated, by
> `scripts/gen_rules_doc.py`). The index is in
> `docs/plumb-style-rules-index.md` (by `scripts/gen_rules_index.py`).

### 7.1 The emit-id of a style rule

```java
// plumb-style/style/RandomIvCheck.java
public final class RandomIvCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-random-iv-cbc", "RANDOM_IV_CBC"),
          "AES/CBC cipher uses a non-random Initialization Vector (CWE-329).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-329"),
          "org.plumb.style.RandomIvCheck",
          true,
          List.of("ast-style-random-iv-cbc-style", "random-iv-cbc")   // legacy alias
      );
}
```

The format of an emit-id is `ast-<category>-<bare>` (category = the name of the
sub-package the checker lives in). The `-style`-suffixed
`ast-style-random-iv-cbc-style` is **only an alias** and is no longer the
emit-id of any rule -- measured over the 579 `new RuleId` literals under
`plumb-style/src/main/java`, not one of them ends in `-style`.

Any of these spellings in the YAML will match:

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc        # emit-id (recommended)
    - ast-style-random-iv-cbc-style  # old alias (still compatible)
    - random-iv-cbc                  # bare slug
```

## 2. Enabling and disabling rules

### 8.1 The category switches (`checks:`)

Enable or disable a whole family of checkers at once with
`checks.<name>: true|false`. For example:

```yaml
checks:
  taint: false         # disable all taint rules (12 in the `TaintRuleRegistry`)
  style: false         # disable all AST style rules
  divByZero: false     # disable DIV_BY_ZERO
```

A key that is not written uses the default value (**almost all default to
true**, only a few are off -- see the `ScanConfig` field initialisation:
`incorrectReturn` / `threadRun` / `catchGeneric` default to false).

**The sub-dict form** (combine it with `aiDedup` or an explicit `enabled`):

```yaml
checks:
  divByZero:           {enabled: true,  aiDedup: true}
  incorrectReturn:     {enabled: false, aiDedup: true}
  catchGeneric:        {enabled: false, aiDedup: false}
```

`aiDedup: true` means the rule goes through the `--ai-dedup` LLM review;
`aiDedup: false` skips it in AI mode -- setting false on the pure style rules
(where the FP is almost certain) saves tokens.

### 8.2 Fine-grained: `rules.enabled` / `rules.disabled`

`rules.enabled` forces the listed rules **on**; `rules.disabled` forces them
off. It accepts a **rule UUID** (such as `bu-0201-style-empty-catch`), a
**rule name** (such as `STYLE_EMPTY_CATCH`) and an **emit-id** (such as
`ast-style-system-out-println`):

```yaml
rules:
  disabled:
    - STYLE_SYSTEM_OUT            # rule name
    - ast-style-system-out-println  # style emit-id
  enabled:
    - bu-0401-weak-crypto         # force on even when the family is off
    - dfa-taint-null-dereference  # DFA taint emit-id
```

The actual behaviour: the `enabled` flag is reset during the rule registration
phase (after `RuleRegistry.withBuiltinDefaults()`). The code path is in
`ScanRunner.buildRegistry()`:

```java
for (String enabled : config.enabledRuleNames) {
    if (enabled.endsWith("." + bare)) return true; // compatibility
    String astStyleBare = enabled;
    if (astStyleBare.startsWith("ast-style-")) {
        astStyleBare = astStyleBare.substring("ast-style-".length());
    }
    if (astStyleBare.endsWith("-style")) {
        astStyleBare = astStyleBare.substring(0, astStyleBare.length() - "-style".length());
    }
    if (astStyleBare.equals(bare)) return true; // style
}
```

### 8.3 Disabling from the command line

The CLI has no native `--disable` flag, but you can use a small shell alias:

```bash
# parse every -D rules.disabled=ID into a YAML fragment and pass it in
cat > /tmp/disable.yaml <<EOF
rules:
  disabled: [$1]
EOF
java -jar plumb-...-all.jar -c /tmp/disable.yaml -i src/main/java

# or use --rule directly to run a single rule for now
bin/sast-scan -i src --rule TAINT_SINK_SQL_INJECTION
```

### 8.4 The 5-layer enabled gate mechanism

This expands "enable / disable" from 1 layer (`checks.*`) to 5 layers. **You
must know these 5 layers and their order when you change the disable
behaviour** -- a later layer overrides the setting of an earlier one:

```
┌──────────────────────────────────────────────────────────┐
│ 1. RuleDef.enabled (the checker's static DEFAULT_RULE field) │
│ - defaults to true. 22 rules are disabled out of the box      │
│    - RuleRegistry.registerFromChecker persists via updateInPlace │
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ 2. RuleRegistry.disabledRuleNames (per-scan override)     │
│    - comes from yaml rules.disabled, the --rule blacklist   │
│    - the buildRegistry() loop resets enabled = false on hit │
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ 3. RuleRegistry.withBuiltinDefaults(true) (smoke test)     │
│    - calls AstCheckerRegistry.builtins() + registerFrom     │
│    - forceAllEnabledIf flips every def.enabled() to true   │
│    - production never passes true, the smoke test does     │
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ 4. StyleContext.forceEnable(String...) (escape hatch)      │
│    - a per-context bypass list, production never calls it  │
│    - the smoke test enables it via ctx.forceEnable(ruleId) │
└──────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────┐
│ 5. ScanRunner.isRuleIdAllowed(ruleId, liveRegistry)       │
│    - prod gate: liveRegistry.byName(ruleId).enabled()      │
│    - return !defaultDisabled at end (default off)          │
│    - yaml rules.enabled / --rule override the explicit on  │
└──────────────────────────────────────────────────────────┘
```

**The practical points**:

- To disable a rule go through **layer 2** (`yaml rules.disabled`) -- that is the ordinary user path
- To force-enable a rule go through **layer 5** (`yaml rules.enabled` or `--rule`) -- a forced override
- Changing a `RuleDef` default needs a rebuild plus re-publishing the image (**layer 1** is compile-time)
- Of the 16 checkers in the current `plumb-dfa/src/main/resources/checkers.yaml`, 2 are
  `enabled: false` (`SingletonRaceDfa` / `ServletAtomicityDfa`); they have to be explicitly
  enabled before they run

## 3. Changing rule parameters

Every `RuleDef` can carry a `RuleParameter` (see
`plumb-rules/src/main/java/org/plumb/rules/RuleParameter.java`).

### 9.1 Runtime parameter overrides (already supported)

**The YAML path is `checks.<rule-id>.params.<param-name>`** (not
`rules.parameters.*`). `ConfigLoader.fromMapImpl`
(`plumb-main/.../ConfigLoader.java:343-357`) reads the flattened `params:` map
of each rule block under `checks:` into `ScanConfig.ruleParams`, coercing the
values to Integer / Boolean / String by their literal type. `ScanRunner`
passes it in when it constructs the `StyleContext`
(`ScanRunner.java:2061`, `:2454`), and `StyleContext` dispatches it by rule id
(`StyleContext.java:132-140`).

```yaml
checks:
  line-length:
    enabled: true
    params:
      max: 120                 # override LineLengthCheck's max default of 100
      ignoreComments: false
```

The outer key must be the `name()` of the checker (a kebab-case slug, such as
`line-length`). **Neither the canonical rule id (`ast-style-line-length`) nor
the short name (`LINE_LENGTH`) works** -- the lookup is a direct key
concatenation of `checker.name() + "." + paramName`, with no alias resolution.
The parameter name must match the name declared in `RuleParameter`.

When no override hits, it falls back to the default declared in
`RuleDef.parameters()`. This fallback path resolves the rule by slug, and
`RuleRegistry` guarantees that each checker's slug points at its own rule --
so the slug-to-rule-name correspondence is maintained by the registry, not by
every checker writing its slug into the alias list by hand.
`CheckerSlugResolutionTest`
(`plumb-style/src/test/.../CheckerSlugResolutionTest.java`) covers this
invariant.

`R761ParamsTest` (`plumb-main/src/test/.../R761ParamsTest.java`) covers this
path, and `R822StyleContextParamOverrideTest` covers the parameter dispatch on
the `StyleContext` side.

### 9.2 Changing a default (needs a rebuild)

To change the default value of a `RuleParameter` in the code:

1. Find the `new RuleParameter(...)` call in
   `plumb-style/src/main/java/.../<Rule>Check.java`.
2. Change the default string.
3. Rebuild with `mvn install`.

For example, to change the line-length threshold of `LineLengthCheck` from 100
to 120:

```java
// plumb-style/style/LineLengthCheck.java
private static final RuleParameter[] PARAMS = {
  new RuleParameter("max", "Maximum allowed line length", RuleParameter.Type.INT, "120"),
  // ...
};
```

**You can also change it without a rebuild** -- use the YAML override of §9.1,
which takes priority anyway.

### 9.3 `aggressiveness` is deprecated

`aggressiveness: low|medium|high` is no longer recognised.
- The CLI has no `--aggressiveness` flag (it has been removed).
- When you write `aggressiveness:` in the YAML, `ConfigLoader.fromMapImpl`
  prints a `[plumb] warning: 'aggressiveness' in YAML is no longer supported; ...`
  to stderr, **but the scan continues as usual** -- this is not a fatal error.
- The replacement: a `checks.<rule>: {enabled: true|false}` block or the
  `--rule <id>` command-line filter for the moment.
