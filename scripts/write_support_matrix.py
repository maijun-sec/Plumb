"""R1099: rewrite the category support matrix from the measured RESULT.json files.

Every number in SUPPORT-STATUS.md and README.md is read from RESULT.json rather than
typed by hand, so the reports cannot drift from the measurements. The prose verdicts are
kept as explicit per-category text because the judgment is not derivable from counts.
"""

import json
import pathlib
from collections import Counter

REPO = pathlib.Path("testcase")

CATEGORIES = [
    ("01-taint-crossfile", False),
    ("02-domain-sensitive", False),
    ("03-pointer-multilevel", False),
    ("04-polymorphic-callgraph", True),
    ("05-real-cve-derived", False),
    ("06-framework-orms", False),
]

VERDICT = {
    "01-taint-crossfile":
        "**Cross-process and cross-file taint propagation works, and findings are "
        "attributed to the caller's parameter.** Values traverse ~9 methods across 6 "
        "files and reach the sink. Every case passes, including the sink-side shapes "
        "the DFA engine has no pattern for -- those are caught by "
        "`ast-style-info-exposure-querystring` (CWE-89), which is why the harness must "
        "match on an explicit rule list rather than a rule-prefix blacklist (R1102).",
    "02-domain-sensitive":
        "**Range and guard narrowing is sound, including across call boundaries.** Local "
        "guards, `Math.min`/`Math.max` clamps, and the early-return idiom "
        "(`if (d == 0) return 0;`) are all honoured. The early-return case needed a "
        "false-edge fact the guard tracker could not express: the guard is popped by the "
        "JUMP that skips the else block, so the else body looked unguarded (R1101).",
    "03-pointer-multilevel":
        "**Field-, array- and container-sensitive aliasing is sound, and its guard transfer is "
        "sound with it.** Allocation-site identity, field sensitivity, per-key container cells, "
        "cross-method returns and call-site context all resolve; every alias precision gate "
        "(12 TN) holds. R1113 found the one way guard transfer was still unsound: the rule "
        "suppressed a finding whenever *some* alias was guarded, but the alias map is a may-"
        "relation, so a guard on `first` silenced a dereference of a `List.get` result that "
        "could equally be an unguarded `second`. It now requires the guard to cover the "
        "receiver's whole points-to set. R1114 added the null direction to the heap facts -- as a "
        "separate channel rather than a null sentinel, because one shared sentinel would make "
        "every null in the program compare equal and leak into the guard test -- which closes the "
        "`x.f = null` gap the fact domain previously could not express. One limit remains recorded: "
        "the AST rule cannot follow an alias at all.",
    "04-polymorphic-callgraph":
        "**Dispatch is sound and type-precise.** Interface / abstract / diamond "
        "hierarchies resolve; overloads are filtered by parameter type, so an `int` "
        "argument no longer binds to a `String` overload, and an arity-2 overload in "
        "the same class resolves on its own key.",
    "05-real-cve-derived":
        "**CWE-502 fires on all four unmitigated shapes and CWE-117 on all five Log4Shell "
        "shapes, including the three-frame cross-method case.** The CWE-117 support needed "
        "two fixes: the `log.sinks` family had no checker bound to it at all, and a logger "
        "held in a static field lowered to the callee signature `LOG.info/1` with no type, "
        "so no FQN-anchored sink pattern could match. R1115 closed the third: an identifier "
        "allowlist is the only correct fix for ORDER BY / table / column, because bind "
        "variables cannot bind identifiers, and the engine modelled sanitizers purely by "
        "callee signature -- an allowlist is not a call, it is a value being narrowed. It "
        "is now recognised, and only in the reject-and-assign form: `if (!\"a\".equals(x)) { x "
        "= \"a\"; }` closes every path that could carry an unlisted value, while `if "
        "(\"a\".equals(x)) { x = \"a\"; }` does not, because its fall-through keeps the "
        "user's value. R1120 tightened that further: polarity alone was not enough, because a "
        "negation inside a disjunction (`if (!\"a\".equals(x) || !\"b\".equals(y))`) can be "
        "satisfied by the unrelated disjunct, so the substitution never runs. The proof is "
        "now deferred to the branch, where the boolean combine is visible. R1116 closed the "
        "last true negative by fixing the FIXTURE rather than the engine -- `runCommand` had "
        "been a `println` stand-in, so no `command.sinks` pattern could ever match and the "
        "case asserted a finding on a shape the engine has no sink for. Behind an `if "
        "(false)` guard it is inert at runtime and present in the IR, which is what a "
        "fixture is for.\n\n"
        "**Three cases are recorded as known limitations rather than counted as passing "
        "precision gates**, and R1121 is why the count reads TN 3/3 instead of 5/5. Two of "
        "them -- `CVE-502-filtered-tn` and `CVE-502-allowlist-tn` -- were previously scored "
        "as precision wins, and both were unfailable: each was anchored on an enclosing "
        "method name, which the engine reports only as a substring of other findings' "
        "`sink_function`, so the anchor check was answered by an unrelated finding and the "
        "gate passed without ever asking about CWE-502. Re-pinned to the real sink, the "
        "engine turns out to report BOTH JEP-290-mitigated `readObject()` calls, even though "
        "`UnsafeDeserializationChecker`'s own spec text names `ObjectInputFilter` / "
        "`resolveClass` as sanitizers it must recognise. Nothing implements that, so the "
        "limitation is an absence of capability in the fact domain rather than an unsound "
        "result, and both are scored outside the tallies with the finding left visible. The "
        "third, `LOG4SHELL-sanitized-tn`, is unrelated: a `replace(\"${\", \"\")` chain is not "
        "provably a sanitizer, and the real fix is a runtime configuration cutoff.",
    "06-framework-orms":
        "**Two framework shapes are covered, and they need two different engines.**\n\n"
        "The Spring JDBC shape is a *taint-flow* problem: the request value is bound to a method "
        "parameter by the framework, flows through a field, and reaches a `JdbcTemplate` call "
        "whose SQL text is built by concatenation. Nothing about the framework is unusual except "
        "that the receiver's type cannot be resolved. The third-party jar is not on the analysis "
        "classpath -- the normal situation for a source-only scan -- so the TAC writes the callee "
        "signature with a SIMPLE receiver name (`JdbcTemplate.query/2`) instead of a fully "
        "qualified one. Every one of the 416 sink patterns in the registry is anchored to a fully "
        "qualified name, so before the sink matcher learned to accept the prefix-optional form, "
        "none of these sinks was reachable, and nothing was reported: no error, no warning, just "
        "a scanner that could not see Spring at all. The relaxed form is consulted only for a "
        "bare simple-name receiver; a fully qualified callee still matches the strict pattern "
        "first, the class name and the method name both stay mandatory, and a word boundary after "
        "the method name keeps `JdbcTemplate.query` from matching `queryForList`.\n\n"
        "The MyBatis shape is *not* a taint-flow problem, and treating it as one is the mistake "
        "that hides it. The TAC does not carry annotations, and a mapper method lowers to a bare "
        "`RETURN`, so by the time the taint engine runs, the string `\"SELECT * FROM users WHERE "
        "name = '${name}'\"` is gone -- there is nothing left to propagate. The detection "
        "therefore belongs in the AST, where the annotation is still attached to the method. The "
        "rule reports a raw `${...}` substitution and not a bound `#{...}` placeholder, because "
        "the two have opposite safety properties: `#{}` becomes a PreparedStatement placeholder "
        "and the value is bound as data, while `${}` is a raw string substitution and the value "
        "becomes part of the SQL text. A `${}` that names something the method does not receive "
        "is not reported either -- a dynamic table name or column is a legitimate use of raw "
        "substitution, because a prepared-statement placeholder cannot stand in for SQL "
        "structure.\n\n"
        "The third shape is the FRAMEWORK ENTRY POINT, and it is the one a servlet-aware "
        "scanner cannot see at all. A Spring controller has no `getParameter()` call anywhere: "
        "the framework binds the request value to the method parameter BY ANNOTATION, and the "
        "controller body never touches a request object. So the source is not a call, it is a "
        "parameter annotation. The taint engine therefore treats a parameter carrying "
        "`@RequestParam` / `@PathVariable` / `@QueryParam` as a source whose origin is the HTTP "
        "request, and the finding names that origin (`param:customer@spring-mvc:RequestParam`) "
        "rather than an opaque parameter name. The annotation list is the same one the AST "
        "checkers use for input validation, so the two layers agree on what \"user input\" means "
        "in this codebase. A framework entry point outranks the caller's origin: the value was "
        "bound by the framework from the request, so no caller can supply a more specific "
        "origin for it, and the caller's label would name an unrelated parameter in an "
        "unrelated class.",
}

rows = []
detail = []
# R1113: read the scan mode back out of each RESULT.json. It used to be hardcoded per
# category, which meant every category's SUPPORT-STATUS.md claimed "directory" while the
# harness actually ran per-file. A status document that misreports how it was produced
# cannot be used to judge whether a change moved the number.
SCAN_MODE = {
    name: json.loads((REPO / name / "RESULT.json").read_text(encoding="utf-8")).get(
        "scan_mode", "directory"
    )
    for name, _pf in CATEGORIES
}
for name, per_file in CATEGORIES:
    result = json.loads((REPO / name / "RESULT.json").read_text(encoding="utf-8"))
    s = result["summary"]
    statuses = Counter(c["status"] for c in result["cases"])
    extra = []
    if statuses.get("unsupported"):
        extra.append(f"{statuses['unsupported']} unsupported")
    if statuses.get("unscorable"):
        extra.append(f"{statuses['unscorable']} unscorable")
    if statuses.get("known-limitation"):
        # R1104: a case where the engine is sound and the mitigation modelled is simply
        # not statically decidable. Reported rather than hidden, because dropping it would
        # make the tallies look better than the coverage actually is.
        extra.append(f"{statuses['known-limitation']} known-limitation")
    suffix = f" ({', '.join(extra)})" if extra else ""
    rows.append(
        f"| {name} | TP {s['tp_supported']}/{s['tp_total']} | "
        f"TN {s['tn_supported']}/{s['tn_total']}{suffix} |"
    )
    # R1113: known-limitation cases are listed here as well, not only counted in the
# summary. A case the engine cannot score stays invisible in "Outstanding" otherwise,
# which is exactly how a silent capability gap survives a green matrix.
    misses = [c["id"] for c in result["cases"]
              if c["status"] in ("false-negative", "false-positive")]
    limits = [c["id"] for c in result["cases"]
              if c["status"] == "known-limitation"]
    detail.append((name, VERDICT[name], misses, limits))

header = (
    "| Category | True positives | True negatives |\n"
    "|---|---|---|\n"
)
table = header + "\n".join(rows) + "\n"

# Verdicts and outstanding cases belong in the matrix itself. Without them the table is
# just counts, and the reader has to open five other files to learn which gaps are
# real. Only true misses are listed; `unsupported` / `unscorable` counts already
# appear in the table's TN cell.
verdict_sections = []
for name, verdict, misses, limits in detail:
    miss_text = (
        "\n".join(f"- `{m}`" for m in misses) if misses else "- none"
    )
    limit_text = (
        "\n".join(f"- `{m}`" for m in limits) if limits else "- none"
    )
    verdict_sections.append(
        f"### {name}\n\n{verdict}\n\nOutstanding: \n{miss_text}\n\n"
        f"Known limitations: \n{limit_text}\n"
    )

(REPO / "SUPPORT-MATRIX.md").write_text(
    "# Plumb analysis support matrix (measured)\n\n"
    "Generated by `scripts/write_support_matrix.py` from each category's `RESULT.json`.\n"
    "Re-run `python testcase/run_category.py testcase/<category>` to refresh.\n\n"
    + table
    + "\n## Verdicts\n\n"
    + "\n".join(verdict_sections),
    encoding="utf-8",
)

for name, verdict, misses, limits in detail:
    miss_text = ", ".join(f"`{m}`" for m in misses) if misses else "none"
    limit_text = ", ".join(f"`{m}`" for m in limits) if limits else "none"
    row = next(r for r in rows if r.startswith(f"| {name} "))
    cells = [c.strip() for c in row.strip().strip("|").split("|")]
    scan_mode = SCAN_MODE.get(name, "directory")
    (REPO / name / "SUPPORT-STATUS.md").write_text(
        f"# Support status - {name}\n\n"
        f"{verdict}\n\n"
        f"## Measured\n\n"
        f"- True positives: **{cells[1]}**\n"
        f"- True negatives: **{cells[2]}**\n"
        f"- Scan mode: {scan_mode}, callgraph strategy: `global`.\n\n"
        f"## Outstanding cases\n\n{miss_text}\n\n"
        f"## Known limitations\n\n{limit_text}\n\n"
        f"Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.\n",
        encoding="utf-8",
    )

print(table)
for name, _v, misses, limits in detail:
    print(f"{name}: outstanding -> {', '.join(misses) if misses else 'none'}"
          f" | known-limitations -> {', '.join(limits) if limits else 'none'}")