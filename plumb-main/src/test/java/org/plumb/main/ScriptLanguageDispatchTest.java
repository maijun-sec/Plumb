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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The script languages: the ones whose source is a script rather than a compiled program.
 *
 * <h2>What this pins</h2>
 *
 * <p>Three things, and each of them has a way to fail silently:
 *
 * <ul>
 *   <li>the <b>dispatch</b> -- a {@code .ts} file must reach the TypeScript frontend, and a
 *       Java-only run must not build it;
 *   <li>the <b>gap</b> -- a language whose frontend does not exist yet must SAY so. A file that is
 *       collected by the discovery and then dropped without a word is indistinguishable from a file
 *       that was analysed and found clean, and the whole point of collecting it was to analyse it;
 *   <li>the <b>scope</b> -- a language the run did not select is out of scope, and that is a choice
 *       rather than a defect.
 * </ul>
 */
class ScriptLanguageDispatchTest {

  @Test
  @DisplayName("a .ts file is a source file Plumb can read")
  void aTsFileIsASourceFilePlumbCanRead() {
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.ts"))).isTrue();
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.tsx"))).isTrue();
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.js"))).isTrue();
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.mjs"))).isTrue();
  }

  @Test
  @DisplayName("the script extensions are in the table the file discovery uses")
  void theScriptExtensionsAreInTheTable() {
    assertThat(FrontendRegistry.knownExtensions())
        .contains("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs", "ets", "py", "php");
  }

  @Test
  @DisplayName("an .ets file is a source file Plumb can read")
  void anEtsFileIsASourceFilePlumbCanRead() {
    assertThat(FrontendRegistry.isSourceFile(Path.of("Index.ets"))).isTrue();
  }

  @Test
  @DisplayName("a .py file is a source file Plumb can read")
  void aPyFileIsASourceFilePlumbCanRead() {
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.py"))).isTrue();
  }

  @Test
  @DisplayName("Python is Python 3, and not Python 2")
  void pythonIsPython3AndNotPython2() {
    assertThat(FrontendRegistry.languageOfExtension("py")).isEqualTo(SourceLanguage.PYTHON3);
    assertThat(SourceLanguage.PYTHON3.name()).isEqualTo("PYTHON3");
    assertThat(SourceLanguage.values())
        .as("there is no language for Python 2: the parser reads Python 3's grammar")
        .noneMatch(language -> language.name().equals("PYTHON2"));
  }

  @Test
  @DisplayName("a .py file is routed to the Python frontend")
  void aPyFileIsRoutedToThePythonFrontend() {
    try (FrontendRegistry registry = new FrontendRegistry(null, List.of(), List.of())) {
      TacFrontend frontend = registry.frontendFor(SourceLanguage.PYTHON3);

      assertThat(frontend)
          .as("a frontend that is not on the scan path is not a capability")
          .isNotNull();
      assertThat(frontend.language()).isEqualTo(SourceLanguage.PYTHON3);
      assertThat(frontend.fileExtensions()).contains("py");
    }
  }

  @Test
  @DisplayName("ArkTS is a language of its own, not a flavour of TypeScript")
  void arktsIsALanguageOfItsOwn() {
    assertThat(FrontendRegistry.languageOfExtension("ets")).isEqualTo(SourceLanguage.ARKTS);
    assertThat(FrontendRegistry.languageOfExtension("ts")).isEqualTo(SourceLanguage.TYPESCRIPT);
  }

  @Test
  @DisplayName("an .ets file is routed to the ArkTS frontend")
  void anEtsFileIsRoutedToTheArktsFrontend() {
    try (FrontendRegistry registry = new FrontendRegistry(null, List.of(), List.of())) {
      TacFrontend frontend = registry.frontendFor(SourceLanguage.ARKTS);

      assertThat(frontend)
          .as("a frontend that is not on the scan path is not a capability")
          .isNotNull();
      assertThat(frontend.language()).isEqualTo(SourceLanguage.ARKTS);
      assertThat(frontend.fileExtensions()).contains("ets");
    }
  }

  @Test
  @DisplayName("JavaScript and TypeScript are two languages, not one")
  void javaScriptAndTypeScriptAreTwoLanguages() {
    assertThat(FrontendRegistry.languageOfExtension("ts")).isEqualTo(SourceLanguage.TYPESCRIPT);
    assertThat(FrontendRegistry.languageOfExtension("tsx")).isEqualTo(SourceLanguage.TYPESCRIPT);
    assertThat(FrontendRegistry.languageOfExtension("js")).isEqualTo(SourceLanguage.JAVASCRIPT);
    assertThat(FrontendRegistry.languageOfExtension("mjs")).isEqualTo(SourceLanguage.JAVASCRIPT);
  }

  @Test
  @DisplayName("a .ts file is routed to the TypeScript frontend")
  void aTsFileIsRoutedToTheTypeScriptFrontend() {
    try (FrontendRegistry registry = new FrontendRegistry(null, List.of(), List.of())) {
      TacFrontend frontend = registry.frontendFor(SourceLanguage.TYPESCRIPT);

      assertThat(frontend)
          .as("a frontend that is not on the scan path is not a capability")
          .isNotNull();
      assertThat(frontend.language()).isEqualTo(SourceLanguage.TYPESCRIPT);
      assertThat(frontend.fileExtensions()).contains("ts", "tsx");
    }
  }

  @Test
  @DisplayName("a .js file is routed to the JavaScript frontend")
  void aJsFileIsRoutedToTheJavaScriptFrontend() {
    try (FrontendRegistry registry = new FrontendRegistry(null, List.of(), List.of())) {
      TacFrontend frontend = registry.frontendFor(SourceLanguage.JAVASCRIPT);

      assertThat(frontend).isNotNull();
      assertThat(frontend.language()).isEqualTo(SourceLanguage.JAVASCRIPT);
      assertThat(frontend.fileExtensions()).contains("js", "jsx");
    }
  }

  @Test
  @DisplayName("a .php file is a source file Plumb can read")
  void aPhpFileIsASourceFilePlumbCanRead() {
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.php"))).isTrue();
  }

  @Test
  @DisplayName("a .php file is routed to the PHP frontend")
  void aPhpFileIsRoutedToThePhpFrontend() {
    try (FrontendRegistry registry = new FrontendRegistry(null, List.of(), List.of())) {
      TacFrontend frontend = registry.frontendFor(SourceLanguage.PHP);

      assertThat(frontend)
          .as("a frontend that is not on the scan path is not a capability")
          .isNotNull();
      assertThat(frontend.language()).isEqualTo(SourceLanguage.PHP);
      assertThat(frontend.fileExtensions()).contains("php");
    }
  }

  @Test
  @DisplayName("a Java-only run does not build the script frontends")
  void aJavaOnlyRunDoesNotBuildTheScriptFrontends() {
    try (FrontendRegistry registry =
        new FrontendRegistry(null, List.of(), List.of(), Set.of(SourceLanguage.JAVA))) {
      assertThat(registry.frontendFor(SourceLanguage.TYPESCRIPT)).isNull();
      assertThat(registry.frontendFor(SourceLanguage.JAVASCRIPT)).isNull();
      assertThat(registry.frontendFor(SourceLanguage.ARKTS)).isNull();
      assertThat(registry.frontendFor(SourceLanguage.PYTHON3)).isNull();
      assertThat(registry.frontendFor(SourceLanguage.PHP)).isNull();
    }
  }

  @Test
  @DisplayName("a file whose language Plumb does not know is reported, not dropped")
  void aFileWhoseLanguagePlumbDoesNotKnowIsReportedNotDropped(@TempDir Path directory)
      throws IOException {
    Path file = directory.resolve("app.rb");
    Files.writeString(file, "puts 'hello'\n");

    try (FrontendRegistry registry = new FrontendRegistry(null, List.of(directory), List.of())) {
      FrontendRegistry.ParsedSourceFile parsed = registry.parseFile(file);

      assertThat(parsed.functions()).isEmpty();
      assertThat(parsed.problems())
          .as("a collected file that was dropped without a word looks like a clean file")
          .hasSize(1);
      assertThat(parsed.problems().get(0).kind()).isEqualTo("FRONTEND_NOT_IMPLEMENTED");
      assertThat(parsed.problems().get(0).message()).contains("no frontend yet");
      assertThat(parsed.language())
          .as("the file's language is UNKNOWN, and saying so is better than guessing")
          .isEqualTo(SourceLanguage.UNKNOWN);
    }
  }

  @Test
  @DisplayName("a language the run did not select is out of scope")
  void aLanguageTheRunDidNotSelectIsOutOfScope(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("app.py");
    Files.writeString(file, "print('hello')\n");

    try (FrontendRegistry registry =
        new FrontendRegistry(null, List.of(directory), List.of(), Set.of(SourceLanguage.JAVA))) {
      FrontendRegistry.ParsedSourceFile parsed = registry.parseFile(file);

      assertThat(parsed.problems()).hasSize(1);
      assertThat(parsed.problems().get(0).kind())
          .as("a choice is not a defect")
          .isEqualTo("LANGUAGE_NOT_SELECTED");
    }
  }
}
