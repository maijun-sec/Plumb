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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of {@link FactKind}s, indexed by kind name. Thread-safe; supports both YAML-loaded
 * defaults and runtime registration / deregistration.
 */
public final class FactKindRegistry {

  private final Map<String, FactKind> byName;

  /**
   * The interned index assigned to each kind.
   *
   * <p>The index is what makes a fact copy cheap: the fact's kind is compared as an integer rather
   * than as a string. See {@link FactKind#index()} for why.
   */
  private final Map<String, Integer> indexByName = new LinkedHashMap<>();

  /** The next index to hand out. */
  private int nextIndex = 0;

  public FactKindRegistry(Map<String, FactKind> byName) {
    this.byName = new LinkedHashMap<>(byName);
    // Assign indices to the kinds that were present at construction. The order is the map's
    // order, so the indices are deterministic for a given configuration -- which matters
    // because the IR is cached to disk and a later run must agree with an earlier one.
    for (FactKind k : this.byName.values()) {
      assignIndex(k);
    }
  }

  public static FactKindRegistry empty() {
    return new FactKindRegistry(Map.of());
  }

  public Optional<FactKind> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /**
   * Registers a kind, assigning it an interned index.
   *
   * <p>Re-registering a name keeps the existing index. A kind that replaces another with the same
   * name must not get a new index: the index is the kind's identity for every integer-keyed
   * structure in the engine, and changing it would silently break every one of them.
   *
   * @param k the kind to register
   * @return the registered kind -- the one now in the registry under this name
   */
  public synchronized FactKind register(FactKind k) {
    Objects.requireNonNull(k, "k");
    Integer existing = indexByName.get(k.name());
    if (existing != null) {
      k.assignIndex(existing);
    } else {
      assignIndex(k);
    }
    byName.put(k.name(), k);
    // Return the kind that is NOW registered, not the one that was there before.
    // Returning the previous value would make the first registration return null,
    // and a caller that uses the return value would get a null kind.
    return k;
  }

  private void assignIndex(FactKind k) {
    Integer existing = indexByName.get(k.name());
    if (existing != null) {
      k.assignIndex(existing);
      return;
    }
    int idx = nextIndex++;
    k.assignIndex(idx);
    indexByName.put(k.name(), idx);
  }

  /** The interned index of a kind name, or {@link FactKind#UNASSIGNED} if the name is unknown. */
  public synchronized int indexOf(String name) {
    Integer idx = indexByName.get(name);
    return idx == null ? FactKind.UNASSIGNED : idx;
  }

  /**
   * The interned index of a kind.
   *
   * <p>A kind that was never registered has no index, and gets {@link FactKind#UNASSIGNED}.
   */
  public synchronized int indexOf(FactKind kind) {
    if (kind == null) return FactKind.UNASSIGNED;
    if (kind.isRegistered()) return kind.index();
    return indexOf(kind.name());
  }

  /**
   * The kind with a given interned index.
   *
   * @param index the index
   * @return the kind, or {@code null} if no kind has that index
   */
  public synchronized FactKind byIndex(int index) {
    if (index < 0) return null;
    for (FactKind k : byName.values()) {
      if (k.index() == index) return k;
    }
    return null;
  }

  public synchronized FactKind deregister(String name) {
    // The index is not reused: a stale index must not silently resolve to a different kind.
    indexByName.remove(name);
    return byName.remove(name);
  }

  public java.util.Collection<FactKind> all() {
    return Collections.unmodifiableCollection(byName.values());
  }

  /** How many kinds have been registered. */
  public int size() {
    return byName.size();
  }
}
