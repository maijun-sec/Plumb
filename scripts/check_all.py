#!/usr/bin/env python3
"""Run every repository gate and report a single verdict.

The gates existed one at a time, and each one had to be remembered
separately. That is how a stale generated file, a drifted javadoc
number, or a cross-layer disagreement reached the repository in the
first place: a gate nobody ran is not a gate.

This script runs all of them and prints one PASS/FAIL summary. It is
the command to run before every commit.

    python scripts/check_all.py

The gates are:
    format --check            every source file is google-java-format clean
    gen_categories --check    the generated checker registry is current
    gen_framework_annotations --check
                              the generated annotation catalogue is current
    check_framework_annotations
                              the two layers agree with the catalogue
    check_catalogue_drift_numbers
                              the drift numbers quoted in javadocs match
                              the history they describe
    check_dataflow_constraint
                              the manifest's dataflow constraint is not a
                              no-op
    check_support_matrix_coverage
                              the support matrix covers every measured
                              category, and every row matches its
                              measurement
    check_javadoc_links        every javadoc @link target names a type
                              that exists
    check_doc_index            the documentation index lists every
                              document, and every link in it resolves
    check_doc_hygiene          the product documentation describes the
                              CURRENT state: no round numbers, no
                              past-state wording, no unreproducible
                              measurement presented as fact -- in the
                              markdown, in the source comments, and in
                              the shipped configuration files
    check_doc_numbers          the counts quoted in the hand-written
                              documentation still match the source
    check_doc_claims           the documentation's structural claims still
                              match the source: the YAML schema shows every
                              key the loader reads, the CLI flags exist,
                              the section numbers are unique, and the
                              quoted counts are right
    check_tac_claims           the IR specification's structural claims
                              still match the source: the class names, the
                              fields, the enum constants and the counts
                              the reader would code against
    check_cross_fn_claims      the cross-function operation document's
                              named symbols still exist: the classes, the
                              Operation variants, the return shapes
    check_environment_claims   the environment document still matches the
                              code: the variables that are read, the ones
                              that are not, and the line citations
    check_cache_claims         the cache document still matches the code:
                              the file format constants, the cache modes
                              and their behaviour, the source-map contract
    check_style_rules_claims   the style-rules document still matches the
                              code: the interface methods, the dispatcher
                              API, the context API, the visit count
    check_capture_claims       the capture section of the architecture
                              document still matches the code: the hooked
                              compiler entry points, both ways, the class
                              list, and no raw ASM
    mutate_capture_claims      the capture gate is not checking air: every
                              deliberate drift must make it fail
    check_capture_options      the capture module's option table still
                              matches Coverity's reference model: the
                              categorised options, their value kinds, their
                              repetition rules, their spellings
    mutate_capture_options     the option gate is not checking air: every
                              deliberate drift must make it fail
    check_i18n_docs            the English and Chinese documentation have
                              the same shape: the same documents, the same
                              headings, the same tables, the same code
                              blocks, working links, and a working
                              language switch on every page
    mutate_i18n_docs           the i18n gate is not checking air: every
                              deliberate drift must make it fail
    check_readme_claims        the root README still matches the code: the
                              counts it quotes, the sizes it quotes, and
                              whether a size names the artifact it is about
    check_section_refs         every cross-document section reference
                              points at a section that exists
    mutate_section_refs        the section-reference gate is not checking
                              air: every deliberate drift must make it fail
    check_package_claims       the packaging scripts copy files that exist,
                              and the packaging documentation's sizes and
                              log level match the build
    capability_baseline --check
                              no rule, sink pattern, source pattern,
                              checker or fact kind was lost
    linkcheck                 no broken relative link in the markdown
    gen_tool_comparison --check
                              the tool comparison document's numbers
                              still match the source
    gen_rules_overview --check
                              the rules overview's numbers still match
                              the source

The generators that WRITE (gen_rules_doc, gen_rules_index) are run
afterwards, and their output is reported as a working-tree change, so
a stale generated document is visible without failing the run -- the
document is regenerated, not judged.

Exit code 0 when every gate passes, 1 otherwise.
"""
import io
import os
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (label, argv, kind) -- kind "check" gates on the exit code, kind
# "regen" regenerates and reports whether the tree changed.
GATES = (
    ("format", ["python", "scripts/format.py", "--check"], "check"),
    ("gen_categories", ["python", "scripts/gen_categories.py", "--check"], "check"),
    ("gen_framework_annotations",
     ["python", "scripts/gen_framework_annotations.py", "--check"], "check"),
    ("check_framework_annotations",
     ["python", "scripts/check_framework_annotations.py"], "check"),
    ("check_catalogue_drift_numbers",
     ["python", "scripts/check_catalogue_drift_numbers.py"], "check"),
    ("check_dataflow_constraint",
     ["python", "scripts/check_dataflow_constraint.py"], "check"),
    ("check_support_matrix_coverage",
     ["python", "scripts/check_support_matrix_coverage.py"], "check"),
    ("check_javadoc_links",
     ["python", "scripts/check_javadoc_links.py"], "check"),
    ("check_doc_index",
     ["python", "scripts/check_doc_index.py"], "check"),
    ("check_doc_hygiene",
     ["python", "scripts/check_doc_hygiene.py"], "check"),
    ("check_doc_numbers",
     ["python", "scripts/check_doc_numbers.py"], "check"),
    ("check_doc_claims",
     ["python", "scripts/check_doc_claims.py"], "check"),
    ("check_tac_claims",
     ["python", "scripts/check_tac_claims.py"], "check"),
    ("check_cross_fn_claims",
     ["python", "scripts/check_cross_fn_claims.py"], "check"),
    ("check_environment_claims",
     ["python", "scripts/check_environment_claims.py"], "check"),
    ("check_cache_claims",
     ["python", "scripts/check_cache_claims.py"], "check"),
    ("check_style_rules_claims",
     ["python", "scripts/check_style_rules_claims.py"], "check"),
    ("check_capture_claims",
     ["python", "scripts/check_capture_claims.py"], "check"),
    ("mutate_capture_claims",
     ["python", "scripts/mutate_capture_claims.py"], "check"),
    ("check_capture_options",
     ["python", "scripts/check_capture_options.py"], "check"),
    ("mutate_capture_options",
     ["python", "scripts/mutate_capture_options.py"], "check"),
    ("check_i18n_docs",
     ["python", "scripts/check_i18n_docs.py"], "check"),
    ("mutate_i18n_docs",
     ["python", "scripts/mutate_i18n_docs.py"], "check"),
    ("check_readme_claims",
     ["python", "scripts/check_readme_claims.py"], "check"),
    ("check_section_refs",
     ["python", "scripts/check_section_refs.py"], "check"),
    ("mutate_section_refs",
     ["python", "scripts/mutate_section_refs.py"], "check"),
    ("check_package_claims",
     ["python", "scripts/check_package_claims.py"], "check"),
    ("capability_baseline",
     ["python", "scripts/capability_baseline.py", "--check"], "check"),
    ("gen_tool_comparison",
     ["python", "scripts/gen_tool_comparison.py", "--check"], "check"),
    ("gen_rules_overview",
     ["python", "scripts/gen_rules_overview.py", "--check"], "check"),
    ("linkcheck", ["python", ".aethercode/linkcheck.py"], "check"),
)

REGENERATORS = (
    ("gen_rules_doc", ["python", "scripts/gen_rules_doc.py"]),
    ("gen_rules_index", ["python", "scripts/gen_rules_index.py"]),
)


def python_on_path():
    """The python executable, resolved for subprocess use on Windows."""
    import shutil
    found = shutil.which("python") or shutil.which("python3")
    if not found:
        raise RuntimeError("cannot find the python executable")
    return found


def run(argv):
    cmd = [python_on_path()] + argv[1:] if argv and argv[0] == "python" else list(argv)
    proc = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    return proc.returncode, proc.stdout + proc.stderr


def main():
    failures = []

    print("=== gates ===")
    for label, argv, kind in GATES:
        code, output = run(argv)
        if code == 0:
            print("  PASS  %s" % label)
        else:
            print("  FAIL  %s (exit %d)" % (label, code))
            failures.append(label)
            for line in output.splitlines()[-25:]:
                print("        %s" % line)

    print()
    print("=== regenerated documents ===")
    before = subprocess.run(["git", "status", "--porcelain", "docs/"], cwd=REPO,
                            capture_output=True, text=True, encoding="utf-8",
                            errors="replace").stdout
    for label, argv in REGENERATORS:
        code, output = run(argv)
        if code != 0:
            print("  FAIL  %s (exit %d)" % (label, code))
            failures.append(label)
            for line in output.splitlines()[-15:]:
                print("        %s" % line)
            continue
        print("  OK    %s" % label)
    after = subprocess.run(["git", "status", "--porcelain", "docs/"], cwd=REPO,
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace").stdout
    if after != before:
        print()
        print("  the regenerated documents differ from the committed ones:")
        for line in after.splitlines():
            print("    %s" % line)
        print("  commit the regenerated documents, or the next run will report them again.")
    else:
        print("  the regenerated documents match the committed ones.")

    print()
    if failures:
        print("FAILED: %d gate(s) did not pass: %s" % (len(failures), ", ".join(failures)))
        return 1
    print("OK: every gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
