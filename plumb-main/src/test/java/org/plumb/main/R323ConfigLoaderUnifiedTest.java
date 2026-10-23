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
