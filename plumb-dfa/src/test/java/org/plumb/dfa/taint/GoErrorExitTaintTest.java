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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.checkers.taint.DatabaseAccessChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The error a Go function returns on its exceptional exit carries its taint to the caller.
 *
 * <h2>The shape this is about</h2>
 *
 * <p>Go's error convention has two exits. A function that returns {@code (T, error)} returns
 * normally with a nil error, and it returns <b>exceptionally</b> with a non-nil one. The Go side
 * car lowers the exceptional exit to a THROW -- that is what makes the error path visible to the
 * engine at all.
 *
 * <p>But the function still hands back <b>two</b> values on that path: the caller writes {@code v,
 * err := f()} and binds both of them. The exceptional exit is not a path that returns nothing; it
 * is a path that returns the error.
 *
 * <h2>What was lost</h2>
 *
 * <p>The THROW named no results. So the callee's summary had no entry for the second result, and
 * the caller's {@code err} variable was <b>a variable nothing writes</b> -- which is
 * indistinguishable from a clean one. A callee that put attacker-controlled text into its error
 * result lost that text at the call boundary, and a sink that consumed the error was never
 * reported.
 *
 * <p>The fix puts the results on the THROW. The exceptional exit keeps everything it had: the
 * thrown value, the type it raises, the catch matching.
 *
 * <h2>Why the control matters most</h2>
 *
 * <p>The second test is the control: a producer whose error is CLEAN must leave the caller's error
 * clean. A fix that says "a throw taints every result it names" would pass the first test and be
 * useless -- and it would be a false positive on every Go program whose error is a constant.
 */
@DisplayName("Taint: the error a Go function returns on its exceptional exit carries its taint")
class GoErrorExitTaintTest {

  /** The taint source: a file read the program did not write. */
  private static final String SOURCE_CALL = "os.ReadFile/1";

  /** The sink: a shared family, so it applies to every language. */
  private static final String SINK_CALL = "java.sql.Statement.execute/1";

  @Test
  @DisplayName("the taint in a callee's error result reaches the caller's variable for it")
  void theTaintInACalleesErrorResultReachesTheCallersVariableForIt() {
    List<Finding> findings = scan(producer("producer", true), handler("producer"));

    assertThat(findings)
        .as(
            "the producer puts attacker-controlled text into the error it returns on its"
                + " exceptional exit; the caller binds that error to its own variable, and a sink"
                + " that consumes the variable must see the taint")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a clean error stays clean when the value result is tainted")
  void aCleanErrorStaysCleanWhenTheValueResultIsTainted() {
    List<Finding> findings = scan(producer("producer", false), handler("producer"));

    assertThat(findings)
        .as(
            "the producer's error is clean; only its value result carries the taint. A binding"
                + " that taints every result of a throw would report this one")
        .isEmpty();
  }

  // ---- the programs ---------------------------------------------------------------------------

  /**
   * The producer: {@code func producer() (string, error)}.
   *
   * <p>It reads a file and returns the content in one of its two results. Which one carries the
   * taint is the only difference between the two programs.
   *
   * <p>The exit is a THROW, not a RETURN: that is the shape Go's {@code return 0, err} takes, and
   * the results ride on it.
   */
  private static TacFunction producer(String name, boolean taintTheErrorResult) {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar input = var("t_input", TacType.STRING);
    out.add(call(SOURCE_CALL, input, List.of(str("/etc/passwd")), 10));
    TacOperand.TacVar first = var("t_first", TacType.STRING);
    TacOperand.TacVar second = var("t_second", TacType.STRING);
    if (taintTheErrorResult) {
      out.add(move(second, input, 11));
    } else {
      out.add(move(first, input, 11));
    }
    // The exceptional exit hands back BOTH results: the value first, the error second. The
    // thrown value is the error, so one value plays both roles.
    out.add(
        TacInstruction.builder(TacInstruction.Kind.THROW)
            .lhs1(second)
            .ctorType(TacType.ofClass("error"))
            .resultDests(List.of(first, second))
            .line(12)
            .column(1)
            .build());
    return goFunction(name, out, List.of());
  }

  /**
   * The caller: {@code v, err := producer(); sink(err)}.
   *
   * <p>The call binds its first result to the destination and its second one to a variable of its
   * own, exactly as the Go front end emits it.
   */
  private static TacFunction handler(String producerName) {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar first = var("t_value", TacType.STRING);
    TacOperand.TacVar second = var("t_error", TacType.STRING);
    out.add(
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig(producerName + "/0")
            .dest(first)
            .resultDests(List.of(first, second))
            .args(List.of())
            .line(20)
            .column(1)
            .build());
    sink(out, second, 21);
    return goFunction("handler", out, List.of());
  }

  // ---- the harness ---------------------------------------------------------------------------

  private static List<Finding> scan(TacFunction... functions) {
    TacProgram program = new TacProgram(Path.of("app.go"), "app");
    for (TacFunction fn : functions) {
      program.addFunction(fn);
    }
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new DatabaseAccessChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  /** A Go function: the language marker decides which catalogue the sources and sinks come from. */
  private static TacFunction goFunction(
      String name, List<TacInstruction> instructions, List<TacOperand.TacVar> params) {
    List<TacOperand.TacVar> locals = new ArrayList<>();
    for (TacInstruction ins : instructions) {
      List<TacOperand> operands = new ArrayList<>();
      operands.add(ins.dest);
      operands.add(ins.lhs1);
      operands.add(ins.lhs2);
      operands.addAll(ins.knownResultDests());
      for (TacOperand op : operands) {
        if (op instanceof TacOperand.TacVar v && !v.isTemp() && !locals.contains(v)) {
          locals.add(v);
        }
      }
    }
    return new TacFunction(
        name,
        name + "():void",
        TacType.VOID,
        params,
        locals,
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.GO,
        IrPrecision.SOURCE_FULL);
  }

  // ---- the instruction builders ----------------------------------------------------------------

  private static TacOperand.TacVar var(String name, TacType type) {
    return new TacOperand.TacVar(name, type, false);
  }

  private static TacOperand.Const str(String value) {
    return TacOperand.Const.ofString(value);
  }

  private static TacInstruction call(
      String callee, TacOperand dest, List<TacOperand> args, int line) {
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .calleeSig(callee)
        .dest(dest)
        .args(args)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  /** The tainted value reaching the sink: {@code "SELECT " + value}, then the sink call. */
  private static void sink(List<TacInstruction> out, TacOperand value, int line) {
    TacOperand.TacVar query = var("t_query", TacType.STRING);
    out.add(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(query)
            .lhs1(str("SELECT * FROM t WHERE x = '"))
            .lhs2(value)
            .binop(BinaryOperator.ADD)
            .line(line)
            .column(1)
            .build());
    // The sink's argument index is 0 in the catalogue, which is args[1] in the TAC: the
    // receiver occupies the receiver slot.
    out.add(
        call(
            SINK_CALL,
            var("t_result", TacType.UNKNOWN),
            List.of(var("stmt", TacType.UNKNOWN), query),
            line));
  }
}
