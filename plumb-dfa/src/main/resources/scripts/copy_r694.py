#!/usr/bin/env python3
"""Overlay r694_* scans onto r693_full."""
import shutil
from pathlib import Path

R693_FULL = Path(r"D:\tmp\plumb_test\scan\r693_full")
SCAN = Path(r"D:\tmp\plumb_test\scan")

# (r694 source file, r693_full target name)
MAP = [
    ("r694_CWE80.ndjson", "CWE80_XSS.ndjson"),
    ("r694_CWE83.ndjson", "CWE83_XSS_Attribute.ndjson"),
    ("r694_CWE113.ndjson", "CWE113_HTTP_Response_Splitting.ndjson"),
    ("r694_CWE789.ndjson", "CWE789_Uncontrolled_Mem_Alloc.ndjson"),
    ("r694_CWE209.ndjson", "CWE209_Information_Leak_Error.ndjson"),
]
for src_name, dst_name in MAP:
    src = SCAN / src_name
    dst = R693_FULL / dst_name
    if not src.exists() or src.stat().st_size == 0:
        print(f"[!] {src_name}: missing or empty")
        continue
    shutil.copy2(src, dst)
    print(f"[+] {dst_name}: {src.stat().st_size:,} bytes")
print("[*] done")
