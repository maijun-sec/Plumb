#!/usr/bin/env python3
"""Prove the per-CWE report agrees with the measurement harness.

`scripts/report_juliet_cwe.py` scores the Juliet cases, and
`testcase/run_category.py` scores them too. If the two disagree, one of
them is wrong -- and a report that disagrees with the harness is a
report nobody can trust, because the harness is the thing the project
already believes.

So the two are compared, case by case, and every disagreement is
reported. The comparison is made against the harness's own RESULT.json,
which is what the harness wrote when it ran -- not against a
recomputation of the harness's logic, because comparing an
implementation to a reimplementation of the same implementation proves
only that the two reimplementations agree.

    python scripts/check_juliet_report.py
"""
import json
import pathlib
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"

CATEGORIES = [
    "01-taint-crossfile",
    "02-domain-sensitive",
    "03-pointer-multilevel",
    "04-polymorphic-callgraph",
    "05-real-cve-derived",
    "06-framework-orms",
    "07-unchecked-return",
]


def harness_statuses(category):
    """The status the harness gave each case, from its own RESULT.json."""
    path = REPO / "testcase" / category / "RESULT.json"
    if not path.is_file():
        return None
    report = json.loads(path.read_text(encoding="utf-8"))
    return {c["id"]: c["status"] for c in report["cases"]}


def report_statuses(category):
    """The status the per-CWE report gives each case.

    The report is a library as well as a script, so it is imported
    rather than run -- the comparison is about its SCORING, not about
    its formatting.
    """
    sys.path.insert(0, str(REPO / "scripts"))
    import report_juliet_cwe

    baseline_dir = REPO / "_audit-scratch" / "baseline"
    base_path = baseline_dir / (category + ".ndjson")
    if not base_path.is_file():
        return None
    baseline = report_juliet_cwe.load(base_path)
    rows = report_juliet_cwe.score_category(
        category, baseline, None, report_juliet_cwe.live_rule_cwes())
    return {r["id"]: r["before_status"] for r in rows}


def main():
    if not JAR.is_file():
        print("ERROR: %s does not exist -- build it first" % JAR)
        return 1

    failures = []
    compared = 0

    for cat in CATEGORIES:
        harness = harness_statuses(cat)
        report = report_statuses(cat)
        if harness is None:
            print("SKIP  %s -- no RESULT.json from the harness" % cat)
            continue
        if report is None:
            print("SKIP  %s -- no baseline NDJSON" % cat)
            continue

        # The harness and the report must score the SAME case ids. A
        # case the harness knows and the report does not is a case the
        # report silently dropped, and a silently dropped case is a
        # number that looks better than it is.
        only_harness = set(harness) - set(report)
        only_report = set(report) - set(harness)
        for cid in sorted(only_harness):
            failures.append("%s: the harness scored %s but the report does "
                            "not have it" % (cat, cid))
        for cid in sorted(only_report):
            failures.append("%s: the report scored %s but the harness does "
                            "not have it" % (cat, cid))

        for cid in sorted(set(harness) & set(report)):
            compared += 1
            if harness[cid] != report[cid]:
                failures.append("%s %s: the harness says %s, the report says %s"
                                % (cat, cid, harness[cid], report[cid]))

    print("compared %d case(s) across %d categor(ies)" % (compared, len(CATEGORIES)))
    if failures:
        print()
        print("FAIL: %d disagreement(s) between the harness and the report"
              % len(failures))
        for f in failures[:40]:
            print("  %s" % f)
        if len(failures) > 40:
            print("  ... and %d more" % (len(failures) - 40))
        print()
        print("Two numbers that describe the same tree must agree. One of")
        print("them is measuring something else.")
        return 1

    print()
    print("OK: the per-CWE report scores every case exactly as the harness does")
    return 0


if __name__ == "__main__":
    sys.exit(main())
