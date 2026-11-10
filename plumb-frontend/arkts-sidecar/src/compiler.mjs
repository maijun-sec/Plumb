// The compiler binding: the ArkTS compiler's own front end.
//
// WHY THE COMPILER IS USED RATHER THAN REIMPLEMENTED. ArkTS is read by a
// fork of the TypeScript compiler that knows the ArkTS language: it parses
// `struct`, it knows the ArkUI decorators, and it parses the declarative UI
// DSL. A second implementation of that grammar in the JVM would be a second
// implementation that disagrees with the real one -- and a disagreement here
// is not a cosmetic difference: the UI DSL is only recognised inside a build
// method whose name the compiler looks up in `ets.render.method`, and a
// component is only a component when its name is in `ets.components`. Get
// either wrong and the file does not parse at all.
//
// So the compiler does the parsing, the binding and the type checking, and
// this module is the thin layer that configures it.
//
// THE THREE CONFIGURATIONS THAT MATTER.
//
//   1. `ets.render.method` must name the render method ("build"). The
//      compiler only parses the declarative UI DSL inside that method, and
//      without it `Column() { ... }` is a call followed by a block -- a
//      syntax error.
//
//   2. `ets.components` must name every component the project uses. A name
//      that is not on the list is an ordinary call, so `Text(x)` inside a
//      build method is a call expression rather than a component expression.
//
//   3. The SDK's `paths` mapping must be loaded, or an `import` of an `@ohos.*`
//      module resolves to nothing -- and an unresolved import means an
//      unknown type, which means a missing finding rather than a missing
//      precision.

import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import * as ts from "ohos-typescript";

/** The directory this file lives in. */
const here = dirname(fileURLToPath(import.meta.url));

/** The sidecar's own directory. */
const sidecarRoot = resolve(here, "..");

/**
 * The ArkTS property decorators, and whether the property has to be
 * initialised where it is declared.
 *
 * <p>A property whose decorator is on this list with `needInitialization`
 * false is exempt from the compiler's "used before its initialisation"
 * check: its value comes from elsewhere -- the parent component, or the
 * framework -- so the compiler must not insist that it was assigned here.
 */
const PROPERTY_DECORATORS = [
  { name: "State", needInitialization: true },
  { name: "Prop", needInitialization: true },
  { name: "Param", needInitialization: true },
  { name: "Link", needInitialization: false },
  { name: "Provide", needInitialization: false },
  { name: "Consume", needInitialization: false },
  { name: "Observed", needInitialization: false },
  { name: "ObjectLink", needInitialization: false },
  { name: "Require", needInitialization: false },
  { name: "Once", needInitialization: false },
  { name: "Event", needInitialization: false },
];

/**
 * The names that are built into the language and are not components.
 *
 * <p>They are excluded from the harvested component names so that an ordinary
 * call such as `String(value)` stays an ordinary call.
 */
const STANDARD_GLOBALS = new Set([
  "Array",
  "ArrayBuffer",
  "BigInt",
  "Boolean",
  "DataView",
  "Date",
  "Error",
  "EvalError",
  "FinalizationRegistry",
  "Function",
  "Intl",
  "Map",
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
  "WeakMap",
  "WeakRef",
  "WeakSet",
]);

/**
 * The names of the ArkUI components the SDK declares.
 *
 * <p>Read from a file rather than hard-coded, so the list can be refreshed
 * from the SDK's own declarations without touching the code.
 */
let cachedComponents = null;

/**
 * The ArkUI component names this sidecar knows.
 *
 * @returns {string[]} the names, possibly empty
 */
export function sdkComponentNames() {
  if (cachedComponents) {
    return cachedComponents;
  }
  try {
    const parsed = JSON.parse(readFileSync(resolve(here, "components.json"), "utf8"));
    cachedComponents = Array.isArray(parsed)
      ? parsed.filter((name) => typeof name === "string")
      : [];
  } catch (e) {
    // The list is a convenience, not a requirement: without it the SDK's own
    // components are not recognised, and the project says so in the report.
    cachedComponents = [];
  }
  return cachedComponents;
}

/**
 * The ArkTS SDK's declaration files, when the SDK package is installed.
 *
 * <p>The SDK ships the mapping from a module name to the file that declares
 * it, in its own `tsconfig.base.json`. This reads that file rather than
 * copying the mapping, so the mapping stays the SDK's.
 *
 * @returns {object|null} the SDK, or null when it is not installed
 */
export function sdkDeclarations() {
  const root = resolve(sidecarRoot, "node_modules", "@arkts", "declarations", "dist");
  if (!existsSync(root)) {
    return null;
  }
  const paths = {};
  const base = resolve(root, "tsconfig.base.json");
  if (existsSync(base)) {
    try {
      const parsed = JSON.parse(readFileSync(base, "utf8"));
      const declared = parsed.compilerOptions && parsed.compilerOptions.paths;
      for (const [pattern, targets] of Object.entries(declared ?? {})) {
        if (!Array.isArray(targets)) {
          continue;
        }
        paths[pattern] = targets.map((target) => resolve(root, target));
      }
    } catch (e) {
      // A mapping that cannot be read is simply no mapping. The frontend
      // reports that the SDK was not usable, rather than pretending it was.
    }
  }
  return {
    root,
    paths,
    globalDeclaration: resolve(root, "global.d.ts"),
    componentDeclaration: resolve(root, "component-all.d.ts"),
  };
}

/**
 * The compiler options for an ArkTS program.
 *
 * @param {object|null} sdk the SDK's declarations, or null
 * @param {string[]} components the component names the compiler should accept
 * @returns {object} the compiler options
 */
export function compilerOptions(sdk, components) {
  const options = {
    target: ts.ScriptTarget.ES2021,
    module: ts.ModuleKind.ES2020,
    moduleResolution: ts.ModuleResolutionKind.NodeJs,
    strict: false,
    noEmit: true,
    allowJs: true,
    experimentalDecorators: true,
    skipLibCheck: true,
    allowArbitraryExtensions: true,
    ets: {
      // The render method is where the declarative UI DSL lives.
      render: { method: ["build"], decorator: ["Builder", "LocalBuilder"] },
      components,
      libs: [],
      extend: { decorator: [], components: [] },
      propertyDecorators: PROPERTY_DECORATORS,
    },
  };
  if (sdk) {
    options.baseUrl = sdk.root;
    options.paths = sdk.paths;
  }
  return options;
}

/**
 * The component names a project's own sources use.
 *
 * <p>The SDK's declarations do not name every component the platform offers,
 * and they cannot name the components a project defines for itself. So the
 * project's own sources are scanned for the shape a component call has: a
 * name that starts with a capital, immediately followed by an argument list.
 *
 * <p>The scan over-approximates on purpose. A name that is really an
 * ordinary function becomes a component expression, and the only thing that
 * changes is the arity the callee signature carries -- the call is still a
 * call, and the taint still flows into its arguments.
 *
 * @param {string[]} texts the project's source texts
 * @returns {string[]} the names
 */
export function harvestedComponentNames(texts) {
  const names = new Set();
  const pattern = /(^|[^\w$.])([A-Z][A-Za-z0-9_$]*)\s*\(/g;
  for (const text of texts) {
    if (!text) {
      continue;
    }
    pattern.lastIndex = 0;
    let match;
    while ((match = pattern.exec(text)) !== null) {
      const name = match[2];
      if (!STANDARD_GLOBALS.has(name)) {
        names.add(name);
      }
    }
  }
  return [...names];
}

/**
 * Open a program over the project's files.
 *
 * <p>The component list is completed with the names the project's own sources
 * use. A component is used in the DSL by its own name, and the compiler only
 * recognises that name as a component when it is on the list -- so the list is
 * the SDK's names plus the project's.
 *
 * <p>That needs two passes: the file that DEFINES a struct parses without
 * the name being on the list, and the file that USES it does not. So the
 * program is built once to learn the names, and again with the names added.
 *
 * @param {object} request the request
 * @param {string[]} request.rootNames the project's source files
 * @param {object|null} request.sdk the SDK's declarations, or null
 * @returns {object} the program, the checker and the names that were added
 */
export function openProgram(request) {
  const sdk = request.sdk;
  const rootNames = [...request.rootNames];
  if (sdk) {
    for (const declaration of [sdk.globalDeclaration, sdk.componentDeclaration]) {
      if (declaration && existsSync(declaration)) {
        rootNames.push(declaration);
      }
    }
  }

  const components = new Set(sdkComponentNames());
  for (const name of harvestedComponentNames(readTextsOf(request.rootNames))) {
    components.add(name);
  }
  let program = ts.createProgram(rootNames, compilerOptions(sdk, [...components]));
  const declared = structNamesOf(program);
  let added = [];
  for (const name of declared) {
    if (!components.has(name)) {
      components.add(name);
      added.push(name);
    }
  }
  if (added.length > 0) {
    // The project's own components are now known, so the files that USE them
    // parse as components rather than as calls.
    program = ts.createProgram(rootNames, compilerOptions(sdk, [...components]));
  }
  return {
    program,
    checker: program.getTypeChecker(),
    components: [...components],
    addedComponents: added,
  };
}

/** The texts of the project's files, as far as they can be read. */
function readTextsOf(paths) {
  const texts = [];
  for (const path of paths) {
    try {
      texts.push(readFileSync(path, "utf8"));
    } catch (e) {
      // A file that cannot be read is the compiler's problem too: it will
      // report it as a missing root file.
    }
  }
  return texts;
}

/**
 * The names of the structs a program declares.
 *
 * @param {object} program the compiler's program
 * @returns {string[]} the names, without duplicates
 */
function structNamesOf(program) {
  const names = new Set();
  for (const sourceFile of program.getSourceFiles()) {
    if (sourceFile.isDeclarationFile) {
      continue;
    }
    const visit = (node) => {
      if (ts.isStructDeclaration(node) && node.name) {
        names.add(node.name.text);
      }
      ts.forEachChild(node, visit);
    };
    ts.forEachChild(sourceFile, visit);
  }
  return [...names];
}

/**
 * The syntax errors of one file.
 *
 * <p>Only the SYNTACTIC diagnostics are reported. A semantic diagnostic says
 * the program's types do not line up; that is the user's problem and not the
 * analysis's, and reporting it would bury the one thing that matters here --
 * that the file did not parse, so what came back is not the whole program.
 *
 * @param {object} program the compiler's program
 * @param {object} sourceFile the file
 * @returns {string[]} the messages
 */
export function syntaxErrorsOf(program, sourceFile) {
  const messages = [];
  for (const diagnostic of program.getSyntacticDiagnostics(sourceFile)) {
    messages.push(ts.flattenDiagnosticMessageText(diagnostic.messageText, " "));
  }
  return messages;
}

/**
 * The source file of a path, and the program that holds it.
 *
 * <p>The compiler normalises the paths it holds, and on Windows that means a
 * path with forward slashes. Both spellings are therefore tried.
 *
 * @param {object} program the compiler's program
 * @param {string} path the file's path
 * @returns {object|null} the source file, or null
 */
export function sourceFileOf(program, path) {
  const candidates = [path, path.replace(/\\/g, "/")];
  for (const candidate of candidates) {
    try {
      const file = program.getSourceFile(candidate);
      if (file) {
        return file;
      }
    } catch (e) {
      // A program that cannot answer simply does not hold the file.
    }
  }
  return null;
}
