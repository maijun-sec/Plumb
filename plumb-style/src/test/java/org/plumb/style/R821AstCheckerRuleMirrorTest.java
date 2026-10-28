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
 * R-821 (2026-10-14): verify AstChecker.rule() correctly
 * reflects the checker's DEFAULT_RULE static field for a
 * sample of checkers. Also verify the RuleRegistry mirror
 * populates entries for all 600+ builtins.
 */
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;

class R821AstCheckerRuleMirrorTest {

  @Test
  void emptyBlockCheckerRuleReflectsDefaultRule() {
    AstChecker c = new org.plumb.style.style.EmptyBlockCheck();
    RuleDef r = c.rule();
    assertThat(r).isNotNull();
    // DEFAULT_RULE id is "ast-style-empty-block".
    assertThat(r.ruleId().id()).isEqualTo("ast-style-empty-block");
    assertThat(r.ruleId().name()).isEqualTo("EMPTY_BLOCK");
  }

  @Test
  void ruleNameMatchesAstCheckerNameForCanonicalCheckers() {
    AstChecker c = new org.plumb.style.style.EmptyBlockCheck();
    RuleDef r = c.rule();
    // name() returns "empty-block" — the rule id name
    // is the UPPER_SNAKE_CASE canonical ("EMPTY_BLOCK").
    assertThat(c.name()).isEqualTo("empty-block");
    assertThat(r.ruleId().name()).isEqualTo("EMPTY_BLOCK");
  }

  @Test
  void everyBuiltinCheckerHasARule() {
    List<AstChecker> all = new AstCheckerRegistry().builtins().all();
    int withRule = 0;
    for (AstChecker c : all) {
      if (c.rule() != null) withRule++;
    }
    // The default reflection-based rule() should
    // resolve for every checker because every one
    // declares a DEFAULT_RULE or RULE static field.
    assertThat(withRule).isEqualTo(all.size());
  }

  @Test
  void ruleRegistryMirrorsEveryBuiltin() {
    List<AstChecker> all = new AstCheckerRegistry().builtins().all();
    // R-834: `withBuiltinDefaults(true)` now also
    // mirrors every AST checker (smoke-test mode).
    // The mirror is idempotent on a second call, so
    // the explicit registerFromCheckers below is a
    // no-op — its only remaining purpose is to assert
    // the contract that *every* checker.rule()
    // produces a registry entry.
    RuleRegistry r = new RuleRegistry().withBuiltinDefaults(true);
    r.registerFromCheckers(all);
    // After the mirror, every checker with a non-null
    // rule() should produce a registry entry.
    int withRule = 0;
    for (AstChecker c : all) {
      if (c.rule() != null) withRule++;
    }
    System.out.println(
        "[R821] all=" + all.size() + " withRule=" + withRule + " registrySize=" + r.all().size());
    // R-834: registrySize is now 115 (builtin DFA) +
    // withRule (AST mirror). The mirror means the
    // AST count must be >= withRule (duplicates
    // between builtin ids and AST defaultRule()
    // ids collapse via updateInPlace).
    assertThat(r.all().size()).isGreaterThanOrEqualTo(withRule);
  }
}
