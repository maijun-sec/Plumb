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
     * Virtual call (invokevirtual / invokeinterface) with one or more possible targets.
     *
     * <p>The targets are resolved by matching the callee's simple name and arity against the
     * functions in the program -- NOT by walking the class hierarchy. That is the standard CHA
     * over-approximation: every type that could be the receiver's dynamic type is a possible
     * target, and the name+arity match reaches them all.
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
