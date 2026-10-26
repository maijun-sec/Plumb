#!/usr/bin/env python3
"""Check that every javadoc @link target in the project names a type that exists.

A javadoc {@link} to a class that does not exist is a false statement about the codebase. It
tells a reader "the CHA-style resolver lives here" or "the per-rule knowledge lives there" --
and points at nothing. The product-documentation rule is that class and method references must
exist in source; the same rule applies to the javadoc, which is the documentation a developer
reads first.

How a target is resolved
------------------------

  * A target that starts with {@code org.plumb.} must be a type THIS project declares. A link
    that names one of the project's own packages but a class that does not exist is exactly the
    defect this gate exists for.
  * A BARE target must resolve to something -- a type this project declares, a JDK class, or a
    class on the dependency classpath. To resolve the third-party case without guessing, the
    classpath is read from Maven's own {@code dependency:build-classpath} output and the jars
    are searched for the class. A bare name that resolves nowhere is a phantom.

The classpath file is produced by:

    mvn -o -q -pl plumb-main dependency:build-classpath \\
        -Dmdep.outputFile=.aethercode/main_cp.txt -Dmdep.includeScope=compile

If that file is absent the gate still runs, but it cannot resolve third-party names, so it
reports them as unknown rather than silently passing. That is deliberate: a gate that quietly
stops checking is worse than one that fails.

    python scripts/check_javadoc_links.py

Exit code 0 when every target resolves, 1 otherwise.
"""

import collections
import io
import pathlib
import re
import subprocess
import sys
import zipfile

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent

MODULES = [
    "plumb-ai", "plumb-main", "plumb-dfa", "plumb-style", "plumb-rules",
    "plumb-tac", "plumb-parser", "plumb-reporter",
    # The Kotlin AST rule engine. Its sources are under src/main/kotlin.
    "plumb-dsl-kotlin",
    # plumb-build is an aggregator; its sub-modules carry the sources.
    "plumb-build/plumb-build-core",
    "plumb-build/plumb-build-maven",
    "plumb-build/plumb-build-capture",
]

# The classpath file Maven writes. Maven resolves a relative -Dmdep.outputFile against the
# MODULE directory, so the file lands in <module>/.aethercode/ when the path is relative --
# both locations are checked.
CLASSPATH_CANDIDATES = [
    REPO / ".aethercode" / "main_cp.txt",
    REPO / "plumb-main" / ".aethercode" / "main_cp.txt",
]

# A JDK class is resolvable by the compiler, so a link to one is never a phantom. Only the
# names this project actually links to are listed; the list is short because the gate only
# needs to cover the bare names the project's javadoc uses.
JDK_NAMES = {
    "Boolean", "Integer", "Long", "Double", "Float", "Short", "Byte", "Character",
    "Number", "String", "Object", "Class", "Exception", "RuntimeException",
    "IllegalArgumentException", "IllegalStateException", "NullPointerException",
    "UnsupportedOperationException", "IOException", "Throwable", "Error",
    "System", "Thread", "ThreadGroup", "ThreadLocal", "Path", "Paths", "Files",
    "Optional", "Objects", "Arrays", "Collections", "List", "Map", "Set", "Queue",
    "Deque", "Iterator", "Comparable", "Comparator", "Runnable", "Iterable",
    "HashMap", "LinkedHashMap", "TreeMap", "HashSet", "LinkedHashSet", "TreeSet",
    "ArrayList", "LinkedList", "ConcurrentHashMap", "ExecutorService", "BiConsumer",
    "Matcher", "Pattern", "LongAdder", "ReentrantLock", "Semaphore", "AtomicInteger",
    "URL", "URI", "Socket", "InputStream", "OutputStream", "Reader", "Writer",
    "Method", "Constructor", "Field", "Annotation", "Enum", "Record",
    "SuppressWarnings", "Deprecated", "Override", "FunctionalInterface", "SafeVarargs",
    "AssertionError", "IllegalMonitorStateException", "ObjectOutputStream",
    "BufferedOutputStream", "ServiceLoader",
    "StringBuilder", "StringBuffer", "FileWriter", "FileReader", "OutputStreamWriter",
    "InputStreamReader", "File", "FileInputStream", "FileOutputStream",
    "PrintStream", "PrintWriter", "BufferedReader", "BufferedWriter",
}


def source_roots():
    """Every source root: main and test, for every module.

    Both the `java` and the `kotlin` layouts are included.  The
    `plumb-dsl-kotlin` module puts its sources under `src/main/kotlin`,
    and a type declared there is just as real as one declared in Java
    -- the documentation must be able to name it.
    """
    roots = []
    for module in MODULES:
        for kind in ("main", "test"):
            for lang in ("java", "kotlin"):
                root = REPO / module / "src" / kind / lang
                if root.is_dir():
                    roots.append(root)
    return roots


def collect_declared():
    """Every type the project declares, by FQCN and by simple name, plus its packages."""
    fqcn = set()
    simple = set()
    packages = set()
    for root in source_roots():
        # Kotlin sources declare types too.  The project has a Kotlin
        # module (`plumb-dsl-kotlin`), and a type name that the
        # documentation names must resolve regardless of the language
        # it is written in.  Kotlin's declaration forms are `class`,
        # `object`, `interface`, `enum class`, and `data class`; the
        # `data` / `enum` / `sealed` / `abstract` / `open` modifiers
        # are optional prefixes, so the regex accepts them.
        for path in list(root.rglob("*.java")) + list(root.rglob("*.kt")):
            text = path.read_text(encoding="utf-8", errors="replace")
            pkg = ""
            m = re.search(r"^package\s+([\w.]+);", text, re.M)
            if not m:
                m = re.search(r"^package\s+([\w.]+)\s*$", text, re.M)
            if m:
                pkg = m.group(1)
                packages.add(pkg)
            for cm in re.finditer(
                r"\b(?:class|interface|enum|record|object)\s+(\w+)", text
            ):
                name = cm.group(1)
                simple.add(name)
                if pkg:
                    fqcn.add(pkg + "." + name)
    return fqcn, simple, packages


def classpath_entries():
    """The dependency jars, from Maven's own classpath output.

    The file is generated on demand: a gate that needs a file nobody produces is a gate that
    quietly stops checking. Maven resolves a relative -Dmdep.outputFile against the MODULE
    directory, so the file lands in plumb-main/.aethercode/ -- that is where it is read from.
    """
    for candidate in CLASSPATH_CANDIDATES:
        if candidate.is_file():
            text = candidate.read_text(encoding="utf-8", errors="replace").strip()
            entries = [p for p in text.split(";") if p.strip()]
            if entries:
                return entries
    return generate_classpath()


def generate_classpath():
    """Ask Maven for the dependency classpath and return the entries."""
    target = REPO / "plumb-main" / ".aethercode" / "main_cp.txt"
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        proc = subprocess.run(
            ["mvn", "-o", "-q", "-pl", "plumb-main", "dependency:build-classpath",
             "-Dmdep.outputFile=main_cp.txt", "-Dmdep.includeScope=compile"],
            cwd=REPO, capture_output=True, text=True, encoding="utf-8", errors="replace")
    except OSError:
        return []
    if proc.returncode != 0 or not target.is_file():
        return []
    text = target.read_text(encoding="utf-8", errors="replace").strip()
    return [p for p in text.split(";") if p.strip()]


def classpath_simple_names(entries):
    """The simple names of every class in the dependency jars."""
    names = set()
    for entry in entries:
        path = pathlib.Path(entry)
        if not path.is_file() or path.suffix != ".jar":
            continue
        try:
            with zipfile.ZipFile(path) as zf:
                for info in zf.infolist():
                    if not info.filename.endswith(".class"):
                        continue
                    simple = info.filename[:-len(".class")].split("/")[-1]
                    # An inner class is "Outer$Inner"; the link names the outer.
                    simple = simple.split("$")[0]
                    if simple:
                        names.add(simple)
        except (zipfile.BadZipFile, OSError):
            continue
    return names


# A backticked token in the markdown that looks like a type reference.
# It must START with an uppercase letter and END with a type-ish suffix, so
# that prose words ("File", "Path", "Config") are not mistaken for types.
#
# The token may be a PATH to a type ("plumb-dfa/src/test/.../FooTest").  The
# type name is then the last path segment, so the pattern captures the whole
# backticked token and the name is taken from it afterwards.
MARKDOWN_TYPE_TOKEN = re.compile(
    r"`([A-Za-z0-9_./$\\-]*[A-Z][A-Za-z0-9_$]*(?:Check|Checker|Test|Engine|Strategy|"
    r"Builder|Program|Store|Registry|Resolver|Adapter|Finder|Reader|Writer|Emitter|"
    r"Reporter|Tracker|Analyser|Analyzer|Controller|Loader|Context|Config|Summary|"
    r"Graph|Bridge|Solver|Rule|Finding|Operation|Shape|Kind|Trace|Sink|Source|"
    r"Factory|Impl|Info|Analysis)[A-Za-z0-9_$]*)`")


def fact_kind_names():
    """The fact kind names, read from the shipped fact-kinds.yaml.

    A fact kind is a string constant, not a java type, but the documentation
    names them in backticks exactly like types.  They must resolve too.
    """
    yaml = REPO / "plumb-dfa" / "src" / "main" / "resources" / "fact-kinds.yaml"
    if not yaml.is_file():
        return set()
    names = set()
    in_kinds = False
    for line in yaml.read_text(encoding="utf-8", errors="replace").splitlines():
        if re.match(r"^fact_kinds:\s*$", line):
            in_kinds = True
            continue
        if in_kinds:
            if re.match(r"^\S", line):
                break  # a new top-level key
            m = re.match(r"^  ([A-Z][A-Za-z0-9]*):\s*$", line)
            if m:
                names.add(m.group(1))
    return names


def configured_checker_names():
    """The checker names configured in the shipped checkers.yaml.

    These are configuration KEYS, not java class names: the yaml says
    "OrmLoadNullCheckDfa" and the code that implements it is
    "OrmLoadNullCheckDfaChecker".  The documentation quotes the yaml key, so
    the gate must know it.
    """
    yaml = REPO / "plumb-dfa" / "src" / "main" / "resources" / "checkers.yaml"
    if not yaml.is_file():
        return set()
    names = set()
    for line in yaml.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"^  ([A-Z][A-Za-z0-9]*):\s*$", line)
        if m:
            names.add(m.group(1))
    return names


def check_markdown_types(known, declared_simple):
    """Backticked type names in the product documentation must exist.

    The same rule the javadoc half of this gate applies, applied to the
    markdown.  A name that is neither a project type, a JDK type, nor a
    dependency class is a phantom -- and a deleted test class documented as
    if it existed is the usual way one gets there.

    Only names that look like type references are checked.  A word in prose
    that happens to be capitalised is not a type reference, and flagging
    those would make the gate noise.
    """
    missing = collections.defaultdict(list)
    docs = REPO / "docs"
    if not docs.is_dir():
        return missing
    for doc in sorted(docs.rglob("*.md")):
        text = doc.read_text(encoding="utf-8", errors="replace")
        rel = doc.relative_to(REPO).as_posix()
        # A code block shows real code; a class named there is either
        # imported or local to the snippet, and is not a documentation claim.
        in_fence = False
        for i, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for m in MARKDOWN_TYPE_TOKEN.finditer(line):
                token = m.group(1)
                # The token may be a path; the type name is its last segment.
                name = token.replace("\\", "/").rsplit("/", 1)[-1]
                if not name:
                    continue
                # "Type.member" -- a member reference.  The TYPE is the part
                # before the LAST dot, and the member is a separate claim.
                if "." in name:
                    head = name.rsplit(".", 1)[0]
                    if head and head[0].isupper():
                        name = head
                    else:
                        # The prefix is not a type name ("engine.run(...)").
                        # The reference names a method; there is no type claim.
                        continue
                # A nested class: "Outer$Inner".  The OUTER is what must exist.
                if "$" in name:
                    name = name.split("$")[0]
                if not name or not name[0].isupper():
                    continue
                if name in known or name in declared_simple:
                    continue
                missing[name].append("%s:%d" % (rel, i))
    return missing


def main():
    fqcn, simple, packages = collect_declared()
    entries = classpath_entries()
    third_party = classpath_simple_names(entries)
    known = simple | JDK_NAMES | third_party | fact_kind_names() | configured_checker_names()

    if not entries:
        print("NOTE: no dependency classpath found -- third-party names cannot be resolved.")
        print("      Generate it with:")
        print("        mvn -o -q -pl plumb-main dependency:build-classpath \\")
        print("            -Dmdep.outputFile=main_cp.txt -Dmdep.includeScope=compile")
        print()

    missing = collections.Counter()
    where = collections.defaultdict(list)

    for root in source_roots():
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="replace")
            for lm in re.finditer(r"\{@link\s+([\w.]+)", text):
                target = lm.group(1)
                rel = path.relative_to(REPO).as_posix()

                if target.startswith("org.plumb."):
                    if target in fqcn:
                        continue
                    # A link to a PACKAGE is legitimate javadoc ("see {@link org.plumb.ai.tools}").
                    if target in packages:
                        continue
                    # A member reference: org.plumb.x.Y#method or org.plumb.x.Y.method.
                    cls = target.split("#")[0]
                    if cls in fqcn or cls.split(".")[-1] in simple:
                        continue
                    missing[target] += 1
                    where[target].append(rel)
                    continue

                if "." in target:
                    # A dotted third-party name. The compiler resolves it against the
                    # dependency classpath, so a missing one is a compile-time problem, not
                    # documentation drift.
                    continue

                # A bare name: it must resolve to something.
                if target in known:
                    continue
                missing[target] += 1
                where[target].append(rel)

    failed = False

    if missing:
        failed = True
        print("javadoc @link targets that name a type that does NOT exist:\n")
        for target, count in missing.most_common():
            print("  %-46s x%-3d  e.g. %s" % (target, count, where[target][0]))
        print()
        print("A javadoc link to a class that does not exist is a false statement about the code.")
    else:
        print("OK: every javadoc @link target resolves to a type that exists.")

    # --- the same rule for the product documentation ------------------------
    # The markdown names types in backticks rather than with {@link}.  A
    # backticked class name that does not exist is exactly the same defect:
    # a deleted test class kept being documented as if it existed.
    doc_missing = check_markdown_types(known, simple)
    if doc_missing:
        failed = True
        print()
        print("backticked type names in the product documentation that do NOT exist:\n")
        for target, where_list in sorted(doc_missing.items()):
            print("  %-46s x%-3d  e.g. %s" % (target, len(where_list), where_list[0]))
        print()
        print("A documented class that does not exist is a false statement about the code.")
    else:
        print("OK: every backticked type name in the documentation resolves.")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
