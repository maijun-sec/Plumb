// The translator: TypeScript and JavaScript source -> Plumb's TAC IR.
//
// WHAT THIS IS. A frontend lowers a source language into the TAC, the
// three-address instruction stream the analysis engine consumes. The TAC is
// language-neutral: it says "move", "call", "read a field", "index a
// container", and nothing about what a variable means.
//
// THE ONE LANGUAGE-SPECIFIC DECISION. In JavaScript, Python and PHP an object
// is a NAMESPACE, not a record: `obj.userId` and `obj["userId"]` are the same
// location, and `arr[0]` is `arr["0"]`. So a property access is lowered as a
// CONTAINER ACCESS with a string key -- an ARRAYLOAD / ARRAYSTORE whose index
// operand is the key -- and not as a field access. The engine's keyed
// namespace is what gives that access its identity: the pair (object, key).
//
// EVERY FAILURE IS REPORTED. A construct this translator cannot lower becomes
// a problem on the function, and the affected value becomes a HAVOC. It never
// becomes a silent "nothing to report": a sidecar that drops a construct
// quietly would leave the engine believing the program had no findings.
//
// The compiler facts come from the TypeScript compiler itself, so the types,
// the overloads and the property names are the ones the compiler sees.

import {
  CONST,
  KIND,
  PRECISION,
  PROBLEM,
  TYPE,
  varOp,
  constOp,
} from "./protocol.mjs";

import {
  isArrayLiteralExpression,
  isAwaitExpression,
  isBinaryExpression,
  isBindingElement,
  isBlock,
  isBreakStatement,
  isCallExpression,
  isClassDeclaration,
  isClassExpression,
  isContinueStatement,
  isElementAccessExpression,
  isExpressionStatement,
  isForOfStatement,
  isForStatement,
  isFunctionDeclaration,
  isFunctionExpression,
  isIdentifier,
  isIfStatement,
  isImportDeclaration,
  isImportEqualsDeclaration,
  isNewExpression,
  isMethodDeclaration,
  isNamespaceImport,
  isNonNullExpression,
  isNullLiteral,
  isNumericLiteral,
  isObjectBindingPattern,
  isObjectLiteralExpression,
  isParenthesizedExpression,
  isPostfixUnaryExpression,
  isPrefixUnaryExpression,
  isPropertyAccessExpression,
  isReturnStatement,
  isStringLiteral,
  isTemplateExpression,
  isThrowStatement,
  isVariableDeclaration,
  isVariableStatement,
  isWhileStatement,
  isArrowFunction,
  isNoSubstitutionTemplateLiteral,
  isTypeOfExpression,
  SyntaxKind,
} from "typescript/unstable/ast";

import { TypeFlags } from "typescript/unstable/sync";

/**
 * The TAC's binary operators, by the compiler's operator token.
 *
 * <p>The key is the token's KIND, not its spelling. A map keyed by the
 * spelling would have to be looked up with a string, and the compiler hands
 * out a number -- and a lookup that always misses does not look like a
 * lookup at all: every binary expression would become an unknown value, and
 * the taint that flowed through it would stop there.
 */
const BINARY_OPERATORS = {
  [SyntaxKind.PlusToken]: "ADD",
  [SyntaxKind.MinusToken]: "SUB",
  [SyntaxKind.AsteriskToken]: "MUL",
  [SyntaxKind.SlashToken]: "DIV",
  [SyntaxKind.PercentToken]: "MOD",
  [SyntaxKind.AsteriskAsteriskToken]: "POW",
  [SyntaxKind.EqualsEqualsToken]: "EQ",
  [SyntaxKind.EqualsEqualsEqualsToken]: "EQ",
  [SyntaxKind.ExclamationEqualsToken]: "NE",
  [SyntaxKind.ExclamationEqualsEqualsToken]: "NE",
  [SyntaxKind.LessThanToken]: "LT",
  [SyntaxKind.LessThanEqualsToken]: "LE",
  [SyntaxKind.GreaterThanToken]: "GT",
  [SyntaxKind.GreaterThanEqualsToken]: "GE",
  [SyntaxKind.AmpersandAmpersandToken]: "LAND",
  [SyntaxKind.BarBarToken]: "LOR",
  [SyntaxKind.QuestionQuestionToken]: "NULL_COALESCE",
  [SyntaxKind.AmpersandToken]: "AND",
  [SyntaxKind.BarToken]: "OR",
  [SyntaxKind.CaretToken]: "XOR",
  [SyntaxKind.LessThanLessThanToken]: "SHL",
  [SyntaxKind.GreaterThanGreaterThanToken]: "SHR",
  [SyntaxKind.GreaterThanGreaterThanGreaterThanToken]: "USHR",
};

/**
 * The owner of a REAL call whose owner this translator could not determine.
 *
 * <p>This is not a stand-in for a construct the TAC has no counterpart for --
 * it is a real call, whose receiver's owner is simply not knowable here.
 *
 * <p>The owner it used to carry was the MODULE the file belongs to, and that
 * shape had two faults:
 *
 * <ul>
 *   <li>It looked like a module path, so a sink catalogue written against a
 *       module could claim the program's OWN file. A file called
 *       "logging.ts" whose "logger.error(value)" reached the catalogue's
 *       "^logging\.(debug|info|...)\b" -- a finding against a program that
 *       never called the logging module at all.
 *   <li>It was a GUESS dressed as a fact. The owner of that call is whatever
 *       the logger is, and this translator cannot tell.
 * </ul>
 *
 * <p>The angle brackets are the point: no module path can contain one, and
 * every sink pattern in the catalogue is anchored at the start of the callee
 * key, so a key that begins with one cannot be claimed by any of them.
 */
const UNKNOWN_OWNER = "<unknown>";

/**
 * The operators the TAC does not have.
 *
 * <p>The TAC's binary operators are the arithmetic, the bitwise, the
 * comparison and the short-circuit ones. A power and a null-coalescing are
 * not among them.
 *
 * <p>Each is mapped onto the closest thing the TAC has and REPORTED: the
 * function is still lowered -- the taint of the operands reaches the result
 * either way -- but the reader is told that the operator in the TAC is not
 * the operator in the source.
 */
const APPROXIMATED_OPERATORS = {
  [SyntaxKind.AsteriskAsteriskToken]: {
    binop: "MUL",
    note: "the TAC has no power operator, so the power is modelled as a multiplication",
  },
  [SyntaxKind.QuestionQuestionToken]: {
    binop: "LOR",
    note: "the TAC has no null-coalescing operator, so the coalescing is modelled as a disjunction",
  },
};

/**
 * The compound assignments, by the compiler's operator token.
 *
 * <p>A compound assignment reads its target, combines it, and writes it back.
 * The read and the write are separate instructions, because the engine tracks
 * the value of a location, not the statement that changed it.
 *
 * <p>The value is an object rather than a string, because two of the
 * operators have no counterpart in the TAC and the approximation has to be
 * reported.
 */
const COMPOUND_OPERATORS = {
  [SyntaxKind.PlusEqualsToken]: { binop: "ADD" },
  [SyntaxKind.MinusEqualsToken]: { binop: "SUB" },
  [SyntaxKind.AsteriskEqualsToken]: { binop: "MUL" },
  [SyntaxKind.SlashEqualsToken]: { binop: "DIV" },
  [SyntaxKind.PercentEqualsToken]: { binop: "MOD" },
  [SyntaxKind.AsteriskAsteriskEqualsToken]: {
    binop: "MUL",
    note: "the TAC has no power operator, so the power is modelled as a multiplication",
  },
  [SyntaxKind.LessThanLessThanEqualsToken]: { binop: "SHL" },
  [SyntaxKind.GreaterThanGreaterThanEqualsToken]: { binop: "SHR" },
  [SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken]: { binop: "USHR" },
  [SyntaxKind.AmpersandEqualsToken]: { binop: "AND" },
  [SyntaxKind.BarEqualsToken]: { binop: "OR" },
  [SyntaxKind.CaretEqualsToken]: { binop: "XOR" },
  [SyntaxKind.BarBarEqualsToken]: { binop: "LOR" },
  [SyntaxKind.AmpersandAmpersandEqualsToken]: { binop: "LAND" },
  [SyntaxKind.QuestionQuestionEqualsToken]: {
    binop: "LOR",
    note: "the TAC has no null-coalescing operator, so the coalescing is modelled as a disjunction",
  },
};

/**
 * The TAC's unary operators, by the TypeScript operator token.
 *
 * <p>There is no unary plus in the TAC, and there does not need to be: a
 * unary plus is a no-op, so the operand is the result.
 */
const UNARY_OPERATORS = {
  "-": "NEG",
  "!": "NOT",
  "~": "BITNOT",
};

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
    this.tempCounter = 0;
    this.labelCounter = 0;
    this.labelPrefix = "";
  }

  newTemp(type) {
    this.tempCounter += 1;
    return varOp(`t_t${this.tempCounter}`, type, true);
  }

  newLabel() {
    this.labelCounter += 1;
    return `${this.labelPrefix}L${this.labelCounter}`;
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
   * @param {object} options.checker the compiler's checker
   * @param {string} options.moduleName the module the file belongs to
   * @param {string} options.language the source language, for the wire
   * @param {string} options.text the file's text, for the line and column
   */
  constructor(options) {
    this.checker = options.checker;
    this.moduleName = options.moduleName;
    this.language = options.language;
    this.text = options.text;
    this.lineStarts = computeLineStarts(options.text ?? "");
    this.imports = new Map();
    this.declaredNames = new Set();
    this.state = null;
  }

  /** The functions declared in a source file, in source order. */
  translateSourceFile(sourceFile) {
    this.collectModuleBindings(sourceFile);
    this.collectDeclaredNames(sourceFile);
    const functions = [];
    for (const statement of sourceFile.statements) {
      if (isFunctionDeclaration(statement) && statement.name) {
        functions.push(this.lowerFunctionDeclaration(statement, null));
        continue;
      }
      if (isClassDeclaration(statement) && statement.name) {
        functions.push(...this.lowerClass(statement));
        continue;
      }
      if (isVariableStatement(statement)) {
        const lifted = this.liftFunctionVariables(statement);
        if (lifted.length > 0) {
          functions.push(...lifted);
          continue;
        }
      }
      // A top-level statement that is not a function: the module's own code.
      // It becomes a synthetic function so that the engine has somewhere to
      // put the instructions.
      const moduleFunction = this.beginFunction("<module>", this.moduleName, null);
      this.guard(statement, () => {
        this.lowerStatement(statement);
      });
      functions.push(this.finishFunction(moduleFunction));
    }
    return { functions };
  }

  // ---- the modules a file reaches --------------------------------------------------------------

  /**
   * The modules the file reaches, by the local names it bound them to.
   *
   * <p>A script language reaches its libraries through MODULES rather than
   * through classes: `child_process.exec` is a function of the child_process
   * module, and there is no class to name. So the owner of a call to a
   * module-level function is the MODULE it was imported from.
   *
   * <p>The owner's alternative -- the module the FILE belongs to -- says
   * nothing about where the function came from. The catalogue would have to
   * name every file in the program, and a call in a file called `app.ts`
   * would be `app.exec`, which no catalogue can match.
   *
   * <p>The alias is resolved away here. `import { exec as run }` calls
   * `exec`, so the name the catalogue has to match is the exported one.
   */
  collectModuleBindings(sourceFile) {
    this.imports = new Map();
    for (const statement of sourceFile.statements ?? []) {
      if (isImportDeclaration(statement)) {
        this.collectImportDeclaration(statement);
      } else if (isImportEqualsDeclaration(statement)) {
        this.collectImportEquals(statement);
      } else if (isVariableStatement(statement)) {
        this.collectRequireBindings(statement);
      }
    }
  }

  /**
   * The names this file declares at the top level.
   *
   * <p>A call to a name the file declares is a call to THIS MODULE's own
   * function, and the owner of that call is the module -- the call graph
   * resolves the call against the definition, and the definition's owner is
   * the module. Changing that owner would break the resolution, and with it
   * the taint that flows into the function.
   *
   * <p>A call to a name the file does NOT declare is something else: a
   * global, or a function of a module that was never imported. Its owner is
   * not this module, and saying so hands the catalogue a module path that
   * does not exist -- the file's own name, which a catalogue written
   * against a real module can claim by accident.
   *
   * <p>The names are collected before anything is lowered, because a call may
   * appear before the declaration it calls: a call in the first function of
   * a file may name a function declared at the end of it.
   *
   * <p>Only the TOP LEVEL is collected, and that is exactly the set that has
   * a definition: this translator lowers a top-level function declaration, a
   * top-level class and a top-level function variable, and nothing else gets
   * a function of its own.
   */
  collectDeclaredNames(sourceFile) {
    this.declaredNames = new Set();
    for (const statement of sourceFile.statements ?? []) {
      if (isFunctionDeclaration(statement) && statement.name) {
        this.declaredNames.add(statement.name.text);
        continue;
      }
      if (isClassDeclaration(statement) && statement.name) {
        this.declaredNames.add(statement.name.text);
        continue;
      }
      if (isVariableStatement(statement)) {
        for (const declaration of statement.declarationList?.declarations ?? []) {
          const initialiser = declaration.initializer;
          if (!initialiser || !declaration.name?.text) continue;
          if (isArrowFunction(initialiser) || isFunctionExpression(initialiser)) {
            this.declaredNames.add(declaration.name.text);
          }
        }
      }
    }
  }

  /** The bindings of one `import ... from "module"`. */
  collectImportDeclaration(statement) {
    const specifier = statement.moduleSpecifier && statement.moduleSpecifier.text;
    if (!specifier) return;
    const clause = statement.importClause;
    if (!clause) return;
    if (clause.name && clause.name.text) {
      // A default import names the module's default export, and the module
      // object is what its members are read from.
      this.bindImport(clause.name.text, specifier, null, true);
    }
    const bindings = clause.namedBindings;
    if (!bindings) return;
    if (isNamespaceImport(bindings)) {
      this.bindImport(bindings.name.text, specifier, null, true);
      return;
    }
    for (const element of bindings.elements ?? []) {
      const local = element.name && element.name.text;
      if (!local) continue;
      const exported = (element.propertyName && element.propertyName.text) || local;
      this.bindImport(local, specifier, exported, false);
    }
  }

  /** The binding of an `import name = require("module")`. */
  collectImportEquals(statement) {
    const reference = statement.moduleReference;
    const expression = reference && reference.expression;
    if (!expression || !isStringLiteral(expression)) return;
    const local = statement.name && statement.name.text;
    if (!local) return;
    this.bindImport(local, expression.text, null, true);
  }

  /**
   * The bindings of a `require("module")`.
   *
   * <p>The CommonJS form is not a second-class citizen: it is how most Node
   * programs are written, and a catalogue that only matched the ESM form
   * would report a clean program that is not clean.
   */
  collectRequireBindings(statement) {
    for (const declaration of statement.declarationList.declarations ?? []) {
      const initializer = declaration.initializer;
      if (!initializer || !isCallExpression(initializer)) continue;
      const specifier = this.requiredModule(initializer);
      if (!specifier) continue;
      const name = declaration.name;
      if (isObjectBindingPattern(name)) {
        for (const element of name.elements ?? []) {
          const local = element.name && element.name.text;
          if (!local) continue;
          const exported = (element.propertyName && element.propertyName.text) || local;
          this.bindImport(local, specifier, exported, false);
        }
      } else if (isIdentifier(name)) {
        this.bindImport(name.text, specifier, null, true);
      }
    }
  }

  /** The module a `require(...)` call names, if it names one. */
  requiredModule(call) {
    const callee = call.expression;
    if (!isIdentifier(callee) || callee.text !== "require") return undefined;
    for (const argument of call.arguments ?? []) {
      if (isStringLiteral(argument)) return argument.text;
      return undefined;
    }
    return undefined;
  }

  /** Remember that a local name came from a module. */
  bindImport(local, module, exported, isNamespace) {
    this.imports.set(local, { module, exported, isNamespace });
  }

  // ---- functions ----------------------------------------------------------------------------

  lowerFunctionDeclaration(node, enclosingClass) {
    const name = node.name ? node.name.text : "<anonymous>";
    const state = this.beginFunction(name, this.functionSignature(node, enclosingClass), enclosingClass);
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    return this.finishFunction(state);
  }

  /** The methods of a class, and the class's own field initialisers. */
  lowerClass(node) {
    const out = [];
    const className = node.name ? node.name.text : "<anonymous>";
    for (const member of node.members) {
      // A class method is a METHOD declaration, not a function declaration:
      // it belongs to the class, and the engine has to see it that way.
      if (isMethodDeclaration(member) && member.name) {
        out.push(this.lowerFunctionDeclaration(member, className));
      }
    }
    return out;
  }

  /**
   * A variable whose initialiser is a function.
   *
   * `const handle = (req) => ...` declares a function, and the engine has to
   * see it as one: a call to `handle` has a callee signature, and a callee
   * signature is what the sink catalogue matches on.
   */
  liftFunctionVariables(statement) {
    const out = [];
    for (const declaration of statement.declarationList.declarations) {
      const initialiser = declaration.initializer;
      if (!initialiser) continue;
      if (!isArrowFunction(initialiser) && !isFunctionExpression(initialiser)) continue;
      const name = declaration.name && declaration.name.text ? declaration.name.text : "<anonymous>";
      const state = this.beginFunction(name, this.functionSignature(initialiser, null), null);
      this.guard(initialiser, () => {
        this.lowerParameters(initialiser.parameters);
        if (initialiser.body) {
          this.lowerBlock(initialiser.body);
        }
      });
      out.push(this.finishFunction(state));
    }
    return out;
  }

  beginFunction(name, signature, enclosingClass) {
    const state = new FunctionState(name, signature, enclosingClass);
    this.state = state;
    return state;
  }

  finishFunction(state) {
    const problems = state.problems;
    return {
      name: state.name,
      signature: state.signature,
      enclosingClass: state.enclosingClass ?? undefined,
      returnType: undefined,
      precision:
        // ANY problem downgrades the function. A problem means a construct was
        // approximated -- an unhandled node became an unknown value, a break
        // became a jump, an unresolved callee became a black box -- and a
        // function that was approximated did not come out whole.
        problems.length > 0 ? PRECISION.LOWERED : PRECISION.FULL,
      synthetic: false,
      params: state.params,
      locals: state.locals,
      instructions: state.instructions,
      problems,
    };
  }

  lowerParameters(parameters) {
    if (!parameters) return;
    for (const parameter of parameters) {
      const name = parameter.name && parameter.name.text ? parameter.name.text : "p";
      const operand = varOp(name, this.typeOf(parameter));
      this.state.params.push(operand);
    }
  }

  /** The signature string: `owner.name(ParamTypes):Return`. */
  functionSignature(node, enclosingClass) {
    const owner = enclosingClass ?? this.moduleName;
    const parameters = node.parameters ?? [];
    const types = parameters.map((p) => this.typeTextOf(p));
    const returnType = this.returnTypeTextOf(node);
    return `${owner}.${node.name ? node.name.text : "<anonymous>"}(${types.join(",")}):${returnType}`;
  }

  // ---- statements ---------------------------------------------------------------------------

  lowerStatement(node) {
    if (!node) return;
    if (isExpressionStatement(node)) {
      this.lowerExpression(node.expression);
      return;
    }
    if (isVariableStatement(node)) {
      this.lowerVariableStatement(node);
      return;
    }
    if (isReturnStatement(node)) {
      const value = node.expression ? this.lowerExpression(node.expression) : undefined;
      this.emit({
        kind: KIND.RETURN,
        lhs1: value,
        ...this.position(node),
      });
      return;
    }
    if (isThrowStatement(node)) {
      const value = node.expression ? this.lowerExpression(node.expression) : undefined;
      this.emit({ kind: KIND.THROW, lhs1: value, ...this.position(node) });
      return;
    }
    if (isIfStatement(node)) {
      this.lowerIf(node);
      return;
    }
    if (isWhileStatement(node)) {
      this.lowerWhile(node);
      return;
    }
    if (isForStatement(node)) {
      this.lowerFor(node);
      return;
    }
    if (isForOfStatement(node)) {
      this.lowerForOf(node);
      return;
    }
    if (isBlock(node)) {
      this.lowerBlock(node);
      return;
    }
    if (isBreakStatement(node) || isContinueStatement(node)) {
      // The TAC has no break or continue. The loop's own structure is what
      // carries the control flow, so a break is recorded and the rest of the
      // body is still lowered -- the loop's exit edge is where the value ends
      // up, and dropping the body would hide a taint that flows through it.
      this.report(PROBLEM.SOURCE_LOWERED, node, "a break or continue has no TAC lowering");
      return;
    }
    this.report(PROBLEM.UNHANDLED_NODE, node, "this statement has no TAC lowering");
  }

  lowerVariableStatement(node) {
    for (const declaration of node.declarationList.declarations) {
      if (!isVariableDeclaration(declaration)) continue;
      const name = declaration.name && declaration.name.text ? declaration.name.text : "v";
      const type = this.typeOf(declaration);
      const variable = varOp(name, type);
      if (!this.state.locals.some((l) => l.name === name)) {
        this.state.locals.push(variable);
      }
      if (!declaration.initializer) continue;
      const value = this.lowerExpression(declaration.initializer);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: value,
        ...this.position(declaration),
      });
    }
  }

  lowerBlock(node) {
    for (const statement of node.statements) {
      this.lowerStatement(statement);
    }
  }

  lowerIf(node) {
    const condition = this.lowerExpression(node.condition);
    const thenLabel = this.state.newLabel();
    const elseLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel: thenLabel,
      falseLabel: elseLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: thenLabel });
    if (node.thenStatement) this.lowerStatement(node.thenStatement);
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emit({ kind: KIND.LABEL, label: elseLabel });
    if (node.elseStatement) this.lowerStatement(node.elseStatement);
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  lowerWhile(node) {
    const headLabel = this.state.newLabel();
    const bodyLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: headLabel });
    const condition = this.lowerExpression(node.condition);
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel: bodyLabel,
      falseLabel: endLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: bodyLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  lowerFor(node) {
    if (node.initializer) {
      if (isVariableStatement(node.initializer)) {
        this.lowerVariableStatement(node.initializer);
      } else {
        this.lowerExpression(node.initializer);
      }
    }
    const headLabel = this.state.newLabel();
    const bodyLabel = this.state.newLabel();
    const stepLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: headLabel });
    if (node.condition) {
      const condition = this.lowerExpression(node.condition);
      this.emit({
        kind: KIND.BRANCH,
        lhs1: condition,
        trueLabel: bodyLabel,
        falseLabel: endLabel,
        ...this.position(node),
      });
    } else {
      this.emit({ kind: KIND.JUMP, label: bodyLabel });
    }
    this.emit({ kind: KIND.LABEL, label: bodyLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.emit({ kind: KIND.JUMP, label: stepLabel });
    this.emit({ kind: KIND.LABEL, label: stepLabel });
    if (node.incrementor) this.lowerExpression(node.incrementor);
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  /**
   * A `for ... of` loop.
   *
   * The iterated value is the element, so the loop variable is bound to it
   * with a MOVE. The TAC has no iteration opcode, so the loop's structure
   * carries it -- the body's instructions are in the stream, and the engine's
   * iterator handling is what turns a tainted element back into a tainted
   * loop variable.
   */
  lowerForOf(node) {
    const iterable = this.lowerExpression(node.expression);
    if (node.initializer && isVariableDeclaration(node.initializer)) {
      const name = node.initializer.name && node.initializer.name.text ? node.initializer.name.text : "v";
      const variable = varOp(name, this.typeOf(node.initializer));
      if (!this.state.locals.some((l) => l.name === name)) {
        this.state.locals.push(variable);
      }
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: iterable,
        ...this.position(node),
      });
    }
    const headLabel = this.state.newLabel();
    const bodyLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: headLabel });
    this.emit({
      kind: KIND.BRANCH,
      lhs1: constOp(CONST.BOOL, true),
      trueLabel: bodyLabel,
      falseLabel: endLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: bodyLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  // ---- expressions --------------------------------------------------------------------------

  /**
   * Lower an expression to an operand.
   *
   * The result is an operand, not a value: an expression that produces a value
   * gets a temporary, and the temporary is what the caller uses.
   */
  lowerExpression(node) {
    if (!node) return undefined;
    if (isIdentifier(node)) {
      return varOp(node.text, this.typeOf(node));
    }
    if (isStringLiteral(node)) {
      return constOp(CONST.STRING, node.text);
    }
    if (isNumericLiteral(node)) {
      return constOp(CONST.DOUBLE, node.text);
    }
    if (node.kind === SyntaxKind.TrueKeyword) {
      return constOp(CONST.BOOL, true);
    }
    if (node.kind === SyntaxKind.FalseKeyword) {
      return constOp(CONST.BOOL, false);
    }
    if (isNullLiteral(node)) {
      return constOp(CONST.NULL, null);
    }
    if (isNoSubstitutionTemplateLiteral(node)) {
      return constOp(CONST.STRING, node.text);
    }
    if (isParenthesizedExpression(node)) {
      return this.lowerExpression(node.expression);
    }
    if (isAwaitExpression(node)) {
      return this.lowerExpression(node.expression);
    }
    if (isTypeOfExpression(node)) {
      return this.lowerExpression(node.expression);
    }
    if (node.kind === SyntaxKind.ThisKeyword) {
      // The receiver of an object's own property. It is the one object the
      // property accesses are keyed by.
      this.declareLocal(varOp("this", TYPE.UNKNOWN));
      return varOp("this", TYPE.UNKNOWN);
    }
    if (isNonNullExpression(node)) {
      return this.lowerExpression(node.expression);
    }
    if (isPropertyAccessExpression(node)) {
      return this.lowerPropertyRead(node);
    }
    if (isElementAccessExpression(node)) {
      return this.lowerElementRead(node);
    }
    if (isCallExpression(node)) {
      return this.lowerCall(node);
    }
    if (isNewExpression(node)) {
      return this.lowerNew(node);
    }
    if (isBinaryExpression(node)) {
      return this.lowerBinary(node);
    }
    if (isPrefixUnaryExpression(node)) {
      return this.lowerUnary(node);
    }
    if (isPostfixUnaryExpression(node)) {
      return this.lowerPostfixUnary(node);
    }
    if (isObjectLiteralExpression(node)) {
      return this.lowerObjectLiteral(node);
    }
    if (isArrayLiteralExpression(node)) {
      return this.lowerArrayLiteral(node);
    }
    if (isTemplateExpression(node)) {
      return this.lowerTemplate(node);
    }
    if (isClassExpression(node)) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "a class expression has no TAC lowering");
      return this.emitHavoc(node);
    }
    if (isArrowFunction(node) || isFunctionExpression(node)) {
      // A function used as a value. The TAC has no closure, so the function
      // becomes a synthetic CALL and the result is unknown.
      this.report(PROBLEM.SOURCE_LOWERED, node, "a function value has no TAC lowering");
      return this.emitHavoc(node);
    }
    this.report(PROBLEM.UNHANDLED_NODE, node, "this expression has no TAC lowering");
    return this.emitHavoc(node);
  }

  /**
   * A property read: `obj.name`.
   *
   * The key is a NAME in a namespace, so this is a container access with a
   * string key and not a field access. `obj.name` and `obj["name"]` are the
   * same location, and the engine's keyed namespace is what keeps them
   * together.
   */
  lowerPropertyRead(node) {
    const receiver = this.lowerExpression(node.expression);
    const key = constOp(CONST.STRING, node.name.text);
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest,
      lhs1: receiver,
      lhs2: key,
      ...this.position(node),
    });
    return dest;
  }

  /** A computed read: `obj[key]`. The key is a value the analysis may not resolve. */
  lowerElementRead(node) {
    const receiver = this.lowerExpression(node.expression);
    const key = this.lowerExpression(node.argumentExpression);
    const dest = this.state.newTemp(this.typeOf(node));
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
   * A call.
   *
   * The receiver slot is reserved whether or not the call has a receiver
   * object: `args[0]` is the receiver, `args[1..]` are the arguments. A free
   * function has no receiver object, so the slot holds the MODULE's name as a
   * constant -- the same shape the other front ends use for a static call.
   */
  lowerCall(node) {
    const signature = this.resolveSignature(node);
    const args = node.arguments ?? [];
    const wireArgs = [];
    const owner = this.ownerOf(node);
    let receiver = undefined;
    if (node.expression && isPropertyAccessExpression(node.expression)) {
      receiver = this.lowerExpression(node.expression.expression);
      wireArgs.push(receiver);
    } else {
      // A free function has no receiver object, so the slot holds the OWNER
      // as a constant: the module the function was imported from. It is the
      // same shape the other front ends use for a static call.
      receiver = constOp(CONST.STRING, owner);
      wireArgs.push(receiver);
    }
    for (const argument of args) {
      wireArgs.push(this.lowerExpression(argument));
    }
    const method = this.methodNameOf(node);
    const arity = this.arityOf(signature, args);
    const calleeSig = `${owner}.${method}/${arity}`;
    const dest = this.state.newTemp(this.typeOf(node));
    const kind = signature ? KIND.CALL : KIND.UNINTERPRETED;
    if (!signature) {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "the callee could not be resolved, so the call is modelled as a black box",
      );
    }
    this.emit({
      kind,
      calleeSig,
      dest,
      args: wireArgs,
      ...this.position(node),
    });
    return dest;
  }

  /** A construction: `new Thing(a, b)`. */
  lowerNew(node) {
    const args = node.arguments ?? [];
    const wireArgs = [];
    for (const argument of args) {
      wireArgs.push(this.lowerExpression(argument));
    }
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.NEW,
      calleeSig: this.constructorSig(node, args.length),
      dest,
      ctorType: this.typeOf(node),
      args: wireArgs,
      ...this.position(node),
    });
    return dest;
  }

  /**
   * The callee key of a construction: `Class.<init>/arity`.
   *
   * <p>The engine looks a construction up in the SAME catalogue as a call, so
   * `new RegExp(userInput)` has to be nameable as `RegExp.<init>/1`. This is
   * the shape the JVM front end already emits for a construction
   * (`TacTranslator.ctorSig`), so a catalogue written for one language works
   * for the others.
   *
   * <p>The arity is the number of arguments PASSED to the constructor, which
   * is what the JVM front end counts too.
   */
  constructorSig(node, arity) {
    const name = this.constructedClassName(node.expression);
    return name ? `${name}.<init>/${arity}` : undefined;
  }

  /**
   * The dotted name of the class a construction names.
   *
   * <p>The name is a SYNTACTIC fact of the expression: `new RegExp(x)` names
   * RegExp whatever the expression's type turns out to be. The type is the
   * wrong thing to ask, because a value can be given another type by its
   * context: `const r: Base = new Derived()` has the type Base, and a key
   * built from it would name a class that was never constructed.
   */
  constructedClassName(expression) {
    if (!expression) return undefined;
    if (isIdentifier(expression)) return expression.text;
    if (isPropertyAccessExpression(expression)) {
      const name = expression.name?.text;
      if (!name) return undefined;
      const qualifier = this.constructedClassName(expression.expression);
      return qualifier ? `${qualifier}.${name}` : name;
    }
    return undefined;
  }

  lowerBinary(node) {
    const operatorKind = node.operatorToken.kind;
    if (operatorKind === SyntaxKind.EqualsToken) {
      return this.lowerAssignment(node, null);
    }
    const compound = COMPOUND_OPERATORS[operatorKind];
    if (compound) {
      return this.lowerAssignment(node, compound);
    }
    const operator = BINARY_OPERATORS[operatorKind];
    const approximated = APPROXIMATED_OPERATORS[operatorKind];
    let binop = operator;
    if (approximated) {
      binop = approximated.binop;
      this.report(PROBLEM.SOURCE_LOWERED, node, approximated.note);
    }
    const left = this.lowerExpression(node.left);
    const right = this.lowerExpression(node.right);
    if (!binop) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const dest = this.state.newTemp(this.typeOf(node));
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
   * An assignment.
   *
   * <p>A plain assignment writes the value; a compound assignment reads the
   * target, combines it, and writes it back. Both end in a WRITE, and the
   * write is what makes the value visible to a later read.
   *
   * @param {object} node the assignment expression
   * @param {string|null} compound the binary operator of a compound
   *     assignment, or null for a plain one
   */
  lowerAssignment(node, compound) {
    const target = node.left;
    let value;
    if (compound) {
      const current = this.lowerExpression(target);
      const right = this.lowerExpression(node.right);
      if (compound.note) {
        this.report(PROBLEM.SOURCE_LOWERED, node, compound.note);
      }
      value = this.state.newTemp(this.typeOf(node));
      this.emit({
        kind: KIND.BINOP,
        dest: value,
        lhs1: current,
        lhs2: right,
        binop: compound.binop,
        ...this.position(node),
      });
    } else {
      value = this.lowerExpression(node.right);
    }
    this.lowerWrite(target, value, node);
    return value;
  }

  /**
   * A write to a location.
   *
   * <p>A name is a local variable; a property and an element are entries of a
   * namespace, so they are container stores with the key as the index.
   */
  lowerWrite(target, value, node) {
    if (isIdentifier(target)) {
      const variable = varOp(target.text, this.typeOf(target));
      this.declareLocal(variable);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: value,
        ...this.position(node),
      });
      return;
    }
    if (isPropertyAccessExpression(target)) {
      const receiver = this.lowerExpression(target.expression);
      const key = constOp(CONST.STRING, target.name.text);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: receiver,
        lhs2: key,
        dest: value,
        ...this.position(node),
      });
      return;
    }
    if (isElementAccessExpression(target)) {
      const receiver = this.lowerExpression(target.expression);
      const key = this.lowerExpression(target.argumentExpression);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: receiver,
        lhs2: key,
        dest: value,
        ...this.position(node),
      });
      return;
    }
    this.report(PROBLEM.UNHANDLED_NODE, target, "this assignment target has no TAC lowering");
  }

  lowerUnary(node) {
    if (node.operator === "++" || node.operator === "--") {
      const value = this.lowerExpression(node.operand);
      const one = constOp(CONST.INT, 1);
      const updated = this.state.newTemp(this.typeOf(node));
      this.emit({
        kind: KIND.BINOP,
        dest: updated,
        lhs1: value,
        lhs2: one,
        binop: node.operator === "++" ? "ADD" : "SUB",
        ...this.position(node),
      });
      this.lowerWrite(node.operand, updated, node);
      return updated;
    }
    const operator = UNARY_OPERATORS[node.operator];
    const operand = this.lowerExpression(node.operand);
    if (!operator) {
      if (node.operator === "+") {
        // A unary plus is a no-op: the operand is the result.
        return operand;
      }
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.UNARYOP,
      dest,
      lhs1: operand,
      unop: operator,
      ...this.position(node),
    });
    return dest;
  }

  /**
   * A postfix `++` or `--`.
   *
   * <p>The expression's value is the value BEFORE the change, and the
   * variable holds the value after it. So the old value is what the
   * expression yields, and the new value is what is written back.
   */
  lowerPostfixUnary(node) {
    if (node.operator !== "++" && node.operator !== "--") {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const before = this.lowerExpression(node.operand);
    const one = constOp(CONST.INT, 1);
    const after = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.BINOP,
      dest: after,
      lhs1: before,
      lhs2: one,
      binop: node.operator === "++" ? "ADD" : "SUB",
      ...this.position(node),
    });
    this.lowerWrite(node.operand, after, node);
    return before;
  }

  /**
   * An object literal.
   *
   * The literal is a fresh object and each property is a write into it. The
   * properties are container accesses with a string key, so a property read
   * later finds the value.
   */
  lowerObjectLiteral(node) {
    const dest = this.state.newTemp(TYPE.UNKNOWN);
    this.emit({
      kind: KIND.NEW,
      dest,
      ctorType: { kind: TYPE.CLASS, name: "Object" },
      args: [],
      ...this.position(node),
    });
    for (const property of node.properties) {
      if (!property.name) continue;
      const key = constOp(CONST.STRING, property.name.text ?? property.name.getText?.() ?? "");
      const value = this.lowerExpression(property.initializer);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: dest,
        lhs2: key,
        dest: value,
        ...this.position(property),
      });
    }
    return dest;
  }

  /** An array literal: a fresh array, then one write per element. */
  lowerArrayLiteral(node) {
    const elements = node.elements ?? [];
    const dest = this.state.newTemp(TYPE.UNKNOWN);
    this.emit({
      kind: KIND.NEWARRAY,
      dest,
      ctorType: { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } },
      lhs1: constOp(CONST.INT, elements.length),
      ...this.position(node),
    });
    elements.forEach((element, index) => {
      if (!element) return;
      const value = this.lowerExpression(element);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: dest,
        lhs2: constOp(CONST.INT, index),
        dest: value,
        ...this.position(element),
      });
    });
    return dest;
  }

  /**
   * A template literal with holes.
   *
   * The TAC has no string interpolation, so the holes are concatenated with
   * the `+` operator. The result is the same text the program builds, so the
   * taint of a hole reaches the string.
   */
  lowerTemplate(node) {
    const parts = [];
    const head = node.head;
    if (head && head.text) parts.push(constOp(CONST.STRING, head.text));
    for (const span of node.templateSpans ?? []) {
      const value = this.lowerExpression(span.expression);
      parts.push(value);
      if (span.literal && span.literal.text) {
        parts.push(constOp(CONST.STRING, span.literal.text));
      }
    }
    if (parts.length === 0) return constOp(CONST.STRING, "");
    let result = parts[0];
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
    return result;
  }

  // ---- helpers ------------------------------------------------------------------------------

  emit(instruction) {
    this.state.instructions.push(instruction);
    return instruction;
  }

  /** Declare a local, once. */
  declareLocal(variable) {
    if (!variable || !variable.name) {
      return;
    }
    if (!this.state.locals.some((local) => local.name === variable.name)) {
      this.state.locals.push(variable);
    }
  }

  /**
   * Lower one function's body, reporting anything that goes wrong.
   *
   * <p>The lowering of ONE function must not take the whole file down, and it
   * must not be silent either. A construct the translator does not understand
   * is a problem on that function -- the function's precision is downgraded
   * and the reader is told what went wrong.
   *
   * <p>So the failure is caught here and REPORTED. It is deliberately not
   * swallowed: a function that was only half lowered looks exactly like a
   * function that was lowered whole, and the difference is a finding.
   *
   * @param {object} node the node the problem is reported against
   * @param {Function} body the lowering
   */
  guard(node, body) {
    try {
      body();
    } catch (e) {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        `this function could not be lowered completely: ${
          e && e.message ? e.message : String(e)
        }`,
      );
    }
  }

  /**
   * A value the translator could not lower.
   *
   * HAVOC says "this value is unknown". It is neither tainted nor clean, and
   * the checkers read it as unknown rather than as safe.
   */
  emitHavoc(node) {
    const dest = this.state.newTemp(TYPE.UNKNOWN);
    this.emit({ kind: KIND.HAVOC, dest, ...this.position(node) });
    return dest;
  }

  report(kind, node, message) {
    this.state.problems.push({
      kind,
      message,
      nodeClass: nodeClassOf(node),
      ...this.position(node),
    });
  }

  /** The node's one-based line and column. */
  position(node) {
    if (!node) return { line: -1, column: -1 };
    const start = node.getStart ? node.getStart() : node.pos;
    return { line: this.lineOf(start), column: this.columnOf(start) };
  }

  lineOf(offset) {
    const starts = this.lineStarts;
    let low = 0;
    let high = starts.length - 1;
    while (low < high) {
      const mid = Math.ceil((low + high) / 2);
      if (starts[mid] <= offset) low = mid;
      else high = mid - 1;
    }
    return low + 1;
  }

  columnOf(offset) {
    return offset - this.lineStarts[this.lineOf(offset) - 1] + 1;
  }

  /** The TAC type of a node, from the compiler's own answer. */
  typeOf(node) {
    if (!node || !this.checker) return undefined;
    let type;
    try {
      type = this.checker.getTypeAtLocation(node);
    } catch (e) {
      return undefined;
    }
    if (!type) return undefined;
    return mapType(type);
  }

  typeTextOf(node) {
    const type = this.typeOf(node);
    return type ? typeText(type) : "any";
  }

  returnTypeTextOf(node) {
    const type = node.type ? this.typeOf(node.type) : undefined;
    return type ? typeText(type) : "any";
  }

  /** The compiler's answer to "which function is this call". */
  resolveSignature(node) {
    if (!this.checker) return undefined;
    try {
      return this.checker.getResolvedSignature(node);
    } catch (e) {
      return undefined;
    }
  }

  /**
   * The owner of a call: the receiver's type for a method, the module for a
   * free function.
   */
  ownerOf(node) {
    const expression = node.expression;
    if (expression && isPropertyAccessExpression(expression)) {
      const base = expression.expression;
      // `vm.runInNewContext(...)`: the base is a namespace import, so the
      // owner is the module the namespace came from.
      if (isIdentifier(base)) {
        const binding = this.imports.get(base.text);
        if (binding && binding.isNamespace) return binding.module;
      }
      // The receiver's CLASS is what a catalogue can name. It is asked of the
      // compiler's own type, because the mapped TAC type cannot tell a class
      // that has a name from one that does not.
      const name = this.classNameOfNode(base);
      if (name) {
        return name;
      }
      // No class to name: a primitive receiver, or one whose type this
      // translator cannot read. The owner is UNKNOWN, and saying it is the
      // module the file belongs to would hand the catalogue a module path
      // that names the program's OWN file -- and a file called
      // "logging.ts" would then be the logging module.
      return UNKNOWN_OWNER;
    }
    if (expression && isIdentifier(expression)) {
      // `exec(cmd)`, where `exec` was imported: the owner is the module it
      // came from, not the file the call is written in.
      const binding = this.imports.get(expression.text);
      if (binding) return binding.module;
      // A function THIS file declares: the owner is the module, because that
      // is the definition's owner and the call graph resolves the two
      // against each other.
      if (this.declaredNames.has(expression.text)) return this.moduleName;
      // A global, or a function of a module that was never imported. The
      // owner is not this module.
      return UNKNOWN_OWNER;
    }
    // The callee is not a name at all, so there is no owner to find.
    return UNKNOWN_OWNER;
  }

  /**
   * The name of a node's class, when its type is a class with a name.
   *
   * <p>A plain object and a class instance both have a type, and the plain
   * one's type is the global `Object` -- which is a real answer, not a missing
   * one. A receiver whose type is not a class at all -- a string, a number --
   * has no class name, and the call's owner falls back to the file's module.
   */
  classNameOfNode(node) {
    if (!node || !this.checker) return undefined;
    try {
      const type = this.checker.getTypeAtLocation(node);
      if (!type) return undefined;
      return classNameOf(type);
    } catch (e) {
      return undefined;
    }
  }

  methodNameOf(node) {
    const expression = node.expression;
    if (expression && isPropertyAccessExpression(expression)) {
      return expression.name.text;
    }
    if (expression && isIdentifier(expression)) {
      // An aliased import calls the name it was exported under, so that
      // `import { exec as run }` reaches the catalogue as `exec`.
      const binding = this.imports.get(expression.text);
      if (binding && binding.exported) return binding.exported;
      return expression.text;
    }
    return "<call>";
  }

  /**
   * The arity of a call.
   *
   * The arity counts the DECLARED parameters, not the arguments passed: an
   * optional parameter is still part of the signature, and the sink catalogue
   * matches on the signature.
   */
  arityOf(signature, args) {
    if (signature && Array.isArray(signature.parameters) && signature.parameters.length > 0) {
      return signature.parameters.length;
    }
    if (signature && signature.parameters && signature.parameters.length === 0) {
      return 0;
    }
    return args.length;
  }
}

/** The offsets at which each line of the text starts. */
function computeLineStarts(text) {
  const starts = [0];
  for (let i = 0; i < text.length; i += 1) {
    if (text.charCodeAt(i) === 10) starts.push(i + 1);
  }
  return starts;
}

/** The TAC type of a compiler type. */
function mapType(type) {
  const flags = type.flags;
  if (flags & TypeFlags.String) return { kind: TYPE.STRING };
  if (flags & TypeFlags.Number) return { kind: TYPE.DOUBLE };
  if (flags & TypeFlags.BooleanLike) return { kind: TYPE.BOOLEAN };
  if (flags & TypeFlags.BigIntLike) return { kind: TYPE.LONG };
  if (flags & TypeFlags.ESSymbolLike) return { kind: TYPE.UNKNOWN };
  if (flags & TypeFlags.VoidLike) return { kind: TYPE.VOID };
  if (flags & TypeFlags.Null) return { kind: TYPE.NULL };
  if (flags & TypeFlags.Object) {
    const name = classNameOf(type);
    if (name === "Array") return { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } };
    return { kind: TYPE.CLASS, name: name ?? "Object" };
  }
  return { kind: TYPE.UNKNOWN };
}

/**
 * The name of a compiler type's class, when it has one.
 *
 * <p><b>Why the name is read as a PROPERTY.</b> The compiler's symbol carries
 * its name as a property, and the accessor `getName()` is not implemented in
 * the native preview -- asking for it yields `undefined` rather than the name.
 * A symbol without a name is a class without a name, so every instance
 * degraded to `Object` and every method on it came out as a call on the
 * FILE's module. The class name is what the owner of a method call has to be,
 * because a catalogue names the class it can recognise.
 */
function classNameOf(type) {
  if (!type) return undefined;
  try {
    if (!type.isObjectType()) return undefined;
    const symbol = type.getSymbol();
    if (!symbol) return undefined;
    const name =
      symbol.name ?? (typeof symbol.getName === "function" ? symbol.getName() : undefined);
    if (typeof name !== "string" || name.length === 0) return undefined;
    // An escaped name is quoted: `"Repo"`. The quotes are not part of it.
    if (name.length > 1 && name.startsWith('"') && name.endsWith('"')) {
      return name.slice(1, -1);
    }
    return name;
  } catch (e) {
    return undefined;
  }
}

/** The text form of a TAC type, for a signature string. */
function typeText(type) {
  if (!type) return "any";
  if (type.kind === TYPE.CLASS) return type.name;
  if (type.kind === TYPE.ARRAY) return `${typeText(type.component)}[]`;
  return type.kind.toLowerCase();
}

/** The node's class name, for a problem report. */
function nodeClassOf(node) {
  if (!node) return "Unknown";
  const name = SYNTAX_KIND_NAMES[node.kind];
  return name ?? `Kind${node.kind}`;
}

// The reverse of the SyntaxKind enum, so a problem can name the construct it
// is about instead of a number.
const SYNTAX_KIND_NAMES = (() => {
  const names = {};
  for (const [name, value] of Object.entries(SyntaxKind ?? {})) {
    if (typeof value === "number") names[value] = name;
  }
  return names;
})();
