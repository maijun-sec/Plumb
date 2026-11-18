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
 * A third probe program: an immediately invoked function.
 *
 * <p>An IIFE calls a function literal, and the literal's body is CODE. If
 * the body is not in the TAC at all -- not as instructions, and not as a drop
 * either -- then the function around it reports full precision, the scan
 * reports success, and the code is simply gone.
 */
const IIFE_SOURCE = `
export function runIife(request: any): any {
  (() => {
    eval(request["cmd"]);
  })();
  return request;
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

/**
 * A third probe program: the shapes the lowering has to produce.
 *
 * <p>Every construct here is one the TAC has an opinion about, and the
 * assertions below say which shape it has to be. A shape that is not
 * asserted here is a shape nobody is checking.
 */
const SHAPES_SOURCE = `
import { exec } from "child_process";

export class Holder {
  #secret: string = "s";
  readonly name: string = "n";
  constructor(private id: string) {
    this.id = id;
  }
  get label(): string {
    return this.name;
  }
  set label(value: string) {
    this.name = value;
  }
  static {
    Holder.counter = 1;
  }
}

export function shapes(input: string | null, count: number, list: string[]): string {
  const and = input && input.length;
  const or = input || "none";
  const coalesce = input ?? "none";
  const conditional = input ? count : 0;
  const optional = input?.length;
  const optionalCall = input?.toUpperCase();
  const optionalIndex = input?.[0];
  const isString = input instanceof String;
  const hasKey = "length" in (input as any);
  let counter = count;
  counter &&= 1;
  counter ||= 2;
  counter ??= 3;
  const negated = !input;
  const incremented = ++counter;
  const decremented = --counter;
  const voided = void 0;
  const comma = (1, 2);
  const pattern = /ab+c/gi;
  switch (count) {
    case 1:
      exec("one");
      break;
    case 2:
      exec("two");
      break;
    default:
      exec("other");
  }
  try {
    exec("try");
  } catch (error) {
    exec("catch");
  } finally {
    exec("finally");
  }
  for (const item of list) {
    exec(item);
  }
  for (const key in list) {
    exec(key);
  }
  outer: for (const item of list) {
    continue outer;
  }
  do {
    exec("do");
  } while (false);
  return input ?? "";
}
`;

/**
 * A fourth probe program: the decorators.
 *
 * <p>A decorator runs where the thing it decorates is DEFINED, and it is
 * what the name holds afterwards. So it is a CALL, and it belongs to the
 * stream of the definition -- the class's own stream, or the module's --
 * and never to the body of the function it decorates.
 */
const DECORATORS_SOURCE = `
export function trace(value: unknown): unknown {
  return value;
}

export class Decorated {
  constructor(private id: string) {
    this.id = id;
  }

  @trace
  method(a: string): string {
    return a;
  }
}

@trace("tag")
export function topLevel(a: string): string {
  return a;
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

/** The index of the first instruction that matches, or -1. */
function indexOf(instructions, predicate) {
  return instructions.findIndex((instruction) => predicate(instruction));
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
  const shapesFile = join(dir, "shapes.ts");
  writeFileSync(shapesFile, SHAPES_SOURCE, "utf8");
  const decoratorFile = join(dir, "decorators.ts");
  writeFileSync(decoratorFile, DECORATORS_SOURCE, "utf8");
  const iifeFile = join(dir, "iife.ts");
  writeFileSync(iifeFile, IIFE_SOURCE, "utf8");

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
        sources: [
          { path: file },
          { path: moduleFile },
          { path: shapesFile },
          { path: decoratorFile },
          { path: iifeFile },
        ],
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
      signatures.some((s) => s.startsWith("eval.eval/")),
      `a global free function belongs to the global it came from, and names it as its owner: ${JSON.stringify(signatures)}`,
    );
    check(
      !signatures.some((s) => s.startsWith("modules.exec") || s.startsWith("modules.runInNewContext")),
      `the file's own name must not stand in for the module: ${JSON.stringify(signatures)}`,
    );

    // ---- the shapes the lowering has to produce ------------------------------------------------

    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: shapesFile,
        language: "TYPESCRIPT",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the shapes file must translate: ${response.error ?? ""}`);

    const shapeFunctions = response.functions ?? [];
    const shapes = shapeFunctions.find((f) => f.name === "shapes");
    check(!!shapes, "the function with the shapes must be in the stream");
    const shapeInstructions = shapes.instructions ?? [];

    // The function object carries the keys the Java side reads.
    check(Array.isArray(shapes.drops), "a function must carry the drops it recorded");
    check(
      typeof shapes.memberKind === "string",
      "a function must say what kind of member it is",
    );
    check(
      shapes.returnType && shapes.returnType.kind === "STRING",
      `a function must carry its return type: ${JSON.stringify(shapes.returnType)}`,
    );
    check(Array.isArray(shapes.tryRegions), "a function must carry the try regions it has");

    // A short-circuit operator is a BRANCH, and the right-hand side is
    // evaluated on ONE side of it only.
    const truthy = shapeInstructions.filter(
      (i) => i.kind === "BRANCH" && i.guard && i.guard.kind === "Truthiness",
    );
    check(truthy.length >= 3, `the short-circuit operators must be branches: ${truthy.length}`);
    const coalesce = shapeInstructions.find(
      (i) =>
        i.kind === "BRANCH" &&
        i.guard &&
        i.guard.kind === "Compare" &&
        i.guard.op === "NE" &&
        i.guard.rhs &&
        i.guard.rhs.constKind === "NULL",
    );
    check(!!coalesce, "a null-coalescing operator must compare against null");
    // The result of a short-circuit is defined on BOTH paths.
    const andMove = shapeInstructions.find(
      (i) => i.kind === "MOVE" && i.dest && i.dest.name === "and",
    );
    check(!!andMove, "the short-circuit's result must be bound");
    const andTemp = andMove && andMove.lhs1 && andMove.lhs1.name;
    const andAssignments = shapeInstructions.filter(
      (i) => i.kind === "MOVE" && i.dest && i.dest.name === andTemp,
    );
    check(
      andAssignments.length === 2,
      `the result of a short-circuit must be defined on both paths: ${andAssignments.length}`,
    );

    // A ternary is a branch with a MOVE on each side.
    const conditionalMove = shapeInstructions.find(
      (i) => i.kind === "MOVE" && i.dest && i.dest.name === "conditional",
    );
    check(!!conditionalMove, "the ternary's result must be bound");
    const conditionalTemp = conditionalMove && conditionalMove.lhs1 && conditionalMove.lhs1.name;
    const conditionalAssignments = shapeInstructions.filter(
      (i) => i.kind === "MOVE" && i.dest && i.dest.name === conditionalTemp,
    );
    check(
      conditionalAssignments.length === 2,
      `each side of a ternary must be assigned once: ${conditionalAssignments.length}`,
    );

    // An optional chain checks the receiver FIRST: the instruction that follows
    // the check is the read, and the nullish side has none.
    const nullChecks = shapeInstructions
      .map((instruction, index) => ({
        instruction,
        index,
        isNullCheck:
          instruction.kind === "BRANCH" &&
          instruction.guard &&
          instruction.guard.kind === "Compare" &&
          instruction.guard.op === "NE" &&
          instruction.guard.rhs &&
          instruction.guard.rhs.constKind === "NULL",
      }))
      .filter((entry) => entry.isNullCheck);
    check(nullChecks.length >= 1, "an optional chain must check its receiver");
    const afterCheck = (index) => {
      const next = shapeInstructions[index + 1];
      if (!next) return undefined;
      return next.kind === "LABEL" ? shapeInstructions[index + 2] : next;
    };
    check(
      nullChecks.some((entry) => {
        const next = afterCheck(entry.index);
        return (
          next &&
          ((next.kind === "ARRAYLOAD" && next.lhs2 && next.lhs2.value === "length") ||
            next.kind === "CALL")
        );
      }),
      "the read of an optional chain must come after the check",
    );

    // A switch dispatches: every case is a branch with a comparison.
    const caseBranches = shapeInstructions.filter(
      (i) => i.kind === "BRANCH" && i.guard && i.guard.kind === "Compare" && i.guard.op === "EQ",
    );
    check(caseBranches.length >= 2, `a switch must dispatch on its cases: ${caseBranches.length}`);
    check(
      shapeInstructions.some(
        (i) => i.kind === "LABEL" && i.labelRef && i.labelRef.kind === "SWITCH_CASE",
      ),
      "a switch's case must have a label reference",
    );

    // A try is a CATCH_HANDLER and a FINALLY_ENTER, and the region says
    // where the parts are.
    check(
      shapeInstructions.some((i) => i.kind === "CATCH_HANDLER"),
      "a catch must bind the exception",
    );
    check(
      shapeInstructions.some((i) => i.kind === "FINALLY_ENTER"),
      "a finally must be entered",
    );
    const region = (shapes.tryRegions ?? [])[0];
    check(!!region, "a try must be recorded as a region");
    check(region.tryStart >= 0 && region.tryEnd > region.tryStart, "the try body must be located");
    check(
      Array.isArray(region.catchLabels) && region.catchLabels.length === 1,
      "the catch must have an entry label",
    );
    check(
      Array.isArray(region.catchTypes) && region.catchTypes[0] === "*",
      "a catch without a type annotation is typed '*'",
    );

    // A `for ... of` binds the ELEMENT, not the iterable.
    const itemMove = shapeInstructions.find(
      (i) => i.kind === "MOVE" && i.dest && i.dest.name === "item",
    );
    check(!!itemMove, "the loop variable must be bound");
    check(
      indexOf(
        shapeInstructions,
        (i) =>
          i.kind === "ARRAYLOAD" &&
          i.lhs2 &&
          i.lhs2.kind === "CONST" &&
          i.lhs2.value === "value",
      ) >= 0,
      "the element must come from the iterator's value",
    );
    check(
      itemMove && itemMove.lhs1 && itemMove.lhs1.kind === "VAR" && itemMove.lhs1.isTemp === true,
      "the loop variable must be bound to the element, not to the iterable",
    );

    // A `for ... in` binds the KEY.
    const keyMove = shapeInstructions.find(
      (i) => i.kind === "MOVE" && i.dest && i.dest.name === "key",
    );
    check(!!keyMove, "the key variable must be bound");

    // A labelled continue is a JUMP.
    check(
      shapeInstructions.some((i) => i.kind === "JUMP"),
      "a continue must be a jump",
    );

    // A type test is an INSTANCEOF.
    check(
      shapeInstructions.some((i) => i.kind === "INSTANCEOF"),
      "an instanceof must be a type test",
    );

    // A property presence test is a branch with a presence guard.
    check(
      shapeInstructions.some(
        (i) => i.kind === "BRANCH" && i.guard && i.guard.kind === "PropertyPresence",
      ),
      "an `in` must be a property presence test",
    );

    // The unary operators are unary operations with their own operators.
    const unops = shapeInstructions.filter((i) => i.kind === "UNARYOP").map((i) => i.unop);
    check(unops.includes("NOT"), `a negation must be a NOT: ${JSON.stringify(unops)}`);
    check(unops.includes("PRE_INC"), `a prefix increment must be a PRE_INC: ${JSON.stringify(unops)}`);
    check(
      unops.includes("PRE_DEC"),
      `a prefix decrement must be a PRE_DEC: ${JSON.stringify(unops)}`,
    );

    // A regular expression is a constant of its own kind.
    check(
      shapeInstructions.some(
        (i) =>
          i.kind === "MOVE" && i.lhs1 && i.lhs1.kind === "CONST" && i.lhs1.constKind === "REGEX",
      ),
      "a regular expression must be a REGEX constant",
    );

    // The class's own members are functions of their own, and they say what
    // kind of member they are.
    const constructor = shapeFunctions.find((f) => f.name === "<init>");
    check(!!constructor, "the constructor must be in the stream");
    check(
      constructor.memberKind === "CONSTRUCTOR",
      `the constructor must say so: ${constructor.memberKind}`,
    );
    check(
      shapeFunctions.some((f) => f.name === "label" && f.memberKind === "GETTER"),
      "the getter must be in the stream",
    );
    check(
      shapeFunctions.some((f) => f.name === "label" && f.memberKind === "SETTER"),
      "the setter must be in the stream",
    );
    check(
      shapeFunctions.some((f) => f.memberKind === "STATIC_INITIALIZER"),
      "the static block must be in the stream",
    );

    // A class field is a FIELDSTORE with the class that owns it.
    const fieldStore = (constructor.instructions ?? []).find((i) => i.kind === "FIELDSTORE");
    check(!!fieldStore, "a class field must be stored as a field");
    check(
      fieldStore.ownerType === "Holder",
      `a field must name the class that owns it: ${fieldStore.ownerType}`,
    );
    check(
      fieldStore.fieldName === "name" || fieldStore.fieldName === "#secret",
      `a field must keep its name: ${fieldStore.fieldName}`,
    );

    // A union is not collapsed into its first member.
    const inputOperand = shapeInstructions.find(
      (i) => i.lhs1 && i.lhs1.kind === "VAR" && i.lhs1.name === "input" && i.lhs1.type,
    );
    check(
      inputOperand &&
        inputOperand.lhs1.type.kind === "STRING" &&
        inputOperand.lhs1.type.nullable === true,
      `a union must keep its nullability: ${JSON.stringify(inputOperand && inputOperand.lhs1.type)}`,
    );

    // A number is a double, and a double has a domain.
    const countOperand = shapeInstructions.find(
      (i) => i.lhs1 && i.lhs1.kind === "VAR" && i.lhs1.name === "count" && i.lhs1.type,
    );
    check(
      countOperand && countOperand.lhs1.type.kind === "DOUBLE",
      `a number must be a DOUBLE: ${JSON.stringify(countOperand && countOperand.lhs1.type)}`,
    );
    check(
      countOperand &&
        countOperand.lhs1.type.domain &&
        countOperand.lhs1.type.domain.kind === "Floating" &&
        countOperand.lhs1.type.domain.bits === 53,
      `a number must carry the double's domain: ${JSON.stringify(
        countOperand && countOperand.lhs1.type,
      )}`,
    );

    // An array keeps its element type.
    const listOperand = shapeInstructions
      .flatMap((i) => [i.lhs1, i.lhs2, ...(i.args ?? [])])
      .find((op) => op && op.kind === "VAR" && op.name === "list" && op.type);
    check(
      listOperand &&
        listOperand.type.kind === "ARRAY" &&
        listOperand.type.component &&
        listOperand.type.component.kind === "STRING",
      `an array must keep its element type: ${JSON.stringify(listOperand && listOperand.type)}`,
    );

    // ---- the decorators ----------------------------------------------------------------------

    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: decoratorFile,
        language: "TYPESCRIPT",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the decorators file must translate: ${response.error ?? ""}`);

    const decoratorFunctions = response.functions ?? [];
    const decoratedMethod = decoratorFunctions.find((f) => f.name === "method");
    check(!!decoratedMethod, "the decorated method must be in the stream");

    // A decorator runs where the thing it decorates is DEFINED. A call in
    // the body of the decorated function would run it once per CALL of that
    // function instead of once per definition -- and a rule that matches on
    // a decorator's name would then match a call that never happens.
    const methodCalls = (decoratedMethod.instructions ?? [])
      .filter((i) => i.kind === "CALL" || i.kind === "UNINTERPRETED")
      .map((i) => i.calleeSig);
    check(
      !methodCalls.some((signature) => signature && signature.includes("trace")),
      `a decorator must not run in the body of the function it decorates: ${JSON.stringify(
        methodCalls,
      )}`,
    );

    // The decorator's call is in the CLASS's own stream: the constructor's.
    const classStream = decoratorFunctions.find((f) => f.name === "<init>");
    check(!!classStream, "the class's own stream must be in the stream");
    const classInstructions = classStream.instructions ?? [];
    const classCallKeys = classInstructions
      .filter((i) => i.kind === "CALL")
      .map((i) => i.calleeSig);
    const decoratorCall = classInstructions.find(
      (i) => i.kind === "CALL" && i.calleeSig === "decorators.trace/1",
    );
    check(
      !!decoratorCall,
      `a decorator must be a call in the class's own stream: ${JSON.stringify(classCallKeys)}`,
    );

    // The callee key names the decorator, and its owner is the decorator's
    // OWN: the module that declares it. An owner that names nothing at all
    // would make a rule that matches on a decorator's name match nothing
    // too.
    check(
      decoratorCall.calleeSig.startsWith("decorators.trace/"),
      `a decorator's callee key must name it: ${decoratorCall.calleeSig}`,
    );
    check(
      !decoratorCall.calleeSig.startsWith("<"),
      `a decorator's callee key must not carry a placeholder owner: ${decoratorCall.calleeSig}`,
    );

    // The receiver slot comes first: the owner is a constant, and the real
    // arguments follow it. The decorated thing is the last of them.
    check(
      decoratorCall.args[0] &&
        decoratorCall.args[0].kind === "CONST" &&
        decoratorCall.args[0].constKind === "STRING",
      `a decorator's first argument must be the constant owner slot: ${JSON.stringify(
        decoratorCall.args[0],
      )}`,
    );
    check(
      decoratorCall.args[0].value === "decorators",
      `the owner slot must name the decorator's owner: ${decoratorCall.args[0].value}`,
    );
    check(
      decoratorCall.args.length === 3,
      `a decorator's arguments are the owner, the decorator and the holder: ${JSON.stringify(
        decoratorCall.args,
      )}`,
    );
    check(
      decoratorCall.args[2] && decoratorCall.args[2].name === "method",
      `the holder must be the decorated thing: ${JSON.stringify(decoratorCall.args[2])}`,
    );

    // What the decorator returns is what the name holds afterwards.
    const writeBack = classInstructions.find(
      (i) => i.kind === "MOVE" && i.dest && i.dest.name === "method",
    );
    check(
      !!writeBack,
      "the decorator's result must be written back to the decorated name",
    );

    // A top-level function's decorator runs in the MODULE's stream. The
    // decorator that is CALLED runs its factory first, and the factory's
    // result is what decorates: the two calls have different arities.
    const moduleStream = decoratorFunctions.find((f) => f.name === "__top");
    check(!!moduleStream, "the module's own stream must be in the stream");
    const moduleCallKeys = (moduleStream.instructions ?? [])
      .filter((i) => i.kind === "CALL")
      .map((i) => i.calleeSig);
    check(
      moduleCallKeys.includes("decorators.trace/1"),
      `a decorator that is called must run its factory: ${JSON.stringify(moduleCallKeys)}`,
    );
    check(
      moduleCallKeys.includes("decorators.trace/2"),
      `a top-level function's decorator must run in the module's stream: ${JSON.stringify(
        moduleCallKeys,
      )}`,
    );
    const topLevel = decoratorFunctions.find((f) => f.name === "topLevel");
    check(
      !((topLevel.instructions ?? []).some(
        (i) => i.kind === "CALL" && i.calleeSig && i.calleeSig.includes("trace"),
      )),
      "a top-level function's decorator must not run in its own body",
    );

    // The body of an immediately invoked function is CODE, and it has to be in
    // the TAC: as a function of its own, with the call naming it. Without
    // that, the code is not merely coarse -- it is absent, and nothing says
    // so.
    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: iifeFile,
        language: "TYPESCRIPT",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the IIFE file must translate: ${response.error ?? ""}`);

    const iifeFunctions = response.functions ?? [];
    const iifeOuter = iifeFunctions.find((f) => f.name === "runIife");
    check(!!iifeOuter, "the function that holds the IIFE must be in the stream");
    const iifeBody = iifeFunctions.find((f) => f.name !== "runIife");
    check(
      !!iifeBody,
      "the IIFE's body must be a function of its own: otherwise the code inside it is gone",
    );
    const bodyCalls = (iifeBody.instructions ?? [])
      .filter((i) => i.kind === "CALL")
      .map((i) => i.calleeSig);
    check(
      bodyCalls.some((signature) => signature && signature.startsWith("eval.eval/")),
      `the IIFE's body must keep its own code: ${JSON.stringify(bodyCalls)}`,
    );
    const outerCalls = (iifeOuter.instructions ?? [])
      .filter((i) => i.kind === "CALL")
      .map((i) => i.calleeSig);
    check(
      outerCalls.some((signature) => signature && signature.includes("closure$")),
      `the call must name the function the body was lifted into: ${JSON.stringify(outerCalls)}`,
    );
    check(
      (iifeOuter.drops ?? []).some((d) => d.construct === "iife-body"),
      "the capture the lifting cannot model must be recorded as a drop",
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
