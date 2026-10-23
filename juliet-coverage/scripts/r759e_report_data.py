"""
R759-E report data: CWE-833 deadlock CWE, CWE-835 infinite-loop, all-cwe delta.
"""
import csv
import json
from pathlib import Path
from collections import defaultdict

per_cwe_csv = Path(r"D:\tmp\plumb_test\r759e_eval_out\global_per_cwe.csv")
r759e_eval = Path(r"D:\tmp\plumb_test\r759e_eval")

# Read CSV
per_cwe: dict[str, dict[str, dict]] = defaultdict(dict)
with per_cwe_csv.open(encoding="utf-8") as fh:
    rd = csv.DictReader(fh)
    for row in rd:
        per_cwe[row["label"]][row["cwe_id"]] = {
            "tp": int(row["tp"]),
            "fp": int(row["fp"]),
            "n_findings": int(row["n_findings"]),
        }

# Count deadlock-ab-ba hits per ndjson (file-level)
def count_hits(ndjson_path: Path, substring: str) -> dict[str, int]:
    file_count: dict[str, int] = defaultdict(int)
    if not ndjson_path.exists():
        return file_count
    with ndjson_path.open(encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            if substring not in raw:
                continue
            try:
                obj = json.loads(raw)
            except json.JSONDecodeError:
                continue
            fn = obj.get("file", "")
            file_count[fn] += 1
    return file_count

c833_deadlock_files = count_hits(r759e_eval / "CWE833.ndjson", "deadlock-ab-ba")
c835_loop_files = count_hits(r759e_eval / "CWE835.ndjson", "cwe835-infinite-loop")

print("=" * 90)
print("R759-E Verified detection: file/rule breakdown")
print("=" * 90)
print()
print("CWE-833 deadlock-ab-ba (R759-E NEW):")
for f, n in sorted(c833_deadlock_files.items()):
    print(f"  {f.split(chr(92))[-1]:60s} hits={n}")
print(f"  TOTAL deadlock-ab-ba: {sum(c833_deadlock_files.values())} hits on {len(c833_deadlock_files)} files")
print()
print("CWE-835 cwe835-infinite-loop (R759-E NEW):")
for f, n in sorted(c835_loop_files.items()):
    print(f"  {f.split(chr(92))[-1]:60s} hits={n}")
print(f"  TOTAL cwe835-infinite-loop: {sum(c835_loop_files.values())} hits on {len(c835_loop_files)} files")
print()

# Per-CWE aggregate
print("=" * 90)
print("Per-CWE: R759-D (baseline) vs R759-E (whitelist)")
print("=" * 90)
print(f"{'CWE':<8} {'baseline TP':>14} {'whitelist TP':>14} {'Δ TP':>8} {'baseline FP':>14} {'whitelist FP':>14} {'Δ FP':>8}")
for cwe in ["833", "835", "191", "90", "606", "566"]:
    b = per_cwe["baseline"].get(cwe, {"tp": 0, "fp": 0})
    w = per_cwe["whitelist"].get(cwe, {"tp": 0, "fp": 0})
    dtp = w["tp"] - b["tp"]
    dfp = w["fp"] - b["fp"]
    print(f"{cwe:<8} {b['tp']:>14} {w['tp']:>14} {dtp:>+8} {b['fp']:>14} {w['fp']:>14} {dfp:>+8}")

# Aggregate total
tot_tp_b = sum(v["tp"] for v in per_cwe["baseline"].values())
tot_tp_w = sum(v["tp"] for v in per_cwe["whitelist"].values())
tot_fp_b = sum(v["fp"] for v in per_cwe["baseline"].values())
tot_fp_w = sum(v["fp"] for v in per_cwe["whitelist"].values())
print()
print(f"{'TOTAL':<8} {tot_tp_b:>14} {tot_tp_w:>14} {tot_tp_w - tot_tp_b:>+8} {tot_fp_b:>14} {tot_fp_w:>14} {tot_fp_w - tot_fp_b:>+8}")