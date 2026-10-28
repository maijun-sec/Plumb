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

import java.util.Map;
import org.junit.jupiter.api.Test;

/** verify the YAML config loader picks up the 7 unified-engine checker flags. */
class R323ConfigLoaderUnifiedTest {

  @Test
  void allSevenUnifiedFlagsAreReadFromYaml() {
    String yaml =
        """
        inputs:
          - /tmp/x.java
        checks:
          taint: false
          divByZero: false
          resourceLeak: false
          nullDeref: false
          hardcodedCred: false
          intOverflow: false
          arrayBounds: false
          tryCatchFinally: true
          offByOne: false
          equalsCalledOnNull: true
          compareWithSelf: false
          xxe: true
          returnFromFinally: false
          emptyCatchHandler: true
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runTryCatchFinally).isTrue();
    assertThat(cfg.runOffByOne).isFalse();
    assertThat(cfg.runEqualsCalledOnNull).isTrue();
    assertThat(cfg.runCompareWithSelf).isFalse();
    assertThat(cfg.runXxe).isTrue();
    assertThat(cfg.runReturnFromFinally).isFalse();
    assertThat(cfg.runEmptyCatchHandler).isTrue();
  }

  @Test
  void allSevenUnifiedFlagsDefaultTrueWhenAbsent() {
    // If the YAML doesn't mention any of
    // the unified flags, they default
    // to true (matching ScanConfig's
    // field default).
    String yaml =
        """
        inputs:
          - /tmp/x.java
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runTryCatchFinally).isTrue();
    assertThat(cfg.runOffByOne).isTrue();
    assertThat(cfg.runEqualsCalledOnNull).isTrue();
    assertThat(cfg.runCompareWithSelf).isTrue();
    assertThat(cfg.runXxe).isTrue();
    assertThat(cfg.runReturnFromFinally).isTrue();
    assertThat(cfg.runEmptyCatchHandler).isTrue();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toMap(String yaml) {
    org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml();
    return (Map<String, Object>) y.load(yaml);
  }
}
