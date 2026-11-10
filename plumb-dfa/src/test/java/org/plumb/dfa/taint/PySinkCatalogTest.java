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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.checkers.taint.LogInjectionChecker;
import org.plumb.dfa.checkers.taint.RegexInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.semantics.LanguageSemantics;
import org.plumb.dfa.semantics.LanguageSemanticsRegistry;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The Python rule layer: the sink catalogue and the library semantics.
 *
 * <p><b>Why the instructions are written out by hand.</b> The catalogue names callee signatures,
 * and a signature the front end never emits is a pattern that never fires. The instructions below
 * are the TAC the Python side car produced for the corresponding Python source, read back out of
 * the side car's own output: a module-level call keeps the MODULE in the receiver slot, and a call
 * whose receiver's type the front end could not prove keeps the MODULE OF THE FILE in that slot
 * instead and is modelled as a black box.
 *
 * <p><b>Why the owner is a module and not a class.</b> Python reaches its libraries through
 * modules: {@code os.system} is a function of the {@code os} module, and there is no class to name.
 * The front end resolves the alias away as well -- {@code from os import system} arrives as {@code
 * os.system}, not as {@code system}.
 *
 * <p><b>Why that matters for precision.</b> A catalogue that named the method alone would claim
 * every {@code loads} and every {@code exec} in the program -- including {@code json.loads}, which
 * parses JSON and does not execute anything, and {@code RegExp.exec}, which runs a regular
 * expression. Naming the module is what keeps the rule from claiming them.
 *
 * <p><b>Why the string operations are a METHOD-NAME table.</b> A Python string is a primitive, and
 * a primitive type carries no class symbol. Measured against the Python side car: the owner of a
 * string method on an ordinary variable is the module the file belongs to, and only a parameter
 * WITH an annotation arrives with a type. So the operations that carry the taint through a Python
 * program -- the string ones -- are exactly the ones a table keyed on owners could not name. The
 * semantics table names the operation instead.
 */
@DisplayName("Taint: the Python rule layer")
class PySinkCatalogTest {

  private static final String COMMAND_RULE = "dfa-taint-command-injection";
  private static final String LOG_RULE = "dfa-taint-log-injection";
  private static final String REGEX_RULE = "dfa-taint-regex-injection";

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  private static TacInstruction call(
      String callee, TacOperand dest, List<TacOperand> args, int line) {
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .calleeSig(callee)
        .dest(dest)
        .args(args)
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

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  /**
   * The Python handler, as the front end lowers it.
   *
   * <p>The shape is the one a Python program has: the request arrives as a parameter, the value is
   * read out of it, and the command is run through the module's function.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction handler(SourceLanguage language) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar name = new TacOperand.TacVar("name", TacType.STRING, false);
    TacOperand.TacVar cleaned = temp("cleaned");

    List<TacInstruction> instructions = new ArrayList<>();
    // name = request["user"]
    instructions.add(move(name, request, 10));
    // cleaned = name.strip()
    // The front end could not prove the receiver's type, so the owner is the module the
    // FILE belongs to and the call is modelled as a black box.
    instructions.add(blackBox("app.strip/0", cleaned, List.of(name), 12));
    // os.system(cleaned)
    // The receiver slot of a module-level call holds the MODULE the function came from.
    instructions.add(
        call("os.system/1", temp("t9"), List.of(TacOperand.Const.ofString("os"), cleaned), 14));
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(request),
        List.of(name, cleaned),
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

  private List<Finding> scan(SourceLanguage language) {
    TacProgram program = new TacProgram(Path.of("app.py"), "app");
    program.addFunction(handler(language));
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

  private static boolean hasFinding(List<Finding> findings, String ruleId, String callee) {
    for (Finding f : findings) {
      if (!f.ruleId().equals(ruleId)) continue;
      if (f.message().contains(callee)) return true;
    }
    return false;
  }

  // ------------------------------------------------------------------ catalogue

  @Test
  @DisplayName("the catalogue names the Python APIs")
  void theCatalogueNamesThePythonApis() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("os.system/1", SourceLanguage.PYTHON3))
        .as("os.system runs its argument through the shell")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("subprocess.run/1", SourceLanguage.PYTHON3))
        .as("the subprocess family runs a command")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("code-injection.sinks")
                .orElseThrow()
                .matches("builtins.eval/1", SourceLanguage.PYTHON3))
        .as("the builtin eval evaluates code")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("deserialization.sinks")
                .orElseThrow()
                .matches("pickle.loads/1", SourceLanguage.PYTHON3))
        .as("pickle builds objects out of bytes")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("urllib.request.urlopen/1", SourceLanguage.PYTHON3))
        .as("a request to a URL the program did not choose")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("file-path.sinks")
                .orElseThrow()
                .matches("os.path.join/2", SourceLanguage.PYTHON3))
        .as("a path built from a tainted segment")
        .isTrue();
  }

  @Test
  @DisplayName("the catalogue does not claim a method name on its own")
  void theCatalogueDoesNotClaimAMethodNameOnItsOwn() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("app.exec/0", SourceLanguage.PYTHON3))
        .as("a method that happens to be called exec is not a command")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("deserialization.sinks")
                .orElseThrow()
                .matches("json.loads/1", SourceLanguage.PYTHON3))
        .as("json.loads parses JSON and does not build objects")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("code-injection.sinks")
                .orElseThrow()
                .matches("app.evaluate/1", SourceLanguage.PYTHON3))
        .as("evaluate is not eval")
        .isFalse();
  }

  @Test
  @DisplayName("the catalogue is scoped to Python")
  void theCatalogueIsScopedToPython() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("os.system/1", SourceLanguage.PYTHON3))
        .as("the rule serves Python")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("os.system/1", SourceLanguage.JAVA))
        .as("a Java program has no os module")
        .isFalse();
  }

  @Test
  @DisplayName("the console and the environment are sources")
  void theConsoleAndTheEnvironmentAreSources() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.console")
                .orElseThrow()
                .matches("builtins.input/0", SourceLanguage.PYTHON3))
        .as("whatever the user types is attacker-controlled")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.env")
                .orElseThrow()
                .matches("os.environ.get/1", SourceLanguage.PYTHON3))
        .as("the program did not choose the value of an environment variable")
        .isTrue();
  }

  // ------------------------------------------------------------------ semantics

  @Test
  @DisplayName("the Python table names the operation rather than the owner")
  void thePythonTableNamesTheOperationRatherThanTheOwner() {
    LanguageSemantics table =
        LanguageSemanticsRegistry.withDefaults().forLanguage(SourceLanguage.PYTHON3);

    assertThat(table.behaviourOf("app.strip/0"))
        .as("a strip keeps the content's taint, whichever owner spells it")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(table.behaviourOf("app.lower/0"))
        .as("Python names the case conversions without the word Case")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(table.behaviourOf("app.format/1"))
        .as("a formatted string carries the taint of what was formatted into it")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(table.behaviourOf("builtins.int/1"))
        .as("a parsed number is not the text it was parsed from")
        .isEqualTo(LanguageSemantics.TaintBehaviour.DISSOCIATES);
    assertThat(
            LanguageSemanticsRegistry.withDefaults()
                .forLanguage(SourceLanguage.JAVA)
                .behaviourOf("app.strip/0"))
        .as("a language with no table has no claims")
        .isEqualTo(LanguageSemantics.TaintBehaviour.UNKNOWN);
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("a tainted value that crosses a string operation still reaches the command")
  void aTaintedValueThatCrossesAStringOperationStillReachesTheCommand() {
    List<Finding> findings = scan(SourceLanguage.PYTHON3);

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system/1"))
        .as("the command call must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  @Test
  @DisplayName("the same program in Java reports nothing, because Java has no such catalogue entry")
  void theSameProgramInJavaReportsNothing() {
    List<Finding> findings = scan(SourceLanguage.JAVA);

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system/1"))
        .as("the rule is scoped to Python")
        .isFalse();
  }

  // ------------------------------------------------------------------- logging

  /**
   * The Python handler that writes to the log, as the front end lowers it.
   *
   * <p>Measured against the Python side car: the receiver slot holds the MODULE the function came
   * from and the message is the first user argument.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction logHandler(SourceLanguage language) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar module = new TacOperand.TacVar("logging", TacType.UNKNOWN, false);
    TacOperand.TacVar name = new TacOperand.TacVar("name", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(name, request, 10));
    // logging.info(name)
    instructions.add(call("logging.info/1", temp("t9"), List.of(module, name), 12));
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(request),
        List.of(module, name),
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

  /**
   * Scan a function with the given checkers.
   *
   * @param language the language the function is written in
   * @param function the function under analysis
   * @param checkers the checkers to run
   * @return the taint findings
   */
  private List<Finding> scan(
      SourceLanguage language, TacFunction function, DfaChecker... checkers) {
    TacProgram program = new TacProgram(Path.of("app.py"), "app");
    program.addFunction(function);
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checkers), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  @Test
  @DisplayName("the catalogue names the logging module")
  void theCatalogueNamesTheLoggingModule() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("logging.info/1", SourceLanguage.PYTHON3))
        .as("the logging module writes what it is given")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("logging.info/1", SourceLanguage.TYPESCRIPT))
        .as("a TypeScript program has no logging module")
        .isFalse();
  }

  @Test
  @DisplayName("the logging bucket does not claim a name on its own")
  void theLoggingBucketDoesNotClaimANameOnItsOwn() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("logging.infox/1", SourceLanguage.PYTHON3))
        .as("a name that starts with info is not a log call")
        .isFalse();
  }

  @Test
  @DisplayName("a tainted value reaches the log")
  void aTaintedValueReachesTheLog() {
    List<Finding> findings =
        scan(SourceLanguage.PYTHON3, logHandler(SourceLanguage.PYTHON3), new LogInjectionChecker());

    assertThat(hasFinding(findings, LOG_RULE, "logging.info/1"))
        .as("the log call must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  @Test
  @DisplayName("the tainted module is not the message")
  void theTaintedModuleIsNotTheMessage() {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar module = new TacOperand.TacVar("logging", TacType.STRING, false);
    TacOperand.TacVar name = new TacOperand.TacVar("name", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(module, request, 10));
    instructions.add(call("logging.info/1", temp("t9"), List.of(module, name), 12));
    TacFunction function =
        new TacFunction(
            "handler",
            "app.handler(string):string",
            TacType.STRING,
            List.of(request),
            List.of(module, name),
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

    List<Finding> findings = scan(SourceLanguage.PYTHON3, function, new LogInjectionChecker());

    assertThat(hasFinding(findings, LOG_RULE, "logging.info/1"))
        .as("the message is the first user argument; the module is not the message")
        .isFalse();
  }

  // ------------------------------------------------------------ the regex

  /**
   * The Python handler that compiles a pattern, as the front end lowers it.
   *
   * <p>Measured against the Python side car: the receiver slot holds the MODULE the function came
   * from, and the pattern is the first user argument.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction regexHandler(SourceLanguage language) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar module = new TacOperand.TacVar("re", TacType.UNKNOWN, false);
    TacOperand.TacVar pattern = new TacOperand.TacVar("pattern", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(pattern, request, 10));
    // re.compile(pattern)
    instructions.add(call("re.compile/1", temp("t9"), List.of(module, pattern), 12));
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(request),
        List.of(module, pattern),
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

  @Test
  @DisplayName("the catalogue names the regex and the page of the script languages")
  void theCatalogueNamesTheRegexAndThePageOfTheScriptLanguages() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("regex.sinks")
                .orElseThrow()
                .matches("re.compile/1", SourceLanguage.PYTHON3))
        .as("a pattern the program did not choose")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("xss.sinks")
                .orElseThrow()
                .matches("django.utils.html.mark_safe/1", SourceLanguage.PYTHON3))
        .as("mark_safe says 'do not escape this'")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("open-redirect.sinks")
                .orElseThrow()
                .matches("django.shortcuts.redirect/1", SourceLanguage.PYTHON3))
        .as("the target of a redirect the program did not choose")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.network")
                .orElseThrow()
                .matches("socket.recv/1", SourceLanguage.PYTHON3))
        .as("the bytes that arrived over the socket")
        .isTrue();
  }

  @Test
  @DisplayName("the regex bucket does not claim the function that escapes a pattern")
  void theRegexBucketDoesNotClaimTheFunctionThatEscapesAPattern() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("regex.sinks")
                .orElseThrow()
                .matches("re.escape/1", SourceLanguage.PYTHON3))
        .as("re.escape escapes a pattern rather than using one")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("xss.sinks")
                .orElseThrow()
                .matches("django.utils.html.escape/1", SourceLanguage.PYTHON3))
        .as("escape is the safe one")
        .isFalse();
  }

  @Test
  @DisplayName("a tainted value reaches the pattern")
  void aTaintedValueReachesThePattern() {
    List<Finding> findings =
        scan(
            SourceLanguage.PYTHON3,
            regexHandler(SourceLanguage.PYTHON3),
            new RegexInjectionChecker());

    assertThat(hasFinding(findings, REGEX_RULE, "re.compile/1"))
        .as("the compile must be recognised, and the value must still be tainted there")
        .isTrue();
  }
}
