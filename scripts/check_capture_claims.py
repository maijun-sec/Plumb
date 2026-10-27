#!/usr/bin/env python3
"""Check the capture section of docs/tech/ARCHITECTURE.md against the source.

The document names the compiler entry points the agent hooks, and the class
that carries the capture logic.  Both are claims that rot quietly: a new entry
point that nobody documented is invisible, and a documented entry point that
was renamed is a lie the reader will try to reproduce.

The check is bidirectional:

  * every entry point in the source appears in the document's table;
  * every entry point in the document's table exists in the source.

The second direction is the one that earns its keep.  The source once claimed
that it hooked ``javax.tools.JavaCompiler#run`` -- a method that does not exist
on a modern JDK, because ``javax.tools.JavaCompiler`` is an interface there.
The hook never fired, so the capture was silently empty for every caller that
used the run-style API.  A gate that only checked "is the source consistent
with itself" would have passed that happily.

The script prints what it derives before it compares, and reports how many
things it checked -- a check that skipped everything looks exactly like a check
that found nothing.

Exit code 0 when the document matches the source, 1 otherwise.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "tech" / "ARCHITECTURE.md"
CAPTURE = REPO / "plumb-build" / "plumb-build-capture" / "src" / "main" / "java" / "org" / "plumb" / "build" / "capture"

doc = DOC.read_text(encoding="utf-8", errors="replace")
failures = []
info = []


def note(msg):
    info.append(msg)
    print(msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def method_body(text, name):
    """The body of a method, by brace matching.

    A regular expression cannot do this: the body of the method being looked
    for contains nested blocks, so a pattern like ``\\{[^}]*\\}`` stops at the
    first closing brace and sees a fragment rather than the whole body.  That
    produced two false failures when this check was first written -- the gate
    reported a defect in a source that was perfectly fine.

    The braces are counted instead, so the body is found whatever it holds.
    """
    m = re.search(r"\b%s\s*\([^)]*\)\s*\{" % re.escape(name), text)
    if not m:
        return None
    depth = 0
    for i in range(m.end() - 1, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[m.end() : i]
    return None


if not CAPTURE.is_dir():
    print("FAIL: the capture module source is missing at %s" % CAPTURE)
    sys.exit(1)

sources = {p.stem: strip_comments(read(p)) for p in CAPTURE.glob("*.java")}
note("indexed %d capture source files: %s" % (len(sources), sorted(sources)))

# ------------------------------------------------- 1. the source's entry points
ep_src = sources.get("CompilerEntryPoints")
if ep_src is None:
    failures.append("the capture module has no CompilerEntryPoints class")
    entry_points = []
else:
    # Each entry point is constructed as
    #   new EntryPoint("type", "method", argc, options, sources, Kind, Compiler)
    # The compiler argument is optional, and may be `null`.
    entry_points = []
    for m in re.finditer(
        r'new\s+EntryPoint\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(-?\d+)'
        r'\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*EntryPoint\.Kind\.([A-Z_]+)'
        r'(?:\s*,\s*(?:CompilerOption\.Compiler\.([A-Z_]+)|null))?\s*\)',
        ep_src,
        flags=re.S,
    ):
        entry_points.append(
            {
                "type": m.group(1),
                "method": m.group(2),
                "argc": int(m.group(3)),
                "options": int(m.group(4)),
                "sources": int(m.group(5)),
                "kind": m.group(6),
                "compiler": m.group(7),
            }
        )
    note("the source declares %d entry point(s)" % len(entry_points))
    if not entry_points:
        failures.append(
            "no entry points could be derived from CompilerEntryPoints -- "
            "the check cannot verify anything"
        )

# ------------------------------------------------- 2. the document's table
# The table is the one under the "capture 挂哪些入口" heading.  Its rows look
# like:  | `com.sun.tools.javac.Main#compile(String[])` | who goes this way |
m = re.search(r"###\s*7\.0\.1.*?\n(.*?)(?=\n###\s|\Z)", doc, flags=re.S)
if m is None:
    failures.append("the document no longer has a §7.0.1 section")
    doc_rows = []
else:
    section = m.group(1)
    doc_rows = []
    for line in section.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        # Skip the header row and the separator row.
        if set(stripped) <= set("| -:"):
            continue
        cell = stripped.split("|")[1].strip()
        # The cell holds a backticked signature: `Type#method(args)`
        for sig in re.findall(r"`([^`]+#[^`]+)`", cell):
            doc_rows.append(sig)
    note("the document's table lists %d entry point signature(s)" % len(doc_rows))
    if not doc_rows:
        failures.append(
            "the document's entry point table lists nothing -- the check "
            "cannot verify it"
        )

# ------------------------------------------------- 3. compare, both ways
def doc_signature(type_name, method_name):
    """The document's form of a signature: `Type#method(args...)`."""
    for row in doc_rows:
        if "#" not in row:
            continue
        type_part, method_part = row.split("#", 1)
        method_only = method_part.split("(", 1)[0].strip()
        if type_part.strip() == type_name and method_only == method_name:
            return row
    return None


source_sigs = set()
for e in entry_points:
    source_sigs.add("%s#%s" % (e["type"], e["method"]))

documented = set()
for row in doc_rows:
    type_part, method_part = row.split("#", 1)
    documented.add("%s#%s" % (type_part.strip(), method_part.split("(", 1)[0].strip()))

missing_from_doc = sorted(source_sigs - documented)
if missing_from_doc:
    failures.append(
        "the source hooks entry point(s) the document does not mention: %s"
        % missing_from_doc
    )
else:
    note("every source entry point is documented")

missing_from_source = sorted(documented - source_sigs)
if missing_from_source:
    failures.append(
        "the document claims entry point(s) the source does not hook: %s"
        % missing_from_source
    )
else:
    note("every documented entry point is hooked by the source")

# ------------------------------------------------- 4. the count the document states
# The document says the count in prose, before the table.  The wording
# differs between the Chinese and the English document, so both are matched.
#
# The number may be an Arabic digit or a Chinese numeral.
m = re.search(
    r"(?:「javac 命令行」不是一个方法，而是|"
    r'The "javac command line" is not one method but)'
    r"\s*\*\*([^*]+?)\*\*",
    doc,
)
if m:
    claimed = m.group(1)
    # The document says "十二个" / "twelve" -- the counter suffix is not part
    # of the number.
    claimed = claimed.rstrip("个").strip()
    numerals = {
        "八": 8, "九": 9, "十": 10, "十一": 11, "十二": 12, "十三": 13,
        "十四": 14, "十五": 15, "十六": 16,
        "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
        "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16,
    }
    actual = len(entry_points)
    if claimed.isdigit():
        stated = int(claimed)
    elif claimed in numerals:
        stated = numerals[claimed]
    else:
        stated = None
        failures.append(
            "the document says the entry point count is %r, which this check "
            "cannot read" % claimed
        )
    if stated is not None and stated != actual:
        failures.append(
            "the document says %s entry points; the source declares %d"
            % (claimed, actual)
        )
    elif stated is not None:
        note("the document's count (%s) matches the source (%d)" % (claimed, actual))
else:
    failures.append("the document no longer states the entry point count")

# ------------------------------------------------- 5. the classes the document names
# The document lists the capture module's classes.  Each must exist.
capture_classes = {
    "CaptureAgent",
    "CaptureRecorder",
    "CaptureEntry",
    "CaptureSink",
    "CaptureReader",
    "JavacOptions",
    "CompilerHooks",
    "CompilerEntryPoints",
    "GradleHooks",
}
named = set()
for m in re.finditer(r"`([A-Z][A-Za-z0-9_]*)`", doc):
    named.add(m.group(1))
missing = sorted(c for c in named if c in capture_classes and c not in sources)
if missing:
    failures.append(
        "the document names capture class(es) that do not exist: %s" % missing
    )
else:
    note("all %d capture classes named in the document exist" % len(capture_classes))

# ------------------------------------------------- 6. the classes the source has
# Every class in the capture package should be mentioned somewhere in the
# document, so a new class is documented the day it is written.
undocumented = sorted(
    stem for stem in sources
    if stem not in named and not stem.endswith("Test")
)
if undocumented:
    failures.append(
        "the capture module has class(es) the document never mentions: %s"
        % undocumented
    )
else:
    note("every capture class is mentioned in the document")

# ------------------------------------------------- 7. no raw ASM
# The module must instrument with Advice.  A hand-written MethodVisitor is
# what this module was rewritten to remove, and it is easy to reintroduce.
for p in sorted(CAPTURE.glob("*.java")):
    for line in read(p).splitlines():
        stripped = line.strip()
        if not stripped.startswith("import "):
            continue
        if "net.bytebuddy.jar.asm" in stripped or "org.objectweb.asm" in stripped:
            failures.append(
                "%s imports raw ASM: %s" % (p.name, stripped)
            )
note("no capture source imports raw ASM")

# ------------------------------------------------- 8. the Gradle classpath source
# The document claims the Gradle classpath comes from the compiler hook and
# NOT from a Gradle-specific compile-spec hook.  That claim is a measurement
# (the compile spec never loads in the JVM that holds the agent), so it has
# to be checked against the source: a Gradle-specific hook would be dead code
# that the document says does not exist.
gradle_hook_src = sources.get("GradleHooks", "")
if not gradle_hook_src:
    failures.append("the capture module has no GradleHooks class")
else:
    # The compile spec class name, or the constant that holds it, must not
    # appear.  Both spellings are checked, because the hook could be written
    # against either.
    for forbidden in ("DefaultJvmLanguageCompileSpec", "setCompileClasspath"):
        if forbidden in gradle_hook_src:
            failures.append(
                "GradleHooks mentions %s: that hook never fires (the compile "
                "spec does not load in the JVM that holds the agent), and a "
                "second copy of a compile would break one-record-per-compile"
                % forbidden
            )
    if not any(
        forbidden in gradle_hook_src
        for forbidden in ("DefaultJvmLanguageCompileSpec", "setCompileClasspath")
    ):
        note("GradleHooks does not hook a Gradle compile spec")

# The classes that the removed design needed must be gone.  Their presence
# would mean a second, worse copy of a compile the compiler hook already
# recorded exactly.
for gone in ("GradleClasspathCapture", "GradleCompile"):
    if gone in sources:
        failures.append(
            "the capture module still has %s: the Gradle classpath comes from "
            "the compiler hook, not from a Gradle-specific capture" % gone
        )
note("no Gradle-specific capture class remains")

# ------------------------------------------------- 9. the agent's own arguments
# The document claims the agent's arguments are the channel that reaches
# Gradle's JVM.  That claim only holds if the agent really applies them.
agent_src = sources.get("CaptureAgent", "")
# The call site, not the declaration: removing the declaration would leave
# the name behind, and the gate would pass on dead code.
if "applyAgentArguments(agentArgs);" not in agent_src:
    failures.append(
        "CaptureAgent does not apply its own arguments: they are the only "
        "configuration channel that reaches Gradle's JVM"
    )
else:
    note("CaptureAgent applies its own arguments")

# The document must state the channel, because a reader who sets the property
# the obvious way -- inside org.gradle.jvmargs -- gets a silently inert
# capture.
if "-javaagent:jar=key=value" not in doc and "key=value" not in doc:
    failures.append(
        "the document no longer explains that the agent's own arguments are the "
        "channel that reaches Gradle's JVM"
    )
else:
    note("the document explains the agent-argument channel")

# ------------------------------------------------- 10. the sources the compiler really compiled
# The document claims the capture names the sources the compiler parsed, not
# only the ones it was given.  That claim is the whole point of the parse
# hook, and it rots quietly: if the hook is removed, the capture still works,
# and it is just quietly missing the code the compiler compiled.
#
# The claim is checked against the source in both directions:
#
#   * the parse hook must be wired, and it must be on the compiler's parse
#     method -- not on the file manager's content cache, which was measured to
#     stay empty;
#   * the capture must be written when the compilation ends, because the
#     sources are only known then.
advice_src = sources.get("CompiledSourcesAdvice", "")
recorder_src = sources.get("CaptureRecorder", "")
hooks_src = sources.get("CompilerHooks", "")
bridge_src = sources.get("CompiledSourcesBridge", "")

if not advice_src:
    failures.append("the capture module has no CompiledSourcesAdvice class")
else:
    # The hook must be on the parse method.  The file manager's content cache
    # is the plausible alternative and it does not work: the compiler reads a
    # source file's content through the file object, so the cache stays empty.
    #
    # The check is on the *shape* of the hook, not on a name: the advice must
    # take the file being parsed, and it must ask that file for its name.  A
    # hook on the cache would take a map instead.
    if "@Advice.Argument(0) JavaFileObject" not in advice_src:
        failures.append(
            "CompiledSourcesAdvice does not hook the file being parsed: the "
            "hook must be on the compiler's parse method, whose argument is "
            "the JavaFileObject it is about to parse"
        )
    if "getName()" not in advice_src:
        failures.append(
            "CompiledSourcesAdvice does not read the name of the file it is "
            "parsing: the capture would have no path to report"
        )
    if "Kind.SOURCE" not in advice_src:
        failures.append(
            "CompiledSourcesAdvice does not check the file's kind: the "
            "compiler also reads class files, and a class file is not a "
            "source"
        )
    if "contentCache" in advice_src or "getCachedContent" in advice_src:
        failures.append(
            "CompiledSourcesAdvice reads the file manager's content cache: it "
            "was measured to stay empty after a normal compile, so it cannot "
            "name the sources the compiler really compiled"
        )
    if "recordParsed" not in advice_src:
        failures.append(
            "CompiledSourcesAdvice does not record the parsed source: the "
            "capture would keep only the command-line sources"
        )

# The advice is inlined into the compiler's own class, which cannot see the
# agent's classes.  The bridge is what carries the value across.
if not bridge_src:
    failures.append("the capture module has no CompiledSourcesBridge class")
else:
    # The bridge must actually pass the path on to the recorder.  A bridge
    # that takes the path and does nothing with it is dead code, and the
    # name of the method alone would not show that.
    if "recordParsed" not in bridge_src:
        failures.append(
            "CompiledSourcesBridge does not carry the parsed source: the "
            "inlined advice cannot call the agent's own classes"
        )
    else:
        body = method_body(bridge_src, "recordParsed")
        if body is None or "invoke" not in body:
            failures.append(
                "CompiledSourcesBridge takes the parsed source and does not pass it "
                "to the recorder: the capture would keep only the command-line "
                "sources"
            )

# The advice must actually be wired to a parse entry point.
if "CompiledSourcesAdvice" not in hooks_src:
    failures.append(
        "CompilerHooks does not use CompiledSourcesAdvice: the parse hook is "
        "never applied, so the capture keeps only the command-line sources"
    )
else:
    note("the parse hook is wired")

# The capture must be written when the compilation ends.
if "onCompileFinished" not in recorder_src:
    failures.append(
        "CaptureRecorder does not write the capture when the compilation ends: "
        "the sources the compiler really compiled are only known then"
    )
else:
    # The method must exist, and the compile-end hook must write the capture
    # from it.  A declaration alone would leave the capture unwritten.
    body = method_body(recorder_src, "onCompileFinished")
    if body is None or "record(" not in body:
        failures.append(
            "CaptureRecorder declares onCompileFinished but does not write the "
            "capture from it: the sources the compiler really compiled would "
            "never be reported"
        )
    else:
        note("the capture is written when the compilation ends")

# The staged compile must be matched, not taken by position: a build can have
# more than one compilation in flight.
if "overlap" not in recorder_src:
    failures.append(
        "CaptureRecorder does not match the staged compile to the compilation "
        "that finished: a build with more than one compilation in flight would "
        "report the wrong options"
    )
else:
    # The match must actually be used to choose.  A helper that is called but
    # whose result is ignored would pass a name check.
    if not re.search(r"if\s*\(\s*overlap\s*\(", recorder_src):
        failures.append(
            "CaptureRecorder computes the overlap but does not use it to "
            "choose the staged compile: a build with more than one compilation "
            "in flight would report the wrong options"
        )
    else:
        note("the staged compile is matched to the compilation that finished")

# The document must state the claim, because a reader who trusts the capture to
# be complete is relying on it.
if "parse" not in doc or "source path" not in doc:
    failures.append(
        "the document no longer explains that the capture names the sources "
        "the compiler really compiled, including the ones found on the source "
        "path"
    )
else:
    note("the document explains the implicit sources")

print()
if failures:
    print("FAIL: %d defect(s) in the capture section of docs/tech/ARCHITECTURE.md"
          % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: the capture section of docs/tech/ARCHITECTURE.md matches the source")
