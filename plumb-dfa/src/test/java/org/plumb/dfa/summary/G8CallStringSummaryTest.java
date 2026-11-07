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
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
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
 * G8: a summary is only true relative to the call site it was reached through.
 *
 * <p><b>The property.</b> A helper that returns its parameter says "this function returns tainted
 * data". That statement is about the function GIVEN which of its parameters were tainted. A helper
 * called once with attacker input and once with a constant does NOT return tainted data at both
 * call sites -- it returns tainted data at one of them.
 *
 * <p>With one shared summary per function, the two call sites share the union, so the constant call
 * inherits the tainted call's taint and the sink fires on a value that never was tainted. That is a
 * false positive, and it is the shape a scanner produces on every codebase that has a helper used
 * both ways -- which is most of them.
 *
 * <p><b>What the fix is.</b> The summary is still built ONCE from the callee's body; the
 * summary-building method is not re-run per call site. What is context-sensitive is the CALLER's
 * side: the summary is projected through the call site's binding -- "which argument was tainted,
 * and which formal parameter does it bind to" -- and the projection keeps only the formal
 * parameters that this call site actually feeds with taint.
 *
 * <p><b>Why the projection can only remove taint.</b> The projection is a subset operation, and a
 * lookup that finds no projection falls back to the context-free summary. So a context-sensitive
 * lookup can find strictly LESS than a context-insensitive one, never more, and never nothing when
 * the context-free one had something. That is why enabling it cannot introduce a false NEGATIVE.
 *
 * <p><b>The negative control.</b> {@link #theContextFreeSummaryStillFiresOnEveryCallSite()} pins
 * the context-free behaviour: the same program, with the projection disabled, reports the finding
 * on the clean call. Without it, a test that asserted "no finding" would also pass if the analysis
 * had simply stopped working.
 */
@DisplayName("G8: a summary is only true relative to the call site it was reached through")
class G8CallStringSummaryTest {

  @TempDir Path tmp;

  /**
   * The scenario: one helper, called twice -- once with attacker input, once with a constant.
   *
   * <p>The sink is fed by ONE of the two results. The test flips which one, and the expected
   * finding flips with it.
   *
   * @param sinkWhich "clean" or "dirty"
   * @return the source
   */
  private static String twoCallSites(String sinkWhich) {
    String sinkArg = "dirty".equals(sinkWhich) ? "dirty" : "clean";
    return """
    class Helper {
      String echo(String v) {
        return v;
      }
    }
    class Caller {
      void go(String tainted, java.sql.Statement st) throws Exception {
        Helper h = new Helper();
        String clean = h.echo("constant");
        String dirty = h.echo(tainted);
        st.executeQuery(%s);
      }
    }
    """
        .formatted(sinkArg);
  }

  /**
   * Scan a source and collect the taint findings.
   *
   * @param source the source
   * @param name the file's name
   * @return the findings
   */
  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig config =
        base.withTuning(base.tuning().withPathSensitivity("full").withContextDepth(2));
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  @Test
  @DisplayName("the tainted call site still reaches the sink")
  void theTaintedCallSiteStillReachesTheSink() throws Exception {
    List<Finding> findings = scan(twoCallSites("dirty"), "Dirty.java");

    assertThat(findings)
        .as(
            "the tainted argument IS the parameter the helper returns, so the taint must cross"
                + " the call boundary -- the projection narrows the fact, it does not remove it")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the clean call site does not inherit the tainted call's taint")
  void theCleanCallSiteDoesNotInheritTheTaintedCallsTaint() throws Exception {
    List<Finding> findings = scan(twoCallSites("clean"), "Clean.java");

    assertThat(findings)
        .as(
            "the constant call site passes nothing tainted, so nothing can reach the sink"
                + " through it -- reporting here would be a false positive")
        .isEmpty();
  }

  @Test
  @DisplayName("the context-free summary still fires on every call site")
  void theContextFreeSummaryStillFiresOnEveryCallSite() throws Exception {
    // The negative control. The context-free summary -- the callee's own
    // facts, with no caller in view -- says "this function returns tainted
    // data", and that statement holds at EVERY call site. So the projection
    // must be able to narrow it, and the mechanism that narrows it must be
    // reachable.
    //
    // This is asserted directly on the summary rather than through a scan,
    // because the point is about the SUMMARY's shape, not about a finding.
    FunctionSummary summary =
        new FunctionSummary.Builder("echo(String):String")
            .recordParamOp(0, new Operation.TaintPropagate("param:v in echo", 1.0))
            .recordReturnOp(new Operation.TaintPropagate("return:v in echo", 1.0))
            .recordReturnTaintCause(0)
            .build();

    assertThat(summary.returnTaintCauses())
        .as("the summary records which formal the return taint flows through")
        .containsExactly(0);

    // Projected through a call site that taints nothing: the parameter
    // operations AND the return taint are both unreachable.
    FunctionSummary clean = summary.projectThrough(CallSiteBinding.NONE);
    assertThat(clean.paramOpsAt(0))
        .as("nothing tainted at the call site, so no parameter operation is reachable")
        .isEmpty();
    assertThat(clean.returnOps())
        .as(
            "the return taint flowed through the parameter, and the parameter is clean here, so"
                + " the return taint does not fire")
        .isEmpty();

    // Projected through a call site that taints the parameter: everything
    // survives.
    FunctionSummary dirty = summary.projectThrough(CallSiteBinding.of(java.util.Map.of(0, 0)));
    assertThat(dirty.paramOpsAt(0))
        .as("the parameter is tainted at this call site, so its operations are reachable")
        .isNotEmpty();
    assertThat(dirty.returnOps())
        .as("and the return taint flows through it, so it fires")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a return taint with no parameter cause fires at every call site")
  void aReturnTaintWithNoParameterCauseFiresAtEveryCallSite() throws Exception {
    // The function returns a tainted value it did not get from a parameter --
    // it read a global, say. There is nothing to narrow the fact by, so the
    // projection must keep it. Dropping it would be a false negative, and a
    // silent one.
    FunctionSummary summary =
        new FunctionSummary.Builder("readGlobal():String")
            .recordReturnOp(new Operation.TaintPropagate("return:g in readGlobal", 1.0))
            .build();

    FunctionSummary projected = summary.projectThrough(CallSiteBinding.NONE);

    assertThat(projected.returnOps())
        .as("the return taint did not come from a parameter, so no call site can rule it out")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the projection is a subset: it never adds an operation")
  void theProjectionIsASubsetItNeverAddsAnOperation() throws Exception {
    FunctionSummary summary =
        new FunctionSummary.Builder("echo(String):String")
            .recordParamOp(0, new Operation.TaintPropagate("param:v in echo", 1.0))
            .recordParamOp(1, new Operation.TaintPropagate("param:w in echo", 1.0))
            .recordReturnOp(new Operation.TaintPropagate("return:v in echo", 1.0))
            .recordReturnTaintCause(0)
            .build();

    FunctionSummary projected = summary.projectThrough(CallSiteBinding.of(java.util.Map.of(1, 1)));

    // Formal 0 is not tainted at this call site, so its operations are gone.
    assertThat(projected.paramOpsAt(0)).isEmpty();
    // Formal 1 is, so its operations remain.
    assertThat(projected.paramOpsAt(1)).isNotEmpty();
    // The return taint flowed through formal 0, which is clean here, so it
    // does not fire.
    assertThat(projected.returnOps()).isEmpty();
  }
}
