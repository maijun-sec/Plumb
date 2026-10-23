# R207 — Unified DFA Engine Design (mirror of Fathom)

**Date**: 2026-07-31
**Round type**: Architecture design document (no code)
**Status**: Pending review

## 1. Goals

Mirror the Fathom DFA architecture into Plumb, while
keeping the existing narrow checkers (`plumb-dfa`,
`plumb-style`) as a "lightweight" option. The new
unified engine addresses the limitations the user
identified:

1. **Cross-function / cross-TU analysis** — most of
   the existing narrow checkers are intra-procedural
   only.
2. **Taint distinguishing SQL / LDAP / Command /
   File-path / Format-string / div-by-zero** — single
   taint walk that tags findings with the right CWE
   family, instead of N independent taint catalog
   entries that don't share infrastructure.
3. **Context-sensitive summaries** — `validateNonZero(x);
   use(x);` should suppress div-by-zero at the call
   site, `createReader()` returning a `FileReader` should
   be tracked for resource leak.
4. **Constraint solving for path feasibility** — Z3
   verification of reverse paths, instead of
   heuristic over-taint.
5. **Externalized configuration** — all source/sink
   families, fact kind traits, checker enable/disable,
   severity mappings should live in YAML and be
   user-editable at runtime, not hard-coded in Java.

## 2. Architecture overview (5 layers, mirror of Fathom)

```
┌────────────────────────────────────────────────────────┐
│  L1. Configuration (YAML, all externalized)            │
│      • sink-families.yaml                              │
│      • fact-kinds.yaml                                 │
│      • checkers.yaml                                   │
│      • cwe-mappings.yaml                               │
│      • tuning.yaml                                     │
├────────────────────────────────────────────────────────┤
│  L2. Call graph                                        │
│      • CallGraph, CallGraphBuilder                     │
│      • GlobalCallGraphBuilder, RPO, SCC                │
├────────────────────────────────────────────────────────┤
│  L3. Analysis engine                                   │
│      • DfaEngine, DfaChecker (base interface)         │
│      • AnalysisContext, FindingReporter               │
│      • k-CFA context-sensitive (k=0/1/2/3)             │
├────────────────────────────────────────────────────────┤
│  L4. Shared infra                                      │
│      • FactKind + Trait (typed dataflow facts)         │
│      • SinkFamilyRegistry (loaded from YAML)          │
│      • TaintDomain (NUMERIC/STRING/POINTER/MIXED)     │
│      • FunctionSummary (per-fn summary)               │
│      • CrossFunctionConstantPropagation (R-values)    │
├────────────────────────────────────────────────────────┤
│  L5. Solver                                            │
│      • Z3Solver + TacToZ3 (Java port)                  │
│      • LocalSolver                                     │
│      • ReverseTaintWalker (Z3-verified)                │
├────────────────────────────────────────────────────────┤
│  L6. Mirror checkers (5 in R214, more in R216+)       │
│      • DivideByZero, IntegerOverflow,                  │
│        NullDereference, ResourceLeak,                  │
│        SensitiveDataLeak                               │
│      • all extend DfaChecker base                      │
└────────────────────────────────────────────────────────┘
```

## 3. Configuration model — all YAML

### 3.1 `sink-families.yaml`

```yaml
# Maps a family to a set of function-name patterns.
# A pattern is a regex that matches the callee signature.
# Used by SinkFamilyRegistry to classify call sites.
families:
  sql.sinks:
    cwe: CWE-89
    severity: CRITICAL
    patterns:
      - "^java\\.sql\\.Statement#execute"
      - "^java\\.sql\\.PreparedStatement#execute"
      - "^java\\.sql\\.Connection#prepareCall"
  ldap.sinks:
    cwe: CWE-90
    severity: HIGH
    patterns:
      - "^javax\\.naming\\.InitialDirContext#search"
      - - "^javax\\.naming\\.ldap\\.LdapContext#search"
  command.sinks:
    cwe: CWE-78
    severity: CRITICAL
    patterns:
      - "^java\\.lang\\.Runtime#exec"
      - "^java\\.lang\\.ProcessBuilder#start"
  file-path.sinks:
    cwe: CWE-22
    severity: HIGH
    patterns:
      - "^java\\.io\\.FileInputStream#<init>"
      - "^java\\.io\\.FileOutputStream#<init>"
      - "^java\\.nio\\.file\\.Paths#get"
  format-string.sinks:
    cwe: CWE-134
    severity: MEDIUM
    patterns:
      - "^java\\.lang\\.String#format"
      - "^java\\.io\\.PrintStream#printf"
  taint.sources:
    cwe: null
    severity: null
    patterns:
      - "^javax\\.servlet\\.http\\.HttpServletRequest#getParameter"
      - "^java\\.io\\.InputStream#read"
      - "^java\\.net\\.Socket#getInputStream"
```

### 3.2 `fact-kinds.yaml`

```yaml
# Defines the typed dataflow facts used across checkers.
# A fact kind is a semantic property a checker cares about.
fact_kinds:
  UntrustedSource:
    description: "Value came from outside the trust boundary"
    traits: [UNTRUSTED_SOURCE, PROPAGATED_BY_DECL, PROPAGATED_THROUGH_CALL]
    owner: TaintedChecker
  UntrustedSink:
    description: "Call site / use site that consumes sensitive values"
    traits: [UNTRUSTED_SINK, IS_PATH_SINK]
    owner: TaintedChecker
  MayBeZero:
    description: "Variable may hold the value 0"
    traits: [PROPAGATED_BY_DECL, IS_PATH_SINK]
    owner: DfaChecker
  MayBeNull:
    description: "Variable may hold the value null"
    traits: [PROPAGATED_BY_DECL, PROPAGATED_THROUGH_CALL, IS_PATH_SINK]
    owner: DfaChecker
  OpenResource:
    description: "Resource opened by callee; needs close"
    traits: [PROPAGATED_THROUGH_CALL, IS_PATH_SINK]
    owner: DfaChecker
  TaintedRange:
    description: "Integer variable may overflow"
    traits: [PROPAGATED_BY_DECL, JOINED_BY_AND]
    owner: DfaChecker
```

### 3.3 `checkers.yaml`

```yaml
# Per-checker enable / disable / severity override
# / context depth.
checkers:
  DivideByZero:
    enabled: true
    severity: HIGH
    context_depth: 2          # k-CFA depth
    path_sensitivity: full    # full | windowed
  IntegerOverflow:
    enabled: true
    severity: MEDIUM
    context_depth: 2
  NullDereference:
    enabled: true
    severity: HIGH
    context_depth: 1
  ResourceLeak:
    enabled: true
    severity: MEDIUM
    context_depth: 2
  SensitiveDataLeak:
    enabled: true
    severity: CRITICAL
    context_depth: 3
    taint_domain: MIXED       # accept any domain
```

### 3.4 `cwe-mappings.yaml`

```yaml
# Maps a finding to its CWE family.
# Used when emitting SARIF / NDJSON output.
mappings:
  DivideByZero: CWE-369
  IntegerOverflow: CWE-190
  NullDereference: CWE-476
  ResourceLeak: [CWE-404, CWE-459]
  SensitiveDataLeak_sql: CWE-89
  SensitiveDataLeak_ldap: CWE-90
  SensitiveDataLeak_command: CWE-78
  SensitiveDataLeak_file: CWE-22
  SensitiveDataLeak_format: CWE-134
```

### 3.5 `tuning.yaml`

```yaml
# Engine-wide tuning knobs.
engine:
  context_depth: 2
  worklist_max_size: 10000
  z3_timeout_ms: 5000
  summary_cache_size: 1000
  reverse_walk_max_depth: 8
  pre_scan_enabled: true
  library_call_classification: true
```

## 4. Configuration loader (R208)

A `ConfigLoader` class loads all YAML files at startup
into a single immutable `EngineConfig` object:

```java
public final class EngineConfig {
    final Map<String, SinkFamily> sinkFamilies;     // family → {cwe, severity, patterns}
    final Map<String, FactKind> factKinds;          // kind-name → FactKind
    final Map<String, CheckerConfig> checkers;      // checker-name → CheckerConfig
    final Map<String, String> cweMappings;          // finding-key → CWE
    final TuningConfig tuning;                       // context-depth, timeouts, etc.
}

public static EngineConfig loadFromDirectory(Path yamlDir);
public static EngineConfig loadFromClasspath();
```

User can:
- Override any subset of the YAML files
- Add new entries to existing families (e.g. add a new
  project-specific SQL sink pattern)
- Add new fact kinds, checkers, sink families
- Disable rules via `checkers: { RuleName: { enabled: false } }`

## 5. Extensibility points

| Where | How to extend |
|---|---|
| Add a new sink family | Append to `sink-families.yaml` under `families:` |
| Add a new fact kind | Append to `fact-kinds.yaml` under `fact_kinds:` |
| Add a new checker | Implement `DfaChecker` interface + register in `checkers.yaml` |
| Override a rule | Add a YAML file with same key, later file wins |
| Disable a rule | Set `enabled: false` in `checkers.yaml` |
| Tune context depth | `tuning.yaml` → `engine.context_depth` |

All four of these are zero-code-change operations on
the user's side. A user adding a custom rule can:

```yaml
# checkers.yaml
checkers:
  MyProjectSqlSink:
    enabled: true
    severity: CRITICAL
    class: com.example.MyCustomSqlSinkChecker
    cwe: CWE-89
    sink_family: sql.sinks
```

…as long as `MyCustomSqlSinkChecker` implements
`DfaChecker` and is on the classpath.

## 6. Module structure

New module: `plumb-dfa` (sibling of
`plumb-dfa`). Existing modules (`plumb-dfa`,
`plumb-style`) untouched.

```
plumb-dfa/
  src/main/java/org/plumb/dfa/unified/
    config/
      EngineConfig.java
      ConfigLoader.java
      SinkFamily.java
      FactKind.java            (mirrored from Fathom, loads from YAML)
      CheckerConfig.java
      TuningConfig.java
    cg/
      CallGraph.java
      CallGraphBuilder.java
      CallSite.java
      FunctionNode.java
    engine/
      DfaEngine.java
      DfaChecker.java          (interface)
      AnalysisContext.java
      FindingReporter.java
      Finding.java
    summary/
      FunctionSummary.java
      ContextSensitiveAnalyzer.java
      ContextKey.java
    constprop/
      CrossFunctionConstantPropagation.java
    taint/
      TaintedChecker.java      (base class)
      TaintChecker.java        (concrete)
      TaintDomain.java
    solver/
      Z3Solver.java
      TacToZ3.java
      LocalSolver.java
      ReverseTaintWalker.java
    checkers/
      DivideByZeroChecker.java
      IntegerOverflowChecker.java
      NullDereferenceChecker.java
      ResourceLeakChecker.java
      SensitiveDataLeakChecker.java
  src/main/resources/
    sink-families.yaml
    fact-kinds.yaml
    checkers.yaml
    cwe-mappings.yaml
    tuning.yaml
  src/test/java/org/plumb/dfa/unified/
    ...
```

## 7. Migration / co-existence strategy

- `plumb-dfa` (existing) and `plumb-dfa` (new)
  live side by side.
- CLI: `-X engine=lightweight` (default) or
  `engine=unified` to select.
- Both engines can run in the same ScanRunner; a
  unified-engine finding coexists with a
  lightweight-engine finding, deduplicated by
  (file, line, ruleId, cweFamily).
- Existing narrow checkers are **not** migrated; new
  mirrors go in `plumb-dfa`. A user wanting
  unified only can disable lightweight in YAML.

## 8. Implementation phases (R207–R215)

| Round | Output | LoC | Day |
|---|---|---|---|
| **R207** | (this design doc) | 0 | 0 |
| **R208** | Module skeleton, `EngineConfig`, `ConfigLoader` (YAML), 5 default YAMLs | ~800 | 1 |
| **R209** | `FactKind` + `Trait` (loads from YAML), `SinkFamilyRegistry` | ~500 | 0.5 |
| **R210** | `CallGraph` + `CallGraphBuilder` + `RPO`/`SCC` | ~600 | 1 |
| **R211** | `DfaEngine` + `DfaChecker` (interface) + `AnalysisContext` + `FindingReporter` | ~600 | 1 |
| **R212** | `FunctionSummary` + `ContextSensitiveAnalyzer` (k-CFA) | ~500 | 1 |
| **R213** | `TaintedChecker` base + `TaintDomain` + TaintChecker rewrite | ~800 | 1 |
| **R214** | `CrossFunctionConstantPropagation` | ~500 | 0.5 |
| **R215** | 5 mirror checkers: DivByZero / IntegerOverflow / NullDeref / ResourceLeak / SensitiveDataLeak | ~1500 | 2 |
| **R216** | `Z3Solver` (Java port) + `TacToZ3` + `LocalSolver` + `ReverseTaintWalker` | ~1500 | 2 |
| **R217** | End-to-end test: taint walk on a sample with SQL/LDAP/Command sinks producing 3 distinct CWE-tagged findings | ~300 | 0.5 |

Total: **~7600 LoC, ~10.5 days**.

## 9. Test strategy

- **Unit tests per checker**: existing narrow-test
  files re-run against the unified checker
  (e.g. `R194Layer3NullDerefTest.java` against the
  new `NullDereferenceChecker`). Expected: same TP
  count, lower FP.
- **Configuration tests**: load `sink-families.yaml`,
  assert SQL patterns match `Statement.execute`,
  LDAP patterns match `LdapContext.search`, etc.
- **End-to-end taint test** (R217): single source
  `HttpServletRequest.getParameter` flowing through
  3 sink call sites (SQL, LDAP, command) — expect
  3 distinct findings, each tagged with the right
  CWE family.
- **Cross-function test**: `validateNonZero(x);
  use(x);` should NOT fire DivByZero at the use
  site (summary `param[0] := KNOWN_NON_ZERO`).

## 10. Open questions

- **Z3 availability**: Z3 Java binding (`com.microsoft.z3`)
  is ~50MB. Should the unified engine require Z3, or
  fall back to LocalSolver (in-house, less precise)?
  Default: optional. Engine runs without Z3 (less
  precise, more conservative). Z3 unlocks
  `ReverseTaintWalker` and tighter path sensitivity.
- **Performance budget**: Fathom's `AnalysisEngine` on
  10K function benchmark is ~30s. Plumb's
  `plumb-main` already has TaintAnalysis at ~20s for
  50K functions. The unified engine should be ≤
  2× current budget. If not, add worklist-priority
  heuristics in a follow-up round.

## 11. Why this design

- **Config externalization** is a hard requirement from
  the user. Every aspect of the engine's behavior
  that a user might want to tune (sources, sinks,
  severity, fact kinds, checker enable/disable,
  context depth) is in YAML. No code change needed
  for the most common customization.
- **Fathom's layered design is well-tested**: the
  R130-R170 series produced a working engine that
  achieves 49/68 CWE per-file coverage on Juliet.
  Mirroring the architecture (not just the surface
  API) gives Plumb the same precision benefits.
- **Mirror module, not replace**: existing narrow
  checkers stay as a lightweight option, so users
  who don't need cross-function can keep their
  fast scans.
