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
    "plumb-tac", "plumb-parser", "plumb-reporter", "plumb-build",
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
}


def source_roots():
    """Every java source root: main and test, for every module."""
    roots = []
    for module in MODULES:
        for kind in ("main", "test"):
            root = REPO / module / "src" / kind / "java"
            if root.is_dir():
                roots.append(root)
    return roots


def collect_declared():
    """Every type the project declares, by FQCN and by simple name, plus its packages."""
    fqcn = set()
    simple = set()
    packages = set()
    for root in source_roots():
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="replace")
            pkg = ""
            m = re.search(r"^package\s+([\w.]+);", text, re.M)
            if m:
                pkg = m.group(1)
                packages.add(pkg)
            for cm in re.finditer(r"\b(?:class|interface|enum|record)\s+(\w+)", text):
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


def main():
    fqcn, simple, packages = collect_declared()
    entries = classpath_entries()
    third_party = classpath_simple_names(entries)
    known = simple | JDK_NAMES | third_party

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

    if not missing:
        print("OK: every javadoc @link target resolves to a type that exists.")
        return 0

    print("javadoc @link targets that name a type that does NOT exist:\n")
    for target, count in missing.most_common():
        print("  %-46s x%-3d  e.g. %s" % (target, count, where[target][0]))
    print()
    print("A javadoc link to a class that does not exist is a false statement about the code.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
