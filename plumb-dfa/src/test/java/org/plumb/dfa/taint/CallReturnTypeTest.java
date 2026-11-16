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
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.types.CalleeReturnTypes;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.python.PythonTacFrontend;
import org.plumb.tac.frontend.sidecar.PythonRuntime;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The type a CALL produces, and where the analysis learns it.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A call produces a value, and the type of that value is written down in the CALLEE's
 * declaration: a function that says it returns an int returns an int. The call site does not say it
 * -- {@code os.system(to_int(user_input))} says nothing about the type of its argument -- so
 * without the declaration the value that reaches the sink has no type at all, and a sink that needs
 * a string cannot tell an int from a string.
 *
 * <p>Two places know the answer, and both are exercised here:
 *
 * <ol type="a">
 *   <li>the FRONT END, for a callee it can see: a built-in function ({@code int}, {@code str}) and
 *       a function of the same module. The front end writes the type into the TAC, as the
 *       destination's type;
 *   <li>the ENGINE, for a callee the front end could not see: a function of ANOTHER module. The
 *       front end lowers one file at a time, so it cannot know what another file declares; the
 *       engine has the whole program, and it resolves the call to the declaration.
 * </ol>
 *
 * <h2>Why the direction of each case matters</h2>
 *
 * <p>The gate that asks about the type suppresses a finding when the value is PROVABLY not the type
 * the sink needs. So every case below is a case where a wrong answer would either lose a finding or
 * keep a false one:
 *
 * <ul>
 *   <li>a value the program says is a NUMBER stops reaching the sink -- the gate fired;
 *   <li>a value the program says is a STRING keeps reaching it -- the gate did not fire;
 *   <li>a value NOTHING says keeps reaching it -- an unknown type proves nothing.
 * </ul>
 *
 * <p><b>The programs differ in ONE line</b> -- the {@code ->} annotation -- so a failure says which
 * line the analysis read. The helper returns its argument unchanged, because a helper that parses
 * the value would drop the taint at the parse and the type would never be asked about.
 *
 * <h2>Why the index has a rule of its own</h2>
 *
 * <p>A key whose candidates disagree about what they return is not evidence of anything: the call
 * site cannot tell which one it called. Such a key is dropped, and the value stays untyped. The
 * last group of cases is about that rule.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The programs are Python, so the test needs a Python 3 runtime. A machine without one is
 * missing an environment, not carrying a broken product -- the same reason the Go front end's test
 * skips when there is no Go toolchain.
 */
@DisplayName("Taint: the type a call produces is carried in the data flow")
class CallReturnTypeTest {

  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  /**
   * A Python program whose tainted value comes from a function that declares it returns an int.
   *
   * <p>The helper returns its argument unchanged, so the taint survives the call and the type is
   * the only thing the gate has to go on.
   */
  private static final String PYTHON_ANNOTATED_NUMBER =
      """
      import os


      def to_int(value) -> int:
          return value


      def handler(request):
          count = to_int(request["count"])
          os.system(count)
      """;

  /** The same program, but the function declares it returns a string. */
  private static final String PYTHON_ANNOTATED_STRING =
      """
      import os


      def to_text(value) -> str:
          return value


      def handler(request):
          command = to_text(request["command"])
          os.system(command)
      """;

  /** The same program, but the function declares nothing. */
  private static final String PYTHON_UNANNOTATED =
      """
      import os


      def to_text(value):
          return value


      def handler(request):
          command = to_text(request["command"])
          os.system(command)
      """;

  /**
   * A Python program whose tainted value is put through a built-in conversion.
   *
   * <p>The conversion's result is an int whatever the request holds, so the TAC can say so.
   */
  private static final String PYTHON_BUILTIN_CONVERSION =
      """
      import os


      def handler(request):
          count = int(request["count"])
          os.system(count)
      """;

  /** The module the cross-module case imports from. */
  private static final String PYTHON_HELPER =
      """
      def to_int(value) -> int:
          return value
      """;

  /**
   * The caller of the other module.
   *
   * <p>The front end lowers one file at a time, so it cannot see what the other module declares.
   * The engine has the whole program, and it is the engine that resolves the call.
   */
  private static final String PYTHON_CROSS_MODULE_CALLER =
      """
      import os
      from helpers import to_int


      def handler(request):
          count = to_int(request["count"])
          os.system(count)
      """;

  @Test
  @DisplayName("a built-in call carries the type it returns")
  void aBuiltInCallCarriesTheTypeItReturns(@TempDir Path directory) throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_BUILTIN_CONVERSION);

    TacType type = typeOfCallResult(PYTHON_BUILTIN_CONVERSION, file, "builtins.int/1");

    assertThat(type)
        .as("int(request[...]) is an int whatever the request holds, and the TAC says so")
        .isEqualTo(TacType.INT);
  }

  @Test
  @DisplayName("a function carries the return type it declares")
  void aFunctionCarriesTheReturnTypeItDeclares(@TempDir Path directory) throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_ANNOTATED_NUMBER);

    TacType type = returnTypeOf(PYTHON_ANNOTATED_NUMBER, file, ".to_int");

    assertThat(type).as("the function declares it returns an int").isEqualTo(TacType.INT);
  }

  @Test
  @DisplayName("a function that declares it returns a number stops the value reaching the sink")
  void aFunctionThatDeclaresItReturnsANumberStopsTheValueReachingTheSink(@TempDir Path directory)
      throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_ANNOTATED_NUMBER);

    List<Finding> findings =
        scan(List.of(PYTHON_ANNOTATED_NUMBER), List.of(file), new CommandInjectionChecker());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system"))
        .as("the function declares it returns an int, so the value at the sink is an int")
        .isFalse();
  }

  @Test
  @DisplayName("a function that declares it returns text keeps the value reaching the sink")
  void aFunctionThatDeclaresItReturnsTextKeepsTheValueReachingTheSink(@TempDir Path directory)
      throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_ANNOTATED_STRING);

    List<Finding> findings =
        scan(List.of(PYTHON_ANNOTATED_STRING), List.of(file), new CommandInjectionChecker());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system"))
        .as("the function declares it returns a string, so the finding is still there")
        .isTrue();
  }

  @Test
  @DisplayName("a function that declares nothing keeps the value reaching the sink")
  void aFunctionThatDeclaresNothingKeepsTheValueReachingTheSink(@TempDir Path directory)
      throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_UNANNOTATED);

    List<Finding> findings =
        scan(List.of(PYTHON_UNANNOTATED), List.of(file), new CommandInjectionChecker());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system"))
        .as("no declaration means no type, and an unknown type proves nothing")
        .isTrue();
  }

  @Test
  @DisplayName("a call to another module is typed by the engine, not by the front end")
  void aCallToAnotherModuleIsTypedByTheEngine(@TempDir Path directory) throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path helper = directory.resolve("helpers.py");
    Files.writeString(helper, PYTHON_HELPER);
    Path caller = directory.resolve("app.py");
    Files.writeString(caller, PYTHON_CROSS_MODULE_CALLER);

    List<Finding> findings =
        scan(
            List.of(PYTHON_HELPER, PYTHON_CROSS_MODULE_CALLER),
            List.of(helper, caller),
            new CommandInjectionChecker());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system"))
        .as(
            "the front end lowers one file and cannot see the other module's declaration, but the"
                + " engine has the whole program and resolves the call to it")
        .isFalse();
  }

  @Test
  @DisplayName("the engine finds the type a callee declares")
  void theEngineFindsTheTypeACalleeDeclares() {
    TacProgram program =
        programWith(function("helpers.to_int", "helpers.to_int():int", TacType.INT));

    CalleeReturnTypes types = CalleeReturnTypes.of(program);

    assertThat(types.returnTypeOf("helpers.to_int/0")).isEqualTo(TacType.INT);
  }

  @Test
  @DisplayName("two callees that disagree about what they return are not evidence")
  void twoCalleesThatDisagreeAboutWhatTheyReturnAreNotEvidence() {
    TacProgram program =
        programWith(
            function("helpers.to_int", "helpers.to_int():int", TacType.INT),
            function("helpers.to_text", "helpers.to_int():str", TacType.STRING));

    CalleeReturnTypes types = CalleeReturnTypes.of(program);

    assertThat(types.returnTypeOf("helpers.to_int/0"))
        .as("the call site cannot tell which of the two it called")
        .isNull();
  }

  @Test
  @DisplayName("two callees that agree about what they return are evidence")
  void twoCalleesThatAgreeAboutWhatTheyReturnAreEvidence() {
    TacProgram program =
        programWith(
            function("a.to_int", "a.to_int():int", TacType.INT),
            function("b.to_int", "b.to_int():int", TacType.INT));

    CalleeReturnTypes types = CalleeReturnTypes.of(program);

    assertThat(types.returnTypeOf("a.to_int/0")).isEqualTo(TacType.INT);
    assertThat(types.returnTypeOf("b.to_int/0")).isEqualTo(TacType.INT);
  }

  @Test
  @DisplayName("a function that returns nothing says nothing about the value")
  void aFunctionThatReturnsNothingSaysNothingAboutTheValue() {
    TacProgram program = programWith(function("helpers.run", "helpers.run():void", TacType.VOID));

    CalleeReturnTypes types = CalleeReturnTypes.of(program);

    assertThat(types.returnTypeOf("helpers.run/0")).isNull();
  }

  @Test
  @DisplayName("a call whose owner the front end spelled differently is still found")
  void aCallWhoseOwnerTheFrontEndSpelledDifferentlyIsStillFound() {
    TacProgram program = programWith(function("to_int", "to_int():int", TacType.INT));

    CalleeReturnTypes types = CalleeReturnTypes.of(program);

    assertThat(types.returnTypeOf("helpers.to_int/0"))
        .as("the owner is dropped, and the method name and the arity still join the two sides")
        .isEqualTo(TacType.INT);
  }

  // ------------------------------------------------------------------ the harness

  /**
   * Run the sources through the front end, then through the engine.
   *
   * @param sources the source texts, in the order the files are given
   * @param files the files the sources came from
   * @param checker the rule under test
   * @return the findings the engine reported
   */
  private static List<Finding> scan(List<String> sources, List<Path> files, DfaChecker checker) {
    TacFrontend frontend = new PythonTacFrontend();
    TacProgram program = new TacProgram(files.get(0), "app");
    for (int i = 0; i < sources.size(); i++) {
      TacFrontend.ParseResult result = frontend.parse(sources.get(i), files.get(i), null);
      assertThat(result.problems()).as("the source must lower without a problem").isEmpty();
      for (TacFunction function : result.functions()) {
        program.addFunction(function);
      }
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

  /**
   * The type the destination of a call carries.
   *
   * @param source the source text
   * @param file the file the source came from
   * @param calleeSig the callee whose destination is asked about
   * @return the type, or null when the call is not there
   */
  private static TacType typeOfCallResult(String source, Path file, String calleeSig) {
    for (TacFunction function : parse(source, file)) {
      for (TacInstruction instruction : function.instructions()) {
        if (instruction.kind != TacInstruction.Kind.CALL) {
          continue;
        }
        if (!calleeSig.equals(instruction.calleeSig)) {
          continue;
        }
        if (instruction.dest instanceof TacOperand.TacVar dest) {
          return dest.type();
        }
      }
    }
    return null;
  }

  /**
   * The return type a function declares.
   *
   * @param source the source text
   * @param file the file the source came from
   * @param signaturePart a part of the signature of the function asked about
   * @return the type, or null when the function is not there
   */
  private static TacType returnTypeOf(String source, Path file, String signaturePart) {
    for (TacFunction function : parse(source, file)) {
      if (function.signature().contains(signaturePart)) {
        return function.returnType();
      }
    }
    return null;
  }

  /** The functions a source lowers to. */
  private static List<TacFunction> parse(String source, Path file) {
    TacFrontend.ParseResult result = new PythonTacFrontend().parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();
    return result.functions();
  }

  /** A program that holds the given functions. */
  private static TacProgram programWith(TacFunction... functions) {
    TacProgram program = new TacProgram(Path.of("test"), "app");
    for (TacFunction function : functions) {
      program.addFunction(function);
    }
    return program;
  }

  /** A function that declares a return type and does nothing else. */
  private static TacFunction function(String name, String signature, TacType returnType) {
    return new TacFunction(name, signature, returnType, List.of(), List.of(), List.of());
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
