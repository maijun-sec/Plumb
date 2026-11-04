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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A fact kind's identity must be an integer, and it must survive re-registration.
 *
 * <p><b>What this pins.</b> Facts are copied constantly -- on every assignment, every summary
 * merge, every guard update -- and each copy involves a map operation keyed by the fact's kind.
 * With the kind keyed by a {@code String}, that is a string hash and comparison, repeated millions
 * of times on a large scan.
 *
 * <p>Interning the kind as an integer index makes those operations integer-keyed. But interning
 * only helps if the index is <em>stable</em>: an index that changes when a kind is re-registered
 * would silently break every integer-keyed structure holding it.
 */
class FactKindInterningTest {

  private static FactKind kind(String name) {
    return new FactKind(name, "desc", null, Set.of());
  }

  /** Every registered kind must have an index. */
  @Test
  @DisplayName("a registered kind has an index")
  void registeredKindsHaveAnIndex() {
    FactKindRegistry registry = new FactKindRegistry(java.util.Map.of("a", kind("a")));

    FactKind a = registry.byName("a").orElseThrow();
    assertThat(a.isRegistered()).isTrue();
    assertThat(a.index()).isNotEqualTo(FactKind.UNASSIGNED);
  }

  /** An unregistered kind must not be mistaken for kind zero. */
  @Test
  @DisplayName("an unregistered kind has no index")
  void unregisteredKindsHaveNoIndex() {
    FactKind loose = kind("loose");

    assertThat(loose.isRegistered()).isFalse();
    assertThat(loose.index()).isEqualTo(FactKind.UNASSIGNED);
  }

  /** Different kinds must have different indices. */
  @Test
  @DisplayName("different kinds have different indices")
  void indicesAreDistinct() {
    FactKindRegistry registry = new FactKindRegistry(java.util.Map.of());
    FactKind a = registry.register(kind("a"));
    FactKind b = registry.register(kind("b"));

    assertThat(a.index()).isNotEqualTo(b.index());
  }

  /**
   * Re-registering a name must keep the index.
   *
   * <p>This is the load-bearing property. A kind that is replaced -- say, a YAML override of a
   * built-in -- has a different object identity but the same name. If it got a new index, every
   * integer-keyed structure holding the old index would silently stop matching it.
   */
  @Test
  @DisplayName("re-registering a name keeps the index")
  void reRegistrationKeepsTheIndex() {
    FactKindRegistry registry = new FactKindRegistry(java.util.Map.of());
    FactKind first = registry.register(kind("a"));
    int firstIndex = first.index();

    FactKind second = registry.register(kind("a"));

    assertThat(second.index())
        .as("a replaced kind must keep the index its predecessors used")
        .isEqualTo(firstIndex);
  }

  /** An index must resolve back to its kind. */
  @Test
  @DisplayName("an index resolves back to its kind")
  void indexResolvesToKind() {
    FactKindRegistry registry = new FactKindRegistry(java.util.Map.of());
    FactKind a = registry.register(kind("a"));

    assertThat(registry.byIndex(a.index())).isSameAs(a);
    assertThat(registry.byIndex(-1)).isNull();
    assertThat(registry.byIndex(9999)).isNull();
  }

  /**
   * Two kinds with the same name must be recognised as the same kind.
   *
   * <p>This is the correctness property the interning has to preserve. {@code FactKind} does not
   * override {@code equals}, so an identity comparison would fail here -- and a fact loaded from
   * YAML would not match a kind the checker looked up in code.
   */
  @Test
  @DisplayName("two kinds with the same name are the same kind")
  void sameNameIsSameKind() {
    FactKindRegistry registry = new FactKindRegistry(java.util.Map.of());
    FactKind registered = registry.register(kind("Shared"));
    FactKind separate = kind("Shared");

    org.plumb.dfa.fact.Fact fact =
        new org.plumb.dfa.fact.Fact(
            registered, new org.plumb.tac.ir.TacOperand.TacVar("v", null, true), "x");

    assertThat(fact.hasKind(separate))
        .as("a kind built elsewhere with the same name must still match")
        .isTrue();
    assertThat(fact.hasKind(kind("Other"))).isFalse();
  }

  /** The bit-set form must answer the same questions the set of kinds does. */
  @Test
  @DisplayName("the bit-set form answers membership correctly")
  void theBitSetFormWorks() {
    FactKindRegistry registry = new FactKindRegistry(java.util.Map.of());
    FactKind a = registry.register(kind("a"));
    FactKind b = registry.register(kind("b"));

    FactKindSet set = FactKindSet.of(a, registry);
    assertThat(set.contains(a, registry)).isTrue();
    assertThat(set.contains(b, registry)).isFalse();
    assertThat(set.size()).isEqualTo(1);

    set.add(b, registry);
    assertThat(set.contains(b, registry)).isTrue();
    assertThat(set.size()).isEqualTo(2);

    set.remove(a, registry);
    assertThat(set.contains(a, registry)).isFalse();
    assertThat(set.size()).isEqualTo(1);
  }

  /**
   * An unregistered kind must still be representable.
   *
   * <p>It has no index, so it cannot go in the bit set -- but it must not be silently dropped,
   * because a dropped kind is a dropped fact.
   */
  @Test
  @DisplayName("an unregistered kind is not silently dropped")
  void unregisteredKindsAreNotDropped() {
    FactKindRegistry registry = new FactKindRegistry(java.util.Map.of());
    FactKind loose = kind("loose");

    FactKindSet set = new FactKindSet();
    set.add(loose, registry);

    assertThat(set.contains(loose, registry))
        .as("an unregistered kind must still be reported as present")
        .isTrue();
    assertThat(set.size()).isEqualTo(1);
  }
}
