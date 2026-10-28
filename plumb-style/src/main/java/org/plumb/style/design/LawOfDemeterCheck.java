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
package org.plumb.style.design;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method chain exceeds LoD threshold (use a wrapper method instead). */
public final class LawOfDemeterCheck implements AstChecker {

  private static final int DEFAULT_THRESHOLD = 1;

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-law-of-demeter", "LAW_OF_DEMETER"),
          "method chain exceeds LoD threshold (use a wrapper method instead)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-1060"),
          "org.plumb.style.LawOfDemeterCheck",
          true,
          List.of("ast-design-of-demeter", "law-of-demeter"));

  @Override
  public String name() {
    return "law-of-demeter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    for (MethodCallExpr m : n.findAll(MethodCallExpr.class)) {
      int chain = 0;
      com.github.javaparser.ast.expr.Expression s = m.getScope().orElse(null);
      while (s instanceof MethodCallExpr || s instanceof FieldAccessExpr) {
        chain++;
        if (s instanceof MethodCallExpr mc) {
          s = mc.getScope().orElse(null);
        } else if (s instanceof FieldAccessExpr fa) {
          s = fa.getScope();
        } else break;
      }
      if (chain > DEFAULT_THRESHOLD) {
        int line = m.getBegin().map(p -> p.line).orElse(-1);
        int col = m.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Method chain length " + chain + " violates LoD (>" + DEFAULT_THRESHOLD + ")",
            RuleDef.Severity.LOW,
            m.getNameAsString());
      }
    }
  }
}
