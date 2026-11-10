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
package org.plumb.dfa.engine;

import java.util.HashMap;
import java.util.Map;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The identity of the lock a lock/unlock call operates on.
 *
 * <p><b>Why the receiver's NAME is not the lock's name.</b> The receiver of a {@code .lock()} /
 * {@code .unlock()} call is whatever expression the call was written on, and two things stand
 * between that expression and a stable identity:
 *
 * <ul>
 *   <li>A FIELD is read into a FRESH TEMPORARY. {@code c.mu.Lock()} and {@code c.mu.Unlock()} read
 *       the field twice, so the two calls have two different receivers -- two different
 *       temporaries. A checker that compares the receiver's NAME pairs nothing, and reports every
 *       correctly written {@code mu.Lock(); defer mu.Unlock()} as an unlock without a lock. This is
 *       not a Go shape: the Java front end lowers a field read the same way, so an instance field
 *       locked and unlocked in one method has the same defect.
 *   <li>The SSA version suffix, which is not part of the source-level name.
 * </ul>
 *
 * <p><b>What this resolves.</b> A receiver that a field load defines is named after the field and
 * the object it was read from ({@code mu@c}); everything else is named after the variable it is.
 * The field path is what makes the identity stable ACROSS functions, which is what a cross-function
 * comparison needs: two methods of the same type that lock {@code c.mu} agree on it, and two
 * methods that lock {@code c.mu} and {@code c.other} do not.
 *
 * <p><b>What this does not do.</b> It does not decide whether a lock is HELD. A lock acquired in
 * one function and released in another is a pattern the callers have to carry; this only says which
 * lock a call is about.
 */
public final class LockIdentity {

  /** How deep a field path may go before it is treated as a cycle rather than followed. */
  private static final int MAX_DEPTH = 8;

  private LockIdentity() {}

  /**
   * A resolver bound to one function.
   *
   * <p>The field loads are collected ONCE, when the resolver is built, because a checker that looks
   * at many calls in one function would otherwise rebuild the map for every one of them.
   */
  public static final class Resolver {

    private final Map<String, TacInstruction> fieldLoads = new HashMap<>();

    /** Builds a resolver over one function's instructions. */
    public Resolver(TacFunction fn) {
      if (fn == null) {
        return;
      }
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind != TacInstruction.Kind.FIELDLOAD) {
          continue;
        }
        if (ins.fieldName == null || !(ins.dest instanceof TacOperand.TacVar tv)) {
          continue;
        }
        fieldLoads.put(tv.name(), ins);
      }
    }

    /**
     * The identity of the lock the receiver names.
     *
     * @param receiver the call's receiver operand
     * @return the identity, or null when the receiver names nothing that can be a lock
     */
    public String of(TacOperand receiver) {
      if (receiver == null) {
        return null;
      }
      if (receiver instanceof TacOperand.Const c) {
        return c.value() == null ? null : "const:" + c.value();
      }
      if (receiver instanceof TacOperand.TacVar tv) {
        return resolve(tv, 0);
      }
      return null;
    }

    private String resolve(TacOperand.TacVar v, int depth) {
      if (depth > MAX_DEPTH) {
        return stripVersion(v.name());
      }
      TacInstruction load = fieldLoads.get(v.name());
      if (load == null) {
        return stripVersion(v.name());
      }
      String base = base(load.lhs1, depth + 1);
      return base == null ? load.fieldName : load.fieldName + "@" + base;
    }

    private String base(TacOperand op, int depth) {
      if (op == null) {
        return null;
      }
      if (op instanceof TacOperand.TacVar tv) {
        return resolve(tv, depth);
      }
      if (op instanceof TacOperand.Const c) {
        return c.value() == null ? null : "const:" + c.value();
      }
      return null;
    }
  }

  /** The source-level name: the SSA version suffix is not part of it. */
  private static String stripVersion(String name) {
    if (name == null) {
      return null;
    }
    int colon = name.indexOf(':');
    return colon >= 0 ? name.substring(0, colon) : name;
  }
}
