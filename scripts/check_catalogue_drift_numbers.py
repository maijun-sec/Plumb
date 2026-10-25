#!/usr/bin/env python3
"""Check that the historical drift numbers quoted in the javadocs are RIGHT.

Several javadocs describe the drift that existed before the shared
framework-annotation catalogue landed, and they quote NUMBERS:

    "Thirteen annotations were an HTTP trust boundary to the taint engine
     but invisible to the input-validation checker."

    "The taint engine listed three names no framework defines"

    "Seven real annotations were missing from the taint engine"

Those numbers are MEMORIES, and a memory in a comment is a number that
rots: one of them said "seventeen" while every other site said
"thirteen", and the doc was wrong.

The facts are recoverable, because the pre-catalogue lists are in git
history. This script reads BOTH lists out of the history, computes the
drift, and compares the computed numbers with the numbers the javadocs
quote. So the number in the source is a MEASURED value, and a javadoc
that drifts from it is caught.

The history is the source of truth here, so this gate needs the history.
When it is unavailable (a shallow clone), the gate says so and passes
with a warning rather than failing -- a missing history is a missing
fact, not a defect.

Usage:
    python scripts/check_catalogue_drift_numbers.py
"""
import io
import os
import re
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The commit that introduced the shared catalogue. Its PARENT holds the
# two separate lists.
CATALOGUE_COMMIT = "eb18c11"
BASE = "%s~1" % CATALOGUE_COMMIT

TAINT = "plumb-dfa/src/main/java/org/plumb/dfa/taint/FrameworkEntryPoints.java"
VALIDATION = ("plumb-style/src/main/java/org/plumb/style/security/"
              "MissingInputValidationCheck.java")

# The javadoc sites that quote a drift number, and the number word each
# one must use. The word list is what makes a mismatch visible: a javadoc
# that says "seventeen" is caught even though the sentence is otherwise
# identical.
NUMBER_WORDS = {
    0: "zero",
    1: "one",
    2: "two",
    3: "three",
    4: "four",
    5: "five",
    6: "six",
    7: "seven",
    8: "eight",
    9: "nine",
    10: "ten",
    11: "eleven",
    12: "twelve",
    13: "thirteen",
    14: "fourteen",
    15: "fifteen",
    16: "sixteen",
    17: "seventeen",
    18: "eighteen",
    19: "nineteen",
    20: "twenty",
    21: "twenty-one",
    22: "twenty-two",
    23: "twenty-three",
    24: "twenty-four",
    25: "twenty-five",
    26: "twenty-six",
}

# (file, a phrase unique to the sentence, the measured fact it quotes)
CLAIM_SITES = (
    (TAINT,
     "annotations were an HTTP trust boundary to the taint engine but invisible to the",
     "invisible_to_validation"),
    (VALIDATION,
     "annotations were an HTTP trust",
     "invisible_to_validation"),
    (RULES_CATALOGUE := "plumb-rules/src/main/java/org/plumb/rules/framework/"
     "FrameworkInputAnnotations.java",
     "annotations were an HTTP trust boundary to the taint engine but invisible to the",
     "invisible_to_validation"),
    ("plumb-rules/src/test/java/org/plumb/rules/framework/FrameworkInputAnnotationsTest.java",
     "annotations were an HTTP trust boundary to the taint engine but invisible to",
     "invisible_to_validation"),
    ("plumb-dfa/src/test/java/org/plumb/dfa/taint/FrameworkAnnotationAgreementTest.java",
     "annotations were an HTTP trust boundary to the taint engine but invisible to the",
     "invisible_to_validation"),
    ("plumb-style/src/test/java/org/plumb/style/security/MissingInputValidationFrameworkTest.java",
     "annotations were an HTTP trust boundary to the taint engine but invisible to",
     "invisible_to_validation"),
    ("plumb-dfa/src/main/java/org/plumb/dfa/taint/FrameworkEntryPoints.java",
     "annotations were an HTTP trust boundary to the taint engine but invisible to the",
     "invisible_to_validation"),
)


def git_show(rev, path):
    proc = subprocess.run(
        ["git", "show", "%s:%s" % (rev, path)],
        cwd=REPO, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if proc.returncode != 0:
        return None
    return proc.stdout


def extract_set(text, field):
    """The names in the Set.of(...) initializer of the named field."""
    idx = text.index(field)
    eq = text.index("=", idx)
    semi = text.index(";", eq)
    initializer = text[eq:semi]
    if "Set.of(" not in initializer:
        return None
    start = text.index("Set.of(", eq)
    depth = 0
    for i in range(text.index("(", start), len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                end = i
                break
    body = re.sub(r"//[^\n]*", "", text[start:end])
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    return re.findall(r'"([^"]+)"', body)


def normalize(text):
    """Collapse the source into one whitespace-free stream per line-group.

    The javadoc is reflowed to 100 columns, so a sentence is split across
    lines with a leading ' * ' on each. The number word and the phrase it
    introduces are often on DIFFERENT lines. Matching the raw text would
    therefore miss most of the real sites -- and a gate that misses the
    sites it is meant to check is worse than no gate, because it reports
    green.
    """
    stripped = re.sub(r"^\s*\*\s?", "", text, flags=re.M)
    return re.sub(r"\s+", " ", stripped)


def main():
    taint_text = git_show(BASE, TAINT)
    validation_text = git_show(BASE, VALIDATION)
    if taint_text is None or validation_text is None:
        print("SKIPPED: the pre-catalogue history is not available in this clone.")
        print("         The drift numbers cannot be measured without it.")
        return 0

    taint = set(extract_set(taint_text, "USER_INPUT_PARAM_ANNOTATIONS") or [])
    validation = set(extract_set(validation_text, "USER_INPUT_PARAM_ANNOS") or [])
    if not taint or not validation:
        print("SKIPPED: the pre-catalogue lists could not be read.")
        return 0

    measured = {
        "invisible_to_validation": len(taint - validation),
        "invisible_to_taint": len(validation - taint),
        "taint_total": len(taint),
        "validation_total": len(validation),
    }

    print("measured from %s:" % BASE)
    print("  the taint engine had       : %d annotations" % measured["taint_total"])
    print("  the validation checker had : %d annotations" % measured["validation_total"])
    print("  invisible to the validation checker: %d"
          % measured["invisible_to_validation"])
    print("  invisible to the taint engine     : %d" % measured["invisible_to_taint"])
    print()

    problems = []
    for path, phrase, fact in CLAIM_SITES:
        with open(os.path.join(REPO, path), "r", encoding="utf-8") as fh:
            text = normalize(fh.read())
        if phrase not in text:
            continue
        expected = measured[fact]
        word = NUMBER_WORDS.get(expected)
        if word is None:
            problems.append("%s quotes a number the gate cannot spell" % path)
            continue
        # The number word precedes the phrase. On the NORMALIZED text the
        # whole sentence is one line, so this matches even when the source
        # wrapped it across three lines.
        pattern = re.compile(
            r"(\w+[-\w]*)\s+" + re.escape(phrase), re.IGNORECASE)
        found = [m.group(1) for m in pattern.finditer(text)]
        if not found:
            problems.append("%s: could not locate the number before %r" % (path, phrase))
            continue
        for candidate in found:
            if candidate.lower() != word:
                problems.append(
                    "%s says %r but the measured value is %d (%s)"
                    % (path, candidate, expected, word))
    if problems:
        print("FAILED: a quoted drift number does not match the history.")
        for p in problems:
            print("  - %s" % p)
        return 1
    print("OK: every quoted drift number matches the history.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
