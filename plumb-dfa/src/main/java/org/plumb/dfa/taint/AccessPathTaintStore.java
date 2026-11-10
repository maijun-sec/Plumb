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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.plumb.tac.ir.TacAccessPath;

/**
 * Taint state keyed by access path, shared by every kind of container.
 *
 * <p>This is the one place that knows how to answer "is the value at this location tainted, and
 * where did it come from?". The field tracker, the array tracker and any future map tracker all
 * delegate to it, so the aliasing rules and the path-matching rules are written once.
 *
 * <h2>What the store holds</h2>
 *
 * <p>A map from {@link TacAccessPath} to the taint found there. The path is the location; the value
 * is what the engine knows about it.
 *
 * <h2>Path matching</h2>
 *
 * <p>A read at path {@code p} finds the taint of any <em>exact</em> match, plus the taint of any
 * path that is a prefix of {@code p} (the container is tainted, so its element is too), plus the
 * taint of any path that {@code p} is a prefix of (the element is tainted, so reading the container
 * sees it).
 *
 * <p>The third direction is the interesting one: it is what makes {@code a[0] = tainted} visible to
 * a later read of {@code a}. Without it, array taint has to be tracked separately from field taint,
 * which is exactly the duplication this class removes.
 */
public final class AccessPathTaintStore {

  /** The taint recorded at a location, and where it came from. */
  public static final class Entry {
    private final String label;
    private final List<org.plumb.reporter.FlowStep> path;

    Entry(String label, List<org.plumb.reporter.FlowStep> path) {
      this.label = label;
      this.path = path == null ? List.of() : List.copyOf(path);
    }

    /** The taint label. */
    public String label() {
      return label;
    }

    /** The flow steps that lead here. */
    public List<org.plumb.reporter.FlowStep> path() {
      return path;
    }
  }

  private final Map<TacAccessPath, Entry> byPath = new LinkedHashMap<>();

  /**
   * Records taint at a location.
   *
   * @param path the location
   * @param label the taint label
   * @param flowPath the flow steps leading to it
   */
  public void record(TacAccessPath path, String label, List<org.plumb.reporter.FlowStep> flowPath) {
    if (path == null) return;
    byPath.put(path, new Entry(label, flowPath));
  }

  /**
   * The taint at a location, if any.
   *
   * <p>Considers the exact location, the containers it lives in, and the elements it contains.
   *
   * @param path the location to read
   * @return the taint found, or {@code null} if the location is clean
   */
  public Entry read(TacAccessPath path) {
    if (path == null) return null;
    Entry exact = byPath.get(path);
    if (exact != null) return exact;
    // The same entry, reached through a key the analysis could not resolve.
    Entry covering = readCovering(path);
    if (covering != null) return covering;
    // The container is tainted, so the element is too.
    Entry fromContainer = readContainer(path);
    if (fromContainer != null) return fromContainer;
    // The element is tainted, so reading the container sees it.
    return readElement(path);
  }

  /**
   * The taint at the same entry, reached through a key the analysis could not resolve.
   *
   * <p>This is the rule that makes a keyed namespace work. A write to {@code obj.userId} and a
   * later read of {@code obj[anyKey]} name the same entry as far as the analysis can tell -- the
   * read's key might be {@code userId} -- so the read has to see the write. The rule is symmetric:
   * a write through a key the analysis could not resolve may have landed on any entry, so a read of
   * a named entry has to see it too.
   *
   * <p>Two DIFFERENT named entries never match each other: {@code obj.userId} and {@code obj.role}
   * are different locations, and treating them as one is a false positive.
   */
  private Entry readCovering(TacAccessPath path) {
    for (Map.Entry<TacAccessPath, Entry> e : byPath.entrySet()) {
      TacAccessPath candidate = e.getKey();
      if (candidate.coversLocation(path)) {
        return e.getValue();
      }
    }
    return null;
  }

  private Entry readContainer(TacAccessPath path) {
    for (Map.Entry<TacAccessPath, Entry> e : byPath.entrySet()) {
      TacAccessPath candidate = e.getKey();
      if (candidate.isPrefixOf(path) && candidate.depth() < path.depth()) {
        return e.getValue();
      }
    }
    return null;
  }

  private Entry readElement(TacAccessPath path) {
    for (Map.Entry<TacAccessPath, Entry> e : byPath.entrySet()) {
      TacAccessPath candidate = e.getKey();
      if (path.isPrefixOf(candidate) && path.depth() < candidate.depth()) {
        return e.getValue();
      }
    }
    return null;
  }

  /** The taint at exactly this location, with no container/element reasoning. */
  public Entry readExact(TacAccessPath path) {
    return path == null ? null : byPath.get(path);
  }

  /** Whether anything is tracked at this location or beneath it. */
  public boolean has(TacAccessPath path) {
    return read(path) != null;
  }

  /** Every tracked location. */
  public java.util.Set<TacAccessPath> paths() {
    return Collections.unmodifiableSet(byPath.keySet());
  }

  /** The number of tracked locations. */
  public int size() {
    return byPath.size();
  }

  /** Removes the state at one location. */
  public void clear(TacAccessPath path) {
    if (path == null) return;
    byPath.remove(path);
  }

  /**
   * Removes the state at a location and everything beneath it.
   *
   * <p>A clean write to a container clears the container's elements too: after {@code a = fresh},
   * the taint that {@code a[0]} carried is gone, because {@code a} is a different object now.
   */
  public void clearSubtree(TacAccessPath path) {
    if (path == null) return;
    byPath.remove(path);
    List<TacAccessPath> doomed = new ArrayList<>();
    for (TacAccessPath candidate : byPath.keySet()) {
      if (path.isPrefixOf(candidate)) doomed.add(candidate);
    }
    for (TacAccessPath d : doomed) byPath.remove(d);
  }

  /** Removes everything. */
  public void clear() {
    byPath.clear();
  }

  /** A readable dump, for diagnostics. */
  public String dump() {
    StringBuilder sb = new StringBuilder("AccessPathTaintStore{");
    sb.append(byPath.size()).append(" location(s)");
    for (Map.Entry<TacAccessPath, Entry> e : byPath.entrySet()) {
      sb.append(", ").append(e.getKey()).append("=").append(e.getValue().label());
    }
    return sb.append('}').toString();
  }
}
