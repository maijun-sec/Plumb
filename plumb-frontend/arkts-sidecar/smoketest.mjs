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

/**
 * A third probe program: a receiver with NO class to name.
 *
 * <p>A receiver whose type is `any` has no class the compiler can report, so
 * the owner of a call on it falls back to the file the call is written in.
 * That fallback is what a catalogue sees, so it is pinned here rather than
 * left to chance -- and it is a DIFFERENT fact from the one above, which is
 * that a receiver whose class the compiler CAN name is named by that class.
 */
const NO_CLASS_SOURCE = `
export function run(text: string): void {
  const holder: any = text;
  holder.method(text);
}
`;

/**
 * A fourth probe program: the shapes the lowering has to produce.
 *
 * <p>Every construct here is one the TAC has an opinion about, and the
 * assertions below say which shape it has to be. A shape that is not
 * asserted here is a shape nobody is checking.
 */
const SHAPES_SOURCE = `
import { parse } from '@ohos.util.json';

@Entry
@Component
struct ProfilePage {
  @State message: string = 'hello'
  readonly fixed: number = 1

  build() {
    Column() {
      Text(this.message)
    }
  }

  @Builder
  customBuilder() {
    Text(this.message)
  }
}

class Worker extends Base implements IDataSource {
  private count: number = 0;
  constructor(id: string) {
    super(id);
    this.count = 1;
  }
  get total(): number {
    return this.count;
  }
  set total(value: number) {
    this.count = value;
  }
  static {
    console.log("init");
  }
  @Concurrent
  run(input: string): void {
    parse(input);
  }
}

export function shapes(input: string | null, count: number, list: string[]): string {
  const and = input && input.length;
  const or = input || "none";
  const coalesce = input ?? "none";
  const conditional = input ? count : 0;
  const optional = input?.length;
  const isString = input instanceof String;
  let counter = count;
  counter &&= 1;
  const negated = !input;
  const incremented = ++counter;
  const voided = void 0;
  const comma = (1, 2);
  const pattern = /ab+c/gi;
  switch (count) {
    case 1:
      parse("one");
      break;
    default:
      parse("other");
  }
  try {
    parse("try");
  } catch (error) {
    parse("catch");
  } finally {
    parse("finally");
  }
  for (const item of list) {
    parse(item);
  }
  for (const key in list) {
    parse(key);
  }
  const ctx = abilityContext;
  return input ?? "";
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

/**
 * A probe program: an immediately invoked function, and a free global.
 *
 * <p>An IIFE calls a function literal, and the literal's body is CODE. If
 * the body is not in the TAC at all -- not as instructions, and not as a drop
 * either -- then the function around it reports full precision, the scan
 * reports success, and the code is simply gone.
 *
 * <p>The free global is the other half: a call whose receiver nobody could
 * type belongs to the global it came from, and the sink catalogue's patterns
 * are anchored at that owner. Without the owner the key is `eval/1`, and
 * nothing claims it -- a code injection sink that never fires.
 */
const IIFE_SOURCE = `
export function runIife(request: any): any {
  (() => {
    eval(request["cmd"]);
  })();
  return request;
}
`;

async function main() {
  const dir = mkdtempSync(join(tmpdir(), "plumb-arkts-smoke-"));
  const file = join(dir, "Page.ets");
  writeFileSync(file, SOURCE, "utf8");
  const moduleFile = join(dir, "Modules.ets");
  writeFileSync(moduleFile, MODULE_SOURCE, "utf8");
  const noClassFile = join(dir, "NoClass.ets");
  writeFileSync(noClassFile, NO_CLASS_SOURCE, "utf8");
  const shapesFile = join(dir, "Shapes.ets");
  writeFileSync(shapesFile, SHAPES_SOURCE, "utf8");
  const iifeFile = join(dir, "Iife.ets");
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
      { path: noClassFile },
      { path: shapesFile },
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

    // A method call names the class of its receiver. The class is what the
    // sink catalogue names, so a front end that fell back to the file's
    // module would produce TAC that matches nothing -- and a scan of a
    // vulnerable program that reports it clean. The sibling side car names
    // the same class for the same call, so one catalogue entry serves both
    // languages.
    const consoleCall = instructions.find(
      (i) =>
        i.kind === "CALL" && typeof i.calleeSig === "string" && i.calleeSig.includes(".log/"),
    );
    check(!!consoleCall, "the call to the console must be in the stream");
    check(
      consoleCall.calleeSig.startsWith("Console.log/"),
      `a method call must name the class of its receiver: ${consoleCall.calleeSig}`,
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

    // A receiver with NO class to name has NO owner either: the callee key
    // has no owner section at all.
    //
    // The file's own module used to stand in for it, and that was wrong: the
    // owner of the call is whatever the receiver is, and naming the file
    // claims a module the call never reached -- a file called "logging.ets"
    // would then be the logging module. The key says "the owner is missing",
    // which matches nothing rather than matching the wrong thing.
    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: noClassFile,
        language: "ARKTS",
      }),
    );
    response = JSON.parse(await readFrame(child.stdout));
    check(response.ok === true, `the file must translate: ${response.error ?? ""}`);

    const noClassRun = (response.functions ?? []).find((f) => f.name === "run");
    check(!!noClassRun, "the function whose receiver has no class must be in the stream");
    const noClassSignatures = (noClassRun.instructions ?? [])
      .filter((i) => i.kind === "CALL" || i.kind === "UNINTERPRETED")
      .map((i) => i.calleeSig);
    check(
      noClassSignatures.some((s) => s === "method/1"),
      `a receiver with no class name has no owner: ${JSON.stringify(noClassSignatures)}`,
    );
    check(
      !noClassSignatures.some((s) => s.startsWith("NoClass.")),
      `the file's own name must not stand in for the owner: ${JSON.stringify(noClassSignatures)}`,
    );

    // A binary operator is a BINOP, not an unknown value. A lookup that
    // always misses does not look like a lookup: every binary expression
    // would become a HAVOC, and the taint that flowed through it would stop
    // there without a word.
    const addition = instructions.find((i) => i.kind === "BINOP");
    check(!!addition, "a binary operator must be lowered into a BINOP");
    check(addition.binop === "ADD", "the concatenation must be the ADD operator");

    // ---- the shapes the lowering has to produce ------------------------------------------------

    writeFrame(
      child.stdin,
      JSON.stringify({
        op: OP.TRANSLATE,
        protocolVersion: PROTOCOL_VERSION,
        irVersion: IR_VERSION,
        path: shapesFile,
        language: "ARKTS",
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
    check(
      "domain" in shapes && (shapes.domain === null || typeof shapes.domain === "object"),
      `a function must carry the domain of its return type: ${JSON.stringify(shapes.domain)}`,
    );

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
    check(caseBranches.length >= 1, `a switch must dispatch on its cases: ${caseBranches.length}`);
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
      shapeInstructions.some(
        (i) =>
          i.kind === "ARRAYLOAD" &&
          i.lhs2 &&
          i.lhs2.kind === "CONST" &&
          i.lhs2.value === "value",
      ),
      "the element must come from the iterator's value",
    );
    check(
      itemMove && itemMove.lhs1 && itemMove.lhs1.kind === "VAR" && itemMove.lhs1.isTemp === true,
      "the loop variable must be bound to the element, not to the iterable",
    );

    // A `for ... in` binds the KEY, and it walks the keys with an index, so
    // that the loop ends.
    const keyMove = shapeInstructions.find(
      (i) => i.kind === "MOVE" && i.dest && i.dest.name === "key",
    );
    check(!!keyMove, "the key variable must be bound");
    check(
      shapeInstructions.some(
        (i) => i.kind === "BINOP" && i.binop === "LT" && i.lhs1 && i.lhs1.isTemp === true,
      ),
      "the key loop must compare its index with the number of keys",
    );

    // A type test is an INSTANCEOF.
    check(
      shapeInstructions.some((i) => i.kind === "INSTANCEOF"),
      "an instanceof must be a type test",
    );

    // The unary operators are unary operations with their own operators.
    const unops = shapeInstructions.filter((i) => i.kind === "UNARYOP").map((i) => i.unop);
    check(unops.includes("NOT"), `a negation must be a NOT: ${JSON.stringify(unops)}`);
    check(unops.includes("PRE_INC"), `a prefix increment must be a PRE_INC: ${JSON.stringify(unops)}`);

    // A regular expression is a constant of its own kind.
    check(
      shapeInstructions.some(
        (i) =>
          i.kind === "MOVE" && i.lhs1 && i.lhs1.kind === "CONST" && i.lhs1.constKind === "REGEX",
      ),
      "a regular expression must be a REGEX constant",
    );

    // The runtime context is injected at run time: the value is unknown, and
    // the loss is recorded.
    check(
      (shapes.drops ?? []).some((d) => d.construct === "runtime-context" && d.impact === "VALUE"),
      `the runtime context must be recorded as a drop: ${JSON.stringify(shapes.drops ?? [])}`,
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

    // ---- the classes ---------------------------------------------------------------------------

    // The struct's own initialiser holds the field initialisers, and it says
    // which decorators the struct carries.
    const initializer = shapeFunctions.find((f) => f.name === "ProfilePage");
    check(!!initializer, "the struct's own initialiser must be in the stream");
    check(
      initializer.memberKind === "FIELD_INITIALIZER",
      `the struct's initialiser must say so: ${initializer.memberKind}`,
    );
    check(
      (initializer.classAnnotations ?? []).includes("Entry") &&
        (initializer.classAnnotations ?? []).includes("Component"),
      `the struct's decorators must be annotations: ${JSON.stringify(initializer.classAnnotations)}`,
    );
    check(
      (initializer.fieldAnnotations ?? {}).message &&
        initializer.fieldAnnotations.message.includes("State"),
      `a state field must be annotated: ${JSON.stringify(initializer.fieldAnnotations)}`,
    );
    // A class member is an entry of the instance's NAMESPACE, so its
    // declaration is a keyed store -- the same mechanism every read of it
    // uses. It used to be a FIELDSTORE, and the two never met: the
    // declaration wrote one location and the read read another, so a value
    // written by a declaration never reached the place that reads it.
    const memberStore = (initializer.instructions ?? []).find(
      (i) =>
        i.kind === "ARRAYSTORE" &&
        i.lhs2 &&
        i.lhs2.kind === "CONST" &&
        i.lhs2.value === "message",
    );
    check(!!memberStore, "a class member must be stored as a namespace entry");
    check(
      memberStore.lhs1 && memberStore.lhs1.kind === "VAR" && memberStore.lhs1.name === "this",
      `a member must be stored on the instance: ${JSON.stringify(memberStore.lhs1)}`,
    );
    check(
      !(initializer.instructions ?? []).some((i) => i.kind === "FIELDSTORE" || i.kind === "FIELDLOAD"),
      "a member must not be stored as a field: in this language a property is a namespace entry",
    );

    // A built-in decorator never runs: there is no such function at run time,
    // so there is no call to lower.
    const builder = shapeFunctions.find((f) => f.name === "customBuilder");
    check(!!builder, "the builder method must be in the stream");
    check(
      (builder.methodAnnotations ?? []).includes("Builder"),
      `a builder method must be annotated: ${JSON.stringify(builder.methodAnnotations)}`,
    );
    check(
      !(builder.instructions ?? []).some(
        (i) => i.kind === "CALL" && typeof i.calleeSig === "string" && i.calleeSig.startsWith("Builder"),
      ),
      "a built-in decorator must not produce a call",
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
      constructor.superType === "Base",
      `a derived class must name its base: ${constructor.superType}`,
    );
    check(
      (constructor.interfaces ?? []).includes("IDataSource"),
      `the interfaces a class implements must be recorded: ${JSON.stringify(constructor.interfaces)}`,
    );
    check(
      (constructor.drops ?? []).some((d) => d.construct === "implements" && d.impact === "TYPE"),
      `the interfaces a class implements must be recorded as a drop: ${JSON.stringify(
        constructor.drops ?? [],
      )}`,
    );
    check(
      shapeFunctions.some((f) => f.name === "total" && f.memberKind === "GETTER"),
      "the getter must be in the stream",
    );
    check(
      shapeFunctions.some((f) => f.name === "total" && f.memberKind === "SETTER"),
      "the setter must be in the stream",
    );
    check(
      shapeFunctions.some((f) => f.memberKind === "STATIC_INITIALIZER"),
      "the static block must be in the stream",
    );

    // A concurrent method runs on another thread: the engine has to know that
    // its instructions are not in this thread's order, and the decorator that
    // says so never runs.
    const run = shapeFunctions.find((f) => f.name === "run");
    check(!!run, "the concurrent method must be in the stream");
    check(
      (run.methodAnnotations ?? []).includes("Concurrent"),
      `a concurrent method must be annotated: ${JSON.stringify(run.methodAnnotations)}`,
    );
    check(
      (run.methodAnnotations ?? []).filter((a) => a === "Concurrent").length === 1,
      `a concurrent method must be annotated once: ${JSON.stringify(run.methodAnnotations)}`,
    );
    check(
      (run.instructions ?? []).some((i) => i.effect === "CONCURRENT"),
      "a concurrent method must say that its instructions are not in order",
    );
    check(
      !(run.instructions ?? []).some(
        (i) => i.kind === "CALL" && typeof i.calleeSig === "string" && i.calleeSig.startsWith("Concurrent"),
      ),
      "a built-in decorator must not produce a call",
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
        language: "ARKTS",
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
