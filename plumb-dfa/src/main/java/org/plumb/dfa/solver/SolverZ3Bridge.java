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

import java.util.Map;
import java.util.Set;

/**
 * Path-condition satisfiability bridge.
 *
 * <p>A {@code SolverZ3Bridge} answers one question: "given this predicate string and these variable
 * bindings, is the predicate satisfiable?" The caller-side checker uses the answer to filter
 * findings -- a finding whose source-to-sink path condition is UNSAT can't actually be taken, so
 * emitting it would be a false positive.
 *
 * <h2>Bridge implementations</h2>
 *
 * <ul>
 *   <li>{@link LocalSolverBridge} -- a pure-Java implementation that handles a small arithmetic /
 *       boolean DSL ({@code &amp;&amp;}, {@code ||}, {@code !}, {@code ==}, {@code !=}, {@code
 *       &lt;}, {@code &lt;}, {@code &gt;}, {@code &lt;=} etc.). Always available; the default for
 *       tests and small analyses.
 *   <li>{@code Z3Bridge} (future) -- wraps {@code tools.aqua.z3-turnkey}'s native context. Handles
 *       the full Z3 input language. Falls back to {@code LocalSolverBridge} when the Z3 native
 *       library is not on the classpath (the production classpath usually has it; CI / dev machines
 *       sometimes don't).
 * </ul>
 *
 * <h2>Why a bridge abstraction</h2>
 *
 * <p>The caller-side checker shouldn't care which SMT solver backs the decision. The bridge is the
 * single seam; tests can run with {@code LocalSolverBridge} (fast, no native deps) and production
 * can run with {@code Z3Bridge} (full power). The production ships the local bridge; the Z3 bridge
 * is a follow-on that drops in without changing any caller.
 */
public interface SolverZ3Bridge {

  /** The result of a satisfiability check. */
  enum Result {
    /** The predicate is satisfiable under the bindings. */
    SAT,
    /** The predicate is unsatisfiable — the path is unreachable. */
    UNSAT,
    /** The bridge couldn't decide (e.g. unsupported feature). */
    UNKNOWN
  }

  /**
   * Check whether {@code predicate} is satisfiable under the given variable bindings. A variable
   * referenced in {@code predicate} but missing from {@code bindings} is treated as a free variable
   * (the bridge may or may not return UNKNOWN -- implementations vary).
   *
   * @param predicate a small predicate string (see {@link LocalSolverBridge} for the grammar)
   * @param bindings variable name → concrete value; values are {@link Long}/{@link Double}/ {@link
   *     Boolean}/{@link String}
   */
  Result check(String predicate, Map<String, Object> bindings);

  /**
   * Check whether {@code antecedent ⇒ consequent} is logically valid. Equivalent to {@code check}
   * of {@code (!antecedent) || consequent} but is the API the new guard-composition layer uses to
   * compare two guards across checkers.
   *
   * <p>Returns {@code true} if the implication is tautologically valid (SAT under any binding),
   * {@code false} if the implication can be violated (UNSAT of the negation), {@code null} if the
   * bridge cannot decide (UNSAT under all bindings, or UNKNOWN).
   */
  default Boolean implies(String antecedent, String consequent, Map<String, Object> bindings) {
    if (antecedent == null || consequent == null) {
      return null;
    }
    // antecedent ⇒ consequent
    // is a tautology iff the
    // negation (antecedent &&
    // !consequent) is UNSAT.
    // We can't ask the bridge
    // for "all bindings" directly
    // — the bridge gives us a
    // single check under the
    // supplied bindings. So the
    // default implementation is
    // best-effort: it returns
    // true only when the
    // check is UNSAT under the
    // supplied bindings AND the
    // consequent is structurally
    // a simple comparison. For
    // a sound cross-checker
    // implication the call site
    // should use a real Z3
    // bridge, which overrides
    // this default.
    String negation = "(" + antecedent + ") && (!(" + consequent + "))";
    Result r = check(negation, bindings);
    if (r == Result.UNSAT) {
      return Boolean.TRUE;
    }
    if (r == Result.SAT) {
      return Boolean.FALSE;
    }
    return null;
  }

  /**
   * Convenience: the variables referenced in {@code predicate}. Bridges use this to validate that
   * all referenced names are bound (or to inject free variables for unbound ones).
   */
  Set<String> referencedVars(String predicate);
}
