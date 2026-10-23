"""
compare_fast.py — Faster baseline vs M3 comparison.

Key optimizations vs compare_baseline_vs_m3.py:
  - Build basename_index once (glob all .java files in juliet_root), share across all CWEs
  - Load testcases once, share across all CWEs
  - For each CWE: read ndjson ONCE, build verdict_map ONCE, then evaluate three times with different filters
    (sharing the per-finding list and span cache)
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


# ─── Global caches ────────────────────────────────────────────────────────

def build_basename_index(juliet_root: Path) -> dict[str, list[tuple[str, Path]]]:
    """filename -> list of (cwe_stem, full_path)."""
    idx: dict[str, list[tuple[str, Path]]] = {}
    for cwe_dir in juliet_root.glob("CWE*_*"):
        cwe_stem = cwe_dir.name.split("_")[0]
        try:
            children = list(cwe_dir.iterdir())
        except OSError:
            continue
        for child in children:
            if child.is_file() and child.suffix == ".java":
                idx.setdefault(child.name, []).append((cwe_stem, child))
            elif child.is_dir() and child.name.startswith("s0"):
                for sc in child.glob("*.java"):
                    if sc.is_file():
                        idx.setdefault(sc.name, []).append((cwe_stem, sc))
    return idx


# ─── Per-finding role classifier (logic from eval_findings.py, inlined) ───

def classify_role(span, tc_methods) -> str:
    """Classify a finding's method span relative to the BAD/GOOD
    classification given by `tc_methods`. Names are the public method
    names from the test case metadata.

    Args:
        span: MethodSpan with `name`, `start_line`, `end_line`.
        tc_methods: list of test-case method names from GT.
    """
    if span.name == "bad" or span.name.startswith("bad"):
        return "bad"
    if span.name.startswith("goodG2B"):
        return "good_g2b"
    if span.name.startswith("goodB2G"):
        return "good_b2g"
    if span.name.startswith("good"):
        return "good_other"
    return "other"


# ─── Evaluation core (operates on a list of Finding-objects) ──────────────

def evaluate_findings(
    findings,
    testcases_by_file,
    testcases_by_cwe_method,
    juliet_root,
    basename_index,
    scope_cwe_id: str | None = None,
    max_findings: int = 0,
):
    """Yield per-(scope, rule_id) stats. Reuses span_cache across calls."""
    per_rule = defaultdict(lambda: {"tp": 0, "fp_total": 0, "n_findings": 0})
    per_cwe = defaultdict(lambda: {"tp": 0, "fp_total": 0, "n_findings": 0})
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
        fpath = eval_findings.Path(f.file)
        if not fpath.is_absolute() or f.file == "unknown":
            fpath_resolved = None
        else:
            fpath_resolved = fpath if fpath.exists() else None

        target_method_name = f.sink_function.split(":")[0].split("(")[0].strip() if f.sink_function else ""

        if fpath_resolved is None:
            candidates = []
            for cwe_id, methods in testcases_by_cwe_method.items():
                if target_method_name not in methods:
                    continue
                for tc in methods[target_method_name]:
                    for fname in tc.files:
                        full = None
                        parts = fname.split("/")
                        if len(parts) >= 3:
                            rel = "/".join(parts[2:])
                            full = juliet_root / rel
                        else:
                            base = fname
                            entries = basename_index.get(base, [])
                            tc_stem = tc.cwe_id
                            for stem, p in entries:
                                if stem == tc_stem:
                                    full = p
                                    break
                        if full and full.exists():
                            candidates.append(full)
            for c in candidates:
                if c.name in eval_findings.DRIVER_FILES:
                    continue
                if c not in span_cache:
                    span_cache[c] = eval_findings.parse_method_spans(c)
                if any(s.name == target_method_name for s in span_cache[c]):
                    fpath_resolved = c
                    break
            if fpath_resolved is None:
                for c in candidates:
                    if c.name not in eval_findings.DRIVER_FILES:
                        fpath_resolved = c
                        break

        if fpath_resolved is None:
            # Unresolved → fp_other
            for c in f.cwe:
                cwe_id = c.replace("CWE-", "").replace("CWE", "")
                per_cwe[cwe_id]["n_findings"] += 1
                per_cwe[cwe_id]["fp_total"] += 1
            per_rule[f.rule_id]["n_findings"] += 1
            per_rule[f.rule_id]["fp_total"] += 1
            continue

        if fpath_resolved.name in eval_findings.DRIVER_FILES:
            for c in f.cwe:
                cwe_id = c.replace("CWE-", "").replace("CWE", "")
                per_cwe[cwe_id]["n_findings"] += 1
                per_cwe[cwe_id]["fp_total"] += 1
            per_rule[f.rule_id]["n_findings"] += 1
            per_rule[f.rule_id]["fp_total"] += 1
            continue

        if fpath_resolved not in span_cache:
            span_cache[fpath_resolved] = eval_findings.parse_method_spans(fpath_resolved)
        spans = span_cache[fpath_resolved]
        span = eval_findings.find_method_span(spans, target_method_name, f.line)
        if span is None:
            role = "other"
        else:
            rel_file = fpath_resolved.name
            tcs = testcases_by_file.get(rel_file, [])
            tc = tcs[0] if tcs else None
            role = classify_role(span, tc.methods if tc else [],
                                 fpath_resolved)

        real_cwe_id = None
        rel_file = fpath_resolved.name
        tcs_for_real = testcases_by_file.get(rel_file, [])
        if tcs_for_real:
            real_cwe_id = tcs_for_real[0].cwe_id.replace("CWE", "")
        else:
            for part in fpath_resolved.parts:
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

    return dict(per_rule), dict(per_cwe)


# ─── NDJSON reader with verdict map ────────────────────────────────────────

def load_with_verdicts(ndjson_path: Path):
    """Return (findings, verdict_map). verdict_map[(file,line,rule_id)] = m3_verdict or None."""
    findings = []
    verdict_map = {}
    with ndjson_path.open("r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw:
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError:
                continue
            f = eval_findings.Finding.from_nd(row)
            findings.append(f)
            key = (f.file, f.line, f.rule_id)
            v = row.get("m3_verdict")
            if v is not None:
                verdict_map[key] = v
    return findings, verdict_map


def filter_findings(findings, verdict_map, keep_set):
    """Return (kept, n_dropped_fp, n_dropped_unk, n_total)."""
    n_total = len(findings)
    kept = []
    n_fp = 0
    n_unk = 0
    if keep_set is None:
        return findings, 0, 0, n_total
    for f in findings:
        v = verdict_map.get((f.file, f.line, f.rule_id))
        if v is None:
            kept.append(f)
            continue
        if v in keep_set:
            kept.append(f)
        elif v == "FP":
            n_fp += 1
        elif v == "UNKNOWN":
            n_unk += 1
    return kept, n_fp, n_unk, n_total


# ─── Main ─────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline-dir", type=Path, default=Path(r"D:\tmp\plumb_test\scan\baseline"))
    ap.add_argument("--m3-dir", type=Path, default=Path(r"D:\tmp\plumb_test\scan\m3"))
    ap.add_argument("--testcases-csv", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv"))
    ap.add_argument("--juliet-root", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\src\testcases"))
    ap.add_argument("--out-dir", type=Path, default=Path(r"D:\tmp\plumb_test\eval"))
    ap.add_argument("--limit-findings", type=int, default=0,
                    help="If >0, evaluate only this many findings per CWE (debug)")
    args = ap.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)

    print("[compare-fast] loading testcases...", flush=True)
    t0 = time.time()
    testcases_by_file, testcases_by_cwe_method = eval_findings.load_testcases(args.testcases_csv)
    print(f"[compare-fast] testcases loaded in {time.time()-t0:.1f}s", flush=True)

    print("[compare-fast] building basename_index...", flush=True)
    t0 = time.time()
    basename_index = build_basename_index(args.juliet_root)
    print(f"[compare-fast] basename_index built in {time.time()-t0:.1f}s ({len(basename_index)} unique filenames)", flush=True)

    rows = []
    baseline_files = sorted(args.baseline_dir.glob("CWE*.ndjson"))
    for baseline in baseline_files:
        stem = baseline.stem
        scope = stem.split("_")[0]
        m3 = args.m3_dir / (stem + "_m3.ndjson")
        if not m3.exists():
            continue

        t0 = time.time()
        # Read baseline ndjson once
        findings_b, _ = load_with_verdicts(baseline)
        # Read m3 ndjson once
        findings_m, verdict_map_m = load_with_verdicts(m3)

        # Baseline eval (no filter)
        if args.limit_findings > 0:
            findings_b_eval = findings_b[:args.limit_findings]
        else:
            findings_b_eval = findings_b
        per_rule_b, per_cwe_b = evaluate_findings(
            findings_b_eval, testcases_by_file, testcases_by_cwe_method,
            args.juliet_root, basename_index, scope_cwe_id=scope,
        )
        n_b = sum(e["n_findings"] for e in per_rule_b.values())
        tp_b = sum(e["tp"] for e in per_rule_b.values())
        fp_b = sum(e["fp_total"] for e in per_rule_b.values())

        # M3 drop-FP
        kept_m, dfp, dunk, total_m = filter_findings(findings_m, verdict_map_m, {"TP", "UNKNOWN", "ERROR"})
        if args.limit_findings > 0:
            kept_m = kept_m[:args.limit_findings]
        per_rule_m, per_cwe_m = evaluate_findings(
            kept_m, testcases_by_file, testcases_by_cwe_method,
            args.juliet_root, basename_index, scope_cwe_id=scope,
        )
        n_m = sum(e["n_findings"] for e in per_rule_m.values())
        tp_m = sum(e["tp"] for e in per_rule_m.values())
        fp_m = sum(e["fp_total"] for e in per_rule_m.values())

        # M3 strict (drop FP + UNK)
        kept_s, dfp_s, dunk_s, _ = filter_findings(findings_m, verdict_map_m, {"TP"})
        if args.limit_findings > 0:
            kept_s = kept_s[:args.limit_findings]
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

        dt = time.time() - t0
        rows.append({
            "scope": scope,
            "baseline_n": n_b,
            "baseline_tp": tp_b,
            "baseline_fp": fp_b,
            "baseline_precision": f"{prec_b:.4f}",
            "m3_drop_fp_n": n_m,
            "m3_drop_fp_tp": tp_m,
            "m3_drop_fp_fp": fp_m,
            "m3_drop_fp_dropped": dfp,
            "m3_drop_fp_unk_dropped": 0,
            "m3_drop_fp_precision": f"{prec_m:.4f}",
            "m3_drop_fp_recall_retention": f"{recall_drop_fp:.4f}",
            "m3_strict_n": n_s,
            "m3_strict_tp": tp_s,
            "m3_strict_fp": fp_s,
            "m3_strict_dropped": dfp_s + dunk_s,
            "m3_strict_precision": f"{prec_s:.4f}",
            "m3_strict_recall_retention": f"{recall_drop_fp_unk:.4f}",
            "n_m3_total_in_ndjson": total_m,
            "eval_time_s": f"{dt:.1f}",
        })

        print(f"  [{scope}] baseline prec={prec_b:.3f} ({tp_b}/{n_b}); "
              f"drop-FP prec={prec_m:.3f} ({tp_m}/{n_m}, dropped_fp={dfp}, total_in_ndjson={total_m}); "
              f"strict prec={prec_s:.3f} ({tp_s}/{n_s}) [{dt:.1f}s]", flush=True)

    out = args.out_dir / "compare_baseline_vs_m3.csv"
    with out.open("w", encoding="utf-8", newline="") as fh:
        if rows:
            wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            wr.writeheader()
            for r in rows:
                wr.writerow(r)
    print(f"[compare-fast] wrote {out}", flush=True)

    total_b = sum(r["baseline_n"] for r in rows)
    total_tp_b = sum(r["baseline_tp"] for r in rows)
    total_fp_b = sum(r["baseline_fp"] for r in rows)
    total_tp_m = sum(r["m3_drop_fp_tp"] for r in rows)
    total_fp_m = sum(r["m3_drop_fp_fp"] for r in rows)
    total_n_m = sum(r["m3_drop_fp_n"] for r in rows)
    print(f"\n[compare-fast] OVERALL: "
          f"baseline prec={total_tp_b/total_b if total_b else 0:.4f} ({total_tp_b}/{total_b}), "
          f"drop-FP prec={total_tp_m/total_n_m if total_n_m else 0:.4f} ({total_tp_m}/{total_n_m})",
          flush=True)


if __name__ == "__main__":
    main()