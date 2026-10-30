# The Juliet measurement: what it measures, and what it does not

[English](../README.md) | [中文](../zh/tech/JULIET-MEASUREMENT.md)

**Date:** 2026-10-30
**Scope:** the seven Juliet-derived categories under `testcase/`.

This document records what the measurement actually measures. It exists because the measurement
was changed during this work, and a number without the method that produced it is a number
nobody can trust.

---

## 1. What changed, and why

### 1.1 The rule set is narrowed at the source (point 3)

**Before:** the scan ran every rule the engine has. The measurement then decided, in the matcher,
which of the findings counted.

**After:** the scan runs exactly the rules the manifest's cases name, and nothing else. The
filter is the `--rule` flag, so the narrowing happens in the engine.

**Why:** a finding that should not have been considered at all is one that cannot be blamed on
the rule under test. When the whole rule set is in the output, every mistake in the matcher is a
mistake about *some* rule, and the report cannot tell which.

**Measured effect:** the baseline dropped from **3839 findings** to **120** across the seven
categories. The score did not change — TP and TN coverage are the same — which is the evidence
that the narrowing removed noise rather than detections.

### 1.2 The match is exact: the rule id AND the CWE (point 4)

**Before:** the matcher treated `rule_ids` and `cwes` as alternatives. A case that named
`rule_ids` ignored the CWE; a case that named only `cwes` ignored the rule.

**After:** a case names a rule id and/or a CWE, and a finding has to satisfy **both**. When a
case names one, the finding must match that one.

**Why:** this is the "a CWE-89 case satisfied by an LDAP-injection finding" mistake. A CWE-89
case satisfied by an LDAP-injection finding inflates recall, hides the false positives, and reports
a detection of a defect class that was never looked for.

### 1.3 The call graph is global (point 2)

The harness passes `--cg-strategy global`, and the engine's default is `global` (cross-TU
signature lookup). This was already true; it is now stated and recorded in each category's
`RESULT.json` as `cg_strategy`, so a number cannot be read without the strategy that produced it.

**But see section 3.** The global call graph is necessary and not sufficient: the cross-file
category does not currently exercise it.

---

## 2. The rule → CWE map is read from the live engine

The per-CWE table needs a rule → CWE mapping. The mapping is **not** a table kept beside the
engine: it is read from the engine itself, by running it:

```
java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar --list-rule-cwes
```

The map is built by `ScanRunner.liveRuleCweMap()`, from the same two lists the scan itself uses:
the AST checkers (`buildStyleChecks()`) and the DFA checkers (`buildDfaCheckers()`).

Three defects were found and fixed while building it:

1. **The map was keyed by the wrong name.** `RuleId` has two: `id()` is the emitted id
   (`ast-security-unchecked-return`) and `name()` is the legacy registry name
   (`UNCHECKED_RETURN`). The map used `name()`, so it contained **zero** of the ids the report
   actually prints. The map is now keyed by `id()`.

2. **The DFA checkers were missing.** `TaintRuleRegistry.builtins()` holds the *generic* taint
   rules, not the per-checker ones — each DFA checker constructs its own `TaintRule` inline. The
   map now reads the checker instances.

3. **Multi-rule checkers reported one rule.** `AstChecker.rule()` returns one rule, but
   `EmptyStatementBlockCheck` alone emits three, under three ids and three CWEs. `AstChecker`
   gained `allRules()`, and the four multi-rule checkers override it.

**Result:** all 155 rule ids the engine emits are in the map. 84 of them carry no CWE — those are
the style and lint rules, which genuinely have no CWE.

---

## 3. The cross-file category does not currently measure cross-file analysis

**This is the most important finding in this document, and it is a limitation of the ENGINE, not
of the measurement.**

Category `01-taint-crossfile` claims to measure a taint chain of "9 hops across 6 files". The
chain is real and well constructed:

```
SourceLayer.readRequestParam   (file 1)
  -> SourceLayer.wrap           (file 1, field store)
  -> Payload.readBack           (file 3, field load)
  -> ...
  -> TextOps.build              (file 5, StringBuilder)
  -> SqlSink.queryByName        (file 8, SINK)
```

### 3.1 The measurement

| scan | findings | dataflow |
|---|---|---|
| `SqlSink.java` **alone** | 3 | `taint: param:userName -> CWE-89` |
| the **whole directory**, `global` cg | 3 | `taint: param:userName -> CWE-89` |
| the **whole directory**, `global` cg, full trace | 4 steps | `source \| SqlSink.java:-1:-1 \| param:userName` |

The trace is **4 steps**, and the source step is at `SqlSink.java:-1:-1` — the sink file itself.
Scanning the file alone and scanning the whole directory produce **byte-identical results**. The
cross-file chain contributes nothing to the finding.

### 3.2 The reason

`TaintedCheckerBase.beginFunction` seeds **every method parameter** as a taint source:

```java
markSource(p, "param:" + p.name(), null, fn.signature(), "param:" + p.name());
```

`SqlSink.queryByName(Connection conn, String userName)` has a `String userName` parameter, so the
sink is reported on the strength of its own parameter. That is a deliberate conservative choice
— a method parameter might be attacker-controlled — but it means the finding does not depend on
the chain.

### 3.3 The chain's real source is not recognised

The chain's true origin is `SourceLayer.readRequestParam` → `HttpRequestSupport.getParameter`,
which the fixture documents as "**Stand-in for** `HttpServletRequest#getParameter`". It is a
*substitute*: it returns `"req:" + name` and never calls the servlet API.

The engine's source patterns require the receiver to end with `HttpServletRequest`:

```yaml
- '^((?:[\w.]+\.)*)HttpServletRequest\.getParameter(/\d+)?'
```

`HttpRequestSupport` does not match it. So the chain's origin is not a source, and there is
nothing for a cross-file propagation to carry.

### 3.4 The discriminator, measured

A probe fixture (`_audit-scratch/probe_crossfile_chain.py`) reproduces the chain but delivers the
value to the sink through a **field** instead of a parameter, so the sink method has no taintable
parameter at all:

**Result: 0 findings.** The only way to reach that sink is through the chain, and the chain
delivers nothing.

### 3.5 What this means for the numbers

The `01-taint-crossfile` TP cases measure **parameter taint reaching a sink**, not cross-file
propagation. They are real detections of a real defect, and they are worth what they measure — but
they are not what the category's name and its manifest notes claim.

The other taint categories are better placed:

| category | source labels observed | recognised source? |
|---|---|---|
| `01-taint-crossfile` | `param:*` only | **no** |
| `04-polymorphic-callgraph` | `input:RequestLike.getParameter/1`, `param:*` | partly |
| `05-real-cve-derived` | `input:Request.getParameter/1`, `sensitive:Request.getHeader/1` | **yes** |
| `06-framework-orms` | `param:customer@spring-mvc`, `param:params@carrier` | **yes** |

Categories 05 and 06 exercise the framework source recognition properly, and their traces carry
the recognised source label rather than a bare `param:`.

### 3.6 What would close the gap

Two independent things are missing, and they are separable:

1. **The fixture's stand-in is not a source.** Either the fixture should call the real
   `javax.servlet.http.HttpServletRequest.getParameter` (which needs the servlet API on the
   classpath), or the engine should recognise a user-defined stand-in. The first is a fixture
   change; the second is a capability.

2. **A cross-file chain with a recognised source does not reach the sink.** Even with a
   recognised source, the propagation has to survive the field store, the field load, the
   cross-file return, the container store and load, and the sanitiser-shaped helper. Whether it
   does is a separate measurement — and it is the measurement this category is named after.

**Recommendation:** the category's notes should say what it measures, or the fixture should be
changed so that the chain is the only possible explanation for the finding. A category that
measures parameter taint while claiming to measure cross-file taint is worse than no category,
because the number reads like a capability the engine does not have.

---

## 4. A second defect found in the same area: the rule filter selected two rules for one id

`--rule dfa-taint-null-deref-from-return` selected **two** rules:

| rule | CWE |
|---|---|
| `dfa-taint-null-deref-from-return` | CWE-476 |
| `ast-style-null-deref-from-return` | CWE-690 |

**Cause.** The filter accepts several spellings of a rule id, including the id with its leading
engine and domain tokens peeled off. Twelve pairs of live rules share a peeled spelling, and
**eight of those pairs carry different CWEs**:

```
catch-nullpointer-exception     ast-error-…  vs  ast-style-…
empty-catch-block               ast-error-…  vs  dfa-style-…
jumbled-incrementer             ast-bug-…     vs  ast-performance-…
literal-boolean-condition       ast-style-…  vs  dfa-taint-…
null-deref-from-return          ast-style-…  vs  dfa-taint-…
unchecked-loop-condition        ast-style-…  vs  dfa-taint-…
unused-private-field            ast-style-…  vs  dfa-taint-…
explicit-finalize               ast-error-…  vs  ast-style-…
```

The AST checker's own `name()` **is** the bare slug (`null-deref-from-return`), so the entry's
peeled spelling matched it.

**Effect on the measurement.** A TN case for CWE-476 was scored as a false positive because the
CWE-690 rule fired on the same line — a false positive that does not exist, and a recall number
that measures the filter rather than the rule.

**Fix.** A peeled spelling selects a rule only when it is **unambiguous**. An ambiguous bare slug
selects nothing, and the user names the rule they mean. The full id always selects exactly the
rule it names. The live rule set is read from the engine, so the check cannot drift.

`scripts/mutate_rule_filter_ambiguity.py` kills all three applicable mutants; the fourth is
reported as a NO-OP with the reason (see the script).

---

## 5. The AI pass

The AI pass has **not** been re-run against the corrected measurement. Five of the seven
categories have AI runs from an earlier build (`_audit-scratch/ai-run/`); categories 03 and 04
were aborted and hold no findings.

**Those earlier runs are NOT comparable with the new baseline**, for three reasons:

1. They were produced by a build without the rule-filter fix, so the findings carry the extra
   rule that `--rule` used to let through.
2. They were produced by a build without the exact-match fix, so a case could be satisfied by a
   finding of a different CWE.
3. They were produced by a scan that ran every rule, so the finding set is much larger.

`_audit-scratch/summarise_ai_runs.py` records what exists and marks the aborted runs as
aborted. An aborted run measures nothing; it is not a run in which the AI found no false
positives.

---

## 6. Reproducing this

```
# build
mvn -o -pl plumb-main -am -DskipTests -B install

# the baseline, per category, with the rules scoped and the match exact
python testcase/run_category.py testcase/<category> --per-file

# the per-CWE report
python scripts/report_juliet_cwe.py --baseline _audit-scratch/baseline

# the report agrees with the harness, case by case
python scripts/check_juliet_report.py
```
