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

import java.util.Objects;

/**
 * A function (or method) in the {@link CallGraph}.
 *
 * <p>Identified by an opaque signature string. For Java methods, the signature is the FQCN + name +
 * parameter types, e.g. {@code "com.example.Foo#bar(Ljava/lang/String;)V"}. For unresolved calls
 * (e.g. indirect dispatch), the signature may be a placeholder like {@code "<indirect:foo>"}.
 *
 * <p>Equality is based on signature string. Two {@code FunctionNode}s with the same signature are
 * considered the same function (this allows deduplication when the same method is loaded from
 * multiple class files).
 */
public final class FunctionNode {

  private final String signature;

  public FunctionNode(String signature) {
    this.signature = Objects.requireNonNull(signature, "signature");
  }

  public String signature() {
    return signature;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof FunctionNode other)) return false;
    return signature.equals(other.signature);
  }

  @Override
  public int hashCode() {
    return signature.hashCode();
  }

  @Override
  public String toString() {
    return "FunctionNode(" + signature + ")";
  }
}
