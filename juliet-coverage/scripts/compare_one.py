"""
compare_one.py — Quick compare of baseline vs M3-filtered for a single CWE.

Usage: python -u compare_one.py --cwe CWE15
"""
import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import compare_baseline_vs_m3 as cmp_mod
import eval_findings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cwe", type=str, required=True, help="e.g. CWE15")
    ap.add_argument("--baseline-dir", type=Path, default=Path(r"D:\tmp\plumb_test\scan\baseline"))
    ap.add_argument("--m3-dir", type=Path, default=Path(r"D:\tmp\plumb_test\scan\m3"))
    ap.add_argument("--testcases-csv", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv"))
    ap.add_argument("--juliet-root", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\src\testcases"))
    args = ap.parse_args()

    # Find the matching ndjson files
    base = next(args.baseline_dir.glob(f"{args.cwe}_*.ndjson"), None)
    m3 = next(args.m3_dir.glob(f"{args.cwe}_*_m3.ndjson"), None)
    if not base:
        print(f"baseline not found for {args.cwe}")
        return
    if not m3:
        print(f"m3 not found for {args.cwe}")
        return

    scope = args.cwe
    print(f"[{args.cwe}] baseline={base.name} ({base.stat().st_size/1e6:.1f}MB)", flush=True)
    print(f"[{args.cwe}] m3={m3.name} ({m3.stat().st_size/1e6:.1f}MB)", flush=True)

    t0 = time.time()
    n_b, tp_b, fp_b, _, _, _ = cmp_mod.evaluate_with_m3_filter(
        base, args.testcases_csv, args.juliet_root, scope, verdict_keep_set=None
    )
    print(f"[{args.cwe}] baseline: n={n_b} tp={tp_b} fp={fp_b} prec={tp_b/n_b if n_b else 0:.3f} ({time.time()-t0:.1f}s)", flush=True)

    t0 = time.time()
    n_m, tp_m, fp_m, dfp, dunk, total = cmp_mod.evaluate_with_m3_filter(
        m3, args.testcases_csv, args.juliet_root, scope, verdict_keep_set={"TP", "UNKNOWN", "ERROR"}
    )
    print(f"[{args.cwe}] drop-FP: n={n_m} tp={tp_m} fp={fp_m} prec={tp_m/n_m if n_m else 0:.3f} (drop_fp={dfp}, drop_unk={dunk}, total={total}) ({time.time()-t0:.1f}s)", flush=True)

    t0 = time.time()
    n_s, tp_s, fp_s, dfp_s, dunk_s, _ = cmp_mod.evaluate_with_m3_filter(
        m3, args.testcases_csv, args.juliet_root, scope, verdict_keep_set={"TP"}
    )
    print(f"[{args.cwe}] drop-FP-UNK: n={n_s} tp={tp_s} fp={fp_s} prec={tp_s/n_s if n_s else 0:.3f} (drop_fp={dfp_s}, drop_unk={dunk_s}) ({time.time()-t0:.1f}s)", flush=True)


if __name__ == "__main__":
    main()