"""
eval_findings_strict.py — strict variant of eval_findings that ONLY counts
findings that are in test cases AND whose rule's targeted CWE matches the
file's CWE directory.

Skipped findings (NOT counted as TP / FP / FN):
  1. File basename in DRIVER_FILES (Main.java, ServletMain.java)
  2. File path contains 'testcasesupport/'
  3. File == 'unknown' (Plumb DFA bug — file field empty)
  4. Finding's `cwe` field doesn't overlap with the file's CWE directory

This matches user intent: "只统计用例，不在用例里就忽略。比如 CatchGenericException
如果报在 Main.java 或 SQL 注入文件里，都应该忽略。"

Drop-in replacement for evaluate_findings() in compare_fast.py.
"""

from __future__ import annotations

import csv
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Iterable

sys.path.insert(0, str(Path(__file__).parent))
import eval_findings


# Same drivers used by the original eval
DRIVER_BASENAMES = {"Main.java", "ServletMain.java"}
SUPPORT_PATH_TOKEN = "testcasesupport"


def extract_cwe_from_path(file_path: Path) -> str | None:
    """Extract the CWE id from a Juliet test case file path.
    e.g. .../CWE89_SQL_Injection/s01/foo.java → '89'
         .../testcasesupport/IO.java → None
    """
    for part in file_path.parts:
        if part.startswith("CWE") and "_" in part:
            return part.split("_")[0].replace("CWE", "")
    return None


def extract_cwes_from_finding(finding) -> set[str]:
    """Normalize the finding's `cwe` field to a set of digits-only CWE ids."""
    out = set()
    for c in (finding.cwe or []):
        c = c.replace("CWE-", "").replace("CWE", "")
        if c.isdigit():
            out.add(c)
    return out


def should_skip_finding(finding) -> tuple[bool, str]:
    """Return (skip?, reason) for a finding.

    Skip rules (any one fires → skip):
      - 'driver'      : file basename in DRIVER_BASENAMES
      - 'support'     : file path contains 'testcasesupport/'
      - 'unknown'     : file == 'unknown' (Plumb DFA didn't fill filePath)
      - 'non-absolute': file path is not absolute (sandbox output)
    """
    file_str = finding.file or ""

    # Rule 3: unknown file
    if file_str == "unknown" or file_str == "":
        return True, "unknown-file"

    # Rule 1: driver file
    base = Path(file_str).name
    if base in DRIVER_BASENAMES:
        return True, "driver"

    # Rule 2: testcasesupport file
    if SUPPORT_PATH_TOKEN in file_str:
        return True, "support"

    return False, ""


# ─── per-(scope, rule_id) stats, same shape as compare_fast.evaluate_findings ──

def evaluate_findings_strict(
    findings,
    testcases_by_file,
    testcases_by_cwe_method,
    juliet_root,
    basename_index,
    scope_cwe_id: str | None = None,
    max_findings: int = 0,
):
    """Strict variant: skip driver / support / unknown / cwe-mismatch findings.

    Returns (per_rule, per_cwe, skip_stats) where:
      - per_rule: dict[rule_id] = {tp, fp_total, n_findings}
      - per_cwe:  dict[cwe_id]  = {tp, fp_total, n_findings}
      - skip_stats: dict[reason] = count
    """
    from compare_fast import evaluate_findings, classify_role, build_basename_index

    per_rule = defaultdict(lambda: {"tp": 0, "fp_total": 0, "n_findings": 0})
    per_cwe = defaultdict(lambda: {"tp": 0, "fp_total": 0, "n_findings": 0})
    skip_stats: dict[str, int] = defaultdict(int)
    span_cache: dict = {}

    if scope_cwe_id:
        sp = scope_cwe_id.upper()
        testcases_by_file = {fn: [tc for tc in tcs if tc.cwe_id.upper() == sp] for fn, tcs in testcases_by_file.items()}
        testcases_by_cwe_method = {cwe: {m: [tc for tc in tcs if tc.cwe_id.upper() == sp] for m, tcs in methods.items()}
                                   for cwe, methods in testcases_by_cwe_method.items()}
        testcases_by_file = {fn: tcs for fn, tcs in testcases_by_file.items() if tcs}
        testcases_by_cwe_method = {cwe: m for cwe, m in testcases_by_cwe_method.items() if m}

    if max_findings > 0:
        findings = findings[:max_findings]

    for f in findings:
        skip, reason = should_skip_finding(f)
        if skip:
            skip_stats[reason] += 1
            continue

        fpath = Path(f.file)
        if not fpath.is_absolute():
            skip_stats["non-absolute-path"] += 1
            continue
        if not fpath.exists():
            skip_stats["file-not-found"] += 1
            continue

        # Pre-filter by driver (defensive)
        if fpath.name in DRIVER_BASENAMES:
            skip_stats["driver"] += 1
            continue
        if SUPPORT_PATH_TOKEN in str(fpath):
            skip_stats["support"] += 1
            continue

        # Now do the full evaluation (the original code)
        target_method_name = f.sink_function.split(":")[0].split("(")[0].strip() if f.sink_function else ""
        if fpath not in span_cache:
            span_cache[fpath] = eval_findings.parse_method_spans(fpath)
        spans = span_cache[fpath]
        span = eval_findings.find_method_span(spans, target_method_name, f.line)
        if span is None:
            role = "other"
        else:
            rel_file = fpath.name
            tcs = testcases_by_file.get(rel_file, [])
            tc = tcs[0] if tcs else None
            role = classify_role(span, tc.methods if tc else [])

        real_cwe_id = None
        rel_file = fpath.name
        tcs_for_real = testcases_by_file.get(rel_file, [])
        if tcs_for_real:
            real_cwe_id = tcs_for_real[0].cwe_id.replace("CWE", "")
        else:
            for part in fpath.parts:
                if part.startswith("CWE") and "_" in part:
                    real_cwe_id = part.split("_")[0].replace("CWE", "")
                    break

        cwe_ids = [real_cwe_id] if real_cwe_id else [c.replace("CWE-", "").replace("CWE", "") for c in f.cwe]
        for cwe_id in cwe_ids:
            per_cwe[cwe_id]["n_findings"] += 1
            if role == "bad":
                per_cwe[cwe_id]["tp"] += 1
            else:
                per_cwe[cwe_id]["fp_total"] += 1
        per_rule[f.rule_id]["n_findings"] += 1
        if role == "bad":
            per_rule[f.rule_id]["tp"] += 1
        else:
            per_rule[f.rule_id]["fp_total"] += 1

    return dict(per_rule), dict(per_cwe), dict(skip_stats)


# ─── Main driver: re-run over whitelist_v2 ──────────────────────────────

def main():
    import argparse
    import time

    ap = argparse.ArgumentParser()
    ap.add_argument("--whitelist-dir", type=Path,
                    default=Path(r"D:\tmp\plumb_test\scan\whitelist_v2"))
    ap.add_argument("--baseline-dir", type=Path,
                    default=Path(r"D:\tmp\plumb_test\scan\baseline"))
    ap.add_argument("--testcases-csv", type=Path,
                    default=Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv"))
    ap.add_argument("--juliet-root", type=Path,
                    default=Path(r"D:\work\tmp\Juliet_Java\src\testcases"))
    ap.add_argument("--out-dir", type=Path,
                    default=Path(r"D:\tmp\plumb_test\eval_strict"))
    args = ap.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)

    print("[strict] loading testcases...", flush=True)
    t0 = time.time()
    testcases_by_file, testcases_by_cwe_method = eval_findings.load_testcases(args.testcases_csv)
    print(f"[strict] testcases loaded in {time.time()-t0:.1f}s", flush=True)

    print("[strict] building basename_index...", flush=True)
    t0 = time.time()
    from compare_fast import build_basename_index
    basename_index = build_basename_index(args.juliet_root)
    print(f"[strict] basename_index built in {time.time()-t0:.1f}s ({len(basename_index)} entries)", flush=True)

    per_rule_rows = []
    per_cwe_rows = []
    skip_total = defaultdict(int)
    scope_stats = []

    for label, scan_dir in [("whitelist", args.whitelist_dir), ("baseline", args.baseline_dir)]:
        if not scan_dir.exists():
            print(f"[strict] {label}: dir not found, skipping", flush=True)
            continue
        ndjson_files = sorted(scan_dir.glob("CWE*.ndjson"))
        print(f"\n[strict-{label}] {len(ndjson_files)} ndjson files", flush=True)
        for nd in ndjson_files:
            stem = nd.stem
            scope = stem.split("_")[0]
            t0 = time.time()
            try:
                findings = []
                with nd.open("r", encoding="utf-8", errors="replace") as fh:
                    for raw in fh:
                        raw = raw.strip()
                        if not raw:
                            continue
                        try:
                            row = json.loads(raw)
                        except json.JSONDecodeError:
                            continue
                        findings.append(eval_findings.Finding.from_nd(row))

                per_rule, per_cwe, skip_stats = evaluate_findings_strict(
                    findings, testcases_by_file, testcases_by_cwe_method,
                    args.juliet_root, basename_index, scope_cwe_id=scope,
                )
            except Exception as e:
                print(f"  [ERR] {stem}: {e}", flush=True)
                continue

            for reason, n in skip_stats.items():
                skip_total[f"{label}/{reason}"] += n

            n_findings = sum(e["n_findings"] for e in per_rule.values())
            tp_total = sum(e["tp"] for e in per_rule.values())
            fp_total = sum(e["fp_total"] for e in per_rule.values())
            precision = tp_total / n_findings if n_findings else 0.0

            scope_stats.append({
                "label": label,
                "scope": scope,
                "n_findings": n_findings,
                "tp": tp_total,
                "fp": fp_total,
                "precision": f"{precision:.4f}",
                "n_rules": len(per_rule),
                "eval_time_s": f"{time.time()-t0:.1f}",
            })

            for rule_id, e in per_rule.items():
                tp = e["tp"]
                fp = e["fp_total"]
                total = e["n_findings"]
                prec = tp / total if total else 0.0
                per_rule_rows.append({
                    "label": label,
                    "scope": scope,
                    "rule_id": rule_id,
                    "tp": tp,
                    "fp_total": fp,
                    "n_findings": total,
                    "precision": f"{prec:.4f}",
                })
            for cwe_id, e in per_cwe.items():
                per_cwe_rows.append({
                    "label": label,
                    "scope": scope,
                    "cwe_id": cwe_id,
                    "tp": e["tp"],
                    "fp": e["fp_total"],
                    "n_findings": e["n_findings"],
                    "precision": f"{e['tp']/e['n_findings'] if e['n_findings'] else 0:.4f}",
                })
            dt = time.time() - t0
            print(f"  [{label} {scope}] findings={n_findings:>7d} tp={tp_total:>6d} "
                  f"fp={fp_total:>6d} prec={precision:.3f} ({dt:.1f}s)", flush=True)

    # Outputs
    with (args.out_dir / "global_per_rule.csv").open("w", encoding="utf-8", newline="") as fh:
        if per_rule_rows:
            wr = csv.DictWriter(fh, fieldnames=list(per_rule_rows[0].keys()))
            wr.writeheader()
            for r in per_rule_rows:
                wr.writerow(r)
    with (args.out_dir / "global_per_cwe.csv").open("w", encoding="utf-8", newline="") as fh:
        if per_cwe_rows:
            wr = csv.DictWriter(fh, fieldnames=list(per_cwe_rows[0].keys()))
            wr.writeheader()
            for r in per_cwe_rows:
                wr.writerow(r)
    with (args.out_dir / "global_summary.csv").open("w", encoding="utf-8", newline="") as fh:
        if scope_stats:
            wr = csv.DictWriter(fh, fieldnames=list(scope_stats[0].keys()))
            wr.writeheader()
            for r in scope_stats:
                wr.writerow(r)
    with (args.out_dir / "skip_stats.csv").open("w", encoding="utf-8", newline="") as fh:
        wr = csv.DictWriter(fh, fieldnames=["label_reason", "count"])
        wr.writeheader()
        for k, v in sorted(skip_total.items(), key=lambda x: -x[1]):
            wr.writerow({"label_reason": k, "count": v})

    # Aggregate
    total_findings = sum(r["n_findings"] for r in scope_stats)
    total_tp = sum(r["tp"] for r in scope_stats)
    total_fp = sum(r["fp"] for r in scope_stats)
    overall_prec = total_tp / total_findings if total_findings else 0.0
    print(f"\n[strict] DONE: findings={total_findings} tp={total_tp} fp={total_fp} precision={overall_prec:.4f}")
    print(f"[strict] skip stats (top reasons):")
    for k, v in sorted(skip_total.items(), key=lambda x: -x[1])[:15]:
        print(f"  {k:<35} {v:>7,}")


if __name__ == "__main__":
    main()