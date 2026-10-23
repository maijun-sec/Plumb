#!/usr/bin/env python3
"""R695: overlay new CWE scans (especially CWE209)
onto r693_full."""
import shutil
from pathlib import Path

R693_FULL = Path(r"D:\tmp\plumb_test\scan\r693_full")
SCAN = Path(r"D:\tmp\plumb_test\scan")

src = SCAN / "r695_CWE209.ndjson"
dst = R693_FULL / "CWE209_Information_Leak_Error.ndjson"
shutil.copy2(src, dst)
print(f"[+] {dst.name}: {src.stat().st_size:,} bytes")