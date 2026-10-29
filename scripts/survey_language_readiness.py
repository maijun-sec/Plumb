#!/usr/bin/env python3
"""Survey how the rules are configured today, and what a per-language
layout would have to change.

The question this answers: if the taint engine is to support Kotlin and
Scala as well as Java, WHERE does the language enter?

The answer is decided by MEASUREMENT, not by reading the code and
guessing. So this script measures three things:

  1. Which configuration files exist, and which of them carry a
     language notion already.
  2. Which parts of the engine are Java-specific -- the parser, the
     type mapper, the source-file dispatch -- and which are not.
  3. Which of the sink patterns are JAVA-library patterns (they name a
     java.* / javax.* class) and which are language-independent (they
     name a project class, or a method on any receiver).

The third one is the interesting measurement: a rule set for Kotlin has
to describe the same defect classes, but the LIBRARY it names is not the
same library, and the split tells us how much of the configuration is
really "which library" rather than "which defect".
"""
import pathlib
import re
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[1]
DFA_RES = REPO / "plumb-dfa" / "src" / "main" / "resources"

# A pattern is anchored with '^' and the class name is regex-escaped
# ('java\\.util\\.logging'), so the plain "starts with java." test does
# not work. The test strips the anchor and the escapes first.
JAVA_LIKE = re.compile(r"^\^?(?:java|javax|jakarta)\\?\.\\?")


def note(msg):
    print(msg)


def families_of(path):
    """The families map of a sink-style YAML, whatever the file is."""
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as ex:  # noqa: BLE001
        return None, ex
    if not isinstance(data, dict):
        return None, "not a mapping"
    fams = data.get("families")
    if fams is None:
        return None, "no 'families' key"
    return fams, None


def pattern_text(pat):
    """The regex of a pattern entry.

    A pattern is either a bare string or a mapping with a `pattern` key
    and an optional `arg_index`. Both shapes occur, so a survey that
    only handled one of them would silently count half the file as
    nothing.
    """
    if isinstance(pat, dict):
        return pat.get("pattern") or ""
    return pat or ""


def main():
    note("=== the configuration files ===")
    configs = sorted(DFA_RES.glob("*.yaml"))
    for p in configs:
        fams, err = families_of(p)
        if err:
            note("  %-28s (not a families file: %s)" % (p.name, err))
            continue
        note("  %-28s %d families" % (p.name, len(fams)))

    note("")
    note("=== how the sink patterns split ===")
    sink = DFA_RES / "sink-families.yaml"
    fams, err = families_of(sink)
    if err:
        print("FAIL: %s: %s" % (sink, err))
        return 1

    java_lib = 0
    other_lib = 0
    total = 0
    per_family = {}
    for name, entry in fams.items():
        patterns = entry.get("patterns") or []
        j = sum(1 for pat in patterns if JAVA_LIKE.match(pattern_text(pat)))
        o = len(patterns) - j
        java_lib += j
        other_lib += o
        total += len(patterns)
        per_family[name] = (j, o)

    note("  %d pattern(s) in %d families" % (total, len(fams)))
    note("  %d name a java/javax/jakarta class" % java_lib)
    note("  %d name something else (a project class, or a bare method)" % other_lib)
    note("")
    note("  the families that are ENTIRELY java-library patterns:")
    for name in sorted(per_family):
        j, o = per_family[name]
        if j and not o:
            note("    %s (%d)" % (name, j))

    note("")
    note("=== where the language enters the engine ===")
    checks = [
        ("plumb-parser", "JavaSourceParser.java", "the parser"),
        ("plumb-tac", "TypeMapper.java", "the type mapper"),
        ("plumb-main", "ScanRunner.java", "the source-file dispatch"),
    ]
    for module, name, what in checks:
        hits = []
        for p in (REPO / module).rglob(name):
            body = p.read_text(encoding="utf-8", errors="replace")
            n_java = len(re.findall(r"\.java\b", body))
            n_fqn = len(re.findall(r"com\.github\.javaparser", body))
            hits.append((p.relative_to(REPO).as_posix(), n_java, n_fqn))
        for path, n_java, n_fqn in hits:
            note("  %-22s %s" % (what, path))
            note("     %d mention(s) of .java, %d of the Java parser" % (n_java, n_fqn))

    note("")
    note("=== what the sink-family MODEL can express ===")
    sink_family = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa" / "config" / "SinkFamily.java"
    if sink_family.is_file():
        body = sink_family.read_text(encoding="utf-8", errors="replace")
        fields = re.findall(r"private final ([\w<>,\s\[\]]+) (\w+);", body)
        note("  SinkFamily's fields: %s" % ", ".join("%s %s" % (t, n) for t, n in fields))
        note("  a language field: %s" % ("yes" if re.search(r"\blanguage\b", body, re.I) else "NO"))
    else:
        note("  SinkFamily.java not found")

    note("")
    note("=== the conclusion the measurement supports ===")
    note("  The SINK families are matched by REGEX against a callee")
    note("  signature, so the model itself has no notion of a source")
    note("  language: a family is a set of patterns, a pattern is a")
    note("  regex, and a regex names whatever class it names.")
    note("")
    note("  So the language-specific part is NOT the rule model. It is:")
    note("    1. the PARSER (one per language)")
    note("    2. the TYPE MAPPER (one per language)")
    note("    3. the SOURCE-FILE DISPATCH (one extension list)")
    note("  and the CONFIGURATION is per-language only insofar as the")
    note("  LIBRARY it names differs -- which is a data difference, not")
    note("  a structural one.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
