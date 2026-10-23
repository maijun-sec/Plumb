"""
compare_whitelist_vs_baseline.py — side-by-side baseline vs whitelist per-CWE.

Inputs:
  --baseline-csv    eval_whitelist/global_per_cwe.csv from baseline eval
  --whitelist-csv   eval_whitelist/global_per_cwe.csv from whitelist eval
  --out-csv         output comparison csv

Output schema (per row per CWE scope):
  scope, cwe_id,
  base_tp, base_fp, base_findings, base_precision,
  white_tp, white_fp, white_findings, white_precision,
  prec_delta, fp_reduction
"""

from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from pathlib import Path


def load_per_cwe(csv_path: Path) -> dict[tuple[str, str], dict]:
    """Return {(scope, cwe_id): {tp, fp, n_findings, precision}}."""
    out = {}
    with csv_path.open("r", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            key = (row["scope"], row["cwe_id"])
            out[key] = {
                "tp": int(row["tp"]),
                "fp": int(row["fp"]),
                "n_findings": int(row["n_findings"]),
                "precision": float(row["precision"]),
            }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline-csv", type=Path, default=Path(r"D:\tmp\plumb_test\eval\global_per_cwe.csv"))
    ap.add_argument("--whitelist-csv", type=Path, default=Path(r"D:\tmp\plumb_test\eval_whitelist\global_per_cwe.csv"))
    ap.add_argument("--out-csv", type=Path, default=Path(r"D:\tmp\plumb_test\eval_whitelist\compare_baseline_vs_whitelist.csv"))
    args = ap.parse_args()

    base = load_per_cwe(args.baseline_csv)
    white = load_per_cwe(args.whitelist_csv)
    all_keys = sorted(set(base) | set(white))

    rows = []
    for key in all_keys:
        scope, cwe_id = key
        b = base.get(key, {"tp": 0, "fp": 0, "n_findings": 0, "precision": 0.0})
        w = white.get(key, {"tp": 0, "fp": 0, "n_findings": 0, "precision": 0.0})
        prec_delta = w["precision"] - b["precision"]
        if b["fp"] > 0:
            fp_reduction = 1 - w["fp"] / b["fp"]
        elif w["fp"] == 0:
            fp_reduction = 0.0
        else:
            fp_reduction = -1.0  # whitelist introduced FP
        rows.append({
            "scope": scope,
            "cwe_id": cwe_id,
            "base_tp": b["tp"],
            "base_fp": b["fp"],
            "base_findings": b["n_findings"],
            "base_precision": f"{b['precision']:.4f}",
            "white_tp": w["tp"],
            "white_fp": w["fp"],
            "white_findings": w["n_findings"],
            "white_precision": f"{w['precision']:.4f}",
            "prec_delta": f"{prec_delta:+.4f}",
            "fp_reduction": f"{fp_reduction*100:.1f}%",
        })

    args.out_csv.parent.mkdir(parents=True, exist_ok=True)
    with args.out_csv.open("w", encoding="utf-8", newline="") as fh:
        if rows:
            wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            wr.writeheader()
            for r in rows:
                wr.writerow(r)

    # Summary stats
    base_total_fp = sum(base.get(k, {"fp": 0})["fp"] for k in all_keys)
    white_total_fp = sum(white.get(k, {"fp": 0})["fp"] for k in all_keys)
    base_total_tp = sum(base.get(k, {"tp": 0})["tp"] for k in all_keys)
    white_total_tp = sum(white.get(k, {"tp": 0})["tp"] for k in all_keys)
    base_total_findings = sum(base.get(k, {"n_findings": 0})["n_findings"] for k in all_keys)
    white_total_findings = sum(white.get(k, {"n_findings": 0})["n_findings"] for k in all_keys)

    base_prec = base_total_tp / base_total_findings if base_total_findings else 0
    white_prec = white_total_tp / white_total_findings if white_total_findings else 0

    print(f"Wrote {len(rows)} rows to {args.out_csv}")
    print(f"\nBaseline:  findings={base_total_findings:,} tp={base_total_tp:,} fp={base_total_fp:,} prec={base_prec:.4f}")
    print(f"Whitelist: findings={white_total_findings:,} tp={white_total_tp:,} fp={white_total_fp:,} prec={white_prec:.4f}")
    print(f"\nFinding reduction: {100*(1-white_total_findings/base_total_findings):.2f}%")
    print(f"FP reduction: {100*(1-white_total_fp/base_total_fp):.2f}%" if base_total_fp else "")
    print(f"Precision delta: {100*(white_prec-base_prec):+.2f} pp")

    # Top improvements
    top_imp = sorted(rows, key=lambda r: float(r["prec_delta"]), reverse=True)[:10]
    print(f"\nTop 10 precision improvements:")
    for r in top_imp:
        print(f"  {r['scope']}/{r['cwe_id']}: {r['base_precision']} -> {r['white_precision']} (delta {r['prec_delta']}, fp {r['fp_reduction']})")

    # Top degradations
    top_deg = sorted(rows, key=lambda r: float(r["prec_delta"]))[:10]
    print(f"\nTop 10 precision degradations (often because whitelist removed a rule that WAS finding TPs):")
    for r in top_deg:
        print(f"  {r['scope']}/{r['cwe_id']}: {r['base_precision']} -> {r['white_precision']} (delta {r['prec_delta']}, fp {r['fp_reduction']})")


if __name__ == "__main__":
    main()