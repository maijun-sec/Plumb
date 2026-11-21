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
// a drop on the function, and the affected value becomes a HAVOC. It never
// becomes a silent "nothing to report": a sidecar that drops a construct
// quietly would leave the engine believing the program had no findings.
//
// The compiler facts come from the ArkTS compiler itself, so the types,
// the overloads and the property names are the ones the compiler sees.

import { CONST, KIND, PRECISION, PROBLEM, TYPE, constOp, varOp } from "./protocol.mjs";

import {
  blankNestedClasses,
  membersOfTruncatedClasses,
  truncatedClasses,
} from "./class-body-repair.mjs";

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
  // A loose equality and a strict one are DIFFERENT operators: `==` coerces
  // its operands, `===` does not.
  [ts.SyntaxKind.EqualsEqualsToken]: "LOOSE_EQ",
  [ts.SyntaxKind.EqualsEqualsEqualsToken]: "EQ",
  [ts.SyntaxKind.ExclamationEqualsToken]: "LOOSE_NE",
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
  [ts.SyntaxKind.AsteriskAsteriskToken]: {
    binop: "MUL",
    note: "the TAC has no power operator, so the power is modelled as a multiplication",
  },
};

/**
 * The compound assignments, by the TypeScript operator token.
 *
 * <p>A compound assignment reads its target, combines it, and writes it back.
 * The read and the write are separate instructions, because the engine tracks
 * the value of a location, not the statement that changed it.
 *
 * <p>The three LOGICAL compound assignments are not here: they are
 * short-circuits, and each of them expands into "read, decide, maybe write".
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
};

/** The logical compound assignments: `&&=`, `||=` and `??=`. */
const LOGICAL_COMPOUND_OPERATORS = {
  [ts.SyntaxKind.BarBarEqualsToken]: "LOR",
  [ts.SyntaxKind.AmpersandAmpersandEqualsToken]: "LAND",
  [ts.SyntaxKind.QuestionQuestionEqualsToken]: "NULL_COALESCE",
};

/**
 * The TAC's unary operators, by the operator's SYNTAX KIND.
 *
 * <p>There is no unary plus in the TAC, and there does not need to be: a
 * unary plus is a no-op, so the operand is the result.
 */
const UNARY_OPERATORS = {
  [ts.SyntaxKind.ExclamationToken]: "NOT",
  [ts.SyntaxKind.TildeToken]: "BITNOT",
  [ts.SyntaxKind.MinusToken]: "NEG",
};

/** The kinds of member a function can be. */
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
 * The decorators the ArkTS compiler knows by itself.
 *
 * <p>The compiler GENERATES the getter and the setter of a decorated
 * property, so the decorator expression never runs at run time: there is no
 * call to lower. The decorator's NAME and its arguments are recorded as
 * annotations instead, and the property itself is lowered as usual.
 *
 * <p>The list is the compiler's own: the property decorators are the ones in
 * `compiler.mjs` (`PROPERTY_DECORATORS`), and the state-management ones are
 * the eighteen names the language defines.
 */
const BUILTIN_DECORATORS = new Set([
  "Builder",
  "BuilderParam",
  "Component",
  "Concurrent",
  "Consume",
  "Entry",
  "Event",
  "Link",
  "Local",
  "Monitor",
  "MonoStorage",
  "ObjectLink",
  "Observed",
  "Once",
  "Param",
  "Prop",
  "Provide",
  "Require",
  "Reusable",
  "Sendable",
  "State",
  "Trace",
  "Type",
  "Watch",
]);

/**
 * The state-management decorators, whose names go into the field's
 * annotations.
 */
const STATE_DECORATORS = new Set([
  "State",
  "Local",
  "Prop",
  "Param",
  "Link",
  "Provide",
  "Consume",
  "Require",
  "Once",
  "Watch",
  "Observed",
  "ObjectLink",
  "Trace",
  "Event",
  "Monitor",
  "Reusable",
  "MonoStorage",
  "Type",
]);

/**
 * The names of the globals the language itself provides.
 *
 * <p>The table gives NAMES and nothing else: it says which receiver a call
 * belongs to, and it does NOT say whether that call is a source or a sink.
 */
const GLOBAL_OWNERS = new Set([
  "Array",
  "ArrayBuffer",
  "BigInt",
  "Boolean",
  "DataView",
  "Date",
  "Error",
  "Function",
  "JSON",
  "Map",
  "Math",
  "Number",
  "Object",
  "Promise",
  "Reflect",
  "RegExp",
  "Set",
  "String",
  "Symbol",
  "TypeError",
  "abilityContext",
  "console",
  "decodeURIComponent",
  "encodeURIComponent",
  "eval",
  "globalThis",
  "parseFloat",
  "parseInt",
  "setInterval",
  "setTimeout",
]);

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
    this.memberKind = MEMBER_KIND.METHOD;
    this.synthetic = synthetic;
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
    this.labelRefs = new Map();
    this.scopes = [];
    this.effect = undefined;
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
    const label = `${this.labelPrefix}${kind === "LABEL" ? "L" : "L"}${this.labelCounter}`;
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
   * @param {object} options.sourceFile the file being lowered
   * @param {string} options.moduleName the module the file belongs to
   * @param {function(string):string} [options.moduleNameOf] the module a FILE
   *     belongs to, wherever it is
   * @param {function(string):boolean} [options.isProjectFile] whether a file is
   *     one of the analysed project's own files.  It is what tells a class of
   *     THIS project from a class of a library: a library's declaration is not
   *     one of the project's own files, and a library class keeps its own name
   *     as its owner.
   * @param {string} options.language the source language, for the wire
   * @param {string} options.text the file's text, for the line and column
   */
  constructor(options) {
    this.checker = options.checker;
    this.sourceFile = options.sourceFile;
    this.moduleName = options.moduleName;
    this.moduleNameOf = options.moduleNameOf;
    this.isProjectFile = options.isProjectFile;
    this.language = options.language ?? "ARKTS";
    this.text = options.text ?? "";
    this.lineStarts = computeLineStarts(this.text);
    this.imports = new Map();
    this.state = null;
    this.pendingFunctions = [];
    this.inlineCounter = 0;
    this.objectCounter = 0;
    this.enclosingBase = undefined;
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
      const moduleFunction = this.beginFunction("__top", `${this.moduleName}.__top()`, null, true);
      try {
        for (const statement of moduleStatements) {
          this.lowerStatement(statement);
        }
      } finally {
        functions.push(this.finishFunction(moduleFunction));
        functions.push(...this.takePendingFunctions());
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
        functions.push(...this.lowerEnum(statement));
        continue;
      }
      if (ts.isFunctionDeclaration(statement) && statement.name) {
        flushModule();
        functions.push(...this.lowerFunctionDeclaration(statement, null));
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

  /**
   * The plan to repair a class body the compiler truncated.
   *
   * <p>The compiler's tree for a class whose body holds a nested class is cut
   * short at the nested class: the members that came after it come back as
   * fragments of the FILE, and a sink inside one of them is not lowered as a
   * call at all. The repair takes the nested class out of the text the
   * compiler reads, so that the compiler builds the outer class whole.
   *
   * <p>Nothing is repaired when the file has no truncated class, so the common
   * case costs one walk of the file's own statements.
   *
   * @param {object} sourceFile the compiler's source file
   * @return {object|null} the plan, or null when there is nothing to repair
   */
  planClassBodyRepair(sourceFile) {
    const truncated = truncatedClasses(sourceFile, this.text ?? "");
    if (truncated.length === 0) return null;
    const members = membersOfTruncatedClasses(sourceFile, truncated);
    // Only a nested CLASS is taken out of the text. The fragments of the
    // other members are left alone: they are what the compiler needs to put
    // the outer class back together.
    const nested = members.filter((member) => ts.isClassDeclaration(member));
    if (nested.length === 0) return null;
    return {
      truncated,
      nested,
      patchedText: blankNestedClasses(this.text ?? "", nested, sourceFile),
    };
  }

  /**
   * Translate a repaired tree, and the nested classes the repair took out of it.
   *
   * <p>The repaired tree holds the outer class whole. The nested classes are
   * NOT in it -- they were taken out of the text -- so they are lowered from
   * the compiler's own tree, where they are complete.
   *
   * <p>The fragments are not lowered at all: in the repaired tree they are
   * members of the class they belong to, and lowering them as module-level
   * code is what invented the calls the source does not have.
   *
   * @param {object} patchedSourceFile the compiler's tree of the repaired text
   * @param {object} plan the plan from {@link planClassBodyRepair}
   * @return {{functions: object[]}} the functions
   */
  translateRepaired(patchedSourceFile, plan) {
    const previous = this.sourceFile;
    let functions;
    try {
      this.sourceFile = patchedSourceFile;
      functions = this.translateSourceFile().functions;
    } finally {
      this.sourceFile = previous;
    }
    for (const node of plan.nested) {
      functions.push(...this.lowerClass(node));
    }
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
   *   <li>one function per method.
   * </ul>
   *
   * <p>The initialisers are emitted ONCE. Copying them into every method
   * would run the same initialiser once per method, and an initialiser that
   * calls something would report that call once per method.
   */
  lowerStruct(node, ownerPath) {
    const out = [];
    const simpleName = node.name ? node.name.text : "<anonymous>";
    // The struct's IDENTITY carries the NAMESPACES it lives in.
    const structName = ownerPath ? `${ownerPath}.${simpleName}` : simpleName;
    const fields = node.members.filter((member) => ts.isPropertyDeclaration(member));
    const decorators = this.decoratorNamesOf(node);
    const interfaces = this.implementedInterfacesOf(node);

    const initializer = this.beginFunction(
      structName,
      `${this.moduleName}.${structName}()`,
      structName,
      true,
    );
    // A struct whose body holds a nested declaration is a struct the COMPILER
    // cut short: the members that come after the nested one are not in the
    // struct at all. The loss is reported, so that the struct's precision
    // drops instead of the loss passing for a whole analysis.
    if (this.classBodyIsLost(node)) {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "this struct's members were not lowered: the compiler's syntax tree does not contain them",
      );
    }
    initializer.memberKind = MEMBER_KIND.FIELD_INITIALIZER;
    initializer.classAnnotations = decorators.slice();
    initializer.interfaces = interfaces.slice();
    this.guard(node, () => {
      this.declareThis();
      this.lowerFieldInitializers(fields, structName);
      this.lowerClassDecorators(node, structName);
      if (interfaces.length > 0) {
        this.emitDrop(
          "implements",
          `the struct implements ${interfaces.join(", ")}; the relation is a type-level fact`,
          IMPACT.TYPE,
          node,
        );
      }
    });
    out.push(this.finishFunction(initializer));

    for (const member of node.members) {
      if (ts.isMethodDeclaration(member) && member.name) {
        out.push(...this.lowerFunctionDeclaration(member, structName));
        continue;
      }
      if (ts.isGetAccessorDeclaration(member)) {
        out.push(...this.lowerAccessor(member, structName, MEMBER_KIND.GETTER));
        continue;
      }
      if (ts.isSetAccessorDeclaration(member)) {
        out.push(...this.lowerAccessor(member, structName, MEMBER_KIND.SETTER));
        continue;
      }
      if (ts.isConstructorDeclaration(member)) {
        // A struct's constructor is the COMPILER's own desugaring: ArkTS does
        // not let a struct declare one, and the compiler synthesises it.
        // Lowering it would invent a function the user never wrote.
        continue;
      }
    }
    return out;
  }

  /** A class: its state, its constructor, and its methods. */
  lowerClass(node, ownerPath) {
    const out = [];
    const simpleName = node.name ? node.name.text : "<anonymous>";
    // The class's IDENTITY carries the NAMESPACES it lives in: a class in a
    // namespace is not the class of the same name at the top level.
    const className = ownerPath ? `${ownerPath}.${simpleName}` : simpleName;
    const fields = node.members.filter((member) => ts.isPropertyDeclaration(member));
    // The compiler's syntax tree for a class whose body holds a nested class is
    // TRUNCATED: the class node stops at the nested class, and the members that
    // come after it are not in the class at all -- they come back as fragments
    // of the FILE's statement list. The class's real members -- and every sink
    // in them -- would be gone without a word. So the loss is REPORTED: the
    // class's own function carries the problem, its precision drops, and the
    // reader is told that this class was not analysed.
    const lostBody = this.classBodyIsLost(node)
      ? "this class's members were not lowered: the compiler's syntax tree does not contain them"
      : null;
    const decorators = this.decoratorNamesOf(node);
    const interfaces = this.implementedInterfacesOf(node);
    const base = this.baseClassNameOf(node);
    const previousBase = this.enclosingBase;
    this.enclosingBase = base;

    let constructor = null;
    for (const member of node.members) {
      if (ts.isConstructorDeclaration(member)) {
        constructor = member;
        break;
      }
    }

    if (constructor) {
      // The field initialisers run inside the constructor, after the base
      // class's constructor: that is the order the language defines.
      out.push(
        ...this.lowerConstructor(
          constructor,
          className,
          fields,
          base,
          decorators,
          interfaces,
          lostBody,
        ),
      );
    } else {
      const initializer = this.beginFunction(
        className,
        `${this.moduleName}.${className}()`,
        className,
        true,
      );
      if (lostBody) this.report(PROBLEM.SOURCE_LOWERED, node, lostBody);
      initializer.memberKind = MEMBER_KIND.FIELD_INITIALIZER;
      initializer.classAnnotations = decorators.slice();
      initializer.interfaces = interfaces.slice();
      this.guard(node, () => {
        this.declareThis();
        this.lowerFieldInitializers(fields, className);
        this.lowerClassDecorators(node, className);
        if (interfaces.length > 0) {
          this.emitDrop(
            "implements",
            `the class implements ${interfaces.join(", ")}; the relation is a type-level fact`,
            IMPACT.TYPE,
            node,
          );
        }
      });
      out.push(this.finishFunction(initializer));
    }

    for (const member of node.members) {
      if (ts.isConstructorDeclaration(member)) {
        continue;
      }
      if (ts.isMethodDeclaration(member) && member.name) {
        out.push(...this.lowerFunctionDeclaration(member, className));
        continue;
      }
      if (ts.isGetAccessorDeclaration(member)) {
        out.push(...this.lowerAccessor(member, className, MEMBER_KIND.GETTER));
        continue;
      }
      if (ts.isSetAccessorDeclaration(member)) {
        out.push(...this.lowerAccessor(member, className, MEMBER_KIND.SETTER));
        continue;
      }
      if (ts.isClassStaticBlockDeclaration(member)) {
        out.push(...this.lowerStaticBlock(member, className));
        continue;
      }
      if (ts.isPropertyDeclaration(member)) {
        continue;
      }
    }
    this.enclosingBase = previousBase;
    return out;
  }

  /**
   * A property's initial value.
   *
   * <p>A property is an entry of the instance's NAMESPACE: `this.value` and
   * `this["value"]` are the same location, and that is the location every
   * other access to the member uses. So the initial value is a keyed store --
   * an ARRAYSTORE whose key is the member's name -- and not a field write.
   *
   * <p>It used to be a FIELDSTORE, and that gave ONE location two mechanisms:
   * the declaration wrote it as a field while every read of it was an
   * ARRAYLOAD on the namespace. The two never met, so a tainted initialiser
   * never reached the place that reads the member.
   *
   * <p>A STATIC field belongs to the class rather than to an instance, so it
   * is written on the class: the entry "counter" of the class object is where
   * the read `Shapes.counter` looks.
   */
  lowerFieldInitializers(fields, className) {
    for (const field of fields) {
      const name = this.fieldNameOf(field);
      // The DECLARATION is a fact of its own, and it is recorded whether or not the
      // member has an initial value: a member with no initialiser is written later --
      // in the constructor, or in a method -- and until it is, the only thing the TAC
      // knows about it is that it was declared. A rule that asks "was this member ever
      // read?" needs the declaration to ask it about.
      const annotations = this.fieldAnnotationsOf(field);
      if (annotations.length > 0) {
        this.state.fieldAnnotations[name] = annotations;
      }
      if (!field.initializer) {
        continue;
      }
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
    }
  }

  /** Whether a field is a static one: it belongs to the class, not to an instance. */
  isStaticField(field) {
    const modifiers = field.modifiers ?? [];
    return modifiers.some((modifier) => modifier.kind === ts.SyntaxKind.StaticKeyword);
  }

  /**
   * A constructor.
   *
   * <p>A constructor is a function of its own: the name is `<init>` and the
   * member kind says so. The base class's constructor runs FIRST, so that the
   * base's fields exist before the derived class's own fields are written.
   */
  lowerConstructor(node, className, fields, base, decorators, interfaces, lostBody) {
    const state = this.beginFunction(
      "<init>",
      `${this.moduleName}.${className}.<init>(${this.constructorParameterTypes(node)})`,
      className,
      false,
    );
    if (lostBody) this.report(PROBLEM.SOURCE_LOWERED, node, lostBody);
    state.memberKind = MEMBER_KIND.CONSTRUCTOR;
    state.classAnnotations = decorators.slice();
    state.superType = base ?? null;
    state.interfaces = interfaces ? interfaces.slice() : this.implementedInterfacesOf(node);
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      this.declareThis();
      // The base constructor runs FIRST, and it runs on THIS object.
      //
      // There is EXACTLY ONE such call, and it carries the arguments the
      // source passed to `super(...)`. The compiler's tree holds that call:
      // `super(input, 7)` is an expression statement in the constructor's
      // body. So the call is lowered HERE -- before the field initialisers,
      // because the base's fields have to exist before the derived class's
      // own fields are written -- and the statement itself is not lowered
      // again when the body is walked, or the base constructor would be
      // called twice.
      //
      // The arity is the base constructor's DECLARED parameter count, which
      // the compiler knows even when the base class is in another file. A
      // count taken from the arguments that were passed would be a different
      // number whenever the source passed fewer arguments than the base
      // declares, and the callee key would then name a function that does
      // not exist.
      const superCall = base ? this.findSuperCall(node) : undefined;
      if (base) {
        const result = this.state.newTemp(TYPE.UNKNOWN);
        const args = superCall ? (superCall.arguments ?? []).map((a) => this.lowerExpression(a)) : [];
        this.emit({
          kind: KIND.CALL,
          calleeSig: `${base}.<init>/${this.baseConstructorArity(base, superCall)}`,
          dest: result,
          args: [varOp("this", TYPE.UNKNOWN), ...args],
          ...this.position(node),
        });
      }
      this.lowerFieldInitializers(fields, className);
      this.lowerClassDecorators(node, className);
      if (state.interfaces.length > 0) {
        this.emitDrop(
          "implements",
          `the class implements ${state.interfaces.join(", ")}; the relation is a type-level fact`,
          IMPACT.TYPE,
          node,
        );
      }
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    const finished = this.finishFunction(state);
    return [finished, ...this.takePendingFunctions()];
  }

  /** A getter or a setter: a member of the class, and a function of its own. */
  lowerAccessor(node, className, memberKind) {
    const name = node.name ? nameTextOf(node.name) : "<accessor>";
    const state = this.beginFunction(name, this.functionSignature(node, className), className, false);
    state.memberKind = memberKind;
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      this.declareThis();
      this.lowerMemberDecorators(node, className, name);
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    if (node.type) {
      state.returnType = this.typeOfTypeNode(node.type);
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
      true,
    );
    state.memberKind = MEMBER_KIND.STATIC_INITIALIZER;
    this.guard(node, () => {
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    const finished = this.finishFunction(state);
    return [finished, ...this.takePendingFunctions()];
  }

  /**
   * An enum: its members are CONSTANTS of the enum object.
   *
   * <p>An enum member is an entry of the enum's own namespace, exactly like a
   * STATIC field of a struct is an entry of the struct object: the member is
   * written with a keyed store whose key is the member's name, and that is
   * where the read `Colour.RED` looks. So the write and the read are ONE
   * mechanism -- which is what lets the value reach the read.
   *
   * <p>It used to be a LOCAL of a synthetic function, and the read is not a
   * local: the two were two different mechanisms and could never meet.
   *
   * <p>A member without an initialiser takes the value after the PREVIOUS
   * member's value, not after the number of the members: `A = 5, B` gives B
   * the value 6.
   *
   * @param {object} node the enum
   * @param {string} ownerPath the namespaces above it, dotted
   * @return {object[]} the enum's own function and its methods
   */
  lowerEnum(node, ownerPath) {
    const simpleName = node.name ? nameTextOf(node.name) : "<anonymous>";
    // The enum's IDENTITY carries the NAMESPACES it lives in, like every other
    // declaration that can live in one.
    const enumName = ownerPath ? `${ownerPath}.${simpleName}` : simpleName;
    const methods = [];
    const state = this.beginFunction(
      enumName,
      `${this.moduleName}.${enumName}()`,
      enumName,
      true,
    );
    state.memberKind = MEMBER_KIND.FIELD_INITIALIZER;
    // The compiler's tree is cut off at the last member when the enum holds a
    // method: the member at the cut is the head of what was lost, so it gets
    // no value, and the loss is reported rather than papered over.
    const lost = this.enumBodyIsLost(node);
    if (lost) {
      this.report(
        PROBLEM.SOURCE_LOWERED,
        node,
        "this enum's body was not lowered: the compiler's syntax tree does not contain it",
      );
    }
    this.guard(node, () => {
      // The value the next auto-numbered member takes, and whether it is
      // known: a member whose value is computed cannot be counted from.
      let next = 0;
      let known = true;
      const members = node.members ?? [];
      for (const member of lost ? members.slice(0, -1) : members) {
        if (ts.isMethodDeclaration(member)) {
          // An enum can have methods, and they are methods of the enum.
          methods.push(member);
          continue;
        }
        const name = nameTextOf(member.name);
        if (!name) {
          continue;
        }
        let value;
        if (member.initializer) {
          value = this.lowerExpression(member.initializer);
          const literal = ts.isNumericLiteral(member.initializer)
            ? Number(member.initializer.text)
            : null;
          if (literal === null || !Number.isFinite(literal)) {
            known = false;
          } else {
            next = literal + 1;
            known = true;
          }
        } else if (known) {
          // The same shape a numeric literal has, so that the value can be
          // compared with the literal the source writes next to it.
          value = constOp(CONST.DOUBLE, String(next));
          next += 1;
        } else {
          // The previous member's value is not known, so this one's is not
          // either. An unknown value is said so, rather than invented.
          value = this.emitHavoc(member);
          this.emitDrop(
            "arkts.enum.memberValueUnknown",
            "the value of the enum member '" + name + "' is not known",
            IMPACT.VALUE,
            member,
          );
        }
        this.emit({
          kind: KIND.ARRAYSTORE,
          lhs1: varOp(enumName, TYPE.UNKNOWN),
          lhs2: constOp(CONST.STRING, name),
          dest: value,
          ...this.position(member),
        });
      }
    });
    const finished = this.finishFunction(state);
    // The methods are lowered after the enum's own function is finished: a
    // function of its own needs the stream it belongs to, and the enum's
    // stream is closed by then.
    const out = [];
    for (const method of methods) {
      out.push(...this.lowerFunctionDeclaration(method, enumName, MEMBER_KIND.METHOD));
    }
    return [finished, ...out, ...this.takePendingFunctions()];
  }

  // ---- functions --------------------------------------------------------------------------------

  lowerFunctionDeclaration(node, enclosingClass, memberKind = MEMBER_KIND.METHOD) {
    const name = node.name ? nameTextOf(node.name) : "<anonymous>";
    const state = this.beginFunction(
      name,
      this.functionSignature(node, enclosingClass),
      enclosingClass,
      false,
    );
    state.memberKind = memberKind;
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      this.lowerMemberDecorators(node, enclosingClass, name);
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    if (node.type) {
      state.returnType = this.typeOfTypeNode(node.type);
    }
    const finished = this.finishFunction(state);
    return [finished, ...this.takePendingFunctions()];
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
      const name = declaration.name && declaration.name.text ? declaration.name.text : "<anonymous>";
      const previous = this.state;
      const state = this.beginFunction(name, this.functionSignature(initialiser, null), null, false);
      this.guard(initialiser, () => {
        this.lowerParameters(initialiser.parameters);
        this.lowerFunctionBody(initialiser.body);
      });
      if (initialiser.type) {
        state.returnType = this.typeOfTypeNode(initialiser.type);
      }
      // The lifted function has a scope of its own, so the names its body
      // reads from the scope around it are not in it. That loss belongs to
      // the LIFTED function -- it is the one whose TAC cannot see them -- and
      // it is recorded here, so that function's precision says so instead of
      // claiming that its body was lowered whole.
      //
      // The function AROUND it keeps its precision: the body does not run
      // where the declaration is, so nothing it does reaches this scope.
      this.emitDrop(
        "lifted-function",
        "the body of this function is lowered into a function of its own, and the names it"
          + " reads from the scope around it are not in that function",
        IMPACT.STRUCTURE,
        initialiser,
      );
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
    while (literal && ts.isParenthesizedExpression(literal)) {
      literal = literal.expression;
    }
    if (!literal) {
      return null;
    }
    if (!ts.isArrowFunction(literal) && !ts.isFunctionExpression(literal)) {
      return null;
    }
    const name = `closure$${this.inlineCounter += 1}`;
    const previous = this.state;
    const state = this.beginFunction(name, this.functionSignature(literal, null, name), null, true);
    this.guard(literal, () => {
      this.lowerParameters(literal.parameters);
      this.lowerFunctionBody(literal.body);
    });
    // The lifted body has a scope of its own, so the names it reads from the
    // scope around it are not in it. That loss belongs to the LIFTED function,
    // and the enclosing one already carries the `iife-body` drop: the body ran
    // HERE, and its effect on this scope is not here either.
    this.emitDrop(
      "lifted-function",
      "the body of this function is lowered into a function of its own, and the names it"
        + " reads from the scope around it are not in that function",
      IMPACT.STRUCTURE,
      literal,
    );
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
   */
  lowerNested(node, body) {
    const previous = this.state;
    try {
      return body();
    } finally {
      this.state = previous;
    }
  }

  beginFunction(name, signature, enclosingClass, synthetic) {
    // The class's IDENTITY is the module it lives in PLUS its name. A simple name is a
    // spelling, not an identity: two files that each declare a struct called "Holder" are
    // two structs, and their members are two members. The Java front end states the same
    // thing the same way -- a class's identity is its qualified name -- so the engine can
    // keep two same-named classes apart on every language.
    //
    // The CALL key spells the class the SAME way, so the two ends of a call agree on
    // which class they mean.  The sink catalogue still recognises the call, because the
    // engine drops the module when it looks for a catalogue entry.
    const identity = enclosingClass ? this.classIdentity(enclosingClass) : enclosingClass;
    const state = new FunctionState(name, signature, identity, synthetic);
    this.state = state;
    return state;
  }

  /**
   * The IDENTITY of a class: the module it lives in plus its name.
   *
   * <p>This is the spelling the class's own function, its constructor and its
   * accessors already use, so the methods join them rather than inventing a
   * second convention.
   *
   * @param {string} className the class's simple name
   * @return {string} the identity
   */
  classIdentity(className) {
    if (!className) return this.moduleName;
    return `${this.moduleName}.${className}`;
  }

  /**
   * The class's SIMPLE name: the spelling the source uses, without the module.
   *
   * <p>The identity carries the module in front; the call key must not, because the
   * call graph and the sink catalogue match owners by simple name.
   *
   * @param identity the class's identity
   * @return the simple name, or the identity when it does not carry the module
   */
  simpleClassName(identity) {
    if (!identity) return identity;
    const prefix = `${this.moduleName}.`;
    return identity.startsWith(prefix) ? identity.slice(prefix.length) : identity;
  }

  /**
   * Whether the compiler's syntax tree lost this enum's body.
   *
   * <p>An enum that holds a METHOD comes back cut off: the enum's own text
   * ends at the method's name, and the method comes back as a stray
   * statement of the file. A well-formed enum ends with a closing brace
   * AFTER its last member, so an enum whose end is at or before the end of
   * its last member is one the compiler stopped in the middle of.
   *
   * <p>The member at the cut is then not a constant: it is the head of what
   * was lost. Giving it a value would state a number the source does not
   * say.
   *
   * @param {object} node the enum
   * @return true when the enum's body is missing
   */
  enumBodyIsLost(node) {
    const members = node?.members ?? [];
    if (members.length === 0) return false;
    const membersEnd = members[members.length - 1].end;
    if (typeof membersEnd !== "number" || typeof node.end !== "number") return false;
    return node.end <= membersEnd;
  }
  /**
   * Whether the compiler's syntax tree lost this class's body.
   *
   * <p>The truncation shows in the POSITIONS. A class the compiler has finished
   * ends AFTER its members: the closing brace of the class comes after the
   * last member, so the class's end is strictly greater than the end of its
   * member list. A class whose end is at or before the end of its members is a
   * class the compiler stopped in the middle of -- and the compiler stops in
   * the middle of a class when the class holds a nested class.
   *
   * <p>The positions are the measure, not the text: the class's own text can
   * still end with a closing brace when the truncation cut it off right after
   * a member whose body ended with one. A class that lost the members that
   * came after a nested class looks complete to the eye and is not.
   *
   * @param node the class node
   * @return true when the class's body is missing
   */
  classBodyIsLost(node) {
    if (!node) return false;
    const membersEnd = node.members ? node.members.end : undefined;
    if (typeof membersEnd === "number" && typeof node.end === "number") {
      return node.end <= membersEnd;
    }
    // A compiler that does not say where the member list ends leaves only the
    // text to go by: a class declaration always ends with a closing brace, so
    // a class whose own text does not is a class the compiler did not finish.
    if (!node.getText) return false;
    const text = node.getText();
    if (!text) return false;
    return !text.trimEnd().endsWith("}");
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
    if (!parameters) {
      return;
    }
    for (const parameter of parameters) {
      const name = parameter.name && parameter.name.text ? parameter.name.text : "p";
      // A `this` parameter is a TYPE annotation: it says what `this` is, and
      // it does not take a slot at run time.
      if (name === "this" && ts.isIdentifier(parameter.name)) {
        continue;
      }
      const type = this.typeOf(parameter);
      const operand = varOp(name, parameter.questionToken ? nullable(type) : type);
      this.state.params.push(operand);
      if (parameter.dotDotDotToken) {
        // A rest parameter is an ARRAY of the remaining arguments.
        const array = varOp(name, { kind: TYPE.ARRAY, component: type ?? { kind: TYPE.UNKNOWN } });
        this.state.params[this.state.params.length - 1] = array;
      }
      // The decorators of a parameter are annotations: `@BuilderParam` says
      // that the framework binds the parameter, and there is no call to it.
      const annotations = this.decoratorNamesOf(parameter);
      if (annotations.length > 0) {
        this.state.paramAnnotations[name] = annotations;
      }
      // A default value is applied when the function is entered, before the
      // body runs. Its expression is lowered like any other.
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
    // The owner is the class's IDENTITY: the module it lives in plus its name.  A
    // signature that names only the simple name is shared by every class of that name
    // in every module -- and the function table is keyed by the signature, so the
    // second one would REPLACE the first and its body would be gone from the program.
    const owner = enclosingClass ? this.classIdentity(enclosingClass) : this.moduleName;
    const parameters = (node.parameters ?? []).filter((p) => !isThisParameter(p));
    const types = parameters.map((p) => this.typeTextOf(p));
    const returnType = this.returnTypeTextOf(node);
    // A name OVERRIDE is for a function literal that has no name of its own.
    // Two of them would otherwise share the name "<anonymous>", and the
    // function table is keyed by the signature -- so the second one would
    // replace the first, and its body would be gone.
    const name = nameOverride ?? (node.name ? nameTextOf(node.name) : "<anonymous>");
    return `${owner}.${name}(${types.join(",")}):${returnType}`;
  }

  /** The parameter types of a constructor, for its signature. */
  constructorParameterTypes(node) {
    return (node.parameters ?? [])
      .filter((p) => !isThisParameter(p))
      .map((p) => this.typeTextOf(p))
      .join(",");
  }

  // ---- statements --------------------------------------------------------------------------------

  /**
   * A namespace: its members are functions and classes of their own.
   *
   * <p>A namespace is a SCOPE, and the scope is part of the identity of
   * everything declared in it: a class in a namespace is not the class of the
   * same name at the top level, and two namespaces of one file are not the
   * same scope. So the members are lifted with the namespace's path in front
   * of their name.
   *
   * <p>The call site spells the same path, because it asks the compiler which
   * namespaces the receiver's declaration lives in. The two ends of a call
   * therefore agree on which class they mean -- which is the whole point of
   * an identity.
   *
   * <p>The namespace's own statements -- the constants it declares, the code
   * it runs -- get a stream of their own: a namespace is initialised when the
   * module that holds it is loaded, and a stream of its own keeps that
   * stream's signature unique.
   *
   * @param {object} node the namespace
   * @param {string} ownerPath the namespaces above it, dotted
   */
  lowerNamespace(node, ownerPath) {
    const name = namespaceNameOf(node);
    if (!name || isAmbientNamespace(node)) {
      // An AMBIENT declaration (`declare namespace X`, `declare module "x"`):
      // it declares a shape and runs nothing, so there is nothing to lower --
      // and nothing to report either.
      return;
    }
    const path = ownerPath ? `${ownerPath}.${name}` : name;
    // The members are lifted while the namespace's own statements are
    // lowered, and both change the function being lowered. The state is saved
    // and put back, or the stream that asked for the namespace would go on
    // writing into the namespace's stream.
    this.lowerNested(node, () => {
      const statements = [];
      for (const statement of node.body?.statements ?? []) {
        if (ts.isFunctionDeclaration(statement) && statement.name) {
          // Collected first, pushed afterwards: see the nested function in
          // lowerStatement for why the order of the evaluation matters here.
          const lifted = this.lowerFunctionDeclaration(statement, path);
          this.pendingFunctions.push(...lifted);
          continue;
        }
        if (ts.isClassDeclaration(statement) && statement.name) {
          const lifted = this.lowerClass(statement, path);
          this.pendingFunctions.push(...lifted);
          continue;
        }
        if (ts.isStructDeclaration(statement) && statement.name) {
          const lifted = this.lowerStruct(statement, path);
          this.pendingFunctions.push(...lifted);
          continue;
        }
        if (ts.isEnumDeclaration(statement) && statement.name) {
          const lifted = this.lowerEnum(statement, path);
          this.pendingFunctions.push(...lifted);
          continue;
        }
        if (ts.isModuleDeclaration(statement)) {
          this.lowerNamespace(statement, path);
          continue;
        }
        if (ts.isInterfaceDeclaration(statement) || ts.isTypeAliasDeclaration(statement)) {
          // A TYPE has no run-time effect at all: there is nothing in it for a
          // dataflow finding to be about, and reporting it would downgrade
          // every file that happens to declare an interface.
          continue;
        }
        // Everything else is the namespace's own code.
        statements.push(statement);
      }
      if (statements.length === 0) {
        return;
      }
      const state = this.beginFunction("__top", `${this.moduleName}.${path}.__top()`, path);
      state.synthetic = true;
      state.memberKind = MEMBER_KIND.METHOD;
      this.guard(node, () => {
        for (const statement of statements) {
          this.lowerStatement(statement);
        }
      });
      const finished = this.finishFunction(state);
      const taken = this.takePendingFunctions();
      this.pendingFunctions.push(finished, ...taken);
    });
  }
  lowerStatement(node) {
    if (!node) {
      return;
    }
    if (this.isSuperCallStatement(node)) {
      // The base constructor is called ONCE: the constructor emits that call
      // itself, before the field initialisers.  This statement is the
      // SOURCE's way of saying the same thing, so it is not lowered here --
      // otherwise the base would be called twice, and the arguments would be
      // evaluated twice.
      //
      // The test is the statement's SHAPE, not the identity of the node that
      // was found: a `super(...)` call is legal inside a branch as well, and
      // it is the base's call wherever it sits.
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
      if (value && value.type) {
        this.state.returnType = value.type;
      }
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
    if (ts.isSwitchStatement(node)) {
      this.lowerSwitch(node);
      return;
    }
    if (ts.isTryStatement(node)) {
      this.lowerTry(node);
      return;
    }
    if (ts.isLabeledStatement(node)) {
      this.lowerLabels(node);
      return;
    }
    if (ts.isBlock(node)) {
      this.lowerBlock(node);
      return;
    }
    if (ts.isBreakStatement(node) || ts.isContinueStatement(node)) {
      this.lowerJump(node);
      return;
    }
    if (ts.isFunctionDeclaration(node)) {
      // A nested function is a function of its own.
      //
      // The functions are collected FIRST and pushed afterwards. A push whose
      // ARGUMENT is the lowering itself evaluates its receiver
      // (`this.pendingFunctions`) before the argument -- and the lowering
      // REPLACES that array when it takes the functions it has lifted so far.
      // The push would land in the array nobody reads any more, and the nested
      // function would vanish without a word.
      const nested = this.lowerNested(node, () =>
        this.lowerFunctionDeclaration(node, this.simpleClassName(this.state?.enclosingClass) ?? null),
      );
      this.pendingFunctions.push(...nested);
      return;
    }
    if (ts.isClassDeclaration(node) || ts.isStructDeclaration(node)) {
      // A nested class is a class of its own; its members are lifted.
      //
      // Collected first, pushed afterwards: see the nested function above for
      // why the order of the evaluation matters here.
      const nested = this.lowerNested(node, () =>
        ts.isStructDeclaration(node) ? this.lowerStruct(node) : this.lowerClass(node),
      );
      this.pendingFunctions.push(...nested);
      return;
    }
    if (ts.isModuleDeclaration(node)) {
      // A NAMESPACE is a scope, and the scope is part of the identity of
      // everything declared in it.
      this.lowerNamespace(node, null);
      return;
    }
    if (ts.isEnumDeclaration(node)) {
      // A nested enum is an enum of its own; its members are lifted.
      const nested = this.lowerNested(node, () => this.lowerEnum(node));
      this.pendingFunctions.push(...nested);
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
      if (ts.isImportDeclaration(node)) {
        this.lowerImport(node);
      }
      return;
    }
    if (ts.isExportDeclaration(node) || ts.isExportAssignment(node)) {
      this.lowerExport(node);
      return;
    }
    if (ts.isEmptyStatement(node)) {
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
      if (!ts.isVariableDeclaration(declaration)) {
        continue;
      }
      const initialiser = declaration.initializer;
      if (initialiser && (ts.isArrowFunction(initialiser) || ts.isFunctionExpression(initialiser))) {
        continue;
      }
      if (ts.isObjectBindingPattern(declaration.name) || ts.isArrayBindingPattern(declaration.name)) {
        this.lowerBindingPattern(declaration);
        continue;
      }
      const name = declaration.name && declaration.name.text ? declaration.name.text : "v";
      const type = this.typeOf(declaration);
      const variable = varOp(name, type);
      this.declareLocal(variable);
      if (!initialiser) {
        continue;
      }
      const value = this.lowerExpression(initialiser);
      this.emit({
        kind: KIND.MOVE,
        dest: variable,
        lhs1: value,
        ...this.position(declaration),
      });
    }
  }

  /** A destructuring declaration. */
  lowerBindingPattern(declaration) {
    const source = this.lowerExpression(declaration.initializer);
    this.bindPattern(declaration.name, source, declaration);
  }

  /** Bind every name of a pattern to the value it names. */
  bindPattern(pattern, source, node) {
    if (!pattern) {
      return;
    }
    if (ts.isObjectBindingPattern(pattern)) {
      for (const element of pattern.elements ?? []) {
        const key = element.propertyName
          ? nameTextOf(element.propertyName)
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
    if (ts.isArrayBindingPattern(pattern)) {
      let index = 0;
      for (const element of pattern.elements ?? []) {
        if (ts.isOmittedExpression(element)) {
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
    if (ts.isIdentifier(pattern)) {
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
    if (!element || !element.name) {
      return;
    }
    if (ts.isObjectBindingPattern(element.name) || ts.isArrayBindingPattern(element.name)) {
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

  /**
   * A block of statements.
   *
   * @param {object} node the block
   */
  lowerBlock(node) {
    for (const statement of node.statements) {
      this.lowerStatement(statement);
    }
  }

  /**
   * The body of a function.
   *
   * <p>A function's body is usually a BLOCK, but an arrow function may have a
   * single EXPRESSION instead: `() => value`. That expression is code, and it
   * is what the function returns.
   *
   * <p>Lowering an expression as a block lowers NOTHING: a block has
   * `statements` and an expression does not, so the iteration runs over
   * `undefined` and throws. The guard turns the throw into a problem, and the
   * whole body is gone -- with the sink that was in it.
   */
  lowerFunctionBody(body) {
    if (!body) {
      return;
    }
    if (ts.isBlock(body)) {
      this.lowerBlock(body);
      return;
    }
    // The concise body is an expression, and the function returns its value.
    const value = this.lowerExpression(body);
    this.emit({ kind: KIND.RETURN, lhs1: value, ...this.position(body) });
  }

  lowerIf(node) {
    const condition = this.lowerExpression(conditionOf(node));
    const thenLabel = this.state.newLabel();
    const elseLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    this.emitBranch(condition, thenLabel, elseLabel, truthiness(condition), node);
    this.emitLabel(thenLabel);
    if (node.thenStatement) {
      this.lowerStatement(node.thenStatement);
    }
    this.emit({ kind: KIND.JUMP, label: endLabel });
    this.emitLabel(elseLabel);
    if (node.elseStatement) {
      this.lowerStatement(node.elseStatement);
    }
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
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
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
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.state.scopes.pop();
    this.emitLabel(condLabel);
    const condition = this.lowerExpression(conditionOf(node));
    this.emitBranch(condition, bodyLabel, endLabel, truthiness(condition), node);
    this.emitLabel(endLabel);
  }

  lowerFor(node) {
    if (node.initializer) {
      if (ts.isVariableDeclarationList(node.initializer)) {
        for (const declaration of node.initializer.declarations ?? []) {
          this.lowerVariableDeclaration(declaration);
        }
      } else if (ts.isVariableStatement(node.initializer)) {
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
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.state.scopes.pop();
    this.emit({ kind: KIND.JUMP, label: stepLabel });
    this.emitLabel(stepLabel);
    if (node.incrementor) {
      this.lowerExpression(node.incrementor);
    }
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(endLabel);
  }

  /** One declaration of a `for` loop's initialiser. */
  lowerVariableDeclaration(declaration) {
    if (!ts.isVariableDeclaration(declaration)) {
      return;
    }
    const name = declaration.name && declaration.name.text ? declaration.name.text : "v";
    const variable = varOp(name, this.typeOf(declaration));
    this.declareLocal(variable);
    if (!declaration.initializer) {
      return;
    }
    const value = this.lowerExpression(declaration.initializer);
    this.emit({
      kind: KIND.MOVE,
      dest: variable,
      lhs1: value,
      ...this.position(declaration),
    });
  }

  /** The declaration a `for ... of` or a `for ... in` loop binds. */
  loopVariableOf(node) {
    const initializer = node.initializer;
    if (!initializer) {
      return undefined;
    }
    if (ts.isVariableDeclaration(initializer)) {
      return initializer;
    }
    if (ts.isVariableDeclarationList(initializer)) {
      return (initializer.declarations ?? [])[0];
    }
    return undefined;
  }

  /**
   * A `for ... of` loop.
   *
   * <p>The loop variable is bound to the ELEMENT, not to the iterable.
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
    }
    this.state.scopes.push({ kind: "LOOP", endLabel, headLabel });
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.state.scopes.pop();
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(endLabel);
  }

  /**
   * A `for ... in` loop.
   *
   * <p>The loop variable is bound to the KEY.
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
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.state.scopes.pop();
    this.emit({ kind: KIND.JUMP, label: headLabel });
    this.emitLabel(endLabel);
  }

  /**
   * A switch.
   *
   * <p>The cases are DISPATCHED, not run in order: each case is reached by a
   * branch that compares the discriminant with the case's value, and the
   * default is reached only when no case matched.
   */
  lowerSwitch(node) {
    const discriminant = this.lowerExpression(node.expression);
    const clauses = node.caseBlock?.clauses ?? [];
    const caseClauses = clauses.filter((clause) => ts.isCaseClause(clause));
    const defaultClause = clauses.find((clause) => ts.isDefaultClause(clause));
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
    if (node.tryBlock) {
      this.lowerBlock(node.tryBlock);
    }
    const tryEnd = this.state.instructions.length;
    const tryExit = finallyLabel ?? endLabel;
    this.emit({ kind: KIND.JUMP, label: tryExit });
    finallyExits.push(tryExit);

    let catchStart = -1;
    let catchEnd = -1;
    for (const clause of this.catchClausesOf(node)) {
      const parameter = clause.variableDeclaration;
      const name = parameter && parameter.name ? parameter.name.text : undefined;
      const label = this.state.newLabel("CATCH", name);
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
          ctorType: parameter && parameter.type ? this.typeOfTypeNode(parameter.type) : undefined,
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
      if (clause.block) {
        this.lowerBlock(clause.block);
      }
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
      if (node.finallyBlock) {
        this.lowerBlock(node.finallyBlock);
      }
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

  /**
   * The catch clauses of a try statement.
   *
   * <p>The compiler this sidecar binds to has ONE catch clause per try
   * statement, and it is a node rather than a list. The list shape is read as
   * well, so the same translator works on either compiler.
   */
  catchClausesOf(node) {
    const clauses = node.catchClause;
    if (!clauses) {
      return [];
    }
    return Array.isArray(clauses) ? clauses : [clauses];
  }

  /**
   * A labelled statement.
   *
   * <p>The label itself produces no instruction: it gives the statement's
   * end a name, so that a `break label` can jump to it.
   */
  lowerLabels(node) {
    const name = node.label ? nameTextOf(node.label) : undefined;
    const endLabel = this.state.newLabel("LABEL", name);
    this.state.scopes.push({ kind: "LABEL", name, endLabel });
    if (node.statement) {
      this.lowerStatement(node.statement);
    }
    this.state.scopes.pop();
    this.emitLabel(endLabel);
  }

  /** A break or a continue: both are jumps. */
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
    const isBreak = ts.isBreakStatement(node);
    const name = node.label ? nameTextOf(node.label) : undefined;
    const scopes = this.state.scopes;
    if (name) {
      const index = scopes.findIndex((scope) => scope.kind === "LABEL" && scope.name === name);
      if (index < 0) {
        return undefined;
      }
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

  // ---- expressions ------------------------------------------------------------------------------

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
      if (node.text === "abilityContext") {
        // The runtime context object is a global: it exists, and its
        // properties are filled in by the framework at run time.
        this.emitDrop(
          "runtime-context",
          "the properties of the runtime context object are injected at run time",
          IMPACT.VALUE,
          node,
        );
        return varOp("abilityContext", TYPE.UNKNOWN);
      }
      return varOp(node.text, this.typeOf(node));
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
      const value = this.lowerExpression(node.expression);
      this.emitDrop(
        "await",
        "the micro-task timing of an await is not in the TAC; the value itself is complete",
        IMPACT.SEMANTICS,
        node,
      );
      return value;
    }
    if (ts.isTypeOfExpression(node)) {
      const value = this.lowerExpression(node.expression);
      this.emitDrop(
        "typeof",
        "the result of typeof is one of eight strings decided at run time",
        IMPACT.VALUE,
        node,
      );
      return value;
    }
    if (node.kind === ts.SyntaxKind.ThisKeyword) {
      // The receiver of a struct's or a class's own property. It is the one
      // object the property accesses are keyed by.
      this.declareThis();
      return varOp("this", TYPE.UNKNOWN);
    }
    if (node.kind === ts.SyntaxKind.SuperKeyword) {
      // `super` is the object itself: the base class's member is read from
      // the same object.
      this.declareThis();
      return varOp("this", TYPE.UNKNOWN);
    }
    if (ts.isNonNullExpression(node)) {
      // A non-null assertion is a compile-time claim: it produces no
      // instruction at all.
      return this.lowerExpression(node.expression);
    }
    if (ts.isAsExpression(node) || ts.isTypeAssertionExpression(node)) {
      // A type assertion is a compile-time claim too.
      return this.lowerExpression(node.expression);
    }
    if (ts.isPropertyAccessExpression(node)) {
      if (node.questionDotToken) {
        return this.lowerOptionalChain(node);
      }
      return this.lowerPropertyRead(node);
    }
    if (ts.isElementAccessExpression(node)) {
      if (node.questionDotToken) {
        return this.lowerOptionalChain(node);
      }
      return this.lowerElementRead(node);
    }
    if (ts.isCallExpression(node)) {
      if (node.questionDotToken) {
        return this.lowerOptionalChain(node);
      }
      if (node.expression && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
        // A dynamic import: the compiler parses it as a call whose callee is
        // the `import` keyword.
        return this.lowerDynamicImport(node);
      }
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
    if (ts.isConditionalExpression(node)) {
      return this.lowerConditional(node);
    }
    if (ts.isVoidExpression(node)) {
      return this.lowerVoid(node);
    }
    if (ts.isTaggedTemplateExpression(node)) {
      return this.lowerTaggedTemplate(node);
    }
    if (ts.isRegularExpressionLiteral(node)) {
      // A regular expression is an immutable constant.
      return constOp(CONST_REGEX, node.text);
    }
    if (ts.isMetaProperty(node)) {
      return this.lowerImportMeta(node);
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
   * <p>The key is a NAME in a namespace, so this is a container access with a
   * string key and not a field access.
   */
  lowerPropertyRead(node, receiver) {
    if (node.expression && node.expression.kind === ts.SyntaxKind.SuperKeyword) {
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

  /** A computed read: `obj[key]`. */
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
    if (ts.isPropertyAccessExpression(node)) {
      value = this.lowerPropertyRead(node, receiver);
    } else if (ts.isElementAccessExpression(node)) {
      value = this.lowerElementRead(node, receiver);
    } else {
      // `rec.f?.()`: the check above is on the FUNCTION -- it is the function
      // that may be missing -- but the call's RECEIVER is the object the
      // function was read from, because that is what `this` is. Passing the
      // function value as the receiver made the call look like a method of
      // the function itself.
      const object =
        node.expression && ts.isPropertyAccessExpression(node.expression)
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
   * <p>The receiver slot is reserved whether or not the call has a receiver
   * object: `args[0]` is the receiver, `args[1..]` are the arguments.
   */
  lowerCall(node, receiver) {
    const signature = this.resolveSignature(node);
    const args = node.arguments ?? [];
    const wireArgs = [];
    const owner = this.ownerOf(node);
    let slot;
    if (node.expression && node.expression.kind === ts.SyntaxKind.SuperKeyword) {
      // `super(...)`: the base class's constructor, called on THIS object.
      slot = varOp("this", TYPE.UNKNOWN);
    } else if (node.expression && ts.isPropertyAccessExpression(node.expression)) {
      slot = receiver ?? this.lowerExpression(node.expression.expression);
    } else {
      // A free function has no receiver object, so the slot holds the OWNER
      // as a constant: the module the function was imported from.
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
    if (!signature && !inline) {
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
    return dest;
  }

  /**
   * A component of the declarative UI DSL: `Column() { ... }`.
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
    if (this.constructedClassName(node.expression) === "Function") {
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
   */
  constructorSig(node, arity) {
    const name = this.constructedClassName(node.expression);
    return name ? `${name}.<init>/${arity}` : undefined;
  }

  /** The dotted name of the class a construction names. */
  constructedClassName(expression) {
    if (!expression) {
      return undefined;
    }
    if (ts.isIdentifier(expression)) {
      return expression.text;
    }
    if (ts.isPropertyAccessExpression(expression)) {
      const name = expression.name?.text;
      if (!name) {
        return undefined;
      }
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
    const logical = LOGICAL_COMPOUND_OPERATORS[operatorKind];
    if (logical) {
      return this.lowerLogicalAssignment(node, logical);
    }
    const compound = COMPOUND_OPERATORS[operatorKind];
    if (compound) {
      return this.lowerAssignment(node, compound);
    }
    if (operatorKind === ts.SyntaxKind.InstanceOfKeyword) {
      return this.lowerInstanceOf(node);
    }
    if (operatorKind === ts.SyntaxKind.InKeyword) {
      return this.lowerIn(node);
    }
    if (operatorKind === ts.SyntaxKind.CommaToken) {
      return this.lowerComma(node);
    }
    if (
      operatorKind === ts.SyntaxKind.AmpersandAmpersandToken ||
      operatorKind === ts.SyntaxKind.BarBarToken ||
      operatorKind === ts.SyntaxKind.QuestionQuestionToken
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
   * A short-circuit operator: `a && b`, `a || b` and `a ?? b`.
   */
  lowerShortCircuit(node) {
    const operatorKind = node.operatorToken.kind;
    const left = this.lowerExpression(node.left);
    const dest = this.state.newTemp(this.typeOf(node));
    this.emit({ kind: KIND.MOVE, dest, lhs1: left, ...this.position(node) });
    const rhsLabel = this.state.newLabel();
    const endLabel = this.state.newLabel();
    if (operatorKind === ts.SyntaxKind.AmpersandAmpersandToken) {
      this.emitBranch(left, rhsLabel, endLabel, truthiness(left), node);
    } else if (operatorKind === ts.SyntaxKind.BarBarToken) {
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

  /** A logical compound assignment: `a &&= b`, `a ||= b` and `a ??= b`. */
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

  /** `k in obj`: the property is there or it is not. */
  lowerIn(node) {
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

  /** A tagged template: `tag`a${x}b``. */
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
   * `import.meta`: one object per module.
   */
  lowerImportMeta(node) {
    if (!this.metaFunction) {
      this.lowerNested(node, () => {
        const state = this.beginFunction("__meta", `${this.moduleName}.__meta()`, null, true);
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
          for (const property of this.metaProperties ?? []) {
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
   */
  lowerDynamicImport(node) {
    const argument = (node.arguments ?? [])[0];
    const literal = argument && ts.isStringLiteral(argument) ? argument.text : undefined;
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
    if (ts.isObjectBindingPattern(target) || ts.isArrayBindingPattern(target)) {
      this.bindPattern(target, value, node);
      return;
    }
    this.report(PROBLEM.UNHANDLED_NODE, target, "this assignment target has no TAC lowering");
  }

  /**
   * The operator of a unary expression.
   *
   * <p>The ArkTS parser does NOT put the operator token on the node: it puts
   * the token's KIND there instead, as a number. The classic shape is read as
   * well, so the same translator works on either parser.
   */
  unaryOperatorKind(node) {
    if (typeof node.operator === "number") {
      return node.operator;
    }
    return node.operatorToken ? node.operatorToken.kind : undefined;
  }

  lowerPrefixUnary(node) {
    const kind = this.unaryOperatorKind(node);
    if (kind === ts.SyntaxKind.PlusPlusToken || kind === ts.SyntaxKind.MinusMinusToken) {
      // A prefix `++` changes the variable FIRST and yields the new value.
      const value = this.lowerExpression(node.operand);
      const updated = this.state.newTemp(this.typeOf(node));
      this.emit({
        kind: KIND.UNARYOP,
        dest: updated,
        lhs1: value,
        unop: kind === ts.SyntaxKind.PlusPlusToken ? "PRE_INC" : "PRE_DEC",
        ...this.position(node),
      });
      this.lowerWrite(node.operand, updated, node);
      return updated;
    }
    const operator = kind === undefined ? undefined : UNARY_OPERATORS[kind];
    const operand = this.lowerExpression(node.operand);
    if (!operator) {
      if (kind === ts.SyntaxKind.PlusToken) {
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
   */
  lowerPostfixUnary(node) {
    const kind = this.unaryOperatorKind(node);
    if (kind !== ts.SyntaxKind.PlusPlusToken && kind !== ts.SyntaxKind.MinusMinusToken) {
      this.report(PROBLEM.UNHANDLED_NODE, node, "this operator has no TAC lowering");
      return this.emitHavoc(node);
    }
    const before = this.lowerExpression(node.operand);
    const after = this.state.newTemp(this.typeOf(node));
    this.emit({
      kind: KIND.UNARYOP,
      dest: after,
      lhs1: before,
      unop: kind === ts.SyntaxKind.PlusPlusToken ? "POST_INC" : "POST_DEC",
      ...this.position(node),
    });
    this.lowerWrite(node.operand, after, node);
    return before;
  }

  /**
   * An object literal.
   *
   * <p>The members of a literal are NOT all properties with an initialiser. A
   * method, a getter and a setter are members of their own, and a shorthand
   * property stands for a name rather than for a value. Reading
   * `property.initializer` for those shapes gets `undefined`, and an undefined
   * value is a member that was never lowered: the body of a method would be
   * gone, and with it every sink in it, with no drop and no problem to say so.
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
    // The identity of the literal. An object literal has no name of its own,
    // so its members are given one: the function table is keyed by the
    // signature, and two literals that each have a `run` would otherwise share
    // one signature and the second one's body would replace the first one's.
    const owner = `object$${this.objectCounter += 1}`;
    for (const property of node.properties) {
      if (ts.isSpreadAssignment(property)) {
        this.lowerObjectSpread(property, dest);
        continue;
      }
      if (!property.name) {
        continue;
      }
      const key = constOp(CONST.STRING, nameTextOf(property.name) ?? "");
      let value;
      if (
        ts.isMethodDeclaration(property) ||
        ts.isGetAccessor(property) ||
        ts.isSetAccessor(property)
      ) {
        value = this.lowerObjectMember(property, owner);
      } else if (ts.isShorthandPropertyAssignment(property)) {
        // `{ cmd }` stands for the value of `cmd`: the name is a shorthand for
        // the property and the variable of the same name.
        value = this.lowerExpression(property.name);
      } else {
        value = this.lowerExpression(property.initializer);
      }
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
   * A method or an accessor of an object literal.
   *
   * <p>Its body is a function of its own, the way the body of a class's method
   * is: the TAC has no nested function, so the instructions of a body cannot
   * live in the stream of the function around it. A sink in the body is still
   * a sink, and it is still found -- but only if the body is lowered at all,
   * and reading `initializer` (which a method does not have) lowers nothing.
   *
   * <p>The body has a scope of its own, so the names it reads from the scope
   * around it are not in it. That loss is recorded, the way the same loss is
   * recorded when an immediately invoked function is lifted.
   *
   * <p>The VALUE the literal stores is a function, and the TAC has no function
   * value: the property holds an unknown value. The name of the method is
   * kept, though, so that a call of `literal.run(x)` finds this function --
   * the call key of a property call names the method and not the object, and
   * the engine matches a function by that key.
   *
   * @param {object} node the method or accessor
   * @param {string} owner the identity of the literal the member belongs to
   * @returns {object} the value the property holds: unknown
   */
  lowerObjectMember(node, owner) {
    const name = node.name ? node.name.text : "<anonymous>";
    const memberKind = ts.isGetAccessor(node)
      ? MEMBER_KIND.GETTER
      : ts.isSetAccessor(node)
        ? MEMBER_KIND.SETTER
        : MEMBER_KIND.METHOD;
    const previous = this.state;
    const state = this.beginFunction(
      name,
      this.functionSignature(node, owner),
      owner,
      true,
    );
    state.memberKind = memberKind;
    this.guard(node, () => {
      this.lowerParameters(node.parameters);
      // `this` is the object the literal builds. It is not the same object as
      // the one the literal is written in, and the TAC has no way to say which
      // one it is, so the receiver is unknown.
      this.declareThis();
      if (node.body) {
        this.lowerBlock(node.body);
      }
    });
    if (node.type) {
      state.returnType = this.typeOf(node.type);
    }
    const finished = this.finishFunction(state);
    this.state = previous;
    this.pendingFunctions.push(finished);
    // The loss is recorded on the function that holds the literal, because
    // that is the function whose shape changed.
    this.emitDrop(
      "object-literal-method",
      "the body of a method in an object literal is lowered into a function of its own, and the"
        + " names it read from the scope around it are not in that function; the value the"
        + " literal stores for it is unknown",
      IMPACT.STRUCTURE,
      node,
    );
    return this.emitHavoc(node);
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

  // ---- the class's own members ------------------------------------------------------------------

  /** The name of a field, with the `#` of a private field kept. */
  fieldNameOf(field) {
    const name = field.name;
    if (!name) {
      return "";
    }
    if (ts.isPrivateIdentifier(name)) {
      return `#${name.text.replace(/^#/, "")}`;
    }
    return nameTextOf(name) ?? "";
  }

  /**
   * The compile-time markers of a field: readonly, visibility, override, and
   * the state-management decorators.
   */
  fieldAnnotationsOf(field) {
    const annotations = [];
    for (const modifier of field.modifiers ?? []) {
      const kind = modifier.kind;
      if (kind === ts.SyntaxKind.ReadonlyKeyword) {
        annotations.push("readonly");
      }
      if (kind === ts.SyntaxKind.PublicKeyword) {
        annotations.push("public");
      }
      if (kind === ts.SyntaxKind.PrivateKeyword) {
        annotations.push("private");
      }
      if (kind === ts.SyntaxKind.ProtectedKeyword) {
        annotations.push("protected");
      }
      if (kind === ts.SyntaxKind.OverrideKeyword) {
        annotations.push("override");
      }
      if (kind === ts.SyntaxKind.StaticKeyword) {
        annotations.push("static");
      }
      if (ts.isDecorator(modifier)) {
        const name = decoratorNameOf(modifier);
        if (name) {
          annotations.push(name);
          if (STATE_DECORATORS.has(name)) {
            // The decorator's own arguments -- `@Provide("key")` -- are the
            // annotation's values.
            const values = decoratorArgumentsOf(modifier);
            if (values.length > 0) {
              this.state.annotationValues[name] = values;
            }
          }
        }
      }
    }
    return annotations;
  }

  /** The names of the decorators on a node. */
  decoratorNamesOf(node) {
    const names = [];
    for (const modifier of node.modifiers ?? []) {
      if (!ts.isDecorator(modifier)) {
        continue;
      }
      const name = decoratorNameOf(modifier);
      if (name) {
        names.push(name);
      }
    }
    return names;
  }

  /**
   * The class decorators that RUN at run time.
   *
   * <p>A decorator the compiler knows by itself never runs: the compiler
   * generates what it means. A decorator the compiler does NOT know is an
   * ordinary function, and it is called with the class.
   */
  lowerClassDecorators(node, className) {
    for (const modifier of node.modifiers ?? []) {
      if (!ts.isDecorator(modifier)) {
        continue;
      }
      const name = decoratorNameOf(modifier);
      if (!name || BUILTIN_DECORATORS.has(name)) {
        // A built-in decorator produces NO call: there is no such function at
        // run time. Its name is an annotation, and that is all.
        continue;
      }
      const arguments_ = this.lowerDecoratorArguments(modifier);
      const dest = this.state.newTemp(TYPE.UNKNOWN);
      this.emit({
        kind: KIND.CALL,
        calleeSig: calleeSigOf(null, name, 1),
        dest,
        args: [varOp(className, { kind: TYPE.CLASS, name: className })],
        ...this.position(modifier),
      });
      void arguments_;
    }
  }

  /**
   * The member decorators that RUN at run time.
   */
  lowerMemberDecorators(node, className, memberName) {
    if (!className) {
      return;
    }
    const annotations = [];
    for (const modifier of node.modifiers ?? []) {
      if (!ts.isDecorator(modifier)) {
        continue;
      }
      const name = decoratorNameOf(modifier);
      if (!name) {
        continue;
      }
      annotations.push(name);
      if (BUILTIN_DECORATORS.has(name)) {
        // A built-in decorator produces NO call.
        const values = decoratorArgumentsOf(modifier);
        if (values.length > 0) {
          this.state.annotationValues[name] = values;
        }
        continue;
      }
      this.lowerDecoratorArguments(modifier);
      const dest = this.state.newTemp(TYPE.UNKNOWN);
      this.emit({
        kind: KIND.CALL,
        calleeSig: calleeSigOf(null, name, 2),
        dest,
        args: [
          varOp("prototype", { kind: TYPE.CLASS, name: className }),
          constOp(CONST.STRING, memberName),
        ],
        ...this.position(modifier),
      });
    }
    if (annotations.length > 0) {
      this.state.methodAnnotations = annotations;
    }
    // A concurrent function runs on another thread: the engine has to know
    // that its instructions are not in this thread's order.
    if (this.isConcurrent(node)) {
      this.state.effect = "CONCURRENT";
      if (!annotations.includes("Concurrent")) {
        annotations.push("Concurrent");
      }
      this.state.methodAnnotations = annotations;
    }
  }

  /** Whether a member is marked `@Concurrent` or `@Sendable`. */
  isConcurrent(node) {
    return this.decoratorNamesOf(node).some(
      (name) => name === "Concurrent" || name === "Sendable",
    );
  }

  /** The arguments of a decorator, lowered like any other expression. */
  lowerDecoratorArguments(decorator) {
    const args = [];
    for (const argument of decorator.expression?.arguments ?? []) {
      args.push(this.lowerExpression(argument));
    }
    return args;
  }

  /** The name of the base class, when the class extends one. */
  baseClassNameOf(node) {
    for (const clause of node.heritageClauses ?? []) {
      if (clause.token !== ts.SyntaxKind.ExtendsKeyword) {
        continue;
      }
      const first = clause.types?.[0];
      const expression = first?.expression;
      if (!expression) {
        return undefined;
      }
      return expression.getText ? expression.getText() : expression.text;
    }
    return undefined;
  }

  /** The interfaces the class implements. */
  implementedInterfacesOf(node) {
    const names = [];
    for (const clause of node.heritageClauses ?? []) {
      if (clause.token !== ts.SyntaxKind.ImplementsKeyword) {
        continue;
      }
      for (const type of clause.types ?? []) {
        const expression = type.expression;
        const name = expression?.getText ? expression.getText() : expression?.text;
        if (name) {
          names.push(name);
        }
      }
    }
    return names;
  }

  /**
   * The number of parameters the base class's constructor declares.
   *
   * <p>The compiler knows this number: the `super(...)` call resolves to the
   * base constructor's own signature, and that signature lists the declared
   * parameters. Reading it there means the answer does not depend on the base
   * class living in the same file -- a scan of the file's own class
   * declarations cannot see a base class in another module, and it answered
   * "zero parameters" there, which named a function that does not exist.
   *
   * <p>When the compiler cannot resolve the call -- no `super(...)` statement
   * to resolve, or no checker -- the file's own declarations are the fallback,
   * and a base class that is in neither place has no known parameter count.
   */
  baseConstructorArity(base, superCall) {
    if (superCall) {
      const signature = this.resolveSignature(superCall);
      if (signature && Array.isArray(signature.parameters) && signature.parameters.length > 0) {
        return signature.parameters.length;
      }
    }
    for (const statement of this.sourceFile.statements ?? []) {
      if (!ts.isClassDeclaration(statement) && !ts.isStructDeclaration(statement)) {
        continue;
      }
      if (!statement.name || statement.name.text !== base) {
        continue;
      }
      for (const member of statement.members ?? []) {
        if (ts.isConstructorDeclaration(member)) {
          return (member.parameters ?? []).filter((p) => !isThisParameter(p)).length;
        }
      }
    }
    return 0;
  }

  /**
   * Whether a statement is a call to `super(...)`.
   *
   * <p>The shape is what matters, not the identity of the node: the base
   * constructor is called once, and the constructor emits that call. So a
   * statement of this shape is left alone wherever it sits -- in the body's
   * own statement list, or nested inside a branch.
   *
   * @param {object} statement the statement
   * @return {boolean} whether it is a `super(...)` call
   */
  isSuperCallStatement(statement) {
    if (!ts.isExpressionStatement(statement)) return false;
    const expression = statement.expression;
    if (!ts.isCallExpression(expression)) return false;
    return expression.expression?.kind === ts.SyntaxKind.SuperKeyword;
  }

  /**
   * The `super(...)` call in a constructor's body, or null when there is none.
   *
   * <p>The call is usually the first statement of the body, but it does not
   * have to be: a class without initialised properties or parameter
   * properties may put the call inside a branch. So the body is searched at
   * every depth -- and the compiler's own checker resolves the call it finds
   * there, which is how the base constructor's declared parameter count is
   * known even when the base class lives in another module.
   *
   * @param {object} node the constructor declaration
   * @return {object|null} the call expression, or null
   */
  findSuperCall(node) {
    return this.findSuperCallIn(node?.body?.statements ?? []);
  }

  /**
   * The first `super(...)` call in a list of statements, at any depth.
   *
   * @param {Array} statements the statements
   * @return {object|null} the call expression, or null
   */
  findSuperCallIn(statements) {
    for (const statement of statements ?? []) {
      if (this.isSuperCallStatement(statement)) return statement.expression;
      const nested = this.nestedSuperCallOf(statement);
      if (nested) return nested;
    }
    return null;
  }

  /**
   * The `super(...)` call inside a statement that can hold one, or null.
   *
   * @param {object} statement the statement
   * @return {object|null} the call expression, or null
   */
  nestedSuperCallOf(statement) {
    if (ts.isBlock(statement)) return this.findSuperCallIn(statement.statements);
    if (ts.isIfStatement(statement)) {
      const inThen = this.findSuperCallIn(
        statement.thenStatement ? [statement.thenStatement] : [],
      );
      if (inThen) return inThen;
      if (statement.elseStatement) return this.findSuperCallIn([statement.elseStatement]);
      return null;
    }
    if (ts.isTryStatement(statement)) {
      const blocks = [];
      if (statement.tryBlock) blocks.push(statement.tryBlock);
      if (statement.catchClause?.block) blocks.push(statement.catchClause.block);
      if (statement.finallyBlock) blocks.push(statement.finallyBlock);
      return this.findSuperCallIn(blocks);
    }
    if (ts.isSwitchStatement(statement)) {
      const clauses = statement.caseBlock?.clauses ?? [];
      for (const clause of clauses) {
        const found = this.findSuperCallIn(clause.statements);
        if (found) return found;
      }
      return null;
    }
    if (
      ts.isWhileStatement(statement) ||
      ts.isDoStatement(statement) ||
      ts.isForStatement(statement) ||
      ts.isForOfStatement(statement) ||
      ts.isForInStatement(statement) ||
      ts.isLabeledStatement(statement)
    ) {
      return statement.statement ? this.findSuperCallIn([statement.statement]) : null;
    }
    return null;
  }

  // ---- modules -----------------------------------------------------------------------------------

  /**
   * A static import: it produces NO instruction at all.
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

  /** An export: it produces no instruction either. */
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

  // ---- helpers -----------------------------------------------------------------------------------

  emit(instruction) {
    if (this.state.effect) {
      // A concurrent function's instructions do not run in this thread's
      // order, and the engine has to know that.
      instruction.effect = this.state.effect;
    }
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

  /**
   * Lower one function's body, reporting anything that goes wrong.
   */
  guard(node, body) {
    try {
      body();
    } catch (e) {
      if (process.env.PLUMB_DEBUG) {
        console.error(`plumb-arkts-sidecar: ${e && e.stack ? e.stack : String(e)}`);
      }
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
    const mapped = mapType(type, this.checker, this);
    // Nullability checks are off in this compiler, so it answers "string" for
    // a type written `string | null`: the union is gone before the translator
    // sees it. The source still says it, so the source is read for the fact
    // the compiler dropped.
    if (mapped && typeNodeIsNullable(node.type)) {
      return { ...mapped, nullable: true };
    }
    return mapped;
  }

  /**
   * The TAC type of a type node, with the nullability the source says.
   */
  typeOfTypeNode(typeNode) {
    const mapped = this.typeOf(typeNode);
    if (mapped && typeNodeIsNullable(typeNode)) {
      return { ...mapped, nullable: true };
    }
    return mapped;
  }

  typeTextOf(node) {
    const type = this.typeOf(node);
    return type ? typeText(type) : "any";
  }

  returnTypeTextOf(node) {
    const type = node.type ? this.typeOfTypeNode(node.type) : undefined;
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
   *
   * <p>The owner is resolved by a chain of five steps, and the last one is
   * "the owner is missing": the callee key then has no owner section at all.
   */
  ownerOf(node) {
    const expression = node.expression;
    if (expression && expression.kind === ts.SyntaxKind.SuperKeyword) {
      return this.enclosingBase ?? null;
    }
    if (expression && ts.isPropertyAccessExpression(expression)) {
      const base = expression.expression;
      if (base && base.kind === ts.SyntaxKind.SuperKeyword) {
        return this.enclosingBase ?? null;
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
      if (base && base.kind === ts.SyntaxKind.ThisKeyword) {
        // The class's IDENTITY: the module it lives in plus its name.  The call key and
        // the declaration have to agree on it, or the call graph cannot connect them --
        // and the simple name does not let them: two files that each declare a class
        // called "Holder" share it, and the call would land on whichever of them was
        // registered first.
        return this.state?.enclosingClass ?? null;
      }
      // `vm.runInNewContext(...)`: the base is a namespace import, so the
      // owner is the module the namespace came from.
      if (ts.isIdentifier(base)) {
        const binding = this.imports.get(base.text);
        if (binding && binding.isNamespace) {
          return binding.module;
        }
      }
      // The receiver's CLASS is what a catalogue can name.
      //
      // The name carries the NAMESPACES the receiver's declaration lives in:
      // the call key and the declaration have to spell the same class, and a
      // class in a namespace is not the class of the same name at the top
      // level.
      const name = this.qualifiedNameOfNode(base);
      if (name) {
        return this.qualifiedClassName(name, node);
      }
      // A primitive receiver: the owner is the wrapper class.
      const wrapper = this.primitiveWrapperOf(base);
      if (wrapper) {
        return wrapper;
      }
      // A known global: `console.log(x)` belongs to `console`.
      if (ts.isIdentifier(base) && GLOBAL_OWNERS.has(base.text)) {
        return base.text;
      }
      return null;
    }
    if (expression && ts.isIdentifier(expression)) {
      // `exec(cmd)`, where `exec` was imported: the owner is the module it
      // came from, not the file the call is written in.
      const binding = this.imports.get(expression.text);
      if (binding) {
        return binding.module;
      }
      // A known global: `eval(x)` belongs to the global it came from, so the
      // owner is that global's own name. It is NOT the same as the global
      // object as a RECEIVER -- `console.log(x)` names `console` because the
      // method belongs to that object -- and the difference matters: a call
      // whose receiver nobody could type has NO owner, so the key tells the
      // two apart.
      if (GLOBAL_OWNERS.has(expression.text)) {
        return expression.text;
      }
      return null;
    }
    return null;
  }

  /** The owner of a call whose callee is an expression, such as a tag. */
  ownerOfExpression(expression) {
    if (!expression) {
      return null;
    }
    if (ts.isIdentifier(expression)) {
      const binding = this.imports.get(expression.text);
      if (binding) {
        return binding.module;
      }
      // A known global: it keeps its own name as the owner, the same as in
      // `ownerOf`. See there for why the two are told apart.
      if (GLOBAL_OWNERS.has(expression.text)) {
        return expression.text;
      }
      return null;
    }
    if (ts.isPropertyAccessExpression(expression)) {
      const base = expression.expression;
      if (ts.isIdentifier(base)) {
        const binding = this.imports.get(base.text);
        if (binding && binding.isNamespace) {
          return binding.module;
        }
      }
      const name = this.classNameOfNode(base);
      if (name) {
        return name;
      }
      const wrapper = this.primitiveWrapperOf(base);
      if (wrapper) {
        return wrapper;
      }
      if (ts.isIdentifier(base) && GLOBAL_OWNERS.has(base.text)) {
        return base.text;
      }
    }
    return null;
  }

  /**
   * The name of a node's class, when its type is a class with a name.
   */
  /**
   * The receiver's name, qualified with the NAMESPACES it lives in.
   *
   * <p>A class's identity is the module it lives in plus its name -- and a
   * class that lives in a namespace has the namespace in its identity too, or
   * two namespaces of one file would each declare a class of the same name.
   *
   * <p>The namespace path is asked of the COMPILER: the receiver's type names
   * the declaration, and the declaration's symbol knows the namespaces it is
   * written in. So the key the call site writes and the signature the
   * declaration carries spell the same class.
   *
   * @param {object} node the receiver
   * @return the qualified name, or undefined when the receiver has no name
   */
  qualifiedNameOfNode(node) {
    const name = this.classNameOfNode(node);
    if (!name) return undefined;
    const path = this.namespacePathOfNode(node);
    return path ? `${path}.${name}` : name;
  }

  /**
   * The namespaces the receiver's declaration is written in, outermost first.
   *
   * <p>The compiler's symbol chain is the answer: a method's symbol's parent
   * is the class, the class's parent is the namespace, and the namespace's
   * parent is the module. The chain therefore names the namespaces on the way
   * out, and they are read in reverse.
   *
   * @param {object} node the receiver
   * @return the dotted path, or null when the receiver is not in a namespace
   */
  namespacePathOfNode(node) {
    if (!node || !this.checker) return null;
    let symbol;
    try {
      const type = this.checker.getTypeAtLocation(node);
      symbol = type && type.getSymbol();
    } catch (e) {
      return null;
    }
    if (!symbol) return null;
    const parts = [];
    // The first symbol is the receiver's OWN declaration: the class, or the
    // namespace. The ones above it are the namespaces it lives in.
    let current = parentSymbolOf(symbol);
    while (current) {
      const name = namespaceNameOfSymbol(current);
      if (name === null) break;
      parts.unshift(name);
      current = parentSymbolOf(current);
    }
    return parts.length ? parts.join(".") : null;
  }
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

  /**
   * The class's name with the module it lives in in front of it.
   *
   * <p>A class of THIS project is named by its module plus its name; a class of
   * a LIBRARY keeps its own name, because the sink catalogue names library
   * classes that way and a module prefix would stop the catalogue from
   * claiming the call.
   *
   * @param {string} className the class's name
   * @param {object} callNode the call the receiver belongs to
   * @return {string} the qualified name, or the bare one
   */
  qualifiedClassName(className, callNode) {
    if (!className) return className;
    const module = this.moduleOfCallee(callNode);
    return module ? `${module}.${className}` : className;
  }

  /**
   * The module the CALLEE of a call is declared in, or null when the callee is
   * not part of the analysed program.
   *
   * <p>The compiler's resolved signature is the answer: it names the very
   * declaration the call reaches, so the module is the one that declaration
   * lives in.
   *
   * @param {object} callNode the call
   * @return {string} the module path, or null
   */
  moduleOfCallee(callNode) {
    if (!callNode || !this.checker || !this.moduleNameOf) return null;
    const signature = this.resolveSignature(callNode);
    return this.moduleOfDeclaration(signature && signature.declaration);
  }

  /**
   * The module a declaration lives in, or null when the declaration is not
   * part of the analysed program.
   *
   * <p>A LIBRARY class has no module here. The sink catalogue names library
   * classes by their own name -- `Console.log`, not the path of the file the
   * standard library happens to be installed in -- and a module prefix would
   * stop the catalogue from claiming the call.
   *
   * <p>The module comes from the compiler's own answer rather than from a
   * guess about the import that brought the class in: the resolved signature
   * names the very declaration the call reaches.
   *
   * @param {object} node the declaration
   * @return {string} the module path, or null
   */
  moduleOfDeclaration(node) {
    if (!node || !this.moduleNameOf) return null;
    let filePath;
    try {
      if (typeof node.getSourceFile === "function") {
        const file = node.getSourceFile();
        filePath = file && file.fileName;
      }
    } catch (e) {
      filePath = undefined;
    }
    if (!filePath) filePath = node.path;
    if (!filePath) return null;
    // The file of a LIBRARY is not one of the project's own files, and the
    // class it declares keeps its own name.  The source root cannot tell them
    // apart -- a dependency sits INSIDE the project -- and the path of the
    // file is not a module name either: on another drive the relative path is
    // the absolute one.
    if (!this.isProjectFile || !this.isProjectFile(filePath)) return null;
    let module;
    try {
      module = this.moduleNameOf(filePath);
    } catch (e) {
      return null;
    }
    if (!module) return null;
    return module;
  }

  /** The wrapper class of a primitive receiver. */
  primitiveWrapperOf(node) {
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
    const wrapper = wrapperOfType(type);
    if (wrapper) {
      return wrapper;
    }
    if (type.flags & ts.TypeFlags.Union) {
      for (const member of type.types ?? []) {
        if (member.flags & (ts.TypeFlags.Null | ts.TypeFlags.Undefined)) {
          continue;
        }
        const found = wrapperOfType(member);
        if (found) {
          return found;
        }
      }
    }
    return undefined;
  }

  methodNameOf(node) {
    const expression = node.expression;
    if (expression && expression.kind === ts.SyntaxKind.SuperKeyword) {
      // `super(...)` calls the base class's constructor.
      return "<init>";
    }
    if (expression && ts.isPropertyAccessExpression(expression)) {
      return expression.name.text;
    }
    if (expression && ts.isIdentifier(expression)) {
      // An aliased import calls the name it was exported under.
      const binding = this.imports.get(expression.text);
      if (binding && binding.exported) {
        return binding.exported;
      }
      return expression.text;
    }
    return "<call>";
  }

  /** The name of the method an expression names. */
  methodNameOfExpression(expression) {
    if (!expression) {
      return "<call>";
    }
    if (ts.isPropertyAccessExpression(expression)) {
      return expression.name.text;
    }
    if (ts.isIdentifier(expression)) {
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
   * <p>A callee that could NOT be resolved has no declared parameters to
   * count, so the arity is the number of arguments that were passed.
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
  if (!type) {
    return { kind: TYPE.UNKNOWN, nullable: true };
  }
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
  if (!type) {
    return undefined;
  }
  return type.domain ?? undefined;
}

/** Whether a parameter is the `this` parameter. */
function isThisParameter(parameter) {
  return !!parameter && !!parameter.name && parameter.name.text === "this";
}

/**
 * Whether a written type says the value can be null.
 *
 * <p>Nullability checks are off in this compiler, so the union `string | null`
 * is collapsed into `string` before the translator ever sees it. The SOURCE
 * still says it, and the fact matters: a value the source calls nullable is
 * not the same value as one the source calls not nullable.
 */
function typeNodeIsNullable(typeNode) {
  if (!typeNode || !ts.isUnionTypeNode(typeNode)) {
    return false;
  }
  return typeNode.types.some(
    (member) =>
      ts.isLiteralTypeNode(member) &&
      (member.literal.kind === ts.SyntaxKind.NullKeyword ||
        member.literal.kind === ts.SyntaxKind.UndefinedKeyword),
  );
}

/** The name of a decorator, when it names one. */
function decoratorNameOf(decorator) {
  const expression = decorator?.expression;
  if (!expression) {
    return undefined;
  }
  if (ts.isIdentifier(expression)) {
    return expression.text;
  }
  if (ts.isPropertyAccessExpression(expression)) {
    return expression.name?.text;
  }
  return undefined;
}

/** The literal arguments of a decorator, as strings. */
function decoratorArgumentsOf(decorator) {
  const values = [];
  for (const argument of decorator?.expression?.arguments ?? []) {
    if (ts.isStringLiteral(argument) || ts.isNoSubstitutionTemplateLiteral(argument)) {
      values.push(argument.text);
    } else if (ts.isNumericLiteral(argument)) {
      values.push(argument.text);
    }
  }
  return values;
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

/** The domain of a JavaScript number: a double, with 53 bits of precision. */
const FLOATING_DOMAIN = { kind: "Floating", bits: 53, exponentBits: 11 };

/** The domain of a type nothing is known about. */
const DYNAMIC_DOMAIN = { kind: "Dynamic" };

/**
 * The TAC type of a compiler type.
 *
 * <p>The dispatch is on the type's STRUCTURE: a union keeps its members, an
 * array keeps its element type, and a literal keeps the type it is a literal
 * of.
 */
function mapType(type, checker, translator) {
  if (!type) {
    return undefined;
  }
  const flags = type.flags;

  if (flags & ts.TypeFlags.Union) {
    const members = type.types ?? [];
    const mapped = members.map((member) => mapType(member, checker, translator)).filter(Boolean);
    const hasNullish = members.some(
      (member) => member.flags & (ts.TypeFlags.Null | ts.TypeFlags.Undefined),
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

  if (flags & ts.TypeFlags.String) {
    return { kind: TYPE.STRING };
  }
  if (flags & ts.TypeFlags.Number) {
    return { kind: TYPE.DOUBLE, domain: FLOATING_DOMAIN };
  }
  if (flags & ts.TypeFlags.BooleanLike) {
    return { kind: TYPE.BOOLEAN };
  }
  if (flags & ts.TypeFlags.BigIntLike) {
    // A bigint is of arbitrary precision: the domain says so.
    return { kind: TYPE_BIGINT, domain: { kind: "Unbounded" } };
  }
  if (flags & ts.TypeFlags.ESSymbolLike) {
    return { kind: TYPE.UNKNOWN, domain: DYNAMIC_DOMAIN };
  }
  if (flags & ts.TypeFlags.VoidLike) {
    return { kind: TYPE.VOID };
  }
  if (flags & ts.TypeFlags.Null) {
    return { kind: TYPE.NULL };
  }
  if (flags & ts.TypeFlags.Undefined) {
    return { kind: TYPE.NULL };
  }
  if (flags & ts.TypeFlags.Never) {
    return { kind: TYPE.VOID };
  }
  if (flags & ts.TypeFlags.Any || flags & ts.TypeFlags.Unknown) {
    return { kind: TYPE.UNKNOWN, domain: DYNAMIC_DOMAIN };
  }
  if (flags & ts.TypeFlags.TypeParameter) {
    return { kind: TYPE.UNKNOWN, domain: DYNAMIC_DOMAIN };
  }
  if (flags & ts.TypeFlags.StringLiteral) {
    return { kind: TYPE.STRING };
  }
  if (flags & ts.TypeFlags.NumberLiteral) {
    return { kind: TYPE.DOUBLE, domain: FLOATING_DOMAIN };
  }
  if (flags & ts.TypeFlags.BooleanLiteral) {
    return { kind: TYPE.BOOLEAN };
  }
  if (flags & ts.TypeFlags.BigIntLiteral) {
    return { kind: TYPE_BIGINT, domain: { kind: "Unbounded" } };
  }

  if (flags & ts.TypeFlags.Object) {
    const arguments_ = typeArgumentsOf(type, checker);
    if (checker && checker.isTupleType && checker.isTupleType(type)) {
      const component =
        arguments_.length > 0 ? mapType(arguments_[0], checker, translator) : undefined;
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

/** The type arguments of a generic type, when the compiler can give them. */
function typeArgumentsOf(type, checker) {
  if (!checker || typeof checker.getTypeArguments !== "function") {
    return [];
  }
  try {
    return Array.from(checker.getTypeArguments(type) ?? []);
  } catch (e) {
    return [];
  }
}

/** The wrapper class of a primitive type: `String`, `Number`, `Boolean`, `BigInt`. */
function wrapperOfType(type) {
  if (!type) {
    return undefined;
  }
  const flags = type.flags;
  if (flags & ts.TypeFlags.StringLike) {
    return "String";
  }
  if (flags & ts.TypeFlags.NumberLike) {
    return "Number";
  }
  if (flags & ts.TypeFlags.BooleanLike) {
    return "Boolean";
  }
  if (flags & ts.TypeFlags.BigIntLike) {
    return "BigInt";
  }
  return undefined;
}

/**
 * A symbol's parent, or null.
 *
 * <p>The two compiler builds spell it differently: the native preview has an
 * accessor, the classic one a property. Both are asked for, and a build that
 * has neither simply has no parent to offer.
 *
 * @param {object} symbol the symbol
 * @return the parent symbol, or null
 */
function parentSymbolOf(symbol) {
  if (!symbol) return null;
  try {
    if (typeof symbol.getParent === "function") return symbol.getParent() ?? null;
    return symbol.parent ?? null;
  } catch (e) {
    return null;
  }
}

/**
 * The name of a symbol that is a NAMESPACE, or null when it is not one.
 *
 * <p>The chain of symbols above a declaration is walked until it leaves the
 * namespaces: a class's parent is the namespace it is written in, and a
 * namespace's parent is the namespace above it -- and then the module, which
 * is not a namespace.
 *
 * @param {object} symbol the symbol
 * @return the name, or null
 */
function namespaceNameOfSymbol(symbol) {
  const declarations = symbol.declarations ?? [];
  for (const declaration of declarations) {
    let kind;
    try {
      kind = declaration.kind;
    } catch (e) {
      continue;
    }
    if (kind === ts.SyntaxKind.ModuleDeclaration) {
      const name = symbol.name;
      return typeof name === "string" && name.length > 0 ? name : null;
    }
  }
  return null;
}

/**
 * The name of a namespace, or null when it has none.
 *
 * @param {object} node the namespace
 * @return the name
 */
function namespaceNameOf(node) {
  const name = node && node.name;
  if (!name) return null;
  const text = name.text;
  return typeof text === "string" && text.length > 0 ? text : null;
}

/**
 * Whether a namespace is an AMBIENT declaration.
 *
 * <p>`declare namespace X { ... }` and `declare module "x" { ... }` declare
 * a shape and run nothing: their members have no bodies. There is nothing to
 * lower, and nothing lost by not lowering it.
 *
 * @param {object} node the namespace
 * @return whether the namespace is ambient
 */
function isAmbientNamespace(node) {
  const modifiers = node.modifiers ?? [];
  return modifiers.some((modifier) => modifier.kind === ts.SyntaxKind.DeclareKeyword);
}
/**
 * The name of a compiler type's class, when it has one.
 *
 * <p><b>Why the name is read as a PROPERTY.</b> The compiler's symbol carries
 * its name as a property, and the accessor `getName()` is not implemented in
 * every build of the compiler. A symbol without a name is a class without a
 * name, so every instance would degrade to `Object`.
 *
 * <p><b>Why the object-type guard is ASKED FOR, not called.</b> The guard is a
 * safety net rather than a filter -- `mapType` only calls this for a type
 * whose flags already say it is an object -- so it is asked for only when the
 * compiler has it.
 */
function classNameOf(type) {
  if (!type) {
    return undefined;
  }
  try {
    if (typeof type.isObjectType === "function" && !type.isObjectType()) {
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
  if (type.unionMembers && type.unionMembers.length > 1) {
    return type.unionMembers.map((member) => typeText(member)).join("|");
  }
  if (type.nullable === true) {
    return `${type.kind.toLowerCase()}|null`;
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
