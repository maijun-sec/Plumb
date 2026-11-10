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
package org.plumb.tac.frontend.ts;

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
 * The TypeScript frontend against a peer on the wire.
 *
 * <h2>Why a stand-in sidecar</h2>
 *
 * <p>The real sidecar needs Node and the compiler, so a test that drives it only runs where Node is
 * installed. But the framing, the handshake and the wire-to-IR mapping are the <b>Java</b> side's
 * own responsibility, and they have to hold wherever the tests run. So these tests drive the real
 * frontend against a peer that speaks the same protocol.
 *
 * <h2>What is being pinned here</h2>
 *
 * <p>Three things, and each of them has a way to go wrong that looks like "no findings":
 *
 * <ul>
 *   <li>the <b>receiver slot</b> -- a call that put the value at {@code args[0]} instead of {@code
 *       args[1]} looks right and matches nothing;
 *   <li>the <b>keyed namespace</b> -- a property read that became a field read names a field, and a
 *       field is not what a property is;
 *   <li>the <b>handshake</b> -- a response that carries no version is a response nobody can check.
 * </ul>
 */
class TsSidecarWireTest {

  @Test
  @DisplayName("the sidecar's TAC arrives as the IR's TAC")
  void theTacsArrivesAsTheIrsTac(@TempDir Path directory) throws IOException {
    Path file = writeSource(directory, "app.ts");
    String source = Files.readString(file);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT, fakeCommand())) {
      TacFrontend.ParseResult result = frontend.parse(source, file, null);

      assertThat(result.problems()).isEmpty();
      assertThat(result.functions()).hasSize(1);
      TacFunction function = result.functions().get(0);
      assertThat(function.name()).isEqualTo("handle");
      assertThat(function.signature()).isEqualTo("app.handle(Object):string");
      assertThat(function.enclosingClass()).isEqualTo("UserStore");
      assertThat(function.sourceLanguage()).isEqualTo(SourceLanguage.TYPESCRIPT);
      assertThat(function.irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
      assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_FULL);

      // A property read is a CONTAINER ACCESS with a string key, not a field
      // access: in this language an object is a namespace, and `obj.userId`
      // and `obj["userId"]` are the same location.
      TacInstruction read =
          function.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the property read is not an ARRAYLOAD"));
      assertThat(read.lhs2).isEqualTo(TacOperand.Const.ofString("userId"));
      assertThat(function.instructions())
          .as("a property read must never become a field access")
          .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD);

      // The call keeps the receiver slot: args[0] is the receiver, args[1..]
      // are the arguments.
      TacInstruction call =
          function.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the call is not in the stream"));
      assertThat(call.calleeSig).isEqualTo("UserStore.find/1");
      assertThat(call.args).hasSize(2);
      assertThat(((TacOperand.TacVar) call.args.get(0)).name()).isEqualTo("store");
      assertThat(((TacOperand.TacVar) call.args.get(1)).name()).isEqualTo("t1");
    }
  }

  @Test
  @DisplayName("what the sidecar could not model reaches the caller")
  void whatTheSidecarCouldNotModelReachesTheCaller(@TempDir Path directory) throws IOException {
    Path file = writeSource(directory, "app.ts");
    String source = Files.readString(file);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT, fakeCommand())) {
      TacFrontend.StructuredParseResult result = frontend.parseStructured(source, file, null);

      assertThat(result.problems())
          .as("a construct the sidecar could not model must not be dropped")
          .anyMatch(
              problem ->
                  "SOURCE_LOWERED".equals(problem.kind())
                      && problem.message().contains("a break has no TAC lowering")
                      && problem.line() == 20);
    }
  }

  @Test
  @DisplayName("a sidecar that cannot be started is a loud failure")
  void aSidecarThatCannotBeStartedIsALoudFailure(@TempDir Path directory) throws IOException {
    Path file = writeSource(directory, "app.ts");
    String source = Files.readString(file);

    try (TsTacFrontend frontend =
        new TsTacFrontend(SourceLanguage.TYPESCRIPT, List.of("plumb-no-such-sidecar-program"))) {
      TacFrontend.ParseResult result = frontend.parse(source, file, null);

      assertThat(result.functions()).isEmpty();
      assertThat(result.problems())
          .as("a missing sidecar must never look like 'nothing to find'")
          .isNotEmpty()
          .anyMatch(problem -> problem.contains("could not be started"));
    }
  }

  @Test
  @DisplayName("a declaration file is reported, not analysed")
  void aDeclarationFileIsReportedNotAnalysed(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("types.d.ts");
    Files.writeString(file, "export declare function handle(id: string): string;\n");

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT, fakeCommand())) {
      TacFrontend.StructuredParseResult result =
          frontend.parseStructured(Files.readString(file), file, null);

      assertThat(result.functions()).isEmpty();
      assertThat(result.problems())
          .as("a declaration file holds no body, so saying so is the honest outcome")
          .anyMatch(problem -> problem.message().contains("declaration file"));
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
    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT, fakeCommand())) {
      TacFrontend.ParseResult result = frontend.parse("export const a = 1;", null, null);

      assertThat(result.functions()).isEmpty();
      assertThat(result.problems())
          .as("a frontend must be total: a bad input is a problem, not a crash")
          .isNotEmpty();
    }
  }

  @Test
  @DisplayName("the frontend speaks the language it was built for")
  void theFrontendSpeaksTheLanguageItWasBuiltFor() {
    TsTacFrontend typescript = new TsTacFrontend(SourceLanguage.TYPESCRIPT);
    TsTacFrontend javascript = new TsTacFrontend(SourceLanguage.JAVASCRIPT);

    assertThat(typescript.language()).isEqualTo(SourceLanguage.TYPESCRIPT);
    assertThat(typescript.fileExtensions()).containsExactlyInAnyOrder("ts", "tsx", "mts", "cts");
    assertThat(javascript.language()).isEqualTo(SourceLanguage.JAVASCRIPT);
    assertThat(javascript.fileExtensions()).containsExactlyInAnyOrder("js", "jsx", "mjs", "cjs");
    assertThat(typescript.requiresClasspath())
        .as("the compiler resolves types from the project, not from a JVM classpath")
        .isFalse();
  }

  /** Write a source file and a project marker, so the project's root is the temporary directory. */
  private static Path writeSource(Path directory, String name) throws IOException {
    Files.writeString(directory.resolve("tsconfig.json"), "{}\n");
    Path file = directory.resolve(name);
    Files.writeString(
        file,
        "export function handle(req: { userId: string }): string {\n  return req.userId;\n}\n");
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
            locationOf(FakeTsSidecar.class),
            locationOf(TsTacFrontend.class),
            System.getProperty("java.class.path"));
    return List.of(binary.toString(), "-cp", classpath, FakeTsSidecar.class.getName());
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
