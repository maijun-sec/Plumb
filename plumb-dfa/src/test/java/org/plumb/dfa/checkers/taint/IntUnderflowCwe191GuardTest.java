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
 * CWE-191: the integer-underflow heuristic is still live code, so its behaviour needs a guard.
 *
 * <p><b>Why this class exists.</b> {@link IntUnderflowChecker} is deprecated in favour of the
 * boundary framework, and its javadoc says that unit tests still exercise the heuristic path as a
 * regression baseline. They did not: the only test that ever pinned it, {@code
 * R222bIntUnderflowCheckerTest}, was archived and cannot even be compiled (its {@code new
 * TacOperand.TacVar(} calls lost their arguments). A deprecated checker with no guard is a checker
 * whose behaviour can drift without anybody noticing, and this one is still registered by the scan
 * runner.
 *
 * <p><b>What the checker actually decides.</b> It is a taint heuristic, not a range analysis:
 *
 * <ul>
 *   <li>a value read from an input source is a <i>hazard</i>; a value that came from a parameter is
 *       not (the {@code param:} label is excluded), and a value with no source at all is not;
 *   <li>the arithmetic must be able to go DOWN: {@code data - 1} can underflow, {@code data + 1}
 *       cannot (it is the overflow checker's case), {@code data + (-1)} can, {@code data * 2} can;
 *   <li>a greater-than guard on the operand suppresses the report, because a bounded operand cannot
 *       underflow;
 *   <li>a decrement reports CWE-191, and this checker is the only owner of the decrement findings.
 * </ul>
 *
 * <p>Every case here is a whole source file compiled through the real parser and translator, so the
 * guard measures the engine's answer rather than the checker's answer to a hand-built IR.
 */
@SuppressWarnings("deprecation")
class IntUnderflowCwe191GuardTest {

  private static final String RULE_ID = "dfa-taint-integer-underflow";

  /** A tainted value minus one: the shape that underflows. */
  private static final String SUBTRACT_ONE =
      """
      class U1 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int x = data - 1;
          System.out.println(x);
        }
      }
      """;

  /** The same tainted value plus one: that is an overflow shape, not an underflow one. */
  private static final String ADD_ONE =
      """
      class U2 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int x = data + 1;
          System.out.println(x);
        }
      }
      """;

  /** Adding a negative constant is a subtraction in disguise, and it underflows. */
  private static final String ADD_NEGATIVE_ONE =
      """
      class U3 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int x = data + (-1);
          System.out.println(x);
        }
      }
      """;

  /** Multiplying by a constant can push the value below the minimum as well. */
  private static final String MULTIPLY_TWO =
      """
      class U4 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int x = data * 2;
          System.out.println(x);
        }
      }
      """;

  /** A greater-than guard on the operand bounds it, so the subtraction cannot underflow. */
  private static final String GUARDED_SUBTRACT =
      """
      class U5 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          if (data > 0) {
            int x = data - 1;
            System.out.println(x);
          }
        }
      }
      """;

  /**
   * The control for the guarded case: the same subtraction, inside an if, with the guard on ANOTHER
   * variable.
   *
   * <p>Without this, "the guard suppressed the report" and "the checker never looked inside an if
   * body" would look the same.
   */
  private static final String GUARD_ON_ANOTHER_VARIABLE =
      """
      class U8 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int other = Integer.parseInt(reader.readLine().trim());
          if (other > 0) {
            int x = data - 1;
            System.out.println(x);
          }
        }
      }
      """;

  /** A decrement of a tainted byte is the classic underflow shape, and this checker owns it. */
  private static final String PREDECREMENT_BYTE =
      """
      class U6 {
        void go(java.io.BufferedReader reader) throws Exception {
          byte data = Byte.parseByte(reader.readLine().trim());
          --data;
          System.out.println(data);
        }
      }
      """;

  /** No source, no hazard, no report. */
  private static final String NO_SOURCE =
      """
      class U7 {
        void go() {
          int data = 5;
          int x = data - 1;
          System.out.println(x);
        }
      }
      """;

  /**
   * A value that came in as a parameter.
   *
   * <p>The checker excludes the {@code param:} label on purpose: a parameter's value is the
   * CALLER's business, and the caller is the one that knows whether it is dangerous.
   */
  private static final String PARAMETER_SOURCE =
      """
      class U9 {
        void go(int data) {
          int x = data - 1;
          System.out.println(x);
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
        new DfaEngine(base, List.of(new IntUnderflowChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE_ID.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  @Test
  void subtractingFromATaintedValueIsReported() throws Exception {
    List<Finding> found = scan(SUBTRACT_ONE, "U1.java");
    assertThat(found)
        .as("CWE-191: `data - 1` on a value read from the console can underflow")
        .hasSize(1);
    assertThat(found.get(0).cwe()).as("the rule id and the CWE must agree").isEqualTo("CWE-191");
    assertThat(found.get(0).message())
        .as("the finding has to name the operation that underflows")
        .contains("SUB");
  }

  @Test
  void addingAPositiveConstantIsNotAnUnderflow() throws Exception {
    assertThat(scan(ADD_ONE, "U2.java"))
        .as(
            "CWE-191: `data + 1` can only overflow, and the overflow direction is not this"
                + " checker's")
        .isEmpty();
  }

  @Test
  void addingANegativeConstantIsAnUnderflow() throws Exception {
    assertThat(scan(ADD_NEGATIVE_ONE, "U3.java"))
        .as("CWE-191: `data + (-1)` is a subtraction and can underflow")
        .isNotEmpty();
  }

  @Test
  void multiplyingByAConstantIsReported() throws Exception {
    assertThat(scan(MULTIPLY_TWO, "U4.java"))
        .as("CWE-191: `data * 2` can push the value below the minimum")
        .isNotEmpty();
  }

  @Test
  void aGreaterThanGuardSuppressesTheReport() throws Exception {
    assertThat(scan(GUARDED_SUBTRACT, "U5.java"))
        .as("CWE-191: `data > 0` bounds the operand, so `data - 1` cannot underflow")
        .isEmpty();
  }

  @Test
  void aGuardOnAnotherVariableDoesNotSuppressTheReport() throws Exception {
    assertThat(scan(GUARD_ON_ANOTHER_VARIABLE, "U8.java"))
        .as("CWE-191: a guard on `other` says nothing about `data`, so the subtraction still fires")
        .isNotEmpty();
  }

  @Test
  void decrementingATaintedByteIsReported() throws Exception {
    List<Finding> found = scan(PREDECREMENT_BYTE, "U6.java");
    assertThat(found)
        .as("CWE-191: `data--` on a value read from the console can underflow")
        .hasSize(1);
    assertThat(found.get(0).cwe())
        .as("this checker is the sole owner of the decrement findings, and it tags them CWE-191")
        .isEqualTo("CWE-191");
    assertThat(found.get(0).message())
        .as("the finding has to name the operation and the type it underflowed")
        .contains("PRE_DEC")
        .contains("byte");
  }

  @Test
  void aValueWithNoSourceIsNotReported() throws Exception {
    assertThat(scan(NO_SOURCE, "U7.java"))
        .as("CWE-191: without a source there is no hazard, so there is nothing to report")
        .isEmpty();
  }

  @Test
  void aValueThatCameInAsAParameterIsNotAHazard() throws Exception {
    assertThat(scan(PARAMETER_SOURCE, "U9.java"))
        .as("CWE-191: a parameter's value is the caller's business, so it is not a hazard here")
        .isEmpty();
  }
}
