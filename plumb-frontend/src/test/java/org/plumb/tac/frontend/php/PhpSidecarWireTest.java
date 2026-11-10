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
package org.plumb.tac.frontend.php;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.frontend.sidecar.SidecarProcess;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The PHP frontend against a peer on the wire.
 *
 * <h2>Why a stand-in sidecar</h2>
 *
 * <p>The real sidecar needs a Node runtime and an installed parser, so a test that drives it only
 * runs where those are installed. But the framing, the handshake and the wire-to-IR mapping are the
 * <b>Java</b> side's own responsibility, and they have to hold wherever the tests run. So these
 * tests drive the real frontend against a peer that speaks the same protocol.
 *
 * <h2>What is being pinned here</h2>
 *
 * <p>Five things, and each of them has a way to go wrong that looks like "no findings":
 *
 * <ul>
 *   <li>the <b>receiver slot</b> -- a call that put the value at {@code args[0]} instead of {@code
 *       args[1]} looks right and matches nothing;
 *   <li>the <b>keyed namespace</b> -- a property read that became a field read names a field, and a
 *       field is not what a property is;
 *   <li>the <b>write</b> -- an assignment that became nothing is a taint that stops halfway;
 *   <li>the <b>handshake</b> -- a response that carries no version is a response nobody can check;
 *   <li>the <b>qualified name</b> -- a callee signature that lost its namespace matches a different
 *       sink than the one the source names.
 * </ul>
 */
class PhpSidecarWireTest {

  @Test
  @DisplayName("the sidecar's TAC arrives as the IR's TAC")
  void theTacsArrivesAsTheIrsTac(@TempDir Path directory) throws IOException {
    Path file = writeSource(directory, "app.php");
    String source = Files.readString(file);

    try (PhpTacFrontend frontend = new PhpTacFrontend(fakeCommand())) {
      TacFrontend.ParseResult result = frontend.parse(source, file, null);

      assertThat(result.functions()).hasSize(1);
      TacFunction function = result.functions().get(0);
      assertThat(function.name()).isEqualTo("send");
      assertThat(function.signature()).isEqualTo("App\\Services\\Mailer::send(string):bool");
      assertThat(function.enclosingClass()).isEqualTo("App\\Services\\Mailer");
      assertThat(function.sourceLanguage()).isEqualTo(SourceLanguage.PHP);
      assertThat(function.irPrecision()).isEqualTo(IrPrecision.SOURCE_LOWERED);
      assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_LOWERED);

      // A property read is a CONTAINER ACCESS with a string key, not a field
      // access: in this language an object is a namespace, and `$box["label"]`
      // and `$box->label` are the same location.
      TacInstruction read =
          function.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the property read is not an ARRAYLOAD"));
      assertThat(read.lhs2).isEqualTo(TacOperand.Const.ofString("label"));
      assertThat(function.instructions())
          .as("a property read must never become a field access")
          .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD);

      // A property WRITE is a store with the same key. A write that is
      // dropped is a taint that stops halfway.
      TacInstruction write =
          function.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYSTORE)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the property write is not an ARRAYSTORE"));
      assertThat(write.lhs2).isEqualTo(TacOperand.Const.ofString("label"));
      assertThat(write.dest).isNotNull();

      // A binary operator is a BINOP, not an unknown value.
      TacInstruction addition =
          function.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.BINOP)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the binary operator is not a BINOP"));
      assertThat(addition.binop).isEqualTo(org.plumb.tac.ir.BinaryOperator.ADD);

      // The call keeps the receiver slot: args[0] is the receiver, args[1..]
      // are the arguments. A free function's receiver slot holds the
      // NAMESPACE it is in, which is how PHP qualifies a global function.
      TacInstruction call =
          function.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the call is not in the stream"));
      assertThat(call.calleeSig).isEqualTo("\\system/1");
      assertThat(call.args).hasSize(2);
      assertThat(call.args.get(0)).isEqualTo(TacOperand.Const.ofString("\\"));
      assertThat(((TacOperand.TacVar) call.args.get(1)).name()).isEqualTo("t3");
    }
  }

  @Test
  @DisplayName("what the sidecar could not model reaches the caller")
  void whatTheSidecarCouldNotModelReachesTheCaller(@TempDir Path directory) throws IOException {
    Path file = writeSource(directory, "app.php");
    String source = Files.readString(file);

    try (PhpTacFrontend frontend = new PhpTacFrontend(fakeCommand())) {
      TacFrontend.StructuredParseResult result = frontend.parseStructured(source, file, null);

      assertThat(result.problems())
          .as("a construct the sidecar could not model must not be dropped")
          .anyMatch(
              problem ->
                  "SOURCE_LOWERED".equals(problem.kind())
                      && problem.message().contains("a closure has no TAC lowering")
                      && problem.line() == 14);
    }
  }

  @Test
  @DisplayName("a sidecar that cannot be started is a loud failure")
  void aSidecarThatCannotBeStartedIsALoudFailure(@TempDir Path directory) throws IOException {
    Path file = writeSource(directory, "app.php");
    String source = Files.readString(file);

    try (PhpTacFrontend frontend = new PhpTacFrontend(List.of("plumb-no-such-sidecar-program"))) {
      TacFrontend.ParseResult result = frontend.parse(source, file, null);

      assertThat(result.functions()).isEmpty();
      assertThat(result.problems())
          .as("a missing sidecar must never look like 'nothing to find'")
          .isNotEmpty()
          .anyMatch(problem -> problem.contains("could not be started"));
    }
  }

  @Test
  @DisplayName("a file whose content on disk differs is reported")
  void aFileWhoseContentOnDiskDiffersIsReported(@TempDir Path directory) throws IOException {
    Path file = writeSource(directory, "app.php");

    try (PhpTacFrontend frontend = new PhpTacFrontend(fakeCommand())) {
      TacFrontend.StructuredParseResult result =
          frontend.parseStructured("<?php\nfunction send() {}\n", file, null);

      assertThat(result.problems())
          .as(
              "the analysis used the file on disk, and saying so is the difference between a "
                  + "known lower bound and a silent surprise")
          .anyMatch(
              problem -> problem.message().contains("differs from the content that was given"));
    }
  }

  @Test
  @DisplayName("a request without a protocol version is refused")
  void aRequestWithoutAProtocolVersionIsRefused() throws IOException {
    try (SidecarProcess process = SidecarProcess.launch("fake", fakeCommand(), null)) {
      String response = process.exchange("{\"op\":\"ping\"}");

      Json.JsonObject object = Json.parseObject(response);
      assertThat(object.getBoolean("ok", true))
          .as("a request that names no version must be refused")
          .isFalse();
      assertThat(object.getString("error")).isNotBlank();
    }
  }

  @Test
  @DisplayName("a file with no path is reported, not crashed on")
  void aFileWithNoPathIsReportedNotCrashedOn() {
    try (PhpTacFrontend frontend = new PhpTacFrontend(fakeCommand())) {
      TacFrontend.ParseResult result = frontend.parse("<?php\n", null, null);

      assertThat(result.functions()).isEmpty();
      assertThat(result.problems())
          .as("a frontend must be total: a bad input is a problem, not a crash")
          .isNotEmpty();
    }
  }

  @Test
  @DisplayName("the frontend speaks the language it was built for")
  void theFrontendSpeaksTheLanguageItWasBuiltFor() {
    PhpTacFrontend php = new PhpTacFrontend();

    assertThat(php.language()).isEqualTo(SourceLanguage.PHP);
    assertThat(php.fileExtensions()).containsExactly("php");
    assertThat(php.requiresClasspath())
        .as("the parser resolves nothing against a JVM classpath")
        .isFalse();
  }

  /**
   * Write a source file and a project marker, so the project's root is the temporary directory.
   *
   * @param directory the temporary directory
   * @param name the file's name
   * @return the file
   * @throws IOException when the file cannot be written
   */
  private static Path writeSource(Path directory, String name) throws IOException {
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve(name);
    Files.writeString(
        file,
        "<?php\n"
            + "namespace App\\Services;\n"
            + "\n"
            + "class Mailer {\n"
            + "    public function send(string $to): bool {\n"
            + "        $box = [];\n"
            + "        $box[\"label\"] = $to;\n"
            + "        return system($box[\"label\"] . \"!\");\n"
            + "    }\n"
            + "}\n");
    return file;
  }

  /**
   * The command that starts the stand-in sidecar.
   *
   * @return the command line
   */
  private static List<String> fakeCommand() {
    Path binary = null;
    for (String name : new String[] {"java", "java.exe"}) {
      Path candidate = Path.of(System.getProperty("java.home"), "bin", name);
      if (Files.isRegularFile(candidate)) {
        binary = candidate;
        break;
      }
    }
    if (binary == null) {
      for (String name : new String[] {"java", "java.exe"}) {
        Path candidate = Path.of(System.getProperty("java.home"), "jre", "bin", name);
        if (Files.isRegularFile(candidate)) {
          binary = candidate;
          break;
        }
      }
    }
    String classpath =
        String.join(
            java.io.File.pathSeparator,
            locationOf(FakePhpSidecar.class),
            locationOf(PhpTacFrontend.class),
            System.getProperty("java.class.path"));
    return List.of(binary.toString(), "-cp", classpath, FakePhpSidecar.class.getName());
  }

  /** Where a class was loaded from. */
  private static String locationOf(Class<?> type) {
    try {
      return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    } catch (Exception ex) {
      return "";
    }
  }
}
