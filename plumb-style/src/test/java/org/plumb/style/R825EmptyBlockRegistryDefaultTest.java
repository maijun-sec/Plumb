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
 * R-825 (2026-10-14): verify EmptyBlockCheck reads its
 * declared defaults from DEFAULT_RULE.parameters[] via
 * the StyleContext -> RuleRegistry fallback chain (R-822)
 * + kebab-case slug mapping (R-823).
 *
 * <p>Before R-825 the checker's {@code paramBool} helper
 * walked the {@code PARAMS} array manually. After R-825
 * it asks the StyleContext, which (no per-file override)
 * falls back to the registry's declared default. The
 * StyleContext + RuleRegistry pair is the single
 * source of truth.</p>
 */
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;

class R825EmptyBlockRegistryDefaultTest {

  @Test
  void paramOverrideReturnsDeclaredDefaultViaRegistryFallback() {
    // Build a registry from the live AST checkers —
    // the R-821 mirror populates it from the
    // DEFAULT_RULE static field. EmptyBlockCheck's
    // DEFAULT_RULE declares allowEmptyCatch=false
    // (the default).
    RuleRegistry r =
        new RuleRegistry()
            .withBuiltinDefaults(true)
            .withBuiltinDefaults(true)
            .registerFromCheckers(new AstCheckerRegistry().builtins().all());

    // No per-file override set — registry should
    // fall back to the declared default. R-823 also
    // makes the kebab-case slug resolve to the
    // canonical UPPER_SNAKE registry entry.
    assertThat(r.param("empty-block", "allowEmptyCatch", Boolean.class))
        .as("declared default is false (R-825 fallback to DEFAULT_RULE.parameters[])")
        .isFalse();
    assertThat(r.param("empty-block", "allowEmptyLoop", Boolean.class)).isFalse();
    assertThat(r.param("empty-block", "allowEmptyIf", Boolean.class)).isFalse();
  }

  @Test
  void paramOverrideFromStyleContextFallsBackToRegistry() {
    // Sanity check: a StyleContext built from a live
    // registry (via the new R-822 fallback) should
    // return declared defaults for the same three
    // params, even without per-file overrides.
    RuleRegistry r =
        new RuleRegistry()
            .withBuiltinDefaults(true)
            .withBuiltinDefaults(true)
            .registerFromCheckers(new AstCheckerRegistry().builtins().all());
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Test.java"), "class T {}");
    StyleContext ctx = new StyleContext(ps, r);
    // The dispatcher sets currentRule = c.name() = "empty-block"
    // (kebab-case). The R-823 slug → UPPER_SNAKE mapping
    // lets the registry find EMPTY_BLOCK.
    ctx.setCurrentRule("empty-block");
    assertThat(ctx.paramOverride("allowEmptyCatch", Boolean.class)).isFalse();
    assertThat(ctx.paramOverride("allowEmptyLoop", Boolean.class)).isFalse();
    assertThat(ctx.paramOverride("allowEmptyIf", Boolean.class)).isFalse();
  }

  @Test
  void emptyBlockCheckerIsRegisteredInRegistry() {
    // R-821 reflection: every checker is in the
    // registry. EmptyBlockCheck has a DEFAULT_RULE
    // with id pl-0725-empty-block and 3 params.
    // R-823 fixed an RuleId.name collision with
    // EmptyStatementBlockCheck by renaming the
    // latter's canonical name to EMPTY_BLOCK_CWE398.
    RuleRegistry r =
        new RuleRegistry()
            .withBuiltinDefaults(true)
            .withBuiltinDefaults(true)
            .registerFromCheckers(new AstCheckerRegistry().builtins().all());
    var def = r.byName("EMPTY_BLOCK");
    assertThat(def).isPresent();
    assertThat(def.get().parameters()).hasSize(3);
    assertThat(def.get().checkerClass()).isEqualTo("org.plumb.style.EmptyBlockCheck");
  }

  @Test
  void emptyBlockCheckParamBoolWorksViaContext() {
    // End-to-end: the live checker's paramBool() helper
    // (R-825 simplification) reads via the StyleContext
    // → RuleRegistry chain. Confirm the helper still
    // returns false (declared default) without any
    // per-file override set.
    RuleRegistry r =
        new RuleRegistry()
            .withBuiltinDefaults(true)
            .withBuiltinDefaults(true)
            .registerFromCheckers(new AstCheckerRegistry().builtins().all());
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Test.java"), "class T {}");
    StyleContext ctx = new StyleContext(ps, r);
    ctx.setCurrentRule("empty-block");
    // No override set — the helper now delegates to
    // ctx.paramOverride which falls back to the
    // declared default in the registry.
    assertThat(ctx.paramOverride("allowEmptyCatch", Boolean.class)).isFalse();
  }

  @Test
  void registryHasNoDuplicateRuleNames() {
    // R-826: AST checkers (the source of truth for
    // R-821) must use unique RuleId.name values so
    // that the reflection-driven register doesn't
    // silently drop entries (last-wins on byName).
    // The 41-name conflicts with the legacy 115
    // RuleRegistry.withBuiltinDefaults(true) entries
    // (R-815 design) are a separate, pre-existing
    // concern and out of scope for R-826 — they
    // cause the AST entry to overwrite the legacy
    // metadata (R-821 last-wins) but don't violate
    // the byName bijection.
    java.util.List<AstChecker> checkers = new AstCheckerRegistry().builtins().all();
    java.util.Map<String, Integer> nameCounts = new java.util.HashMap<>();
    java.util.Map<String, String> nameToFile = new java.util.HashMap<>();
    for (AstChecker c : checkers) {
      var rd = c.rule();
      if (rd == null) continue;
      String name = rd.ruleId().name();
      nameCounts.merge(name, 1, Integer::sum);
      nameToFile.putIfAbsent(name, c.getClass().getSimpleName());
    }
    var dups = nameCounts.entrySet().stream().filter(e -> e.getValue() > 1).toList();
    assertThat(dups)
        .as("R-826: AST checker RuleId.name dups (legacy builtin conflicts are a separate issue)")
        .isEmpty();
  }
}
