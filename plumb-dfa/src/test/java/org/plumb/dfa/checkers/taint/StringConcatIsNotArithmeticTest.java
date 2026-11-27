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
package org.plumb.dfa.checkers.taint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.boundary.OverflowConstraintChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.python.PythonTacFrontend;
import org.plumb.tac.frontend.sidecar.PythonRuntime;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * A string concatenation is not an arithmetic operation.
 *
 * <h2>Why this is pinned</h2>
 *
 * <p>The integer overflow rules must not report a concatenation: adding two strings does not
 * overflow, it builds a longer string. Each arithmetic checker had its own guard for that, but the
 * guard could only see a string LITERAL sitting next to the operation -- because that is the one
 * place the IR carries a type. A script front end has no symbol solver, so its values are typed
 * UNKNOWN.
 *
 * <p>So the guard missed every concatenation whose string-ness was one step away:
 *
 * <pre>
 *   t_t6 = t_t5 + "/"     the literal makes this a concatenation
 *   t_t7 = t_t6 + bar     the line above makes this a concatenation too
 * </pre>
 *
 * <p>The second line was reported as an integer overflow -- on a string. Measured against the OWASP
 * Benchmark for Python (1230 files), the arithmetic rules accounted for about 6,600 of the 10,190
 * findings, and every one of them named a CWE the analysed file was not about.
 *
 * <h2>Why the control matters</h2>
 *
 * <p>Without it, "the rule knows a string when it sees one" and "the rule stopped reporting" look
 * exactly the same. The control adds a tainted value to a NUMBER and insists the finding is still
 * there -- so the test pins the difference between the two operations, not the absence of a report.
 */
@DisplayName("Taint: a string concatenation is not an arithmetic hazard")
class StringConcatIsNotArithmeticTest {

  private static final String OVERFLOW_RULE = "dfa-taint-integer-overflow";

  private static final String BOUNDARY_RULE = "dfa-boundary-integer-overflow";

  /**
   * A Python program that builds a path out of a request value.
   *
   * <p>The value that arrived is a string, and so is everything it is concatenated with.
   *
   * <p>The shape matters: the first addition puts the request value into a string, and the second
   * one appends to THAT. The second addition has no literal next to it -- the string-ness is one
   * step away -- and that is the shape the guard missed.
   */
  private static final String PYTHON_CONCATENATION =
      """
      import flask

      app = flask.Flask(__name__)


      @app.route("/read", methods=["GET"])
      def handler():
          param = flask.request.cookies.get("name")
          directory = "/data/" + param
          path = directory + param
          return path
      """;

  /**
   * The same program with a NUMBER added instead of a string.
   *
   * <p>The control: adding a request value to a number can overflow, so the report must stay.
   */
  private static final String PYTHON_ADDITION =
      """
      import flask

      app = flask.Flask(__name__)


      @app.route("/read", methods=["GET"])
      def handler():
          param = flask.request.cookies.get("name")
          total = 1 + param
          return str(total)
      """;

  // ------------------------------------------------------------------ the hand-written TAC

  @Test
  @DisplayName("a concatenation whose string-ness is one step away is not an overflow")
  void aConcatenationWhoseStringnessIsOneStepAwayIsNotAnOverflow() {
    // The shape the guard missed: the first addition is a concatenation because
    // of its literal, and the second one is a concatenation because of the first.
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar directory = new TacOperand.TacVar("t_t2", TacType.UNKNOWN, true);
    TacOperand.TacVar path = new TacOperand.TacVar("t_t3", TacType.UNKNOWN, true);

    List<Finding> found =
        scan(
            function(
                List.of(
                    binop(
                        directory,
                        new TacOperand.TacVar("t_t5", TacType.UNKNOWN, true),
                        slash(),
                        10),
                    binop(path, directory, request, 11))),
            new IntOverflowChecker());

    assertThat(found).as("adding two strings cannot overflow an int").isEmpty();
  }

  @Test
  @DisplayName("adding a tainted value to a number is still an overflow")
  void addingATaintedValueToANumberIsStillAnOverflow() {
    // The control. The rule must still report the arithmetic hazard, or the
    // fix is a blanket suppression and not a fix.
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar total = new TacOperand.TacVar("t_t3", TacType.UNKNOWN, true);

    List<Finding> found =
        scan(
            function(List.of(binop(total, request, TacOperand.Const.ofInt(1), 11))),
            new IntOverflowChecker());

    assertThat(found).as("a request value added to a number can overflow it").hasSize(1);
    assertThat(found.get(0).message()).as("the report must name the operation").contains("ADD");
  }

  // ------------------------------------------------------------------ the real front end

  @Test
  @DisplayName("the real Python program: a path built out of a request value")
  void theRealPythonProgramAPathBuiltOutOfARequestValue(@TempDir Path directory)
      throws IOException {
    assumeTrue(
        PythonRuntime.locate() != null,
        "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_CONCATENATION);

    List<Finding> found =
        scan(
            new PythonTacFrontend(),
            PYTHON_CONCATENATION,
            file,
            new IntOverflowChecker(),
            new OverflowConstraintChecker());

    assertThat(found)
        .as("a path built out of a request value is a concatenation, not an arithmetic hazard")
        .isEmpty();
  }

  @Test
  @DisplayName("the real Python program: a number added to a request value")
  void theRealPythonProgramANumberAddedToARequestValue(@TempDir Path directory) throws IOException {
    assumeTrue(
        PythonRuntime.locate() != null,
        "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_ADDITION);

    List<Finding> found =
        scan(
            new PythonTacFrontend(),
            PYTHON_ADDITION,
            file,
            new IntOverflowChecker(),
            new OverflowConstraintChecker());

    assertThat(found).as("adding a request value to a number is an arithmetic hazard").isNotEmpty();
  }

  // ------------------------------------------------------------------ the helpers

  /** The string literal {@code "/"}. */
  private static TacOperand slash() {
    return TacOperand.Const.ofString("/");
  }

  /** One binary operation. */
  private static TacInstruction binop(TacOperand dest, TacOperand lhs1, TacOperand lhs2, int line) {
    return TacInstruction.builder(TacInstruction.Kind.BINOP)
        .dest(dest)
        .lhs1(lhs1)
        .lhs2(lhs2)
        .binop(BinaryOperator.ADD)
        .line(line)
        .column(1)
        .build();
  }

  /**
   * The function under analysis.
   *
   * <p>The request arrives as a parameter, which the engine treats as untrusted until a caller says
   * otherwise -- that is how a value with no source call still reaches a rule.
   */
  private static TacFunction function(List<TacInstruction> instructions) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(request),
        List.of(),
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.PYTHON3,
        IrPrecision.SOURCE_FULL);
  }

  /** Scan a hand-written function. */
  private static List<Finding> scan(TacFunction function, DfaChecker... checkers) {
    TacProgram program = new TacProgram(Path.of("app.py"), "app");
    program.addFunction(function);
    return run(program, checkers);
  }

  /** Run the source through the front end, then through the engine. */
  private static List<Finding> scan(
      TacFrontend frontend, String source, Path file, DfaChecker... checkers) {
    TacFrontend.ParseResult result = frontend.parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }
    return run(program, checkers);
  }

  /** The findings the arithmetic rules reported. */
  private static List<Finding> run(TacProgram program, DfaChecker... checkers) {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checkers), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (OVERFLOW_RULE.equals(finding.ruleId()) || BOUNDARY_RULE.equals(finding.ruleId())) {
        out.add(finding);
      }
    }
    return out;
  }
}
