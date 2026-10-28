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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R761 audit (Oct 2026): per-rule parameter overrides from {@code checks.<ruleId>.params.<name>}
 * now flow into {@link StyleContext} (and ultimately into each checker's {@code
 * paramString/paramInt/paramBool} lookup) instead of being silently ignored.
 */
class R761ParamsTest {

  @Test
  void parsesCheckParams() throws Exception {
    Path tmp = Files.createTempFile("r761-params", ".yaml");
    Files.writeString(
        tmp,
        """
        inputs:
          - src/main/java
        checks:
          line-length:
            params:
              max: 80
              ignoreComments: false
          magic-number:
            params:
              ignoreNumbers: "100,200,300"
        """);
    try {
      ScanConfig cfg = ConfigLoader.load(tmp);
      assertThat(cfg.ruleParams).hasSize(2);
      Map<String, Object> lineParams = cfg.ruleParams.get("line-length");
      assertThat(lineParams).isNotNull();
      assertThat(lineParams.get("max")).isEqualTo(80);
      assertThat(lineParams.get("ignoreComments")).isEqualTo(false);
      Map<String, Object> magicParams = cfg.ruleParams.get("magic-number");
      assertThat(magicParams).isNotNull();
      assertThat(magicParams.get("ignoreNumbers")).isEqualTo("100,200,300");
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void emptyConfigNoParams() throws Exception {
    Path tmp = Files.createTempFile("r761-empty", ".yaml");
    Files.writeString(
        tmp,
        """
        inputs:
          - src/main/java
        """);
    try {
      ScanConfig cfg = ConfigLoader.load(tmp);
      assertThat(cfg.ruleParams).isEmpty();
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void paramsCoerceStringValues() throws Exception {
    // User wrote "max: \"80\"" (string) — the
    // StyleContext lookup coerces at read time so
    // it still works.
    Path tmp = Files.createTempFile("r761-coerce", ".yaml");
    Files.writeString(
        tmp,
        """
        inputs:
          - src/main/java
        checks:
          line-length:
            params:
              max: "80"
        """);
    try {
      ScanConfig cfg = ConfigLoader.load(tmp);
      // Raw value kept as String; StyleContext.coerce
      // handles the int conversion at lookup time.
      assertThat(cfg.ruleParams.get("line-length").get("max")).isEqualTo("80");
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
