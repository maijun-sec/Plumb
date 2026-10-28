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
 * R-831 (2026-10-14): enforce the new RuleId.uuid naming
 * convention. Every AST checker RuleDef must have an id
 * matching `^(ast|dfa)-[a-z]+(-[a-z0-9]+)+$` — three
 * or more dash-separated lower-kebab segments, with the
 * first segment being the engine (`ast` or `dfa`).
 *
 * Format design (R-831):
 *   <engine>-<topic>-<slug>
 *     <engine> = ast | dfa
 *     <topic>   = checker class package tail (security, style,
 *                 error, naming, bug, concurrency, design, quality,
 *                 performance)
 *     <slug>    = kebab-case derived from the checker's
 *                 RuleId.name() (UPPER_SNAKE_CASE → lower-kebab)
 */
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.plumb.rules.RuleDef;

class R831IdNamingLintTest {

  private static final Pattern ID_PATTERN = Pattern.compile("^(ast|dfa)-[a-z]+(-[a-z0-9]+)+$");

  @Test
  void everyAstCheckerRuleIdMatchesNewFormat() {
    // R-AST-FIX-2026-10-18: skip stubs and de-dup'd no-op
    // classes. Their ruleIds deliberately violate the format
    // (e.g. "-NOT-IMPLEMENTED" / "-DEDUPED" suffixes) so the
    // disabled-by-default R-834 checkers cannot be accidentally
    // re-enabled by an operator mistake. The lint enforces
    // shape on real rules only.
    List<AstChecker> all = new AstCheckerRegistry().builtins().all();
    List<String> violations = new ArrayList<>();
    int withRule = 0;
    for (AstChecker c : all) {
      RuleDef r = c.rule();
      if (r == null) continue;
      if (!r.enabled()) continue;
      withRule++;
      String id = r.ruleId().id();
      if (!ID_PATTERN.matcher(id).matches()) {
        violations.add(c.getClass().getSimpleName() + " -> " + id);
      }
    }
    assertThat(violations)
        .as("All AST checker RuleId must match " + ID_PATTERN.pattern() + "; got violations:")
        .isEmpty();
    assertThat(withRule).isGreaterThan(500);
  }

  @Test
  void everyAstCheckerRuleIdIsUnique() {
    // R-AST-FIX-2026-10-18: same skip rule as the format test
    // above. Disabled stubs may share a ruleId with their
    // canonical owner (e.g. the dedup'd InsecureTempFilePermsCheck
    // has "-DEDUPED" but the same sourceId as the security-side
    // owner before its suffix is stripped).
    List<AstChecker> all = new AstCheckerRegistry().builtins().all();
    java.util.Set<String> seen = new java.util.HashSet<>();
    List<String> dups = new ArrayList<>();
    for (AstChecker c : all) {
      RuleDef r = c.rule();
      if (r == null) continue;
      if (!r.enabled()) continue;
      String id = r.ruleId().id();
      if (!seen.add(id)) {
        dups.add(id);
      }
    }
    assertThat(dups).as("Duplicate RuleId across AST checkers:").isEmpty();
  }
}
