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
// a drop on the function, and the affected value becomes a HAVOC. It never
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
  isArrayBindingPattern,
  isArrayLiteralExpression,
  isAsExpression,
  isAwaitExpression,
  isBinaryExpression,
  isBindingElement,
  isBlock,
  isBreakStatement,
  isCallExpression,
  isCatchClause,
  isClassDeclaration,
  isClassExpression,
  isClassStaticBlockDeclaration,
  isConditionalExpression,
  isConstructorDeclaration,
  isContinueStatement,
  isDecorator,
  isDoStatement,
  isElementAccessExpression,
  isEmptyStatement,
  isExpressionStatement,
  isForInStatement,
  isForOfStatement,
  isForStatement,
  isFunctionDeclaration,
  isFunctionExpression,
  isGetAccessorDeclaration,
  isHeritageClause,
  isIdentifier,
  isIfStatement,
  isImportDeclaration,
  isImportEqualsDeclaration,
  isImportExpression,
  isIndexSignatureDeclaration,
  isJsxElement,
  isJsxFragment,
  isJsxSelfClosingElement,
  isLabeledStatement,
  isMetaProperty,
  isMethodDeclaration,
  isNamespaceImport,
  isNewExpression,
  isNonNullExpression,
  isNullLiteral,
  isNumericLiteral,
  isObjectBindingPattern,
  isObjectLiteralExpression,
  isOmittedExpression,
  isParenthesizedExpression,
  isPostfixUnaryExpression,
  isPrefixUnaryExpression,
  isPrivateIdentifier,
  isPropertyAccessExpression,
  isPropertyDeclaration,
  isRegularExpressionLiteral,
  isReturnStatement,
  isSatisfiesExpression,
  isSetAccessorDeclaration,
  isSpreadAssignment,
  isSpreadElement,
  isStringLiteral,
  isSuperExpression,
  isSwitchStatement,
  isTaggedTemplateExpression,
  isTemplateExpression,
  isThrowStatement,
  isTryStatement,
  isTypeAssertion,
  isVariableDeclaration,
  isVariableDeclarationList,
  isVariableStatement,
  isVoidExpression,
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
  // A loose equality and a strict one are DIFFERENT operators: `==` coerces
  // its operands, `===` does not. The token kind is what tells them apart,
  // so the two spellings no longer share one entry.
  [SyntaxKind.EqualsEqualsToken]: "LOOSE_EQ",
  [SyntaxKind.EqualsEqualsEqualsToken]: "EQ",
  [SyntaxKind.ExclamationEqualsToken]: "LOOSE_NE",
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
 * The operators the TAC does not have.
 *
 * <p>The TAC's binary operators are the arithmetic, the bitwise, the
 * comparison and the short-circuit ones. A power is not among them.
 *
 * <p>It is mapped onto the closest thing the TAC has and REPORTED: the
 * function is still lowered -- the taint of the operands reaches the result
 * either way -- but the reader is told that the operator in the TAC is not
 * the operator in the source.
 *
 * <p>The null-coalescing operator is NOT in this table any more: `a ?? b` is a
 * SHORT-CIRCUIT expression and it is expanded into a branch chain rather than
 * approximated by a disjunction.
 */
const APPROXIMATED_OPERATORS = {
  [SyntaxKind.AsteriskAsteriskToken]: {
    binop: "MUL",
    note: "the TAC has no power operator, so the power is modelled as a multiplication",
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
 *
 * <p>The three LOGICAL compound assignments are not here: they are
 * short-circuits, and each of them expands into "read, decide, maybe write".
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
};

/**
 * The logical compound assignments: `&&=`, `||=` and `??=`.
 *
 * <p>Each of them is a short-circuit: the right-hand side is evaluated only
 * when the left-hand side says so, and the value of the expression is the
 * value of the left-hand side AFTER the write.
 */
const LOGICAL_COMPOUND_OPERATORS = {
  [SyntaxKind.BarBarEqualsToken]: "LOR",
  [SyntaxKind.AmpersandAmpersandEqualsToken]: "LAND",
  [SyntaxKind.QuestionQuestionEqualsToken]: "NULL_COALESCE",
};

/**
 * The TAC's unary operators, by the operator's SYNTAX KIND.
 *
 * <p>The key is the kind, not the spelling: the compiler hands out a number,
 * and a table keyed by the spelling would never be found.
 *
 * <p>There is no unary plus in the TAC, and there does not need to be: a
 * unary plus is a no-op, so the operand is the result.
 */
const UNARY_OPERATORS = {
  [SyntaxKind.ExclamationToken]: "NOT",
  [SyntaxKind.TildeToken]: "BITNOT",
  [SyntaxKind.MinusToken]: "NEG",
};

/**
 * The kinds of member a function can be.
 *
 * <p>A member kind says what the function IS, not what it does: a getter and
 * a setter are both "a member of the class", but the engine has to tell them
 * apart, because a getter is called and a setter is written.
 */
const MEMBER_KIND = {
  METHOD: "METHOD",
  CONSTRUCTOR: "CONSTRUCTOR",
  GETTER: "GETTER",
  SETTER: "SETTER",
  STATIC_INITIALIZER: "STATIC_INITIALIZER",
  FIELD_INITIALIZER: "FIELD_INITIALIZER",
};

/**
 * How much a degradation costs.
 *
 * <p>`SEMANTICS` -- the semantics are lost, so the function is degraded.
 * `STRUCTURE` -- only the shape is lost, so the function is lowered.
 * `VALUE` -- one value is unknown; the precision is unchanged.
 * `TYPE` -- only type-level information is lost; the precision is unchanged.
 */
const IMPACT = {
  SEMANTICS: "SEMANTICS",
  STRUCTURE: "STRUCTURE",
  VALUE: "VALUE",
  TYPE: "TYPE",
};

/**
 * The wire values the protocol's constant tables do not carry yet.
 *
 * <p>The protocol tables are shared with the other front ends, and they are
 * not this file's to change: the values are written out here so that the
 * translator does not depend on a table that does not have them yet. The
 * Java side maps them by name.
 */
const CONST_REGEX = "REGEX";
const TYPE_BIGINT = "BIGINT";
const PRECISION_DEGRADED = "SOURCE_DEGRADED";

/**
 * The owner of a call whose owner this translator could not determine.
 *
 * <p>The owner is not a sentinel: the callee key simply has no owner section,
 * which is the shape the IR defines for "the owner is not knowable here". A
 * sentinel that no module path can contain used to stand in for it, and a key
 * that begins with one cannot be claimed by any sink pattern -- but it also
 * cannot be resolved by the call graph, and it hid the difference between "the
 * owner is missing" and "the owner is a global".
 */
const OWNER_MISSING = null;

/**
 * The names of the globals the language itself provides.
 *
 * <p>The table gives NAMES and nothing else: it says which receiver a call
 * belongs to, and it does NOT say whether that call is a source or a sink.
 * That is the engine's rule table's business, keyed by `owner.method`.
 */
const GLOBAL_OWNERS = new Set([
  "Array",
  "ArrayBuffer",
  "BigInt",
  "Boolean",
  "Buffer",
  "DataView",
  "Date",
  "Error",
  "EvalError",
  "Function",
  "Intl",
  "JSON",
  "Map",
  "Math",
  "Number",
  "Object",
  "Promise",
  "Proxy",
  "RangeError",
  "ReferenceError",
  "Reflect",
  "RegExp",
  "Set",
  "String",
  "Symbol",
  "SyntaxError",
  "TypeError",
  "URIError",
  "URL",
  "URLSearchParams",
  "WeakMap",
  "WeakRef",
  "WeakSet",
  "alert",
  "clearInterval",
  "clearTimeout",
  "console",
  "crypto",
  "decodeURI",
  "decodeURIComponent",
  "document",
  "encodeURI",
  "encodeURIComponent",
  "eval",
  "fetch",
  "globalThis",
  "history",
  "isFinite",
  "isNaN",
  "localStorage",
  "location",
  "navigator",
  "parseFloat",
  "parseInt",
  "performance",
  "postMessage",
  "process",
  "queueMicrotask",
  "require",
  "self",
  "sessionStorage",
  "setInterval",
  "setTimeout",
  "structuredClone",
  "window",
]);

/**
 * One function's lowering state.
 *
 * <p>The instruction list, the locals and the problems belong to the function
 * being lowered, so they live here rather than on the translator.
 */
class FunctionState {
  constructor(name, signature, enclosingClass) {
    this.name = name;
    this.signature = signature;
    this.enclosingClass = enclosingClass;
    this.memberKind = MEMBER_KIND.METHOD;
    this.synthetic = false;
    this.params = [];
    this.locals = [];
    this.instructions = [];
    this.problems = [];
    this.drops = [];
    this.tryRegions = [];
    this.fieldAnnotations = {};
    this.classAnnotations = [];
    this.methodAnnotations = [];
    this.paramAnnotations = {};
    this.annotationValues = {};
    this.returnType = undefined;
    this.interfaces = [];
    this.superType = null;
    this.labelRefs = new Map();
    this.scopes = [];
    this.tempCounter = 0;
    this.labelCounter = 0;
    this.labelPrefix = "";
  }

  newTemp(type) {
    this.tempCounter += 1;
    return varOp(`t_t${this.tempCounter}`, type, true);
  }

  newLabel(kind, name) {
    this.labelCounter += 1;
    const label = `${this.labelPrefix}L${this.labelCounter}`;
    if (kind) {
      this.labelRefs.set(label, { kind, name: name ?? null, scopeId: this.scopes.length });
    }
    return label;
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
    this.pendingFunctions = [];
    this.inlineCounter = 0;
    this.sourceFile = null;
    this.metaProperties = [];
    this.enclosingBase = undefined;
  }

  /** The functions declared in a source file, in source order. */
  translateSourceFile(sourceFile) {
    this.sourceFile = sourceFile;
    this.collectModuleBindings(sourceFile);
    this.collectDeclaredNames(sourceFile);
    this.metaProperties = collectMetaProperties(sourceFile);
    const functions = [];
    const moduleStatements = [];
    // The decorators of the module's own functions. A decorator runs where
    // the function is DEFINED, so its call belongs to the module's stream
    // and not to the body of the function it decorates.
    const moduleDecorators = [];
    const flushModule = () => {
      if (moduleStatements.length === 0 && moduleDecorators.length === 0) {
        return;
      }
      const state = this.beginFunction("__top", `${this.moduleName}.__top()`, null);
      state.synthetic = true;
      state.memberKind = MEMBER_KIND.METHOD;
      this.guard(moduleStatements[0] ?? moduleDecorators[0].decorator, () => {
        for (const statement of moduleStatements) {
          this.lowerStatement(statement);
        }
        for (const application of moduleDecorators) {
          this.lowerDecorator(application);
        }
      });
      functions.push(this.finishFunction(state));
      functions.push(...this.takePendingFunctions());
      moduleStatements.length = 0;
      moduleDecorators.length = 0;
    };

    for (const statement of sourceFile.statements) {
      if (isFunctionDeclaration(statement) && statement.name) {
        // The function's decorators run where the function is defined: in
        // the module's own stream, before the function's own body.
        moduleDecorators.push(
          ...this.decoratorApplicationsOf(statement, statement.name.text),
        );
        flushModule();
        functions.push(...this.lowerFunctionDeclaration(statement, null));
        continue;
      }
      if (isClassDeclaration(statement) && statement.name) {
        flushModule();
        functions.push(...this.lowerClass(statement));
        continue;
      }
      // A top-level statement that is not a function: the module's own code.
      // It becomes a synthetic function so that the engine has somewhere to
      // put the instructions -- one function for the whole module, so that
      // the module's statements keep their order relative to each other.
      moduleStatements.push(statement);
    }
    flushModule();

    if (this.metaProperties.length > 0) {
      functions.push(...this.lowerMetaFunction());
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

  lowerFunctionDeclaration(node, enclosingClass, memberKind = MEMBER_KIND.METHOD) {
    const name = node.name ? node.name.text : "<anonymous>";
    const state = this.beginFunction(name, this.functionSignature(node, enclosingClass), enclosingClass);
    state.memberKind = memberKind;
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    if (node.type) {
      state.returnType = this.typeOf(node.type);
    }
    const finished = this.finishFunction(state);
    return [finished, ...this.takePendingFunctions()];
  }

  /**
   * The methods of a class, and the class's own field initialisers.
   *
   * <p>The dispatch is EXHAUSTIVE: every kind of member a class can have is
   * lowered into a function of its own, and a member kind this translator
   * does not know is reported rather than silently dropped. A class whose
   * constructor, fields, accessors and static blocks all disappear still
   * claims full precision -- that is the shape of a half-lowered program that
   * looks like a whole one.
   */
  lowerClass(node) {
    const out = [];
    const className = node.name ? node.name.text : "<anonymous>";
    const fields = (node.members ?? []).filter((member) => isPropertyDeclaration(member));
    const base = this.baseClassNameOf(node);
    const interfaces = this.implementedInterfacesOf(node);
    const decorators = this.decoratorNamesOf(node);
    // The class's own decorators, and those of its members. They all run
    // where the CLASS is defined, which is the class's own stream.
    const applications = this.classDecoratorApplications(node, className);
    const previousBase = this.enclosingBase;
    this.enclosingBase = base;

    let constructor = null;
    for (const member of node.members ?? []) {
      if (isConstructorDeclaration(member)) {
        constructor = member;
        break;
      }
    }
    if (constructor) {
      out.push(
        ...this.lowerConstructor(
          constructor,
          className,
          fields,
          base,
          decorators,
          interfaces,
          applications,
        ),
      );
    } else {
      // Every class has an own function, even one without fields: it is where
      // the class's own facts -- the interfaces it implements -- are recorded.
      out.push(
        ...this.lowerFieldInitializer(
          className,
          fields,
          base,
          decorators,
          interfaces,
          applications,
        ),
      );
    }
    for (const member of node.members ?? []) {
      if (isMethodDeclaration(member) && member.name) {
        out.push(...this.lowerFunctionDeclaration(member, className, MEMBER_KIND.METHOD));
        continue;
      }
      if (isGetAccessorDeclaration(member)) {
        out.push(...this.lowerAccessor(member, className, MEMBER_KIND.GETTER));
        continue;
      }
      if (isSetAccessorDeclaration(member)) {
        out.push(...this.lowerAccessor(member, className, MEMBER_KIND.SETTER));
        continue;
      }
      if (isClassStaticBlockDeclaration(member)) {
        out.push(...this.lowerStaticBlock(member, className));
        continue;
      }
      if (isPropertyDeclaration(member)) {
        // The field's initial value is in the field initialiser (or in the
        // constructor); an auto-accessor adds a getter and a setter.
        out.push(...this.lowerAccessorField(member, className));
        continue;
      }
      if (isIndexSignatureDeclaration(member)) {
        // An index signature has no run-time behaviour at all.
        continue;
      }
      if (isConstructorDeclaration(member)) {
        continue;
      }
      this.report(PROBLEM.UNHANDLED_NODE, member, "this class member has no TAC lowering");
    }
    this.enclosingBase = previousBase;
    return out;
  }

  /**
   * A constructor.
   *
   * <p>A constructor is a function of its own: the name is `<init>` and the
   * member kind says so. The base class's constructor runs FIRST, so that the
   * base's fields exist before the derived class's own fields are written.
   */
  lowerConstructor(node, className, fields, base, decorators, interfaces, applications) {
    const state = this.beginFunction(
      "<init>",
      `${this.moduleName}.${className}.<init>(${this.constructorParameterTypes(node)})`,
      className,
    );
    state.memberKind = MEMBER_KIND.CONSTRUCTOR;
    state.classAnnotations = decorators.slice();
    state.superType = base ?? null;
    state.interfaces = interfaces ? interfaces.slice() : this.implementedInterfacesOf(node);
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      this.declareThis();
      // The class's decorators, and those of its members, run where the class
      // is defined: in the class's own stream.
      for (const application of applications ?? []) {
        this.lowerDecorator(application);
      }
      if (base) {
        // The base constructor runs first, and it runs on THIS object.
        const result = this.state.newTemp(TYPE.UNKNOWN);
        this.emit({
          kind: KIND.CALL,
          calleeSig: `${base}.<init>/${this.baseConstructorArity(base)}`,
          dest: result,
          args: [varOp("this", TYPE.UNKNOWN)],
          ...this.position(node),
        });
      }
      this.lowerFieldInitializers(fields, className);
      this.lowerParameterProperties(node, className);
      this.emitImplementsDrop(node, state.interfaces, "the class");
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    const finished = this.finishFunction(state);
    return [finished, ...this.takePendingFunctions()];
  }

  /**
   * The field initialisers of a class that has no constructor of its own.
   *
   * <p>The function is synthetic: the class's fields are written when the
   * class is initialised, and that is a moment of the program's own making.
   */
  lowerFieldInitializer(className, fields, base, decorators, interfaces, applications) {
    const state = this.beginFunction(className, `${this.moduleName}.${className}()`, className);
    state.memberKind = MEMBER_KIND.FIELD_INITIALIZER;
    state.synthetic = true;
    state.classAnnotations = decorators.slice();
    state.superType = base ?? null;
    state.interfaces = interfaces ? interfaces.slice() : [];
    this.guard(fields[0] ?? { getStart: () => 0 }, () => {
      this.declareThis();
      // The class's decorators, and those of its members, run where the class
      // is defined: in the class's own stream.
      for (const application of applications ?? []) {
        this.lowerDecorator(application);
      }
      this.lowerFieldInitializers(fields, className);
      this.emitImplementsDrop(fields[0] ?? null, state.interfaces, "the class");
    });
    const finished = this.finishFunction(state);
    return [finished, ...this.takePendingFunctions()];
  }

  /**
   * Record the interfaces a class implements.
   *
   * <p>The relation is a TYPE-level fact: it has no run-time behaviour, and it
   * does not belong to the call graph. It is recorded on the CLASS's own
   * function, because a drop that lands on one of the class's members says
   * something untrue about that member.
   */
  emitImplementsDrop(node, interfaces, subject) {
    if (!interfaces || interfaces.length === 0) return;
    this.emitDrop(
      "implements",
      `${subject} implements ${interfaces.join(", ")}; the relation is a type-level fact`,
      IMPACT.TYPE,
      node,
    );
  }

  /** A getter or a setter: a member of the class, and a function of its own. */
  lowerAccessor(node, className, memberKind) {
    const name = node.name ? node.name.text : "<accessor>";
    const state = this.beginFunction(name, this.functionSignature(node, className), className);
    state.memberKind = memberKind;
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      this.declareThis();
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    if (node.type) {
      state.returnType = this.typeOf(node.type);
    }
    const finished = this.finishFunction(state);
    return [finished, ...this.takePendingFunctions()];
  }

  /** A static block: the class's own initialiser, run once. */
  lowerStaticBlock(node, className) {
    const state = this.beginFunction(
      "<clinit>",
      `${this.moduleName}.${className}.<clinit>()`,
      className,
    );
    state.memberKind = MEMBER_KIND.STATIC_INITIALIZER;
    state.synthetic = true;
    this.guard(node, () => {
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    const finished = this.finishFunction(state);
    return [finished, ...this.takePendingFunctions()];
  }

  /**
   * An auto-accessor field: a private backing field, a getter and a setter.
   */
  lowerAccessorField(node, className) {
    if (!this.isAccessorField(node)) {
      return [];
    }
    const name = node.name ? node.name.text : "x";
    const out = [];

    const getter = this.beginFunction(name, `${this.moduleName}.${className}.${name}()`, className);
    getter.memberKind = MEMBER_KIND.GETTER;
    getter.synthetic = true;
    this.guard(node, () => {
      this.declareThis();
      const value = this.state.newTemp(this.typeOf(node));
      this.emit({
        kind: KIND.ARRAYLOAD,
        dest: value,
        lhs1: varOp("this", TYPE.UNKNOWN),
        lhs2: constOp(CONST.STRING, name),
        ...this.position(node),
      });
      this.emit({ kind: KIND.RETURN, lhs1: value, ...this.position(node) });
    });
    out.push(this.finishFunction(getter));

    const setter = this.beginFunction(
      name,
      `${this.moduleName}.${className}.${name}(${this.constructorParameterTypes(node)})`,
      className,
    );
    setter.memberKind = MEMBER_KIND.SETTER;
    setter.synthetic = true;
    this.guard(node, () => {
      this.declareThis();
      const parameter = (node.parameters ?? [])[0];
      const value = parameter ? varOp(parameter.name.text, this.typeOf(parameter)) : undefined;
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: varOp("this", TYPE.UNKNOWN),
        lhs2: constOp(CONST.STRING, name),
        dest: value,
        ...this.position(node),
      });
    });
    out.push(this.finishFunction(setter));
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
      const previous = this.state;
      const state = this.beginFunction(name, this.functionSignature(initialiser, null), null);
      this.guard(initialiser, () => {
        this.lowerParameters(initialiser.parameters);
        if (initialiser.body) {
          this.lowerBlock(initialiser.body);
        }
      });
      if (initialiser.type) {
        state.returnType = this.typeOf(initialiser.type);
      }
      out.push(this.finishFunction(state));
      // The lifted function is a function of its own: the state that was
      // being lowered is the one that continues.
      this.state = previous;
    }
    return out;
  }

  /**
   * The body of an immediately invoked function, lifted into a function of its own.
   *
   * <p>An IIFE -- {@code (() => { ... })()} -- calls a function literal, and the
   * literal's body is CODE. The engine resolves a call by the callee's name, so
   * the body has to be a function with a name of its own: otherwise the call has
   * nothing to resolve to and the body is not in the TAC at all -- not as
   * instructions, and not as a drop either. That is the worst of both: the
   * function reports full precision and the code is simply gone.
   *
   * <p><b>What is NOT modelled: the capture.</b> A lifted function has its own
   * scope, so the names the body read from the scope around it are not in it. The
   * body is lowered anyway -- a sink in it is still a sink -- and the loss is
   * recorded with a drop, so that the precision says so.
   *
   * @param {object} node the call node
   * @returns {{name: string, arity: number}|null} the lifted function's name and arity,
   *     or null when the callee is not a function literal
   */
  liftInlineFunction(node) {
    let literal = node.expression;
    // The callee is usually PARENTHESIZED -- `(() => { ... })()` -- and the
    // brackets are not part of the function, so they come off before the
    // literal is recognised.
    while (literal && isParenthesizedExpression(literal)) {
      literal = literal.expression;
    }
    if (!literal) return null;
    if (!isArrowFunction(literal) && !isFunctionExpression(literal)) return null;
    const name = `closure$${this.inlineCounter += 1}`;
    const previous = this.state;
    const state = this.beginFunction(name, this.functionSignature(literal, null, name), null);
    state.synthetic = true;
    this.guard(literal, () => {
      this.lowerParameters(literal.parameters);
      if (literal.body) {
        this.lowerBlock(literal.body);
      }
    });
    const finished = this.finishFunction(state);
    this.state = previous;
    this.pendingFunctions.push(finished);
    this.emitDrop(
      "iife-body",
      "the body of an immediately invoked function is lifted into a function of its own, and"
        + " the names it read from the scope around it are not in that function",
      IMPACT.STRUCTURE,
      node,
    );
    return {
      name,
      arity: (literal.parameters ?? []).filter((p) => !isThisParameter(p)).length,
    };
  }

  /** The functions that were lifted while the last function was lowered. */
  takePendingFunctions() {
    const pending = this.pendingFunctions;
    this.pendingFunctions = [];
    return pending;
  }

  /**
   * Lower a nested declaration without disturbing the function around it.
   *
   * <p>A nested function is lowered into a function of its own, and lowering
   * it replaces the state that is being lowered. The state around it is put
   * back afterwards, so the enclosing function keeps its own instructions.
   */
  lowerNested(node, body) {
    const previous = this.state;
    try {
      return body();
    } finally {
      this.state = previous;
    }
  }

  beginFunction(name, signature, enclosingClass) {
    const state = new FunctionState(name, signature, enclosingClass);
    this.state = state;
    return state;
  }

  finishFunction(state) {
    const problems = state.problems;
    const returnType = state.returnType ?? { kind: TYPE.VOID };
    return {
      name: state.name,
      signature: state.signature,
      enclosingClass: state.enclosingClass ?? undefined,
      memberKind: state.memberKind,
      returnType,
      domain: domainOf(returnType) ?? null,
      precision: precisionOf(state),
      synthetic: state.synthetic,
      params: state.params,
      locals: state.locals,
      instructions: state.instructions,
      problems,
      drops: state.drops,
      tryRegions: state.tryRegions,
      classAnnotations: state.classAnnotations,
      methodAnnotations: state.methodAnnotations,
      fieldAnnotations: state.fieldAnnotations,
      paramAnnotations: state.paramAnnotations,
      annotationValues: state.annotationValues,
      superType: state.superType ?? null,
      interfaces: state.interfaces ?? [],
    };
  }

  lowerParameters(parameters) {
    if (!parameters) return;
    for (const parameter of parameters) {
      const name = parameter.name && parameter.name.text ? parameter.name.text : "p";
      // A `this` parameter is a TYPE annotation: it says what `this` is, and
      // it does not take a slot at run time.
      if (name === "this" && isIdentifier(parameter.name)) {
        continue;
      }
      // A parameter decorator runs where the function is defined, and it
      // replaces nothing: there is no value of the parameter that it
      // changes, and so no holder for it to write back to. It is reported
      // rather than silently dropped.
      for (const decorator of this.decoratorNodesOf(parameter)) {
        this.report(
          PROBLEM.UNHANDLED_NODE,
          decorator,
          "a parameter decorator has no TAC lowering",
        );
      }
      const type = this.typeOf(parameter);
      const operand = varOp(name, parameter.questionToken ? nullable(type) : type);
      this.state.params.push(operand);
      if (parameter.dotDotDotToken) {
        // A rest parameter is an ARRAY of the remaining arguments.
        const array = varOp(name, { kind: TYPE.ARRAY, component: type ?? { kind: TYPE.UNKNOWN } });
        this.state.params[this.state.params.length - 1] = array;
      }
      // A default value is applied when the function is entered, before the
      // body runs. Its expression is lowered like any other: a call in a
      // default value is a call that happens.
      if (parameter.initializer) {
        const value = this.lowerExpression(parameter.initializer);
        this.emit({
          kind: KIND.MOVE,
          dest: this.state.params[this.state.params.length - 1],
          lhs1: value,
          ...this.position(parameter),
        });
      }
    }
  }

  /** The signature string: `owner.name(ParamTypes):Return`. */
  functionSignature(node, enclosingClass, nameOverride) {
    const owner = enclosingClass ?? this.moduleName;
    const parameters = (node.parameters ?? []).filter((p) => !isThisParameter(p));
    const types = parameters.map((p) => this.typeTextOf(p));
    const returnType = this.returnTypeTextOf(node);
    // A name OVERRIDE is for a function literal that has no name of its own.
    // Two of them would otherwise share the name "<anonymous>", and the
    // function table is keyed by the signature -- so the second one would
    // replace the first, and its body would be gone.
    const name = nameOverride ?? (node.name ? node.name.text : "<anonymous>");
    return `${owner}.${name}(${types.join(",")}):${returnType}`;
  }

  /** The parameter types of a constructor, for its signature. */
  constructorParameterTypes(node) {
    return (node.parameters ?? [])
      .filter((p) => !isThisParameter(p))
      .map((p) => this.typeTextOf(p))
      .join(",");
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
      if (value && value.type) {
        this.state.returnType = value.type;
      }
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
    if (isDoStatement(node)) {
      this.lowerDo(node);
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
    if (isForInStatement(node)) {
      this.lowerForIn(node);
      return;
    }
    if (isSwitchStatement(node)) {
      this.lowerSwitch(node);
      return;
    }
    if (isTryStatement(node)) {
      this.lowerTry(node);
      return;
    }
    if (isLabeledStatement(node)) {
      this.lowerLabels(node);
      return;
    }
    if (isBlock(node)) {
      this.lowerBlock(node);
      return;
    }
    if (isBreakStatement(node) || isContinueStatement(node)) {
      this.lowerJump(node);
      return;
    }
    if (isFunctionDeclaration(node)) {
      // A nested function's decorators run where it is defined -- in THIS
      // stream, and not in the body of the function they decorate.
      for (const application of this.decoratorApplicationsOf(node, node.name?.text)) {
        this.lowerDecorator(application);
      }
      // A nested function is a function of its own.
      this.pendingFunctions.push(...this.lowerNested(node, () =>
        this.lowerFunctionDeclaration(node, this.state?.enclosingClass ?? null),
      ));
      return;
    }
    if (isClassDeclaration(node)) {
      // A nested class is a class of its own; its members are lifted.
      this.pendingFunctions.push(...this.lowerNested(node, () => this.lowerClass(node)));
      return;
    }
    if (isImportDeclaration(node)) {
      this.lowerImport(node);
      return;
    }
    if (isImportEqualsDeclaration(node)) {
      return;
    }
    if (isExportDeclaration(node) || isExportAssignment(node)) {
      this.lowerExport(node);
      return;
    }
    if (isEmptyStatement(node)) {
      return;
    }
    this.report(PROBLEM.UNHANDLED_NODE, node, "this statement has no TAC lowering");
  }

  lowerVariableStatement(node) {
    // A declaration whose initialiser is a function declares a FUNCTION, not
    // a value: it is lifted into a function of its own, and the rest of the
    // statement is lowered as usual.
    const lifted = this.liftFunctionVariables(node);
    if (lifted.length > 0) {
      this.pendingFunctions.push(...lifted);
    }
    for (const declaration of node.declarationList.declarations) {
      if (!isVariableDeclaration(declaration)) continue;
      const initialiser = declaration.initializer;
      if (initialiser && (isArrowFunction(initialiser) || isFunctionExpression(initialiser))) {
        continue;
      }
      if (isObjectBindingPattern(declaration.name) || isArrayBindingPattern(declaration.name)) {
        this.lowerBindingPattern(declaration);
        continue;
      }
      const name = declaration.name && declaration.name.text ? declaration.name.text : "v";
      const type = this.typeOf(declaration);
      const variable = varOp(name, type);
      if (!this.state.locals.some((l) => l.name === name)) {
        this.state.locals.push(variable);
      }
      if (!initialiser) continue;
      const value = this.lowerExpression(initialiser);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: value,
        ...this.position(declaration),
      });
    }
  }

  /**
   * A destructuring declaration.
   *
   * <p>Each name in the pattern is read out of the value and bound to its
   * own variable: `const {a: {b}} = o` reads `o["a"]` and then reads
   * `["b"]` out of that, however deep the pattern is.
   */
  lowerBindingPattern(declaration) {
    const source = this.lowerExpression(declaration.initializer);
    this.bindPattern(declaration.name, source, declaration);
  }

  /** Bind every name of a pattern to the value it names. */
  bindPattern(pattern, source, node) {
    if (!pattern) return;
    if (isObjectBindingPattern(pattern)) {
      for (const element of pattern.elements ?? []) {
        const key = element.propertyName
          ? element.propertyName.text
          : element.name && element.name.text;
        const read = this.state.newTemp(TYPE.UNKNOWN);
        this.emit({
          kind: KIND.ARRAYLOAD,
          dest: read,
          lhs1: source,
          lhs2: constOp(CONST.STRING, key ?? ""),
          ...this.position(node),
        });
        if (element.dotDotDotToken) {
          // The rest element: a fresh object that holds what is left.
          const rest = this.state.newTemp(TYPE.UNKNOWN);
          this.emit({
            kind: KIND.NEW,
            dest: rest,
            ctorType: { kind: TYPE.CLASS, name: "Object" },
            calleeSig: "Object.<init>/0",
            args: [],
            ...this.position(node),
          });
          const name = element.name && element.name.text;
          if (name) {
            this.declareLocal(varOp(name, TYPE.UNKNOWN));
            this.emit({
              kind: KIND.MOVE,
              dest: varOp(name, TYPE.UNKNOWN),
              lhs1: rest,
              ...this.position(node),
            });
          }
          this.emitDrop(
            "rest-element",
            "the rest of an object pattern is modelled as an empty object",
            IMPACT.STRUCTURE,
            node,
          );
          continue;
        }
        this.bindElement(element, read, node);
      }
      return;
    }
    if (isArrayBindingPattern(pattern)) {
      let index = 0;
      for (const element of pattern.elements ?? []) {
        if (isOmittedExpression(element)) {
          index += 1;
          continue;
        }
        const read = this.state.newTemp(TYPE.UNKNOWN);
        this.emit({
          kind: KIND.ARRAYLOAD,
          dest: read,
          lhs1: source,
          lhs2: constOp(CONST.INT, index),
          ...this.position(node),
        });
        index += 1;
        if (element.dotDotDotToken) {
          // The rest element: a fresh array that holds what is left.
          const rest = this.state.newTemp({ kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } });
          this.emit({
            kind: KIND.NEWARRAY,
            dest: rest,
            ctorType: { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } },
            lhs1: constOp(CONST.INT, 0),
            ...this.position(node),
          });
          const name = element.name && element.name.text;
          if (name) {
            this.declareLocal(varOp(name, { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } }));
            this.emit({
              kind: KIND.MOVE,
              dest: varOp(name, { kind: TYPE.ARRAY, component: { kind: TYPE.UNKNOWN } }),
              lhs1: rest,
              ...this.position(node),
            });
          }
          this.emitDrop(
            "rest-element",
            "the rest of an array pattern is modelled as an empty array",
            IMPACT.STRUCTURE,
            node,
          );
          continue;
        }
        this.bindElement(element, read, node);
      }
      return;
    }
    if (isIdentifier(pattern)) {
      const variable = varOp(pattern.text, this.typeOf(pattern));
      this.declareLocal(variable);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: source,
        ...this.position(node),
      });
    }
  }

  /** Bind one element of a pattern, with its default value if it has one. */
  bindElement(element, read, node) {
    if (!element || !element.name) return;
    if (isObjectBindingPattern(element.name) || isArrayBindingPattern(element.name)) {
      this.bindPattern(element.name, read, node);
      return;
    }
    const name = element.name.text;
    const variable = varOp(name, this.typeOf(element.name));
    this.declareLocal(variable);
    if (!element.initializer) {
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: read,
        ...this.position(node),
      });
      return;
    }
    // A default value: it is used when the value that was read is undefined.
    const defaultValue = this.lowerExpression(element.initializer);
    const haveLabel = this.state.newLabel();
    const defaultLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emitBranch(
      read,
      haveLabel,
      defaultLabel,
      { kind: "Compare", lhs: read, op: "NE", rhs: constOp(CONST.NULL, null) },
      node,
    );
    this.emitLabel(haveLabel);
    this.emit({ kind: KIND.MOVE, dest: variable, lhs1: read, ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emitLabel(defaultLabel);
    this.emit({ kind: KIND.MOVE, dest: variable, lhs1: defaultValue, ...this.position(node) });
    this.emitLabel(endLabel);
  }

  lowerBlock(node) {
    for (const statement of node.statements) {
      this.lowerStatement(statement);
    }
  }

  lowerIf(node) {
    const condition = this.lowerExpression(conditionOf(node));
    const thenLabel = this.state.newLabel();
    const elseLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emitBranch(condition, thenLabel, elseLabel, truthiness(condition), node);
    this.emitLabel(thenLabel);
    if (node.thenStatement) this.lowerStatement(node.thenStatement);
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emitLabel(elseLabel);
    if (node.elseStatement) this.lowerStatement(node.elseStatement);
    this.emitLabel(endLabel);
  }

  lowerWhile(node) {
    const headLabel = this.state.newLabel("LOOP_HEAD");
    const bodyLabel = this.state.newLabel();
    const endLabel = this.state.newLabel("LOOP_END");
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(headLabel);
    const condition = this.lowerExpression(conditionOf(node));
    this.emitBranch(condition, bodyLabel, endLabel, truthiness(condition), node);
    this.emitLabel(bodyLabel);
    this.state.scopes.push({ kind: "LOOP", endLabel, headLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.state.scopes.pop();
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(endLabel);
  }

  lowerDo(node) {
    const bodyLabel = this.state.newLabel();
    const condLabel = this.state.newLabel("LOOP_HEAD");
    const endLabel = this.state.newLabel("LOOP_END");
    this.emitLabel(bodyLabel);
    this.state.scopes.push({ kind: "LOOP", endLabel, headLabel: condLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.state.scopes.pop();
    this.emitLabel(condLabel);
    // A `do` statement's condition is its EXPRESSION.
    const condition = this.lowerExpression(conditionOf(node));
    this.emitBranch(condition, bodyLabel, endLabel, truthiness(condition), node);
    this.emitLabel(endLabel);
  }

  lowerFor(node) {
    if (node.initializer) {
      // The compiler gives a `for (let i = 0; ...)` a DECLARATION LIST, not a
      // statement: the two are different nodes, and only the list holds the
      // declarations.
      if (isVariableDeclarationList(node.initializer)) {
        for (const declaration of node.initializer.declarations ?? []) {
          this.lowerVariableDeclaration(declaration);
        }
      } else if (isVariableStatement(node.initializer)) {
        this.lowerVariableStatement(node.initializer);
      } else {
        this.lowerExpression(node.initializer);
      }
    }
    const headLabel = this.state.newLabel("LOOP_HEAD");
    const bodyLabel = this.state.newLabel();
    const stepLabel = this.state.newLabel();
    const endLabel = this.state.newLabel("LOOP_END");
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(headLabel);
    if (conditionOf(node)) {
      const condition = this.lowerExpression(conditionOf(node));
      this.emitBranch(condition, bodyLabel, endLabel, truthiness(condition), node);
    } else {
      this.emit({ kind: KIND.JUMP, label: bodyLabel });
    }
    this.emitLabel(bodyLabel);
    this.state.scopes.push({ kind: "LOOP", endLabel, headLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.state.scopes.pop();
    this.emit({ kind: KIND.JUMP, label: stepLabel });
    this.emitLabel(stepLabel);
    if (node.incrementor) this.lowerExpression(node.incrementor);
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(endLabel);
  }

  /** One declaration of a `for` loop's initialiser. */
  lowerVariableDeclaration(declaration) {
    if (!isVariableDeclaration(declaration)) return;
    const name = declaration.name && declaration.name.text ? declaration.name.text : "v";
    const variable = varOp(name, this.typeOf(declaration));
    this.declareLocal(variable);
    if (!declaration.initializer) return;
    const value = this.lowerExpression(declaration.initializer);
    this.emit({
      kind: KIND.MOVE,
      dest: variable,
      lhs1: value,
      ...this.position(declaration),
    });
  }

  /**
   * The declaration a `for ... of` or a `for ... in` loop binds.
   *
   * <p>The compiler gives the loop a DECLARATION LIST, and the declaration
   * that binds the loop variable is the first one in it. A loop whose
   * initialiser is an expression binds nothing.
   */
  loopVariableOf(node) {
    const initializer = node.initializer;
    if (!initializer) return undefined;
    if (isVariableDeclaration(initializer)) return initializer;
    if (isVariableDeclarationList(initializer)) {
      return (initializer.declarations ?? [])[0];
    }
    return undefined;
  }

  /**
   * A `for ... of` loop.
   *
   * <p>The loop variable is bound to the ELEMENT, not to the iterable: the
   * iterable is a sequence of elements, and the value the body works with is
   * one of them. The iteration itself is the language's own protocol, so it
   * is lowered as the protocol's calls.
   */
  lowerForOf(node) {
    const iterable = this.lowerExpression(node.expression);
    const iterator = this.state.newTemp(TYPE.UNKNOWN);
    this.emit({
      kind: KIND.CALL,
      calleeSig: "Iterator[Symbol.iterator]/1",
      dest: iterator,
      args: [iterable],
      ...this.position(node),
    });
    const headLabel = this.state.newLabel("LOOP_HEAD");
    const bodyLabel = this.state.newLabel();
    const endLabel = this.state.newLabel("LOOP_END");
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(headLabel);
    const step = this.state.newTemp(TYPE.UNKNOWN);
    this.emit({
      kind: KIND.CALL,
      calleeSig: "Iterator.next/0",
      dest: step,
      args: [iterator],
      ...this.position(node),
    });
    const done = this.state.newTemp(TYPE.BOOLEAN);
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest: done,
      lhs1: step,
      lhs2: constOp(CONST.STRING, "done"),
      ...this.position(node),
    });
    this.emitBranch(done, endLabel, bodyLabel, truthiness(done), node);
    this.emitLabel(bodyLabel);
    const item = this.state.newTemp(TYPE.UNKNOWN);
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest: item,
      lhs1: step,
      lhs2: constOp(CONST.STRING, "value"),
      ...this.position(node),
    });
    const loopVariable = this.loopVariableOf(node);
    if (loopVariable) {
      const name = loopVariable.name && loopVariable.name.text ? loopVariable.name.text : "v";
      const variable = varOp(name, this.typeOf(loopVariable));
      this.declareLocal(variable);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: item,
        ...this.position(node),
      });
    }    this.state.scopes.push({ kind: "LOOP", endLabel, headLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.state.scopes.pop();
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(endLabel);
  }

  /**
   * A `for ... in` loop.
   *
   * <p>The loop variable is bound to the KEY: the names of the properties
   * are what the loop walks over, and the values are read out of the object
   * by those names.
   *
   * <p>The keys are walked with an index, so that the loop ends: a loop that
   * always reads the FIRST key is a loop that never ends, and the engine
   * would see a program that never gets past its own `for`.
   */
  lowerForIn(node) {
    const object = this.lowerExpression(node.expression);
    const keys = this.state.newTemp({ kind: TYPE.ARRAY, component: { kind: TYPE.STRING } });
    this.emit({
      kind: KIND.CALL,
      calleeSig: "Object.keys/1",
      dest: keys,
      args: [object],
      ...this.position(node),
    });
    const length = this.state.newTemp(TYPE.INT);
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest: length,
      lhs1: keys,
      lhs2: constOp(CONST.STRING, "length"),
      ...this.position(node),
    });
    const index = this.state.newTemp(TYPE.INT);
    this.emit({
      kind: KIND.MOVE,
      dest: index,
      lhs1: constOp(CONST.INT, 0),
      ...this.position(node),
    });
    const headLabel = this.state.newLabel("LOOP_HEAD");
    const bodyLabel = this.state.newLabel();
    const endLabel = this.state.newLabel("LOOP_END");
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(headLabel);
    const comparison = this.state.newTemp(TYPE.BOOLEAN);
    this.emit({
      kind: KIND.BINOP,
      dest: comparison,
      lhs1: index,
      lhs2: length,
      binop: "LT",
      ...this.position(node),
    });
    this.emitBranch(
      comparison,
      bodyLabel,
      endLabel,
      { kind: "Compare", lhs: index, op: "LT", rhs: length },
      node,
    );
    this.emitLabel(bodyLabel);
    const key = this.state.newTemp({ kind: TYPE.STRING });
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest: key,
      lhs1: keys,
      lhs2: index,
      ...this.position(node),
    });
    const loopVariable = this.loopVariableOf(node);
    if (loopVariable) {
      const name = loopVariable.name && loopVariable.name.text ? loopVariable.name.text : "v";
      const variable = varOp(name, this.typeOf(loopVariable));
      this.declareLocal(variable);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: key,
        ...this.position(node),
      });
    }
    const next = this.state.newTemp(TYPE.INT);
    this.emit({
      kind: KIND.BINOP,
      dest: next,
      lhs1: index,
      lhs2: constOp(CONST.INT, 1),
      binop: "ADD",
      ...this.position(node),
    });
    this.emit({
      kind: KIND.MOVE,
      dest: index,
      lhs1: next,
      ...this.position(node),
    });
    this.state.scopes.push({ kind: "LOOP", endLabel, headLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.state.scopes.pop();
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(endLabel);
  }

  /**
   * A switch.
   *
   * <p>The cases are DISPATCHED, not run in order: each case is reached by a
   * branch that compares the discriminant with the case's value, and the
   * default is reached only when no case matched. A case that does not end
   * with a break falls through into the next one, which is what the language
   * does.
   */
  lowerSwitch(node) {
    const discriminant = this.lowerExpression(node.expression);
    const clauses = node.caseBlock?.clauses ?? [];
    const caseClauses = clauses.filter((clause) => clause.expression !== undefined);
    const defaultClause = clauses.find((clause) => clause.expression === undefined);
    const bodyLabels = [];
    const endLabel = this.state.newLabel("SWITCH_END");

    for (const clause of caseClauses) {
      const value = this.lowerExpression(clause.expression);
      const bodyLabel = this.state.newLabel("SWITCH_CASE");
      bodyLabels.push(bodyLabel);
      const nextLabel = this.state.newLabel();
      this.emitBranch(
        discriminant,
        bodyLabel,
        nextLabel,
        { kind: "Compare", lhs: discriminant, op: "EQ", rhs: value },
        node,
      );
      this.emitLabel(nextLabel);
    }

    // No case matched: the default, if the switch has one.
    const defaultEntry = this.state.newLabel();
    this.emit({ kind: KIND.JUMP, label: defaultEntry });
    this.emitLabel(defaultEntry);
    const defaultLabel = this.state.newLabel("SWITCH_CASE");
    this.emit({ kind: KIND.JUMP, label: defaultLabel });

    this.state.scopes.push({ kind: "SWITCH", endLabel });
    for (let index = 0; index < caseClauses.length; index += 1) {
      this.emitLabel(bodyLabels[index]);
      for (const statement of caseClauses[index].statements ?? []) {
        this.lowerStatement(statement);
      }
    }
    this.emitLabel(defaultLabel);
    if (defaultClause) {
      for (const statement of defaultClause.statements ?? []) {
        this.lowerStatement(statement);
      }
    }
    this.state.scopes.pop();
    this.emitLabel(endLabel);
  }

  /**
   * A try / catch / finally.
   *
   * <p>The IR has explicit instructions for this: a CATCH_HANDLER binds the
   * catch parameter to the exception, and a FINALLY_ENTER marks the block
   * every exit of the region runs through.
   */
  lowerTry(node) {
    const tryLabel = this.state.newLabel("BLOCK");
    const hasFinally = !!node.finallyBlock;
    const finallyLabel = hasFinally ? this.state.newLabel("FINALLY_ENTRY") : undefined;
    const endLabel = this.state.newLabel();
    const catchLabels = [];
    const catchTypes = [];
    const finallyExits = [];

    this.emitLabel(tryLabel);
    const tryStart = this.state.instructions.length;
    if (node.tryBlock) this.lowerBlock(node.tryBlock);
    const tryEnd = this.state.instructions.length;
    const tryExit = finallyLabel ?? endLabel;
    this.emit({ kind: KIND.JUMP, label: tryExit });
    finallyExits.push(tryExit);

    let catchStart = -1;
    let catchEnd = -1;
    for (const clause of this.catchClausesOf(node)) {
      const parameter = clause.variableDeclaration;
      const name = parameter && parameter.name ? parameter.name.text : undefined;
      const label = this.state.newLabel(name ? `CATCH_${name}` : "CATCH", name);
      catchLabels.push(label);
      catchTypes.push(parameter && parameter.type ? this.typeTextOf(parameter.type) : "*");
      this.emitLabel(label);
      if (catchStart < 0) {
        catchStart = this.state.instructions.length;
      }
      if (name) {
        const variable = varOp(name, this.typeOf(parameter));
        this.declareLocal(variable);
        this.emit({
          kind: KIND.CATCH_HANDLER,
          dest: variable,
          ctorType: parameter && parameter.type ? this.typeOf(parameter.type) : undefined,
          ...this.position(clause),
        });
      } else if (parameter && parameter.name) {
        // A catch parameter may be a PATTERN: `catch ({ message })`. The
        // pattern has no name of its own, and the caught value reaches the
        // names through the pattern. Without this the caught value was
        // dropped and the names were never assigned -- and the catch body
        // read a variable that nothing had ever written.
        const caught = this.state.newTemp(this.typeOf(parameter));
        this.declareLocal(caught);
        this.emit({
          kind: KIND.CATCH_HANDLER,
          dest: caught,
          ...this.position(clause),
        });
        this.bindPattern(parameter.name, caught, parameter);
      } else {
        this.emit({ kind: KIND.CATCH_HANDLER, ...this.position(clause) });
      }
      if (clause.block) this.lowerBlock(clause.block);
      this.emit({ kind: KIND.JUMP, label: tryExit });
      finallyExits.push(tryExit);
      catchEnd = this.state.instructions.length;
    }

    let finallyStart = -1;
    let finallyEnd = -1;
    if (finallyLabel) {
      this.emitLabel(finallyLabel);
      this.emit({ kind: KIND.FINALLY_ENTER, label: finallyLabel, ...this.position(node) });
      finallyStart = this.state.instructions.length;
      if (node.finallyBlock) this.lowerBlock(node.finallyBlock);
      finallyEnd = this.state.instructions.length;
    }
    this.emitLabel(endLabel);

    this.state.tryRegions.push({
      tryStart,
      tryEnd,
      catchStart,
      catchEnd,
      catchLabels,
      catchTypes,
      finallyLabel: finallyLabel ?? null,
      finallyStart,
      finallyEnd,
      syntheticFinally: false,
      finallyExits,
      resourceVars: [],
    });
  }

  /** The catch clauses of a try statement, whether there is one or several. */
  catchClausesOf(node) {
    const clause = node.catchClause;
    if (!clause) return [];
    return Array.isArray(clause) ? clause : [clause];
  }

  /**
   * A labelled statement.
   *
   * <p>The label itself produces no instruction: it gives the statement's
   * end a name, so that a `break label` can jump to it.
   */
  lowerLabels(node) {
    const name = node.label ? node.label.text : undefined;
    const endLabel = this.state.newLabel("LABEL", name);
    this.state.scopes.push({ kind: "LABEL", name, endLabel });
    if (node.statement) this.lowerStatement(node.statement);
    this.state.scopes.pop();
    this.emitLabel(endLabel);
  }

  /**
   * A break or a continue.
   *
   * <p>Both are jumps: the loop's own structure carries the control flow, and
   * the jump says which edge is taken.
   */
  lowerJump(node) {
    const target = this.jumpTargetOf(node);
    if (!target) {
      this.report(PROBLEM.SOURCE_LOWERED, node, "this break or continue has no target");
      return;
    }
    this.emit({ kind: KIND.JUMP, label: target.label, ...this.position(node) });
  }

  /** The label a break or a continue jumps to. */
  jumpTargetOf(node) {
    const isBreak = isBreakStatement(node);
    const name = node.label ? node.label.text : undefined;
    const scopes = this.state.scopes;
    if (name) {
      const index = scopes.findIndex((scope) => scope.kind === "LABEL" && scope.name === name);
      if (index < 0) return undefined;
      if (isBreak) {
        return { label: scopes[index].endLabel };
      }
      // A labelled continue continues the loop the label is ON. The label's
      // scope is pushed BEFORE the statement the label decorates, so the loop
      // it names is the FIRST one after it -- not the innermost one, which is
      // a loop nested inside it and the wrong loop entirely.
      for (let scan = index + 1; scan < scopes.length; scan += 1) {
        if (scopes[scan].kind === "LOOP") {
          return { label: scopes[scan].headLabel };
        }
      }
      return undefined;
    }
    for (let scan = scopes.length - 1; scan >= 0; scan -= 1) {
      const scope = scopes[scan];
      if (isBreak) {
        if (scope.kind === "LOOP" || scope.kind === "SWITCH") {
          return { label: scope.endLabel };
        }
      } else if (scope.kind === "LOOP") {
        return { label: scope.headLabel };
      }
    }
    return undefined;
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
      const value = this.lowerExpression(node.expression);
      this.emitDrop(
        "await",
        "the micro-task timing of an await is not in the TAC; the value itself is complete",
        IMPACT.SEMANTICS,
        node,
      );
      return value;
    }
    if (isTypeOfExpression(node)) {
      const value = this.lowerExpression(node.expression);
      this.emitDrop(
        "typeof",
        "the result of typeof is one of eight strings decided at run time",
        IMPACT.VALUE,
        node,
      );
      return value;
    }
    if (node.kind === SyntaxKind.ThisKeyword) {
      // The receiver of an object's own property. It is the one object the
      // property accesses are keyed by.
      this.declareThis();
      return varOp("this", TYPE.UNKNOWN);
    }
    if (isSuperExpression(node)) {
      // `super` is the object itself: the base class's member is read from
      // the same object.
      this.declareThis();
      return varOp("this", TYPE.UNKNOWN);
    }
    if (isNonNullExpression(node)) {
      // A non-null assertion is a compile-time claim: it produces no
      // instruction at all.
      return this.lowerExpression(node.expression);
    }
    if (isAsExpression(node) || isSatisfiesExpression(node) || isTypeAssertion(node)) {
      // A type assertion is a compile-time claim too: the value is the
      // value, and the type is the type it is asserted to have.
      return this.lowerExpression(node.expression);
    }
    if (isPropertyAccessExpression(node)) {
      if (node.questionDotToken) {
        return this.lowerOptionalChain(node);
      }
      return this.lowerPropertyRead(node);
    }
    if (isElementAccessExpression(node)) {
      if (node.questionDotToken) {
        return this.lowerOptionalChain(node);
      }
      return this.lowerElementRead(node);
    }
    if (isCallExpression(node)) {
      if (node.questionDotToken) {
        return this.lowerOptionalChain(node);
      }
      if (node.expression && node.expression.kind === SyntaxKind.ImportKeyword) {
        // A dynamic import: the compiler parses it as a call whose callee is
        // the `import` keyword.
        return this.lowerDynamicImport(node);
      }
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
    if (isConditionalExpression(node)) {
      return this.lowerConditional(node);
    }
    if (isVoidExpression(node)) {
      return this.lowerVoid(node);
    }
    if (isTaggedTemplateExpression(node)) {
      return this.lowerTaggedTemplate(node);
    }
    if (isRegularExpressionLiteral(node)) {
      // A regular expression is an immutable constant.
      return constOp(CONST_REGEX, node.text);
    }
    if (isMetaProperty(node)) {
      return this.lowerImportMeta(node);
    }
    if (isImportExpression(node)) {
      return this.lowerDynamicImport(node);
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
    if (isSpreadElement(node)) {
      return this.lowerExpression(node.expression);
    }
    if (isJsxElement(node) || isJsxSelfClosingElement(node) || isJsxFragment(node)) {
      return this.lowerJsx(node);
    }
    if (isClassExpression(node)) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "a class expression has no TAC lowering");
      return this.emitHavoc(node);
    }
    if (isArrowFunction(node) || isFunctionExpression(node)) {
      // A function used as a value. The TAC has no closure, so the function
      // becomes an unknown value.
      this.report(PROBLEM.SOURCE_LOWERED, node, "a function value has no TAC lowering");
      this.emitDrop(
        "closure",
        "a function used as a value has no TAC lowering; the value is unknown",
        IMPACT.STRUCTURE,
        node,
      );
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
  lowerPropertyRead(node, receiver) {
    if (isSuperExpression(node.expression)) {
      // `super.prop` reads the BASE class's member -- and that member is an
      // entry of THIS instance's namespace, because the base class's field
      // initialiser wrote it there. So it is the location `this.prop` reads,
      // and it is read the same way.
      const dest = this.state.newTemp(this.typeOf(node));
      this.emit({
        kind: KIND.ARRAYLOAD,
        dest,
        lhs1: varOp("this", TYPE.UNKNOWN),
        lhs2: constOp(CONST.STRING, node.name.text),
        ...this.position(node),
      });
      return dest;
    }
    const base = receiver ?? this.lowerExpression(node.expression);
    const key = constOp(CONST.STRING, node.name.text);
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest,
      lhs1: base,
      lhs2: key,
      ...this.position(node),
    });
    return dest;
  }

  /** A computed read: `obj[key]`. The key is a value the analysis may not resolve. */
  lowerElementRead(node, receiver) {
    const base = receiver ?? this.lowerExpression(node.expression);
    const key = this.lowerExpression(node.argumentExpression);
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.ARRAYLOAD,
      dest,
      lhs1: base,
      lhs2: key,
      ...this.position(node),
    });
    return dest;
  }

  /**
   * An optional chain: `a?.b`, `a?.[k]` and `a?.()`.
   *
   * <p>The receiver is checked FIRST: when it is nullish, the read or the
   * call is not emitted at all and the value of the whole expression is
   * undefined.
   */
  lowerOptionalChain(node) {
    const receiver = this.lowerExpression(node.expression);
    const dest = this.state.newTemp(this.typeOf(node));
    const useLabel = this.state.newLabel();
    const nullLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emitBranch(
      receiver,
      useLabel,
      nullLabel,
      { kind: "Compare", lhs: receiver, op: "NE", rhs: constOp(CONST.NULL, null) },
      node,
    );
    this.emitLabel(useLabel);
    let value;
    if (isPropertyAccessExpression(node)) {
      value = this.lowerPropertyRead(node, receiver);
    } else if (isElementAccessExpression(node)) {
      value = this.lowerElementRead(node, receiver);
    } else {
      // `rec.f?.()`: the check above is on the FUNCTION -- it is the function
      // that may be missing -- but the call's RECEIVER is the object the
      // function was read from, because that is what `this` is. Passing the
      // function value as the receiver made the call look like a method of
      // the function itself.
      const object =
        node.expression && isPropertyAccessExpression(node.expression)
          ? this.lowerExpression(node.expression.expression)
          : undefined;
      value = this.lowerCall(node, object);
    }
    this.emit({ kind: KIND.MOVE, dest, lhs1: value, ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emitLabel(nullLabel);
    this.emit({
      kind: KIND.MOVE,
      dest,
      lhs1: constOp(CONST.NULL, null),
      ...this.position(node),
    });
    this.emitLabel(endLabel);
    return dest;
  }

  /**
   * A call.
   *
   * The receiver slot is reserved whether or not the call has a receiver
   * object: `args[0]` is the receiver, `args[1..]` are the arguments. A free
   * function has no receiver object, so the slot holds the OWNER's name as a
   * constant -- the same shape the other front ends use for a static call.
   */
  lowerCall(node, receiver) {
    const signature = this.resolveSignature(node);
    const args = node.arguments ?? [];
    const wireArgs = [];
    const owner = this.ownerOf(node);
    let slot;
    if (isSuperExpression(node.expression)) {
      // `super(...)`: the base class's constructor, called on THIS object.
      slot = varOp("this", TYPE.UNKNOWN);
    } else if (node.expression && isPropertyAccessExpression(node.expression)) {
      slot = receiver ?? this.lowerExpression(node.expression.expression);
    } else {
      // A free function has no receiver object, so the slot holds the OWNER
      // as a constant: the module the function was imported from. It is the
      // same shape the other front ends use for a static call.
      slot = constOp(CONST.STRING, owner ?? "");
    }
    wireArgs.push(slot);
    for (const argument of args) {
      wireArgs.push(this.lowerExpression(argument));
    }
    // An IIFE: the callee is a function literal, and its body is a function of
    // its own. Lifting it gives the call a callee the engine can resolve, and
    // puts the body's instructions into the TAC.
    const inline = this.liftInlineFunction(node);
    const method = inline ? inline.name : this.methodNameOf(node);
    const arity = inline ? inline.arity : this.arityOf(signature, args);
    const dest = this.state.newTemp(this.typeOf(node));
    const kind = inline || signature ? KIND.CALL : KIND.UNINTERPRETED;
    if (!signature && !inline && !isSuperExpression(node.expression)) {
      this.emitDrop(
        "unknown-callee",
        "the callee could not be resolved, so the call is modelled as a black box",
        IMPACT.SEMANTICS,
        node,
      );
    }
    this.emit({
      kind,
      calleeSig: calleeSigOf(inline ? this.moduleName : owner, method, arity),
      dest,
      args: wireArgs,
      ...this.position(node),
    });
    if (this.isEvalCall(node)) {
      // `eval` computes its result at run time: the argument is evaluated, but
      // the value that comes back is not knowable here.
      this.emitDrop(
        "eval-call",
        "the value eval returns is computed at run time",
        IMPACT.VALUE,
        node,
      );
      return this.emitHavoc(node);
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
    if (this.constructedClassName(node.expression) === "Function") {
      // The body of a function built from a string is decided at run time.
      this.emitDrop(
        "new-function",
        "the body of a function built from a string is decided at run time",
        IMPACT.VALUE,
        node,
      );
    }
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
    const logical = LOGICAL_COMPOUND_OPERATORS[operatorKind];
    if (logical) {
      return this.lowerLogicalAssignment(node, logical);
    }
    const compound = COMPOUND_OPERATORS[operatorKind];
    if (compound) {
      return this.lowerAssignment(node, compound);
    }
    if (operatorKind === SyntaxKind.InstanceOfKeyword) {
      return this.lowerInstanceOf(node);
    }
    if (operatorKind === SyntaxKind.InKeyword) {
      return this.lowerIn(node);
    }
    if (operatorKind === SyntaxKind.CommaToken) {
      return this.lowerComma(node);
    }
    if (
      operatorKind === SyntaxKind.AmpersandAmpersandToken ||
      operatorKind === SyntaxKind.BarBarToken ||
      operatorKind === SyntaxKind.QuestionQuestionToken
    ) {
      return this.lowerShortCircuit(node);
    }
    const operator = BINARY_OPERATORS[operatorKind];
    const approximated = APPROXIMATED_OPERATORS[operatorKind];
    let binop = operator;
    if (approximated) {
      binop = approximated.binop;
      this.report(PROBLEM.SOURCE_LOWERED, node, approximated.note);
      this.emitDrop("approximated-operator", approximated.note, IMPACT.STRUCTURE, node);
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
   * A short-circuit operator: `a && b`, `a || b` and `a ?? b`.
   *
   * <p>The right-hand side is evaluated ONLY when the left-hand side says so,
   * and the value of the expression is defined on both paths: it is the left
   * operand when the right one is not evaluated, and the right one when it
   * is.
   */
  lowerShortCircuit(node) {
    const operatorKind = node.operatorToken.kind;
    const left = this.lowerExpression(node.left);
    const dest = this.state.newTemp(this.typeOf(node));
    // The result is defined before the branch: both paths assign to it.
    this.emit({ kind: KIND.MOVE, dest, lhs1: left, ...this.position(node) });
    const rhsLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    if (operatorKind === SyntaxKind.AmpersandAmpersandToken) {
      this.emitBranch(left, rhsLabel, endLabel, truthiness(left), node);
    } else if (operatorKind === SyntaxKind.BarBarToken) {
      this.emitBranch(left, endLabel, rhsLabel, truthiness(left), node);
    } else {
      this.emitBranch(
        left,
        endLabel,
        rhsLabel,
        { kind: "Compare", lhs: left, op: "NE", rhs: constOp(CONST.NULL, null) },
        node,
      );
    }
    this.emitLabel(rhsLabel);
    const right = this.lowerExpression(node.right);
    this.emit({ kind: KIND.MOVE, dest, lhs1: right, ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emitLabel(endLabel);
    return dest;
  }

  /**
   * A logical compound assignment: `a &&= b`, `a ||= b` and `a ??= b`.
   *
   * <p>The right-hand side is evaluated only when it is needed, and the value
   * of the expression is the value of the left-hand side after the write.
   */
  lowerLogicalAssignment(node, operator) {
    const target = node.left;
    const current = this.lowerExpression(target);
    const dest = this.state.newTemp(this.typeOf(node));
    const rhsLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    if (operator === "LAND") {
      this.emitBranch(current, rhsLabel, endLabel, truthiness(current), node);
    } else if (operator === "LOR") {
      this.emitBranch(current, endLabel, rhsLabel, truthiness(current), node);
    } else {
      this.emitBranch(
        current,
        endLabel,
        rhsLabel,
        { kind: "Compare", lhs: current, op: "NE", rhs: constOp(CONST.NULL, null) },
        node,
      );
    }
    this.emitLabel(rhsLabel);
    const right = this.lowerExpression(node.right);
    this.lowerWrite(target, right, node);
    this.emit({ kind: KIND.MOVE, dest, lhs1: right, ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emitLabel(endLabel);
    this.emit({ kind: KIND.MOVE, dest, lhs1: current, ...this.position(node) });
    return dest;
  }

  /** `x instanceof Y`: a type test. */
  lowerInstanceOf(node) {
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

  /**
   * `k in obj`: the property is there or it is not.
   *
   * <p>The TAC has no "presence" opcode, so the answer is a branch: one arm
   * for "the property is there" and one for "it is not".
   */
  lowerIn(node) {
    // `k in obj`: the KEY is on the left, the object on the right.
    const key = this.lowerExpression(node.left);
    const object = this.lowerExpression(node.right);
    const dest = this.state.newTemp(TYPE.BOOLEAN);
    const hasLabel = this.state.newLabel();
    const hasNotLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emitBranch(
      key,
      hasLabel,
      hasNotLabel,
      { kind: "PropertyPresence", receiver: object, key },
      node,
    );
    this.emitLabel(hasLabel);
    this.emit({ kind: KIND.MOVE, dest, lhs1: constOp(CONST.BOOL, true), ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emitLabel(hasNotLabel);
    this.emit({ kind: KIND.MOVE, dest, lhs1: constOp(CONST.BOOL, false), ...this.position(node) });
    this.emitLabel(endLabel);
    return dest;
  }

  /** `(a, b)`: both are evaluated, and only the last one is the result. */
  lowerComma(node) {
    this.lowerExpression(node.left);
    const right = this.lowerExpression(node.right);
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({ kind: KIND.MOVE, dest, lhs1: right, ...this.position(node) });
    return dest;
  }

  /** `c ? a : b`: two branches, and each side is evaluated once. */
  lowerConditional(node) {
    const condition = this.lowerExpression(conditionOf(node));
    const dest = this.state.newTemp(this.typeOf(node));
    const thenLabel = this.state.newLabel();
    const elseLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emitBranch(condition, thenLabel, elseLabel, truthiness(condition), node);
    this.emitLabel(thenLabel);
    const whenTrue = this.lowerExpression(node.whenTrue);
    this.emit({ kind: KIND.MOVE, dest, lhs1: whenTrue, ...this.position(node) });
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emitLabel(elseLabel);
    const whenFalse = this.lowerExpression(node.whenFalse);
    this.emit({ kind: KIND.MOVE, dest, lhs1: whenFalse, ...this.position(node) });
    this.emitLabel(endLabel);
    return dest;
  }

  /** `void x`: the operand is evaluated, and the result is undefined. */
  lowerVoid(node) {
    this.lowerExpression(node.expression);
    const dest = this.state.newTemp(TYPE.VOID);
    this.emit({
      kind: KIND.MOVE,
      dest,
      lhs1: constOp(CONST.NULL, null),
      ...this.position(node),
    });
    return dest;
  }

  /**
   * A tagged template: `tag`a${x}b``.
   *
   * <p>The tag is called with the static parts as an array and the
   * interpolated values after it.
   */
  lowerTaggedTemplate(node) {
    const template = node.template;
    const parts = [];
    if (template && template.head && template.head.text) {
      parts.push(template.head.text);
    }
    const holes = [];
    for (const span of template.templateSpans ?? []) {
      holes.push(this.lowerExpression(span.expression));
      if (span.literal && span.literal.text) {
        parts.push(span.literal.text);
      }
    }
    const arrayType = { kind: TYPE.ARRAY, component: { kind: TYPE.STRING } };
    const strings = this.state.newTemp(arrayType);
    this.emit({
      kind: KIND.NEWARRAY,
      dest: strings,
      ctorType: arrayType,
      lhs1: constOp(CONST.INT, parts.length),
      ...this.position(node),
    });
    parts.forEach((text, index) => {
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: strings,
        lhs2: constOp(CONST.INT, index),
        dest: constOp(CONST.STRING, text),
        ...this.position(node),
      });
    });
    const owner = this.ownerOfExpression(node.tag);
    const method = this.methodNameOfExpression(node.tag);
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.CALL,
      calleeSig: calleeSigOf(owner, method, 1 + holes.length),
      dest,
      args: [constOp(CONST.STRING, owner ?? ""), strings, ...holes],
      ...this.position(node),
    });
    return dest;
  }

  /**
   * A JSX element: one call, with the children and the attributes as the
   * arguments.
   */
  lowerJsx(node) {
    const opening = isJsxElement(node)
      ? node.openingElement
      : isJsxSelfClosingElement(node)
        ? node
        : node.openingFragment;
    const tagName = opening && opening.tagName ? opening.tagName.getText?.() : undefined;
    const args = [];
    for (const attribute of opening?.attributes ?? []) {
      if (attribute.initializer) {
        args.push(this.lowerExpression(attribute.initializer));
      }
    }
    for (const child of node.children ?? []) {
      if (child.expression) {
        args.push(this.lowerExpression(child.expression));
        continue;
      }
      const text = child.text ?? child.getText?.();
      if (typeof text === "string") {
        args.push(constOp(CONST.STRING, text));
      }
    }
    const owner = tagName ?? "<jsx>";
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.CALL,
      calleeSig: calleeSigOf(owner, owner, args.length),
      dest,
      args: [constOp(CONST.STRING, owner), ...args],
      ...this.position(node),
    });
    return dest;
  }

  /**
   * `import.meta`: one object per module.
   *
   * <p>The object is built by a synthetic function of the module's own, and
   * every use of it calls that function: the TAC has no global variable slot,
   * so this is the only shape that says "one object, shared by the module".
   */
  lowerImportMeta(node) {
    if (!this.metaFunction) {
      this.lowerNested(node, () => {
        const state = this.beginFunction("__meta", `${this.moduleName}.__meta()`, null);
        state.synthetic = true;
        this.guard(node, () => {
          const object = this.state.newTemp({ kind: TYPE.CLASS, name: "Object" });
          this.emit({
            kind: KIND.NEW,
            dest: object,
            ctorType: { kind: TYPE.CLASS, name: "Object" },
            calleeSig: "Object.<init>/0",
            args: [],
            ...this.position(node),
          });
          for (const property of this.metaProperties) {
            const value = this.emitHavoc(node);
            this.emit({
              kind: KIND.ARRAYSTORE,
              lhs1: object,
              lhs2: constOp(CONST.STRING, property),
              dest: value,
              ...this.position(node),
            });
          }
          this.emit({ kind: KIND.RETURN, lhs1: object, ...this.position(node) });
          this.emitDrop(
            "import-meta",
            "the values of import.meta are decided at run time",
            IMPACT.VALUE,
            node,
          );
        });
        this.metaFunction = this.finishFunction(state);
      });
    }
    return this.callMetaFunction(node);
  }

  /** The synthetic function that builds the module's `import.meta` object. */
  lowerMetaFunction() {
    if (this.metaFunction) {
      return [this.metaFunction];
    }
    return [];
  }

  /** Call the module's `import.meta` function. */
  callMetaFunction(node) {
    const dest = this.state.newTemp({ kind: TYPE.CLASS, name: "Object" });
    this.emit({
      kind: KIND.CALL,
      calleeSig: `${this.moduleName}.__meta/0`,
      dest,
      args: [constOp(CONST.STRING, this.moduleName)],
      ...this.position(node),
    });
    return dest;
  }

  /**
   * A dynamic import: `import(specifier)`.
   *
   * <p>A literal specifier names the module, so the call can name it too. A
   * specifier that is only known at run time cannot: the key then has no
   * owner section, which says "the target is not nameable" rather than
   * naming the wrong module.
   */
  lowerDynamicImport(node) {
    const argument = node.argument ?? (node.arguments ?? [])[0];
    const literal = argument && isStringLiteral(argument) ? argument.text : undefined;
    const specifier = this.lowerExpression(argument);
    const dest = this.state.newTemp(TYPE.UNKNOWN);
    this.emit({
      kind: KIND.CALL,
      calleeSig: literal ? `${literal}.import/1` : "import/1",
      dest,
      args: [constOp(CONST.STRING, literal ?? ""), specifier],
      ...this.position(node),
    });
    if (!literal) {
      this.emitDrop(
        "dynamic-import",
        "the module a dynamic import names is decided at run time",
        IMPACT.SEMANTICS,
        node,
      );
    }
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
        this.emitDrop("approximated-operator", compound.note, IMPACT.STRUCTURE, node);
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
    if (isObjectBindingPattern(target) || isArrayBindingPattern(target)) {
      // An assignment to a pattern binds the names that already exist.
      this.bindPattern(target, value, node);
      return;
    }
    this.report(PROBLEM.UNHANDLED_NODE, target, "this assignment target has no TAC lowering");
  }

  lowerUnary(node) {
    if (node.operator === SyntaxKind.PlusPlusToken || node.operator === SyntaxKind.MinusMinusToken) {
      // A prefix `++` changes the variable FIRST and yields the new value.
      const value = this.lowerExpression(node.operand);
      const updated = this.state.newTemp(this.typeOf(node));
      this.emit({
        kind: KIND.UNARYOP,
        dest: updated,
        lhs1: value,
        unop: node.operator === SyntaxKind.PlusPlusToken ? "PRE_INC" : "PRE_DEC",
        ...this.position(node),
      });
      this.lowerWrite(node.operand, updated, node);
      return updated;
    }
    const operator = UNARY_OPERATORS[node.operator];
    const operand = this.lowerExpression(node.operand);
    if (!operator) {
      if (node.operator === SyntaxKind.PlusToken) {
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
    if (node.operator !== SyntaxKind.PlusPlusToken && node.operator !== SyntaxKind.MinusMinusToken) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const before = this.lowerExpression(node.operand);
    const after = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.UNARYOP,
      dest: after,
      lhs1: before,
      unop: node.operator === SyntaxKind.PlusPlusToken ? "POST_INC" : "POST_DEC",
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
      if (isSpreadAssignment(property)) {
        this.lowerObjectSpread(property, dest);
        continue;
      }
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

  /**
   * A spread in an object literal: `{ ...other }`.
   *
   * <p>The spread copies the source's OWN properties into the object being
   * built. An object in this TAC is a NAMESPACE -- a set of key/value pairs --
   * and the source's KEYS are not known here, so the copy cannot be written
   * out property by property. It is modelled as the call that performs it:
   * `Object.assign(target, source)`.
   *
   * <p>The call is UNINTERPRETED on purpose. The engine cannot see inside
   * `Object.assign`, and an unknown call is assumed to carry its arguments'
   * taint into its result -- which is what puts the source's taint into the
   * new object. As an ordinary CALL the source would have been dropped: the
   * spread's properties were simply not there, and the object came out clean.
   */
  lowerObjectSpread(node, target) {
    const source = this.lowerExpression(node.expression);
    const merged = this.state.newTemp(TYPE.UNKNOWN);
    this.emit({
      kind: KIND.UNINTERPRETED,
      calleeSig: "Object.assign/2",
      dest: merged,
      args: [constOp(CONST.STRING, "Object"), target, source],
      ...this.position(node),
    });
    this.emit({
      kind: KIND.MOVE,
      dest: target,
      lhs1: merged,
      ...this.position(node),
    });
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

  // ---- the class's own members ---------------------------------------------------------------

  /**
   * The field initialisers of a class, as writes to the object's namespace.
   *
   * <p>A class field IS an entry of the instance's namespace: `this.value` and
   * `this["value"]` are the same location, and that is the location every
   * other access to the member uses. So the initialiser is a keyed store --
   * an ARRAYSTORE whose key is the member's name -- and not a field write.
   *
   * <p>It used to be a FIELDSTORE, and that gave ONE location two mechanisms:
   * the declaration wrote it as a field while every read of it was an
   * ARRAYLOAD on the namespace. The two never met, so a tainted initialiser
   * never reached the place that reads the member -- and the engine's
   * "assigned but never read" rule, which watches field writes, reported
   * members that were read all the time.
   *
   * <p>A STATIC field belongs to the class rather than to an instance, so it
   * is written on the class: `Shapes.counter = 1` writes the entry "counter"
   * of the class object, and that is where the read `Shapes.counter` looks.
   */
  lowerFieldInitializers(fields, className) {
    for (const field of fields) {
      if (!field.initializer) {
        continue;
      }
      const name = this.fieldNameOf(field);
      const value = this.lowerExpression(field.initializer);
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: this.isStaticField(field)
          ? varOp(className, TYPE.UNKNOWN)
          : varOp("this", TYPE.UNKNOWN),
        lhs2: constOp(CONST.STRING, name),
        dest: value,
        ...this.position(field),
      });
      const annotations = this.fieldAnnotationsOf(field);
      if (annotations.length > 0) {
        this.state.fieldAnnotations[name] = annotations;
      }
    }
  }

  /** Whether a field is a static one: it belongs to the class, not to an instance. */
  isStaticField(field) {
    const modifiers = field.modifiers ?? [];
    return modifiers.some((modifier) => modifier.kind === SyntaxKind.StaticKeyword);
  }

  /** The parameter properties of a constructor: members written from parameters. */
  lowerParameterProperties(node, className) {
    for (const parameter of node.parameters ?? []) {
      if (!this.isParameterProperty(parameter)) {
        continue;
      }
      const name = parameter.name.text;
      this.emit({
        kind: KIND.ARRAYSTORE,
        lhs1: varOp("this", TYPE.UNKNOWN),
        lhs2: constOp(CONST.STRING, name),
        dest: varOp(name, this.typeOf(parameter)),
        ...this.position(node),
      });
    }
  }

  /** The name of a field, with the `#` of a private field kept. */
  fieldNameOf(field) {
    const name = field.name;
    if (!name) return "";
    if (isPrivateIdentifier(name)) return `#${name.text.replace(/^#/, "")}`;
    return name.text ?? name.getText?.() ?? "";
  }

  /** Whether a parameter is a parameter property. */
  isParameterProperty(parameter) {
    const modifiers = parameter.modifiers ?? [];
    return modifiers.some((modifier) => {
      const kind = modifier.kind;
      return (
        kind === SyntaxKind.PublicKeyword ||
        kind === SyntaxKind.PrivateKeyword ||
        kind === SyntaxKind.ProtectedKeyword ||
        kind === SyntaxKind.ReadonlyKeyword ||
        kind === SyntaxKind.OverrideKeyword
      );
    });
  }

  /** Whether a field is an auto-accessor. */
  isAccessorField(field) {
    const modifiers = field.modifiers ?? [];
    return modifiers.some((modifier) => modifier.kind === SyntaxKind.AccessorKeyword);
  }

  /** The compile-time markers of a field: readonly, visibility, override. */
  fieldAnnotationsOf(field) {
    const annotations = [];
    for (const modifier of field.modifiers ?? []) {
      if (modifier.kind === SyntaxKind.ReadonlyKeyword) annotations.push("readonly");
      if (modifier.kind === SyntaxKind.PublicKeyword) annotations.push("public");
      if (modifier.kind === SyntaxKind.PrivateKeyword) annotations.push("private");
      if (modifier.kind === SyntaxKind.ProtectedKeyword) annotations.push("protected");
      if (modifier.kind === SyntaxKind.OverrideKeyword) annotations.push("override");
      if (isDecorator(modifier)) {
        const name = decoratorNameOf(modifier);
        if (name) annotations.push(name);
      }
    }
    return annotations;
  }

  /** The name of the base class, when the class extends one. */
  baseClassNameOf(node) {
    for (const clause of node.heritageClauses ?? []) {
      if (!isHeritageClause(clause)) continue;
      if (clause.token !== SyntaxKind.ExtendsKeyword) continue;
      const first = clause.types?.[0];
      const expression = first?.expression;
      if (!expression) return undefined;
      return expression.getText?.() ?? expression.text;
    }
    return undefined;
  }

  /** The interfaces the class implements. */
  implementedInterfacesOf(node) {
    const names = [];
    for (const clause of node.heritageClauses ?? []) {
      if (!isHeritageClause(clause)) continue;
      if (clause.token !== SyntaxKind.ImplementsKeyword) continue;
      for (const type of clause.types ?? []) {
        const expression = type.expression;
        const name = expression?.getText?.() ?? expression?.text;
        if (name) names.push(name);
      }
    }
    return names;
  }

  /** The names of the decorators on a node. */
  decoratorNamesOf(node) {
    const names = [];
    for (const modifier of node.modifiers ?? []) {
      if (!isDecorator(modifier)) continue;
      const name = decoratorNameOf(modifier);
      if (name) names.push(name);
    }
    return names;
  }

  /**
   * The decorators a node carries.
   *
   * <p>A decorator is a modifier of the thing it decorates, so it lives among
   * the modifiers: `readonly`, `static`, and the decorator itself.
   */
  decoratorNodesOf(node) {
    const decorators = [];
    for (const modifier of node?.modifiers ?? []) {
      if (isDecorator(modifier)) decorators.push(modifier);
    }
    return decorators;
  }

  /**
   * The decorator applications of a declaration.
   *
   * <p>An application is the decorator and the HOLDER of the thing it
   * decorates: the name that holds the decorated thing before the decorator
   * runs, and that holds the decorator's result afterwards.
   */
  decoratorApplicationsOf(node, holderName) {
    const applications = [];
    for (const decorator of this.decoratorNodesOf(node)) {
      applications.push({ decorator, holder: holderName });
    }
    return applications;
  }

  /**
   * The decorator applications of a class: its own, and those of its
   * members.
   *
   * <p>They all run where the CLASS is defined -- which is the class's own
   * stream: the constructor's, or the field initialiser's.
   */
  classDecoratorApplications(node, className) {
    const applications = this.decoratorApplicationsOf(node, className);
    for (const member of node.members ?? []) {
      const name = member.name?.text;
      if (!name) continue;
      const decorated =
        isMethodDeclaration(member) ||
        isGetAccessorDeclaration(member) ||
        isSetAccessorDeclaration(member) ||
        isPropertyDeclaration(member);
      if (!decorated) continue;
      applications.push(...this.decoratorApplicationsOf(member, name));
    }
    return applications;
  }

  /**
   * A decorator, as a call.
   *
   * <p>A decorator runs where the thing it decorates is DEFINED, and it is
   * what the name holds afterwards. So the call belongs to the stream of the
   * definition -- the class's own stream, or the module's -- and never to
   * the body of the decorated function, which would run the decorator once
   * per call instead of once per definition.
   *
   * <p>The callee key NAMES the decorator: a decorator is a function like any
   * other, and a rule that matches on one matches on its name. The owner is
   * the decorator's own: the module it was imported from, or the module that
   * declares it.
   *
   * <p>The arguments are the owner slot, the decorator's own value, and the
   * holder of the decorated thing. The arity counts the decorator's
   * arguments plus the decorated thing itself.
   */
  lowerDecorator(application) {
    const decorator = application?.decorator;
    const expression = decorator?.expression;
    if (!expression) return;
    const value = this.lowerExpression(expression);
    if (!value) return;
    const holder = this.decoratorHolder(application.holder);
    const applied = this.state.newTemp(TYPE.UNKNOWN);
    const name = decoratorNameOf(decorator);
    const owner = this.decoratorOwnerOf(decorator);
    const arity = 1 + this.decoratorArgumentCount(expression);
    this.emit({
      kind: KIND.CALL,
      calleeSig: calleeSigOf(owner, name ?? "<decorator>", arity),
      dest: applied,
      args: [constOp(CONST.STRING, owner ?? ""), value, holder],
      ...this.position(decorator),
    });
    this.emit({
      kind: KIND.MOVE,
      dest: holder,
      lhs1: applied,
      ...this.position(decorator),
    });
  }

  /**
   * The variable that holds the decorated thing.
   *
   * <p>The holder is a local of the stream the decorator runs in. A name
   * that is already a local of that stream would have its value replaced by
   * the decorator's result, so a temporary stands in for it then.
   */
  decoratorHolder(name) {
    if (!name) return this.state.newTemp(TYPE.UNKNOWN);
    if (this.state.locals.some((local) => local.name === name)) {
      return this.state.newTemp(TYPE.UNKNOWN);
    }
    const holder = varOp(name, TYPE.UNKNOWN);
    this.declareLocal(holder);
    return holder;
  }

  /**
   * The owner a decorator's callee key carries.
   *
   * <p>The owner is the decorator's OWN: the module it was imported from, or
   * the module that declares it. An owner that names nothing at all would
   * make a rule that matches on a decorator's name match nothing too.
   */
  decoratorOwnerOf(decorator) {
    const expression = decorator?.expression;
    if (!expression) return undefined;
    // A decorator that is called -- `@app.route("/x")` -- is owned by what
    // the CALL is owned by: the factory, not its result.
    const target = isCallExpression(expression) ? expression.expression : expression;
    return this.ownerOfExpression(target) ?? undefined;
  }

  /** The number of arguments a decorator expression passes. */
  decoratorArgumentCount(expression) {
    if (!isCallExpression(expression)) return 0;
    return (expression.arguments ?? []).length;
  }

  /** The number of parameters the base class's constructor declares. */
  baseConstructorArity(base) {
    if (!this.sourceFile) return 0;
    for (const statement of this.sourceFile.statements ?? []) {
      if (!isClassDeclaration(statement)) continue;
      if (!statement.name || statement.name.text !== base) continue;
      for (const member of statement.members ?? []) {
        if (isConstructorDeclaration(member)) {
          return (member.parameters ?? []).filter((p) => !isThisParameter(p)).length;
        }
      }
    }
    return 0;
  }

  // ---- modules -------------------------------------------------------------------------------

  /**
   * A static import.
   *
   * <p>The binding is made at compile time, so an import produces NO
   * instruction at all. A type-only import is erased completely; the edge it
   * names is a type-level fact, and it is recorded as such.
   */
  lowerImport(node) {
    const clause = node.importClause;
    if (clause && clause.isTypeOnly) {
      this.emitDrop(
        "type-only-import",
        "a type-only import is erased at compile time; the module edge is a type-level fact",
        IMPACT.TYPE,
        node,
      );
      return;
    }
    for (const element of clause?.namedBindings?.elements ?? []) {
      if (element.isTypeOnly) {
        this.emitDrop(
          "type-only-import",
          "a type-only import is erased at compile time; the module edge is a type-level fact",
          IMPACT.TYPE,
          node,
        );
      }
    }
  }

  /**
   * An export.
   *
   * <p>An export produces no instruction either: the module's export table
   * is built at compile time. A type-only export is erased completely.
   */
  lowerExport(node) {
    if (node.isTypeOnly) {
      this.emitDrop(
        "type-only-export",
        "a type-only export is erased at compile time; the module edge is a type-level fact",
        IMPACT.TYPE,
        node,
      );
      return;
    }
    if (node.declaration) {
      this.lowerStatement(node.declaration);
    }
  }

  // ---- helpers ------------------------------------------------------------------------------

  emit(instruction) {
    this.state.instructions.push(instruction);
    return instruction;
  }

  /** Emit a label, with the reference that says what the label is. */
  emitLabel(label) {
    const instruction = { kind: KIND.LABEL, label };
    const reference = this.state.labelRefs.get(label);
    if (reference) {
      instruction.labelRef = reference;
    }
    this.emit(instruction);
    return instruction;
  }

  /** Emit a branch, with the guard that says what the branch tests. */
  emitBranch(condition, trueLabel, falseLabel, guard, node) {
    const instruction = {
      kind: KIND.BRANCH,
      lhs1: condition,
      trueLabel,
      falseLabel,
      ...this.position(node),
    };
    if (guard) {
      instruction.guard = guard;
    }
    this.emit(instruction);
    return instruction;
  }

  /**
   * Record a degradation.
   *
   * <p>Every construct that is modelled less faithfully than the source says
   * so here. The impact says how much was lost: `SEMANTICS` means the
   * function's precision is degraded, `STRUCTURE` means it is only lowered,
   * and `VALUE` and `TYPE` leave the precision alone.
   */
  emitDrop(construct, detail, impact, node) {
    if (!this.state) {
      return undefined;
    }
    const known = this.state.drops.some(
      (drop) => drop.construct === construct && drop.detail === detail,
    );
    if (known) {
      return undefined;
    }
    const position = node ? this.position(node) : { line: -1, column: -1 };
    const drop = {
      construct,
      detail,
      impact,
      line: position.line,
      column: position.column,
      scope: "FUNCTION",
    };
    this.state.drops.push(drop);
    return drop;
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

  /** Declare the receiver of a class's or a struct's own properties. */
  declareThis() {
    this.declareLocal(varOp("this", TYPE.UNKNOWN));
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
      if (process.env.PLUMB_DEBUG) {
        console.error(`plumb-ts-sidecar: ${e && e.stack ? e.stack : String(e)}`);
      }
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
    return mapType(type, this.checker, this);
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
   *
   * <p>The owner is resolved by a chain of five steps, and the last one is
   * "the owner is missing": the callee key then has no owner section at all.
   * A missing owner is never a module name and never a sentinel -- both of
   * those name something the call is not.
   */
  ownerOf(node) {
    const expression = node.expression;
    if (expression && isSuperExpression(expression)) {
      // `super(...)`: the owner is the base class.
      return this.enclosingBase ?? OWNER_MISSING;
    }
    if (expression && isPropertyAccessExpression(expression)) {
      const base = expression.expression;
      if (isSuperExpression(base)) {
        // `super.m(...)`: the owner is the base class as well.
        return this.enclosingBase ?? OWNER_MISSING;
      }
      // `this.m(...)`: the receiver is THIS object, so the owner is the class the
      // method is written in.
      //
      // The compiler's own type of `this` cannot answer it. The `this` type is not an
      // object type, and the class-name lookup drops every type that is not one -- so
      // `this.m()` used to get NO owner at all, and a call key without an owner names
      // every method of that name in every class. The owner is taken from the function
      // being lowered instead: that is the class whose name the function's own
      // SIGNATURE carries, so the call key and the signature agree and the call graph
      // can resolve the call to this function rather than to the first same-named
      // method it finds.
      if (base && base.kind === SyntaxKind.ThisKeyword) {
        return this.state?.enclosingClass ?? OWNER_MISSING;
      }
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
      // A primitive receiver: the owner is the wrapper class, which is what
      // the method really belongs to.
      const wrapper = this.primitiveWrapperOf(base);
      if (wrapper) {
        return wrapper;
      }
      // A known global: `console.log(x)` belongs to `console`.
      if (isIdentifier(base) && GLOBAL_OWNERS.has(base.text)) {
        return base.text;
      }
      return OWNER_MISSING;
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
      // A known global: `eval(x)` belongs to the global it came from, so the
      // owner is that global's own name. It is NOT the same as the global
      // object as a RECEIVER -- `console.log(x)` names `console` because the
      // method belongs to that object -- and the difference matters: a call
      // whose receiver nobody could type has NO owner, so the key tells the
      // two apart.
      if (GLOBAL_OWNERS.has(expression.text)) return expression.text;
      return OWNER_MISSING;
    }
    // The callee is not a name at all, so there is no owner to find.
    return OWNER_MISSING;
  }

  /** The owner of a call whose callee is an expression, such as a tag. */
  ownerOfExpression(expression) {
    if (!expression) return OWNER_MISSING;
    if (isIdentifier(expression)) {
      const binding = this.imports.get(expression.text);
      if (binding) return binding.module;
      if (this.declaredNames.has(expression.text)) return this.moduleName;
      // A known global: it keeps its own name as the owner, the same as in
      // `ownerOf`. See there for why the two are told apart.
      if (GLOBAL_OWNERS.has(expression.text)) return expression.text;
      return OWNER_MISSING;
    }
    if (isPropertyAccessExpression(expression)) {
      const base = expression.expression;
      if (isIdentifier(base)) {
        const binding = this.imports.get(base.text);
        if (binding && binding.isNamespace) return binding.module;
      }
      const name = this.classNameOfNode(base);
      if (name) return name;
      const wrapper = this.primitiveWrapperOf(base);
      if (wrapper) return wrapper;
      if (isIdentifier(base) && GLOBAL_OWNERS.has(base.text)) return base.text;
    }
    return OWNER_MISSING;
  }

  /**
   * The name of a node's class, when its type is a class with a name.
   *
   * <p>A plain object and a class instance both have a type, and the plain
   * one's type is the global `Object` -- which is a real answer, not a missing
   * one. A receiver whose type is not a class at all -- a string, a number --
   * has no class name, and the call's owner is looked for elsewhere.
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

  /**
   * The wrapper class of a primitive receiver: `"abc".trim()` belongs to
   * `String`, `1..toFixed(2)` to `Number`, and so on.
   */
  primitiveWrapperOf(node) {
    if (!node || !this.checker) return undefined;
    let type;
    try {
      type = this.checker.getTypeAtLocation(node);
    } catch (e) {
      return undefined;
    }
    if (!type) return undefined;
    const wrapper = wrapperOfType(type);
    if (wrapper) return wrapper;
    // A union: the wrapper of the member that is not nullish.
    if (type.flags & TypeFlags.Union) {
      const members = type.getTypes ? type.getTypes() : [];
      for (const member of members) {
        if (member.flags & (TypeFlags.Null | TypeFlags.Undefined)) continue;
        const found = wrapperOfType(member);
        if (found) return found;
      }
    }
    return undefined;
  }

  methodNameOf(node) {
    const expression = node.expression;
    if (expression && isSuperExpression(expression)) {
      // `super(...)` calls the base class's constructor.
      return "<init>";
    }
    if (expression && isPropertyAccessExpression(expression)) {
      if (isSuperExpression(expression.expression)) {
        return expression.name.text;
      }
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

  /** The name of the method an expression names. */
  methodNameOfExpression(expression) {
    if (!expression) return "<call>";
    if (isPropertyAccessExpression(expression)) {
      return expression.name.text;
    }
    if (isIdentifier(expression)) {
      const binding = this.imports.get(expression.text);
      if (binding && binding.exported) return binding.exported;
      return expression.text;
    }
    return "<call>";
  }

  /** Whether a call is a call to `eval`. */
  isEvalCall(node) {
    const expression = node.expression;
    return !!expression && isIdentifier(expression) && expression.text === "eval";
  }

  /**
   * The arity of a call.
   *
   * <p>The arity counts the DECLARED parameters when the callee could be
   * resolved: an optional parameter is still part of the signature, and the
   * sink catalogue matches on the signature. A callee that could NOT be
   * resolved has no declared parameters to count, so the arity is the number
   * of arguments that were passed.
   */
  arityOf(signature, args) {
    if (signature && Array.isArray(signature.parameters) && signature.parameters.length > 0) {
      return signature.parameters.length;
    }
    return args.length;
  }
}

/** The callee key of a call: `owner.method/arity`, with no owner when the owner is missing. */
function calleeSigOf(owner, method, arity) {
  return owner ? `${owner}.${method}/${arity}` : `${method}/${arity}`;
}

/** The guard of a branch on a value's truthiness. */
function truthiness(operand) {
  return { kind: "Truthiness", operand };
}

/**
 * The condition of a statement or an expression that has one.
 *
 * <p>The name of the field is NOT the same in every compiler. The `if`, the
 * `while` and the `do` call it `expression`; the `for` and the conditional
 * expression call it `condition`. Asking a node for the one it does not have
 * returns UNDEFINED -- and a branch on UNDEFINED has NO SUBJECT: the
 * comparison is never evaluated, the guard says nothing, and the path
 * condition is lost.
 *
 * <p>That loss is invisible. The instruction stream is still well formed,
 * every label still resolves and the scan still reports success, so nothing
 * but reading the instructions tells that the condition is not there.
 */
function conditionOf(node) {
  return node?.condition ?? node?.expression;
}

/** A type that may also be null or undefined. */
function nullable(type) {
  if (!type) return { kind: TYPE.UNKNOWN, nullable: true };
  return { ...type, nullable: true };
}

/** The precision of a function, derived from its drops and its problems. */
function precisionOf(state) {
  if (state.drops.some((drop) => drop.impact === IMPACT.SEMANTICS)) {
    return PRECISION_DEGRADED;
  }
  if (state.drops.some((drop) => drop.impact === IMPACT.STRUCTURE)) {
    return PRECISION.LOWERED;
  }
  if (state.problems.length > 0) {
    return PRECISION.LOWERED;
  }
  return PRECISION.FULL;
}

/** The value domain of a type, when the type has one. */
function domainOf(type) {
  if (!type) return undefined;
  return type.domain ?? undefined;
}

/** Whether a parameter is the `this` parameter. */
function isThisParameter(parameter) {
  return !!parameter && !!parameter.name && parameter.name.text === "this";
}

/** The name of a decorator, when it names one. */
function decoratorNameOf(decorator) {
  return decoratorNameOfExpression(decorator?.expression);
}

/**
 * The name a decorator expression carries.
 *
 * <p>A decorator that is CALLED -- `@app.route("/x")` -- is named after the
 * function it calls: the factory's result is what decorates, but the name a
 * rule matches on is the factory's.
 */
function decoratorNameOfExpression(expression) {
  if (!expression) return undefined;
  if (isIdentifier(expression)) return expression.text;
  if (isPropertyAccessExpression(expression)) return expression.name?.text;
  if (isCallExpression(expression)) return decoratorNameOfExpression(expression.expression);
  return undefined;
}

/** The offsets at which each line of the text starts. */
function computeLineStarts(text) {
  const starts = [0];
  for (let i = 0; i < text.length; i += 1) {
    if (text.charCodeAt(i) === 10) starts.push(i + 1);
  }
  return starts;
}

/**
 * The properties of `import.meta` that a file uses.
 *
 * <p>The object is built by the module's own synthetic function, and it has
 * to hold the properties the file actually asks for: the shape is static even
 * though the values are not.
 */
function collectMetaProperties(sourceFile) {
  const names = [];
  const visit = (node) => {
    if (!node) return;
    if (isPropertyAccessExpression(node) && isMetaProperty(node.expression)) {
      const name = node.name?.text;
      if (name && !names.includes(name)) names.push(name);
    }
    if (typeof node.forEachChild === "function") {
      node.forEachChild(visit);
    }
  };
  visit(sourceFile);
  return names;
}

/** The TAC type of a compiler type. */
function mapType(type, checker, translator) {
  if (!type) return undefined;
  const flags = type.flags;

  // A union: the members decide the type, and a nullish member makes it
  // nullable. A union is never collapsed into its first member.
  if (flags & TypeFlags.Union) {
    const members = type.getTypes ? type.getTypes() : [];
    const mapped = members.map((member) => mapType(member, checker, translator)).filter(Boolean);
    const hasNullish = members.some(
      (member) => member.flags & (TypeFlags.Null | TypeFlags.Undefined),
    );
    const primary = mapped.find((member) => member.kind !== TYPE.NULL) ?? { kind: TYPE.UNKNOWN };
    if (mapped.length > 1) {
      return {
        ...primary,
        nullable: hasNullish || primary.nullable === true,
        unionMembers: mapped,
      };
    }
    return { ...primary, nullable: hasNullish || primary.nullable === true };
  }

  if (flags & TypeFlags.String) return { kind: TYPE.STRING };
  if (flags & TypeFlags.Number) return { kind: TYPE.DOUBLE, domain: FLOATING_DOMAIN };
  if (flags & TypeFlags.BooleanLike) return { kind: TYPE.BOOLEAN };
  if (flags & TypeFlags.BigIntLike) {
    // A bigint is of arbitrary precision: the domain says so.
    return { kind: TYPE_BIGINT, domain: { kind: "Unbounded" } };
  }
  if (flags & TypeFlags.ESSymbolLike) return { kind: TYPE.UNKNOWN, domain: DYNAMIC_DOMAIN };
  if (flags & TypeFlags.VoidLike) return { kind: TYPE.VOID };
  if (flags & TypeFlags.Null) return { kind: TYPE.NULL };
  if (flags & TypeFlags.Undefined) return { kind: TYPE.NULL };
  if (flags & TypeFlags.Never) return { kind: TYPE.VOID };
  if (flags & TypeFlags.Any || flags & TypeFlags.Unknown) {
    return { kind: TYPE.UNKNOWN, domain: DYNAMIC_DOMAIN };
  }
  if (flags & TypeFlags.TypeParameter) {
    // A type parameter's argument is only known at the instantiation.
    return { kind: TYPE.UNKNOWN, domain: DYNAMIC_DOMAIN };
  }
  if (flags & TypeFlags.StringLiteral) return { kind: TYPE.STRING };
  if (flags & TypeFlags.NumberLiteral) return { kind: TYPE.DOUBLE, domain: FLOATING_DOMAIN };
  if (flags & TypeFlags.BooleanLiteral) return { kind: TYPE.BOOLEAN };
  if (flags & TypeFlags.BigIntLiteral) return { kind: TYPE_BIGINT, domain: { kind: "Unbounded" } };

  if (flags & TypeFlags.Object) {
    const arguments_ = typeArgumentsOf(type, checker);
    if (checker && checker.isTupleType && checker.isTupleType(type)) {
      // A tuple is an array whose length is part of its type: the length is
      // not expressible, and it is reported.
      const component = arguments_.length > 0 ? mapType(arguments_[0], checker, translator) : undefined;
      if (translator) {
        translator.emitDrop(
          "tuple-type",
          "a tuple's length is not part of the TAC's array type; the first element's type is used",
          IMPACT.STRUCTURE,
          null,
        );
      }
      return { kind: TYPE.ARRAY, component: component ?? { kind: TYPE.UNKNOWN } };
    }
    if (checker && checker.isArrayType && checker.isArrayType(type)) {
      const component =
        arguments_.length > 0 ? mapType(arguments_[0], checker, translator) : { kind: TYPE.UNKNOWN };
      return { kind: TYPE.ARRAY, component: component ?? { kind: TYPE.UNKNOWN } };
    }
    const name = classNameOf(type);
    if (name === "Array") {
      const component =
        arguments_.length > 0 ? mapType(arguments_[0], checker, translator) : { kind: TYPE.UNKNOWN };
      return { kind: TYPE.ARRAY, component: component ?? { kind: TYPE.UNKNOWN } };
    }
    return { kind: TYPE.CLASS, name: name ?? "Object" };
  }
  return { kind: TYPE.UNKNOWN, domain: DYNAMIC_DOMAIN };
}

/** The wrapper class of a primitive type: `String`, `Number`, `Boolean`, `BigInt`. */
function wrapperOfType(type) {
  if (!type) return undefined;
  const flags = type.flags;
  if (flags & TypeFlags.StringLike) return "String";
  if (flags & TypeFlags.NumberLike) return "Number";
  if (flags & TypeFlags.BooleanLike) return "Boolean";
  if (flags & TypeFlags.BigIntLike) return "BigInt";
  return undefined;
}

/** The type arguments of a generic type, when the compiler can give them. */function typeArgumentsOf(type, checker) {
  if (!checker || typeof checker.getTypeArguments !== "function") {
    return [];
  }
  try {
    return Array.from(checker.getTypeArguments(type) ?? []);
  } catch (e) {
    return [];
  }
}

/** The domain of a JavaScript number: a double, with 53 bits of precision. */
const FLOATING_DOMAIN = { kind: "Floating", bits: 53, exponentBits: 11 };

/** The domain of a type nothing is known about. */
const DYNAMIC_DOMAIN = { kind: "Dynamic" };

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
 *
 * <p><b>Why the object-type guard is ASKED FOR, not called.</b> The guard is a
 * safety net rather than a filter -- `mapType` only calls this for a type whose
 * flags already say it is an object -- so it is asked for only when the
 * compiler has it. Calling it on a build that does not implement it throws, the
 * catch below swallows the throw, and every class in the language degrades to
 * `Object`: an instance's type comes out as `Object`, so a method call on it is
 * keyed by the FILE's module instead of the class that owns the method. The
 * sibling sidecar, whose compiler build does not implement the accessor, had
 * exactly that.
 */
function classNameOf(type) {
  if (!type) return undefined;
  try {
    if (typeof type.isObjectType === "function" && !type.isObjectType()) return undefined;
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
  if (type.unionMembers && type.unionMembers.length > 1) {
    return type.unionMembers.map((member) => typeText(member)).join("|");
  }
  if (type.nullable === true) {
    return `${type.kind.toLowerCase()}|null`;
  }
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
