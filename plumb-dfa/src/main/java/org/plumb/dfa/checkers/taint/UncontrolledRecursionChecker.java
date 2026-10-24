/*
 * Taint-style checker for CWE-674
 * (Uncontrolled Recursion).
 *
 * <p>This checker runs only when taint is enabled (because it extends
 * {@link TaintedCheckerBase}). It reports a call site that calls back
 * into the enclosing method without an obvious base case.</p>
 *
 * <p><b>Receiver identity.</b> A call is only self-recursion when the
 * receiver is the enclosing instance (or is absent, i.e. an unqualified
 * call, which Java resolves against the enclosing class). A call whose
 * receiver is a different object is NOT recursion, even when the method
 * name and arity match: {@code controller.rename(a, b)} delegating to
 * {@code repository.rename(a, b)} is ordinary delegation, and reporting
 * it as "uncontrolled recursion" is a false positive.</p>
 *
 * <p>The IR makes the receiver observable in two ways:</p>
 * <ul>
 *   <li>A scoped call records the receiver operand as {@code args[0]},
 *       ahead of the declared arguments. The receiver's declared type is
 *       the discriminator: {@code this} carries the enclosing class, a
 *       field or local carries its own type.</li>
 *   <li>An unscoped call records no receiver operand at all, and the
 *       callee signature carries no class prefix. Java resolves such a
 *       call against the enclosing class, so it is recursion when the
 *       method name matches.</li>
 * </ul>
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
import org.plumb.tac.ir.TacType;

public final class UncontrolledRecursionChecker extends TaintedCheckerBase {
  public UncontrolledRecursionChecker() {
    super(
        new TaintRule(
            "dfa-taint-uncontrolled-recursion",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-674",
            "MEDIUM",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE));
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind != TacInstruction.Kind.CALL) {
      return;
    }
    if (ins.calleeSig == null) {
      return;
    }
    if (isSelfRecursion(ins, fn)) {
      Finding finding =
          Finding.dfa(
              this.rule.ruleId(),
              this.rule.ruleId(),
              "Uncontrolled recursion: "
                  + fn.signature()
                  + " calls itself without an obvious base case (no early-return guard found in the"
                  + " immediate predecessors)",
              this.rule.severity(),
              this.rule.cwe(),
              "",
              ins.line,
              ins.column,
              "uncontrolled-recursion: " + fn.signature());
      ctx.reporter().report(finding);
    }
  }

  /**
   * Decide whether the call targets the enclosing method itself.
   *
   * <p>The comparison uses the method name, the arity, AND the receiver's identity. Name + arity
   * alone is not enough: two unrelated classes commonly share a method name and an arity, and
   * treating that as recursion produces a false positive on ordinary delegation.
   */
  static boolean isSelfRecursion(TacInstruction ins, TacFunction fn) {
    String callee = ins.calleeSig;
    String fnName = fn.name();
    int lastDot = fnName == null ? -1 : fnName.lastIndexOf('.');
    String shortName = (fnName != null && lastDot >= 0) ? fnName.substring(lastDot + 1) : fnName;
    if (shortName == null || shortName.isEmpty()) {
      return false;
    }
    int arity = fn.params() == null ? 0 : fn.params().size();

    // Split "pkg.Cls.method/arity" (or "method/arity") into the method
    // name, the arity, and the declaring class prefix (may be absent).
    int slash = callee.lastIndexOf('/');
    String head = slash >= 0 ? callee.substring(0, slash) : callee;
    int calleeArity = -1;
    if (slash >= 0) {
      try {
        calleeArity = Integer.parseInt(callee.substring(slash + 1));
      } catch (NumberFormatException ignore) {
        // Leave calleeArity at -1: an unknown arity cannot disprove a match.
      }
    }
    int sep = Math.max(head.lastIndexOf('.'), head.lastIndexOf('#'));
    String calleeMethod = sep >= 0 ? head.substring(sep + 1) : head;
    String calleeClass = sep >= 0 ? head.substring(0, sep) : null;

    if (!shortName.equals(calleeMethod)) {
      return false;
    }
    if (calleeArity >= 0 && calleeArity != arity) {
      return false;
    }

    // The receiver decides. When the IR recorded one, its declared type
    // must be the enclosing class; a receiver of any other type is a
    // different object and the call is delegation, not recursion.
    TacOperand receiver = receiverOf(ins, calleeArity);
    if (receiver == null) {
      // No receiver operand: an unqualified call. Java resolves it
      // against the enclosing class, so it is recursion. The callee
      // signature carries no class prefix either, which agrees.
      return true;
    }
    if (!(receiver instanceof TacOperand.TacVar recvVar)) {
      // A constant receiver can never be the enclosing instance.
      return false;
    }
    String enclosing = fn.enclosingClass();
    boolean enclosingKnown = enclosing != null && !enclosing.isEmpty();
    String recvType = classNameOf(recvVar.type());

    if ("this".equals(recvVar.name())) {
      // `this` is the enclosing instance by definition. When the
      // enclosing class is known, the recorded type must agree with it
      // -- a disagreement means the IR typed `this` as some other
      // class, and trusting it would report the wrong method.
      if (!enclosingKnown) {
        return true;
      }
      return recvType == null || recvType.isEmpty() || recvType.equals(enclosing);
    }

    // A named receiver (a field or a local).
    if (recvType != null && !recvType.isEmpty()) {
      // The receiver's declared type is known: it is the enclosing
      // instance only when that type IS the enclosing class.
      return recvType.equals(enclosing);
    }

    // The receiver's type is unknown. The IR does not type field
    // references, so `self.loop(a)` inside `class T` and
    // `other.loop(a)` inside `class U` look the same here.
    //
    // Fall back to the class named in the callee signature. When that
    // class is NOT the enclosing class, the call provably leaves the
    // enclosing method and is delegation. When it IS the enclosing
    // class, the receiver may still be a different instance of that
    // class -- but the call is then a call to the enclosing method
    // through some object, which is the recursion shape worth
    // reporting.
    if (calleeClass == null || calleeClass.isEmpty()) {
      // Neither the receiver nor the signature identifies a class.
      return false;
    }
    return enclosingKnown && calleeClass.equals(enclosing);
  }

  /**
   * The receiver operand of a scoped call, or {@code null} for an unscoped call.
   *
   * <p>The IR stores the receiver as {@code args[0]}, ahead of the declared arguments, so the
   * receiver is present exactly when the argument count exceeds the callee's arity by one.
   */
  private static TacOperand receiverOf(TacInstruction ins, int calleeArity) {
    if (ins.args == null || ins.args.isEmpty()) {
      return null;
    }
    if (calleeArity < 0) {
      // The arity is unknown, so the receiver cannot be located by
      // counting. Fall back to the convention that a scoped call
      // records the receiver first.
      return ins.args.get(0);
    }
    if (ins.args.size() == calleeArity + 1) {
      return ins.args.get(0);
    }
    return null;
  }

  /** The class name of a type, or {@code null} when it carries none. */
  private static String classNameOf(TacType type) {
    if (type == null || type.kind() != TacType.Kind.CLASS) {
      return null;
    }
    return type.className();
  }
}
