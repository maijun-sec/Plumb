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

import { API } from "typescript/unstable/sync";

import { IR_VERSION, OP, PROTOCOL_VERSION, stamp } from "./protocol.mjs";
import { Translator } from "./translator.mjs";

/** The project the sidecar currently holds. */
let session = null;

/**
 * The compiler session.
 *
 * The compiler is a long-lived native process, and the project is opened
 * once: opening it per file would re-parse the standard library for every
 * file, and the standard library is the bulk of the work.
 */
class Session {
  constructor(api, snapshot, projects, sourceRoot) {
    this.api = api;
    this.snapshot = snapshot;
    this.projects = projects;
    this.sourceRoot = sourceRoot;
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

  /**
   * The source file of a path, and the project that holds it.
   *
   * A tree can hold SEVERAL projects -- one per tsconfig -- and the file
   * being translated belongs to exactly one of them. Asking only the first
   * project would leave every file of the other projects untranslatable, and
   * an untranslatable file is a missing finding rather than a missing
   * precision.
   */
  sourceFileOf(path) {
    const candidates = [path, path.replace(/\\/g, "/")];
    for (const project of this.projects) {
      for (const candidate of candidates) {
        try {
          const file = project.program.getSourceFile(candidate);
          if (file) return { sourceFile: file, project };
        } catch (e) {
          // A project that cannot answer simply does not hold the file.
        }
      }
    }
    return null;
  }

  /**
   * The compiler's tree of a REPAIRED version of a file.
   *
   * <p>The compiler truncates the body of a class whose body holds a nested
   * class, and the repair takes the nested class out of the text the compiler
   * reads. The repaired text has to reach the compiler AT THE FILE'S OWN
   * PATH: at any other path the relative imports would resolve elsewhere and
   * the file would lose its module name.
   *
   * <p>So the compiler gets a virtual file system that overrides exactly one
   * file and falls back to the real disk for everything else. The fallback
   * matters: the standard library and the imported modules are read from the
   * disk, and only this one file comes from the repair.
   *
   * <p>The compiler is a separate process, and it is asked for this tree once
   * -- a file whose class body was truncated is rare, and the price of the
   * repair is paid only by the files that need it.
   *
   * @param {string} path the file's path
   * @param {string} text the repaired text
   * @return {{sourceFile: object, checker: object, dispose: function}|null} the
   *     tree, or null when the compiler could not be asked
   */
  compilePatched(path, text) {
    const overrides = new Map();
    overrides.set(virtualKey(path), text);
    const fs = {
      readFile: (name) => (overrides.has(name) ? overrides.get(name) : undefined),
      fileExists: (name) => (overrides.has(name) ? true : undefined),
      directoryExists: () => undefined,
      getAccessibleEntries: () => undefined,
      realpath: () => undefined,
    };
    let api = null;
    try {
      api = new API({ fs });
      const snapshot = api.updateSnapshot({ openFiles: [path] });
      for (const project of snapshot.getProjects()) {
        const sourceFile = this.sourceFileIn(project, path);
        if (!sourceFile) {
          console.error(
            `plumb-ts-sidecar: the repaired tree holds no source file for ${path}`,
          );
          continue;
        }
        return {
          sourceFile,
          checker: project.checker,
          dispose: () => {
            try {
              api.close();
            } catch (e) {
              // A compiler that will not close is not a reason to fail.
            }
          },
        };
      }
      console.error(`plumb-ts-sidecar: the repaired tree holds no project for ${path}`);
      return null;
    } catch (e) {
      // A compiler that cannot be asked is reported, not swallowed: the
      // repair is an improvement, and a reader has to be able to see that it
      // did not happen.
      console.error(`plumb-ts-sidecar: the repaired tree could not be built: ${describe(e)}`);
      if (api) {
        try {
          api.close();
        } catch (ignored) {
          // Nothing to do: the compiler is already gone.
        }
      }
      return null;
    }
  }

  /** The source file of a path inside one project, or null. */
  sourceFileIn(project, path) {
    for (const candidate of [path, path.replace(/\\/g, "/")]) {
      try {
        const file = project.program.getSourceFile(candidate);
        if (file) return file;
      } catch (e) {
        // A project that cannot answer simply does not hold the file.
      }
    }
    return null;
  }

  close() {
    try {
      this.api.close();
    } catch (e) {
      // Closing is best effort: the process is going away either way.
    }
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

/**
 * The form of a path the compiler asks the virtual file system for.
 *
 * <p>The compiler asks for the file with forward slashes and a lower-case
 * drive letter, and the answer has to be found under that exact name: a
 * virtual file system that does not know the path falls back to the real disk,
 * and the repair would then be read as the original file.
 */
function virtualKey(path) {
  return path.replace(/\\/g, "/").replace(/^([A-Z]):/, (m, drive) => `${drive.toLowerCase()}:`);
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
      if (session) {
        session.close();
        session = null;
      }
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
    if (session) {
      session.close();
      session = null;
    }
    const api = new API({});
    const snapshot = api.updateSnapshot({ openFiles: paths });
    const projects = snapshot.getProjects();
    if (projects.length === 0) {
      api.close();
      return failure(OP.OPEN_PROJECT, "the compiler opened no project for the given files");
    }
    session = new Session(api, snapshot, projects, request.sourceRoot);
    // The project's files, in the spelling the project uses. The compiler
    // hands out lower-cased paths, and the module path has to be one spelling.
    session.sourcePaths = new Map(paths.map((p) => [resolve(p).toLowerCase(), p]));
    return {
      ...stamp(),
      op: OP.OPEN_PROJECT,
      ok: true,
      projectId: request.projectId,
      languages: request.languages ?? [],
    };
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
  let owner;
  try {
    const found = session.sourceFileOf(path);
    if (found) {
      sourceFile = found.sourceFile;
      owner = found.project;
    }
  } catch (e) {
    return failure(OP.TRANSLATE, `the file could not be read: ${describe(e)}`);
  }
  if (!sourceFile) {
    return failure(OP.TRANSLATE, `the compiler has no source file for ${path}`);
  }
  let text;
  try {
    text = readFileSync(path, "utf8");
  } catch (e) {
    text = "";
  }
  const language = request.language ?? "TYPESCRIPT";
  // The file being translated is one of the project's own files, so it has a
  // module: the scan asks for the file it is parsing.
  const moduleName = session.moduleNameOf(path);
  const makeTranslator = (sourceText, checker) =>
    new Translator({
      checker: checker ?? owner.checker,
      moduleName,
      moduleNameOf: (filePath) => session.moduleNameOf(filePath),
      isProjectFile: (filePath) => session.isProjectFile(filePath),
      language,
      text: sourceText,
    });
  let result = null;
  let patched = null;
  try {
    // The compiler truncates the body of a class whose body holds a nested
    // class, and the members that came after it come back as fragments of
    // the FILE. The repair takes the nested class out of the text the
    // compiler reads, so that the compiler builds the outer class whole.
    const plan = makeTranslator(text).planClassBodyRepair(sourceFile);
    if (plan) {
      patched = session.compilePatched(path, plan.patchedText);
      if (patched) {
        try {
          result = makeTranslator(plan.patchedText, patched.checker).translateRepaired(
            patched.sourceFile,
            plan,
          );
        } catch (e) {
          // The repair is an improvement, not a requirement: a failure in it
          // must not cost the file its translation.
          console.error(`plumb-ts-sidecar: the repaired tree could not be lowered: ${describe(e)}`);
          result = null;
        }
      }
    }
  } catch (e) {
    // The repair is optional; the original path below still runs. The failure
    // is reported, because a repair that silently does not happen is a loss
    // that looks like a finding that is not there.
    console.error(`plumb-ts-sidecar: the class body could not be repaired: ${describe(e)}`);
    result = null;
  }
  try {
    if (!result) {
      result = makeTranslator(text).translateSourceFile(sourceFile);
    }
  } catch (e) {
    return failure(OP.TRANSLATE, `the file could not be translated: ${describe(e)}`);
  } finally {
    if (patched) patched.dispose();
  }
  return {
    ...stamp(),
    op: OP.TRANSLATE,
    ok: true,
    capabilities: ["drops", "guards", "tryRegions", "annotations", "domains", "labels", "types"],
    problems: [],
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
  if (e instanceof Error) return e.message;
  return String(e);
}

// ---- the framing ---------------------------------------------------------------------------

/**
 * The bytes that have arrived but do not yet make a whole frame.
 *
 * A stream hands over whatever bytes happen to be available, so the framing
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
    if (pending.length < 4 + length) return;
    const payload = pending.subarray(4, 4 + length).toString("utf8");
    pending = pending.subarray(4 + length);
    const response = respond(payload);
    if (response === null) {
      if (session) session.close();
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
  if (session) session.close();
  process.exit(0);
});

process.stdin.on("error", (e) => {
  process.stderr.write(`plumb-ts-sidecar: could not read from stdin: ${describe(e)}\n`);
  process.exit(1);
});

drain();
