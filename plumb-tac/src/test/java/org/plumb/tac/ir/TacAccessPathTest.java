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
package org.plumb.tac.ir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An access path must answer the question a field-sensitive taint check actually asks.
 *
 * <p><b>What went wrong.</b> The engine tracked locations three ways: fields by a {@code
 * "receiver.field"} string, array elements by a separate index-key record, and map entries by a
 * third ad-hoc map. Three key shapes means three places to get the aliasing rules wrong, and --
 * more importantly -- <em>no way to ask whether one location reaches another</em>. That question
 * ("does the write to {@code a.b} reach the read of {@code a.b.c}?") is the whole point of
 * path-sensitive tracking.
 */
class TacAccessPathTest {

  /**
   * A path is a prefix of a longer path when the longer one continues from it.
   *
   * <p>This is the reachability question. A write to {@code a.b} reaches a read of {@code a.b.c}.
   */
  @Test
  @DisplayName("a path is a prefix of a path that continues from it")
  void prefixRelation() {
    TacAccessPath ab = TacAccessPath.root("a").withField("b");
    TacAccessPath abc = TacAccessPath.root("a").withField("b").withField("c");

    assertThat(ab.isPrefixOf(abc)).isTrue();
    assertThat(abc.isPrefixOf(ab)).isFalse();
    assertThat(ab.isPrefixOf(ab)).as("a path is a prefix of itself").isTrue();
  }

  /** Two paths with different roots are never related, however similar they look. */
  @Test
  @DisplayName("paths with different roots are unrelated")
  void differentRootsAreUnrelated() {
    TacAccessPath a = TacAccessPath.root("a").withField("b");
    TacAccessPath other = TacAccessPath.root("x").withField("b");

    assertThat(a.isPrefixOf(other)).isFalse();
    assertThat(a.sameLocation(other)).isFalse();
  }

  /** An array element is one level below the array. */
  @Test
  @DisplayName("an array element is below the array")
  void arrayElementIsBelowTheArray() {
    TacAccessPath arr = TacAccessPath.root("a");
    TacAccessPath elem = TacAccessPath.root("a").withArrayIndex("0");

    assertThat(arr.isPrefixOf(elem)).isTrue();
    assertThat(elem.isPrefixOf(arr)).isFalse();
  }

  /**
   * "Some key" contains "this key".
   *
   * <p>A map iterated with an unknown key reaches any specific key. That is the relation a
   * map-entry rule needs.
   */
  @Test
  @DisplayName("a whole-map path contains any specific key")
  void wholeMapContainsSpecificKey() {
    TacAccessPath whole = TacAccessPath.root("m").with(new TacAccessPath.MapKeySet());
    TacAccessPath one = TacAccessPath.root("m").withMapKey("k1");

    assertThat(whole.isPrefixOf(one)).isTrue();
    assertThat(one.isPrefixOf(whole)).isFalse();
  }

  /**
   * An alias barrier cuts the path.
   *
   * <p>After a barrier the engine can no longer prove that the next step reaches the same storage,
   * so a path-sensitive match must not assume it does. The barrier is visible in the path's own
   * text, so a dump shows where the certainty stopped.
   */
  @Test
  @DisplayName("an alias barrier is recorded and visible")
  void aliasBarrierIsVisible() {
    TacAccessPath plain = TacAccessPath.root("a").withField("b");
    TacAccessPath barred = plain.withAliasBarrier();

    assertThat(barred.hasAliasBarrier()).isTrue();
    assertThat(plain.hasAliasBarrier()).isFalse();
    assertThat(barred.toString()).contains("alias-barrier");
  }

  /** Chopping the head and the tail are inverses of building the path. */
  @Test
  @DisplayName("chopping the head and the tail undo building the path")
  void chopIsInverseOfBuild() {
    TacAccessPath path = TacAccessPath.root("a").withField("b").withArrayIndex("0");

    // chopHead removes the outermost selector: a.b[0] -> a[0].
    assertThat(path.chopHead().toString()).isEqualTo("a[0]");
    // chopTail removes the innermost selector: a.b[0] -> a.b.
    assertThat(path.chopTail().toString()).isEqualTo("a.b");
    assertThat(path.chopHead().chopTail().toString()).isEqualTo("a");
    // Chopping a root path is a no-op, not an error.
    TacAccessPath root = TacAccessPath.root("a");
    assertThat(root.chopHead()).isEqualTo(root);
    assertThat(root.chopTail()).isEqualTo(root);
  }

  /** Stripping indirections gives the path to the referenced object. */
  @Test
  @DisplayName("indirection can be stripped")
  void stripIndirection() {
    TacAccessPath path =
        TacAccessPath.root("a").with(new TacAccessPath.Dereference()).withField("b");

    assertThat(path.depth()).isEqualTo(2);
    assertThat(path.stripIndirection().toString()).isEqualTo("a.b");
    assertThat(path.stripIndirection().depth()).isEqualTo(1);
  }

  /** Equal paths are interchangeable as map keys. */
  @Test
  @DisplayName("equal paths are equal")
  void equality() {
    TacAccessPath one = TacAccessPath.root("a").withField("b").withArrayIndex("0");
    TacAccessPath two = TacAccessPath.root("a").withField("b").withArrayIndex("0");
    TacAccessPath three = TacAccessPath.root("a").withField("b").withArrayIndex("1");

    assertThat(one).isEqualTo(two);
    assertThat(one.hashCode()).isEqualTo(two.hashCode());
    assertThat(one).isNotEqualTo(three);
  }

  /**
   * A path must be immutable.
   *
   * <p>It is a value used as a map key, so a mutation after insertion would corrupt the map.
   */
  @Test
  @DisplayName("a path is immutable")
  void pathsAreImmutable() {
    TacAccessPath base = TacAccessPath.root("a");
    TacAccessPath extended = base.withField("b");

    assertThat(base.depth()).as("the base path must not change").isEqualTo(0);
    assertThat(extended.depth()).isEqualTo(1);
  }
}
