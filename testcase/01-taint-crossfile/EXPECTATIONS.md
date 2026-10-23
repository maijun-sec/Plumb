# 01 — Cross-File / Cross-Method Taint Propagation

**Goal.** Measure whether the engine can carry one attacker-controlled value from a source
through **≥ 20 methods across 10 files** into a sink, and whether it can tell a *real* sanitizer
from a *fake* one when both are named like sanitizers.

## Why this shape

A taint engine that tracks only intra-procedural flow reports nothing here. A taint engine that
tracks interprocedural flow but loses taint at field boundaries reports only the chains that stay
in locals. Only an engine with **field-sensitive + summary-based + container-aware** propagation
finds the deep chains. Each hop below isolates one of those capabilities, so a partial failure
tells us *which* capability is missing rather than just "it missed".

## Fixture inventory

| # | File | Role | Hops added |
|---|------|------|-----------|
| 1 | `src/chain/SourceLayer.java` | the only taint origin | stream read, wrap |
| 2 | `src/chain/HttpRequestSupport.java` | source support code | ByteArrayOutputStream → String |
| 3 | `src/chain/Payload.java` | mutable holder | field store + field load, 2-level nesting |
| 4 | `src/chain/CollectionCarrier.java` | containers | List / Map / array store+load |
| 5 | `src/chain/TextOps.java` | string transforms | concat, substring, StringBuilder, regex, format, split/join |
| 6 | `src/chain/RequestContext.java` | getter-returned taint | constructor → getter → conditional merge |
| 7 | `src/chain/Validator.java` | real vs fake sanitizers | allowlist (kills taint) vs weak validate (keeps it) |
| 8 | `src/chain/SqlSink.java` | SQL sinks (CWE-89) | 7 distinct sink shapes |
| 8b | `src/chain/NonSqlSink.java` | path / cmd / log / XSS sinks | CWE-22, 78, 532, 79 |
| 9 | `src/chain/ChainOrchestrator.java` | wiring; no source, no sink | 5 full chains |
| 10 | `src/chain/ChainMidStage.java` | extra file boundary | return-value summary, ternary, loop, exception path |

## Expected findings

### Must report (true positives)

| ID | Entry | Sink | Why it is hard |
|----|-------|------|-----------------|
| TP-1 | `ChainOrchestrator.handleFullChain` | `SqlSink.queryByName` (CWE-89) | 9 hops, 6 files, field + list + fake-sanitizer + StringBuilder |
| TP-2 | `ChainOrchestrator.handleMapChain` | `SqlSink.queryByName` (CWE-89) | map store/load instead of list |
| TP-3 | `ChainOrchestrator.handleArrayChain` | `SqlSink.queryByName` (CWE-89) | array store/load + split/join |
| TP-4 | `ChainOrchestrator.handleDeepFieldChain` | `SqlSink.queryByName` (CWE-89) | 2-level object graph field read |
| TP-5 | `ChainOrchestrator.handleConditionalChain` | `SqlSink.queryByName` (CWE-89) | taint returned from a CFG join |

### Must NOT report (true negatives — these are the precision probes)

| ID | Entry | Why it must stay silent |
|----|-------|------------------------|
| TN-1 | `ChainOrchestrator.handleSanitizedChain` | `Validator.hardAllowlist` returns a **literal**; taint is genuinely dead at the sink |
| TN-2 | any chain through `TextOps.sanitize` | value is rebuilt from allowed chars only |

If TN-1 is reported, the engine has no sanitizer/kill-node modelling and every TP above is
suspect. If TP-1..5 are all silent, propagation dies before the first file boundary.

### Secondary sink families

`NonSqlSink` provides CWE-22 (path traversal), CWE-78 (command injection), CWE-532 (log
injection), CWE-79 (XSS) at the same chain depth. These are expected to be **less** uniformly
supported than SQL, because each sink family has its own regex over callee signatures.

## How support status is decided

For each expected finding, the harness records one of:

- **supported** — reported, at the expected file+line
- **partial** — reported but at a different location, or reported only for some chains in the group
- **false positive** — TN reported
- **false negative** — TP not reported
- **not applicable** — the sink family has no checker in this build

A category is **supported** only when every TP fires and every TN stays silent.