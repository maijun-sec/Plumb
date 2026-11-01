#!/usr/bin/env python3
"""Generate the Juliet corpus support table from a measurement.

    python juliet-coverage/scripts/measurement/gen_support_table.py \
        --score <out>/score/score.json --out juliet-coverage/reports/corpus

The table is DERIVED, not maintained by hand. A hand-written table drifts
from the measurement it claims to describe, and a drift here is invisible:
the numbers look plausible, they are just about a different run.

Every category is placed by its OUTCOME, not by its numbers:

| outcome | meaning |
| --- | --- |
| MEASURED | the CWE's own rules ran and found the defect |
| VALID ZERO | the CWE's own rules ran and found nothing |
| CONFIG GAP | the engine has rules for the CWE, the config enables none of them |
| COVERAGE GAP | the engine has no rule for the CWE at all |
| NOT MEASURED | the scan did not finish |

A category with no rule is a COVERAGE GAP, and its numbers are zero because
there was no rule to run -- not because a rule ran and missed.
"""
import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent.parent

HEADER = """# Juliet corpus support

Every CWE category in the Juliet_Java testcase corpus, with what the
engine supports today.

**The unit is the method.** A Juliet file declares the vulnerable method
(`bad*`) and the fixed one (`good*`). A finding inside a `bad*`
method is a true positive; inside a `good*` method it is a false
positive.

**Only a finding whose rule is about the CWE under test is scored.** A
finding from a rule labelled with another CWE is counted separately,
because counting it as a false positive would measure the breadth of
the per-CWE whitelist rather than the engine's precision.

| column | meaning |
| --- | --- |
| rules on | the per-CWE config enables N rules, of which M are the engine's own rules for this CWE |
| vulnerable | the number of `bad*` methods in the corpus |
| TP / FN | vulnerable methods with / without a finding |
| FP | fixed (`good*`) methods that carry a finding |
| TP cov | TP / (TP + FN) |
| TN cov | fixed methods kept clean / all fixed methods |
| dTP / dFP | the change in TP and FP against the previous measurement |

A category with **no rule at all** is a coverage gap. Its numbers are
zero because there was no rule to run -- not because a rule ran and
missed.

"""

SECTIONS = [
    ("measured", "MEASURED -- Supported - the rule ran and found the defect"),
    ("valid_zero", "VALID ZERO -- the rule ran and found nothing"),
    ("config_gap", "CONFIG GAP -- the engine has the rule, the config enables none of it"),
    ("coverage_gap", "COVERAGE GAP -- the engine has no rule for this CWE at all"),
    ("not_measured", "NOT MEASURED -- the scan did not finish, so nothing was measured"),
]


def classify(e, audit):
    """The outcome of one CWE.

    The classification is the SAME one the corpus report uses, so the two
    documents cannot disagree about what a CWE's zero means:

    - not measured: the scan did not finish.
    - coverage gap: the engine has no rule for the CWE at all, so its
      numbers are zero because nothing ran.
    - config gap: the engine has the rule, the config enables none of it,
      so its numbers say nothing about the rule.
    - measured: the CWE's own rules ran and found the defect.
    - valid zero: the CWE's own rules ran and found nothing.
    """
    if e.get("status") != "measured":
        return "not_measured"
    if not e.get("engine_has_rule_for_cwe"):
        return "coverage_gap"
    covered = audit.get(str(e.get("cwe")), {}).get("config_covers_own_rules")
    if covered is False:
        return "config_gap"
    if e.get("tp_methods", 0) > 0:
        return "measured"
    return "valid_zero"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--score", required=True)
    ap.add_argument("--audit", default="",
                    help="the whitelist audit; the CONFIG GAP outcome is "
                         "read from it, because only the audit knows "
                         "whether the config enabled the CWE's own rules")
    ap.add_argument("--out", required=True)
    ap.add_argument("--baseline", default="",
                    help="a second score.json; the table then shows the "
                         "change in TP and FP coverage per CWE")
    args = ap.parse_args()

    score = json.loads(Path(args.score).read_text(encoding="utf-8"))
    audit = {}
    if args.audit:
        for r in json.loads(Path(args.audit).read_text(encoding="utf-8")):
            audit[str(r.get("cwe"))] = r
    baseline = {}
    if args.baseline:
        for e in json.loads(Path(args.baseline).read_text(encoding="utf-8")):
            baseline[str(e.get("cwe"))] = e

    buckets = {k: [] for k, _ in SECTIONS}
    for e in score:
        buckets[classify(e, audit)].append(e)

    out = [HEADER]
    for key, title in SECTIONS:
        rows = sorted(buckets[key], key=lambda x: int(x["cwe"]) if str(x["cwe"]).isdigit() else 0)
        out.append("### %s  (%d categories)\n\n" % (title, len(rows)))
        if not rows:
            out.append("none\n\n")
            continue
        if args.baseline:
            out.append("| CWE | config | rules on | vulnerable | TP | FN | FP | TP cov | TN cov | dTP | dFP |\n")
            out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n")
        else:
            out.append("| CWE | config | rules on | vulnerable | TP | FN | FP | TP cov | TN cov |\n")
            out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n")
        for e in rows:
            cwe = str(e["cwe"])
            own = len(e.get("engine_rules_for_cwe") or [])
            on = len(e.get("whitelist_rules") or [])
            mode = e.get("whitelist_mode", "?")
            pos = e.get("methods_positive", 0)
            tp = e.get("tp_methods", 0)
            fn = e.get("fn_methods", 0)
            fp = e.get("fp_methods", 0)
            tncov = e.get("tn_coverage")
            tpcov = e.get("tp_coverage")
            tpcov_s = "n/a" if tpcov is None else "%.3f" % tpcov
            tncov_s = "n/a" if tncov is None else "%.3f" % tncov
            if args.baseline:
                b = baseline.get(cwe)
                d_tp = f"{tp - b.get('tp_methods', 0):+d}" if b else "n/a"
                d_fp = f"{fp - b.get('fp_methods', 0):+d}" if b else "n/a"
                out.append("| CWE-%s | %s | %d/%d | %d | %d | %d | %d | %s | %s | %s | %s |\n"
                           % (cwe, mode, on, own, pos, tp, fn, fp, tpcov_s, tncov_s, d_tp, d_fp))
            else:
                out.append("| CWE-%s | %s | %d/%d | %d | %d | %d | %d | %s | %s |\n"
                           % (cwe, mode, on, own, pos, tp, fn, fp, tpcov_s, tncov_s))
        out.append("\n")

    out_path = Path(args.out) / "SUPPORT-TABLE.md"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("".join(out), encoding="utf-8")
    print("written: %s" % out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
