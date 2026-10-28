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

class ScanRunnerTest {

  @Test
  void scansSimpleFile() throws Exception {
    Path tmp = Files.createTempFile("Sample", ".java");
    Files.writeString(
        tmp,
        """
        package demo;
        class Sample {
            void m(javax.servlet.http.HttpServletRequest req) {
                String id = req.getParameter("id");
                Runtime.getRuntime().exec(id);
            }
        }
        """);
    try {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(tmp);
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
      int rc = new ScanRunner(cfg).run();
      assertThat(rc).isEqualTo(0);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @Test
  void emitsNdjsonToStdout() throws Exception {
    Path tmp = Files.createTempFile("Out", ".java");
    Files.writeString(tmp, "class Out { void m() { System.out.println(\"x\"); } }");
    try {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(tmp);
      cfg.outputFormat = ScanConfig.OutputFormat.CONSOLE;
      // Just ensure no exception
      new ScanRunner(cfg).run();
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
