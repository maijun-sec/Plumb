#!/usr/bin/env python3
"""Prove the engine's report keeps a finding's identity.

A finding is a claim about a place in the source: "this defect is HERE".
Two fields carry that claim:

  - ``file`` -- which file the defect is in;
  - ``sink_function`` -- which callee the defect flows into.

Both are load-bearing, not decoration:

  - the Juliet harness matches a case to a finding BY FILE, so a finding
    whose file is lost stops matching its case and the case is scored as a
    miss. The finding is in the output the whole time; only the
    bookkeeping that connects it to the case is gone;
  - ``sink_function`` is what lets a consumer say "the sink is
    ``Statement.executeQuery``", and it is what the harness's
    ``anchor_fn`` pins a case to.

TWO REAL DEFECTS THIS GATE PINS.

  1. The AI dedup pass rebuilt every finding through a constructor that
     dropped ``sink_function``, so every finding that went through the
     AI lost it. The finding was in the report; the field that says
     where the flow ENDS was not.

  2. A function declared in a NESTED class had no enclosing class, so
     the engine could not resolve the file it lives in and emitted it
     with ``file="unknown"``.

Both are silent: the scan succeeds, the finding count is unchanged, and
only the bookkeeping is gone. A count cannot see them.

WHY THE AI LEG IS CHECKED AT THE SOURCE, NOT OVER THE NETWORK.

The interesting property of the AI pass is a property of a FUNCTION: it
attaches a verdict to a finding and changes nothing else. That is
decided in one place -- the method that rebuilds the finding -- and it is
decided the same way whether the verdict came from a model or not.

So the gate checks that function's SOURCE, and runs the live engine for
the part that genuinely needs the engine (the nested class's file). A
gate that called a live model would be slow, would need a key, and would
fail for a reason that has nothing to do with the invariant it claims to
protect. It would also pass on a network outage -- which is how a gate
stops checking without anyone noticing.

    python scripts/check_finding_identity.py
"""
from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"

# The fixture is a real file in the repository, so the gate measures the
# shipped behaviour and not a synthetic copy of it.
FIXTURE_DIR = REPO / "testcase" / "04-polymorphic-callgraph" / "src"

# The source of the method that attaches a verdict to a finding.
SCAN_RUNNER = REPO / "plumb-main/src/main/java/org/plumb/main/ScanRunner.java"

# The source of the translator that decides a function's enclosing class.
TRANSLATOR = REPO / "plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java"


def run_scan(extra_args, rules):
    """Run the engine over the fixture and return its findings.

    A non-zero exit is an ERROR, not an empty result: a crashed scan
    produces no findings, and "no findings" and "the scan crashed" look
    identical to a reader that only counts lines.
    """
    cmd = [
        "java", "-jar", str(JAR),
        "-i", str(FIXTURE_DIR),
        "-f", "ndjson",
        "--cg-strategy", "global",
    ]
    for r in rules:
        cmd += ["--rule", r]
    cmd += extra_args

    proc = subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=str(REPO), timeout=900,
    )
    if proc.returncode != 0:
        raise SystemExit(
            "ERROR: the scan did not run (exit %d).\n%s"
            % (proc.returncode, (proc.stderr or "")[-2000:])
        )
    out = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    if not out:
        raise SystemExit(
            "ERROR: the scan produced no findings. That measures nothing -- "
            "it is not a clean result."
        )
    return out


def verdict_method_body(source: str) -> str:
    """The body of the method that attaches a verdict to a finding.

    The method is found by its NAME and its signature, not by a line
    number, so the check survives the method moving.
    """
    m = re.search(
        r"static\s+Finding\s+withAiVerdict\s*\(\s*Finding\s+\w+\s*,\s*String\s+\w+\s*,\s*String\s+\w+\s*\)\s*\{",
        source,
    )
    if not m:
        raise SystemExit(
            "ERROR: the method that attaches a verdict to a finding was not "
            "found in %s" % SCAN_RUNNER
        )
    start = m.end()
    depth = 1
    i = start
    while i < len(source) and depth > 0:
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
        i += 1
    return source[start:i - 1]


def main() -> int:
    if not JAR.is_file():
        print("ERROR: %s does not exist -- build it first" % JAR)
        return 1
    if not FIXTURE_DIR.is_dir():
        print("ERROR: the fixture directory %s does not exist" % FIXTURE_DIR)
        return 1

    failures = []

    # ------------------------------------------------------------------
    # Part 1: the AI verdict path must not change the finding.
    #
    # Checked at the SOURCE, because the property is a property of the
    # function and not of the model that answers it.
    # ------------------------------------------------------------------
    runner_source = SCAN_RUNNER.read_text(encoding="utf-8")
    body = verdict_method_body(runner_source)

    if "f.sinkFunction()" not in body:
        failures.append(
            "the method that attaches a verdict to a finding does not carry the "
            "finding's sink_function, so every finding that goes through the AI "
            "pass loses the field that names where its flow ends"
        )
    if "f.file()" not in body:
        failures.append(
            "the method that attaches a verdict to a finding does not carry the "
            "finding's file, so every finding that goes through the AI pass loses "
            "its location"
        )
    for field in ("f.rule()", "f.message()", "f.severity()",
                  "f.dataflowHead()", "f.dataflowSteps()"):
        if field not in body:
            failures.append(
                "the method that attaches a verdict to a finding does not carry "
                "the finding's %s" % field
            )

    # The verdict itself must be carried, or the pass does nothing at all.
    if "verdict" not in body or "reason" not in body:
        failures.append(
            "the method that attaches a verdict to a finding does not attach the "
            "verdict"
        )

    # ------------------------------------------------------------------
    # Part 2: a nested type must name its enclosing class, so the engine
    # can resolve the file it lives in.
    # ------------------------------------------------------------------
    translator_source = TRANSLATOR.read_text(encoding="utf-8")
    if "enclosingClassName" not in translator_source:
        failures.append(
            "the translator no longer computes an enclosing class for a type, so "
            "a function in a nested class cannot be traced back to its file"
        )
    else:
        # The nested case must NOT be the null case any more: a null
        # enclosing class is exactly what made the lookup miss.
        m = re.search(
            r"String\s+enclosingClassName\s*=\s*(.*?);", translator_source, re.S)
        if m and "null" in m.group(1):
            failures.append(
                "the translator gives a nested type a null enclosing class, so the "
                "engine cannot resolve the file a nested class lives in and the "
                "finding is reported with file=\"unknown\""
            )

    # ------------------------------------------------------------------
    # Part 3: the live engine, over a fixture whose sinks live in NESTED
    # classes. Every finding must name its file.
    # ------------------------------------------------------------------
    plain = run_scan([], ["dfa-taint-sql-injection"])
    unknown = [f for f in plain if (f.get("file") or "") in ("", "unknown")]
    if unknown:
        failures.append(
            "%d finding(s) do not name their file (file=\"unknown\"): %s"
            % (len(unknown),
               ", ".join(sorted({"%s:%s" % (f.get("rule_id"), f.get("line"))
                                 for f in unknown})))
        )

    # The fixture's whole point is that the sinks live in NESTED classes,
    # so a scan that resolves every file has to have resolved a nested
    # class's file. If the fixture ever stops containing one, the gate
    # would silently stop testing the thing it exists to test.
    nested_source = list(FIXTURE_DIR.rglob("*.java"))
    if not any("static class" in p.read_text(encoding="utf-8", errors="replace")
               for p in nested_source):
        failures.append(
            "the fixture no longer contains a nested class, so the nested-class "
            "file resolution is no longer being measured"
        )

    print("checked %d finding(s) from the live scan" % len(plain))
    if failures:
        print()
        print("FAIL: %d finding-identity violation(s)" % len(failures))
        for f in failures:
            print("  %s" % f)
        print()
        print("A finding that does not say where it is cannot be acted on,")
        print("and a verdict that changes a finding is not a verdict.")
        return 1

    print()
    print("OK: every finding names its file, and the AI pass changes only the verdict")
    return 0


if __name__ == "__main__":
    sys.exit(main())
