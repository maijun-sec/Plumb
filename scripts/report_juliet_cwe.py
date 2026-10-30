#!/usr/bin/env python3
"""Report the per-CWE Juliet coverage, before and after the AI pass.

The measurement asks, per CWE:

  - how many TRUE POSITIVES does the engine find, out of how many exist;
  - how many TRUE NEGATIVES does it stay quiet on, out of how many exist;
  - the ratio between them.

and the same three numbers again after the AI false-positive pass, so the
pass can be judged on what it changed rather than on what it reported.

The unit is the CASE, not the file. A Juliet-style fixture holds both a
vulnerable method and a fixed one, so a file-level count would mix them.

THE SCORING IS THE HARNESS'S, NOT THIS SCRIPT'S.
`testcase/run_category.py` is the authority on what counts as a hit: it
knows about `anchor_fn`, about `status_override`, about the `line_from`
/ `line_to` range, and about which statuses are excluded from the
tallies. This script calls into it. A report that reimplemented the
matching would quietly disagree with the harness about the same tree,
and the two numbers would both look authoritative.

THE CWE OF A CASE
A case's CWE comes from two places, and the report says which:
  - the manifest's own `cwes` field, when the manifest names one; and
  - otherwise, the rule the case names -- looked up in the LIVE engine's
    rule -> CWE map, which the engine prints with `--list-rule-cwes`.

The live map is the authority, because a table kept beside the engine
drifts. A case whose rule the live map does not know is reported as
`(unmapped)` rather than being folded into some other CWE.

The AI pass is scored by DROPPING the findings it judged FP. That is what
`ai.dropOnAiFp: true` does in the product, so measuring it that way
measures the product's behaviour rather than a proxy for it.

    python scripts/report_juliet_cwe.py --baseline DIR [--ai DIR]
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "testcase"))
import run_category  # noqa: E402

CATEGORIES = [
    "01-taint-crossfile",
    "02-domain-sensitive",
    "03-pointer-multilevel",
    "04-polymorphic-callgraph",
    "05-real-cve-derived",
    "06-framework-orms",
    "07-unchecked-return",
]

# The statuses the harness excludes from the tallies. A case in one of
# them is not counted as a pass and not counted as a miss: the harness
# could not measure it, or the capability is absent by design.
EXCLUDED = ("unscorable", "unsupported", "known-limitation")

# The label for a case whose CWE could not be determined.
UNMAPPED = "(unmapped)"


def live_rule_cwes():
    """The engine's own rule id -> CWE map, read from the live engine.

    Read by RUNNING the engine, not by parsing a table beside it: a table
    maintained beside the engine drifts, and a report that attributes a
    case to a CWE the engine does not emit is measuring a different tool
    than the one that ships.

    The map is empty when the engine cannot be run. A missing map is not
    a reason to guess: the report says `(unmapped)` instead.
    """
    jar = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"
    if not jar.is_file():
        return {}
    try:
        proc = subprocess.run(
            ["java", "-jar", str(jar), "--list-rule-cwes"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            cwd=str(REPO), timeout=180,
        )
    except (OSError, subprocess.SubprocessError):
        return {}
    out = {}
    for line in proc.stdout.splitlines():
        parts = line.rstrip("\n").split("\t")
        if not parts or not parts[0]:
            continue
        out[parts[0]] = [c for c in parts[1:] if c]
    return out


def load(path):
    """The findings in an NDJSON file.

    The encoding is detected, not assumed. A file written by a PowerShell
    redirect is UTF-16, and reading it as UTF-8 raises -- but a reader that
    swallowed the error would report ZERO findings and score every case as a
    miss, which reads as "the rules stopped working" rather than "the file
    was not read".
    """
    path = Path(path)
    raw = path.read_bytes()
    text = None
    for encoding in ("utf-8-sig", "utf-16", "utf-8"):
        try:
            text = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        raise SystemExit("could not decode %s" % path)
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


def kept_findings(findings):
    """The findings the operator would still see after the AI pass.

    This is `ai.dropOnAiFp: true` applied to the findings themselves:
    a finding the AI judged FP is one the operator would never see.
    """
    return [f for f in findings if (f.get("ai_verdict") or "").upper() != "FP"]


def case_cwe(case, rule_cwes):
    """The CWE of a case, and where it came from.

    The manifest's own `cwes` wins: the manifest is the ground truth for
    what the case is about. Only when the manifest is silent does the
    rule's CWE stand in -- and then the answer comes from the LIVE
    engine, not from a table.

    Returns (cwe, source) where source is "manifest" or "rule:<id>" or
    "none".
    """
    manifest_cwes = case.get("cwes") or []
    if manifest_cwes:
        return manifest_cwes[0], "manifest"
    for rid in case.get("rule_ids") or []:
        cwes = rule_cwes.get(rid) or []
        if cwes:
            return cwes[0], "rule:%s" % rid
    return UNMAPPED, "none"


def harness_hits(findings, case):
    """The findings that satisfy this case, by the harness's rules.

    The harness's `match` does the file/line/rule/sink/label matching.
    The `anchor_fn` handling lives in `main`, not in `match`, so it is
    reproduced here exactly as the harness does it -- including the
    distinction between a sink callee anchor (matchable) and an
    enclosing-method anchor (not matchable, and therefore unscorable).
    """
    anchor = case.get("anchor_fn")
    if anchor and "/" not in anchor:
        # R1121: an anchor that names an enclosing method cannot be
        # matched, because a finding's `sink_function` is the SINK
        # CALLEE. The harness scores such a case `unscorable`.
        return []
    if anchor:
        scoped = [f for f in findings
                  if not run_category._is_lint(f)
                  and anchor in (f.get("sink_function") or "")]
        if not scoped:
            return []
        return [f for f in scoped if run_category.match(f, case)]
    return [f for f in findings if run_category.match(f, case)]


def case_status(case, hits, override):
    """The status the harness gives this case.

    Same branches, same reasons, as `run_category.main`.
    """
    anchor = case.get("anchor_fn")
    if anchor and "/" not in anchor:
        return "unscorable"
    if override == "unsupported" and not hits:
        return "unsupported"
    if override == "unreachable-known-limitation" and not hits:
        return "known-limitation"
    if override == "unreachable-known-limitation" and hits:
        return "known-limitation"
    if override == "known-limitation" and hits:
        return "known-limitation"
    if case["kind"] == "tp":
        return "supported" if hits else "false-negative"
    return "false-positive" if hits else "supported"


def score_category(category, baseline, ai, rule_cwes):
    """Score one category, before and after.

    The per-case scoring is the HARNESS's. Every rule the harness applies
    is listed in `run_category.py` next to the reason it exists.

    Returns one row per case.
    """
    manifest = json.loads(
        (REPO / "testcase" / category / "EXPECTATIONS.json").read_text(encoding="utf-8")
    )
    after = kept_findings(ai) if ai is not None else None

    rows = []
    for case in manifest["cases"]:
        before_hits = harness_hits(baseline, case)
        after_hits = harness_hits(after, case) if after is not None else None
        override = case.get("status_override")

        before_status = case_status(case, before_hits, override)
        after_status = (
            case_status(case, after_hits, override) if after_hits is not None else None
        )

        cwe, cwe_source = case_cwe(case, rule_cwes)
        rows.append({
            "id": case["id"],
            "kind": case["kind"],
            "cwe": cwe,
            "cwe_source": cwe_source,
            "file": case["file"],
            "line": case.get("line"),
            "before_status": before_status,
            "after_status": after_status,
            "before_hit": before_status == "supported" if case["kind"] == "tp"
            else before_status == "false-positive",
            "after_hit": (after_status == "supported" if case["kind"] == "tp"
                          else after_status == "false-positive")
            if after_status is not None else None,
            "hits_before": len(before_hits),
            "hits_after": len(after_hits) if after_hits is not None else None,
        })
    return rows


def counted(row):
    """Is this row in the tallies?

    A case the harness could not measure, or one whose capability is
    absent by design, is not counted as a pass and not counted as a
    miss. Counting an unscorable case as a pass would manufacture a
    precision number out of a blind spot.
    """
    return row["before_status"] not in EXCLUDED


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True, help="directory of baseline NDJSON")
    ap.add_argument("--ai", help="directory of AI NDJSON (optional)")
    ap.add_argument("--only", action="append", default=[])
    ap.add_argument("--json", help="write the rows to this JSON file")
    args = ap.parse_args()

    baseline_dir = Path(args.baseline)
    ai_dir = Path(args.ai) if args.ai else None

    rule_cwes = live_rule_cwes()
    if not rule_cwes:
        print("WARNING: the live rule -> CWE map could not be read; cases whose "
              "manifest does not name a CWE are reported as %s" % UNMAPPED)

    all_rows = []
    missing_ai = []
    for cat in (args.only or CATEGORIES):
        base_path = baseline_dir / (cat + ".ndjson")
        if not base_path.is_file():
            print("WARNING: no baseline for %s -- the category is left out of "
                  "the report" % cat)
            continue
        baseline = load(base_path)
        ai = None
        if ai_dir is not None:
            ai_path = ai_dir / (cat + ".ndjson")
            if ai_path.is_file():
                ai = load(ai_path)
                if not ai:
                    # An EMPTY AI file is an aborted run. It is reported
                    # as such, and the category is scored on the BASELINE
                    # only -- with the AI column marked as "no run" rather
                    # than as "no false positives".
                    missing_ai.append(cat)
                    ai = None
            else:
                missing_ai.append(cat)
        all_rows.extend(score_category(cat, baseline, ai, rule_cwes))

    if args.json:
        Path(args.json).write_text(
            json.dumps(all_rows, ensure_ascii=False, indent=2), encoding="utf-8")

    # ------------------------------------------------------------------
    # The per-CWE table.
    # ------------------------------------------------------------------
    print("=" * 100)
    print("PER-CWE COVERAGE  (the unit is the CASE: a vulnerable method and a fixed method)")
    print("=" * 100)
    print()
    has_ai = any(r["after_status"] is not None for r in all_rows)
    print("%-12s %-4s %6s  %-11s %-11s %-11s"
          % ("CWE", "kind", "cases", "TP before", "TP after", "TN before"))
    print("-" * 100)

    by_cwe = defaultdict(lambda: {"tp": [], "tn": []})
    for row in all_rows:
        if not counted(row):
            continue
        by_cwe[row["cwe"]][row["kind"]].append(row)

    summary = {"tp_total": 0, "tp_before": 0, "tp_after": 0,
               "tn_total": 0, "tn_before": 0, "tn_after": 0}
    for cwe in sorted(by_cwe):
        tps = by_cwe[cwe]["tp"]
        tns = by_cwe[cwe]["tn"]
        tp_before = sum(1 for r in tps if r["before_status"] == "supported")
        tp_after = sum(1 for r in tps if r["after_status"] == "supported")
        tn_before = sum(1 for r in tns if r["before_status"] == "supported")
        tn_after = sum(1 for r in tns if r["after_status"] == "supported")
        summary["tp_total"] += len(tps)
        summary["tp_before"] += tp_before
        summary["tp_after"] += tp_after
        summary["tn_total"] += len(tns)
        summary["tn_before"] += tn_before
        summary["tn_after"] += tn_after
        if tps:
            print("%-12s %-4s %6d  %-11s %-11s %-11s"
                  % (cwe, "TP", len(tps),
                     "%d/%d" % (tp_before, len(tps)),
                     "%d/%d" % (tp_after, len(tps)) if has_ai else "no run",
                     "-"))
        if tns:
            print("%-12s %-4s %6d  %-11s %-11s %-11s"
                  % (cwe, "TN", len(tns),
                     "-", "-",
                     "%d/%d" % (tn_before, len(tns))))

    # ------------------------------------------------------------------
    # The dropped true positives -- the number that decides whether the
    # pass is usable.
    # ------------------------------------------------------------------
    if has_ai:
        print()
        print("=" * 100)
        print("TRUE POSITIVES THE AI PASS DROPPED  (each one is a real finding the operator no longer sees)")
        print("=" * 100)
        dropped = [r for r in all_rows
                   if counted(r) and r["kind"] == "tp"
                   and r["before_status"] == "supported"
                   and r["after_status"] == "false-negative"]
        if not dropped:
            print("  none")
        for r in dropped:
            print("  %-12s %-30s %s:%s" % (r["cwe"], r["id"], r["file"], r["line"]))

    print()
    print("=" * 100)
    print("TOTALS")
    print("=" * 100)
    s = summary
    if has_ai:
        print("  true positives   before %d/%d   after %d/%d   (coverage %.1f%% -> %.1f%%)"
              % (s["tp_before"], s["tp_total"], s["tp_after"], s["tp_total"],
                 100.0 * s["tp_before"] / max(1, s["tp_total"]),
                 100.0 * s["tp_after"] / max(1, s["tp_total"])))
        print("  true negatives   before %d/%d   after %d/%d   (coverage %.1f%% -> %.1f%%)"
              % (s["tn_before"], s["tn_total"], s["tn_after"], s["tn_total"],
                 100.0 * s["tn_before"] / max(1, s["tn_total"]),
                 100.0 * s["tn_after"] / max(1, s["tn_total"])))
        print("  TP/TN ratio      before %.2f   after %.2f"
              % (s["tp_before"] / max(1, s["tn_before"]),
                 s["tp_after"] / max(1, s["tn_after"])))
    else:
        print("  true positives   before %d/%d   (coverage %.1f%%)"
              % (s["tp_before"], s["tp_total"],
                 100.0 * s["tp_before"] / max(1, s["tp_total"])))
        print("  true negatives   before %d/%d   (coverage %.1f%%)"
              % (s["tn_before"], s["tn_total"],
                 100.0 * s["tn_before"] / max(1, s["tn_total"])))
        print("  TP/TN ratio      before %.2f" % (s["tp_before"] / max(1, s["tn_before"])))
        print()
        print("  No AI run was compared. The 'after' columns are absent, not zero:")
        print("  an absent run measures nothing.")
    if has_ai:
        print("  dropped true positives: %d"
              % len([r for r in all_rows
                     if counted(r) and r["kind"] == "tp"
                     and r["before_status"] == "supported"
                     and r["after_status"] == "false-negative"]))

    # ------------------------------------------------------------------
    # The cases the harness could not measure, and the categories with
    # no AI run. Both are reported rather than dropped.
    # ------------------------------------------------------------------
    excluded_rows = [r for r in all_rows if not counted(r)]
    if excluded_rows:
        print()
        print("=" * 100)
        print("EXCLUDED FROM THE TALLIES  (%d case(s): the harness could not measure them, "
              "or the capability is absent by design)" % len(excluded_rows))
        print("=" * 100)
        for r in excluded_rows:
            print("  %-12s %-16s %-30s %s"
                  % (r["cwe"], r["before_status"], r["id"], r["file"]))

    if missing_ai:
        print()
        print("=" * 100)
        print("CATEGORIES WITH NO AI RUN  (scored on the baseline only)")
        print("=" * 100)
        for cat in missing_ai:
            print("  %s" % cat)
        print()
        print("  A missing AI run measures nothing. It is NOT a run in which")
        print("  the AI found no false positives.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
