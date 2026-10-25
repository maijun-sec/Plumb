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
 * <p>The applier does <i>not</i> decide whether the fold is semantically correct ? the builder is
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
   * entry for the callee ? e.g. a library call).
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
    FunctionSignature sig = null;
    try {
      sig = FunctionSignature.parse(calleeSig);
    } catch (RuntimeException re) {
      // fall through to null
    }
    if (sig == null) {
      // FunctionSignature.parse returns
      // null for the
      // {@code Class.method/arity} form
      // that the IR uses for CALL
      // instructions (e.g.
      // {@code "IO.staticReturnsTrue/0"}).
      // Try a shape-only fallback that
      // splits on '.' and '/' so the
      // cache lookup can find a
      // pre-computed summary the
      // engine stored via
      // {@code parseFnSignature(fn)}
      // with class="<unknown>" and
      // method="staticReturnsTrue".
      // Without this fallback the
      // caller-side fold consumer
      // never sees the
      // {@code RETURN_VALUE_FOLDED}
      // fact for cross-TU calls.
      sig = fallbackParse(calleeSig);
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
   * Fallback parser for the {@code Class.method/arity} form that CALL instructions use when the
   * engine has not yet canonicalised the signature. Returns a {@code FunctionSignature} with {@code
   * class="<unknown>"} (matching the engine's {@code parseFnSignature} fallback) so the cache
   * lookup can find a pre-computed summary.
   *
   * <p>The fallback accepts:
   *
   * <ul>
   *   <li>{@code "Class.method/N"} → method="method", arity=N (ignored for cache key)
   *   <li>{@code "Class.method"} (no arity) → method="method"
   *   <li>Anything else → {@code null} (caller treats as genuinely non-canonical)
   * </ul>
   *
   * <p>The cache key intentionally drops the arity and the return type so it matches whatever the
   * engine's {@code parseFnSignature(fn)} fallback produced for the same method. The arity
   * difference is acceptable because the {@link MethodSummary} is a per-method view (the {@link
   * org.plumb.dfa.summary.ReturnShape} encodes the return value regardless of arity, and the
   * side-effect set already tracks writes-param:N for any N). Multiple overloads of the same method
   * name would collide ? for Juliet this never happens; the general case is a known limitation (see
   * fallbackParse's doc for details).
   */
  static FunctionSignature fallbackParse(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return null;
    int slash = calleeSig.indexOf('/');
    String head = slash < 0 ? calleeSig : calleeSig.substring(0, slash);
    // Validate the arity if present
    // — we don't use it for the key
    // but we want to reject
    // non-numeric slashes.
    if (slash >= 0) {
      for (int i = slash + 1; i < calleeSig.length(); i++) {
        if (!Character.isDigit(calleeSig.charAt(i))) return null;
      }
    }
    int dot = head.lastIndexOf('.');
    if (dot < 0) return null;
    String method = head.substring(dot + 1);
    if (method.isEmpty()) return null;
    // Synthesise a cache key with
    // class="<unknown>" to match
    // the engine's parseFnSignature
    // fallback exactly. Empty
    // paramTypes and "?" return
    // type so the canonical() form
    // matches regardless of the
    // function's actual arity or
    // return type.
    return new FunctionSignature("", "<unknown>", method, java.util.List.of(), "?");
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
