/*
 * R-822 (2026-10-14): verify StyleContext.paramOverride()
 * falls back to the RuleRegistry's declared default when
 * no per-file override is set.
 */
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.rules.RuleRegistry;

class R822StyleContextParamOverrideTest {

  private static RuleRegistry newRegistryWithParam() {
    RuleParameter[] params = {
      new RuleParameter(
          "ignoreIfElseChain",
          "Allow empty if in if-else chain",
          RuleParameter.Type.BOOLEAN,
          "true"),
    };
    RuleDef def =
        new RuleDef(
            new RuleId("pl-9999-empty-if", "EMPTY_IF"),
            "test rule with params",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.test.EmptyIf",
            true,
            List.of(),
            List.of(params));
    return new RuleRegistry().withBuiltinDefaults(true).register(def);
  }

  @Test
  void paramOverrideFallsBackToRegistryDefault() {
    RuleRegistry r = newRegistryWithParam();
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Test.java"), "class T {}");
    StyleContext ctx = new StyleContext(ps, r);
    ctx.setCurrentRule("EMPTY_IF");
    // No override set — should return the declared default.
    assertThat(ctx.paramOverride("ignoreIfElseChain", Boolean.class)).isTrue();
  }

  @Test
  void paramOverrideWinsOverDefault() {
    RuleRegistry r = newRegistryWithParam();
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Test.java"), "class T {}");
    Map<String, Map<String, Object>> ruleParams = new HashMap<>();
    Map<String, Object> t = new HashMap<>();
    t.put("ignoreIfElseChain", Boolean.FALSE);
    ruleParams.put("EMPTY_IF", t);
    StyleContext ctx = new StyleContext(ps, r, ruleParams);
    ctx.setCurrentRule("EMPTY_IF");
    // Override (false) beats the declared default (true).
    assertThat(ctx.paramOverride("ignoreIfElseChain", Boolean.class)).isFalse();
  }
}
