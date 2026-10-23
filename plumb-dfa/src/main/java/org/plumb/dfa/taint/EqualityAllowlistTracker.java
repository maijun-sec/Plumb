package org.plumb.dfa.taint;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.BinaryOperator;
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
 *
 * <h2>R1120: a negated equality must GOVERN the body, not merely appear in the condition</h2>
 *
 * <p>Polarity alone is necessary but not sufficient, and the second independent audit found the
 * gap. These two shapes are byte-identical to the negation handler:
 *
 * <pre>
 *   sound:    t2 = NOT t1 ;          if t2 goto L_THEN
 *   unsound:  t2 = NOT t1 ; t5 = t2 LOR t4 ; if t5 goto L_THEN
 * </pre>
 *
 * <p>In the second, {@code other == "users"} makes the disjunction true whatever {@code col} is, so
 * the {@code col = "createdAt"} substitution never runs and {@code col} reaches the sink fully
 * attacker-controlled. Recording the negation at the UNARYOP -- which is what R1115 did -- cannot
 * tell these apart, so the proof is deferred to the BRANCH, the only point at which the boolean
 * combine is known. Conjunctions remain sound and stay supported: every conjunct must hold for the
 * body to run, so an {@code &&} allowlist is the correct idiom.
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
    this.candidateNegated.clear();
    this.andLeaves.clear();
    this.orResults.clear();
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
      // R1115: a NEGATED equality guard is the reject-and-assign idiom. Only these may
      // license proving the variable safe; a positive guard's THEN block leaves the
      // fall-through tainted.
      //
      // R1120: recording the candidate here is NECESSARY BUT NOT SUFFICIENT. At this point we know
      // only that *something* was negated -- not that the negation governs the body. The proof is
      // completed in the BRANCH case below, once we know how the negated temp was combined into the
      // condition. Promoting from here (the pre-R1120 behaviour) is exactly the false negative:
      //   t2 = NOT t1 ; t5 = t2 LOR t4 ; if t5 goto L_THEN
      // `t4` makes the branch true no matter what `t2` says, so the body may never execute.
      candidateNegated.put(negated.name(), testedByResult.get(from.name()));
      return false;
    }

    if (ins.kind == TacInstruction.Kind.BINOP
        && ins.lhs1 instanceof TacOperand.TacVar a
        && ins.lhs2 instanceof TacOperand.TacVar b) {
      // R1120: record how this logical combine consumed its operands, so the BRANCH that follows
      // can tell a conjunction from a disjunction. Deciding at the combine -- rather than at the
      // branch -- is what lets us retire OR-derived facts before anything downstream reads them.
      if (ins.binop == BinaryOperator.LOR) {
        if (candidateNegated.containsKey(a.name())) orResults.add(a.name());
        if (candidateNegated.containsKey(b.name())) orResults.add(b.name());
      } else if (ins.binop == BinaryOperator.LAND) {
        if (candidateNegated.containsKey(a.name())) andLeaves.add(a.name());
        if (candidateNegated.containsKey(b.name())) andLeaves.add(b.name());
      }
      return false;
    }

    if (ins.kind == TacInstruction.Kind.BRANCH && ins.lhs1 instanceof TacOperand.TacVar cond) {
      // R1120: this is the only point where "which comparison governs the body" is decidable.
      //
      // A variable may be proven safe only when its negated comparison reaches the branch as the
      // WHOLE condition, or as a conjunct of an AND. Both mean the body runs only when every
      // rejection test held, so every path carrying a non-listed value is the one that gets
      // substituted.
      //
      // An OR-derived negation proves nothing at all: the sibling disjunct can make the condition
      // true by itself, the body is then skipped, and the user's value flows straight through.
      for (Map.Entry<String, String> e : List.copyOf(candidateNegated.entrySet())) {
        String tempName = e.getKey();
        String subject = e.getValue();
        boolean governs = tempName.equals(cond.name()) || andLeaves.contains(tempName);
        if (!governs || orResults.contains(tempName)) continue;
        pendingNegatedGuards.add(subject);
      }
      // Candidates are function-scoped state, so drop them once their branch has been judged. A
      // later guard must be re-argued from its own comparisons, never inherit this one's proof.
      candidateNegated.clear();
      andLeaves.clear();
      orResults.clear();
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
   * Negated-comparison temp -&gt; the variable that comparison tested, as of the last UNARYOP.
   *
   * <p>R1120. A candidate is a claim that is <em>not yet proved</em>: the negation exists, but
   * nothing has established that it governs the guarded body. Promotion into {@link
   * #pendingNegatedGuards} happens only in the BRANCH case, once the boolean combine is known.
   */
  private final java.util.Map<String, String> candidateNegated = new java.util.LinkedHashMap<>();

  /**
   * Negated-comparison temps consumed as a CONJUNCT by an {@code AND} since the last branch.
   *
   * <p>R1120. A conjunct is safe to promote: the body runs only when every conjunct holds, so a
   * sibling conjunct can only make the body run <em>less</em> often, never skip it for an unrelated
   * reason. A {@code &&} allowlist is the correct idiom and must keep working.
   */
  private final Set<String> andLeaves = new LinkedHashSet<>();

  /**
   * Negated-comparison temps consumed as a DISJUNCT by an {@code OR} since the last branch.
   *
   * <p>R1120. Permanently disqualifying, because an OR sibling can make the condition true on its
   * own: the body is then skipped while the variable keeps its value. Recorded so a temp that was
   * both conjuncted and disjuncted cannot be laundered through the conjunction.
   */
  private final Set<String> orResults = new LinkedHashSet<>();

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