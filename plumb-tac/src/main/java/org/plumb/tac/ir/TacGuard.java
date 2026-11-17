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

import java.io.Serializable;
import java.util.Objects;

/**
 * A GUARD: the condition a branch tests.
 *
 * <p>A branch in the TAC says "if this value is true, go here; otherwise go there". That is enough
 * to build a control-flow graph, but it is not enough to say what the branch PROVES. A guard says
 * it: "if {@code x} is equal to {@code 3}, go here".
 *
 * <p><b>Why it matters.</b> The engine infers many facts from the values that flow into a sink. A
 * guard is one of the few places where the program itself states such a fact, and a checker that
 * could read it would not have to re-derive it. The guard is therefore a RECORD of what the source
 * said, not an inference: nothing here is allowed to make the engine less cautious.
 *
 * <p><b>Directional rule.</b> A guard may only ever REMOVE a finding, never add one. It is a
 * statement the program made about its own values.
 *
 * <p>IR-serializable: this class participates in the disk-cache contract (IrSerializer).
 */
public final class TacGuard implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Which kind of guard this is. */
  public enum Kind {
    /** The subject must be truthy. */
    TRUTHINESS,
    /** The subject is compared with a value. */
    COMPARE,
    /** A property must be present on the subject. */
    PROPERTY_PRESENCE
  }

  private final Kind kind;
  private final TacOperand subject;
  private final BinaryOperator op;
  private final TacOperand value;
  private final String propertyName;

  private TacGuard(
      Kind kind, TacOperand subject, BinaryOperator op, TacOperand value, String propertyName) {
    this.kind = Objects.requireNonNull(kind);
    this.subject = subject;
    this.op = op;
    this.value = value;
    this.propertyName = propertyName;
  }

  /** A guard of the form "the subject must be truthy". */
  public static TacGuard truthiness(TacOperand subject) {
    return new TacGuard(Kind.TRUTHINESS, subject, null, null, null);
  }

  /** A guard of the form "the subject compared with the value". */
  public static TacGuard compare(TacOperand subject, BinaryOperator op, TacOperand value) {
    return new TacGuard(Kind.COMPARE, subject, op, value, null);
  }

  /** A guard of the form "the subject must carry the property". */
  public static TacGuard propertyPresence(TacOperand subject, String propertyName) {
    return new TacGuard(Kind.PROPERTY_PRESENCE, subject, null, null, propertyName);
  }

  /** Which kind of guard this is. Never null. */
  public Kind kind() {
    return kind;
  }

  /** What the guard is about, or {@code null} when the front end did not say. */
  public TacOperand subject() {
    return subject;
  }

  /** The comparison, for a {@link Kind#COMPARE} guard; {@code null} otherwise. */
  public BinaryOperator op() {
    return op;
  }

  /** The right-hand side, for a {@link Kind#COMPARE} guard; {@code null} otherwise. */
  public TacOperand value() {
    return value;
  }

  /** The property name, for a {@link Kind#PROPERTY_PRESENCE} guard; {@code null} otherwise. */
  public String propertyName() {
    return propertyName;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TacGuard g)) {
      return false;
    }
    return kind == g.kind
        && Objects.equals(subject, g.subject)
        && op == g.op
        && Objects.equals(value, g.value)
        && Objects.equals(propertyName, g.propertyName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, subject, op, value, propertyName);
  }

  @Override
  public String toString() {
    return switch (kind) {
      case TRUTHINESS -> "truthy(" + subject + ")";
      case COMPARE -> "(" + subject + " " + op + " " + value + ")";
      case PROPERTY_PRESENCE -> "has(" + subject + "." + propertyName + ")";
    };
  }
}
