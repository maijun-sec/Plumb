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
import org.plumb.tac.ir.TacType;

/**
 * The C# frontend: the type a THROW carries.
 *
 * <h2>Why the type matters</h2>
 *
 * <p>The engine matches a catch handler against the TYPE of the exception that is thrown: the
 * handler whose declared type is a supertype of the thrown type is the one that runs, and the
 * handlers after it are unreachable for that throw. Without the type every handler stays reachable
 * from every throw, so a handler that cannot receive the exception inherits its taint all the same.
 *
 * <p>The type travels on the instruction as its {@code ctorType}, which is the same field the
 * {@code CATCH_HANDLER} uses for the type it catches. The Java side reads it unconditionally, so
 * all this frontend has to do is fill it in.
 *
 * <h2>Why there is a test HERE and not in the sidecar</h2>
 *
 * <p>The other sidecars carry their own test harness. The C# sidecar does not: it is a .NET program
 * with no test project, and the Java build does not produce it. So the shape is pinned here, where
 * the real sidecar is run as a PROCESS and its wire is read back -- which is the only place a
 * reader can see that the type really travels.
 */
@DisplayName("C#: the type a THROW carries")
class CSharpThrowTypeLoweringTest {

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

  /** A source that throws a constructed exception, and a source that throws a plain value. */
  private static final String CONSTRUCTED =
      """
      namespace Demo;

      public class Sink
      {
          public void Run(string input)
          {
              throw new System.IO.IOException(input);
          }
      }
      """;

  private static final String PLAIN =
      """
      namespace Demo;

      public class Sink
      {
          public void Run(string input)
          {
              throw input;
          }
      }
      """;

  @Test
  @DisplayName("a throw of a constructed exception names the class it throws")
  void aThrowOfAConstructedExceptionNamesItsClass() {
    TacFunction function = functionNamed(support.parse(CONSTRUCTED).functions(), "Run");

    TacInstruction thrown = theThrow(function);

    assertThat(thrown.ctorType)
        .as("the throw must name the type it throws, so the engine can route the catch")
        .isNotNull();
    assertThat(thrown.ctorType.kind())
        .as("only a class can be matched against a catch handler")
        .isEqualTo(TacType.Kind.CLASS);
    assertThat(thrown.ctorType.className()).isEqualTo("System.IO.IOException");
  }

  @Test
  @DisplayName("a throw of a value that is not an exception names no type")
  void aThrowOfANonClassNamesNoType() {
    TacFunction function = functionNamed(support.parse(PLAIN).functions(), "Run");

    TacInstruction thrown = theThrow(function);

    assertThat(namesNoClass(thrown))
        .as(
            "a catch clause can only catch an exception, so the type of anything else says "
                + "nothing about which handler would catch it -- and C# models a string as the "
                + "class System.String, which is a class and not an exception")
        .isTrue();
  }

  /**
   * Whether the instruction names no class at all.
   *
   * <p>A wire that carries no type at all is read as UNKNOWN by the Java side, so "no type" and "an
   * unknown type" are the same thing for the engine: neither names a class, and a class is what a
   * catch handler is matched against.
   */
  private static boolean namesNoClass(TacInstruction instruction) {
    return instruction.ctorType == null || instruction.ctorType.kind() != TacType.Kind.CLASS;
  }

  /** The function's THROW, or a failure. */
  private static TacInstruction theThrow(TacFunction function) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.THROW) {
        return instruction;
      }
    }
    throw new AssertionError("the function must contain a THROW");
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
