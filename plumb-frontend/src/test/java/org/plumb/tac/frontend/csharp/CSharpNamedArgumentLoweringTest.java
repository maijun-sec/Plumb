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
 * The lowering of a call whose arguments are NOT written in parameter order.
 *
 * <p><b>The question.</b> A C# call may NAME its arguments, and it may leave an optional one out.
 * Both change which argument belongs to which parameter. The engine's convention is "args[0] is the
 * receiver, args[1..] are the arguments, one per parameter, in parameter order", and the callee
 * key's arity is the PARAMETER COUNT. So an argument list in source order is read as if it were in
 * parameter order, and a named argument lands on the wrong formal.
 *
 * <p><b>Why that costs a finding.</b> The engine binds formal {@code i} to {@code args[i+1]} by
 * position, so a callee that writes a tainted value into its second parameter writes it into the
 * caller's variable at the wrong slot -- a variable the sink never reads. The value is analysed and
 * then lands nowhere.
 *
 * <p><b>Why the by-reference list is part of this.</b> The list of written-back arguments runs
 * parallel to the argument list, and the engine matches the callee's summary against it by index. A
 * list that is parallel to the SOURCE order is parallel to the wrong thing, so the write-back is
 * recorded against the wrong formal and the wrong variable.
 *
 * <p><b>Why the positional case is in the same file.</b> A fix that reshapes the argument list
 * would break the ordinary call if it moved a slot. So the positional call is here as the control:
 * every slot still holds the argument that was already there.
 */
@DisplayName("C#: the TAC lowering of a call whose arguments are not in parameter order")
class CSharpNamedArgumentLoweringTest {

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

  /** A source with a named-argument call, a by-reference one, a default and a {@code params}. */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Producer
      {
          public static string Echo(string first, string second)
          {
              return second;
          }

          public static bool Copy(string input, out string value)
          {
              value = input;
              return true;
          }

          public static string WithDefault(string required, string optional = "fallback")
          {
              return required + optional;
          }

          public static string JoinAll(params string[] parts)
          {
              return parts.Length.ToString();
          }
      }

      public class Caller
      {
          public void Run()
          {
              var first = "first";
              var second = "second";
              var result = "result";
              var one = "one";
              var two = "two";
              var three = "three";
              Producer.Echo(second: second, first: first);
              Producer.Copy(value: out result, input: first);
              Producer.WithDefault("required");
              Producer.JoinAll(one, two, three);
          }
      }
      """;

  /** The control: the same call written with the arguments in parameter order. */
  private static final String POSITIONAL_SOURCE =
      """
      namespace Demo;

      public class Producer
      {
          public static string Echo(string first, string second)
          {
              return second;
          }
      }

      public class Caller
      {
          public void Run()
          {
              var first = "first";
              var second = "second";
              Producer.Echo(first, second);
          }
      }
      """;

  @Test
  @DisplayName("a named argument lands on the parameter it names")
  void aNamedArgumentLandsOnTheParameterItNames() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Echo");

    assertThat(call.args).as("one slot for the receiver and one per parameter").hasSize(3);
    assertThat(variableName(call.args.get(1)))
        .as("the first parameter's slot holds the argument named for the first parameter")
        .isEqualTo("first");
    assertThat(variableName(call.args.get(2)))
        .as("the second parameter's slot holds the argument named for the second parameter")
        .isEqualTo("second");
  }

  @Test
  @DisplayName("a positional argument keeps its slot")
  void aPositionalArgumentKeepsItsSlot() {
    TacInstruction call = callNamed(support.parse(POSITIONAL_SOURCE).functions(), "Echo");

    assertThat(call.args).hasSize(3);
    assertThat(variableName(call.args.get(1))).isEqualTo("first");
    assertThat(variableName(call.args.get(2))).isEqualTo("second");
  }

  @Test
  @DisplayName("a named by-reference argument is written back at its parameter's slot")
  void aNamedByReferenceArgumentIsWrittenBackAtItsParametersSlot() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Copy");

    assertThat(call.knownRefOutDests())
        .as("the written-back list runs parallel to the argument list")
        .hasSize(call.args.size());
    assertThat(call.knownRefOutDests().get(1))
        .as("the first parameter is passed by value")
        .isNull();
    assertThat(call.knownRefOutDests().get(2))
        .as(
            "the second parameter is the out one, so its slot says which variable the callee"
                + " writes into")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) call.knownRefOutDests().get(2)).name()).isEqualTo("result");
  }

  @Test
  @DisplayName("an omitted optional parameter holds its default value")
  void anOmittedOptionalParameterHoldsItsDefaultValue() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "WithDefault");

    assertThat(call.args)
        .as("the parameter nobody passed still has a slot, or the parameters after it shift")
        .hasSize(3);
    assertThat(call.args.get(2)).isInstanceOf(TacOperand.Const.class);
    TacOperand.Const value = (TacOperand.Const) call.args.get(2);
    assertThat(value.kind()).isEqualTo(TacOperand.Const.Kind.STRING);
    assertThat(value.value())
        .as("the default of an optional parameter is a compile-time constant")
        .isEqualTo("fallback");
  }

  @Test
  @DisplayName("the params parameter takes the arguments nobody else took")
  void theParamsParameterTakesTheArgumentsNobodyElseTook() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "JoinAll");

    assertThat(call.args)
        .as(
            "the IR has no array shape yet, so the first extra argument takes the params slot and"
                + " the rest follow it")
        .hasSize(4);
    assertThat(variableName(call.args.get(1))).isEqualTo("one");
    assertThat(variableName(call.args.get(2))).isEqualTo("two");
    assertThat(variableName(call.args.get(3))).isEqualTo("three");
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

  /** The call to the method whose name is given, in the {@code Run} function. */
  private static TacInstruction callNamed(List<TacFunction> functions, String methodName) {
    TacFunction run = functionNamed(functions, "Run");
    for (TacInstruction instruction : run.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("." + methodName + "/")) {
        return instruction;
      }
    }
    throw new AssertionError(
        "no call to "
            + methodName
            + " in "
            + run.instructions().stream().map(i -> String.valueOf(i.calleeSig)).toList());
  }

  /**
   * The name of the variable the operand stands for, or its text form when it is not a variable.
   */
  private static String variableName(TacOperand operand) {
    return operand instanceof TacOperand.TacVar v ? v.name() : String.valueOf(operand);
  }
}
