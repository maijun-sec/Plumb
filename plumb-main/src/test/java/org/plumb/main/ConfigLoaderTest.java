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
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

  @Test
  void loadsYaml() throws Exception {
    Path tmp = Files.createTempFile("cfg", ".yaml");
    Files.writeString(
        tmp,
        """
        inputs:
          - src/main/java
          - src/test/java
        output:
          path: out.ndjson
          format: NDJSON
        checks:
          taint: true
          style: false
          errorMessageInfoLeak: false
          commentInfoLeak: false
          resourceExhaustion: false
        rules:
          disabled:
            - STYLE_UNUSED_IMPORT
        """);
    try {
      ScanConfig cfg = ConfigLoader.load(tmp);
      assertThat(cfg.inputs).hasSize(2);
      assertThat(cfg.outputPath.toString()).isEqualTo("out.ndjson");
      assertThat(cfg.outputFormat).isEqualTo(ScanConfig.OutputFormat.NDJSON);
      assertThat(cfg.runTaint).isTrue();
      assertThat(cfg.runStyle).isFalse();
      // R761: the 3 keys below were silently-ignored before.
      // Now `checks.X: false` actually flips the flag.
      assertThat(cfg.runErrorMessageInfoLeak).isFalse();
      assertThat(cfg.runCommentInfoLeak).isFalse();
      assertThat(cfg.runResourceExhaustion).isFalse();
      assertThat(cfg.disabledRuleNames).contains("STYLE_UNUSED_IMPORT");
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
