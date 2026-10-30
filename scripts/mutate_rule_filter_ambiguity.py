#!/usr/bin/env python3
"""Mutation battery for the rule filter's ambiguity guard.

The guard is the difference between "a filter that names one rule
selects one rule" and "a filter that names one rule may select two".
A guard that silently stops working is worse than no guard: the filter
goes on selecting two rules, and the measurement reports a false
positive that does not exist.

Each mutant breaks the guard in a plausible way. The mutant is applied
to the SOURCE, the project is rebuilt, the behaviour is measured, and
the source is restored byte-exactly.

A mutant that cannot be built is reported separately: "could not build"
is not "survived".

    python scripts/mutate_rule_filter_ambiguity.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanRunner.java"
JAR = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"
# The REAL fixture. A synthetic one would have to be tuned until it made
# both rules fire, and a fixture tuned until it works is a fixture whose
# behaviour is a coincidence. This file is the one the Juliet corpus
# uses, and it fires the DFA rule (CWE-476) and the AST rule (CWE-690).
FIXTURE_DIR = REPO / "testcase" / "03-pointer-multilevel" / "src"
FIXTURE = FIXTURE_DIR / "ptr" / "L1BasicAlias.java"
PYTHON = sys.executable

# (name, the exact text to find, the text to replace it with)
MUTANTS = [
    # The guard stops asking the question.
    ("guard-always-says-unique",
     "      if (owners > 1) return true;",
     "      if (false) return true;"),

    # The guard asks about the wrong thing: it only counts a rule when the
    # form IS the rule's identity, and not when the form is a PEELED form
    # of it. That is the bug the first version had.
    ("guard-only-counts-exact-identities",
     "      } else {\n        for (String f : ruleIdForms(identity)) {\n"
     "          if (f.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {\n"
     "            owners++;\n            break;\n          }\n        }\n      }",
     "      }"),

    # The live rule set becomes empty, so every form looks unique.
    ("live-rule-set-becomes-empty",
     "    liveRuleIdsCache = ids;\n    return ids;",
     "    liveRuleIdsCache = new java.util.LinkedHashSet<>();\n    return ids;"),
]

# The mutants that cannot apply to this fixture, and why.
#
# A mutant that does not change the behaviour is not a survivor -- it is a mutant that was never
# tested. The project's rule is explicit about this: report a mutation that does not apply as a
# NO-OP, not as a surviving mutant. Reporting it as a survivor would be a false alarm that trains
# people to ignore the battery.
#
# "guard-comparison-becomes-case-sensitive" is the case. The comparison folds case so that the
# peeled form of a LEGACY NAME (`NULL_DEREF_FROM_RETURN` -> `null-deref-from-return`) collides
# with a lower-case slug. For the pair this fixture exercises, the collision does not NEED the
# folding: the AST rule's own ID (`ast-style-null-deref-from-return`) already peels to
# `null-deref-from-return` in lower case. So the mutant changes nothing observable here.
#
# The folding is not decoration -- it is what stops a FUTURE collision of the same shape, where two
# rules differ only in the case of a legacy name. It is defensive, and this fixture cannot
# measure it.
NO_OP_MUTANTS = {
    "guard-comparison-becomes-case-sensitive":
        "the pair this fixture exercises collides on the rule ID's own peeled form, which is "
        "already lower case -- the case folding is defensive against a different shape of "
        "collision and this fixture cannot measure it",
}


def measure():
    """How many DISTINCT rule ids does the filter select?

    The defect shows up as MORE THAN ONE rule id for a filter that names
    one rule. The count is what matters, not the finding count.
    """
    out = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8")
    out.close()
    try:
        cmd = [
            "java", "-jar", str(JAR),
            "-i", str(FIXTURE),
            "-f", "ndjson",
            "--cg-strategy", "global",
            "--rule", "dfa-taint-null-deref-from-return",
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", cwd=str(REPO))
        import json
        ids = set()
        for line in proc.stdout.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            ids.add(d.get("rule_id"))
        return ids
    finally:
        pathlib.Path(out.name).unlink(missing_ok=True)


def mvn_command():
    """The Maven launcher, as an absolute path.

    <p>`mvn` is a `.cmd` on Windows, and a subprocess cannot find it on PATH the way a shell
    does. The build is the whole measurement, so a launcher that cannot be found is a battery
    that cannot run -- and it has to be found the same way every time, not by luck of the
    environment.
    """
    from shutil import which

    home = os.environ.get("MAVEN_HOME") or os.environ.get("M2_HOME")
    if home:
        candidate = pathlib.Path(home) / "bin" / ("mvn.cmd" if os.name == "nt" else "mvn")
        if candidate.is_file():
            return [str(candidate)]
    found = which("mvn") or which("mvn.cmd")
    if found:
        return [found]
    # The project's own toolchain location, as a last resort.
    for guess in ("D:/development/apache-maven-3.9.1/bin/mvn.cmd",
                  "C:/Program Files/apache-maven/bin/mvn.cmd"):
        if pathlib.Path(guess).is_file():
            return [guess]
    raise SystemExit("ERROR: the Maven launcher was not found")


def build():
    """Rebuild the jar. Returns (ok, output)."""
    proc = subprocess.run(
        mvn_command() + ["-o", "-q", "-pl", "plumb-main", "-am",
                         "-DskipTests", "-B", "install"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=str(REPO),
    )
    return proc.returncode == 0, (proc.stdout or "") + (proc.stderr or "")


def main():
    if not SRC.is_file():
        print("FAIL: %s does not exist" % SRC)
        return 1

    # The fixture is the REAL one. Without it, the measurement would be
    # "no findings", and a mutant that selects two rules would look the
    # same as one that selects one.
    if not FIXTURE.is_file():
        print("FAIL: the fixture does not exist: %s" % FIXTURE)
        return 1

    original = SRC.read_bytes()
    try:
        ok, out = build()
        if not ok:
            print("FAIL: the project does not build on the current tree")
            print(out[-3000:])
            return 1

        baseline = measure()
        print("baseline: %d distinct rule id(s): %s" % (len(baseline), sorted(baseline)))
        if len(baseline) != 1:
            print("FAIL: the baseline selects %d rules -- the guard is not working "
                  "on the current tree" % len(baseline))
            return 1
        if not baseline:
            print("FAIL: the baseline selects NO rule -- the fixture does not fire")
            return 1

        killed = 0
        survived = []
        broken = []

        for name, old, new in MUTANTS:
            text = original.decode("utf-8")
            count = text.count(old)
            if count != 1:
                print("FAIL: %s -- the anchor occurs %d times, expected exactly 1"
                      % (name, count))
                broken.append(name)
                continue
            try:
                SRC.write_bytes(text.replace(old, new).encode("utf-8"))
                ok, build_out = build()
                if not ok:
                    print("could not build %s -- the mutant does not compile" % name)
                    broken.append(name)
                    continue
                ids = measure()
                if len(ids) > 1:
                    killed += 1
                    print("killed    %s  (selected %d rules: %s)"
                          % (name, len(ids), sorted(ids)))
                else:
                    survived.append(name)
                    print("SURVIVED  %s  (selected %d rules)" % (name, len(ids)))
            finally:
                SRC.write_bytes(original)

        # Restore the good build.
        build()

        print()
        print("%d/%d mutant(s) killed" % (killed, len(MUTANTS)))
        if NO_OP_MUTANTS:
            print()
            print("NO-OP mutants (they do not apply to this fixture -- they are NOT survivors):")
            for name, why in NO_OP_MUTANTS.items():
                print("  %s" % name)
                print("      %s" % why)
        if broken:
            print("could not build: %s" % ", ".join(broken))
        if survived:
            print("survived: %s" % ", ".join(survived))
            return 1
        return 0 if not broken else 1
    finally:
        SRC.write_bytes(original)

if __name__ == "__main__":
    sys.exit(main())
