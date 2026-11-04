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

// IR-serializable: this enum participates in the disk-cache contract (IrSerializer).
// Enum constants are serialised by name, so ADDING a constant at the end is
// backwards compatible. Reordering or renaming a constant is NOT.

/**
 * The source language a {@link TacFunction}'s body was written in.
 *
 * <p>This is a <b>dimension of the IR contract</b>, not a language-specific extension. The TAC
 * itself stays language-neutral; this enum tells a checker which language's semantics it is looking
 * at, so that:
 *
 * <ul>
 *   <li>language-specific sinks can be declared (e.g. {@code kotlin.text.Regex});
 *   <li>language-specific false positives can be suppressed (e.g. Kotlin's non-null type system);
 *   <li>language-specific checks can be enabled (e.g. Kotlin's {@code Result<T>} error handling).
 * </ul>
 *
 * <p><b>Directional rule.</b> This enum may be used to <i>suppress</i> a finding or to
 * <i>select</i> a rule. It must never be used to <i>prove</i> that code is safe -- see {@link
 * IrPrecision} for the same rule applied to precision.
 */
public enum SourceLanguage {

  /** Java source. The default; {@code .java} files parsed by the JavaParser frontend. */
  JAVA,

  /** Kotlin source. */
  KOTLIN,

  /** Scala 3 source. */
  SCALA,

  /**
   * Groovy source.
   *
   * <p>Groovy is dynamically dispatched, so dataflow precision is limited. Only enable Groovy
   * analysis when the user accepts that.
   */
  GROOVY,

  /**
   * The language could not be determined -- typically code loaded from a third-party {@code .jar}
   * through the bytecode frontend.
   */
  UNKNOWN;

  /**
   * Whether this language has a compile-time non-null type system.
   *
   * <p>Kotlin's {@code String} (as opposed to {@code String?}) guarantees non-nullness at the
   * declaration site. Scala and Java have no such system.
   *
   * <p><b>Directional rule.</b> A {@code true} answer means "the compiler rejects a null literal
   * for this type", which is a fact about the <i>declaration</i>. It says nothing about whether a
   * particular runtime value is null (platform types from Java interop still can be). Use it only
   * to suppress null-dereference false positives.
   */
  public boolean hasNonNullTypeSystem() {
    return this == KOTLIN;
  }

  /** Whether this language is statically typed enough for reliable dataflow analysis. */
  public boolean supportsFullDataflow() {
    return this == JAVA || this == KOTLIN || this == SCALA;
  }

  /**
   * Parse a language name as written in configuration (YAML, CLI).
   *
   * <p>Accepts the enum name in any case, plus a few common aliases. Returns {@link #UNKNOWN} for
   * anything unrecognised -- an unknown language must not silently become {@link #JAVA}, because
   * that would make Java-specific rules fire on code they do not understand.
   */
  public static SourceLanguage parse(String name) {
    if (name == null || name.isBlank()) return UNKNOWN;
    String k = name.trim().toUpperCase(java.util.Locale.ROOT);
    switch (k) {
      case "JAVA":
      case "JVM-JAVA":
        return JAVA;
      case "KOTLIN":
      case "KOT":
      case "JVM-KOTLIN":
        return KOTLIN;
      case "SCALA":
      case "SCALA3":
      case "JVM-SCALA":
        return SCALA;
      case "GROOVY":
      case "JVM-GROOVY":
        return GROOVY;
      default:
        return UNKNOWN;
    }
  }
}
