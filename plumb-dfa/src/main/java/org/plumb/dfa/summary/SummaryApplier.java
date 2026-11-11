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
package org.plumb.dfa.summary;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import org.plumb.dfa.cg.FunctionSignature;
import org.plumb.dfa.config.FactKind;
import org.plumb.dfa.config.FactKindRegistry;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.dfa.fact.Fact;
import org.plumb.dfa.fact.FactBag;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Apply a {@link MethodSummary} at a call site.
 *
 * <p>The applier is a small, static, no-state helper that caller-side checkers invoke from {@link
 * org.plumb.dfa.engine.DfaChecker#checkInstruction(TacInstruction, org.plumb.tac.ir.TacFunction,
 * AnalysisContext)} when they see a {@code CALL} instruction. The applier consults the engine's
 * {@link MethodSummaryCache}, folds the return-value shape into a {@code RETURN_VALUE_FOLDED} fact
 * on the call's destination operand, and writes the side-effect record as a {@code
 * SIDE_EFFECT_RECORDED} fact on the call site itself.
 *
 * <p>The applier does <i>not</i> decide whether the fold is semantically correct -- the builder is
 * path- insensitive, the side- effect set is a sound over- approximation. The caller's checker
 * decides what to do with the fact (e.g. a divide-by-zero checker sees {@code const:5} on the
 * divisor's dest and concludes {@code divisor != 0} for that path).
 *
 * <h2>Symbolic value encoding</h2>
 *
 * <p>The applier encodes each shape as a string {@code value} in the produced fact. The encoding is
 * fixed and stable so checkers can match on it without coupling to the builder's internal types:
 *
 * <pre>
 *   RETURN_VOID → "void"
 *   RETURN_CONST → "const:&lt;literal&gt;"
 *   RETURN_PARAM → "param:&lt;i&gt;"
 *   RETURN_THIS_FIELD → "this-field:&lt;name&gt;"
 *   RETURN_STATIC_FIELD → "static-field:&lt;FQCN.field&gt;"
 *   RETURN_LOCAL_VAR → "local:&lt;name&gt;"
 *   RETURN_BINARY_OP → "binop:&lt;op&gt;:&lt;lhs&gt;:&lt;rhs&gt;"
 *   RETURN_CONDITIONAL → "cond:&lt;trueArm&gt;:&lt;falseArm&gt;"
 *   RETURN_NEW_INSTANCE → "new:&lt;type&gt;"
 *   UNKNOWN → "top"
 * </pre>
 *
 * <h2>Side-effect encoding</h2>
 *
 * <p>Side effects are similarly encoded in the fact value as a comma-joined set of flags. A pure
 * function is encoded as the single token {@code "pure"} so downstream consumers can short-circuit
 * on it.
 */
public final class SummaryApplier {

  private SummaryApplier() {}

  /**
   * Look up the {@link MethodSummary} for the callee of {@code call} in the engine's {@link
   * MethodSummaryCache} and, if present, fold the return value and record the side effects. The
   * {@code args} list is the callee's actual arguments (so the applier can encode {@code
   * RETURN_PARAM} with a real caller's operand name, when desired).
   *
   * <p>Returns the {@link MethodSummary} that was applied (or {@code null} if the cache has no
   * entry for the callee -- e.g. a library call).
   */
  public static MethodSummary apply(
      TacInstruction call, AnalysisContext ctx, List<TacOperand> args) {
    Objects.requireNonNull(call);
    Objects.requireNonNull(ctx);
    if (call.kind != TacInstruction.Kind.CALL) {
      throw new IllegalArgumentException("apply expects a CALL, got " + call.kind);
    }
    String calleeSig = call.calleeSig;
    if (calleeSig == null) return null;
    // The cross-function folding depth. At zero the analysis stays inside one function, so a
    // callee's summary is never folded into its caller. The setting is read from the same
    // place as the taint checkers read it, so the whole engine agrees on what it means.
    if (ctx.config() != null && ctx.config().tuning() != null) {
      if (ctx.config().tuning().contextDepth() <= 0) return null;
    }
    FunctionSignature sig = null;
    try {
      sig = FunctionSignature.parse(calleeSig);
    } catch (RuntimeException re) {
      // fall through to null
    }
    if (sig == null) {
      // FunctionSignature.parse returns null for the "Class.method/arity" form that the IR
      // uses for CALL instructions (e.g. "IO.staticReturnsTrue/0"). The fallback builds the
      // same shape of key the engine stores its summaries under, so the lookup can find a
      // pre-computed summary.
      sig = fallbackParse(calleeSig, callerClassOf(ctx));
    }
    if (sig == null) {
      // Genuinely non-canonical: the
      // call is to a library function
      // whose body the engine has not
      // modelled. The cache's UNKNOWN
      // sentinel handles these.
      return null;
    }
    MethodSummary s = ctx.methodSummaryCache().get(sig);
    applyReturnValue(call, s, ctx, args);
    applySideEffect(call, s, ctx);
    return s;
  }

  /**
   * The key a call site looks a callee's summary up under.
   *
   * <p>The IR spells a call in one of two ways:
   *
   * <ul>
   *   <li>{@code "Class.method/N"} — the receiver's type names the class
   *   <li>{@code "method/N"} — a call to a method of the caller's own class, which names no class
   *       at all
   * </ul>
   *
   * <p>The second form carries no class, and a key without a class in it addresses nothing: the
   * most common call there is is never found, so the callee's return shape is never folded into the
   * caller and the callee's side effects are never recorded. The declaring class of that call is
   * the caller's own class, and the caller knows it.
   *
   * <p>Both forms are reduced to the shape the engine stores under: no package, the class's SIMPLE
   * name, the method name, no parameter types, an unknown return type. The arity is not part of the
   * key, because the summary it addresses is a per-method view — the return shape does not depend
   * on how many arguments were passed, and the side-effect set records a parameter write by index.
   *
   * @param calleeSig the callee signature as the IR spells it
   * @param callerClass the declaring class of the function the call sits in, or null when the
   *     caller has none
   * @return the key to look the summary up under, or null when the signature is not a shape the
   *     engine can address
   */
  static FunctionSignature fallbackParse(String calleeSig, String callerClass) {
    if (calleeSig == null || calleeSig.isEmpty()) return null;
    int slash = calleeSig.indexOf('/');
    String head = slash < 0 ? calleeSig : calleeSig.substring(0, slash);
    // Validate the arity if present — it is not part of the key, but a non-numeric slash
    // means the signature is not a shape this parser understands.
    if (slash >= 0) {
      for (int i = slash + 1; i < calleeSig.length(); i++) {
        if (!Character.isDigit(calleeSig.charAt(i))) return null;
      }
    }
    int dot = head.lastIndexOf('.');
    String method;
    String cls;
    if (dot < 0) {
      // The call names no class, so the callee is a method of the caller's own class.
      method = head;
      cls = simpleClassName(callerClass);
    } else {
      method = head.substring(dot + 1);
      cls = simpleClassName(head.substring(0, dot));
    }
    if (method.isEmpty()) return null;
    return new FunctionSignature(
        "", cls == null ? NO_CLASS : cls, method, java.util.List.of(), "?");
  }

  /**
   * The class name a key addresses, when the engine has none.
   *
   * <p>A function the IR did not place in a class — a free function, a synthetic initialiser —
   * lands here, and so does a call site whose class the IR did not name. It is a bucket of its own:
   * it holds the functions that have no class and nothing else, so a lookup that lands in it can
   * only find a class-less function.
   */
  public static final String NO_CLASS = "<unknown>";

  /**
   * The simple name of a type: the last dot-separated segment of a qualified name.
   *
   * <p>The two ends of a summary lookup have to spell the class the same way. The IR spells a
   * receiver's type by its simple name on the source lane and by its fully qualified name on the
   * bytecode lane, so both ends reduce the name to its last segment before it becomes part of a
   * key.
   *
   * @param qualified the type name, qualified or not
   * @return the simple name, or null when there is no name to reduce
   */
  public static String simpleClassName(String qualified) {
    if (qualified == null || qualified.isEmpty()) return null;
    int dot = qualified.lastIndexOf('.');
    String simple = dot < 0 ? qualified : qualified.substring(dot + 1);
    return simple.isEmpty() ? null : simple;
  }

  /**
   * The declaring class of the function being analysed on this thread.
   *
   * @param ctx the analysis context
   * @return the class name, or null when no function is being analysed
   */
  private static String callerClassOf(AnalysisContext ctx) {
    org.plumb.tac.ir.TacFunction fn = ctx.currentFunction();
    return fn == null ? null : fn.enclosingClass();
  }

  /**
   * Fold the {@link ReturnShape} into a {@code RETURN_VALUE_FOLDED} fact on the call's destination.
   * If the call has no destination (a bare {@code CALL f();} statement) this is a no-op.
   */
  public static void applyReturnValue(
      TacInstruction call, MethodSummary s, AnalysisContext ctx, List<TacOperand> args) {
    TacOperand dest = call.dest;
    if (dest == null) return;
    FactKindRegistry reg = ctx.config().factKinds();
    FactKind kind = reg.byName(BuiltinFactKinds.RETURN_VALUE_FOLDED).orElse(null);
    if (kind == null) {
      // Defensive:
      // the registry is
      // supposed to
      // include the
      // built-in, but
      // skip the fact
      // if it doesn't.
      return;
    }
    String value = encodeShape(s, args);
    FactBag bag = ctx.factBag();
    bag.add(new Fact(kind, dest, value, call));
  }

  /**
   * Write a {@code SIDE_EFFECT_RECORDED} fact on the call's destination (or, if absent, on an
   * arbitrary sentinel operand derived from the call's {@code calleeSig}) so cross-function
   * state-invalidation consumers can pick it up.
   */
  public static void applySideEffect(TacInstruction call, MethodSummary s, AnalysisContext ctx) {
    if (s == null) return;
    if (s.isPure()) {
      // Nothing to
      // record; pure
      // methods do not
      // affect caller
      // state.
      return;
    }
    FactKindRegistry reg = ctx.config().factKinds();
    FactKind kind = reg.byName(BuiltinFactKinds.SIDE_EFFECT_RECORDED).orElse(null);
    if (kind == null) return;
    String value = encodeSideEffect(s);
    TacOperand site =
        call.dest != null
            ? call.dest
            : new TacOperand.TacVar(
                "@call@" + Integer.toHexString(System.identityHashCode(call)), null, true);
    // Account for the fact against the analysis budget. The worklist cap bounds how many
    // facts the engine tracks; once it is passed, the fact is not recorded and the
    // exhaustion is reported. Recording it anyway would defeat the cap -- the whole point
    // of the cap is to stop the tracking from growing without bound.
    org.plumb.tac.ir.TacFunction fn = ctx.currentFunction();
    String subject = fn == null ? call.calleeSig : fn.signature();
    if (!ctx.budget().accountFact(subject)) {
      return;
    }
    ctx.factBag().add(new Fact(kind, site, value, call));
  }

  // ---------------- encoders ----------------

  static String encodeShape(MethodSummary s, List<TacOperand> args) {
    if (s == null) return "top";
    ReturnShape r = s.returnShape;
    switch (r.kind) {
      case RETURN_VOID:
        return "void";
      case RETURN_CONST:
        {
          TacOperand.Const c = r.constValue;
          if (c == null) return "top";
          return "const:" + c.toString();
        }
      case RETURN_PARAM:
        {
          int i = r.paramIndex;
          if (args != null && i >= 0 && i < args.size() && args.get(i) != null) {
            return "param:" + i + ":" + args.get(i);
          }
          return "param:" + i;
        }
      case RETURN_THIS_FIELD:
        return "this-field:" + r.thisFieldName;
      case RETURN_STATIC_FIELD:
        return "static-field:" + r.staticFieldKey;
      case RETURN_LOCAL_VAR:
        return "local:" + r.localName;
      case RETURN_BINARY_OP:
        return "binop:" + r.binop + ":" + r.binopLhs + ":" + r.binopRhs;
      case RETURN_CONDITIONAL:
        return "cond:" + r.condTrue + ":" + r.condFalse;
      case RETURN_NEW_INSTANCE:
        return "new:" + r.newInstanceType;
      case UNKNOWN:
      default:
        return "top";
    }
  }

  static String encodeSideEffect(MethodSummary s) {
    if (s == null) return "pure";
    SideEffect e = s.sideEffect;
    if (e.isPure()) return "pure";
    StringJoiner sj = new StringJoiner(",");
    for (String f : e.writesThisFields()) {
      sj.add("writes-this:" + f);
    }
    for (Integer p : e.writesParamIndices()) {
      sj.add("writes-param:" + p);
    }
    for (String f : e.writesStaticFields()) {
      sj.add("writes-static:" + f);
    }
    for (String c : e.callsMethods()) {
      sj.add("calls:" + c);
    }
    if (e.mayThrow()) {
      sj.add("may-throw");
    }
    return sj.toString();
  }
}
