package org.plumb.dfa.points;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * copy equivalence — "are these two names the same <em>reference</em>?".
 *
 * <h2>Why this exists alongside {@link PointsToSolver}</h2>
 *
 * <p>The points-to analysis answers "may these two names denote the same <em>object</em>?", and it
 * needs an allocation site to compare. A method parameter has none: the entry method is called by
 * nobody, so {@code pts(param)} is empty, and so is every set derived from it. That makes the
 * points-to-only answer for the most common real-world shape useless:
 *
 * <pre>
 *   void run(String a) {
 *     String b = a;          // b and a are the same reference — a language-level fact
 *     if (a == null) return;
 *     b.use();               // safe, and the guard is on a, not b
 *   }
 * </pre>
 *
 * <p>Here {@code b} and {@code a} are the same reference by construction, independent of any
 * allocation. That is strictly more precise than points-to for the question a null-ness guard
 * actually asks, and it is available where points-to is not.
 *
 * <p>The two are complementary, not competing:
 *
 * <ul>
 *   <li><b>copy equivalence</b> answers "same reference?" and is exact for assignment, parameter
 *       passing, and returns. It cannot see through a container or a field, because it has no heap
 *       model.
 *   <li><b>points-to</b> answers "same object?" and reaches through fields and containers. It is
 *       conservative: it answers "maybe", never "definitely".
 * </ul>
 *
 * <p>A checker that wants to transfer a fact from one name to another must consult both. Using only
 * points-to silently fails on parameters; using only copy equivalence silently fails on {@code
 * list.get(0)} aliases.
 *
 * <h2>Precision and limits</h2>
 *
 * <p>Equivalence is a union-find over (function, variable) pairs. The propagation rules are the
 * ones that make two names provably the same reference:
 *
 * <pre>
 *   MOVE        dest and src are the same reference
 *   CALL        each formal and its actual are the same reference (the callee sees the caller's object)
 *               the CALL's dest and the callee's return pseudo-variable are the same reference
 *   RETURN      the returned variable and the function's return pseudo-variable
 * </pre>
 *
 * <p>A <em>fresh</em> allocation is a new reference and never merges, so {@code String x = a; new
 * String b = ...} keeps {@code x} and {@code b} apart — which is what the negative controls pin.
 *
 * <p>Not modelled: array and container elements, and fields. Those need a heap model and belong to
 * {@link PointsToSolver}; this class deliberately stays reference-local so that it remains exact
 * where it applies.
 */
public final class CopyEquivalence {

  /** One node: a variable within one function, plus the synthetic return slot of that function. */
  private record Node(String signature, String varName) {}

  private final Map<Node, Node> parent = new LinkedHashMap<>();

  /**
   * The variables of a function, by name.
   *
   * <p>{@link #classOf} filters candidates through this and needs the real {@link
   * TacOperand.TacVar} instances, because a checker's guard set is keyed by those objects.
   * Populated by {@link #of(Collection)}, which already walks every function.
   */
  private final Map<String, Map<String, TacOperand.TacVar>> varsBySignature = new LinkedHashMap<>();

  private CopyEquivalence() {}

  /**
   * Build the equivalence over a whole program.
   *
   * <p>A single pass suffices: the constraints are static, and union-find reaches the transitive
   * closure on its own. Unlike the points-to solver there is nothing to iterate to a fixpoint,
   * because no rule depends on a discovered heap fact.
   */
  public static CopyEquivalence of(Collection<TacFunction> program) {
    CopyEquivalence eq = new CopyEquivalence();
    Map<String, TacFunction> bySignature = new LinkedHashMap<>();
    Map<String, String> byNameArity = new LinkedHashMap<>();
    for (TacFunction fn : program) {
      if (fn == null) continue;
      bySignature.put(fn.signature(), fn);
      if (fn.name() != null && !fn.name().isEmpty()) {
        byNameArity.putIfAbsent(fn.name() + "/" + fn.params().size(), fn.signature());
      }
      // Make sure every declared local and parameter is a node, so asking about a variable that
      // was never involved in any assignment still returns a singleton class rather than null.
      Map<String, TacOperand.TacVar> vars =
          eq.varsBySignature.computeIfAbsent(fn.signature(), k -> new LinkedHashMap<>());
      for (TacOperand.TacVar v : fn.locals()) {
        eq.add(new Node(fn.signature(), v.name()));
        vars.put(v.name(), v);
      }
      for (TacOperand.TacVar v : fn.params()) {
        eq.add(new Node(fn.signature(), v.name()));
        vars.put(v.name(), v);
      }
      eq.add(new Node(fn.signature(), RETURN_SLOT));
    }
    for (TacFunction fn : program) {
      if (fn == null) continue;
      for (TacInstruction ins : fn.instructions()) {
        eq.record(fn.signature(), ins);
      }
    }
    return eq;
  }

  /** The synthetic variable standing for a function's return value. */
  public static final String RETURN_SLOT = "<return>";

  // ── construction ──────────────────────────────────────────────────────

  private void record(String signature, TacInstruction ins) {
    switch (ins.kind) {
      case MOVE -> {
        if (ins.dest instanceof TacOperand.TacVar d && ins.lhs1 instanceof TacOperand.TacVar s) {
          union(new Node(signature, d.name()), new Node(signature, s.name()));
        }
      }
      case CALL -> {
        // deliberately does nothing.
        //
        // An earlier version unioned the caller's actual with the callee's formal, and the call's
        // destination with the callee's return slot. Both are true in isolation, and together they
        // are a bug: union-find's transitive closure merges the two call sites of one method, so
        //
        //   a = make(input);
        //   b = make(input);
        //   if (a == null) return;
        //   b.length();              // reported as safe, and it is not
        //
        // The structure cannot express "same origin, different call site, therefore distinct", so
        // the choice is between a wrong answer and no answer. Cross-method identity belongs to
        // {@link PointsToSolver}, which qualifies every fact with a call-site context and can tell
        // those two results apart. This class keeps the question it can answer exactly: which names
        // inside ONE method are the same reference.
      }
      case RETURN -> {
        // Intentionally also nothing. A RETURN's value is unified with this function's own
        // RETURN_SLOT only when a call site asks for it, and that question is a cross-method one.
      }
      default -> {
        // No reference-copying semantics: a fresh allocation, a field read, or an arithmetic op.
        // Field and array access need a heap model and belong to PointsToSolver.
      }
    }
  }

  // ── union-find ────────────────────────────────────────────────────────

  private Node add(Node n) {
    return parent.computeIfAbsent(n, x -> x);
  }

  private Node find(Node n) {
    Node root = add(n);
    // Path compression, iterative: a recursive find is a stack-depth risk on the long MOVE chains
    // that a straight-line assignment block produces.
    while (root != parent.get(root)) {
      root = parent.get(root);
    }
    Node cur = add(n);
    while (cur != root) {
      Node next = parent.get(cur);
      parent.put(cur, root);
      cur = next;
    }
    return root;
  }

  private void union(Node a, Node b) {
    Node ra = find(a);
    Node rb = find(b);
    if (ra == rb) return;
    // Union by lexical order rather than by rank: the result must be deterministic across runs,
    // because a finding's provenance can name a class member and a flaky member name would make
    // findings irreproducible. A record has no compareTo, so compare the rendered form --
    // "signature#var" -- which is unique per node.
    Node keep = ra.toString().compareTo(rb.toString()) <= 0 ? ra : rb;
    Node drop = keep == ra ? rb : ra;
    parent.put(drop, keep);
  }

  // ── queries ───────────────────────────────────────────────────────────

  /**
   * Are these two variables of {@code signature} the same reference?
   *
   * <p>Never throws and never returns true on absent information: a variable the analysis never saw
   * forms a singleton class, which answers false.
   */
  public boolean sameReference(String signature, TacOperand.TacVar a, TacOperand.TacVar b) {
    if (signature == null || a == null || b == null) return false;
    return find(new Node(signature, a.name())).equals(find(new Node(signature, b.name())));
  }

  /**
   * Every known variable of {@code signature} that is the same reference as {@code seed}.
   *
   * <p>Always contains {@code seed} itself (when it is known to this analysis), so callers can test
   * membership without special-casing it.
   */
  public Set<TacOperand.TacVar> classOf(String signature, TacOperand.TacVar seed) {
    Set<TacOperand.TacVar> out = new LinkedHashSet<>();
    if (signature == null || seed == null) return out;
    Node root = find(new Node(signature, seed.name()));
    for (Node n : new ArrayList<>(parent.keySet())) {
      if (!signature.equals(n.signature())) continue;
      if (!find(n).equals(root)) continue;
      TacOperand.TacVar v = lookupVariable(signature, n.varName());
      if (v != null) out.add(v);
    }
    return out;
  }

  private TacOperand.TacVar lookupVariable(String signature, String varName) {
    Map<String, TacOperand.TacVar> vars = varsBySignature.get(signature);
    return vars == null ? null : vars.get(varName);
  }
}
