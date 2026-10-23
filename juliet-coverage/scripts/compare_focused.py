"""
compare_focused.py — Focused comparison on the subset of findings that M3 actually reviewed.

For each CWE with both baseline + m3 ndjson:
  - Find findings whose rule_id matches M3's review patterns
  - Among those, evaluate baseline TP/FP vs M3-drop-FP TP/FP
  - Compute precision delta and recall retention

This is a much smaller subset than all findings, so it evaluates in seconds.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import eval_findings
from compare_fast import build_basename_index, evaluate_findings


M3_PATTERNS = [
    "plumb.", "taint",
    "sensitivedataleak", "offbyone", "equalscalledonnull", "emptycatchhandler",
    "infiniteloop", "comparewithself", "badshift", "arraybounds", "hardcodedcred",
    "predictableseed", "predictablekey", "staticapikey", "missingauth",
    "missinginputvalidation", "informationexposure", "uncheckedreturn",
    "obsoletefunction", "weakcrypto", "insecurerandom", "insecurecomms",
    "corsmisconfig", "insecurecookie", "fieldhardcodedpassword", "xxe",
    "uncontrolledmemalloc", "numerictruncation", "iteratorconcurrentmod",
    "emptycatchblock", "unreachableassertion", "predicatablerandom",
]


def matches_pattern(rule_id: str) -> bool:
    rl = rule_id.lower()
    return any(p in rl for p in M3_PATTERNS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline-dir", type=Path, default=Path(r"D:\tmp\plumb_test\scan\baseline"))
    ap.add_argument("--m3-dir", type=Path, default=Path(r"D:\tmp\plumb_test\scan\m3"))
    ap.add_argument("--testcases-csv", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv"))
    ap.add_argument("--juliet-root", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\src\testcases"))
    ap.add_argument("--out-dir", type=Path, default=Path(r"D:\tmp\plumb_test\eval"))
    args = ap.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)

    print("[focused] loading testcases...", flush=True)
    t0 = time.time()
    testcases_by_file, testcases_by_cwe_method = eval_findings.load_testcases(args.testcases_csv)
    print(f"[focused] testcases loaded in {time.time()-t0:.1f}s", flush=True)

    print("[focused] building basename_index...", flush=True)
    t0 = time.time()
    basename_index = build_basename_index(args.juliet_root)
    print(f"[focused] basename_index built in {time.time()-t0:.1f}s ({len(basename_index)} unique filenames)", flush=True)

    rows = []
    m3_files = sorted(args.m3_dir.glob("CWE*_m3.ndjson"))
    for m3 in m3_files:
        stem = m3.stem.replace("_m3", "")
        scope = stem.split("_")[0]
        baseline = args.baseline_dir / (stem + ".ndjson")
        if not baseline.exists():
            print(f"  [{scope}] no baseline, skip", flush=True)
            continue

        t0 = time.time()
        # Read m3 ndjson, filter to M3-pattern findings
        m3_findings = []
        verdict_map = {}
        with m3.open("r", encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    row = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                rule_id = row.get("rule_id", "")
                if not matches_pattern(rule_id):
                    continue
                v = row.get("m3_verdict")
                if v is None:
                    continue
                f = eval_findings.Finding.from_nd(row)
                m3_findings.append(f)
                verdict_map[(f.file, f.line, f.rule_id)] = v

        # Read baseline ndjson for the same subset (filter to pattern + matching files)
        baseline_findings = []
        with baseline.open("r", encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    row = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                rule_id = row.get("rule_id", "")
                if not matches_pattern(rule_id):
                    continue
                f = eval_findings.Finding.from_nd(row)
                baseline_findings.append(f)

        # Evaluate baseline (subset)
        per_rule_b, per_cwe_b = evaluate_findings(
            baseline_findings, testcases_by_file, testcases_by_cwe_method,
            args.juliet_root, basename_index, scope_cwe_id=scope,
        )
        n_b = sum(e["n_findings"] for e in per_rule_b.values())
        tp_b = sum(e["tp"] for e in per_rule_b.values())
        fp_b = sum(e["fp_total"] for e in per_rule_b.values())

        # Evaluate M3 drop-FP
        kept_m, dfp, dunk, _ = filter_m3(m3_findings, verdict_map, keep={"TP", "UNKNOWN", "ERROR"})
        per_rule_m, per_cwe_m = evaluate_findings(
            kept_m, testcases_by_file, testcases_by_cwe_method,
            args.juliet_root, basename_index, scope_cwe_id=scope,
        )
        n_m = sum(e["n_findings"] for e in per_rule_m.values())
        tp_m = sum(e["tp"] for e in per_rule_m.values())
        fp_m = sum(e["fp_total"] for e in per_rule_m.values())

        # Evaluate M3 strict (drop FP + UNK)
        kept_s, dfp_s, dunk_s, _ = filter_m3(m3_findings, verdict_map, keep={"TP"})
        per_rule_s, per_cwe_s = evaluate_findings(
            kept_s, testcases_by_file, testcases_by_cwe_method,
            args.juliet_root, basename_index, scope_cwe_id=scope,
        )
        n_s = sum(e["n_findings"] for e in per_rule_s.values())
        tp_s = sum(e["tp"] for e in per_rule_s.values())
        fp_s = sum(e["fp_total"] for e in per_rule_s.values())

        prec_b = tp_b / n_b if n_b else 0
        prec_m = tp_m / n_m if n_m else 0
        prec_s = tp_s / n_s if n_s else 0
        recall_drop_fp = tp_m / tp_b if tp_b else 0
        recall_drop_fp_unk = tp_s / tp_b if tp_b else 0

        # Per-rule breakdown for this CWE
        for rule_id in sorted(set(per_rule_b.keys()) | set(per_rule_m.keys()) | set(per_rule_s.keys())):
            eb = per_rule_b.get(rule_id, {"tp": 0, "fp_total": 0, "n_findings": 0})
            em = per_rule_m.get(rule_id, {"tp": 0, "fp_total": 0, "n_findings": 0})
            es = per_rule_s.get(rule_id, {"tp": 0, "fp_total": 0, "n_findings": 0})
            n_baseline = eb["n_findings"]
            tp_baseline = eb["tp"]
            fp_baseline = eb["fp_total"]
            n_drop = em["n_findings"]
            tp_drop = em["tp"]
            fp_drop = em["fp_total"]
            n_strict = es["n_findings"]
            tp_strict = es["tp"]
            fp_strict = es["fp_total"]
            prec_baseline = tp_baseline / n_baseline if n_baseline else 0
            prec_drop = tp_drop / n_drop if n_drop else 0
            prec_strict = tp_strict / n_strict if n_strict else 0
            recall_drop = tp_drop / tp_baseline if tp_baseline else 0
            recall_strict = tp_strict / tp_baseline if tp_baseline else 0
            rows.append({
                "scope": scope,
                "rule_id": rule_id,
                "baseline_n": n_baseline,
                "baseline_tp": tp_baseline,
                "baseline_fp": fp_baseline,
                "baseline_precision": f"{prec_baseline:.4f}",
                "drop_fp_n": n_drop,
                "drop_fp_tp": tp_drop,
                "drop_fp_fp": fp_drop,
                "drop_fp_precision": f"{prec_drop:.4f}",
                "drop_fp_recall": f"{recall_drop:.4f}",
                "strict_n": n_strict,
                "strict_tp": tp_strict,
                "strict_fp": fp_strict,
                "strict_precision": f"{prec_strict:.4f}",
                "strict_recall": f"{recall_strict:.4f}",
                "fp_dropped": fp_baseline - fp_drop,
                "tp_dropped": tp_baseline - tp_drop,
            })

        # CWE-level summary row (use rule_id = "(scope)" for rollup)
        rows.append({
            "scope": scope,
            "rule_id": "(scope-rollup)",
            "baseline_n": n_b,
            "baseline_tp": tp_b,
            "baseline_fp": fp_b,
            "baseline_precision": f"{prec_b:.4f}",
            "drop_fp_n": n_m,
            "drop_fp_tp": tp_m,
            "drop_fp_fp": fp_m,
            "drop_fp_precision": f"{prec_m:.4f}",
            "drop_fp_recall": f"{recall_drop_fp:.4f}",
            "strict_n": n_s,
            "strict_tp": tp_s,
            "strict_fp": fp_s,
            "strict_precision": f"{prec_s:.4f}",
            "strict_recall": f"{recall_drop_fp_unk:.4f}",
            "fp_dropped": fp_b - fp_m,
            "tp_dropped": tp_b - tp_m,
        })
        dt = time.time() - t0
        print(f"  [{scope}] subset_baseline={n_b} (prec {prec_b:.3f}, tp {tp_b}); "
              f"drop-FP={n_m} (prec {prec_m:.3f}, tp {tp_m}, dropped {dfp}); "
              f"strict={n_s} (prec {prec_s:.3f}, tp {tp_s}) [{dt:.1f}s]", flush=True)

    out = args.out_dir / "compare_focused_per_rule.csv"
    with out.open("w", encoding="utf-8", newline="") as fh:
        if rows:
            wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            wr.writeheader()
            for r in rows:
                wr.writerow(r)
    print(f"[focused] wrote {out}", flush=True)


def filter_m3(findings, verdict_map, keep):
    kept = []
    dfp = 0
    dunk = 0
    for f in findings:
        v = verdict_map.get((f.file, f.line, f.rule_id))
        if v in keep:
            kept.append(f)
        elif v == "FP":
            dfp += 1
        elif v == "UNKNOWN":
            dunk += 1
    return kept, dfp, dunk, len(findings)


if __name__ == "__main__":
    main()