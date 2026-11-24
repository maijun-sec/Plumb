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
 * A call that produces SEVERAL values carries each one's taint.
 *
 * <h2>Why this needed its own machinery</h2>
 *
 * <p>A Go call can return several values: {@code v, err := f()}. The TAC's CALL has ONE
 * destination, so the results after the first had nowhere to go. The front end had been sending
 * them on the wire for a long time -- the Go side car fills the instruction's result list -- and
 * the Java side read neither the wire field nor had a field to read it into, so the information was
 * simply gone. The engine saw a call with one result, and a variable {@code err} that <b>nothing
 * ever writes</b>.
 *
 * <p><b>And a variable nothing writes is indistinguishable from a clean one.</b> That is the whole
 * defect: a callee that puts attacker-controlled text into its error result lost that text at the
 * call boundary, and a sink that consumed the error was never reported.
 *
 * <h2>What the two tests pin</h2>
 *
 * <p>The first says the taint in a callee's SECOND result reaches the caller's variable for it. The
 * second is the control: the taint is in the FIRST result, the second result is clean, and the sink
 * that consumes the second result must stay silent.
 *
 * <p><b>The control is the one that matters.</b> A binding that taints every result would pass the
 * first test and be useless -- and it would be a false positive on every Go program whose error
 * result is the clean one. So the second test says: the binding is BY INDEX, not "the call produced
 * something tainted".
 *
 * <h2>Why the instructions are written out by hand</h2>
 *
 * <p>No Java program produces a multi-value call, so the TAC is written directly, in the shape the
 * Go front end emits: the call's destination is its first result, and the instruction's result list
 * names the variable that holds each result.
 */
@DisplayName("Taint: a call that produces several values carries each one's taint")
class MultiResultTaintTest {

  /** The taint source: a file read the program did not write. */
  private static final String SOURCE_CALL = "os.ReadFile/1";

  /** The sink: a shared family, so it applies to every language. */
  private static final String SINK_CALL = "java.sql.Statement.execute/1";

  @Test
  @DisplayName("the taint in a callee's second result reaches the caller's variable for it")
  void theTaintInACalleesSecondResultReachesTheCallersVariableForIt() {
    List<Finding> findings = scan(producer("producer", true), handler("producer"));

    assertThat(findings)
        .as(
            "the producer puts attacker-controlled text into its SECOND result; the caller binds"
                + " that result to its own variable, and a sink that consumes the variable must see"
                + " the taint")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a clean second result stays clean when the first one is tainted")
  void aCleanSecondResultStaysCleanWhenTheFirstOneIsTainted() {
    List<Finding> findings = scan(producer("producer", false), handler("producer"));

    assertThat(findings)
        .as(
            "the producer puts the taint in its FIRST result; the second result is clean, and a"
                + " binding that taints every result of a call would report this one")
        .isEmpty();
  }

  // ---- the programs ---------------------------------------------------------------------------

  /**
   * The producer: {@code func producer() (string, error)}.
   *
   * <p>It reads a file, puts the content into ONE of its two results, and returns both. Which one
   * carries the taint is the only difference between the two programs.
   */
  private static TacFunction producer(String name, boolean taintTheSecondResult) {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar input = var("t_input", TacType.STRING);
    out.add(call(SOURCE_CALL, input, List.of(str("/etc/passwd")), 10));
    TacOperand.TacVar first = var("t_first", TacType.STRING);
    TacOperand.TacVar second = var("t_second", TacType.STRING);
    if (taintTheSecondResult) {
      out.add(move(second, input, 11));
    } else {
      out.add(move(first, input, 11));
    }
    // The return hands back both results: the first one in the instruction's own value
    // slot, and BOTH of them in the result list -- entry 0 is the first result.
    out.add(
        TacInstruction.builder(TacInstruction.Kind.RETURN)
            .lhs1(first)
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
