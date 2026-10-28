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
/*
 * Uncontrolled memory allocation checker
 * (CWE-789). Tainted NUMERIC data flowing into
 * the size arg of a heap-allocation site
 * (new HashMap(int) / new Vector(int) /
 * new byte[size] via ARRAYLEN, etc.) so an
 * attacker can OOM the JVM.
 *
 * <p>The source kind is
 * {@code plumb.numeric.overflow-hazard} (the
 * IntOverflowChecker numerical taint
 * propagation path), so tainted values
 * survive {@code Integer.parseInt(readLine())}
 * to reach the ctor. The domain is
 * NumericTaint, not StringTaint -- integer
 * allocation sizes are numeric, not string.
 *
 * <p>Sink family: mem-alloc.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacInstruction;

public final class UncontrolledMemAllocChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: Uncontrolled memory allocation
      (CWE-789). This rule fires when a
      tainted numeric value flows into a
      heap-allocation site
      (new byte[tainted], new HashMap(int),
      new StringBuffer(tainted),
      ByteArrayOutputStream constructor with
      tainted initial size, etc.) so an
      attacker can OOM the JVM.

      A finding is a TRUE POSITIVE (TP) when
      the tainted value is not bounded before
      the allocation.

      A finding is a FALSE POSITIVE (FP) when
      the value is bounded (Math.min, parsed
      to a small int via Integer.parseInt with
      a known-format string, etc.) or comes
      from a constant config.

      A finding is UNKNOWN when you cannot
      tell from source + dataflow alone.
      """;

  public UncontrolledMemAllocChecker() {
    super(
        new TaintRule(
            "dfa-taint-uncontrolled-mem-alloc",
            // Share the IntOverflow numeric taint
            // path so parseInt(readLine()) →
            // numeric taint reaches the ctor.
            "plumb.numeric.overflow-hazard",
            "mem-alloc.sinks",
            "CWE-789",
            "HIGH",
            TaintDomain.NumericTaint.INSTANCE));
  }

  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    // For new byte[intSize] the size is at
    // arg 0; for ByteArrayOutputStream(int)
    // constructor likewise. For String(byte[],
    // int, int) the bytes are at 0 (tainted),
    // for the StringBuffer(int) constructor
    // the size is at 0. We default to 0 which
    // matches most patterns; subclass with
    // arity-2 ctors would override.
    return 0;
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
