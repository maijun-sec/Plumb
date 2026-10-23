"""
analyze_fp_by_rule.py — aggregate per-rule FP / precision across all CWEs.

Reads eval_whitelist/global_per_rule.csv, rolls up each rule_id across CWEs,
and sorts by absolute FP count desc. Output: a CSV of (rule_id, total_findings,
total_tp, total_fp, weighted_precision, n_cwes_with_finding, hit_count).
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-rule-csv", type=Path,
                    default=Path(r"D:\tmp\plumb_test\eval_whitelist\global_per_rule.csv"))
    ap.add_argument("--out-csv", type=Path,
                    default=Path(r"D:\tmp\plumb_test\eval_whitelist\fp_by_rule.csv"))
    args = ap.parse_args()

    by_rule: dict[str, dict] = defaultdict(lambda: {"tp": 0, "fp": 0, "n": 0, "cwes": set()})
    with args.per_rule_csv.open("r", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            rid = row["rule_id"]
            by_rule[rid]["tp"] += int(row["tp"])
            by_rule[rid]["fp"] += int(row["fp_total"])
            by_rule[rid]["n"] += int(row["n_findings"])
            by_rule[rid]["cwes"].add(row["scope"])

    rows = []
    for rid, e in by_rule.items():
        prec = e["tp"] / e["n"] if e["n"] else 0.0
        rows.append({
            "rule_id": rid,
            "total_findings": e["n"],
            "total_tp": e["tp"],
            "total_fp": e["fp"],
            "weighted_precision": f"{prec:.4f}",
            "n_cwes_hit": len(e["cwes"]),
            "cwe_hit_list": ",".join(sorted(e["cwes"])),
        })

    rows.sort(key=lambda r: r["total_fp"], reverse=True)

    args.out_csv.parent.mkdir(parents=True, exist_ok=True)
    with args.out_csv.open("w", encoding="utf-8", newline="") as fh:
        if rows:
            wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            wr.writeheader()
            for r in rows:
                wr.writerow(r)

    print(f"Wrote {len(rows)} rules to {args.out_csv}")
    print(f"\nTop 20 FP-heavy rules (across all 112 CWE):")
    print(f"{'rule_id':<35} {'total':>8} {'tp':>6} {'fp':>6} {'prec':>7} {'#cwe':>5}")
    for r in rows[:20]:
        print(f"  {r['rule_id']:<33} {r['total_findings']:>8} {r['total_tp']:>6} "
              f"{r['total_fp']:>6} {r['weighted_precision']:>7} {r['n_cwes_hit']:>5}")


if __name__ == "__main__":
    main()