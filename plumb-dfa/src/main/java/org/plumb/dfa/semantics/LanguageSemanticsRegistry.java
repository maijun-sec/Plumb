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
   * <p>Java, Kotlin, Scala and C# are populated; the rest get the empty table until their semantics
   * are written down. That is the honest state: an unwritten table is not a claim that every call
   * preserves taint, it is an absence of claims.
   */
  public static LanguageSemanticsRegistry withDefaults() {
    LanguageSemanticsRegistry reg = new LanguageSemanticsRegistry();
    reg.register(javaSemantics());
    reg.register(kotlinSemantics());
    reg.register(scalaSemantics());
    reg.register(csharpSemantics());
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

  /**
   * Scala's standard library semantics.
   *
   * <p><b>Why this table exists.</b> Before it, Scala had no table at all, so every Scala callee
   * was {@link TaintBehaviour#UNKNOWN} and the engine's conservative default (taint-preserving)
   * applied to all of them. That default is safe for <em>unknown</em> APIs, but it is wrong for the
   * APIs whose whole purpose is to <em>remove</em> taint: {@code s.length}, {@code s.hashCode}. An
   * unknown callee that dissociates taint is reported as a false positive at every call site, and a
   * rule set that fires on {@code length} stops being usable.
   *
   * <p><b>Every prefix here was MEASURED, not assumed.</b> The Scala frontend resolves a call to
   * the class the method is <em>declared</em> in, so the owner of a Scala call is not always what
   * the source text suggests. The measured owners, from running the frontend over a probe of the
   * standard library's String operations:
   *
   * <ul>
   *   <li>{@code s.trim}, {@code s.substring}, {@code s.split}, {@code s.replace} and the rest of
   *       the {@code java.lang.String} operations resolve to <b>{@code java.lang.String}</b> -- a
   *       Scala {@code String} IS a {@code java.lang.String}, and these are its methods.
   *   <li>{@code s.take}, {@code s.drop}, {@code s.stripPrefix}, {@code s.splitAt} and the other
   *       {@code StringOps}-only operations resolve to <b>{@code scala.collection.StringOps}</b>,
   *       preceded by a {@code scala.Predef$.augmentString/1} that wraps the string.
   *   <li>{@code s"text=$x"} resolves to <b>{@code scala.StringContext.s/1}</b>.
   * </ul>
   *
   * <p><b>Note the {@code $extension} suffix is NOT part of the resolved name.</b> Scala 3 emits
   * these operations as static extension methods on the library class, but the frontend resolves
   * them to the <em>instance</em> method the compiler ultimately calls, so the emitted key has no
   * {@code $extension}. A prefix written against {@code ...$extension} would match nothing at all.
   *
   * <p><b>What is deliberately NOT here.</b> The table records behaviour the engine can rely on. It
   * does not try to mirror every method: an absent entry means {@link TaintBehaviour#UNKNOWN},
   * which is the conservative choice, and a table that guessed would be worse than one that
   * abstains. In particular the operations the frontend currently cannot lower -- {@code toInt},
   * {@code reverse}, {@code capitalize} and friends -- are NOT listed here: they produce no call to
   * classify, and the frontend reports them as unsupported constructs instead.
   */
  public static LanguageSemantics scalaSemantics() {
    return LanguageSemantics.of(
        SourceLanguage.SCALA,
        // --- java.lang.String: the result carries the input's taint ---
        "java.lang.String.trim/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.strip/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.substring/",
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
        "java.lang.String.toLowerCase/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.toUpperCase/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.format/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.formatted/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.join/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.valueOf/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.intern/",
        TaintBehaviour.PRESERVES,
        "java.lang.String.chars/",
        TaintBehaviour.PRESERVES,
        // --- java.lang.String: the result does NOT carry the input's taint ---
        "java.lang.String.length/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.hashCode/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.isEmpty/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.isBlank/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.contains/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.startsWith/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.endsWith/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.indexOf/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.lastIndexOf/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.compareTo/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.compareToIgnoreCase/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.matches/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.equals/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.contentEquals/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.getBytes/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.toCharArray/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.String.codePointAt/",
        TaintBehaviour.DISSOCIATES,
        // --- scala.collection.StringOps: the result carries the input's taint ---
        "scala.collection.StringOps.take/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.takeRight/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.drop/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.dropRight/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.stripPrefix/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.stripSuffix/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.splitAt/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.patch/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.map/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.partition/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.filter/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.takeWhile/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.dropWhile/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.sliding/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.mkString/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.sizeCompare/",
        TaintBehaviour.PRESERVES,
        "scala.collection.StringOps.lengthCompare/",
        TaintBehaviour.DISSOCIATES,
        "scala.collection.StringOps.sizeIs/",
        TaintBehaviour.DISSOCIATES,
        "scala.collection.StringOps.lengthIs/",
        TaintBehaviour.DISSOCIATES,
        // --- scala.Predef / LowPriorityImplicits: the implicit conversions ---
        // Every Scala file inherits these, so a call to one resolves to the Predef owner.
        // augmentString wraps a String into a StringOps, so the wrapper carries the taint.
        "scala.Predef$.augmentString/",
        TaintBehaviour.PRESERVES,
        "scala.Predef$.wrapString/",
        TaintBehaviour.PRESERVES,
        "scala.LowPriorityImplicits.wrapString/",
        TaintBehaviour.PRESERVES,
        "scala.Predef$.identity/",
        TaintBehaviour.PRESERVES,
        "scala.Predef$.copyArray/",
        TaintBehaviour.PRESERVES,
        // --- scala.StringContext: an interpolated string carries the interpolated parts ---
        // s"user=$name" is built from $name, so the result carries $name's taint.
        "scala.StringContext.s/",
        TaintBehaviour.PRESERVES,
        "scala.StringContext$.apply/",
        TaintBehaviour.PRESERVES,
        // --- scala.Char: a character derived from the string carries its taint ---
        "scala.Char$.toUpper/",
        TaintBehaviour.PRESERVES,
        "scala.Char$.toLower/",
        TaintBehaviour.PRESERVES,
        "scala.Char$.isDigit/",
        TaintBehaviour.DISSOCIATES,
        "scala.Char$.isLetter/",
        TaintBehaviour.DISSOCIATES,
        "scala.Char$.isWhitespace/",
        TaintBehaviour.DISSOCIATES,
        // --- numeric parsing: the result is a number, not the input text ---
        "java.lang.Integer.parseInt/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Long.parseLong/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Double.parseDouble/",
        TaintBehaviour.DISSOCIATES,
        "java.lang.Boolean.parseBoolean/",
        TaintBehaviour.DISSOCIATES,
        // --- exception messages: the message is derived from the exception ---
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
        "java.lang.Exception.toString/",
        TaintBehaviour.PRESERVES,
        "java.lang.RuntimeException.getMessage/",
        TaintBehaviour.PRESERVES,
        "java.lang.RuntimeException.toString/",
        TaintBehaviour.PRESERVES,
        // --- encryption: the ciphertext does not carry the plaintext's taint ---
        "javax.crypto.Cipher.doFinal/",
        TaintBehaviour.DISSOCIATES,
        "javax.crypto.Cipher.update/",
        TaintBehaviour.DISSOCIATES,
        // --- secrets: the value itself, so its taint ---
        "javax.crypto.SecretKey.getEncoded/",
        TaintBehaviour.PRESERVES,
        "java.net.PasswordAuthentication.getPassword/",
        TaintBehaviour.PRESERVES);
  }

  /**
   * C#'s standard library semantics.
   *
   * <p><b>Why this table exists.</b> C# had no table at all, so every C# callee was {@link
   * TaintBehaviour#UNKNOWN} and the engine's conservative default (taint-preserving) applied to all
   * of them. That default is safe for <em>unknown</em> APIs, but it is wrong for the APIs whose
   * whole purpose is to <em>remove</em> taint: {@code s.Length}, {@code s.GetHashCode()}. An
   * unknown callee that dissociates taint is reported as a false positive at every call site, and a
   * rule set that fires on {@code Length} stops being usable.
   *
   * <p><b>Every prefix here was MEASURED, not assumed.</b> The C# frontend resolves a call to the
   * type the method is <em>declared</em> in, and .NET spells several operations differently from
   * the source text suggests. The measured keys, from running the frontend over a probe of the .NET
   * string and IO operations:
   *
   * <ul>
   *   <li>A <b>property</b> is a method call: {@code s.Length} resolves to <b>{@code
   *       System.String.get_Length/0}</b>, not to a {@code Length} member. A table written against
   *       the property name would match nothing at all.
   *   <li>The <b>{@code +} operator</b> on strings lowers to a call to <b>{@code
   *       System.String.Concat/2}</b>. Without that entry, every string concatenation would lose
   *       its taint at the {@code +}.
   *   <li>{@code string.Format} is <b>{@code System.String.Format/2}</b> -- the arity counts the
   *       format string as well as the arguments.
   *   <li>A <b>type in a namespace that is not imported</b> resolves to its <em>unqualified</em>
   *       name: a {@code StringWriter} without {@code using System.IO;} resolves to {@code
   *       StringWriter}, and with the import to {@code System.IO.StringWriter}. The table lists the
   *       qualified form, because that is the form a real project produces.
   * </ul>
   *
   * <p><b>What is deliberately NOT here.</b> The table records behaviour the engine can rely on. It
   * does not try to mirror every method: an absent entry means {@link TaintBehaviour#UNKNOWN},
   * which is the conservative choice, and a table that guessed would be worse than one that
   * abstains.
   */
  public static LanguageSemantics csharpSemantics() {
    return LanguageSemantics.of(
        SourceLanguage.CSHARP,
        // --- System.String: the result carries the input's taint ---
        "System.String.Trim/",
        TaintBehaviour.PRESERVES,
        "System.String.TrimStart/",
        TaintBehaviour.PRESERVES,
        "System.String.TrimEnd/",
        TaintBehaviour.PRESERVES,
        "System.String.Substring/",
        TaintBehaviour.PRESERVES,
        "System.String.Replace/",
        TaintBehaviour.PRESERVES,
        "System.String.Split/",
        TaintBehaviour.PRESERVES,
        "System.String.ToUpper/",
        TaintBehaviour.PRESERVES,
        "System.String.ToUpperInvariant/",
        TaintBehaviour.PRESERVES,
        "System.String.ToLower/",
        TaintBehaviour.PRESERVES,
        "System.String.ToLowerInvariant/",
        TaintBehaviour.PRESERVES,
        "System.String.PadLeft/",
        TaintBehaviour.PRESERVES,
        "System.String.PadRight/",
        TaintBehaviour.PRESERVES,
        "System.String.Insert/",
        TaintBehaviour.PRESERVES,
        "System.String.Remove/",
        TaintBehaviour.PRESERVES,
        "System.String.Concat/",
        TaintBehaviour.PRESERVES,
        "System.String.Join/",
        TaintBehaviour.PRESERVES,
        "System.String.Format/",
        TaintBehaviour.PRESERVES,
        "System.String.Copy/",
        TaintBehaviour.PRESERVES,
        "System.String.Normalize/",
        TaintBehaviour.PRESERVES,
        "System.String.Intern/",
        TaintBehaviour.PRESERVES,
        "System.String.ToString/",
        TaintBehaviour.PRESERVES,
        "System.String.AsSpan/",
        TaintBehaviour.PRESERVES,
        // --- System.String: the result does NOT carry the input's taint ---
        // A property is a method: s.Length is System.String.get_Length/0.
        "System.String.get_Length/",
        TaintBehaviour.DISSOCIATES,
        "System.String.GetHashCode/",
        TaintBehaviour.DISSOCIATES,
        "System.String.CompareTo/",
        TaintBehaviour.DISSOCIATES,
        "System.String.CompareToIgnoreCase/",
        TaintBehaviour.DISSOCIATES,
        "System.String.Equals/",
        TaintBehaviour.DISSOCIATES,
        "System.String.Contains/",
        TaintBehaviour.DISSOCIATES,
        "System.String.StartsWith/",
        TaintBehaviour.DISSOCIATES,
        "System.String.EndsWith/",
        TaintBehaviour.DISSOCIATES,
        "System.String.IndexOf/",
        TaintBehaviour.DISSOCIATES,
        "System.String.LastIndexOf/",
        TaintBehaviour.DISSOCIATES,
        "System.String.GetBytes/",
        TaintBehaviour.DISSOCIATES,
        "System.String.ToCharArray/",
        TaintBehaviour.DISSOCIATES,
        "System.String.GetEnumerator/",
        TaintBehaviour.DISSOCIATES,
        "System.String.Compare/",
        TaintBehaviour.DISSOCIATES,
        "System.String.IsNullOrEmpty/",
        TaintBehaviour.DISSOCIATES,
        "System.String.IsNullOrWhiteSpace/",
        TaintBehaviour.DISSOCIATES,
        // --- System.Text.StringBuilder: the builder carries what was appended ---
        "System.Text.StringBuilder.Append/",
        TaintBehaviour.PRESERVES,
        "System.Text.StringBuilder.AppendLine/",
        TaintBehaviour.PRESERVES,
        "System.Text.StringBuilder.Insert/",
        TaintBehaviour.PRESERVES,
        "System.Text.StringBuilder.Replace/",
        TaintBehaviour.PRESERVES,
        "System.Text.StringBuilder.ToString/",
        TaintBehaviour.PRESERVES,
        // --- System.IO: the writer carries what was written ---
        "System.IO.StringWriter.Write/",
        TaintBehaviour.PRESERVES,
        "System.IO.StringWriter.WriteLine/",
        TaintBehaviour.PRESERVES,
        "System.IO.StringWriter.ToString/",
        TaintBehaviour.PRESERVES,
        "System.IO.TextWriter.Write/",
        TaintBehaviour.PRESERVES,
        "System.IO.TextWriter.WriteLine/",
        TaintBehaviour.PRESERVES,
        // --- numeric parsing: the result is a number, not the input text ---
        "System.Int32.Parse/",
        TaintBehaviour.DISSOCIATES,
        "System.Int64.Parse/",
        TaintBehaviour.DISSOCIATES,
        "System.Double.Parse/",
        TaintBehaviour.DISSOCIATES,
        "System.Decimal.Parse/",
        TaintBehaviour.DISSOCIATES,
        "System.Boolean.Parse/",
        TaintBehaviour.DISSOCIATES,
        "System.Int32.TryParse/",
        TaintBehaviour.DISSOCIATES,
        "System.Int64.TryParse/",
        TaintBehaviour.DISSOCIATES,
        "System.Double.TryParse/",
        TaintBehaviour.DISSOCIATES,
        "System.Decimal.TryParse/",
        TaintBehaviour.DISSOCIATES,
        "System.Boolean.TryParse/",
        TaintBehaviour.DISSOCIATES,
        // --- boxing: a numeric wrapper's text is not the number's taint ---
        "System.Int32.ToString/",
        TaintBehaviour.DISSOCIATES,
        "System.Int64.ToString/",
        TaintBehaviour.DISSOCIATES,
        "System.Double.ToString/",
        TaintBehaviour.DISSOCIATES,
        "System.Decimal.ToString/",
        TaintBehaviour.DISSOCIATES,
        "System.Boolean.ToString/",
        TaintBehaviour.DISSOCIATES,
        "System.Convert.ToString/",
        TaintBehaviour.DISSOCIATES,
        "System.Convert.ToBase64String/",
        TaintBehaviour.DISSOCIATES,
        // --- encoding: the bytes are not the text ---
        "System.Text.Encoding.GetBytes/",
        TaintBehaviour.DISSOCIATES,
        // --- exception messages: the message is derived from the exception ---
        "System.Exception.get_Message/",
        TaintBehaviour.PRESERVES,
        "System.Exception.ToString/",
        TaintBehaviour.PRESERVES,
        "System.Exception.get_StackTrace/",
        TaintBehaviour.PRESERVES,
        // --- encryption: the ciphertext does not carry the plaintext's taint ---
        "System.Security.Cryptography.SymmetricAlgorithm.Encrypt/",
        TaintBehaviour.DISSOCIATES,
        "System.Security.Cryptography.SymmetricAlgorithm.Decrypt/",
        TaintBehaviour.DISSOCIATES,
        // --- secrets: the value itself, so its taint ---
        "System.Security.Cryptography.PasswordDeriveBytes.GetBytes/",
        TaintBehaviour.PRESERVES);
  }
}
