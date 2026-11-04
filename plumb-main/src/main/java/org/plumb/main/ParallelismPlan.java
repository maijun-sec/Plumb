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
package org.plumb.main;

/**
 * How many workers a scan may use, and how that number is decided.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The scan had a parallel parse phase and a sequential analysis phase, and a {@code
 * --max-parallelism} flag that nothing read. A user who set it got the same behaviour either way,
 * and the flag's own help text promised a bound that did not exist.
 *
 * <p>A scan also had no memory ceiling: the analysis was bounded in time and in work, but not in
 * heap. A large project could exhaust the heap, and the failure was an {@code OutOfMemoryError}
 * rather than a report.
 *
 * <p>So the decision is made ONCE, here, and both phases obey it.
 *
 * <h2>How the number is decided</h2>
 *
 * <ol>
 *   <li>An explicit {@code --max-parallelism N} is taken as given, clamped to {@code >= 1}.
 *   <li>Otherwise the number is derived from the machine: the available processors, bounded by
 *       {@link #MAX_AUTO_WORKERS}, because a dataflow analysis is memory-hungry per worker and a
 *       64-core machine with 8 GB of heap cannot run 64 analyses at once.
 * </ol>
 *
 * <h2>Why the heap is consulted</h2>
 *
 * <p>Each analysis worker holds its own per-function state -- the fact bag, the side-effect
 * consumer -- plus the solver's working set. The dominant cost, though, is the fact bag of the
 * functions being analysed at once. A worker that allocates a hundred megabytes of facts and a
 * machine with one gigabyte of heap can afford two workers, not sixteen.
 *
 * <p>So the automatic number is bounded by the heap divided by a per-worker allowance. When the
 * heap cannot be determined, the processor count is used on its own -- an unknown heap is not a
 * reason to refuse to run.
 *
 * <h2>The guarantee</h2>
 *
 * <p>The promise this class makes is narrow and checkable: <b>the scan uses at most this many
 * workers, and it reports the number it used.</b> It does not promise the scan will finish in any
 * particular time, because the work is bounded by the project, not by the tool.
 */
final class ParallelismPlan {

  /** The most workers an automatic choice may pick, however many processors the machine has. */
  static final int MAX_AUTO_WORKERS = 16;

  /**
   * The heap a single analysis worker is assumed to need, in megabytes.
   *
   * <p>This is the allowance the heap is divided by. It is deliberately generous: the point is to
   * avoid a runaway on a small heap, not to squeeze the last worker out of a large one.
   */
  static final int MB_PER_WORKER = 512;

  /** The number of workers to use. Always at least 1. */
  final int workers;

  /** How the number was decided, for the log line. */
  final String reason;

  private ParallelismPlan(int workers, String reason) {
    this.workers = workers;
    this.reason = reason;
  }

  /**
   * Decide the worker count.
   *
   * @param requested the user's {@code --max-parallelism}, or 0 for "decide for me"
   * @param processors the available processors
   * @param maxHeapMegabytes the JVM's maximum heap in megabytes, or -1 when it cannot be determined
   */
  static ParallelismPlan decide(int requested, int processors, long maxHeapMegabytes) {
    if (requested > 0) {
      // An explicit request is honoured, but it is still bounded by the heap: a user who asks for
      // 64 workers on a 1 GB heap gets a scan that dies, and a scan that dies is worse than a
      // scan that is slower.
      int capped = requested;
      String note = "requested";
      long heapLimit = heapBound(maxHeapMegabytes);
      if (heapLimit > 0 && capped > heapLimit) {
        capped = (int) heapLimit;
        note = "requested, capped by the heap";
      }
      return new ParallelismPlan(Math.max(1, capped), note);
    }

    int byCpu = Math.max(1, processors);
    String note = "auto";
    if (byCpu > MAX_AUTO_WORKERS) {
      byCpu = MAX_AUTO_WORKERS;
      note = "auto, capped at " + MAX_AUTO_WORKERS;
    }
    long heapLimit = heapBound(maxHeapMegabytes);
    if (heapLimit > 0 && byCpu > heapLimit) {
      byCpu = (int) heapLimit;
      note = "auto, capped by the heap";
    }
    return new ParallelismPlan(Math.max(1, byCpu), note);
  }

  /**
   * How many workers the heap can afford.
   *
   * @return the bound, or 0 when the heap is unknown or too small to bound anything
   */
  private static long heapBound(long maxHeapMegabytes) {
    if (maxHeapMegabytes <= 0) return 0;
    long byHeap = maxHeapMegabytes / MB_PER_WORKER;
    // One worker is always affordable; a bound of zero would mean "do not run at all".
    return Math.max(1, byHeap);
  }

  /**
   * The JVM's maximum heap, in megabytes, or -1 when it cannot be determined.
   *
   * <p>{@code Runtime.getRuntime().maxMemory()} reports the CURRENT heap, which grows as the JVM
   * needs it. On a machine where the JVM has not been given an explicit {@code -Xmx}, that number
   * is a fraction of the physical memory, and it is the best estimate available at the time the
   * scan starts.
   */
  static long maxHeapMegabytes() {
    try {
      return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    } catch (RuntimeException e) {
      return -1;
    }
  }

  /** A one-line description for the log. */
  String describe() {
    return workers + " worker(s) (" + reason + ")";
  }
}
