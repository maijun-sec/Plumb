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
        "receiver's whole points-to set. Two limits are recorded rather than hidden: an "
        "explicit `x.f = null` is invisible because the fact domain has no null element, and "
        "the AST rule cannot follow an alias at all.",
    "04-polymorphic-callgraph":
        "**Dispatch is sound and type-precise.** Interface / abstract / diamond "
        "hierarchies resolve; overloads are filtered by parameter type, so an `int` "
        "argument no longer binds to a `String` overload, and an arity-2 overload in "
        "the same class resolves on its own key.",
    "05-real-cve-derived":
        "**CWE-502 and CWE-117 are both fully supported.** All four deserialization shapes "
        "fire and both mitigations stay silent; all five Log4Shell shapes fire, including "
        "the three-frame cross-method case. The CWE-117 support needed two fixes: the "
        "`log.sinks` family had no checker bound to it at all, and a logger held in a "
        "static field lowered to the callee signature `LOG.info/1` with no type, so no "
        "FQN-anchored sink pattern could match. Two cases remain, and they are genuine "
        "gaps: `CVE-915-coap-header` has no CWE-78 finding for the assembled command "
        "string, and `SQL-orderby-allowlist-tn` is a true false positive because an "
        "identifier allowlist is the only correct fix for ORDER BY and the engine has no "
        "allowlist-style sanitizer.",
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