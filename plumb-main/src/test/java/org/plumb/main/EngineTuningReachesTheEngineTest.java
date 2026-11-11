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
 * The knobs the configuration file declares have to reach the engine.
 *
 * <p><b>What went wrong.</b> {@code tuning.yaml} declares the worklist cap, the solver timeout, the
 * summary cache's capacity, the reverse walk's depth, the per-function timeout and the pre-scan.
 * The engine reads all of them. The runner did not: it built a tuning of its own and passed that to
 * the engine instead, so the declared values never arrived. The worklist cap that reached the
 * engine was a tenth of the declared one, the summary cache a tenth, the solver timeout a fifth,
 * and the per-function timeout was not enforced at all.
 *
 * <p>The failure is invisible from the outside. A user who lowered {@code per_function_timeout_ms}
 * to make a scan finish saw no change and concluded the knob was broken, which is worse than a knob
 * that was never declared: a declared knob teaches that it works.
 *
 * <p><b>What this pins.</b> That the engine's tuning IS the configured tuning. The claim rots
 * silently -- nothing crashes when a copy of the tuning creeps back in -- so it needs a test.
 */
class EngineTuningReachesTheEngineTest {

  @Test
  @DisplayName("the engine runs with the tuning the configuration declared")
  void theEngineRunsWithTheConfiguredTuning() {
    org.plumb.dfa.config.TuningConfig declared =
        org.plumb.dfa.config.ConfigLoader.loadFromClasspath().tuning();
    org.plumb.dfa.config.TuningConfig actual =
        new ScanRunner(new ScanConfig()).unifiedEngineConfig().tuning();

    assertThat(actual.worklistMaxSize())
        .as("the worklist cap the configuration declared")
        .isEqualTo(declared.worklistMaxSize());
    assertThat(actual.z3TimeoutMs())
        .as("the solver timeout the configuration declared")
        .isEqualTo(declared.z3TimeoutMs());
    assertThat(actual.summaryCacheSize())
        .as("the summary cache's capacity the configuration declared")
        .isEqualTo(declared.summaryCacheSize());
    assertThat(actual.reverseWalkMaxDepth())
        .as("the reverse walk's depth the configuration declared")
        .isEqualTo(declared.reverseWalkMaxDepth());
    assertThat(actual.perFunctionTimeoutMs())
        .as("the per-function timeout the configuration declared")
        .isEqualTo(declared.perFunctionTimeoutMs());
    assertThat(actual.preScanEnabled())
        .as("the pre-scan the configuration declared")
        .isEqualTo(declared.preScanEnabled());
    assertThat(actual.libraryCallClassification())
        .as("the library-call classification the configuration declared")
        .isEqualTo(declared.libraryCallClassification());
    assertThat(actual.taintCallerStringConcat())
        .as("the caller string concatenation the configuration declared")
        .isEqualTo(declared.taintCallerStringConcat());
    assertThat(actual.contextDepth())
        .as("the context depth the configuration declared")
        .isEqualTo(declared.contextDepth());
  }
}
