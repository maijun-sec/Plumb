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
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * CWE-502: deserialization of untrusted data.
 *
 * <h2>Why this checker exists</h2>
 *
 * <p>The {@code deserialization.sinks} family has been present in {@code sink-families.yaml} with
 * {@code severity: CRITICAL} for some time, but <em>no checker consumed it</em>. A registered rule
 * with no implementation is worse than an absent one: the coverage report claims CWE-502 is handled
 * while nothing can ever report it.
 *
 * <h2>The receiver is the sink argument</h2>
 *
 * <p>{@code ObjectInputStream#readObject()} takes no arguments — the untrusted bytes arrive on the
 * stream itself. That is expressed with {@link org.plumb.dfa.config.SinkFamily#RECEIVER_ARG_INDEX}
 * ({@code arg_index: -2}) in the YAML, which makes the checker inspect the call's receiver rather
 * than a user argument. Sinks that DO take the untrusted data as an argument (Jackson {@code
 * readValue}, SnakeYAML {@code load}, Kryo {@code readObject(InputStream)}) keep the family
 * default.
 *
 * <p>The motivating real-world cases are Apache Camel MINA and Log4j-style gadget chains, where a
 * network-supplied stream is handed straight to {@code readObject()}.
 */
public final class UnsafeDeserializationChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Deserialization of untrusted data (CWE-502).
      This rule fires when attacker-controlled data reaches a
      deserialization entry point: ObjectInputStream#readObject /
      readUnshared, Jackson ObjectMapper#readValue, SnakeYAML Yaml#load,
      XStream#fromXML, or Kryo#readObject.

      A finding is a TRUE POSITIVE (TP) when the serialized
      payload genuinely originates outside the trust boundary,
      e.g. an HTTP request body, a socket, a message-queue
      payload, a file uploaded by a user, or a cache shared
      with an untrusted writer.

      A finding is a FALSE POSITIVE (FP) when the bytes are
      provably trusted:
        * Deserialising a stream the program itself just wrote
          (writeObject followed by readObject on a local buffer)
        * Deserialising a constant bundled resource
        * A typed, allow-listed deserialisation where the class
          is resolved from a fixed whitelist and never from
          the payload (ObjectInputFilter / resolveClass)
        * Kryo with an explicit, fixed registration list

      Pay particular attention to the receiver case: for
      ObjectInputStream#readObject() the reported taint is on
      the STREAM object, because that is where the untrusted
      bytes live. A finding whose source is a constant byte
      array or a resource the program loaded is an FP.

      A finding is UNKNOWN when you cannot determine the
      origin of the bytes from the source context + dataflow
      trace alone. Prefer UNKNOWN over guessing.
      """;

  public UnsafeDeserializationChecker() {
    super(
        new TaintRule(
            "dfa-taint-unsafe-deserialize",
            "dfa-taint-untrusted-source",
            "deserialization.sinks",
            "CWE-502",
            "CRITICAL",
            TaintDomain.MixedTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
