"""
coverage_v3_strict.py 鈥?R691 strict coverage analysis.

A finding is a TP only if:
  (a) it's in the bad() method of a positive file, AND
  (b) its rule_id is in the per-CWE mapping (plumb_rule_to_cwe.csv + dfa_rule_cwe_map.csv)

This is the most precise FN measurement.

R703 note: METHOD_RE widened from "void only" to "any return type" so that
methods returning int / short / long / boolean / String / Helper etc. are
correctly captured. The earlier strict-void regex silently dropped methods
like `private int badSource()` (Variant 42 / Variant 45 of Juliet CWE190),
which made every finding inside that helper method fall outside any parsed
range and classify as UNKNOWN. The widen is safe: parse_methods_java() now
filters out lines that look like constructor calls (`new ClassName(`) and
control-flow keywords (if / while / for / switch / return / throw / catch /
synchronized), so plain field declarations and new-expression rows are
still rejected.
"""
import csv, json, re
from pathlib import Path
from collections import defaultdict, Counter

CSV_PATH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
SOURCE_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
BASELINE_DIR = Path(r"D:\tmp\plumb_test\scan\r704_merged")
OUTPUT_DIR = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\resources\scripts\r704_summary")

# R703: match any return type, then filter out non-method lines in
# parse_methods_java().
_METHOD_DECL_RE = re.compile(
    r"^(?P<indent>\s*)(?P<mods>(?:(?:public|private|protected|static)\s+)*)"
    r"(?P<rtype>[\w<>,\s\[\]\.]+?)\s+(?P<mname>\w+)\s*\("
)
_KEYWORDS = {"if", "while", "for", "switch", "return", "throw", "catch", "synchronized"}
MULTI_FILE_RE = re.compile(r"[;,]")


def is_multi_file_test(basename: str) -> bool:
    if MULTI_FILE_RE.search(basename):
        return True
    return False


def is_cross_file_variant(basename: str) -> bool:
    m = re.search(r"_(\d{2,3})[a-z]\.java$", basename)
    if m and int(m.group(1)) >= 22:
        return True
    return False


def parse_methods_java(path):
    """R703 method parser.

    Two-step:
      1. regex finds lines that look like method declarations
         (any return type, including int / short / long / String / Holder)
      2. filter out:
           * `new ClassName(args)` constructor calls (the type token preceded by
             `new ` on the same line 鈥?these are field init / local decl, not
             methods)
           * control-flow keywords (`if`, `while`, ...)
           * empty return type
    """
    methods = []
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = _METHOD_DECL_RE.match(line)
        if not m:
            continue
        # Reject constructor-style `new ClassName(args)` (lhs is the type,
        # not a method name).
        if "new " in line[: m.start("rtype")]:
            continue
        rtype = m.group("rtype").strip()
        mname = m.group("mname")
        if not rtype:
            continue
        if rtype in _KEYWORDS:
            continue
        methods.append([mname, i + 1, None])
    for i, (name, start, _) in enumerate(methods):
        if i + 1 < len(methods):
            methods[i][2] = methods[i + 1][1] - 1
        else:
            end = start + 500
            for j in range(start, len(lines)):
                if re.match(r"^\s*\}\s*$", lines[j]):
                    end = j + 1
                    break
            methods[i][2] = end
    return [(n, s, e) for n, s, e in methods]


def method_class(name):
    n = name.lower()
    if n.startswith("bad"): return "TP"
    if n.startswith("good"): return "FP"
    if n.startswith("main"): return "DRIVER"
    return "UNKNOWN"


# Load rule鈫扖WE maps (both registry and dfa)
def load_rule_cwe_map(path):
    m = {}
    if not path.exists(): return m
    with path.open("r", encoding="utf-8") as f:
        rd = csv.DictReader(f)
        for r in rd:
            try:
                if int(r["n_cwe"]) <= 0: continue
            except: continue
            rid = r["rule_id"].strip()
            cwes = [c.strip() for c in r["cwes"].split(";") if c.strip()]
            for c in cwes:
                if c not in m: m[c] = set()
                m[c].add(rid)
                # Also add CWE89 / CWE-89 forms for direct lookup
                if c.isdigit():
                    m.setdefault(f"CWE{c}", set()).add(rid)
                    m.setdefault(f"CWE-{c}", set()).add(rid)
    return m


rule_to_cwe = {}
for p in [
    Path(r"D:\tmp\plumb_test\plumb_rule_to_cwe.csv"),
    Path(r"D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\resources\scripts\plumb_rule_to_cwe.csv"),
]:
    rm = load_rule_cwe_map(p)
    for c, rids in rm.items():
        if c not in rule_to_cwe: rule_to_cwe[c] = set()
        rule_to_cwe[c] |= rids


print("[*] rule_to_cwe map:", sum(len(v) for v in rule_to_cwe.values()), "mappings")
print("[*] sample 89:", len(rule_to_cwe.get("89", set())), "CWE89:", len(rule_to_cwe.get("CWE89", set())))
print("[*] sample 80:", len(rule_to_cwe.get("80", set())), "CWE80:", len(rule_to_cwe.get("CWE80", set())))


print("[*] loading CSV ...", flush=True)
rows = []
with CSV_PATH.open("r", encoding="utf-8") as f:
    rd = csv.DictReader(f)
    for row in rd:
        bn = row["FILES"]
        if is_multi_file_test(bn): continue
        if is_cross_file_variant(bn): continue
        rows.append(row)
print(f"[*] {len(rows)} rows after filtering")
by_cwe = defaultdict(list)
for r in rows: by_cwe[r["CWE_ID"]].append(r)
print(f"[*] {len(by_cwe)} CWEs")


print("[*] building basename -> fullpath ...", flush=True)
basename_to_fullpath = {}
for f in SOURCE_ROOT.rglob("*.java"):
    basename_to_fullpath[f.name] = str(f)


print("[*] indexing baseline findings ...", flush=True)
findings_by_basename = defaultdict(list)
total = 0
for nd in BASELINE_DIR.glob("*.ndjson"):
    if not nd.exists() or nd.stat().st_size == 0: continue
    with nd.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                obj = json.loads(line)
                file_str = obj.get("file", "") or ""
                if not file_str: continue
                bn = Path(file_str).name
                ln = int(obj.get("line") or 0)
                rid = obj.get("rule_id", "") or ""
                findings_by_basename[bn].append((ln, rid, file_str))
                total += 1
            except: continue
print(f"[*] {total} findings")


print("[*] parsing methods ...", flush=True)
methods_cache = {}
referenced = set()
for cwe_id, cwe_rows in by_cwe.items():
    for r in cwe_rows:
        bn = Path(r["FILES"]).name
        if bn in findings_by_basename:
            referenced.add(bn)
parsed = 0
for bn in sorted(referenced):
    fp = basename_to_fullpath.get(bn)
    if fp and Path(fp).exists():
        methods_cache[fp] = parse_methods_java(Path(fp))
        parsed += 1
print(f"[*] parsed {parsed}")


def classify(file, line):
    methods = methods_cache.get(file)
    if not methods: return "UNKNOWN", "?"
    for name, start, end in methods:
        if start <= line <= end:
            return method_class(name), name
    return "UNKNOWN", "?"


print("\n=== Global Coverage (R702 strict: R703 v3 parser + rule-CWE mapped) ===", flush=True)
per_cwe_stats = {}
all_missed = []
fp_top_rules = Counter()
for cwe_id, cwe_rows in sorted(by_cwe.items()):
    pos = [r for r in cwe_rows if r["POSITIVE"] == "True"]
    neg = [r for r in cwe_rows if r["NEGATIVE"] == "True"]
    n_pos = len(pos); n_neg = len(neg)
    relevant_rules = rule_to_cwe.get(cwe_id, set())
    pos_with_tp = 0; pos_missed = []; rule_tp = Counter(); n_fp_files = 0
    if cwe_id in ("CWE89", "CWE369", "CWE197"):
        print(f"DEBUG cwe={cwe_id} relevant_rules={sorted(relevant_rules)}")
    for r in pos:
        if cwe_id == "CWE89" and r["FILES"] == "CWE89_SQL_Injection__connect_tcp_execute_01.java":
            print(f"DEBUG matching row: bn={bn}")
        bn = Path(r["FILES"]).name
        fp = basename_to_fullpath.get(bn)
        if fp and Path(fp).exists():
            file_findings = [(l, rid) for l, rid, f in findings_by_basename.get(bn, []) if f == fp]
            # STRICT: only count findings whose rule_id maps to this CWE
            relevant_finds = [(l, rid) for l, rid in file_findings if rid in relevant_rules]
            bad = [(l, rid) for l, rid in relevant_finds if classify(fp, l)[0] == "TP"]
            if bad:
                pos_with_tp += 1
                for _, rid in bad:
                    rule_tp[rid] += 1
            else:
                pos_missed.append((bn, fp, len(file_findings), len(relevant_finds)))
                all_missed.append((cwe_id, bn, fp, len(file_findings), len(relevant_finds)))
        else:
            pos_missed.append((bn, "PATH_NOT_FOUND", 0, 0))
            all_missed.append((cwe_id, bn, "PATH_NOT_FOUND", 0, 0))
    # Count FP files for negative rows
    for r in neg:
        bn = Path(r["FILES"]).name
        fp = basename_to_fullpath.get(bn)
        if fp and Path(fp).exists():
            file_findings = [(l, rid) for l, rid, f in findings_by_basename.get(bn, []) if f == fp]
            relevant_finds = [(l, rid) for l, rid in file_findings if rid in relevant_rules]
            bad = [(l, rid) for l, rid in relevant_finds if classify(fp, l)[0] == "TP"]
            if bad:
                n_fp_files += 1
                for _, rid in bad: fp_top_rules[rid] += 1
    p = (pos_with_tp / (pos_with_tp + n_fp_files) * 100) if (pos_with_tp + n_fp_files) else 0
    r2 = (pos_with_tp / n_pos * 100) if n_pos else 0
    per_cwe_stats[cwe_id] = {
        "n_pos": n_pos, "n_neg": n_neg, "tp": pos_with_tp, "fn": len(pos_missed),
        "fp_files": n_fp_files, "precision": p, "recall": r2,
        "top_rule": rule_tp.most_common(1)[0][0] if rule_tp else "<none>",
        "relevant_rules": sorted(relevant_rules),
    }

# Write summary
with (OUTPUT_DIR / "coverage_summary.csv").open("w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow(["CWE_ID", "Pos", "Neg", "TP", "FN", "FP_files", "Precision%", "Recall%", "Top_TP_Rule"])
    for cwe, s in sorted(per_cwe_stats.items()):
        w.writerow([cwe, s["n_pos"], s["n_neg"], s["tp"], s["fn"], s["fp_files"],
                    f"{s['precision']:.2f}", f"{s['recall']:.2f}", s["top_rule"]])

with (OUTPUT_DIR / "all_missed_files.csv").open("w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow(["CWE_ID", "Basename", "FullPath", "TotalFindings", "RelevantFindings"])
    for row in all_missed:
        w.writerow(row)

# Aggregate
total_pos = sum(s["n_pos"] for s in per_cwe_stats.values())
total_tp = sum(s["tp"] for s in per_cwe_stats.values())
total_fn = sum(s["fn"] for s in per_cwe_stats.values())
total_fp = sum(s["fp_files"] for s in per_cwe_stats.values())
agg_recall = total_tp / total_pos * 100 if total_pos else 0
agg_prec = total_tp / (total_tp + total_fp) * 100 if (total_tp + total_fp) else 0

print(f"\n=== Aggregate ===")
print(f"Total POSITIVE (single-file, no cross-file variant): {total_pos}")
print(f"Total TP: {total_tp}")
print(f"Total FN: {total_fn}")
print(f"Total FP_files: {total_fp}")
print(f"Aggregate Recall: {agg_recall:.2f}%")
print(f"Aggregate Precision: {agg_prec:.2f}%")

# Distribution
buckets = {"100%": 0, "80-99%": 0, "50-79%": 0, "10-49%": 0, "1-9%": 0, "0%": 0}
for s in per_cwe_stats.values():
    r = s["recall"]
    if r >= 100: buckets["100%"] += 1
    elif r >= 80: buckets["80-99%"] += 1
    elif r >= 50: buckets["50-79%"] += 1
    elif r >= 10: buckets["10-49%"] += 1
    elif r > 0: buckets["1-9%"] += 1
    else: buckets["0%"] += 1
print(f"\n=== Recall Distribution ===")
for k, v in buckets.items(): print(f"  {k}: {v} CWEs")

# Per-CWE table
print(f"\n=== Per-CWE Strict Coverage ===")
sorted_cwes = sorted(per_cwe_stats.items(), key=lambda x: x[1]["recall"], reverse=True)
for cwe, s in sorted_cwes:
    if s["fn"] > 0:
        print(f"{cwe:8} {s['n_pos']:5} {s['n_neg']:5} {s['tp']:5} {s['fn']:5} {s['fp_files']:5} "
              f"{s['precision']:6.2f} {s['recall']:6.2f} {s['top_rule']}")