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
package org.plumb.dfa.cg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.CalleeKeyNormalizer;

/**
 * The callee key is a SHARED CONVENTION, and these tests pin it.
 *
 * <p><b>What is being pinned.</b> A call names its callee as {@code "owner.method/arity"}; a
 * function is registered under its own signature ({@code "run(String):void"}) and knows its owner
 * only through {@code enclosingClass()}. When the two sides of a cross-language call compute
 * different keys for the same method, the call-graph edge is never built -- and the taint flow
 * across the language boundary is silently not followed.
 *
 * <p>So the convention is: ONE canonical key, {@code owner.method/arity}, with the owner matched by
 * SIMPLE NAME. A Java declaration's owner is {@code JavaSink}; a Kotlin call's owner is {@code
 * demo.JavaSink}. They name the same class.
 *
 * <p>These are unit tests on the convention itself. The end-to-end behaviour -- the edge forming
 * and the finding landing on the CALLER's file -- is pinned by {@code CrossLanguageInteropTest}.
 */
class CalleeKeyNormalizerTest {

  @Test
  @DisplayName("the Java paren form and the Kotlin slash form are the SAME method")
  void theTwoSignatureFormsNameTheSameMethod() {
    // The two forms the frontends produce for one method.
    String javaForm = "run(String):void";
    String kotlinForm = "demo.JavaSink.run/1";

    assertThat(CalleeKeyNormalizer.sameMethod(javaForm, kotlinForm))
        .as("the Java declaration and the Kotlin call name the same method")
        .isTrue();
  }

  @Test
  @DisplayName("the canonical key is owner.method/arity")
  void theCanonicalKeyHasTheDocumentedShape() {
    assertThat(CalleeKeyNormalizer.canonicalCalleeKey("run(String):void"))
        .as("a declaration with no owner keeps just the method and arity")
        .isEqualTo("run/1");
    assertThat(CalleeKeyNormalizer.canonicalCalleeKey("demo.JavaSink.run/1"))
        .as("a qualified call keeps its owner")
        .isEqualTo("demo.JavaSink.run/1");
    assertThat(CalleeKeyNormalizer.canonicalCalleeKey("Repository.helper/1"))
        .isEqualTo("Repository.helper/1");
  }

  @Test
  @DisplayName("the bare key drops the owner")
  void theBareKeyDropsTheOwner() {
    assertThat(CalleeKeyNormalizer.bareCalleeKey("demo.JavaSink.run/1")).isEqualTo("run/1");
    assertThat(CalleeKeyNormalizer.bareCalleeKey("run(String):void")).isEqualTo("run/1");
  }

  @Test
  @DisplayName("two methods with the same name in DIFFERENT classes do not match")
  void sameNameInDifferentClassesDoesNotMatch() {
    // This is why the owner is kept in the key. Merging them would make a call to
    // Alpha.run resolve to Beta.run.
    assertThat(CalleeKeyNormalizer.sameMethod("Alpha.run/1", "Beta.run/1"))
        .as("the owner is part of the identity")
        .isFalse();
  }

  @Test
  @DisplayName("the same class spelled two ways DOES match")
  void theSameClassSpelledTwoWaysMatches() {
    // A Java declaration's owner is the short name; a Kotlin call's owner is fully qualified.
    assertThat(CalleeKeyNormalizer.sameMethod("demo.JavaSink.run/1", "JavaSink.run/1"))
        .as("the owner is compared by simple name")
        .isTrue();
  }

  @Test
  @DisplayName("a different arity is a different method")
  void aDifferentArityIsADifferentMethod() {
    assertThat(CalleeKeyNormalizer.sameMethod("demo.JavaSink.run/1", "demo.JavaSink.run/2"))
        .isFalse();
  }

  @Test
  @DisplayName("a generic parameter list does not inflate the arity")
  void aGenericParameterListDoesNotInflateTheArity() {
    // The comma inside the generic argument belongs to the TYPE, not to the parameter list.
    assertThat(CalleeKeyNormalizer.canonicalCalleeKey("f(Map<String,Integer>,String):void"))
        .as("the comma inside the angle brackets is not a parameter separator")
        .isEqualTo("f/2");
  }

  @Test
  @DisplayName("a malformed signature yields null rather than a wrong key")
  void aMalformedSignatureYieldsNull() {
    assertThat(CalleeKeyNormalizer.canonicalCalleeKey("f(")).isNull();
    assertThat(CalleeKeyNormalizer.canonicalCalleeKey("")).isNull();
    assertThat(CalleeKeyNormalizer.canonicalCalleeKey(null)).isNull();
    assertThat(CalleeKeyNormalizer.canonicalCalleeKey("f/notanumber")).isNull();
  }

  @Test
  @DisplayName("the owner-aware index connects a qualified call to a short-named declaration")
  void theOwnerAwareIndexConnectsTheTwoSpellings() {
    // The shape the call graph uses: a function is indexed under its owner's SIMPLE name.
    Map<String, List<String>> ownerKeyToCanonical = new HashMap<>();
    ownerKeyToCanonical
        .computeIfAbsent("JavaSink.run/1", k -> new ArrayList<>())
        .add("run(String):void");

    String resolved =
        CallGraphBuilder.r48ResolveCalleeSigCore(
            sig -> false, "demo.JavaSink.run/1", new HashMap<>(), ownerKeyToCanonical, null);

    assertThat(resolved)
        .as("a Kotlin call to demo.JavaSink.run resolves to the Java declaration")
        .isEqualTo("run(String):void");
  }

  @Test
  @DisplayName("the owner-aware index does NOT merge two different classes")
  void theOwnerAwareIndexDoesNotMergeDifferentClasses() {
    Map<String, List<String>> ownerKeyToCanonical = new HashMap<>();
    ownerKeyToCanonical
        .computeIfAbsent("Beta.run/1", k -> new ArrayList<>())
        .add("betaRun(String):void");

    String resolved =
        CallGraphBuilder.r48ResolveCalleeSigCore(
            sig -> false, "demo.Alpha.run/1", new HashMap<>(), ownerKeyToCanonical, null);

    assertThat(resolved)
        .as("a call to demo.Alpha.run finds no declaration and stays unresolved")
        .isEqualTo("demo.Alpha.run/1");
  }
}
