package org.plumb.dfa.solver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-checker guard store. Lives on the {@code AnalysisContext} (one per engine run). Each
 * checker that emits a guard (the guard-emit hook) calls {@link #add} here; any other checker that
 * wants to know "is my missing guard implied by a guard someone else already recorded?" calls
 * {@link #impliedGuard}.
 *
 * <p>Keys are the guarded variable's name (string, not {@code TacVar} ? guards are surfaced in the
 * same flat string form the bridge speaks, so a single var name is enough). Values are the union of
 * predicates the various checkers have recorded for that variable.
 *
 * <p>The store is single-threaded: the engine calls checkers in a serial RPO order so the store
 * only needs the per-function lifecycle hooks. The engine clears the store between runs.
 */
public final class GuardStore {

  /** Variable name → predicates known to hold. */
  private final Map<String, List<String>> byVar = new LinkedHashMap<>();

  /** Total predicate count, for the {@link #size()} probe. */
  private int totalPredicates = 0;

  /** Optional bridge, set lazily. Used by {@link #impliedGuard}. */
  private SolverZ3Bridge bridge;

  public GuardStore() {}

  public void setBridge(SolverZ3Bridge bridge) {
    this.bridge = bridge;
  }

  public void add(String var, String predicate) {
    if (var == null || var.isBlank() || predicate == null || predicate.isBlank()) {
      return;
    }
    byVar.computeIfAbsent(var, k -> new ArrayList<>()).add(predicate);
    totalPredicates++;
  }

  public List<String> guardsFor(String var) {
    return byVar.getOrDefault(var, List.of());
  }

  public Set<String> variables() {
    return byVar.keySet();
  }

  public int size() {
    return totalPredicates;
  }

  public void clear() {
    byVar.clear();
    totalPredicates = 0;
  }

  /**
   * Does the store already imply {@code consequent} on variable {@code var}?
   *
   * <p>The check is two-tier:
   *
   * <ol>
   *   <li><b>Structural fast-path</b> — if any recorded guard is a syntactic strengthening of the
   *       consequent, return true.
   *   <li><b>SMT fallback</b> — otherwise ask the configured bridge to test the implication
   *       (conjunction of recorded guards implies the consequent).
   * </ol>
   *
   * @return {@code true} if the store implies the guard, false if it actively contradicts it, null
   *     if the bridge cannot decide.
   */
  public Boolean impliedGuard(String var, String consequent, Map<String, Object> bindings) {
    if (var == null || consequent == null) return null;
    List<String> existing = byVar.get(var);
    if (existing == null || existing.isEmpty()) {
      return null;
    }
    PathCondition pc = new PathCondition(existing);
    // Tier 1: structural.
    if (pc.structurallyImplies(consequent)) {
      return Boolean.TRUE;
    }
    if (bridge == null) {
      return null;
    }
    // Tier 2: SMT.
    return pc.implies(consequent, bridge, bindings);
  }

  public String dump() {
    StringBuilder sb = new StringBuilder("GuardStore{");
    sb.append("total=").append(totalPredicates);
    sb.append(", vars=").append(byVar.size());
    for (var e : byVar.entrySet()) {
      sb.append(", ")
          .append(e.getKey())
          .append("->[")
          .append(String.join("; ", e.getValue()))
          .append("]");
    }
    sb.append("}");
    return sb.toString();
  }
}
