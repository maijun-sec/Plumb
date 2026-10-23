/*
 * R1123 (2026-10-23): the documented YAML runtime-parameter override path
 * must actually reach the checker.
 *
 * docs/usage/CLI.md §4.4 and docs/rules/RULE-STYLE-OP.md §9.1 both claim:
 *
 *     checks:
 *       line-length:
 *         params:
 *           max: 120
 *
 * overrides LineLengthCheck's hard-coded default of 100.
 *
 * Two halves have to hold, and only the first was ever tested:
 *
 *   1. PARSING  -- ConfigLoader puts the block into ScanConfig.ruleParams.
 *                  Covered by R761ParamsTest (plumb-main).
 *   2. DELIVERY -- the override reaches the checker and changes its result.
 *                  Nothing covered this, so a doc could describe the feature
 *                  while the feature did nothing.
 *
 * This test pins the delivery half end to end, and pins the ONE thing the
 * docs must keep saying: the outer key is the checker's `name()`
 * (kebab-case slug). The lookup key is `checker.name() + "." + paramName`
 * -- a direct string concatenation with NO alias resolution -- so the
 * canonical rule id and the UPPER_SNAKE short name must NOT work.
 * Getting that wrong is a silent no-op for the user, not an error.
 */
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.style.LineLengthCheck;

class R1123ParamOverrideDeliveryTest {

  /** A 102-char source line: over the default max=100, under max=120. */
  private static ParsedSource sourceWithLongLine() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 100; i++) sb.append('x');
    String code = "class T {\n  void m() {\n    String s = \"" + sb + "\";\n  }\n}\n";
    return new JavaSourceParser().parseFromString(Path.of("T.java"), code);
  }

  private static int findingsWith(Map<String, Map<String, Object>> ruleParams) {
    ParsedSource ps = sourceWithLongLine();
    RuleRegistry reg = new RuleRegistry().withBuiltinDefaults(true);
    StyleContext ctx = new StyleContext(ps, reg, ruleParams);
    AstChecker checker = new LineLengthCheck();
    AstDispatcher d = new AstDispatcher();
    d.register(checker);
    d.run(ctx);
    return ctx.findings().size();
  }

  private static Map<String, Map<String, Object>> params(String outerKey, int max) {
    Map<String, Object> inner = new HashMap<>();
    inner.put("max", Integer.valueOf(max));
    Map<String, Map<String, Object>> outer = new HashMap<>();
    outer.put(outerKey, inner);
    return outer;
  }

  @Test
  void defaultMaxIsOneHundredAndTheLineIsReported() {
    // Control: with no override the 102-char line is over the default max=100.
    assertThat(findingsWith(new HashMap<>())).isEqualTo(1);
  }

  @Test
  void overrideUnderTheCheckerNameSuppressesTheFinding() {
    // The documented path: outer key == checker.name().
    assertThat(findingsWith(params("line-length", 120))).isZero();
  }

  @Test
  void canonicalRuleIdIsNotAValidOuterKey() {
    // The lookup key is `checker.name() + "." + paramName` -- a plain string
    // concatenation. The canonical rule id is NOT resolved, so this override
    // is a silent no-op and the finding still fires.
    assertThat(findingsWith(params("ast-style-line-length", 120))).isEqualTo(1);
  }

  @Test
  void upperSnakeShortNameIsNotAValidOuterKey() {
    // Same reason: no alias resolution on the outer key.
    assertThat(findingsWith(params("LINE_LENGTH", 120))).isEqualTo(1);
  }

  @Test
  void loweringTheMaxIncreasesTheFindings() {
    // The override is not just a "suppress" switch -- it moves the threshold
    // in both directions. A max of 40 catches a line that the default missed.
    assertThat(findingsWith(params("line-length", 40))).isEqualTo(1);
  }
}
