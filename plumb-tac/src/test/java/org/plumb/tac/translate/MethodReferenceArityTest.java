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
package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacProgram;

/**
 * A method reference names a method, and the binding says which one.
 *
 * <p>{@code String::trim} is bound to {@code java.lang.String.trim/1}: the referenced method takes
 * no parameters, and the functional interface supplies the receiver. The application site is
 * rewritten into that call, so the taint of the argument crosses the reference.
 *
 * <p>The arity came from the resolved declaration, and the resolution never happened: the code
 * asked the resolved declaration to hand back a method, and a resolved method declaration is not
 * asked that way -- the call throws, the catch swallowed it, and every reference was bound with the
 * arity {@code -1}. A callee signature with the arity {@code -1} matches nothing, so the rewrite
 * found no callee and the taint stopped at the application: the program was reported clean.
 */
class MethodReferenceArityTest {

  @Test
  void anUnboundInstanceReferenceBindsTheReceiversArity() {
    String src =
        "import java.util.function.Function;\n"
            + "class Use {\n"
            + "  void go(String userInput) {\n"
            + "    Function<String, String> f = String::trim;\n"
            + "    f.apply(userInput);\n"
            + "  }\n"
            + "}\n";

    TacProgram program = translate(src);
    Map<String, String> targets = program.deferredTargets();

    assertThat(targets)
        .as("the reference is bound to the method it names, with the arity that method takes")
        .containsValue("java.lang.String.trim/1");
  }

  @Test
  void aStaticReferenceBindsTheParametersArity() {
    String src =
        "import java.util.function.Function;\n"
            + "class Use {\n"
            + "  void go(String userInput) {\n"
            + "    Function<String, String> f = String::valueOf;\n"
            + "    f.apply(userInput);\n"
            + "  }\n"
            + "}\n";

    TacProgram program = translate(src);
    Map<String, String> targets = program.deferredTargets();

    assertThat(targets)
        .as("a static reference takes the arguments and no receiver")
        .containsValue("java.lang.String.valueOf/1");
  }

  @Test
  void noReferenceIsBoundWithANegativeArity() {
    String src =
        "import java.util.function.Function;\n"
            + "class Use {\n"
            + "  void go(String userInput) {\n"
            + "    Function<String, String> f = String::trim;\n"
            + "    f.apply(userInput);\n"
            + "  }\n"
            + "}\n";

    for (String target : translate(src).deferredTargets().values()) {
      assertThat(target).as("a callee signature's arity is a count").doesNotEndWith("/-1");
    }
  }

  private static TacProgram translate(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("T.java"), src);
    return new TacTranslator(ps).translate();
  }
}
