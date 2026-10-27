#!/usr/bin/env python3
"""Check that the compile cross-check reports differences instead of resolving them.

The two sides of the compile-parameter story answer the same questions from
two directions:

  * the pom says what the build DECLARED;
  * the capture says what the compiler ACTUALLY did.

They are used together, so a disagreement means one of them is wrong -- and
the analysis is wrong in a way nobody can see, because the two answers are
used in different places.

The cross-check therefore has to REPORT the difference.  The failure mode
this gate guards against is a cross-check that quietly picks one side: the
disagreement disappears, and the analysis is confidently wrong.

The gate checks the three things that make a report a report:

  * the comparison exists, and it covers the parameters that can differ;
  * a difference keeps BOTH values, so the reader can see what each side
    said;
  * an unknown value is not a difference -- an undeclared parameter is
    unknown, not a default, so there is nothing to disagree about.

Exit code 0 when the cross-check behaves, 1 otherwise.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent

CROSS_CHECK = (
    REPO
    / "plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/CompileCrossCheck.java"
)
DISCREPANCY = (
    REPO / "plumb-build/plumb-build-core/src/main/java/org/plumb/build/CompileDiscrepancy.java"
)
ADAPTER = (
    REPO
    / "plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/MavenResolverAdapter.java"
)

failures = []
info = []


def note(msg):
    info.append(msg)
    print(msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


for path in (CROSS_CHECK, DISCREPANCY, ADAPTER):
    if not path.is_file():
        print("FAIL: %s is missing" % path)
        sys.exit(1)

cross_check = read(CROSS_CHECK)
discrepancy = read(DISCREPANCY)
adapter = read(ADAPTER)

# ------------------------------------------------------------------
# 1. the parameters that can differ
# ------------------------------------------------------------------
# The capture and the pom both answer these questions.  A parameter that
# only one side answers is a gap, not a difference -- but a parameter that
# BOTH sides answer, and that can differ, has to be compared.
PARAMETERS = [
    ("languageLevel", "the language level"),
    ("encoding", "the source encoding"),
    ("outputDirectory", "the output directory"),
    ("classpath", "the classpath"),
    ("sourcePath", "the source path"),
    ("sourceFiles", "the source files"),
]

for name, description in PARAMETERS:
    # The check is on the COMPARISON, not on the name.  The name appears in
    # the constant, in the comment and in the JSON field, so a gate that
    # looked for the name would pass a cross-check that never compared the
    # parameter -- which is exactly the failure this gate exists to catch.
    #
    # The constant has to be an ARGUMENT of a comparison call.
    constant = {
        "languageLevel": "LANGUAGE_LEVEL",
        "encoding": "ENCODING",
        "outputDirectory": "OUTPUT_DIRECTORY",
        "classpath": "CLASSPATH",
        "sourcePath": "SOURCE_PATH",
        "sourceFiles": "SOURCE_FILES",
    }[name]
    if not re.search(r"compare\w*\(\s*\n?\s*byParameter,\s*\n?\s*" + constant, cross_check):
        failures.append(
            "the cross-check does not compare %s (%s): the two sides answer it "
            "and it can differ" % (name, description)
        )
    else:
        note("the cross-check compares %s" % name)

# ------------------------------------------------------------------
# 2. a difference keeps BOTH values
# ------------------------------------------------------------------
# A report that keeps one value has already resolved the difference, and
# the reader cannot tell which side it is looking at.
if "record CompileDiscrepancy" not in discrepancy:
    failures.append(
        "the discrepancy is not a record: it has to keep both values, or the "
        "reader cannot see what each side said"
    )
else:
    for field in ("declared", "observed"):
        if field not in discrepancy:
            failures.append(
                "the discrepancy does not keep the %s value: a report that keeps "
                "one value has already resolved the difference" % field
            )
    if "describe()" not in discrepancy:
        failures.append(
            "the discrepancy has no description: the report has to say which "
            "side said what"
        )
    else:
        note("the discrepancy keeps both values and describes them")

# The cross-check has to hand BOTH values to the discrepancy.
# The check is on the CONSTRUCTOR CALL: a call that passes the declared
# value twice has already resolved the difference, and a call that passes it
# once has lost the observed value.
discrepancy_calls = re.findall(
    r"new\s+CompileDiscrepancy\s*\(([^;]*?)\)", cross_check, flags=re.S
)
if not discrepancy_calls:
    failures.append(
        "the cross-check never builds a discrepancy: it compares and throws "
        "the result away"
    )
else:
    for call in discrepancy_calls:
        args = call.split(",")
        # The parameter, the declared value, the observed value and the
        # detail.  A call that passes the same expression twice has
        # resolved the difference instead of reporting it.
        if len(args) < 4:
            failures.append(
                "the cross-check builds a discrepancy without both values: a "
                "report that keeps one value has already resolved the difference"
            )
            break
        if args[1].strip() == args[2].strip():
            failures.append(
                "the cross-check passes the same value as both the declared and "
                "the observed one: the difference has been resolved instead of "
                "reported"
            )
            break
    else:
        note("the discrepancy is built with both values")

# ------------------------------------------------------------------
# 3. an unknown value is not a difference
# ------------------------------------------------------------------
# An undeclared parameter is unknown, not a default.  So a side that says
# nothing is not a contradiction: reporting it would be noise, and it would
# drown the real differences.
# The check is on the GUARD: the comparison has to return early when one
# side has no value.
if not re.search(
    r"if\s*\(declared\s*==\s*null\s*\|\|\s*observed\s*==\s*null\)\s*\{\s*return;",
    cross_check,
):
    failures.append(
        "the cross-check does not skip the comparison when one side has no "
        "value: an undeclared parameter is unknown, not a default, so it is "
        "not a difference"
    )
else:
    note("an unknown value on either side is not reported as a difference")

# ------------------------------------------------------------------
# 4. the result is deterministic
# ------------------------------------------------------------------
# Two runs over the same inputs must produce the same report, in the same
# order.  A report whose order depends on a hash map's iteration order is a
# report nobody can diff.
#
# The check is on the MAP the result is collected in: it has to be sorted
# by its keys, not merely to be a Map.  A LinkedHashMap keeps the INSERTION
# order, which is the order the differences were found in -- and that order
# depends on the order of the captures and the modules.
sorted_map = re.search(
    r"TreeMap\s*<\s*String\s*,\s*CompileDiscrepancy\s*>\s+(\w+)", cross_check
)
if not sorted_map:
    failures.append(
        "the cross-check does not collect its differences in a sorted map: two "
        "runs over the same inputs would produce different reports"
    )
else:
    name = sorted_map.group(1)
    # And the map has to be the one the result comes from.
    if not re.search(r"new\s+ArrayList\s*<>\s*\(\s*" + name + r"\.values\s*\(\s*\)\s*\)", cross_check):
        failures.append(
            "the cross-check's sorted map is not the one the result comes from: "
            "the report's order is still decided by something else"
        )
    else:
        note("the differences are sorted")

# The adapter has to sort too -- it compares every capture against every
# module, so the order of the inputs matters.
#
# The check is on the CALL, not on the name: a sort that is guarded off, or
# that sorts nothing, is not a sort.
sort_call = re.search(r"(if\s*\(\s*false\s*\)\s*)?out\.sort\s*\(([^;]*)\);", adapter, flags=re.S)
if not sort_call or sort_call.group(1):
    failures.append(
        "the adapter does not sort the cross-check's result: the order of the "
        "captures and the modules would decide the order of the report"
    )
elif not re.search(r"comparing|sort", sort_call.group(2)):
    failures.append(
        "the adapter's sort does not order by anything: the result's order "
        "would still be the order it was found in"
    )
else:
    note("the adapter sorts the cross-check's result")

# ------------------------------------------------------------------
# 5. the cross-check is reachable
# ------------------------------------------------------------------
# A comparison nobody can call is a comparison nobody benefits from.
if "crossCheck(" not in adapter:
    failures.append(
        "the adapter has no crossCheck method: the comparison exists but "
        "nothing can reach it"
    )
elif "crossCheck(" not in read(
    REPO / "plumb-main/src/main/java/org/plumb/main/SastMain.java"
):
    failures.append(
        "the CLI never calls the cross-check: the differences are computed and "
        "then thrown away"
    )
else:
    note("the CLI runs the cross-check")

print()
if failures:
    print("FAIL: %d defect(s) in the compile cross-check" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: the cross-check reports the differences instead of resolving them")
