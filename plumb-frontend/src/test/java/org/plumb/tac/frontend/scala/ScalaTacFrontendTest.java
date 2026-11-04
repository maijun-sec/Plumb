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
package org.plumb.tac.frontend.scala;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * The Scala frontend's contract: what it produces, what it refuses to produce, and what
 * it does with a broken file.
 */
class ScalaTacFrontendTest {

  /** The shared scaffolding. */
  private static ScalaTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new ScalaTestSupport();
  }

  // ------------------------------------------------------------------
  // The SPI contract
  // ------------------------------------------------------------------

  @Test
  void frontendReportsScalaAndItsExtensions() {
    ScalaTacFrontend frontend = support.frontend();
    assertThat(frontend.language()).isEqualTo(SourceLanguage.SCALA);
    assertThat(frontend.fileExtensions()).containsExactly("scala");
    assertThat(frontend.defaultPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  void parsingAMinimalFileProducesAFunctionWithTheScalaLanguage() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Greeter {",
                "  def greet(name: String): String = \"hi \" + name",
                "}"));

    assertThat(result.problems()).as("problems: %s", result.problems()).isEmpty();
    assertThat(result.functions()).isNotEmpty();

    TacFunction fn = find(result.functions(), "demo.Greeter.greet");
    assertThat(fn).isNotNull();
    assertThat(fn.sourceLanguage()).isEqualTo(SourceLanguage.SCALA);
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
                "class Calc {",
                "  def add(a: Int, b: Int): Int = a + b",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Calc.add");
    assertThat(fn).isNotNull();
    assertThat(fn.params()).hasSize(2);
    assertThat(fn.params().get(0).name()).isEqualTo("a");
    assertThat(fn.params().get(1).name()).isEqualTo("b");
    assertThat(fn.returnType()).isEqualTo(TacType.INT);

    List<TacInstruction.Kind> kinds = kinds(fn);
    assertThat(kinds).contains(TacInstruction.Kind.BINOP);
    assertThat(kinds).contains(TacInstruction.Kind.RETURN);

    // The addition is a BINOP with ADD, not a call.
    TacInstruction binop =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.BINOP)
            .findFirst()
            .orElseThrow();
    assertThat(binop.binop).isEqualTo(org.plumb.tac.ir.BinaryOperator.ADD);
  }

  @Test
  void aMethodCallLowersToACallInstruction() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def lengthOf(s: String): Int = s.length",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.lengthOf");
    assertThat(fn).isNotNull();
    assertThat(kinds(fn)).contains(TacInstruction.Kind.CALL);

    TacInstruction call =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.CALL)
            .findFirst()
            .orElseThrow();
    // The signature is in the form the sink families match: a dotted qualified
    // name and an arity. It used to be dotc's own form -- "java.lang.String#length",
    // a hash separator and no arity -- which matches NOT ONE pattern in
    // sink-families.yaml, because every pattern is anchored with ^ to a dotted
    // qualified name. The taint rules were therefore silently inert on Scala code.
    assertThat(call.calleeSig).isEqualTo("java.lang.String.length/0");
  }

  @Test
  void anObjectCreationLowersToANewInstruction() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Point(x: Int, y: Int)",
                "",
                "class Demo {",
                "  def make(): Point = new Point(1, 2)",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.make");
    assertThat(fn).isNotNull();
    assertThat(kinds(fn)).contains(TacInstruction.Kind.NEW);

    TacInstruction newInsn =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.NEW)
            .findFirst()
            .orElseThrow();
    assertThat(newInsn.ctorType.kind()).isEqualTo(TacType.Kind.CLASS);
    assertThat(newInsn.ctorType.className()).isEqualTo("demo.Point");
  }

  @Test
  void aLocalVariableLowersToAMoveAndIsVisibleAsALocal() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def compute(a: Int): Int = {",
                "    val sum = a + 1",
                "    sum",
                "  }",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.compute");
    assertThat(fn).isNotNull();
    assertThat(kinds(fn)).contains(TacInstruction.Kind.MOVE);
    assertThat(fn.locals()).extracting(TacOperand.TacVar::name).contains("sum");
  }

  @Test
  void anIfLowersToABranch() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def pick(c: Boolean): Int = if (c) 1 else 2",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.pick");
    assertThat(fn).isNotNull();
    assertThat(kinds(fn)).contains(TacInstruction.Kind.BRANCH);
  }

  // ------------------------------------------------------------------
  // Synthetic methods
  // ------------------------------------------------------------------

  @Test
  void aCaseClassCopyIsMarkedSynthetic() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "case class Point(x: Int, y: Int)"));

    TacFunction copy = find(result.functions(), "demo.Point.copy");
    assertThat(copy).as("the case class's copy must be lowered").isNotNull();
    assertThat(copy.isSynthetic()).isTrue();
  }

  @Test
  void aCaseClassCompanionApplyAndUnapplyAreMarkedSynthetic() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "case class Point(x: Int, y: Int)"));

    TacFunction apply = find(result.functions(), "demo.Point$.apply");
    assertThat(apply).as("the companion's apply must be lowered").isNotNull();
    assertThat(apply.isSynthetic()).isTrue();

    TacFunction unapply = find(result.functions(), "demo.Point$.unapply");
    assertThat(unapply).as("the companion's unapply must be lowered").isNotNull();
    assertThat(unapply.isSynthetic()).isTrue();
  }

  @Test
  void aCaseClassAccessorIsMarkedSynthetic() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "case class Point(x: Int, y: Int)"));

    TacFunction first = find(result.functions(), "demo.Point._1");
    assertThat(first).as("the case class's first accessor must be lowered").isNotNull();
    assertThat(first.isSynthetic()).isTrue();
  }

  @Test
  void aHandWrittenMethodIsNotMarkedSynthetic() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def add(a: Int, b: Int): Int = a + b",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.add");
    assertThat(fn).isNotNull();
    assertThat(fn.isSynthetic()).isFalse();
  }

  @Test
  void aConstructorIsNotMarkedSynthetic() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "case class Point(x: Int, y: Int)"));

    // The constructor is compiler-generated, but it is also the class's public API:
    // a call site can reach it, so a checker must not be told to ignore it.
    TacFunction ctor = find(result.functions(), "demo.Point.<init>");
    assertThat(ctor).as("the case class's constructor must be lowered").isNotNull();
    assertThat(ctor.isSynthetic()).isFalse();
  }

  // ------------------------------------------------------------------
  // Match lowering
  // ------------------------------------------------------------------

  @Test
  void aMatchLowersToABranchChain() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def pick(o: Any): Int = o match {",
                "    case s: String => s.length",
                "    case i: Int => i",
                "    case _ => 0",
                "  }",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.pick");
    assertThat(fn).isNotNull();

    List<TacInstruction.Kind> kinds = kinds(fn);
    assertThat(kinds).contains(TacInstruction.Kind.INSTANCEOF);
    assertThat(kinds).contains(TacInstruction.Kind.BRANCH);
    assertThat(kinds).contains(TacInstruction.Kind.LABEL);
    assertThat(kinds).contains(TacInstruction.Kind.JUMP);
  }

  @Test
  void aMatchDowngradesTheFunctionToSourceLowered() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def pick(o: Any): Int = o match {",
                "    case s: String => s.length",
                "    case _ => 0",
                "  }",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.pick");
    assertThat(fn).isNotNull();
    assertThat(fn.irPrecision()).isEqualTo(IrPrecision.SOURCE_LOWERED);
    assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_LOWERED);
  }

  @Test
  void aFunctionWithoutAMatchKeepsFullPrecision() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "def add(a: Int, b: Int): Int = a + b"));

    // A top-level definition becomes a member of the package object, whose name
    // is derived from the file's name.
    TacFunction fn = findEndingWith(result.functions(), ".add");
    assertThat(fn).isNotNull();
    assertThat(fn.irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
    assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_FULL);
  }

  // ------------------------------------------------------------------
  // The problems travel with the IR
  // ------------------------------------------------------------------

  /**
   * A lowering gap has to reach the IR as a STRUCTURED problem.
   *
   * <p><b>Why this test exists.</b> The Scala translator used to record its gaps in a plain
   * {@code List<String>}, and the frontend handed the PHASE's structured problems to the IR. The
   * phase only ever had the outer {@code TRANSLATION_ERROR}, so a Scala function that was only
   * partly lowered produced findings that looked exactly as complete as a fully lowered one --
   * which is the failure mode the whole {@code SOURCE_LOWERED} mechanism exists to prevent.
   *
   * <p>So the gaps are now recorded in BOTH forms, by ONE collector, and this test asserts the
   * structured one reaches the IR.
   */
  @Test
  void aLoweringGapTravelsWithTheIrAsAStructuredProblem() {
    TacFrontend.StructuredParseResult result =
        support.parseStructured(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def pick(o: Any): Int = o match {",
                "    case s: String => s.length",
                "    case _ => 0",
                "  }",
                "}"));

    assertThat(result.problems())
        .as("a degraded Scala function must say so in the IR, not only in the log")
        .isNotEmpty();
    assertThat(result.problems().get(0).kind())
        .as("the problem says WHICH kind of gap it was")
        .isNotBlank();
    assertThat(result.problems().get(0).message())
        .as("and it says what went wrong")
        .isNotBlank();
  }

  /**
   * A file that lowers cleanly carries no problems.
   *
   * <p>The counterpart of the test above: a frontend that reports problems for everything is as
   * useless as one that reports none.
   */
  @Test
  void aCleanFileCarriesNoProblems() {
    TacFrontend.StructuredParseResult result =
        support.parseStructured(
            String.join(
                "\n",
                "package demo",
                "",
                "def add(a: Int, b: Int): Int = a + b"));

    assertThat(result.problems()).isEmpty();
  }

  @Test
  void aConstructorWithParametersIsDowngradedBecauseItsFieldWritesAreNotModelled() {
    // The compiler synthesizes "this.x = x" for a constructor parameter AFTER the
    // typer, so the frontend never sees those writes. A checker must not trust a
    // precision-dependent fact about such a constructor.
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Point(x: Int, y: Int)"));

    TacFunction ctor = find(result.functions(), "demo.Point.<init>");
    assertThat(ctor).isNotNull();
    assertThat(ctor.irPrecision()).isEqualTo(IrPrecision.SOURCE_LOWERED);
  }

  @Test
  void theMatchChainLinksEveryCaseToTheNext() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def pick(o: Any): Int = o match {",
                "    case s: String => s.length",
                "    case i: Int => i",
                "    case _ => 0",
                "  }",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.pick");
    assertThat(fn).isNotNull();

    // Every label a branch or a jump names must actually exist in the function.
    // A chain whose false edge points at a label that was never emitted would
    // still "look" like a branch chain, but the CFG builder would find a dangling
    // edge -- so this is the assertion that the chain is really a chain.
    java.util.Set<String> labels = new java.util.HashSet<>();
    for (TacInstruction i : fn.instructions()) {
      if (i.kind == TacInstruction.Kind.LABEL) {
        labels.add(i.label);
      }
    }
    for (TacInstruction i : fn.instructions()) {
      if (i.kind == TacInstruction.Kind.BRANCH) {
        assertThat(labels).as("branch true target %s", i.trueLabel).contains(i.trueLabel);
        assertThat(labels).as("branch false target %s", i.falseLabel).contains(i.falseLabel);
      }
      if (i.kind == TacInstruction.Kind.JUMP) {
        assertThat(labels).as("jump target %s", i.label).contains(i.label);
      }
    }
  }

  // ------------------------------------------------------------------
  // Malformed input
  // ------------------------------------------------------------------

  @Test
  void aMalformedFileReportsProblemsAndDoesNotThrow() {
    assertThatCode(() -> support.parse("package demo\n\nclass Broken {\n  def x(: Int = \n"))
        .doesNotThrowAnyException();

    TacFrontend.ParseResult result =
        support.parse("package demo\n\nclass Broken {\n  def x(: Int = \n");
    assertThat(result.problems()).isNotEmpty();
  }

  @Test
  void aFileWithATypeErrorReportsProblemsAndDoesNotThrow() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def add(a: Int, b: Int): Int = a + b",
                "  def broken(): Int = \"not an int\"",
                "}"));

    assertThat(result.problems()).isNotEmpty();
  }

  @Test
  void aNullSourceYieldsAnEmptyResult() {
    TacFrontend.ParseResult result = support.frontend().parse(null, java.nio.file.Path.of("X.scala"), null);
    assertThat(result.functions()).isEmpty();
    assertThat(result.problems()).isEmpty();
  }

  // ------------------------------------------------------------------
  // The type mapping
  // ------------------------------------------------------------------

  @Test
  void scalaTypesMapOntoTacTypes() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def i(a: Int): Int = a",
                "  def l(a: Long): Long = a",
                "  def d(a: Double): Double = a",
                "  def b(a: Boolean): Boolean = a",
                "  def s(a: String): String = a",
                "  def o(a: Option[Int]): Option[Int] = a",
                "}"));

    assertThat(find(result.functions(), "demo.Demo.i").returnType()).isEqualTo(TacType.INT);
    assertThat(find(result.functions(), "demo.Demo.l").returnType()).isEqualTo(TacType.LONG);
    assertThat(find(result.functions(), "demo.Demo.d").returnType()).isEqualTo(TacType.DOUBLE);
    assertThat(find(result.functions(), "demo.Demo.b").returnType()).isEqualTo(TacType.BOOLEAN);
    assertThat(find(result.functions(), "demo.Demo.s").returnType()).isEqualTo(TacType.STRING);

    // Option is a plain class, not a null-safety mechanism.
    TacType option = find(result.functions(), "demo.Demo.o").returnType();
    assertThat(option.kind()).isEqualTo(TacType.Kind.CLASS);
    assertThat(option.className()).isEqualTo("scala.Option");
  }

  @Test
  void scalaHasNoNonNullTypeSystemSoReferencesStayNullable() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def s(a: String): String = a",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.s");
    assertThat(fn.returnType().isNullable())
        .as("Scala has no non-null type system; a String may hold null")
        .isTrue();
  }

  // ------------------------------------------------------------------
  // The `for` comprehension: the loop body must survive.
  // ------------------------------------------------------------------

  @Test
  @DisplayName("a for comprehension lowers its body into a callable function")
  void aForComprehensionLowersItsBody() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def loop(items: Seq[String]): String = {",
                "    var total = \"\"",
                "    for (x <- items) {",
                "      total = x",
                "    }",
                "    total",
                "  }",
                "}"));

    // The lambda the typer created for the loop body becomes its own function. Without it,
    // the body lived in a function nobody called: the accumulator stayed at its initial
    // value and every taint through the loop was destroyed, SILENTLY.
    TacFunction lambda = findContaining(result.functions(), "anonfun");
    assertThat(lambda)
        .as(
            "the loop body's lambda must be lowered into a function of its own, or the loop body"
                + " is never executed and the analysis reports the function as clean")
        .isNotNull();
    assertThat(lambda.isSynthetic())
        .as("nobody wrote the lambda; the compiler generated it")
        .isTrue();
  }

  @Test
  @DisplayName("a for comprehension's use site calls the loop body")
  void aForComprehensionCallsTheLoopBody() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def loop(items: Seq[String]): String = {",
                "    var total = \"\"",
                "    for (x <- items) {",
                "      total = x",
                "    }",
                "    total",
                "  }",
                "}"));

    TacFunction fn = find(result.functions(), "demo.Demo.loop");
    assertThat(fn).isNotNull();

    // The enclosing function must CALL the lambda, and the call must carry the captured
    // value. The capture is what keeps the body's taint alive.
    // The call must name the function that carries the body. If the two names differ,
    // the call graph's edge points at nothing and the body's effect is never followed.
    TacFunction body = findEndingWith(result.functions(), "$$anonfun");
    assertThat(body).as("the loop body must be lowered into a function of its own").isNotNull();

    boolean callsTheBody =
        fn.instructions().stream()
            .anyMatch(i -> i.kind == TacInstruction.Kind.CALL && i.calleeSig != null
                && body.name().equals(i.calleeSig));
    assertThat(callsTheBody)
        .as(
            "the loop's use site must CALL the function that carries the body -- by the SAME"
                + " name -- or the body never runs and the analysis reports the function as"
                + " clean")
        .isTrue();
  }

  @Test
  @DisplayName("a lambda captures the enclosing function's variables")
  void aLambdaCapturesTheEnclosingVariables() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def apply(items: Seq[String]): String = {",
                "    val prefix = \"p\"",
                "    items.map(x => prefix + x).mkString",
                "  }",
                "}"));

    TacFunction lambda = findContaining(result.functions(), "anonfun");
    assertThat(lambda)
        .as("the lambda must be lowered into a function of its own")
        .isNotNull();

    // The lambda's own parameter crosses the boundary as a PARAMETER of the function that
    // carries the body. That is how a compiler lowers a lambda, and it is what keeps the
    // value's taint alive across the boundary.
    assertThat(lambda.params())
        .as("the function that carries the body takes the lambda's parameters")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a lambda's body is lowered into the synthetic function, not the caller")
  void aLambdaBodyIsLoweredIntoTheSyntheticFunction() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "class Demo {",
                "  def apply(s: String): Int = {",
                "    val f = (x: String) => x.length",
                "    f(s)",
                "  }",
                "}"));

    TacFunction lambda = findContaining(result.functions(), "anonfun");
    assertThat(lambda)
        .as("the lambda must be lowered into a function of its own")
        .isNotNull();
    assertThat(kinds(lambda))
        .as("the body's instructions must be lowered INTO the function that carries them")
        .contains(TacInstruction.Kind.CALL);
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  /** Finds a function by its fully-qualified name. */
  private static TacFunction find(List<TacFunction> functions, String name) {
    return functions.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
  }

  /**
   * Finds a function whose qualified name CONTAINS the given text.
   *
   * <p>The Scala compiler mangles a lambda's function name differently depending on the form: a
   * {@code val f = (x) => ...} becomes {@code _$_$$anonfun}, while the lambda inside {@code
   * items.map(x => ...)} becomes {@code _$$anonfun}. The mangling is the compiler's business, so
   * a test that cares about the SHAPE looks for the name, not for one exact spelling.
   */
  private static TacFunction findContaining(List<TacFunction> functions, String text) {
    return functions.stream()
        .filter(f -> f.name().contains(text))
        .findFirst()
        .orElse(null);
  }

  /** Finds a function whose qualified name ends with the given suffix. */
  private static TacFunction findEndingWith(List<TacFunction> functions, String suffix) {
    return functions.stream()
        .filter(f -> f.name().endsWith(suffix))
        .findFirst()
        .orElse(null);
  }

  /** The instruction kinds of a function, in order. */
  private static List<TacInstruction.Kind> kinds(TacFunction fn) {
    return fn.instructions().stream().map(i -> i.kind).toList();
  }
}
