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
package org.plumb.tac.frontend.python;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The class body, as the side car lowers it.
 *
 * <p><b>Why a class body is a block and not a list of members.</b> Python's grammar lets a class
 * body hold the statements the language has -- an {@code if}, a {@code for}, a {@code with}, a
 * {@code try}, a {@code match}. The side car used to know only the members (a method, a nested
 * class, an attribute) and reported every other statement as something it did not understand, so
 * the body of such a statement was not in the TAC at all: a sink inside it was not a finding the
 * report could not show -- it was never produced.
 *
 * <p><b>Why a name written in a class body is an attribute of the class.</b> A class body is a
 * scope of its own, and a name written in it belongs to the class. So the write and the read of
 * that name have to be the same location: the class. A name written to a local of the class body's
 * stream would be a name the class does not have.
 */
@DisplayName("Python: a class body holds the statements the language has")
class PythonClassBodyLoweringTest {

  private static final String STATEMENTS =
      """
      import os

      class Runner:
          if True:
              os.system("a")
          for item in [1]:
              os.system("b")
          with open("f") as handle:
              os.system("c")
          try:
              os.system("d")
          except Exception:
              os.system("e")
      """;

  private static final String ATTRIBUTE =
      """
      class Runner:
          if True:
              VALUE = 1
          print(VALUE)
      """;

  private static final String METHOD =
      """
      class Runner:
          if True:
              def helper(self):
                  return 1
      """;

  @Test
  @DisplayName("every statement a class body holds is lowered into the class body's stream")
  void everyStatementAClassBodyHoldsIsLowered(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, STATEMENTS);
    TacFunction body = PythonTestSupport.functionNamed(functions, "<clinit>");

    assertThat(body).as("the class body is a stream of its own").isNotNull();
    assertThat(PythonTestSupport.callsTo(body, "system"))
        .as(
            "the calls inside the class body's branches must be in the class body's stream:"
                + " a body that is not lowered is a body whose findings are not there")
        .hasSize(5);
  }

  @Test
  @DisplayName("a class body reports no statement it could not lower")
  void aClassBodyReportsNoStatementItCouldNotLower(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, STATEMENTS);
    TacFunction body = PythonTestSupport.functionNamed(functions, "<clinit>");

    assertThat(body).isNotNull();
    assertThat(body.drops())
        .as(
            "every statement in the body was lowered, so nothing in it was reported as a"
                + " statement this translator did not understand")
        .noneMatch(drop -> drop.construct().startsWith("unhandled."));
  }

  @Test
  @DisplayName("a name written in a class body is read back from the class")
  void aNameWrittenInAClassBodyIsReadBackFromTheClass(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, ATTRIBUTE);
    TacFunction body = PythonTestSupport.functionNamed(functions, "<clinit>");

    assertThat(body).as("the class body is a stream of its own").isNotNull();

    TacInstruction store = PythonTestSupport.storeOfKey(body, "VALUE");
    assertThat(store)
        .as("a name written in a class body is an attribute of the class, not a local")
        .isNotNull();

    TacInstruction load = PythonTestSupport.loadOfKey(body, "VALUE");
    assertThat(load).as("the name must be read from the class as well").isNotNull();
    assertThat(load.lhs1)
        .as("the read and the write must be the same location, or the class has two of them")
        .isEqualTo(store.lhs1);
  }

  @Test
  @DisplayName("a method written inside a class body is a member of that class")
  void aMethodWrittenInsideAClassBodyIsAMemberOfThatClass(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, METHOD);
    TacFunction helper = PythonTestSupport.functionNamed(functions, "helper");

    assertThat(helper).as("the method must be in the stream").isNotNull();
    assertThat(helper.enclosingClass())
        .as("a method written inside a class body belongs to that class, not to the module")
        .isNotNull()
        .endsWith("Runner");
  }
}
