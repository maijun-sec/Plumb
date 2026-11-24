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
   * they run in. A few language primitives break that assumption, and the effect says which one
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
    CONCURRENT,
    /**
     * The execution LEAVES the function here.
     *
     * <p>It may come back to one of the function's resume points -- if the front end named any. A
     * front end that marks the suspension but does not say where it resumes says only that the
     * execution leaves, and that is still a fact worth keeping.
     *
     * <p>A generator's {@code yield} and a coroutine's {@code suspend} both do this: the current
     * execution stops at this instruction, the caller gets control back, and the execution resumes
     * later at a place the function itself named.
     *
     * <p><b>Why the effect alone is not enough.</b> The effect says "this instruction may leave";
     * it does not say where the execution comes back to. That is what {@link TacResumePoint} is
     * for: the function-level list of the places a suspended execution may resume from. The two
     * together are what a generator's state machine is made of -- the suspend points are the edges
     * out of a state, and the resume points are the states' entries.
     */
    SUSPEND
  }

  public final Kind kind;
  public final TacOperand dest; // may be null; usually TacVar but for STOREs can be any operand
  public final TacOperand lhs1; // for BINOP / UNARYOP / FIELDLOAD / ARRAYLOAD
  public final TacOperand lhs2; // for BINOP / ARRAYSTORE / FIELDSTORE / BRANCH
  public final BinaryOperator binop; // when kind == BINOP
  public final UnaryOperator unop; // when kind == UNARYOP
  public final TacType castType; // when kind == UNARYOP and unop == CAST

  /**
   * For CALL / NEW: the callee signature string.
   *
   * <p>A call names the method it calls ({@code "java.lang.Runtime.exec/1"}); a construction names
   * the class it constructs ({@code "java.io.File.<init>/1"}).
   */
  public final String calleeSig;

  /**
   * For CALL: the parameter types of the callee, as this call site resolved them.
   *
   * <p>The callee signature says <em>which method by name and by number of parameters</em>, and
   * those two facts do not say which of the overloads was called: {@code pick(String)} and {@code
   * pick(Object)} share both. A rule that asks "is this call the method I am looking at?" cannot
   * answer it from the signature alone, and guessing "same name, same arity, therefore the same
   * method" reports a call to one overload as a call to another.
   *
   * <p>This field answers it. One entry per parameter, each written the way a function signature
   * writes its parameter list ({@code "java.lang.String"}, {@code "int"}, {@code
   * "java.lang.String[]"}), so a consumer compares the list against the parameter list of the
   * enclosing function's signature directly.
   *
   * <p>Empty when the front end did not resolve the callee's parameter types. "Unknown" is not "the
   * same method": a consumer that cannot decide must not read an empty list as a match.
   */
  public final List<String> calleeParamTypes;

  /** For CALL / NEW: the actual argument list. */
  public final List<TacOperand> args;

  /**
   * For CALL / RETURN / THROW: the destinations of the results this instruction produces, in order.
   *
   * <p>A call may produce more than one value. Go's {@code v, err := f()} has two: the value and
   * the error. The TAC's CALL has ONE destination, so the further results had nowhere to go -- the
   * front end emitted them and the Java side dropped them, and the engine saw a call with one
   * result plus a variable ({@code err}) that nothing ever writes. A variable nothing writes is
   * indistinguishable from a clean one, so the error's taint was lost before the analysis started.
   *
   * <p><b>Entry 0 is the FIRST result.</b> For a CALL it is normally the same value as {@link
   * #dest}; for a RETURN it is normally the same value as {@link #lhs1}. The entries after it are
   * the further results: Go's error result, the "ok" of a comma-ok assignment, a channel receive's
   * open flag. The list is in the same order the wire protocol sends it, so the index of a result
   * is the same on both sides.
   *
   * <p>A null entry means the result exists but is bound to nothing -- the programmer discarded it
   * with the blank identifier ({@code v, _ := f()}). The entry is KEPT, because the POSITION is the
   * meaning: dropping it would shift every later result one slot to the left, and the engine would
   * then read the error result as the value result.
   *
   * <p>Empty when the instruction produces a single result, which is the ordinary case. Then the
   * one result is {@link #dest} (or {@link #lhs1} for a RETURN).
   *
   * <p><b>For a THROW, the list says what the exceptional exit hands back.</b> Go's {@code return
   * 0, err} returns two values, and the caller binds them to two variables -- but the function does
   * not return NORMALLY on that path: it exits exceptionally. The results therefore ride on the
   * THROW itself, and the thrown value ({@link #lhs1}) is also the last result: one value plays
   * both roles. A THROW with no list throws without handing anything back, which is the ordinary
   * case for an exception.
   */
  public final List<TacOperand> resultDests;

  /**
   * For CALL: the variables the call writes back into through a by-reference argument.
   *
   * <p>The list is parallel to {@link #args}: entry i is the variable that argument i is written
   * back into, and a null entry means that argument is not passed by reference. When the argument
   * itself IS the variable -- the ordinary case -- the entry is the same operand as {@code
   * args.get(i)}.
   *
   * <p>Empty when the call passes no argument by reference.
   *
   * <p><b>No front end fills this list yet.</b> Both wire protocols declare it and neither side car
   * writes it, so nothing in the engine reads it today: there is no code path that could. The shape
   * is connected anyway -- the IR has a place for it and the mapper reads it -- because a field
   * with nowhere to land cannot be connected later without changing the IR a second time.
   */
  public final List<TacOperand> refOutDests;

  /**
   * For NEW: the constructed type. For NEWARRAY: the component type. For INSTANCEOF: the type
   * tested against. For CATCH_HANDLER: the declared exception type. For THROW: the type of the
   * thrown value.
   *
   * <p><b>Why THROW needs one.</b> A catch handler matches on the type of the exception, so a TAC
   * that does not say what was thrown cannot say which handler an exception reaches: every handler
   * would have to be considered reachable from every throw. With the type on the instruction, the
   * CFG builder can decide the exception-flow edges by type -- see {@link TacCfgBuilder}. The
   * exception HIERARCHY is not here: it is world knowledge, and it comes from an {@link
   * ExceptionTypeMatcher} rather than from the instruction stream.
   *
   * <p>{@code null} when the front end did not know the type. That is not "the type is {@code
   * java.lang.Object}": it is the absence of a type, and a consumer must read it as "unknown"
   * rather than as a name.
   */
  public final TacType ctorType;

  /** For FIELDLOAD / FIELDSTORE: the field name. */
  public final String fieldName;

  /** For BRANCH: the true-target label name. */
  public final String trueLabel;

  /** For BRANCH: the false-target label name. */
  public final String falseLabel;

  /** For JUMP: the target label name. For LABEL: this label's own name. */
  public final String label;

  /**
   * For FIELDLOAD / FIELDSTORE: the FQN of the type that owns the field.
   *
   * <p>Without it, a field access says "some object's {@code name} field" and nothing more -- so a
   * checker cannot tell a field of the audited class from a field of a local helper object. The
   * owner makes the access attributable.
   *
   * <p>{@code null} when the front end did not resolve the owner.
   */
  public final String ownerType;

  /**
   * For BRANCH: the condition the branch tests, stated in full.
   *
   * <p>{@code null} for every other instruction kind, and for a branch whose front end could not
   * describe the condition any better than "some value is true".
   */
  public final TacGuard guard;

  /**
   * For LABEL / JUMP / BRANCH: what the referenced label IS.
   *
   * <p>{@code null} when the front end did not classify the label.
   */
  public final TacLabelRef labelRef;

  /**
   * For BRANCH / JUMP: the id of the region this branch belongs to.
   *
   * <p>The region's own body lives at the function level. {@code null} when the front end did not
   * build regions.
   */
  public final String branchRegion;

  /** Source line for diagnostics. -1 if unknown. */
  public final int line;

  /** Source column for diagnostics. -1 if unknown. */
  public final int column;

  /**
   * The execution effects of this instruction. Empty for an ordinary step.
   *
   * <p>Mostly a CALL has one: it is the call whose timing or concurrency differs from the order of
   * the instruction stream. A generator's suspend point has one too -- it is the instruction where
   * the execution leaves the function. An instruction with an empty set runs here, in this order,
   * like any other.
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
    this.calleeParamTypes =
        b.calleeParamTypes == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.calleeParamTypes));
    this.args =
        b.args == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.args));
    this.resultDests =
        b.resultDests == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.resultDests));
    this.refOutDests =
        b.refOutDests == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(b.refOutDests));
    this.ctorType = b.ctorType;
    this.fieldName = b.fieldName;
    this.trueLabel = b.trueLabel;
    this.falseLabel = b.falseLabel;
    this.label = b.label;
    this.ownerType = b.ownerType;
    this.guard = b.guard;
    this.labelRef = b.labelRef;
    this.branchRegion = b.branchRegion;
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

  /**
   * Whether the execution leaves the function at this instruction.
   *
   * <p>See {@link Effect#SUSPEND} and {@link TacFunction#resumePoints()}.
   */
  public boolean isSuspending() {
    return effects != null && effects.contains(Effect.SUSPEND);
  }

  /**
   * The callee's parameter types, or an empty list when the front end did not resolve them.
   *
   * <p>An instruction that came from a cache written before this field existed carries none: the
   * deserializer does not run the constructor. Read it through this accessor, not through the
   * field.
   */
  public List<String> knownCalleeParamTypes() {
    return calleeParamTypes == null ? Collections.emptyList() : calleeParamTypes;
  }

  /**
   * The destinations of the results after the first, or an empty list when there are none.
   *
   * <p>An instruction that came from a cache written before this field existed carries none: the
   * deserializer does not run the constructor. Read it through this accessor, not through the
   * field. An entry may itself be null -- that is how a discarded result is spelled.
   */
  public List<TacOperand> knownResultDests() {
    return resultDests == null ? Collections.emptyList() : resultDests;
  }

  /**
   * The by-reference write-back variables, or an empty list when the call passes none.
   *
   * <p>An instruction that came from a cache written before this field existed carries none: the
   * deserializer does not run the constructor. Read it through this accessor, not through the
   * field. An entry may itself be null -- that is how "this argument is not by reference" is
   * spelled.
   */
  public List<TacOperand> knownRefOutDests() {
    return refOutDests == null ? Collections.emptyList() : refOutDests;
  }

  /**
   * The operand that holds the result at the given index.
   *
   * <p>Index 0 is the FIRST result. When the instruction carries a result list, the list answers
   * every index; when it does not, the single result is the instruction's own destination ({@link
   * #dest} for a CALL, {@link #lhs1} for a RETURN).
   *
   * @param resultIndex the zero-based index of the result
   * @return the operand the result is bound to, or null when the result is unbound or the index is
   *     out of range
   */
  public TacOperand resultDestAt(int resultIndex) {
    if (resultIndex < 0) {
      return null;
    }
    List<TacOperand> all = knownResultDests();
    if (!all.isEmpty()) {
      return resultIndex < all.size() ? all.get(resultIndex) : null;
    }
    if (resultIndex == 0) {
      return kind == Kind.RETURN ? lhs1 : dest;
    }
    return null;
  }

  /**
   * How many results this instruction produces.
   *
   * <p>A CALL with no destination and no result list produces none -- a bare {@code f();}
   * statement. Everything else produces at least one.
   */
  public int resultCount() {
    List<TacOperand> all = knownResultDests();
    if (!all.isEmpty()) {
      return all.size();
    }
    return (kind == Kind.RETURN ? lhs1 : dest) == null ? 0 : 1;
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
    private List<String> calleeParamTypes;
    private List<TacOperand> args;
    private List<TacOperand> resultDests;
    private List<TacOperand> refOutDests;
    private TacType ctorType;
    private String fieldName;
    private String trueLabel;
    private String falseLabel;
    private String label;
    private String ownerType;
    private TacGuard guard;
    private TacLabelRef labelRef;
    private String branchRegion;
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

    /**
     * Records the parameter types of the callee, as the call site resolved them. One entry per
     * parameter, in the order the callee declares them.
     */
    public Builder calleeParamTypes(List<String> types) {
      this.calleeParamTypes = types;
      return this;
    }

    public Builder args(List<TacOperand> a) {
      this.args = a;
      return this;
    }

    /**
     * Records the destinations of the results, in order.
     *
     * <p>Entry 0 is the first result. A null entry means that result is bound to nothing; the entry
     * is kept so the positions stay aligned with the results themselves.
     */
    public Builder resultDests(List<TacOperand> dests) {
      this.resultDests = dests;
      return this;
    }

    /**
     * Records the by-reference write-back variables, parallel to the argument list.
     *
     * <p>A null entry means that argument is not passed by reference.
     */
    public Builder refOutDests(List<TacOperand> dests) {
      this.refOutDests = dests;
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

    /** Records the FQN of the type that owns the accessed field. */
    public Builder ownerType(String owner) {
      this.ownerType = owner;
      return this;
    }

    /** Records the condition this branch tests. */
    public Builder guard(TacGuard g) {
      this.guard = g;
      return this;
    }

    /** Records what the referenced label IS. */
    public Builder labelRef(TacLabelRef ref) {
      this.labelRef = ref;
      return this;
    }

    /** Records the region this branch belongs to. */
    public Builder branchRegion(String id) {
      this.branchRegion = id;
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
      case THROW -> {
        sb.append("throw ").append(lhs1);
        if (ctorType != null) {
          sb.append(" : ").append(ctorType);
        }
      }
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
