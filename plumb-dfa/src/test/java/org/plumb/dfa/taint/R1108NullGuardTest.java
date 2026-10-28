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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1108: {@code if (v != null)} / {@code if (v == null) return;} must register a null-ness guard.
 *
 * <h2>Why this needed proving</h2>
 *
 * <p>{@link GuardTracker} existed for numeric bounds before null-ness was ever asked of it. Its
 * comparison recorder is type-agnostic — any {@code TacVar op Const} pair becomes a Guard, and
 * {@code null} is just another {@link TacOperand.Const} kind — so the recording side plausibly
 * needed no change at all. Which is exactly why it needed a test: "plausibly" is not "verified",
 * and a null guard that silently fails to record produces a NullDeref false positive that looks
 * like a pre-existing limitation rather than a missing feature.
 *
 * <p>Both spellings of the idiom are covered, and they are covered separately because they are
 * carried by <em>different</em> structures. {@code if (v != null) {...}} leaves the fact on the
 * active guard stack; {@code if (v == null) return;} leaves it on the false-edge map, because the
 * JUMP that ends the then-block pops the guard before the trailing use is reached. An
 * implementation that consulted only the active stack would pass the first shape and fail the
 * second — which is the more common real-world spelling, so the gap would have been noticed
 * immediately in practice and is worth pinning here instead.
 */
class R1108NullGuardTest {

  @TempDir Path tmp;

  private TacProgram parse(String source, String name) throws Exception {
    Path f = tmp.resolve(name);
    Files.writeString(f, source);
    ParsedSource ps = new JavaSourceParser().parse(f);
    return new TacTranslator(ps).translate();
  }

  /**
   * Walk one function, tracking guards, and report the proven-non-null set at the instruction that
   * performs a call on {@code probe}'s receiver.
   *
   * <p>Driving the real tracker over real IR rather than hand-built instructions: the point of the
   * gate is that the <em>translation</em> plus the tracker together produce the fact, so a test
   * that injected a synthetic guard would pass even if the IR never emitted the comparison.
   */
  private java.util.Set<TacOperand.TacVar> provenNonNullAtCall(
      TacProgram p, String fnName, String probe) {
    for (TacFunction fn : p.functions()) {
      if (fn.name() == null || !fn.name().equals(fnName)) continue;
      GuardTracker tracker = new GuardTracker();
      for (TacInstruction ins : fn.instructions()) {
        tracker.track(ins);
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        if (ins.args == null || ins.args.isEmpty()) continue;
        if (!(ins.args.get(0) instanceof TacOperand.TacVar recv)) continue;
        if (recv.name().equals(probe)) {
          return tracker.provenNonNullVars();
        }
      }
    }
    throw new AssertionError("no call on receiver named " + probe + " in " + fnName);
  }

  private static final String POSITIVE_GUARD =
      """
      class G1 {
        void sink(String s) {
        }
        void run(String v) {
          if (v != null) {
            sink(v);
          }
        }
      }
      """;

  private static final String EARLY_RETURN_GUARD =
      """
      class G2 {
        void sink(String s) {
        }
        void run(String v) {
          if (v == null) {
            return;
          }
          sink(v);
        }
      }
      """;

  private static final String INVERTED_EARLY_RETURN =
      """
      class G3 {
        void sink(String s) {
        }
        void run(String v) {
          if (v != null) {
            return;
          }
          sink(v);
        }
      }
      """;

  // ── the two safe shapes ───────────────────────────────────────────────

  @Test
  void positiveGuardProvesNonNullInsideTheThenBlock() throws Exception {
    TacProgram p = parse(POSITIVE_GUARD, "G1.java");
    java.util.Set<TacOperand.TacVar> proven = provenNonNullAtCall(p, "run", "v");
    assertThat(proven)
        .as("if (v != null) { v.use(); } must prove v non-null at the use")
        .extracting(TacOperand.TacVar::name)
        .contains("v");
  }

  @Test
  void earlyReturnGuardProvesNonNullAfterTheIf() throws Exception {
    TacProgram p = parse(EARLY_RETURN_GUARD, "G2.java");
    java.util.Set<TacOperand.TacVar> proven = provenNonNullAtCall(p, "run", "v");
    assertThat(proven)
        .as("if (v == null) return; v.use(); must prove v non-null after the if")
        .extracting(TacOperand.TacVar::name)
        .contains("v");
  }

  // ── negative controls ─────────────────────────────────────────────────

  @Test
  void invertedEarlyReturnDoesNotProveNonNull() throws Exception {
    TacProgram p = parse(INVERTED_EARLY_RETURN, "G3.java");
    java.util.Set<TacOperand.TacVar> proven = provenNonNullAtCall(p, "run", "v");
    assertThat(proven)
        .as("if (v != null) return; v.use(); -- v is null here, so it must NOT be proven non-null")
        .extracting(TacOperand.TacVar::name)
        .doesNotContain("v");
  }

  @Test
  void anUnguardedVariableIsNeverProvenNonNull() throws Exception {
    String source =
        """
        class G4 {
          void sink(String s) {
          }
          void run(String v, String w) {
            sink(w);
          }
        }
        """;
    TacProgram p = parse(source, "G4.java");
    java.util.Set<TacOperand.TacVar> proven = provenNonNullAtCall(p, "run", "w");
    assertThat(proven)
        .as("no guard exists at all, so nothing may be proven")
        .extracting(TacOperand.TacVar::name)
        .doesNotContain("w");
  }

  /**
   * A non-null comparison against something other than {@code null} proves nothing about null-ness.
   *
   * <p>This is the negative control that keeps {@code isProvenNonNull} from degenerating into "any
   * NE guard counts". Matching the operator alone would make {@code if (v != other)} suppress a
   * genuine null dereference.
   */
  @Test
  void comparisonAgainstANonNullLiteralDoesNotProveNonNull() throws Exception {
    String source =
        """
        class G5 {
          void sink(String s) {
          }
          void run(String v, String other) {
            if (v != other) {
              sink(v);
            }
          }
        }
        """;
    TacProgram p = parse(source, "G5.java");
    java.util.Set<TacOperand.TacVar> proven = provenNonNullAtCall(p, "run", "v");
    assertThat(proven)
        .as("v != other says nothing about whether v is null")
        .extracting(TacOperand.TacVar::name)
        .doesNotContain("v");
  }
}
