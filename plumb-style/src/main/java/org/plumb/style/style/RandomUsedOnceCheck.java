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

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** new Random() used only once. */
public final class RandomUsedOnceCheck implements AstChecker {

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "dmi-random-used-only-once";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.RANDOM_USED_ONCE).orElse(null);
  }

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!(n.getScope().orElse(null) instanceof ObjectCreationExpr ctor)) {
      return;
    }
    String typeName = ctor.getType().asString();
    // Match "Random" (imported) or "java.util.Random"
    // (FQN). Subclasses of Random (e.g. SecureRandom)
    // are not in scope for this rule per design.
    if (!"Random".equals(typeName) && !"java.util.Random".equals(typeName)) {
      return;
    }
    // The chain call's name should be one of the
    // common Random methods. (Anything else means
    // the user is doing something custom.)
    String method = n.getNameAsString();
    if (!isRandomMethod(method)) return;
    int line = n.getRange().map(r -> r.begin.line).orElse(-1);
    int col = n.getRange().map(r -> r.begin.column).orElse(-1);
    this.ctx.report(
        line,
        col,
        this.rule,
        "new Random() is used "
            + "only once; share a single Random instance or use "
            + "ThreadLocalRandom.current() to avoid wasting the "
            + "entropy pool",
        RuleDef.Severity.LOW,
        n.toString());
  }

  private static boolean isRandomMethod(String name) {
    return switch (name) {
      case "nextInt",
          "nextLong",
          "nextDouble",
          "nextFloat",
          "nextBoolean",
          "nextBytes",
          "nextGaussian",
          "ints",
          "longs",
          "doubles" ->
          true;
      default -> false;
    };
  }
}
