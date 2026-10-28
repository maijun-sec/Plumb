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
package org.plumb.tac.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.
/**
 * A single three-address instruction. The IR is small but expressive enough for the dataflow
 * engine: assignment, binary / unary ops, call, object creation, array access, field access,
 * branch, jump, label, return, throw, nop.
 *
 * <p>{@code Branch} carries the condition operand and two successor labels (true / false). The
 * order matches Java source: the "trueTarget" is the branch taken when the condition is truthy.
 */
public final class TacInstruction implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  public enum Kind {
    MOVE,
    BINOP,
    UNARYOP,
    CALL,
    NEW,
    NEWARRAY,
    ARRAYLOAD,
    ARRAYSTORE,
    FIELDLOAD,
    FIELDSTORE,
    INSTANCEOF,
    BRANCH,
    JUMP,
    LABEL,
    RETURN,
    THROW,
    NOP,
    /**
     * Marks the entry of a catch handler. The {@link #ctorType} field carries the declared
     * exception type (e.g. {@code Exception}, {@code java.io.IOException}). The catch parameter is
     * the {@link #dest} field (always a {@link TacOperand.TacVar}) and is non-null on entry by Java
     * semantics. Any {@link #THROW} in the enclosing try block (or any call that may throw) that
     * can propagate to a {@code CATCH_HANDLER} whose {@code ctorType} is a supertype of the thrown
     * type reaches this instruction.
     */
    CATCH_HANDLER,
    /**
     * Marks the entry of a {@code finally} block. Always executes after the try body completes
     * normally, after any catch handler that matches a propagated exception, or after a {@code try}
     * body's {@link #THROW} escapes the whole {@code try} (rethrow path). Modeled as a bare marker;
     * the run-time semantics are encoded by the CFG builder, which adds edges from every exit of
     * the try / catch region to this instruction.
     */
    FINALLY_ENTER
  }

  public final Kind kind;
  public final TacOperand dest; // may be null; usually TacVar but for STOREs can be any operand
  public final TacOperand lhs1; // for BINOP / UNARYOP / FIELDLOAD / ARRAYLOAD
  public final TacOperand lhs2; // for BINOP / ARRAYSTORE / FIELDSTORE / BRANCH
  public final BinaryOperator binop; // when kind == BINOP
  public final UnaryOperator unop; // when kind == UNARYOP
  public final TacType castType; // when kind == UNARYOP and unop == CAST

  /** For CALL: the callee signature string (e.g. {@code "java.lang.Runtime#exec"}). */
  public final String calleeSig;

  /** For CALL / NEW: the actual argument list. */
  public final List<TacOperand> args;

  /** For NEW: the constructed type. For NEWARRAY: the component type. */
  public final TacType ctorType;

  /** For FIELDLOAD / FIELDSTORE: the field name. */
  public final String fieldName;

  /** For BRANCH: the true-target label name. */
  public final String trueLabel;

  /** For BRANCH: the false-target label name. */
  public final String falseLabel;

  /** For JUMP: the target label name. For LABEL: this label's own name. */
  public final String label;

  /** Source line for diagnostics. -1 if unknown. */
  public final int line;

  /** Source column for diagnostics. -1 if unknown. */
  public final int column;

  private TacInstruction(Builder b) {
    this.kind = b.kind;
    this.dest = b.dest;
    this.lhs1 = b.lhs1;
    this.lhs2 = b.lhs2;
    this.binop = b.binop;
    this.unop = b.unop;
    this.castType = b.castType;
    this.calleeSig = b.calleeSig;
    this.args =
        b.args == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.args));
    this.ctorType = b.ctorType;
    this.fieldName = b.fieldName;
    this.trueLabel = b.trueLabel;
    this.falseLabel = b.falseLabel;
    this.label = b.label;
    this.line = b.line;
    this.column = b.column;
  }

  public static Builder builder(Kind kind) {
    return new Builder(kind);
  }

  public static final class Builder implements java.io.Serializable {
    private final Kind kind;
    private TacOperand dest;
    private TacOperand lhs1;
    private TacOperand lhs2;
    private BinaryOperator binop;
    private UnaryOperator unop;
    private TacType castType;
    private String calleeSig;
    private List<TacOperand> args;
    private TacType ctorType;
    private String fieldName;
    private String trueLabel;
    private String falseLabel;
    private String label;
    private int line = -1;
    private int column = -1;

    private Builder(Kind kind) {
      this.kind = Objects.requireNonNull(kind);
    }

    public Builder dest(TacOperand v) {
      this.dest = v;
      return this;
    }

    public Builder lhs1(TacOperand o) {
      this.lhs1 = o;
      return this;
    }

    public Builder lhs2(TacOperand o) {
      this.lhs2 = o;
      return this;
    }

    public Builder binop(BinaryOperator op) {
      this.binop = op;
      return this;
    }

    public Builder unop(UnaryOperator op) {
      this.unop = op;
      return this;
    }

    public Builder castType(TacType t) {
      this.castType = t;
      return this;
    }

    public Builder calleeSig(String s) {
      this.calleeSig = s;
      return this;
    }

    public Builder args(List<TacOperand> a) {
      this.args = a;
      return this;
    }

    public Builder ctorType(TacType t) {
      this.ctorType = t;
      return this;
    }

    public Builder fieldName(String s) {
      this.fieldName = s;
      return this;
    }

    public Builder trueLabel(String s) {
      this.trueLabel = s;
      return this;
    }

    public Builder falseLabel(String s) {
      this.falseLabel = s;
      return this;
    }

    public Builder label(String s) {
      this.label = s;
      return this;
    }

    public Builder line(int l) {
      this.line = l;
      return this;
    }

    public Builder column(int c) {
      this.column = c;
      return this;
    }

    public TacInstruction build() {
      return new TacInstruction(this);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    if (line > 0) sb.append(String.format("%4d: ", line));
    switch (kind) {
      case MOVE -> sb.append(dest).append(" = ").append(lhs1);
      case BINOP ->
          sb.append(dest)
              .append(" = ")
              .append(lhs1)
              .append(' ')
              .append(binop)
              .append(' ')
              .append(lhs2);
      case UNARYOP -> sb.append(dest).append(" = ").append(unop).append(' ').append(lhs1);
      case CALL -> {
        if (dest != null) sb.append(dest).append(" = ");
        sb.append(calleeSig == null ? "<call>" : calleeSig).append('(').append(args).append(')');
      }
      case NEW -> {
        if (dest != null) sb.append(dest).append(" = ");
        sb.append("new ").append(ctorType).append('(').append(args).append(')');
      }
      case NEWARRAY -> {
        if (dest != null) sb.append(dest).append(" = ");
        sb.append("new ").append(ctorType).append('[').append(lhs1).append(']');
      }
      case ARRAYLOAD ->
          sb.append(dest).append(" = ").append(lhs1).append('[').append(lhs2).append(']');
      case ARRAYSTORE -> sb.append(lhs1).append('[').append(lhs2).append("] = ").append(dest);
      case FIELDLOAD -> sb.append(dest).append(" = ").append(lhs1).append('.').append(fieldName);
      case FIELDSTORE -> sb.append(lhs1).append('.').append(fieldName).append(" = ").append(dest);
      case INSTANCEOF ->
          sb.append(dest).append(" = ").append(lhs1).append(" instanceof ").append(ctorType);
      case BRANCH ->
          sb.append("if ")
              .append(lhs1)
              .append(" goto ")
              .append(trueLabel)
              .append(" else goto ")
              .append(falseLabel);
      case JUMP -> sb.append("goto ").append(label);
      case LABEL -> sb.append(label).append(':');
      case RETURN -> sb.append("return").append(lhs1 == null ? "" : " " + lhs1);
      case THROW -> sb.append("throw ").append(lhs1);
      case CATCH_HANDLER -> sb.append("catch(").append(ctorType).append(") -> ").append(dest);
      case FINALLY_ENTER -> sb.append("finally: ");
      case NOP -> sb.append("nop");
    }
    return sb.toString();
  }
}
