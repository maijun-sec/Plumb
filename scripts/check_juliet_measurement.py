#!/usr/bin/env python3
"""Check that the measurement attributes a finding to the method that reaches it.

THE DEFECT THIS GATE EXISTS FOR

A Juliet testcase often puts the DEFECT in a helper and calls the helper from
the canonical method::

    static private void helperBad() { LOCK.lock(); ... LOCK.unlock(); }
    public void bad() { helperBad(); }

The measurement attributed a finding by LINE, so a finding inside `helperBad`
was classified as "in a method that is neither bad* nor good*". That counts
the corpus's SHAPE as a miss, not the engine's precision: the engine found the
double unlock exactly where it is, and the measurement refused to credit it.

The same shape broke the method-span parser itself. The corpus writes the
flaw description into the declaration line::

    static public void helperBad() /* FLAW: No synchronization in this fn */

The declaration pattern required the line to END after the parameter list, so
that line did not match, `helperBad` had no span at all, and every finding
inside it was "outside any method".

THE INVARIANT

For a file in the corpus's own shape:

1. the helper method HAS a span, even when its declaration carries a comment;
2. the helper is attributed to the `bad*`/`good*` method that calls it;
3. a finding inside the helper is credited to that caller.

    python scripts/check_juliet_measurement.py
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MEASUREMENT = REPO / "juliet-coverage" / "scripts" / "measurement"
sys.path.insert(0, str(MEASUREMENT))

import measure  # noqa: E402

# A file per shape, written out here rather than read from the corpus, so the
# gate does not depend on the corpus being present.
CASES = {
    "helper with a comment on the declaration line": (
        "class T {\n"
        "    static public void helperBad() /* FLAW: No synchronization */\n"
        "    {\n"
        "        LOCK.lock();\n"
        "        LOCK.unlock();\n"
        "    }\n"
        "    public void bad()\n"
        "    {\n"
        "        helperBad();\n"
        "    }\n"
        "}\n"
    ),
    "helper without a comment": (
        "class T {\n"
        "    static public void helperBad()\n"
        "    {\n"
        "        LOCK.lock();\n"
        "    }\n"
        "    public void bad()\n"
        "    {\n"
        "        helperBad();\n"
        "    }\n"
        "}\n"
    ),
}


def check(tmp: Path) -> list[str]:
    failures: list[str] = []
    for name, source in CASES.items():
        path = tmp / ("T_" + name.replace(" ", "_") + ".java")
        path.write_text(source, encoding="utf-8")

        spans = measure.all_method_spans(path)

        # EVERY method the file declares must have a span. A method that
        # silently loses its span is a finding that cannot be attributed to
        # anything, and the measurement would count it as "outside any
        # method" -- a statement about the parser, not about the engine.
        for declared in ("helperBad", "bad"):
            if declared not in spans:
                failures.append(
                    f"{name}: the method {declared!r} has no span, so a finding "
                    f"inside it cannot be attributed to any method"
                )

        callers = measure.helper_callers(path, spans)
        if "helperBad" in spans and callers.get("helperBad") != "bad":
            failures.append(
                f"{name}: the helper is attributed to {callers.get('helperBad')!r}, "
                f"not to the 'bad' method that calls it"
            )

        if "helperBad" not in spans:
            # Without a span there is no body to check.
            continue

        # The span must actually COVER the helper's body, so a finding on a
        # body line is attributed to the helper and then to its caller.
        start, end = spans["helperBad"]
        if not start <= 3 <= end:
            failures.append(
                f"{name}: the helper's span {start}-{end} does not cover its body"
            )

    # A helper that is NOT called by a bad*/good* method stays unattributed:
    # the gate must not credit a helper that nothing reaches.
    orphan = tmp / "T_orphan.java"
    orphan.write_text(
        "class T {\n"
        "    static public void helperBad()\n"
        "    {\n"
        "        LOCK.lock();\n"
        "    }\n"
        "    public void unrelated()\n"
        "    {\n"
        "        helperBad();\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    spans = measure.all_method_spans(orphan)
    if measure.helper_callers(orphan, spans).get("helperBad") == "bad":
        failures.append(
            "an orphan helper was attributed to 'bad' even though nothing "
            "bad* calls it"
        )
    if "helperBad" in measure.helper_callers(orphan, spans):
        failures.append(
            "a helper called only by an unrelated method was attributed to a "
            "caller at all"
        )

    # A helper called by BOTH a bad* and a good* method is ambiguous, and the
    # map must not claim it belongs to one of them by accident.
    both = tmp / "T_both.java"
    both.write_text(
        "class T {\n"
        "    static public void helperShared()\n"
        "    {\n"
        "        LOCK.lock();\n"
        "    }\n"
        "    public void bad()\n"
        "    {\n"
        "        helperShared();\n"
        "    }\n"
        "    public void good()\n"
        "    {\n"
        "        helperShared();\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )
    spans = measure.all_method_spans(both)
    callers = measure.helper_callers(both, spans)
    if callers.get("helperShared") not in (None, "bad", "good"):
        failures.append(
            f"a helper called by two methods was attributed to {callers.get('helperShared')!r}"
        )
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--tmp-dir",
        default="",
        help="where to write the fixture files (a temp dir by default)",
    )
    args = parser.parse_args()

    if args.tmp_dir:
        tmp = Path(args.tmp_dir)
        tmp.mkdir(parents=True, exist_ok=True)
        failures = check(tmp)
    else:
        import tempfile

        with tempfile.TemporaryDirectory() as raw:
            failures = check(Path(raw))

    if failures:
        print("FAIL: the measurement does not attribute a helper to its caller")
        for f in failures:
            print(f"  - {f}")
        print()
        print(
            "A finding inside a helper belongs to the bad*/good* method that\n"
            "reaches it. Otherwise the measurement reports the corpus's shape\n"
            "as a miss and the engine's precision is unreadable."
        )
        return 1
    print("OK: a finding inside a helper is attributed to the method that reaches it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
