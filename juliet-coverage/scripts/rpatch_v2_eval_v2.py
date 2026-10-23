"""
Phase 10 v2: 用 RuleRegistry 实际加载 JSON，生成完整 alias map。
需要先把 plumb-rules, plumb-dfa, plumb-style install 到 local repo.
"""
import sys
import subprocess
import os
import shutil
from pathlib import Path

# Set up classpath via local m2 repo
M2 = Path(r"D:\cache\mvn_repo")
RESOLVED = subprocess.run(
    ["cmd", "/c", "mvn", "-q", "dependency:build-classpath", "-Dmdep.outputFile=cp.txt", "-pl", "plumb-rules"],
    cwd=str(Path(r"D:\work\workspace\idea\engine\Plumb")),
    capture_output=True, text=True
)
print(RESOLVED.stdout[-500:] if RESOLVED.stdout else "")
print(RESOLVED.stderr[-500:] if RESOLVED.stderr else "")

cp_file = Path(r"D:\work\workspace\idea\engine\Plumb\cp.txt")
if cp_file.exists():
    cp = cp_file.read_text().strip()
    print(f"Classpath entries: {len(cp.split(';'))}")
else:
    print("cp.txt not generated; falling back to manual classpath")
    cp = ""

# Save classpath for the eval script
Path(r"D:\work\workspace\idea\engine\Plumb\juliet-coverage\scripts\_classpath.txt").write_text(cp, encoding="utf-8")