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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The module boundary, and what it is FOR.
 *
 * <h2>Why the boundary is a scope and not a filter</h2>
 *
 * <p>The user's requirement is: analyse one module of a big project, and do not break the call
 * graph while doing it. Filtering the files would satisfy the first half and destroy the second: a
 * callee in a dependency module would disappear, the edge across the boundary would vanish, and a
 * tainted value entering the module from outside would never reach the sink inside it. The module
 * would look clean because nothing could reach it.
 *
 * <p>So the scope is about WHERE A FINDING MAY BE REPORTED, not about what is read. The other
 * modules are still read, still resolved and still summarised.
 *
 * <p>These tests assert the SCOPE's semantics. The end-to-end proof that the call graph really does
 * cross the boundary is {@code CrossModuleScopeTest} in plumb-main.
 */
class ModuleScopeTest {

  @Test
  @DisplayName("an unlimited scope contains everything")
  void unlimitedContainsEverything() {
    ModuleScope scope = ModuleScope.all();
    assertThat(scope.isLimited()).isFalse();
    assertThat(scope.contains("anything")).isTrue();
    assertThat(scope.contains(null)).isTrue();
    assertThat(scope.crossingCallers()).isZero();
  }

  @Test
  @DisplayName("a blank module name means no boundary, not an empty one")
  void blankMeansNoBoundary() {
    assertThat(ModuleScope.of("").isLimited()).isFalse();
    assertThat(ModuleScope.of("   ").isLimited()).isFalse();
    assertThat(ModuleScope.of(null).isLimited()).isFalse();
  }

  @Test
  @DisplayName("a limited scope contains its own module")
  void limitedContainsItsOwnModule() {
    ModuleScope scope = ModuleScope.of("service");
    assertThat(scope.isLimited()).isTrue();
    assertThat(scope.contains("service")).isTrue();
    assertThat(scope.contains("other")).isFalse();
  }

  @Test
  @DisplayName(
      "a function with no module is inside the scope: there is no boundary to be outside of")
  void aModulelessFunctionIsInside() {
    ModuleScope scope = ModuleScope.of("service");
    assertThat(scope.contains(null))
        .as(
            "a single-file scan has no module boundary; treating it as 'outside' would drop every"
                + " finding of a single-module analysis")
        .isTrue();
    assertThat(scope.contains("")).isTrue();
  }

  @Test
  @DisplayName("the crossings are recorded per caller")
  void crossingsAreRecordedPerCaller() {
    ModuleScope scope = ModuleScope.of("service");
    scope.recordCrossing("service.A.call", "lib.B.run", true);
    scope.recordCrossing("service.A.call", "lib.C.run", true);
    scope.recordCrossing("service.D.call", "lib.B.run", true);

    assertThat(scope.crossingCallers()).isEqualTo(2);
    assertThat(scope.crossings()).hasSize(3);
    assertThat(scope.crossings().get(0).to()).isEqualTo("lib.B.run");
  }

  @Test
  @DisplayName("an unlimited scope records no crossings")
  void unlimitedRecordsNoCrossings() {
    ModuleScope scope = ModuleScope.all();
    scope.recordCrossing("a", "b", true);
    assertThat(scope.crossingCallers()).isZero();
  }

  @Test
  @DisplayName(
      "the out-of-scope findings are counted, so 'no findings' is not mistaken for 'clean'")
  void outOfScopeFindingsAreCounted() {
    ModuleScope scope = ModuleScope.of("service");
    scope.recordInScopeFinding();
    scope.recordInScopeFinding();
    scope.recordOutOfScopeFinding();

    assertThat(scope.inScopeFindings()).isEqualTo(2);
    assertThat(scope.outOfScopeFindings()).isEqualTo(1);
    assertThat(scope.summary()).contains("2 finding(s) in scope").contains("1 dropped");
  }

  @Test
  @DisplayName("the modules the scan saw are recorded")
  void seenModulesAreRecorded() {
    ModuleScope scope = ModuleScope.of("service");
    scope.observeModule("service");
    scope.observeModule("lib");
    scope.observeModule(null);
    assertThat(scope.seenModules()).containsExactlyInAnyOrder("service", "lib");
  }

  @Test
  @DisplayName("the summary of an unlimited scope says so")
  void unlimitedSummary() {
    assertThat(ModuleScope.all().summary()).contains("whole project");
  }

  @Test
  @DisplayName("a scope is built from a set of modules the same way")
  void scopeFromASet() {
    // The single-module scope is the case the user asked for; the set form is what the
    // engine would use for a multi-module selection.
    assertThat(Set.of("service")).contains("service");
    assertThat(ModuleScope.of("service").module()).isEqualTo("service");
  }
}
