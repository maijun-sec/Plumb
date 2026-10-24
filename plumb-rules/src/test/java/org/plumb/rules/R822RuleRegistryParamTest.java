/*
 * R-822 (2026-10-14): verify the new RuleRegistry.param()
 * method resolves parameter defaults from a registered
 * rule's parameters() list.
 */
package org.plumb.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class R822RuleRegistryParamTest {

  private static final RuleParameter[] EMPTY_IF_PARAMS = {
    new RuleParameter(
        "ignoreAfterReturn", "Ignore empty if after return", RuleParameter.Type.BOOLEAN, "false"),
    new RuleParameter(
        "ignoreIfElseChain", "Allow empty if in if-else chain", RuleParameter.Type.BOOLEAN, "true"),
  };

  @Test
  void paramReturnsDeclaredDefaultById() {
    RuleDef def =
        new RuleDef(
            new RuleId("pl-9999-test-rule", "TEST_RULE"),
            "test rule with params",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.test.TestRule",
            true,
            List.of(),
            List.of(EMPTY_IF_PARAMS));
    RuleRegistry r = new RuleRegistry().register(def);

    Boolean byId = r.param("pl-9999-test-rule", "ignoreIfElseChain", Boolean.class);
    assertThat(byId).isTrue();
  }

  @Test
  void paramReturnsDeclaredDefaultByName() {
    RuleDef def =
        new RuleDef(
            new RuleId("pl-9999-test-rule", "TEST_RULE"),
            "test rule with params",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.test.TestRule",
            true,
            List.of(),
            List.of(EMPTY_IF_PARAMS));
    RuleRegistry r = new RuleRegistry().register(def);

    System.out.println("[R822] byId=" + r.byId("pl-9999-test-rule"));
    System.out.println("[R822] byName=" + r.byName("TEST_RULE"));
    System.out.println("[R822] param=" + r.param("TEST_RULE", "ignoreAfterReturn", Boolean.class));
    Boolean byName = r.param("TEST_RULE", "ignoreAfterReturn", Boolean.class);
    assertThat(byName).isFalse();
  }

  @Test
  void paramReturnsNullForUnknownRule() {
    RuleRegistry r = new RuleRegistry();
    Boolean v = r.param("nonexistent", "x", Boolean.class);
    assertThat(v).isNull();
  }

  @Test
  void paramReturnsNullForUnknownParam() {
    RuleDef def =
        new RuleDef(
            new RuleId("pl-9999-test-rule", "TEST_RULE"),
            "test rule with params",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.test.TestRule",
            true,
            List.of(),
            List.of(EMPTY_IF_PARAMS));
    RuleRegistry r = new RuleRegistry().register(def);

    Boolean v = r.param("pl-9999-test-rule", "noSuchParam", Boolean.class);
    assertThat(v).isNull();
  }

  @Test
  void paramResolvesByAlias() {
    RuleDef def =
        new RuleDef(
            new RuleId("pl-9999-test-rule", "TEST_RULE"),
            "test rule with params",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.test.TestRule",
            true,
            List.of("SonarQube:java:S00100", "PMD:TestRule"),
            List.of(EMPTY_IF_PARAMS));
    RuleRegistry r = new RuleRegistry().register(def);

    Boolean byAlias = r.param("SonarQube:java:S00100", "ignoreIfElseChain", Boolean.class);
    assertThat(byAlias).isTrue();
  }

  @Test
  void paramResolvesByKebabCaseSlug() {
    // R-823: many checkers expose name() as
    // kebab-case (e.g. "empty-block") but their
    // DEFAULT_RULE uses PMD-style UPPER_SNAKE
    // (e.g. "EMPTY_BLOCK"). StyleContext stores
    // currentRule = c.name() (kebab) and queries
    // the registry with it; param() must map
    // kebab → UPPER_SNAKE to find the rule.
    RuleDef def =
        new RuleDef(
            new RuleId("pl-0725-empty-block", "EMPTY_BLOCK"),
            "Empty block statement",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.EmptyBlockCheck",
            true,
            List.of(),
            List.of(EMPTY_IF_PARAMS));
    RuleRegistry r = new RuleRegistry().register(def);

    Boolean bySlug = r.param("empty-block", "ignoreIfElseChain", Boolean.class);
    assertThat(bySlug).isTrue();
  }
}
