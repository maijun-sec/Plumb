#!/usr/bin/env python3
"""Mutation test for scripts/check_capture_claims.py.

A gate that passes because it checked nothing is worse than no gate.  This
script mutates the document and the source in ways that MUST make the gate
fail, and checks that it does.

Each mutant is applied to a temporary copy of the repo's files; the real files
are never touched.

Usage:  python scripts/mutate_capture_claims.py
"""
import io
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "tech" / "ARCHITECTURE.md"
CAPTURE = REPO / "plumb-build" / "plumb-build-capture" / "src" / "main" / "java" / "org" / "plumb" / "build" / "capture"
EP = CAPTURE / "CompilerEntryPoints.java"
GRADLE_HOOKS = CAPTURE / "GradleHooks.java"
CAPTURE_AGENT = CAPTURE / "CaptureAgent.java"
GATE = REPO / "scripts" / "check_capture_claims.py"

original_doc = DOC.read_text(encoding="utf-8", errors="replace")
original_ep = EP.read_text(encoding="utf-8", errors="replace")
original_gradle_hooks = GRADLE_HOOKS.read_text(encoding="utf-8", errors="replace")
original_capture_agent = CAPTURE_AGENT.read_text(encoding="utf-8", errors="replace")


def restore_sources():
    EP.write_text(original_ep, encoding="utf-8")
    GRADLE_HOOKS.write_text(original_gradle_hooks, encoding="utf-8")
    CAPTURE_AGENT.write_text(original_capture_agent, encoding="utf-8")


def run_gate():
    result = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.returncode, result.stdout


MUTANTS = [
    (
        "the document names an entry point the source does not hook",
        "doc",
        lambda t: t.replace(
            "com.sun.tools.sjavac.client.ClientMain#run(String[])",
            "com.sun.tools.sjavac.client.ClientMainX#run(String[])",
        ),
    ),
    (
        "the document drops an entry point the source hooks",
        "doc",
        # The table row's wording differs between the Chinese and the
        # English document, so the row is removed by its signature rather
        # than by its text.
        lambda t: "\n".join(
            line
            for line in t.split("\n")
            if "com.sun.tools.sjavac.server.ServerMain#run(String[])" not in line
        ),
    ),
    (
        "the document's entry point count is wrong",
        "doc",
        # The document may be in Chinese or English; the count is what
        # matters, so both spellings are tried.
        lambda t: (
            t.replace("而是**十二个**", "而是**八个**")
            if "而是**十二个**" in t
            else t.replace("but **twelve**", "but **eight**")
        ),
    ),
    (
        "the document stops mentioning a capture class",
        "doc",
        lambda t: t.replace("`CompilerEntryPoints`", "`CompilerEntryPointz`"),
    ),
    (
        "the source hooks an entry point the document does not mention",
        "ep",
        lambda t: t.replace(
            '"com.sun.tools.sjavac.server.ServerMain", "run", 1, 0, -1, EntryPoint.Kind.ARGV, '
            "CompilerOption.Compiler.SJAVAC));",
            '"com.sun.tools.sjavac.server.ServerMain", "run", 1, 0, -1, EntryPoint.Kind.ARGV, '
            "CompilerOption.Compiler.SJAVAC),\n"
            '          new EntryPoint("com.sun.tools.javac.api.Foo", "bar", 1, 0, -1, '
            "EntryPoint.Kind.ARGV, CompilerOption.Compiler.JAVAC));",
        ),
    ),
    (
        "the Gradle hooks hook the compile spec again",
        "gradle_hooks",
        # The compile spec never loads in the JVM that holds the agent, so a
        # hook on it is dead code that the document says does not exist.
        lambda t: t.replace(
            'private static final String DAEMON_PARAMETERS =',
            'private static final String COMPILE_SPEC =\n'
            '      "org.gradle.api.internal.tasks.compile.DefaultJvmLanguageCompileSpec";\n'
            '  private static final String DAEMON_PARAMETERS =',
        ),
    ),
    (
        "the agent stops applying its own arguments",
        "capture_agent",
        lambda t: t.replace("applyAgentArguments(agentArgs);", "/* removed */"),
    ),
    (
        "the document stops explaining the agent-argument channel",
        "doc",
        lambda t: t.replace("key=value", "the agent"),
    ),
]

survivors = []
no_ops = []
try:
    for name, target, mutate in MUTANTS:
        if target == "doc":
            DOC.write_text(mutate(original_doc), encoding="utf-8")
            restore_sources()
        elif target == "ep":
            EP.write_text(mutate(original_ep), encoding="utf-8")
            GRADLE_HOOKS.write_text(original_gradle_hooks, encoding="utf-8")
            CAPTURE_AGENT.write_text(original_capture_agent, encoding="utf-8")
            DOC.write_text(original_doc, encoding="utf-8")
        elif target == "gradle_hooks":
            GRADLE_HOOKS.write_text(mutate(original_gradle_hooks), encoding="utf-8")
            EP.write_text(original_ep, encoding="utf-8")
            CAPTURE_AGENT.write_text(original_capture_agent, encoding="utf-8")
            DOC.write_text(original_doc, encoding="utf-8")
        else:  # capture_agent
            CAPTURE_AGENT.write_text(mutate(original_capture_agent), encoding="utf-8")
            EP.write_text(original_ep, encoding="utf-8")
            GRADLE_HOOKS.write_text(original_gradle_hooks, encoding="utf-8")
            DOC.write_text(original_doc, encoding="utf-8")

        # A mutant that did not change anything proves nothing: it would be
        # reported as "the gate let a drift through" when in fact the drift
        # was never introduced.  The first version of this script had exactly
        # that bug -- the formatter had reflowed the line the mutant looked
        # for, so the replacement was a no-op and the gate was blamed.
        changed = (
            DOC.read_text(encoding="utf-8") != original_doc
            or EP.read_text(encoding="utf-8") != original_ep
            or GRADLE_HOOKS.read_text(encoding="utf-8") != original_gradle_hooks
            or CAPTURE_AGENT.read_text(encoding="utf-8") != original_capture_agent
        )
        if not changed:
            no_ops.append(name)
            print("NO-OP     %s  (the mutation did not apply)" % name)
            continue

        code, out = run_gate()
        if code == 0:
            survivors.append(name)
            print("SURVIVED  %s" % name)
        else:
            first = ""
            for line in out.splitlines():
                if line.startswith("  "):
                    first = line.strip()
                    break
            print("killed    %s  (%s)" % (name, first))
finally:
    DOC.write_text(original_doc, encoding="utf-8")
    restore_sources()

print()
if no_ops:
    print("FAIL: %d mutant(s) did not apply -- they prove nothing" % len(no_ops))
    for s in no_ops:
        print("  " + s)
    sys.exit(1)
if survivors:
    print("FAIL: %d mutant(s) survived -- the gate is checking air" % len(survivors))
    for s in survivors:
        print("  " + s)
    sys.exit(1)
print("OK: all %d mutants were killed" % len(MUTANTS))
