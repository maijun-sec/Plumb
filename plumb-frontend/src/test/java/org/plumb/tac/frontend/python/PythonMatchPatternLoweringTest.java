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
 * The names a match pattern captures, as the side car lowers them.
 *
 * <p><b>Why a capture binds a VALUE and not the thing that was matched.</b> {@code case {"a": x}}
 * binds x to the entry under "a", and {@code case [x, y]} binds x to the first element and y to
 * the second. A name that was never bound is a name the program reads that does not exist, and a
 * sink that reads it is a sink whose argument the analysis knows nothing about.
 *
 * <p><b>Why the assertions are about relations.</b> What has to stay true is that the capture is
 * assigned the thing the pattern names: the entry under that key, the element at that position,
 * the whole matched value.
 */
@DisplayName("Python: a match pattern binds the value it captures")
class PythonMatchPatternLoweringTest {

  private static final String REST =
      """
      def handle(data):
          match data:
              case {**rest}:
                  return rest
      """;

  private static final String KEYWORD =
      """
      class Point:
          pass

      def handle(data):
          match data:
              case Point(cmd=command):
                  return command
      """;

  private static final String SEQUENCE =
      """
      def handle(data):
          match data:
              case [first, second]:
                  return second
      """;

  private static final String WHOLE =
      """
      def handle(data):
          match data:
              case [inner] as whole:
                  return whole
      """;

  @Test
  @DisplayName("a rest capture holds the value that was matched")
  void aRestCaptureHoldsTheValueThatWasMatched(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, REST);
    TacFunction handle = PythonTestSupport.functionNamed(functions, "handle");

    assertThat(handle).as("the function must be in the stream").isNotNull();
    TacInstruction rest = PythonTestSupport.moveTo(handle, "rest");
    assertThat(rest).as("the rest must be bound: a name that is read and never bound does not"
            + " exist")
        .isNotNull();
    assertThat(rest.lhs1)
        .as("the rest holds the value the match tested")
        .isEqualTo(PythonTestSupport.subjectOf(handle));
  }

  @Test
  @DisplayName("a keyword capture holds the entry of that name")
  void aKeywordCaptureHoldsTheEntryOfThatName(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, KEYWORD);
    TacFunction handle = PythonTestSupport.functionNamed(functions, "handle");

    assertThat(handle).as("the function must be in the stream").isNotNull();

    TacInstruction entry = PythonTestSupport.loadOfKey(handle, "cmd");
    assertThat(entry)
        .as("the entry the keyword names must be read out of the matched value")
        .isNotNull();

    TacInstruction capture = PythonTestSupport.moveTo(handle, "command");
    assertThat(capture).as("the keyword capture must be bound").isNotNull();
    assertThat(capture.lhs1)
        .as("the capture holds that entry and not the whole matched value")
        .isEqualTo(entry.dest);
  }

  @Test
  @DisplayName("a sequence capture holds the element at that position")
  void aSequenceCaptureHoldsTheElementAtThatPosition(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, SEQUENCE);
    TacFunction handle = PythonTestSupport.functionNamed(functions, "handle");

    assertThat(handle).as("the function must be in the stream").isNotNull();

    TacInstruction first = PythonTestSupport.loadAtPosition(handle, 0L);
    TacInstruction second = PythonTestSupport.loadAtPosition(handle, 1L);
    assertThat(first).as("the first element must be read out of the matched value").isNotNull();
    assertThat(second).as("the second element must be read out of the matched value").isNotNull();

    assertThat(PythonTestSupport.moveTo(handle, "first").lhs1)
        .as("the first capture holds the first element")
        .isEqualTo(first.dest);
    assertThat(PythonTestSupport.moveTo(handle, "second").lhs1)
        .as("the second capture holds the second element")
        .isEqualTo(second.dest);
  }

  @Test
  @DisplayName("a whole-value capture holds the value that was matched")
  void aWholeValueCaptureHoldsTheValueThatWasMatched(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, WHOLE);
    TacFunction handle = PythonTestSupport.functionNamed(functions, "handle");

    assertThat(handle).as("the function must be in the stream").isNotNull();

    TacInstruction inner = PythonTestSupport.moveTo(handle, "inner");
    TacInstruction whole = PythonTestSupport.moveTo(handle, "whole");
    assertThat(inner).as("the inner pattern's capture must be bound").isNotNull();
    assertThat(whole).as("the whole-value capture must be bound too").isNotNull();
    assertThat(whole.lhs1)
        .as("the whole capture holds the value that was matched, not the inner element")
        .isEqualTo(PythonTestSupport.subjectOf(handle));
    assertThat(whole.lhs1)
        .as("and it is a different value from the inner element")
        .isNotEqualTo(inner.lhs1);
  }
}
