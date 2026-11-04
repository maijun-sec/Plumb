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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * The Kotlin frontend's contract: what it produces, what it refuses to produce, and what
 * it does with a broken file.
 */
class KotlinTacFrontendTest {

  /** The shared session. */
  private static KotlinTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new KotlinTestSupport();
  }

  @AfterAll
  static void tearDown() {
    // The session is a JVM-wide singleton and is released by a shutdown hook, so the
    // test classes must not release it themselves: whichever class ran last would
    // break the classes that ran after it.
    if (support != null) {
      support.cleanUp();
    }
  }

  // ------------------------------------------------------------------
  // The SPI contract
  // ------------------------------------------------------------------

  @Test
  void frontendReportsKotlinAndItsExtensions() {
    KotlinTacFrontend frontend = support.frontend();
    assertThat(frontend.language()).isEqualTo(SourceLanguage.KOTLIN);
    assertThat(frontend.fileExtensions()).containsExactlyInAnyOrder("kt", "kts");
    assertThat(frontend.defaultPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  void parsingAMinimalFileProducesAFunctionWithTheKotlinLanguage() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun greet(name: String): String {",
                "    return \"hi \" + name",
                "}"));

    assertThat(result.problems()).as("problems: %s", result.problems()).isEmpty();
    assertThat(result.functions()).isNotEmpty();
    TacFunction fn = result.functions().get(0);
    assertThat(fn.sourceLanguage()).isEqualTo(SourceLanguage.KOTLIN);
    assertThat(fn.name()).isEqualTo("greet");
    assertThat(fn.instructions()).isNotEmpty();
  }

  @Test
  void parsingAFileWithNoDeclarationsProducesNoFunctionsAndNoCrash() {
    TacFrontend.ParseResult result = support.parse("package demo\n\n// nothing here\n");
    assertThat(result.functions()).isEmpty();
  }

  // ------------------------------------------------------------------
  // The TAC shape
  // ------------------------------------------------------------------

  @Test
  void aSimpleFunctionLowersToTheExpectedInstructionKinds() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun add(a: Int, b: Int): Int {",
                "    val sum = a + b",
                "    return sum",
                "}"));

    TacFunction fn = result.functions().get(0);
    assertThat(fn.name()).isEqualTo("add");
    assertThat(fn.params()).hasSize(2);

    List<TacInstruction.Kind> kinds =
        fn.instructions().stream().map(i -> i.kind).toList();

    // The addition is a BINOP, the copy into the local is a MOVE, and the function
    // returns with a RETURN.
    assertThat(kinds).contains(TacInstruction.Kind.BINOP);
    assertThat(kinds).contains(TacInstruction.Kind.MOVE);
    assertThat(kinds).contains(TacInstruction.Kind.RETURN);

    // The BINOP must be the addition of the two parameters.
    TacInstruction binop =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.BINOP)
            .findFirst()
            .orElseThrow();
    assertThat(binop.binop).isEqualTo(org.plumb.tac.ir.BinaryOperator.ADD);
    assertThat(binop.lhs1).isEqualTo(new TacOperand.TacVar("a", TacType.INT, false));
    assertThat(binop.lhs2).isEqualTo(new TacOperand.TacVar("b", TacType.INT, false));
  }

  @Test
  void anIfBecomesABranch() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun pick(flag: Boolean, x: Int): Int {",
                "    if (flag) {",
                "        return x",
                "    }",
                "    return 0",
                "}"));

    TacFunction fn = result.functions().get(0);
    List<TacInstruction.Kind> kinds =
        fn.instructions().stream().map(i -> i.kind).toList();
    assertThat(kinds).contains(TacInstruction.Kind.BRANCH);
    assertThat(kinds).contains(TacInstruction.Kind.LABEL);
    assertThat(kinds).contains(TacInstruction.Kind.RETURN);
  }

  @Test
  void aCallBecomesACallInstruction() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Service {",
                "    fun handle(): String = \"done\"",
                "}",
                "",
                "fun useIt(s: Service): String {",
                "    return s.handle()",
                "}"));

    TacFunction fn =
        result.functions().stream()
            .filter(f -> "useIt".equals(f.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("useIt was not lowered: " + result.functions()));

    assertThat(fn.instructions().stream().map(i -> i.kind))
        .contains(TacInstruction.Kind.CALL);
  }

  /**
   * A qualified call carries its receiver in {@code args[0]}.
   *
   * <p>The TAC's {@code CALL} instruction has no separate receiver slot, so the receiver
   * goes in front of the arguments -- the convention the Java frontend and the bytecode
   * frontend both follow. The DFA's null-dereference checker reads the receiver from
   * {@code args[0]}: without it, a method call on a possibly-null receiver is not
   * reported at all.
   */
  @Test
  void aQualifiedCallPutsItsReceiverFirstInTheArguments() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Service {",
                "    fun handle(value: String): String = value",
                "}",
                "",
                "fun useIt(s: Service): String {",
                "    return s.handle(\"x\")",
                "}"));

    TacFunction fn =
        result.functions().stream()
            .filter(f -> "useIt".equals(f.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("useIt was not lowered: " + result.functions()));

    TacInstruction call =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.CALL)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CALL was emitted: " + fn.instructions()));

    assertThat(call.args).as("the receiver must be the first argument").isNotEmpty();
    assertThat(call.args.get(0))
        .as("the receiver must be the `s` parameter")
        .isEqualTo(new TacOperand.TacVar("s", TacType.UNKNOWN, false));
  }

  /**
   * A {@code when} with a subject compares the subject against each entry's condition.
   *
   * <p>The entry's <i>condition</i> is the value compared ({@code when (x) { 1 -> ... }}),
   * and the entry's <i>expression</i> is the branch body after the arrow. Using the body
   * as the comparison value would make the branch compare the subject against itself.
   */
  @Test
  void aWhenWithASubjectComparesAgainstTheEntriesCondition() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun pick(x: Int): Int {",
                "    when (x) {",
                "        1 -> return 10",
                "        2 -> return 20",
                "        else -> return 0",
                "    }",
                "}"));

    TacFunction fn = result.functions().get(0);

    // The comparison values are the entry's conditions: the constants 1 and 2, never the
    // branch bodies (10 and 20).
    List<TacOperand> compared =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.BINOP)
            .map(i -> i.lhs2)
            .toList();

    assertThat(compared).isNotEmpty();
    assertThat(compared)
        .as("the `when` must compare against its conditions, not its branch bodies")
        .allSatisfy(
            operand ->
                assertThat(operand)
                    .as("operand %s must be a constant", operand)
                    .isInstanceOf(TacOperand.Const.class));
    assertThat(compared)
        .extracting(o -> ((TacOperand.Const) o).value())
        .containsExactlyInAnyOrder(1, 2);
  }

  // ------------------------------------------------------------------
  // The `when` VALUE -- it used to be dropped
  // ------------------------------------------------------------------

  /**
   * A {@code when} HAS a value, and the lowering has to produce it.
   *
   * <p><b>Why this test exists.</b> The translator lowered each arm's body as a STATEMENT, so
   * the value was computed and thrown away, and the {@code when} produced nothing. A caller that
   * did {@code val x = when (y) { ... }} got an undefined {@code x}, and every taint that flowed
   * through the {@code when} was lost -- silently, with the function still claiming full
   * precision.
   */
  @Test
  void aWhenProducesItsValue() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun pick(x: Int): String {",
                "    val v = when (x) {",
                "        1 -> \"one\"",
                "        else -> \"other\"",
                "    }",
                "    return v",
                "}"));

    TacFunction fn = result.functions().get(0);

    // The arms' values are moved into a result, and the result is what the property
    // reads. Without the move, the string constants would be computed and dropped.
    List<Object> moved =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.MOVE)
            .map(i -> i.lhs1)
            .filter(o -> o instanceof TacOperand.Const)
            .map(o -> ((TacOperand.Const) o).value())
            .toList();

    assertThat(moved)
        .as("the arms' values must be moved somewhere, not computed and dropped")
        .contains("one", "other");
  }

  /**
   * An {@code is}-branch that cannot be lowered is REPORTED and degrades the function.
   *
   * <p><b>Why this test exists.</b> The {@code is} branch was skipped with a bare {@code
   * continue}, so the gap was SILENT: the function's precision stayed {@code SOURCE_FULL} and a
   * checker trusted a function whose branch was simply gone.
   */
  @Test
  void anIsBranchThatCannotBeLoweredIsReported() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun pick(x: Any): Int {",
                "    when (x) {",
                "        is String -> 1",
                "        else -> 0",
                "    }",
                "    return 0",
                "}"));

    TacFunction fn = result.functions().get(0);

    assertThat(fn.irPrecision())
        .as("a function with an unlowered branch must not claim full precision")
        .isEqualTo(IrPrecision.SOURCE_LOWERED);
    assertThat(result.problems())
        .as("and the gap must be reported, not just flagged")
        .isNotEmpty();
    assertThat(String.join("\n", result.problems()))
        .as("the message says what could not be lowered")
        .contains("is");
  }

  // ------------------------------------------------------------------
  // Nullability -- the Kotlin frontend's main contribution
  // ------------------------------------------------------------------

  @Test
  void aNonNullParameterMapsToANonNullableType() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun takesName(name: String) {",
                "}"));

    TacFunction fn = result.functions().get(0);
    TacOperand.TacVar param = fn.params().get(0);
    assertThat(param.name()).isEqualTo("name");
    assertThat(param.type().isNullable()).isFalse();
    assertThat(param.type().displayName()).isEqualTo("java.lang.String");
  }

  @Test
  void aNullableParameterMapsToANullableType() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun takesName(name: String?) {",
                "}"));

    TacFunction fn = result.functions().get(0);
    TacOperand.TacVar param = fn.params().get(0);
    assertThat(param.name()).isEqualTo("name");
    assertThat(param.type().isNullable()).isTrue();
  }

  @Test
  void aNonNullReturnTypeMapsToANonNullableType() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun makeName(): String {",
                "    return \"x\"",
                "}"));

    TacFunction fn = result.functions().get(0);
    assertThat(fn.returnType().isNullable()).isFalse();
  }

  @Test
  void aNullableReturnTypeMapsToANullableType() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun makeName(): String? {",
                "    return null",
                "}"));

    TacFunction fn = result.functions().get(0);
    assertThat(fn.returnType().isNullable()).isTrue();
  }

  @Test
  void aNonNullLocalIsDeclaredNonNullable() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun makeName(): Int {",
                "    val local: String = \"x\"",
                "    return local.length",
                "}"));

    TacFunction fn = result.functions().get(0);
    TacOperand.TacVar local =
        fn.locals().stream()
            .filter(v -> "local".equals(v.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("local was not declared: " + fn.locals()));
    assertThat(local.type().isNullable()).isFalse();
  }

  // ------------------------------------------------------------------
  // NULL_CHECK
  // ------------------------------------------------------------------

  @Test
  void requireNotNullEmitsANullCheck() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun unwrap(value: String?): String {",
                "    val checked = requireNotNull(value)",
                "    return checked",
                "}"));

    TacFunction fn = result.functions().get(0);
    List<TacInstruction> checks =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.NULL_CHECK)
            .toList();
    assertThat(checks).isNotEmpty();
    // The DFA reads the checked variable off `dest`, so `dest` must be the variable.
    assertThat(checks.get(0).dest).isInstanceOf(TacOperand.TacVar.class);
  }

  @Test
  void checkNotNullEmitsANullCheck() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun unwrap(value: String?): String {",
                "    val checked = checkNotNull(value)",
                "    return checked",
                "}"));

    TacFunction fn = result.functions().get(0);
    assertThat(fn.instructions().stream().map(i -> i.kind))
        .contains(TacInstruction.Kind.NULL_CHECK);
  }

  // ------------------------------------------------------------------
  // Synthetic detection
  // ------------------------------------------------------------------

  @Test
  void aDataClasssGeneratedMembersAreMarkedSynthetic() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "data class Point(val x: Int, val y: String)"));

    List<TacFunction> functions = result.functions();
    assertThat(functions).isNotEmpty();

    // The compiler generates `component1`, `component2` and `copy` for a data class.
    // They must come back marked synthetic, and the hand-written members must not be.
    List<TacFunction> generated =
        functions.stream().filter(TacFunction::isSynthetic).toList();
    assertThat(generated)
        .as("the data class's generated members must be marked synthetic; got %s", functions)
        .isNotEmpty();

    for (TacFunction fn : generated) {
      String name = fn.name();
      assertThat(name).satisfiesAnyOf(
          n -> assertThat(n).endsWith("copy"),
          n -> assertThat(n).contains("component"));
    }
  }

  @Test
  void aHandWrittenFunctionIsNotMarkedSynthetic() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Service {",
                "    fun handle(): String = \"done\"",
                "}"));

    TacFunction fn = result.functions().get(0);
    assertThat(fn.isSynthetic()).isFalse();
  }

  // ------------------------------------------------------------------
  // Totality
  // ------------------------------------------------------------------

  @Test
  void aMalformedFileReturnsProblemsInsteadOfThrowing() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun broken(: String {",
                "    this is not kotlin at all (((",
                "}"));

    // The parse must not throw. A malformed file may yield functions, may yield none,
    // but it must not escape.
    assertThat(result).isNotNull();
    assertThat(result.problems()).isNotNull();
  }

  @Test
  void aMalformedFileIsReportedAsAProblem() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun broken(: String {",
                "    this is not kotlin at all (((",
                "}"));

    // The frontend reports the construct it could not lower, by name and by line, so
    // the report says what went wrong and where.
    assertThat(result.problems()).isNotEmpty();
    assertThat(result.problems().toString())
        .as("the problem must name the construct and its line")
        .contains("unsupported statement")
        .contains("broken")
        .contains("line 4");
  }

  @Test
  void aMalformedFileStillYieldsThePartItCouldParse() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun broken(: String {",
                "    this is not kotlin at all (((",
                "}"));

    // Partial results are the normal case: the function's signature was resolvable, so
    // the function is produced even though its body could not be lowered.
    assertThat(result.functions()).isNotEmpty();
    assertThat(result.functions().get(0).name()).isEqualTo("broken");
  }

  @Test
  void aFunctionThatCouldNotBeFullyLoweredIsMarkedSourceLowered() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun broken(: String {",
                "    this is not kotlin at all (((",
                "}"));

    // A checker consults the precision before trusting a precision-dependent fact
    // about the function, so a function that lost information must say so.
    assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_LOWERED);
    assertThat(result.functions().get(0).irPrecision()).isEqualTo(IrPrecision.SOURCE_LOWERED);
  }

  @Test
  void aFullyLoweredFunctionIsMarkedSourceFull() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun clean(name: String): String {",
                "    return name",
                "}"));

    assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_FULL);
    assertThat(result.functions().get(0).irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  void aNullSourceIsHandledWithoutThrowing() {
    assertThatCode(() -> support.frontend().parse(null, null, support.context()))
        .doesNotThrowAnyException();
  }

  @Test
  void aFileWithAnUnresolvableReferenceDoesNotThrow() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun callUnknown() {",
                "    somethingThatDoesNotExist()",
                "}"));

    assertThat(result).isNotNull();
  }
}
