/*
 * OperandRef ? the structured operand of a
 * {@link TraceNode}. v1's {@link FlowStep#expr()} was a
 * free-form string ("MOVE x -> y", "String.toLowerCase
 * passthrough -> dest"); AI dedup and SARIF consumers
 * had to regex-parse it. v2 replaces that string with a
 * sealed-interface union so consumers can dispatch on
 * the concrete shape.
 *
 * <p>Each {@link TraceNode} carries 0..N operands. The
 * shapes here cover the operands the engine currently
 * emits (MOVE / BINOP / FIELDLOAD / ARRAYLOAD / CALL
 * passthroughs / summary-fold). New shapes can be added
 * here as new checkers are ported.</p>
 *
 * <p><b>Backwards compat</b>: a {@link OperandRef} is
 * always renderable to a human-readable string via
 * {@link #render()}. {@link FlowStep} legacy paths still
 * work because {@link Trace#toFlowSteps()} converts a
 * Trace back into {@code List<FlowStep>} with the
 * canonical string form.</p>
 */
package org.plumb.reporter.trace;

import java.util.Objects;

public sealed interface OperandRef {

  /**
   * A local / SSA / param variable. {@code ssaVersion} is the post-increment suffix the TAC IR
   * appends (e.g. {@code "x:1"} for the second SSA version of {@code x}). Use {@code ssaVersion=0}
   * when the SSA version is not tracked (legacy / cross-fn summary-fold cases).
   */
  record Var(String name, int ssaVersion) implements OperandRef {
    public Var {
      Objects.requireNonNull(name, "name");
      if (ssaVersion < 0) {
        throw new IllegalArgumentException("ssaVersion < 0: " + ssaVersion);
      }
    }

    @Override
    public String render() {
      return ssaVersion == 0 ? name : name + ":" + ssaVersion;
    }
  }

  /**
   * A function parameter, indexed by position. The {@code name} is the source-level name; the
   * {@code paramIndex} is the 0-based position in {@code TacFunction#params()}.
   */
  record ParamRef(int paramIndex, String name) implements OperandRef {
    public ParamRef {
      if (paramIndex < 0) {
        throw new IllegalArgumentException("paramIndex < 0: " + paramIndex);
      }
      Objects.requireNonNull(name, "name");
    }

    @Override
    public String render() {
      return "param:" + paramIndex + ":" + name;
    }
  }

  /**
   * Integer constant. Long is used to be uniform with TacOperand.Const.Kind.INT / LONG at the IR
   * level.
   */
  record ConstInt(long value) implements OperandRef {
    @Override
    public String render() {
      return Long.toString(value);
    }
  }

  /** String constant. {@code value} may be empty but not null. */
  record ConstStr(String value) implements OperandRef {
    public ConstStr {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public String render() {
      return "\"" + value + "\"";
    }
  }

  /** The Java {@code null} literal. */
  record ConstNull() implements OperandRef {
    @Override
    public String render() {
      return "null";
    }
  }

  record ConstFloat(double value) implements OperandRef {
    @Override
    public String render() {
      return Double.toString(value);
    }
  }

  /**
   * A call site reference. {@code calleeSig} is the canonical {@code Class.method/arity} form (the
   * same shape {@code TacInstruction.calleeSig} uses).
   */
  record CallRef(String calleeSig) implements OperandRef {
    public CallRef {
      Objects.requireNonNull(calleeSig, "calleeSig");
    }

    @Override
    public String render() {
      return calleeSig;
    }
  }

  /**
   * A constructor site reference. {@code typeName} is the simple class name (e.g. {@code
   * "FileInputStream"}) or the FQN ? both shapes appear in the IR.
   */
  record CtorRef(String typeName) implements OperandRef {
    public CtorRef {
      Objects.requireNonNull(typeName, "typeName");
    }

    @Override
    public String render() {
      return "new " + typeName + "(...)";
    }
  }

  /**
   * A field reference. {@code owner} is the simple class name (or {@code ""} for an un-classed
   * field); {@code fieldName} is the field's simple name.
   */
  record FieldRef(String owner, String fieldName) implements OperandRef {
    public FieldRef {
      Objects.requireNonNull(fieldName, "fieldName");
      Objects.requireNonNull(owner, "owner");
    }

    @Override
    public String render() {
      return owner.isEmpty() ? fieldName : owner + "." + fieldName;
    }
  }

  /**
   * ? a reference to a {@code public static} field of a class (e.g. {@code Level.WARNING}, {@code
   * IO.logger}). Distinct from {@link FieldRef} because the receiver is a <i>class</i>, not an
   * instance, and the declaration site is the class body (not an instance's field write). Pair with
   * {@link TraceNode.Op#STATIC_FIELD_DECL} to mark the field's initializer node.
   *
   * <p>{@code isFinal} is captured so consumers can tell the difference between {@code public
   * static final} (which the resolver can fold) and {@code public static} mutable (which Phase 2 of
   * ? init-path resolution ? will handle).
   */
  record StaticFieldRef(String className, String fieldName, boolean isFinal) implements OperandRef {
    public StaticFieldRef {
      Objects.requireNonNull(className, "className");
      Objects.requireNonNull(fieldName, "fieldName");
    }

    @Override
    public String render() {
      return className + "." + fieldName;
    }
  }

  /**
   * Free-form text ? escape hatch for the rare case the engine cannot represent the operand
   * structurally (e.g. a folded "param + literal" expression that the v2 model has not yet been
   * taught to encode). New code SHOULD prefer the typed records above; this exists so the v2
   * migration can land without breaking unknown operand shapes.
   */
  record Free(String text) implements OperandRef {
    public Free {
      Objects.requireNonNull(text, "text");
    }

    @Override
    public String render() {
      return text;
    }
  }

  /** Canonical string form for legacy {@code FlowStep#expr()} compat. */
  String render();
}
