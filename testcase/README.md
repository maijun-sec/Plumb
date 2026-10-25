# Plumb analysis test cases

Six independent fixtures, one per analysis capability, plus a scorer. Each category is
self-contained so its support verdict can be read and re-measured on its own.

| Directory | Capability under test |
|---|---|
| `01-taint-crossfile` | Taint propagation across ~20 methods and 10 files |
| `02-domain-sensitive` | Range / domain narrowing across 3-4 layers |
| `03-pointer-multilevel` | Field-, array- and container-sensitive alias analysis with context-sensitive cross-method returns |
| `04-polymorphic-callgraph` | Dispatch through interfaces, abstract classes, diamond inheritance |
| `05-real-cve-derived` | CVE-shaped real-world cases |
| `06-framework-orms` | Spring JDBC and MyBatis sinks, with the dependency jars absent from the analysis classpath; Spring, Quarkus REST and Micronaut annotation-bound entry points |

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

**4. A finding can exist and still say the wrong thing.**
A framework entry point is supposed to be reported as `param:customer@spring-mvc:RequestParam`
-- the trust boundary it crossed -- and not as a bare `param:customer`. A case that only asserts
"a finding exists at this line" passes for a model that produced the right NUMBER of findings
with the wrong ORIGIN, and the origin is what a reader of the report actually sees. So a case
may pin the source label with `dataflow_contains`, a substring test on the finding's dataflow
head.

```json
{ "id": "TP-quarkus-framework-entry-point", "kind": "tp",
  "file": "QuarkusOrderResource.java", "line": 61,
  "rule_ids": ["dfa-taint-sql-injection"],
  "dataflow_contains": "param:customer@quarkus-rest:RestQuery" }
```

The plain-parameter controls use the same field to assert the **absence** of a qualifier, by
pinning the whole head (`"taint: param:customer ->"`), so a source of
`param:customer@quarkus-rest:RestQuery` would not satisfy them.

## Manifest fields

| Field | Meaning |
|---|---|
| `id` | The case's name, unique within the category |
| `kind` | `tp` (a finding is expected) or `tn` (no finding is expected) |
| `file` | The fixture file the finding must be in |
| `line` | The exact source line the finding must be reported at |
| `line_from` / `line_to` | An inclusive line RANGE, for scoping a case to one method |
| `rule_ids` | The canonical rule ids that may satisfy the case. Required for every TN |
| `cwes` | The CWE list that may satisfy the case, when the rule id is not pinned |
| `sink_function` | A substring of the finding's sink callee signature |
| `dataflow_contains` | A substring of the finding's dataflow head, e.g. the source label |
| `anchor_fn` | The sink callee the case is scoped to (`Class.method/arity`) |
| `status_override` | `unsupported` or `unreachable-known-limitation` |

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

`known-limitation` is excluded too. It means the engine is sound and the *mitigation* the
fixture models is not statically decidable — `LOG4SHELL-sanitized-tn`: the real Log4Shell fix
is a runtime configuration cutoff. Such cases are listed in `SUPPORT-MATRIX.md` under "Known
limitations", so a gap that cannot be scored stays visible instead of surviving behind a
green tally.

The harness also accepts `status_override: "unreachable-known-limitation"` for the mirror case:
the engine cannot *represent* the fact at all, so no finding is expected. It existed for exactly
one case — `L6-unguarded-sibling-field`, where an explicit `x.f = null` recorded nothing because
`PointsToSolver`'s facts were sets of abstract objects with no null element. R1114 closed that
gap, so no manifest uses the override today; it stays in the harness because "sound but
undecidable" and "not representable" are different things and the next gap will need it.

### Measuring a rule, not the absence of other rules

**Every TN gate pins an explicit `rule_ids`.** This is enforced, not merely documented:
`run_category.py` refuses to score a category containing a TN case with no `rule_ids`
(exit code 2, with the offending ids listed).

A CWE is a **category**, not an identity. Category-03 was caught by exactly this: a TN
filtered on `cwes: ["CWE-476"]` also matched `ast-bug-equals-avoid-null`, a style rule
that fires on the `null == x` *spelling of the test's own guard*. Three of twelve alias
precision gates were scored as false positives when the only finding in range was a
correct style warning. R1117 pinned the remaining three gates in category-05 — unharmed
only because each of those CWEs happened to have exactly one rule bound to it, which is
a property of today's rule table rather than of the manifest.

The failure is silent in both directions: the gate either reports a false positive that
does not exist, or hides a real one behind a passing count. Either way the number stops
measuring the rule it claims to measure, so the harness refuses rather than warns.

Measure a rule, not the absence of other rules.

## Probes

Scratch probes are not kept in the tree. Every probe that ever proved anything was
folded into a durable regression gate under `plumb-dfa/src/test/java/`
(`R1094InterprocBoundaryTest`, `R1099*Test`, `R1100SinkArgIndexPrecedenceTest`)
with the defect and the measured before/after written into its javadoc. If a new
probe is needed, keep it out of the repository until it has become a test.