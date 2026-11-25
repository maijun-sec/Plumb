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
package org.plumb.tac.frontend.csharp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The lowering of a call to an EXTENSION METHOD in its reduced form.
 *
 * <p><b>The question.</b> An extension method's receiver is its FIRST FORMAL -- the {@code this}
 * parameter -- and it is also the call's receiver, which the engine reads from {@code args[0]}. The
 * engine binds formal {@code i} to {@code args[i+1]}. So the receiver's value has to be in both
 * places: at {@code args[0]}, where the receiver slot is, and at {@code args[1]}, where the first
 * formal's value is.
 *
 * <p><b>Why the shape matters.</b> Every consumer derives the number of parameters from the
 * argument count ({@code args.size() - 1}). An argument list that is exactly as long as the
 * parameter list therefore reads every formal after the first one argument too far, and does not
 * read the LAST formal at all -- and a by-reference write-back lands on the last formal.
 *
 * <p><b>Why the controls are here.</b> The same method reached in its explicit static form, a plain
 * static call and a plain instance call all have to keep their shape. A fix that reshapes the
 * argument list would break one of them, and the control is what says it did not.
 */
@DisplayName("C#: the TAC lowering of a call to an extension method")
class CSharpExtensionMethodLoweringTest {

  private static CSharpTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new CSharpTestSupport();
  }

  @AfterAll
  static void tearDown() {
    if (support != null) {
      support.close();
    }
  }

  /** An extension method, reached both ways, plus the plain static and instance calls. */
  private static final String SOURCE =
      """
      namespace Demo;

      public static class Ext
      {
          public static void Fill(this string s, out string v)
          {
              v = s;
          }

          public static string Echo(this string s, string other)
          {
              return s;
          }
      }

      public class Plain
      {
          public static void FillStatic(string s, out string v)
          {
              v = s;
          }

          public string EchoInstance(string other)
          {
              return other;
          }
      }

      public class Caller
      {
          public void Run()
          {
              var text = "text";
              var other = "other";
              var result = "result";
              text.Fill(out result);
              Ext.Fill(text, out result);
              Plain.FillStatic(text, out result);
              var echoed = text.Echo(other);
              var instance = new Plain();
              instance.EchoInstance(other);
          }
      }
      """;

  @Test
  @DisplayName(
      "the receiver of a reduced extension call is in the receiver slot and in the first formal's"
          + " slot")
  void theReceiverOfAReducedExtensionCallIsInBothSlots() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Fill");

    assertThat(call.args)
        .as(
            "one slot for the receiver and one per parameter, so a two-parameter extension method"
                + " passes three arguments")
        .hasSize(3);
    assertThat(variableName(call.args.get(0)))
        .as(
            "the receiver slot holds the receiver, or a call on a possibly-null receiver is"
                + " invisible")
        .isEqualTo("text");
    assertThat(variableName(call.args.get(1)))
        .as("the first formal IS the receiver, so its slot holds the receiver too")
        .isEqualTo("text");
    assertThat(variableName(call.args.get(2)))
        .as("the second formal's slot holds the argument passed for it")
        .isEqualTo("result");
  }

  @Test
  @DisplayName("the argument list of an extension call is one longer than the parameter list")
  void theArgumentListOfAnExtensionCallIsOneLongerThanTheParameterList() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Echo");

    assertThat(call.calleeSig).isEqualTo("Demo.Ext.Echo/2");
    assertThat(call.args)
        .as(
            "the consumers derive the parameter count from the argument count, so a list that is"
                + " exactly as long as the parameter list costs the call its last formal")
        .hasSize(3);
  }

  @Test
  @DisplayName("the explicit static form of the same method reaches the same parameter")
  void theExplicitStaticFormOfTheSameMethodReachesTheSameParameter() {
    List<TacInstruction> calls = callsNamed(support.parse(SOURCE).functions(), "Fill");

    assertThat(calls).as("the method is called twice: reduced and explicit").hasSize(2);
    TacInstruction explicit = calls.get(1);
    assertThat(explicit.calleeSig)
        .as("both forms reach the same callee, so both share one summary")
        .isEqualTo("Demo.Ext.Fill/2");
    assertThat(explicit.args).hasSize(3);
    assertThat(explicit.args.get(0))
        .as("a static call's receiver slot holds the type that declares the method")
        .isInstanceOf(TacOperand.Const.class);
    assertThat(variableName(explicit.args.get(1)))
        .as("the receiver is passed as the first argument, so it lands on the first formal")
        .isEqualTo("text");
  }

  @Test
  @DisplayName("the written-back list of an extension call runs parallel to the argument list")
  void theWrittenBackListOfAnExtensionCallRunsParallelToTheArgumentList() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Fill");

    assertThat(call.knownRefOutDests())
        .as("the written-back list runs parallel to the argument list")
        .hasSize(call.args.size());
    assertThat(call.knownRefOutDests().get(0)).as("the receiver slot").isNull();
    assertThat(call.knownRefOutDests().get(1)).as("the receiver's own slot").isNull();
    assertThat(call.knownRefOutDests().get(2))
        .as("the out parameter's slot says which variable the callee writes into")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) call.knownRefOutDests().get(2)).name()).isEqualTo("result");
  }

  @Test
  @DisplayName("the control: a plain instance call keeps one receiver slot")
  void theControlAPlainInstanceCallKeepsOneReceiverSlot() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "EchoInstance");

    assertThat(call.args).as("one receiver slot and one per parameter").hasSize(2);
    assertThat(variableName(call.args.get(1)))
        .as("the only parameter's slot holds the argument passed for it")
        .isEqualTo("other");
  }

  @Test
  @DisplayName("the control: a plain static call keeps the declaring type in the receiver slot")
  void theControlAPlainStaticCallKeepsTheDeclaringTypeInTheReceiverSlot() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "FillStatic");

    assertThat(call.args).hasSize(3);
    assertThat(call.args.get(0))
        .as("a static call's receiver slot holds the type that declares the method")
        .isInstanceOf(TacOperand.Const.class);
    assertThat(variableName(call.args.get(1))).isEqualTo("text");
  }

  private static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    throw new AssertionError(
        "no function named '"
            + name
            + "' in "
            + functions.stream().map(TacFunction::name).toList());
  }

  /** The first call to the method whose name is given, in the {@code Run} function. */
  private static TacInstruction callNamed(List<TacFunction> functions, String methodName) {
    List<TacInstruction> calls = callsNamed(functions, methodName);
    if (calls.isEmpty()) {
      throw new AssertionError("no call to " + methodName);
    }
    return calls.get(0);
  }

  /** Every call to the method whose name is given, in the {@code Run} function. */
  private static List<TacInstruction> callsNamed(List<TacFunction> functions, String methodName) {
    TacFunction run = functionNamed(functions, "Run");
    return run.instructions().stream()
        .filter(
            i ->
                i.kind == TacInstruction.Kind.CALL
                    && i.calleeSig != null
                    && i.calleeSig.contains("." + methodName + "/"))
        .toList();
  }

  /**
   * The name of the variable the operand stands for, or its text form when it is not a variable.
   */
  private static String variableName(TacOperand operand) {
    return operand instanceof TacOperand.TacVar v ? v.name() : String.valueOf(operand);
  }
}
