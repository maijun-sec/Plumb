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
