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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.capture.CaptureEntry;
import org.plumb.tac.io.SourcePathMapper;
import org.plumb.tac.io.SqliteTacStore;

/**
 * The three stages, end to end.
 *
 * <p><b>What this measures.</b> That the pipeline is a pipeline: the capture says what the build
 * did, the build turns that into a stored TAC, and the analysis reads the TAC and finds the defect
 * -- <b>with the source deleted in between</b>.
 *
 * <p>The deletion is the point. It is what separates "the analysis reads the store" from "the
 * analysis happens to work because the sources were still there". A test that leaves the sources in
 * place passes either way.
 */
@DisplayName("TAC: the three stages, end to end")
class TacPipelineTest {

  @TempDir Path tmp;

  /**
   * A source file with a SQL injection.
   *
   * @param dir the directory to write it in
   * @return the file
   */
  private static Path writeVulnerableSource(Path dir) throws Exception {
    Path src = dir.resolve("src");
    Files.createDirectories(src);
    Path file = src.resolve("Runner.java");
    Files.writeString(
        file,
        """
        class Runner {
          void run(String userInput) {
            String sql = "SELECT * FROM t WHERE name = '" + userInput + "'";
            java.sql.Statement st = null;
            st.executeQuery(sql);
          }
        }
        """,
        StandardCharsets.UTF_8);
    return file;
  }

  /**
   * A capture of the compile that produced the file.
   *
   * @param file the source file
   * @param sourceRoot the source root
   * @return the capture
   */
  private static CaptureEntry captureOf(Path file, Path sourceRoot) {
    return new CaptureEntry(
        "cli",
        "javac",
        "java",
        List.of("javac", "-d", "out", file.toString()),
        "UTF-8",
        "17",
        sourceRoot.resolve("out"),
        List.<Path>of(),
        List.of(sourceRoot),
        List.<Path>of(),
        List.<Path>of(),
        List.<Path>of(),
        List.of(file),
        List.<String>of(),
        List.<String>of(),
        List.<String>of(),
        false,
        true,
        "17.0.1",
        "/opt/jdk",
        sourceRoot.toString());
  }

  @Test
  @DisplayName("a capture becomes a compilation database")
  void aCaptureBecomesACompilationDatabase() throws Exception {
    Path file = writeVulnerableSource(tmp);
    Path captureDir = tmp.resolve("captures");
    Files.createDirectories(captureDir);
    Files.writeString(
        captureDir.resolve("javac-1.json"), captureOf(file, tmp.resolve("src")).toJson());

    Path database = tmp.resolve("compile_commands.json");
    int exit =
        TacCommands.run(
            "capture",
            List.of("--capture-dir", captureDir.toString(), "--output", database.toString()));

    assertThat(exit).as("the capture stage succeeds").isZero();
    assertThat(database).as("the database is written").exists();

    String text = Files.readString(database);
    assertThat(text)
        .as("the database is an array of entries with the three fields")
        .startsWith("[\n")
        .contains("\"directory\"")
        .contains("\"arguments\"")
        .contains("\"file\"");
  }

  @Test
  @DisplayName("the captures become a stored TAC")
  void theCapturesBecomeAStoredTac() throws Exception {
    Path file = writeVulnerableSource(tmp);
    Path captureDir = tmp.resolve("captures");
    Files.createDirectories(captureDir);
    Files.writeString(
        captureDir.resolve("javac-1.json"), captureOf(file, tmp.resolve("src")).toJson());

    Path outputDir = tmp.resolve("out");
    int exit =
        TacCommands.run(
            "build",
            List.of("--capture-dir", captureDir.toString(), "--output-dir", outputDir.toString()));

    assertThat(exit).as("the build stage succeeds").isZero();
    Path store = new OutputLayout(outputDir).tacDb();
    assertThat(store).as("the store is where the layout says it is").exists();

    try (SqliteTacStore tacStore = SqliteTacStore.open(store)) {
      assertThat(tacStore.size()).as("the program is in the store").isEqualTo(1);
      assertThat(tacStore.getMerged()).as("and so is the merged program").isNotNull();
    }
  }

  @Test
  @DisplayName("the stored TAC is analysed with the source deleted")
  void theStoredTacIsAnalysedWithTheSourceDeleted() throws Exception {
    Path file = writeVulnerableSource(tmp);
    Path captureDir = tmp.resolve("captures");
    Files.createDirectories(captureDir);
    Files.writeString(
        captureDir.resolve("javac-1.json"), captureOf(file, tmp.resolve("src")).toJson());

    Path outputDir = tmp.resolve("out");
    TacCommands.run(
        "build",
        List.of("--capture-dir", captureDir.toString(), "--output-dir", outputDir.toString()));

    // The source goes away. The analysis must not need it -- that is what
    // makes the store portable.
    Files.delete(file);

    Path report = tmp.resolve("findings.txt");
    int exit =
        TacCommands.run(
            "analyze",
            List.of(
                "--store", new OutputLayout(outputDir).tacDb().toString(),
                "--output", report.toString()));

    String text = Files.readString(report);
    assertThat(text)
        .as("the analysis ran on the store alone")
        .contains("1 function(s)")
        .contains("finding(s)");
    assertThat(text).as("and it found the injection").contains("dfa-taint");
  }

  @Test
  @DisplayName("the analysis names the consumer's path, not the producer's")
  void theAnalysisNamesTheConsumersPathNotTheProducers() throws Exception {
    Path file = writeVulnerableSource(tmp);
    Path captureDir = tmp.resolve("captures");
    Files.createDirectories(captureDir);
    Files.writeString(
        captureDir.resolve("javac-1.json"), captureOf(file, tmp.resolve("src")).toJson());

    Path outputDir = tmp.resolve("out");
    TacCommands.run(
        "build",
        List.of("--capture-dir", captureDir.toString(), "--output-dir", outputDir.toString()));
    Files.delete(file);

    // The producer built the store under /producer; the consumer's checkout
    // is at /consumer. The finding has to point at the consumer's path,
    // because that is the file the reader can open.
    Path report = tmp.resolve("findings.txt");
    TacCommands.run(
        "analyze",
        List.of(
            "--store",
            new OutputLayout(outputDir).tacDb().toString(),
            "--output",
            report.toString(),
            "--source-path-map",
            tmp.toAbsolutePath().normalize() + "=/consumer"));

    String text = Files.readString(report);
    assertThat(text)
        .as("the finding points at the consumer's path")
        .contains("consumer")
        .doesNotContain(tmp.toAbsolutePath().normalize().toString());
    // The producer's root is the temporary directory; the consumer's root is
    // a different place entirely. The finding must not name the producer's.
    assertThat(text).doesNotContain(tmp.getFileName().toString());
  }

  @Test
  @DisplayName("an empty store is an empty analysis, not a failure")
  void anEmptyStoreIsAnEmptyAnalysisNotAFailure() throws Exception {
    Path store = tmp.resolve("empty.db");
    try (SqliteTacStore tacStore = SqliteTacStore.open(store)) {
      IrAnalysisRunner.IrAnalysisResult result = new IrAnalysisRunner().analyzeStore(tacStore);
      assertThat(result.findings()).isEmpty();
      assertThat(result.analyzedFunctions()).isZero();
      assertThat(result.isComplete()).as("an empty project is complete").isTrue();
    }
  }

  @Test
  @DisplayName("the mapper is applied when the store is read")
  void theMapperIsAppliedWhenTheStoreIsRead() throws Exception {
    // The unit-level version of the property the pipeline test shows end to
    // end: the store's path is the producer's, and the mapper turns it into
    // the consumer's.
    Path producer = Path.of("/producer/src");
    Path consumer = Path.of("/consumer/src");
    SourcePathMapper mapper = SourcePathMapper.of(producer, consumer);

    assertThat(mapper.map(producer.resolve("Main.java")))
        .isEqualTo(consumer.resolve("Main.java").toAbsolutePath().normalize());
  }
}
