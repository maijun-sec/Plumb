#!/usr/bin/env python3
"""Run every repository gate and report a single verdict.

The gates existed one at a time, and each one had to be remembered
separately. That is how a stale generated file, a drifted javadoc
number, or a cross-layer disagreement reached the repository in the
first place: a gate nobody ran is not a gate.

This script runs all of them and prints one PASS/FAIL summary. It is
the command to run before every commit.

    python scripts/check_all.py

The gates are:
    format --check            every source file is google-java-format clean
    gen_categories --check    the generated checker registry is current
    gen_framework_annotations --check
                              the generated annotation catalogue is current
    check_framework_annotations
                              the two layers agree with the catalogue
    check_catalogue_drift_numbers
                              the drift numbers quoted in javadocs match
                              the history they describe
    check_dataflow_constraint
                              the manifest's dataflow constraint is not a
                              no-op
    check_support_matrix_coverage
                              the support matrix covers every measured
                              category, and every row matches its
                              measurement
    check_javadoc_links        every javadoc @link target names a type
                              that exists
    capability_baseline --check
                              no rule, sink pattern, source pattern,
                              checker or fact kind was lost
    linkcheck                 no broken relative link in the markdown

The generators that WRITE (gen_rules_doc, gen_rules_index) are run
afterwards, and their output is reported as a working-tree change, so
a stale generated document is visible without failing the run -- the
document is regenerated, not judged.

Exit code 0 when every gate passes, 1 otherwise.
"""
import io
import os
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (label, argv, kind) -- kind "check" gates on the exit code, kind
# "regen" regenerates and reports whether the tree changed.
GATES = (
    ("format", ["python", "scripts/format.py", "--check"], "check"),
    ("gen_categories", ["python", "scripts/gen_categories.py", "--check"], "check"),
    ("gen_framework_annotations",
     ["python", "scripts/gen_framework_annotations.py", "--check"], "check"),
    ("check_framework_annotations",
     ["python", "scripts/check_framework_annotations.py"], "check"),
    ("check_catalogue_drift_numbers",
     ["python", "scripts/check_catalogue_drift_numbers.py"], "check"),
    ("check_dataflow_constraint",
     ["python", "scripts/check_dataflow_constraint.py"], "check"),
    ("check_support_matrix_coverage",
     ["python", "scripts/check_support_matrix_coverage.py"], "check"),
    ("check_javadoc_links",
     ["python", "scripts/check_javadoc_links.py"], "check"),
    ("capability_baseline",
     ["python", "scripts/capability_baseline.py", "--check"], "check"),
    ("linkcheck", ["python", ".aethercode/linkcheck.py"], "check"),
)

REGENERATORS = (
    ("gen_rules_doc", ["python", "scripts/gen_rules_doc.py"]),
    ("gen_rules_index", ["python", "scripts/gen_rules_index.py"]),
)


def python_on_path():
    """The python executable, resolved for subprocess use on Windows."""
    import shutil
    found = shutil.which("python") or shutil.which("python3")
    if not found:
        raise RuntimeError("cannot find the python executable")
    return found


def run(argv):
    cmd = [python_on_path()] + argv[1:] if argv and argv[0] == "python" else list(argv)
    proc = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.returncode, proc.stdout + proc.stderr


def main():
    failures = []

    print("=== gates ===")
    for label, argv, kind in GATES:
        code, output = run(argv)
        if code == 0:
            print("  PASS  %s" % label)
        else:
            print("  FAIL  %s (exit %d)" % (label, code))
            failures.append(label)
            for line in output.splitlines()[-25:]:
                print("        %s" % line)

    print()
    print("=== regenerated documents ===")
    before = subprocess.run(["git", "status", "--porcelain", "docs/"], cwd=REPO,
                            capture_output=True, text=True, encoding="utf-8",
                            errors="replace").stdout
    for label, argv in REGENERATORS:
        code, output = run(argv)
        if code != 0:
            print("  FAIL  %s (exit %d)" % (label, code))
            failures.append(label)
            for line in output.splitlines()[-15:]:
                print("        %s" % line)
            continue
        print("  OK    %s" % label)
    after = subprocess.run(["git", "status", "--porcelain", "docs/"], cwd=REPO,
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace").stdout
    if after != before:
        print()
        print("  the regenerated documents differ from the committed ones:")
        for line in after.splitlines():
            print("    %s" % line)
        print("  commit the regenerated documents, or the next run will report them again.")
    else:
        print("  the regenerated documents match the committed ones.")

    print()
    if failures:
        print("FAILED: %d gate(s) did not pass: %s" % (len(failures), ", ".join(failures)))
        return 1
    print("OK: every gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
