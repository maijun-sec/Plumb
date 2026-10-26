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
   * Replaced {@code int hits} / {@code int misses} with {@link LongAdder} for low-contention
   * concurrent increment. The post-run log reads these via {@link #hits()} / {@link #misses()}.
   */
  private final LongAdder hits = new LongAdder();

  private final LongAdder misses = new LongAdder();

  public FunctionSummaryCache(TuningConfig tuning) {
    this.tuning = Objects.requireNonNull(tuning, "tuning");
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
    map.put(signature, summary);
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
}
