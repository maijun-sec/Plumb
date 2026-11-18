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
 * A C# enumerator hands out the container's tainted elements.
 *
 * <p><b>Why C# needs a rule of its own.</b> C#'s enumerator has no {@code next} that returns the
 * element. {@code MoveNext} says whether there IS an element, and {@code Current} -- a property, so
 * the call that reads it is its getter, {@code get_Current} -- hands it out. Tainting what {@code
 * MoveNext} returns would taint a boolean that the program never uses.
 *
 * <p><b>Why the instructions are written out by hand.</b> The point of this test is the ENGINE's
 * protocol handling. The C# front end writes the four calls of the protocol, and the engine has to
 * recognise them; the TAC below is the one the C# side car produces for the corresponding C#
 * source, read back out of the side car's own output.
 *
 * <p><b>Why the clean case is here.</b> A rule that taints the element whatever the container holds
 * passes the first test and is useless. So the control -- a container whose element is clean -- is
 * asserted to stay clean.
 *
 * <p><b>What is NOT covered here, and why.</b> The C# front end builds a collection through {@code
 * Add} and not through an array store, so the collection's elements are not in the element tracker
 * yet, and an end-to-end C# program cannot reach the sink through this protocol. That gap belongs
 * to the front end's collection handling, not to the engine's protocol handling, and this test pins
 * the engine half so that the front-end half has something to land on.
 */
@DisplayName("Taint: a C# enumerator hands out the container's tainted elements")
class CsharpEnumeratorTaintTest {

  /** The sink: a shared family, so it applies to every language. */
  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  @Test
  @DisplayName("the element comes out of the enumerator's Current property")
  void theElementComesOutOfTheEnumeratorsCurrentProperty() {
    List<Finding> findings = scan(enumeratorOverAContainerWithATaintedElement());

    assertThat(hasFinding(findings, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "arr[0] = tainted; it = arr.GetEnumerator(); s = it.get_Current() -- the element is"
                + " the Current property of the enumerator, so the taint has to survive the"
                + " protocol")
        .isTrue();
  }

  @Test
  @DisplayName("the element does not come out of MoveNext")
  void theElementDoesNotComeOutOfMoveNext() {
    List<Finding> findings = scan(enumeratorAdvancingWithoutTakingTheElement());

    assertThat(findings)
        .as(
            "it = arr.GetEnumerator(); moved = it.MoveNext() -- MoveNext says whether there IS an"
                + " element, it is not the element, so the value it returns must not carry the"
                + " container's taint")
        .isEmpty();
  }

  @Test
  @DisplayName("a clean container hands out clean values")
  void aCleanContainerHandsOutCleanValues() {
    List<Finding> findings = scan(enumeratorOverAContainerWithACleanElement());

    assertThat(findings)
        .as(
            "arr[0] = \"safe\"; it = arr.GetEnumerator(); s = it.get_Current() -- nothing came from"
                + " outside the program, so the command is not a finding")
        .isEmpty();
  }

  // ---- the programs ---------------------------------------------------------------------------

  /** {@code arr[0] = tainted; it = arr.GetEnumerator(); s = it.get_Current(); Process.Start(s)} */
  private static TacFunction enumeratorOverAContainerWithATaintedElement() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar array = var("arr");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar temporary = temp("t_array");
    out.add(newArray(temporary, 11));
    out.add(arrayStore(temporary, i(0), request, 11));
    out.add(move(array, temporary, 11));

    appendEnumeration(out, array, x, 12);
    out.add(command(x, 13));
    return function(out, request, array, x);
  }

  /**
   * {@code arr[0] = tainted; it = arr.GetEnumerator(); moved = it.MoveNext(); Process.Start(moved)}
   *
   * <p>The element is never taken out of the enumerator, so nothing tainted reaches the sink.
   */
  private static TacFunction enumeratorAdvancingWithoutTakingTheElement() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar array = var("arr");
    TacOperand.TacVar moved = var("moved");

    TacOperand.TacVar temporary = temp("t_array");
    out.add(newArray(temporary, 11));
    out.add(arrayStore(temporary, i(0), request, 11));
    out.add(move(array, temporary, 11));

    TacOperand.TacVar iterator = temp("t_iterator");
    out.add(call("System.Collections.Generic.List.GetEnumerator/0", iterator, List.of(array), 12));
    TacOperand.TacVar advanced = temp("t_moved");
    out.add(
        call(
            "System.Collections.Generic.List.Enumerator.MoveNext/0",
            advanced,
            List.of(iterator),
            12));
    out.add(move(moved, advanced, 12));
    out.add(command(moved, 13));
    return function(out, request, array, moved);
  }

  /** {@code arr[0] = "safe"; it = arr.GetEnumerator(); s = it.get_Current(); Process.Start(s)} */
  private static TacFunction enumeratorOverAContainerWithACleanElement() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar array = var("arr");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar temporary = temp("t_array");
    out.add(newArray(temporary, 11));
    out.add(arrayStore(temporary, i(0), str("safe"), 11));
    out.add(move(array, temporary, 11));

    appendEnumeration(out, array, x, 12);
    out.add(command(x, 13));
    return function(out, request, array, x);
  }

  /**
   * The enumeration, as the C# side car lowers it.
   *
   * <p>{@code it = items.GetEnumerator(); moved = it.MoveNext(); s = it.get_Current();}
   */
  private static void appendEnumeration(
      List<TacInstruction> out, TacOperand.TacVar iterable, TacOperand.TacVar target, int line) {
    TacOperand.TacVar iterator = temp("t_iterator");
    out.add(
        call("System.Collections.Generic.List.GetEnumerator/0", iterator, List.of(iterable), line));
    TacOperand.TacVar advanced = temp("t_moved");
    out.add(
        call(
            "System.Collections.Generic.List.Enumerator.MoveNext/0",
            advanced,
            List.of(iterator),
            line));
    TacOperand.TacVar current = temp("t_current");
    out.add(
        call(
            "System.Collections.Generic.List.Enumerator.get_Current/0",
            current,
            List.of(iterator),
            line));
    out.add(move(target, current, line));
  }

  // ---- the harness ---------------------------------------------------------------------------

  private static TacFunction function(List<TacInstruction> out, TacOperand.TacVar... locals) {
    List<TacOperand.TacVar> vars = new ArrayList<>();
    for (TacOperand.TacVar v : locals) {
      vars.add(v);
    }
    return new TacFunction(
        "handle",
        // The signature is part of the OBJECT LABEL the points-to solver hands out, and the keyed
        // taint store is keyed by that label. Two programs in the same JVM that allocate the same
        // type at the same site under the same signature therefore share the store's keys -- so a
        // test that reuses a common signature can be broken by a test that ran before it. This one
        // names itself.
        "app.csharp_enumerator_control(any):any",
        TacType.UNKNOWN,
        List.of(vars.get(0)),
        vars,
        out,
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

  private static List<Finding> scan(TacFunction function) {
    TacProgram program = new TacProgram(Path.of("app.cs"), "app");
    program.addFunction(function);
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

  private static boolean hasFinding(List<Finding> findings, String ruleId, String callee) {
    for (Finding f : findings) {
      if (!f.ruleId().equals(ruleId)) continue;
      if (f.message().contains(callee)) return true;
    }
    return false;
  }

  // ---- the instruction builders ----------------------------------------------------------------

  private static TacOperand.TacVar var(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, false);
  }

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  private static TacOperand.Const str(String value) {
    return TacOperand.Const.ofString(value);
  }

  private static TacOperand.Const i(int value) {
    return TacOperand.Const.ofInt(value);
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

  /** The command sink: the receiver slot holds the type, the value is the first argument. */
  private static TacInstruction command(TacOperand value, int line) {
    return call(
        "System.Diagnostics.Process.Start/2",
        temp("t_result"),
        List.of(str("System.Diagnostics.Process"), value),
        line);
  }

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction arrayStore(
      TacOperand receiver, TacOperand key, TacOperand value, int line) {
    return TacInstruction.builder(TacInstruction.Kind.ARRAYSTORE)
        .lhs1(receiver)
        .lhs2(key)
        .dest(value)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction newArray(TacOperand dest, int line) {
    return TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
        .dest(dest)
        .ctorType(TacType.arrayOf(TacType.UNKNOWN))
        .line(line)
        .column(1)
        .build();
  }
}
