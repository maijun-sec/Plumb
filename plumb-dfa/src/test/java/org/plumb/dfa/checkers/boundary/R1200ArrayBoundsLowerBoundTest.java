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
package org.plumb.dfa.checkers.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * CWE-129: an index that is only checked against the array's UPPER bound can still be negative.
 *
 * <p>The corpus's defect is a half-check:
 *
 * <pre>
 *   if (data &lt; array.length) {   // the upper bound is checked
 *     array[data] = ...;            // but the LOWER bound is not
 *   }
 * </pre>
 *
 * and its fix adds the missing half:
 *
 * <pre>
 *   if (data &gt;= 0 &amp;&amp; data &lt; array.length) {   // both bounds
 *     array[data] = ...;
 *   }
 * </pre>
 *
 * <p>The array-bounds checker asks Z3 whether the index can be negative. The guard {@code data >=
 * 0} must REFUTE that question in the fixed shape, and must NOT in the defective one -- otherwise
 * the checker reports the fixed code as unsafe.
 */
class R1200ArrayBoundsLowerBoundTest {

  /** The defective shape: only the upper bound is checked. */
  private static final String ONLY_UPPER_BOUND =
      """
      class Rcv {
        int go(int data) {
          int[] array = new int[5];
          if (data < array.length) {
            return array[data];
          }
          return 0;
        }
      }
      """;

  /** The fixed shape: both bounds are checked. */
  private static final String BOTH_BOUNDS =
      """
      class Rcv2 {
        int go(int data) {
          int[] array = new int[5];
          if (data >= 0 && data < array.length) {
            return array[data];
          }
          return 0;
        }
      }
      """;

  /**
   * The corpus's fixed shape: the index is assigned INSIDE a nested block, and the guard comes
   * after that block.
   */
  private static final String BOTH_BOUNDS_AFTER_NESTED_BLOCK =
      """
      class Rcv4 {
        int go(int data) {
          {
            try {
              data = Integer.parseInt(readLine());
            } catch (NumberFormatException e) {
              data = Integer.MIN_VALUE;
            }
          }
          int[] array = new int[5];
          if (data >= 0 && data < array.length) {
            return array[data];
          }
          return 0;
        }
        String readLine() { return "0"; }
      }
      """;

  /** The fixed shape written as two nested ifs. */
  private static final String BOTH_BOUNDS_NESTED =
      """
      class Rcv3 {
        int go(int data) {
          int[] array = new int[5];
          if (data >= 0) {
            if (data < array.length) {
              return array[data];
            }
          }
          return 0;
        }
      }
      """;

  /** The corpus's fixed shape: the index comes from a METHOD CALL, and the guard comes after it. */
  private static final String BOTH_BOUNDS_AFTER_A_CALL =
      """
      class Rcv5 {
        int go() {
          int data = source();
          int[] array = new int[5];
          if (data >= 0 && data < array.length) {
            return array[data];
          }
          return 0;
        }
        int source() { return 1; }
      }
      """;

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(base, List.of(new ArrayBoundsConstraintChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-boundary-array-out-of-bounds".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  @Test
  void anIndexWithOnlyTheUpperBoundCheckedStillFires() throws Exception {
    assertThat(scan(ONLY_UPPER_BOUND, "Rcv.java"))
        .as("CWE-129: an index that may be negative must fire")
        .isNotEmpty();
  }

  @Test
  void anIndexWithBothBoundsCheckedIsSilent() throws Exception {
    assertThat(scan(BOTH_BOUNDS, "Rcv2.java"))
        .as("CWE-129: `data >= 0 && data < array.length` proves the index is in range")
        .isEmpty();
  }

  @Test
  void bothBoundsInNestedIfsAreSilent() throws Exception {
    assertThat(scan(BOTH_BOUNDS_NESTED, "Rcv3.java"))
        .as("CWE-129: the same two guards written as nested ifs prove the same thing")
        .isEmpty();
  }

  @Test
  void bothBoundsAfterANestedBlockAreSilent() throws Exception {
    assertThat(scan(BOTH_BOUNDS_AFTER_NESTED_BLOCK, "Rcv4.java"))
        .as("CWE-129: the index assigned in a nested block and then guarded is in range")
        .isEmpty();
  }

  @Test
  void bothBoundsAfterACallAreSilent() throws Exception {
    assertThat(scan(BOTH_BOUNDS_AFTER_A_CALL, "Rcv5.java"))
        .as("CWE-129: the index read from a call and then guarded is in range")
        .isEmpty();
  }
}
