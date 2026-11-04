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
package org.plumb.dfa.taint;

import java.util.Locale;

/**
 * The catalogue of mutating calls whose receiver accumulates the taint of their arguments, and of
 * the calls that read such an accumulated state back out.
 *
 * <p><b>Why this exists.</b> A taint analysis that only tracks "value flows into result" misses the
 * classic accumulator shape:
 *
 * <pre>
 *   StringBuilder sb = new StringBuilder();
 *   sb.append(userInput);          // the tainted value is now INSIDE sb
 *   Runtime.getRuntime().exec(sb.toString());
 * </pre>
 *
 * <p>The taint does not travel from {@code userInput} to {@code sb.toString()} along an expression
 * edge: it travels through the <em>state</em> of {@code sb}. {@code append} returns the builder
 * itself, so the call result carries the taint, but the builder object stays clean, and the later
 * {@code toString()} sees a clean receiver and produces a clean result. The analysis then reports a
 * clean program for code that hands attacker input to the operating system.
 *
 * <p>The shape is general, not StringBuilder-specific: {@code list.add(x)}, {@code map.put(k, x)},
 * {@code out.write(x)} and {@code writer.append(x)} all accumulate their argument into the
 * receiver's state, and every later read of that state carries the taint.
 *
 * <p>Two directions are therefore modelled:
 *
 * <ul>
 *   <li><b>mutator</b> -- a call that writes a tainted argument into the receiver's state taints
 *       the receiver. The receiver is then tainted for the rest of the function.
 *   <li><b>accumulator read</b> -- a call that returns the receiver's accumulated state propagates
 *       the receiver's taint to the result.
 * </ul>
 *
 * <p>Both directions are deliberately conservative in the same direction: they can only ever
 * <em>add</em> taint. A mutator never clears the receiver's taint, and an accumulator read never
 * removes it. That keeps the model free of false negatives without inventing false positives from a
 * clean write.
 *
 * <p><b>Scope.</b> The catalogue is keyed by the fully-qualified callee signature in the TAC's
 * {@code owner.method/arity} form. The receiver of an instance call is {@code args[0]}; the
 * arguments follow at {@code args[1]}.
 */
public final class AccumulatorCatalog {

  private AccumulatorCatalog() {}

  /**
   * Is this call one that writes its arguments into the receiver's own state?
   *
   * <p>The match is on the receiver's declaring type plus the method name, because {@code append}
   * means different things on different types: on a {@code StringBuilder} it mutates, on an
   * immutable {@code String} it returns a new value. Matching the type keeps the two apart.
   *
   * @param calleeSig the callee signature in {@code owner.method/arity} form
   * @return true when a tainted argument taints the receiver
   */
  public static boolean isMutator(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return false;
    String sig = calleeSig.toLowerCase(Locale.ROOT);
    // StringBuilder / StringBuffer: the canonical accumulator.
    if (sig.contains(".stringbuilder.") || sig.contains(".stringbuffer.")) {
      return sig.contains(".append/")
          || sig.contains(".insert/")
          || sig.contains(".appendcodePoint/".toLowerCase(Locale.ROOT))
          || sig.contains(".replace/")
          || sig.contains(".reverse/")
          || sig.contains(".setCharAt/");
    }
    // Collections and maps: an element added to a container is part of the container's state.
    if (sig.contains(".java.util.list.") || sig.contains(".java.util.set.")) {
      return sig.contains(".add/") || sig.contains(".addAll/") || sig.contains(".set/");
    }
    if (sig.contains(".java.util.map.")) {
      return sig.contains(".put/") || sig.contains(".putAll/");
    }
    // Character / byte streams and writers: written bytes are part of the stream's state.
    if (sig.contains(".java.io.outputstream.")
        || sig.contains(".java.io.writer.")
        || sig.contains(".java.io.printwriter.")
        || sig.contains(".java.io.stringwriter.")
        || sig.contains(".java.io.bufferedwriter.")
        || sig.contains(".java.io.filteroutputstream.")
        || sig.contains(".java.io.objectoutputstream.")) {
      return sig.contains(".write/")
          || sig.contains(".writebytes/")
          || sig.contains(".print/")
          || sig.contains(".println/")
          || sig.contains(".append/")
          || sig.contains(".writeint/");
    }
    return false;
  }

  /**
   * Is this call one that returns the receiver's accumulated state?
   *
   * <p>The counterpart of {@link #isMutator(String)}: the value it returns is derived from what was
   * written into the receiver earlier.
   *
   * @param calleeSig the callee signature in {@code owner.method/arity} form
   * @return true when the result carries the receiver's accumulated taint
   */
  public static boolean isAccumulatorRead(String calleeSig) {
    if (calleeSig == null || calleeSig.isEmpty()) return false;
    String sig = calleeSig.toLowerCase(Locale.ROOT);
    if (sig.contains(".stringbuilder.") || sig.contains(".stringbuffer.")) {
      return sig.contains(".tostring/");
    }
    if (sig.contains(".java.io.stringwriter.")) {
      return sig.contains(".tostring/") || sig.contains(".getbuffer/");
    }
    if (sig.contains(".java.io.bytearrayoutputstream.")
        || sig.contains(".java.io.chararraywriter.")
        || sig.contains(".java.io.stringwriter.")) {
      return sig.contains(".tostring/");
    }
    return false;
  }

  /**
   * The argument indices of a mutator call whose taint flows into the receiver.
   *
   * <p>The receiver itself is {@code args[0]} on an instance call; the value arguments follow. The
   * receiver is deliberately NOT included: a builder that already holds tainted content stays
   * tainted on its own, and listing the receiver would turn every append into a self-referential
   * label.
   *
   * @param ins the CALL instruction
   * @return the argument indices to inspect
   */
  public static java.util.List<Integer> mutatorArgIndices(org.plumb.tac.ir.TacInstruction ins) {
    java.util.List<Integer> out = new java.util.ArrayList<>();
    if (ins == null || ins.args == null) return out;
    for (int i = 1; i < ins.args.size(); i++) {
      out.add(i);
    }
    return out;
  }
}
