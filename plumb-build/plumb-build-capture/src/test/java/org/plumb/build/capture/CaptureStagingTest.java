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
package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the recorder's staging of a compile.
 *
 * <p><b>Why this is worth testing.</b> The capture is written when the compile <em>ends</em>, not
 * when it starts, because the sources the compiler really compiled are only known at the end. The
 * options are therefore staged at the start and taken at the end.
 *
 * <p>That staging has two ways to go wrong, and both of them were wrong at some point:
 *
 * <ul>
 *   <li>The staged compile is taken by position, so a build that has more than one compile in
 *       flight reports the wrong options. Gradle, for one, creates the task for the next module
 *       before the previous module's compile has finished.
 *   <li>The staged compile is taken by draining all of them, so the next module's options are
 *       consumed by the previous module's capture.
 * </ul>
 *
 * <p>The fix is to <em>match</em> the staged compile to the compile that finished, by the sources
 * the compiler parsed.
 *
 * <p><b>Why the captures are read from the directory.</b> The recorder runs on the bootstrap class
 * loader and cannot reach the in-memory sink, so it writes the files itself. Reading the files back
 * is what an analyser does, and it is what makes this test an end-to-end check of the staging
 * rather than a check of the recorder's internals.
 *
 * <p><b>Why every test uses its own source names.</b> The recorder's duplicate check is on the
 * compile's identity, and the identity includes the sources. Two tests that compiled the same files
 * would be the same compile, and the second one's capture would be discarded as a duplicate.
 */
class CaptureStagingTest {

  /** Where the recorder writes. The same directory the test reads back. */
  @TempDir Path dir;

  @BeforeEach
  void pointTheRecorderAtTheTempDirectory() {
    System.setProperty(CaptureRecorder.PROP_OUTPUT_DIR, dir.toString());
  }

  @AfterEach
  void clearTheOutputDirectory() {
    System.clearProperty(CaptureRecorder.PROP_OUTPUT_DIR);
  }

  @Test
  void aFinishedCompileTakesTheOptionsThatWereStagedForIt() {
    // The API path: the options arrive with the task, and the compile ends
    // later.
    CaptureRecorder.onApiOptions(List.of("-d", "out", "Alone.java"), List.of("Alone.java"));
    CaptureRecorder.rememberParsedSource("Alone.java");
    CaptureRecorder.onCompileFinished(CompilerOption.Compiler.JAVAC);

    CaptureEntry written = onlyCapture();
    assertThat(written.argv()).containsExactly("-d", "out", "Alone.java");
    assertThat(written.sourceFiles()).containsExactly(Path.of("Alone.java"));
  }

  @Test
  void aSecondCompileDoesNotInheritTheFirstCompilesOptions() {
    // The first compile.
    CaptureRecorder.onApiOptions(List.of("-d", "one", "One.java"), List.of("One.java"));
    CaptureRecorder.rememberParsedSource("One.java");
    CaptureRecorder.onCompileFinished(CompilerOption.Compiler.JAVAC);

    // The second compile, staged before the first one is written -- which is
    // what a build that compiles module after module does.
    CaptureRecorder.onApiOptions(List.of("-d", "two", "Two.java"), List.of("Two.java"));
    CaptureRecorder.rememberParsedSource("Two.java");
    CaptureRecorder.onCompileFinished(CompilerOption.Compiler.JAVAC);

    // The second capture must report the second compile, not the first.
    List<CaptureEntry> captures = CaptureReader.readAll(dir);
    assertThat(captures).hasSize(2);
    CaptureEntry second = captures.get(captures.size() - 1);
    assertThat(second.argv()).contains("-d", "two");
    assertThat(second.argv()).doesNotContain("one");
    assertThat(second.sourceFiles()).containsExactly(Path.of("Two.java"));
  }

  /**
   * Two compiles staged before either of them finishes, and the first one finishes first.
   *
   * <p>This is the shape that caught the real defect. Gradle creates the task for the next module
   * before the previous module's compile has finished, so when the previous module's compile ends
   * there are two staged compiles -- and the one that belongs to it is <em>not</em> the most recent
   * one.
   *
   * <p>So the staged compile is chosen by matching: the one whose sources are the ones the compiler
   * actually parsed. Taking the most recent one instead would report the next module's options for
   * this module's compile.
   */
  @Test
  void aFinishedCompileTakesTheStagedCompileThatMatchesItsSources() {
    // Both compiles are staged before either of them finishes.
    CaptureRecorder.onApiOptions(List.of("-d", "early", "Early.java"), List.of("Early.java"));
    CaptureRecorder.onApiOptions(List.of("-d", "late", "Late.java"), List.of("Late.java"));

    // The FIRST compile finishes, having parsed the first compile's source.
    // The most recently staged compile is the other one.
    CaptureRecorder.rememberParsedSource("Early.java");
    CaptureRecorder.onCompileFinished(CompilerOption.Compiler.JAVAC);

    List<CaptureEntry> captures = CaptureReader.readAll(dir);
    assertThat(captures).as("the early compile must have been written").hasSize(1);
    CaptureEntry early = captures.get(0);
    assertThat(early.argv()).contains("-d", "early");
    assertThat(early.argv()).doesNotContain("late");
    assertThat(early.sourceFiles()).containsExactly(Path.of("Early.java"));
  }

  @Test
  void theParsedSourcesAreNotCarriedOverToTheNextCompile() {
    CaptureRecorder.onApiOptions(List.of("-d", "carry", "Carried.java"), List.of("Carried.java"));
    CaptureRecorder.rememberParsedSource("Carried.java");
    CaptureRecorder.onCompileFinished(CompilerOption.Compiler.JAVAC);

    // A compile that parses nothing: the first compile's parsed sources must
    // not leak into it.
    CaptureRecorder.onApiOptions(List.of("-d", "empty"), List.of());
    CaptureRecorder.onCompileFinished(CompilerOption.Compiler.JAVAC);

    List<CaptureEntry> captures = CaptureReader.readAll(dir);
    CaptureEntry second = captures.get(captures.size() - 1);
    assertThat(second.sourceFiles())
        .as("the second compile must not report the first compile's sources")
        .doesNotContain(Path.of("Carried.java"));
  }

  @Test
  void aCompileThatWasNeverStagedWritesNothing() {
    // The agent saw the compile only at its end: there are no options, and a
    // capture without options is useless.
    CaptureRecorder.rememberParsedSource("Unstaged.java");
    CaptureRecorder.onCompileFinished(CompilerOption.Compiler.JAVAC);

    assertThat(CaptureReader.readAll(dir)).isEmpty();
  }

  /** The only capture written so far. */
  private CaptureEntry onlyCapture() {
    List<CaptureEntry> captures = CaptureReader.readAll(dir);
    assertThat(captures).as("exactly one capture must have been written").hasSize(1);
    return captures.get(0);
  }
}
