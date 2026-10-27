"""The joint gate: the capture and the Maven reader must mean the same things.

The two sides of the compile-parameter story are:

  * `plumb-build-capture` OBSERVES a compile.  It knows the options the
    compiler actually received, and it has a table of them.
  * `plumb-build-maven` READS a pom.  It knows the options the build
    DECLARED, and it has its own reading of the compiler plugin's
    configuration.

Both of them answer the same questions -- what language level, what encoding,
what classpath -- and they are used together: a build is analysed by reading
its pom AND by capturing what it really compiled with.  When the two
disagree about what an option MEANS, one of them is wrong and the analysis
is wrong in a way nobody can see.

So this gate checks the two against each other:

  * the compile parameters the Maven side reads have a counterpart in the
    capture's option table -- the pom's `<release>` is the same thing as the
    capture's `--release`;
  * the two agree on the ORDER in which the parameters decide, because
    `<release>` beats `<source>` on the pom side and `--release` beats
    `-source` on the capture side, and a build that says one thing and
    compiles another is a real situation;
  * the two agree on which parameters are optional -- an undeclared
    parameter is unknown, not a default, on both sides.

The gate is mutation-tested: `scripts/mutate_compile_parameters.py` breaks
each of these agreements in turn, and every one of them must make the gate
fail.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent

CAPTURE_OPTION = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/CompilerOption.java"
)
MAVEN_POM = (
    REPO
    / "plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/MavenPom.java"
)
CAPTURE_OPTIONS = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/JavacOptions.java"
)

failures = []
info = []


def note(msg):
    info.append(msg)
    print(msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


for path in (CAPTURE_OPTION, MAVEN_POM, CAPTURE_OPTIONS):
    if not path.is_file():
        print("FAIL: %s is missing" % path)
        sys.exit(1)

capture_option = read(CAPTURE_OPTION)
maven_pom = read(MAVEN_POM)
capture_options = read(CAPTURE_OPTIONS)

# ------------------------------------------------------------------
# 1. the parameters both sides read
# ------------------------------------------------------------------
# The compile parameters that both sides report.  Each entry is the name
# the parameter is known by, the capture's option that carries it, and the
# Maven configuration element that declares it.
#
# The point of the list is not that it is long.  It is that a parameter
# which is on one side and not the other is a hole: the analysis would
# report the parameter from one side and not the other, and the difference
# between them would be invisible.
# The parameters the CAPTURE observes, and the compile parameters the
# MAVEN side reads for the same thing.
#
# (the parameter, the capture's option, the Maven accessor)
PARAMETERS = [
    ("release", "--release", 'pluginConfiguration("release")'),
    ("source", "--source", 'pluginConfiguration("source")'),
    ("target", "--target", 'pluginConfiguration("target")'),
    ("encoding", "--encoding", 'pluginConfiguration("encoding")'),
    ("output directory", "--output-dir", "getOutputDirectory()"),
]

# The parameters the Maven side reads from the compiler plugin's
# configuration.  Each one has a counterpart in the capture's option table,
# because the two are the same parameter seen from two sides: one declared,
# the other performed.
MAVEN_ELEMENTS = [
    "release",
    "source",
    "target",
    "encoding",
]

# The capture's canonical option names, read out of its table.
canonical_names = set(re.findall(r'opt\("[^"]+",\s*"([^"]+)"', capture_option))
note("the capture declares %d canonical option name(s)" % len(canonical_names))

for parameter, capture_name, maven_element in PARAMETERS:
    if capture_name not in canonical_names:
        failures.append(
            "the compile parameter %s has no counterpart in the capture's option table "
            "(expected %s)" % (parameter, capture_name)
        )
    # The Maven side must read the parameter by that name.
    if maven_element not in maven_pom:
        failures.append(
            "the compile parameter %s is not read by the Maven side "
            "(expected %s)" % (parameter, maven_element)
        )
    else:
        note("the parameter %s is on both sides (%s / %s)" % (parameter, capture_name, maven_element))

# ------------------------------------------------------------------
# 2. the two sides must agree on the ORDER that decides
# ------------------------------------------------------------------
# The release beats the source, on both sides.  A build that declares
# `<source>8</source>` and pins `<release>17</release>` compiles at 17, and
# a side that reported 8 would be confidently wrong.
def order_of(text, first, second):
    """True when `first` appears before `second` in the text.

    <p>The invariant is about the ORDER, not about the shape of the code, so
    the check is the order -- a rewrite that reorders the two branches is
    caught, and a rewrite that reformats them is not a false alarm.
    """
    at_first = text.find(first)
    at_second = text.find(second)
    if at_first < 0 or at_second < 0:
        return False
    return at_first < at_second


release_before_source_pom = order_of(
    maven_pom, 'pluginConfiguration("release")', 'pluginConfiguration("source")'
)
release_before_source_capture = order_of(
    capture_options, "if (release != null)", "if (source != null)"
)
if not release_before_source_pom:
    failures.append(
        "the Maven side does not read <release> before <source>: a build that pins "
        "both would be reported at the wrong level"
    )
else:
    note("the Maven side reads <release> before <source>")
if not release_before_source_capture:
    failures.append(
        "the capture side does not prefer --release over -source: the two sides would "
        "report different levels for the same compile"
    )
else:
    note("the capture side prefers --release over -source")

# ------------------------------------------------------------------
# 3. the two sides must agree that an undeclared parameter is UNKNOWN
# ------------------------------------------------------------------
# Neither side may fall back to a default.  The upstream project hard-coded
# "1.8" and "UTF-8", so every project was reported as if it compiled at 1.8
# in UTF-8 -- a confident wrong answer, which is worse than an absent one
# because the analyser cannot tell it is a guess.
# The literals that would be a confident wrong answer if they were used as
# a fallback.  The upstream project hard-coded "1.8" with a TODO next to
# it, so every project was reported as if it compiled at 1.8 -- and the
# analyser cannot tell that it is a guess.
LEVEL_DEFAULTS = ('"1.8"', '"8"')


def uses_a_level_default(source_text):
    """True when a default level appears as a fallback.

    <p>The check is on the LITERAL and on how it is used -- a fallback
    literal is one that stands in for a value the build did not declare.
    The upstream's `return "1.8"` and a ternary
    `return x == null ? "1.8" : y` are the same mistake, so both are
    caught.
    """
    for literal in LEVEL_DEFAULTS:
        for match in re.finditer(re.escape(literal), source_text):
            at = match.start()
            # The context around the literal: a fallback is a literal that
            # appears after a "?" or after a "return".
            before = source_text[max(0, at - 60) : at]
            if "?" in before or "return" in before or "=" in before:
                return True
    return False


for side, source_text in (
    ("Maven", maven_pom),
    ("capture", capture_options),
):
    if uses_a_level_default(source_text):
        failures.append(
            "the %s side falls back to a default language level: an undeclared "
            "parameter is unknown, not a default" % side
        )
    else:
        note("the %s side does not default the language level" % side)

# The Maven side returns null for an unknown encoding rather than UTF-8.
#
# The check is on the CATCH BLOCK of the Charset.forName call: that is the
# place where the encoding was not known, and what it returns there is the
# answer to "what is the encoding of a build that did not declare one".
# The check is scoped to the encoding method, because the file has more
# than one catch block and the first one is not the encoding's.
#
# The catch block's body contains a format string with braces, so matching
# to the first '}' would stop inside the string.  The invariant is about
# the RETURN -- so that is what is matched: the first `return` after the
# catch is the answer to "what is the encoding of a build that did not
# declare one".
encoding_method = re.search(
    r"Charset\s+encoding\(\)\s*\{(.*?)\n  \}", maven_pom, flags=re.S
)
encoding_failure = None
if encoding_method:
    body = encoding_method.group(1)
    catch_at = body.find("catch")
    if catch_at >= 0:
        after_catch = body[catch_at:]
        returned = re.search(r"return\s+([^;]+);", after_catch)
        if returned:
            encoding_failure = returned
if encoding_failure is None:
    failures.append(
        "the Maven side does not handle an unknown encoding at all: an undeclared "
        "encoding is unknown, not UTF-8"
    )
elif "null" in encoding_failure.group(1):
    note("the Maven side returns null for an encoding it does not know")
else:
    failures.append(
        "the Maven side does not return null for an unknown encoding: an undeclared "
        "encoding is unknown, not UTF-8"
    )

# ------------------------------------------------------------------
# 4. the two sides must not disagree about the compiler's own spellings
# ------------------------------------------------------------------
# The Maven side reads the compiler plugin's configuration elements, and
# the capture side reads the compiler's command line options.  The ELEMENTS
# and the OPTIONS are different spellings of the same thing, so each element
# must have an option that means the same.  A `<release>` element and a
# `--release` option are the same parameter; an element with no option
# counterpart is a parameter the capture could never see.
ELEMENT_TO_OPTION = {
    "release": "--release",
    "source": "--source",
    "target": "--target",
    "encoding": "--encoding",
    "outputDirectory": "--output-dir",
}

for element, option in ELEMENT_TO_OPTION.items():
    if option not in canonical_names:
        failures.append(
            "the compiler plugin's <%s> element has no counterpart in the capture's "
            "option table (expected %s)" % (element, option)
        )
    else:
        note("the element <%s> and the option %s are the same parameter" % (element, option))

# ------------------------------------------------------------------
# 5. the classpath comes from the resolver, not from the plugin's
#    configuration
# ------------------------------------------------------------------
# This is a DELIBERATE difference, and the gate says so: the Maven side
# resolves the dependency closure through the Maven Resolver, because a
# plugin's own <classpath> declaration is not what the compiler is given.
# The capture side observes the classpath the compiler actually received.
#
# So the classpath is a parameter on BOTH sides, but it is obtained in two
# different ways -- and both ways have to exist.
resolver_adapter = (
    REPO
    / "plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/MavenResolverAdapter.java"
)
if resolver_adapter.is_file():
    resolver_text = read(resolver_adapter)
    if "classpath" not in resolver_text.lower():
        failures.append(
            "the Maven side does not resolve a classpath at all: the resolver adapter "
            "is the only source of the transitive closure"
        )
    else:
        note("the Maven side resolves the classpath through the Maven Resolver")
else:
    failures.append("the Maven resolver adapter is missing: %s" % resolver_adapter)

if "--classpath" not in canonical_names:
    failures.append(
        "the capture does not record a classpath: the two sides would not be "
        "describing the same compile"
    )
else:
    note("the capture records the classpath the compiler actually received")

# ------------------------------------------------------------------
# 6. the plugin's own extras have to be readable on both sides
# ------------------------------------------------------------------
# The compiler plugin's configuration holds more than the four well-known
# elements.  Two of them change the compile in a way nothing else records:
#
#   * `<compilerArgs>` -- arguments passed to the compiler verbatim.  A
#     `-proc:none` turns annotation processing off; a `-parameters` changes
#     the class files.  Neither can be reconstructed from the pom's other
#     settings, so the Maven side has to read them.
#   * `<annotationProcessorPaths>` -- the jars that hold the annotation
#     processors.  They are the jars that decide which annotations are
#     processed at all, and they are a path of their own rather than part of
#     the compile classpath.
#
# The capture side records the processor path, and it records the arguments
# that reached the compiler.  So both sides have to have a way to say it.
PROCESSOR_PATH_OPTION = "--processor-path"

if PROCESSOR_PATH_OPTION not in canonical_names:
    failures.append(
        "the capture does not record a processor path: the compiler plugin's "
        "<annotationProcessorPaths> is a parameter the capture could never see"
    )
else:
    note("the capture records the processor path the compiler received")

# The Maven side must read the two elements.
for element, accessor in (
    ("compilerArgs", "compilerArgs()"),
    ("annotationProcessorPaths", "annotationProcessorPaths()"),
):
    if accessor not in maven_pom:
        failures.append(
            "the Maven side does not read the compiler plugin's <%s> element "
            "(expected %s)" % (element, accessor)
        )
    else:
        note("the Maven side reads the compiler plugin's <%s>" % element)

# The elements must be looked up by their real names, not by a prefix that
# would also match something else.
for element in ("compilerArgs", "annotationProcessorPaths"):
    if '"%s"' % element not in maven_pom:
        failures.append(
            "the Maven side does not look up the <%s> element by its name" % element
        )

# ------------------------------------------------------------------
# 7. a child module inherits the compiler plugin's configuration
# ------------------------------------------------------------------
# This is the normal shape of a multi-module project: the parent configures
# the compiler once, and every child inherits it.  A lookup that only reads
# the module's own pom reports "no language level" for every child of a
# well-configured parent -- and the analyser then falls back to a guess.
#
# So the Maven side has to collect the plugin's configuration along the
# parent chain, and the module's own has to win.
if "addCompilerConfiguration" not in maven_pom:
    failures.append(
        "the Maven side does not collect the compiler plugin's configuration along "
        "the parent chain: a child module would report no language level at all"
    )
else:
    note("the Maven side collects the compiler configuration along the parent chain")

# The module's own configuration must be searched FIRST, because that is what
# Maven does: the effective pom merges the parent into the child, with the
# child on top.
own_before_parent = False
if "addCompilerConfiguration(compilerConfigurations, raw);" in maven_pom:
    parent_at = maven_pom.find("addCompilerConfiguration(compilerConfigurations, parentModel);")
    own_at = maven_pom.find("addCompilerConfiguration(compilerConfigurations, raw);")
    if parent_at >= 0 and own_at >= 0:
        own_before_parent = own_at < parent_at
if not own_before_parent:
    failures.append(
        "the Maven side does not prefer the module's own compiler configuration over "
        "its parent's: a child that pins a different release is compiled at that "
        "release"
    )
else:
    note("the module's own compiler configuration wins over its parent's")

print()
if failures:
    print("FAIL: %d defect(s) in the compile parameters" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: the capture and the Maven reader mean the same things")
