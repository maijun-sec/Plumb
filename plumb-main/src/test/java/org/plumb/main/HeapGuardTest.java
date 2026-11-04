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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The heap guard: the scan that gets out of its own way instead of dying.
 *
 * <h2>Why the guard exists</h2>
 *
 * <p>The user asked for one command that finishes, whatever the project. The CPU half of that is
 * {@link ParallelismPlan}: it picks a worker count the machine can afford. The memory half cannot
 * be settled up front, because the memory a project needs is not known until it is being analysed.
 *
 * <p>So the guard is REACTIVE, and the property that matters is that it only ever goes DOWN. A scan
 * that has seen the heap fill up is in a regime where adding workers makes things worse, and the
 * heap's recovery is not something to gamble on.
 */
class HeapGuardTest {

  @Test
  @DisplayName("a guard starts at the worker count it was given")
  void startsAtTheGivenCount() {
    HeapGuard guard = HeapGuard.forWorkers(8);
    assertThat(guard.effectiveWorkers()).isEqualTo(8);
    assertThat(guard.hasReduced()).isFalse();
    assertThat(guard.reason()).isNull();
  }

  @Test
  @DisplayName("a guard never has fewer than one worker")
  void neverGoesBelowOne() {
    HeapGuard guard = HeapGuard.forWorkers(0);
    assertThat(guard.effectiveWorkers()).isEqualTo(1);
    guard = HeapGuard.forWorkers(-5);
    assertThat(guard.effectiveWorkers()).isEqualTo(1);
  }

  @Test
  @DisplayName("a guard with one worker never reduces: there is nothing left to give up")
  void oneWorkerNeverReduces() {
    HeapGuard guard = HeapGuard.forWorkers(1);
    int before = guard.effectiveWorkers();
    guard.sample();
    assertThat(guard.effectiveWorkers()).isEqualTo(before);
  }

  @Test
  @DisplayName("sampling on a healthy heap changes nothing")
  void healthyHeapIsUnchanged() {
    // The test JVM's heap is not under pressure in a unit test, so the guard must leave the
    // worker count alone. If it reduced the pool here, every test run would be slower.
    HeapGuard guard = HeapGuard.forWorkers(8);
    guard.sample();
    assertThat(guard.effectiveWorkers())
        .as("a healthy heap must not cost the scan its workers")
        .isEqualTo(8);
    assertThat(guard.hasReduced()).isFalse();
  }

  @Test
  @DisplayName("the guard allows more work while the heap has headroom")
  void allowsMoreWorkWithHeadroom() {
    HeapGuard guard = HeapGuard.forWorkers(4);
    assertThat(guard.mayStartMoreWork())
        .as("the unit-test heap has headroom, so the guard must allow the work")
        .isTrue();
  }

  @Test
  @DisplayName("the sample interval is short enough to react")
  void sampleIntervalIsShort() {
    assertThat(HeapGuard.sampleIntervalMs())
        .as("a guard that samples every minute reacts after the damage is done")
        .isLessThanOrEqualTo(1000L);
  }
}
