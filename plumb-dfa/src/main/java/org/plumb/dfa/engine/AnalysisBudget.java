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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.plumb.dfa.config.TuningConfig;

/**
 * The analysis budget, and the record of where it ran out.
 *
 * <p><b>Why this class exists.</b> {@link TuningConfig} has always carried {@code contextDepth},
 * {@code worklistMaxSize}, {@code z3TimeoutMs}, {@code perFunctionTimeoutMs}, {@code
 * reverseWalkMaxDepth} and {@code summaryCacheSize}. Until now the engine read <em>none</em> of
 * them: a budget that is declared but not consumed is a promise the analysis does not keep, and a
 * user who lowers {@code per_function_timeout_ms} sees no change and concludes the knob is broken.
 *
 * <p>So the budget has two jobs, and they are the same job seen from two sides:
 *
 * <ul>
 *   <li><b>Cap the work.</b> Each dimension is checked before the work is done, and when a cap is
 *       hit the engine stops doing that work rather than running unbounded.
 *   <li><b>Say so out loud.</b> Every cap that fires is recorded here with the dimension, the
 *       limit, and the thing that was being analysed when it fired. Nothing is truncated silently.
 * </ul>
 *
 * <p><b>Silence is the failure mode this class exists to prevent.</b> A silently truncated analysis
 * and a complete analysis produce the same findings so far as the user can tell -- the difference
 * only shows up as a missing vulnerability. That is the worst possible failure mode for a tool
 * whose users hold it to a low false-negative bar, because the user has no way to know which
 * findings are missing. A budget that reports its exhaustion turns "the tool missed it" into "the
 * tool stopped here, and here is why".
 *
 * <h2>What exhaustion does and does not mean</h2>
 *
 * <p>Exhaustion is <b>not</b> a finding. It says "the analysis of this function is incomplete", not
 * "this code is vulnerable". It is reported through {@link BudgetReport}, a separate channel from
 * {@link org.plumb.reporter.Finding}, and it never suppresses a finding either: a truncated
 * analysis still reports everything it did find. Suppressing findings on exhaustion would trade a
 * false negative for a false positive and solve nothing.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The counters are {@link AtomicLong} and the exhaustion records are guarded by the instance
 * lock, so the engine's parallel workers can share one budget.
 */
public final class AnalysisBudget {

  /** The dimension that ran out. */
  public enum Dimension {
    /** Wall-clock budget for analysing one function. */
    PER_FUNCTION_TIME,
    /** Wall-clock budget for a single solver query. */
    SOLVER_TIME,
    /** Maximum number of facts tracked for one operand. */
    WORKLIST_SIZE,
    /** Maximum number of interprocedural context levels followed. */
    CONTEXT_DEPTH,
    /** Maximum reverse-walk distance when looking for a definition. */
    REVERSE_WALK_DEPTH,
    /** Maximum number of cached function summaries. */
    SUMMARY_CACHE_SIZE
  }

  /**
   * One recorded exhaustion.
   *
   * <p>The record names the dimension, the limit that was hit, and -- crucially -- <em>what</em>
   * was under analysis. "The 30-second budget ran out" is not actionable; "the 30-second budget ran
   * out while analysing {@code com.example.Thing.handle}" is.
   */
  public static final class Exhaustion {
    private final Dimension dimension;
    private final long limit;
    private final String subject;

    Exhaustion(Dimension dimension, long limit, String subject) {
      this.dimension = dimension;
      this.limit = limit;
      this.subject = subject == null ? "" : subject;
    }

    /** Which budget dimension ran out. */
    public Dimension dimension() {
      return dimension;
    }

    /** The limit that was exceeded. */
    public long limit() {
      return limit;
    }

    /** What was being analysed when the limit was hit (a function signature, usually). */
    public String subject() {
      return subject;
    }

    @Override
    public String toString() {
      return dimension
          + " limit "
          + limit
          + " exceeded"
          + (subject.isEmpty() ? "" : " while analysing " + subject);
    }
  }

  /**
   * The budget's exhaustion record, collected at the end of an analysis run.
   *
   * <p>This is the "overflow rule" the third-party analyzers emit: a visible marker that the
   * analysis was incomplete, carrying enough detail to tell the user where to look.
   */
  public static final class BudgetReport {
    private final List<Exhaustion> exhaustions;
    private final Map<Dimension, Long> limits;

    BudgetReport(List<Exhaustion> exhaustions, Map<Dimension, Long> limits) {
      this.exhaustions = Collections.unmodifiableList(new ArrayList<>(exhaustions));
      this.limits = Collections.unmodifiableMap(new LinkedHashMap<>(limits));
    }

    /** Every exhaustion recorded during the run, in the order it happened. */
    public List<Exhaustion> exhaustions() {
      return exhaustions;
    }

    /** True if the analysis completed within every budget. */
    public boolean complete() {
      return exhaustions.isEmpty();
    }

    /** The configured limit for each dimension. */
    public Map<Dimension, Long> limits() {
      return limits;
    }

    /** A one-line human-readable summary, or {@code "analysis complete within budget"} . */
    public String summary() {
      if (exhaustions.isEmpty()) {
        return "analysis complete within budget";
      }
      StringBuilder sb = new StringBuilder();
      sb.append("analysis incomplete: ")
          .append(exhaustions.size())
          .append(" budget limit(s) reached");
      for (Exhaustion e : exhaustions) {
        sb.append("; ").append(e);
      }
      return sb.toString();
    }
  }

  private final long perFunctionTimeoutMs;
  private final long solverTimeoutMs;
  private final int worklistMaxSize;
  private final int contextDepth;
  private final int reverseWalkMaxDepth;
  private final int summaryCacheSize;

  private final AtomicLong factsTracked = new AtomicLong();
  private final List<Exhaustion> exhaustions = new ArrayList<>();
  private final Object lock = new Object();

  /**
   * Builds a budget from the tuning configuration.
   *
   * <p>A non-positive limit means "no limit", which is how the configuration expresses "disabled".
   * That is distinct from a limit of zero being hit immediately.
   */
  public AnalysisBudget(TuningConfig tuning) {
    this.perFunctionTimeoutMs = tuning.perFunctionTimeoutMs();
    this.solverTimeoutMs = tuning.z3TimeoutMs();
    this.worklistMaxSize = tuning.worklistMaxSize();
    this.contextDepth = tuning.contextDepth();
    this.reverseWalkMaxDepth = tuning.reverseWalkMaxDepth();
    this.summaryCacheSize = tuning.summaryCacheSize();
  }

  /** A budget with no limits at all. Useful in tests that are not about the budget. */
  public static AnalysisBudget unlimited() {
    return new AnalysisBudget(0L, 0L, 0, 0, 0, 0);
  }

  /**
   * Builds a budget from explicit limits.
   *
   * <p>Each limit follows the same convention as {@link TuningConfig}: a non-positive value means
   * "no limit", which is how the configuration expresses "disabled". That is distinct from a limit
   * of zero being hit immediately.
   *
   * @param perFunctionTimeoutMs wall-clock budget for one function; 0 means unlimited
   * @param solverTimeoutMs wall-clock budget for one solver query; 0 means unlimited
   * @param worklistMaxSize maximum tracked facts; 0 means unlimited
   * @param contextDepth maximum interprocedural context depth
   * @param reverseWalkMaxDepth maximum reverse-walk distance
   * @param summaryCacheSize maximum cached function summaries
   */
  public AnalysisBudget(
      long perFunctionTimeoutMs,
      long solverTimeoutMs,
      int worklistMaxSize,
      int contextDepth,
      int reverseWalkMaxDepth,
      int summaryCacheSize) {
    this.perFunctionTimeoutMs = perFunctionTimeoutMs;
    this.solverTimeoutMs = solverTimeoutMs;
    this.worklistMaxSize = worklistMaxSize;
    this.contextDepth = contextDepth;
    this.reverseWalkMaxDepth = reverseWalkMaxDepth;
    this.summaryCacheSize = summaryCacheSize;
  }

  /**
   * Checks whether a per-function wall-clock budget still has room.
   *
   * @param startedAtNanos the {@code System.nanoTime()} reading taken when the function started
   * @param subject the function being analysed, for the exhaustion record
   * @return true if the analysis may continue
   */
  public boolean checkPerFunctionTime(long startedAtNanos, String subject) {
    if (perFunctionTimeoutMs <= 0) {
      return true;
    }
    long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
    if (elapsedMs <= perFunctionTimeoutMs) {
      return true;
    }
    record(Dimension.PER_FUNCTION_TIME, perFunctionTimeoutMs, subject);
    return false;
  }

  /**
   * Checks whether a solver query may run, given the time already spent in the current function.
   *
   * @param startedAtNanos the {@code System.nanoTime()} reading taken when the function started
   * @param subject the function being analysed
   * @return true if the query may run
   */
  public boolean checkSolverTime(long startedAtNanos, String subject) {
    if (solverTimeoutMs <= 0) {
      return true;
    }
    long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
    if (elapsedMs <= solverTimeoutMs) {
      return true;
    }
    record(Dimension.SOLVER_TIME, solverTimeoutMs, subject);
    return false;
  }

  /**
   * Accounts for one tracked fact, and reports exhaustion when the worklist cap is passed.
   *
   * <p>The counter is cumulative across the run; the cap applies to the total number of facts the
   * engine has tracked. This is deliberately coarse: the point is to bound the work, not to model a
   * per-function worklist precisely.
   *
   * @param subject the function being analysed
   * @return true if the caller may keep tracking facts
   */
  public boolean accountFact(String subject) {
    if (worklistMaxSize <= 0) {
      return true;
    }
    long n = factsTracked.incrementAndGet();
    if (n <= worklistMaxSize) {
      return true;
    }
    record(Dimension.WORKLIST_SIZE, worklistMaxSize, subject);
    return false;
  }

  /** The number of facts the engine has tracked so far. */
  public long factsTracked() {
    return factsTracked.get();
  }

  /**
   * The maximum interprocedural context depth the engine may follow.
   *
   * <p>Unlike the other dimensions this one is a <em>query</em>, not a cap that fires: the engine
   * asks how deep it may go before deciding to stop. The value comes straight from the tuning
   * configuration, so a configured depth of 0 means "do not follow calls at all".
   */
  public int contextDepth() {
    return contextDepth;
  }

  /** The maximum reverse-walk distance the engine may use when looking for a definition. */
  public int reverseWalkMaxDepth() {
    return reverseWalkMaxDepth;
  }

  /** The maximum number of cached function summaries the engine may keep. */
  public int summaryCacheSize() {
    return summaryCacheSize;
  }

  /** The per-function wall-clock limit in milliseconds; 0 means unlimited. */
  public long perFunctionTimeoutMs() {
    return perFunctionTimeoutMs;
  }

  /** The per-solver-query wall-clock limit in milliseconds; 0 means unlimited. */
  public long solverTimeoutMs() {
    return solverTimeoutMs;
  }

  /** The maximum number of tracked facts; 0 means unlimited. */
  public int worklistMaxSize() {
    return worklistMaxSize;
  }

  /**
   * Records an exhaustion.
   *
   * <p>Public so that a checker which discovers it has hit a limit (rather than the engine
   * discovering it) can report the same way.
   */
  public void record(Dimension dimension, long limit, String subject) {
    synchronized (lock) {
      exhaustions.add(new Exhaustion(dimension, limit, subject));
    }
  }

  /** True if any dimension has been exhausted so far. */
  public boolean anyExhausted() {
    synchronized (lock) {
      return !exhaustions.isEmpty();
    }
  }

  /** The exhaustion record for this run. */
  public BudgetReport report() {
    synchronized (lock) {
      Map<Dimension, Long> limits = new LinkedHashMap<>();
      limits.put(Dimension.PER_FUNCTION_TIME, perFunctionTimeoutMs);
      limits.put(Dimension.SOLVER_TIME, solverTimeoutMs);
      limits.put(Dimension.WORKLIST_SIZE, Long.valueOf(worklistMaxSize));
      limits.put(Dimension.CONTEXT_DEPTH, Long.valueOf(contextDepth));
      limits.put(Dimension.REVERSE_WALK_DEPTH, Long.valueOf(reverseWalkMaxDepth));
      limits.put(Dimension.SUMMARY_CACHE_SIZE, Long.valueOf(summaryCacheSize));
      return new BudgetReport(exhaustions, limits);
    }
  }
}
