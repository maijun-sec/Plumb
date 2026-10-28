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
