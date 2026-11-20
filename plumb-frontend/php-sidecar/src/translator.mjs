// The translator: PHP source -> Plumb's TAC IR.
//
// WHAT THIS IS. A frontend lowers a source language into the TAC, the
// three-address instruction stream the analysis engine consumes. The TAC is
// language-neutral: it says "move", "call", "read a field", "index a
// container", and nothing about what a variable means.
//
// THE PARSER. `php-parser` parses the source. It is a port of the reference
// PHP parser, and its AST is the reference AST, so the node shapes this
// translator reads are the ones the PHP community writes rules about.
//
// THE ONE LANGUAGE-SPECIFIC DECISION. In PHP an object is a NAMESPACE, not a
// record: `$o->name` and `$o['name']` are the same location, and `$items[0]`
// is `$items["0"]`. So a property access is lowered as a CONTAINER ACCESS
// with a string key -- an ARRAYLOAD / ARRAYSTORE whose index operand is the
// key -- and not as a field access. The engine's keyed namespace is what
// gives that access its identity: the pair (object, key).
//
// THE OTHER LANGUAGE-SPECIFIC DECISION. PHP qualifies a name with a
// namespace and a class with `::`, and the callee signature carries that
// qualified name as PHP writes it: `\system/1` for a global function,
// `App\Services\helper/1` for a namespaced one, `App\Mailer::send/1` for a
// method. A rule that matches a PHP call matches a PHP name.
//
// EVERY FAILURE IS REPORTED. A construct this translator cannot lower becomes
// a problem on the function, and the affected value becomes a HAVOC. It never
// becomes a silent "nothing to report": a sidecar that drops a construct
// quietly would leave the engine believing the program had no findings.

import {
  CONST,
  KIND,
  PRECISION,
  PROBLEM,
  TYPE,
  constOp,
  varOp,
} from "./protocol.mjs";

/**
 * The TAC's binary operators, by the PHP operator.
 *
 * The key is the operator's SPELLING, because that is what the parser hands
 * out: an operator node carries the operator's text, not a number. A lookup
 * that always misses does not look like a lookup at all: every binary
 * expression would become an unknown value, and the taint that flowed
 * through it would stop there.
 */
const BINARY_OPERATORS = {
  "+": "ADD",
  "-": "SUB",
  "*": "MUL",
  "/": "DIV",
  "%": "MOD",
  "**": "MUL",
  ".": "ADD",
  "==": "EQ",
  "===": "EQ",
  "!=": "NE",
  "!==": "NE",
  "<>": "NE",
  "<": "LT",
  "<=": "LE",
  ">": "GT",
  ">=": "GE",
  "&&": "LAND",
  "||": "LOR",
  "and": "LAND",
  "or": "LOR",
  "xor": "XOR",
  "&": "AND",
  "|": "OR",
  "^": "XOR",
  "<<": "SHL",
  ">>": "SHR",
  "??": "LOR",
  "<=>": "EQ",
};

/**
 * The operators the TAC does not have.
 *
 * Each is mapped onto the closest thing the TAC has and REPORTED: the
 * function is still lowered -- the taint of the operands reaches the result
 * either way -- but the reader is told that the operator in the TAC is not
 * the operator in the source.
 *
 * The table is EMPTY: the null-coalescing, the three-way comparison and the
 * power are all expanded into real instructions, so nothing is approximated
 * any more.
 */
const APPROXIMATED_OPERATORS = {};

/**
 * The compound assignments, by the PHP operator.
 *
 * A compound assignment reads its target, combines it, and writes it back.
 * The read and the write are separate instructions, because the engine tracks
 * the value of a location, not the statement that changed it.
 */
const COMPOUND_OPERATORS = {
  "+=": "ADD",
  "-=": "SUB",
  "*=": "MUL",
  "/=": "DIV",
  "%=": "MOD",
  ".=": "ADD",
  "&=": "AND",
  "|=": "OR",
  "^=": "XOR",
  "<<=": "SHL",
  ">>=": "SHR",
  "??=": "LOR",
};

/**
 * The TAC's unary operators, by the PHP operator.
 *
 * There is no unary plus in the TAC, and there does not need to be: a unary
 * plus is a no-op, so the operand is the result.
 */
const UNARY_OPERATORS = {
  "-": "NEG",
  "!": "NOT",
  "~": "BITNOT",
};

// The owner of a CALL that stands in for something the TAC has no
// counterpart for.
//
// It is not a real namespace: a sink catalogue written against "system/1"
// must not match "<php>.include/1" by accident, and a checker that prints
// the callee can see at a glance that the call is a stand-in.
const SYNTHETIC_OWNER = "<php>";

/**
 * The precision a function carries when a drop says that SEMANTICS were lost.
 *
 * The TAC's own precision enum has three source-level values and the wire
 * protocol's PRECISION table lists two of them. The third one is spelled here
 * rather than in the protocol module, because the protocol module is not this
 * file's to change.
 */
const PRECISION_DEGRADED = "SOURCE_DEGRADED";

/**
 * The impact of a drop.
 *
 * The engine derives a function's precision from its drops, so the impact is
 * not a decoration:
 *
 *   SEMANTICS  the function's meaning is not whole (an unknown call target,
 *              an `eval`, a variable variable) => SOURCE_DEGRADED
 *   STRUCTURE  only the shape is not whole (a loop's `else`, a set's order)
 *              => SOURCE_LOWERED
 *   VALUE      one value is not known, and nothing else is => unchanged
 *   TYPE       one type is not known, and nothing else is => unchanged
 */
export const DROP_IMPACT = {
  SEMANTICS: "SEMANTICS",
  STRUCTURE: "STRUCTURE",
  VALUE: "VALUE",
  TYPE: "TYPE",
};

/**
 * The guard kinds.
 *
 * A guard is the STRUCTURED condition of a branch. The engine reads it to
 * build a path condition, so a branch whose condition is only a boolean
 * temporary tells the solver nothing.
 */
export const GUARD = {
  COMPARE: "Compare",
  TRUTHINESS: "Truthiness",
  MEMBERSHIP: "Membership",
  TYPE_TEST: "TypeTest",
  ALWAYS: "Always",
  PROPERTY_PRESENCE: "PropertyPresence",
};

/**
 * The label kinds.
 *
 * A label's kind says what the label IS, so that the engine can tell a loop's
 * end from a loop's body end -- Python's `else` hangs off the body end, and
 * a `break` has to skip it.
 */
export const LABEL_KIND = {
  FUNCTION_ENTRY: "FUNCTION_ENTRY",
  FUNCTION_EXIT: "FUNCTION_EXIT",
  BLOCK: "BLOCK",
  LOOP_HEAD: "LOOP_HEAD",
  LOOP_BODY_END: "LOOP_BODY_END",
  LOOP_END: "LOOP_END",
  SWITCH_HEAD: "SWITCH_HEAD",
  SWITCH_CASE: "SWITCH_CASE",
  SWITCH_END: "SWITCH_END",
  CATCH_ENTRY: "CATCH_ENTRY",
  FINALLY_ENTRY: "FINALLY_ENTRY",
};

/**
 * The member kinds.
 *
 * A member kind says what a function IS inside its owner: a method, the
 * constructor, or the function that runs a class body's field initializers.
 */
export const MEMBER_KIND = {
  MODULE: "MODULE",
  METHOD: "METHOD",
  CONSTRUCTOR: "CONSTRUCTOR",
  FIELD_INITIALIZER: "FIELD_INITIALIZER",
  GETTER: "GETTER",
  SETTER: "SETTER",
  DESTRUCTOR: "DESTRUCTOR",
};

/**
 * The scope binding kinds.
 *
 * A scope binding says that a name belongs to a scope other than the one the
 * function's own locals live in: the module's scope (`global`), the
 * function's own persistent storage (`static`), or the scope around the
 * function (a capture).
 */
export const SCOPE_KIND = {
  LOCAL: "LOCAL",
  GLOBAL: "GLOBAL",
  STATIC_LOCAL: "STATIC_LOCAL",
  CAPTURED: "CAPTURED",
  PARAM: "PARAM",
  DECLARE: "DECLARE",
};

/**
 * The PHP operators that compare two values.
 *
 * They are the ones that can become a Compare guard: the truth of a branch
 * whose condition is one of them is a fact about two values, and the engine
 * can use that fact.
 */
const COMPARISON_OPERATORS = new Set([
  "==",
  "===",
  "!=",
  "!==",
  "<>",
  "<",
  "<=",
  ">",
  ">=",
]);

/**
 * The PHP operators that short-circuit.
 *
 * Their right operand is only evaluated when the left one says it has to be,
 * so they cannot be a single BINOP: the right operand's evaluation has to be
 * on the branch that reaches it.
 */
const SHORT_CIRCUIT_OPERATORS = new Set(["&&", "||", "and", "or"]);

// The PHP type names that are scalars.
const SCALAR_TYPES = new Set([
  "int",
  "integer",
  "float",
  "double",
  "string",
  "bool",
  "boolean",
  "array",
  "iterable",
  "object",
  "mixed",
  "void",
  "null",
  "callable",
  "false",
  "true",
]);

/**
 * The object an arrow function is bound to.
 *
 * An arrow function defined in a method sees that method's `$this`.  It is not
 * a variable the arrow function copies, so it is not one of its captures.
 */
const THE_OBJECT = "this";

/**
 * The superglobals: the variables that are in scope in every scope.
 *
 * That is what makes a superglobal a superglobal, so an arrow function does
 * not capture one either -- it is always there.
 *
 * The names are the ones the parser writes, and they carry no sigil: `$_GET`
 * is the name `_GET`.
 */
const SUPERGLOBALS = new Set([
  "GLOBALS",
  "argc",
  "argv",
  "_GET",
  "_POST",
  "_SERVER",
  "_COOKIE",
  "_FILES",
  "_ENV",
  "_REQUEST",
  "_SESSION",
]);

/**
 * The precision a function carries, given its drops.
 *
 * A drop says what was lost. A drop that cost the function its SEMANTICS
 * leaves it DEGRADED; a drop that cost it only its SHAPE leaves it LOWERED;
 * a drop that cost one value, or one type, leaves it whole -- because the
 * analysis still knows everything it needs to know about every other value.
 *
 * @param {object[]} drops the function's drops
 * @return {string} the precision
 */
function precisionOf(drops) {
  let lowered = false;
  for (const drop of drops ?? []) {
    if (drop.impact === DROP_IMPACT.SEMANTICS) return PRECISION_DEGRADED;
    if (drop.impact === DROP_IMPACT.STRUCTURE) lowered = true;
  }
  return lowered ? PRECISION.LOWERED : PRECISION.FULL;
}

/**
 * The text of an attribute's argument.
 *
 * PHP requires an attribute's arguments to be constant expressions, so the
 * text of the value is the whole of it.
 *
 * @param {object} value the argument
 * @return {string} the text
 */
function attributeValueText(value) {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (!value || typeof value !== "object") return "";
  if (value.kind === "string" || value.kind === "number" || value.kind === "boolean") {
    return String(value.value);
  }
  if (value.kind === "identifier") return value.name ?? "";
  return value.kind ?? "";
}

/**
 * The construct a problem names, when the problem does not name one itself.
 *
 * A construct is a stable word, so that a gate can compare the words the
 * sidecar actually emits with the words the vocabulary lists.
 *
 * @param {string} kind the problem kind
 * @param {object} node the node the problem is about
 * @return {string} the construct
 */
function constructOf(kind, node) {
  const nodeClass = node?.kind ?? "node";
  if (kind === PROBLEM.UNHANDLED_NODE) return `unhandled.${nodeClass}`;
  if (kind === PROBLEM.PARSE || kind === PROBLEM.TRANSLATION) return `parse.${nodeClass}`;
  return `lowered.${nodeClass}`;
}

/**
 * The impact a problem kind has, when the problem does not say itself.
 *
 * A node the translator does not understand at all costs the function its
 * semantics; a construct it understood but could not shape exactly costs it
 * only its shape.
 *
 * @param {string} kind the problem kind
 * @return {string} the impact
 */
function impactOfProblem(kind) {
  if (kind === PROBLEM.SOURCE_LOWERED) return DROP_IMPACT.STRUCTURE;
  return DROP_IMPACT.SEMANTICS;
}

/**
 * The value domain of a PHP integer.
 *
 * PHP's int is platform dependent -- 32 bits on some builds and 64 on
 * others -- and 64 is the conservative choice: a wider range admits more
 * values, so a check that holds for 64 bits holds for 32.
 */
const INTEGRAL_DOMAIN = { domain: "INTEGRAL", bits: 64, signed: true };

/**
 * The value domain of a PHP float.
 *
 * A PHP float is an IEEE 754 double, always.
 */
const FLOATING_DOMAIN = { domain: "FLOATING", bits: 53, exponentBits: 11 };

/**
 * The rewrite that makes a simple interpolation with a quoted subscript
 * parseable.
 *
 * `php-parser` 3.7.0 cannot read `"$_GET['x']"`: the lexer puts the quote
 * of the subscript into the "whitespace" class of the `ST_VAR_OFFSET`
 * state, so the parser sees a string token where it expects a name, and
 * the WHOLE FILE fails to parse. A file that fails to parse produces no
 * functions at all, so every sink in it disappears from the scan.
 *
 * The fix is to rewrite the simple interpolation into the COMPLEX one:
 * `"$_GET['x']"` becomes `"{$_GET['x']}"`. The complex form goes through a
 * different lexer state, where the quote is an ordinary string token, and
 * the parser reads it.
 *
 * The rewrite makes the source longer, so the positions of everything after
 * it move. The shifts are recorded, and `position()` takes them back, so
 * that a finding still points at the line the author wrote.
 *
 * @param {string} text the source
 * @return {object} the rewritten source and the shifts
 */
export function preprocessSource(text) {
  const shifts = [];
  let out = "";
  let index = 0;
  let line = 1;
  let column = 0;
  while (index < text.length) {
    const ch = text[index];
    if (ch === "\n") {
      out += ch;
      index += 1;
      line += 1;
      column = 0;
      continue;
    }
    if (ch === "'") {
      const end = skipSingleQuoted(text, index);
      out += text.slice(index, end);
      column += end - index;
      index = end;
      continue;
    }
    if (ch === '"') {
      const end = skipDoubleQuoted(text, index);
      const body = text.slice(index + 1, end - 1);
      const rewritten = rewriteInterpolation(body);
      if (rewritten !== body) {
        const bodyLine = line;
        const bodyColumn = column + 1;
        out += `"${rewritten}"`;
        shifts.push({ line: bodyLine, column: bodyColumn + 1, delta: 1 });
        shifts.push({ line: bodyLine, column: bodyColumn + rewritten.length + 1, delta: 1 });
        column += rewritten.length + 2;
        index = end;
        continue;
      }
      out += text.slice(index, end);
      column += end - index;
      index = end;
      continue;
    }
    out += ch;
    index += 1;
    column += 1;
  }
  return { text: out, shifts };
}

/**
 * The end of a single-quoted string, the quote included.
 */
function skipSingleQuoted(text, start) {
  let index = start + 1;
  while (index < text.length) {
    if (text[index] === "\\") {
      index += 2;
      continue;
    }
    if (text[index] === "'") return index + 1;
    index += 1;
  }
  return text.length;
}

/**
 * The end of a double-quoted string, the quote included.
 */
function skipDoubleQuoted(text, start) {
  let index = start + 1;
  while (index < text.length) {
    if (text[index] === "\\") {
      index += 2;
      continue;
    }
    if (text[index] === '"') return index + 1;
    index += 1;
  }
  return text.length;
}

/**
 * Rewrite the simple interpolations of a string body that carry a quoted
 * subscript.
 *
 * A simple interpolation is `$name`; it becomes `$name[...]` when the name
 * is indexed. The complex form is `{$name[...]}`.
 */
function rewriteInterpolation(body) {
  let out = "";
  let index = 0;
  let changed = false;
  while (index < body.length) {
    const ch = body[index];
    if (ch === "\\") {
      out += body.slice(index, index + 2);
      index += 2;
      continue;
    }
    if (ch === "{") {
      const end = skipComplexInterpolation(body, index);
      out += body.slice(index, end);
      index = end;
      continue;
    }
    if (ch === "$" && /[A-Za-z_]/.test(body[index + 1] ?? "")) {
      let name = index + 1;
      while (name < body.length && /[A-Za-z0-9_]/.test(body[name])) name += 1;
      if (body[name] === "[") {
        const close = matchBracket(body, name);
        if (close > 0) {
          const inner = body.slice(name + 1, close);
          if (inner.includes("'") || inner.includes('"')) {
            out += `{${body.slice(index, close + 1)}}`;
            changed = true;
            index = close + 1;
            continue;
          }
        }
      }
    }
    out += ch;
    index += 1;
  }
  return changed ? out : body;
}

/**
 * The end of a complex interpolation, the closing brace included.
 */
function skipComplexInterpolation(body, start) {
  let index = start + 1;
  let depth = 1;
  while (index < body.length && depth > 0) {
    const ch = body[index];
    if (ch === "{") {
      depth += 1;
      index += 1;
      continue;
    }
    if (ch === "}") {
      depth -= 1;
      index += 1;
      continue;
    }
    if (ch === "'") {
      index = skipSingleQuoted(body, index);
      continue;
    }
    if (ch === '"') {
      index = skipDoubleQuoted(body, index);
      continue;
    }
    index += 1;
  }
  return index;
}

/**
 * The index of the bracket that closes the one at `start`.
 *
 * The subscript may hold a string, and a string may hold a bracket.
 */
function matchBracket(body, start) {
  let depth = 0;
  let index = start;
  while (index < body.length) {
    const ch = body[index];
    if (ch === "'") {
      index = skipSingleQuoted(body, index);
      continue;
    }
    if (ch === '"') {
      index = skipDoubleQuoted(body, index);
      continue;
    }
    if (ch === "[") {
      depth += 1;
      index += 1;
      continue;
    }
    if (ch === "]") {
      depth -= 1;
      if (depth === 0) return index;
      index += 1;
      continue;
    }
    index += 1;
  }
  return -1;
}

/**
 * One function's lowering state.
 *
 * The instruction list, the locals and the problems belong to the function
 * being lowered, so they live here rather than on the translator.
 */
class FunctionState {
  constructor(name, signature, enclosingClass) {
    this.name = name;
    this.signature = signature;
    this.enclosingClass = enclosingClass;
    this.params = [];
    this.locals = [];
    this.instructions = [];
    this.problems = [];
    this.problemCounts = new Map();
    this.tempCounter = 0;
    this.labelCounter = 0;
    this.labelPrefix = "";
    // The types this function inferred for its own variables. PHP has no
    // static types, so they come from the annotations and from the few
    // assignments that name a class.
    this.types = new Map();
    this.breakLabel = null;
    this.continueLabel = null;
    // What this function IS inside its owner: a method, the constructor, or
    // the function that runs a class body's field initializers.
    this.memberKind = MEMBER_KIND.METHOD;
    // Whether this function is one the translator made up rather than one
    // the source declared.
    this.synthetic = false;
    // The type this function returns, as a type object.
    this.returnType = { kind: TYPE.UNKNOWN };
    // The declared parameters, with the facts a declaration carries and a
    // positional list cannot: whether a parameter is optional, variadic, or
    // taken by reference.
    this.parameters = [];
    // The names this function binds in a scope that is not its own.
    this.scopeBindings = [];
    // The exception regions this function contains.
    this.tryRegions = [];
    // The branch regions: the switch and match dispatches, with their
    // cases.
    this.branchRegions = [];
    // The generator's slots: the value it yields, and the key it yields it
    // under.
    this.generatorSlot = null;
    this.generatorKeySlot = null;
    // The labels a generator resumes at.
    this.generatorStates = [];
    // The annotations: the five channels the IR has.
    this.classAnnotations = [];
    this.methodAnnotations = [];
    this.fieldAnnotations = [];
    this.paramAnnotations = [];
    this.annotationValues = {};
    // The drops: what this translator could not lower, and what that cost.
    this.drops = [];
    this.dropCounts = new Map();
    // The label kinds, by label name, so that a jump can say what it jumps
    // to.
    this.labelKinds = new Map();
    // The scope ids, by label name, so that a jump and its target agree.
    this.labelScopes = new Map();
    // The counter that names the scopes. A scope id says how deep a label is
    // nested, so that a jump can be checked against its target.
    this.scopeCounter = 0;
    // The stack of enclosing loops and switches, so that `break 2` knows
    // which one it leaves.
    this.loopStack = [];
    // The scope's variable table. A variable variable (`$$x`) reads and
    // writes a slot of it whose NAME is only known at run time.
    this.scopeTable = null;
    // The closures this function's own variables hold, by variable name. A
    // call of such a variable has to reach the closure's own function.
    this.closureByVar = new Map();
    // The class this function's code is in, when it is one.
    this.superType = null;
    // The `finally` blocks this function is inside, innermost last. A
    // `return` inside a `try` has to run them before it leaves.
    this.finallyStack = [];
    // Whether the function returns by reference.
    this.byRef = false;
  }

  newTemp(type) {
    this.tempCounter += 1;
    return varOp(`t_t${this.tempCounter}`, type, true);
  }

  newLabel(prefix = "L") {
    this.labelCounter += 1;
    return `${this.labelPrefix}${prefix}${this.labelCounter}`;
  }

  /** A fresh scope id: one step deeper than the scopes before it. */
  newScope() {
    this.scopeCounter += 1;
    return this.scopeCounter;
  }
}

/**
 * The translator.
 *
 * One instance lowers one source file.
 */
export class Translator {
  /**
   * @param {object} options
   * @param {string} options.moduleName the module the file belongs to
   * @param {string} options.text the file's text, for the line and column
   */
  constructor(options) {
    this.moduleName = options.moduleName ?? "<module>";
    this.text = options.text ?? "";
    // The shifts the source's positions moved by, when the source had to be
    // rewritten to parse at all. See `preprocessSource`.
    this.shifts = options.shifts ?? preprocessSource(this.text).shifts;
    this.state = null;
    // The functions that the file's own code declared. They are lifted out
    // of the file's stream, because a function is a function.
    this.moduleFunctions = [];
    // The namespace the code is in. PHP qualifies a name with it, and an
    // unqualified call looks here FIRST.
    this.namespace = "";
    // The names the file's `use` statements brought in, per namespace: the
    // alias to the qualified name it stands for. A `use function` and a `use`
    // of a class are different things, and PHP resolves them differently.
    this.classAliases = new Map();
    this.functionAliases = new Map();
    // The constants the file's `use const` statements brought in, per
    // namespace: the alias to the qualified name it stands for. A constant's
    // VALUE is not modelled, but the NAME it is known by is a fact about the
    // file, and a rule that names the constant can match it.
    this.constantAliases = new Map();
    // The functions the file declares, per namespace. PHP resolves an
    // unqualified function call to the current namespace first and to the
    // global one only when the current one does not declare it -- and whether
    // it declares it is a fact about the file, so the file is read before it
    // is lowered.
    this.namespaceFunctions = new Map();
    // The declared parameters of the functions the file declares, so that a
    // call of one of them carries the arity of the DECLARATION. The key is
    // the callee name as it appears in a callee signature.
    this.declaredArities = new Map();
    // The declared parameter NAMES, by the same key. A named argument binds
    // by name, so the call site has to know what the callee calls them.
    this.declaredParameterNames = new Map();
    // The type declarations the file's classes, interfaces, enums and traits
    // make. They are the file's type surface; the response's `types` key
    // carries them.
    this.typeDeclarations = [];
    // The counter that names the closures of one file.
    this.closureCounter = 0;
    // The counter that names the generators of one file.
    this.generatorCounter = 0;
    // The closures this file made, by the name of the closure OBJECT. A
    // variable that holds a closure object is a call of the closure's own
    // function, and the call has to say so.
    this.closureByObject = new Map();
  }

  /**
   * The functions a file declares, in source order.
   *
   * @param {object} program the parsed file
   * @return {object} the wire functions
   */
  translateProgram(program) {
    this.collectBindings(program.children ?? [], "");
    const functions = [];
    const outer = this.state;
    const state = this.beginFunction("<module>", this.moduleName, null);
    state.memberKind = MEMBER_KIND.MODULE;
    state.synthetic = true;
    this.guard(program, () => this.lowerBody(program.children ?? []));
    functions.push(this.finishFunction(state));
    this.state = outer;
    functions.push(...this.moduleFunctions);
    return { functions, types: this.typeDeclarations };
  }

  /**
   * Read the file's declarations before lowering it.
   *
   * Two of PHP's name-resolution rules need this. An unqualified function
   * call looks in the CURRENT namespace first and in the global one only if
   * the current one does not declare it; and a `use` statement binds an alias
   * to a qualified name, which is what makes a class resolvable at all.
   * Both are facts about the file, and the file has to be read before it is
   * lowered to know them.
   *
   * The DECLARED PARAMETERS are read here too, for the same reason: a call
   * that appears BEFORE the declaration it calls still has to carry that
   * declaration's arity, and a file that is read while it is lowered cannot
   * know a declaration it has not reached yet.
   *
   * @param {object[]} children the statements of a namespace block
   * @param {string} namespace the namespace they are in
   */
  collectBindings(children, namespace) {
    for (const child of children) {
      if (!child || typeof child !== "object") continue;
      if (child.kind === "namespace") {
        this.collectBindings(child.children ?? [], child.name ?? "");
        continue;
      }
      if (child.kind === "usegroup" || child.kind === "useitem") {
        this.recordUse(child, namespace);
        continue;
      }
      if (child.kind === "function") {
        const name = this.nameText(child.name);
        if (!name) continue;
        if (!this.namespaceFunctions.has(namespace)) {
          this.namespaceFunctions.set(namespace, new Set());
        }
        this.namespaceFunctions.get(namespace).add(name);
        const qualified = this.qualifyFunction(name, namespace);
        this.declaredArities.set(qualified, this.declaredArityOf(child));
        this.declaredParameterNames.set(qualified, this.parameterNamesOf(child));
        continue;
      }
      if (child.kind === "class" || child.kind === "trait") {
        this.collectClassBindings(child, namespace);
      }
    }
  }

  /**
   * Read a class's methods before the class is lowered.
   *
   * @param {object} node the class node
   * @param {string} namespace the namespace it is in
   */
  collectClassBindings(node, namespace) {
    const qualified = this.qualifyClass(this.nameText(node.name), namespace);
    for (const member of node.body ?? []) {
      if (member.kind === "method") {
        const methodName = this.nameText(member.name);
        const arity = this.declaredArityOf(member);
        this.declaredArities.set(`${qualified}::${methodName}`, arity);
        this.declaredParameterNames.set(
          `${qualified}::${methodName}`,
          this.parameterNamesOf(member),
        );
        if (methodName === "__construct") {
          // The CONSTRUCTOR is lowered as `<init>`, and a `new` names the
          // constructor as `<fqcn>.<init>/n`. The arity has to be
          // registered under that name too, or a construction cannot be
          // linked to the constructor it calls.
          this.declaredArities.set(`${qualified}.<init>`, arity);
          this.declaredParameterNames.set(
            `${qualified}.<init>`,
            this.parameterNamesOf(member),
          );
        }
      } else if (member.kind === "class" || member.kind === "trait") {
        this.collectClassBindings(member, namespace);
      }
    }
  }

  /**
   * Record a `use` statement.
   *
   * It binds an alias to a qualified name, and the binding is what makes a
   * class resolvable: `use App\Support\Logger;` then `new Logger()` is
   * `App\Support\Logger`. A `use function` binds a FUNCTION, and PHP
   * resolves the two independently.
   *
   * A GROUPED use statement carries the prefix in its own `name`: `use
   * App\Support\{Logger, Mailer};` binds `Logger` to `App\Support\Logger`
   * and `Mailer` to `App\Support\Mailer`, so the prefix has to be put back
   * or both of them resolve to a name that does not exist.
   *
   * @param {object} node a usegroup or a useitem
   * @param {string} namespace the namespace it is in
   */
  recordUse(node, namespace) {
    const items = node.kind === "usegroup" ? node.items ?? [] : [node];
    const prefix = node.kind === "usegroup" ? (node.name ?? "") : "";
    for (const item of items) {
      if (!item || !item.name) continue;
      const type = item.type ?? node.type;
      const qualified = prefix ? `${prefix}\\${item.name}` : item.name;
      const alias = this.nameText(item.alias) ?? qualified.split("\\").pop();
      if (type === "const") {
        // A `use const` binds a CONSTANT. The constant's value is not
        // modelled, so the binding has nothing to resolve -- but the name
        // the constant is known by is a fact about the file.
        let constants = this.constantAliases.get(namespace);
        if (!constants) {
          constants = new Map();
          this.constantAliases.set(namespace, constants);
        }
        constants.set(alias, qualified);
        continue;
      }
      const table = type === "function" ? this.functionAliases : this.classAliases;
      let map = table.get(namespace);
      if (!map) {
        map = new Map();
        table.set(namespace, map);
      }
      map.set(alias, qualified);
    }
  }

  // ---- functions ------------------------------------------------------------------------

  beginFunction(name, signature, enclosingClass) {
    const state = new FunctionState(name, signature, enclosingClass);
    this.state = state;
    if (enclosingClass) {
      state.types.set("this", enclosingClass);
    }
    return state;
  }

  /**
   * Finish a function's lowering and produce its wire form.
   *
   * The function's precision is DERIVED from its drops, not from whether it
   * has a problem: a construct whose semantics were lost is a different
   * thing from one whose shape was lost, and the engine has to be able to
   * tell them apart.
   */
  finishFunction(state) {
    for (const entry of state.problemCounts.values()) {
      if (entry.count > 1) {
        entry.problem.message = `${entry.problem.message} (${entry.count} occurrences)`;
      }
    }
    // A jump to a label that comes LATER in the stream could not say what
    // the label was when it was emitted, because the label did not exist
    // yet. It is filled in now that every label has been seen.
    for (const instruction of state.instructions) {
      const ref = instruction.labelRef;
      if (!ref || !ref.name) continue;
      const kind = state.labelKinds.get(ref.name);
      if (kind) ref.kind = kind;
      const scopeId = state.labelScopes.get(ref.name);
      if (scopeId !== undefined) ref.scopeId = scopeId;
    }
    const problems = state.problems;
    const drops = state.drops;
    return {
      name: state.name,
      signature: state.signature,
      enclosingClass: state.enclosingClass ?? undefined,
      precision: precisionOf(drops),
      synthetic: state.synthetic,
      memberKind: state.memberKind,
      // The type this function returns. The engine cannot learn it from the
      // body -- a return value's type is a property of the declaration --
      // so without this field the return type of every function in the
      // program was unknown to the analysis.
      returnType: state.returnType,
      // The value domain of what this function returns.
      domain: state.returnType?.domain ?? null,
      params: state.params,
      locals: state.locals,
      instructions: state.instructions,
      problems,
      // What this translator could not lower, and what that cost.
      drops,
      // The declared parameters, with the facts a positional list of
      // variables cannot carry.
      parameters: state.parameters,
      // The exception regions this function contains.
      tryRegions: state.tryRegions,
      // The branch regions: the switch and match dispatches.
      branchRegions: state.branchRegions,
      // The labels a generator resumes at.
      generatorStates: state.generatorStates,
      // The names this function binds in a scope that is not its own.
      scopeBindings: state.scopeBindings,
      // The annotations: the five channels the IR has.
      classAnnotations: state.classAnnotations,
      methodAnnotations: state.methodAnnotations,
      fieldAnnotations: state.fieldAnnotations,
      paramAnnotations: state.paramAnnotations,
      annotationValues: state.annotationValues,
      // Whether the function returns by reference.
      byRef: state.byRef === true,
    };
  }

  /**
   * Lower a function or a method.
   *
   * @param {object} node the function or method node
   * @param {string|null} enclosingClass the qualified class it belongs to
   * @return {object} the wire function
   */
  lowerFunction(node, enclosingClass) {
    const name = this.nameText(node.name);
    if (enclosingClass && name === "__construct") {
      return this.lowerConstructor(node, enclosingClass);
    }
    const outer = this.state;
    const qualified = enclosingClass ? `${enclosingClass}::${name}` : this.qualifyFunction(name);
    const state = this.beginFunction(name, "", enclosingClass);
    state.signature = this.functionSignature(node, qualified);
    state.byRef = node.byref === true;
    if (name === "__destruct") state.memberKind = MEMBER_KIND.DESTRUCTOR;
    this.annotate(state, node, "method");
    try {
      this.lowerParameters(node);
      if (node.body) {
        this.lowerBody(node.body.children ?? []);
      }
    } finally {
      this.state = outer;
    }
    return this.finishFunction(state);
  }

  /**
   * Lower a constructor.
   *
   * A `new` names the constructor it calls as `<fqcn>.<init>/n`, so the
   * constructor has to be lowered under that name: a constructor that keeps
   * its PHP name (`__construct`) is never reached by the construction that
   * calls it, and its body's findings are lost.
   *
   * @param {object} node the method node
   * @param {string} enclosingClass the qualified class it belongs to
   * @return {object} the wire function
   */
  lowerConstructor(node, enclosingClass) {
    const outer = this.state;
    const qualified = `${enclosingClass}.<init>`;
    const state = this.beginFunction("<init>", "", enclosingClass);
    state.signature = this.functionSignature(node, qualified);
    state.memberKind = MEMBER_KIND.CONSTRUCTOR;
    state.byRef = node.byref === true;
    this.annotate(state, node, "method");
    try {
      this.lowerParameters(node);
      this.lowerPromotedProperties(node, enclosingClass);
      if (node.body) {
        this.lowerBody(node.body.children ?? []);
      }
    } finally {
      this.state = outer;
    }
    return this.finishFunction(state);
  }

  /**
   * The properties a constructor promotes.
   *
   * A parameter with a VISIBILITY is a property: PHP 8.0 lets the
   * constructor's parameter list declare one, and the constructor assigns it
   * before the body runs. Without that assignment the property is never
   * written, and whatever reads it later sees an unknown value -- so the
   * value the CALLER passed never reaches the code that uses the property.
   *
   * The assignment has the shape an ordinary property write already has: a
   * keyed store on the object, under the class's own name. That is what
   * makes the write and the read meet: the read of `$this->name` asks for
   * the same owner and the same key.
   *
   * @param {object} node the constructor
   * @param {string} enclosingClass the class the constructor belongs to
   */
  lowerPromotedProperties(node, enclosingClass) {
    const parameters = node.arguments ?? [];
    for (let index = 0; index < parameters.length; index += 1) {
      const parameter = parameters[index];
      const flags = parameter.flags;
      if (flags === undefined || flags === null || flags === 0) continue;
      const name = this.nameText(parameter.name);
      if (!name) continue;
      const value = this.state.params[index];
      if (!value) continue;
      this.annotate(this.state, parameter, "field");
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: varOp("this", { kind: TYPE.CLASS, name: enclosingClass }),
        lhs2: constOp(CONST.STRING, name),
        dest: value,
        ownerType: enclosingClass,
        ...this.position(parameter),
      });
      if (parameter.value !== undefined && parameter.value !== null) {
        this.emitDrop(
          "value.promoted.default",
          "a promoted property takes the value the caller passed, and its default is used when the "
            + "caller passes none, which the TAC cannot say",
          DROP_IMPACT.VALUE,
          parameter,
        );
      }
    }
  }

  /**
   * The declared parameters of a function.
   *
   * A parameter carries more than its name: whether it is optional, whether
   * it is variadic, whether it is taken by reference, and whether it is
   * nullable. Those facts decide how a call binds its arguments, so they
   * are recorded rather than thrown away.
   *
   * The parser spells the two flags `byref` and `variadic`; the design
   * document calls them `isRef` and `isVariadic`. Both are read, so that the
   * facts are there whichever spelling the parser uses.
   */
  lowerParameters(node) {
    for (const parameter of node.arguments ?? []) {
      const name = this.nameText(parameter.name) ?? "p";
      const typeName = this.typeNameOf(parameter.type);
      const type = this.tacTypeOfName(typeName);
      if (parameter.nullable === true) type.nullable = true;
      const operand = varOp(name, type);
      this.state.params.push(operand);
      if (typeName) {
        this.state.types.set(name, typeName);
      }
      this.state.parameters.push({
        name,
        type,
        optional: parameter.value !== undefined && parameter.value !== null,
        variadic: parameter.variadic === true || parameter.isVariadic === true,
        byRef: parameter.byref === true || parameter.isRef === true,
        keywordOnly: false,
        defaultValue: this.defaultValueOf(parameter),
      });
      this.annotateParameter(parameter);
    }
  }

  /**
   * The default value of a parameter, as an operand.
   *
   * A default is evaluated when the function is CALLED, in the callee's own
   * scope. A constant default is the same value every time, so it is
   * recorded as the constant it is. A default that reads a variable is not:
   * its value depends on when the function is called, and the TAC has no
   * place to put that, so it is recorded as absent and the difference is
   * reported.
   */
  defaultValueOf(parameter) {
    if (parameter.value === undefined || parameter.value === null) return null;
    const value = parameter.value;
    if (value.kind === "number") return this.numberConstant(value.value);
    if (value.kind === "string") return constOp(CONST.STRING, value.value);
    if (value.kind === "boolean") return constOp(CONST.BOOL, value.value ? "true" : "false");
    if (value.kind === "null") return constOp(CONST.NULL);
    this.emitDrop(
      "value.default.evaluated",
      "a default value that is not a constant is evaluated when the function is called, and the "
        + "TAC has no place to hold that value",
      DROP_IMPACT.VALUE,
      value,
    );
    return null;
  }

  /**
   * The declared parameters of a function, counted.
   *
   * The count is what a call of the function carries, because the arity of a
   * call is the arity of the DECLARATION when the declaration is visible. An
   * optional parameter is still a parameter: the callee has a slot for it,
   * whether or not the caller fills it.
   */
  declaredArityOf(node) {
    return this.parameterListOf(node).length;
  }

  /**
   * The declared parameters of a function, by name.
   *
   * A named argument binds by name, so the call site has to know what the
   * callee calls its parameters.
   */
  parameterNamesOf(node) {
    return this.parameterListOf(node).map((p) => this.nameText(p.name) ?? "p");
  }

  /**
   * The declared parameters of a function, as a list.
   *
   * Every count of parameters goes through here, so that the count a call
   * carries and the count the signature shows are the same count.
   */
  parameterListOf(node) {
    return node.arguments ?? [];
  }

  /**
   * The signature of a function: its qualified name, its parameter types and
   * its return type.
   *
   * The engine reads the arity out of this string, so the parameter list
   * holds every declared parameter -- the same count the callee signature
   * carries.
   *
   * The RETURN TYPE is also recorded as a TYPE OBJECT, because a type object
   * is what the engine's mapper reads: a string has to be parsed again on the
   * other side of the wire, and a type that cannot be parsed is a type that
   * is lost.
   */
  functionSignature(node, qualified) {
    const parameters = this.parameterListOf(node);
    const types = parameters.map((p) => this.typeNameOf(p.type) ?? "any");
    const returns = this.typeNameOf(node.type) ?? "any";
    const returnType = this.tacTypeOfName(returns);
    if (node.nullable === true) returnType.nullable = true;
    if (this.state) this.state.returnType = returnType;
    return `${qualified}(${types.join(",")}):${returns}`;
  }

  // ---- statements -----------------------------------------------------------------------

  lowerBody(children) {
    for (const statement of children) {
      this.guard(statement, () => this.lowerStatement(statement));
    }
  }

  lowerStatement(node) {
    if (!node) return;
    switch (node.kind) {
      case "namespace": {
        const outer = this.namespace;
        this.namespace = node.name ?? "";
        this.lowerBody(node.children ?? []);
        this.namespace = outer;
        return;
      }
      case "usegroup":
      case "useitem":
        this.recordUse(node, this.namespace);
        return;
      case "expressionstatement":
        this.lowerExpression(node.expression);
        return;
      case "echo":
        for (const expression of node.expressions ?? []) {
          this.lowerExpression(expression);
        }
        return;
      case "print":
        this.lowerExpression(node.expression);
        return;
      case "return": {
        const value = node.expr ? this.lowerExpression(node.expr) : undefined;
        this.lowerReturn(value, node);
        return;
      }
      case "throw":
        this.emit({
          kind: KIND.THROW,
          lhs1: node.what ? this.lowerExpression(node.what) : undefined,
          ...this.position(node),
        });
        return;
      case "if":
        this.lowerIf(node);
        return;
      case "while":
        this.lowerWhile(node);
        return;
      case "do":
        this.lowerDo(node);
        return;
      case "for":
        this.lowerFor(node);
        return;
      case "foreach":
        this.lowerForeach(node);
        return;
      case "switch":
        this.lowerSwitch(node);
        return;
      case "break":
        this.lowerBreak(node);
        return;
      case "continue":
        this.lowerContinue(node);
        return;
      case "try":
        this.lowerTry(node);
        return;
      case "function":
        this.lowerFunctionDeclaration(node);
        return;
      case "class":
        this.lowerClass(node);
        return;
      case "trait":
        this.lowerTrait(node);
        return;
      case "enum":
        this.lowerEnum(node);
        return;
      case "interface":
        this.lowerInterface(node);
        return;
      case "propertystatement":
        this.lowerPropertyStatement(node);
        return;
      case "constantstatement":
        this.lowerConstantStatement(node);
        return;
      case "classconstant":
        this.lowerClassConstant(node);
        return;
      case "enumcase":
        this.lowerEnumCase(node);
        return;
      case "traituse":
        this.lowerTraitUse(node);
        return;
      case "global":
        this.lowerGlobal(node);
        return;
      case "static":
        this.lowerStaticVariable(node);
        return;
      case "declare":
        this.lowerDeclare(node);
        return;
      case "inline":
      case "noscript":
        // HTML has no counterpart in the TAC.
        return;
      case "block":
        this.lowerBody(node.children ?? []);
        return;
      case "unset":
        this.lowerUnset(node);
        return;
      case "goto":
        this.lowerGoto(node);
        return;
      case "label":
        this.lowerLabel(node);
        return;
      default:
        this.report(PROBLEM.UNHANDLED_NODE, node, "this statement has no TAC lowering");
    }
  }

  /**
   * A `return`.
   *
   * A `return` inside a `try` that has a `finally` does not leave the
   * function straight away: PHP runs the `finally` first. So the value is
   * put aside, the finally is run, and the function returns afterwards.
   */
  lowerReturn(value, node) {
    const pending = this.state.finallyStack[this.state.finallyStack.length - 1];
    if (!pending) {
      this.emit({ kind: KIND.RETURN, lhs1: value, ...this.position(node) });
      return;
    }
    this.emit({ kind: KIND.MOVE, dest: pending.returnValue, lhs1: value, ...this.position(node) });
    this.emit({
      kind: KIND.MOVE,
      dest: pending.returnFlag,
      lhs1: constOp(CONST.INT, "1"),
      ...this.position(node),
    });
    this.emitJump(pending.label, node);
  }

  /**
   * A `global` statement.
   *
   * The names it names belong to the file's scope, not to the function's.
   * The binding is recorded, and the engine routes the writes to the
   * module's scope: a `global G; G = $cmd;` writes the module's `G`, and
   * a function that reads `G` reads what this one wrote.
   */
  lowerGlobal(node) {
    for (const item of node.items ?? []) {
      const name = this.nameText(item);
      if (!name) continue;
      this.state.scopeBindings.push({ name, kind: SCOPE_KIND.GLOBAL, byRef: false });
    }
  }

  /**
   * A `static` statement: a variable that keeps its value between calls.
   */
  lowerStaticVariable(node) {
    for (const variable of node.variables ?? []) {
      const name = this.nameText(variable.variable);
      if (!name) continue;
      this.state.scopeBindings.push({ name, kind: SCOPE_KIND.STATIC_LOCAL, byRef: false });
      if (variable.defaultValue === undefined || variable.defaultValue === null) continue;
      const value = this.lowerExpression(variable.defaultValue);
      const local = varOp(name);
      this.declareLocal(local);
      this.emit({ kind: KIND.MOVE, dest: local, lhs1: value, ...this.position(variable) });
    }
  }

  /**
   * A `declare` statement.
   *
   * A directive sets how the rest of the file is compiled. It has no
   * counterpart in the TAC, so it is recorded as a scope binding and its
   * value is evaluated, in case the value is not a constant.
   */
  lowerDeclare(node) {
    for (const directive of node.directives ?? []) {
      const name = this.nameText(directive.key);
      if (!name) continue;
      this.state.scopeBindings.push({ name, kind: SCOPE_KIND.DECLARE, byRef: false });
      if (directive.value !== undefined && directive.value !== null) {
        this.lowerExpression(directive.value);
      }
    }
    // In the "none" mode the parser collects the statements that FOLLOW the
    // directive into the node, and the enclosing body lowers them again.
    // Only a block or a short tag has a body of its own.
    if (node.mode === "block" || node.mode === "short") {
      this.lowerBody(node.children ?? []);
    }
  }

  /**
   * An `unset` statement.
   *
   * Removing a key is a STORE that says "the key is gone": the TAC has no
   * other way to say it, and a store that does not say it would leave the
   * key's taint in place -- which is a finding that is not there.
   */
  lowerUnset(node) {
    for (const target of node.variables ?? []) {
      if (
        target.kind === "offsetlookup" ||
        target.kind === "propertylookup" ||
        target.kind === "nullsafepropertylookup"
      ) {
        const receiver = this.lowerExpression(target.what);
        const key =
          target.kind === "offsetlookup"
            ? this.lowerKey(target.offset)
            : constOp(CONST.STRING, this.nameText(target.offset) ?? "");
        this.emit({
          kind: KIND.ARRAYSTORE,
          lhs1: receiver,
          lhs2: key,
          deleteKey: true,
          ...this.position(target),
        });
        continue;
      }
      if (target.kind === "variable" && typeof target.name === "string") {
        // A plain variable: the variable no longer has a value, and the TAC
        // has no way to say that.
        const variable = varOp(target.name);
        this.declareLocal(variable);
        this.emit({
          kind: KIND.MOVE,
          dest: variable,
          lhs1: this.emitHavoc(target),
          ...this.position(target),
        });
        this.emitDrop(
          "expr.unset.variable",
          "an unset of a plain variable removes the variable's value, and the TAC has no way to "
            + "say that a variable has no value",
          DROP_IMPACT.STRUCTURE,
          target,
        );
        continue;
      }
      this.report(PROBLEM.UNHANDLED_NODE, target, "this unset target has no TAC lowering");
    }
  }

  /**
   * A `break`.
   *
   * A break leaves the innermost loop or switch. A break with a LEVEL leaves
   * several of them, so the labels of the enclosing loops and switches are
   * kept on a stack and the level says which one to leave.
   */
  lowerBreak(node) {
    const level = this.breakLevelOf(node);
    const stack = this.state.loopStack;
    if (level > stack.length) {
      this.report(
        PROBLEM.UNHANDLED_NODE,
        node,
        "this break leaves more levels than there are loops and switches around it",
      );
      return;
    }
    const entry = stack[stack.length - level];
    this.emitJump(entry.breakLabel, node);
  }

  /**
   * The level of a `break` or a `continue`.
   *
   * The parser gives the level as a NODE -- `{kind: "number", value: "2"}` --
   * and not as a number, so a level read as one is an object, and an object
   * cannot be counted with: the stack index becomes NaN and the break finds
   * nothing to jump to.
   *
   * @param {object} node the break or continue node
   * @return {number} how many levels it leaves
   */
  breakLevelOf(node) {
    const level = node?.level;
    if (level === undefined || level === null) return 1;
    const raw =
      typeof level === "object" ? level.value : level;
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 1) return 1;
    return Math.floor(value);
  }

  /**
   * A `continue`.
   *
   * A continue leaves the innermost LOOP, but NOT a switch: in PHP a
   * continue inside a switch continues the loop the switch is in. So the
   * levels are counted over the loops only.
   */
  lowerContinue(node) {
    const level = this.breakLevelOf(node);
    const loops = this.state.loopStack.filter((entry) => entry.kind === "loop");
    if (level > loops.length) {
      this.report(
        PROBLEM.UNHANDLED_NODE,
        node,
        "this continue leaves more levels than there are loops around it",
      );
      return;
    }
    const entry = loops[loops.length - level];
    this.emitJump(entry.continueLabel, node);
  }

  /**
   * A `goto`.
   *
   * The label it jumps to is named by the source, so the jump says the
   * label's name and the label says what it is.
   */
  lowerGoto(node) {
    const name = this.nameText(node.label);
    if (!name) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this goto names no label");
      return;
    }
    this.emitJump(name, node);
  }

  /**
   * A label: the target of a `goto`.
   */
  lowerLabel(node) {
    const name = this.nameText(node.name);
    if (!name) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this label has no name");
      return;
    }
    this.emitLabel(name, LABEL_KIND.BLOCK, node);
  }

  lowerFunctionDeclaration(node) {
    // The declared arity was read with the file's other declarations, before
    // any of it was lowered: a call that appears before the declaration it
    // calls still has to carry that declaration's arity.
    this.moduleFunctions.push(this.lowerFunction(node, null));
  }

  /**
   * A class: its body becomes a synthetic function, and its methods become
   * functions of their own.
   *
   * The class body runs when the class is created, so it is a function of
   * its own -- a synthetic one, named `<clinit>`. It is NOT the
   * constructor: the constructor is the method named `__construct`, and it
   * is lowered as `<init>` so that a `new` can reach it.
   */
  lowerClass(node) {
    const className = this.nameText(node.name);
    const qualified = this.qualifyClass(className);
    const functions = [];
    const outer = this.state;
    const state = this.beginFunction("<clinit>", `${qualified}.<clinit>():any`, qualified);
    state.memberKind = MEMBER_KIND.FIELD_INITIALIZER;
    state.synthetic = true;
    state.superType = this.superTypeOf(node);
    this.annotate(state, node, "class");
    try {
      this.guard(node, () => this.lowerClassBody(node, functions, qualified));
    } finally {
      this.state = outer;
    }
    functions.unshift(this.finishFunction(state));
    this.moduleFunctions.push(...functions);
    this.recordTypeDeclaration(node, {
      fqn: qualified,
      simpleName: className,
      superType: state.superType,
      interfaces: (node.implements ?? []).map((i) => this.qualifiedClassNameOf(i)).filter(Boolean),
    });
  }

  /**
   * The body of a class, an enum or a trait.
   *
   * The dispatch is EXHAUSTIVE: every kind of member a class body can hold
   * is handled here, so that a member this translator does not know about
   * is a member it says it does not know about -- and not one it passes on
   * to a statement lowering that would report it as an unknown STATEMENT.
   */
  lowerClassBody(node, functions, qualified) {
    const owner = qualified ?? this.qualifyClass(this.nameText(node.name));
    for (const member of node.body ?? []) {
      switch (member.kind) {
        case "method": {
          const methodName = this.nameText(member.name);
          this.declaredArities.set(`${owner}::${methodName}`, this.declaredArityOf(member));
          this.declaredParameterNames.set(
            `${owner}::${methodName}`,
            this.parameterNamesOf(member),
          );
          if (methodName === "__construct") {
            this.declaredArities.set(`${owner}.<init>`, this.declaredArityOf(member));
            this.declaredParameterNames.set(
              `${owner}.<init>`,
              this.parameterNamesOf(member),
            );
          }
          functions.push(this.lowerFunction(member, owner));
          break;
        }
        case "class":
          this.lowerClass(member);
          break;
        case "trait":
          this.lowerTrait(member);
          break;
        case "enum":
          this.lowerEnum(member);
          break;
        case "interface":
          this.lowerInterface(member);
          break;
        case "propertystatement":
          this.lowerPropertyStatement(member);
          break;
        case "constantstatement":
          this.lowerConstantStatement(member);
          break;
        case "classconstant":
          this.lowerClassConstant(member);
          break;
        case "enumcase":
          this.lowerEnumCase(member);
          break;
        case "traituse":
          this.lowerTraitUse(member);
          break;
        case "traitprecedence":
        case "traitalias":
          // Both are resolved when the class is COMPILED: which trait's
          // method wins, and what it is called. The decision belongs to the
          // type declaration, and the instruction stream has nothing to
          // say about it.
          break;
        case "usegroup":
        case "useitem":
          this.recordUse(member, this.namespace);
          break;
        case "attribute":
        case "attrgroup":
          // An attribute is metadata: it is recorded in the annotation
          // channel of the class that carries it.
          break;
        case "inline":
        case "noscript":
          // HTML has no counterpart in the TAC.
          break;
        default:
          this.report(
            PROBLEM.UNHANDLED_NODE,
            member,
            "this class member has no TAC lowering",
          );
      }
    }
  }

  /**
   * An interface.
   *
   * An interface declares signatures and no bodies, so it has nothing to
   * lower into instructions. It is not a no-op, though: the signatures it
   * declares are the file's type surface, and a rule that names an
   * interface has to be able to find it.
   */
  lowerInterface(node) {
    const qualified = this.qualifyClass(this.nameText(node.name));
    const members = [];
    for (const member of node.body ?? []) {
      if (member.kind === "method") {
        const methodName = this.nameText(member.name);
        this.declaredArities.set(`${qualified}::${methodName}`, this.declaredArityOf(member));
        this.declaredParameterNames.set(
          `${qualified}::${methodName}`,
          this.parameterNamesOf(member),
        );
        members.push({
          name: methodName,
          kind: "METHOD",
          isAbstract: member.isAbstract === true,
          isPublic: member.visibility === undefined || member.visibility === "public",
        });
      }
    }
    this.recordTypeDeclaration(node, {
      fqn: qualified,
      simpleName: this.nameText(node.name),
      superType: null,
      interfaces: (node.extends ?? []).map((e) => this.qualifiedClassNameOf(e)).filter(Boolean),
      isInterface: true,
      members,
    });
  }

  /**
   * An enum.
   *
   * An enum is a class whose body holds cases instead of fields. The cases
   * are class constants, and the methods are methods of the enum.
   */
  lowerEnum(node) {
    const className = this.nameText(node.name);
    const qualified = this.qualifyClass(className);
    const functions = [];
    const outer = this.state;
    const state = this.beginFunction("<clinit>", `${qualified}.<clinit>():any`, qualified);
    state.memberKind = MEMBER_KIND.FIELD_INITIALIZER;
    state.synthetic = true;
    this.annotate(state, node, "class");
    try {
      this.guard(node, () => this.lowerClassBody(node, functions, qualified));
    } finally {
      this.state = outer;
    }
    functions.unshift(this.finishFunction(state));
    this.moduleFunctions.push(...functions);
    this.recordTypeDeclaration(node, {
      fqn: qualified,
      simpleName: className,
      superType: null,
      interfaces: (node.implements ?? []).map((i) => this.qualifiedClassNameOf(i)).filter(Boolean),
      isEnum: true,
    });
  }

  /**
   * A trait.
   *
   * A trait's methods are methods of the trait: they are lowered as
   * functions of their own, owned by the trait, and the classes that use
   * the trait name it in their type declaration.
   */
  lowerTrait(node) {
    const className = this.nameText(node.name);
    const qualified = this.qualifyClass(className);
    const functions = [];
    const outer = this.state;
    const state = this.beginFunction("<clinit>", `${qualified}.<clinit>():any`, qualified);
    state.memberKind = MEMBER_KIND.FIELD_INITIALIZER;
    state.synthetic = true;
    try {
      this.guard(node, () => this.lowerClassBody(node, functions, qualified));
    } finally {
      this.state = outer;
    }
    functions.unshift(this.finishFunction(state));
    this.moduleFunctions.push(...functions);
    this.recordTypeDeclaration(node, { fqn: qualified, simpleName: className, superType: null });
  }

  /**
   * A `use` of traits inside a class body.
   *
   * The trait's methods become the class's methods. Which one wins when two
   * traits have a method of the same name is decided when the class is
   * COMPILED, so the decision belongs to the type declaration and not to
   * the instruction stream.
   */
  lowerTraitUse(node) {
    for (const trait of node.traits ?? []) {
      const name = this.qualifiedClassNameOf(trait);
      if (!name) continue;
      this.emitDrop(
        "decl.trait.unlinked",
        `the class uses the trait ${name}, and a trait's methods are not copied into the class, `
          + "so a call of one of them is not linked to the trait's body",
        DROP_IMPACT.STRUCTURE,
        node,
      );
    }
  }

  /**
   * The properties of a class.
   *
   * A property with an initial value is a value that exists from the moment
   * the class is created, so it belongs to the class's own body -- and it
   * lives in the OBJECT's namespace, not in the function's locals: `$this`
   * and the property name are one location, exactly as `$o->name` and
   * `$o["name"]` are.
   */
  lowerPropertyStatement(node) {
    const owner = this.enclosingClassName();
    for (const property of node.properties ?? []) {
      this.annotate(this.state, property, "field");
      if (!property.value) continue;
      const value = this.lowerExpression(property.value);
      const name = this.nameText(property.name) ?? "p";
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: varOp("this", { kind: TYPE.CLASS, name: owner }),
        lhs2: constOp(CONST.STRING, name),
        dest: value,
        ownerType: owner,
        ...this.position(property),
      });
    }
  }

  /**
   * The constants of a file.
   *
   * A top-level constant is a value of the file's own scope. It is not a
   * member of a class, so it is a local of the module's function.
   */
  lowerConstantStatement(node) {
    for (const constant of node.constants ?? []) {
      const value = this.lowerExpression(constant.value);
      const name = this.nameText(constant.name) ?? "C";
      const variable = varOp(name);
      this.declareLocal(variable);
      this.emit({ kind: KIND.MOVE, dest: variable, lhs1: value, ...this.position(constant) });
    }
  }

  /**
   * The constants of a class.
   *
   * A class constant belongs to the CLASS, so the receiver of the store is
   * the class object and not the instance: `C::X` reads the same location
   * that the declaration wrote.
   */
  lowerClassConstant(node) {
    const owner = this.enclosingClassName();
    const classVariable = this.classObjectOf(owner);
    for (const constant of node.constants ?? []) {
      this.annotate(this.state, constant, "field");
      const value = this.lowerExpression(constant.value);
      const name = this.nameText(constant.name) ?? "C";
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: classVariable,
        lhs2: constOp(CONST.STRING, name),
        dest: value,
        ownerType: owner,
        ...this.position(constant),
      });
    }
  }

  /**
   * A case of an enum.
   *
   * A case is a class constant: the class holds it, and reading it is a
   * read of the class's own namespace.
   */
  lowerEnumCase(node) {
    const owner = this.enclosingClassName();
    const classVariable = this.classObjectOf(owner);
    const name = this.nameText(node.name) ?? "CASE";
    const value =
      node.value === undefined || node.value === null
        ? constOp(CONST.STRING, name)
        : typeof node.value === "object" && node.value.kind
          ? this.lowerExpression(node.value)
          : constOp(CONST.STRING, String(node.value));
    this.emit({
      kind: KIND.ARRAYSTORE,
      lhs1: classVariable,
      lhs2: constOp(CONST.STRING, name),
      dest: value,
      ownerType: owner,
      ...this.position(node),
    });
  }

  lowerIf(node) {
    this.state.newScope();
    const { operand: condition, guard } = this.lowerCondition(node.test);
    const thenLabel = this.state.newLabel("L");
    const elseLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emitBranch(condition, thenLabel, elseLabel, guard, node);
    this.emitLabel(thenLabel, LABEL_KIND.BLOCK, node);
    this.lowerBody(node.body?.children ?? []);
    this.emitJump(endLabel, node);
    this.emitLabel(elseLabel, LABEL_KIND.BLOCK, node);
    if (node.alternate) {
      if (node.alternate.kind === "if") {
        this.lowerIf(node.alternate);
      } else {
        this.lowerBody(node.alternate.children ?? []);
      }
    }
    this.emitLabel(endLabel, LABEL_KIND.BLOCK, node);
  }

  lowerWhile(node) {
    this.state.newScope();
    const conditionLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emitJump(conditionLabel, node);
    this.emitLabel(conditionLabel, LABEL_KIND.LOOP_HEAD, node);
    const { operand: condition, guard } = this.lowerCondition(node.test);
    this.emitBranch(condition, bodyLabel, endLabel, guard, node);
    this.emitLabel(bodyLabel, LABEL_KIND.BLOCK, node);
    this.withLoopLabels(endLabel, conditionLabel, LABEL_KIND.LOOP_END, () =>
      this.lowerBody(node.body?.children ?? []),
    );
    this.emitJump(conditionLabel, node);
    this.emitLabel(endLabel, LABEL_KIND.LOOP_END, node);
  }

  lowerDo(node) {
    this.state.newScope();
    const bodyLabel = this.state.newLabel("L");
    const conditionLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emitLabel(bodyLabel, LABEL_KIND.BLOCK, node);
    this.withLoopLabels(endLabel, conditionLabel, LABEL_KIND.LOOP_END, () =>
      this.lowerBody(node.body?.children ?? []),
    );
    this.emitLabel(conditionLabel, LABEL_KIND.LOOP_HEAD, node);
    const { operand: condition, guard } = this.lowerCondition(node.test);
    this.emitBranch(condition, bodyLabel, endLabel, guard, node);
    this.emitLabel(endLabel, LABEL_KIND.LOOP_END, node);
  }

  lowerFor(node) {
    this.state.newScope();
    for (const expression of node.init ?? []) {
      this.lowerExpression(expression);
    }
    const conditionLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const postLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emitJump(conditionLabel, node);
    this.emitLabel(conditionLabel, LABEL_KIND.LOOP_HEAD, node);
    // The parser gives the condition as a LIST: `for ($i = 0; $i < 3; $i++)`
    // has ONE condition, and it is the first of the list. Reading the list
    // itself would make the condition an unknown value, and an unknown
    // condition means the loop's guard is nothing.
    const tests = node.test ?? [];
    const { operand: condition, guard } = this.lowerCondition(tests[0]);
    this.emitBranch(condition, bodyLabel, endLabel, guard, node);
    this.emitLabel(bodyLabel, LABEL_KIND.BLOCK, node);
    this.withLoopLabels(endLabel, postLabel, LABEL_KIND.LOOP_END, () =>
      this.lowerBody(node.body?.children ?? []),
    );
    this.emitLabel(postLabel, LABEL_KIND.LOOP_BODY_END, node);
    // The increment is called `increment`, not `loop`: reading `loop` finds
    // nothing, and the increment is dropped without a word.
    for (const expression of node.increment ?? []) {
      this.lowerExpression(expression);
    }
    this.emitJump(conditionLabel, node);
    this.emitLabel(endLabel, LABEL_KIND.LOOP_END, node);
  }

  /**
   * A `foreach`.
   *
   * The loop reads the collection by index. The length is a call to the
   * language's own `count`, under a synthetic owner: the TAC has no length,
   * and a stand-in says so. An array literal has a length the translator can
   * read, so that one is a constant.
   *
   * The KEY variable binds the KEY. When the collection is an array literal
   * its keys are known, so they are read out of a table of them; when it is
   * not, the keys are not knowable here, and the difference is reported
   * rather than passed off as a key.
   */
  lowerForeach(node) {
    this.state.newScope();
    const collection = this.lowerExpression(node.source);
    const literal = node.source && node.source.kind === "array" ? node.source.items ?? [] : null;
    let length;
    let keyTable = null;
    if (literal) {
      length = constOp(CONST.INT, String(literal.length));
      keyTable = this.state.newTemp({ kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } });
      this.emit({
        kind: KIND.NEWARRAY,
        dest: keyTable,
        ctorType: { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } },
        lhs1: constOp(CONST.INT, String(literal.length)),
        ...this.position(node),
      });
      literal.forEach((entry, index) => {
        const key =
          entry && entry.key ? this.lowerKey(entry.key) : constOp(CONST.INT, String(index));
        this.emit({
          kind: KIND.ARRAYSTORE,
          lhs1: keyTable,
          lhs2: constOp(CONST.INT, String(index)),
          dest: key,
          ...this.position(entry ?? node),
        });
      });
    } else {
      length = this.state.newTemp(TYPE.INT);
      this.emit({
        kind: KIND.CALL,
        dest: length,
        calleeSig: `${SYNTHETIC_OWNER}.count/1`,
        args: [constOp(CONST.STRING, SYNTHETIC_OWNER), collection],
        ...this.position(node),
      });
      this.emitDrop(
        "ctrl.foreach.keyUnknown",
        "the keys of the collection are not known here, so the key variable holds the position "
          + "instead of the key",
        DROP_IMPACT.STRUCTURE,
        node,
      );
    }
    const index = this.state.newTemp(TYPE.INT);
    this.emit({
      kind: KIND.MOVE,
      dest: index,
      lhs1: constOp(CONST.INT, "0"),
      ...this.position(node),
    });
    const conditionLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const postLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emitJump(conditionLabel, node);
    this.emitLabel(conditionLabel, LABEL_KIND.LOOP_HEAD, node);
    const comparison = this.state.newTemp(TYPE.BOOLEAN);
    this.emit({
      kind: KIND.BINOP,
      dest: comparison,
      lhs1: index,
      lhs2: length,
      binop: "LT",
      ...this.position(node),
    });
    this.emitBranch(comparison, bodyLabel, endLabel, {
      kind: GUARD.COMPARE,
      lhs: index,
      op: "LT",
      rhs: length,
    }, node);
    this.emitLabel(bodyLabel, LABEL_KIND.BLOCK, node);
    // The key: read out of the table of keys when the collection's keys are
    // known, and the position itself when they are not.
    let key = index;
    if (keyTable) {
      const read = this.state.newTemp();
      this.emit({
        kind: KIND.ARRAYLOAD,
        dest: read,
        lhs1: keyTable,
        lhs2: index,
        ...this.position(node),
      });
      key = read;
    }
    const element = this.state.newTemp();
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest: element,
      lhs1: collection,
      lhs2: key,
      ...this.position(node),
    });
    if (node.value) {
      if (node.value.byref === true || node.byRef === true) {
        // The value variable ALIASES the element: a write through it is a
        // write to the collection's entry. The TAC's locations are one name
        // each, so the write names the collection it aliases -- and the
        // element's own key is not part of that name.
        const alias = this.nameText(node.source?.name);
        this.lowerWrite(node.value, element, node, alias);
        this.emitDrop(
          "ctrl.foreach.byRef",
          "the value variable is an alias of the collection's entry, and the TAC's locations are "
            + "one name each, so the write names the collection and not the entry",
          DROP_IMPACT.STRUCTURE,
          node,
        );
      } else {
        this.lowerWrite(node.value, element, node);
      }
    }
    if (node.key) {
      this.lowerWrite(node.key, key, node);
    }
    this.withLoopLabels(endLabel, postLabel, LABEL_KIND.LOOP_END, () =>
      this.lowerBody(node.body?.children ?? []),
    );
    this.emitLabel(postLabel, LABEL_KIND.LOOP_BODY_END, node);
    const incremented = this.state.newTemp(TYPE.INT);
    this.emit({
      kind: KIND.BINOP,
      dest: incremented,
      lhs1: index,
      lhs2: constOp(CONST.INT, "1"),
      binop: "ADD",
      ...this.position(node),
    });
    this.emit({ kind: KIND.MOVE, dest: index, lhs1: incremented, ...this.position(node) });
    this.emitJump(conditionLabel, node);
    this.emitLabel(endLabel, LABEL_KIND.LOOP_END, node);
  }

  /**
   * A `switch`.
   *
   * PHP's switch compares the subject with each case until one matches, and
   * then FALLS THROUGH into the next case unless a `break` says otherwise.
   * Both halves are modelled: the dispatch is a chain of equality tests, and
   * the body of a case ends by jumping into the body of the next one.
   *
   * What is not modelled is PHP's LOOSE comparison: the TAC's equality test
   * compares the values as they are, and PHP would coerce them first. That
   * difference is the ENGINE's rule table's business, not this translator's:
   * the guard says what the source says, and the engine applies the rules of
   * the language.
   */
  lowerSwitch(node) {
    this.state.newScope();
    const condition = this.lowerExpression(node.test);
    const cases = node.body?.children ?? [];
    const headLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    const caseLabels = cases.map(() => this.state.newLabel("L"));
    let defaultIndex = -1;
    const region = {
      id: `R${this.state.branchRegions.length + 1}`,
      kind: "SWITCH",
      subject: condition,
      cases: [],
      defaultLabel: null,
      fallThrough: true,
    };

    this.emitLabel(headLabel, LABEL_KIND.SWITCH_HEAD, node);
    for (let i = 0; i < cases.length; i++) {
      const caseNode = cases[i];
      if (!caseNode.test) {
        defaultIndex = i;
        continue;
      }
      const test = this.lowerExpression(caseNode.test);
      const matched = this.state.newTemp(TYPE.BOOLEAN);
      this.emit({
        kind: KIND.BINOP,
        dest: matched,
        lhs1: condition,
        lhs2: test,
        binop: "EQ",
        ...this.position(caseNode),
      });
      const nextLabel = this.state.newLabel("L");
      this.emitBranch(
        matched,
        caseLabels[i],
        nextLabel,
        { kind: GUARD.COMPARE, lhs: condition, op: "EQ", rhs: test },
        caseNode,
      );
      region.cases.push({
        id: `C${i + 1}`,
        guards: [{ lhs: condition, op: "EQ", rhs: test }],
        bodyLabel: caseLabels[i],
        nextLabel,
      });
      this.emitLabel(nextLabel, LABEL_KIND.SWITCH_CASE, caseNode);
    }

    this.emitJump(defaultIndex >= 0 ? caseLabels[defaultIndex] : endLabel, node);
    if (defaultIndex >= 0) region.defaultLabel = caseLabels[defaultIndex];

    for (let i = 0; i < cases.length; i++) {
      this.emitLabel(caseLabels[i], LABEL_KIND.SWITCH_CASE, cases[i]);
      this.withBreakLabel(endLabel, () => this.lowerBody(cases[i].body?.children ?? []));
      if (i + 1 < cases.length) {
        // PHP's fall-through: the next case's body runs unless a break said
        // otherwise.
        this.emitJump(caseLabels[i + 1], cases[i]);
      }
    }
    this.emitLabel(endLabel, LABEL_KIND.SWITCH_END, node);
    this.state.branchRegions.push(region);
  }

  /**
   * A `try` / `catch` / `finally`.
   *
   * The IR has explicit instructions for this: a CATCH_HANDLER binds the
   * exception to the handler's name, and a FINALLY_ENTER marks the block
   * every exit of the region runs through.
   *
   * The `finally` block is on the path of EVERY exit: the try body's normal
   * end, each catch body's normal end, and a `return` out of the region.
   * A `return` is the awkward one: it leaves the function, and PHP still
   * runs the `finally` first. So the value is put aside, a flag says that
   * there is one, and the function returns after the `finally` has run.
   */
  lowerTry(node) {
    this.state.newScope();
    const hasFinally = !!node.always;
    const elseLabel = this.state.newLabel("L");
    const afterLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    const finallyLabel = hasFinally ? this.state.newLabel("L_FINALLY") : null;

    const tryStart = this.state.instructions.length;
    this.lowerBody(node.body?.children ?? []);
    const tryEnd = this.state.instructions.length - 1;
    this.emitJump(hasFinally ? finallyLabel : elseLabel, node);

    const catchLabels = [];
    const catchTypes = [];
    let catchStart = -1;
    let catchEnd = -1;
    for (const handler of node.catches ?? []) {
      const types = this.catchTypesOf(handler);
      const typeName = types[0];
      const label = this.state.newLabel(`L_CATCH_${typeName}`);
      if (catchStart < 0) catchStart = this.state.instructions.length;
      this.emitLabel(label, LABEL_KIND.CATCH_ENTRY, handler);
      const variableName = this.nameText(handler.variable);
      if (variableName) {
        const variable = varOp(variableName);
        this.declareLocal(variable);
        this.emit({
          kind: KIND.CATCH_HANDLER,
          dest: variable,
          ctorType: { kind: TYPE.CLASS, name: typeName },
          ...this.position(handler),
        });
      } else {
        this.emit({ kind: KIND.CATCH_HANDLER, ...this.position(handler) });
      }
      this.lowerBody(handler.body?.children ?? []);
      catchEnd = this.state.instructions.length - 1;
      catchLabels.push(label);
      catchTypes.push(types);
      this.emitJump(hasFinally ? finallyLabel : afterLabel, handler);
    }

    this.emitLabel(afterLabel, LABEL_KIND.BLOCK, node);
    this.emitJump(hasFinally ? finallyLabel : endLabel, node);
    this.emitLabel(elseLabel, LABEL_KIND.BLOCK, node);

    let finallyStart = -1;
    let finallyEnd = -1;
    let returnFlag = null;
    let returnValue = null;
    if (hasFinally) {
      returnFlag = this.state.newTemp(TYPE.INT);
      returnValue = this.state.newTemp();
      this.emit({
        kind: KIND.MOVE,
        dest: returnFlag,
        lhs1: constOp(CONST.INT, "0"),
        ...this.position(node),
      });
      this.state.finallyStack.push({ label: finallyLabel, returnFlag, returnValue });
      finallyStart = this.state.instructions.length;
      this.emitLabel(finallyLabel, LABEL_KIND.FINALLY_ENTRY, node);
      this.emit({
        kind: KIND.FINALLY_ENTER,
        label: finallyLabel,
        labelRef: this.labelRefOf(finallyLabel),
        ...this.position(node),
      });
      this.lowerBody(node.always.children ?? []);
      finallyEnd = this.state.instructions.length - 1;
      this.state.finallyStack.pop();
      const finallyDone = this.state.newLabel("L");
      this.emitJump(finallyDone, node);
      this.emitLabel(finallyDone, LABEL_KIND.BLOCK, node);
      const returnLabel = this.state.newLabel("L");
      this.emitBranch(
        returnFlag,
        returnLabel,
        endLabel,
        { kind: GUARD.COMPARE, lhs: returnFlag, op: "NE", rhs: constOp(CONST.INT, "0") },
        node,
      );
      this.emitLabel(returnLabel, LABEL_KIND.BLOCK, node);
      this.emit({ kind: KIND.RETURN, lhs1: returnValue, ...this.position(node) });
    }
    this.emitLabel(endLabel, LABEL_KIND.BLOCK, node);

    this.state.tryRegions.push({
      tryStart,
      tryEnd,
      catchStart,
      catchEnd,
      catchLabels,
      catchTypes,
      finallyLabel,
      finallyStart,
      finallyEnd,
      syntheticFinally: false,
      finallyExits: hasFinally ? [finallyLabel, ...catchLabels] : [],
      resourceVars: [],
    });
  }

  /**
   * The exception types a catch handles.
   *
   * A catch may name several types, and then it handles whichever was
   * thrown. The TAC's catch handler carries ONE type, so the first one is
   * the one the handler binds -- but the others are recorded, because a
   * catch of two types is a catch of two types and not of the first one.
   *
   * @param {object} handler the catch node
   * @return {string[]} the qualified type names
   */
  catchTypesOf(handler) {
    const types = handler.what ?? [];
    if (types.length === 0) return ["Throwable"];
    const names = types.map((t) => this.qualifiedClassNameOf(t) ?? "Throwable");
    if (names.length > 1) {
      this.emitDrop(
        "exc.catch.multiType",
        `the catch handles ${names.join(" and ")}, and the TAC's catch handler carries one type, `
          + "so only the first one is bound",
        DROP_IMPACT.STRUCTURE,
        handler,
      );
    }
    return names;
  }

  /**
   * The exception type a catch handles.
   *
   * @param {object} handler the catch node
   * @return {string} the qualified type name
   */
  catchTypeName(handler) {
    return this.catchTypesOf(handler)[0];
  }

  /**
   * Lower a loop's body with the loop's own break and continue labels.
   *
   * The labels are kept on a STACK, so that a `break 2` knows which loop it
   * leaves: the innermost one, the one around it, or the one around that.
   *
   * @param {string} breakLabel where a `break` goes
   * @param {string} continueLabel where a `continue` goes
   * @param {string} kind the kind of the label a `break` goes to
   * @param {Function} body the lowering
   */
  withLoopLabels(breakLabel, continueLabel, kind, body) {
    const outer = this.state.breakLabel;
    const outerContinue = this.state.continueLabel;
    this.state.breakLabel = breakLabel;
    this.state.continueLabel = continueLabel;
    this.state.loopStack.push({ kind: "loop", breakLabel, continueLabel });
    try {
      body();
    } finally {
      this.state.breakLabel = outer;
      this.state.continueLabel = outerContinue;
      this.state.loopStack.pop();
    }
  }

  /**
   * Lower a switch's cases with the switch's own break label.
   *
   * A `continue` inside a switch continues the LOOP the switch is in, so
   * only the break label changes -- and a switch is not a loop, so a
   * `continue`'s levels do not count it.
   *
   * @param {string} breakLabel where a `break` goes
   * @param {Function} body the lowering
   */
  withBreakLabel(breakLabel, body) {
    const outer = this.state.breakLabel;
    this.state.breakLabel = breakLabel;
    this.state.loopStack.push({ kind: "switch", breakLabel, continueLabel: null });
    try {
      body();
    } finally {
      this.state.breakLabel = outer;
      this.state.loopStack.pop();
    }
  }

  // ---- expressions ----------------------------------------------------------------------

  lowerExpression(node) {
    if (!node) return undefined;
    switch (node.kind) {
      case "variable": {
        // A variable variable (`$$x`) has an OBJECT where a name would be,
        // and the name it has is only known at run time. So it is a slot of
        // the scope's variable table, and it is not bound to any name the
        // analysis can see.
        if (typeof node.name !== "string") {
          return this.lowerVariableVariableRead(node);
        }
        return varOp(node.name, this.tacTypeOfVariable(node.name));
      }
      case "identifier": {
        // A name in an expression position is a constant, and its value is
        // not known here. The NAME it is known by is a fact about the file:
        // a `use const` binds an alias to a qualified name.
        const alias = this.constantAliases.get(this.namespace)?.get(node.name);
        return varOp(alias ?? node.name);
      }
      case "name": {
        // A bare CONSTANT -- `PHP_EOL`, `DEBUG` -- is a different node kind
        // from an identifier, and it had no branch of its own: it fell
        // through to the fallback, which THROWS, and the throw ends the whole
        // statement. A statement that mentions a constant -- `if (DEBUG) {
        // ... }` -- was therefore lowered to nothing at all, and every sink
        // in it with it.
        //
        // The value of the constant is not known here; the NAME it is known
        // by is, and a `use const` binds an alias to a qualified name.
        const alias = this.constantAliases.get(this.namespace)?.get(node.name);
        return varOp(alias ?? node.name);
      }
      case "number":
        return this.numberConstant(node.value);
      case "string":
        return constOp(CONST.STRING, node.value);
      case "boolean":
        return constOp(CONST.BOOL, node.value ? "true" : "false");
      case "null":
      case "nullkeyword":
        // The parser calls the null literal `nullkeyword`, and a case that
        // spells it `null` alone never matches: the literal became an unknown
        // value.
        return constOp(CONST.NULL);
      case "magic":
        return constOp(CONST.STRING, node.value ?? "");
      case "encapsed":
        return this.lowerEncapsed(node);
      case "assign":
        return this.lowerAssign(node);
      case "bin":
        return this.lowerBinary(node);
      case "unary":
        return this.lowerUnary(node);
      case "call":
        return this.lowerCall(node);
      case "propertylookup":
      case "nullsafepropertylookup":
        return this.lowerPropertyRead(node);
      case "offsetlookup":
        return this.lowerOffsetRead(node);
      case "staticlookup":
        return this.lowerStaticRead(node);
      case "new":
        return this.lowerNew(node);
      case "cast": {
        const operand = this.lowerExpression(node.expr);
        const castType = this.tacTypeOfName(node.type);
        const dest = this.state.newTemp(castType);
        this.emit({
          kind: KIND.UNARYOP,
          dest,
          lhs1: operand,
          unop: "CAST",
          castType,
          ...this.position(node),
        });
        return dest;
      }
      case "isset": {
        // Whether a variable is set is not known here, so the answer is
        // not a claim: it is the value at the key, tested against null, and
        // the guard says the more precise thing -- the key is there.
        return this.lowerPresence(node).operand;
      }
      case "empty": {
        return this.lowerPresence(node).operand;
      }
      case "print": {
        return this.lowerExpression(node.expression);
      }
      case "clone": {
        const value = this.lowerExpression(node.what);
        const dest = this.state.newTemp();
        this.emit({ kind: KIND.MOVE, dest, lhs1: value, ...this.position(node) });
        return dest;
      }
      case "include": {
        const target = this.lowerExpression(node.target);
        const dest = this.state.newTemp();
        this.emit({
          kind: KIND.CALL,
          dest,
          calleeSig: `${SYNTHETIC_OWNER}.include/1`,
          args: [constOp(CONST.STRING, SYNTHETIC_OWNER), target],
          ...this.position(node),
        });
        return dest;
      }
      case "eval": {
        // `eval` RUNS the code it is given, so the engine cannot model what
        // happens inside it: it is a black box.
        //
        // But it is still a CALL, and a call the sink catalogue can name.
        // `eval($_GET['x'])` is the most classic PHP code injection there is,
        // and the earlier lowering lost both halves of it: the expression had
        // no lowering of its own, so the whole call became a HAVOC -- which
        // carries no callee key for a rule to name, and no taint on its
        // destination, so the taint stopped AT the eval and everything after
        // it looked clean.
        //
        // The kind is UNINTERPRETED rather than CALL, and the difference is
        // the point: an unmodelled call keeps its arguments' taint on its
        // result, because the value the evaluated code produces is
        // attacker-influenced when the code is. A CALL would look for a
        // summary for `<php>.eval/1`, find none, and leave the result
        // clean.
        const argument = node.source
          ? this.lowerExpression(node.source)
          : constOp(CONST.STRING, "");
        const dest = this.state.newTemp();
        this.emit({
          kind: KIND.UNINTERPRETED,
          dest,
          calleeSig: `${SYNTHETIC_OWNER}.eval/1`,
          args: [constOp(CONST.STRING, SYNTHETIC_OWNER), argument],
          ...this.position(node),
        });
        return dest;
      }
      case "silent": {
        // The `@` operator suppresses errors; the value is the operand's.
        return this.lowerExpression(node.expr);
      }
      case "retif":
        return this.lowerConditional(node);
      case "array":
        return this.lowerArrayLiteral(node);
      case "closure":
      case "arrowfunc":
        return this.lowerClosure(node);
      case "yield":
      case "yieldfrom":
        return this.lowerYield(node);
      case "match":
        return this.lowerMatch(node);
      case "list":
        return this.lowerListExpression(node);
      case "nowdoc":
        return constOp(CONST.STRING, node.value ?? "");
      case "exit": {
        // `exit` ends the execution unit: the TAC's way to say that is a
        // THROW.
        const value = node.expression ? this.lowerExpression(node.expression) : undefined;
        const dest = this.state.newTemp();
        this.emit({
          kind: KIND.THROW,
          lhs1: value,
          dest,
          ctorType: { kind: TYPE.CLASS, name: "Exit" },
          ...this.position(node),
        });
        return dest;
      }
      case "pre":
      case "post":
        return this.lowerIncrement(node);
      case "assignref": {
        // `$a = &$b`: the two names are ONE location. The TAC has no way to
        // say that, so the write names the location it aliases.
        const value = this.lowerExpression(node.right);
        this.lowerWrite(node.left, value, node);
        this.emitDrop(
          "op.assign.byref",
          "a reference assignment makes two names one location, and the TAC's locations are one "
            + "name each",
          DROP_IMPACT.SEMANTICS,
          node,
        );
        return value;
      }
      case "byref": {
        // A reference to a variable: the value is the variable's value, and
        // the location is the variable's.
        const value = this.lowerExpression(node.what);
        this.emitDrop(
          "op.assign.byref",
          "a by-reference argument makes the callee's parameter and the caller's variable one "
            + "location, and the TAC's locations are one name each",
          DROP_IMPACT.SEMANTICS,
          node,
        );
        return value;
      }
      case "reference":
        return this.emitHavoc(node);
      case "variadic": {
        // `f(...$a)`: the arguments are unpacked by the callee, and how
        // many there are is only known at run time.
        const value = this.lowerExpression(node.what);
        this.emitDrop(
          "op.call.spread",
          "an unpacked argument list has a length that is only known at run time",
          DROP_IMPACT.VALUE,
          node,
        );
        return value;
      }
      case "variadicplaceholder": {
        // `foo(...)` is NOT a call: it is the callable itself. So no CALL is
        // emitted for it, and the value is the callable's reference.
        this.emitDrop(
          "expr.fcc.notCall",
          "a first-class callable syntax is the callable itself and not a call of it",
          DROP_IMPACT.STRUCTURE,
          node,
        );
        return this.emitHavoc(node);
      }
      case "unset": {
        this.lowerUnset(node);
        return this.state.newTemp(TYPE.BOOLEAN);
      }
      default:
        throw new Error(`this expression has no TAC lowering: ${node.kind}`);
    }
  }

  /**
   * The scope's variable table.
   *
   * A variable variable (`$$x`) names a slot whose NAME is only known at run
   * time, so the scope's variables live in a container and the variable
   * variable reaches into it. The table is made once per function, so that
   * every variable variable of the function reaches the same table.
   *
   * @return {object} the container operand
   */
  scopeTable() {
    if (this.state.scopeTable) return this.state.scopeTable;
    const table = varOp("t_scope", { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } });
    this.state.scopeTable = table;
    this.declareLocal(table);
    return table;
  }

  /**
   * A read of a variable variable: `$$x`.
   *
   * The name is a run-time value, so the read is a slot of the scope's
   * variable table whose key is that value. The variable it names is NOT
   * the same location as the name in the source, and the difference is
   * recorded: a write through one name is not seen through the other.
   */
  lowerVariableVariableRead(node) {
    const name = this.lowerExpression(node.name);
    const table = this.scopeTable();
    const dest = this.state.newTemp();
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest,
      lhs1: table,
      lhs2: name,
      ...this.position(node),
    });
    this.emitDrop(
      "expr.varvar.alias",
      "the name of a variable variable is only known at run time, so the variable it names "
        + "cannot be the same location as the name the source uses",
      DROP_IMPACT.SEMANTICS,
      node,
    );
    return dest;
  }

  /**
   * A closure: an INDEPENDENT function, and a closure object that holds it.
   *
   * The TAC has no nested function and no function value, so the body
   * cannot live in the enclosing function's stream: it becomes a function
   * of its own. The value the expression produces is the closure OBJECT --
   * a container that holds the captured values -- and the body's function
   * takes that object as its receiver.
   *
   * The captured values are COPIED into the object, so a closure that
   * outlives the scope it was made in still sees them. A capture by
   * reference is an alias, and the TAC cannot say that.
   */
  lowerClosure(node) {
    const outer = this.state;
    this.closureCounter += 1;
    const name = `closure$${this.closureCounter}`;
    const owner = this.closureOwner();
    const qualified = `${owner}$${name}`;
    // An arrow function has no `use` list: the parser gives none, because its
    // captures are implicit.  The list has to be worked out from the body.
    const uses = node.kind === "arrowfunc" ? this.implicitCapturesOf(node) : (node.uses ?? []);
    const captureNames = uses.map((use) => this.nameText(use) ?? "cap");
    const state = this.beginFunction(name, "", outer?.enclosingClass ?? null);
    state.signature = this.closureSignature(node, qualified, captureNames);
    state.synthetic = true;
    state.memberKind = MEMBER_KIND.METHOD;
    try {
      // The captured values are the LEADING PARAMETERS of the closure's own
      // function, and the declared ones follow them. The engine binds a
      // call's user arguments to the callee's parameters in order, so the
      // captures have to be the leading ones: the call hands them over
      // first, exactly as it hands over a nested function's captures.
      //
      // The closure's own receiver is NOT a parameter. The TAC's convention
      // is that the receiver lives in the call's argument slot ZERO, and
      // the callee's parameters are the arguments after it -- a receiver
      // that was a parameter would shift every argument by one.
      for (const capture of captureNames) {
        state.params.push(varOp(capture));
        state.scopeBindings.push({ name: capture, kind: SCOPE_KIND.CAPTURED, byRef: false });
      }
      for (const parameter of node.arguments ?? []) {
        const parameterName = this.nameText(parameter.name) ?? "p";
        state.params.push(varOp(parameterName));
        state.parameters.push({
          name: parameterName,
          type: this.tacTypeOfName(this.typeNameOf(parameter.type)),
          optional: parameter.value !== undefined && parameter.value !== null,
          variadic: parameter.variadic === true || parameter.isVariadic === true,
          byRef: parameter.byref === true || parameter.isRef === true,
          keywordOnly: false,
          defaultValue: null,
        });
      }
      if (node.body) {
        if (node.body.kind === "expressionstatement") {
          this.lowerExpression(node.body.expression);
        } else if (Array.isArray(node.body.children)) {
          this.lowerBody(node.body.children);
        } else {
          this.lowerExpression(node.body);
        }
      }
    } finally {
      this.state = outer;
    }
    const closure = this.finishFunction(state);
    this.moduleFunctions.push(closure);

    // The closure object: a container that holds the captured values.
    const object = this.state.newTemp({ kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } });
    this.emit({
      kind: KIND.NEWARRAY,
      dest: object,
      ctorType: { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } },
      lhs1: constOp(CONST.INT, String(captureNames.length)),
      ...this.position(node),
    });
    uses.forEach((use, index) => {
      const capture = captureNames[index];
      const value = this.lowerExpression(use);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: object,
        lhs2: constOp(CONST.STRING, capture),
        dest: value,
        ...this.position(node),
      });
      if (use && use.byref === true) {
        this.emitDrop(
          "op.assign.byref",
          `the closure captures ${capture} by reference, and the TAC's locations are one name each`,
          DROP_IMPACT.SEMANTICS,
          node,
        );
      }
    });
    this.closureByObject.set(object.name, {
      qualified,
      arity: captureNames.length + (node.arguments ?? []).length,
      captures: captureNames,
    });
    return object;
  }

  /**
   * The variables an arrow function captures.
   *
   * An arrow function captures by value the outer variables its body uses, and
   * the parser does not say which those are: an `arrowfunc` has no `use` list,
   * while a closure has one.  So the list has to be worked out from the body.
   *
   * The rule is the language's own: a name the body READS, that the arrow
   * function does not bind itself, and that is not in scope everywhere, is
   * captured.  A name the body ASSIGNS is the arrow function's own -- the
   * capture is by value, so assigning to it does not reach the outer variable,
   * and the arrow function does not capture the outer one.
   *
   * The captures are handed over exactly like an explicit `use` list: copied
   * into the closure object, and passed as the closure's leading parameters.
   *
   * @param {object} node the arrow function
   * @return {object[]} the captures, as the `use` entries they stand for
   */
  implicitCapturesOf(node) {
    const bound = new Set();
    for (const parameter of node.arguments ?? []) {
      const name = this.nameText(parameter.name);
      if (name) bound.add(name);
    }
    const reads = new Set();
    this.collectArrowReads(node.body, bound, reads);
    const candidates = [...reads].filter((name) => !bound.has(name));
    const names = candidates.filter((name) => !this.isAlwaysInScope(name)).sort();
    return names.map((name) => ({ kind: "variable", name, byref: false }));
  }

  /**
   * Whether a name is in scope everywhere, so it is not a capture.
   *
   * @param {string} name the name
   * @return {boolean} whether the name is in scope everywhere
   */
  isAlwaysInScope(name) {
    return name === THE_OBJECT || SUPERGLOBALS.has(name);
  }

  /**
   * The names an arrow function's body reads, and the names it binds.
   *
   * @param {object} node the expression
   * @param {Set<string>} bound the names the arrow function binds itself
   * @param {Set<string>} reads the names the body reads
   */
  collectArrowReads(node, bound, reads) {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      for (const item of node) this.collectArrowReads(item, bound, reads);
      return;
    }
    if (typeof node.kind !== "string") return;
    if (node.kind === "variable") {
      // A variable variable (`$$x`) names a slot whose name is only known at
      // run time, so there is no name to capture.
      if (typeof node.name === "string") reads.add(node.name);
      return;
    }
    if (node.kind === "assign") {
      // What the body WRITES to is the arrow function's own, and what it
      // reads on the right is the enclosing scope's.
      this.collectArrowTarget(node.left, bound, reads);
      this.collectArrowReads(node.right, bound, reads);
      return;
    }
    if (node.kind === "list") {
      this.collectArrowTarget(node, bound, reads);
      return;
    }
    if (node.kind === "closure") {
      // A nested closure has its OWN scope: its body is not read here.  Its
      // `use` list, on the other hand, is resolved in THIS scope.
      for (const use of node.uses ?? []) this.collectArrowReads(use, bound, reads);
      return;
    }
    if (node.kind === "arrowfunc") {
      // A nested arrow function captures from THIS scope, so the names its
      // body reads are read here too -- but the names it binds are its own,
      // and a name it binds must not become a capture of THIS one.
      const inner = new Set(bound);
      for (const parameter of node.arguments ?? []) {
        const name = this.nameText(parameter.name);
        if (name) inner.add(name);
      }
      const innerReads = new Set();
      this.collectArrowReads(node.body, inner, innerReads);
      for (const name of innerReads) {
        if (!inner.has(name)) reads.add(name);
      }
      return;
    }
    for (const key of Object.keys(node)) {
      if (key === "kind" || key === "loc") continue;
      const value = node[key];
      if (value && typeof value === "object") this.collectArrowReads(value, bound, reads);
    }
  }

  /**
   * The names an assignment target binds.
   *
   * @param {object} node the target
   * @param {Set<string>} bound the names the arrow function binds itself
   * @param {Set<string>} reads the names the target reads
   */
  collectArrowTarget(node, bound, reads) {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      for (const item of node) this.collectArrowTarget(item, bound, reads);
      return;
    }
    if (node.kind === "variable") {
      if (typeof node.name === "string") bound.add(node.name);
      return;
    }
    if (node.kind === "list") {
      for (const item of node.items ?? []) this.collectArrowTarget(item, bound, reads);
      return;
    }
    if (node.kind === "entry") {
      this.collectArrowTarget(node.value, bound, reads);
      return;
    }
    // A property or an element is not a name: the RECEIVER is read, though.
    this.collectArrowReads(node, bound, reads);
  }

  /**
   * The owner of a closure's own function.
   *
   * A closure made inside a method belongs to the class; one made at the
   * top level of a file belongs to the file's namespace.
   */
  closureOwner() {
    if (this.state?.enclosingClass) return this.state.enclosingClass;
    const namespace = this.namespace;
    return namespace ? namespace : "global";
  }

  /**
   * The signature of a closure's own function.
   *
   * The captures come FIRST: they are the leading parameters of the
   * function, so the signature counts them before the declared ones.
   */
  closureSignature(node, qualified, captureNames) {
    const types = captureNames.map(() => "any");
    for (const parameter of node.arguments ?? []) {
      types.push(this.typeNameOf(parameter.type) ?? "any");
    }
    return `${qualified}(${types.join(",")}):any`;
  }

  /**
   * A `yield`: the generator's state machine.
   *
   * The value is evaluated HERE, in the generator's own stream, so that a
   * source that reaches a sink through the yielded value is visible. The
   * value is put in the generator's slot, and the generator resumes at the
   * label that follows.
   *
   * The states are labelled in the generator's own stream rather than split
   * into functions of their own: the TAC's jump goes to a LABEL in the same
   * function, and there is no instruction that resumes another function.
   */
  lowerYield(node) {
    const value = node.value ? this.lowerExpression(node.value) : undefined;
    const slot = this.generatorSlot();
    this.emit({
      kind: KIND.MOVE,
      dest: slot,
      lhs1: value,
      ...this.position(node),
    });
    if (node.key) {
      const key = this.lowerExpression(node.key);
      this.emit({
        kind: KIND.MOVE,
        dest: this.generatorKeySlot(),
        lhs1: key,
        ...this.position(node),
      });
    }
    if (node.kind === "yieldfrom") {
      this.emitDrop(
        "expr.generator.delegate",
        "a generator that delegates to another one resumes the other one's states, and the TAC "
          + "has no way to say that",
        DROP_IMPACT.STRUCTURE,
        node,
      );
    }
    const resume = this.state.newLabel("L_RESUME");
    this.emitJump(resume, node);
    this.emitLabel(resume, LABEL_KIND.BLOCK, node);
    this.state.generatorStates.push(resume);
    this.emitDrop(
      "expr.generator.states",
      "the generator's states are labelled in the generator's own stream, because the TAC's jump "
        + "goes to a label of the same function and cannot resume another one",
      DROP_IMPACT.STRUCTURE,
      node,
    );
    return slot;
  }

  /**
   * The slot a generator's yielded value is put in.
   */
  generatorSlot() {
    if (!this.state.generatorSlot) {
      this.state.generatorSlot = varOp("t_gen_yielded");
      this.declareLocal(this.state.generatorSlot);
    }
    return this.state.generatorSlot;
  }

  /**
   * The slot a generator's yielded KEY is put in.
   */
  generatorKeySlot() {
    if (!this.state.generatorKeySlot) {
      this.state.generatorKeySlot = varOp("t_gen_key");
      this.declareLocal(this.state.generatorKeySlot);
    }
    return this.state.generatorKeySlot;
  }

  /**
   * A `list` in an expression position: `[$a, $b] = $c` used as a value.
   *
   * A list is an array, so it is lowered as one: a container with one entry
   * per element.
   */
  lowerListExpression(node) {
    const items = node.items ?? [];
    const dest = this.state.newTemp({ kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } });
    this.emit({
      kind: KIND.NEWARRAY,
      dest,
      ctorType: { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } },
      lhs1: constOp(CONST.INT, String(items.length)),
      ...this.position(node),
    });
    items.forEach((entry, index) => {
      if (!entry) return;
      const value = this.lowerExpression(entry.value);
      const key = entry.key ? this.lowerKey(entry.key) : constOp(CONST.INT, String(index));
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: dest,
        lhs2: key,
        dest: value,
        ...this.position(entry),
      });
    });
    return dest;
  }

  /**
   * An increment or a decrement: `++$i`, `$i--`.
   *
   * The variable is read, combined, and WRITTEN BACK: the TAC tracks the
   * value of a location, not the statement that changed it, so a change
   * that is not written back is a change the analysis never sees.
   *
   * A prefix returns the NEW value and a postfix returns the OLD one.
   */
  lowerIncrement(node) {
    const target = node.what;
    const current = this.lowerExpression(target);
    // The parser gives the operator as `type`, and it spells it "+" or "-",
    // not "++" and "--". A test for "++" never matches, so every increment
    // became a decrement.
    const operator = node.type ?? node.operation;
    const binop = operator === "+" || operator === "++" ? "ADD" : "SUB";
    const value = this.state.newTemp();
    this.emit({
      kind: KIND.BINOP,
      dest: value,
      lhs1: current,
      lhs2: constOp(CONST.INT, "1"),
      binop,
      ...this.position(node),
    });
    this.lowerWrite(target, value, node);
    return node.kind === "pre" ? value : current;
  }

  /**
   * An interpolated string.
   *
   * The parts are joined into one string. A BACKTICK string is a command: PHP
   * runs it through the shell, so the joined string is passed to a shell
   * stand-in rather than being a plain value.
   */
  lowerEncapsed(node) {
    const parts = [];
    for (const part of node.value ?? []) {
      const expression = part && part.expression ? part.expression : part;
      if (!expression) continue;
      if (expression.kind === "string") {
        parts.push(constOp(CONST.STRING, expression.value));
      } else {
        parts.push(this.lowerExpression(expression));
      }
    }
    let result;
    if (parts.length === 0) {
      result = constOp(CONST.STRING, "");
    } else {
      result = parts[0];
      for (const part of parts.slice(1)) {
        const dest = this.state.newTemp(TYPE.STRING);
        this.emit({
          kind: KIND.BINOP,
          dest,
          lhs1: result,
          lhs2: part,
          binop: "ADD",
          ...this.position(node),
        });
        result = dest;
      }
    }
    if (node.type === "shell") {
      const dest = this.state.newTemp();
      this.emit({
        kind: KIND.CALL,
        dest,
        calleeSig: `${SYNTHETIC_OWNER}.shell/1`,
        args: [constOp(CONST.STRING, SYNTHETIC_OWNER), result],
        ...this.position(node),
      });
      return dest;
    }
    return result;
  }

  /**
   * An array literal.
   *
   * A PHP array is a keyed container: an entry's identity is the pair
   * (array, key). So the array is built with one store per entry, and the
   * key is the index operand of the store.
   */
  lowerArrayLiteral(node) {
    const items = node.items ?? [];
    const dest = this.state.newTemp({ kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } });
    this.emit({
      kind: KIND.NEWARRAY,
      dest,
      ctorType: { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } },
      lhs1: constOp(CONST.INT, String(items.length)),
      ...this.position(node),
    });
    items.forEach((entry, index) => {
      if (!entry) return;
      const value = this.lowerExpression(entry.value);
      if (entry.unpack === true) {
        // The number of elements an unpack contributes is only known at run
        // time, so the store says that it is an unpack and the key is a
        // run-time value.
        const key = this.state.newTemp(TYPE.INT);
        this.emit({
          kind: KIND.ARRAYSTORE,
          lhs1: dest,
          lhs2: key,
          dest: value,
          spread: true,
          ...this.position(entry),
        });
        this.emitDrop(
          "op.array.spread",
          "an unpacked entry contributes a number of elements that is only known at run time",
          DROP_IMPACT.VALUE,
          entry,
        );
        return;
      }
      const key = entry.key ? this.lowerKey(entry.key) : constOp(CONST.INT, String(index));
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: dest,
        lhs2: key,
        dest: value,
        ...this.position(entry),
      });
    });
    return dest;
  }

  /**
   * A conditional expression: `$a ? $b : $c`.
   *
   * Both arms write into one destination, and the engine merges the two
   * paths at the label that follows.
   *
   * The SHORT form `$a ?: $b` has no true arm: it yields `$a` when `$a` is
   * truthy and `$b` otherwise. So the true arm is the TEST's own value, and
   * using a placeholder for it would throw away the taint that came in with
   * the test.
   */
  lowerConditional(node) {
    const { operand: condition, guard } = this.lowerCondition(node.test);
    const thenLabel = this.state.newLabel("L");
    const elseLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    const dest = this.state.newTemp();
    this.emitBranch(condition, thenLabel, elseLabel, guard, node);
    this.emitLabel(thenLabel, LABEL_KIND.BLOCK, node);
    const whenTrue = node.trueExpr ? this.lowerExpression(node.trueExpr) : condition;
    this.emit({ kind: KIND.MOVE, dest, lhs1: whenTrue, ...this.position(node) });
    this.emitJump(endLabel, node);
    this.emitLabel(elseLabel, LABEL_KIND.BLOCK, node);
    const whenFalse = node.falseExpr ? this.lowerExpression(node.falseExpr) : this.emitHavoc(node);
    this.emit({ kind: KIND.MOVE, dest, lhs1: whenFalse, ...this.position(node) });
    this.emitLabel(endLabel, LABEL_KIND.BLOCK, node);
    return dest;
  }

  /**
   * A `match` expression.
   *
   * PHP's match picks the FIRST arm whose conditions hold, and throws when
   * no arm holds. The TAC has no match, so the arms become a chain of
   * equality tests.
   *
   * The chain's last false target is the "nothing matched" path, and that
   * path is laid out RIGHT AFTER the chain: a chain that fell through into
   * the first arm's body would report that arm's findings for a value that
   * PHP would have thrown on.
   */
  lowerMatch(node) {
    this.state.newScope();
    const condition = this.lowerExpression(node.cond);
    const arms = node.arms ?? [];
    const endLabel = this.state.newLabel("L");
    const armLabels = arms.map(() => this.state.newLabel("L"));
    const dest = this.state.newTemp();
    const noMatchLabel = this.state.newLabel("L");

    // The default arm is the one that holds whatever the others do not, so
    // it is not a test of its own.
    const defaultIndex = arms.findIndex((arm) => !arm.conds || arm.conds.length === 0);

    const tests = [];
    for (let i = 0; i < arms.length; i++) {
      if (i === defaultIndex) continue;
      for (const conditionNode of arms[i].conds ?? []) {
        tests.push({ arm: i, condition: conditionNode });
      }
    }

    const region = {
      id: `R${this.state.branchRegions.length + 1}`,
      kind: "MATCH",
      subject: condition,
      cases: [],
      defaultLabel: defaultIndex >= 0 ? armLabels[defaultIndex] : null,
      fallThrough: false,
    };

    const testLabels = tests.map(() => this.state.newLabel("L"));
    for (let k = 0; k < tests.length; k++) {
      this.emitLabel(testLabels[k], LABEL_KIND.BLOCK, arms[tests[k].arm]);
      const test = this.lowerExpression(tests[k].condition);
      const matched = this.state.newTemp(TYPE.BOOLEAN);
      this.emit({
        kind: KIND.BINOP,
        dest: matched,
        lhs1: condition,
        lhs2: test,
        binop: "EQ",
        ...this.position(arms[tests[k].arm]),
      });
      const falseTarget =
        k + 1 < tests.length
          ? testLabels[k + 1]
          : defaultIndex >= 0
            ? armLabels[defaultIndex]
            : noMatchLabel;
      this.emitBranch(
        matched,
        armLabels[tests[k].arm],
        falseTarget,
        { kind: GUARD.COMPARE, lhs: condition, op: "EQ", rhs: test },
        arms[tests[k].arm],
      );
      region.cases.push({
        id: `C${k + 1}`,
        guards: [{ lhs: condition, op: "EQ", rhs: test }],
        bodyLabel: armLabels[tests[k].arm],
        nextLabel: falseTarget,
      });
    }

    // No arm matched: PHP THROWS, and the TAC has a THROW. So the path
    // where no arm matched ends in a throw rather than in an unknown value:
    // an unknown value would go on and produce a result that PHP would never
    // produce.
    //
    // A match with a DEFAULT arm has no such path: the default arm holds
    // whatever the others do not. A throw there would be a throw the code
    // cannot make, and a rule that reads throws would report it.
    if (defaultIndex < 0) {
      this.emitLabel(noMatchLabel, LABEL_KIND.BLOCK, node);
      this.emit({
        kind: KIND.THROW,
        lhs1: constOp(CONST.STRING, "UnhandledMatchError"),
        ctorType: { kind: TYPE.CLASS, name: "UnhandledMatchError" },
        ...this.position(node),
      });
    }

    for (let i = 0; i < arms.length; i++) {
      this.emitLabel(armLabels[i], LABEL_KIND.BLOCK, arms[i]);
      const value = this.lowerExpression(arms[i].body);
      this.emit({ kind: KIND.MOVE, dest, lhs1: value, ...this.position(arms[i]) });
      this.emitJump(endLabel, arms[i]);
    }
    this.emitLabel(endLabel, LABEL_KIND.BLOCK, node);
    this.state.branchRegions.push(region);
    return dest;
  }

  numberConstant(value) {
    const text = String(value ?? "0");
    if (/^-?\d+$/.test(text)) {
      const number = Number(text);
      if (Number.isSafeInteger(number)) {
        return constOp(CONST.INT, text);
      }
      return constOp(CONST.LONG, text);
    }
    return constOp(CONST.DOUBLE, text);
  }

  /**
   * An assignment.
   *
   * A plain assignment is a write; a compound assignment (`+=` and friends)
   * is a read, a BINOP, and then a write.
   *
   * The null-coalescing assignment (`??=`) writes ONLY when the target is
   * null, so the write is on a branch and not in the middle of the stream.
   */
  lowerAssign(node) {
    const operator = node.operator ?? "=";
    if (operator === "=") {
      const value = this.lowerExpression(node.right);
      this.lowerWrite(node.left, value, node);
      return value;
    }
    if (operator === "??=") {
      const current = this.lowerExpression(node.left);
      // The write happens when the target IS NULL, so the branch's operand is
      // the "is not null" test and the write is on the FALSE edge -- the same
      // way round as the guard, so that the edge and the guard agree.
      const notNull = this.state.newTemp(TYPE.BOOLEAN);
      this.emit({
        kind: KIND.BINOP,
        dest: notNull,
        lhs1: current,
        lhs2: constOp(CONST.NULL),
        binop: "NE",
        ...this.position(node),
      });
      const writeLabel = this.state.newLabel("L");
      const endLabel = this.state.newLabel("L");
      this.emitBranch(
        notNull,
        endLabel,
        writeLabel,
        { kind: GUARD.COMPARE, lhs: current, op: "NE", rhs: constOp(CONST.NULL) },
        node,
      );
      this.emitLabel(writeLabel, LABEL_KIND.BLOCK, node);
      const value = this.lowerExpression(node.right);
      this.lowerWrite(node.left, value, node);
      this.emitJump(endLabel, node);
      this.emitLabel(endLabel, LABEL_KIND.BLOCK, node);
      return current;
    }
    if (operator === "**=") {
      // The power is expanded the same way the power operator is: a constant
      // exponent becomes the repeated multiplication it stands for.
      const current = this.lowerExpression(node.left);
      const exponent = this.powerExponentOf(node.right);
      if (exponent !== null) {
        const value = this.repeatMultiplication(current, exponent, node);
        this.lowerWrite(node.left, value, node);
        return value;
      }
      const right = this.lowerExpression(node.right);
      const value = this.state.newTemp();
      this.emit({
        kind: KIND.BINOP,
        dest: value,
        lhs1: current,
        lhs2: right,
        binop: "MUL",
        ...this.position(node),
      });
      this.lowerWrite(node.left, value, node);
      this.emitDrop(
        "op.bin.power",
        "the TAC has no power operator, so a power whose exponent is only known at run time is "
          + "modelled as a multiplication, and the number it gives is not the power",
        DROP_IMPACT.STRUCTURE,
        node,
      );
      return value;
    }
    const compound = COMPOUND_OPERATORS[operator];
    if (!compound) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this assignment operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const current = this.lowerExpression(node.left);
    const right = this.lowerExpression(node.right);
    const value = this.state.newTemp();
    this.emit({
      kind: KIND.BINOP,
      dest: value,
      lhs1: current,
      lhs2: right,
      binop: compound,
      ...this.position(node),
    });
    this.lowerWrite(node.left, value, node);
    return value;
  }

  /**
   * A write to a location.
   *
   * A variable is a local; a property and an offset are entries of a
   * namespace, so they are container stores with the key as the index.
   *
   * A write through a REFERENCE names the location it aliases: the TAC's
   * locations are one name each, and a reference makes two of them one.
   */
  lowerWrite(target, value, node, alias) {
    if (!target) return;
    if (target.kind === "byref") {
      const inner = target.what;
      if (inner && inner.kind === "variable" && typeof inner.name === "string") {
        const variable = varOp(inner.name, this.tacTypeOfVariable(inner.name));
        this.declareLocal(variable);
        this.emit({
          kind: KIND.MOVE,
          dest: variable,
          lhs1: value,
          aliasOf: inner.name,
          ...this.position(target),
        });
        this.emitDrop(
          "op.assign.byref",
          "a write through a reference makes two names one location, and the TAC's locations are "
            + "one name each",
          DROP_IMPACT.SEMANTICS,
          target,
        );
        return;
      }
      this.report(PROBLEM.UNHANDLED_NODE, target, "this by-reference target has no TAC lowering");
      return;
    }
    if (target.kind === "variable") {
      if (typeof target.name !== "string") {
        // A variable variable: the name is a run-time value, so the write
        // goes into the scope's variable table under that name.
        const name = this.lowerExpression(target.name);
        const table = this.scopeTable();
        this.emit({
          kind: KIND.ARRAYSTORE,
          lhs1: table,
          lhs2: name,
          dest: value,
          ...this.position(target),
        });
        this.emitDrop(
          "expr.varvar.alias",
          "the name of a variable variable is only known at run time, so the variable it names "
            + "cannot be the same location as the name the source uses",
          DROP_IMPACT.SEMANTICS,
          target,
        );
        return;
      }
      const variable = varOp(target.name, this.tacTypeOfVariable(target.name));
      this.declareLocal(variable);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: value,
        ...(alias ? { aliasOf: alias } : {}),
        ...this.position(target),
      });
      // A variable that holds a closure: a call of that variable has to
      // reach the closure's own function.
      const closure = value?.name ? this.closureByObject.get(value.name) : null;
      if (closure) {
        this.state.closureByVar.set(target.name, closure);
      }
      this.inferTypeOf(target.name, node?.right);
      return;
    }
    if (target.kind === "propertylookup" || target.kind === "nullsafepropertylookup") {
      const receiver = this.lowerExpression(target.what);
      const key = constOp(CONST.STRING, this.nameText(target.offset) ?? "");
      const ownerType = this.typeOfReceiver(target.what);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: receiver,
        lhs2: key,
        dest: value,
        ...(ownerType ? { ownerType } : {}),
        ...this.position(target),
      });
      return;
    }
    if (target.kind === "offsetlookup") {
      const receiver = this.lowerExpression(target.what);
      const key = this.lowerKey(target.offset);
      const ownerType = this.typeOfReceiver(target.what);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: receiver,
        lhs2: key,
        dest: value,
        ...(ownerType ? { ownerType } : {}),
        ...this.position(target),
      });
      return;
    }
    if (target.kind === "list") {
      this.lowerListAssignment(target, value, node);
      return;
    }
    if (target.kind === "staticlookup") {
      // A static property: the class is the receiver and the member is the
      // key, so that a later read of the same member finds the value.
      const owner = this.resolveStaticOwner(target);
      const receiver = this.classObjectOf(owner);
      const key = constOp(CONST.STRING, this.nameText(target.offset) ?? "");
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: receiver,
        lhs2: key,
        dest: value,
        ownerType: owner,
        ...this.position(target),
      });
      return;
    }
    this.report(PROBLEM.UNHANDLED_NODE, target, "this assignment target has no TAC lowering");
  }

  /**
   * A destructuring assignment.
   *
   * The value holds the elements, and each target reads its own position out
   * of it. A target with a key reads that key instead of a position.
   */
  lowerListAssignment(target, value, node) {
    const container = this.state.newTemp();
    this.emit({ kind: KIND.MOVE, dest: container, lhs1: value, ...this.position(target) });
    let index = 0;
    for (const item of target.items ?? []) {
      const element = this.state.newTemp();
      const key = item && item.key ? this.lowerKey(item.key) : constOp(CONST.INT, String(index));
      this.emit({
        kind: KIND.ARRAYLOAD,
        dest: element,
        lhs1: container,
        lhs2: key,
        ...this.position(target),
      });
      this.lowerWrite(item.value, element, node);
      index += 1;
    }
  }

  /**
   * The key of an offset.
   *
   * A constant key names one entry; a key the analysis cannot resolve stands
   * for every key.
   */
  lowerKey(node) {
    if (!node) return this.emitHavoc(node);
    if (node.kind === "number") {
      return this.numberConstant(node.value);
    }
    if (node.kind === "string") {
      return constOp(CONST.STRING, node.value);
    }
    return this.lowerExpression(node);
  }

  lowerBinary(node) {
    const operator = node.type;
    if (operator === "instanceof") {
      return this.lowerInstanceof(node);
    }
    if (operator === "??") {
      return this.lowerCoalesce(node);
    }
    if (operator === "<=>") {
      return this.lowerSpaceship(node);
    }
    if (operator === "**") {
      return this.lowerPower(node);
    }
    if (SHORT_CIRCUIT_OPERATORS.has(operator)) {
      return this.lowerShortCircuit(node);
    }
    const approximation = APPROXIMATED_OPERATORS[operator];
    let binop = BINARY_OPERATORS[operator];
    if (approximation) {
      binop = approximation.binop;
      this.report(PROBLEM.SOURCE_LOWERED, node, approximation.note);
    }
    const left = this.lowerExpression(node.left);
    const right = this.lowerExpression(node.right);
    if (!binop) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const dest = this.state.newTemp();
    this.emit({
      kind: KIND.BINOP,
      dest,
      lhs1: left,
      lhs2: right,
      binop,
      ...this.position(node),
    });
    return dest;
  }

  /**
   * The null-coalescing operator: `$a ?? $b`.
   *
   * The right operand is only evaluated when the left one is null, so it
   * cannot be a single BINOP: it has to be on the branch that reaches it.
   */
  lowerCoalesce(node) {
    const left = this.lowerExpression(node.left);
    const dest = this.state.newTemp();
    const rhsLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emit({ kind: KIND.MOVE, dest, lhs1: left, ...this.position(node) });
    // The test is `a IS NOT NULL`, not `a is truthy`: `0 ?? $b` is `0` in PHP,
    // and a truthiness test would take `$b`. So the branch's operand is the
    // comparison itself, and the guard says the same thing about the edge the
    // branch takes.
    const notNull = this.state.newTemp(TYPE.BOOLEAN);
    this.emit({
      kind: KIND.BINOP,
      dest: notNull,
      lhs1: left,
      lhs2: constOp(CONST.NULL),
      binop: "NE",
      ...this.position(node),
    });
    this.emitBranch(
      notNull,
      endLabel,
      rhsLabel,
      { kind: GUARD.COMPARE, lhs: left, op: "NE", rhs: constOp(CONST.NULL) },
      node,
    );
    this.emitLabel(rhsLabel, LABEL_KIND.BLOCK, node);
    const right = this.lowerExpression(node.right);
    this.emit({ kind: KIND.MOVE, dest, lhs1: right, ...this.position(node) });
    this.emitJump(endLabel, node);
    this.emitLabel(endLabel, LABEL_KIND.BLOCK, node);
    return dest;
  }

  /**
   * The three-way comparison: `$a <=> $b`.
   *
   * It is -1, 0 or 1, and it is two comparisons and a choice between them.
   */
  lowerSpaceship(node) {
    const left = this.lowerExpression(node.left);
    const right = this.lowerExpression(node.right);
    const dest = this.state.newTemp(TYPE.INT);
    const less = this.state.newTemp(TYPE.BOOLEAN);
    const greater = this.state.newTemp(TYPE.BOOLEAN);
    this.emit({
      kind: KIND.BINOP,
      dest: less,
      lhs1: left,
      lhs2: right,
      binop: "LT",
      ...this.position(node),
    });
    this.emit({
      kind: KIND.BINOP,
      dest: greater,
      lhs1: left,
      lhs2: right,
      binop: "GT",
      ...this.position(node),
    });
    const lessLabel = this.state.newLabel("L");
    const greaterLabel = this.state.newLabel("L");
    const checkGreaterLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emit({ kind: KIND.MOVE, dest, lhs1: constOp(CONST.INT, "0"), ...this.position(node) });
    this.emitBranch(
      less,
      lessLabel,
      checkGreaterLabel,
      { kind: GUARD.COMPARE, lhs: left, op: "LT", rhs: right },
      node,
    );
    this.emitLabel(checkGreaterLabel, LABEL_KIND.BLOCK, node);
    this.emitBranch(
      greater,
      greaterLabel,
      endLabel,
      { kind: GUARD.COMPARE, lhs: left, op: "GT", rhs: right },
      node,
    );
    this.emitLabel(lessLabel, LABEL_KIND.BLOCK, node);
    this.emit({ kind: KIND.MOVE, dest, lhs1: constOp(CONST.INT, "-1"), ...this.position(node) });
    this.emitJump(endLabel, node);
    this.emitLabel(greaterLabel, LABEL_KIND.BLOCK, node);
    this.emit({ kind: KIND.MOVE, dest, lhs1: constOp(CONST.INT, "1"), ...this.position(node) });
    this.emitJump(endLabel, node);
    this.emitLabel(endLabel, LABEL_KIND.BLOCK, node);
    return dest;
  }

  /**
   * A power: `$a ** $b`.
   *
   * The TAC has no power operator, so the power is EXPANDED: a constant
   * integer exponent becomes the repeated multiplication it stands for, which
   * is the same number. An exponent that is only known at run time cannot be
   * expanded, so it is modelled as a single multiplication -- which is NOT
   * the same number -- and the difference is recorded.
   */
  lowerPower(node) {
    const left = this.lowerExpression(node.left);
    const exponent = this.powerExponentOf(node.right);
    if (exponent !== null) {
      return this.repeatMultiplication(left, exponent, node);
    }
    const right = this.lowerExpression(node.right);
    const dest = this.state.newTemp();
    this.emit({
      kind: KIND.BINOP,
      dest,
      lhs1: left,
      lhs2: right,
      binop: "MUL",
      ...this.position(node),
    });
    this.emitDrop(
      "op.bin.power",
      "the TAC has no power operator, so a power whose exponent is only known at run time is "
        + "modelled as a multiplication, and the number it gives is not the power",
      DROP_IMPACT.STRUCTURE,
      node,
    );
    return dest;
  }

  /**
   * The integer a power's exponent says, when it says one.
   *
   * @param {object} node the exponent node
   * @return {number|null} the exponent, or null when it is not a constant
   */
  powerExponentOf(node) {
    if (!node || node.kind !== "number") return null;
    const text = String(node.value ?? "");
    if (!/^\d+$/.test(text)) return null;
    const value = Number(text);
    if (!Number.isSafeInteger(value) || value > 16) return null;
    return value;
  }

  /**
   * A value multiplied by itself a number of times.
   *
   * @param {object} base the value
   * @param {number} times how many factors
   * @param {object} node the node the multiplication is for
   * @return {object} the result
   */
  repeatMultiplication(base, times, node) {
    if (times <= 0) return constOp(CONST.INT, "1");
    let value = base;
    for (let i = 1; i < times; i++) {
      const dest = this.state.newTemp();
      this.emit({
        kind: KIND.BINOP,
        dest,
        lhs1: value,
        lhs2: base,
        binop: "MUL",
        ...this.position(node),
      });
      value = dest;
    }
    return value;
  }

  /**
   * A short-circuit operator: `$a && $b` or `$a || $b`.
   *
   * The right operand is only evaluated when the left one says it has to
   * be, so it cannot be a single BINOP: it has to be on the branch that
   * reaches it. That is also what makes the taint of the right operand
   * reach the result only when it is evaluated.
   */
  lowerShortCircuit(node) {
    const isAnd = node.type === "&&" || node.type === "and";
    const dest = this.state.newTemp();
    const rhsLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    const left = this.lowerExpression(node.left);
    this.emit({ kind: KIND.MOVE, dest, lhs1: left, ...this.position(node) });
    this.emitBranch(
      left,
      isAnd ? rhsLabel : endLabel,
      isAnd ? endLabel : rhsLabel,
      { kind: GUARD.TRUTHINESS, operand: left, sense: "TRUE" },
      node,
    );
    this.emitLabel(rhsLabel, LABEL_KIND.BLOCK, node);
    const right = this.lowerExpression(node.right);
    this.emit({ kind: KIND.MOVE, dest, lhs1: right, ...this.position(node) });
    this.emitJump(endLabel, node);
    this.emitLabel(endLabel, LABEL_KIND.BLOCK, node);
    return dest;
  }

  /**
   * An `instanceof` test.
   *
   * The TAC has an instruction for it, and it carries the type that was
   * tested for.
   */
  lowerInstanceof(node) {
    const operand = this.lowerExpression(node.left);
    const typeName = this.qualifiedClassNameOf(node.right) ?? "object";
    const dest = this.state.newTemp(TYPE.BOOLEAN);
    this.emit({
      kind: KIND.INSTANCEOF,
      dest,
      lhs1: operand,
      ctorType: { kind: TYPE.CLASS, name: typeName },
      ...this.position(node),
    });
    return dest;
  }

  lowerUnary(node) {
    const operator = node.type;
    const operand = this.lowerExpression(node.what);
    if (operator === "+") {
      // A unary plus is a no-op: the operand is the result.
      return operand;
    }
    const unop = UNARY_OPERATORS[operator];
    if (!unop) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const dest = this.state.newTemp();
    this.emit({ kind: KIND.UNARYOP, dest, lhs1: operand, unop, ...this.position(node) });
    return dest;
  }

  /**
   * A property read.
   *
   * The key is a NAME in a namespace, so this is a container access with a
   * string key and not a field access. `$o->name` and `$o["name"]` are the
   * same location, and the engine's keyed namespace is what keeps them
   * together.
   *
   * When the receiver's type is known, the read says whose namespace it is:
   * the owner type is an ANNOTATION on the access, not a different kind of
   * access.
   */
  lowerPropertyRead(node) {
    const receiver = this.lowerExpression(node.what);
    const key = constOp(CONST.STRING, this.nameText(node.offset) ?? "");
    const ownerType = this.typeOfReceiver(node.what);
    const dest = this.state.newTemp();
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest,
      lhs1: receiver,
      lhs2: key,
      ...(ownerType ? { ownerType } : {}),
      ...this.position(node),
    });
    return dest;
  }

  lowerOffsetRead(node) {
    const receiver = this.lowerExpression(node.what);
    const key = this.lowerKey(node.offset);
    const dest = this.state.newTemp();
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest,
      lhs1: receiver,
      lhs2: key,
      ...this.position(node),
    });
    return dest;
  }

  /**
   * A static read: a class constant or a static property.
   *
   * It is a read of the CLASS's namespace, so it is a container access of
   * the class object -- and it DOES emit an instruction: a read that emits
   * nothing is not a read, and a rule that names a class constant could
   * never match one.
   */
  lowerStaticRead(node) {
    const owner = this.resolveStaticOwner(node);
    const member = this.nameText(node.offset) ?? "";
    const receiver = this.classObjectOf(owner);
    const dest = this.state.newTemp();
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest,
      lhs1: receiver,
      lhs2: constOp(CONST.STRING, member),
      ownerType: owner,
      ...this.position(node),
    });
    return dest;
  }

  /**
   * The class a static access names.
   *
   * `self` is the class the code is in, `static` is the class the code is
   * in TOO -- but the class that actually runs the code is only known at
   * run time -- and `parent` is the class it extends, which the
   * translator knows from the class's own declaration.
   */
  resolveStaticOwner(node) {
    const what = node.what;
    if (!what) return this.enclosingClassName();
    if (what.kind === "name") {
      return this.qualifiedClassNameOf(what);
    }
    if (what.kind === "selfreference") {
      return this.enclosingClassName();
    }
    if (what.kind === "staticreference") {
      this.emitDrop(
        "lateStaticBinding",
        "`static` names the class the code is written in, but the class that runs it is only "
          + "known at run time",
        DROP_IMPACT.SEMANTICS,
        node,
      );
      return this.enclosingClassName();
    }
    if (what.kind === "parentreference") {
      const superType = this.state?.superType;
      if (superType) return superType;
      this.emitDrop(
        "type.parent.unknown",
        "the parent class is not known here, so a member of it is modelled as a member of the "
          + "class the code is in",
        DROP_IMPACT.TYPE,
        node,
      );
      return this.enclosingClassName();
    }
    if (what.kind === "variable") {
      // A static member of an OBJECT: the class is the object's class, which
      // the translator may know.
      const receiverType = this.typeOfReceiver(what);
      if (receiverType) {
        return receiverType;
      }
      this.emitDrop(
        "type.receiver.unknown",
        "the receiver's type could not be resolved, so the member is modelled as a member of the "
          + "class the code is in",
        DROP_IMPACT.TYPE,
        node,
      );
      return this.enclosingClassName();
    }
    if (what.kind === "identifier") {
      return this.qualifyClass(what.name);
    }
    return this.enclosingClassName();
  }

  /** The class the code being lowered belongs to. */
  enclosingClassName() {
    return this.state?.enclosingClass ?? this.namespacePrefix();
  }

  /**
   * The class a construction names.
   *
   * `self` and `static` are the class the code is in, `parent` is the class
   * it extends -- which the translator does not know -- and a class named by
   * a variable is not a class the translator can name at all.
   *
   * @param {object} node the `what` of a construction
   * @return {string|null} the qualified class name, or null when it is unknown
   */
  classNameOf(node) {
    if (!node) return null;
    if (node.kind === "selfreference" || node.kind === "staticreference") {
      return this.enclosingClassName();
    }
    if (node.kind === "parentreference") {
      const superType = this.state?.superType;
      if (superType) return superType;
      this.emitDrop(
        "type.parent.unknown",
        "the parent class is not known here, so a construction of it is modelled as a construction "
          + "of the class the code is in",
        DROP_IMPACT.TYPE,
        node,
      );
      return this.enclosingClassName();
    }
    if (node.kind === "variable") {
      this.emitDrop(
        "type.constructor.unknown",
        "the class of a construction is named by a variable, so the constructed type is unknown",
        DROP_IMPACT.SEMANTICS,
        node,
      );
      return null;
    }
    return this.qualifiedClassNameOf(node);
  }

  /**
   * A construction: `new Thing($a)`.
   *
   * A NEW carries the constructor's arguments and no receiver slot: the
   * object does not exist yet.
   *
   * The callee key names the CONSTRUCTOR -- `<fqcn>.<init>/n` -- and that
   * is the name the constructor itself is lowered under, so a construction
   * finds the constructor it calls and the constructor's body is analysed.
   */
  lowerNew(node) {
    const args = [];
    const namedArgs = [];
    for (const argument of this.argumentsOf(node)) {
      const value = this.lowerExpression(argument.node);
      if (argument.name) {
        namedArgs.push({ name: argument.name, value });
      } else {
        args.push(value);
      }
    }
    const className = this.classNameOf(node.what);
    const typeName = className ?? "object";
    const dest = this.state.newTemp({ kind: TYPE.CLASS, name: typeName });
    const declared = className ? this.declaredArities.get(`${className}.<init>`) : undefined;
    this.emit({
      kind: KIND.NEW,
      calleeSig: className ? `${className}.<init>/${declared ?? args.length}` : undefined,
      dest,
      ctorType: { kind: TYPE.CLASS, name: typeName },
      args,
      argCount: args.length + namedArgs.length,
      ...(namedArgs.length ? { namedArgs } : {}),
      ...this.position(node),
    });
    return dest;
  }

  /**
   * A call.
   *
   * The receiver slot is reserved whether or not the call has a receiver
   * object: `args[0]` is the receiver, `args[1..]` are the arguments. A free
   * function has no receiver object, so the slot holds the NAMESPACE's
   * name as a constant. A METHOD call has a receiver object, and it is the
   * receiver -- not the namespace -- that the slot holds.
   *
   * A NAMED argument binds by name, so it is carried beside the positional
   * ones instead of being pushed into their place: the callee's parameter
   * names decide where it goes, and only the callee knows them.
   */
  lowerCall(node) {
    if (node.what && node.what.kind === "variadicplaceholder") {
      // `foo(...)` is the CALLABLE, not a call of it. So no CALL is
      // emitted: the value is the callable's reference.
      this.emitDrop(
        "expr.fcc.notCall",
        "a first-class callable syntax is the callable itself and not a call of it",
        DROP_IMPACT.STRUCTURE,
        node,
      );
      return this.emitHavoc(node);
    }
    const callee = this.resolveCallee(node);
    const args = [];
    const namedArgs = [];
    let spread = null;
    for (const argument of this.argumentsOf(node)) {
      const value = this.lowerExpression(argument.node);
      if (argument.name) {
        namedArgs.push({ name: argument.name, value });
      } else if (argument.spread) {
        spread = value;
      } else {
        args.push(value);
      }
    }
    if (namedArgs.length > 0 && !this.declaredParameterNames.has(callee.qualified)) {
      this.emitDrop(
        "op.call.namedArg",
        "a named argument binds by name, and the callee's parameter names are not known here",
        DROP_IMPACT.STRUCTURE,
        node,
      );
    }
    // The captured values of a closure are its leading parameters, so they
    // are handed over before the arguments the source wrote.
    const leading = (callee.leading ?? []).map((name) =>
      varOp(name, this.tacTypeOfVariable(name)),
    );
    const wireArgs = [callee.receiver, ...leading, ...args];
    const dest = this.state.newTemp();
    const kind = callee.declared ? KIND.CALL : KIND.UNINTERPRETED;
    this.emit({
      kind,
      dest,
      calleeSig: `${callee.qualified}/${callee.arity}`,
      args: wireArgs,
      argCount: args.length + namedArgs.length,
      ...(namedArgs.length ? { namedArgs } : {}),
      ...(spread ? { spreadArgs: spread } : {}),
      ...this.position(node),
    });
    return dest;
  }

  /**
   * The arguments of a call.
   *
   * A named argument is a `namedargument` node with a name and a value; an
   * ordinary one is the expression itself; and an unpacked one is a
   * `variadic` node whose length is only known at run time.
   *
   * @param {object} node the call node
   * @return {object[]} the arguments: their name (or null), their node, and
   *   whether they are unpacked
   */
  argumentsOf(node) {
    const out = [];
    for (const argument of node.arguments ?? []) {
      if (argument && argument.kind === "namedargument") {
        out.push({ name: this.nameText(argument.name), node: argument.value, spread: false });
      } else if (argument && argument.kind === "variadic") {
        out.push({ name: null, node: argument, spread: true });
      } else {
        out.push({ name: null, node: argument, spread: false });
      }
    }
    return out;
  }

  /**
   * The callee of a call: its qualified name, its receiver and its arity.
   *
   * PHP qualifies a name with a namespace and a class with `::`, and the
   * callee signature carries that qualified name as PHP writes it.
   */
  resolveCallee(node) {
    const what = node.what;
    if (!what) {
      return this.unresolvedCallee("<call>", node);
    }
    if (what.kind === "name" || what.kind === "identifier") {
      // A function. PHP's own rules place the name: the current namespace
      // first, the global one as the fallback.
      const qualified = this.qualifiedFunctionNameOf(what);
      return {
        qualified,
        receiver: constOp(CONST.STRING, this.namespaceOf(qualified)),
        arity: this.arityOf(qualified, node),
        declared: this.declaredArities.has(qualified),
        reason: null,
      };
    }
    if (what.kind === "staticlookup") {
      const owner = this.resolveStaticOwner(what);
      const member = this.nameText(what.offset) ?? "";
      const qualified = `${owner}::${member}`;
      return {
        qualified,
        receiver: constOp(CONST.STRING, owner),
        arity: this.arityOf(qualified, node),
        declared: this.declaredArities.has(qualified),
        reason: null,
      };
    }
    if (what.kind === "variable") {
      // A variable that holds a CLOSURE: the call is a call of the
      // closure's own function, and the closure object is the receiver.
      const closure = this.state?.closureByVar.get(what.name);
      if (closure) {
        return {
          qualified: closure.qualified,
          receiver: varOp(what.name),
          arity: closure.arity,
          declared: true,
          reason: null,
          // The captured values are the closure's leading parameters, so
          // the call hands them over first.
          leading: closure.captures,
        };
      }
    }
    if (what.kind === "propertylookup" || what.kind === "nullsafepropertylookup") {
      const receiverType = this.typeOfReceiver(what.what);
      const member = this.nameText(what.offset) ?? "";
      const receiver = this.lowerExpression(what.what);
      if (receiverType) {
        const qualified = `${receiverType}::${member}`;
        return {
          qualified,
          receiver,
          arity: this.arityOf(qualified, node),
          declared: this.declaredArities.has(qualified),
          reason: null,
        };
      }
      const owner = this.enclosingClassName();
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "the receiver's type could not be resolved, so the callee's owner is the class the code is "
          + "in",
      );
      const qualified = `${owner}::${member}`;
      return {
        qualified,
        receiver,
        arity: this.arityOf(qualified, node),
        declared: this.declaredArities.has(qualified),
        reason: "the receiver's type could not be resolved",
      };
    }
    return this.unresolvedCallee("<call>", node);
  }

  unresolvedCallee(name, node) {
    this.report(
      PROBLEM.UNHANDLED_NODE,
      node,
      "the callee is not a name, so the call is modelled as a black box",
    );
    return {
      qualified: name,
      receiver: constOp(CONST.STRING, this.namespacePrefix()),
      arity: this.argumentsOf(node).length,
      declared: false,
      reason: "the callee is not a name",
    };
  }

  /**
   * The arity of a call.
   *
   * The arity counts the DECLARED parameters of a function this file
   * declares, because that is the signature the sink catalogue matches on.
   * A function from elsewhere has no declaration the analysis can see, so
   * the arity is the number of arguments passed.
   */
  arityOf(qualified, node) {
    const declared = this.declaredArities.get(qualified);
    if (declared !== undefined) {
      return declared;
    }
    return this.argumentsOf(node).length;
  }

  /**
   * A function name qualified by the current namespace.
   *
   * PHP resolves an unqualified function call to the CURRENT namespace
   * first, and to the global namespace only when the current one does not
   * declare it. So a call of a name the file declares is qualified by the
   * namespace, and a call of any other name falls back to the global one --
   * which is where `system` and `exec` live.
   *
   * @param {string} name the name
   * @param {string} namespace the namespace to qualify it in, or null for the current one
   * @return {string} the qualified name
   */
  qualifyFunction(name, namespace) {
    const scope = namespace ?? this.namespace;
    if (!name) return this.namespacePrefix();
    if (name.startsWith("\\")) return this.canonicalName(name);
    const alias = this.functionAliases.get(scope)?.get(name);
    if (alias) return this.canonicalName(alias);
    const declared = this.namespaceFunctions.get(scope);
    if (declared && declared.has(name)) {
      return this.canonicalName(scope ? `${scope}\\${name}` : name);
    }
    return this.canonicalName(name);
  }

  /**
   * A class or function name in the ONE spelling PHP knows it by.
   *
   * <p>The sources spell the same name several ways: `system($cmd)` and
   * `\system($cmd)` and `use function system;` all name the global function
   * `system`, and the catalogue's entry for it is written `^\\(system|exec|...)`.
   * A name that arrives spelled `system` does not match that entry, so a call
   * that the catalogue would otherwise report is missed -- silently, with the
   * scan still reporting success.
   *
   * <p>PHP's own spelling is the rule: a name in the GLOBAL namespace carries
   * the leading backslash (`\system`, `\SimpleXMLElement`) and a name in a
   * namespace carries none (`App\Support\Logger`). One name, one spelling, so
   * that an entry names a class or a function and every call to it matches.
   *
   * @param {string} name the qualified name, in any of the spellings PHP allows
   * @return {string} the name in the canonical spelling
   */
  canonicalName(name) {
    const trimmed = name.replace(/^\\+/, "");
    if (!trimmed) return name;
    return trimmed.includes("\\") ? trimmed : `\\${trimmed}`;
  }

  /**
   * A class name qualified by the current namespace.
   *
   * A class has NO global fallback: PHP looks for the name in the current
   * namespace and nowhere else.
   *
   * @param {string} name the name
   * @param {string} namespace the namespace to qualify it in, or null for the current one
   * @return {string} the qualified name
   */
  qualifyClass(name, namespace) {
    const scope = namespace ?? this.namespace;
    if (!name) return this.namespacePrefix();
    if (name.startsWith("\\")) return this.canonicalName(name);
    const alias = this.classAliases.get(scope)?.get(name);
    if (alias) return this.canonicalName(alias);
    return this.canonicalName(scope ? `${scope}\\${name}` : name);
  }

  /** The current namespace, spelled the way PHP spells it. */
  namespacePrefix() {
    return this.namespace ? this.namespace : "\\";
  }

  /**
   * The qualified name of a `name` node that names a FUNCTION.
   *
   * The parser records HOW the name was written -- fully qualified,
   * unqualified, or relative to the namespace -- and the rules for each are
   * PHP's own.
   */
  qualifiedFunctionNameOf(node) {
    return this.qualifiedNameOf(node, (name) => this.qualifyFunction(name));
  }

  /** The qualified name of a `name` node that names a CLASS. */
  qualifiedClassNameOf(node) {
    return this.qualifiedNameOf(node, (name) => this.qualifyClass(name));
  }

  qualifiedNameOf(node, qualify) {
    if (!node) return this.namespacePrefix();
    if (node.kind === "identifier") {
      return qualify(node.name);
    }
    if (node.kind === "selfreference" || node.kind === "staticreference") {
      // `self` and `static` name the class the code is written in.
      return this.enclosingClassName();
    }
    if (node.kind === "parentreference") {
      // `parent` names the class the code's class extends.
      return this.state?.superType ?? this.enclosingClassName();
    }
    const name = node.name ?? "";
    if (node.resolution === "fqn") {
      // A name written out in full. It names the class or function it says it
      // names, and it is spelled the same way however it was written -- so it
      // goes through the same canonical spelling as every other name.
      return this.canonicalName(name);
    }
    if (node.resolution === "rn") {
      const relative = name.replace(/^namespace\\/, "");
      return this.canonicalName(
        this.namespace ? `${this.namespace}\\${relative}` : `\\${relative}`,
      );
    }
    return qualify(name);
  }

  /** The namespace part of a qualified name. */
  namespaceOf(qualified) {
    const index = qualified.lastIndexOf("\\");
    if (index <= 0) {
      return "\\";
    }
    return qualified.slice(0, index);
  }

  /**
   * Record the type a variable was given, when the source says so.
   *
   * PHP has no static types, so this is the narrow set of cases where the
   * source names a class: a construction of a class, and an annotation.
   *
   * The construction has already been lowered by the time this runs, so the
   * class's name is read here WITHOUT reporting: a report that the
   * construction already made would be counted twice.
   */
  inferTypeOf(name, value) {
    if (!name || !value) return;
    this.state.types.delete(name);
    if (value.kind !== "new") return;
    const what = value.what;
    if (!what) return;
    let type = null;
    if (what.kind === "selfreference" || what.kind === "staticreference") {
      type = this.enclosingClassName();
    } else if (what.kind !== "variable" && what.kind !== "parentreference") {
      type = this.qualifiedClassNameOf(what);
    }
    if (type) {
      this.state.types.set(name, type);
    }
  }

  /** The type of a receiver, when the function inferred one. */
  typeOfReceiver(node) {
    if (!node || node.kind !== "variable") return null;
    return this.state.types.get(node.name) ?? null;
  }

  // ---- helpers --------------------------------------------------------------------------

  emit(instruction) {
    this.state.instructions.push(instruction);
    return instruction;
  }

  declareLocal(variable) {
    if (!variable || !variable.name) return;
    if (!this.state.locals.some((local) => local.name === variable.name)) {
      this.state.locals.push(variable);
    }
  }

  /**
   * The text of a name.
   *
   * The parser spells a declaration's name as an `identifier` node and a
   * use statement's alias the same way, while a variable's name is a plain
   * string. One helper reads all three.
   *
   * @param {object|string} node the name
   * @return {string|null} the text, or null when there is none
   */
  nameText(node) {
    if (node === null || node === undefined) return null;
    if (typeof node === "string") return node;
    if (typeof node.name === "string") return node.name;
    if (node.name && typeof node.name.name === "string") return node.name.name;
    return null;
  }

  /**
   * Lower one statement or function, reporting anything that goes wrong.
   *
   * The lowering of ONE function must not take the whole file down, and it
   * must not be silent either. A construct the translator does not understand
   * is a problem on that function -- the function's precision is downgraded
   * and the reader is told what went wrong.
   */
  guard(node, body) {
    try {
      body();
    } catch (e) {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        `this could not be lowered completely: ${e && e.message ? e.message : String(e)}`,
        DROP_IMPACT.SEMANTICS,
      );
    }
  }

  /**
   * A value the translator could not lower.
   *
   * HAVOC says "this value is unknown". It is neither tainted nor clean, and
   * the checkers read it as unknown rather than as safe.
   */
  emitHavoc(node, type) {
    const dest = this.state.newTemp(type ?? { kind: TYPE.UNKNOWN });
    this.emit({ kind: KIND.HAVOC, dest, ...this.position(node) });
    return dest;
  }

  /**
   * Record the attributes a declaration carries.
   *
   * PHP's `#[Attr]` is metadata ABOUT a declaration, and the IR has four
   * channels for it. The attribute's NAME goes into the channel, and its
   * arguments go into `annotationValues`, so that a rule can match on the
   * value and not only on the name.
   *
   * @param {object} state the function being lowered
   * @param {object} node the declaration
   * @param {string} channel the channel: class, method, field or param
   */
  annotate(state, node, channel) {
    for (const group of node.attrGroups ?? []) {
      for (const attribute of group.attrs ?? []) {
        const name = attribute.name;
        if (!name) continue;
        const list = state[`${channel}Annotations`];
        if (list) list.push(name);
        const values = [];
        for (const argument of attribute.args ?? []) {
          const value = argument && argument.value !== undefined ? argument.value : argument;
          if (value === undefined || value === null) continue;
          values.push(attributeValueText(value));
        }
        const existing = state.annotationValues[name] ?? [];
        state.annotationValues[name] = [...existing, ...values];
      }
    }
  }

  /**
   * Record the attributes ONE parameter carries.
   *
   * A parameter's attributes belong to the function that declares it, not to
   * the class the function is in, so they go into the parameter channel.
   */
  annotateParameter(parameter) {
    for (const group of parameter.attrGroups ?? []) {
      for (const attribute of group.attrs ?? []) {
        const name = attribute.name;
        if (!name) continue;
        this.state.paramAnnotations.push(name);
        const values = [];
        for (const argument of attribute.args ?? []) {
          const value = argument && argument.value !== undefined ? argument.value : argument;
          if (value === undefined || value === null) continue;
          values.push(attributeValueText(value));
        }
        const existing = this.state.annotationValues[name] ?? [];
        this.state.annotationValues[name] = [...existing, ...values];
      }
    }
  }

  /**
   * The class object of a class: the synthetic variable that holds it.
   *
   * A static property and a class constant belong to the CLASS, so their
   * receiver is the class object. The variable's name is the class's
   * qualified name with the separators flattened, so that two classes with
   * the same short name in different namespaces do not share one location.
   *
   * @param {string} qualified the class's qualified name
   * @return {object} the variable operand
   */
  classObjectOf(qualified) {
    const name = qualified ? `t_cls_${qualified.replace(/\\/g, "_")}` : "t_cls_unknown";
    const variable = varOp(name, { kind: TYPE.CLASS, name: qualified ?? "object" });
    this.declareLocal(variable);
    return variable;
  }

  /**
   * The class a declaration extends.
   *
   * @param {object} node the declaration
   * @return {string|null} the qualified name, or null when it extends nothing
   */
  superTypeOf(node) {
    if (!node || !node.extends) return null;
    return this.qualifiedClassNameOf(node.extends) ?? null;
  }

  /**
   * Record a type declaration.
   *
   * The declaration is the file's TYPE surface: what the classes are, what
   * they extend, what they declare. It is not the instruction stream, and
   * the two are separate.
   *
   * @param {object} node the declaration
   * @param {object} fields what is known about it
   */
  recordTypeDeclaration(node, fields) {
    const modifiers = [];
    if (node.isAbstract === true) modifiers.push("abstract");
    if (node.isFinal === true) modifiers.push("final");
    if (node.isReadonly === true) modifiers.push("readonly");
    const annotations = [];
    for (const group of node.attrGroups ?? []) {
      for (const attribute of group.attrs ?? []) {
        if (attribute.name) annotations.push(attribute.name);
      }
    }
    this.typeDeclarations.push({
      fqn: fields.fqn,
      simpleName: fields.simpleName,
      superType: fields.superType ?? null,
      interfaces: fields.interfaces ?? [],
      annotations,
      fields: fields.fields ?? [],
      fieldAnnotations: fields.fieldAnnotations ?? [],
      members: fields.members ?? [],
      traits: fields.traits ?? [],
      modifiers,
      metaclass: null,
      isInterface: fields.isInterface === true,
      isAbstract: node.isAbstract === true,
      isEnum: fields.isEnum === true,
      isRecord: false,
      isSynthetic: false,
    });
  }

  /**
   * A label reference: what a label IS, and how deep it is.
   *
   * The engine tells a loop's end from a loop's body end by their kind, and
   * a jump is checked against its target by their scope.
   *
   * @param {string} name the label's name
   * @param {string} kind the label's kind
   * @param {number} scopeId the scope the label is in
   * @return {object} the label reference
   */
  labelRef(name, kind, scopeId) {
    return { name, kind, scopeId };
  }

  /**
   * The label reference of a label this function knows.
   *
   * A label that has not been seen yet -- a `goto` to a label further down
   * -- is a block until it is seen; `finishFunction` fills it in.
   *
   * @param {string} name the label's name
   * @return {object} the label reference
   */
  labelRefOf(name) {
    const kind = this.state.labelKinds.get(name) ?? LABEL_KIND.BLOCK;
    const scopeId = this.state.labelScopes.get(name) ?? this.state.scopeCounter;
    return this.labelRef(name, kind, scopeId);
  }

  /**
   * Remember what a label IS, so that a jump to it can say so too.
   *
   * @param {string} name the label's name
   * @param {string} kind the label's kind
   * @param {number} scopeId the scope the label is in
   */
  rememberLabel(name, kind, scopeId) {
    this.state.labelKinds.set(name, kind);
    this.state.labelScopes.set(name, scopeId);
  }

  /**
   * Emit a label.
   *
   * @param {string} name the label's name
   * @param {string} kind the label's kind
   * @param {object} node the node it belongs to
   */
  emitLabel(name, kind, node) {
    const scopeId = this.state.scopeCounter;
    this.rememberLabel(name, kind, scopeId);
    this.emit({
      kind: KIND.LABEL,
      label: name,
      labelRef: this.labelRef(name, kind, scopeId),
      ...this.position(node),
    });
  }

  /**
   * Emit a jump.
   *
   * @param {string} name the label it jumps to
   * @param {object} node the node it belongs to
   */
  emitJump(name, node) {
    this.emit({
      kind: KIND.JUMP,
      label: name,
      labelRef: this.labelRefOf(name),
      ...this.position(node),
    });
  }

  /**
   * Emit a branch.
   *
   * The guard is the STRUCTURED condition: it says what the branch tests,
   * and the engine turns it into a path condition. A branch with no guard
   * tells the solver nothing, which is the same as not having lowered the
   * condition at all.
   *
   * @param {object} condition the boolean operand
   * @param {string} trueLabel where the true arm goes
   * @param {string} falseLabel where the false arm goes
   * @param {object} guard the structured condition
   * @param {object} node the node it belongs to
   */
  emitBranch(condition, trueLabel, falseLabel, guard, node) {
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel,
      falseLabel,
      guard,
      labelRef: this.labelRef(trueLabel, LABEL_KIND.BLOCK, this.state.scopeCounter),
      ...this.position(node),
    });
  }

  /**
   * Lower a condition, and say what it tests.
   *
   * A comparison is a Compare guard, an `instanceof` is a TypeTest guard, an
   * `isset` is a PropertyPresence guard, and everything else is a
   * Truthiness guard: the condition is true when the value is, and the
   * engine's language rules say what "is" means.
   *
   * The guard is never a guess: it says what the SOURCE says.
   *
   * @param {object} node the condition
   * @return {object} the operand and the guard
   */
  lowerCondition(node) {
    if (!node) {
      return {
        operand: constOp(CONST.BOOL, "true"),
        guard: { kind: GUARD.ALWAYS, value: true },
      };
    }
    if (node.kind === "boolean") {
      const value = node.value === true;
      return {
        operand: constOp(CONST.BOOL, value ? "true" : "false"),
        guard: { kind: GUARD.ALWAYS, value },
      };
    }
    if (node.kind === "bin" && node.type === "instanceof") {
      const operand = this.lowerExpression(node.left);
      const typeName = this.qualifiedClassNameOf(node.right) ?? "object";
      const dest = this.state.newTemp(TYPE.BOOLEAN);
      this.emit({
        kind: KIND.INSTANCEOF,
        dest,
        lhs1: operand,
        ctorType: { kind: TYPE.CLASS, name: typeName },
        ...this.position(node),
      });
      return {
        operand: dest,
        guard: {
          kind: GUARD.TYPE_TEST,
          operand,
          type: { kind: TYPE.CLASS, name: typeName },
          negated: false,
        },
      };
    }
    if (node.kind === "bin" && COMPARISON_OPERATORS.has(node.type)) {
      const left = this.lowerExpression(node.left);
      const right = this.lowerExpression(node.right);
      const dest = this.state.newTemp(TYPE.BOOLEAN);
      this.emit({
        kind: KIND.BINOP,
        dest,
        lhs1: left,
        lhs2: right,
        binop: BINARY_OPERATORS[node.type],
        ...this.position(node),
      });
      return {
        operand: dest,
        guard: { kind: GUARD.COMPARE, lhs: left, op: BINARY_OPERATORS[node.type], rhs: right },
      };
    }
    if (node.kind === "isset" || node.kind === "empty") {
      return this.lowerPresence(node);
    }
    const operand = this.lowerExpression(node);
    return { operand, guard: { kind: GUARD.TRUTHINESS, operand, sense: "TRUE" } };
  }

  /**
   * Lower a presence test: `isset(...)` and `empty(...)`.
   *
   * Whether a key is PRESENT is a fact the TAC can carry as a guard, but
   * not as a value: there is no instruction whose result is "the key is
   * there". So the VALUE is the value at the key, tested against null, and
   * the GUARD says the more precise thing: the key is there.
   *
   * The guard cannot be rendered into the solver's predicate language, so
   * the path condition it carries is lost -- and the loss is recorded.
   *
   * @param {object} node the isset or empty node
   * @return {object} the operand and the guard
   */
  lowerPresence(node) {
    const target = node.kind === "empty" ? node.expression : (node.variables ?? [])[0];
    const isEmpty = node.kind === "empty";
    if (isEmpty) {
      // `empty()` is not "is null": it is also true for `0`, `""`, `false` and
      // the empty array, and those are not values the TAC can enumerate. So
      // the test below is narrower than the source, and the difference is
      // recorded rather than passed off as the source's meaning.
      this.emitDrop(
        "expr.empty.falsy",
        "empty() is lowered as 'is null', so the other values PHP calls empty are not told "
          + "apart from the ones that are not",
        DROP_IMPACT.VALUE,
        node,
      );
    }
    let operand;
    let guard;
    if (target && (target.kind === "offsetlookup" || target.kind === "propertylookup"
      || target.kind === "nullsafepropertylookup")) {
      const receiver = this.lowerExpression(target.what);
      const key =
        target.kind === "offsetlookup"
          ? this.lowerKey(target.offset)
          : constOp(CONST.STRING, this.nameText(target.offset) ?? "");
      const loaded = this.state.newTemp();
      this.emit({
        kind: KIND.ARRAYLOAD,
        dest: loaded,
        lhs1: receiver,
        lhs2: key,
        ...this.position(target),
      });
      const nullConstant = constOp(CONST.NULL);
      const dest = this.state.newTemp(TYPE.BOOLEAN);
      this.emit({
        kind: KIND.BINOP,
        dest,
        lhs1: loaded,
        lhs2: nullConstant,
        binop: isEmpty ? "EQ" : "NE",
        ...this.position(node),
      });
      operand = dest;
      guard = { kind: GUARD.PROPERTY_PRESENCE, receiver, key };
      this.emitDrop(
        "guard.unrenderable",
        "whether a key is present cannot be rendered into a solver predicate, so the path "
          + "condition this branch carries is lost",
        DROP_IMPACT.STRUCTURE,
        node,
      );
    } else {
      const value = this.lowerExpression(target);
      const nullConstant = constOp(CONST.NULL);
      const dest = this.state.newTemp(TYPE.BOOLEAN);
      this.emit({
        kind: KIND.BINOP,
        dest,
        lhs1: value,
        lhs2: nullConstant,
        binop: isEmpty ? "EQ" : "NE",
        ...this.position(node),
      });
      operand = dest;
      guard = { kind: GUARD.COMPARE, lhs: value, op: isEmpty ? "EQ" : "NE", rhs: nullConstant };
    }
    return { operand, guard };
  }

  /**
   * Report a problem on the current function.
   *
   * The same problem in the same function is reported ONCE, with the number
   * of times it happened: a file with fifty calls to a name the analysis
   * cannot resolve has one problem, not fifty.
   *
   * A problem is also a DROP. The problem is what the reader is told; the
   * drop is what the engine derives the function's precision from, and what
   * says HOW MUCH was lost. A construct that was not understood at all costs
   * the function its semantics; one that was understood but whose shape is
   * not expressible costs it only its shape.
   *
   * @param {string} kind the problem kind
   * @param {object} node the node the problem is about
   * @param {string} message what the reader is told
   * @param {string} [impact] the drop's impact, when it is not the kind's own
   */
  report(kind, node, message, impact) {
    const key = `${kind}|${message}`;
    const entry = this.state.problemCounts.get(key);
    if (entry) {
      entry.count += 1;
    } else {
      const problem = {
        kind,
        message,
        nodeClass: node?.kind ?? null,
        ...this.position(node),
      };
      this.state.problemCounts.set(key, { problem, count: 1 });
      this.state.problems.push(problem);
    }
    this.emitDrop(
      constructOf(kind, node),
      message,
      impact ?? impactOfProblem(kind),
      node,
    );
  }

  /**
   * Record a drop: something this translator could not lower, and what it
   * cost.
   *
   * This is the ONE exit for a degradation. Every place that gives up on a
   * construct comes through here, so that "what did this translator not
   * understand" has exactly one answer and the engine's precision is
   * derived from the same record the reader sees.
   *
   * The drops are deduped by WHERE they happened, not only by what they
   * say: two different calls of an unresolvable name are two different
   * places the analysis is blind at, and the reader is told about both.
   *
   * @param {string} construct what was not lowered
   * @param {string} detail why, and what was done instead
   * @param {string} impact how much was lost
   * @param {object} [node] the node it happened at
   */
  emitDrop(construct, detail, impact, node) {
    const position = this.position(node);
    const nodeClass = node?.kind ?? null;
    const key = `${construct}|${detail}|${impact}|${nodeClass}|${position.line}|${position.column}`;
    const entry = this.state.dropCounts.get(key);
    if (entry) {
      entry.count += 1;
      return;
    }
    this.state.dropCounts.set(key, { count: 1 });
    this.state.drops.push({
      construct,
      detail,
      impact,
      line: position.line,
      column: position.column,
      scope: "FUNCTION",
    });
  }

  /** The node's one-based line and column. */
  position(node) {
    if (!node || !node.loc) return { line: -1, column: -1 };
    const start = node.loc.start;
    return { line: start.line, column: this.originalColumn(start.line, start.column + 1) };
  }

  /**
   * The column a position of the REWRITTEN source had in the original.
   *
   * The rewrite makes the source longer, so everything after it moved. The
   * shift is taken back here, so that a finding points at the line the
   * author wrote and not at the line the rewrite put it on.
   *
   * @param {number} line the line in the rewritten source
   * @param {number} column the column in the rewritten source
   * @return {number} the column in the original source
   */
  originalColumn(line, column) {
    if (!this.shifts || this.shifts.length === 0) return column;
    let shift = 0;
    for (const entry of this.shifts) {
      if (entry.line === line && entry.column <= column) shift += entry.delta;
    }
    return column - shift;
  }

  /** The name a type node gives, or null. */
  typeNameOf(node) {
    if (!node) return null;
    if (node.kind === "typereference") {
      return node.name ?? null;
    }
    if (node.kind === "uniontype" || node.kind === "intersectiontype") {
      const types = (node.types ?? []).map((t) => this.typeNameOf(t)).filter(Boolean);
      return types.length > 0 ? types[0] : null;
    }
    if (node.kind === "nullable") {
      return this.typeNameOf(node.type);
    }
    if (node.kind === "identifier") {
      return node.name;
    }
    if (node.kind === "name") {
      return this.qualifiedClassNameOf(node);
    }
    return null;
  }

  /** The TAC type of a variable, when the function inferred one. */
  tacTypeOfVariable(name) {
    if (!name || !this.state) return { kind: TYPE.UNKNOWN };
    return this.tacTypeOfName(this.state.types.get(name));
  }

  /** The TAC type of a PHP type name. */
  tacTypeOfName(name) {
    if (!name) return { kind: TYPE.UNKNOWN };
    const simple = String(name).split("\\").pop();
    const lower = simple.toLowerCase();
    if (lower === "int" || lower === "integer") return { kind: TYPE.INT, domain: INTEGRAL_DOMAIN };
    if (lower === "float" || lower === "double") return { kind: TYPE.DOUBLE, domain: FLOATING_DOMAIN };
    if (lower === "string") return { kind: TYPE.STRING };
    if (lower === "bool" || lower === "boolean") return { kind: TYPE.BOOLEAN };
    if (lower === "array" || lower === "iterable") {
      return { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } };
    }
    if (lower === "null") return { kind: TYPE.NULL };
    if (lower === "void") return { kind: TYPE.VOID };
    if (lower === "mixed" || lower === "object" || lower === "callable") {
      return { kind: TYPE.UNKNOWN };
    }
    if (SCALAR_TYPES.has(lower)) return { kind: TYPE.UNKNOWN };
    return { kind: TYPE.CLASS, name };
  }
}
