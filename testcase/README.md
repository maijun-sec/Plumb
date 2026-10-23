# Plumb analysis test cases

Five independent fixtures, one per analysis capability, plus a scorer. Each category is
self-contained so its support verdict can be read and re-measured on its own.

| Directory | Capability under test |
|---|---|
| `01-taint-crossfile` | Taint propagation across ~20 methods and 10 files |
| `02-domain-sensitive` | Range / domain narrowing across 3-4 layers |
| `03-pointer-multilevel` | Field-, array- and container-sensitive alias analysis with context-sensitive cross-method returns |
| `04-polymorphic-callgraph` | Dispatch through interfaces, abstract classes, diamond inheritance |
| `05-real-cve-derived` | CVE-shaped real-world cases |

## Support matrix (measured)

See [SUPPORT-MATRIX.md](SUPPORT-MATRIX.md). Regenerate with:

```
python scripts/write_support_matrix.py
```

## Running a category

```
python testcase/run_category.py testcase/<category> [--per-file] [--cg <strategy>]
```

The scorer reads `EXPECTATIONS.json`, runs the fat jar
(`plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar`), and writes `RESULT.json` plus a
per-category `SUPPORT-STATUS.md`.

## Three things the harness had to get right

**1. Per-file isolation needs a fresh directory, not just `-i <file>`.**
`ScanRunner.maybeExpandForCrossTu` promotes a single-file `-i` request to a merged scan
whenever the file's parent directory holds up to 20 sibling `.java` files. Scanning each
unit where it lives therefore returned byte-identical results for every unit -- the whole
directory was analysed each time -- and made per-file isolation look broken when it never
happened. `--per-file` now copies each unit into its own single-file temp directory.

**2. `anchor_fn` names an enclosing method; plumb reports a sink callee.**
A finding's `sink_function` is the SINK signature (`java.sql.Statement.executeQuery/1`),
never the method containing the call. Cases that anchored on `handleFullChain` could
never match, so they were silently scored as misses even where detection worked. Such
cases are now pinned to the reported line, and any remaining unmatchable anchor is
reported as `unscorable` rather than as a false negative.

**3. Lint findings also carry `sink_function`.**
`ast-style-commentsindentation` reports `sink_function: vulnerableDeserialize(...)`, so an
enclosing-method anchor matched a STYLE finding and then failed the CWE check -- scoring a
real detection as a miss. Anchor scoping is now restricted to DFA findings.

## Case statuses

| Status | Meaning |
|---|---|
| `supported` | Expected behaviour observed |
| `false-negative` | TP case, no finding produced |
| `false-positive` | TN case, a finding was produced |
| `unsupported` | Capability measured as absent (declared via `status_override`) |
| `unscorable` | The case asks for something the output does not carry |
| `known-limitation` | Engine is sound; the modelled situation is not statically decidable |

`unsupported` and `unscorable` are excluded from the TP/TN tallies: counting them as
misses understates support, counting them as passes would overstate it.

`known-limitation` is excluded too, and it covers two genuinely different situations:

- **reachable** — the engine has the capability and behaves soundly, but the *mitigation* the
  fixture models cannot be decided statically. `LOG4SHELL-sanitized-tn`: the real Log4Shell
  fix is a runtime configuration cutoff.
- **unreachable** (`status_override: "unreachable-known-limitation"`) — the engine cannot
  *represent* the fact at all, so no finding is expected.
  `L6-unguarded-sibling-field`: an explicit `x.f = null` followed by a dereference of `x.f`
  is a real CWE-476, but `PointsToSolver`'s facts are sets of abstract objects with no null
  element, so the null store records nothing. An empty set has to keep meaning "unknown" —
  treating it as null would invent a finding for every unmodelled field read.

Both are listed in `SUPPORT-MATRIX.md` under "Known limitations", so a capability gap that
cannot be scored stays visible instead of surviving behind a green tally.

### Measuring a rule, not the absence of other rules

Every alias TN in category-03 pins `rule_ids: ["dfa-taint-null-deref-from-return"]`.
Without that, the unscoped CWE-476 filter also matched `ast-bug-equals-avoid-null` — a style
rule that fires on the `null == x` *spelling of the guard itself*. Three of twelve TN gates
were scored as false positives of the alias analysis when the only finding in range was a
correct style warning about the test's own guard. See
[`03-pointer-multilevel/R1113-null-field-limitation.json`](03-pointer-multilevel/R1113-null-field-limitation.json)
for the measurement behind the second limitation.

## Probes

Scratch probes are not kept in the tree. Every probe that ever proved anything was
folded into a durable regression gate under `plumb-dfa/src/test/java/`
(`R1094InterprocBoundaryTest`, `R1099*Test`, `R1100SinkArgIndexPrecedenceTest`)
with the defect and the measured before/after written into its javadoc. If a new
probe is needed, keep it out of the repository until it has become a test.