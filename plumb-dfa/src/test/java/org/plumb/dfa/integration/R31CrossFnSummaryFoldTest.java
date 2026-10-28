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
/*
 * Test stub for the cross-function taint
 * summary-fold work.
 *
 * <p>This is a DESIGN-PHASE placeholder. The
 * actual implementation is deferred to a
 * multi-day sprint (see
 * {@code docs/R5-PLUS-CROSS-FN-DESIGN-2026-08-16.md}).
 * The test is currently {@code @Disabled}
 * because the underlying implementation
 * (Phase 1-3 of the design) is not yet
 * in place.</p>
 *
 * <p>When the implementation lands, the test
 * is enabled and the expected behavior is
 * verified:</p>
 *
 * <ul>
 *   <li><b>String passthrough survival</b>:
 *       {@code helper(tainted) -> t = arg.toLowerCase(); return t;}
 *       — taint flows through the passthrough
 *       to the caller.</li>
 *   <li><b>Cross-method chain</b>:
 *       {@code a -> b -> c -> sink} where each
 *       function is a one-liner ? taint flows
 *       through all 3 hops.</li>
 *   <li><b>Field propagation</b>:
 *       {@code this.data = tainted} in helper,
 *       then read in caller ? taint flows
 *       through the field.</li>
 *   <li><b>Path-sensitive</b>:
 *       if/else with taint in one branch —
 *       the taint fires only on that branch.</li>
 * </ul>
 */
package org.plumb.dfa.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("R31 design phase — awaiting 5-day sprint implementation")
class R31CrossFnSummaryFoldTest {

  @Test
  void stringPassthroughSurvivesFold() {
    // TODO: enable when the cross-fn phase 1 lands.
    // See docs/R5-PLUS-CROSS-FN-DESIGN-2026-08-16.md
    // for the expected behavior.
  }

  @Test
  void crossMethodChainAtoBtoCtoSink() {
    // TODO: enable when the cross-fn phase 2 lands.
  }

  @Test
  void fieldPropagationViaThis() {
    // TODO: enable when the cross-fn phase 3 lands.
    // Mirrors the FN_DEEP test case.
  }

  @Test
  void pathSensitiveBranchTaint() {
    // TODO: enable when the cross-fn phase 2 lands.
  }

  @Test
  void fnCrossR567Case() {
    // TODO: enable when the cross-fn phase 1 lands.
    // Mirrors the FN_CROSS test case.
  }
}
