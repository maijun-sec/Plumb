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
package org.plumb.dfa.taint;

import java.util.Objects;
import org.plumb.tac.ir.TacType;

/**
 * Taint value domain.
 *
 * <p>A {@code TaintDomain} describes the <em>kind of value</em> a taint fact carries. Different
 * sinks consume different domains: SQL {@code execute} consumes a {@link StringTaint} (the SQL
 * query is a string), {@code malloc} consumes a {@link NumericTaint} (the size is a number), {@code
 * FileOutputStream.write} consumes a {@link PointerTaint} (the receiver is a resource handle).
 * Mixing a {@link StringTaint} into a {@code malloc} is meaningless; the domain lets the engine
 * short-circuit obvious non-matches and keep findings precise.
 *
 * <h2>Why a sealed interface and not a string</h2>
 *
 * <p>The config layer carries the domain as a free-form string on {@code
 * CheckerConfig.taintDomain}; promoting that to a sealed sum type here means a checker can
 * pattern-match on it without a typo in a string literal (the compiler exhaustiveness check catches
 * the missing case). The YAML / config layer keeps the string form for human readability; the
 * runtime layer promotes it to this enum-style type the first time a checker reads it.
 *
 * <h2>Cross-domain propagation</h2>
 *
 * <p>A value that goes through a string-conversion (e.g. {@code Integer.toString(t)}) can turn a
 * {@link NumericTaint} into a {@link StringTaint}. The TaintedCheckerBase records both into the
 * summary so a downstream sink matcher can still see "this value carries taint, regardless of what
 * domain". The domain is a hint, not a gate.
 *
 * <h2>Subtypes</h2>
 *
 * <ul>
 *   <li>{@link StringTaint} -- a string (or anything that flows as a String through the program).
 *       Used by SQL, LDAP, command, format-string, log-injection, XSS, and path-traversal sinks.
 *   <li>{@link NumericTaint} -- a number (int / long / float / double). Used by array-index,
 *       allocation-size, divide-by-zero, and integer-overflow sinks.
 *   <li>{@link PointerTaint} -- a pointer / handle that the engine can dereference. Used by
 *       null-deref, use-after-free, and resource leak sinks.
 *   <li>{@link MixedTaint} -- a value that has been observed to participate in more than one of the
 *       above. The base class promotes to this when a checker can't decide a single domain (e.g. a
 *       value that is sometimes a number and sometimes a string). Sinks that don't care about
 *       domain see this the same as any other domain.
 * </ul>
 */
public sealed interface TaintDomain
    permits TaintDomain.StringTaint,
        TaintDomain.NumericTaint,
        TaintDomain.PointerTaint,
        TaintDomain.MixedTaint {

  /** Human-readable label used in finding messages. */
  String label();

  /**
   * "A string" -- text flowing through {@code String} references, {@code CharSequence} chains,
   * string-concat operations, and string-typed parameters. The most common domain: SQL, LDAP,
   * command, format string, XSS, log, path traversal all live here.
   */
  record StringTaint() implements TaintDomain {
    public static final StringTaint INSTANCE = new StringTaint();

    @Override
    public String label() {
      return "STRING";
    }
  }

  /**
   * "A number" -- int / long / float / double. Drives array-index, allocation-size, divide-by-zero,
   * integer-overflow checks. String → Number conversions ({@code Integer.parseInt}) can also
   * produce a NumericTaint from a StringTaint source.
   */
  record NumericTaint() implements TaintDomain {
    public static final NumericTaint INSTANCE = new NumericTaint();

    @Override
    public String label() {
      return "NUMERIC";
    }
  }

  /**
   * "A pointer / handle" -- a reference that can be dereferenced (load, store, method call on a
   * receiver) or closed (file, socket). Drives null-deref, use-after-free, resource-leak checks.
   * PointerTaint is typically introduced by an explicit {@code new} (allocation) and consumed by an
   * explicit {@code close} / {@code free}.
   */
  record PointerTaint() implements TaintDomain {
    public static final PointerTaint INSTANCE = new PointerTaint();

    @Override
    public String label() {
      return "POINTER";
    }
  }

  /**
   * "A value with multiple possible domains" -- the engine observed a value being used as a string
   * AND as a number (or pointer), and can't pick a single one. Sinks that care about a specific
   * domain should treat MixedTaint as "this MIGHT match"; sinks that don't care (e.g.
   * sensitive-data exfiltration) match it the same as any other domain.
   *
   * <p>MixedTaint is also the conservative default when a checker can't decide a domain from the IR
   * alone (e.g. a value whose declaration type is a generic {@code T} with no bound).
   */
  record MixedTaint() implements TaintDomain {
    public static final MixedTaint INSTANCE = new MixedTaint();

    @Override
    public String label() {
      return "MIXED";
    }
  }

  /**
   * Parse a domain from the string form used in YAML / {@code CheckerConfig.taintDomain}.
   * Case-insensitive. Returns {@link MixedTaint} for an unknown / null / empty string -- the safe
   * default that a checker can always treat as "this might match".
   */
  static TaintDomain parse(String label) {
    if (label == null || label.isEmpty()) return MixedTaint.INSTANCE;
    String u = label.trim().toUpperCase(java.util.Locale.ROOT);
    return switch (u) {
      case "STRING" -> StringTaint.INSTANCE;
      case "NUMERIC", "INT", "LONG", "NUMBER" -> NumericTaint.INSTANCE;
      case "POINTER", "REFERENCE", "HANDLE" -> PointerTaint.INSTANCE;
      case "MIXED", "ANY" -> MixedTaint.INSTANCE;
      default -> MixedTaint.INSTANCE;
    };
  }

  /**
   * Promote two domains. If they're equal, the result is that domain. If they differ, the result is
   * {@link MixedTaint} -- the "couldn't decide" answer.
   */
  static TaintDomain join(TaintDomain a, TaintDomain b) {
    if (a == null) return b;
    if (b == null) return a;
    if (a.equals(b)) return a;
    return MixedTaint.INSTANCE;
  }

  /**
   * Whether a domain can flow into a sink expecting {@code expected}. The match is inclusive: a
   * {@link MixedTaint} can flow anywhere; a {@link StringTaint} can flow into a StringTaint sink,
   * but not into a NumericTaint sink (caller decides whether to be conservative).
   */
  default boolean flowsInto(TaintDomain expected) {
    Objects.requireNonNull(expected, "expected");
    if (this.equals(MixedTaint.INSTANCE)) return true;
    if (expected.equals(MixedTaint.INSTANCE)) return true;
    return this.equals(expected);
  }

  /**
   * Whether a rule that consumes THIS domain asks about the KIND of the tainted value.
   *
   * <p>A rule that consumes a {@link StringTaint} or a {@link NumericTaint} is asking "is this
   * value a string / a number" -- the tainted thing IS the value that reaches the sink. So the
   * fact's kind is the answer, and a mismatch is a provable non-match.
   *
   * <p>A rule that consumes a {@link PointerTaint} is asking something else: whether the RECEIVER
   * is null, or already closed. The tainted thing there is a handle, and the kind of the value the
   * handle carries says nothing about whether the handle is usable. A rule that consumes a {@link
   * MixedTaint} does not discriminate at all.
   *
   * <p>⇒ So the fact's kind is consulted only against a value-domain rule. That is the conservative
   * direction: a rule that does not ask about the kind never loses a finding to it.
   *
   * @return true when this domain makes the fact's kind relevant
   */
  default boolean asksAboutValueKind() {
    return this.equals(StringTaint.INSTANCE) || this.equals(NumericTaint.INSTANCE);
  }

  /**
   * The kind of value a type denotes.
   *
   * <p>This is the one place that decides it, because the answer is a property of the TYPE and
   * every writer of a fact needs the same answer.
   *
   * <p>The mapping is deliberately coarse and deliberately one-sided:
   *
   * <ul>
   *   <li>{@link TacType.Kind#STRING} and a class reference to {@code java.lang.String} are {@link
   *       StringTaint}.
   *   <li>The integral and floating kinds are {@link NumericTaint}.
   *   <li>Everything else -- every other class reference, every array, {@code char}, {@code
   *       boolean}, {@code null}, {@code void}, and an unknown type -- is {@link MixedTaint}, which
   *       flows into every sink.
   * </ul>
   *
   * <p>Two of those deserve their reason. A class reference other than {@code String} could be a
   * string under another name, or a handle, or neither, so the engine does not guess. And {@code
   * char} is neither: it is not a number and it is not a string, so treating it as either would be
   * a claim the type does not support.
   *
   * @param type the type of the variable the tainted value sits in; {@code null} for "no type"
   * @return the kind of value that type denotes
   */
  static TaintDomain of(TacType type) {
    if (type == null) return MixedTaint.INSTANCE;
    TacType.Kind kind = type.kind();
    if (kind == null) return MixedTaint.INSTANCE;
    switch (kind) {
      case STRING -> {
        return StringTaint.INSTANCE;
      }
      case INT, LONG, SHORT, BYTE, FLOAT, DOUBLE, DECIMAL -> {
        return NumericTaint.INSTANCE;
      }
      case CLASS -> {
        String fqn = type.className();
        return "java.lang.String".equals(fqn) ? StringTaint.INSTANCE : MixedTaint.INSTANCE;
      }
      default -> {
        return MixedTaint.INSTANCE;
      }
    }
  }
}
