#!/usr/bin/env python3
"""Measure what a function value costs the analysis.

A function value -- a lambda, a closure, a generator -- is a value the
front end may not be able to model. The question this probe answers is the
one a reader of a report cannot answer by looking at the findings: **when
the front end drops such a value, what does the analysis lose?**

The probe runs a side car over its real wire protocol and reports, for four
shapes, whether the sink call is in the IR at all and whether the value the
closure captured is in scope where the sink is.

    python scripts/probe_function_values.py

The shapes are:

    control        the sink is called directly in the function body
    with_lambda    the sink is inside a lambda expression
    with_nested    the sink is inside a `def` nested in the function, and the
                   nested function captures a value from the enclosing scope
    with_generator the sink is inside a function that yields

The IR has no way to say "a function value" -- `TacOperand` permits a
variable and a constant and nothing else -- so a function value can only
exist as a variable holding a name. What this probe shows is the
consequence: the body of the function is not in the IR, so a sink inside
it is not an unknown taint, it is an instruction that is never analysed.
"""
import json
import os
import struct
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIDECAR = os.path.join(REPO, "plumb-frontend", "python-sidecar")

SOURCE = '''import os


def control(request):
    value_control = request["cmd"]
    os.system(value_control)


def with_lambda(request):
    value_lambda = request["cmd"]
    run = lambda: os.system(value_lambda)
    return run


def with_nested(request):
    value_nested = request["cmd"]

    def inner():
        os.system(value_nested)

    return inner


def with_generator(request):
    value_generator = request["cmd"]

    def gen():
        yield os.system(value_generator)

    return gen
'''

# The shape, and the value it captures. The names differ on purpose: a name
# that two shapes share cannot tell which shape a nested function belongs to.
SHAPES = [
    ("control", "value_control", "the sink is called in the body"),
    ("with_lambda", "value_lambda", "the sink is inside a lambda"),
    ("with_nested", "value_nested", "the sink is inside a nested def, capturing a value"),
    ("with_generator", "value_generator", "the sink is inside a generator"),
]


def fail(message):
    """Fail the probe with a message."""
    print("FAIL: %s" % message, file=sys.stderr)
    sys.exit(1)


def write_frame(stream, payload):
    """Write one length-prefixed frame."""
    data = payload.encode("utf-8")
    stream.write(struct.pack(">I", len(data)))
    stream.write(data)
    stream.flush()


def read_frame(stream):
    """Read one length-prefixed frame."""
    header = stream.read(4)
    if not header or len(header) < 4:
        fail("the side car closed its output")
    (length,) = struct.unpack(">I", header)
    payload = b""
    while len(payload) < length:
        chunk = stream.read(length - len(payload))
        if not chunk:
            fail("the side car closed its output")
        payload += chunk
    return json.loads(payload.decode("utf-8"))


def main():
    """Run the probe."""
    sys.path.insert(0, os.path.join(SIDECAR, "src"))
    from plumb_wire import IR_VERSION, Op, PROTOCOL_VERSION

    directory = tempfile.mkdtemp(prefix="plumb-function-value-probe-")
    path = os.path.join(directory, "app.py")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(SOURCE)

    child = subprocess.Popen(
        [sys.executable, os.path.join(SIDECAR, "src", "main.py")],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
    )
    try:
        write_frame(
            child.stdin,
            json.dumps(
                {
                    "op": Op.OPEN_PROJECT,
                    "protocolVersion": PROTOCOL_VERSION,
                    "irVersion": IR_VERSION,
                    "projectId": "probe",
                    "sourceRoot": directory,
                    "sources": [{"path": path}],
                }
            ),
        )
        response = read_frame(child.stdout)
        if not response.get("ok"):
            fail("the project must open: %s" % response.get("error", ""))

        write_frame(
            child.stdin,
            json.dumps(
                {
                    "op": Op.TRANSLATE,
                    "protocolVersion": PROTOCOL_VERSION,
                    "irVersion": IR_VERSION,
                    "path": path,
                    "language": "PYTHON3",
                }
            ),
        )
        response = read_frame(child.stdout)
        if not response.get("ok"):
            fail("the file must translate: %s" % response.get("error", ""))

        functions = response.get("functions") or []

        # The sink calls, and the function each one sits in.
        sinks = {}
        for function in functions:
            for instruction in function.get("instructions") or []:
                if (instruction.get("calleeSig") or "").startswith("os.system"):
                    sinks.setdefault(function.get("name"), []).append(instruction)
                    break

        # The scope of each function: a captured name that is in neither the
        # params nor the locals is a free variable, and a free variable
        # carries no taint into the function that uses it.
        scopes = {}
        for function in functions:
            names = [p.get("name") for p in (function.get("params") or [])]
            names += [l.get("name") for l in (function.get("locals") or [])]
            scopes[function.get("name")] = names

        print("functions in the IR: %d" % len(functions))
        print()
        print("%-16s %-8s %s" % ("shape", "sink", "verdict"))
        print("-" * 78)
        lost = 0
        for name, captured, description in SHAPES:
            function = next((f for f in functions if f.get("name") == name), None)
            if function is None:
                print("%-16s %-8s %s" % (name, "?", "the shape is not in the IR"))
                lost += 1
                continue
            body = "\n".join(
                json.dumps(i, ensure_ascii=False) for i in function.get("instructions") or []
            )
            if "os.system" in body:
                print("%-16s %-8s %s" % (name, "yes", "the sink is in the body"))
                continue
            # The sink is not in this function's body. It may still be in a
            # function of its own that captures the value this shape holds.
            holder = None
            for other, instructions in sinks.items():
                if other == name:
                    continue
                for instruction in instructions:
                    for argument in instruction.get("args") or []:
                        if argument.get("kind") == "VAR" and argument.get("name") == captured:
                            holder = other
                            break
                    if holder:
                        break
                if holder:
                    break
            if holder is None:
                print(
                    "%-16s %-8s %s"
                    % (name, "no", "LOST: the sink is not in the IR at all")
                )
                lost += 1
                continue
            if captured in scopes.get(holder, []):
                print(
                    "%-16s %-8s %s"
                    % (name, "no", "the sink is in %r and the captured value is in scope" % holder)
                )
            else:
                print(
                    "%-16s %-8s %s"
                    % (
                        name,
                        "no",
                        "LOST: the sink is in %r, but the captured value is not in its scope"
                        % holder,
                    )
                )
                lost += 1
        print()
        print("shapes that lose the finding: %d of %d" % (lost, len(SHAPES)))
        if lost:
            print("A lost finding is not a false positive: it is never produced, and a report")
            print("cannot show it. The front end says which shapes it dropped, through the")
            print("problem channel -- the report is honest, the finding is still gone.")
        else:
            print("Every shape keeps the finding: the sink is in the IR, and the value the")
            print("closure reads is in scope where the sink is. A capture is a PARAMETER, so")
            print("it is analysed as untrusted -- the finding is produced, and the origin it")
            print("names is the capture.")
    finally:
        child.kill()


if __name__ == "__main__":
    main()
