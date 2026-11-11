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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Two functions can share a signature, and the cache that holds their control-flow graphs has to
 * tell them apart.
 *
 * <p><b>What went wrong.</b> The cache was keyed by the bare signature. A corpus that declares the
 * same method in many files -- the Juliet corpus declares {@code goodB2G()} in 2530 of its 7047
 * files -- therefore handed every one of them the SAME control-flow graph and the SAME analysis
 * order. When the cached order belonged to a longer function, the shorter function's instruction
 * lookup ran past the end of its own instruction list, the lookup threw, and the exception was
 * caught per checker: every checker failed on that function, so the function produced no findings
 * at all.
 *
 * <p>The identity of a function is its enclosing class together with its signature, which is the
 * key the merged view already uses ({@code MergedProgram.mergedKey}). Keying by the signature alone
 * is the mistake that the merged view was fixed for and this cache was not.
 */
class FunctionOrderCacheIdentityTest {

  /**
   * The order must address the function's OWN instructions.
   *
   * <p>The longer function is primed into the cache first, so a collision would hand the shorter
   * one an order that addresses instructions it does not have -- which is exactly the lookup that
   * threw.
   */
  @Test
  @DisplayName("two functions with the same signature get their own analysis order")
  void twoFunctionsWithTheSameSignatureGetTheirOwnOrder() {
    FunctionOrderCache cache = new FunctionOrderCache();

    TacFunction longer = function("com.example.Longer", "handle():void", 40);
    TacFunction shorter = function("com.example.Shorter", "handle():void", 3);

    cache.analysisOrder(longer);
    List<Integer> shorterOrder = cache.analysisOrder(shorter);

    assertThat(shorterOrder)
        .as("the order must address only this function's own instructions")
        .allSatisfy(i -> assertThat(i).isLessThan(shorter.instructions().size()));
  }

  /** The cache must still do its job: the same function asked for twice is one computation. */
  @Test
  @DisplayName("the same function still hits the cache")
  void theSameFunctionStillHitsTheCache() {
    FunctionOrderCache cache = new FunctionOrderCache();
    TacFunction fn = function("com.example.A", "handle():void", 5);

    cache.analysisOrder(fn);
    cache.analysisOrder(fn);

    assertThat(cache.hits()).as("the cache still serves the second ask").isEqualTo(1);
    assertThat(cache.misses()).as("the function was computed once").isEqualTo(1);
  }

  /** A function with no enclosing class keeps the signature as its key. */
  @Test
  @DisplayName("a function without an enclosing class is still cached by its signature")
  void aFunctionWithoutAnEnclosingClassIsStillCached() {
    FunctionOrderCache cache = new FunctionOrderCache();
    TacFunction fn = function(null, "handle():void", 5);

    cache.analysisOrder(fn);
    cache.analysisOrder(fn);

    assertThat(cache.hits()).isEqualTo(1);
  }

  private static TacFunction function(String enclosingClass, String signature, int instructions) {
    List<TacInstruction> body = new ArrayList<>();
    for (int i = 0; i < instructions; i++) {
      TacOperand.TacVar dest = new TacOperand.TacVar("v" + i, TacType.UNKNOWN, true);
      body.add(TacInstruction.builder(TacInstruction.Kind.HAVOC).dest(dest).build());
    }
    return new TacFunction(
        "handle",
        signature,
        TacType.UNKNOWN,
        List.of(),
        List.of(),
        body,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        enclosingClass);
  }
}
