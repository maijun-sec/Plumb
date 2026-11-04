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
package org.plumb.dfa.config;

import java.util.BitSet;
import java.util.Collection;

/**
 * A set of fact kinds, stored as a bit set over the kinds' interned indices.
 *
 * <p><b>Why this exists.</b> The engine asks "which kinds are present?" and "does this kind count?"
 * in the hot path of taint propagation -- every assignment, every summary merge, every guard
 * update. Answering with a {@code Set<FactKind>} means hashing and comparing strings, over and
 * over, on a set whose members are known to be few.
 *
 * <p>With interned indices the same question is a bit test.
 *
 * <h2>The unregistered kind</h2>
 *
 * <p>A kind that has not been registered has no index. It cannot be placed in the bit set, so it is
 * tracked separately in a single flag. That is enough: the engine has one notion of "a kind nobody
 * registered", and it is never more than one at a time in a given set.
 *
 * <p><b>That flag is not a kind.</b> It says "an unregistered kind is present", not "a particular
 * unregistered kind is present". A caller that needs to know <em>which</em> one must ask the
 * registry, not the set.
 */
public final class FactKindSet {

  private final BitSet bits;
  private boolean hasUnregistered;

  /** An empty set. */
  public FactKindSet() {
    this.bits = new BitSet();
  }

  /**
   * A set holding one kind.
   *
   * @param kind the kind to add
   * @param registry the registry that holds the kind's index
   */
  public static FactKindSet of(FactKind kind, FactKindRegistry registry) {
    FactKindSet s = new FactKindSet();
    s.add(kind, registry);
    return s;
  }

  /**
   * Adds a kind.
   *
   * @param kind the kind to add
   * @param registry the registry that holds the kind's index
   */
  public void add(FactKind kind, FactKindRegistry registry) {
    if (kind == null) return;
    int idx = registry == null ? FactKind.UNASSIGNED : registry.indexOf(kind);
    if (idx == FactKind.UNASSIGNED) {
      hasUnregistered = true;
      return;
    }
    bits.set(idx);
  }

  /**
   * Removes a kind.
   *
   * @param kind the kind to remove
   * @param registry the registry that holds the kind's index
   */
  public void remove(FactKind kind, FactKindRegistry registry) {
    if (kind == null) return;
    int idx = registry == null ? FactKind.UNASSIGNED : registry.indexOf(kind);
    if (idx == FactKind.UNASSIGNED) {
      hasUnregistered = false;
      return;
    }
    bits.clear(idx);
  }

  /**
   * Whether the set contains a kind.
   *
   * @param kind the kind to look for
   * @param registry the registry that holds the kind's index
   */
  public boolean contains(FactKind kind, FactKindRegistry registry) {
    if (kind == null) return false;
    int idx = registry == null ? FactKind.UNASSIGNED : registry.indexOf(kind);
    if (idx == FactKind.UNASSIGNED) {
      return hasUnregistered;
    }
    return bits.get(idx);
  }

  /** Whether the set is empty. */
  public boolean isEmpty() {
    return bits.isEmpty() && !hasUnregistered;
  }

  /** How many kinds are in the set. */
  public int size() {
    return bits.cardinality() + (hasUnregistered ? 1 : 0);
  }

  /**
   * Adds every kind in a collection.
   *
   * @param kinds the kinds to add
   * @param registry the registry that holds the kinds' indices
   */
  public void addAll(Collection<FactKind> kinds, FactKindRegistry registry) {
    if (kinds == null) return;
    for (FactKind k : kinds) add(k, registry);
  }

  /** Removes everything. */
  public void clear() {
    bits.clear();
    hasUnregistered = false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FactKindSet other)) return false;
    return hasUnregistered == other.hasUnregistered && bits.equals(other.bits);
  }

  @Override
  public int hashCode() {
    return bits.hashCode() * 31 + (hasUnregistered ? 1 : 0);
  }

  @Override
  public String toString() {
    return "FactKindSet{size=" + size() + ", unregistered=" + hasUnregistered + "}";
  }
}
