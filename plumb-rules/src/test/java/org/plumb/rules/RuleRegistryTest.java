package org.plumb.rules;

import org.plumb.rules.RuleDef;
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
