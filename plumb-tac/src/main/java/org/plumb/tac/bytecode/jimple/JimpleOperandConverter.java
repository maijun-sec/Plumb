package org.plumb.tac.bytecode.jimple;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.UnaryOperator;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.constant.BooleanConstant;
import sootup.core.jimple.common.constant.ClassConstant;
import sootup.core.jimple.common.constant.DoubleConstant;
import sootup.core.jimple.common.constant.FloatConstant;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.constant.LongConstant;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractBinopExpr;
import sootup.core.jimple.common.expr.AbstractConditionExpr;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.AbstractUnopExpr;
import sootup.core.jimple.common.expr.Expr;
import sootup.core.jimple.common.expr.JAddExpr;
import sootup.core.jimple.common.expr.JAndExpr;
import sootup.core.jimple.common.expr.JCastExpr;
import sootup.core.jimple.common.expr.JDivExpr;
import sootup.core.jimple.common.expr.JEqExpr;
import sootup.core.jimple.common.expr.JGeExpr;
import sootup.core.jimple.common.expr.JGtExpr;
import sootup.core.jimple.common.expr.JInstanceOfExpr;
import sootup.core.jimple.common.expr.JLeExpr;
import sootup.core.jimple.common.expr.JLengthExpr;
import sootup.core.jimple.common.expr.JLtExpr;
import sootup.core.jimple.common.expr.JMulExpr;
import sootup.core.jimple.common.expr.JNeExpr;
import sootup.core.jimple.common.expr.JNegExpr;
import sootup.core.jimple.common.expr.JNewArrayExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.expr.JOrExpr;
import sootup.core.jimple.common.expr.JRemExpr;
import sootup.core.jimple.common.expr.JShlExpr;
import sootup.core.jimple.common.expr.JShrExpr;
import sootup.core.jimple.common.expr.JSpecialInvokeExpr;
import sootup.core.jimple.common.expr.JStaticInvokeExpr;
import sootup.core.jimple.common.expr.JSubExpr;
import sootup.core.jimple.common.expr.JUshrExpr;
import sootup.core.jimple.common.expr.JVirtualInvokeExpr;
import sootup.core.jimple.common.expr.JXorExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JCaughtExceptionRef;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JThisRef;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.model.Body;
import sootup.core.types.ArrayType;
import sootup.core.types.ClassType;
import sootup.core.types.PrimitiveType;
import sootup.core.types.Type;
import sootup.core.types.VoidType;

/**
 * Convert SootUp Jimple values into the project's TAC operand and type vocabulary. This is the
 * bottom layer of the bytecode-to-TAC pipeline: every value produced or consumed by a Jimple
 * statement passes through this converter.
 *
 * <p>The mapping rules:
 *
 * <ul>
 *   <li>{@code Jimple Local} → {@link TacOperand.TacVar} (named location; type comes from the
 *       body's local declaration).
 *   <li>{@code IntConstant} / {@code LongConstant} / etc. → {@link TacOperand.Const} of the
 *       matching {@link TacType} and kind.
 *   <li>{@code JFieldRef} / {@code JArrayRef} → not directly an operand; the {@code lhs} of a
 *       {@code FIELDLOAD}/{@code ARRAYLOAD} is the destination local and the ref's components
 *       ({@code base} + {@code fieldName}, or {@code base} + {@code index}) are what the LHS
 *       instruction needs. We expose helpers for that rather than turning refs into opaque
 *       operands.
 *   <li>Binop / Unop / Cast / Instanceof → produced as part of {@code JAssignStmt} conversion; this
 *       class exposes {@link #binaryOpOf} / {@link #unaryOpOf} / {@link #castTypeOf} for the
 *       statement layer to consult.
 * </ul>
 *
 * <p>Everything that doesn't fit a known shape falls through to {@code TacType.UNKNOWN} -- a
 * defensive choice that keeps the dataflow engine running instead of crashing on an unhandled
 * Jimple instruction (we log and continue, just like the AST translator).
 */
public final class JimpleOperandConverter {

  /** The Jimple body whose local types we use to construct {@link TacOperand.TacVar}s. */
  private final Body body;

  public JimpleOperandConverter(Body body) {
    this.body = body;
  }

  // ---- TacOperand construction ------------------------------------

  /**
   * Convert any Jimple {@link Expr}-or-Value into a TAC operand. Used for the RHS of an assignment,
   * the args of a call, the condition of a branch, etc.
   */
  public TacOperand toOperand(Object value) {
    if (value == null) {
      return TacOperand.Const.ofNull();
    }
    if (value instanceof sootup.core.jimple.common.Local local) {
      return toVar(local);
    }
    if (value instanceof IntConstant c) {
      return TacOperand.Const.ofInt(c.getValue());
    }
    if (value instanceof LongConstant c) {
      return TacOperand.Const.ofLong(c.getValue());
    }
    if (value instanceof FloatConstant c) {
      return TacOperand.Const.ofFloat(c.getValue());
    }
    if (value instanceof DoubleConstant c) {
      return TacOperand.Const.ofDouble(c.getValue());
    }
    if (value instanceof BooleanConstant c) {
      return TacOperand.Const.ofBool(c.getValue());
    }
    if (value instanceof StringConstant c) {
      return TacOperand.Const.ofString(c.getValue());
    }
    if (value instanceof NullConstant) {
      return TacOperand.Const.ofNull();
    }
    if (value instanceof ClassConstant c) {
      // Represent a class literal as a string constant
      // "<ClassName>.class" — keeps the dataflow engine
      // happy without inventing a new operand kind.
      return TacOperand.Const.ofString(c.getValue());
    }
    if (value instanceof JParameterRef p) {
      return toVar(p);
    }
    if (value instanceof JThisRef) {
      return new TacOperand.TacVar(
          "this",
          TacType.ofClass(classNameOf(body.getMethodSignature().getDeclClassType())),
          false);
    }
    if (value instanceof JCaughtExceptionRef) {
      return new TacOperand.TacVar("$caught", TacType.ofClass("java.lang.Throwable"), true);
    }
    if (value instanceof JFieldRef || value instanceof JArrayRef) {
      // refs are not raw operands; if they appear here it's a
      // translator mistake. Return a sentinel to surface the bug.
      return new TacOperand.TacVar("<ref>", TacType.UNKNOWN, true);
    }
    // Fallback: log and produce a typed null-ish var so the engine
    // does not crash on an unknown Jimple value (we are
    // conservative: missing one is acceptable, crashing is not).
    return new TacOperand.TacVar(
        "<unhandled:" + value.getClass().getSimpleName() + ">", TacType.UNKNOWN, true);
  }

  /** Convert a {@link Local} to a {@link TacOperand.TacVar}, attaching the declared type. */
  public TacOperand.TacVar toVar(Local local) {
    return new TacOperand.TacVar(local.getName(), tacTypeOf(local.getType()), false);
  }

  /** Convert a parameter reference (index 0 == this for instance methods). */
  public TacOperand.TacVar toVar(JParameterRef p) {
    int idx = p.getIndex();
    // The method signature has the parameter types; for an
    // instance method index 0 is `this`, for static it starts at 0.
    Type paramType = p.getType();
    String name;
    if (idx == 0) {
      // The "this" parameter; caller should already have set up
      // the "this" var. We use the project convention: "this".
      name = "this";
    } else {
      // Jimple's parameter ref indexes ignore `this` for
      // instance methods; the project's TAC just needs a stable
      // synthetic name for the parameter slot. The caller is
      // responsible for stripping this if the method is static.
      int projectIdx = idx - 1;
      name = "@parameter" + projectIdx;
    }
    return new TacOperand.TacVar(name, tacTypeOf(paramType), false);
  }

  // ---- Type mapping ------------------------------------------------

  public TacType tacTypeOf(Type t) {
    if (t == null) return TacType.UNKNOWN;
    if (t instanceof VoidType) {
      return TacType.VOID;
    }
    if (t instanceof PrimitiveType pt) {
      if (pt instanceof PrimitiveType.BooleanType) return TacType.BOOLEAN;
      if (pt instanceof PrimitiveType.ByteType) return TacType.BYTE;
      if (pt instanceof PrimitiveType.ShortType) return TacType.SHORT;
      if (pt instanceof PrimitiveType.CharType) return TacType.CHAR;
      if (pt instanceof PrimitiveType.IntType) return TacType.INT;
      if (pt instanceof PrimitiveType.LongType) return TacType.LONG;
      if (pt instanceof PrimitiveType.FloatType) return TacType.FLOAT;
      if (pt instanceof PrimitiveType.DoubleType) return TacType.DOUBLE;
      return TacType.UNKNOWN;
    }
    if (t instanceof ArrayType at) {
      return TacType.arrayOf(tacTypeOf(at.getElementType()));
    }
    if (t instanceof ClassType ct) {
      String fqn = ct.getFullyQualifiedName();
      if ("java.lang.String".equals(fqn)) {
        return TacType.STRING;
      }
      return TacType.ofClass(fqn);
    }
    return TacType.UNKNOWN;
  }

  public static String classNameOf(ClassType ct) {
    if (ct == null) return "<unknown>";
    return ct.getFullyQualifiedName();
  }

  // ---- Binary / unary op mapping ----------------------------------

  /**
   * Return the project's {@link BinaryOperator} for a Jimple expression, or {@code null} if the
   * operator isn't recognized (caller falls back to a no-op or logs an unhandled).
   */
  public static BinaryOperator binaryOpOf(AbstractBinopExpr expr) {
    if (expr instanceof JAddExpr) return BinaryOperator.ADD;
    if (expr instanceof JSubExpr) return BinaryOperator.SUB;
    if (expr instanceof JMulExpr) return BinaryOperator.MUL;
    if (expr instanceof JDivExpr) return BinaryOperator.DIV;
    if (expr instanceof JRemExpr) return BinaryOperator.MOD;
    if (expr instanceof JShlExpr) return BinaryOperator.SHL;
    if (expr instanceof JShrExpr) return BinaryOperator.SHR;
    if (expr instanceof JUshrExpr) return BinaryOperator.USHR;
    if (expr instanceof JAndExpr) return BinaryOperator.AND;
    if (expr instanceof JOrExpr) return BinaryOperator.OR;
    if (expr instanceof JXorExpr) return BinaryOperator.XOR;
    if (expr instanceof AbstractConditionExpr) {
      if (expr instanceof JEqExpr) return BinaryOperator.EQ;
      if (expr instanceof JNeExpr) return BinaryOperator.NE;
      if (expr instanceof JLtExpr) return BinaryOperator.LT;
      if (expr instanceof JLeExpr) return BinaryOperator.LE;
      if (expr instanceof JGtExpr) return BinaryOperator.GT;
      if (expr instanceof JGeExpr) return BinaryOperator.GE;
    }
    return null;
  }

  /**
   * Return the project's {@link UnaryOperator} for a Jimple unop, or {@code null} if unrecognized.
   */
  public static UnaryOperator unaryOpOf(AbstractUnopExpr expr) {
    if (expr instanceof JNegExpr) return UnaryOperator.NEG;
    if (expr instanceof JLengthExpr) return null; // mapped at stmt level to ArrayLength below
    if (expr != null && expr.getClass().equals(JCastExpr.class)) {
      return UnaryOperator.CAST;
    }
    return null;
  }

  // ---- Invoke / new / array arg-list helpers -----------------------

  /**
   * Build a callee signature string in the project's "FQN.method/Arity" form (matches the
   * convention the existing AST translator uses, so the call-graph builder can find the function by
   * bare name).
   *
   * <p>Previously this emitted {@code "FQN#method/Arity"} with a {@code #} separator, which the
   * call-graph builder could not strip -- cross-class call resolution in the bytecode path was
   * silently broken. The AST path always used {@code .}, so unifying the two formats lets
   * multi-input and single-input bytecode scans resolve callees consistently. The byte-code format
   * doc note in the original JimpleOperandConverter claimed the format matched AST; this was a
   * documentation vs code drift that this fix closes.
   */
  public static String calleeSigOf(AbstractInvokeExpr expr) {
    String cls = classNameOf(expr.getMethodSignature().getDeclClassType());
    String name = expr.getMethodSignature().getName();
    int arity = expr.getMethodSignature().getParameterTypes().size();
    return cls + "." + name + "/" + arity;
  }

  public static String calleeSigOf(JSpecialInvokeExpr expr) {
    return calleeSigOf((AbstractInvokeExpr) expr);
  }

  public static String calleeSigOf(JVirtualInvokeExpr expr) {
    return calleeSigOf((AbstractInvokeExpr) expr);
  }

  public static String calleeSigOf(JStaticInvokeExpr expr) {
    return calleeSigOf((AbstractInvokeExpr) expr);
  }

  /**
   * Build the arg list for a call, with the receiver (if any) prepended. The project convention is
   * "args[0] is the receiver when present, args[1..] are the actual args".
   */
  public List<TacOperand> callArgs(AbstractInvokeExpr expr) {
    List<TacOperand> out = new ArrayList<>();
    if (expr instanceof AbstractInstanceInvokeExpr iie) {
      out.add(toOperand(iie.getBase()));
    }
    for (Object arg : expr.getArgs()) {
      out.add(toOperand(arg));
    }
    return out;
  }

  /**
   * Build the arg list for {@code new T(...)} (no receiver, the constructor's args are the
   * operands).
   */
  public List<TacOperand> newArgs(JNewExpr expr) {
    // new T() has no explicit args in Jimple; if T's constructor
    // takes parameters they appear on the special-invoke site
    // that follows. Return empty here.
    return new ArrayList<>();
  }

  // ---- Field / array ref helpers -----------------------------------

  /** Receiver of an instance field ref (for {@code FIELDLOAD} / {@code FIELDSTORE}). */
  public TacOperand fieldBase(JInstanceFieldRef ref) {
    return toOperand(ref.getBase());
  }

  public String fieldName(JFieldRef ref) {
    return ref.getFieldSignature().getName();
  }

  public TacOperand arrayBase(JArrayRef ref) {
    return toOperand(ref.getBase());
  }

  public TacOperand arrayIndex(JArrayRef ref) {
    return toOperand(ref.getIndex());
  }

  // ---- new / newarray helpers --------------------------------------

  public TacType newTypeOf(JNewExpr expr) {
    return tacTypeOf(expr.getType());
  }

  public TacType newArrayComponentOf(JNewArrayExpr expr) {
    return tacTypeOf(expr.getBaseType());
  }

  public TacOperand newArraySizeOf(JNewArrayExpr expr) {
    return toOperand(expr.getSize());
  }

  // ---- instanceof helper ------------------------------------------

  public TacType instanceofTypeOf(JInstanceOfExpr expr) {
    return tacTypeOf(expr.getCheckType());
  }

  // ---- Stmt identity helpers ---------------------------------------

  /**
   * Convert a {@link JIdentityStmt} ({@code l0 = @parameter N: T} or {@code l0 = this: T}) into the
   * assigned local.
   */
  public Optional<Local> identityLocal(JIdentityStmt stmt) {
    Object lhs = stmt.getLeftOp();
    if (lhs instanceof Local local) {
      return Optional.of(local);
    }
    return Optional.empty();
  }
}
