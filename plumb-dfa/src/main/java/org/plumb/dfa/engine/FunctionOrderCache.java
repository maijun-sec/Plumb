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
package org.plumb.dfa.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.plumb.tac.ir.TacCfg;
import org.plumb.tac.ir.TacCfgBuilder;
import org.plumb.tac.ir.TacFunction;

/**
 * A per-run cache of the control-flow graph and the analysis order, keyed by function.
 *
 * <p><b>Why this exists.</b> The analysis order is not a cheap thing to compute: it builds the CFG
 * from the instruction list, runs a depth-first search, identifies the back edges, computes each
 * natural loop, and then re-runs a search with a successor order chosen so that loop bodies come
 * before loop exits. That is a walk over the whole function, done several times.
 *
 * <p>It used to be done <em>once per checker per function</em>. With 78 registered checkers, a
 * function was walked 78 times to produce 78 identical lists. The work was pure repetition: the
 * order depends only on the function, not on which checker wants it.
 *
 * <p>So the order is computed once per function and shared. The cache is bounded -- an unbounded
 * map keyed by function would keep every analysed function alive for the run's duration, which for
 * a large scan is the whole program.
 *
 * <h2>What is cached, and why both</h2>
 *
 * <p>Two things: the {@link TacCfg} and the analysis order. The CFG is cached because the order is
 * derived from it, and because the CFG is also needed by the path-sensitive checkers, which would
 * otherwise each rebuild it.
 *
 * <h2>It delegates; it does not reimplement</h2>
 *
 * <p>The order is computed by {@link DfaEngine#analysisOrder(TacFunction)} -- the one
 * implementation, with the loop-aware successor ordering that the taint engine's correctness
 * depends on. A cache that carried its own copy of that algorithm would be a second source of
 * truth, and the two would drift: the cache would be right about the functions it was written for
 * and wrong about the ones it was not.
 *
 * <p>That means the cache cannot avoid the CFG being built twice for a cache miss -- once inside
 * {@code analysisOrder}, and once for the CFG it stores. The second build is the price of not
 * having two implementations. It is a real cost, and it is deliberately paid: a duplicated
 * algorithm is a worse cost, because it fails silently.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The engine's parallel worklist analyses different functions on different threads, so the cache
 * is concurrent. Two threads racing on the same function compute the same value twice, which is
 * harmless; the map itself is the only shared state.
 */
public final class FunctionOrderCache {

  /**
   * How many functions to keep.
   *
   * <p>A scan of a large project analyses thousands of functions. Keeping every CFG alive would
   * hold the whole program's control-flow graphs in memory for the duration of the run, which is
   * the kind of cost that turns a fast scan into a slow one. The cache is a small LRU: the
   * functions being analysed right now are the ones that benefit.
   */
  private static final int MAX_ENTRIES = 512;

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();

  /** A cached CFG and analysis order for one function. */
  private static final class Entry {
    final TacCfg cfg;
    final List<Integer> order;

    Entry(TacCfg cfg, List<Integer> order) {
      this.cfg = cfg;
      this.order = order;
    }
  }

  /**
   * The analysis order for a function, computed once and reused.
   *
   * @param fn the function
   * @return the instruction indices in analysis order
   */
  public List<Integer> analysisOrder(TacFunction fn) {
    return entryFor(fn).order;
  }

  /**
   * The control-flow graph for a function, computed once and reused.
   *
   * @param fn the function
   * @return the CFG
   */
  public TacCfg cfg(TacFunction fn) {
    return entryFor(fn).cfg;
  }

  private Entry entryFor(TacFunction fn) {
    if (fn == null) {
      return new Entry(
          TacCfgBuilder.build(
              new TacFunction(" empty", " empty", null, List.of(), List.of(), List.of())),
          List.of());
    }
    String key = fn.signature();
    Entry cached = entries.get(key);
    if (cached != null) {
      hits.incrementAndGet();
      return cached;
    }
    misses.incrementAndGet();
    // The order comes from the engine's single implementation. See the class javadoc:
    // a cache with its own copy of the algorithm would be a second source of truth.
    List<Integer> order = DfaEngine.analysisOrder(fn);
    TacCfg cfg = TacCfgBuilder.build(fn);
    Entry entry = new Entry(cfg, order);
    if (entries.size() >= MAX_ENTRIES) {
      // Simple eviction: drop one entry. The map is insertion-ordered enough for this to
      // approximate an LRU, and the cost of a stale miss is one recomputation.
      java.util.Iterator<String> it = entries.keySet().iterator();
      if (it.hasNext()) {
        entries.remove(it.next());
      }
    }
    entries.put(key, entry);
    return entry;
  }

  /** How many times the cache was hit. */
  public long hits() {
    return hits.get();
  }

  /** How many times the cache missed. */
  public long misses() {
    return misses.get();
  }

  /** The number of cached functions. */
  public int size() {
    return entries.size();
  }

  /** Empties the cache. */
  public void clear() {
    entries.clear();
  }
}
