#!/usr/bin/env python3
"""R693 final: keep r692_full as baseline, but overlay only the
CWE400 and CWE606 new scans (the only CWEs where the new rules
actually fire something the r692 scan couldn't).
"""
import shutil
from pathlib import Path

R692_FULL = Path(r"D:\tmp\plumb_test\scan\r692_full")
R693_FINAL = Path(r"D:\tmp\plumb_test\scan\r693_full")
R693_FINAL.mkdir(parents=True, exist_ok=True)

# Start fresh: copy r692_full -> r693_full
print("[*] Rebuilding r693_full from r692_full + CWE400/606 overlays...")
for f in R692_FULL.glob("*.ndjson"):
    target = R693_FINAL / f.name
    shutil.copy2(f, target)

# Only overlay CWE400 and CWE606 (newly enabled rules)
for cwe, src_name in [
    ("CWE400", "r693_final_CWE400.ndjson"),
    ("CWE606", "r693_final_CWE606.ndjson"),
]:
    src = Path(r"D:\tmp\plumb_test\scan") / src_name
    if not src.exists():
        print(f"[!] {cwe}: source missing {src}")
        continue
    # Find the Juliet dir name in r692_full matching this CWE
    matches = list(R692_FULL.glob(f"{cwe}_*.ndjson"))
    if not matches:
        print(f"[!] {cwe}: no r692 baseline file")
        continue
    dst = R693_FINAL / matches[0].name
    shutil.copy2(src, dst)
    print(f"[+] {cwe}: overlaid {matches[0].name} ({src.stat().st_size:,} bytes)")

print("[*] Done.")
