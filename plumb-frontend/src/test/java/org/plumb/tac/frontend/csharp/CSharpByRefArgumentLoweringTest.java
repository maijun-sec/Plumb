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
 * The lowering of a BY-REFERENCE argument, which is written by the callee and read by the caller.
 *
 * <p><b>The question.</b> C#'s {@code out} and {@code ref} arguments are not values the caller
 * passes: they are variables the CALLEE writes. The TAC's CALL has ONE destination, and that
 * destination is the call's RESULT. So the variable the callee wrote had nowhere to go, and the
 * engine never learned that the caller's variable was written at all.
 *
 * <p><b>Why the list is parallel to the argument list.</b> The engine matches the callee's summary
 * (which says what the callee wrote into each FORMAL) against the call site's list (which says
 * which ARGUMENT is written back, and into which variable) BY INDEX. A list that was not parallel
 * to the arguments would point the engine at the wrong argument, and a by-reference argument at
 * position 1 would be read as one at position 0.
 *
 * <p><b>Why {@code in} is not in the list.</b> An {@code in} argument is read-only by contract: the
 * callee may not write to it. Treating it as written back would taint the caller's variable from a
 * callee that cannot have changed it.
 *
 * <p><b>Why the receiver's slot is null.</b> The TAC's call convention puts the receiver in the
 * first slot of the argument list, and the by-reference list runs parallel to the WHOLE list, so
 * the receiver's slot has an entry too -- and that entry says "not by reference".
 */
@DisplayName("C#: the TAC lowering of a by-reference argument")
class CSharpByRefArgumentLoweringTest {

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

  /** A source with an {@code out}, a {@code ref} and an {@code in} argument. */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Producer
      {
          public static bool Copy(string input, out string value)
          {
              value = input;
              return true;
          }

          public static void Bump(ref string value)
          {
          }

          public static void Read(in string value)
          {
          }
      }

      public class Caller
      {
          public void Run()
          {
              var payload = "payload";
              string result;
              Producer.Copy(payload, out result);
              Producer.Bump(ref result);
              Producer.Read(in result);
          }
      }
      """;

  @Test
  @DisplayName("an out argument is recorded as written back into the caller's variable")
  void anOutArgumentIsRecordedAsWrittenBack() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Copy");

    assertThat(call.knownRefOutDests())
        .as("the out argument has to be recorded, or the value the callee writes into it is lost")
        .hasSize(call.args.size());
    int index = argumentIndexOf(call, "result");
    assertThat(index).as("the caller's variable is one of the arguments").isGreaterThanOrEqualTo(0);
    assertThat(call.knownRefOutDests().get(index))
        .as("the by-reference list names the variable the callee writes into")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) call.knownRefOutDests().get(index)).name()).isEqualTo("result");
  }

  @Test
  @DisplayName("a ref argument is recorded as written back into the caller's variable")
  void aRefArgumentIsRecordedAsWrittenBack() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Bump");

    assertThat(call.knownRefOutDests()).hasSize(call.args.size());
    int index = argumentIndexOf(call, "result");
    assertThat(call.knownRefOutDests().get(index)).isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) call.knownRefOutDests().get(index)).name()).isEqualTo("result");
  }

  @Test
  @DisplayName("an in argument is not recorded as written back")
  void anInArgumentIsNotRecordedAsWrittenBack() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Read");

    assertThat(call.knownRefOutDests())
        .as(
            "an in argument is read-only by contract: the callee may not write to it, so recording"
                + " it as written back would taint the caller's variable from a callee that cannot"
                + " have changed it")
        .isEmpty();
  }

  @Test
  @DisplayName("the receiver's slot is not by reference")
  void theReceiversSlotIsNotByReference() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Copy");

    assertThat(call.knownRefOutDests().get(0))
        .as("args[0] is the receiver, and a receiver is never written back")
        .isNull();
  }

  @Test
  @DisplayName("an argument that is not by reference has a null in its slot")
  void anArgumentThatIsNotByReferenceHasANullInItsSlot() {
    TacInstruction call = callNamed(support.parse(SOURCE).functions(), "Copy");

    int index = argumentIndexOf(call, "payload");
    assertThat(index).as("the by-value argument is one of the arguments").isGreaterThanOrEqualTo(0);
    assertThat(call.knownRefOutDests().get(index))
        .as("a by-value argument is not written back")
        .isNull();
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

  /** The index of the argument that names the given variable, or -1. */
  private static int argumentIndexOf(TacInstruction call, String variableName) {
    for (int i = 0; i < call.args.size(); i++) {
      if (call.args.get(i) instanceof TacOperand.TacVar v && variableName.equals(v.name())) {
        return i;
      }
    }
    return -1;
  }
}
