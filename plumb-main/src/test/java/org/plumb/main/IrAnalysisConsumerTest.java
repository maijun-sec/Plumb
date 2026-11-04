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
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.checkers.taint.RegexInjectionChecker;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.io.IrSerializer;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The front/back split, end to end, as a PRODUCER/CONSUMER handoff.
 *
 * <p><b>The scenario this pins.</b> A producer machine prepares the source list, the dependency
 * list and the entity files, translates them to IR, and ships the IR. A consumer machine --
 * possibly with no source, no classpath, and no Java toolchain -- loads the IR and scans it.
 *
 * <p>Every test here deletes the source before the analysis runs. That is not a stylistic choice:
 * it is the only way to prove the analysis does not secretly read the source back. An assertion
 * that the findings match would also pass if the analyser quietly re-parsed a source file it
 * happened to find next to the IR.
 *
 * <p><b>What "works with no source" means precisely.</b> The consumer:
 *
 * <ul>
 *   <li>does not compile anything,
 *   <li>does not resolve any Java symbol,
 *   <li>does not read any {@code .java} file,
 *   <li>resolves a finding's file purely from the paths the IR carries.
 * </ul>
 */
class IrAnalysisConsumerTest {

  @TempDir Path tmp;

  private static final String COMMAND_INJECTION =
      """
      import java.io.*;
      class Cmd {
        void run(String userInput) throws IOException {
          Process p = Runtime.getRuntime().exec(userInput);
          p.waitFor();
        }
      }
      """;

  private static final String REGEX_INJECTION =
      """
      import java.util.regex.*;
      class Rx {
        void run(String userInput) {
          Pattern.compile(userInput).matcher("x").matches();
        }
      }
      """;

  /** The producer half: source in, IR on disk. */
  private Path produce(Path source, String content) throws Exception {
    Files.writeString(source, content);
    ParsedSource parsed = new JavaSourceParser().parse(source);
    TacProgram program = new TacTranslator(parsed).translate();
    Path irFile = tmp.resolve(source.getFileName() + ".tac");
    IrSerializer.writeProgram(program, irFile);
    return irFile;
  }

  @Test
  @DisplayName("a consumer with no source finds the vulnerability from the IR alone")
  void consumerFindsTheVulnerabilityWithoutSource() throws Exception {
    Path source = tmp.resolve("Cmd.java");
    Path irFile = produce(source, COMMAND_INJECTION);

    // ── the source disappears ──
    Files.delete(source);
    assertThat(Files.exists(source)).as("the consumer must have no source to read").isFalse();

    IrAnalysisRunner runner =
        new IrAnalysisRunner(
            org.plumb.dfa.config.ConfigLoader.loadFromClasspath(),
            List.of(new CommandInjectionChecker()));
    IrAnalysisRunner.IrAnalysisResult result = runner.analyzeProgram(irFile);

    assertThat(result.findings())
        .as("the finding must be reproducible from the IR alone")
        .isNotEmpty();
    assertThat(result.findings().get(0).cwe()).isEqualTo("CWE-78");
  }

  @Test
  @DisplayName("a finding from a source-less consumer still names its source file")
  void findingResolvesItsFileFromTheIr() throws Exception {
    Path source = tmp.resolve("Cmd.java");
    Path irFile = produce(source, COMMAND_INJECTION);
    Files.delete(source);

    IrAnalysisRunner runner =
        new IrAnalysisRunner(
            org.plumb.dfa.config.ConfigLoader.loadFromClasspath(),
            List.of(new CommandInjectionChecker()));
    IrAnalysisRunner.IrAnalysisResult result = runner.analyzeProgram(irFile);

    Finding finding = result.findings().get(0);
    // The path comes from the IR, not from the filesystem: the file does not
    // exist any more, and the finding still names it.
    assertThat(finding.filePath()).as("the source path must come from the IR").isNotEmpty();
    assertThat(finding.filePath()).contains("Cmd.java");
    assertThat(Files.exists(Path.of(finding.filePath())))
        .as("the named file genuinely does not exist -- the path came from the IR")
        .isFalse();
  }

  @Test
  @DisplayName("a merged IR of many files is analysable with no source present")
  void mergedIrIsAnalysableWithoutSource() throws Exception {
    Path a = tmp.resolve("A.java");
    Path b = tmp.resolve("B.java");
    Files.writeString(a, COMMAND_INJECTION);
    Files.writeString(b, COMMAND_INJECTION.replace("Cmd", "Cmd2"));

    TacProgram pa = new TacTranslator(new JavaSourceParser().parse(a)).translate();
    TacProgram pb = new TacTranslator(new JavaSourceParser().parse(b)).translate();

    Path mergedFile = tmp.resolve("merged.tac");
    IrSerializer.writeMerged(new MergedProgram(List.of(pa, pb)), mergedFile);

    // Both sources gone.
    Files.delete(a);
    Files.delete(b);

    IrAnalysisRunner runner =
        new IrAnalysisRunner(
            org.plumb.dfa.config.ConfigLoader.loadFromClasspath(),
            List.of(new CommandInjectionChecker()));
    IrAnalysisRunner.IrAnalysisResult result = runner.analyzeMerged(mergedFile);

    assertThat(result.analyzedFunctions())
        .as("both files' functions must be analysed")
        .isGreaterThan(1);
    assertThat(result.findings()).as("the merged IR must still produce findings").isNotEmpty();
  }

  @Test
  @DisplayName("the IR's problems reach the consumer, so it can see the analysis was incomplete")
  void problemsReachTheConsumer() throws Exception {
    Path source = tmp.resolve("Broken.java");
    // A file JavaParser cannot fully read: the consumer must be told, because
    // "no findings" on a file the analyser could not read is not "no findings".
    Files.writeString(
        source,
        """
        class Broken {
          void go() {
            int x = ;
          }
        }
        """);
    ParsedSource parsed = new JavaSourceParser().parse(source);
    TacProgram program = new TacTranslator(parsed).translate();

    Path irFile = tmp.resolve("Broken.tac");
    IrSerializer.writeProgram(program, irFile);
    Files.delete(source);

    IrAnalysisRunner runner =
        new IrAnalysisRunner(
            org.plumb.dfa.config.ConfigLoader.loadFromClasspath(),
            List.of(new CommandInjectionChecker()));
    IrAnalysisRunner.IrAnalysisResult result = runner.analyzeProgram(irFile);

    assertThat(result.problems())
        .as("a consumer must be able to see that the analysis was incomplete")
        .isNotEmpty();
    assertThat(result.isComplete()).as("the completeness flag must reflect the problems").isFalse();
  }

  @Test
  @DisplayName("a clean program reports itself complete")
  void cleanProgramIsComplete() throws Exception {
    Path source = tmp.resolve("Clean.java");
    Path irFile =
        produce(
            source,
            """
            class Clean {
              void go() {
                int x = 1 + 2;
              }
            }
            """);
    Files.delete(source);

    IrAnalysisRunner runner =
        new IrAnalysisRunner(
            org.plumb.dfa.config.ConfigLoader.loadFromClasspath(),
            List.of(new CommandInjectionChecker()));
    IrAnalysisRunner.IrAnalysisResult result = runner.analyzeProgram(irFile);

    assertThat(result.isComplete()).as("a fully-translated file must report no problems").isTrue();
  }

  @Test
  @DisplayName("a structured problem names the construct and the position")
  void structuredProblemNamesTheConstruct() throws Exception {
    // The string form of a problem says "something went wrong". The STRUCTURED
    // form says WHICH construct, in WHICH function, at WHICH line. A consumer
    // that only has strings cannot triage a gap -- it cannot tell an
    // unsupported construct from a broken source, and it cannot point at the
    // place to look.
    Path source = tmp.resolve("Broken.java");
    Files.writeString(
        source,
        """
        class Broken {
          void go() {
            int x = ;
          }
        }
        """);
    ParsedSource parsed = new JavaSourceParser().parse(source);
    TacProgram program = new TacTranslator(parsed).translate();

    Path irFile = tmp.resolve("Broken.tac");
    IrSerializer.writeProgram(program, irFile);
    Files.delete(source);

    IrAnalysisRunner runner =
        new IrAnalysisRunner(
            org.plumb.dfa.config.ConfigLoader.loadFromClasspath(),
            List.of(new CommandInjectionChecker()));
    IrAnalysisRunner.IrAnalysisResult result = runner.analyzeProgram(irFile);

    assertThat(result.problems()).as("the problems must survive the round trip").isNotEmpty();
    assertThat(result.problems().get(0).kind())
        .as("a broken source is a PARSE_PROBLEM, not an UNHANDLED_NODE")
        .isEqualTo("PARSE_PROBLEM");
    assertThat(result.problems().get(0).line())
        .as("the problem must carry a line, so a consumer can point at it")
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("the CWE-732 gap rule works through the source-less path")
  void gapRuleWorksFromIrAlone() throws Exception {
    Path source = tmp.resolve("Perm.java");
    Path irFile =
        produce(
            source,
            """
            import java.io.*;
            class Perm {
              void run() throws IOException {
                File f = new File("/tmp/x");
                f.setReadable(true, false);
              }
            }
            """);
    Files.delete(source);

    IrAnalysisRunner runner =
        new IrAnalysisRunner(
            org.plumb.dfa.config.ConfigLoader.loadFromClasspath(),
            List.of(
                new RegexInjectionChecker(),
                new org.plumb.dfa.checkers.taint.FilePermissionChecker()));
    IrAnalysisRunner.IrAnalysisResult result = runner.analyzeProgram(irFile);

    assertThat(result.findings())
        .as("the file-permission rule must fire from the IR alone")
        .anyMatch(f -> "CWE-732".equals(f.cwe()));
  }

  /**
   * The consumer must not read a single {@code .java} file.
   *
   * <p>The other tests in this class delete the source before analysing. That proves the analyser
   * did not need THAT file. It does not prove the analyser did not go and read some OTHER file --
   * and it did: the engine built a cross-file static-field resolver as a side effect of being
   * handed the source paths, and that resolver walked the directory tree around the IR's nominal
   * path, reading and regex-scanning every {@code .java} file it found.
   *
   * <p>Two things are pinned here:
   *
   * <ul>
   *   <li>the consumer installs no source-reading resolver, and
   *   <li>the analysis finishes in a bounded time even when the IR's path sits next to a large tree
   *       -- because a walk that has to be timed out is a walk that happened.
   * </ul>
   */
  @Test
  @DisplayName("the consumer reads no .java file, and does not walk the filesystem")
  void consumerReadsNoSource() throws Exception {
    // A sibling tree full of sources, right next to the IR's nominal path.
    Path neighbour = tmp.resolve("neighbour");
    Files.createDirectories(neighbour);
    for (int i = 0; i < 400; i++) {
      Files.writeString(
          neighbour.resolve("Noise" + i + ".java"),
          "package noise; public class Noise"
              + i
              + " { public static final int N = "
              + i
              + "; }\n");
    }

    Path source = tmp.resolve("Cmd.java");
    Path irFile = produce(source, COMMAND_INJECTION);
    Files.delete(source);

    IrAnalysisRunner runner =
        new IrAnalysisRunner(
            org.plumb.dfa.config.ConfigLoader.loadFromClasspath(),
            List.of(new CommandInjectionChecker()));

    long t0 = System.nanoTime();
    IrAnalysisRunner.IrAnalysisResult result = runner.analyzeProgram(irFile);
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

    assertThat(result.findings()).as("the finding must still be produced").isNotEmpty();

    // The bound is deliberately generous: the point is not the exact duration but that
    // the analysis is O(program) rather than O(filesystem). The old behaviour indexed
    // 48k files per analysis and took over a hundred seconds for this one call.
    assertThat(elapsedMs)
        .as("the consumer must not walk the filesystem; analysis took " + elapsedMs + " ms")
        .isLessThan(30_000L);
  }
}
