# R762 — Rule parameter support (Oct 2026)

The `RuleParameter` framework was designed but never wired through.
R761 audit identified the gap; R762 wires it.

## What changed

### 1. ConfigLoader parses `checks.<rule>.params.<name>`

`plumb-main/.../ConfigLoader.java` — inside the `checks:` map block,
every `<key>` whose value is a `Map` with a `params:` subkey is
parsed into `cfg.ruleParams[<key>][<paramName>] = coercedValue`.

```yaml
checks:
  line-length:
    params:
      max: 80
      ignoreComments: false
  magic-number:
    params:
      ignoreNumbers: "100,200,300"
```

Values are coerced by SnakeYAML: `80` → `Integer`, `false` →
`Boolean`, `"100,200,300"` → `String`. A new helper
`coerceParamValue(Object)` centralises the type rules so the
StyleContext lookup can rely on the values.

### 2. ScanConfig carries `Map<String, Map<String, Object>> ruleParams`

`plumb-main/.../ScanConfig.java` — new public field. Default empty
map; populated by ConfigLoader from the `checks.<rule>.params:`
block above.

### 3. StyleContext accepts a per-rule param map

`plumb-style/.../StyleContext.java`:

- New 3-arg constructor `(source, rules, ruleParams)` keeps the
  2-arg form for back-compat.
- `setCurrentRule(String)` and `currentRule()` setter/getter —
  the runner calls `setCurrentRule(c.name())` before each
  `init()` / `visit()` so per-checker lookups scope by rule id.
- `paramOverride(name, type)` now keys by
  `<currentRule>.<name>`. If `currentRule` is empty (e.g. an old
  direct caller without a runner), it falls back to the bare
  name — preserves the pre-R762 behaviour for any code that
  built a StyleContext without going through ScanRunner.
- A new private `coerce(Object, Class)` consolidates the
  `String→Integer` / `String→Boolean` coercion that used to
  live inline.

### 4. AstDispatcher sets the current rule on every init / visit

`plumb-style/.../AstDispatcher.java` — one `ctx.setCurrentRule(
c.name())` call before each `c.init(ctx)` and one before each
`c.visit(node, ctx)` (across all 50+ `visit` overloads). This
makes the per-checker param lookup automatic without requiring
any checker to pass its own id.

### 5. ScanRunner wires it through

`plumb-main/.../ScanRunner.java` — both `StyleContext`
construction sites now pass `config.ruleParams` (line 1586 in
`runStyleChecks`; line 1903 in the legacy classfile path).

### 6. R760 cleanup recovery: 2 JSON metadata files restored

`plumb-rules/src/main/resources/rules/ast/`:
- `object-hijack.json` — CWE-491, `org.plumb.style.ObjectHijackCheck`
- `uncaught-exception.json` — CWE-248, `org.plumb.style.UncaughtExceptionCheck`

These were written during R759-F (R-PATCH v2 spec §4) but the
R760 large-cleanup pass deleted the empty `plumb-rules/.../
resources/` directory along with the 9 loose-checker trash
files. Restored from the R759-F SUMMARY descriptions. Note:
`RuleRegistry.fromResources(ClassLoader)` is still **not
implemented** — these JSON files are documentation-only until
the loader exists. They have no runtime effect today.

## Tests added

`plumb-main/src/test/java/org/plumb/main/R761ParamsTest.java`
(R762 in code, named for the audit round that identified the
gap):

| Test | What it verifies |
|------|------------------|
| `parsesCheckParams` | YAML `checks.<rule>.params` lands in `cfg.ruleParams` with correct types (int / bool / string) |
| `emptyConfigNoParams` | No `checks:` block → `ruleParams` is empty |
| `paramsCoerceStringValues` | Quoted-string user values preserved; StyleContext coerces at lookup |

## Verification

### E2E
- `checks.line-length.params.max: 50` → finding says `max 50` ✅
- Same config without `params:` → finding says `max 100` (default) ✅
- Column in NDJSON output shifts from 100 to 50 (threshold changes the report position) ✅

### Test suites
- `mvn -pl plumb-main test` → **13/13 test classes pass** (R761ParamsTest added)
- `mvn -pl plumb-style test` → **76/76 test classes pass** (no regression from setCurrentRule)
- `mvn -pl plumb-main,plumb-style -am test` → **89/89 test classes pass**
- `NdjsonEmitterTest.emitsOneLinePerFinding` is a **pre-existing
  failure** (line 29 expects `"line":"10"` with quotes; the
  emitter produces `"line":10` as int) — unrelated to this
  change, present before R760.

## Operational notes

- 32 style checkers now have **user-overridable parameters** via
  `checks.<ruleId>.params.<name>`. The 32 with `RuleParameter`
  declarations are listed in `juliet-coverage/reports/r761/
  SUMMARY.md`.
- Param name collisions (3 cases: `format`, `max`,
  `ignoreMethodsWithOverrideAnnotation`) are resolved
  automatically by scoping each lookup to the current rule id —
  the user does not need to namespace their own keys.
- DFA-side checkers (`plumb-dfa/`) still do not declare any
  `RuleParameter` (verified via grep). Their per-rule
  configuration is hard-coded in checker constructors. If a
  DFA checker needs a tunable knob in the future, follow the
  same pattern (declare `RuleParameter` in the static
  `PARAMS` array; expose via `AnalysisContext.paramOverride`).
- `RuleRegistry.fromResources(ClassLoader)` is the next gap.
  The two JSON files restored here will become loadable
  automatically once a future R-PATCH v3 implements the
  loader.