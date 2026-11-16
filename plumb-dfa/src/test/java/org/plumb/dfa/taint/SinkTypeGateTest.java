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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.checkers.taint.RegexInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.types.TypeCompatibility;
import org.plumb.dfa.types.TypeSet;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.python.PythonTacFrontend;
import org.plumb.tac.frontend.sidecar.PythonRuntime;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The type a value has, carried in the data flow, and the sink that asks about it.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The taint domain is a string domain: every tainted value is treated as a string. That is right
 * most of the time and wrong in one observable way. A value that is PROVABLY a number is not a
 * string, so a tainted number reaching a sink that needs a string is not that vulnerability --
 * {@code os.system(42)} raises a TypeError, it does not run a command.
 *
 * <p>So the data flow carries the type of each value, and a sink family may declare the type its
 * argument must have. The gate fires only when EVERY type the value may hold is incompatible with
 * the expected one.
 *
 * <h2>Why these four programs</h2>
 *
 * <p>The gate has to be right in both directions, so the test pins both:
 *
 * <ol type="a">
 *   <li>a value the program says is a NUMBER does not reach a sink that needs a string -- the
 *       finding is gone;
 *   <li>a value the program says is a STRING still reaches it -- the gate did not switch the rule
 *       off;
 *   <li>a value whose type NOTHING knows still reaches it -- an unknown type proves nothing, and a
 *       gate that fired on an unknown type would throw away findings the analysis had no reason to
 *       doubt;
 *   <li>a family that declares NO type is not affected at all -- the gate is the catalogue's
 *       decision, not a rule the engine applies on its own.
 * </ol>
 *
 * <p>The first three programs differ in ONE line, so a failure says which line the gate read. The
 * fourth uses a different family on purpose: it is the control for the whole mechanism.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The programs are Python, so the test needs a Python 3 runtime. A machine without one is
 * missing an environment, not carrying a broken product -- the same reason the Go front end's test
 * skips when there is no Go toolchain.
 */
@DisplayName(
    "Taint: a value the program says is a number does not reach a sink that needs a string")
class SinkTypeGateTest {

  private static final String COMMAND_RULE = "dfa-taint-command-injection";
  private static final String REGEX_RULE = "dfa-taint-regex-injection";

  /**
   * A Python program whose tainted value is declared to be a NUMBER.
   *
   * <p>{@code request} is a parameter, so the engine seeds it as a source and the value it holds is
   * untrusted. The annotation says the value is an int, which is what the gate reads.
   */
  private static final String PYTHON_NUMBER =
      """
      import os


      def handler(request):
          count: int = request["count"]
          os.system(count)
      """;

  /**
   * The same program with the value declared to be a STRING.
   *
   * <p>The one line that differs is the annotation, so a failure here says the gate fired on a
   * value that was never a number.
   */
  private static final String PYTHON_STRING =
      """
      import os


      def handler(request):
          command: str = request["command"]
          os.system(command)
      """;

  /**
   * The same program with NO annotation.
   *
   * <p>Nothing says what the value is, so nothing is proven about it and the finding stands. A gate
   * that fired here would be throwing away findings the analysis had no reason to doubt.
   */
  private static final String PYTHON_UNTYPED =
      """
      import os


      def handler(request):
          command = request["command"]
          os.system(command)
      """;

  /**
   * A Python program whose tainted value is declared to be a NUMBER, and reaches a family that
   * declares no type.
   *
   * <p>The regex family is the control: it is not one of the families that name a type, so the gate
   * has nothing to ask and the finding is reported exactly as before.
   */
  private static final String PYTHON_NUMBER_INTO_A_FAMILY_WITHOUT_A_TYPE =
      """
      import re


      def handler(request):
          pattern: int = request["pattern"]
          re.compile(pattern)
      """;

  @Test
  @DisplayName("a tainted number does not reach a sink that needs a string")
  void aTaintedNumberDoesNotReachACommandSink(@TempDir Path directory) throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_NUMBER);

    List<Finding> findings =
        scan(new PythonTacFrontend(), PYTHON_NUMBER, file, new CommandInjectionChecker());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system"))
        .as("a value the program says is an int is not a command line: os.system(42) raises")
        .isFalse();
  }

  @Test
  @DisplayName("a tainted string still reaches a sink that needs a string")
  void aTaintedStringStillReachesACommandSink(@TempDir Path directory) throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_STRING);

    List<Finding> findings =
        scan(new PythonTacFrontend(), PYTHON_STRING, file, new CommandInjectionChecker());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system"))
        .as("the value is a string, so the command injection is still there")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted value of an unknown type still reaches a sink that needs a string")
  void aTaintedValueOfUnknownTypeStillReachesACommandSink(@TempDir Path directory)
      throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_UNTYPED);

    List<Finding> findings =
        scan(new PythonTacFrontend(), PYTHON_UNTYPED, file, new CommandInjectionChecker());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system"))
        .as("nothing says the value is not a string, so the finding stands")
        .isTrue();
  }

  @Test
  @DisplayName("a family that declares no type is not affected by the gate")
  void aFamilyThatDeclaresNoTypeIsNotAffectedByTheGate(@TempDir Path directory) throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_NUMBER_INTO_A_FAMILY_WITHOUT_A_TYPE);

    List<Finding> findings =
        scan(
            new PythonTacFrontend(),
            PYTHON_NUMBER_INTO_A_FAMILY_WITHOUT_A_TYPE,
            file,
            new RegexInjectionChecker());

    assertThat(hasFinding(findings, REGEX_RULE, "re.compile"))
        .as("the regex family declares no type, so every value is reported as before")
        .isTrue();
  }

  @Test
  @DisplayName("a value that may hold either type keeps its finding")
  void aValueThatMayHoldEitherTypeKeepsItsFinding() {
    TypeSet set = TypeSet.of(TacType.INT);
    set.add(TacType.STRING);

    assertThat(set.isDefinitelyIncompatibleWith(TacType.STRING))
        .as("one compatible member is enough to keep the finding")
        .isFalse();
  }

  @Test
  @DisplayName("a value that is only ever a number is provably not a string")
  void aValueThatIsOnlyEverANumberIsProvablyNotAString() {
    assertThat(TypeSet.of(TacType.INT).isDefinitelyIncompatibleWith(TacType.STRING)).isTrue();
  }

  @Test
  @DisplayName("nothing known about the value proves nothing about it")
  void nothingKnownAboutTheValueProvesNothingAboutIt() {
    assertThat(TypeSet.empty().isDefinitelyIncompatibleWith(TacType.STRING))
        .as("an empty set is not evidence")
        .isFalse();
  }

  @Test
  @DisplayName("the catalogue only names types the engine understands")
  void theCatalogueOnlyNamesTypesTheEngineUnderstands() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<String> declared = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (SinkFamily family : config.sinkFamilies().all()) {
      String word = family.expectsType();
      if (word == null) {
        continue;
      }
      declared.add(family.name() + " -> " + word);
      if (TypeCompatibility.expectedType(word) == null) {
        unknown.add(family.name() + " -> " + word);
      }
    }

    assertThat(unknown)
        .as(
            "a word the engine does not understand switches the gate off silently, and the"
                + " family looks like it has a type")
        .isEmpty();
    assertThat(declared)
        .as("the catalogue must name at least one type, or the mechanism is dead code")
        .isNotEmpty();
  }

  // ------------------------------------------------------------------ the harness

  /**
   * Run the source through the front end, then through the engine.
   *
   * @param frontend the front end that lowers the source
   * @param source the source text
   * @param file the file the source came from
   * @param checker the rule under test
   * @return the findings the engine reported
   */
  private static List<Finding> scan(
      TacFrontend frontend, String source, Path file, DfaChecker checker) {
    TacFrontend.ParseResult result = frontend.parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        findings.add(finding);
      }
    }
    return findings;
  }

  /** Whether a finding of the given rule names the given callee. */
  private static boolean hasFinding(List<Finding> findings, String rule, String callee) {
    for (Finding finding : findings) {
      if (!rule.equals(finding.ruleId())) {
        continue;
      }
      if (finding.message().contains(callee)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the Python front end can run here. */
  private static boolean pythonAvailable() {
    return PythonRuntime.locate() != null;
  }
}
