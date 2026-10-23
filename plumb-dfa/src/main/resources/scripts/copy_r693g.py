#!/usr/bin/env python3
"""Overlay r693g_* scans (mode: all) onto r693_full."""
import shutil
from pathlib import Path

R693_FULL = Path(r"D:\tmp\plumb_test\scan\r693_full")
SCAN = Path(r"D:\tmp\plumb_test\scan")

MAP = [
    ("r693g_CWE80.ndjson", "CWE80_XSS.ndjson"),
    ("r693g_CWE81.ndjson", "CWE81_XSS_Error_Message.ndjson"),
    ("r693f_CWE83.ndjson", "CWE83_XSS_Attribute.ndjson"),  # fall back to r693f for 83
    ("r693g_CWE113.ndjson", "CWE113_HTTP_Response_Splitting.ndjson"),
    ("r693g_CWE78.ndjson", "CWE78_OS_Command_Injection.ndjson"),
    ("r693g_CWE209.ndjson", "CWE209_Information_Leak_Error.ndjson"),
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
