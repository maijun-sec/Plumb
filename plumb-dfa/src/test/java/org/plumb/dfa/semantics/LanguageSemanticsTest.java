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
package org.plumb.dfa.semantics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.semantics.LanguageSemantics.TaintBehaviour;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The library semantics must be data, and the data must be per language.
 *
 * <p><b>What this pins.</b> The taint engine used to decide "does this call carry its argument's
 * taint through?" by writing {@code "java.lang.String.trim/"} inline, across 19 files. That made
 * three things impossible:
 *
 * <ol>
 *   <li>Adding a language. A Kotlin frontend had no way to say that {@code
 *       kotlin.text.StringsKt.trim/1} behaves like {@code String.trim}, except by editing
 *       Java-centric checks in the engine.
 *   <li>Seeing the whole catalogue. The set of prefixes was the real knowledge, and it was smeared
 *       across whichever file happened to need it.
 *   <li>Testing it. There was no single place to assert "trim preserves, parseInt does not".
 * </ol>
 */
class LanguageSemanticsTest {

  /**
   * Java's table must classify the calls the engine depends on.
   *
   * <p>These are the calls whose behaviour the taint engine's correctness rests on. If {@code trim}
   * were classified as dissociating, a tainted string would stop being tainted at the trim and a
   * downstream sink would report clean.
   */
  @Test
  @DisplayName("Java's table classifies the calls the taint engine depends on")
  void javaTableClassifiesTheCallsThatMatter() {
    LanguageSemantics java = LanguageSemanticsRegistry.javaSemantics();

    // Preserves: the result is derived from the input.
    assertThat(java.behaviourOf("java.lang.String.trim/1")).isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(java.behaviourOf("java.lang.String.substring/2"))
        .isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(java.behaviourOf("java.lang.String.concat/1")).isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(java.behaviourOf("java.lang.String.format/2")).isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(java.behaviourOf("java.lang.String.join/2")).isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(java.behaviourOf("java.lang.Throwable.getMessage/0"))
        .isEqualTo(TaintBehaviour.PRESERVES);

    // Dissociates: the result is not the input.
    assertThat(java.behaviourOf("java.lang.Integer.parseInt/1"))
        .isEqualTo(TaintBehaviour.DISSOCIATES);
    assertThat(java.behaviourOf("java.lang.Long.parseLong/1"))
        .isEqualTo(TaintBehaviour.DISSOCIATES);
    assertThat(java.behaviourOf("java.lang.String.getBytes/1"))
        .isEqualTo(TaintBehaviour.DISSOCIATES);
  }

  /**
   * An unknown callee must be assumed to preserve taint.
   *
   * <p>This is the load-bearing default. A call the engine has not learned about is a hole in the
   * analysis; assuming it does <em>not</em> pass taint through drops the taint at every unknown API
   * boundary, and a dropped taint is a missed vulnerability. Assuming it does pass taint through
   * costs a possible false positive on an unmodelled API -- a visible, fixable cost.
   */
  @Test
  @DisplayName("an unknown callee is assumed to preserve taint")
  void unknownCalleesPreserveTaint() {
    LanguageSemantics java = LanguageSemanticsRegistry.javaSemantics();

    assertThat(java.behaviourOf("com.example.Something.unknown/1"))
        .isEqualTo(TaintBehaviour.UNKNOWN);
    assertThat(java.preservesTaint("com.example.Something.unknown/1"))
        .as("an unmodelled call must not drop the taint")
        .isTrue();
  }

  /**
   * The table must be per language.
   *
   * <p>Kotlin's stdlib has its own names for the same behaviour. A Kotlin function that calls
   * {@code trim} must be recognised just like a Java one -- and that only works if the table is
   * selected by the function's language.
   */
  @Test
  @DisplayName("the table is selected by language")
  void theTableIsSelectedByLanguage() {
    LanguageSemanticsRegistry registry = LanguageSemanticsRegistry.withDefaults();

    assertThat(registry.forLanguage(SourceLanguage.JAVA).behaviourOf("java.lang.String.trim/1"))
        .isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(
            registry.forLanguage(SourceLanguage.KOTLIN).behaviourOf("kotlin.text.StringsKt.trim/1"))
        .as("Kotlin's stdlib name must be recognised")
        .isEqualTo(TaintBehaviour.PRESERVES);
  }

  /**
   * A language with no table must still answer.
   *
   * <p>Every language Plumb can analyse has a table now, but the mechanism for a language that has
   * none must keep working: the empty table makes every callee unknown, and the conservative
   * default applies. This is what a newly added language gets before its semantics are written
   * down.
   */
  @Test
  @DisplayName("a language with no table gets the conservative default")
  void aLanguageWithNoTableGetsTheDefault() {
    LanguageSemanticsRegistry registry = new LanguageSemanticsRegistry();

    LanguageSemantics unknown = registry.forLanguage(SourceLanguage.SCALA);
    assertThat(unknown.entries()).isEmpty();
    assertThat(unknown.behaviourOf("scala.Predef.println/1")).isEqualTo(TaintBehaviour.UNKNOWN);
    assertThat(unknown.preservesTaint("scala.Predef.println/1"))
        .as("an unwritten table must not drop taint")
        .isTrue();
  }

  /**
   * Scala's table must classify the calls the taint engine depends on.
   *
   * <p><b>Every owner here was MEASURED</b> by running the Scala frontend over a probe of the
   * standard library's String operations, not inferred from the source text. The measurement
   * matters because a Scala {@code String} is a {@code java.lang.String}: {@code s.trim} resolves
   * to {@code java.lang.String.trim}, while {@code s.take} resolves to {@code
   * scala.collection.StringOps.take}. A table written from the source text alone would have named
   * owners that never appear in the IR, and every entry would have been dead.
   */
  @Test
  @DisplayName("Scala's table classifies the measured Scala owners")
  void scalaTableClassifiesTheMeasuredOwners() {
    LanguageSemantics scala = LanguageSemanticsRegistry.scalaSemantics();

    // A Scala String IS a java.lang.String: these resolve to java.lang.String.
    assertThat(scala.behaviourOf("java.lang.String.trim/0")).isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(scala.behaviourOf("java.lang.String.substring/1"))
        .isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(scala.behaviourOf("java.lang.String.concat/1")).isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(scala.behaviourOf("java.lang.String.split/1")).isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(scala.behaviourOf("java.lang.String.toLowerCase/0"))
        .isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(scala.behaviourOf("java.lang.String.replace/2")).isEqualTo(TaintBehaviour.PRESERVES);

    // These resolve to java.lang.String too, and they REMOVE the taint.
    assertThat(scala.behaviourOf("java.lang.String.length/0"))
        .isEqualTo(TaintBehaviour.DISSOCIATES);
    assertThat(scala.behaviourOf("java.lang.String.hashCode/0"))
        .isEqualTo(TaintBehaviour.DISSOCIATES);
    assertThat(scala.behaviourOf("java.lang.String.isEmpty/0"))
        .isEqualTo(TaintBehaviour.DISSOCIATES);
    assertThat(scala.behaviourOf("java.lang.String.getBytes/0"))
        .isEqualTo(TaintBehaviour.DISSOCIATES);

    // The StringOps-only operations resolve to scala.collection.StringOps.
    assertThat(scala.behaviourOf("scala.collection.StringOps.take/1"))
        .isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(scala.behaviourOf("scala.collection.StringOps.drop/1"))
        .isEqualTo(TaintBehaviour.PRESERVES);
    assertThat(scala.behaviourOf("scala.collection.StringOps.stripPrefix/1"))
        .isEqualTo(TaintBehaviour.PRESERVES);

    // The implicit conversion that wraps a String into a StringOps.
    assertThat(scala.behaviourOf("scala.Predef$.augmentString/1"))
        .isEqualTo(TaintBehaviour.PRESERVES);

    // An interpolated string carries the interpolated parts.
    assertThat(scala.behaviourOf("scala.StringContext.s/1")).isEqualTo(TaintBehaviour.PRESERVES);
  }

  /**
   * The Scala table must not name owners the frontend never emits.
   *
   * <p>Scala 3 emits these operations as static extension methods ({@code toInt$extension}), but
   * the frontend resolves them to the instance method the compiler ultimately calls, so the emitted
   * key carries no {@code $extension} suffix. A table written against the library's own method
   * names would match nothing: every entry would be dead, and the table would look populated while
   * changing no behaviour.
   *
   * <p>This test is the guard against that: it asserts the table does NOT contain the extension
   * spelling, and that the operation it DOES contain is reachable.
   */
  @Test
  @DisplayName("the Scala table names the resolved owner, not the library's extension spelling")
  void scalaTableNamesTheResolvedOwner() {
    LanguageSemantics scala = LanguageSemanticsRegistry.scalaSemantics();

    assertThat(scala.behaviourOf("scala.collection.StringOps.toInt$extension/0"))
        .as("the frontend never emits the $extension spelling")
        .isEqualTo(TaintBehaviour.UNKNOWN);
    assertThat(scala.behaviourOf("scala.collection.StringOps.take$extension/1"))
        .as("the frontend never emits the $extension spelling")
        .isEqualTo(TaintBehaviour.UNKNOWN);
    assertThat(scala.behaviourOf("scala.collection.StringOps.take/1"))
        .as("the operation the frontend CAN lower is classified")
        .isEqualTo(TaintBehaviour.PRESERVES);
  }

  /**
   * The registry must carry a Scala table.
   *
   * <p>Before the Scala table existed, every Scala callee was {@link TaintBehaviour#UNKNOWN}, and
   * the engine's conservative default made every Scala call taint-preserving -- including {@code
   * s.length} and {@code s.hashCode}, whose whole purpose is to remove taint.
   */
  @Test
  @DisplayName("the registry carries a Scala table")
  void theRegistryCarriesAScalaTable() {
    LanguageSemanticsRegistry registry = LanguageSemanticsRegistry.withDefaults();

    assertThat(registry.languages()).contains(SourceLanguage.SCALA);
    assertThat(registry.forLanguage(SourceLanguage.SCALA).entries()).isNotEmpty();
    assertThat(registry.forLanguage(SourceLanguage.SCALA).behaviourOf("java.lang.String.length/0"))
        .as("a Scala call to length must not be treated as taint-preserving")
        .isEqualTo(TaintBehaviour.DISSOCIATES);
  }

  /**
   * A black-box call must be distinguishable.
   *
   * <p>The engine uses this to decide whether to emit an {@code UNINTERPRETED} instruction: "I
   * could not see inside this call" must be a fact in the IR, not an absence of a summary.
   */
  @Test
  @DisplayName("a black-box call is distinguishable from an unknown one")
  void blackBoxIsDistinguishable() {
    LanguageSemantics custom =
        LanguageSemantics.of(SourceLanguage.JAVA, "com.example.Opaque/1", TaintBehaviour.BLACK_BOX);

    assertThat(custom.isBlackBox("com.example.Opaque/1")).isTrue();
    assertThat(custom.isBlackBox("com.example.Other/1")).isFalse();
    assertThat(custom.behaviourOf("com.example.Other/1")).isEqualTo(TaintBehaviour.UNKNOWN);
  }

  /** A malformed table must be rejected rather than silently half-built. */
  @Test
  @DisplayName("a malformed table is rejected")
  void aMalformedTableIsRejected() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> LanguageSemantics.of(SourceLanguage.JAVA, "only-a-prefix"));
  }
}
