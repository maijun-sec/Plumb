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
package org.plumb.dfa.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The rules that say an ARGUMENT's taint reaches the RESULT.
 *
 * <p><b>Why this is a table of its own.</b> There are two shapes a library method can propagate
 * taint in, and they are not the same shape:
 *
 * <ul>
 *   <li>the RECEIVER's taint reaches the result -- {@code request.getParameter("id")} returns a
 *       piece of the request. That shape is in {@code receiver-passthrough.yaml}.
 *   <li>an ARGUMENT's taint reaches the result -- {@code path.resolve(userInput)} returns a path
 *       built from the argument, and {@code String.valueOf(x)} returns x's text. That shape had no
 *       place in the model, so a rule that stated it could only be counted, never applied.
 * </ul>
 *
 * <p>This class is that place. A rule names a callee-signature pattern and the argument position
 * whose taint reaches the result.
 *
 * <p><b>Why the rule is a gate and not a fact.</b> The engine applies these rules only to callees
 * it has NO summary for, and only when the named argument is tainted. Without the table the result
 * of such a call would be clean, and the taint would stop at the call -- which is how a finding
 * goes missing. With the table applied unconditionally, every method would pass its argument's
 * taint on, and the results would be tainted where they are not. The table is what makes the
 * difference reviewable: each entry says which method, and which argument.
 *
 * <p><b>The argument index is a USER argument index.</b> It is zero-based over the arguments the
 * SOURCE wrote, so it is the same number in every language: the receiver is not one of them. The
 * engine applies the call convention itself -- an instance call keeps its receiver at {@code
 * args[0]}, so user argument {@code i} is at {@code args[i+1]}; a static call has no receiver, so
 * it is at {@code args[i]}.
 *
 * @see org.plumb.dfa.taint.TaintedCheckerBase
 */
public final class ArgumentPassthrough {

  /**
   * The rule does not name ONE argument: any user argument carries its taint to the result.
   *
   * <p>A rule that says "the arguments from position 1 onwards" is this: the taint reaches the
   * result whichever of those arguments is tainted.
   */
  public static final int ANY_ARGUMENT = -2;

  /** No rule matched the callee. */
  public static final int NO_MATCH = -1;

  /**
   * One rule: the callee signatures it matches, and the argument positions it names.
   *
   * <p>A rule may name more than one position, and then the result is tainted when any of them is.
   * That is the conservative reading of "these arguments reach the result": it can only ever add
   * taint, never remove it.
   */
  public static final class Rule {

    private final Pattern pattern;

    private final List<Integer> argIndexes;

    private final Set<SourceLanguage> languages;

    /**
     * Builds a rule that applies to every language.
     *
     * @param pattern the callee signatures the rule matches
     * @param argIndexes the user argument positions the rule names
     */
    public Rule(Pattern pattern, List<Integer> argIndexes) {
      this(pattern, argIndexes, Set.of());
    }

    /**
     * Builds a rule.
     *
     * @param pattern the callee signatures the rule matches
     * @param argIndexes the user argument positions the rule names
     * @param languages the languages the rule is for; empty means every language
     */
    public Rule(Pattern pattern, List<Integer> argIndexes, Set<SourceLanguage> languages) {
      this.pattern = Objects.requireNonNull(pattern, "pattern");
      this.argIndexes = List.copyOf(Objects.requireNonNull(argIndexes, "argIndexes"));
      this.languages =
          languages == null
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(languages));
    }

    /** The callee-signature pattern. */
    public Pattern pattern() {
      return pattern;
    }

    /** The user argument positions the rule names. */
    public List<Integer> argIndexes() {
      return argIndexes;
    }

    /** The languages the rule is for; empty means every language. */
    public Set<SourceLanguage> languages() {
      return languages;
    }

    /**
     * Whether the rule applies to code written in the given language.
     *
     * <p><b>What an unknown language answers.</b> A rule that names languages does not apply to an
     * unknown one. The alternative -- applying a JDK rule to code whose language the engine could
     * not determine -- is how a rule written for one language ends up firing on another, and the
     * engine cannot tell the two apart afterwards.
     *
     * @param language the analysed language; null means unknown
     * @return true when the rule applies
     */
    public boolean appliesTo(SourceLanguage language) {
      if (languages.isEmpty()) return true;
      return language != null && languages.contains(language);
    }
  }

  private final List<Rule> rules;

  /**
   * Builds the table.
   *
   * @param rules the rules
   */
  public ArgumentPassthrough(List<Rule> rules) {
    this.rules = rules == null ? List.of() : List.copyOf(rules);
  }

  /** The table with no rules: nothing passes through. */
  public static ArgumentPassthrough empty() {
    return EMPTY;
  }

  private static final ArgumentPassthrough EMPTY = new ArgumentPassthrough(List.of());

  /**
   * The argument positions the rule that matches the callee names.
   *
   * <p>The callee signature is compared in its DOT form: a legacy JVM signature spells the owner
   * separator as {@code #}, and the rule patterns are written in the dot form.
   *
   * @param calleeSig the callee's signature
   * @return the positions, an empty list when no rule matched
   */
  public List<Integer> argIndexesFor(String calleeSig) {
    return argIndexesFor(calleeSig, rule -> true);
  }

  /**
   * The argument positions the rule that matches the callee names, in the given language.
   *
   * <p>This is the view the engine uses: a rule that names languages applies only to those
   * languages, so a rule written for one language's library does not fire on another language's
   * code.
   *
   * @param calleeSig the callee's signature
   * @param language the analysed language; null means unknown
   * @return the positions, an empty list when no rule matched
   */
  public List<Integer> argIndexesFor(String calleeSig, SourceLanguage language) {
    return argIndexesFor(calleeSig, rule -> rule.appliesTo(language));
  }

  /**
   * The argument positions the rule that matches the callee names.
   *
   * @param calleeSig the callee's signature
   * @param accept which rules are considered
   * @return the positions, an empty list when no rule matched
   */
  private List<Integer> argIndexesFor(String calleeSig, Predicate<Rule> accept) {
    if (calleeSig == null || calleeSig.isEmpty() || rules.isEmpty()) {
      return List.of();
    }
    String normalized = calleeSig.replace('#', '.');
    for (Rule rule : rules) {
      if (!accept.test(rule)) continue;
      if (rule.pattern().matcher(normalized).find()) {
        return rule.argIndexes();
      }
    }
    return List.of();
  }

  /**
   * The user argument positions whose taint the matching rule sends to the result.
   *
   * <p>The positions are resolved against the call's argument list: an instance call keeps its
   * receiver at {@code args[0]}, so user argument {@code i} is at {@code args[i+1]}; a static call
   * has no receiver, so it is at {@code args[i]}. The instance position is tried first and the
   * static one second, which is the same two-tier convention the summary fold uses.
   *
   * @param calleeSig the callee's signature
   * @param args the call's arguments
   * @return the TAC argument positions, empty when no rule matched or no position is present
   */
  public List<Integer> tacArgIndexesFor(String calleeSig, List<?> args) {
    return tacArgIndexesFor(argIndexesFor(calleeSig), args);
  }

  /**
   * The user argument positions whose taint the matching rule sends to the result, in the given
   * language.
   *
   * <p>This is the view the engine uses. A rule that names languages applies only to those
   * languages, so a rule written for one language's library does not carry taint across a call in
   * another language.
   *
   * @param calleeSig the callee's signature
   * @param args the call's arguments
   * @param language the analysed language; null means unknown
   * @return the TAC argument positions, empty when no rule matched or no position is present
   */
  public List<Integer> tacArgIndexesFor(String calleeSig, List<?> args, SourceLanguage language) {
    return tacArgIndexesFor(argIndexesFor(calleeSig, language), args);
  }

  /**
   * The user argument positions whose taint the matching rule sends to the result.
   *
   * @param named the user argument positions the matching rule names
   * @param args the call's arguments
   * @return the TAC argument positions, empty when no rule matched or no position is present
   */
  private List<Integer> tacArgIndexesFor(List<Integer> named, List<?> args) {
    if (named.isEmpty() || args == null || args.isEmpty()) {
      return List.of();
    }
    List<Integer> resolved = new ArrayList<>();
    if (named.contains(ANY_ARGUMENT)) {
      // Any user argument. The receiver is not one of them, so it is skipped, and the
      // static convention is covered by the same walk.
      for (int index = 1; index < args.size(); index++) {
        resolved.add(index);
      }
      if (args.size() == 1) {
        // A static call with a single argument: the argument is at args[0].
        resolved.add(0);
      }
      return resolved;
    }
    for (Integer named1 : named) {
      int instancePosition = named1 + 1;
      if (instancePosition < args.size()) {
        resolved.add(instancePosition);
        continue;
      }
      if (named1 < args.size()) {
        resolved.add(named1);
      }
    }
    return Collections.unmodifiableList(resolved);
  }

  /** The number of rules. */
  public int size() {
    return rules.size();
  }
}
