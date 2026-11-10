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
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
    FINALLY_ENTER,
    /**
     * A compiler-inserted null check.
     *
     * <p>Semantics: if {@code dest} is null, throw NPE; otherwise {@code dest} is proven non-null.
     *
     * <p><b>Purpose is strictly limited to removing false positives.</b> A null-dereference checker
     * uses this to know that a value has already been proven non-null, and therefore does not need
     * to report it. It must <b>never</b> be used to <b>prove safety</b>: Kotlin's compiler-inserted
     * checks can be removed by {@code -Xno-param-assertions} / {@code -Xno-call-assertions}, and
     * ProGuard/R8's {@code -assumenosideeffects} strips them from bytecode. "There is no check in
     * the bytecode" does not imply "the runtime is safe".
     *
     * <p><b>Do not map these to NULL_CHECK:</b>
     *
     * <ul>
     *   <li>{@code kotlin.jvm.internal.Intrinsics.areEqual} -- the null-safe comparison helper used
     *       by a data class's generated {@code equals()}. It is not a null check; treating it as
     *       one would pollute the non-null fact set with "these two values are equal".
     *   <li>{@code kotlin.jvm.internal.Intrinsics.throwUninitializedPropertyAccessException} -- the
     *       {@code lateinit} access check. It is not a null check.
     * </ul>
     *
     * <p>A source frontend that read the type system does not need this opcode for ordinary
     * declarations (the nullability is already in {@link TacType#isNullable()}); it is used for
     * control-flow narrowing that the type system cannot express -- smart casts, explicit {@code
     * requireNotNull}, and platform types coming from Java interop.
     */
    NULL_CHECK,
    /**
     * The destination becomes an <b>unknown value</b>: any value at all.
     *
     * <p>This is the explicit form of "the frontend could not model this". It exists because the
     * alternative was to degrade the whole function to {@link IrPrecision#SOURCE_LOWERED}, which
     * throws away the parts of the function that <em>were</em> understood -- including the taint
     * that had already reached the function's parameters. One unreadable expression should cost one
     * instruction's precision, not the whole function's.
     *
     * <p><b>What the engine does with it.</b> The destination is not tainted (nothing is known
     * about it), and it is not proven safe either. A checker that needs to know a value's origin
     * must treat it as unknown rather than as clean.
     *
     * <p><b>What it does not mean.</b> It does not mean "the value is attacker-controlled", and it
     * does not mean "the value is benign". It means the analysis does not know. A checker that
     * treats a HAVOC destination as tainted will report findings the code may not deserve; one that
     * treats it as clean will miss findings it might deserve. Neither is right, and both are wrong
     * in a way the user cannot see -- which is why the frontend must report the problem separately,
     * through the {@code FunctionProblem} channel, so the incompleteness is visible.
     *
     * <p><b>Where it comes from.</b> A frontend that meets a construct it cannot lower into the
     * existing opcodes. The frontend's job is to emit a HAVOC for the affected value <em>and</em>
     * to record a problem saying what it could not lower.
     */
    HAVOC,
    /**
     * A call whose internal behaviour is <b>not modelled</b>: a black box.
     *
     * <p>The difference from {@link #HAVOC} is where the unknown lies. {@code HAVOC} says "this
     * value is unknown"; {@code UNINTERPRETED} says "this call's effect is unknown -- it may return
     * anything, and it may have side effects".
     *
     * <p><b>Why the distinction matters.</b> A black-box call is a <em>taint barrier</em> in one
     * direction and a <em>taint source</em> in another, and which one it is depends on the call,
     * not on the value. {@code String.getBytes()} passes its argument's taint through; {@code
     * Object.hashCode()} may return a value derived from anything the object holds.
     *
     * <p><b>The default treatment.</b> An {@code UNINTERPRETED} call is assumed to be
     * taint-preserving on its arguments and to possibly produce a tainted result. That is the
     * conservative choice for a tool whose users hold it to a low false-negative bar: the risk is a
     * false positive on an unknown API, not a missed vulnerability.
     *
     * <p>When the engine does know a call's semantics -- because a summary exists, or because the
     * call's target was resolved and analysed -- the call is a plain {@link #CALL} and this opcode
     * is not used. {@code UNINTERPRETED} is for calls the engine genuinely cannot see inside.
     */
    UNINTERPRETED
  }

  /**
   * How the call at this instruction actually runs, when that is not the same as "here, in this
   * order".
   *
   * <p>The instruction stream is a sequence of steps, and for most calls that sequence IS the order
   * they run in. Two language primitives break that assumption, and the effect says which one
   * applies.
   *
   * <p><b>Each language keeps its own primitives here</b>, rather than being forced into one shared
   * thread model. Go's {@code defer} and {@code go} are not Java's {@code synchronized}, and a
   * single "concurrency" flag would say less than the truth about any of them. A language that has
   * no such primitive leaves the set empty, and the instruction is an ordinary step.
   */
  public enum Effect {
    /**
     * The call runs when the enclosing function returns, not at this point in the stream.
     *
     * <p>Go's {@code defer f(x)} evaluates its arguments HERE and runs the call when the function
     * exits. The arguments are already in the instruction stream in the right place, so the taint
     * that reaches them is correct; only the TIMING of the call is not. A checker that wants to
     * know what the program does with the result has to know that the result arrives later.
     */
    DEFERRED,
    /**
     * The call runs on another thread of execution, concurrently with the rest of the function.
     *
     * <p>Go's {@code go f()} starts a goroutine. The engine models no happens-before relation
     * between it and anything else, so a checker that relies on the order of the instructions would
     * conclude something the program does not guarantee.
     */
    CONCURRENT
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

  /**
   * The execution effects of this instruction. Empty for an ordinary step.
   *
   * <p>Only a CALL has one: it is the call whose timing or concurrency differs from the order of
   * the instruction stream. An instruction with an empty set runs here, in this order, like any
   * other.
   */
  public final Set<Effect> effects;

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
    this.effects =
        b.effects == null || b.effects.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(b.effects));
  }

  /**
   * Whether the call runs when the enclosing function returns rather than at this point in the
   * stream.
   */
  public boolean isDeferred() {
    // The set is null on an instruction that came from a cache written
    // before the field existed: deserialization does not run a
    // constructor. An instruction with no effects is an ordinary one.
    return effects != null && effects.contains(Effect.DEFERRED);
  }

  /**
   * Whether the call runs on another thread of execution, concurrently with the rest of the
   * function.
   */
  public boolean isConcurrent() {
    return effects != null && effects.contains(Effect.CONCURRENT);
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
    private Set<Effect> effects;

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

    /** Records one execution effect on this instruction. */
    public Builder effect(Effect e) {
      if (e == null) {
        return this;
      }
      if (this.effects == null) {
        this.effects = EnumSet.noneOf(Effect.class);
      }
      this.effects.add(e);
      return this;
    }

    /** Records several execution effects at once. */
    public Builder effects(Collection<Effect> es) {
      if (es == null || es.isEmpty()) {
        return this;
      }
      if (this.effects == null) {
        this.effects = EnumSet.noneOf(Effect.class);
      }
      this.effects.addAll(es);
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
        appendEffects(sb);
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
      case HAVOC -> sb.append(dest).append(" = <unknown>");
      case UNINTERPRETED -> {
        if (dest != null) sb.append(dest).append(" = ");
        sb.append("<uninterpreted>(")
            .append(calleeSig == null ? "<call>" : calleeSig)
            .append(')')
            .append(args);
      }
    }
    return sb.toString();
  }

  private void appendEffects(StringBuilder sb) {
    if (effects == null || effects.isEmpty()) {
      return;
    }
    sb.append(" [");
    boolean first = true;
    for (Effect e : effects) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(e);
    }
    sb.append(']');
  }
}
