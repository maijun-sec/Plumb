#!/usr/bin/env python3
"""Check that every Java source carries the licence header.

A licence header is a promise. It says the file is covered by the
project's terms. A file without one is a file the promise does not
reach, and there are two ways that happens:

  1. somebody added a file and forgot the header, and
  2. somebody regenerated a file and the generator did not emit one.

The second is the one that bites. A generator that writes the file
from scratch drops anything it does not know about, so a header added
by hand is a header that disappears on the next run. That is why the
generator emits the header itself, and why this gate checks the
GENERATED file too.

The checks:

  1. Every Java source starts with the exact header.
  2. The header is the FIRST thing in the file -- a header after the
     package declaration is not a header.
  3. The LICENSE file at the root says the same thing the headers
     say. One text, one source: scripts/license_header.py.
  4. The generated file carries the header, and the generator still
     produces it (regenerating must not drop it).

    python scripts/check_license.py
"""
import os
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from add_license_headers import java_sources  # noqa: E402
from license_header import (  # noqa: E402
    LICENSE_REL_PATH,
    header_text,
    license_file_text,
)

REPO = pathlib.Path(__file__).resolve().parents[1]

# The generated Java file. It is checked like any other source, but
# it is ALSO checked by regenerating it: a header that the generator
# does not emit is a header that disappears the next time somebody
# runs the generator.
GENERATED_JAVA = [
    "plumb-style/src/main/java/org/plumb/style/CheckerCategories.java",
    "plumb-rules/src/main/java/org/plumb/rules/framework/FrameworkInputAnnotations.java",
]

GENERATORS = [
    ("scripts/gen_categories.py", "CheckerCategories.java"),
    ("scripts/gen_framework_annotations.py", "FrameworkInputAnnotations.java"),
]


def note(msg):
    print(msg)


def has_header(raw):
    """Does these bytes start with the header?

    Bytes, not text: the header text is LF and the checked-out tree
    is CRLF, so a decoded comparison would fail on every file.
    """
    expected = header_text().encode("utf-8").replace(b"\r\n", b"\n")
    return raw.replace(b"\r\n", b"\n").startswith(expected)


def main():
    failures = []

    # (1) Every Java source starts with the header.
    files = java_sources()
    if not files:
        print("FAIL: no Java sources found -- the check cannot verify anything")
        return 1

    missing = []
    misplaced = []
    duplicated = []
    for path in files:
        raw = path.read_bytes()
        rel = path.relative_to(REPO).as_posix()
        text = raw.replace(b"\r\n", b"\n").decode("utf-8", errors="replace")
        before_package = text.split("package ", 1)[0]

        if has_header(raw):
            # The header is at the top. But it may ALSO be somewhere
            # else in the file -- a file with two headers is a file
            # where somebody pasted one in by hand and then a
            # generator added another. The header at the top is what
            # the check is about; a second copy is a defect of its
            # own, so it is reported separately.
            if text.count("Copyright (c)") > 1:
                duplicated.append(rel)
            continue

        # Is there a header, but not at the top? A header after the
        # package declaration is not a header: a reader scanning the
        # file for the terms has to find them.
        if "Copyright (c)" in before_package:
            misplaced.append(rel)
        else:
            missing.append(rel)
    note("checked %d Java source file(s)" % len(files))

    for rel in missing:
        failures.append("%s does not carry the licence header" % rel)
    for rel in misplaced:
        failures.append("%s has the licence header, but not at the top of "
                        "the file" % rel)
    for rel in duplicated:
        failures.append("%s carries the licence header more than once" % rel)
    note("checked the header on %d file(s)" % len(files))

    # (2) The LICENSE file says the same thing the headers say.
    lic = REPO / LICENSE_REL_PATH
    if not lic.is_file():
        failures.append("%s does not exist" % LICENSE_REL_PATH)
    else:
        actual = lic.read_text(encoding="utf-8")
        expected = license_file_text()
        if actual != expected:
            failures.append(
                "%s does not match the text the headers carry -- the licence "
                "file and the file headers are two different promises"
                % LICENSE_REL_PATH)
        else:
            note("checked the LICENSE file against the header text")

    # (3) The generated file carries the header, and the generator
    #     still produces it.
    #
    #     Regenerating is the real check. A header on the generated
    #     file that the generator does not emit is a header that
    #     disappears the next time somebody runs the generator -- and
    #     the file on disk looks fine right up until then.
    for rel in GENERATED_JAVA:
        path = REPO / rel
        if not path.is_file():
            failures.append("%s does not exist" % rel)
            continue
        if not has_header(path.read_bytes()):
            failures.append("%s does not carry the licence header" % rel)
    note("checked %d generated file(s)" % len(GENERATED_JAVA))

    for script, produced in GENERATORS:
        script_path = REPO / script
        if not script_path.is_file():
            failures.append("%s does not exist" % script)
            continue
        body = script_path.read_text(encoding="utf-8", errors="replace")
        if "header_text" not in body:
            failures.append(
                "%s does not use the shared header text -- regenerating the "
                "file would drop the header" % script)
            continue
        # Regenerate into a scratch copy and confirm the header is in
        # the regenerated text. The generator is run in --check mode
        # first: if the committed file is already stale, the check
        # below would be measuring the wrong thing.
        check = subprocess.run(
            [sys.executable, str(script_path), "--check"],
            capture_output=True, text=True, cwd=str(REPO),
            encoding="utf-8", errors="replace")
        if check.returncode != 0:
            failures.append(
                "%s --check fails, so the generated file is stale and the "
                "regeneration check below would measure the wrong thing"
                % script)
            continue
        # The generator's own render is what decides the content, so
        # ask the generator for it rather than re-running it.
        #
        # The probe is written to a file rather than passed with -c: the
        # two generators have different render signatures -- one takes
        # the collected entries, the other takes nothing -- and a
        # try/except does not survive being crammed onto one line.
        with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False,
                                         encoding="utf-8") as fh:
            fh.write(
                "import importlib.util\n"
                "import sys\n"
                "sys.path.insert(0, 'scripts')\n"
                "spec = importlib.util.spec_from_file_location('g', %r)\n"
                "m = importlib.util.module_from_spec(spec)\n"
                "spec.loader.exec_module(m)\n"
                "# The two generators have different render signatures:\n"
                "# one takes the collected entries, the other takes\n"
                "# nothing. Whichever shape this one has, call it.\n"
                "if hasattr(m, 'collect'):\n"
                "    out = m.render(m.collect())\n"
                "else:\n"
                "    out = m.render()\n"
                "sys.stdout.write(out)\n" % script.replace("\\", "/"))
            probe_path = fh.name
        try:
            rendered = subprocess.run(
                [sys.executable, probe_path],
                capture_output=True, text=True, cwd=str(REPO),
                encoding="utf-8", errors="replace")
        finally:
            os.unlink(probe_path)
        if rendered.returncode != 0:
            failures.append(
                "%s could not be re-rendered to check its header: %s"
                % (script, (rendered.stderr or "").strip()[:200]))
            continue
        if not rendered.stdout.startswith(header_text()):
            failures.append(
                "%s does not emit the licence header -- regenerating %s would "
                "drop it" % (script, produced))
    note("checked %d generator(s)" % len(GENERATORS))

    if failures:
        print()
        print("FAIL: %d defect(s) in the licence headers" % len(failures))
        for f in failures[:40]:
            print("  %s" % f)
        if len(failures) > 40:
            print("  ... and %d more" % (len(failures) - 40))
        print()
        print("A licence header is a promise about a file. A file")
        print("without one is a file the promise does not reach.")
        return 1

    print()
    print("OK: every Java source carries the licence header")
    return 0


if __name__ == "__main__":
    sys.exit(main())
