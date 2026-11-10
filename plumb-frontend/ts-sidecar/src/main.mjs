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
  }

  /** The module a file belongs to: its path under the source root, without the extension. */
  moduleNameOf(path) {
    const absolute = resolve(path);
    if (!this.sourceRoot) {
      return stripExtension(basename(absolute));
    }
    const rel = relative(resolve(this.sourceRoot), absolute);
    return stripExtension(rel.split(sep).join("/"));
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
  const translator = new Translator({
    checker: owner.checker,
    moduleName: session.moduleNameOf(path),
    language,
    text,
  });
  let result;
  try {
    result = translator.translateSourceFile(sourceFile);
  } catch (e) {
    return failure(OP.TRANSLATE, `the file could not be translated: ${describe(e)}`);
  }
  return {
    ...stamp(),
    op: OP.TRANSLATE,
    ok: true,
    diagnostics: [],
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
