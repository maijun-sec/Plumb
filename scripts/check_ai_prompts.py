#!/usr/bin/env python3
"""Check that the false-positive prompts still carry their method.

A prompt is data, and data drifts silently. Nothing about the compiler
complains when the analysis method loses a question -- the prompt still
compiles, the agent still runs, and the findings still come back. Only
the FP rate moves, and it moves in the direction nobody is watching.

So the prompt's LOAD-BEARING parts are pinned here:

  1. The three prompts exist under names that state their policy, and
     the deprecated aliases still resolve to the SAME text (an alias that
     pointed at a stale copy would be a second source of truth).
  2. Each prompt carries the five analysis questions. A prompt that
     drops a question has changed its decision procedure, and that is
     exactly the kind of change that should not happen by accident.
  3. The questions cover the five things a reviewer must establish:
     the source really introduces tainted data, the sink really causes
     the harm, the trace is really reachable, the conditions on the path
     can be satisfied, and a sanitiser is really on the path.
  4. The two policies disagree in the direction they are supposed to:
     the active one defaults to FP, the strict one defaults to TP.
  5. The runner uses the ACTIVE prompt -- not a deprecated alias, and
     not the strict one.

    python scripts/check_ai_prompts.py
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[1]

PROMPTS_JAVA = REPO / "plumb-ai" / "src" / "main" / "java" / "org" / "plumb" / "ai" / "prompts" / "FalsePositivePrompts.java"
AGENT_PROMPTS_JAVA = REPO / "plumb-ai" / "src" / "main" / "java" / "org" / "plumb" / "ai" / "prompts" / "AgentPrompts.java"
RUNNER_JAVA = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanRunner.java"

# The three named prompts, and what each one decides.
#
# The name is the policy. That is the whole point of the rename: a
# reader who knows the policy can predict the behaviour without
# reading the text, and a test can assert the behaviour from the name.
NAMED_PROMPTS = {
    "FALSE_POSITIVE_REDUCTION": {
        "default": "FP",
        "forbidden_default": "TP",
    },
    "FALSE_POSITIVE_REDUCTION_STRICT": {
        "default": "TP",
        "forbidden_default": "FP",
    },
    "FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES": {
        "default": "FP",
        "forbidden_default": "TP",
    },
}

# The deprecated aliases, and the named prompt each one must resolve to.
#
# The mapping preserves each alias's ORIGINAL meaning. Renaming a
# constant must not silently change what it means -- a caller that
# relied on the old default would get a different verdict.
DEPRECATED_ALIASES = {
    "FP_REDUCER": "FALSE_POSITIVE_REDUCTION",
    "FP_REDUCER_V10": "FALSE_POSITIVE_REDUCTION",
    "FP_REDUCER_V11": "FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES",
}

# The five analysis questions, and the phrase each one is introduced by.
#
# Each phrase is a MARKER in the prompt text. The marker is what proves
# the question is still there: a prompt that dropped the reachability
# question would still compile, still run, and still answer -- just
# worse.
ANALYSIS_QUESTIONS = [
    ("1", "DOES THE SOURCE REALLY INTRODUCE"),
    ("2", "DOES THE SINK REALLY CAUSE"),
    ("3", "IS THE TRACE ACTUALLY REACHABLE"),
    ("4", "CAN THE CONDITIONS ON THE PATH BE SATISFIED"),
    ("5", "IS THERE AN EFFECTIVE SANITISER ON THE PATH"),
]

# The hop that is most often wrong: Java passes by value, so a value
# handed to a method is a copy and the callee may discard it.
REACHABILITY_MARKERS = [
    "CALL-BY-VALUE SEMANTICS",
    "discards it",
]

# The implicit guarantees the third prompt adds.
IMPLICIT_GUARANTY_MARKERS = [
    "DOCUMENTED NON-NULL CONTRACT",
    "FRESHLY CONSTRUCTED OBJECT",
    "UTILITY-CLASS / STATIC FACTORY",
    "CONSTRUCTOR INVARIANT",
]


def _string_fields(text):
    """The `static final String` fields of a Java source, with their bodies.

    The body of a field runs until the `;` that ENDS the statement -- and
    a `;` inside a string literal does not end it. A naive
    ``= (.*?);`` stops at the first `;` it finds, which in a prompt that
    contains sentences like "is NOT a sanitiser; a helper called ..." is
    the one in the middle of the text.

    So the body is scanned with the string literals skipped: a `;` that
    is not inside a literal ends the field.
    """
    fields = {}
    decl = re.compile(
        r"(?:public|private|protected)?\s*static\s+final\s+String\s+(\w+)\s*=\s*")
    for m in decl.finditer(text):
        i = m.end()
        depth = 0
        in_string = False
        escaped = False
        while i < len(text):
            ch = text[i]
            if in_string:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == '"':
                    in_string = False
            else:
                if ch == '"':
                    in_string = True
                elif ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                elif ch == ";" and depth == 0:
                    break
            i += 1
        fields[m.group(1)] = text[m.end():i]
    return fields


def java_string_literals(text):
    """The value of every `static final String` in a Java source.

    A Java field is written as a chain of `"..." + "..."` pieces, so the
    text of the constant is the concatenation of its pieces. The
    concatenation is what the gate checks -- a marker split across two
    pieces is still present in the value.

    A field may also REFERENCE another constant:

        public static final String FP_REDUCER = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION;

    or MIX literals and references:

        public static final String P = "prefix " + ANALYSIS_METHOD + " suffix";

    so the value is resolved. Without that, a prompt built by referencing
    the shared method would read as EMPTY, and the gate would report
    every question missing -- a gate that reports the truth as broken is
    worse than no gate.

    The Java escape sequences are decoded, because a marker in the
    source may be written across a `\\n` and the check is on the VALUE
    the model will see.
    """
    fields = _string_fields(text)

    def decode(s):
        return (s.replace("\\\\n", "\n").replace("\\\\t", "\t")
                 .replace('\\\\"', '"').replace("\\\\\\\\", "\\\\"))

    def resolve(name, seen):
        if name in seen:
            return ""
        seen = seen | {name}
        body = fields.get(name)
        if body is None:
            return ""
        # The pieces of the expression, in order: a literal or a
        # reference. Splitting on '+' is enough here because the
        # expressions are plain concatenations of literals and names.
        out = []
        for part in re.split(r"\+", body):
            part = part.strip()
            lit = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', part, re.S)
            if lit:
                out.append(decode(lit.group(1)))
                continue
            ref = re.fullmatch(r"(?:[A-Za-z_]\w*\.)?(\w+)", part)
            if ref:
                out.append(resolve(ref.group(1), seen))
        return "".join(out)

    return {name: resolve(name, set()) for name in fields}


def fail(failures, msg):
    failures.append(msg)


def main():
    failures = []

    if not PROMPTS_JAVA.is_file():
        print("FAIL: %s does not exist" % PROMPTS_JAVA)
        return 1
    if not AGENT_PROMPTS_JAVA.is_file():
        print("FAIL: %s does not exist" % AGENT_PROMPTS_JAVA)
        return 1

    prompts_text = PROMPTS_JAVA.read_text(encoding="utf-8", errors="replace")
    prompts = java_string_literals(prompts_text)
    agent_text = AGENT_PROMPTS_JAVA.read_text(encoding="utf-8", errors="replace")
    agent = java_string_literals(agent_text)

    # (1) Every named prompt exists and carries the five questions.
    for name, policy in NAMED_PROMPTS.items():
        if name not in prompts:
            fail(failures, "%s does not exist" % name)
            continue
        body = prompts[name]
        for number, marker in ANALYSIS_QUESTIONS:
            if marker not in body:
                fail(failures,
                     "%s does not carry analysis question %s (%s)"
                     % (name, number, marker))

    # (2) The reachability hop is spelled out.
    active = prompts.get("FALSE_POSITIVE_REDUCTION", "")
    for marker in REACHABILITY_MARKERS:
        if marker not in active:
            fail(failures,
                 "FALSE_POSITIVE_REDUCTION does not explain the reachability "
                 "hop (%s)" % marker)

    # (3) The third prompt carries the implicit guarantees.
    with_guarantees = prompts.get(
        "FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES", "")
    for marker in IMPLICIT_GUARANTY_MARKERS:
        if marker not in with_guarantees:
            fail(failures,
                 "FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES does not "
                 "carry %s" % marker)

    # (4) The two policies disagree in the direction they must.
    #
    # The check is on the DEFAULT the prompt states, not on the absence
    # of the other word: the strict prompt mentions "Default to FP" in
    # its own analysis method only if the method is quoted wrongly, so
    # the phrasing matters.
    for name, policy in NAMED_PROMPTS.items():
        body = prompts.get(name, "")
        if not body:
            continue
        wanted = "default to %s" % policy["default"].lower()
        forbidden = "default to %s" % policy["forbidden_default"].lower()
        if wanted not in body.lower():
            fail(failures,
                 "%s does not say '%s' -- the policy its name states is not the "
                 "policy it implements" % (name, wanted))
        if forbidden in body.lower():
            fail(failures,
                 "%s also says '%s' -- the two policies are not distinguished"
                 % (name, forbidden))

    # (5) The deprecated aliases resolve to the named prompts.
    #
    # The alias must be a REFERENCE to the named prompt, not a COPY of
    # its text. A copy would be a second source of truth: the named
    # prompt could be edited and the alias would keep the old text,
    # and the two would silently diverge.
    alias_bodies = _string_fields(agent_text)

    for alias, target in DEPRECATED_ALIASES.items():
        if alias not in alias_bodies:
            fail(failures, "%s does not exist" % alias)
            continue
        body = alias_bodies[alias]
        if not re.search(r"(?:[A-Za-z_]\w*\.)?%s\s*$" % re.escape(target), body):
            fail(failures,
                 "%s does not resolve to %s -- a deprecated alias that held a "
                 "COPY of the prompt text would be a second source of truth"
                 % (alias, target))

    # (6) The runner uses the ACTIVE prompt.
    if not RUNNER_JAVA.is_file():
        fail(failures, "%s does not exist" % RUNNER_JAVA)
    else:
        runner = RUNNER_JAVA.read_text(encoding="utf-8", errors="replace")
        if "FalsePositivePrompts.FALSE_POSITIVE_REDUCTION" not in runner:
            fail(failures,
                 "the runner does not use "
                 "FalsePositivePrompts.FALSE_POSITIVE_REDUCTION")
        # A deprecated alias in the runner would keep the old text alive
        # and would fail loudly on the next removal of the alias.
        if re.search(r"AgentPrompts\.FP_REDUCER", runner):
            fail(failures,
                 "the runner uses the deprecated AgentPrompts.FP_REDUCER "
                 "alias instead of the named prompt")

    # (7) The three prompts are genuinely different texts.
    bodies = [prompts.get(n, "") for n in NAMED_PROMPTS]
    if len(set(bodies)) != len(bodies):
        fail(failures,
             "two of the named prompts have the same text -- the names would "
             "be decoration")

    print("checked %d named prompt(s), %d deprecated alias(es), %d analysis "
          "question(s)" % (len(NAMED_PROMPTS), len(DEPRECATED_ALIASES),
                           len(ANALYSIS_QUESTIONS)))

    if failures:
        print()
        print("FAIL: %d defect(s) in the false-positive prompts" % len(failures))
        for f in failures[:40]:
            print("  %s" % f)
        if len(failures) > 40:
            print("  ... and %d more" % (len(failures) - 40))
        print()
        print("A prompt that loses a question still compiles, still runs, and")
        print("still answers -- just worse. Only the false-positive rate")
        print("moves, and it moves where nobody is watching.")
        return 1

    print("OK: the false-positive prompts carry their method")
    return 0


if __name__ == "__main__":
    sys.exit(main())
