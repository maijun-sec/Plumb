#!/usr/bin/env python3
"""Overlay CWE789 new scan onto r693_full."""
import shutil
from pathlib import Path

src = Path(r"D:\tmp\plumb_test\scan\r694_CWE789_full.ndjson")
dst = Path(r"D:\tmp\plumb_test\scan\r693_full\CWE789_Uncontrolled_Mem_Alloc.ndjson")
shutil.copy2(src, dst)
print(f"[+] {dst}: {src.stat().st_size:,} bytes")