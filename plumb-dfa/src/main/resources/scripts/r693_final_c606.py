#!/usr/bin/env python3
"""Background: scan CWE606 (and other key CWEs) with the new build
to verify task 4 (ResourceExhaustionChecker) and task 5 (Throwable passthrough).
"""
import os
import sys
import subprocess
from pathlib import Path

JAVA_HOME = r"D:\development\jdk-25.0.1"
PLUMB_BIN = Path(r"D:\work\workspace\idea\engine\Plumb\dist-test-package\plumb-0.1.0-SNAPSHOT\bin\plumb.bat")
CONFIGS = Path(r"D:\tmp\plumb_test\configs_v2")
JULIET = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
SCAN = Path(r"D:\tmp\plumb_test\scan")
LOG = Path(r"D:\tmp\plumb_test\logs\r693_final")
LOG.mkdir(parents=True, exist_ok=True)

os.environ["JAVA_HOME"] = JAVA_HOME
os.environ["PLUMB_JAVA_OPTS"] = "-Xmx5g -XX:+UseG1GC"

CWES = ["CWE606", "CWE80", "CWE81", "CWE83", "CWE113", "CWE89", "CWE78", "CWE209"]
for cwe in CWES:
    cfg = CONFIGS / f"{cwe}.yaml"
    if not cfg.exists():
        print(f"[!] config missing: {cfg}")
        continue
    # find the Juliet dir
    candidates = [d for d in JULIET.iterdir() if d.name.startswith(cwe + "_")]
    if not candidates:
        print(f"[!] Juliet dir missing for {cwe}")
        continue
    jdir = candidates[0]
    out = SCAN / f"r693_final_{cwe}.ndjson"
    log_path = LOG / f"{cwe}.log"
    print(f"[*] Scanning {cwe}: {jdir.name} -> {out.name}")
    with open(log_path, "w", encoding="utf-8") as logf:
        proc = subprocess.run(
            [str(PLUMB_BIN), "-c", str(cfg), "-i", str(jdir),
             "-o", str(out), "--format", "ndjson"],
            stdout=logf, stderr=subprocess.STDOUT,
            env={**os.environ, "JAVA_HOME": JAVA_HOME},
        )
    print(f"    exit={proc.returncode}, log={log_path}")
