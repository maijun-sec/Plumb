package org.plumb.dfa.taint;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Lightweight path-sensitive guard tracker for DFA checkers.
 *
 * <p>The Juliet "goodB2G" pattern is the classic false-positive source for path-insensitive
 * dataflow analysis:
 *
 * <pre>
 *     if (data &lt; Byte.MAX_VALUE) {
 *         byte result = (byte)(data + 1);
 *         // ^ safe: data is bounded by
 *         //   MAX, so data+1 cannot overflow
 *     }
 * </pre>
 *
 * <p>A flow-insensitive check (the {@link TaintedCheckerBase} default) sees the {@code data + 1}
 * and fires a CWE-190 finding --but the operation is provably safe. The guard tracker maintains a
 * stack of "currently-active" comparison conditions so the check can suppress the finding when the
 * operation is in the "if-true" block of a comparison that bounds the operand.
 *
 * <h2>Algorithm</h2>
 *
 * <p>The linear IR has the shape {@code ... BRANCH t LABEL ... JUMP LABEL ...} where the LABEL
 * after BRANCH is the if-true block. The tracker:
 *
 * <ol>
 *   <li>Records every comparison {@code BINOP t = a &lt;relop&gt; b} (where one operand is a
 *       literal or a simple constant) into {@link #lastCompare}.
 *   <li>On {@code BRANCH t}, looks up {@code t} in {@code lastCompare} and pushes the resulting
 *       guard onto the stack.
 *   <li>On {@code JUMP} (end of if-true block), pops the stack.
 *   <li>Checkers ask {@link #isGuarded(TacOperand.TacVar)} to find out whether the current var is
 *       provably bounded by an active guard. If so, the finding is suppressed.
 * </ol>
 *
 * <p>The tracker is per-function; reset via {@link #clear()} from {@link
 * TaintedCheckerBase#initFunction}.
 */
public final class GuardTracker {

  /**
   * A single guard expression. The {@code literalDescription} is a human-readable form of the RHS
   * of the comparison (e.g. "5" for a literal int, "Byte.MAX_VALUE" for a static-final field load).
   * It's stored as a string rather than a {@link TacOperand} because the literal may be a
   * FIELDLOAD-loaded constant that doesn't fit the {@code TacOperand} sealed hierarchy.
   */
  public record Guard(TacOperand.TacVar boundedVar, BinaryOperator op, String literalDescription) {
    public Guard {
      Objects.requireNonNull(boundedVar, "boundedVar");
      Objects.requireNonNull(op, "op");
      Objects.requireNonNull(literalDescription, "literalDescription");
    }

    /** Human description: "data < Byte.MAX_VALUE". */
    public String description() {
      return boundedVar.name() + " " + op + " " + literalDescription;
    }
  }

  /**
   * Comparison operators that produce a useful bound on the variable. {@code EQ} / {@code NE} are
   * not useful for bounding the LHS in the general case (e.g. {@code data == 0} is a precise value,
   * not a bound --the underflow check {@code data - 1} would still be wrong; skip these).
   */
  private static final Set<BinaryOperator> BOUNDING_OPS =
      Set.of(BinaryOperator.LT, BinaryOperator.LE, BinaryOperator.GT, BinaryOperator.GE);

  /**
   * Comparison operators that indicate the variable is provably non-zero. Used by DivByZero-style
   * checkers which want to suppress fires when the divisor is guarded by {@code data != 0} (e.g.
   * the Juliet CWE-369 goodB2G pattern).
   */
  private static final Set<BinaryOperator> NON_ZERO_OPS = Set.of(BinaryOperator.NE);

  /**
   * operators whose <i>false</i> edge excludes exactly one value.
   *
   * <p>{@code EQ} is deliberately absent from {@link #BOUNDING_OPS} — "the variable equals a
   * literal" is not a range bound, so it can never help the overflow / bounds checkers. But it is
   * exactly what the early-return idiom is built from:
   *
   * <pre>
   *   if (data == 0) { return 0; }
   *   return a / data;          // reached only on the false edge: data != 0
   * </pre>
   *
   * <p>So EQ is recorded here and used only for {@link #recordFalseEdge}. It is never treated as an
   * active bound, which keeps {@link #isGuardedBy} and the Range lattice semantics unchanged.
   */
  private static final Set<BinaryOperator> FALSE_EDGE_OPS = Set.of(BinaryOperator.EQ);

  // lastCompare maps a comparison's dest
  // to the list of Guards it represents.
  // The list form supports short-circuit
  // && / || compound guards: when a
  // BINOP is LAND/LOR, the result's
  // lastCompare entry holds the union of
  // the underlying guard lists. The
  // active stack still pushes one Guard
  // at a time, so JUMP pop logic is
  // unaffected.
  private final Map<TacOperand.TacVar, java.util.List<Guard>> lastCompare = new HashMap<>();
  private final Deque<Guard> activeGuards = new ArrayDeque<>();

  /**
   * Frame markers for multi-guard BRANCH push / JUMP pop pairing. Each BRANCH that pushes N guards
   * records the stack size BEFORE the push. The matching JUMP pops until reaching that size, so a
   * short-circuit LAND/LOR BRANCH that pushed multiple guards pops them all on exit (instead of
   * leaking 1+ to the outer scope).
   */
  private final Deque<Integer> frameMarkers = new ArrayDeque<>();

  private final Set<TacOperand.TacVar> guardedVars = new HashSet<>();

  /**
   * negation of every {@code ==} / {@code !=} guard seen in this function, keyed by the false-edge
   * label that establishes it.
   *
   * <p>The linear IR puts an if-else like this:
   *
   * <pre>
   *   BINOP t0 = data EQ 0
   *   BRANCH t0, L_THEN_0, L_ELSE_1
   *   L_THEN_0:
   *     return 0
   *   JUMP L_END_IF_2          // pops the EQ guard (true branch done)
   *   L_ELSE_1:                // &lt;-- arriving here means `data != 0`
   *   L_END_IF_2:
   *     a / data
   * </pre>
   *
   * <p>The guard is popped by the JUMP before the else block is reached, so the active stack is
   * empty at the sink and the early-return idiom looks unguarded -- which is why {@code if (data ==
   * 0) return 0; a / data} fired while the symmetric {@code if (data != 0) { a / data; }} was
   * correctly suppressed.
   *
   * <p>Recording the negation against the BRANCH's {@code falseLabel} and consulting it when that
   * label is reached is the only reading under which the fact is sound: the negation holds on the
   * false edge and nowhere else.
   *
   * <p>Bounding operators are excluded: negating {@code v < L} yields {@code v >= L}, a range fact
   * the Range lattice already models, not an excluded value.
   */
  private final Map<String, java.util.List<Guard>> guardsPendingFalseEdge = new HashMap<>();

  /** False-edge negations currently in force, keyed by the label that established each. */
  private final Map<String, java.util.List<Guard>> activeFalseEdgeGuards = new HashMap<>();

  /**
   * All guards that were ever active during this function. Used by subclasses (e.g. {@code
   * IntOverflowChecker}) to emit the guards to the function summary for cross-function propagation.
   * Populated when a guard is pushed onto the active stack; cleared by {@link #clear()}.
   */
  private final java.util.LinkedHashSet<Guard> allGuardsSeen = new java.util.LinkedHashSet<>();

  /**
   * TacVars that are known to hold a compile-time constant. Populated by {@link
   * #recordConstantLoadIfApplicable} when a {@code FIELDLOAD} matches the "static final uppercase"
   * pattern (e.g. {@code Byte.MAX_VALUE}, {@code Integer.MIN_VALUE}). Used by {@link
   * #recordCompareIfApplicable} to recognise comparisons against such "constants" even though the
   * IR doesn't fold them to a literal.
   */
  private final Map<TacOperand.TacVar, String> knownConstants = new HashMap<>();

  /**
   * Guards propagated through arithmetic and MOVE. When we see {@code BINOP dest = var ± literal}
   * and {@code var} is currently active-guarded, or {@code MOVE dest = var} and {@code var} is
   * active-guarded, we add {@code dest} to {@link #derivedGuards} with the same operator and an
   * adjusted literal. This is the "guarded base" support that lets the ArrayIndexChecker recognise
   * patterns like:
   *
   * <pre>
   *     if (3 &lt;= pos) {
   *         int idx = pos - 3;          // BINOP: derived guard
   *         use(src[idx]);              // ArrayIndex: suppress
   *     }
   * </pre>
   *
   * <p>The derived guards are PER-FUNCTION (not popped on JUMP). This is conservative: it may cause
   * some false negatives if a derived var is reused outside the original if-block, but the typical
   * pattern (the BINOP is the index of an ARRAYLOAD inside the same if-block) is correctly
   * suppressed. The set is cleared by {@link #clear()}.
   */
  private final Map<TacOperand.TacVar, java.util.List<Guard>> derivedGuards = new HashMap<>();

  private final Set<TacOperand.TacVar> derivedGuardedVars = new HashSet<>();

  /**
   * maximum entries kept per derived-guard list. Without this DERIVED_GUARD_CAP, Juliet cross-file
   * scans (variant 22 / 51 / 81 with 1000+ files) blow up the per-dest / per-src lists unbounded.
   * 64 is well above the realistic per-var guard count (single digits), so the FP / FN surface is
   * unchanged for typical Juliet inputs.
   */
  private static final int DERIVED_GUARD_CAP = 64;

  /**
   * Fast-path index. Set of vars that have ever been bounded (or derived from a bounded var).
   * Populated when a guard is pushed onto {@link #activeGuards} (mirroring {@link #guardedVars})
   * and when a derived guard is added (mirroring {@link #derivedGuardedVars}). Used by {@link
   * #recordArithIfApplicable} and {@link #recordMoveIfApplicable} as an O(1) gate: if the source
   * var is not in this set, no propagation can happen, so skip the more expensive lookups. Cleared
   * by {@link #clear()}.
   */
  private final Set<TacOperand.TacVar> propagatableSources = new HashSet<>();

  /**
   * Recognises a {@code FIELDLOAD} that loads a "constant-like" value: a static final uppercase
   * field (e.g. {@code Byte.MAX_VALUE}, {@code Long.MIN_VALUE}). The dest is recorded in {@link
   * #knownConstants}.
   *
   * <p>Array length tracking is disabled by default. Re-enable it to restore the array-length bound
   * that suppresses the CWE-129 false positives.
   */
  private void recordConstantLoadIfApplicable(TacInstruction ins) {
    if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar dest)) {
      return;
    }
    if (ins.fieldName == null) return;
    // (The array.length special case is
    // currently disabled. Re-enable here
    // if the regression is re-introduced.)
    // if ("length".equals(ins.fieldName)
    //         && ins.lhs1 instanceof TacOperand.TacVar) {
    //     knownConstants.put(dest, "<array-length>");
    //     return;
    // }
    // Standard "constant-like" field names
    // for integral types.
    if (!isConstantFieldName(ins.fieldName)) return;
    // The lhs1 is the receiver (the class
    // name in source). The IR translator
    // emits a synthetic TacVar for
    // unresolved names (e.g. "Byte"),
    // not a Const. Accept either:
    // a synthetic var whose name starts
    // with an uppercase letter, or a
    // Const literal whose value starts
    // with an uppercase letter.
    String receiverName = null;
    if (ins.lhs1 instanceof TacOperand.TacVar v) {
      receiverName = v.name();
    } else if (ins.lhs1 instanceof TacOperand.Const c) {
      receiverName = c.toString();
    }
    if (receiverName == null || receiverName.isEmpty()) return;
    // First char should be a letter (class
    // names start with uppercase; we
    // accept lowercase too as a fallback).
    if (!Character.isLetter(receiverName.charAt(0))) return;
    String kind = receiverName + "." + ins.fieldName;
    knownConstants.put(dest, kind);
  }

  private static boolean isConstantFieldName(String name) {
    if (name == null || name.isEmpty()) return false;
    // All-uppercase (and underscores).
    for (int i = 0; i < name.length(); i++) {
      char ch = name.charAt(i);
      if (!Character.isUpperCase(ch) && ch != '_' && !Character.isDigit(ch)) {
        return false;
      }
    }
    return true;
  }

  /** Per-function reset. Called from the checker's {@code initFunction}. */
  public void clear() {
    lastCompare.clear();
    activeGuards.clear();
    frameMarkers.clear();
    guardedVars.clear();
    allGuardsSeen.clear();
    knownConstants.clear();
    derivedGuards.clear();
    derivedGuardedVars.clear();
    propagatableSources.clear();
    guardsPendingFalseEdge.clear();
    activeFalseEdgeGuards.clear();
  }

  /**
   * Feed one instruction into the tracker. Subclass overrides of {@code checkInstruction} should
   * call this AFTER {@code super.checkInstruction} so the inherited MOVE/BINOP propagation has
   * already happened, and BEFORE the checker's own sink check (so {@link #isGuarded} reflects the
   * current instruction's context).
   *
   * <p>Only BRANCH, JUMP, BINOP-with-compare, FIELDLOAD-with-constant, and LABEL are interesting.
   * All other instruction kinds are ignored.
   */
  public void track(TacInstruction ins) {
    switch (ins.kind) {
      case FIELDLOAD -> recordConstantLoadIfApplicable(ins);
      case BINOP -> {
        recordCompareIfApplicable(ins);
        recordLogicalOpIfApplicable(ins);
        // Only call the arith
        // propagator if at least one var
        // is propagatable. The common
        // case (functions with no guards)
        // skips the call entirely.
        if (!propagatableSources.isEmpty()) {
          recordArithIfApplicable(ins);
        }
      }
      case BRANCH -> onBranch(ins);
      case JUMP -> onJump(ins.label);
      case LABEL -> onLabel(ins);
      case MOVE -> {
        // rebinding a variable retires every guard proved for its previous value.
        //
        // A guard constrains a binding, not a name. `if (x == null) { break; }` establishes
        // `x != null` for whatever `x` holds at that point; a later `x = peek(cur)` binds a
        // different value, and consulting the old fact by name alone hides a real dereference:
        //
        //     while (cur != null) {
        //       StringBuilder x = peek(cur);
        //       if (x == null) { break; }
        //       x = peek(cur);        // new binding
        //       x.length();           // suppressed by the PREVIOUS binding's guard
        //     }
        //
        // Retiring early can only regain a false positive, so this is the safe direction.
        if (ins.dest instanceof TacOperand.TacVar rebound
            && ins.lhs1 instanceof TacOperand.TacVar source
            && rebound != source) {
          retireGuardsFor(rebound);
        }
        if (!propagatableSources.isEmpty()) {
          recordMoveIfApplicable(ins);
        }
      }
      default -> {
        /* LABEL etc. --no-op */
      }
    }
  }

  /**
   * Returns true if {@code var} is currently bounded by an active guard. The check is
   * "best-effort": it returns true if any active guard names this var, regardless of whether the
   * literal bound is the actual MAX/MIN of the type. The sink-specific check (e.g. IntOverflow)
   * does the final semantic check.
   */
  public boolean isGuarded(TacOperand.TacVar var) {
    return guardedVars.contains(var);
  }

  /**
   * Returns true if {@code var} is guarded by an active guard whose operator is {@code op}. Used by
   * checkers that need a specific guard direction (e.g. IntOverflowChecker wants {@code v < MAX}
   * for an ADD op).
   */
  public boolean isGuardedBy(TacOperand.TacVar var, BinaryOperator op) {
    for (Guard g : activeGuards) {
      if (g.boundedVar().equals(var) && g.op() == op) return true;
    }
    return false;
  }

  /**
   * Returns the most-specific active guard for {@code var}, or {@code null} if no guard applies.
   * The "most specific" is the top of the active stack (innermost if-block), which is the most
   * relevant for path-sensitive analysis.
   */
  public Guard currentGuardFor(TacOperand.TacVar var) {
    // Search from innermost (top of stack)
    // to outermost. Innermost is most
    // relevant.
    for (Guard g : activeGuards) {
      if (g.boundedVar().equals(var)) return g;
    }
    return null;
  }

  /** Visible for testing. Active guards as an unmodifiable snapshot. */
  public java.util.List<Guard> activeGuards() {
    return new java.util.ArrayList<>(activeGuards);
  }

  /**
   * All guards that have ever been pushed onto the active stack during this function. Includes
   * guards that have since been popped (e.g. after the if-true block ends). Used by {@code
   * IntOverflowChecker}'s {@code emitSummaryOps} to record bounds on the function summary.
   */
  public java.util.List<Guard> allGuardsSeen() {
    return new java.util.ArrayList<>(allGuardsSeen);
  }

  // ─── internal ─────────────────────────────────

  private void recordCompareIfApplicable(TacInstruction ins) {
    BinaryOperator op = ins.binop;
    if (op == null) return;
    // We track BOUNDING_OPS for IntOverflow
    // and NON_ZERO_OPS for DivByZero.
    // Both sets are recorded so the
    // checker can ask isGuardedBy(op) to
    // see which kind of guard applies.
    // FALSE_EDGE_OPS is recorded too, so the BRANCH's false edge can carry the
    // "this literal is excluded here" fact. It is not a bound and never becomes an
    // active guard -- see the FALSE_EDGE_OPS javadoc.
    if (!BOUNDING_OPS.contains(op) && !NON_ZERO_OPS.contains(op) && !FALSE_EDGE_OPS.contains(op)) {
      return;
    }
    if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar dest)) {
      return;
    }
    TacOperand.TacVar var = null;
    String literalDesc = null;
    boolean flipped = false;
    if (ins.lhs1 instanceof TacOperand.TacVar v && ins.lhs2 instanceof TacOperand.Const c) {
      var = v;
      literalDesc = c.toString();
    } else if (ins.lhs2 instanceof TacOperand.TacVar v && ins.lhs1 instanceof TacOperand.Const c) {
      // Literal on LHS (e.g. `3 LE pos`).
      // The guard semantics is `pos >= 3`,
      // not `pos <= 3`. Flip the operator so
      // isGuardedBy(pos, GE) matches the
      // intended lower bound.
      var = v;
      literalDesc = c.toString();
      flipped = true;
    } else if (ins.lhs1 instanceof TacOperand.TacVar v
        && ins.lhs2 instanceof TacOperand.TacVar v2
        && knownConstants.containsKey(v2)) {
      // Compare against a
      // FIELDLOAD-loaded "constant"
      // (e.g. `data < t_t24` where
      // t_t24 was loaded from
      // `Byte.MAX_VALUE`).
      var = v;
      literalDesc = knownConstants.get(v2);
    } else if (ins.lhs2 instanceof TacOperand.TacVar v
        && ins.lhs1 instanceof TacOperand.TacVar v2
        && knownConstants.containsKey(v2)) {
      // Same flip for FIELDLOAD-loaded
      // constant on LHS (e.g. `MAX_VALUE GE data`).
      var = v;
      literalDesc = knownConstants.get(v2);
      flipped = true;
    }
    if (var == null || literalDesc == null) return;
    BinaryOperator recordedOp = flipped ? flipOperator(op) : op;
    Guard g = new Guard(var, recordedOp, literalDesc);
    lastCompare.put(dest, java.util.List.of(g));
  }

  /**
   * Flip a comparison operator when the literal is on the LHS of the comparison (e.g. {@code 3 LE
   * pos} means {@code pos GE 3}, so the recorded op is GE not LE).
   */
  private static BinaryOperator flipOperator(BinaryOperator op) {
    return switch (op) {
      case LE -> BinaryOperator.GE;
      case GE -> BinaryOperator.LE;
      case LT -> BinaryOperator.GT;
      case GT -> BinaryOperator.LT;
      // EQ / NE / non-comparison --no flip
      // (flipping doesn't make semantic sense
      // for these; they remain unchanged).
      default -> op;
    };
  }

  /**
   * Handle short-circuit {@code &&} (LAND) and {@code ||} (LOR). The Juliet goodB2G pattern {@code
   * if (data >= 0 && data < length)} lowers to:
   *
   * <pre>
   *   BINOP t1 = data GE 0
   *   BINOP t3 = data LT length
   *   BINOP t4 = t1 LAND t3   // combined boolean
   *   BRANCH t4, L_then, ...
   * </pre>
   *
   * Without this fix, the {@code BRANCH} on {@code t4} finds no entry in {@code lastCompare} (only
   * {@code t1} and {@code t3} are recorded), so no guard is pushed and the inner if-block is
   * treated as unguarded. CWE-129 fires a false positive on the goodB2G case.
   *
   * <p>The fix: when seeing a {@code LAND} or {@code LOR} BINOP, look up the operand guards in
   * {@code lastCompare} and store the union under the result's destination. The {@code LAND} case
   * requires BOTH operands' guards to be active when the combined boolean is true; {@code LOR} is
   * more permissive (any operand's guard suffices) but we conservatively treat it as {@code LAND}
   * to avoid losing suppression on the {@code if (data < 0 || data > MAX)} pattern (where either
   * branch's guard applies). For the checkers that use {@code isGuardedBy} (the more common case),
   * {@code LAND} union and intersection are equivalent in the sense that both operands' guards end
   * up active.
   */
  private void recordLogicalOpIfApplicable(TacInstruction ins) {
    if (ins.binop != BinaryOperator.LAND && ins.binop != BinaryOperator.LOR) return;
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return;
    if (!(ins.lhs1 instanceof TacOperand.TacVar lhs1)) return;
    if (!(ins.lhs2 instanceof TacOperand.TacVar lhs2)) return;
    final java.util.List<Guard> g1 = lastCompare.get(lhs1);
    final java.util.List<Guard> g2 = lastCompare.get(lhs2);
    // Need BOTH operands' guards --otherwise
    // the compound guard has unknown guard
    // semantics (e.g. `data >= 0 && arr.length > 0`
    // --the second arm is not a guard on data).
    if (g1 == null || g2 == null) return;
    final java.util.List<Guard> combined = new java.util.ArrayList<>(g1.size() + g2.size());
    combined.addAll(g1);
    combined.addAll(g2);
    lastCompare.put(dest, combined);
  }

  private void onBranch(TacInstruction ins) {
    if (!(ins.lhs1 instanceof TacOperand.TacVar branchCond)) return;
    final java.util.List<Guard> guards = lastCompare.get(branchCond);
    if (guards == null || guards.isEmpty()) return;
    // A single BRANCH may activate multiple
    // guards (when the branch cond is a LAND/LOR
    // combination). Push all of them onto the
    // active stack, then mirror onto guardedVars
    // and allGuardsSeen.
    //
    // only push a frame marker if something is actually going on the active
    // stack. A BRANCH whose guards are all false-edge-only (EQ) pushes nothing, and a
    // marker with no matching push would desynchronise the JUMP pop counts.
    final boolean anyPushable = guards.stream().anyMatch(g -> !FALSE_EDGE_OPS.contains(g.op()));
    if (anyPushable) {
      frameMarkers.push(activeGuards.size());
    }
    for (Guard g : guards) {
      allGuardsSeen.add(g);
      // an EQ guard is NOT a bound. "data == 0" says the variable equals a literal,
      // which cannot justify the interval reasoning that isGuarded / isGuardedBy / the Range
      // lattice perform, so it must never enter the active stack or guardedVars. It is
      // recorded against the false edge below and consulted only through isRefutedBy.
      if (FALSE_EDGE_OPS.contains(g.op())) {
        recordFalseEdge(ins.falseLabel, g);
        continue;
      }
      activeGuards.push(g);
      guardedVars.add(g.boundedVar());
      // Mirror onto the fast-path
      // propagation source index.
      propagatableSources.add(g.boundedVar());
    }
  }

  /**
   * record, against the BRANCH's false target, the single value an {@code ==} / {@code !=} guard
   * rules out on that edge.
   *
   * <p>{@code if (v == L) {...} else {...}} reaching the else block means {@code v != L}, and
   * {@code if (v != L) {...} else {...}} means {@code v == L} -- neither excludes a value, so only
   * the EQ case records anything. Bounding operators are skipped: their negation is a range, not an
   * excluded value.
   */
  private void recordFalseEdge(String falseLabel, Guard g) {
    if (falseLabel == null || falseLabel.isEmpty()) return;
    if (g.op() != BinaryOperator.EQ) return;
    guardsPendingFalseEdge.computeIfAbsent(falseLabel, k -> new java.util.ArrayList<>()).add(g);
  }

  /**
   * activate the guards whose <i>false</i> edge is this label.
   *
   * <p>Reaching {@code L_ELSE_1} in {@code if (v == L) {...} else {...}} proves {@code v != L}. The
   * corresponding true-branch guard was already popped by the JUMP that skips the else block, so
   * without this the else body looks unguarded.
   *
   * <p>False-edge guards are never deactivated here. In a linear IR the else block runs to the
   * merge label and control then falls through into whatever follows, so the fact stays valid for
   * the remainder of the block. That over-approximation can only lose a suppression (regaining a
   * false positive), never invent a missed vulnerability.
   */
  private void onLabel(TacInstruction ins) {
    final String name = ins.label;
    if (name == null || name.isEmpty()) return;
    // entering a loop body invalidates every fact proved before it.
    //
    // The false-edge table is keyed by label and consulted BY NAME, so without this it lives for
    // the rest of the function. Inside a loop that silently suppressed real findings on every later
    // round:
    //
    //     while (cur != null) {
    //       StringBuilder x = peek(cur);     // nullable
    //       if (x == null) { break; }        // records "x != null" for the rest of the function
    //       x = peek(cur);
    //       x.length();                      // round 2: DROPPED
    //     }
    //
    // On the next iteration `x` holds a value produced INSIDE the body, which nothing established
    // outside the loop can constrain. Dropping the facts is the conservative direction: it can only
    // regain a false positive, never hide one.
    //
    // Only the BODY label clears. `L_WHILE_COND_0` is the condition, evaluated before any body fact
    // exists, so clearing there would discard the loop's own guard for no reason.
    if (isLoopBodyLabel(name)) {
      activeFalseEdgeGuards.clear();
      guardsPendingFalseEdge.clear();
    }
    // entering a loop's EXIT label retires the guards the loop CONDITION pushed.
    //
    // `while (a != null) { ... } a.length();` is a real NPE -- the loop may have exited precisely
    // because `a` was null -- but the condition's guard survives the loop:
    //
    //     t_t0 = a NE null
    //     if t_t0 goto L_WHILE_BODY_1 else goto L_WHILE_END_2   // pushes `a != null`
    //     L_WHILE_BODY_1: ...
    //       goto L_WHILE_COND_0        // back edge: isBlockTerminatingJump says false, no pop
    //     L_WHILE_END_2:               // the exit edge FALLS THROUGH -- there is no JUMP here
    //       a.length()                 // still "guarded" -> suppressed
    //
    // The exit edge carries no JUMP, so no pop can ever fire for it. The only place the join is
    // visible is the label itself, so that is where the condition's guards have to go.
    //
    // This is the conservative direction: dropping a guard can only regain a false positive, never
    // hide one. It is scoped to loop-exit labels so an `if`'s merge point, which legitimately
    // inherits its branch's facts, is untouched.
    if (isLoopExitLabel(name)) {
      retireGuardsIntroducedByLoopCondition();
    }
    java.util.List<Guard> pending = guardsPendingFalseEdge.get(name);
    if (pending == null || pending.isEmpty()) return;
    activeFalseEdgeGuards.computeIfAbsent(name, k -> new java.util.ArrayList<>()).addAll(pending);
  }

  /**
   * drop every guard that constrains {@code v}, because {@code v} is being rebound.
   *
   * <p>Covers all three places a guard can live: the active stack, the pending false-edge queue,
   * and the label-keyed false-edge table. Missing the last one is what made the loop case look
   * unaffected by the earlier attempts, because {@code isProvenNonNull} falls back to {@link
   * #isRefutedBy}, which reads that table and nowhere else.
   */
  private void retireGuardsFor(TacOperand.TacVar v) {
    if (v == null) return;
    activeGuards.removeIf(g -> g.boundedVar().equals(v));
    guardedVars.remove(v);
    propagatableSources.remove(v);
    lastCompare.remove(v);
    java.util.Iterator<java.util.Map.Entry<String, java.util.List<Guard>>> it =
        activeFalseEdgeGuards.entrySet().iterator();
    while (it.hasNext()) {
      java.util.Map.Entry<String, java.util.List<Guard>> e = it.next();
      e.getValue().removeIf(g -> g.boundedVar().equals(v));
      if (e.getValue().isEmpty()) it.remove();
    }
    java.util.Iterator<java.util.Map.Entry<String, java.util.List<Guard>>> pit =
        guardsPendingFalseEdge.entrySet().iterator();
    while (pit.hasNext()) {
      java.util.Map.Entry<String, java.util.List<Guard>> e = pit.next();
      e.getValue().removeIf(g -> g.boundedVar().equals(v));
      if (e.getValue().isEmpty()) pit.remove();
    }
  }

  /**
   * is this the BODY label of a loop?
   *
   * <p>The translator emits {@code L_WHILE_BODY_n} / {@code L_FOR_BODY_n} for the body and {@code
   * L_WHILE_COND_n} / {@code L_WHILE_END_n} for the condition and the exit. Only the body is the
   * region where pre-loop facts stop applying.
   */
  private static boolean isLoopBodyLabel(String label) {
    if (label == null || label.isEmpty()) return false;
    final String upper = label.toUpperCase(java.util.Locale.ROOT);
    return upper.contains("_BODY_");
  }

  /**
   * is this the EXIT label of a loop, i.e. the point control reaches when the loop condition
   * finally fails?
   *
   * <p>The translator emits {@code L_WHILE_END_n} / {@code L_FOR_END_n} / {@code L_DO_END_n}. A
   * {@code break} also targets an {@code _END_} label, but reaching that label is the same join
   * either way: past it, no fact established inside the loop still holds.
   */
  private static boolean isLoopExitLabel(String label) {
    if (label == null || label.isEmpty()) return false;
    final String upper = label.toUpperCase(java.util.Locale.ROOT);
    return upper.startsWith("L_WHILE_END")
        || upper.startsWith("L_FOR_END")
        || upper.startsWith("L_DO_END");
  }

  /**
   * retire every guard still active when control reaches a loop's exit join.
   *
   * <p>Clearing wholesale rather than popping to a frame marker is deliberate. Any guard whose
   * {@code if}-block already ended has been popped by that block's own JUMP, so what remains on the
   * stack at the loop join belongs to the loop condition. Clearing more can only regain a false
   * positive, never hide a finding.
   */
  private void retireGuardsIntroducedByLoopCondition() {
    if (activeGuards.isEmpty()) return;
    activeGuards.clear();
    frameMarkers.clear();
    guardedVars.clear();
    propagatableSources.clear();
  }

  /**
   * is this JUMP the one that closes an if-block, rather than loop control?
   *
   * <p>True for the jump to a merge label, false for the jump a {@code break} / {@code continue}
   * lowers to. Those target the loop's own labels ({@code L_WHILE_BODY_1}, {@code L_WHILE_END_2},
   * {@code L_FOR_*}...), which belong to the enclosing loop rather than to the guarded block.
   *
   * <p>An unrecognised target answers {@code true}, preserving the pre- behaviour. That direction
   * can only regain a false positive; misjudging a real block-terminating jump the other way would
   * hide a finding.
   */
  private static boolean isBlockTerminatingJump(String target) {
    if (target == null || target.isEmpty()) return true;
    final String upper = target.toUpperCase(java.util.Locale.ROOT);
    return !(upper.startsWith("L_WHILE")
        || upper.startsWith("L_FOR")
        || upper.startsWith("L_DO")
        || upper.contains("CONTINUE")
        || upper.contains("BREAK"));
  }

  private void onJump(String target) {
    if (activeGuards.isEmpty()) return;
    // only the JUMP that ENDS a branch block may pop.
    //
    // `break` and `continue` also lower to an unconditional JUMP, and popping on those
    // desynchronises the stack: two pops for one push, so an outer scope inherits guards it never
    // had. A loop like
    //
    //     while (cur != null) {
    //       StringBuilder x = peek(cur);
    //       if (x == null) { break; }   // JUMP to the loop's end label
    //       x = peek(cur);
    //       x.length();                  // real NPE, suppressed by a leaked guard
    //     }
    //
    // reported nothing, while the identical loop without the `break` reported correctly.
    //
    // The only signal the linear IR carries is the target label. Loop-control targets belong to the
    // enclosing loop, not to the if-block whose guard is on the stack.
    if (!isBlockTerminatingJump(target)) {
      return;
    }
    // Pop to the recorded frame
    // size. The frameMarkers stack records
    // the stack size BEFORE the BRANCH push;
    // pop until the current size matches
    // the top frame marker. This handles
    // multi-guard BRANCH push correctly
    // (without this, only 1 guard pops and
    // the rest leak into the outer scope).
    int targetSize;
    if (frameMarkers.isEmpty()) {
      targetSize = activeGuards.size() - 1;
    } else {
      targetSize = frameMarkers.pop();
    }
    while (activeGuards.size() > targetSize) {
      Guard popped = activeGuards.pop();
      // Only remove the var from guardedVars
      // if no other active guard still names
      // it (handles nested ifs).
      boolean stillGuarded = false;
      for (Guard g : activeGuards) {
        if (g.boundedVar().equals(popped.boundedVar())) {
          stillGuarded = true;
          break;
        }
      }
      if (!stillGuarded) {
        guardedVars.remove(popped.boundedVar());
      }
    }
  }

  /**
   * has {@code var} been proven not to equal {@code literal} on this path?
   *
   * <p>Answers the "early-return guard" idiom:
   *
   * <pre>
   *   if (data == 0) { return 0; }
   *   return a / data;          // isRefutedBy(data, "0") == true
   * </pre>
   *
   * <p>Only false-edge negations count. A guard on the active stack is an if-true condition and
   * says nothing about the value once the block is left.
   */
  public boolean isRefutedBy(TacOperand.TacVar var, String literal) {
    if (var == null || literal == null) return false;
    final String lit = literal.trim();
    for (java.util.List<Guard> gs : activeFalseEdgeGuards.values()) {
      for (Guard g : gs) {
        if (g.boundedVar().equals(var) && g.literalDescription().trim().equals(lit)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * name-based variant of {@link #isRefutedBy}, for callers that only have the variable name parsed
   * out of a sink predicate string.
   */
  public boolean isRefutedBy(String varName, String literal) {
    if (varName == null || literal == null) return false;
    final String lit = literal.trim();
    for (java.util.List<Guard> gs : activeFalseEdgeGuards.values()) {
      for (Guard g : gs) {
        if (g.boundedVar().name().equals(varName) && g.literalDescription().trim().equals(lit)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * has {@code var} been proven non-zero on this path?
   *
   * <p>Unifies the two shapes that establish the fact, so {@code DivByZeroChecker} (taint family)
   * and {@code BoundaryConstraintChecker} (boundary family) agree instead of one of them reporting
   * a false positive the other already suppressed:
   *
   * <ul>
   *   <li>active NE guard — {@code if (data != 0) { a / data; }}
   *   <li>false-edge EQ guard — {@code if (data == 0) { return 0; } a / data;}
   * </ul>
   *
   * <p>Only the literal {@code 0} is special-cased, because that is the only value whose absence
   * makes an integer divisor unsafe. Callers that need a general "excludes literal L" query use
   * {@link #isRefutedBy(TacOperand.TacVar, String)} directly.
   */
  public boolean isProvenNonZero(TacOperand.TacVar var) {
    if (var == null) return false;
    // The active NE guard may be against a literal other than 0 (`data != -1`), which says
    // nothing about zero. Match the recorded literal, not the operator alone.
    for (Guard g : activeGuards) {
      if (g.boundedVar().equals(var)
          && g.op() == BinaryOperator.NE
          && isZeroLiteral(g.literalDescription())) {
        return true;
      }
    }
    return isRefutedBy(var, "0");
  }

  /** Does this literal description denote the integer zero? Tolerates FIELDLOAD-style text. */
  private static boolean isZeroLiteral(String literalDescription) {
    if (literalDescription == null) return false;
    String t = literalDescription.trim();
    return t.equals("0") || t.equals("+0") || t.equals("-0");
  }

  // ── null-ness guards ──────────────────────────────────────────────────

  /**
   * is {@code var} provably non-null on this path?
   *
   * <p>Two source constructs establish the fact, and both are needed because they appear in
   * opposite halves of the same idiom:
   *
   * <pre>
   *   if (v != null) { v.use(); }   // active guard: NE against the null literal
   *   if (v == null) return;        // false edge: the EQ guard's negation
   *   v.use();                      // safe in both shapes
   * </pre>
   *
   * <p>The second shape is the early-return guard, and it is carried by {@link
   * #activeFalseEdgeGuards} rather than {@link #activeGuards}: the JUMP that ends the then-block
   * pops the guard before the code after the {@code if} is reached, so at the use site the active
   * stack is empty and the fact only exists on the false edge. Reading it from the active stack
   * alone would make the two spellings disagree, which is the same defect fixed for the
   * divide-by-zero case.
   *
   * <p>Only {@code null} is special-cased. {@code v != someOtherValue} says nothing about
   * null-ness, so the literal is matched, not just the operator — the same discipline {@link
   * #isProvenNonZero} applies to zero.
   */
  public boolean isProvenNonNull(TacOperand.TacVar var) {
    if (var == null) return false;
    for (Guard g : activeGuards) {
      if (g.boundedVar().equals(var)
          && g.op() == BinaryOperator.NE
          && isNullLiteral(g.literalDescription())) {
        return true;
      }
    }
    // The early-return shape: `if (v == null) return;` establishes `v != null` on the false edge.
    return isRefutedBy(var, "null");
  }

  /**
   * is {@code var} provably <em>null</em> on this path?
   *
   * <p>Only a true-edge {@code EQ} guard establishes this. A false-edge {@code NE} guard ({@code if
   * (v != null) return;}) leaves {@code v} either null or unreachable, which is not the same as "is
   * null", so it is deliberately not consulted: answering true there would make the NullDeref
   * checker fire on the safe tail of a guard that already returned.
   */
  public boolean isProvenNull(TacOperand.TacVar var) {
    if (var == null) return false;
    for (Guard g : activeGuards) {
      if (g.boundedVar().equals(var)
          && g.op() == BinaryOperator.EQ
          && isNullLiteral(g.literalDescription())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Every variable currently proven non-null by an active or false-edge guard.
   *
   * <p>Exists so an alias-aware consumer does not have to re-implement the two-source rule: it can
   * ask for the proven set once and test membership. Snapshot semantics — the caller gets the set
   * as of this instruction, and a later BRANCH/JUMP will not retroactively change it.
   */
  public java.util.Set<TacOperand.TacVar> provenNonNullVars() {
    java.util.Set<TacOperand.TacVar> out = new java.util.HashSet<>();
    for (Guard g : activeGuards) {
      if (g.op() == BinaryOperator.NE && isNullLiteral(g.literalDescription())) {
        out.add(g.boundedVar());
      }
    }
    for (java.util.List<Guard> gs : activeFalseEdgeGuards.values()) {
      for (Guard g : gs) {
        if (g.op() == BinaryOperator.EQ && isNullLiteral(g.literalDescription())) {
          out.add(g.boundedVar());
        }
      }
    }
    return out;
  }

  /** Does this literal description denote the {@code null} literal? */
  private static boolean isNullLiteral(String literalDescription) {
    if (literalDescription == null) return false;
    return literalDescription.trim().equalsIgnoreCase("null");
  }

  /** Visible for testing. Snapshot of false-edge guards currently in force. */
  public java.util.Map<String, java.util.List<Guard>> activeFalseEdgeGuards() {
    return new java.util.HashMap<>(activeFalseEdgeGuards);
  }

  /**
   * Returns true if {@code var} is currently bounded by an active guard (same as {@link
   * #isGuardedBy}) OR if {@code var} has a derived guard from a prior {@code BINOP} or {@code MOVE}
   * that propagated a guard from a guarded source. Used by checkers like {@code ArrayIndexChecker}
   * that need to recognise "guarded base" patterns (e.g. {@code pos - 3} when {@code pos >= 3} is
   * active).
   */
  public boolean isGuardedByIncludingDerived(TacOperand.TacVar var, BinaryOperator op) {
    if (isGuardedBy(var, op)) return true;
    java.util.List<Guard> dgs = derivedGuards.get(var);
    if (dgs == null) return false;
    for (Guard g : dgs) {
      if (g.op() == op) return true;
    }
    return false;
  }

  /** Visible for testing. Snapshot of derived-guards map. */
  public java.util.Map<TacOperand.TacVar, java.util.List<Guard>> derivedGuards() {
    return new java.util.HashMap<>(derivedGuards);
  }

  /**
   * Propagate guards through arithmetic {@code BINOP}. When {@code dest = var ± literal} and {@code
   * var} is active-guarded, add {@code dest} to {@link #derivedGuards} with the same operator and
   * an adjusted literal. For {@code GE}/{@code GT} (lower-bound) we only propagate when the
   * resulting bound is non-negative (otherwise the index could be negative).
   */
  private void recordArithIfApplicable(TacInstruction ins) {
    BinaryOperator op = ins.binop;
    if (op != BinaryOperator.ADD && op != BinaryOperator.SUB) return;
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return;
    if (ins.lhs1 == null || ins.lhs2 == null) return;

    // Find which side is the "var" (TacVar) and which is the "literal" (Const).
    TacOperand.TacVar varOp = null;
    Long literal = null;
    if (ins.lhs1 instanceof TacOperand.TacVar v && ins.lhs2 instanceof TacOperand.Const c) {
      varOp = v;
      literal = parseLongConst(c);
    } else if (ins.lhs2 instanceof TacOperand.TacVar v && ins.lhs1 instanceof TacOperand.Const c) {
      varOp = v;
      literal = parseLongConst(c);
    }
    if (varOp == null || literal == null) return;

    // Fast-path: if varOp is not
    // in the propagatable source set, no
    // propagation can happen. Skip the more
    // expensive lookups. This is the common
    // case (most BINOPs in a function have
    // no relation to a guarded var).
    if (!propagatableSources.contains(varOp)) return;

    // Compute the offset: ADD adds, SUB subtracts.
    // (The TAC BINOP convention is lhs1 OP lhs2 = result.)
    long offset = (op == BinaryOperator.ADD) ? literal : -literal;

    // Use allGuardsSeen (cumulative, per-function)
    // rather than activeGuards (current stack) to find
    // candidate guards. The DFA worklist processes
    // instructions in BB-RPO order, which can visit a
    // BINOP after the matching JUMP has popped the
    // active guard. allGuardsSeen records every guard
    // that was ever pushed during this function and is
    // not popped, so it's robust to any worklist
    // order. The propagation itself is conservative:
    // once added, a derived guard is not removed.
    java.util.List<Guard> varGuards = new java.util.ArrayList<>();
    for (Guard g : allGuardsSeen) {
      if (g.boundedVar().equals(varOp)) {
        varGuards.add(g);
      }
    }
    // Also check derivedGuards for chain propagation
    // (e.g. y = x - 1 where x = pos - 3, propagated).
    java.util.List<Guard> xDerived = derivedGuards.get(varOp);
    if (xDerived != null) {
      for (Guard g : xDerived) {
        if (g.boundedVar().equals(varOp)) varGuards.add(g);
      }
    }
    if (varGuards.isEmpty()) return;

    // For each guard, compute the propagated guard and add to derived.
    java.util.List<Guard> newDerived = new java.util.ArrayList<>();
    for (Guard g : varGuards) {
      Long bound = parseBoundLiteral(g);
      if (bound == null) continue;
      long newBound = bound + offset;
      // For GE (lower bound, inclusive): only useful
      // if newBound >= 0.
      if (g.op() == BinaryOperator.GE && newBound < 0) {
        continue;
      }
      // For GT (lower bound, strict): `t > newBound`
      // is equivalent to `t >= newBound+1` for
      // integer arithmetic. Allow newBound >= -1
      // (which means t >= 0).
      if (g.op() == BinaryOperator.GT && newBound < -1) {
        continue;
      }
      String newLiteral = String.valueOf(newBound);
      newDerived.add(new Guard(dest, g.op(), newLiteral));
    }
    if (newDerived.isEmpty()) return;
    derivedGuards.merge(
        dest,
        newDerived,
        (old, add) -> {
          // -- bound the merged list. Without this,
          // the merge lambdas accumulate without DERIVED_GUARD_CAP, and a
          // 3682-file Juliet dir OOMs in the recordMove path
          // even with the early-return guard. DERIVED_GUARD_CAP at DERIVED_GUARD_CAP.
          java.util.List<Guard> combined = new java.util.ArrayList<>(old);
          for (Guard g : add) {
            if (combined.size() >= DERIVED_GUARD_CAP) break;
            combined.add(g);
          }
          return combined;
        });
    derivedGuardedVars.add(dest);
    // Mirror onto the fast-path
    // propagation source index so chain
    // propagation (e.g. y = x - 1 where
    // x = pos - 3) works without an extra
    // hash lookup.
    propagatableSources.add(dest);
  }

  /**
   * Propagate guards through {@code MOVE}. When {@code dest = var} and {@code var} is
   * active-guarded, propagate the same guards to {@code dest}. Used for the {@code MOVE t_alias =
   * var} pattern where the alias gets the same bounds.
   */
  private void recordMoveIfApplicable(TacInstruction ins) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return;
    if (!(ins.lhs1 instanceof TacOperand.TacVar src)) return;
    // Fast-path: see recordArithIfApplicable
    // for the same gate.
    if (!propagatableSources.contains(src)) return;
    // -bound the per-dest and per-src derived-guards
    // lists. Without this, Juliet variant 22 / 51 / 81 cross-
    // file scans blow up the list unbounded: every MOVE on a
    // propagated var adds a new entry, and merge() then
    // doubles on subsequent MOVEs. The OOM shows up at line
    // 791 (the per-src loop) because src's own derivedGuards
    // list grew without bound as callers chained 22a --22b --        // ... --sink across thousands
    // of files. DERIVED_GUARD_CAP both sides at
    // 64 entries --well above the realistic per-var guard
    // count (single digits in practice for Juliet-style
    // inputs), so the FP / FN surface is unchanged.
    final int DERIVED_GUARD_CAP = 64;
    java.util.List<Guard> existing = derivedGuards.get(dest);
    if (existing != null && existing.size() >= DERIVED_GUARD_CAP) {
      derivedGuardedVars.add(dest);
      propagatableSources.add(dest);
      return;
    }
    // Same as recordArithIfApplicable, use
    // allGuardsSeen for worklist-order robustness.
    // -- bound srcGuards. allGuardsSeen grows
    // unbounded as Juliet variant 22 / 51 chains accumulate
    // guards across 1000+ files; iterating the full set
    // and copying entries that match src turns srcGuards
    // into another unbounded list. DERIVED_GUARD_CAP at DERIVED_GUARD_CAP.
    java.util.List<Guard> srcGuards = new java.util.ArrayList<>();
    if (allGuardsSeen.size() <= DERIVED_GUARD_CAP) {
      for (Guard g : allGuardsSeen) {
        if (g.boundedVar().equals(src)) srcGuards.add(g);
      }
    } else {
      // allGuardsSeen already at scale -- treat as
      // "everything is bounded". Conservatively
      // bound the dest to any literal we've seen.
      srcGuards.add(
          new Guard(src, org.plumb.tac.ir.BinaryOperator.LT, "<guard-DERIVED_GUARD_CAP>"));
    }
    java.util.List<Guard> xDerived = derivedGuards.get(src);
    if (xDerived != null) {
      // DERIVED_GUARD_CAP the src contribution to the per-call
      // accumulation. The full src list is preserved in
      // derivedGuards for the next MOVE; we just don't
      // blow up the call-time srcGuards array.
      int limit = Math.min(xDerived.size(), DERIVED_GUARD_CAP);
      for (int i = 0; i < limit; i++) {
        Guard g = xDerived.get(i);
        if (g.boundedVar().equals(src)) srcGuards.add(g);
      }
    }
    if (srcGuards.isEmpty()) return;
    // For MOVE, copy guards verbatim (no offset adjustment).
    java.util.List<Guard> newDerived = new java.util.ArrayList<>();
    for (Guard g : srcGuards) {
      newDerived.add(new Guard(dest, g.op(), g.literalDescription()));
    }
    derivedGuards.merge(
        dest,
        newDerived,
        (old, add) -> {
          // -- bound the merged list. Without this,
          // the merge lambdas accumulate without DERIVED_GUARD_CAP, and a
          // 3682-file Juliet dir OOMs in the recordMove path
          // even with the early-return guard. DERIVED_GUARD_CAP at DERIVED_GUARD_CAP.
          java.util.List<Guard> combined = new java.util.ArrayList<>(old);
          for (Guard g : add) {
            if (combined.size() >= DERIVED_GUARD_CAP) break;
            combined.add(g);
          }
          return combined;
        });
    derivedGuardedVars.add(dest);
    propagatableSources.add(dest);
  }

  /**
   * Parse a {@link TacOperand.Const} as a {@code long}, or {@code null} if it is not a numeric
   * literal. Used to compute arithmetic offsets for derived guard propagation.
   */
  private static Long parseLongConst(TacOperand.Const c) {
    if (c == null) return null;
    Object v = c.value();
    if (v instanceof Long l) return l;
    if (v instanceof Integer i) return (long) i;
    if (v instanceof Short s) return (long) s;
    if (v instanceof Byte b) return (long) b;
    if (v instanceof Character ch) return (long) (int) ch;
    if (v instanceof Number n) return n.longValue();
    return null;
  }

  /**
   * Parse a guard's literal description as a {@code long} bound. Returns {@code null} if the
   * literal is not numeric (e.g. {@code "<array-length>"} or {@code "Byte.MAX_VALUE"}).
   */
  private static Long parseBoundLiteral(Guard g) {
    String desc = g.literalDescription();
    if (desc == null) return null;
    try {
      return Long.parseLong(desc.trim());
    } catch (NumberFormatException nfe) {
      return null;
    }
  }
}
