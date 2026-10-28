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
package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** test method should include at least one assertion. */
public final class TestWithoutAssertionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-test-no-assertion", "TEST_NO_ASSERTION"),
          "test method should include at least one assertion",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of(),
          "org.plumb.style.TestWithoutAssertionCheck",
          true,
          List.of("ast-style-no-assertion", "test-no-assertion"));

  /** Method names that count as "assertions" in tests. */
  private static final Set<String> ASSERTION_PREFIXES = Set.of("assert", "fail", "verify");

  /** Method names that count as assertions regardless of prefix. */
  private static final Set<String> ASSERTION_NAMES =
      Set.of("assertThrows", "assertDoesNotThrow", "assertAll", "expectThrows", "fail", "verify");

  @Override
  public String name() {
    return "test-no-assertion";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Must be a test method: annotated with @Test
    // or @ParameterizedTest. Other annotations are
    // out of scope (could be a helper, not a test).
    boolean isTest =
        n.getAnnotations().stream()
            .anyMatch(
                a -> {
                  String name = a.getNameAsString();
                  return "Test".equals(name) || "ParameterizedTest".equals(name);
                });
    if (!isTest) return;
    // Walk the body and look for any method call
    // whose name matches an assertion.
    final boolean[] hasAssertion = {false};
    n.walk(
        com.github.javaparser.ast.stmt.Statement.class,
        s -> {
          if (hasAssertion[0]) return;
          s.walk(
              MethodCallExpr.class,
              mc -> {
                if (hasAssertion[0]) return;
                String name = mc.getNameAsString();
                if (ASSERTION_NAMES.contains(name)) {
                  hasAssertion[0] = true;
                  return;
                }
                for (String prefix : ASSERTION_PREFIXES) {
                  if (name.startsWith(prefix)) {
                    hasAssertion[0] = true;
                    return;
                  }
                }
              });
        });
    if (!hasAssertion[0]) {
      ctx.report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          DEFAULT_RULE,
          "Test method '" + n.getNameAsString() + "' has no assertions (always passes)",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
    }
  }
}
