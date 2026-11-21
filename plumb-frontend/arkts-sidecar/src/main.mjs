// The sidecar's entry point: a request/response loop over stdio.
//
// The process reads one frame at a time, dispatches it, and writes one frame
// back. It exits when the pipe closes or when it is asked to shut down.
//
// EVERY FAILURE IS REPORTED, NEVER SWALLOWED. A sidecar that dies silently
// would leave the Java side believing the project had no findings. So an
// error becomes an `ok: false` response, and the process stays alive to
// answer the next request.
//
// The frames are length-prefixed JSON: a 4-byte big-endian length, then the
// UTF-8 payload. A source file may contain any byte in a string literal,
// including a newline, so a line-oriented protocol would have to escape or
// guess; a length prefix has no such ambiguity.

import { readFileSync } from "node:fs";
import { relative, resolve, sep } from "node:path";

import { IR_VERSION, OP, PROBLEM, PROTOCOL_VERSION, stamp } from "./protocol.mjs";
import {
  openProgram,
  programWithPatchedFile,
  sdkDeclarations,
  sourceFileOf,
  syntaxErrorsOf,
} from "./compiler.mjs";
import { Translator } from "./translator.mjs";

/** The project the sidecar currently holds. */
let session = null;

/**
 * The compiler session.
 *
 * <p>The project is opened once: the compiler reads and binds every file of
 * it, and doing that per file would make a scan quadratic in the size of the
 * project.
 */
class Session {
  constructor(program, checker, sourceRoot, components, addedComponents, rootNames, sdk) {
    this.program = program;
    this.checker = checker;
    this.sourceRoot = sourceRoot;
    this.components = components;
    this.addedComponents = addedComponents;
    this.rootNames = rootNames;
    this.sdk = sdk;
    /**
     * The project's files, keyed by their lower-cased absolute path.
     *
     * <p>The compiler hands out lower-cased paths, while the request that
     * opened the project spells them the way the project does. The module path
     * has to be ONE spelling: a declaration and the call that reaches it must
     * land in the same key space, and "modA/store" and "moda/store" are two
     * different ones.
     */
    this.sourcePaths = new Map();
  }

  /**
   * The module a file belongs to: its path under the source root, without the
   * extension.
   *
   * @param {string} path the file
   * @return {string} the module
   */
  moduleNameOf(path) {
    const absolute = resolve(path);
    if (!this.sourceRoot) {
      return stripExtension(basename(absolute));
    }
    // The path the PROJECT spells, when the compiler handed out a lower-cased
    // one. See the sourcePaths map.
    const original = this.sourcePaths.get(absolute.toLowerCase());
    const rel = relative(resolve(this.sourceRoot), resolve(original ?? absolute));
    return stripExtension(rel.split(sep).join("/"));
  }

  /**
   * Whether a file is one of the analysed project's OWN files.
   *
   * <p>The project's own files are the ones the scan asked for, and that set is
   * what tells a class of THIS project from a class of a LIBRARY: the file of a
   * library is never in it. The source root cannot tell them apart -- a
   * dependency often sits INSIDE the project, under `node_modules` -- and the
   * path of a file the compiler hands out is not a module name either: on
   * another drive the relative path is the absolute one, so the owner of a
   * call to the standard library became the whole path of the file it is
   * installed in.
   *
   * <p>A library class is named by its OWN name: the sink catalogue is written
   * against it, and a module prefix in front of it stops the catalogue from
   * claiming the call.
   *
   * @param {string} path the file
   * @return {boolean} true when the file is one of the project's own
   */
  isProjectFile(path) {
    return this.sourcePaths.has(resolve(path).toLowerCase());
  }
}

/** The basename of a path, with the platform's separator handled. */
function basename(path) {
  const parts = path.split(/[\\/]/);
  return parts[parts.length - 1];
}

/** A path without its extension. */
function stripExtension(path) {
  const dot = path.lastIndexOf(".");
  return dot <= 0 ? path : path.slice(0, dot);
}

/** Handle one request. */
function handle(request) {
  if (!request || typeof request !== "object") {
    return failure(OP.PING, "the request was not a JSON object");
  }
  const op = request.op;
  if (request.protocolVersion !== PROTOCOL_VERSION) {
    return failure(
      op ?? OP.PING,
      `this sidecar speaks protocol version ${PROTOCOL_VERSION}, and the request said ` +
        `${request.protocolVersion === undefined ? "nothing" : request.protocolVersion}`,
    );
  }
  if (request.irVersion !== undefined && request.irVersion !== IR_VERSION) {
    return failure(
      op ?? OP.PING,
      `this sidecar was built against IR version ${IR_VERSION}, and the request said ` +
        `${request.irVersion}`,
    );
  }
  switch (op) {
    case OP.PING:
      return { ...stamp(), op, ok: true };
    case OP.SHUTDOWN:
      return null;
    case OP.OPEN_PROJECT:
      return openProject(request);
    case OP.TRANSLATE:
      return translate(request);
    case OP.CLOSE_PROJECT:
      session = null;
      return { ...stamp(), op, ok: true };
    default:
      return failure(op, `unknown operation: ${String(op)}`);
  }
}

/** Open the project: start the compiler and load the files. */
function openProject(request) {
  const sources = Array.isArray(request.sources) ? request.sources : [];
  const paths = sources
    .map((s) => (typeof s === "string" ? s : s && s.path))
    .filter((p) => typeof p === "string" && p.length > 0);
  if (paths.length === 0) {
    return failure(OP.OPEN_PROJECT, "the project has no source files");
  }
  try {
    const sdk = sdkDeclarations();
    const opened = openProgram({ rootNames: paths, sdk });
    session = new Session(
      opened.program,
      opened.checker,
      request.sourceRoot,
      opened.components,
      opened.addedComponents,
      opened.rootNames,
      opened.sdk,
    );
    // The project's files, in the spelling the project uses. The compiler
    // hands out lower-cased paths, and the module path has to be one spelling.
    session.sourcePaths = new Map(paths.map((p) => [resolve(p).toLowerCase(), p]));
    const response = {
      ...stamp(),
      op: OP.OPEN_PROJECT,
      ok: true,
      projectId: request.projectId,
      languages: request.languages ?? [],
      components: opened.components.length,
      sdk: sdk ? "available" : "absent",
    };
    // The project's OWN components are worth saying out loud: they are the
    // names the compiler had to be told about, and a reader who wonders why
    // a component is recognised can find the answer here.
    if (opened.addedComponents.length > 0) {
      response.addedComponents = opened.addedComponents;
    }
    return response;
  } catch (e) {
    return failure(OP.OPEN_PROJECT, `the compiler could not be started: ${describe(e)}`);
  }
}

/** Translate one file of the open project. */
function translate(request) {
  if (!session) {
    return failure(OP.TRANSLATE, "no project is open");
  }
  const path = request.path;
  if (typeof path !== "string" || path.length === 0) {
    return failure(OP.TRANSLATE, "the request names no file");
  }
  let sourceFile;
  try {
    sourceFile = sourceFileOf(session.program, path);
  } catch (e) {
    return failure(OP.TRANSLATE, `the file could not be read: ${describe(e)}`);
  }
  if (!sourceFile) {
    return failure(OP.TRANSLATE, `the compiler has no source file for ${path}`);
  }

  // A file that did not parse is a PARTIAL file: the compiler still produced
  // an AST for it, but the AST is not the whole program. So the file is
  // lowered, and the parse errors are reported -- never swallowed.
  const problems = [];
  for (const message of syntaxErrorsOf(session.program, sourceFile)) {
    problems.push({
      kind: PROBLEM.PARSE,
      message: `the file did not parse: ${message}`,
      nodeClass: "SourceFile",
      line: -1,
      column: -1,
    });
  }

  let text;
  try {
    text = readFileSync(path, "utf8");
  } catch (e) {
    text = "";
  }
  const language = request.language ?? "ARKTS";
  // The file being translated is one of the project's own files, so it has a
  // module: the scan asks for the file it is parsing.
  const moduleName = session.moduleNameOf(path);
  const makeTranslator = (sourceText, checker, sourceFileNode) =>
    new Translator({
      checker: checker ?? session.checker,
      sourceFile: sourceFileNode,
      moduleName,
      moduleNameOf: (filePath) => session.moduleNameOf(filePath),
      isProjectFile: (filePath) => session.isProjectFile(filePath),
      language,
      text: sourceText,
    });
  let result = null;
  try {
    // The compiler truncates the body of a class whose body holds a nested
    // class, and the members that came after it come back as fragments of
    // the FILE. The repair takes the nested class out of the text the
    // compiler reads, so that the compiler builds the outer class whole.
    const plan = makeTranslator(text, null, sourceFile).planClassBodyRepair(sourceFile);
    if (plan) {
      const patched = programWithPatchedFile(session, path, plan.patchedText);
      if (patched) {
        const patchedSourceFile = sourceFileOf(patched.program, path);
        if (patchedSourceFile) {
          try {
            result = makeTranslator(
              plan.patchedText,
              patched.checker,
              patchedSourceFile,
            ).translateRepaired(patchedSourceFile, plan);
          } catch (e) {
            // The repair is an improvement, not a requirement: a failure in it
            // must not cost the file its translation.
            console.error(
              `plumb-arkts-sidecar: the repaired tree could not be lowered: ${describe(e)}`,
            );
            result = null;
          }
        } else {
          // The program was built but does not hold the file: the repair did
          // not happen, and that has to be said.
          console.error(
            `plumb-arkts-sidecar: the repaired program holds no source file for ${path}`,
          );
        }
      } else {
        console.error(
          `plumb-arkts-sidecar: the repaired program could not be built for ${path}`,
        );
      }
    }
  } catch (e) {
    // The repair is optional; the original path below still runs. The failure
    // is reported, because a repair that silently does not happen is a loss
    // that looks like a finding that is not there.
    console.error(`plumb-arkts-sidecar: the class body could not be repaired: ${describe(e)}`);
    result = null;
  }
  try {
    if (!result) {
      result = makeTranslator(text, null, sourceFile).translateSourceFile();
    }
  } catch (e) {
    return failure(OP.TRANSLATE, `the file could not be translated: ${describe(e)}`);
  }
  return {
    ...stamp(),
    op: OP.TRANSLATE,
    ok: true,
    capabilities: ["drops", "guards", "tryRegions", "annotations", "domains", "labels", "types"],
    problems,
    programDrops: [],
    types: result.types ?? [],
    functions: result.functions,
  };
}

/** A failure response. */
function failure(op, message) {
  return { ...stamp(), op, ok: false, error: message };
}

/** A message that says what went wrong, without the stack. */
function describe(e) {
  if (e instanceof Error) {
    return e.message;
  }
  return String(e);
}

// ---- the framing ---------------------------------------------------------------------------

/**
 * The bytes that have arrived but do not yet make a whole frame.
 *
 * <p>A stream hands over whatever bytes happen to be available, so the framing
 * is done here rather than at the stream: a frame is complete when the buffer
 * holds its four-byte length AND that many payload bytes.
 */
let pending = Buffer.alloc(0);

/** Write one frame to stdout. */
function writeFrame(payload) {
  const bytes = Buffer.from(payload, "utf8");
  const header = Buffer.alloc(4);
  header.writeUInt32BE(bytes.length, 0);
  process.stdout.write(header);
  process.stdout.write(bytes);
}

/** Answer one request frame. */
function respond(payload) {
  let request;
  try {
    request = JSON.parse(payload);
  } catch (e) {
    return failure(OP.PING, `the request was not JSON: ${describe(e)}`);
  }
  try {
    return handle(request);
  } catch (e) {
    return failure(request && request.op, `the operation failed: ${describe(e)}`);
  }
}

/** Answer every whole frame in the buffer. */
function drain() {
  while (pending.length >= 4) {
    const length = pending.readUInt32BE(0);
    if (pending.length < 4 + length) {
      return;
    }
    const payload = pending.subarray(4, 4 + length).toString("utf8");
    pending = pending.subarray(4 + length);
    const response = respond(payload);
    if (response === null) {
      process.exit(0);
    }
    writeFrame(JSON.stringify(response));
  }
}

process.stdin.on("data", (chunk) => {
  pending = Buffer.concat([pending, chunk]);
  drain();
});

process.stdin.on("end", () => {
  process.exit(0);
});

process.stdin.on("error", (e) => {
  process.stderr.write(`plumb-arkts-sidecar: could not read from stdin: ${describe(e)}\n`);
  process.exit(1);
});

drain();
