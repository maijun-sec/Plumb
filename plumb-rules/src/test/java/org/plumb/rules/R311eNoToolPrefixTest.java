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
package org.plumb.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards against regressing the rule-ID/constant-name tool-prefix cleanup. The Plumb rule's
 * identity (ID, name, description) must not advertise its origin from PMD, SpotBugs, SonarQube
 * Java, or Checkstyle.
 *
 * <p>The alias table (foreign-key mapping to other tools' rule IDs) is exempt ? it's the data
 * source for the cross-tool coverage report and the values are by design tool-side identifiers.
 */
class R311eNoToolPrefixTest {

  private static final List<Pattern> TOOL_PATTERNS =
      List.of(
          Pattern.compile("-pmd-"),
          Pattern.compile("-spotbugs-"),
          Pattern.compile("-sonar-"),
          Pattern.compile("-checkstyle-"));

  private static final List<Pattern> TOOL_NAME_PATTERNS =
      List.of(
          Pattern.compile("^PMD_"),
          Pattern.compile("^SPOTBUGS_"),
          Pattern.compile("^SONAR_"),
          Pattern.compile("^CHECKSTYLE_"));

  @Test
  void allRuleIdsAreToolFree() {
    RuleRegistry reg = new RuleRegistry().withBuiltinDefaults();
    for (RuleDef d : reg.all()) {
      String id = d.ruleId().id();
      for (Pattern p : TOOL_PATTERNS) {
        assertThat(p.matcher(id).find())
            .as("rule id %s must not contain %s", id, p.pattern())
            .isFalse();
      }
    }
  }

  @Test
  void constantNamesAreToolFree() {
    RuleRegistry reg = new RuleRegistry().withBuiltinDefaults();
    for (RuleDef d : reg.all()) {
      String n = d.ruleId().name();
      for (Pattern p : TOOL_NAME_PATTERNS) {
        assertThat(p.matcher(n).find())
            .as("rule name %s must not start with %s", n, p.pattern())
            .isFalse();
      }
    }
  }

  @Test
  void renamesAreVisibleAtExpectedConstants() {
    // Sanity: the renamed constants exist and resolve to
    // the renamed rule IDs.
    assertThat(RuleRegistry.ASSIGNMENT_IN_OPERAND).isEqualTo("bu-0400-assignment-in-operand");
    assertThat(RuleRegistry.AVOID_HARDCODED_IP).isEqualTo("bu-0406-avoid-hardcoded-ip");
    assertThat(RuleRegistry.SUPERFLUOUS_INSTANCEOF).isEqualTo("bu-0505-superfluous-instanceof");
    assertThat(RuleRegistry.COLLECTION_IS_EMPTY).isEqualTo("bu-0604-collection-is-empty");
    assertThat(RuleRegistry.BIG_DECIMAL_EQUALS).isEqualTo("bu-0607-big-decimal-equals");
  }

  @Test
  void oldToolPrefixedConstantsNoLongerExist() {
    // The old constant names must be gone — they used to
    // exist and were renamed.
    for (String old :
        new String[] {
          "PMD_ASSIGNMENT_IN_OPERAND", "PMD_AVOID_HARDCODED_IP",
          "SPOTBUGS_DM_DEFAULT_ENCODING", "SPOTBUGS_SIO_SUPERFLUOUS_INSTANCEOF",
          "SPOTBUGS_DMI_RANDOM_USED_ONLY_ONCE", "SPOTBUGS_DMI_BIGDECIMAL_CTOR_DOUBLE",
          "SONAR_S1155_COLLECTION_IS_EMPTY", "SONAR_S1104_CLASS_VAR_VISIBILITY",
          "SONAR_S2168_BIG_DECIMAL_EQUALS", "SONAR_S1656_SELF_ASSIGNMENT",
          "SONAR_S2699_TEST_NO_ASSERTION"
        }) {
      try {
        java.lang.reflect.Field f = RuleRegistry.class.getField(old);
        assertThat(f).as("legacy field %s must not exist", old).isNull();
      } catch (NoSuchFieldException expected) {
        // expected
      }
    }
  }
}
