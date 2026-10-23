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
