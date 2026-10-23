#!/usr/bin/env python3
"""R693 final: merge the new CWE400/606/80/81/83/113/89/78/209 scans
into a single coverage_v3_strict baseline, then run the strict eval.
"""
import csv
import json
import re
import shutil
from pathlib import Path
from collections import defaultdict

SOURCE_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
CSV_PATH = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
R692_FULL = Path(r"D:\tmp\plumb_test\scan\r692_full")
R693_FINAL = Path(r"D:\tmp\plumb_test\scan\r693_full")
R693_FINAL.mkdir(parents=True, exist_ok=True)

# Copy all r692_full files to r693_full as starting baseline
print("[*] Copying r692_full baseline to r693_full...")
for f in R692_FULL.glob("*.ndjson"):
    target = R693_FINAL / f.name
    if not target.exists():
        shutil.copy2(f, target)

# Merge new CWE400 scan (post-R693 task 4 + 5)
R693_C400 = Path(r"D:\tmp\plumb_test\scan\r693_final_CWE400.ndjson")
if R693_C400.exists():
    target = R693_FINAL / "CWE400_Resource_Exhaustion.ndjson"
    shutil.copy2(R693_C400, target)
    print(f"[+] Replaced CWE400 with new scan ({R693_C400.stat().st_size} bytes)")

print("[*] Done. Files in r693_full:")
for f in sorted(R693_FINAL.glob("*.ndjson")):
    print(f"    {f.name}: {f.stat().st_size:,} bytes")
