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

/**
 * A field write observed inside a function. Recorded in the function's {@link FunctionSummary} so
 * that cross-function taint can survive summary fold when a helper stores a tainted param into a
 * field.
 *
 * <p>Background: a function like {@code void setData(String d) { this.data = d; }} writes a
 * parameter to an instance field. The caller-side taint checker that does {@code
 * setData(tainted_arg); ...; use(this.data); } needs the summary to tell it "the field is now
 * tainted iff the arg was tainted".
 *
 * <p>Previously, the only way to encode this was {@link Operation.WriteToField}, which records the
 * field name but not the source (a caller seeing {@code WriteToField("data")} knows the field is
 * written but can't tell whether it was written from a parameter, from a constant, or from a chain
 * of computations). A dedicated {@code FieldOp} with a {@link #sourceParamIndex()} preserves the
 * source so the caller can apply the appropriate taint propagation.
 *
 * <h2>Source semantics</h2>
 *
 * <p>{@link #sourceParamIndex()} is the 0-based parameter index of the value being written. The
 * value {@code -1} means "the source is NOT a parameter" (a local computation, a constant, a call
 * return value, etc.). For the simple {@code this.f = param_N} pattern the index is N. For {@code
 * this.f = param_N.toLowerCase()} the index is still N (the string-passthrough would be a separate
 * {@link StringOp} in the same summary).
 *
 * <h2>Receiver semantics</h2>
 *
 * <p>Only {@code this}-targeted field writes are recorded (the typical setter pattern). Writes to
 * other-class fields and to static fields are out of scope (deferred per the design doc).
 *
 * <h2>Application</h2>
 *
 * <p>Caller-side checkers consume the {@link FunctionSummary#fieldOps()} list to seed the
 * corresponding field with taint from the matching caller arg. The {@code FieldTaintTracker} will
 * use this data to propagate taint through the field-write → field-read chain across functions.
 */
public record FieldOp(String fieldName, int sourceParamIndex) {

  /** Sentinel: source is NOT a parameter. */
  public static final int NON_PARAM_SOURCE = -1;

  public FieldOp {
    Objects.requireNonNull(fieldName, "fieldName");
    if (fieldName.isEmpty()) {
      throw new IllegalArgumentException("fieldName must be non-empty");
    }
    if (sourceParamIndex < NON_PARAM_SOURCE) {
      throw new IllegalArgumentException("sourceParamIndex must be >= -1, got " + sourceParamIndex);
    }
  }

  /**
   * Convenience: a field write from a non-parameter source (a local computation, a constant, etc.).
   */
  public static FieldOp fromLocalSource(String fieldName) {
    return new FieldOp(fieldName, NON_PARAM_SOURCE);
  }

  /** Convenience: a field write from a parameter. */
  public static FieldOp fromParam(String fieldName, int paramIndex) {
    if (paramIndex < 0) {
      throw new IllegalArgumentException("paramIndex must be >= 0, got " + paramIndex);
    }
    return new FieldOp(fieldName, paramIndex);
  }

  /**
   * @return {@code true} iff the source of this write is a parameter (sourceParamIndex >= 0).
   */
  public boolean hasParamSource() {
    return sourceParamIndex >= 0;
  }
}
