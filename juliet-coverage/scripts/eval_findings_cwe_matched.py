"""
eval_findings_cwe_matched.py — strict eval that ONLY counts findings
whose rule's CWE matches the test case's CWE directory.

Uses:
  - plumb_rule_to_cwe.json — Plumb rule_id → [CWE] map (206 rules)
  - Juliet testcases_detail.csv — test case ground truth

Skip rules:
  1. file == 'unknown' (Plumb DFA bug — no source location)
  2. file path contains 'testcasesupport/'
  3. file basename in DRIVER_FILES (Main.java, ServletMain.java)
  4. file path not resolvable to a Juliet test case
  5. finding's rule_id not in plumb_rule_to_cwe.json
  6. finding's rule's CWE set does NOT include the file's CWE

This is the user-intended strict eval: rule-CWE alignment.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

# Force UTF-8 stdout to avoid UnicodeEncodeError on Windows GBK consoles
if sys.platform == "win32":
    try:
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
        sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")
    except Exception:
        pass

sys.path.insert(0, str(Path(__file__).parent))
import eval_findings
from compare_fast import build_basename_index, classify_role


DRIVER_BASENAMES = {"Main.java", "ServletMain.java"}
SUPPORT_PATH_TOKEN = "testcasesupport"


def load_rule_cwe_map(json_path: Path) -> dict[str, set[str]]:
    """Load Plumb rule_id → set(CWE) map."""
    with json_path.open("r", encoding="utf-8") as fh:
        raw = json.load(fh)
    return {k: set(v) for k, v in raw.items()}


def extract_cwe_from_path(file_path: Path) -> str | None:
    """Extract CWE id from a Juliet test case file path."""
    for part in file_path.parts:
        if part.startswith("CWE") and "_" in part:
            return part.split("_")[0].replace("CWE", "")
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rule-cwe-map", type=Path,
                    default=Path(r"D:\tmp\plumb_test\plumb_rule_to_cwe.json"))
    ap.add_argument("--baseline-dir", type=Path,
                    default=Path(r"D:\tmp\plumb_test\scan\baseline"))
    ap.add_argument("--whitelist-dir", type=Path,
                    default=Path(r"D:\tmp\plumb_test\scan\whitelist_v2"))
    ap.add_argument("--testcases-csv", type=Path,
                    default=Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv"))
    ap.add_argument("--juliet-root", type=Path,
                    default=Path(r"D:\work\tmp\Juliet_Java\src\testcases"))
    ap.add_argument("--out-dir", type=Path,
                    default=Path(r"D:\tmp\plumb_test\eval_cwe_matched"))
    args = ap.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)

    print("[cwe-matched] loading rule↔CWE map ...", flush=True)
    rule_to_cwes = load_rule_cwe_map(args.rule_cwe_map)
    n_with_cwe = sum(1 for v in rule_to_cwes.values() if v)
    print(f"[cwe-matched] {len(rule_to_cwes)} rules; {n_with_cwe} have CWE tags", flush=True)

    print("[cwe-matched] loading testcases ...", flush=True)
    t0 = time.time()
    testcases_by_file, testcases_by_cwe_method = eval_findings.load_testcases(args.testcases_csv)
    print(f"[cwe-matched] testcases loaded in {time.time()-t0:.1f}s", flush=True)

    print("[cwe-matched] building basename_index ...", flush=True)
    t0 = time.time()
    basename_index = build_basename_index(args.juliet_root)
    print(f"[cwe-matched] basename_index built in {time.time()-t0:.1f}s ({len(basename_index)} entries)", flush=True)

    # Skip stats per category
    skip_stats: dict[str, int] = defaultdict(int)

    per_rule_rows = []
    per_cwe_rows = []
    scope_stats = []

    for label, scan_dir in [("whitelist", args.whitelist_dir), ("baseline", args.baseline_dir)]:
        if not scan_dir.exists():
            print(f"[cwe-matched] {label}: dir not found, skipping", flush=True)
            continue
        ndjson_files = sorted(scan_dir.glob("CWE*.ndjson"))
        print(f"\n[cwe-matched-{label}] {len(ndjson_files)} ndjson files", flush=True)
        for nd in ndjson_files:
            stem = nd.stem
            scope = stem.split("_")[0]
            t0 = time.time()
            try:
                # Scope-filter the test case maps
                sp = scope.upper()
                tbf = {fn: [tc for tc in tcs if tc.cwe_id.upper() == sp]
                       for fn, tcs in testcases_by_file.items()}
                tbf = {fn: tcs for fn, tcs in tbf.items() if tcs}
                tbcm = {cwe: {m: [tc for tc in tcs if tc.cwe_id.upper() == sp]
                                for m, tcs in methods.items()}
                        for cwe, methods in testcases_by_cwe_method.items()}
                tbcm = {cwe: m for cwe, m in tbcm.items() if m}

                # Stream-eval findings
                per_rule = defaultdict(lambda: {"tp": 0, "fp_total": 0, "n_findings": 0})
                per_cwe = defaultdict(lambda: {"tp": 0, "fp_total": 0, "n_findings": 0})
                span_cache: dict = {}

                with nd.open("r", encoding="utf-8", errors="replace") as fh:
                    for raw in fh:
                        raw = raw.strip()
                        if not raw:
                            continue
                        try:
                            row = json.loads(raw)
                        except json.JSONDecodeError:
                            continue

                        # Extract fields
                        rule_id = row.get("rule_id") or ""
                        file_str = row.get("file") or ""
                        try:
                            line = int(row.get("line") or 0)
                        except Exception:
                            line = 0
                        try:
                            sink_function = row.get("sink_function") or ""
                        except Exception:
                            sink_function = ""

                        # Skip rules
                        if file_str in ("unknown", ""):
                            skip_stats[f"{label}/unknown-file"] += 1
                            continue
                        base = Path(file_str).name
                        if base in DRIVER_BASENAMES:
                            skip_stats[f"{label}/driver"] += 1
                            continue
                        if SUPPORT_PATH_TOKEN in file_str:
                            skip_stats[f"{label}/support"] += 1
                            continue
                        if rule_id not in rule_to_cwes:
                            skip_stats[f"{label}/rule-not-in-map"] += 1
                            continue
                        rule_cwes = rule_to_cwes[rule_id]
                        if not rule_cwes:
                            skip_stats[f"{label}/rule-no-cwe"] += 1
                            continue

                        # CWE alignment
                        fpath = Path(file_str)
                        file_cwe = extract_cwe_from_path(fpath)
                        if file_cwe is None:
                            skip_stats[f"{label}/file-no-cwe"] += 1
                            continue
                        if file_cwe not in rule_cwes:
                            skip_stats[f"{label}/cwe-mismatch"] += 1
                            continue

                        # File must exist
                        if not fpath.exists():
                            skip_stats[f"{label}/file-not-found"] += 1
                            continue

                        # Classify by method span
                        target_method_name = sink_function.split(":")[0].split("(")[0].strip() if sink_function else ""
                        if fpath not in span_cache:
                            span_cache[fpath] = eval_findings.parse_method_spans(fpath)
                        spans = span_cache[fpath]
                        span = eval_findings.find_method_span(spans, target_method_name, line)
                        if span is None:
                            role = "other"
                        else:
                            rel_file = fpath.name
                            tcs_for_file = tbf.get(rel_file, [])
                            tc = tcs_for_file[0] if tcs_for_file else None
                            role = classify_role(span, tc.methods if tc else [])

                        # Aggregate
                        per_cwe[file_cwe]["n_findings"] += 1
                        per_rule[rule_id]["n_findings"] += 1
                        if role == "bad":
                            per_cwe[file_cwe]["tp"] += 1
                            per_rule[rule_id]["tp"] += 1
                        else:
                            per_cwe[file_cwe]["fp_total"] += 1
                            per_rule[rule_id]["fp_total"] += 1

            except Exception as e:
                print(f"  [ERR] {stem}: {e}", flush=True)
                continue

            n_findings = sum(e["n_findings"] for e in per_rule.values())
            tp_total = sum(e["tp"] for e in per_rule.values())
            fp_total = sum(e["fp_total"] for e in per_rule.values())
            precision = tp_total / n_findings if n_findings else 0.0
            scope_stats.append({
                "label": label, "scope": scope,
                "n_findings": n_findings, "tp": tp_total, "fp": fp_total,
                "precision": f"{precision:.4f}",
                "n_rules": len(per_rule),
                "eval_time_s": f"{time.time()-t0:.1f}",
            })
            for rule_id_, e in per_rule.items():
                prec = e["tp"] / e["n_findings"] if e["n_findings"] else 0.0
                per_rule_rows.append({
                    "label": label, "scope": scope, "rule_id": rule_id_,
                    "tp": e["tp"], "fp_total": e["fp_total"],
                    "n_findings": e["n_findings"], "precision": f"{prec:.4f}",
                })
            for cwe_id, e in per_cwe.items():
                prec = e["tp"] / e["n_findings"] if e["n_findings"] else 0.0
                per_cwe_rows.append({
                    "label": label, "scope": scope, "cwe_id": cwe_id,
                    "tp": e["tp"], "fp": e["fp_total"],
                    "n_findings": e["n_findings"], "precision": f"{prec:.4f}",
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
        for k, v in sorted(skip_stats.items(), key=lambda x: -x[1]):
            wr.writerow({"label_reason": k, "count": v})

    # Aggregate
    by_label = defaultdict(lambda: {"n": 0, "tp": 0, "fp": 0})
    for r in scope_stats:
        b = by_label[r["label"]]
        b["n"] += r["n_findings"]
        b["tp"] += r["tp"]
        b["fp"] += r["fp"]
    print(f"\n[cwe-matched] DONE")
    for label, b in by_label.items():
        prec = b["tp"] / b["n"] if b["n"] else 0
        print(f"  {label}: findings={b['n']:,} tp={b['tp']:,} fp={b['fp']:,} prec={prec:.4f}")
    print(f"\n[cwe-matched] Skip stats (top reasons):")
    for k, v in sorted(skip_stats.items(), key=lambda x: -x[1])[:15]:
        print(f"  {k:<35} {v:>7,}")


if __name__ == "__main__":
    main()