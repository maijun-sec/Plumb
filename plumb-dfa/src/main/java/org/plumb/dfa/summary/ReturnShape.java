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

import java.util.Objects;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Shape of a method's return value, as observed from the TAC IR.
 *
 * <p>The shape tells the engine "if I call this method, what should the return value look like?"
 * Each variant carries exactly the data needed to reconstruct the value symbolically at the call
 * site.
 *
 * <p>Ten shapes are recognised: {@link #RETURN_VOID}, {@link #RETURN_CONST}, {@link #RETURN_PARAM},
 * {@link #RETURN_THIS_FIELD}, {@link #RETURN_STATIC_FIELD}, {@link #RETURN_LOCAL_VAR}, {@link
 * #RETURN_BINARY_OP}, {@link #RETURN_CONDITIONAL}, {@link #RETURN_NEW_INSTANCE}, {@link #UNKNOWN}.
 */
public final class ReturnShape {

  public enum Kind {
    RETURN_VOID,
    RETURN_CONST,
    RETURN_PARAM,
    RETURN_THIS_FIELD,
    RETURN_STATIC_FIELD,
    RETURN_LOCAL_VAR,
    RETURN_BINARY_OP,
    RETURN_CONDITIONAL,
    RETURN_NEW_INSTANCE,
    UNKNOWN
  }

  public final Kind kind;

  // RETURN_CONST payload
  public final TacOperand.Const constValue;
  // RETURN_PARAM payload
  public final int paramIndex;
  // RETURN_THIS_FIELD payload
  public final String thisFieldName;
  // RETURN_STATIC_FIELD payload
  public final String staticFieldKey; // "FQCN.field"
  // RETURN_LOCAL_VAR payload (the TacVar name)
  public final String localName;
  // RETURN_BINARY_OP payload
  public final BinaryOperator binop;
  public final TacOperand binopLhs;
  public final TacOperand binopRhs;
  // RETURN_CONDITIONAL payload (the two arms; can be any shape)
  public final ReturnShape condTrue;
  public final ReturnShape condFalse;
  // RETURN_NEW_INSTANCE payload
  public final TacType newInstanceType;
  // UNKNOWN payload (optional reason for diagnostics)
  public final String unknownReason;

  private ReturnShape(Builder b) {
    this.kind = Objects.requireNonNull(b.kind);
    this.constValue = b.constValue;
    this.paramIndex = b.paramIndex;
    this.thisFieldName = b.thisFieldName;
    this.staticFieldKey = b.staticFieldKey;
    this.localName = b.localName;
    this.binop = b.binop;
    this.binopLhs = b.binopLhs;
    this.binopRhs = b.binopRhs;
    this.condTrue = b.condTrue;
    this.condFalse = b.condFalse;
    this.newInstanceType = b.newInstanceType;
    this.unknownReason = b.unknownReason;
  }

  public static ReturnShape voidShape() {
    return new Builder(Kind.RETURN_VOID).build();
  }

  public static ReturnShape constShape(TacOperand.Const c) {
    return new Builder(Kind.RETURN_CONST).constValue(c).build();
  }

  public static ReturnShape paramShape(int idx) {
    return new Builder(Kind.RETURN_PARAM).paramIndex(idx).build();
  }

  public static ReturnShape thisFieldShape(String field) {
    return new Builder(Kind.RETURN_THIS_FIELD).thisFieldName(field).build();
  }

  public static ReturnShape staticFieldShape(String key) {
    return new Builder(Kind.RETURN_STATIC_FIELD).staticFieldKey(key).build();
  }

  public static ReturnShape localShape(String localName) {
    return new Builder(Kind.RETURN_LOCAL_VAR).localName(localName).build();
  }

  public static ReturnShape binopShape(BinaryOperator op, TacOperand l, TacOperand r) {
    return new Builder(Kind.RETURN_BINARY_OP).binop(op).binopLhs(l).binopRhs(r).build();
  }

  public static ReturnShape condShape(ReturnShape t, ReturnShape f) {
    return new Builder(Kind.RETURN_CONDITIONAL).condTrue(t).condFalse(f).build();
  }

  public static ReturnShape newInstanceShape(TacType type) {
    return new Builder(Kind.RETURN_NEW_INSTANCE).newInstanceType(type).build();
  }

  public static ReturnShape unknown(String reason) {
    return new Builder(Kind.UNKNOWN).unknownReason(reason).build();
  }

  @Override
  public String toString() {
    return switch (kind) {
      case RETURN_VOID -> "void";
      case RETURN_CONST -> "const(" + constValue + ")";
      case RETURN_PARAM -> "param(" + paramIndex + ")";
      case RETURN_THIS_FIELD -> "this." + thisFieldName;
      case RETURN_STATIC_FIELD -> "static(" + staticFieldKey + ")";
      case RETURN_LOCAL_VAR -> "local(" + localName + ")";
      case RETURN_BINARY_OP -> binop + "(" + binopLhs + "," + binopRhs + ")";
      case RETURN_CONDITIONAL -> "cond(" + condTrue + " | " + condFalse + ")";
      case RETURN_NEW_INSTANCE -> "new " + newInstanceType;
      case UNKNOWN -> "unknown(" + unknownReason + ")";
    };
  }

  public static final class Builder {
    private final Kind kind;
    private TacOperand.Const constValue;
    private int paramIndex = -1;
    private String thisFieldName;
    private String staticFieldKey;
    private String localName;
    private BinaryOperator binop;
    private TacOperand binopLhs;
    private TacOperand binopRhs;
    private ReturnShape condTrue;
    private ReturnShape condFalse;
    private TacType newInstanceType;
    private String unknownReason;

    public Builder(Kind kind) {
      this.kind = kind;
    }

    public Builder constValue(TacOperand.Const c) {
      this.constValue = c;
      return this;
    }

    public Builder paramIndex(int i) {
      this.paramIndex = i;
      return this;
    }

    public Builder thisFieldName(String s) {
      this.thisFieldName = s;
      return this;
    }

    public Builder staticFieldKey(String s) {
      this.staticFieldKey = s;
      return this;
    }

    public Builder localName(String s) {
      this.localName = s;
      return this;
    }

    public Builder binop(BinaryOperator op) {
      this.binop = op;
      return this;
    }

    public Builder binopLhs(TacOperand o) {
      this.binopLhs = o;
      return this;
    }

    public Builder binopRhs(TacOperand o) {
      this.binopRhs = o;
      return this;
    }

    public Builder condTrue(ReturnShape s) {
      this.condTrue = s;
      return this;
    }

    public Builder condFalse(ReturnShape s) {
      this.condFalse = s;
      return this;
    }

    public Builder newInstanceType(TacType t) {
      this.newInstanceType = t;
      return this;
    }

    public Builder unknownReason(String s) {
      this.unknownReason = s;
      return this;
    }

    public ReturnShape build() {
      return new ReturnShape(this);
    }
  }
}
