package org.plumb.dfa.cg;

import org.plumb.tac.ir.TacInstruction;

/**
 * A call edge in the {@link CallGraph}.
 *
 * <p>Records the caller's signature, the callee's signature, the {@link TacInstruction} that
 * emitted the call (so the dataflow walk can reproduce the call site), and an optional
 * human-readable call-site line number for reporting.
 *
 * <p>The kind field distinguishes direct (static dispatch) from indirect (virtual / interface)
 * calls. The analysis engine uses this to decide whether to look up a single callee summary or
 * multiple candidates.
 */
public final class CallSite {

  public enum Kind {
    /** Static dispatch or invokestatic / invokevirtual with a single resolved target. */
    DIRECT,
    /**
     * Virtual call (invokevirtual / invokeinterface) with one or more possible targets resolved via
     * the class hierarchy.
     */
    VIRTUAL,
    /**
     * Indirect call (e.g. reflection, method-handle, dynamic proxy). Resolved conservatively to all
     * methods matching the name+arity.
     */
    INDIRECT,
    /**
     * Call to a JDK / framework library function that the analysis treats as opaque (no body to
     * analyze).
     */
    LIBRARY
  }

  private final FunctionNode caller;
  private final FunctionNode callee;
  private final int callerInstructionIndex;
  private final int line;
  private final Kind kind;

  public CallSite(
      FunctionNode caller, FunctionNode callee, int callerInstructionIndex, int line, Kind kind) {
    this.caller = caller;
    this.callee = callee;
    this.callerInstructionIndex = callerInstructionIndex;
    this.line = line;
    this.kind = kind == null ? Kind.DIRECT : kind;
  }

  public FunctionNode caller() {
    return caller;
  }

  public FunctionNode callee() {
    return callee;
  }

  public int callerInstructionIndex() {
    return callerInstructionIndex;
  }

  public int line() {
    return line;
  }

  public Kind kind() {
    return kind;
  }

  @Override
  public String toString() {
    return "CallSite("
        + caller.signature()
        + " -> "
        + callee.signature()
        + " @"
        + line
        + ", "
        + kind
        + ")";
  }
}
