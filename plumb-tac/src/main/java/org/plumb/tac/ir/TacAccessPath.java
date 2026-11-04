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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A path from a root value to a location inside it.
 *
 * <p>An access path says <em>where</em> a value lives, as a chain of selectors from a root: {@code
 * root.field.subfield[0].name}. It is the vocabulary for tracking taint through containers, and the
 * point of having one is that fields, array elements and map entries are the same kind of thing.
 *
 * <h2>Why one abstraction instead of three</h2>
 *
 * <p>Before this, the engine tracked locations three ways: {@code FieldTaintTracker} keyed fields
 * by a {@code "receiver.fieldName"} string, {@code ArrayTaintTracker} keyed elements by a separate
 * {@code IndexKey} record, and a third ad-hoc map handled map entries. Three key shapes means three
 * places to get the aliasing rules wrong, three places to extend when a language needs a new
 * container, and no way to ask "is this path a prefix of that one?" -- which is the question a
 * field-sensitive taint check actually needs to ask.
 *
 * <p>With one path type, "does {@code a.b} reach {@code a.b.c}?" is a question about selector
 * chains, and it has one answer.
 *
 * <h2>Alias barriers</h2>
 *
 * <p>A path can be <b>cut</b> at a point: {@link #withAliasBarrier()} marks a location where the
 * engine can no longer prove that two references reach the same storage. After a barrier, a
 * path-sensitive match must not assume the rest of the path still holds.
 *
 * <p>This is the honest response to aliasing uncertainty. A tracker that keeps the full path
 * through an alias it cannot resolve will report a false positive on a field the write did not
 * actually reach; a tracker that cuts the path will miss a real one. Cutting is the safer of the
 * two, and the barrier makes the cut visible instead of implicit.
 *
 * <h2>Immutability</h2>
 *
 * <p>Paths are immutable. A path is a value, so it can be a map key without defensive copying, and
 * two equal paths are interchangeable.
 */
public final class TacAccessPath implements java.io.Serializable {

  private static final long serialVersionUID = 1L;

  /** One step of a path. */
  public sealed interface Selector
      permits Reference, Dereference, ArrayIndex, MapKey, MapKeySet, LazyMapKey {

    /** The rendering of this selector, used in the path's string form. */
    String render();
  }

  /**
   * A named field of the current object.
   *
   * <p>Java's {@code this.f}, Kotlin's {@code this.f}, Scala's {@code this.f}.
   */
  public record Reference(String fieldName) implements Selector {
    @Override
    public String render() {
      return "." + fieldName;
    }
  }

  /**
   * A dereference through a pointer, reference, or handle.
   *
   * <p>Java has no user-level dereference, but a value that <em>is</em> a reference (a {@code
   * Ref<T>} in another language, a boxed pointer in bytecode) has one. The selector is deliberately
   * unnamed: the point is that a dereference happened, not what it was called.
   */
  public record Dereference() implements Selector {
    @Override
    public String render() {
      return ".*";
    }
  }

  /**
   * An array element.
   *
   * <p>The index is the <em>textual</em> form of the index expression. A constant index keeps the
   * constant ({@code "0"}), and a symbolic index keeps the variable's name ({@code "i"}). Two loads
   * through the same symbolic index share state; a load through a different constant does not.
   */
  public record ArrayIndex(String index) implements Selector {
    @Override
    public String render() {
      return "[" + index + "]";
    }
  }

  /**
   * A map entry.
   *
   * <p>Keyed by the key's textual form. A constant key keeps the constant; a symbolic key keeps the
   * variable's name.
   */
  public record MapKey(String key) implements Selector {
    @Override
    public String render() {
      return "[" + key + "]";
    }
  }

  /**
   * Every entry of a map.
   *
   * <p>Written when the key is not statically known, or when the whole map is iterated. A path
   * ending in a {@code MapKeySet} matches any {@link MapKey} path beneath it.
   */
  public record MapKeySet() implements Selector {
    @Override
    public String render() {
      return "[*]";
    }
  }

  /**
   * A map entry whose key is computed lazily.
   *
   * <p>Distinct from {@link MapKeySet}: this one names <em>one</em> entry whose key is not known
   * yet, so a path through it is narrower than a path through the whole map.
   */
  public record LazyMapKey(String hint) implements Selector {
    @Override
    public String render() {
      return "[~" + hint + "]";
    }
  }

  /** The root of a path: the variable or temporary the path starts from. */
  private final String root;

  /** The selector chain, outermost first. */
  private final List<Selector> selectors;

  /**
   * Whether an alias barrier sits at the end of this path.
   *
   * <p>When true, the last selector's target may not be the same storage a later path reaches.
   */
  private final boolean aliasBarrier;

  private TacAccessPath(String root, List<Selector> selectors, boolean aliasBarrier) {
    this.root = root;
    this.selectors = Collections.unmodifiableList(new ArrayList<>(selectors));
    this.aliasBarrier = aliasBarrier;
  }

  /**
   * A path rooted at a variable, with no selectors.
   *
   * @param root the root variable's name
   */
  public static TacAccessPath root(String root) {
    return new TacAccessPath(Objects.requireNonNull(root, "root"), List.of(), false);
  }

  /** A path rooted at a variable, extended by one selector. */
  public TacAccessPath with(Selector selector) {
    List<Selector> next = new ArrayList<>(selectors);
    next.add(Objects.requireNonNull(selector, "selector"));
    return new TacAccessPath(root, next, false);
  }

  /** A path rooted at a variable, extended by a named field. */
  public TacAccessPath withField(String fieldName) {
    return with(new Reference(fieldName));
  }

  /** A path rooted at a variable, extended by an array element. */
  public TacAccessPath withArrayIndex(String index) {
    return with(new ArrayIndex(index));
  }

  /** A path rooted at a variable, extended by a map entry. */
  public TacAccessPath withMapKey(String key) {
    return with(new MapKey(key));
  }

  /**
   * This path, with an alias barrier at its end.
   *
   * <p>The barrier says: the engine can no longer prove that the next step reaches the same
   * storage. A path-sensitive match must not assume it does.
   */
  public TacAccessPath withAliasBarrier() {
    return new TacAccessPath(root, selectors, true);
  }

  /** The root variable's name. */
  public String root() {
    return root;
  }

  /** The selector chain, outermost first. */
  public List<Selector> selectors() {
    return selectors;
  }

  /** How many selectors this path has. */
  public int depth() {
    return selectors.size();
  }

  /** Whether this path ends at an alias barrier. */
  public boolean hasAliasBarrier() {
    return aliasBarrier;
  }

  /**
   * Whether this path is a prefix of another.
   *
   * <p>The question a field-sensitive taint check needs: "does the write to {@code a.b} reach the
   * read of {@code a.b.c}?" A path is a prefix of itself.
   *
   * <p>The comparison is selector-by-selector. A {@link MapKeySet} selector matches any {@link
   * MapKey} or {@link LazyMapKey} at the same position, because "some key" contains "this key".
   */
  public boolean isPrefixOf(TacAccessPath other) {
    if (other == null) return false;
    if (!root.equals(other.root)) return false;
    if (selectors.size() > other.selectors.size()) return false;
    for (int i = 0; i < selectors.size(); i++) {
      if (!selectorMatches(selectors.get(i), other.selectors.get(i))) return false;
    }
    return true;
  }

  /**
   * Whether this path and another reach the same location.
   *
   * <p>Two paths are the same location when they have the same root and the same selectors.
   */
  public boolean sameLocation(TacAccessPath other) {
    if (other == null) return false;
    return root.equals(other.root) && selectors.equals(other.selectors);
  }

  private static boolean selectorMatches(Selector mine, Selector theirs) {
    if (mine.equals(theirs)) return true;
    // "some key" contains "this key".
    if (mine instanceof MapKeySet) {
      return theirs instanceof MapKey || theirs instanceof LazyMapKey;
    }
    return false;
  }

  /**
   * The path with the first selector removed.
   *
   * <p>Used to compare a write path against a read path when the write is to a container and the
   * read is of one of its elements.
   */
  public TacAccessPath chopHead() {
    if (selectors.isEmpty()) return this;
    return new TacAccessPath(root, selectors.subList(1, selectors.size()), false);
  }

  /**
   * The path with the last selector removed.
   *
   * <p>The inverse of {@link #with(Selector)}: the location that contains this one.
   */
  public TacAccessPath chopTail() {
    if (selectors.isEmpty()) return this;
    return new TacAccessPath(root, selectors.subList(0, selectors.size() - 1), false);
  }

  /**
   * The path without any dereference selectors.
   *
   * <p>A dereference is a step through a reference; stripping them gives the path to the referenced
   * object, which is what an alias question is usually about.
   */
  public TacAccessPath stripIndirection() {
    List<Selector> kept = new ArrayList<>();
    for (Selector s : selectors) {
      if (!(s instanceof Dereference)) kept.add(s);
    }
    return new TacAccessPath(root, kept, aliasBarrier);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TacAccessPath other)) return false;
    return root.equals(other.root)
        && selectors.equals(other.selectors)
        && aliasBarrier == other.aliasBarrier;
  }

  @Override
  public int hashCode() {
    return Objects.hash(root, selectors, aliasBarrier);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder(root);
    for (Selector s : selectors) sb.append(s.render());
    if (aliasBarrier) sb.append("|alias-barrier");
    return sb.toString();
  }
}
