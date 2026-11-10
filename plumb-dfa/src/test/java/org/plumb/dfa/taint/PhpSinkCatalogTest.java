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
import org.plumb.dfa.checkers.taint.FormatStringChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
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
 * The PHP rule layer: the sink catalogue and the library semantics.
 *
 * <p><b>Why the instructions are written out by hand.</b> The catalogue names callee signatures,
 * and a signature the front end never emits is a pattern that never fires. The instructions below
 * are the TAC the PHP side car produced for the corresponding PHP source, read back out of the side
 * car's own output.
 *
 * <p><b>Why the owner is a backslash.</b> PHP qualifies a name with its namespace, and a global
 * function is written with a leading backslash: {@code system} arrives as {@code \system/1}, a
 * namespaced one as {@code App\Support\sanitize/1}, and a static method with {@code ::} as {@code
 * App\Http\Greeter::greet/1}. There is no class to name, so the catalogue names the qualified name
 * PHP itself writes.
 *
 * <p><b>Why a built-in is a black box.</b> The front end did not resolve {@code system}, so it
 * emits an UNINTERPRETED call rather than a CALL. That is what makes the semantics table load
 * bearing here: a black box carries its arguments' taint only if the table says the operation can,
 * so the DISSOCIATES entries are what stop the taint at the calls that really do drop it.
 *
 * <p><b>Why the catalogue does not claim a name on its own.</b> The engine matches a pattern as a
 * SUBSTRING, so a pattern that named {@code system} without a boundary would also claim {@code
 * systemctl} -- and a project that defines its own {@code system_info} would be reported as command
 * injection. Every pattern in the PHP bucket ends its name with a word boundary.
 */
@DisplayName("Taint: the PHP rule layer")
class PhpSinkCatalogTest {

  private static final String COMMAND_RULE = "dfa-taint-command-injection";
  private static final String SQL_RULE = "dfa-taint-sql-injection";
  private static final String FORMAT_RULE = "dfa-taint-format-string";

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
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
   * The PHP handler, as the front end lowers it.
   *
   * <p>The shape is the one a PHP program has: the request arrives as a parameter, the value is
   * read out of it, and the command is run through a built-in.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction handler(SourceLanguage language) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar name = new TacOperand.TacVar("name", TacType.STRING, false);
    TacOperand.TacVar cleaned = temp("cleaned");

    List<TacInstruction> instructions = new ArrayList<>();
    // $name = $_GET['user'];
    instructions.add(move(name, request, 10));
    // $cleaned = trim($name);
    // The receiver slot of a global function holds the namespace, which for a
    // global function is empty.
    instructions.add(
        blackBox("\\trim/1", cleaned, List.of(TacOperand.Const.ofString(""), name), 12));
    // system($cleaned);
    instructions.add(
        blackBox("\\system/1", temp("t9"), List.of(TacOperand.Const.ofString(""), cleaned), 14));
    return new TacFunction(
        "handle",
        "app\\handle(string):string",
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
    TacProgram program = new TacProgram(Path.of("handler.php"), "app");
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
  @DisplayName("the catalogue names the PHP APIs")
  void theCatalogueNamesThePhpApis() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("\\system/1", SourceLanguage.PHP))
        .as("system runs its argument through the shell")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("\\shell_exec/1", SourceLanguage.PHP))
        .as("the shell_exec family runs a command")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("deserialization.sinks")
                .orElseThrow()
                .matches("\\unserialize/1", SourceLanguage.PHP))
        .as("unserialize builds objects out of bytes")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("file-inclusion.sinks")
                .orElseThrow()
                .matches("<php>.include/1", SourceLanguage.PHP))
        .as("include is a language construct, so it has a synthetic owner")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("file-path.sinks")
                .orElseThrow()
                .matches("\\file_put_contents/2", SourceLanguage.PHP))
        .as("a path the program did not choose")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("\\curl_setopt/3", SourceLanguage.PHP))
        .as("the URL of a request the program did not choose")
        .isTrue();
  }

  @Test
  @DisplayName("the catalogue does not claim a name on its own")
  void theCatalogueDoesNotClaimANameOnItsOwn() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("\\systemctl/1", SourceLanguage.PHP))
        .as("a name that starts with system is not a command")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("\\str_replace/3", SourceLanguage.PHP))
        .as("a replacement is not a command")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("deserialization.sinks")
                .orElseThrow()
                .matches("\\json_decode/1", SourceLanguage.PHP))
        .as("json_decode parses JSON and does not build objects")
        .isFalse();
  }

  @Test
  @DisplayName("the catalogue is scoped to PHP")
  void theCatalogueIsScopedToPhp() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("\\system/1", SourceLanguage.PHP))
        .as("the rule serves PHP")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("\\system/1", SourceLanguage.JAVA))
        .as("a Java program has no PHP built-ins")
        .isFalse();
  }

  @Test
  @DisplayName("the environment and the file content are sources")
  void theEnvironmentAndTheFileContentAreSources() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.env")
                .orElseThrow()
                .matches("\\getenv/1", SourceLanguage.PHP))
        .as("the program did not choose the value of an environment variable")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.io")
                .orElseThrow()
                .matches("\\file_get_contents/1", SourceLanguage.PHP))
        .as("the content of a file is data the program did not write")
        .isTrue();
  }

  // ------------------------------------------------------------------ semantics

  @Test
  @DisplayName("the PHP table names the operation rather than the owner")
  void thePhpTableNamesTheOperationRatherThanTheOwner() {
    LanguageSemantics table =
        LanguageSemanticsRegistry.withDefaults().forLanguage(SourceLanguage.PHP);

    assertThat(table.behaviourOf("\\trim/1"))
        .as("a trim keeps the content's taint")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(table.behaviourOf("\\str_replace/3"))
        .as("a replacement keeps the content's taint")
        .isEqualTo(LanguageSemantics.TaintBehaviour.PRESERVES);
    assertThat(table.behaviourOf("\\intval/1"))
        .as("a parsed number is not the text it was parsed from")
        .isEqualTo(LanguageSemantics.TaintBehaviour.DISSOCIATES);
    assertThat(table.behaviourOf("\\md5/1"))
        .as("a digest is not the text either")
        .isEqualTo(LanguageSemantics.TaintBehaviour.DISSOCIATES);
    assertThat(
            LanguageSemanticsRegistry.withDefaults()
                .forLanguage(SourceLanguage.JAVA)
                .behaviourOf("\\trim/1"))
        .as("a language with no table has no claims")
        .isEqualTo(LanguageSemantics.TaintBehaviour.UNKNOWN);
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("a tainted value that crosses a string operation still reaches the command")
  void aTaintedValueThatCrossesAStringOperationStillReachesTheCommand() {
    List<Finding> findings = scan(SourceLanguage.PHP);

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as("the command call must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  @Test
  @DisplayName("the same program in Java reports nothing, because Java has no such catalogue entry")
  void theSameProgramInJavaReportsNothing() {
    List<Finding> findings = scan(SourceLanguage.JAVA);

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as("the rule is scoped to PHP")
        .isFalse();
  }

  // ------------------------------------------- response, cookie and database

  /**
   * The PHP handler that queries a database, as the front end lowers it.
   *
   * <p>Measured against the PHP side car: the receiver slot of a global function holds the
   * NAMESPACE the name was qualified with, so the connection is the first user argument and the
   * statement is the second.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction sqlHandler(SourceLanguage language) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar link = new TacOperand.TacVar("link", TacType.UNKNOWN, false);
    TacOperand.TacVar name = new TacOperand.TacVar("name", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(name, request, 10));
    // mysqli_query($link, $name);
    instructions.add(
        blackBox(
            "\\mysqli_query/2",
            temp("t9"),
            List.of(TacOperand.Const.ofString("\\"), link, name),
            12));
    return new TacFunction(
        "handle",
        "app\\handle(string):string",
        TacType.STRING,
        List.of(request),
        List.of(link, name),
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
    TacProgram program = new TacProgram(Path.of("handler.php"), "app");
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
  @DisplayName("the catalogue names the PHP response, cookie, log and database APIs")
  void theCatalogueNamesThePhpResponseCookieLogAndDatabaseApis() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("header.sinks")
                .orElseThrow()
                .matches("\\header/1", SourceLanguage.PHP))
        .as("header writes a response header")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("header.sinks")
                .orElseThrow()
                .matches("\\mail/3", SourceLanguage.PHP))
        .as("mail writes its subject into a header")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("cookie.sinks")
                .orElseThrow()
                .matches("\\setcookie/2", SourceLanguage.PHP))
        .as("setcookie writes a cookie value")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("cookie.sinks")
                .orElseThrow()
                .matches("\\setrawcookie/2", SourceLanguage.PHP))
        .as("setrawcookie writes a cookie value too")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("reflection.sinks")
                .orElseThrow()
                .matches("\\call_user_func/2", SourceLanguage.PHP))
        .as("call_user_func chooses a function by name")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("\\mysqli_query/2", SourceLanguage.PHP))
        .as("mysqli_query sends the statement to the database")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("\\PDO::query/1", SourceLanguage.PHP))
        .as("PDO::query sends the statement to the database")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("\\error_log/1", SourceLanguage.PHP))
        .as("error_log writes the message to the log")
        .isTrue();
  }

  @Test
  @DisplayName("the PHP buckets do not reach into another language")
  void thePhpBucketsDoNotReachIntoAnotherLanguage() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("header.sinks")
                .orElseThrow()
                .matches("\\header/1", SourceLanguage.JAVA))
        .as("a Java program has no PHP built-ins")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("\\mysqli_query/2", SourceLanguage.JAVA))
        .as("a Java program has no PHP built-ins")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("\\error_log/1", SourceLanguage.JAVA))
        .as("a Java program has no PHP built-ins")
        .isFalse();
  }

  @Test
  @DisplayName("the new PHP buckets do not claim a name on its own")
  void theNewPhpBucketsDoNotClaimANameOnItsOwn() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("\\error_logging/1", SourceLanguage.PHP))
        .as("a name that starts with error_log is not a log call")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("header.sinks")
                .orElseThrow()
                .matches("\\header_size/1", SourceLanguage.PHP))
        .as("a name that starts with header is not a header call")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("header.sinks")
                .orElseThrow()
                .matches("\\mailer/1", SourceLanguage.PHP))
        .as("a name that starts with mail is not a mail call")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("cookie.sinks")
                .orElseThrow()
                .matches("\\setcookiex/2", SourceLanguage.PHP))
        .as("a name that starts with setcookie is not a cookie call")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("cookie.sinks")
                .orElseThrow()
                .matches("\\setrawcookiez/2", SourceLanguage.PHP))
        .as("a name that starts with setrawcookie is not a cookie call")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("reflection.sinks")
                .orElseThrow()
                .matches("\\call_user_func_extra/2", SourceLanguage.PHP))
        .as("a name that starts with call_user_func is not a call")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("\\mysqli_query_all/2", SourceLanguage.PHP))
        .as("a name that starts with mysqli_query is not a query")
        .isFalse();
  }

  @Test
  @DisplayName("a tainted value reaches the database call")
  void aTaintedValueReachesTheDatabaseCall() {
    List<Finding> findings =
        scan(SourceLanguage.PHP, sqlHandler(SourceLanguage.PHP), new SqlInjectionChecker());

    assertThat(hasFinding(findings, SQL_RULE, "\\mysqli_query/2"))
        .as("the query call must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  @Test
  @DisplayName("the tainted connection is not the statement")
  void theTaintedConnectionIsNotTheStatement() {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar link = new TacOperand.TacVar("link", TacType.STRING, false);
    TacOperand.TacVar name = new TacOperand.TacVar("name", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(link, request, 10));
    instructions.add(
        blackBox(
            "\\mysqli_query/2",
            temp("t9"),
            List.of(TacOperand.Const.ofString("\\"), link, name),
            12));
    TacFunction function =
        new TacFunction(
            "handle",
            "app\\handle(string):string",
            TacType.STRING,
            List.of(request),
            List.of(link, name),
            instructions,
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            null,
            SourceOrigin.SOURCE,
            List.of(),
            SourceLanguage.PHP,
            IrPrecision.SOURCE_FULL);

    List<Finding> findings = scan(SourceLanguage.PHP, function, new SqlInjectionChecker());

    assertThat(hasFinding(findings, SQL_RULE, "\\mysqli_query/2"))
        .as("the statement is the second argument; the connection is not the statement")
        .isFalse();
  }

  // ------------------------------------------------- the format and the pattern

  /**
   * The PHP handler that formats a string, as the front end lowers it.
   *
   * <p>Measured against the PHP side car: the receiver slot of a global function holds the
   * NAMESPACE, so the format string is the FIRST user argument.
   *
   * @param language the language the function is written in
   * @return the function under analysis
   */
  private static TacFunction formatHandler(SourceLanguage language) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar format = new TacOperand.TacVar("format", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(format, request, 10));
    // sprintf($format, "x");
    instructions.add(
        blackBox(
            "\\sprintf/2",
            temp("t9"),
            List.of(TacOperand.Const.ofString("\\"), format, TacOperand.Const.ofString("x")),
            12));
    return new TacFunction(
        "handle",
        "app\\handle(string):string",
        TacType.STRING,
        List.of(request),
        List.of(format),
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
  @DisplayName("the catalogue names the PHP format and pattern APIs")
  void theCatalogueNamesThePhpFormatAndPatternApis() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("format-string.sinks")
                .orElseThrow()
                .matches("\\printf/1", SourceLanguage.PHP))
        .as("the format string is the first argument")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("format-string.sinks")
                .orElseThrow()
                .matches("\\sprintf/2", SourceLanguage.PHP))
        .as("and so it is for sprintf")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("regex.sinks")
                .orElseThrow()
                .matches("\\preg_match/2", SourceLanguage.PHP))
        .as("a pattern the program did not choose")
        .isTrue();
  }

  @Test
  @DisplayName("the PHP format and pattern buckets do not claim a name on its own")
  void thePhpFormatAndPatternBucketsDoNotClaimANameOnItsOwn() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("format-string.sinks")
                .orElseThrow()
                .matches("\\printfx/1", SourceLanguage.PHP))
        .as("a name that starts with printf is not a format")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("regex.sinks")
                .orElseThrow()
                .matches("\\preg_quote/1", SourceLanguage.PHP))
        .as("preg_quote escapes a pattern rather than using one")
        .isFalse();
  }

  @Test
  @DisplayName("a tainted value reaches the format string")
  void aTaintedValueReachesTheFormatString() {
    List<Finding> findings =
        scan(SourceLanguage.PHP, formatHandler(SourceLanguage.PHP), new FormatStringChecker());

    assertThat(hasFinding(findings, FORMAT_RULE, "\\sprintf/2"))
        .as("the format call must be recognised, and the value must still be tainted there")
        .isTrue();
  }
}
