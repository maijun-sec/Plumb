"""
count_tp_via_sinkfn.py — quick TP/FP estimate using Juliet's bad/good sink_function pattern.

Each Juliet test case has these methods:
  bad() / badG2B() / badB2G() — TP-eligible for SQL injection if rule fires
  good() / goodG2B() / goodB2G() — FP if rule fires (these are the FIXED versions)

A finding with sink_function starting with "bad" → TP
A finding with sink_function starting with "good" → FP

Taint findings should mostly fire in bad() / badG2B() / badB2G() functions.
"""
import json
from pathlib import Path
from collections import defaultdict

BASE = Path(r"D:\tmp\plumb_test\scan\taint_v1")

# Plumb taint rules
TAINTS = ("plumb.taint.sql-injection", "plumb.taint.command-injection",
          "plumb.taint.xss-injection", "plumb.taint.path-traversal",
          "plumb.taint.ldap-injection", "plumb.taint.open-redirect",
          "plumb.taint.http-response-splitting", "plumb.taint.insecure-random",
          "plumb.taint.el-injection", "plumb.taint.ognl-injection",
          "plumb.taint.java-code-injection", "plumb.taint.jcr-injection",
          "plumb.taint.jsp-dynamic-include", "plumb.taint.trust-boundary-violation",
          "plumb.taint.sensitive-pii-leak", "plumb.taint.session-fixation",
          "plumb.taint.unsafe-reflection", "plumb.taint.unsafe-jni",
          "plumb.taint.bad-cert-verification")


def classify(sink_function: str) -> str:
    """bad → TP, good → FP, anything else → UNKNOWN"""
    s = (sink_function or "").lower().strip()
    if not s:
        return "UNKNOWN"
    # Bad patterns (Juliet "bad" + variants)
    if any(s.startswith(prefix) for prefix in ("bad()", "badg2b()", "badb2g()", "bad1(", "bad2(", "badg2b(", "badb2g(", "bad")):
        # Avoid matching "badSink" but that doesn't appear here
        if "good" not in s:
            return "TP"
    if any(s.startswith(prefix) for prefix in ("goodg2b()", "goodb2g()", "good1(", "good2(", "goodg2b(", "goodb2g(", "good(")):
        return "FP"
    if s.startswith("bad"):
        return "TP"
    if s.startswith("good"):
        return "FP"
    return "UNKNOWN"


per_cwe = {}
total_all = 0
total_tp = 0
total_fp = 0
total_unk = 0
total_taint_all = 0
total_taint_tp = 0
total_taint_fp = 0
total_taint_unk = 0

print(f"{'CWE':<35} {'Taint_total':>12} {'TP':>8} {'FP':>8} {'UNK':>8} {'Precision':>10}")
print("-" * 85)
for nd in sorted(BASE.glob("CWE*.ndjson")):
    taint_total = 0
    tp = fp = unk = 0
    rule_counts = defaultdict(lambda: {"TP": 0, "FP": 0, "UNK": 0, "total": 0})
    with nd.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                obj = json.loads(line)
                rid = obj.get("rule_id", "") or ""
                if rid not in TAINTS:
                    continue
                taint_total += 1
                cls = classify(obj.get("sink_function", ""))
                tp += 1 if cls == "TP" else 0
                fp += 1 if cls == "FP" else 0
                unk += 1 if cls == "UNKNOWN" else 0
                rule_counts[rid][cls] += 1
                rule_counts[rid]["total"] += 1
            except Exception:
                continue
    prec = tp / taint_total * 100 if taint_total else 0
    print(f"{nd.stem:<35} {taint_total:>12} {tp:>8} {fp:>8} {unk:>8} {prec:>9.2f}%")
    per_cwe[nd.stem] = (taint_total, tp, fp, unk, rule_counts)
    total_taint_all += taint_total
    total_taint_tp += tp
    total_taint_fp += fp
    total_taint_unk += unk

print("-" * 85)
overall_prec = total_taint_tp / total_taint_all * 100 if total_taint_all else 0
print(f"{'TOTAL':<35} {total_taint_all:>12} {total_taint_tp:>8} {total_taint_fp:>8} {total_taint_unk:>8} {overall_prec:>9.2f}%")

# Per-rule breakdown
print("\n=== Per-rule TP/FP breakdown (across all CWEs) ===")
all_rules = set()
for cwe, (t, tp, fp, unk, rc) in per_cwe.items():
    all_rules.update(rc.keys())
rule_totals = defaultdict(lambda: {"TP": 0, "FP": 0, "UNK": 0, "total": 0})
for cwe, (t, tp, fp, unk, rc) in per_cwe.items():
    for rid, c in rc.items():
        for k, v in c.items():
            if k != "total":
                rule_totals[rid][k] += v
        rule_totals[rid]["total"] += c["total"]

print(f"{'Rule':<45} {'Total':>10} {'TP':>8} {'FP':>8} {'UNK':>8} {'Precision':>10}")
print("-" * 95)
for rid in sorted(rule_totals, key=lambda r: -rule_totals[r]["total"]):
    c = rule_totals[rid]
    prec = c["TP"] / c["total"] * 100 if c["total"] else 0
    print(f"{rid:<45} {c['total']:>10} {c['TP']:>8} {c['FP']:>8} {c['UNK']:>8} {prec:>9.2f}%")