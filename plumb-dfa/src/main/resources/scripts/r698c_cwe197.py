"""Compute CWE197 TP/FP for r698_config_full."""
import csv, json, re
from pathlib import Path
from collections import defaultdict

CSV_PATH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
SOURCE_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
CURRENT_DIR  = Path(r"D:\tmp\plumb_test\scan\r698_config_full\CWE197")
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
        if r["CWE_ID"] == "CWE197":
            rows.append(r)

basename_to_fullpath = {}
for f in SOURCE_ROOT.rglob("*.java"):
    basename_to_fullpath[f.name] = str(f)

methods_cache = {}
for r in rows:
    bn = Path(r["FILES"]).name
    fp = basename_to_fullpath.get(bn)
    if fp and Path(fp).exists():
        methods_cache[fp] = parse_methods(Path(fp))

findings = []
for nd in CURRENT_DIR.rglob("*.ndjson"):
    if nd.stat().st_size == 0: continue
    with nd.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                obj = json.loads(line)
                fp = obj.get("file", "")
                if not fp: continue
                if "Truncation" not in (obj.get("rule_id", "") or ""): continue
                findings.append((fp, int(obj.get("line") or 0), obj.get("rule_id", "")))
            except: continue

print(f"[*] {len(findings)} CWE197 truncation findings")

tp = fp = 0
for r in rows:
    bn = Path(r["FILES"]).name
    fp_path = basename_to_fullpath.get(bn)
    if not fp_path: continue
    is_pos = (r["POSITIVE"] == "True")
    methods = methods_cache.get(fp_path, [])
    for (f_fp, ln, rid) in findings:
        if rid != "LooseNumericTruncation": continue
        if f_fp != fp_path: continue
        for name, s, e in methods:
            if s <= ln <= e:
                if name.startswith("bad"):
                    tp += 1; break
                elif name.startswith("good"):
                    fp += 1; break
        else:
            continue
        break

print(f"\n=== CWE197 TP: {tp}, FP: {fp} ===")
print(f"Total positive: {sum(1 for r in rows if r['POSITIVE'] == 'True')}, negative: {sum(1 for r in rows if r['NEGATIVE'] == 'True')}")