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
import org.plumb.dfa.checkers.taint.PathTraversalChecker;
import org.plumb.dfa.checkers.taint.TaintedXmlParserChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.python.PythonTacFrontend;
import org.plumb.tac.frontend.sidecar.PythonRuntime;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The Python path and XML sinks: the catalogue entries and the positions they name.
 *
 * <p><b>Why the keys are written out by hand.</b> A pattern that names a signature the front end
 * never emits is a rule that never fires, so every key below was measured against the Python side
 * car rather than read off an API reference. The measurement gives two facts the catalogue depends
 * on.
 *
 * <p><b>The receiver slot holds the MODULE, as a constant.</b> A Python call arrives as {@code
 * lxml.etree.fromstring/1} with the argument table {@code ["lxml.etree", user_input]}: the first
 * slot is the module the function came from, and it is a string constant. The engine adds one to a
 * user argument index for a CALL, so user argument 0 is the second slot -- which is where the
 * tainted value sits.
 *
 * <p><b>The arity in the key counts the POSITIONAL arguments only.</b> {@code open(name)} is {@code
 * builtins.open/1} and {@code open(name, "w")} is {@code builtins.open/2}, while {@code
 * open(file=name)} is {@code builtins.open/0} even though the argument table still holds the name.
 * The catalogue therefore matches the arity loosely and names the position by the user argument
 * index, which is the index space the argument table uses for a keyword argument too.
 *
 * <p><b>Why only lxml is in the external-entity family.</b> The family means "the tainted document
 * reaches a parser that resolves external entities", and whether a Python parser resolves them is a
 * property of the parser. Measured on the standard library: {@code xml.etree.ElementTree}, {@code
 * xml.dom.minidom}, {@code xml.sax} and {@code xml.dom.pulldom} are built on expat without an
 * external entity handler, so a document that names one is refused rather than expanded. lxml
 * expands it when the parser resolves entities. Naming the standard library parsers would therefore
 * report a defect the code cannot have.
 */
@DisplayName("Taint: the Python path and XML sinks")
class PySinkPathXmlTest {

  private static final String PATH_RULE = "dfa-taint-path-traversal";
  private static final String XXE_RULE = "dfa-taint-xxe";

  /**
   * A Python program that opens a file whose name arrived over the socket.
   *
   * <p>The socket read is a source whose tainted value is the call's RESULT, so the name the
   * program opens is the one the caller sent.
   */
  private static final String PYTHON_FILE_SOURCE =
      """
      import socket


      def handler():
          data = socket.recv(1024)
          handle = open(data)
      """;

  /**
   * A Python program that hands a document to lxml.
   *
   * <p>Same shape as the file program: the bytes that arrived are the document the parser reads.
   */
  private static final String PYTHON_XML_SOURCE =
      """
      import lxml.etree
      import socket


      def handler():
          data = socket.recv(1024)
          document = lxml.etree.fromstring(data)
      """;

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  /**
   * A call the Python side car emitted, with the receiver slot holding the module as a constant.
   *
   * @param callee the measured callee signature
   * @param args the argument table, receiver slot first
   * @param line the source line
   * @return the instruction
   */
  private static TacInstruction call(String callee, List<TacOperand> args, int line) {
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .calleeSig(callee)
        .dest(temp("t_t1"))
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
   * The handler, as the front end lowers it: the request arrives as a parameter, the value is read
   * out of it, and the value reaches the call.
   *
   * @param language the language the function is written in
   * @param calls the calls that follow the read
   * @return the function under analysis
   */
  private static TacFunction handler(SourceLanguage language, TacInstruction... calls) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar name = new TacOperand.TacVar("name", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(name, request, 10));
    instructions.addAll(List.of(calls));

    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(request),
        List.of(name),
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

  private static boolean hasFinding(List<Finding> findings, String ruleId, String callee) {
    for (Finding f : findings) {
      if (!f.ruleId().equals(ruleId)) continue;
      if (f.message().contains(callee)) return true;
    }
    return false;
  }

  /**
   * Run the source through the front end, then through the engine.
   *
   * <p>This is the half a hand-written TAC cannot prove: that the front end emits the name the
   * catalogue matches on. A hand-written TAC is what the side car was measured to produce, so the
   * two can drift apart without anybody noticing, and the symptom of that drift is a scan that
   * reports the program clean.
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
      if (finding.ruleId().startsWith("dfa-taint-")) findings.add(finding);
    }
    return findings;
  }

  /** The families that claim the given signature in the given language. */
  private static List<String> familiesFor(String signature, SourceLanguage language) {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<String> names = new ArrayList<>();
    for (SinkFamily family : config.sinkFamilies().lookupAll(signature, language)) {
      names.add(family.name());
    }
    return names;
  }

  // ------------------------------------------------------------------ the catalogue

  @Test
  @DisplayName("the catalogue names the Python APIs that open a path")
  void theCatalogueNamesThePythonApisThatOpenAPath() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("file-path.sinks").orElseThrow();

    for (String signature :
        List.of(
            "builtins.open/1",
            "builtins.open/2",
            "builtins.open/0",
            "io.open/1",
            "os.open/2",
            "pathlib.Path/1",
            "logging.FileHandler/1",
            "os.makedirs/1")) {
      assertThat(family.matches(signature, SourceLanguage.PYTHON3))
          .as("%s names a path the program did not choose", signature)
          .isTrue();
    }
  }

  @Test
  @DisplayName("the path family is scoped to Python")
  void thePathFamilyIsScopedToPython() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("file-path.sinks").orElseThrow();

    assertThat(family.matches("builtins.open/1", SourceLanguage.JAVA))
        .as("a Java program has no builtins module")
        .isFalse();
  }

  @Test
  @DisplayName("the catalogue names the one Python parser that resolves an external entity")
  void theCatalogueNamesTheOnePythonParserThatResolvesAnExternalEntity() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("xxe.sinks").orElseThrow();

    for (String signature :
        List.of("lxml.etree.fromstring/1", "lxml.etree.parse/1", "lxml.etree.parseid/1")) {
      assertThat(family.matches(signature, SourceLanguage.PYTHON3))
          .as("%s expands the entity the document names", signature)
          .isTrue();
    }
  }

  @Test
  @DisplayName("the standard library parsers are not external-entity sinks")
  void theStandardLibraryParsersAreNotExternalEntitySinks() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("xxe.sinks").orElseThrow();

    for (String signature :
        List.of(
            "xml.etree.ElementTree.fromstring/1",
            "xml.etree.ElementTree.parse/1",
            "xml.dom.minidom.parseString/1",
            "xml.sax.parseString/1",
            "xml.dom.pulldom.parseString/1")) {
      assertThat(family.matches(signature, SourceLanguage.PYTHON3))
          .as("%s refuses an entity instead of expanding it", signature)
          .isFalse();
    }
  }

  @Test
  @DisplayName("the external-entity family is scoped to Python")
  void theExternalEntityFamilyIsScopedToPython() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("xxe.sinks").orElseThrow();

    assertThat(family.matches("lxml.etree.fromstring/1", SourceLanguage.JAVA))
        .as("a Java program has no lxml module")
        .isFalse();
  }

  @Test
  @DisplayName("one API belongs to one family")
  void oneApiBelongsToOneFamily() {
    assertThat(familiesFor("builtins.open/1", SourceLanguage.PYTHON3))
        .as("opening a file is a path defect and nothing else")
        .containsExactly("file-path.sinks");
    assertThat(familiesFor("logging.FileHandler/1", SourceLanguage.PYTHON3))
        .as("the name of a log file is a path, not a log record")
        .containsExactly("file-path.sinks");
    assertThat(familiesFor("pathlib.Path/1", SourceLanguage.PYTHON3))
        .as("a path object is a path and nothing else")
        .containsExactly("file-path.sinks");
    assertThat(familiesFor("lxml.etree.fromstring/1", SourceLanguage.PYTHON3))
        .as("parsing an XML document is an external-entity defect and nothing else")
        .containsExactly("xxe.sinks");
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("a tainted path reaches the file it opens")
  void aTaintedPathReachesTheFileItOpens() {
    TacFunction function =
        handler(
            SourceLanguage.PYTHON3,
            call(
                "builtins.open/1",
                List.of(
                    TacOperand.Const.ofString("builtins"),
                    new TacOperand.TacVar("name", TacType.STRING, false)),
                12));

    List<Finding> findings = scan(SourceLanguage.PYTHON3, function, new PathTraversalChecker());

    assertThat(hasFinding(findings, PATH_RULE, "builtins.open/1"))
        .as("the path must still be tainted where the file is opened")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted document reaches the parser that expands it")
  void aTaintedDocumentReachesTheParserThatExpandsIt() {
    TacFunction function =
        handler(
            SourceLanguage.PYTHON3,
            call(
                "lxml.etree.fromstring/1",
                List.of(
                    TacOperand.Const.ofString("lxml.etree"),
                    new TacOperand.TacVar("name", TacType.STRING, false)),
                12));

    List<Finding> findings = scan(SourceLanguage.PYTHON3, function, new TaintedXmlParserChecker());

    assertThat(hasFinding(findings, XXE_RULE, "lxml.etree.fromstring/1"))
        .as("the document must still be tainted where the parser reads it")
        .isTrue();
  }

  @Test
  @DisplayName("the mode argument is not the path")
  void theModeArgumentIsNotThePath() {
    TacFunction function =
        handler(
            SourceLanguage.PYTHON3,
            call(
                "builtins.open/2",
                List.of(
                    TacOperand.Const.ofString("builtins"),
                    TacOperand.Const.ofString("/tmp/fixed"),
                    new TacOperand.TacVar("name", TacType.STRING, false)),
                12));

    List<Finding> findings = scan(SourceLanguage.PYTHON3, function, new PathTraversalChecker());

    assertThat(hasFinding(findings, PATH_RULE, "builtins.open/2"))
        .as("the tainted value is the mode, and the path is the one the program chose")
        .isFalse();
  }

  @Test
  @DisplayName("the same program in Java reports nothing, because Java has no such catalogue entry")
  void theSameProgramInJavaReportsNothing() {
    TacFunction function =
        handler(
            SourceLanguage.JAVA,
            call(
                "builtins.open/1",
                List.of(
                    TacOperand.Const.ofString("builtins"),
                    new TacOperand.TacVar("name", TacType.STRING, false)),
                12));

    List<Finding> findings = scan(SourceLanguage.JAVA, function, new PathTraversalChecker());

    assertThat(hasFinding(findings, PATH_RULE, "builtins.open/1"))
        .as("the rule is scoped to Python")
        .isFalse();
  }

  // ------------------------------------------------- through the real front end

  @Test
  @DisplayName("a tainted path reaches the file through the real front end")
  void aTaintedPathReachesTheFileThroughTheRealFrontEnd(@TempDir Path directory)
      throws IOException {
    assumeTrue(
        PythonRuntime.locate() != null,
        "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_FILE_SOURCE);

    List<Finding> findings =
        scan(new PythonTacFrontend(), PYTHON_FILE_SOURCE, file, new PathTraversalChecker());

    assertThat(hasFinding(findings, PATH_RULE, "builtins.open"))
        .as("the name that arrived over the socket is the path the program opens")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted document reaches the parser through the real front end")
  void aTaintedDocumentReachesTheParserThroughTheRealFrontEnd(@TempDir Path directory)
      throws IOException {
    assumeTrue(
        PythonRuntime.locate() != null,
        "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_XML_SOURCE);

    List<Finding> findings =
        scan(new PythonTacFrontend(), PYTHON_XML_SOURCE, file, new TaintedXmlParserChecker());

    assertThat(hasFinding(findings, XXE_RULE, "lxml.etree.fromstring"))
        .as("the bytes that arrived are the document the parser reads")
        .isTrue();
  }
}
