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
package org.plumb.dfa.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * A merged program must keep SAME-NAMED METHODS APART.
 *
 * <p>THE DEFECT THIS PINS. The parameter-origin fixpoint kept its per-function label state in a map
 * keyed by the function's SIGNATURE. A corpus like Juliet's declares the same method name in
 * thousands of files: CWE-190 has 7,047 files, and hundreds of them declare {@code void bad()}.
 * Every one of those has the signature {@code bad():void}.
 *
 * <p>So they all shared ONE label map. Each function overwrote the others' labels, and -- because
 * the fixpoint asks "did anything change?" -- the flag was set on every single round. The loop
 * therefore ran all 34,000+ rounds instead of converging, and the scan of one CWE never finished.
 *
 * <p>The merged program already knows how to keep them apart: it keys functions by {@code
 * enclosingClass + " :: " + signature}. Anything that maps per-function state has to use that same
 * key.
 */
class MergedProgramKeyUniquenessTest {

  /**
   * Two classes in DIFFERENT FILES that declare the SAME method name must produce two distinct
   * per-function keys.
   *
   * <p>This is the shape a corpus like Juliet's actually has: every testcase file declares its own
   * top-level class, and every one of them declares a {@code bad()} method. The class names differ,
   * so the SIGNATURES are {@code bad():void} in every file -- they collide.
   */
  @Test
  void sameNamedMethodsInDifferentFilesHaveDistinctKeys() {
    TacProgram a = translate("class FileA { void bad() { int x = 1; } }", "FileA.java");
    TacProgram b = translate("class FileB { void bad() { int y = 2; } }", "FileB.java");

    var bads = new java.util.ArrayList<TacFunction>();
    bads.addAll(a.functions().stream().filter(f -> "bad".equals(f.name())).toList());
    bads.addAll(b.functions().stream().filter(f -> "bad".equals(f.name())).toList());

    assertThat(bads).as("both files' bad() must be translated").hasSize(2);
    // The signatures collide ...
    assertThat(bads.stream().map(TacFunction::signature).distinct().count())
        .as("the signatures are identical -- that is the whole problem")
        .isLessThan(bads.size());
    // ... but the merged keys do not.
    var merged = new MergedProgram(java.util.List.of(a, b));
    var keys =
        merged.asProgram().functions().stream()
            .map(
                f ->
                    (f.enclosingClass() == null || f.enclosingClass().isEmpty()
                        ? f.signature()
                        : f.enclosingClass() + " :: " + f.signature()))
            .distinct()
            .count();
    assertThat(keys)
        .as("every function must have a distinct merged key")
        .isEqualTo(merged.asProgram().functions().size());
  }

  /**
   * The key the engine uses for per-function state must distinguish functions whose signatures
   * collide.
   *
   * <p>This is the invariant the fixpoint depends on: it is what makes the round counter converge.
   */
  @Test
  void theEngineKeyDistinguishesCollidingSignatures() {
    TacProgram a = translate("class FileA { void bad() { int x = 1; } }", "FileA.java");
    TacProgram b = translate("class FileB { void bad() { int y = 2; } }", "FileB.java");

    var bads = new java.util.ArrayList<TacFunction>();
    bads.addAll(a.functions().stream().filter(f -> "bad".equals(f.name())).toList());
    bads.addAll(b.functions().stream().filter(f -> "bad".equals(f.name())).toList());

    var keys = bads.stream().map(f -> DfaEngine.mergedKeyForTest(f)).distinct().toList();

    assertThat(keys).as("colliding signatures must still get distinct keys").hasSize(bads.size());
  }

  private static TacProgram translate(String src, String fileName) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of(fileName), src);
    return new TacTranslatorHolder().translate(ps);
  }

  /** Keeps the test's translator usage in one place. */
  private static final class TacTranslatorHolder {
    TacProgram translate(ParsedSource ps) {
      return new org.plumb.tac.translate.TacTranslator(ps).translate();
    }
  }
}
