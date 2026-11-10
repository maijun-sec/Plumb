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
 */
const APPROXIMATED_OPERATORS = {
  "**": {
    binop: "MUL",
    note: "the TAC has no power operator, so the power is modelled as a multiplication",
  },
  "??": {
    binop: "LOR",
    note: "the TAC has no null-coalescing operator, so the coalescing is modelled as a disjunction",
  },
  "<=>": {
    binop: "EQ",
    note:
      "the TAC has no three-way comparison, so the spaceship operator is modelled as an equality "
        + "test",
  },
};

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
  "**=": "MUL",
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
  }

  newTemp(type) {
    this.tempCounter += 1;
    return varOp(`t_t${this.tempCounter}`, type, true);
  }

  newLabel(prefix = "L") {
    this.labelCounter += 1;
    return `${this.labelPrefix}${prefix}${this.labelCounter}`;
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
    this.guard(program, () => this.lowerBody(program.children ?? []));
    functions.push(this.finishFunction(state));
    this.state = outer;
    functions.push(...this.moduleFunctions);
    return { functions };
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
        this.declaredArities.set(this.qualifyFunction(name, namespace), this.declaredArityOf(child));
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
        this.declaredArities.set(
          `${qualified}::${methodName}`,
          this.declaredArityOf(member),
        );
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
   * @param {object} node a usegroup or a useitem
   * @param {string} namespace the namespace it is in
   */
  recordUse(node, namespace) {
    const items = node.kind === "usegroup" ? node.items ?? [] : [node];
    const type = node.type;
    if (type === "const") {
      // A `use const` binds a constant. A constant's value is not modelled,
      // so the binding has nothing to resolve.
      return;
    }
    const table = type === "function" ? this.functionAliases : this.classAliases;
    let map = table.get(namespace);
    if (!map) {
      map = new Map();
      table.set(namespace, map);
    }
    for (const item of items) {
      if (!item || !item.name) continue;
      const alias = this.nameText(item.alias) ?? item.name.split("\\").pop();
      map.set(alias, item.name);
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

  finishFunction(state) {
    for (const entry of state.problemCounts.values()) {
      if (entry.count > 1) {
        entry.problem.message = `${entry.problem.message} (${entry.count} occurrences)`;
      }
    }
    const problems = state.problems;
    return {
      name: state.name,
      signature: state.signature,
      enclosingClass: state.enclosingClass ?? undefined,
      precision: problems.length > 0 ? PRECISION.LOWERED : PRECISION.FULL,
      synthetic: false,
      params: state.params,
      locals: state.locals,
      instructions: state.instructions,
      problems,
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
    const outer = this.state;
    const name = this.nameText(node.name);
    const qualified = enclosingClass ? `${enclosingClass}::${name}` : this.qualifyFunction(name);
    const state = this.beginFunction(name, this.functionSignature(node, qualified), enclosingClass);
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

  lowerParameters(node) {
    for (const parameter of node.arguments ?? []) {
      const name = this.nameText(parameter.name) ?? "p";
      const operand = varOp(name, this.tacTypeOfName(this.typeNameOf(parameter.type)));
      this.state.params.push(operand);
      const type = this.typeNameOf(parameter.type);
      if (type) {
        this.state.types.set(name, type);
      }
    }
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
    return (node.arguments ?? []).length;
  }

  /**
   * The signature of a function: its qualified name, its parameter types and
   * its return type.
   *
   * The engine reads the arity out of this string, so the parameter list
   * holds every declared parameter -- the same count the callee signature
   * carries.
   */
  functionSignature(node, qualified) {
    const types = (node.arguments ?? []).map((p) => this.typeNameOf(p.type) ?? "any");
    const returns = this.typeNameOf(node.type) ?? "any";
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
      case "return":
        this.emit({
          kind: KIND.RETURN,
          lhs1: node.expr ? this.lowerExpression(node.expr) : undefined,
          ...this.position(node),
        });
        return;
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
      case "trait":
        this.lowerClass(node);
        return;
      case "interface":
        // An interface declares signatures and no bodies, so there is nothing
        // in it for a dataflow finding to be about.
        return;
      case "propertystatement":
        this.lowerPropertyStatement(node);
        return;
      case "constantstatement":
        this.lowerConstantStatement(node);
        return;
      case "global":
      case "static":
      case "declare":
      case "inline":
      case "noscript":
        return;
      case "block":
        this.lowerBody(node.children ?? []);
        return;
      case "unset":
        this.report(
          PROBLEM.SOURCE_LOWERED,
          node,
          "an unset drops a value the analysis cannot see being dropped",
        );
        return;
      case "goto":
      case "label":
        this.report(PROBLEM.UNHANDLED_NODE, node, "a goto has no TAC lowering");
        return;
      default:
        this.report(PROBLEM.UNHANDLED_NODE, node, "this statement has no TAC lowering");
    }
  }

  /**
   * A `break`.
   *
   * A break leaves the innermost loop or switch. A break with a LEVEL leaves
   * several of them, and the TAC has no way to say how many.
   */
  lowerBreak(node) {
    const level = node.level ?? 1;
    if (level > 1) {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "a break with a level leaves several loops at once, so it is modelled as leaving the "
          + "innermost one",
      );
    }
    if (!this.state.breakLabel) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this break is not inside a loop or a switch");
      return;
    }
    this.emit({ kind: KIND.JUMP, label: this.state.breakLabel, ...this.position(node) });
  }

  /**
   * A `continue`.
   *
   * A continue leaves the innermost loop, but NOT a switch: in PHP a continue
   * inside a switch continues the loop the switch is in.
   */
  lowerContinue(node) {
    const level = node.level ?? 1;
    if (level > 1) {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "a continue with a level leaves several loops at once, so it is modelled as leaving the "
          + "innermost one",
      );
    }
    if (!this.state.continueLabel) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this continue is not inside a loop");
      return;
    }
    this.emit({ kind: KIND.JUMP, label: this.state.continueLabel, ...this.position(node) });
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
   * its own -- a synthetic one, named after the class's constructor.
   */
  lowerClass(node) {
    const className = this.nameText(node.name);
    const qualified = this.qualifyClass(className);
    const functions = [];
    const outer = this.state;
    const state = this.beginFunction("<init>", `${qualified}.<init>():any`, qualified);
    try {
      this.guard(node, () => this.lowerClassBody(node, functions));
    } finally {
      this.state = outer;
    }
    functions.unshift(this.finishFunction(state));
    this.moduleFunctions.push(...functions);
  }

  lowerClassBody(node, functions) {
    const qualified = this.qualifyClass(this.nameText(node.name));
    for (const member of node.body ?? []) {
      if (member.kind === "method") {
        const methodName = this.nameText(member.name);
        this.declaredArities.set(`${qualified}::${methodName}`, this.declaredArityOf(member));
        functions.push(this.lowerFunction(member, qualified));
      } else if (member.kind === "class" || member.kind === "trait") {
        this.lowerClass(member);
      } else if (member.kind === "propertystatement") {
        this.lowerPropertyStatement(member);
      } else if (member.kind === "constantstatement") {
        this.lowerConstantStatement(member);
      } else if (member.kind === "usegroup" || member.kind === "useitem") {
        this.recordUse(member, this.namespace);
      } else {
        this.lowerStatement(member);
      }
    }
  }

  /**
   * The properties of a class.
   *
   * A property with an initial value is a value that exists from the moment
   * the class is created, so it belongs to the class's own body.
   */
  lowerPropertyStatement(node) {
    for (const property of node.properties ?? []) {
      if (!property.value) continue;
      const value = this.lowerExpression(property.value);
      const name = this.nameText(property.name) ?? "p";
      const variable = varOp(name, this.tacTypeOfName(this.typeNameOf(property.type)));
      this.declareLocal(variable);
      this.emit({ kind: KIND.MOVE, dest: variable, lhs1: value, ...this.position(property) });
    }
  }

  lowerConstantStatement(node) {
    for (const constant of node.constants ?? []) {
      const value = this.lowerExpression(constant.value);
      const name = this.nameText(constant.name) ?? "C";
      const variable = varOp(name);
      this.declareLocal(variable);
      this.emit({ kind: KIND.MOVE, dest: variable, lhs1: value, ...this.position(constant) });
    }
  }

  lowerIf(node) {
    const condition = this.lowerExpression(node.test);
    const thenLabel = this.state.newLabel("L");
    const elseLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel: thenLabel,
      falseLabel: elseLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: thenLabel, ...this.position(node) });
    this.lowerBody(node.body?.children ?? []);
    this.emit({ kind: KIND.JUMP, label: endLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: elseLabel, ...this.position(node) });
    if (node.alternate) {
      if (node.alternate.kind === "if") {
        this.lowerIf(node.alternate);
      } else {
        this.lowerBody(node.alternate.children ?? []);
      }
    }
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });
  }

  lowerWhile(node) {
    const conditionLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emit({ kind: KIND.JUMP, label: conditionLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: conditionLabel, ...this.position(node) });
    const condition = this.lowerExpression(node.test);
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel: bodyLabel,
      falseLabel: endLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: bodyLabel, ...this.position(node) });
    this.withLoopLabels(endLabel, conditionLabel, () => this.lowerBody(node.body?.children ?? []));
    this.emit({ kind: KIND.JUMP, label: conditionLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });
  }

  lowerDo(node) {
    const bodyLabel = this.state.newLabel("L");
    const conditionLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emit({ kind: KIND.LABEL, label: bodyLabel, ...this.position(node) });
    this.withLoopLabels(endLabel, conditionLabel, () => this.lowerBody(node.body?.children ?? []));
    this.emit({ kind: KIND.LABEL, label: conditionLabel, ...this.position(node) });
    const condition = this.lowerExpression(node.test);
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel: bodyLabel,
      falseLabel: endLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });
  }

  lowerFor(node) {
    for (const expression of node.init ?? []) {
      this.lowerExpression(expression);
    }
    const conditionLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const postLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emit({ kind: KIND.JUMP, label: conditionLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: conditionLabel, ...this.position(node) });
    const condition = node.test ? this.lowerExpression(node.test) : constOp(CONST.BOOL, "true");
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel: bodyLabel,
      falseLabel: endLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: bodyLabel, ...this.position(node) });
    this.withLoopLabels(endLabel, postLabel, () => this.lowerBody(node.body?.children ?? []));
    this.emit({ kind: KIND.LABEL, label: postLabel, ...this.position(node) });
    for (const expression of node.loop ?? []) {
      this.lowerExpression(expression);
    }
    this.emit({ kind: KIND.JUMP, label: conditionLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });
  }

  /**
   * A `foreach`.
   *
   * The loop reads the collection by index. The length is a call to the
   * language's own `count`, under a synthetic owner: the TAC has no length,
   * and a stand-in says so. An array literal has a length the translator can
   * read, so that one is a constant.
   */
  lowerForeach(node) {
    const collection = this.lowerExpression(node.source);
    const literal = node.source && node.source.kind === "array" ? node.source.items ?? [] : null;
    let length;
    if (literal) {
      length = constOp(CONST.INT, String(literal.length));
      if (literal.some((entry) => entry && entry.key)) {
        this.report(
          PROBLEM.SOURCE_LOWERED,
          node,
          "a foreach over an array with string keys does not see those keys, so the key variable "
            + "holds the position instead",
        );
      }
    } else {
      length = this.state.newTemp(TYPE.INT);
      this.emit({
        kind: KIND.CALL,
        dest: length,
        calleeSig: `${SYNTHETIC_OWNER}.count/1`,
        args: [constOp(CONST.STRING, SYNTHETIC_OWNER), collection],
        ...this.position(node),
      });
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
    this.emit({ kind: KIND.JUMP, label: conditionLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: conditionLabel, ...this.position(node) });
    const comparison = this.state.newTemp(TYPE.BOOLEAN);
    this.emit({
      kind: KIND.BINOP,
      dest: comparison,
      lhs1: index,
      lhs2: length,
      binop: "LT",
      ...this.position(node),
    });
    this.emit({
      kind: KIND.BRANCH,
      lhs1: comparison,
      trueLabel: bodyLabel,
      falseLabel: endLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: bodyLabel, ...this.position(node) });
    const element = this.state.newTemp();
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest: element,
      lhs1: collection,
      lhs2: index,
      ...this.position(node),
    });
    if (node.value) {
      this.lowerWrite(node.value, element, node);
    }
    if (node.key) {
      this.lowerWrite(node.key, index, node);
    }
    this.withLoopLabels(endLabel, postLabel, () => this.lowerBody(node.body?.children ?? []));
    this.emit({ kind: KIND.LABEL, label: postLabel, ...this.position(node) });
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
    this.emit({ kind: KIND.JUMP, label: conditionLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });
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
   * difference is reported, because it is a difference.
   */
  lowerSwitch(node) {
    this.report(
      PROBLEM.SOURCE_LOWERED,
      node,
      "a switch compares its subject loosely in PHP, and the TAC's equality test compares the "
        + "values as they are, so a loose comparison may not match",
    );
    const condition = this.lowerExpression(node.test);
    const cases = node.body?.children ?? [];
    const endLabel = this.state.newLabel("L");
    const caseLabels = cases.map(() => this.state.newLabel("L"));
    let defaultIndex = -1;

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
      this.emit({
        kind: KIND.BRANCH,
        lhs1: matched,
        trueLabel: caseLabels[i],
        falseLabel: nextLabel,
        ...this.position(caseNode),
      });
      this.emit({ kind: KIND.LABEL, label: nextLabel, ...this.position(caseNode) });
    }

    this.emit({
      kind: KIND.JUMP,
      label: defaultIndex >= 0 ? caseLabels[defaultIndex] : endLabel,
      ...this.position(node),
    });

    for (let i = 0; i < cases.length; i++) {
      this.emit({ kind: KIND.LABEL, label: caseLabels[i], ...this.position(cases[i]) });
      this.withBreakLabel(endLabel, () => this.lowerBody(cases[i].body?.children ?? []));
      if (i + 1 < cases.length) {
        // PHP's fall-through: the next case's body runs unless a break said
        // otherwise.
        this.emit({ kind: KIND.JUMP, label: caseLabels[i + 1], ...this.position(cases[i]) });
      }
    }
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });
  }

  /**
   * A `try` / `catch` / `finally`.
   *
   * The IR has explicit instructions for this: a CATCH_HANDLER binds the
   * exception to the handler's name, and a FINALLY_ENTER marks the block
   * every exit of the region runs through.
   */
  lowerTry(node) {
    const elseLabel = this.state.newLabel("L");
    const afterLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    const finallyLabel = node.always ? this.state.newLabel("L_FINALLY") : null;

    this.lowerBody(node.body?.children ?? []);
    this.emit({ kind: KIND.JUMP, label: elseLabel, ...this.position(node) });

    for (const handler of node.catches ?? []) {
      const typeName = this.catchTypeName(handler);
      const label = this.state.newLabel(`L_CATCH_${typeName}`);
      this.emit({ kind: KIND.LABEL, label, ...this.position(handler) });
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
      this.emit({ kind: KIND.JUMP, label: afterLabel, ...this.position(handler) });
    }

    this.emit({ kind: KIND.LABEL, label: afterLabel, ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: elseLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });

    if (node.always) {
      this.emit({ kind: KIND.LABEL, label: finallyLabel, ...this.position(node) });
      this.emit({ kind: KIND.FINALLY_ENTER, label: finallyLabel, ...this.position(node) });
      this.lowerBody(node.always.children ?? []);
    }
  }

  /**
   * The exception type a catch handles.
   *
   * A catch may name several types, and then it handles whichever was
   * thrown. The TAC's catch handler carries ONE type, so the first one is
   * used and the rest are reported.
   */
  catchTypeName(handler) {
    const types = handler.what ?? [];
    if (types.length === 0) return "Throwable";
    if (types.length > 1) {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        handler,
        "a catch that names several exception types is modelled as catching the first one only",
      );
    }
    return this.qualifiedClassNameOf(types[0]) ?? "Throwable";
  }

  /**
   * Lower a loop's body with the loop's own break and continue labels.
   *
   * @param {string} breakLabel where a `break` goes
   * @param {string} continueLabel where a `continue` goes
   * @param {Function} body the lowering
   */
  withLoopLabels(breakLabel, continueLabel, body) {
    const outerBreak = this.state.breakLabel;
    const outerContinue = this.state.continueLabel;
    this.state.breakLabel = breakLabel;
    this.state.continueLabel = continueLabel;
    try {
      body();
    } finally {
      this.state.breakLabel = outerBreak;
      this.state.continueLabel = outerContinue;
    }
  }

  /**
   * Lower a switch's cases with the switch's own break label.
   *
   * A `continue` inside a switch continues the LOOP the switch is in, so only
   * the break label changes.
   *
   * @param {string} breakLabel where a `break` goes
   * @param {Function} body the lowering
   */
  withBreakLabel(breakLabel, body) {
    const outer = this.state.breakLabel;
    this.state.breakLabel = breakLabel;
    try {
      body();
    } finally {
      this.state.breakLabel = outer;
    }
  }

  // ---- expressions ----------------------------------------------------------------------

  lowerExpression(node) {
    if (!node) return undefined;
    switch (node.kind) {
      case "variable":
        return varOp(node.name, this.tacTypeOfVariable(node.name));
      case "identifier":
        // A name in an expression position is a constant, and its value is
        // not known here.
        return varOp(node.name);
      case "number":
        return this.numberConstant(node.value);
      case "string":
        return constOp(CONST.STRING, node.value);
      case "boolean":
        return constOp(CONST.BOOL, node.value ? "true" : "false");
      case "null":
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
        for (const variable of node.variables ?? []) {
          this.lowerExpression(variable);
        }
        // Whether a variable is set is not known here, so the answer is
        // unknown. A constant "false" would be a claim, and a claim the
        // analysis cannot support is how a finding goes missing.
        return this.emitHavoc(node, TYPE.BOOLEAN);
      }
      case "empty": {
        this.lowerExpression(node.expression);
        return this.emitHavoc(node, TYPE.BOOLEAN);
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
        this.report(
          PROBLEM.SOURCE_LOWERED,
          node,
          "a closure has no TAC lowering, so the value it produces is unknown",
        );
        return this.emitHavoc(node);
      case "yield":
      case "yieldfrom":
        this.report(
          PROBLEM.UNHANDLED_NODE,
          node,
          "a generator has no TAC lowering, so the value it produces is unknown",
        );
        return this.emitHavoc(node);
      case "match":
        return this.lowerMatch(node);
      case "list":
        this.report(
          PROBLEM.SOURCE_LOWERED,
          node,
          "a list in an expression position has no TAC lowering, so its value is unknown",
        );
        return this.emitHavoc(node);
      default:
        this.report(PROBLEM.UNHANDLED_NODE, node, "this expression has no TAC lowering");
        return this.emitHavoc(node);
    }
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
    const condition = this.lowerExpression(node.test);
    const thenLabel = this.state.newLabel("L");
    const elseLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    const dest = this.state.newTemp();
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel: thenLabel,
      falseLabel: elseLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: thenLabel, ...this.position(node) });
    const whenTrue = node.trueExpr ? this.lowerExpression(node.trueExpr) : condition;
    this.emit({ kind: KIND.MOVE, dest, lhs1: whenTrue, ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: elseLabel, ...this.position(node) });
    const whenFalse = node.falseExpr ? this.lowerExpression(node.falseExpr) : this.emitHavoc(node);
    this.emit({ kind: KIND.MOVE, dest, lhs1: whenFalse, ...this.position(node) });
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });
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

    const testLabels = tests.map(() => this.state.newLabel("L"));
    for (let k = 0; k < tests.length; k++) {
      this.emit({ kind: KIND.LABEL, label: testLabels[k], ...this.position(arms[tests[k].arm]) });
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
      this.emit({
        kind: KIND.BRANCH,
        lhs1: matched,
        trueLabel: armLabels[tests[k].arm],
        falseLabel: falseTarget,
        ...this.position(arms[tests[k].arm]),
      });
    }

    // No arm matched: PHP throws, and the TAC has no way to say that, so the
    // value is unknown rather than missing. The block is laid out RIGHT
    // AFTER the chain: a chain that fell through into the first arm's body
    // would report that arm's findings for a value PHP would have thrown on.
    this.emit({ kind: KIND.LABEL, label: noMatchLabel, ...this.position(node) });
    this.emit({ kind: KIND.MOVE, dest, lhs1: this.emitHavoc(node), ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel, ...this.position(node) });

    for (let i = 0; i < arms.length; i++) {
      this.emit({ kind: KIND.LABEL, label: armLabels[i], ...this.position(arms[i]) });
      const value = this.lowerExpression(arms[i].body);
      this.emit({ kind: KIND.MOVE, dest, lhs1: value, ...this.position(arms[i]) });
      this.emit({ kind: KIND.JUMP, label: endLabel, ...this.position(arms[i]) });
    }
    this.emit({ kind: KIND.LABEL, label: endLabel, ...this.position(node) });
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
   */
  lowerAssign(node) {
    const operator = node.operator ?? "=";
    if (operator === "=") {
      const value = this.lowerExpression(node.right);
      this.lowerWrite(node.left, value, node);
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
   */
  lowerWrite(target, value, node) {
    if (!target) return;
    if (target.kind === "variable") {
      const variable = varOp(target.name, this.tacTypeOfVariable(target.name));
      this.declareLocal(variable);
      this.emit({ kind: KIND.MOVE, dest: variable, lhs1: value, ...this.position(target) });
      this.inferTypeOf(target.name, node?.right);
      return;
    }
    if (target.kind === "propertylookup" || target.kind === "nullsafepropertylookup") {
      const receiver = this.lowerExpression(target.what);
      const key = constOp(CONST.STRING, this.nameText(target.offset) ?? "");
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: receiver,
        lhs2: key,
        dest: value,
        ...this.position(target),
      });
      return;
    }
    if (target.kind === "offsetlookup") {
      const receiver = this.lowerExpression(target.what);
      const key = this.lowerKey(target.offset);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: receiver,
        lhs2: key,
        dest: value,
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
      const receiver = constOp(CONST.STRING, this.resolveStaticOwner(target));
      const key = constOp(CONST.STRING, this.nameText(target.offset) ?? "");
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: receiver,
        lhs2: key,
        dest: value,
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
   */
  lowerPropertyRead(node) {
    const receiver = this.lowerExpression(node.what);
    const key = constOp(CONST.STRING, this.nameText(node.offset) ?? "");
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
   * Its value is not known here, so it is a name the analysis has not seen
   * a value for.
   */
  lowerStaticRead(node) {
    const owner = this.resolveStaticOwner(node);
    const member = this.nameText(node.offset) ?? "";
    return varOp(`${owner}::${member}`);
  }

  /**
   * The class a static access names.
   *
   * `self` and `static` are the class the code is in, and `parent` is the
   * class it extends -- which the translator does not know.
   */
  resolveStaticOwner(node) {
    const what = node.what;
    if (!what) return this.enclosingClassName();
    if (what.kind === "name") {
      return this.qualifiedClassNameOf(what);
    }
    if (what.kind === "selfreference" || what.kind === "staticreference") {
      return this.enclosingClassName();
    }
    if (what.kind === "parentreference") {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "the parent class is not known here, so a member of it is modelled as a member of the "
          + "class the code is in",
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
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "the receiver's type could not be resolved, so the member is modelled as a member of the "
          + "class the code is in",
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
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "the parent class is not known here, so a construction of it is modelled as a construction "
          + "of the class the code is in",
      );
      return this.enclosingClassName();
    }
    if (node.kind === "variable") {
      this.report(
        PROBLEM.UNHANDLED_NODE,
        node,
        "the class of a construction is named by a variable, so the constructed type is unknown",
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
   */
  lowerNew(node) {
    const args = [];
    for (const argument of this.argumentsOf(node)) {
      args.push(this.lowerExpression(argument));
    }
    const className = this.classNameOf(node.what);
    const typeName = className ?? "object";
    const dest = this.state.newTemp({ kind: TYPE.CLASS, name: typeName });
    this.emit({
      kind: KIND.NEW,
      calleeSig: className ? `${className}.<init>/${args.length}` : undefined,
      dest,
      ctorType: { kind: TYPE.CLASS, name: typeName },
      args,
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
   * name as a constant.
   */
  lowerCall(node) {
    const callee = this.resolveCallee(node);
    const args = [];
    for (const argument of this.argumentsOf(node)) {
      args.push(this.lowerExpression(argument));
    }
    const wireArgs = [callee.receiver, ...args];
    const dest = this.state.newTemp();
    const kind = callee.declared ? KIND.CALL : KIND.UNINTERPRETED;
    this.emit({
      kind,
      dest,
      calleeSig: `${callee.qualified}/${callee.arity}`,
      args: wireArgs,
      ...this.position(node),
    });
    return dest;
  }

  /**
   * The arguments of a call.
   *
   * A named argument is a `namedargument` node with a name and a value; an
   * ordinary one is the expression itself.
   */
  argumentsOf(node) {
    const out = [];
    for (const argument of node.arguments ?? []) {
      if (argument && argument.kind === "namedargument") {
        out.push(argument.value);
      } else {
        out.push(argument);
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
    if (name.startsWith("\\")) return name;
    const alias = this.functionAliases.get(scope)?.get(name);
    if (alias) return alias;
    const declared = this.namespaceFunctions.get(scope);
    if (declared && declared.has(name)) {
      return scope ? `${scope}\\${name}` : `\\${name}`;
    }
    return `\\${name}`;
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
    if (name.startsWith("\\")) return name;
    const alias = this.classAliases.get(scope)?.get(name);
    if (alias) return alias;
    return scope ? `${scope}\\${name}` : `\\${name}`;
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
    const name = node.name ?? "";
    if (node.resolution === "fqn") {
      return name;
    }
    if (node.resolution === "rn") {
      const relative = name.replace(/^namespace\\/, "");
      return this.namespace ? `${this.namespace}\\${relative}` : `\\${relative}`;
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
   * Report a problem on the current function.
   *
   * The same problem in the same function is reported ONCE, with the number
   * of times it happened: a file with fifty calls to a name the analysis
   * cannot resolve has one problem, not fifty.
   */
  report(kind, node, message) {
    const key = `${kind}|${message}`;
    const entry = this.state.problemCounts.get(key);
    if (entry) {
      entry.count += 1;
      return;
    }
    const problem = {
      kind,
      message,
      nodeClass: node?.kind ?? null,
      ...this.position(node),
    };
    this.state.problemCounts.set(key, { problem, count: 1 });
    this.state.problems.push(problem);
  }

  /** The node's one-based line and column. */
  position(node) {
    if (!node || !node.loc) return { line: -1, column: -1 };
    const start = node.loc.start;
    return { line: start.line, column: start.column + 1 };
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
    if (lower === "int" || lower === "integer") return { kind: TYPE.INT };
    if (lower === "float" || lower === "double") return { kind: TYPE.DOUBLE };
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
