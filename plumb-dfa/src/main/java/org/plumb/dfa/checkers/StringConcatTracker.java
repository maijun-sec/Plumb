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
package org.plumb.dfa.checkers;

import java.util.HashSet;
import java.util.Set;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Shared per-function tracker for string concatenation.
 *
 * <h2>Why this exists</h2>
 *
 * <p>An integer overflow rule must not report a string concatenation: adding two strings does not
 * overflow, it builds a longer string. Each arithmetic checker had its own guard for that, but the
 * guard could only see a string LITERAL sitting next to the operation, because that is the one
 * place the IR carries a type: a script front end has no symbol solver, so its values are typed
 * UNKNOWN.
 *
 * <p>So the guard missed every concatenation whose string-ness was one step away:
 *
 * <pre>
 *   t_t6 = t_t5 + "/"     the literal makes this a concatenation
 *   t_t7 = t_t6 + bar     the line above makes this a concatenation too
 * </pre>
 *
 * <p>The second line was reported as an integer overflow -- on a string. Measured against the OWASP
 * Benchmark for Python, that one shape accounted for thousands of findings that named a CWE the
 * analysed file was not about.
 *
 * <h2>What this does</h2>
 *
 * <p>It remembers, per function, which variables hold a string, and it carries that knowledge
 * forward: a concatenation produces a string, a move of a string produces a string, and any other
 * write forgets what the variable held.
 *
 * <h2>Why it forgets</h2>
 *
 * <p>A variable that held a string and then holds a number must not keep the string label, or the
 * rules would stay silent about a real overflow. So the label is dropped whenever something else
 * writes the variable -- which makes the tracker forget rather than guess, and guessing wrong in
 * this direction would cost a true finding.
 */
public final class StringConcatTracker {

  /** The variables this function has shown to hold a string. */
  private final Set<TacOperand.TacVar> stringVars = new HashSet<>();

  /** Whether the instruction observed last was a string concatenation. */
  private boolean lastConcat;

  /**
   * Forget everything. Called at every function boundary: a variable's type is a property of the
   * function that holds it, and the same name in another function says nothing about this one.
   */
  public void clear() {
    this.stringVars.clear();
    this.lastConcat = false;
  }

  /**
   * Take note of one instruction, and say whether it concatenates strings.
   *
   * <p>Call it once per instruction, in program order: the answer for an instruction depends on
   * what the earlier ones showed.
   *
   * @param ins the instruction
   * @return true when the instruction is a string concatenation
   */
  public boolean observe(TacInstruction ins) {
    if (ins == null) {
      this.lastConcat = false;
      return false;
    }
    if (ins.kind == TacInstruction.Kind.BINOP) {
      boolean concat =
          holdsString(ins.lhs1)
              || holdsString(ins.lhs2)
              || (ins.dest instanceof TacOperand.TacVar dv && dv.type() == TacType.STRING);
      // A concatenation produces a string; any other arithmetic produces a number.
      record(ins.dest, ins.binop == BinaryOperator.ADD && concat);
      this.lastConcat = concat;
      return concat;
    }
    if (ins.kind == TacInstruction.Kind.MOVE) {
      record(ins.dest, holdsString(ins.lhs1));
      this.lastConcat = false;
      return false;
    }
    // Any other write forgets what the destination held.
    if (ins.dest instanceof TacOperand.TacVar) {
      this.stringVars.remove(ins.dest);
    }
    this.lastConcat = false;
    return false;
  }

  /**
   * Whether the instruction observed last was a string concatenation.
   *
   * <p>The answer for the instruction being processed, for a caller that observes the instruction
   * somewhere else than here.
   */
  public boolean lastWasConcat() {
    return this.lastConcat;
  }

  /**
   * Whether the operand is known to hold a string.
   *
   * <p>A constant says so through its own type; a variable says so either through its type (a typed
   * language) or through what this function has already shown about it (a script language).
   */
  private boolean holdsString(TacOperand operand) {
    if (operand instanceof TacOperand.Const c) {
      return c.type() == TacType.STRING;
    }
    if (operand instanceof TacOperand.TacVar v) {
      return v.type() == TacType.STRING || this.stringVars.contains(v);
    }
    return false;
  }

  /** Remember (or forget) what the destination holds. */
  private void record(TacOperand dest, boolean isString) {
    if (!(dest instanceof TacOperand.TacVar dv)) {
      return;
    }
    if (isString) {
      this.stringVars.add(dv);
    } else {
      this.stringVars.remove(dv);
    }
  }
}
