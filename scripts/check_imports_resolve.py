#!/usr/bin/env python3
"""Check that every `org.plumb` import in a tracked file names a package that exists.

A tracked file that imports a package the repository does not have is a
compile error that nothing in the build reports until someone tries to
compile that module. The file is in version control, the package is not,
and the failure looks like "the main branch does not compile" -- a claim
about a tree that nobody can point at.

The shape of the hole is specific: it catches a commit that depends on a
file nobody committed. A new class in a module, referenced from another
module, where the new class's own commit was never made: the import
resolves on the author's machine, the module does not compile on anyone
else's, and the report says "main is broken" without saying which file.

So the gate asks the question at the level the report is made at: does
every tracked file's import resolve INSIDE the tracked tree?

It reads HEAD, not the working tree. The question is what the commit
says, not what this checkout happens to hold: a gate that read the
working tree would pass on a tree with uncommitted work and fail on a
clean one, which is the opposite of a gate.

    python scripts/check_imports_resolve.py            # the commit at HEAD
    python scripts/check_imports_resolve.py --ref <ref>  # some other commit
"""
import argparse
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[1]

# The source roots whose directory layout mirrors the package layout.
SOURCE_ROOTS = (
    "src/main/java/",
    "src/test/java/",
    "src/main/kotlin/",
    "src/test/kotlin/",
)

# `import org.plumb.a.b.C;` and `import static org.plumb.a.b.C.member;`
IMPORT_RE = re.compile(
    r"^\s*import\s+(static\s+)?(org\.plumb\.[A-Za-z0-9_.]+)\s*;", re.M
)


def git(*args):
    """Run a git command in the repository and return its output."""
    result = subprocess.run(
        ["git", "-C", str(REPO), *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stderr.decode("utf-8", "replace"))
        raise SystemExit(f"git {' '.join(args)} failed")
    return result.stdout.decode("utf-8", "replace")


def tracked_files(ref):
    """The paths the commit tracks."""
    return [p for p in git("ls-tree", "-r", ref, "--name-only").splitlines() if p]


def packages_and_types(paths):
    """The packages the tracked sources declare, and the types inside them.

    A source file's directory under `src/main/java` (or the Kotlin
    equivalent) IS its package, and its name is the type it declares. So
    the tree says which packages exist and which types they hold
    without parsing a single file.
    """
    packages = set()
    types = set()
    for path in paths:
        norm = path.replace("\\", "/")
        if not (norm.endswith(".java") or norm.endswith(".kt")):
            continue
        for root in SOURCE_ROOTS:
            index = norm.find(root)
            if index < 0:
                continue
            rest = norm[index + len(root):]
            directory, _, file_name = rest.rpartition("/")
            package = directory.replace("/", ".")
            if not package:
                break
            packages.add(package)
            types.add((package, file_name.rsplit(".", 1)[0]))
            break
    return packages, types


def imports_of(paths, ref):
    """Every distinct `org.plumb` import the tracked sources declare."""
    if not any(p.endswith((".java", ".kt")) for p in paths):
        return set()
    # The pathspec is a glob, not a file list: naming two thousand files
    # on the command line overflows it on Windows.
    out = git(
        "grep",
        "-h",
        "-E",
        r"^\s*import\s+(static\s+)?org\.plumb\.",
        ref,
        "--",
        "*.java",
        "*.kt",
    )
    found = set()
    for line in out.splitlines():
        match = IMPORT_RE.match(line)
        if not match:
            continue
        is_static = bool(match.group(1))
        name = match.group(2)
        if is_static:
            # A static import names a MEMBER: the type is the segment
            # before it.
            name = name.rsplit(".", 1)[0]
        found.add(name)
    return found


def resolves(imported, packages, types):
    """Whether the import names something the tree has.

    An import names a package and a type in it. The type may be nested
    (`org.plumb.tac.ir.TacOperand.TacVar`), and the import may be a
    wildcard (`org.plumb.ai.tools.*`), which names a package and
    nothing else.

    The package has to be one the tree declares, and the type has to be
    one that package declares. Accepting a shorter package prefix would
    be wrong: `org.plumb.dfa.summary.nosuch.Missing` starts with a
    package the tree HAS, and is still an import of a package the tree
    does not have.
    """
    parts = imported.split(".")
    if parts and parts[-1] == "*":
        return ".".join(parts[:-1]) in packages
    # The longest package prefix first: the segment right after it is
    # the type, and the ones after that are nested types inside it.
    for cut in range(len(parts) - 1, 0, -1):
        prefix = ".".join(parts[:cut])
        if prefix in packages:
            return (prefix, parts[cut]) in types
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--ref",
        default="HEAD",
        help="the commit to check (default: HEAD)",
    )
    args = parser.parse_args()

    paths = tracked_files(args.ref)
    packages, types = packages_and_types(paths)
    imports = imports_of(paths, args.ref)
    unresolved = sorted(i for i in imports if not resolves(i, packages, types))

    print(f"tracked sources at {args.ref}   : {sum(1 for p in paths if p.endswith(('.java', '.kt')))}")
    print(f"org.plumb packages at {args.ref} : {len(packages)}")
    print(f"distinct org.plumb imports     : {len(imports)}")
    print(f"imports with no package here    : {len(unresolved)}")
    if unresolved:
        for imported in unresolved:
            print(f"  MISSING: {imported}")
        print()
        print("A tracked file imports a package the tree does not have.")
        print("The module will not compile, and nothing says which file.")
        return 1
    print(f"OK: every org.plumb import at {args.ref} resolves inside it")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
