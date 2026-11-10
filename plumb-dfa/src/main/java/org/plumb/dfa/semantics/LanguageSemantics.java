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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.plumb.tac.ir.SourceLanguage;

/**
 * How a language's standard library behaves, expressed as data rather than as scattered string
 * comparisons.
 *
 * <p><b>What this replaces.</b> The taint engine used to ask "is this callee one of the 94 {@code
 * java.lang.*} string prefixes?" by writing the prefixes out inline, across 19 files. That
 * arrangement has three costs:
 *
 * <ol>
 *   <li><b>Adding a language means editing the engine.</b> A Kotlin frontend that wants {@code
 *       kotlin.text.StringsKt.trim} to behave like {@code String.trim} had no way to say so except
 *       by editing the Java-centric checks.
 *   <li><b>Java's semantics leak into the engine.</b> A caller cannot tell whether {@code
 *       "java.lang.String.trim/"} was a fact about strings or a fact about Java.
 *   <li><b>Every new rule re-litigates the same prefixes.</b> The set of prefixes is the real
 *       knowledge, and it was smeared across the code that happened to need it.
 * </ol>
 *
 * <p>This class makes the set of prefixes <em>the</em> knowledge: one table, one place, and a
 * language binding that says which table applies.
 *
 * <h2>What the semantics describe</h2>
 *
 * <p>Each entry classifies a callee by its <b>taint behaviour</b>, which is the only thing the
 * engine needs to know about a library call to track data flow:
 *
 * <ul>
 *   <li>{@link TaintBehaviour#PRESERVES} -- the result carries the taint of the arguments.
 *   <li>{@link TaintBehaviour#DISSOCIATES} -- the result does <em>not</em> carry the argument's
 *       taint (a hash, a length, a comparison result).
 *   <li>{@link TaintBehaviour#PRODUCES_TAINT} -- the result may be tainted regardless of its
 *       arguments (a random generator, a clock, a network read).
 *   <li>{@link TaintBehaviour#BLACK_BOX} -- the engine does not model this call at all.
 * </ul>
 *
 * <h2>Why the classification is per-language</h2>
 *
 * <p>The same conceptual operation has different names, and sometimes different behaviour, per
 * language. {@code trim} is {@code java.lang.String.trim/1} in Java and {@code
 * kotlin.text.StringsKt.trim/1} in Kotlin. The <em>behaviour</em> is the same; the spelling is not.
 * So the tables are per language, and the engine asks the language's table.
 *
 * <p>A callee that no table mentions is {@link TaintBehaviour#UNKNOWN}, which the engine treats as
 * taint-preserving -- the conservative choice for a tool that holds itself to a low false-negative
 * bar.
 */
public final class LanguageSemantics {

  /**
   * How a call affects taint.
   *
   * <p>These are the four answers the engine needs. There is deliberately no "sanitizes" category:
   * sanitization is a property of a <em>specific taint kind</em> at a <em>specific</em> call site,
   * not of the API, and it is modelled by the rule layer ({@code TaintRule}), not here.
   */
  public enum TaintBehaviour {
    /** The result carries the taint of the arguments. */
    PRESERVES,
    /** The result does not carry the arguments' taint. */
    DISSOCIATES,
    /** The result may be tainted regardless of the arguments. */
    PRODUCES_TAINT,
    /** The call is not modelled; the engine treats it as a black box. */
    BLACK_BOX,
    /** No table says; the engine treats it as taint-preserving. */
    UNKNOWN
  }

  /**
   * One rule in a language's semantics table.
   *
   * <p>An entry matches in one of two ways. A PREFIX entry matches the canonical callee key ({@code
   * owner.method/arity}), which is the form every frontend produces; a prefix rather than a full
   * key because the arity often varies with overloads and the behaviour does not. A METHOD-NAME
   * entry matches the operation alone, for the languages whose callee keys carry no owner to name
   * -- see {@link #byMethod(String, TaintBehaviour)} for why a script language needs one.
   */
  public static final class Entry {
    private final String prefix;
    private final String methodName;
    private final TaintBehaviour behaviour;

    Entry(String prefix, TaintBehaviour behaviour) {
      this(prefix, null, behaviour);
    }

    private Entry(String prefix, String methodName, TaintBehaviour behaviour) {
      this.prefix = prefix;
      this.methodName = methodName;
      this.behaviour = behaviour;
    }

    /**
     * An entry keyed by the METHOD NAME rather than by a prefix of the callee key.
     *
     * <p><b>Why a language needs it.</b> A prefix entry names the OWNER as well as the method, and
     * the owner is only there when the front end could resolve the receiver's type. In a script
     * language a string is a PRIMITIVE, and a primitive type carries no class symbol -- so {@code
     * s.trim()} has no owner to name, and the front end falls back to the module the file belongs
     * to. Measured against the TypeScript compiler: the type of a string receiver reports no symbol
     * at all, while an array receiver reports {@code Array} and a class instance reports its class.
     *
     * <p>So the operations that matter most in a script language -- the string ones -- are exactly
     * the ones a prefix entry cannot name, and a table that could only say {@code
     * java.lang.String.trim} would say nothing about them. A method-name entry names what the
     * operation IS rather than which class spells it.
     *
     * <p><b>Why the comparison ignores case.</b> The engine classifies a call by its method name in
     * the same way (see the taint engine's own method-name helpers), and the two have to agree: a
     * call the engine calls a trim must find a PRESERVES entry, or the taint stops there.
     *
     * @param methodName the method name, without the owner or the arity
     * @param behaviour the behaviour of a matching callee
     * @return the entry
     */
    public static Entry byMethod(String methodName, TaintBehaviour behaviour) {
      return new Entry(null, Objects.requireNonNull(methodName, "methodName"), behaviour);
    }

    /** The callee-key prefix this entry matches; null for a method-name entry. */
    public String prefix() {
      return prefix;
    }

    /** The method name this entry matches; null for a prefix entry. */
    public String methodName() {
      return methodName;
    }

    /** The behaviour of a matching callee. */
    public TaintBehaviour behaviour() {
      return behaviour;
    }

    /** Whether this entry matches the callee key. */
    boolean matches(String calleeSig) {
      if (prefix != null) return calleeSig.startsWith(prefix);
      return methodName.equalsIgnoreCase(methodNameOf(calleeSig));
    }
  }

  private final SourceLanguage language;
  private final List<Entry> entries;
  private final Map<String, TaintBehaviour> cache = new LinkedHashMap<>();

  private LanguageSemantics(SourceLanguage language, List<Entry> entries) {
    this.language = language;
    this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
  }

  /**
   * Builds a semantics table.
   *
   * @param language the language this table describes
   * @param entries the rules, in priority order -- the first match wins
   */
  public static LanguageSemantics of(SourceLanguage language, List<Entry> entries) {
    List<Entry> copy = new ArrayList<>();
    for (Entry e : entries) copy.add(new Entry(e.prefix(), e.behaviour()));
    return new LanguageSemantics(language, copy);
  }

  /**
   * Builds a semantics table from entries.
   *
   * <p>This is the form for a table that mixes the two kinds of entry: a prefix entry for the
   * symbols whose owner the front end resolves, and a method-name entry for the ones whose receiver
   * is a primitive and has no owner to name.
   *
   * @param language the language this table describes
   * @param entries the rules, in priority order -- the first match wins
   * @return the table
   */
  public static LanguageSemantics ofEntries(SourceLanguage language, Entry... entries) {
    List<Entry> copy = new ArrayList<>();
    for (Entry e : entries) copy.add(e);
    return new LanguageSemantics(language, copy);
  }

  /**
   * The method name of a callee key.
   *
   * <p>The key is {@code owner.method/arity}: the method is what stands between the last dot and
   * the arity.
   *
   * <p><b>Why a backslash counts as a separator too.</b> PHP qualifies a name with its namespace,
   * and a global function is written with a leading backslash: {@code system} arrives as {@code
   * \system/1} and a namespaced one as {@code App\Support\sanitize/1}. Neither has a dot, so a
   * dot-only split would name the whole thing -- or nothing at all -- and a method-name entry would
   * never match. The engine's own method-name reader splits the same way, because the two have to
   * agree: a call the engine calls a trim has to find a PRESERVES entry.
   *
   * @param calleeSig the callee key
   * @return the method name; empty when the key names none
   */
  static String methodNameOf(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return "";
    int slash = calleeSig.lastIndexOf('/');
    String head = slash < 0 ? calleeSig : calleeSig.substring(0, slash);
    int dot = head.lastIndexOf('.');
    int backslash = head.lastIndexOf('\\');
    int cut = Math.max(dot, backslash);
    return cut < 0 ? head : head.substring(cut + 1);
  }

  /**
   * Builds a semantics table from prefix/behaviour pairs.
   *
   * @param language the language this table describes
   * @param pairs alternating prefix and behaviour
   */
  public static LanguageSemantics of(SourceLanguage language, Object... pairs) {
    if (pairs.length % 2 != 0) {
      throw new IllegalArgumentException(
          "LanguageSemantics.of needs alternating prefix/behaviour pairs, got "
              + pairs.length
              + " arguments");
    }
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < pairs.length; i += 2) {
      entries.add(new Entry((String) pairs[i], (TaintBehaviour) pairs[i + 1]));
    }
    return new LanguageSemantics(language, entries);
  }

  /** The language this table describes. */
  public SourceLanguage language() {
    return language;
  }

  /** The rules, in priority order. */
  public List<Entry> entries() {
    return entries;
  }

  /**
   * The taint behaviour of a callee.
   *
   * @param calleeSig the canonical callee key (e.g. {@code java.lang.String.trim/1})
   * @return the behaviour; {@link TaintBehaviour#UNKNOWN} when no rule matches
   */
  public TaintBehaviour behaviourOf(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) {
      return TaintBehaviour.UNKNOWN;
    }
    TaintBehaviour cached = cache.get(calleeSig);
    if (cached != null) return cached;
    TaintBehaviour found = TaintBehaviour.UNKNOWN;
    for (Entry e : entries) {
      if (e.matches(calleeSig)) {
        found = e.behaviour();
        break;
      }
    }
    cache.put(calleeSig, found);
    return found;
  }

  /**
   * Whether a callee's result carries its arguments' taint.
   *
   * <p>This is the question the engine asks most often, and the answer for an unknown callee is
   * <em>yes</em>. A call the engine does not recognise is assumed to pass its input through,
   * because the alternative -- assuming it does not -- drops taint at every unknown API boundary,
   * and a dropped taint is a missed vulnerability.
   */
  public boolean preservesTaint(String calleeSig) {
    TaintBehaviour b = behaviourOf(calleeSig);
    switch (b) {
      case DISSOCIATES:
      case BLACK_BOX:
        return false;
      case PRESERVES:
      case PRODUCES_TAINT:
      case UNKNOWN:
      default:
        return true;
    }
  }

  /**
   * Whether the engine treats this callee as a black box.
   *
   * <p>A black-box call is one whose internal behaviour the engine does not model. The engine emits
   * {@link org.plumb.tac.ir.TacInstruction.Kind#UNINTERPRETED} for it rather than a plain {@link
   * org.plumb.tac.ir.TacInstruction.Kind#CALL}, so the "I do not know this" is recorded in the IR
   * instead of being implied by the absence of a summary.
   */
  public boolean isBlackBox(String calleeSig) {
    return behaviourOf(calleeSig) == TaintBehaviour.BLACK_BOX;
  }

  /**
   * Whether the callee may produce a tainted result regardless of its arguments.
   *
   * <p>Used for sources like random number generators and clock reads, where the result is
   * unpredictable but not attacker-controlled.
   */
  public boolean producesTaint(String calleeSig) {
    return behaviourOf(calleeSig) == TaintBehaviour.PRODUCES_TAINT;
  }

  /**
   * A table with no rules at all.
   *
   * <p>Every callee is {@link TaintBehaviour#UNKNOWN}, and the engine falls back to its
   * conservative default of taint-preserving.
   */
  public static LanguageSemantics empty(SourceLanguage language) {
    return new LanguageSemantics(language, List.of());
  }
}
