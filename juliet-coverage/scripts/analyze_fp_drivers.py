"""
analyze_fp_drivers.py — for top FP rules, count how many findings are in
Main.java / ServletMain.java / abstract test harness files.

If most of a rule's FP comes from driver files, the fix is trivial:
skip driver files via Plumb's include/exclude glob.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--whitelist-dir", type=Path,
                    default=Path(r"D:\tmp\plumb_test\scan\whitelist_v2"))
    ap.add_argument("--rules", type=str, nargs="*",
                    default=[
                        "CatchGenericException",
                        "UncheckedReturn",
                        "plumb.resource.leak",
                        "plumb.pointer.null-dereference",
                        "plumb.numeric.integer-overflow",
                        "plumb.dfa.insecure-cookie",
                        "InsecureCommunication",
                    ])
    ap.add_argument("--top-driver", type=int, default=10)
    args = ap.parse_args()

    # file basename bucket → count
    by_rule: dict[str, Counter] = {r: Counter() for r in args.rules}
    by_rule_total: Counter = Counter()
    for nd in sorted(args.whitelist_dir.glob("CWE*.ndjson")):
        try:
            with nd.open("r", encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except Exception:
                        continue
                    rid = obj.get("rule_id") or ""
                    if rid not in args.rules:
                        continue
                    file_path = Path(obj.get("file", ""))
                    by_rule_total[rid] += 1
                    # Bucket: is this a driver?
                    base = file_path.name
                    if base in ("Main.java", "ServletMain.java"):
                        bucket = "DRIVER"
                    elif "testcasesupport" in str(file_path):
                        bucket = "support"
                    else:
                        bucket = base
                    by_rule[rid][bucket] += 1
        except Exception:
            continue

    print(f"\nTop FP rule analysis — driver/support vs test case files")
    print(f"{'rule_id':<35} {'total':>7} {'DRIVER':>7} {'support':>7} {'%driver+support':>15}")
    print("-" * 85)
    for rid in args.rules:
        total = by_rule_total[rid]
        driver = by_rule[rid].get("DRIVER", 0)
        support = by_rule[rid].get("support", 0)
        non_case = driver + support
        pct = 100 * non_case / total if total else 0
        print(f"{rid:<35} {total:>7} {driver:>7} {support:>7} {pct:>14.1f}%")

    print(f"\n\nFor each rule: top file basenames driving FP")
    for rid in args.rules:
        top = by_rule[rid].most_common(args.top_driver)
        print(f"\n[{rid}]")
        for base, cnt in top:
            tag = ""
            if base == "DRIVER":
                tag = "  ← driver file"
            elif base == "support":
                tag = "  ← testcasesupport/"
            print(f"  {base:<55} {cnt:>6}{tag}")


if __name__ == "__main__":
    main()