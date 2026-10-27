#!/usr/bin/env python3
"""Check that the packaging scripts and their documentation agree with reality.

Three families of defect, all of them silent until someone tries to ship:

  1. A packaging script copies a file that does not exist.  `package.sh` runs
     under `set -euo pipefail` and the PowerShell scripts run under
     `$ErrorActionPreference = 'Stop'`, so a missing source file ABORTS the
     packaging run.  The doc tree was reorganised and the scripts were left
     pointing at the old paths.

  2. A script the documentation names does not exist.

  3. A number the documentation states -- an artifact size, a log level --
     that the current build does not produce.

The check is deliberately narrow: it verifies the claims that are cheap to
re-derive and expensive to get wrong.  Sizes are compared with a tolerance
because they move with the dependency tree.

    python scripts/check_package_claims.py

Exit code 0 when everything agrees, 1 otherwise.
"""
import io
import os
import pathlib
import re
import shutil
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
SCRIPTS = REPO / "scripts"
DOC = REPO / "docs" / "build" / "PACKAGE.md"

# The scripts the documentation names.
NAMED_SCRIPTS = [
    "build.sh",
    "build.ps1",
    "package.sh",
    "package-slim.ps1",
    "dist-jlink-linux.sh",
    "dist-jlink-windows.ps1",
]

# The files a script copies out of the repository.  Each entry is
# (script, source path relative to the repo root).
COPIED_SOURCES = {
    "dist-jlink-linux.sh": [
        "README.md",
        "docs/plumb-style-rules.md",
        "docs/usage/ENVIRONMENT.md",
        "docs/usage/CLI.md",
        "docs/usage/CLI-REFERENCE.md",
        "docs/usage/CLI-EXAMPLES.md",
        "docs/rules/RULES.md",
    ],
    "dist-jlink-windows.ps1": [
        "README.md",
        "docs/plumb-style-rules.md",
        "docs/usage/ENVIRONMENT.md",
        "docs/usage/CLI.md",
        "docs/usage/CLI-REFERENCE.md",
        "docs/usage/CLI-EXAMPLES.md",
        "docs/rules/RULES.md",
    ],
    "package.sh": [
        "plumb-main/src/main/resources/logback.xml",
        "plumb-main/src/main/resources/default-config.yaml",
    ],
}

# The logback config the shipped one comes from.
LOGBACK = REPO / "plumb-main" / "src" / "main" / "resources" / "logback.xml"


def main():
    failures = []

    if not DOC.is_file():
        print("FAIL: %s does not exist" % DOC)
        return 1
    text = DOC.read_text(encoding="utf-8", errors="replace")

    # (1) Every script the documentation names must exist.
    #     The names are read OUT OF THE DOCUMENT, so a document that names a
    #     script that does not exist is caught.
    doc_scripts = set(re.findall(r"scripts/([A-Za-z0-9._-]+\.(?:sh|ps1))", text))
    for name in sorted(doc_scripts):
        if not (SCRIPTS / name).is_file():
            failures.append("the documentation names scripts/%s, which does not exist" % name)
    print("INFO  scripts named in the documentation: %s" % ", ".join(sorted(doc_scripts)))

    # (2) Every file a script copies must exist -- otherwise the packaging
    #     run aborts, because both script families stop on the first error.
    for script, sources in COPIED_SOURCES.items():
        path = SCRIPTS / script
        if not path.is_file():
            failures.append("scripts/%s does not exist" % script)
            continue
        body = path.read_text(encoding="utf-8", errors="replace")
        for src in sources:
            # The PowerShell scripts spell the paths with backslashes.
            variants = {src, src.replace("/", "\\")}
            if not any(v in body for v in variants):
                failures.append(
                    "scripts/%s no longer copies %s -- the packaging layout and the "
                    "script disagree" % (script, src))
        # Every source path the script names must exist on disk.  This is
        # the direction that breaks the build: `cp` of a missing file aborts
        # under `set -euo pipefail`.
        for m in re.finditer(r"copy_if_present\s+(\S+)", body):
            src = m.group(1)
            if not (REPO / src).is_file():
                failures.append(
                    "scripts/%s copies %s, which does not exist -- packaging would "
                    "abort on the missing file" % (script, src))
        for m in re.finditer(r"'([A-Za-z0-9_./\\-]+\.(?:md|yaml|xml|txt))'", body):
            src = m.group(1).replace("\\", "/")
            if src.startswith("docs/") or src == "README.md":
                if not (REPO / src).is_file():
                    failures.append(
                        "scripts/%s copies %s, which does not exist -- packaging would "
                        "abort on the missing file" % (script, src))

    # (3) The artifact size the documentation states must match the build.
    jar = REPO / "plumb-main" / "target"
    jars = sorted(jar.glob("*-all.jar")) if jar.is_dir() else []
    if jars:
        actual_mb = jars[-1].stat().st_size / (1024 * 1024)
        # Only the uber-jar size is checked: the other MB figures are the
        # jlink JRE and the compressed archives, which are not built here.
        claimed = {int(m.group(1)) for m in re.finditer(r"uber-jar[^\n|]*?(\d+)\s*MB", text)}
        claimed |= {int(m.group(1)) for m in re.finditer(r"\(shaded uber-jar\)\s*\|\s*~?(\d+)\s*MB", text)}
        print("INFO  the fat jar is %.1f MB; the documentation claims %s MB"
              % (actual_mb, sorted(claimed) or "nothing"))
        if claimed:
            for c in sorted(claimed):
                if abs(c - actual_mb) > 5:
                    failures.append(
                        "the documentation states an uber-jar size of %d MB, but the "
                        "built jar is %.1f MB" % (c, actual_mb))
    else:
        print("NOTE: no uber-jar built -- the size claim was not checked.")

    # (4) The log level the documentation states must match the shipped
    #     logback config.
    if LOGBACK.is_file():
        logback = LOGBACK.read_text(encoding="utf-8", errors="replace")
        root = re.search(r'<root\s+level="(\w+)"', logback)
        plumb = re.search(r'<logger\s+name="org\.plumb"\s+level="(\w+)"', logback)
        if root and plumb:
            print("INFO  logback: root=%s org.plumb=%s" % (root.group(1), plumb.group(1)))
            if "root = `ERROR`" not in text and "root = ERROR" not in text:
                if root.group(1) != "ERROR":
                    failures.append(
                        "the documentation does not state the logback root level %s"
                        % root.group(1))
            if "INFO 级别" in text or "INFO level" in text:
                failures.append(
                    "the documentation says the shipped logback config is INFO level, "
                    "but it is root=%s / org.plumb=%s"
                    % (root.group(1), plumb.group(1)))
        else:
            print("NOTE: could not read the root/logger levels from logback.xml")

    # (5) The scripts must parse.  A syntax error in a packaging script is
    #     invisible until someone runs it, and no maven build covers it.
    check_shell_syntax(failures)
    check_powershell_syntax(failures)

    if failures:
        print()
        print("FAIL: the packaging scripts and their documentation do not agree")
        for f in failures:
            print("  %s" % f)
        print()
        print("A packaging script that copies a missing file aborts the whole")
        print("build. A documentation number that does not match the artifact")
        print("sends the reader looking for the wrong thing.")
        return 1

    print("  ok: the packaging scripts and the packaging documentation agree")
    return 0


def check_shell_syntax(failures):
    """The bash scripts must parse.

    A syntax error in a packaging script is invisible until someone runs it,
    and the script is not part of the maven build.  `bash -n` parses without
    executing.
    """
    bash = shutil.which("bash")
    if not bash:
        print("NOTE: bash is not on PATH -- the shell scripts were not syntax-checked.")
        return
    for name in ("dist-jlink-linux.sh", "package.sh", "build.sh"):
        path = SCRIPTS / name
        if not path.is_file():
            continue
        # The `bash` on PATH may be the WSL launcher, which cannot open a
        # Windows path.  A path relative to the repository root works for
        # every flavour of bash, and the subprocess inherits the cwd.
        rel = path.relative_to(REPO).as_posix()
        proc = subprocess.run([bash, "-n", rel], capture_output=True, text=True,
                              encoding="utf-8", errors="replace", cwd=str(REPO))
        if proc.returncode != 0:
            failures.append(
                "scripts/%s does not parse: %s" % (name, (proc.stderr or "").strip()))


def check_powershell_syntax(failures):
    """The PowerShell scripts must parse.

    The parser is the same one PowerShell itself uses, so a parse error here
    is a parse error at run time.  The check is skipped when no PowerShell
    host is available.
    """
    host = shutil.which("pwsh") or shutil.which("powershell")
    if not host:
        print("NOTE: no PowerShell host on PATH -- the .ps1 scripts were not syntax-checked.")
        return
    # The path is passed as an environment variable: the argument list of a
    # Windows PowerShell -Command differs from pwsh's, but the environment
    # is the same for both.
    script = (
        "$e=$null;"
        "[System.Management.Automation.Language.Parser]::ParseFile("
        "$env:PLUMB_PARSE_TARGET,[ref]$null,[ref]$e)|Out-Null;"
        "if($e){$e|ForEach-Object{Write-Output $_.Message};exit 1}else{exit 0}"
    )
    for name in ("dist-jlink-windows.ps1", "package-slim.ps1", "build.ps1"):
        path = SCRIPTS / name
        if not path.is_file():
            continue
        env = dict(os.environ)
        env["PLUMB_PARSE_TARGET"] = str(path.resolve())
        proc = subprocess.run(
            [host, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            env=env)
        if proc.returncode != 0:
            failures.append(
                "scripts/%s does not parse: %s"
                % (name, (proc.stdout or proc.stderr).strip()))


if __name__ == "__main__":
    sys.exit(main())
