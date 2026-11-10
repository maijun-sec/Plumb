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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.php.PhpSidecarClient;
import org.plumb.tac.frontend.php.PhpTacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The taint sources that are not calls: PHP's superglobals.
 *
 * <p><b>Why this needs its own concept.</b> Every other source the catalogue names is a callee
 * signature -- {@code java.io.FileInputStream.read}, {@code os.environ.get} -- and the catalogue
 * matches on exactly that. A PHP superglobal is not a call. Measured against the PHP side car,
 * {@code $_GET['user']} lowers to an {@code ARRAYLOAD} whose base is the variable and whose index
 * is a constant:
 *
 * <pre>
 *   ARRAYLOAD dest=t_t1  lhs1=VAR(_GET)  lhs2=CONST("user")
 *   MOVE     dest=q     lhs1=VAR(t_t1)
 * </pre>
 *
 * <p>There is no callee signature in that, so a pattern has nothing to match and the value arrives
 * with no marker at all. {@code $_GET['x']} flowing into {@code system()} is the most classic PHP
 * vulnerability there is, and a catalogue that can only name calls sees nothing.
 *
 * <p><b>What the catalogue says instead.</b> A source family may declare SOURCE VARIABLES: the
 * names of variables that carry untrusted data, scoped by language. The engine seeds them at the
 * function entry, beside the parameters, because a superglobal is a trust-boundary entry point in
 * exactly that sense -- the request reached the program through it.
 *
 * <p><b>Why the scoping is the whole point.</b> The name {@code _GET} means "the request's query
 * string" in PHP and nothing at all in any other language. A rule that applied everywhere would
 * claim every Java program with a field called {@code _GET}, so the declaration is per language and
 * the engine asks for the names of the language it is analysing.
 *
 * <p><b>What is deliberately NOT declared.</b> {@code $_SERVER} and {@code $_FILES} are absent on
 * purpose. {@code $_SERVER} mixes the request's headers with the server's own configuration, so
 * marking the whole array untrusted claims values the attacker does not control; {@code $_FILES}
 * holds a temporary name PHP generated. Naming them would trade a precise rule for a noisy one.
 */
@DisplayName("Taint: the sources that are not calls")
class PhpSourceVariableTest {

  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  private static TacOperand.Const str(String value) {
    return TacOperand.Const.ofString(value);
  }

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  private static TacOperand.TacVar local(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, false);
  }

  private static TacInstruction arrayLoad(TacOperand dest, TacOperand base, String key, int line) {
    return TacInstruction.builder(TacInstruction.Kind.ARRAYLOAD)
        .dest(dest)
        .lhs1(base)
        .lhs2(str(key))
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction blackBox(
      String callee, TacOperand dest, List<TacOperand> args, int line) {
    return TacInstruction.builder(TacInstruction.Kind.UNINTERPRETED)
        .calleeSig(callee)
        .dest(dest)
        .args(args)
        .line(line)
        .column(1)
        .build();
  }

  /**
   * The PHP handler, as the front end lowers it.
   *
   * <p>The value is read out of a container and handed to a built-in:
   *
   * <pre>
   *   $q = $_GET['user'];
   *   system($q);
   * </pre>
   *
   * @param language the language the function is written in
   * @param base the variable the value is read out of
   * @return the function under analysis
   */
  private static TacFunction handler(SourceLanguage language, String base) {
    TacOperand.TacVar value = local("q");
    TacOperand.TacVar container = local(base);

    List<TacInstruction> instructions = new ArrayList<>();
    // $q = $_GET['user'];
    instructions.add(arrayLoad(temp("t_t1"), container, "user", 3));
    instructions.add(move(value, temp("t_t1"), 3));
    // system($q);
    instructions.add(blackBox("\\system/1", temp("t_t2"), List.of(str(""), value), 5));
    return new TacFunction(
        "handle",
        "app\\handle():void",
        TacType.VOID,
        List.of(),
        List.of(value, container),
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        language,
        IrPrecision.SOURCE_FULL);
  }

  private List<Finding> scan(SourceLanguage language, String base) {
    TacProgram program = new TacProgram(Path.of("handler.php"), "app");
    program.addFunction(handler(language, base));
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new CommandInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  /**
   * Run the source through the front end, then through the engine.
   *
   * @param frontend the front end that lowers the source
   * @param source the source text
   * @param file the file the source came from
   * @return the findings the engine reported
   */
  private static List<Finding> scan(TacFrontend frontend, String source, Path file) {
    TacFrontend.ParseResult result = frontend.parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new CommandInjectionChecker()), "global", null)
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
   * Whether the PHP front end can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the package the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean phpAvailable() {
    Path node = PhpSidecarClient.locateNode();
    Path script = PhpSidecarClient.locateScript(null);
    if (node == null || script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("php-parser"));
  }

  private static boolean hasFinding(List<Finding> findings, String ruleId, String callee) {
    for (Finding f : findings) {
      if (!f.ruleId().equals(ruleId)) continue;
      if (f.message().contains(callee)) return true;
    }
    return false;
  }

  // ------------------------------------------------------------------ catalogue

  @Test
  @DisplayName("the catalogue declares the superglobals for PHP")
  void theCatalogueDeclaresTheSuperglobalsForPhp() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(config.sinkFamilies().sourceVariableNames(SourceLanguage.PHP))
        .as("the request's query string, body, merged request and cookies")
        .contains("_GET", "_POST", "_REQUEST", "_COOKIE")
        .contains("_ENV");
    assertThat(config.sinkFamilies().sourceVariableNames(SourceLanguage.JAVA))
        .as("a Java program has no superglobals")
        .isEmpty();
    assertThat(config.sinkFamilies().sourceVariables().seeds(SourceLanguage.TYPESCRIPT))
        .as("the script languages have no variable of their own: what they read is an ENTRY")
        .isEmpty();
  }

  @Test
  @DisplayName("the filter API is a source, because it reads the request")
  void theFilterApiIsASourceBecauseItReadsTheRequest() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.web")
                .orElseThrow()
                .matches("\\filter_input/3", SourceLanguage.PHP))
        .as("filter_input reads a value of the request")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.web")
                .orElseThrow()
                .matches("\\filter_input_array/1", SourceLanguage.PHP))
        .as("and filter_input_array reads the whole request")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.web")
                .orElseThrow()
                .matches("\\filter_input/3", SourceLanguage.JAVA))
        .as("a Java program has no PHP built-ins")
        .isFalse();
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("the real front end's superglobal reaches the command")
  void theRealFrontEndsSuperglobalReachesTheCommand(@TempDir Path directory) throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    String source =
        """
        <?php
        function handler(): void {
            $q = $_GET['user'];
            system($q);
        }
        """;
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    List<Finding> findings = scan(new PhpTacFrontend(), source, file);

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/"))
        .as("the query string is the request, and the request reached the shell")
        .isTrue();
  }

  @Test
  @DisplayName("a value read out of a superglobal reaches the command")
  void aValueReadOutOfASuperglobalReachesTheCommand() {
    List<Finding> findings = scan(SourceLanguage.PHP, "_GET");

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as("the query string is the request, and the request reached the shell")
        .isTrue();
  }

  @Test
  @DisplayName("a value read out of an ordinary local reaches nothing")
  void aValueReadOutOfAnOrdinaryLocalReachesNothing() {
    List<Finding> findings = scan(SourceLanguage.PHP, "local");

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as("the program chose that value itself")
        .isFalse();
  }

  @Test
  @DisplayName("the superglobal is a source under either spelling of its name")
  void theSuperglobalIsASourceUnderEitherSpellingOfItsName() {
    List<Finding> findings = scan(SourceLanguage.PHP, "$_GET");

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as("the sigil is not part of the variable's name, so it must not matter")
        .isTrue();
  }

  @Test
  @DisplayName("a variable that only looks like the superglobal is not one")
  void aVariableThatOnlyLooksLikeTheSuperglobalIsNotOne() {
    List<Finding> findings = scan(SourceLanguage.PHP, "$GET");

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as("the program chose that value itself")
        .isFalse();
  }

  @Test
  @DisplayName("the same program in Java reports nothing, because the name is PHP's")
  void theSameProgramInJavaReportsNothingBecauseTheNameIsPhps() {
    List<Finding> findings = scan(SourceLanguage.JAVA, "_GET");

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as("a Java variable called _GET is not the request")
        .isFalse();
  }
}
