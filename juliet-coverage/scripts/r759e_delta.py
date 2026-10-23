"""
R759-E delta: compare baseline (R758) vs whitelist (R759-E) eval output.
"""
from __future__ import annotations

import csv
from collections import defaultdict
from pathlib import Path

OUT_DIR = Path(r"D:\tmp\plumb_test\r759e_eval_out")
per_cwe_csv = OUT_DIR / "global_per_cwe.csv"

# bucket[label][cwe_id] = (tp, fp, n_findings)
bucket: dict[str, dict[str, tuple[int, int, int]]] = defaultdict(dict)

with per_cwe_csv.open(encoding="utf-8") as fh:
    rd = csv.DictReader(fh)
    for row in rd:
        label = row["label"]
        cwe_id = row["cwe_id"]
        bucket[label][cwe_id] = (
            int(row["tp"]),
            int(row["fp"]),
            int(row["n_findings"]),
        )

print("=" * 90)
print("Per-CWE detail (focus)")
print("=" * 90)
print(f"{'CWE':<8} {'Label':<10} {'TP':>8} {'FP':>8} {'Findings':>10} {'Precision':>10}")
print("-" * 90)
focus = ["833", "835", "191", "90", "606", "566", "113", "15", "23", "36", "470", "78", "80", "89", "601", "190", "400", "476", "563", "506", "510", "315", "253", "404"]
for cwe_id in focus:
    has_baseline = cwe_id in bucket["baseline"]
    has_whitelist = cwe_id in bucket["whitelist"]
    if not has_baseline and not has_whitelist:
        continue
    for label in ("baseline", "whitelist"):
        if cwe_id not in bucket[label]:
            print(f"{cwe_id:<8} {label:<10} {'--':>8} {'--':>8} {'--':>10} {'-':>10}")
            continue
    bt, bf, bn = bucket["baseline"][cwe_id]
    wt, wf, wn = bucket["whitelist"][cwe_id]
    bp = bt / bn if bn else 0
    wp = wt / wn if wn else 0
    print(f"{cwe_id:<8} {'baseline':<10} {bt:>8} {bf:>8} {bn:>10} {bp:>10.4f}")
    print(f"{cwe_id:<8} {'whitelist':<10} {wt:>8} {wf:>8} {wn:>10} {wp:>10.4f}")
    dtp = wt - bt
    dfp = wf - bf
    print(f"{'':8} {'Δ':<10} {dtp:>+8} {dfp:>+8} {wn-bn:>+10}")
    print()

print()
print("=" * 90)
print("Aggregate")
print("=" * 90)
for label in ("baseline", "whitelist"):
    total_tp = sum(tp for cwe_id, (tp, fp, n) in bucket[label].items())
    total_fp = sum(fp for cwe_id, (tp, fp, n) in bucket[label].items())
    total_n = sum(n for cwe_id, (tp, fp, n) in bucket[label].items())
    prec = total_tp / total_n if total_n else 0
    print(f"{label:<10} TP={total_tp:>10} FP={total_fp:>10} Findings={total_n:>10} Precision={prec:.4f}")