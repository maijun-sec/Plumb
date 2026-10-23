package org.plumb.dfa.solver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.solver.SolverZ3Bridge.Result;

/**
 * Cross-checker path-condition composition.
 *
 * <p>A path condition is a list of boolean predicates that are known to hold on the current control
 * path. Each predicate is rendered in the same mini-DSL the {@link SolverZ3Bridge} accepts (the
 * same DSL the {@code emit guards} hook uses).
 *
 * <p>Two predicates can be composed with {@link #implies(PathCondition, SolverZ3Bridge, Map)} —
 * this is the heart of the new guard-sharing layer:
 *
 * <ul>
 *   <li>{@code DivByZeroChecker} records the guard {@code data != 0}.
 *   <li>{@code IntUnderflowChecker} records {@code data >= 1} (i.e. the developer is guarding
 *       against underflow).
 *   <li>A new finding wants to know "is this division safe?" ? the answer is yes iff the current
 *       path condition <em>implies</em> {@code data != 0}. If IntUnderflow already proved {@code
 *       data >= 1} (which implies {@code data != 0}), the finding can be suppressed without an
 *       extra Z3 query.
 * </ul>
 *
 * <p>The composition is symmetric: any checker that records a guard helps every other checker that
 * consumes it. The {@code GuardStore} in the {@code analysis context} holds the union across all
 * checkers, and a checker calls {@link #implies(PathCondition, SolverZ3Bridge, Map)} with the union
 * + its own negation as the second argument.
 */
public final class PathCondition {

  private final List<String> predicates;

  public PathCondition() {
    this(new ArrayList<>());
  }

  public PathCondition(List<String> predicates) {
    this.predicates = new ArrayList<>(predicates);
  }

  public PathCondition add(String predicate) {
    if (predicate == null || predicate.isBlank()) return this;
    predicates.add(predicate);
    return this;
  }

  public PathCondition addAll(PathCondition other) {
    if (other == null) return this;
    predicates.addAll(other.predicates);
    return this;
  }

  public List<String> predicates() {
    return List.copyOf(predicates);
  }

  public boolean isEmpty() {
    return predicates.isEmpty();
  }

  /**
   * Render the conjunction of all predicates. Empty path conditions render as the always-true
   * predicate {@code "true"}.
   */
  public String renderConjunction() {
    if (predicates.isEmpty()) return "true";
    return String.join(" && ", predicates);
  }

  /**
   * Test whether this path condition implies {@code consequent} (i.e. for every binding that
   * satisfies this condition, {@code consequent} is also true). The {@code antecedentBindings}
   * carry the known concrete values for variables in the predicate; the bridge may use them or
   * ignore them depending on its implementation.
   *
   * @return {@link Boolean#TRUE} when implication is valid, {@link Boolean#FALSE} when the
   *     implication can be violated, {@code null} when the bridge cannot decide.
   */
  public Boolean implies(
      String consequent, SolverZ3Bridge bridge, Map<String, Object> antecedentBindings) {
    if (consequent == null || consequent.isBlank()) {
      return Boolean.TRUE;
    }
    if (predicates.isEmpty()) {
      // No antecedent — implication
      // is just the consequent.
      Result r = bridge.check(consequent, antecedentBindings);
      return switch (r) {
        case SAT -> Boolean.TRUE;
        case UNSAT -> Boolean.FALSE;
        case UNKNOWN -> null;
      };
    }
    // antecedent AND NOT consequent
    // — UNSAT iff implication
    // holds.
    String conjunction = renderConjunction();
    String negation = "(" + conjunction + ") && (!(" + consequent + "))";
    Result r = bridge.check(negation, antecedentBindings);
    return switch (r) {
      case SAT -> Boolean.FALSE;
      case UNSAT -> Boolean.TRUE;
      case UNKNOWN -> null;
    };
  }

  public static Boolean implies(
      PathCondition antecedent,
      String consequent,
      SolverZ3Bridge bridge,
      Map<String, Object> bindings) {
    if (antecedent == null) antecedent = new PathCondition();
    return antecedent.implies(consequent, bridge, bindings);
  }

  /**
   * Quick syntactic check: {@code this} contains a predicate that is a trivial strengthening of
   * {@code consequent}. Used as a fast path before falling back to the (expensive) SMT call.
   *
   * <p>Recognised shapes:
   *
   * <ul>
   *   <li>predicate {@code x >= N} (N concrete) implies {@code x != 0} (when N &gt; 0) and {@code x
   *       > 0}.
   *   <li>predicate {@code x > N} (N concrete) implies {@code x >= N+1}.
   *   <li>predicate {@code x <= N} implies {@code x < N+1}.
   *   <li>predicate {@code x == 0} implies NOT-{@code x != 0}.
   * </ul>
   */
  public boolean structurallyImplies(String consequent) {
    if (predicates.isEmpty()) return false;
    for (String p : predicates) {
      if (StructuralImply.match(p, consequent)) {
        return true;
      }
    }
    return false;
  }

  public Set<String> variables() {
    Set<String> out = new LinkedHashSet<>();
    for (String p : predicates) {
      out.addAll(extractVars(p));
    }
    return out;
  }

  static Set<String> extractVars(String pred) {
    Set<String> out = new LinkedHashSet<>();
    if (pred == null) return out;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(pred);
    while (m.find()) {
      String s = m.group();
      if (s.equals("true") || s.equals("false") || s.equals("null")) {
        continue;
      }
      out.add(s);
    }
    return out;
  }
}
