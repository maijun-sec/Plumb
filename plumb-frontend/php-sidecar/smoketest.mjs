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
const SOURCE = `<?php
namespace App\\Services;

use App\\Support\\Logger;

class Mailer {
    private Logger $log;

    public function __construct(string $name) {
        $this->log = new Logger($name);
    }

    public function send(string $to, string $body): bool {
        $cmd = "mail -s test " . $to;
        $rc = system($cmd);
        $this->log = $cmd;
        $seen = $this->log;
        $out = helper($seen);
        $ran = \`ls $cmd\`;
        return true;
    }
}

function helper(string $a): string {
    return $a;
}
`;

/** The second probe program: the constructs whose shape has to be right. */
const SECOND_SOURCE = `<?php
namespace App\\Services;

function pick(string $a): string {
    $w = $a ?: "empty";
    return $w;
}

function choose(int $b): string {
    $v = match($b) {
        1 => "one",
        2, 3 => "two-or-three",
    };
    return $v;
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

/**
 * The instructions reachable from one instruction.
 *
 * The control flow is what a lowering gets wrong silently: a stream that
 * reads perfectly can still run the wrong arm.
 */
function reachableFrom(instructions, start) {
  const labelIndex = new Map();
  instructions.forEach((ins, i) => {
    if (ins.kind === "LABEL") labelIndex.set(ins.label, i);
  });
  const seen = new Set();
  const stack = [start];
  while (stack.length > 0) {
    const i = stack.pop();
    if (i === undefined || i < 0 || i >= instructions.length || seen.has(i)) continue;
    seen.add(i);
    const ins = instructions[i];
    if (ins.kind === "JUMP") {
      const target = labelIndex.get(ins.label);
      if (target !== undefined) stack.push(target);
      continue;
    }
    if (ins.kind === "BRANCH") {
      for (const label of [ins.trueLabel, ins.falseLabel]) {
        const target = labelIndex.get(label);
        if (target !== undefined) stack.push(target);
      }
      continue;
    }
    if (ins.kind === "RETURN" || ins.kind === "THROW") continue;
    stack.push(i + 1);
  }
  return seen;
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
  const dir = mkdtempSync(join(tmpdir(), "plumb-php-smoke-"));
  const file = join(dir, "app.php");
  writeFileSync(file, SOURCE, "utf8");

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
        sources: [{ path: file }],
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
        language: "PHP",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the file must translate: ${response.error ?? ""}`);

    const functions = response.functions ?? [];
    const send = functions.find((f) => f.name === "send");
    check(!!send, "the method must be in the stream");
    check(
      send.enclosingClass === "App\\Services\\Mailer",
      "a method must name the class it belongs to, qualified as PHP qualifies it",
    );

    const helperFunction = functions.find((f) => f.name === "helper");
    check(!!helperFunction, "the function must be in the stream");
    check(
      helperFunction.signature === "App\\Services\\helper(string):string",
      `the signature must be the PHP qualified name: ${helperFunction.signature}`,
    );

    const instructions = send.instructions;

    // A property read is a CONTAINER ACCESS with a string key, not a field
    // access: in this language an object is a namespace, and `$o->name` and
    // `$o["name"]` are the same location.
    const propertyRead = instructions.find(
      (i) => i.kind === "ARRAYLOAD" && i.lhs2 && i.lhs2.kind === "CONST" && i.lhs2.value === "log",
    );
    check(
      !!propertyRead,
      "a property read must be an ARRAYLOAD whose key is the property name",
    );
    check(
      !instructions.some((i) => i.kind === "FIELDLOAD" || i.kind === "FIELDSTORE"),
      "a property access must not be a field access in this language",
    );

    // A property WRITE is a store with the same key, so that a later read
    // finds the value that was written.
    const propertyWrite = instructions.find(
      (i) =>
        i.kind === "ARRAYSTORE" &&
        i.lhs2 &&
        i.lhs2.kind === "CONST" &&
        i.lhs2.value === "log",
    );
    check(
      !!propertyWrite,
      "a property assignment must be an ARRAYSTORE whose key is the property name",
    );

    // A call keeps the receiver slot: args[0] is the receiver, args[1..] are
    // the arguments. A call that put the tainted value at args[0] would look
    // right and match nothing.
    const systemCall = instructions.find((i) => i.calleeSig === "\\system/1");
    check(!!systemCall, "a global function call must be named the way PHP names it");
    check(systemCall.args.length >= 2, "a call must carry the receiver slot and its arguments");
    check(
      systemCall.args[0].kind === "CONST" && systemCall.args[0].value === "\\",
      "a free function's receiver slot holds the namespace it is in",
    );

    // A call of a function the file declares carries the ARITY OF THE
    // DECLARATION and the namespace it is in, because that is the signature
    // the sink catalogue matches on.
    const helperCall = instructions.find((i) => i.calleeSig === "App\\Services\\helper/1");
    check(!!helperCall, "a namespaced call must carry the namespace PHP gives it");
    check(helperCall.kind === "CALL", "a call of a declared function is a CALL, not a black box");

    // The construction is a NEW, and the `use` statement resolved the class.
    const constructor = functions.find((f) => f.name === "__construct");
    check(!!constructor, "the constructor must be in the stream");
    const construction = (constructor.instructions ?? []).find((i) => i.kind === "NEW");
    check(!!construction, "the construction must be a NEW instruction");
    check(
      construction.ctorType && construction.ctorType.name === "App\\Support\\Logger",
      `a \`use\` statement must resolve the class name: ${JSON.stringify(construction.ctorType)}`,
    );
    // ... and the construction NAMES the class it constructs. The engine looks
    // a construction up in the same catalogue as a call, so a construction
    // that cannot be named cannot be matched by any rule.
    check(
      construction.calleeSig === "App\\Support\\Logger.<init>/1",
      `a construction must name the class it constructs: ${construction.calleeSig}`,
    );

    // A binary operator is a BINOP, not an unknown value. A lookup that
    // always misses does not look like a lookup: every binary expression
    // would become a HAVOC, and the taint that flowed through it would stop
    // there without a word.
    const addition = instructions.find((i) => i.kind === "BINOP");
    check(!!addition, "a binary operator must be lowered into a BINOP");
    check(addition.binop === "ADD", "the concatenation must be the ADD operator");

    // A backtick string is a COMMAND: PHP runs it through the shell. It is
    // lowered as a call to a shell stand-in, so the command is visible as a
    // call and not as an ordinary string.
    const shell = instructions.find((i) => i.calleeSig === "<php>.shell/1");
    check(!!shell, "a backtick string must be lowered as a shell command");

    check(
      !instructions.some((i) => i.kind === "HAVOC"),
      "nothing in this program is beyond the translator",
    );

    // The second file: the constructs whose shape has to be right even
    // though the stream reads perfectly.
    const secondFile = join(dir, "shapes.php");
    writeFileSync(secondFile, SECOND_SOURCE, "utf8");
    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: secondFile,
        language: "PHP",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the second file must translate: ${response.error ?? ""}`);
    const secondFunctions = response.functions ?? [];

    // The SHORT form of the conditional, `$a ?: $b`, yields the TEST's own
    // value when the test holds. A placeholder for that arm would throw
    // away the taint that came in with the test.
    const pick = secondFunctions.find((f) => f.name === "pick");
    check(!!pick, "the function must be in the stream");
    check(
      !pick.instructions.some((i) => i.kind === "HAVOC"),
      "the short conditional must yield the test's value, not an unknown one",
    );
    check(
      pick.instructions.some(
        (i) => i.kind === "MOVE" && i.lhs1 && i.lhs1.kind === "VAR" && i.lhs1.name === "a",
      ),
      "the short conditional's true arm must be the test's value",
    );

    // A `match` that no arm satisfies THROWS in PHP. So the path where no
    // arm matched must not run an arm's body -- otherwise the first arm's
    // findings are reported for a value PHP would have thrown on.
    const choose = secondFunctions.find((f) => f.name === "choose");
    check(!!choose, "the function must be in the stream");
    const branches = choose.instructions
      .map((ins, i) => ({ ins, i }))
      .filter((entry) => entry.ins.kind === "BRANCH");
    check(branches.length > 0, "the match must be lowered into a chain of tests");
    const labelIndex = new Map();
    choose.instructions.forEach((ins, i) => {
      if (ins.kind === "LABEL") labelIndex.set(ins.label, i);
    });
    const noMatchEntry = labelIndex.get(branches[branches.length - 1].ins.falseLabel);
    check(noMatchEntry !== undefined, "the match's last test must have a false target");
    const noMatchPath = reachableFrom(choose.instructions, noMatchEntry);
    const armBodies = choose.instructions.filter(
      (i) => i.kind === "MOVE" && i.lhs1 && i.lhs1.kind === "CONST",
    );
    check(armBodies.length > 0, "the match's arms must be in the stream");
    check(
      armBodies.every((body) => !noMatchPath.has(choose.instructions.indexOf(body))),
      "the path where no arm matched must not run an arm's body",
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
