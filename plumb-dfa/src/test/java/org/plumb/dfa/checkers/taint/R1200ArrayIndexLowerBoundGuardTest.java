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
package org.plumb.dfa.checkers.taint;

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
 * CWE-129: the taint heuristic must recognise a lower-bound guard that is one arm of a conjunction.
 *
 * <p>The corpus's fixed shape is:
 *
 * <pre>
 *   if (data &gt;= 0 &amp;&amp; data &lt; array.length) { array[data] = ...; }
 * </pre>
 *
 * <p>The lower bound is what makes the index safe, and it is the {@code >=} arm that provides it. A
 * heuristic that only looks for a guard on its own -- and not for one arm of a {@code &&} --
 * reports the fixed code as unsafe.
 */
class R1200ArrayIndexLowerBoundGuardTest {

  /** The corpus's fixed shape: a tainted index guarded on BOTH bounds, one arm of a conjunction. */
  private static final String CONJUNCTION_GUARD =
      """
      class Rcv {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int[] array = new int[5];
          if (data >= 0 && data < array.length) {
            System.out.println(array[data]);
          }
        }
      }
      """;

  /** The same, with the lower bound as its own if. */
  private static final String SEPARATE_GUARD =
      """
      class Rcv2 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int[] array = new int[5];
          if (data >= 0) {
            if (data < array.length) {
              System.out.println(array[data]);
            }
          }
        }
      }
      """;

  /** The defective shape: only the upper bound is checked. */
  private static final String NO_LOWER_BOUND =
      """
      class Rcv3 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int[] array = new int[5];
          if (data < array.length) {
            System.out.println(array[data]);
          }
        }
      }
      """;

  /**
   * A lower bound on its own leaves a real out-of-bounds access open: {@code data} may be 100 while
   * the array holds five elements.
   */
  private static final String LOWER_BOUND_ONLY =
      """
      class Rcv5 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int[] array = new int[5];
          if (data >= 0) {
            System.out.println(array[data]);
          }
        }
      }
      """;

  /** The upper bound is measured against another array, so it says nothing about this one. */
  private static final String WRONG_ARRAY =
      """
      class Rcv6 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int[] array = new int[5];
          int[] other = new int[10];
          if (data >= 0 && data < other.length) {
            System.out.println(array[data]);
          }
        }
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
        new DfaEngine(base, List.of(new ArrayIndexChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-taint-unchecked-array-index".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  @Test
  void aLowerBoundInAConjunctionSuppressesTheFinding() throws Exception {
    assertThat(scan(CONJUNCTION_GUARD, "Rcv.java"))
        .as("CWE-129: `data >= 0 && data < array.length` proves the index is in range")
        .isEmpty();
  }

  @Test
  void aLowerBoundInItsOwnIfSuppressesTheFinding() throws Exception {
    assertThat(scan(SEPARATE_GUARD, "Rcv2.java"))
        .as("CWE-129: the same guard written as its own if proves the same thing")
        .isEmpty();
  }

  @Test
  void aMissingLowerBoundStillFires() throws Exception {
    assertThat(scan(NO_LOWER_BOUND, "Rcv3.java"))
        .as("CWE-129: an index with no lower bound must still be reported")
        .isNotEmpty();
  }

  @Test
  void aLowerBoundWithoutAnUpperBoundStillFires() throws Exception {
    assertThat(scan(LOWER_BOUND_ONLY, "Rcv5.java"))
        .as("CWE-129: `data >= 0` alone leaves the access open, so it must still be reported")
        .isNotEmpty();
  }

  @Test
  void anUpperBoundAgainstAnotherArrayStillFires() throws Exception {
    assertThat(scan(WRONG_ARRAY, "Rcv6.java"))
        .as("CWE-129: `data < other.length` does not put `data` inside `array`")
        .isNotEmpty();
  }
}
