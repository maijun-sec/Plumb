#!/usr/bin/env python3
"""The license header, in one place.

The header text lives HERE and nowhere else. Three things need it:

  - scripts/add_license_headers.py   applies it to the sources
  - scripts/check_license.py         verifies it is present
  - scripts/gen_categories.py        emits it on the generated file

If each of those carried its own copy of the text, they would drift:
the applier would write one header, the gate would demand another, and
the generated file would carry a third. The gate would then fail on
every file at once, and the fix would be to relax the gate -- which is
how a licence check quietly stops being a licence check.

So: one constant, imported by all three.

The header is a javadoc-style block comment. Two reasons:

  - it is what the project asked for, and
  - it is a comment the compiler ignores and the formatter leaves
    alone. google-java-format 1.37.0 preserves a leading block comment
    byte for byte and is idempotent about it, so the formatting gate
    and the licence gate do not fight.

The block must be the FIRST thing in the file. A licence header that
follows the package declaration is not a header; a reader scanning for
the terms has to find them.
"""

COPYRIGHT_YEAR = "2026"
COPYRIGHT_HOLDER = "maijun"

SPDX_ID = "MIT"

# The MIT permission text, as it appears in the LICENSE file.
MIT_BODY = """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""


def header_text() -> str:
    """The exact header block, including the trailing newline.

    Every line is prefixed with " * " so the block reads as javadoc.
    The width is fixed at 79 columns, which is what the project uses
    everywhere else.
    """
    lines = [
        "/*",
        " * Copyright (c) %s %s" % (COPYRIGHT_YEAR, COPYRIGHT_HOLDER),
        " *",
    ]
    for line in MIT_BODY.splitlines():
        lines.append((" * " + line).rstrip())
    lines.append(" */")
    return "\n".join(lines) + "\n"


# The file the header text is derived from, so a mismatch between the
# LICENSE file and the headers is detectable rather than invisible.
LICENSE_REL_PATH = "LICENSE"


def license_file_text() -> str:
    """The LICENSE file's text, with the year and holder substituted."""
    return (
        "%s License\n\n"
        "Copyright (c) %s %s\n\n"
        "%s\n"
        % (SPDX_ID, COPYRIGHT_YEAR, COPYRIGHT_HOLDER, MIT_BODY)
    )


if __name__ == "__main__":
    import sys

    sys.stdout.write(header_text())
