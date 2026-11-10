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
import org.plumb.dfa.checkers.taint.PathTraversalChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.checkers.taint.XssInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
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
 * The TypeScript and JavaScript rule layer: the sink catalogue and the library semantics.
 *
 * <p><b>Why the instructions are written out by hand.</b> The catalogue names callee signatures,
 * and a signature the front end never emits is a pattern that never fires. The instructions below
 * are the TAC the TypeScript side car produced for the corresponding TypeScript source, read back
 * out of the TAC: a property read is an ARRAYLOAD with a string key, a call keeps the receiver in
 * args[0], and a free function puts the module's name in that slot instead.
 *
 * <p><b>Why the callee keys name a MODULE and not a class.</b> A script language reaches its
 * libraries through modules: {@code child_process.exec} is a function of the {@code child_process}
 * module, and there is no class to name. The catalogue therefore names the module the function was
 * imported from, which is what the front end is specified to emit.
 *
 * <p><b>Why that matters for precision.</b> A catalogue that named the method alone would claim
 * every {@code exec} in the program -- including {@code RegExp.exec}, which runs a regular
 * expression and not a command, and it is the single most common method name in a script program.
 * Naming the module is what keeps the rule from claiming it.
 *
 * <p><b>Why the string operations are a METHOD-NAME table.</b> A JavaScript string is a primitive,
 * and a primitive type carries no class symbol: measured against the TypeScript compiler, the type
 * of a string receiver reports no symbol at all, while an array receiver reports {@code Array} and
 * a class instance reports its class. So the operations that carry the taint through a script
 * program -- the string ones -- are exactly the ones a table keyed on owners could not name. The
 * semantics table names the operation instead.
 *
 * <p><b>What that costs, and why it is the right trade.</b> A method-name entry cannot tell a
 * string's {@code trim} from a user object's {@code trim}. The risk is a rule that fires where it
 * should not, and it is why the table lists only operations whose behaviour is the same wherever
 * they are spelled: a trim keeps the content's taint whether it is called on a string or on
 * anything else.
 */
@DisplayName("Taint: the TypeScript and JavaScript rule layer")
class TsSinkCatalogTest {

  private static final String COMMAND_RULE = "dfa-taint-command-injection";
  private static final String PATH_RULE = "dfa-taint-path-traversal";
  private static final String LOG_RULE = "dfa-taint-log-injection";
  private static final String SQL_RULE = "dfa-taint-sql-injection";
  private static final String XSS_RULE = "dfa-taint-xss-injection";

  /** The module the free function came from: the receiver slot of a module-level call. */
  private static TacOperand.Const str(String value) {
    return TacOperand.Const.ofString(value);
  }

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

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  /**
   * The TypeScript handler, as the front end lowers it.
   *
   * <p>The shape is the one a script program has: the request arrives as a parameter, a property
   * read is a container access, and the command is run through the module's function.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction handler(SourceLanguage language) {
    TacOperand.TacVar req = new TacOperand.TacVar("req", TacType.UNKNOWN, false);
    TacOperand.TacVar cp = new TacOperand.TacVar("cp", TacType.UNKNOWN, false);
    TacOperand.TacVar input = new TacOperand.TacVar("input", TacType.STRING, false);
    TacOperand.TacVar trimmed = temp("trimmed");

    List<TacInstruction> instructions = new ArrayList<>();
    // const input = req.query.userId;
    instructions.add(move(input, req, 10));
    // const trimmed = input.trim();
    // The receiver has no class to name, so the owner is the stand-in for an
    // unknown one: <unknown>. It cannot be a module path, so a catalogue that
    // names modules cannot claim it by accident.
    instructions.add(call("<unknown>.trim/0", trimmed, List.of(input), 12));
    // cp.exec(trimmed);
    instructions.add(call("child_process.exec/2", temp("t9"), List.of(cp, trimmed), 15));
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(req),
        List.of(cp, input, trimmed),
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
    TacProgram program = new TacProgram(Path.of("app.ts"), "app");
    program.addFunction(handler(language));
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(
                config,
                List.of(new CommandInjectionChecker(), new PathTraversalChecker()),
                "global",
                null)
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
  @DisplayName("the catalogue names the Node APIs the script languages can reach")
  void theCatalogueNamesTheNodeApis() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("child_process.exec/2", SourceLanguage.TYPESCRIPT))
        .as("child_process.exec is a command sink")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("node:child_process.execSync/2", SourceLanguage.TYPESCRIPT))
        .as("the same module under the name Node prefers")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("file-path.sinks")
                .orElseThrow()
                .matches("fs.readFileSync/2", SourceLanguage.TYPESCRIPT))
        .as("fs.readFileSync is a path sink")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("http.get/3", SourceLanguage.TYPESCRIPT))
        .as("http.get is a request sink")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("code-injection.sinks")
                .orElseThrow()
                .matches("vm.runInNewContext/3", SourceLanguage.TYPESCRIPT))
        .as("vm.runInNewContext evaluates code")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("code-injection.sinks")
                .orElseThrow()
                .matches("<unknown>.eval/1", SourceLanguage.TYPESCRIPT))
        .as("the global eval evaluates code, and its owner is the stand-in for an unknown one")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("<unknown>.fetch/2", SourceLanguage.TYPESCRIPT))
        .as("the global fetch sends its argument to a URL, and its owner is the same stand-in")
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
                .matches("RegExp.exec/1", SourceLanguage.TYPESCRIPT))
        .as("RegExp.exec runs a regular expression, not a command")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("someexec/1", SourceLanguage.TYPESCRIPT))
        .as("a name that ends in exec is not a command")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("code-injection.sinks")
                .orElseThrow()
                .matches("app.evaluate/1", SourceLanguage.TYPESCRIPT))
        .as("evaluate is not eval")
        .isFalse();
  }

  @Test
  @DisplayName("the catalogue is scoped to the script languages")
  void theCatalogueIsScopedToTheScriptLanguages() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("child_process.exec/2", SourceLanguage.JAVASCRIPT))
        .as("the same rule serves JavaScript")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("child_process.exec/2", SourceLanguage.JAVA))
        .as("a Java program has no child_process")
        .isFalse();
  }

  @Test
  @DisplayName("a file read is a source in a script language")
  void aFileReadIsASource() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.io")
                .orElseThrow()
                .matches("fs.readFileSync/2", SourceLanguage.TYPESCRIPT))
        .as("the content of a file is data the program did not write")
        .isTrue();
  }

  // ------------------------------------------------------------------ semantics

  @Test
  @DisplayName("the script table names the operation rather than the owner")
  void theScriptTableNamesTheOperationRatherThanTheOwner() {
    LanguageSemantics table =
        LanguageSemanticsRegistry.withDefaults().forLanguage(SourceLanguage.TYPESCRIPT);

    assertThat(table.behaviourOf("app.trim/0"))
        .as("a trim keeps the content's taint, whichever owner spells it")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(table.behaviourOf("<unknown>.trim/0"))
        .as("the stand-in owner spells the method the same way")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(table.behaviourOf("app.substring/2"))
        .as("a substring is a piece of the input")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(table.behaviourOf("app.parseInt/2"))
        .as("a parsed number is not the text it was parsed from")
        .isEqualTo(LanguageSemantics.TaintBehaviour.DISSOCIATES);
    assertThat(
            LanguageSemanticsRegistry.withDefaults()
                .forLanguage(SourceLanguage.JAVASCRIPT)
                .behaviourOf("app.trim/0"))
        .as("JavaScript has the same operations")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(
            LanguageSemanticsRegistry.withDefaults()
                .forLanguage(SourceLanguage.ARKTS)
                .behaviourOf("app.trim/0"))
        .as("ArkTS has the same operations")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(
            LanguageSemanticsRegistry.withDefaults()
                .forLanguage(SourceLanguage.GROOVY)
                .behaviourOf("app.trim/0"))
        .as("a language with no table has no claims")
        .isEqualTo(LanguageSemantics.TaintBehaviour.UNKNOWN);
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("a tainted value that crosses a string operation still reaches the command")
  void aTaintedValueThatCrossesAStringOperationStillReachesTheCommand() {
    List<Finding> findings = scan(SourceLanguage.TYPESCRIPT);

    assertThat(hasFinding(findings, COMMAND_RULE, "child_process.exec/2"))
        .as("the command call must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  @Test
  @DisplayName("the same program in Java reports nothing, because Java has no such catalogue entry")
  void theSameProgramInJavaReportsNothing() {
    List<Finding> findings = scan(SourceLanguage.JAVA);

    assertThat(hasFinding(findings, COMMAND_RULE, "child_process.exec/2"))
        .as("the rule is scoped to the script languages")
        .isFalse();
  }

  // ------------------------------------------------------- console and database

  /**
   * The script handler that writes to the console, as the front end lowers it.
   *
   * <p>Measured against the TypeScript side car: the receiver slot holds the console object and the
   * message is the FIRST user argument, so a pattern that names the console has to look at argument
   * zero and not at the receiver.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction consoleHandler(SourceLanguage language) {
    TacOperand.TacVar req = new TacOperand.TacVar("req", TacType.UNKNOWN, false);
    TacOperand.TacVar console = new TacOperand.TacVar("console", TacType.UNKNOWN, false);
    TacOperand.TacVar input = new TacOperand.TacVar("input", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(input, req, 10));
    // console.log(input);
    instructions.add(call("Console.log/1", temp("t9"), List.of(console, input), 12));
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(req),
        List.of(console, input),
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
   * The script handler that queries a database, as the front end lowers it.
   *
   * <p>Measured against the TypeScript side car: the receiver slot holds the MODULE the client was
   * imported from and the statement is the first user argument.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction queryHandler(SourceLanguage language) {
    TacOperand.TacVar req = new TacOperand.TacVar("req", TacType.UNKNOWN, false);
    TacOperand.TacVar client = new TacOperand.TacVar("mysql", TacType.UNKNOWN, false);
    TacOperand.TacVar input = new TacOperand.TacVar("input", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(input, req, 10));
    // mysql.query(input);
    instructions.add(call("mysql.query/0", temp("t9"), List.of(client, input), 12));
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(req),
        List.of(client, input),
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
    TacProgram program = new TacProgram(Path.of("app.ts"), "app");
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
  @DisplayName("the catalogue names the runtime console and the database clients")
  void theCatalogueNamesTheRuntimeConsoleAndTheDatabaseClients() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("Console.log/1", SourceLanguage.TYPESCRIPT))
        .as("the console writes what it is given")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("mysql.query/0", SourceLanguage.TYPESCRIPT))
        .as("a database client sends the statement to the database")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("knex.raw/0", SourceLanguage.TYPESCRIPT))
        .as("a query builder sends raw SQL")
        .isTrue();
  }

  @Test
  @DisplayName("the script buckets do not reach into another language")
  void theScriptBucketsDoNotReachIntoAnotherLanguage() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("Console.log/1", SourceLanguage.JAVA))
        .as("a Java program has no console object")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("mysql.query/0", SourceLanguage.JAVA))
        .as("a Java program has no mysql module")
        .isFalse();
  }

  @Test
  @DisplayName("the console and the client buckets do not claim a name on its own")
  void theConsoleAndTheClientBucketsDoNotClaimANameOnItsOwn() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("Console.logger/1", SourceLanguage.TYPESCRIPT))
        .as("a name that starts with log is not a log call")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("mysql.queryAll/0", SourceLanguage.TYPESCRIPT))
        .as("a name that starts with query is not a query")
        .isFalse();
  }

  @Test
  @DisplayName("a tainted value reaches the console")
  void aTaintedValueReachesTheConsole() {
    List<Finding> findings =
        scan(
            SourceLanguage.TYPESCRIPT,
            consoleHandler(SourceLanguage.TYPESCRIPT),
            new LogInjectionChecker());

    assertThat(hasFinding(findings, LOG_RULE, "Console.log/1"))
        .as("the console call must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted value reaches the database client")
  void aTaintedValueReachesTheDatabaseClient() {
    List<Finding> findings =
        scan(
            SourceLanguage.TYPESCRIPT,
            queryHandler(SourceLanguage.TYPESCRIPT),
            new SqlInjectionChecker());

    assertThat(hasFinding(findings, SQL_RULE, "mysql.query/0"))
        .as("the query call must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  @Test
  @DisplayName("the tainted receiver is not the message")
  void theTaintedReceiverIsNotTheMessage() {
    TacOperand.TacVar req = new TacOperand.TacVar("req", TacType.UNKNOWN, false);
    TacOperand.TacVar console = new TacOperand.TacVar("console", TacType.STRING, false);
    TacOperand.TacVar input = new TacOperand.TacVar("input", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(console, req, 10));
    instructions.add(call("Console.log/1", temp("t9"), List.of(console, input), 12));
    TacFunction function =
        new TacFunction(
            "handler",
            "app.handler(string):string",
            TacType.STRING,
            List.of(req),
            List.of(console, input),
            instructions,
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            null,
            SourceOrigin.SOURCE,
            List.of(),
            SourceLanguage.TYPESCRIPT,
            IrPrecision.SOURCE_FULL);

    List<Finding> findings = scan(SourceLanguage.TYPESCRIPT, function, new LogInjectionChecker());

    assertThat(hasFinding(findings, LOG_RULE, "Console.log/1"))
        .as("the message is the first user argument; the receiver is not the message")
        .isFalse();
  }

  // --------------------------------------------------- the page and the format

  /**
   * The script handler that writes into the page, as the front end lowers it.
   *
   * <p>Measured against the TypeScript side car: the owner of {@code document.write} is the {@code
   * Document} TYPE, not the module the page came from, and the text is the first user argument.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction documentHandler(SourceLanguage language) {
    TacOperand.TacVar req = new TacOperand.TacVar("req", TacType.UNKNOWN, false);
    TacOperand.TacVar document = new TacOperand.TacVar("document", TacType.UNKNOWN, false);
    TacOperand.TacVar input = new TacOperand.TacVar("input", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(input, req, 10));
    // document.write(input);
    instructions.add(call("Document.write/1", temp("t9"), List.of(document, input), 12));
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(req),
        List.of(document, input),
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
  @DisplayName("the catalogue names the page and the format of the script languages")
  void theCatalogueNamesThePageAndTheFormatOfTheScriptLanguages() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("xss.sinks")
                .orElseThrow()
                .matches("Document.write/1", SourceLanguage.TYPESCRIPT))
        .as("document.write puts the text into the page as markup")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("format-string.sinks")
                .orElseThrow()
                .matches("util.format/0", SourceLanguage.TYPESCRIPT))
        .as("util.format takes the format string first")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("format-string.sinks")
                .orElseThrow()
                .matches("node:util.format/0", SourceLanguage.TYPESCRIPT))
        .as("the same module under the name Node prefers")
        .isTrue();
  }

  @Test
  @DisplayName("the page and the format buckets do not claim a name on its own")
  void thePageAndTheFormatBucketsDoNotClaimANameOnItsOwn() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("xss.sinks")
                .orElseThrow()
                .matches("Document.writeAll/1", SourceLanguage.TYPESCRIPT))
        .as("a name that starts with write is not a write")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("format-string.sinks")
                .orElseThrow()
                .matches("util.formatAll/0", SourceLanguage.TYPESCRIPT))
        .as("a name that starts with format is not a format")
        .isFalse();
  }

  @Test
  @DisplayName("a tainted value reaches the page")
  void aTaintedValueReachesThePage() {
    List<Finding> findings =
        scan(
            SourceLanguage.TYPESCRIPT,
            documentHandler(SourceLanguage.TYPESCRIPT),
            new XssInjectionChecker());

    assertThat(hasFinding(findings, XSS_RULE, "Document.write/1"))
        .as("the write must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  @Test
  @DisplayName("no family claims a call whose owner nobody could name")
  void noFamilyClaimsACallWhoseOwnerNobodyCouldName() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    for (SinkFamily family : config.sinkFamilies().all()) {
      assertThat(family.matches("<unknown>.error/1", SourceLanguage.TYPESCRIPT))
          .as(
              "%s must not claim a call whose owner is the stand-in for an unknown one",
              family.name())
          .isFalse();
    }
  }

  @Test
  @DisplayName("the catalogue still claims the call once its owner can be named")
  void theCatalogueStillClaimsTheCallOnceItsOwnerCanBeNamed() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    // The test above can go green with an empty catalogue, so it needs a
    // pair: the same call, with an owner somebody could name, must still
    // be claimed. console.error writes to the log, and the catalogue
    // names its owner the Console type.
    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("Console.error/1", SourceLanguage.TYPESCRIPT))
        .as("console.error writes to the log, and its owner is the Console type")
        .isTrue();
  }

  @Test
  @DisplayName("a method of the same name on an object is not the global")
  void aMethodOfTheSameNameOnAnObjectIsNotTheGlobal() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily codeInjection = config.sinkFamilies().byName("code-injection.sinks").orElseThrow();
    SinkFamily ssrf = config.sinkFamilies().byName("ssrf.sinks").orElseThrow();

    // The two patterns that name the stand-in claim the GLOBAL function and nothing else:
    // a method of the same name on some object is a different thing.
    assertThat(codeInjection.matches("__object.eval/0", SourceLanguage.TYPESCRIPT))
        .as("a method called eval on an object is not the global eval")
        .isFalse();
    assertThat(ssrf.matches("__object.fetch/0", SourceLanguage.TYPESCRIPT))
        .as("a method called fetch on an object is not the global fetch")
        .isFalse();
    assertThat(codeInjection.matches("app.eval/1", SourceLanguage.TYPESCRIPT))
        .as("a function the file declares is not the global eval either")
        .isFalse();
  }
}
