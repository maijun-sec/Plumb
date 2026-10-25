package org.plumb.dfa.fact;

import java.util.Objects;
import org.plumb.dfa.config.FactKind;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacOperand.TacVar;

/**
 * A single per-operand data-flow fact.
 *
 * <p>A {@code Fact} is the runtime instance of a {@link FactKind}: "this {@link TacOperand} (the
 * subject) carries the semantic property {@code kind} with this particular {@code value} (e.g. a
 * literal, a sink-family key, a numerical range)". The {@code origin} instruction is where the fact
 * was first asserted (for debugging / finding messages).
 *
 * <h2>Why a per-operand instance and not just a kind on the operand</h2>
 *
 * <p>The same {@link FactKind} can apply to many operands (every var that holds user input has the
 * "UntrustedSource" kind). It can also apply to one operand with different values (a string that
 * may be either A or B). The instance carries the value because some checkers care about which
 * specific value (e.g. "this operand is the literal {@code 5}"; the literal matters for
 * divide-by-zero suppression). The {@link FactKind} declares the propagation policy; the {@code
 * Fact} carries the runtime data.
 *
 * <h2>Three orthogonal layers</h2>
 *
 * <p>The fact layer is one of Plumb's three data-flow layers, alongside the FunctionSummary layer:
 *
 * <ul>
 *   <li><b>Layer 1 - {@link FactKind} + {@code Trait} bits</b>: a global registry of "kinds of
 *       facts the analysis cares about", with a declarative statement of how each kind is meant to
 *       propagate. The traits are documentation: no production code reads them, and the propagation
 *       is implemented by the checker that owns the kind.
 *   <li><b>Layer 2 - {@code Fact} instance</b>: a single "(operand, kind, value, origin)" tuple
 *       observed by a checker during analysis. Stored in a per-function {@link FactBag} for that
 *       run.
 *   <li><b>Layer 3 - {@code FunctionSummary} + {@code Operation}</b>: the accumulated
 *       cross-function view that summarises a callee's param-bound effects for callers to consume
 *       without re-analysing the callee body.
 * </ul>
 *
 * <p>The three layers compose: a checker observes a {@code Fact} in layer 2 during {@code
 * checkInstruction}; at the end of the function it converts the layer-2 facts into a layer-3 {@code
 * FunctionSummary} so the next caller doesn't have to redo the analysis.
 */
public final class Fact {

  private final FactKind kind;
  private final TacOperand operand;
  private final String value; // free-form; the kind gives it shape
  private final TacInstruction origin; // nullable

  public Fact(FactKind kind, TacOperand operand, String value, TacInstruction origin) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.operand = Objects.requireNonNull(operand, "operand");
    this.value = value == null ? "" : value;
    this.origin = origin;
  }

  /** Convenience constructor for facts without a recorded origin. */
  public Fact(FactKind kind, TacOperand operand, String value) {
    this(kind, operand, value, null);
  }

  public FactKind kind() {
    return kind;
  }

  public TacOperand operand() {
    return operand;
  }

  public String value() {
    return value;
  }

  public TacInstruction origin() {
    return origin;
  }

  /** Whether this fact is of the given kind. */
  public boolean hasKind(FactKind k) {
    return kind.equals(k);
  }

  /** Whether this fact's operand matches by name. */
  public boolean isFor(String operandName) {
    return operand instanceof TacVar v && v.name().equals(operandName);
  }

  @Override
  public String toString() {
    return "Fact{" + kind.name() + "@" + operand + "=" + value + "}";
  }
}
