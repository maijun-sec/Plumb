#!/usr/bin/env python3
"""Measure what a sink family's declared type does to a corpus, by scanning it twice.

    python scripts/measure_type_gate.py <corpus-dir> [--classes DIR] [--classpath CP]

A family may declare the type its argument must have (`expects_type`). The
engine then suppresses a finding when the value is PROVABLY not of that type.
That is a rule about the product, and a rule that can lose a finding has to be
MEASURED rather than argued about.

So the corpus is scanned twice, and the ONLY difference between the two runs
is the catalogue: the first run reads it as it is, the second reads a copy
with the `expects_type` lines removed. The engine, the front end and the
corpus are the same in both, so a difference in the findings is the gate's
doing and nothing else's.

The comparison is by (file, line, rule id, message), so a finding that moved
counts as a difference.

## Why the catalogue is swapped rather than a flag

The catalogue is a classpath resource, so a copy of the engine's classes with
the lines removed is enough to switch the gate off -- and that is what makes
the two runs comparable. A jar cannot do it: the catalogue is baked into it.

## What the engine's classes are

    mvn -o -q -DskipTests package

The classpath is the module's own target/classes plus the dependency jars.
The dependency jars come from Maven's own output when it is there, and the
modules' target/classes otherwise:

    mvn -o -q -pl plumb-main dependency:build-classpath \\
        -Dmdep.outputFile=.aethercode/main_cp.txt -Dmdep.includeScope=compile
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent

# The modules whose classes the scan needs, in the order they are put on the
# classpath. The engine's own classes come FIRST, because the catalogue is a
# resource and the first copy on the classpath is the one that is read.
MODULES = (
    "plumb-dfa",
    "plumb-tac",
    "plumb-parser",
    "plumb-reporter",
    "plumb-rules",
    "plumb-frontend",
    "plumb-main",
    "plumb-ai",
    "plumb-style",
)

CLASSPATH_CANDIDATES = (
    REPO / ".aethercode" / "main_cp.txt",
    REPO / "plumb-main" / ".aethercode" / "main_cp.txt",
)

# The four fields a finding is identified by.
KEYS = ("file", "line", "ruleId", "message")


def dependency_jars():
    """The dependency jars, from Maven's own classpath output when it is there."""
    for candidate in CLASSPATH_CANDIDATES:
        if candidate.is_file():
            text = candidate.read_text(encoding="utf-8", errors="replace").strip()
            entries = [p for p in text.split(";") if p.strip()]
            if entries:
                return entries
    return []


def classpath(classes):
    """The classpath the scan runs with."""
    entries = [str(classes)]
    for module in MODULES:
        target = REPO / module / "target" / "classes"
        if target.is_dir():
            entries.append(str(target))
    entries.extend(dependency_jars())
    return os.pathsep.join(entries)


def catalogue_without_types(classes, workdir):
    """A copy of the engine's classes whose catalogue declares no type."""
    copy = workdir / "classes-without-types"
    shutil.copytree(classes, copy)
    catalogue = copy / "sink-families.yaml"
    if not catalogue.is_file():
        raise SystemExit("%s does not hold the catalogue" % classes)
    text = catalogue.read_text(encoding="utf-8", newline="")
    kept = [line for line in text.splitlines(keepends=True)
            if not line.strip().startswith("expects_type:")]
    removed = len(text.splitlines()) - len(kept)
    if removed == 0:
        raise SystemExit("the catalogue declares no type, so there is nothing to switch off")
    catalogue.write_text("".join(kept), encoding="utf-8", newline="")
    return copy, removed


def scan(classes, corpus, out, label):
    """Scan the corpus and return the findings, each as a comparable tuple."""
    if out.exists():
        out.unlink()
    cmd = [
        "java",
        "-Xmx1600m",
        "-cp",
        classpath(classes),
        "org.plumb.main.SastMain",
        "scan",
        "-i",
        str(corpus),
        "-f",
        "ndjson",
        "-o",
        str(out),
    ]
    print("  %s: scanning..." % label)
    proc = subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if not out.is_file():
        raise SystemExit("the %s scan produced no findings file:\n%s"
                         % (label, proc.stderr[-2000:]))
    findings = []
    for line in out.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        findings.append(tuple(str(record.get(k, "")) for k in KEYS))
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", help="the directory of source files to scan")
    parser.add_argument("--classes", default=str(REPO / "plumb-dfa" / "target" / "classes"),
                        help="the engine's compiled classes (the catalogue is read from here)")
    args = parser.parse_args()

    corpus = pathlib.Path(args.corpus)
    classes = pathlib.Path(args.classes)
    if not corpus.is_dir():
        raise SystemExit("%s is not a directory" % corpus)
    if not classes.is_dir():
        raise SystemExit("%s does not exist -- build the project first" % classes)

    with tempfile.TemporaryDirectory(prefix="plumb-type-gate-") as tmp:
        workdir = pathlib.Path(tmp)
        without, removed = catalogue_without_types(classes, workdir)
        print("catalogue: %d `expects_type` line(s) removed for the second run" % removed)

        with_gate = scan(classes, corpus, workdir / "with.ndjson", "with the gate")
        without_gate = scan(without, corpus, workdir / "without.ndjson", "without the gate")

    only_with = sorted(set(with_gate) - set(without_gate))
    only_without = sorted(set(without_gate) - set(with_gate))

    print("with the gate   : %d finding(s)" % len(with_gate))
    print("without the gate: %d finding(s)" % len(without_gate))
    print("only with the gate   : %d" % len(only_with))
    print("only without the gate: %d" % len(only_without))
    for record in only_with[:20]:
        print("  + %s" % (record,))
    for record in only_without[:20]:
        print("  - %s" % (record,))

    if not only_with and not only_without:
        print("THE GATE CHANGED NOTHING ON THIS CORPUS")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
