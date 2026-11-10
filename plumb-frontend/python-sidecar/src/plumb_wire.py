"""The wire model of the TAC instruction stream.

These are the TAC's own shapes, but they are a WIRE MODEL, not the IR
itself: they live on the Python side of a process boundary, so they cannot
be the Java classes. The Java side maps them into
``org.plumb.tac.ir.TacInstruction`` and friends -- see
``org.plumb.tac.frontend.sidecar.SidecarTacMapper``.

The field names are the JSON property names, so they are lower-camel-cased
to match the wire format.

See PROTOCOL.md for the contract.
"""

# The wire protocol version. Bumped when the request or response shape
# changes in a way an older frontend would misinterpret.
PROTOCOL_VERSION = 1

# The IR version this sidecar was built against. It travels with the wire
# so that a sidecar built against a different IR is refused rather than
# half-understood.
IR_VERSION = 1


class Kind:
    """The TAC instruction kinds, by name.

    The list is the TAC's own. A kind this sidecar does not emit is still
    declared, because the Java side switches on the same enum and a typo in
    a string literal would otherwise surface as an "unknown instruction" at
    run time.
    """

    MOVE = "MOVE"
    BINOP = "BINOP"
    UNARYOP = "UNARYOP"
    CALL = "CALL"
    NEW = "NEW"
    NEWARRAY = "NEWARRAY"
    ARRAYLOAD = "ARRAYLOAD"
    ARRAYSTORE = "ARRAYSTORE"
    FIELDLOAD = "FIELDLOAD"
    FIELDSTORE = "FIELDSTORE"
    INSTANCEOF = "INSTANCEOF"
    BRANCH = "BRANCH"
    JUMP = "JUMP"
    LABEL = "LABEL"
    RETURN = "RETURN"
    THROW = "THROW"
    NOP = "NOP"
    CATCH_HANDLER = "CATCH_HANDLER"
    FINALLY_ENTER = "FINALLY_ENTER"
    NULL_CHECK = "NULL_CHECK"
    HAVOC = "HAVOC"
    UNINTERPRETED = "UNINTERPRETED"


class Type:
    """The TAC type kinds, by name.

    UNKNOWN is not one of the fourteen: it is what a sidecar emits when it
    cannot resolve a type at all, and the Java side maps it onto the IR's
    UNKNOWN.
    """

    BOOLEAN = "BOOLEAN"
    BYTE = "BYTE"
    SHORT = "SHORT"
    CHAR = "CHAR"
    INT = "INT"
    LONG = "LONG"
    FLOAT = "FLOAT"
    DOUBLE = "DOUBLE"
    DECIMAL = "DECIMAL"
    STRING = "STRING"
    CLASS = "CLASS"
    ARRAY = "ARRAY"
    NULL = "NULL"
    VOID = "VOID"
    UNKNOWN = "UNKNOWN"


class Const:
    """The constant kinds."""

    INT = "INT"
    LONG = "LONG"
    FLOAT = "FLOAT"
    DOUBLE = "DOUBLE"
    BOOL = "BOOL"
    CHAR = "CHAR"
    STRING = "STRING"
    NULL = "NULL"


class Precision:
    """The precision levels a function can carry.

    SOURCE_UNKNOWN is deliberately absent: the TAC's own precision enum has
    no third source-level value, and inventing one here would produce a
    precision string the Java side cannot map.
    """

    FULL = "SOURCE_FULL"
    LOWERED = "SOURCE_LOWERED"


class Problem:
    """The problem kinds a function or a project can report."""

    PARSE = "PARSE_PROBLEM"
    TRANSLATION = "TRANSLATION_ERROR"
    UNHANDLED_NODE = "UNHANDLED_NODE"
    SOURCE_LOWERED = "SOURCE_LOWERED"


class Op:
    """The operations the Java side may ask for."""

    OPEN_PROJECT = "openProject"
    TRANSLATE = "translate"
    CLOSE_PROJECT = "closeProject"
    PING = "ping"
    SHUTDOWN = "shutdown"


def stamp():
    """The response's version fields.

    Every response carries them, including the failed ones: a failure
    response is exactly the one a caller reads when something has gone
    wrong, and it is the worst place to discover that the handshake failed.
    """
    return {"protocolVersion": PROTOCOL_VERSION, "irVersion": IR_VERSION}


def var_op(name, type_=None, is_temp=False):
    """A variable operand.

    The type is optional; an absent type means UNKNOWN. ``is_temp`` marks a
    synthetic temporary.
    """
    op = {"kind": "VAR", "name": name}
    if type_:
        op["type"] = type_
    if is_temp:
        op["isTemp"] = True
    return op


def const_op(const_kind, value=None):
    """A constant operand."""
    op = {"kind": "CONST", "constKind": const_kind}
    if value is not None:
        op["value"] = value
    return op
