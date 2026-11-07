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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compilation database's rules, checked against the reference implementation of the format.
 *
 * <p><b>Why the reference and not a golden file.</b> A golden file records what the writer produced
 * once, and it passes forever after -- including when the writer changes and the reader does not. A
 * conformance check against the reference implementation asks the real question: <em>does a
 * consumer that follows the format read this file the way the format says?</em> When the two
 * disagree, one of them is wrong and the check says which.
 *
 * <p><b>Why it can be skipped.</b> The reference probe is a native executable, so it is not present
 * on every machine. The check therefore runs when the probe is there and is skipped when it is not.
 * The pure-Java assertions in {@link CompdbWriterTest} always run, so the rules are pinned either
 * way.
 */
@DisplayName("Capture: the compilation database agrees with the reference implementation")
class CompdbConformanceTest {

  /**
   * Where the reference probe is expected.
   *
   * <p>The test runs with the module directory as its working directory, so the repository root is
   * two levels up.
   */
  private static final Path PROBE =
      Paths.get("..", "..", ".aethercode", "probe", "conform_probe.exe");

  /** The reference probe is present. */
  static boolean probePresent() {
    return Files.isRegularFile(PROBE);
  }

  @Test
  @EnabledIf("probePresent")
  @DisplayName("path normalization and string quoting match the reference, byte for byte")
  void pathNormalizationAndStringQuotingMatchTheReference(@TempDir Path tmp) throws Exception {
    Path inputs = tmp.resolve("inputs.txt");
    CompdbConformanceProbe.writeInputs(inputs);

    List<String> reference = CompdbConformanceProbe.runReference(PROBE, inputs);
    List<String> ours = CompdbConformanceProbe.run(inputs);

    assertThat(ours)
        .as(
            "the two implementations answer the same number of cases -- a"
                + " mismatch here means the input lists drifted apart")
        .hasSameSizeAs(reference);

    for (int i = 0; i < reference.size(); i++) {
      assertThat(ours.get(i))
          .as("case " + (i + 1) + " of the conformance set")
          .isEqualTo(reference.get(i));
    }
  }

  @Test
  @DisplayName("the Java half and the reference half see the same number of cases")
  void theJavaHalfAndTheReferenceHalfSeeTheSameNumberOfCases(@TempDir Path tmp) throws Exception {
    // This runs whether or not the native probe is present: it pins the shape
    // of the comparison itself, so a change to the case list cannot silently
    // reduce the check to nothing.
    Path inputs = tmp.resolve("inputs.txt");
    List<String> cases = CompdbConformanceProbe.conformanceInputs();
    CompdbConformanceProbe.writeInputs(inputs);

    List<String> ours = CompdbConformanceProbe.run(inputs);

    assertThat(ours).as("every case produces a record").hasSameSizeAs(cases);
    assertThat(ours)
        .as("the path cases and the quoting cases are both present")
        .anyMatch(r -> r.startsWith("N "))
        .anyMatch(r -> r.startsWith("Q "));
  }

  @Test
  @DisplayName("the escaped wire form carries a case that holds a line separator")
  void theEscapedWireFormCarriesACaseThatHoldsALineSeparator() {
    // The input file is line-oriented, so a case holding a newline cannot be
    // written literally. This is the property that makes the rest of the check
    // possible: without it, the control-character cases would silently be
    // split and compared as something else.
    assertThat(CompdbConformanceProbe.escape('Q', "a\nb"))
        .as("a newline becomes an escape, not a line break")
        .isEqualTo("Q a\\nb");
    assertThat(CompdbConformanceProbe.unescape("Q a\\nb")).isEqualTo("Q a\nb");
    assertThat(CompdbConformanceProbe.unescape("N a\\\\b")).isEqualTo("N a\\b");
    assertThat(CompdbConformanceProbe.unescape("Q \\u4e2d")).isEqualTo("Q 中");
    assertThat(CompdbConformanceProbe.escape('Q', "中"))
        .as("a non-ASCII character is escaped, so the file stays plain ASCII")
        .isEqualTo("Q \\u4e2d");
  }
}
