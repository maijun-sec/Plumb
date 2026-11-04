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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link SolverZ3Bridge} that bounds how long a single query may take.
 *
 * <p><b>Why this wrapper exists.</b> The tuning configuration has carried {@code z3_timeout_ms}
 * since it was written, but nothing ever read it: the bridge was called directly and a pathological
 * predicate could stall the whole analysis. A timeout that is not enforced is worse than no
 * timeout, because a user who sets one believes the analysis is bounded and it is not.
 *
 * <p>The query runs on a worker thread so that the timeout can actually interrupt it. A query that
 * runs on the calling thread cannot be stopped -- {@code Thread.interrupt} only sets a flag, and a
 * solver loop that never checks it runs to completion regardless.
 *
 * <h2>What happens on timeout</h2>
 *
 * <p>The bridge returns {@link SolverZ3Bridge.Result#UNKNOWN}. That is the honest answer: the
 * engine does not know whether the path is reachable. It is deliberately <em>not</em> {@code SAT}
 * and <em>not</em> {@code UNSAT} -- both of those are claims about the program's behaviour, and a
 * timeout supports neither claim.
 *
 * <p>The consequence is the conservative one for false negatives: a checker that needs the solver
 * to confirm reachability cannot confirm it, so it does not suppress a finding on that basis. A
 * timeout makes the analysis less precise, not less truthful.
 *
 * <h2>Reporting</h2>
 *
 * <p>Every timeout is counted. {@link #timeouts()} is what the engine's budget report reads, so a
 * run that hit the solver timeout says so rather than silently degrading.
 */
public final class TimeBoundedSolverBridge implements SolverZ3Bridge, AutoCloseable {

  private final SolverZ3Bridge inner;
  private final long timeoutMs;
  private final ExecutorService executor;
  private final AtomicLong timeoutCount = new AtomicLong();
  private final AtomicLong queryCount = new AtomicLong();
  private final boolean ownsExecutor;

  /**
   * Wraps {@code inner} with a per-query timeout.
   *
   * @param inner the bridge to delegate to
   * @param timeoutMs the per-query limit in milliseconds; 0 or less means no limit
   */
  public TimeBoundedSolverBridge(SolverZ3Bridge inner, long timeoutMs) {
    this.inner = inner;
    this.timeoutMs = timeoutMs;
    this.ownsExecutor = true;
    this.executor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "plumb-solver");
              // A daemon thread: a stuck solver query must never keep the JVM alive.
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * Wraps {@code inner} with a per-query timeout, using a caller-owned executor.
   *
   * <p>Use this when the caller already has a thread pool it wants the queries on.
   *
   * @param inner the bridge to delegate to
   * @param timeoutMs the per-query limit in milliseconds; 0 or less means no limit
   * @param executor the pool to run queries on
   */
  public TimeBoundedSolverBridge(SolverZ3Bridge inner, long timeoutMs, ExecutorService executor) {
    this.inner = inner;
    this.timeoutMs = timeoutMs;
    this.executor = executor;
    this.ownsExecutor = false;
  }

  @Override
  public Result check(String predicate, Map<String, Object> bindings) {
    queryCount.incrementAndGet();
    if (timeoutMs <= 0) {
      return inner.check(predicate, bindings);
    }
    Future<Result> f = executor.submit(() -> inner.check(predicate, bindings));
    try {
      return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      timeoutCount.incrementAndGet();
      // Cancel the query. The worker thread may or may not notice, depending on whether the
      // inner bridge checks for interruption; either way the caller stops waiting for it.
      f.cancel(true);
      return Result.UNKNOWN;
    } catch (InterruptedException ie) {
      f.cancel(true);
      Thread.currentThread().interrupt();
      return Result.UNKNOWN;
    } catch (java.util.concurrent.ExecutionException ee) {
      // The inner bridge threw. Surface it as UNKNOWN rather than propagating: one bad
      // predicate should not take down the analysis.
      return Result.UNKNOWN;
    }
  }

  @Override
  public Boolean implies(String antecedent, String consequent, Map<String, Object> bindings) {
    queryCount.incrementAndGet();
    if (timeoutMs <= 0) {
      return inner.implies(antecedent, consequent, bindings);
    }
    Future<Boolean> f = executor.submit(() -> inner.implies(antecedent, consequent, bindings));
    try {
      return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      timeoutCount.incrementAndGet();
      f.cancel(true);
      return null;
    } catch (InterruptedException ie) {
      f.cancel(true);
      Thread.currentThread().interrupt();
      return null;
    } catch (java.util.concurrent.ExecutionException ee) {
      return null;
    }
  }

  @Override
  public Set<String> referencedVars(String predicate) {
    return inner.referencedVars(predicate);
  }

  /** How many queries have been issued through this bridge. */
  public long queryCount() {
    return queryCount.get();
  }

  /** How many queries hit the timeout. */
  public long timeouts() {
    return timeoutCount.get();
  }

  /** The per-query timeout in milliseconds; 0 means unlimited. */
  public long timeoutMs() {
    return timeoutMs;
  }

  /**
   * Releases the worker pool, if this bridge owns it.
   *
   * <p>Safe to call more than once.
   */
  @Override
  public void close() {
    if (!ownsExecutor) return;
    executor.shutdownNow();
  }
}
