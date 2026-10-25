#!/usr/bin/env python3
"""Evaluate a plumb testcase category: run the scanner, score it against the
expectation manifest, and emit a machine-readable summary.

Usage:
    python run_category.py <category-dir> [--jar <path>] [--keep-raw] [--per-file]

    <category-dir> is e.g. testcase/01-taint-crossfile

The scorer reads EXPECTATIONS.json from the category directory:

    {
      "cases": [
        {
          "id": "TP-1",
          "kind": "tp" | "tn",
          "file": "SqlSink.java",
          "line": 24,
          "rule_ids": ["dfa-taint-sql-injection"],
          "cwes": ["CWE-89"],
          "note": "why this must / must not fire"
        }
      ]
    }

A TP passes when at least one finding matches file+line and matches at least one
of rule_ids (or cwes). A TN passes when NO finding matches.

Exit code is always 0: this is a measurement tool, not a CI gate.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"


LINT_PREFIXES = ("ast-style", "ast-naming", "ast-formatting", "ast-design",
                 "ast-bug", "ast-security", "ast-error")


def _is_lint(finding: dict) -> bool:
    """Lint / style findings also carry the enclosing method in `sink_function`.

    `ast-style-commentsindentation` reports `sink_function:
    vulnerableDeserialize(InputStream):Object`, which makes an `anchor_fn` that
    names an enclosing method match a STYLE finding and then fail the CWE check —
    scoring a real detection as a miss. Restrict anchor scoping to DFA findings.
    """
    return (finding.get("rule_id") or "").startswith(LINT_PREFIXES)


def run_scan(jar: Path, target: Path, per_file: bool, cg: str) -> list[dict]:
    """Run the plumb scanner over `target`, returning parsed findings.

    Per-file mode isolates each unit in its own temp directory before scanning.
    This is not cosmetic: `ScanRunner.maybeExpandForCrossTu` silently promotes a
    single-file `-i` request to a MERGED scan when the file's parent directory
    holds up to CROSS_TU_AUTO_EXPAND_LIMIT sibling .java files. Scanning each file
    in place therefore produced byte-identical results for every unit -- the whole
    directory was analysed each time -- which made per-file isolation look broken
    when it never happened. Copying into a fresh one-file temp dir gives the
    isolation the flag promises.
    """
    findings: list[dict] = []
    if per_file:
        units = sorted(target.rglob("*.java"))
    else:
        units = [target]

    with tempfile.TemporaryDirectory(prefix="plumb-perfile-") as tmpdir:
        tmp = Path(tmpdir)
        for idx, unit in enumerate(units):
            if per_file:
                # One isolated directory per unit: exactly one .java file inside,
                # so maybeExpandForCrossTu finds no siblings and stays single-file.
                iso = tmp / f"u{idx}"
                iso.mkdir(parents=True, exist_ok=True)
                shutil.copy2(unit, iso / unit.name)
                scan_target = iso
            else:
                scan_target = unit
            cmd = [
                "java", "-jar", str(jar),
                "-i", str(scan_target),
                "-f", "ndjson",
                "--cg-strategy", cg,
            ]
            proc = subprocess.run(
                cmd, capture_output=True, text=True,
                encoding="utf-8", errors="replace", cwd=REPO,
            )
            # The scanner reports the path it was given, not the original source
            # location. Rewrite it back so downstream matching against the
            # EXPECTATIONS manifest (which names real files) still works.
            for line in proc.stdout.splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    finding = json.loads(line)
                except json.JSONDecodeError:
                    # Console-format leakage or a truncated final line.
                    continue
                if per_file:
                    finding["file"] = unit.name
                findings.append(finding)
    return findings


def match(finding: dict, case: dict) -> bool:
    """Does this finding satisfy the case's file/line/rule constraints?

    Three independent constraints, all optional:

    * ``line`` pins the case to that exact source line.
    * ``sink_function`` pins it to the enclosing/callee method. Boundary and
      taint findings are reported at the operation site rather than at the call
      site, so a cross-method case cannot be pinned by the caller's line --
      the finding legitimately lands inside the callee. Pinning the method is
      what keeps such a case measurable instead of a false negative.
    * neither means file-scoped (used for TN cases that assert "nowhere in
      this file").
    """
    fpath = finding.get("file", "") or ""
    fname = os.path.basename(fpath.replace("\\", "/"))
    if fname != case["file"]:
        return False

    rule_ids = case.get("rule_ids")
    cwes = case.get("cwes")
    line_required = case.get("line") is not None
    sink_fn = case.get("sink_function")

    rule_ok = True
    if rule_ids:
        rule_ok = finding.get("rule_id") in rule_ids
    cwe_ok = True
    if cwes:
        fc = finding.get("cwe") or []
        if isinstance(fc, str):
            fc = [fc]
        cwe_ok = any(c in fc for c in cwes)

    if rule_ids or cwes:
        # A case must not be satisfied by an unrelated finding that merely happens
        # to sit on the same line.
        #
        # R1102: this used to reject any rule_id starting with "ast-style" /
        # "ast-naming" / "ast-formatting". That silently threw away real security
        # findings: `ast-style-info-exposure-querystring` carries CWE-89 and is the
        # only rule that fires on the SqlSink prepared / builder / loop / context
        # shapes, because the DFA engine has no sink pattern for them. Four true
        # positives were scored as false negatives while the finding was present in
        # the output the whole time.
        #
        # The rule namespace does not track the severity: `ast-style-*` and
        # `ast-security-*` are both fine to satisfy a case when the manifest names
        # them. So trust the explicit rule_ids list, and fall back to the CWE list
        # only when no rule_id matched.
        rid = finding.get("rule_id") or ""
        if rule_ids:
            if rid not in rule_ids:
                return False
        elif cwes:
            fc = finding.get("cwe") or []
            if isinstance(fc, str):
                fc = [fc]
            if not any(c in cwes for c in fc):
                return False

    if sink_fn:
        actual = finding.get("sink_function") or ""
        if sink_fn not in actual:
            return False

    # The SOURCE LABEL is often the whole point of a case. A framework entry point
    # is supposed to be reported as "param:customer@spring-mvc:RequestParam" -- the
    # trust boundary it crossed -- and not as a bare "param:customer". Asserting only
    # that a finding EXISTS would pass for a model that produced the right NUMBER of
    # findings with the wrong origin, which is what a reader of the report actually
    # sees.
    #
    # The comparison is a substring test on the finding's dataflow head, so a case can
    # pin the exact label ("param:customer@quarkus-rest:RestQuery") or just the
    # framework part.
    label = case.get("dataflow_contains")
    if label:
        dataflow = finding.get("dataflow") or ""
        if label not in dataflow:
            return False

    # R1113: a line RANGE scopes a case to one method without needing a method name in the
    # finding. Findings carry a line but no enclosing-method field, so `anchor_fn` cannot be used
    # for rules that do not report through a sink (null-dereference among them), and a file-level
    # TN is unusable whenever the same fixture file also contains a genuine positive -- which it
    # must, or the positive half of the pair would be missing.
    #
    # The range is inclusive on both ends. A finding outside it does not match, which is exactly
    # what makes this a method-level assertion rather than a file-level one.
    lo = case.get("line_from")
    hi = case.get("line_to")
    if lo is not None or hi is not None:
        fline = finding.get("line")
        if fline is None:
            return False
        if lo is not None and fline < lo:
            return False
        if hi is not None and fline > hi:
            return False
        return True

    if line_required:
        return finding.get("line") == case["line"]
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("category")
    ap.add_argument("--jar", default=str(DEFAULT_JAR))
    ap.add_argument("--cg", default="global")
    ap.add_argument("--per-file", action="store_true",
                    help="scan each .java file separately instead of the whole directory")
    ap.add_argument("--keep-raw", action="store_true")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    cat = Path(args.category)
    if not cat.is_absolute():
        cat = REPO / cat
    jar = Path(args.jar)
    if not jar.exists():
        print(f"ERROR: jar not found at {jar}\nBuild it first:\n"
              f"  mvn -o -pl plumb-main -am -DskipTests -Dfile.encoding=UTF-8 -B install",
              file=sys.stderr)
        return 2

    src = cat / "src"
    manifest_path = cat / "EXPECTATIONS.json"
    if not manifest_path.exists():
        print(f"ERROR: no EXPECTATIONS.json in {cat}", file=sys.stderr)
        return 2
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    cases = manifest["cases"]

    # R1117: refuse to score a category whose TN gates filter on a CWE alone.
    #
    # A CWE is a CATEGORY, not an identity. Category-03 was caught by exactly
    # this: a TN filtered on `cwes: ["CWE-476"]` counted
    # `ast-bug-equals-avoid-null` -- a style rule that fires on the `null == x`
    # spelling of the test's OWN guard -- as a false positive of the alias
    # analysis, and three of twelve precision gates failed for that reason.
    #
    # This is a hard error rather than a warning because the failure mode is
    # silent in both directions: the gate reports a false positive that does not
    # exist, or hides a real one behind a passing count. Either way the number
    # stops measuring the rule it claims to measure.
    unscoped = [
        c["id"]
        for c in cases
        if c["kind"] == "tn" and not c.get("rule_ids")
    ]
    if unscoped:
        print(
            "ERROR: TN cases filter on a CWE with no explicit rule_ids: "
            + ", ".join(unscoped),
            file=sys.stderr,
        )
        print(
            "       Pin each to the rule it is meant to measure, e.g.\n"
            "         {\"id\": \"...\", \"kind\": \"tn\", \"rule_ids\": [\"dfa-...\"], ...}\n"
            "       Category-03's ast-bug-equals-avoid-null false positive is why.",
            file=sys.stderr,
        )
        return 2

    findings = run_scan(jar, src, args.per_file, args.cg)

    if args.keep_raw or args.out:
        raw = Path(args.out) if args.out else cat / "_raw.ndjson"
        with raw.open("w", encoding="utf-8") as fh:
            for f in findings:
                fh.write(json.dumps(f, ensure_ascii=False) + "\n")

    results = []
    for case in cases:
        # NOTE: a finding's `sink_function` is the SINK CALLEE signature
        # (e.g. "java.sql.Statement.executeQuery/1"), NOT the enclosing method.
        # So an `anchor_fn` naming an enclosing method (e.g.
        # `vulnerableDeserialize`) never matches `sink_function`.
        #
        # R1099: the engine does not currently expose the enclosing method on a
        # finding, so an anchor that names an enclosing method cannot be matched
        # reliably. Rather than silently scoring it as a miss, we treat it as
        # UNSCORED and say so: a harness that quietly turns an unmatchable case
        # into a false negative is more dangerous than one that admits it
        # cannot measure the case.
        anchor = case.get("anchor_fn")
        dfa_findings = [f for f in findings if not _is_lint(f)]
        # R1121: a finding's `sink_function` sometimes carries the ENCLOSING METHOD
        # with its signature rather than the sink callee (`runCommand(String,String):void`),
        # and some rules do not report through a sink at all. So a substring hit on an
        # enclosing-method anchor is NOT evidence that the anchored code was examined.
        #
        # The decision therefore cannot be "did any finding match this string" -- it has
        # to be "is this anchor a sink callee at all". `Class.method/arity` is the only
        # spelling that is guaranteed to be matched against a real sink.
        anchored_sink = bool(anchor) and "/" in anchor
        if anchor and not anchored_sink:
            # R1121: an UNSCORABLE TN is never a pass. For a TP, "the engine does not
            # expose the enclosing method" is a real, honest gap. For a TN it is the
            # opposite: the gate asserts the ABSENCE of a finding, and an absence the
            # harness cannot actually look for is a blind spot, not a measurement.
            # Scoring it `supported` manufactures a precision number out of that blind
            # spot -- which is how CVE-915-filtered-tn came to "pass" with a real
            # Runtime.exec planted in the very method it claimed to be measuring.
            status = "unscorable"
            detail = (
                "anchor_fn names an enclosing method, which plumb does not report as a "
                "sink callee; this case cannot verify what it claims to measure. Pin the "
                "real sink (anchor_fn containing '/') or use line_from/line_to."
            )
            results.append({**case, "status": status, "hits": 0,
                            "hit_lines": [], "detail": detail})
            continue
        if anchor:
            scoped = [f for f in dfa_findings
                      if anchor in (f.get("sink_function") or "")]
            if not scoped:
                # The anchor sink never fired. For a TP that is a false
                # negative (the chain produced no finding). For a TN, an
                # absent anchor is exactly the desired outcome, so it is a
                # pass -- NOT a false positive.
                status = "false-negative" if case["kind"] == "tp" else "supported"
                results.append({**case, "status": status, "hits": 0, "hit_lines": [],
                                "detail": f"anchor sink {anchor} produced no finding"})
                continue
            hits = [f for f in scoped if match(f, case)]
        else:
            hits = [f for f in findings if match(f, case)]

        # R1099: an explicit status_override lets a manifest declare a case's
        # capability ABSENT rather than let it masquerade as a regression.
        #
        # R1104: `known-limitation` covers the distinct case where the engine has the
        # capability and is behaving soundly, but the mitigation this fixture models is
        # not statically decidable. `replace("${", "")` removes one exact substring;
        # nested lookup syntax still gets through, and the real Log4Shell fix is a
        # runtime configuration cutoff. Scoring that as a false positive would say the
        # engine is wrong, which it is not -- so it is reported separately.
        override = case.get("status_override")
        if override == "unsupported" and not hits:
            results.append({**case, "status": "unsupported", "hits": 0,
                            "hit_lines": [],
                            "detail": "capability not implemented; measured as absent"})
            continue
        # R1113: `unreachable-known-limitation` is the mirror image of the branch below.
        #
        # `known-limitation` means "the engine HAS the capability and behaves soundly, but the
        # mitigation this fixture models is not statically decidable" -- so a finding is
        # expected and the case is scored outside the tallies. That does not describe a case
        # where the engine cannot represent the fact AT ALL: there, no finding is expected,
        # and without this branch the case falls through and is scored as a false-negative,
        # which reads as "the rule is broken" rather than "the fact domain cannot hold this".
        #
        # Category-03 `L6-unguarded-sibling-field` is the case that motivated it: the TAC is
        # correct (`FIELDSTORE dest=Const(NULL)`), but PointsToSolver's facts are sets of
        # abstract objects with no null element, so the null store records nothing.
        if override == "unreachable-known-limitation" and not hits:
            results.append({**case, "status": "known-limitation", "hits": 0,
                            "hit_lines": [],
                            "detail": "capability absent by design; the fact is not"
                                      " representable, so no finding is expected"})
            continue
        if override == "unreachable-known-limitation" and hits:
            # R1121: the gate says "this mitigation must silence the rule", but the engine
            # has no representation for the mitigating fact, so it cannot.
            #
            # R1113 introduced this override for the opposite polarity -- it assumed the
            # engine was SILENT where it should have been loud, so `not hits` was the only
            # case that mattered. That assumption is what made this branch unreachable here,
            # and an unreachable override is an invisible one: the case silently fell through
            # and scored as a false positive, which asserts the rule is WRONG rather than
            # INCOMPLETE.
            #
            # Both polarities are the same defect -- a mitigation the fact domain cannot hold --
            # so both are scored outside the tallies, with the hit lines kept visible.
            results.append({**case, "status": "known-limitation", "hits": len(hits),
                            "hit_lines": sorted({h.get("line") for h in hits}),
                            "detail": "mitigation modelled here is not representable in the "
                                      "fact domain, so the rule cannot honour it; the "
                                      "finding above is that missing capability, not a "
                                      "wrong result"})
            continue
        if override == "known-limitation" and hits:
            results.append({**case, "status": "known-limitation", "hits": len(hits),
                            "hit_lines": sorted({h.get("line") for h in hits}),
                            "detail": "engine is sound; the mitigation modelled here is not"
                                      " statically decidable"})
            continue
        if case["kind"] == "tp":
            status = "supported" if hits else "false-negative"
        else:
            status = "false-positive" if hits else "supported"
        results.append({**case, "status": status, "hits": len(hits),
                        "hit_lines": sorted({h.get("line") for h in hits})})

    # Aggregate
    counts = Counter(r["status"] for r in results)
    # R1099: "unscorable" cases are EXCLUDED from the tallies. Counting them as
    # misses would understate support; counting them as passes would overstate it.
    # R1104 adds "known-limitation" to the excluded set for the same reason.
    EXCLUDED = ("unscorable", "unsupported", "known-limitation")
    tp_total = sum(1 for r in results if r["kind"] == "tp" and r["status"] not in EXCLUDED)
    tn_total = sum(1 for r in results if r["kind"] == "tn" and r["status"] not in EXCLUDED)
    tp_ok = counts.get("supported", 0) if False else sum(
        1 for r in results if r["kind"] == "tp" and r["status"] == "supported")
    tn_ok = sum(1 for r in results if r["kind"] == "tn" and r["status"] == "supported")

    cwe_counts = Counter()
    for f in findings:
        c = f.get("cwe") or []
        if isinstance(c, str):
            c = [c]
        for one in c:
            cwe_counts[one] += 1

    report = {
        "category": cat.name,
        "scan_mode": "per-file" if args.per_file else "directory",
        "cg_strategy": args.cg,
        "total_findings": len(findings),
        "cases": results,
        "summary": {
            "tp_total": tp_total, "tp_supported": tp_ok,
            "tp_false_negative": tp_total - tp_ok,
            "tn_total": tn_total, "tn_supported": tn_ok,
            "tn_false_positive": tn_total - tn_ok,
        },
        "cwe_histogram": dict(cwe_counts.most_common()),
    }

    print(f"=== {cat.name} ===")
    # Print the manifest path, not just the category name. Two categories
    # with the same name (a copy in a scratch directory, say) are
    # indistinguishable otherwise, and a score reported against the
    # wrong manifest is worse than no score at all.
    print(f"manifest: {manifest_path}")
    print(f"findings: {len(findings)}   mode: {report['scan_mode']}   cg: {args.cg}")
    s = report["summary"]
    print(f"TP: {s['tp_supported']}/{s['tp_total']} supported, "
          f"{s['tp_false_negative']} false-negative")
    print(f"TN: {s['tn_supported']}/{s['tn_total']} supported, "
          f"{s['tn_false_positive']} false-positive")
    print()
    for r in results:
        mark = {"supported": "PASS", "false-negative": "FN  ", "false-positive": "FP  ",
                "unscorable": "SKIP", "unsupported": "N/A ",
                "known-limitation": "LIMIT"}[r["status"]]
        anchor = f" @{r['anchor_fn'].split('(')[0]}" if r.get("anchor_fn") else ""
        print(f"  [{mark}] {r['id']:<24} {r['kind']:<3} {r['file']}:{r.get('line')}{anchor}"
              f"  ({r['hits']} hits)")
    print()
    print("CWE histogram:")
    for c, n in list(cwe_counts.most_common())[:15]:
        print(f"  {c:<22} {n}")

    (cat / "RESULT.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())