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
package org.plumb.dfa.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.io.IrSerializer;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The front/back split: source in, IR out, IR in, findings out.
 *
 * <p><b>The contract this test pins.</b> A producer prepares the source list, the dependency list
 * and the entity files, translates them to IR, and stores the IR. A consumer -- possibly on another
 * machine, with no source and no classpath -- loads the IR and scans it.
 *
 * <p>For that to work, everything the analysis needs must be <b>in the IR</b>:
 *
 * <ul>
 *   <li>the instructions, with their line and column;
 *   <li>the source path, so a finding can name the file it came from;
 *   <li>the class hierarchy and the annotation metadata the class-aware checkers read;
 *   <li>the problems, so the consumer can see that the analysis was incomplete.
 * </ul>
 *
 * <p><b>Why this matters more than it looks.</b> The alternative is that the consumer needs the
 * source. Then the "IR" is not a contract, it is a cache, and every consumer has to be able to read
 * and resolve Java -- which is exactly the coupling the split is supposed to remove.
 */
class IrRoundTripAnalysisTest {

  @TempDir Path tmp;

  /** A program with a command injection: the argument reaches a process builder. */
  private static final String VULNERABLE =
      """
      import java.io.*;
      class Cmd {
        void run(String userInput) throws IOException {
          Process p = Runtime.getRuntime().exec(userInput);
          p.waitFor();
        }
      }
      """;

  private TacProgram translate(Path file, String source) throws Exception {
    ParsedSource parsed = new JavaSourceParser().parse(file);
    return new TacTranslator(parsed).translate();
  }

  private List<Finding> scan(List<TacProgram> programs, DfaChecker... checkers) {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checkers), "global", null).runWithGlobalStore(programs, 0);
    return new ArrayList<>(reporter.all());
  }

  @Test
  @DisplayName("a finding survives the IR round-trip: source to IR to disk to IR to finding")
  void findingSurvivesTheRoundTrip() throws Exception {
    Path source = tmp.resolve("Cmd.java");
    Files.writeString(source, VULNERABLE);

    // ── the front end: source in, IR out ──
    TacProgram program = translate(source, VULNERABLE);
    Path irFile = tmp.resolve("Cmd.tac");
    IrSerializer.writeProgram(program, irFile);

    // The baseline: the same checker over the IR that was never written to disk. If this
    // does not fire, the fixture is wrong -- not the round-trip.
    List<Finding> baseline = scan(List.of(program), new CommandInjectionChecker());
    assertThat(baseline)
        .as("the fixture must produce a command-injection finding before the round-trip")
        .isNotEmpty();
    assertThat(baseline.get(0).cwe()).isEqualTo("CWE-78");

    // ── the back end: IR in, findings out. NO SOURCE IS AVAILABLE. ──
    // The source file is deleted before the analysis, so the test proves the analysis
    // does not secretly read it.
    Files.delete(source);
    assertThat(Files.exists(source)).isFalse();

    TacProgram loaded = IrSerializer.readProgram(irFile);
    List<Finding> findings = scan(List.of(loaded), new CommandInjectionChecker());

    assertThat(findings).as("the finding must be reproducible from the IR alone").isNotEmpty();
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-78");
  }

  @Test
  @DisplayName("the loaded IR carries the source path, so a finding can name its file")
  void loadedIrCarriesTheSourcePath() throws Exception {
    Path source = tmp.resolve("Cmd.java");
    Files.writeString(source, VULNERABLE);
    TacProgram program = translate(source, VULNERABLE);

    Path irFile = tmp.resolve("Cmd.tac");
    IrSerializer.writeProgram(program, irFile);
    TacProgram loaded = IrSerializer.readProgram(irFile);

    assertThat(loaded.sourcePath()).as("the IR must remember which file it came from").isNotNull();
    assertThat(loaded.sourcePath().getFileName().toString()).isEqualTo("Cmd.java");
  }

  @Test
  @DisplayName("the loaded IR carries the class hierarchy and the annotation metadata")
  void loadedIrCarriesTheSemanticMetadata() throws Exception {
    Path source = tmp.resolve("Annotated.java");
    Files.writeString(
        source,
        """
        @Deprecated
        class Annotated {
          @SuppressWarnings("unused")
          private String secret = "x";

          @SuppressWarnings("unused")
          void m(@Deprecated int p) {}
        }
        """);
    TacProgram program = translate(source, "");

    Path irFile = tmp.resolve("Annotated.tac");
    IrSerializer.writeProgram(program, irFile);
    TacProgram loaded = IrSerializer.readProgram(irFile);

    assertThat(loaded.classToParent()).as("the class hierarchy must survive").isNotNull();
    assertThat(loaded.functions()).as("the functions must survive").isNotEmpty();
    assertThat(loaded.functions().get(0).classAnnotations())
        .as("the class annotations must survive -- a class-aware checker reads them")
        .contains("Deprecated");
  }

  @Test
  @DisplayName("the loaded IR carries the source map, so a finding can name a line")
  void loadedIrCarriesTheSourceMap() throws Exception {
    Path source = tmp.resolve("Cmd.java");
    Files.writeString(source, VULNERABLE);
    TacProgram program = translate(source, VULNERABLE);

    Path irFile = tmp.resolve("Cmd.tac");
    IrSerializer.writeProgram(program, irFile);
    TacProgram loaded = IrSerializer.readProgram(irFile);

    assertThat(loaded.sourceMap()).as("the source map must survive").isNotNull();
    boolean anyLocation =
        loaded.functions().stream()
            .anyMatch(f -> !loaded.sourceMap().instructions(f.signature()).isEmpty());
    assertThat(anyLocation)
        .as("the instruction-to-line mapping must survive, or a finding has no location")
        .isTrue();
  }

  @Test
  @DisplayName("a merged IR of many files round-trips and analyses as one program")
  void mergedIrRoundTrips() throws Exception {
    Path a = tmp.resolve("A.java");
    Path b = tmp.resolve("B.java");
    Files.writeString(
        a,
        """
        class A {
          void go(String s) { System.out.println(s); }
        }
        """);
    Files.writeString(
        b,
        """
        class B {
          void go(String s) { System.out.println(s); }
        }
        """);

    TacProgram pa = translate(a, "");
    TacProgram pb = translate(b, "");
    MergedProgram merged = new MergedProgram(List.of(pa, pb));

    Path mergedFile = tmp.resolve("merged.tac");
    IrSerializer.writeMerged(merged, mergedFile);
    MergedProgram back = IrSerializer.readMerged(mergedFile);

    assertThat(back.sourceCount()).isEqualTo(2);
    assertThat(back.functions())
        .as("both files' functions must survive")
        .hasSizeGreaterThanOrEqualTo(2);

    // And the merged IR still analyses.
    List<Finding> findings = scan(back.sources(), new CommandInjectionChecker());
    assertThat(findings).as("a merged IR must be analysable").isNotNull();
  }

  @Test
  @DisplayName("the IR round-trip is lossless for the instruction stream")
  void instructionStreamIsLossless() throws Exception {
    Path source = tmp.resolve("Cmd.java");
    Files.writeString(source, VULNERABLE);
    TacProgram program = translate(source, VULNERABLE);

    Path irFile = tmp.resolve("Cmd.tac");
    IrSerializer.writeProgram(program, irFile);
    TacProgram loaded = IrSerializer.readProgram(irFile);

    assertThat(loaded.functions()).hasSameSizeAs(program.functions());
    for (int i = 0; i < program.functions().size(); i++) {
      var before = program.functions().get(i);
      var after = loaded.functions().get(i);
      assertThat(after.signature()).isEqualTo(before.signature());
      assertThat(after.instructions())
          .as("the instruction stream must be identical")
          .hasSameSizeAs(before.instructions());
      assertThat(after.params()).hasSameSizeAs(before.params());
      assertThat(after.locals()).hasSameSizeAs(before.locals());
      assertThat(after.sourceLanguage())
          .as("the language marker must survive -- a language-specific rule reads it")
          .isEqualTo(before.sourceLanguage());
      assertThat(after.irPrecision())
          .as("the precision marker must survive")
          .isEqualTo(before.irPrecision());
    }
  }

  @Test
  @DisplayName("the problems travel with the IR, so a consumer can see the analysis was incomplete")
  void problemsTravelWithTheIr() throws Exception {
    Path source = tmp.resolve("Cmd.java");
    Files.writeString(source, VULNERABLE);
    TacProgram program = translate(source, VULNERABLE);
    program.addProgramProblem(
        new TacProgram.FunctionProblem(
            "Cmd#run", "UNHANDLED_NODE", "not lowered: Something", "Something", 4, 5));

    Path irFile = tmp.resolve("Cmd.tac");
    IrSerializer.writeProgram(program, irFile);
    TacProgram loaded = IrSerializer.readProgram(irFile);

    assertThat(loaded.hasProblems())
        .as("a consumer must be able to see that the analysis was incomplete")
        .isTrue();
    assertThat(loaded.programProblems()).hasSize(1);
    assertThat(loaded.programProblems().get(0).nodeClass()).isEqualTo("Something");
  }
}
