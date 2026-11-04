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
package org.plumb.dfa.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A solver query must be time-bounded, and the timeout must answer honestly.
 *
 * <p><b>Why this matters for correctness, not just for speed.</b> The bridge answers SAT, UNSAT or
 * UNKNOWN. SAT means "the path is reachable"; UNSAT means "the path is dead". A checker that needs
 * the solver to confirm reachability uses the answer to decide whether to suppress a finding.
 *
 * <p>So a timeout that returned SAT would claim "reachable" without knowing, and a timeout that
 * returned UNSAT would claim "dead" without knowing. Both are worse than admitting ignorance: UNSAT
 * suppresses findings that were real. The only honest answer is UNKNOWN, and the analysis must
 * treat UNKNOWN as "cannot decide" rather than as either verdict.
 */
class TimeBoundedSolverBridgeTest {

  /** A bridge whose inner query never returns. */
  private static final class StuckBridge implements SolverZ3Bridge {
    final AtomicBoolean interrupted = new AtomicBoolean();

    @Override
    public Result check(String predicate, Map<String, Object> bindings) {
      try {
        while (true) {
          Thread.sleep(10);
        }
      } catch (InterruptedException e) {
        interrupted.set(true);
        Thread.currentThread().interrupt();
        return Result.SAT;
      }
    }

    @Override
    public java.util.Set<String> referencedVars(String predicate) {
      return java.util.Set.of();
    }
  }

  /** A bridge that answers immediately. */
  private static final class QuickBridge implements SolverZ3Bridge {
    @Override
    public Result check(String predicate, Map<String, Object> bindings) {
      return Result.SAT;
    }

    @Override
    public Boolean implies(String antecedent, String consequent, Map<String, Object> bindings) {
      return Boolean.TRUE;
    }

    @Override
    public java.util.Set<String> referencedVars(String predicate) {
      return java.util.Set.of();
    }
  }

  /**
   * A query that overruns its budget must return UNKNOWN -- not SAT, not UNSAT.
   *
   * <p>Returning UNSAT here would be the worst outcome: it would tell the engine that the path is
   * dead, and the engine would suppress findings on a path it never actually checked.
   */
  @Test
  void aTimedOutQueryAnswersUnknown() {
    StuckBridge inner = new StuckBridge();
    try (TimeBoundedSolverBridge bridge = new TimeBoundedSolverBridge(inner, 50L)) {
      long start = System.nanoTime();
      SolverZ3Bridge.Result result = bridge.check("x == 1", Map.of("x", 1L));
      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

      assertThat(result)
          .as("a timeout must not claim the path is dead")
          .isNotEqualTo(SolverZ3Bridge.Result.UNSAT);
      assertThat(result).isEqualTo(SolverZ3Bridge.Result.UNKNOWN);
      assertThat(elapsedMs)
          .as("the call must return promptly, not wait for the stuck query")
          .isLessThan(5_000L);
      assertThat(bridge.timeouts()).isEqualTo(1L);
    }
  }

  /** A query that finishes in time must return the inner bridge's real answer. */
  @Test
  void aQueryWithinBudgetReturnsTheRealAnswer() {
    try (TimeBoundedSolverBridge bridge = new TimeBoundedSolverBridge(new QuickBridge(), 5_000L)) {
      assertThat(bridge.check("x == 1", Map.of("x", 1L))).isEqualTo(SolverZ3Bridge.Result.SAT);
      assertThat(bridge.implies("x == 1", "x == 1", Map.of("x", 1L))).isTrue();
      assertThat(bridge.timeouts()).isZero();
      assertThat(bridge.queryCount()).isEqualTo(2L);
    }
  }

  /**
   * A bridge with no timeout must pass straight through.
   *
   * <p>This is the control for the timeout tests: without it, "the query returned SAT" could pass
   * simply because the timeout never applied.
   */
  @Test
  void aBridgeWithoutATimeoutPassesThrough() {
    try (TimeBoundedSolverBridge bridge = new TimeBoundedSolverBridge(new QuickBridge(), 0L)) {
      assertThat(bridge.check("x == 1", Map.of("x", 1L))).isEqualTo(SolverZ3Bridge.Result.SAT);
      assertThat(bridge.timeouts()).isZero();
    }
  }

  /**
   * A stuck query must not keep the JVM alive.
   *
   * <p>The worker thread is a daemon, and the query is cancelled on timeout. If the inner bridge
   * ignores interruption the worker may still be running when the bridge is closed -- but it must
   * never be a non-daemon thread holding the process open.
   */
  @Test
  void aStuckQueryDoesNotHoldTheJvmOpen() throws Exception {
    StuckBridge inner = new StuckBridge();
    TimeBoundedSolverBridge bridge = new TimeBoundedSolverBridge(inner, 20L);
    assertThat(bridge.check("x == 1", Map.of("x", 1L))).isEqualTo(SolverZ3Bridge.Result.UNKNOWN);
    bridge.close();

    // Every worker thread the bridge started must be a daemon.
    Thread[] threads = new Thread[Thread.activeCount()];
    int n = Thread.enumerate(threads);
    for (int i = 0; i < n; i++) {
      if (threads[i] != null && "plumb-solver".equals(threads[i].getName())) {
        assertThat(threads[i].isDaemon())
            .as("a solver worker must not keep the JVM alive")
            .isTrue();
      }
    }
  }
}
