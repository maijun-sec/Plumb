#!/usr/bin/env python3
"""Prove the new `dataflow_contains` constraint actually constrains.

A constraint that never rejects anything is not a constraint. This
script checks both directions:

  1. a WRONG label in the manifest makes the case fail (the constraint
     is not a no-op);
  2. the RIGHT label makes it pass (the constraint is not rejecting
     everything).

It works on a COPY of the manifest, so the real one is never touched.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(r"D:\work\workspace\idea\engine\Plumb")
CATEGORY = REPO / "testcase" / "06-framework-orms"
MANIFEST = CATEGORY / "EXPECTATIONS.json"

FAILURES = []


def note(label, ok, detail=""):
    status = "PASS" if ok else "FAIL"
    print("[%s] %s%s" % (status, label, ("  -- " + detail) if detail and not ok else ""))
    if not ok:
        FAILURES.append(label)


def run_category(category_dir):
    """Run the harness and return (exit_code, output, report).

    The harness is a MEASUREMENT tool: it always exits 0 and writes the
    score into RESULT.json. So the verdict has to be read from the
    report, not from the exit code or from grepping the output for
    the word "FAIL" -- a label mismatch is reported as "false-negative",
    which a naive check would not notice.
    """
    proc = subprocess.run(
        [sys.executable, str(REPO / "testcase" / "run_category.py"),
         str(category_dir), "--per-file"],
        capture_output=True, cwd=REPO, timeout=1800)
    out = proc.stdout.decode("utf-8", "replace") + proc.stderr.decode("utf-8", "replace")
    report = None
    result_path = Path(category_dir) / "RESULT.json"
    if result_path.exists():
        report = json.loads(result_path.read_text(encoding="utf-8"))
    return proc.returncode, out, report


def case_status(report, case_id):
    """The status the harness assigned to one case.

    The report's per-case list is under "cases" -- each entry carries
    the "status" the harness computed ("supported", "false-negative",
    "false-positive", ...).
    """
    if not report:
        return None
    for r in report.get("cases", []):
        if r.get("id") == case_id:
            return r.get("status")
    return None


def with_manifest(manifest, fn):
    """Run fn against a temporary copy of the category that uses `manifest`."""
    work = Path(tempfile.mkdtemp(prefix="plumb-datflow-"))
    try:
        copy = work / "06-framework-orms"
        shutil.copytree(CATEGORY, copy)
        shutil.copy2(manifest, copy / "EXPECTATIONS.json")
        # RESULT.json is a measurement from a previous run; the harness
        # rewrites it, but a stale one could confuse the scoring.
        result = copy / "RESULT.json"
        if result.exists():
            result.unlink()
        return fn(copy)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    original = json.loads(MANIFEST.read_text(encoding="utf-8"))

    print("=== baseline: the real manifest passes ===")
    code, out, report = run_category(CATEGORY)
    status = case_status(report, "TP-quarkus-framework-entry-point")
    note("baseline case is supported", status == "supported",
         "status=%r\n%s" % (status, out[-800:]))

    print()
    print("=== 1. a WRONG label must make the case FAIL ===")
    wrong = json.loads(json.dumps(original))
    for case in wrong["cases"]:
        if case["id"] == "TP-quarkus-framework-entry-point":
            case["dataflow_contains"] = "param:customer@jax-rs:QueryParam"
    work = Path(tempfile.mkdtemp(prefix="plumb-datflow-"))
    try:
        bad_manifest = work / "EXPECTATIONS.json"
        bad_manifest.write_text(json.dumps(wrong, indent=2, ensure_ascii=False),
                                encoding="utf-8")

        def run_bad(copy):
            return run_category(copy)

        code, out, report = with_manifest(bad_manifest, run_bad)
        status = case_status(report, "TP-quarkus-framework-entry-point")
        note("a wrong label makes the case a false negative",
             status == "false-negative", "status=%r\n%s" % (status, out[-1200:]))
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print()
    print("=== 2. the RIGHT label must make the case PASS ===")
    right = json.loads(json.dumps(original))
    for case in right["cases"]:
        if case["id"] == "TP-quarkus-framework-entry-point":
            case["dataflow_contains"] = "param:customer@quarkus-rest:RestQuery"
    work = Path(tempfile.mkdtemp(prefix="plumb-datflow-"))
    try:
        good_manifest = work / "EXPECTATIONS.json"
        good_manifest.write_text(json.dumps(right, indent=2, ensure_ascii=False),
                                encoding="utf-8")

        def run_good(copy):
            return run_category(copy)

        code, out, report = with_manifest(good_manifest, run_good)
        status = case_status(report, "TP-quarkus-framework-entry-point")
        note("the right label still passes", status == "supported",
             "status=%r\n%s" % (status, out[-800:]))
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print()
    print("=== 3. the plain-parameter assertion must reject a framework label ===")
    # The plain-parameter case asserts the whole "taint: param:customer ->" head.
    # If the engine wrongly labelled it 'param:customer@quarkus-rest:RestQuery',
    # the assertion must not be satisfied -- the finding exists, but it
    # says the wrong thing.
    wrong2 = json.loads(json.dumps(original))
    for case in wrong2["cases"]:
        if case["id"] == "TP-quarkus-plain-parameter":
            case["dataflow_contains"] = "param:customer@quarkus-rest:RestQuery"
    work = Path(tempfile.mkdtemp(prefix="plumb-datflow-"))
    try:
        bad_manifest = work / "EXPECTATIONS.json"
        bad_manifest.write_text(json.dumps(wrong2, indent=2, ensure_ascii=False),
                                encoding="utf-8")

        def run_bad2(copy):
            return run_category(copy)

        code, out, report = with_manifest(bad_manifest, run_bad2)
        status = case_status(report, "TP-quarkus-plain-parameter")
        note("the plain-parameter probe rejects a framework label",
             status == "false-negative", "status=%r\n%s" % (status, out[-1200:]))
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print()
    print("=== the real manifest is untouched ===")
    now = json.loads(MANIFEST.read_text(encoding="utf-8"))
    note("the real manifest is unchanged", now == original)

    print()
    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        for f in FAILURES:
            print("  - " + f)
        sys.exit(1)
    print("all dataflow-constraint checks passed")


if __name__ == "__main__":
    main()
