"""Compare r693_full vs r698_config_full for the 3 target CWEs."""
import csv, json, re
from pathlib import Path
from collections import defaultdict

CSV_PATH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
SOURCE_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
BASELINE_DIR = Path(r"D:\tmp\plumb_test\scan\r693_full")
CURRENT_DIR  = Path(r"D:\tmp\plumb_test\scan\r698_config_full")
OUTPUT_DIR = Path(r"D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\resources\scripts\r698_config_summary")

METHOD_RE = re.compile(r"\b(?:public\s+|private\s+|protected\s+|static\s+)*void\s+(\w+)\s*\(")


def method_class(name):
    n = name.lower()
    if n.startswith("bad"): return "TP"
    if n.startswith("good"): return "FP"
    return "UNKNOWN"


def parse_methods(path):
    methods = []
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = METHOD_RE.search(line)
        if m: methods.append([m.group(1), i + 1, None])
    for i, (name, start, _) in enumerate(methods):
        if i + 1 < len(methods):
            methods[i][2] = methods[i + 1][1] - 1
        else:
            end = start + 500
            for j in range(start, len(lines)):
                if re.match(r"^\s*\}\s*$", lines[j]):
                    end = j + 1; break
            methods[i][2] = end
    return [(n, s, e) for n, s, e in methods]


def classify(file, line, methods_cache):
    methods = methods_cache.get(file)
    if not methods: return "UNKNOWN", "?"
    for name, start, end in methods:
        if start <= line <= end: return method_class(name), name
    return "UNKNOWN", "?"


def is_cross(basename):
    m = re.search(r"_(\d{2,3})[a-z]\.java$", basename)
    return m and int(m.group(1)) >= 22


def is_multi(bn):
    return ";" in bn or "," in bn


print("[*] loading CSV ...", flush=True)
with CSV_PATH.open("r", encoding="utf-8") as f:
    rd = csv.DictReader(f)
    rows = []
    for r in rd:
        if is_multi(r["FILES"]): continue
        if is_cross(r["FILES"]): continue
        rows.append(r)

by_cwe = defaultdict(list)
for r in rows: by_cwe[r["CWE_ID"]].append(r)

basename_to_fullpath = {}
for f in SOURCE_ROOT.rglob("*.java"):
    basename_to_fullpath[f.name] = str(f)


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
                m.setdefault(c, set()).add(rid)
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
        rule_to_cwe.setdefault(c, set()).update(rids)


def load_findings(scan_dir, target_rules):
    out = defaultdict(list)
    total = 0
    for nd in scan_dir.rglob("*.ndjson"):
        if nd.stat().st_size == 0: continue
        with nd.open("r", encoding="utf-8", errors="replace") as f:
            for line in f:
                try:
                    obj = json.loads(line)
                    fp = obj.get("file", "") or ""
                    if not fp: continue
                    bn = Path(fp).name
                    ln = int(obj.get("line") or 0)
                    rid = obj.get("rule_id", "") or ""
                    if rid not in target_rules: continue
                    out[bn].append((ln, rid, fp))
                    total += 1
                except: continue
    return out


target_rules = {"plumb.taint.sql-injection", "plumb.taint.uncontrolled-mem-alloc", "plumb.numeric.divide-by-zero", "LooseDivByZero", "loose.numeric.truncation", "LooseNumericTruncation", "loose.numeric.integer-overflow", "LooseIntOverflow", "loose.numeric.integer-underflow", "LooseIntUnderflow", "plumb.numeric.integer-overflow", "plumb.numeric.integer-underflow", "plumb.numeric.numeric-truncation", "NumericTruncation", "plumb.dfa.resource-exhaustion"}
b = load_findings(BASELINE_DIR, target_rules)
c = load_findings(CURRENT_DIR, target_rules)
print(f"[*] baseline findings: {sum(len(v) for v in b.values())}, current: {sum(len(v) for v in c.values())}")

methods_cache = {}
referenced = set()
for cwe_id, rs in by_cwe.items():
    for r in rs:
        bn = Path(r["FILES"]).name
        if bn in b or bn in c:
            referenced.add(bn)
for bn in referenced:
    fp = basename_to_fullpath.get(bn)
    if fp and Path(fp).exists():
        methods_cache[fp] = parse_methods(Path(fp))

print(f"[*] parsed {len(methods_cache)}")

print("\n=== Coverage compare (R698 config vs R693) ===")
header = f"{'CWE':8} {'Pos':>5} {'Rule':45} {'R693_TP':>10} {'R698C_TP':>10} {'ΔTP':>8} {'R693_FP':>10} {'R698C_FP':>10} {'ΔFP':>8}"
print(header)
results = []

current_cwes = set()
for bn, fs in c.items():
    for ln, rid, fp in fs:
        m = re.search(r"CWE(\d+)_", bn)
        if m: current_cwes.add(f"CWE{m.group(1)}")

for cwe_id in sorted(by_cwe.keys()):
    if cwe_id not in current_cwes: continue
    pos = [r for r in by_cwe[cwe_id] if r["POSITIVE"] == "True"]
    neg = [r for r in by_cwe[cwe_id] if r["NEGATIVE"] == "True"]
    relevant_rules = rule_to_cwe.get(cwe_id, set())
    for rule in sorted(relevant_rules):
        def tp_fp(findings):
            tp_files = n_fp_files = 0
            for r in pos:
                bn = Path(r["FILES"]).name
                fp = basename_to_fullpath.get(bn)
                if fp and Path(fp).exists():
                    file_findings = [(l, rid) for l, rid, f in findings.get(bn, []) if f == fp and rid == rule]
                    if any(classify(fp, l, methods_cache)[0] == "TP" for l, _ in file_findings):
                        tp_files += 1
            for r in neg:
                bn = Path(r["FILES"]).name
                fp = basename_to_fullpath.get(bn)
                if fp and Path(fp).exists():
                    file_findings = [(l, rid) for l, rid, f in findings.get(bn, []) if f == fp and rid == rule]
                    if any(classify(fp, l, methods_cache)[0] == "TP" for l, _ in file_findings):
                        n_fp_files += 1
            return tp_files, n_fp_files
        b_tp, b_fp = tp_fp(b)
        c_tp, c_fp = tp_fp(c)
        if b_tp or c_tp:
            print(f"{cwe_id:8} {len(pos):>5} {rule:45} {b_tp:>10} {c_tp:>10} {c_tp-b_tp:>+8} {b_fp:>10} {c_fp:>10} {c_fp-b_fp:>+8}")
            results.append((cwe_id, rule, b_tp, c_tp, b_fp, c_fp))


with (OUTPUT_DIR / "compare_summary.csv").open("w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow(["CWE", "Rule", "R693_TP", "R698C_TP", "Delta_TP", "R693_FP", "R698C_FP", "Delta_FP"])
    for r in results: w.writerow(r)
print(f"\n[*] summary written to {OUTPUT_DIR / 'compare_summary.csv'}")