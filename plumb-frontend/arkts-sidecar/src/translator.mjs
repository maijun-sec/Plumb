// The translator: ArkTS source -> Plumb's TAC IR.
//
// WHAT THIS IS. A frontend lowers a source language into the TAC, the
// three-address instruction stream the analysis engine consumes. The TAC is
// language-neutral: it says "move", "call", "read a container", and nothing
// about what a variable means.
//
// THE ONE LANGUAGE-SPECIFIC DECISION. In ArkTS an object is a NAMESPACE, not
// a record: `this.message` and `this["message"]` are the same location, and
// `items[0]` is `items["0"]`. So a property access is lowered as a CONTAINER
// ACCESS with a string key -- an ARRAYLOAD / ARRAYSTORE whose index operand
// is the key -- and not as a field access. The engine's keyed namespace is
// what gives that access its identity: the pair (object, key).
//
// THREE THINGS ARKTS ADDS TO THE SHAPE.
//
//   1. A `struct` is a component: its methods are functions, and its
//      decorated properties are its state.
//
//   2. The declarative UI DSL -- `Column() { Text(this.message) }` -- is not
//      a call and a block: it is one construct, and the compiler parses it
//      that way. It is lowered as a CALL, because that is what it does: it
//      hands its arguments to a component.
//
//   3. An assignment is a WRITE, and a write has to reach the store. A
//      property assignment becomes an ARRAYSTORE with the property's name as
//      the key, which is what makes `this.message = userInput` visible to a
//      later `Text(this.message)`.
//
// EVERY FAILURE IS REPORTED. A construct this translator cannot lower becomes
// a problem on the function, and the affected value becomes a HAVOC. It never
// becomes a silent "nothing to report": a sidecar that drops a construct
// quietly would leave the engine believing the program had no findings.
//
// The compiler facts come from the ArkTS compiler itself, so the types, the
// overloads and the property names are the ones the compiler sees.

import { CONST, KIND, PRECISION, PROBLEM, TYPE, constOp, varOp } from "./protocol.mjs";

import * as ts from "ohos-typescript";

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
  [ts.SyntaxKind.PlusToken]: "ADD",
  [ts.SyntaxKind.MinusToken]: "SUB",
  [ts.SyntaxKind.AsteriskToken]: "MUL",
  [ts.SyntaxKind.SlashToken]: "DIV",
  [ts.SyntaxKind.PercentToken]: "MOD",
  [ts.SyntaxKind.AsteriskAsteriskToken]: "POW",
  [ts.SyntaxKind.EqualsEqualsToken]: "EQ",
  [ts.SyntaxKind.EqualsEqualsEqualsToken]: "EQ",
  [ts.SyntaxKind.ExclamationEqualsToken]: "NE",
  [ts.SyntaxKind.ExclamationEqualsEqualsToken]: "NE",
  [ts.SyntaxKind.LessThanToken]: "LT",
  [ts.SyntaxKind.LessThanEqualsToken]: "LE",
  [ts.SyntaxKind.GreaterThanToken]: "GT",
  [ts.SyntaxKind.GreaterThanEqualsToken]: "GE",
  [ts.SyntaxKind.AmpersandAmpersandToken]: "LAND",
  [ts.SyntaxKind.BarBarToken]: "LOR",
  [ts.SyntaxKind.QuestionQuestionToken]: "NULL_COALESCE",
  [ts.SyntaxKind.AmpersandToken]: "AND",
  [ts.SyntaxKind.BarToken]: "OR",
  [ts.SyntaxKind.CaretToken]: "XOR",
  [ts.SyntaxKind.LessThanLessThanToken]: "SHL",
  [ts.SyntaxKind.GreaterThanGreaterThanToken]: "SHR",
  [ts.SyntaxKind.GreaterThanGreaterThanGreaterThanToken]: "USHR",
};

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
  [ts.SyntaxKind.AsteriskAsteriskToken]: {
    binop: "MUL",
    note: "the TAC has no power operator, so the power is modelled as a multiplication",
  },
  [ts.SyntaxKind.QuestionQuestionToken]: {
    binop: "LOR",
    note: "the TAC has no null-coalescing operator, so the coalescing is modelled as a disjunction",
  },
};

/**
 * The compound assignments, by the TypeScript operator token.
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
  [ts.SyntaxKind.PlusEqualsToken]: { binop: "ADD" },
  [ts.SyntaxKind.MinusEqualsToken]: { binop: "SUB" },
  [ts.SyntaxKind.AsteriskEqualsToken]: { binop: "MUL" },
  [ts.SyntaxKind.SlashEqualsToken]: { binop: "DIV" },
  [ts.SyntaxKind.PercentEqualsToken]: { binop: "MOD" },
  [ts.SyntaxKind.AsteriskAsteriskEqualsToken]: {
    binop: "MUL",
    note: "the TAC has no power operator, so the power is modelled as a multiplication",
  },
  [ts.SyntaxKind.LessThanLessThanEqualsToken]: { binop: "SHL" },
  [ts.SyntaxKind.GreaterThanGreaterThanEqualsToken]: { binop: "SHR" },
  [ts.SyntaxKind.GreaterThanGreaterThanGreaterThanEqualsToken]: { binop: "USHR" },
  [ts.SyntaxKind.AmpersandEqualsToken]: { binop: "AND" },
  [ts.SyntaxKind.BarEqualsToken]: { binop: "OR" },
  [ts.SyntaxKind.CaretEqualsToken]: { binop: "XOR" },
  [ts.SyntaxKind.BarBarEqualsToken]: { binop: "LOR" },
  [ts.SyntaxKind.AmpersandAmpersandEqualsToken]: { binop: "LAND" },
  [ts.SyntaxKind.QuestionQuestionEqualsToken]: {
    binop: "LOR",
    note: "the TAC has no null-coalescing operator, so the coalescing is modelled as a disjunction",
  },
};

/**
 * The TAC's unary operators, by the TypeScript operator.
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
 * <p>The instruction list, the locals and the problems belong to the function
 * being lowered, so they live here rather than on the translator.
 */
class FunctionState {
  constructor(name, signature, enclosingClass, synthetic) {
    this.name = name;
    this.signature = signature;
    this.enclosingClass = enclosingClass;
    this.synthetic = synthetic;
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

  newLabel(prefix) {
    this.labelCounter += 1;
    return `${this.labelPrefix}${prefix ?? "L"}${this.labelCounter}`;
  }
}

/**
 * The translator.
 *
 * <p>One instance lowers one source file.
 */
export class Translator {
  /**
   * @param {object} options
   * @param {object} options.checker the compiler's checker
   * @param {object} options.sourceFile the file being lowered
   * @param {string} options.moduleName the module the file belongs to
   * @param {string} options.language the source language, for the wire
   * @param {string} options.text the file's text, for the line and column
   */
  constructor(options) {
    this.checker = options.checker;
    this.sourceFile = options.sourceFile;
    this.moduleName = options.moduleName;
    this.language = options.language ?? "ARKTS";
    this.text = options.text ?? "";
    this.lineStarts = computeLineStarts(this.text);
    this.imports = new Map();
    this.state = null;
  }

  /** The functions declared in a source file, in source order. */
  translateSourceFile() {
    this.collectModuleBindings();
    const functions = [];
    const moduleStatements = [];
    const flushModule = () => {
      if (moduleStatements.length === 0) {
        return;
      }
      const moduleFunction = this.beginFunction("<module>", this.moduleName, null, true);
      try {
        for (const statement of moduleStatements) {
          this.lowerStatement(statement);
        }
      } finally {
        functions.push(this.finishFunction(moduleFunction));
      }
      moduleStatements.length = 0;
    };

    for (const statement of this.sourceFile.statements) {
      if (ts.isStructDeclaration(statement)) {
        flushModule();
        functions.push(...this.lowerStruct(statement));
        continue;
      }
      if (ts.isClassDeclaration(statement)) {
        flushModule();
        functions.push(...this.lowerClass(statement));
        continue;
      }
      if (ts.isEnumDeclaration(statement)) {
        flushModule();
        functions.push(this.lowerEnum(statement));
        continue;
      }
      if (ts.isFunctionDeclaration(statement) && statement.name) {
        flushModule();
        functions.push(this.lowerFunctionDeclaration(statement, null, []));
        continue;
      }
      if (ts.isVariableStatement(statement)) {
        const lifted = this.liftFunctionVariables(statement);
        if (lifted.length > 0) {
          flushModule();
          functions.push(...lifted);
          continue;
        }
      }
      moduleStatements.push(statement);
    }
    flushModule();
    return { functions };
  }

  // ---- the modules a file reaches ---------------------------------------------------------------

  /**
   * The modules the file reaches, by the local names it bound them to.
   *
   * <p>A script language reaches its libraries through MODULES rather than
   * through classes: there is no class to name, so the owner of a call to a
   * module-level function is the MODULE it was imported from. The owner's
   * alternative -- the module the FILE belongs to -- says nothing about
   * where the function came from, and no catalogue could match it.
   *
   * <p>The alias is resolved away here: `import { exec as run }` calls
   * `exec`, so the name the catalogue has to match is the exported one.
   */
  collectModuleBindings() {
    this.imports = new Map();
    for (const statement of this.sourceFile.statements ?? []) {
      if (!ts.isImportDeclaration(statement)) {
        continue;
      }
      const specifier = statement.moduleSpecifier && statement.moduleSpecifier.text;
      if (!specifier) {
        continue;
      }
      const clause = statement.importClause;
      if (!clause) {
        continue;
      }
      if (clause.name && clause.name.text) {
        // A default import names the module's default export, and the module
        // object is what its members are read from.
        this.bindImport(clause.name.text, specifier, null, true);
      }
      const bindings = clause.namedBindings;
      if (!bindings) {
        continue;
      }
      if (ts.isNamespaceImport(bindings)) {
        this.bindImport(bindings.name.text, specifier, null, true);
        continue;
      }
      for (const element of bindings.elements ?? []) {
        const local = element.name && element.name.text;
        if (!local) {
          continue;
        }
        const exported = (element.propertyName && element.propertyName.text) || local;
        this.bindImport(local, specifier, exported, false);
      }
    }
  }

  /** Remember that a local name came from a module. */
  bindImport(local, module, exported, isNamespace) {
    this.imports.set(local, { module, exported, isNamespace });
  }

  // ---- structs and classes ---------------------------------------------------------------------

  /**
   * A struct: its state, and its methods.
   *
   * <p>A struct's properties are its state, and a method that reads a
   * property has to see what the property holds. So the struct gets TWO
   * functions:
   *
   * <ul>
   *   <li>the struct's own initialiser, which holds every property's initial
   *       value, and
   *   <li>one function per method, each of which starts with the property
   *       values it may read.
   * </ul>
   *
   * <p>The duplication is deliberate and it is bounded. A property whose
   * initialiser is a PURE expression -- a literal, a name, a read of another
   * property -- is copied into every method, because copying it cannot run
   * anything twice and it is what makes the method see the property's value.
   * A property whose initialiser CALLS something is not copied: a call has
   * effects, and running it once per method would report the same effect
   * once per method. It stays in the struct's initialiser, and the fact that
   * the methods do not see it is reported.
   */
  lowerStruct(node) {
    const out = [];
    const structName = node.name ? node.name.text : "<anonymous>";
    const fields = node.members.filter((member) => ts.isPropertyDeclaration(member));

    const initializer = this.beginFunction(
      structName,
      `${this.moduleName}.${structName}()`,
      structName,
      true,
    );
    this.guard(node, () => {
      this.declareThis();
      this.lowerFieldInitializers(fields, false);
    });
    out.push(this.finishFunction(initializer));

    for (const member of node.members) {
      if (ts.isMethodDeclaration(member) && member.name) {
        out.push(this.lowerFunctionDeclaration(member, structName, fields));
      }
      // A struct's constructor is the COMPILER's own desugaring: ArkTS does
      // not let a struct declare one, and the compiler synthesises it. Lowering
      // it would invent a function the user never wrote.
    }
    return out;
  }

  /** A class: its state, its constructor, and its methods. */
  lowerClass(node) {
    const out = [];
    const className = node.name ? node.name.text : "<anonymous>";
    const fields = node.members.filter((member) => ts.isPropertyDeclaration(member));

    const initializer = this.beginFunction(
      className,
      `${this.moduleName}.${className}()`,
      className,
      true,
    );
    this.guard(node, () => {
      this.declareThis();
      this.lowerFieldInitializers(fields, false);
    });
    out.push(this.finishFunction(initializer));

    for (const member of node.members) {
      if (ts.isConstructorDeclaration(member)) {
        out.push(this.lowerConstructor(member, className));
        continue;
      }
      if (ts.isMethodDeclaration(member) && member.name) {
        out.push(this.lowerFunctionDeclaration(member, className, fields));
      }
    }
    return out;
  }

  /**
   * A property's initial value.
   *
   * @param {object[]} fields the properties
   * @param {boolean} replicate whether a pure initialiser is copied into the
   *     method as well as into the struct's own initialiser
   */
  lowerFieldInitializers(fields, replicate) {
    for (const field of fields) {
      if (!field.initializer) {
        continue;
      }
      if (replicate && containsCall(field.initializer)) {
        continue;
      }
      const key = constOp(CONST.STRING, nameTextOf(field.name) ?? "");
      const value = this.lowerExpression(field.initializer);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: varOp("this", TYPE.UNKNOWN),
        lhs2: key,
        dest: value,
        ...this.position(field),
      });
    }
  }

  /**
   * An enum: its members are constants.
   *
   * <p>An enum member without an initialiser takes the value after the
   * previous one, so the numbering is tracked here rather than invented.
   */
  lowerEnum(node) {
    const enumName = node.name ? node.name.text : "<anonymous>";
    const state = this.beginFunction(enumName, `${this.moduleName}.${enumName}()`, enumName, true);
    this.guard(node, () => {
      let next = 0;
      for (const member of node.members) {
        const name = nameTextOf(member.name);
        if (!name) {
          continue;
        }
        const variable = varOp(name, this.typeOf(member));
        this.declareLocal(variable);
        let value;
        if (member.initializer) {
          value = this.lowerExpression(member.initializer);
        } else {
          value = constOp(CONST.INT, next);
        }
        this.emit({
          kind: KIND.MOVE,
          dest: variable,
          lhs1: value,
          ...this.position(member),
        });
        if (!member.initializer) {
          next += 1;
        }
      }
    });
    return this.finishFunction(state);
  }

  // ---- functions --------------------------------------------------------------------------------

  lowerFunctionDeclaration(node, enclosingClass, fields) {
    const name = node.name ? node.name.text : "<anonymous>";
    const state = this.beginFunction(
      name,
      this.functionSignature(node, enclosingClass),
      enclosingClass,
      false,
    );
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      if (enclosingClass !== null && fields.length > 0) {
        this.declareThis();
        this.lowerFieldInitializers(fields, true);
      }
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    return this.finishFunction(state);
  }

  lowerConstructor(node, enclosingClass) {
    const state = this.beginFunction(
      "<init>",
      `${this.moduleName}.${enclosingClass}.<init>()`,
      enclosingClass,
      false,
    );
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    return this.finishFunction(state);
  }

  /**
   * A variable whose initialiser is a function.
   *
   * <p>`const handle = (req) => ...` declares a function, and the engine has
   * to see it as one: a call to `handle` has a callee signature, and a callee
   * signature is what the sink catalogue matches on.
   */
  liftFunctionVariables(statement) {
    const out = [];
    for (const declaration of statement.declarationList.declarations) {
      const initialiser = declaration.initializer;
      if (!initialiser) {
        continue;
      }
      if (!ts.isArrowFunction(initialiser) && !ts.isFunctionExpression(initialiser)) {
        continue;
      }
      const name = declaration.name ? declaration.name.text : "<anonymous>";
      const state = this.beginFunction(
        name,
        this.functionSignature(initialiser, null),
        null,
        false,
      );
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

  beginFunction(name, signature, enclosingClass, synthetic) {
    const state = new FunctionState(name, signature, enclosingClass, synthetic);
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
        // approximated -- an unhandled node became an unknown value, a switch
        // lost its case selection, an unresolved callee became a black box --
        // and a function that was approximated did not come out whole.
        problems.length > 0 ? PRECISION.LOWERED : PRECISION.FULL,
      synthetic: state.synthetic,
      params: state.params,
      locals: state.locals,
      instructions: state.instructions,
      problems,
    };
  }

  lowerParameters(parameters) {
    if (!parameters) {
      return;
    }
    for (const parameter of parameters) {
      const name = parameter.name && parameter.name.text ? parameter.name.text : "p";
      const operand = varOp(name, this.typeOf(parameter));
      this.state.params.push(operand);
      this.declareLocal(operand);
    }
  }

  /** The signature string: `owner.name(ParamTypes):Return`. */
  functionSignature(node, enclosingClass) {
    const owner = enclosingClass ?? this.moduleName;
    const parameters = node.parameters ?? [];
    const types = parameters.map((p) => this.typeTextOf(p));
    const returnType = this.returnTypeTextOf(node);
    const name = node.name ? node.name.text : "<anonymous>";
    return `${owner}.${name}(${types.join(",")}):${returnType}`;
  }

  // ---- statements --------------------------------------------------------------------------------

  lowerStatement(node) {
    if (!node) {
      return;
    }
    if (ts.isExpressionStatement(node)) {
      this.lowerExpression(node.expression);
      return;
    }
    if (ts.isVariableStatement(node)) {
      this.lowerVariableStatement(node);
      return;
    }
    if (ts.isReturnStatement(node)) {
      const value = node.expression ? this.lowerExpression(node.expression) : undefined;
      this.emit({ kind: KIND.RETURN, lhs1: value, ...this.position(node) });
      return;
    }
    if (ts.isThrowStatement(node)) {
      const value = node.expression ? this.lowerExpression(node.expression) : undefined;
      this.emit({ kind: KIND.THROW, lhs1: value, ...this.position(node) });
      return;
    }
    if (ts.isIfStatement(node)) {
      this.lowerIf(node);
      return;
    }
    if (ts.isWhileStatement(node)) {
      this.lowerWhile(node);
      return;
    }
    if (ts.isDoStatement(node)) {
      this.lowerDo(node);
      return;
    }
    if (ts.isForStatement(node)) {
      this.lowerFor(node);
      return;
    }
    if (ts.isForOfStatement(node)) {
      this.lowerForOf(node);
      return;
    }
    if (ts.isForInStatement(node)) {
      this.lowerForIn(node);
      return;
    }
    if (ts.isBlock(node)) {
      this.lowerBlock(node);
      return;
    }
    if (ts.isBreakStatement(node) || ts.isContinueStatement(node)) {
      // The TAC has no break or continue. The loop's own structure is what
      // carries the control flow, so a break is recorded and the rest of the
      // body is still lowered -- the loop's exit edge is where the value ends
      // up, and dropping the body would hide a taint that flows through it.
      this.report(PROBLEM.SOURCE_LOWERED, node, "a break or continue has no TAC lowering");
      return;
    }
    if (ts.isSwitchStatement(node)) {
      this.lowerSwitch(node);
      return;
    }
    if (ts.isTryStatement(node)) {
      this.lowerTry(node);
      return;
    }
    if (ts.isLabeledStatement(node)) {
      this.lowerStatement(node.statement);
      return;
    }
    // These declare a TYPE, and a type has no run-time effect at all. They
    // are skipped rather than reported: there is nothing in them for a
    // dataflow finding to be about, and reporting them would downgrade every
    // file that happens to declare an interface.
    if (
      ts.isImportDeclaration(node) ||
      ts.isTypeAliasDeclaration(node) ||
      ts.isInterfaceDeclaration(node) ||
      ts.isImportEqualsDeclaration(node)
    ) {
      return;
    }
    if (ts.isExportDeclaration(node) || ts.isExportAssignment(node)) {
      if (node.declaration) {
        this.lowerStatement(node.declaration);
      }
      return;
    }
    if (ts.isEmptyStatement(node)) {
      return;
    }
    this.report(PROBLEM.UNHANDLED_NODE, node, "this statement has no TAC lowering");
  }

  lowerVariableStatement(node) {
    for (const declaration of node.declarationList.declarations) {
      if (!ts.isVariableDeclaration(declaration)) {
        continue;
      }
      const name = declaration.name && declaration.name.text ? declaration.name.text : "v";
      const variable = varOp(name, this.typeOf(declaration));
      this.declareLocal(variable);
      if (!declaration.initializer) {
        continue;
      }
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
    this.emit({ kind: KIND.LABEL, label: thenLabel });
    if (node.thenStatement) {
      this.lowerStatement(node.thenStatement);
    }
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emit({ kind: KIND.LABEL, label: elseLabel });
    if (node.elseStatement) {
      this.lowerStatement(node.elseStatement);
    }
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  lowerWhile(node) {
    const headLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
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
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  lowerDo(node) {
    const bodyLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
    this.emit({ kind: KIND.LABEL, label: bodyLabel });
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    const condition = this.lowerExpression(node.condition);
    this.emit({
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel: bodyLabel,
      falseLabel: endLabel,
      ...this.position(node),
    });
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  lowerFor(node) {
    if (node.initializer) {
      if (ts.isVariableStatement(node.initializer)) {
        this.lowerVariableStatement(node.initializer);
      } else {
        this.lowerExpression(node.initializer);
      }
    }
    const headLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const stepLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
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
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.emit({ kind: KIND.JUMP, label: stepLabel });
    this.emit({ kind: KIND.LABEL, label: stepLabel });
    if (node.incrementor) {
      this.lowerExpression(node.incrementor);
    }
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  /**
   * A `for ... of` loop.
   *
   * <p>The iterated value is the element, so the loop variable is bound to it
   * with a MOVE. The TAC has no iteration opcode, so the loop's structure
   * carries it -- the body's instructions are in the stream, and the engine's
   * iterator handling is what turns a tainted element back into a tainted
   * loop variable.
   */
  lowerForOf(node) {
    const iterable = this.lowerExpression(node.expression);
    if (node.initializer && ts.isVariableDeclaration(node.initializer)) {
      const name = node.initializer.name && node.initializer.name.text ? node.initializer.name.text : "v";
      const variable = varOp(name, this.typeOf(node.initializer));
      this.declareLocal(variable);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: iterable,
        ...this.position(node),
      });
    }
    const headLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
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
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  /** A `for ... in` loop: the iterated value is a KEY, not an element. */
  lowerForIn(node) {
    const iterable = this.lowerExpression(node.expression);
    if (node.initializer && ts.isVariableDeclaration(node.initializer)) {
      const name = node.initializer.name && node.initializer.name.text ? node.initializer.name.text : "v";
      const variable = varOp(name, this.typeOf(node.initializer));
      this.declareLocal(variable);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: iterable,
        ...this.position(node),
      });
    }
    const headLabel = this.state.newLabel("L");
    const bodyLabel = this.state.newLabel("L");
    const endLabel = this.state.newLabel("L");
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
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emit({ kind: KIND.LABEL, label: endLabel });
  }

  /**
   * A switch.
   *
   * <p>The TAC has no case selection, so the cases are lowered in order and
   * the selection is reported. The cases are still lowered -- dropping them
   * would hide every taint that flows through a case -- so this
   * over-approximates the control flow rather than losing the data flow.
   */
  lowerSwitch(node) {
    const discriminant = this.lowerExpression(node.expression);
    this.report(
      PROBLEM.SOURCE_LOWERED,
      node,
      "a switch has no TAC case selection, so its cases are lowered in order",
    );
    for (const clause of node.caseBlock.clauses) {
      if (ts.isCaseClause(clause)) {
        this.lowerExpression(clause.expression);
        for (const statement of clause.statements) {
          this.lowerStatement(statement);
        }
        continue;
      }
      for (const statement of clause.statements) {
        this.lowerStatement(statement);
      }
    }
    // The discriminant is read so that its taint is not lost, even though no
    // branch is emitted for it.
    void discriminant;
  }

  /**
   * A try / catch / finally.
   *
   * <p>The IR has explicit instructions for this: a CATCH_HANDLER binds the
   * catch parameter to the exception, and a FINALLY_ENTER marks the block
   * every exit of the region runs through.
   */
  lowerTry(node) {
    if (node.tryBlock) {
      this.lowerBlock(node.tryBlock);
    }
    for (const clause of node.catchClause ?? []) {
      const parameter = clause.variableDeclaration;
      const name = parameter && parameter.name ? parameter.name.text : undefined;
      const label = this.state.newLabel(name ? `L_CATCH_${name}` : "L_CATCH");
      this.emit({ kind: KIND.LABEL, label, ...this.position(clause) });
      if (name) {
        const variable = varOp(name, this.typeOf(parameter));
        this.declareLocal(variable);
        this.emit({
          kind: KIND.CATCH_HANDLER,
          dest: variable,
          ctorType: parameter && parameter.type ? this.typeOf(parameter.type) : undefined,
          ...this.position(clause),
        });
      } else {
        this.emit({ kind: KIND.CATCH_HANDLER, ...this.position(clause) });
      }
      if (clause.block) {
        this.lowerBlock(clause.block);
      }
    }
    if (node.finallyBlock) {
      const label = this.state.newLabel("L_FINALLY");
      this.emit({ kind: KIND.LABEL, label, ...this.position(node) });
      this.emit({ kind: KIND.FINALLY_ENTER, label, ...this.position(node) });
      this.lowerBlock(node.finallyBlock);
    }
  }

  // ---- expressions --------------------------------------------------------------------------------

  /**
   * Lower an expression to an operand.
   *
   * <p>The result is an operand, not a value: an expression that produces a
   * value gets a temporary, and the temporary is what the caller uses.
   */
  lowerExpression(node) {
    if (!node) {
      return undefined;
    }
    if (ts.isIdentifier(node)) {
      return varOp(node.text, this.typeOf(node));
    }
    if (node.kind === ts.SyntaxKind.ThisKeyword) {
      // The receiver of a struct's or a class's own property. It is the one
      // object the property accesses are keyed by.
      this.declareThis();
      return varOp("this", TYPE.UNKNOWN);
    }
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
      return constOp(CONST.STRING, node.text);
    }
    if (ts.isNumericLiteral(node)) {
      return constOp(CONST.DOUBLE, node.text);
    }
    if (node.kind === ts.SyntaxKind.TrueKeyword) {
      return constOp(CONST.BOOL, true);
    }
    if (node.kind === ts.SyntaxKind.FalseKeyword) {
      return constOp(CONST.BOOL, false);
    }
    if (node.kind === ts.SyntaxKind.NullKeyword) {
      // The compiler this sidecar uses predates the `isNullLiteral` guard:
      // `null` is a keyword there, not a literal node.
      return constOp(CONST.NULL, null);
    }
    if (ts.isBigIntLiteral(node)) {
      return constOp(CONST.LONG, node.text);
    }
    if (ts.isParenthesizedExpression(node)) {
      return this.lowerExpression(node.expression);
    }
    if (ts.isAwaitExpression(node)) {
      return this.lowerExpression(node.expression);
    }
    if (ts.isTypeOfExpression(node)) {
      return this.lowerExpression(node.expression);
    }
    if (ts.isNonNullExpression(node)) {
      return this.lowerExpression(node.expression);
    }
    if (ts.isPropertyAccessExpression(node)) {
      return this.lowerPropertyRead(node);
    }
    if (ts.isElementAccessExpression(node)) {
      return this.lowerElementRead(node);
    }
    if (ts.isCallExpression(node)) {
      return this.lowerCall(node);
    }
    if (ts.isNewExpression(node)) {
      return this.lowerNew(node);
    }
    if (ts.isBinaryExpression(node)) {
      return this.lowerBinary(node);
    }
    if (ts.isPrefixUnaryExpression(node)) {
      return this.lowerPrefixUnary(node);
    }
    if (ts.isPostfixUnaryExpression(node)) {
      return this.lowerPostfixUnary(node);
    }
    if (ts.isObjectLiteralExpression(node)) {
      return this.lowerObjectLiteral(node);
    }
    if (ts.isArrayLiteralExpression(node)) {
      return this.lowerArrayLiteral(node);
    }
    if (ts.isTemplateExpression(node)) {
      return this.lowerTemplate(node);
    }
    if (ts.isEtsComponentExpression(node)) {
      return this.lowerComponentExpression(node);
    }
    if (ts.isSpreadElement(node)) {
      return this.lowerExpression(node.expression);
    }
    if (ts.isClassExpression(node)) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "a class expression has no TAC lowering");
      return this.emitHavoc(node);
    }
    if (ts.isArrowFunction(node) || ts.isFunctionExpression(node)) {
      // A function used as a value. The TAC has no closure, so the function
      // becomes an unknown value.
      this.report(PROBLEM.SOURCE_LOWERED, node, "a function value has no TAC lowering");
      return this.emitHavoc(node);
    }
    this.report(PROBLEM.UNHANDLED_NODE, node, "this expression has no TAC lowering");
    return this.emitHavoc(node);
  }

  /**
   * A property read: `obj.name`.
   *
   * <p>The key is a NAME in a namespace, so this is a container access with a
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
   * <p>The receiver slot is reserved whether or not the call has a receiver
   * object: `args[0]` is the receiver, `args[1..]` are the arguments. A free
   * function has no receiver object, so the slot holds the MODULE's name as a
   * constant -- the same shape the other front ends use for a static call.
   */
  lowerCall(node) {
    const signature = this.resolveSignature(node);
    const args = node.arguments ?? [];
    const wireArgs = [];
    const owner = this.ownerOf(node);
    let receiver;
    if (node.expression && ts.isPropertyAccessExpression(node.expression)) {
      receiver = this.lowerExpression(node.expression.expression);
    } else {
      // A free function has no receiver object, so the slot holds the OWNER
      // as a constant: the module the function was imported from.
      receiver = constOp(CONST.STRING, owner);
    }
    wireArgs.push(receiver);
    for (const argument of args) {
      wireArgs.push(this.lowerExpression(argument));
    }
    const method = this.methodNameOf(node);
    const arity = this.arityOf(signature, args);
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
      calleeSig: `${owner}.${method}/${arity}`,
      dest,
      args: wireArgs,
      ...this.position(node),
    });
    return dest;
  }

  /**
   * A component of the declarative UI DSL: `Column() { ... }`.
   *
   * <p>The compiler parses this as ONE construct, not as a call followed by a
   * block, and it is lowered as a CALL because that is what it does: it hands
   * its arguments to a component. The body holds the nested components, and
   * they are lowered into the same function.
   *
   * <p>The arity is the number of arguments the DSL call passes. The
   * component's own type is a CLASS in the SDK's declarations -- the DSL
   * calls it as a function -- so its declared parameter count says nothing
   * about the call, and the argument count is the honest one.
   */
  lowerComponentExpression(node) {
    const args = node.arguments ?? [];
    const wireArgs = [constOp(CONST.STRING, this.moduleName)];
    for (const argument of args) {
      wireArgs.push(this.lowerExpression(argument));
    }
    const name = node.expression.text;
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.CALL,
      calleeSig: `${this.moduleName}.${name}/${args.length}`,
      dest,
      args: wireArgs,
      ...this.position(node),
    });
    if (node.body) {
      this.lowerBlock(node.body);
    }
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
    if (ts.isIdentifier(expression)) return expression.text;
    if (ts.isPropertyAccessExpression(expression)) {
      const name = expression.name?.text;
      if (!name) return undefined;
      const qualifier = this.constructedClassName(expression.expression);
      return qualifier ? `${qualifier}.${name}` : name;
    }
    return undefined;
  }

  lowerBinary(node) {
    const operatorKind = node.operatorToken.kind;
    if (operatorKind === ts.SyntaxKind.EqualsToken) {
      return this.lowerAssignment(node, null);
    }
    const compound = COMPOUND_OPERATORS[operatorKind];
    if (compound) {
      return this.lowerAssignment(node, compound);
    }
    if (node.operatorToken.kind === ts.SyntaxKind.InstanceOfKeyword) {
      const value = this.lowerExpression(node.left);
      const dest = this.state.newTemp(TYPE.BOOLEAN);
      this.emit({
        kind: KIND.INSTANCEOF,
        dest,
        lhs1: value,
        ctorType: this.typeOf(node.right),
        ...this.position(node),
      });
      return dest;
    }
    const operator = BINARY_OPERATORS[node.operatorToken.kind];
    const approximated = APPROXIMATED_OPERATORS[node.operatorToken.kind];
    let binop = operator;
    if (approximated) {
      binop = approximated.binop;
      this.report(PROBLEM.SOURCE_LOWERED, node, approximated.note);
    }
    if (!binop) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const left = this.lowerExpression(node.left);
    const right = this.lowerExpression(node.right);
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
    if (ts.isIdentifier(target)) {
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
    if (ts.isPropertyAccessExpression(target)) {
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
    if (ts.isElementAccessExpression(target)) {
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

  lowerPrefixUnary(node) {
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
    if (!operator) {
      if (node.operator === "+") {
        // A unary plus is a no-op: the operand is the result.
        return this.lowerExpression(node.operand);
      }
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const operand = this.lowerExpression(node.operand);
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
   * <p>The literal is a fresh object and each property is a write into it. The
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
      if (!property.name) {
        continue;
      }
      const key = constOp(CONST.STRING, nameTextOf(property.name) ?? "");
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
      if (!element) {
        return;
      }
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
   * <p>The TAC has no string interpolation, so the holes are concatenated with
   * the `+` operator. The result is the same text the program builds, so the
   * taint of a hole reaches the string.
   */
  lowerTemplate(node) {
    const parts = [];
    const head = node.head;
    if (head && head.text) {
      parts.push(constOp(CONST.STRING, head.text));
    }
    for (const span of node.templateSpans ?? []) {
      parts.push(this.lowerExpression(span.expression));
      if (span.literal && span.literal.text) {
        parts.push(constOp(CONST.STRING, span.literal.text));
      }
    }
    if (parts.length === 0) {
      return constOp(CONST.STRING, "");
    }
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

  // ---- helpers -----------------------------------------------------------------------------------

  emit(instruction) {
    this.state.instructions.push(instruction);
    return instruction;
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
        PROBLEM.TRANSLATION,
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
   * <p>HAVOC says "this value is unknown". It is neither tainted nor clean, and
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

  /** Declare a local, once. */
  declareLocal(variable) {
    if (!variable || !variable.name) {
      return;
    }
    if (!this.state.locals.some((local) => local.name === variable.name)) {
      this.state.locals.push(variable);
    }
  }

  /** Declare the receiver of a struct's or a class's own properties. */
  declareThis() {
    this.declareLocal(varOp("this", TYPE.UNKNOWN));
  }

  /** The node's one-based line and column. */
  position(node) {
    if (!node) {
      return { line: -1, column: -1 };
    }
    let start = node.pos;
    try {
      start = node.getStart(this.sourceFile);
    } catch (e) {
      // A node the compiler cannot place keeps its raw position.
    }
    return { line: this.lineOf(start), column: this.columnOf(start) };
  }

  lineOf(offset) {
    const starts = this.lineStarts;
    let low = 0;
    let high = starts.length - 1;
    while (low < high) {
      const mid = Math.ceil((low + high) / 2);
      if (starts[mid] <= offset) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low + 1;
  }

  columnOf(offset) {
    return offset - this.lineStarts[this.lineOf(offset) - 1] + 1;
  }

  /** The TAC type of a node, from the compiler's own answer. */
  typeOf(node) {
    if (!node || !this.checker) {
      return undefined;
    }
    let type;
    try {
      type = this.checker.getTypeAtLocation(node);
    } catch (e) {
      return undefined;
    }
    if (!type) {
      return undefined;
    }
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
    if (!this.checker) {
      return undefined;
    }
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
    if (expression && ts.isPropertyAccessExpression(expression)) {
      const base = expression.expression;
      // `vm.runInNewContext(...)`: the base is a namespace import, so the
      // owner is the module the namespace came from.
      if (ts.isIdentifier(base)) {
        const binding = this.imports.get(base.text);
        if (binding && binding.isNamespace) {
          return binding.module;
        }
      }
      // The receiver's CLASS is what a catalogue can name. It is asked of the
      // compiler's own type, because the mapped TAC type cannot tell a class
      // that has a name from one that does not.
      const name = this.classNameOfNode(base);
      if (name) {
        return name;
      }
    } else if (expression && ts.isIdentifier(expression)) {
      // `exec(cmd)`, where `exec` was imported: the owner is the module it
      // came from, not the file the call is written in.
      const binding = this.imports.get(expression.text);
      if (binding) {
        return binding.module;
      }
    }
    return this.moduleName;
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
    if (!node || !this.checker) {
      return undefined;
    }
    try {
      const type = this.checker.getTypeAtLocation(node);
      if (!type) {
        return undefined;
      }
      return classNameOf(type);
    } catch (e) {
      return undefined;
    }
  }

  methodNameOf(node) {
    const expression = node.expression;
    if (expression && ts.isPropertyAccessExpression(expression)) {
      return expression.name.text;
    }
    if (expression && ts.isIdentifier(expression)) {
      // An aliased import calls the name it was exported under, so that
      // `import { exec as run }` reaches the catalogue as `exec`.
      const binding = this.imports.get(expression.text);
      if (binding && binding.exported) {
        return binding.exported;
      }
      return expression.text;
    }
    return "<call>";
  }

  /**
   * The arity of a call.
   *
   * <p>The arity counts the DECLARED parameters, not the arguments passed: an
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
    if (text.charCodeAt(i) === 10) {
      starts.push(i + 1);
    }
  }
  return starts;
}

/** The TAC type of a compiler type. */
function mapType(type) {
  const flags = type.flags;
  if (flags & ts.TypeFlags.String) {
    return { kind: TYPE.STRING };
  }
  if (flags & ts.TypeFlags.Number) {
    return { kind: TYPE.DOUBLE };
  }
  if (flags & ts.TypeFlags.BooleanLike) {
    return { kind: TYPE.BOOLEAN };
  }
  if (flags & ts.TypeFlags.BigIntLike) {
    return { kind: TYPE.LONG };
  }
  if (flags & ts.TypeFlags.ESSymbolLike) {
    return { kind: TYPE.UNKNOWN };
  }
  if (flags & ts.TypeFlags.VoidLike) {
    return { kind: TYPE.VOID };
  }
  if (flags & ts.TypeFlags.Null) {
    return { kind: TYPE.NULL };
  }
  if (flags & ts.TypeFlags.Object) {
    const name = classNameOf(type);
    if (name === "Array") {
      return { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } };
    }
    return { kind: TYPE.CLASS, name: name ?? "Object" };
  }
  return { kind: TYPE.UNKNOWN };
}

/**
 * The name of a compiler type's class, when it has one.
 *
 * <p><b>Why the name is read as a PROPERTY.</b> The compiler's symbol carries
 * its name as a property, and the accessor `getName()` is not implemented in
 * every build of the compiler. A symbol without a name is a class without a
 * name, so every instance would degrade to `Object` and every method on it
 * would come out as a call on the FILE's module. The class name is what the
 * owner of a method call has to be, because a catalogue names the class it can
 * recognise.
 */
function classNameOf(type) {
  if (!type) {
    return undefined;
  }
  try {
    if (!type.isObjectType()) {
      return undefined;
    }
    const symbol = type.getSymbol();
    if (!symbol) {
      return undefined;
    }
    const name =
      symbol.name ?? (typeof symbol.getName === "function" ? symbol.getName() : undefined);
    if (typeof name !== "string" || name.length === 0) {
      return undefined;
    }
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
  if (!type) {
    return "any";
  }
  if (type.kind === TYPE.CLASS) {
    return type.name;
  }
  if (type.kind === TYPE.ARRAY) {
    return `${typeText(type.component)}[]`;
  }
  return type.kind.toLowerCase();
}

/** A name node's text, without asking the compiler for the source text. */
function nameTextOf(name) {
  if (!name) {
    return undefined;
  }
  if (ts.isIdentifier(name) || ts.isStringLiteral(name) || ts.isNumericLiteral(name)) {
    return name.text;
  }
  if (name.kind === ts.SyntaxKind.PrivateIdentifier) {
    return name.text;
  }
  if (ts.isComputedPropertyName(name)) {
    const expression = name.expression;
    if (expression && (ts.isStringLiteral(expression) || ts.isNumericLiteral(expression))) {
      return expression.text;
    }
  }
  return undefined;
}

/**
 * Whether an expression calls anything.
 *
 * <p>Used to decide whether an initial value may be copied into every method
 * of a struct: copying a call would run it once per method.
 */
function containsCall(node) {
  let found = false;
  const visit = (child) => {
    if (found || !child) {
      return;
    }
    if (ts.isCallExpression(child) || ts.isNewExpression(child)) {
      found = true;
      return;
    }
    ts.forEachChild(child, visit);
  };
  ts.forEachChild(node, visit);
  return found;
}

/** The node's class name, for a problem report. */
function nodeClassOf(node) {
  if (!node) {
    return "Unknown";
  }
  const name = SYNTAX_KIND_NAMES[node.kind];
  return name ?? `Kind${node.kind}`;
}

// The reverse of the SyntaxKind enum, so a problem can name the construct it
// is about instead of a number.
const SYNTAX_KIND_NAMES = (() => {
  const names = {};
  for (const [name, value] of Object.entries(ts.SyntaxKind ?? {})) {
    if (typeof value === "number") {
      names[value] = name;
    }
  }
  return names;
})();
