#!/usr/bin/env python3
"""Merge R693 final scan files into r693_full."""
import shutil
from pathlib import Path

R693_FINAL = Path(r"D:\tmp\plumb_test\scan\r693_full")
R693_FINAL.mkdir(parents=True, exist_ok=True)

# Map CWE -> expected Juliet dir basename
CWE_TO_JULIET_DIR = {
    "CWE606": "CWE606_Unchecked_Loop_Condition",
    "CWE80": "CWE80_XSS",
    "CWE81": "CWE81_XSS_Error_Message",
    "CWE83": "CWE83_XSS_Attribute",
    "CWE113": "CWE113_HTTP_Response_Splitting",
    "CWE89": "CWE89_SQL_Injection",
    "CWE78": "CWE78_OS_Command_Injection",
    "CWE209": "CWE209_Information_Leak_Error",
}

for cwe, juliet_dir in CWE_TO_JULIET_DIR.items():
    src = Path(r"D:\tmp\plumb_test\scan") / f"r693_final_{cwe}.ndjson"
    if not src.exists():
        print(f"[!] {cwe}: source missing {src}")
        continue
    dst = R693_FINAL / f"{juliet_dir}.ndjson"
    shutil.copy2(src, dst)
    print(f"[+] {cwe}: {src.stat().st_size:,} bytes -> {dst.name}")
print("[*] done")
