"""Diagnose UNKNOWN classification in strict eval for r698_config_full."""
import csv, json, re
from pathlib import Path
from collections import defaultdict

CSV_PATH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
SOURCE_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
CURRENT_DIR  = Path(r"D:\tmp\plumb_test\scan\r698_config_full")
METHOD_RE = re.compile(r"\b(?:public\s+|private\s+|protected\s+|static\s+)*void\s+(\w+)\s*\(")


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


def method_class(name):
    n = name.lower()
    if n.startswith("bad"): return "TP"
    if n.startswith("good"): return "FP"
    if n.startswith("main"): return "DRIVER"
    return "UNKNOWN"


def is_cross(bn):
    m = re.search(r"_(\d{2,3})[a-z]\.java$", bn)
    return m and int(m.group(1)) >= 22


# Load CWE rows
print("[*] loading CSV ...", flush=True)
target_cwes = ['CWE89', 'CWE789', 'CWE369', 'CWE197', 'CWE190', 'CWE191']
with CSV_PATH.open("r", encoding="utf-8") as f:
    rd = csv.DictReader(f)
    rows = []
    for r in rd:
        if ";" in r["FILES"] or "," in r["FILES"]: continue
        if is_cross(r["FILES"]): continue
        if r["CWE_ID"] in target_cwes:
            rows.append(r)
print(f"[*] {len(rows)} rows")

basename_to_fullpath = {}
for f in SOURCE_ROOT.rglob("*.java"):
    basename_to_fullpath[f.name] = str(f)

# Pre-parse methods for ALL referenced files
referenced = set()
for r in rows:
    bn = Path(r["FILES"]).name
    referenced.add(bn)
methods_cache = {}
for bn in referenced:
    fp = basename_to_fullpath.get(bn)
    if fp and Path(fp).exists():
        methods_cache[fp] = parse_methods(Path(fp))

no_methods_files = [fp for fp in methods_cache if not methods_cache[fp]]
print(f"[*] total files: {len(methods_cache)}, with 0 methods: {len(no_methods_files)}")

# Load all findings from r698_config_full
findings = []
for nd in CURRENT_DIR.rglob("*.ndjson"):
    if nd.stat().st_size == 0: continue
    with nd.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                obj = json.loads(line)
                fp = obj.get("file", "")
                if not fp: continue
                rid = obj.get("rule_id", "")
                if "sql-injection" in rid or "uncontrolled-mem" in rid or "div" in rid or "Truncation" in rid:
                    findings.append((fp, int(obj.get("line") or 0), rid))
            except: continue
print(f"[*] {len(findings)} findings")

# Classify each finding
unknown_method = []
unknown_outside = []
unknown_file = []
tp_count = fp_count = driver_count = 0
for fp, ln, rid in findings:
    methods = methods_cache.get(fp)
    if not methods:
        unknown_file.append(fp)
        continue
    in_method = False
    for name, s, e in methods:
        if s <= ln <= e:
            in_method = True
            cls = method_class(name)
            if cls == "TP": tp_count += 1
            elif cls == "FP": fp_count += 1
            elif cls == "DRIVER": driver_count += 1
            else: unknown_method.append((fp, ln, name))
            break
    if not in_method:
        unknown_outside.append((fp, ln))

print(f"\n=== Classification of findings ===")
print(f"TP: {tp_count}")
print(f"FP: {fp_count}")
print(f"DRIVER (main): {driver_count}")
print(f"UNCLASSIFIED method ({len(unknown_method)}):")
for fp, ln, name in unknown_method[:10]:
    print(f"  {Path(fp).name}:{ln} in method '{name}'")
print(f"\nLine outside any method ({len(unknown_outside)}):")
for fp, ln in unknown_outside[:10]:
    print(f"  {Path(fp).name}:{ln}")
print(f"\nFile with no methods ({len(unknown_file)}):")
for fp in unknown_file[:10]:
    print(f"  {Path(fp).name}")