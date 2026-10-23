package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** verify the YAML config loader picks up the cross-function UncaughtExceptionPropagator flag. */
class R324ConfigLoaderUncaughtTest {

  @Test
  void uncaughtPropagatorFlagIsReadFromYaml() {
    String yaml =
        """
        inputs:
          - /tmp/x.java
        checks:
          uncaughtExceptionPropagator: false
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runUncaughtExceptionPropagator).isFalse();
  }

  @Test
  void uncaughtPropagatorFlagDefaultsTrueWhenAbsent() {
    String yaml =
        """
        inputs:
          - /tmp/x.java
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runUncaughtExceptionPropagator).isTrue();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toMap(String yaml) {
    org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml();
    return (Map<String, Object>) y.load(yaml);
  }
}
