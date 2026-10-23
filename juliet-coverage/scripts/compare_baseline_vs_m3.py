"""
compare_baseline_vs_m3.py — Compute TP/FP stats on baseline (all findings)
                          vs M3-filtered (drop findings where m3_verdict == FP).

For each CWE:
  - Run eval_findings.evaluate on the baseline ndjson (all findings)
  - Run the same evaluator on the m3 ndjson (filter findings to drop FP)
  - Compare precision / recall

Outputs:
  eval/compare_baseline_vs_m3.csv — per-CWE (scope) precision before/after M3
"""
import argparse
import csv
import sys
import time
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import eval_findings


def evaluate_with_filter(ndjson_path: Path, testcases_csv: Path, juliet_root: Path,
                         scope_cwe_id: str, drop_verdict: str = "FP") -> tuple[int, int, int, dict]:
    """Return (n_findings, tp, fp_total, per_rule_counts).

    Filters out findings whose m3_verdict == drop_verdict.
    If ndjson_path doesn't have m3_verdict fields, behaviour is "no filter".
    """
    # Custom eval that reads m3_verdict
    testcases_by_file, testcases_by_cwe_method = eval_findings.load_testcases(testcases_csv)
    if scope_cwe_id:
        sp = scope_cwe_id.upper()
        testcases_by_file = {fn: [tc for tc in tcs if tc.cwe_id.upper() == sp] for fn, tcs in testcases_by_file.items()}
        testcases_by_cwe_method = {cwe: {m: [tc for tc in tcs if tc.cwe_id.upper() == sp] for m, tcs in methods.items()}
                                   for cwe, methods in testcases_by_cwe_method.items()}
        testcases_by_file = {fn: tcs for fn, tcs in testcases_by_file.items() if tcs}
        testcases_by_cwe_method = {cwe: m for cwe, m in testcases_by_cwe_method.items() if m}

    findings = eval_findings.load_plumb(ndjson_path)
    # Apply m3 filter
    n_total = len(findings)
    n_after_filter = 0
    n_fp_dropped = 0
    n_unk_kept = 0
    kept = []
    for f in findings:
        # Re-read ndjson row to access m3_verdict (load_plumb doesn't preserve it)
        # We'll re-parse ndjson for verdict info.
        kept.append(f)
    # Note: eval_findings.Finding doesn't carry m3_verdict; we'll need to extend.
    # For now, fall back to evaluate(...) and a separate verdict map.
    return kept


def evaluate_with_m3_filter(
    ndjson_path: Path,
    testcases_csv: Path,
    juliet_root: Path,
    scope_cwe_id: str,
    drop_verdict: str = "FP",
    verdict_keep_set: set | None = None,
    _cached_ctx=None,
) -> tuple[int, int, int]:
    """Read ndjson, drop findings with m3_verdict in drop_verdict set (or drop just drop_verdict),
    then run eval.

    verdict_keep_set: alternative — only keep findings whose m3_verdict is in this set.
                      None = keep everything (baseline).
                      {"TP", "UNKNOWN"} = drop FP only.
                      {"TP"} = drop FP + UNK (only keep strict TPs).

    _cached_ctx: optional dict to memoize testcases + basename_index across multiple calls.
                 If None, a fresh context is built (slow on large CWE).
    """
    import json as _json

    if _cached_ctx is None:
        testcases_by_file, testcases_by_cwe_method = eval_findings.load_testcases(testcases_csv)
        if scope_cwe_id:
            sp = scope_cwe_id.upper()
            testcases_by_file = {fn: [tc for tc in tcs if tc.cwe_id.upper() == sp] for fn, tcs in testcases_by_file.items()}
            testcases_by_cwe_method = {cwe: {m: [tc for tc in tcs if tc.cwe_id.upper() == sp] for m, tcs in methods.items()}
                                       for cwe, methods in testcases_by_cwe_method.items()}
            testcases_by_file = {fn: tcs for fn, tcs in testcases_by_file.items() if tcs}
            testcases_by_cwe_method = {cwe: m for cwe, m in testcases_by_cwe_method.items() if m}
        _cached_ctx = {"testcases_by_file": testcases_by_file, "testcases_by_cwe_method": testcases_by_cwe_method,
                       "basename_index": None}

    testcases_by_file = _cached_ctx["testcases_by_file"]
    testcases_by_cwe_method = _cached_ctx["testcases_by_cwe_method"]

    # Build verdict map from ndjson (key = file|line|rule_id)
    verdict_map: dict[tuple, str] = {}
    n_total = 0
    n_dropped_fp = 0
    n_dropped_unk = 0
    with ndjson_path.open("r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw:
                continue
            try:
                row = _json.loads(raw)
            except _json.JSONDecodeError:
                continue
            n_total += 1
            v = row.get("m3_verdict")
            file = row.get("file", "")
            line = int(row.get("line") or 0)
            rule_id = row.get("rule_id", "")
            key = (file, line, rule_id)
            if v is not None:
                verdict_map[key] = v

    # Read findings and filter
    findings = eval_findings.load_plumb(ndjson_path)
    if verdict_keep_set is None:
        kept = findings
    else:
        kept = []
        for f in findings:
            key = (f.file, f.line, f.rule_id)
            v = verdict_map.get(key)
            if v is None:
                kept.append(f)
                continue
            if v in verdict_keep_set:
                kept.append(f)
            else:
                if v == "FP":
                    n_dropped_fp += 1
                elif v == "UNKNOWN":
                    n_dropped_unk += 1

    # Use eval_findings.evaluate logic but with our filtered findings
    per_rule, per_cwe = _evaluate_findings_list(kept, testcases_by_file, testcases_by_cwe_method, juliet_root, _cached_ctx)
    n_findings = len(kept)
    tp_total = sum(e["tp"] for e in per_rule.values())
    fp_total = sum(e["fp_total"] for e in per_rule.values())
    return n_findings, tp_total, fp_total, n_dropped_fp, n_dropped_unk, n_total


def _evaluate_findings_list(findings, testcases_by_file, testcases_by_cwe_method, juliet_root, cached_ctx=None):
    """Like eval_findings.evaluate but takes pre-loaded testcases + finding list."""
    per_rule = defaultdict(lambda: {
        "tp": 0, "fp_total": 0, "fp_g2b": 0, "fp_b2g": 0, "fp_good_other": 0,
        "fp_other": 0, "n_findings": 0, "cwes": set(),
    })
    per_cwe = defaultdict(lambda: {"tp": 0, "fp_total": 0, "n_findings": 0})
    span_cache: dict = {}

    # Pre-build juliet_root dir index for the basename case
    if cached_ctx is not None and cached_ctx.get("basename_index") is not None:
        basename_index = cached_ctx["basename_index"]
    else:
        basename_index: dict[str, list[tuple[str, Path]]] = {}
        for cwe_dir in juliet_root.glob("CWE*_*"):
            cwe_stem = cwe_dir.name.split("_")[0]  # e.g. "CWE476"
            try:
                children = list(cwe_dir.iterdir())
            except OSError:
                continue
            for child in children:
                if child.is_file() and child.suffix == ".java":
                    basename_index.setdefault(child.name, []).append((cwe_stem, child))
                elif child.is_dir() and child.name.startswith("s0"):
                    for sub_child in child.glob("*.java"):
                        if sub_child.is_file():
                            basename_index.setdefault(sub_child.name, []).append((cwe_stem, sub_child))
        if cached_ctx is not None:
            cached_ctx["basename_index"] = basename_index

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
                            # Basename only — use precomputed index
                            base = fname
                            entries = basename_index.get(base, [])
                            # Filter to entries whose cwe matches tc.cwe_id
                            tc_cwe_stem = tc.cwe_id  # e.g. "CWE476"
                            for stem, p in entries:
                                if stem == tc_cwe_stem:
                                    full = p
                                    break
                        if full and full.exists():
                            candidates.append(full)
            for c in candidates:
                if c.name in eval_findings.DRIVER_FILES:
                    continue
                if c not in span_cache:
                    span_cache[c] = eval_findings.parse_method_spans(c)
                spans = span_cache[c]
                if any(s.name == target_method_name for s in spans):
                    fpath_resolved = c
                    break
            if fpath_resolved is None:
                for c in candidates:
                    if c.name not in eval_findings.DRIVER_FILES:
                        fpath_resolved = c
                        break

        if fpath_resolved is None:
            # Cannot resolve — uncategorized
            for c in f.cwe:
                cwe_id = c.replace("CWE-", "").replace("CWE", "")
                per_cwe[cwe_id]["n_findings"] += 1
                per_cwe[cwe_id]["fp_total"] += 1
            entry = per_rule[f.rule_id]
            entry["n_findings"] += 1
            entry["fp_total"] += 1
            entry["fp_other"] += 1
            continue

        if fpath_resolved.name in eval_findings.DRIVER_FILES:
            for c in f.cwe:
                cwe_id = c.replace("CWE-", "").replace("CWE", "")
                per_cwe[cwe_id]["n_findings"] += 1
                per_cwe[cwe_id]["fp_total"] += 1
            entry = per_rule[f.rule_id]
            entry["n_findings"] += 1
            entry["fp_total"] += 1
            entry["fp_other"] += 1
            continue

        if fpath_resolved not in span_cache:
            span_cache[fpath_resolved] = eval_findings.parse_method_spans(fpath_resolved)
        spans = span_cache[fpath_resolved]
        span = eval_findings.find_method_span(spans, target_method_name, f.line)
        role = "other"
        if span:
            rel_file = fpath_resolved.name
            tcs = testcases_by_file.get(rel_file, [])
            tc = tcs[0] if tcs else None
            role = eval_findings.classify_method_role(span.name, tc.methods if tc else [])

        # Real CWE id from file path
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

        entry = per_rule[f.rule_id]
        entry["n_findings"] += 1
        if real_cwe_id:
            entry["cwes"].add(real_cwe_id)
        if role == "bad":
            entry["tp"] += 1
        elif role == "good_g2b":
            entry["fp_total"] += 1
            entry["fp_g2b"] += 1
        elif role == "good_b2g":
            entry["fp_total"] += 1
            entry["fp_b2g"] += 1
        elif role == "good_other":
            entry["fp_total"] += 1
            entry["fp_good_other"] += 1
        else:
            entry["fp_total"] += 1
            entry["fp_other"] += 1

    return dict(per_rule), dict(per_cwe)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline-dir", type=Path, default=Path(r"D:\tmp\plumb_test\scan\baseline"))
    ap.add_argument("--m3-dir", type=Path, default=Path(r"D:\tmp\plumb_test\scan\m3"))
    ap.add_argument("--testcases-csv", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv"))
    ap.add_argument("--juliet-root", type=Path, default=Path(r"D:\work\tmp\Juliet_Java\src\testcases"))
    ap.add_argument("--out-dir", type=Path, default=Path(r"D:\tmp\plumb_test\eval"))
    args = ap.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)
    rows = []

    baseline_files = sorted(args.baseline_dir.glob("CWE*.ndjson"))
    for baseline in baseline_files:
        stem = baseline.stem
        scope = stem.split("_")[0]
        m3 = args.m3_dir / (stem + "_m3.ndjson")
        if not m3.exists():
            continue

        # Build a per-CWE cached context so testcases/basename_index aren't rebuilt 3x
        ctx = {"testcases_by_file": None, "testcases_by_cwe_method": None, "basename_index": None}
        # Load testcases once for this scope
        testcases_by_file, testcases_by_cwe_method = eval_findings.load_testcases(args.testcases_csv)
        sp = scope.upper()
        testcases_by_file = {fn: [tc for tc in tcs if tc.cwe_id.upper() == sp] for fn, tcs in testcases_by_file.items()}
        testcases_by_cwe_method = {cwe: {m: [tc for tc in tcs if tc.cwe_id.upper() == sp] for m, tcs in methods.items()}
                                   for cwe, methods in testcases_by_cwe_method.items()}
        testcases_by_file = {fn: tcs for fn, tcs in testcases_by_file.items() if tcs}
        testcases_by_cwe_method = {cwe: m for cwe, m in testcases_by_cwe_method.items() if m}
        ctx["testcases_by_file"] = testcases_by_file
        ctx["testcases_by_cwe_method"] = testcases_by_cwe_method

        t0 = time.time()
        # Baseline (no filter)
        n_b, tp_b, fp_b, _, _, _ = evaluate_with_m3_filter(
            baseline, args.testcases_csv, args.juliet_root, scope, verdict_keep_set=None, _cached_ctx=ctx
        )
        # M3-filtered (drop FP only)
        n_m, tp_m, fp_m, dropped_fp, dropped_unk, n_total_m = evaluate_with_m3_filter(
            m3, args.testcases_csv, args.juliet_root, scope, verdict_keep_set={"TP", "UNKNOWN", "ERROR"}, _cached_ctx=ctx
        )
        # M3-strict (drop FP + UNK)
        n_s, tp_s, fp_s, _, _, _ = evaluate_with_m3_filter(
            m3, args.testcases_csv, args.juliet_root, scope, verdict_keep_set={"TP"}, _cached_ctx=ctx
        )
        dt = time.time() - t0

        prec_b = tp_b / n_b if n_b else 0
        prec_m = tp_m / n_m if n_m else 0
        prec_s = tp_s / n_s if n_s else 0
        # Recall: in Juliet ground truth, every bad() in a POSITIVE testcase should fire.
        # The "true positives" are determined by ground truth; we don't compute recall directly
        # from the static analysis side. We approximate: assume the static analysis catches
        # all TPs when its filter is permissive; after M3-filtering, recall = tp_after / tp_before.
        recall_drop_drop_fp = tp_m / tp_b if tp_b else 0.0
        recall_drop_drop_fp_unk = tp_s / tp_b if tp_b else 0.0
        fp_dropped = fp_b - fp_m
        fp_dropped_strict = fp_b - fp_s

        rows.append({
            "scope": scope,
            "baseline_n": n_b,
            "baseline_tp": tp_b,
            "baseline_fp": fp_b,
            "baseline_precision": f"{prec_b:.4f}",
            "m3_n": n_m,
            "m3_tp": tp_m,
            "m3_fp": fp_m,
            "m3_drop_fp": fp_dropped,
            "m3_drop_unk": tp_b - tp_m,  # TPs lost
            "m3_precision": f"{prec_m:.4f}",
            "m3_recall_retention": f"{recall_drop_drop_fp:.4f}",
            "m3_strict_n": n_s,
            "m3_strict_tp": tp_s,
            "m3_strict_fp": fp_s,
            "m3_strict_drop_fp": fp_dropped_strict,
            "m3_strict_drop_unk": tp_b - tp_s,
            "m3_strict_precision": f"{prec_s:.4f}",
            "m3_strict_recall_retention": f"{recall_drop_drop_fp_unk:.4f}",
            "n_total_findings": n_total_m,
            "eval_time_s": f"{dt:.1f}",
        })
        print(f"  [{scope}] baseline prec={prec_b:.3f} ({tp_b}/{n_b}); "
              f"drop-FP prec={prec_m:.3f} ({tp_m}/{n_m}, drop_fp={fp_dropped}); "
              f"drop-FP-UNK prec={prec_s:.3f} ({tp_s}/{n_s})",
              flush=True)

    out = args.out_dir / "compare_baseline_vs_m3.csv"
    with out.open("w", encoding="utf-8", newline="") as fh:
        if rows:
            wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            wr.writeheader()
            for r in rows:
                wr.writerow(r)
    print(f"[compare] wrote {out}")


if __name__ == "__main__":
    main()