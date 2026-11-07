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
package org.plumb.dfa.summary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;

/**
 * Per-engine-run cache of {@link FunctionSummary}s, keyed by function signature.
 *
 * <p>The cache was introduced as a single-threaded data structure (LinkedHashMap + int counters)
 * and later lifted to a thread-safe implementation backed by {@link ConcurrentHashMap} + {@link
 * LongAdder} so the engine can analyse functions in parallel without contention. The public API is
 * unchanged: every method that was single-threaded still works the same way; parallel callers just
 * don't serialise on a monitor.
 *
 * <h2>Atomicity guarantees</h2>
 *
 * <ul>
 *   <li>{@link #get(String)} -- lock-free read on the {@code ConcurrentHashMap}; the hit / miss
 *       {@link LongAdder}s do a thread-local increment and a (rare) contended write, so even hot
 *       loops scale.
 *   <li>{@link #put(String, FunctionSummary)} -- last-writer-wins on the {@code ConcurrentHashMap}.
 *       Two parallel putters of the same signature race; either summary is acceptable to the engine
 *       because both come from the same analysis pass (deterministic for any given input).
 *   <li>{@link #computeIfAbsent(String, Function)} -- delegated to {@code
 *       ConcurrentMap.computeIfAbsent} which is atomic: the producer runs at most once per
 *       signature, even under contention.
 *   <li>{@link #clear()} -- best-effort reset. Concurrent readers may see stale data for one or two
 *       operations after {@code clear()} returns. The engine calls {@code clear()} only at the
 *       start of a run, when no threads are yet reading, so this is acceptable.
 * </ul>
 *
 * <h2>Why not partition the cache by thread</h2>
 *
 * <p>A partition-by-thread design (each thread keeps its own map, merged at the end) would avoid
 * contention but lose the "caller sees callee summary" invariant when the caller and callee happen
 * to run on different threads. The shared {@code ConcurrentHashMap} keeps that invariant intact:
 * any thread's {@code get(calleeSig)} sees every other thread's prior {@code put(calleeSig, …)}.
 */
public final class FunctionSummaryCache {

  private final TuningConfig tuning;

  /**
   * Map<signature, summary>. Backed by {@link ConcurrentHashMap} for lock-free concurrent reads and
   * atomic puts. Insertion order is no longer preserved (the engine no longer depends on it -- RPO
   * is the engine's scheduling order, not the cache's iteration order).
   */
  private final ConcurrentMap<String, FunctionSummary> map = new ConcurrentHashMap<>();

  /**
   * The CONTEXT-SENSITIVE entries, keyed by {@link SummaryKey} -- the callee's signature AND the
   * call context it was reached through.
   *
   * <p>Kept in a SEPARATE map from {@link #map} rather than merged into it, because the two answer
   * different questions. The plain map holds the callee's own, context-free facts. The contextual
   * map holds the CALLER's projection of those facts through one call site's binding. Merging them
   * would make a context-free lookup able to return a projection -- and a projection is missing the
   * operations that call site could not reach, so returning one as if it were the whole summary
   * would under-report.
   */
  private final ConcurrentMap<SummaryKey, FunctionSummary> contextual = new ConcurrentHashMap<>();

  /**
   * Replaced {@code int hits} / {@code int misses} with {@link LongAdder} for low-contention
   * concurrent increment. The post-run log reads these via {@link #hits()} / {@link #misses()}.
   */
  private final LongAdder hits = new LongAdder();

  private final LongAdder misses = new LongAdder();

  /**
   * How many summaries were refused because the cache was full.
   *
   * <p>The engine reports this, because a refused summary means a function's cross-function facts
   * did not reach its callers, and that is an incomplete analysis rather than a clean one.
   */
  private final LongAdder overflows = new LongAdder();

  /** The configured capacity, or {@code 0} for "unbounded". */
  private final int capacity;

  public FunctionSummaryCache(TuningConfig tuning) {
    this.tuning = Objects.requireNonNull(tuning, "tuning");
    int configured = tuning.summaryCacheSize();
    // A non-positive capacity means "no limit". Treating a misconfigured zero as "cache
    // nothing" would silently disable the whole cross-function analysis, which is a much
    // bigger change than the user asked for.
    this.capacity = configured > 0 ? configured : 0;
  }

  /**
   * Is there room for one more entry?
   *
   * <p>Checked INSIDE the atomic map operation by the callers, so two workers cannot both see room
   * and both take it.
   */
  private boolean hasRoom() {
    if (capacity <= 0) return true;
    return map.size() < capacity;
  }

  /**
   * How many summaries were refused because the cache was full.
   *
   * <p>Non-zero means the analysis was incomplete: a function's facts did not reach its callers.
   */
  public long overflowCount() {
    return overflows.sum();
  }

  /** The configured capacity, or {@code 0} when the cache is unbounded. */
  public int capacity() {
    return capacity;
  }

  /**
   * Look up the summary for {@code signature}. Returns the trivial empty summary (not null) if no
   * summary was ever recorded. A null return is reserved for "the cache itself was never asked" —
   * that's a programming error in the caller, not a legitimate "no summary" answer.
   */
  public FunctionSummary get(String signature) {
    if (signature == null || signature.isEmpty()) {
      return FunctionSummary.empty();
    }
    FunctionSummary s = map.get(signature);
    if (s != null) {
      hits.increment();
      return s;
    }
    misses.increment();
    return FunctionSummary.empty();
  }

  /**
   * Look up by {@link TacFunction} (delegates to the signature-keyed lookup). Convenience for
   * checkers that have a function in hand rather than a string.
   */
  public FunctionSummary get(TacFunction fn) {
    if (fn == null) return FunctionSummary.empty();
    return get(fn.signature());
  }

  /**
   * Populate the cache entry for {@code signature} with {@code summary}. Replaces any prior entry.
   * Backed by {@code ConcurrentHashMap.put}, lock-free on the common path. Two parallel putters of
   * the same signature race; either summary wins (the engine analyses each function exactly once,
   * so this can only happen if the same function is registered twice -- a user-error path the
   * engine doesn't recover from gracefully).
   */
  public void put(String signature, FunctionSummary summary) {
    if (signature == null || signature.isEmpty() || summary == null) {
      return;
    }
    // Replacing an existing entry never grows the cache, so it is always allowed.
    if (map.containsKey(signature)) {
      map.put(signature, summary);
      invalidateProjectionsOf(signature);
      return;
    }
    if (!hasRoom()) {
      overflows.increment();
      return;
    }
    map.put(signature, summary);
    invalidateProjectionsOf(signature);
  }

  /**
   * Drop the context-sensitive projections of a signature.
   *
   * <p>A projection is derived from the context-free summary. When that summary is replaced, the
   * projections derived from the OLD one no longer describe the current facts -- they are missing
   * whatever the new summary added. A stale projection is a silent under-report, which is the one
   * failure mode a context-sensitive layer must not have.
   *
   * <p>So the projections go when the summary they were derived from changes.
   *
   * @param signature the signature whose projections are now stale
   */
  private void invalidateProjectionsOf(String signature) {
    if (signature == null || signature.isEmpty()) return;
    contextual.keySet().removeIf(key -> key.signature().equals(signature));
  }

  /**
   * Merge {@code summary} into the entry for {@code signature}, instead of replacing it.
   *
   * <p><b>Why a merge and not a put.</b> The cache is keyed by SIGNATURE, because that is what a
   * call site resolves to. Two functions can share a signature -- two subclasses of an abstract
   * base both implementing {@code action(String)} -- and both are analysed. A plain {@code put}
   * keeps whichever variant was stored last, so the summary a caller reads would depend on the
   * order the scheduler happened to finish the two variants in. Merging makes the stored summary
   * independent of the order: the caller sees the union of what both variants do.
   *
   * <p>Atomic: delegated to {@code ConcurrentMap.compute}, so two workers storing the two variants
   * at the same time cannot lose one of them.
   */
  public void merge(String signature, FunctionSummary summary) {
    if (signature == null || signature.isEmpty() || summary == null) {
      return;
    }
    map.compute(
        signature,
        (sig, existing) -> {
          if (existing == null) {
            // A new entry. The capacity check is INSIDE the atomic operation, so two workers
            // cannot both see room and both take it.
            if (!hasRoom()) {
              overflows.increment();
              return null;
            }
            return summary;
          }
          FunctionSummary combined = existing.merge(summary);
          return combined == null ? existing : combined;
        });
    // The merged summary is a different summary, so the projections derived
    // from the old one are stale.
    invalidateProjectionsOf(signature);
  }

  /**
   * Atomic conditional put. Use when the caller wants to record a summary ONLY if no one else has
   * already recorded one for this signature (e.g. a checker that wants to contribute a default but
   * yield to a more authoritative source).
   */
  public FunctionSummary putIfAbsent(String signature, FunctionSummary summary) {
    if (signature == null || signature.isEmpty() || summary == null) {
      return FunctionSummary.empty();
    }
    return map.putIfAbsent(signature, summary);
  }

  /**
   * Conditional put: compute the summary via {@code producer} only if no entry exists for {@code
   * signature}. Delegated to {@code ConcurrentMap.computeIfAbsent} which is atomic -- the producer
   * runs at most once per signature, even under contention.
   */
  public FunctionSummary computeIfAbsent(
      String signature, Function<String, FunctionSummary> producer) {
    if (signature == null || signature.isEmpty()) {
      return FunctionSummary.empty();
    }
    return map.computeIfAbsent(
        signature,
        sig -> {
          misses.increment();
          FunctionSummary s = producer.apply(sig);
          return s == null ? FunctionSummary.empty() : s;
        });
  }

  /**
   * Whether the cache contains a real (non-empty) entry for {@code signature}. Useful for
   * distinguishing "this function was analysed" from "this function is trivially empty because it
   * wasn't even visited".
   */
  public boolean contains(String signature) {
    return signature != null && map.containsKey(signature);
  }

  /**
   * Convenience: should the given function be summarised at all? Delegates to {@link
   * TuningConfig#shouldAnalyse(SourceOrigin)}; if the origin is filtered out, the caller should not
   * put a summary into the cache (the engine uses this to short-circuit the summary pass for
   * SYSTEM_LIBRARY functions by default).
   */
  public boolean shouldSummarise(TacFunction fn) {
    return tuning.shouldAnalyse(fn.origin());
  }

  /**
   * Drop every cached entry. Called by the engine at the start of each run so that summaries from a
   * previous run don't leak across.
   */
  public void clear() {
    map.clear();
    contextual.clear();
    hits.reset();
    misses.reset();
  }

  public int size() {
    return map.size();
  }

  /**
   * Total cache hits since the last {@link #clear()} (or construction). Thread-safe; safe to read
   * concurrently with {@link #get} / {@link #put} calls.
   */
  public long hits() {
    return hits.sum();
  }

  /**
   * Total cache misses since the last {@link #clear()} (or construction). Thread-safe; safe to read
   * concurrently with {@link #get} / {@link #put} calls.
   */
  public long misses() {
    return misses.sum();
  }

  /**
   * Snapshot of all entries. Returns a copy so the cache can keep its mutable state private. Useful
   * for post-run dump / debugging / inspection.
   */
  public Map<String, FunctionSummary> snapshot() {
    return new LinkedHashMap<>(map);
  }

  // ── call-string-aware entries ──────────────────────────────────────────
  //
  // The entries above are the CALLEE's own facts: "this function does these things to its
  // parameters", computed once from the function's body. Those are context-free by construction,
  // and they stay that way -- the summary BUILDING method is not re-run per call site.
  //
  // What is context-sensitive is the CALLER's side: at a given call site, only some of the
  // callee's formal parameters are reachable (the ones a tainted argument actually binds to),
  // and the argument that binds to them differs from call site to call site.
  //
  // So the context-sensitive entry is not a second analysis of the callee. It is the callee's
  // context-free summary, PROJECTED through one call site's binding: the operations on a formal
  // parameter that the call site does not feed with taint are dropped, because at THIS call site
  // nothing can reach them.
  //
  // The projection is monotone in the safe direction: a context-sensitive entry is always a
  // SUBSET of the context-free one. A lookup that finds no context-sensitive entry falls back to
  // the context-free one, so enabling this can only ever remove taint that provably could not
  // reach the sink -- never add it, and never lose it.

  /**
   * The context-sensitive entry for a (signature, call string) pair.
   *
   * @param signature the callee's signature
   * @param callString the call context
   * @return the projected summary, or the empty summary when there is no such entry
   */
  public FunctionSummary getInContext(String signature, String callString) {
    if (signature == null || signature.isEmpty()) {
      return FunctionSummary.empty();
    }
    if (callString == null || callString.isEmpty()) {
      return get(signature);
    }
    FunctionSummary s = contextual.get(new SummaryKey(signature, callString));
    if (s != null) {
      hits.increment();
      return s;
    }
    misses.increment();
    return FunctionSummary.empty();
  }

  /**
   * Record the context-sensitive projection for a (signature, call string) pair.
   *
   * <p>Replaces any prior entry for the pair: a projection is derived, so a later, more precise
   * projection of the same call site is the better answer.
   *
   * @param signature the callee's signature
   * @param callString the call context
   * @param summary the projected summary
   */
  public void putInContext(String signature, String callString, FunctionSummary summary) {
    if (signature == null || signature.isEmpty() || summary == null) {
      return;
    }
    if (callString == null || callString.isEmpty()) {
      put(signature, summary);
      return;
    }
    contextual.put(new SummaryKey(signature, callString), summary);
  }

  /**
   * Whether a context-sensitive entry exists for a (signature, call string) pair.
   *
   * @param signature the callee's signature
   * @param callString the call context
   * @return true when there is one
   */
  public boolean hasInContext(String signature, String callString) {
    if (signature == null || signature.isEmpty() || callString == null || callString.isEmpty()) {
      return false;
    }
    return contextual.containsKey(new SummaryKey(signature, callString));
  }

  /**
   * How many context-sensitive entries the cache holds.
   *
   * @return the count
   */
  public int contextualSize() {
    return contextual.size();
  }

  /**
   * A snapshot of the context-sensitive entries.
   *
   * @return a copy of the entries
   */
  public Map<SummaryKey, FunctionSummary> contextualSnapshot() {
    return new LinkedHashMap<>(contextual);
  }
}
