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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.capture.CaptureEntry;
import org.plumb.tac.ir.SourceLanguage;

/**
 * What a capture tells the TAC pipeline that a source tree does not.
 *
 * <p><b>What this measures.</b> The bridge's job is to read the compiler's own report of the
 * compile it performed -- the classpath it resolved, the roots it searched, the level it compiled
 * at -- and hand those to the frontends. A bridge that guesses instead of reads produces an
 * analysis whose unresolved types look like clean code.
 */
@DisplayName("TAC: the captures tell the pipeline what to resolve against")
class CaptureToTacBridgeTest {

  @TempDir Path tmp;

  /**
   * A capture with the fields the bridge reads.
   *
   * @param language the language
   * @param classpath the classpath
   * @param sourcePath the source path
   * @param sourceFiles the source files
   * @param languageLevel the language level
   */
  private static CaptureEntry capture(
      String language,
      List<Path> classpath,
      List<Path> sourcePath,
      List<Path> sourceFiles,
      String languageLevel) {
    return new CaptureEntry(
        "cli",
        "javac",
        language,
        List.of("javac", "-d", "out"),
        "UTF-8",
        languageLevel,
        Path.of("out"),
        classpath,
        sourcePath,
        List.<Path>of(),
        List.<Path>of(),
        List.<Path>of(),
        sourceFiles,
        List.<String>of(),
        List.<String>of(),
        List.<String>of(),
        false,
        true,
        "17.0.1",
        "/opt/jdk",
        "/work");
  }

  @Test
  @DisplayName("the capture's classpath reaches the pipeline unchanged")
  void theCapturesClasspathReachesThePipelineUnchanged() {
    Path lib = tmp.resolve("lib.jar");
    Path other = tmp.resolve("other.jar");

    List<CaptureEntry> captures =
        List.of(
            capture(
                "java", List.of(lib, other), List.of(tmp), List.of(tmp.resolve("A.java")), "17"));

    assertThat(CaptureToTacBridge.classpathOf(captures))
        .as("the classpath is the compiler's own, not a re-derived guess")
        .containsExactly(lib, other);
  }

  @Test
  @DisplayName("the source roots reach the pipeline unchanged")
  void theSourceRootsReachThePipelineUnchanged() {
    Path src = tmp.resolve("src");
    Path gen = tmp.resolve("generated");

    List<CaptureEntry> captures =
        List.of(
            capture(
                "java", List.<Path>of(), List.of(src, gen), List.of(src.resolve("A.java")), "17"));

    assertThat(CaptureToTacBridge.sourceRootsOf(captures))
        .as("the roots the compiler searched are the roots the analysis uses")
        .containsExactly(src, gen);
  }

  @Test
  @DisplayName("a file belongs to the longest source root that contains it")
  void aFileBelongsToTheLongestSourceRootThatContainsIt() {
    Path project = tmp.resolve("project");
    Path module = project.resolve("service").resolve("src");
    Path file = module.resolve("Main.java");

    // The roots nest, and the inner one is the module boundary.
    List<Path> roots = List.of(project, module);

    assertThat(CaptureToTacBridge.rootOf(file, roots))
        .as("the module's root is the boundary the analysis reports against")
        .isEqualTo(module.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a file outside every root falls back to its own directory")
  void aFileOutsideEveryRootFallsBackToItsOwnDirectory() {
    Path file = tmp.resolve("loose").resolve("A.java");

    assertThat(CaptureToTacBridge.rootOf(file, List.of(tmp.resolve("nowhere"))))
        .as("there is no module boundary, so the file's directory is the closest thing")
        .isEqualTo(file.getParent().toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a file that is gone is not handed to the frontend")
  void aFileThatIsGoneIsNotHandedToTheFrontend() {
    Path present = tmp.resolve("Present.java");
    Path missing = tmp.resolve("Missing.java");
    try {
      Files.writeString(present, "class Present {}");
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    List<CaptureEntry> captures =
        List.of(capture("java", List.<Path>of(), List.of(tmp), List.of(present, missing), "17"));

    assertThat(CaptureToTacBridge.sourceFilesOf(captures))
        .as("a deleted file cannot be translated, and asking for it would be a parse error")
        .containsExactly(present);
  }

  @Test
  @DisplayName("the language comes from the capture, not from the compiler")
  void theLanguageComesFromTheCaptureNotFromTheCompiler() {
    // javac compiles Java. kotlinc compiles Kotlin. The compiler is the
    // PROGRAM; the language is what the program compiles, and a capture records
    // both.
    CaptureEntry kotlin =
        new CaptureEntry(
            "cli",
            "kotlin",
            "kotlin",
            List.of("kotlinc", "Main.kt"),
            "UTF-8",
            "2.0",
            Path.of("out"),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.<String>of(),
            List.<String>of(),
            List.<String>of(),
            false,
            true,
            "17.0.1",
            "/opt/jdk",
            "/work");

    assertThat(CaptureToTacBridge.languageOf(kotlin)).isEqualTo(SourceLanguage.KOTLIN);
    assertThat(
            CaptureToTacBridge.languageOf(capture("java", List.of(), List.of(), List.of(), "17")))
        .isEqualTo(SourceLanguage.JAVA);
  }

  @Test
  @DisplayName("a capture without a language falls back to the file extension")
  void aCaptureWithoutALanguageFallsBackToTheFileExtension() {
    CaptureEntry noLanguage =
        new CaptureEntry(
            "cli",
            "javac",
            null,
            List.of("javac"),
            "UTF-8",
            "17",
            Path.of("out"),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.of(tmp.resolve("A.scala")),
            List.<String>of(),
            List.<String>of(),
            List.<String>of(),
            false,
            true,
            "17.0.1",
            "/opt/jdk",
            "/work");

    assertThat(CaptureToTacBridge.languageOf(noLanguage))
        .as("the extension still says which language the files are")
        .isEqualTo(SourceLanguage.SCALA);
  }

  @Test
  @DisplayName("the highest language level wins")
  void theHighestLanguageLevelWins() {
    List<CaptureEntry> captures =
        List.of(
            capture("java", List.of(), List.of(), List.of(), "11"),
            capture("java", List.of(), List.of(), List.of(), "17"),
            capture("java", List.of(), List.of(), List.of(), "8"));

    assertThat(CaptureToTacBridge.languageLevelOf(captures))
        .as("a project has to be analysed at the highest level it uses")
        .isEqualTo("17");
  }

  @Test
  @DisplayName("the old spelling of a language level is understood")
  void theOldSpellingOfALanguageLevelIsUnderstood() {
    List<CaptureEntry> captures =
        List.of(
            capture("java", List.of(), List.of(), List.of(), "1.8"),
            capture("java", List.of(), List.of(), List.of(), "21"));

    assertThat(CaptureToTacBridge.languageLevelOf(captures))
        .as("1.8 is the old spelling of 8, and 21 is newer than both")
        .isEqualTo("21");
  }

  @Test
  @DisplayName("several captures contribute one classpath")
  void severalCapturesContributeOneClasspath() {
    Path a = tmp.resolve("a.jar");
    Path b = tmp.resolve("b.jar");

    List<CaptureEntry> captures =
        List.of(
            capture("java", List.of(a), List.of(tmp), List.of(), "17"),
            capture("java", List.of(a, b), List.of(tmp), List.of(), "17"));

    assertThat(CaptureToTacBridge.classpathOf(captures))
        .as("the union, deduplicated, in the order it was seen")
        .containsExactly(a, b);
  }
}
