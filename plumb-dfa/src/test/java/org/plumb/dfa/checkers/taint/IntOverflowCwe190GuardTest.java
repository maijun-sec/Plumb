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
 * CWE-190: the multiplication branch of the overflow heuristic needs a guard.
 *
 * <p><b>Why this class exists.</b> The overflow checker's {@code isGuardBoundedOverflow} had ADD
 * and SUB branches and <b>no MUL branch</b> =&gt; a multiplication never asked whether its operand
 * was bounded =&gt; a guard did nothing for a multiplication at all. That is the largest
 * false-positive source in Juliet's CWE190 family: the fixed versions are almost all {@code if
 * (data < (Integer.MAX_VALUE / 2)) { data * 2 }}, and that guard was never consulted.
 *
 * <p><b>What is pinned here is "judged by its value", not "there is a bound".</b> An upper bound
 * only stops a multiplication if it is tight enough: {@code data < Integer.MAX_VALUE} does
 * <b>not</b> stop {@code data * 2} (data may be MAX-1), while {@code data < (Integer.MAX_VALUE /
 * 2)} does. The two must give <b>different</b> answers, otherwise the mechanism degrades into
 * "silence whenever a bound exists" -- and that hides real findings.
 *
 * <p>Every case is a whole source file run through the real parser and translator, so what is
 * pinned is the engine's answer, not the checker's answer on hand-built IR.
 */
@SuppressWarnings("deprecation")
class IntOverflowCwe190GuardTest {

  private static final String RULE_ID = "dfa-taint-integer-overflow";

  /** A tainted value multiplied by a constant: the shape that overflows. */
  private static final String MULTIPLY_TWO =
      """
      class O1 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int x = data * 2;
          System.out.println(x);
        }
      }
      """;

  /**
   * A multiplication whose upper bound is <b>tight</b>: {@code data < MAX/2} =&gt; {@code data * 2}
   * cannot overflow an int.
   *
   * <p>This is the most common fix shape in Juliet's CWE190 family.
   */
  private static final String GUARDED_TIGHT_MULTIPLY =
      """
      class O2 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          if (data < (Integer.MAX_VALUE / 2)) {
            int x = data * 2;
            System.out.println(x);
          }
        }
      }
      """;

  /**
   * A multiplication whose upper bound is <b>not tight</b>: {@code data < Integer.MAX_VALUE} does
   * not stop {@code data * 2}.
   *
   * <p>data may be MAX-1, and twice MAX-1 overflows an int. So this case <b>must still be
   * reported</b> -- it is the pin against the guard degrading into a blanket suppression.
   */
  private static final String GUARDED_LOOSE_MULTIPLY =
      """
      class O3 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          if (data < Integer.MAX_VALUE) {
            int x = data * 2;
            System.out.println(x);
          }
        }
      }
      """;

  /** An unknown multiplier: {@code data * data} has no number to check the bound against. */
  private static final String GUARDED_SQUARE =
      """
      class O4 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          if (data < (Integer.MAX_VALUE / 2)) {
            int x = data * data;
            System.out.println(x);
          }
        }
      }
      """;

  /**
   * The control case: the guard is on <b>another variable</b>.
   *
   * <p>Without this one, "the guard silenced the report" and "the checker never entered the if
   * body" look exactly the same.
   */
  private static final String GUARD_ON_ANOTHER_VARIABLE =
      """
      class O5 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          int other = Integer.parseInt(reader.readLine().trim());
          if (other < (Integer.MAX_VALUE / 2)) {
            int x = data * 2;
            System.out.println(x);
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
        new DfaEngine(base, List.of(new IntOverflowChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE_ID.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  @Test
  void multiplyingATaintedValueIsReported() throws Exception {
    List<Finding> found = scan(MULTIPLY_TWO, "O1.java");
    assertThat(found).as("CWE-190：读自控制台的值乘以 2 会溢出 int").hasSize(1);
    assertThat(found.get(0).cwe()).as("规则 id 与 CWE 必须一致").isEqualTo("CWE-190");
    assertThat(found.get(0).message()).as("finding 必须点名那个会溢出的运算").contains("MUL");
  }

  @Test
  void aTightUpperBoundRulesOutTheOverflow() throws Exception {
    assertThat(scan(GUARDED_TIGHT_MULTIPLY, "O2.java"))
        .as("守卫 `data < Integer.MAX_VALUE / 2` 说明乘积超不过 int 的最大值，所以这次乘法是安全的")
        .isEmpty();
  }

  @Test
  void aLooseUpperBoundDoesNotRuleOutTheOverflow() throws Exception {
    assertThat(scan(GUARDED_LOOSE_MULTIPLY, "O3.java"))
        .as("`data < Integer.MAX_VALUE` 拦不住 `data * 2` 溢出——data 可以是 MAX-1——所以报告必须留下")
        .hasSize(1);
  }

  @Test
  void anUnknownMultiplierIsNotBounded() throws Exception {
    assertThat(scan(GUARDED_SQUARE, "O4.java"))
        .as("`data * data` 没有常量乘数可以拿来核对界，所以守卫对它什么也说明不了")
        .hasSize(1);
  }

  @Test
  void aGuardOnAnotherVariableDoesNotBoundTheProduct() throws Exception {
    assertThat(scan(GUARD_ON_ANOTHER_VARIABLE, "O5.java"))
        .as("`other` 上的界对 `data * 2` 说明不了任何事")
        .hasSize(1);
  }
}
