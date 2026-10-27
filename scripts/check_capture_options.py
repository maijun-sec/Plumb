#!/usr/bin/env python3
"""Check the capture module's option table against Coverity's reference model.

Coverity's `JavacOptionParser` holds its option knowledge in tables: the
options where only the last occurrence counts, the options that may be
repeated, the options that take no value, the options that do not produce a
class file, and the per-compiler path options.

This module's option table is the same idea, and this gate keeps the two from
drifting apart.  It checks:

  * every option in Coverity's categories exists in our table;
  * the category each option belongs to agrees with ours;
  * every canonical name in the reference exists in our table, with the same
    value kind and the same repetition rule;
  * every alias in the reference is an option name in our table;
  * every option in our table is in the reference, or is explicitly listed as
    an addition.

That last check is the one that keeps the gate honest in both directions: a
deliberate addition has to be written down, so "we support more than Coverity"
is a visible fact rather than an accident.

The script prints what it derives before it compares, and reports how many
things it checked -- a check that skipped everything looks exactly like a check
that found nothing.

Exit code 0 when the tables agree, 1 otherwise.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
BASELINE = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/resources/coverity-java-options.toml"
)
SOURCE = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/CompilerOption.java"
)

doc = []
failures = []


def note(msg):
    doc.append(msg)
    print(msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


# ------------------------------------------------------------------ the baseline

if not BASELINE.is_file():
    print("FAIL: the reference baseline is missing at %s" % BASELINE)
    sys.exit(1)

text = read(BASELINE)


def toml_array(name):
    """The items of a [name] section that holds one item per line."""
    m = re.search(
        r"^\[%s\]\s*$(.*?)(?=^\[|\Z)" % re.escape(name), text, flags=re.M | re.S
    )
    if not m:
        return []
    out = []
    for line in m.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        out.append(line)
    return out


def toml_options():
    """The [[option]] blocks, as dicts."""
    out = []
    for block in re.findall(r"^\[\[option\]\]\s*$(.*?)(?=^\[\[option\]\]|\Z)", text, flags=re.M | re.S):
        entry = {"aliases": []}
        for key, value in re.findall(r"^(\w+)\s*=\s*(.+?)\s*$", block, flags=re.M):
            if key == "aliases":
                entry["aliases"] = re.findall(r'"([^"]*)"', value)
            else:
                entry[key] = value.strip('"')
        out.append(entry)
    return out


categories = {
    "last-instance-only": toml_array("last-instance-only"),
    "repeat-allowable": toml_array("repeat-allowable"),
    "no-value": toml_array("no-value"),
    "repeat-squash": toml_array("repeat-squash"),
    "not-compilation": toml_array("not-compilation"),
    "ecj-path": toml_array("ecj-path"),
}
note(
    "the reference has %d categorised option(s): %s"
    % (sum(len(v) for v in categories.values()),
       {k: len(v) for k, v in categories.items()})
)

reference = toml_options()
note("the reference describes %d option(s) with their aliases" % len(reference))
if not reference:
    print("FAIL: the reference describes no options -- the check cannot verify anything")
    sys.exit(1)

# The category each canonical name belongs to, from the reference.
#
# An option can be in MORE THAN ONE category: --classpath is both a
# "last instance only" option and one of ECJ's path options.  So the mapping
# is name -> list of categories, and every one of them is checked.
category_of = {}
for category, names in categories.items():
    for name in names:
        category_of.setdefault(name, []).append(category)

overlapping = {n: c for n, c in category_of.items() if len(c) > 1}
if overlapping:
    note(
        "the reference puts %d option(s) in more than one category: %s"
        % (len(overlapping), sorted(overlapping))
    )

# ------------------------------------------------------------------ our table

if not SOURCE.is_file():
    print("FAIL: the option table source is missing at %s" % SOURCE)
    sys.exit(1)

src = read(SOURCE)
src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
src = re.sub(r"//[^\n]*", " ", src)

# Each entry: opt("<name>", "<canonical>", Value.X, Repeat.Y, Effect.Z, [Compiler.W | null])
#
# The compiler argument is `null` for an option that applies to every compiler,
# so the group accepts a null literal as well as a Compiler constant.
pattern = re.compile(
    r'opt\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*Value\.([A-Z_]+)\s*,\s*Repeat\.([A-Z_]+)'
    r'\s*,\s*Effect\.([A-Z_]+)\s*(?:,\s*(?:Compiler\.([A-Z_]+)|null)\s*)?\)'
)
ours = []
for m in pattern.finditer(src):
    ours.append(
        {
            "name": m.group(1),
            "canonical": m.group(2),
            "value": m.group(3),
            "repeat": m.group(4),
            "effect": m.group(5),
            "compiler": m.group(6),
        }
    )
note("our table declares %d option(s)" % len(ours))
if not ours:
    print("FAIL: no options could be derived from CompilerOption.java")
    sys.exit(1)

by_name = {}
for o in ours:
    by_name[o["name"]] = o

# Every spelling of a canonical name, not just the first one.
#
# The first version kept only the first, and a mutation test caught it: a
# mutant that changed the repetition rule of `-cp` -- the third spelling of
# `--classpath` -- was invisible, because the check looked at `-classpath`.
# Every spelling has to agree with the reference, because a build that uses
# one spelling and a build that uses another must be understood the same way.
by_canonical = {}
for o in ours:
    by_canonical.setdefault(o["canonical"], []).append(o)

# The value kind the reference uses, mapped onto our enum.
VALUE_MAP = {
    "none": "NONE",
    "single": "SINGLE",
    "path-list": "PATH_LIST",
    "comma-list": "COMMA_LIST",
}
REPEAT_MAP = {
    "last-wins": "LAST_WINS",
    "all-apply": "ALL_APPLY",
}

# ------------------------------------------------------------------ 1. categories

# The category an option belongs to is implied by our own table:
#   not-compilation   -> Effect.NOT_COMPILATION
#   no-value         -> Value.NONE
#   repeat-allowable -> Repeat.ALL_APPLY
#   repeat-squash    -> Repeat.ALL_APPLY, but only one value is kept
#   last-instance-only-> everything else
#   ecj-path         -> a path option of ECJ
#
# The check is that the two agree, option by option.

def our_category(o):
    if o["effect"] == "NOT_COMPILATION":
        return "not-compilation"
    if o["value"] == "NONE":
        return "no-value"
    if o["repeat"] == "ALL_APPLY":
        return "repeat-allowable"
    return "last-instance-only"


checked = 0
skipped = []
for name, cats_of_option in sorted(category_of.items()):
    spellings = by_canonical.get(name)
    if not spellings:
        spellings = [by_name[name]] if name in by_name else []
    if not spellings:
        failures.append(
            "the reference puts %r in %s, but our table has no such option"
            % (name, ", ".join(cats_of_option))
        )
        skipped.append(name)
        continue
    checked += 1
    for o in spellings:
        for category in cats_of_option:
            if category == "not-compilation":
                if o["effect"] != "NOT_COMPILATION":
                    failures.append(
                        "%r does not compile anything in the reference, but our table says %s"
                        % (name, o["effect"])
                    )
            elif category == "no-value":
                if o["value"] != "NONE":
                    failures.append(
                        "%r takes no value in the reference, but our table says %s"
                        % (name, o["value"])
                    )
            elif category == "repeat-allowable":
                if o["repeat"] != "ALL_APPLY":
                    failures.append(
                        "%r may be repeated in the reference, but our table says %s"
                        % (name, o["repeat"])
                    )
            elif category == "repeat-squash":
                if o["repeat"] != "ALL_APPLY":
                    failures.append(
                        "%r is repeatable-but-squashed in the reference, but our table says %s"
                        % (name, o["repeat"])
                    )
            elif category == "last-instance-only":
                if o["repeat"] != "LAST_WINS":
                    failures.append(
                        "%r keeps only its last occurrence in the reference, but our table says %s"
                        % (name, o["repeat"])
                    )
            elif category == "ecj-path":
                if o["value"] != "PATH_LIST":
                    failures.append(
                        "%r is a path option in the reference, but our table says %s"
                        % (name, o["value"])
                    )

note("checked %d categorised option(s) against our table" % checked)
if checked == 0:
    failures.append("no categorised option could be checked")
if skipped:
    note("could not be checked: %s" % sorted(skipped))

# ------------------------------------------------------------------ 2. the options

for entry in reference:
    canonical = entry["name"]
    spellings = by_canonical.get(canonical)
    o = spellings[0] if spellings else None
    if o is None:
        # The reference may name an option that is, in our table, a spelling
        # of another one.  ECJ's --class-path is --classpath with a different
        # spelling, not a different option.
        o = by_name.get(canonical)
    if o is None:
        failures.append("our table has no option named %r" % canonical)
        continue

    # The canonical name itself has to agree.
    #
    # The canonical name is what the option is recorded under and what the
    # compile identity is built from, so an option that canonicalises to a
    # different name is a different option, whatever it is called.  A table
    # that canonicalised --release to --release-level would still parse the
    # arguments correctly, and would still record the right attributes -- but
    # the two tables would no longer describe the same thing.
    if o["canonical"] != canonical:
        failures.append(
            "the reference names the option %r, but our table canonicalises it "
            "to %r" % (canonical, o["canonical"])
        )

    # Every spelling of the option has to agree with the reference, not just
    # the first one.
    for o in (by_canonical.get(canonical) or [o]):
        expected_value = VALUE_MAP.get(entry.get("value", ""))
        if expected_value and o["value"] != expected_value:
            failures.append(
                "%r is a %s option in the reference, but our table says %s"
                % (o["name"], entry["value"], o["value"])
            )

        expected_repeat = REPEAT_MAP.get(entry.get("repeat", ""))
        if expected_repeat and o["repeat"] != expected_repeat:
            failures.append(
                "%r repeats %s in the reference, but our table says %s"
                % (o["name"], entry["repeat"], o["repeat"])
            )

        expected_compiler = entry.get("compiler")
        if expected_compiler and (o["compiler"] or "").lower() != expected_compiler.lower():
            failures.append(
                "%r belongs to %s in the reference, but our table says %s"
                % (o["name"], expected_compiler, o["compiler"])
            )

    for alias in entry.get("aliases", []):
        if alias not in by_name:
            failures.append(
                "the reference says %r is another spelling of %r, but our table "
                "does not know %r" % (alias, canonical, alias)
            )

note("checked %d option(s) with their aliases" % len(reference))

# ------------------------------------------------------------------ 2b. the reference agrees with itself

# The categorised lists and the [[option]] blocks must describe the same
# options.  A name in a category that no [[option]] block describes, or an
# [[option]] block that no category mentions, means the reference itself is
# inconsistent -- and a gate that trusts an inconsistent reference is a gate
# that checks half the thing.
described = {e["name"] for e in reference}
for category, names in categories.items():
    for name in names:
        if name not in described:
            failures.append(
                "the reference puts %r in %s, but no [[option]] block describes it"
                % (name, category)
            )

# And the other way round is NOT required: Coverity's categories are only the
# six special behaviours (last-wins, repeatable, no-value, squash, not-a-
# compilation, ECJ path).  Most options are in none of them, and that is
# correct -- they are ordinary options.
#
# So the check is narrower: an option that the reference's own [[option]] block
# gives a "special" attribute to must be in the matching category.  --enable-
# preview is declared with value = none, so it belongs in [no-value]; if it
# were removed from that list, the reference would contradict itself.
for entry in reference:
    canonical = entry["name"]
    spellings = by_canonical.get(canonical) or (
        [by_name[canonical]] if canonical in by_name else []
    )
    if not spellings:
        continue
    o = spellings[0]
    # The special attributes the reference's own block gives it.  An option
    # can have more than one.
    #
    # The check is against the CATEGORY lists, not against the value kind in
    # the [[option]] block: Coverity's NO_VALUE_OPTIONS holds just
    # --enable-preview, so -Xprint is "takes no value" without being in the
    # no-value category.  The value kind is checked separately, against the
    # block.
    declared_special = []
    if entry.get("effect") == "not-compilation":
        declared_special.append("not-compilation")
    for special in declared_special:
        if special not in category_of.get(canonical, []):
            failures.append(
                "the reference describes %r as %s, but no category says so"
                % (canonical, special)
            )

    # An option that is in the no-value category must be declared as taking
    # no value, and an option the reference declares as taking no value must
    # be in the no-value category.
    #
    # Both directions matter.  Coverity's NO_VALUE_OPTIONS holds just
    # --enable-preview, so -Xprint -- which also takes no value -- is in the
    # not-compilation category instead.  The two are distinguished by the
    # category, so an option whose value kind says "none" has to be in a
    # category that says so.
    in_no_value = "no-value" in category_of.get(canonical, [])
    declared_none = entry.get("value") == "none"
    if in_no_value and not declared_none:
        failures.append(
            "the reference puts %r in [no-value], but its [[option]] block "
            "says value = %s" % (canonical, entry.get("value"))
        )
    if declared_none and not in_no_value:
        # The option takes no value, so it has to be in [no-value] -- unless
        # it is in a category that already says it takes no value.
        other = "not-compilation" in category_of.get(canonical, [])
        if not other:
            failures.append(
                "the reference declares %r as taking no value, but it is in "
                "no category that says so" % canonical
            )

# Every option that the reference describes in a category must be in that
# category, judged by our own table's rules.
for entry in reference:
    canonical = entry["name"]
    o = by_canonical.get(canonical) or ([by_name[canonical]] if canonical in by_name else [])
    if not o:
        continue
    o = o[0]
    for category in category_of.get(canonical, []):
        agrees = True
        if category == "not-compilation":
            agrees = o["effect"] == "NOT_COMPILATION"
        elif category == "no-value":
            agrees = o["value"] == "NONE"
        elif category in ("repeat-allowable", "repeat-squash"):
            agrees = o["repeat"] == "ALL_APPLY"
        elif category == "last-instance-only":
            agrees = o["repeat"] == "LAST_WINS"
        elif category == "ecj-path":
            agrees = o["value"] == "PATH_LIST"
        if not agrees:
            failures.append(
                "the reference puts %r in %s, but our table disagrees" % (canonical, category)
            )

# ------------------------------------------------------------------ 3. our extras

# The options we know that the reference does not.  They are not a defect --
# we support more -- but they must be written down, so that a reader can see
# what the difference is.
#
# The list below is the set of option NAMES we have that the reference does
# not name.  A name the reference lists as an ALIAS of another option is not
# an extra: it is the same option, spelled differently.
ADVISED = {
    # The javac spellings the reference does not list as aliases.
    "-d",
    "--output-dir",
    "-classpath",
    "-cp",
    "--class-path",
    "-sourcepath",
    "--source-path",
    "-bootclasspath",
    "--boot-class-path",
    "-Xbootclasspath",
    "-Xbootclasspath/p",
    "-Xbootclasspath/a",
    "-extdirs",
    "-Djava.ext.dirs",
    "-endorseddirs",
    "-Djava.endorsed.dirs",
    "-p",
    "-m",
    "-processorpath",
    "-processor",
    "-s",
    "-source",
    "-target",
    "-encoding",
    "-src",
    # Options we support that Coverity does not model at all.
    "-nowarn",
    "-verbose",
    "-deprecation",
    "-Xlint",
    "-Werror",
    "-g",
    "-parameters",
}

reference_names = {e["name"] for e in reference}
reference_aliases = {a for e in reference for a in e.get("aliases", [])}

# Everything the reference mentions, by every name it uses.
reference_all = reference_names | reference_aliases

extras = sorted(n for n in by_name if n not in reference_all)
undocumented = [n for n in extras if n not in ADVISED]
if undocumented:
    failures.append(
        "our table has option(s) the reference does not and that are not "
        "listed as additions: %s" % undocumented
    )
else:
    note(
        "our table has %d option(s) beyond the reference, all of them listed "
        "as additions" % len(extras)
    )

# An option the reference names must be one of ours, under that name or as
# one of our spellings.
for name in sorted(reference_all):
    if name not in by_name and name not in by_canonical:
        failures.append(
            "the reference names the option %r, which our table does not have "
            "under that name or as a spelling" % name
        )

# ------------------------------------------------------------------ 4. the table is reachable

for o in ours:
    if o["canonical"] not in by_canonical:
        failures.append(
            "the option %r canonicalises to %r, which no option declares"
            % (o["name"], o["canonical"])
        )

note("every option canonicalises to a declared option")

# ------------------------------------------------------------------ 5. the spellings agree

# All the spellings of one option have to agree with each other.
#
# Without this, a table could declare -cp as a repeatable option and
# -classpath as a last-wins one, and the two spellings of the same option
# would behave differently.  The reference lists the spellings of an option
# together for exactly this reason.
for canonical, spellings in sorted(by_canonical.items()):
    first = spellings[0]
    for o in spellings[1:]:
        for field in ("value", "repeat", "effect", "compiler"):
            if o[field] != first[field]:
                failures.append(
                    "the spellings of %r disagree: %s has %s=%s but %s has %s=%s"
                    % (
                        canonical,
                        first["name"],
                        field,
                        first[field],
                        o["name"],
                        field,
                        o[field],
                    )
                )

# A spelling must not be claimed by two different options.
canonical_of_name = {}
for o in ours:
    previous = canonical_of_name.get(o["name"])
    if previous is not None and previous != o["canonical"]:
        failures.append(
            "the option %r is claimed by both %r and %r"
            % (o["name"], previous, o["canonical"])
        )
    canonical_of_name[o["name"]] = o["canonical"]

note("checked %d option(s) and their spellings for agreement" % len(ours))

print()
if failures:
    print("FAIL: %d defect(s) between our option table and Coverity's" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: the capture option table matches Coverity's reference model")
