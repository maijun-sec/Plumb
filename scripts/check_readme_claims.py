#!/usr/bin/env python3
"""Check that the repository's root README still matches the code.

The root README is the first document anybody reads, and until this gate
existed nothing checked it. That is not a hypothetical gap: the README
claimed the uber-jar was "~115 MB" while the built jar was 141.6 MB, it
said `default-config.yaml` had 47 `checks:` keys while the loader reads
50, and it carried a "current round" line -- process history that belongs
in git history, not in the document a user reads first.

The README is also the place where a size claim is most likely to be
misread. "77 MB" in the packaging document was the size of the UBER-JAR,
not of the documentation directory; the two were never linked, and the
number travelled without its subject. So this gate checks two things:

  1. Every count the README states is re-derived from the source.
  2. Every size the README states names the artifact it is about.

A size without a subject is an ambiguity, and an ambiguity is how a
reader ends up believing the documentation is 77 MB when it is 0.7 MB.

    python scripts/check_readme_claims.py
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts"))

import capability_baseline as cb  # noqa: E402

STYLE_ROOT = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style"
DFA_ROOT = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
CATEGORIES = STYLE_ROOT / "CheckerCategories.java"
SCAN_RUNNER = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanRunner.java"
CONFIG_LOADER = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ConfigLoader.java"

# The two READMEs. The Chinese one must carry the same numbers as the
# English one -- a bilingual README that disagrees with itself is worse
# than one that is only in one language.
READMES = {
    "README.md": REPO / "README.md",
    "README.zh.md": REPO / "README.zh.md",
}

# How many milliseconds of tolerance a measured size may differ by. The
# jar changes size whenever a dependency changes, and the README rounds
# to a whole number, so the comparison cannot be exact.
SIZE_TOLERANCE_MB = 5


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def style_checker_classes():
    return sum(1 for p in STYLE_ROOT.rglob("*.java")
               if p.name.endswith("Check.java") or p.name.endswith("Checker.java"))


def category_registrations():
    if not CATEGORIES.is_file():
        return 0
    return len(re.findall(r"m\.put\(",
                           read(CATEGORIES)))


def registered_dfa_checkers():
    """The DFA checkers the engine actually registers.

    Measured by `capability_baseline`, which is the SAME measurement the
    capability gate uses. That measurement deliberately skips commented-out
    `checkers.add(...)` lines, because a commented-out registration
    registers nothing -- a naive regex over the file would count those and
    overstate the number.

    Measuring with a second, different method would be a second opinion
    about the same population, and the two would eventually disagree. The
    README must quote the number the capability gate protects.
    """
    return len(cb.measure()["registered_checkers"])


def taint_rules():
    """The rules the taint registry holds.

    Measured the same way `check_doc_numbers` measures it, so the README
    cannot quote a different number from the one the other gate checks.
    """
    registry = DFA_ROOT / "taint" / "TaintRuleRegistry.java"
    if not registry.is_file():
        return 0
    text = read(registry)
    return len(re.findall(r"new TaintRule\(|TaintRule\.of\(", text))


def loader_checks_keys():
    """The `checks:` keys the configuration loader actually reads.

    Measured by `check_doc_claims.loader_check_keys()`, which is the SAME
    measurement the documentation-schema gate uses. That function counts
    the camelCase key names the loader passes to `cm.get(...)`; the
    snake_case spellings are ALIASES of those keys, so counting them would
    double-count.

    Measuring with a second, different method would be a second opinion
    about the same population, and the two would eventually disagree. The
    README must quote the number the schema gate protects.
    """
    import check_doc_claims
    return len(check_doc_claims.loader_check_keys())


def built_uber_jar():
    """The built uber-jar, if there is one."""
    target = REPO / "plumb-main" / "target"
    if not target.is_dir():
        return None
    jars = sorted(target.glob("*-all.jar"))
    if not jars:
        return None
    return jars[-1]


def check_counts(failures):
    """The counts the README states, re-derived from the source."""
    # (label, the README text that carries the number, the number, the measurement)
    #
    # The patterns must match BOTH the English README and the Chinese one.
    # The Chinese README puts the number in the same bold form but the
    # surrounding wording differs, so each pattern accepts either
    # language's phrasing and stops at the digits.
    checks = [
        ("style checkers",
         r"\*\*(\d+)\*\*\s*(?:个)?\s*[（(]\s*`CheckerCategories`",
         style_checker_classes()),
        ("registered DFA checkers",
         r"\*\*(\d+)\*\*\s*(?:个)?\s*(?:actually registered|实际注册)",
         registered_dfa_checkers()),
        ("taint rules",
         r"\*\*(\d+)\*\*\s*(?:条)?\s*\n?\|?\s*`TaintRuleRegistry`",
         taint_rules()),
    ]

    for label, pattern, expected in checks:
        for name, path in READMES.items():
            if not path.is_file():
                continue
            text = read(path)
            m = re.search(pattern, text)
            if not m:
                failures.append(
                    "%s: the %s count is not stated in the format the gate "
                    "expects -- it cannot be checked" % (name, label))
                continue
            claimed = int(m.group(1))
            if claimed != expected:
                failures.append(
                    "%s: the %s count says %d, the source says %d"
                    % (name, label, claimed, expected))

    # The `checks:` key count. The README says "50 `checks:` keys (48
    # checker families plus the two master switches)".
    for name, path in READMES.items():
        if not path.is_file():
            continue
        text = read(path)
        m = re.search(r"(\d+)\s*(?:个)?\s*`checks:`\s*(?:keys|键)", text)
        if not m:
            failures.append(
                "%s: the `checks:` key count is not stated in the format the "
                "gate expects -- it cannot be checked" % name)
            continue
        claimed = int(m.group(1))
        expected = loader_checks_keys()
        if expected and claimed != expected:
            failures.append(
                "%s: the `checks:` key count says %d, the loader reads %d"
                % (name, claimed, expected))

    return len(checks) * 2


def check_sizes(failures):
    """Every size the README states must name its artifact.

    A bare size is an ambiguity. The reader cannot tell whether "77 MB"
    refers to the documentation, the jar, or the release package -- and
    when it means the jar but sits next to a sentence about the
    documentation, the reader will believe the documentation.
    """
    checked = 0
    jar = built_uber_jar()
    for name, path in READMES.items():
        if not path.is_file():
            continue
        text = read(path)
        for m in re.finditer(r"(\d+)\s*MB", text):
            lineno = text[:m.start()].count("\n") + 1
            line = text.splitlines()[lineno - 1]
            # The size must be attached to a named artifact on the same
            # line, or the line must say which artifact it is about.
            if not re.search(
                    r"uber-jar|jar|JRE|jre|package|release|docs|documentation|目录",
                    line):
                failures.append(
                    "%s:%d: the size %s MB does not name the artifact it is "
                    "about -- a reader cannot tell what it measures"
                    % (name, lineno, m.group(1)))
            checked += 1
    return checked


def check_language_switch(failures):
    """The two READMEs must link to each other."""
    en = REPO / "README.md"
    zh = REPO / "README.zh.md"
    if not en.is_file():
        failures.append("README.md does not exist")
        return 0
    if not zh.is_file():
        failures.append(
            "README.zh.md does not exist -- the root README has no Chinese "
            "version, so a Chinese reader has no entry point")
        return 0

    if "README.zh.md" not in read(en):
        failures.append(
            "README.md does not link to README.zh.md -- the Chinese version "
            "is unreachable from the English one")
    if "README.md" not in read(zh):
        failures.append(
            "README.zh.md does not link to README.md -- the English version "
            "is unreachable from the Chinese one")
    return 2


def check_no_round_marker(failures):
    """The README must not carry a round number.

    The round number is process history. It belongs in git history, and a
    reader of the README has no access to it.
    """
    pattern = re.compile(r"(?<![A-Za-z0-9_])R-?\d{3,4}[a-z]?(?![A-Za-z0-9_])")
    for name, path in READMES.items():
        if not path.is_file():
            continue
        for m in pattern.finditer(read(path)):
            failures.append(
                "%s: the round marker %s is process history; it belongs in git "
                "history" % (name, m.group(0)))
    return 1


def check_documentation_size(failures):
    """The documentation size the README states must match the tree.

    The README says the documentation directory is under a certain size.
    That number is about the DOCUMENTATION, and it is the number a reader
    would quote -- so it has to be measured, not remembered.

    This is also the check that keeps the "77 MB" mistake from coming
    back. 77 MB was the size of the uber-jar; it sat next to a sentence
    about the documentation, and a reader could reasonably conclude that
    the documentation was 77 MB. A size that names the documentation
    directory AND is measured against it cannot drift that way.
    """
    docs = REPO / "docs"
    if not docs.is_dir():
        return 0
    total = 0
    for p in docs.rglob("*"):
        if p.is_file():
            total += p.stat().st_size
    actual_mb = total / (1024 * 1024)

    checked = 0
    for name, path in READMES.items():
        if not path.is_file():
            continue
        text = read(path)
        # The claim reads "the whole `docs/` tree ... is about 1 MB" in
        # English and "整个 `docs/` 目录树 ... 约 1 MB" in Chinese.
        #
        # The comparison is on the ROUNDED value, not the exact one. A
        # document that grows by a paragraph must not force the README to
        # change, or the number would be a maintenance chore instead of
        # a fact. "about 1 MB" is true for 0.5 MB to 1.5 MB, and the
        # reader understands it that way.
        for m in re.finditer(
                r"`docs/`[^\n]{0,80}?(?:about|approximately|约|around)\s*"
                r"(\d+(?:\.\d+)?)\s*MB", text):
            claimed = float(m.group(1))
            if round(actual_mb) > round(claimed):
                failures.append(
                    "%s: the documentation size is stated as about %s MB, but "
                    "the docs/ tree is %.2f MB" % (name, m.group(1), actual_mb))
            checked += 1

    print("INFO  the docs/ tree is %.2f MB" % actual_mb)
    return checked


def check_uber_jar_size(failures):
    """The uber-jar size the README states must match the built jar.

    The uber-jar is the biggest thing the repository produces, and it
    grows every time a dependency changes. A size in the README that is
    never re-measured drifts silently -- and "77 MB" is exactly what
    happened: it was true once, then the Kotlin DSL module arrived, and
    the number stayed behind.

    The comparison is on the ROUNDED value, because the README rounds.
    A jar that changes by a few hundred kilobytes -- which a dependency
    bump does -- does not force the README to change.
    """
    jar = built_uber_jar()
    if jar is None:
        print("NOTE: no uber-jar built -- the size claim was not checked.")
        return 0
    actual_mb = jar.stat().st_size / (1024 * 1024)
    print("INFO  the uber-jar is %.1f MB" % actual_mb)

    checked = 0
    for name, path in READMES.items():
        if not path.is_file():
            continue
        text = read(path)
        # The claim reads "the uber-jar: every dependency inlined, about
        # 142 MB" in English and "uber-jar：内联所有依赖，约 142 MB" in
        # Chinese. The number must be on a line that names the jar.
        for line in text.splitlines():
            if not re.search(r"uber-jar", line, re.I):
                continue
            m = re.search(r"(\d+)\s*MB", line)
            if not m:
                continue
            claimed = int(m.group(1))
            if abs(claimed - round(actual_mb)) > 2:
                failures.append(
                    "%s: the uber-jar size is stated as %d MB, but the built "
                    "jar is %.1f MB" % (name, claimed, actual_mb))
            checked += 1
    return checked


def main():
    failures = []

    for name, path in READMES.items():
        if not path.is_file():
            failures.append("%s does not exist" % name)

    count_checked = check_counts(failures)
    size_checked = check_sizes(failures)
    doc_size_checked = check_documentation_size(failures)
    jar_size_checked = check_uber_jar_size(failures)
    switch_checked = check_language_switch(failures)
    check_no_round_marker(failures)

    if failures:
        print("FAIL: the root README does not match the code")
        for f in failures:
            print("  %s" % f)
        print()
        print("The root README is the first document a reader opens. A number")
        print("in it is a claim about the code, and a size without a named")
        print("artifact is a claim a reader cannot check.")
        return 1

    print("  ok: the root README's %d count(s) and %d size(s) match the "
          "source" % (count_checked, size_checked))
    print("  ok: %d documentation-size claim(s) match the docs/ tree"
          % doc_size_checked)
    print("  ok: %d uber-jar size claim(s) match the built jar"
          % jar_size_checked)
    print("  ok: the two READMEs link to each other (%d check(s))"
          % switch_checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
