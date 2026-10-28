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
 * R1101 regression gate: the early-return guard idiom for divide-by-zero.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code if (data == 0) { return 0; } a / data;} is the most common way real code avoids
 * divide-by-zero, and it fired as a false positive while the mirror-image {@code if (data != 0) { a
 * / data; }} was correctly suppressed. The asymmetry was structural:
 *
 * <pre>
 *   BINOP t0 = data EQ 0
 *   BRANCH t0, L_THEN_0, L_ELSE_1
 *   L_THEN_0:
 *     return 0
 *   JUMP L_END_IF_2          // pops the guard
 *   L_ELSE_1:                // reached only when data != 0
 *   L_END_IF_2:
 *     a / data
 * </pre>
 *
 * <p>Two things had to be true for the false positive, and both were false. First, {@code
 * GuardTracker} never recorded {@code EQ} at all -- it was excluded from both {@code BOUNDING_OPS}
 * and {@code NON_ZERO_OPS}, correctly, because "equals a literal" is not a range bound. Second, the
 * fact the else edge establishes is a false-edge one, and the tracker had no notion of false edges
 * at all.
 *
 * <h2>The fix</h2>
 *
 * <p>{@code FALSE_EDGE_OPS} records {@code EQ}; {@code onBranch} files the guard against the
 * BRANCH's {@code falseLabel} instead of the active stack; {@code onLabel} activates it when that
 * label is reached; {@code BoundaryConstraintChecker.isRefutedByNonZeroGuard} consults it.
 *
 * <p>EQ guards are kept out of {@code activeGuards} / {@code guardedVars} on purpose: they are not
 * bounds, and admitting them would change {@link org.plumb.dfa.taint.GuardTracker#isGuardedBy}
 * semantics for every other checker.
 */
class R1101DivZeroGuardMatrixTest {

  /** The control. If this stops firing the whole measurement is meaningless. */
  private static final String UNGUARDED =
      """
      class M0 {
        int go(int a, int data) { return a / data; }
      }
      """;

  /** The defect: early-return guard on an EQ comparison. */
  private static final String EARLY_RETURN_EQ =
      """
      class M2 {
        int go(int a, int data) {
          if (data == 0) { return 0; }
          return a / data;
        }
      }
      """;

  /** Same idiom but the guard condition assigns a safe value on the bail-out path. */
  private static final String GUARD_BODY_ASSIGNS =
      """
      class M5 {
        int go(int a, int data) {
          if (data == 0) {
            data = 1;
            return a / data;
          }
          return a / data;
        }
      }
      """;

  /** Guard on a local copy of the divisor -- the MOVE must not break the fact. */
  private static final String GUARD_ON_LOCAL_COPY =
      """
      class M7 {
        int go(int a, int data) {
          int d = data;
          if (d == 0) { return 0; }
          return a / d;
        }
      }
      """;

  /** Already worked before the fix; guards against regressing it. */
  private static final String INLINE_NE =
      """
      class M1 {
        int go(int a, int data) {
          if (data != 0) { return a / data; }
          return 0;
        }
      }
      """;

  private static final String POSITIVE_GUARD =
      """
      class M6 {
        int go(int a, int data) {
          if (data > 0) { return a / data; }
          return 0;
        }
      }
      """;

  /**
   * Must still fire. The false-edge fact is scoped to the label that established it; a genuine
   * division by an unchecked variable anywhere must not be suppressed.
   */
  private static final String UNGUARDED_AFTER_A_GUARD =
      """
      class M8 {
        int go(int a, int data, int other) {
          if (data == 0) { return 0; }
          return a / other;
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
        new DfaEngine(base, List.of(new DivZeroConstraintChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-boundary-divide-by-zero".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  @Test
  void unguardedDivisionStillFires() throws Exception {
    assertThat(scan(UNGUARDED, "M0.java"))
        .as("control: an unguarded divisor must be reported")
        .isNotEmpty();
  }

  @Test
  void earlyReturnEqGuardIsSilent() throws Exception {
    assertThat(scan(EARLY_RETURN_EQ, "M2.java"))
        .as("if (data == 0) return 0; establishes data != 0 on the fall-through")
        .isEmpty();
  }

  @Test
  void guardBodyThatAssignsAConstantIsSilent() throws Exception {
    assertThat(scan(GUARD_BODY_ASSIGNS, "M5.java")).isEmpty();
  }

  @Test
  void guardOnALocalCopyIsSilent() throws Exception {
    assertThat(scan(GUARD_ON_LOCAL_COPY, "M7.java"))
        .as("int d = data; if (d == 0) return 0; a / d; -- the guard names d, the sink names d")
        .isEmpty();
  }

  @Test
  void thePreExistingNonZeroGuardStaysSilent() throws Exception {
    assertThat(scan(INLINE_NE, "M1.java")).isEmpty();
    assertThat(scan(POSITIVE_GUARD, "M6.java")).isEmpty();
  }

  @Test
  void theGuardDoesNotLeakToAnUnrelatedDivisor() throws Exception {
    assertThat(scan(UNGUARDED_AFTER_A_GUARD, "M8.java"))
        .as("`data` is proven non-zero but `other` is not; the finding on `other` must survive")
        .isNotEmpty();
  }
}
