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
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Phase 0: the measurement that decides how the C# frontend is built.
 *
 * <p><b>The question.</b> Can a C# property assignment be lowered into the TAC in a way that the
 * sink catalogue can match, without a second, property-aware matching mechanism?
 *
 * <p><b>Why it is the FIRST thing to measure.</b> The answer decides the shape of everything
 * downstream. If a property assignment could NOT become a {@code CALL} whose callee signature the
 * catalogue matches, the frontend would need a second matching path -- one that knows about
 * properties, one that every checker and every rule would have to learn about, and one whose
 * behaviour would differ from the JVM languages'. That is a large and permanent cost. If it CAN,
 * the C# frontend is an ordinary frontend: it produces the same TAC shapes the other languages
 * produce, and everything downstream works unchanged.
 *
 * <p><b>What the measurement shows.</b> A C# property assignment becomes a {@code CALL} to {@code
 * owner.set_X/arity}, with the assigned value as argument 0. That is the shape the catalogue
 * matches, so no second mechanism is needed.
 *
 * <p>The tests below are the measurement, written so that a change in the lowering breaks them.
 */
@DisplayName("C#: the TAC lowering of a property assignment")
class CSharpPropertyLoweringTest {

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

  /** A source with a property, a field, and an assignment to each. */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Sink
      {
          public void Run(string input)
          {
              var cmd = new Command();
              cmd.CommandText = input;
              cmd.Field = input;
          }
      }

      public class Command
      {
          public string CommandText { get; set; }

          public int Field;
      }
      """;

  @Test
  @DisplayName("a property assignment becomes a CALL to the setter, not a FIELDSTORE")
  void propertyAssignmentBecomesACall() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    TacInstruction setterCall = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("set_CommandText")) {
        setterCall = instruction;
      }
    }

    assertThat(setterCall)
        .as("the property assignment must lower to a CALL to the setter")
        .isNotNull();
    assertThat(setterCall.calleeSig)
        .as("the callee signature must be the canonical owner.method/arity form")
        .isEqualTo("Demo.Command.set_CommandText/1");
    assertThat(setterCall.args)
        .as("the call must carry the receiver and the assigned value")
        .hasSize(2);
    assertThat(((TacOperand.TacVar) setterCall.args.get(0)).name())
        .as("args[0] is the RECEIVER, by the TAC's call convention")
        .isEqualTo("cmd");
    assertThat(((TacOperand.TacVar) setterCall.args.get(1)).name())
        .as(
            "the assigned value is the first USER argument, which is what the catalogue's arg_index"
                + " 0 names")
        .isEqualTo("input");
  }

  @Test
  @DisplayName("a field assignment stays a FIELDSTORE")
  void fieldAssignmentStaysAFieldStore() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    TacInstruction fieldStore = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.FIELDSTORE
          && "Field".equals(instruction.fieldName)) {
        fieldStore = instruction;
      }
    }

    assertThat(fieldStore)
        .as("a field assignment must stay a FIELDSTORE: a field really is a field")
        .isNotNull();
    assertThat(fieldStore.calleeSig)
        .as("a FIELDSTORE carries no callee signature, which is why a property cannot be one")
        .isNull();
  }

  @Test
  @DisplayName(
      "the property and the field are distinguished by the resolved symbol, not the spelling")
  void propertyAndFieldAreDistinguishedBySymbol() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    // Both assignments are to a member named after a property or a field, and
    // the shapes differ. The only thing that can decide this is the resolved
    // symbol: a property has a setter, a field does not.
    long calls =
        function.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.CALL)
            .filter(i -> i.calleeSig != null && i.calleeSig.contains("CommandText"))
            .count();
    long fieldStores =
        function.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.FIELDSTORE)
            .filter(i -> "Field".equals(i.fieldName))
            .count();

    assertThat(calls).as("the property assignment is a CALL").isEqualTo(1);
    assertThat(fieldStores).as("the field assignment is a FIELDSTORE").isEqualTo(1);
  }

  @Test
  @DisplayName("the function is tagged as C#, at full precision")
  void theFunctionIsTaggedAsCSharp() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    assertThat(function.sourceLanguage()).isEqualTo(SourceLanguage.CSHARP);
    assertThat(function.irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("the assigned value is the first USER argument, which is what the catalogue names")
  void theSettersArgumentIndexIsZero() {
    // The catalogue's contract: "which argument carries the untrusted value",
    // counted over the USER arguments -- the receiver is not one of them.
    //
    // The TAC's own convention puts the receiver at args[0], so the user
    // argument index 0 lands at args[1]. The engine applies that offset itself,
    // so the catalogue stays written in user-argument terms.
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    TacInstruction setterCall = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.calleeSig != null && instruction.calleeSig.contains("set_CommandText")) {
        setterCall = instruction;
      }
    }

    assertThat(setterCall).isNotNull();
    assertThat(setterCall.args).as("the receiver plus the assigned value").hasSize(2);
    assertThat(((TacOperand.TacVar) setterCall.args.get(1)).name())
        .as("the user argument 0 is the assigned value")
        .isEqualTo("input");
  }

  @Test
  @DisplayName("the parameter is the variable that reaches the setter")
  void theParameterReachesTheSetter() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    assertThat(function.params()).extracting(TacOperand.TacVar::name).contains("input");
  }

  @Test
  @DisplayName("the null-forgiving operator is NOT a NULL_CHECK")
  void theNullForgivingOperatorIsNotANullCheck() {
    // `x!` tells the COMPILER that the value is not null. It inserts NO runtime
    // check -- there is no NullReferenceException to catch, because the check
    // does not exist at run time. Emitting a NULL_CHECK would claim a check that
    // does not exist, and the null-dereference checker would then suppress a
    // finding that is entirely real.
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run(string? input)
            {
                var value = input!;
                Console.WriteLine(value);
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    boolean hasNullCheck = false;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.NULL_CHECK) {
        hasNullCheck = true;
      }
    }
    assertThat(hasNullCheck)
        .as("`x!` inserts no runtime check, so it must not lower to a NULL_CHECK")
        .isFalse();
  }

  @Test
  @DisplayName("await lowers the value and downgrades the precision")
  void awaitDowngradesThePrecision() {
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public async System.Threading.Tasks.Task RunAsync(string input)
            {
                var value = await System.Threading.Tasks.Task.FromResult(input);
                Console.WriteLine(value);
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "RunAsync");

    assertThat(function.irPrecision())
        .as("an async state machine is lowered away, so the precision says so")
        .isEqualTo(IrPrecision.SOURCE_LOWERED);
  }

  @Test
  @DisplayName("a catch clause lowers to a CATCH_HANDLER carrying the caught type")
  void catchBecomesACatchHandler() {
    String source =
        """
        using System;
        using System.IO;

        namespace Demo;

        public class Sink
        {
            public void Run(string input)
            {
                try
                {
                    Console.WriteLine(input);
                }
                catch (IOException ex)
                {
                    Console.WriteLine(ex.Message);
                }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    TacInstruction handler = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CATCH_HANDLER) {
        handler = instruction;
      }
    }

    assertThat(handler).as("a catch clause must lower to a CATCH_HANDLER").isNotNull();
    assertThat(handler.ctorType)
        .as("the handler must carry the DECLARED exception type, so the engine can route")
        .isNotNull();
    assertThat(handler.ctorType.kind()).isEqualTo(org.plumb.tac.ir.TacType.Kind.CLASS);
    assertThat(handler.ctorType.className()).isEqualTo("System.IO.IOException");
  }

  @Test
  @DisplayName("a source that does not compile is reported, not silently ignored")
  void aBrokenSourceIsReported() {
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run()
            {
                this is not C#;
            }
        }
        """;

    TacFrontend.ParseResult result = support.parse(source);

    assertThat(result.problems())
        .as("a source that does not compile must be reported as a problem")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a `new` carries the callee signature a constructor sink needs")
  void aNewCarriesTheCalleeSignature() {
    // A constructor is a NEW instruction, and the engine's constructor-sink
    // lookup returns immediately when the instruction carries no callee
    // signature. So a NEW without one makes EVERY constructor sink
    // unreachable -- the catalogue can carry the pattern, the code can be
    // right, and the finding is simply absent while the scan reports
    // success.
    //
    // The Kotlin frontend sets both `ctorType` and `calleeSig` on a NEW, and
    // the C# frontend must do the same.
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run(string input)
            {
                var cmd = new System.Data.Common.DbCommand();
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    TacInstruction newInstruction = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.NEW) {
        newInstruction = instruction;
      }
    }

    assertThat(newInstruction).as("the source must lower to a NEW instruction").isNotNull();
    assertThat(newInstruction.ctorType).as("the NEW must carry the constructed type").isNotNull();
    assertThat(newInstruction.calleeSig)
        .as(
            "the NEW must carry the callee signature: without it the engine's constructor-sink "
                + "lookup returns immediately and every constructor sink is unreachable")
        .isNotNull();
    assertThat(newInstruction.calleeSig)
        .as("the constructor's callee key must use the <init> convention every frontend uses")
        .contains(".<init>/");
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
}
