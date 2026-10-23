package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * verify the YAML config loader picks up the 2 unified-engine checker flags
 * (IteratorConcurrentModification, CollectionSelfReference) that were missed.
 */
class R329ConfigLoaderR312Test {

  @Test
  void bothR312FlagsAreReadFromYaml() {
    String yaml =
        """
        inputs:
          - /tmp/x.java
        checks:
          iteratorConcurrentModification: true
          collectionSelfReference: false
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runIteratorConcurrentModification).isTrue();
    assertThat(cfg.runCollectionSelfReference).isFalse();
  }

  @Test
  void bothR312FlagsDefaultTrueWhenAbsent() {
    // If the YAML doesn't mention either of
    // the flags, they default to true
    // (matching ScanConfig's field default).
    String yaml =
        """
        inputs:
          - /tmp/x.java
        """;
    ScanConfig cfg = ConfigLoader.fromMap(toMap(yaml));
    assertThat(cfg.runIteratorConcurrentModification).isTrue();
    assertThat(cfg.runCollectionSelfReference).isTrue();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toMap(String yaml) {
    org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml();
    return (Map<String, Object>) y.load(yaml);
  }
}
