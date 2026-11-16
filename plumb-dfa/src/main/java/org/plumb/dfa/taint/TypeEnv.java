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

import java.util.LinkedHashMap;
import java.util.Map;
import org.plumb.dfa.types.CalleeReturnTypes;
import org.plumb.dfa.types.TypeSet;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * What the data flow knows about the TYPE of each value in the current function.
 *
 * <p><b>Why the analysis needs this.</b> The taint domain is a string domain: the engine treats
 * every tainted value as a string. That is right most of the time and wrong in a specific,
 * observable way -- a value that is provably a NUMBER is not a string, so a tainted number reaching
 * a sink that needs a string is not that vulnerability. Without a type in the data flow, the engine
 * has no way to say so, and reports it.
 *
 * <p><b>Why it is per function and never shrinks.</b> The state is cleared at every function
 * boundary, like the taint state beside it. Within a function it only grows: the analysis is
 * path-insensitive, so a value written on one path may still be the other one after the paths join,
 * and a type observed once cannot be taken back. That is the same discipline the taint state
 * follows -- see the note in {@code propagateMOVE} about keeping a prior {@code param:} label
 * across a path join.
 *
 * <p><b>Where the types come from.</b>
 *
 * <ul>
 *   <li>The DECLARED type of a variable -- what the programmer wrote. {@code x: int = ...} names an
 *       int, and that is exact.
 *   <li>The type of a value the instruction moved -- a literal carries its own type, so {@code x =
 *       42} says x is an int even though nothing declared it.
 *   <li>The type of the variable a value was copied from.
 * </ul>
 *
 * <p><b>Why the whole function is scanned at once, and not as the walk proceeds.</b> The type a
 * value has AT a sink is what the gate asks about, and a walk that learned types as it went would
 * answer with the types it happened to meet first. That answer depends on the order the
 * instructions are visited in, and the order is not the source order: a loop body can be laid out
 * after the code that uses what the loop assigned. So a value that is an int on one path and a
 * string on another would be seen as whichever came first, and the finding could be suppressed on a
 * path the walk never reached.
 *
 * <p>So the scan covers the WHOLE function and every type a variable may hold is kept. The cost is
 * one pass over the instructions, and it is paid only by the functions that actually reach a sink
 * with a declared type -- see {@link #resolve(TacFunction)}.
 */
public final class TypeEnv {

  /**
   * How many times the scan repeats while it keeps learning.
   *
   * <p>The types of copies can depend on each other ({@code a = 1; b = a; c = b}), so one pass is
   * not always enough. The state only grows and the lattice is finite, so the scan converges; the
   * cap is there so a pathological function cannot make it spin.
   */
  private static final int MAX_ROUNDS = 8;

  private final Map<TacOperand.TacVar, TypeSet> types = new LinkedHashMap<>();

  /** Whether the function has been scanned yet. The scan happens at most once per function. */
  private boolean resolved;

  /** Forget everything. Called at every function boundary. */
  public void clear() {
    types.clear();
    resolved = false;
  }

  /**
   * Scan the function and record every type the IR shows for its values.
   *
   * <p>Calling this twice for the same function does nothing the second time, so the callers can
   * ask for the types without keeping a flag of their own.
   *
   * @param fn the function
   */
  public void resolve(TacFunction fn) {
    resolve(fn, null);
  }

  /**
   * Scan the function and record every type the IR shows for its values.
   *
   * <p>The callee index is what tells the environment the type a CALL produces: the destination of
   * a call has no type of its own in the IR, because the type of a result is a property of the
   * callee's declaration and not of the call site.
   *
   * @param fn the function
   * @param calleeReturnTypes the program's callees, or null when the program declares none
   */
  public void resolve(TacFunction fn, CalleeReturnTypes calleeReturnTypes) {
    if (resolved || fn == null) return;
    resolved = true;
    for (TacOperand.TacVar p : fn.params()) {
      observe(p, p.type());
    }
    for (TacOperand.TacVar l : fn.locals()) {
      observe(l, l.type());
    }
    for (int round = 0; round < MAX_ROUNDS; round++) {
      boolean learned = false;
      for (TacInstruction ins : fn.instructions()) {
        learned |= observeInstruction(ins, calleeReturnTypes);
      }
      if (!learned) return;
    }
  }

  /**
   * Record what one instruction says about the types of its values.
   *
   * @param ins the instruction
   * @return whether anything new was learned
   */
  private boolean observeInstruction(TacInstruction ins, CalleeReturnTypes calleeReturnTypes) {
    boolean learned = false;
    if (ins.dest instanceof TacOperand.TacVar dv) {
      // The DECLARED type of the destination is what the programmer wrote, and it is exact.
      learned |= observe(dv, dv.type());
      // A copy carries the type of what it copied, which is how a literal reaches a variable
      // ({@code x = 42} says x is an int even though nothing declared it).
      if (ins.kind == TacInstruction.Kind.MOVE && ins.lhs1 != null) {
        learned |= observeFrom(dv, ins.lhs1);
      }
      // A CALL produces a value whose type is the CALLEE's return type. The
      // destination of the call does not carry it in the IR, so it is learned
      // here -- from the callee's declaration, which is the only place the
      // answer is written down.
      if (calleeReturnTypes != null && ins.kind == TacInstruction.Kind.CALL) {
        TacType returned = calleeReturnTypes.returnTypeOf(ins.calleeSig);
        if (returned != null) {
          learned |= observe(dv, returned);
        }
      }
    }
    if (ins.args != null) {
      for (TacOperand arg : ins.args) {
        if (arg instanceof TacOperand.TacVar av) {
          learned |= observe(av, av.type());
        }
      }
    }
    return learned;
  }

  /**
   * Record the type a copy carries.
   *
   * @param dest the variable the value was copied into
   * @param source the value that was copied
   * @return whether anything new was learned
   */
  private boolean observeFrom(TacOperand.TacVar dest, TacOperand source) {
    if (source instanceof TacOperand.Const c) {
      return observe(dest, c.type());
    }
    if (source instanceof TacOperand.TacVar sv) {
      boolean learned = observe(dest, sv.type());
      TypeSet known = types.get(sv);
      if (known != null) {
        learned |= observe(dest, known);
      }
      return learned;
    }
    return false;
  }

  /**
   * Record that a variable was seen holding a type.
   *
   * <p>An UNKNOWN type is not recorded: it says nothing, and recording it would make the set look
   * like it knows something. A variable the analysis knows nothing about therefore has NO entry,
   * and an empty set never suppresses anything.
   *
   * @param variable the variable
   * @param type the type, or null for none
   * @return whether anything new was learned
   */
  public boolean observe(TacOperand.TacVar variable, TacType type) {
    if (variable == null || type == null) return false;
    if (type.kind() == TacType.Kind.UNKNOWN) return false;
    TypeSet set = types.computeIfAbsent(variable, v -> TypeSet.empty());
    int before = set.size();
    set.add(type);
    return set.size() != before;
  }

  /**
   * Record that a variable was seen holding every type of another set.
   *
   * @param variable the variable
   * @param addition the types, or null for none
   * @return whether anything new was learned
   */
  public boolean observe(TacOperand.TacVar variable, TypeSet addition) {
    if (variable == null || addition == null || addition.isEmpty()) return false;
    TypeSet set = types.computeIfAbsent(variable, v -> TypeSet.empty());
    int before = set.size();
    set.addAll(addition);
    return set.size() != before;
  }

  /**
   * The types a variable may hold.
   *
   * @param variable the variable
   * @return the types; empty when the analysis has seen none
   */
  public TypeSet typesOf(TacOperand.TacVar variable) {
    if (variable == null) return TypeSet.empty();
    TypeSet known = types.get(variable);
    return known == null ? TypeSet.empty() : known;
  }

  /**
   * Whether the analysis has seen a type for this variable at all.
   *
   * @param variable the variable
   * @return whether anything is known
   */
  public boolean knows(TacOperand.TacVar variable) {
    TypeSet known = types.get(variable);
    return known != null && !known.isEmpty();
  }
}
