package org.plumb.dfa.solver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caching layer over a {@link SolverZ3Bridge}.
 *
 * <p>During a Juliet-style sweep the engine may ask the bridge for the same path condition
 * thousands of times. Z3 is fast for small inputs but creating a fresh {@code
 * com.microsoft.z3.Context} for every call still costs a few hundred microseconds of native
 * overhead. The cache layers a {@link LinkedHashMap} (with a bounded LRU eviction policy) in front
 * of the inner bridge so the second and subsequent calls with the same {@code (predicate,
 * bindings)} pair are answered instantly.
 *
 * <h2>Key derivation</h2>
 *
 * <p>The cache key is {@code predicate} concatenated with the bindings sorted by variable name and
 * {@code name=value} rendered with {@code Objects.toString}. Sort order matters because {@code
 * (x=1, y=2)} and {@code (y=2, x=1)} are the same constraint; the cache treats them as the same
 * key.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #check(String, Map)} synchronises on a {@link ReentrantLock}. The bridge is intended to
 * be shared across the DFA engine's worker threads (worker-thread parallelism).
 */
public final class CachedZ3Bridge implements SolverZ3Bridge {

  private final SolverZ3Bridge inner;
  private final int maxEntries;
  private final Map<String, Result> cache;
  private final ReentrantLock lock = new ReentrantLock();

  public CachedZ3Bridge(SolverZ3Bridge inner) {
    this(inner, 4096);
  }

  public CachedZ3Bridge(SolverZ3Bridge inner, int maxEntries) {
    this.inner = inner;
    this.maxEntries = maxEntries;
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > CachedZ3Bridge.this.maxEntries;
          }
        };
  }

  @Override
  public Result check(String predicate, Map<String, Object> bindings) {
    String key = renderKey(predicate, bindings);
    lock.lock();
    try {
      Result cached = cache.get(key);
      if (cached != null) return cached;
      Result r = inner.check(predicate, bindings);
      cache.put(key, r);
      return r;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Set<String> referencedVars(String predicate) {
    return inner.referencedVars(predicate);
  }

  /** Number of entries currently held. Useful in tests / logs. */
  public int cacheSize() {
    lock.lock();
    try {
      return cache.size();
    } finally {
      lock.unlock();
    }
  }

  /** Drop the entire cache. */
  public void clearCache() {
    lock.lock();
    try {
      cache.clear();
    } finally {
      lock.unlock();
    }
  }

  static String renderKey(String predicate, Map<String, Object> bindings) {
    StringBuilder sb = new StringBuilder();
    sb.append(predicate == null ? "" : predicate);
    sb.append('|');
    if (bindings != null && !bindings.isEmpty()) {
      bindings.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              e -> {
                sb.append(e.getKey())
                    .append('=')
                    .append(e.getValue() == null ? "null" : e.getValue().toString())
                    .append(';');
              });
    }
    return sb.toString();
  }
}
