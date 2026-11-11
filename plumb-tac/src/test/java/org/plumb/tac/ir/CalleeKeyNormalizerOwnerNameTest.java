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
package org.plumb.tac.ir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The owner's simple name is the owner's name, not the method's.
 *
 * <p>The canonical key is {@code owner.method/arity}, so the owner is everything before the last
 * dot. A helper that answers "the owner's simple name" has to cut the OWNER there; cutting the
 * whole key there answers with the method's name instead.
 *
 * <p>It matters because the answer is used to build an owner-qualified lookup key. For {@code
 * demo.JavaSink$.run/1} the wrong answer is {@code run}, and the key built from it is {@code
 * run.run/1} -- a key no declaration is ever published under, so the lookup misses and the caller
 * falls back to a coarser key.
 *
 * <p>The MODULE-CLASS suffix is part of the owner's name here and is not removed: {@code Service$}
 * and {@code Service} are two different classes, and the suffix is only stripped when two owners
 * are COMPARED.
 */
class CalleeKeyNormalizerOwnerNameTest {

  @Test
  @DisplayName("the owner's simple name is the owner's last segment, not the method's")
  void theOwnerNameIsNotTheMethodName() {
    assertThat(CalleeKeyNormalizer.ownerSimpleName("demo.JavaSink$.run/1"))
        .as("the owner of demo.JavaSink$.run is demo.JavaSink$")
        .isEqualTo("JavaSink$");
    assertThat(CalleeKeyNormalizer.ownerSimpleName("demo.JavaSink.run/1"))
        .as("the owner of demo.JavaSink.run is demo.JavaSink")
        .isEqualTo("JavaSink");
    assertThat(CalleeKeyNormalizer.ownerSimpleName("JavaSink.run/1"))
        .as("an unqualified owner is its own simple name")
        .isEqualTo("JavaSink");
  }

  @Test
  @DisplayName("a signature that carries no owner has no owner name")
  void aSignatureWithoutAnOwnerHasNoOwnerName() {
    assertThat(CalleeKeyNormalizer.ownerSimpleName("run/1"))
        .as("the bare key names no owner")
        .isNull();
    assertThat(CalleeKeyNormalizer.ownerSimpleName("run(java.lang.String):void"))
        .as("a declaration's own signature names no owner either")
        .isNull();
  }

  @Test
  @DisplayName("the owner name is what the owner-qualified key is built from")
  void theOwnerNameBuildsTheLookupKey() {
    String owner = CalleeKeyNormalizer.ownerSimpleName("demo.JavaSink$.run/1");
    String bare = CalleeKeyNormalizer.bareCalleeKey("demo.JavaSink$.run/1");

    assertThat(bare).as("the bare key of the call").isEqualTo("run/1");
    assertThat(owner + "." + bare)
        .as("the key a lookup builds from the two")
        .isEqualTo("JavaSink$.run/1");
  }
}
