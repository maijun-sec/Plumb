package org.plumb.dfa.checkers.taint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.points.AbstractObject;
import org.plumb.dfa.taint.GuardTracker;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * R1110: null-dereference of a value that came from a call, decided with alias knowledge.
 *
 * <h2>Relation to the AST rule</h2>
 *
 * <p>This is the dataflow counterpart of {@code
 * org.plumb.style.security.NullDerefFromReturnAstCheck}. Both express the same rule name ({@code
 * NULL_DEREF_FROM_RETURN}) and are registered under different ids — the AST one as {@code
 * ast-security-null-deref-from-return-ast}, this one as {@code dfa-taint-null-deref-from-return} —
 * deliberately, so both run. Neither is a migration of the other:
 *
 * <ul>
 *   <li>the AST rule also fires when the TAC translation failed, and it needs no dataflow engine;
 *   <li>this rule can see through containers, fields and cross-method returns, which a syntactic
 *       name walk cannot.
 * </ul>
 *
 * <h2>What the dataflow side adds</h2>
 *
 * <p>Three things, in increasing order of how much they matter:
 *
 * <ol>
 *   <li><b>Every call result is nullable, not just ones with suggestive names.</b> The AST rule and
 *       {@link NullDerefChecker} both restrict "might be null" to a {@code null} literal or a
 *       method whose name contains {@code Bad}/{@code Null}/{@code Nullable} — a heuristic aimed at
 *       the Juliet corpus. This rule treats <em>any</em> value arriving from a call as possibly
 *       null, which is what a caller of an unknown method actually has to assume.
 *   <li><b>A guard transfers across aliases, but only where it covers the whole reference.</b>
 *       {@code if (a == null) return;} constrains the object {@code a} denotes, so it covers every
 *       name whose points-to set is <em>contained</em> in {@code a}'s. It does not cover a name
 *       that may also hold some other, unguarded object; see {@link #isFullyGuardedByAlias} for why
 *       the weaker "some alias is guarded" test manufactures false negatives.
 *   <li><b>A fresh allocation is never null.</b> {@code new X()} followed by a dereference is not
 *       reported, because the points-to set proves an object is there.
 * </ol>
 *
 * <h2>What it deliberately does not claim</h2>
 *
 * <p>Only the non-null direction of a guard is used. Concluding "this reference may be null because
 * another one is" would <em>add</em> findings, and the alias map is conservative — it answers
 * "maybe the same object", so such a rule would fire on unrelated same-typed locals and trade a
 * precision fix for a false-positive generator.
 *
 * <p>An empty alias class means "no alias information", not "provably unshared", so it falls back
 * to reporting. Absence of proof is never treated as proof of safety.
 */
public final class NullDerefFromReturnDfaChecker extends TaintedCheckerBase {

  /**
   * References that may currently be null, with a short reason for the finding text.
   *
   * <p>Separate from the base class's {@code taintedVars} on purpose. That map marks every method
   * parameter as an untrusted <em>source</em> at the entry boundary, which is right for taint and
   * catastrophically wrong for null-ness: every parameter would be "maybe null" and every use of
   * one would be reported. {@link NullDerefChecker} hit exactly this and carries its own map for
   * the same reason.
   */
  private final Map<TacOperand.TacVar, String> nullableVars = new LinkedHashMap<>();

  /**
   * R1110: path-sensitive null guards for the current function.
   *
   * <p>Owned per checker, matching {@link DivByZeroChecker} and {@link NullDerefChecker}: the guard
   * state is per-function, and the base class keeps its own instance private.
   */
  private final GuardTracker nullGuards = new GuardTracker();

  public NullDerefFromReturnDfaChecker() {
    super(
        new TaintRule(
            "dfa-taint-null-deref-from-return",
            "plumb.nullable.may-be-null",
            "nullable.call-result.sinks",
            "CWE-476",
            "MEDIUM",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE));
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.nullableVars.clear();
    this.nullGuards.clear();
    resetAllocatedHere();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    super.checkInstruction(ins, fn, ctx);
    // AFTER super, so the base class's own handling of this instruction has already run and the
    // guard set reflects the current position before anything below consults it.
    this.nullGuards.track(ins);

    switch (ins.kind) {
      case MOVE -> {
        TacOperand.TacVar dest = asVar(ins.dest);
        if (dest == null) return;
        if (ins.lhs1 instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.NULL) {
          this.nullableVars.put(dest, "assigned null literal");
          return;
        }
        if (ins.lhs1 instanceof TacOperand.TacVar src) {
          // R1122: a rebinding replaces the variable's current value, so it also retires the
          // fresh-allocation claim the previous binding earned. Without this, `h = new String(..)`
          // followed by `h = b.v` would still let a later `x = h` erase x's nullable fact.
          // R1122: decide BEFORE recording this binding. Whether this MOVE is a copy or a rebinding
          // is
          // exactly what `reboundAwayFromAlias` hinges on.
          final boolean destAlreadyBound = this.everBound.contains(dest);
          if (dest != src) {
            this.allocatedHere.remove(dest);
            this.lastBindingWasCall.remove(dest);
            this.reboundAwayFromAlias.remove(dest);
          }
          if (dest != null) this.everBound.add(dest);
          // R1119: copy propagation is ADDITIVE and never subtracts.
          //
          // The previous code cleared the destination whenever the source was absent from the map.
          // That reads absence as "known non-null", which inverts a may-fact into a must-claim --
          // the exact error every other part of this engine is careful about (see the empty-set
          // contract on PointsToSolver.pointsTo, on aliasClassOf, and in the coverage check below).
          //
          // Two defects fell out of it, both silent:
          //
          //   direct:   x = peek();  y = clean();  x.length()
          //             -- `clean` has no recorded fact, so the mark on `x` was erased and the
          //             dereference was never even considered.
          //
          //   in a loop: the state is one map walked in linear order, with no fixpoint and no
          //             predecessor merge. On a later round the erasure destroys a fact the back
          //             edge just re-introduced, so a correctly-guarded `while (cur != null)`
          // looked
          //             safe on round 1 and the real round-2 NPE disappeared.
          //
          // Why additivity is also the PRECISE answer here, not just the safe one: a may-analysis
          // cannot retract facts, so once `x` may be null anywhere, a later assignment cannot make
          // it not-null for the paths already traversed. The `File f = null; f = open();` case the
          // old comment worried about is handled correctly anyway -- `open()` is a CALL, and the
          // CALL handler marks the result nullable on its own.
          if (this.nullableVars.containsKey(src)) {
            this.nullableVars.put(dest, "copy of " + src.name());
            // R1122: the copy inherits WHERE the value came from. `b = sink.get()` lowers to a
            // CALL into a temp followed by `b = t_t1`, so the flag recorded on the temp has to
            // travel with the MOVE -- otherwise the receiver looks like an ordinary copy and an
            // alias guard covers it.
            //
            // Rebinding, not copying, is what breaks the alias equivalence: a plain copy of a call
            // result still denotes ONE reference, so `StringBuilder b = a;` must stay
            // suppressible. Assigning a call result into a variable that already held a value is
            // what makes the monotonic union-find stop describing the truth.
            if (this.lastBindingWasCall.contains(src)) {
              this.lastBindingWasCall.add(dest);
              if (destAlreadyBound) {
                // Rebinding: `b` already held a reference, and a monotonic union-find still lists
                // the old aliases. That is what makes the old guard stop applying to `b`.
                this.reboundAwayFromAlias.add(dest);
              }
            } else {
              this.lastBindingWasCall.remove(dest);
              reboundAwayFromAlias.remove(dest);
            }
          } else if (isFreshAllocation(fn, src, ctx)) {
            // R1119: the ONE case where a MOVE may subtract.
            //
            // Copy propagation cannot retract, because a may-fact about a name is not a fact about
            // the value a later assignment brings. A fresh allocation is different in kind: `new
            // X()`
            // is an object by construction, so `x = new StringBuilder(..)` genuinely makes every
            // later read of `x` non-null. Without this branch, making MOVE additive turns the
            // Juliet `File temp = null; ... temp = new File(..); temp.close();` shape into a
            // false positive.
            //
            // The test is deliberately narrow -- a pointer to an object the solver actually knows
            // -- rather than "the source has no facts", which is the inversion this round removed.
            this.nullableVars.remove(dest);
          }
        }
        return;
      }
      case CALL -> {
        // Any value arriving from a call may be null: the callee is unknown, and a caller cannot
        // assume otherwise. This is the rule's namesake behaviour.
        TacOperand.TacVar dest = asVar(ins.dest);
        if (dest != null && ins.calleeSig != null && !ins.calleeSig.isEmpty()) {
          this.nullableVars.put(dest, "result of " + ins.calleeSig);
          // R1122: a call result is a value that arrived from outside; no guard already in scope
          // can constrain it, so a later alias guard must not suppress on its behalf.
          this.lastBindingWasCall.add(dest);
          this.allocatedHere.remove(dest);
          if (dest != null) this.everBound.add(dest);
        }
        // The receiver of a call is dereferenced by the call itself, so check it before falling
        // through to the generic dereference dispatch below.
        checkDereference(ins, fn, ctx);
        return;
      }
      case NEW, NEWARRAY -> {
        // A fresh allocation is an object by construction. Clearing here (rather than only
        // removing on MOVE) keeps a later reassignment of the same name honest too.
        TacOperand.TacVar dest = asVar(ins.dest);
        if (dest != null) {
          this.nullableVars.remove(dest);
          // R1122: record that this variable's CURRENT binding is the allocation, which is what
          // licenses the single subtraction MOVE. Noted on the binding, not on the name.
          this.allocatedHere.add(dest);
          this.lastBindingWasCall.remove(dest);
        }
        return;
      }
      case FIELDLOAD, ARRAYLOAD -> {
        // R1114: a load of a slot that the solver knows may hold null makes the DESTINATION
        // nullable. This is the path that was missing: `right.node = null` followed by
        // `right.node.payload.length()` used to produce no finding at all, because the checker only
        // ever learned nullness from a CALL result or a direct `x = null` MOVE.
        //
        // Consulted AFTER the dereference check on purpose -- the receiver's own nullness is the
        // question here, and the freshly-loaded temp is what the NEXT instruction will touch.
        checkDereference(ins, fn, ctx);
        TacOperand.TacVar dest = asVar(ins.dest);
        if (dest != null && fn != null && ctx.mayBeNull(fn.signature(), dest)) {
          this.nullableVars.put(dest, "load of a field that may hold null");
        }
        return;
      }
      case FIELDSTORE, ARRAYSTORE -> {
        checkDereference(ins, fn, ctx);
        return;
      }
      default -> {
        return;
      }
    }
  }

  /**
   * Emit a finding when the receiver of this instruction may be null and nothing on this path has
   * proven it safe.
   *
   * <p>The suppression order matters and is deliberately conservative:
   *
   * <ol>
   *   <li>the receiver must be a known-maybe-null reference — an untouched name is silent, which is
   *       what keeps the rule from reporting every method call in a file;
   *   <li>a null check on this path covering the receiver silences it;
   *   <li>a null check covering <em>any alias</em> silences it, because the check constrains the
   *       object rather than the name;
   *   <li>an alias class that the analysis could not compute at all falls through to reporting.
   * </ol>
   */
  private void checkDereference(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    TacOperand.TacVar receiver = receiverOf(ins);
    if (receiver == null) return;
    String reason = this.nullableVars.get(receiver);
    if (reason == null) return;
    if (this.nullGuards.isProvenNonNull(receiver)) {
      return;
    }
    if (fn != null && ctx != null && isFullyGuardedByAlias(receiver, fn, ctx)) {
      return;
    }
    String what =
        switch (ins.kind) {
          case FIELDLOAD -> "field load";
          case FIELDSTORE -> "field store";
          case ARRAYLOAD -> "array load";
          case ARRAYSTORE -> "array store";
          case CALL -> "method call";
          default -> "dereference";
        };
    ctx.reporter()
        .report(
            Finding.dfa(
                this.rule.ruleId(),
                this.rule.ruleId(),
                "Null dereference: "
                    + what
                    + " on possibly-null receiver `"
                    + receiver.name()
                    + "` ("
                    + reason
                    + ")",
                this.rule.severity(),
                this.rule.cwe(),
                "",
                ins.line,
                ins.column,
                receiver.name()));
  }

  /**
   * R1113: is every object this receiver may hold covered by a guard?
   *
   * <p><b>This method is the reason the rule does not under-report.</b> The tempting version is
   * "some alias of the receiver is proven non-null, therefore the receiver is safe". It is wrong,
   * and wrong in the direction that hides real bugs.
   *
   * <p>The alias map is a <em>may</em>-relation: membership means "these two names could be the
   * same object", never "they are". Confusing the two reads as proof is how a guard on one
   * reference silences a dereference of an unrelated one:
   *
   * <pre>
   *   items.add(first); items.add(second);
   *   StringBuilder other = items.get(1);   // points-to = {first's object, second's object}
   *   if (first == null) return;
   *   other.length();                       // `second` was never guarded
   * </pre>
   *
   * <p>{@code other} aliases {@code first} only because {@code List.add} cannot know the index it
   * will occupy, so both elements share one {@code ANY_KEY} cell. The conservative imprecision is
   * correct; acting on it as if it were precision is not. Suppressing here would be a false
   * negative manufactured by imprecision rather than by a real alias -- the one failure mode a
   * guard-transfer rule may never have.
   *
   * <p>So the guard must cover the receiver's <em>whole</em> points-to set, not one member of it:
   *
   * <ul>
   *   <li>no points-to information at all (empty set) means the analysis cannot say -- fall through
   *       to reporting, since absence of proof is never proof of safety;
   *   <li>otherwise every object the receiver may hold must be reachable from a proven-non-null
   *       name, or the finding stands.
   * </ul>
   *
   * <p>The consequence is deliberate asymmetry: the rule still suppresses the cheap copy case
   * ({@code b = a;} guarded {@code a}), because there both names denote the same set of objects. It
   * stops suppressing the imprecision case, which is where the coverage is incomplete.
   */
  /**
   * R1119/R1122: does this variable provably point at a fresh object?
   *
   * <p>The single subtraction MOVE is allowed to perform. {@code x = new StringBuilder(..)} binds a
   * value that is an object by construction, so any earlier may-null fact about the previous
   * binding is genuinely irrelevant to later reads.
   *
   * <p>R1122: the test can no longer be "the points-to set is non-empty". Points-to is a
   * MAY-analysis that never retracts, so a variable that once received an allocation keeps that
   * allocation in its set for the rest of the function even after being rebound:
   *
   * <pre>
   *   String h = new String("x");   // pointsTo(h) = { that allocation }
   *   h = b.v;                       // h rebound to a possibly-null field; set NOT updated
   *   String x = peek();             // x nullable (call result)
   *   x = h;                         // isFreshAllocation(h) would answer TRUE -> x's fact erased
   *   x.length();                    // REAL NPE
   * </pre>
   *
   * <p>The right question is whether this variable's CURRENT binding IS the allocation, which the
   * checker can answer from its own instruction stream: a {@code NEW} writes into its destination,
   * and any subsequent {@code MOVE} into that destination replaces the binding. A variable holding
   * an allocation is therefore exactly one that was assigned by a {@code NEW} and never rebound
   * since -- a may-analysis cannot say that, but the linear walk can.
   *
   * <p>Conservative direction: answering false can only regain a finding.
   */
  private boolean isFreshAllocation(TacFunction fn, TacOperand.TacVar v, AnalysisContext ctx) {
    if (fn == null || ctx == null || v == null) return false;
    return this.allocatedHere.contains(v);
  }

  /**
   * R1122: variables whose CURRENT binding is a {@code NEW} result.
   *
   * <p>Populated by the {@code NEW} handler and cleared whenever the variable is rebound, so a
   * stale allocation cannot survive the binding that displaced it. Per-function, like the rest of
   * the checker's state.
   */
  private final java.util.Set<TacOperand.TacVar> allocatedHere = new java.util.LinkedHashSet<>();

  /**
   * R1122: variables whose most recent binding came from a CALL result.
   *
   * <p>Feeds the copy-inheritance in the MOVE handler: {@code b = sink.get()} lowers to a CALL into
   * a temp plus {@code b = t_t1}, so the fact recorded on the temp has to travel with the MOVE.
   */
  private final java.util.Set<TacOperand.TacVar> lastBindingWasCall =
      new java.util.LinkedHashSet<>();

  /**
   * R1122: variables that were REBOUND to a call result after previously aliasing something else.
   *
   * <p>Not "holds a call result" -- a plain copy of a call result must stay suppressible, because
   * the two names denote one reference. Set only when a MOVE assigns a call result's temp into a
   * variable that already held a value, i.e. exactly when copy-equivalence's monotonic union stops
   * describing the truth. Cleared whenever the variable is bound again from something that is not a
   * call result.
   */
  private final java.util.Set<TacOperand.TacVar> reboundAwayFromAlias =
      new java.util.LinkedHashSet<>();

  /**
   * R1122: variables that have ever received a value, so a later MOVE can tell a FIRST binding (a
   * copy) from a REBINDING.
   */
  private final java.util.Set<TacOperand.TacVar> everBound = new java.util.LinkedHashSet<>();

  /** R1122: reset the per-function binding-origin state alongside the other checker state. */
  private void resetAllocatedHere() {
    allocatedHere.clear();
    lastBindingWasCall.clear();
    reboundAwayFromAlias.clear();
    everBound.clear();
  }

  private boolean isFullyGuardedByAlias(
      TacOperand.TacVar receiver, TacFunction fn, AnalysisContext ctx) {
    Set<TacOperand.TacVar> aliases = ctx.aliasClassOf(fn.signature(), receiver);
    if (aliases.isEmpty()) {
      // No alias information at all. Not the same as "no aliases", so it cannot silence anything.
      return false;
    }
    Set<TacOperand.TacVar> proven = this.nullGuards.provenNonNullVars();

    Set<AbstractObject.Obj> held = ctx.pointsTo(fn.signature(), receiver);
    boolean mayBeNull = ctx.mayBeNull(fn.signature(), receiver);
    // R1122: a receiver REBOUND to a fresh call result is not covered by a guard on a
    // variable that is merely still in its (monotonic) copy-equivalence class.
    //
    // The finding below is driven by `nullableVars`, which marks EVERY call result nullable. The
    // suppression, before this, consulted only `ctx.mayBeNull` -> `PointsToSolver`, which is
    // populated just for a resolved `return null`, an explicit `x = null`, or a load from a slot in
    // `nullFields` / `nullArrayCells`. Two sources of truth inside one method:
    //
    //     String a = head;
    //     if (a == null) { return 0; }   // a proven non-null
    //     String b = a;                   // copy -> b joins a's class (union-find never un-unions)
    //     b = sink.get();                 // CHECKER knows b may be null;
    //                                     // SOLVER could not resolve `sink` -> no null fact
    //     b.length();                     // reads mayBeNull == false as proof -> suppressed
    //
    // The discriminator is not "is this variable's value a call result" -- a plain copy of a call
    // result is exactly the shape R1110/R1113 require to stay silent:
    //
    //     StringBuilder a = make(input);  // call result
    //     StringBuilder b = a;            // ONE reference, two names
    //     if (a == null) { return; }
    //     b.length();                     // guard on `a` legitimately covers `b`
    //
    // What breaks the equivalence is a REBINDING. After `b = sink.get()`, `b` denotes a different
    // reference than `a`, and only a monotonic union-find still lists `a` among its aliases. So the
    // test is whether a call result was assigned into a variable that already held the guarded
    // reference -- that is precisely the case where the old guard stops applying.
    //
    // Conservative direction: a positive here can only regain a finding.
    if (this.reboundAwayFromAlias.contains(receiver)) {
      return false;
    }

    if (held.isEmpty()) {
      // The alias class exists but the heap facts do not: the relationship is syntactic (a copy),
      // so covering the guarded name is the strongest claim available -- PROVIDED the reference is
      // not also known to be null.
      //
      // R1114: this branch is exactly where a null fact changes the answer. `held` empty used to
      // mean "no objects", but it now also covers "the only thing known about this reference is
      // that it is null". A guarded name in the alias class proves that NAME non-null, and for a
      // syntactic copy the two names denote the same reference -- but the solver still holds a
      // null fact for the receiver, which means it saw a path where the name was null. When those
      // two disagree, the guard is the newer information and the finding stands.
      if (mayBeNull) return false;
      for (TacOperand.TacVar alias : aliases) {
        if (proven.contains(alias)) {
          return true;
        }
      }
      return false;
    }

    // Every object the receiver may hold must be covered by some proven-non-null name. One
    // uncovered object means there is a real path where this dereference sees a null reference.
    Set<AbstractObject.Obj> covered = new java.util.LinkedHashSet<>();
    boolean anyProven = false;
    for (TacOperand.TacVar alias : aliases) {
      if (!proven.contains(alias)) continue;
      anyProven = true;
      covered.addAll(ctx.pointsTo(fn.signature(), alias));
    }
    if (!anyProven) return false;
    // Nullness was handled above, in the `held.isEmpty()` branch and here. Reaching this point
    // means the receiver may hold at least one real object; a guard naming any alias covers that
    // object, and `mayBeNull` has already been resolved against the same guard set.
    if (covered.containsAll(held)) return true;

    // R1113: containment can fail for a reason that carries no risk at all. `store.put(key, v)`
    // followed by `w = store.get(key)` with the SAME unknown `key` lands in one ANY_KEY cell, so
    // the write and the read are symmetric: every object the read can return is an object the
    // write put there. The set of possibilities is wider than reality, but its ENDPOINTS are the
    // guarded object.
    //
    // This does NOT generalise to a cell with more than one writer. `items.add(first)` and
    // `items.add(second)` share one ANY_KEY cell too, but a `get` on it may return `second`, which
    // no guard has ever covered -- cell identity alone is not evidence, so the additional
    // "the guarded name is the only writer" requirement below is load-bearing, not decoration.
    return isExclusiveContainerRoundTrip(receiver, aliases, proven, fn, ctx);
  }

  /**
   * Did the guarded name put the object into the exact cell the receiver reads back, as the cell's
   * <em>only</em> writer?
   *
   * <p>{@link org.plumb.dfa.points.PointsToSolver#linkedViaContainer} answers "do these two touch a
   * common cell", which is necessary but not sufficient: a cell with two writers satisfies it just
   * as well. The additional test here is that the guarded name's facts account for the receiver's
   * entire possibility set <em>plus</em> whatever else reached that cell. When another writer
   * contributed, the guard says nothing about the object that writer added and the finding stands.
   */
  private boolean isExclusiveContainerRoundTrip(
      TacOperand.TacVar receiver,
      Set<TacOperand.TacVar> aliases,
      Set<TacOperand.TacVar> proven,
      TacFunction fn,
      AnalysisContext ctx) {
    org.plumb.dfa.points.PointsToSolver solver = ctx.pointsToSolver();
    if (solver == null) return false;

    Set<AbstractObject.Obj> held = ctx.pointsTo(fn.signature(), receiver);
    for (TacOperand.TacVar alias : aliases) {
      if (!proven.contains(alias)) continue;
      if (!solver.linkedViaContainer(
          fn.signature(), alias, receiver, AbstractObject.Context.empty())) {
        continue;
      }
      // Objects written into the shared cells by names OTHER than the guarded one. If this is
      // non-empty the guarded object is only one of several possible results of the read.
      Set<AbstractObject.Obj> mine = ctx.pointsTo(fn.signature(), alias);
      Set<AbstractObject.Obj> foreign = new java.util.LinkedHashSet<>(held);
      foreign.removeAll(mine);
      if (foreign.isEmpty()) {
        return true;
      }
      // The read may still return only the guarded object if every other contributor to the cell
      // is itself covered by some proven-non-null name.
      Set<AbstractObject.Obj> coveredByOthers = new java.util.LinkedHashSet<>();
      for (TacOperand.TacVar other : aliases) {
        if (proven.contains(other)) {
          coveredByOthers.addAll(ctx.pointsTo(fn.signature(), other));
        }
      }
      if (coveredByOthers.containsAll(held)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The reference this instruction dereferences.
   *
   * <p>For a CALL the TAC keeps the receiver at {@code args[0]}, which is the same convention
   * {@link NullDerefChecker} documents. For the field and array forms the receiver is {@code lhs1}.
   *
   * <p>Returns {@code null} rather than guessing when the slot holds a constant, which happens for
   * a static call: {@code Arrays.asList(..)} has no receiver, and treating its first argument as
   * one would report a dereference that does not exist.
   */
  private static TacOperand.TacVar receiverOf(TacInstruction ins) {
    if (ins.kind == TacInstruction.Kind.CALL) {
      if (ins.args == null || ins.args.isEmpty()) return null;
      return asVar(ins.args.get(0));
    }
    return asVar(ins.lhs1);
  }

  private static TacOperand.TacVar asVar(Object operand) {
    return operand instanceof TacOperand.TacVar v ? v : null;
  }
}
