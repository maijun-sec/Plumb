#!/usr/bin/env python3
"""Check that every layer agrees with the framework binding-annotation catalogue.

The catalogue in scripts/framework_input_annotations.py is the single
definition of "a parameter bound to a request value". The taint engine
and the input-validation checker both read it.

This script verifies that they really do, by reading each layer's
DECLARED set and comparing it with the catalogue.

It exists because a JUnit test cannot catch the interesting failure. A
layer that keeps its own narrower list still passes every test that
touches that layer: the catalogue test checks the catalogue, and the
layer's own test checks the layer. Only a comparison ACROSS the layers
sees the drift. The mutation battery demonstrated this: reverting the
input-validation checker to its old thirteen-annotation list left every
JUnit test green.

Usage:
    python scripts/check_framework_annotations.py
"""
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from framework_input_annotations import (  # noqa: E402
    ANNOTATIONS,
    CONTAINER_ANNOTATIONS,
    ENDPOINT_ANNOTATIONS,
)

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

LAYERS = (
    (
        "DFA taint engine",
        os.path.join(REPO, "plumb-dfa", "src", "main", "java", "org", "plumb", "dfa",
                     "taint", "FrameworkEntryPoints.java"),
        "USER_INPUT_PARAM_ANNOTATIONS",
    ),
    (
        "AST input validation",
        os.path.join(REPO, "plumb-style", "src", "main", "java", "org", "plumb", "style",
                     "security", "MissingInputValidationCheck.java"),
        "USER_INPUT_PARAM_ANNOS",
    ),
)

# Layers that consume the CONTAINER classification. A container annotation
# binds a bean of parameters rather than a value, so the taint engine has to
# tell "this parameter is a carrier" from "this parameter is a source". A
# layer that keeps its own container list can drift exactly like the binding
# list did, so it is checked the same way.
CONTAINER_LAYERS = (
    (
        "DFA field bindings",
        os.path.join(REPO, "plumb-dfa", "src", "main", "java", "org", "plumb", "dfa",
                     "taint", "FrameworkFieldBindings.java"),
        "isContainerAnnotation",
    ),
)


def declared_set(path, marker):
    """The set a layer declares, or the catalogue when it references it.

    A layer may either declare its own Set.of(...) -- which is the drift
    this check exists to catch -- or reference the shared catalogue. The
    field's own INITIALIZER is read, not the first Set.of that happens
    to follow the field name: a class often has several Set constants,
    and picking the wrong one compares the validation-annotation set
    against the binding-annotation catalogue.
    """
    with open(path, "r", encoding="utf-8") as fh:
        text = fh.read()
    idx = text.index(marker)
    eq = text.index("=", idx)
    semi = text.index(";", eq)
    initializer = text[eq:semi]

    if "Set.of(" not in initializer:
        return set(ANNOTATIONS), False

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
    return set(re.findall(r'"([^"]+)"', body)), True


def main():
    catalogue = set(ANNOTATIONS)
    problems = []

    print("catalogue: %d binding annotations across %d frameworks" % (
        len(catalogue), len({f for f, _ in ANNOTATIONS.values()})))
    print()

    for name, path, marker in LAYERS:
        declared, own_copy = declared_set(path, marker)
        print("=== %s ===" % name)
        if own_copy:
            print("  declares its OWN set of %d" % len(declared))
        else:
            print("  uses the shared catalogue (%d)" % len(catalogue))

        extra = sorted(declared - catalogue)
        missing = sorted(catalogue - declared)
        if extra:
            problems.append("%s declares %d name(s) no framework defines: %s"
                            % (name, len(extra), ", ".join(extra)))
            print("  NOT IN THE CATALOGUE (%d): %s" % (len(extra), ", ".join(extra)))
        if missing:
            problems.append("%s is missing %d catalogue annotation(s): %s"
                            % (name, len(missing), ", ".join(missing)))
            print("  MISSING (%d): %s" % (len(missing), ", ".join(missing)))
        if not extra and not missing:
            print("  agrees with the catalogue")

        # A layer that holds a COPY of the catalogue can drift the moment
        # somebody adds an annotation. The fix was "one source of truth",
        # so the check has to notice a layer that stopped using it.
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        for copy_pattern in ("new java.util.LinkedHashSet<>(", "new LinkedHashSet<>(",
                             "Set.copyOf("):
            if copy_pattern in text:
                problems.append("%s holds a COPY of the catalogue -- a copy can drift again"
                                % name)
                print("  HOLDS A COPY of the catalogue -- a copy can drift again")
                break
        print()

    print("endpoint annotations: %d" % len(ENDPOINT_ANNOTATIONS))
    print()

    # --- the CONTAINER classification -------------------------------
    #
    # A container annotation binds a BEAN of parameters, not a value. The
    # layers that consume it must read the shared catalogue rather than
    # keep their own list, for the same reason the binding list is shared:
    # a copy can drift.
    containers = set(CONTAINER_ANNOTATIONS)
    print("container annotations: %d" % len(containers))
    for name, path, marker in CONTAINER_LAYERS:
        print("=== %s ===" % name)
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        if marker not in text:
            problems.append("%s never consults the shared container catalogue (%s)"
                            % (name, marker))
            print("  NEVER consults the shared container catalogue")
        else:
            print("  consults the shared container catalogue")
        for copy_pattern in ("new java.util.LinkedHashSet<>(", "new LinkedHashSet<>(",
                             "Set.copyOf("):
            if copy_pattern in text:
                problems.append("%s holds a COPY of the container catalogue -- a copy can drift"
                                % name)
                print("  HOLDS A COPY of the container catalogue")
                break
    # Every container annotation must also be a binding annotation: a bean
    # parameter is a request-supplied object, so it binds request values.
    not_binding = sorted(containers - set(ANNOTATIONS))
    if not_binding:
        problems.append("container annotation(s) that are not binding annotations: %s"
                        % ", ".join(not_binding))
        print("  NOT BINDING ANNOTATIONS: %s" % ", ".join(not_binding))
    print()

    if problems:
        print("FAILED: the layers have drifted from the catalogue.")
        for p in problems:
            print("  - " + p)
        return 1
    print("OK: every layer agrees with the verified catalogue.")
    return 0


if __name__ == "__main__":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.exit(main())
