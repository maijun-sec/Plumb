// The wire model of the TAC instruction stream, as the ArkTS sidecar speaks it.
//
// This is a WIRE MODEL, not the IR itself. The IR lives in Java
// (org.plumb.tac.ir), and the two cannot be the same classes because they sit
// on opposite sides of a process boundary. The Java side maps every field here
// onto the IR's own type.
//
// The ArkTS sidecar is its OWN npm project. It does not share a file with the
// TypeScript sidecar, and it does not share a file with the Go or the C# one.
// The reason is the compiler: ArkTS is read by a different compiler with a
// different AST, and the two sidecars would have to agree on a file that
// neither of them owns. A copy that can drift is still better than a shared
// file that forces one language's compiler into the other's shape.
//
// See PROTOCOL.md for the contract. The field names are the JSON property
// names, so they are lower-camel-cased to match the wire format.

// The wire protocol version. Bumped when the request or response shape changes
// in a way an older frontend would misinterpret.
export const PROTOCOL_VERSION = 1;

// The IR version this sidecar was built against. It travels with the wire so
// that a sidecar built against a different IR is refused rather than
// half-understood.
export const IR_VERSION = 1;

// The TAC instruction kinds, by name.
//
// The list is the TAC's own. A kind this sidecar does not emit is still
// declared, because the Java side switches on the same enum: a typo in a
// string literal would otherwise surface as an "unknown instruction" at run
// time, which is the one place where a typo must not hide.
export const KIND = {
  MOVE: "MOVE",
  BINOP: "BINOP",
  UNARYOP: "UNARYOP",
  CALL: "CALL",
  NEW: "NEW",
  NEWARRAY: "NEWARRAY",
  ARRAYLOAD: "ARRAYLOAD",
  ARRAYSTORE: "ARRAYSTORE",
  FIELDLOAD: "FIELDLOAD",
  FIELDSTORE: "FIELDSTORE",
  INSTANCEOF: "INSTANCEOF",
  BRANCH: "BRANCH",
  JUMP: "JUMP",
  LABEL: "LABEL",
  RETURN: "RETURN",
  THROW: "THROW",
  NOP: "NOP",
  CATCH_HANDLER: "CATCH_HANDLER",
  FINALLY_ENTER: "FINALLY_ENTER",
  NULL_CHECK: "NULL_CHECK",
  HAVOC: "HAVOC",
  UNINTERPRETED: "UNINTERPRETED",
};

// The TAC type kinds, by name.
//
// UNKNOWN is not one of the fourteen: it is what a sidecar emits when it
// cannot resolve a type at all, and the Java side maps it onto the IR's own
// UNKNOWN.
export const TYPE = {
  BOOLEAN: "BOOLEAN",
  BYTE: "BYTE",
  SHORT: "SHORT",
  CHAR: "CHAR",
  INT: "INT",
  LONG: "LONG",
  FLOAT: "FLOAT",
  DOUBLE: "DOUBLE",
  DECIMAL: "DECIMAL",
  STRING: "STRING",
  CLASS: "CLASS",
  ARRAY: "ARRAY",
  NULL: "NULL",
  VOID: "VOID",
  UNKNOWN: "UNKNOWN",
};

// The constant kinds.
export const CONST = {
  INT: "INT",
  LONG: "LONG",
  FLOAT: "FLOAT",
  DOUBLE: "DOUBLE",
  BOOL: "BOOL",
  CHAR: "CHAR",
  STRING: "STRING",
  NULL: "NULL",
};

// The precision levels a function can carry.
//
// SOURCE_UNKNOWN is deliberately absent: the TAC's own precision enum has no
// third source-level value, and inventing one here would produce a precision
// string the Java side cannot map.
export const PRECISION = {
  FULL: "SOURCE_FULL",
  LOWERED: "SOURCE_LOWERED",
};

// The problem kinds a function or a project can report.
export const PROBLEM = {
  PARSE: "PARSE_PROBLEM",
  TRANSLATION: "TRANSLATION_ERROR",
  UNHANDLED_NODE: "UNHANDLED_NODE",
  SOURCE_LOWERED: "SOURCE_LOWERED",
};

// The operations the Java side may ask for.
export const OP = {
  OPEN_PROJECT: "openProject",
  TRANSLATE: "translate",
  CLOSE_PROJECT: "closeProject",
  PING: "ping",
  SHUTDOWN: "shutdown",
};

/**
 * The response's version fields.
 *
 * Every response carries them, including the failed ones: a failure response
 * is exactly the one a caller reads when something has gone wrong, and it is
 * the worst place to discover that the handshake failed.
 */
export function stamp() {
  return { protocolVersion: PROTOCOL_VERSION, irVersion: IR_VERSION };
}

/**
 * A variable operand.
 *
 * The type is optional; an absent type means UNKNOWN. `isTemp` marks a
 * synthetic temporary.
 */
export function varOp(name, type, isTemp = false) {
  const op = { kind: "VAR", name };
  if (type) op.type = type;
  if (isTemp) op.isTemp = true;
  return op;
}

/** A constant operand. */
export function constOp(constKind, value) {
  const op = { kind: "CONST", constKind };
  if (value !== undefined) op.value = value;
  return op;
}
