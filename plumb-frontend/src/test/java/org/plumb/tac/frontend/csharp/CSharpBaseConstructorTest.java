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
import org.plumb.tac.ir.TacMemberKind;
import org.plumb.tac.ir.TacOperand;

/**
 * The TAC lowering of a CONSTRUCTOR INITIALIZER: the <code>: base(x)</code> and the <code>: this(x)
 * </code> that name the constructor a constructor delegates to.
 *
 * <p><b>The question.</b> In Roslyn's syntax tree the initializer is a SIBLING of the
 * constructor's body, not a statement inside it. A frontend that walks the body never visits it, so
 * the base constructor's body is not part of the derived constructor's flow: the base constructor
 * appears in the TAC as a function that nothing calls, and the constructor chain is invisible to
 * the call graph.
 *
 * <p><b>Why that is a FIDELITY gap and not a lost finding.</b> The engine analyses constructors as
 * entry points, so a sink inside the base constructor is still reported. What is missing is the
 * CALL -- and a call is what the call graph is made of.
 *
 * <p><b>Why the shape is the shape of every other call.</b> The TAC's convention is "args[0] is the
 * receiver, args[1..] are the actual arguments", and the engine reads every argument index against
 * it: the sink catalogue's <code>arg_index</code> names a USER argument, and the engine adds the
 * receiver offset itself. The receiver of a base constructor call is <code>this</code>: the base
 * constructor runs on the very object the derived constructor is building. The JVM bytecode front
 * end lowers the implicit <code>super()</code> the same way.
 *
 * <p><b>Why the order matters.</b> The base constructor has already run by the time the derived
 * constructor's first statement executes, so the call comes before the body.
 */
@DisplayName("C#: the TAC lowering of a constructor initializer")
class CSharpBaseConstructorTest {

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

  /** A source with a base class, a derived class, and a constructor that chains to the base. */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Base
      {
          public Base(int x)
          {
              this.x = x;
          }

          public int x;
      }

      public class Derived : Base
      {
          public Derived(int x) : base(x)
          {
              this.y = x;
          }

          public int y;
      }
      """;

  /** A source whose constructor chains to ANOTHER constructor of the same class. */
  private static final String CHAINING_SOURCE =
      """
      namespace Demo;

      public class Chain
      {
          public Chain(int x) : this(x, 0)
          {
          }

          public Chain(int x, int y)
          {
              this.sum = x + y;
          }

          public int sum;
      }
      """;

  @Test
  @DisplayName("a base constructor initializer becomes a CALL to the base constructor")
  void aBaseConstructorInitializerBecomesACall() {
    TacFunction constructor = constructorOf(support.parse(SOURCE).functions(), "Derived", 1);

    TacInstruction call = callTo(constructor, "Demo.Base.<init>/1");

    assertThat(call)
        .as(
            "the initializer must lower to a CALL to the base constructor: the initializer is a "
                + "sibling of the constructor's body, so a frontend that walks only the body "
                + "leaves the base constructor as a function that nothing calls")
        .isNotNull();
  }

  @Test
  @DisplayName("the base constructor call carries the receiver, then the initializer's arguments")
  void theBaseConstructorCallCarriesTheReceiverThenTheArguments() {
    TacFunction constructor = constructorOf(support.parse(SOURCE).functions(), "Derived", 1);

    TacInstruction call = callTo(constructor, "Demo.Base.<init>/1");

    assertThat(call).isNotNull();
    assertThat(call.args)
        .as("the receiver, then the one argument the initializer passes")
        .hasSize(2);
    assertThat(((TacOperand.TacVar) call.args.get(0)).name())
        .as(
            "args[0] is the RECEIVER, by the TAC's call convention: the base constructor runs on "
                + "the object the derived constructor is building")
        .isEqualTo("Derived");
    assertThat(((TacOperand.TacVar) call.args.get(1)).name())
        .as("the initializer's argument is the first USER argument, which is what the catalogue names")
        .isEqualTo("x");
  }

  @Test
  @DisplayName("the base constructor call comes before the constructor's body")
  void theBaseConstructorCallComesBeforeTheBody() {
    TacFunction constructor = constructorOf(support.parse(SOURCE).functions(), "Derived", 1);

    TacInstruction call = callTo(constructor, "Demo.Base.<init>/1");
    TacInstruction body = null;
    for (TacInstruction instruction : constructor.instructions()) {
      if (instruction.kind == TacInstruction.Kind.FIELDSTORE) {
        body = instruction;
      }
    }

    assertThat(call).isNotNull();
    assertThat(body).as("the constructor's body must lower to something").isNotNull();
    assertThat(constructor.instructions().indexOf(call))
        .as(
            "the base constructor has already run by the time the derived constructor's first "
                + "statement executes, so the call comes first")
        .isLessThan(constructor.instructions().indexOf(body));
  }

  @Test
  @DisplayName("the base constructor call has no destination, because a constructor returns nothing")
  void theBaseConstructorCallHasNoDestination() {
    TacFunction constructor = constructorOf(support.parse(SOURCE).functions(), "Derived", 1);

    TacInstruction call = callTo(constructor, "Demo.Base.<init>/1");

    assertThat(call).isNotNull();
    assertThat(call.dest).as("a constructor produces no value").isNull();
  }

  @Test
  @DisplayName("a constructor without an initializer calls nothing")
  void aConstructorWithoutAnInitializerCallsNothing() {
    TacFunction constructor = constructorOf(support.parse(SOURCE).functions(), "Base", 1);

    assertThat(callsOf(constructor))
        .as(
            "the base constructor has no initializer, so it has no call to make: the compiler's "
                + "implicit call is not in the source, and the sidecar reports what the source says")
        .isEmpty();
  }

  @Test
  @DisplayName("a this initializer calls another constructor of the same class")
  void aThisInitializerCallsAnotherConstructorOfTheSameClass() {
    TacFunction constructor = constructorOf(support.parse(CHAINING_SOURCE).functions(), "Chain", 1);

    // The constructor with ONE parameter is the one that chains.
    TacInstruction call = null;
    for (TacInstruction instruction : constructor.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL && instruction.calleeSig != null) {
        call = instruction;
      }
    }

    assertThat(call).as("the this initializer must lower to a CALL").isNotNull();
    assertThat(call.calleeSig)
        .as("the callee key must name the class and the constructor it delegates to")
        .isEqualTo("Demo.Chain.<init>/2");
    assertThat(call.args).as("the receiver, then the initializer's two arguments").hasSize(3);
    assertThat(((TacOperand.TacVar) call.args.get(0)).name())
        .as("args[0] is the receiver, by the TAC's call convention")
        .isEqualTo("Chain");
    assertThat(((TacOperand.TacVar) call.args.get(1)).name())
        .as("the initializer's arguments keep their order")
        .isEqualTo("x");
  }

  /**
   * The constructor of a class.
   *
   * @param functions the functions of the program
   * @param enclosingClass the simple name of the class that declares the constructor
   * @param parameterCount the number of parameters the constructor takes
   * @return the constructor
   */
  private static TacFunction constructorOf(
      List<TacFunction> functions, String enclosingClass, int parameterCount) {
    for (TacFunction function : functions) {
      if (enclosingClass.equals(function.enclosingClass())
          && function.memberKind() == TacMemberKind.CONSTRUCTOR
          && function.params().size() == parameterCount) {
        return function;
      }
    }
    throw new AssertionError(
        "no constructor of '"
            + enclosingClass
            + "' with "
            + parameterCount
            + " parameters in "
            + functions.stream().map(f -> f.enclosingClass() + "." + f.name()).toList());
  }

  /**
   * The call to a callee, or null when the function makes none.
   *
   * @param function the function
   * @param calleeSig the callee key
   * @return the call, or null
   */
  private static TacInstruction callTo(TacFunction function, String calleeSig) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL && calleeSig.equals(instruction.calleeSig)) {
        return instruction;
      }
    }
    return null;
  }

  /**
   * Every call the function makes.
   *
   * @param function the function
   * @return the calls
   */
  private static List<TacInstruction> callsOf(TacFunction function) {
    return function.instructions().stream()
        .filter(i -> i.kind == TacInstruction.Kind.CALL)
        .toList();
  }
}
