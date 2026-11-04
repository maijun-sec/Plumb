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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.plumb.dfa.semantics.LanguageSemantics.TaintBehaviour;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The registry of per-language semantics tables.
 *
 * <p>One table per language family, built once and shared. A language with no table gets the
 * {@linkplain LanguageSemantics#empty(SourceLanguage) empty table}, which makes every callee {@link
 * TaintBehaviour#UNKNOWN} -- and the engine's conservative default (taint-preserving) then applies.
 *
 * <p><b>Why the empty table is a safe default.</b> An unknown callee is assumed to pass its
 * argument's taint through. The cost of that assumption is a possible false positive at an
 * unmodelled API; the benefit is that taint is not dropped at every boundary the engine has not
 * learned yet. For a tool whose users hold it to a low false-negative bar, that is the right side
 * to err on.
 */
public final class LanguageSemanticsRegistry {

  private final Map<SourceLanguage, LanguageSemantics> tables = new EnumMap<>(SourceLanguage.class);

  /** Registers a table for a language, replacing any previous one. */
  public LanguageSemanticsRegistry register(LanguageSemantics semantics) {
    tables.put(semantics.language(), semantics);
    return this;
  }

  /**
   * The table for a language.
   *
   * <p>Never {@code null}: a language with no registered table gets an empty one, so callers do not
   * have to handle a missing table separately from a table with no rules.
   */
  public LanguageSemantics forLanguage(SourceLanguage language) {
    LanguageSemantics s = tables.get(language == null ? SourceLanguage.UNKNOWN : language);
    if (s != null) return s;
    return LanguageSemantics.empty(language == null ? SourceLanguage.UNKNOWN : language);
  }

  /** The languages that have a registered table. */
  public List<SourceLanguage> languages() {
    return List.copyOf(tables.keySet());
  }

  /**
   * The registry with the built-in tables.
   *
   * <p>Java and Kotlin are populated; Scala and the rest get the empty table until their semantics
   * are written down. That is the honest state: an unwritten table is not a claim that every Scala
   * call preserves taint, it is an absence of claims.
   */
  public static LanguageSemanticsRegistry withDefaults() {
    LanguageSemanticsRegistry reg = new LanguageSemanticsRegistry();
    reg.register(javaSemantics());
    reg.register(kotlinSemantics());
    return reg;
  }

  /**
   * Java's standard library semantics.
   *
   * <p>Every prefix here was previously an inline string comparison in the taint engine. The
   * classification is the behaviour the engine needs:
   *
   * <ul>
   *   <li>{@code PRESERVES} -- the result carries the argument's taint ({@code trim}, {@code
   *       substring}, {@code concat}, {@code format}).
   *   <li>{@code DISSOCIATES} -- the result does not ({@code parseInt}, {@code getBytes}, {@code
   *       toString} on a primitive wrapper).
   *   <li>{@code PRODUCES_TAINT} -- the result may be tainted regardless of the arguments.
   *   <li>{@code BLACK_BOX} -- the engine does not model the call.
   * </ul>
   */
  public static LanguageSemantics javaSemantics() {
    return LanguageSemantics.of(
        SourceLanguage.JAVA,
        // --- String: the result carries the input's taint ---
        "java.lang.String.trim/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.strip/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.stripLeading/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.stripTrailing/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.toLowerCase/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.toUpperCase/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.replace/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.replaceAll/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.replaceFirst/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.concat/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.split/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.substring/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.format/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.join/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.formatted/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.valueOf/",
        TaintBehaviour.PRESERVES,
        "java.lang.StringBuilder.append/",
        TaintBehaviour.PRESERVES,
        "java.lang.StringBuffer.append/",
        TaintBehaviour.PRESERVES,
        // --- String: the result does NOT carry the input's taint ---
        "java.lang.String.getBytes/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.toCharArray/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.hashCode/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.length/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.compareTo/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.contentEquals/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.isEmpty/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.equals/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.matches/",
        TaintBehaviour.DISSOCIATES,
        // --- Numeric parsing: the result is a number, not the input ---
        "java.lang.Integer.parseInt/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Integer.parseUnsignedInt/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Long.parseLong/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Long.parseUnsignedLong/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Short.parseShort/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Byte.parseByte/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Float.parseFloat/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Double.parseDouble/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Boolean.parseBoolean/",
        TaintBehaviour.DISSOCIATES,
        // --- Wrapper toString: a number rendered, not the input text ---
        "java.lang.Integer.toString/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Long.toString/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Boolean.toString/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Object.toString/",
        TaintBehaviour.DISSOCIATES,
        // --- Object.clone: a copy of the receiver, so the receiver's taint ---
        "java.lang.Object.clone/",
        TaintBehaviour.PRESERVES,
        // --- Exception messages: the message is derived from the exception ---
        "java.lang.Throwable.getMessage/",
        TaintBehaviour.PRESERVES,
        "java.lang.Throwable.getLocalizedMessage/",
        TaintBehaviour.PRESERVES,
        "java.lang.Throwable.getCause/",
        TaintBehaviour.PRESERVES,
        "java.lang.Throwable.toString/",
        TaintBehaviour.PRESERVES,
        "java.lang.Exception.getMessage/",
        TaintBehaviour.PRESERVES,
        "java.lang.Exception.getLocalizedMessage/",
        TaintBehaviour.PRESERVES,
        "java.lang.Exception.getCause/",
        TaintBehaviour.PRESERVES,
        "java.lang.Exception.toString/",
        TaintBehaviour.PRESERVES,
        "java.lang.RuntimeException.getMessage/",
        TaintBehaviour.PRESERVES,
        "java.lang.RuntimeException.getLocalizedMessage/",
        TaintBehaviour.PRESERVES,
        "java.lang.RuntimeException.getCause/",
        TaintBehaviour.PRESERVES,
        "java.lang.RuntimeException.toString/",
        TaintBehaviour.PRESERVES,
        "java.lang.Error.getMessage/",
        TaintBehaviour.PRESERVES,
        "java.lang.Error.toString/",
        TaintBehaviour.PRESERVES,
        "java.io.IOException.getMessage/",
        TaintBehaviour.PRESERVES,
        "java.io.IOException.toString/",
        TaintBehaviour.PRESERVES,
        "java.sql.SQLException.getMessage/",
        TaintBehaviour.PRESERVES,
        "java.sql.SQLException.toString/",
        TaintBehaviour.PRESERVES,
        // --- Tokenizing: the result is a piece of the input ---
        "java.util.StringTokenizer.nextToken/",
        TaintBehaviour.PRESERVES,
        "java.util.Scanner.next/",
        TaintBehaviour.PRESERVES,
        "java.util.Scanner.nextLine/",
        TaintBehaviour.PRESERVES,
        "java.util.regex.Pattern.split/",
        TaintBehaviour.PRESERVES,
        "java.util.regex.Matcher.group/",
        TaintBehaviour.PRESERVES,
        // --- Secrets: the value itself, so its taint ---
        "javax.crypto.SecretKey.getEncoded/",
        TaintBehaviour.PRESERVES,
        "java.net.PasswordAuthentication.getPassword/",
        TaintBehaviour.PRESERVES,
        // --- Encryption: the ciphertext does not carry the plaintext's taint ---
        "javax.crypto.Cipher.doFinal/",
        TaintBehaviour.DISSOCIATES,
        "javax.crypto.Cipher.update/",
        TaintBehaviour.DISSOCIATES);
  }

  /**
   * Kotlin's standard library semantics.
   *
   * <p>Kotlin's stdlib is Java-based, so many of its functions are the same Java methods under a
   * different owner ({@code kotlin.text.StringsKt.trim} is {@code java.lang.String.trim}). The
   * behaviour is the same, and the spelling is not -- which is exactly why the table is per
   * language.
   */
  public static LanguageSemantics kotlinSemantics() {
    return LanguageSemantics.of(
        SourceLanguage.KOTLIN,
        // kotlin.text.StringsKt.* mirrors java.lang.String's behaviour.
        "kotlin.text.StringsKt.trim/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.trimIndent/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.trimStart/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.trimEnd/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.uppercase/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.lowercase/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.replace/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.substring/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.split/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.concatToString/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.format/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.padStart/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.padEnd/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.StringsKt.toByteArray/",
        TaintBehaviour.DISSOCIATES,
        "kotlin.text.StringsKt.toInt/",
        TaintBehaviour.DISSOCIATES,
        "kotlin.text.StringsKt.toLong/",
        TaintBehaviour.DISSOCIATES,
        "kotlin.text.StringsKt.toDouble/",
        TaintBehaviour.DISSOCIATES,
        "kotlin.text.StringsKt.toBoolean/",
        TaintBehaviour.DISSOCIATES,
        "kotlin.text.StringsKt.length/",
        TaintBehaviour.DISSOCIATES,
        // The Kotlin stdlib's own String extensions.
        "kotlin.text.trim/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.uppercase/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.lowercase/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.replace/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.substring/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.split/",
        TaintBehaviour.PRESERVES,
        "kotlin.text.toByteArray/",
        TaintBehaviour.DISSOCIATES,
        "kotlin.text.toInt/",
        TaintBehaviour.DISSOCIATES,
        "kotlin.text.toLong/",
        TaintBehaviour.DISSOCIATES,
        "kotlin.text.toDouble/",
        TaintBehaviour.DISSOCIATES,
        // A Kotlin String is still a java.lang.String underneath.
        "java.lang.String.trim/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.substring/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.concat/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.format/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.getBytes/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Integer.parseInt/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Long.parseLong/",
        TaintBehaviour.DISSOCIATES);
  }
}
