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
    // A CONSTRUCTOR is never recursion. `X.<init>` runs the constructor of X, and a constructor
    // that called itself would not return -- the language rejects it at compile time. So the
    // comparison below could only ever match by accident: the constructor's name and the
    // enclosing constructor's name are the same word, and the receiver is `this` in both cases.
    //
    // This is not hypothetical. The bytecode front end names the implicit super() call that the
    // compiler inserts, so every class with a constructor produces `java.lang.Object.<init>/0`
    // with `this` as the receiver -- and every one of them was reported as uncontrolled
    // recursion. The source front end does not emit that call, which is why the false positive
    // only showed up on bytecode.
    if (isConstructorName(shortName)) {
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
    // The callee is a constructor: running a constructor is not re-entering the enclosing
    // method, whichever class the constructor belongs to.
    if (isConstructorName(calleeMethod)) {
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
    String enclosing = enclosingClassOf(fn);
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

  /**
   * The class that declares the function under analysis.
   *
   * <p>The IR records it on the function when the front end knows it. When it does not, the
   * function's SIGNATURE may still name the declaring class: the bytecode front end writes {@code
   * org.apache.commons.text.StringTokenizer.add(1)}, while the source front end writes {@code
   * open(BufferedReader):File} and leaves the class to the enclosing-class field.
   *
   * <p>Why the fallback matters: without it, a front end that does not record the enclosing class
   * leaves every receiver named {@code this} looking like the enclosing instance, and every call on
   * {@code this} was reported as recursion. The bytecode lane does not record the enclosing class,
   * so every method that called a method of the same name on itself was reported -- including the
   * ones whose own body throws, such as a method the class declares only to refuse the call.
   *
   * @param fn the function under analysis
   * @return the declaring class, or {@code null} when neither source names it
   */
  private static String enclosingClassOf(TacFunction fn) {
    String recorded = fn.enclosingClass();
    if (recorded != null && !recorded.isEmpty()) {
      return recorded;
    }
    return declaringClassOfSignature(fn.signature());
  }

  /**
   * The class named in a function signature, or {@code null} when the signature names none.
   *
   * <p>The signature comes in two shapes. The bytecode front end writes {@code
   * org.apache.commons.text.StringTokenizer.add(1)} -- the class, then the method, then the arity
   * in parentheses. The source front end writes {@code open(BufferedReader):File} -- the method,
   * its parameters, its return type -- and leaves the class to the enclosing-class field. The two
   * are told apart by the dot before the method name: the first has one, the second does not.
   *
   * <p>Only the part BEFORE the parameter list is looked at. The return type may itself be a
   * qualified name ({@code open(String):java.io.File}), and its dots are not the declaring class.
   *
   * @param signature the function signature
   * @return the declaring class, or {@code null}
   */
  private static String declaringClassOfSignature(String signature) {
    if (signature == null) {
      return null;
    }
    int paren = signature.indexOf('(');
    if (paren <= 0) {
      return null;
    }
    String head = signature.substring(0, paren);
    int lastDot = head.lastIndexOf('.');
    if (lastDot <= 0) {
      return null;
    }
    return head.substring(0, lastDot);
  }

  /**
   * Whether a method name is a constructor's.
   *
   * <p>The JVM names an instance constructor {@code <init>} and a static initialiser {@code
   * <clinit>}. Both are called by the runtime rather than by the program, and neither can re-enter
   * itself.
   *
   * @param name the method name
   * @return true when the name belongs to a constructor
   */
  private static boolean isConstructorName(String name) {
    return "<init>".equals(name) || "<clinit>".equals(name);
  }

  /** The class name of a type, or {@code null} when it carries none. */
  private static String classNameOf(TacType type) {
    if (type == null || type.kind() != TacType.Kind.CLASS) {
      return null;
    }
    return type.className();
  }
}
