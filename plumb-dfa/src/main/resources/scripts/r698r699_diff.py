"""Quick CWE789 TP file diff: r693 vs r698r699."""
import csv, json
from pathlib import Path
from collections import defaultdict
import re

CSV_PATH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
SOURCE_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
BASELINE_DIR = Path(r"D:\tmp\plumb_test\scan\r693_full")
CURRENT_DIR  = Path(r"D:\tmp\plumb_test\scan\r698r699_full")

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


def load_findings(scan_dir, cwe_filter):
    out = defaultdict(list)
    for nd in scan_dir.rglob("*.ndjson"):
        if nd.stat().st_size == 0: continue
        # check the path: r693 has CWE directly named files,
        # r698r699 has CWE-named subdirs.
        if cwe_filter and cwe_filter not in str(nd): continue
        with nd.open("r", encoding="utf-8", errors="replace") as f:
            for line in f:
                try:
                    obj = json.loads(line)
                    file_str = obj.get("file", "") or ""
                    if not file_str: continue
                    bn = Path(file_str).name
                    ln = int(obj.get("line") or 0)
                    rid = obj.get("rule_id", "") or ""
                    if "uncontrolled-mem-alloc" not in rid and "sql-injection" not in rid and "LooseDivByZero" not in rid: continue
                    out[bn].append((ln, rid, file_str))
                except: continue
    return out


# Load only relevant findings
print("[*] loading CWE789 findings ...", flush=True)
b789 = load_findings(BASELINE_DIR, "CWE789")
c789 = load_findings(CURRENT_DIR, "CWE789")
print(f"[*] r693: {sum(len(v) for v in b789.values())} CWE789 findings")
print(f"[*] r698r699: {sum(len(v) for v in c789.values())} CWE789 findings")

basename_to_fullpath = {}
for f in SOURCE_ROOT.rglob("*.java"):
    basename_to_fullpath[f.name] = str(f)

# Pre-parse CWE789 methods
referenced = set(b789.keys()) | set(c789.keys())
methods_cache = {}
for bn in sorted(referenced):
    fp = basename_to_fullpath.get(bn)
    if fp and Path(fp).exists():
        methods_cache[fp] = parse_methods(Path(fp))

def classify(file, line):
    methods = methods_cache.get(file)
    if not methods: return "?"
    for name, start, end in methods:
        if start <= line <= end:
            n = name.lower()
            if n.startswith("bad"): return "TP"
            if n.startswith("good"): return "FP"
    return "?"

def tp_files(findings):
    out = set()
    for bn, fs in findings.items():
        for ln, rid, fp in fs:
            if "uncontrolled-mem-alloc" not in rid: continue
            cls = classify(fp, ln)
            if cls == "TP":
                out.add(bn)
    return out

b_tp = tp_files(b789)
c_tp = tp_files(c789)
print(f"\n=== CWE789 TP files ===")
print(f"R693: {len(b_tp)}, R698R699: {len(c_tp)}")
new_tp = c_tp - b_tp
lost_tp = b_tp - c_tp
print(f"NEW (+): {len(new_tp)}")
for bn in sorted(new_tp)[:20]:
    print(f"  + {bn}")
print(f"LOST (-): {len(lost_tp)}")
for bn in sorted(lost_tp)[:20]:
    print(f"  - {bn}")