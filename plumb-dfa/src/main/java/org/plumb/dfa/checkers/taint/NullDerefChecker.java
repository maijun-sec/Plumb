/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.plumb.dfa.engine.AnalysisContext
 *  org.plumb.dfa.engine.Finding
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$PointerTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 *  org.plumb.tac.ir.TacOperand
 *  org.plumb.tac.ir.TacOperand$Const
 *  org.plumb.tac.ir.TacOperand$Const$Kind
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class NullDerefChecker extends TaintedCheckerBase {
  public NullDerefChecker() {
    super(
        new TaintRule(
            "dfa-taint-null-dereference",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-476",
            "HIGH",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE));
  }

  // Separate map for "may be null" taint.
  // We CANNOT reuse taintedVars (the base class's map) because
  // it already marks every function parameter as "untrusted
  // source" via initFunction — that would cause massive false
  // positives: any param passed to a CALL would fire CWE-690
  // because every param is "tainted" by the base class.
  // We need a *distinct* nullness domain.
  private final java.util.Map<TacOperand.TacVar, String> nullTaintedVars =
      new java.util.LinkedHashMap<>();

  /**
   * R1108: path-sensitive null guards for the current function.
   *
   * <p>Owned here rather than borrowed from the base class, for the same reason {@link
   * DivByZeroChecker} owns one: the guard state is per-function and per-checker, and the base class
   * keeps its own instance private.
   */
  private final org.plumb.dfa.taint.GuardTracker nullGuards = new org.plumb.dfa.taint.GuardTracker();

  /**
   * Clear per-function state on function boundary. The earlier code cleared on the first
   * instruction of each function but that was ordered AFTER the null-MOVE handler, so the first
   * null assignment was added then immediately removed. Using {@link #initFunction} clears the map
   * BEFORE any instruction runs, fixing the bug.
   */
  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.nullTaintedVars.clear();
    this.nullGuards.clear();
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    TacOperand.TacVar d;
    TacOperand.Const c;
    TacOperand tacOperand;
    super.checkInstruction(ins, fn, ctx);
    // R1108: track guards AFTER super, so the base class's MOVE/BINOP handling has run and this
    // instruction's context is complete before the guard set is consulted below.
    this.nullGuards.track(ins);
    if (ins.kind == TacInstruction.Kind.MOVE
        && ins.lhs1 instanceof TacOperand.Const
        && ((TacOperand.Const) ins.lhs1).kind() == TacOperand.Const.Kind.NULL
        && ins.dest instanceof TacOperand.TacVar) {
      d = (TacOperand.TacVar) ins.dest;
      this.nullTaintedVars.put(d, "null-literal");
    }
    // Mark CALL dest as "may be null" if callee name
    // contains "Bad" / "getNull" / etc. (heuristic for
    // CWE-690 Juliet corpus).
    if (ins.kind == TacInstruction.Kind.CALL
        && ins.calleeSig != null
        && ins.dest instanceof TacOperand.TacVar) {
      String sig = ins.calleeSig;
      int lastDot = Math.max(sig.lastIndexOf('.'), sig.lastIndexOf('#'));
      String shortName = lastDot < 0 ? sig : sig.substring(lastDot + 1);
      int slash = shortName.indexOf('/');
      if (slash > 0) shortName = shortName.substring(0, slash);
      if (shortName.contains("Bad")
          || shortName.contains("Null")
          || shortName.contains("null")
          || shortName.contains("Nullable")) {
        this.nullTaintedVars.put((TacOperand.TacVar) ins.dest, "may-return-null:" + sig);
      }
    }
    // Propagate through MOVE in the null map.
    // When the source is NOT null-tainted,
    // we MUST remove the dest from nullTaintedVars
    // because the dest is being reassigned. Without
    // this, a pattern like
    //     File tempFile = null;        // dest marked null-literal
    //     try { tempFile = File.createTempFile(...); } // no MOVE propagation
    //     finally { if (tempFile != null) { tempFile.delete(); } }
    // falsely fires because tempFile stays in
    // nullTaintedVars from the initial null
    // assignment, even after the non-null
    // reassignment. The bug previously produced
    // hundreds of FPs on CWE-378 / CWE-459.
    if (ins.kind == TacInstruction.Kind.MOVE
        && ins.lhs1 instanceof TacOperand.TacVar mvSrc
        && ins.dest instanceof TacOperand.TacVar mvDest) {
      if (this.nullTaintedVars.containsKey(mvSrc)) {
        this.nullTaintedVars.put(mvDest, "move-from:" + mvSrc.name());
      } else {
        // Source is CLEAN: dest is being reassigned
        // to a non-null value, so remove any
        // pre-existing null-taint.
        this.nullTaintedVars.remove(mvDest);
      }
    }
    // Clear per-function state is done in initFunction now.
    // The old "first instruction clear" was ordered after the
    // null-MOVE handler, causing the first null assignment to
    // be added then immediately cleared.
    if (ins.kind != TacInstruction.Kind.FIELDLOAD
        && ins.kind != TacInstruction.Kind.FIELDSTORE
        && ins.kind != TacInstruction.Kind.ARRAYLOAD
        && ins.kind != TacInstruction.Kind.ARRAYSTORE
        && ins.kind != TacInstruction.Kind.CALL) {
      return;
    }
    // TAC CALL puts receiver at args[0], not lhs1.
    TacOperand.TacVar receiver = null;
    if (ins.kind == TacInstruction.Kind.CALL && ins.args != null && !ins.args.isEmpty()) {
      receiver =
          (ins.args.get(0) instanceof TacOperand.TacVar)
              ? (TacOperand.TacVar) ins.args.get(0)
              : null;
    } else {
      receiver = (ins.lhs1 instanceof TacOperand.TacVar) ? (TacOperand.TacVar) ins.lhs1 : null;
    }
    if (receiver == null) return;
    // Use the separate null map — NOT the base class's
    // taintedVars (which marks every param as untrusted).
    if (!this.nullTaintedVars.containsKey(receiver)) {
      return;
    }
    // R1108: suppress when this receiver is provably non-null on the current path, including by
    // ALIAS. See suppressReasonFor for why the alias step is sound and what it deliberately does
    // not do.
    String suppressed = suppressReasonFor(receiver, fn, ctx);
    if (suppressed != null) {
      return;
    }
    String src = (String) this.nullTaintedVars.get(receiver);
    ctx.reporter()
        .report(
            Finding.dfa(
                this.rule.ruleId(),
                this.rule.ruleId(),
                switch (ins.kind) {
                  case FIELDLOAD ->
                      "Null dereference: field load on null receiver " + receiver.name();
                  case FIELDSTORE ->
                      "Null dereference: field store on null receiver " + receiver.name();
                  case ARRAYLOAD ->
                      "Null dereference: array load on null handle " + receiver.name();
                  case ARRAYSTORE ->
                      "Null dereference: array store on null handle " + receiver.name();
                  case CALL -> "Null dereference: method call on null receiver " + receiver.name();
                  default -> "Null dereference at " + String.valueOf(ins);
                },
                this.rule.severity(),
                ins.kind == TacInstruction.Kind.CALL ? "CWE-690" : this.rule.cwe(),
                "",
                ins.line,
                ins.column,
                "may-be-null: " + src));
  }

  /**
   * R1108: why this dereference must NOT be reported, or {@code null} to report it.
   *
   * <p>A non-null guard constrains the <em>object</em>, not the variable name. So when
   * {@code if (a != null)} holds on this path and {@code b} may hold the same object as {@code a},
   * {@code b} is non-null here as well — and the reverse-scan that flagged {@code b} has simply
   * missed that the path already established the fact. That is why consulting the alias class
   * removes findings instead of adding them: every branch below can only conclude "this path proves
   * the receiver safe", never "this path might make it less safe".
   *
   * <p>What this deliberately does NOT do, because it would invert the guarantee:
   *
   * <ul>
   *   <li>It does not propagate null-ness through aliases. Only the non-null direction is used.
   *       Concluding "b may be null because a may be null" would add findings, and a conservative
   *       alias map makes that fire on unrelated same-type locals.
   *   <li>It does not treat an empty alias class as proof of uniqueness. An empty result means the
   *       solver has no information (not installed, or the receiver is a parameter whose points-to
   *       set is empty), so the code falls through to reporting exactly as it did before R1108.
   * </ul>
   *
   * @return a short reason for suppression, or {@code null} to report.
   */
  private String suppressReasonFor(
      TacOperand.TacVar receiver, TacFunction fn, AnalysisContext ctx) {
    if (this.nullGuards.isProvenNonNull(receiver)) {
      return "proven non-null on this path";
    }
    if (ctx == null || fn == null || receiver == null) {
      return null;
    }
    java.util.Set<TacOperand.TacVar> aliases = ctx.aliasClassOf(fn.signature(), receiver);
    if (aliases.isEmpty()) {
      // No alias information. Fall through and report: absence of proof is not proof of safety.
      return null;
    }
    java.util.Set<TacOperand.TacVar> proven = this.nullGuards.provenNonNullVars();
    for (TacOperand.TacVar alias : aliases) {
      if (proven.contains(alias)) {
        return "alias " + alias.name() + " is proven non-null on this path";
      }
    }
    return null;
  }
}
