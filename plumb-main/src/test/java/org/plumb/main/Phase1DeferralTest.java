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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;

/**
 * The parse phase must not lose a file.
 *
 * <h2>What went wrong</h2>
 *
 * <p>The parallel parse phase asked the heap guard, per file, whether it may start another parse.
 * When the guard said no, the worker returned {@code null}. The caller could not tell that apart
 * from "the file could not be parsed", because both were {@code null}, so it skipped the file with
 * a {@code continue}.
 *
 * <p>The comment above that {@code continue} said the opposite -- "Do it here, on the caller's
 * thread, so the file is not silently dropped" -- and the code did not do it. The consequence was a
 * scan that quietly analysed less than it was asked to: the log line said {@code phase-1
 * parse+translate+style on 6506 of 7047 files}, the report said nothing about the 541 files that
 * were never looked at, and the two facts looked the same as "those files are clean".
 *
 * <h2>Why the guard is a parameter here</h2>
 *
 * <p>The guard reads the real heap, and a test cannot make a real heap full on demand. So the "may
 * I start more work" question is a parameter of {@link ScanRunner#translateAll}, and the test
 * supplies the answer. That is also the only way to test a SILENT failure: a failure that says
 * nothing cannot be observed unless it is provoked.
 */
class Phase1DeferralTest {

  @Test
  @DisplayName("a file the heap guard refuses is still analysed")
  void aRefusedFileIsStillAnalysed(@TempDir Path dir) throws Exception {
    List<Path> files = writeJavaFiles(dir, 3);
    ScanRunner runner = runner();
    AtomicInteger consulted = new AtomicInteger();

    List<ScanRunner.TranslatedFile> out =
        runner.translateAll(
            files,
            new RuleRegistry(),
            () -> {
              consulted.incrementAndGet();
              return false;
            });

    assertThat(consulted.get())
        .as("the guard has to be asked about every file")
        .isEqualTo(files.size());
    assertThat(out)
        .as("a file the guard refused must still be analysed, on the caller's thread")
        .hasSize(files.size());
  }

  @Test
  @DisplayName("a file the heap guard allows is analysed")
  void anAllowedFileIsAnalysed(@TempDir Path dir) throws Exception {
    List<Path> files = writeJavaFiles(dir, 3);
    ScanRunner runner = runner();
    AtomicInteger consulted = new AtomicInteger();

    List<ScanRunner.TranslatedFile> out =
        runner.translateAll(
            files,
            new RuleRegistry(),
            () -> {
              consulted.incrementAndGet();
              return true;
            });

    assertThat(consulted.get()).isEqualTo(files.size());
    assertThat(out).hasSize(files.size());
  }

  @Test
  @DisplayName("one refusal does not cost the other files")
  void oneRefusalDoesNotCostTheOthers(@TempDir Path dir) throws Exception {
    List<Path> files = writeJavaFiles(dir, 4);
    ScanRunner runner = runner();
    AtomicInteger consulted = new AtomicInteger();

    List<ScanRunner.TranslatedFile> out =
        runner.translateAll(
            files,
            new RuleRegistry(),
            // Refuse exactly one file. WHICH file is refused depends on the order the
            // workers happen to take the files in, and it does not matter: the count is
            // what the fix is about.
            () -> consulted.incrementAndGet() == 1);

    assertThat(consulted.get()).isEqualTo(files.size());
    assertThat(out).hasSize(files.size());
  }

  @Test
  @DisplayName("a refused file is still analysed when the style checks run too")
  void aRefusedFileIsStillAnalysedWithStyle(@TempDir Path dir) throws Exception {
    List<Path> files = writeJavaFiles(dir, 3);
    ScanConfig cfg = new ScanConfig();
    cfg.runStyle = true;
    ScanRunner runner = new ScanRunner(cfg);

    List<ScanRunner.TranslatedFile> out =
        runner.translateAll(files, new RuleRegistry(), () -> false);

    assertThat(out)
        .as("the style path is the production default, and it must not lose a file either")
        .hasSize(files.size());
  }

  @Test
  @DisplayName("a file that cannot be parsed is recorded, and the others are still analysed")
  void anUnreadableFileIsRecorded(@TempDir Path dir) throws Exception {
    List<Path> good = writeJavaFiles(dir, 3);
    Path broken = writeUnreadableJavaFile(dir);
    List<Path> files = new ArrayList<>(good);
    files.add(broken);

    ScanRunner runner = runner();
    List<ScanRunner.TranslatedFile> out =
        runner.translateAll(files, new RuleRegistry(), () -> false);

    assertThat(out)
        .as("the readable files are analysed even when the guard refuses every file")
        .hasSize(good.size());
    assertThat(runner.unparsedFiles())
        .as("a file that could not be read has to be recorded, not dropped")
        .containsExactly(broken.toString());
    assertThat(runner.outOfMemoryFiles())
        .as("a file that could not be read is not a file that ran the heap out")
        .isEmpty();
  }

  @Test
  @DisplayName("the per-file result does not hold the parse tree")
  void theResultDoesNotHoldTheParseTree() {
    for (java.lang.reflect.RecordComponent component :
        ScanRunner.TranslatedFile.class.getRecordComponents()) {
      assertThat(component.getType())
          .as(
              "the per-file result must not keep the parse tree alive: a scan of a few"
                  + " thousand files held a few thousand whole parse trees at once, and"
                  + " that is what made the heap guard refuse work in the first place")
          .isNotEqualTo(ParsedSource.class);
    }
  }

  @Test
  @DisplayName("the report says which file was not analysed")
  void theReportNamesTheFileThatWasNotAnalysed(@TempDir Path dir, @TempDir Path out)
      throws Exception {
    Path good = dir.resolve("Good.java");
    Files.writeString(good, "class Good { void m() { int x = 1; } }");
    Path broken = writeUnreadableJavaFile(dir);
    Path report = out.resolve("report.sarif");

    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(good);
    cfg.inputs.add(broken);
    cfg.outputFormat = ScanConfig.OutputFormat.SARIF;
    cfg.outputPath = report;
    int rc = new ScanRunner(cfg).run();

    assertThat(rc).isZero();
    String sarif = Files.readString(report);
    assertThat(sarif)
        .as("the report must carry the completeness record")
        .contains("could not be parsed");
    assertThat(sarif).as("the report must name the file").contains("Broken.java");
  }

  /** A runner that does not run the style checks, so the test is about the parse phase. */
  private static ScanRunner runner() {
    ScanConfig cfg = new ScanConfig();
    cfg.runStyle = false;
    return new ScanRunner(cfg);
  }

  /** Write {@code count} readable Java files into {@code dir}. */
  private static List<Path> writeJavaFiles(Path dir, int count) throws Exception {
    List<Path> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Path f = dir.resolve("Sample" + i + ".java");
      Files.writeString(
          f, "package demo; class Sample" + i + " { int m(int a) { int b = a + 1; return b; } }");
      out.add(f);
    }
    return out;
  }

  /**
   * A {@code .java} file that cannot be read as source.
   *
   * <p>The bytes are not valid UTF-8, so reading the file as source fails. That is the realistic
   * "this file could not be parsed" case: the parser itself is error tolerant and hands back an
   * empty tree for a syntax error, but it cannot hand back a tree for bytes that are not text.
   */
  private static Path writeUnreadableJavaFile(Path dir) throws Exception {
    Path broken = dir.resolve("Broken.java");
    Files.write(broken, new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0x00, (byte) 0x41});
    return broken;
  }
}
