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
package org.plumb.dfa.solver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap syntactic implication check for arithmetic comparison guards.
 *
 * <p>Before falling back to a full SMT query the new cross-checker guard-sharing layer first asks
 * "does guard A obviously imply guard B?" -- for instance {@code x >= 1} obviously implies {@code x
 * != 0}. A positive answer here lets us suppress the finding without paying for a thousand-line Z3
 * solve.
 *
 * <p>Recognised shapes:
 *
 * <ul>
 *   <li>antecedent {@code x &gt;= N} (N concrete, &gt; 0) implies consequent {@code x != 0}.
 *   <li>antecedent {@code x &gt; N} (N &ge; 0) implies consequent {@code x != 0}.
 *   <li>antecedent {@code x &lt;= N} (N &lt; 0) implies consequent {@code x != 0}.
 *   <li>antecedent {@code x &gt;= N} (N &gt; 0) implies consequent {@code x &gt; 0}.
 *   <li>antecedent {@code x &gt; N} (N &ge; 0) implies consequent {@code x &gt;= 1}.
 *   <li>antecedent {@code x &lt;= N} (N &lt; 1) implies consequent {@code x &lt; 1}.
 *   <li>antecedent {@code x &gt; N} (N &ge; 0) implies consequent {@code x &gt;= N+1}.
 *   <li>antecedent {@code x &lt; N} (N &le; 0) implies consequent {@code x &lt;= N-1}.
 *   <li>antecedent identical to consequent.
 *   <li>antecedent {@code x == N} implies {@code x != M} for any M &ne; N.
 * </ul>
 *
 * <p>The syntactic check is intentionally limited: it does <em>not</em> reason about chained
 * comparisons or arithmetic identities. For those, callers should use {@link
 * PathCondition#implies(String, SolverZ3Bridge, java.util.Map)} which goes through Z3.
 */
final class StructuralImply {

  private static final Pattern CMP =
      Pattern.compile(
          "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*"
              + "(>=|<=|!=|==|>|<)\\s*"
              + "(-?[0-9]+(?:\\.[0-9]+)?)\\s*$");

  private StructuralImply() {}

  static boolean match(String antecedent, String consequent) {
    if (antecedent == null || consequent == null) return false;
    // Strip trailing semicolons and whitespace.
    String a = antecedent.trim();
    String c = consequent.trim();
    if (a.equals(c)) return true;
    Cmp ac = parse(a);
    Cmp cc = parse(c);
    if (ac == null || cc == null) return false;
    if (!ac.var.equals(cc.var)) return false;
    if (ac.op.equals("==") && cc.op.equals("!=") && ac.value != cc.value) {
      return true;
    }
    // Same op, tighter bound.
    if (ac.op.equals(cc.op)) {
      return switch (ac.op) {
        case ">=" -> ac.value >= cc.value;
        case ">" -> ac.value > cc.value;
        case "<=" -> ac.value <= cc.value;
        case "<" -> ac.value < cc.value;
        case "==" -> ac.value == cc.value;
        default -> false;
      };
    }
    // x >= N (N > 0) implies x != 0 and x > 0
    if (ac.op.equals(">=") && ac.value > 0) {
      if (cc.op.equals("!=") && cc.value == 0) return true;
      if (cc.op.equals(">") && cc.value == 0) return true;
      if (cc.op.equals(">=") && cc.value == 1) return true;
    }
    // x > N (N >= 0) implies x != 0 and x >= 1
    if (ac.op.equals(">") && ac.value >= 0) {
      if (cc.op.equals("!=") && cc.value == 0) return true;
      if (cc.op.equals(">=") && cc.value == 1) return true;
      if (cc.op.equals(">") && cc.value == 0) return true;
    }
    // x <= N (N < 0) implies x != 0
    if (ac.op.equals("<=") && ac.value < 0) {
      if (cc.op.equals("!=") && cc.value == 0) return true;
    }
    // x < N (N <= 0) implies x != 0 and x <= -1
    if (ac.op.equals("<") && ac.value <= 0) {
      if (cc.op.equals("!=") && cc.value == 0) return true;
      if (cc.op.equals("<=") && cc.value == -1) return true;
    }
    // x > N implies x >= N+1
    if (ac.op.equals(">") && cc.op.equals(">=") && Math.abs(cc.value - (ac.value + 1)) < 1e-9) {
      return true;
    }
    // x < N implies x <= N-1
    if (ac.op.equals("<") && cc.op.equals("<=") && Math.abs(cc.value - (ac.value - 1)) < 1e-9) {
      return true;
    }
    return false;
  }

  private static Cmp parse(String s) {
    Matcher m = CMP.matcher(s);
    if (!m.matches()) return null;
    return new Cmp(m.group(1), m.group(2), Double.parseDouble(m.group(3)));
  }

  private static final class Cmp {
    final String var;
    final String op;
    final double value;

    Cmp(String var, String op, double value) {
      this.var = var;
      this.op = op;
      this.value = value;
    }
  }
}
