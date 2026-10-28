#!/usr/bin/env python3
"""Measure a CWE's recall and precision on the Juliet test suite.

This is a SELF-CONTAINED measurement, not a wrapper around the historical
R-* pipelines (those are round-specific and their reports are partly
encoding-damaged).  It does the three things a measurement needs:

  1. scan the CWE's testcase directory with the built plumb jar,
  2. read Juliet's ground truth (testcases_detail.csv),
  3. classify each finding as TP or FP and print the per-CWE numbers.

## The unit of judgement is the METHOD, not the file

A Juliet testcase file usually contains BOTH the vulnerable method and the
fixed one:

    public void bad()  { if (fis.read(byteArray) == 0) { ... } }   // the bug
    private void good1() { int n = fis.read(byteArray); ... }       // the fix

So a finding is a TP when it lands in a `bad*` method and an FP when it
lands in a `good*` method of the SAME file.  A per-file measurement would
report every finding as a TP and the recall would come out above 1.

## The rules are restricted to the CWE under test

A scan emits every rule the engine has, and the style rules (indentation,
final parameters, ...) fire everywhere.  Measuring them against a CWE's
ground truth says nothing about that CWE.  So the rules are restricted to
the per-CWE whitelist in juliet-coverage/configs/CWE<N>.yaml, which is
the set of rules a human already judged to be about that CWE.

Usage:
    python scripts/juliet_measure.py CWE252 [CWE253 ...]
    python scripts/juliet_measure.py --all
    python scripts/juliet_measure.py CWE252 --rules UncheckedReturn
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JULIET_ROOT = Path(r"D:\work\tmp\Juliet_Java\src\testcases")
JULIET_CSV = Path(r"D:\work\tmp\Juliet_Java\testcases_detail.csv")
CONFIG_DIR = REPO / "juliet-coverage" / "configs"
DEFAULT_JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"

# The Juliet directory name for each CWE id we measure.
CWE_DIRS = {
    "CWE129": "CWE129_Improper_Validation_of_Array_Index",
    "CWE190": "CWE190_Integer_Overflow",
    "CWE191": "CWE191_Integer_Underflow",
    "CWE252": "CWE252_Unchecked_Return_Value",
    "CWE253": "CWE253_Incorrect_Check_of_Function_Return_Value",
    "CWE369": "CWE369_Divide_By_Zero",
}

# The files Juliet ships to drive the suite; they are not testcases.
DRIVER_FILES = {"Main.java", "ServletMain.java"}


def read_whitelist(cwe: str) -> list[str]:
    """The rule ids the per-CWE config enables, or [] when there is no config."""
    config = CONFIG_DIR / f"{cwe}.yaml"
    if not config.is_file():
        return []
    rules: list[str] = []
    in_enabled = False
    for raw in config.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip().startswith("#"):
            continue
        if re.match(r"^\s*enabled\s*:", line):
            in_enabled = True
            continue
        if in_enabled:
            stripped = line.strip()
            if stripped.startswith("- "):
                rules.append(stripped[2:].strip())
                continue
            if stripped and not stripped.startswith("-"):
                in_enabled = False
    return rules


def load_ground_truth() -> dict:
    """Return {cwe_id: {filename: [method names]}} from Juliet's CSV.

    The FILES column names the testcase files; the METHODS column names the
    methods inside them, with the `bad` / `good` role embedded in the name.
    """
    truth: dict = {}
    with JULIET_CSV.open(newline="", encoding="utf-8", errors="replace") as fh:
        for row in csv.DictReader(fh):
            cwe = (row.get("CWE_ID") or "").strip()
            files = (row.get("FILES") or "").strip()
            methods = (row.get("METHODS") or "").strip()
            if not cwe or not files:
                continue
            names = [
                m.strip()
                for m in re.split(r"[;,]", methods)
                if m.strip()
            ]
            bucket = truth.setdefault(cwe, {})
            for name in files.split(";"):
                name = name.strip()
                if not name:
                    continue
                bucket.setdefault(name, [])
                for m in names:
                    if m not in bucket[name]:
                        bucket[name].append(m)
    return truth


def method_spans(path: Path, wanted: list[str]) -> dict:
    """Return {method_name: (first_line, last_line)} for the wanted methods.

    Juliet's testcases put the method's opening brace on the line AFTER the
    declaration (`public void bad() throws Throwable` then `{`), so the
    declaration and the brace are found separately: the declaration line
    names the method, and the brace counter starts at the first `{` after
    it.

    Only the methods the ground truth names are located, so a nested
    anonymous class or a lambda cannot be mistaken for one of them.
    """
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    lines = text.splitlines()
    spans: dict[str, tuple[int, int]] = {}
    for name in wanted:
        pattern = re.compile(r"\b" + re.escape(name) + r"\s*\(")
        for index, line in enumerate(lines):
            if not pattern.search(line):
                continue
            if line.strip().startswith(("if", "for", "while", "switch", "catch", "return")):
                continue
            # The body starts at the first '{' at or after the declaration.
            depth = 0
            started = False
            for cursor in range(index, len(lines)):
                chunk = lines[cursor]
                depth += chunk.count("{") - chunk.count("}")
                if "{" in chunk:
                    started = True
                if started and depth <= 0:
                    spans[name] = (index + 1, cursor + 1)
                    break
            if name in spans:
                break
    return spans


def role_of(method: str) -> str:
    """The role of a Juliet method: 'bad', 'good' or 'other'."""
    if method.startswith("bad"):
        return "bad"
    if method.startswith("good"):
        return "good"
    return "other"


def scan(cwe: str, jar: Path, rules: list[str]) -> list[dict]:
    """Scan one CWE's testcase directory and return the findings.

    The rules are applied with the PER-CWE YAML CONFIG, not with `--rule`.

    The two filters are not the same, and the difference is a trap:
    `--rule` matches a rule's canonical id (and a couple of stripped
    forms), while the yaml whitelist ALSO matches the rule's name
    (`UNCHECKED_RETURN`) and the checker's simple class name
    (`UncheckedReturn`).  The per-CWE configs are written in the class-name
    form, so passing them to `--rule` silently filters out every rule the
    config enables -- and the measurement then reports 0% recall for a
    CWE the engine covers perfectly.
    """
    directory = JULIET_ROOT / CWE_DIRS[cwe]
    if not directory.is_dir():
        print(f"ERROR: {directory} does not exist")
        return []
    config = CONFIG_DIR / f"{cwe}.yaml"
    with tempfile.TemporaryDirectory(prefix="juliet-measure-") as tmp:
        ndjson = Path(tmp) / "findings.ndjson"
        cmd = [
            "java",
            "-jar",
            str(jar),
            "scan",
            "-i",
            str(directory),
            "-f",
            "ndjson",
            "-o",
            str(ndjson),
        ]
        if config.is_file():
            cmd += ["-c", str(config)]
        elif rules:
            # No config: fall back to the CLI filter, and say so, because
            # the CLI filter is NOT the same as the whitelist.
            print(f"NOTE: {config.name} does not exist; using --rule {rules}")
            for rule in rules:
                cmd += ["--rule", rule]
        result = subprocess.run(cmd, capture_output=True, text=True, cwd=REPO)
        if not ndjson.is_file():
            print(f"ERROR: the scan produced no findings file for {cwe}")
            print(result.stdout[-1500:])
            print(result.stderr[-1500:])
            return []
        findings = []
        for line in ndjson.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                findings.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return findings


def classify(cwe: str, findings: list[dict], truth: dict) -> dict:
    """Classify the findings as TP / FP against the method-level ground truth."""
    files = truth.get(cwe, {})
    span_cache: dict[str, dict] = {}

    def spans_for(name: str) -> dict:
        if name not in span_cache:
            path = None
            for candidate in JULIET_ROOT.glob(f"{cwe}_*"):
                probe = candidate / name
                if probe.is_file():
                    path = probe
                    break
            span_cache[name] = method_spans(path, files.get(name, [])) if path else {}
        return span_cache[name]

    tp = 0
    fp = 0
    fn_files: set[str] = set()
    covered_bad: set[tuple[str, str]] = set()
    fp_by_rule: Counter = Counter()
    tp_by_rule: Counter = Counter()
    fp_examples: list[tuple[str, int, str, str]] = []

    for finding in findings:
        name = Path(finding.get("file") or "").name
        line = int(finding.get("line") or 0)
        rule = finding.get("rule_id") or "?"
        if name in DRIVER_FILES:
            fp += 1
            fp_by_rule[rule] += 1
            if len(fp_examples) < 8:
                fp_examples.append((name, line, rule, "on a driver file"))
            continue
        if name not in files:
            fp += 1
            fp_by_rule[rule] += 1
            if len(fp_examples) < 8:
                fp_examples.append((name, line, rule, "on a file the ground truth does not track"))
            continue
        role = "other"
        for method, (start, end) in spans_for(name).items():
            if start <= line <= end:
                role = role_of(method)
                break
        if role == "bad":
            tp += 1
            tp_by_rule[rule] += 1
            covered_bad.add((name, str(line)))
        else:
            fp += 1
            fp_by_rule[rule] += 1
            if len(fp_examples) < 8:
                fp_examples.append((name, line, rule, f"in a {role} method"))

    # A file whose bad method was never hit is a false negative.
    for name, methods in files.items():
        has_bad = any(role_of(m) == "bad" for m in methods)
        if not has_bad:
            continue
        hit = False
        for method, (start, end) in spans_for(name).items():
            if role_of(method) != "bad":
                continue
            if any(n == name and start <= int(line) <= end for n, line in covered_bad):
                hit = True
                break
        if not hit:
            fn_files.add(name)

    positives = sum(
        1 for methods in files.values() if any(role_of(m) == "bad" for m in methods)
    )
    negatives = sum(
        1 for methods in files.values() if not any(role_of(m) == "bad" for m in methods)
    )
    # RECALL IS A FILE-LEVEL NUMBER.
    #
    # The denominator is the number of positive FILES, so the numerator has to
    # be the number of positive FILES that produced at least one finding.
    # Dividing the number of FINDINGS by the number of FILES mixes two units
    # and produced a recall of 6.18 -- a number that is not a rate at all, and
    # that reads as "the engine is six times better than perfect".
    #
    # The finding-level counts are still reported, because they are what
    # precision is computed from.
    tp_files = positives - len(fn_files)
    recall = tp_files / positives if positives else 0.0
    precision = tp / (tp + fp) if (tp + fp) else 0.0

    return {
        "cwe": cwe,
        "files_positive": positives,
        "files_negative": negatives,
        "tp": tp,
        "fp": fp,
        "tp_files": tp_files,
        "fn_files": len(fn_files),
        "recall": recall,
        "precision": precision,
        "f1": (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0,
        "fp_by_rule": dict(fp_by_rule.most_common(12)),
        "tp_by_rule": dict(tp_by_rule.most_common(12)),
        "fp_examples": fp_examples,
        "missed_positives": sorted(fn_files)[:20],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("cwes", nargs="*", help="CWE ids, e.g. CWE252")
    parser.add_argument("--all", action="store_true", help="measure every known CWE")
    parser.add_argument("--jar", default=str(DEFAULT_JAR))
    parser.add_argument("--rules", default="", help="comma-separated rule ids (overrides the config)")
    parser.add_argument("--json", action="store_true", help="print the raw JSON")
    args = parser.parse_args()

    cwes = list(CWE_DIRS) if args.all else [c.upper() for c in args.cwes]
    if not cwes:
        print("ERROR: name at least one CWE, or pass --all")
        return 2

    jar = Path(args.jar)
    if not jar.is_file():
        print(f"ERROR: {jar} does not exist -- build the shaded jar first")
        return 2

    truth = load_ground_truth()
    results = []
    for cwe in cwes:
        if cwe not in CWE_DIRS:
            print(f"ERROR: {cwe} is not a CWE this script knows how to measure")
            return 2
        rules = (
            [r.strip() for r in args.rules.split(",") if r.strip()]
            if args.rules
            else read_whitelist(cwe)
        )
        findings = scan(cwe, jar, rules)
        result = classify(cwe, findings, truth)
        result["rules"] = rules
        results.append(result)

    if args.json:
        print(json.dumps(results, indent=2))
        return 0

    print()
    print("=" * 78)
    print("Juliet measurement (method-level ground truth, CWE rule whitelist)")
    print("=" * 78)
    for r in results:
        print()
        print(
            f"{r['cwe']}: rules={r['rules']}\n"
            f"  files positive={r['files_positive']} negative={r['files_negative']}"
        )
        print(
            f"  TP={r['tp']} findings  FP={r['fp']} findings"
            f"   TP files={r['tp_files']}  FN(files)={r['fn_files']}"
            f"\n   recall={r['recall']:.4f}  precision={r['precision']:.4f}  f1={r['f1']:.4f}"
        )
        if r["tp_by_rule"]:
            print("  TP by rule:")
            for rule, count in r["tp_by_rule"].items():
                print(f"    {count:6d}  {rule}")
        if r["fp_by_rule"]:
            print("  FP by rule:")
            for rule, count in r["fp_by_rule"].items():
                print(f"    {count:6d}  {rule}")
        if r["fp_examples"]:
            print("  FP examples:")
            for name, line, rule, why in r["fp_examples"]:
                print(f"    {name}:{line}  [{rule}]  {why}")
        if r["missed_positives"]:
            print(f"  missed positives ({len(r['missed_positives'])}):")
            for name in r["missed_positives"][:10]:
                print(f"    {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
