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
package org.plumb.style.bug;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** loading a value known to be null (NP_LOAD_OF_KNOWN_NULL_VALUE). */
public final class NpLoadOfKnownNullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-np-load-null", "NP_LOAD_NULL"),
          "loading a value known to be null (NP_LOAD_OF_KNOWN_NULL_VALUE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-476"),
          "org.plumb.style.NpLoadOfKnownNullCheck",
          true,
          List.of("ast-bug-load-of-known-null-value", "np-load-null"));

  @Override
  public String name() {
    return "np-load-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (n.getScope().isEmpty()) return;
    // Heuristic: the receiver is a method call
    // whose name ends with "OrNull" or starts
    // with "find" / "peek" / "poll".
    if (!(n.getScope().get() instanceof MethodCallExpr)) return;
    MethodCallExpr receiver = (MethodCallExpr) n.getScope().get();
    String name = receiver.getNameAsString();
    if (name.endsWith("OrNull")
        || name.startsWith("find")
        || name.startsWith("peek")
        || name.startsWith("poll")) {
      // A chained call that doesn't null-check.
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method '" + name + "' can return null : " + "add a null check before chaining",
          RuleDef.Severity.MEDIUM,
          name);
    }
  }
}
