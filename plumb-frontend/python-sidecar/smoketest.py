"""The smoke test: the sidecar, end to end, over its real wire.

It starts the sidecar as a process, speaks the framing protocol to it, and
checks the TAC that comes back. The point is to test the PROCESS and the
PROTOCOL, not the translator's internals: a translator that is right in a
unit test and wrong on the wire produces a sidecar the Java side cannot
read, and the Java side's answer to that is "no findings".

Run it with `python smoketest.py` from this directory.
"""

import ast
import json
import os
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "src"))

from plumb_translator import Translator  # noqa: E402
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


def shadowed(request):
    UserStore = request
    return UserStore(request)
'''

# The second probe program: the constructs whose shape has to be right.
SECOND_SOURCE = '''import os


class Base:
    def ping(self):
        return 1


class Child(Base):
    """A class with a docstring."""

    limit = 10

    def __init__(self, name):
        self.name = name

    def run(self, value):
        if value and os.getcwd():
            return value
        return None

    @staticmethod
    def stat(cmd):
        return cmd

    @classmethod
    def build(cls, name):
        return cls(name)


def truthy(a, b):
    return a and b


def coalesced(a, b):
    return a if a else b


def looping(items):
    total = 0
    for item in items:
        if item:
            break
    else:
        total = 1
    while total:
        break
    return total


def guarded():
    try:
        return 1
    except OSError as error:
        return str(error)
    finally:
        pass


def matched(value):
    match value:
        case 1:
            return "one"
        case _:
            return "other"


def comprehended(items):
    return [str(item) for item in items if item]


def formatted(width):
    return f"{width:>3}"


def sliced(items):
    return items[1:2]


def contained(items, value):
    return value in items


def powered(value):
    value **= 2
    return value
'''

# The third probe program: the constructs whose shape has to be right.
THIRD_SOURCE = '''import os
from dataclasses import dataclass


@dataclass
class Record:
    name: str
    count: int = 0


class Base:
    @staticmethod
    def stat(cmd):
        return cmd

    @classmethod
    def build(cls, name):
        return cls(name)


class Child(Base):
    pass


def keyword(a, *, b=1, **kw):
    return a


def defaulted(a, *, b=os.getcwd()):
    return b


def reading(path):
    with open(path) as fh:
        return fh.read()


def scoping():
    G = 0

    def inner():
        nonlocal G
        G = 2

    inner()
    return G


def grouped():
    try:
        risky()
    except* OSError:
        pass
    return 1


def decorated():
    @app.route("/x")
    def handler():
        return 1

    return handler


def uses():
    Base.stat("x")
    Base.build("y")
    keyword(1, b=2, kw=3)
    return Record("n")
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
            isinstance(find.get("enclosingClass"), str)
            and find.get("enclosingClass").endswith("UserStore")
            and find.get("enclosingClass") != "UserStore",
            "a method must name the class it belongs to, and the class's identity is its QUALIFIED name: the module it lives in plus its name",
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
        # match this one. The owner is the class itself: the constructor is
        # lowered as `<Class>.<init>`, so this is the name that reaches the
        # constructor's body.
        construction = next((i for i in instructions if i["kind"] == "NEW"), None)
        check(
            construction is not None,
            "the construction of a class this module declares must be a NEW",
        )
        check(
            construction["calleeSig"] == "UserStore.<init>/0",
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

        # A local that shadows a class is not a construction. Python decides
        # the scope of a name when the function is compiled, so the local is
        # the local everywhere in the body, and the call is a call of a
        # value. A construction here would name a constructor that is never
        # called -- and a catalogue entry that claims constructors would
        # claim it.
        shadowed_function = next((f for f in functions if f["name"] == "shadowed"), None)
        check(shadowed_function is not None, "the shadowing function must be in the stream")
        shadowed_instructions = shadowed_function["instructions"]
        check(
            not any(i["kind"] == "NEW" for i in shadowed_instructions),
            "a call of a local that shadows a class must not be a construction",
        )
        check(
            any(i["kind"] == "UNINTERPRETED" for i in shadowed_instructions),
            "a call of a local that shadows a class is a call of a value, so it is a black box",
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

        # The second file: the constructs whose shape has to be right.
        second_path = os.path.join(directory, "shapes.py")
        with open(second_path, "w", encoding="utf-8") as handle:
            handle.write(SECOND_SOURCE)
        write_frame(
            child.stdin,
            json.dumps(
                {
                    "op": Op.TRANSLATE,
                    "protocolVersion": PROTOCOL_VERSION,
                    "irVersion": IR_VERSION,
                    "path": second_path,
                    "language": "PYTHON3",
                }
            ),
        )
        response = read_frame(child.stdout)
        check(response.get("ok") is True, "the second file must translate: %s" % response.get("error", ""))
        second_functions = response.get("functions") or []

        def second_function(name):
            return next((f for f in second_functions if f["name"] == name), None)

        # A BRANCH carries a GUARD: the structured condition of the branch.
        # A branch with no guard tells the solver nothing.
        run = second_function("run")
        check(run is not None, "the method must be in the stream")
        branches = [i for i in run["instructions"] if i["kind"] == "BRANCH"]
        check(branches, "a branch must be in the stream")
        check(
            all(b.get("guard") for b in branches),
            "every branch must carry a guard",
        )
        check(
            any(b["guard"]["kind"] == "Truthiness" for b in branches),
            "a truthiness test must be a Truthiness guard",
        )

        # A LABEL and a JUMP carry a LABEL REFERENCE: what the label IS.
        labels = [i for i in run["instructions"] if i["kind"] == "LABEL"]
        check(labels, "a label must be in the stream")
        check(
            all(i.get("labelRef") and i["labelRef"].get("name") == i["label"] for i in labels),
            "a label's reference must name the label",
        )

        # A short-circuit operator is a BRANCH CHAIN: the right operand is
        # only evaluated when the left one says it has to be.
        truthy = second_function("truthy")
        check(truthy is not None, "the function must be in the stream")
        truthy_branches = [i for i in truthy["instructions"] if i["kind"] == "BRANCH"]
        check(truthy_branches, "a short-circuit operator must be a branch chain")
        check(
            not any(
                i["kind"] == "BINOP" and i.get("binop") in ("LAND", "LOR")
                for i in truthy["instructions"]
            ),
            "a short-circuit operator must not be a single binop",
        )

        # A loop's ELSE runs when the loop finished WITHOUT a break, so the
        # break's target has to skip it: the loop's BODY end and the loop's
        # END are two different labels.
        looping = second_function("looping")
        check(looping is not None, "the function must be in the stream")
        kinds = {i.get("labelRef", {}).get("kind") for i in looping["instructions"]}
        check("LOOP_BODY_END" in kinds, "a loop must have a body end")
        check("LOOP_END" in kinds, "a loop must have an end")

        # The iteration protocol: the element IS the result of __next__.
        # Python's __next__ returns the element -- it is not a pair of a value
        # and a done flag, the way JavaScript's iterator result is. Reading a
        # "value" out of it says the element is a MEMBER of what __next__
        # returned, and the engine's iterator propagation taints the RESULT:
        # so the element's taint would stop right here, silently.
        next_call = next(
            (
                i
                for i in looping["instructions"]
                if i["kind"] == "CALL"
                and str(i.get("calleeSig") or "").endswith(".__next__/0")
            ),
            None,
        )
        check(next_call is not None, "the iteration protocol must call __next__")
        element = next_call["dest"]["name"]
        check(
            not any(
                i["kind"] == "ARRAYLOAD"
                and i.get("lhs1", {}).get("name") == element
                for i in looping["instructions"]
            ),
            "nothing may read a member out of what __next__ returned: it returns the element",
        )
        item_assignment = next(
            (
                i
                for i in looping["instructions"]
                if i["kind"] == "MOVE" and i.get("dest", {}).get("name") == "item"
            ),
            None,
        )
        check(
            item_assignment is not None and item_assignment["lhs1"]["name"] == element,
            "the loop variable must be the result of __next__",
        )

        # An EXCEPTION REGION is filled in: without it the catch body is
        # never reached by the analysis.
        guarded = second_function("guarded")
        check(guarded is not None, "the function must be in the stream")
        check(
            guarded.get("tryRegions"),
            "a try must produce a try region",
        )
        region = guarded["tryRegions"][0]
        check(region.get("catchLabels"), "a try region's catch labels must be filled in")
        check(
            any(i["kind"] == "CATCH_HANDLER" for i in guarded["instructions"]),
            "a catch must produce a catch handler",
        )
        check(
            any(i["kind"] == "FINALLY_ENTER" for i in guarded["instructions"]),
            "a finally must produce a finally enter",
        )

        # EVERY case body of a match is lowered: a body that is not lowered
        # is a body whose findings are not there.
        matched = second_function("matched")
        check(matched is not None, "the function must be in the stream")
        check(
            any(i["kind"] == "THROW" for i in matched["instructions"]),
            "a match that matches nothing must throw",
        )
        check(
            not any(i["kind"] == "HAVOC" for i in matched["instructions"]),
            "a match must not leave an unknown value",
        )

        # A comprehension's FILTER is lowered, and its loop variable does
        # not leak into the scope around it.
        comprehended = second_function("comprehended")
        check(comprehended is not None, "the function must be in the stream")
        check(
            any(i["kind"] == "BRANCH" for i in comprehended["instructions"]),
            "a comprehension's filter must be lowered",
        )
        check(
            not any(
                i.get("dest", {}).get("name") == "item" for i in comprehended["instructions"]
            ),
            "a comprehension's loop variable must not leak into the outer scope",
        )

        # An f-string's format specification is evaluated.
        formatted = second_function("formatted")
        check(formatted is not None, "the function must be in the stream")
        check(
            any(i["kind"] == "UNARYOP" and i.get("unop") == "CAST" for i in formatted["instructions"]),
            "an f-string's conversion must be a cast",
        )

        # A slice's three parts are evaluated separately.
        sliced = second_function("sliced")
        check(sliced is not None, "the function must be in the stream")
        check(
            not any(i["kind"] == "HAVOC" for i in sliced["instructions"]),
            "a slice must not be an unknown value",
        )

        # A membership test is a comparison, not an unknown value.
        contained = second_function("contained")
        check(contained is not None, "the function must be in the stream")
        check(
            any(i["kind"] == "BINOP" for i in contained["instructions"]),
            "an in test must be a comparison",
        )
        check(
            not any(i["kind"] == "HAVOC" for i in contained["instructions"]),
            "an in test must not be an unknown value",
        )

        # A power assignment is not silently a multiplication: the operator
        # the TAC does not have is reported.
        powered = second_function("powered")
        check(powered is not None, "the function must be in the stream")
        check(
            any(
                "power" in (d.get("detail") or "") for d in powered.get("drops") or []
            ),
            "a power assignment must be reported as an approximation",
        )

        # A STATIC method has no receiver, so its arity is the number of
        # the user's arguments.
        stat = next((f for f in second_functions if f["name"] == "stat"), None)
        check(stat is not None, "the static method must be in the stream")
        check(
            stat.get("memberKind") == "METHOD",
            "a static method must say that it is a method",
        )

        # A CLASS's body is a `<clinit>`, and the CONSTRUCTOR is `<init>`:
        # a construction names the constructor as `<Class>.<init>/n`.
        clinit = next((f for f in second_functions if f["name"] == "<clinit>"), None)
        check(clinit is not None, "the class body must be in the stream")
        check(
            clinit.get("memberKind") == "FIELD_INITIALIZER",
            "the class body must say that it initialises the fields",
        )
        constructor = next((f for f in second_functions if f["name"] == "<init>"), None)
        check(constructor is not None, "the constructor must be in the stream")
        check(
            constructor.get("memberKind") == "CONSTRUCTOR",
            "the constructor must say that it is a constructor",
        )

        # Every function carries a RETURN TYPE and a MEMBER KIND.
        for function in second_functions:
            check(
                "returnType" in function,
                "every function must carry a return type: %s" % function.get("name"),
            )
            check(
                function.get("memberKind"),
                "every function must carry a member kind: %s" % function.get("name"),
            )
            check(
                isinstance(function.get("drops"), list),
                "every function must carry its drops: %s" % function.get("name"),
            )
            check(
                isinstance(function.get("parameters"), list),
                "every function must carry its parameters: %s" % function.get("name"),
            )
            check(
                isinstance(function.get("scopeBindings"), list),
                "every function must carry its scope bindings: %s" % function.get("name"),
            )

        # The third file: the constructs whose shape has to be right.
        third_path = os.path.join(directory, "third.py")
        with open(third_path, "w", encoding="utf-8") as handle:
            handle.write(THIRD_SOURCE)
        write_frame(
            child.stdin,
            json.dumps(
                {
                    "op": Op.TRANSLATE,
                    "protocolVersion": PROTOCOL_VERSION,
                    "irVersion": IR_VERSION,
                    "path": third_path,
                    "language": "PYTHON3",
                }
            ),
        )
        response = read_frame(child.stdout)
        check(response.get("ok") is True, "the third file must translate: %s" % response.get("error", ""))
        third_functions = response.get("functions") or []

        def third_function(name):
            return next((f for f in third_functions if f["name"] == name), None)

        # A STATIC method is called on the CLASS, so the receiver slot holds
        # the class object and the arity is the number of the user's
        # arguments -- the receiver is not one of them.
        uses = third_function("uses")
        check(uses is not None, "the function must be in the stream")
        stat_call = next(
            (i for i in uses["instructions"] if i.get("calleeSig") == "Base.stat/1"),
            None,
        )
        check(stat_call is not None, "a static method must be called on the class")
        check(
            stat_call["args"][0].get("name", "").startswith("t_cls_"),
            "the receiver slot of a static method must hold the class object",
        )

        # A CLASS method is called on the class as well: its receiver is the
        # CLASS and not an instance.
        build_call = next(
            (i for i in uses["instructions"] if i.get("calleeSig") == "Base.build/1"),
            None,
        )
        check(build_call is not None, "a class method must be called on the class")
        check(
            build_call["args"][0].get("name", "").startswith("t_cls_"),
            "the receiver slot of a class method must hold the class object",
        )

        # The arity of a callee key is the arity of the SIGNATURE: a rule
        # that matches on the key has to match on the same function.
        keyword_call = next(
            (i for i in uses["instructions"] if i.get("calleeSig", "").endswith("keyword/3")),
            None,
        )
        check(keyword_call is not None, "a call must carry the arity of the declaration")
        keyword_function = third_function("keyword")
        check(
            keyword_function is not None
            and keyword_function["signature"].count("any") >= 3,
            "the signature must name the parameters: %s"
            % (keyword_function or {}).get("signature"),
        )

        # A DATACLASS gets its constructor from the decorator when it does
        # not write one itself: the decorator is lowered and the constructor
        # is a function of its own.
        record_clinit = next(
            (
                f
                for f in third_functions
                if f["name"] == "<clinit>"
                and any(
                    "dataclass" in (i.get("calleeSig") or "") for i in f["instructions"]
                )
            ),
            None,
        )
        check(record_clinit is not None, "a dataclass's decorator must be lowered")
        record_init = next(
            (
                f
                for f in third_functions
                if f["name"] == "<init>"
                and "Record" in (f.get("signature") or "")
            ),
            None,
        )
        check(record_init is not None, "a dataclass must have a constructor")
        check(
            record_init.get("memberKind") == "CONSTRUCTOR",
            "the generated constructor must say that it is a constructor",
        )
        check(
            any(i["kind"] == "ARRAYSTORE" for i in record_init["instructions"]),
            "the generated constructor must write the fields into the instance",
        )

        # A DEFAULT value that is computed is evaluated where the
        # DECLARATION is written, so the expression is lowered in the
        # stream that declaration is in -- and not in the declared
        # function's own.  A default that read a variable of the
        # enclosing scope was, before, lowered into the declared
        # function's own stream, where that variable does not exist.
        defaulted = third_function("defaulted")
        check(defaulted is not None, "the function must be in the stream")
        module_stream = third_function("<module>")
        check(module_stream is not None, "the module's own stream must be in the stream")
        check(
            any("getcwd" in (i.get("calleeSig") or "") for i in module_stream["instructions"]),
            "a default value that is computed must be lowered where the declaration is written",
        )
        check(
            not any("getcwd" in (i.get("calleeSig") or "") for i in defaulted["instructions"]),
            "and it must not be lowered in the declared function's own stream",
        )

        # A `with` binds the value `__enter__` returns, and it calls
        # `__exit__` when the block is done.
        reading = third_function("reading")
        check(reading is not None, "the function must be in the stream")
        check(
            any("__enter__" in (i.get("calleeSig") or "") for i in reading["instructions"]),
            "a with must call __enter__",
        )
        check(
            any("__exit__" in (i.get("calleeSig") or "") for i in reading["instructions"]),
            "a with must call __exit__",
        )
        enter = next(
            (i for i in reading["instructions"] if "__enter__" in (i.get("calleeSig") or "")),
            None,
        )
        bound = next(
            (
                i
                for i in reading["instructions"]
                if i["kind"] == "MOVE" and i.get("dest", {}).get("name") == "fh"
            ),
            None,
        )
        check(
            bound is not None and enter is not None,
            "the name of a with must be bound to the value __enter__ returns",
        )

        # A `nonlocal` name belongs to the scope AROUND the function.
        scoping = third_function("scoping")
        check(scoping is not None, "the function must be in the stream")
        inner = third_function("inner")
        check(inner is not None, "the inner function must be in the stream")
        check(
            any(b.get("name") == "G" for b in inner.get("scopeBindings") or []),
            "a nonlocal name must be bound to the scope around the function",
        )

        # An `except*` handler catches a GROUP of exceptions, and the TAC
        # catches one: it is lowered and the difference is recorded.
        grouped = third_function("grouped")
        check(grouped is not None, "the function must be in the stream")
        check(
            any(i["kind"] == "CATCH_HANDLER" for i in grouped["instructions"]),
            "an except* must be lowered",
        )
        check(
            any(
                "group" in (d.get("detail") or "").lower()
                for d in grouped.get("drops") or []
            ),
            "an except* must say that it catches a group",
        )

        # A DECORATOR's name is in a callee key, so that a rule that names
        # the decorator can match it.
        decorated = third_function("decorated")
        check(decorated is not None, "the function must be in the stream")
        check(
            any("route" in (i.get("calleeSig") or "") for i in decorated["instructions"]),
            "a decorator's name must be in a callee key",
        )

        # The TYPE SURFACE: a class's base is a fact about the class. The
        # types are not on the wire yet -- the entry point has to carry them
        # -- so they are read from the translator here.
        translator = Translator(module_name="smoke", text=THIRD_SOURCE)
        translator.translate_module(ast.parse(THIRD_SOURCE))
        child_type = next(
            (t for t in translator.type_declarations if t.get("simpleName") == "Child"),
            None,
        )
        check(child_type is not None, "a class must declare itself")
        check(
            child_type.get("superType") == "Base",
            "a class's base must be declared: %s" % child_type.get("superType"),
        )

        print("ok: the sidecar answers over the wire, and the TAC has the expected shape")
    finally:
        child.kill()


if __name__ == "__main__":
    main()
