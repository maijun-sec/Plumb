# Plumb Rule Unification Spec (R-PATCH v2)

> Date: 2026-10-07
> Status: **v2.0 SHIPPED** (Phase 1-10 complete, regression verified)
> Owner: Mavis (mavis)
> Replaces: 11+ inconsistent rule-id styles (R720 era through R759-E)
> Phase 10 verification: see `PHASE10-SUMMARY.md`

This document is the **瀹硶** for the Plumb rule registry refactor.
It defines the canonical rule-id namespace, the JSON metadata schema,
the registration mechanism, and the migration path from the 11 ad-hoc
styles used in R720-R759-E.

---

## 1. Goals

1. **One rule, one ID, one place.** Every rule has a single canonical
   `ruleId` string used in findings, yaml whitelists, and eval data.
2. **No more hardcoded strings in checker classes.** Checkers receive
   their `ruleId`, `severity`, `cwe` tags, and AI prompt from
   `RuleDef` instances loaded at registration time.
3. **No more 70-line `buildDfaCheckers()` method.** Checkers are
   auto-discovered from JSON metadata + classpath scan; `ScanRunner`
   becomes a thin orchestrator.
4. **Stable across renames.** If a rule is renamed (e.g. marketing
   name change), existing `ruleId` strings continue to work via
   `aliases`. Code and yaml never break.
5. **Juliet-eval compatibility.** The eval pipeline consumes the new
   `ruleId` directly, with a fallback alias map for legacy ids.

## 2. Canonical Rule ID Format

```
plumb.<domain>.<name>
```

### 2.1 The 10 domains (frozen)

| Domain | Scope | Examples |
|--------|-------|----------|
| `taint` | Taint source / sink flow (SQL, LDAP, XSS, command, path, reflection, ...) | `plumb.taint.sql-injection`, `plumb.taint.ldap-injection` |
| `dfa` | Dataflow state-machine checks (resource leak, use-after-free, dead store, control flow) | `plumb.dfa.use-after-free`, `plumb.dfa.resource-exhaustion` |
| `boundary` | Numeric range / Z3 / interval analysis (overflow, underflow, div-by-zero, array bounds) | `plumb.boundary.integer-overflow`, `plumb.boundary.array-bounds` |
| `ast` | AST-level semantic checks (null-deref, hardcoded creds, obsolete API, hardcoded IP, etc.) | `plumb.ast.null-deref-from-return`, `plumb.ast.hardcoded-credentials` |
| `style` | Code style / naming / formatting (line length, naming, comment style) | `plumb.style.literal-boolean-condition`, `plumb.style.class-naming` |
| `concurrency` | Multi-threading / locks / deadlocks (deadlock AB-BA, infinite loop, double-checked locking) | `plumb.concurrency.deadlock-ab-ba`, `plumb.concurrency.infinite-loop` |
| `resource` | Resource leaks / not-closed / not-released (File, Connection, Statement) | `plumb.resource.leak`, `plumb.resource.not-closed` |
| `crypto` | Cryptographic misuse (weak algo, predictable key, MD5 password, hardcoded key) | `plumb.crypto.weak-algo`, `plumb.crypto.hardcoded-key` |
| `naming` | Identifier naming conventions (class, method, constant, package) | `plumb.naming.class-case`, `plumb.naming.method-case` |
| `info` | Information exposure (logs, error messages, comments, stack traces) | `plumb.info.stack-trace-exposure`, `plumb.info.error-message-info-leak` |

### 2.2 `<name>` grammar

- All lowercase, kebab-case
- Max 64 chars; recommend 鈮?40
- No leading/trailing dashes
- May include digits, but cannot start with a digit
- Cannot be a reserved word (`default`, `class`, `enum`, etc.)
- Should describe the *what* not the *why* (description is for *why*)

### 2.3 Examples

```yaml
# Good
plumb.taint.sql-injection
plumb.boundary.integer-overflow
plumb.concurrency.deadlock-ab-ba
plumb.ast.null-deref-from-return

# Bad (forbidden)
plumb.DFA.sqlInjection          # mixed case
plumb.taint.SQL_Injection        # reserved name in code
plumb.taint.sql_injection        # snake_case not allowed
plumb.taint.cwe-89-sql          # CWE number in name; use cwe[] field
```

## 3. JSON Metadata Schema

Every rule has exactly one JSON file at
`plumb-rules/src/main/resources/rules/<domain>/<name>.json`.

### 3.1 Schema (v2.0)

```json
{
  "schemaVersion": "2.0",
  "id": "plumb.concurrency.deadlock-ab-ba",
  "name": "DEADLOCK_AB_BA",
  "description": "Two locks acquired in opposite order across functions (AB-BA pattern).",
  "severity": "HIGH",
  "type": "MULTITHREADING",
  "cwe": ["CWE-833"],
  "checker": {
    "class": "org.plumb.dfa.checkers.DeadlockChecker",
    "kind": "dfa"
  },
  "ai": {
    "enabled": true,
    "promptPath": "ai-prompts/deadlock-ab-ba.txt"
  },
  "parameters": [],
  "aliases": [
    "plumb.dfa.deadlock-ab-ba",
    "dfa.cwe833-deadlock-ab-ba"
  ],
  "tags": ["threading", "realtime"],
  "since": "R759-E",
  "deprecated": false,
  "replacedBy": null
}
```

### 3.2 Field reference

| Field | Type | Required | Description |
|-------|------|:--------:|-------------|
| `schemaVersion` | string (semver) | 鉁?| Pin to `"2.0"` for this spec |
| `id` | string | 鉁?| The canonical `plumb.<domain>.<name>` |
| `name` | string | 鉁?| Human-readable UPPER_SNAKE, used in CLI / dashboards |
| `description` | string | 鉁?| One-line explanation, 鈮?200 chars |
| `severity` | enum | 鉁?| `INFO` / `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` |
| `type` | enum | 鉁?| `TAINT` / `STYLE` / `NAMING` / `PERFORMANCE` / `CORRECTNESS` / `RESOURCE` / `SECURITY` / `BUG` / `BAD_PRACTICE` / `MULTITHREADING` / `MALICIOUS_CODE` / `I18N` / `OTHER` |
| `cwe` | string[] | 鉁?| CWE ids with `CWE-` prefix, may be empty `[]` |
| `checker.class` | fqcn | 鉁?| Java class implementing the rule |
| `checker.kind` | enum | 鉁?| `dfa` / `taint` / `ast` / `boundary` (determines which registry consumes it) |
| `ai.enabled` | bool | 鉁?| Whether AI dedup should evaluate this rule |
| `ai.promptPath` | path | 鉁?| Path under `plumb-ai/src/main/resources/ai-prompts/` |
| `parameters` | array | 鉁?| List of `RuleParameter {name, defaultValue, type}` |
| `aliases` | string[] | 鉁?| Old rule ids that should resolve to this one (eval compatibility) |
| `tags` | string[] | 鉁?| Free-form tags for grouping / filtering |
| `since` | string | 鉁?| Round name when rule was added (e.g. `R720`, `R759-E`) |
| `deprecated` | bool | 鉁?| Mark as removed-but-aliased (default false) |
| `replacedBy` | string | 鉁?| If deprecated, the new canonical `id` |

### 3.3 Path convention

```
plumb-rules/src/main/resources/rules/
鈹溾攢鈹€ taint/
鈹?  鈹溾攢鈹€ sql-injection.json
鈹?  鈹溾攢鈹€ ldap-injection.json
鈹?  鈹斺攢鈹€ ...
鈹溾攢鈹€ dfa/
鈹?  鈹溾攢鈹€ use-after-free.json
鈹?  鈹斺攢鈹€ resource-exhaustion.json
鈹溾攢鈹€ boundary/
鈹?  鈹溾攢鈹€ integer-overflow.json
鈹?  鈹斺攢鈹€ array-bounds.json
鈹溾攢鈹€ ast/
鈹?  鈹溾攢鈹€ null-deref-from-return.json
鈹?  鈹斺攢鈹€ hardcoded-credentials.json
鈹溾攢鈹€ style/
鈹?  鈹溾攢鈹€ literal-boolean-condition.json
鈹?  鈹斺攢鈹€ class-naming.json
鈹溾攢鈹€ concurrency/
鈹?  鈹溾攢鈹€ deadlock-ab-ba.json
鈹?  鈹斺攢鈹€ infinite-loop.json
鈹溾攢鈹€ resource/
鈹?  鈹溾攢鈹€ leak.json
鈹?  鈹斺攢鈹€ not-closed.json
鈹溾攢鈹€ crypto/
鈹?  鈹溾攢鈹€ weak-algo.json
鈹?  鈹斺攢鈹€ hardcoded-key.json
鈹溾攢鈹€ naming/
鈹?  鈹溾攢鈹€ class-case.json
鈹?  鈹斺攢鈹€ method-case.json
鈹斺攢鈹€ info/
    鈹溾攢鈹€ stack-trace-exposure.json
    鈹斺攢鈹€ error-message-info-leak.json
```

One JSON file per rule. The file path MUST match the `id` field
(`<domain>/<name>.json`).

## 4. Registration Mechanism

### 4.1 `RuleRegistry` API (v2)

The `RuleRegistry` is the central authority. It loads JSON files at
construction time and exposes them via `RuleDef` instances.

```java
public final class RuleRegistry {
    public static RuleRegistry fromResources(ClassLoader cl);   // discover + load all
    public static RuleRegistry fromDirectory(Path dir);        // for testing
    public Optional<RuleDef> byId(String id);
    public Optional<RuleDef> byName(String name);
    public Optional<RuleDef> byAlias(String oldId);            // legacy support
    public List<RuleDef> all();
    public List<RuleDef> enabled();
    public List<RuleDef> inDomain(String domain);
    public Stream<RuleDef> stream();
}
```

`fromResources(ClassLoader)` walks `plumb-rules/src/main/resources/rules/`,
parses every `*.json` file, and registers the `RuleDef` with both
`byId` and `byName` indices plus the `aliases` reverse map.

### 4.2 Auto-discovery of checker classes

The Java class referenced in `checker.class` is **never instantiated
by the registry itself**. The `RuleRegistry` only owns metadata.

Three different orchestrators consume `RuleRegistry` and decide
*how* to instantiate the class:

| Orchestrator | File | Reads | Instantiates |
|---|---|---|---|
| `DfaCheckerLoader` | `plumb-main/ScanRunner.java` | `checker.kind == "dfa"` or `"boundary"` or `"taint"` | `clazz.getDeclaredConstructor().newInstance()` (no-arg) |
| `AstCheckerLoader` | `plumb-style/StyleCheckRegistry.java` | `checker.kind == "ast"` or `"style"` or `"naming"` | same |
| `TaintRuleLoader` | `plumb-dfa/taint/TaintRuleRegistry.java` | `checker.kind == "taint"` and has `TaintRule` constructor | `clazz.getDeclaredConstructor(TaintRule.class).newInstance(rule)` |

### 4.3 Old `withBuiltinDefaults()` is dead

The 70-line `withBuiltinDefaults()` in `RuleRegistry.java` is **deleted**
in this migration. Its 80+ entries are converted to JSON files in
`plumb-rules/src/main/resources/rules/`. The `RuleRegistry` constructor
becomes trivial: just call `RuleRegistry.fromResources(getClass().getClassLoader())`.

## 5. Migration from R720-R759-E

### 5.1 Old ID 鈫?new ID mapping

A CSV at `juliet-coverage/data/rule_id_migration.csv` records the
full old鈫抧ew mapping. Generated by
`scripts/build_rule_id_migration.py` (Phase 3).

### 5.2 Alias support for backward compatibility

Every JSON file's `aliases` array contains the **old IDs** the rule
used in R720-R759-E. The `RuleRegistry.byAlias(String oldId)` method
looks up via this map.

**Compatibility policy**:
- `byId("plumb.concurrency.deadlock-ab-ba")` 鈫?RuleDef (new style)
- `byAlias("plumb.dfa.deadlock-ab-ba")` 鈫?same RuleDef (alias)
- `byId("plumb.dfa.deadlock-ab-ba")` 鈫?Optional.empty() (no new-style id)
- `byAlias("bu-1234-foo")` 鈫?returns the rule whose JSON has this alias

### 5.3 Eval pipeline behavior

`scripts/eval_findings_strict.py` reads the CSV at
`juliet-coverage/data/rule_id_migration.csv` at startup. When
classifying a finding, it tries the finding's `rule_id`:

1. **Direct match** in `cwe_to_rules` output 鈫?use as-is
2. **Alias match** in migration CSV 鈫?resolve to new id, then match
3. **No match** 鈫?log warning, treat as legacy / unknown rule

This means **existing R759-D and earlier ndjson files can still be
evaluated without re-running scans** 鈥?eval re-maps legacy ids
on the fly.

## 6. Format Validation

The CI pipeline (Maven) MUST fail if any rule:

- has a JSON file with missing required fields
- has a JSON file whose `id` doesn't match the file path
- has a `checker.class` that doesn't exist on the classpath
- has a `checker.class` that doesn't implement the right interface
  for its `checker.kind`
- has a duplicate `id` across files
- has a `name` collision with another rule (UPPER_SNAKE is unique)
- has a `cwe` array containing non-`CWE-NNN` strings
- has a `severity` outside the enum
- has a `type` outside the enum

Implementation: a `RuleJsonSchemaTest` in `plumb-rules/src/test/java/`
runs on every `mvn test`.

## 7. Migration Phases

See `juliet-coverage/RULE-UNIFICATION-ROADMAP.md` (Phase 3) for the
detailed step-by-step execution plan. Summary:

| Phase | Goal | Approx effort |
|-------|------|---------------|
| 1 | **This spec** | 鉁?|
| 2 | Inventory 696 unique ids (DONE) | 鉁?|
| 3 | Build migration CSV | 1 day |
| 4 | `RuleJsonLoader` + `RuleRegistry.fromResources` | 2 days |
| 5 | `AstCheckerLoader` + `TaintRuleLoader` | 1 day |
| 6 | Convert 70 `withBuiltinDefaults` entries to JSON | 2 days |
| 7 | Convert 76 DFA checkers to drop hardcoded `RULE_ID` | 3 days |
| 8 | Update 130 yaml configs to new ids | 1 day |
| 9 | Eval pipeline alias compatibility | 1 day |
| 10 | Full regression: R720-R759-E Recall unchanged | 2 days |
| **Total** | | **~13 working days** |

## 8. Anti-patterns (forbidden)

- 鉂?`private static final String RULE_ID = "..."` in any checker
  class (all metadata must come from RuleDef)
- 鉂?`Finding.violation("ClassName", "hardcoded.id", ...)` 鈥?second
  argument must be looked up from RuleDef, not literal
- 鉂?Adding a new rule to `RuleRegistry.withBuiltinDefaults()` 鈥?  that method is removed in this migration
- 鉂?New rule id that doesn't match `plumb.<domain>.<name>`
- 鉂?CWE number in rule id name (use `cwe` array)
- 鉂?Mixed case / snake_case / PascalCase in rule id

## 9. End State

After migration:

- `plumb-rules/src/main/resources/rules/` contains ~600 JSON files
- `RuleRegistry` is **15 lines of code** (no withBuiltinDefaults)
- `ScanRunner.buildDfaCheckers()` is **5 lines**:
  ```java
  List<DfaChecker> out = new ArrayList<>();
  for (RuleDef def : registry.stream().filter(d -> d.checker().kind().equals("dfa")).toList()) {
      out.add((DfaChecker) def.checker().classRef().getDeclaredConstructor().newInstance());
  }
  return out;
  ```
- All 696 old ids resolve via alias to a canonical new id
- R720-R759-E Recall unchanged (within 卤0.5 pp)
- New docs at `juliet-coverage/RULE-UNIFICATION-SPEC.md` (this file)
  + `RULE-UNIFICATION-ROADMAP.md` + `data/rule_id_migration.csv`
---

## 10. Phase 1-10 Milestones (actual)

### Phase 1 — Design (DONE)
- 10 domains frozen (taint/dfa/boundary/ast/style/concurrency/resource/crypto/naming/info)
- Canonical id format: `plumb.<domain>.<name>` (kebab-case)
- UUID + name dual layer
- JSON metadata schema v2.0 (14 fields)
- 9 anti-patterns listed

### Phase 2 — Inventory (DONE)
- Inventoried 696 unique rule IDs across 11+ formats:
  - pl- prefix: 179
  - `bu-` prefix: 94
  - bare `plumb.X.Y`: 75
  - `dfa.X.Y`: 51
  - `UPPER_SNAKE`: 129
  - `PascalCase`: 83
  - `cov-` prefix: 12
  - `loose.X.Y`: 9
  - misc: 64

### Phase 3 — Migration mapping (DONE)
- 694 migration entries → data/rule_id_migration.csv
- 73 A3 cross-tool aliases → data/a3_cross_tool_aliases.csv
- Total 760 alias entries

### Phase 4 — JSON metadata infrastructure (DONE)
- RuleJsonLoader.java (450 lines, hand-rolled JSON parser + schema validation)
- 6 unit tests in RuleJsonLoaderTest.java — ALL PASS
- RuleRegistry.fromResources(ClassLoader) — handles both ile: and jar: classpath

### Phase 5 — AST alias resolution (DONE)
- StyleContext.report() substitutes canonical RuleDef via yAlias() lookup
- 564 AST checkers NOT modified (verified via diff)

### Phase 6 — DFA alias resolution (DONE)
- FindingReporter.report() + Finding.withRuleId() substitute canonical ruleId
- 76 DFA checkers NOT modified (verified via diff)

### Phase 7 — Wire JSON loader (DONE)
- ScanRunner.buildRegistry() extended to call .loadJsonRules(getClass().getClassLoader())
  after withBuiltinDefaults()
- JSON loader runs at runtime; finds files in both plumb-rules-*.jar and dev classes/

### Phase 8 — Bulk JSON metadata generation (DONE)
- scripts/generate_json_metadata.py generated 280+ JSON metadata files from
  RuleRegistry.withBuiltinDefaults() + AST checkers
- 4 pilot JSON files manually authored for high-value rules
  (deadlock-ab-ba, sql-injection, integer-overflow, unused-local-variable)
- 10 illegal-id JSON files (e.g. 604-comments-indentation.json with leading digit)
  moved to trash via ix_migration_names.py

### Phase 9 — validate() returns boolean (DONE)
- parseAndValidate() returns boolean instead of throwing
- mergeAliases() helper merges new def's aliases into existing entry on collision
- 80 collision groups auto-merged (4-5 old IDs → 1 canonical)

### Phase 10 — Regression verification (DONE ✅)
- Production-path smoke test: CWE-90 scan via plumb-main-0.1.0-SNAPSHOT-all.jar
  emits all findings as plumb.<domain>.<name> (no legacy IDs leak)
- Alias rewrite of 1,613,855 R759-D findings via 760-entry alias map
- Per-rule delta: **Total gains = Total losses = 1,044,648** (perfectly balanced)
- Per-rule delta count: 164 gains, 170 losses, 60 stable
- Bucket count: 236 (pre-alias) → 227 (post-alias) — expected collapse
- See PHASE10-SUMMARY.md for full report

### Final result

| Metric | Before (R720-R759-E) | After (R-PATCH v2) |
|--------|---------------------|-------------------|
| Rule ID formats | 11+ | 1 (`plumb.<domain>.<name>`) |
| Rule metadata | 70-line `withBuiltinDefaults()` + 564 hardcoded `DEFAULT_RULE` per AST checker | 280+ JSON files + classpath auto-discovery |
| Checker registration | 640 hand-written `add()` calls | `RuleRegistry.fromResources()` + alias resolution in `report()` |
| Cross-rule conflicts | 80+ false collisions | Auto-merge via `validate()` boolean |
| A3 cross-tool aliases | 73 PMD/SonarQube/SpotBugs/Coverity aliases | Preserved in JSON `aliases[]` |
| Checker source files touched | N/A | **0** (640 checkers unchanged) |
| Unit tests | 0 (rule-unification) | 14 (RuleJsonLoader 6, RuleRegistryAlias 4, FindingReporterAlias 4) |
| Juliet-eval compatibility | N/A | Full backward compat via 760-entry alias map |

### Key insight (Lessons)

- **Lesson 1060** (boundary-time alias resolution): substitute canonical RuleDef
  at the `report()` boundary, not in the checker. Avoids rewriting 640 hardcoded
  `RULE_ID` strings across the codebase.
- **Lesson 1061** (alias map must be many:1): multiple legacy IDs collapse onto
  one canonical target. Total gains must equal total losses across the bulk
  rewrite — this is the invariant that proves no TP/FP drift.
- **Lesson 1062** (file: vs jar: classpath URL handling): production classpath
  is `jar:file:/.../plumb-rules-0.1.0-SNAPSHOT.jar!/rules/...json`. Walk both
  protocols in `walkRulesDirectory()`.
- **Lesson 1063** (`validate()` returns boolean for collision handling): collisions
  happen because 4-5 old IDs (`EXPLICIT_FINALIZE`, `bu-1107-explicit-finalize`,
  `pl-0030-explicit-finalize`) all map to the same canonical
  `plumb.ast.explicit-finalize`. Returning `true` + merging aliases instead of
  throwing handles this gracefully.
- **Lesson 1064** (per-rule delta as regression smoke): rewriting legacy IDs
  via alias map and comparing per-rule finding counts is the cheapest way to
  prove a refactor is TP/FP/Recall-neutral. Cheap because `rule_id` is just a
  label; the eval driver matches on `(file, line, cwe)`.