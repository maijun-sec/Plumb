#!/usr/bin/env python3
"""Write the Juliet measurement report.

The report answers one question per CWE, and the answer is one of FIVE
things -- not a number, because a number alone does not say what was
measured:

  MEASURED       the CWE's own rules ran and found the defect
  VALID ZERO     the CWE's own rules ran and found nothing -- a real zero
  CONFIG GAP     the engine has rules for this CWE, but the per-CWE config
                 enables NONE of them, so the coverage number says nothing
                 about the rule
  COVERAGE GAP   the engine has NO rule for this CWE at all
  NOT MEASURED   the scan did not finish, or produced no status

A report that collapses these into "recall 0.000" for the last three
claims the engine cannot find the defect when the truth is that nothing
was measured.

    python report_juliet.py --score <score.json> --audit <audit.json> \\
        --rule-cwes <tsv> --out <dir>
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
sys.path.insert(0, str(HERE))
import measure  # noqa: E402


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def classify(entry: dict, audit: dict | None) -> str:
    """The one-word outcome for a CWE."""
    if entry.get("status") != "measured":
        return "NOT MEASURED"
    has_own = bool(entry.get("engine_has_rule_for_cwe"))
    covered = audit.get("config_covers_own_rules") if audit else None
    if not has_own:
        return "COVERAGE GAP"
    if covered is False:
        return "CONFIG GAP"
    if entry.get("tp_methods", 0) > 0:
        return "MEASURED"
    return "VALID ZERO"


def pct(x) -> str:
    if x is None:
        return "n/a"
    return "%.3f" % x


def rule_counts(nd: Path) -> dict[str, int]:
    """The findings per rule id in one NDJSON file."""
    out: dict[str, int] = {}
    with nd.open("r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw:
                continue
            try:
                d = json.loads(raw)
            except json.JSONDecodeError:
                continue
            rid = d.get("rule_id") or "?"
            out[rid] = out.get(rid, 0) + 1
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--score", required=True)
    ap.add_argument("--audit", required=True)
    ap.add_argument("--rule-cwes", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default="")
    ap.add_argument("--compare-score", default="",
                    help="a second score.json, to diff against")
    ap.add_argument("--ai-pass", action="append", default=[],
                    help="an ai-pass.json to include (repeatable)")
    ap.add_argument("--ai-provider", action="append", default=[],
                    help="the provider that judged each ai-pass.json, in the "
                         "same order (repeatable)")
    ap.add_argument("--rule-diff", default="",
                    help="a second findings directory; the per-rule finding "
                         "counts are compared with this measurement's, which "
                         "is how the engine change is told apart from the "
                         "configuration change")
    ap.add_argument("--findings", default="",
                    help="this measurement's findings directory; needed by "
                         "--rule-diff")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    score = load_json(Path(args.score))
    audit_rows = load_json(Path(args.audit))
    audit = {str(r["cwe"]): r for r in audit_rows}

    compare = {}
    if args.compare_score:
        other = load_json(Path(args.compare_score))
        compare = {str(r["cwe"]): r for r in other}

    # rule id -> engine, and rule id -> CWE
    rule_cwes: dict[str, list[str]] = {}
    raw = Path(args.rule_cwes).read_bytes()
    text = None
    for enc in ("utf-8-sig", "utf-16", "utf-8"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text:
        for line in text.splitlines():
            parts = line.rstrip("\n").split("\t")
            if parts and parts[0]:
                rule_cwes[parts[0].strip()] = [measure.norm_cwe(c) for c in parts[1:] if c]

    lines: list[str] = []
    w = lines.append

    # ---------------------------------------------------------------- header
    w("# Juliet measurement")
    w("")
    if args.label:
        w(f"*{args.label}*")
        w("")
    w("The corpus is the real Juliet_Java testcase tree: %d CWE directories, "
      "%d testcase files."
      % (len(measure.cwe_dirs()),
         sum(1 for _ in measure.JULIET_ROOT.rglob("*.java"))))
    w("")
    w("**The unit is the method.** A Juliet file declares the vulnerable method "
      "(`bad*`) and the fixed one (`good*`). A finding inside a `bad*` method is a "
      "true positive; inside a `good*` method it is a false positive; a `good*` "
      "method with no finding is a true negative; a `bad*` method with no finding "
      "is a false negative.")
    w("")
    w("**Only a rule that is about the CWE under test is scored.** A finding from a "
      "rule labelled with another CWE is counted separately, because counting it as "
      "a false positive would measure the breadth of the per-CWE whitelist rather "
      "than the engine's precision.")
    w("")

    # ------------------------------------------------------------ categories
    cats: dict[str, list[dict]] = defaultdict(list)
    for e in score:
        c = classify(e, audit.get(str(e.get("cwe"))))
        e["outcome"] = c
        cats[c].append(e)

    w("## What each CWE's number means")
    w("")
    w("| outcome | count | meaning |")
    w("| --- | ---: | --- |")
    w("| MEASURED | %d | the CWE's own rules ran and found the defect |"
      % len(cats["MEASURED"]))
    w("| VALID ZERO | %d | the CWE's own rules ran and found nothing |"
      % len(cats["VALID ZERO"]))
    w("| CONFIG GAP | %d | the engine has rules for the CWE, the config enables "
      "none of them, so the coverage number says nothing about the rule |"
      % len(cats["CONFIG GAP"]))
    w("| COVERAGE GAP | %d | the engine has no rule for the CWE at all |"
      % len(cats["COVERAGE GAP"]))
    w("| NOT MEASURED | %d | the scan did not finish, or produced no status |"
      % len(cats["NOT MEASURED"]))
    w("")

    # -------------------------------------------------------------- the table
    w("## Per CWE")
    w("")
    w("| CWE | outcome | mode | own rules on | bad | good | TP | FN | FP | TN | "
      "TP cov | TN cov | prec |")
    w("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | "
      "---: | ---: | ---: |")
    for e in sorted(score, key=lambda x: int(x["cwe"]) if str(x["cwe"]).isdigit() else 0):
        cwe = e["cwe"]
        a = audit.get(str(cwe))
        own_on = "?"
        if a:
            own_on = "%d/%d" % (len(a.get("own_enabled") or []),
                               len(a.get("own_rules") or []))
        if e.get("status") != "measured":
            w("| CWE-%s | %s | %s | %s | - | - | - | - | - | - | - | - | - |"
              % (cwe, e["outcome"], e.get("whitelist_mode"), own_on))
            continue
        w("| CWE-%s | %s | %s | %s | %d | %d | %d | %d | %d | %d | %s | %s | %s |"
          % (cwe, e["outcome"], e.get("whitelist_mode"), own_on,
             e["methods_positive"], e["methods_negative"],
             e["tp_methods"], e["fn_methods"], e["fp_methods"], e["tn_methods"],
             pct(e.get("tp_coverage")), pct(e.get("tn_coverage")),
             pct(e.get("precision"))))
    w("")

    # ------------------------------------------------------------- aggregates
    measured = [e for e in score if e.get("status") == "measured"]
    tp = sum(e["tp_methods"] for e in measured)
    fn = sum(e["fn_methods"] for e in measured)
    fp = sum(e["fp_methods"] for e in measured)
    tn = sum(e["tn_methods"] for e in measured)
    w("## Totals over the measured CWEs")
    w("")
    w("| | methods |")
    w("| --- | ---: |")
    w("| vulnerable (`bad*`) | %d |" % (tp + fn))
    w("| covered by a finding | %d |" % tp)
    w("| missed | %d |" % fn)
    w("| fixed (`good*`) | %d |" % (fp + tn))
    w("| kept clean | %d |" % tn)
    w("| reported as a false positive | %d |" % fp)
    w("")
    w("TP coverage over the vulnerable methods: **%s** (%d of %d)"
      % (pct(tp / (tp + fn) if (tp + fn) else None), tp, tp + fn))
    w("")
    w("TN coverage over the fixed methods: **%s** (%d of %d)"
      % (pct(tn / (fp + tn) if (fp + tn) else None), tn, fp + tn))
    w("")

    # ------------------------------------------------------------- by engine
    w("## AST rules and DFA rules")
    w("")
    w("The two engines have different reachability, so their coverage is not "
      "interchangeable and is reported apart.")
    w("")
    tp_by_engine: Counter = Counter()
    fp_by_engine: Counter = Counter()
    xfile_tp = 0
    xfile_fp = 0
    for e in measured:
        for k, v in (e.get("tp_by_engine") or {}).items():
            tp_by_engine[k] += v
        for k, v in (e.get("fp_by_engine") or {}).items():
            fp_by_engine[k] += v
        xfile_tp += e.get("tp_cross_file_findings") or 0
        xfile_fp += e.get("fp_cross_file_findings") or 0
    w("| engine | true positives | false positives |")
    w("| --- | ---: | ---: |")
    for k in sorted(set(tp_by_engine) | set(fp_by_engine)):
        w("| %s | %d | %d |" % (k, tp_by_engine.get(k, 0), fp_by_engine.get(k, 0)))
    w("")

    w("## Cross-file reach")
    w("")
    w("A finding whose trace names a step in another file crossed a translation "
      "unit, so the global call graph reached it. Every other finding was decided "
      "inside one file.")
    w("")
    w("| | findings |")
    w("| --- | ---: |")
    w("| true positives whose trace leaves the file | %d |" % xfile_tp)
    w("| false positives whose trace leaves the file | %d |" % xfile_fp)
    w("")
    w("Juliet's testcases are single-file, so a cross-file trace here means the "
      "engine followed the value out of the file the finding is reported in -- it "
      "is not the corpus testing cross-file coverage.")
    w("")

    # ------------------------------------------------------- false positives
    w("## False positives by cause")
    w("")
    fp_by_rule: Counter = Counter()
    fp_methods_by_rule: Counter = Counter()
    off_cwe_by_rule: Counter = Counter()
    for e in measured:
        for k, v in (e.get("fp_by_rule") or {}).items():
            fp_by_rule[k] += v
        for k, v in (e.get("fp_by_rule") or {}).items():
            fp_methods_by_rule[k] += v
        for k, v in (e.get("off_cwe_by_rule") or {}).items():
            off_cwe_by_rule[k] += v
    w("| rule | engine | false-positive findings | CWEs affected |")
    w("| --- | --- | ---: | ---: |")
    cwe_by_rule: dict[str, set] = defaultdict(set)
    for e in measured:
        for k in (e.get("fp_by_rule") or {}):
            cwe_by_rule[k].add(e["cwe"])
    for k, v in fp_by_rule.most_common(25):
        w("| `%s` | %s | %d | %d |"
          % (k, k.split("-", 1)[0] if "-" in k else "?", v, len(cwe_by_rule[k])))
    w("")

    w("## Findings about another CWE")
    w("")
    w("These fired in the corpus but are not about the CWE under test, so they "
      "are neither a hit nor a miss here. Two very different sources are "
      "separated, because lumping them together would make a deliberate "
      "no-whitelist measurement look like engine noise.")
    w("")
    off_whitelist: Counter = Counter()
    off_all: Counter = Counter()
    for e in measured:
        target = off_all if e.get("whitelist_mode") == "all" else off_whitelist
        for k, v in (e.get("off_cwe_by_rule") or {}).items():
            target[k] += v
    w("| source | findings |")
    w("| --- | ---: |")
    w("| a whitelist config that enables a rule about another CWE | %d |"
      % sum(off_whitelist.values()))
    w("| a `mode: all` config, which enables every rule by design | %d |"
      % sum(off_all.values()))
    w("")
    w("The second row is not a defect: a `mode: all` config is a deliberate "
      "measurement of the whole catalogue, and every rule that fires is "
      "expected. The first row is a measurement of how broad the per-CWE "
      "whitelists are.")
    w("")
    if off_whitelist:
        w("| rule | findings in a whitelist config |")
        w("| --- | ---: |")
        for k, v in off_whitelist.most_common(12):
            w("| `%s` | %d |" % (k, v))
        w("")

    # ------------------------------------------------- configuration defects
    w("## The CWEs with no measurement to report")
    w("")
    w("**A CWE the engine has no rule for** -- a coverage gap, not a recall of "
      "zero. The rule would have to be written.")
    w("")
    gaps = sorted(cats["COVERAGE GAP"], key=lambda e: int(e["cwe"]))
    if gaps:
        for e in gaps:
            w("- CWE-%s (%d vulnerable methods, %d fixed)"
              % (e["cwe"], e.get("methods_positive", 0), e.get("methods_negative", 0)))
    else:
        w("none")
    w("")
    w("**A CWE whose own rules ran and found nothing** -- a valid zero. The rule "
      "is enabled, it ran, and it did not find the defect in these testcases.")
    w("")
    zeros = sorted(cats["VALID ZERO"], key=lambda e: int(e["cwe"]))
    if zeros:
        for e in zeros:
            w("- CWE-%s: %d vulnerable methods, %d fixed, rule(s) %s"
              % (e["cwe"], e.get("methods_positive", 0), e.get("methods_negative", 0),
                 ", ".join("`%s`" % r for r in e.get("engine_rules_for_cwe") or [])))
    else:
        w("none")
    w("")

    w("## Configuration defects")
    w("")
    dead: list[tuple[str, str]] = []
    gaps: list[str] = []
    partial: list[tuple[str, list[str]]] = []
    for cwe, a in sorted(audit.items(), key=lambda kv: int(kv[0]) if kv[0].isdigit() else 0):
        for e in a.get("dead_entries") or []:
            dead.append((cwe, e))
        if a.get("has_own_rules") and not a.get("config_covers_own_rules") \
                and not a.get("mode_all"):
            gaps.append(cwe)
        elif a.get("missing_own_rules"):
            partial.append((cwe, a["missing_own_rules"]))
    w("**A whitelist entry that selects no rule the engine emits** -- the entry "
      "is dead, and the CWE was measured without the rule it names.")
    w("")
    if dead:
        w("| CWE | dead entry |")
        w("| --- | --- |")
        for cwe, e in dead:
            w("| CWE-%s | `%s` |" % (cwe, e))
    else:
        w("none")
    w("")
    w("**A CWE whose own rules the config does not enable at all** -- the "
      "coverage number for these CWEs is uninterpretable, not zero.")
    w("")
    if gaps:
        w("CWE-" + ", CWE-".join(gaps))
    else:
        w("none")
    w("")
    w("**A CWE whose own rules are only partly enabled.**")
    w("")
    if partial:
        w("| CWE | own rules not enabled |")
        w("| --- | --- |")
        for cwe, missing in partial:
            w("| CWE-%s | %s |" % (cwe, ", ".join("`%s`" % m for m in missing)))
    else:
        w("none")
    w("")

    # ------------------------------------------------------- engine vs config
    if args.rule_diff:
        w("## The engine change and the configuration change, told apart")
        w("")
        w("Two things changed between the previous measurement and this one: "
          "the engine's parameter-origin fixpoint, and the per-CWE configs. "
          "A per-rule finding count tells them apart, because a rule that "
          "was already enabled keeps its count if the engine change was "
          "semantically neutral.")
        w("")
        old_dir = Path(args.rule_diff)
        rows = []
        engine_only = []
        for e in sorted(measured, key=lambda x: int(x["cwe"])):
            cwe = e["cwe"]
            old_nd = old_dir / f"CWE{cwe}.ndjson"
            new_nd = Path(args.findings) / f"CWE{cwe}.ndjson"
            if not old_nd.is_file() or not new_nd.is_file():
                continue
            old_counts = rule_counts(old_nd)
            new_counts = rule_counts(new_nd)
            # A `mode: all` config enables every rule, so it is the SAME
            # in both runs. Every difference in such a CWE is the engine.
            same_config = e.get("whitelist_mode") == "all"
            for rule in sorted(set(old_counts) | set(new_counts)):
                o = old_counts.get(rule, 0)
                n = new_counts.get(rule, 0)
                if o != n:
                    rows.append((cwe, rule, o, n, same_config))
                    if same_config:
                        engine_only.append((cwe, rule, o, n))
        w("| CWE | config | rule | before | after |")
        w("| --- | --- | --- | ---: | ---: |")
        for cwe, rule, o, n, same in rows:
            w("| CWE-%s | %s | `%s` | %d | %d |"
              % (cwe, "unchanged" if same else "changed", rule, o, n))
        w("")
        if engine_only:
            w("**The rows whose config did not change are the engine's "
              "doing.** A `mode: all` config enables every rule, so it is "
              "the same in both runs, and every difference in such a CWE "
              "comes from the fixpoint fix.")
            w("")
            w("Those differences are small but they are not zero, so the "
              "fixpoint fix is NOT purely a performance fix: keying the "
              "parameter origins by the merged key stopped one method's "
              "labels from leaking into another, and a few verdicts changed "
              "because of it.")
            w("")
        if not rows:
            w("No rule changed its finding count.")
            w("")

    # ------------------------------------------------------------- AI pass
    if args.ai_pass:
        w("## The AI false-positive pass")
        w("")
        ai_rows = []
        for i, p in enumerate(args.ai_pass):
            provider = (args.ai_provider[i] if i < len(args.ai_provider) else "?")
            for r in load_json(Path(p)):
                r["provider"] = provider
                ai_rows.append(r)
        if not ai_rows:
            w("No AI measurement was taken.")
            w("")
        else:
            w("The AI pass judges every finding the engine reports and drops the "
              "ones it calls false positives. For each CWE the SAME sample of "
              "Juliet files is scanned twice -- once with the AI off and once "
              "with it on -- so the only difference between the two numbers is "
              "the AI pass.")
            w("")
            w("**A rate-limited request is not a verdict.** The provider's plan "
              "has a request rate limit, and a rate-limited request comes back "
              "as an UNKNOWN, which is indistinguishable from the model being "
              "unsure. A CWE whose verdicts were rate-limited is reported as "
              "rate-limited, not as a measurement.")
            w("")
            w("| CWE | provider | TP before | TP after | FP before | FP after | "
              "TP cov before | TP cov after | other-CWE before | after | verdicts |")
            w("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
            tp_b = tp_a = fp_b = fp_a = 0
            other_b = other_a = 0
            for r in sorted(ai_rows, key=lambda x: int(x["cwe"])):
                b = r.get("before") or {}
                a = r.get("after") or {}
                if not b:
                    continue
                verdicts = r.get("verdicts") or {}
                vtext = ", ".join("%s=%d" % (k, v) for k, v in sorted(verdicts.items()))
                w("| CWE-%s | %s | %d | %d | %d | %d | %s | %s | %d | %d | %s |"
                  % (r["cwe"], r.get("provider", "?"),
                     b.get("tp_methods", 0), a.get("tp_methods", 0),
                     b.get("fp_methods", 0), a.get("fp_methods", 0),
                     pct(b.get("tp_coverage")), pct(a.get("tp_coverage")),
                     r.get("before_off_cwe", 0), r.get("after_off_cwe", 0),
                     vtext or "-"))
                tp_b += b.get("tp_methods", 0)
                tp_a += a.get("tp_methods", 0)
                fp_b += b.get("fp_methods", 0)
                fp_a += a.get("fp_methods", 0)
                other_b += r.get("before_off_cwe", 0)
                other_a += r.get("after_off_cwe", 0)
            w("")
            w("Over the sampled CWEs: true positives **%d -> %d**, false "
              "positives **%d -> %d**, findings about another CWE **%d -> %d**."
              % (tp_b, tp_a, fp_b, fp_a, other_b, other_a))
            w("")
            w("The last column is the pass's real effect on the report: a "
              "finding that is not about the CWE under test still costs the "
              "reader time, and the AI removes it whether or not the CWE's "
              "own false-positive count moves.")
            w("")
            rate = [r for r in ai_rows if r.get("rate_limited_requests")]
            if rate:
                w("**Rate-limited and therefore not measured:** "
                  + ", ".join("CWE-%s (%d requests)" % (r["cwe"],
                                                        r["rate_limited_requests"])
                              for r in rate))
                w("")
            nojudge = [r for r in ai_rows
                       if r.get("status") == "no_findings_to_judge"]
            if nojudge:
                w("**Nothing to judge:** the sample's scan produced no finding, "
                  "so the AI had nothing to review: "
                  + ", ".join("CWE-%s" % r["cwe"] for r in nojudge))
                w("")

    # ------------------------------------------------------------- comparison
    if compare:
        w("## Against the previous measurement")
        w("")
        w("Two things changed between this measurement and the previous one, "
          "and this table shows the COMBINED effect. It is not a measure of "
          "either change on its own:")
        w("")
        w("1. The engine's parameter-origin fixpoint is keyed by the merged "
          "key (enclosing class + signature) instead of the signature alone. "
          "Before the fix, every same-named method in the corpus shared one "
          "label map, so the fixpoint never converged and the taint labels "
          "of one method leaked into another.")
        w("2. The per-CWE configs are derived from the engine's own rule "
          "table instead of from one past scan. Before the fix, most of the "
          "CWEs had a rule the engine ships and a config that did not "
          "enable it, so their coverage number said nothing about the rule.")
        w("")
        w("A CWE whose true-positive count went from 0 to a positive number "
          "was almost certainly unmeasured before, not undetected.")
        w("")
        changed = []
        for e in sorted(score, key=lambda x: int(x["cwe"]) if str(x["cwe"]).isdigit() else 0):
            cwe = str(e["cwe"])
            if cwe not in compare:
                continue
            o = compare[cwe]
            if o.get("status") != "measured" or e.get("status") != "measured":
                continue
            a_tp, b_tp = o.get("tp_methods", 0), e.get("tp_methods", 0)
            a_fp, b_fp = o.get("fp_methods", 0), e.get("fp_methods", 0)
            if (a_tp, a_fp) != (b_tp, b_fp):
                changed.append((cwe, a_tp, b_tp, a_fp, b_fp))
        w("%d of %d CWEs changed their true-positive or false-positive method "
          "count." % (len(changed), len([c for c in compare])))
        w("")
        if changed:
            w("| CWE | TP before | TP after | FP before | FP after |")
            w("| --- | ---: | ---: | ---: | ---: |")
            for cwe, a_tp, b_tp, a_fp, b_fp in changed:
                w("| CWE-%s | %d | %d | %d | %d |" % (cwe, a_tp, b_tp, a_fp, b_fp))
        w("")

    text = "\n".join(lines) + "\n"
    (out_dir / "JULIET-REPORT.md").write_text(text, encoding="utf-8")
    print(text)
    print("written: %s" % (out_dir / "JULIET-REPORT.md"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
