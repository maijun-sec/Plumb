# R760 — Loose checker removal (Oct 2026)

## What
Deleted the entire loose-checker subsystem (path-insensitive, no-taint-required
heuristics) from the engine. Loose had been turned off by default in the prior
round but the code, configuration, and registration wiring were still present.

## Why
- Loose checkers were high-Recall / high-FP rules designed to be filtered by AI
  dedup. Empirically (R759-H audit), they contributed ~9k false positives per
  CWE corpus scan and almost zero unique TP on the Juliet v1.3 corpus.
- The strict `plumb.numeric.*` / `plumb.boundary.*` precise rules cover
  CWE-190/191 TP without loose, and `plumb.ast.*` + `CatchGenericException`
  cover class-level CWE-491/580/585 that loose used to surface via helperBad().

## Code changes

### Files removed
Moved to `_cleanup_trash/loose_checkers_2026_10_08/` (kept for history):
- `plumb-dfa/.../checkers/LooseIntOverflowChecker.java`
- `plumb-dfa/.../checkers/LooseIntUnderflowChecker.java`
- `plumb-dfa/.../checkers/LooseNumericTruncationChecker.java`
- `plumb-dfa/.../checkers/LooseArrayIndexChecker.java`
- `plumb-dfa/.../checkers/LooseDivByZeroChecker.java`
- `plumb-dfa/.../checkers/LooseFormatStringChecker.java`
- `plumb-dfa/.../checkers/LooseHttpResponseSplittingChecker.java`
- `plumb-dfa/.../checkers/LooseClassChecker.java`
- `plumb-dfa/.../checkers/LooseDanglingChecker.java`
- `plumb-dfa/.../checkers/LooseNumericCheckers.java` (was already a stub)

Note: `LooseArrayIndexChecker.java` was previously eaten by an R759-G
artifact — a regular file (not directory) named `_cleanup_trash` was created
in its place during the R759-G rollback. That artifact file has been removed
in this round; the actual checker source was never recoverable so it is
listed as removed-from-trash here.

### Engine code
- `plumb-main/.../ScanRunner.java` (buildDfaCheckers, ~30 lines): removed the
  `if (config.runLooseRules) { … }` block.
- `plumb-main/.../ScanConfig.java` (~12 lines): removed the
  `public boolean runLooseRules = …;` declaration.
- `plumb-main/.../ConfigLoader.java` (~5 lines): removed the
  `Object loose = firstNonNull(cm.get("loose"), cm.get("loose_rules"));`
  parse branch. Old YAML files with `loose: {enabled: true}` are silently
  ignored — the key is no longer read.

### Test fixtures
- `plumb-dfa/src/test/.../integration/R601JulietSweep.java`:
  removed 9 imports and 9 `new LooseXxxChecker()` instantiations.
  Replaced the comment block with a "R760 (Oct 2026): loose checkers
  removed entirely" note.

### Configuration
- `plumb-main/src/main/resources/default-config.yaml`: removed the
  `loose: {enabled: false, aiDedup: false}` block.
- `juliet-coverage/data/rule_id_migration.csv`: removed 9 loose→boundary
  mapping rows; replaced with a "R760" comment.
- `juliet-coverage/configs/CWE{129,134,197,369,89}.yaml`: stripped
  `- loose.X.Y` whitelist lines (5 files, 5 lines removed).

### Documentation
- `juliet-coverage/RULE-UNIFICATION-SPEC.md`: untouched — that line
  ("`loose.X.Y`: 9") is a Phase-2 inventory snapshot from R-PATCH v2,
  accurate history. Editing it would falsify the spec.

## Verification

### Compilation
```
mvn -DskipTests compile      → BUILD SUCCESS  (full module)
mvn -DskipTests -pl plumb-main -am package -q  → exit 0
```

### Juliet CWE-190 smoke test

| Config | Findings | Loose findings | Coverage Δ |
|--------|---------:|---------------:|-----------:|
| R759-F (yaml whitelist loose 删了, ScanConfig.runLooseRules=true) | 20,614 | 0 | baseline |
| R760 (loose checker deleted from code) | 20,614 | 0 | **0** |

`loose.*` ruleIds in the output: 0
`dfa.cwe{477,404,506,510,566}-*` ruleIds in the output: 0

Coverage identical to R759-F. Loose-removal is a no-op for the existing
JuliCWE-190 corpus scan.

## Operational principle (cross-cutting)

- Loose was a path-insensitive, no-taint family whose entire reason for
  existence was "high Recall + AI dedup压". When the AI dedup layer was
  never enabled by default and the strict rules covered the same TP without
  loose, the loose layer became a pure-FP injector. Future "broad-recall"
  attempts should be evaluated against strict-rule coverage first before
  adding new path-insensitive heuristics.