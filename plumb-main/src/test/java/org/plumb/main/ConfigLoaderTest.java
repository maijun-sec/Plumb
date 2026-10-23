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
