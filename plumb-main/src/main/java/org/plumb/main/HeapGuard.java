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
 * The heap guard: the scan notices that it is running out of memory and gets out of the way.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The user's requirement is blunt: give it one command, let it decide how many workers to use,
 * and let it finish -- without dying because of the CPU or the memory.
 *
 * <p>The CPU half is {@link ParallelismPlan}: it picks a worker count that the machine can afford.
 * The memory half cannot be settled up front, because the memory a project needs is not known until
 * it is being analysed. A project with a thousand generated classes and a deep call graph needs far
 * more heap per worker than a project with forty handwritten ones.
 *
 * <p>So the guard is REACTIVE. It watches the heap while the scan runs, and when the headroom drops
 * below a floor it shrinks the pool that the analysis phase uses.
 *
 * <h2>What it does NOT do</h2>
 *
 * <p>It does not promise that the scan cannot run out of memory. A single function can allocate
 * more than the whole heap, and no amount of scheduling fixes that. What it promises is that a scan
 * under heap PRESSURE reduces its own parallelism instead of dying, and that the reduction is
 * visible in the log rather than silent.
 *
 * <p>The other half of the promise is the {@link OutOfMemoryError} catch in the worker: an OOM
 * inside one function does not take the whole scan down. The function is reported as unanalysed,
 * and the scan continues with the rest.
 */
final class HeapGuard {

  /**
   * The fraction of the heap below which the guard starts reducing the worker count.
   *
   * <p>0.25 means: when a quarter of the heap is still free, the scan starts shedding workers. The
   * threshold is deliberately not lower -- reacting at the last megabyte is reacting too late,
   * because by then the allocation that triggered it has already happened.
   */
  static final double LOW_HEADROOM_RATIO = 0.25;

  /**
   * How much of the heap the guard aims to keep free, as a fraction of the maximum.
   *
   * <p>The target is a little above the floor, so the guard settles instead of oscillating between
   * "reduce" and "restore".
   */
  static final double TARGET_HEADROOM_RATIO = 0.35;

  /** The heap guard samples. */
  private static final long SAMPLE_INTERVAL_MS = 500L;

  /** The number of workers the guard has reduced the pool to. */
  private volatile int effectiveWorkers;

  /** Whether the guard has already reduced the pool. */
  private volatile boolean reduced;

  /** Why the guard reduced the pool, for the log line. */
  private volatile String reason;

  private HeapGuard(int effectiveWorkers) {
    this.effectiveWorkers = effectiveWorkers;
  }

  /**
   * A guard for a run with the given worker count.
   *
   * @param workers the worker count the plan chose
   * @return the guard
   */
  static HeapGuard forWorkers(int workers) {
    return new HeapGuard(Math.max(1, workers));
  }

  /**
   * The worker count the analysis phase should use NOW.
   *
   * <p>This is the number the guard has settled on. It only ever goes DOWN, never back up: a scan
   * that has already seen the heap fill up is in a regime where adding workers makes things worse,
   * and the recovery of the heap is not something to gamble on.
   *
   * @return the worker count to use
   */
  int effectiveWorkers() {
    return effectiveWorkers;
  }

  /** Whether the guard has reduced the pool. */
  boolean hasReduced() {
    return reduced;
  }

  /** Why the guard reduced the pool, or {@code null}. */
  String reason() {
    return reason;
  }

  /**
   * Sample the heap and reduce the worker count if the headroom is low.
   *
   * <p>Called periodically by the scan. It is cheap: two {@code Runtime} calls and an arithmetic
   * comparison.
   *
   * @return the worker count to use now
   */
  int sample() {
    if (effectiveWorkers <= 1) return effectiveWorkers;
    long max = maxMemory();
    long used = totalMemory() - freeMemory();
    if (max <= 0) return effectiveWorkers;
    long headroom = max - used;
    if (headroom < 0) headroom = 0;
    double ratio = (double) headroom / (double) max;
    if (ratio >= LOW_HEADROOM_RATIO) return effectiveWorkers;
    // Shed half the workers, at least one. Halving converges in a few samples, and it is
    // gentler than a cliff: the workers that are still running finish their current
    // function.
    int reducedTo = Math.max(1, effectiveWorkers / 2);
    reduced = true;
    reason =
        String.format(
            "the heap fell to %.0f%% free, so the analysis pool was reduced from %d worker(s) to"
                + " %d",
            ratio * 100, effectiveWorkers, reducedTo);
    effectiveWorkers = reducedTo;
    return effectiveWorkers;
  }

  /**
   * Whether the scan may start more parallel work.
   *
   * <p>Used by the parse phase, which can start a new file's parse at any time. A parse that starts
   * when the heap is nearly full is a parse that fails.
   */
  boolean mayStartMoreWork() {
    if (effectiveWorkers <= 1) return true;
    long max = maxMemory();
    if (max <= 0) return true;
    long used = totalMemory() - freeMemory();
    long headroom = max - used;
    if (headroom < 0) headroom = 0;
    return (double) headroom / (double) max >= TARGET_HEADROOM_RATIO;
  }

  private static long maxMemory() {
    try {
      return Runtime.getRuntime().maxMemory();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private static long totalMemory() {
    try {
      return Runtime.getRuntime().totalMemory();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private static long freeMemory() {
    try {
      return Runtime.getRuntime().freeMemory();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  /** The interval the guard samples at, in milliseconds. */
  static long sampleIntervalMs() {
    return SAMPLE_INTERVAL_MS;
  }
}
