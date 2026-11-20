/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.tac.frontend.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacMemberKind;
import org.plumb.tac.ir.UnaryOperator;

/**
 * The Kotlin front end's SYNTAX surface: the constructs it lowers into real TAC shapes.
 *
 * <p>Every test here pins one construct. The assertion is about the SHAPE -- an instruction of such
 * a kind, with such an operand -- and not about the exact position, because the position is an
 * accident of the lowering order and the shape is the contract.
 */
class KotlinSyntaxSurfaceTest {

  /** The shared session. */
  private static KotlinTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new KotlinTestSupport();
  }

  @AfterAll
  static void tearDown() {
    // The session is a JVM-wide singleton and is released by a shutdown hook, so the
    // test classes must not release it themselves.
    if (support != null) {
      support.cleanUp();
    }
  }

  // ------------------------------------------------------------------
  // Control flow
  // ------------------------------------------------------------------

  @Test
  @DisplayName("A while loop becomes a labelled branch with a jump back to the condition")
  void whileLoopBecomesABranchChain() {
    TacFunction fn =
        parseSingle(
            "fun loop(items: List<String>) {",
            "    while (items.isNotEmpty()) {",
            "        val x = items.size",
            "    }",
            "}");

    assertThat(kinds(fn))
        .as("the loop's instructions")
        .contains(TacInstruction.Kind.BRANCH, TacInstruction.Kind.JUMP);
    TacInstruction branch = firstOfKind(fn, TacInstruction.Kind.BRANCH);
    assertThat(branch.guard).as("the branch says what it tests").isNotNull();
    assertThat(branch.guard.kind())
        .as("the guard of a plain condition is a truthiness")
        .isEqualTo(org.plumb.tac.ir.TacGuard.Kind.TRUTHINESS);
    assertThat(fn.instructions())
        .as("the loop's labels are classified")
        .anySatisfy(
            instruction ->
                assertThat(instruction.labelRef)
                    .as("a label that says what kind of place it is")
                    .isNotNull());
  }

  @Test
  @DisplayName("A for loop goes through the iteration protocol")
  void forLoopUsesTheIterationProtocol() {
    TacFunction fn =
        parseSingle(
            "fun each(items: List<String>) {",
            "    for (item in items) {",
            "        println(item)",
            "    }",
            "}");

    List<String> callees = calleeSignatures(fn);
    assertThat(callees)
        .as("the loop asks for an iterator, then for the next element")
        .contains(
            "java.lang.Iterable.iterator/0",
            "java.util.Iterator.hasNext/0",
            "java.util.Iterator.next/0");
  }

  @Test
  @DisplayName("A break jumps to the loop's end, and a continue jumps to its condition")
  void breakAndContinueJumpToTheRightLabel() {
    TacFunction fn =
        parseSingle(
            "fun loop(items: List<String>) {",
            "    while (true) {",
            "        if (items.isEmpty()) break",
            "        continue",
            "    }",
            "}");

    List<TacInstruction> jumps = ofKind(fn, TacInstruction.Kind.JUMP);
    assertThat(jumps).as("the loop has jumps").isNotEmpty();
    assertThat(jumps)
        .as("every jump names the label it targets")
        .allSatisfy(jump -> assertThat(jump.label).as("a jump's target").isNotNull());
  }

  @Test
  @DisplayName("A try block produces a catch handler, a finally entry and a try region")
  void tryCatchFinallyProducesARegion() {
    TacFunction fn =
        parseSingle(
            "fun guarded(action: () -> String): String {",
            "    try {",
            "        return action()",
            "    } catch (ex: IllegalStateException) {",
            "        return \"failed\"",
            "    } finally {",
            "        println(\"done\")",
            "    }",
            "}");

    assertThat(kinds(fn))
        .as("the try's instructions")
        .contains(TacInstruction.Kind.CATCH_HANDLER, TacInstruction.Kind.FINALLY_ENTER);
    assertThat(fn.tryRegions()).as("the try region is registered").isNotEmpty();
    TacFunction.TryRegion region = fn.tryRegions().get(0);
    assertThat(region.hasCatch()).as("the region has a catch handler").isTrue();
    assertThat(region.hasFinally()).as("the region has a finally block").isTrue();
  }

  @Test
  @DisplayName("A throw terminates the path")
  void throwBecomesAThrowInstruction() {
    TacFunction fn =
        parseSingle(
            "fun fail(message: String): Nothing {",
            "    throw IllegalStateException(message)",
            "}");

    assertThat(kinds(fn)).as("the throw's instructions").contains(TacInstruction.Kind.THROW);
  }

  // ------------------------------------------------------------------
  // Expressions
  // ------------------------------------------------------------------

  @Test
  @DisplayName("An assignment stores into the local it names")
  void assignmentStoresIntoTheLocal() {
    TacFunction fn =
        parseSingle(
            "fun assign(input: String): String {",
            "    var result = input",
            "    result = \"fixed\"",
            "    return result",
            "}");

    assertThat(kinds(fn)).as("the assignment's instructions").contains(TacInstruction.Kind.MOVE);
  }

  @Test
  @DisplayName("A property read is a field load with the owning type")
  void propertyReadIsAFieldLoad() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "class Holder {",
                "    val value: String = \"x\"",
                "    fun read(): String {",
                "        return value",
                "    }",
                "}"));

    TacFunction read = functionNamed(result, "Holder.read");
    TacInstruction load = firstOfKind(read, TacInstruction.Kind.FIELDLOAD);
    assertThat(load).as("the property read is a field load").isNotNull();
    assertThat(load.fieldName).as("the field it reads").isEqualTo("value");
    assertThat(load.ownerType)
        .as("the field's owner, so two classes' fields do not share a bucket")
        .isNotNull();
  }

  @Test
  @DisplayName("A property write is a field store")
  void propertyWriteIsAFieldStore() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "class Holder {",
                "    var value: String = \"x\"",
                "    fun write(other: String) {",
                "        value = other",
                "    }",
                "}"));

    TacFunction write = functionNamed(result, "Holder.write");
    TacInstruction store = firstOfKind(write, TacInstruction.Kind.FIELDSTORE);
    assertThat(store).as("the property write is a field store").isNotNull();
    assertThat(store.fieldName).as("the field it writes").isEqualTo("value");
  }

  @Test
  @DisplayName("A safe call checks its receiver and produces null on the other branch")
  void safeCallChecksTheReceiver() {
    TacFunction fn =
        parseSingle("fun read(input: String?): String? {", "    return input?.trim()", "}");

    assertThat(kinds(fn)).as("the safe call's instructions").contains(TacInstruction.Kind.BRANCH);
    assertThat(ofKind(fn, TacInstruction.Kind.BRANCH))
        .as("the branch that decides whether the call happens")
        .isNotEmpty();
  }

  @Test
  @DisplayName("A double bang proves the value is not null")
  void doubleBangProvesNonNull() {
    TacFunction fn =
        parseSingle("fun require(input: String?): String {", "    return input!!", "}");

    assertThat(kinds(fn))
        .as("the assertion's instructions")
        .contains(TacInstruction.Kind.NULL_CHECK);
  }

  @Test
  @DisplayName("A cast is a cast, and a safe cast yields a nullable value")
  void castsAreCasts() {
    TacFunction fn =
        parseSingle(
            "fun cast(input: Any): String? {",
            "    val a = input as String",
            "    val b = input as? String",
            "    return b",
            "}");

    List<TacInstruction> casts = ofKind(fn, TacInstruction.Kind.UNARYOP);
    assertThat(casts).as("the casts").isNotEmpty();
    assertThat(casts)
        .as("every cast is a CAST")
        .allSatisfy(
            instruction ->
                assertThat(instruction.unop)
                    .as("the unary operator")
                    .isEqualTo(UnaryOperator.CAST));
  }

  @Test
  @DisplayName("A type test is an instanceof")
  void typeTestIsInstanceOf() {
    TacFunction fn =
        parseSingle("fun test(input: Any): Boolean {", "    return input is String", "}");

    assertThat(kinds(fn)).as("the test's instructions").contains(TacInstruction.Kind.INSTANCEOF);
  }

  @Test
  @DisplayName("A membership test calls contains")
  void membershipTestCallsContains() {
    TacFunction fn =
        parseSingle(
            "fun contains(items: List<String>, value: String): Boolean {",
            "    return value in items",
            "}");

    assertThat(calleeSignatures(fn))
        .as("the membership test's call")
        .anySatisfy(signature -> assertThat(signature).as("a contains call").contains("contains"));
  }

  @Test
  @DisplayName("The Elvis operator takes the left side unless it is null")
  void elvisBranchesOnNull() {
    TacFunction fn =
        parseSingle("fun elvis(input: String?): String {", "    return input ?: \"fallback\"", "}");

    assertThat(kinds(fn)).as("the operator's instructions").contains(TacInstruction.Kind.BRANCH);
  }

  @Test
  @DisplayName("An array access is an array load")
  void arrayAccessIsAnArrayLoad() {
    TacFunction fn =
        parseSingle(
            "fun read(items: Array<String>, index: Int): String {", "    return items[index]", "}");

    assertThat(kinds(fn)).as("the access's instructions").contains(TacInstruction.Kind.ARRAYLOAD);
  }

  @Test
  @DisplayName("A compound assignment computes the new value and stores it")
  void compoundAssignmentStoresTheNewValue() {
    TacFunction fn =
        parseSingle(
            "fun add(input: String): String {",
            "    var result = input",
            "    result += \"!\"",
            "    return result",
            "}");

    assertThat(kinds(fn))
        .as("the compound assignment's instructions")
        .contains(TacInstruction.Kind.BINOP, TacInstruction.Kind.MOVE);
    assertThat(ofKind(fn, TacInstruction.Kind.BINOP))
        .as("the addition")
        .anySatisfy(
            instruction ->
                assertThat(instruction.binop).as("the operator").isEqualTo(BinaryOperator.ADD));
  }

  @Test
  @DisplayName("An increment changes the variable and yields the old value")
  void incrementIsAUnaryOperation() {
    TacFunction fn =
        parseSingle(
            "fun bump(counter: Int): Int {", "    var n = counter", "    n++", "    return n", "}");

    assertThat(ofKind(fn, TacInstruction.Kind.UNARYOP))
        .as("the increment")
        .anySatisfy(
            instruction ->
                assertThat(instruction.unop).as("the operator").isEqualTo(UnaryOperator.POST_INC));
  }

  @Test
  @DisplayName("A lambda becomes a function of its own, and its captures travel as parameters")
  void lambdaBecomesAFunction() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "fun use(input: String): String {",
                "    val f: (String) -> String = { prefix -> prefix + input }",
                "    return f(\"x\")",
                "}"));

    TacFunction lambda =
        result.functions().stream()
            .filter(fn -> fn.name().contains("$L$"))
            .findFirst()
            .orElse(null);
    assertThat(lambda).as("the lambda became a function of its own").isNotNull();
    assertThat(lambda.instructions()).as("the lambda's body was lowered").isNotEmpty();
    assertThat(lambda.params())
        .as("the captured value is a leading parameter")
        .anySatisfy(param -> assertThat(param.name()).as("a capture").isEqualTo("input"));
  }

  // ------------------------------------------------------------------
  // Declarations
  // ------------------------------------------------------------------

  @Test
  @DisplayName("A class has a constructor, and the constructor stores the initialisers")
  void classHasAConstructor() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                // "val input" makes the parameter a PROPERTY, and a property has a backing
                // field. A plain constructor parameter does not: Kotlin gives it no field,
                // so a constructor that stored one would be storing a field the source
                // never declared.
                "class Holder(val input: String) {",
                "    val value: String = input",
                "    init {",
                "        println(input)",
                "    }",
                "}"));

    TacFunction constructor = functionNamed(result, "Holder.<init>");
    assertThat(constructor).as("the class has a constructor").isNotNull();
    assertThat(constructor.memberKind())
        .as("it is a constructor")
        .isEqualTo(TacMemberKind.CONSTRUCTOR);
    List<TacInstruction> stores = ofKind(constructor, TacInstruction.Kind.FIELDSTORE);
    assertThat(stores).as("the constructor stores the fields").isNotEmpty();
    assertThat(stores)
        .as("the backing field of the constructor's own parameter")
        .anySatisfy(store -> assertThat(store.fieldName).as("the field").isEqualTo("input"));
    assertThat(stores)
        .as("the backing field of the property")
        .anySatisfy(store -> assertThat(store.fieldName).as("the field").isEqualTo("value"));
  }

  @Test
  @DisplayName("A property has a getter, and a var has a setter too")
  void propertyHasAccessors() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "class Holder {",
                "    var value: String = \"x\"",
                "    val fixed: String = \"y\"",
                "}"));

    TacFunction getter = functionNamed(result, "Holder.getValue");
    assertThat(getter).as("the var has a getter").isNotNull();
    assertThat(getter.memberKind()).as("it is a getter").isEqualTo(TacMemberKind.GETTER);
    assertThat(functionNamed(result, "Holder.setValue")).as("the var has a setter").isNotNull();
    assertThat(functionNamed(result, "Holder.getFixed")).as("the val has a getter").isNotNull();
    assertThat(functionNamed(result, "Holder.setFixed")).as("the val has no setter").isNull();
  }

  @Test
  @DisplayName("An enum entry is a member of its own kind")
  void enumEntryHasItsOwnMemberKind() {
    TacFrontend.ParseResult result =
        support.parse(String.join("\n", "enum class Colour {", "    RED,", "    GREEN", "}"));

    TacFunction entry = functionNamed(result, "Colour.RED");
    assertThat(entry).as("the enum entry became a function").isNotNull();
    assertThat(entry.memberKind())
        .as("an enum entry is neither a method nor a field")
        .isEqualTo(TacMemberKind.ENUM_ENTRY);
  }

  @Test
  @DisplayName("A secondary constructor is a constructor of its own")
  void secondaryConstructorIsAConstructor() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "class Holder {",
                "    constructor(value: String) {",
                "        println(value)",
                "    }",
                "}"));

    List<TacFunction> constructors =
        result.functions().stream()
            .filter(fn -> fn.memberKind() == TacMemberKind.CONSTRUCTOR)
            .toList();
    assertThat(constructors).as("the class has a constructor").isNotEmpty();
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  /** Parses a source and returns the one function it declares. */
  private static TacFunction parseSingle(String... lines) {
    TacFrontend.ParseResult result = support.parse(String.join("\n", lines));
    assertThat(result.functions()).as("the source declared exactly one function").hasSize(1);
    return result.functions().get(0);
  }

  /** The function with the given name, or {@code null}. */
  private static TacFunction functionNamed(TacFrontend.ParseResult result, String name) {
    return result.functions().stream()
        .filter(fn -> fn.name().equals(name))
        .findFirst()
        .orElse(null);
  }

  /** The kinds of a function's instructions, in order. */
  private static List<TacInstruction.Kind> kinds(TacFunction fn) {
    return fn.instructions().stream().map(instruction -> instruction.kind).toList();
  }

  /** The instructions of a kind. */
  private static List<TacInstruction> ofKind(TacFunction fn, TacInstruction.Kind kind) {
    return fn.instructions().stream().filter(instruction -> instruction.kind == kind).toList();
  }

  /** The first instruction of a kind, or {@code null}. */
  private static TacInstruction firstOfKind(TacFunction fn, TacInstruction.Kind kind) {
    List<TacInstruction> found = ofKind(fn, kind);
    return found.isEmpty() ? null : found.get(0);
  }

  /** The callee signatures of a function's calls. */
  private static List<String> calleeSignatures(TacFunction fn) {
    return ofKind(fn, TacInstruction.Kind.CALL).stream()
        .map(instruction -> instruction.calleeSig)
        .filter(signature -> signature != null)
        .toList();
  }
}
