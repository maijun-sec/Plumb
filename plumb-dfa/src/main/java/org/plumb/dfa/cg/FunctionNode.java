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
