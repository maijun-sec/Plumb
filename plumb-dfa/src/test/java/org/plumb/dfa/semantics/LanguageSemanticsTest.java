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
   * <p>Scala has no table yet. That is an absence of claims, not a claim that every Scala call
   * preserves taint. The empty table makes every callee unknown, and the conservative default
   * applies.
   */
  @Test
  @DisplayName("a language with no table gets the conservative default")
  void aLanguageWithNoTableGetsTheDefault() {
    LanguageSemanticsRegistry registry = LanguageSemanticsRegistry.withDefaults();

    LanguageSemantics scala = registry.forLanguage(SourceLanguage.SCALA);
    assertThat(scala.entries()).isEmpty();
    assertThat(scala.behaviourOf("scala.Predef.println/1")).isEqualTo(TaintBehaviour.UNKNOWN);
    assertThat(scala.preservesTaint("scala.Predef.println/1"))
        .as("an unwritten table must not drop taint")
        .isTrue();
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
