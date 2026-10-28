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
package org.plumb.build.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.CompileDiscrepancy;

/**
 * Tests for {@link CompileCrossCheck}.
 *
 * <p><b>Why this is worth having.</b> The two sides of the compile-parameter story answer the same
 * questions from two directions, and they are used together. When they disagree, one of them is
 * wrong -- and the analysis is wrong in a way nobody can see, because the two answers are used in
 * different places.
 *
 * <p>So the difference is reported. The test is about <em>which</em> differences are reported: a
 * difference in what is <em>known</em> is not a difference, and reporting it would be noise.
 */
class CompileCrossCheckTest {

  /** A capture whose language level differs from the declaration. */
  private static final String CAPTURE_WITH_LEVEL_17 =
      """
      {
        "api": "api",
        "compiler": "javac",
        "argv": ["-d", "out"],
        "encoding": "UTF-8",
        "languageLevel": "17",
        "outputDirectory": "D:/project/target/classes",
        "classpath": ["D:/repo/a.jar", "D:/repo/b.jar"],
        "sourcePath": [],
        "modulePath": [],
        "processorPath": [],
        "bootClassPath": [],
        "sourceFiles": ["D:/project/src/main/java/Main.java"],
        "preview": false,
        "compilation": true
      }
      """;

  @Test
  void reportsALanguageLevelThatDiffers() {
    ModuleDescriptor declared = descriptor().languageLevel("11").build();

    List<CompileDiscrepancy> differences =
        CompileCrossCheck.compare(CAPTURE_WITH_LEVEL_17, declared);

    assertThat(differences).hasSize(1);
    CompileDiscrepancy difference = differences.get(0);
    assertThat(difference.parameter()).isEqualTo(CompileCrossCheck.LANGUAGE_LEVEL);
    assertThat(difference.declared()).isEqualTo("11");
    assertThat(difference.observed()).isEqualTo("17");
    assertThat(difference.isConflict()).isTrue();
  }

  @Test
  void reportsNothingWhenTheTwoSidesAgree() {
    ModuleDescriptor declared =
        descriptor()
            .languageLevel("17")
            .encoding(StandardCharsets.UTF_8)
            .outputDirectory(Path.of("D:/project/target/classes"))
            .classpath(List.of(Path.of("D:/repo/a.jar"), Path.of("D:/repo/b.jar")))
            .build();

    List<CompileDiscrepancy> differences =
        CompileCrossCheck.compare(CAPTURE_WITH_LEVEL_17, declared);

    assertThat(differences).isEmpty();
  }

  /**
   * A side that says nothing is not a contradiction.
   *
   * <p>An undeclared parameter is unknown, not a default. So a capture that does not report a
   * language level, against a pom that declares one, is not a difference -- it is a gap, and the
   * gap is already visible.
   */
  @Test
  void anUnknownValueIsNotADifference() {
    ModuleDescriptor declared = descriptor().languageLevel("11").build();

    String captureWithoutALevel =
        """
        {
          "api": "api",
          "compiler": "javac",
          "encoding": null,
          "languageLevel": null,
          "outputDirectory": null,
          "classpath": [],
          "sourcePath": [],
          "sourceFiles": []
        }
        """;

    List<CompileDiscrepancy> differences =
        CompileCrossCheck.compare(captureWithoutALevel, declared);

    assertThat(differences).isEmpty();
  }

  /** A classpath entry on one side and not the other is a difference. */
  @Test
  void reportsAClasspathThatDiffers() {
    ModuleDescriptor declared =
        descriptor().classpath(List.of(Path.of("D:/repo/a.jar"), Path.of("D:/repo/c.jar"))).build();

    List<CompileDiscrepancy> differences =
        CompileCrossCheck.compare(CAPTURE_WITH_LEVEL_17, declared);

    assertThat(differences).hasSize(1);
    CompileDiscrepancy difference = differences.get(0);
    assertThat(difference.parameter()).isEqualTo(CompileCrossCheck.CLASSPATH);
    assertThat(difference.detail()).contains("c.jar");
  }

  /**
   * The differences come out in a fixed order.
   *
   * <p>Two runs over the same inputs must produce the same report. A report whose order depends on
   * a hash map's iteration order is a report nobody can diff.
   */
  @Test
  void theDifferencesAreSortedByParameter() {
    ModuleDescriptor declared =
        descriptor()
            .languageLevel("11")
            .encoding(StandardCharsets.ISO_8859_1)
            .outputDirectory(Path.of("D:/project/other"))
            .classpath(List.of(Path.of("D:/repo/c.jar")))
            .build();

    List<CompileDiscrepancy> differences =
        CompileCrossCheck.compare(CAPTURE_WITH_LEVEL_17, declared);

    assertThat(differences).isNotEmpty();
    List<String> parameters = differences.stream().map(CompileDiscrepancy::parameter).toList();
    assertThat(parameters).isSorted();
  }

  /** Something that is not a capture produces no differences, rather than a wrong one. */
  @Test
  void somethingThatIsNotACaptureProducesNothing() {
    ModuleDescriptor declared = descriptor().languageLevel("11").build();

    assertThat(CompileCrossCheck.compare("{\"not\":\"a capture\"}", declared)).isEmpty();
    assertThat(CompileCrossCheck.compare("", declared)).isEmpty();
    assertThat(CompileCrossCheck.compare(null, declared)).isEmpty();
  }

  /** The capture is read from the file the agent wrote. */
  @Test
  void readsACaptureFromItsFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("javac-1.json");
    Files.writeString(file, CAPTURE_WITH_LEVEL_17, StandardCharsets.UTF_8);

    ModuleDescriptor declared = descriptor().languageLevel("11").build();

    CompileCrossCheck.CapturedCompile captured = CompileCrossCheck.CapturedCompile.read(file);

    assertThat(captured).isNotNull();
    assertThat(captured.languageLevel()).isEqualTo("17");
    assertThat(captured.encoding()).isEqualTo("UTF-8");
    assertThat(captured.classpath()).hasSize(2);
    assertThat(captured.sourceFiles()).hasSize(1);
  }

  private static ModuleDescriptor.Builder descriptor() {
    return ModuleDescriptor.builder("demo");
  }
}
