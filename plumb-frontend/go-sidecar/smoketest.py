#!/usr/bin/env python3
"""Drive the Go sidecar over its wire protocol and check the contract.

The script speaks the real framing -- a 4-byte big-endian length prefix
followed by a UTF-8 JSON payload -- and checks the things the contract
promises:

  1. `ping` answers, and every response carries `protocolVersion` and
     `irVersion`.
  2. `openProject` accepts a package whose file's TEXT is supplied by the
     caller, and the analysis sees the caller's text rather than the disk's.
  3. `translate` answers with the functions, and a `defer` produces a CALL
     instruction, a SOURCE_LOWERED precision and a SOURCE_LOWERED problem.
  4. `go` produces the same, plus `startsGoroutine`.
  5. A request for another protocol version is REFUSED.

Usage:

    python smoketest.py --sidecar bin/plumb-go-sidecar.exe
"""

import argparse
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

# The standard library packages the sample module needs, and the ones the
# export data of "os" refers to. A package that only appears in the export
# data is a placeholder unless its own export data is supplied, and a
# placeholder makes the types that mention it invalid.
STDLIB_PACKAGES = [
    "os",
    "io",
    "io/fs",
    "time",
    "syscall",
    "runtime",
    "errors",
    "sync",
    "sort",
    "strconv",
    "strings",
    "bytes",
    "fmt",
    "unicode",
    "unicode/utf8",
    "path",
    "path/filepath",
    "reflect",
    "slices",
    "cmp",
    "internal/poll",
    "internal/syscall/execenv",
    "internal/syscall/windows",
    "internal/syscall/unix",
    "internal/bytealg",
    "internal/itoa",
    "internal/oserror",
    "internal/race",
    "internal/reflectlite",
    "internal/stringslite",
    "internal/syscall",
    "internal/testlog",
    "internal/unsafeheader",
    "internal/goarch",
    "internal/goos",
    "internal/goarch",
    "vendor/golang.org/x/sys/cpu",
]

# The file on disk. It differs from the text the caller sends, so that the
# two can be told apart in the analysis.
ON_DISK = """package store

import "os"

func fromDisk() {
}

func WriteFile(name string, taintedBytes []byte) error {
\tdefer os.WriteFile(name, taintedBytes, 0644)
\treturn nil
}

func worker() {
}

func Spawn() {
\tgo worker()
}
"""

# The caller's copy: an unsaved buffer with a different function in it.
IN_MEMORY = """package store

import "os"

func fromMemory() {
}

func WriteFile(name string, taintedBytes []byte) error {
\tdefer os.WriteFile(name, taintedBytes, 0644)
\treturn nil
}

func worker() {
}

func Spawn() {
\tgo worker()
}
"""


class Sidecar:
    """A sidecar process, spoken to over the framed protocol."""

    def __init__(self, executable):
        self.process = subprocess.Popen(
            [executable],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.responses = []

    def exchange(self, request):
        payload = json.dumps(request).encode("utf-8")
        self.process.stdin.write(struct.pack(">I", len(payload)) + payload)
        self.process.stdin.flush()

        header = self.process.stdout.read(4)
        if len(header) != 4:
            raise RuntimeError("the sidecar closed the pipe")
        (length,) = struct.unpack(">I", header)
        body = b""
        while len(body) < length:
            chunk = self.process.stdout.read(length - len(body))
            if not chunk:
                raise RuntimeError("the sidecar closed the pipe mid-frame")
            body += chunk

        response = json.loads(body.decode("utf-8"))
        self.responses.append(response)
        return response

    def close(self):
        try:
            self.process.stdin.close()
        except OSError:
            pass
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()


def find_go_toolchain():
    """Finds a Go toolchain: the GO variable, the PATH, GOROOT, then the usual places."""
    candidates = []
    if os.environ.get("GO"):
        candidates.append(os.environ["GO"])
    found = shutil.which("go")
    if found:
        candidates.append(os.path.dirname(os.path.dirname(found)))
    if os.environ.get("GOROOT"):
        candidates.append(os.environ["GOROOT"])
    candidates += [
        r"D:\development\go1.26.10\go",
        r"C:\Program Files\Go",
        r"C:\Go",
        "/usr/local/go",
        "/usr/lib/go",
    ]
    leaf = "go.exe" if os.name == "nt" else "go"
    for candidate in candidates:
        executable = os.path.join(candidate, "bin", leaf)
        if os.path.isfile(executable):
            return candidate, executable
    return None, None


def build_export_data(go_exe, workspace, toolchain=""):
    """Builds the export data of the standard library packages the sample needs.

    The export data is what the build compiled, so it is what the sidecar
    reads. `go list -export` is the way to get it: it builds the package if
    it has to and then names the export data the compiler produced.

    `go build -o` does NOT produce it -- it writes an archive of the package,
    which is not the export data, and a file that is not export data is
    worse than no file at all: the import fails with a message about the
    file's format instead of a message about the missing dependency.

    The GOROOT is set to the toolchain this script chose. The environment's
    GOROOT is a leftover of whatever toolchain was installed last, and a
    leftover that names a directory which is not there makes the go command
    fail before it starts -- which looks like "the export data cannot be
    built" rather than like "the environment is stale".
    """
    produced = {}
    environment = dict(os.environ)
    environment["GOCACHE"] = os.path.join(workspace, "gocache")
    environment["GOMODCACHE"] = os.path.join(workspace, "gomodcache")
    environment["GOPROXY"] = "off"
    if toolchain:
        environment["GOROOT"] = toolchain
    os.makedirs(environment["GOCACHE"], exist_ok=True)

    for package in STDLIB_PACKAGES:
        completed = subprocess.run(
            [go_exe, "list", "-export", "-f", "{{.Export}}", package],
            capture_output=True,
            text=True,
            cwd=workspace,
            env=environment,
        )
        if completed.returncode != 0:
            continue
        target = completed.stdout.strip()
        if target and os.path.isfile(target):
            produced[package] = target
    return produced


def function_named(functions, name):
    for function in functions:
        if function.get("name") == name:
            return function
    return None


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    print("  [%s] %s%s" % (status, label, (" -- " + detail) if detail and not condition else ""))
    return condition


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--sidecar",
        default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin", "plumb-go-sidecar.exe"),
        help="the sidecar executable",
    )
    parser.add_argument("--go", default="", help="the Go toolchain root, for the export data")
    arguments = parser.parse_args()

    if not os.path.isfile(arguments.sidecar):
        print("the sidecar is not at %s" % arguments.sidecar)
        return 2

    toolchain, go_exe = find_go_toolchain()
    if arguments.go:
        toolchain = arguments.go
        go_exe = os.path.join(arguments.go, "bin", "go.exe" if os.name == "nt" else "go")
    if not toolchain:
        print("no Go toolchain was found: the export data cannot be built")
        return 2
    print("using the Go toolchain at %s" % toolchain)

    workspace = tempfile.mkdtemp(prefix="plumb-go-sidecar-smoke-")
    failures = 0
    try:
        module = os.path.join(workspace, "store")
        os.makedirs(module, exist_ok=True)
        source = os.path.join(module, "store.go")
        with open(source, "w", encoding="utf-8") as handle:
            handle.write(ON_DISK)
        with open(os.path.join(module, "go.mod"), "w", encoding="utf-8") as handle:
            handle.write("module example.com/smoke\n\ngo 1.21\n")

        export_data = build_export_data(go_exe, workspace, toolchain)
        print("built the export data of %d packages" % len(export_data))
        if "os" not in export_data:
            print("the export data of 'os' could not be built")
            return 2

        sidecar = Sidecar(arguments.sidecar)
        try:
            print("\n1. ping")
            response = sidecar.exchange({"op": "ping", "protocolVersion": 1, "irVersion": 1})
            print("   %s" % json.dumps(response))
            if not check("the ping succeeded", response.get("ok") is True):
                failures += 1

            print("\n2. openProject, with the caller's text for the file")
            request = {
                "op": "openProject",
                "protocolVersion": 1,
                "irVersion": 1,
                "projectId": "smoke",
                "sources": [{"path": source, "text": IN_MEMORY}],
                "packages": [
                    {
                        "importPath": "example.com/smoke/store",
                        "dir": module,
                        "files": [source],
                        "goVersion": "go1.21",
                        "buildTags": ["linux", "amd64"],
                        "imports": [
                            {"importPath": name, "exportData": path}
                            for name, path in sorted(export_data.items())
                        ],
                    }
                ],
            }
            response = sidecar.exchange(request)
            diagnostics = response.get("diagnostics") or []
            print("   ok=%s, %d diagnostics" % (response.get("ok"), len(diagnostics)))
            for diagnostic in diagnostics[:5]:
                print("     %s" % json.dumps(diagnostic))
            if not check("the project was opened", response.get("ok") is True):
                failures += 1
                return 1

            print("\n3. translate the file")
            response = sidecar.exchange(
                {"op": "translate", "protocolVersion": 1, "irVersion": 1, "path": source}
            )
            functions = response.get("functions") or []
            names = [function.get("name") for function in functions]
            print("   the functions are %s" % names)

            if not check("the caller's text was analysed, not the disk's",
                         "fromMemory" in names and "fromDisk" not in names,
                         "the functions are %s" % names):
                failures += 1

            write_file = function_named(functions, "WriteFile")
            if write_file is None:
                print("   the function 'WriteFile' was not translated")
                failures += 1
            else:
                calls = [
                    instruction
                    for instruction in write_file.get("instructions") or []
                    if instruction.get("kind") == "CALL"
                    and instruction.get("calleeSig") == "os.WriteFile/3"
                ]
                print("   WriteFile: precision=%s, %d CALL to os.WriteFile/3" % (
                    write_file.get("precision"), len(calls)))
                for problem in write_file.get("problems") or []:
                    print("     problem: %s" % json.dumps(problem))
                if not check("the deferred call is an instruction", len(calls) == 1):
                    failures += 1
                if not check("the function's precision is SOURCE_LOWERED",
                             write_file.get("precision") == "SOURCE_LOWERED",
                             str(write_file.get("precision"))):
                    failures += 1
                if not check("the function reports a SOURCE_LOWERED problem",
                             any(problem.get("kind") == "SOURCE_LOWERED"
                                 for problem in write_file.get("problems") or [])):
                    failures += 1
                if not check("the deferred call is in the side table",
                             len(write_file.get("deferredCalls") or []) == 1):
                    failures += 1

            spawn = function_named(functions, "Spawn")
            if spawn is None:
                print("   the function 'Spawn' was not translated")
                failures += 1
            else:
                print("   Spawn: precision=%s, startsGoroutine=%s" % (
                    spawn.get("precision"), spawn.get("startsGoroutine")))
                for problem in spawn.get("problems") or []:
                    print("     problem: %s" % json.dumps(problem))
                if not check("the function starts a goroutine",
                             spawn.get("startsGoroutine") is True):
                    failures += 1
                if not check("the function's precision is SOURCE_LOWERED",
                             spawn.get("precision") == "SOURCE_LOWERED",
                             str(spawn.get("precision"))):
                    failures += 1
                if not check("the function reports a SOURCE_LOWERED problem",
                             any(problem.get("kind") == "SOURCE_LOWERED"
                                 for problem in spawn.get("problems") or [])):
                    failures += 1

            print("\n4. a request for another protocol version")
            response = sidecar.exchange({"op": "ping", "protocolVersion": 99, "irVersion": 1})
            print("   %s" % json.dumps(response))
            if not check("the request was refused", response.get("ok") is False):
                failures += 1
            if not check("the refusal explains itself",
                         bool(response.get("error"))):
                failures += 1

            print("\n5. a request with no protocol version")
            response = sidecar.exchange({"op": "ping"})
            print("   %s" % json.dumps(response))
            if not check("the request was refused", response.get("ok") is False):
                failures += 1

            print("\n6. every response carries the versions")
            for index, answer in enumerate(sidecar.responses):
                if not check("response %d carries protocolVersion and irVersion" % (index + 1),
                             answer.get("protocolVersion") == 1 and answer.get("irVersion") == 1,
                             json.dumps(answer)[:200]):
                    failures += 1
        finally:
            sidecar.close()
    finally:
        shutil.rmtree(workspace, ignore_errors=True)

    print("\n%s" % ("ALL CHECKS PASSED" if failures == 0 else "%d CHECK(S) FAILED" % failures))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
