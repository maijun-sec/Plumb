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
package org.plumb.dfa.summary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Whether the value a function RETURNS may depend on something the caller supplied.
 *
 * <h2>Why the question is asked</h2>
 *
 * <p>The conservative caller-arg to dest rule answers "the caller passed a tainted argument and the
 * callee's result may carry it, so the call's destination inherits the taint". The rule is only
 * useful when the second half is true, and the rule's own comment says so: it "does not over-taint
 * because the gate ensures only functions whose result may depend on their arguments are
 * candidates".
 *
 * <p>The gate did not do that. It asked whether the callee had a body at all, which is true of
 * almost every function, so the rule fired on every call whose argument was tainted -- including
 * the calls whose result provably cannot carry it. The question this class answers is the one the
 * comment describes.
 *
 * <h2>How the answer is reached</h2>
 *
 * <p>The returned value is followed BACKWARDS through the instructions that define it, and the
 * answer is "yes" when that walk reaches
 *
 * <ul>
 *   <li>a formal parameter -- the caller's argument reaches the result;
 *   <li>an instruction whose result may carry anything -- a call, a field read, an array read, an
 *       unknown value. A field read is the getter pattern: the receiver's state reaches the result,
 *       and the receiver is one of the caller's arguments.
 * </ul>
 *
 * <p>The answer is "no" only when the returned value is built from constants alone, possibly
 * through locals: {@code return "constant";}, {@code String y = "k"; return y;}. Such a result
 * cannot carry the caller's argument, whatever the rest of the body does.
 *
 * <h2>Why the answer is never wrong in the direction that loses a finding</h2>
 *
 * <p>Every shape the walk does not understand answers "yes". Only {@code MOVE}, {@code BINOP} and
 * {@code UNARYOP} are followed, and they are followed into their operands; every other opcode -- a
 * call, a field read, an array read, an unknown value -- answers "yes" on its own. A variable with
 * no definition in the function answers "yes" too, because where it came from is not something this
 * walk can see.
 *
 * <p>The TAC has no version on a variable, so a name can be assigned more than once. Every
 * definition of a name is therefore followed, and the answers are combined with "or": if any
 * definition reaches an input, the name may carry it.
 *
 * <p>So the answer is "no" only when the value provably cannot carry the caller's argument, and
 * "yes" in every case where the walk is not sure.
 */
public final class ReturnDependency {

  private ReturnDependency() {}

  /**
   * Whether the value a function returns may depend on something the caller supplied.
   *
   * <p>That is a formal parameter, or the receiver's state -- the receiver is one of the caller's
   * arguments too, and a getter is the shape that reads it.
   *
   * @param fn the function to analyse
   * @return true when the returned value may carry an input; false when it provably cannot
   */
  public static boolean mayDependOnArgument(TacFunction fn) {
    if (fn == null) return false;
    Set<String> paramNames = new HashSet<>();
    for (TacOperand.TacVar p : fn.params()) {
      if (p != null) paramNames.add(p.name());
    }
    Map<String, List<TacInstruction>> defs = new HashMap<>();
    for (TacInstruction ins : fn.instructions()) {
      if (ins == null) continue;
      if (ins.dest instanceof TacOperand.TacVar v) {
        defs.computeIfAbsent(v.name(), k -> new ArrayList<>()).add(ins);
      }
    }
    for (TacInstruction ins : fn.instructions()) {
      if (ins == null || ins.kind != TacInstruction.Kind.RETURN) continue;
      TacOperand returned = ins.lhs1;
      // A return with no operand returns nothing: there is no value to carry an input.
      if (returned == null) continue;
      if (dependsOnInput(returned, paramNames, defs, new HashSet<>())) return true;
    }
    return false;
  }

  /**
   * Whether the value an operand carries may come from an input.
   *
   * @param op the operand to look at
   * @param paramNames the names of the formal parameters
   * @param defs the instructions that define each name
   * @param visiting the names on the current walk, for the cycle guard
   * @return true when the value may carry an input
   */
  private static boolean dependsOnInput(
      TacOperand op,
      Set<String> paramNames,
      Map<String, List<TacInstruction>> defs,
      Set<String> visiting) {
    if (op instanceof TacOperand.Const) return false;
    if (!(op instanceof TacOperand.TacVar v)) {
      // An operand shape this walk does not know: assume it carries something.
      return true;
    }
    String name = v.name();
    if (paramNames.contains(name)) return true;
    if (!visiting.add(name)) {
      // The name defines itself: the walk cannot get past it, so assume the worst.
      return true;
    }
    List<TacInstruction> defsOfName = defs.get(name);
    if (defsOfName == null || defsOfName.isEmpty()) {
      // No definition in this function: where the value came from is not visible here.
      visiting.remove(name);
      return true;
    }
    boolean depends = false;
    for (TacInstruction def : defsOfName) {
      if (dependsOnInstruction(def, paramNames, defs, visiting)) {
        depends = true;
        break;
      }
    }
    visiting.remove(name);
    return depends;
  }

  /**
   * Whether the value an instruction computes may come from an input.
   *
   * @param ins the instruction to look at
   * @param paramNames the names of the formal parameters
   * @param defs the instructions that define each name
   * @param visiting the names on the current walk, for the cycle guard
   * @return true when the value may carry an input
   */
  private static boolean dependsOnInstruction(
      TacInstruction ins,
      Set<String> paramNames,
      Map<String, List<TacInstruction>> defs,
      Set<String> visiting) {
    switch (ins.kind) {
      case MOVE:
        return dependsOnInput(ins.lhs1, paramNames, defs, visiting);
      case BINOP:
        return dependsOnInput(ins.lhs1, paramNames, defs, visiting)
            || dependsOnInput(ins.lhs2, paramNames, defs, visiting);
      case UNARYOP:
        return dependsOnInput(ins.lhs1, paramNames, defs, visiting);
      default:
        // Every other shape -- a call, a field read, an array read, an unknown value -- may
        // carry anything, including what the caller passed in.
        return true;
    }
  }
}
