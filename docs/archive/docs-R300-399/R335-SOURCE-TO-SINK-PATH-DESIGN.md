# R335 Source-to-Sink Path in Data Flow Rules — Design

**Date**: 2026-08-10  
**Status**: DESIGN — implementation deferred to R335 cycle

## Goal

Add source-to-sink path information to data flow rule findings. Currently the
ndjson output for a finding is:

```json
{
  "file": "CWE78__connect_tcp_01.java",
  "line": 35,
  "column": 18,
  "severity": "high",
  "rule_id": "bu-0102-taint-sink-cmd",
  "rule_name": "OS_CMD_EXEC",
  "rule_type": "security",
  "cwe": ["CWE-78"],
  "message": "Tainted 'data' (bu-0001-taint-request-param) flows into Runtime.exec/1",
  "dataflow": "data ⊨?[bu-0001-taint-request-param]"
}
```

The `dataflow` field currently only lists the **taint sources** (which source
IDs contributed to this taint), not the **path** through the code. We want to
add:

```json
{
  "...": "...",
  "source": {
    "file": "CWE78__connect_tcp_01.java",
    "line": 28,
    "var": "data"
  },
  "path": [
    { "file": "...", "line": 28, "kind": "CALL",      "op": "request.getParameter",  "var": "data" },
    { "file": "...", "line": 30, "kind": "ASSIGN",    "op": "=",                     "var": "data" },
    { "file": "...", "line": 32, "kind": "BRANCH",    "op": "if (data != null)",     "var": "data" },
    { "file": "...", "line": 35, "kind": "CALL",      "op": "Runtime.exec/1",        "var": "data" }
  ]
}
```

## Design

### 1. New Types

```java
// In org.plumb.dfa
public record PathHop(
    Path file,        // source file
    int line,         // 1-based line number
    String kind,      // TacInstruction.Kind name (CALL, BRANCH, BINOP, ...)
    String op,        // human-readable operation summary
    String var,       // the TacVar name this hop touches
    Integer col       // optional column
) {}

public record SourceLocation(
    Path file,
    int line,
    String var,
    String sourceId  // which catalog source (e.g. "bu-0001-taint-request-param")
) {}

// Finding class extensions (additive)
public final class Finding {
    // ... existing fields ...
    private final SourceLocation source;  // nullable
    private final List<PathHop> path;      // nullable or empty
}
```

### 2. TaintFact Changes

Each `TaintFact` currently maps `TacOperand → Set<String>` (var → set of source IDs).

Add:
- Per-var path tracking: when taint enters a var, record the source location + first hop
- Path concatenation: when taint propagates, append a new hop

But — paths can be expensive (each propagation copies the list). Two-tier approach:

**Tier 1 (always)**: Track `source` only (Path, line, var, sourceId). One source per var (merge: first-wins or "earliest in CFG wins"). Cheap to propagate (single record).

**Tier 2 (opt-in)**: Track full `path`. Each propagation creates a new TaintFact with appended hop. Enable only when caller asks for path (e.g. via AnalysisContext flag or path-aware TaintFact subclass).

```java
// In TaintFact
public final class TaintFact {
    // ... existing ...
    private final Map<TacOperand, SourceLocation> sourceMap;  // tier 1: source per var
    
    public SourceLocation sourceOf(TacOperand var) { ... }
    
    public TaintFact withSource(TacOperand var, SourceLocation src) { ... }
}
```

### 3. TaintAnalysis Changes

When a taint source is added (e.g. `request.getParameter`):
- Record source location: `sourceMap.put(dest, new SourceLocation(file, line, dest, "bu-0001"))`

When taint propagates through a propagation instruction:
- Inherit source: `sourceMap.put(dest, sourceMap.get(srcVar))`

When taint reaches a sink:
- Emit Finding with `source = sourceMap.get(sinkArg)`, `path = []` (tier 1)
- Or `path = computePathFromTaintFact()` (tier 2)

### 4. NdjsonEmitter Changes

Add `source` and `path` fields to JSON output. Only emit when non-null/non-empty to keep backward compat.

```java
// In NdjsonEmitter
sb.append(",\"source\":{");
if (f.source() != null) {
    sb.append("\"file\":").append(jsonStr(f.source().file().toString()));
    sb.append(",\"line\":").append(f.source().line());
    sb.append(",\"var\":").append(jsonStr(f.source().var()));
    sb.append(",\"source_id\":").append(jsonStr(f.source().sourceId()));
}
sb.append("}");
```

### 5. Migration Strategy

- Phase 1: Add `source` only (tier 1). All existing tests should still pass (additive).
- Phase 2: Validate on a few Juliet CWEs. Verify `source` is correct.
- Phase 3: Add `path` (tier 2) for a subset of checkers (e.g. taint-sink only, not numeric).
- Phase 4: Extend to numeric checkers (CWE-190/191/129/369).

## Estimated Effort

| Phase | Effort | Output |
|---|---|---|
| 1 (source only, taint-sink) | 1 day | ndjson has `source` field for all taint-sink rules |
| 2 (validation) | 0.5 day | verify on CWE-78/89/15 |
| 3 (path, taint-sink) | 1.5 days | ndjson has full `path` for taint-sink rules |
| 4 (extend to numeric) | 1.5 days | ndjson has source/path for numeric rules too |
| **Total** | **4.5 days** | |

## Implementation Notes

- **Performance**: Tier 1 source tracking is essentially free (one record per var, no copy on propagation). Tier 2 path tracking requires a copy per propagation — could double memory use for large programs. Mitigation: cap path length to last N hops (e.g. 20) or use persistent data structures (HAMT / vector trie).
- **Merged program**: source file may not match the sink file. Always emit the per-hop file path so the consumer can render full path.
- **Cross-function**: when taint propagates through a function call, the path crosses file/function boundaries. Mark these as a "call hop" in the path (kind=CALL, op=function_name).
- **Cross-TU (cross-class)**: similar to cross-function but with class name in op.
- **Backward compat**: when source/path is null, ndjson emission should skip these fields (don't emit `"source":null`).

## Cross-Project Pitfall Patterns

- **Per-var source map can grow unbounded**: a long-running program with many assignments to many vars can accumulate millions of entries. Mitigation: weak references or periodic cleanup at function exit.
- **Source location stamping at the right time**: must stamp source at the moment of source CALL, not at IR construction. Otherwise stale file/line from parser.
- **Taint merging**: when two tainted vars flow into one expression (`a + b` where both are tainted), the result has 2 sources but 1 path slot. Need to choose: first source, deepest source, or both (with separate findings per source).
- **Cycle in propagation**: a `while (data != null) { ... }` loop will add the BRANCH hop infinitely. Need to dedup consecutive identical hops or cap path length.
