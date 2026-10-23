"""Quick CWE197 TP count."""
import csv, json, re
from pathlib import Path
from collections import defaultdict

CSV_PATH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
SOURCE_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
BASELINE_DIR = Path(r"D:\tmp\plumb_test\scan\r693_full")
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


def is_cross(bn):
    m = re.search(r"_(\d{2,3})[a-z]\.java$", bn)
    return m and int(m.group(1)) >= 22


# Load CWE197 rows
print("[*] loading CSV ...", flush=True)
with CSV_PATH.open("r", encoding="utf-8") as f:
    rd = csv.DictReader(f)
    cwe197_rows = [r for r in rd if r["CWE_ID"] == "CWE197"
                   and ";" not in r["FILES"] and "," not in r["FILES"]
                   and not is_cross(r["FILES"])]

basename_to_fullpath = {}
for f in SOURCE_ROOT.rglob("*.java"):
    basename_to_fullpath[f.name] = str(f)

methods_cache = {}
for r in cwe197_rows:
    bn = Path(r["FILES"]).name
    fp = basename_to_fullpath.get(bn)
    if fp and Path(fp).exists():
        methods_cache[fp] = parse_methods(Path(fp))

# Load r693 CWE197 findings
findings_by_bn = defaultdict(list)
for nd in BASELINE_DIR.rglob("*.ndjson"):
    if "CWE197" not in nd.name: continue
    if nd.stat().st_size == 0: continue
    with nd.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                obj = json.loads(line)
                fp = obj.get("file", "")
                if not fp: continue
                bn = Path(fp).name
                ln = int(obj.get("line") or 0)
                rid = obj.get("rule_id", "")
                if "Truncation" not in rid: continue
                findings_by_bn[bn].append((ln, rid, fp))
            except: continue

print(f"[*] {sum(len(v) for v in findings_by_bn.values())} CWE197 truncation findings")

# Count TP/FP
target_rule = "LooseNumericTruncation"  # friendly name from R697 CSV
tp = fp = 0
for r in cwe197_rows:
    bn = Path(r["FILES"]).name
    fp_path = basename_to_fullpath.get(bn)
    if not fp_path: continue
    methods = methods_cache.get(fp_path, [])
    is_pos = (r["POSITIVE"] == "True")
    fired = False
    for ln, rid, fp2 in findings_by_bn.get(bn, []):
        if fp2 != fp_path: continue
        if rid != target_rule and rid != "loose.numeric.truncation": continue
        for name, start, end in methods:
            if start <= ln <= end:
                if name.startswith("bad"):
                    tp += 1; fired = True; break
                elif name.startswith("good"):
                    fp += 1; fired = True; break
        if fired: break

print(f"\n=== CWE197 TP: {tp}, FP: {fp} ===")