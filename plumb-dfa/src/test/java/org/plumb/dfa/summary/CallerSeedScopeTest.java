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
package org.plumb.dfa.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.boundary.OverflowConstraintChecker;
import org.plumb.dfa.checkers.boundary.Range;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * A range seed belongs to the function that recorded it.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The boundary checker learns, at a call site, that the callee's parameter has a known range,
 * and it keeps that range so that the arithmetic after the call can use it. The map it keeps it in
 * was cleared at every call but NOT at the function boundary, and the map is keyed by variable -- a
 * {@code TacVar} is identified by its name -- so a seed recorded for one function's {@code data}
 * was the same key as the next function's {@code data}.
 *
 * <p>The next function the worker analysed therefore started with the previous function's seeds.
 * Whether a boundary finding fired then depended on which function happened to be analysed first,
 * which is a schedule and not a property of the program: the same program gave different findings
 * on different runs, and the sequential path and the parallel path disagreed with each other.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>The seeds surviving the function boundary again: the victim function's {@code data} would
 *       inherit the caller's seed, the overflow would be refuted, and the finding would be missing.
 * </ul>
 */
class CallerSeedScopeTest {

  @TempDir Path tmp;

  /**
   * Three functions.
   *
   * <p>{@code caller} calls a method with a variable named {@code data}; the callee's summary says
   * that its parameter is the constant 5, so the call site seeds {@code data} with the range [5,5].
   * {@code victim} has a parameter of the same name and adds 1 to it.
   */
  private static final String SOURCE =
      """
      class Leak {
        static void callee(int data) {
          if (data > 0) {
            return;
          }
        }

        void caller() {
          int data = 5;
          callee(data);
        }

        void victim(int data) {
          int r = data + 1;
        }
      }
      """;

  @Test
  @DisplayName("a seed recorded in one function does not reach the next one")
  void aSeedRecordedInOneFunctionDoesNotReachTheNextOne() throws Exception {
    Path file = tmp.resolve("Leak.java");
    Files.writeString(file, SOURCE);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();

    TacFunction caller = functionNamed(program, "caller");
    TacFunction victim = functionNamed(program, "victim");

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter = new FindingReporter();
    AnalysisContext ctx = new AnalysisContext(program, null, config, reporter);

    // The callee's summary says: "my parameter is the constant 5". The call site
    // therefore seeds the caller's `data` with the range [5,5].
    String calleeSig = calleeSigOf(caller);
    ctx.summaryCache()
        .put(
            calleeSig,
            new FunctionSummary.Builder(calleeSig)
                .recordParamOp(0, new Operation.RangeSeed(0, Range.ofConst(5)))
                .build());

    OverflowConstraintChecker checker = new OverflowConstraintChecker();
    // The caller runs first and records the seed.
    drive(checker, ctx, caller);
    // The victim runs next. Its `data` is a parameter of its own: nothing is known
    // about it here, so `data + 1` may overflow.
    drive(checker, ctx, victim);

    List<String> findings = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-boundary-integer-overflow".equals(f.ruleId())) {
        findings.add(f.ruleId() + "@" + f.line() + " | " + f.message());
      }
    }

    assertThat(findings)
        .as(
            "the victim's parameter is unbounded in its own function, so `data + 1` may overflow;"
                + " a finding that is missing here means the caller's seed reached the victim's"
                + " parameter, and whether it fires then depends on the order the functions were"
                + " analysed in")
        .hasSize(1);
  }

  private void drive(OverflowConstraintChecker checker, AnalysisContext ctx, TacFunction fn) {
    ctx.setCurrentFunction(fn);
    checker.initFunction(ctx, fn);
    for (TacInstruction ins : fn.instructions()) {
      checker.checkInstruction(ins, fn, ctx);
    }
  }

  private static TacFunction functionNamed(TacProgram program, String name) {
    for (TacFunction fn : program.functions()) {
      if (fn.name().equals(name)) {
        return fn;
      }
    }
    throw new IllegalStateException("no function named " + name);
  }

  private static String calleeSigOf(TacFunction fn) {
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind == TacInstruction.Kind.CALL && ins.calleeSig != null) {
        return ins.calleeSig;
      }
    }
    throw new IllegalStateException("no call in " + fn.signature());
  }
}
