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
package org.plumb.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuleRegistryTest {

  @Test
  void hasBuiltinDefaults() {
    RuleRegistry r = new RuleRegistry().withBuiltinDefaults();
    assertThat(r.all()).isNotEmpty();
    assertThat(r.byName("TAINT_SINK_SQL_INJECTION")).isPresent();
    assertThat(r.byId(RuleRegistry.TAINT_SINK_SQL_EXECUTE)).isPresent();
  }

  @Test
  void rejectsDuplicateUuid() {
    RuleRegistry r = new RuleRegistry();
    r.register(
        new RuleDef(
            new RuleId("id-1", "R1"),
            "x",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            java.util.List.of(),
            "X",
            true));
    assertThatThrownBy(
            () ->
                r.register(
                    new RuleDef(
                        new RuleId("id-1", "R2"),
                        "y",
                        RuleDef.Severity.LOW,
                        RuleDef.Type.STYLE,
                        java.util.List.of(),
                        "Y",
                        true)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate rule UUID");
  }
}
