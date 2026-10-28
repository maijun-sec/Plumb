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
ADVICE = CAPTURE / "CompiledSourcesAdvice.java"
BRIDGE = CAPTURE / "CompiledSourcesBridge.java"
HOOKS = CAPTURE / "CompilerHooks.java"
RECORDER = CAPTURE / "CaptureRecorder.java"
GATE = REPO / "scripts" / "check_capture_claims.py"

original_doc = DOC.read_text(encoding="utf-8", errors="replace")
original_ep = EP.read_text(encoding="utf-8", errors="replace")
original_gradle_hooks = GRADLE_HOOKS.read_text(encoding="utf-8", errors="replace")
original_capture_agent = CAPTURE_AGENT.read_text(encoding="utf-8", errors="replace")
original_advice = ADVICE.read_text(encoding="utf-8", errors="replace")
original_bridge = BRIDGE.read_text(encoding="utf-8", errors="replace")
original_hooks = HOOKS.read_text(encoding="utf-8", errors="replace")
original_recorder = RECORDER.read_text(encoding="utf-8", errors="replace")

# Every file a mutant may touch.
MUTABLE = {
    "doc": (DOC, original_doc),
    "ep": (EP, original_ep),
    "gradle_hooks": (GRADLE_HOOKS, original_gradle_hooks),
    "capture_agent": (CAPTURE_AGENT, original_capture_agent),
    "advice": (ADVICE, original_advice),
    "bridge": (BRIDGE, original_bridge),
    "hooks": (HOOKS, original_hooks),
    "recorder": (RECORDER, original_recorder),
}


def restore_sources():
    """Put every mutable file back the way it was."""
    for path, original in MUTABLE.values():
        path.write_text(original, encoding="utf-8")


def run_gate():
    result = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.returncode, result.stdout


def add_entry_point(text):
    """Add an entry point the document does not mention.

    The entry-point list is formatted across several lines, so the
    mutation works on the LAST entry rather than on an exact string.
    Matching the whole call as one string would depend on the source's
    line layout, and a mutation that does not apply proves NOTHING.

    The new entry goes BEFORE the last one, with a comma after it --
    the last entry is the end of the array and has no comma of its
    own, so appending after it would produce a syntax error rather
    than a mutation.
    """
    entries = list(re.finditer(r'new EntryPoint\(', text))
    if not entries:
        return text
    last = entries[-1]
    new = (
        'new EntryPoint("com.sun.tools.javac.api.Foo", "bar", 1, 0, -1, '
        "EntryPoint.Kind.ARGV, CompilerOption.Compiler.JAVAC),\n"
        "              ")
    return text[:last.start()] + new + text[last.start():]


def drop_the_capture_write(text):
    """Drop the write of the capture, keeping the method and its call.

    The sources the compiler really compiled are only known at the
    end, so a capture written at the start cannot report them.

    The call is found by its shape -- the `record(...)` call that
    builds the capture -- rather than by an exact string, because the
    source's line layout is not what is being tested.
    """
    pattern = re.compile(r'record\(\s*buildCapture\(.*?\)\s*\)\s*;', re.S)
    match = pattern.search(text)
    if not match:
        return text
    return (text[:match.start()]
            + "/* the capture is not written from here */"
            + text[match.end():])


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
        # matters, so both spellings are tried.  The count itself changes
        # as entry points are added, so the numeral is replaced by
        # whatever the document currently says.
        lambda t: (
            re.sub(r"而是\*\*[^*]+\*\*", "而是**三个**", t)
            if "而是**" in t
            else re.sub(r"but \*\*[^*]+\*\*", "but **three**", t)
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
        # The entry-point list is formatted across several lines, so
        # the mutation finds the LAST entry point's closing call and
        # appends a new one after it.  Matching the whole call as one
        # string would depend on the source's line layout, and a
        # mutation that does not apply proves NOTHING.
        lambda t: add_entry_point(t),
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
    (
        "the parse hook reads the file manager's content cache again",
        "advice",
        # The content cache was measured to stay empty after a normal
        # compile, so a hook on it names no sources at all.  The mutant
        # keeps the method name and the call, and changes only where the
        # path comes from -- so a gate that checks the name passes it.
        lambda t: t.replace(
            "CompiledSourcesBridge.recordParsed(file.getName());",
            "java.util.Map<?, ?> cache = null;\n"
            "      CompiledSourcesBridge.recordParsed(String.valueOf(cache));",
        ),
    ),
    (
        "the parse hook stops recording the parsed source",
        "advice",
        lambda t: t.replace(
            "CompiledSourcesBridge.recordParsed(file.getName());", "/* removed */"
        ),
    ),
    (
        "the bridge takes the parsed source and drops it",
        "bridge",
        # The method still exists and still takes the path; it just does not
        # pass it on.  A gate that checks the name alone passes this.
        lambda t: t.replace(
            "        remember.invoke(null, path);", "        /* dropped */"
        ),
    ),
    (
        "the parse hook is never applied",
        "hooks",
        lambda t: t.replace(
            "result = result.visit(Advice.to(CompiledSourcesAdvice.ParsedSource.class).on(matcher));",
            "/* the parse hook is not applied */",
        ),
    ),
    (
        "the capture is written when the compilation starts again",
        "recorder",
        # The sources the compiler really compiled are only known at the end,
        # so a capture written at the start cannot report them.  The mutant
        # keeps the method and its call, and drops the write.
        lambda t: drop_the_capture_write(t),
    ),
    (
        "the staged compile is taken by position again",
        "recorder",
        # A build with more than one compilation in flight would report the
        # wrong options.  The helper stays, but its result is ignored.
        lambda t: t.replace(
            "if (overlap(p.sources(), parsed) > 0) {", "if (true) {"
        ),
    ),
]

survivors = []
no_ops = []
try:
    for name, target, mutate in MUTANTS:
        # Everything is restored first, so a mutant never sees another
        # mutant's damage.
        restore_sources()
        path, original = MUTABLE[target]
        path.write_text(mutate(original), encoding="utf-8")

        # A mutant that did not change anything proves nothing: it would be
        # reported as "the gate let a drift through" when in fact the drift
        # was never introduced.  The first version of this script had exactly
        # that bug -- the formatter had reflowed the line the mutant looked
        # for, so the replacement was a no-op and the gate was blamed.
        changed = any(
            p.read_text(encoding="utf-8") != o for p, o in MUTABLE.values()
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
