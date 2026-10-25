package org.plumb.dfa.fact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.plumb.dfa.config.FactKind;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacOperand.TacVar;

/**
 * Per-function storage of {@link Fact}s.
 *
 * <p>A {@code FactBag} is a {@code Map<TacOperand, List<Fact>>}: for every operand that has at
 * least one fact recorded, the list of facts applies to it (in insertion order). The bag is
 * per-function; the engine makes one per function it's analysing, hands it to every checker for
 * read/write during that function's lifecycle, then discards it when the next function starts. The
 * cross-function persistence is the job of the {@code FunctionSummary}, not this bag.
 *
 * <h2>Why a list and not a {@code Set<Fact>}</h2>
 *
 * <p>Two facts can legitimately coexist for the same operand and kind: e.g. a TaintChecker might
 * record that operand {@code x} carries {@code UntrustedSource("user-input")} AND {@code
 * UntrustedSource("header-X-Forwarded-For")} (the var was assigned from a sequence of sources). The
 * bag is a list so the consumer can walk every source. Duplicate facts (identical operand, kind,
 * value) are still allowed ? the consumer is expected to dedup at use time, not the bag.
 *
 * <h2>Trait-driven propagation</h2>
 *
 * <p><b>The bag does not propagate anything, and it does not read the traits.</b> It is a store: a
 * checker records a fact during the function, and a checker (often the same one, sometimes another)
 * reads it back and decides what to do. {@link FactKind#has(org.plumb.dfa.config.FactKind.Trait)}
 * exists and is exercised by the tests, but no production code calls it -- the propagation is
 * implemented per checker.
 *
 * <p>What the traits DO say is what the kind is meant to mean: {@code PROPAGATED_BY_DECL} means "a
 * fact of this kind should follow the variable through the IR", {@code PROPAGATED_THROUGH_CALL}
 * means "it should cross a function boundary". Those statements are the catalogue's contract with
 * the reader, and the per-checker implementations are what honour them.
 */
public final class FactBag {

  private final Map<TacOperand, List<Fact>> map = new LinkedHashMap<>();

  /**
   * Add a fact. The same fact can be added multiple times; dedup is the consumer's responsibility.
   */
  public void add(Fact f) {
    Objects.requireNonNull(f, "f");
    map.computeIfAbsent(f.operand(), k -> new ArrayList<>()).add(f);
  }

  /** Convenience: build a fact from kind+operand+value and add it. */
  public void add(FactKind kind, TacOperand operand, String value) {
    add(new Fact(kind, operand, value));
  }

  /**
   * All facts recorded against {@code operand}. Returns an empty list (not null) for operands with
   * no observations; this lets consumers write {@code bag.factsOf(x).stream().filter(...)} without
   * a null check.
   */
  public List<Fact> factsOf(TacOperand operand) {
    if (operand == null) return List.of();
    List<Fact> l = map.get(operand);
    return l == null ? List.of() : Collections.unmodifiableList(l);
  }

  /** Convenience: facts of a {@link TacVar} by name. */
  public List<Fact> factsOfVar(String varName) {
    for (Map.Entry<TacOperand, List<Fact>> e : map.entrySet()) {
      if (e.getKey() instanceof TacVar v && v.name().equals(varName)) {
        return Collections.unmodifiableList(e.getValue());
      }
    }
    return List.of();
  }

  /**
   * Set of all operands that have at least one fact. Useful for the engine's PROPAGATED_BY_DECL
   * copy-from-def step (it walks the def's fact list to write onto the use operand).
   */
  public Set<TacOperand> operands() {
    return Collections.unmodifiableSet(map.keySet());
  }

  /** True if no facts have been recorded. */
  public boolean isEmpty() {
    return map.isEmpty();
  }

  /** Total number of recorded facts (across all operands). */
  public int size() {
    int n = 0;
    for (List<Fact> l : map.values()) n += l.size();
    return n;
  }

  /**
   * Snapshot of the bag as a map. Returns a defensive copy so the bag's internal state stays
   * private.
   */
  public Map<TacOperand, List<Fact>> snapshot() {
    Map<TacOperand, List<Fact>> out = new LinkedHashMap<>();
    for (Map.Entry<TacOperand, List<Fact>> e : map.entrySet()) {
      out.put(e.getKey(), new ArrayList<>(e.getValue()));
    }
    return out;
  }

  /** Clear all facts. Called by the engine between functions. */
  public void clear() {
    map.clear();
  }
}
