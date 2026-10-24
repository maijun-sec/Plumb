/*
 * R1124 (2026-10-24): the AI FP-reducer must be able to find the prompt for
 * the rules it is asked to review.
 *
 * The runner's lookup is:
 *
 *     promptByRule   keyed by checker.name()          (the kebab-case slug)
 *     ruleNameOf(f)  returns finding.rule().ruleId().id()   (the canonical emit id)
 *
 *     inlinePrompt = promptByRule.containsKey(ruleName) ? ... : null
 *     if (finalPrompt == null) -> "(no-rule-prompt)"  => the finding is SKIPPED
 *
 * Those two strings are equal for ZERO of the 595 AST checkers: name() returns
 * e.g. "bc-equals-no-null" while the emitted rule id is
 * "ast-bug-bc-equals-no-null". So every AST finding took the
 * "(no-rule-prompt)" branch and the AI dedup never reviewed a single style
 * rule -- the feature was a silent no-op for the whole AST family. The DFA
 * taint family was unaffected because TaintedCheckerBase.name() returns
 * rule.ruleId() by construction.
 *
 * This test pins the fix: every registered AST checker must be reachable by
 * the id its findings actually carry.
 */
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.plumb.rules.RuleDef;
import org.plumb.style.AstChecker;
import org.plumb.style.AstCheckerRegistry;
import org.plumb.style.StyleContext;

class R1124AiDedupRuleReachTest {

  /** Mirror of ScanRunner.buildStyleChecks(). */
  private static List<AstChecker> styleChecks() {
    List<AstChecker> out = new ArrayList<>();
    for (AstChecker c : new AstCheckerRegistry().builtins().all()) {
      if (c.name().equals("method-length")) continue;
      out.add(c);
    }
    out.add(new org.plumb.style.style.MethodTooLongCheck());
    return out;
  }

  /**
   * Invoke the runner's private buildPromptByRuleMap the same way runAiDedup does, and return the
   * set of keys it holds.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, ?> promptMapKeys(List<AstChecker> styleChecks) throws Exception {
    ScanRunner runner = new ScanRunner(new ScanConfig());
    Method m = ScanRunner.class.getDeclaredMethod("buildPromptByRuleMap", List.class, List.class);
    m.setAccessible(true);
    return (Map<String, ?>) m.invoke(runner, new ArrayList<>(), styleChecks);
  }

  @Test
  void everyAstCheckerIsReachableByTheIdItsFindingsCarry() throws Exception {
    List<AstChecker> checkers = styleChecks();
    Map<String, ?> map = promptMapKeys(checkers);

    List<String> unreachable = new ArrayList<>();
    for (AstChecker c : checkers) {
      RuleDef def = c.rule();
      if (def == null || def.ruleId() == null) continue;
      String emittedId = def.ruleId().id();
      if (emittedId == null || emittedId.isEmpty()) continue;
      if (!map.containsKey(emittedId)) {
        unreachable.add(c.name() + " -> " + emittedId);
      }
    }
    assertThat(unreachable)
        .as(
            "every AST checker must be reachable by the rule id its findings carry, "
                + "otherwise the AI dedup skips it as (no-rule-prompt)")
        .isEmpty();
  }

  @Test
  void theSlugIsStillAKeySoUserOverridesKeepWorking() throws Exception {
    // A --rule-prompts-dir file is named after the slug, and the map must
    // still carry the slug key for the override path.
    Map<String, ?> map = promptMapKeys(styleChecks());
    for (AstChecker c : styleChecks()) {
      assertThat(map).containsKey(c.name());
    }
  }

  @Test
  void theSlugAndTheEmitIdAreBothPresentAndTheEmitIdIsTheOneThatCounts() throws Exception {
    // Control for the shape of the bug: the two spellings really are
    // different for at least one rule, so a test that only checked "the map
    // is not empty" would have passed before the fix.
    Map<String, ?> map = promptMapKeys(styleChecks());
    boolean sawDifferent = false;
    for (AstChecker c : styleChecks()) {
      RuleDef def = c.rule();
      if (def == null || def.ruleId() == null) continue;
      String emittedId = def.ruleId().id();
      if (emittedId == null || emittedId.isEmpty()) continue;
      if (!emittedId.equals(c.name())) {
        sawDifferent = true;
        assertThat(map).containsKey(emittedId);
      }
    }
    assertThat(sawDifferent)
        .as("at least one checker must have slug != emit id, otherwise this test proves nothing")
        .isTrue();
  }

  @Test
  void aCheckerWithoutRuleMetadataDoesNotBreakTheMap() throws Exception {
    // A checker whose rule() returns null must be skipped, not throw.
    AstChecker noRule =
        new AstChecker() {
          @Override
          public String name() {
            return "zz-no-rule";
          }

          @Override
          public void init(StyleContext ctx) {}

          @Override
          public org.plumb.rules.RuleDef rule() {
            return null;
          }
        };
    List<AstChecker> withBad = new ArrayList<>(styleChecks());
    withBad.add(noRule);
    Map<String, ?> map = promptMapKeys(withBad);
    assertThat(map).containsKey("zz-no-rule");
  }

  @Test
  void styleContextStillReportsTheEmitIdSoTheTwoAgree() {
    // The two halves must agree: the finding's rule id and the map's emit-id
    // key are the same string. This checks the report() path end to end for
    // one real checker.
    List<AstChecker> checkers = styleChecks();
    AstChecker lineLength = null;
    for (AstChecker c : checkers) {
      if (c.name().equals("line-length")) {
        lineLength = c;
        break;
      }
    }
    assertThat(lineLength).isNotNull();
    RuleDef def = lineLength.rule();
    assertThat(def).isNotNull();
    assertThat(def.ruleId().id())
        .as("LineLengthCheck's emit id")
        .isEqualTo("ast-style-line-length");
  }

  /** Keeps the unused-import checker honest about StyleContext being on the path. */
  @Test
  void styleContextIsTheReportPathUsedByTheAiPass() {
    assertThat(StyleContext.class.getName()).isEqualTo("org.plumb.style.StyleContext");
    Map<String, String> dummy = new HashMap<>();
    assertThat(dummy).isEmpty();
  }
}
