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
import org.plumb.tac.ir.TacOperand;

/**
 * The Python iteration protocol, as the side car lowers it.
 *
 * <p><b>Why the element is the RESULT of {@code __next__}.</b> Python's {@code __next__} returns
 * the element itself. It is not a pair of a value and a done flag -- that is JavaScript's iterator
 * result, and the shape Python used to be lowered into. Reading a {@code ["value"]} out of it says
 * that the element is a MEMBER of what {@code __next__} returned, which is a different thing
 * entirely, and it is what made the element's taint disappear: the engine's iterator propagation
 * taints the result of {@code __next__}, and a read of a member of it is not that result.
 *
 * <p><b>Why this is a shape assertion and not a position one.</b> The loop's instructions move
 * whenever anything above them changes. What has to stay true is the RELATION between them: the
 * loop variable is assigned the result of the {@code __next__} call, and nothing reads a member of
 * it.
 */
@DisplayName("Python: the iteration protocol hands out the element itself")
class PythonIteratorLoweringTest {

  private static final String SOURCE =
      """
      def handle(request):
          items = [request]
          for x in items:
              use(x)
          return x
      """;

  @Test
  @DisplayName("the loop variable is the result of __next__")
  void theLoopVariableIsTheResultOfNext(@TempDir Path directory) throws IOException {
    TacFunction handle = translate(directory, SOURCE);

    TacInstruction next = PythonTestSupport.firstCallTo(handle, "__next__");
    assertThat(next).as("the iteration protocol must call __next__").isNotNull();
    assertThat(next.dest)
        .as("__next__ returns the element, so it must produce a value")
        .isInstanceOf(TacOperand.TacVar.class);

    TacOperand.TacVar element = (TacOperand.TacVar) next.dest;
    TacInstruction assignment = PythonTestSupport.moveTo(handle, "x");
    assertThat(assignment).as("the loop variable is never assigned").isNotNull();
    assertThat(assignment.lhs1)
        .as("the loop variable is the result of __next__, not a member of it")
        .isEqualTo(element);

    assertThat(handle.instructions())
        .as(
            "nothing may read a member out of what __next__ returned: Python's __next__ returns"
                + " the element, so a \"value\" read would be reading a member of the element")
        .noneMatch(
            i ->
                i.kind == TacInstruction.Kind.ARRAYLOAD
                    && i.lhs1 instanceof TacOperand.TacVar v
                    && element.equals(v));
  }

  @Test
  @DisplayName("the protocol calls carry the real Python method names")
  void theProtocolCallsCarryTheRealPythonMethodNames(@TempDir Path directory) throws IOException {
    TacFunction handle = translate(directory, SOURCE);

    TacInstruction iter = PythonTestSupport.firstCallTo(handle, "__iter__");
    assertThat(iter).as("the iteration protocol must call __iter__").isNotNull();
    assertThat(iter.args)
        .as("the receiver comes first: __iter__ is called ON the iterable")
        .isNotEmpty();
    assertThat(iter.args.get(0))
        .as("the receiver of __iter__ is the iterable itself")
        .isInstanceOf(TacOperand.TacVar.class);

    TacInstruction next = PythonTestSupport.firstCallTo(handle, "__next__");
    assertThat(next).as("the iteration protocol must call __next__").isNotNull();
    assertThat(next.args)
        .as("the receiver comes first: __next__ is called ON the iterator")
        .isNotEmpty();
    assertThat(next.args.get(0))
        .as("the receiver of __next__ is the iterator that __iter__ returned")
        .isInstanceOf(TacOperand.TacVar.class);
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** Translate the source and return the function under test. */
  private static TacFunction translate(Path directory, String source) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, source);
    TacFunction handle = PythonTestSupport.functionNamed(functions, "handle");
    assertThat(handle).as("the function must be in the stream").isNotNull();
    return handle;
  }
}
