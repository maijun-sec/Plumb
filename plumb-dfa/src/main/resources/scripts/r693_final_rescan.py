#!/usr/bin/env python3
"""Re-scan CWE80/81/83/113/78/209 with updated YAMLs."""
import os
import subprocess
from pathlib import Path

JAVA_HOME = r"D:\development\jdk-25.0.1"
PLUMB_BIN = Path(r"D:\work\workspace\idea\engine\Plumb\dist-test-package\plumb-0.1.0-SNAPSHOT\bin\plumb.bat")
CONFIGS = Path(r"D:\tmp\plumb_test\configs_v2")
JULIET = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
SCAN = Path(r"D:\tmp\plumb_test\scan")
LOG = Path(r"D:\tmp\plumb_test\logs\r693_final2")
LOG.mkdir(parents=True, exist_ok=True)

os.environ["JAVA_HOME"] = JAVA_HOME
os.environ["PLUMB_JAVA_OPTS"] = "-Xmx5g -XX:+UseG1GC"

# (CWE code, Juliet dir pattern, output name)
CWES = [
    ("CWE80", "CWE80_XSS*", "CWE80_XSS"),
    ("CWE81", "CWE81_XSS_Error_Message*", "CWE81_XSS_Error_Message"),
    ("CWE83", "CWE83_XSS_Attribute*", "CWE83_XSS_Attribute"),
    ("CWE113", "CWE113_HTTP_Response_Splitting*", "CWE113_HTTP_Response_Splitting"),
    ("CWE78", "CWE78_OS_Command_Injection*", "CWE78_OS_Command_Injection"),
    ("CWE209", "CWE209_Information_Leak_Error*", "CWE209_Information_Leak_Error"),
]
for cwe, pattern, outname in CWES:
    cfg = CONFIGS / f"{cwe}.yaml"
    if not cfg.exists():
        print(f"[!] config missing: {cfg}")
        continue
    candidates = [d for d in JULIET.iterdir() if d.name.startswith(cwe + "_")]
    if not candidates:
        print(f"[!] Juliet dir missing for {cwe}")
        continue
    jdir = candidates[0]
    out = SCAN / f"r693f_{cwe}.ndjson"
    log_path = LOG / f"{cwe}.log"
    print(f"[*] Scanning {cwe}: {jdir.name} -> {out.name}")
    with open(log_path, "w", encoding="utf-8") as logf:
        proc = subprocess.run(
            [str(PLUMB_BIN), "-c", str(cfg), "-i", str(jdir),
             "-o", str(out), "--format", "ndjson"],
            stdout=logf, stderr=subprocess.STDOUT,
            env={**os.environ, "JAVA_HOME": JAVA_HOME},
        )
    print(f"    exit={proc.returncode}, size={out.stat().st_size if out.exists() else 0}")
