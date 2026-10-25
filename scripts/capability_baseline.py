"""Measure the engine's capability surface, so a change cannot quietly shrink it.

"Capability" here is the set of things the engine can DETECT. It is the product. A change that
removes a rule, a sink pattern, a source pattern or a checker does not "simplify" the product --
it removes a capability, and the category matrix will not necessarily show it, because the
category fixtures only cover a handful of the rules.

So the surface is measured directly, from the sources that define it:

  * the registered rules, from the generated {@code CheckerCategories} plus the live
    registrations in the AST and DFA checkers;
  * the sink families and their patterns, from {@code sink-families.yaml};
  * the input-source patterns, from {@code InputSourcePatterns};
  * the fact kinds, from the built-in catalogue plus {@code fact-kinds.yaml};
  * the receiver-passthrough families, from {@code receiver-passthrough.yaml}.

The output is a stable, sorted fingerprint per surface. It is written to a baseline file, and
the gate compares the current measurement against that baseline. A REMOVAL is an error; an
ADDITION is reported and accepted (more capability is the goal), but the baseline has to be
updated deliberately.

    python scripts/capability_baseline.py --report      # print the current surface
    python scripts/capability_baseline.py --check       # compare against the baseline
    python scripts/capability_baseline.py --update      # accept the current surface

Exit code 0 when nothing was lost, 1 when something was.
"""

import argparse
import io
import json
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent

BASELINE = REPO / "scripts" / "capability-baseline.json"

SINK_FAMILIES = REPO / "plumb-dfa" / "src" / "main" / "resources" / "sink-families.yaml"
FACT_KINDS = REPO / "plumb-dfa" / "src" / "main" / "resources" / "fact-kinds.yaml"
RECEIVER_PASSTHROUGH = REPO / "plumb-dfa" / "src" / "main" / "resources" / "receiver-passthrough.yaml"
CHECKER_CATEGORIES = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style" / "CheckerCategories.java"
INPUT_SOURCE_PATTERNS = (REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
                          / "taint" / "InputSourcePatterns.java")

# The checkers that actually run. A checker class that exists but is not registered detects
# nothing, so the LIVE registration is what matters -- the class list alone would overstate.
AST_CHECKER_DIR = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style"
DFA_CHECKER_DIR = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa" / "checkers"


def yaml_families(path):
    """The pattern list of every family in a sink/passthrough YAML file.

    <p>Read as text rather than through a YAML library so the measurement does not depend on
    a parser's idea of the file's structure -- the measurement should change only when the
    content changes.

    <p>The file's own top-level key ("families") is a CONTAINER, not a family. It used to be
    picked up as one, which put an empty "families" entry in the surface and made the
    family list look empty to every consumer that read it -- the generated comparison
    document reported "0 / 0" taint sources and sinks because of it.
    """
    if not path.is_file():
        return {}
    text = path.read_text(encoding="utf-8", errors="replace")
    families = {}
    current = None
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        # A family header is an indented key with no value.
        m = re.match(r"^([\w.\-]+):\s*$", stripped)
        if m and not line.startswith("    "):
            name = m.group(1)
            current = name
            families.setdefault(current, [])
            continue
        # A pattern is a list item.
        m = re.match(r"^-\s*[\"'](.+?)[\"']\s*$", stripped)
        if m and current is not None:
            families[current].append(m.group(1))
    # The container key is not a family. It is reported separately, as the list of names.
    names = sorted(k for k in families if k != "families")
    result = {k: sorted(v) for k, v in families.items() if k != "families"}
    result["families"] = names
    return result


def registered_rules():
    """The rule ids the engine registers.

    <p>Two sources, because a rule is registered in one of two ways: the AST checkers declare
    their rule inside the checker class, and the DFA checkers declare theirs the same way. The
    generated category list is the third: it is the catalogue the docs and the AI prompt use.
    """
    rules = set()

    # The generated catalogue: it lists every category's rules.
    if CHECKER_CATEGORIES.is_file():
        text = CHECKER_CATEGORIES.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'"(ast-[a-z0-9\-]+)"', text):
            rules.add(m.group(1))

    # The live registrations: a RuleId built inside a checker.
    for root in (AST_CHECKER_DIR, DFA_CHECKER_DIR):
        if not root.is_dir():
            continue
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="replace")
            # Only CODE lines count. A RuleId mentioned in a comment is not a rule the engine
            # can emit -- the same reason the checker registration scan skips comments.
            code = "\n".join(_code_lines(text))
            for m in re.finditer(r'new RuleId\(\s*"([a-z0-9\-]+)"', code):
                rules.add(m.group(1))
            for m in re.finditer(r'new RuleId\(\s*"([a-z0-9\-]+)"\s*,\s*"([A-Z_]+)"', code):
                rules.add(m.group(2))
    return sorted(rules)


def live_checkers():
    """The checker classes that are actually REGISTERED with the engine.

    <p>A checker class that exists but is never registered detects nothing, so counting the
    files would overstate the capability. The live registration is a
    {@code checkers.add(new org.plumb.dfa.checkers...SomeChecker())} call in the runner, and
    the AST side registers by class through the rule registry.

    <p>Both the registered instances and the checker CLASSES that exist are recorded: the first
    is what runs, the second is what a future registration could use. A class that is deleted
    loses the second even if nobody registered it yet -- and that is the right signal, because
    a checker that is written but never wired up is a capability that was never delivered.

    <p>The registration scan SKIPS COMMENTED-OUT lines. A commented-out
    {@code checkers.add(new XChecker())} registers nothing: the checker does not run. Counting
    it made the baseline claim five checkers that the engine never executed -- and the
    "capability" it was protecting was a comment.
    """
    registered = set()
    runner = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanRunner.java"
    if runner.is_file():
        text = runner.read_text(encoding="utf-8", errors="replace")
        for line in _code_lines(text):
            for m in re.finditer(r"add\(new\s+(?:org\.plumb\.[\w.]+\.)?(\w+)\(\)\)", line):
                registered.add(m.group(1))

    classes = set()
    for root in (AST_CHECKER_DIR, DFA_CHECKER_DIR):
        if not root.is_dir():
            continue
        for path in root.rglob("*.java"):
            if path.name.endswith("Checker.java") or path.name.endswith("Check.java"):
                classes.add(path.stem)
    return sorted(registered), sorted(classes)


def _code_lines(text):
    """The lines of a java file that are CODE, not comments.

    <p>Used by the measurements that must not count a commented-out registration.
    """
    in_block = False
    for line in text.splitlines():
        stripped = line.strip()
        if in_block:
            if "*/" in stripped:
                in_block = False
            continue
        if stripped.startswith("//"):
            continue
        if stripped.endswith("/*") and not stripped.startswith("/*"):
            in_block = True
            continue
        if stripped.startswith("/*"):
            if "*/" not in stripped:
                in_block = True
            continue
        yield line


def input_source_patterns():
    """The input-source substrings, from the shared catalogue."""
    if not INPUT_SOURCE_PATTERNS.is_file():
        return []
    text = INPUT_SOURCE_PATTERNS.read_text(encoding="utf-8", errors="replace")
    start = text.find("DEFAULT_PATTERNS")
    if start < 0:
        return []
    brace = text.find("List.of(", start)
    if brace < 0:
        return []
    depth = 0
    end = None
    i = brace
    in_string = False
    quote = ""
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                in_string = False
        elif ch in ("'", '"'):
            in_string = True
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                end = i
                break
        i += 1
    if end is None:
        return []
    return sorted(re.findall(r'"([^"]*)"', text[brace:end]))


def builtin_fact_kinds():
    """The fact kind names the built-in catalogue registers.

    <p>Read from the constant that holds each name, because the constant IS the registered key:
    {@code ConfigLoader.parseFactKinds} copies every {@link FactKind} out of
    {@code BuiltinFactKinds.registryWithBuiltins()} into the registry under {@code kind.name()},
    and the name is the string constant that the constant declares.

    <p>An earlier version of this measurement looked for {@code public static final FactKind}.
    No such field exists -- the built-ins declare their names as {@code String} constants -- so the
    pattern matched nothing and the 15 built-in kinds were silently absent from the baseline. A
    built-in kind could have been deleted without the gate noticing.
    """
    builtins = (REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa" / "fact"
                / "BuiltinFactKinds.java")
    if not builtins.is_file():
        return []
    text = builtins.read_text(encoding="utf-8", errors="replace")
    # Each kind is declared as a String constant holding the registered key. The registration in
    # buildRegistry() refers to the CONSTANT, not to the literal, so the constant identifier is
    # what has to be followed to the registration.
    constants = []
    for m in re.finditer(r'public static final String\s+(\w+)\s*=\s*"([^"]+)"', text):
        constant, value = m.group(1), m.group(2)
        # The *_TRAITS constants are Sets, not Strings, so they are not picked up here.
        if constant.endswith("_TRAITS"):
            continue
        constants.append((constant, value))

    # Only the kinds that registryWithBuiltins() actually registers count. A constant that is
    # declared but never registered is not a capability.
    registry_body = text[text.find("buildRegistry()"):] if "buildRegistry()" in text else ""
    registered = []
    for constant, value in constants:
        if re.search(r'Map\.entry\(\s*%s\b' % re.escape(constant), registry_body) or \
           re.search(r'new FactKind\(\s*%s\b' % re.escape(constant), registry_body):
            registered.append(value)
    return sorted(set(registered))


def yaml_fact_kinds():
    """The fact kind names the YAML config declares."""
    if not FACT_KINDS.is_file():
        return []
    text = FACT_KINDS.read_text(encoding="utf-8", errors="replace")
    names = []
    in_kinds = False
    for line in text.splitlines():
        if line.startswith("fact_kinds:"):
            in_kinds = True
            continue
        if in_kinds:
            m = re.match(r"^  (\w+):\s*$", line)
            if m:
                names.append(m.group(1))
    return sorted(set(names))


def fact_kinds():
    """The fact kinds, from the built-in catalogue plus the YAML.

    <p>These are two distinct populations that {@code ConfigLoader.parseFactKinds} merges into ONE
    registry: the built-in kinds first, then the YAML entries, which may override a built-in by
    name or add new ones.

    <p>They are measured with distinct prefixes because the namespaces overlap in meaning but not
    in spelling -- the built-ins are {@code dfa-taint-untrusted-source} style, the YAML entries are
    {@code UntrustedSource} style -- and a single flat set would let one mask the other.
    """
    return sorted(
        ["builtin:" + name for name in builtin_fact_kinds()]
        + ["yaml:" + name for name in yaml_fact_kinds()]
    )


def measure():
    """The current capability surface."""
    registered_checkers, checker_classes = live_checkers()
    return {
        "registered_rules": registered_rules(),
        "registered_checkers": registered_checkers,
        "checker_classes": checker_classes,
        "input_source_patterns": input_source_patterns(),
        "fact_kinds": fact_kinds(),
        "sink_families": yaml_families(SINK_FAMILIES),
        "receiver_passthrough_families": yaml_families(RECEIVER_PASSTHROUGH),
    }


def flatten(surface):
    """The surface as a flat set of strings, so a removal is a set difference."""
    out = set()
    for key, value in surface.items():
        if isinstance(value, list):
            for item in value:
                out.add("%s:%s" % (key, item))
        else:
            for family, patterns in value.items():
                for pattern in patterns:
                    out.add("%s:%s:%s" % (key, family, pattern))
                # A family with no patterns still exists; its presence matters.
                out.add("%s-family:%s" % (key, family))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", action="store_true", help="print the current surface")
    parser.add_argument("--check", action="store_true", help="compare against the baseline")
    parser.add_argument("--update", action="store_true", help="accept the current surface")
    args = parser.parse_args()

    surface = measure()

    if args.report:
        print(json.dumps(surface, indent=2, sort_keys=True, ensure_ascii=False))
        return 0

    if args.update:
        BASELINE.write_text(
            json.dumps(surface, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
            encoding="utf-8")
        print("baseline updated: %d rules, %d registered checkers, %d checker classes, "
              "%d source patterns, %d fact kinds, %d sink families, %d passthrough families"
              % (len(surface["registered_rules"]), len(surface["registered_checkers"]),
                 len(surface["checker_classes"]),
                 len(surface["input_source_patterns"]), len(surface["fact_kinds"]),
                 len(surface["sink_families"]), len(surface["receiver_passthrough_families"])))
        return 0

    if not BASELINE.is_file():
        print("FAIL: no capability baseline at %s" % BASELINE.relative_to(REPO))
        print("Create it with: python scripts/capability_baseline.py --update")
        return 1

    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    before = flatten(baseline)
    after = flatten(surface)

    lost = sorted(before - after)
    gained = sorted(after - before)

    for name, value in sorted(surface.items()):
        old = baseline.get(name)
        if isinstance(value, list):
            old_len = len(old) if isinstance(old, list) else 0
            print("  %-32s %d (baseline %d)" % (name, len(value), old_len))
        else:
            old_len = len(old) if isinstance(old, dict) else 0
            print("  %-32s %d families (baseline %d)" % (name, len(value), old_len))

    if gained:
        print()
        print("ADDED since the baseline (%d):" % len(gained))
        for item in gained:
            print("  + %s" % item)

    if lost:
        print()
        print("LOST since the baseline (%d):" % len(lost))
        for item in lost:
            print("  - %s" % item)
        print()
        print("Removing a rule, a sink pattern, a source pattern, a checker or a fact kind is a "
              "CAPABILITY REGRESSION.")
        print("If the removal is deliberate, update the baseline with:")
        print("  python scripts/capability_baseline.py --update")
        return 1

    print()
    print("OK: no capability was lost.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
