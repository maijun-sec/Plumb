#!/usr/bin/env python3
"""Report the per-CWE coverage of a Juliet measurement, before and after the AI pass.

The measurement asks two questions per CWE:

  - how many of the TRUE POSITIVES does the engine find?
  - how many of the TRUE NEGATIVES does it stay quiet on?

and the ratio between them. The ratio is the number that says whether a
rule is usable: a rule that finds 10 of 10 true positives and fires on 8 of
10 true negatives is worse than useless, because the operator has to read
every finding.

The unit is the CASE, not the file. A Juliet-style fixture holds both a
vulnerable method and a fixed one, so a file-level count would mix them.

Two inputs:

  --baseline  the NDJSON of a scan WITHOUT the AI pass
  --ai        the NDJSON of a scan WITH the AI pass

The AI pass is scored by DROPPING the findings it judged FP. That is what
`ai.dropOnAiFp: true` does in the product, so measuring it that way measures
the product's behaviour rather than a proxy for it.

    python scripts/report_juliet_cwe.py --baseline baseline.ndjson --ai ai.ndjson
"""
from __future__ import annotations

import argparse
import json
import os
from collections import defaultdict
from pathlib import Path


def load(path):
    """The findings in an NDJSON file."""
    out = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return out


def cwes_of(finding):
    c = finding.get("cwe") or []
    if isinstance(c, str):
        c = [c]
    return [x for x in c if x]


def key_of(finding):
    """The identity of a finding: file, line, rule.

    The AI pass re-emits the same finding with two extra fields
    (`ai_verdict`, `ai_reason`), so the identity has to exclude them or the
    two files would not join.
    """
    return (
        os.path.basename((finding.get("file") or "").replace("\\", "/")),
        finding.get("line"),
        finding.get("rule_id"),
    )


def index(findings):
    """The findings keyed by identity."""
    out = {}
    for f in findings:
        out.setdefault(key_of(f), []).append(f)
    return out


def cwe_of(finding):
    """The finding's first CWE, or None."""
    c = cwes_of(finding)
    return c[0] if c else None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True)
    ap.add_argument("--ai")
    ap.add_argument("--cases", action="append", default=[],
                    help="an EXPECTATIONS.json to score against (repeatable)")
    args = ap.parse_args()

    baseline = load(args.baseline)
    ai_findings = load(args.ai) if args.ai else None

    # The AI pass DROPS the findings it judged FP. So the surviving set is
    # what the operator would actually see.
    if ai_findings is not None:
        kept = [f for f in ai_findings if (f.get("ai_verdict") or "") != "FP"]
    else:
        kept = None

    # ---- the case-level score, when manifests are given ---------------
    if args.cases:
        print("=" * 78)
        print("CASE-LEVEL SCORE (the unit is the case, not the file)")
        print("=" * 78)
        for manifest_path in args.cases:
            manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
            score(manifest, baseline, kept, manifest_path)

    # ---- the per-CWE histogram ----------------------------------------
    print()
    print("=" * 78)
    print("PER-CWE FINDING HISTOGRAM (what the engine reported)")
    print("=" * 78)
    hist = defaultdict(lambda: [0, 0])
    for f in baseline:
        for c in cwes_of(f):
            hist[c][0] += 1
    if kept is not None:
        for f in kept:
            for c in cwes_of(f):
                hist[c][1] += 1
    for c in sorted(hist, key=lambda k: -hist[k][0]):
        b, a = hist[c]
        if a >= 0:
            print("  %-16s baseline %5d   after-AI %5d   dropped %5d"
                  % (c, b, a, b - a))
        else:
            print("  %-16s baseline %5d" % (c, b))

    return 0


def score(manifest, baseline, kept, label):
    """Score one manifest against a finding set."""
    from importlib import import_module
    sys_path = Path(__file__).resolve().parent.parent / "testcase"
    import sys
    sys.path.insert(0, str(sys_path))
    run_category = import_module("run_category")

    findings = kept if kept is not None else baseline
    results = []
    for case in manifest["cases"]:
        hits = [f for f in findings if run_category.match(f, case)]
        if case["kind"] == "tp":
            status = "supported" if hits else "false-negative"
        else:
            status = "false-positive" if hits else "supported"
        results.append({**case, "status": status, "hits": len(hits)})

    excluded = ("unscorable", "unsupported", "known-limitation")
    tp_total = sum(1 for r in results if r["kind"] == "tp" and r["status"] not in excluded)
    tp_ok = sum(1 for r in results if r["kind"] == "tp" and r["status"] == "supported")
    tn_total = sum(1 for r in results if r["kind"] == "tn" and r["status"] not in excluded)
    tn_ok = sum(1 for r in results if r["kind"] == "tn" and r["status"] == "supported")

    print()
    print("--- %s ---" % label)
    print("  TP %d/%d   TN %d/%d" % (tp_ok, tp_total, tn_ok, tn_total))
    for r in results:
        if r["status"] not in ("supported",):
            print("    %-28s %-4s %-14s %s" % (r["id"], r["kind"], r["status"],
                                               r.get("file", "")))
    return results


if __name__ == "__main__":
    raise SystemExit(main())
