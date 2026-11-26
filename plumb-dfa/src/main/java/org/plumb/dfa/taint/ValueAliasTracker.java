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
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.plumb.dfa.taint;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which variables hold the SAME VALUE.
 *
 * <p><b>Why this exists.</b> A path condition is a statement about a VALUE, not about a variable
 * name. The engine used to build one from the guards recorded against the sink argument's own name,
 * so the condition was empty whenever the sink's argument was not the variable the guard talked
 * about — and the argument at a sink is usually a temporary:
 *
 * <pre>{@code
 * int copy = n;
 * if (copy > 5 && copy < 3) {   // the guard is on `copy`
 *     sink(n);                    // the sink's argument is `n`
 * }
 * }</pre>
 *
 * <p>Here the value that reaches the sink is the one the guard talks about, but the NAMES differ,
 * so the engine saw no guard and asked the solver nothing.
 *
 * <p><b>What an alias is here.</b> Two names are aliased when a copy made them hold one value:
 * {@code int copy = n} makes {@code copy} and {@code n} the same value, and a guard on either one
 * constrains the other. The relation is bidirectional for that reason — it is a statement about the
 * value, and the value does not care which name you call it.
 *
 * <p><b>What is NOT an alias.</b> Reading a field or an array element produces a value that is not
 * the container's value, so a load does not alias the container. Arithmetic produces a value that
 * is derived from the source rather than equal to it, and it is aliased to its source only because
 * the SOURCE's bounds still constrain it (the guard facts keep their own names, so the arithmetic
 * itself is never claimed to be the same value).
 *
 * <p><b>Why it is position sensitive.</b> An alias holds from the moment it is made. A copy made
 * AFTER the sink cannot explain the value at the sink, so every group carries the index at which it
 * came into being, and a group is only consulted at an index at or after that one. The direction of
 * the filter matters: it can only DELAY an alias, never apply one too early, so it cannot turn a
 * reachable path into an unreachable one.
 *
 * <p><b>What invalidates an alias.</b> An assignment. When a name is assigned, it holds a different
 * value, so it leaves the group it was in. This is the same discipline the taint tracker uses for
 * the same reason.
 */
public final class ValueAliasTracker {

  /** One group of names that hold one value. */
  private static final class Group {
    final Set<String> members = new LinkedHashSet<>();

    /** The index at which this group came into being. */
    int establishedAt;

    Group(String member, int establishedAt) {
      this.members.add(member);
      this.establishedAt = establishedAt;
    }
  }

  private final Map<String, Group> groupOf = new HashMap<>();

  /**
   * Record that {@code a} and {@code b} hold the same value from {@code atIndex} on.
   *
   * @param a one name
   * @param b the other name
   * @param atIndex the instruction index the relation starts at
   */
  public void alias(String a, String b, int atIndex) {
    if (a == null || b == null || a.equals(b)) return;
    Group ga = groupOf.get(a);
    Group gb = groupOf.get(b);
    if (ga == gb) return;
    if (ga == null) ga = new Group(a, atIndex);
    if (gb == null) gb = new Group(b, atIndex);
    // The merged group is usable only from NOW on: the relation between the two
    // sides is a fact about this point in the function, and asking about it at an
    // earlier index would be asking about a value that did not exist yet.
    ga.members.addAll(gb.members);
    ga.establishedAt = atIndex;
    for (String m : ga.members) {
      groupOf.put(m, ga);
    }
  }

  /**
   * Record that {@code name} now holds a DIFFERENT value: it leaves the group it was in.
   *
   * @param name the name that was assigned
   * @param atIndex the instruction index the assignment happens at
   */
  public void kill(String name, int atIndex) {
    if (name == null) return;
    Group old = groupOf.remove(name);
    if (old != null) {
      old.members.remove(name);
    }
    groupOf.put(name, new Group(name, atIndex));
  }

  /**
   * The names that hold the same value as {@code name}, as of {@code atIndex}.
   *
   * <p>The result always contains {@code name} itself. It contains the other members only when the
   * group they belong to was already in being at {@code atIndex}.
   *
   * @param name the name to ask about
   * @param atIndex the instruction index to ask at
   * @return the names holding the same value (never empty)
   */
  public Set<String> groupAt(String name, int atIndex) {
    if (name == null) return Set.of();
    Group g = groupOf.get(name);
    if (g == null) return Set.of(name);
    if (atIndex >= 0 && g.establishedAt > atIndex) {
      // The group came into being later than the position being asked about, so
      // at that point this name was on its own.
      return Set.of(name);
    }
    return new LinkedHashSet<>(g.members);
  }

  /** Forget everything. The state is per function. */
  public void clear() {
    groupOf.clear();
  }

  /** The groups, for diagnostics. */
  public Map<String, Set<String>> snapshot() {
    Map<String, Set<String>> out = new LinkedHashMap<>();
    for (Map.Entry<String, Group> e : groupOf.entrySet()) {
      out.put(e.getKey(), new LinkedHashSet<>(e.getValue().members));
    }
    return out;
  }
}
