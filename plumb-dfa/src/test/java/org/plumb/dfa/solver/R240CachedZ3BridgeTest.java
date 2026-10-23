package org.plumb.dfa.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CachedZ3Bridge}. The cache should return identical results for identical
 * (predicate, bindings) pairs and serve them from memory after the first lookup.
 */
class R240CachedZ3BridgeTest {

  @Test
  void cache_returnsSameResultForSamePredicate() {
    CachedZ3Bridge bridge = new CachedZ3Bridge(new LocalSolverBridge());
    Map<String, Object> bindings = new HashMap<>();
    bindings.put("x", 5L);
    SolverZ3Bridge.Result first = bridge.check("x > 0", bindings);
    SolverZ3Bridge.Result second = bridge.check("x > 0", bindings);
    assertThat(first).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(second).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.cacheSize()).isEqualTo(1);
  }

  @Test
  void cache_distinguishesBindingValues() {
    CachedZ3Bridge bridge = new CachedZ3Bridge(new LocalSolverBridge());
    Map<String, Object> sat = new HashMap<>();
    sat.put("x", 5L);
    Map<String, Object> unsat = new HashMap<>();
    unsat.put("x", -3L);
    assertThat(bridge.check("x > 0", sat)).isEqualTo(SolverZ3Bridge.Result.SAT);
    assertThat(bridge.check("x > 0", unsat)).isEqualTo(SolverZ3Bridge.Result.UNSAT);
    assertThat(bridge.cacheSize()).isEqualTo(2);
  }

  @Test
  void cache_bindsBindingOrderInvariance() {
    CachedZ3Bridge bridge = new CachedZ3Bridge(new LocalSolverBridge());
    Map<String, Object> ab = new HashMap<>();
    ab.put("a", 1L);
    ab.put("b", 2L);
    Map<String, Object> ba = new HashMap<>();
    ba.put("b", 2L);
    ba.put("a", 1L);
    bridge.check("a < b", ab);
    assertThat(bridge.cacheSize()).isEqualTo(1);
    bridge.check("a < b", ba);
    // Same key — still one
    // entry.
    assertThat(bridge.cacheSize()).isEqualTo(1);
  }

  @Test
  void cache_clearDropsEntries() {
    CachedZ3Bridge bridge = new CachedZ3Bridge(new LocalSolverBridge());
    Map<String, Object> bindings = new HashMap<>();
    bindings.put("x", 1L);
    bridge.check("x > 0", bindings);
    assertThat(bridge.cacheSize()).isEqualTo(1);
    bridge.clearCache();
    assertThat(bridge.cacheSize()).isEqualTo(0);
  }

  @Test
  void cache_evictsWhenFull() {
    CachedZ3Bridge bridge = new CachedZ3Bridge(new LocalSolverBridge(), 4);
    for (int i = 0; i < 10; i++) {
      Map<String, Object> b = new HashMap<>();
      b.put("x", (long) i);
      bridge.check("x >= 0", b);
    }
    // Bounded LRU — must not
    // exceed max.
    assertThat(bridge.cacheSize()).isLessThanOrEqualTo(4);
  }

  @Test
  void cache_isThreadSafe() throws Exception {
    final CachedZ3Bridge bridge = new CachedZ3Bridge(new LocalSolverBridge());
    final Map<String, Object> bindings = new HashMap<>();
    bindings.put("x", 1L);
    final int threads = 8;
    final int iters = 100;
    final AtomicInteger errors = new AtomicInteger();
    Thread[] ts = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      ts[t] =
          new Thread(
              () -> {
                try {
                  for (int i = 0; i < iters; i++) {
                    bridge.check("x > 0", bindings);
                  }
                } catch (Throwable th) {
                  errors.incrementAndGet();
                }
              });
      ts[t].start();
    }
    for (Thread t : ts) t.join();
    assertThat(errors.get()).isZero();
  }
}
