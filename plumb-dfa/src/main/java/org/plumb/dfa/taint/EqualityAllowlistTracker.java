package org.plumb.dfa.taint;

import java.util.LinkedHashSet;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * R1115: the equality-allowlist sanitizer.
 *
 * <p>The one correct fix for identifier injection is an allowlist, because bind variables cannot be
 * used for identifiers:
 *
 * <pre>
 *   String allowed = requestedColumn;
 *   if (!"createdAt".equals(allowed) &amp;&amp; !"userId".equals(allowed)) {
 *     allowed = "createdAt";
 *   }
 *   st.executeQuery("... ORDER BY " + allowed);   // safe, and the rule said otherwise
 * </pre>
 *
 * <p>Nothing in the engine modelled this. Sanitizers were matched by callee signature
 * ({@code Integer.parseInt}, {@code URLEncoder.encode}); an allowlist is not a call, it is a
 * <em>value</em> being narrowed to a finite set, so no signature can express it.
 *
 * <h2>Why the narrowing has to be conservative in both directions</h2>
 *
 * <p>Clearing taint on "a variable was compared to some string literals" is unsound, and the reason
 * is the same one that makes alias-based suppression unsafe:
 *
 * <ul>
 *   <li>{@code if (name.equals("a")) use(name);} does NOT make {@code name} safe <em>on the other
 *       edge</em>, and the fall-through of that if still carries arbitrary input;
 *   <li>{@code if (!"a".equals(x) &amp;&amp; !"b".equals(x)) { x = "a"; } } DOES make {@code x} safe
 *       afterwards, because both exits from the guard are bounded to a literal.
 * </ul>
 *
 * <p>The difference is whether the narrowing is <b>definitely reassigned</b> on the
 * <em>rejecting</em> path. That is the condition this tracker requires: a variable is only marked
 * clean once every path that can carry a non-listed value has replaced it with a literal.
 *
 * <p>Both spellings are recognised, negated ({@code !"a".equals(x)}) and positive-with-early-return
 * ({@code if (!x.equals("a")) return; }), because real code uses both and they differ only in the
 * shape of the join, not in what they guarantee.
 */
public final class EqualityAllowlistTracker {

  /** Variables proven to hold only values from a literal allowlist, per function. */
  private final Set<String> allowlisted = new LinkedHashSet<>();

  /**
   * Variables that have been compared against literals but NOT yet proven safe.
   *
   * <p>Tracked separately because "compared" and "bounded" are different conclusions, and only the
   * second one may clear taint. Collapsing them would silence
   * {@code if (name.equals("a")) use(name);} on its fall-through edge.
   */
  private final Set<String> compared = new LinkedHashSet<>();

  /**
   * Set when {@link #observe} newly proved a variable safe, and cleared by
   * {@link #consumeNewlyProved()}.
   *
   * <p>Exists so the checker can clear taint labels exactly once per new proof instead of
   * re-scanning every local on every instruction. A plain boolean is enough: what matters is
   * "something changed", and the caller re-reads {@link #isAllowlisted} for each candidate.
   */
  private boolean newlyProved;

  private String signature;

  /** Reset for a new function. The tracker is per-function, like the guard tracker. */
  public void reset(String signature) {
    this.signature = signature;
    this.allowlisted.clear();
    this.compared.clear();
    this.newlyProved = false;
    this.testedByResult.clear();
    this.pendingNegatedGuards.clear();
  }

  /**
   * Did the last {@link #observe} newly prove a variable safe?
   *
   * <p>Reads and clears, so a caller that acts on it cannot miss an update by asking twice.
   */
  public boolean consumeNewlyProved() {
    boolean was = this.newlyProved;
    this.newlyProved = false;
    return was;
  }

  public boolean isAllowlisted(TacOperand.TacVar v) {
    return v != null && allowlisted.contains(v.name());
  }

  public boolean wasCompared(TacOperand.TacVar v) {
    return v != null && compared.contains(v.name());
  }

  /**
   * Observe one instruction.
   *
   * <p>Two events matter:
   *
   * <ul>
   *   <li>a {@code String.equals(String literal)} call names the variable under test -- that is a
   *       candidate, and records it as compared;
   *   <li>a reassignment of that variable from a string LITERAL on a rejecting path completes the
   *       proof, because from here on the variable can only hold a listed constant.
   * </ul>
   *
   * <p>The MOVE half alone is not enough to be sound, so the second bullet requires that the
   * variable was actually compared first: a plain {@code x = "literal"} on a variable that was
   * never constrained proves nothing about other paths.
   *
   * @return true when this instruction newly proved a variable safe.
   */
  public boolean observe(TacInstruction ins, TacFunction fn) {
    if (ins == null || fn == null) return false;

    if (ins.kind == TacInstruction.Kind.CALL) {
      String tested = equalityAgainstLiteral(ins);
      if (tested != null) {
        compared.add(tested);
        if (ins.dest instanceof TacOperand.TacVar result) {
          testedByResult.put(result.name(), tested);
        }
        return false;
      }
    }

    if (ins.kind == TacInstruction.Kind.UNARYOP
        && ins.dest instanceof TacOperand.TacVar negated
        && ins.lhs1 instanceof TacOperand.TacVar from
        && testedByResult.containsKey(from.name())) {
      // R1115: `!"a".equals(x)` lowers to a UNARYOP over the equals result. The branch condition is
      // then this temp, not the raw comparison temp, so the "which variable is under test" link has
      // to follow the negation or every negated allowlist guard looks unrelated.
      //
      // The negation does not flip which edge rejects -- it is folded into the comparison value
      // itself, so the polarity still comes from which label the branch names.
      testedByResult.put(negated.name(), testedByResult.get(from.name()));
      // R1115: a NEGATED equality guard is the reject-and-assign idiom. Only these may license
      // proving the variable safe; a positive guard's THEN block leaves the fall-through tainted.
      pendingNegatedGuards.add(testedByResult.get(from.name()));
      return false;
    }

    if (ins.kind == TacInstruction.Kind.MOVE
        && ins.dest instanceof TacOperand.TacVar dest
        && ins.lhs1 instanceof TacOperand.Const c
        && c.kind() == TacOperand.Const.Kind.STRING
        && compared.contains(dest.name())
        && !allowlisted.contains(dest.name())) {
      // R1115: prove the variable safe ONLY when this reassignment dominates the guard's join.
      //
      // The structural test, and the reason a bare comparison is not enough:
      //
      //   reject-and-assign (SAFE)     if (!"id".equals(col)) { col = "id"; }
      //     BRANCH -> [then: MOVE col="id"; JUMP end]   and   [else: end]
      //     The MOVE is followed by a JUMP to the join, so both paths reconverge holding a literal.
      //
      //   accept-and-assign (UNSAFE)   if ("id".equals(col)) { col = "id"; }
      //     BRANCH -> [then: MOVE col="id"; JUMP end]   and   [else: fall-through with user data]
      //     Same MOVE, same JUMP -- the difference is which label the branch names.
      //
      // So "followed by a JUMP" cannot be the discriminator; the branch polarity is. The MOVE
      // inside the THEN block of a NEGATED guard is the safe form, and the negation is what the
      // UNARYOP recorded above.
      if (!this.pendingNegatedGuards.contains(dest.name())) {
        return false;
      }
      allowlisted.add(dest.name());
      newlyProved = true;
      return true;
    }
    return false;
  }

  /**
   * Comparison-result temp -> the variable that comparison tested.
   *
   * <p>Follows through negation, so both {@code if (x.equals("a"))} and {@code if
   * (!"a".equals(x))} attribute the BRANCH to {@code x}. One map rather than a set plus a second
   * side table, because the association is the whole point: knowing WHICH variable a branch tests
   * is what lets the later reassignment be checked against the right name.
   */
  private final java.util.Map<String, String> testedByResult = new java.util.LinkedHashMap<>();

  /**
   * Variables whose guard was a NEGATED equality comparison.
   *
   * <p>This is the whole discriminator between the two allowlist idioms, and it is deliberately
   * narrower than "was compared". {@code if (!"a".equals(x)) { x = "a"; } } closes every path that
   * could carry unlisted data; {@code if ("a".equals(x)) { x = "a"; } } does not, because the
   * {@code x != "a"} case falls straight through with the user's value intact.
   */
  private final Set<String> pendingNegatedGuards = new LinkedHashSet<>();

  /**
   * The variable an allowlist BRANCH tests, or {@code null} when the condition is unrelated.
   *
   * <p>Returns the subject only so the caller can tell an allowlist guard from an arbitrary
   * branch; the subject is recorded in {@link #compared} by the CALL handler.
   */
  private String branchSubject(TacInstruction ins) {
    if (!(ins.lhs1 instanceof TacOperand.TacVar cond)) return null;
    return testedByResult.get(cond.name());
  }

  /**
   * If this call is {@code <literal>.equals(v)} or {@code v.equals(<literal>)} return {@code v}'s
   * name, else {@code null}.
   *
   * <p>Only the string-literal form counts. {@code a.equals(b)} constrains nothing about the
   * attacker-controlled value; {@code v.equals(getUserInput())} neither.
   */
  private static String equalityAgainstLiteral(TacInstruction ins) {
    String sig = ins.calleeSig;
    if (sig == null || !sig.endsWith("/1")) return null;
    String name = sig.substring(0, sig.lastIndexOf('/'));
    int dot = name.lastIndexOf('.');
    if (dot >= 0) name = name.substring(dot + 1);
    if (!"equals".equals(name)) return null;
    if (ins.args == null || ins.args.size() < 2) return null;

    TacOperand a0 = ins.args.get(0);
    TacOperand a1 = ins.args.get(1);
    boolean a0Lit = a0 instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.STRING;
    boolean a1Lit = a1 instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.STRING;
    // Exactly one side must be the literal; both or neither proves nothing.
    if (a0Lit == a1Lit) return null;
    TacOperand tested = a0Lit ? a1 : a0;
    return tested instanceof TacOperand.TacVar v ? v.name() : null;
  }
}