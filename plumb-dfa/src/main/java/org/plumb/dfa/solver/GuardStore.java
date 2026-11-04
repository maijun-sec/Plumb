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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-checker guard store. Lives on the {@code AnalysisContext} (one per engine run). Each
 * checker that emits a guard (the guard-emit hook) calls {@link #add} here; any other checker that
 * wants to know "is my missing guard implied by a guard someone else already recorded?" calls
 * {@link #impliedGuard}.
 *
 * <p>Keys are the guarded variable's name (string, not {@code TacVar} -- guards are surfaced in the
 * same flat string form the bridge speaks, so a single var name is enough). Values are the union of
 * predicates the various checkers have recorded for that variable.
 *
 * <p>The store is shared by the whole run, so it must survive the parallel worklist: the engine
 * analyses several functions at once and any checker may record or query a guard. The map is a
 * {@link java.util.concurrent.ConcurrentHashMap} and the predicate lists are copy-on-write, so a
 * reader never sees a half-updated list. The engine clears the store between runs.
 */
public final class GuardStore {

  /**
   * Variable name → predicates known to hold.
   *
   * <p>Concurrent, because the parallel worklist has several workers recording guards at the same
   * time. The value is a copy-on-write list: a worker builds a new list and publishes it, so a
   * reader that already holds the old list keeps a consistent snapshot instead of seeing an {@link
   * ArrayList} mutate under it.
   */
  private final Map<String, List<String>> byVar = new java.util.concurrent.ConcurrentHashMap<>();

  /** Total predicate count, for the {@link #size()} probe. */
  private final java.util.concurrent.atomic.AtomicInteger totalPredicates =
      new java.util.concurrent.atomic.AtomicInteger();

  /** Optional bridge, set lazily. Used by {@link #impliedGuard}. */
  private volatile SolverZ3Bridge bridge;

  public GuardStore() {}

  public void setBridge(SolverZ3Bridge bridge) {
    this.bridge = bridge;
  }

  public void add(String var, String predicate) {
    if (var == null || var.isBlank() || predicate == null || predicate.isBlank()) {
      return;
    }
    byVar.compute(
        var,
        (k, existing) -> {
          List<String> next = new ArrayList<>(existing == null ? List.of() : existing);
          next.add(predicate);
          return List.copyOf(next);
        });
    totalPredicates.incrementAndGet();
  }

  public List<String> guardsFor(String var) {
    return byVar.getOrDefault(var, List.of());
  }

  public Set<String> variables() {
    return byVar.keySet();
  }

  public int size() {
    return totalPredicates.get();
  }

  public void clear() {
    byVar.clear();
    totalPredicates.set(0);
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
