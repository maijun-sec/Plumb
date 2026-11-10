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

import phpParser from "php-parser";

import { IR_VERSION, OP, PROTOCOL_VERSION, stamp } from "./protocol.mjs";
import { Translator } from "./translator.mjs";

const { parseCode } = phpParser;

/** The project the sidecar currently holds. */
let session = null;

/**
 * The parser's own settings.
 *
 * `withPositions` is the one that matters: without it the AST carries no
 * line and no column, and a finding without a position is a finding nobody
 * can go and look at.
 */
const PARSER_OPTIONS = {
  parser: {
    extractDoc: false,
    suppressErrors: false,
    version: "8.3",
  },
  ast: {
    withPositions: true,
  },
};

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
 * The module a file belongs to: its path under the source root, with the
 * platform's separator turned into PHP's.
 */
function moduleNameOf(path, sourceRoot) {
  const absolute = resolve(path);
  if (!sourceRoot) {
    return stripExtension(basename(absolute)).split(sep).join("\\");
  }
  const rel = relative(resolve(sourceRoot), absolute);
  return stripExtension(rel.split(sep).join("/")).split("/").join("\\");
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

/** Open the project: remember where its files live. */
function openProject(request) {
  const sources = Array.isArray(request.sources) ? request.sources : [];
  const paths = sources
    .map((s) => (typeof s === "string" ? s : s && s.path))
    .filter((p) => typeof p === "string" && p.length > 0);
  if (paths.length === 0) {
    return failure(OP.OPEN_PROJECT, "the project has no source files");
  }
  session = { sourceRoot: request.sourceRoot ?? null, files: paths };
  return { ...stamp(), op: OP.OPEN_PROJECT, ok: true, projectId: request.projectId };
}

/** Translate one file of the open project. */
function translate(request) {
  const path = request.path;
  if (typeof path !== "string" || path.length === 0) {
    return failure(OP.TRANSLATE, "the request names no file");
  }
  let text;
  try {
    text = readFileSync(path, "utf8");
  } catch (e) {
    return failure(OP.TRANSLATE, `the file could not be read: ${describe(e)}`);
  }

  let program;
  try {
    program = parseCode(text, PARSER_OPTIONS);
  } catch (e) {
    // A file that does not parse is a file that is not PHP. Saying so is
    // the difference between a known lower bound and a silent surprise.
    return failure(OP.TRANSLATE, `the file is not valid PHP: ${describe(e)}`);
  }

  const sourceRoot = session ? session.sourceRoot : null;
  const translator = new Translator({
    moduleName: moduleNameOf(path, sourceRoot),
    text,
  });
  let result;
  try {
    result = translator.translateProgram(program);
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
  process.stderr.write(`plumb-php-sidecar: could not read from stdin: ${describe(e)}\n`);
  process.exit(1);
});

drain();
