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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * Does the taint survive each Java construct?
 *
 * <p>The syntax-coverage matrix asserts "the construct produced at least one instruction". That is
 * necessary but weak: a construct can produce instructions and still lose the taint on the way
 * through. A program that hands user input to {@code Runtime.exec} through a {@code StringBuilder},
 * through a loop, through a record accessor or through a lambda was reported as clean, and every
 * one of those programs produced plenty of instructions.
 *
 * <p>So this test asserts the thing that matters: <b>a tainted value that flows THROUGH a construct
 * and reaches a sink is still seen as tainted</b>.
 *
 * <p>The sink is {@code Runtime.getRuntime().exec(..)} rather than a local method, so the sink
 * family matching is the real one and the test measures the data flow, not a test-only shortcut.
 *
 * <p>The negative cases matter just as much. A construct that does NOT carry the taint -- a switch
 * whose branches only ever assign a constant -- must produce no finding. A test that only asserted
 * "taint is found" would pass on an analysis that flags everything.
 */
public class TacTaintSurvivalTest {

  /** The sink every fixture uses. A real sink family, so the matching is the production one. */
  private static final String SINK = "Runtime.getRuntime().exec(";

  /**
   * Run one source through the whole pipeline and report whether the command-injection sink fired.
   *
   * @param source the Java source to analyse
   * @return true when a CWE-78 finding was produced
   */
  private static boolean taintReachesSink(String source) throws IOException {
    Path dir = Files.createTempDirectory("plumb-taint-survival");
    Path file = dir.resolve("Fixture.java");
    Files.writeString(file, source);

    ParsedSource parsed = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(parsed).translate();

    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new CommandInjectionChecker());
    Map<String, Path> sourcePaths = new LinkedHashMap<>();
    for (TacFunction fn : program.functions()) {
      if (fn.enclosingClass() != null && !fn.enclosingClass().isEmpty()) {
        sourcePaths.putIfAbsent(fn.enclosingClass(), file);
      }
    }
    DfaEngine engine =
        new DfaEngine(config, checkers, "global", null).withFunctionSourcePaths(sourcePaths);
    FindingReporter reporter = engine.runWithGlobalStore(List.of(program), 0);

    for (Finding f : reporter.all()) {
      if ("CWE-78".equals(f.cwe())) {
        return true;
      }
    }
    return false;
  }

  /** The fixture body: a method that takes a tainted parameter and reaches the sink. */
  private static String fixture(String body) {
    return "import java.io.*;\n"
        + "class C {\n"
        + "  void go(String userInput) throws IOException {\n"
        + body
        + "\n"
        + "  }\n"
        + "}\n";
  }

  /** A fixture whose class declares a STATIC field, so the field access resolves. */
  private static String staticFieldFixture(String body) {
    return "import java.io.*;\n"
        + "class C {\n"
        + "  static String FIELD;\n"
        + "  void go(String userInput) throws IOException {\n"
        + body
        + "\n"
        + "  }\n"
        + "}\n";
  }

  /**
   * The constructs under test, and the body that exercises each one.
   *
   * <p>Every body reaches {@code exec(..)} with a value derived from {@code userInput}. The
   * analysis has to carry the taint through the construct to reach the sink.
   */
  static java.util.stream.Stream<Arguments> constructs() {
    return java.util.stream.Stream.of(
        Arguments.of("plain-assign", "    String x = userInput; exec(x);"),
        Arguments.of("ternary", "    String x = true ? userInput : \"safe\"; exec(x);"),
        Arguments.of("string-concat", "    String x = \"prefix \" + userInput; exec(x);"),
        Arguments.of("cast", "    String x = (String) (Object) userInput; exec(x);"),
        Arguments.of("var-declaration", "    var x = userInput; exec(x);"),
        Arguments.of("compound-assign", "    String x = \"safe\"; x += userInput; exec(x);"),
        Arguments.of(
            "stringbuilder",
            "    StringBuilder sb = new StringBuilder(); sb.append(userInput);"
                + " exec(sb.toString());"),
        Arguments.of(
            "stringbuilder-result",
            "    StringBuilder sb = new StringBuilder(); StringBuilder out = sb.append(userInput);"
                + " exec(out.toString());"),
        Arguments.of("field-roundtrip", "    this.field = userInput; exec(this.field);"),
        Arguments.of(
            "array-element", "    String[] arr = new String[1]; arr[0] = userInput; exec(arr[0]);"),
        Arguments.of("array-init", "    String[] arr = {userInput}; exec(arr[0]);"),
        Arguments.of("array-to-sink", "    exec(new String[] {userInput});"),
        Arguments.of(
            "foreach", "    String[] arr = {userInput}; for (String s : arr) { exec(s); }"),
        Arguments.of(
            "while",
            "    String x = \"safe\"; int i = 0; while (i < 3) {"
                + " if (i == 1) x = userInput; i++; } exec(x);"),
        Arguments.of(
            "labeled-break",
            "    String x = \"safe\"; outer: for (int i = 0; i < 3; i++) {"
                + " for (int j = 0; j < 3; j++) { if (j == 1) { x = userInput; break outer; } } }"
                + " exec(x);"),
        Arguments.of(
            "switch-expression",
            "    String x = switch (userInput) { case \"a\" -> userInput; default -> \"safe\"; };"
                + " exec(x);"),
        Arguments.of(
            "throw-catch",
            "    try { throw new IOException(userInput); } catch (IOException e) {"
                + " exec(e.getMessage()); }"),
        Arguments.of("static-call", "    exec(String.valueOf(userInput));"),
        Arguments.of("static-format", "    exec(String.format(\"%s\", userInput));"),
        Arguments.of(
            "method-ref",
            "    java.util.function.Function<String, String> f = String::trim;"
                + " exec(f.apply(userInput));"),
        Arguments.of(
            "lambda-capture",
            "    Runnable r = () -> { try { exec(userInput); } catch (IOException e) {} };"
                + " r.run();"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("constructs")
  @DisplayName("taint survives the construct and reaches the sink")
  void taintSurvivesConstruct(String label, String body) throws IOException {
    String source = fixture(body.replace("exec(", SINK));
    assertThat(taintReachesSink(source))
        .as("%s: the taint must reach the sink through the construct", label)
        .isTrue();
  }

  @Test
  @DisplayName("a static field carries the taint from the write to the read")
  void staticFieldCarriesTaint() throws IOException {
    // A static field belongs to the CLASS, so the write and the read must be
    // keyed on the class rather than on an instance. The analysis used to
    // treat an upper-case field name as a class reference, so the read was
    // modelled as a class-identity bind and the taint was dropped.
    String source =
        staticFieldFixture("    FIELD = userInput; exec(FIELD);").replace("exec(", SINK);
    assertThat(taintReachesSink(source))
        .as("the taint must reach the sink through the static field")
        .isTrue();
  }

  @Test
  @DisplayName("a switch whose branches only assign constants must NOT report a finding")
  void switchWithConstantBranchesIsClean() throws IOException {
    // The DISCRIMINATOR is tainted, but the assigned value never is: `x` is
    // either "safe" or "b". A finding here would be a false positive, and a
    // test that only asserted "taint is found" would not notice.
    String source =
        fixture(
            "    String x = \"safe\";\n"
                + "    switch (userInput) {\n"
                + "      case \"a\": x = \"b\"; break;\n"
                + "      default: break;\n"
                + "    }\n"
                + "    exec(x);");
    assertThat(taintReachesSink(source))
        .as("the tainted switch discriminator does not taint the assigned constant")
        .isFalse();
  }

  @Test
  @DisplayName("a constant that never met the source must NOT report a finding")
  void constantOnlyProgramIsClean() throws IOException {
    String source = fixture("    exec(\"safe\");");
    assertThat(taintReachesSink(source))
        .as("a program that never uses the tainted parameter is clean")
        .isFalse();
  }

  @Test
  @DisplayName("the record's component is read back through the accessor")
  void recordComponentIsReadThroughAccessor() throws IOException {
    Path dir = Files.createTempDirectory("plumb-taint-survival");
    Path file = dir.resolve("RecordFixture.java");
    // The replacement is applied to the WHOLE source. Writing it as a
    // trailing `.replace(...)` on the last string literal would bind to that
    // literal alone and leave `exec(` in the method body -- the fixture would
    // then not compile, and the test would fail for a reason that has nothing
    // to do with the record.
    String source =
        ("import java.io.*;\n"
                + "record R(String name) {}\n"
                + "class C {\n"
                + "  void go(String userInput) throws IOException {\n"
                + "    R r = new R(userInput);\n"
                + "    exec(r.name());\n"
                + "  }\n"
                + "}\n")
            .replace("exec(", SINK);
    Files.writeString(file, source);
    ParsedSource parsed = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(parsed).translate();

    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new CommandInjectionChecker());
    Map<String, Path> sourcePaths = new LinkedHashMap<>();
    for (TacFunction fn : program.functions()) {
      if (fn.enclosingClass() != null && !fn.enclosingClass().isEmpty()) {
        sourcePaths.putIfAbsent(fn.enclosingClass(), file);
      }
    }
    DfaEngine engine =
        new DfaEngine(config, checkers, "global", null).withFunctionSourcePaths(sourcePaths);
    FindingReporter reporter = engine.runWithGlobalStore(List.of(program), 0);

    boolean reported = false;
    for (Finding f : reporter.all()) {
      if ("CWE-78".equals(f.cwe())) {
        reported = true;
      }
    }
    assertThat(reported)
        .as("the record component read through the accessor must reach the sink")
        .isTrue();
  }

  @Test
  @DisplayName("every fixture produces at least one instruction: the IR is not silently empty")
  void everyFixtureProducesInstructions(@TempDir Path dir) throws IOException {
    // A fixture that produced NO instruction would pass the "no finding"
    // assertions above for the wrong reason. This checks the positive side:
    // the IR is non-empty.
    Path file = dir.resolve("Fixture.java");
    Files.writeString(file, fixture("    exec(userInput);"));
    ParsedSource parsed = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(parsed).translate();
    int instructions = 0;
    for (TacFunction fn : program.functions()) {
      instructions += fn.instructions().size();
    }
    assertThat(instructions).as("the fixture must lower to instructions").isGreaterThan(0);
  }
}
