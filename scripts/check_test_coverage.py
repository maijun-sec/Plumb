#!/usr/bin/env python3
"""Check that every test file the build is supposed to run is actually run.

`plumb-dfa` pins surefire to an ALLOW LIST of test classes. That is
deliberate -- the module carries a large `integration/` tree of sweep
and dump programs that are `@Disabled` because they do not compile or
do not belong in a unit build.

But an allow list has a hole shaped exactly like "the test we forgot to
add": a test file that is not named in the list does not run, and
nothing says so. The build is green, the test file is on disk, and
the test has never been executed.

That is not hypothetical. Two test classes were missing from the list:

  - the resource-leak test, whose two failures exposed a checker that
    reported a `byte[]` and a `String` as leaks, and that did not
    recognise a close inside a `finally` block;
  - the deserialisation-filter test, which is the regression test for
    the JEP-290 mitigation.

Both were written, both passed in isolation, and neither ran.

So the gate checks the DIRECTION that matters: every test file that is
NOT `@Disabled` must be named by some module's surefire allow list.

The rule is per module, because the modules differ: `plumb-dfa` pins
an allow list, and the other modules use surefire's defaults, which
already run everything. So a module is either "pinned" (it has an
`<includes>` list, and every non-disabled test file must be named) or
"default" (no list, and surefire's own default pattern applies).

    python scripts/check_test_coverage.py
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[1]

# The modules whose tests are checked. A module that pins an allow list
# is checked against that list; a module that does not is checked for
# having no list at all -- because a module that pins one by accident
# would silently drop tests.
MODULES = [
    "plumb-dfa",
    "plumb-ai",
    "plumb-build/plumb-build-capture",
    "plumb-build/plumb-build-core",
    "plumb-build/plumb-build-maven",
    "plumb-main",
    "plumb-parser",
    "plumb-reporter",
    "plumb-rules",
    "plumb-style",
    "plumb-tac",
]

# A surefire include that is a PATTERN rather than a path. A pattern
# covers every file that matches it, so the files it matches do not
# have to be named individually.
INCLUDE_PATTERNS = {"**/*IT.java", "**/*Test.java", "**/Test*.java", "**/*Tests.java"}

# The tests that are NOT unit tests, and the reason each one is not.
#
# A test that reads an external corpus is an EVALUATION run: it costs
# minutes, and it skips when the corpus is absent. Putting it in the
# unit build would make the build slow on a machine that has the
# corpus and fast on one that does not -- which is the wrong way
# round, because the slow machine is the one that would notice a
# regression.
EVALUATION_TESTS = {
    "plumb-dfa": {
        "org/plumb/dfa/integration/R253JulietProjectRootSweepTest.java":
            "reads the Juliet corpus from D:/work/tmp/Juliet_Java, which is "
            "not in version control",
    },
}

# The number of test files the scan must have checked.
#
# A scan that checks almost nothing finds almost nothing and passes.
# The floor is what makes "the gate stopped looking at a module" a
# failure rather than a quiet reduction in coverage.
MIN_FILES_CHECKED = 200


def surefire_plugin_text(pom_text):
    """The text of the maven-surefire-plugin's configuration, or the whole pom.

    The `<includes>` block has to be read from the SUREFIRE plugin
    specifically. The failsafe plugin has an `<includes>` block too --
    it names the integration tests (`**/*IT.java`) -- and reading that
    one instead would conclude that the unit tests are not allowed to
    run, when surefire is in fact running them with its default
    pattern.
    """
    m = re.search(
        r"<artifactId>maven-surefire-plugin</artifactId>(.*?)</plugin>", pom_text, re.S
    )
    return m.group(1) if m else None


def include_block(pom_text):
    """The text of the surefire `<includes>` block, or None."""
    plugin = surefire_plugin_text(pom_text)
    if plugin is None:
        return None
    m = re.search(r"<includes>(.*?)</includes>", plugin, re.S)
    return m.group(1) if m else None


def includes_of(pom_text):
    """The include entries of the surefire block."""
    block = include_block(pom_text)
    if block is None:
        return None
    return re.findall(r"<include>(.*?)</include>", block)


def failsafe_patterns(pom_text):
    """The include patterns of the maven-failsafe-plugin.

    The failsafe plugin runs the INTEGRATION tests -- the ones named
    `*IT` -- in the `integration-test` phase. So a test named `*IT` is
    run by the build, just by a different plugin. A gate that only
    reads surefire would report those as holes.
    """
    m = re.search(
        r"<artifactId>maven-failsafe-plugin</artifactId>(.*?)</plugin>", pom_text, re.S
    )
    if not m:
        return []
    block = re.search(r"<includes>(.*?)</includes>", m.group(1), re.S)
    if not block:
        return []
    return re.findall(r"<include>(.*?)</include>", block.group(1))


def pattern_matches(pattern, path):
    """Whether a maven-style include pattern matches a path."""
    regex = re.escape(pattern).replace(r"\*\*/", ".*").replace(r"\*", "[^/]*")
    return re.fullmatch(regex, path) is not None


def is_disabled(path):
    """Whether a test file is `@Disabled`.

    The annotation is looked for in the whole file, because the class
    and the methods can each carry it.
    """
    try:
        body = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return "@Disabled" in body


def has_test_methods(path):
    """Whether a file declares any `@Test` method.

    This is what separates a TEST from a HELPER. A support class --
    a mock provider, a harness -- lives in the test tree but carries
    no test method, and surefire has nothing to run in it. Requiring
    every file in the test tree to be named `*Test` would send people
    renaming support classes to satisfy a gate, which is worse than
    the hole the gate closes.
    """
    try:
        body = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return "@Test" in body


def main():
    problems = []
    checked_files = 0

    for module in MODULES:
        module_dir = REPO / module
        pom = module_dir / "pom.xml"
        test_root = module_dir / "src" / "test" / "java"
        if not pom.is_file() or not test_root.is_dir():
            continue

        pom_text = pom.read_text(encoding="utf-8")
        includes = includes_of(pom_text)

        tests = sorted(
            p.relative_to(test_root).as_posix() for p in test_root.rglob("*.java")
        )
        if not tests:
            continue

        if includes is None:
            # No allow list: surefire's default pattern applies, and
            # it runs every *Test class. That is fine -- nothing is
            # being silently dropped -- but the default pattern does
            # NOT match a class named e.g. "R253JulietProjectRootSweep"
            # or "R253ProjectCompilationContextTest" with a package
            # but an unusual suffix. So the default modules are
            # checked for a name surefire would actually pick up.
            it_patterns = failsafe_patterns(pom_text)
            for t in tests:
                if is_disabled(test_root / t):
                    continue
                if not has_test_methods(test_root / t):
                    # A helper class, not a test.
                    continue
                checked_files += 1
                if any(pattern_matches(p, t) for p in it_patterns):
                    # An integration test: run by failsafe, in a different phase.
                    continue
                stem = t.rsplit("/", 1)[-1][:-5]
                if not (stem.endswith("Test") or stem.endswith("Tests")
                        or stem.startswith("Test")):
                    problems.append(
                        "%s: %s has @Test methods but its name is not *Test, so "
                        "surefire's default pattern never runs it"
                        % (module, t)
                    )
            continue

        # The module pins an allow list. A pattern entry covers every
        # file that matches it.
        patterns = [i for i in includes if "*" in i or "?" in i]
        exact = {i for i in includes if "*" not in i and "?" not in i}

        for t in tests:
            if is_disabled(test_root / t):
                continue
            if not has_test_methods(test_root / t):
                # A helper class, not a test.
                continue
            if t in EVALUATION_TESTS.get(module, {}):
                continue
            checked_files += 1
            if t in exact:
                continue
            covered = False
            for pattern in patterns:
                # The pattern is a maven-style path pattern. The only
                # shape in use is "**/*IT.java", so the translation is
                # direct: "**/" matches any directory depth.
                regex = re.escape(pattern).replace(r"\*\*/", ".*").replace(r"\*", "[^/]*")
                if re.fullmatch(regex, t):
                    covered = True
                    break
            if not covered:
                problems.append(
                    "%s: %s exists, is not @Disabled, and is not in the "
                    "surefire allow list -- so the normal build never runs it"
                    % (module, t)
                )

    print("checked %d test file(s)" % checked_files)

    if checked_files < MIN_FILES_CHECKED:
        print("")
        print("FAIL: the scan checked too few files to be meaningful")
        print("  %d file(s) checked, at least %d expected"
              % (checked_files, MIN_FILES_CHECKED))
        print("  A scan that checks almost nothing finds almost nothing and passes.")
        return 1

    if problems:
        print("")
        print("FAIL: %d test file(s) never run" % len(problems))
        for p in problems:
            print("  %s" % p)
        print("")
        print("A test that exists, passes in isolation, and is never executed")
        print("by the build is indistinguishable from a test that does not exist.")
        print("Add it to the module's surefire allow list, or mark it @Disabled")
        print("with a reason.")
        return 1

    print("OK: every test file the build should run is named")
    return 0


if __name__ == "__main__":
    sys.exit(main())
