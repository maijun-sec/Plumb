package org.plumb.tac.translate;

import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Result of translating an expression: a {@link TacOperand} plus the inferred type. The operand can
 * be either a {@link TacOperand.TacVar} (named or temporary) or a {@link TacOperand.Const}
 * (literal).
 *
 * <p>{@link #lambdaSignature} is set when the expression's value is a synthetic {@code lambda$N}
 * function reference. The translator uses it to record the user-var → lambda binding so the
 * inter-procedural taint engine can follow {@code f.apply(...)} into the lambda body.
 */
public record ExpressionResult(TacOperand operand, TacType type, String lambdaSignature) {

  public ExpressionResult {
    if (operand == null) throw new IllegalArgumentException("operand");
    type = type == null ? TacType.UNKNOWN : type;
  }

  public ExpressionResult(TacOperand operand, TacType type) {
    this(operand, type, null);
  }

  public static ExpressionResult of(TacOperand operand, TacType type) {
    return new ExpressionResult(operand, type, null);
  }

  public static ExpressionResult ofConst(TacOperand.Const c) {
    return new ExpressionResult(c, c.type(), null);
  }

  /** Build a result that carries a lambda binding. */
  public static ExpressionResult ofLambda(TacOperand operand, String lambdaSignature) {
    return new ExpressionResult(operand, TacType.UNKNOWN, lambdaSignature);
  }
}
