// The smoke test: the sidecar, end to end, over its real wire.
//
// It starts the sidecar as a process, speaks the framing protocol to it, and
// checks the TAC that comes back. The point is to test the PROCESS and the
// PROTOCOL, not the translator's internals: a translator that is right in a
// unit test and wrong on the wire produces a sidecar the Java side cannot
// read, and the Java side's answer to that is "no findings".
//
// Run it with `npm test` from this directory.

import { spawn } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { IR_VERSION, OP, PROTOCOL_VERSION } from "./src/protocol.mjs";

const here = fileURLToPath(new URL(".", import.meta.url));

/** The probe program. */
const SOURCE = `
@Entry
@Component
struct ProfilePage {
  @State message: string = 'hello'
  private secret: string = 'hidden'

  build() {
    Column() {
      Text(this.message)
    }
  }

  render(input: string): string {
    this.message = input;
    console.log(this.message);
    return this.message + "!";
  }
}
`;

/**
 * A second probe program: the calls that reach a MODULE.
 *
 * <p>A script language reaches its libraries through modules rather than
 * through classes, so the owner of a call to a module-level function is the
 * module it was imported from. The sink catalogue is written against that
 * name, so a front end that named the FILE instead would produce TAC that
 * matches nothing -- and a scan that reports a clean program.
 */
const MODULE_SOURCE = `
import { parse } from '@ohos.util.json';
import * as json from '@ohos.util.json';

export function run(text: string): void {
  parse(text);
  json.stringify(text);
}
`;

/** Fail the test with a message. */
function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

/** Assert a condition. */
function check(condition, message) {
  if (!condition) fail(message);
}

/** Write one frame. */
function writeFrame(stream, payload) {
  const bytes = Buffer.from(payload, "utf8");
  const header = Buffer.alloc(4);
  header.writeUInt32BE(bytes.length, 0);
  stream.write(header);
  stream.write(bytes);
}

/** Read one frame from the sidecar's stdout. */
function readFrame(stream) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length < 4) return;
      const length = buffer.readUInt32BE(0);
      if (buffer.length < 4 + length) return;
      stream.off("data", onData);
      resolve(buffer.subarray(4, 4 + length).toString("utf8"));
    };
    stream.on("data", onData);
    stream.on("error", reject);
    stream.on("end", () => reject(new Error("the sidecar closed its output")));
  });
}

async function main() {
  const dir = mkdtempSync(join(tmpdir(), "plumb-arkts-smoke-"));
  const file = join(dir, "Page.ets");
  writeFileSync(file, SOURCE, "utf8");
  const moduleFile = join(dir, "Modules.ets");
  writeFileSync(moduleFile, MODULE_SOURCE, "utf8");

  const child = spawn(process.execPath, [join(here, "src", "main.mjs")], {
    stdio: ["pipe", "pipe", "inherit"],
  });

  try {
    // The handshake: a request without the protocol version is refused.
    writeFrame(child.stdin, JSON.stringify({ op: OP.PING }));
    let response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === false, "a request without a protocol version must be refused");
    check(
      typeof response.error === "string" && response.error.length > 0,
      "a refused request must say why",
    );

    // A ping with the version is answered.
    writeFrame(
      child.stdin,
      JSON.stringify({ op: OP.PING, protocolVersion: PROTOCOL_VERSION, irVersion: IR_VERSION }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, "a ping must be answered");
    check(
      response.protocolVersion === PROTOCOL_VERSION && response.irVersion === IR_VERSION,
      "every response must carry the version fields",
    );

    // The project opens.
    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.OPEN_PROJECT,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        projectId: "smoke",
        sourceRoot: dir,
        sources: [{ path: file }, { path: moduleFile }],
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the project must open: ${response.error ?? ""}`);

    // The file translates.
    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: file,
        language: "ARKTS",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the file must translate: ${response.error ?? ""}`);
    check(
      (response.problems ?? []).length === 0,
      `the file must parse: ${JSON.stringify(response.problems ?? [])}`,
    );

    const functions = response.functions ?? [];
    const build = functions.find((f) => f.name === "build");
    check(!!build, "the struct's build method must be in the stream");
    check(
      build.enclosingClass === "ProfilePage",
      "a struct's method must name the struct it belongs to",
    );
    const render = functions.find((f) => f.name === "render");
    check(!!render, "the struct's other method must be in the stream");
    check(
      functions.some((f) => f.name === "ProfilePage" && f.synthetic),
      "the struct's own initialiser must be in the stream",
    );

    const instructions = render.instructions;

    // A method call names the class of its receiver. When the compiler does
    // not name one -- the receiver is an untyped global, and this compiler
    // does not load the DOM declarations -- the owner falls back to the file
    // the call is written in. That fallback is what a catalogue sees, so it
    // is pinned here rather than left to chance.
    const consoleCall = instructions.find(
      (i) =>
        i.kind === "CALL" && typeof i.calleeSig === "string" && i.calleeSig.includes(".log/"),
    );
    check(!!consoleCall, "the call to the console must be in the stream");
    check(
      consoleCall.calleeSig.startsWith("Page.log/"),
      `a receiver with no class name falls back to the file's module: ${consoleCall.calleeSig}`,
    );


    // A property read is a CONTAINER ACCESS with a string key, not a field
    // access: in this language an object is a namespace, and `this.message`
    // and `this["message"]` are the same location.
    const propertyRead = instructions.find(
      (i) => i.kind === "ARRAYLOAD" && i.lhs2 && i.lhs2.kind === "CONST" && i.lhs2.value === "message",
    );
    check(
      !!propertyRead,
      "a property read must be an ARRAYLOAD whose key is the property name",
    );
    check(
      !instructions.some((i) => i.kind === "FIELDLOAD"),
      "a property read must not be a field access in this language",
    );

    // A property WRITE is a store with the same key, so that a later read
    // finds the value that was written.
    const propertyWrite = instructions.find(
      (i) =>
        i.kind === "ARRAYSTORE" &&
        i.lhs2 &&
        i.lhs2.kind === "CONST" &&
        i.lhs2.value === "message" &&
        i.dest &&
        i.dest.kind === "VAR" &&
        i.dest.name === "input",
    );
    check(
      !!propertyWrite,
      "a property assignment must be an ARRAYSTORE that carries the assigned value",
    );

    // The call keeps the receiver slot: args[0] is the receiver, args[1..]
    // are the arguments.
    const call = instructions.find((i) => i.kind === "CALL" || i.kind === "UNINTERPRETED");
    check(!!call, "the call must be in the stream");
    check(call.args.length >= 1, "a call must carry the receiver slot");
    check(
      call.args[0].kind === "VAR" || call.args[0].kind === "CONST",
      "the receiver slot must hold an operand",
    );

    // The UI DSL is a component call, and its argument is where the taint of
    // a property reaches the component.
    const buildInstructions = build.instructions;
    const componentCall = buildInstructions.find(
      (i) => i.kind === "CALL" && typeof i.calleeSig === "string" && i.calleeSig.includes(".Text/"),
    );
    check(!!componentCall, "the UI DSL must be lowered into a component call");
    check(
      componentCall.args.length === 2,
      "a component call must carry the receiver slot and its argument",
    );
    check(
      buildInstructions.some(
        (i) =>
          i.kind === "ARRAYLOAD" &&
          i.lhs2 &&
          i.lhs2.kind === "CONST" &&
          i.lhs2.value === "message",
      ),
      "the property the component renders must be read",
    );

    // The function's own name is in its signature, so the sink catalogue has
    // something to match.
    check(
      typeof render.signature === "string" && render.signature.includes("render"),
      "the signature must name the function",
    );

    // A call to a module-level function names the MODULE it was imported
    // from. The sink catalogue is written against that name, so a front end
    // that named the file instead would produce TAC that matches nothing --
    // and a scan of a vulnerable program that reports it clean.
    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: moduleFile,
        language: "ARKTS",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the module file must translate: ${response.error ?? ""}`);

    const runFunction = (response.functions ?? []).find((f) => f.name === "run");
    check(!!runFunction, "the function that reaches a module must be in the stream");
    const signatures = (runFunction.instructions ?? [])
      .filter((i) => i.kind === "CALL" || i.kind === "UNINTERPRETED")
      .map((i) => i.calleeSig);
    check(
      signatures.some((s) => s.startsWith("@ohos.util.json.parse/")),
      `an imported function must name the module it came from: ${JSON.stringify(signatures)}`,
    );
    check(
      signatures.some((s) => s.startsWith("@ohos.util.json.stringify/")),
      `a namespace member must name the module it came from: ${JSON.stringify(signatures)}`,
    );
    check(
      !signatures.some((s) => s.startsWith("Modules.")),
      `the file's own name must not stand in for the module: ${JSON.stringify(signatures)}`,
    );

    // A binary operator is a BINOP, not an unknown value. A lookup that
    // always misses does not look like a lookup: every binary expression
    // would become a HAVOC, and the taint that flowed through it would stop
    // there without a word.
    const addition = instructions.find((i) => i.kind === "BINOP");
    check(!!addition, "a binary operator must be lowered into a BINOP");
    check(addition.binop === "ADD", "the concatenation must be the ADD operator");

    console.log("ok: the sidecar answers over the wire, and the TAC has the expected shape");
  } finally {
    child.kill();
  }
}

main().catch((e) => {
  console.error(`FAIL: ${e && e.stack ? e.stack : e}`);
  process.exit(1);
});
