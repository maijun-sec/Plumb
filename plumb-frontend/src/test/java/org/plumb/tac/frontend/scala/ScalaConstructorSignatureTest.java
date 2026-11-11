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
package org.plumb.tac.frontend.scala;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * A construction carries the CONSTRUCTOR's signature.
 *
 * <p><b>Why this test exists.</b> The DFA's checkers match a sink family against an instruction's
 * callee signature, and a construction is matched the same way: {@code FilePermissionChecker} and
 * {@code PathTraversalChecker} both handle {@code Kind.NEW} and both skip the instruction when the
 * signature is null. So a NEW without a signature is invisible to every constructor family.
 *
 * <p>The Scala frontend emitted {@code dest}, {@code ctorType} and {@code args} and NOTHING else,
 * while the Java lane ({@code ExpressionTranslator}) and the Kotlin lane ({@code
 * KotlinTacTranslator}) both emitted the signature. The consequence was that the families naming a
 * constructor -- {@code file-permission.sinks} and {@code path-traversal.sinks} among them --
 * matched nothing on Scala code: the scan ran, produced no finding, and looked clean.
 *
 * <p>This is the Scala counterpart of {@code ScalaCalleeOwnerTest}, which holds the CALL side to
 * the same contract, because the two share one sink file.
 */
class ScalaConstructorSignatureTest {

  /** Every NEW's callee signature, across every function. */
  private static List<String> constructorSignatures(String source) {
    ScalaTestSupport support = new ScalaTestSupport();
    List<TacFunction> functions = support.parse(source).functions();
    List<String> sigs = new ArrayList<>();
    for (TacFunction fn : functions) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == TacInstruction.Kind.NEW && ins.calleeSig != null) {
          sigs.add(ins.calleeSig);
        }
      }
    }
    return sigs;
  }

  @Test
  @DisplayName("a construction carries the constructed class's fully qualified name")
  void constructionCarriesTheClassName() {
    // `new java.io.File("/tmp/x")` constructs java.io.File, and the families name that class.
    List<String> sigs =
        constructorSignatures(
            """
            import java.io.File

            class Owner:
              def run(): Unit =
                val f = new File("/tmp/x")
            """);

    assertThat(sigs)
        .as("the construction must name the constructed class")
        .anyMatch(s -> s.startsWith("java.io.File."));
  }

  @Test
  @DisplayName("the member is the constructor, and the arity is the argument count")
  void memberIsInitAndArityIsTheArgumentCount() {
    // The families' trailing `(/\d+)?` group matches against the arity, so it has to be the real
    // argument count. The constructor is written `<init>` -- that is the name the Java lane uses
    // for the same instruction.
    List<String> sigs =
        constructorSignatures(
            """
            import java.io.File

            class Owner:
              def run(): Unit =
                val f = new File("/tmp/x")
            """);

    assertThat(sigs)
        .as("a one-argument construction is arity 1")
        .anyMatch(s -> s.startsWith("java.io.File.<init>/1"));
  }

  @Test
  @DisplayName("a construction with two arguments has arity 2")
  void twoArgumentConstructionHasArityTwo() {
    List<String> sigs =
        constructorSignatures(
            """
            import java.io.File

            class Owner:
              def run(): Unit =
                val f = new File("/tmp", "x")
            """);

    assertThat(sigs)
        .as("a two-argument construction is arity 2")
        .anyMatch(s -> s.startsWith("java.io.File.<init>/2"));
  }

  @Test
  @DisplayName("no signature uses dotc's hash separator")
  void noSignatureUsesTheHashSeparator() {
    // dotc's own convention is `owner#member`. The families are anchored to `owner.member`, so a
    // hash makes every pattern miss -- the same contract the CALL side is held to.
    //
    // The list must not be empty: an empty list would satisfy "none uses a hash" without saying
    // anything, and the assertion would pass on a frontend that emits no signature at all.
    List<String> sigs =
        constructorSignatures(
            """
            import java.io.File

            class Owner:
              def run(): Unit =
                val f = new File("/tmp/x")
            """);

    assertThat(sigs).as("the construction must carry a signature at all").isNotEmpty();
    assertThat(sigs)
        .as("no signature may use dotc's hash separator")
        .noneMatch(s -> s.contains("#"));
  }
}
