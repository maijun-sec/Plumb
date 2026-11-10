"""The sidecar's entry point: a request/response loop over stdio.

The process reads one frame at a time, dispatches it, and writes one frame
back. It exits when the pipe closes or when it is asked to shut down.

EVERY FAILURE IS REPORTED, NEVER SWALLOWED. A sidecar that dies silently
would leave the Java side believing the project had no findings. So an
error becomes an ``ok: false`` response, and the process stays alive to
answer the next request.

The frames are length-prefixed JSON: a 4-byte big-endian length, then the
UTF-8 payload. A source file may contain any byte in a string literal,
including a newline, so a line-oriented protocol would have to escape or
guess; a length prefix has no such ambiguity.
"""

import ast
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from plumb_translator import Translator  # noqa: E402 - the path is set above
from plumb_wire import IR_VERSION, Op, PROTOCOL_VERSION, stamp  # noqa: E402

# The project the sidecar currently holds.
session = {"sourceRoot": None, "files": []}


def read_frame(stream):
    """Read one frame from the stream, or None when the pipe is closed."""
    header = stream.read(4)
    if header is None or len(header) < 4:
        return None
    (length,) = struct.unpack(">I", header)
    payload = b""
    while len(payload) < length:
        chunk = stream.read(length - len(payload))
        if not chunk:
            return None
        payload += chunk
    return payload.decode("utf-8")


def write_frame(stream, payload):
    """Write one frame to the stream."""
    data = payload.encode("utf-8")
    stream.write(struct.pack(">I", len(data)))
    stream.write(data)
    stream.flush()


def module_name_of(path, source_root):
    """The module a file belongs to: its path under the source root.

    A package's ``__init__.py`` IS the package, so the name of the
    directory is the name of the module.
    """
    absolute = os.path.abspath(path)
    relative = os.path.basename(absolute)
    if source_root:
        try:
            relative = os.path.relpath(absolute, os.path.abspath(source_root))
        except ValueError:
            relative = os.path.basename(absolute)
    relative = relative.replace(os.sep, "/")
    for suffix in (".pyi", ".py"):
        if relative.endswith(suffix):
            relative = relative[: -len(suffix)]
            break
    parts = [part for part in relative.split("/") if part]
    if parts and parts[-1] == "__init__":
        parts = parts[:-1]
    return ".".join(parts) if parts else "<module>"


def handle(request):
    """Handle one request."""
    if not isinstance(request, dict):
        return failure(Op.PING, "the request was not a JSON object")
    op = request.get("op")
    if request.get("protocolVersion") != PROTOCOL_VERSION:
        return failure(
            op or Op.PING,
            "this sidecar speaks protocol version %d, and the request said %s"
            % (PROTOCOL_VERSION, request.get("protocolVersion", "nothing")),
        )
    if "irVersion" in request and request["irVersion"] != IR_VERSION:
        return failure(
            op or Op.PING,
            "this sidecar was built against IR version %d, and the request said %s"
            % (IR_VERSION, request["irVersion"]),
        )
    if op == Op.PING:
        return {
            **stamp(),
            "op": op,
            "ok": True,
            "runtime": {
                "name": "python",
                "version": "%d.%d.%d" % sys.version_info[:3],
                "major": sys.version_info[0],
            },
        }
    if op == Op.SHUTDOWN:
        return None
    if op == Op.OPEN_PROJECT:
        return open_project(request)
    if op == Op.TRANSLATE:
        return translate(request)
    if op == Op.CLOSE_PROJECT:
        session["sourceRoot"] = None
        session["files"] = []
        return {**stamp(), "op": op, "ok": True}
    return failure(op, "unknown operation: %s" % op)


def open_project(request):
    """Open the project: remember where its files live."""
    sources = request.get("sources")
    if not isinstance(sources, list):
        sources = []
    paths = []
    for source in sources:
        if isinstance(source, str):
            path = source
        elif isinstance(source, dict):
            path = source.get("path")
        else:
            path = None
        if isinstance(path, str) and path:
            paths.append(path)
    if not paths:
        return failure(Op.OPEN_PROJECT, "the project has no source files")
    session["sourceRoot"] = request.get("sourceRoot")
    session["files"] = paths
    return {**stamp(), "op": Op.OPEN_PROJECT, "ok": True, "projectId": request.get("projectId")}


def translate(request):
    """Translate one file into TAC."""
    path = request.get("path")
    if not isinstance(path, str) or not path:
        return failure(Op.TRANSLATE, "the request names no file")
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle_file:
            source = handle_file.read()
    except OSError as exc:
        return failure(Op.TRANSLATE, "the file could not be read: %s" % exc)
    try:
        tree = ast.parse(source, filename=path)
    except SyntaxError as exc:
        return failure(
            Op.TRANSLATE,
            "the file is not valid Python 3: %s (line %s)" % (exc.msg, exc.lineno),
        )
    except (ValueError, RecursionError, MemoryError) as exc:
        return failure(Op.TRANSLATE, "the file could not be parsed: %s" % exc)

    language = request.get("language") or "PYTHON3"
    translator = Translator(
        module_name=module_name_of(path, session["sourceRoot"]),
        language=language,
        text=source,
    )
    try:
        functions = translator.translate_module(tree)
    except Exception as exc:  # noqa: BLE001 - the point is to report it
        return failure(Op.TRANSLATE, "the file could not be translated: %s" % exc)
    return {
        **stamp(),
        "op": Op.TRANSLATE,
        "ok": True,
        "diagnostics": [],
        "functions": functions,
    }


def failure(op, message):
    """A failure response."""
    return {**stamp(), "op": op, "ok": False, "error": message}


def respond(payload):
    """Answer one request frame."""
    try:
        request = json.loads(payload)
    except ValueError as exc:
        return failure(Op.PING, "the request was not JSON: %s" % exc)
    try:
        return handle(request)
    except Exception as exc:  # noqa: BLE001 - the point is to report it
        return failure(request.get("op"), "the operation failed: %s" % exc)


def main():
    """The frame loop."""
    stdin = sys.stdin.buffer
    stdout = sys.stdout.buffer
    while True:
        payload = read_frame(stdin)
        if payload is None:
            return 0
        response = respond(payload)
        if response is None:
            return 0
        write_frame(stdout, json.dumps(response, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
