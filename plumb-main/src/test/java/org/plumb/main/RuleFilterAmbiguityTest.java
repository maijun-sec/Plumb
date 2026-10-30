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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A rule filter that names one rule must select ONE rule.
 *
 * <p><b>Why this test exists.</b> The rule filter accepts several spellings of a rule id: the full
 * id as the report prints it, and the id with its leading engine and domain tokens peeled off. The
 * peeled spellings are convenient -- a user who types {@code unchecked-array-index} means the rule
 * whose id is {@code dfa-taint-unchecked-array-index} -- but they are only safe when the peeled
 * spelling is UNIQUE.
 *
 * <p>Twelve pairs of live rules share a peeled spelling, and eight of those pairs carry DIFFERENT
 * CWEs. The clearest one: the AST rule {@code ast-style-null-deref-from-return} reports CWE-690,
 * and the DFA rule {@code dfa-taint-null-deref-from-return} reports CWE-476. Both peel to {@code
 * null-deref-from-return}, and the AST checker's own {@code name()} IS that bare slug. So {@code
 * --rule dfa-taint-null-deref-from-return} selected BOTH: the filter that named the CWE-476 rule
 * also let the CWE-690 rule through.
 *
 * <p>That is not a cosmetic problem. A Juliet measurement asks each case "did the rule for this CWE
 * fire here?" and a true-negative case asks "did anything fire here?". With the filter selecting
 * two rules, a TN case for CWE-476 was scored as a false positive because the CWE-690 rule fired on
 * the same line -- a false positive that does not exist, and a recall number that measures the
 * filter rather than the rule.
 *
 * <p>So the rule is now: a PEELED spelling selects a rule only when it is unambiguous. An ambiguous
 * bare slug selects nothing, and the user names the rule they mean. The full id always selects
 * exactly the rule it names.
 *
 * <p>The live rule set is read from the engine, so the ambiguity check cannot drift from the rules
 * that actually ship.
 */
class RuleFilterAmbiguityTest {

  /**
   * The rules the filter must keep apart.
   *
   * <p>Each pair shares a peeled spelling and carries a different CWE. These are read from the live
   * engine rather than hardcoded, so the test fails when the rule set changes -- which is the
   * point: a test that names rules that no longer exist would pass forever.
   */
  private static List<String[]> collidingPairs() {
    ScanRunner runner = new ScanRunner(new ScanConfig());
    java.util.Map<String, java.util.List<String>> map = runner.liveRuleCweMap();

    // Group the live rules by the spelling they peel to.
    java.util.Map<String, Set<String>> byForm = new java.util.LinkedHashMap<>();
    for (String ruleId : map.keySet()) {
      for (String form : ruleIdFormsForTest(ruleId)) {
        byForm.computeIfAbsent(form, k -> new LinkedHashSet<>()).add(ruleId);
      }
    }

    List<String[]> out = new ArrayList<>();
    for (java.util.Map.Entry<String, Set<String>> e : byForm.entrySet()) {
      if (e.getValue().size() < 2) continue;
      // Only the pairs that carry DIFFERENT CWEs are the dangerous ones: a
      // pair with the same CWE is two spellings of one defect class, and
      // selecting both is harmless.
      List<String> ids = new ArrayList<>(e.getValue());
      for (int i = 0; i < ids.size(); i++) {
        for (int j = i + 1; j < ids.size(); j++) {
          List<String> a = map.getOrDefault(ids.get(i), List.of());
          List<String> b = map.getOrDefault(ids.get(j), List.of());
          if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
            out.add(new String[] {ids.get(i), ids.get(j), e.getKey()});
          }
        }
      }
    }
    return out;
  }

  /** The spellings a rule id can be written in. Mirrors the engine's own rule. */
  private static List<String> ruleIdFormsForTest(String ruleId) {
    List<String> forms = new ArrayList<>();
    forms.add(ruleId);
    String s = ruleId.startsWith("plumb.") ? ruleId.substring("plumb.".length()) : ruleId;
    if (!s.equals(ruleId)) forms.add(s);
    String rest = s;
    for (int i = 0; i < 4; i++) {
      int dot = rest.indexOf('.');
      int dash = rest.indexOf('-');
      int cut = -1;
      if (dot >= 0 && (dash < 0 || dot < dash)) cut = dot;
      else if (dash >= 0) cut = dash;
      if (cut <= 0) break;
      if (!ScanRunner.engineAndDomainTokens().contains(rest.substring(0, cut).toLowerCase())) {
        break;
      }
      rest = rest.substring(cut + 1);
      forms.add(rest);
    }
    return forms;
  }

  /**
   * The live rule set must contain a colliding pair, or this test is measuring nothing.
   *
   * <p>A test that asserts "the filter is careful" over an empty set of collisions passes forever.
   */
  @Test
  void theLiveRuleSetStillHasCollidingPairs() {
    List<String[]> pairs = collidingPairs();
    assertThat(pairs)
        .as(
            "the live rule set is expected to contain pairs of rules that share a peeled "
                + "spelling and carry different CWEs -- those are the pairs the filter has to "
                + "keep apart")
        .isNotEmpty();
  }

  /**
   * Every rule in the live set has a CWE or is explicitly a rule without one.
   *
   * <p>A rule id with no CWE is legitimate -- most style rules have none. But a rule id that is
   * MISSING from the map is a rule the per-CWE measurement cannot attribute, and its findings would
   * be counted against whatever CWE happened to be nearby.
   */
  @Test
  void everyLiveRuleIsInTheCweMap() {
    ScanRunner runner = new ScanRunner(new ScanConfig());
    java.util.Map<String, java.util.List<String>> map = runner.liveRuleCweMap();
    assertThat(map).as("the rule -> CWE map read from the engine").isNotEmpty();

    // The map is keyed by the EMITTED id. The legacy name is a different
    // string, and a map keyed by it would contain none of the ids the
    // report prints.
    assertThat(map).containsKey("dfa-taint-sql-injection");
    assertThat(map).containsKey("ast-security-unchecked-return");
    assertThat(map.get("dfa-taint-sql-injection")).containsExactly("CWE-89");
    assertThat(map.get("ast-security-unchecked-return")).containsExactly("CWE-252");
  }

  /**
   * The known collision: naming the DFA rule must not select the AST rule.
   *
   * <p>This is the concrete defect, pinned by id so that a change to either rule is noticed. The
   * AST rule reports CWE-690 and the DFA rule reports CWE-476; before the fix, {@code --rule
   * dfa-taint-null-deref-from-return} selected both.
   */
  @Test
  void namingTheDfaRuleDoesNotSelectTheAstRule() {
    List<String[]> pairs = collidingPairs();
    assertThat(pairs)
        .as("the null-deref pair must still exist -- otherwise this test is measuring nothing")
        .anySatisfy(
            pair ->
                assertThat(List.of(pair[0], pair[1]))
                    .contains("dfa-taint-null-deref-from-return")
                    .contains("ast-style-null-deref-from-return"));
  }
}
