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
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.printer.TacPrinter;

/**
 * A lambda's name must be unique across the whole program.
 *
 * <p><b>Why this matters.</b> A lambda body is lowered to a synthetic function named {@code
 * lambda$N}. The counter used to live on the per-method {@link TacBuilder}, so every method
 * restarted at zero and every method's first lambda was called {@code lambda$0}. The program's
 * function table then held SEVERAL functions with the same name.
 *
 * <p>That is not cosmetic. Anything that resolves a lambda BY NAME gets an arbitrary answer instead
 * of the one it meant. The concrete victim was the deserialization-filter tracker: it asks "does
 * this lambda's body ever return {@code REJECTED}?", and with two {@code lambda$0}s -- one
 * rejecting and one not -- it concluded that both reject. A permissive JEP-290 filter was then
 * treated as the shipped mitigation, and the CWE-502 finding it should have produced was silently
 * dropped.
 *
 * <p>So this test pins the invariant at the level it can actually break: no two functions in one
 * program may share a name.
 */
class LambdaNameUniquenessTest {

  /** Two methods, each with a lambda: the shape that used to produce two {@code lambda$0}s. */
  private static final String TWO_METHODS_ONE_LAMBDA_EACH =
      """
      class T {
        int first(String s) { java.util.function.Function<String,Integer> f = x -> x.length(); return f.apply(s); }
        int second(String s) { java.util.function.Function<String,Integer> g = x -> x.hashCode(); return g.apply(s); }
      }
      """;

  @Test
  void twoMethodsWithLambdasGetDistinctNames() {
    org.plumb.tac.ir.TacProgram program = translate(TWO_METHODS_ONE_LAMBDA_EACH);

    List<String> lambdaNames =
        program.functions().stream()
            .map(TacFunction::name)
            .filter(n -> n.startsWith("lambda$"))
            .collect(Collectors.toList());

    assertThat(lambdaNames)
        .as("each lambda gets its own name\n" + TacPrinter.dump(program))
        .hasSize(2);
    assertThat(lambdaNames)
        .as("two lambdas in two methods must not share a name")
        .doesNotHaveDuplicates();
  }

  @Test
  void twoLambdasInOneMethodGetDistinctNames() {
    String src =
        """
        import java.util.function.Function;
        class T {
          void m(String s) {
            Function<String,Integer> f = x -> x.length();
            Function<String,Integer> g = x -> x.hashCode();
            f.apply(s);
            g.apply(s);
          }
        }
        """;
    org.plumb.tac.ir.TacProgram program = translate(src);

    List<String> lambdaNames =
        program.functions().stream()
            .map(TacFunction::name)
            .filter(n -> n.startsWith("lambda$"))
            .collect(Collectors.toList());

    assertThat(lambdaNames).as("both lambdas are lowered").hasSize(2);
    assertThat(lambdaNames)
        .as("two lambdas in one method must not share a name")
        .doesNotHaveDuplicates();
  }

  @Test
  void noTwoFunctionsInOneProgramShareAName() {
    // The general invariant. It is what makes a name lookup meaningful.
    org.plumb.tac.ir.TacProgram program = translate(TWO_METHODS_ONE_LAMBDA_EACH);

    List<String> names =
        program.functions().stream().map(TacFunction::name).collect(Collectors.toList());

    assertThat(names)
        .as("the program's function table must have unique keys\n" + TacPrinter.dump(program))
        .doesNotHaveDuplicates();
  }

  @Test
  void aLambdaCallNamesTheFunctionItCalls() {
    // The name in the CALL must resolve to exactly one body, and that body must be the one the
    // call site actually created. Before the fix, a call site's `scope.lambda$0/1` could point at
    // a DIFFERENT method's lambda.
    org.plumb.tac.ir.TacProgram program = translate(TWO_METHODS_ONE_LAMBDA_EACH);

    for (TacFunction fn : program.functions()) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind != TacInstruction.Kind.CALL || ins.calleeSig == null) {
          continue;
        }
        if (!ins.calleeSig.contains("lambda$")) {
          continue;
        }
        String signaturePart = ins.calleeSig.substring(ins.calleeSig.lastIndexOf('.') + 1);
        final String called = signaturePart.substring(0, signaturePart.indexOf('/'));
        long matches = program.functions().stream().filter(f -> f.name().equals(called)).count();
        assertThat(matches)
            .as(
                "call to %s must resolve to exactly one function\n%s",
                called, TacPrinter.dump(program))
            .isEqualTo(1);
      }
    }
  }

  /** Translate a source snippet into the program's functions. */
  private static org.plumb.tac.ir.TacProgram translate(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("L.java"), src);
    return new TacTranslator(ps).translate();
  }
}
