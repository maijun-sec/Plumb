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

import phpParser from "php-parser";

import { IR_VERSION, OP, PROTOCOL_VERSION } from "./src/protocol.mjs";
import { Translator, preprocessSource } from "./src/translator.mjs";

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

/**
 * The third probe program: the same name, written several ways.
 *
 * PHP lets a name be written unqualified, fully qualified, or imported with a
 * `use` statement, and all of them name ONE class or function. The catalogue's
 * entries name that class or function once, so the callee key has to spell it
 * the same way every time -- otherwise an entry matches some of the calls to
 * its name and misses the others, and the scan reports success either way.
 */
const SPELLINGS_SOURCE = `<?php
namespace App\\Services;

use function system;
use SimpleXMLElement;

function spellings(string $cmd, string $xml): void {
    $a = system($cmd);
    $b = \\system($cmd);
    $c = new SimpleXMLElement($xml);
    $d = new \\SimpleXMLElement($xml);
}
`;

/**
 * The fourth probe program: the control flow whose shape has to be right.
 *
 * Every construct in here is one the acceptance list names, and every one of
 * them is one the engine depends on: a branch that carries its guard, a loop
 * whose end a `break` can reach, a catch whose body is reachable.
 */
const CONTROLS_SOURCE = `<?php
namespace App\\Services;

class Store {
    const KIND = "store";

    public function __construct(string $name) {
        $this->name = $name;
    }

    public function run(array $rows, int $n): string {
        if ($rows) {
            $x = 1;
        }
        if (isset($rows["k"])) {
            $present = 1;
        }
        $y = $rows && $n;
        $z = $rows ?? "d";
        for ($i = 0; $i < 3; $i++) {
            $acc = $i;
        }
        foreach (["a" => 1, "b" => 2] as $k => $v) {
            $t = $v;
        }
        foreach ($rows as &$ref) {
            $ref = 1;
        }
        switch ($n) {
            case 1:
                $s = "one";
                break;
            case 2:
                $s = "two";
                break;
        }
        try {
            $q = 1;
        } catch (\\RuntimeException | \\LogicException $e) {
            $q = 2;
        } finally {
            $q = 3;
        }
        $c = function () { return system($_GET['x']); };
        $a = fn($v) => system($v);
        $this->name = $_GET['name'];
        unset($rows['k']);
        $i2 = 0;
        $i2++;
        $k2 = Store::KIND;
        return "x";
    }

    public function levels(int $n): int {
        for ($p = 0; $p < 3; $p++) {
            while (true) {
                break 2;
            }
        }
        return $n;
    }

    public function make(string $name): Store {
        return new Store($name);
    }

    public function gen(array $rows): array {
        yield $rows['k'];
        yield from $rows;
    }
}
`;

/**
 * The parser's own settings.
 *
 * The same ones the sidecar uses: the positions are what a finding points
 * with, so they have to be there.
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

/**
 * The instructions a label's own block holds.
 *
 * A block ends at the jump that leaves it, so the instructions after the
 * jump belong to the next block and not to this one.
 */
function blockOf(instructions, label) {
  const start = instructions.findIndex((i) => i.kind === "LABEL" && i.label === label);
  if (start < 0) return [];
  const block = [];
  for (let i = start + 1; i < instructions.length; i++) {
    const ins = instructions[i];
    if (ins.kind === "LABEL") break;
    if (ins.kind === "JUMP" || ins.kind === "RETURN" || ins.kind === "THROW") break;
    block.push(ins);
  }
  return block;
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
    //
    // The CONSTRUCTOR is lowered as `<init>`: a `new` names the constructor
    // it calls as `<fqcn>.<init>/n`, so a constructor that kept its PHP
    // name would never be reached by the construction that calls it.
    const constructor = functions.find((f) => f.name === "<init>");
    check(!!constructor, "the constructor must be in the stream");
    check(
      constructor.memberKind === "CONSTRUCTOR",
      "the constructor must say that it is a constructor",
    );
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
    // The path where no arm matched THROWS in PHP, so it must be a throw in
    // the TAC: an unknown value there would go on and produce a result that
    // PHP would never produce.
    check(
      choose.instructions.some(
        (i, index) =>
          i.kind === "THROW" &&
          noMatchPath.has(index) &&
          i.ctorType &&
          i.ctorType.name === "UnhandledMatchError",
      ),
      "the path where no arm matched must throw",
    );
    check(
      branches.every(
        (entry) =>
          entry.ins.guard &&
          entry.ins.guard.kind === "Compare" &&
          entry.ins.guard.op === "EQ",
      ),
      "each test of a match must be an equality test",
    );
    const armBodies = choose.instructions.filter(
      (i) => i.kind === "MOVE" && i.lhs1 && i.lhs1.kind === "CONST",
    );
    check(armBodies.length > 0, "the match's arms must be in the stream");
    check(
      armBodies.every((body) => !noMatchPath.has(choose.instructions.indexOf(body))),
      "the path where no arm matched must not run an arm's body",
    );

    // The third file: ONE name, written several ways. The catalogue names it
    // once, so the key must spell it once.
    const spellingsFile = join(dir, "spellings.php");
    writeFileSync(spellingsFile, SPELLINGS_SOURCE, "utf8");
    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: spellingsFile,
        language: "PHP",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the third file must translate: ${response.error ?? ""}`);
    const spellings = (response.functions ?? []).find((f) => f.name === "spellings");
    check(!!spellings, "the function must be in the stream");
    const spellingSigs = (spellings.instructions ?? [])
      .filter((i) => i.calleeSig)
      .map((i) => i.calleeSig);

    // A global FUNCTION, imported and written out: both are the same function,
    // and the catalogue's entry for it is written `^\\(system|exec|...)`. A key
    // that spells it `system/1` does not match that entry, and the command
    // injection is missed with the scan still reporting success.
    check(
      spellingSigs.filter((s) => s === "\\system/1").length === 2,
      `a global function must have one spelling whatever way it is written: ${spellingSigs.join(", ")}`,
    );
    check(
      !spellingSigs.some((s) => s === "system/1"),
      `a global function must not be spelled without its namespace: ${spellingSigs.join(", ")}`,
    );

    // A global CLASS, imported and written out: the same rule, and the
    // construction's key is what a catalogue entry for the class matches.
    check(
      spellingSigs.filter((s) => s === "\\SimpleXMLElement.<init>/1").length === 2,
      `a global class must have one spelling whatever way it is written: ${spellingSigs.join(", ")}`,
    );
    check(
      !spellingSigs.some((s) => s === "SimpleXMLElement.<init>/1"),
      `a global class must not be spelled without its namespace: ${spellingSigs.join(", ")}`,
    );

    // The fourth file: the control flow whose shape has to be right.
    const controlsFile = join(dir, "controls.php");
    writeFileSync(controlsFile, CONTROLS_SOURCE, "utf8");
    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: controlsFile,
        language: "PHP",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the fourth file must translate: ${response.error ?? ""}`);
    const controlFunctions = response.functions ?? [];
    const run = controlFunctions.find((f) => f.name === "run");
    check(!!run, "the method must be in the stream");
    const runIns = run.instructions;

    // A branch that tests a value carries the test as a GUARD. Without one
    // the engine cannot tell one path from another, and the path condition
    // is empty on every branch of the function.
    const truthyBranches = runIns.filter(
      (i) => i.kind === "BRANCH" && i.guard && i.guard.kind === "Truthiness",
    );
    check(
      truthyBranches.length >= 2,
      `a branch on a value must carry a Truthiness guard: ${truthyBranches.length}`,
    );

    // The right operand of a `&&` is only evaluated when the left one says
    // it has to be, so it belongs on the branch that needs it -- and not
    // before the branch.
    const andBranch = truthyBranches.find((b) =>
      blockOf(runIns, b.trueLabel).some(
        (i) => i.lhs1 && i.lhs1.kind === "VAR" && i.lhs1.name === "n",
      ),
    );
    check(!!andBranch, "the `&&` must evaluate its right operand on the branch that needs it");
    const andTrueBlock = blockOf(runIns, andBranch.trueLabel);
    const rightRead = andTrueBlock.findIndex(
      (i) => i.lhs1 && i.lhs1.kind === "VAR" && i.lhs1.name === "n",
    );
    check(rightRead >= 0, "the right operand must be evaluated inside the branch that needs it");
    check(
      runIns.indexOf(andTrueBlock[rightRead]) > runIns.indexOf(andBranch),
      "the right operand must not be evaluated before the branch",
    );

    // The null-coalescing operator branches on "is not null", and the FALSE
    // branch is the one that takes the right operand.
    const coalesce = runIns.find(
      (i) =>
        i.kind === "BRANCH" &&
        i.guard &&
        i.guard.kind === "Compare" &&
        i.guard.op === "NE" &&
        i.guard.rhs &&
        i.guard.rhs.constKind === "NULL",
    );
    check(!!coalesce, "the null-coalescing operator must branch on 'is not null'");
    check(
      blockOf(runIns, coalesce.falseLabel).some(
        (i) => i.kind === "MOVE" && i.lhs1 && i.lhs1.kind === "CONST" && i.lhs1.value === "d",
      ),
      "the false branch of the null-coalescing operator must take the right operand",
    );

    // The loop's condition is a comparison, not an unknown value, and the
    // increment adds one.
    const forGuard = runIns.find(
      (i) =>
        i.kind === "BRANCH" &&
        i.guard &&
        i.guard.kind === "Compare" &&
        i.guard.op === "LT" &&
        i.guard.rhs &&
        i.guard.rhs.value === "3",
    );
    check(!!forGuard, "the loop's condition must be a comparison, not an unknown value");
    check(
      runIns.some(
        (i) =>
          i.kind === "BINOP" &&
          i.binop === "ADD" &&
          i.lhs1 &&
          i.lhs1.name === "i" &&
          i.lhs2 &&
          i.lhs2.value === "1",
      ),
      "the loop's increment must add one",
    );

    // The key variable binds the KEY. The keys of an array literal are
    // known, so they are read out of a table of them: the key variable must
    // not be the position.
    const keyWrite = runIns.find((i) => i.kind === "MOVE" && i.dest && i.dest.name === "k");
    check(!!keyWrite, "the key variable must be written");
    const keyTemp = keyWrite.lhs1;
    check(
      keyTemp && keyTemp.isTemp === true,
      "the key variable must bind the key, not the position",
    );
    const keyRead = runIns.find(
      (i) => i.kind === "ARRAYLOAD" && i.dest && i.dest.name === keyTemp.name,
    );
    check(!!keyRead, "the key must be read out of the table of the collection's keys");
    check(
      runIns.some((i) => i.kind === "NEWARRAY" && i.dest && i.dest.name === keyRead.lhs1.name),
      "the key must come from the table of the keys, not from the collection itself",
    );

    // A BY-REFERENCE foreach makes the value variable an alias of the
    // collection's entry. The TAC's locations are one name each, so the
    // write has to say which one it aliases.
    check(
      runIns.some((i) => i.kind === "MOVE" && i.aliasOf),
      "a by-reference foreach must say what the value variable aliases",
    );

    // A switch dispatches with one equality test per case, and it says so
    // in a branch region.
    const switchRegion = (run.branchRegions ?? []).find((r) => r.kind === "SWITCH");
    check(!!switchRegion, "a switch must carry a branch region");
    check(
      switchRegion.cases.length === 2,
      `a switch of two cases must have two cases in its region: ${JSON.stringify(switchRegion.cases)}`,
    );
    check(
      switchRegion.cases.every((c) => c.guards.every((g) => g.op === "EQ")),
      "each case of a switch must be an equality test",
    );

    // The catch body must be REACHABLE: a catch that no edge reaches is a
    // catch the engine never sees, and the exception flow through it is
    // lost. A catch of two types records both of them.
    const region = (run.tryRegions ?? [])[0];
    check(!!region, "a try must carry its region");
    check(
      !!region.catchLabels && region.catchLabels.length > 0,
      "the region must name the labels of its catches",
    );
    check(
      region.catchTypes && region.catchTypes[0] && region.catchTypes[0].length === 2,
      `a catch of two types must record both of them: ${JSON.stringify(region.catchTypes)}`,
    );
    check(
      runIns.some((i) => i.kind === "CATCH_HANDLER"),
      "a catch must emit a catch handler",
    );
    check(
      runIns.some((i) => i.kind === "FINALLY_ENTER"),
      "a finally must emit a finally entry",
    );
    const catchIndex = runIns.findIndex(
      (i) => i.kind === "LABEL" && i.label === region.catchLabels[0],
    );
    check(catchIndex >= 0, "the catch's label must be in the stream");
    check(
      catchIndex >= 0 &&
        runIns[catchIndex].labelRef &&
        runIns[catchIndex].labelRef.kind === "CATCH_ENTRY",
      "the catch's label must say that it is a catch's entry",
    );
    const finallyLabel = runIns.find(
      (i) => i.kind === "LABEL" && i.label === region.finallyLabel,
    );
    check(
      finallyLabel && finallyLabel.labelRef && finallyLabel.labelRef.kind === "FINALLY_ENTRY",
      "the finally's label must say that it is a finally's entry",
    );
    const catchBody = runIns.find(
      (i) =>
        i.kind === "MOVE" &&
        i.dest &&
        i.dest.name === "q" &&
        i.lhs1 &&
        i.lhs1.kind === "CONST" &&
        i.lhs1.value === "2",
    );
    check(!!catchBody, "the catch body must be in the stream");
    check(
      catchIndex >= 0 && reachableFrom(runIns, catchIndex).has(runIns.indexOf(catchBody)),
      "the catch body must be reachable",
    );

    // A closure is a function of its own: the body is lowered on its own,
    // and the call inside it is a call the engine can name.
    const closures = controlFunctions.filter((f) => f.name.startsWith("closure$"));
    check(
      closures.length >= 2,
      `the closure and the arrow function must each be a function of their own: ${closures.length}`,
    );
    for (const closure of closures) {
      check(
        closure.instructions.some((i) => i.calleeSig === "\\system/1"),
        `the closure's body must carry its call: ${closure.name}`,
      );
    }

    // The yielded value is EVALUATED: a generator that threw its value away
    // would lose the taint that came in with it. The states of a generator
    // cannot be split into functions of their own, and the difference is
    // recorded rather than passed off as a whole lowering.
    const gen = controlFunctions.find((f) => f.name === "gen");
    check(!!gen, "the generator must be in the stream");
    check(
      gen.instructions.some((i) => i.kind === "ARRAYLOAD"),
      "the yielded value must be evaluated",
    );
    check(
      (gen.drops ?? []).some((d) => d.construct === "expr.generator.states"),
      "a generator whose states cannot be split must say so",
    );

    // A property write says which class it writes to.
    const ownerWrite = runIns.find((i) => i.kind === "ARRAYSTORE" && i.ownerType);
    check(!!ownerWrite, "a property write must say which class it writes to");
    check(
      ownerWrite.lhs1 && ownerWrite.lhs1.name === "this",
      "a property write must write into the object",
    );

    // A construction names the constructor it calls, and the constructor's
    // body is in the stream.
    const make = controlFunctions.find((f) => f.name === "make");
    check(!!make, "the method must be in the stream");
    const storeNew = (make.instructions ?? []).find((i) => i.kind === "NEW");
    check(!!storeNew, "the construction must be a NEW instruction");
    check(
      storeNew.calleeSig === "App\\Services\\Store.<init>/1",
      `a construction must name the constructor it calls: ${storeNew.calleeSig}`,
    );
    const storeConstructor = controlFunctions.find((f) => f.name === "<init>");
    check(!!storeConstructor, "the constructor must be in the stream");
    check(
      storeConstructor.memberKind === "CONSTRUCTOR",
      "the constructor must say that it is a constructor",
    );
    check(
      storeConstructor.instructions.some((i) => i.kind === "ARRAYSTORE"),
      "the constructor's body must be in the stream",
    );

    // An isset carries a presence guard, leaves no unknown value, and does
    // not claim that the function is whole: the guard cannot be rendered
    // into a predicate, and the loss is recorded.
    const presence = runIns.find(
      (i) => i.kind === "BRANCH" && i.guard && i.guard.kind === "PropertyPresence",
    );
    check(!!presence, "an isset must carry a presence guard");
    check(
      !runIns.some((i) => i.kind === "HAVOC"),
      "an isset must not leave an unknown value",
    );
    check(
      run.precision !== "SOURCE_FULL",
      `a function that lost a guard must not claim full precision: ${run.precision}`,
    );
    check(
      (run.drops ?? []).some((d) => d.construct === "guard.unrenderable"),
      "a guard that cannot be rendered must be recorded",
    );

    // An unset deletes the key, and a deletion is a store that says so.
    check(
      runIns.some((i) => i.kind === "ARRAYSTORE" && i.deleteKey === true),
      "an unset must delete the key with a store",
    );

    // An increment writes the new value back: the TAC tracks a location, not
    // the statement that changed it.
    check(
      runIns.some(
        (i) =>
          i.kind === "BINOP" &&
          i.binop === "ADD" &&
          i.lhs1 &&
          i.lhs1.name === "i2" &&
          i.lhs2 &&
          i.lhs2.value === "1",
      ),
      "an increment must add one",
    );
    check(
      runIns.some(
        (i) =>
          i.kind === "MOVE" &&
          i.dest &&
          i.dest.name === "i2" &&
          i.lhs1 &&
          i.lhs1.isTemp === true,
      ),
      "an increment must write the new value back",
    );

    // A class constant read is an instruction: a constant that is read
    // without an instruction is a constant the engine cannot see.
    check(
      runIns.some((i) => i.kind === "ARRAYLOAD" && i.lhs2 && i.lhs2.value === "KIND"),
      "a class constant read must emit an instruction",
    );

    // A break with a level leaves the loop that many levels out, so it
    // jumps to the OUTER loop's end.
    const levels = controlFunctions.find((f) => f.name === "levels");
    check(!!levels, "the method must be in the stream");
    const loopEnds = levels.instructions.filter(
      (i) => i.kind === "LABEL" && i.labelRef && i.labelRef.kind === "LOOP_END",
    );
    check(loopEnds.length === 2, `the two loops must have two ends: ${loopEnds.length}`);
    const levelBreak = levels.instructions.find(
      (i) => i.kind === "JUMP" && i.labelRef && i.labelRef.kind === "LOOP_END",
    );
    check(!!levelBreak, "a break with a level must jump to a loop's end");
    check(
      levelBreak.label === loopEnds[loopEnds.length - 1].label,
      `a break with a level must leave the OUTER loop: ${levelBreak.label}`,
    );

    // The return type is the one the source declares, and it is a type
    // OBJECT and not a string.
    check(
      run.returnType && run.returnType.kind === "STRING",
      `the return type must be the declared one: ${JSON.stringify(run.returnType)}`,
    );
    const clinit = controlFunctions.find((f) => f.name === "<clinit>");
    check(
      clinit && clinit.memberKind === "FIELD_INITIALIZER",
      "the class body must say that it is a field initializer",
    );

    // The interpolation the parser cannot read.
    //
    // `"$_GET['x']"` is a form php-parser 3.7.0 does not read: the quote of
    // the subscript goes into the lexer's whitespace class, so the parser
    // sees a string where it expects a name, and the WHOLE FILE fails to
    // parse -- which means every sink in the file disappears from the scan.
    //
    // The rewrite into the complex form `"{$_GET['x']}"` is what makes it
    // read, and the three writings must all parse and must all give the
    // same shape.
    const INTERPOLATIONS = [`"$_GET['x']"`, `"$a[0]"`, `"{$a['x']}"`];
    const shapes = [];
    for (const interpolation of INTERPOLATIONS) {
      const source = `<?php
function probe() {
    $x = ${interpolation};
    return $x;
}
`;
      const rewritten = preprocessSource(source);
      let program;
      try {
        program = phpParser.parseCode(rewritten.text, PARSER_OPTIONS);
      } catch (e) {
        fail(`the interpolation ${interpolation} must parse: ${e && e.message}`);
      }
      const translated = new Translator({ moduleName: "smoke", text: source }).translateProgram(
        program,
      );
      const probe = translated.functions.find((f) => f.name === "probe");
      check(!!probe, `the interpolation ${interpolation} must produce a function`);
      check(
        probe.instructions.some((i) => i.kind === "ARRAYLOAD"),
        `the interpolation ${interpolation} must produce an ARRAYLOAD`,
      );
      shapes.push(probe.instructions.map((i) => i.kind).join(","));
    }
    check(
      shapes[0] === shapes[1] && shapes[1] === shapes[2],
      `the three writings must give the same shape: ${shapes.join(" | ")}`,
    );

    // The TYPE SURFACE: a class's super type is a fact about the class, and a
    // rule that names a base class has to be able to find it. The types are
    // not on the wire yet -- the entry point has to carry them -- so they are
    // read from the translator here.
    const typedSource = `<?php
namespace App\\Services;

class Child extends Base implements Shape {
}
`;
    const typedProgram = phpParser.parseCode(typedSource, PARSER_OPTIONS);
    const typedResult = new Translator({ moduleName: "smoke", text: typedSource }).translateProgram(
      typedProgram,
    );
    const childType = (typedResult.types ?? []).find((t) => t.simpleName === "Child");
    check(!!childType, "a class must declare itself");
    check(
      childType.superType === "App\\Services\\Base",
      `a class's super type must be declared: ${childType.superType}`,
    );
    check(
      (childType.interfaces ?? []).includes("App\\Services\\Shape"),
      `a class's interfaces must be declared: ${JSON.stringify(childType.interfaces)}`,
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
