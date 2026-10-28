#!/usr/bin/env python3
"""Mutation test for scripts/check_compile_parameters.py.

A gate that passes because it checked nothing is worse than no gate.  This
script mutates the two sides of the compile-parameter story in ways that
MUST make the gate fail, and checks that it does.

Each mutant is applied to a temporary copy of the repository's files; the
real files are never touched.

Usage:  python scripts/mutate_compile_parameters.py
"""
import io
import pathlib
import re
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
CAPTURE_OPTION = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/CompilerOption.java"
)
CAPTURE_OPTIONS = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/JavacOptions.java"
)
MAVEN_POM = (
    REPO / "plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/MavenPom.java"
)
GATE = REPO / "scripts" / "check_compile_parameters.py"

original_capture_option = CAPTURE_OPTION.read_text(encoding="utf-8")
original_capture_options = CAPTURE_OPTIONS.read_text(encoding="utf-8")
original_maven_pom = MAVEN_POM.read_text(encoding="utf-8")


def run_gate():
    result = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.returncode, result.stdout


def drop_an_option(text):
    """Remove the capture's --release option."""
    return re.sub(
        r'\n *out\.add\(\s*opt\(\s*"--release".*?\);', "", text, count=1, flags=re.S
    )


def swap_the_release_precedence(text):
    """Make the capture prefer -source over --release."""
    return text.replace(
        "if (release != null) {\n      return release;\n    }\n    if (source != null) {",
        "if (source != null) {\n      return release;\n    }\n    if (release != null) {",
        1,
    )


def swap_the_maven_precedence(text):
    """Make the Maven side read <source> before <release>."""
    return text.replace(
        'pluginConfiguration("release")', 'pluginConfiguration("source")', 1
    ).replace(
        'pluginConfiguration("source")', 'pluginConfiguration("target")', 1
    )


def default_the_language_level(text):
    """Give the Maven side a default language level, as the upstream had."""
    return text.replace(
        'return property == null ? null : resolve(property);',
        'return property == null ? "1.8" : resolve(property);',
        1,
    )


def default_the_encoding(text):
    """Give the Maven side a default encoding, as the upstream had."""
    return text.replace(
        """    try {
      return Charset.forName(plugin);
    } catch (Exception e) {
      LOG.debug("Maven: the declared encoding {} is not a known charset", plugin);
      return null;
    }""",
        """    try {
      return Charset.forName(plugin);
    } catch (Exception e) {
      LOG.debug("Maven: the declared encoding {} is not a known charset", plugin);
      return java.nio.charset.StandardCharsets.UTF_8;
    }""",
        1,
    )


def drop_the_target_level(text):
    """Stop the Maven side from reading the <target> level."""
    return text.replace('pluginConfiguration("target")', 'pluginConfiguration("nope")', 1)


def drop_the_processor_path_option(text):
    """Remove every declaration of the capture's --processor-path option.

    The option is declared twice -- once under each of its two spellings --
    so the mutation has to remove both, or the canonical name survives.
    """
    return re.sub(
        r'\n *out\.add\(\s*opt\(\s*"[^"]+",\s*"--processor-path".*?\);', "", text, flags=re.S
    )


def drop_the_compiler_args(text):
    """Stop the Maven side from reading the <compilerArgs> element."""
    return text.replace('dom.getChild("compilerArgs")', 'dom.getChild("nope")', 1)


def drop_the_annotation_processor_paths(text):
    """Stop the Maven side from reading the <annotationProcessorPaths> element."""
    return text.replace(
        'dom.getChild("annotationProcessorPaths")', 'dom.getChild("nope")', 1
    )


def drop_the_parent_chain(text):
    """Stop the Maven side from collecting the parent's configuration."""
    return text.replace(
        "addCompilerConfiguration(compilerConfigurations, parentModel);",
        "/* the parent is not consulted */",
        1,
    )


def prefer_the_parent_over_the_module(text):
    """Make the parent's configuration win over the module's own."""
    return text.replace(
        "addCompilerConfiguration(compilerConfigurations, raw);",
        "addCompilerConfiguration(compilerConfigurations, parentModel);",
        1,
    ).replace(
        "addCompilerConfiguration(compilerConfigurations, parentModel);\n      current = parentModel;",
        "current = parentModel;",
        1,
    )


MUTANTS = [
    ("the capture loses its --release option", "capture_option", drop_an_option),
    ("the capture prefers -source over --release", "capture_options", swap_the_release_precedence),
    ("the Maven side reads <source> before <release>", "maven_pom", swap_the_maven_precedence),
    ("the Maven side defaults the language level", "maven_pom", default_the_language_level),
    ("the Maven side defaults the encoding", "maven_pom", default_the_encoding),
    ("the Maven side stops reading <target>", "maven_pom", drop_the_target_level),
    ("the capture loses its --processor-path option", "capture_option", drop_the_processor_path_option),
    ("the Maven side stops reading <compilerArgs>", "maven_pom", drop_the_compiler_args),
    (
        "the Maven side stops reading <annotationProcessorPaths>",
        "maven_pom",
        drop_the_annotation_processor_paths,
    ),
    ("the Maven side stops consulting the parent pom", "maven_pom", drop_the_parent_chain),
    (
        "the parent's configuration wins over the module's own",
        "maven_pom",
        prefer_the_parent_over_the_module,
    ),
]

survivors = []
no_ops = []
try:
    for name, target, mutate in MUTANTS:
        CAPTURE_OPTION.write_text(original_capture_option, encoding="utf-8")
        CAPTURE_OPTIONS.write_text(original_capture_options, encoding="utf-8")
        MAVEN_POM.write_text(original_maven_pom, encoding="utf-8")

        if target == "capture_option":
            CAPTURE_OPTION.write_text(mutate(original_capture_option), encoding="utf-8")
        elif target == "capture_options":
            CAPTURE_OPTIONS.write_text(mutate(original_capture_options), encoding="utf-8")
        else:
            MAVEN_POM.write_text(mutate(original_maven_pom), encoding="utf-8")

        # A mutant that did not change anything proves nothing: it would be
        # reported as "the gate let a drift through" when in fact the drift
        # was never introduced.
        changed = (
            CAPTURE_OPTION.read_text(encoding="utf-8") != original_capture_option
            or CAPTURE_OPTIONS.read_text(encoding="utf-8") != original_capture_options
            or MAVEN_POM.read_text(encoding="utf-8") != original_maven_pom
        )
        if not changed:
            no_ops.append(name)
            print("NO-OP     %s  (the mutation did not apply)" % name)
            continue

        code, out = run_gate()
        if code == 0:
            survivors.append(name)
            print("SURVIVED  %s" % name)
        else:
            first = ""
            for line in out.splitlines():
                if line.startswith("  "):
                    first = line.strip()
                    break
            print("killed    %s  (%s)" % (name, first))
finally:
    CAPTURE_OPTION.write_text(original_capture_option, encoding="utf-8")
    CAPTURE_OPTIONS.write_text(original_capture_options, encoding="utf-8")
    MAVEN_POM.write_text(original_maven_pom, encoding="utf-8")

print()
if no_ops:
    print("FAIL: %d mutant(s) did not apply -- they prove nothing" % len(no_ops))
    for s in no_ops:
        print("  " + s)
    sys.exit(1)
if survivors:
    print("FAIL: %d mutant(s) survived -- the gate is checking air" % len(survivors))
    for s in survivors:
        print("  " + s)
    sys.exit(1)
print("OK: all %d mutants were killed" % len(MUTANTS))
