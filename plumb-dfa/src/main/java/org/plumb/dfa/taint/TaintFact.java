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

/**
 * One taint fact: WHAT the value is, and WHERE it came from.
 *
 * <p><b>Why the fact is one value and not two.</b> The engine used to keep the label in one map
 * ({@code taintedVars}) and the matching flow path in another ({@code taintPaths}). A finding names
 * the label and shows the path, so the two have to agree -- and nothing made them agree. They were
 * two halves of one fact, stored apart, and every writer had to remember to write both. The label
 * and the domain are the same kind of thing: two properties of one fact. They belong in one value.
 *
 * <p><b>What the domain is.</b> {@link TaintDomain} says what KIND of value the tainted thing is: a
 * string, a number, a handle. The sink already knew what it wanted -- every taint rule declares its
 * own domain -- but the two halves were never compared, so the comparison was a self-comparison
 * that could not fail. With the domain on the fact, the comparison is real: a tainted NUMBER that
 * reaches a SQL sink is not SQL injection.
 *
 * <p><b>Where the domain comes from, and why it is derived rather than propagated.</b> The domain
 * is a property of the VALUE, and the value's kind is what the DESTINATION variable's declared type
 * says it is. So the domain is derived from the destination's type at the moment the fact is
 * written, rather than carried along from whatever produced the value. That is deliberate:
 *
 * <ul>
 *   <li>A value that flows into a variable of another kind changes kind on the way, and deriving
 *       the domain from the destination gets that right for free -- {@code String s = taintedInt;}
 *       makes {@code s} a string, so the fact says string.
 *   <li>A domain carried along a propagation chain has to be updated at every step, and every step
 *       that forgets is a wrong fact. Deriving it means there is no chain to keep in step.
 *   <li>When the destination's type is not known, the domain is {@link TaintDomain.MixedTaint},
 *       which flows into every sink. Unknown never suppresses.
 * </ul>
 *
 * <p><b>What this cannot claim.</b> The domain is a coarse kind, not a type lattice. A tainted
 * {@code Object} that holds a string is {@link TaintDomain.MixedTaint}, so it still reaches every
 * sink. That is the right direction: the domain removes a mismatch the engine can prove, and never
 * removes one it cannot.
 *
 * @param label the label a finding names -- where the value came from, e.g. {@code
 *     "input:java.io.BufferedReader.readLine/0"}
 * @param domain what KIND of value the tainted thing is
 */
public record TaintFact(String label, TaintDomain domain) {

  public TaintFact {
    java.util.Objects.requireNonNull(label, "label");
    java.util.Objects.requireNonNull(domain, "domain");
  }

  /**
   * A fact whose kind is not known -- it flows into every sink.
   *
   * <p>Use this only where the value's kind genuinely cannot be established. Everywhere else the
   * caller knows the destination variable, and {@code TaintedCheckerBase.factFor} derives the kind
   * from it.
   */
  public static TaintFact of(String label) {
    return new TaintFact(label, TaintDomain.MixedTaint.INSTANCE);
  }

  /**
   * A fact with an explicit kind.
   *
   * @param label the label a finding names
   * @param domain what kind of value the tainted thing is
   */
  public static TaintFact of(String label, TaintDomain domain) {
    return new TaintFact(label, domain);
  }

  /**
   * Join two facts into one, keeping the caller's label and widening the kind.
   *
   * <p>The kind widens the way {@link TaintDomain#join} widens: two facts of the same kind keep it,
   * two facts of different kinds become {@link TaintDomain.MixedTaint} -- "couldn't decide", which
   * flows into every sink. The label is the caller's, because only the caller knows which order and
   * which wording the finding should show.
   *
   * @param label the label the joined fact carries
   * @param a the first fact, or {@code null} when only one side carries taint
   * @param b the second fact, or {@code null} when only one side carries taint
   * @return the joined fact
   */
  public static TaintFact joined(String label, TaintFact a, TaintFact b) {
    TaintDomain domain =
        (a == null || b == null)
            ? (a != null ? a.domain() : b.domain())
            : TaintDomain.join(a.domain(), b.domain());
    return new TaintFact(label, domain);
  }

  /**
   * Whether this fact's kind can reach a sink that wants {@code expected}.
   *
   * <p>The same relation {@link TaintDomain#flowsInto} describes, spelled out for the fact so a
   * reader does not have to know that the fact's domain and the rule's domain are the same kind of
   * thing.
   *
   * @param expected the kind the sink consumes
   * @return true when the value could be what the sink consumes
   */
  public boolean flowsInto(TaintDomain expected) {
    return domain.flowsInto(expected);
  }
}
