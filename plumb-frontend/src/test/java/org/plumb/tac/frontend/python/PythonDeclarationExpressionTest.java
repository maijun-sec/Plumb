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

/**
 * The expressions a declaration evaluates, as the side car lowers them.
 *
 * <p><b>Why they belong to the stream the declaration is written in.</b> Python evaluates a default
 * value, an annotation and a class's bases when the declaration is written -- not when the function
 * is called, and not when the class body runs. So they belong to the enclosing stream, and that is
 * also where the names they read are defined. A default that read a variable of the enclosing
 * function was, before this, lowered into the declared function's own stream, where that variable
 * does not exist: the sink inside it was visible and its argument was not.
 *
 * <p><b>Why the assertion names the streams rather than counting instructions.</b> What has to stay
 * true is WHERE the call is: in the enclosing function's stream, and in no other.
 */
@DisplayName("Python: a declaration's expressions are evaluated where it is written")
class PythonDeclarationExpressionTest {

  private static final String LAMBDA_DEFAULT =
      """
      import os

      def handle(cmd):
          f = lambda x=os.system(cmd): x
          return f
      """;

  private static final String FUNCTION_DEFAULT =
      """
      import os

      def handle(cmd):
          def inner(x=os.system(cmd)):
              return x
          return inner
      """;

  private static final String PARAMETER_ANNOTATION =
      """
      import os

      def handle(cmd):
          def inner(x: os.system(cmd)):
              return x
          return inner
      """;

  private static final String RETURN_ANNOTATION =
      """
      import os

      def handle(cmd):
          def inner() -> os.system(cmd):
              return 1
          return inner
      """;

  private static final String CLASS_BASE =
      """
      import os

      def handle(cmd):
          class Inner(os.system(cmd)):
              pass
          return Inner
      """;

  @Test
  @DisplayName("a lambda's default value is evaluated where the lambda is written")
  void aLambdasDefaultValueIsEvaluatedWhereTheLambdaIsWritten(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, LAMBDA_DEFAULT);

    assertThat(PythonTestSupport.functionsCalling(functions, "system"))
        .as(
            "the default is evaluated where the lambda is written, so the call belongs to the"
                + " enclosing function -- and the closure's own stream must not have it")
        .containsExactly("handle");
  }

  @Test
  @DisplayName("a function's default value is evaluated where the function is written")
  void aFunctionsDefaultValueIsEvaluatedWhereTheFunctionIsWritten(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, FUNCTION_DEFAULT);

    assertThat(PythonTestSupport.functionsCalling(functions, "system"))
        .as(
            "the default is evaluated where the declaration is written, so the call belongs to"
                + " the enclosing function -- and the declared function's stream must not have it")
        .containsExactly("handle");
  }

  @Test
  @DisplayName("a parameter's annotation is evaluated where the function is written")
  void aParametersAnnotationIsEvaluatedWhereTheFunctionIsWritten(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, PARAMETER_ANNOTATION);

    assertThat(PythonTestSupport.functionsCalling(functions, "system"))
        .as("the annotation is evaluated where the declaration is written")
        .containsExactly("handle");
  }

  @Test
  @DisplayName("a return annotation is evaluated where the function is written")
  void aReturnAnnotationIsEvaluatedWhereTheFunctionIsWritten(@TempDir Path directory)
      throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, RETURN_ANNOTATION);

    assertThat(PythonTestSupport.functionsCalling(functions, "system"))
        .as("the annotation is evaluated where the declaration is written")
        .containsExactly("handle");
  }

  @Test
  @DisplayName("a class's bases are evaluated where the class is written")
  void aClasssBasesAreEvaluatedWhereTheClassIsWritten(@TempDir Path directory) throws IOException {
    List<TacFunction> functions = PythonTestSupport.translateAll(directory, CLASS_BASE);

    assertThat(PythonTestSupport.functionsCalling(functions, "system"))
        .as("the bases are evaluated where the class statement is written, before the body runs")
        .containsExactly("handle");
  }
}
