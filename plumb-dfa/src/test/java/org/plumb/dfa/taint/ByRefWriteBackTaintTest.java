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
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * A value the CALLEE writes back into a by-reference argument reaches the caller's variable.
 *
 * <h2>Why this needed its own machinery</h2>
 *
 * <p>A by-reference argument -- C#'s {@code out} and {@code ref} -- is written by the callee and
 * read by the caller, so the value that crosses the call boundary travels through the FORMAL rather
 * than through a result. The TAC's CALL has ONE destination, and that destination is the call's
 * RESULT. A variable the callee wrote and the caller reads had nowhere to go: the front end had no
 * way to say "this argument is written back", so the information was simply gone.
 *
 * <p><b>And a variable nothing writes is indistinguishable from a clean one.</b> That is the whole
 * defect: a callee that puts attacker-controlled text into its {@code out} parameter lost that text
 * at the call boundary, and a sink that consumed the variable was never reported.
 *
 * <h2>What the tests pin</h2>
 *
 * <p>The first says the taint a callee writes into its {@code out} parameter reaches the caller's
 * variable for it -- including the case where the taint came from the CALLEE'S OWN source, which is
 * the case with no argument to blame it on. The second is the control: a clean value written into
 * the same parameter leaves the caller's variable clean, so the rule cannot be "every by-reference
 * argument is tainted".
 *
 * <p>The third and fourth are the other direction: when the written taint DID come through an
 * argument, the write-back has to fire where that argument is tainted and nowhere else. A binding
 * that tainted every by-reference argument would pass the third test and be useless -- it would
 * report every call that passes an {@code out} variable, whether the value in it was
 * attacker-controlled or not.
 *
 * <h2>Why the instructions are written out by hand</h2>
 *
 * <p>No Java program produces a by-reference argument, so the TAC is written directly, in the shape
 * the C# front end emits: the argument list keeps the receiver in its first slot, and the
 * by-reference list runs PARALLEL to it, with a null in the slot of every argument that is not
 * written back.
 */
@DisplayName("Taint: a value written back into a by-reference argument")
class ByRefWriteBackTaintTest {

  /** The source: a line the program did not write. */
  private static final String SOURCE_CALL = "System.Console.ReadLine/0";

  /** The sink: a command that runs what it is given. */
  private static final String SINK_CALL = "System.Diagnostics.Process.Start/1";

  @Test
  @DisplayName("the taint the callee writes into its out parameter reaches the caller's variable")
  void theTaintTheCalleeWritesIntoItsOutParameterReachesTheCallersVariable() {
    List<Finding> findings = scan(producerThatReadsASource(), callerThatPassesAnOutArgument());

    assertThat(findings)
        .as(
            "the producer reads a source and puts it into its out parameter; the caller passes a"
                + " variable for that parameter, and a sink that consumes the variable has to see"
                + " the taint")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a clean value written into an out parameter leaves the caller's variable clean")
  void aCleanValueWrittenIntoAnOutParameterLeavesTheCallersVariableClean() {
    List<Finding> findings = scan(producerThatWritesAConstant(), callerThatPassesAnOutArgument());

    assertThat(findings)
        .as(
            "the producer writes a literal into its out parameter, and a literal is clean; a rule"
                + " that tainted every by-reference argument would report this one")
        .isEmpty();
  }

  @Test
  @DisplayName("the taint copied from a tainted argument is written back into the out parameter")
  void theTaintCopiedFromATaintedArgumentIsWrittenBackIntoTheOutParameter() {
    List<Finding> findings =
        scan(producerThatCopiesItsArgument(), callerThatPassesATaintedArgumentAndAnOutArgument());

    assertThat(findings)
        .as(
            "the producer copies its first argument into its out parameter, and the caller passes a"
                + " tainted argument, so the write-back carries the taint to the caller's variable")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the taint is not written back when the argument it came from is clean")
  void theTaintIsNotWrittenBackWhenTheArgumentItCameFromIsClean() {
    List<Finding> findings =
        scan(producerThatCopiesItsArgument(), callerThatPassesACleanArgumentAndAnOutArgument());

    assertThat(findings)
        .as(
            "the producer copies its first argument into its out parameter, but the caller passes a"
                + " clean one, so the write-back carries no taint and the sink stays silent")
        .isEmpty();
  }

  // ---- the programs ---------------------------------------------------------------------------

  /**
   * The producer: {@code static bool Read(out string value)}.
   *
   * <p>It reads a line and puts it into the parameter the caller passed by reference. The taint's
   * origin is the callee's own source -- there is no argument to blame it on.
   */
  private static TacFunction producerThatReadsASource() {
    TacOperand.TacVar value = var("value", TacType.STRING);
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar read = var("t_read", TacType.STRING);
    out.add(call(SOURCE_CALL, read, List.of(), 10));
    out.add(move(value, read, 11));
    out.add(returnValue(TacOperand.Const.ofBool(true), 12));
    return csharpFunction("producer", out, List.of(value));
  }

  /** The producer: {@code static bool Constant(out string value)} -- it writes a literal. */
  private static TacFunction producerThatWritesAConstant() {
    TacOperand.TacVar value = var("value", TacType.STRING);
    List<TacInstruction> out = new ArrayList<>();
    out.add(move(value, TacOperand.Const.ofString("constant"), 10));
    out.add(returnValue(TacOperand.Const.ofBool(true), 11));
    return csharpFunction("producer", out, List.of(value));
  }

  /**
   * The producer: {@code static bool Copy(string input, out string value)} -- it copies its
   * argument.
   */
  private static TacFunction producerThatCopiesItsArgument() {
    TacOperand.TacVar input = var("input", TacType.STRING);
    TacOperand.TacVar value = var("value", TacType.STRING);
    List<TacInstruction> out = new ArrayList<>();
    out.add(move(value, input, 10));
    out.add(returnValue(TacOperand.Const.ofBool(true), 11));
    return csharpFunction("producer", out, List.of(input, value));
  }

  /**
   * The caller: {@code Producer.Read(out result); Process.Start(result);}.
   *
   * <p>The argument list keeps the declaring type in the receiver slot, and the by-reference list
   * runs parallel to it: a null in the slot of every argument that is not written back.
   */
  private static TacFunction callerThatPassesAnOutArgument() {
    TacOperand.TacVar result = var("t_result", TacType.STRING);
    List<TacInstruction> out = new ArrayList<>();
    out.add(byRefCall("producer/1", List.of(result), List.of(result), 20));
    out.add(sinkOn(result, 21));
    return csharpFunction("caller", out, List.of());
  }

  /** The caller: it reads a source and passes it as the producer's first argument. */
  private static TacFunction callerThatPassesATaintedArgumentAndAnOutArgument() {
    TacOperand.TacVar input = var("t_input", TacType.STRING);
    TacOperand.TacVar result = var("t_result", TacType.STRING);
    List<TacInstruction> out = new ArrayList<>();
    out.add(call(SOURCE_CALL, input, List.of(), 19));
    out.add(
        byRefCall("producer/2", List.of(input, result), java.util.Arrays.asList(null, result), 20));
    out.add(sinkOn(result, 21));
    return csharpFunction("caller", out, List.of());
  }

  /** The caller: it passes a literal as the producer's first argument. */
  private static TacFunction callerThatPassesACleanArgumentAndAnOutArgument() {
    TacOperand.TacVar result = var("t_result", TacType.STRING);
    List<TacInstruction> out = new ArrayList<>();
    out.add(
        byRefCall(
            "producer/2",
            List.of(TacOperand.Const.ofString("clean"), result),
            java.util.Arrays.asList(null, result),
            20));
    out.add(sinkOn(result, 21));
    return csharpFunction("caller", out, List.of());
  }

  // ---- the harness ---------------------------------------------------------------------------

  private static List<Finding> scan(TacFunction... functions) {
    TacProgram program = new TacProgram(Path.of("app.cs"), "app");
    for (TacFunction fn : functions) {
      program.addFunction(fn);
    }
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new CommandInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  /**
   * A C# function.
   *
   * <p>The signature spells the parameter list, because the arity in it is what a call site's
   * {@code name/arity} key is matched against.
   */
  private static TacFunction csharpFunction(
      String name, List<TacInstruction> instructions, List<TacOperand.TacVar> params) {
    List<TacOperand.TacVar> locals = new ArrayList<>();
    for (TacInstruction ins : instructions) {
      List<TacOperand> operands = new ArrayList<>();
      operands.add(ins.dest);
      operands.add(ins.lhs1);
      operands.add(ins.lhs2);
      for (TacOperand op : operands) {
        if (op instanceof TacOperand.TacVar v
            && !v.isTemp()
            && !locals.contains(v)
            && !params.contains(v)) {
          locals.add(v);
        }
      }
    }
    StringBuilder signature = new StringBuilder(name).append('(');
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) {
        signature.append(',');
      }
      signature.append("string");
    }
    signature.append("):void");
    return new TacFunction(
        name,
        signature.toString(),
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
        SourceLanguage.CSHARP,
        IrPrecision.SOURCE_FULL);
  }

  // ---- the instruction builders ----------------------------------------------------------------

  private static TacOperand.TacVar var(String name, TacType type) {
    return new TacOperand.TacVar(name, type, false);
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

  /**
   * A call with a by-reference argument.
   *
   * <p>The declaring type goes into the receiver slot, the arguments follow it, and the
   * by-reference list runs parallel to the WHOLE argument list: a null in the receiver's slot, and
   * a null in the slot of every argument that is not written back.
   *
   * @param writtenBack the variable each USER argument is written back into, aligned with {@code
   *     userArgs}; a null means that argument is not written back
   */
  private static TacInstruction byRefCall(
      String callee, List<TacOperand> userArgs, List<TacOperand> writtenBack, int line) {
    List<TacOperand> args = new ArrayList<>();
    List<TacOperand> refOuts = new ArrayList<>();
    args.add(TacOperand.Const.ofString("Demo.Producer"));
    refOuts.add(null);
    for (int i = 0; i < userArgs.size(); i++) {
      args.add(userArgs.get(i));
      refOuts.add(writtenBack.size() > i ? writtenBack.get(i) : null);
    }
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .calleeSig(callee)
        .dest(var("t_flag", TacType.BOOLEAN))
        .args(args)
        .refOutDests(refOuts)
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

  private static TacInstruction returnValue(TacOperand value, int line) {
    return TacInstruction.builder(TacInstruction.Kind.RETURN)
        .lhs1(value)
        .line(line)
        .column(1)
        .build();
  }

  /**
   * The sink: the tainted value reaches the command that runs it.
   *
   * <p>The catalogue names USER arguments, and the TAC keeps the receiver in the first slot, so the
   * catalogue's argument 0 is the TAC's second argument.
   */
  private static TacInstruction sinkOn(TacOperand value, int line) {
    return call(
        SINK_CALL,
        var("t_started", TacType.UNKNOWN),
        List.of(TacOperand.Const.ofString("System.Diagnostics.Process"), value),
        line);
  }
}
