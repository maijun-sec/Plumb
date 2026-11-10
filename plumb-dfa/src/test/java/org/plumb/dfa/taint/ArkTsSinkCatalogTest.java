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
import org.plumb.dfa.checkers.taint.LogInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.arkts.ArktsSidecarClient;
import org.plumb.tac.frontend.arkts.ArktsTacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The ArkTS rule layer: the console sink.
 *
 * <p><b>Why this class exists on its own.</b> The TypeScript catalogue test covers TypeScript and
 * JavaScript, and ArkTS was not in either of them. ArkTS is a third language with its own front end
 * and its own side car, and whether a catalogue entry reaches it is a question about that front
 * end, not about the entry.
 *
 * <p><b>Why the console is the entry.</b> The catalogue names the runtime console as a log sink for
 * the script languages, and whether ArkTS can be included is a property of the key the side car
 * emits. Measured against both side cars, the console arrives as the Console type in all three
 * languages -- {@code Console.log/1} -- so one pattern names them all.
 *
 * <p><b>Why the write to the standard output is NOT an entry.</b> Neither language can name the
 * class of {@code process.stdout}: TypeScript reports {@code <unknown>.write/0} and ArkTS reports
 * the file's module name. A pattern for it would claim every write, and the catalogue has a test
 * that refuses to claim a call whose owner nobody could name.
 */
@DisplayName("Taint: the ArkTS rule layer")
class ArkTsSinkCatalogTest {

  private static final String LOG_RULE = "dfa-taint-log-injection";

  /** An ArkTS program that writes a value it received to the console. */
  private static final String SOURCE =
      """
      export function handle(userInput: string): void {
        console.log(userInput);
      }
      """;

  /**
   * The families that claim the given signature in the given language.
   *
   * @param signature the callee key the front end emits
   * @param language the language the program is written in
   * @return the family names
   */
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
  @DisplayName("the catalogue names the console in ArkTS")
  void theCatalogueNamesTheConsoleInArkTs() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("log.sinks").orElseThrow();

    assertThat(family.appliesTo(SourceLanguage.ARKTS))
        .as("the family has to apply to ArkTS, or its bucket is never read")
        .isTrue();
    assertThat(family.matches("Console.log/1", SourceLanguage.ARKTS))
        .as("the console writes what it is given")
        .isTrue();
    assertThat(family.matches("Console.error/1", SourceLanguage.ARKTS))
        .as("every console method writes a record")
        .isTrue();
  }

  @Test
  @DisplayName("the same key serves the three script languages")
  void theSameKeyServesTheThreeScriptLanguages() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("log.sinks").orElseThrow();

    for (SourceLanguage language :
        List.of(SourceLanguage.TYPESCRIPT, SourceLanguage.JAVASCRIPT, SourceLanguage.ARKTS)) {
      assertThat(family.matches("Console.log/1", language))
          .as("the console arrives as the Console type in %s", language)
          .isTrue();
    }
  }

  @Test
  @DisplayName("one API belongs to one family")
  void oneApiBelongsToOneFamily() {
    assertThat(familiesFor("Console.log/1", SourceLanguage.ARKTS))
        .as("the console is a log sink and nothing else")
        .containsExactly("log.sinks");
  }

  @Test
  @DisplayName("the write to the standard output is not claimed")
  void theWriteToTheStandardOutputIsNotClaimed() {
    assertThat(familiesFor("<unknown>.write/0", SourceLanguage.TYPESCRIPT))
        .as("TypeScript cannot name the class of process.stdout")
        .isEmpty();
    assertThat(familiesFor("probe.write/0", SourceLanguage.ARKTS))
        .as("ArkTS reports the file's module name, which is not a class")
        .isEmpty();
  }

  @Test
  @DisplayName("the console bucket does not reach into a JVM language")
  void theConsoleBucketDoesNotReachIntoAJvmLanguage() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("log.sinks").orElseThrow();

    assertThat(family.matches("Console.log/1", SourceLanguage.JAVA))
        .as("a Java program has no console object")
        .isFalse();
  }

  @Test
  @DisplayName("the catalogue names the formatter in ArkTS")
  void theCatalogueNamesTheFormatterInArkTs() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("format-string.sinks").orElseThrow();

    assertThat(family.appliesTo(SourceLanguage.ARKTS))
        .as("the family has to apply to ArkTS, or its bucket is never read")
        .isTrue();
    assertThat(family.matches("util.format/0", SourceLanguage.ARKTS))
        .as("a bare import of util arrives as the same key the TypeScript side car emits")
        .isTrue();
  }

  @Test
  @DisplayName("a program's own module named util is not the formatter")
  void aProgramsOwnModuleNamedUtilIsNotTheFormatter() {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    SinkFamily family = config.sinkFamilies().byName("format-string.sinks").orElseThrow();

    assertThat(family.matches("./util.format/2", SourceLanguage.ARKTS))
        .as("a relative import arrives with the relative specifier, and the pattern is anchored")
        .isFalse();
    assertThat(familiesFor("./util.format/2", SourceLanguage.ARKTS))
        .as("no family may claim a helper the program wrote itself")
        .isEmpty();
  }

  // ------------------------------------------------- through the real front end

  @Test
  @DisplayName("a tainted value reaches the console through the real front end")
  void aTaintedValueReachesTheConsoleThroughTheRealFrontEnd(@TempDir Path directory)
      throws IOException {
    assumeTrue(
        sidecarAvailable(),
        "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
            + " plumb-frontend/arkts-sidecar");

    Files.writeString(directory.resolve("build-profile.json5"), "{}\n");
    Path file = directory.resolve("page.ets");
    Files.writeString(file, SOURCE);

    TacFrontend.ParseResult result;
    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      result = frontend.parse(SOURCE, file, null);
    }
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new LogInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) findings.add(finding);
    }

    boolean reported = false;
    for (Finding finding : findings) {
      if (finding.ruleId().equals(LOG_RULE) && finding.message().contains("Console.log")) {
        reported = true;
      }
    }
    assertThat(reported)
        .as("the console call must be recognised, and the value must still be tainted there")
        .isTrue();
  }

  /**
   * Whether the ArkTS side car can run here.
   *
   * @return true when Node and the sidecar's compiler dependency are present
   */
  private static boolean sidecarAvailable() {
    Path script = ArktsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null
        && Files.isDirectory(root.resolve("node_modules").resolve("ohos-typescript"));
  }
}
