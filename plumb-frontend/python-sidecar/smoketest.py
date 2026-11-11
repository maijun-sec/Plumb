"""The smoke test: the sidecar, end to end, over its real wire.

It starts the sidecar as a process, speaks the framing protocol to it, and
checks the TAC that comes back. The point is to test the PROCESS and the
PROTOCOL, not the translator's internals: a translator that is right in a
unit test and wrong on the wire produces a sidecar the Java side cannot
read, and the Java side's answer to that is "no findings".

Run it with `python smoketest.py` from this directory.
"""

import json
import os
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "src"))

from plumb_wire import IR_VERSION, Op, PROTOCOL_VERSION  # noqa: E402

# The probe program.
SOURCE = '''import os


class UserStore:
    def find(self, key):
        return self.data[key]


def handle(request):
    store = UserStore()
    key = request["user_id"]
    out = store.find(key)
    box = {"label": out}
    box["label"] = key + "!"
    os.system(box["label"])
    return box["label"]
'''


def fail(message):
    """Fail the test with a message."""
    print("FAIL: %s" % message, file=sys.stderr)
    sys.exit(1)


def check(condition, message):
    """Assert a condition."""
    if not condition:
        fail(message)


def write_frame(stream, payload):
    """Write one frame."""
    data = payload.encode("utf-8")
    stream.write(struct.pack(">I", len(data)))
    stream.write(data)
    stream.flush()


def read_frame(stream):
    """Read one frame from the sidecar's stdout."""
    header = stream.read(4)
    if header is None or len(header) < 4:
        fail("the sidecar closed its output")
    (length,) = struct.unpack(">I", header)
    payload = b""
    while len(payload) < length:
        chunk = stream.read(length - len(payload))
        if not chunk:
            fail("the sidecar closed its output")
        payload += chunk
    return json.loads(payload.decode("utf-8"))


def main():
    """Run the smoke test."""
    directory = tempfile.mkdtemp(prefix="plumb-python-smoke-")
    path = os.path.join(directory, "app.py")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(SOURCE)

    child = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "src", "main.py")],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=None,
    )

    try:
        # The handshake: a request without the protocol version is refused.
        write_frame(child.stdin, json.dumps({"op": Op.PING}))
        response = read_frame(child.stdout)
        check(response.get("ok") is False, "a request without a protocol version must be refused")
        check(
            isinstance(response.get("error"), str) and response["error"],
            "a refused request must say why",
        )

        # A ping with the version is answered.
        write_frame(
            child.stdin,
            json.dumps(
                {"op": Op.PING, "protocolVersion": PROTOCOL_VERSION, "irVersion": IR_VERSION}
            ),
        )
        response = read_frame(child.stdout)
        check(response.get("ok") is True, "a ping must be answered")
        check(
            response.get("protocolVersion") == PROTOCOL_VERSION
            and response.get("irVersion") == IR_VERSION,
            "every response must carry the version fields",
        )
        check(
            response.get("runtime", {}).get("major") == 3,
            "the sidecar must report that it runs on Python 3",
        )

        # The project opens.
        write_frame(
            child.stdin,
            json.dumps(
                {
                    "op": Op.OPEN_PROJECT,
                    "protocolVersion": PROTOCOL_VERSION,
                    "irVersion": IR_VERSION,
                    "projectId": "smoke",
                    "sourceRoot": directory,
                    "sources": [{"path": path}],
                }
            ),
        )
        response = read_frame(child.stdout)
        check(response.get("ok") is True, "the project must open: %s" % response.get("error", ""))

        # The file translates.
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
        check(response.get("ok") is True, "the file must translate: %s" % response.get("error", ""))

        functions = response.get("functions") or []
        handle_function = next((f for f in functions if f["name"] == "handle"), None)
        check(handle_function is not None, "the module's function must be in the stream")
        find = next((f for f in functions if f["name"] == "find"), None)
        check(find is not None, "the class method must be in the stream")
        check(
            find.get("enclosingClass") == "UserStore",
            "a method must name the class it belongs to",
        )

        instructions = handle_function["instructions"]

        # A property read is a CONTAINER ACCESS with a string key, not a
        # field access: in this language an object is a namespace, and
        # `box["label"]` and `box.label` are the same location.
        property_read = next(
            (
                i
                for i in instructions
                if i["kind"] == "ARRAYLOAD"
                and i.get("lhs2", {}).get("kind") == "CONST"
                and i["lhs2"].get("value") == "label"
            ),
            None,
        )
        check(
            property_read is not None,
            "a property read must be an ARRAYLOAD whose key is the property name",
        )
        check(
            not any(i["kind"] == "FIELDLOAD" for i in instructions),
            "a property read must not be a field access in this language",
        )

        # A call keeps the receiver slot: args[0] is the receiver, args[1..]
        # are the arguments. A call that put the tainted value at args[0]
        # would look right and match nothing.
        call = next(
            (i for i in instructions if i["kind"] in ("CALL", "UNINTERPRETED")), None
        )
        check(call is not None, "the call must be in the stream")
        check(len(call.get("args") or []) >= 1, "a call must carry the receiver slot")
        check(
            call["args"][0]["kind"] in ("VAR", "CONST"),
            "the receiver slot must hold an operand",
        )

        # A module's function is called by its qualified name: the import is
        # what makes the name resolvable, and the name is what the sink
        # catalogue matches on.
        system = next(
            (i for i in instructions if (i.get("calleeSig") or "").startswith("os.system")),
            None,
        )
        check(system is not None, "a call of an imported module's function must name it")
        check(
            system["calleeSig"] == "os.system/1",
            "the callee signature must be the qualified name: %s" % system["calleeSig"],
        )
        check(
            system["args"][0]["kind"] == "CONST" and system["args"][0].get("value") == "os",
            "the receiver slot of a module's function must hold the module's name",
        )

        # A method's owner is the class of the receiver, and the receiver is
        # the first argument.
        find_call = next(
            (i for i in instructions if (i.get("calleeSig") or "").startswith("UserStore.find")),
            None,
        )
        check(find_call is not None, "a call of a method must name the class of the receiver")
        check(
            find_call["calleeSig"] == "UserStore.find/1",
            "the callee signature of a method must be the class and the name: %s"
            % find_call["calleeSig"],
        )

        # A construction is a NEW, and its key names the CONSTRUCTOR. The
        # `<init>` part is the shape every other front end uses for a
        # construction, so a catalogue entry written for one of them can
        # match this one. The owner stays `<unknown>`: a class this module
        # declares comes from no module, and a file whose name looks like a
        # framework module must not produce a key the catalogue can claim.
        construction = next((i for i in instructions if i["kind"] == "NEW"), None)
        check(
            construction is not None,
            "the construction of a class this module declares must be a NEW",
        )
        check(
            construction["calleeSig"] == "<unknown>.UserStore.<init>/0",
            "the callee signature of a construction must name the constructor: %s"
            % construction["calleeSig"],
        )
        check(
            len(construction.get("args") or []) == 0,
            "a construction has no receiver slot, so it carries only the arguments",
        )
        check(
            construction.get("ctorType") is not None,
            "a construction must carry the type it built",
        )
        check(
            not any(i["kind"] == "UNINTERPRETED" for i in instructions),
            "a construction of a class this module declares must not be a black box",
        )

        # A binary operator is a BINOP, not an unknown value. A lookup that
        # always misses does not look like a lookup: every binary expression
        # would become a HAVOC, and the taint that flowed through it would
        # stop there without a word.
        addition = next((i for i in instructions if i["kind"] == "BINOP"), None)
        check(addition is not None, "a binary operator must be lowered into a BINOP")
        check(addition.get("binop") == "ADD", "the addition must be the ADD operator")

        # A property WRITE is a store with the same key, so that a later read
        # finds the value that was written.
        property_write = next(
            (
                i
                for i in instructions
                if i["kind"] == "ARRAYSTORE"
                and i.get("lhs2", {}).get("kind") == "CONST"
                and i["lhs2"].get("value") == "label"
            ),
            None,
        )
        check(
            property_write is not None,
            "a property assignment must be an ARRAYSTORE whose key is the property name",
        )
        check(
            not any(i["kind"] == "HAVOC" for i in instructions),
            "nothing in this program is beyond the translator",
        )

        # The function's own name is in its signature, so the sink catalogue
        # has something to match.
        check(
            isinstance(handle_function.get("signature"), str)
            and "handle" in handle_function["signature"],
            "the signature must name the function",
        )

        print("ok: the sidecar answers over the wire, and the TAC has the expected shape")
    finally:
        child.kill()


if __name__ == "__main__":
    main()
