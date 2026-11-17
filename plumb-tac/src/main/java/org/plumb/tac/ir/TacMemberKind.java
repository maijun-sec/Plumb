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

/**
 * What KIND of member a function is.
 *
 * <p>A function in the IR does not say whether it is a method, a constructor, a getter, or a field
 * initializer. That sounds like a detail, but it decides three things a checker cares about:
 *
 * <ul>
 *   <li>a getter is a READ of a field, and a constructor is not a call anybody makes -- reporting a
 *       finding on a constructor's own body is usually a false positive;
 *   <li>a static initializer runs once, before any main work, so a rule about "user input reaching
 *       a sink" does not apply to it in the same way;
 *   <li>a field initializer is a VALUE, not a call.
 * </ul>
 *
 * <p>{@link #UNKNOWN} means "the front end said something this build does not know". {@code null}
 * -- the state of a function from a cache written before this field existed -- means "the front end
 * said nothing at all". The two are different facts and the accessor keeps them apart.
 *
 * <p>IR-serializable: this class participates in the disk-cache contract (IrSerializer).
 */
public enum TacMemberKind implements Serializable {
  /** An ordinary method. */
  METHOD,
  /** A constructor. */
  CONSTRUCTOR,
  /** A property getter. */
  GETTER,
  /** A property setter. */
  SETTER,
  /** An instance field initializer. */
  FIELD_INITIALIZER,
  /** A static field initializer, or a static initialization block. */
  STATIC_INITIALIZER,
  /** A plain function with no owner. */
  FUNCTION,
  /** A module-level function that holds the file's top-level statements. */
  MODULE,
  /** The front end said something this build does not know. */
  UNKNOWN;

  private static final long serialVersionUID = 1L;

  /**
   * The kind a wire {@code memberKind} names.
   *
   * <p>An unrecognized name reads as {@link #UNKNOWN}, never as {@link #METHOD}: claiming a member
   * is a method when it is not would make a checker apply method rules to a constructor.
   */
  public static TacMemberKind fromWire(String name) {
    if (name == null || name.isEmpty()) {
      return UNKNOWN;
    }
    for (TacMemberKind k : values()) {
      if (k.name().equals(name)) {
        return k;
      }
    }
    return UNKNOWN;
  }
}
