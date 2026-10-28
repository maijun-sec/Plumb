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
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** same no-arg method called twice : store in a local (RV_REMOVE_REPEAT_METHOD_CALLS). */
public final class RvRemoveRepeatCallsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-rv-remove-repeat-calls", "RV_REMOVE_REPEAT_CALLS"),
          "same no-arg method called twice : store in a local (RV_REMOVE_REPEAT_METHOD_CALLS)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RvRemoveRepeatCallsCheck",
          true,
          List.of("ast-style-remove-repeat-method-calls", "rv-remove-repeat-calls"));

  @Override
  public String name() {
    return "rv-remove-repeat-calls";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(com.github.javaparser.ast.body.MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    Map<String, Integer> callCount = new HashMap<>();
    for (Statement s : body.getStatements()) {
      String text = s.toString();
      // Crude: same method-call substring appears 2+ times in the body.
      for (MethodCallExpr mc : s.findAll(MethodCallExpr.class)) {
        if (mc.getArguments().isEmpty()) {
          String key = mc.toString();
          callCount.merge(key, 1, Integer::sum);
        }
      }
    }
    for (java.util.Map.Entry<String, Integer> e : callCount.entrySet()) {
      if (e.getValue() >= 2) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Method '" + e.getKey() + "' called " + e.getValue() + " times : store in a local",
            RuleDef.Severity.LOW,
            e.getKey());
      }
    }
  }
}
