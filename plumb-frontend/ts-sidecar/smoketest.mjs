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
export class UserStore {
  find(id: string): string {
    return id;
  }
}

export function handle(req: { query: { userId: string } }): string {
  const store = new UserStore();
  const id = req.query.userId;
  const out = store.find(id);
  const box = { label: out };
  box.label = id + "!");
  return box.label;
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
import { exec, execFile as spawner } from "child_process";
import * as vm from "vm";

export function run(cmd: string): void {
  exec(cmd);
  spawner(cmd);
  vm.runInNewContext(cmd);
  eval(cmd);
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
  const dir = mkdtempSync(join(tmpdir(), "plumb-ts-smoke-"));
  const file = join(dir, "app.ts");
  writeFileSync(file, SOURCE, "utf8");
  const moduleFile = join(dir, "modules.ts");
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
        language: "TYPESCRIPT",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the file must translate: ${response.error ?? ""}`);

    const functions = response.functions ?? [];
    const handleFunction = functions.find((f) => f.name === "handle");
    check(!!handleFunction, "the exported function must be in the stream");
    const find = functions.find((f) => f.name === "find");
    check(!!find, "the class method must be in the stream");
    check(
      find.enclosingClass === "UserStore",
      "a method must name the class it belongs to",
    );

    const instructions = handleFunction.instructions;

    // A property read is a CONTAINER ACCESS with a string key, not a field
    // access: in this language an object is a namespace, and `obj.userId` and
    // `obj["userId"]` are the same location.
    const propertyRead = instructions.find(
      (i) => i.kind === "ARRAYLOAD" && i.lhs2 && i.lhs2.kind === "CONST" && i.lhs2.value === "userId",
    );
    check(
      !!propertyRead,
      "a property read must be an ARRAYLOAD whose key is the property name",
    );
    check(
      !instructions.some((i) => i.kind === "FIELDLOAD"),
      "a property read must not be a field access in this language",
    );

    // A call keeps the receiver slot: args[0] is the receiver, args[1..] are
    // the arguments. A call that put the tainted value at args[0] would look
    // right and match nothing.
    const call = instructions.find((i) => i.kind === "CALL" || i.kind === "UNINTERPRETED");
    check(!!call, "the call must be in the stream");
    check(
      call.calleeSig.startsWith("UserStore.find/"),
      `a method call must name the class of its receiver: ${call.calleeSig}`,
    );
    check(call.args.length >= 1, "a call must carry the receiver slot");
    check(
      call.args[0].kind === "VAR" || call.args[0].kind === "CONST",
      "the receiver slot must hold an operand",
    );

    // The construction is a NEW.
    const construction = instructions.find((i) => i.kind === "NEW");
    check(!!construction, "the construction must be a NEW instruction");

    // ... and it NAMES the class it constructs. The engine looks a construction
    // up in the same catalogue as a call, so a construction that cannot be
    // named cannot be matched by any rule -- and the symptom of that is a
    // program that scans clean.
    check(
      construction.calleeSig === "UserStore.<init>/0",
      `a construction must name the class it constructs: ${construction.calleeSig}`,
    );

    // A binary operator is a BINOP, not an unknown value. A lookup that
    // always misses does not look like a lookup: every binary expression
    // would become a HAVOC, and the taint that flowed through it would stop
    // there without a word.
    const addition = instructions.find((i) => i.kind === "BINOP");
    check(!!addition, "a binary operator must be lowered into a BINOP");
    check(addition.binop === "ADD", "the addition must be the ADD operator");

    // A property WRITE is a store with the same key, so that a later read
    // finds the value that was written.
    const propertyWrite = instructions.find(
      (i) =>
        i.kind === "ARRAYSTORE" &&
        i.lhs2 &&
        i.lhs2.kind === "CONST" &&
        i.lhs2.value === "label",
    );
    check(
      !!propertyWrite,
      "a property assignment must be an ARRAYSTORE whose key is the property name",
    );
    check(
      !instructions.some((i) => i.kind === "HAVOC"),
      "nothing in this program is beyond the translator",
    );

    // The function's own name is in its signature, so the sink catalogue has
    // something to match.
    check(
      typeof handleFunction.signature === "string" && handleFunction.signature.includes("handle"),
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
        language: "TYPESCRIPT",
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
      signatures.some((s) => s.startsWith("child_process.exec/")),
      `an imported function must name the module it came from: ${JSON.stringify(signatures)}`,
    );
    check(
      signatures.some((s) => s.startsWith("child_process.execFile/")),
      `an aliased import must be resolved to the name it was exported under: ${JSON.stringify(
        signatures,
      )}`,
    );
    check(
      signatures.some((s) => s.startsWith("vm.runInNewContext/")),
      `a namespace member must name the module it came from: ${JSON.stringify(signatures)}`,
    );
    check(
      signatures.some((s) => s.includes(".eval/")),
      `a global free function keeps the file's own module as its owner: ${JSON.stringify(signatures)}`,
    );
    check(
      !signatures.some((s) => s.startsWith("modules.exec") || s.startsWith("modules.runInNewContext")),
      `the file's own name must not stand in for the module: ${JSON.stringify(signatures)}`,
    );

    console.log("ok: the sidecar answers over the wire, and the TAC has the expected shape");
  } finally {
    child.kill();
  }
}

main().catch((e) => {
  console.error(`FAIL: ${e && e.stack ? e.stack : e}`);
  process.exit(1);
});
