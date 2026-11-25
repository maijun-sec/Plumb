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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.FlowStep;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * Who owns the "this value came from an untrusted place" label?
 *
 * <p><b>The question.</b> The taint lattice has one fact -- "this variable holds a value that came
 * from an untrusted source" -- and it is stored twice over: as a LABEL in {@code taintedVars} and
 * as a PATH in {@code taintPaths}. The label is what a finding names; the path is what a finding
 * shows. When they disagree, the finding says one thing and proves another.
 *
 * <p><b>Why the label can diverge.</b> The base class marks an input-source call's destination
 * once, with {@code "input:" + calleeSig}, and records the matching path. Several checkers then
 * wrote that same label AGAIN, into the same map, with their own copy of the format. Today the two
 * copies agree, so the duplicate writes are invisible. The moment the base's format changes -- a
 * different prefix, a richer label, a callee spelling -- the checkers that kept their own copy keep
 * writing the OLD format, and the same source is labelled one way by the checkers that use the base
 * and another way by the checkers that do not. Nothing reports it: every finding is individually
 * well-formed.
 *
 * <p><b>What this guard pins.</b> Four things, checked across checkers rather than within one,
 * because a per-checker test cannot catch a split -- each checker is individually consistent; only
 * comparing the checkers against each other can.
 *
 * <ol>
 *   <li><b>One owner per fact.</b> Every taint checker labels the same source the same way.
 *   <li><b>The label names its source.</b> The label must contain the source call's signature. This
 *       is the invariant that catches a LOST source marking. Measured, not reasoned: with the
 *       base's marking deleted, every checker still agreed -- on {@code
 *       "rcv-passthrough:param:reader"}, a label produced by the RECEIVER-passthrough path, not by
 *       the source marking at all. Agreement alone would have read that as healthy.
 *   <li><b>The label's prefix is one the engine preserves.</b> The base class keeps an old label
 *       across an assignment only when it starts with {@code "param:"} or {@code "input:"} ({@code
 *       TaintedCheckerBase}, the MOVE handler's path-insensitive rule). A source label outside that
 *       set dies on the next assignment, so its rule goes blind without saying so.
 *   <li><b>The path agrees with the label.</b> The flow path must start with the {@code "source"}
 *       step and name the same source, so a finding cannot claim one origin and prove another.
 * </ol>
 *
 * <p><b>And what it deliberately does NOT pin.</b> Two checkers own a source pattern of their own
 * ({@code sensitive:} and {@code pii:}), which the base class does not know about. Their labels are
 * meant to differ, and the guard asserts that difference as well -- otherwise "make them all the
 * same" would delete their only marking.
 */
class SourceMarkingLabelConsistencyTest {

  /** One input-source call whose destination every taint checker must label identically. */
  private static final String ONE_SOURCE =
      """
      class L1 {
        void go(java.io.BufferedReader reader) throws Exception {
          String s = reader.readLine();
          System.out.println(s);
        }
      }
      """;

  /** A source that only the sensitive-data checker knows about. */
  private static final String SENSITIVE_SOURCE =
      """
      class L2 {
        void go() {
          String s = System.getenv("SECRET_TOKEN");
          System.out.println(s);
        }
      }
      """;

  /** A source that only the PII checker knows about. */
  private static final String PII_SOURCE =
      """
      class L3 {
        void go() {
          String s = new java.io.File("x").getName();
          System.out.println(s);
        }
      }
      """;

  /**
   * The checkers that take their label from the base class's input-source marking.
   *
   * <p>Deliberately broad: the point of the comparison is that a checker which kept its own copy of
   * the format is caught. {@code DivByZeroChecker} is in the set because it carried such a copy
   * too, and its rule reads taint-presence rather than the label, so it is free to take the base's
   * label like everybody else.
   */
  private static List<TaintedCheckerBase> baseLabelledCheckers() {
    return List.of(
        new SqlInjectionChecker(),
        new IntUnderflowChecker(),
        new IntOverflowChecker(),
        new ArrayIndexChecker(),
        new PathTraversalChecker(),
        new DivByZeroChecker());
  }

  @TempDir Path tmp;

  /** The source call this test pins: its destination and its signature. */
  private record SourceCall(TacOperand.TacVar dest, String calleeSig) {}

  private TacProgram program(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }

  /** The destination variable of the first CALL whose callee matches, in the only function. */
  private SourceCall sourceCall(TacProgram program, String calleeFragment) {
    for (TacFunction fn : program.functions()) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        if (ins.calleeSig == null || !ins.calleeSig.contains(calleeFragment)) continue;
        if (ins.dest instanceof TacOperand.TacVar d) {
          return new SourceCall(d, ins.calleeSig);
        }
      }
    }
    throw new AssertionError("no CALL matching " + calleeFragment);
  }

  /** Run one checker over one program and return the label it left on the call's destination. */
  private String labelAfter(
      TaintedCheckerBase checker, TacProgram program, TacOperand.TacVar dest) {
    EngineConfig base = ConfigLoader.loadFromClasspath();
    new DfaEngine(base, List.of(checker), "global", null).runWithGlobalStore(List.of(program), 0);
    return checker.taintedLabelFor(dest);
  }

  /** Run one checker over one program and return the flow path it left on the destination. */
  private List<FlowStep> pathAfter(
      TaintedCheckerBase checker, TacProgram program, TacOperand.TacVar dest) {
    EngineConfig base = ConfigLoader.loadFromClasspath();
    new DfaEngine(base, List.of(checker), "global", null).runWithGlobalStore(List.of(program), 0);
    return checker.taintedPathFor(dest);
  }

  @Test
  void everyTaintCheckerLabelsTheSameSourceTheSameWay() throws Exception {
    TacProgram program = program(ONE_SOURCE, "L1.java");
    SourceCall source = sourceCall(program, "readLine");

    Map<String, String> labels = new LinkedHashMap<>();
    for (TaintedCheckerBase checker : baseLabelledCheckers()) {
      labels.put(checker.getClass().getSimpleName(), labelAfter(checker, program, source.dest()));
    }

    assertThat(labels)
        .as("every taint checker must label the same source the same way -- one owner per fact")
        .isNotEmpty();
    assertThat(new ArrayList<>(labels.values()).stream().distinct().count())
        .as("the labels differ between checkers: %s", labels)
        .isEqualTo(1);
    for (Map.Entry<String, String> entry : labels.entrySet()) {
      assertThat(entry.getValue())
          .as("%s must have labelled the source", entry.getKey())
          .isNotNull();
    }
  }

  @Test
  void theLabelNamesTheSourceItComesFrom() throws Exception {
    TacProgram program = program(ONE_SOURCE, "L1.java");
    SourceCall source = sourceCall(program, "readLine");

    Map<String, String> labels = new LinkedHashMap<>();
    for (TaintedCheckerBase checker : baseLabelledCheckers()) {
      labels.put(checker.getClass().getSimpleName(), labelAfter(checker, program, source.dest()));
    }

    for (Map.Entry<String, String> entry : labels.entrySet()) {
      assertThat(entry.getValue())
          .as(
              "%s labelled the source with %s, which does not name the source call %s -- "
                  + "a label that names something else is a label the engine produced by "
                  + "propagation, not by the source marking",
              entry.getKey(), entry.getValue(), source.calleeSig())
          .contains(source.calleeSig());
    }
  }

  @Test
  void theLabelPrefixIsOneTheEngineKeepsAcrossAnAssignment() throws Exception {
    TacProgram program = program(ONE_SOURCE, "L1.java");
    SourceCall source = sourceCall(program, "readLine");

    for (TaintedCheckerBase checker : baseLabelledCheckers()) {
      String label = labelAfter(checker, program, source.dest());
      assertThat(label)
          .as(
              "%s labelled the source with %s -- a prefix outside {param:, input:} is dropped by "
                  + "the base class's MOVE rule, so the value loses its taint on the next "
                  + "assignment and the rule goes blind without saying so",
              checker.getClass().getSimpleName(), label)
          .startsWith("input:");
    }
  }

  @Test
  void thePathAgreesWithTheLabel() throws Exception {
    TacProgram program = program(ONE_SOURCE, "L1.java");
    SourceCall source = sourceCall(program, "readLine");

    for (TaintedCheckerBase checker : baseLabelledCheckers()) {
      List<FlowStep> path = pathAfter(checker, program, source.dest());
      assertThat(path)
          .as(
              "%s must have recorded a flow path for the source",
              checker.getClass().getSimpleName())
          .isNotEmpty();
      FlowStep first = path.get(0);
      assertThat(first.role())
          .as(
              "%s: the path must start at the source, not at a propagation",
              checker.getClass().getSimpleName())
          .isEqualTo("source");
      assertThat(first.expr())
          .as(
              "%s: the path must name the same source as the label does",
              checker.getClass().getSimpleName())
          .contains(source.calleeSig());
    }
  }

  @Test
  void theSensitiveSourceCheckerKeepsItsOwnLabel() throws Exception {
    TacProgram program = program(SENSITIVE_SOURCE, "L2.java");
    SourceCall source = sourceCall(program, "getenv");

    SensitiveDataLeakChecker checker = new SensitiveDataLeakChecker();
    String label = labelAfter(checker, program, source.dest());

    assertThat(label)
        .as(
            "the base class does not know the sensitive-source patterns, so this checker owns the "
                + "marking itself -- deleting it would delete the rule's sources")
        .isNotNull()
        .startsWith("sensitive:");
  }

  @Test
  void thePiiSourceCheckerKeepsItsOwnLabel() throws Exception {
    TacProgram program = program(PII_SOURCE, "L3.java");
    SourceCall source = sourceCall(program, "getName");

    SensitivePIILeakChecker checker = new SensitivePIILeakChecker();
    String label = labelAfter(checker, program, source.dest());

    assertThat(label)
        .as(
            "the base class does not know the PII source patterns, so this checker owns the "
                + "marking itself -- deleting it would delete the rule's sources")
        .isNotNull()
        .startsWith("pii:");
  }
}
