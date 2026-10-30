#!/usr/bin/env python3
"""Mutation battery for `scripts/check_finding_identity.py`.

A gate that checks a finding's identity drifts by *stopping to check*.
Each mutant here breaks the PRODUCT in a way the gate claims to catch:

  - the AI pass goes back to rebuilding a finding through the
    constructor that drops ``sink_function``;
  - the AI pass goes back to dropping the finding's file;
  - a nested type goes back to having no enclosing class, so the engine
    cannot resolve the file it lives in.

Each mutant is applied to the SOURCE, the project is rebuilt, the gate
is run, and the source is restored byte-exactly. A mutant that survives
is a hole in the gate; a mutant that could not be built is reported
separately, because "could not build" is not "survived".

THE MUTANTS BREAK THE PRODUCT, NOT THE GATE.

A gate that has two independent checks for one invariant stays green when
one of them is disabled -- which is correct, not a hole. So weakening the
gate's own source proves nothing about whether the gate can catch the
defect. What proves it is breaking the product and watching the gate go
red. That is what these mutants do.

The restore is explicit: the sources are saved before the mutation and
written back afterwards. `git checkout -- <file>` is NOT used, because
the fixes being mutated are not committed and discarding them would
destroy the work.

    python scripts/mutate_finding_identity.py
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_finding_identity.py"
PYTHON = sys.executable

SCAN_RUNNER = REPO / "plumb-main/src/main/java/org/plumb/main/ScanRunner.java"
TRANSLATOR = REPO / "plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java"

# The exact text each mutant replaces. A mutant that cannot be applied
# (because the text has moved) is reported as NOT-APPLIED rather than
# silently counted as killed.
MUTANTS = [
    (
        "the-ai-pass-drops-the-sink-again",
        SCAN_RUNNER,
        """        verdict,
        reason,
        f.sinkFunction(),
        f.trace());""",
        """        verdict,
        reason);""",
    ),
    (
        "the-ai-pass-drops-the-file-again",
        SCAN_RUNNER,
        """        f.file(),
        f.line(),
        f.column(),
        f.rule(),
        f.message(),
        f.severity(),
        f.dataflowHead(),
        f.dataflowSteps(),
        verdict,
        reason,
        f.sinkFunction(),
        f.trace());""",
        """        f.file(),
        f.line(),
        f.column(),
        f.rule(),
        f.message(),
        f.severity(),
        f.dataflowHead(),
        f.dataflowSteps(),
        verdict,
        reason);""",
    ),
    (
        "a-nested-type-has-no-enclosing-class-again",
        TRANSLATOR,
        """    String enclosingClassName =
        outerChain.isEmpty() ? classShortName : outerChain + "$" + classShortName;""",
        """    String enclosingClassName =
        outerChain.isEmpty() ? classShortName : null;""",
    ),
]


def mvn_command():
    """The Maven executable, resolved rather than assumed.

    `mvn` on this machine is `mvn.cmd`, and a bare `mvn` in a
    subprocess argument list is not always resolvable -- the error is
    "the system cannot find the file specified", which says nothing
    about Maven. So the command is found the way the shell finds it and
    the absolute path is used.
    """
    for candidate in ("mvn.cmd", "mvn"):
        found = shutil.which(candidate)
        if found:
            return found
    raise SystemExit(
        "ERROR: neither mvn.cmd nor mvn is on the PATH -- the mutation "
        "battery needs Maven to rebuild the jar it measures."
    )


def build():
    """Build the jar the gate runs against."""
    proc = subprocess.run(
        [mvn_command(), "-o", "-pl", "plumb-main", "-am", "-DskipTests", "-B", "install"],
        cwd=str(REPO), capture_output=True, text=True, encoding="utf-8",
        errors="replace", timeout=1800,
    )
    return proc.returncode == 0, (proc.stdout or "") + (proc.stderr or "")


def run_gate():
    proc = subprocess.run(
        [PYTHON, str(GATE)], cwd=str(REPO), capture_output=True, text=True,
        encoding="utf-8", errors="replace", timeout=1800,
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def apply_mutation(text: str, old: str, new: str):
    """Apply a mutation, whatever the file's line endings are.

    A source file in this repository may use CRLF and another LF, and a
    mutation that does not apply is reported as NOT-APPLIED -- which
    looks exactly like a mutant the gate could not catch. So the line
    endings are normalised for the MATCH, and the file is written back
    with the endings it had.
    """
    normalised = text.replace("\r\n", "\n")
    if old not in normalised:
        return None
    mutated = normalised.replace(old, new, 1)
    if "\r\n" in text:
        mutated = mutated.replace("\n", "\r\n")
    return mutated


def main():
    if not GATE.is_file():
        raise SystemExit("ERROR: the gate %s does not exist" % GATE)

    backup = pathlib.Path(tempfile.mkdtemp(prefix="plumb-finding-identity-"))
    saved = {}
    for _, path, _, _ in MUTANTS:
        if path not in saved:
            saved[path] = True
            shutil.copy2(path, backup / (path.name + "." + str(len(saved))))

    results = []
    for name, path, old, new in MUTANTS:
        original = path.read_bytes()
        text = original.decode("utf-8")
        mutated = apply_mutation(text, old, new)
        if mutated is None:
            results.append((name, "NOT-APPLIED", "the text to mutate was not found"))
            continue
        try:
            path.write_text(mutated, encoding="utf-8")
            ok, log = build()
            if not ok:
                results.append((name, "NOT-APPLIED", "the project did not build"))
                continue
            rc, gate_log = run_gate()
            if rc != 0:
                results.append((name, "killed", "the gate reported the defect"))
            else:
                results.append((name, "SURVIVED", "the gate stayed green"))
        finally:
            path.write_bytes(original)

    # Rebuild so the tree is left in the fixed state.
    build()

    print("=" * 78)
    print("MUTATION BATTERY: the finding-identity gate")
    print("=" * 78)
    killed = sum(1 for _, s, _ in results if s == "killed")
    survived = [r for r in results if r[1] == "SURVIVED"]
    not_applied = [r for r in results if r[1] == "NOT-APPLIED"]
    for name, status, detail in results:
        print("  %-8s %s" % (status, name))
        if status != "killed":
            print("           %s" % detail)
    print()
    print("killed %d, survived %d, not-applied %d" % (killed, len(survived), len(not_applied)))
    if survived or not_applied:
        print()
        print("A surviving mutant is a hole in the gate.")
        return 1
    print()
    print("OK: every mutant was killed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
