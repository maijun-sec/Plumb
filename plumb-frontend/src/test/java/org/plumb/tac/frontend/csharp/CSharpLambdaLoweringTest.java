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
 * A C# lambda is a function of its own, and the variables it captures are its LEADING PARAMETERS.
 *
 * <p><b>Why this is pinned.</b> The TAC has no nested function and no function value, so a lambda
 * cannot be a value in an instruction stream. Before this the lambda's WHOLE BODY was invisible:
 * the finding inside a handler that the program registers was absent, and the scan reported
 * success.
 *
 * <p><b>Why the capture is a PARAMETER and not something else.</b> A C# lambda reads an enclosing
 * variable as it is when the lambda RUNS. The TAC has no reference, so the value is passed at the
 * call site -- and the engine binds a callee's formal {@code i} to the call site's {@code
 * args[i+1]}. The layout is therefore fixed: the receiver occupies {@code args[0]}, the captured
 * variables follow, and the arguments the source wrote come last. That is what these tests measure.
 *
 * <p><b>Why every test parses ONCE.</b> The closure's name carries a project-wide counter, so two
 * parses of the same source produce two sets of names. A test that parsed twice and compared the
 * two sets would be comparing a closure with itself.
 */
@DisplayName("C#: a lambda is a function of its own, and its captures are leading parameters")
class CSharpLambdaLoweringTest {

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

  /**
   * The fixture.
   *
   * <p>It holds the four shapes a lambda takes in real code: a lambda with its own parameter, a
   * lambda that CAPTURES an enclosing variable, a lambda that captures the enclosing INSTANCE, and
   * an explicitly typed lambda.
   */
  private static final String SOURCE =
      """
      using System;

      namespace Demo;

      public class Runner
      {
          private string secret;

          public void Run(string userInput)
          {
              string captured = userInput;

              Action<string> handler = value => Sink(value);
              handler(userInput);

              Action capturing = () => Sink(captured);
              capturing();

              Func<string, string> withThis = s => this.secret + s;
              Func<string, string> explicitTyped = (string s) => s.ToUpper();

              var anon = new { Name = userInput, Count = 1 };
              System.Console.WriteLine(anon.Name);
          }

          private static void Sink(string value)
          {
          }
      }
      """;

  @Test
  @DisplayName("a lambda becomes a function of its own")
  void aLambdaBecomesAFunctionOfItsOwn() {
    List<TacFunction> functions = support.parse(SOURCE).functions();

    List<TacFunction> closures =
        functions.stream().filter(f -> f.name().contains(".lambda")).toList();

    assertThat(closures).as("every lambda in the fixture must be a function of its own").hasSize(4);
    for (TacFunction closure : closures) {
      assertThat(closure.signature())
          .as("the closure's signature must carry its name and its arity")
          .contains(closure.name());
      assertThat(closure.enclosingClass())
          .as("the closure belongs to the type that encloses the member")
          .isEqualTo("Runner");
    }
  }

  @Test
  @DisplayName("the captured variable is the closure's LEADING parameter")
  void theCapturedVariableIsTheLeadingParameter() {
    List<TacFunction> functions = support.parse(SOURCE).functions();

    TacFunction closure = closureCapturing(functions, "captured");

    assertThat(closure.params()).as("the closure takes the capture and nothing else").hasSize(1);
    assertThat(closure.params().get(0).name())
        .as("the capture is the closure's first parameter")
        .isEqualTo("captured");
  }

  @Test
  @DisplayName("the call site reaches the closure and passes the capture")
  void theCallSiteReachesTheClosureAndPassesTheCapture() {
    List<TacFunction> functions = support.parse(SOURCE).functions();
    TacFunction run = functionNamed(functions, "Run");
    TacFunction closure = closureCapturing(functions, "captured");

    TacInstruction call = callTo(run, closure.name());

    assertThat(call.args)
        .as("the receiver slot, then the capture, then the arguments the source wrote")
        .hasSize(2);
    assertThat(call.args.get(0))
        .as("the receiver slot holds the closure's own value")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) call.args.get(1)).name())
        .as("the capture is passed at the slot the formal binds to")
        .isEqualTo("captured");
  }

  @Test
  @DisplayName("the enclosing INSTANCE is a capture too")
  void theEnclosingInstanceIsACaptureToo() {
    // A lambda that mentions `this` holds the instance. Without it the
    // field read inside the lambda has no receiver, and the value it reaches
    // has no taint.
    List<TacFunction> functions = support.parse(SOURCE).functions();

    TacFunction closure = closureCapturingTheInstance(functions);

    assertThat(closure.params().get(0).name())
        .as("the instance is the closure's first capture")
        .isEqualTo("Runner");
    assertThat(closure.params().get(1).name())
        .as("the lambda's own parameter comes after the captures")
        .isEqualTo("s");
  }

  @Test
  @DisplayName("a field read inside a lambda keeps its receiver")
  void aFieldReadInsideALambdaKeepsItsReceiver() {
    // The receiver used to be resolved with the enclosing symbol at POSITION
    // ZERO -- the start of the FILE -- so it was always empty, and the field
    // read became a HAVOC: the value had no origin, and nothing downstream
    // could be tainted by it.
    List<TacFunction> functions = support.parse(SOURCE).functions();

    TacFunction closure = closureCapturingTheInstance(functions);

    TacInstruction load =
        closure.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.FIELDLOAD)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the field read was not lowered"));

    assertThat(load.lhs1).as("the field read must name the captured instance").isNotNull();
    assertThat(((TacOperand.TacVar) load.lhs1).name()).isEqualTo("Runner");
  }

  @Test
  @DisplayName("an anonymous object is an allocation whose arguments are the members")
  void anAnonymousObjectIsLowered() {
    List<TacFunction> functions = support.parse(SOURCE).functions();
    TacFunction run = functionNamed(functions, "Run");

    TacInstruction allocation =
        run.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.NEW)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the anonymous object was not lowered"));

    assertThat(allocation.ctorType).as("the allocation carries the anonymous type").isNotNull();
    assertThat(allocation.args).as("the members are the constructor's arguments").hasSize(2);
    assertThat(((TacOperand.TacVar) allocation.args.get(0)).name())
        .as("the first member holds the tainted value")
        .isEqualTo("userInput");
  }

  @Test
  @DisplayName("nothing in the fixture was left unlowered")
  void nothingWasLeftUnlowered() {
    // The frontend's problem list carries the COMPILER's diagnostics too --
    // a warning about an unread field is one of them, and it is not a
    // lowering failure. The lowering failures are the UNHANDLED_NODE ones:
    // they say "the frontend did not understand this part".
    List<String> unlowered =
        support.parseStructured(SOURCE).problems().stream()
            .filter(p -> "UNHANDLED_NODE".equals(p.kind()))
            .map(p -> p.functionName() + ": " + p.message())
            .toList();

    assertThat(unlowered).as("a lambda and an anonymous object are both lowered now").isEmpty();
  }

  @Test
  @DisplayName("a call through a variable that holds a lambda reaches the closure")
  void aCallThroughAClosureVariableReachesTheClosure() {
    // `handler(userInput)` is the commonest shape of all: the lambda is
    // stored in a local, and the local is called later. The registration of
    // the local is what makes the call reach the closure's body instead of
    // the framework's delegate Invoke.
    List<TacFunction> functions = support.parse(SOURCE).functions();
    TacFunction run = functionNamed(functions, "Run");

    TacInstruction call = callCarrying(run, "userInput");

    assertThat(call.args).as("the receiver slot, then the argument the source wrote").hasSize(2);
    assertThat(((TacOperand.TacVar) call.args.get(1)).name()).isEqualTo("userInput");
  }

  @Test
  @DisplayName("a null-conditional call of a closure reaches the closure")
  void aNullConditionalCallOfAClosureReachesTheClosure() {
    // `handler?.Invoke()` is the same call, guarded. The guard is the
    // conditional access's own business; the call itself is the closure's.
    String source =
        """
        using System;

        namespace Demo;

        public class Runner
        {
            public void Run(string userInput)
            {
                Action<string> handler = value => Sink(value);
                handler?.Invoke(userInput);
            }

            private static void Sink(string value)
            {
            }
        }
        """;

    TacFunction run = functionNamed(support.parse(source).functions(), "Run");

    TacInstruction call = callCarrying(run, "userInput");

    assertThat(((TacOperand.TacVar) call.args.get(1)).name()).isEqualTo("userInput");
  }

  /** The closure that captures the named variable, and nothing else. */
  private static TacFunction closureCapturing(List<TacFunction> functions, String captureName) {
    return functions.stream()
        .filter(f -> f.name().contains(".lambda"))
        .filter(f -> f.params().size() == 1 && f.params().get(0).name().equals(captureName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no closure captures '" + captureName + "'"));
  }

  /** The closure that captures the enclosing instance. */
  private static TacFunction closureCapturingTheInstance(List<TacFunction> functions) {
    return functions.stream()
        .filter(f -> f.name().contains(".lambda"))
        .filter(f -> f.params().size() == 2 && f.params().get(0).name().equals("Runner"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no closure captures the instance"));
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

  private static TacInstruction callTo(TacFunction function, String calleeName) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains(calleeName)) {
        return instruction;
      }
    }
    throw new AssertionError("no call to '" + calleeName + "'");
  }

  /**
   * The call that carries a value into a closure.
   *
   * @param function the calling function
   * @param value the variable the call carries
   * @return the instruction
   */
  private static TacInstruction callCarrying(TacFunction function, String value) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.CALL) {
        continue;
      }
      if (instruction.calleeSig == null || !instruction.calleeSig.contains(".lambda")) {
        continue;
      }
      for (TacOperand argument : instruction.args) {
        if (argument instanceof TacOperand.TacVar v && value.equals(v.name())) {
          return instruction;
        }
      }
    }
    throw new AssertionError("no closure call carries '" + value + "'");
  }
}
